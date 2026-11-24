import Link from "next/link";
import { buildHref, PERIODS, type Period, type SearchParams } from "@/lib/filters";
import { cn } from "./ui";

// The same choice of periods as the Android app's home screen. Kept in the page address so a shared link opens the same view.
export function PeriodSwitch({ path, params, current }: { path: string; params: SearchParams; current: Period }) {
  return (
    <div role="group" aria-label="Period" className="inline-flex rounded-md border border-line bg-surface p-0.5">
      {PERIODS.map((option) => (
        <Link
          key={option.value}
          href={buildHref(path, params, { period: option.value === "today" ? null : option.value })}
          aria-current={current === option.value ? "true" : undefined}
          className={cn("rounded px-3 py-1.5 text-xs font-medium sm:text-sm", current === option.value ? "bg-primary text-primary-ink" : "text-ink-2 hover:bg-surface-2")}
        >
          {option.label}
        </Link>
      ))}
    </div>
  );
}
