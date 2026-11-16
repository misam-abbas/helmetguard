"use server";

import { redirect } from "next/navigation";
import { forgetEmail, pendingEmail, rememberEmail } from "@/lib/pending-email";
import { emailLink } from "@/lib/site-url";
import { authErrorKey, createEmailClient } from "@/lib/supabase/email";
import { createClient } from "@/lib/supabase/server";

const MIN_PASSWORD = 8;

function field(form: FormData, name: string) {
  return String(form.get(name) ?? "").trim();
}

function code(form: FormData) {
  return field(form, "code").replace(/\D/g, "").slice(0, 10);
}

export async function signIn(form: FormData) {
  const email = field(form, "email");
  const supabase = await createClient();
  const { error } = await supabase.auth.signInWithPassword({ email, password: String(form.get("password") ?? "") });
  if (error) {
    if (error.code === "email_not_confirmed") {
      // Let them confirm from here, or ask for a new email, without starting again.
      await rememberEmail(email);
      redirect("/verify?error=unconfirmed");
    }
    const key = authErrorKey(error, "credentials");
    redirect(`/login?error=${key === "rate" ? "wait" : key}`);
  }
  redirect("/dashboard");
}

export async function signUp(form: FormData) {
  const password = String(form.get("password") ?? "");
  if (password.length < MIN_PASSWORD) redirect("/login?mode=signup&error=short");

  const email = field(form, "email");
  const { data, error } = await createEmailClient().auth.signUp({
    email,
    password,
    options: {
      data: { full_name: field(form, "full_name").slice(0, 120) },
      // The email's link confirms the address in any browser, then lands on /pending.
      emailRedirectTo: await emailLink(),
    },
  });
  if (error) redirect(`/login?mode=signup&error=${authErrorKey(error, "signup")}`);
  if (data.session) {
    // Email confirmation is off in Supabase, so the account is signed in already.
    const supabase = await createClient();
    await supabase.auth.setSession(data.session);
    redirect("/pending");
  }
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
  const { error } = await createEmailClient().auth.resend({ type: "signup", email, options: { emailRedirectTo: await emailLink() } });
  redirect(error ? `/verify?error=${authErrorKey(error, "send")}` : "/verify?sent=1");
}

export async function requestPasswordReset(form: FormData) {
  const email = field(form, "email");
  if (!email) redirect("/forgot-password?error=email");
  // Supabase answers the same whether or not the address has an account.
  const { error } = await createEmailClient().auth.resetPasswordForEmail(email, { redirectTo: await emailLink() });
  if (error) redirect(`/forgot-password?error=${authErrorKey(error, "send")}`);
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
    redirect(`/forgot-password?error=${authErrorKey(updateError, "update")}`);
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
  if (error) redirect(`/reset-password?error=${authErrorKey(error, "update")}`);
  await forgetEmail();
  redirect("/dashboard?notice=password");
}

export async function signOut() {
  const supabase = await createClient();
  await supabase.auth.signOut();
  redirect("/login");
}
