import { createServerClient } from "@supabase/ssr";
import { NextResponse, type NextRequest } from "next/server";
import { SUPABASE_KEY, SUPABASE_URL, supabaseConfigured } from "@/lib/supabase/env";

// Pages for people who are not signed in yet: sign in, confirm an email, reset a password.
// A signed-in person is sent on to the dashboard instead.
const SIGNED_OUT_PAGES = new Set(["/login", "/verify", "/forgot-password"]);
// Pages anyone may read, signed in or not.
const OPEN_PAGES = new Set(["/privacy", "/auth/confirm", "/auth/callback"]);

// Layer one of three: refreshes the session and sends signed-out visitors to /login.
// Server pages then re-check the role, and Postgres RLS decides what comes back.
export async function proxy(request: NextRequest) {
  if (!supabaseConfigured) {
    return new NextResponse(
      "HelmetGuard is not configured. Copy web/.env.example to web/.env.local and fill in the Supabase values.",
      { status: 503, headers: { "content-type": "text/plain; charset=utf-8" } },
    );
  }

  let response = NextResponse.next({ request });

  const supabase = createServerClient(SUPABASE_URL, SUPABASE_KEY, {
    cookies: {
      getAll: () => request.cookies.getAll(),
      setAll(list) {
        list.forEach(({ name, value }) => request.cookies.set(name, value));
        response = NextResponse.next({ request });
        list.forEach(({ name, value, options }) => response.cookies.set(name, value, options));
      },
    },
  });

  const {
    data: { user },
  } = await supabase.auth.getUser();

  const { pathname, searchParams } = request.nextUrl;

  if (OPEN_PAGES.has(pathname)) return response;
  if (pathname === "/" || pathname === "/login") {
    // If Supabase doesn't accept a link's address, it sends the person to the site's home page instead.
    if (searchParams.has("code") || searchParams.has("token_hash")) {
      const confirm = new URL("/auth/confirm", request.url);
      confirm.search = searchParams.toString();
      return NextResponse.redirect(confirm);
    }
    // An expired or already-used email link.
    if (searchParams.has("error_description") || searchParams.has("error_code")) {
      return NextResponse.redirect(new URL("/login?error=link", request.url));
    }
  }

  const signedOutPage = SIGNED_OUT_PAGES.has(pathname);
  if (!user && !signedOutPage) {
    return NextResponse.redirect(new URL("/login", request.url));
  }
  if (user && signedOutPage) {
    return NextResponse.redirect(new URL("/dashboard", request.url));
  }

  return response;
}

export const config = {
  // The detector's model and runtime files are static and already public in the repository.
  matcher: ["/((?!_next/static|_next/image|favicon.ico|models/|ort/|.*\\.(?:svg|png|jpg|webp|ico)$).*)"],
};
