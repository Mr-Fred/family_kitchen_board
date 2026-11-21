package com.familykitchen.board.ports

import com.familykitchen.board.domain.DownloadableGemmaPreset
import com.familykitchen.board.domain.EdgeDeviceProfile
import com.familykitchen.board.domain.ModelDownloadApprovalSpec
import kotlinx.coroutines.flow.StateFlow

// Pure coroutine boundary port for on-device LiteRT-LM (0.16.1) and MediaPipe GenAI (0.10.22).
// Enforces Mutex-serialized JNI access, atomic .part SAF model imports, and non-silent vision gates.

/**
 * Describes the active capabilities of a loaded on-device LLM/VLM runtime (`LiteRT-LM` or
 * `MediaPipe Tasks GenAI`).
 *
 * @property supportsText `true` when on-device text generation is ready.
 * @property supportsVision `true` **only** when multimodal image+text `Conversation` is active.
 * @property backendLabel Active hardware delegate label (e.g., `"LiteRT-LM GPU + Vision"`).
 * @property modelName Loaded model package filename (e.g., `"gemma-e2b-it-multimodal-int4.litertlm"`).
 */
data class RuntimeCapabilities(
  val supportsText: Boolean,
  val supportsVision: Boolean,
  val backendLabel: String,
  val modelName: String,
)

/**
 * Lifecycle state machine exposed via [AiEdgePort.runtimeState] to `AiChefScreen`.
 */
sealed interface AiRuntimeState {
  // Cold-start state before checking app-scoped filesDir/models or externalFilesDir/models.
  /** Cold-start state prior to scanning app-scoped model directories. */
  data object Uninitialized : AiRuntimeState

  /** Scanning `filesDir/models` and `getExternalFilesDir("models")` for valid model packages. */
  data object Discovering : AiRuntimeState

  // Atomic .part transfer state: covers both local SAF copies and approved Hugging Face downloads.
  /**
   * Streaming a user-selected SAF model or approved Hugging Face download into
   * `filesDir/models/<name>.part` prior to atomic rename.
   *
   * @property fileName Display filename being imported or downloaded.
   * @property progressFraction Byte copy progress clamped in `0f..1f` (`0f` when stream size is unknown).
   * @property copiedBytes Verified byte count copied to staging storage so far (`0L` default).
   * @property totalBytes Expected stream byte length, or `-1L` when unknown.
   * @property isNetworkDownload `true` when streaming from Hugging Face CDN rather than local SAF.
   */
  data class Importing(
    val fileName: String,
    val progressFraction: Float,
    val copiedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val isNetworkDownload: Boolean = false,
  ) : AiRuntimeState

  /**
   * Initializing native OpenCL GPU or CPU inference buffers for [modelName].
   *
   * @property modelName Model package filename currently initializing.
   */
  data class Loading(val modelName: String) : AiRuntimeState

  // Ready state carries explicit supportsVision so UI can render accurate vision badges/gates.
  /**
   * Model is initialized and ready for serialized inference calls matching [capabilities].
   *
   * @property capabilities Verified text and multimodal vision capability flags.
   */
  data class Ready(val capabilities: RuntimeCapabilities) : AiRuntimeState

  /**
   * Recoverable initialization, import, or missing-model state surfaced cleanly in `AiChefScreen`.
   *
   * @property message Actionable user-facing explanation of why the runtime is unavailable.
   */
  data class Error(val message: String) : AiRuntimeState

  /**
   * `true` whenever app-scoped model discovery, SAF `.part` stream copying, or native GPU/CPU
   * weight initialization is actively running in the background.
   */
  val isBackgroundBusy: Boolean
    get() = this is Discovering || this is Importing || this is Loading
}

/**
 * Supported on-device model package formats discovered in app-scoped storage.
 */
enum class ModelFileFormat {
  // .litertlm packages are prioritized ahead of text-only .task and .bin bundles.
  /** Primary LiteRT-LM `.litertlm` package supporting GPU text and optional multimodal vision. */
  LITERT_LM,

  /** MediaPipe GenAI `.task` bundle supporting text-only inference (`supportsVision = false`). */
  MEDIAPIPE_TASK,

  /** MediaPipe GenAI `.bin` weights supporting text-only inference (`supportsVision = false`). */
  MEDIAPIPE_BIN,
}

/**
 * Metadata describing a validated (`> 100 MB`) on-device model file discovered in app storage.
 *
 * @property fileName File basename (for example, `"gemma-3-1b-it-gpu-int4.litertlm"`).
 * @property absolutePath Scoped filesystem path inside `filesDir/models` or `externalFilesDir/models`.
 * @property sizeBytes Verified complete file size in bytes (`> 100 MB`, never a `.part` temp file).
 * @property format Package format (`LITERT_LM` prioritized ahead of `MEDIAPIPE_TASK` / `MEDIAPIPE_BIN`).
 * @property supportsVisionCandidate Whether the package format can initialize a vision backend.
 * @property recommendedProfile Suggested Samsung Galaxy S22 / S22 Ultra hardware profile.
 */
