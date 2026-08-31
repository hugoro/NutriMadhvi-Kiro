package com.example.data.voice

import android.content.Context
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TTS wrapper for the chat feature — reads assistant bubbles aloud.
 *
 * Design:
 *  - Single [TextToSpeech] engine, created in the Application/Activity context and
 *    disposed via [shutdown] on `onDestroy` (see MainActivity wiring per plan).
 *  - Callers request speech via [speak], passing the raw bubble text plus the
 *    profile language (`"pt-PT" | "en" | "gu"`). We strip Markdown once, split
 *    the cleaned text on paragraph breaks, and queue each paragraph as a
 *    separate utterance so `stop()` cancels at a natural boundary and long
 *    replies don't monopolise the audio focus indefinitely.
 *  - [speakingMessageId] exposes the ChatMessage.id currently voiced, so the
 *    footer icon in `MessageBubbleItem` can flip between VolumeUp / VolumeOff
 *    without polling. It clears on start error, on completion of the LAST
 *    chunk, on explicit [stop], and on `onError` from the engine.
 *  - We never invoke [TextToSpeech.speak] before [onInit] resolved OK — early
 *    calls are dropped with a log line and the caller is expected to try again
 *    (the button UX naturally allows this).
 *
 * NB: we do NOT use `com.example.ui.localeForLanguage()` here. That helper is
 * tuned for date formatting and returns `Locale("pt")` (language-only) so
 * ICU emits "dom." instead of "domingo". TTS engines pick voices better when
 * given a region, so we map explicitly to `Locale("pt","PT")`, `Locale("en","US")`,
 * `Locale("gu","IN")`.
 */
class TtsController(context: Context) {

    private val appCtx = context.applicationContext
    private val ready = AtomicBoolean(false)
    private val _speakingMessageId = MutableStateFlow<Long?>(null)
    val speakingMessageId: StateFlow<Long?> = _speakingMessageId.asStateFlow()

    // Tracks which utteranceId belongs to which message so [UtteranceProgressListener]
    // can clear the flow only when the LAST chunk of the current message completes.
    // Cleared on every new [speak] call and on [stop].
    @Volatile private var currentMessageId: Long? = null
    @Volatile private var lastChunkUtteranceId: String? = null

