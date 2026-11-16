import type { Metadata } from "next";
import { TrendChart } from "@/components/charts";
import { SourceSwitch } from "@/components/SourceSwitch";
import { Card, EmptyState, Notice, PageHeader, TextLink } from "@/components/ui";
import { VIOLATION_ROW_COLUMNS, ViolationRows, type ViolationRow } from "@/components/ViolationRows";
import { requireStaff } from "@/lib/auth";
import { thumbnailsFor } from "@/lib/evidence";
import { buildHref, param, parseSource, rangeFromDays, type SearchParams } from "@/lib/filters";
import { formatBucket, formatNumber, formatPercent } from "@/lib/format";
import type { DashboardSummary, TrendPoint } from "@/lib/types";

export const metadata: Metadata = { title: "Dashboard" };

const FREE_STORAGE_BYTES = 1024 * 1024 * 1024;

function Stat({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="rounded-lg border border-line bg-surface p-4">
      <p className="text-xs text-ink-3">{label}</p>
      <p className="tabular mt-1 text-2xl font-semibold tracking-tight">{value}</p>
      {hint && <p className="mt-0.5 text-xs text-ink-3">{hint}</p>}
    </div>
  );
}

export default async function DashboardPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const { supabase } = await requireStaff();
  const params = await searchParams;
  const source = parseSource(params);
  const { from, to } = rangeFromDays(30);
  const range = { p_from: from, p_to: to, p_source: source };

  let recent = supabase.from("violations").select(VIOLATION_ROW_COLUMNS).order("detected_at", { ascending: false }).limit(10);
  if (source) recent = recent.eq("source", source);

  const [summaryResult, trendResult, recentResult, storageResult] = await Promise.all([
    supabase.rpc("dashboard_summary", range),
    supabase.rpc("violation_trend", { ...range, p_interval: "day" }),
    recent,
    supabase.rpc("evidence_bytes_used"),
  ]);

  const summary = summaryResult.data as DashboardSummary | null;
  const trend = (trendResult.data ?? []) as TrendPoint[];
  const rows = (recentResult.data ?? []) as unknown as ViolationRow[];
  const thumbs = await thumbnailsFor(
    supabase,
    rows.map((r) => r.id),
  );
  const storageUsed = Number(storageResult.data ?? 0) / FREE_STORAGE_BYTES;
  const failed = summaryResult.error || trendResult.error || recentResult.error;

  return (
    <>
      <PageHeader title="Dashboard" subtitle="Last 30 days, Pakistan time">
        <SourceSwitch path="/dashboard" params={params} current={source} />
      </PageHeader>

      {param(params, "notice") === "password" && <Notice>Your password was changed. Use the new one next time you sign in.</Notice>}
      {failed && <Notice tone="bad">Some numbers could not be loaded. Refresh to try again.</Notice>}
      {storageUsed >= 0.8 && (
        <Notice tone="warn">
          Evidence storage is {Math.round(storageUsed * 100)}% full on the 1 GB free plan. An admin can remove old evidence from Settings.
        </Notice>
      )}

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat label="Helmet compliance" value={formatPercent(summary?.compliance_pct)} hint={`${formatNumber(summary?.riders_helmet)} of ${formatNumber((summary?.riders_helmet ?? 0) + (summary?.riders_no_helmet ?? 0))} decided riders`} />
        <Stat label="Motorcycles" value={formatNumber(summary?.motorcycles)} hint={`${formatNumber(summary?.sessions)} sessions`} />
        <Stat label="Riders" value={formatNumber(summary?.riders)} hint={`${formatNumber(summary?.riders_unknown)} undecided`} />
        <Stat label="With helmet" value={formatNumber(summary?.riders_helmet)} />
        <Stat label="Without helmet" value={formatNumber(summary?.riders_no_helmet)} />
        <Stat label="AI alerts" value={formatNumber(summary?.ai_alerts)} hint="Unverified until reviewed" />
        <Stat label="Waiting for review" value={formatNumber(summary?.unreviewed)} />
        <Stat label="Reviewed violations" value={formatNumber(summary?.reviewed_confirmed)} hint={`${formatNumber(summary?.reviewed_rejected)} rejected`} />
      </div>

      <Card title="AI alerts per day" className="mt-4">
        {trend.length === 0 ? (
          <EmptyState title="No alerts in this period">Alerts appear here after the Android app syncs them.</EmptyState>
        ) : (
          <TrendChart data={trend.map((t) => ({ label: formatBucket(t.bucket), live: Number(t.live), video: Number(t.video) }))} showLive={source !== "video"} showVideo={source !== "live"} />
        )}
      </Card>

      <div className="mb-3 mt-6 flex items-center justify-between">
        <h2 className="text-sm font-semibold">Recent violations</h2>
        <TextLink href={buildHref("/violations", params, {})}>View all</TextLink>
      </div>
      {rows.length === 0 ? <EmptyState title="No violations yet" /> : <ViolationRows rows={rows} thumbs={thumbs} />}
    </>
  );
}
