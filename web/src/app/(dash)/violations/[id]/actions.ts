"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { requireStaff } from "@/lib/auth";
import type { ReviewStatus } from "@/lib/types";

const UUID = /^[0-9a-f-]{36}$/i;
const STATUSES: ReviewStatus[] = ["unreviewed", "confirmed", "rejected", "needs_info"];

async function apply(id: string, changes: Record<string, string | null>) {
  if (!UUID.test(id)) redirect("/violations");
  const { supabase } = await requireStaff();
  const { data, error } = await supabase.from("violations").update(changes).eq("id", id).select("id");
  if (error || !data?.length) redirect(`/violations/${id}?error=1`);
  revalidatePath(`/violations/${id}`);
  redirect(`/violations/${id}?saved=1`);
}

export async function setReviewStatus(form: FormData) {
  const status = String(form.get("status")) as ReviewStatus;
  if (!STATUSES.includes(status)) return;
  await apply(String(form.get("id")), { review_status: status });
}

export async function saveNotes(form: FormData) {
  const notes = String(form.get("notes") ?? "").slice(0, 2000);
  await apply(String(form.get("id")), { review_notes: notes || null });
}
