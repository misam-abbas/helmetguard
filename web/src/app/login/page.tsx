import { Shield } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";
import { SubmitButton } from "@/components/SubmitButton";
import { inputClass, Notice } from "@/components/ui";
import { param, type SearchParams } from "@/lib/filters";
import { signIn, signUp } from "./actions";

export const metadata: Metadata = { title: "Sign in" };

const ERRORS: Record<string, string> = {
  credentials: "That email and password do not match an account.",
  unconfirmed: "Confirm your email first. Create the account again to get a new code.",
  short: "Use a password of at least 8 characters.",
  signup: "That account could not be created. It may already exist.",
  expired: "That sign-up took too long. Start again.",
  link: "That email link has expired or was already used, or it was opened in a different browser. Ask for a new one, or use the code from the email.",
};

export default async function LoginPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  const signup = param(params, "mode") === "signup";
  const error = ERRORS[param(params, "error") ?? ""];

  return (
    <main className="flex min-h-dvh items-center justify-center px-4 py-10">
      <div className="w-full max-w-sm">
        <div className="mb-6 flex items-center gap-2 text-base font-semibold">
          <Shield className="size-5" aria-hidden /> HelmetGuard
        </div>
        <h1 className="text-xl font-semibold tracking-tight">{signup ? "Create an account" : "Sign in to review alerts"}</h1>
        <p className="mb-5 mt-1 text-sm text-ink-2">
          {signup ? "We will email you a code to confirm your address. Then an admin approves your account." : "For approved reviewers and administrators."}
        </p>

        {error && <Notice tone="bad">{error}</Notice>}

        <form action={signup ? signUp : signIn} className="space-y-3">
          {signup && (
            <label className="block text-sm font-medium">
              Full name
              <input name="full_name" required autoComplete="name" className={`${inputClass} mt-1`} />
            </label>
          )}
          <label className="block text-sm font-medium">
            Email
            <input name="email" type="email" required autoComplete="email" className={`${inputClass} mt-1`} />
          </label>
          <label className="block text-sm font-medium">
            Password
            <input name="password" type="password" required minLength={signup ? 8 : undefined} autoComplete={signup ? "new-password" : "current-password"} className={`${inputClass} mt-1`} />
          </label>
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
      </div>
    </main>
  );
}
