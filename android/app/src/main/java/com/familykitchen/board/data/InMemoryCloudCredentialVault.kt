package com.familykitchen.board.data

import com.familykitchen.board.domain.AiExecutionMode
import com.familykitchen.board.domain.CloudLlmConfig
import com.familykitchen.board.domain.CloudLlmProvider
import com.familykitchen.board.domain.isDeprecatedCloudModelId
import com.familykitchen.board.domain.isValidCloudModelId
import com.familykitchen.board.domain.maskApiKeyPreview
import com.familykitchen.board.domain.migrateDeprecatedCloudModelId
import com.familykitchen.board.domain.requireDomainValid
import com.familykitchen.board.ports.CloudCredentialVaultPort
import java.util.EnumMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Pure-Kotlin thread-safe in-memory CloudCredentialVaultPort implementation for hermetic JVM tests
// and default ViewModel wiring with zero Android framework or disk dependencies.

/**
 * Thread-safe, pure-Kotlin in-memory implementation of [CloudCredentialVaultPort].
 *
 * Serves as the default vault in `KitchenBoardViewModel` so existing and new JVM unit tests run
 * 100% hermetically without Android Keystore hardware or `SharedPreferences`.
 *
 * @param initialConfig Optional initial non-secret [CloudLlmConfig] state.
 */
