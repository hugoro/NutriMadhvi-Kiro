package com.example.ui.components

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.BuildConfig
import com.example.data.health.ShWorkout
import com.example.data.local.ActivityEntry
import com.example.data.local.ActivityType
import com.example.data.local.FoodEntry
import com.example.ui.tr
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/**
 * Popup that itemises what added up to a Dashboard macro bar. Rendered on
 * top of the Dashboard when the user taps a bar; dismissed on outside-tap,
 * the X icon, or Back. See plan-adding-a-new-radiant-candy.md for the full
 * spec + honesty rules.
 *
 * The bar at the top of the dialog is rendered by the SAME composables the
 * Dashboard uses ([com.example.ui.MacroProgressIndicator] +
 * [com.example.ui.MacroProgressIndicatorSplit]) so what the user sees in
 * the popup matches the row they tapped exactly. The Calories case has a
 * bespoke card-style bar on the Dashboard (Consumed / Burned / Net); the
 * popup renders a stripped-down amber replica ([CalorieHeaderBar]) that
 * still communicates "consumed vs goal" without duplicating the Net math.
 *
 * @param onEditMeal invoked when a row in the Meals table (CALORIES) or
 *   any meal-level row that carries a foodEntryId is tapped. Callers wire
 *   this to `viewModel.setEditingFoodEntry(...) + onNavigateToTab(ACTIVITY,
 *   FOOD)` — the same two-call pattern History uses.
 */
