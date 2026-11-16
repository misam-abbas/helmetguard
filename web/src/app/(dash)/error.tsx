"use client";

import { RotateCw } from "lucide-react";
import Link from "next/link";
import { useEffect } from "react";
import { buttonClass, secondaryButtonClass } from "@/components/ui";

// Shown inside the dashboard frame when a page fails, so the menu still works.
export default function DashboardError({ error, retry }: { error: unknown; retry: () => void }) {
  useEffect(() => {
    console.error(error);
  }, [error]);

  return (
    <div role="alert" className="mx-auto max-w-md py-16 text-center">
      <h1 className="text-lg font-semibold">This page could not be loaded</h1>
      <p className="mt-1 text-sm text-ink-2">Check your internet connection, then try again. If it keeps happening, sign out and sign back in.</p>
      <div className="mt-5 flex flex-wrap justify-center gap-2">
        <button type="button" onClick={() => retry()} className={buttonClass}>
          <RotateCw className="size-4" aria-hidden /> Try again
        </button>
        <Link href="/dashboard" className={secondaryButtonClass}>
          Go to the dashboard
        </Link>
      </div>
    </div>
  );
}
