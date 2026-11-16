# Supabase settings for sign-up and password-reset emails

When someone creates an account or forgets their password, Supabase emails them a link and a code. The link opens the website's `/auth/callback` page, which works in any browser and on any device, for example a phone's mail app. The code can be typed into the app or the website instead. A confirmed account then waits for an admin to approve it on the Users page.

## What is set (17 November 2026)

All in project `helmetguard-dev`, under **Authentication**:

| Setting | Where | Value |
|---|---|---|
| Site URL | URL Configuration | `https://helmetguard.vercel.app` (it was `http://localhost:3000`, which is why email links opened localhost) |
| Redirect URLs | URL Configuration | `https://helmetguard.vercel.app/**`, and `http://localhost:3000/**` for local testing |
| Confirm email | Sign In / Providers | On. Email codes are 8 digits and last one hour |
| Email sender | Emails → SMTP Settings | Custom SMTP through Gmail: host `smtp.gmail.com`, port `465`, sender name `HelmetGuard`, the project owner's Gmail address as sender and username, and a Gmail **app password** |
| Emails per hour | Rate Limits | 30 (Supabase's own sender allowed 2, which is why sign-ups failed with "email rate limit exceeded" on 16 November) |
| Confirm sign up, Reset password | Emails → Templates | A link and the `{{ .Token }}` code, below |

A test reset email sent from the live site on 17 November arrived in the inbox, not spam, with its link pointing at `https://helmetguard.vercel.app/auth/callback`.

## If emails stop arriving

Look at the auth logs (Supabase → Logs → Auth) for the failed request:

- **"Username and Password not accepted"** or **"Application-specific password required"** from Gmail: the password in SMTP Settings isn't a valid app password. App passwords need 2-Step Verification on the Google account. Make a new one at <https://myaccount.google.com/apppasswords>, paste it (without spaces) into SMTP Settings → Password, and wait for Supabase's success message. Changing the Google account's password also cancels its app passwords.
- **"email rate limit exceeded"**: more than 30 emails in an hour. Raise it under Rate Limits.

Gmail is meant for personal mail, so some messages may land in spam. For a public launch, switch the sender to a service such as Brevo or Resend; their dashboards show the host, port, username and password to paste into SMTP Settings.

## The templates

Confirm sign up, subject "Confirm your HelmetGuard account":

```html
<h2>Confirm your HelmetGuard account</h2>

<p>Open this link to confirm your email address. It works on any phone or computer.</p>
<p><a href="{{ .ConfirmationURL }}">Confirm my email</a></p>
<p>Or enter this code in the HelmetGuard app or on the website: <strong>{{ .Token }}</strong></p>
<p>The link and the code work for one hour. After you confirm, an admin approves your account before you can use HelmetGuard.</p>
<p>If you didn't create a HelmetGuard account, you can ignore this email.</p>
```

Reset password, subject "Reset your HelmetGuard password":

```html
<h2>Reset your HelmetGuard password</h2>

<p>Open this link to choose a new password. It works on any phone or computer.</p>
<p><a href="{{ .ConfirmationURL }}">Choose a new password</a></p>
<p>Or enter this code with your new password in the HelmetGuard app or on the website: <strong>{{ .Token }}</strong></p>
<p>The link and the code work for one hour.</p>
<p>If you didn't ask to reset your password, you can ignore this email. Your password stays the same.</p>
```

## How it fits together

| Step | App | Website |
|---|---|---|
| Create an account | Email with a link and a code; enter the code, or open the link and then tap "I've confirmed my email: sign in" | `/verify` |
| Open the confirmation link | Opens the website's `/auth/callback`, then `/pending`; sign in on the app afterwards | Same |
| Sign in before confirming | Goes to the confirm screen, with "Send a new email" | Goes to `/verify` |
| Forgot password | "Forgot password?" on the sign-in screen | `/forgot-password` |
| Open the reset link | Opens the website's `/reset-password` page | Same |
| Change password while signed in | Not yet | Key icon next to sign out |

Password boxes on both have a show/hide button.
