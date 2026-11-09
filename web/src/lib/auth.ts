import { redirect } from "next/navigation";
import { cache } from "react";
import { createClient } from "@/lib/supabase/server";
import type { Profile } from "@/lib/types";

// Cached per request, so the layout and page share one lookup.
export const getCurrentUser = cache(async () => {
  const supabase = await createClient();
  const {
    data: { user },
  } = await supabase.auth.getUser();
  if (!user) return null;
  const { data } = await supabase.from("profiles").select("*").eq("id", user.id).single();
  if (!data) return null;
  return { supabase, userId: user.id, profile: data as Profile };
});

export async function requireStaff() {
  const current = await getCurrentUser();
  if (!current) redirect("/login");
  const { profile } = current;
  if (profile.status !== "active" || profile.role === "operator") redirect("/pending");
  return current;
}

export async function requireAdmin() {
  const current = await requireStaff();
  if (current.profile.role !== "admin") redirect("/dashboard");
  return current;
}
