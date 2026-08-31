package com.example.data.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Streaming speech-to-text wrapper for the Voice Mode toggle.
 *
 * Design mirrors `itopsea/ITOpsEA/SpeechManager.swift`:
 *  - [startListening] fires `Intent(ACTION_RECOGNIZE_SPEECH)` with partial
 *    results enabled and a language tag derived from the profile.
 *  - Partial results feed [currentPartial] so the input field can echo what
 *    the user is saying in real time — visible feedback = trust.
 *  - A **silence timer** starts on every partial result. When it elapses
 *    (default 1500 ms) we treat the utterance as complete and fire
 *    [onFinal] with the accumulated text. This is the auto-submit behaviour
 *    the plan calls out; without it Voice Mode would need a manual stop
 *    button which defeats the hands-free UX.
 *  - Android's `SpeechRecognizer` itself already emits `onResults` after its
 *    own end-of-speech heuristic. That path is kept as a belt-and-braces
 *    trigger — whichever fires first wins, and the other is a no-op via
 *    [handled] guard.
 *
 * NOT thread-safe by design: create + drive from the main thread (Compose
 * side-effects). All callbacks are already delivered on the main looper by
 * the platform recognizer.
 */
class SpeechRecognizerController(context: Context) {

    private val appCtx = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private val _currentPartial = MutableStateFlow("")
    val currentPartial: StateFlow<String> = _currentPartial.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _permissionRequired = MutableStateFlow(false)
    val permissionRequired: StateFlow<Boolean> = _permissionRequired.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var onPartial: ((String) -> Unit)? = null
    private var onFinal: ((String) -> Unit)? = null
    private var onSessionEnded: ((EndReason) -> Unit)? = null
    private var silenceRunnable: Runnable? = null
    private var windowRunnable: Runnable? = null
    private var handled = false

    /**
     * Why a session ended without producing a final transcript. Lets the
     * caller (VM) leave the hands-free `Listening` state instead of getting
     * stuck with a live-looking mic pill and no recognizer attached.
     */
    enum class EndReason { SILENCE, ERROR }

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(appCtx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Begin listening. If mic permission is missing, [permissionRequired] flips
     * true and the call is a no-op — the caller (VM/Activity) is responsible
     * for launching the permission dialog and calling this again.
     */
    fun startListening(
        language: String,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onSessionEnded: ((EndReason) -> Unit)? = null,
        silenceMillis: Long = DEFAULT_SILENCE_MS
    ) {
        if (!hasMicPermission()) {
            _permissionRequired.value = true
            Log.i(TAG, "startListening blocked — RECORD_AUDIO not granted")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(appCtx)) {
            Log.w(TAG, "SpeechRecognizer not available on this device")
            return
        }
        // Cancel any in-flight session.
        internalStop(clearFlow = true)

        this.onPartial = onPartial
        this.onFinal = onFinal
        this.onSessionEnded = onSessionEnded
        this.handled = false

        val rec = SpeechRecognizer.createSpeechRecognizer(appCtx).also { recognizer = it }
        rec.setRecognitionListener(listener(silenceMillis))

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, sttLocaleFor(language).toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, sttLocaleFor(language).toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appCtx.packageName)
            // These tune the platform's own end-of-speech heuristics. Kept
            // wider than our silence timer so our timer wins first in most
            // cases (predictable UX).
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1_500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
        }

