import { KeyRound } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";
import { SubmitButton } from "@/components/SubmitButton";
import { inputClass, Notice } from "@/components/ui";
import { param, type SearchParams } from "@/lib/filters";
import { pendingEmail } from "@/lib/pending-email";
import { requestPasswordReset, resetPassword } from "../login/actions";

export const metadata: Metadata = { title: "Reset your password" };

const ERRORS: Record<string, string> = {
  email: "Enter your email address.",
  send: "Could not send the code. Wait a minute, then try again.",
  expired: "That reset took too long. Start again.",
  short: "Use a password of at least 8 characters.",
  code: "That code is wrong or has expired. Check the email, or send a new code.",
  update: "Your new password could not be saved. Try a different one.",
};

export default async function ForgotPasswordPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  const email = await pendingEmail();
  const onCodeStep = param(params, "step") === "code" && email;
  const error = ERRORS[param(params, "error") ?? ""];

  return (
    <main className="flex min-h-dvh items-center justify-center px-4 py-10">
      <div className="w-full max-w-sm">
        <KeyRound className="mb-3 size-6 text-ink-2" aria-hidden />
        <h1 className="text-xl font-semibold tracking-tight">{onCodeStep ? "Choose a new password" : "Reset your password"}</h1>
        <p className="mb-5 mt-1 text-sm text-ink-2">
          {onCodeStep ? (
            <>
              If <span className="font-medium text-ink">{email}</span> has an account, we sent it an email. Open its link in this browser, or enter the code from it here with a new password.
            </>
          ) : (
            "Enter your email and we will send you a reset link and code."
          )}
        </p>

        {error && <Notice tone="bad">{error}</Notice>}

        {onCodeStep ? (
          <form action={resetPassword} className="space-y-3">
            <label className="block text-sm font-medium">
              Code from the email
              <input name="code" required inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6,10}" maxLength={10} className={`${inputClass} mt-1 tracking-widest`} />
            </label>
            <label className="block text-sm font-medium">
              New password
              <input name="password" type="password" required minLength={8} autoComplete="new-password" className={`${inputClass} mt-1`} />
            </label>
            <SubmitButton className="w-full">Save new password</SubmitButton>
          </form>
        ) : (
          <form action={requestPasswordReset} className="space-y-3">
            <label className="block text-sm font-medium">
              Email
              <input name="email" type="email" required autoComplete="email" className={`${inputClass} mt-1`} />
            </label>
            <SubmitButton className="w-full">Send reset email</SubmitButton>
          </form>
        )}

        <div className="mt-4 flex items-center justify-between text-sm text-ink-2">
          {onCodeStep ? (
            <Link href="/forgot-password" className="underline underline-offset-2">
              Send a new code
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
