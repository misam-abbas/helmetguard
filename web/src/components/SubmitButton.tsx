"use client";

import { Loader2 } from "lucide-react";
import { useFormStatus } from "react-dom";
import { buttonClass, secondaryButtonClass } from "./ui";

export function SubmitButton({
  children,
  variant = "primary",
  name,
  value,
  className = "",
}: {
  children: React.ReactNode;
  variant?: "primary" | "secondary";
  name?: string;
  value?: string;
  className?: string;
}) {
  const { pending } = useFormStatus();
  return (
    <button
      type="submit"
      name={name}
      value={value}
      disabled={pending}
      aria-busy={pending || undefined}
      className={`${variant === "primary" ? buttonClass : secondaryButtonClass} ${className}`}
    >
      {pending && <Loader2 className="size-4 animate-spin" aria-hidden />}
      {children}
    </button>
  );
}
