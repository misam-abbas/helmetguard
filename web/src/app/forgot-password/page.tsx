import { KeyRound } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";
import { PasswordInput } from "@/components/PasswordInput";
import { SubmitButton } from "@/components/SubmitButton";
import { inputClass, Notice } from "@/components/ui";
import { param, type SearchParams } from "@/lib/filters";
import { pendingEmail } from "@/lib/pending-email";
import { AUTH_MESSAGES } from "@/lib/supabase/email";
import { requestPasswordReset, resetPassword } from "../login/actions";

export const metadata: Metadata = { title: "Reset your password" };

const ERRORS: Record<string, string> = {
  ...AUTH_MESSAGES,
  email: "Enter your email address.",
  send: "Could not send the email. Wait a minute, then try again.",
  expired: "That reset took too long. Start again.",
  short: "Use a password of at least 8 characters.",
  code: "That code is wrong or has expired. Check the email, or ask for a new one.",
  update: "Your new password could not be saved. Try a different one.",
  link: "That reset link has expired or was already used. Ask for a new one below.",
};

export default async function ForgotPasswordPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  const email = await pendingEmail();
  const onCodeStep = param(params, "step") === "code" && email;
  const errorKey = param(params, "error") ?? "";
  const error = ERRORS[errorKey];

  return (
    <main className="flex min-h-dvh items-center justify-center px-4 py-10">
      <div className="w-full max-w-sm">
        <KeyRound className="mb-3 size-6 text-ink-2" aria-hidden />
        <h1 className="text-xl font-semibold tracking-tight">{onCodeStep ? "Check your email" : "Reset your password"}</h1>
        <p className="mb-5 mt-1 text-sm text-ink-2">
          {onCodeStep ? (
            <>
              If <span className="break-all font-medium text-ink">{email}</span> has an account, we sent it a link. Open it on this device or any other to
              choose a new password. It can take a minute, and may land in spam.
            </>
          ) : (
            "Enter your account's email and we will send you a link to choose a new password."
          )}
        </p>

        {error && <Notice tone="bad">{error}</Notice>}

        {onCodeStep ? (
          <details className="rounded-md border border-line px-3 py-2" open={errorKey === "code" || errorKey === "short"}>
            <summary className="cursor-pointer text-sm font-medium">The email has a code instead</summary>
            <form action={resetPassword} className="mt-3 space-y-3 pb-1">
              <label className="block text-sm font-medium">
                Code from the email
                <input name="code" required inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6,10}" maxLength={10} className={`${inputClass} mt-1 tracking-widest`} />
              </label>
              <PasswordInput id="new-password" label="New password" autoComplete="new-password" minLength={8} hint="At least 8 characters." />
              <SubmitButton className="w-full">Save new password</SubmitButton>
            </form>
          </details>
        ) : (
          <form action={requestPasswordReset} className="space-y-3">
            <label className="block text-sm font-medium">
              Email
              <input name="email" type="email" required autoComplete="email" autoCapitalize="none" spellCheck={false} defaultValue={email ?? undefined} className={`${inputClass} mt-1`} />
            </label>
            <SubmitButton className="w-full">Send reset link</SubmitButton>
          </form>
        )}

        <div className="mt-4 flex items-center justify-between text-sm text-ink-2">
          {onCodeStep ? (
            <Link href="/forgot-password" className="underline underline-offset-2">
              Send a new link
            </Link>
          ) : (
            <span />
          )}
          <Link href="/login" className="underline underline-offset-2">
            Back to sign in
          </Link>
        </div>
      </div>
    </main>
  );
}
