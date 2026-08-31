package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.meal.FoodAnalysisProgress
import com.example.ui.localeForLanguage
import com.example.ui.theme.AccentSoft
import com.example.ui.tr

/**
 * Replacement for the static "Analysing nutrition via AI… / Estimating
 * nutrition stats based on visual data" card in the Activity tab capture
 * flow. Renders the same viewfinder-style box (rounded corners, gradient
 * border, dark fill) but the title + subtitle are driven by the live
 * [phaseKey] and [progress] coming out of
 * [com.example.data.meal.MealAnalysisOrchestrator] — so the user actually
 * sees what the engine is doing instead of a 5–15 s silent spinner.
 *
 * The component is a self-contained replacement for the old
 * `if (isAnalyzing) { CircularProgressIndicator + 2 Texts }` block; callers
 * just wrap it in the existing viewfinder Box.
 */
@Composable
fun FoodAnalysisProgressCard(
    phaseKey: String?,
    progress: FoodAnalysisProgress,
    language: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val title = tr(phaseKey ?: "phase_analyzing_meal", language)
    val subtitle = buildSubtitle(progress, language)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxSize()
    ) {
        CircularProgressIndicator(color = AccentSoft, strokeWidth = 3.dp)
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = Color.White
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF94A3B8)
        )

        // Chips strip — appears progressively as ingredient names are pulled
        // out of the SSE buffer. Capped at 6 to keep the card compact; the
        // rest will land in the edit-meal screen once the stream finishes.
        if (progress.ingredientsSoFar.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(progress.ingredientsSoFar.take(6)) { name ->
                    IngredientChip(name)
                }
                if (progress.ingredientsSoFar.size > 6) {
                    item {
                        IngredientChip("+${progress.ingredientsSoFar.size - 6}")
                    }
                }
            }
        }

        TextButton(onClick = onCancel) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = null,
                tint = Color(0xFFCBD5E1),
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = tr("cancel_analysis", language),
                color = Color(0xFFCBD5E1),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun IngredientChip(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1E293B))
            .border(
                BorderStroke(1.dp, AccentSoft.copy(alpha = 0.4f)),
                RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            color = Color(0xFFE2E8F0),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
    }
}

/**
 * Pick the most informative status line given how much the orchestrator
 * has parsed so far. Falls back to the original static copy when the
 * stream hasn't begun emitting partials yet.
 */
private fun buildSubtitle(progress: FoodAnalysisProgress, language: String): String {
    val locale = localeForLanguage(language)
    return when {
        !progress.name.isNullOrBlank() ->
            String.format(locale, tr("subtitle_identified", language), progress.name)
        progress.ingredientsSoFar.isNotEmpty() ->
            String.format(
                locale,
                tr("subtitle_ingredients_count", language),
                progress.ingredientsSoFar.size
            )
        progress.rawBytesReceived > 0 ->
            tr("subtitle_receiving_data", language)
        else ->
            // Same copy as the legacy card, now translated.
            tr("Estimating nutrition stats based on visual data", language)
    }
}
