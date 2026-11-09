import { Check, CircleHelp, Clock3, Eye, X } from "lucide-react";
import Link from "next/link";
import type { OcrStatus, ReviewStatus, Source } from "@/lib/types";

export function cn(...parts: (string | false | null | undefined)[]) {
  return parts.filter(Boolean).join(" ");
}

export const inputClass =
  "h-10 w-full rounded-md border border-line bg-surface px-3 text-sm text-ink placeholder:text-ink-3";

export const buttonClass =
  "inline-flex h-10 items-center justify-center gap-2 whitespace-nowrap rounded-md bg-primary px-4 text-sm font-medium text-primary-ink transition-opacity hover:opacity-85 disabled:opacity-50";

export const secondaryButtonClass =
  "inline-flex h-10 items-center justify-center gap-2 whitespace-nowrap rounded-md border border-line bg-surface px-4 text-sm font-medium text-ink transition-colors hover:bg-surface-2 disabled:opacity-50";

export function Card({ title, action, children, className }: { title?: string; action?: React.ReactNode; children: React.ReactNode; className?: string }) {
  return (
    <section className={cn("rounded-lg border border-line bg-surface p-4 sm:p-5", className)}>
      {(title || action) && (
        <div className="mb-3 flex items-center justify-between gap-3">
          {title && <h2 className="text-sm font-semibold">{title}</h2>}
          {action}
        </div>
      )}
      {children}
    </section>
  );
}

export function PageHeader({ title, subtitle, children }: { title: string; subtitle?: string; children?: React.ReactNode }) {
  return (
    <div className="mb-5 flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">{title}</h1>
        {subtitle && <p className="mt-0.5 text-sm text-ink-2">{subtitle}</p>}
      </div>
      {children}
    </div>
  );
}

export function SourceTag({ source }: { source: Source }) {
  return (
    <span className="inline-flex items-center gap-1.5 text-xs text-ink-2">
      <span aria-hidden className={cn("size-2 rounded-full", source === "live" ? "bg-live" : "bg-video")} />
      {source === "live" ? "Live" : "Video"}
    </span>
  );
}

const pill = "inline-flex items-center gap-1 whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-medium";

export function ReviewBadge({ status }: { status: ReviewStatus }) {
  switch (status) {
    case "confirmed":
      return (
        <span className={cn(pill, "bg-good-bg text-good")}>
          <Check className="size-3" aria-hidden /> Confirmed
        </span>
      );
    case "rejected":
      return (
        <span className={cn(pill, "bg-surface-2 text-ink-2")}>
          <X className="size-3" aria-hidden /> Rejected
        </span>
      );
    case "needs_info":
      return (
        <span className={cn(pill, "bg-warn-bg text-warn")}>
          <CircleHelp className="size-3" aria-hidden /> Needs info
        </span>
      );
    default:
      return (
        <span className={cn(pill, "bg-bad-bg text-bad")}>
          <Eye className="size-3" aria-hidden /> AI alert – unverified
        </span>
      );
  }
}

const OCR_LABEL: Record<OcrStatus, string> = {
  read: "Read",
  low_confidence: "Needs review",
  unreadable: "Unreadable",
  no_plate_detected: "No plate",
};

export function OcrBadge({ status }: { status: OcrStatus }) {
  return (
    <span className={cn(pill, status === "read" ? "bg-good-bg text-good" : status === "low_confidence" ? "bg-warn-bg text-warn" : "bg-surface-2 text-ink-2")}>
      {OCR_LABEL[status]}
    </span>
  );
}

export function PendingPill({ children }: { children: React.ReactNode }) {
  return (
    <span className={cn(pill, "bg-warn-bg text-warn")}>
      <Clock3 className="size-3" aria-hidden /> {children}
    </span>
  );
}

export function EmptyState({ title, children }: { title: string; children?: React.ReactNode }) {
  return (
    <div className="rounded-lg border border-dashed border-line px-6 py-12 text-center">
      <p className="text-sm font-medium">{title}</p>
      {children && <p className="mx-auto mt-1 max-w-md text-sm text-ink-2">{children}</p>}
    </div>
  );
}

export function Notice({ tone = "good", children }: { tone?: "good" | "bad" | "warn"; children: React.ReactNode }) {
  return (
    <p role="status" className={cn("mb-4 rounded-md px-3 py-2 text-sm", tone === "good" && "bg-good-bg text-good", tone === "bad" && "bg-bad-bg text-bad", tone === "warn" && "bg-warn-bg text-warn")}>
      {children}
    </p>
  );
}

export function TextLink({ href, children }: { href: string; children: React.ReactNode }) {
  return (
    <Link href={href} className="text-sm font-medium underline underline-offset-2 hover:text-ink-2">
      {children}
    </Link>
  );
}