class InMemoryCloudCredentialVault(
  initialConfig: CloudLlmConfig = CloudLlmConfig(),
) : CloudCredentialVaultPort {

  // Coroutine Mutex serializes concurrent state transitions and key reads/writes deterministically.
  private val vaultMutex = Mutex()

  // Per-provider API secrets held strictly in private memory and never serialized to snapshots.
  private val secretsByProvider = EnumMap<CloudLlmProvider, String>(CloudLlmProvider::class.java)

  // Per-provider model identifier overrides initialized from each provider's curated default.
  private val modelIdsByProvider = EnumMap<CloudLlmProvider, String>(CloudLlmProvider::class.java).apply {
    for (entry in CloudLlmProvider.entries) {
      put(entry, entry.defaultModelId)
    }
    if (isValidCloudModelId(initialConfig.modelId)) {
      // Seamlessly upgrade any persisted pre-late-2026 deprecated model ID on load.
      put(
        initialConfig.provider,
        migrateDeprecatedCloudModelId(initialConfig.provider, initialConfig.modelId),
      )
    }
  }

  // Per-provider live-synced model lists merged with curated presets for 1-tap UI chip selection.
  private val availableModelsByProvider =
    EnumMap<CloudLlmProvider, List<String>>(CloudLlmProvider::class.java).apply {
      for (entry in CloudLlmProvider.entries) {
        put(entry, entry.presetModelIds)
      }
      val initialMerged = mergePresetAndLiveModels(initialConfig.provider, initialConfig.availableModelIds)
      put(initialConfig.provider, initialMerged)
    }

  private var executionMode: AiExecutionMode = initialConfig.executionMode
  private var activeProvider: CloudLlmProvider = initialConfig.provider

  private val mutableConfigFlow = MutableStateFlow(buildSnapshotLocked())

  /** Hot reactive stream of non-secret [CloudLlmConfig] state. */
  override val configFlow: StateFlow<CloudLlmConfig> = mutableConfigFlow.asStateFlow()

  /** Switches between [AiExecutionMode.ON_DEVICE] and [AiExecutionMode.CLOUD_API]. */
  override suspend fun setExecutionMode(mode: AiExecutionMode): CloudLlmConfig =
    vaultMutex.withLock {
      executionMode = mode
      publishSnapshotLocked()
    }

  /** Selects [provider] as the active cloud provider and emits its model and key presence metadata. */
  override suspend fun selectProvider(provider: CloudLlmProvider): CloudLlmConfig =
    vaultMutex.withLock {
      activeProvider = provider
      publishSnapshotLocked()
    }

  /** Validates and records [modelId] for [provider], auto-migrating deprecated IDs and blocking traversal. */
  override suspend fun setModelId(
    modelId: String,
    provider: CloudLlmProvider,
  ): CloudLlmConfig =
    vaultMutex.withLock {
      val cleanModelId = modelId.trim()
      requireDomainValid(isValidCloudModelId(cleanModelId), "CloudLlmConfig") {
        "Invalid cloud model ID '$cleanModelId'"
      }
      modelIdsByProvider[provider] = migrateDeprecatedCloudModelId(provider, cleanModelId)
      publishSnapshotLocked()
    }

  /** Validates and stores [rawApiKey] for [provider] in memory while emitting only a masked preview. */
  override suspend fun saveApiKey(
    provider: CloudLlmProvider,
    rawApiKey: String,
  ): CloudLlmConfig =
    vaultMutex.withLock {
      val cleanKey = validateApiKeyFormat(rawApiKey)
      secretsByProvider[provider] = cleanKey
      activeProvider = provider
      publishSnapshotLocked()
    }

  /** Returns the stored API key for [provider], or `null` when unconfigured. */
  override suspend fun readApiKey(provider: CloudLlmProvider): String? =
    vaultMutex.withLock {
      secretsByProvider[provider]
    }

  /** Removes any stored API key for [provider] and updates [configFlow]. */
  override suspend fun clearApiKey(provider: CloudLlmProvider): CloudLlmConfig =
    vaultMutex.withLock {
      secretsByProvider.remove(provider)
      publishSnapshotLocked()
    }

  /** Merges live-synced [modelIds] behind [provider]'s curated presets and publishes [configFlow]. */
  override suspend fun setAvailableModels(
    provider: CloudLlmProvider,
    modelIds: List<String>,
  ): CloudLlmConfig =
    vaultMutex.withLock {
      availableModelsByProvider[provider] = mergePresetAndLiveModels(provider, modelIds)
      publishSnapshotLocked()
    }

  private fun publishSnapshotLocked(): CloudLlmConfig {
    val next = buildSnapshotLocked()
    mutableConfigFlow.value = next
    return next
  }

  private fun buildSnapshotLocked(): CloudLlmConfig {
    val activeSecret = secretsByProvider[activeProvider]
    val rawModelId = modelIdsByProvider[activeProvider] ?: activeProvider.defaultModelId
    val resolvedModelId = migrateDeprecatedCloudModelId(activeProvider, rawModelId)
    val resolvedAvailable = availableModelsByProvider[activeProvider] ?: activeProvider.presetModelIds
    return CloudLlmConfig(
      executionMode = executionMode,
      provider = activeProvider,
      modelId = resolvedModelId,
      hasStoredApiKey = !activeSecret.isNullOrBlank(),
      maskedKeyPreview = activeSecret?.let(::maskApiKeyPreview),
      configuredProviders = secretsByProvider.keys.toSet(),
      availableModelIds = resolvedAvailable,
    )
  }

  companion object {
    /** Minimum trimmed character length required for a valid Cloud LLM API key. */
    const val MIN_API_KEY_LENGTH: Int = 8

    /** Maximum number of merged preset + live-synced model chips retained per provider. */
    const val MAX_AVAILABLE_MODELS_PER_PROVIDER: Int = 16

    // Merge curated active presets first, followed by validated non-deprecated live-synced IDs.
    internal fun mergePresetAndLiveModels(
      provider: CloudLlmProvider,
      liveModelIds: List<String>,
    ): List<String> {
      val merged = LinkedHashSet<String>(MAX_AVAILABLE_MODELS_PER_PROVIDER)
      for (preset in provider.presetModelIds) {
        if (merged.size < MAX_AVAILABLE_MODELS_PER_PROVIDER) merged.add(preset)
      }
      for (candidate in liveModelIds) {
        val clean = candidate.trim()
        if (
          merged.size < MAX_AVAILABLE_MODELS_PER_PROVIDER &&
          isValidCloudModelId(clean) &&
          !isDeprecatedCloudModelId(clean)
        ) {
          merged.add(clean)
        }
      }
      return merged.toList()
    }

    // Reject blank, short, or CRLF/whitespace-polluted strings to prevent HTTP header injection.
    internal fun validateApiKeyFormat(rawApiKey: String): String {
      val cleanKey = rawApiKey.trim()
      requireDomainValid(cleanKey.length >= MIN_API_KEY_LENGTH, "CloudApiKey") {
        "API key must contain at least $MIN_API_KEY_LENGTH characters"
      }
      requireDomainValid(cleanKey.none { it == '\r' || it == '\n' || it.isWhitespace() }, "CloudApiKey") {
        "API key must not contain whitespace or control characters"
      }
      return cleanKey
    }
  }
}
