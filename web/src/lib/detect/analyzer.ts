// Browser-only: runs the same two ONNX models as the Android app, on the user's own computer.
// The video never leaves the browser; only alerts and their photos are saved (lib/detect/save.ts).
import type { InferenceSession, Tensor } from "onnxruntime-web";
import type { Photo } from "./tracking";
import { letterboxMapping, parseOutput, toSource, type Box, type Detection } from "./yolo";

type Ort = typeof import("onnxruntime-web/wasm");

export type Stage = { session: InferenceSession; size: number; classes: string[] };
export type Models = { ort: Ort; version: string; stage1: Stage; stage2: Stage };

type ManifestEntry = { file: string; input: number; classes: string[] };

let cached: Promise<Models> | null = null;

/**
 * Threads need the page to be cross-origin isolated, which next.config.ts turns on for
 * /detect only. Reached by an in-app link instead of a full page load, it isn't, so one thread.
 */
export function threadCount(): number {
  if (typeof self === "undefined" || !self.crossOriginIsolated) return 1;
  return Math.min(4, Math.max(1, Math.floor((navigator.hardwareConcurrency || 2) / 2)));
}

export function loadModels(): Promise<Models> {
  cached ??= (async () => {
    const ort = await import("onnxruntime-web/wasm");
    ort.env.wasm.wasmPaths = "/ort/";
    ort.env.wasm.numThreads = threadCount();
    const response = await fetch("/models/models.json");
    if (!response.ok) throw new Error("The detection models are missing from this site.");
    const manifest = (await response.json()) as { version: string; stage1: ManifestEntry; stage2: ManifestEntry };
    const open = async (entry: ManifestEntry): Promise<Stage> => ({
      session: await ort.InferenceSession.create(`/models/${entry.file}`, { executionProviders: ["wasm"], graphOptimizationLevel: "all" }),
      size: entry.input,
      classes: entry.classes,
    });
    const [stage1, stage2] = await Promise.all([open(manifest.stage1), open(manifest.stage2)]);
    return { ort, version: manifest.version, stage1, stage2 };
  })().catch((error) => {
    cached = null;
    throw error;
  });
  return cached;
}

export type Scratch = { canvas: HTMLCanvasElement; ctx: CanvasRenderingContext2D };

export function scratch(size: number): Scratch {
  const canvas = document.createElement("canvas");
  canvas.width = size;
  canvas.height = size;
  const ctx = canvas.getContext("2d", { willReadFrequently: true });
  if (!ctx) throw new Error("This browser cannot draw video frames.");
  return { canvas, ctx };
}

function toTensor(ort: Ort, { ctx }: Scratch, size: number): Tensor {
  const { data } = ctx.getImageData(0, 0, size, size);
  const plane = size * size;
  const input = new Float32Array(3 * plane);
  for (let i = 0; i < plane; i++) {
    input[i] = data[i * 4] / 255;
    input[plane + i] = data[i * 4 + 1] / 255;
    input[2 * plane + i] = data[i * 4 + 2] / 255;
  }
  return new ort.Tensor("float32", input, [1, 3, size, size]);
}

/** Runs one stage on a region of the frame and returns boxes in frame pixels. */
export async function detect(models: Models, stage: Stage, pad: Scratch, frame: HTMLCanvasElement, region: Box, minScore: number): Promise<Detection[]> {
  const mapping = letterboxMapping(region, stage.size);
  pad.ctx.fillStyle = "#000";
  pad.ctx.fillRect(0, 0, stage.size, stage.size);
  pad.ctx.drawImage(
    frame,
    region.x1,
    region.y1,
    region.x2 - region.x1,
    region.y2 - region.y1,
    mapping.padX,
    mapping.padY,
    (region.x2 - region.x1) * mapping.scale,
    (region.y2 - region.y1) * mapping.scale,
  );
  const input = toTensor(models.ort, pad, stage.size);
  const outputs = await stage.session.run({ [stage.session.inputNames[0]]: input });
  const output = outputs[stage.session.outputNames[0]];
  const found = parseOutput(output.data as Float32Array, output.dims, minScore);
  input.dispose();
  output.dispose();
  return found.map((d) => ({ ...d, box: toSource(d.box, mapping) }));
}

/** A JPEG of one area of the frame, at most `maxSide` pixels on its long side. */
export function photo(frame: HTMLCanvasElement, region: Box, maxSide = 360, quality = 0.85): Photo {
  const w = Math.max(1, region.x2 - region.x1);
  const h = Math.max(1, region.y2 - region.y1);
  const scale = Math.min(1, maxSide / Math.max(w, h));
  const canvas = document.createElement("canvas");
  canvas.width = Math.max(1, Math.round(w * scale));
  canvas.height = Math.max(1, Math.round(h * scale));
  canvas.getContext("2d")?.drawImage(frame, region.x1, region.y1, w, h, 0, 0, canvas.width, canvas.height);
  return { dataUrl: canvas.toDataURL("image/jpeg", quality), width: canvas.width, height: canvas.height };
}

/** A small JPEG of one area of the frame, for the results list. */
export function crop(frame: HTMLCanvasElement, region: Box, maxSide = 360): string {
  return photo(frame, region, maxSide).dataUrl;
}

/** One area of the frame fitted into a fixed size with dark bars, like the app's list thumbnails. */
export function thumbnail(frame: HTMLCanvasElement, region: Box, width = 320, height = 240, quality = 0.7): Photo {
  const w = Math.max(1, region.x2 - region.x1);
  const h = Math.max(1, region.y2 - region.y1);
  const scale = Math.min(width / w, height / h);
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext("2d");
  if (ctx) {
    ctx.fillStyle = "#111";
    ctx.fillRect(0, 0, width, height);
    ctx.drawImage(frame, region.x1, region.y1, w, h, (width - w * scale) / 2, (height - h * scale) / 2, w * scale, h * scale);
  }
  return { dataUrl: canvas.toDataURL("image/jpeg", quality), width, height };
}
