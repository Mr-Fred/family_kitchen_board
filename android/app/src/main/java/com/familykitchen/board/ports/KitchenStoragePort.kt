package com.familykitchen.board.ports

import com.familykitchen.board.domain.AiChatTurn
import com.familykitchen.board.domain.ConsumerHouseholdTemplate
import com.familykitchen.board.domain.CookedDeductionResult
import com.familykitchen.board.domain.CustomGroceryItem
import com.familykitchen.board.domain.DEFAULT_TIMESTAMP_ISO
import com.familykitchen.board.domain.ExpiryStatus
import com.familykitchen.board.domain.FirstRunOnboardingDraft
import com.familykitchen.board.domain.GrocerySortMode
import com.familykitchen.board.domain.HouseholdCurrency
import com.familykitchen.board.domain.HouseholdProfile
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.MealSlotStatus
import com.familykitchen.board.domain.PantryLedgerEntry
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.RestockDelta
import com.familykitchen.board.domain.SeedCatalogDataset
import com.familykitchen.board.domain.WeeklyBoard
import com.familykitchen.board.domain.autoDraftWeeklyBoard
import com.familykitchen.board.domain.batchRestockPantry
import com.familykitchen.board.domain.computeFirstRunOnboardingOutcome
import com.familykitchen.board.domain.indexCatalogById
import com.familykitchen.board.domain.indexPantryById
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

// Hexagonal persistence port enforcing O(1) single-transaction batch snapshot commits.
// Keeps ViewModel and pure domain logic decoupled from DataStore or filesystem adapters.

/**
 * Complete offline-first persisted household state snapshot committed in `O(1)` atomic writes.
 *
 * All multi-ingredient cooked deductions, grocery restocks, weekly station-prep commits, recipe
 * bookmarks/notes, and AI Chef proposal updates serialize a single [KitchenSnapshot] per user
 * batch action so storage adapters never execute `N+1` write loops.
 *
 * @property ingredients Active household ingredient catalog list.
 * @property recipes Active household recipe library (including learned `householdBomOverrides`).
 * @property pantryEntries Current two-tier pantry stock records.
 * @property weeklyBoard Active 7-day (21-slot) household menu board.
 * @property householdProfile Editable 4-member growth-stage, goal, allergen, and life-event profile.
 * @property aiAddedGroceryIds Manually queued or AI-suggested grocery catalog IDs.
 * @property aiTurnHistory Rolling 6-turn AI Chef conversation history.
 * @property completedPrepTaskIds Station task IDs completed in the active weekly prep session.
 * @property updatedAtIso ISO-8601 timestamp of the latest atomic batch write.
 * @property checkedGroceryItemIds Checked catalog `ingredientId`s on the Smart Grocery List.
 * @property customGroceryItems User-added or voice-dictated custom shopping items.
 * @property savedRecipeIds Bookmarked [Recipe.recipeId]s surfaced in Clean Reader & Cookbook.
 * @property recipeNotesByRecipeId Persisted household kitchen notes keyed by `recipeId`.
 * @property checkedReaderIngredientsByRecipeId Checked prep ingredients in Clean Reader per recipe.
 * @property grocerySortMode Active Smart Grocery List ordering strategy.
 * @property rescuedExpiringMealsCount Cumulative meals cooked that rescued expiring stock (`96%`+).
 * @property hasCompletedOnboarding Whether the household has completed or skipped first-run setup.
 */
