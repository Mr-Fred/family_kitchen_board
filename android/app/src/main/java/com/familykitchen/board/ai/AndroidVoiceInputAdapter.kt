package com.familykitchen.board.ai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.familykitchen.board.ports.VoiceInputPort
import com.familykitchen.board.ports.VoiceInputState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Main-thread Android SpeechRecognizer + RecognizerIntent fallback adapter for en-US and fr-FR.
// Paired with Android 12+ <queries> visibility in AndroidManifest.xml and clean destroy() teardown.

/**
 * Production Android implementation of [VoiceInputPort] supporting bilingual household dictation
 * (`"en-US"` and `"fr-FR"`) in `AiChefScreen`.
 *
 * Uses platform [SpeechRecognizer] on the Main thread (`Looper.getMainLooper()`) to stream live
 * interim hypotheses into [VoiceInputState.Listening] and final transcripts into
 * [VoiceInputState.Result] whenever `SpeechRecognizer.isRecognitionAvailable(context)` returns
 * `true`. Exposes [buildFallbackRecognizerIntent] and [onFallbackIntentResult] for devices where
 * direct service binding is unavailable and `ActivityResultContracts.StartActivityForResult`
 * handles `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`.
 *
 * @param context Android context used to create [SpeechRecognizer] via `applicationContext`.
 */
class AndroidVoiceInputAdapter(context: Context) : VoiceInputPort {

  // Bind SpeechRecognizer against applicationContext so Activity lifecycle changes never leak UI.
  private val appContext: Context = context.applicationContext ?: context

  // SpeechRecognizer strictly requires Main-Looper invocation for create/start/stop/destroy.
  private val mainHandler = Handler(Looper.getMainLooper())

  // Reactive state stream observed by KitchenBoardViewModel and AiChefScreen mic controls.
  private val mutableVoiceState = MutableStateFlow<VoiceInputState>(VoiceInputState.Idle)

  // Active SpeechRecognizer instance owned exclusively on the Main thread.
  private var speechRecognizer: SpeechRecognizer? = null

  // Last non-blank partial hypothesis retained in case an OEM engine fires ERROR_NO_MATCH at silence.
  private var latestPartialHypothesis: String = ""

  /** Reactive stream of current voice capture states (`Idle`, `Listening`, `Result`, `Error`). */
  override val voiceState: StateFlow<VoiceInputState> = mutableVoiceState.asStateFlow()

  /**
   * Starts an on-device speech recognition session for [languageTag] (`"en-US"` or `"fr-FR"`).
   *
   * Returns `false` synchronously when `SpeechRecognizer.isRecognitionAvailable(appContext)` is
   * `false` so the Compose UI caller can immediately launch [buildFallbackRecognizerIntent] via
   * `rememberLauncherForActivityResult(StartActivityForResult())`.
   *
   * @param languageTag Requested BCP-47 locale tag (`"en-US"` default or `"fr-FR"`).
   * @return `true` when inline [SpeechRecognizer] started; `false` when Intent fallback is needed.
   */
  override fun startListening(languageTag: String): Boolean {
    // Normalize en/fr tags to canonical BCP-47 strings before checking platform recognizer support.
    val normalizedLocale = normalizeLanguageTag(languageTag)
    val serviceAvailable = runCatching {
      SpeechRecognizer.isRecognitionAvailable(appContext)
    }.getOrDefault(false)

    if (!serviceAvailable) {
      // Signal caller to fall back to RecognizerIntent.ACTION_RECOGNIZE_SPEECH activity launcher.
      mutableVoiceState.value = VoiceInputState.Error(
        "Direct speech service unavailable; launching system voice recognizer sheet.",
      )
      return false
    }

    runOnMainThread {
      try {
        // SpeechRecognizer must be constructed and started strictly on the Main Looper thread.
        latestPartialHypothesis = ""
        val recognizer = ensureSpeechRecognizerOnMain()
        val recognizerIntent = buildStreamingRecognizerIntent(normalizedLocale)
        mutableVoiceState.value = VoiceInputState.Listening(partialTranscript = "")
        recognizer.startListening(recognizerIntent)
      } catch (err: Throwable) {
        val reason = err.message?.trim()?.takeIf { it.isNotEmpty() }
          ?: "Unable to start speech recognition."
        mutableVoiceState.value = VoiceInputState.Error(reason)
      }
    }
    return true
  }

