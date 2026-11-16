import Link from "next/link";
import { buttonClass } from "@/components/ui";

// For a record that doesn't exist, or that this account isn't allowed to see.
export default function DashboardNotFound() {
  return (
    <div className="mx-auto max-w-md py-16 text-center">
      <h1 className="text-lg font-semibold">Not found</h1>
      <p className="mt-1 text-sm text-ink-2">This record doesn&apos;t exist, was deleted, or your account can&apos;t open it.</p>
      <Link href="/dashboard" className={`${buttonClass} mt-5`}>
        Go to the dashboard
      </Link>
    </div>
  );
}
