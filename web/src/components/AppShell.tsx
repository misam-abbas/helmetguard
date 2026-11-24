"use client";

import { BarChart3, Download, FileWarning, KeyRound, Layers, LayoutDashboard, LogOut, Menu, Settings, Shield, ShieldCheck, Users, Video, X } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useState } from "react";
import { APK_URL } from "@/lib/app-download";
import { ThemeToggle } from "./ThemeToggle";
import { cn } from "./ui";

const STAFF_LINKS = [
  { href: "/dashboard", label: "Dashboard", icon: LayoutDashboard },
  { href: "/violations", label: "Violations", icon: FileWarning },
  { href: "/detect", label: "Analyse video", icon: Video },
  { href: "/sessions", label: "Sessions", icon: Layers },
  { href: "/analytics", label: "Analytics", icon: BarChart3 },
];

const ADMIN_LINKS = [
  { href: "/users", label: "Users", icon: Users },
  { href: "/settings", label: "Settings", icon: Settings },
];

export function AppShell({
  name,
  role,
  isAdmin,
  signOut,
  children,
}: {
  name: string;
  role: string;
  isAdmin: boolean;
  signOut: () => Promise<void>;
  children: React.ReactNode;
}) {
  const pathname = usePathname();
  const [open, setOpen] = useState(false);
  const links = [...STAFF_LINKS, ...(isAdmin ? ADMIN_LINKS : []), { href: "/privacy", label: "Privacy", icon: ShieldCheck }];

  return (
    <div className="lg:grid lg:min-h-dvh lg:grid-cols-[232px_1fr]">
      <header className="sticky top-0 z-30 flex h-14 items-center justify-between border-b border-line bg-surface px-4 lg:hidden">
        <span className="flex items-center gap-2 text-sm font-semibold">
          <Shield className="size-4" aria-hidden /> HelmetGuard
        </span>
        <button type="button" onClick={() => setOpen((v) => !v)} aria-expanded={open} aria-label="Toggle navigation menu" className="inline-flex size-9 items-center justify-center rounded-md hover:bg-surface-2">
          {open ? <X className="size-5" aria-hidden /> : <Menu className="size-5" aria-hidden />}
        </button>
      </header>

      <aside className={cn("border-b border-line bg-surface lg:sticky lg:top-0 lg:flex lg:h-dvh lg:flex-col lg:border-r lg:border-b-0", open ? "block" : "hidden lg:flex")}>
        <div className="hidden h-14 items-center gap-2 border-b border-line px-5 text-sm font-semibold lg:flex">
          <Shield className="size-4" aria-hidden /> HelmetGuard
        </div>
        <nav className="flex-1 space-y-0.5 overflow-y-auto p-3">
          {links.map(({ href, label, icon: Icon }) => {
            const active = pathname === href || pathname.startsWith(`${href}/`);
            const props = {
              onClick: () => setOpen(false),
              "aria-current": active ? ("page" as const) : undefined,
              className: cn("flex h-9 items-center gap-2.5 rounded-md px-2.5 text-sm", active ? "bg-surface-2 font-medium text-ink" : "text-ink-2 hover:bg-surface-2"),
            };
            const content = (
              <>
                <Icon className="size-4" aria-hidden />
                {label}
              </>
            );
            // The video page loads in full so the browser applies its cross-origin isolation,
            // which lets detection use several CPU threads.
            return href === "/detect" ? (
              <a key={href} href={href} {...props}>
                {content}
              </a>
            ) : (
              <Link key={href} href={href} {...props}>
                {content}
              </Link>
            );
          })}
          <a href={APK_URL} title="For Android 12 or newer" className="flex h-9 items-center gap-2.5 rounded-md px-2.5 text-sm text-ink-2 hover:bg-surface-2">
            <Download className="size-4" aria-hidden />
            Android app
          </a>
        </nav>
        <div className="flex items-center justify-between gap-2 border-t border-line p-3">
          <div className="min-w-0 px-1">
            <p className="truncate text-sm font-medium">{name}</p>
            <p className="text-xs capitalize text-ink-3">{role}</p>
          </div>
          <div className="flex shrink-0 items-center">
            <ThemeToggle />
            <Link href="/reset-password" aria-label="Change password" title="Change password" className="inline-flex size-9 items-center justify-center rounded-md text-ink-2 hover:bg-surface-2">
              <KeyRound className="size-4" aria-hidden />
            </Link>
            <form action={signOut}>
              <button type="submit" aria-label="Sign out" className="inline-flex size-9 items-center justify-center rounded-md text-ink-2 hover:bg-surface-2">
                <LogOut className="size-4" aria-hidden />
              </button>
            </form>
          </div>
        </div>
      </aside>

      <main className="min-w-0 px-4 py-5 sm:px-6 lg:px-8 lg:py-7">{children}</main>
    </div>
  );
}
