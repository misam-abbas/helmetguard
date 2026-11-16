# Supabase settings for sign-up and password-reset emails

When someone creates an account or forgets their password, Supabase emails them a link. The link opens the website's `/auth/callback` page, which works in any browser and on any device, for example a phone's mail app. A confirmed account then waits for an admin to approve it on the Users page.

## Already set (17 November 2026)

In project `helmetguard-dev`, **Authentication → URL Configuration**:

- **Site URL:** `https://helmetguard.vercel.app` (it was `http://localhost:3000`, which is why email links opened localhost)
- **Redirect URLs:** `https://helmetguard.vercel.app/**` and, for local testing, `http://localhost:3000/**`

**Confirm email** is on (Authentication → Sign In / Providers), and email codes are 8 digits long.

## Still to do: your own email sender (Authentication → Emails → SMTP Settings)

Supabase's built-in sender only sends a few emails an hour. On 16 November, sign-ups failed with "email rate limit exceeded" after a few tries. It also won't let you change what the emails say. Turn on **Custom SMTP** and fill in a provider's details, for example:

- **Gmail:** host `smtp.gmail.com`, port `465`, username your Gmail address, sender email the same address, sender name `HelmetGuard`. The password is a Gmail **App password**, not your normal password: Google Account → Security → 2-Step Verification (turn it on) → App passwords → create one called HelmetGuard, and paste the 16 letters. Gmail sends up to about 500 emails a day.
- **Brevo or Resend:** both have free plans; their dashboards show the host, port, username and password to paste in.

Then raise **Authentication → Rate Limits → emails per hour** to about 30, and send yourself a test with "Forgot password?" on the website.

## Optional, after the sender works: codes in the emails (Authentication → Emails → Templates)

The app and the website also accept a code from the email, for people who can't open links. Add `{{ .Token }}` to **Confirm sign up** and **Reset password**, and keep the link. For example, Confirm sign up:

```html
<h2>Confirm your HelmetGuard account</h2>
<p><a href="{{ .ConfirmationURL }}">Confirm my email</a></p>
<p>Or enter this code in the app or on the website: <strong>{{ .Token }}</strong></p>
<p>An admin still has to approve your account before you can use HelmetGuard.</p>
```

Reset password:

```html
<h2>Reset your HelmetGuard password</h2>
<p><a href="{{ .ConfirmationURL }}">Choose a new password</a></p>
<p>Or enter this code with your new password in the app or on the website: <strong>{{ .Token }}</strong></p>
<p>If you didn't ask for this, ignore this email.</p>
```

## How it fits together

| Step | App | Website |
|---|---|---|
| Create an account | Email with a link; "I've confirmed my email: sign in" | `/verify` |
| Open the confirmation link | Opens the website's `/auth/callback`, then `/pending`; sign in on the app afterwards | Same |
| Sign in before confirming | Goes to the confirm screen, with "Send a new email" | Goes to `/verify` |
| Forgot password | "Forgot password?" on the sign-in screen | `/forgot-password` |
| Open the reset link | Opens the website's `/reset-password` page | Same |
| Change password while signed in | Not yet | Key icon next to sign out |

Password boxes on both have a show/hide button.
