import { AppShell } from "@/components/AppShell";
import { requireStaff } from "@/lib/auth";
import { signOut } from "../login/actions";

export default async function DashLayout({ children }: { children: React.ReactNode }) {
  const { profile } = await requireStaff();
  return (
    <AppShell name={profile.full_name || profile.email || "Signed in"} role={profile.role} isAdmin={profile.role === "admin"} signOut={signOut}>
      {children}
    </AppShell>
  );
}
