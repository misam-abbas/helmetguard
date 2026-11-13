"use server";

import { redirect } from "next/navigation";
import { forgetEmail, pendingEmail, rememberEmail } from "@/lib/pending-email";
import { confirmLink } from "@/lib/site-url";
import { createClient } from "@/lib/supabase/server";

const MIN_PASSWORD = 8;

function field(form: FormData, name: string) {
  return String(form.get(name) ?? "").trim();
}

function code(form: FormData) {
  return field(form, "code").replace(/\D/g, "").slice(0, 10);
}

export async function signIn(form: FormData) {
  const supabase = await createClient();
  const { error } = await supabase.auth.signInWithPassword({
    email: field(form, "email"),
    password: String(form.get("password") ?? ""),
  });
  if (error) redirect(error.code === "email_not_confirmed" ? "/login?error=unconfirmed" : "/login?error=credentials");
  redirect("/dashboard");
}

export async function signUp(form: FormData) {
  const password = String(form.get("password") ?? "");
  if (password.length < MIN_PASSWORD) redirect("/login?mode=signup&error=short");

  const email = field(form, "email");
  const supabase = await createClient();
  const { data, error } = await supabase.auth.signUp({
    email,
    password,
    options: {
      data: { full_name: field(form, "full_name").slice(0, 120) },
      // The email has a code, and a link for anyone who prefers to click.
      emailRedirectTo: await confirmLink("/pending"),
    },
  });
  if (error) redirect("/login?mode=signup&error=signup");
  // With email confirmation off in Supabase there is a session already; otherwise a code was emailed.
  if (data.session) redirect("/pending");
  await rememberEmail(email);
  redirect("/verify");
}

export async function verifySignup(form: FormData) {
  const email = await pendingEmail();
  if (!email) redirect("/login?mode=signup&error=expired");
  const supabase = await createClient();
  const { error } = await supabase.auth.verifyOtp({ email, token: code(form), type: "email" });
  if (error) redirect("/verify?error=code");
  await forgetEmail();
  // Confirmed. The account now waits for an admin to approve it.
  redirect("/pending");
}

export async function resendSignupCode() {
  const email = await pendingEmail();
  if (!email) redirect("/login?mode=signup&error=expired");
  const supabase = await createClient();
  const { error } = await supabase.auth.resend({ type: "signup", email, options: { emailRedirectTo: await confirmLink("/pending") } });
  redirect(error ? "/verify?error=send" : "/verify?sent=1");
}

export async function requestPasswordReset(form: FormData) {
  const email = field(form, "email");
  if (!email) redirect("/forgot-password?error=email");
  const supabase = await createClient();
  // Supabase answers the same whether or not the address has an account.
  const { error } = await supabase.auth.resetPasswordForEmail(email, { redirectTo: await confirmLink("/reset-password") });
  if (error) redirect("/forgot-password?error=send");
  await rememberEmail(email);
  redirect("/forgot-password?step=code");
}

export async function resetPassword(form: FormData) {
  const email = await pendingEmail();
  if (!email) redirect("/forgot-password?error=expired");
  const password = String(form.get("password") ?? "");
  if (password.length < MIN_PASSWORD) redirect("/forgot-password?step=code&error=short");

  const supabase = await createClient();
  const { error } = await supabase.auth.verifyOtp({ email, token: code(form), type: "recovery" });
  if (error) redirect("/forgot-password?step=code&error=code");
  const { error: updateError } = await supabase.auth.updateUser({ password });
  if (updateError) {
    // The code signed them in and is now used up, so sign out and let them start again.
    await supabase.auth.signOut();
    redirect("/forgot-password?error=update");
  }
  await forgetEmail();
  redirect("/dashboard");
}

/** For someone already signed in: from a reset link, or changing their password. */
export async function setNewPassword(form: FormData) {
  const password = String(form.get("password") ?? "");
  if (password.length < MIN_PASSWORD) redirect("/reset-password?error=short");
  const supabase = await createClient();
  const { error } = await supabase.auth.updateUser({ password });
  if (error) redirect("/reset-password?error=update");
  await forgetEmail();
  redirect("/dashboard");
}

export async function signOut() {
  const supabase = await createClient();
  await supabase.auth.signOut();
  redirect("/login");
}
