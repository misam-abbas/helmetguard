-- Helpers, guard triggers and the sync entry point (plan section 9).

-- The caller's role, but only while their account is active. NULL otherwise.
create function private.current_role()
returns public.user_role
language sql
stable
security definer
set search_path = ''
as $$
  select p.role from public.profiles p
  where p.id = (select auth.uid()) and p.status = 'active'
$$;
revoke all on function private.current_role() from public;
grant usage on schema private to authenticated;
grant execute on function private.current_role() to authenticated;

-- Every new sign-up starts pending and can do nothing until an admin approves it.
create function private.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  insert into public.profiles (id, email, full_name)
  values (
    new.id,
    new.email,
    left(coalesce(new.raw_user_meta_data ->> 'full_name', ''), 120)
  );
  return new;
end;
$$;
revoke all on function private.handle_new_user() from public;

create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function private.handle_new_user();

-- Only an admin may change role or status, including their own. Direct database
-- roles (the SQL editor used once to create the first admin) are not API users.
create function private.protect_profile_fields()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  new.updated_at := now();

  if current_user not in ('authenticated', 'anon') then
    return new;
  end if;

  if new.id is distinct from old.id then
    raise exception 'profile id cannot change' using errcode = '42501';
  end if;

  if private.current_role() = 'admin' then
    if new.status = 'active' and old.status is distinct from 'active' then
      new.approved_by := (select auth.uid());
      new.approved_at := now();
    end if;
    return new;
  end if;

  if new.role is distinct from old.role
     or new.status is distinct from old.status
     or new.approved_by is distinct from old.approved_by
     or new.approved_at is distinct from old.approved_at
     or new.email is distinct from old.email then
    raise exception 'only an admin can change account fields' using errcode = '42501';
  end if;

  return new;
end;
$$;
revoke all on function private.protect_profile_fields() from public;

create trigger protect_profile_fields
  before update on public.profiles
  for each row execute function private.protect_profile_fields();

-- The AI's results are permanent after insert. Only review fields may change.
create function private.protect_violation_fields()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  -- Generated columns are not computed yet in a BEFORE trigger, so skip them too.
  editable text[] := array[
    'plate_text_corrected', 'review_status', 'reviewed_by', 'reviewed_at',
    'review_notes', 'plate_text_final', 'plate_search'
  ];
begin
  if current_user not in ('authenticated', 'anon') then
    return new;
  end if;

  if (to_jsonb(new) - editable) is distinct from (to_jsonb(old) - editable) then
    raise exception 'AI results cannot be changed after insert' using errcode = '42501';
  end if;

  new.plate_text_corrected := nullif(btrim(new.plate_text_corrected), '');
  new.review_notes := nullif(btrim(new.review_notes), '');

  if new.review_status is distinct from old.review_status
     or new.plate_text_corrected is distinct from old.plate_text_corrected
     or new.review_notes is distinct from old.review_notes then
    new.reviewed_by := (select auth.uid());
    new.reviewed_at := now();
  else
    new.reviewed_by := old.reviewed_by;
    new.reviewed_at := old.reviewed_at;
  end if;

  return new;
end;
$$;
revoke all on function private.protect_violation_fields() from public;

create trigger protect_violation_fields
  before update on public.violations
  for each row execute function private.protect_violation_fields();

-- Reviewers have no insert rights on review_events, so the audit write runs as
-- the table owner. It records only what actually changed.
create function private.write_review_events()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  actor uuid := (select auth.uid());
begin
  if new.review_status is distinct from old.review_status then
    insert into public.review_events (violation_id, actor_id, action, old_value, new_value)
    values (new.id, actor, 'status_changed', old.review_status::text, new.review_status::text);
  end if;
  if new.plate_text_corrected is distinct from old.plate_text_corrected then
    insert into public.review_events (violation_id, actor_id, action, old_value, new_value)
    values (new.id, actor, 'plate_corrected', coalesce(old.plate_text_corrected, old.plate_text_ocr), new.plate_text_corrected);
  end if;
  if new.review_notes is distinct from old.review_notes then
    insert into public.review_events (violation_id, actor_id, action, old_value, new_value)
    values (new.id, actor, 'note_added', old.review_notes, new.review_notes);
  end if;
  return new;
