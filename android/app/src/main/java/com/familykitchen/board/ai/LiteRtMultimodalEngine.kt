package com.familykitchen.board.ai

import android.content.Context
import android.net.Uri
import com.familykitchen.board.domain.AiMenuAdvisorEngine
import com.familykitchen.board.domain.DownloadableGemmaPreset
import com.familykitchen.board.domain.KitchenBoardException
import com.familykitchen.board.domain.ModelDownloadApprovalSpec
import com.familykitchen.board.domain.ModelNotReadyException
import com.familykitchen.board.domain.ValidationException
import com.familykitchen.board.domain.VisionNotSupportedException
import com.familykitchen.board.ports.AiEdgePort
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ports.DiscoveredModelInfo
import com.familykitchen.board.ports.ModelFileFormat
import com.familykitchen.board.ports.RuntimeCapabilities
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.Session
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Thread-safe LiteRT-LM 0.16.1 + MediaPipe GenAI 0.10.22 adapter with non-silent Vision Gate.
// Guards every native JNI operation behind engineMutex and resets Conversation per photo scan.

/**
 * Production Android implementation of [AiEdgePort] integrating `litertlm-android:0.16.1` (primary
 * OpenCL GPU + multimodal vision engine on Samsung Galaxy S22 & S22 Ultra) with
 * `tasks-genai:0.10.22` (`maxTokens = 1024`) fallback for `.task` and `.bin` text bundles.
 *
 * Key architectural guarantees:
 * 1. **Serialized JNI Access**: Every native initialization, text completion, image scan, and
 *    teardown call executes inside [engineMutex] to prevent concurrent native `SIGSEGV` crashes.
 * 2. **Hard Non-Silent Vision Gate**: Calling [analyzeImageWithPrompt] when not `Ready` throws
 *    [ModelNotReadyException], and calling it when `!capabilities.supportsVision` or `liteRtEngine`
 *    is inactive (text-only `.litertlm`, `Session` fallback, or MediaPipe `LlmInference`)
 *    **immediately** throws [VisionNotSupportedException]—never silently dropping an image.
 * 3. **Bounded 8 GB S22 KV-Cache**: Opens a fresh `Conversation` per inference turn and closes it
 *    in `finally` so multi-photo kitchen scans never exhaust RAM across repeated turns.
 *
 * @param context Android context providing `cacheDir` and `filesDir` for native shader/model caches.
 * @param modelDiscovery Scoped model file discovery and atomic `*.part` SAF importer.
 * @param ioDispatcher Dispatcher used for filesystem model discovery and SAF stream copies.
 * @param inferenceDispatcher Dispatcher used for CPU/GPU JNI inference execution.
 * @param modelDownloader Approval-gated Hugging Face `.litertlm` downloader with `.part` resume.
 */
