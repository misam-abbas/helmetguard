import { cookies } from "next/headers";

// The address a sign-up or reset code was sent to rides in a short-lived cookie that page
// scripts cannot read, never in the URL.
const NAME = "hg_pending_email";

export async function rememberEmail(email: string) {
  (await cookies()).set(NAME, email, { httpOnly: true, secure: true, sameSite: "lax", path: "/", maxAge: 60 * 60 });
}

export async function pendingEmail() {
  return (await cookies()).get(NAME)?.value ?? null;
}

export async function forgetEmail() {
  (await cookies()).delete(NAME);
}
