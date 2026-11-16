import { MailCheck } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";
import { redirect } from "next/navigation";
import { SubmitButton } from "@/components/SubmitButton";
import { inputClass, Notice } from "@/components/ui";
import { param, type SearchParams } from "@/lib/filters";
import { pendingEmail } from "@/lib/pending-email";
import { AUTH_MESSAGES } from "@/lib/supabase/email";
import { resendSignupCode, verifySignup } from "../login/actions";

export const metadata: Metadata = { title: "Confirm your email" };

const ERRORS: Record<string, string> = {
  ...AUTH_MESSAGES,
  code: "That code is wrong or has expired. Check the email, or send a new one.",
  send: "Could not send a new email. Wait a minute, then try again.",
};

export default async function VerifyPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const email = await pendingEmail();
  if (!email) redirect("/login?mode=signup");
  const params = await searchParams;
  const errorKey = param(params, "error") ?? "";
  const error = ERRORS[errorKey];

  return (
    <main className="flex min-h-dvh items-center justify-center px-4 py-10">
      <div className="w-full max-w-sm">
        <MailCheck className="mb-3 size-6 text-ink-2" aria-hidden />
        <h1 className="text-xl font-semibold tracking-tight">Check your email</h1>
        <p className="mb-5 mt-1 text-sm text-ink-2">
          We sent a confirmation link to <span className="break-all font-medium text-ink">{email}</span>. Open it on this device or any other, for example in
          your phone&apos;s mail app. Then an admin approves your account.
        </p>

        {errorKey === "unconfirmed" && <Notice tone="warn">Confirm your email before signing in: open the link we sent you, or send a new one.</Notice>}
        {error && <Notice tone="bad">{error}</Notice>}
        {param(params, "sent") && <Notice>A new email is on its way. It can take a minute, and may land in spam.</Notice>}

        <form action={resendSignupCode}>
          <SubmitButton variant="secondary" className="w-full">
            Send a new email
          </SubmitButton>
        </form>

        <details className="mt-5 rounded-md border border-line px-3 py-2" open={errorKey === "code"}>
          <summary className="cursor-pointer text-sm font-medium">The email has a code instead</summary>
          <form action={verifySignup} className="mt-3 space-y-3 pb-1">
            <label className="block text-sm font-medium">
              Code from the email
              <input name="code" required inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6,10}" maxLength={10} className={`${inputClass} mt-1 tracking-widest`} />
            </label>
            <SubmitButton className="w-full">Confirm email</SubmitButton>
          </form>
        </details>

        <p className="mt-4 text-sm text-ink-2">
          Already confirmed?{" "}
          <Link href="/login" className="font-medium text-ink underline underline-offset-2">
            Sign in
          </Link>
        </p>
      </div>
    </main>
  );
}
