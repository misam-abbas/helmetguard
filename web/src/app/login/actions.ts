"use server";

import { redirect } from "next/navigation";
import { createClient } from "@/lib/supabase/server";

function field(form: FormData, name: string) {
  return String(form.get(name) ?? "").trim();
}

export async function signIn(form: FormData) {
  const supabase = await createClient();
  const { error } = await supabase.auth.signInWithPassword({
    email: field(form, "email"),
    password: String(form.get("password") ?? ""),
  });
  if (error) redirect("/login?error=credentials");
  redirect("/dashboard");
}

export async function signUp(form: FormData) {
  const password = String(form.get("password") ?? "");
  if (password.length < 8) redirect("/login?mode=signup&error=short");

  const supabase = await createClient();
  const { error } = await supabase.auth.signUp({
    email: field(form, "email"),
    password,
    options: { data: { full_name: field(form, "full_name").slice(0, 120) } },
  });
  if (error) redirect("/login?mode=signup&error=signup");
  redirect("/pending");
}

export async function signOut() {
  const supabase = await createClient();
  await supabase.auth.signOut();
  redirect("/login");
}
