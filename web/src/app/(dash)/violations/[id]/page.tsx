import { ArrowLeft, Check, CircleHelp, X } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { SubmitButton } from "@/components/SubmitButton";
import { Card, inputClass, Notice, OcrBadge, ReviewBadge, SourceTag } from "@/components/ui";
import { requireStaff } from "@/lib/auth";
import { signPaths } from "@/lib/evidence";
import { param, type SearchParams } from "@/lib/filters";
import { formatClipTime, formatConfidence, formatDateTime } from "@/lib/format";
import type { Box, Evidence, ReviewEvent, Rider, Violation } from "@/lib/types";
import { correctPlate, saveNotes, setReviewStatus } from "./actions";

export const metadata: Metadata = { title: "Violation" };

function Overlay({ box, color, label, below = false }: { box: Box; color: string; label: string; below?: boolean }) {
  const [x1, y1, x2, y2] = box;
  return (
    <div className="pointer-events-none absolute border-2" style={{ left: `${x1 * 100}%`, top: `${y1 * 100}%`, width: `${(x2 - x1) * 100}%`, height: `${(y2 - y1) * 100}%`, borderColor: color }}>
      <span className={`absolute left-0 whitespace-nowrap px-1 text-xs font-medium text-white ${below ? "-bottom-5" : "-top-5"}`} style={{ background: color }}>
        {label}
      </span>
    </div>
  );
}

function Photo({ url, alt, note }: { url?: string; alt: string; note?: string }) {
  if (!url) return null;
  return (
    <figure>
      <a href={url} target="_blank" rel="noreferrer">
        {/* Signed links expire hourly, so these bypass next/image on purpose. */}
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src={url} alt={alt} loading="lazy" className="h-40 w-full rounded-md bg-surface-2 object-contain" />
      </a>
      <figcaption className="mt-1 text-xs text-ink-3">
        {alt}
        {note && ` · ${note}`}
      </figcaption>
    </figure>
  );
}

function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-baseline justify-between gap-4 py-1.5">
      <dt className="text-sm text-ink-3">{label}</dt>
      <dd className="tabular text-right text-sm">{children}</dd>
    </div>
  );
}

const ACTION_LABEL: Record<ReviewEvent["action"], string> = {
  status_changed: "Changed review status",
  plate_corrected: "Corrected plate",
  note_added: "Edited notes",
};

