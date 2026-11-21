package com.familykitchen.board.ai

import com.familykitchen.board.domain.AiExecutionMode
import com.familykitchen.board.domain.AiMenuAdvisorEngine
import com.familykitchen.board.domain.CloudLlmConfig
import com.familykitchen.board.domain.ModelNotReadyException
import com.familykitchen.board.domain.VisionNotSupportedException
import com.familykitchen.board.ports.AiEdgePort
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ports.CloudCredentialVaultPort
import com.familykitchen.board.ports.RuntimeCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// Hexagonal AiEdgePort router switching seamlessly between offline LiteRT-LM / MediaPipe inference
// and Keystore-backed HTTPS Cloud LLM providers without coupling AiChefCoordinator to network code.

/**
 * Composite [AiEdgePort] routing text and multimodal vision calls between local [onDevicePort]
 * (`AiExecutionMode.ON_DEVICE` offline default) and [cloudClient] (`AiExecutionMode.CLOUD_API`).
 *
 * Synchronizes [runtimeState] deterministically whenever [vaultPort] or [onDevicePort] emits so
 * switching between `ON_DEVICE` and `CLOUD_API` updates UI badges and vision-gate capabilities
 * synchronously with zero stale state.
 *
 * @param onDevicePort Local LiteRT-LM / MediaPipe adapter handling offline GPU/CPU execution.
 * @param vaultPort Hardware Keystore or in-memory vault managing non-secret config and API keys.
 * @param cloudClient HTTPS multi-provider Cloud LLM client used strictly in `CLOUD_API` mode.
 * @param catalogFetcher HTTPS `GET /models` catalog adapter for 1-tap live model synchronization.
 */
