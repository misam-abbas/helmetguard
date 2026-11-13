import { MailCheck } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";
import { redirect } from "next/navigation";
import { SubmitButton } from "@/components/SubmitButton";
import { inputClass, Notice } from "@/components/ui";
import { param, type SearchParams } from "@/lib/filters";
import { pendingEmail } from "@/lib/pending-email";
import { resendSignupCode, verifySignup } from "../login/actions";

export const metadata: Metadata = { title: "Confirm your email" };

const ERRORS: Record<string, string> = {
  code: "That code is wrong or has expired. Check the email, or send a new code.",
  send: "Could not send a new code. Wait a minute, then try again.",
};

export default async function VerifyPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const email = await pendingEmail();
  if (!email) redirect("/login?mode=signup");
  const params = await searchParams;
  const error = ERRORS[param(params, "error") ?? ""];

  return (
    <main className="flex min-h-dvh items-center justify-center px-4 py-10">
      <div className="w-full max-w-sm">
        <MailCheck className="mb-3 size-6 text-ink-2" aria-hidden />
        <h1 className="text-xl font-semibold tracking-tight">Check your email</h1>
        <p className="mb-5 mt-1 text-sm text-ink-2">
          We sent an email to <span className="font-medium text-ink">{email}</span>. Enter the code from it, or open its link in this browser. Then an admin approves your account.
        </p>

        {error && <Notice tone="bad">{error}</Notice>}
        {param(params, "sent") && <Notice>A new code is on its way.</Notice>}

        <form action={verifySignup} className="space-y-3">
          <label className="block text-sm font-medium">
            Code from the email
            <input name="code" required inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6,10}" maxLength={10} className={`${inputClass} mt-1 tracking-widest`} />
          </label>
          <SubmitButton className="w-full">Confirm email</SubmitButton>
        </form>

        <div className="mt-4 flex items-center justify-between text-sm text-ink-2">
          <form action={resendSignupCode}>
            <button type="submit" className="font-medium text-ink underline underline-offset-2">
              Send a new code
            </button>
          </form>
          <Link href="/login" className="underline underline-offset-2">
            Back to sign in
          </Link>
        </div>
      </div>
    </main>
  );
}
