# Performance Tab Enhancement — Implementation Plan

Increment 1 (per @hermes 2026-10-19). Branch: checkpoint/20261005-chat-perf. DB v38 → v39.

## Ground rules
- Additive-only Room migration; `adb install -r`; never destructive schema/FS changes
  (user standing rule — no data wipe).
- Local deterministic code wherever possible. LLM only for structured extraction/reporting.
- Model routing: routine structured work → `gemini-3.5-flash-lite` (primary) with
  `gemini-3.6-flash` compatibility fallback; `gemini-3.8-flash` NOT added (no quality-test
  justification exists in-repo); Gemini 4 Argon NOT in any routine path — only a reserved
  interface constant for future high-reasoning escalation.
- No camera exercise monitoring, no uncurated video/YouTube links in this increment.

## Commit 1 — docs (this file)

## Commit 2 — DB v38→v39: body_plan_checkpoints + migration test
- `data/local/BodyPlanCheckpointEntity.kt` (new table `body_plan_checkpoints`):
  id, checkpointAtMs (indexed), snapshotId?, bodyCompRowId?, phase?, prioritiesJson?,
  tacticsJson?, tacticSource?, deltaSummary?, coachReport?, reportGeneratedAtMs?
- `data/local/BodyPlanCheckpointDao.kt`: insert / observeAll / latest / recent(limit) / deleteById.
- `ChatDatabase.kt`: version 39, DAO property, `MIGRATION_38_39` (CREATE TABLE IF NOT EXISTS +
  index), registered after MIGRATION_37_38. Also `silhouetteNormalization TEXT` on
  `performance_snapshots` (nullable, via addColumnIfMissing) so each snapshot records which
  normalization convention produced its ratios (`image_height` legacy | `pose_person_height`).
- `DatabaseMigrationTest.testMigration38To39AddsBodyPlanCheckpointsAndPreservesData`:
  pre-loads v38 performance_snapshots (incl. silhouette row with coachReport + photo paths),
  performance_goals, food_entries rows; asserts all survive byte-identical, new table empty,
  new column NULL on legacy, user_version=39.

## Commit 3 — pose-normalized silhouette measurements
- New `ui/SilhouetteZoneMath.kt` — pure, unit-testable geometry:
  `zonesForFrontal/Lateral(landmarks?, imgH)` → per-zone (startRow,endRow) + personHeightPx.
  Pose path: shoulderMid/hipMid/kneeMid anchors; waist = shoulderY+0.55·torso etc.
  Fallback: the current fixed fractions (behavior unchanged when pose is absent).
  Normalizer: personHeightPx (nose→ankleMid vertical span) when plausible (0.3h..1.0h),
  else image height (legacy convention).
- `SilhouetteAnalyzer.analyze(bitmap, isFrontal, landmarks?)` overload; old 2-arg signature
  delegates with null landmarks (zero regression for other call sites).
- `SilhouetteAssessmentScreen` passes the latest pose landmarks (already detected in-stream
  by the full-body guard).
- Unit tests `SilhouetteZoneMathTest`: pose-anchored band placement, fallback to fixed
  fractions, normalizer selection, ratio invariance to image scale (same body, 2x zoom ⇒
  same pose-normalized ratio).

## Commit 4 — deterministic body-plan engine (pure + tests)
- `data/performance/BodyPlanEngine.kt`:
  - `BodyPhase` enum BULK/CUT/RECOMP/MAINTAIN; `classifyPhase(fatPctHistory, muscleKgHistory, goalText)`
    — trend rules (Δfat > +0.5pp & Δmuscle < 0 → CUT; Δmuscle > +0.3kg & Δfat ≤ +0.5pp → BULK;
    both → RECOMP; goal keywords as tie-breaker/override; null when no composition data).
  - `classifyTissue(zone, bodyComp)` — waist/belly → fat-prone; shoulder/thigh/chest →
    muscle-prone; UNKNOWN when no composition data.
  - `rankPriorities(snap, targets)` — top-3 by |gap| in pp; SAFETY GATE: direction REDUCE only
    for fat-prone zones (mirrors existing PerformanceCoachBuilder anti-muscle-loss rule);
    muscle zones can only GROW/HOLD.
  - `defaultTactics(phase, priorities, proteinTargetG)` — deterministic safe fallback tactic
    list (protein target, strength 2-3×/wk progressive, steps 7-9k, sleep 7-9h) used when the
    LLM fails.
  - `deltasBetween(latest, previous)` → deltaSummary string.
- `BodyPlanEngineTest`: phase matrix, safety gate (never REDUCE muscle zone), priority
  ordering, fallback tactics determinism, delta summary.

## Commit 5 — checkpoint persistence + ViewModel + low-cost LLM coaching
- `data/ai/ModelRoles.kt`: ROUTINE_EXTRACTION_MODEL = "gemini-3.5-flash-lite",
  ROUTINE_FALLBACK_MODEL = "gemini-3.6-flash", HIGH_REASONING_ESCALATION_MODEL: String? = null
  (reserved interface for future high-reasoning escalation — NOT wired to any runtime path).
- `GeminiRepository.generateBodyPlanContent(apiKey, request, language, modelChain)` —
  non-streaming JSON call with per-model retry/backoff (same pattern as
  generateContentWithFallback), chain from ModelRoles.
- `ChatViewModel`:
  - `bodyPlanCheckpoints` StateFlow; `saveBodyPlanCheckpoint(snapshot, bodyCompRows)` —
    computes phase/priorities/deltas synchronously (deterministic), persists, then async
    LLM tactics (flash-lite) + checkpoint report; on failure → deterministic fallback tactics
    (tacticSource="deterministic_fallback") + report stays null (retry available).
  - `requestCheckpointReport(id)` — explicit retry (no infinite spinner).
- JSON schemas: prioritiesJson [{zone, direction, gapPp, tissue}], tacticsJson
  [{category, text, target, windowDays}] — clamped/validated on parse (safe by default).

## Commit 6 — UI: Overview / Plano / Progresso + report retry
- `PerformanceTab`: internal segmented navigation (Overview | Plano | Progresso):
  - Overview: existing content (silhouette gap card, coach report, evolution, measures, goal,
    history) + report retry fix: when coachReport==null with silhouette data, show
    "Gerar novamente" (viewModel.requestCoachReport) instead of the infinite spinner.
  - Plano: latest checkpoint — phase badge, top-3 priority zones (with tissue + direction),
    tactics list with source label (LLM vs fallback), "Gerar novamente" for tactics/report.
  - Progresso: checkpoint timeline (date, phase, deltaSummary, per-checkpoint report + retry),
    zone progress current→target across checkpoints.
- i18n: new keys in I18n.kt (pt/en/gu) — no hard-coded strings in composables.

## Commit 7 — build, test, install, verify
- `./gradlew :app:testDebugUnitTest` (full unit suite incl. new tests).
- `./gradlew :app:assembleDebug`; emulator + `adb install -r`; data-preservation evidence:
  DB pull before/after install (row counts + checksums of performance_snapshots,
  performance_goals, food_entries) + migration user_version 38→39 on device.
- Attempt `ksp { arg("exportSchema", "true") }` to emit schemas/39.json; keep only if the
  Room schema validation passes cleanly (revert if pre-existing drift breaks the build).

## Out of scope (explicit, per instruction)
- Camera-based exercise form monitoring (pose math exists but no new capture flow).
- Exercise/video guidance content (no new cards, no YouTube links).
- Body-region navigation deepening beyond the existing zone legend.
- Any Gemini 4 Argon / 3.8-flash wiring.
