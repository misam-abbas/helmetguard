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

// The dashboard's periods, the same as on the Android app's home screen, so both show the same totals.
export const PERIODS = [
  { value: "today", label: "Today" },
  { value: "7", label: "7 days" },
  { value: "30", label: "30 days" },
] as const;

export type Period = (typeof PERIODS)[number]["value"];

export function parsePeriod(params: SearchParams): Period {
  const value = param(params, "period");
  return PERIODS.find((p) => p.value === value)?.value ?? "today";
}

const PAKISTAN_OFFSET_MS = 5 * 3_600_000; // UTC+5 all year: Pakistan has no daylight saving time

/** Midnight at the start of today in Pakistan time. */
export function startOfTodayPakistan(now = new Date()): Date {
  const local = new Date(now.getTime() + PAKISTAN_OFFSET_MS);
  return new Date(Date.UTC(local.getUTCFullYear(), local.getUTCMonth(), local.getUTCDate()) - PAKISTAN_OFFSET_MS);
}

/**
 * Today since midnight Pakistan time, or the last 7 or 30 days, exactly as the app counts them.
 * Like the app, nothing after "now" is cut off: the end is a day ahead, so a record from a phone
 * whose clock runs a little fast still counts.
 */
export function rangeForPeriod(period: Period, now = new Date()) {
  const from = period === "today" ? startOfTodayPakistan(now) : new Date(now.getTime() - Number(period) * 86_400_000);
  return { from: from.toISOString(), to: new Date(now.getTime() + 86_400_000).toISOString() };
}
