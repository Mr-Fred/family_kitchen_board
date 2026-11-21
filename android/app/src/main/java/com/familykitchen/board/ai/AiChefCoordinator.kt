package com.familykitchen.board.ai

import com.familykitchen.board.domain.AiChatRole
import com.familykitchen.board.domain.AiChatTurn
import com.familykitchen.board.domain.AiChefAction
import com.familykitchen.board.domain.AiMenuAdvisorEngine
import com.familykitchen.board.domain.AiMenuOptimizationProposal
import com.familykitchen.board.domain.AiToolCallResult
import com.familykitchen.board.domain.DEFAULT_TIMESTAMP_ISO
import com.familykitchen.board.domain.EdgeDeviceProfile
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.IngredientTier
import com.familykitchen.board.domain.IntakeCommitTarget
import com.familykitchen.board.domain.KitchenBoardException
import com.familykitchen.board.domain.MealSlotStatus
import com.familykitchen.board.domain.ModelNotReadyException
import com.familykitchen.board.domain.PantryLedgerEntry
import com.familykitchen.board.domain.RestockDelta
import com.familykitchen.board.domain.StapleStatus
import com.familykitchen.board.domain.StorageLocation
import com.familykitchen.board.domain.ValidationException
import com.familykitchen.board.domain.VisionNotSupportedException
import com.familykitchen.board.domain.VisualIntakeResult
import com.familykitchen.board.domain.batchRestockPantry
import com.familykitchen.board.domain.synthesizeCatalogItemFromLabel
import com.familykitchen.board.domain.tryExecuteDeterministicToolIntent
import com.familykitchen.board.ports.AiEdgePort
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ports.KitchenSnapshot
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

// Pure-Kotlin AI Chef turn orchestrator decoupling ViewModels and JUnit tests from Android APIs.
// Enforces zero fake grocery prompts, strict Vision Gate surfacing, and guaranteed temp-file cleanup.

/**
 * Resolution pathway taken by [AiChefCoordinator.submitTurn] for telemetry, UI banners, and
 * deterministic JUnit assertions.
 */
enum class AiTurnResolutionStatus {
  /** Explicit text-only command matched a deterministic `< 5ms` on-device kitchen tool. */
  DETERMINISTIC_TOOL_EXECUTED,

  /** On-device LLM text output was validated and grounded against the household catalog. */
  ON_DEVICE_TEXT_GROUNDED,

  /** Text-only prompt resolved via deterministic offline `generatePantryGroundedMenuProposal`. */
  OFFLINE_DETERMINISTIC_PROPOSAL,

  /** Attached photo was inspected via multimodal vision into a confirmation-gated intake card. */
  MULTIMODAL_VISION_GROUNDED,

  /** Attached photo was blocked non-silently by [VisionNotSupportedException] or [ModelNotReadyException]. */
  VISION_GATE_BLOCKED,

  /** Attached photo failed multimodal inference or schema validation without mutating pantry. */
  VISION_INFERENCE_ERROR,
}

/**
 * Complete immutable result of handling a single user text or multimodal image turn in
 * [AiChefCoordinator].
 *
 * @property status Exact resolution pathway taken by the coordinator.
 * @property userTurn Recorded user [AiChatTurn] appended to rolling history.
 * @property assistantTurn Recorded assistant [AiChatTurn] appended to rolling history.
 * @property updatedTurnHistory Bounded rolling conversation history including this exchange.
 * @property updatedSnapshot Next [KitchenSnapshot] ready for a single `O(1)` batch write.
 * @property visionWarningMessage Non-null explicit warning banner text when a photo scan is gated/failed.
 * @property blockingException Typed [KitchenBoardException] surfaced when vision is unsupported/unready.
 * @property toastMessage Optional scaffold confirmation banner message (e.g., after tool execution).
 * @property tempImageCleanedUp `true` when `finally` invoked temp-image cleanup for an image turn.
 */
data class AiChefTurnOutcome(
  val status: AiTurnResolutionStatus,
  val userTurn: AiChatTurn,
  val assistantTurn: AiChatTurn,
  val updatedTurnHistory: List<AiChatTurn>,
  val updatedSnapshot: KitchenSnapshot,
  val visionWarningMessage: String? = null,
  val blockingException: KitchenBoardException? = null,
  val toastMessage: String? = null,
  val tempImageCleanedUp: Boolean = false,
)

/**
 * Pure-testable AI Chef turn coordinator bridging [AiEdgePort] with the pure functional domain
 * grounding and menu-optimization engines in [AiMenuAdvisorEngine].
 *
 * Contains **zero** `android.*` imports so `VisionGateAndChefCoordinatorTest` can verify:
 * 1. Authentic `Content.ImageFile` forwarding on vision-capable models,
 * 2. Non-silent [VisionNotSupportedException] / [ModelNotReadyException] warning surfacing with
 *    zero pantry mutation and zero silent text-only fallback on image turns,
 * 3. Guaranteed temporary preprocessed file cleanup inside `finally` across all outcomes.
 *
 * @param aiEdgePort Hexagonal on-device text and multimodal inference port.
 * @param onCleanupTempImage Deterministic callback invoked in `finally` to delete temp image files.
 * @param maxTurnHistorySize Maximum messages retained in rolling chat history (`12` = 6 exchanges).
 */
