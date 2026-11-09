-- Private evidence bucket (plan section 9).
-- Path: <userId>/<violationId>/<kind>_<seq>.jpg. The first folder must be the uploader.

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('evidence', 'evidence', false, 5242880, array['image/jpeg', 'image/webp']);

create policy "operators upload to own folder" on storage.objects
  for insert to authenticated
  with check (
    bucket_id = 'evidence'
    and (storage.foldername(name))[1] = (select auth.uid())::text
    and (select private.current_role()) is not null
  );

-- Needed so a retried upload can overwrite the same path.
create policy "operators overwrite own files" on storage.objects
  for update to authenticated
  using (
    bucket_id = 'evidence'
    and (storage.foldername(name))[1] = (select auth.uid())::text
    and (select private.current_role()) is not null
  )
  with check (
    bucket_id = 'evidence'
    and (storage.foldername(name))[1] = (select auth.uid())::text
    and (select private.current_role()) is not null
  );

create policy "read own files or staff reads all" on storage.objects
  for select to authenticated
  using (
    bucket_id = 'evidence'
    and (
      (select private.current_role()) in ('reviewer', 'admin')
      or (
        (storage.foldername(name))[1] = (select auth.uid())::text
        and (select private.current_role()) is not null
      )
    )
  );

create policy "admins delete files" on storage.objects
  for delete to authenticated
  using (bucket_id = 'evidence' and (select private.current_role()) = 'admin');
