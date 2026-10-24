package com.example.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.PerformanceSnapshotEntity
import com.example.data.performance.MuscleMapGeometry
import com.example.data.performance.TrainingDemoCatalog
import com.example.data.performance.TrainingDemoCatalog.feelByView
import com.example.data.performance.TrainingDemoCatalog.incompatibilities
import com.example.data.remote.ChartKpi
import com.example.data.remote.ChartLine
import com.example.ui.chat.MultilineChart
import com.example.ui.theme.AccentPrimary
import com.example.ui.theme.AccentSoft
import com.halilibo.richtext.commonmark.Markdown
import com.halilibo.richtext.ui.material3.RichText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow

// ─── Silhouette ratio KPIs for the evolution chart ───────────────────────────

private enum class SilhouetteKpi(
    val label: String,
    val isFrontal: Boolean,
    val pick: (PerformanceSnapshotEntity) -> Float?
) {
    FRONTAL_SHOULDER("Ombros", true, { it.frontalShoulderRatio }),
    FRONTAL_WAIST("Cintura", true, { it.frontalWaistRatio }),
    FRONTAL_HIP("Ancas", true, { it.frontalHipRatio }),
    FRONTAL_THIGH("Coxas", true, { it.frontalThighRatio }),
    LATERAL_CHEST("Peito (lat)", false, { it.lateralChestRatio }),
    LATERAL_BELLY("Barriga (lat)", false, { it.lateralBellyRatio }),
    LATERAL_THIGH("Coxa (lat)", false, { it.lateralThighRatio }),
}

// ─── Legacy joint KPIs (kept for backward compat with v35 angle-only snapshots)

private enum class MobilityKpi(
    val label: String,
    val unit: String,
    val pick: (PerformanceSnapshotEntity) -> Float?
) {
    LEFT_SHOULDER_SAG("Ombro E (Sag)", "°", { it.leftShoulderSagittalDeg }),
    LEFT_SHOULDER_FRO("Ombro E (Fro)", "°", { it.leftShoulderFrontalDeg }),
    RIGHT_SHOULDER_SAG("Ombro D (Sag)", "°", { it.rightShoulderSagittalDeg }),
    RIGHT_SHOULDER_FRO("Ombro D (Fro)", "°", { it.rightShoulderFrontalDeg }),
    LEFT_HIP_SAG("Anca E (Sag)", "°", { it.leftHipSagittalDeg }),
    LEFT_HIP_FRO("Anca E (Fro)", "°", { it.leftHipFrontalDeg }),
    RIGHT_HIP_SAG("Anca D (Sag)", "°", { it.rightHipSagittalDeg }),
    RIGHT_HIP_FRO("Anca D (Fro)", "°", { it.rightHipFrontalDeg }),
    LEFT_ELBOW_SAG("Cotovelo E", "°", { it.leftElbowSagittalDeg }),
    RIGHT_ELBOW_SAG("Cotovelo D", "°", { it.rightElbowSagittalDeg }),
    LEFT_KNEE_SAG("Joelho E", "°", { it.leftKneeSagittalDeg }),
    RIGHT_KNEE_SAG("Joelho D", "°", { it.rightKneeSagittalDeg }),
}

private val DATE_FMT = SimpleDateFormat("dd/MM/yy", Locale.getDefault())

// QA 2026-10: checkpoint rows carry the time as well — two same-day
// checkpoints rendered as identical "dd/MM/yy" labels read as duplicates.
private val DATE_TIME_FMT = SimpleDateFormat("dd/MM/yy · HH:mm", Locale.getDefault())
// QA 2026-10 (second pass): checkpoints created within the same minute render
// an identical "dd/MM/yy · HH:mm" label and read as duplicates — the seconds
// format is applied to those rows only (see BodyProgressView).
private val DATE_TIME_SECONDS_FMT = SimpleDateFormat("dd/MM/yy · HH:mm:ss", Locale.getDefault())

// ─── Body plan UI (v39, hardened v40) ───────────────────────────────────────

/** Internal segmented navigation of the Performance tab. */
private enum class PerfTab { OVERVIEW, PLAN, PROGRESS }

private data class ParsedPriority(
    val zone: String,
    val direction: String, // REDUCE | GROW | HOLD
    val gapPp: Float,
    val tissue: String,
)

private data class ParsedTactic(
    val category: String,
    val text: String,
    val target: String?,
    /** v43.1: zone key (zone tactics) — feeds the demo compatibility check
     *  (a shoulder-zone tactic must not carry a lower-body demo). */
    val zone: String? = null,
    val windowDays: Int,
    /** v43: curated training-demo id (validated at the validator boundary;
     *  the UI only resolves ids from the local catalog — unknown ids render
     *  no demo chrome, fail closed). */
    val demoId: String? = null,
)

/**
 * Fail-soft + defensive parsers for the persisted JSON payloads (never
 * throw). v40 hardening: non-finite or implausible numbers are REJECTED at
 * the parse boundary (gapPp must be finite and ≤ 30pp; windowDays must be
 * finite and 1..365 before being clamped into 7..120) so a corrupted or
 * hostile row can never drive the UI.
 */
private fun parsePriorities(json: String?): List<ParsedPriority> = try {
    val arr = org.json.JSONArray(json ?: return emptyList())
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val zone = o.optString("zone", "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val gapRaw = o.optDouble("gapPp", Double.NaN)
        if (!gapRaw.isFinite() || kotlin.math.abs(gapRaw) > 30.0) return@mapNotNull null
        ParsedPriority(
            zone = zone,
            direction = o.optString("direction", "HOLD"),
            gapPp = gapRaw.toFloat(),
            tissue = o.optString("tissue", "UNKNOWN"),
        )
    }
} catch (e: Exception) {
    emptyList()
}

private fun parseTactics(json: String?): List<ParsedTactic> = try {
    val arr = org.json.JSONArray(json ?: return emptyList())
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val text = o.optString("text", "").trim().takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val wRaw = o.opt("windowDays") as? Number
        if (wRaw == null || !wRaw.toDouble().isFinite() ||
            wRaw.toInt() !in 1..365
        ) return@mapNotNull null
        ParsedTactic(
            category = o.optString("category", ""),
            text = text,
            target = o.optString("target", "").trim().takeIf { it.isNotBlank() },
            zone = o.optString("zone", "").trim().takeIf { it.isNotBlank() },
            windowDays = wRaw.toInt().coerceIn(7, 120),
            demoId = o.optString("demoId", "").trim().takeIf { it.isNotBlank() },
        )
    }
} catch (e: Exception) {
    emptyList()
}

@Composable
private fun PerfSegmentedControl(
    selected: PerfTab,
    onSelect: (PerfTab) -> Unit,
    lang: String,
) {
    val labels = listOf(
        PerfTab.OVERVIEW to tr("perf_tab_overview", lang),
        PerfTab.PLAN to tr("perf_tab_plan", lang),
        PerfTab.PROGRESS to tr("perf_tab_progress", lang),
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        labels.forEach { (tab, label) ->
            val isSelected = tab == selected
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (isSelected) AccentPrimary else Color(0xFF1A1F2E),
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(tab) },
            ) {
                Text(
                    label,
                    // QA 2026-10: inactive tab contrast raised (0.6 → 0.78) so the
                    // unselected options stay legible on the dark card.
                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.78f),
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 9.dp),
                )
            }
        }
    }
}

/** Persistent medical disclaimer (v40): estimates only — not medical
 *  advice, diagnosis, or a medical device. Shown on Plan and Progress. */
@Composable
private fun MedicalDisclaimer(lang: String) {
    // QA 2026-10: contrast raised (0.38 → 0.62) — the medical disclaimer is
    // safety-relevant copy and must be readable, not decorative.
    Text(
        tr("medical_disclaimer", lang),
        color = Color.White.copy(alpha = 0.62f),
        fontSize = 10.sp,
        lineHeight = 14.sp,
        modifier = Modifier.padding(bottom = 2.dp),
    )
}

/** Localizes a per-checkpoint enrichment error key (v40 — retries must be
 *  visible, never silent). */
private fun checkpointErrorLabel(key: String?, lang: String): String? = when (key) {
    "checkpoint_gone" -> tr("plan_error_checkpoint_gone", lang)
    "snapshot_missing" -> tr("plan_error_snapshot_missing", lang)
    "llm_failed" -> tr("plan_error_llm_failed", lang)
    "tactics_rejected" -> tr("plan_error_tactics_rejected", lang)
    else -> null
}

