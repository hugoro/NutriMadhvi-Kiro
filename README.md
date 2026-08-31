# NutriMadhvi

A native Android nutrition & health-coaching app built with **Kotlin + Jetpack Compose**. It combines a Gemini-powered chat/coach, meal-photo analysis, Samsung Health activity & body-composition integration, and an on-device food history — all persisted locally in a Room database.

**Highlights**

- 🍽️ **Meal-photo analysis** — snap a photo, get a macro/calorie breakdown (vision cascade over Gemini `3.6-flash` → `3.5-flash`, with macros enriched from your own logging history).
- 💬 **AI coach chat** — streaming replies, action proposals (log a meal, log activity, render a chart), voice mode (TTS + speech recognition), local-first orchestration so facts are measured, not guessed.
- 📊 **Dashboard & Análise** — daily macro bars, body-composition multi-line charts (weight / muscle / fat / water), energy balance.
- ⌚ **Samsung Health** — active calories, workouts, weight and body-composition via the Samsung Health Data SDK.
- 📁 **Export** — XLSX / HTML nutrition reports to Google Drive (SAF).
- 🌐 **Bilingual UI** — Portuguese (pt-PT) and Gujarati, with English fallback.

Architecture overview: see [`NutriMadhvi-Architecture.pdf`](NutriMadhvi-Architecture.pdf).

---

## Requirements

- [Android Studio](https://developer.android.com/studio) (latest stable)
- JDK 21 (the JBR bundled with Android Studio works)
- Android SDK: `compileSdk 36`, `minSdk 29`, `targetSdk 36`
- A **Gemini API key** — get one from [Google AI Studio](https://aistudio.google.com/apikey)
- (Optional) A Samsung device with Samsung Health for the activity / body-composition features

---

## Setup

1. Open the project in Android Studio (**Open** → choose this directory) and let it sync Gradle.
2. Create a `.env` file in the project root and set your Gemini key (see [`.env.example`](.env.example)):

   ```
   GEMINI_API_KEY=your_key_here
   ```

   The key is injected into `BuildConfig.GEMINI_API_KEY` at build time by the Secrets Gradle Plugin — it is **not** hardcoded and `.env` is git-ignored.

---

## How to test the app

There are two layers of tests: **fast JVM unit tests** (run on any machine, no device needed) and **manual end-to-end verification on a device/emulator**.

### 1. JVM unit tests (no device required)

The bulk of the logic — the chat orchestrator, intent router, model circuit breaker, meal-analysis gates, macro/nutrition parsing, i18n, and Compose screenshot tests (Robolectric + Roborazzi) — runs as plain JVM unit tests.

```bash
# from the project root
export JAVA_HOME=/path/to/jdk-21        # or Android Studio's bundled JBR
export ANDROID_HOME=$HOME/Library/Android/sdk

# run the full unit-test suite
./gradlew testDebugUnitTest

# run a single test class
./gradlew testDebugUnitTest --tests "com.example.data.chat.IntentRouterTest"

# regenerate Roborazzi screenshot baselines (Compose UI snapshots)
./gradlew recordRoborazziDebug
# verify against the recorded baselines
./gradlew verifyRoborazziDebug
```

The HTML report is written to `app/build/reports/tests/testDebugUnitTest/index.html`.

### 2. Build & install on a device or emulator

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=$HOME/Library/Android/sdk

# build a debug APK
./gradlew assembleDebug

# install on a connected device / running emulator
./gradlew installDebug
# (or: adb install -r app/build/outputs/apk/debug/app-debug.apk)
```

> **Note:** the debug build ships with a checked-in debug signing config so it installs without extra setup. If you hit a signing error, remove the line
> `signingConfig = signingConfigs.getByName("debugConfig")` from `app/build.gradle.kts`.

### 3. Instrumented tests (device / emulator required)

```bash
./gradlew connectedDebugAndroidTest
```

### 4. Manual smoke test (what a user would do)

Once the app is running on a device/emulator with a valid `GEMINI_API_KEY`:

1. **Chat** — open the chat tab, send a message (e.g. *"o que comi hoje?"*), confirm a streamed reply appears.
2. **Meal photo** — attach a food photo in chat, confirm a macro/calorie breakdown is proposed and can be logged.
3. **Dashboard** — check the daily macro bars and calorie totals update after logging.
4. **Análise** — open the analysis tab, confirm the body-composition and balance charts render.
5. **(Samsung device)** — grant Samsung Health permissions and confirm activity / body-composition data appears.

If the Gemini key is missing or invalid, the app surfaces an explicit *"API Key not configured"* message rather than failing silently.

---

## Project layout

```
app/src/main/java/com/example/
├── ui/              Jetpack Compose screens, ChatViewModel, components
├── data/
│   ├── chat/        ChatOrchestrator, IntentRouter, ModelCircuitBreaker
│   ├── meal/        MealAnalysisOrchestrator (vision cascade)
│   ├── remote/      Gemini repository, streaming client, parsers
│   ├── health/      Samsung Health integration
│   ├── local/       Room entities, DAOs, migrations (v30)
│   ├── nutrition/   INSA food DB + cluster enrichment
│   └── export/      XLSX / HTML report builders
app/src/test/        JVM unit + Robolectric/Roborazzi tests
app/src/androidTest/ Instrumented tests
scripts/             Dev utilities (env restore, INSA DB build, TTS debug)
```