data class DiscoveredModelInfo(
  val fileName: String,
  val absolutePath: String,
  val sizeBytes: Long,
  val format: ModelFileFormat = ModelFileFormat.LITERT_LM,
  val supportsVisionCandidate: Boolean = format == ModelFileFormat.LITERT_LM,
  val recommendedProfile: EdgeDeviceProfile = EdgeDeviceProfile.GALAXY_S22_GEMMA_1B_INT4,
)

/**
 * Hexagonal port for thread-safe on-device text and multimodal vision inference on Samsung Galaxy
 * S22 and Galaxy S22 Ultra (`LiteRT-LM 0.16.1` primary + `MediaPipe Tasks GenAI 0.10.22` fallback).
 */
interface AiEdgePort {
  /** Reactive lifecycle state stream observed by `KitchenBoardViewModel` and `AiChefScreen`. */
  val runtimeState: StateFlow<AiRuntimeState>

  // Discovers models in scoped app directories with zero MANAGE_EXTERNAL_STORAGE permission.
  /**
   * Scans app-scoped model directories (`filesDir/models` and `getExternalFilesDir("models")`) and
   * initializes the highest-priority valid model package if present.
   *
   * @return Resulting [AiRuntimeState] (`Ready` when a model loaded, or `Error` if none found).
   */
  suspend fun autoDiscoverAndInitialize(): AiRuntimeState

  /**
   * Copies a user-selected SAF document URI into `filesDir/models` via an atomic `*.part` temp
   * file and initializes the imported model.
   *
   * @param sourceUriString Scoped `content://` URI returned by `ActivityResultContracts.OpenDocument`.
   * @param displayName Optional display filename resolved from document metadata.
   * @return Resulting [AiRuntimeState] after import and initialization.
   */
  suspend fun importAndLoadModel(sourceUriString: String, displayName: String? = null): AiRuntimeState

  /**
   * Runs a serialized on-device text completion turn and returns raw model text.
   *
   * @param prompt Grounded system + user prompt constructed by `AiMenuAdvisorEngine`.
   * @return Raw model completion string.
   */
  suspend fun generateText(prompt: String): String

  // Hard vision gate: implementations MUST throw VisionNotSupportedException if !supportsVision.
  /**
   * Runs an authentic multimodal vision + text turn against the preprocessed JPEG at
   * [preprocessedImagePath] on a fresh/reset LiteRT-LM `Conversation`.
   *
   * @param preprocessedImagePath Absolute path to the EXIF-normalized, `<= 1280px` JPEG file.
   * @param prompt Neutral visual inspection prompt from `AiMenuAdvisorEngine.buildVisualIntakePrompt`.
   * @return Raw multimodal model completion string.
   */
  suspend fun analyzeImageWithPrompt(preprocessedImagePath: String, prompt: String): String

  /**
   * Resets any active on-device `Conversation` context without unloading model weights from RAM.
   */
  suspend fun resetConversation() = Unit

  // Approval-gated Hugging Face Gemma 4 (.litertlm) inspection, resumable download, & cancellation.
  /**
   * Inspects local free storage, active network metering (`UNMETERED_WIFI`, `METERED_CELLULAR`,
   * `OFFLINE_OR_UNKNOWN`), and any resumable `.part` bytes for [preset] without opening a socket.
   *
   * @param preset Candidate Gemma 4 `.litertlm` preset to inspect.
   * @return Populated [ModelDownloadApprovalSpec] for user approval.
   */
  suspend fun inspectDownloadPreconditions(
    preset: DownloadableGemmaPreset = DownloadableGemmaPreset.GEMMA_4_E2B_VISION,
  ): ModelDownloadApprovalSpec = ModelDownloadApprovalSpec(preset = preset)

  /**
   * Downloads (or resumes via HTTP `Range`) the approved [preset] into `filesDir/models` via an
   * atomic `.part` staging file and initializes the resulting `.litertlm` runtime.
   *
   * @param preset User-approved Gemma 4 `.litertlm` preset to download and load.
   * @return Resulting [AiRuntimeState] (`Ready` on success, or `Error` on failure).
   */
  suspend fun downloadAndLoadPresetModel(
    preset: DownloadableGemmaPreset = DownloadableGemmaPreset.GEMMA_4_E2B_VISION,
  ): AiRuntimeState = runtimeState.value

  /**
   * Requests immediate cancellation of an active preset model download and cleans up its temporary
   * `.part` staging file.
   */
  fun cancelActiveDownload() = Unit

  // Deterministic native cleanup invoked on model reload and ViewModel.onCleared().
  /**
   * Deterministically closes active native `Conversation`, `Session`, `Engine`, and `LlmInference`
   * handles on model reload or `ViewModel.onCleared()`.
   */
  fun close()
}
