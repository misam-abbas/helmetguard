import type { EmailOtpType } from "@supabase/supabase-js";
import { NextResponse, type NextRequest } from "next/server";
import { createClient } from "@/lib/supabase/server";

// Email links in two other formats land here; most land on /auth/callback instead.
// A token hash comes from a custom email template and works in any browser. A one-time
// code comes from links asked for by older versions of this site, and only works in
// the browser that asked; by the time Supabase hands one out, a sign-up is already confirmed.

const NEXT_PAGES = new Set(["/pending", "/reset-password", "/dashboard"]);
const OTP_TYPES = new Set<string>(["signup", "email", "recovery", "invite", "magiclink", "email_change"]);
const RECENT_MS = 60 * 60 * 1000;

export async function GET(request: NextRequest) {
  const params = request.nextUrl.searchParams;
  const tokenHash = params.get("token_hash");
  const type = params.get("type");
  const code = params.get("code");
  const asked = params.get("next");
  const forReset = type === "recovery" || asked === "/reset-password";
  const supabase = await createClient();

  let confirmed = false;
  if (tokenHash && type && OTP_TYPES.has(type)) {
    const { error } = await supabase.auth.verifyOtp({ type: type as EmailOtpType, token_hash: tokenHash });
    confirmed = !error;
  } else if (code) {
    const { error } = await supabase.auth.exchangeCodeForSession(code);
    confirmed = !error;
  }
  if (!confirmed) {
    let failed = forReset ? "/forgot-password?error=link" : "/login?error=link";
    // Supabase only hands out a code after it has checked the link, so a sign-up is confirmed even
    // when this browser can't finish signing in (for example, the link was opened on a phone).
    if (code && !forReset && asked === "/pending") failed = "/login?notice=confirmed";
    return NextResponse.redirect(new URL(failed, request.url));
  }

  let next = asked && NEXT_PAGES.has(asked) ? asked : null;
  if (!next) {
    if (type === "recovery") {
      next = "/reset-password";
    } else {
      // A bare code says nothing about why it was sent, but a reset asked for in the last hour does.
      const {
        data: { user },
      } = await supabase.auth.getUser();
      const askedAt = user?.recovery_sent_at ? Date.parse(user.recovery_sent_at) : 0;
      next = Date.now() - askedAt < RECENT_MS ? "/reset-password" : "/pending";
    }
  }
  return NextResponse.redirect(new URL(next, request.url));
}