@Composable
private fun PhaseBadge(phase: String?, lang: String) {
    if (phase.isNullOrBlank()) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF2A3350),
            modifier = Modifier.padding(end = 4.dp),
        ) {
            Text(
                tr("phase_unknown", lang),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        return
    }
    val (label, color) = when (phase) {
        "CUT" -> tr("phase_cut", lang) to Color(0xFF38BDF8)
        "BULK" -> tr("phase_bulk", lang) to Color(0xFF4ADE80)
        "RECOMP" -> tr("phase_recomp", lang) to Color(0xFFA78BFA)
        "MAINTAIN" -> tr("phase_maintain", lang) to Color(0xFF94A3B8)
        // QA 2026-10 (second pass): unknown internal codes must not leak to the
        // user — show the generic "Phase" label instead of the raw token.
        else -> tr("phase_unknown", lang) to Color(0xFF94A3B8)
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.16f),
        modifier = Modifier.padding(end = 4.dp),
    ) {
        Text(
            label,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** v43 (2026-10): presentation state for a checkpoint's plan block.
 *
 *  A USABLE plan (any non-empty tactics set — fully LLM, partially
 *  adjusted, or full deterministic fallback) renders ONLY the neutral
 *  "Plano recomendado" label: no "IA indisponível", no technical
 *  model/source display, no error, no retry. [BodyPlanCheckpointEntity.tacticSource]
 *  stays on the entity and in the logcat for audit. Error and retry
 *  appear only when there is NO usable plan (tactics empty) — or while
 *  nothing has been generated yet (generate action).
 */
internal data class PlanBlockUi(
    val pending: Boolean,
    val usablePlan: Boolean,
    val showsAdjustedNote: Boolean,
    val showsError: Boolean,
    val offersRetry: Boolean,
    val offersGenerate: Boolean,
) {
    companion object {
        fun of(
            tacticSource: String?,
            tacticsCount: Int,
            hasError: Boolean,
            isEnriching: Boolean,
        ): PlanBlockUi = when {
            isEnriching -> PlanBlockUi(
                pending = true, usablePlan = false,
                showsAdjustedNote = false, showsError = false,
                offersRetry = false, offersGenerate = false,
            )
            tacticsCount > 0 -> PlanBlockUi(
                pending = false, usablePlan = true,
                showsAdjustedNote = tacticSource?.startsWith("mixed") == true,
                showsError = false,
                offersRetry = false, offersGenerate = false,
            )
            else -> PlanBlockUi(
                pending = false, usablePlan = false,
                showsAdjustedNote = false,
                showsError = hasError,
                offersRetry = hasError, offersGenerate = !hasError,
            )
        }
    }
}

/**
 * Plano (v39/v40): latest body-plan checkpoint — phase badge, top-3 priority
 * zones (safety-gated directions), tactics list with source label (real
 * "llm:<model>" traceability), persistent medical disclaimer, visible
 * enrichment errors, and a retry that re-runs the LLM enrichment from the
 * checkpoint's own anchored inputs.
 */
@Composable
internal fun BodyPlanView(
    checkpoints: List<com.example.data.local.BodyPlanCheckpointEntity>,
    hasGoalTargets: Boolean,
    lang: String,
    enriching: Set<Long>,
    errors: Map<Long, String>,
    onRetry: (Long) -> Unit,
    // QA 2026-10: direct goal action from the Plan empty states — jumps to the
    // Overview goal card and expands it.
    onOpenGoal: () -> Unit = { },
) {
    val cp = checkpoints.firstOrNull()
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1F2E), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MedicalDisclaimer(lang)
        if (cp == null) {
            Text(
                tr("plan_no_checkpoint", lang),
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 13.sp,
            )
            // QA 2026-10 (third round): when a goal with targets ALREADY
            // exists, "Definir objetivo" is misleading — the missing piece
            // is an assessment, which the message above already says.
            if (!hasGoalTargets) {
                OutlinedButton(
                    onClick = onOpenGoal,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentSoft),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(tr("plan_open_goal", lang), fontSize = 12.sp)
                }
            }
            return
        }
        val isEnriching = cp.id in enriching
        val errorLabel = checkpointErrorLabel(errors[cp.id], lang)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("plan_phase", lang), color = Color.White, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            PhaseBadge(cp.phase, lang)
            Text(
                DATE_TIME_FMT.format(Date(cp.checkpointAtMs)),
                color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp,
            )
        }

        // ── Priorities ────────────────────────────────────────────────────────
        val priorities = parsePriorities(cp.prioritiesJson)
        if (priorities.isEmpty() && !hasGoalTargets) {
            Text(
                tr("plan_no_targets", lang),
                color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp,
            )
            // QA 2026-10: direct goal action from the Plan empty state.
            OutlinedButton(
                onClick = onOpenGoal,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentSoft),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(tr("plan_open_goal", lang), fontSize = 12.sp)
            }
        } else if (priorities.isNotEmpty()) {
            Text(tr("plan_priorities", lang),
                color = Color.White.copy(alpha = 0.7f),
                fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            priorities.forEach { p ->
                val (dirLabel, dirColor) = when (p.direction) {
                    "REDUCE" -> tr("plan_direction_reduce", lang) to Color(0xFFF87171)
                    "GROW" -> tr("plan_direction_grow", lang) to Color(0xFF4ADE80)
                    else -> tr("plan_direction_hold", lang) to Color(0xFF94A3B8)
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF12172A), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tr("zone_${p.zone}", lang),
                        color = Color.White, fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${if (p.gapPp >= 0) "+" else ""}${p.gapPp.roundTo1Ui(lang)}pp",
                        color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp,
                    )
                    Text(
                        dirLabel,
                        color = dirColor, fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // ── Tactics ───────────────────────────────────────────────────────────
        // QA 2026-10: explicit pending / error / idle states. The old version
        // rendered "Táticas a gerar..." (implies in flight) alongside the
        // "Gerar novamente" button (implies failure) at the same time.
        val tactics = parseTactics(cp.tacticsJson)
        val block = PlanBlockUi.of(cp.tacticSource, tactics.size, errorLabel != null, isEnriching)
        Text(tr("plan_tactics", lang),
            color = Color.White.copy(alpha = 0.7f),
            fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        if (isEnriching) {
            // PENDING — enrichment in flight: label + spinner, no action button.
            Text(
                tr("plan_enriching", lang),
                color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp,
            )
            LinearProgressIndicator(
                color = AccentSoft,
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
        } else if (tactics.isEmpty()) {
            // Not in flight: the state is explicit — failed (the error label
            // below explains why) or idle (never generated yet).
            Text(
                if (errorLabel != null) tr("plan_tactics_failed", lang)
                else tr("plan_tactics_idle", lang),
                color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp,
            )
        } else {
            tactics.forEach { t ->
                Column(Modifier.padding(vertical = 3.dp)) {
                    Text(t.text, color = Color.White, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        t.target?.let {
                            Text(it, color = AccentSoft, fontSize = 11.sp)
                        }
                        Text(
                            tr("plan_window_days", lang).replace("{days}", "${t.windowDays}"),
                            color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp,
                        )
                    }
                    // v43 (2026-10): curated training demo — the card renders
                    // ONLY for ids resolvable in the local catalog (unknown
                    // ids fail closed and render nothing).
                    // v43.1 (2026-10): + the demo must FIT this tactic
                    // (category + region) — persisted rows are not re-run
                    // through the validator, so the UI applies the same
                    // shared incompatibility predicate. Fail closed.
                    t.demoId?.let { id ->
                        TrainingDemoCatalog.byId(id)?.takeIf { demo ->
                            demo.incompatibilities(t.category, t.text, t.target, t.zone).isEmpty()
                        }?.let { demo ->
                            TrainingDemoCard(demo, lang)
                        }
                    }
                }
            }
            // v43 (2026-10): a usable plan — fully LLM, partially adjusted,
            // or full deterministic fallback — renders only the neutral
            // label. No "IA indisponível", no model name. tacticSource
            // stays on the entity + logcat for audit.
            Text(
                tr("plan_recommended", lang),
                color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
            // v42 (2026-10): a partially adjusted plan is still a COMPLETE
            // safe plan — informational note only (no error, no retry).
            if (block.showsAdjustedNote) {
                Text(
                    tr("plan_tactics_adjusted", lang),
                    color = Color(0xFF94A3B8), fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        // ── Visible error (v40: retries must never be silent) ─────────────────
        // v43 (2026-10): only when there is NO usable plan — a usable plan
        // (even a full fallback) is not a failure and hides the error.
        if (block.showsError && errorLabel != null) {
            Text(
                errorLabel,
                color = Color(0xFFF87171).copy(alpha = 0.9f), fontSize = 12.sp,
            )
        }

        // ── Retry / generate (only when there is NO usable plan) ─────────────
        // QA 2026-10: when tactics were never generated and nothing failed the
        // action reads "Gerar táticas" (generate), not "Gerar novamente"
        // (generate again).
        // v42 (2026-10): a mixed (partially adjusted) plan is still complete
        // and safe, so it must not offer a "generate again" that implies
        // failure. v43 (2026-10): same for the full deterministic fallback —
        // error and retry appear only without a usable plan.
        if (!block.usablePlan && !isEnriching) {
            // QA 2026-10 (second pass): enrichment without a goal has nothing to
            // target — the actionable step is to define the goal, not to run a
            // generation that would fail or produce generic tactics.
            if (!hasGoalTargets) {
                OutlinedButton(
                    onClick = onOpenGoal,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentSoft),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(tr("plan_open_goal", lang), fontSize = 12.sp)
                }
            } else {
                OutlinedButton(
                    onClick = { onRetry(cp.id) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentSoft),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        if (tactics.isEmpty() && errorLabel == null)
                            tr("plan_tactics_generate", lang)
                        else tr("plan_retry", lang),
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

/**
 * v43 (2026-10): local curated training-demo card.
 *
 * Local illustration + name/region + prescription (sets-reps or duration)
 * + "Watch demo" (opens the curated video EXTERNALLY via ACTION_VIEW).
 *
 * v43.7 (2026-10): the personal-trainer execution protocol, in order:
 *   1. INTENT — what the exercise is for,
 *   2. FEEL — which muscles the user must feel (anatomical highlight over
 *      the user's own silhouette, per gender) + the mirror self-check rule,
 *   3. EXECUTION — the existing form cue,
 *   4. AFTER — what to do immediately after the set, and what NOT to do
 *      (e.g. sitting immediately → blood pools in the legs → fainting).
 * Copy is local; fail closed: a missing localized "Deves sentir" copy
 * renders the generic mirror line (never a raw key, never an empty
 * section); a (view, region) without geometry renders no figure (never a
 * wrong one). All copy keys are pinned by TrainingDemoFeelProtocolTest.
 */
@Composable
private fun TrainingDemoCard(
    demo: TrainingDemoCatalog.TrainingDemo,
    lang: String,
) {
    val context = LocalContext.current
    // The anatomical figure follows the user's own silhouette — same gender
    // convention as the Performance silhouettes: "Female" → female figure,
    // anything else (Male/Other) → male figure.
    val isMale = remember {
        val gender = context
            .getSharedPreferences("user_profile_pref", android.content.Context.MODE_PRIVATE)
            .getString("profile_gender", "Female") ?: "Female"
        !gender.equals("Female", ignoreCase = true)
    }
    val feelByView = demo.feelByView()
    // v43.8: tap a muscle figure → full-screen-width zoom so the target
    // region is unambiguous (where the work / toning should be felt).
    var zoomedView by remember { mutableStateOf<TrainingDemoCatalog.MuscleView?>(null) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1F2E), RoundedCornerShape(10.dp))
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Image(
            painter = painterResource(demoIllustrationResource(demo.illustrationName)),
            contentDescription = tr("demo_${demo.id}_name", lang),
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(56.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                tr("demo_${demo.id}_name", lang),
                color = Color.White, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                tr("demo_${demo.id}_prescription", lang),
                color = AccentSoft, fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
            // v43.7 ── 1. INTENT ─────────────────────────────────────────────
            ProtocolSectionLabel(tr("demo_intent_label", lang))
            Text(
                tr("demo_${demo.id}_intent", lang),
                color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp,
            )
            // v43.7 ── 2. FEEL (muscle map + mirror self-check rule) ────────
            ProtocolSectionLabel(tr("demo_feel_label", lang))
            // v43.13: three body views (FRONTAL/LATERAL/POSTERIOR) — a card
            // with three figures gets a narrower slot so the Row still fits.
            val feelSlotWidth = if (feelByView.size >= 3) 96.dp else 120.dp
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 2.dp).testTag("demo_feel_figures"),
            ) {
                TrainingDemoCatalog.MuscleView.values().forEach { view ->
                    feelByView[view]?.let { regions ->
                        MuscleFigure(isMale, view, regions, lang, feelSlotWidth) {
                            zoomedView = view
                        }
                    }
                }
            }
            // v43.8: the tap-to-zoom affordance, stated plainly.
            Text(
                tr("demo_feel_zoom_hint", lang),
                color = Color.White.copy(alpha = 0.45f), fontSize = 9.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
            // v43.7 fail closed: a missing localized feel copy must never
            // render the raw key — fall back to the generic mirror rule.
            val feelCopy = tr("demo_${demo.id}_feel", lang).takeIf {
                !it.startsWith("demo_${demo.id}_")
            } ?: tr("demo_feel_mirror_fallback", lang)
            Text(
                feelCopy,
                color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
            // ── 3. EXECUTION (existing form cue) ─────────────────────────────
            Text(
                tr("demo_${demo.id}_cue", lang),
                color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
            // v43.7 ── 4. AFTER (do / don't immediately) ────────────────────
            ProtocolSectionLabel(tr("demo_after_label", lang))
            Text(
                tr("demo_${demo.id}_after_do", lang),
                color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp,
            )
            Text(
                "✗ " + tr("demo_${demo.id}_after_dont", lang),
                color = Color(0xFFE08A8A).copy(alpha = 0.9f), fontSize = 10.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                tr("demo_${demo.id}_progression", lang),
                color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
            // v43.2: 48dp minimum touch target (WCAG 2.5.5 / Android
            // accessibility) — the WHOLE area is the clickable target
            // (Material Button: onClick covers the entire padded region),
            // tagged for the Compose size/action test.
            Button(
                onClick = { openDemoVideo(context, demo.videoUrl) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("demo_view_button"),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF232A3D)),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
            ) {
                Text(
                    tr("demo_view", lang),
                    color = AccentSoft,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
    // v43.8: the zoom overlay — full screen width, tap to close.
    zoomedView?.let { view ->
        val regions = feelByView[view].orEmpty()
        if (regions.isNotEmpty()) {
            MuscleFigureZoom(isMale, view, regions, lang) { zoomedView = null }
        }
    }
}

/** v43.7: small uppercase section label for the execution protocol. */
@Composable
private fun ProtocolSectionLabel(label: String) {
    Text(
        label.uppercase(),
        color = AccentSoft.copy(alpha = 0.85f),
        fontSize = 9.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/** Maps a (gender, view) to the local muscle-figure drawable.
 *  v43.13: realistic Gemini-rendered anatomical figures (9:16, transparent
 *  bg) — a DEDICATED asset set for the demo cards, so the goal card keeps
 *  its own line-art silhouettes + zone-marker calibration. */
private fun silhouetteDrawableRes(isMale: Boolean, view: TrainingDemoCatalog.MuscleView): Int =
    when (view) {
        TrainingDemoCatalog.MuscleView.FRONTAL ->
            if (isMale) R.drawable.demo_muscle_male_frontal else R.drawable.demo_muscle_female_frontal
        TrainingDemoCatalog.MuscleView.LATERAL ->
            if (isMale) R.drawable.demo_muscle_male_lateral else R.drawable.demo_muscle_female_lateral
        TrainingDemoCatalog.MuscleView.POSTERIOR ->
            if (isMale) R.drawable.demo_muscle_male_posterior else R.drawable.demo_muscle_female_posterior
    }

/** PNG intrinsic aspect (width/height) — v43.13: all six muscle-figure
 *  renders are 768x1344 (9:16). */
private fun silhouetteAspect(isMale: Boolean, view: TrainingDemoCatalog.MuscleView): Float = 768f / 1344f

/**
 * v43.8 (2026-10): anatomical "where to feel it" figure — BIG enough to read
 * at a glance: the user's own silhouette with the target muscle in a bright
 * amber GLOW (soft halo + solid core + white edge) and an ARROW pointing at
 * the highlighted area. A view label ("FRONTAL" / "LATERAL") makes the two
 * figures unmistakably different poses — the earlier 44dp side-by-side pair
 * read as "repeated images" to the user (QA 2026-10-22).
 * Fail closed: no geometry for any (view, region) → the figure is skipped
 * (the feel TEXT still renders).
 */
// Bright warm amber — the "glare" the user asked for: high contrast against
// the dark card (26,31,46) where the old violet @ 0.75 alpha went unnoticed.
private val MuscleGlow = Color(0xFFFFB84D)

/** v43.13: the i18n key for a body view label (three closed views). */
private fun viewLabelKey(view: TrainingDemoCatalog.MuscleView): String = when (view) {
    TrainingDemoCatalog.MuscleView.FRONTAL -> "view_frontal"
    TrainingDemoCatalog.MuscleView.LATERAL -> "view_lateral"
    TrainingDemoCatalog.MuscleView.POSTERIOR -> "view_posterior"
}

@Composable
private fun MuscleFigure(
    isMale: Boolean,
    view: TrainingDemoCatalog.MuscleView,
    regions: List<TrainingDemoCatalog.MuscleRegion>,
    lang: String,
    slotWidth: Dp = 120.dp,
    onZoom: () -> Unit,
) {
    val polys = regions.mapNotNull {
        MuscleMapGeometry.highlight(isMale, view, it)
    }.flatMap { it.polys }
    if (polys.isEmpty()) return // fail closed — text-only feel
    val label = regions.joinToString(" · ") { tr("muscle_${it.name.lowercase()}", lang) }
    val viewLabel = tr(viewLabelKey(view), lang)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.testTag("demo_feel_figure_${view.name.lowercase()}"),
    ) {
        Text(
            viewLabel.uppercase(),
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
        )
        // 120x170dp slot — the silhouette is ~2.5x bigger than the v43.7
        // 44dp version; the whole slot is the tap target for the zoom.
        // v43.13: three figures per card (walk) get a narrower slot.
        Box(
            Modifier
                .size(width = slotWidth, height = 170.dp)
                .clickable(onClick = onZoom)
                .testTag("demo_feel_figure_tap"),
        ) {
            Image(
                painter = painterResource(silhouetteDrawableRes(isMale, view)),
                contentDescription = label,
                contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSize(),
            )
            Canvas(Modifier.matchParentSize()) {
                drawMuscleMap(isMale, view, polys, lineWidth = 3f, head = 9f)
            }
        }
        Text(
            label,
            color = MuscleGlow,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            // The caption must wrap at the figure's width, NOT expand to the
            // card width — an unconstrained Text made this Column claim the
            // whole Row, starving the sibling figure of width and wrapping
            // its caption one character per line at the screen edge (QA
            // 2026-10-22: "texto desformatado").
            modifier = Modifier
                .widthIn(max = slotWidth)
                .padding(top = 2.dp),
        )
    }
}

/**
 * v43.14: draws the ILLUMINATED muscle glow + arrows for the given
 * (gender, view) into the current canvas — the silhouette PNG is
 * letterboxed with the SAME rect math as the fitted Image. Shared by the
 * card's mini-figures and the full-width zoom view, so the two can never
 * render differently.
 *
 * The user's QA 2026-10-23: the old rendering (solid amber capsule + white
 * edge + arrow) read as flat "circulos amarelos opacos" — subjective and
 * hard to identify. Now each lobe is painted as layered soft light: a hot
 * bright core that fades through several blur radii into the body, like
 * the muscle itself is lit from within. No opaque fill, no white outline.
 */
private fun DrawScope.drawMuscleMap(
    isMale: Boolean,
    view: TrainingDemoCatalog.MuscleView,
    polys: List<MuscleMapGeometry.Poly>,
    lineWidth: Float,
    head: Float,
) {
    val aspect = silhouetteAspect(isMale, view)
    val boxAspect = size.width / size.height
    val rect = if (aspect < boxAspect) {
        val w = size.height * aspect
        Rect((size.width - w) / 2f, 0f, (size.width + w) / 2f, size.height)
    } else {
        val h = size.width / aspect
        Rect(0f, (size.height - h) / 2f, size.width, (size.height + h) / 2f)
    }
    // The glow, per lobe — pure Compose (this BOM's DrawScope exposes no
    // native canvas, so no BlurMaskFilter). v43.15 (user QA 2026-10-23,
    // reference image): the glow is a SEMI-TRANSPARENT warm light ON the
    // muscle — the body shows through it (its own shading stays visible),
    // and the TIGHT footprint is what makes the placement read at a glance:
    // "this exact area of my body is the muscle". v43.15 shipped 8 halo
    // rings (~0.016 of the figure width beyond the lobe); user QA 2nd round
    // (2026-10-23): that fringe still read onto the TFL/hip flexors at the
    // quads and brushed the clavicle/trapezius at the pecs. v43.16: 4 rings
    // (~0.008, i.e. ~5px on the card figures) — the bright fringe hugs the
    // lobe edge. Radial core capped at 0.50 alpha — a warm wash, not an
    // opaque sticker (v43.14 used 0.80–0.95: too opaque, halo too wide).
    val haloSteps = 4
    val stepW = rect.width * 0.004f
    polys.forEach { poly ->
        val path = Path().apply {
            poly.pts.forEachIndexed { i, (nx, ny) ->
                val x = rect.left + nx * rect.width
                val y = rect.top + ny * rect.height
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        // 1) halo: widest (faintest) → tightest (brightest), drawn outside-in
        // so the tight bright ring paints over the wide faint one.
        for (i in haloSteps downTo 1) {
            val a = 0.30f * (1f - i.toFloat() / (haloSteps + 1f)).pow(1.6f)
            if (a > 0.004f) {
                drawPath(
                    path,
                    color = MuscleGlow.copy(alpha = a),
                    style = Stroke(width = i * stepW, cap = StrokeCap.Round),
                )
            }
        }
        // 2) lit core: translucent radial gradient centred on the muscle
        // belly — the figure's own tones remain visible underneath.
        val xs = poly.pts.map { it.first }
        val ys = poly.pts.map { it.second }
        val cx = rect.left + (xs.min() + xs.max()) / 2f * rect.width
        val cy = rect.top + (ys.min() + ys.max()) / 2f * rect.height
        val radius = maxOf(
            (xs.max() - xs.min()) * rect.width,
            (ys.max() - ys.min()) * rect.height * 0.85f,
        ) * 0.45f
        drawPath(
            path,
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFFFFE9C4).copy(alpha = 0.50f),
                    Color(0xFFFFB84D).copy(alpha = 0.42f),
                    Color(0xFFFF8A00).copy(alpha = 0.34f),
                ),
                center = Offset(cx, cy),
                radius = radius,
            ),
        )
    }
    // One arrow per lobe, pointing at the highlighted area. The line starts
    // at the canvas edge, but is capped at 28% of the fitted figure width —
    // in the full-width zoom an uncapped line would run across the whole
    // screen and read as a stray UI element (QA 2026-10-22).
    polys.forEach { poly ->
        val pts = poly.pts
        val minX = pts.minOf { it.first }
        val maxX = pts.maxOf { it.first }
        val midY = pts.map { it.second }.average().toFloat()
        val cx = (minX + maxX) / 2f
        val leftEdge = rect.left + minX * rect.width
        val rightEdge = rect.right - (1f - maxX) * rect.width
        val cy = rect.top + midY * rect.height
        val fromLeft = cx < (rect.left + rect.right) / 2f
        val ax = if (fromLeft) 6f else size.width - 6f
        val tx = if (fromLeft) leftEdge - 4f else rightEdge + 4f
        val span = if (fromLeft) tx - ax else ax - tx
        if (span > 18f) {
            val len = minOf(span, rect.width * 0.28f)
            val from = if (fromLeft) tx - len else tx + len
            val arrowColor = Color.White.copy(alpha = 0.95f)
            drawLine(arrowColor, Offset(from, cy), Offset(tx, cy), lineWidth)
            val dir = if (fromLeft) 1f else -1f
            drawPath(
                Path().apply {
                    moveTo(tx + dir * head, cy)
                    lineTo(tx, cy - head * 0.6f)
                    lineTo(tx, cy + head * 0.6f)
                    close()
                },
                color = arrowColor,
            )
        }
    }
}

/**
 * v43.8: full-screen-width zoom of ONE muscle figure — the user's ask: tap
 * to enlarge "por forma a não ficarem dúvidas onde o músculo está e qual
 * deve ser a região onde o resultado do trabalho se sente (tonificação)".
 * Same rendering as the card figure (shared drawMuscleMap), much bigger.
 * Tap anywhere to close.
 */
@Composable
private fun MuscleFigureZoom(
    isMale: Boolean,
    view: TrainingDemoCatalog.MuscleView,
    regions: List<TrainingDemoCatalog.MuscleRegion>,
    lang: String,
    onDismiss: () -> Unit,
) {
    val polys = regions.mapNotNull {
        MuscleMapGeometry.highlight(isMale, view, it)
    }.flatMap { it.polys }
    if (polys.isEmpty()) return // fail closed — nothing to show
    val label = regions.joinToString(" · ") { tr("muscle_${it.name.lowercase()}", lang) }
    val viewLabel = tr(viewLabelKey(view), lang)
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF0B0E16))
                .clickable(onClick = onDismiss)
                .testTag("demo_feel_zoom"),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    viewLabel.uppercase(),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 12.dp),
                ) {
                    Image(
                        painter = painterResource(silhouetteDrawableRes(isMale, view)),
                        contentDescription = label,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.matchParentSize(),
                    )
                    Canvas(Modifier.matchParentSize()) {
                        drawMuscleMap(isMale, view, polys, lineWidth = 6f, head = 18f)
                    }
                }
                Text(
                    label,
                    color = MuscleGlow,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    tr("demo_feel_zoom_close", lang),
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 32.dp),
                )
            }
        }
    }
}

/** Maps a catalog illustration NAME to its local drawable (closed set —
 *  an unmapped name is a developer error and must not silently render a
 *  WRONG exercise illustration).
 *  v43.10 (2026-10): `internal` (not private) so the unit test
 *  [TrainingDemoIllustrationTest] can pin that EVERY catalog demo resolves
 *  to a drawable — the "Força card with no data" class of bug: a demo in
 *  the catalog without its illustration threw at composition time. */
internal fun demoIllustrationResource(name: String): Int = when (name) {
    "demo_squat_bodyweight" -> R.drawable.demo_squat_bodyweight
    "demo_brisk_walk" -> R.drawable.demo_brisk_walk
    "demo_hip_flexor_stretch" -> R.drawable.demo_hip_flexor_stretch
    "demo_full_body_circuit" -> R.drawable.demo_full_body_circuit
    else -> error("No local illustration mapped for demo '$name'")
}

/** Opens a curated demo video in an external app (ACTION_VIEW).
 *  v43.2: the URL is validated at RUNTIME against the catalog guard —
 *  only https + allowlisted host (TrainingDemoCatalog.VIDEO_HOSTS) is
 *  launched; anything else (http, other/lookalike hosts, malformed) is
 *  refused and never reaches startActivity (fail closed). Fail-soft: a
 *  device without a video handler simply does nothing. */
private fun openDemoVideo(context: android.content.Context, url: String) {
    if (!TrainingDemoCatalog.isSafeDemoUrl(url)) {
        // audit trail: a URL that failed the guard is logged, never launched
        android.util.Log.w("DemoVideo", "refusing unsafe demo url (fail closed)")
        return
    }
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * Progresso (v39/v40): checkpoint timeline — date, phase, delta summary,
 * per-checkpoint report with its own retry, visible errors, and the
 * persistent medical disclaimer.
 */
@Composable
internal fun BodyProgressView(
    checkpoints: List<com.example.data.local.BodyPlanCheckpointEntity>,
    /** Pre-checkpoint assessments (v35-v38). They remain historical records;
     * the UI must not hide them or pretend that adaptive checkpoints existed
     * when they were captured. */
    legacySnapshots: List<PerformanceSnapshotEntity> = emptyList(),
    lang: String,
    enriching: Set<Long>,
    errors: Map<Long, String>,
    onRetry: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1A1F2E),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                MedicalDisclaimer(lang)
            }
        }
        if (checkpoints.isEmpty() && legacySnapshots.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF1A1F2E),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    tr("progress_empty", lang),
                    color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp,
                    modifier = Modifier.padding(16.dp),
                )
            }
            return
        }
        if (checkpoints.isEmpty()) {
            legacySnapshots
                .sortedByDescending { it.capturedAtMs }
                .forEach { snap ->
                    LegacyAssessmentCard(
                        snap = snap,
                        date = legacyDateLabel(snap, legacySnapshots),
                        lang = lang,
                    )
                }
            return
        }
        // QA 2026-10 (second pass): two checkpoints captured within the same
        // minute (e.g. the v41 seed pair) render identical "dd/MM/yy · HH:mm"
        // labels and read as duplicates. Show seconds for colliding rows only.
        val minuteKey: (Long) -> Long = { ms -> ms / 60_000L }
        val collidingMinutes = checkpoints
            .groupBy { minuteKey(it.checkpointAtMs) }
            .filterValues { it.size > 1 }
            .keys
        val checkpointDateLabel: (com.example.data.local.BodyPlanCheckpointEntity) -> String =
            { cp ->
                if (minuteKey(cp.checkpointAtMs) in collidingMinutes)
                    DATE_TIME_SECONDS_FMT.format(Date(cp.checkpointAtMs))
                else DATE_TIME_FMT.format(Date(cp.checkpointAtMs))
            }
        checkpoints.forEach { cp ->
            val isEnriching = cp.id in enriching
            val errorLabel = checkpointErrorLabel(errors[cp.id], lang)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1F2E), RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        checkpointDateLabel(cp),
                        color = Color.White, fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    PhaseBadge(cp.phase, lang)
                }
                cp.deltaSummary?.let {
                    // QA 2026-10 (second pass): deltaSummary was built with
                    // US-locale decimals ("cintura -0.3pp"); render it with the
                    // app locale ("cintura -0,3pp") for pt users. Existing DB
                    // rows keep their stored format — display-only conversion.
                    Text(
                        tr("plan_delta_label", lang) + ": " + localizeDecimals(it, lang),
                        color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp,
                    )
                }
                if (cp.coachReport != null) {
                    Text(
                        cp.coachReport,
                        color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                } else {
                    // QA 2026-10: explicit pending / idle states — the old
                    // "Relatório a gerar..." + "Gerar novamente" pair read as a
                    // contradiction.
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (isEnriching) tr("plan_enriching", lang)
                            else tr("progress_report_idle", lang),
                            color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp,
                        )
                        // When an error is present the error row below already
                        // offers the retry — avoid two competing buttons.
                        if (!isEnriching && errorLabel == null) {
                            TextButton(onClick = { onRetry(cp.id) }) {
                                Text(tr("progress_report_generate", lang), fontSize = 12.sp, color = AccentSoft)
                            }
                        }
                    }
                    if (isEnriching) {
                        LinearProgressIndicator(
                            color = AccentSoft,
                            modifier = Modifier.fillMaxWidth().height(2.dp),
                        )
                    }
                }
                if (errorLabel != null) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            errorLabel,
                            color = Color(0xFFF87171).copy(alpha = 0.9f), fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                        )
                        if (!isEnriching) {
                            TextButton(onClick = { onRetry(cp.id) }) {
                                Text(tr("plan_retry", lang), fontSize = 12.sp, color = AccentSoft)
                            }
                        }
                    }
                }
            }
        }
        // QA 2026-10 (second pass): mixed history — unreferenced v35-v38
        // snapshots must not disappear when checkpoints exist. Rendered
        // newest-first, below the checkpoint cards (they predate the
        // checkpoint era and remain historical records).
        if (legacySnapshots.isNotEmpty()) {
            legacySnapshots
                .sortedByDescending { it.capturedAtMs }
                .forEach { snap ->
                    LegacyAssessmentCard(
                        snap = snap,
                        date = legacyDateLabel(snap, legacySnapshots),
                        lang = lang,
                    )
                }
        }
    }
}

