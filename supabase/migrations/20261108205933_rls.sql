-- Row Level Security (plan section 9). Signed-out visitors can read nothing:
-- every policy below is limited to the authenticated role.
-- auth.uid() and the role helper are wrapped in (select ...) so Postgres
-- evaluates them once per query instead of once per row.

alter table public.profiles enable row level security;
alter table public.devices enable row level security;
alter table public.detection_sessions enable row level security;
alter table public.violations enable row level security;
alter table public.violation_evidence enable row level security;
alter table public.review_events enable row level security;
alter table public.app_settings enable row level security;

-- profiles
create policy "read own or staff reads all" on public.profiles
  for select to authenticated
  using (
    id = (select auth.uid())
    or (select private.current_role()) in ('reviewer', 'admin')
  );

-- The protect_profile_fields trigger limits non-admins to their own name.
create policy "edit own or admin edits all" on public.profiles
  for update to authenticated
  using (id = (select auth.uid()) or (select private.current_role()) = 'admin')
  with check (id = (select auth.uid()) or (select private.current_role()) = 'admin');

-- devices
create policy "read own devices or staff reads all" on public.devices
  for select to authenticated
  using (
    (owner_id = (select auth.uid()) and (select private.current_role()) is not null)
    or (select private.current_role()) in ('reviewer', 'admin')
  );

create policy "active users add own devices" on public.devices
  for insert to authenticated
  with check (owner_id = (select auth.uid()) and (select private.current_role()) is not null);

create policy "active users update own devices" on public.devices
  for update to authenticated
  using (owner_id = (select auth.uid()) and (select private.current_role()) is not null)
  with check (owner_id = (select auth.uid()) and (select private.current_role()) is not null);

create policy "admins delete devices" on public.devices
  for delete to authenticated
  using ((select private.current_role()) = 'admin');

-- detection_sessions
create policy "read own sessions or staff reads all" on public.detection_sessions
  for select to authenticated
  using (
    (operator_id = (select auth.uid()) and (select private.current_role()) is not null)
    or (select private.current_role()) in ('reviewer', 'admin')
  );

create policy "active users add own sessions" on public.detection_sessions
  for insert to authenticated
  with check (
    operator_id = (select auth.uid())
    and (select private.current_role()) is not null
    and exists (
      select 1 from public.devices d
      where d.id = device_id and d.owner_id = (select auth.uid())
    )
  );

create policy "active users update own sessions" on public.detection_sessions
  for update to authenticated
  using (operator_id = (select auth.uid()) and (select private.current_role()) is not null)
  with check (operator_id = (select auth.uid()) and (select private.current_role()) is not null);

create policy "admins delete sessions" on public.detection_sessions
  for delete to authenticated
  using ((select private.current_role()) = 'admin');

-- violations
create policy "read own violations or staff reads all" on public.violations
  for select to authenticated
  using (
    (operator_id = (select auth.uid()) and (select private.current_role()) is not null)
    or (select private.current_role()) in ('reviewer', 'admin')
  );

create policy "operators insert own violations" on public.violations
  for insert to authenticated
  with check (
    operator_id = (select auth.uid())
    and (select private.current_role()) is not null
    and exists (
      select 1 from public.devices d
      where d.id = device_id and d.owner_id = (select auth.uid())
    )
  );

-- The protect_violation_fields trigger limits this to review fields.
create policy "staff update reviews" on public.violations
  for update to authenticated
  using ((select private.current_role()) in ('reviewer', 'admin'))
  with check ((select private.current_role()) in ('reviewer', 'admin'));

create policy "admins delete violations" on public.violations
  for delete to authenticated
  using ((select private.current_role()) = 'admin');

-- violation_evidence
create policy "read own evidence or staff reads all" on public.violation_evidence
  for select to authenticated
  using (
    (select private.current_role()) in ('reviewer', 'admin')
    or (
      (select private.current_role()) is not null
      and exists (
        select 1 from public.violations v
        where v.id = violation_id and v.operator_id = (select auth.uid())
      )
    )
  );

create policy "active users add evidence for own violations" on public.violation_evidence
  for insert to authenticated
  with check (
    (select private.current_role()) is not null
    and split_part(storage_path, '/', 1) = (select auth.uid())::text
    and exists (
      select 1 from public.violations v
      where v.id = violation_id and v.operator_id = (select auth.uid())
    )
  );

create policy "admins delete evidence" on public.violation_evidence
  for delete to authenticated
  using ((select private.current_role()) = 'admin');

-- review_events: written only by the audit trigger, never by a client.
create policy "staff read review events" on public.review_events
  for select to authenticated
  using ((select private.current_role()) in ('reviewer', 'admin'));

-- app_settings
create policy "active users read settings" on public.app_settings
  for select to authenticated
  using ((select private.current_role()) is not null);

create policy "admins update settings" on public.app_settings
  for update to authenticated
  using ((select private.current_role()) = 'admin')
  with check ((select private.current_role()) = 'admin');
