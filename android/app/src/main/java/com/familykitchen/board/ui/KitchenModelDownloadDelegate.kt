package com.familykitchen.board.ui

import com.familykitchen.board.ai.HybridAiInferenceRouter
import com.familykitchen.board.domain.DownloadableGemmaPreset
import com.familykitchen.board.domain.ModelDownloadApprovalSpec
import com.familykitchen.board.ports.AiEdgePort
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ports.CloudCredentialVaultPort
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Cohesive presentation delegate orchestrating approval-gated Hugging Face Gemma 4 (.litertlm)
// precondition inspection, background .part downloads, and clean user cancellation.

/**
 * Internal presentation delegate handling the 4 approval-gated Gemma 4 download actions
 * (`RequestModelDownloadApproval`, `DismissModelDownloadApproval`,
 * `ConfirmApprovedModelDownload`, and `CancelActiveModelDownload`) on behalf of
 * [KitchenAiAndCookDelegate].
 *
 * Ensures zero network bytes are transferred before explicit user approval, shares the
 * [modelTransferDispatched] single-flight guard with SAF model imports, and restores clean
 * `Error` / idle UI state whenever the user cancels an active download.
 */
internal class KitchenModelDownloadDelegate(
  private val scope: CoroutineScope,
  private val mutableUiState: MutableStateFlow<KitchenUiState>,
  private val aiEdgePort: AiEdgePort,
  private val cloudVaultPort: CloudCredentialVaultPort,
  private val modelTransferDispatched: AtomicBoolean,
) {

  // Tracks explicit user-initiated Cancel taps to distinguish clean cancellation from network errors.
  private val userCancelRequested = AtomicBoolean(false)

  @Volatile
  private var activeDownloadJob: Job? = null

  /**
   * Queries free disk space, Wi-Fi vs metered cellular status, and resumable `.part` bytes for
   * [preset], populating [KitchenUiState.pendingModelDownloadApproval] without opening a socket.
   */
  fun requestApproval(preset: DownloadableGemmaPreset) {
    // Query StatFs headroom, Wi-Fi/cellular metering, and .part resume state asynchronously.
    scope.launch {
      try {
        val spec = aiEdgePort.inspectDownloadPreconditions(preset)
        mutableUiState.update { it.copy(pendingModelDownloadApproval = spec) }
      } catch (cancel: CancellationException) {
        // Preserve structured coroutine cancellation on ViewModel teardown.
        throw cancel
      } catch (err: Throwable) {
        // Surface fallback approval spec for preset so the dialog still opens cleanly on error.
        mutableUiState.update {
          it.copy(
            pendingModelDownloadApproval = ModelDownloadApprovalSpec(preset = preset),
            toastMessage = err.message ?: "Unable to inspect local storage preconditions.",
          )
        }
      }
    }
  }

  /** Dismisses `ModelDownloadApprovalDialog` without starting any network transfer. */
  fun dismissApproval() {
    // Clear modal approval state with zero network side effects when user taps 'Not Now'.
    mutableUiState.update { it.copy(pendingModelDownloadApproval = null) }
  }

  /**
   * Clears the approval dialog, switches [KitchenUiState.activeDeviceProfile] to
   * `preset.targetProfile`, enforces single-flight transfer safety, and launches background
   * resumable `.part` download + GPU initialization on [scope].
   */
  fun confirmApprovedDownload(preset: DownloadableGemmaPreset) {
    // Reject overlapping transfers while model discovery, SAF copy, download, or GPU load runs.
    if (
      aiEdgePort.runtimeState.value.isBackgroundBusy ||
      mutableUiState.value.aiRuntimeState.isBackgroundBusy ||
      !modelTransferDispatched.compareAndSet(false, true)
    ) {
      mutableUiState.update {
        it.copy(
          pendingModelDownloadApproval = null,
          activeDeviceProfile = preset.targetProfile,
          toastMessage = "Model setup is already running in the background — watch the live progress spinner.",
        )
      }
      return
    }

    // Dismiss the approval dialog immediately and inform the user that SavorAI stays usable.
    userCancelRequested.set(false)
    mutableUiState.update {
      it.copy(
        pendingModelDownloadApproval = null,
        activeDeviceProfile = preset.targetProfile,
        toastMessage = "Downloading ${preset.fileName} — you can keep using SavorAI while it downloads.",
      )
    }

    // Run network .part streaming + OpenCL GPU initialization in background across tab switches.
    activeDownloadJob = scope.launch {
      try {
        val result = aiEdgePort.downloadAndLoadPresetModel(preset)
        val syncedState = (aiEdgePort as? HybridAiInferenceRouter)?.refreshRuntimeState() ?: result
        when {
          userCancelRequested.getAndSet(false) -> applyCancelledUiState()
          result is AiRuntimeState.Ready -> mutableUiState.update { current ->
            // Sync cloudLlmConfig too so auto-switch from CLOUD_API -> ON_DEVICE reflects in UI.
            current.copy(
              aiRuntimeState = syncedState,
              cloudLlmConfig = cloudVaultPort.configFlow.value,
              toastMessage = "Downloaded & loaded ${result.capabilities.modelName} (${result.capabilities.backendLabel}) — ready!",
            )
          }
          result is AiRuntimeState.Error -> mutableUiState.update { current ->
            current.copy(
              aiRuntimeState = syncedState,
              toastMessage = result.message,
            )
          }
        }
      } catch (cancel: CancellationException) {
        // Restore non-busy Error state and confirmation toast before rethrowing cancellation.
        userCancelRequested.set(false)
        applyCancelledUiState()
        throw cancel
      } catch (err: Throwable) {
        val cancelledByUser = userCancelRequested.getAndSet(false)
        when {
          cancelledByUser -> applyCancelledUiState()
          else -> {
            // Ensure transient failures never leave UI stuck in an indefinite Importing spinner.
            val errMsg = err.message ?: "Unable to download ${preset.fileName}."
            mutableUiState.update { current ->
              val cleanRuntime = when (current.aiRuntimeState) {
                is AiRuntimeState.Importing -> AiRuntimeState.Error(errMsg)
                else -> current.aiRuntimeState
              }
              current.copy(
                aiRuntimeState = cleanRuntime,
                toastMessage = errMsg,
              )
            }
          }
        }
      } finally {
        // Release single-flight lock so the user can retry or resume at any time.
        activeDownloadJob = null
        modelTransferDispatched.set(false)
      }
    }
  }

  /**
   * Cancels the active background Gemma 4 download, triggers `.part` temp file cleanup via
   * [AiEdgePort.cancelActiveDownload], and restores clean `Error` / idle UI state.
   */
  fun cancelActiveDownload() {
    // Signal port stream teardown first so OnDeviceModelDownloader deletes the .part staging file.
    userCancelRequested.set(true)
    aiEdgePort.cancelActiveDownload()
    val runningJob = activeDownloadJob
    when (runningJob) {
      null -> {
        userCancelRequested.set(false)
        applyCancelledUiState()
      }
      else -> runningJob.cancel(CancellationException("Model download cancelled"))
    }
  }

  // Restores clean non-busy runtime state and posts the canonical cancellation toast message.
  private fun applyCancelledUiState() {
    val portState = (aiEdgePort as? HybridAiInferenceRouter)?.refreshRuntimeState()
      ?: aiEdgePort.runtimeState.value
    mutableUiState.update { current ->
      val restoredRuntime = when {
        portState is AiRuntimeState.Error -> portState
        current.aiRuntimeState is AiRuntimeState.Importing ->
          AiRuntimeState.Error(CANCELLED_DOWNLOAD_TOAST)
        else -> current.aiRuntimeState
      }
      current.copy(
        aiRuntimeState = restoredRuntime,
        toastMessage = CANCELLED_DOWNLOAD_TOAST,
      )
    }
  }

  companion object {
    /** Canonical toast confirmation posted after explicit user download cancellation & cleanup. */
    const val CANCELLED_DOWNLOAD_TOAST: String =
      "Cancelled Gemma model download and cleaned up temporary file."
  }
}
