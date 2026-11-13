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

/** Where an email link lands, then sends the person on to `next`. */
export async function confirmLink(next: "/pending" | "/reset-password"): Promise<string> {
  return `${await siteOrigin()}/auth/confirm?next=${encodeURIComponent(next)}`;
}
