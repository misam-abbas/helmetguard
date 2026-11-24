import { Download, Shield } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";
import { PasswordInput } from "@/components/PasswordInput";
import { SubmitButton } from "@/components/SubmitButton";
import { inputClass, Notice, secondaryButtonClass } from "@/components/ui";
import { APK_NOTE, APK_URL } from "@/lib/app-download";
import { param, type SearchParams } from "@/lib/filters";
import { AUTH_MESSAGES } from "@/lib/supabase/email";
import { signIn, signUp } from "./actions";

export const metadata: Metadata = { title: "Sign in" };

const ERRORS: Record<string, string> = {
  ...AUTH_MESSAGES,
  credentials: "That email and password do not match an account.",
  short: "Use a password of at least 8 characters.",
  signup: "That account could not be created. Check the details and try again.",
  send: "The confirmation email could not be sent. Try again in a few minutes.",
  expired: "That sign-up took too long. Start again.",
  link: "That email link has expired or was already used. If you already confirmed your email, just sign in. Otherwise ask for a new email.",
};

const NOTICES: Record<string, string> = {
  confirmed: "Your email address is confirmed. Sign in to continue.",
};

export default async function LoginPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  const signup = param(params, "mode") === "signup";
  const error = ERRORS[param(params, "error") ?? ""];
  const notice = NOTICES[param(params, "notice") ?? ""];

  return (
    <main className="flex min-h-dvh items-center justify-center px-4 py-10">
      <div className="w-full max-w-sm">
        <div className="mb-6 flex items-center gap-2 text-base font-semibold">
          <Shield className="size-5" aria-hidden /> HelmetGuard
        </div>
        <h1 className="text-xl font-semibold tracking-tight">{signup ? "Create an account" : "Sign in to review alerts"}</h1>
        <p className="mb-5 mt-1 text-sm text-ink-2">
          {signup ? "We will email you a link to confirm your address. Then an admin approves your account." : "For approved reviewers and administrators."}
        </p>

        {error && <Notice tone="bad">{error}</Notice>}
        {notice && !error && <Notice>{notice}</Notice>}

        <form action={signup ? signUp : signIn} className="space-y-3">
          {signup && (
            <label className="block text-sm font-medium">
              Full name
              <input name="full_name" required autoComplete="name" maxLength={120} className={`${inputClass} mt-1`} />
            </label>
          )}
          <label className="block text-sm font-medium">
            Email
            <input name="email" type="email" required autoComplete="email" autoCapitalize="none" spellCheck={false} className={`${inputClass} mt-1`} />
          </label>
          <PasswordInput
            id="password"
            label="Password"
            autoComplete={signup ? "new-password" : "current-password"}
            minLength={signup ? 8 : undefined}
            hint={signup ? "At least 8 characters." : undefined}
          />
          <SubmitButton className="w-full">{signup ? "Create account" : "Sign in"}</SubmitButton>
        </form>

        {!signup && (
          <p className="mt-3 text-sm">
            <Link href="/forgot-password" className="font-medium text-ink underline underline-offset-2">
              Forgot password?
            </Link>
          </p>
        )}

        <p className="mt-4 text-sm text-ink-2">
          {signup ? "Already have an account? " : "New here? "}
          <Link href={signup ? "/login" : "/login?mode=signup"} className="font-medium text-ink underline underline-offset-2">
            {signup ? "Sign in" : "Create an account"}
          </Link>
        </p>
        <section aria-labelledby="get-app" className="mt-6 rounded-md border border-line p-3">
          <h2 id="get-app" className="text-sm font-medium">
            Detect on a phone
          </h2>
          <p className="mb-2 mt-0.5 text-xs text-ink-2">{APK_NOTE}</p>
          <a href={APK_URL} className={`${secondaryButtonClass} w-full`}>
            <Download className="size-4" aria-hidden /> Download the Android app
          </a>
        </section>
        <p className="mt-6 text-xs text-ink-3">
          <Link href="/privacy" className="underline underline-offset-2">
            Privacy notice
          </Link>
        </p>
      </div>
    </main>
  );
}