@Composable
fun MacroBreakdownDialog(
    kind: MacroKind,
    foods: List<FoodEntry>,
    nonOverlappingManuals: List<ActivityEntry>,
    shWorkouts: List<ShWorkout>,
    shAvailable: Boolean,
    periodDateRange: ClosedRange<LocalDate>,
    zoneId: ZoneId,
    target: Double,
    totalCarbs: Double,
    totalFreeSugars: Double,
    hasAnyFreeSugarsData: Boolean,
    totalProtein: Double,
    totalFat: Double,
    totalSatFat: Double,
    hasAnySatFatData: Boolean,
    totalMealsKcal: Int,
    calorieTargetGoal: Int,
    language: String,
    onEditMeal: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val breakdown = remember(kind, foods, nonOverlappingManuals, shWorkouts, shAvailable, periodDateRange) {
        when (kind) {
            MacroKind.PROTEIN -> computeMacroRows(kind, foods)
            MacroKind.CARBS -> computeCarbsRows(foods)
            MacroKind.FAT -> computeFatRows(foods)
            MacroKind.CALORIES -> computeCaloriesRows(
                foods = foods,
                manualActivities = nonOverlappingManuals,
                shWorkouts = shWorkouts,
                shAvailable = shAvailable,
                periodDateRange = periodDateRange,
                zoneId = zoneId,
                activityTypeLabel = { t -> tr(t.displayKey, language) }
            )
        }
    }

    // Debug honesty assertion — matches the invariants documented in the plan.
    // Kept behind BuildConfig.DEBUG so release binaries pay nothing.
    if (BuildConfig.DEBUG) {
        val sum = breakdown.primary.sumOf { it.amount } + breakdown.secondary.sumOf { it.amount }
        val expected = when (kind) {
            MacroKind.CARBS -> totalCarbs
            MacroKind.PROTEIN -> totalProtein
            MacroKind.FAT -> totalFat
            // For CALORIES the table sums to meals + (Σ manual kcal + Σ workout kcal).
            // NOT to the amber Burned figure — the ambient residual (steps × 0.015
            // + passive movement) lives outside per-row attribution, same as Análise.
            MacroKind.CALORIES -> {
                val actKcal = breakdown.secondary.sumOf { it.amount }
                (totalMealsKcal + actKcal.toInt()).toDouble()
            }
        }
        if (abs(sum - expected) > 0.5) {
            Log.w(
                "MacroBreakdown",
                "Honesty violation: kind=$kind sum=$sum bar=$expected"
            )
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        // Default Dialog width ≈ 280dp on phones (Material default). Turning off
        // the platform default lets the Card fill 95% of the screen — much more
        // room for meal + ingredient names without ellipsis. Height stays capped
        // at 640dp so long lists still scroll inside the card.
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .heightIn(max = 640.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            border = BorderStroke(1.dp, Color(0xFF334155))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = tr(titleKeyFor(kind), language),
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = tr("macro_breakdown_close", language),
                            tint = Color(0xFF94A3B8)
                        )
                    }
                }

                // Bar section — identical to Dashboard rendering.
                when (kind) {
                    MacroKind.CARBS -> {
                        if (hasAnyFreeSugarsData) {
                            com.example.ui.MacroProgressIndicatorSplit(
                                name = tr("Carbohydrates", language),
                                actualPrimary = (totalCarbs - totalFreeSugars).coerceAtLeast(0.0),
                                actualSecondary = totalFreeSugars.coerceAtMost(totalCarbs),
                                target = target,
                                colorPrimary = Color(0xFF8B5CF6),
                                colorSecondary = Color(0xFFC4B5FD),
                                language = language
                            )
                        } else {
                            com.example.ui.MacroProgressIndicator(
                                name = tr("Carbohydrates", language),
                                actual = totalCarbs,
                                target = target,
                                color = Color(0xFF8B5CF6),
                                language = language
                            )
                        }
                    }
                    MacroKind.PROTEIN -> com.example.ui.MacroProgressIndicator(
                        name = tr("Protein", language),
                        actual = totalProtein,
                        target = target,
                        color = Color(0xFFF97316),
                        language = language
                    )
                    MacroKind.FAT -> {
                        if (hasAnySatFatData) {
                            com.example.ui.MacroProgressIndicatorSplit(
                                name = tr("Fat", language),
                                actualPrimary = (totalFat - totalSatFat).coerceAtLeast(0.0),
                                actualSecondary = totalSatFat.coerceAtMost(totalFat),
                                target = target,
                                colorPrimary = Color(0xFFEAB308),
                                colorSecondary = Color(0xFFFDE68A),
                                language = language
                            )
                        } else {
                            com.example.ui.MacroProgressIndicator(
                                name = tr("Fat", language),
                                actual = totalFat,
                                target = target,
                                color = Color(0xFFEAB308),
                                language = language
                            )
                        }
                    }
                    MacroKind.CALORIES -> CalorieHeaderBar(
                        consumed = totalMealsKcal,
                        goal = calorieTargetGoal
                    )
                }

                // Tables. For CARBS and CALORIES the section header carries
                // the sub-group's share of the two-table total ("BOAS FONTES
                // (80%)") so the split is legible before the user reads a
                // single row. Denominator = primary + secondary summed —
                // gives an intra-popup ratio that always adds to 100%.
                val primaryTotal = breakdown.primary.sumOf { it.amount }
                val secondaryTotal = breakdown.secondary.sumOf { it.amount }
                val comboTotal = primaryTotal + secondaryTotal
                val primaryPct = if (comboTotal > 0.0)
                    (primaryTotal / comboTotal * 100.0).toInt() else 0
                val secondaryPct = if (comboTotal > 0.0)
                    100 - primaryPct else 0
                when (kind) {
                    MacroKind.CARBS -> {
                        SectionHeader(
                            tr("macro_breakdown_carbs_good_title", language),
                            percent = primaryPct
                        )
                        MacroContributionTable(
                            kind = kind,
                            variant = TableVariant.STANDARD,
                            rows = breakdown.primary,
                            language = language,
                            onRowClick = { row -> row.foodEntryId?.let(onEditMeal) },
                            modifier = Modifier.heightIn(max = 220.dp)
                        )
                        Divider(color = Color(0xFF334155))
                        SectionHeader(
                            tr("macro_breakdown_carbs_bad_title", language),
                            percent = secondaryPct
                        )
                        MacroContributionTable(
                            kind = kind,
                            variant = TableVariant.STANDARD,
                            rows = breakdown.secondary,
                            language = language,
                            onRowClick = { row -> row.foodEntryId?.let(onEditMeal) },
                            modifier = Modifier.heightIn(max = 220.dp)
                        )
                    }
                    MacroKind.CALORIES -> {
                        SectionHeader(
                            tr("macro_breakdown_meals_title", language),
                            percent = primaryPct
                        )
                        MacroContributionTable(
                            kind = kind,
                            variant = TableVariant.CALORIES_MEALS,
                            rows = breakdown.primary,
                            language = language,
                            onRowClick = { row ->
                                row.foodEntryId?.let(onEditMeal)
                            },
                            modifier = Modifier.heightIn(max = 220.dp)
                        )
                        Divider(color = Color(0xFF334155))
                        SectionHeader(
                            tr("macro_breakdown_activity_title", language),
                            percent = secondaryPct
                        )
                        MacroContributionTable(
                            kind = kind,
                            variant = TableVariant.CALORIES_ACTIVITY,
                            rows = breakdown.secondary,
                            language = language,
                            onRowClick = null,
                            modifier = Modifier.heightIn(max = 220.dp)
                        )
                    }
                    MacroKind.FAT -> {
                        if (hasAnySatFatData) {
                            SectionHeader(
                                tr("macro_breakdown_fat_unsat_title", language),
                                percent = primaryPct
                            )
                            MacroContributionTable(
                                kind = kind,
                                variant = TableVariant.STANDARD,
                                rows = breakdown.primary,
                                language = language,
                                onRowClick = { row -> row.foodEntryId?.let(onEditMeal) },
                                modifier = Modifier.heightIn(max = 220.dp)
                            )
                            Divider(color = Color(0xFF334155))
                            SectionHeader(
                                tr("macro_breakdown_fat_sat_title", language),
                                percent = secondaryPct
                            )
                            MacroContributionTable(
                                kind = kind,
                                variant = TableVariant.STANDARD,
                                rows = breakdown.secondary,
                                language = language,
                                onRowClick = { row -> row.foodEntryId?.let(onEditMeal) },
                                modifier = Modifier.heightIn(max = 220.dp)
                            )
                        } else {
                            // Legacy day: no meal in the window carries
                            // saturatedFatG. Render the single table like Protein
                            // does — primary holds every row, secondary is empty.
                            MacroContributionTable(
                                kind = kind,
                                variant = TableVariant.STANDARD,
                                rows = breakdown.primary,
                                language = language,
                                onRowClick = { row -> row.foodEntryId?.let(onEditMeal) },
                                modifier = Modifier.heightIn(max = 460.dp)
                            )
                        }
                    }
                    MacroKind.PROTEIN -> MacroContributionTable(
                        kind = kind,
                        variant = TableVariant.STANDARD,
                        rows = breakdown.primary,
                        language = language,
                        onRowClick = { row -> row.foodEntryId?.let(onEditMeal) },
                        modifier = Modifier.heightIn(max = 460.dp)
                    )
                }
            }
        }
    }
}

