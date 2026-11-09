-- HelmetGuard core schema (plan section 9).
-- IDs for devices, sessions, violations and evidence are UUIDs created on the
-- phone, so a retried upload is recognised and ignored.

create schema if not exists extensions;
create extension if not exists pg_trgm with schema extensions;

-- Not exposed through the API. Holds helpers that policies and triggers call.
create schema if not exists private;

create type public.user_role as enum ('admin', 'reviewer', 'operator');
create type public.user_status as enum ('pending', 'active', 'disabled');
create type public.detection_source as enum ('live', 'video');
create type public.session_status as enum ('active', 'completed', 'interrupted', 'cancelled');
create type public.review_status as enum ('unreviewed', 'confirmed', 'rejected', 'needs_info');
create type public.plate_ocr_status as enum ('read', 'low_confidence', 'unreadable', 'no_plate_detected');
create type public.evidence_kind as enum (
  'full_frame', 'motorcycle', 'rider', 'head', 'plate', 'plate_enhanced',
  'plate_source_frame', 'still_hires', 'alt_frame', 'thumb'
);
create type public.review_action as enum ('status_changed', 'plate_corrected', 'note_added');

create table public.profiles (
  id uuid primary key references auth.users (id) on delete cascade,
  email text,
  full_name text not null default '' check (char_length(full_name) <= 120),
  role public.user_role not null default 'operator',
  status public.user_status not null default 'pending',
  approved_by uuid references public.profiles (id) on delete set null,
  approved_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.devices (
  id uuid primary key,
  owner_id uuid not null references public.profiles (id) on delete cascade,
  label text,
  manufacturer text,
  model text,
  android_version text,
  app_version text,
  model_version text,
  last_sync_at timestamptz,
  created_at timestamptz not null default now()
);

create table public.detection_sessions (
  id uuid primary key,
  device_id uuid not null references public.devices (id) on delete cascade,
  operator_id uuid not null references public.profiles (id) on delete cascade,
  source public.detection_source not null,
  started_at timestamptz not null,
  ended_at timestamptz,
  status public.session_status not null default 'active',
  location_label text,
  video_file_name text,
  video_duration_ms bigint check (video_duration_ms is null or video_duration_ms >= 0),
  frames_processed integer not null default 0 check (frames_processed >= 0),
  avg_fps real,
  motorcycles_total integer not null default 0 check (motorcycles_total >= 0),
  riders_total integer not null default 0 check (riders_total >= 0),
  riders_helmet integer not null default 0 check (riders_helmet >= 0),
  riders_no_helmet integer not null default 0 check (riders_no_helmet >= 0),
  riders_unknown integer not null default 0 check (riders_unknown >= 0),
  violations_confirmed integer not null default 0 check (violations_confirmed >= 0),
  settings jsonb not null default '{}'::jsonb,
  model_version text,
  created_at timestamptz not null default now()
);

create table public.violations (
  id uuid primary key,
  session_id uuid not null references public.detection_sessions (id) on delete cascade,
  device_id uuid not null references public.devices (id) on delete cascade,
  operator_id uuid not null references public.profiles (id) on delete cascade,
  track_id integer not null,
  source public.detection_source not null,
  detected_at timestamptz not null,
  video_timestamp_ms bigint check (video_timestamp_ms is null or video_timestamp_ms >= 0),
  rider_count integer not null default 0 check (rider_count >= 0),
  no_helmet_count integer not null default 0 check (no_helmet_count >= 0),
  riders jsonb not null default '[]'::jsonb,
  helmet_policy text not null default 'all_riders' check (helmet_policy in ('all_riders', 'driver_only')),
  helmet_confidence real check (helmet_confidence between 0 and 1),
  motorcycle_confidence real check (motorcycle_confidence between 0 and 1),
  frames_observed integer not null default 0 check (frames_observed >= 0),
  no_helmet_frames integer not null default 0 check (no_helmet_frames >= 0),
  plate_text_ocr text,
  plate_ocr_confidence real check (plate_ocr_confidence between 0 and 1),
  plate_ocr_status public.plate_ocr_status not null default 'no_plate_detected',
  plate_candidates jsonb not null default '[]'::jsonb,
  ocr_engine text,
  plate_text_corrected text check (plate_text_corrected is null or char_length(plate_text_corrected) <= 20),
  -- A human correction always wins over the OCR text, which stays untouched.
  plate_text_final text generated always as (coalesce(plate_text_corrected, plate_text_ocr)) stored,
  plate_search text generated always as (
    upper(regexp_replace(coalesce(plate_text_corrected, plate_text_ocr, ''), '[^A-Za-z0-9]', '', 'g'))
  ) stored,
  review_status public.review_status not null default 'unreviewed',
  reviewed_by uuid references public.profiles (id) on delete set null,
  reviewed_at timestamptz,
  review_notes text check (review_notes is null or char_length(review_notes) <= 2000),
  model_version text,
  app_version text,
  client_created_at timestamptz,
  synced_at timestamptz not null default now(),
  constraint video_needs_timestamp check (source <> 'video' or video_timestamp_ms is not null)
);

create table public.violation_evidence (
  id uuid primary key,
  violation_id uuid not null references public.violations (id) on delete cascade,
  kind public.evidence_kind not null,
  seq integer not null default 0 check (seq >= 0),
  storage_path text not null unique,
  width integer,
  height integer,
  bytes integer check (bytes is null or bytes >= 0),
  sha256 text,
  bbox jsonb,
  frame_timestamp_ms bigint,
  quality_score real,
  is_enhanced boolean not null default false,
  unique (violation_id, kind, seq)
);

create table public.review_events (
  id bigint generated always as identity primary key,
  violation_id uuid not null references public.violations (id) on delete cascade,
  actor_id uuid references public.profiles (id) on delete set null,
  action public.review_action not null,
  old_value text,
  new_value text,
  created_at timestamptz not null default now()
);

-- Single-row settings edited on the dashboard's admin Settings page.
create table public.app_settings (
  id boolean primary key default true check (id),
  retention_days integer not null default 90 check (retention_days between 7 and 730),
  offline_grace_days integer not null default 7 check (offline_grace_days between 1 and 60),
  updated_by uuid references public.profiles (id) on delete set null,
  updated_at timestamptz not null default now()
);
insert into public.app_settings (id) values (true);

-- List and filter indexes.
create index violations_detected_at_idx on public.violations (detected_at desc);
create index violations_review_status_idx on public.violations (review_status, detected_at desc);
create index violations_source_idx on public.violations (source, detected_at desc);
create index violations_plate_search_idx on public.violations using gin (plate_search extensions.gin_trgm_ops);
create index sessions_operator_started_idx on public.detection_sessions (operator_id, started_at desc);
create index review_events_violation_idx on public.review_events (violation_id, created_at);

-- Foreign keys not already covered above.
create index profiles_approved_by_idx on public.profiles (approved_by);
create index devices_owner_idx on public.devices (owner_id);
create index sessions_device_idx on public.detection_sessions (device_id);
create index violations_session_idx on public.violations (session_id);
create index violations_device_idx on public.violations (device_id);
create index violations_operator_idx on public.violations (operator_id);
create index violations_reviewed_by_idx on public.violations (reviewed_by);
create index review_events_actor_idx on public.review_events (actor_id);
create index app_settings_updated_by_idx on public.app_settings (updated_by);
