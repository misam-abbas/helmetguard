"use client";

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
    <button type="submit" name={name} value={value} disabled={pending} className={`${variant === "primary" ? buttonClass : secondaryButtonClass} ${className}`}>
      {children}
    </button>
  );
}
