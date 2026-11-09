export type UserRole = "admin" | "reviewer" | "operator";
export type UserStatus = "pending" | "active" | "disabled";
export type Source = "live" | "video";
export type ReviewStatus = "unreviewed" | "confirmed" | "rejected" | "needs_info";
export type OcrStatus = "read" | "low_confidence" | "unreadable" | "no_plate_detected";

export interface Profile {
  id: string;
  email: string | null;
  full_name: string;
  role: UserRole;
  status: UserStatus;
  approved_at: string | null;
  created_at: string;
}

export interface Device {
  id: string;
  owner_id: string;
  label: string | null;
  manufacturer: string | null;
  model: string | null;
  android_version: string | null;
  app_version: string | null;
  model_version: string | null;
  last_sync_at: string | null;
}

export interface DetectionSession {
  id: string;
  device_id: string;
  operator_id: string;
  source: Source;
  started_at: string;
  ended_at: string | null;
  status: "active" | "completed" | "interrupted" | "cancelled";
  location_label: string | null;
  video_file_name: string | null;
  video_duration_ms: number | null;
  frames_processed: number;
  avg_fps: number | null;
  motorcycles_total: number;
  riders_total: number;
  riders_helmet: number;
  riders_no_helmet: number;
  riders_unknown: number;
  violations_confirmed: number;
  settings: Record<string, unknown>;
  model_version: string | null;
}

/** Normalised to the full frame: x1, y1, x2, y2 between 0 and 1. */
export type Box = [number, number, number, number];

export interface Rider {
  role: "driver" | "pillion";
  status: "helmet" | "no_helmet" | "unknown";
  confidence: number;
  box?: Box;
}

export interface Violation {
  id: string;
  session_id: string;
  device_id: string;
  operator_id: string;
  track_id: number;
  source: Source;
  detected_at: string;
  video_timestamp_ms: number | null;
  rider_count: number;
  no_helmet_count: number;
  riders: Rider[];
  helmet_policy: string;
  helmet_confidence: number | null;
  motorcycle_confidence: number | null;
  frames_observed: number;
  no_helmet_frames: number;
  plate_text_ocr: string | null;
  plate_ocr_confidence: number | null;
  plate_ocr_status: OcrStatus;
  plate_candidates: { text: string; confidence: number }[];
  ocr_engine: string | null;
  plate_text_corrected: string | null;
  plate_text_final: string | null;
  review_status: ReviewStatus;
  reviewed_at: string | null;
  review_notes: string | null;
  model_version: string | null;
  app_version: string | null;
  synced_at: string;
}

export interface Evidence {
  id: string;
  violation_id: string;
  kind:
    | "full_frame"
    | "motorcycle"
    | "rider"
    | "head"
    | "plate"
    | "plate_enhanced"
    | "plate_source_frame"
    | "still_hires"
    | "alt_frame"
    | "thumb";
  seq: number;
  storage_path: string;
  width: number | null;
  height: number | null;
  bbox: Box | null;
  frame_timestamp_ms: number | null;
  is_enhanced: boolean;
}

export interface ReviewEvent {
  id: number;
  action: "status_changed" | "plate_corrected" | "note_added";
  old_value: string | null;
  new_value: string | null;
  created_at: string;
  actor: { full_name: string; email: string | null } | null;
}

export interface DashboardSummary {
  sessions: number;
  motorcycles: number;
  riders: number;
  riders_helmet: number;
  riders_no_helmet: number;
  riders_unknown: number;
  compliance_pct: number | null;
  ai_alerts: number;
  unreviewed: number;
  reviewed_confirmed: number;
  reviewed_rejected: number;
}

export interface TrendPoint {
  bucket: string;
  live: number;
  video: number;
  total: number;
}
