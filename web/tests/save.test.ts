import { describe, expect, it } from "vitest";
import { corners, sessionTotals, violationForm } from "../src/lib/detect/save";
import { DEFAULT_RULES, Tracker, type Capture } from "../src/lib/detect/tracking";
import type { Box } from "../src/lib/detect/yolo";

const box = (x1: number, y1: number, x2: number, y2: number): Box => ({ x1, y1, x2, y2 });
const bare = (score = 0.8) => ({ box: box(10, 10, 20, 20), bare: true, score });
const helmet = (score = 0.8) => ({ box: box(10, 10, 20, 20), bare: false, score });

/** A tracker that has followed one bike for `frames` frames, observing `heads` in each. */
function followed(frames: number, heads: () => ReturnType<typeof bare>[]) {
  const tracker = new Tracker();
  for (let i = 0; i < frames; i++) {
    const [track] = tracker.update([{ box: box(0, 0, 100, 100), score: 0.9 }], i * 250);
    track.observe(heads(), i * 250);
  }
  return tracker;
}

const jpeg = "data:image/jpeg;base64,/9j/2wBDAP8=";
const capture: Capture = {
  timeMs: 750,
  frame: { dataUrl: jpeg, width: 1280, height: 720 },
  bike: { dataUrl: jpeg, width: 200, height: 300 },
  thumb: { dataUrl: jpeg, width: 320, height: 240 },
  bikeBox: [0.1, 0.2, 0.3, 0.6],
  heads: [
    { bare: false, score: 0.6, box: [0.15, 0.2, 0.18, 0.25] },
    { bare: true, score: 0.9, box: [0.2, 0.2, 0.23, 0.25] },
  ],
};

describe("corners", () => {
  it("gives a box as shares of the frame, clamped to it", () => {
    expect(corners(box(-10, 36, 640, 900), 1280, 720)).toEqual([0, 0.05, 0.5, 1]);
  });
});

describe("sessionTotals", () => {
  it("counts a flagged bike as a rider without a helmet", () => {
    const totals = sessionTotals(followed(DEFAULT_RULES.minBareFrames + 2, () => [bare()]));
    expect(totals).toEqual({ motorcyclesTotal: 1, ridersTotal: 1, ridersHelmet: 0, ridersNoHelmet: 1, ridersUnknown: 0 });
  });

  it("counts a bike with helmets as a rider with a helmet", () => {
    expect(sessionTotals(followed(5, () => [helmet()])).ridersHelmet).toBe(1);
  });

  it("leaves a bike with no clear head undecided, and ignores bikes seen too briefly", () => {
    expect(sessionTotals(followed(5, () => [])).ridersUnknown).toBe(1);
    expect(sessionTotals(followed(2, () => [helmet()])).motorcyclesTotal).toBe(0);
  });
});

describe("violationForm", () => {
  it("sends the alert's details and its three photos, the clearest head first", async () => {
    const tracker = followed(6, () => [bare(0.7)]);
    const track = tracker.tracks[0];
    track.capture = capture;
    const ids = { sessionId: "11111111-1111-4111-8111-111111111111", violationId: "22222222-2222-4222-8222-222222222222", browserKey: "browser-key-1" };
    const form = violationForm(track, ids, "yolo11n-v3");
    expect(form).not.toBeNull();
    const data = JSON.parse(String(form!.get("data")));
    expect(data).toMatchObject({ ...ids, trackId: track.id, frameTimestampMs: 750, noHelmetFrames: 6, framesObserved: 6, modelVersion: "yolo11n-v3" });
    expect(data.videoTimestampMs).toBe(0);
    expect(data.riders).toEqual([
      { role: "driver", status: "no_helmet", confidence: 0.9, box: [0.2, 0.2, 0.23, 0.25] },
      { role: "pillion", status: "helmet", confidence: 0.6, box: [0.15, 0.2, 0.18, 0.25] },
    ]);
    for (const kind of ["full_frame", "motorcycle", "thumb"]) {
      const file = form!.get(kind) as Blob;
      expect(file.type).toBe("image/jpeg");
      expect(file.size).toBeGreaterThan(0);
    }
  });

  it("has nothing to send without photos", () => {
    const track = followed(6, () => [bare()]).tracks[0];
    expect(violationForm(track, { sessionId: "a", violationId: "b", browserKey: "c" }, "v")).toBeNull();
  });
});
