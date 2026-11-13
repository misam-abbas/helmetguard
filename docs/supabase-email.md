# Supabase settings for email codes and password reset

The app and the website ask for a 6-digit code when someone creates an account or resets a password. Supabase sends those emails, and it needs four settings changed in the dashboard of project `helmetguard-dev` before codes arrive. Until then, sign-up works without a code (the account still waits for admin approval) and a reset email contains only a link, which also works: on the website, open it in the browser where you asked for the reset.

Do them in this order, because turning on email confirmation before email sending works would stop new people from signing up.

## 1. Your own email sender (Authentication → Emails → SMTP Settings)

Supabase's built-in sender only delivers to the project team's own addresses and only a few emails an hour. Turn on **Custom SMTP** and fill in a provider's details, for example:

- **Gmail:** host `smtp.gmail.com`, port `465`, username your Gmail address, password an **App password** (Google Account → Security → 2-Step Verification → App passwords), sender your Gmail address.
- **Resend or Brevo:** both have free plans; their dashboards show the host, port, username and password to paste in.

Send yourself a test (for example "Forgot password?" on the website) before going on.

## 2. Addresses (Authentication → URL Configuration)

- **Site URL:** `https://helmetguard.vercel.app`
- **Redirect URLs:** add `https://helmetguard.vercel.app/auth/confirm`, `https://helmetguard.vercel.app/auth/callback`, and for local testing `http://localhost:3000/**`

## 3. Email templates (Authentication → Emails → Templates)

Put the code in **Confirm signup** and **Reset password**. Keep the link too, for people who prefer it. For example, Confirm signup:

```html
<h2>Confirm your HelmetGuard account</h2>
<p>Your code is <strong>{{ .Token }}</strong>. Enter it in the app or on the website.</p>
<p>Or open this link in the same browser: <a href="{{ .ConfirmationURL }}">Confirm my email</a></p>
<p>An admin still has to approve your account before you can use HelmetGuard.</p>
```

Reset password:

```html
<h2>Reset your HelmetGuard password</h2>
<p>Your code is <strong>{{ .Token }}</strong>. Enter it with your new password in the app or on the website.</p>
<p>Or open this link: <a href="{{ .ConfirmationURL }}">Choose a new password</a></p>
<p>If you didn't ask for this, ignore this email.</p>
```

## 4. Turn on email confirmation (Authentication → Sign In / Providers → Email)

Turn on **Confirm email**. From now on a new account must enter its code before an admin can approve it.

## How it fits together

| Step | App | Website |
|---|---|---|
| Create an account | Code screen after sign-up | `/verify` |
| Confirmation link | Opens the website, then sign in on the app | `/auth/confirm` or `/auth/callback` |
| Forgot password | "Forgot password?" on the sign-in screen | `/forgot-password` |
| Reset link | Opens the website's `/reset-password` page | Same |
| Change password while signed in | Not yet | Key icon next to sign out |
