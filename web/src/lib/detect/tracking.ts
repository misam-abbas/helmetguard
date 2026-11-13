// Follows motorcycles from frame to frame and decides, from many frames, whether
// someone on a bike is riding without a helmet. One bad frame never raises an alert.
import { iou, type Box } from "./yolo";

export type Head = { box: Box; bare: boolean; score: number };

export type Rules = {
  /** Heads below this score are not counted either way. */
  minConfidence: number;
  /** Frames with a bare head before a bike is flagged. */
  minBareFrames: number;
  /** Share of decided frames that must show a bare head. */
  minBareShare: number;
  /** Bare heads must be seen across at least this much video. */
  minBareSpanMs: number;
};

// Same thresholds as the Android app, except it needs 5 frames: the web samples fewer frames per second.
export const DEFAULT_RULES: Rules = { minConfidence: 0.55, minBareFrames: 4, minBareShare: 0.7, minBareSpanMs: 500 };

export class Track {
  hits = 1;
  missed = 0;
  bareFrames = 0;
  helmetFrames = 0;
  bareScoreSum = 0;
  firstBareMs = Number.POSITIVE_INFINITY;
  lastBareMs = Number.NEGATIVE_INFINITY;
  firstSeenMs: number;
  lastSeenMs: number;
  /** Picture of the whole bike from the clearest bare-head frame, as a JPEG data URL. */
  snapshot: string | null = null;
  snapshotScore = 0;

  constructor(
    readonly id: number,
    public box: Box,
    public score: number,
    timeMs: number,
  ) {
    this.firstSeenMs = timeMs;
    this.lastSeenMs = timeMs;
  }

  /** A bare head has been seen at least once, though maybe not often enough to flag yet. */
  get suspect(): boolean {
    return this.bareFrames > 0;
  }

  flagged(rules: Rules = DEFAULT_RULES): boolean {
    const decided = this.bareFrames + this.helmetFrames;
    return (
      this.bareFrames >= rules.minBareFrames &&
      decided > 0 &&
      this.bareFrames / decided >= rules.minBareShare &&
      this.lastBareMs - this.firstBareMs >= rules.minBareSpanMs
    );
  }

  get averageBareScore(): number {
    return this.bareFrames ? this.bareScoreSum / this.bareFrames : 0;
  }

  /** Counts one analysed frame's heads for this bike. */
  observe(heads: Head[], timeMs: number, rules: Rules = DEFAULT_RULES) {
    const counted = heads.filter((h) => h.score >= rules.minConfidence);
    const bare = counted.filter((h) => h.bare);
    if (bare.length) {
      this.bareFrames += 1;
      this.bareScoreSum += Math.max(...bare.map((h) => h.score));
      this.firstBareMs = Math.min(this.firstBareMs, timeMs);
      this.lastBareMs = Math.max(this.lastBareMs, timeMs);
    } else if (counted.length) {
      this.helmetFrames += 1;
    }
  }
}

/** Matches each frame's motorcycles to the ones already being followed, by overlap. */
export class Tracker {
  private next = 1;
  readonly tracks: Track[] = [];

  constructor(
    private readonly minIou = 0.3,
    private readonly maxMissed = 3,
  ) {}

  update(detections: { box: Box; score: number }[], timeMs: number): Track[] {
    const live = this.tracks.filter((t) => t.missed <= this.maxMissed);
    const pairs: { t: Track; d: number; overlap: number }[] = [];
    live.forEach((t) => detections.forEach((det, d) => {
      const overlap = iou(t.box, det.box);
      if (overlap >= this.minIou) pairs.push({ t, d, overlap });
    }));
    pairs.sort((a, b) => b.overlap - a.overlap);

    const usedTracks = new Set<Track>();
    const usedDetections = new Set<number>();
    for (const { t, d } of pairs) {
      if (usedTracks.has(t) || usedDetections.has(d)) continue;
      usedTracks.add(t);
      usedDetections.add(d);
      t.box = detections[d].box;
      t.score = detections[d].score;
      t.hits += 1;
      t.missed = 0;
      t.lastSeenMs = timeMs;
    }
    live.filter((t) => !usedTracks.has(t)).forEach((t) => (t.missed += 1));

    detections.forEach((det, d) => {
      if (usedDetections.has(d)) return;
      const track = new Track(this.next++, det.box, det.score, timeMs);
      this.tracks.push(track);
      usedTracks.add(track);
    });
    return [...usedTracks];
  }

  /** Bikes seen in enough frames to count as real, not a one-frame mistake. */
  counted(minHits = 3): Track[] {
    return this.tracks.filter((t) => t.hits >= minHits);
  }
}
