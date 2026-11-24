"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { getCurrentUser, requireAdmin } from "@/lib/auth";

export async function saveSettings(form: FormData) {
  const { supabase, userId } = await requireAdmin();
  const retention = Number(form.get("retention_days"));
  const grace = Number(form.get("offline_grace_days"));
  if (!Number.isInteger(retention) || retention < 7 || retention > 730 || !Number.isInteger(grace) || grace < 1 || grace > 60) {
    redirect("/settings?error=range");
  }
  const { error } = await supabase
    .from("app_settings")
    .update({ retention_days: retention, offline_grace_days: grace, updated_by: userId, updated_at: new Date().toISOString() })
    .eq("id", true);
  if (error) redirect("/settings?error=save");
  revalidatePath("/settings");
  redirect("/settings?saved=1");
}

export async function saveProfile(form: FormData) {
  const current = await getCurrentUser();
  if (!current) redirect("/login");
  const { error } = await current.supabase
    .from("profiles")
    .update({ full_name: String(form.get("full_name") ?? "").trim().slice(0, 120) })
    .eq("id", current.userId);
  if (error) redirect("/settings?error=save");
  revalidatePath("/", "layout");
  redirect("/settings?saved=1");
}

const PURGE_BATCH = 200;

// Removes unreviewed and rejected alerts older than the retention period, with
// their files. Confirmed violations are always kept. Uses the admin's own session.
export async function purgeEvidence(form: FormData) {
  const { supabase } = await requireAdmin();
  if (String(form.get("confirm")) !== "PURGE") redirect("/settings?error=confirm");

  const { data: settings } = await supabase.from("app_settings").select("retention_days").single();
  const days = settings?.retention_days ?? 90;
  const cutoff = new Date(Date.now() - days * 86_400_000).toISOString();

  let removed = 0;
  for (;;) {
    const { data: batch } = await supabase
      .from("violations")
      .select("id")
      .in("review_status", ["unreviewed", "rejected"])
      .lt("detected_at", cutoff)
      .limit(PURGE_BATCH);
    const ids = (batch ?? []).map((v) => v.id as string);
    if (ids.length === 0) break;

    const { data: files } = await supabase.from("violation_evidence").select("storage_path").in("violation_id", ids);
    const paths = (files ?? []).map((f) => f.storage_path as string);
    if (paths.length > 0) {
      const { error: storageError } = await supabase.storage.from("evidence").remove(paths);
      if (storageError) redirect("/settings?error=purge");
    }
    const { error } = await supabase.from("violations").delete().in("id", ids);
    if (error) redirect("/settings?error=purge");
    removed += ids.length;
    if (ids.length < PURGE_BATCH) break;
  }

  revalidatePath("/settings");
  redirect(`/settings?purged=${removed}`);
}

// A fresh start: every alert with its photos and reviews, and every detection session, for all
// accounts. Accounts, phones and settings stay. Photos go through the Storage API with the admin's
// own session, so the files themselves are deleted, not just their records.
export async function clearAllData(form: FormData) {
  const { supabase } = await requireAdmin();
  if (String(form.get("confirm")) !== "DELETE ALL") redirect("/settings?error=confirm_all");

  let files = 0;
  for (;;) {
    const { data: rows, error: readError } = await supabase.from("violation_evidence").select("id, storage_path").limit(PURGE_BATCH);
    if (readError) redirect("/settings?error=clear");
    if (!rows || rows.length === 0) break;
    const { error: storageError } = await supabase.storage.from("evidence").remove(rows.map((r) => r.storage_path as string));
    if (storageError) redirect("/settings?error=clear");
    const { error } = await supabase.from("violation_evidence").delete().in("id", rows.map((r) => r.id as string));
    if (error) redirect("/settings?error=clear");
    files += rows.length;
  }

  // Reviews go with their alerts, and alerts with their sessions.
  const { count: alerts } = await supabase.from("violations").select("id", { count: "exact", head: true });
  const { error: alertsError } = await supabase.from("violations").delete().not("id", "is", null);
  if (alertsError) redirect("/settings?error=clear");
  const { count: sessions } = await supabase.from("detection_sessions").select("id", { count: "exact", head: true });
  const { error: sessionsError } = await supabase.from("detection_sessions").delete().not("id", "is", null);
  if (sessionsError) redirect("/settings?error=clear");

  revalidatePath("/", "layout");
  redirect(`/settings?cleared=${alerts ?? 0}-${files}-${sessions ?? 0}`);
}
