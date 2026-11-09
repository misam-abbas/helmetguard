import { Clock3, ShieldOff } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";
import { redirect } from "next/navigation";
import { SubmitButton } from "@/components/SubmitButton";
import { getCurrentUser } from "@/lib/auth";
import { signOut } from "../login/actions";

export const metadata: Metadata = { title: "Account status" };

export default async function PendingPage() {
  const current = await getCurrentUser();
  if (!current) redirect("/login");
  const { status, role } = current.profile;
  if (status === "active" && role !== "operator") redirect("/dashboard");

  const content =
    status === "pending"
      ? { icon: Clock3, title: "Waiting for approval", body: "An admin needs to approve your account before you can use HelmetGuard. Check back after they have done so." }
      : status === "disabled"
        ? { icon: ShieldOff, title: "This account is disabled", body: "Ask an admin if you think this is a mistake." }
        : { icon: ShieldOff, title: "Operators use the Android app", body: "The dashboard is for reviewers and admins. Your account is active, so sign in on the HelmetGuard app to capture alerts." };
  const Icon = content.icon;

  return (
    <main className="flex min-h-dvh items-center justify-center px-4 py-10">
      <div className="w-full max-w-sm">
        <Icon className="mb-3 size-6 text-ink-2" aria-hidden />
        <h1 className="text-xl font-semibold tracking-tight">{content.title}</h1>
        <p className="mb-5 mt-1 text-sm text-ink-2">{content.body}</p>
        <div className="flex items-center gap-3">
          <form action={signOut}>
            <SubmitButton variant="secondary">Sign out</SubmitButton>
          </form>
          <Link href="/privacy" className="text-sm underline underline-offset-2">
            Privacy notice
          </Link>
        </div>
      </div>
    </main>
  );
}
