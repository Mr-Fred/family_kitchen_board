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
import com.familykitchen.board.domain.Recipe
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

// Cohesive AI Chef multimodal turn dispatcher, Step-by-Step Cook Mode controller, and bilingual
// voice-routing delegate keeping KitchenBoardViewModel modular and well below 700 logical LOC.

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
 * Cook Mode` transitions, and bilingual (`en-US` / `fr-FR`) voice transcript routing on behalf of
 * [KitchenBoardViewModel].
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
  private val onOpenCustomMealSheet: (MealSlotType) -> Unit,
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

  // Dedicated Cloud AI setup & Keystore verification delegate keeping this class < 600 logical LOC.
  private val cloudConfigDelegate = KitchenCloudConfigDelegate(
    scope = scope,
    mutableUiState = mutableUiState,
    aiEdgePort = aiEdgePort,
    cloudVaultPort = cloudVaultPort,
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
      state.snapshot.weeklyBoard.slots.firstOrNull { it.slotId == id }?.targetServings
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
        activeVoiceTarget = if (handsFreeVoiceActive) VoiceCaptureTarget.COOK_MODE else it.activeVoiceTarget,
      )
    }
    // Start offline SpeechRecognizer immediately when launched from the Hands-Free Sous-Chef bar.
    if (handsFreeVoiceActive) {
      voiceInputPort.startListening(VoiceInputPort.LOCALE_EN_US)
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
        val started = voiceInputPort.startListening(VoiceInputPort.LOCALE_EN_US)
        // Revert active indicator and inform cook if platform speech binding is unavailable.
        if (!started) {
          mutableUiState.update {
            it.copy(
              activeCookModeSession = currentSession.copy(isHandsFreeVoiceActive = false),
              toastMessage = "Hands-free speech service unavailable; use quick Q&A chips.",
            )
          }
        }
      }
      else -> {
        // Stop Main-Looper SpeechRecognizer cleanly when the cook mutes the hands-free HUD.
        voiceInputPort.stopListening()
        mutableUiState.update {
          it.copy(activeCookModeSession = currentSession.copy(isHandsFreeVoiceActive = false))
        }
      }
    }
  }

  /** Opens `CookedOverrideSheet` for the active Cook Mode meal while keeping Cook Mode active. */
  fun openCookModePantryAdjuster() {
    val state = mutableUiState.value
    val session = state.activeCookModeSession ?: return
    // Match the explicit board slotId, today's planned slot, or any planned weekly slot for this dish.
    val matchingSlotId = session.slotId
      ?: state.dayMealCards
        .firstOrNull { it.recipe.recipeId == session.recipe.recipeId && it.slot.status == MealSlotStatus.PLANNED }
        ?.slot?.slotId
      ?: state.snapshot.weeklyBoard.slots
        .firstOrNull { it.recipeId == session.recipe.recipeId && it.status == MealSlotStatus.PLANNED }
        ?.slotId

    when {
      matchingSlotId != null -> onOpenAdjustSlotSheet(matchingSlotId)
      else -> {
        // Keep activeCookModeSession and running step timers intact on ad-hoc Reader/AI sessions.
        mutableUiState.update {
          it.copy(
            toastMessage = "Schedule '${session.recipe.title}' on your Meal Plan to customize per-slot ingredient overrides.",
          )
        }
      }
    }
  }

  /** Completes Step-by-Step Cook Mode and commits `commitCookedMealBatch` in 1 `O(1)` write. */
  fun completeCookModeAndDeductPantry(session: CookModeSessionState) {
    runGuarded {
      val state = mutableUiState.value
      val current = state.snapshot
      // Link ad-hoc Reader/AI Cook Mode launches to a matching PLANNED slot when available.
      val resolvedSlot = session.slotId?.let { id ->
        current.weeklyBoard.slots.firstOrNull { it.slotId == id }
      } ?: state.dayMealCards
        .firstOrNull { it.recipe.recipeId == session.recipe.recipeId && it.slot.status == MealSlotStatus.PLANNED }
        ?.slot

      val targetSlotId = resolvedSlot?.slotId ?: "cookmode_adhoc_${session.recipe.recipeId}"
      // Compute single-pass O(M) pantry deduction respecting EC-07 flat 1x workout boosts & EC-10/11.
      val deduction = PantryLedgerEngine.applyCookedDeduction(
        pantry = current.pantryEntries,
        recipe = session.recipe,
        catalog = current.ingredients,
        payload = CookedDeductionPayload(
          slotId = targetSlotId,
          recipeId = session.recipe.recipeId,
          targetServings = session.targetServings,
          activeWorkoutModifier = resolvedSlot?.activeWorkoutModifier ?: WorkoutTiming.NONE,
        ),
        nowIso = nowIso(),
        householdAllergens = current.householdProfile.householdAllergens,
      )
      // Release active microphone capture before closing the full-screen Cook Mode overlay.
      if (session.isHandsFreeVoiceActive) {
        voiceInputPort.stopListening()
      }
      // Persist deducted pantry, slot status, and rescued expiring counter in 1 O(1) write.
      storagePort.commitCookedMealBatch(current, targetSlotId, deduction, nowIso())
      mutableUiState.update {
        it.copy(
          activeCookModeSession = null,
          toastMessage = "Completed '${session.recipe.title}' and updated your Pantry.",
        )
      }
    }
  }

  /** Exits full-screen `StepByStepCookModeScreen` cleanly without deducting pantry stock. */
  fun exitCookMode() {
    val session = mutableUiState.value.activeCookModeSession
    if (session?.isHandsFreeVoiceActive == true) {
      voiceInputPort.stopListening()
    }
    mutableUiState.update { it.copy(activeCookModeSession = null) }
  }

  /** Dispatches all unidirectional [AiChefAction] intents emitted by `AiChefScreen`. */
  fun handleAiChefAction(action: AiChefAction) {
    // Exhaustive sealed-interface dispatch ensures zero unhandled AI Chef UI actions.
    when (action) {
      is AiChefAction.SubmitPrompt -> submitTurn(action.promptText, action.attachedImagePath)

      is AiChefAction.SelectQuickPrompt -> {
        // Route recipe-import pills to CustomMealSheet and guide photo-scan pills cleanly.
        val lowerPrompt = action.promptText.lowercase()
        val stagedImage = mutableUiState.value.attachedImagePath
        when {
          lowerPrompt.contains("paste recipe") || lowerPrompt.contains("import recipe") ->
            onOpenCustomMealSheet(MealSlotType.DINNER)
          (lowerPrompt.contains("scan fridge") || lowerPrompt.contains("scan spice")) && stagedImage.isNullOrBlank() ->
            mutableUiState.update {
              it.copy(toastMessage = "Tap Camera or Photo below to scan your fridge or spice rack.")
            }
          else -> submitTurn(action.promptText, attachedImagePath = null)
        }
      }

      is AiChefAction.AttachImage -> attachAndPreprocessImage(action.sourceUriOrPath, action.displayLabel)

      AiChefAction.ClearAttachedImage -> {
        // Deterministically delete staged temp JPEG when user taps 'X' on the composer thumbnail.
        deleteTempImage(mutableUiState.value.attachedImagePath)
        mutableUiState.update { it.copy(attachedImagePath = null, attachedImageLabel = null) }
      }

      is AiChefAction.SelectDeviceProfile ->
        mutableUiState.update { it.copy(activeDeviceProfile = action.profile) }

      is AiChefAction.ImportModelUri -> importOnDeviceModel(action.sourceUriString, action.displayName)

      is AiChefAction.ApplyMealSwaps -> runGuarded {
        // Apply approved pantry-grounded meal swaps in 1 O(1) snapshot write with Undo capture.
        val current = mutableUiState.value.snapshot
        val next = aiChefCoordinator.applyConfirmedMealSwaps(
          snapshot = current,
          proposal = action.proposal,
          nowIso = nowIso(),
        )
        val summary = "Applied ${action.proposal.slotSwaps.size} meal swap(s) to Meal Plan."
        storagePort.saveSnapshotBatch(next)
        recordUndoSnapshot(current, summary, TopLevelDestination.PLAN)
        mutableUiState.update {
          it.copy(
            appliedSwapProposalIds = it.appliedSwapProposalIds + action.proposal.proposalId,
            toastMessage = summary,
          )
        }
      }

      is AiChefAction.QueueSuggestedIngredients -> runGuarded {
        // Merge suggested brain-booster items into catalog + grocery queue in 1 O(1) write.
        val current = mutableUiState.value.snapshot
        val next = aiChefCoordinator.queueSuggestedBrainBoosters(
          snapshot = current,
          proposal = action.proposal,
          nowIso = nowIso(),
        )
        val summary = "Added ${action.proposal.newIngredientSuggestions.size} item(s) to Grocery List."
        storagePort.saveSnapshotBatch(next)
        recordUndoSnapshot(current, summary, TopLevelDestination.GROCERIES)
        mutableUiState.update {
          it.copy(
            queuedIngredientProposalIds = it.queuedIngredientProposalIds + action.proposal.proposalId,
            toastMessage = summary,
          )
        }
      }

      is AiChefAction.ConfirmVisualRestock -> runGuarded {
        // User-confirmed visual scan restock: never runs automatically prior to explicit tap.
        val current = mutableUiState.value.snapshot
        val next = aiChefCoordinator.applyConfirmedVisualRestock(current, action.intakeResult, nowIso())
        val itemCount = action.intakeResult.groundedRestockDeltas.size +
          action.intakeResult.proposedNewCatalogItems.size
        val summary = "Stocked $itemCount scanned item(s) in Pantry."
        if (next != current) {
          storagePort.saveSnapshotBatch(next)
          recordUndoSnapshot(current, summary, TopLevelDestination.PANTRY)
        }
        val confirmedIds = current.aiTurnHistory
          .filter { it.visualIntakeResult == action.intakeResult }
          .map { it.turnId }
          .toSet()
        mutableUiState.update {
          it.copy(
            confirmedVisualTurnIds = it.confirmedVisualTurnIds + confirmedIds,
            toastMessage = summary,
          )
        }
      }

      is AiChefAction.CommitVisualIntakeSelection -> runGuarded {
        // Selective chip commit for matched catalog items and synthesized spices/ingredients.
        val current = mutableUiState.value.snapshot
        val next = aiChefCoordinator.applySelectiveVisualIntakeCommit(
          snapshot = current,
          intakeResult = action.intakeResult,
          selectedIngredientIds = action.selectedIngredientIds,
          selectedNewLabels = action.selectedNewLabels,
          destination = action.destination,
          nowIso = nowIso(),
        )
        if (next == current) {
          mutableUiState.update {
            it.copy(toastMessage = "Select at least one detected item to add.")
          }
          return@runGuarded
        }
        storagePort.saveSnapshotBatch(next)
        val newCatalogCount = (next.ingredients.size - current.ingredients.size).coerceAtLeast(0)
        val matchedCount = action.intakeResult.groundedRestockDeltas.count {
          it.ingredientId in action.selectedIngredientIds
        }
        val committedCount = when (action.destination) {
          IntakeCommitTarget.PANTRY -> (matchedCount + newCatalogCount).coerceAtLeast(1)
          IntakeCommitTarget.GROCERY -> (next.aiAddedGroceryIds - current.aiAddedGroceryIds).size.coerceAtLeast(1)
        }
        val adjustTarget = when (action.destination) {
          IntakeCommitTarget.PANTRY -> TopLevelDestination.PANTRY
          IntakeCommitTarget.GROCERY -> TopLevelDestination.GROCERIES
        }
        val summary = when (action.destination) {
          IntakeCommitTarget.PANTRY -> "Stocked $committedCount item(s) in Pantry."
          IntakeCommitTarget.GROCERY -> "Added $committedCount item(s) to Grocery List."
        }
        val confirmedIds = current.aiTurnHistory
          .filter { it.turnId == action.turnId || it.visualIntakeResult == action.intakeResult }
          .map { it.turnId }
          .toSet() + setOfNotNull(action.turnId.takeIf { it.isNotBlank() })
        recordUndoSnapshot(current, summary, adjustTarget)
        mutableUiState.update {
          it.copy(
            confirmedVisualTurnIds = it.confirmedVisualTurnIds + confirmedIds,
            toastMessage = summary,
          )
        }
      }

      AiChefAction.UndoLastAgentAction -> onUndoLastAgentAction()

      AiChefAction.DismissAgentActionBanner -> onDismissAgentActionBanner()

      is AiChefAction.StartVoiceInput -> {
        mutableUiState.update { it.copy(activeVoiceTarget = VoiceCaptureTarget.AI_CHEF) }
        voiceInputPort.startListening(action.languageTag)
      }

      AiChefAction.StopVoiceInput -> voiceInputPort.stopListening()

      AiChefAction.ResetConversation -> runGuarded {
        aiEdgePort.resetConversation()
        onDismissAgentActionBanner()
        val current = mutableUiState.value.snapshot
        storagePort.saveSnapshotBatch(current.copy(aiTurnHistory = emptyList(), updatedAtIso = nowIso()))
        mutableUiState.update {
          it.copy(
            appliedSwapProposalIds = emptySet(),
            queuedIngredientProposalIds = emptySet(),
            confirmedVisualTurnIds = emptySet(),
            toastMessage = "Reset SavorAI conversation.",
          )
        }
      }

      is AiChefAction.StartCookModeFromAi ->
        startCookMode(recipe = action.recipe, slotId = action.slotId, handsFreeVoiceActive = false)

      is AiChefAction.AddRecipeToPlanFromAi -> {
        // Respect EC-01 <= 25m Breakfast cap when selecting a default target slot for AI recipes.
        val state = mutableUiState.value
        val resolvedSlotType = action.slotType ?: action.recipe.allowedSlots.firstOrNull { cand ->
          cand != MealSlotType.BREAKFAST || isWeekdayBreakfastEligible(action.recipe)
        } ?: MealSlotType.DINNER
        val resolvedDay = action.dayIndex ?: state.activeDayIndex
        onSchedulePresetRecipe(action.recipe, resolvedSlotType, resolvedDay, false)
      }

      is AiChefAction.ToggleSaveRecipe -> onToggleSavedRecipe(action.recipeId)

      is AiChefAction.ApplyRecipeSubstitution -> runGuarded {
        // Rewrite BOM line and recompute per-serving USDA/FNRI kcal & macros deterministically.
        val current = mutableUiState.value.snapshot
        val reduced = reduceAiRecipeSubstitution(
          snapshot = current,
          recipeId = action.recipeId,
          fromIngredientId = action.fromIngredientId,
          toIngredientId = action.toIngredientId,
          nowIso = nowIso(),
        ) ?: return@runGuarded
        val summary = "Updated '${reduced.second.title}' (${reduced.second.caloriesPerServing} kcal/srv)."
        storagePort.saveSnapshotBatch(reduced.first)
        recordUndoSnapshot(current, summary, TopLevelDestination.RECIPES)
        mutableUiState.update {
          it.copy(toastMessage = summary)
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
    }
  }

  /** Submits a text or multimodal image turn through [AiChefCoordinator] and persists in `O(1)`. */
  fun submitTurn(promptText: String, attachedImagePath: String?) {
    val stateSnapshot = mutableUiState.value
    // Block overlapping AI turns while inference or photo EXIF preprocessing is already active.
    if (stateSnapshot.isAiGenerating || stateSnapshot.isPreprocessingImage) {
      mutableUiState.update {
        it.copy(
          toastMessage = when {
            stateSnapshot.isPreprocessingImage ->
              "Photo optimization is still finishing — try sending in a moment."
            else ->
              "SavorAI is already working on your request — watch the live spinner."
          },
        )
      }
      return
    }
    // Pass explicit per-turn image path rather than mutable @Volatile side-channel state.
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
        val outcome = aiChefCoordinator.submitTurn(
          snapshot = preTurnSnapshot,
          promptText = promptText,
          attachedImagePath = effectiveImagePath,
          attachedImageLabel = currentState.attachedImageLabel,
          deviceProfile = currentState.activeDeviceProfile,
          nowIso = nowIso(),
          activeDayIndex = currentState.activeDayIndex,
        )
        // Persist updated rolling 6-turn history (plus any deterministic text tool mutations).
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
        mutableUiState.update {
          it.copy(
            isAiGenerating = false,
            isGeneratingVisionTurn = false,
            attachedImagePath = null,
            attachedImageLabel = null,
            toastMessage = outcome.visionWarningMessage ?: outcome.toastMessage ?: it.toastMessage,
          )
        }
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (err: Throwable) {
        mutableUiState.update {
          it.copy(
            toastMessage = err.message ?: "Unable to complete SavorAI request.",
          )
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
        // Parse bilingual en-US / fr-FR Cook Mode commands or grounded Sous-Chef step Q&A.
        val session = state.activeCookModeSession ?: return
        val nextSession = CookModeEngine.applyVoiceTranscriptToCookSession(
          state = session,
          transcript = transcript,
          catalog = state.snapshot.ingredients,
          youngestStage = state.snapshot.householdProfile.youngestStage,
        )
        mutableUiState.update { it.copy(activeCookModeSession = nextSession) }
      }

      VoiceCaptureTarget.GROCERY_DICTATION -> {
        // Append spoken shopping item straight onto Smart Grocery List in 1 O(1) write.
        val clean = transcript.trim()
        if (clean.isNotEmpty()) {
          val slug = clean.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "dictated" }
          onAddCustomGroceryItem(CustomGroceryItem(customId = "voice_$slug", displayName = clean))
        }
      }

      VoiceCaptureTarget.AI_CHEF -> Unit
    }
  }

  private fun attachAndPreprocessImage(rawUriOrPath: String, displayLabel: String) {
    // Guard against overlapping camera/gallery preprocessing calls while resizing a photo.
    if (mutableUiState.value.isPreprocessingImage) {
      mutableUiState.update {
        it.copy(toastMessage = "Still optimizing your previous photo — watch the spinner below.")
      }
      return
    }
    // Clean up any previously staged photo before preprocessing the newly selected image.
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
          it.copy(toastMessage = err.message ?: "Unable to prepare kitchen photo.")
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

  private fun importOnDeviceModel(sourceUriString: String, displayName: String?) {
    // Reject duplicate model import requests while discovery, SAF copy, or GPU shader init runs.
    if (
      aiEdgePort.runtimeState.value.isBackgroundBusy ||
      mutableUiState.value.aiRuntimeState.isBackgroundBusy ||
      !modelImportDispatched.compareAndSet(false, true)
    ) {
      mutableUiState.update {
        it.copy(
          toastMessage = "Model setup is already running in the background — watch the live progress spinner.",
        )
      }
      return
    }
    val cleanDisplayName = displayName?.trim()?.takeIf { it.isNotEmpty() }
    mutableUiState.update {
      it.copy(
        toastMessage = "Importing ${cleanDisplayName ?: "Gemma model"} — see live progress spinner...",
      )
    }
    // Stream SAF document via <name>.part temp file + atomic rename inside LiteRtMultimodalEngine.
    scope.launch {
      try {
        val result = aiEdgePort.importAndLoadModel(sourceUriString, displayName)
        when (result) {
          is AiRuntimeState.Ready -> mutableUiState.update {
            it.copy(
              toastMessage = "Loaded ${result.capabilities.modelName} (${result.capabilities.backendLabel}) — ready!",
            )
          }
          is AiRuntimeState.Error -> mutableUiState.update { it.copy(toastMessage = result.message) }
          else -> Unit
        }
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (err: Throwable) {
        mutableUiState.update {
          it.copy(toastMessage = err.message ?: "Unable to import on-device model.")
        }
      } finally {
        modelImportDispatched.set(false)
      }
    }
  }
}
