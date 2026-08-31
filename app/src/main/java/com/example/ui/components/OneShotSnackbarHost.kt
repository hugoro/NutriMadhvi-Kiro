package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow

/**
 * One-shot transient banner overlay. Subscribes to [oneShotSnack] (typically
 * [com.example.ui.ChatViewModel.oneShotSnack]); each emission is resolved
 * with [resolveMessage] (so the VM stays locale-agnostic and ships only
 * i18n keys) and renders for ~4 seconds before fading out.
 *
 * Designed to be the topmost child of a fullscreen Box so it floats above
 * scrim/buttons without participating in the underlying scroll. Bottom-anchored
 * so it does not collide with the top app bar / brand title.
 *
 * Why not Material3 [androidx.compose.material3.SnackbarHost]? It is locked
 * to the Scaffold layout, requires a host state pinned to a remembered
 * coroutine, and clips against the bottom inset by default — wrong fit for
 * the bottom-bar-less edit-meal screen. This composable is ~50 LoC and
 * matches the dark-pill style used elsewhere (food-analysis card, etc.).
 */
@Composable
fun OneShotSnackbarHost(
    oneShotSnack: SharedFlow<String>,
    resolveMessage: (String) -> String,
    modifier: Modifier = Modifier
) {
    var current by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(oneShotSnack) {
        oneShotSnack.collect { key ->
            current = resolveMessage(key)
            delay(4000)
            current = null
        }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = current != null,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 })
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 80.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1E293B))
                    .border(
                        BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f)),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = Color(0xFFF59E0B),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = current.orEmpty(),
                    color = Color(0xFFE2E8F0),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
