// Pure geometry and model-output parsing, shared by the browser analyser and its tests.
// Mirrors the Android app's YoloOutputParser and Letterboxer so both give the same boxes.

export type Box = { x1: number; y1: number; x2: number; y2: number };
export type Detection = { box: Box; cls: number; score: number };
export type Mapping = { scale: number; padX: number; padY: number; originX: number; originY: number };

export const width = (b: Box) => Math.max(0, b.x2 - b.x1);
export const height = (b: Box) => Math.max(0, b.y2 - b.y1);
export const area = (b: Box) => width(b) * height(b);

export function iou(a: Box, b: Box): number {
  const inter = area({ x1: Math.max(a.x1, b.x1), y1: Math.max(a.y1, b.y1), x2: Math.min(a.x2, b.x2), y2: Math.min(a.y2, b.y2) });
  const union = area(a) + area(b) - inter;
  return union > 0 ? inter / union : 0;
}

export function nms(detections: Detection[], iouThreshold: number): Detection[] {
  const kept: Detection[] = [];
  for (const candidate of [...detections].sort((a, b) => b.score - a.score)) {
    if (!kept.some((k) => k.cls === candidate.cls && iou(k.box, candidate.box) > iouThreshold)) kept.push(candidate);
  }
  return kept;
}

/**
 * Reads a raw YOLOv8 export: shape [1, 4 + classes, candidates], each candidate a
 * centre x, centre y, width and height in input pixels followed by class scores.
 */
export function parseRaw(data: ArrayLike<number>, dims: readonly number[], minScore: number, iouThreshold = 0.5): Detection[] {
  if (dims.length !== 3 || dims[1] < 5) throw new Error(`Unexpected model output shape ${dims.join("x")}`);
  const channels = dims[1];
  const count = dims[2];
  const found: Detection[] = [];
  for (let i = 0; i < count; i++) {
    let cls = 0;
    let score = 0;
    for (let c = 4; c < channels; c++) {
      const s = data[c * count + i];
      if (s > score) {
        score = s;
        cls = c - 4;
      }
    }
    if (score < minScore) continue;
    const cx = data[i];
    const cy = data[count + i];
    const w = data[2 * count + i];
    const h = data[3 * count + i];
    found.push({ box: { x1: cx - w / 2, y1: cy - h / 2, x2: cx + w / 2, y2: cy + h / 2 }, cls, score });
  }
  return nms(found, iouThreshold);
}

/** Reads an end-to-end export (YOLO26 and similar): shape [1, N, 6], rows of x1, y1, x2, y2, score, class. */
export function parseEndToEnd(data: ArrayLike<number>, dims: readonly number[], minScore: number): Detection[] {
  const found: Detection[] = [];
  for (let i = 0; i < dims[1]; i++) {
    const row = i * 6;
    const score = data[row + 4];
    if (score < minScore) continue;
    found.push({ box: { x1: data[row], y1: data[row + 1], x2: data[row + 2], y2: data[row + 3] }, cls: Math.round(data[row + 5]), score });
  }
  return found;
}

/**
 * Either output layout, like the Android app. A raw two-class model is [1, 6, candidates]
 * and an end-to-end one is [1, N, 6], so the six columns tell them apart.
 */
export function parseOutput(data: ArrayLike<number>, dims: readonly number[], minScore: number): Detection[] {
  if (dims.length === 3 && dims[2] === 6 && dims[1] !== 6) return parseEndToEnd(data, dims, minScore);
  return parseRaw(data, dims, minScore);
}

/** How a region of the frame is scaled and centred into a square model input. */
export function letterboxMapping(region: Box, size: number): Mapping {
  const w = Math.max(1, width(region));
  const h = Math.max(1, height(region));
  const scale = Math.min(size / w, size / h);
  return { scale, padX: (size - w * scale) / 2, padY: (size - h * scale) / 2, originX: region.x1, originY: region.y1 };
}

export function toSource(box: Box, m: Mapping): Box {
  return {
    x1: (box.x1 - m.padX) / m.scale + m.originX,
    y1: (box.y1 - m.padY) / m.scale + m.originY,
    x2: (box.x2 - m.padX) / m.scale + m.originX,
    y2: (box.y2 - m.padY) / m.scale + m.originY,
  };
}

export function clampBox(b: Box, frameWidth: number, frameHeight: number): Box {
  return {
    x1: Math.min(Math.max(0, b.x1), frameWidth),
    y1: Math.min(Math.max(0, b.y1), frameHeight),
    x2: Math.min(Math.max(0, b.x2), frameWidth),
    y2: Math.min(Math.max(0, b.y2), frameHeight),
  };
}

export function grow(b: Box, fraction: number): Box {
  const dx = width(b) * fraction;
  const dy = height(b) * fraction;
  return { x1: b.x1 - dx, y1: b.y1 - dy, x2: b.x2 + dx, y2: b.y2 + dy };
}
