package com.familykitchen.board.ai

import com.familykitchen.board.domain.AdkKitchenToolsEngine
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
import com.familykitchen.board.domain.SmartRecipeDraft
import com.familykitchen.board.domain.StapleStatus
import com.familykitchen.board.domain.StorageLocation
import com.familykitchen.board.domain.ValidationException
import com.familykitchen.board.domain.VisionNotSupportedException
import com.familykitchen.board.domain.VisualIntakeResult
import com.familykitchen.board.domain.batchRestockPantry
import com.familykitchen.board.domain.buildCookbookPhotoOcrPrompt
import com.familykitchen.board.domain.formatAiFallbackToastMessage
import com.familykitchen.board.domain.formatAiResponseSourceLabel
import com.familykitchen.board.domain.isMealOrRecipeProposalRequest
import com.familykitchen.board.domain.parseAllModelToolCalls
import com.familykitchen.board.domain.parseCookbookVisionJsonToDraft
import com.familykitchen.board.domain.requireDomainValid
import com.familykitchen.board.domain.synthesizeCatalogItemFromLabel
import com.familykitchen.board.domain.tryExecuteDeterministicToolIntent
import com.familykitchen.board.ports.AiEdgePort
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ports.KitchenSnapshot
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

// Pure-Kotlin AI Chef turn orchestrator decoupling ViewModels and JUnit tests from Android APIs.
// Enforces zero fake grocery prompts, live Single-Gemma EGM + ADK routing, strict Vision Gate
// surfacing (both kitchen scans and cookbook OCR), and guaranteed temp-file cleanup.

/**
 * Resolution pathway taken by [AiChefCoordinator.submitTurn] for telemetry, UI banners, and
 * deterministic JUnit assertions.
 */
enum class AiTurnResolutionStatus {
  /** Explicit text-only command matched a deterministic `< 5ms` on-device kitchen tool. */
  DETERMINISTIC_TOOL_EXECUTED,

