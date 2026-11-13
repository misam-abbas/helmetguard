import type { Metadata } from "next";
import Link from "next/link";
import { HourChart, SourceComparisonChart, TrendChart } from "@/components/charts";
import { SourceSwitch } from "@/components/SourceSwitch";
import { Card, cn, EmptyState, Notice, PageHeader } from "@/components/ui";
import { requireStaff } from "@/lib/auth";
import { buildHref, param, parseSource, rangeFromDays, type SearchParams } from "@/lib/filters";
import { formatBucket, formatNumber, formatPercent } from "@/lib/format";
import type { TrendPoint } from "@/lib/types";

export const metadata: Metadata = { title: "Analytics" };

const RANGES = [
  { days: 7, label: "7 days" },
  { days: 30, label: "30 days" },
  { days: 90, label: "90 days" },
  { days: 365, label: "12 months" },
];

const REVIEW_LABELS: Record<string, string> = { unreviewed: "Unreviewed", confirmed: "Confirmed", rejected: "Rejected", needs_info: "Needs info" };

function Breakdown({ rows, labels }: { rows: { status: string; total: number }[]; labels: Record<string, string> }) {
  const sum = rows.reduce((acc, r) => acc + Number(r.total), 0);
  if (sum === 0) return <EmptyState title="No data in this period" />;
  return (
    <ul className="space-y-2.5">
      {rows.map((row) => {
        const share = (100 * Number(row.total)) / sum;
        return (
          <li key={row.status}>
            <div className="mb-1 flex justify-between text-sm">
              <span className="text-ink-2">{labels[row.status] ?? row.status}</span>
              <span className="tabular">
                {formatNumber(Number(row.total))} <span className="text-ink-3">({share.toFixed(0)}%)</span>
              </span>
            </div>
            <div className="h-2 rounded-full bg-surface-2">
              <div className="h-2 rounded-r bg-live" style={{ width: `${share}%` }} />
            </div>
          </li>
        );
      })}
    </ul>
  );
}

export default async function AnalyticsPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const { supabase } = await requireStaff();
  const params = await searchParams;
  const source = parseSource(params);
  const days = RANGES.find((r) => String(r.days) === param(params, "days"))?.days ?? 30;
  const { from, to } = rangeFromDays(days);
  const interval = days > 90 ? "month" : "day";
  const base = { p_from: from, p_to: to };
  const withSource = { ...base, p_source: source };

  const [trend, location, hour, bySource, review] = await Promise.all([
    supabase.rpc("violation_trend", { ...withSource, p_interval: interval }),
    supabase.rpc("compliance_by_location", withSource),
    supabase.rpc("compliance_by_hour", base),
    supabase.rpc("compliance_by_source", base),
    supabase.rpc("review_outcomes", withSource),
  ]);
  const failed = [trend, location, hour, bySource, review].some((r) => r.error);

  const trendData = ((trend.data ?? []) as TrendPoint[]).map((t) => ({ label: formatBucket(t.bucket, interval), live: Number(t.live), video: Number(t.video) }));
  const locations = (location.data ?? []) as { location_label: string; source: string; riders_helmet: number; riders_no_helmet: number; compliance_pct: number | null }[];
  const hours = (hour.data ?? []) as { hour_of_day: number; compliance_pct: number | null }[];
  const hourLookup = new Map(hours.map((h) => [h.hour_of_day, h.compliance_pct]));
  const hourData = Array.from({ length: 24 }, (_, h) => ({ hour: String(h).padStart(2, "0"), compliance: hourLookup.get(h) ?? null }));
  const sources = (bySource.data ?? []) as { source: string; compliance_pct: number | null }[];
  const reviewRows = (review.data ?? []) as { status: string; total: number }[];
  const confirmed = Number(reviewRows.find((r) => r.status === "confirmed")?.total ?? 0);
  const rejected = Number(reviewRows.find((r) => r.status === "rejected")?.total ?? 0);
  const precision = confirmed + rejected === 0 ? null : (100 * confirmed) / (confirmed + rejected);

  return (
    <>
      <PageHeader title="Reports and analytics" subtitle="Grouped in Pakistan time">
        <SourceSwitch path="/analytics" params={params} current={source} />
      </PageHeader>

      <div role="group" aria-label="Time range" className="mb-4 flex flex-wrap gap-1.5">
        {RANGES.map((range) => (
          <Link key={range.days} href={buildHref("/analytics", params, { days: String(range.days) })} aria-current={days === range.days ? "true" : undefined} className={cn("rounded-full border px-3 py-1 text-xs font-medium", days === range.days ? "border-primary bg-primary text-primary-ink" : "border-line text-ink-2 hover:bg-surface-2")}>
            {range.label}
          </Link>
        ))}
      </div>

      {failed && <Notice tone="bad">Some reports could not be loaded. Refresh to try again.</Notice>}

      <div className="grid gap-4 lg:grid-cols-2">
        <Card title={interval === "month" ? "AI alerts per month" : "AI alerts per day"} className="lg:col-span-2">
          {trendData.length === 0 ? <EmptyState title="No alerts in this period" /> : <TrendChart data={trendData} showLive={source !== "video"} showVideo={source !== "live"} />}
        </Card>

        <Card title="Real-world AI precision">
          <p className="tabular text-3xl font-semibold tracking-tight">{formatPercent(precision)}</p>
          <p className="mt-1 text-sm text-ink-2">
            Share of reviewed alerts that a person confirmed ({formatNumber(confirmed)} confirmed, {formatNumber(rejected)} rejected).
          </p>
        </Card>

        <Card title="Live and video compliance">
          {sources.length === 0 ? <EmptyState title="No sessions in this period" /> : <SourceComparisonChart data={sources.map((s) => ({ source: s.source === "live" ? "Live camera" : "Video", compliance: s.compliance_pct === null ? null : Number(s.compliance_pct) }))} />}
        </Card>

        <Card title="Live compliance by hour session started">
          {hours.length === 0 ? <EmptyState title="No live sessions in this period" /> : <HourChart data={hourData.map((h) => ({ ...h, compliance: h.compliance === null ? null : Number(h.compliance) }))} />}
        </Card>

        <Card title="Review outcomes" className="lg:col-span-2">
          <Breakdown rows={reviewRows} labels={REVIEW_LABELS} />
        </Card>

        <Card title="Compliance by location" className="lg:col-span-2">
          {locations.length === 0 ? (
            <EmptyState title="No sessions in this period" />
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead className="border-b border-line text-xs text-ink-3">
                  <tr>
                    <th className="py-2 pr-4 font-medium">Location or video</th>
                    <th className="py-2 pr-4 font-medium">Source</th>
                    <th className="py-2 pr-4 text-right font-medium">With helmet</th>
                    <th className="py-2 pr-4 text-right font-medium">Without helmet</th>
                    <th className="py-2 text-right font-medium">Compliance</th>
                  </tr>
                </thead>
                <tbody className="tabular divide-y divide-line">
                  {locations.map((row) => (
                    <tr key={`${row.location_label}-${row.source}`}>
                      <td className="max-w-64 truncate py-2 pr-4">{row.location_label}</td>
                      <td className="py-2 pr-4 capitalize text-ink-2">{row.source}</td>
                      <td className="py-2 pr-4 text-right">{formatNumber(Number(row.riders_helmet))}</td>
                      <td className="py-2 pr-4 text-right">{formatNumber(Number(row.riders_no_helmet))}</td>
                      <td className="py-2 text-right font-medium">{formatPercent(row.compliance_pct === null ? null : Number(row.compliance_pct))}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      </div>
    </>
  );
}
