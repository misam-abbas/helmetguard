"use client";

import { Loader2 } from "lucide-react";
import { useEffect, useRef } from "react";
import { adoptSession } from "./actions";

export default function EmailLinkCallback() {
  const started = useRef(false);

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    const hash = new URLSearchParams(window.location.hash.slice(1));
    // Keep the tokens out of the address bar and the browser history.
    window.history.replaceState(null, "", window.location.pathname);
    if (hash.get("error")) {
      window.location.replace("/login?error=link");
      return;
    }
    void adoptSession(hash.get("access_token") ?? "", hash.get("refresh_token") ?? "", hash.get("type") ?? "");
  }, []);

  return (
    <main className="flex min-h-dvh items-center justify-center px-4">
      <p className="flex items-center gap-2 text-sm text-ink-2">
        <Loader2 className="size-4 animate-spin" aria-hidden /> Checking your email link…
      </p>
    </main>
  );
}
