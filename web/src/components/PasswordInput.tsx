"use client";

import { Eye, EyeOff } from "lucide-react";
import { useState } from "react";
import { inputClass } from "./ui";

/** A password field with a button that shows what was typed, to check it before sending. */
export function PasswordInput({
  id,
  label,
  name = "password",
  autoComplete,
  minLength,
  hint,
}: {
  id: string;
  label: string;
  name?: string;
  autoComplete: "current-password" | "new-password";
  minLength?: number;
  hint?: string;
}) {
  const [shown, setShown] = useState(false);
  return (
    <div>
      <label htmlFor={id} className="block text-sm font-medium">
        {label}
      </label>
      <div className="relative mt-1">
        <input
          id={id}
          name={name}
          type={shown ? "text" : "password"}
          required
          minLength={minLength}
          autoComplete={autoComplete}
          autoCapitalize="none"
          autoCorrect="off"
          spellCheck={false}
          aria-describedby={hint ? `${id}-hint` : undefined}
          className={`${inputClass} pr-11`}
        />
        <button
          type="button"
          onClick={() => setShown((value) => !value)}
          aria-label={shown ? "Hide password" : "Show password"}
          aria-pressed={shown}
          aria-controls={id}
          title={shown ? "Hide password" : "Show password"}
          className="absolute inset-y-0 right-0 inline-flex w-10 items-center justify-center rounded-r-md text-ink-2 hover:text-ink"
        >
          {shown ? <EyeOff className="size-4" aria-hidden /> : <Eye className="size-4" aria-hidden />}
        </button>
      </div>
      {hint && (
        <p id={`${id}-hint`} className="mt-1 text-xs text-ink-3">
          {hint}
        </p>
      )}
    </div>
  );
}