@Serializable
data class KitchenSnapshot(
  val ingredients: List<IngredientCatalogItem>,
  val recipes: List<Recipe>,
  val pantryEntries: List<PantryLedgerEntry>,
  val weeklyBoard: WeeklyBoard,
  val householdProfile: HouseholdProfile = HouseholdProfile(),
  val aiAddedGroceryIds: Set<String> = emptySet(),
  val aiTurnHistory: List<AiChatTurn> = emptyList(),
  val completedPrepTaskIds: Set<String> = emptySet(),
  val updatedAtIso: String = DEFAULT_TIMESTAMP_ISO,
  val checkedGroceryItemIds: Set<String> = emptySet(),
  val customGroceryItems: List<CustomGroceryItem> = emptyList(),
  val savedRecipeIds: Set<String> = emptySet(),
  val recipeNotesByRecipeId: Map<String, List<String>> = emptyMap(),
  val checkedReaderIngredientsByRecipeId: Map<String, Set<String>> = emptyMap(),
  val grocerySortMode: GrocerySortMode = GrocerySortMode.AISLE_SORTED,
  val rescuedExpiringMealsCount: Int = 12,
  val hasCompletedOnboarding: Boolean = true,
) {
  // Computed lookup maps avoid duplicating normalized collections inside persisted JSON files.
  /** Derived `O(1)` catalog lookup map keyed by `ingredientId`. */
  val catalogById: Map<String, IngredientCatalogItem>
    get() = indexCatalogById(ingredients)

  /** Derived `O(1)` recipe lookup map keyed by `recipeId`. */
  val recipesById: Map<String, Recipe>
    get() = recipes.associateBy { it.recipeId }

  /** Derived `O(1)` pantry lookup map keyed by `ingredientId`. */
  val pantryById: Map<String, PantryLedgerEntry>
    get() = indexPantryById(pantryEntries)

  companion object {
    /**
     * Initializes a clean [KitchenSnapshot] from a validated [SeedCatalogDataset], auto-drafting a
     * 21-slot weekly board calibrated for the default Family-of-4 profile.
     *
     * @param seedCatalog Validated offline seed catalog dataset.
     * @param householdId Household identifier stamped onto the board and profile.
     * @param boardId Initial weekly board identifier.
     * @param weekStartDateIso ISO-8601 Monday start date for the drafted board.
     * @param hasCompletedOnboarding `false` on brand-new cold device installs, `true` by default.
     * @return Initialized [KitchenSnapshot] ready for atomic persistence.
     */
    fun fromSeedCatalog(
      seedCatalog: SeedCatalogDataset,
      householdId: String = "household_family_4",
      boardId: String = "board_week_1",
      weekStartDateIso: String = "2026-10-26",
      hasCompletedOnboarding: Boolean = true,
    ): KitchenSnapshot {
      val profile = HouseholdProfile(householdId = householdId)
      // Draft 21 initial slots respecting Fred's <= 25m morning breakfast cap (EC-01).
      val draftedBoard = autoDraftWeeklyBoard(
        boardId = boardId,
        householdId = householdId,
        weekStartDateIso = weekStartDateIso,
        recipes = seedCatalog.recipes,
        catalog = seedCatalog.ingredients,
        householdAllergens = profile.householdAllergens,
        defaultServings = profile.effectiveTargetServings,
        pantry = seedCatalog.initialPantry,
      )
      val initialSavedIds = seedCatalog.recipes
        .filter { it.isSavedFavorite }
        .map { it.recipeId }
        .toSet()
      return KitchenSnapshot(
        ingredients = seedCatalog.ingredients,
        recipes = seedCatalog.recipes,
        pantryEntries = seedCatalog.initialPantry,
        weeklyBoard = draftedBoard,
        householdProfile = profile,
        savedRecipeIds = initialSavedIds,
        hasCompletedOnboarding = hasCompletedOnboarding,
      )
    }
  }
}

/**
 * Hexagonal persistence port enforcing `O(1)` atomic snapshot loads and batch writes.
 *
 * Production (`LocalJsonKitchenStorage`) and JVM test (`FakeKitchenStorage`) adapters increment
 * [batchWriteCallCount] by exactly `1` on each [saveSnapshotBatch] transaction.
 */
interface KitchenStoragePort {
  // Telemetry counter asserted in JUnit (`batchWriteCallCount == 1`) to ban N+1 write loops.
  /** Monotonic counter of atomic batch writes executed during the adapter's lifetime (`G-04`). */
  val batchWriteCallCount: Int

  /** Hot reactive stream emitting the latest in-memory [KitchenSnapshot] (or `null` pre-load). */
  val snapshotFlow: StateFlow<KitchenSnapshot?>