  /**
   * Stops active microphone listening on the Main thread so [SpeechRecognizer] finalizes any
   * spoken transcript.
   */
  override fun stopListening() {
    // Dispatch stopListening on Main Looper so ongoing audio buffers flush to onResults cleanly.
    runOnMainThread {
      val active = speechRecognizer
      if (active == null) {
        if (mutableVoiceState.value is VoiceInputState.Listening) {
          mutableVoiceState.value = VoiceInputState.Idle
        }
        return@runOnMainThread
      }
      runCatching { active.stopListening() }
        .onFailure { mutableVoiceState.value = VoiceInputState.Idle }
    }
  }

  /**
   * Cancels any active session and invokes `SpeechRecognizer.destroy()` on the Main thread to
   * prevent Android binder leaks when `KitchenBoardViewModel.onCleared()` runs.
   */
  override fun release() {
    // Cancel active audio capture and destroy the underlying Binder connection on Main Looper.
    runOnMainThread {
      val active = speechRecognizer
      speechRecognizer = null
      latestPartialHypothesis = ""
      if (active != null) {
        runCatching { active.cancel() }
        runCatching { active.destroy() }
      }
      mutableVoiceState.value = VoiceInputState.Idle
    }
  }

  /**
   * Resets [voiceState] back to [VoiceInputState.Idle] after the composer consumes a finalized
   * [VoiceInputState.Result] or dismisses an error banner.
   */
  fun resetToIdle() {
    latestPartialHypothesis = ""
    mutableVoiceState.value = VoiceInputState.Idle
  }

  /**
   * Builds a configured `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` [Intent] for [languageTag]
   * when launching the system speech sheet fallback via `StartActivityForResult`.
   *
   * @param languageTag Requested BCP-47 locale tag (`"en-US"` or `"fr-FR"`).
   * @return Configured speech recognition [Intent].
   */
  fun buildFallbackRecognizerIntent(languageTag: String = VoiceInputPort.LOCALE_EN_US): Intent =
    Companion.buildFallbackRecognizerIntent(languageTag)

  /**
   * Processes the Activity result returned from [buildFallbackRecognizerIntent] and updates
   * [voiceState] to [VoiceInputState.Result] or [VoiceInputState.Error].
   *
   * @param resultCode Activity result code (`Activity.RESULT_OK` or `Activity.RESULT_CANCELED`).
   * @param data Optional result [Intent] carrying `RecognizerIntent.EXTRA_RESULTS`.
   * @return Emitted [VoiceInputState].
   */
  fun onFallbackIntentResult(resultCode: Int, data: Intent?): VoiceInputState {
    val resolvedState = extractTranscriptFromIntentResult(resultCode, data)
    mutableVoiceState.value = resolvedState
    return resolvedState
  }

  // Reuse or lazily allocate SpeechRecognizer on the Main thread, cancelling stale sessions first.
  private fun ensureSpeechRecognizerOnMain(): SpeechRecognizer {
    val existing = speechRecognizer
    if (existing != null) {
      runCatching { existing.cancel() }
      return existing
    }
    return SpeechRecognizer.createSpeechRecognizer(appContext).also { created ->
      created.setRecognitionListener(createRecognitionListener())
      speechRecognizer = created
    }
  }

  // Bridge Android RecognitionListener callbacks into immutable VoiceInputState emissions.
  private fun createRecognitionListener(): RecognitionListener =
    object : RecognitionListener {
      override fun onReadyForSpeech(params: Bundle?) {
        mutableVoiceState.value = VoiceInputState.Listening(partialTranscript = latestPartialHypothesis)
      }

      override fun onBeginningOfSpeech() = Unit

      override fun onRmsChanged(rmsdB: Float) = Unit

      override fun onBufferReceived(buffer: ByteArray?) = Unit

      override fun onEndOfSpeech() = Unit

      override fun onPartialResults(partialResults: Bundle?) {
        // Stream live interim hypotheses into the AI Chef composer while the parent speaks.
        val partial = extractFirstNonBlankMatch(partialResults)
        if (partial.isNotEmpty()) {
          latestPartialHypothesis = partial
          mutableVoiceState.value = VoiceInputState.Listening(partialTranscript = partial)
        }
      }

      override fun onResults(results: Bundle?) {
        // Prefer final top recognition result, falling back to last streamed partial if needed.
        val finalTranscript = extractFirstNonBlankMatch(results).ifEmpty { latestPartialHypothesis }
        latestPartialHypothesis = ""
        mutableVoiceState.value = when {
          finalTranscript.isNotEmpty() -> VoiceInputState.Result(transcript = finalTranscript)
          else -> VoiceInputState.Error("No speech recognized. Tap the microphone to try again.")
        }
      }