/** Pre-checkpoint assessment (v35-v38) rendered as a historical card.
 *  [date] is pre-formatted by the caller (seconds applied only to
 *  same-minute collisions, see [legacyDateLabel]). */
@Composable
private fun LegacyAssessmentCard(
    snap: PerformanceSnapshotEntity,
    date: String,
    lang: String,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1F2E), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                date,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                tr("progress_legacy_assessment", lang),
                color = AccentSoft,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Text(
            tr("progress_legacy_explanation", lang),
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 12.sp,
        )
        snap.coachReport?.let { report ->
            Text(
                report,
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

/** Same-minute-collision-aware date label for legacy snapshots — the same
 *  rule the checkpoint rows use: "dd/MM/yy · HH:mm", with seconds appended
 *  only when two assessments share the same minute. */
private fun legacyDateLabel(
    snap: PerformanceSnapshotEntity,
    all: List<PerformanceSnapshotEntity>,
): String {
    val minuteKey: (Long) -> Long = { ms -> ms / 60_000L }
    val collidingMinutes = all
        .groupBy { minuteKey(it.capturedAtMs) }
        .filterValues { it.size > 1 }
        .keys
    return if (minuteKey(snap.capturedAtMs) in collidingMinutes)
        DATE_TIME_SECONDS_FMT.format(Date(snap.capturedAtMs))
    else DATE_TIME_FMT.format(Date(snap.capturedAtMs))
}

internal fun Float.roundTo1Ui(lang: String): String {
    val r = kotlin.math.round(this * 10f) / 10f
    return if (r == kotlin.math.floor(r)) r.toInt().toString()
    else String.format(localeForLanguage(lang), "%.1f", r)
}

/** QA 2026-10 (second pass): converts US-style decimal separators in
 *  pre-formatted strings (e.g. the persisted "cintura -0.3pp") into the
 *  app locale's separator ("cintura -0,3pp" for pt). Display-only — stored
 *  values are never rewritten. */
internal fun localizeDecimals(text: String, lang: String): String {
    val ds = java.text.DecimalFormatSymbols(localeForLanguage(lang)).decimalSeparator
    if (ds == '.') return text
    return text.replace(Regex("(\\d)\\.(\\d)")) { m ->
        m.groupValues[1] + ds + m.groupValues[2]
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

private fun PerformanceSnapshotEntity.hasSilhouetteData(): Boolean =
    frontalShoulderRatio != null || frontalWaistRatio != null ||
        lateralChestRatio != null || lateralBellyRatio != null

private fun PerformanceSnapshotEntity.hasAngleData(): Boolean =
    leftShoulderSagittalDeg != null || leftHipSagittalDeg != null

private fun toRatioChartLine(
    kpi: SilhouetteKpi,
    snaps: List<PerformanceSnapshotEntity>,
    latestFrontalConv: String?,
    latestLateralConv: String?,
): ChartLine {
    // v40: a value is only comparable when its side used the SAME
    // normalization convention as the latest capture — a legacy
    // (image-height) ratio and a pose-person-height ratio of the "same"
    // zone are different units, so not-same-convention values are
    // excluded from the line (null gap) instead of being charted.
    val latestConv = if (kpi.isFrontal) latestFrontalConv else latestLateralConv
    val values = snaps.map { snap ->
        val conv = if (kpi.isFrontal) snap.frontalNormalization else snap.lateralNormalization
        if (SilhouetteZoneMath.sameConvention(conv, latestConv)) kpi.pick(snap)?.toDouble() else null
    }
    val nonNull = values.filterNotNull()
    val chartKpi = if (nonNull.size >= 2) {
        val delta = nonNull.last() - nonNull.first()
        ChartKpi(latest = nonNull.last(), delta = delta,
            deltaTone = when {
                // for waist/belly, decrease = positive
                kpi == SilhouetteKpi.FRONTAL_WAIST || kpi == SilhouetteKpi.LATERAL_BELLY ->
                    if (delta < -0.005) "positive" else if (delta > 0.005) "negative" else "neutral"
                else -> if (delta > 0.005) "positive" else if (delta < -0.005) "negative" else "neutral"
            }
        )
    } else null
    return ChartLine(label = kpi.label, unit = "", values = values, kpi = chartKpi)
}

// ─── Collapsible card wrapper ─────────────────────────────────────────────────
// A Surface whose titled header toggles a chevron and shows/hides [content].
// Used for the secondary Performance cards (coach report, evolution, measures,
// goal) so the silhouette stays at the top and the rest can be folded away.

@Composable
private fun CollapsibleCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    surfaceColor: Color = Color(0xFF1A1F2E),
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = surfaceColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(14.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, color = AccentSoft,
                    fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    trailing?.invoke()
                    Icon(
                        if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (expanded) "Fechar" else "Abrir",
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (expanded) {
                Spacer(Modifier.height(10.dp))
                content()
            }
        }
    }
}

// ─── Main tab composable ──────────────────────────────────────────────────────

@Composable
fun PerformanceTab(viewModel: ChatViewModel) {
    val snapshots by viewModel.performanceSnapshots.collectAsStateWithLifecycle()
    val latestGoal by viewModel.latestPerformanceGoal.collectAsStateWithLifecycle()
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val bodyPlanCheckpoints by viewModel.bodyPlanCheckpoints.collectAsStateWithLifecycle()
    // v41: the in-flight enrichment state and the visible per-checkpoint
    // errors are collected ONCE here (collectAsStateWithLifecycle) and
    // passed down as stable values — the sub-views never touch the ViewModel
    // directly, and tests can drive them with explicit values.
    val enrichingCheckpointIds by viewModel.enrichingCheckpointIds.collectAsStateWithLifecycle()
    val checkpointEnrichmentErrors by viewModel.checkpointEnrichmentErrors.collectAsStateWithLifecycle()
    val coachReportGeneratingIds by viewModel.coachReportGeneratingIds.collectAsStateWithLifecycle()
    var showAssessment by remember { mutableStateOf(false) }
    val lang = userProfile.language

    // Internal segmented navigation (v39): Visão geral | Plano | Progresso.
    var perfTab by rememberSaveable { mutableStateOf(PerfTab.OVERVIEW) }

    // Collapse state for the secondary cards (silhouette + history stay fixed).
    // Coach report starts expanded; the rest collapsed to keep the view compact.
    var coachExpanded     by rememberSaveable { mutableStateOf(true) }
    var evolutionExpanded by rememberSaveable { mutableStateOf(false) }
    var measuresExpanded  by rememberSaveable { mutableStateOf(false) }
    var goalExpanded      by rememberSaveable { mutableStateOf(false) }

    if (showAssessment) {
        SilhouetteAssessmentScreen(
            source = "manual",
            onConfirm = { entity ->
                viewModel.savePerformanceSnapshotAndRequestReport(entity)
                showAssessment = false
            },
            onDismiss = { showAssessment = false }
        )
        return
    }

    val hasGoalTargets = latestGoal?.coachTargetJson != null

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F19)),
        // QA 2026-10: extra bottom padding so the last card never sits flush
        // against the bottom nav / system bar.
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── Header ────────────────────────────────────────────────────────────
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(tr("Performance", lang), color = Color.White,
                        fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(tr("perf_silhouette_comp", lang),
                        color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp)
                }
                Button(
                    onClick = { showAssessment = true },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.CameraAlt, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Avaliar agora", fontSize = 13.sp)
                }
            }
        }

        // ── Segmented navigation (v39) ────────────────────────────────────────
        item {
            PerfSegmentedControl(selected = perfTab, onSelect = { perfTab = it }, lang = lang)
        }

        // ── Plano (v39): latest body-plan checkpoint ──────────────────────────
        if (perfTab == PerfTab.PLAN) {
            item {
                BodyPlanView(
                    checkpoints = bodyPlanCheckpoints,
                    hasGoalTargets = hasGoalTargets,
                    lang = lang,
                    enriching = enrichingCheckpointIds,
                    errors = checkpointEnrichmentErrors,
                    onRetry = { viewModel.requestCheckpointEnrichment(it) },
                    // QA 2026-10: direct goal action from the Plan empty state.
                    onOpenGoal = {
                        perfTab = PerfTab.OVERVIEW
                        goalExpanded = true
                    },
                )
            }
            return@LazyColumn
        }

        // ── Progresso (v39): checkpoint timeline ─────────────────────────────
        if (perfTab == PerfTab.PROGRESS) {
            item {
                BodyProgressView(
                    checkpoints = bodyPlanCheckpoints,
                    // Existing users may have v35-v38 assessments but no v39+
                    // checkpoints. Keep that accumulated history visible.
                    legacySnapshots = snapshots.filter { snap ->
                        bodyPlanCheckpoints.none { it.snapshotId == snap.id }
                    },
                    lang = lang,
                    enriching = enrichingCheckpointIds,
                    errors = checkpointEnrichmentErrors,
                    onRetry = { viewModel.requestCheckpointEnrichment(it) },
                )
            }
            return@LazyColumn
        }

        // ── Overview (original content) ───────────────────────────────────────
        // ── Empty state ───────────────────────────────────────────────────────
        if (snapshots.isEmpty()) {
            item { EmptyPerformanceState(onStart = { showAssessment = true }, lang = lang) }
            return@LazyColumn
        }

        val latest = snapshots.lastOrNull()
        val silhouetteSnaps = snapshots.filter { it.hasSilhouetteData() }

        // 1 ── Silhouette gap card (primary, always on top, not collapsible) ────
        if (latest != null && latest.hasSilhouetteData()) {
            val isMale = !userProfile.gender.equals("Female", ignoreCase = true)
            item {
                SilhouetteGoalCard(
                    snap = latest,
                    latestGoal = latestGoal,
                    isMale = isMale,
                    lang = lang,
                )
            }
        }

        // 2 ── Coach report (collapsible) ──────────────────────────────────────
        if (latest != null) {
            item {
                CollapsibleCard(
                    title = tr("perf_coach_report", lang),
                    expanded = coachExpanded,
                    onToggle = { coachExpanded = !coachExpanded },
                    surfaceColor = Color(0xFF1E293B),
                    trailing = {
                        Text(DATE_TIME_FMT.format(Date(latest.capturedAtMs)),
                            color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp,
                            modifier = Modifier.padding(end = 8.dp))
                    },
                ) {
                    CoachReportContent(
                        latest,
                        isGenerating = latest.id in coachReportGeneratingIds,
                        onGenerateReport = { viewModel.requestCoachReport(latest) },
                        lang = lang,
                    )
                }
            }
        }

        // 3 ── Silhouette evolution (collapsible) ──────────────────────────────
        // QA 2026-10: with exactly one silhouette capture the card was hidden
        // entirely, so users never learned WHY there was no evolution chart.
        if (silhouetteSnaps.size >= 2) {
            item {
                CollapsibleCard(
                    title = "Evolução da silhueta",
                    expanded = evolutionExpanded,
                    onToggle = { evolutionExpanded = !evolutionExpanded },
                ) {
                    SilhouetteEvolutionContent(silhouetteSnaps, lang)
                }
            }
        } else if (silhouetteSnaps.size == 1) {
            item {
                CollapsibleCard(
                    title = "Evolução da silhueta",
                    expanded = evolutionExpanded,
                    onToggle = { evolutionExpanded = !evolutionExpanded },
                ) {
                    Text(
                        tr("perf_evolution_need_two", lang),
                        color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp,
                    )
                }
            }
        } else if (snapshots.size >= 2 && latest?.hasAngleData() == true) {
            item {
                CollapsibleCard(
                    title = "Evolução (ângulos articulares)",
                    expanded = evolutionExpanded,
                    onToggle = { evolutionExpanded = !evolutionExpanded },
                ) {
                    LegacyAngleEvolutionContent(snapshots)
                }
            }
        }

        // 4 ── Silhouette measures (collapsible) ───────────────────────────────
        if (latest != null && latest.hasSilhouetteData()) {
            item {
                CollapsibleCard(
                    title = "Medidas de silhueta",
                    expanded = measuresExpanded,
                    onToggle = { measuresExpanded = !measuresExpanded },
                ) {
                    SilhouetteRatioBarsContent(latest)
                }
            }
        }

        // 5 ── Goal input (collapsible) ────────────────────────────────────────
        item {
            CollapsibleCard(
                title = "O teu objetivo",
                expanded = goalExpanded,
                onToggle = { goalExpanded = !goalExpanded },
                surfaceColor = Color(0xFF1A2236),
                trailing = {
                    if (latestGoal?.coachTargetJson != null) {
                        Text("✓", color = Color(0xFF4ADE80), fontSize = 12.sp,
                            modifier = Modifier.padding(end = 8.dp))
                    }
                },
            ) {
                PerformanceGoalInputContent(
                    currentGoal = latestGoal,
                    onSave = { viewModel.savePerformanceGoal(it) },
                )
            }
        }

        // ── Backward compat: angle table for v35-only snapshots ───────────────
        if (latest != null && latest.hasAngleData() && !latest.hasSilhouetteData()) {
            item { LatestSnapshotCard(latest) }
        }

        // 6 ── History ─────────────────────────────────────────────────────────
        if (snapshots.size > 1) {
            item {
                Text("Avaliações anteriores",
                    color = Color.White.copy(alpha = 0.75f),
                    fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }
            items(snapshots.dropLast(1).reversed()) { snap ->
                SnapshotHistoryRow(snap)
            }
        }
    }
}

