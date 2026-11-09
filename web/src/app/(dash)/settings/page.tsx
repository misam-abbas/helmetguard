import type { Metadata } from "next";
import { SubmitButton } from "@/components/SubmitButton";
import { Card, inputClass, Notice, PageHeader } from "@/components/ui";
import { requireAdmin } from "@/lib/auth";
import { daysAgo, param, type SearchParams } from "@/lib/filters";
import { formatNumber } from "@/lib/format";
import { purgeEvidence, saveProfile, saveSettings } from "./actions";

export const metadata: Metadata = { title: "Settings" };

const FREE_STORAGE_BYTES = 1024 * 1024 * 1024;

const ERRORS: Record<string, string> = {
  range: "Retention must be 7 to 730 days, and the grace period 1 to 60 days.",
  save: "That could not be saved.",
  confirm: "Type PURGE exactly to remove old evidence.",
  purge: "The purge stopped part-way. Run it again to finish.",
};

export default async function SettingsPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const { supabase, profile } = await requireAdmin();
  const params = await searchParams;

  const { data: settings } = await supabase.from("app_settings").select("*").single();
  const retention = settings?.retention_days ?? 90;
  const cutoff = daysAgo(retention);

  const [{ count: purgeable }, { data: used }] = await Promise.all([
    supabase.from("violations").select("id", { count: "exact", head: true }).in("review_status", ["unreviewed", "rejected"]).lt("detected_at", cutoff),
    supabase.rpc("evidence_bytes_used"),
  ]);
  const bytes = Number(used ?? 0);
  const error = ERRORS[param(params, "error") ?? ""];
  const purged = param(params, "purged");

  return (
    <>
      <PageHeader title="Settings" />
      {error && <Notice tone="bad">{error}</Notice>}
      {param(params, "saved") && <Notice>Saved.</Notice>}
      {purged && <Notice>{purged} old alerts and their files were removed.</Notice>}

      <div className="grid max-w-3xl gap-4">
        <Card title="Retention and offline access">
          <form action={saveSettings} className="grid gap-3 sm:grid-cols-2">
            <label className="text-sm font-medium">
              Keep unreviewed and rejected alerts (days)
              <input name="retention_days" type="number" min={7} max={730} required defaultValue={retention} className={`${inputClass} mt-1`} />
            </label>
            <label className="text-sm font-medium">
              Offline grace period on phones (days)
              <input name="offline_grace_days" type="number" min={1} max={60} required defaultValue={settings?.offline_grace_days ?? 7} className={`${inputClass} mt-1`} />
            </label>
            <div className="sm:col-span-2">
              <SubmitButton>Save settings</SubmitButton>
            </div>
          </form>
        </Card>

        <Card title="Storage and evidence purge">
          <p className="text-sm text-ink-2">
            Using about {(bytes / 1024 / 1024).toFixed(1)} MB of the 1 GB free plan ({((100 * bytes) / FREE_STORAGE_BYTES).toFixed(1)}%).
          </p>
          <div className="my-3 h-2 rounded-full bg-surface-2">
            <div className="h-2 rounded-r bg-live" style={{ width: `${Math.min(100, (100 * bytes) / FREE_STORAGE_BYTES)}%` }} />
          </div>
          <p className="mb-3 text-sm text-ink-2">
            <strong className="font-medium text-ink">{formatNumber(purgeable ?? 0)}</strong> alerts are older than {retention} days and can be removed. Confirmed violations are always kept. This cannot be undone.
          </p>
          <form action={purgeEvidence} className="flex flex-wrap gap-2">
            <input name="confirm" placeholder="Type PURGE" aria-label="Type PURGE to confirm" autoComplete="off" className={`${inputClass} w-40`} />
            <SubmitButton variant="secondary">Remove old evidence</SubmitButton>
          </form>
        </Card>

        <Card title="Your profile">
          <form action={saveProfile} className="flex flex-wrap items-end gap-2">
            <label className="min-w-56 flex-1 text-sm font-medium">
              Full name
              <input name="full_name" defaultValue={profile.full_name} maxLength={120} className={`${inputClass} mt-1`} />
            </label>
            <SubmitButton variant="secondary">Save</SubmitButton>
          </form>
          <p className="mt-2 text-sm text-ink-3">{profile.email}</p>
        </Card>
      </div>
    </>
  );
}
