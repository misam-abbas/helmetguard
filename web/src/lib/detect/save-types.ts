// What the video page sends to the server actions in app/(dash)/detect/actions.ts.
import type { Corners } from "./tracking";

export type SaveResult = { ok: true } | { ok: false; error: string };

export type VideoSessionStart = {
  sessionId: string;
  /** A random key this browser keeps, so its analyses stay grouped under one device. */
  browserKey: string;
  /** "Chrome on Windows", for the device's label. */
  browser: string;
  fileName: string;
  durationMs: number;
  modelVersion: string;
  sampleRate: number;
};

export type VideoSessionEnd = {
  sessionId: string;
  status: "completed" | "cancelled" | "interrupted";
  framesProcessed: number;
  avgFps: number;
  motorcyclesTotal: number;
  ridersTotal: number;
  ridersHelmet: number;
  ridersNoHelmet: number;
  ridersUnknown: number;
  violationsSaved: number;
};

/** The JSON part of an alert; its photos travel beside it in the same form. */
export type VideoViolationData = {
  sessionId: string;
  violationId: string;
  browserKey: string;
  trackId: number;
  videoTimestampMs: number;
  frameTimestampMs: number;
  riders: { role: "driver" | "pillion"; status: "no_helmet" | "helmet"; confidence: number; box: Corners }[];
  helmetConfidence: number;
  motorcycleConfidence: number;
  framesObserved: number;
  noHelmetFrames: number;
  modelVersion: string;
  bikeBox: Corners;
  photos: Record<"full_frame" | "motorcycle" | "thumb", { width: number; height: number }>;
};

export const PHOTO_KINDS = ["full_frame", "motorcycle", "thumb"] as const;
