import { describe, expect, it } from "vitest";
import { DEFAULT_RULES, Track, Tracker } from "../src/lib/detect/tracking";
import { iou, letterboxMapping, nms, parseOutput, parseRaw, toSource, type Box } from "../src/lib/detect/yolo";

const box = (x1: number, y1: number, x2: number, y2: number): Box => ({ x1, y1, x2, y2 });

describe("parseRaw", () => {
  // Two candidates, two classes: [cx, cy, w, h, class0, class1] stored channel by channel.
  const dims = [1, 6, 2];
  const data = Float32Array.from([
    100, 300, // cx
    100, 300, // cy
    40, 20, // w
    20, 20, // h
    0.9, 0.1, // class 0
    0.05, 0.3, // class 1
  ]);

  it("reads centre boxes into corners and picks the best class", () => {
    const found = parseRaw(data, dims, 0.25);
    expect(found).toHaveLength(2);
    expect(found[0]).toMatchObject({ cls: 0, box: box(80, 90, 120, 110) });
    expect(found[0].score).toBeCloseTo(0.9);
    expect(found[1]).toMatchObject({ cls: 1, box: box(290, 290, 310, 310) });
  });

  it("drops low scores", () => {
    expect(parseRaw(data, dims, 0.5)).toHaveLength(1);
  });

  it("refuses an output it does not understand", () => {
    expect(() => parseRaw(data, [1, 2100, 7], 0.25)).not.toThrow();
    expect(() => parseRaw(data, [1, 4, 3], 0.25)).toThrow(/Unexpected/);
  });
});

describe("parseOutput", () => {
  it("treats a two-class raw output, six rows deep, as raw", () => {
    const raw = Float32Array.from([100, 300, 100, 300, 40, 20, 20, 20, 0.9, 0.1, 0.05, 0.3]);
    expect(parseOutput(raw, [1, 6, 2], 0.25)).toHaveLength(2);
  });

  it("reads an end-to-end output of corner boxes, six columns wide", () => {
    const rows = Float32Array.from([
      10, 20, 50, 80, 0.8, 1, // kept
      5, 5, 9, 9, 0.1, 0, // below the cut-off
    ]);
    const found = parseOutput(rows, [1, 2, 6], 0.25);
    expect(found).toHaveLength(1);
    expect(found[0]).toMatchObject({ cls: 1, box: box(10, 20, 50, 80) });
    expect(found[0].score).toBeCloseTo(0.8);
  });
});

describe("nms", () => {
  it("keeps the best of overlapping boxes of one class only", () => {
    const kept = nms(
      [
        { box: box(0, 0, 10, 10), cls: 0, score: 0.6 },
        { box: box(1, 1, 11, 11), cls: 0, score: 0.9 },
        { box: box(1, 1, 11, 11), cls: 1, score: 0.5 },
      ],
      0.5,
    );
    expect(kept.map((k) => [k.cls, k.score])).toEqual([
      [0, 0.9],
      [1, 0.5],
    ]);
  });
});

describe("letterbox mapping", () => {
  it("maps model pixels back to the original frame", () => {
    // A 200 x 100 crop at (50, 20) into a 320 input: scale 1.6, padded 80 px top and bottom.
    const m = letterboxMapping(box(50, 20, 250, 120), 320);
    expect(m.scale).toBeCloseTo(1.6);
    expect(m.padY).toBeCloseTo(80);
    expect(toSource(box(0, 80, 320, 240), m)).toEqual(box(50, 20, 250, 120));
  });

  it("measures overlap", () => {
    expect(iou(box(0, 0, 10, 10), box(5, 0, 15, 10))).toBeCloseTo(1 / 3);
    expect(iou(box(0, 0, 1, 1), box(5, 5, 6, 6))).toBe(0);
  });
});

describe("tracking and the no-helmet rule", () => {
  const bare = (score = 0.8) => ({ box: box(0, 0, 1, 1), bare: true, score });
  const helmet = (score = 0.8) => ({ box: box(0, 0, 1, 1), bare: false, score });

  it("follows a moving bike as one track", () => {
    const tracker = new Tracker();
    const first = tracker.update([{ box: box(0, 0, 100, 200), score: 0.9 }], 0);
    const second = tracker.update([{ box: box(10, 0, 110, 200), score: 0.9 }], 250);
    expect(second[0]).toBe(first[0]);
    expect(second[0].hits).toBe(2);
    expect(tracker.update([{ box: box(500, 0, 600, 200), score: 0.9 }], 500)[0].id).not.toBe(first[0].id);
  });

  it("forgets a bike after it has been missing for a while", () => {
    const tracker = new Tracker(0.3, 1);
    const [bike] = tracker.update([{ box: box(0, 0, 100, 100), score: 0.9 }], 0);
    tracker.update([], 250);
    tracker.update([], 500);
    const [later] = tracker.update([{ box: box(0, 0, 100, 100), score: 0.9 }], 750);
    expect(later).not.toBe(bike);
  });

  it("never flags a bike from one bad frame", () => {
    const t = new Track(1, box(0, 0, 1, 1), 0.9, 0);
    t.observe([bare()], 0);
    expect(t.suspect).toBe(true);
    expect(t.flagged()).toBe(false);
  });

  it("flags a bike after enough bare-head frames over enough time", () => {
    const t = new Track(1, box(0, 0, 1, 1), 0.9, 0);
    for (let i = 0; i < DEFAULT_RULES.minBareFrames; i++) t.observe([bare()], i * 250);
    expect(t.flagged()).toBe(true);
  });

  it("does not flag when helmets are seen most of the time", () => {
    const t = new Track(1, box(0, 0, 1, 1), 0.9, 0);
    for (let i = 0; i < 4; i++) t.observe([bare()], i * 250);
    for (let i = 4; i < 12; i++) t.observe([helmet()], i * 250);
    expect(t.flagged()).toBe(false);
  });

  it("ignores unsure heads and bursts shorter than half a second", () => {
    const unsure = new Track(1, box(0, 0, 1, 1), 0.9, 0);
    for (let i = 0; i < 6; i++) unsure.observe([bare(0.4)], i * 250);
    expect(unsure.bareFrames).toBe(0);

    const burst = new Track(2, box(0, 0, 1, 1), 0.9, 0);
    for (let i = 0; i < 6; i++) burst.observe([bare()], i * 50);
    expect(burst.flagged()).toBe(false);
  });

  it("counts a frame with any bare head on the bike", () => {
    const t = new Track(1, box(0, 0, 1, 1), 0.9, 0);
    t.observe([helmet(), bare()], 0);
    expect([t.bareFrames, t.helmetFrames]).toEqual([1, 0]);
  });
});
