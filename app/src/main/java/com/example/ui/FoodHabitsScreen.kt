package com.example.ui

import com.example.ui.theme.AccentInk
import com.example.ui.theme.AccentPrimary
import com.example.ui.theme.AccentSoft

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.FoodHabit
import com.example.data.local.MealCategory

/**
 * Screen for reviewing and editing the user-taught dietary patterns
 * (FoodHabit rows). Shows active and inactive habits in a single list, with
 * toggle switches and an inline editor opened by FAB or by tapping a card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodHabitsScreen(viewModel: ChatViewModel) {
    val habits by viewModel.foodHabits.collectAsState()
    val userProfile by viewModel.userProfile.collectAsState()
    val lang = userProfile.language

    var editing by remember { mutableStateOf<FoodHabit?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    Scaffold(
        // 2026-06-18: contentWindowInsets explicitly zeroed. The OUTER
        // Scaffold in ChatScreen() already consumes the status-bar top
        // inset (and the bottom-nav + IME). Leaving this nested Scaffold
        // on the default contentWindowInsets caused a SECOND consumption
        // of the status-bar inset, which pushed the ScreenHeader visibly
        // away from the top of the screen — Hugo flagged this as the
        // Habits tab title sitting too far below the status bar, while
        // every other tab (Dashboard, History, Activity, Chat) — which
        // do NOT use a nested Scaffold — paints the title flush against
        // the top. Same kind of double-consumption the IME fix in
        // ChatScreen guards against (see [[scaffold-imepadding-double-trap]]),
        // applied here to status bars rather than the keyboard.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Color(0xFF0B0F19),
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editing = null
                    showEditor = true
                },
                containerColor = AccentSoft,
                contentColor = AccentInk
            ) {
                Icon(Icons.Rounded.Add, contentDescription = tr("New Habit", lang))
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            com.example.ui.components.ScreenHeader(
                title = tr("Food Habits", lang),
                subtitle = tr("Teach the AI which foods you eat regularly", lang)
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {

            if (habits.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Rounded.RestaurantMenu,
                            contentDescription = null,
                            tint = Color(0xFF334155),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            tr("No habits saved yet", lang),
                            color = Color(0xFF64748B),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            tr("Tap + to add a habit, or describe one in the chat.", lang),
                            color = Color(0xFF475569),
                            fontSize = 11.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(habits, key = { it.id }) { habit ->
                        HabitCard(
                            habit = habit,
                            language = lang,
                            onClick = {
                                editing = habit
                                showEditor = true
                            },
                            onToggle = { active ->
                                viewModel.setFoodHabitActive(habit.id, active)
                            }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }
        } // end inner padding(horizontal=16) Column
    }

    if (showEditor) {
        FoodHabitEditorDialog(
            initial = editing,
            language = lang,
            onDismiss = { showEditor = false },
            onSave = { saved ->
                viewModel.saveFoodHabit(saved)
                showEditor = false
            },
            onDelete = { toDelete ->
                viewModel.deleteFoodHabit(toDelete)
                showEditor = false
            }
        )
    }
}

@Composable
private fun HabitCard(
    habit: FoodHabit,
    language: String,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    val borderColor = if (habit.isActive) Color(0xFF38BDF8).copy(alpha = 0.45f) else Color(0xFF334155)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = habit.trigger.split(";").firstOrNull()?.trim()?.ifBlank { habit.descriptor.take(40) }
                        ?: habit.descriptor.take(40),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Text(
                    text = habit.descriptor,
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    maxLines = 2
                )
                if (habit.mealCategoryHint != null) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "• " + tr(habit.mealCategoryHint.displayKey, language),
                        color = AccentSoft,
                        fontSize = 10.sp
                    )
                }
                if (habit.timesApplied > 0) {
                    Text(
                        text = tr("Applied", language) + " ${habit.timesApplied}×",
                        color = Color(0xFF64748B),
                        fontSize = 10.sp
                    )
                }
            }
            Switch(
                checked = habit.isActive,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = AccentSoft,
                    checkedTrackColor = Color(0xFF4C1D95)
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FoodHabitEditorDialog(
    initial: FoodHabit?,
    language: String,
    onDismiss: () -> Unit,
    onSave: (FoodHabit) -> Unit,
    onDelete: (FoodHabit) -> Unit
) {
    var trigger by remember(initial?.id) { mutableStateOf(initial?.trigger ?: "") }
    var descriptor by remember(initial?.id) { mutableStateOf(initial?.descriptor ?: "") }
    var notes by remember(initial?.id) { mutableStateOf(initial?.notes ?: "") }
    var category by remember(initial?.id) { mutableStateOf<MealCategory?>(initial?.mealCategoryHint) }
    var isActive by remember(initial?.id) { mutableStateOf(initial?.isActive ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F172A),
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = {
            Text(
                if (initial == null) tr("New Habit", language) else tr("Edit Habit", language),
                color = Color.White
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = trigger,
                    onValueChange = { trigger = it },
                    label = { Text(tr("Trigger words", language), color = Color(0xFF94A3B8)) },
                    placeholder = { Text(tr("Separate triggers with ;", language), color = Color(0xFF475569)) },
                    minLines = 1,
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF38BDF8),
                        unfocusedBorderColor = Color(0xFF334155)
                    )
                )
                OutlinedTextField(
                    value = descriptor,
                    onValueChange = { descriptor = it },
                    label = { Text(tr("Description (sent to AI)", language), color = Color(0xFF94A3B8)) },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF38BDF8),
                        unfocusedBorderColor = Color(0xFF334155)
                    )
                )
                Text(
                    tr("Meal category (optional)", language),
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CategoryChip(label = tr("Any", language), selected = category == null) { category = null }
                    MealCategory.values().forEach { mc ->
                        CategoryChip(
                            label = tr(mc.displayKey, language),
                            selected = category == mc
                        ) { category = mc }
                    }
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(tr("Notes", language), color = Color(0xFF94A3B8)) },
                    minLines = 1,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF38BDF8),
                        unfocusedBorderColor = Color(0xFF334155)
                    )
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = isActive,
                        onCheckedChange = { isActive = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = AccentSoft,
                            checkedTrackColor = Color(0xFF4C1D95)
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(tr("Active", language), color = Color.White)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (trigger.isBlank() || descriptor.isBlank()) return@TextButton
                    val saved = (initial ?: FoodHabit(trigger = "", descriptor = "")).copy(
                        trigger = trigger.trim(),
                        descriptor = descriptor.trim(),
                        notes = notes.trim().ifBlank { null },
                        mealCategoryHint = category,
                        isActive = isActive
                    )
                    onSave(saved)
                }
            ) {
                Text(tr("Save", language), color = AccentSoft, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row {
                if (initial != null) {
                    TextButton(onClick = { onDelete(initial) }) {
                        Text(tr("Delete", language), color = Color(0xFFEF4444))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(tr("Cancel", language), color = Color(0xFF94A3B8))
                }
            }
        }
    )
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    // Pill compacto com gradient violeta + glow quando seleccionado, outline cinza
    // quando não. Mantém o tamanho 11sp / padding 9×5 dp original — só a paleta muda.
    com.example.ui.components.SegmentedPillBox(
        isSelected = selected,
        onClick = onClick,
        compact = true
    ) {
        Text(
            label,
            color = if (selected) Color.White else Color(0xFF94A3B8),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false
        )
    }
}
