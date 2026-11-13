"use client";

import { useEffect } from "react";

/**
 * If Supabase doesn't accept the link address the app asked for, it sends the email link
 * to the home page instead, and the session rides in the address after "#". This passes
 * it on to the page that finishes the job.
 */
export function EmailLinkForwarder() {
  useEffect(() => {
    const { hash, pathname } = window.location;
    if (pathname !== "/auth/callback" && /(^#|&)(access_token|error_description)=/.test(hash)) {
      window.location.replace(`/auth/callback${hash}`);
    }
  }, []);
  return null;
}