  /** On-device or Cloud LLM output passed EGM safety audit / grounding against household catalog. */
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
 * Pure-testable AI Chef turn coordinator bridging [AiEdgePort] and [SingleGemmaEgmGraphRunner]
 * with the pure functional domain grounding, cookbook OCR, and menu-optimization engines.
 *
 * Contains **zero** `android.*` imports so hermetic JUnit suites can verify:
 * 1. Authentic `Content.ImageFile` forwarding on vision-capable models (both kitchen intake and
 *    cookbook recipe OCR via [scanCookbookRecipePhoto]),
 * 2. Non-silent [VisionNotSupportedException] / [ModelNotReadyException] warning surfacing with
 *    zero pantry mutation and zero silent text-only fallback on image turns,
 * 3. Live 4-stage Single-Gemma Elephant & Goldfish + ADK `LoopAgent` execution on meal-planning
 *    prompts alongside multi-turn conversational continuity (`RECENT_CONVERSATION`) on chat Q&A,
 * 4. Guaranteed temporary preprocessed file cleanup inside `finally` across all outcomes.
 *
 * @param aiEdgePort Hexagonal on-device text and multimodal inference port.
 * @param onCleanupTempImage Deterministic callback invoked in `finally` to delete temp image files.
 * @param maxTurnHistorySize Maximum messages retained in rolling chat history (`12` = 6 exchanges).
 * @param egmGraphRunner Single-Gemma Elephant & Goldfish + ADK graph runner for meal proposals.
 */
class AiChefCoordinator(
  private val aiEdgePort: AiEdgePort,
  private val onCleanupTempImage: (String) -> Unit = ::coordV2DefaultDeleteTempFile,
  private val maxTurnHistorySize: Int = DEFAULT_MAX_TURN_HISTORY,
  val egmGraphRunner: SingleGemmaEgmGraphRunner = SingleGemmaEgmGraphRunner(aiEdgePort),
) {

  // Monotonic sequence generates collision-free turn IDs even under deterministic test timestamps.
  private val turnSequence = AtomicLong(0L)

  /**
   * Convenience adapter routing an [AiChefAction.SubmitPrompt] directly to [submitTurn].
   *
   * @param snapshot Current household state snapshot.
   * @param action User prompt submission action carrying optional `attachedImagePath`.
   * @param attachedImageLabel Optional human-readable chip label for an attached photo.
   * @param deviceProfile Active universal Android RAM-tier / Gemma hardware profile.
   * @param nowIso Deterministic ISO-8601 timestamp stamped onto generated chat turns.
   * @param activeDayIndex Active weekday index (`0..6`) anchoring `"tonight"` / `"today"` proposals.
   * @param currentHour24 Optional 24-hour local clock (`0..23`, parsed from [nowIso] when omitted).
   * @return Complete [AiChefTurnOutcome] ready for state emission and `O(1)` persistence.
   */
  suspend fun handleSubmitAction(
    snapshot: KitchenSnapshot,
    action: AiChefAction.SubmitPrompt,
    attachedImageLabel: String? = null,
    deviceProfile: EdgeDeviceProfile = EdgeDeviceProfile.BALANCED_GEMMA_2B_INT4,
    nowIso: String = DEFAULT_TIMESTAMP_ISO,
    activeDayIndex: Int = 0,
    currentHour24: Int? = null,
  ): AiChefTurnOutcome =
    submitTurn(
      snapshot = snapshot,
      promptText = action.promptText,
      attachedImagePath = action.attachedImagePath,
      attachedImageLabel = attachedImageLabel,
      deviceProfile = deviceProfile,
      nowIso = nowIso,
      activeDayIndex = activeDayIndex,
      currentHour24 = currentHour24,
    )

  /**
   * Coordinates either an **image-attached multimodal turn** or a **text-only turn**:
   * - **Image-attached turns**: Builds a pixel-neutral prompt via [AiMenuAdvisorEngine.buildVisualIntakePrompt],
   *   invokes [AiEdgePort.analyzeImageWithPrompt], grounds output into a confirmation-gated
   *   [VisualIntakeResult] (or surfaces [VisionNotSupportedException] / [ModelNotReadyException]
   *   explicitly without touching pantry state), and deletes [attachedImagePath] in `finally`.
   * - **Text-only turns**: Dispatches explicit `< 5ms` commands via [tryExecuteDeterministicToolIntent],
   *   routes meal/recipe proposal requests through [SingleGemmaEgmGraphRunner.executeTurn] (and
   *   conversational greetings/technique Q&A through multi-turn `buildPantryAwareSystemPrompt`),
   *   and falls back cleanly to [AiMenuAdvisorEngine.generatePantryGroundedMenuProposal] anchored
   *   to [activeDayIndex].
   *
   * @param snapshot Current immutable household state snapshot.
   * @param promptText User-entered prompt or optional photo note.
   * @param attachedImagePath Optional preprocessed JPEG path for multimodal vision turns.
   * @param attachedImageLabel Optional display badge name for the attached image.
   * @param deviceProfile Active hardware profile guiding token/delegate system prompt hints.
   * @param nowIso Deterministic ISO-8601 timestamp.
   * @param activeDayIndex Current real weekday (`0..6`) for `"tonight"` / `"today"` swap commands.
   * @param currentHour24 Optional 24-hour local clock (`0..23`, parsed from [nowIso] when omitted).
   * @return Immutable [AiChefTurnOutcome].
   */
  suspend fun submitTurn(
    snapshot: KitchenSnapshot,
    promptText: String,
    attachedImagePath: String? = null,
    attachedImageLabel: String? = null,
    deviceProfile: EdgeDeviceProfile = EdgeDeviceProfile.BALANCED_GEMMA_2B_INT4,
    nowIso: String = DEFAULT_TIMESTAMP_ISO,
    activeDayIndex: Int = 0,
    currentHour24: Int? = null,
  ): AiChefTurnOutcome {
    // Route strictly by presence of an explicit per-turn image path (zero @Volatile side-channels).
    val cleanImagePath = attachedImagePath?.trim()?.takeIf { it.isNotEmpty() }
    return when {
      cleanImagePath != null ->
        coordV2ExecuteImageAttachedTurn(snapshot, promptText, cleanImagePath, attachedImageLabel, nowIso)
      else -> coordV2ExecuteTextOnlyTurn(snapshot, promptText, deviceProfile, nowIso, activeDayIndex, currentHour24)
    }
  }

  /**
   * Transcribes a photographed cookbook page or handwritten recipe card into an editable
   * [SmartRecipeDraft] while enforcing the **Non-Silent Vision Gate** and guaranteed temporary file
   * cleanup inside `finally`.
   *
   * Never silently drops [preprocessedImagePath] to text-only generation: if no vision-capable
   * model is active, [AiEdgePort.analyzeImageWithPrompt] throws [VisionNotSupportedException] or
   * [ModelNotReadyException] directly to the caller after cleaning up [preprocessedImagePath].
   *
   * @param snapshot Current household snapshot supplying catalog items and `preferredCuisines`.
   * @param preprocessedImagePath Downscaled (`<= 1280px`) EXIF-normalized JPEG path to inspect.
   * @param nowIso Deterministic ISO-8601 timestamp retained for audit symmetry.
   * @return Grounded, editable [SmartRecipeDraft] ready for confirmation in `AddOrEditRecipeSheet`.
   */
  suspend fun scanCookbookRecipePhoto(
    snapshot: KitchenSnapshot,
    preprocessedImagePath: String,
    nowIso: String = DEFAULT_TIMESTAMP_ISO,
  ): SmartRecipeDraft {
    val cleanPath = preprocessedImagePath.trim()
    requireDomainValid(cleanPath.isNotEmpty() && nowIso.isNotBlank(), "CookbookPhotoScan") {
      "preprocessedImagePath must be non-blank"
    }
    try {
      // Build a pixel-neutral OCR prompt tuned to household preferred cuisines (0 fake food names).
      val ocrPrompt = buildCookbookPhotoOcrPrompt(snapshot.householdProfile.preferredCuisines)
      val rawVisionOutput = aiEdgePort.analyzeImageWithPrompt(cleanPath, ocrPrompt)
      return parseCookbookVisionJsonToDraft(
        rawOutput = rawVisionOutput,
        catalog = snapshot.ingredients,
        defaultCuisines = snapshot.householdProfile.preferredCuisines,
      )
    } catch (cancel: CancellationException) {
      throw cancel
    } finally {
      // Deterministically delete the temporary preprocessed JPEG across success, error, or cancel.
      runCatching { onCleanupTempImage(cleanPath) }
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
    val mergedCatalog = coordV2UpsertCatalogItems(snapshot.ingredients, selectedNewItemsById.values.toList())
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
  private suspend fun coordV2ExecuteImageAttachedTurn(
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
      turnId = coordV2NextTurnId("user_img", existingIds),
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
      return coordV2BuildGroundedVisionOutcome(snapshot, userTurn, userNote, rawVisionOutput, nowIso)
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (visionGateError: VisionNotSupportedException) {
      // Surface explicit non-silent warning without mutating pantry or running text-only fallback.
      return coordV2BuildBlockedVisionOutcome(
        snapshot, userTurn, AiTurnResolutionStatus.VISION_GATE_BLOCKED,
        visionGateError.message, visionGateError, nowIso,
      )
    } catch (notReadyError: ModelNotReadyException) {
      // Model still loading or missing: inform user explicitly rather than fabricating items.
      return coordV2BuildBlockedVisionOutcome(
        snapshot, userTurn, AiTurnResolutionStatus.VISION_GATE_BLOCKED,
        notReadyError.message, notReadyError, nowIso,
      )
    } catch (validationError: ValidationException) {
      // Reject ungrounded or malformed vision JSON without touching pantry stock.
      val msg = "Photo scan output did not match strict household schema (${validationError.message}). " +
        "No pantry items were changed—please try a clearer photo."
      return coordV2BuildBlockedVisionOutcome(
        snapshot, userTurn, AiTurnResolutionStatus.VISION_INFERENCE_ERROR,
        msg, validationError, nowIso,
      )
    } catch (unexpected: Throwable) {
      // Catch unexpected native JNI Errors and Exceptions cleanly so UI and pantry stay intact.
      val detail = unexpected.message?.trim()?.takeIf { it.isNotEmpty() } ?: "multimodal runtime error"
      val msg = "Unable to complete on-device photo scan ($detail). Pantry state remains unchanged."
      return coordV2BuildBlockedVisionOutcome(
        snapshot, userTurn, AiTurnResolutionStatus.VISION_INFERENCE_ERROR, msg, null, nowIso,
      )
    } finally {
      // Guarantee temporary preprocessed capture deletion across success, gate error, or cancel.
      runCatching { onCleanupTempImage(preprocessedImagePath) }
    }
  }

  // Parse VisualIntakeResult first; if the vision model returned a grounded swap schema, accept that.
  private fun coordV2BuildGroundedVisionOutcome(
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
          rawModelOutput = rawVisionOutput, userPrompt = userNote, board = snapshot.weeklyBoard,
          recipes = snapshot.allHouseholdRecipes, catalog = snapshot.ingredients,
          pantry = snapshot.pantryEntries, householdProfile = snapshot.householdProfile,
        )
      } catch (_: ValidationException) {
        throw primaryErr
      }
      val reservedIds = snapshot.aiTurnHistory.map { it.turnId }.toSet() + userTurn.turnId
      return coordV2BuildProposalOutcome(
        snapshot = snapshot, userTurn = userTurn, reservedIds = reservedIds,
        prefix = "asst_vis_prop", status = AiTurnResolutionStatus.MULTIMODAL_VISION_GROUNDED,
        proposal = groundedProposal, sourceLabel = coordV2ResolveActiveModelSourceLabel(),
        nowIso = nowIso, tempCleanedUp = true,
      )
    }

    val assistantMessage = coordV2FormatVisualIntakeMessage(intakeResult)
    val reservedIds = snapshot.aiTurnHistory.map { it.turnId }.toSet() + userTurn.turnId
    val assistantTurn = AiChatTurn(
      turnId = coordV2NextTurnId("asst_vis", reservedIds), role = AiChatRole.ASSISTANT,
      messageText = assistantMessage, createdAtIso = nowIso,
      visualIntakeResult = intakeResult, responseSourceLabel = coordV2ResolveActiveModelSourceLabel(),
    )
    val history = coordV2AppendRollingTurns(snapshot.aiTurnHistory, userTurn, assistantTurn)
    // Confirmation gate: never auto-commit groundedRestockDeltas until ConfirmVisualRestock fires.
    return AiChefTurnOutcome(
      status = AiTurnResolutionStatus.MULTIMODAL_VISION_GROUNDED,
      userTurn = userTurn, assistantTurn = assistantTurn, updatedTurnHistory = history,
      updatedSnapshot = snapshot.copy(aiTurnHistory = history, updatedAtIso = nowIso),
      tempImageCleanedUp = true,
    )
  }