      override fun onError(error: Int) {
        // Recover gracefully if the Android recognizer streamed partial words before silence timeout.
        val recoveredPartial = latestPartialHypothesis.trim()
        latestPartialHypothesis = ""
        if (
          recoveredPartial.isNotEmpty() &&
          (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        ) {
          mutableVoiceState.value = VoiceInputState.Result(transcript = recoveredPartial)
          return
        }
        if (error == SpeechRecognizer.ERROR_CLIENT) {
          mutableVoiceState.value = VoiceInputState.Idle
          return
        }
        mutableVoiceState.value = VoiceInputState.Error(mapSpeechErrorCode(error))
      }

      override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

  private fun runOnMainThread(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      block()
    } else {
      mainHandler.post(block)
    }
  }

  companion object {
    /**
     * Normalizes shorthand or underscore locale codes (`"fr"`, `"fr_FR"`, `"en"`, `"en_US"`) to
     * canonical BCP-47 tags (`"en-US"` or `"fr-FR"`).
     *
     * @param rawLanguageTag Caller-supplied locale string.
     * @return Normalized BCP-47 locale tag.
     */
    fun normalizeLanguageTag(rawLanguageTag: String): String {
      val cleaned = rawLanguageTag.trim().replace('_', '-')
      val lower = cleaned.lowercase()
      return when {
        lower.isEmpty() || lower == "en" || lower.startsWith("en-") -> VoiceInputPort.LOCALE_EN_US
        lower == "fr" || lower.startsWith("fr-") -> VoiceInputPort.LOCALE_FR_FR
        else -> cleaned
      }
    }

    /**
     * Static builder creating a `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` fallback [Intent] for
     * `en-US` or `fr-FR` usable directly from Jetpack Compose activity-result launchers.
     *
     * @param languageTag Requested BCP-47 locale tag (`"en-US"` default or `"fr-FR"`).
     * @return Configured fallback [Intent].
     */
    fun buildFallbackRecognizerIntent(languageTag: String = VoiceInputPort.LOCALE_EN_US): Intent {
      val normalized = normalizeLanguageTag(languageTag)
      val promptLabel = when (normalized) {
        VoiceInputPort.LOCALE_FR_FR -> "Parlez au Chef IA..."
        else -> "Speak to AI Chef..."
      }
      return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, normalized)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, normalized)
        putExtra(RecognizerIntent.EXTRA_PROMPT, promptLabel)
      }
    }

    /**
     * Pure helper converting an Activity result from [buildFallbackRecognizerIntent] into a
     * [VoiceInputState].
     *
     * @param resultCode Activity result code (`Activity.RESULT_OK`, etc.).
     * @param data Optional result [Intent] containing `RecognizerIntent.EXTRA_RESULTS`.
     * @return Parsed [VoiceInputState].
     */
    fun extractTranscriptFromIntentResult(resultCode: Int, data: Intent?): VoiceInputState {
      if (resultCode != Activity.RESULT_OK || data == null) {
        return VoiceInputState.Idle
      }
      val matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
      val bestMatch = matches?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
      return when {
        bestMatch.isNotEmpty() -> VoiceInputState.Result(transcript = bestMatch)
        else -> VoiceInputState.Error("No spoken words captured from voice input.")
      }
    }

    // Enable EXTRA_PARTIAL_RESULTS so live spoken words stream before silence timeout triggers.
    private fun buildStreamingRecognizerIntent(normalizedLocale: String): Intent =
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, normalizedLocale)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, normalizedLocale)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
      }

    // Extract top non-empty recognition candidate from SpeechRecognizer result bundles safely.
    private fun extractFirstNonBlankMatch(bundle: Bundle?): String {
      if (bundle == null) return ""
      val matches = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
      return matches?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    // Translate platform SpeechRecognizer integer error codes into actionable offline guidance.
    private fun mapSpeechErrorCode(errorCode: Int): String =
      when (errorCode) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
          "Microphone permission is required for voice dictation."
        SpeechRecognizer.ERROR_AUDIO ->
          "Microphone audio capture error. Check that another app is not recording."
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> "No speech detected. Tap the microphone and speak clearly."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
          "Speech recognizer is busy finalizing the previous utterance."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER,
        -> "Offline speech pack unavailable for this language—install offline voice data in Android Settings."
        else -> "Voice recognition unavailable (code $errorCode)."
      }
  }
}
