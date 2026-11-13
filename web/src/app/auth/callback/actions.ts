"use server";

import { redirect } from "next/navigation";
import { createClient } from "@/lib/supabase/server";

/**
 * Finishes an email link that the Android app asked for. Those links carry the session
 * in the part of the address the server never sees, so the page hands it over here.
 * Supabase checks the tokens before any cookie is set.
 */
export async function adoptSession(accessToken: string, refreshToken: string, type: string) {
  if (!accessToken || !refreshToken) redirect("/login?error=link");
  const supabase = await createClient();
  const { error } = await supabase.auth.setSession({ access_token: accessToken, refresh_token: refreshToken });
  if (error) redirect("/login?error=link");
  redirect(type === "recovery" ? "/reset-password" : "/pending");
}
