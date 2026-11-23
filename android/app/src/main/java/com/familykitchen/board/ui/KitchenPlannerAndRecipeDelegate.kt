package com.familykitchen.board.ui

import com.familykitchen.board.ai.AiChefCoordinator
import com.familykitchen.board.domain.CookedDeductionPayload
import com.familykitchen.board.domain.ElephantKitchenObservation
import com.familykitchen.board.domain.ExpiringPantryCardModel
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.MarketVenue
import com.familykitchen.board.domain.MealSlotType
import com.familykitchen.board.domain.MenuPlannerEngine
import com.familykitchen.board.domain.MenuTemplate
import com.familykitchen.board.domain.PantryLedgerEngine
import com.familykitchen.board.domain.PlanCadence
import com.familykitchen.board.domain.PostCookReactionTag
import com.familykitchen.board.domain.ProportionalStockLevel
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.RecipeCreationMode
import com.familykitchen.board.domain.SlotSkipReason
import com.familykitchen.board.domain.SmartRecipeDraft
import com.familykitchen.board.domain.StorageLocation
import com.familykitchen.board.domain.UnpackItemDirective
import com.familykitchen.board.domain.WeeklyPrepBatchCommitPayload
import com.familykitchen.board.domain.WeeklyRolloverEngine
import com.familykitchen.board.domain.addDaysIso
import com.familykitchen.board.domain.applyMenuTemplateToBoard
import com.familykitchen.board.domain.distillActionIntoElephantMemory
import com.familykitchen.board.domain.forkRecipeForHousehold
import com.familykitchen.board.domain.materializeRecipeFromDraft
import com.familykitchen.board.domain.mondayOfIsoDate
import com.familykitchen.board.domain.movePantryItemToZone
import com.familykitchen.board.domain.parseRawRecipeTextToDraft
import com.familykitchen.board.domain.removeCustomRecipeFromBoardAndList
import com.familykitchen.board.domain.saveBoardAsMenuTemplate
import com.familykitchen.board.domain.setProportionalPantryLevel
import com.familykitchen.board.ports.KitchenSnapshot
import com.familykitchen.board.ports.KitchenStoragePort
import com.familykitchen.board.ui.sheets.CustomFamilyMealDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Cohesive delegate owning SavorAI v2.0 weekly horizon/rollover, saved menus, 3-mode Recipe Studio
// (Smart Paste, Cookbook Vision OCR, Quick Editor, Fork), Pantry zone/audit, Grocery unpack, and Post-Cook actions.

/**
 * Internal delegate handling Meal Plan rollover/menus/slot transforms, 3-mode Recipe Studio
 * creation/forking/OCR, Smart Pantry zone & proportional audits, Smart Grocery unpack batches, and
 * Post-Cook leftover scheduling on behalf of [KitchenBoardViewModel].
 */
