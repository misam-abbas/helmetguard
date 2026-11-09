import type { Metadata } from "next";
import Link from "next/link";
import { SourceSwitch } from "@/components/SourceSwitch";
import { EmptyState, Notice, PageHeader, SourceTag } from "@/components/ui";
import { requireStaff } from "@/lib/auth";
import { parseSource, type SearchParams } from "@/lib/filters";
import { compliance, formatClipTime, formatDateTime, formatNumber, formatPercent } from "@/lib/format";
import type { DetectionSession } from "@/lib/types";

export const metadata: Metadata = { title: "Sessions" };

function title(s: DetectionSession) {
  return s.source === "video" ? (s.video_file_name ?? "Video") : (s.location_label ?? "Unlabelled location");
}

export default async function SessionsPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const { supabase } = await requireStaff();
  const params = await searchParams;
  const source = parseSource(params);

  let query = supabase.from("detection_sessions").select("*").order("started_at", { ascending: false }).limit(100);
  if (source) query = query.eq("source", source);
  const { data, error } = await query;
  const sessions = (data ?? []) as DetectionSession[];

  return (
    <>
      <PageHeader title="Detection sessions" subtitle="The latest 100 live and video sessions">
        <SourceSwitch path="/sessions" params={params} current={source} />
      </PageHeader>

      {error && <Notice tone="bad">Sessions could not be loaded. Refresh to try again.</Notice>}

      {sessions.length === 0 ? (
        <EmptyState title="No sessions yet">Sessions appear after the Android app syncs them.</EmptyState>
      ) : (
        <ul className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          {sessions.map((s) => (
            <li key={s.id}>
              <Link href={`/sessions/${s.id}`} className="block h-full rounded-lg border border-line bg-surface p-4 hover:bg-surface-2/60">
                <div className="flex items-start justify-between gap-2">
                  <p className="truncate text-sm font-medium">{title(s)}</p>
                  <SourceTag source={s.source} />
                </div>
                <p className="tabular mt-0.5 text-xs text-ink-3">
                  {formatDateTime(s.started_at)}
                  {s.source === "video" && s.video_duration_ms !== null && ` · ${formatClipTime(s.video_duration_ms)} long`} · <span className="capitalize">{s.status}</span>
                </p>
                <dl className="mt-3 grid grid-cols-3 gap-2 text-center">
                  <div>
                    <dt className="text-xs text-ink-3">Bikes</dt>
                    <dd className="tabular text-sm font-semibold">{formatNumber(s.motorcycles_total)}</dd>
                  </div>
                  <div>
                    <dt className="text-xs text-ink-3">Alerts</dt>
                    <dd className="tabular text-sm font-semibold">{formatNumber(s.violations_confirmed)}</dd>
                  </div>
                  <div>
                    <dt className="text-xs text-ink-3">Compliance</dt>
                    <dd className="tabular text-sm font-semibold">{formatPercent(compliance(s.riders_helmet, s.riders_no_helmet))}</dd>
                  </div>
                </dl>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}
