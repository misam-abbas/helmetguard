"use client";

import { Loader2 } from "lucide-react";
import { useEffect, useRef } from "react";
import { adoptSession } from "./actions";

/**
 * Where sign-up and password-reset email links land. Supabase puts the session after the "#",
 * which only this page can read; adoptSession checks it with Supabase before any cookie is set.
 */
export default function EmailLinkCallback() {
  const started = useRef(false);

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    const hash = new URLSearchParams(window.location.hash.slice(1));
    const query = new URLSearchParams(window.location.search);
    // Keep the tokens out of the address bar and the browser history.
    window.history.replaceState(null, "", window.location.pathname);

    if (hash.get("error") || query.get("error")) {
      // An expired or already-used link. A reset link says so; for a sign-up, the account may be confirmed already.
      const type = hash.get("type") ?? query.get("type");
      window.location.replace(type === "recovery" ? "/forgot-password?error=link" : "/login?error=link");
      return;
    }
    if (query.get("code") || query.get("token_hash")) {
      // A link in the other format: the server finishes it.
      window.location.replace(`/auth/confirm?${query.toString()}`);
      return;
    }
    void adoptSession(hash.get("access_token") ?? "", hash.get("refresh_token") ?? "", hash.get("type") ?? "");
  }, []);

  return (
    <main className="flex min-h-dvh items-center justify-center px-4">
      <p role="status" className="flex items-center gap-2 text-sm text-ink-2">
        <Loader2 className="size-4 animate-spin" aria-hidden /> Checking your email link…
      </p>
    </main>
  );
}