// ─── Coach report content (inside CollapsibleCard) ────────────────────────────

@Composable
private fun CoachReportContent(
    snap: PerformanceSnapshotEntity,
    isGenerating: Boolean,
    onGenerateReport: () -> Unit,
    lang: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            snap.coachReport != null -> {
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    RichText(
                        modifier = Modifier.fillMaxWidth(),
                        style = com.halilibo.richtext.ui.RichTextStyle(
                            stringStyle = com.halilibo.richtext.ui.string.RichTextStringStyle(
                                boldStyle = androidx.compose.ui.text.SpanStyle(
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                ),
                                italicStyle = androidx.compose.ui.text.SpanStyle(
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                    color = Color.White
                                ),
                            ),
                        )
                    ) {
                        Markdown(content = snap.coachReport)
                    }
                }
            }
            isGenerating -> {
                // QA 2026-10: explicit PENDING — the report job is tracked by
                // the ViewModel, so a spinner is only shown while it is
                // genuinely running. No competing retry button.
                Text(tr("progress_report_pending", lang),
                    color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                LinearProgressIndicator(
                    color = AccentSoft,
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                )
            }
            snap.hasSilhouetteData() -> {
                // Idle: generation is NOT running (failed, or the app
                // restarted mid-flight) — offer the retry as the single action.
                Text(tr("progress_report_idle", lang),
                    color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                OutlinedButton(
                    onClick = onGenerateReport,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentSoft),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(tr("progress_report_generate", lang), fontSize = 12.sp)
                }
            }
            else -> {
                Text(
                    tr("progress_report_need_photos", lang),
                    color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp,
                )
                OutlinedButton(
                    onClick = onGenerateReport,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentSoft),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(tr("progress_report_generate", lang), fontSize = 12.sp)
                }
            }
        }
    }
}