class AiChefCoordinator(
  private val aiEdgePort: AiEdgePort,
  private val onCleanupTempImage: (String) -> Unit = ::defaultDeleteTempFile,
  private val maxTurnHistorySize: Int = DEFAULT_MAX_TURN_HISTORY,
) {

  // Monotonic sequence generates collision-free turn IDs even under deterministic test timestamps.
  private val turnSequence = AtomicLong(0L)

  /**
   * Convenience adapter routing an [AiChefAction.SubmitPrompt] directly to [submitTurn].
   *
   * @param snapshot Current household state snapshot.
   * @param action User prompt submission action carrying optional `attachedImagePath`.
   * @param attachedImageLabel Optional human-readable chip label for an attached photo.
   * @param deviceProfile Active Samsung Galaxy S22 / S22 Ultra / Gemma E2B hardware profile.
   * @param nowIso Deterministic ISO-8601 timestamp stamped onto generated chat turns.
   * @return Complete [AiChefTurnOutcome] ready for state emission and `O(1)` persistence.
   */
  suspend fun handleSubmitAction(
    snapshot: KitchenSnapshot,
    action: AiChefAction.SubmitPrompt,
    attachedImageLabel: String? = null,
    deviceProfile: EdgeDeviceProfile = EdgeDeviceProfile.GALAXY_S22_ULTRA_GEMMA_2B_INT4,
    nowIso: String = DEFAULT_TIMESTAMP_ISO,
  ): AiChefTurnOutcome =
    submitTurn(
      snapshot = snapshot,
      promptText = action.promptText,
      attachedImagePath = action.attachedImagePath,
      attachedImageLabel = attachedImageLabel,
      deviceProfile = deviceProfile,
      nowIso = nowIso,
    )

  /**
   * Coordinates either an **image-attached multimodal turn** or a **text-only turn**:
   * - **Image-attached turns**: Never executes regex tool intents or fake grocery prompts; builds
   *   a neutral prompt via [AiMenuAdvisorEngine.buildVisualIntakePrompt], invokes
   *   [AiEdgePort.analyzeImageWithPrompt], grounds output into a confirmation-gated
   *   [VisualIntakeResult] (or surfaces [VisionNotSupportedException] / [ModelNotReadyException]
   *   explicitly without touching pantry state), and deletes [attachedImagePath] in `finally`.
   * - **Text-only turns**: Dispatches explicit NL commands via
   *   [AiMenuAdvisorEngine.tryExecuteDeterministicToolIntent], queries [AiEdgePort.generateText]
   *   when [AiRuntimeState.Ready] (grounding via [AiMenuAdvisorEngine.parseAndGroundAiResponse]),
   *   and falls back cleanly to [AiMenuAdvisorEngine.generatePantryGroundedMenuProposal].
   *
   * @param snapshot Current immutable household state snapshot.
   * @param promptText User-entered prompt or optional photo note.
   * @param attachedImagePath Optional preprocessed JPEG path for multimodal vision turns.
   * @param attachedImageLabel Optional display badge name for the attached image.
   * @param deviceProfile Active hardware profile guiding token/delegate system prompt hints.
   * @param nowIso Deterministic ISO-8601 timestamp.
   * @param activeDayIndex Default weekday (`0..6`) for `"tonight"` / `"today"` swap commands.
   * @return Immutable [AiChefTurnOutcome].
   */
  suspend fun submitTurn(
    snapshot: KitchenSnapshot,
    promptText: String,
    attachedImagePath: String? = null,
    attachedImageLabel: String? = null,
    deviceProfile: EdgeDeviceProfile = EdgeDeviceProfile.GALAXY_S22_ULTRA_GEMMA_2B_INT4,
    nowIso: String = DEFAULT_TIMESTAMP_ISO,
    activeDayIndex: Int = 0,
  ): AiChefTurnOutcome {
    // Route strictly by presence of an explicit per-turn image path (zero @Volatile side-channels).
    val cleanImagePath = attachedImagePath?.trim()?.takeIf { it.isNotEmpty() }
    return when {
      cleanImagePath != null ->
        executeImageAttachedTurn(snapshot, promptText, cleanImagePath, attachedImageLabel, nowIso)
      else -> executeTextOnlyTurn(snapshot, promptText, deviceProfile, nowIso, activeDayIndex)
    }
  }

  /**
   * Pure single-pass reducer applying all user-confirmed [VisualIntakeResult.groundedRestockDeltas]
   * and [VisualIntakeResult.proposedNewCatalogItems] into [snapshot]'s pantry ledger in one batch.
   *
   * @param snapshot Current household snapshot prior to confirmation.
   * @param intakeResult Grounded visual scan result approved by the user.
   * @param nowIso Deterministic ISO-8601 commit timestamp.
   * @return Updated [KitchenSnapshot] with restocked pantry quantities and cleared grocery queue IDs.
   */
  fun applyConfirmedVisualRestock(
    snapshot: KitchenSnapshot,
    intakeResult: VisualIntakeResult,
    nowIso: String = DEFAULT_TIMESTAMP_ISO,
  ): KitchenSnapshot {
    // Commit every matched catalog item and synthesized new culinary item to PANTRY in 1 pass.
    val allIds = (
      intakeResult.groundedRestockDeltas.map { it.ingredientId } +
        intakeResult.proposedNewCatalogItems.map { it.ingredientId }
      ).toSet()
    return applySelectiveVisualIntakeCommit(
      snapshot = snapshot,
      intakeResult = intakeResult,
      selectedIngredientIds = allIds,
      selectedNewLabels = emptyList(),
      destination = IntakeCommitTarget.PANTRY,
      nowIso = nowIso,
    )
  }

  /**
   * Pure single-pass reducer committing user-selected matched IDs and newly discovered food/spice
   * labels from [intakeResult] into either [IntakeCommitTarget.PANTRY] or
   * [IntakeCommitTarget.GROCERY] in one `O(1)` snapshot update.
   *
   * @param snapshot Current household snapshot prior to confirmation.
   * @param intakeResult Grounded visual scan result containing matched deltas and proposed items.
   * @param selectedIngredientIds Selected catalog or proposed `ingredientId`s.
   * @param selectedNewLabels Selected raw or display labels for newly discovered items.
   * @param destination Target surface (`PANTRY` stock vs `GROCERY` queue).
   * @param nowIso Deterministic ISO-8601 commit timestamp.
   * @return Updated [KitchenSnapshot] ready for a single `saveSnapshotBatch` write.
   */
  fun applySelectiveVisualIntakeCommit(
    snapshot: KitchenSnapshot,
    intakeResult: VisualIntakeResult,
    selectedIngredientIds: Set<String>,
    selectedNewLabels: List<String>,
    destination: IntakeCommitTarget = IntakeCommitTarget.PANTRY,
    nowIso: String = DEFAULT_TIMESTAMP_ISO,
  ): KitchenSnapshot {
    val chosenGroundedDeltas = intakeResult.groundedRestockDeltas.filter { it.ingredientId in selectedIngredientIds }
    val selectedNewItemsById = linkedMapOf<String, IngredientCatalogItem>()
    val extraExistingDeltas = mutableListOf<RestockDelta>()

    // Collect selected proposed items matched either by ingredientId or display/raw label.
    for (proposed in intakeResult.proposedNewCatalogItems) {
      val matchesLabel = selectedNewLabels.any { lbl ->
        lbl.equals(proposed.displayName, ignoreCase = true) ||
          synthesizeCatalogItemFromLabel(lbl)?.ingredientId == proposed.ingredientId
      }
      if (proposed.ingredientId in selectedIngredientIds || matchesLabel) {
        selectedNewItemsById[proposed.ingredientId] = proposed
      }
    }

    // Synthesize catalog items on demand for any extra raw labels selected on the intake card.
    for (rawLabel in selectedNewLabels) {
      val synthesized = synthesizeCatalogItemFromLabel(rawLabel) ?: continue
      val existingCatalog = snapshot.catalogById[synthesized.ingredientId]
      when {
        existingCatalog != null -> {
          if (chosenGroundedDeltas.none { it.ingredientId == existingCatalog.ingredientId }) {
            val status = if (existingCatalog.tier == IngredientTier.TIER_2_STAPLE) StapleStatus.PLENTY else null
            extraExistingDeltas.add(RestockDelta(existingCatalog.ingredientId, existingCatalog.defaultPackageQuantity, status))
          }
        }
        else -> selectedNewItemsById.putIfAbsent(synthesized.ingredientId, synthesized)
      }
    }

    // Avoid redundant O(1) snapshot writes when zero items were selected by the user.
    val baseDeltas = chosenGroundedDeltas + extraExistingDeltas
    if (baseDeltas.isEmpty() && selectedNewItemsById.isEmpty()) return snapshot

    // Upsert new catalog records first so Pantry and Grocery reducers resolve tier and unit metadata.
    val mergedCatalog = upsertCatalogItems(snapshot.ingredients, selectedNewItemsById.values.toList())
    return when (destination) {
      IntakeCommitTarget.PANTRY -> {
        // Stock Tier-2 spices as PLENTY in PANTRY and add 1 default package for Tier-1 foods.
        val baseIds = baseDeltas.map { it.ingredientId }.toSet()
        val newDeltas = selectedNewItemsById.values
          .filter { it.ingredientId !in baseIds }
          .map { item ->
            val status = if (item.tier == IngredientTier.TIER_2_STAPLE) StapleStatus.PLENTY else null
            RestockDelta(item.ingredientId, item.defaultPackageQuantity, status)
          }
        val allDeltas = baseDeltas + newDeltas
        val updatedPantry = batchRestockPantry(snapshot.pantryEntries, mergedCatalog, allDeltas, nowIso)
        val restockedIds = allDeltas.map { it.ingredientId }.toSet()
        snapshot.copy(
          ingredients = mergedCatalog,
          pantryEntries = updatedPantry,
          aiAddedGroceryIds = snapshot.aiAddedGroceryIds - restockedIds,
          updatedAtIso = nowIso,
        )
      }
      IntakeCommitTarget.GROCERY -> {
        // Queue selected IDs onto Smart Grocery List without mutating existing pantry balances.
        val queuedIds = baseDeltas.map { it.ingredientId }.toSet() + selectedNewItemsById.keys
        snapshot.copy(
          ingredients = mergedCatalog,
          aiAddedGroceryIds = snapshot.aiAddedGroceryIds + queuedIds,
          updatedAtIso = nowIso,
        )
      }
    }
  }

  /**
   * Pure single-pass reducer applying all meal swaps in [proposal] to [snapshot] in one batch.
   *
   * @param snapshot Current household snapshot.
   * @param proposal Grounded AI menu optimization proposal approved by the user.
   * @param nowIso Deterministic ISO-8601 commit timestamp.
   * @return Updated [KitchenSnapshot] with swapped weekly board slots and enriched recipes.
   */
  fun applyConfirmedMealSwaps(
    snapshot: KitchenSnapshot,
    proposal: AiMenuOptimizationProposal,
    nowIso: String = DEFAULT_TIMESTAMP_ISO,
  ): KitchenSnapshot {
    // Apply only approved slot swaps while leaving grocery discovery items separate for user choice.
    val applied = AiMenuAdvisorEngine.applyAiMenuProposalBatch(
      board = snapshot.weeklyBoard,
      recipes = snapshot.recipes,
      catalog = snapshot.ingredients,
      proposal = proposal,
      applySlotSwaps = true,
      includeNewIngredients = false,
    )
    return snapshot.copy(
      weeklyBoard = applied.updatedBoard,
      recipes = applied.updatedRecipes,
      updatedAtIso = nowIso,
    )
  }

  /**
   * Pure single-pass reducer merging suggested brain-booster catalog items from [proposal] and
   * queueing their IDs onto the Smart Grocery List in one atomic snapshot update.
   *
   * @param snapshot Current household snapshot.
   * @param proposal Grounded AI menu optimization proposal whose ingredients were selected.
   * @param nowIso Deterministic ISO-8601 commit timestamp.
   * @return Updated [KitchenSnapshot] with merged catalog items and queued grocery IDs.
   */
  fun queueSuggestedBrainBoosters(
    snapshot: KitchenSnapshot,
    proposal: AiMenuOptimizationProposal,
    nowIso: String = DEFAULT_TIMESTAMP_ISO,
  ): KitchenSnapshot {
    // Merge newly discovered catalog items and queue their IDs onto Smart Grocery in 1 pass.
    val applied = AiMenuAdvisorEngine.applyAiMenuProposalBatch(
      board = snapshot.weeklyBoard,
      recipes = snapshot.recipes,
      catalog = snapshot.ingredients,
      proposal = proposal,
      applySlotSwaps = false,
      includeNewIngredients = true,
    )
    return snapshot.copy(
      ingredients = applied.updatedCatalog,
      aiAddedGroceryIds = snapshot.aiAddedGroceryIds + applied.queuedGroceryIngredientIds,
      updatedAtIso = nowIso,
    )
  }

  // Image turn pipeline: zero regex tools, zero fake grocery text, strict non-silent Vision Gate.
  private suspend fun executeImageAttachedTurn(
    snapshot: KitchenSnapshot,
    userNote: String,
    preprocessedImagePath: String,
    attachedImageLabel: String?,
    nowIso: String,
  ): AiChefTurnOutcome {
    val resolvedLabel = attachedImageLabel?.trim()?.takeIf { it.isNotEmpty() }
      ?: File(preprocessedImagePath).name.ifBlank { DEFAULT_IMAGE_LABEL }
    val userDisplayMessage = userNote.trim().ifEmpty { "Inspect attached kitchen photo" }
    val existingIds = snapshot.aiTurnHistory.map { it.turnId }.toSet()
    val userTurn = AiChatTurn(
      turnId = nextTurnId("user_img", existingIds),
      role = AiChatRole.USER,
      messageText = userDisplayMessage,
      createdAtIso = nowIso,
      attachedImageLabel = resolvedLabel,
    )

    try {
      // Synthesize a 100% neutral visual prompt referencing valid catalog IDs (0 fake food strings).
      val visualPrompt = AiMenuAdvisorEngine.buildVisualIntakePrompt(
        userNote = userNote,
        catalog = snapshot.ingredients,
        youngestStage = snapshot.householdProfile.youngestStage,
      )
      // Dispatch real preprocessed JPEG path to AiEdgePort (enforces VisionNotSupportedException).
      val rawVisionOutput = aiEdgePort.analyzeImageWithPrompt(preprocessedImagePath, visualPrompt)
      return buildGroundedVisionOutcome(snapshot, userTurn, userNote, rawVisionOutput, nowIso)
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (visionGateError: VisionNotSupportedException) {
      // Surface explicit non-silent warning without mutating pantry or running text-only fallback.
      return buildBlockedVisionOutcome(
        snapshot, userTurn, AiTurnResolutionStatus.VISION_GATE_BLOCKED,
        visionGateError.message, visionGateError, nowIso,
      )
    } catch (notReadyError: ModelNotReadyException) {
      // Model still loading or missing: inform user explicitly rather than fabricating items.
      return buildBlockedVisionOutcome(
        snapshot, userTurn, AiTurnResolutionStatus.VISION_GATE_BLOCKED,
        notReadyError.message, notReadyError, nowIso,
      )
    } catch (validationError: ValidationException) {
      // Reject ungrounded or malformed vision JSON without touching pantry stock.
      val msg = "Photo scan output did not match strict household schema (${validationError.message}). " +
        "No pantry items were changed—please try a clearer photo."
      return buildBlockedVisionOutcome(
        snapshot, userTurn, AiTurnResolutionStatus.VISION_INFERENCE_ERROR,
        msg, validationError, nowIso,
      )
    } catch (unexpected: Throwable) {
      // Catch unexpected native JNI Errors and Exceptions cleanly so UI and pantry stay intact.
      val detail = unexpected.message?.trim()?.takeIf { it.isNotEmpty() } ?: "multimodal runtime error"
      val msg = "Unable to complete on-device photo scan ($detail). Pantry state remains unchanged."
      return buildBlockedVisionOutcome(
        snapshot, userTurn, AiTurnResolutionStatus.VISION_INFERENCE_ERROR, msg, null, nowIso,
      )
    } finally {
      // Guarantee temporary preprocessed capture deletion across success, gate error, or cancel.
      runCatching { onCleanupTempImage(preprocessedImagePath) }
    }
  }

  // Parse VisualIntakeResult first; if the vision model returned a grounded swap schema, accept that.
  private fun buildGroundedVisionOutcome(
    snapshot: KitchenSnapshot,
    userTurn: AiChatTurn,
    userNote: String,
    rawVisionOutput: String,
    nowIso: String,
  ): AiChefTurnOutcome {
    val intakeResult = try {
      AiMenuAdvisorEngine.parseVisualIntakeJson(rawVisionOutput, snapshot.ingredients)
    } catch (primaryErr: ValidationException) {
      // Secondary strict parser in case a vision prompt asked directly for JSON slot swaps.
      val groundedProposal = try {
        AiMenuAdvisorEngine.parseAndGroundAiResponse(
          rawModelOutput = rawVisionOutput,
          userPrompt = userNote,
          board = snapshot.weeklyBoard,
          recipes = snapshot.recipes,
          catalog = snapshot.ingredients,
          pantry = snapshot.pantryEntries,
          householdProfile = snapshot.householdProfile,
        )
      } catch (_: ValidationException) {
        throw primaryErr
      }
      val reservedIds = snapshot.aiTurnHistory.map { it.turnId }.toSet() + userTurn.turnId
      val assistantTurn = AiChatTurn(
        turnId = nextTurnId("asst_vis_prop", reservedIds),
        role = AiChatRole.ASSISTANT,
        messageText = groundedProposal.assistantSummary,
        createdAtIso = nowIso,
        proposal = groundedProposal,
      )
      val history = appendRollingTurns(snapshot.aiTurnHistory, userTurn, assistantTurn)
      return AiChefTurnOutcome(
        status = AiTurnResolutionStatus.MULTIMODAL_VISION_GROUNDED,
        userTurn = userTurn,
        assistantTurn = assistantTurn,
        updatedTurnHistory = history,
        updatedSnapshot = snapshot.copy(aiTurnHistory = history, updatedAtIso = nowIso),
        tempImageCleanedUp = true,
      )
    }

    val assistantMessage = formatVisualIntakeMessage(intakeResult)
    val reservedIds = snapshot.aiTurnHistory.map { it.turnId }.toSet() + userTurn.turnId
    val assistantTurn = AiChatTurn(
      turnId = nextTurnId("asst_vis", reservedIds),
      role = AiChatRole.ASSISTANT,
      messageText = assistantMessage,
      createdAtIso = nowIso,
      visualIntakeResult = intakeResult,
    )
    val history = appendRollingTurns(snapshot.aiTurnHistory, userTurn, assistantTurn)
    // Confirmation gate: never auto-commit groundedRestockDeltas until ConfirmVisualRestock fires.
    return AiChefTurnOutcome(
      status = AiTurnResolutionStatus.MULTIMODAL_VISION_GROUNDED,
      userTurn = userTurn,
      assistantTurn = assistantTurn,
      updatedTurnHistory = history,
      updatedSnapshot = snapshot.copy(aiTurnHistory = history, updatedAtIso = nowIso),
      tempImageCleanedUp = true,
    )
  }

  // Construct a non-mutating warning turn preserving pantryEntries, weeklyBoard, and grocery queue.
  private fun buildBlockedVisionOutcome(
    snapshot: KitchenSnapshot,
    userTurn: AiChatTurn,
    status: AiTurnResolutionStatus,
    warningMessage: String,
    blockingException: KitchenBoardException?,
    nowIso: String,
  ): AiChefTurnOutcome {
    val reservedIds = snapshot.aiTurnHistory.map { it.turnId }.toSet() + userTurn.turnId
    val assistantTurn = AiChatTurn(
      nextTurnId("asst_gate", reservedIds),
      AiChatRole.ASSISTANT,
      warningMessage,
      nowIso,
    )
    val history = appendRollingTurns(snapshot.aiTurnHistory, userTurn, assistantTurn)
    return AiChefTurnOutcome(
      status = status,
      userTurn = userTurn,
      assistantTurn = assistantTurn,
      updatedTurnHistory = history,
      updatedSnapshot = snapshot.copy(aiTurnHistory = history, updatedAtIso = nowIso),
      visionWarningMessage = warningMessage,
      blockingException = blockingException,
      tempImageCleanedUp = true,
    )
  }

  // Text-only pipeline: deterministic NL tool -> on-device Ready LLM -> pantry-grounded fallback.
  private suspend fun executeTextOnlyTurn(
    snapshot: KitchenSnapshot,
    promptText: String,
    deviceProfile: EdgeDeviceProfile,
    nowIso: String,
    activeDayIndex: Int = 0,
  ): AiChefTurnOutcome {
    val cleanPrompt = promptText.trim()
    if (cleanPrompt.isEmpty()) {
      throw ValidationException(
        message = "Enter a message or attach a kitchen photo before sending.",
        issues = listOf("promptText: blank"),
      )
    }

    val existingIds = snapshot.aiTurnHistory.map { it.turnId }.toSet()
    val userTurn = AiChatTurn(
      turnId = nextTurnId("user_txt", existingIds),
      role = AiChatRole.USER,
      messageText = cleanPrompt,
      createdAtIso = nowIso,
    )
    val reservedForAssistant = existingIds + userTurn.turnId

    // Step 1: check explicit natural-language batch commands (< 5ms deterministic tools).
    val toolResult = tryExecuteDeterministicToolIntent(
      userPrompt = cleanPrompt,
      hasAttachedImage = false,
      catalog = snapshot.ingredients,
      recipes = snapshot.recipes,
      board = snapshot.weeklyBoard,
      pantry = snapshot.pantryEntries,
      householdAllergens = snapshot.householdProfile.householdAllergens,
      activeDayIndex = activeDayIndex,
    )
    if (toolResult != null) {
      val assistantTurn = AiChatTurn(
        turnId = nextTurnId("asst_tool", reservedForAssistant),
        role = AiChatRole.ASSISTANT,
        messageText = toolResult.summaryMessage,
        createdAtIso = nowIso,
        toolCallResult = toolResult,
      )
      val history = appendRollingTurns(snapshot.aiTurnHistory, userTurn, assistantTurn)
      val mutatedSnapshot = applyToolResultToSnapshot(snapshot, toolResult, history, nowIso)
      return AiChefTurnOutcome(
        status = AiTurnResolutionStatus.DETERMINISTIC_TOOL_EXECUTED,
        userTurn = userTurn,
        assistantTurn = assistantTurn,
        updatedTurnHistory = history,
        updatedSnapshot = mutatedSnapshot,
        toastMessage = toolResult.toastMessage,
      )
    }

    // Step 2: when on-device runtime is Ready, run single-pass grounded LLM inference.
    if (aiEdgePort.runtimeState.value is AiRuntimeState.Ready) {
      val systemPrompt = AiMenuAdvisorEngine.buildPantryAwareSystemPrompt(
        userPrompt = cleanPrompt,
        board = snapshot.weeklyBoard,
        recipes = snapshot.recipes,
        catalog = snapshot.ingredients,
        pantry = snapshot.pantryEntries,
        householdAllergens = snapshot.householdProfile.householdAllergens,
        householdProfile = snapshot.householdProfile,
        deviceProfile = deviceProfile,
      )
      val llmGroundedProposal = try {
        val rawReply = aiEdgePort.generateText(systemPrompt)
        AiMenuAdvisorEngine.parseAndGroundAiResponse(
          rawModelOutput = rawReply,
          userPrompt = cleanPrompt,
          board = snapshot.weeklyBoard,
          recipes = snapshot.recipes,
          catalog = snapshot.ingredients,
          pantry = snapshot.pantryEntries,
          householdProfile = snapshot.householdProfile,
        )
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (_: Throwable) {
        // Text-only turns safely degrade to deterministic pantry advisor on JNI Error or bad JSON.
        null
      }

      if (llmGroundedProposal != null) {
        val assistantTurn = AiChatTurn(
          turnId = nextTurnId("asst_llm", reservedForAssistant),
          role = AiChatRole.ASSISTANT,
          messageText = llmGroundedProposal.assistantSummary,
          createdAtIso = nowIso,
          proposal = llmGroundedProposal,
        )
        val history = appendRollingTurns(snapshot.aiTurnHistory, userTurn, assistantTurn)
        return AiChefTurnOutcome(
          status = AiTurnResolutionStatus.ON_DEVICE_TEXT_GROUNDED,
          userTurn = userTurn,
          assistantTurn = assistantTurn,
          updatedTurnHistory = history,
          updatedSnapshot = snapshot.copy(aiTurnHistory = history, updatedAtIso = nowIso),
        )
      }
    }

    // Step 3: 100% offline deterministic pantry-grounded menu & brain-booster advisor fallback.
    val fallbackProposal = AiMenuAdvisorEngine.generatePantryGroundedMenuProposal(
      userPrompt = cleanPrompt,
      board = snapshot.weeklyBoard,
      recipes = snapshot.recipes,
      catalog = snapshot.ingredients,
      pantry = snapshot.pantryEntries,
      householdAllergens = snapshot.householdProfile.householdAllergens,
      householdProfile = snapshot.householdProfile,
    )
    val assistantTurn = AiChatTurn(
      turnId = nextTurnId("asst_det", reservedForAssistant),
      role = AiChatRole.ASSISTANT,
      messageText = fallbackProposal.assistantSummary,
      createdAtIso = nowIso,
      proposal = fallbackProposal,
    )
    val history = appendRollingTurns(snapshot.aiTurnHistory, userTurn, assistantTurn)
    return AiChefTurnOutcome(
      status = AiTurnResolutionStatus.OFFLINE_DETERMINISTIC_PROPOSAL,
      userTurn = userTurn,
      assistantTurn = assistantTurn,
      updatedTurnHistory = history,
      updatedSnapshot = snapshot.copy(aiTurnHistory = history, updatedAtIso = nowIso),
    )
  }

  // Apply explicit text-only tool results in one pure pass so persistence stays O(1).
  private fun applyToolResultToSnapshot(
    current: KitchenSnapshot,
    toolResult: AiToolCallResult,
    updatedHistory: List<AiChatTurn>,
    nowIso: String,
  ): KitchenSnapshot =
    when (toolResult.toolName) {
      "restock_pantry_batch" -> {
        // Upsert any newly synthesized spices/ingredients before merging restocked deltas.
        val mergedCatalog = upsertCatalogItems(current.ingredients, toolResult.synthesizedCatalogItems)
        val restocked = batchRestockPantry(
          pantry = current.pantryEntries,
          catalog = mergedCatalog,
          deltas = toolResult.restockDeltas,
          nowIso = nowIso,
        )
        val restockedIds = toolResult.restockDeltas.map { it.ingredientId }.toSet()
        current.copy(
          ingredients = mergedCatalog,
          pantryEntries = restocked,
          aiAddedGroceryIds = current.aiAddedGroceryIds - restockedIds,
          aiTurnHistory = updatedHistory,
          updatedAtIso = nowIso,
        )
      }
      "deplete_pantry_items" -> {
        // Mark matched Tier-1 items (0.0 / isOutOfStock = true) and Tier-2 staples (StapleStatus.OUT).
        val outIds = toolResult.outOfStockIngredientIds.toSet()
        val catalogById = current.catalogById
        val seenIds = mutableSetOf<String>()
        val updatedEntries = current.pantryEntries.map { entry ->
          seenIds.add(entry.ingredientId)
          when {
            entry.ingredientId !in outIds -> entry
            entry.tier == IngredientTier.TIER_2_STAPLE ->
              entry.copy(stapleStatus = StapleStatus.OUT, isOutOfStock = true, updatedAtIso = nowIso)
            else ->
              entry.copy(currentQuantity = 0.0, stapleStatus = StapleStatus.OUT, isOutOfStock = true, updatedAtIso = nowIso)
          }
        }
        val missingEntries = outIds.filter { it !in seenIds }.map { missingId ->
          val item = catalogById[missingId]
          val tier = item?.tier ?: IngredientTier.TIER_1_QUANTITATIVE
          PantryLedgerEntry(
            ingredientId = missingId,
            tier = tier,
            currentQuantity = 0.0,
            stapleStatus = StapleStatus.OUT,
            isOutOfStock = true,
            updatedAtIso = nowIso,
            storageLocation = item?.defaultStorageLocation ?: StorageLocation.PANTRY,
          )
        }
        current.copy(
          pantryEntries = updatedEntries + missingEntries,
          aiTurnHistory = updatedHistory,
          updatedAtIso = nowIso,
        )
      }
      "queue_grocery_items" -> {
        // Upsert synthesized catalog items and append requested IDs onto Smart Grocery List.
        val mergedCatalog = upsertCatalogItems(current.ingredients, toolResult.synthesizedCatalogItems)
        current.copy(
          ingredients = mergedCatalog,
          aiAddedGroceryIds = current.aiAddedGroceryIds + toolResult.queuedGroceryIngredientIds,
          aiTurnHistory = updatedHistory,
          updatedAtIso = nowIso,
        )
      }
      "activate_life_event" -> {
        // Upsert life event by eventId and rescale PLANNED slots to effectiveTargetServings.
        val event = toolResult.activatedLifeEvent
        val updatedProfile = when (event) {
          null -> current.householdProfile
          else -> {
            val mergedEvents = current.householdProfile.activeLifeEvents
              .filterNot { it.eventId == event.eventId } + event
            current.householdProfile.copy(activeLifeEvents = mergedEvents)
          }
        }
        val scaledSlots = current.weeklyBoard.slots.map { slot ->
          when (slot.status) {
            MealSlotStatus.PLANNED -> slot.copy(targetServings = updatedProfile.effectiveTargetServings)
            else -> slot
          }
        }
        current.copy(
          householdProfile = updatedProfile,
          weeklyBoard = current.weeklyBoard.copy(slots = scaledSlots),
          aiAddedGroceryIds = current.aiAddedGroceryIds + toolResult.queuedGroceryIngredientIds,
          aiTurnHistory = updatedHistory,
          updatedAtIso = nowIso,
        )
      }
      "schedule_family_meal" -> {
        val targetDay = toolResult.scheduledDayIndex
        val targetSlot = toolResult.scheduledSlotType
        val targetRecipeId = toolResult.scheduledRecipeId
        val updatedBoard = when {
          targetDay != null && targetSlot != null && !targetRecipeId.isNullOrBlank() ->
            current.weeklyBoard.copy(
              slots = current.weeklyBoard.slots.map { slot ->
                when {
                  slot.dayIndex == targetDay && slot.slotType == targetSlot ->
                    slot.copy(
                      recipeId = targetRecipeId,
                      status = MealSlotStatus.PLANNED,
                      isPreppedAhead = false,
                      preppedTimeSavedMinutes = 0,
                    )
                  else -> slot
                }
              },
            )
          else -> current.weeklyBoard
        }
        current.copy(
          weeklyBoard = updatedBoard,
          aiTurnHistory = updatedHistory,
          updatedAtIso = nowIso,
        )
      }
      else -> current.copy(aiTurnHistory = updatedHistory, updatedAtIso = nowIso)
    }

  private fun upsertCatalogItems(
    existing: List<IngredientCatalogItem>,
    additions: List<IngredientCatalogItem>,
  ): List<IngredientCatalogItem> {
    if (additions.isEmpty()) return existing
    val map = LinkedHashMap<String, IngredientCatalogItem>(existing.size + additions.size)
    existing.forEach { map[it.ingredientId] = it }
    additions.forEach { map[it.ingredientId] = it }
    return map.values.toList()
  }

  // Format concise confirmation instructions for the grounded VisualIntakeResult card.
  private fun formatVisualIntakeMessage(intakeResult: VisualIntakeResult): String {
    val totalCount = intakeResult.groundedRestockDeltas.size + intakeResult.proposedNewCatalogItems.size
    return when {
      totalCount > 0 ->
        "Spotted $totalCount kitchen item(s) — select items below to add to your Pantry or Grocery List."
      else ->
        "${intakeResult.summary} No known catalog items were matched automatically."
    }
  }

  // Bound rolling chat history to maxTurnHistorySize and guarantee 100% unique turnIds across restarts.
  private fun appendRollingTurns(
    existing: List<AiChatTurn>,
    userTurn: AiChatTurn,
    assistantTurn: AiChatTurn,
  ): List<AiChatTurn> {
    val seenIds = mutableSetOf<String>()
    val deduplicated = (existing + userTurn + assistantTurn).mapIndexed { index, turn ->
      val baseId = turn.turnId.ifBlank { "turn_auto_$index" }
      val uniqueId = when {
        seenIds.add(baseId) -> baseId
        else -> {
          var suffix = 2
          while (!seenIds.add("${baseId}_dup$suffix")) suffix++
          "${baseId}_dup$suffix"
        }
      }
      if (uniqueId == turn.turnId) turn else turn.copy(turnId = uniqueId)
    }
    val cap = maxTurnHistorySize.coerceAtLeast(2)
    return if (deduplicated.size <= cap) deduplicated else deduplicated.takeLast(cap)
  }

  // Generate monotonic epoch-backed turn IDs guaranteed never to collide with persisted history.
  private fun nextTurnId(prefix: String, reservedIds: Set<String> = emptySet()): String {
    val epochMillis = System.currentTimeMillis()
    while (true) {
      val seq = turnSequence.incrementAndGet()
      val candidate = "${prefix}_${epochMillis}_$seq"
      if (candidate !in reservedIds) return candidate
    }
  }

  companion object {
    /** Default rolling conversation buffer size (`12` messages = 6 user/assistant pairs). */
    const val DEFAULT_MAX_TURN_HISTORY: Int = 12

    private const val DEFAULT_IMAGE_LABEL: String = "Attached Photo"

    // Default pure-JVM file deletion callback removing temporary preprocessed captures safely.
    private fun defaultDeleteTempFile(filePath: String) {
      if (filePath.isBlank()) return
      runCatching {
        val target = File(filePath)
        if (target.isFile) {
          target.delete()
        }
      }
    }
  }
}
