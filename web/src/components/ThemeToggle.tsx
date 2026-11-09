"use client";

import { Moon, Sun } from "lucide-react";

export function ThemeToggle() {
  function toggle() {
    const dark = document.documentElement.classList.toggle("dark");
    try {
      localStorage.setItem("theme", dark ? "dark" : "light");
    } catch {
      // Private windows can block storage; the theme still applies for this page.
    }
  }

  return (
    <button type="button" onClick={toggle} aria-label="Switch between light and dark theme" className="inline-flex size-9 items-center justify-center rounded-md text-ink-2 hover:bg-surface-2">
      <Sun className="hidden size-4 dark:block" aria-hidden />
      <Moon className="size-4 dark:hidden" aria-hidden />
    </button>
  );
}