        _currentPartial.value = ""
        _isListening.value = true
        try {
            rec.startListening(intent)
            Log.i(TAG, "listening lang=$language silence=${silenceMillis}ms")
            scheduleListenWindow()
        } catch (t: Throwable) {
            Log.w(TAG, "startListening threw", t)
            internalStop(clearFlow = true)
        }
    }

    /** Stop and discard whatever was in flight. */
    fun stopListening() {
        internalStop(clearFlow = true)
    }

    /** Cancel silently — no final callback, no error. */
    fun cancel() {
        try { recognizer?.cancel() } catch (_: Throwable) { /* noop */ }
        internalStop(clearFlow = true)
    }

    private fun internalStop(clearFlow: Boolean) {
        silenceRunnable?.let { handler.removeCallbacks(it) }
        silenceRunnable = null
        windowRunnable?.let { handler.removeCallbacks(it) }
        windowRunnable = null
        try {
            recognizer?.stopListening()
            recognizer?.destroy()
        } catch (_: Throwable) { /* noop */ }
        recognizer = null
        _isListening.value = false
        if (clearFlow) _currentPartial.value = ""
    }

    /** Reset the [permissionRequired] latch after the user has been prompted. */
    fun clearPermissionFlag() {
        _permissionRequired.value = false
    }

    private fun listener(silenceMillis: Long) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { /* noop */ }
        override fun onBeginningOfSpeech() { /* noop */ }
        override fun onRmsChanged(rmsdB: Float) { /* noop — could drive VU meter later */ }
        override fun onBufferReceived(buffer: ByteArray?) { /* noop */ }
        override fun onEvent(eventType: Int, params: Bundle?) { /* noop */ }

        override fun onEndOfSpeech() {
            // Platform detected end; we still let the silence timer or
            // onResults trigger the finalisation to keep behaviour
            // predictable across OEMs.
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val hyps = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = hyps?.firstOrNull()?.trim().orEmpty()
            if (text.isNotEmpty() && text != _currentPartial.value) {
                _currentPartial.value = text
                onPartial?.invoke(text)
                scheduleSilenceFinalise(silenceMillis, text)
            }
        }

        override fun onResults(results: Bundle?) {
            val hyps = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = hyps?.firstOrNull()?.trim().orEmpty()
            finalise(text)
        }

        override fun onError(error: Int) {
            val name = errorName(error)
            Log.i(TAG, "onError=$name")
            // ERROR_NO_MATCH / ERROR_SPEECH_TIMEOUT: fall back to whatever
            // partial we already have — often the user did speak but the
            // engine gave up. Anything else: drop the session.
            val fallback = _currentPartial.value
            if ((error == SpeechRecognizer.ERROR_NO_MATCH ||
                 error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) &&
                fallback.isNotBlank()) {
                finalise(fallback)
            } else {
                internalStop(clearFlow = false)
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    _permissionRequired.value = true
                } else {
                    // Session died without capturing anything. Tell the caller
                    // so the hands-free pill can leave `Listening` instead of
                    // looking active over a dead recognizer (user would speak
                    // into a mic that no longer listens).
                    val reason =
                        if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                            EndReason.SILENCE
                        } else {
                            EndReason.ERROR
                        }
                    onSessionEnded?.invoke(reason)
                }
            }
        }
    }

    private fun scheduleSilenceFinalise(silenceMillis: Long, latest: String) {
        silenceRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            if (!handled && _isListening.value) {
                finalise(latest)
            }
        }
        silenceRunnable = r
        handler.postDelayed(r, silenceMillis)
    }

    private fun finalise(text: String) {
        if (handled) return
        handled = true
        silenceRunnable?.let { handler.removeCallbacks(it) }
        silenceRunnable = null
        windowRunnable?.let { handler.removeCallbacks(it) }
        windowRunnable = null
        val cb = onFinal
        val ended = onSessionEnded
        internalStop(clearFlow = false)
        // Reset partial after we've captured the text for the callback so
        // the input field doesn't flicker back to empty before submit runs.
        val finalText = text.ifBlank { _currentPartial.value }
        _currentPartial.value = ""
        if (finalText.isNotBlank()) {
            cb?.invoke(finalText)
            Log.i(TAG, "final len=${finalText.length}")
        } else {
            Log.i(TAG, "final empty — dropping")
            // Nothing captured — surface it so the caller leaves `Listening`.
            ended?.invoke(EndReason.SILENCE)
        }
    }

    /**
     * Hard cap on a single listening turn. The [scheduleSilenceFinalise] timer
     * only arms *after* the first partial arrives, so if the user never speaks
     * we would otherwise depend entirely on the OEM's own heuristics (which
     * may keep the mic open indefinitely). This bounds the window: if nothing
     * was captured within [LISTEN_WINDOW_MS], stop and report SILENCE.
     */
    private fun scheduleListenWindow() {
        windowRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            if (!handled && _isListening.value) {
                Log.i(TAG, "listen window elapsed — stopping")
                val ended = onSessionEnded
                internalStop(clearFlow = true)
                ended?.invoke(EndReason.SILENCE)
            }
        }
        windowRunnable = r
        handler.postDelayed(r, LISTEN_WINDOW_MS)
    }

    private fun sttLocaleFor(language: String): Locale = when (language) {
        "pt-PT", "pt" -> Locale("pt", "PT")
        "gu"          -> Locale("gu", "IN")
        else          -> Locale.US
    }

    private fun errorName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "NO_PERMISSION"
        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "BUSY"
        SpeechRecognizer.ERROR_SERVER -> "SERVER"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
        else -> "UNKNOWN($code)"
    }

    companion object {
        private const val TAG = "STT"
        private const val DEFAULT_SILENCE_MS = 1_500L
        /** Max duration of one listening turn before we give up on silence. */
        private const val LISTEN_WINDOW_MS = 20_000L
    }
}