private fun titleKeyFor(kind: MacroKind): String = when (kind) {
    MacroKind.PROTEIN -> "macro_breakdown_title_protein"
    MacroKind.CARBS -> "macro_breakdown_title_carbs"
    MacroKind.FAT -> "macro_breakdown_title_fat"
    MacroKind.CALORIES -> "macro_breakdown_title_calories"
}

/**
 * The four column layouts the table can render. See plan §"MacroContributionTable"
 * for the visual spec; each variant is tuned to the data it shows.
 */
private enum class TableVariant { STANDARD, CALORIES_MEALS, CALORIES_ACTIVITY }

/**
 * Small section title above each table. 10sp uppercase letter-spaced label
 * mirrors the "TODAY'S CONSUMPTION" mini-header style used elsewhere on
 * the Dashboard so the popup feels native.
 *
 * When [percent] is non-null it's appended in parentheses so the user sees
 * the sub-group's share of the two-table total at a glance — e.g. "BOAS
 * FONTES (80%)". Pass null (or use the single-table macros) to render
 * just the label.
 */
@Composable
private fun SectionHeader(text: String, percent: Int? = null) {
    val label = if (percent != null) "$text (${percent}%)" else text
    Text(
        text = label.uppercase(Locale.getDefault()),
        color = Color(0xFF94A3B8),
        style = MaterialTheme.typography.labelSmall,
        letterSpacing = 1.2.sp,
        fontWeight = FontWeight.SemiBold
    )
}

/** One header cell — dimmer secondary text, small size, one-line ellipsis
 *  so a translated column label never breaks the row layout. Consolidates
 *  the repeated Text(...) block across the three table variants. */
