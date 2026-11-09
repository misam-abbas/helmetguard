-- Dashboard numbers (plan sections 9 and 11). All run with the caller's
-- permissions, so RLS applies. Data is stored in UTC and grouped in Pakistan time.

create function public.dashboard_summary(
  p_from timestamptz,
  p_to timestamptz,
  p_source public.detection_source default null
)
returns jsonb
language sql
stable
security invoker
set search_path = ''
as $$
  with s as (
    select
      coalesce(sum(motorcycles_total), 0) as motorcycles,
      coalesce(sum(riders_total), 0) as riders,
      coalesce(sum(riders_helmet), 0) as riders_helmet,
      coalesce(sum(riders_no_helmet), 0) as riders_no_helmet,
      coalesce(sum(riders_unknown), 0) as riders_unknown,
      count(*) as sessions
    from public.detection_sessions
    where started_at >= p_from and started_at < p_to
      and (p_source is null or source = p_source)
  ),
  v as (
    select
      count(*) as ai_alerts,
      count(*) filter (where review_status = 'unreviewed') as unreviewed,
      count(*) filter (where review_status = 'confirmed') as reviewed_confirmed,
      count(*) filter (where review_status = 'rejected') as reviewed_rejected
    from public.violations
    where detected_at >= p_from and detected_at < p_to
      and (p_source is null or source = p_source)
  )
  select jsonb_build_object(
    'sessions', s.sessions,
    'motorcycles', s.motorcycles,
    'riders', s.riders,
    'riders_helmet', s.riders_helmet,
    'riders_no_helmet', s.riders_no_helmet,
    'riders_unknown', s.riders_unknown,
    'compliance_pct', case
      when s.riders_helmet + s.riders_no_helmet = 0 then null
      else round(100.0 * s.riders_helmet / (s.riders_helmet + s.riders_no_helmet), 1)
    end,
    'ai_alerts', v.ai_alerts,
    'unreviewed', v.unreviewed,
    'reviewed_confirmed', v.reviewed_confirmed,
    'reviewed_rejected', v.reviewed_rejected
  )
  from s, v
$$;

create function public.violation_trend(
  p_from timestamptz,
  p_to timestamptz,
  p_interval text default 'day',
  p_source public.detection_source default null
)
returns table (bucket date, live bigint, video bigint, total bigint)
language sql
stable
security invoker
set search_path = ''
as $$
  select
    date_trunc(
      case when p_interval = 'month' then 'month' else 'day' end,
      detected_at at time zone 'Asia/Karachi'
    )::date as bucket,
    count(*) filter (where source = 'live') as live,
    count(*) filter (where source = 'video') as video,
    count(*) as total
  from public.violations
  where detected_at >= p_from and detected_at < p_to
    and (p_source is null or source = p_source)
  group by 1
  order by 1
$$;

create function public.compliance_by_location(
  p_from timestamptz,
  p_to timestamptz,
  p_source public.detection_source default null
)
returns table (location_label text, source public.detection_source, riders_helmet bigint, riders_no_helmet bigint, compliance_pct numeric)
language sql
stable
security invoker
set search_path = ''
as $$
  select
    coalesce(nullif(btrim(s.location_label), ''), s.video_file_name, 'Unlabelled') as location_label,
    s.source,
    sum(s.riders_helmet)::bigint,
    sum(s.riders_no_helmet)::bigint,
    case when sum(s.riders_helmet) + sum(s.riders_no_helmet) = 0 then null
      else round(100.0 * sum(s.riders_helmet) / (sum(s.riders_helmet) + sum(s.riders_no_helmet)), 1)
    end
  from public.detection_sessions s
  where s.started_at >= p_from and s.started_at < p_to
    and (p_source is null or s.source = p_source)
  group by 1, 2
  order by sum(s.riders_helmet) + sum(s.riders_no_helmet) desc
$$;

-- Sessions store totals rather than per-rider times, so an hour here means the
-- hour the session started. Video sessions use processing time, so live only.
create function public.compliance_by_hour(p_from timestamptz, p_to timestamptz)
returns table (hour_of_day integer, riders_helmet bigint, riders_no_helmet bigint, compliance_pct numeric)
language sql
stable
security invoker
set search_path = ''
as $$
  select
    extract(hour from s.started_at at time zone 'Asia/Karachi')::integer,
    sum(s.riders_helmet)::bigint,
    sum(s.riders_no_helmet)::bigint,
    case when sum(s.riders_helmet) + sum(s.riders_no_helmet) = 0 then null
      else round(100.0 * sum(s.riders_helmet) / (sum(s.riders_helmet) + sum(s.riders_no_helmet)), 1)
    end
  from public.detection_sessions s
  where s.started_at >= p_from and s.started_at < p_to and s.source = 'live'
  group by 1
  order by 1
$$;

create function public.compliance_by_source(p_from timestamptz, p_to timestamptz)
returns table (source public.detection_source, riders_helmet bigint, riders_no_helmet bigint, compliance_pct numeric)
language sql
stable
security invoker
set search_path = ''
as $$
  select
    s.source,
    sum(s.riders_helmet)::bigint,
    sum(s.riders_no_helmet)::bigint,
    case when sum(s.riders_helmet) + sum(s.riders_no_helmet) = 0 then null
      else round(100.0 * sum(s.riders_helmet) / (sum(s.riders_helmet) + sum(s.riders_no_helmet)), 1)
    end
  from public.detection_sessions s
  where s.started_at >= p_from and s.started_at < p_to
  group by 1
  order by 1
$$;

create function public.ocr_breakdown(
  p_from timestamptz,
  p_to timestamptz,
  p_source public.detection_source default null
)
returns table (status public.plate_ocr_status, total bigint)
language sql
stable
security invoker
set search_path = ''
as $$
  select plate_ocr_status, count(*)
  from public.violations
  where detected_at >= p_from and detected_at < p_to
    and (p_source is null or source = p_source)
  group by 1
  order by 2 desc
$$;

create function public.review_outcomes(
  p_from timestamptz,
  p_to timestamptz,
  p_source public.detection_source default null
)
returns table (status public.review_status, total bigint)
language sql
stable
security invoker
set search_path = ''
as $$
  select review_status, count(*)
  from public.violations
  where detected_at >= p_from and detected_at < p_to
    and (p_source is null or source = p_source)
  group by 1
  order by 2 desc
$$;

-- Sum of uploaded file sizes, for the 80% storage warning on the 1 GB free plan.
create function public.evidence_bytes_used()
returns bigint
language sql
stable
security invoker
set search_path = ''
as $$
  select coalesce(sum(bytes), 0)::bigint from public.violation_evidence
$$;
