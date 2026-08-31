package com.example.data.health

import android.app.Activity
import android.content.Context
import com.example.data.local.ActivityType
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.error.HealthDataException
import com.samsung.android.sdk.health.data.error.ResolvablePlatformException
import com.samsung.android.sdk.health.data.helper.aggregate
import com.samsung.android.sdk.health.data.helper.read
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.request.LocalTimeFilter
import com.samsung.android.sdk.health.data.request.LocalTimeGroup
import com.samsung.android.sdk.health.data.request.LocalTimeGroupUnit
import com.samsung.android.sdk.health.data.request.Ordering
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class ShWorkout(
    val recordId: String,
    val type: ActivityType,
    /**
     * Human-readable label of the specific Samsung Health
     * `PredefinedExerciseType` (e.g. `"Pilates"`, `"Weight machine"`,
     * `"Track running"`). Preserves fidelity to the modality the user
     * actually did — Samsung's ~110-value enum collapses onto our 7-bucket
     * [ActivityType] for aggregates, but this string carries the fine
     * label all the way to UI, LLM prompt, and XLSX export. Empty string
     * for UNDEFINED / null SDK enum; renderers fall back to
     * [ActivityType.displayKey] via [displayLabel] in that case.
     */
    val sourceLabel: String,
    val startTime: Instant,
    val endTime: Instant,
    val title: String?,
    val notes: String?,
    val activeKcal: Int?
)

/**
 * Priority for user-visible workout labels:
 *  1. [ShWorkout.title]        — user's own custom title set inside Samsung Health
 *  2. [ShWorkout.sourceLabel]  — the fine `PredefinedExerciseType` name ("Pilates", …)
 *  3. `type.displayKey`        — coarse bucket fallback ("Yoga / Stretch")
 *
 * `title` outranks `sourceLabel` because a user who explicitly renamed the session
 * chose that label deliberately; the SDK's fine label is the correct default only
 * when they didn't. Aggregate widgets (pie chart legend, ActivitySummaryTable rows)
 * intentionally keep using `type.displayKey` — see [[samsung-health-exercise-type-mapping]].
 */
val ShWorkout.displayLabel: String
    get() = title?.takeIf { it.isNotBlank() }
        ?: sourceLabel.takeIf { it.isNotBlank() }
        ?: type.displayKey

/**
 * Single-day rollup of Samsung Health's "Activity" summary — the same numbers shown in the
 * Samsung Health app under "Tempo ativo / Calorias de atividade / Distância". Read directly
 * from [DataType.ActivitySummaryType] so the dashboard mirrors the SH UI 1:1, instead of
 * reconstructing values from steps × custom factors.
 */
data class ShDailyActivity(
    val date: LocalDate,
    val activeTime: Duration,
    val activeCalories: Float,
    val totalCalories: Float,   // BMR + active; reserved for future "total burned" UI
    val distanceMeters: Float
) {
    companion object {
        fun zero(date: LocalDate) = ShDailyActivity(date, Duration.ZERO, 0f, 0f, 0f)
    }
}

/**
 * Source of a BMR/TMB (Basal Metabolic Rate / Taxa Metabólica Basal) value shown on the
 * dashboard. Samsung Health is preferred when available; the manual override from the
 * health profile is used as fallback (or when SH isn't installed / the BODY_COMPOSITION
 * permission was denied).
 */
enum class BmrSource { SAMSUNG_HEALTH, MANUAL }

data class BmrSnapshot(
    val value: Int,            // kcal/day — Samsung Health stores this as Field<Integer>
    val source: BmrSource,
    val timestamp: Instant?    // start time of the SH measurement, or save-time of the manual entry; null only for legacy manual values saved before this field existed
)

/**
 * Snapshot of a single body-weight reading from Samsung Health. Only
 * Samsung-sourced for now — there's no "manual" weight path in the app
 * yet, but [BmrSource]-style discriminator can be added if we add one.
 *
 * @property kg Weight in kilograms; Samsung exposes this as Field<Float>
 *              so the precision matches what the user sees on their scale.
 * @property timestamp Start time of the SH reading. Useful to surface
 *              "actualizado há 3 dias" in the UI when the latest weigh-in
 *              is stale.
 */
data class WeightSnapshot(
    val kg: Float,
    val timestamp: Instant?
)

