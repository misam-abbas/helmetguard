// Browser side of saving a video analysis: what the server actions in app/(dash)/detect/actions.ts get.
import type { VideoSessionEnd, VideoViolationData } from "./save-types";
import type { Corners, Track, Tracker } from "./tracking";
import type { Box } from "./yolo";

const KEY_NAME = "helmetguard-browser";

/** A random key kept in this browser, so its analyses stay grouped under one device. */
export function browserKey(): string {
  try {
    const saved = window.localStorage.getItem(KEY_NAME);
    if (saved && /^[0-9a-z-]{8,64}$/i.test(saved)) return saved;
    const fresh = crypto.randomUUID();
    window.localStorage.setItem(KEY_NAME, fresh);
    return fresh;
  } catch {
    // Storage blocked (a private window): this visit counts as its own device.
    return crypto.randomUUID();
  }
}

/** "Chrome on Windows", for the device's label on the website. */
export function browserName(): string {
  const ua = navigator.userAgent;
  const browser = /Edg\//.test(ua) ? "Edge" : /OPR\//.test(ua) ? "Opera" : /Firefox\//.test(ua) ? "Firefox" : /Chrome\//.test(ua) ? "Chrome" : /Safari\//.test(ua) ? "Safari" : "browser";
  const os = /Windows/.test(ua) ? "Windows" : /Android/.test(ua) ? "Android" : /iPhone|iPad/.test(ua) ? "iOS" : /Mac OS X/.test(ua) ? "macOS" : /Linux/.test(ua) ? "Linux" : "";
  return os ? `${browser} on ${os}` : browser;
}

/** A box as shares of the frame, as the Android app stores boxes. */
export function corners(box: Box, frameWidth: number, frameHeight: number): Corners {
  const clamp = (v: number) => Math.min(1, Math.max(0, v));
  return [clamp(box.x1 / frameWidth), clamp(box.y1 / frameHeight), clamp(box.x2 / frameWidth), clamp(box.y2 / frameHeight)];
}

function blobOf(dataUrl: string): Blob {
  const comma = dataUrl.indexOf(",");
  const type = /^data:([^;,]+)/.exec(dataUrl)?.[1] ?? "image/jpeg";
  const binary = atob(dataUrl.slice(comma + 1));
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return new Blob([bytes], { type });
}

/** The alert for one flagged bike, with its three photos, or null if it has no photos. */
export function violationForm(track: Track, ids: { sessionId: string; violationId: string; browserKey: string }, modelVersion: string): FormData | null {
  const capture = track.capture;
  if (!capture) return null;
  // The clearest head first. Which head is the driver isn't known on the web, so the first counts as one.
  const heads = [...capture.heads].sort((a, b) => b.score - a.score);
  const data: VideoViolationData = {
    ...ids,
    trackId: track.id,
    videoTimestampMs: Number.isFinite(track.firstBareMs) ? Math.round(track.firstBareMs) : Math.round(capture.timeMs),
    frameTimestampMs: Math.round(capture.timeMs),
    riders: heads.map((h, i) => ({ role: i === 0 ? "driver" : "pillion", status: h.bare ? "no_helmet" : "helmet", confidence: Math.round(h.score * 1000) / 1000, box: h.box })),
    helmetConfidence: track.averageBareScore,
    motorcycleConfidence: track.score,
    framesObserved: track.hits,
    noHelmetFrames: track.bareFrames,
    modelVersion,
    bikeBox: capture.bikeBox,
    photos: {
      full_frame: { width: capture.frame.width, height: capture.frame.height },
      motorcycle: { width: capture.bike.width, height: capture.bike.height },
      thumb: { width: capture.thumb.width, height: capture.thumb.height },
    },
  };
  const form = new FormData();
  form.set("data", JSON.stringify(data));
  form.set("full_frame", blobOf(capture.frame.dataUrl), "full_frame.jpg");
  form.set("motorcycle", blobOf(capture.bike.dataUrl), "motorcycle.jpg");
  form.set("thumb", blobOf(capture.thumb.dataUrl), "thumb.jpg");
  return form;
}

/**
 * The video's totals for its detection session. The web follows bikes, not each rider on them, so
 * every counted bike is one rider decision: without a helmet if flagged, with one if helmets
 * outnumbered bare heads, otherwise undecided.
 */
export function sessionTotals(tracker: Tracker): Pick<VideoSessionEnd, "motorcyclesTotal" | "ridersTotal" | "ridersHelmet" | "ridersNoHelmet" | "ridersUnknown"> {
  const bikes = tracker.counted();
  let helmet = 0;
  let bare = 0;
  for (const t of bikes) {
    if (t.flagged()) bare += 1;
    else if (t.helmetFrames > 0 && t.helmetFrames >= t.bareFrames) helmet += 1;
  }
  return { motorcyclesTotal: bikes.length, ridersTotal: bikes.length, ridersHelmet: helmet, ridersNoHelmet: bare, ridersUnknown: bikes.length - helmet - bare };
}
