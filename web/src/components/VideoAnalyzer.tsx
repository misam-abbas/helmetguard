"use client";

import { Download, Play, Square, Upload } from "lucide-react";
import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { finishVideoSession, saveVideoViolation, startVideoSession } from "@/app/(dash)/detect/actions";
import { detect, loadModels, photo, scratch, threadCount, thumbnail } from "@/lib/detect/analyzer";
import { browserKey, browserName, corners, sessionTotals, violationForm } from "@/lib/detect/save";
import type { VideoSessionEnd } from "@/lib/detect/save-types";
import { DEFAULT_RULES, MAX_MISSED_FRAMES, Tracker, type Head, type Track } from "@/lib/detect/tracking";
import { clampBox, grow, height, width, type Box } from "@/lib/detect/yolo";
import { buttonClass, Card, cn, EmptyState, inputClass, Notice, secondaryButtonClass } from "./ui";

const SAMPLE_RATES = [2, 4, 8];
const MAX_FRAME_WIDTH = 1280;
// Same cut-offs as the Android app.
const STAGE1_MIN_SCORE = 0.35;
const STAGE2_MIN_SCORE = 0.25;
// Margin around each bike for the head model, as in training and the Android app.
const DETAIL_PADDING = 0.08;
// The largest bikes in a frame get the detail check; a crowded frame would otherwise take too long.
const MAX_BIKES_PER_FRAME = 6;
const MIN_BIKE_PX = 16;

type Status = "idle" | "loading" | "running" | "done" | "stopped" | "error";

type Result = {
  id: number;
  firstMs: number;
  lastMs: number;
  bareFrames: number;
  decidedFrames: number;
  score: number;
  snapshot: string | null;
};

type Progress = { positionMs: number; durationMs: number; frames: number; msPerFrame: number; motorcycles: number };

/** Saving the alerts to Violations, so they reach the dashboard and analytics like the app's. */
type Saving = { on: boolean; saved: number; failed: number; problem: string | null };

const NOT_SAVING: Saving = { on: false, saved: 0, failed: 0, problem: null };

function clock(ms: number) {
  const s = Math.max(0, Math.floor(ms / 1000));
  return `${String(Math.floor(s / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
}

function once(target: HTMLVideoElement, event: string, timeoutMs = 15000) {
  return new Promise<void>((resolve, reject) => {
    const timer = window.setTimeout(() => reject(new Error("The video stopped responding.")), timeoutMs);
    const done = () => {
      window.clearTimeout(timer);
      target.removeEventListener("error", failed);
      resolve();
    };
    const failed = () => {
      window.clearTimeout(timer);
      reject(new Error("This browser cannot play this video. Try an MP4 file recorded with H.264."));
    };
    target.addEventListener(event, done, { once: true });
    target.addEventListener("error", failed, { once: true });
  });
}

function toResult(t: Track): Result {
  return {
    id: t.id,
    firstMs: t.firstSeenMs,
    lastMs: t.lastSeenMs,
    bareFrames: t.bareFrames,
    decidedFrames: t.bareFrames + t.helmetFrames,
    score: t.averageBareScore,
    snapshot: t.snapshot,
  };
}

function drawOverlay(view: HTMLCanvasElement, frame: HTMLCanvasElement, bikes: { track: Track; heads: Head[] }[]) {
  view.width = frame.width;
  view.height = frame.height;
  const ctx = view.getContext("2d");
  if (!ctx) return;
  ctx.drawImage(frame, 0, 0);
  const line = Math.max(2, Math.round(frame.width / 400));
  ctx.lineWidth = line;
  ctx.font = `${Math.max(12, Math.round(frame.width / 70))}px system-ui, sans-serif`;
  const box = (b: Box, colour: string, label?: string) => {
    ctx.strokeStyle = colour;
    ctx.strokeRect(b.x1, b.y1, width(b), height(b));
    if (!label) return;
    const w = ctx.measureText(label).width + 8;
    const h = parseInt(ctx.font, 10) + 6;
    ctx.fillStyle = colour;
    ctx.fillRect(b.x1, Math.max(0, b.y1 - h), w, h);
    ctx.fillStyle = "#fff";
    ctx.fillText(label, b.x1 + 4, Math.max(h - 6, b.y1 - 6));
  };
  for (const { track, heads } of bikes) {
    const flagged = track.flagged();
    box(track.box, flagged ? "#dc2626" : track.suspect ? "#d97706" : "#2563eb", `#${track.id} ${flagged ? "No helmet" : "Motorcycle"}`);
    heads.forEach((h) => box(h.box, h.bare ? "#dc2626" : "#16a34a", h.bare ? "No helmet" : "Helmet"));
  }
}