/**
 * All Samsung Health BodyComposition fields the Medisana via VitaDock+
 * was confirmed (2026-06-26 probe on Hugo's Pixel) to populate, plus
 * height which is reported on every weigh-in. Fields the scale didn't
 * write come back null — the caller decides whether to derive (e.g.
 * massa magra = weight × (1 − bodyFatPct/100) when both are present)
 * or simply hide that row.
 *
 * **UNITS — critical (2026-08-19 probe):** the Medisana/VitaDock+ scale
 * writes **percentages**, not kilograms, into the SDK's `MUSCLE_MASS`
 * and `TOTAL_BODY_WATER` fields (probe read MUSCLE_MASS=34.4,
 * TOTAL_BODY_WATER=58.8 while the scale UI showed 34.4% / 58.8%). The
 * mass-in-kg fields (`BODY_FAT_MASS`, `SKELETAL_MUSCLE_MASS`,
 * `FAT_FREE_MASS`) come back null, so the only way to get kg is
 * `weight × pct / 100`. [readLatestBodyCompositionAll] does that
 * conversion at read time, so [muscleMassKg] / [totalBodyWaterKg] here
 * (and the columns of the same name in body_composition_snapshots) hold
 * **real kilograms** — the display layer derives the % back from
 * kg/weight×100 when it wants to show both. [bodyFatPct] stays a genuine
 * percentage (the SDK's BODY_FAT is already a %, matching the scale).
 *
 * Source of truth is always Samsung Health: a fresh instance is built
 * on each successful read. The local body_composition_snapshots table
 * caches it for offline + chat history use, but never gets edited
 * directly — every value here came from a real BodyComposition entry
 * in S-Health.
 */
data class BodyCompositionReading(
    val timestamp: Instant?,
    val weightKg: Float,
    val heightCm: Float?,
    val bodyFatPct: Float?,
    /** Muscle mass in **kg**, derived from the scale's MUSCLE_MASS %. */
    val muscleMassKg: Float?,
    /** Total body water in **kg**, derived from the scale's TOTAL_BODY_WATER %. */
    val totalBodyWaterKg: Float?,
    val bmiValue: Float?
)

class SamsungHealthRepository(private val context: Context) {
    private val store by lazy { HealthDataService.getStore(context) }

    enum class Status { AVAILABLE, NEEDS_PERMISSION, SAMSUNG_HEALTH_MISSING_OR_OUTDATED, UNSUPPORTED_OS, ERROR }

    companion object {
        // Required for AVAILABLE state. DO NOT add new entries here without considering
        // the re-grant flow for existing users — adding a permission would force every
        // installed user back to NEEDS_PERMISSION until they re-confirm in the SH dialog.
        val SH_PERMISSIONS = setOf(
            Permission.of(DataTypes.STEPS, AccessType.READ),
            Permission.of(DataTypes.EXERCISE, AccessType.READ),
            Permission.of(DataTypes.ACTIVITY_SUMMARY, AccessType.READ),
        )

        // Best-effort additions. Requested in the same dialog as SH_PERMISSIONS so a
        // fresh install gets one combined prompt, but the AVAILABLE check ignores
        // these — denying them just makes the corresponding read return null.
        val SH_OPTIONAL_PERMISSIONS = setOf(
            Permission.of(DataTypes.BODY_COMPOSITION, AccessType.READ),
        )

        val SH_ALL_PERMISSIONS = SH_PERMISSIONS + SH_OPTIONAL_PERMISSIONS
    }

    suspend fun status(): Status {
        return try {
            val granted = store.getGrantedPermissions(SH_PERMISSIONS)
            if (granted.containsAll(SH_PERMISSIONS)) {
                Status.AVAILABLE
            } else {
                Status.NEEDS_PERMISSION
            }
        } catch (e: ResolvablePlatformException) {
            Status.SAMSUNG_HEALTH_MISSING_OR_OUTDATED
        } catch (e: HealthDataException) {
            Status.ERROR
        } catch (e: Exception) {
            Status.ERROR
        }
    }

