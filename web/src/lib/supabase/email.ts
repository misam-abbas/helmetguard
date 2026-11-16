import { createClient } from "@supabase/supabase-js";
import { SUPABASE_KEY, SUPABASE_URL } from "./env";

/**
 * Sends sign-up and password-reset emails. Its links carry the session itself (the
 * "implicit" flow), so they work in any browser and on another device, such as a phone's
 * mail app. The cookie client uses PKCE instead, whose links only work in the browser
 * that asked. This client never keeps a session; /auth/callback receives it.
 */
export function createEmailClient() {
  return createClient(SUPABASE_URL, SUPABASE_KEY, {
    auth: { flowType: "implicit", persistSession: false, autoRefreshToken: false, detectSessionInUrl: false },
  });
}

/** Turns a Supabase auth error into a short key the sign-in pages have a message for. */
export function authErrorKey(error: { code?: string; status?: number } | null, fallback: string): string {
  switch (error?.code) {
    case "over_email_send_rate_limit":
      return "rate";
    case "over_request_rate_limit":
      return "wait";
    case "email_address_not_authorized":
      return "not-allowed";
    case "email_address_invalid":
    case "validation_failed":
      return "invalid";
    case "weak_password":
      return "weak";
    case "user_already_exists":
    case "email_exists":
      return "exists";
    case "signup_disabled":
      return "closed";
    case "same_password":
      return "same";
    default:
      return error?.status === 429 ? "rate" : fallback;
  }
}

/** Messages for the keys above, shared by the sign-in, sign-up, verify and reset pages. */
export const AUTH_MESSAGES: Record<string, string> = {
  rate: "Too many emails have been sent recently, so this one wasn't. Try again later.",
  wait: "Too many attempts. Wait a minute, then try again.",
  "not-allowed": "This site can't send email to that address yet. Ask the HelmetGuard admin.",
  invalid: "Enter a valid email address.",
  weak: "Choose a stronger password: at least 8 characters, and not a common one.",
  exists: "An account with that email already exists. Sign in, or use “Forgot password?”.",
  closed: "New accounts are turned off. Ask the HelmetGuard admin.",
  same: "Choose a password different from your current one.",
};