// ─── Silhouette ratio bars content (inside CollapsibleCard) ───────────────────

@Composable
private fun SilhouetteRatioBarsContent(snap: PerformanceSnapshotEntity) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val frontalRows = listOfNotNull(
            snap.frontalShoulderRatio?.let { "Ombros (frontal)" to it },
            snap.frontalWaistRatio?.let    { "Cintura (frontal)" to it },
            snap.frontalHipRatio?.let      { "Ancas (frontal)" to it },
            snap.frontalThighRatio?.let    { "Coxas (frontal)" to it },
        )
        val lateralRows = listOfNotNull(
            snap.lateralChestRatio?.let   { "Peito (lateral)" to it },
            snap.lateralBellyRatio?.let   { "Barriga (lateral)" to it },
            snap.lateralThighRatio?.let   { "Coxa (lateral)" to it },
        )
        val allRows = frontalRows + lateralRows
        val maxVal = allRows.maxOfOrNull { it.second } ?: 1f

        allRows.forEach { (label, value) ->
            RatioBar(label = label, value = value, maxValue = maxVal)
        }
    }
}

@Composable
private fun RatioBar(label: String, value: Float, maxValue: Float) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp)
            Text("%.3f".format(value), color = AccentSoft, fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(2.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).background(Color.White.copy(0.08f),
            RoundedCornerShape(3.dp))) {
            val fraction = (value / maxValue).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight()
                .background(AccentSoft, RoundedCornerShape(3.dp)))
        }
        Spacer(Modifier.height(6.dp))
    }
}