    suspend fun requestPermissions(activity: Activity): Boolean {
        return try {
            // Ask for the optional set in the same dialog so users granting fresh see
            // BODY_COMPOSITION alongside the required ones — but success is decided
            // only by SH_PERMISSIONS, so denying BMR doesn't kill the dashboard.
            val granted = store.requestPermissions(SH_ALL_PERMISSIONS, activity)
            granted.containsAll(SH_PERMISSIONS)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Returns true if any of the optional permissions ([SH_OPTIONAL_PERMISSIONS]) is
     * NOT currently granted. Used by the dashboard's manual-refresh button: when the
     * required perms are already granted (so the user upgraded from a build that didn't
     * ask for BMR yet), the SH permission dialog never re-opens on its own — Samsung
     * Health only lists toggles for permissions the app has *ever* asked for. Carrying
     * this check lets the refresh button trigger a one-shot top-up request.
     */
    suspend fun hasMissingOptionalPermissions(): Boolean {
        return try {
            val granted = store.getGrantedPermissions(SH_OPTIONAL_PERMISSIONS)
            !granted.containsAll(SH_OPTIONAL_PERMISSIONS)
        } catch (e: Exception) {
            // If we can't check (SH offline / not installed), don't claim something is
            // missing — the caller will silently skip and try again next refresh.
            false
        }
    }

    /**
     * Reads daily step totals from Samsung Health between [start] and [end] (inclusive of the
     * day for [start], exclusive end side handled by the SDK's LocalTimeFilter).
     *
     * Uses [DataType.StepsType.TOTAL] aggregated with DAILY grouping. The SDK returns one
     * [com.samsung.android.sdk.health.data.data.AggregatedData] entry per day; we key the
     * map by `startLocalDateTime.toLocalDate()` since the device's local zone is the natural
     * reporting unit for step totals.
     */
    suspend fun readDailySteps(start: Instant, end: Instant): Map<LocalDate, Long> {
        return try {
            val zone = ZoneId.systemDefault()
            val startLocal = LocalDateTime.ofInstant(start, zone)
            val endLocal = LocalDateTime.ofInstant(end, zone)
            val filter = LocalTimeFilter.of(startLocal, endLocal)
            val response = store.aggregate(DataType.StepsType.TOTAL) {
                setLocalTimeFilterWithGroup(filter, LocalTimeGroup.of(LocalTimeGroupUnit.DAILY, 1))
            }
            response.dataList.associate { bucket ->
                bucket.startTime.atZone(zone).toLocalDate() to (bucket.value ?: 0L)
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * Reads daily ActivitySummary rollups (active time, active calories, total calories,
     * distance) directly from Samsung Health between [start] and [end]. These are the
     * exact numbers shown on the Samsung Health home/Activity screen — no custom formulas.
     *
     * Each of the four aggregates is a separate `store.aggregate` call but they're issued
     * concurrently via [coroutineScope]+[async]. Days the device didn't track collapse to
     * [ShDailyActivity.zero] (matches what the SH UI shows for those days: blanks).
     */
    suspend fun readDailyActivitySummary(start: Instant, end: Instant): Map<LocalDate, ShDailyActivity> {
        return try {
            val zone = ZoneId.systemDefault()
            val startLocal = LocalDateTime.ofInstant(start, zone)
            val endLocal = LocalDateTime.ofInstant(end, zone)
            val filter = LocalTimeFilter.of(startLocal, endLocal)
            val daily = LocalTimeGroup.of(LocalTimeGroupUnit.DAILY, 1)

            coroutineScope {
                val activeTimeDef = async {
                    store.aggregate(DataType.ActivitySummaryType.TOTAL_ACTIVE_TIME) {
                        setLocalTimeFilterWithGroup(filter, daily)
                    }
                }
                val activeKcalDef = async {
                    store.aggregate(DataType.ActivitySummaryType.TOTAL_ACTIVE_CALORIES_BURNED) {
                        setLocalTimeFilterWithGroup(filter, daily)
                    }
                }
                val totalKcalDef = async {
                    store.aggregate(DataType.ActivitySummaryType.TOTAL_CALORIES_BURNED) {
                        setLocalTimeFilterWithGroup(filter, daily)
                    }
                }
                val distanceDef = async {
                    store.aggregate(DataType.ActivitySummaryType.TOTAL_DISTANCE) {
                        setLocalTimeFilterWithGroup(filter, daily)
                    }
                }

                val activeTimeByDate = activeTimeDef.await().dataList.associate {
                    it.startTime.atZone(zone).toLocalDate() to (it.value ?: Duration.ZERO)
                }
                val activeKcalByDate = activeKcalDef.await().dataList.associate {
                    it.startTime.atZone(zone).toLocalDate() to (it.value ?: 0f)
                }
                val totalKcalByDate = totalKcalDef.await().dataList.associate {
                    it.startTime.atZone(zone).toLocalDate() to (it.value ?: 0f)
                }
                val distanceByDate = distanceDef.await().dataList.associate {
                    it.startTime.atZone(zone).toLocalDate() to (it.value ?: 0f)
                }

                val allDates =
                    activeTimeByDate.keys + activeKcalByDate.keys + totalKcalByDate.keys + distanceByDate.keys
                allDates.associateWith { d ->
                    ShDailyActivity(
                        date = d,
                        activeTime = activeTimeByDate[d] ?: Duration.ZERO,
                        activeCalories = activeKcalByDate[d] ?: 0f,
                        totalCalories = totalKcalByDate[d] ?: 0f,
                        distanceMeters = distanceByDate[d] ?: 0f
                    )
                }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * Reads exercise sessions (workouts) from Samsung Health between [start] and [end].
     *
     * Each Exercise data point can hold a list of [ExerciseSession] entries plus the dominant
     * [PredefinedExerciseType] for the point. Sessions don't expose calories per-session
     * cleanly here (the parent point's TOTAL_CALORIES aggregate is per-bucket, not per-session),
     * so we report null for activeKcal on a session-level basis. Mapping for [ActivityType]
     * matches the in-app enum.
     */
    suspend fun readWorkouts(start: Instant, end: Instant): List<ShWorkout> {
        return try {
            val zone = ZoneId.systemDefault()
            val startLocal = LocalDateTime.ofInstant(start, zone)
            val endLocal = LocalDateTime.ofInstant(end, zone)
            val filter = LocalTimeFilter.of(startLocal, endLocal)
            val response = store.read(DataTypes.EXERCISE) {
                setLocalTimeFilter(filter)
            }
            // 2026-08-16 — removed two per-point diagnostic loops here (a
            // type-tally pass + a "TEMP DIAGNOSTIC" per-session Log.i over every
            // today-dated point). Both iterated the full 30-day dataList on
            // every sync and ran on the read path — a measurable contributor to
            // the startup freeze. See [[log-architecture-perf-vs-signal]].
            response.dataList.flatMap { point ->
                val pointType = point.getValueOrDefault(
                    DataType.ExerciseType.EXERCISE_TYPE,
                    DataType.ExerciseType.PredefinedExerciseType.UNDEFINED
                )
                val customTitle: String? = point.getValue(DataType.ExerciseType.CUSTOM_TITLE)
                val sessions = point.getValueOrDefault(
                    DataType.ExerciseType.SESSIONS,
                    emptyList()
                )
                if (sessions.isEmpty()) {
                    listOf(
                        ShWorkout(
                            recordId = point.uid,
                            type = ExerciseTypeLabels.bucket(pointType?.name),
                            sourceLabel = ExerciseTypeLabels.pretty(pointType?.name),
                            startTime = point.startTime,
                            endTime = point.endTime ?: point.startTime,
                            title = customTitle,
                            notes = null,
                            activeKcal = null
                        )
                    )
                } else {
                    sessions.map { session ->
                        ShWorkout(
                            recordId = "${point.uid}#${session.startTime.epochSecond}",
                            type = ExerciseTypeLabels.bucket(session.exerciseType?.name),
                            sourceLabel = ExerciseTypeLabels.pretty(session.exerciseType?.name),
                            startTime = session.startTime,
                            endTime = session.endTime,
                            title = session.customTitle ?: customTitle,
                            notes = session.comment,
                            activeKcal = session.calories.toInt()
                        )
                    }
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Reads the most recent BodyComposition entry from Samsung Health and returns its
     * BASAL_METABOLIC_RATE field (kcal/day, integer per the SDK type).
     *
     * BodyComposition is non-aggregable (Readable, DualTimeBuilder), so we read points
     * directly with descending order + limit 1 to grab the latest measurement. Window
     * is the last 365 days — body composition entries are sparse (per weigh-in) and
     * 1 year is plenty without paging.
     *
     * Returns null if BODY_COMPOSITION wasn't granted (the read throws and we catch),
     * if there are no entries, or if the latest entry has no BMR field populated.
     */
    suspend fun readLatestBodyCompositionBmr(): BmrSnapshot? {
        return try {
            val zone = ZoneId.systemDefault()
            val endLocal = LocalDateTime.now(zone)
            val startLocal = endLocal.minusDays(365)
            val filter = LocalTimeFilter.of(startLocal, endLocal)

            val response = store.read(DataTypes.BODY_COMPOSITION) {
                setLocalTimeFilter(filter)
                setOrdering(Ordering.DESC)
                setLimit(1)
            }
            val point = response.dataList.firstOrNull() ?: return null
            val bmrKcal: Int = point.getValue(DataType.BodyCompositionType.BASAL_METABOLIC_RATE)
                ?: return null
            BmrSnapshot(
                value = bmrKcal,
                source = BmrSource.SAMSUNG_HEALTH,
                timestamp = point.startTime
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Snapshot of the most recent body weight reading from Samsung Health.
     * Mirrors [readLatestBodyCompositionBmr] — same data type, same
     * permission (BODY_COMPOSITION), same 365-day descending window.
     *
     * Returns null when the permission wasn't granted, when no
     * body-composition entries exist in the last year, or when the latest
     * entry has no [DataType.BodyCompositionType.WEIGHT] field populated
     * (some Samsung scales/manual entries skip individual fields).
     *
     * Used by the Reports tab (2026-06-26) to surface "Peso actual" as a
     * read-only KPI alongside the macros table. The lightweight DTO keeps
     * the timestamp around so the UI can label staleness when the latest
     * weigh-in is days old.
     */
    suspend fun readLatestBodyCompositionWeight(): WeightSnapshot? {
        return try {
            val zone = ZoneId.systemDefault()
            val endLocal = LocalDateTime.now(zone)
            val startLocal = endLocal.minusDays(365)
            val filter = LocalTimeFilter.of(startLocal, endLocal)

            val response = store.read(DataTypes.BODY_COMPOSITION) {
                setLocalTimeFilter(filter)
                setOrdering(Ordering.DESC)
                setLimit(1)
            }
            val point = response.dataList.firstOrNull() ?: return null
            val weightKg: Float = point.getValue(DataType.BodyCompositionType.WEIGHT)
                ?: return null
            WeightSnapshot(
                kg = weightKg,
                timestamp = point.startTime
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Reads the latest BodyComposition entry from Samsung Health and
     * returns every Medisana-validated field at once. Used by the
     * sync path to feed a single insert into
     * [com.example.data.local.BodyCompositionSnapshotEntity], where
     * weight + body fat + muscle + water + BMI + height all share one row
     * keyed by the original measurement timestamp.
     *
     * Returns null when BODY_COMPOSITION wasn't granted, no entries exist,
     * or — critically — when the latest entry lacks WEIGHT. We refuse to
     * persist a snapshot without weight: every consumer (chart, fallback,
     * chat history) expects weight as the anchor field and degrading to
     * null there would force null-checks everywhere downstream.
     */
    suspend fun readLatestBodyCompositionAll(): BodyCompositionReading? {
        return try {
            val zone = ZoneId.systemDefault()
            val endLocal = LocalDateTime.now(zone)
            val startLocal = endLocal.minusDays(365)
            val filter = LocalTimeFilter.of(startLocal, endLocal)

            val response = store.read(DataTypes.BODY_COMPOSITION) {
                setLocalTimeFilter(filter)
                setOrdering(Ordering.DESC)
                setLimit(1)
            }
            val point = response.dataList.firstOrNull() ?: return null
            val weightKg: Float = point.getValue(DataType.BodyCompositionType.WEIGHT)
                ?: return null

            // The Medisana/VitaDock+ scale writes MUSCLE_MASS and
            // TOTAL_BODY_WATER as **percentages** (2026-08-19 probe), not kg.
            // Convert to kg here so every downstream consumer (table, chart,
            // KPIs, chat context, report) sees real kilograms. The mass-in-kg
            // SDK fields (BODY_FAT_MASS etc.) come back null, so weight × %/100
            // is the only path. BODY_FAT is already a genuine % and stays as-is.
            val muscleRawPct = point.getValue(DataType.BodyCompositionType.MUSCLE_MASS)
            val waterRawPct = point.getValue(DataType.BodyCompositionType.TOTAL_BODY_WATER)

            BodyCompositionReading(
                timestamp = point.startTime,
                weightKg = weightKg,
                heightCm = point.getValue(DataType.BodyCompositionType.HEIGHT),
                bodyFatPct = point.getValue(DataType.BodyCompositionType.BODY_FAT),
                muscleMassKg = muscleRawPct?.let { weightKg * it / 100f },
                totalBodyWaterKg = waterRawPct?.let { weightKg * it / 100f },
                bmiValue = point.getValue(DataType.BodyCompositionType.BODY_MASS_INDEX),
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Diagnostic probe: reads the latest BODY_COMPOSITION entry and logs
     * **every field** in the SDK's `BodyCompositionType` so we can see
     * what the connected scale (e.g. Medisana via VitaDock+) actually
     * writes to Samsung Health.
     *
     * **Why a probe and not "just call getValue() and trust nullability"?**
     * The SDK's `Field<T>` typing is uniform; `point.getValue(FIELD)` returns
     * `null` whether (a) the writer-app didn't populate that field, or (b)
     * the read failed silently for some other reason. Logging the whole
     * surface in one go is the only practical way to compare what Hugo's
     * Medisana scale sees on the S-Health Body Composition screen vs.
     * what arrives via the API.
     *
     * Output format (each call emits one line per field):
     *   SHD#body_composition WEIGHT=75.4
     *   SHD#body_composition HEIGHT=null
     *   SHD#body_composition MUSCLE_MASS=33.2
     *   ...
     *
     * The single Log.i header line ("probe @timestamp source=…") makes
     * grep-ing easy:
     *   adb logcat -d | grep "SHD#body_composition"
     *
     * Designed to be a no-op when BODY_COMPOSITION wasn't granted or no
     * entries exist — runs inside a try/catch + early-returns, never
     * throws to the caller.
     *
     * Added 2026-06-26 for the Medisana validation step. Once Hugo
     * confirms which fields are populated, the per-field reader functions
     * (e.g. [readLatestBodyCompositionWeight] pattern) can be added for
     * just those fields and this probe can stay around for future scales
     * or as a debugging aid.
     */
    suspend fun probeBodyCompositionFields() {
        try {
            val zone = ZoneId.systemDefault()
            val endLocal = LocalDateTime.now(zone)
            val startLocal = endLocal.minusDays(365)
            val filter = LocalTimeFilter.of(startLocal, endLocal)

            val response = store.read(DataTypes.BODY_COMPOSITION) {
                setLocalTimeFilter(filter)
                setOrdering(Ordering.DESC)
                setLimit(1)
            }
            val point = response.dataList.firstOrNull()
            if (point == null) {
                android.util.Log.i(
                    "SHD#body_composition",
                    "probe: no entries in last 365 days — scale not connected or sync pending"
                )
                return
            }

            // List every field declared on BodyCompositionType in
            // samsung-health-data-api-1.1.0.aar (verified via javap). Pairs
            // are (label, value-or-null). Float fields and the Int BMR are
            // covariant under Any? so the same list shape works.
            val fields: List<Pair<String, Any?>> = listOf(
                "WEIGHT" to point.getValue(DataType.BodyCompositionType.WEIGHT),
                "HEIGHT" to point.getValue(DataType.BodyCompositionType.HEIGHT),
                "BODY_FAT" to point.getValue(DataType.BodyCompositionType.BODY_FAT),
                "BODY_FAT_MASS" to point.getValue(DataType.BodyCompositionType.BODY_FAT_MASS),
                "MUSCLE_MASS" to point.getValue(DataType.BodyCompositionType.MUSCLE_MASS),
                "SKELETAL_MUSCLE" to point.getValue(DataType.BodyCompositionType.SKELETAL_MUSCLE),
                "SKELETAL_MUSCLE_MASS" to point.getValue(DataType.BodyCompositionType.SKELETAL_MUSCLE_MASS),
                "FAT_FREE_MASS" to point.getValue(DataType.BodyCompositionType.FAT_FREE_MASS),
                "FAT_FREE" to point.getValue(DataType.BodyCompositionType.FAT_FREE),
                "TOTAL_BODY_WATER" to point.getValue(DataType.BodyCompositionType.TOTAL_BODY_WATER),
                "BODY_MASS_INDEX" to point.getValue(DataType.BodyCompositionType.BODY_MASS_INDEX),
                "BASAL_METABOLIC_RATE" to point.getValue(DataType.BodyCompositionType.BASAL_METABOLIC_RATE),
            )

            android.util.Log.i(
                "SHD#body_composition",
                "probe @ ${point.startTime} (${fields.count { it.second != null }}/${fields.size} fields populated)"
            )
            fields.forEach { (name, value) ->
                android.util.Log.i("SHD#body_composition", "$name=${value ?: "null"}")
            }
        } catch (e: Exception) {
            android.util.Log.w(
                "SHD#body_composition",
                "probe failed: ${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }
}