@Composable
private fun HeaderCell(
    text: String,
    modifier: Modifier = Modifier,
    align: TextAlign = TextAlign.Start
) {
    Text(
        text = text,
        modifier = modifier,
        color = Color(0xFF64748B),
        style = MaterialTheme.typography.labelSmall,
        textAlign = align,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun MacroContributionTable(
    kind: MacroKind,
    variant: TableVariant,
    rows: List<MacroContributionRow>,
    language: String,
    onRowClick: ((MacroContributionRow) -> Unit)?,
    modifier: Modifier = Modifier
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Column headers. Numeric columns are kept intentionally narrow (a %
        // never exceeds 3 chars, kcal rarely exceeds 4) so the text columns
        // (ingredient / meal / activity name) get most of the row width. Small
        // spacers between columns keep numbers readable without leaving a
        // large empty band.
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HeaderCell(tr("macro_breakdown_col_percent", language), Modifier.width(40.dp))
            Spacer(Modifier.width(6.dp))
            when (variant) {
                TableVariant.STANDARD -> {
                    HeaderCell(
                        tr("macro_breakdown_col_grams", language),
                        Modifier.width(46.dp),
                        align = TextAlign.End
                    )
                    Spacer(Modifier.width(10.dp))
                    HeaderCell(
                        tr("macro_breakdown_col_ingredient", language),
                        Modifier.weight(3f)
                    )
                    Spacer(Modifier.width(6.dp))
                    HeaderCell(
                        tr("macro_breakdown_col_meal", language),
                        Modifier.weight(2f)
                    )
                }
                TableVariant.CALORIES_MEALS -> {
                    HeaderCell(
                        tr("macro_breakdown_col_kcal", language),
                        Modifier.width(52.dp),
                        align = TextAlign.End
                    )
                    Spacer(Modifier.width(10.dp))
                    HeaderCell(
                        tr("macro_breakdown_col_meal", language),
                        Modifier.weight(1f)
                    )
                }
                TableVariant.CALORIES_ACTIVITY -> {
                    HeaderCell(
                        tr("macro_breakdown_col_kcal", language),
                        Modifier.width(52.dp),
                        align = TextAlign.End
                    )
                    Spacer(Modifier.width(8.dp))
                    HeaderCell(
                        tr("macro_breakdown_col_min", language),
                        Modifier.width(38.dp),
                        align = TextAlign.End
                    )
                    Spacer(Modifier.width(10.dp))
                    HeaderCell(
                        tr("macro_breakdown_col_activity", language),
                        Modifier.weight(1f)
                    )
                }
            }
        }
        Divider(color = Color(0xFF334155).copy(alpha = 0.5f))

        if (rows.isEmpty()) {
            Text(
                text = tr("macro_breakdown_empty", language),
                color = Color(0xFF64748B),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                textAlign = TextAlign.Center
            )
            return@Column
        }

        LazyColumn(modifier = modifier) {
            items(rows, key = { row ->
                // Compose stable key: include amount + name to survive
                // re-order across period switches without recycling wrong.
                "${row.foodEntryId ?: "act"}|${row.mealTimestamp}|${row.ingredientName}|${row.mealName}|${row.amount}"
            }) { row ->
                MacroContributionRowView(kind, variant, row, onRowClick)
            }
        }
    }
}

@Composable
private fun MacroContributionRowView(
    kind: MacroKind,
    variant: TableVariant,
    row: MacroContributionRow,
    onRowClick: ((MacroContributionRow) -> Unit)?
) {
    val clickModifier = if (onRowClick != null && row.foodEntryId != null) {
        Modifier.clickable { onRowClick(row) }
    } else Modifier
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(clickModifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${row.percent.toInt()}%",
            modifier = Modifier.width(40.dp),
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false
        )
        Spacer(Modifier.width(6.dp))
        when (variant) {
            TableVariant.STANDARD -> {
                Text(
                    text = formatGrams(row.amount),
                    modifier = Modifier.width(46.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.End
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = row.ingredientName.ifBlank { MACRO_ROW_MISSING },
                    modifier = Modifier.weight(3f),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = row.mealName,
                    modifier = Modifier.weight(2f),
                    color = Color(0xFF94A3B8),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TableVariant.CALORIES_MEALS -> {
                Text(
                    text = "${row.amount.toInt()}",
                    modifier = Modifier.width(52.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.End
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = row.mealName,
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TableVariant.CALORIES_ACTIVITY -> {
                Text(
                    text = "${row.amount.toInt()}",
                    modifier = Modifier.width(52.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.End
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = row.durationMinutes?.let { "$it" } ?: MACRO_ROW_MISSING,
                    modifier = Modifier.width(38.dp),
                    color = Color(0xFF94A3B8),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.End
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = row.mealName,
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 1-decimal grams with trailing `.0` stripped, matching [formatFreeSugarsLine]
 *  style used elsewhere. */
private fun formatGrams(g: Double): String {
    val rounded = (g * 10).toInt() / 10.0
    return if (rounded % 1.0 == 0.0) "${rounded.toInt()}g" else "${rounded}g"
}

/**
 * Inline replica of the amber Consumed/Goal bar from the Dashboard's
 * calorie card ([ChatScreen.kt] lines ~985-1008). Kept in this file (not
 * imported from ChatScreen.kt) so the popup can be reused without dragging
 * the whole dashboard layout in as a dependency.
 */
@Composable
private fun CalorieHeaderBar(consumed: Int, goal: Int) {
    val fillFraction = if (goal > 0)
        (consumed.coerceAtLeast(0).toFloat() / goal).coerceIn(0f, 1f)
    else 0f
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        border = BorderStroke(1.dp, Color(0xFF334155))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$consumed kcal",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = String.format(Locale.US, "%,d kcal", goal),
                    color = Color(0xFF94A3B8),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF334155))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fillFraction)
                        .fillMaxHeight()
                        .background(Color(0xFFEAB308))
                )
            }
        }
    }
}
