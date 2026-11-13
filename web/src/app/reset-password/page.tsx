import { KeyRound } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";
import { redirect } from "next/navigation";
import { SubmitButton } from "@/components/SubmitButton";
import { inputClass, Notice } from "@/components/ui";
import { param, type SearchParams } from "@/lib/filters";
import { createClient } from "@/lib/supabase/server";
import { setNewPassword } from "../login/actions";

export const metadata: Metadata = { title: "Choose a new password" };

const ERRORS: Record<string, string> = {
  short: "Use a password of at least 8 characters.",
  update: "Your new password could not be saved. Try a different one.",
};

// Reached from a password-reset email link, or by a signed-in person changing their password.
export default async function ResetPasswordPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const supabase = await createClient();
  const {
    data: { user },
  } = await supabase.auth.getUser();
  if (!user) redirect("/login?error=link");
  const params = await searchParams;
  const error = ERRORS[param(params, "error") ?? ""];

  return (
    <main className="flex min-h-dvh items-center justify-center px-4 py-10">
      <div className="w-full max-w-sm">
        <KeyRound className="mb-3 size-6 text-ink-2" aria-hidden />
        <h1 className="text-xl font-semibold tracking-tight">Choose a new password</h1>
        <p className="mb-5 mt-1 text-sm text-ink-2">
          For <span className="font-medium text-ink">{user.email}</span>. Use it next time you sign in, on the website and in the app.
        </p>

        {error && <Notice tone="bad">{error}</Notice>}

        <form action={setNewPassword} className="space-y-3">
          <label className="block text-sm font-medium">
            New password
            <input name="password" type="password" required minLength={8} autoComplete="new-password" className={`${inputClass} mt-1`} />
          </label>
          <SubmitButton className="w-full">Save new password</SubmitButton>
        </form>

        <p className="mt-4 text-sm text-ink-2">
          <Link href="/dashboard" className="underline underline-offset-2">
            Cancel
          </Link>
        </p>
      </div>
    </main>
  );
}
