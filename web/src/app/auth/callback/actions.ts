"use server";

import { redirect } from "next/navigation";
import { createClient } from "@/lib/supabase/server";

/**
 * Finishes an email link: sign-up confirmation or password reset, asked for on the website or
 * in the Android app. Those links carry the session in the part of the address the server never
 * sees, so the page hands it over here. Supabase checks the tokens before any cookie is set.
 */
export async function adoptSession(accessToken: string, refreshToken: string, type: string) {
  const recovery = type === "recovery";
  if (!accessToken || !refreshToken) redirect(recovery ? "/forgot-password?error=link" : "/login?error=link");
  const supabase = await createClient();
  const { error } = await supabase.auth.setSession({ access_token: accessToken, refresh_token: refreshToken });
  if (error) redirect(recovery ? "/forgot-password?error=link" : "/login?error=link");
  // A confirmed sign-up waits for an admin; /pending sends approved accounts on to the dashboard.
  redirect(recovery ? "/reset-password" : "/pending");
}