  // Construct a non-mutating warning turn preserving pantryEntries, weeklyBoard, and grocery queue.
  private fun coordV2BuildBlockedVisionOutcome(
    snapshot: KitchenSnapshot,
    userTurn: AiChatTurn,
    status: AiTurnResolutionStatus,
    warningMessage: String,
    blockingException: KitchenBoardException?,
    nowIso: String,
  ): AiChefTurnOutcome {
    val reservedIds = snapshot.aiTurnHistory.map { it.turnId }.toSet() + userTurn.turnId
    val assistantTurn = AiChatTurn(
      coordV2NextTurnId("asst_gate", reservedIds), AiChatRole.ASSISTANT, warningMessage, nowIso,
    )
    val history = coordV2AppendRollingTurns(snapshot.aiTurnHistory, userTurn, assistantTurn)
    return AiChefTurnOutcome(
      status = status, userTurn = userTurn, assistantTurn = assistantTurn,
      updatedTurnHistory = history,
      updatedSnapshot = snapshot.copy(aiTurnHistory = history, updatedAtIso = nowIso),
      visionWarningMessage = warningMessage, blockingException = blockingException,
      tempImageCleanedUp = true,
    )
  }

  // Shared proposal outcome builder deduplicating rolling-history append across vision, EGM, and offline paths.
  // Never mutates weeklyBoard, pantryEntries, or grocery queues prior to explicit user confirmation.
  private fun coordV2BuildProposalOutcome(
    snapshot: KitchenSnapshot,
    userTurn: AiChatTurn,
    reservedIds: Set<String>,
    prefix: String,
    status: AiTurnResolutionStatus,
    proposal: AiMenuOptimizationProposal,
    sourceLabel: String?,
    nowIso: String,
    toastMessage: String? = null,
    tempCleanedUp: Boolean = false,
  ): AiChefTurnOutcome {
    // Stamp monotonic collision-free turnId and active model provenance badge onto assistant reply.
    val assistantTurn = AiChatTurn(
      turnId = coordV2NextTurnId(prefix, reservedIds), role = AiChatRole.ASSISTANT,
      messageText = proposal.assistantSummary, createdAtIso = nowIso,
      proposal = proposal, responseSourceLabel = sourceLabel,
    )
    // Trim bounded chat window to maxTurnHistorySize so persisted snapshot stays compact.
    val history = coordV2AppendRollingTurns(snapshot.aiTurnHistory, userTurn, assistantTurn)
    // Persist updated aiTurnHistory in 1 atomic snapshot write at the ViewModel boundary.
    return AiChefTurnOutcome(
      status = status, userTurn = userTurn, assistantTurn = assistantTurn,
      updatedTurnHistory = history,
      updatedSnapshot = snapshot.copy(aiTurnHistory = history, updatedAtIso = nowIso),
      toastMessage = toastMessage, tempImageCleanedUp = tempCleanedUp,
    )
  }

