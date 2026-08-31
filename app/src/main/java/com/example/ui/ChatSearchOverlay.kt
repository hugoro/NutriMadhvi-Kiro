package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.data.local.MessageSearchHit
import com.example.ui.theme.AccentSoft
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Cross-conversation search panel — added 2026-06-15 in response to Hugo's
 * "WhatsApp is brilliant at this" feedback on the chat redesign.
 *
 * Layout
 * ------
 *   - Top: an [OutlinedTextField] that auto-focuses on entry. Typing
 *     mutates [ChatViewModel.searchQuery]; results re-emit on a 200 ms
 *     debounce backed by FTS4 (`chat_messages_fts MATCH …`).
 *   - Middle: empty state ("type to search"), zero-results state, or a
 *     LazyColumn of result cards. Cards show:
 *         · session title (which conversation)
 *         · sender icon (Person for "user", SmartToy otherwise)
 *         · snippet of the message text with the matched term **bolded**
 *           and surrounding context preserved (~120 chars window)
 *         · relative timestamp (Hoje 14:32 / Ontem / 12 Jun)
 *   - A tap on any card calls back with (sessionId, messageId), which
 *     [ChatViewModel.jumpToMessage] uses to switch sessions and arm the
 *     scroll-to-message latch.
 *
 * Why a separate file: ChatScreen.kt is already 8000+ lines. Anything we
 * can extract without crossing private boundaries should live elsewhere.
 *
 * @param viewModel the same [ChatViewModel] the chat tab uses; we read
 *   [searchQuery] and [searchResults] from it and forward query updates
 *   via [updateSearchQuery].
 * @param userProfileLang current language code (`pt` / `gu` / fallback
 *   English) for [tr] lookups.
 * @param onResultClick fired when the user taps a result card; receives
 *   the session id to switch to and the message id to scroll to.
 * @param onClose fired when the close affordance (or back) is invoked.
 *   The owning ChatTab also handles this via its header X icon and a
 *   BackHandler — this callback exists so the panel itself can offer a
 *   visible "back" if we ever extract the header out.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
