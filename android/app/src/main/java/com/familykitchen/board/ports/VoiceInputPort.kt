package com.familykitchen.board.ports

import kotlinx.coroutines.flow.StateFlow

// Pure voice-input boundary port isolating Android SpeechRecognizer and RecognizerIntent APIs.
// Supports bilingual household dictation (`en-US` and `fr-FR`) with live partial transcripts.

/**
 * Lifecycle state machine for offline-capable speech-to-text input in `AiChefScreen`.
 */
sealed interface VoiceInputState {
  // Default resting state when the composer microphone button is inactive.
  /** Microphone is idle and not recording audio. */
  data object Idle : VoiceInputState

  /**
   * Actively listening to user speech and streaming live partial recognition hypotheses.
   *
   * @property partialTranscript Interim spoken text recognized so far (empty at session start).
   */
  data class Listening(val partialTranscript: String = "") : VoiceInputState

  /**
   * Completed speech recognition session with a finalized utterance ready for the composer.
   *
   * @property transcript Finalized transcribed text.
   */
  data class Result(val transcript: String) : VoiceInputState

  /**
   * Recoverable speech recognition or permission failure surfaced cleanly in UI.
   *
   * @property message Human-readable reason explaining why voice capture could not complete.
   */
  data class Error(val message: String) : VoiceInputState
}

/**
 * Hexagonal port abstracting Android `SpeechRecognizer` and `RecognizerIntent` voice capture
 * (`en-US` and `fr-FR` household locale support) away from pure domain and Compose UI layers.
 */
interface VoiceInputPort {
  /** Reactive stream of current voice capture states observed by `KitchenBoardViewModel`. */
  val voiceState: StateFlow<VoiceInputState>

  // Dispatched on Main thread in AndroidVoiceInputAdapter; returns false to trigger Intent fallback.
  /**
   * Begins an on-device speech recognition session for the requested BCP-47 [languageTag].
   *
   * @param languageTag BCP-47 locale tag (`"en-US"` default or `"fr-FR"`).
   * @return `true` when native speech listening started, or `false` if unavailable/fallback needed.
   */
  fun startListening(languageTag: String = LOCALE_EN_US): Boolean

  /** Stops active audio capture and requests finalization of the current utterance. */
  fun stopListening()

  // Must invoke SpeechRecognizer.destroy() on ViewModel.onCleared() to avoid binder leaks.
  /** Releases underlying platform `SpeechRecognizer` resources on `ViewModel.onCleared()`. */
  fun release()

  companion object {
    /** Default English (United States) BCP-47 voice recognition locale tag. */
    const val LOCALE_EN_US: String = "en-US"

    /** Supported French (France) BCP-47 voice recognition locale tag for household bilingual use. */
    const val LOCALE_FR_FR: String = "fr-FR"
  }
}