  /**
   * Loads an existing persisted [KitchenSnapshot] from local storage, or initializes and returns a
   * fresh snapshot from [seedCatalog] when cold-starting for the first time.
   *
   * @param seedCatalog Validated offline seed catalog dataset.
   * @return Loaded or freshly initialized [KitchenSnapshot].
   */
  suspend fun loadOrInitialize(seedCatalog: SeedCatalogDataset): KitchenSnapshot

  /**
   * Atomically persists [snapshot] in a single `O(1)` transaction (temp-write + rename or single
   * DataStore update) and increments [batchWriteCallCount] by `1`.
   *
   * @param snapshot Complete immutable household state snapshot to persist.
   */
  suspend fun saveSnapshotBatch(snapshot: KitchenSnapshot)

  /**
   * Resets persisted household state back to [seedCatalog] in a single atomic write.
   *
   * @param seedCatalog Validated offline seed catalog dataset.
   * @return Freshly restored [KitchenSnapshot].
   */
  suspend fun resetToSeed(seedCatalog: SeedCatalogDataset): KitchenSnapshot

  /**
   * Convenience batch helper committing a cooked meal deduction (`EC-04`–`EC-07`, `EC-10`, `EC-11`)
   * in a single [saveSnapshotBatch] transaction (`batchWriteCallCount += 1`), incrementing
   * [KitchenSnapshot.rescuedExpiringMealsCount] whenever an expiring item is consumed.
   *
   * @param current Current household snapshot before the cooked deduction.
   * @param slotId Cooked board slot ID to transition to `COOKED`.
   * @param deductionResult Pure engine output from `applyCookedDeduction`.
   * @param nowIso Deterministic ISO-8601 commit timestamp.
   * @return Persisted [KitchenSnapshot].
   */
  suspend fun commitCookedMealBatch(
    current: KitchenSnapshot,
    slotId: String,
    deductionResult: CookedDeductionResult,
    nowIso: String = DEFAULT_TIMESTAMP_ISO,
  ): KitchenSnapshot {
    // Update the cooked recipe (for EC-05 household overrides) and slot status in one snapshot.
    // Guarantees that pantry, learned recipe defaults, and board slot persist together atomically.
    val updatedRecipes = current.recipes.map { recipe ->
      when {
        recipe.recipeId == deductionResult.updatedRecipe.recipeId -> deductionResult.updatedRecipe
        else -> recipe
      }
    }
    val updatedSlots = current.weeklyBoard.slots.map { slot ->
      when {
        slot.slotId == slotId -> slot.copy(status = MealSlotStatus.COOKED)
        else -> slot
      }
    }
    // Reward the household Waste Score whenever cooking consumes an EXPIRING_SOON/EXPIRED item.
    val rescuedExpiringItem = current.pantryEntries.any { beforeEntry ->
      val usedQty = deductionResult.deductedQuantities[beforeEntry.ingredientId] ?: 0.0
      usedQty > 0.0 && beforeEntry.effectiveExpiryStatus != ExpiryStatus.FRESH
    }
    val nextRescuedCount = when {
      rescuedExpiringItem -> current.rescuedExpiringMealsCount + 1
      else -> current.rescuedExpiringMealsCount
    }

    val nextSnapshot = current.copy(
      recipes = updatedRecipes,
      pantryEntries = deductionResult.updatedPantry,
      weeklyBoard = current.weeklyBoard.copy(slots = updatedSlots),
      rescuedExpiringMealsCount = nextRescuedCount,
      updatedAtIso = nowIso,
    )
    // Single O(1) batch commit across all affected pantry entries and slot state.
    saveSnapshotBatch(nextSnapshot)
    return nextSnapshot
  }

