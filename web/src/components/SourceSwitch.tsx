import Link from "next/link";
import { buildHref, type SearchParams } from "@/lib/filters";
import type { Source } from "@/lib/types";
import { cn } from "./ui";

const OPTIONS: { value: Source | null; label: string }[] = [
  { value: "live", label: "Live camera" },
  { value: "video", label: "Prerecorded video" },
  { value: null, label: "All" },
];

// Kept in the page address so a shared link opens the same view.
export function SourceSwitch({ path, params, current }: { path: string; params: SearchParams; current: Source | null }) {
  return (
    <div role="group" aria-label="Detection source" className="inline-flex rounded-md border border-line bg-surface p-0.5">
      {OPTIONS.map((option) => (
        <Link
          key={option.label}
          href={buildHref(path, params, { source: option.value, page: null })}
          aria-current={current === option.value ? "true" : undefined}
          className={cn("rounded px-3 py-1.5 text-xs font-medium sm:text-sm", current === option.value ? "bg-primary text-primary-ink" : "text-ink-2 hover:bg-surface-2")}
        >
          {option.label}
        </Link>
      ))}
    </div>
  );
}
