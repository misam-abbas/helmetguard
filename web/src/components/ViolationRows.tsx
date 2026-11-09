import { ImageOff } from "lucide-react";
import Link from "next/link";
import { formatClipTime, formatDateTime, plateLabel } from "@/lib/format";
import type { Violation } from "@/lib/types";
import { OcrBadge, ReviewBadge, SourceTag } from "./ui";

export type ViolationRow = Pick<
  Violation,
  "id" | "source" | "detected_at" | "video_timestamp_ms" | "plate_text_final" | "plate_ocr_status" | "review_status"
> & { session: { video_file_name: string | null; location_label: string | null } | null };

export const VIOLATION_ROW_COLUMNS =
  "id, source, detected_at, video_timestamp_ms, plate_text_final, plate_ocr_status, review_status, session:detection_sessions(video_file_name, location_label)";

function Thumb({ url }: { url?: string }) {
  if (!url) {
    return (
      <span className="flex h-12 w-16 items-center justify-center rounded bg-surface-2 text-ink-3">
        <ImageOff className="size-4" aria-hidden />
        <span className="sr-only">No thumbnail</span>
      </span>
    );
  }
  // Signed Supabase links change hourly, so next/image caching adds nothing here.
  // eslint-disable-next-line @next/next/no-img-element
  return <img src={url} alt="Violation thumbnail" loading="lazy" className="h-12 w-16 rounded object-cover" />;
}

function PlateText({ row }: { row: ViolationRow }) {
  if (!row.plate_text_final) return <span className="whitespace-nowrap text-sm text-ink-3">{plateLabel(null, row.plate_ocr_status)}</span>;
  return <span className="plate truncate text-sm font-medium">{row.plate_text_final}</span>;
}

function Where({ row }: { row: ViolationRow }) {
  if (row.source === "video") {
    return (
      <span className="block max-w-56 truncate text-xs text-ink-3">
        {row.session?.video_file_name ?? "Video"} · {formatClipTime(row.video_timestamp_ms)}
      </span>
    );
  }
  return row.session?.location_label ? <span className="block max-w-56 truncate text-xs text-ink-3">{row.session.location_label}</span> : null;
}

export function ViolationRows({ rows, thumbs }: { rows: ViolationRow[]; thumbs: Map<string, string> }) {
  return (
    <>
      {/* Tables become cards on small screens. */}
      <ul className="space-y-2 md:hidden">
        {rows.map((row) => (
          <li key={row.id}>
            <Link href={`/violations/${row.id}`} className="flex gap-3 rounded-lg border border-line bg-surface p-3">
              <Thumb url={thumbs.get(row.id)} />
              <div className="min-w-0 flex-1 space-y-1">
                <div className="flex items-center justify-between gap-2">
                  <PlateText row={row} />
                  <SourceTag source={row.source} />
                </div>
                <p className="tabular text-xs text-ink-2">{formatDateTime(row.detected_at)}</p>
                <Where row={row} />
                <div className="flex flex-wrap gap-1.5">
                  <ReviewBadge status={row.review_status} />
                  <OcrBadge status={row.plate_ocr_status} />
                </div>
              </div>
            </Link>
          </li>
        ))}
      </ul>

      <div className="hidden overflow-x-auto rounded-lg border border-line bg-surface md:block">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-line text-xs text-ink-3">
            <tr>
              <th className="px-4 py-2.5 font-medium">Photo</th>
              <th className="px-4 py-2.5 font-medium">Time (PKT)</th>
              <th className="px-4 py-2.5 font-medium">Source</th>
              <th className="px-4 py-2.5 font-medium">Plate and OCR</th>
              <th className="px-4 py-2.5 font-medium">Review</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-line">
            {rows.map((row) => (
              <tr key={row.id} className="hover:bg-surface-2/60">
                <td className="px-4 py-2">
                  <Link href={`/violations/${row.id}`} aria-label="Open violation">
                    <Thumb url={thumbs.get(row.id)} />
                  </Link>
                </td>
                <td className="tabular whitespace-nowrap px-4 py-2 text-ink-2">
                  <Link href={`/violations/${row.id}`} className="hover:underline">
                    {formatDateTime(row.detected_at)}
                  </Link>
                </td>
                <td className="px-4 py-2">
                  <SourceTag source={row.source} />
                  <Where row={row} />
                  {row.source === "video" && (
                    <Link href={`/violations/${row.id}#frame`} className="text-xs underline underline-offset-2">
                      Play at this moment
                    </Link>
                  )}
                </td>
                <td className="px-4 py-2">
                  <PlateText row={row} />
                  <div className="mt-1">
                    <OcrBadge status={row.plate_ocr_status} />
                  </div>
                </td>
                <td className="px-4 py-2">
                  <ReviewBadge status={row.review_status} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}