  // Text-only pipeline: < 5ms reflex tool -> EGM Goldfish / conversational Ready LLM -> fallback.
  private suspend fun coordV2ExecuteTextOnlyTurn(
    snapshot: KitchenSnapshot,
    promptText: String,
    deviceProfile: EdgeDeviceProfile,
    nowIso: String,
    activeDayIndex: Int = 0,
    currentHour24: Int? = null,
  ): AiChefTurnOutcome {
    // Reject blank text-only submissions early with a typed domain ValidationException.
    val cleanPrompt = promptText.trim()
    if (cleanPrompt.isEmpty()) {
      throw ValidationException("Enter a message or attach a kitchen photo before sending.", listOf("promptText: blank"))
    }

    val existingIds = snapshot.aiTurnHistory.map { it.turnId }.toSet()
    val userTurn = AiChatTurn(coordV2NextTurnId("user_txt", existingIds), AiChatRole.USER, cleanPrompt, nowIso)
    val reservedForAssistant = existingIds + userTurn.turnId
    // Include heritage/world dishes automatically whenever canonical catalog ingredients are present.
    val effectiveRecipes = snapshot.allHouseholdRecipes

    // Step 1: check explicit natural-language batch commands (< 5ms deterministic reflex tools).
    val toolResult = tryExecuteDeterministicToolIntent(
      userPrompt = cleanPrompt, hasAttachedImage = false, catalog = snapshot.ingredients,
      recipes = effectiveRecipes, board = snapshot.weeklyBoard, pantry = snapshot.pantryEntries,
      householdAllergens = snapshot.householdProfile.householdAllergens, activeDayIndex = activeDayIndex,
    )
    if (toolResult != null) {
      val assistantTurn = AiChatTurn(
        turnId = coordV2NextTurnId("asst_tool", reservedForAssistant), role = AiChatRole.ASSISTANT,
        messageText = toolResult.summaryMessage, createdAtIso = nowIso,
        toolCallResult = toolResult, responseSourceLabel = SOURCE_LABEL_KITCHEN_TOOL,
      )
      val history = coordV2AppendRollingTurns(snapshot.aiTurnHistory, userTurn, assistantTurn)
      val mutatedSnapshot = coordV2ApplyToolResultToSnapshot(snapshot, toolResult, history, nowIso)
      return AiChefTurnOutcome(
        status = AiTurnResolutionStatus.DETERMINISTIC_TOOL_EXECUTED,
        userTurn = userTurn, assistantTurn = assistantTurn,
        updatedTurnHistory = history, updatedSnapshot = mutatedSnapshot,
        toastMessage = toolResult.toastMessage,
      )
    }

    // Step 2: when runtime is Ready, route meal/recipe proposals through SingleGemmaEgmGraphRunner
    // (ADK tools + LoopAgent SafetyAuditor) and greetings/Q&A through multi-turn conversational prompt.
    var fallbackDiagnosticToast: String? = null
    if (aiEdgePort.runtimeState.value is AiRuntimeState.Ready) {
      val activeSourceLabel = coordV2ResolveActiveModelSourceLabel()
      if (isMealOrRecipeProposalRequest(cleanPrompt)) {
        // Hydrate effectiveRecipes onto snapshot when running the 4-stage Single-Gemma EGM graph.
        val egmSnapshot = if (effectiveRecipes.size != snapshot.recipes.size) snapshot.copy(recipes = effectiveRecipes) else snapshot
        val egmOutcome = egmGraphRunner.executeTurn(egmSnapshot, cleanPrompt, snapshot.elephantMemory, activeDayIndex)
        val approvedProposal = egmOutcome.proposal
        // Accept Iteration-1 approvals and Iteration-2 LoopAgent repairs; otherwise fall back cleanly.
        val isApprovedGraphPass =
          egmOutcome.outcome == EgmGraphStageOutcome.GOLDFISH_APPROVED_ITERATION_1 ||
            egmOutcome.outcome == EgmGraphStageOutcome.GOLDFISH_REPAIRED_ITERATION_2
        if (isApprovedGraphPass && approvedProposal != null) {
          val resolvedSource = egmOutcome.responseSourceLabel.ifBlank { activeSourceLabel.orEmpty() }.ifEmpty { null }
          return coordV2BuildProposalOutcome(
            snapshot = snapshot, userTurn = userTurn, reservedIds = reservedForAssistant,
            prefix = "asst_llm", status = AiTurnResolutionStatus.ON_DEVICE_TEXT_GROUNDED,
            proposal = approvedProposal, sourceLabel = resolvedSource, nowIso = nowIso,
          )
        }
        fallbackDiagnosticToast = egmOutcome.fallbackToastMessage
      } else {
        // Conversational greeting / technique Q&A path preserves RECENT_CONVERSATION + time context.
        val resolvedHour24 = currentHour24 ?: coordV2ParseIsoHour24OrNull(nowIso)
        val systemPrompt = AiMenuAdvisorEngine.buildPantryAwareSystemPrompt(
          userPrompt = cleanPrompt, board = snapshot.weeklyBoard, recipes = effectiveRecipes,
          catalog = snapshot.ingredients, pantry = snapshot.pantryEntries,
          householdAllergens = snapshot.householdProfile.householdAllergens,
          householdProfile = snapshot.householdProfile, deviceProfile = deviceProfile,
          recentTurns = snapshot.aiTurnHistory.takeLast(RECENT_PROMPT_TURNS_LIMIT),
          todayDayIndex = activeDayIndex, currentHour24 = resolvedHour24,
        )
        val llmGroundedProposal = try {
          var rawReply = aiEdgePort.generateText(systemPrompt)
          // Execute up to 2 read-only ADK tools if Gemma 4 emits <|tool_call> on a conversational turn.
          val convTools = parseAllModelToolCalls(rawReply).filter { call ->
            call.toolName != "propose_meal_board_swaps" &&
              call.toolName != "audit_candidate_meal_swaps" &&
              call.toolName != "web_culinary_search"
          }.take(MAX_CONVERSATIONAL_READ_TOOLS)
          if (convTools.isNotEmpty()) {
            val toolState = if (effectiveRecipes.size != snapshot.recipes.size) snapshot.copy(recipes = effectiveRecipes) else snapshot
            val obsText = convTools.joinToString("\n") { call ->
              val res = AdkKitchenToolsEngine.executeKitchenDomainTool(toolState, snapshot.elephantMemory, call)
              "TOOL_RESULT[${res.toolName}]: ${res.payloadJson}"
            }
            val synthPrompt =
              "$systemPrompt\n$obsText\nFINAL_SYNTHESIS_RULE: Answer USER_REQUEST warmly in 1-2 sentences using TOOL_RESULT above without emitting any <|tool_call> tags or raw IDs."
            rawReply = aiEdgePort.generateText(synthPrompt)
          }
          AiMenuAdvisorEngine.groundFlexibleLlmTextReply(
            rawModelOutput = rawReply, userPrompt = cleanPrompt, board = snapshot.weeklyBoard,
            recipes = effectiveRecipes, catalog = snapshot.ingredients,
            pantry = snapshot.pantryEntries, householdProfile = snapshot.householdProfile,
            todayDayIndex = activeDayIndex,
          )
        } catch (cancel: CancellationException) {
          throw cancel
        } catch (err: Throwable) {
          // Record a secret-redacted diagnostic toast so Ready-mode fallbacks are never silent.
          fallbackDiagnosticToast = formatAiFallbackToastMessage(err)
          null
        }

        if (llmGroundedProposal != null) {
          return coordV2BuildProposalOutcome(
            snapshot = snapshot, userTurn = userTurn, reservedIds = reservedForAssistant,
            prefix = "asst_llm", status = AiTurnResolutionStatus.ON_DEVICE_TEXT_GROUNDED,
            proposal = llmGroundedProposal, sourceLabel = activeSourceLabel, nowIso = nowIso,
          )
        }
      }
    }

    // Step 3: 100% offline deterministic pantry-grounded advisor targeting real todayDayIndex first.
    val fallbackProposal = AiMenuAdvisorEngine.generatePantryGroundedMenuProposal(
      userPrompt = cleanPrompt, board = snapshot.weeklyBoard, recipes = effectiveRecipes,
      catalog = snapshot.ingredients, pantry = snapshot.pantryEntries,
      householdAllergens = snapshot.householdProfile.householdAllergens,
      householdProfile = snapshot.householdProfile, todayDayIndex = activeDayIndex,
    )
    return coordV2BuildProposalOutcome(
      snapshot = snapshot, userTurn = userTurn, reservedIds = reservedForAssistant,
      prefix = "asst_det", status = AiTurnResolutionStatus.OFFLINE_DETERMINISTIC_PROPOSAL,
      proposal = fallbackProposal, sourceLabel = SOURCE_LABEL_OFFLINE_ADVISOR,
      nowIso = nowIso, toastMessage = fallbackDiagnosticToast,
    )
  }

