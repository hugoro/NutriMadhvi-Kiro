package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AccentSoft

/**
 * Canonical top-of-screen header used by every tab. Mirrors the Painel
 * (Dashboard) brand block: a vertical white-gradient headlineMedium title and
 * an AccentSoft bold bodySmall subtitle, with optional left and right slots
 * for icons (e.g. the Chat hamburger or Dashboard profile button).
 *
 * Keeping every tab on this single composable is what makes the app feel
 * uniform — so don't pass custom typography/colors here. If a screen needs
 * a different layout it should not use this; this is the reference style.
 */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    leadingSlot: (@Composable () -> Unit)? = null,
    trailingSlot: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingSlot != null) {
            leadingSlot()
            Spacer(Modifier.width(8.dp))
        }
        // The brand brush — matches the Dashboard's NutriMadhvi wordmark so
        // every tab title shares the same vertical white-to-translucent
        // gradient. Compose 1.6+ paints brushes inside TextStyle.
        val brandBrush = Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to Color.White,
                0.4f to Color.White,
                1.0f to Color.White.copy(alpha = 0.5f)
            )
        )
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium.copy(brush = brandBrush),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = AccentSoft,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (trailingSlot != null) {
            Spacer(Modifier.width(8.dp))
            trailingSlot()
        }
    }
}