end;
$$;
revoke all on function private.write_review_events() from public;

create trigger write_review_events
  after update on public.violations
  for each row execute function private.write_review_events();

-- Inserts one violation and its evidence in a single transaction. A repeat of the
-- same ID does nothing. Runs with the caller's permissions, so RLS still applies.
create function public.sync_violation(payload jsonb)
returns jsonb
language plpgsql
security invoker
set search_path = ''
as $$
declare
  vid uuid := (payload ->> 'id')::uuid;
  inserted_id uuid;
  server_time timestamptz := now();
begin
  insert into public.violations (
    id, session_id, device_id, operator_id, track_id, source, detected_at,
    video_timestamp_ms, rider_count, no_helmet_count, riders, helmet_policy,
    helmet_confidence, motorcycle_confidence, frames_observed, no_helmet_frames,
    plate_text_ocr, plate_ocr_confidence, plate_ocr_status, plate_candidates,
    ocr_engine, model_version, app_version, client_created_at, synced_at
  )
  values (
    vid,
    (payload ->> 'session_id')::uuid,
    (payload ->> 'device_id')::uuid,
    (select auth.uid()),
    (payload ->> 'track_id')::integer,
    (payload ->> 'source')::public.detection_source,
    (payload ->> 'detected_at')::timestamptz,
    (payload ->> 'video_timestamp_ms')::bigint,
    coalesce((payload ->> 'rider_count')::integer, 0),
    coalesce((payload ->> 'no_helmet_count')::integer, 0),
    coalesce(payload -> 'riders', '[]'::jsonb),
    coalesce(payload ->> 'helmet_policy', 'all_riders'),
    (payload ->> 'helmet_confidence')::real,
    (payload ->> 'motorcycle_confidence')::real,
    coalesce((payload ->> 'frames_observed')::integer, 0),
    coalesce((payload ->> 'no_helmet_frames')::integer, 0),
    payload ->> 'plate_text_ocr',
    (payload ->> 'plate_ocr_confidence')::real,
    coalesce((payload ->> 'plate_ocr_status')::public.plate_ocr_status, 'no_plate_detected'),
    coalesce(payload -> 'plate_candidates', '[]'::jsonb),
    payload ->> 'ocr_engine',
    payload ->> 'model_version',
    payload ->> 'app_version',
    (payload ->> 'client_created_at')::timestamptz,
    server_time
  )
  on conflict (id) do nothing
  returning id into inserted_id;

  if inserted_id is null then
    return jsonb_build_object('id', vid, 'inserted', false);
  end if;

  insert into public.violation_evidence (
    id, violation_id, kind, seq, storage_path, width, height, bytes, sha256,
    bbox, frame_timestamp_ms, quality_score, is_enhanced
  )
  select
    (e ->> 'id')::uuid,
    vid,
    (e ->> 'kind')::public.evidence_kind,
    coalesce((e ->> 'seq')::integer, 0),
    e ->> 'storage_path',
    (e ->> 'width')::integer,
    (e ->> 'height')::integer,
    (e ->> 'bytes')::integer,
    e ->> 'sha256',
    e -> 'bbox',
    (e ->> 'frame_timestamp_ms')::bigint,
    (e ->> 'quality_score')::real,
    coalesce((e ->> 'is_enhanced')::boolean, false)
  from jsonb_array_elements(coalesce(payload -> 'evidence', '[]'::jsonb)) as e;

  return jsonb_build_object('id', vid, 'inserted', true, 'synced_at', server_time);
end;
$$;
revoke all on function public.sync_violation(jsonb) from public, anon;
grant execute on function public.sync_violation(jsonb) to authenticated;