  /**
   * Convenience batch helper restocking checked Smart Grocery List items in a single
   * [saveSnapshotBatch] transaction (`batchWriteCallCount += 1`) and clearing restocked IDs from
   * manual and checked grocery sets.
   *
   * @param current Current household snapshot before restocking.
   * @param deltas Checked whole-package [RestockDelta]s to merge.
   * @param nowIso Deterministic ISO-8601 commit timestamp.
   * @return Persisted [KitchenSnapshot].
   */
  suspend fun commitGroceryRestockBatch(
    current: KitchenSnapshot,
    deltas: List<RestockDelta>,
    nowIso: String = DEFAULT_TIMESTAMP_ISO,
  ): KitchenSnapshot {
    // Merge all checked restock deltas in one pure pass and clear fulfilled manual/checked IDs.
    val restockedPantry = batchRestockPantry(current.pantryEntries, current.ingredients, deltas, nowIso)
    val restockedIds = deltas.map { it.ingredientId }.toSet()
    val nextSnapshot = current.copy(
      pantryEntries = restockedPantry,
      aiAddedGroceryIds = current.aiAddedGroceryIds - restockedIds,
      checkedGroceryItemIds = current.checkedGroceryItemIds - restockedIds,
      updatedAtIso = nowIso,
    )
    // Persist the entire grocery restock in one atomic write regardless of checked item count.
    saveSnapshotBatch(nextSnapshot)
    return nextSnapshot
  }

  /**
   * Toggles the checked state of a Smart Grocery List catalog item or [CustomGroceryItem] in a
   * single `O(1)` [saveSnapshotBatch] write.
   *
   * @param current Current household snapshot.
   * @param itemId Catalog `ingredientId` or [CustomGroceryItem.customId].
   * @param nowIso Deterministic ISO-8601 commit timestamp.
   * @return Persisted [KitchenSnapshot].
   */
  suspend fun toggleGroceryItemChecked(
    current: KitchenSnapshot,
    itemId: String,
    nowIso: String = DEFAULT_TIMESTAMP_ISO,
  ): KitchenSnapshot {
    // Route custom user items separately from catalog IDs so both checklists share one helper.
    val matchesCustom = current.customGroceryItems.any { it.customId == itemId }
    val nextSnapshot = when {
      matchesCustom -> current.copy(
        customGroceryItems = current.customGroceryItems.map { custom ->
          if (custom.customId == itemId) custom.copy(isChecked = !custom.isChecked) else custom
        },
        updatedAtIso = nowIso,
      )
      itemId in current.checkedGroceryItemIds -> current.copy(
        checkedGroceryItemIds = current.checkedGroceryItemIds - itemId,
        updatedAtIso = nowIso,
      )
      else -> current.copy(
        checkedGroceryItemIds = current.checkedGroceryItemIds + itemId,
        updatedAtIso = nowIso,
      )
    }
    // Commit checked toggle in a single O(1) snapshot write.
    saveSnapshotBatch(nextSnapshot)
    return nextSnapshot
  }

  /**
   * Appends or updates a user-added [CustomGroceryItem] on the Smart Grocery List in one `O(1)`
   * [saveSnapshotBatch] write.
   *
   * @param current Current household snapshot.
   * @param item Custom shopping item to add.
   * @param nowIso Deterministic ISO-8601 commit timestamp.
   * @return Persisted [KitchenSnapshot].
   */
  suspend fun addCustomGroceryItem(
    current: KitchenSnapshot,
    item: CustomGroceryItem,
    nowIso: String = DEFAULT_TIMESTAMP_ISO,
  ): KitchenSnapshot {
    // Upsert by customId to avoid duplicate rows when editing or re-dictating an item.
    val merged = current.customGroceryItems.filterNot { it.customId == item.customId } + item
    val nextSnapshot = current.copy(customGroceryItems = merged, updatedAtIso = nowIso)
    saveSnapshotBatch(nextSnapshot)
    return nextSnapshot
  }

  /**
   * Clears all checked catalog grocery IDs and removes checked [CustomGroceryItem]s in one `O(1)`
   * [saveSnapshotBatch] write.
   *
   * @param current Current household snapshot.
   * @param nowIso Deterministic ISO-8601 commit timestamp.
   * @return Persisted [KitchenSnapshot].
   */
  suspend fun clearCheckedGroceryItems(
    current: KitchenSnapshot,
    nowIso: String = DEFAULT_TIMESTAMP_ISO,
  ): KitchenSnapshot {
    // Sweep checked catalog IDs and checked custom rows together in one O(1) write.
    val nextSnapshot = current.copy(
      checkedGroceryItemIds = emptySet(),
      customGroceryItems = current.customGroceryItems.filterNot { it.isChecked },
      updatedAtIso = nowIso,
    )
    saveSnapshotBatch(nextSnapshot)
    return nextSnapshot
  }