  // Apply explicit text-only tool results in one pure pass so persistence stays O(1).
  private fun coordV2ApplyToolResultToSnapshot(
    current: KitchenSnapshot, toolResult: AiToolCallResult,
    updatedHistory: List<AiChatTurn>, nowIso: String,
  ): KitchenSnapshot =
    when (toolResult.toolName) {
      "restock_pantry_batch" -> {
        val mergedCatalog = coordV2UpsertCatalogItems(current.ingredients, toolResult.synthesizedCatalogItems)
        val restocked = batchRestockPantry(current.pantryEntries, mergedCatalog, toolResult.restockDeltas, nowIso)
        val restockedIds = toolResult.restockDeltas.map { it.ingredientId }.toSet()
        current.copy(
          ingredients = mergedCatalog, pantryEntries = restocked,
          aiAddedGroceryIds = current.aiAddedGroceryIds - restockedIds,
          aiTurnHistory = updatedHistory, updatedAtIso = nowIso,
        )
      }
      "deplete_pantry_items" -> {
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
          PantryLedgerEntry(
            ingredientId = missingId, tier = item?.tier ?: IngredientTier.TIER_1_QUANTITATIVE,
            currentQuantity = 0.0, stapleStatus = StapleStatus.OUT, isOutOfStock = true,
            updatedAtIso = nowIso, storageLocation = item?.defaultStorageLocation ?: StorageLocation.PANTRY,
          )
        }
        current.copy(pantryEntries = updatedEntries + missingEntries, aiTurnHistory = updatedHistory, updatedAtIso = nowIso)
      }
      "queue_grocery_items" -> {
        val mergedCatalog = coordV2UpsertCatalogItems(current.ingredients, toolResult.synthesizedCatalogItems)
        current.copy(
          ingredients = mergedCatalog,
          aiAddedGroceryIds = current.aiAddedGroceryIds + toolResult.queuedGroceryIngredientIds,
          aiTurnHistory = updatedHistory, updatedAtIso = nowIso,
        )
      }
      "activate_life_event" -> {
        val event = toolResult.activatedLifeEvent
        val updatedProfile = when (event) {
          null -> current.householdProfile
          else -> current.householdProfile.copy(
            activeLifeEvents = current.householdProfile.activeLifeEvents.filterNot { it.eventId == event.eventId } + event,
          )
        }
        val scaledSlots = current.weeklyBoard.slots.map { slot ->
          if (slot.status == MealSlotStatus.PLANNED) slot.copy(targetServings = updatedProfile.effectiveTargetServings) else slot
        }
        current.copy(
          householdProfile = updatedProfile, weeklyBoard = current.weeklyBoard.copy(slots = scaledSlots),
          aiAddedGroceryIds = current.aiAddedGroceryIds + toolResult.queuedGroceryIngredientIds,
          aiTurnHistory = updatedHistory, updatedAtIso = nowIso,
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
                if (slot.dayIndex == targetDay && slot.slotType == targetSlot) {
                  slot.copy(recipeId = targetRecipeId, status = MealSlotStatus.PLANNED, isPreppedAhead = false, preppedTimeSavedMinutes = 0)
                } else {
                  slot
                }
              },
            )
          else -> current.weeklyBoard
        }
        current.copy(
          weeklyBoard = updatedBoard, recipes = current.allHouseholdRecipes,
          aiTurnHistory = updatedHistory, updatedAtIso = nowIso,
        )
      }
      else -> current.copy(aiTurnHistory = updatedHistory, updatedAtIso = nowIso)
    }

  private fun coordV2UpsertCatalogItems(
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
  private fun coordV2FormatVisualIntakeMessage(intakeResult: VisualIntakeResult): String {
    val totalCount = intakeResult.groundedRestockDeltas.size + intakeResult.proposedNewCatalogItems.size
    return when {
      totalCount > 0 ->
        "Spotted $totalCount kitchen item(s) — select items below to add to your Pantry or Grocery List."
      else ->
        "${intakeResult.summary} No known catalog items were matched automatically."
    }
  }

  // Bound rolling chat history to maxTurnHistorySize and guarantee 100% unique turnIds across restarts.
  private fun coordV2AppendRollingTurns(
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

  // Derive concise model provenance badge (e.g., "gemma-4-E2B-it" or "Gemini · gemini-3.8-flash").
  private fun coordV2ResolveActiveModelSourceLabel(): String? {
    val ready = aiEdgePort.runtimeState.value as? AiRuntimeState.Ready ?: return null
    return formatAiResponseSourceLabel(ready.capabilities.backendLabel, ready.capabilities.modelName)
  }

  // Generate monotonic epoch-backed turn IDs guaranteed never to collide with persisted history.
  private fun coordV2NextTurnId(prefix: String, reservedIds: Set<String> = emptySet()): String {
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

    private const val RECENT_PROMPT_TURNS_LIMIT: Int = 4
    private const val MAX_CONVERSATIONAL_READ_TOOLS: Int = 2
    private const val SOURCE_LABEL_KITCHEN_TOOL: String = "Kitchen Tool"
    private const val SOURCE_LABEL_OFFLINE_ADVISOR: String = "Offline Advisor"
    private const val DEFAULT_IMAGE_LABEL: String = "Attached Photo"
    private val ISO_HOUR_REGEX: Regex = Regex("T(\\d{2}):")

    private fun coordV2ParseIsoHour24OrNull(nowIso: String): Int? =
      ISO_HOUR_REGEX.find(nowIso)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..23 }

    // Default pure-JVM file deletion callback removing temporary preprocessed captures safely.
    private fun coordV2DefaultDeleteTempFile(filePath: String) {
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