class HybridAiInferenceRouter(
  private val onDevicePort: AiEdgePort,
  private val vaultPort: CloudCredentialVaultPort,
  private val cloudClient: CloudLlmClient = CloudLlmClient(),
  private val catalogFetcher: CloudModelCatalogFetcher = CloudModelCatalogFetcher(),
) : AiEdgePort {

  // Unconfined supervisor scope keeps StateFlow collectors synchronous on upstream emissions.
  private val routerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

  private val backingRuntimeState = MutableStateFlow(
    computeEffectiveRuntimeState(
      config = vaultPort.configFlow.value,
      onDeviceState = onDevicePort.runtimeState.value,
    ),
  )

  /**
   * Synchronized [AiRuntimeState] stream reflecting either [onDevicePort] (`ON_DEVICE`) or the
   * configured cloud provider readiness (`CLOUD_API`).
   */
  override val runtimeState: StateFlow<AiRuntimeState> = object : StateFlow<AiRuntimeState> {
    // Expose a single-item replay cache derived from the freshly synchronized state value.
    override val replayCache: List<AiRuntimeState>
      get() = listOf(value)

    // Recompute effective state on direct .value reads so callers never observe stale snapshots.
    override val value: AiRuntimeState
      get() = refreshRuntimeState()

    override suspend fun collect(collector: FlowCollector<AiRuntimeState>): Nothing {
      refreshRuntimeState()
      backingRuntimeState.collect(collector)
    }
  }

  init {
    // Observe vault configuration changes synchronously so mode/key switches reflect immediately.
    routerScope.launch(start = CoroutineStart.UNDISPATCHED) {
      vaultPort.configFlow.collect {
        refreshRuntimeState()
      }
    }
    // Mirror on-device discovery, SAF import progress, and Ready/Error transitions when ON_DEVICE.
    routerScope.launch(start = CoroutineStart.UNDISPATCHED) {
      onDevicePort.runtimeState.collect {
        refreshRuntimeState()
      }
    }
  }

  /**
   * Recomputes and publishes the effective [AiRuntimeState] from [vaultPort] and [onDevicePort]
   * synchronously before returning.
   *
   * @return Latest synchronized [AiRuntimeState].
   */
  fun refreshRuntimeState(): AiRuntimeState {
    val effective = computeEffectiveRuntimeState(
      config = vaultPort.configFlow.value,
      onDeviceState = onDevicePort.runtimeState.value,
    )
    backingRuntimeState.value = effective
    return effective
  }

  /**
   * Runs local model discovery when in [AiExecutionMode.ON_DEVICE] mode and returns the active
   * synchronized [AiRuntimeState].
   */
  override suspend fun autoDiscoverAndInitialize(): AiRuntimeState {
    if (vaultPort.configFlow.value.executionMode == AiExecutionMode.ON_DEVICE) {
      onDevicePort.autoDiscoverAndInitialize()
    }
    return refreshRuntimeState()
  }

  /** Delegates SAF `.litertlm` / `.task` model import to [onDevicePort] and refreshes state. */
  override suspend fun importAndLoadModel(
    sourceUriString: String,
    displayName: String?,
  ): AiRuntimeState {
    val importedState = onDevicePort.importAndLoadModel(sourceUriString, displayName)
    val synced = refreshRuntimeState()
    return when (vaultPort.configFlow.value.executionMode) {
      AiExecutionMode.ON_DEVICE -> importedState
      AiExecutionMode.CLOUD_API -> synced
    }
  }

  /**
   * Routes text generation strictly to [onDevicePort] in `ON_DEVICE` mode or to [cloudClient] in
   * `CLOUD_API` mode, stripping Markdown code fences and control tokens before returning.
   */
  override suspend fun generateText(prompt: String): String {
    val config = vaultPort.configFlow.value
    return when (config.executionMode) {
      // Offline ON_DEVICE mode delegates strictly to LiteRtMultimodalEngine with zero network I/O.
      AiExecutionMode.ON_DEVICE -> onDevicePort.generateText(prompt)
      AiExecutionMode.CLOUD_API -> {
        // Decrypt API credential on demand and scrub any Markdown fences from cloud output.
        val apiKey = requireConfiguredCloudKey(config)
        val rawReply = cloudClient.generateText(
          provider = config.provider,
          modelId = config.modelId,
          apiKey = apiKey,
          prompt = prompt,
        )
        AiMenuAdvisorEngine.stripGemmaControlTokens(rawReply)
      }
    }
  }

  /**
   * Routes multimodal image + prompt inspection strictly to [onDevicePort] in `ON_DEVICE` mode or
   * to [cloudClient] in `CLOUD_API` mode, enforcing non-silent vision and missing-key gates.
   */
  override suspend fun analyzeImageWithPrompt(
    preprocessedImagePath: String,
    prompt: String,
  ): String {
    val config = vaultPort.configFlow.value
    return when (config.executionMode) {
      // Preserve non-silent VisionNotSupportedException behavior from onDevicePort when offline.
      AiExecutionMode.ON_DEVICE ->
        onDevicePort.analyzeImageWithPrompt(preprocessedImagePath, prompt)
      AiExecutionMode.CLOUD_API -> {
        // Guard against any non-vision cloud provider rather than silently dropping the photo.
        if (!config.provider.supportsVision) {
          throw VisionNotSupportedException(
            activeBackendLabel = "Cloud · ${config.provider.shortLabel}",
            modelName = config.modelId,
          )
        }
        val apiKey = requireConfiguredCloudKey(config)
        val rawVisionOutput = cloudClient.analyzeImageWithPrompt(
          provider = config.provider,
          modelId = config.modelId,
          apiKey = apiKey,
          preprocessedImagePath = preprocessedImagePath,
          prompt = prompt,
        )
        AiMenuAdvisorEngine.stripGemmaControlTokens(rawVisionOutput)
      }
    }
  }

  /** Resets any active on-device conversation state. */
  override suspend fun resetConversation() {
    onDevicePort.resetConversation()
  }

  /**
   * Verifies connectivity and credentials for the currently selected [CloudLlmConfig.provider] and
   * [CloudLlmConfig.modelId] via [CloudLlmClient.verifyConnection].
   *
   * @return Sanitized health-check response text.
   */
  suspend fun verifyActiveCloudConnection(): String {
    val config = vaultPort.configFlow.value
    val apiKey = requireConfiguredCloudKey(config)
    val rawReply = cloudClient.verifyConnection(
      provider = config.provider,
      modelId = config.modelId,
      apiKey = apiKey,
    )
    refreshRuntimeState()
    return AiMenuAdvisorEngine.stripGemmaControlTokens(rawReply)
  }

  /**
   * Fetches the live non-deprecated model list from the active [CloudLlmConfig.provider]'s
   * `/models` endpoint, merges it with `provider.presetModelIds` in [vaultPort], and refreshes
   * [runtimeState].
   *
   * @return Merged list of active model IDs stored on [CloudLlmConfig.availableModelIds].
   */
  suspend fun refreshCloudModels(): List<String> {
    val config = vaultPort.configFlow.value
    val apiKey = requireConfiguredCloudKey(config)
    val liveModels = catalogFetcher.fetchAvailableModels(
      provider = config.provider,
      apiKey = apiKey,
    )
    val updatedConfig = vaultPort.setAvailableModels(
      provider = config.provider,
      modelIds = liveModels,
    )
    refreshRuntimeState()
    return updatedConfig.availableModelIds
  }

  /** Cancels synchronization coroutines and deterministically closes [onDevicePort] JNI handles. */
  override fun close() {
    routerScope.cancel()
    onDevicePort.close()
  }

  // Ensures missing cloud credentials raise ModelNotReadyException(CLOUD_API_KEY_MISSING).
  private suspend fun requireConfiguredCloudKey(config: CloudLlmConfig): String {
    val decryptedKey = vaultPort.readApiKey(config.provider)?.trim().orEmpty()
    if (decryptedKey.isEmpty()) {
      throw ModelNotReadyException(
        runtimeStatus = STATUS_CLOUD_API_KEY_MISSING,
        message = "Add your ${config.provider.displayName} key in Model Setup, or switch to On-Device mode.",
      )
    }
    return decryptedKey
  }

  // Maps CLOUD_API + stored key to Ready("Cloud · <ShortLabel>", modelId) or actionable Error.
  private fun computeEffectiveRuntimeState(
    config: CloudLlmConfig,
    onDeviceState: AiRuntimeState,
  ): AiRuntimeState =
    when (config.executionMode) {
      AiExecutionMode.ON_DEVICE -> onDeviceState
      AiExecutionMode.CLOUD_API -> when {
        config.hasStoredApiKey -> AiRuntimeState.Ready(
          capabilities = RuntimeCapabilities(
            supportsText = true,
            supportsVision = config.provider.supportsVision,
            backendLabel = "Cloud · ${config.provider.shortLabel}",
            modelName = config.modelId,
          ),
        )
        else -> AiRuntimeState.Error(
          message = "Add your ${config.provider.displayName} key in Model Setup, or switch to On-Device mode.",
        )
      }
    }

  companion object {
    /** Stable [ModelNotReadyException.runtimeStatus] code emitted when no cloud key is stored. */
    const val STATUS_CLOUD_API_KEY_MISSING: String = "CLOUD_API_KEY_MISSING"
  }
}
