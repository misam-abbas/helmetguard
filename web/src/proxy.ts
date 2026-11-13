import { createServerClient } from "@supabase/ssr";
import { NextResponse, type NextRequest } from "next/server";
import { SUPABASE_KEY, SUPABASE_URL, supabaseConfigured } from "@/lib/supabase/env";

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

  // Email links land here whether or not someone is signed in.
  if (pathname === "/auth/confirm" || pathname === "/auth/callback") return response;
  // If Supabase doesn't accept our link address, it sends the code to the site's home page instead.
  if ((pathname === "/" || pathname === "/login") && searchParams.has("code")) {
    const confirm = new URL("/auth/confirm", request.url);
    confirm.searchParams.set("code", searchParams.get("code") ?? "");
    return NextResponse.redirect(confirm);
  }

  // Pages for people who are not signed in yet: sign in, confirm the sign-up code, reset a password.
  const isPublic = pathname === "/login" || pathname === "/verify" || pathname === "/forgot-password";

  if (!user && !isPublic) {
    return NextResponse.redirect(new URL("/login", request.url));
  }
  if (user && isPublic) {
    return NextResponse.redirect(new URL("/dashboard", request.url));
  }

  return response;
}

export const config = {
  // The detector's model and runtime files are static and already public in the repository.
  matcher: ["/((?!_next/static|_next/image|favicon.ico|models/|ort/|.*\\.(?:svg|png|jpg|webp|ico)$).*)"],
};