  /**
   * Toggles bookmark favorite status for [recipeId] across both [KitchenSnapshot.savedRecipeIds]
   * and [Recipe.isSavedFavorite] in one `O(1)` [saveSnapshotBatch] write.
   *
   * @param current Current household snapshot.
   * @param recipeId Target [Recipe.recipeId] to bookmark or unbookmark.
   * @param nowIso Deterministic ISO-8601 commit timestamp.
   * @return Persisted [KitchenSnapshot].
   */
  suspend fun toggleSavedRecipe(
    current: KitchenSnapshot,
    recipeId: String,
    nowIso: String = DEFAULT_TIMESTAMP_ISO,
  ): KitchenSnapshot {
    // Keep savedRecipeIds and Recipe.isSavedFavorite synchronized for O(1) and card-level reads.
    val isNowSaved = recipeId !in current.savedRecipeIds
    val nextSavedIds = when {
      isNowSaved -> current.savedRecipeIds + recipeId
      else -> current.savedRecipeIds - recipeId
    }
    val nextRecipes = current.recipes.map { recipe ->
      if (recipe.recipeId == recipeId) recipe.copy(isSavedFavorite = isNowSaved) else recipe
    }
    val nextSnapshot = current.copy(
      recipes = nextRecipes,
      savedRecipeIds = nextSavedIds,
      updatedAtIso = nowIso,
    )
    saveSnapshotBatch(nextSnapshot)
    return nextSnapshot
  }

  /**
   * Appends a household kitchen note to [recipeId] inside [KitchenSnapshot.recipeNotesByRecipeId]
   * in one `O(1)` [saveSnapshotBatch] write.
   *
   * @param current Current household snapshot.
   * @param recipeId Target [Recipe.recipeId].
   * @param noteText Household note text to persist.
   * @param nowIso Deterministic ISO-8601 commit timestamp.
   * @return Persisted [KitchenSnapshot].
   */
  suspend fun addRecipeKitchenNote(
    current: KitchenSnapshot,
    recipeId: String,
    noteText: String,
    nowIso: String = DEFAULT_TIMESTAMP_ISO,
  ): KitchenSnapshot {
    // Ignore empty whitespace notes so storage never increments batchWriteCallCount on no-ops.
    val cleanNote = noteText.trim()
    if (cleanNote.isEmpty()) return current
    val existingNotes = current.recipeNotesByRecipeId[recipeId].orEmpty()
    val updatedMap = current.recipeNotesByRecipeId + (recipeId to (existingNotes + cleanNote))
    val nextSnapshot = current.copy(recipeNotesByRecipeId = updatedMap, updatedAtIso = nowIso)
    saveSnapshotBatch(nextSnapshot)
    return nextSnapshot
  }

  /**
   * Toggles (or clears all) checked ingredient rows for [recipeId] inside Clean Recipe Reader in
   * one `O(1)` [saveSnapshotBatch] write.
   *
   * @param current Current household snapshot.
   * @param recipeId Target [Recipe.recipeId].
   * @param ingredientId Ingredient ID to toggle (ignored when [clearAllForRecipe] is `true`).
   * @param clearAllForRecipe Unchecks all ingredients on [recipeId] when `true`.
   * @param nowIso Deterministic ISO-8601 commit timestamp.
   * @return Persisted [KitchenSnapshot].
   */
  suspend fun toggleReaderIngredientChecked(
    current: KitchenSnapshot,
    recipeId: String,
    ingredientId: String = "",
    clearAllForRecipe: Boolean = false,
    nowIso: String = DEFAULT_TIMESTAMP_ISO,
  ): KitchenSnapshot {
    val currentSet = current.checkedReaderIngredientsByRecipeId[recipeId].orEmpty()
    val nextSet = when {
      clearAllForRecipe -> emptySet()
      ingredientId.isBlank() -> currentSet
      ingredientId in currentSet -> currentSet - ingredientId
      else -> currentSet + ingredientId
    }
    val nextMap = when {
      nextSet.isEmpty() -> current.checkedReaderIngredientsByRecipeId - recipeId
      else -> current.checkedReaderIngredientsByRecipeId + (recipeId to nextSet)
    }
    val nextSnapshot = current.copy(checkedReaderIngredientsByRecipeId = nextMap, updatedAtIso = nowIso)
    saveSnapshotBatch(nextSnapshot)
    return nextSnapshot
  }

