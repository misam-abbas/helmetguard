"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { requireAdmin } from "@/lib/auth";
import type { UserRole, UserStatus } from "@/lib/types";

const ROLES: UserRole[] = ["admin", "reviewer", "operator"];
const STATUSES: UserStatus[] = ["active", "disabled"];

export async function updateUser(form: FormData) {
  const { supabase, userId } = await requireAdmin();
  const id = String(form.get("id"));
  const role = form.get("role") ? (String(form.get("role")) as UserRole) : null;
  const status = form.get("status") ? (String(form.get("status")) as UserStatus) : null;

  if ((role && !ROLES.includes(role)) || (status && !STATUSES.includes(status))) redirect("/users?error=invalid");

  // Stops the last admin accidentally locking everyone out.
  if (id === userId && ((role && role !== "admin") || status === "disabled")) redirect("/users?error=self");

  const changes: Partial<{ role: UserRole; status: UserStatus }> = {};
  if (role) changes.role = role;
  if (status) changes.status = status;

  const { data, error } = await supabase.from("profiles").update(changes).eq("id", id).select("id");
  if (error || !data?.length) redirect("/users?error=save");
  revalidatePath("/users");
  redirect("/users?saved=1");
}