fun ChatSearchOverlay(
    viewModel: ChatViewModel,
    userProfileLang: String,
    onResultClick: (sessionId: Long, messageId: Long) -> Unit,
    onClose: () -> Unit,
    isVisible: Boolean = true
) {
    val query by viewModel.searchQuery.collectAsState()
    val results by viewModel.searchResults.collectAsState()
    val focusRequester = remember { FocusRequester() }

    // Auto-focus the text field once on entry so the keyboard rises and
    // the user can start typing without an extra tap. Gated on isVisible so
    // it can NOT fire while the Chat tab is composed off-screen by the pager
    // (that would raise the IME over whatever tab is actually showing).
    LaunchedEffect(isVisible) {
        if (!isVisible) return@LaunchedEffect
        // Small delay tolerated by IME — without this, on some devices the
        // requestFocus call lands before the field is laid out and the
        // keyboard never rises. 50 ms is below the threshold of perception.
        kotlinx.coroutines.delay(50L)
        // Re-check AFTER the delay. `isVisible` here is the settled-on-Chat
        // signal (see ChatScreen call site). During a Chat→Painel settle the
        // pager can arm this effect with a stale reading; by the time the 50 ms
        // elapse the pager has settled elsewhere, this guard aborts, and the IME
        // never rises over Painel. Closes the sub-frame window where the
        // coroutine resumed from delay before a pending cancellation applied.
        if (!isVisible) return@LaunchedEffect
        runCatching { focusRequester.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F19))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.updateSearchQuery(it) },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            placeholder = {
                Text(
                    tr("Search across all chats", userProfileLang),
                    color = Color(0xFF64748B)
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = Color(0xFF94A3B8)
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { /* live-search already runs */ }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = AccentSoft,
                focusedBorderColor = AccentSoft,
                unfocusedBorderColor = Color(0xFF334155)
            ),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        when {
            query.trim().length < 2 -> {
                EmptySearchHint(
                    text = tr("Type at least 2 letters to search.", userProfileLang)
                )
            }
            results.isEmpty() -> {
                EmptySearchHint(
                    text = tr("No matches found.", userProfileLang)
                )
            }
            else -> {
                Text(
                    text = buildResultCountLabel(results.size, userProfileLang),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF64748B),
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                )
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(results, key = { it.messageId }) { hit ->
                        SearchResultRow(
                            hit = hit,
                            queryRaw = query,
                            lang = userProfileLang,
                            onClick = { onResultClick(hit.sessionId, hit.messageId) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptySearchHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 64.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Text(
            text = text,
            color = Color(0xFF64748B),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun SearchResultRow(
    hit: MessageSearchHit,
    queryRaw: String,
    lang: String,
    onClick: () -> Unit
) {
    val snippet = remember(hit.text, queryRaw) {
        buildHighlightedSnippet(hit.text, queryRaw)
    }
    val isUser = hit.sender == "user"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1E293B))
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.ChatBubbleOutline,
                contentDescription = null,
                tint = AccentSoft,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = hit.sessionTitle.ifBlank { tr("Untitled chat", lang) },
                style = MaterialTheme.typography.labelMedium,
                color = AccentSoft,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = formatHitTimestamp(hit.timestamp, lang),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF64748B)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = if (isUser) Icons.Rounded.Person else Icons.Rounded.SmartToy,
                contentDescription = null,
                tint = if (isUser) Color(0xFF22C55E) else Color(0xFF94A3B8),
                modifier = Modifier
                    .size(14.dp)
                    .padding(top = 3.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = snippet,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFE2E8F0)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Snippet & timestamp helpers
// ---------------------------------------------------------------------------

/**
 * Renders a short window around the first match of any search token, with
 * matched text shown bold. Mirrors WhatsApp's "...palavra antes [match] palavra
 * depois..." style.
 *
 *   - Tokenizes the query the same way [com.example.data.remote.GeminiRepository.searchChatMessages]
 *     does: trim → strip FTS4 meta-chars → split on whitespace.
 *   - Search is diacritic-insensitive and case-insensitive (NFC + NFD strip
 *     to match Portuguese / Gujarati equivalents).
 *   - If the message is short enough we show the whole thing. Otherwise we
 *     center a ~120-char window on the earliest match.
 */
private fun buildHighlightedSnippet(text: String, queryRaw: String): AnnotatedString {
    val tokens = queryRaw
        .trim()
        .replace(Regex("""[\"*:^]"""), " ")
        .split(Regex("""\s+"""))
        .filter { it.isNotBlank() }
    if (tokens.isEmpty()) return AnnotatedString(text.take(160))

    val flatText = text
    val flatLower = stripDiacritics(flatText.lowercase())
    val tokensLower = tokens.map { stripDiacritics(it.lowercase()) }

    // Earliest match across all tokens — drives the snippet window.
    val firstMatch = tokensLower
        .mapNotNull { tok -> flatLower.indexOf(tok).takeIf { it >= 0 }?.let { it to tok.length } }
        .minByOrNull { it.first }

    val WINDOW = 120
    val (windowStart, windowEnd) = if (firstMatch == null || flatText.length <= WINDOW) {
        0 to minOf(flatText.length, WINDOW)
    } else {
        val (idx, _) = firstMatch
        val start = (idx - WINDOW / 3).coerceAtLeast(0)
        val end = (start + WINDOW).coerceAtMost(flatText.length)
        start to end
    }

    val visible = flatText.substring(windowStart, windowEnd)
    val visibleLower = stripDiacritics(visible.lowercase())

    return buildAnnotatedString {
        if (windowStart > 0) append("…")
        var cursor = 0
        // Greedy walk: at every position, try every token and pick the
        // longest matching one. Bold it, advance, repeat. O(n·k) on the
        // window size which is bounded at 120 chars.
        while (cursor < visible.length) {
            var matched: Int = -1
            for (tok in tokensLower) {
                if (tok.isEmpty()) continue
                if (cursor + tok.length <= visibleLower.length &&
                    visibleLower.regionMatches(cursor, tok, 0, tok.length)
                ) {
                    matched = tok.length
                    break
                }
            }
            if (matched > 0) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFFFACC15))) {
                    append(visible.substring(cursor, cursor + matched))
                }
                cursor += matched
            } else {
                append(visible[cursor])
                cursor += 1
            }
        }
        if (windowEnd < flatText.length) append("…")
    }
}

private fun stripDiacritics(s: String): String {
    val normalized = Normalizer.normalize(s, Normalizer.Form.NFD)
    return normalized.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
}

private fun formatHitTimestamp(ms: Long, lang: String): String {
    val instant = Instant.ofEpochMilli(ms)
    val zone = ZoneId.systemDefault()
    val date = instant.atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    val time = LocalDateTime.ofInstant(instant, zone).format(DateTimeFormatter.ofPattern("HH:mm"))
    return when {
        date == today -> "${tr("Today", lang)} $time"
        date == today.minusDays(1) -> "${tr("Yesterday", lang)} $time"
        else -> {
            // dd MMM (locale-friendly short month)
            val dayMonth = LocalDateTime.ofInstant(instant, zone)
                .format(DateTimeFormatter.ofPattern("d MMM", java.util.Locale.forLanguageTag(lang.ifBlank { "en" })))
            "$dayMonth $time"
        }
    }
}

private fun buildResultCountLabel(n: Int, lang: String): String {
    return if (n == 1) tr("1 result", lang)
    else tr("%d results", lang).replace("%d", n.toString())
}