  /**
   * Updates the active [GrocerySortMode] in a single `O(1)` [saveSnapshotBatch] write.
   *
   * @param current Current household snapshot.
   * @param sortMode Selected Smart Grocery List ordering strategy.
   * @param nowIso Deterministic ISO-8601 commit timestamp.
   * @return Persisted [KitchenSnapshot].
   */
  suspend fun setGrocerySortMode(
    current: KitchenSnapshot,
    sortMode: GrocerySortMode,
    nowIso: String = DEFAULT_TIMESTAMP_ISO,
  ): KitchenSnapshot {
    val nextSnapshot = current.copy(grocerySortMode = sortMode, updatedAtIso = nowIso)
    saveSnapshotBatch(nextSnapshot)
    return nextSnapshot
  }

  /**
   * Switches the household to a [ConsumerHouseholdTemplate] (for example, Solo Cook, Active Couple,
   * Parents + Baby, Family of 4, or Multi-Gen 6) and rescales `PLANNED` board slots in one `O(1)`
   * [saveSnapshotBatch] write.
   *
   * @param current Current household snapshot.
   * @param template Selected consumer household onboarding template.
   * @param householdName Custom kitchen display name.
   * @param morningLeadName Display name for the morning cook.
   * @param eveningLeadName Display name for the midday/evening cook.
   * @param nowIso Deterministic ISO-8601 commit timestamp.
   * @return Persisted [KitchenSnapshot].
   */
  suspend fun applyConsumerHouseholdTemplate(
    current: KitchenSnapshot,
    template: ConsumerHouseholdTemplate,
    householdName: String = current.householdProfile.householdName,
    morningLeadName: String = current.householdProfile.morningLeadName,
    eveningLeadName: String = current.householdProfile.eveningLeadName,
    nowIso: String = DEFAULT_TIMESTAMP_ISO,
  ): KitchenSnapshot {
    // Preserve active allergens and life events while adapting member goals and serving baseline.
    val templated = HouseholdProfile.fromConsumerTemplate(
      template = template,
      householdName = householdName,
      morningLeadName = morningLeadName,
      eveningLeadName = eveningLeadName,
      currency = current.householdProfile.currency,
      marketRegionLabel = current.householdProfile.marketRegionLabel,
    ).copy(
      householdAllergens = current.householdProfile.householdAllergens,
      activeLifeEvents = current.householdProfile.activeLifeEvents,
    )
    val rescaledSlots = current.weeklyBoard.slots.map { slot ->
      when (slot.status) {
        MealSlotStatus.PLANNED -> slot.copy(targetServings = templated.effectiveTargetServings)
        else -> slot
      }
    }
    val nextSnapshot = current.copy(
      householdProfile = templated,
      weeklyBoard = current.weeklyBoard.copy(slots = rescaledSlots),
      updatedAtIso = nowIso,
    )
    saveSnapshotBatch(nextSnapshot)
    return nextSnapshot
  }

