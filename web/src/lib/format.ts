// Everything is stored in UTC and shown in Pakistan time.
const TZ = "Asia/Karachi";

const dateTime = new Intl.DateTimeFormat("en-GB", {
  timeZone: TZ,
  day: "2-digit",
  month: "short",
  year: "numeric",
  hour: "2-digit",
  minute: "2-digit",
  hourCycle: "h23",
});

const dateOnly = new Intl.DateTimeFormat("en-GB", { timeZone: "UTC", day: "2-digit", month: "short" });
const monthOnly = new Intl.DateTimeFormat("en-GB", { timeZone: "UTC", month: "short", year: "2-digit" });

export function formatDateTime(value: string | null | undefined): string {
  return value ? dateTime.format(new Date(value)) : "—";
}

export function formatBucket(value: string, interval: "day" | "month" = "day"): string {
  const date = new Date(`${value}T00:00:00Z`);
  return interval === "month" ? monthOnly.format(date) : dateOnly.format(date);
}

export function formatClipTime(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return "—";
  const total = Math.floor(ms / 1000);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const mmss = `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
  return h > 0 ? `${h}:${mmss}` : mmss;
}

export function formatPercent(value: number | null | undefined): string {
  return value === null || value === undefined ? "—" : `${Number(value).toFixed(1)}%`;
}

export function formatConfidence(value: number | null | undefined): string {
  return value === null || value === undefined ? "—" : value.toFixed(2);
}

export function formatNumber(value: number | null | undefined): string {
  return (value ?? 0).toLocaleString("en-US");
}

export function compliance(helmet: number, noHelmet: number): number | null {
  const total = helmet + noHelmet;
  return total === 0 ? null : (100 * helmet) / total;
}

export function plateLabel(text: string | null, status: string): string {
  if (text) return text;
  return status === "no_plate_detected" ? "No plate seen" : "Unreadable";
}
