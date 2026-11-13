import { Download } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";
import { SourceSwitch } from "@/components/SourceSwitch";
import { EmptyState, inputClass, Notice, PageHeader, secondaryButtonClass } from "@/components/ui";
import { VIOLATION_ROW_COLUMNS, ViolationRows, type ViolationRow } from "@/components/ViolationRows";
import { requireStaff } from "@/lib/auth";
import { thumbnailsFor } from "@/lib/evidence";
import { applyViolationFilters, buildHref, param, parseViolationFilters, type SearchParams } from "@/lib/filters";
import { formatNumber } from "@/lib/format";

export const metadata: Metadata = { title: "Violations" };

const PAGE_SIZE = 25;

export default async function ViolationsPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const { supabase } = await requireStaff();
  const params = await searchParams;
  const filters = parseViolationFilters(params);
  const page = Math.max(1, Number(param(params, "page")) || 1);

  const query = applyViolationFilters(supabase.from("violations").select<string, ViolationRow>(VIOLATION_ROW_COLUMNS, { count: "exact" }), filters);
  const { data, count, error } = await query.order("detected_at", { ascending: false }).range((page - 1) * PAGE_SIZE, page * PAGE_SIZE - 1);

  const rows = data ?? [];
  const thumbs = await thumbnailsFor(
    supabase,
    rows.map((r) => r.id),
  );
  const total = count ?? 0;
  const lastPage = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <>
      <PageHeader title="Violations" subtitle={`${formatNumber(total)} matching records`}>
        <SourceSwitch path="/violations" params={params} current={filters.source} />
      </PageHeader>

      <form className="mb-4 grid grid-cols-2 gap-2 lg:grid-cols-[1fr_1fr_1fr_auto]">
        {filters.source && <input type="hidden" name="source" value={filters.source} />}
        <input name="from" type="date" defaultValue={param(params, "from")} aria-label="From date" className={inputClass} />
        <input name="to" type="date" defaultValue={param(params, "to")} aria-label="To date" className={inputClass} />
        <select name="review" defaultValue={param(params, "review") ?? ""} aria-label="Review status" className={inputClass}>
          <option value="">Any review status</option>
          <option value="unreviewed">Unreviewed</option>
          <option value="confirmed">Confirmed</option>
          <option value="rejected">Rejected</option>
          <option value="needs_info">Needs info</option>
        </select>
        <div className="col-span-2 flex gap-2 lg:col-span-1">
          <button type="submit" className={`${secondaryButtonClass} flex-1`}>
            Apply
          </button>
          <a href={buildHref("/api/export", params, { page: null })} className={`${secondaryButtonClass} flex-1`}>
            <Download className="size-4" aria-hidden /> CSV
          </a>
        </div>
      </form>

      {error && <Notice tone="bad">The list could not be loaded. Refresh to try again.</Notice>}

      {rows.length === 0 ? (
        <EmptyState title="No violations match">Try removing a filter or widening the dates.</EmptyState>
      ) : (
        <ViolationRows rows={rows} thumbs={thumbs} />
      )}

      {lastPage > 1 && (
        <nav aria-label="Pages" className="mt-4 flex items-center justify-between text-sm text-ink-2">
          {page > 1 ? (
            <Link className={secondaryButtonClass} href={buildHref("/violations", params, { page: String(page - 1) })}>
              Previous
            </Link>
          ) : (
            <span />
          )}
          <span className="tabular">
            Page {page} of {lastPage}
          </span>
          {page < lastPage ? (
            <Link className={secondaryButtonClass} href={buildHref("/violations", params, { page: String(page + 1) })}>
              Next
            </Link>
          ) : (
            <span />
          )}
        </nav>
      )}
    </>
  );
}
