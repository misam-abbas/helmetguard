import { ArrowLeft } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { Card, EmptyState, PageHeader, SourceTag } from "@/components/ui";
import { VIOLATION_ROW_COLUMNS, ViolationRows, type ViolationRow } from "@/components/ViolationRows";
import { requireStaff } from "@/lib/auth";
import { thumbnailsFor } from "@/lib/evidence";
import { compliance, formatClipTime, formatDateTime, formatNumber, formatPercent } from "@/lib/format";
import type { DetectionSession, Device } from "@/lib/types";

export const metadata: Metadata = { title: "Session" };

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <p className="text-xs text-ink-3">{label}</p>
      <p className="tabular text-lg font-semibold">{value}</p>
    </div>
  );
}

export default async function SessionPage({ params }: { params: Promise<{ id: string }> }) {
  const { supabase } = await requireStaff();
  const { id } = await params;
  if (!/^[0-9a-f-]{36}$/i.test(id)) notFound();

  const { data } = await supabase.from("detection_sessions").select("*").eq("id", id).maybeSingle();
  if (!data) notFound();
  const session = data as DetectionSession;

  const [deviceResult, violationResult] = await Promise.all([
    supabase.from("devices").select("*").eq("id", session.device_id).maybeSingle(),
    supabase.from("violations").select(VIOLATION_ROW_COLUMNS).eq("session_id", id).order("detected_at", { ascending: false }).limit(200),
  ]);
  const device = deviceResult.data as Device | null;
  const rows = (violationResult.data ?? []) as unknown as ViolationRow[];
  const thumbs = await thumbnailsFor(
    supabase,
    rows.map((r) => r.id),
  );

  return (
    <>
      <Link href="/sessions" className="mb-4 inline-flex items-center gap-1.5 text-sm text-ink-2 hover:text-ink">
        <ArrowLeft className="size-4" aria-hidden /> Sessions
      </Link>
      <PageHeader title={session.source === "video" ? (session.video_file_name ?? "Video session") : (session.location_label ?? "Live session")} subtitle={`${formatDateTime(session.started_at)} to ${formatDateTime(session.ended_at)}`}>
        <SourceTag source={session.source} />
      </PageHeader>

      <Card>
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
          <Stat label="Compliance" value={formatPercent(compliance(session.riders_helmet, session.riders_no_helmet))} />
          <Stat label="Motorcycles" value={formatNumber(session.motorcycles_total)} />
          <Stat label="Riders" value={formatNumber(session.riders_total)} />
          <Stat label="With helmet" value={formatNumber(session.riders_helmet)} />
          <Stat label="Without helmet" value={formatNumber(session.riders_no_helmet)} />
          <Stat label="Undecided" value={formatNumber(session.riders_unknown)} />
          <Stat label="AI alerts" value={formatNumber(session.violations_confirmed)} />
          <Stat label="Average FPS" value={session.avg_fps?.toFixed(1) ?? "—"} />
        </div>
      </Card>

      <div className="mt-4 grid gap-4 lg:grid-cols-2">
        <Card title="Device">
          <p className="text-sm">{device ? [device.manufacturer, device.model].filter(Boolean).join(" ") || device.label || "Unnamed device" : "Unknown device"}</p>
          <p className="mt-0.5 text-xs text-ink-3">
            Android {device?.android_version ?? "—"} · App {device?.app_version ?? "—"} · Model {session.model_version ?? "—"}
          </p>
          {session.source === "video" && <p className="mt-2 text-xs text-ink-3">Clip length {formatClipTime(session.video_duration_ms)} · {formatNumber(session.frames_processed)} frames processed</p>}
        </Card>
        <Card title="Settings used">
          <pre className="max-h-40 overflow-auto text-xs text-ink-2">{JSON.stringify(session.settings, null, 2)}</pre>
        </Card>
      </div>

      <h2 className="mb-3 mt-6 text-sm font-semibold">Violations in this session</h2>
      {rows.length === 0 ? <EmptyState title="No violations in this session" /> : <ViolationRows rows={rows} thumbs={thumbs} />}
    </>
  );
}
