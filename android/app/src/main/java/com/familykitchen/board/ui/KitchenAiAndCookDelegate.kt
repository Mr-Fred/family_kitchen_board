package com.familykitchen.board.ui

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.familykitchen.board.ai.AiChefCoordinator
import com.familykitchen.board.ai.AiTurnResolutionStatus
import com.familykitchen.board.data.InMemoryCloudCredentialVault
import com.familykitchen.board.data.KitchenImagePreprocessor
import com.familykitchen.board.domain.AiChefAction
import com.familykitchen.board.domain.CookModeEngine
import com.familykitchen.board.domain.CookModeSessionState
import com.familykitchen.board.domain.CookedDeductionPayload
import com.familykitchen.board.domain.CustomGroceryItem
import com.familykitchen.board.domain.IntakeCommitTarget
import com.familykitchen.board.domain.MealSlotStatus
import com.familykitchen.board.domain.MealSlotType
import com.familykitchen.board.domain.PantryLedgerEngine
import com.familykitchen.board.domain.PostCookCompletionSummary
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.RecipeCreationMode
import com.familykitchen.board.domain.RestockDelta
import com.familykitchen.board.domain.WorkoutTiming
import com.familykitchen.board.domain.isWeekdayBreakfastEligible
import com.familykitchen.board.ports.AiEdgePort
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ports.CloudCredentialVaultPort
import com.familykitchen.board.ports.KitchenSnapshot
import com.familykitchen.board.ports.KitchenStoragePort
import com.familykitchen.board.ports.VoiceInputPort
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Cohesive AI Sous-Chef multimodal turn dispatcher, Step-by-Step Cook Mode controller (with
// Mini-Timer minimize & Post-Cook summary emission), and bilingual voice-routing delegate.

/**
 * Normalizes `content://` gallery URIs and `FileProvider` camera captures in `cacheDir/captures/`
 * into EXIF-rotated `<= 1280px` JPEGs (`quality = 88`) ready for `Content.ImageFile`.
 */
internal suspend fun preprocessImagePathOrUri(safeContext: Context?, rawUriOrPath: String): String {
  // Hermetic JVM tests omit Android Context and pass pre-created temp file paths directly.
  if (safeContext == null) return rawUriOrPath
  return when {
    rawUriOrPath.startsWith("content://") ->
      // Stream PickVisualMedia content:// URI off the UI thread and downscale to <= 1280px.
      KitchenImagePreprocessor.preprocessUriToInferenceJpeg(
        context = safeContext,
        sourceUri = Uri.parse(rawUriOrPath),
      ).absolutePath
    else -> {
      val rawFile = File(rawUriOrPath)
      when {
        rawFile.exists() -> {
          // Wrap raw TakePicture file in a scoped FileProvider URI (never legacy Uri.fromFile).
          val scopedUri = FileProvider.getUriForFile(
            safeContext,
            "${safeContext.packageName}${KitchenImagePreprocessor.FILE_PROVIDER_SUFFIX}",
            rawFile,
          )
          val processed = KitchenImagePreprocessor.preprocessUriToInferenceJpeg(safeContext, scopedUri)
          // Delete the full-resolution camera file once the resized inference JPEG is written.
          if (processed.absolutePath != rawFile.absolutePath) {
            KitchenImagePreprocessor.deleteQuietly(rawFile)
          }
          processed.absolutePath
        }
        else -> rawUriOrPath
      }
    }
  }
}

/**
 * Internal delegate coordinating `SavorAI` (`AiChefAction`) multimodal/text turns, `Step-by-Step
 * Cook Mode` transitions (including persistent Mini-Timer minimize & Post-Cook summaries), and
 * bilingual (`en-US` / `fr-FR`) voice transcript routing on behalf of [KitchenBoardViewModel].
 */
internal class KitchenAiAndCookDelegate(
  private val scope: CoroutineScope,
  private val mutableUiState: MutableStateFlow<KitchenUiState>,
  private val storagePort: KitchenStoragePort,
  private val aiEdgePort: AiEdgePort,
  private val voiceInputPort: VoiceInputPort,
  private val aiChefCoordinator: AiChefCoordinator,
  private val preprocessImageUri: suspend (String) -> String,
  private val deleteTempImage: (String?) -> Unit,
  private val nowIso: () -> String,
  private val runGuarded: (suspend () -> Unit) -> Unit,
  private val onOpenAddRecipeStudio: (RecipeCreationMode) -> Unit,
  private val onSchedulePresetRecipe: (Recipe, MealSlotType, Int, Boolean) -> Unit,
  private val onToggleSavedRecipe: (String) -> Unit,
  private val onQueueMissingIngredients: (Set<String>, List<RestockDelta>) -> Unit,
  private val onOpenAdjustSlotSheet: (String) -> Unit,
  private val onAddCustomGroceryItem: (CustomGroceryItem) -> Unit,
  private val recordUndoSnapshot: (KitchenSnapshot, String, TopLevelDestination) -> Unit = { _, _, _ -> },
  private val onUndoLastAgentAction: () -> Unit = {},
  private val onDismissAgentActionBanner: () -> Unit = {},
  private val cloudVaultPort: CloudCredentialVaultPort = InMemoryCloudCredentialVault(),
) {

  // Single-flight flag blocking duplicate SAF import coroutine launches on rapid double-taps.
  private val modelImportDispatched = AtomicBoolean(false)

  // Dedicated Cloud AI setup & Keystore verification delegate keeping this class well under 700 LOC.
  private val cloudConfigDelegate = KitchenCloudConfigDelegate(
    scope = scope,
    mutableUiState = mutableUiState,
    aiEdgePort = aiEdgePort,
    cloudVaultPort = cloudVaultPort,
  )

  // Dedicated approval-gated Gemma 4 (.litertlm) download & cancellation delegate.
  private val modelDownloadDelegate = KitchenModelDownloadDelegate(
    scope = scope,
    mutableUiState = mutableUiState,
    aiEdgePort = aiEdgePort,
    cloudVaultPort = cloudVaultPort,
    modelTransferDispatched = modelImportDispatched,
  )

  /** Launches full-screen `StepByStepCookModeScreen` for [recipe] with optional hands-free mic. */
  fun startCookMode(
    recipe: Recipe,
    slotId: String? = null,
    handsFreeVoiceActive: Boolean = false,
  ) {
    val state = mutableUiState.value
    // Inherit scheduled slot servings (including +1 postpartum / +2 guests) when tied to a slot.
    val resolvedServings = slotId?.let { id ->
      state.activeDisplayedBoard.slots.firstOrNull { it.slotId == id }?.targetServings
        ?: state.snapshot.weeklyBoard.slots.firstOrNull { it.slotId == id }?.targetServings
    } ?: recipe.baseServings.coerceAtLeast(1)

    // Pre-populate step ingredient chips and multi-step countdown timers via CookModeEngine.
    val session = CookModeEngine.startCookSession(
      recipe = recipe,
      slotId = slotId,
      targetServings = resolvedServings,
      catalog = state.snapshot.ingredients,
      initialCheckedIngredients = state.snapshot.checkedReaderIngredientsByRecipeId[recipe.recipeId].orEmpty(),
      handsFreeVoiceActive = handsFreeVoiceActive,
    )
    mutableUiState.update {
      it.copy(
        activeCookModeSession = session,
        isCookModeMinimized = false,
        postCookSummary = null,
        activeVoiceTarget = if (handsFreeVoiceActive) VoiceCaptureTarget.COOK_MODE else it.activeVoiceTarget,
      )
    }
    // Start offline SpeechRecognizer immediately when launched from the Hands-Free Sous-Chef bar.
    if (handsFreeVoiceActive) {
      voiceInputPort.startListening(VoiceInputPort.LOCALE_EN_US)
    }
  }

  /** Collapses full-screen Cook Mode into the persistent Mini-Timer bar while keeping timers live. */
  fun minimizeCookMode() {
    // Only minimize when an active CookModeSessionState exists so countdown ticks keep running.
    if (mutableUiState.value.activeCookModeSession != null) {
      mutableUiState.update { it.copy(isCookModeMinimized = true) }
    }
  }

  /** Restores a minimized Cook Mode session back to full screen. */
  fun resumeMinimizedCookMode() {
    // Re-expand the existing CookModeSessionState without resetting step index or timer progress.
    if (mutableUiState.value.activeCookModeSession != null) {
      mutableUiState.update { it.copy(isCookModeMinimized = false) }
    }
  }

  /** Toggles hands-free voice listening inside `StepByStepCookModeScreen`. */
  fun toggleCookModeHandsFreeMic(enabled: Boolean) {
    val currentSession = mutableUiState.value.activeCookModeSession ?: return
    when {
      enabled -> {
        // Point VoiceCaptureTarget at COOK_MODE so spoken "Next step" / "+1 min" route cleanly.
        mutableUiState.update {
          it.copy(
            activeVoiceTarget = VoiceCaptureTarget.COOK_MODE,
            activeCookModeSession = currentSession.copy(isHandsFreeVoiceActive = true),
          )
        }
        // Revert mic toggle state non-silently if Android SpeechRecognizer is unavailable offline.
        if (!voiceInputPort.startListening(VoiceInputPort.LOCALE_EN_US)) {
          mutableUiState.update {
            it.copy(activeCookModeSession = currentSession.copy(isHandsFreeVoiceActive = false))
              .withToast("Hands-free speech service unavailable; use quick Q&A chips.")
          }
        }
      }
      else -> {
        // Deterministically stop native microphone recognition when user disables hands-free mic.
        voiceInputPort.stopListening()
        mutableUiState.update { it.copy(activeCookModeSession = currentSession.copy(isHandsFreeVoiceActive = false)) }
      }
    }
  }

  /** Opens `CookedOverrideSheet` for the active Cook Mode meal while keeping Cook Mode active. */
  fun openCookModePantryAdjuster() {
    val state = mutableUiState.value
    val session = state.activeCookModeSession ?: return
    // Resolve explicit session slotId first, then fall back to matching PLANNED slot on today/board.
    val matchingSlotId = session.slotId
      ?: state.dayMealCards
        .firstOrNull { it.recipe.recipeId == session.recipe.recipeId && it.slot.status == MealSlotStatus.PLANNED }
        ?.slot?.slotId
      ?: state.snapshot.weeklyBoard.slots
        .firstOrNull { it.recipeId == session.recipe.recipeId && it.status == MealSlotStatus.PLANNED }
        ?.slotId

    when {
      matchingSlotId != null -> onOpenAdjustSlotSheet(matchingSlotId)
      else -> mutableUiState.update {
        it.withToast("Schedule ${session.recipe.title} on your Meal Plan to customize per-slot ingredient overrides.")
      }
    }
  }

  /**
   * Completes Step-by-Step Cook Mode, commits `commitCookedMealBatch` in 1 `O(1)` write, and
   * opens `PostCookCompletionSheet` with leftover portion & family reaction chips.
   */
  fun completeCookModeAndDeductPantry(session: CookModeSessionState) = runGuarded {
    val state = mutableUiState.value
    val current = state.snapshot
    // Match the scheduled board slot so workout boosts and slot completion status update together.
    val resolvedSlot = session.slotId?.let { id -> current.weeklyBoard.slots.firstOrNull { it.slotId == id } }
      ?: state.dayMealCards
        .firstOrNull { it.recipe.recipeId == session.recipe.recipeId && it.slot.status == MealSlotStatus.PLANNED }
        ?.slot

    val targetSlotId = resolvedSlot?.slotId ?: "cookmode_adhoc_${session.recipe.recipeId}"
    val stamp = nowIso()
    // Compute scaled BOM + flat 1x workout boost deduction clamped at 0.0 (EC-07 & EC-10).
    val deduction = PantryLedgerEngine.applyCookedDeduction(
      pantry = current.pantryEntries, recipe = session.recipe, catalog = current.ingredients,
      payload = CookedDeductionPayload(
        slotId = targetSlotId, recipeId = session.recipe.recipeId,
        targetServings = session.targetServings,
        activeWorkoutModifier = resolvedSlot?.activeWorkoutModifier ?: WorkoutTiming.NONE,
      ),
      nowIso = stamp, householdAllergens = current.householdProfile.householdAllergens,
    )
    // Release active SpeechRecognizer session before closing full-screen Cook Mode.
    if (session.isHandsFreeVoiceActive) voiceInputPort.stopListening()
    storagePort.commitCookedMealBatch(current, targetSlotId, deduction, stamp)

    // Build PostCookCompletionSummary so extra batch-cooked portions can fill tomorrow's 0-BOM lunch.
    val householdServings = current.householdProfile.effectiveTargetServings
    val cookedServings = session.targetServings.coerceAtLeast(1)
    val extraLeftovers = (cookedServings - householdServings).coerceAtLeast(0)
    val cookedDay = (resolvedSlot?.dayIndex ?: state.todayDayIndex).coerceIn(0, 6)
    val summary = PostCookCompletionSummary(
      recipeId = session.recipe.recipeId, recipeTitle = session.recipe.title,
      cookedSlotId = resolvedSlot?.slotId, cookedDayIndex = cookedDay,
      cookedServings = cookedServings, householdServings = householdServings,
      extraLeftoverServings = extraLeftovers,
    )
    mutableUiState.update {
      it.copy(activeCookModeSession = null, isCookModeMinimized = false, postCookSummary = summary)
        .withToast("Completed ${session.recipe.title} and updated your Pantry.")
    }
  }

  /** Ends `StepByStepCookModeScreen` (and any minimized Mini-Timer bar) without deducting stock. */
  fun exitCookMode() {
    // Stop active speech recognition and clear both full-screen and minimized Mini-Timer state.
    if (mutableUiState.value.activeCookModeSession?.isHandsFreeVoiceActive == true) {
      voiceInputPort.stopListening()
    }
    mutableUiState.update { it.copy(activeCookModeSession = null, isCookModeMinimized = false) }
  }

  /** Dispatches all unidirectional [AiChefAction] intents emitted by `AiChefScreen` / `SousChefSheet`. */
  fun handleAiChefAction(action: AiChefAction) {
    when (action) {
      is AiChefAction.SubmitPrompt -> submitTurn(action.promptText, action.attachedImagePath)

      is AiChefAction.SelectQuickPrompt -> {
        // Route recipe-import pills straight to the 3-mode AddOrEditRecipeSheet (SMART_PASTE tab).
        val lowerPrompt = action.promptText.lowercase()
        val stagedImage = mutableUiState.value.attachedImagePath
        when {
          lowerPrompt.contains("paste recipe") || lowerPrompt.contains("import recipe") -> {
            mutableUiState.update { it.copy(isSousChefSheetOpen = false) }
            onOpenAddRecipeStudio(RecipeCreationMode.SMART_PASTE)
          }
          (lowerPrompt.contains("scan fridge") || lowerPrompt.contains("scan spice")) && stagedImage.isNullOrBlank() ->
            mutableUiState.update {
              it.withToast("Tap Camera or Photo below to scan your fridge or spice rack.")
            }
          else -> submitTurn(action.promptText, attachedImagePath = null)
        }
      }

      is AiChefAction.AttachImage -> aiDelV2AttachAndPreprocessImage(action.sourceUriOrPath, action.displayLabel)

      AiChefAction.ClearAttachedImage -> {
        // Delete temporary preprocessed JPEG immediately when user removes attachment chip.
        deleteTempImage(mutableUiState.value.attachedImagePath)
        mutableUiState.update { it.copy(attachedImagePath = null, attachedImageLabel = null) }
      }

      is AiChefAction.SelectDeviceProfile ->
        mutableUiState.update { it.copy(activeDeviceProfile = action.profile) }

      is AiChefAction.ImportModelUri -> aiDelV2ImportOnDeviceModel(action.sourceUriString, action.displayName)

      is AiChefAction.ApplyMealSwaps -> runGuarded {
        // Commit approved AI slot swaps in 1 O(1) write and retain pre-swap snapshot for 1-tap Undo.
        val current = mutableUiState.value.snapshot
        val next = aiChefCoordinator.applyConfirmedMealSwaps(current, action.proposal, nowIso())
        val summary = "Applied ${action.proposal.slotSwaps.size} meal swaps to Meal Plan."
        storagePort.saveSnapshotBatch(next)
        recordUndoSnapshot(current, summary, TopLevelDestination.PLAN)
        mutableUiState.update {
          it.copy(appliedSwapProposalIds = it.appliedSwapProposalIds + action.proposal.proposalId)
            .withToast(summary)
        }
      }

      is AiChefAction.QueueSuggestedIngredients -> runGuarded {
        // Merge newly discovered catalog staples and queue onto Smart Grocery in 1 atomic write.
        val current = mutableUiState.value.snapshot
        val next = aiChefCoordinator.queueSuggestedBrainBoosters(current, action.proposal, nowIso())
        val summary = "Added ${action.proposal.newIngredientSuggestions.size} items to Grocery List."
        storagePort.saveSnapshotBatch(next)
        recordUndoSnapshot(current, summary, TopLevelDestination.GROCERIES)
        mutableUiState.update {
          it.copy(queuedIngredientProposalIds = it.queuedIngredientProposalIds + action.proposal.proposalId)
            .withToast(summary)
        }
      }

      is AiChefAction.ConfirmVisualRestock -> runGuarded {
        // Apply confirmation-gated visual scan restock deltas only after explicit user approval.
        val current = mutableUiState.value.snapshot
        val next = aiChefCoordinator.applyConfirmedVisualRestock(current, action.intakeResult, nowIso())
        val itemCount = action.intakeResult.groundedRestockDeltas.size + action.intakeResult.proposedNewCatalogItems.size
        val summary = "Stocked $itemCount scanned items in Pantry."
        if (next != current) {
          storagePort.saveSnapshotBatch(next)
          recordUndoSnapshot(current, summary, TopLevelDestination.PANTRY)
        }
        val confirmedIds = current.aiTurnHistory.filter { it.visualIntakeResult == action.intakeResult }.map { it.turnId }.toSet()
        mutableUiState.update {
          it.copy(confirmedVisualTurnIds = it.confirmedVisualTurnIds + confirmedIds).withToast(summary)
        }
      }

      is AiChefAction.CommitVisualIntakeSelection -> runGuarded {
        // Commit selectively checked scanned foods/spices to Pantry or Grocery in 1 O(1) write.
        val current = mutableUiState.value.snapshot
        val next = aiChefCoordinator.applySelectiveVisualIntakeCommit(
          current, action.intakeResult, action.selectedIngredientIds,
          action.selectedNewLabels, action.destination, nowIso(),
        )
        if (next == current) {
          mutableUiState.update { it.withToast("Select at least one detected item to add.") }
          return@runGuarded
        }
        storagePort.saveSnapshotBatch(next)
        val newCatalogCount = (next.ingredients.size - current.ingredients.size).coerceAtLeast(0)
        val matchedCount = action.intakeResult.groundedRestockDeltas.count { it.ingredientId in action.selectedIngredientIds }
        val committedCount = when (action.destination) {
          IntakeCommitTarget.PANTRY -> (matchedCount + newCatalogCount).coerceAtLeast(1)
          IntakeCommitTarget.GROCERY -> (next.aiAddedGroceryIds - current.aiAddedGroceryIds).size.coerceAtLeast(1)
        }
        val adjustTarget = if (action.destination == IntakeCommitTarget.PANTRY) TopLevelDestination.PANTRY else TopLevelDestination.GROCERIES
        val summary = when (action.destination) {
          IntakeCommitTarget.PANTRY -> "Stocked $committedCount items in Pantry."
          IntakeCommitTarget.GROCERY -> "Added $committedCount items to Grocery List."
        }
        val confirmedIds = current.aiTurnHistory
          .filter { it.turnId == action.turnId || it.visualIntakeResult == action.intakeResult }
          .map { it.turnId }.toSet() + setOfNotNull(action.turnId.takeIf { it.isNotBlank() })
        recordUndoSnapshot(current, summary, adjustTarget)
        mutableUiState.update {
          it.copy(confirmedVisualTurnIds = it.confirmedVisualTurnIds + confirmedIds).withToast(summary)
        }
      }

      AiChefAction.UndoLastAgentAction -> onUndoLastAgentAction()

      AiChefAction.DismissAgentActionBanner -> onDismissAgentActionBanner()

      is AiChefAction.StartVoiceInput -> {
        // Route microphone transcripts to the Sous-Chef composer input field.
        mutableUiState.update { it.copy(activeVoiceTarget = VoiceCaptureTarget.AI_CHEF) }
        voiceInputPort.startListening(action.languageTag)
      }

      AiChefAction.StopVoiceInput -> voiceInputPort.stopListening()

      AiChefAction.ResetConversation -> runGuarded {
        // Reset native LiteRT/Cloud conversation context while keeping ElephantMemory intact.
        aiEdgePort.resetConversation()
        onDismissAgentActionBanner()
        val current = mutableUiState.value.snapshot
        storagePort.saveSnapshotBatch(current.copy(aiTurnHistory = emptyList(), updatedAtIso = nowIso()))
        mutableUiState.update {
          it.copy(
            appliedSwapProposalIds = emptySet(),
            queuedIngredientProposalIds = emptySet(),
            confirmedVisualTurnIds = emptySet(),
          ).withToast("Reset SavorAI conversation.")
        }
      }

      is AiChefAction.StartCookModeFromAi -> {
        // Close modal SousChefSheet cleanly before expanding full-screen StepByStepCookModeScreen.
        mutableUiState.update { it.copy(isSousChefSheetOpen = false) }
        startCookMode(recipe = action.recipe, slotId = action.slotId, handsFreeVoiceActive = false)
      }

      is AiChefAction.AddRecipeToPlanFromAi -> {
        // Default AI recipe scheduling to real todayDayIndex (never hardcoded Monday 0).
        // Respect EC-01 weekday breakfast <= 25m cap when picking a default MealSlotType.
        val state = mutableUiState.value
        val resolvedSlotType = action.slotType ?: action.recipe.allowedSlots.firstOrNull { cand ->
          cand != MealSlotType.BREAKFAST || isWeekdayBreakfastEligible(action.recipe)
        } ?: MealSlotType.DINNER
        val resolvedDay = action.dayIndex ?: state.todayDayIndex
        onSchedulePresetRecipe(action.recipe, resolvedSlotType, resolvedDay, false)
      }

      is AiChefAction.ToggleSaveRecipe -> onToggleSavedRecipe(action.recipeId)

      is AiChefAction.ApplyRecipeSubstitution -> runGuarded {
        // Recompute BOM macros and sync matching assistant turn cards in 1 atomic batch write.
        val current = mutableUiState.value.snapshot
        val reduced = reduceAiRecipeSubstitution(
          snapshot = current,
          recipeId = action.recipeId,
          fromIngredientId = action.fromIngredientId,
          toIngredientId = action.toIngredientId,
          nowIso = nowIso(),
        ) ?: return@runGuarded
        val summary = "Updated ${reduced.second.title} to ${reduced.second.caloriesPerServing} kcal per serving."
        storagePort.saveSnapshotBatch(reduced.first)
        recordUndoSnapshot(current, summary, TopLevelDestination.RECIPES)
        mutableUiState.update {
          it.withToast(summary)
        }
      }

      is AiChefAction.QueueMissingRecipeStaples ->
        onQueueMissingIngredients(action.ingredientIds.toSet(), action.deltas)

      AiChefAction.DismissContextBanner ->
        mutableUiState.update { it.copy(isAiContextBannerDismissed = true) }

      is AiChefAction.SelectAiExecutionMode ->
        cloudConfigDelegate.updateExecutionMode(action.mode)

      is AiChefAction.SelectCloudProvider ->
        cloudConfigDelegate.selectProvider(action.provider)

      is AiChefAction.UpdateCloudModelId ->
        cloudConfigDelegate.updateModelId(action.modelId)

      is AiChefAction.SaveCloudApiKey ->
        cloudConfigDelegate.saveAndOptionallyVerifyKey(action)

      is AiChefAction.ClearCloudApiKey ->
        cloudConfigDelegate.clearApiKey(action.provider)

      AiChefAction.VerifyCloudConnection ->
        cloudConfigDelegate.verifyActiveConnection()

      AiChefAction.RefreshCloudModels ->
        cloudConfigDelegate.refreshCloudModels()

      is AiChefAction.RequestModelDownloadApproval ->
        modelDownloadDelegate.requestApproval(action.preset)

      is AiChefAction.ConfirmApprovedModelDownload ->
        modelDownloadDelegate.confirmApprovedDownload(action.preset)

      AiChefAction.DismissModelDownloadApproval ->
        modelDownloadDelegate.dismissApproval()

      AiChefAction.CancelActiveModelDownload ->
        modelDownloadDelegate.cancelActiveDownload()
    }
  }

  /** Submits a text or multimodal image turn through [AiChefCoordinator] and persists in `O(1)`. */
  fun submitTurn(promptText: String, attachedImagePath: String?) {
    val stateSnapshot = mutableUiState.value
    if (stateSnapshot.isAiGenerating || stateSnapshot.isPreprocessingImage) {
      mutableUiState.update {
        it.withToast(
          when {
            stateSnapshot.isPreprocessingImage ->
              "Photo optimization is still finishing — try sending in a moment."
            else ->
              "SavorAI is already working on your request — watch the live spinner."
          },
        )
      }
      return
    }
    val effectiveImagePath = attachedImagePath ?: stateSnapshot.attachedImagePath
    if (promptText.isBlank() && effectiveImagePath.isNullOrBlank()) return
    val isVisionTurn = !effectiveImagePath.isNullOrBlank()

    mutableUiState.update {
      it.copy(
        isAiGenerating = true,
        isGeneratingVisionTurn = isVisionTurn,
      )
    }
    scope.launch {
      try {
        val currentState = mutableUiState.value
        val preTurnSnapshot = currentState.snapshot
        // Pass real todayDayIndex and currentSimulatedHour24 so time-of-day and "tonight" stay synced.
        val outcome = aiChefCoordinator.submitTurn(
          snapshot = preTurnSnapshot,
          promptText = promptText,
          attachedImagePath = effectiveImagePath,
          attachedImageLabel = currentState.attachedImageLabel,
          deviceProfile = currentState.activeDeviceProfile,
          nowIso = nowIso(),
          activeDayIndex = currentState.todayDayIndex,
          currentHour24 = currentState.currentSimulatedHour24,
        )
        storagePort.saveSnapshotBatch(outcome.updatedSnapshot)
        if (outcome.status == AiTurnResolutionStatus.DETERMINISTIC_TOOL_EXECUTED) {
          val adjustTarget = when (outcome.assistantTurn.toolCallResult?.toolName) {
            "queue_grocery_items" -> TopLevelDestination.GROCERIES
            "schedule_family_meal", "activate_life_event" -> TopLevelDestination.PLAN
            else -> TopLevelDestination.PANTRY
          }
          val summary = outcome.assistantTurn.toolCallResult?.summaryMessage
            ?: outcome.toastMessage
            ?: "Updated kitchen state."
          recordUndoSnapshot(preTurnSnapshot, summary, adjustTarget)
        }
        val newOutcomeToast = outcome.visionWarningMessage ?: outcome.toastMessage
        mutableUiState.update {
          val cleared = it.copy(
            isAiGenerating = false,
            isGeneratingVisionTurn = false,
            attachedImagePath = null,
            attachedImageLabel = null,
          )
          if (!newOutcomeToast.isNullOrBlank()) cleared.withToast(newOutcomeToast) else cleared
        }
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (err: Throwable) {
        mutableUiState.update {
          it.withToast(err.message ?: "Unable to complete SavorAI request.")
        }
      } finally {
        mutableUiState.update {
          it.copy(
            isAiGenerating = false,
            isGeneratingVisionTurn = false,
          )
        }
      }
    }
  }

  /** Routes finalized speech recognition transcripts by active [VoiceCaptureTarget]. */
  fun routeVoiceTranscript(transcript: String) {
    val state = mutableUiState.value
    when (state.activeVoiceTarget) {
      VoiceCaptureTarget.COOK_MODE -> {
        val session = state.activeCookModeSession ?: return
        val nextSession = CookModeEngine.applyVoiceTranscriptToCookSession(
          state = session,
          transcript = transcript,
          catalog = state.snapshot.ingredients,
          youngestStage = state.snapshot.householdProfile.youngestStage,
          pantryEntries = state.snapshot.pantryEntries,
        )
        mutableUiState.update { it.copy(activeCookModeSession = nextSession) }
      }

      VoiceCaptureTarget.GROCERY_DICTATION -> {
        val clean = transcript.trim()
        if (clean.isNotEmpty()) {
          val slug = clean.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "dictated" }
          onAddCustomGroceryItem(CustomGroceryItem(customId = "voice_$slug", displayName = clean))
        }
      }

      VoiceCaptureTarget.AI_CHEF -> Unit
    }
  }

  private fun aiDelV2AttachAndPreprocessImage(rawUriOrPath: String, displayLabel: String) {
    if (mutableUiState.value.isPreprocessingImage) {
      mutableUiState.update {
        it.withToast("Still optimizing your previous photo — watch the spinner below.")
      }
      return
    }
    val previousPath = mutableUiState.value.attachedImagePath
    if (!previousPath.isNullOrBlank() && previousPath != rawUriOrPath) {
      deleteTempImage(previousPath)
    }
    val safeLabel = displayLabel.ifBlank { "photo" }
    mutableUiState.update {
      it.copy(
        isPreprocessingImage = true,
        preprocessingImageLabel = safeLabel,
      )
    }
    scope.launch {
      try {
        val preprocessedPath = preprocessImageUri(rawUriOrPath)
        mutableUiState.update {
          it.copy(
            attachedImagePath = preprocessedPath,
            attachedImageLabel = safeLabel,
          )
        }
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (err: Throwable) {
        mutableUiState.update {
          it.withToast(err.message ?: "Unable to prepare kitchen photo.")
        }
      } finally {
        mutableUiState.update {
          it.copy(
            isPreprocessingImage = false,
            preprocessingImageLabel = null,
          )
        }
      }
    }
  }

  private fun aiDelV2ImportOnDeviceModel(sourceUriString: String, displayName: String?) {
    if (
      aiEdgePort.runtimeState.value.isBackgroundBusy ||
      mutableUiState.value.aiRuntimeState.isBackgroundBusy ||
      !modelImportDispatched.compareAndSet(false, true)
    ) {
      mutableUiState.update {
        it.withToast("Model setup is already running in the background — watch the live progress spinner.")
      }
      return
    }
    val cleanDisplayName = displayName?.trim()?.takeIf { it.isNotEmpty() }
    mutableUiState.update {
      it.withToast("Importing ${cleanDisplayName ?: "Gemma model"} — see live progress spinner...")
    }
    scope.launch {
      try {
        val result = aiEdgePort.importAndLoadModel(sourceUriString, displayName)
        when (result) {
          is AiRuntimeState.Ready -> mutableUiState.update {
            it.withToast("Loaded ${result.capabilities.modelName} (${result.capabilities.backendLabel}) — ready!")
          }
          is AiRuntimeState.Error -> mutableUiState.update { it.withToast(result.message) }
          else -> Unit
        }
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (err: Throwable) {
        mutableUiState.update {
          it.withToast(err.message ?: "Unable to import on-device model.")
        }
      } finally {
        modelImportDispatched.set(false)
      }
    }
  }
}
