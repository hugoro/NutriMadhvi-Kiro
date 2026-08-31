package com.example.ui.components

// Unused after 2026-06-22 — kept for future reuse.
// The Reports tab's "lead KPI" block was removed because it duplicated the
// highlighted summary row inside each card's detail table 1:1. If a future
// screen needs a card-top headline + delta one-liner, this composable is
// ready to use without modification.

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AccentPrimary
import com.example.ui.theme.AccentSoft

/**
 * Tone of the [LeadKpi] delta line. Drives the colour of the comparison text
 * underneath the headline value.
 *
 *   GOOD    — within ~5% of the target (emerald-400)
 *   WARN    — within ~15% (amber-400)
 *   BAD     — outside 15% (red-400)
 *   NEUTRAL — no comparison available (slate-400)
 */
enum class DeltaTone { GOOD, WARN, BAD, NEUTRAL }

/**
 * Card-top "headline KPI" block used by the redesigned Reports tab. Renders:
 *   - small uppercase label (AccentSoft)
 *   - large bold value (white, ~28sp)
 *   - optional delta one-liner (tone-coloured)
 *   - optional caption (slate-500, 11sp) — used to disclose e.g. "inclui TMB"
 *
 * Framed in a faint violet wash + border so the user's eye lands on the
 * insight first without making the card feel busy. Detail rows below stay at
 * their current bodySmall density.
 */
@Composable
fun LeadKpi(
    label: String,
    value: String,
    deltaText: String? = null,
    deltaTone: DeltaTone = DeltaTone.NEUTRAL,
    caption: String? = null,
    modifier: Modifier = Modifier
) {
    val deltaColor = when (deltaTone) {
        DeltaTone.GOOD -> Color(0xFF34D399)  // emerald-400
        DeltaTone.WARN -> Color(0xFFFBBF24)  // amber-400
        DeltaTone.BAD -> Color(0xFFF87171)   // red-400
        DeltaTone.NEUTRAL -> Color(0xFF94A3B8) // slate-400
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AccentPrimary.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .border(
                BorderStroke(1.dp, AccentPrimary.copy(alpha = 0.25f)),
                RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label.uppercase(),
            color = AccentSoft,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelSmall
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Visible
        )
        if (deltaText != null) {
            Text(
                text = deltaText,
                color = deltaColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
        if (caption != null) {
            Text(
                text = caption,
                color = Color(0xFF64748B), // slate-500
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal
            )
        }
    }
}
