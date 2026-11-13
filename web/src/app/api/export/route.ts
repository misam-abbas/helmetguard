import { NextResponse, type NextRequest } from "next/server";
import { getCurrentUser } from "@/lib/auth";
import { applyViolationFilters, parseViolationFilters, type SearchParams } from "@/lib/filters";
import type { Violation } from "@/lib/types";

const MAX_ROWS = 10_000;
const CHUNK = 1000;

const COLUMNS = [
  "id",
  "detected_at",
  "source",
  "video_timestamp_ms",
  "track_id",
  "rider_count",
  "no_helmet_count",
  "helmet_confidence",
  "review_status",
  "reviewed_at",
  "review_notes",
] as const;

// Spreadsheet apps run cells beginning with these characters as formulas.
function csvCell(value: unknown): string {
  let text = value === null || value === undefined ? "" : String(value);
  if (/^[=+\-@\t\r]/.test(text)) text = `'${text}`;
  return /[",\n\r]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

function fileName(source: string | null) {
  const now = new Date();
  const month = `${now.getUTCFullYear()}-${String(now.getUTCMonth() + 1).padStart(2, "0")}`;
  return `violations-${source ?? "all"}-${month}.csv`;
}

export async function GET(request: NextRequest) {
  const current = await getCurrentUser();
  if (!current) return new NextResponse("Sign in required", { status: 401 });
  const { profile, supabase } = current;
  if (profile.status !== "active" || profile.role === "operator") {
    return new NextResponse("Not allowed", { status: 403 });
  }

  const params: SearchParams = Object.fromEntries(request.nextUrl.searchParams.entries());
  const filters = parseViolationFilters(params);
  const encoder = new TextEncoder();

  const stream = new ReadableStream<Uint8Array>({
    async start(controller) {
      // The byte-order mark makes Excel read the file as UTF-8.
      controller.enqueue(encoder.encode(`﻿${COLUMNS.join(",")}\r\n`));
      for (let offset = 0; offset < MAX_ROWS; offset += CHUNK) {
        const end = Math.min(offset + CHUNK, MAX_ROWS) - 1;
        const { data, error } = await applyViolationFilters(supabase.from("violations").select<string, Partial<Violation>>(COLUMNS.join(",")), filters)
          .order("detected_at", { ascending: false })
          .order("id")
          .range(offset, end);
        if (error) {
          controller.error(new Error("Export failed"));
          return;
        }
        const rows = data ?? [];
        const text = rows.map((row) => COLUMNS.map((c) => csvCell(row[c])).join(",")).join("\r\n");
        if (text) controller.enqueue(encoder.encode(`${text}\r\n`));
        if (rows.length < CHUNK) break;
      }
      controller.close();
    },
  });

  return new NextResponse(stream, {
    headers: {
      "content-type": "text/csv; charset=utf-8",
      "content-disposition": `attachment; filename="${fileName(filters.source)}"`,
      "cache-control": "no-store",
    },
  });
}
