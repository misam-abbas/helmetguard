"use client";

import { RotateCw, Shield } from "lucide-react";
import { useEffect } from "react";
import { buttonClass } from "@/components/ui";

// For pages outside the dashboard frame, such as sign-in.
export default function RootError({ error, retry }: { error: unknown; retry: () => void }) {
  useEffect(() => {
    console.error(error);
  }, [error]);

  return (
    <main className="flex min-h-dvh items-center justify-center px-4 py-10">
      <div role="alert" className="w-full max-w-sm text-center">
        <Shield className="mx-auto mb-3 size-6 text-ink-2" aria-hidden />
        <h1 className="text-xl font-semibold tracking-tight">Something went wrong</h1>
        <p className="mt-1 text-sm text-ink-2">Check your internet connection, then try again.</p>
        <button type="button" onClick={() => retry()} className={`${buttonClass} mt-5`}>
          <RotateCw className="size-4" aria-hidden /> Try again
        </button>
      </div>
    </main>
  );
}
