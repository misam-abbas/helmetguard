import { headers } from "next/headers";

const FALLBACK = "https://helmetguard.vercel.app";

/**
 * The address this site is being used at, for links inside Supabase emails.
 * Next.js already rejects server actions whose Origin doesn't match the host,
 * and Supabase only follows links on its own allow-list.
 */
export async function siteOrigin(): Promise<string> {
  const list = await headers();
  const origin = list.get("origin");
  if (origin && /^https?:\/\/[^/]+$/.test(origin)) return origin;
  const host = list.get("x-forwarded-host") ?? list.get("host");
  if (!host) return FALLBACK;
  const proto = list.get("x-forwarded-proto") ?? (host.startsWith("localhost") ? "http" : "https");
  return `${proto}://${host}`;
}

/**
 * Where a sign-up or password-reset email link lands. The page reads the session from the
 * link and sends the person on: to /reset-password for a reset, otherwise to /pending.
 * The address must match Supabase's Redirect URLs list; if it doesn't, Supabase sends the
 * link to its Site URL instead, and EmailLinkForwarder passes it on from there.
 */
export async function emailLink(): Promise<string> {
  return `${await siteOrigin()}/auth/callback`;
}
