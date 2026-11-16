import type { ReviewStatus, Source } from "@/lib/types";

export type SearchParams = Record<string, string | string[] | undefined>;

export function param(params: SearchParams, key: string): string | undefined {
  const value = params[key];
  return Array.isArray(value) ? value[0] : value || undefined;
}

export function parseSource(params: SearchParams): Source | null {
  const value = param(params, "source");
  return value === "live" || value === "video" ? value : null;
}

const REVIEW_VALUES: ReviewStatus[] = ["unreviewed", "confirmed", "rejected", "needs_info"];

// Number plates are never read, so there is no plate filter.
export interface ViolationFilters {
  source: Source | null;
  review: ReviewStatus | null;
  from: string | null;
  to: string | null;
}

/** A yyyy-mm-dd date in Pakistan time, as a UTC instant. */
function dayStart(value: string | undefined, addDays = 0): string | null {
  if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return null;
  const date = new Date(`${value}T00:00:00+05:00`);
  if (Number.isNaN(date.getTime())) return null;
  date.setUTCDate(date.getUTCDate() + addDays);
  return date.toISOString();
}

export function parseViolationFilters(params: SearchParams): ViolationFilters {
  const review = param(params, "review") as ReviewStatus | undefined;
  return {
    source: parseSource(params),
    review: review && REVIEW_VALUES.includes(review) ? review : null,
    from: dayStart(param(params, "from")),
    to: dayStart(param(params, "to"), 1),
  };
}

// Structural type so both page queries and the CSV route can share these filters.
interface FilterableQuery {
  eq(column: string, value: string): this;
  gte(column: string, value: string): this;
  lt(column: string, value: string): this;
}

export function applyViolationFilters<Q extends FilterableQuery>(query: Q, f: ViolationFilters): Q {
  let q = query;
  if (f.source) q = q.eq("source", f.source);
  if (f.review) q = q.eq("review_status", f.review);
  if (f.from) q = q.gte("detected_at", f.from);
  if (f.to) q = q.lt("detected_at", f.to);
  return q;
}

export function buildHref(path: string, params: SearchParams, changes: Record<string, string | null>): string {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    const single = Array.isArray(value) ? value[0] : value;
    if (single) query.set(key, single);
  }
  // A one-time message, such as "password changed", never follows a link to another view.
  query.delete("notice");
  for (const [key, value] of Object.entries(changes)) {
    if (value === null) query.delete(key);
    else query.set(key, value);
  }
  const text = query.toString();
  return text ? `${path}?${text}` : path;
}

export function daysAgo(days: number): string {
  return new Date(Date.now() - days * 86_400_000).toISOString();
}

export function rangeFromDays(days: number) {
  const to = new Date();
  const from = new Date(to.getTime() - days * 86_400_000);
  return { from: from.toISOString(), to: to.toISOString() };
}
