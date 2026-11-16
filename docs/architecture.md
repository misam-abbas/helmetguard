# Architecture and decisions

The full plan is the "HelmetGuard – Technical Planning Document" (7 Nov 2026). This file records the decisions and any place the build differs from it.

## System

The phone does all detection, verification and storage by itself. Supabase receives only finished records, and the dashboard reads them under each reviewer's own permissions. No paid inference server is involved. The dashboard can also analyse a video in the reviewer's browser with the same models; the video never leaves their computer.

## Decisions (approved 7 Nov 2026)

| # | Decision | Choice |
|---|---|---|
| 1 | Licence | Public repository, AGPL-3.0, Ultralytics YOLO (YOLOv8n first, YOLO11n from the second models; see `ml/README.md`) |
| 2 | Helmet rule | Every rider including pillion; driver vs pillion recorded |
| 3 | Supabase | Two projects: `helmetguard-dev`, `helmetguard-prod` |
| 4 | Email confirmation | Changed 10 Nov 2026 at the owner's request: a code is emailed at sign-up, then an admin approves the account. Needs the Supabase settings in `docs/supabase-email.md`; until then sign-up works as before |
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

| Source | https://github.com/misam-abbas/helmetguard |

`helmetguard-prod` (decision 3) is not created yet; the plan makes that Phase 19. Pushing does not redeploy the dashboard, because the repository isn't connected in Vercel's project settings (that needs a GitHub login connection on the Vercel account). Instead, a deployment is created for a pushed commit through Vercel's API with a `gitSource` of `misam-abbas/helmetguard`; the repository is public, so Vercel can clone it without the connection, and the build copies the models from `android/`. Vercel's Root Directory is set to `web`. A deployment uploaded as files instead has only the `web` folder, so its build downloads the models from the GitHub commit pinned in `web/models.lock.json` and checks their SHA-256; run `node web/scripts/lock-models.mjs` after committing new models.

## Where the build differs from the plan

- **`app_settings` table added.** The plan says six tables, but the admin Settings page needs somewhere to keep retention and grace periods.
- **`profiles.email` added.** An admin needs to see who a pending account belongs to, and `auth.users` is not readable through RLS.
- **Trigger functions live in the `private` schema**, not `public`, so they can never be called through the API.
- **Hand-written UI primitives** in `web/src/components/ui.tsx`, in the style of shadcn/ui, instead of running its installer. Replace them if you add many more components.
- **TypeScript 5.9 and ESLint 9** rather than the newest majors, because of compatibility with Next.js 16's tooling.
- **Tracker matching is greedy best-overlap-first** rather than the Hungarian algorithm. It is simpler and is enough for a tripod view. Revisit it if Phase 9 measurements show ID switches.
- **No number plates (owner's decision, 10 Nov 2026).** Plates are not detected, read or stored. The evidence for a rider without a helmet is a picture of the whole motorcycle with its riders, plus the full frame. Duplicate prevention uses position, time and colour only. The database's plate columns stay, unused.
- **Stage 1 sees 640 × 640 from the second models** (the plan says 416), because distant and half-visible bikes were missed at 416. The head model gets the bike crop with an 8% margin on every side, as in training.
- **Position-only duplicate matching stops after 2 seconds**, because the next bike arrives in the same lane.
