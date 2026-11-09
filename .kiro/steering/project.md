# NutriMadhvi — Kiro Steering

## Project overview
NutriMadhvi is a native Android nutrition app (Kotlin + Jetpack Compose) that uses multimodal AI to analyse meal photos, track activity via Samsung Health, and provide a conversational coach persona.

## Architecture
- **UI**: Jetpack Compose, single-Activity, bottom-nav with 6 tabs (Dashboard, Meals, Activity, Analysis, Performance, Chat)
- **Persistence**: Room database, currently schema v43+; append-only versioned snapshot tables
- **AI pipeline**: Gemini (vision + chat), Groq (fast text), Bitdeer/Qwen3-VL-32B (vision fallback); tiered cascade with circuit breaker
- **Activity data**: Samsung Health Data SDK (AAR in `app/libs/`), not simulated
- **Local-first orchestration**: IntentRouter → DataFetcher (parallel) → LLM synthesis; model never invents facts it can read from DB

## Key conventions
- All UI strings use `tr()` with inline `pt`/`gu` maps; English is the silent fallback
- Room migrations are append-only; never use `fallbackToDestructiveMigration` in production
- `NAV_ORDER` constants drive both the Pager and the BottomBar; never use enum ordinal for navigation
- Secrets live in Android Keystore / `.env` (never committed); use `restore-env.sh` to reseed
- Version bumps are automatic via `BumpVersionCodeTask` (runs after assemble, so on-disk APK is always one version behind the just-printed number)

## What Kiro should NOT do
- Do not modify `app/libs/` — Samsung Health AAR is a binary dependency
- Do not remove or rename Room entity fields without a migration
- Do not touch `food_images/` paths or `food_entries`/`activities` table rows
- Do not commit `.env`, `debug.keystore`, `google-services.json`, or any `*.db` files
- Do not add `.kiro/` to `.gitignore`

## Coding standards
- Kotlin only; no Java new files
- Compose for all UI; no XML layouts
- One-line comments only when the WHY is non-obvious
- No backwards-compatibility shims; if something is unused, delete it
