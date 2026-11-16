import { Shield } from "lucide-react";
import Link from "next/link";
import { buttonClass } from "@/components/ui";

export default function NotFound() {
  return (
    <main className="flex min-h-dvh items-center justify-center px-4 py-10">
      <div className="w-full max-w-sm text-center">
        <Shield className="mx-auto mb-3 size-6 text-ink-2" aria-hidden />
        <h1 className="text-xl font-semibold tracking-tight">Page not found</h1>
        <p className="mt-1 text-sm text-ink-2">The address may be mistyped, or the page has moved.</p>
        <Link href="/dashboard" className={`${buttonClass} mt-5`}>
          Go to HelmetGuard
        </Link>
      </div>
    </main>
  );
}
