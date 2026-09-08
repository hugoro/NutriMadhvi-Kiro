package com.example.data.meal.ensemble

import com.example.BuildConfig

/**
 * Single place where the ensemble is switched on or off.
 *
 * The default is [EnsembleMode.GEMINI_ONLY], and that is an *evidence-based*
 * decision rather than caution.
 *
 * Both Groq-hosted Qwen models were benchmarked against six real meal photos
 * from the app, scored against the user-validated entries already stored for
 * those same photos:
 *
 *   qwen3.6-27b — median calorie error 61%, 0/6 answers passed the gate
 *   qwen3.8-27b — median calorie error 68%, 0/6 answers passed the gate
 *
 * Both misidentified dishes outright (lentil curry read as "arroz biryani",
 * Maria biscuits as generic "biscoitos") and one reply leaked CJK characters
 * into a Portuguese label. Crucially they almost never emit a self-confidence
 * field, so [ConfidenceScorer] treats them as "medium" — which correctly keeps
 * them below the identification threshold.
 *
 * The consequence is that [EnsembleMode.QWEN_THEN_GEMINI] would escalate on
 * essentially every photo: the user pays the full Gemini call anyway, plus
 * ~2s of added latency, and gains nothing. The ensemble machinery is kept
 * because it is validated and inert, but it must not be switched on until a
 * primary exists that can actually clear the gate.
 *
 * For the record, the free tier's quota is *not* a reason for this default.
 * Console metrics over the benchmark day show ~18k tokens against an 80k
 * ceiling and requests barely off zero against a limit of 300; every HTTP 429
 * observed was self-inflicted by firing the benchmark photos back-to-back
 * within one minute, which no real user does. The decision rests solely on
 * accuracy.
 */
object EnsembleConfig {

    /**
     * Active mode. Debug builds can opt into the ensemble via
     * [debugOverrideMode]; release builds always use [DEFAULT_MODE] until it
     * is intentionally changed.
     */
    @Volatile
    private var debugOverrideMode: EnsembleMode? = null

    /** Production default. Reads from BuildConfig.VISION_ENSEMBLE_MODE when
     *  present (allows flag-based activation without rewriting this file).
     *  Falls back to GEMINI_ONLY so the field being absent never crashes. */
    val DEFAULT_MODE: EnsembleMode = runCatching {
        val field = com.example.BuildConfig::class.java.getDeclaredField("VISION_ENSEMBLE_MODE")
        field.isAccessible = true
        val value = field.get(null) as? String
        EnsembleMode.entries.firstOrNull { it.name == value } ?: EnsembleMode.GEMINI_ONLY
    }.getOrDefault(EnsembleMode.GEMINI_ONLY)

    val mode: EnsembleMode
        get() = if (BuildConfig.DEBUG) (debugOverrideMode ?: DEFAULT_MODE) else DEFAULT_MODE

    /** Debug-only override; a no-op in release builds. */
    fun setDebugMode(value: EnsembleMode?) {
        if (BuildConfig.DEBUG) debugOverrideMode = value
    }

    val policy: EnsemblePolicy get() = EnsemblePolicy.DEFAULT.copy(mode = mode)

    /** True when the ensemble should take over the provider decision layer. */
    val isEnsembleActive: Boolean get() = mode != EnsembleMode.GEMINI_ONLY

    /**
     * Bitdeer key, sourced from the same `.env` → BuildConfig mechanism as the
     * other keys. Returns blank when the field is absent or still the
     * placeholder, which makes the Bitdeer provider report itself unavailable
     * and the ensemble degrade cleanly to Gemini-only.
     *
     * Read reflectively so the code compiles whether or not `BITDEER_API_KEY`
     * is present in `.env` — a missing field must never crash at startup.
     */
    val bitdeerApiKey: String by lazy {
        runCatching {
            val field = BuildConfig::class.java.getDeclaredField("BITDEER_API_KEY")
            field.isAccessible = true
            (field.get(null) as? String).orEmpty()
        }.getOrDefault("").let { key ->
            if (key.isBlank() || key == "MY_BITDEER_API_KEY") "" else key
        }
    }

    /**
     * Groq key, sourced from the same `.env` → BuildConfig mechanism as the
     * Gemini key. Returns blank when the field is absent or still the
     * placeholder, which makes the Qwen provider report itself unavailable
     * and the ensemble degrade cleanly to Gemini-only.
     *
     * Read reflectively so the code compiles and runs unchanged whether or not
     * `GROQ_API_KEY` has been added to `.env.example` yet — a missing field
     * must never break the build or crash at startup.
     */
    val groqApiKey: String by lazy {
        runCatching {
            val field = BuildConfig::class.java.getDeclaredField("GROQ_API_KEY")
            field.isAccessible = true
            (field.get(null) as? String).orEmpty()
        }.getOrDefault("").let { key ->
            if (key.isBlank() || key == "MY_GROQ_API_KEY") "" else key
        }
    }

    val geminiApiKey: String
        get() = BuildConfig.GEMINI_API_KEY.let { key ->
            if (key.isBlank() || key == "MY_GEMINI_API_KEY") "" else key
        }
}
