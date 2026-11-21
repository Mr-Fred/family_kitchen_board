package com.familykitchen.board.ports

import com.familykitchen.board.domain.AiExecutionMode
import com.familykitchen.board.domain.CloudLlmConfig
import com.familykitchen.board.domain.CloudLlmProvider
import kotlinx.coroutines.flow.StateFlow

// Hexagonal boundary port isolating hardware-backed Android Keystore encryption from pure domain
// and ViewModel layers so raw API keys never enter KitchenSnapshot or KitchenUiState.

/**
 * Hexagonal credential-vault port managing dual-mode [AiExecutionMode] selection, per-provider
 * model identifiers, and at-rest encrypted API keys for [CloudLlmProvider] endpoints.
 *
 * Implementations **must** guarantee that [configFlow] emits only non-secret metadata
 * (`hasStoredApiKey`, `maskedKeyPreview`, `configuredProviders`) while raw API credentials are
 * decrypted on demand exclusively via [readApiKey].
 */
interface CloudCredentialVaultPort {
  /** Hot reactive stream of non-secret [CloudLlmConfig] state observed by `KitchenBoardViewModel`. */
  val configFlow: StateFlow<CloudLlmConfig>

  /**
   * Switches inference routing between [AiExecutionMode.ON_DEVICE] and [AiExecutionMode.CLOUD_API].
   *
   * @param mode Target execution mode.
   * @return Updated [CloudLlmConfig] snapshot emitted to [configFlow].
   */
  suspend fun setExecutionMode(mode: AiExecutionMode): CloudLlmConfig

  /**
   * Selects the active [CloudLlmProvider] and loads its persisted model ID and key status.
   *
   * @param provider Target cloud provider.
   * @return Updated [CloudLlmConfig] snapshot emitted to [configFlow].
   */
  suspend fun selectProvider(provider: CloudLlmProvider): CloudLlmConfig

  /**
   * Validates and persists [modelId] for [provider] (defaulting to the currently active provider).
   *
   * Implementations must reject malformed or path-traversal model IDs (`isValidCloudModelId == false`)
   * with `ValidationException`.
   *
   * @param modelId Candidate model identifier (`2..80` allowed characters, zero `".."` segments).
   * @param provider Provider associated with [modelId].
   * @return Updated [CloudLlmConfig] snapshot emitted to [configFlow].
   */
  suspend fun setModelId(
    modelId: String,
    provider: CloudLlmProvider = configFlow.value.provider,
  ): CloudLlmConfig

  /**
   * Encrypts and stores [rawApiKey] for [provider], selects [provider] as active, and updates
   * [configFlow] with a masked suffix preview (`"•••• 1234"`).
   *
   * @param provider Target cloud provider owning the API credential.
   * @param rawApiKey User-supplied API secret (trimmed and validated before encryption).
   * @return Updated [CloudLlmConfig] snapshot emitted to [configFlow].
   */
  suspend fun saveApiKey(provider: CloudLlmProvider, rawApiKey: String): CloudLlmConfig

  /**
   * Decrypts and returns the raw API key stored for [provider], or `null` when none is configured.
   *
   * @param provider Cloud provider whose secret should be decrypted.
   * @return Decrypted API key string, or `null` if absent.
   */
  suspend fun readApiKey(provider: CloudLlmProvider = configFlow.value.provider): String?

  /**
   * Permanently deletes any stored API key for [provider] and refreshes [configFlow].
   *
   * @param provider Cloud provider whose stored credential should be removed.
   * @return Updated [CloudLlmConfig] snapshot emitted to [configFlow].
   */
  suspend fun clearApiKey(provider: CloudLlmProvider): CloudLlmConfig

  /**
   * Records live-synced model identifiers for [provider] (merged with `provider.presetModelIds`)
   * so [CloudLlmConfig.availableModelIds] flows reactively to UI chips.
   *
   * @param provider Cloud provider whose available model catalog was fetched.
   * @param modelIds Validated non-deprecated model identifiers returned by `/models`.
   * @return Updated [CloudLlmConfig] snapshot emitted to [configFlow].
   */
  suspend fun setAvailableModels(
    provider: CloudLlmProvider,
    modelIds: List<String>,
  ): CloudLlmConfig = configFlow.value
}