export default async function ViolationPage({ params, searchParams }: { params: Promise<{ id: string }>; searchParams: Promise<SearchParams> }) {
  const { supabase } = await requireStaff();
  const { id } = await params;
  const query = await searchParams;
  if (!/^[0-9a-f-]{36}$/i.test(id)) notFound();

  const [violationResult, evidenceResult, eventsResult] = await Promise.all([
    supabase.from("violations").select("*, session:detection_sessions(video_file_name, location_label)").eq("id", id).maybeSingle(),
    supabase.from("violation_evidence").select("*").eq("violation_id", id).order("kind").order("seq"),
    supabase.from("review_events").select("id, action, old_value, new_value, created_at, actor:profiles(full_name, email)").eq("violation_id", id).order("created_at", { ascending: false }),
  ]);

  if (!violationResult.data) notFound();
  const violation = violationResult.data as unknown as Violation & { session: { video_file_name: string | null; location_label: string | null } | null };
  const evidence = (evidenceResult.data ?? []) as Evidence[];
  const events = (eventsResult.data ?? []) as unknown as ReviewEvent[];

  // The thumbnail is for lists only; everything else is signed for this page.
  const urls = await signPaths(
    supabase,
    evidence.filter((e) => e.kind !== "thumb").map((e) => e.storage_path),
  );
  const urlOf = (e?: Evidence) => (e ? urls.get(e.storage_path) : undefined);
  const first = (kind: Evidence["kind"]) => evidence.find((e) => e.kind === kind);
  const all = (kind: Evidence["kind"]) => evidence.filter((e) => e.kind === kind);

  const full = first("full_frame");
  const riders = (violation.riders ?? []) as Rider[];
  const riderColor = (status: Rider["status"]) => (status === "no_helmet" ? "#d92d20" : status === "helmet" ? "#12864b" : "#b76e00");
  const riderText = (r: Rider) => `${r.role === "pillion" ? "Pillion" : "Driver"}: ${r.status === "no_helmet" ? "No helmet" : r.status === "helmet" ? "Helmet" : "Undecided"} ${formatConfidence(r.confidence)}`;

  return (
    <>
      <Link href="/violations" className="mb-4 inline-flex items-center gap-1.5 text-sm text-ink-2 hover:text-ink">
        <ArrowLeft className="size-4" aria-hidden /> Violations
      </Link>

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <h1 className="plate text-xl font-semibold">{violation.plate_text_final ?? "Plate not read"}</h1>
        <ReviewBadge status={violation.review_status} />
        <OcrBadge status={violation.plate_ocr_status} />
        <SourceTag source={violation.source} />
      </div>

      {param(query, "saved") && <Notice>Saved and added to the audit history.</Notice>}
      {param(query, "error") && <Notice tone="bad">That change could not be saved. Check your access and try again.</Notice>}

      <div className="grid gap-4 xl:grid-cols-[1fr_340px]">
        <div className="min-w-0 space-y-4">
          <Card title="Road scene">
            <div id="frame" className="scroll-mt-20">
              {urlOf(full) && full ? (
                <div className="relative overflow-hidden rounded-md pt-5">
                  <div className="relative">
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img src={urlOf(full)} alt="Full road scene from the best frame" className="block w-full" />
                    {full.bbox && <Overlay box={full.bbox} color="#b76e00" label={`#${violation.track_id} Motorcycle ${formatConfidence(violation.motorcycle_confidence)}`} />}
                    {/* Riders sit side by side, so alternate labels above and below to keep both readable. */}
                    {riders.map((rider, index) => rider.box && <Overlay key={index} box={rider.box} color={riderColor(rider.status)} label={riderText(rider)} below={index % 2 === 1} />)}
                  </div>
                </div>
              ) : (
                <p className="text-sm text-ink-2">The full frame has not been uploaded.</p>
              )}
            </div>
          </Card>

          <Card title="Evidence photos">
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
              <Photo url={urlOf(first("motorcycle"))} alt="Motorcycle" />
              {all("rider").map((e, i) => (
                <Photo key={e.id} url={urlOf(e)} alt={`Rider ${i + 1}`} />
              ))}
              <Photo url={urlOf(first("plate"))} alt="Plate (original)" />
              <Photo url={urlOf(first("plate_enhanced"))} alt="Plate (enhanced)" note="Edited copy used for OCR" />
              <Photo url={urlOf(first("plate_source_frame"))} alt="Frame the plate came from" />
              <Photo url={urlOf(first("alt_frame"))} alt="Second-best frame" />
              <Photo url={urlOf(first("still_hires"))} alt="Full-resolution still" />
            </div>
          </Card>
        </div>

        <div className="space-y-4">
          <Card title="Decision">
            <p className="mb-3 text-sm text-ink-2">This is an AI alert until you confirm it. HelmetGuard never issues fines.</p>
            <form action={setReviewStatus} className="grid grid-cols-2 gap-2">
              <input type="hidden" name="id" value={violation.id} />
              <SubmitButton name="status" value="confirmed" className="col-span-2">
                <Check className="size-4" aria-hidden /> Confirm violation
              </SubmitButton>
              <SubmitButton name="status" value="rejected" variant="secondary">
                <X className="size-4" aria-hidden /> Reject
              </SubmitButton>
              <SubmitButton name="status" value="needs_info" variant="secondary">
                <CircleHelp className="size-4" aria-hidden /> Needs info
              </SubmitButton>
            </form>
          </Card>

          <Card title="Number plate">
            <dl className="mb-3 divide-y divide-line">
              <Fact label="OCR text">
                <span className="plate">{violation.plate_text_ocr ?? "None stored"}</span>
              </Fact>
              <Fact label="OCR confidence">{formatConfidence(violation.plate_ocr_confidence)}</Fact>
              <Fact label="Engine">{violation.ocr_engine ?? "—"}</Fact>
            </dl>
            {violation.plate_candidates?.length > 0 && (
              <p className="mb-3 text-xs text-ink-3">
                Alternatives: <span className="plate">{violation.plate_candidates.map((c) => `${c.text} (${formatConfidence(c.confidence)})`).join(", ")}</span>
              </p>
            )}
            <form action={correctPlate} className="flex gap-2">
              <input type="hidden" name="id" value={violation.id} />
              <input name="plate" defaultValue={violation.plate_text_corrected ?? ""} maxLength={20} placeholder="Correct plate" aria-label="Corrected plate" className={`${inputClass} plate uppercase`} />
              <SubmitButton variant="secondary">Save</SubmitButton>
            </form>
          </Card>

          <Card title="Notes">
            <form action={saveNotes} className="space-y-2">
              <input type="hidden" name="id" value={violation.id} />
              <textarea name="notes" defaultValue={violation.review_notes ?? ""} rows={3} maxLength={2000} aria-label="Review notes" className="w-full rounded-md border border-line bg-surface p-3 text-sm" />
              <SubmitButton variant="secondary">Save notes</SubmitButton>
            </form>
          </Card>

          <Card title="Detection details">
            <dl className="divide-y divide-line">
              <Fact label="Detected">{formatDateTime(violation.detected_at)}</Fact>
              {violation.source === "video" ? (
                <>
                  <Fact label="Video">{violation.session?.video_file_name ?? "—"}</Fact>
                  <Fact label="Clip time">{formatClipTime(violation.video_timestamp_ms)}</Fact>
                </>
              ) : (
                <Fact label="Location">{violation.session?.location_label ?? "—"}</Fact>
              )}
              <Fact label="Track">#{violation.track_id}</Fact>
              <Fact label="Riders without helmet">
                {violation.no_helmet_count} of {violation.rider_count}
              </Fact>
              <Fact label="Helmet confidence">{formatConfidence(violation.helmet_confidence)}</Fact>
              <Fact label="Frames (no helmet / seen)">
                {violation.no_helmet_frames} / {violation.frames_observed}
              </Fact>
              <Fact label="Model">{violation.model_version ?? "—"}</Fact>
              <Fact label="App">{violation.app_version ?? "—"}</Fact>
              <Fact label="Session">
                <Link href={`/sessions/${violation.session_id}`} className="underline underline-offset-2">
                  Open
                </Link>
              </Fact>
            </dl>
          </Card>

          <Card title="Audit history">
            {events.length === 0 ? (
              <p className="text-sm text-ink-2">No changes yet.</p>
            ) : (
              <ol className="space-y-3">
                {events.map((event) => (
                  <li key={event.id} className="text-sm">
                    <p className="font-medium">{ACTION_LABEL[event.action]}</p>
                    <p className="text-ink-2">
                      {event.old_value ?? "empty"} → {event.new_value ?? "empty"}
                    </p>
                    <p className="text-xs text-ink-3">
                      {event.actor?.full_name || event.actor?.email || "Unknown"} · {formatDateTime(event.created_at)}
                    </p>
                  </li>
                ))}
              </ol>
            )}
          </Card>
        </div>
      </div>
    </>
  );
}
