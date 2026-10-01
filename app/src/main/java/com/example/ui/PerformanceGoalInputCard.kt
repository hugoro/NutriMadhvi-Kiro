package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.PerformanceGoalEntity
import com.example.ui.theme.AccentPrimary
import com.example.ui.theme.AccentSoft

/**
 * Card that lets the user describe their performance goal in natural language.
 * The goal text is sent to the coach, which derives silhouette-ratio targets
 * and embeds them in the next coach report.
 */
@Composable
fun PerformanceGoalInputCard(
    currentGoal: PerformanceGoalEntity?,
    onSave: (String) -> Unit,
) {
    var text by remember(currentGoal?.goalText) {
        mutableStateOf(currentGoal?.goalText ?: "")
    }
    val hasGoal = currentGoal != null
    val hasTargets = currentGoal?.coachTargetJson != null

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1A2236),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "O teu objetivo",
                    color = AccentSoft,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
                if (hasTargets) {
                    Text(
                        "✓ objetivos calculados",
                        color = Color(0xFF4ADE80),
                        fontSize = 10.sp,
                    )
                } else if (hasGoal) {
                    Text(
                        "a calcular...",
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 10.sp,
                    )
                }
            }

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = {
                    Text(
                        "Descreve o teu objetivo de performance...",
                        color = Color.White.copy(alpha = 0.35f),
                        fontSize = 13.sp,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default,
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = AccentPrimary.copy(alpha = 0.6f),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                    cursorColor = AccentSoft,
                    focusedContainerColor = Color.White.copy(alpha = 0.04f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.04f),
                ),
                shape = RoundedCornerShape(10.dp),
            )

            // Allow re-triggering target generation when a goal exists but the
            // coach never produced targets (generation failed / returned null).
            val textChanged = text.trim() != currentGoal?.goalText
            val canRetry = hasGoal && !hasTargets && text.isNotBlank()
            Button(
                onClick = { if (text.isNotBlank()) onSave(text.trim()) },
                enabled = text.isNotBlank() && (textChanged || canRetry),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentPrimary,
                    disabledContainerColor = Color.White.copy(alpha = 0.08f),
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        canRetry && !textChanged -> "Tentar calcular objetivos"
                        hasGoal                  -> "Atualizar objetivo"
                        else                     -> "Guardar objetivo"
                    },
                    fontSize = 13.sp,
                )
            }
        }
    }
}
