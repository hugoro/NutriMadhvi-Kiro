package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AccentDeep
import com.example.ui.theme.AccentGradient

/**
 * Row de pills com selecção exclusiva — substitui o padrão "Row + forEach + Box
 * com background condicional verde/transparente" que estava espalhado por
 * Dashboard/History/Report/ActivityCapture/FoodHabits.
 *
 * Selected: pill totalmente arredondado (RoundedCornerShape(999.dp)) preenchido
 * com [AccentGradient] e com glow violeta via Modifier.shadow (ambient + spot
 * a [AccentDeep]). Texto branco semibold.
 *
 * Unselected: mesmo shape, fundo transparente, border 1dp [Color(0xFF334155)]
 * (slate-700). Texto cinza [Color(0xFF94A3B8)].
 *
 * @param compact se true, padding/font menores — para chips inline (ex.: filtro
 *                de categorias em FoodHabitsScreen) onde não interessa que cada
 *                pill ocupe largura igual.
 * @param weighted se true, cada pill recebe Modifier.weight(1f) para dividir a
 *                 largura disponível (caso típico do segmented Daily/Weekly/
 *                 Monthly). Se false, cada pill encolhe ao seu conteúdo.
 * @param rowModifier modificador extra a aplicar ao Row (ex.: `.horizontalSwipe(...)`).
 */
@Composable
fun SegmentedPillRow(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    labelFor: (String) -> String = { it },
    modifier: Modifier = Modifier,
    rowModifier: Modifier = Modifier,
    compact: Boolean = false,
    weighted: Boolean = true,
    spacing: Dp = if (compact) 6.dp else 12.dp
) {
    Row(
        modifier = modifier
            .then(if (weighted) Modifier.fillMaxWidth() else Modifier)
            .then(rowModifier),
        horizontalArrangement = Arrangement.spacedBy(spacing)
    ) {
        options.forEach { opt ->
            SegmentedPillBox(
                isSelected = selected == opt,
                onClick = { onSelect(opt) },
                modifier = if (weighted) Modifier.weight(1f) else Modifier,
                compact = compact
            ) {
                val isSelected = selected == opt
                Text(
                    text = labelFor(opt),
                    style = if (compact) {
                        MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    },
                    color = if (isSelected) Color.White else Color(0xFF94A3B8),
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

/**
 * Variante low-level: 1 pill com conteúdo arbitrário (suporta ícone + texto, ou
 * qualquer outro composable). Usada quando o helper de alto nível não chega —
 * caso dos botões Food/Activity tracker em ActivityCaptureTab.
 */
@Composable
fun SegmentedPillBox(
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(999.dp)
    val verticalPad = if (compact) 5.dp else 12.dp
    val horizontalPad = if (compact) 12.dp else 8.dp
    Box(
        modifier = modifier
            .clip(shape)
            .then(
                if (isSelected) {
                    Modifier
                        .shadow(
                            elevation = if (compact) 8.dp else 18.dp,
                            shape = shape,
                            ambientColor = AccentDeep,
                            spotColor = AccentDeep
                        )
                        .background(brush = AccentGradient)
                } else {
                    Modifier
                        .background(Color.Transparent)
                        .border(1.dp, Color(0xFF334155), shape)
                }
            )
            .clickable(onClick = onClick)
            .padding(vertical = verticalPad, horizontal = horizontalPad),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
