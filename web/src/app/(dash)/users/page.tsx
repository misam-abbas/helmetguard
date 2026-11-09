import type { Metadata } from "next";
import { SubmitButton } from "@/components/SubmitButton";
import { EmptyState, inputClass, Notice, PageHeader, PendingPill } from "@/components/ui";
import { requireAdmin } from "@/lib/auth";
import { param, type SearchParams } from "@/lib/filters";
import { formatDateTime } from "@/lib/format";
import type { Device, Profile } from "@/lib/types";
import { updateUser } from "./actions";

export const metadata: Metadata = { title: "Users" };

const ERRORS: Record<string, string> = {
  self: "You cannot demote or disable your own account.",
  save: "That change could not be saved.",
  invalid: "That value is not allowed.",
};

function UserCard({ user, devices }: { user: Profile; devices: Device[] }) {
  return (
    <li className="rounded-lg border border-line bg-surface p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="flex flex-wrap items-center gap-2 text-sm font-medium">
            <span className="truncate">{user.full_name || "No name given"}</span>
            {user.status === "pending" && <PendingPill>Waiting for approval</PendingPill>}
            {user.status === "disabled" && <span className="text-xs text-ink-3">Disabled</span>}
          </p>
          <p className="truncate text-sm text-ink-2">{user.email}</p>
          <p className="mt-0.5 text-xs text-ink-3">Joined {formatDateTime(user.created_at)}</p>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <form action={updateUser} className="flex items-center gap-2">
            <input type="hidden" name="id" value={user.id} />
            <select name="role" defaultValue={user.role} aria-label={`Role for ${user.email}`} className={`${inputClass} w-32`}>
              <option value="operator">Operator</option>
              <option value="reviewer">Reviewer</option>
              <option value="admin">Admin</option>
            </select>
            <SubmitButton variant="secondary">Set role</SubmitButton>
          </form>
          <form action={updateUser}>
            <input type="hidden" name="id" value={user.id} />
            {user.status === "active" ? (
              <SubmitButton name="status" value="disabled" variant="secondary">
                Disable
              </SubmitButton>
            ) : (
              <SubmitButton name="status" value="active">
                {user.status === "pending" ? "Approve" : "Enable"}
              </SubmitButton>
            )}
          </form>
        </div>
      </div>

      {devices.length > 0 && (
        <ul className="mt-3 space-y-0.5 border-t border-line pt-3 text-xs text-ink-3">
          {devices.map((d) => (
            <li key={d.id}>
              {[d.manufacturer, d.model].filter(Boolean).join(" ") || d.label || "Unnamed device"} · Android {d.android_version ?? "—"} · last sync {formatDateTime(d.last_sync_at)}
            </li>
          ))}
        </ul>
      )}
    </li>
  );
}

export default async function UsersPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const { supabase } = await requireAdmin();
  const params = await searchParams;

  const [usersResult, devicesResult] = await Promise.all([
    supabase.from("profiles").select("*").order("created_at", { ascending: false }),
    supabase.from("devices").select("*"),
  ]);
  const users = (usersResult.data ?? []) as Profile[];
  const devices = (devicesResult.data ?? []) as Device[];
  // Pending accounts come first because they need action.
  const ordered = [...users.filter((u) => u.status === "pending"), ...users.filter((u) => u.status !== "pending")];
  const error = ERRORS[param(params, "error") ?? ""];

  return (
    <>
      <PageHeader title="User management" subtitle={`${users.filter((u) => u.status === "pending").length} waiting for approval`} />
      {error && <Notice tone="bad">{error}</Notice>}
      {param(params, "saved") && <Notice>Saved.</Notice>}
      {usersResult.error && <Notice tone="bad">Users could not be loaded.</Notice>}
      {ordered.length === 0 ? (
        <EmptyState title="No users yet" />
      ) : (
        <ul className="space-y-2">
          {ordered.map((user) => (
            <UserCard key={user.id} user={user} devices={devices.filter((d) => d.owner_id === user.id)} />
          ))}
        </ul>
      )}
    </>
  );
}
