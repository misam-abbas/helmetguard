import type { EmailOtpType } from "@supabase/supabase-js";
import { NextResponse, type NextRequest } from "next/server";
import { createClient } from "@/lib/supabase/server";

// Supabase emails can carry a link as well as a code. This is where the link lands.
// Two kinds exist: a token hash (from a custom email template, works in any browser)
// and a one-time code (from the default template, works in the browser that asked).

const NEXT_PAGES = new Set(["/pending", "/reset-password", "/dashboard"]);
const OTP_TYPES = new Set<string>(["signup", "email", "recovery", "invite", "magiclink", "email_change"]);
const RECENT_MS = 60 * 60 * 1000;

export async function GET(request: NextRequest) {
  const params = request.nextUrl.searchParams;
  const tokenHash = params.get("token_hash");
  const type = params.get("type");
  const code = params.get("code");
  const supabase = await createClient();

  let confirmed = false;
  if (tokenHash && type && OTP_TYPES.has(type)) {
    const { error } = await supabase.auth.verifyOtp({ type: type as EmailOtpType, token_hash: tokenHash });
    confirmed = !error;
  } else if (code) {
    const { error } = await supabase.auth.exchangeCodeForSession(code);
    confirmed = !error;
  }
  if (!confirmed) return NextResponse.redirect(new URL("/login?error=link", request.url));

  let next = params.get("next");
  if (!next || !NEXT_PAGES.has(next)) {
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