  /**
   * Updates the household's active [HouseholdCurrency] (`PHP` `₱` default, `USD`, `EUR`, `GBP`,
   * `XOF`) and [marketRegionLabel] in one `O(1)` [saveSnapshotBatch] write.
   *
   * @param current Current household snapshot.
   * @param currency Selected household display currency.
   * @param marketRegionLabel Local market sourcing label (defaults to `"Antipolo & Marikina, PH"`).
   * @param nowIso Deterministic ISO-8601 commit timestamp.
   * @return Persisted [KitchenSnapshot].
   */
  suspend fun setHouseholdCurrencyAndRegion(
    current: KitchenSnapshot,
    currency: HouseholdCurrency,
    marketRegionLabel: String = current.householdProfile.marketRegionLabel,
    nowIso: String = DEFAULT_TIMESTAMP_ISO,
  ): KitchenSnapshot {
    // Trim region label and fall back to Antipolo & Marikina when cleared by the user.
    val cleanRegion = marketRegionLabel.trim().ifEmpty { "Antipolo & Marikina, PH" }
    val updatedProfile = current.householdProfile.copy(
      currency = currency,
      marketRegionLabel = cleanRegion,
    )
    val nextSnapshot = current.copy(householdProfile = updatedProfile, updatedAtIso = nowIso)
    saveSnapshotBatch(nextSnapshot)
    return nextSnapshot
  }

  /**
   * Commits a completed 4-step [FirstRunOnboardingDraft] (from a brand-new install or replay) in a
   * single `O(1)` [saveSnapshotBatch] transaction (`batchWriteCallCount += 1`), marking
   * [KitchenSnapshot.hasCompletedOnboarding] `true`.
   *
   * @param current Current household snapshot before applying onboarding choices.
   * @param seedPantry Canonical 44-item demo pantry stock for `SAMPLE_DEMO_KITCHEN` mode.
   * @param draft Validated 4-step user onboarding selections.
   * @param nowIso Deterministic ISO-8601 commit timestamp.
   * @return Persisted [KitchenSnapshot].
   */
  suspend fun commitFirstRunOnboardingBatch(
    current: KitchenSnapshot,
    seedPantry: List<PantryLedgerEntry>,
    draft: FirstRunOnboardingDraft,
    nowIso: String = DEFAULT_TIMESTAMP_ISO,
  ): KitchenSnapshot {
    // Compute pure profile, starting pantry, and 21-slot weekly board in one deterministic pass.
    val outcome = computeFirstRunOnboardingOutcome(
      catalog = current.ingredients,
      recipes = current.recipes,
      seedPantry = seedPantry,
      currentBoard = current.weeklyBoard,
      draft = draft,
      nowIso = nowIso,
    )
    val updatedSnapshot = current.copy(
      householdProfile = outcome.householdProfile,
      pantryEntries = outcome.pantryEntries,
      weeklyBoard = outcome.weeklyBoard,
      rescuedExpiringMealsCount = outcome.rescuedExpiringMealsCount,
      aiAddedGroceryIds = emptySet(),
      aiTurnHistory = emptyList(),
      completedPrepTaskIds = emptySet(),
      checkedGroceryItemIds = emptySet(),
      customGroceryItems = emptyList(),
      hasCompletedOnboarding = true,
      updatedAtIso = nowIso,
    )
    // Persist the entire onboarding configuration in a single O(1) atomic snapshot write.
    saveSnapshotBatch(updatedSnapshot)
    return updatedSnapshot
  }

  /**
   * Skips guided onboarding into the pre-stocked 44-item Sample Kitchen in a single `O(1)`
   * [saveSnapshotBatch] transaction (`batchWriteCallCount += 1`).
   *
   * @param current Current household snapshot.
   * @param seedPantry Canonical 44-item demo pantry stock from `seed_catalog.json`.
   * @param nowIso Deterministic ISO-8601 commit timestamp.
   * @return Persisted demo [KitchenSnapshot] with `hasCompletedOnboarding = true`.
   */
  suspend fun skipFirstRunOnboardingBatch(
    current: KitchenSnapshot,
    seedPantry: List<PantryLedgerEntry>,
    nowIso: String = DEFAULT_TIMESTAMP_ISO,
  ): KitchenSnapshot {
    // Stock the canonical 44-item sample kitchen and mark first-run setup completed atomically.
    val demoSnapshot = current.copy(
      pantryEntries = seedPantry,
      rescuedExpiringMealsCount = 12,
      hasCompletedOnboarding = true,
      updatedAtIso = nowIso,
    )
    saveSnapshotBatch(demoSnapshot)
    return demoSnapshot
  }
}
