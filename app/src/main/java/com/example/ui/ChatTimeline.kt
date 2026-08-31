package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.ChatMessage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Mixed timeline row — either a calendar-day separator or a chat message.
 * Used by [chatTimelineRows] to interleave inline date headers with the
 * message stream so the chat reads more like WhatsApp / Messages.
 */
sealed class ChatTimelineRow {
    data class Header(val epochDay: Long) : ChatTimelineRow()
    data class Msg(val message: ChatMessage) : ChatTimelineRow()
}

/**
 * Walks the message list (chronological, oldest → newest) and inserts a
 * [ChatTimelineRow.Header] before every group whose calendar day differs
 * from the previous message's day. Returns the list in CHRONOLOGICAL
 * order (oldest first) so it can be fed straight into a normal
 * `LazyColumn` — the Compose default with `reverseLayout = false`.
 *
 * Build 2 v9 (2026-06-28): the list is no longer reversed. The chat
 * moved away from `reverseLayout = true` because the inverted layout
 * fought the streaming bubble's growth in every possible way (v2..v8
 * each tried a different workaround). Lista normal + scrollToItem at
 * the bottom on send is the pattern ChatGPT/Gemini apps actually use.
 *
 * Time zone:
 *   - Day grouping uses [ZoneId.systemDefault()] — same convention as
 *     all other date-based grouping in the app (see
 *     [com.example.ui.formatHistoryDate]). A daylight-savings shift on
 *     the boundary day is harmless: the header label still says the
 *     local date the user perceives.
 */
fun chatTimelineRows(messages: List<ChatMessage>): List<ChatTimelineRow> {
    if (messages.isEmpty()) return emptyList()
    val zone = ZoneId.systemDefault()
    val rows = ArrayList<ChatTimelineRow>(messages.size + 8)
    var lastDay: Long? = null
    for (m in messages) {
        val day = Instant.ofEpochMilli(m.timestamp).atZone(zone).toLocalDate().toEpochDay()
        if (day != lastDay) {
            rows.add(ChatTimelineRow.Header(day))
            lastDay = day
        }
        rows.add(ChatTimelineRow.Msg(m))
    }
    return rows
}

/**
 * Pill-style separator showing "Hoje" / "Ontem" / "12 jun" depending on
 * proximity to today. Mirrors WhatsApp's centered date chip aesthetic.
 *
 * Localized via [tr] for "Today" / "Yesterday" plus a locale-aware
 * `d MMM` formatter for the fallback case (Portuguese gets "12 jun",
 * English gets "12 Jun", Gujarati gets the locale-translated form).
 */
@Composable
fun DayHeaderChip(epochDay: Long, lang: String) {
    val date = LocalDate.ofEpochDay(epochDay)
    val today = LocalDate.now(ZoneId.systemDefault())
    val label = when {
        date == today -> tr("Today", lang)
        date == today.minusDays(1) -> tr("Yesterday", lang)
        else -> date.format(
            DateTimeFormatter.ofPattern(
                "d MMM",
                Locale.forLanguageTag(lang.ifBlank { "en" })
            )
        )
    }
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF94A3B8),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1E293B))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
