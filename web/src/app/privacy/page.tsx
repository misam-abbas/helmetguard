import { ArrowLeft } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";
import { getCurrentUser } from "@/lib/auth";

export const metadata: Metadata = { title: "Privacy and data notice" };

const SECTIONS = [
  {
    title: "What is collected",
    items: [
      "Photos of motorcycles flagged by the AI: the road scene, a picture of the whole motorcycle, the riders and their bare heads.",
      "Session totals, a typed location label, the time, and which device and operator captured the record.",
      "A video analysed on the website stays on the computer that analyses it. Only the alerts with their photos, and the video's file name and totals, are saved.",
    ],
  },
  {
    title: "What is never done",
    items: [
      "No face is recognised or matched. Head crops are used only to decide helmet or no helmet.",
      "Number plates are not detected, read or stored.",
      "No fines are issued. Every record stays an unverified AI alert until a person reviews it.",
      "GPS coordinates are not collected by default.",
    ],
  },
  {
    title: "Who can see it",
    items: [
      "Operators see only records from their own account.",
      "Reviewers and admins see all records, and every review change is written to an audit log.",
      "Evidence files are private. They open only through links that expire after 60 minutes.",
      "Signed-out visitors can read nothing.",
    ],
  },
  {
    title: "How long it is kept",
    items: [
      "Unreviewed and rejected alerts are deleted after the retention period (90 days unless an admin changes it).",
      "Confirmed violations are kept until the project ends.",
      "On the phone, full-size images can be removed 7 days after syncing, keeping the thumbnail and record.",
    ],
  },
];

// Open to everyone, so people can read it before they create an account.
export default async function PrivacyPage() {
  const current = await getCurrentUser();

  return (
    <main className="mx-auto max-w-2xl px-4 py-8">
      <Link href={current ? "/dashboard" : "/login"} className="mb-5 inline-flex items-center gap-1.5 text-sm text-ink-2 hover:text-ink">
        <ArrowLeft className="size-4" aria-hidden /> {current ? "Back to the dashboard" : "Back to sign in"}
      </Link>
      <h1 className="text-xl font-semibold tracking-tight">Privacy and data notice</h1>
      <p className="mt-1 text-sm text-ink-2">HelmetGuard is an academic prototype. This is how it treats the people it records.</p>
      <div className="mt-6 space-y-6">
        {SECTIONS.map((section) => (
          <section key={section.title}>
            <h2 className="mb-1.5 text-sm font-semibold">{section.title}</h2>
            <ul className="list-disc space-y-1 pl-5 text-sm text-ink-2">
              {section.items.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>
          </section>
        ))}
      </div>
    </main>
  );
}