// ─── Silhouette evolution content (inside CollapsibleCard) ────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SilhouetteEvolutionContent(snaps: List<PerformanceSnapshotEntity>, lang: String) {
    var selectedKpis by remember {
        mutableStateOf(setOf(SilhouetteKpi.FRONTAL_WAIST, SilhouetteKpi.FRONTAL_HIP))
    }
    val latestConv = snaps.lastOrNull()
    // v40: how many captures carry a different normalization convention on
    // at least one side than the latest — those values are not same-unit
    // data and are excluded from the lines below.
    val mixedCount = latestConv?.let { lt ->
        snaps.count { s ->
            !SilhouetteZoneMath.sameConvention(s.frontalNormalization, lt.frontalNormalization) ||
                !SilhouetteZoneMath.sameConvention(s.lateralNormalization, lt.lateralNormalization)
        }
    } ?: 0
    Column {
        // KPI chips — FlowRow so chips wrap instead of overflowing
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            SilhouetteKpi.entries.forEach { kpi ->
                val on = kpi in selectedKpis
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (on) AccentPrimary else Color.White.copy(alpha = 0.08f),
                    modifier = Modifier.clickable {
                        selectedKpis = if (on) {
                            if (selectedKpis.size > 1) selectedKpis - kpi else selectedKpis
                        } else {
                            if (selectedKpis.size < 4) selectedKpis + kpi else selectedKpis
                        }
                    },
                ) {
                    Text(
                        kpi.label,
                        color = if (on) Color.White else Color.White.copy(alpha = 0.72f),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (mixedCount > 0) {
            Text(
                tr("perf_convention_mixed", lang).replace("{n}", "$mixedCount"),
                color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp,
            )
            Spacer(Modifier.height(6.dp))
        }
        val xLabels = snaps.map { DATE_FMT.format(Date(it.capturedAtMs)) }
        val lines = selectedKpis.map {
            toRatioChartLine(it, snaps, latestConv?.frontalNormalization, latestConv?.lateralNormalization)
        }
        if (lines.isNotEmpty()) {
            MultilineChart(lines = lines, xLabels = xLabels, showLatestValue = true)
        }
    }
}

// ─── Legacy angle chart content (v35 backward compat) ────────────────────────

@Composable
private fun LegacyAngleEvolutionContent(snaps: List<PerformanceSnapshotEntity>) {
    var selected by remember {
        mutableStateOf(setOf(MobilityKpi.LEFT_HIP_SAG, MobilityKpi.RIGHT_HIP_SAG))
    }
    Column {
        KpiSelector(selected = selected, onToggle = { kpi ->
            selected = if (kpi in selected) {
                if (selected.size > 1) selected - kpi else selected
            } else {
                if (selected.size < 4) selected + kpi else selected
            }
        })
        Spacer(Modifier.height(10.dp))
        val xLabels = snaps.map { DATE_FMT.format(Date(it.capturedAtMs)) }
        val lines = selected.map { kpi ->
            val values = snaps.map { kpi.pick(it)?.toDouble() }
            val nonNull = values.filterNotNull()
            val chartKpi = if (nonNull.size >= 2) {
                val delta = nonNull.last() - nonNull.first()
                ChartKpi(latest = nonNull.last(), delta = delta,
                    deltaTone = if (delta > 0.5) "positive" else if (delta < -0.5) "negative" else "neutral")
            } else null
            ChartLine(label = kpi.label, unit = kpi.unit, values = values, kpi = chartKpi)
        }
        if (lines.isNotEmpty()) {
            MultilineChart(lines = lines, xLabels = xLabels, showLatestValue = true)
        }
    }
}

// ─── Empty state ──────────────────────────────────────────────────────────────

@Composable
private fun EmptyPerformanceState(onStart: () -> Unit, lang: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1A1F2E),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.FitnessCenter, null, tint = AccentSoft,
                modifier = Modifier.size(48.dp))
            Text(tr("perf_silhouette_analysis", lang), color = Color.White,
                fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(
                tr("perf_silhouette_analysis_desc", lang),
                color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp,
            )
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Default.CameraAlt, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr("perf_start_assessment", lang))
            }
        }
    }
}