internal class KitchenPlannerAndRecipeDelegate(
  private val scope: CoroutineScope,
  private val mutableUiState: MutableStateFlow<KitchenUiState>,
  private val storagePort: KitchenStoragePort,
  private val aiChefCoordinator: AiChefCoordinator,
  private val preprocessImageUri: suspend (String) -> String,
  private val deleteTempImage: (String?) -> Unit,
  private val nowIso: () -> String,
  private val runGuarded: (suspend () -> Unit) -> Unit,
) {

  /** Opens the contextual Sous-Chef modal sheet with an optional [contextHint]. */
  fun openSousChefSheet(contextHint: String? = null) {
    // Trim blank context hints so SousChefSheet falls back cleanly to tab-aware starter chips.
    val cleanHint = contextHint?.trim()?.takeIf { it.isNotEmpty() }
    mutableUiState.update { it.copy(isSousChefSheetOpen = true, sousChefContextPromptHint = cleanHint) }
  }

  /** Closes the contextual Sous-Chef modal sheet and clears any active prompt hint. */
  fun dismissSousChefSheet() =
    mutableUiState.update { it.copy(isSousChefSheetOpen = false, sousChefContextPromptHint = null) }

  /**
   * Switches between `This Week` (`false`) and `Next Week` (`true`), lazily drafting an initial
   * `nextWeekBoard` anchored to next Monday when previewed for the first time.
   */
  fun setViewingNextWeek(nextWeek: Boolean) {
    // Fast-path toggle when switching back to This Week or when nextWeekBoard already exists.
    if (!nextWeek || mutableUiState.value.snapshot.nextWeekBoard != null) {
      mutableUiState.update { state ->
        deriveKitchenUiState(
          state.copy(viewingNextWeek = nextWeek, activePacerSlotId = null),
          state.snapshot, storagePort.batchWriteCallCount,
        )
      }
      return
    }
    // Lazily initialize nextWeekBoard in 1 O(1) write so Next Week shows real Mon..Sun dates.
    runGuarded {
      val state = mutableUiState.value
      val current = state.snapshot
      val stamp = nowIso()
      val nextMondayIso = addDaysIso(mondayOfIsoDate(state.realTodayIso), 7)
      val rollover = WeeklyRolloverEngine.startOrRefreshWeeklyBoard(
        previousBoard = current.weeklyBoard, newWeekStartDateIso = nextMondayIso,
        cadence = current.planCadence, carryOverUncooked = false,
        recipes = current.allHouseholdRecipes, catalog = current.ingredients,
        pantryEntries = current.pantryEntries, householdProfile = current.householdProfile,
        elephantMemory = current.elephantMemory, todayIso = state.realTodayIso, nowIso = stamp,
      )
      val updated = current.copy(
        recipes = current.allHouseholdRecipes, nextWeekBoard = rollover.nextBoard, updatedAtIso = stamp,
      )
      storagePort.saveSnapshotBatch(updated)
      mutableUiState.update { latest ->
        deriveKitchenUiState(
          latest.copy(viewingNextWeek = true, activePacerSlotId = null),
          updated, storagePort.batchWriteCallCount,
        )
      }
    }
  }

  /** Opens `StartNewWeekSheet` for choosing planning cadence and uncooked carry-over. */
  fun openStartNewWeekSheet() = mutableUiState.update { it.copy(isStartNewWeekSheetOpen = true) }

  /** Closes `StartNewWeekSheet`. */
  fun dismissStartNewWeekSheet() = mutableUiState.update { it.copy(isStartNewWeekSheetOpen = false) }

  /**
   * Starts or refreshes the weekly board with [cadence] and [carryOverUncooked], updating
   * `weeklyBoard`, `planCadence`, and `elephantMemory` (`WeeklyEpisodicRollup`) in `1` `O(1)` write.
   */
  fun confirmStartNewWeek(cadence: PlanCadence, carryOverUncooked: Boolean = true) = runGuarded {
    val state = mutableUiState.value
    val current = state.snapshot
    val stamp = nowIso()
    // Anchor weekStartDateIso to real Monday (or next Monday when planning Next Week).
    val thisMondayIso = mondayOfIsoDate(state.realTodayIso)
    val targetMondayIso = if (state.viewingNextWeek) addDaysIso(thisMondayIso, 7) else thisMondayIso
    val sourceBoard = state.activeDisplayedBoard

    // Preserve pinned family traditions and compute WeeklyEpisodicRollup in one pure pass.
    val rollover = WeeklyRolloverEngine.startOrRefreshWeeklyBoard(
      previousBoard = sourceBoard, newWeekStartDateIso = targetMondayIso,
      cadence = cadence, carryOverUncooked = carryOverUncooked,
      recipes = current.allHouseholdRecipes, catalog = current.ingredients,
      pantryEntries = current.pantryEntries, householdProfile = current.householdProfile,
      elephantMemory = current.elephantMemory, todayIso = state.realTodayIso, nowIso = stamp,
    )
    val nextSnapshot = when {
      state.viewingNextWeek -> current.copy(
        recipes = current.allHouseholdRecipes, nextWeekBoard = rollover.nextBoard,
        planCadence = cadence, elephantMemory = rollover.updatedElephantMemory, updatedAtIso = stamp,
      )
      else -> current.copy(
        recipes = current.allHouseholdRecipes, weeklyBoard = rollover.nextBoard,
        planCadence = cadence, elephantMemory = rollover.updatedElephantMemory,
        completedPrepTaskIds = emptySet(), updatedAtIso = stamp,
      )
    }
    storagePort.saveSnapshotBatch(nextSnapshot)
    mutableUiState.update {
      it.copy(isStartNewWeekSheetOpen = false).withToast("Started ${cadence.displayTitle} meal plan.")
    }
  }

  /** Opens `SavedMenusSheet` ranked by household `preferredCuisines`. */
  fun openSavedMenusSheet() = mutableUiState.update { it.copy(isSavedMenusSheetOpen = true) }

  /** Closes `SavedMenusSheet`. */
  fun dismissSavedMenusSheet() = mutableUiState.update { it.copy(isSavedMenusSheetOpen = false) }

  /** Applies [template] onto the active displayed board in `1` `O(1)` atomic batch write. */
  fun applySavedMenu(template: MenuTemplate, remixWithPantry: Boolean = true) = runGuarded {
    val state = mutableUiState.value
    val current = state.snapshot
    val stamp = nowIso()
    val effectiveRecipes = current.allHouseholdRecipes
    val baseMonday = mondayOfIsoDate(state.realTodayIso)
    val targetMonday = if (state.viewingNextWeek) addDaysIso(baseMonday, 7) else baseMonday

    // Respect pinned slots and auto-remix locally unavailable template dishes when requested.
    val appliedBoard = applyMenuTemplateToBoard(
      currentBoard = state.activeDisplayedBoard, template = template,
      recipes = effectiveRecipes, catalog = current.ingredients,
      pantryEntries = current.pantryEntries, profile = current.householdProfile,
      remixUnavailableOrMissing = remixWithPantry, newWeekStartDateIso = targetMonday,
    )
    val nextSnapshot = when {
      state.viewingNextWeek -> current.copy(
        recipes = effectiveRecipes, nextWeekBoard = appliedBoard, updatedAtIso = stamp,
      )
      else -> current.copy(
        recipes = effectiveRecipes, weeklyBoard = appliedBoard,
        completedPrepTaskIds = emptySet(), updatedAtIso = stamp,
      )
    }
    storagePort.saveSnapshotBatch(nextSnapshot)
    mutableUiState.update {
      it.copy(isSavedMenusSheetOpen = false).withToast("Applied ${template.title} to Meal Plan.")
    }
  }

  /** Saves the currently displayed weekly board as a reusable household [MenuTemplate] (`1` `O(1)` write). */
  fun saveCurrentWeekAsMenu(title: String, subtitle: String = "") = runGuarded {
    val state = mutableUiState.value
    val current = state.snapshot
    val stamp = nowIso()
    // Prepend the new user-authored template ahead of starter menus for immediate access.
    val slug = title.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "week" }
    val savedTemplate = saveBoardAsMenuTemplate(
      board = state.activeDisplayedBoard, title = title, subtitle = subtitle,
      cuisineFocus = current.householdProfile.preferredCuisines,
      menuId = "menu_user_${slug}_${current.savedMenus.size + 1}",
    )
    val mergedMenus = listOf(savedTemplate) + current.savedMenus.filterNot { it.menuId == savedTemplate.menuId }
    storagePort.saveSnapshotBatch(current.copy(savedMenus = mergedMenus, updatedAtIso = stamp))
    vmPlanPostToast("Saved ${savedTemplate.title} to Menus.")
  }

  /** Clears [slotId] from its owning weekly board in `1` `O(1)` atomic batch write. */
  fun clearSlot(slotId: String) = runGuarded {
    // Remove slot assignment so GroceryDiffEngine releases its reserved Tier-1 BOM immediately.
    val state = mutableUiState.value
    val nextSnapshot = uiStateV2TransformBoardContainingSlot(state.snapshot, state.viewingNextWeek, slotId, nowIso()) {
      WeeklyRolloverEngine.clearMealSlot(it, slotId)
    }
    storagePort.saveSnapshotBatch(nextSnapshot)
    mutableUiState.update {
      it.copy(adjustingSlotId = if (it.adjustingSlotId == slotId) null else it.adjustingSlotId)
        .withToast("Cleared meal slot.")
    }
  }

  /** Toggles `isPinned` on [slotId] so weekly rollovers preserve or release the anchor routine. */
  fun toggleSlotPinned(slotId: String) = runGuarded {
    // Pinned slots survive Start New Week rollovers and Saved Menu template applications intact.
    val state = mutableUiState.value
    var nowPinned = false
    val nextSnapshot = uiStateV2TransformBoardContainingSlot(state.snapshot, state.viewingNextWeek, slotId, nowIso()) {
      val toggled = WeeklyRolloverEngine.toggleMealSlotPinned(it, slotId)
      nowPinned = toggled.slots.firstOrNull { slot -> slot.slotId == slotId }?.isPinned == true
      toggled
    }
    storagePort.saveSnapshotBatch(nextSnapshot)
    vmPlanPostToast(if (nowPinned) "Pinned routine meal for future weeks." else "Unpinned meal slot.")
  }

  /** Moves [sourceSlotId] to `(targetDayIndex, targetSlotType)` or swaps with an occupied slot (`EC-01`). */
  fun moveOrSwapSlot(sourceSlotId: String, targetDayIndex: Int, targetSlotType: MealSlotType) = runGuarded {
    val state = mutableUiState.value
    val current = state.snapshot
    val recipesById = current.allHouseholdRecipes.associateBy { it.recipeId }
    // WeeklyRolloverEngine enforces EC-01 (<= 25m morning cap) on both moved and swapped slots.
    val nextSnapshot = uiStateV2TransformBoardContainingSlot(current, state.viewingNextWeek, sourceSlotId, nowIso()) {
      WeeklyRolloverEngine.moveOrSwapMealSlot(
        it, sourceSlotId, targetDayIndex, targetSlotType, recipesById, current.householdProfile.fredMorningCapMinutes,
      )
    }
    storagePort.saveSnapshotBatch(nextSnapshot)
    mutableUiState.update {
      it.copy(adjustingSlotId = null, activeDayIndex = targetDayIndex.coerceIn(0, 6))
        .withToast("Moved meal to selected slot.")
    }
  }

  /**
   * Marks [slotId] `COMPLETED_SKIPPED` with explicit [reason] (`ATE_LEFTOVERS`, `EATING_OUT`,
   * `SKIPPED`), releasing reserved BOM (`EC-12`) and distilling into `ElephantMemory` in `1` `O(1)` write.
   */
  fun markSlotSkipWithReason(slotId: String, reason: SlotSkipReason) = runGuarded {
    // Deduct 0 pantry units (EC-12) and record skip cadence in ElephantMemory for future weeks.
    val state = mutableUiState.value
    val current = state.snapshot
    val stamp = nowIso()
    val matchedSlot = state.activeDisplayedBoard.slots.firstOrNull { it.slotId == slotId }
      ?: current.weeklyBoard.slots.firstOrNull { it.slotId == slotId }
    val boardUpdated = uiStateV2TransformBoardContainingSlot(current, state.viewingNextWeek, slotId, stamp) {
      WeeklyRolloverEngine.markSlotWithSkipReason(it, slotId, reason)
    }
    val nextMemory = vmPlanDistillSkippedSlot(current, matchedSlot?.slotId, matchedSlot?.dayIndex, matchedSlot?.slotType, matchedSlot?.recipeId, reason.badgeLabel, stamp)
    storagePort.saveSnapshotBatch(boardUpdated.copy(elephantMemory = nextMemory, updatedAtIso = stamp))
    mutableUiState.update {
      it.copy(adjustingSlotId = null).withToast("Marked ${reason.badgeLabel} and released reserved pantry stock.")
    }
  }

  /** Marks [slotId] `COMPLETED_SKIPPED` (`ATE_LEFTOVERS` default) and distills into `ElephantMemory`. */
  fun markSlotSkippedOrLeftovers(slotId: String) = runGuarded {
    // Release chronological Tier-1 reservations without depleting physical Pantry balances.
    val state = mutableUiState.value
    val current = state.snapshot
    val stamp = nowIso()
    val matched = current.weeklyBoard.slots.firstOrNull { it.slotId == slotId }
      ?: state.activeDisplayedBoard.slots.firstOrNull { it.slotId == slotId }
    val updatedBoard = MenuPlannerEngine.markSlotSkippedOrLeftovers(current.weeklyBoard, slotId)
    val nextMemory = vmPlanDistillSkippedSlot(current, matched?.slotId, matched?.dayIndex, matched?.slotType, matched?.recipeId, null, stamp)
    storagePort.saveSnapshotBatch(current.copy(weeklyBoard = updatedBoard, elephantMemory = nextMemory, updatedAtIso = stamp))
    mutableUiState.update {
      it.copy(adjustingSlotId = null).withToast("Marked Ate Leftovers and released reserved pantry stock.")
    }
  }

  // --- 3-Mode Recipe Studio, Cookbook Grid & Ingredient Swapping ---
  // Supports Smart Paste, Gemma Vision Cookbook OCR, Quick Editor, and Make Our Version forks.

  /** Opens the 3-mode `AddOrEditRecipeSheet` studio (`SMART_PASTE`, `SCAN_COOKBOOK`, `QUICK_EDITOR`). */
  fun openAddRecipeStudio(
    mode: RecipeCreationMode = RecipeCreationMode.QUICK_EDITOR,
    scheduleByDefault: Boolean = false,
  ) {
    // Seed blank draft servings and cuisine tags from active HouseholdProfile defaults.
    val profile = mutableUiState.value.snapshot.householdProfile
    val blankDraft = SmartRecipeDraft(
      title = "", servings = profile.effectiveTargetServings,
      rawPrepMinutes = 10, cookMinutes = 20,
      allowedSlots = listOf(MealSlotType.DINNER), cuisineTags = profile.preferredCuisines,
    )
    mutableUiState.update {
      it.copy(
        isAddOrEditRecipeSheetOpen = true, initialRecipeCreationMode = mode,
        scheduleOnSaveByDefault = scheduleByDefault, editingRecipeDraft = blankDraft,
        editingExistingRecipeId = null, editingParentRecipeId = null,
      )
    }
  }

  /** Opens `AddOrEditRecipeSheet` pre-filled with a forked household remix of [source] (`Make Our Version`). */
  fun openForkRecipeStudio(source: Recipe) {
    // Fork preserves canonical recipe untouched while recording parentRecipeId lineage.
    val draft = uiStateV2RecipeToEditableDraft(forkRecipeForHousehold(source), mutableUiState.value.snapshot)
    mutableUiState.update {
      it.copy(
        isAddOrEditRecipeSheetOpen = true, initialRecipeCreationMode = RecipeCreationMode.QUICK_EDITOR,
        scheduleOnSaveByDefault = false, editingRecipeDraft = draft,
        editingExistingRecipeId = null, editingParentRecipeId = source.recipeId,
      )
    }
  }

  /** Opens `AddOrEditRecipeSheet` pre-filled with an existing user-created [recipe] for in-place editing. */
  fun openEditCustomRecipeStudio(recipe: Recipe) {
    // Hydrate existing recipeId so saveSmartRecipeDraft updates the custom recipe in-place.
    val draft = uiStateV2RecipeToEditableDraft(recipe, mutableUiState.value.snapshot)
    mutableUiState.update {
      it.copy(
        isAddOrEditRecipeSheetOpen = true, initialRecipeCreationMode = RecipeCreationMode.QUICK_EDITOR,
        scheduleOnSaveByDefault = false, editingRecipeDraft = draft,
        editingExistingRecipeId = recipe.recipeId, editingParentRecipeId = recipe.parentRecipeId,
      )
    }
  }

  /** Closes `AddOrEditRecipeSheet` and clears transient studio draft fields. */
  fun dismissAddOrEditRecipeSheet() = mutableUiState.update {
    it.copy(
      isAddOrEditRecipeSheetOpen = false, editingRecipeDraft = null,
      editingExistingRecipeId = null, editingParentRecipeId = null,
    )
  }

  /** Updates the in-progress [SmartRecipeDraft] staged inside `AddOrEditRecipeSheet`. */
  fun updateEditingRecipeDraft(draft: SmartRecipeDraft) =
    mutableUiState.update { it.copy(editingRecipeDraft = draft) }

  /** Parses free-form pasted recipe text into `editingRecipeDraft` and switches to `QUICK_EDITOR`. */
  fun parsePastedRecipeText(rawText: String) {
    val state = mutableUiState.value
    // Ground matched ingredients against household catalog without fabricating pantry stock.
    val parsed = parseRawRecipeTextToDraft(
      rawText, state.snapshot.ingredients, state.snapshot.householdProfile.preferredCuisines,
    )
    mutableUiState.update {
      it.copy(
        isAddOrEditRecipeSheetOpen = true, editingRecipeDraft = parsed,
        initialRecipeCreationMode = RecipeCreationMode.QUICK_EDITOR,
      ).withToast("Extracted ${parsed.ingredientLines.size} ingredients from pasted text.")
    }
  }

  /**
   * Preprocesses [sourceUri] (`<= 1280px` EXIF JPEG) and runs [AiChefCoordinator.scanCookbookRecipePhoto],
   * enforcing the **Non-Silent Vision Gate** and populating `editingRecipeDraft` on success.
   */
  fun scanCookbookPhotoIntoDraft(sourceUri: String) {
    val snapshotState = mutableUiState.value
    if (snapshotState.isPreprocessingImage || snapshotState.isAiGenerating) {
      vmPlanPostToast("Cookbook photo transcription is already running.")
      return
    }
    mutableUiState.update { it.copy(isPreprocessingImage = true, preprocessingImageLabel = "cookbook_page") }
    scope.launch {
      var preprocessedPath: String? = null
      var handedToCoordinator = false
      try {
        // Normalize EXIF orientation and resize <= 1280px before passing to Gemma Vision OCR.
        val readyPath = preprocessImageUri(sourceUri)
        preprocessedPath = readyPath
        mutableUiState.update {
          it.copy(
            isPreprocessingImage = false, preprocessingImageLabel = null,
            isAiGenerating = true, isGeneratingVisionTurn = true,
          )
        }
        handedToCoordinator = true
        val draft = aiChefCoordinator.scanCookbookRecipePhoto(mutableUiState.value.snapshot, readyPath, nowIso())
        mutableUiState.update {
          it.copy(
            isAddOrEditRecipeSheetOpen = true, editingRecipeDraft = draft,
            initialRecipeCreationMode = RecipeCreationMode.QUICK_EDITOR,
            isAiGenerating = false, isGeneratingVisionTurn = false,
          ).withToast("Transcribed ${draft.title} from photo.")
        }
      } catch (cancel: CancellationException) {
        if (!handedToCoordinator && preprocessedPath != null) deleteTempImage(preprocessedPath)
        throw cancel
      } catch (err: Throwable) {
        // Surface VisionNotSupportedException / ModelNotReadyException non-silently via toast.
        if (!handedToCoordinator && preprocessedPath != null) deleteTempImage(preprocessedPath)
        vmPlanPostToast(err.message ?: "Unable to transcribe cookbook photo.")
      } finally {
        mutableUiState.update {
          it.copy(
            isPreprocessingImage = false, preprocessingImageLabel = null,
            isAiGenerating = false, isGeneratingVisionTurn = false,
          )
        }
      }
    }
  }

  /**
   * Materializes [draft] (`0` phantom stock for new items), upserts into the household cookbook,
   * and optionally schedules onto `(scheduleDayIndex, scheduleSlotType)` in **1 `O(1)` write**.
   */
  fun saveSmartRecipeDraft(
    draft: SmartRecipeDraft,
    scheduleDayIndex: Int? = null,
    scheduleSlotType: MealSlotType? = null,
  ) = runGuarded {
    val state = mutableUiState.value
    val current = state.snapshot
    val stamp = nowIso()
    // New ingredients start at 0.0 / OUT so Shop immediately surfaces exact shopping deficits.
    val materialized = materializeRecipeFromDraft(
      draft, current.ingredients, current.pantryEntries,
      state.editingExistingRecipeId, state.editingParentRecipeId, stamp,
    )
    val baseRecipes = current.allHouseholdRecipes
    val upsertedRecipes = when {
      baseRecipes.any { it.recipeId == materialized.recipe.recipeId } ->
        baseRecipes.map { if (it.recipeId == materialized.recipe.recipeId) materialized.recipe else it }
      else -> baseRecipes + materialized.recipe
    }
    var nextSnapshot = current.copy(
      ingredients = materialized.updatedCatalog, pantryEntries = materialized.updatedPantry,
      recipes = upsertedRecipes, updatedAtIso = stamp,
    )
    if (scheduleDayIndex != null && scheduleSlotType != null) {
      nextSnapshot = reduceScheduledPresetRecipe(
        nextSnapshot, materialized.recipe, scheduleSlotType, scheduleDayIndex, false, stamp, state.viewingNextWeek,
      )
    }
    storagePort.saveSnapshotBatch(nextSnapshot)
    mutableUiState.update {
      it.copy(
        isAddOrEditRecipeSheetOpen = false, editingRecipeDraft = null,
        editingExistingRecipeId = null, editingParentRecipeId = null,
        selectedRecipeId = materialized.recipe.recipeId,
      ).withToast("Saved ${materialized.recipe.title} to Recipes.")
    }
  }

  /** Deletes a user-created or forked recipe and clears any upcoming `PLANNED` slots (`1` `O(1)` write). */
  fun deleteCustomRecipe(recipeId: String) = runGuarded {
    val current = mutableUiState.value.snapshot
    val stamp = nowIso()
    val (updatedRecipes, updatedWeekly) = removeCustomRecipeFromBoardAndList(
      current.allHouseholdRecipes, current.weeklyBoard, recipeId,
    )
    if (updatedRecipes.size == current.allHouseholdRecipes.size) {
      vmPlanPostToast("Built-in family recipes cannot be deleted; tap Make Our Version to customize.")
      return@runGuarded
    }
    val updatedNext = current.nextWeekBoard?.let {
      removeCustomRecipeFromBoardAndList(current.allHouseholdRecipes, it, recipeId).second
    }
    storagePort.saveSnapshotBatch(
      current.copy(
        recipes = updatedRecipes, weeklyBoard = updatedWeekly, nextWeekBoard = updatedNext,
        savedRecipeIds = current.savedRecipeIds - recipeId,
        recipeNotesByRecipeId = current.recipeNotesByRecipeId - recipeId,
        checkedReaderIngredientsByRecipeId = current.checkedReaderIngredientsByRecipeId - recipeId,
        updatedAtIso = stamp,
      ),
    )
    mutableUiState.update {
      it.copy(selectedRecipeId = if (it.selectedRecipeId == recipeId) null else it.selectedRecipeId)
        .withToast("Deleted custom recipe.")
    }
  }

  /** Swaps [fromId] with [toId] on [recipeId] and recomputes per-serving nutrition (`1` `O(1)` write). */
  fun swapIngredientInRecipe(recipeId: String, fromId: String, toId: String) = runGuarded {
    val reduced = reduceAiRecipeSubstitution(
      mutableUiState.value.snapshot, recipeId, fromId, toId, nowIso(),
    ) ?: return@runGuarded
    storagePort.saveSnapshotBatch(reduced.first)
    vmPlanPostToast("Swapped ingredient in ${reduced.second.title}.")
  }

  /** Closes `CleanRecipeReaderScreen` back onto the 2-Column Cookbook Grid (`selectedRecipeId = null`). */
  fun closeRecipeReaderBackToGrid() = mutableUiState.update { it.copy(selectedRecipeId = null) }

  // --- Smart Pantry Zone Moves, In-Place Shelf Audit & Pre-Shop Requeue ---

  /** Toggles in-place `Empty · Low · Half · Full` Shelf Audit mode on `SmartPantryScreen`. */
  fun toggleShelfAuditMode() = mutableUiState.update { state ->
    val nextActive = !state.isShelfAuditModeActive
    state.copy(isShelfAuditModeActive = nextActive)
      .withToast(if (nextActive) "Quick Shelf Check active." else "Closed Quick Shelf Check.")
  }

  /** Sets in-place Shelf Audit mode explicitly to [active]. */
  fun setShelfAuditMode(active: Boolean) = mutableUiState.update { it.copy(isShelfAuditModeActive = active) }

  /** Moves [ingredientId] into [targetZone] (`+90d`/`+180d` on Freezer rescue) in `1` `O(1)` write. */
  fun movePantryItemZone(ingredientId: String, targetZone: StorageLocation) = runGuarded {
    val state = mutableUiState.value
    val current = state.snapshot
    val stamp = nowIso()
    // Moving perishable produce/proteins to FREEZER extends expiryDateIso automatically.
    val updated = movePantryItemToZone(
      current.pantryEntries, ingredientId, targetZone, current.ingredients, state.realTodayIso, stamp,
    )
    storagePort.saveSnapshotBatch(current.copy(pantryEntries = updated, updatedAtIso = stamp))
    val label = current.catalogById[ingredientId]?.displayName ?: ingredientId
    vmPlanPostToast("Moved $label to ${targetZone.displayLabel}.")
  }

  /** Marks [ingredientId] used up (`0.0` / `OUT`, clearing phantom expiry) in `1` `O(1)` write. */
  fun markPantryItemUsed(ingredientId: String) =
    setPantryItemProportionalLevel(ingredientId, ProportionalStockLevel.EMPTY)

  /** Sets [ingredientId] to [level] (`EMPTY`, `LOW`, `HALF`, `FULL`) in `1` `O(1)` batch write. */
  fun setPantryItemProportionalLevel(ingredientId: String, level: ProportionalStockLevel) = runGuarded {
    val state = mutableUiState.value
    val current = state.snapshot
    val stamp = nowIso()
    val updated = setProportionalPantryLevel(
      current.pantryEntries, ingredientId, level, current.ingredients, state.realTodayIso, stamp,
    )
    storagePort.saveSnapshotBatch(current.copy(pantryEntries = updated, updatedAtIso = stamp))
    val label = current.catalogById[ingredientId]?.displayName ?: ingredientId
    val msg = if (level == ProportionalStockLevel.EMPTY) "Marked $label used up." else "Updated $label to ${level.chipLabel}."
    vmPlanPostToast(msg)
  }

  /** Updates default zone & shelf-life days on [ingredientId] across catalog and stocked pantry (`1` write). */
  fun updatePantryItemStorageAndShelfLife(
    ingredientId: String,
    storageLocation: StorageLocation,
    shelfLifeDays: Int,
  ) = runGuarded {
    val state = mutableUiState.value
    val next = uiStateV2ApplyStorageAndShelfLifeEdit(
      state.snapshot, ingredientId, storageLocation, shelfLifeDays, state.realTodayIso, nowIso(),
    )
    storagePort.saveSnapshotBatch(next)
    val label = state.snapshot.catalogById[ingredientId]?.displayName ?: ingredientId
    vmPlanPostToast("Updated $label storage to ${storageLocation.displayLabel}.")
  }

  /** Re-queues a skipped `Pantry Adjusted` staple (`Ran Low` in `Shop`) in **1 `O(1)` write**. */
  fun requeueSkippedPantryStaple(ingredientId: String) = runGuarded {
    val state = mutableUiState.value
    val next = uiStateV2ApplySkippedStapleRequeue(state.snapshot, ingredientId, state.realTodayIso, nowIso())
    storagePort.saveSnapshotBatch(next)
    val label = state.snapshot.catalogById[ingredientId]?.displayName ?: ingredientId
    vmPlanPostToast("Added $label back to your Grocery List.")
  }

  // --- Smart Grocery Venue Filtering, Safe Uncheck & Unpack Groceries Sheet ---

  /** Updates the active `All · Palengke · Supermarket` filter on `SmartGroceryScreen`. */
  fun setGroceryVenueFilter(venue: MarketVenue?) =
    mutableUiState.update { it.copy(activeGroceryVenueFilter = venue) }

  /** Opens `UnpackGroceriesSheet` for zone-aware grocery unpacking. */
  fun openUnpackGroceriesSheet() = mutableUiState.update { it.copy(isUnpackGroceriesSheetOpen = true) }

  /** Closes `UnpackGroceriesSheet`. */
  fun dismissUnpackGroceriesSheet() = mutableUiState.update { it.copy(isUnpackGroceriesSheetOpen = false) }

  /** Commits checked [directives] and promoted/cleared custom items in **1 `O(1)` batch write**. */
  fun confirmUnpackGroceries(
    directives: List<UnpackItemDirective>,
    promotedCustomIds: Set<String> = emptySet(),
    clearCustomIds: Set<String> = emptySet(),
  ) = runGuarded {
    val state = mutableUiState.value
    storagePort.commitGroceryUnpackBatch(
      state.snapshot, directives, promotedCustomIds, clearCustomIds, state.realTodayIso, nowIso(),
    )
    val count = directives.size + promotedCustomIds.size + clearCustomIds.size
    mutableUiState.update {
      it.copy(isUnpackGroceriesSheetOpen = false).withToast("Unpacked $count grocery items into Pantry.")
    }
  }

  /** Unchecks all checked catalog and custom grocery rows without deleting needed deficits (`1` write). */
  fun uncheckAllCheckedGrocery() = runGuarded {
    storagePort.uncheckCheckedGroceryItems(current = mutableUiState.value.snapshot, nowIso = nowIso())
    vmPlanPostToast("Unchecked grocery items.")
  }

  // --- Post-Cook Completion Summary & 0-BOM Tomorrow Leftover Lunch ---

  /** Dismisses `PostCookCompletionSheet` without scheduling leftovers. */
  fun dismissPostCookSummary() = mutableUiState.update { it.copy(postCookSummary = null) }

  /**
   * Commits optional `0`-BOM tomorrow Leftover Lunch (`scheduleLeftoverLunchFromCook`) and distills
   * [reactionTag] into `HouseholdElephantMemory` in **1 single `O(1)` write**.
   */
  fun commitPostCookFollowUp(saveLeftoverLunch: Boolean, reactionTag: PostCookReactionTag?) {
    val summary = mutableUiState.value.postCookSummary ?: return
    if (!saveLeftoverLunch && reactionTag == null) {
      mutableUiState.update { it.copy(postCookSummary = null) }
      return
    }
    runGuarded {
      val next = uiStateV2ApplyPostCookFollowUpBatch(
        mutableUiState.value.snapshot, summary, saveLeftoverLunch, reactionTag, nowIso(),
      )
      storagePort.saveSnapshotBatch(next)
      val toast = when {
        saveLeftoverLunch -> "Saved ${summary.recipeTitle} leftovers for tomorrow lunch."
        reactionTag != null -> "Saved ${reactionTag.chipLabel} note for ${summary.recipeTitle}."
        else -> null
      }
      mutableUiState.update {
        val cleared = it.copy(postCookSummary = null)
        if (toast != null) cleared.withToast(toast) else cleared
      }
    }
  }

  // Distill skipped-meal cadence patterns into ElephantMemory so Goldfish reduces overscheduling.
  private fun vmPlanDistillSkippedSlot(
    current: KitchenSnapshot,
    slotId: String?,
    dayIndex: Int?,
    slotType: MealSlotType?,
    recipeId: String?,
    reasonBadge: String?,
    nowIso: String,
  ) = when {
    slotId == null || dayIndex == null || slotType == null || recipeId == null -> current.elephantMemory
    else -> {
      val baseTitle = current.recipesById[recipeId]?.title ?: recipeId
      val fullTitle = if (reasonBadge.isNullOrBlank()) baseTitle else "$baseTitle · $reasonBadge"
      distillActionIntoElephantMemory(
        current.elephantMemory,
        ElephantKitchenObservation.MealSlotSkipped(slotId, dayIndex, slotType, fullTitle),
        nowIso,
      )
    }
  }

  /** Executes 1-tap `Cooked It` fast-path pantry deduction (`O(1)` atomic batch commit). */
  fun quickCookSlot(slotId: String) = runGuarded {
    // Deduct scaled BOM + flat 1x workout boost in 1 atomic write without opening override sheet.
    val current = mutableUiState.value.snapshot
    val slot = current.weeklyBoard.slots.firstOrNull { it.slotId == slotId } ?: return@runGuarded
    val recipe = current.recipesById[slot.recipeId] ?: return@runGuarded
    val stamp = nowIso()
    val payload = CookedDeductionPayload(slot.slotId, recipe.recipeId, slot.targetServings, slot.activeWorkoutModifier)
    val deduction = PantryLedgerEngine.applyCookedDeduction(
      current.pantryEntries, recipe, current.ingredients, payload, stamp, current.householdProfile.householdAllergens,
    )
    storagePort.commitCookedMealBatch(current, slot.slotId, deduction, stamp)
    vmPlanPostToast("Cooked ${recipe.title} and updated Pantry in 1 atomic batch.")
  }

  /** Commits an adjusted `CookedDeductionPayload` (`EC-04`–`EC-06`) + `ElephantMemory` in 1 `O(1)` write. */
  fun confirmCookedOverride(payload: CookedDeductionPayload) = runGuarded {
    val current = mutableUiState.value.snapshot
    val recipe = current.recipesById[payload.recipeId] ?: return@runGuarded
    val stamp = nowIso()
    val deduction = PantryLedgerEngine.applyCookedDeduction(
      current.pantryEntries, recipe, current.ingredients, payload, stamp, current.householdProfile.householdAllergens,
    )
    // Distill learned household BOM ratios (EC-05) into ElephantMemory within the same O(1) commit.
    val nextMemory = uiStateV2DistillCookedOverrideMemory(
      current, recipe, payload.saveOverrideAsHouseholdDefault,
      payload.actualUsedQuantities, deduction.updatedRecipe.householdBomOverrides, stamp,
    )
    storagePort.commitCookedMealBatch(current.copy(elephantMemory = nextMemory), payload.slotId, deduction, stamp)
    val savedNote = if (payload.saveOverrideAsHouseholdDefault) " with saved family default" else ""
    mutableUiState.update {
      it.copy(adjustingSlotId = null).withToast("Cooked ${recipe.title}$savedNote in 1 atomic batch.")
    }
  }

  /** Swaps [slotId] to 100% in-stock [newRecipeId] (`EC-01` & `EC-13` verified) in 1 `O(1)` write. */
  fun quickSwapSlotRecipe(slotId: String, newRecipeId: String) = runGuarded {
    // Verify morning <= 25m cap and allergen safety via MenuPlannerEngine before persisting.
    val state = mutableUiState.value
    val current = state.snapshot
    val replacement = current.recipesById[newRecipeId] ?: return@runGuarded
    val next = uiStateV2TransformBoardContainingSlot(current, state.viewingNextWeek, slotId, nowIso()) { board ->
      MenuPlannerEngine.swapSlotRecipe(
        board, slotId, replacement, current.ingredients, current.householdProfile.householdAllergens,
      )
    }
    storagePort.saveSnapshotBatch(next)
    vmPlanPostToast("Swapped meal to ${replacement.title}.")
  }

  /** Opens `CustomMealSheet` preselected to [initialSlotType] (or inferred from [forSlotId]). */
  fun openCustomMealSheet(initialSlotType: MealSlotType, forSlotId: String?) {
    // Infer target dayIndex and MealSlotType when opened from a specific slot card on Plan.
    val state = mutableUiState.value
    val matched = forSlotId?.let { id ->
      state.activeDisplayedBoard.slots.firstOrNull { it.slotId == id }
        ?: state.snapshot.weeklyBoard.slots.firstOrNull { it.slotId == id }
    }
    mutableUiState.update {
      it.copy(
        adjustingSlotId = null, activeDayIndex = matched?.dayIndex ?: it.activeDayIndex,
        customMealInitialSlotType = matched?.slotType ?: initialSlotType, isCustomMealSheetOpen = true,
      )
    }
  }

  /** Schedules a preset [recipe] onto a single day or `Mon–Fri` in 1 `O(1)` batch write. */
  fun schedulePresetRecipeFromSheet(
    recipe: Recipe,
    slotType: MealSlotType,
    dayIndex: Int,
    applyMondayThroughFriday: Boolean,
  ) = runGuarded {
    // Schedule onto activeDisplayedBoard (This Week or Next Week) in 1 atomic snapshot write.
    val state = mutableUiState.value
    val next = reduceScheduledPresetRecipe(
      state.snapshot, recipe, slotType, dayIndex, applyMondayThroughFriday, nowIso(), state.viewingNextWeek,
    )
    storagePort.saveSnapshotBatch(next)
    val scopeLabel = if (applyMondayThroughFriday) "Mon–Fri" else "Day ${dayIndex + 1}"
    mutableUiState.update {
      it.copy(isCustomMealSheetOpen = false)
        .withToast("Scheduled ${recipe.title} for $scopeLabel ${slotType.name.lowercase()}.")
    }
  }

  /** Creates and schedules a custom household meal (`Single Day` or `Mon–Fri`) in 1 `O(1)` write. */
  fun saveCustomFamilyMeal(draft: CustomFamilyMealDraft) = runGuarded {
    // Create custom recipe, schedule slots, and optionally queue ingredients in 1 O(1) commit.
    val (nextSnapshot, customRecipe) = uiStateV2CreateCustomMealSnapshot(mutableUiState.value.snapshot, draft, nowIso())
    storagePort.saveSnapshotBatch(nextSnapshot)
    mutableUiState.update {
      it.copy(isCustomMealSheetOpen = false, selectedRecipeId = customRecipe.recipeId)
        .withToast("Saved custom meal ${customRecipe.title} in 1 atomic batch.")
    }
  }

  /** Commits a 4-station Weekly Meal Prep Session batch in 1 `O(1)` snapshot write. */
  fun commitWeeklyPrepSession(payload: WeeklyPrepBatchCommitPayload) = runGuarded {
    // Stamp isPreppedAhead and deduct raw prep ingredients across covered slots in 1 atomic write.
    val state = mutableUiState.value
    val current = state.snapshot
    val stamp = nowIso()
    val result = MenuPlannerEngine.applyWeeklyPrepSessionBatch(
      current.weeklyBoard, current.pantryEntries, current.ingredients, state.weeklyPrepSessionPlan, payload, stamp,
    )
    storagePort.saveSnapshotBatch(
      current.copy(
        weeklyBoard = result.updatedBoard, pantryEntries = result.updatedPantry,
        completedPrepTaskIds = current.completedPrepTaskIds + payload.completedTaskIds, updatedAtIso = stamp,
      ),
    )
    vmPlanPostToast("Weekly Prep complete · saved ${result.totalTimeSavedMinutes}m across ${result.stampedSlotIds.size} meals.")
  }

  /** Opens `CleanRecipeReaderScreen` (or filtered Cookbook Grid) for rescue recipes consuming [expiringCard]. */
  fun findRescueRecipesFromPantry(expiringCard: ExpiringPantryCardModel) = mutableUiState.update {
    // Jump directly to top matching rescue dish, or filter Cookbook Grid by ingredient name.
    val firstRescue = expiringCard.matchingRescueRecipes.firstOrNull()
    val msg = when (firstRescue) {
      null -> "Showing cookbook matches for ${expiringCard.catalogItem.displayName}."
      else -> "Found ${expiringCard.matchingRescueRecipes.size} rescue recipes for ${expiringCard.catalogItem.displayName}."
    }
    it.copy(
      activeDestination = TopLevelDestination.RECIPES, selectedRecipeId = firstRescue?.recipeId,
      searchQuery = if (firstRescue != null) "" else expiringCard.catalogItem.displayName,
      isSearchBarExpanded = firstRescue == null,
    ).withToast(msg)
  }

  /** Upserts [item] into catalog and stocks Pantry or queues Smart Grocery in 1 `O(1)` write. */
  fun upsertIngredientItem(
    item: IngredientCatalogItem,
    stockInPantry: Boolean,
    queueInGrocery: Boolean,
    location: StorageLocation? = null,
    expiresInDays: Int? = null,
    toast: String,
  ) = runGuarded {
    // Stamp realTodayIso onto newly stocked entries so expiry countdown badges age accurately.
    val state = mutableUiState.value
    val next = upsertCatalogAndPantrySnapshot(
      state.snapshot, item, stockInPantry, queueInGrocery, location, expiresInDays, nowIso(), state.realTodayIso,
    )
    storagePort.saveSnapshotBatch(next)
    vmPlanPostToast(toast)
  }

  // Emit confirmation or diagnostic toast with monotonically incremented toastEventId.
  private fun vmPlanPostToast(message: String) = mutableUiState.update { it.withToast(message) }
}
