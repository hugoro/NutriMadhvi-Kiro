package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.tr
import androidx.compose.foundation.layout.Box

/**
 * Small coloured pill that surfaces the LLM's self-rated confidence in
 * the meal analysis (low / medium / high). Rendered above the meal
 * description in the edit-meal screen so the user knows whether to take
 * the macros at face value or scrutinize the assumptions.
 *
 * Colours match the dashboard's traffic-light convention:
 *   high   → emerald
 *   medium → amber
 *   low    → red
 *
 * The chip word (Alta / Média / Baixa) is translated app-side via
 * [tr] so it stays consistent with the rest of the UI even if the model
 * emits the canonical English `high|medium|low` literal.
 *
 * When [escalated] is true, a small adjacent badge names the LLM that
 * actually produced this result (e.g. "gemini-3.1-pro-preview") instead of
 * the previous hardcoded "Pro" label — useful once more than one provider
 * can be behind an escalation (see the ensemble scaffolding), and honest
 * even today since "Pro" alone didn't say *which* Gemini tier answered.
 */
@Composable
fun ConfidenceChip(
    level: String,
    language: String,
    modifier: Modifier = Modifier,
    escalated: Boolean = false,
    modelName: String? = null
) {
    val normalized = level.trim().lowercase()
    val (dotColor, textColor, borderColor, label) = when (normalized) {
        "high" -> Quad(
            Color(0xFF10B981),
            Color(0xFFA7F3D0),
            Color(0xFF065F46),
            tr("High", language)
        )
        "medium" -> Quad(
            Color(0xFFF59E0B),
            Color(0xFFFCD34D),
            Color(0xFF78350F),
            tr("Medium", language)
        )
        "low" -> Quad(
            Color(0xFFEF4444),
            Color(0xFFFCA5A5),
            Color(0xFF7F1D1D),
            tr("Low", language)
        )
        else -> return  // unknown value → render nothing rather than a fallback chip
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0x14FFFFFF))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${tr("Confidence", language)}: $label",
                color = textColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (escalated) {
            Spacer(Modifier.width(6.dp))
            // Indigo-violet pill matching AccentPrimary; Color.White as
            // contentColor is set explicitly (the auto-derive on top of a
            // custom background falls back to a grey that has no contrast).
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF6366F1))
                    .padding(horizontal = 7.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = modelDisplayName(modelName),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
    // borderColor reserved for a future outlined variant — kept in the
    // tuple to make adding a tonal background straightforward later.
    @Suppress("UNUSED_EXPRESSION") borderColor
}

private data class Quad(
    val first: Color,
    val second: Color,
    val third: Color,
    val fourth: String
)

/**
 * Shortens a raw model id into something that fits a small badge while
 * staying identifiable, e.g. `gemini-3.1-pro-preview` → `Gemini 3.1 Pro`,
 * `qwen/qwen3.6-27b` → `Qwen 3.6`. Falls back to "Pro" only when the model
 * name is entirely missing, so an escalation is never shown as blank.
 */
internal fun modelDisplayName(rawModel: String?): String {
    val model = rawModel?.trim()?.lowercase().orEmpty()
    if (model.isEmpty()) return "Pro"
    return when {
        model.startsWith("gemini") -> {
            val version = Regex("gemini-?(\\d+\\.\\d+)").find(model)?.groupValues?.get(1)
            val tier = when {
                model.contains("pro") -> "Pro"
                model.contains("flash-lite") -> "Flash Lite"
                model.contains("flash") -> "Flash"
                else -> null
            }
            listOfNotNull("Gemini", version, tier).joinToString(" ")
        }
        model.contains("qwen") -> {
            val version = Regex("qwen(\\d+\\.\\d+)").find(model)?.groupValues?.get(1)
            listOfNotNull("Qwen", version).joinToString(" ")
        }
        else -> rawModel!!
    }
}