// ─── Legacy joint angle selector (kept for v35 compat) ───────────────────────

@Composable
private fun KpiSelector(selected: Set<MobilityKpi>, onToggle: (MobilityKpi) -> Unit) {
    val joints = listOf(
        "Ombro" to listOf(MobilityKpi.LEFT_SHOULDER_SAG, MobilityKpi.LEFT_SHOULDER_FRO,
            MobilityKpi.RIGHT_SHOULDER_SAG, MobilityKpi.RIGHT_SHOULDER_FRO),
        "Anca" to listOf(MobilityKpi.LEFT_HIP_SAG, MobilityKpi.LEFT_HIP_FRO,
            MobilityKpi.RIGHT_HIP_SAG, MobilityKpi.RIGHT_HIP_FRO),
        "Cotovelo" to listOf(MobilityKpi.LEFT_ELBOW_SAG, MobilityKpi.RIGHT_ELBOW_SAG),
        "Joelho" to listOf(MobilityKpi.LEFT_KNEE_SAG, MobilityKpi.RIGHT_KNEE_SAG),
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        joints.forEach { (group, kpis) ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(group, color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp,
                    modifier = Modifier.width(52.dp))
                kpis.forEach { kpi ->
                    val on = kpi in selected
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (on) AccentPrimary else Color.White.copy(alpha = 0.08f),
                        modifier = Modifier.clickable { onToggle(kpi) },
                    ) {
                        Text(kpi.label.removePrefix("$group "),
                            color = if (on) Color.White else Color.White.copy(alpha = 0.72f),
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
            }
        }
    }
}

// ─── Latest angle snapshot card (legacy, v35 snapshots) ──────────────────────

@Composable
private fun LatestSnapshotCard(snap: PerformanceSnapshotEntity) {
    Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF1A1F2E),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Ângulos articulares", color = AccentSoft,
                    fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(DATE_TIME_FMT.format(Date(snap.capturedAtMs)),
                    color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
            }
            Spacer(Modifier.height(10.dp))
            val rows = listOf(
                "Ombro E (Sag)" to snap.leftShoulderSagittalDeg,
                "Ombro E (Fro)" to snap.leftShoulderFrontalDeg,
                "Ombro D (Sag)" to snap.rightShoulderSagittalDeg,
                "Ombro D (Fro)" to snap.rightShoulderFrontalDeg,
                "Anca E (Sag)"  to snap.leftHipSagittalDeg,
                "Anca E (Fro)"  to snap.leftHipFrontalDeg,
                "Anca D (Sag)"  to snap.rightHipSagittalDeg,
                "Anca D (Fro)"  to snap.rightHipFrontalDeg,
                "Cotovelo E"    to snap.leftElbowSagittalDeg,
                "Cotovelo D"    to snap.rightElbowSagittalDeg,
                "Joelho E"      to snap.leftKneeSagittalDeg,
                "Joelho D"      to snap.rightKneeSagittalDeg,
            ).filter { it.second != null }
            rows.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth()) {
                    pair.forEach { (label, value) ->
                        Row(Modifier.weight(1f).padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp,
                                modifier = Modifier.weight(1f))
                            Text("${value?.toInt()}°", color = Color.White,
                                fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

// ─── History row ──────────────────────────────────────────────────────────────

@Composable
private fun SnapshotHistoryRow(snap: PerformanceSnapshotEntity) {
    Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFF151A27),
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            // QA 2026-10: include the time — same-day captures no longer read
            // as duplicate rows.
            Text(DATE_TIME_FMT.format(Date(snap.capturedAtMs)),
                color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
            val summary = when {
                snap.hasSilhouetteData() -> listOfNotNull(
                    snap.frontalWaistRatio?.let { "Cin: ${"%.3f".format(it)}" },
                    snap.frontalHipRatio?.let   { "Anc: ${"%.3f".format(it)}" },
                ).joinToString("  ")
                snap.hasAngleData() -> listOfNotNull(
                    snap.leftHipSagittalDeg?.let  { "Anca E: ${it.toInt()}°" },
                    snap.rightHipSagittalDeg?.let { "Anca D: ${it.toInt()}°" },
                ).joinToString("  ")
                else -> "—"
            }
            Text(summary.ifEmpty { "—" }, color = AccentSoft.copy(alpha = 0.8f), fontSize = 12.sp)
        }
    }
}
