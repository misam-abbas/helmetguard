"use server";

import { createHash, randomUUID } from "node:crypto";
import { revalidatePath } from "next/cache";
import { requireStaff } from "@/lib/auth";
import { PHOTO_KINDS, type SaveResult, type VideoSessionEnd, type VideoSessionStart, type VideoViolationData } from "@/lib/detect/save-types";

// A video analysed on the website is saved like one analysed in the Android app: a device for each
// browser and account, a detection session for the video, and an alert with photos for each bike
// flagged without a helmet, at the app's paths in the private evidence bucket. It all goes through
// the signed-in user's own session, so the database's row rules apply exactly as for the app, and
// the alerts reach Violations, the dashboard and the analytics like the app's.

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const BROWSER_KEY = /^[0-9a-z-]{8,64}$/i;
const MAX_PHOTO_BYTES = 5 * 1024 * 1024; // the evidence bucket's limit
const STATUSES = new Set(["completed", "cancelled", "interrupted"]);
const INVALID: SaveResult = { ok: false, error: "The analysis sent details the server could not use." };

/** One browser and one account always give the same device; two accounts on a browser never share one. */
function deviceIdFor(userId: string, browserKey: string): string {
  const hex = createHash("sha256").update(`web:${userId}:${browserKey}`).digest("hex");
  const variant = ((parseInt(hex[16], 16) & 3) | 8).toString(16);
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-5${hex.slice(13, 16)}-${variant}${hex.slice(17, 20)}-${hex.slice(20, 32)}`;
}

function count(value: unknown): number {
  const n = Math.round(Number(value));
  return Number.isFinite(n) && n >= 0 ? n : 0;
}

function share(value: unknown): number | null {
  const n = Number(value);
  return Number.isFinite(n) ? Math.min(1, Math.max(0, n)) : null;
}

function corners(value: unknown): number[] | null {
  if (!Array.isArray(value) || value.length !== 4) return null;
  const c = value.map(share);
  return c.every((v) => v !== null) ? (c as number[]) : null;
}

export async function startVideoSession(input: VideoSessionStart): Promise<SaveResult> {
  if (!UUID.test(String(input?.sessionId)) || !BROWSER_KEY.test(String(input?.browserKey))) return INVALID;
  const { supabase, userId } = await requireStaff();
  const now = new Date().toISOString();
  const deviceId = deviceIdFor(userId, input.browserKey);
  const modelVersion = String(input.modelVersion ?? "").slice(0, 60);

  const { error: deviceError } = await supabase.from("devices").upsert({
    id: deviceId,
    owner_id: userId,
    label: `Website, ${String(input.browser ?? "browser").slice(0, 60)}`,
    model: "Web browser",
    app_version: "web",
    model_version: modelVersion,
    last_sync_at: now,
  });
  if (deviceError) return { ok: false, error: deviceError.message };

  const { error } = await supabase.from("detection_sessions").insert({
    id: input.sessionId,
    device_id: deviceId,
    operator_id: userId,
    source: "video",
    started_at: now,
    status: "active",
    video_file_name: String(input.fileName ?? "").slice(0, 200),
    video_duration_ms: count(input.durationMs),
    settings: { app: "website", frames_per_second: count(input.sampleRate) },
    model_version: modelVersion,
  });
  if (error) return { ok: false, error: error.message };
  return { ok: true };
}

export async function saveVideoViolation(form: FormData): Promise<SaveResult> {
  let data: VideoViolationData;
  try {
    data = JSON.parse(String(form.get("data") ?? ""));
  } catch {
    return INVALID;
  }
  if (!UUID.test(String(data?.sessionId)) || !UUID.test(String(data?.violationId)) || !BROWSER_KEY.test(String(data?.browserKey))) return INVALID;
  const { supabase, userId } = await requireStaff();
  const deviceId = deviceIdFor(userId, data.browserKey);

  // The photos first, at fixed paths, so a retry overwrites instead of adding copies.
  const evidence: Record<string, unknown>[] = [];
  for (const kind of PHOTO_KINDS) {
    const file = form.get(kind);
    if (!(file instanceof Blob) || file.size === 0) continue;
    if (file.size > MAX_PHOTO_BYTES || file.type !== "image/jpeg") return { ok: false, error: "A photo was too large or not a JPEG." };
    const bytes = Buffer.from(await file.arrayBuffer());
    const path = `${userId}/${data.violationId}/${kind}_0.jpg`;
    const { error } = await supabase.storage.from("evidence").upload(path, bytes, { contentType: "image/jpeg", upsert: true });
    if (error) return { ok: false, error: `The photos could not be stored: ${error.message}` };
    const size = data.photos?.[kind];
    const bbox = kind === "full_frame" ? corners(data.bikeBox) : null;
    evidence.push({
      id: randomUUID(),
      kind,
      seq: 0,
      storage_path: path,
      width: count(size?.width),
      height: count(size?.height),
      bytes: bytes.length,
      sha256: createHash("sha256").update(bytes).digest("hex"),
      ...(bbox ? { bbox } : {}),
      frame_timestamp_ms: count(data.frameTimestampMs),
      is_enhanced: false,
    });
  }

  const riders = (Array.isArray(data.riders) ? data.riders : []).slice(0, 6).map((r) => {
    const box = corners(r?.box);
    return {
      role: r?.role === "pillion" ? "pillion" : "driver",
      status: r?.status === "helmet" ? "helmet" : "no_helmet",
      confidence: share(r?.confidence) ?? 0,
      ...(box ? { box } : {}),
    };
  });
  const now = new Date().toISOString();

  // The same server function the Android app syncs its alerts through.
  const { error } = await supabase.rpc("sync_violation", {
    payload: {
      id: data.violationId,
      session_id: data.sessionId,
      device_id: deviceId,
      track_id: count(data.trackId),
      source: "video",
      detected_at: now,
      video_timestamp_ms: count(data.videoTimestampMs),
      rider_count: Math.max(1, riders.length),
      no_helmet_count: Math.max(1, riders.filter((r) => r.status === "no_helmet").length),
      riders,
      helmet_policy: "all_riders",
      helmet_confidence: share(data.helmetConfidence),
      motorcycle_confidence: share(data.motorcycleConfidence),
      frames_observed: count(data.framesObserved),
      no_helmet_frames: count(data.noHelmetFrames),
      plate_ocr_status: "no_plate_detected",
      plate_candidates: [],
      model_version: String(data.modelVersion ?? "").slice(0, 60),
      app_version: "web",
      client_created_at: now,
      evidence,
    },
  });
  if (error) return { ok: false, error: error.message };
  revalidatePath("/violations");
  revalidatePath("/dashboard");
  return { ok: true };
}

export async function finishVideoSession(input: VideoSessionEnd): Promise<SaveResult> {
  if (!UUID.test(String(input?.sessionId)) || !STATUSES.has(String(input?.status))) return INVALID;
  const { supabase } = await requireStaff();
  const avgFps = Number(input.avgFps);
  const { error } = await supabase
    .from("detection_sessions")
    .update({
      ended_at: new Date().toISOString(),
      status: input.status,
      frames_processed: count(input.framesProcessed),
      avg_fps: Number.isFinite(avgFps) && avgFps >= 0 ? Math.round(avgFps * 100) / 100 : null,
      motorcycles_total: count(input.motorcyclesTotal),
      riders_total: count(input.ridersTotal),
      riders_helmet: count(input.ridersHelmet),
      riders_no_helmet: count(input.ridersNoHelmet),
      riders_unknown: count(input.ridersUnknown),
      violations_confirmed: count(input.violationsSaved),
    })
    .eq("id", input.sessionId);
  if (error) return { ok: false, error: error.message };
  revalidatePath("/sessions");
  revalidatePath("/dashboard");
  revalidatePath("/analytics");
  return { ok: true };
}
