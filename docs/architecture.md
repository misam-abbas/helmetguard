# Architecture and decisions

The full plan is the "HelmetGuard – Technical Planning Document" (7 Nov 2026). This file records the decisions and any place the build differs from it.

## System

The phone does all detection, verification, OCR and storage by itself. Supabase receives only finished records, and the dashboard reads them under each reviewer's own permissions. No paid inference server is involved.

## Decisions (approved 7 Nov 2026)

| # | Decision | Choice |
|---|---|---|
| 1 | Licence | Public repository, AGPL-3.0, Ultralytics YOLO26n |
| 2 | Helmet rule | Every rider including pillion; driver vs pillion recorded |
| 3 | Supabase | Two projects: `helmetguard-dev`, `helmetguard-prod` |
| 4 | Email confirmation | Off; admin approval gates every account |
| 5 | Test phone | Google Pixel 7a, 8 GB, Android 17 |
| 6 | Recording permission | **Still needed before filming** |
| 7 | Disabled account's unsynced data | Kept encrypted until an admin decides |
| 8 | Retention | Unreviewed and rejected deleted after 90 days |
| 9 | Location | Typed label per session; GPS off by default |
| 10 | App ID | `com.misamabbas.helmetguard` |

## Live environment

| Piece | Where |
|---|---|
| Dashboard | https://helmetguard.vercel.app (Vercel project `helmetguard`) |
| Backend | Supabase project `helmetguard-dev`, ref `aqywebxmkbgxvhjlcvhl`, Mumbai |

`helmetguard-prod` (decision 3) is not created yet; the plan makes that Phase 19. The dashboard was deployed by direct upload because the GitHub repository does not exist yet, so it does not redeploy on push until the repository is connected. When connecting it, set Vercel's Root Directory to `web`.

## Where the build differs from the plan

- **`app_settings` table added.** The plan says six tables, but the admin Settings page needs somewhere to keep retention and grace periods.
- **`profiles.email` added.** An admin needs to see who a pending account belongs to, and `auth.users` is not readable through RLS.
- **Trigger functions live in the `private` schema**, not `public`, so they can never be called through the API.
- **Hand-written UI primitives** in `web/src/components/ui.tsx`, in the style of shadcn/ui, instead of running its installer. Replace them if you add many more components.
- **TypeScript 5.9 and ESLint 9** rather than the newest majors, because of compatibility with Next.js 16's tooling.
- **Tracker matching is greedy best-overlap-first** rather than the Hungarian algorithm. It is simpler and is enough for a tripod view. Revisit it if Phase 9 measurements show ID switches.
- **A human's plate differs by more than one character:** two clearly different plates never merge in duplicate prevention, even if position or colour agree. The plan listed these as alternatives; this avoids merging two real bikes.
- **Position-only duplicate matching stops after 2 seconds**, because the next bike arrives in the same lane.
