package com.familykitchen.board.ui

import com.familykitchen.board.ai.HybridAiInferenceRouter
import com.familykitchen.board.domain.AiChefAction
import com.familykitchen.board.domain.AiExecutionMode
import com.familykitchen.board.domain.CloudLlmProvider
import com.familykitchen.board.domain.redactApiSecrets
import com.familykitchen.board.ports.AiEdgePort
import com.familykitchen.board.ports.CloudCredentialVaultPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Cohesive internal Cloud AI configuration & Keystore credential verification delegate.
// Keeps KitchenAiAndCookDelegate modular and comfortably below the 680 logical-line ceiling.

/**
 * Internal presentation helper handling the 6 dual-mode Cloud LLM setup actions
 * (`SelectAiExecutionMode`, `SelectCloudProvider`, `UpdateCloudModelId`, `SaveCloudApiKey`,
 * `ClearCloudApiKey`, and `VerifyCloudConnection`) on behalf of [KitchenAiAndCookDelegate].
 *
 * Guarantees that raw API keys are never copied into `KitchenUiState` or `KitchenSnapshot`,
 * synchronizes [HybridAiInferenceRouter.refreshRuntimeState] immediately after each vault mutation,
 * and scrubs all diagnostic messages via [redactApiSecrets].
 */
internal class KitchenCloudConfigDelegate(
  private val scope: CoroutineScope,
  private val mutableUiState: MutableStateFlow<KitchenUiState>,
  private val aiEdgePort: AiEdgePort,
  private val cloudVaultPort: CloudCredentialVaultPort,
) {

  /** Switches active execution routing between `ON_DEVICE` (offline) and `CLOUD_API` (HTTPS). */
  fun updateExecutionMode(mode: AiExecutionMode) {
    // Launch vault update on viewModelScope and synchronize HybridAiInferenceRouter immediately.
    scope.launch {
      try {
        val updated = cloudVaultPort.setExecutionMode(mode)
        syncHybridRuntimeState()
        val statusMsg = when (mode) {
          AiExecutionMode.ON_DEVICE -> "Switched to On-Device (Offline) mode."
          AiExecutionMode.CLOUD_API -> "Switched to Cloud API mode (${updated.provider.shortLabel})."
        }
        mutableUiState.update { it.copy(cloudLlmConfig = updated, toastMessage = statusMsg) }
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (err: Throwable) {
        val safeMsg = redactApiSecrets(err.message ?: "Unable to switch AI execution mode.")
        mutableUiState.update { it.copy(toastMessage = safeMsg) }
      }
    }
  }

  /** Selects the active [CloudLlmProvider] and loads its persisted model ID and masked badge. */
  fun selectProvider(provider: CloudLlmProvider) {
    // Switching provider automatically updates modelId and hasStoredApiKey for that provider.
    scope.launch {
      try {
        val updated = cloudVaultPort.selectProvider(provider)
        syncHybridRuntimeState()
        mutableUiState.update {
          it.copy(
            cloudLlmConfig = updated,
            toastMessage = "Selected ${provider.displayName} (${updated.modelId}).",
          )
        }
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (err: Throwable) {
        val safeMsg = redactApiSecrets(err.message ?: "Unable to select cloud provider.")
        mutableUiState.update { it.copy(toastMessage = safeMsg) }
      }
    }
  }

  /** Validates and saves a preset or custom [rawModelId] for the active cloud provider. */
  fun updateModelId(rawModelId: String) {
    // Rejects path-traversal ("..") or malformed model strings at the vault validation boundary.
    scope.launch {
      try {
        val updated = cloudVaultPort.setModelId(rawModelId)
        syncHybridRuntimeState()
        mutableUiState.update { it.copy(cloudLlmConfig = updated) }
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (err: Throwable) {
        val safeMsg = redactApiSecrets(err.message ?: "Invalid cloud model ID.")
        mutableUiState.update { it.copy(toastMessage = safeMsg) }
      }
    }
  }

  /**
   * Encrypts and stores `action.rawApiKey` in [cloudVaultPort], switches to `CLOUD_API`, and
   * optionally runs a lightweight health probe while `isVerifyingCloudKey` is `true`.
   */
  fun saveAndOptionallyVerifyKey(action: AiChefAction.SaveCloudApiKey) {
    val trimmedKey = action.rawApiKey.trim()
    // Reject blank submissions early before touching Keystore or network transport.
    if (trimmedKey.isEmpty()) {
      mutableUiState.update { it.copy(toastMessage = "Enter a valid API key before saving.") }
      return
    }

    scope.launch {
      // Step 1: Persist provider, optional model override, encrypted key, and CLOUD_API mode.
      val savedConfig = try {
        cloudVaultPort.selectProvider(action.provider)
        val modelOverride = action.modelId?.trim()?.takeIf { it.isNotEmpty() }
        if (modelOverride != null) {
          cloudVaultPort.setModelId(modelOverride, action.provider)
        }
        cloudVaultPort.saveApiKey(action.provider, trimmedKey)
        val nextConfig = cloudVaultPort.setExecutionMode(AiExecutionMode.CLOUD_API)
        syncHybridRuntimeState()
        nextConfig
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (err: Throwable) {
        val safeMsg = redactApiSecrets(err.message ?: "Invalid cloud API key configuration.", trimmedKey)
        mutableUiState.update { it.copy(toastMessage = safeMsg) }
        return@launch
      }

      val maskedBadge = savedConfig.maskedKeyPreview ?: "••••••••"
      // Skip live network verification when caller explicitly sets verifyConnection = false.
      if (!action.verifyConnection) {
        mutableUiState.update {
          it.copy(
            cloudLlmConfig = savedConfig,
            toastMessage = "Saved ${action.provider.displayName} key ($maskedBadge).",
          )
        }
        return@launch
      }

      // Step 2: Run 16-token health check while keeping the saved key even if temporarily offline.
      mutableUiState.update { it.copy(cloudLlmConfig = savedConfig, isVerifyingCloudKey = true) }
      try {
        (aiEdgePort as? HybridAiInferenceRouter)?.verifyActiveCloudConnection()
        mutableUiState.update {
          it.copy(
            toastMessage = "Saved & connected ${action.provider.displayName} (${savedConfig.modelId})!",
          )
        }
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (err: Throwable) {
        val safeDetail = redactApiSecrets(err.message ?: "Connection check failed.", trimmedKey)
        mutableUiState.update {
          it.copy(
            toastMessage = "Saved ${action.provider.shortLabel} key ($maskedBadge); check failed: $safeDetail",
          )
        }
      } finally {
        mutableUiState.update { it.copy(isVerifyingCloudKey = false) }
      }
    }
  }

  /** Deletes the stored API key for [provider] and refreshes runtime readiness immediately. */
  fun clearApiKey(provider: CloudLlmProvider) {
    // Clearing the active provider key transitions CLOUD_API runtimeState cleanly to Error.
    scope.launch {
      try {
        val cleared = cloudVaultPort.clearApiKey(provider)
        syncHybridRuntimeState()
        mutableUiState.update {
          it.copy(
            cloudLlmConfig = cleared,
            toastMessage = "Removed ${provider.displayName} key from vault.",
          )
        }
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (err: Throwable) {
        val safeMsg = redactApiSecrets(err.message ?: "Unable to clear API key.")
        mutableUiState.update { it.copy(toastMessage = safeMsg) }
      }
    }
  }

  /** Verifies connectivity for the active cloud provider without mutating stored credentials. */
  fun verifyActiveConnection() {
    // Guard against duplicate overlapping health checks on rapid button taps.
    if (mutableUiState.value.isVerifyingCloudKey) return
    scope.launch {
      mutableUiState.update { it.copy(isVerifyingCloudKey = true) }
      try {
        val router = aiEdgePort as? HybridAiInferenceRouter
        when {
          router != null -> {
            router.verifyActiveCloudConnection()
            val cfg = cloudVaultPort.configFlow.value
            mutableUiState.update {
              it.copy(toastMessage = "Verified ${cfg.provider.displayName} (${cfg.modelId}) connection — ready!")
            }
          }
          else -> {
            val cfg = cloudVaultPort.configFlow.value
            val hasKey = !cloudVaultPort.readApiKey(cfg.provider).isNullOrBlank()
            val msg = when {
              hasKey -> "Stored key present for ${cfg.provider.displayName} (${cfg.modelId})."
              else -> "Add your ${cfg.provider.displayName} key in Model Setup before verifying."
            }
            mutableUiState.update { it.copy(toastMessage = msg) }
          }
        }
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (err: Throwable) {
        val safeMsg = redactApiSecrets(err.message ?: "Cloud connection verification failed.")
        mutableUiState.update { it.copy(toastMessage = safeMsg) }
      } finally {
        mutableUiState.update { it.copy(isVerifyingCloudKey = false) }
      }
    }
  }

  /** Syncs the live `/models` catalog for the active cloud provider into `availableModelIds`. */
  fun refreshCloudModels() {
    // Guard against duplicate overlapping /models sync requests on rapid chip taps.
    if (mutableUiState.value.isRefreshingCloudModels) return
    scope.launch {
      mutableUiState.update { it.copy(isRefreshingCloudModels = true) }
      try {
        val router = aiEdgePort as? HybridAiInferenceRouter
        when {
          router != null -> {
            val syncedModels = router.refreshCloudModels()
            val updatedCfg = cloudVaultPort.configFlow.value
            mutableUiState.update {
              it.copy(
                cloudLlmConfig = updatedCfg,
                toastMessage = "Synced ${syncedModels.size} live models for ${updatedCfg.provider.displayName}.",
              )
            }
          }
          else -> {
            val cfg = cloudVaultPort.configFlow.value
            mutableUiState.update {
              it.copy(
                toastMessage = "Showing ${cfg.availableModelIds.size} preset models for ${cfg.provider.displayName}.",
              )
            }
          }
        }
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (err: Throwable) {
        val safeMsg = redactApiSecrets(err.message ?: "Unable to sync live cloud models.")
        mutableUiState.update { it.copy(toastMessage = safeMsg) }
      } finally {
        mutableUiState.update { it.copy(isRefreshingCloudModels = false) }
      }
    }
  }

  // Synchronously pushes updated HybridAiInferenceRouter state into KitchenUiState.
  private fun syncHybridRuntimeState() {
    val synced = (aiEdgePort as? HybridAiInferenceRouter)?.refreshRuntimeState() ?: return
    mutableUiState.update { it.copy(aiRuntimeState = synced) }
  }
}