    private val tts: TextToSpeech = TextToSpeech(appCtx) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready.set(true)
            Log.i(TAG, "engine ready")
        } else {
            Log.w(TAG, "engine init failed status=$status — TTS disabled")
        }
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { /* noop */ }

            override fun onDone(utteranceId: String?) {
                if (utteranceId != null && utteranceId == lastChunkUtteranceId) {
                    _speakingMessageId.value = null
                    currentMessageId = null
                    lastChunkUtteranceId = null
                }
            }

            @Deprecated("legacy API", ReplaceWith("onError(utteranceId, errorCode)"))
            override fun onError(utteranceId: String?) {
                Log.w(TAG, "utterance error id=$utteranceId (legacy)")
                _speakingMessageId.value = null
                currentMessageId = null
                lastChunkUtteranceId = null
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "utterance error id=$utteranceId code=$errorCode")
                _speakingMessageId.value = null
                currentMessageId = null
                lastChunkUtteranceId = null
            }
        })
    }

    /**
     * Speak [text] in the voice that matches [language]. Idempotent per message:
     * calling [speak] with the same [messageId] while it is already speaking is
     * treated as "stop" (the bubble button relies on this — one click starts,
     * next click stops).
     *
     * Returns false if the engine is not ready or the requested voice is
     * unavailable (caller can surface a toast if they care).
     */
    fun speak(text: String, language: String, messageId: Long): Boolean {
        if (!ready.get()) {
            Log.w(TAG, "speak dropped — engine not ready")
            return false
        }
        if (currentMessageId == messageId) {
            // Second click on the same bubble → stop.
            stop()
            return true
        }
        // Cancel anything in flight before starting the new bubble.
        tts.stop()

        val locale = ttsLocaleFor(language)
        val availability = tts.isLanguageAvailable(locale)
        if (availability == TextToSpeech.LANG_MISSING_DATA ||
            availability == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "voice unavailable for $locale (code=$availability) — using default")
            // Fall through with default voice rather than staying silent —
            // Gujarati often needs the user to install the voice pack.
        } else {
            tts.language = locale
        }

        val cleaned = stripMarkdown(text)
        if (cleaned.isBlank()) {
            Log.i(TAG, "speak skipped — text is blank after cleanup")
            return false
        }

        val chunks = chunkForTts(cleaned)
        if (chunks.isEmpty()) return false

        currentMessageId = messageId
        lastChunkUtteranceId = "$messageId#${chunks.lastIndex}"
        _speakingMessageId.value = messageId

        chunks.forEachIndexed { idx, chunk ->
            val id = "$messageId#$idx"
            val mode = if (idx == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            // API 21+ signature: speak(text, mode, params, utteranceId).
            val res = tts.speak(chunk, mode, null, id)
            if (res != TextToSpeech.SUCCESS) {
                Log.w(TAG, "speak() returned $res for id=$id")
            }
        }
        Log.i(TAG, "speaking msgId=$messageId lang=$language chunks=${chunks.size}")
        return true
    }

    /** Stop any current speech and clear [speakingMessageId]. */
    fun stop() {
        tts.stop()
        _speakingMessageId.value = null
        currentMessageId = null
        lastChunkUtteranceId = null
    }

    /** Free the engine. Call from Activity.onDestroy (single Activity app). */
    fun shutdown() {
        try {
            tts.stop()
            tts.shutdown()
        } catch (t: Throwable) {
            Log.w(TAG, "shutdown error", t)
        }
        ready.set(false)
        _speakingMessageId.value = null
        currentMessageId = null
        lastChunkUtteranceId = null
    }

    // ------------------------------------------------------------------
    // Helpers (kept internal so tests can exercise them if needed later).
    // ------------------------------------------------------------------

    private fun ttsLocaleFor(language: String): Locale = when (language) {
        "pt-PT", "pt" -> Locale("pt", "PT")
        "gu"          -> Locale("gu", "IN")
        else          -> Locale.US
    }

    companion object {
        private const val TAG = "TTS"

        // Approx max utterance length before we split. Android's spoken
        // buffer is engine-specific; on modern engines (Google TTS) the safe
        // ceiling for a single utterance is around 4000 chars. Paragraphs
        // in chat replies rarely exceed a few hundred, so this only fires as
        // a defensive fallback for pathological cases.
        private const val MAX_UTTERANCE_CHARS = 3500

        /**
         * Prepare assistant text for TTS: strip Markdown formatting, expand
         * units and abbreviations the engine would otherwise spell out literally,
         * and normalise punctuation/dates so the result sounds natural in pt-PT.
         */
        internal fun stripMarkdown(input: String): String {
            var s = input

            // ── Markdown formatting ──────────────────────────────────────────
            s = s.replace(Regex("(?s)```.*?```"), " ")
            s = s.replace(Regex("`([^`]+)`"), "$1")
            s = s.replace(Regex("!\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            s = s.replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
            s = s.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
            s = s.replace(Regex("__([^_]+)__"), "$1")
            s = s.replace(Regex("(?<![*])\\*([^*\\n]+)\\*(?![*])"), "$1")
            s = s.replace(Regex("(?<![_])_([^_\\n]+)_(?![_])"), "$1")
            s = s.replace(Regex("(?m)^\\s*#{1,6}\\s*"), "")  // headings (including indented)
            s = s.replace("#", " ")                                // any surviving # → silence
            s = s.replace(Regex("(?m)^\\s*[-*_]{3,}\\s*$"), "\n")
            s = s.replace(Regex("(?m)^\\s*[%=]{3,}\\s*$"), "\n")
            s = s.replace(Regex("(?m)^\\s*[\\[{][^\\n]*?[}\\]]\\s*$"), "\n")
            s = s.replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
            s = s.replace(Regex("(?m)^\\s*\\d+\\.\\s+"), "")
            s = s.replace(Regex("\\n{3,}"), "\n\n")

            // ── Dates ────────────────────────────────────────────────────────
            // DD/MM/YYYY → "D de mês de YYYY"
            s = s.replace(Regex("\\b(0?[1-9]|[12]\\d|3[01])/(0?[1-9]|1[0-2])/(\\d{4})\\b")) { m ->
                val day = m.groupValues[1].trimStart('0').ifEmpty { "0" }
                val month = MONTH_PT[m.groupValues[2].trimStart('0').toIntOrNull() ?: 0] ?: m.groupValues[2]
                "${day} de ${month} de ${m.groupValues[3]}"
            }
            // DD/MM → "D de mês"
            s = s.replace(Regex("\\b(0?[1-9]|[12]\\d|3[01])/(0?[1-9]|1[0-2])\\b")) { m ->
                val day = m.groupValues[1].trimStart('0').ifEmpty { "0" }
                val month = MONTH_PT[m.groupValues[2].trimStart('0').toIntOrNull() ?: 0] ?: m.groupValues[2]
                "${day} de ${month}"
            }

            // ── Units attached to numbers (no space) ─────────────────────────
            // Must run before the spaced variants so we don't double-expand.
            s = s.replace(Regex("(\\d)kcal\\b"), "$1 quilocalorias")
            s = s.replace(Regex("(\\d)kg\\b"), "$1 quilos")
            s = s.replace(Regex("(\\d)g\\b(?!\\w)"), "$1 gramas")   // bare g, not "gramas"/"grandes" etc.
            s = s.replace(Regex("(\\d)mg\\b"), "$1 miligramas")
            s = s.replace(Regex("(\\d)ml\\b"), "$1 mililitros")
            s = s.replace(Regex("(\\d)km\\b"), "$1 quilómetros")
            s = s.replace(Regex("(\\d)cm\\b"), "$1 centímetros")
            s = s.replace(Regex("(\\d)min\\b"), "$1 minutos")
            s = s.replace(Regex("(\\d)h\\b(?!\\w)"), "$1 horas")
            s = s.replace(Regex("(\\d)bpm\\b"), "$1 batimentos por minuto")
            s = s.replace(Regex("(\\d)kJ\\b"), "$1 quilojoules")

            // ── Spaced unit forms ─────────────────────────────────────────────
            // "N kcal/day" → "N quilocalorias por dia"
            s = s.replace(Regex("\\bkcal/day\\b"), "quilocalorias por dia")
            s = s.replace(Regex("\\bkcal/dia\\b"), "quilocalorias por dia")
            s = s.replace(Regex("\\bkcal\\b"), "quilocalorias")
            s = s.replace(Regex("\\bkg\\b"), "quilos")
            s = s.replace(Regex("(?<=\\d )g\\b(?!\\w)"), "gramas")
            s = s.replace(Regex("\\bmg\\b"), "miligramas")
            s = s.replace(Regex("\\bml\\b"), "mililitros")
            s = s.replace(Regex("\\bkm\\b"), "quilómetros")
            s = s.replace(Regex("\\bcm\\b"), "centímetros")
            s = s.replace(Regex("\\bbpm\\b"), "batimentos por minuto")
            s = s.replace(Regex("\\bkJ\\b"), "quilojoules")

            // ── Macro compact notation ────────────────────────────────────────
            // "P30/C45/F12" → "proteína 30, carbos 45, gordura 12"
            s = s.replace(Regex("\\bP(\\d+(?:\\.\\d+)?)/C(\\d+(?:\\.\\d+)?)/F(\\d+(?:\\.\\d+)?)\\b")) { m ->
                "proteína ${m.groupValues[1]}, carbos ${m.groupValues[2]}, gordura ${m.groupValues[3]}"
            }
            // "8g/45g" → "8 gramas de 45" (free-sugar / sat-fat ratio pattern)
            s = s.replace(Regex("(\\d+(?:\\.\\d+)?)g/(\\d+(?:\\.\\d+)?)g\\b")) { m ->
                "${m.groupValues[1]} gramas de ${m.groupValues[2]}"
            }

            // ── Percentage points ─────────────────────────────────────────────
            // "+1.2 pp" / "-0.5pp" → "+1.2 pontos percentuais"
            s = s.replace(Regex("([+-]?\\d+(?:\\.\\d+)?)\\s*pp\\b"), "$1 pontos percentuais")

            // ── Comparison operators ──────────────────────────────────────────
            s = s.replace("≥", "pelo menos ")
            s = s.replace("≤", "no máximo ")
            s = s.replace(">", " mais de ")    // only when clearly numeric context; broad replace is fine for TTS
            s = s.replace("<", " menos de ")

            // ── Middle-dot separator → comma pause ────────────────────────────
            s = s.replace("·", ",")

            // ── Arrows / special symbols ──────────────────────────────────────
            s = s.replace("≈", "aproximadamente ")
            s = s.replace("→", " ")
            s = s.replace("←", " ")
            s = s.replace("↑", " ")
            s = s.replace("↓", " ")
            s = s.replace("•", " ")
            s = s.replace("–", ", ")
            s = s.replace("—", ", ")
            s = s.replace("~", "aproximadamente ")

            // ── Numeric ranges ────────────────────────────────────────────────
            s = s.replace(Regex("(\\d)-(\\d)"), "$1 a $2")
            s = s.replace(Regex(" - "), ", ")

            // ── Residual punctuation ──────────────────────────────────────────
            s = s.replace(Regex("[\\[\\]{}]"), "")
            s = s.replace(Regex("%{2,}"), "")

            return s.trim()
        }

        private val MONTH_PT = mapOf(
            1 to "janeiro", 2 to "fevereiro", 3 to "março", 4 to "abril",
            5 to "maio", 6 to "junho", 7 to "julho", 8 to "agosto",
            9 to "setembro", 10 to "outubro", 11 to "novembro", 12 to "dezembro"
        )

        /**
         * Split cleaned text into TTS-safe chunks. First by paragraph
         * (`\n\n`), then any surviving paragraph over [MAX_UTTERANCE_CHARS]
         * gets split on sentence boundaries.
         */
        internal fun chunkForTts(text: String): List<String> {
            if (text.isBlank()) return emptyList()
            val paragraphs = text.split(Regex("\\n{2,}")).map { it.trim() }.filter { it.isNotEmpty() }
            val out = mutableListOf<String>()
            for (p in paragraphs) {
                if (p.length <= MAX_UTTERANCE_CHARS) {
                    out += p
                    continue
                }
                // Fallback: split on sentence enders, greedy accumulate.
                val sentences = p.split(Regex("(?<=[.!?…])\\s+"))
                val buf = StringBuilder()
                for (s in sentences) {
                    if (buf.isNotEmpty() && buf.length + s.length + 1 > MAX_UTTERANCE_CHARS) {
                        out += buf.toString().trim()
                        buf.clear()
                    }
                    if (buf.isNotEmpty()) buf.append(' ')
                    buf.append(s)
                }
                if (buf.isNotEmpty()) out += buf.toString().trim()
            }
            return out
        }

        // Referenced only to silence "unused" on Build if we later gate a
        // feature by SDK level; safe to remove.
        @Suppress("unused")
        private val CURRENT_SDK = Build.VERSION.SDK_INT
    }
}