class LiteRtMultimodalEngine(
  context: Context,
  private val modelDiscovery: ModelFileDiscovery = ModelFileDiscovery(context),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val inferenceDispatcher: CoroutineDispatcher = Dispatchers.Default,
  private val modelDownloader: OnDeviceModelDownloader = OnDeviceModelDownloader(
    context = context,
    ioDispatcher = ioDispatcher,
  ),
) : AiEdgePort {

  // Retain applicationContext so native shader caches never hold a destroyed Activity reference.
  private val appContext: Context = context.applicationContext ?: context

  // Serializes all JNI calls across initialize, generateText, analyzeImageWithPrompt, and close.
  private val engineMutex = Mutex()

  // Backing lifecycle state stream observed by KitchenBoardViewModel and AiChefScreen.
  private val mutableRuntimeState = MutableStateFlow<AiRuntimeState>(AiRuntimeState.Uninitialized)

  // Atomic single-flight guard preventing concurrent SAF .part writes from double-taps.
  private val importInFlight = AtomicBoolean(false)

  // Deferred teardown flag ensures close() invoked mid-inference releases handles safely on unlock.
  @Volatile
  private var closeRequested: Boolean = false

  // Active native handles guarded exclusively by engineMutex.
  private var liteRtEngine: Engine? = null
  private var liteRtConversation: Conversation? = null
  private var liteRtSession: Session? = null
  private var mediaPipeLlm: LlmInference? = null
  private var supportsConversationApi: Boolean = false

  /** Reactive lifecycle state stream observed by `KitchenBoardViewModel` and `AiChefScreen`. */
  override val runtimeState: StateFlow<AiRuntimeState> = mutableRuntimeState.asStateFlow()

  /**
   * Scans `filesDir/models` and `getExternalFilesDir("models")` (zero `MANAGE_EXTERNAL_STORAGE`)
   * and initializes the highest-priority valid model package (`> 100 MB`, `.litertlm` first).
   *
   * @return Resulting [AiRuntimeState] (`Ready` on success, or `Error` if no model is present).
   */
  override suspend fun autoDiscoverAndInitialize(): AiRuntimeState {
    try {
      return engineMutex.withLock {
        // Short-circuit when a verified runtime is already loaded inside engineMutex.
        closeRequested = false
        val existing = mutableRuntimeState.value
        if (existing is AiRuntimeState.Ready) return@withLock existing

        // Scan app-scoped internal/external model directories without storage permissions.
        mutableRuntimeState.value = AiRuntimeState.Discovering
        val bestCandidate = modelDiscovery.discoverModels().firstOrNull()
        when (bestCandidate) {
          null -> {
            val missingState = AiRuntimeState.Error(
              "No local on-device model (> 100 MB) found in app storage. " +
                "Import a Gemma .litertlm or .task file in AI Setup (offline menu advisor remains ready).",
            )
            mutableRuntimeState.value = missingState
            missingState
          }
          else -> initializeModelLocked(bestCandidate)
        }
      }
    } catch (cancel: CancellationException) {
      // Rethrow coroutine cancellation so structured lifecycle teardown completes promptly.
      throw cancel
    } catch (err: Throwable) {
      val cleanReason = sanitizeNativeErrorMessage(err)
      val errorState = AiRuntimeState.Error("On-device AI discovery error: $cleanReason")
      mutableRuntimeState.value = errorState
      return errorState
    } finally {
      flushDeferredCloseIfNeeded()
    }
  }

  /**
   * Streams a user-selected SAF `OpenDocument` URI into `filesDir/models` via an atomic `*.part`
   * file + `renameTo` (publishing byte progress to [runtimeState]) and initializes the model.
   *
   * @param sourceUriString Scoped `content://` URI string selected by the user.
   * @param displayName Optional display filename resolved by the Activity result caller.
   * @return Resulting [AiRuntimeState] after atomic copy and native initialization.
   */
  override suspend fun importAndLoadModel(
    sourceUriString: String,
    displayName: String?,
  ): AiRuntimeState {
    // Reject overlapping SAF imports immediately without clobbering active Importing/Loading state.
    if (!importInFlight.compareAndSet(false, true)) {
      return mutableRuntimeState.value
    }
    val initialLabel = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: "model.litertlm"
    return try {
      // Reject empty SAF URI strings before launching background I/O streams.
      if (sourceUriString.isBlank()) {
        val errState = AiRuntimeState.Error("Selected model URI was empty. Please pick a .litertlm or .task file.")
        mutableRuntimeState.value = errState
        errState
      } else {
        // Stream SAF weights outside engineMutex so UI progress updates smoothly, then lock to init.
        val parsedUri = Uri.parse(sourceUriString)
        mutableRuntimeState.value = AiRuntimeState.Importing(
          fileName = initialLabel,
          progressFraction = 0f,
          copiedBytes = 0L,
          totalBytes = -1L,
        )
        val importedInfo = modelDiscovery.importModelFromUri(parsedUri, displayName) { copied, total, fraction ->
          val safeFraction = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
          mutableRuntimeState.value = AiRuntimeState.Importing(
            fileName = initialLabel,
            progressFraction = safeFraction,
            copiedBytes = copied,
            totalBytes = total,
          )
        }
        engineMutex.withLock {
          closeRequested = false
          initializeModelLocked(importedInfo)
        }
      }
    } catch (cancel: CancellationException) {
      mutableRuntimeState.value = AiRuntimeState.Error("Model import was cancelled; partial .part file cleaned up.")
      throw cancel
    } catch (domainError: KitchenBoardException) {
      val errState = AiRuntimeState.Error(domainError.message)
      mutableRuntimeState.value = errState
      errState
    } catch (unexpected: Throwable) {
      val cleanReason = sanitizeNativeErrorMessage(unexpected)
      val errState = AiRuntimeState.Error("Failed to import model '$initialLabel': $cleanReason")
      mutableRuntimeState.value = errState
      errState
    } finally {
      importInFlight.set(false)
      flushDeferredCloseIfNeeded()
    }
  }

  /**
   * Inspects local free storage, Wi-Fi vs metered cellular status, and any resumable `.part` bytes
   * for [preset] via [modelDownloader] without opening any network connection.
   */
  override suspend fun inspectDownloadPreconditions(
    preset: DownloadableGemmaPreset,
  ): ModelDownloadApprovalSpec =
    modelDownloader.inspectPreconditions(preset)

  /**
   * Streams (or resumes via HTTP `Range`) the approved [preset] from Hugging Face CDN into
   * `filesDir/models/<fileName>.part` (publishing `AiRuntimeState.Importing(..., isNetworkDownload = true)`)
   * and initializes native LiteRT-LM weights under [engineMutex].
   */
  override suspend fun downloadAndLoadPresetModel(
    preset: DownloadableGemmaPreset,
  ): AiRuntimeState {
    // Share importInFlight single-flight guard so SAF imports and network downloads never collide.
    if (!importInFlight.compareAndSet(false, true)) {
      return mutableRuntimeState.value
    }
    return try {
      mutableRuntimeState.value = AiRuntimeState.Importing(
        fileName = preset.fileName,
        progressFraction = 0f,
        copiedBytes = 0L,
        totalBytes = preset.approxSizeBytes,
        isNetworkDownload = true,
      )
      val downloadedInfo = modelDownloader.downloadPresetModel(preset) { copied, total, fraction ->
        val safeFraction = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
        mutableRuntimeState.value = AiRuntimeState.Importing(
          fileName = preset.fileName,
          progressFraction = safeFraction,
          copiedBytes = copied,
          totalBytes = total,
          isNetworkDownload = true,
        )
      }
      engineMutex.withLock {
        closeRequested = false
        initializeModelLocked(downloadedInfo)
      }
    } catch (cancel: CancellationException) {
      mutableRuntimeState.value = AiRuntimeState.Error(
        "Cancelled Gemma model download and cleaned up temporary file.",
      )
      throw cancel
    } catch (domainError: KitchenBoardException) {
      val errState = AiRuntimeState.Error(domainError.message)
      mutableRuntimeState.value = errState
      errState
    } catch (ioError: IOException) {
      val cleanReason = sanitizeNativeErrorMessage(ioError)
      val errState = AiRuntimeState.Error(
        "Network interrupted while downloading '${preset.fileName}' ($cleanReason). " +
          "Partial download was saved — tap Download again to resume.",
      )
      mutableRuntimeState.value = errState
      errState
    } catch (unexpected: Throwable) {
      val cleanReason = sanitizeNativeErrorMessage(unexpected)
      val errState = AiRuntimeState.Error("Failed to download '${preset.fileName}': $cleanReason")
      mutableRuntimeState.value = errState
      errState
    } finally {
      importInFlight.set(false)
      flushDeferredCloseIfNeeded()
    }
  }

  /** Cancels any active preset model download and cleans up its incomplete `.part` staging file. */
  override fun cancelActiveDownload() {
    modelDownloader.cancelActiveDownload()
  }

  /**
   * Runs a serialized text-only completion against the active LiteRT-LM or MediaPipe runtime and
   * strips raw Gemma control tokens (`<pad>`, `<bos>`, `<eos>`, `<start_of_turn>`, `<unusedN>`).
   *
   * @param prompt Grounded household system + user prompt built by [AiMenuAdvisorEngine].
   * @return Sanitized model output string.
   * @throws ModelNotReadyException When no on-device model is currently in [AiRuntimeState.Ready].
   */
  override suspend fun generateText(prompt: String): String {
    if (prompt.isBlank()) {
      throw ValidationException(
        message = "Cannot execute on-device text generation with a blank prompt.",
        issues = listOf("prompt: blank"),
      )
    }
    try {
      return engineMutex.withLock {
        val currentState = mutableRuntimeState.value
        if (currentState !is AiRuntimeState.Ready) {
          throw ModelNotReadyException(runtimeStatus = describeStateName(currentState))
        }
        withContext(inferenceDispatcher) {
          val activeEngine = liteRtEngine
          val activeSession = liteRtSession
          val activeMediaPipe = mediaPipeLlm
          val rawText = when {
            activeEngine != null && supportsConversationApi -> {
              // Release any prior Conversation handle and open a fresh per-turn Conversation so
              // repeated 3,000-char system prompts never bloat or overflow the 2B model KV-cache.
              runCatching { liteRtConversation?.close() }
              liteRtConversation = null
              var textConversation: Conversation? = null
              try {
                textConversation = activeEngine.createConversation()
                textConversation.sendMessage(prompt).toString()
              } finally {
                runCatching { textConversation?.close() }
              }
            }
            activeSession != null -> activeSession.generateContent(listOf(InputData.Text(prompt)))
            activeMediaPipe != null -> activeMediaPipe.generateResponse(prompt).orEmpty()
            else -> throw ModelNotReadyException(runtimeStatus = "DETACHED_NATIVE_HANDLE")
          }
          AiMenuAdvisorEngine.stripGemmaControlTokens(rawText)
        }
      }
    } catch (cancel: CancellationException) {
      throw cancel
    } finally {
      flushDeferredCloseIfNeeded()
    }
  }

  /**
   * Executes an authentic multimodal vision + text turn against [preprocessedImagePath] inside
   * [engineMutex] on a fresh per-scan LiteRT-LM `Conversation` (closed deterministically in
   * `finally` to protect 8 GB Galaxy S22 RAM headroom).
   *
   * **Hard Non-Silent Vision Gate**:
   * - Throws [ModelNotReadyException] if [runtimeState] is not [AiRuntimeState.Ready].
   * - Immediately throws [VisionNotSupportedException] whenever `!capabilities.supportsVision`,
   *   `liteRtEngine == null`, `liteRtSession != null`, or `mediaPipeLlm != null`—**never**
   *   silently dropping the image or falling back to text-only completion.
   *
   * @param preprocessedImagePath Absolute path to the EXIF-normalized, `<= 1280px` JPEG file.
   * @param prompt Grounded visual inspection prompt from `AiMenuAdvisorEngine.buildVisualIntakePrompt`.
   * @return Sanitized multimodal response string with Gemma control tokens stripped.
   */
  override suspend fun analyzeImageWithPrompt(
    preprocessedImagePath: String,
    prompt: String,
  ): String {
    try {
      return engineMutex.withLock {
        val currentState = mutableRuntimeState.value
        if (currentState !is AiRuntimeState.Ready) {
          throw ModelNotReadyException(runtimeStatus = describeStateName(currentState))
        }

        val capabilities = currentState.capabilities
        val activeEngine = liteRtEngine
        // Enforce strict non-silent vision gate before inspecting files or touching text APIs.
        if (
          !capabilities.supportsVision ||
          activeEngine == null ||
          !supportsConversationApi ||
          liteRtSession != null ||
          mediaPipeLlm != null
        ) {
          throw VisionNotSupportedException(
            activeBackendLabel = capabilities.backendLabel,
            modelName = capabilities.modelName,
          )
        }

        if (preprocessedImagePath.isBlank()) {
          throw ValidationException(
            message = "Preprocessed image path must not be blank for multimodal scan.",
            issues = listOf("preprocessedImagePath: blank"),
          )
        }
        if (prompt.isBlank()) {
          throw ValidationException(
            message = "Visual inspection prompt must not be blank.",
            issues = listOf("prompt: blank"),
          )
        }

        val imageFile = File(preprocessedImagePath)
        if (!imageFile.isFile || imageFile.length() <= 0L) {
          throw ValidationException(
            message = "Preprocessed kitchen photo is missing or empty at '${imageFile.name}'.",
            issues = listOf("preprocessedImagePath: missing or 0 bytes"),
          )
        }

        withContext(inferenceDispatcher) {
          // Release any cached text Conversation first so two native Conversations never contend on S22 GPU RAM.
          runCatching { liteRtConversation?.close() }
          liteRtConversation = null

          // Allocate a dedicated Conversation for this photo and close it in finally so multi-scan
          // grocery sessions never accumulate vision embeddings in KV-cache RAM.
          var visionConversation: Conversation? = null
          try {
            visionConversation = activeEngine.createConversation()
            val contents = Contents.of(
              Content.ImageFile(imageFile.absolutePath),
              Content.Text(prompt),
            )
            val rawReply = visionConversation.sendMessage(contents).toString()
            AiMenuAdvisorEngine.stripGemmaControlTokens(rawReply)
          } finally {
            runCatching { visionConversation?.close() }
          }
        }
      }
    } catch (cancel: CancellationException) {
      throw cancel
    } finally {
      flushDeferredCloseIfNeeded()
    }
  }

  /**
   * Resets any active LiteRT-LM `Conversation` KV-cache while keeping model weights resident in RAM.
   */
  override suspend fun resetConversation() {
    try {
      engineMutex.withLock {
        withContext(inferenceDispatcher) {
          runCatching { liteRtConversation?.close() }
          liteRtConversation = null
        }
      }
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (_: Throwable) {
      // Keep UI responsive even if native conversation teardown reports a benign error.
    } finally {
      flushDeferredCloseIfNeeded()
    }
  }

  /**
   * Deterministically closes all active native `Session`, `Engine`, and `LlmInference` handles.
   *
   * Acquires [engineMutex] immediately via `tryLock()` when idle (avoiding `runBlocking`), or marks
   * [closeRequested] so an in-flight JNI call releases native handles safely as soon as its mutex
   * critical section exits.
   */
  override fun close() {
    // Non-blocking teardown safe for ViewModel.onCleared(): cancel active downloads & release JNI.
    modelDownloader.cancelActiveDownload()
    closeRequested = true
    if (engineMutex.tryLock()) {
      try {
        closeActiveHandlesLocked()
      } finally {
        engineMutex.unlock()
      }
    }
  }

  /**
   * Coroutine-suspending teardown variant awaiting [engineMutex] before closing native handles.
   */
  suspend fun closeAsync() {
    // Suspending teardown waits for any active JNI call to complete before freeing native pointers.
    engineMutex.withLock {
      closeRequested = false
      closeActiveHandlesLocked()
    }
  }

  // Must run inside engineMutex.withLock: closes prior handles, initializes weights, updates state.
  private suspend fun initializeModelLocked(modelInfo: DiscoveredModelInfo): AiRuntimeState =
    withContext(ioDispatcher) {
      closeActiveHandlesLocked()
      val modelFile = File(modelInfo.absolutePath)
      if (!modelFile.isFile || modelFile.length() <= 0L) {
        val missing = AiRuntimeState.Error("Model file '${modelInfo.fileName}' is no longer readable on disk.")
        mutableRuntimeState.value = missing
        return@withContext missing
      }

      mutableRuntimeState.value = AiRuntimeState.Loading(modelName = modelFile.name)
      try {
        val capabilities = when (modelInfo.format) {
          ModelFileFormat.LITERT_LM -> initializeLiteRtPackageLocked(modelFile)
          ModelFileFormat.MEDIAPIPE_TASK,
          ModelFileFormat.MEDIAPIPE_BIN,
          -> initializeMediaPipePackageLocked(modelFile)
        }
        val readyState = AiRuntimeState.Ready(capabilities)
        mutableRuntimeState.value = readyState
        readyState
      } catch (cancel: CancellationException) {
        closeActiveHandlesLocked()
        throw cancel
      } catch (initError: Throwable) {
        closeActiveHandlesLocked()
        val cleanMsg = sanitizeNativeErrorMessage(initError)
        val errorState = AiRuntimeState.Error(
          "Could not initialize '${modelFile.name}': $cleanMsg",
        )
        mutableRuntimeState.value = errorState
        errorState
      }
    }

  // Fallback ladder: Preferred GPU/CPU + Vision -> Preferred GPU/CPU Text -> CPU Text.
  private fun initializeLiteRtPackageLocked(modelFile: File): RuntimeCapabilities {
    val hardware = ModelFileDiscovery.detectHardwareEnvironment()
    val preferredBackend: Backend = if (hardware.isEmulator) Backend.CPU() else Backend.GPU()
    val delegateTag = if (hardware.isEmulator) "CPU" else "GPU"
    val cacheDirPath = modelFile.parentFile?.takeIf { it.canWrite() }?.absolutePath
      ?: appContext.cacheDir.absolutePath
    val fallbackCachePath = appContext.cacheDir.absolutePath

    var visionInitEnabled = false
    var backendLabel = "LiteRT-LM $delegateTag"

    val initializedEngine = try {
      val multimodalCfg = EngineConfig(
        modelPath = modelFile.absolutePath,
        backend = preferredBackend,
        visionBackend = preferredBackend,
        audioBackend = Backend.CPU(),
        cacheDir = cacheDirPath,
      )
      createAndInitEngine(multimodalCfg).also {
        visionInitEnabled = true
        backendLabel = "LiteRT-LM $delegateTag + Vision"
      }
    } catch (_: Throwable) {
      // Vision encoder missing or unsupported in this .litertlm bundle: fall back to text-only.
      try {
        val textPreferredCfg = EngineConfig(
          modelPath = modelFile.absolutePath,
          backend = preferredBackend,
          cacheDir = cacheDirPath,
        )
        createAndInitEngine(textPreferredCfg).also {
          visionInitEnabled = false
          backendLabel = "LiteRT-LM $delegateTag (Text-Only)"
        }
      } catch (_: Throwable) {
        val cpuFallbackCfg = EngineConfig(
          modelPath = modelFile.absolutePath,
          backend = Backend.CPU(),
          cacheDir = fallbackCachePath,
        )
        createAndInitEngine(cpuFallbackCfg).also {
          visionInitEnabled = false
          backendLabel = "LiteRT-LM CPU Fallback (Text-Only)"
        }
      }
    }

    liteRtEngine = initializedEngine
    // Probe Conversation support once at load time (closing immediately so KV-cache starts empty);
    // fall back to Session mode if Conversation API is unsupported by this package.
    try {
      val probeConversation = initializedEngine.createConversation()
      runCatching { probeConversation.close() }
      liteRtConversation = null
      supportsConversationApi = true
    } catch (_: Throwable) {
      liteRtConversation = null
      supportsConversationApi = false
      visionInitEnabled = false
      liteRtSession = initializedEngine.createSession()
      backendLabel = "LiteRT-LM $delegateTag Session (Text-Only)"
    }

    return RuntimeCapabilities(
      supportsText = true,
      supportsVision = visionInitEnabled && supportsConversationApi,
      backendLabel = backendLabel,
      modelName = modelFile.name,
    )
  }

  // Initialize MediaPipe Tasks GenAI (.task / .bin) at maxTokens = 1024 so JSON never truncates.
  private fun initializeMediaPipePackageLocked(modelFile: File): RuntimeCapabilities {
    val options = LlmInference.LlmInferenceOptions.builder()
      .setModelPath(modelFile.absolutePath)
      .setMaxTokens(MEDIA_PIPE_MAX_TOKENS)
      .build()
    mediaPipeLlm = LlmInference.createFromOptions(appContext, options)
    supportsConversationApi = false
    return RuntimeCapabilities(
      supportsText = true,
      supportsVision = false,
      backendLabel = "MediaPipe GenAI (Text-Only)",
      modelName = modelFile.name,
    )
  }

  // Ensure failed Engine(cfg).initialize() attempts close any partially allocated native buffers.
  private fun createAndInitEngine(config: EngineConfig): Engine {
    val candidate = Engine(config)
    try {
      candidate.initialize()
      return candidate
    } catch (err: Throwable) {
      runCatching { candidate.close() }
      throw err
    }
  }

  // Deterministically close active Conversation BEFORE Session/Engine so native C++ order is respected.
  private fun closeActiveHandlesLocked() {
    runCatching { liteRtConversation?.close() }
    runCatching { liteRtSession?.close() }
    runCatching { liteRtEngine?.close() }
    runCatching { mediaPipeLlm?.close() }
    liteRtConversation = null
    liteRtSession = null
    liteRtEngine = null
    mediaPipeLlm = null
    supportsConversationApi = false
    closeRequested = false
    mutableRuntimeState.value = AiRuntimeState.Uninitialized
  }

  // Execute deferred native teardown if close() was invoked while another coroutine held engineMutex.
  private fun flushDeferredCloseIfNeeded() {
    if (!closeRequested) return
    if (engineMutex.tryLock()) {
      try {
        if (closeRequested) closeActiveHandlesLocked()
      } finally {
        engineMutex.unlock()
      }
    }
  }

  // Map sealed AiRuntimeState variants to stable uppercase diagnostic codes for ModelNotReadyException.
  private fun describeStateName(state: AiRuntimeState): String =
    when (state) {
      AiRuntimeState.Uninitialized -> "UNINITIALIZED"
      AiRuntimeState.Discovering -> "DISCOVERING"
      is AiRuntimeState.Importing -> "IMPORTING"
      is AiRuntimeState.Loading -> "LOADING"
      is AiRuntimeState.Ready -> "READY"
      is AiRuntimeState.Error -> "ERROR"
    }

  // Strip C++ source file/line noise from JNI exception strings before surfacing in UI banners.
  private fun sanitizeNativeErrorMessage(error: Throwable): String =
    (error.message ?: "native initialization error")
      .replace(Regex("\\([^)]*\\.(cc|cpp|h):\\d+\\)"), "")
      .lineSequence()
      .map { it.trim() }
      .firstOrNull { it.isNotEmpty() }
      ?: "native initialization error"

  companion object {
    /** Context token budget (`1024`) ensuring MediaPipe `.task`/`.bin` JSON replies do not clip. */
    const val MEDIA_PIPE_MAX_TOKENS: Int = 1024
  }
}