export function VideoAnalyzer() {
  const [file, setFile] = useState<File | null>(null);
  const [rate, setRate] = useState(4);
  const [status, setStatus] = useState<Status>("idle");
  const [error, setError] = useState<string | null>(null);
  const [progress, setProgress] = useState<Progress>({ positionMs: 0, durationMs: 0, frames: 0, msPerFrame: 0, motorcycles: 0 });
  const [results, setResults] = useState<Result[]>([]);
  const [engine, setEngine] = useState<string | null>(null);
  const [saving, setSaving] = useState<Saving>(NOT_SAVING);
  const view = useRef<HTMLCanvasElement>(null);
  const stopRequested = useRef(false);
  const running = status === "loading" || status === "running";

  useEffect(() => () => void (stopRequested.current = true), []);

  async function run() {
    if (!file) return;
    stopRequested.current = false;
    setError(null);
    setResults([]);
    setSaving(NOT_SAVING);
    setStatus("loading");
    const url = URL.createObjectURL(file);

    // Saving: one detection session for this video, and one alert per flagged bike, sent one at a
    // time beside the analysis. A failed save never stops the analysis.
    const sessionId = crypto.randomUUID();
    const key = browserKey();
    let saveOn = false;
    let finished = false;
    let saved = 0;
    let failed = 0;
    let problem: string | null = null;
    const savedTracks = new Set<number>();
    let queue: Promise<void> = Promise.resolve();
    let modelVersion = "";
    let tracker: Tracker | null = null;
    let frames = 0;
    let spent = 0;

    const save = (track: Track) => {
      if (!saveOn || savedTracks.has(track.id) || !track.capture) return;
      savedTracks.add(track.id);
      const form = violationForm(track, { sessionId, violationId: crypto.randomUUID(), browserKey: key }, modelVersion);
      track.capture = null; // the photos now live in the form
      if (!form) return;
      queue = queue.then(async () => {
        try {
          const result = await saveVideoViolation(form);
          if (result.ok) saved += 1;
          else {
            failed += 1;
            problem = result.error;
          }
        } catch {
          failed += 1;
          problem = "The server could not be reached.";
        }
        setSaving({ on: true, saved, failed, problem });
      });
    };

    const finish = async (sessionStatus: VideoSessionEnd["status"]) => {
      if (!saveOn || finished || !tracker) return;
      finished = true;
      tracker.tracks.filter((t) => t.flagged()).forEach(save);
      await queue;
      try {
        const result = await finishVideoSession({
          sessionId,
          status: sessionStatus,
          framesProcessed: frames,
          avgFps: spent > 0 ? (frames * 1000) / spent : 0,
          violationsSaved: saved,
          ...sessionTotals(tracker),
        });
        if (!result.ok) problem = result.error;
      } catch {
        problem = "The server could not be reached.";
      }
      setSaving({ on: true, saved, failed, problem });
    };

    try {
      const models = await loadModels();
      const threads = threadCount();
      setEngine(`Models ${models.version}, ${models.stage1.size} px, ${threads} CPU ${threads === 1 ? "thread" : "threads"}`);
      const kinds = models.stage2.classes.map((c) => c.toLowerCase());

      const video = document.createElement("video");
      video.muted = true;
      video.playsInline = true;
      video.preload = "auto";
      video.src = url;
      await once(video, "loadeddata", 30000);
      if (!video.videoWidth || !Number.isFinite(video.duration)) throw new Error("This video has no readable length. Try an MP4 file.");

      const scale = Math.min(1, MAX_FRAME_WIDTH / video.videoWidth);
      const frame = document.createElement("canvas");
      frame.width = Math.round(video.videoWidth * scale);
      frame.height = Math.round(video.videoHeight * scale);
      const frameCtx = frame.getContext("2d", { willReadFrequently: true });
      if (!frameCtx) throw new Error("This browser cannot draw video frames.");
      const whole: Box = { x1: 0, y1: 0, x2: frame.width, y2: frame.height };
      const pad1 = scratch(models.stage1.size);
      const pad2 = scratch(models.stage2.size);
      tracker = new Tracker();
      const durationMs = Math.floor(video.duration * 1000);
      modelVersion = models.version;

      try {
        const started = await startVideoSession({
          sessionId,
          browserKey: key,
          browser: browserName(),
          fileName: file.name,
          durationMs,
          modelVersion,
          sampleRate: rate,
        });
        saveOn = started.ok;
        setSaving(started.ok ? { on: true, saved: 0, failed: 0, problem: null } : { ...NOT_SAVING, problem: started.error });
      } catch {
        setSaving({ ...NOT_SAVING, problem: "The server could not be reached." });
      }

      setStatus("running");
      for (let t = 0; t * 1000 < durationMs && !stopRequested.current; t += 1 / rate) {
        video.currentTime = t;
        await once(video, "seeked");
        frameCtx.drawImage(video, 0, 0, frame.width, frame.height);
        const timeMs = Math.round(t * 1000);
        const started = performance.now();

        const bikes = (await detect(models, models.stage1, pad1, frame, whole, STAGE1_MIN_SCORE))
          .map((d) => ({ box: clampBox(d.box, frame.width, frame.height), score: d.score }))
          .filter((d) => width(d.box) >= MIN_BIKE_PX && height(d.box) >= MIN_BIKE_PX);
        const seen = tracker.update(bikes, timeMs).sort((a, b) => width(b.box) * height(b.box) - width(a.box) * height(a.box));

        const drawn: { track: Track; heads: Head[] }[] = [];
        for (const track of seen.slice(0, MAX_BIKES_PER_FRAME)) {
          // The head model was trained on bike crops with this margin, which also keeps a head above a tight box.
          const region = clampBox(grow(track.box, DETAIL_PADDING), frame.width, frame.height);
          const parts = await detect(models, models.stage2, pad2, frame, region, STAGE2_MIN_SCORE);
          const heads: Head[] = parts
            .filter((p) => kinds[p.cls] === "helmet" || kinds[p.cls] === "no_helmet")
            .map((p) => ({ box: p.box, bare: kinds[p.cls] === "no_helmet", score: p.score }));
          track.observe(heads, timeMs);

          // The evidence is the whole frame, the whole bike with its riders and a thumbnail, all
          // from the frame where the bare head was clearest, as in the app. Number plates are not detected.
          const bare = heads.filter((h) => h.bare && h.score >= DEFAULT_RULES.minConfidence);
          const bareScore = bare.length ? Math.max(...bare.map((h) => h.score)) : 0;
          if (bareScore > track.snapshotScore) {
            const area = clampBox(grow(track.box, 0.1), frame.width, frame.height);
            const bike = photo(frame, area, 480);
            track.snapshot = bike.dataUrl;
            track.snapshotScore = bareScore;
            track.capture = {
              timeMs,
              frame: photo(frame, whole, MAX_FRAME_WIDTH, 0.8),
              bike,
              thumb: thumbnail(frame, area),
              bikeBox: corners(track.box, frame.width, frame.height),
              heads: heads
                .filter((h) => h.score >= DEFAULT_RULES.minConfidence)
                .map((h) => ({ bare: h.bare, score: h.score, box: corners(h.box, frame.width, frame.height) })),
            };
          }
          drawn.push({ track, heads });
        }

        // A flagged bike that has left the picture gets no better photo: save its alert now.
        tracker.tracks.filter((tr) => tr.missed > MAX_MISSED_FRAMES && tr.flagged()).forEach(save);

        frames += 1;
        spent += performance.now() - started;
        if (view.current) drawOverlay(view.current, frame, drawn);
        setProgress({ positionMs: timeMs, durationMs, frames, msPerFrame: spent / frames, motorcycles: tracker.counted().length });
        setResults(tracker.tracks.filter((tr) => tr.flagged()).map(toResult));
      }
      const stopped = stopRequested.current;
      setStatus(stopped ? "stopped" : "done");
      await finish(stopped ? "cancelled" : "completed");
    } catch (e) {
      setError(e instanceof Error ? e.message : "The analysis failed.");
      setStatus("error");
      await finish("interrupted");
    } finally {
      URL.revokeObjectURL(url);
    }
  }

  function downloadReport() {
    const rows = [
      ["motorcycle", "first_seen", "last_seen", "frames_without_helmet", "frames_decided", "average_confidence"],
      ...results.map((r) => [r.id, clock(r.firstMs), clock(r.lastMs), r.bareFrames, r.decidedFrames, r.score.toFixed(2)]),
    ];
    const blob = new Blob([rows.map((r) => r.join(",")).join("\n")], { type: "text/csv" });
    const link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = `${(file?.name ?? "video").replace(/\.[^.]+$/, "")}-helmetguard.csv`;
    link.click();
    URL.revokeObjectURL(link.href);
  }

  const percent = progress.durationMs ? Math.min(100, Math.round((100 * progress.positionMs) / progress.durationMs)) : 0;
  const remainingFrames = Math.max(0, Math.ceil(((progress.durationMs - progress.positionMs) / 1000) * rate));

  return (
    <div className="space-y-4">
      <Card>
        <div className="grid gap-3 sm:grid-cols-[1fr_auto_auto] sm:items-end">
          <label className="block text-sm font-medium">
            Video file
            <input
              type="file"
              accept="video/*"
              disabled={running}
              onChange={(e) => {
                setFile(e.target.files?.[0] ?? null);
                setStatus("idle");
                setResults([]);
                setError(null);
              }}
              className={cn(inputClass, "mt-1 py-1.5 file:mr-3 file:rounded file:border-0 file:bg-surface-2 file:px-2 file:py-1 file:text-sm")}
            />
          </label>
          <label className="block text-sm font-medium">
            Frames checked per second
            <select value={rate} disabled={running} onChange={(e) => setRate(Number(e.target.value))} className={cn(inputClass, "mt-1")}>
              {SAMPLE_RATES.map((r) => (
                <option key={r} value={r}>
                  {r} {r === 4 ? "(recommended)" : r === 8 ? "(slower, more thorough)" : "(faster)"}
                </option>
              ))}
            </select>
          </label>
          {running ? (
            <button type="button" onClick={() => (stopRequested.current = true)} className={secondaryButtonClass}>
              <Square className="size-4" aria-hidden /> Stop
            </button>
          ) : (
            <button type="button" onClick={run} disabled={!file} className={buttonClass}>
              {file ? <Play className="size-4" aria-hidden /> : <Upload className="size-4" aria-hidden />} Analyse
            </button>
          )}
        </div>
        <p className="mt-3 text-xs text-ink-3">
          The video stays on this computer: the detection runs in your browser. Each rider found without a helmet is saved to Violations, with photos of the bike, as an unverified AI alert for a person to check, and the
          video&apos;s totals count in the dashboard and analytics, as for the Android app.
        </p>
      </Card>

      {error && <Notice tone="bad">{error}</Notice>}
      {status === "loading" && <Notice tone="warn">Loading the detection models. The first time takes a little longer.</Notice>}
      {!saving.on && saving.problem && status !== "idle" && (
        <Notice tone="bad">This analysis is not being saved to Violations: {saving.problem}</Notice>
      )}
      {saving.on && (saving.saved > 0 || saving.failed > 0) && (
        <Notice tone={saving.failed > 0 ? "bad" : undefined}>
          {saving.saved} {saving.saved === 1 ? "alert" : "alerts"} saved to{" "}
          <Link href="/violations?source=video" className="underline underline-offset-2">
            Violations
          </Link>
          {saving.failed > 0 && `; ${saving.failed} could not be saved${saving.problem ? `: ${saving.problem}` : ""}`}.
        </Notice>
      )}

      {(running || status === "done" || status === "stopped") && (
        <Card title={status === "done" ? "Finished" : status === "stopped" ? "Stopped" : "Analysing"}>
          <canvas ref={view} className="w-full rounded-md bg-black" />
          <div className="mt-3 h-1.5 w-full overflow-hidden rounded-full bg-surface-2" role="progressbar" aria-valuenow={percent} aria-valuemin={0} aria-valuemax={100}>
            <div className="h-full bg-primary transition-[width]" style={{ width: `${percent}%` }} />
          </div>
          <dl className="mt-3 grid grid-cols-2 gap-3 text-sm sm:grid-cols-4">
            <div>
              <dt className="text-ink-3">Position</dt>
              <dd>
                {clock(progress.positionMs)} of {clock(progress.durationMs)}
              </dd>
            </div>
            <div>
              <dt className="text-ink-3">Frames checked</dt>
              <dd>{progress.frames}</dd>
            </div>
            <div>
              <dt className="text-ink-3">Motorcycles</dt>
              <dd>{progress.motorcycles}</dd>
            </div>
            <div>
              <dt className="text-ink-3">{running ? "Time left" : "Riders without helmets"}</dt>
              <dd>{running ? clock(remainingFrames * progress.msPerFrame) : results.length}</dd>
            </div>
          </dl>
          {engine && <p className="mt-2 text-xs text-ink-3">{engine}</p>}
        </Card>
      )}

      {(status === "done" || status === "stopped" || results.length > 0) && (
        <Card
          title={`Riders without helmets (${results.length})`}
          action={
            results.length > 0 && !running ? (
              <button type="button" onClick={downloadReport} className={secondaryButtonClass}>
                <Download className="size-4" aria-hidden /> Download CSV
              </button>
            ) : undefined
          }
        >
          {results.length === 0 ? (
            <EmptyState title="No rider without a helmet found">
              A bike is only listed after a bare head is seen in at least {DEFAULT_RULES.minBareFrames} checked frames, so one bad frame never raises an alert.
            </EmptyState>
          ) : (
            <ul className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
              {results.map((r) => (
                <li key={r.id} className="overflow-hidden rounded-md border border-line">
                  {r.snapshot && (
                    // eslint-disable-next-line @next/next/no-img-element -- a local data URL, not a hosted image
                    <img src={r.snapshot} alt={`Motorcycle ${r.id}`} className="aspect-video w-full bg-black object-contain" />
                  )}
                  <div className="space-y-1 p-3 text-sm">
                    <p className="font-medium">
                      Motorcycle #{r.id} · {clock(r.firstMs)}–{clock(r.lastMs)}
                    </p>
                    <p className="text-ink-2">
                      No helmet in {r.bareFrames} of {r.decidedFrames} checked frames · confidence {(r.score * 100).toFixed(0)}%
                    </p>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Card>
      )}
    </div>
  );
}
