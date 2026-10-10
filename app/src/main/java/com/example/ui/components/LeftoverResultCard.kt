package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.FoodEntry
import com.example.data.remote.FoodAnalysisResult
import com.example.ui.theme.AccentPrimary

/**
 * Card showing leftovers analysis result with breakdown of what remained uneaten.
 * Shows: original macros, leftover macros, and calculated consumed amount.
 *
 * @param original The original meal entry before leftovers
 * @param leftovers Analysis result of the leftover food
 * @param consumedPreview Preview of what will be saved (original - leftovers)
 * @param hasWarning If true, shows warning badge (e.g. leftovers > 80% original)
 * @param warningText Optional warning message to display
 * @param onConfirm Called when user confirms the adjustment
 * @param onCancel Called when user cancels
 * @param tr Translation function (key, language) -> String
 * @param language Current user language
 */
@Composable
fun LeftoverResultCard(
    original: FoodEntry,
    leftovers: FoodAnalysisResult,
    consumedPreview: FoodEntry,
    hasWarning: Boolean,
    warningText: String?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    tr: (String, String) -> String,
    language: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        border = BorderStroke(
            width = 1.5.dp,
            color = if (hasWarning) Color(0xFFF59E0B) else AccentPrimary.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tr("leftover_analysis_result", language),
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                if (hasWarning) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFFF59E0B).copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "⚠ ${tr("Warning", language)}",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            color = Color(0xFFF59E0B),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Warning message if present
            if (hasWarning && warningText != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFF59E0B).copy(alpha = 0.1f)
                ) {
                    Text(
                        text = warningText,
                        modifier = Modifier.padding(10.dp),
                        color = Color(0xFFE2E8F0),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            Divider(color = Color(0xFF334155), thickness = 1.dp)

            // Leftovers detected
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = tr("leftovers_detected", language),
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                // Macros of leftovers
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MacroChip(
                        label = "${leftovers.calories ?: 0}",
                        unit = "kcal",
                        color = Color(0xFFEAB308)
                    )
                    MacroChip(
                        label = "${String.format("%.1f", leftovers.protein ?: 0.0)}",
                        unit = "P",
                        color = Color(0xFFEF4444)
                    )
                    MacroChip(
                        label = "${String.format("%.1f", leftovers.carbs ?: 0.0)}",
                        unit = "C",
                        color = Color(0xFFA78BFA)
                    )
                    MacroChip(
                        label = "${String.format("%.1f", leftovers.fat ?: 0.0)}",
                        unit = "F",
                        color = Color(0xFFFBBF24)
                    )
                }

                // Ingredient breakdown if available
                val ingredients = leftovers.ingredients ?: emptyList()
                if (ingredients.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        ingredients.take(5).forEach { ing ->
                            Text(
                                text = "− ${ing.weightGrams.toInt()}g ${ing.name}",
                                color = Color(0xFF64748B),
                                fontSize = 11.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                        if (ingredients.size > 5) {
                            Text(
                                text = "+ ${ingredients.size - 5} ${tr("more", language)}",
                                color = Color(0xFF475569),
                                fontSize = 10.sp,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                            )
                        }
                    }
                }
            }

            Divider(color = Color(0xFF334155), thickness = 1.dp)

            // Consumed calculation
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = tr("consumed_calculated", language),
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                // Show: Original → Consumed (difference)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "${original.calories} → ${consumedPreview.calories}",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "(−${(original.calories ?: 0) - (consumedPreview.calories ?: 0)} kcal)",
                            color = AccentPrimary,
                            fontSize = 12.sp
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "P: ${String.format("%.1f", consumedPreview.protein)}g",
                            color = Color(0xFFCBD5E1),
                            fontSize = 11.sp
                        )
                        Text(
                            text = "C: ${String.format("%.1f", consumedPreview.carbs)}g",
                            color = Color(0xFFCBD5E1),
                            fontSize = 11.sp
                        )
                        Text(
                            text = "F: ${String.format("%.1f", consumedPreview.fat)}g",
                            color = Color(0xFFCBD5E1),
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFF475569))
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = null,
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        tr("Cancel", language),
                        color = Color(0xFFCBD5E1),
                        fontSize = 14.sp
                    )
                }

                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentPrimary,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        tr("button_confirm_adjustment", language),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun MacroChip(
    label: String,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                color = color,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = unit,
                color = color.copy(alpha = 0.7f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun HorizontalDivider(
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF334155),
    thickness: androidx.compose.ui.unit.Dp = 1.dp
) {
    androidx.compose.material3.Divider(
        modifier = modifier,
        color = color,
        thickness = thickness
    )
}

/**
 * Parsed leftovers block for the persistent card.
 * [headerLine] is the "Total sobras: N kcal" line; [bulletLines] are the
 * per-ingredient "• name (~Xg)" lines.
 */
data class ParsedLeftovers(
    val headerLine: String,
    val bulletLines: List<String>
)

/**
 * Parses the structured leftovers block text stored in the meal's snapshot
 * ([com.example.data.local.LeftoverOriginalSnapshot.leftoverBlockText]). The
 * block is "Total sobras: N kcal\n• name (~Xg)\n…". Returns null when blank
 * (meal never adjusted) so the caller can skip the card. 2026-10-10 — replaces
 * the previous fullSummary-section parser; the summary no longer embeds this.
 */
fun parseLeftoversFromBlock(blockText: String?): ParsedLeftovers? {
    if (blockText.isNullOrBlank()) return null
    val lines = blockText.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) return null
    val header = lines.first()
    val bullets = lines.drop(1).filter { it.startsWith("•") }
    return ParsedLeftovers(headerLine = header, bulletLines = bullets)
}

/**
 * Read-only card showing leftovers that were previously registered for a meal,
 * rendered persistently in the edit screen directly below the summary box. The
 * X button requests deletion (the caller shows a confirmation dialog, then
 * reverts the meal to its pre-leftovers state). 2026-10-10.
 */
@Composable
fun PersistedLeftoverCard(
    parsed: ParsedLeftovers,
    onDelete: () -> Unit,
    tr: (String, String) -> String,
    language: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        border = BorderStroke(1.dp, AccentPrimary.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = AccentPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = tr("leftover_registered_title", language),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                // X — delete leftovers / revert to original (caller confirms first)
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = tr("button_delete", language),
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            HorizontalDivider()

            // Header (Total sobras: N kcal)
            Text(
                text = parsed.headerLine,
                color = Color(0xFFCBD5E1),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )

            // One bullet per line
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                parsed.bulletLines.forEach { bullet ->
                    Text(
                        text = bullet,
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
