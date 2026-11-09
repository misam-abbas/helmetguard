# HelmetGuard

An offline-first Android app that detects motorcycle riders without helmets on the phone itself, captures evidence and reads number plates. It syncs to a free Supabase backend, and a Next.js dashboard lets reviewers confirm or reject each AI alert.

HelmetGuard is an academic prototype. It never issues fines: every record stays an unverified AI alert until a person reviews it, and no face is ever recognised.

## Repository

| Folder | What it holds |
|---|---|
| `android/` | Native Kotlin app. `vision/` is pure Kotlin logic with fast unit tests |
| `web/` | Next.js admin dashboard |
| `supabase/migrations/` | Database schema, Row Level Security, storage rules, analytics |
| `ml/` | Model training (needs your own footage) |
| `docs/` | Architecture and decisions |

## Run the checks

```powershell
cd web
npm install
npm test          # database security tests, on an embedded Postgres
npm run build
```

```powershell
cd android
.\gradlew.bat :vision:test
.\gradlew.bat :app:assembleDebug
```

## Run the website

1. Copy `web/.env.example` to `web/.env.local` and fill in your Supabase URL and publishable key.
2. In `web`, run `npm run dev` and open http://localhost:3000.

## Run the Android app

1. Open `android/` in Android Studio.
2. Add `SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY` to `android/local.properties`.
3. Put the trained models in `android/app/src/main/assets/models/`.
4. Connect your phone and press Run.

## Licence

AGPL-3.0, because the detection models are trained with Ultralytics YOLO26n. See `LICENSE`.
