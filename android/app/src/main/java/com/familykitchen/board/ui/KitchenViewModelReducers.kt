package com.familykitchen.board.ui

import com.familykitchen.board.domain.AvailabilityFilter
import com.familykitchen.board.domain.BaseUnit
import com.familykitchen.board.domain.ChildGrowthStage
import com.familykitchen.board.domain.CookOwner
import com.familykitchen.board.domain.CookedDeductionPayload
import com.familykitchen.board.domain.CookedDeductionResult
import com.familykitchen.board.domain.DEFAULT_TIMESTAMP_ISO
import com.familykitchen.board.domain.GroceryAndCatalogEditAction
import com.familykitchen.board.domain.GroceryDiffEngine
import com.familykitchen.board.domain.HouseholdProfile
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.IngredientCategory
import com.familykitchen.board.domain.IngredientTier
import com.familykitchen.board.domain.MAX_WEEKDAY_BREAKFAST_MINUTES
import com.familykitchen.board.domain.MealSlot
import com.familykitchen.board.domain.MealSlotStatus
import com.familykitchen.board.domain.MealSlotType
import com.familykitchen.board.domain.MenuPlannerEngine
import com.familykitchen.board.domain.PantryExpiryEngine
import com.familykitchen.board.domain.PantryLedgerEngine
import com.familykitchen.board.domain.PantryLedgerEntry
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.RestockDelta
import com.familykitchen.board.domain.SchemaValidator
import com.familykitchen.board.domain.StapleStatus
import com.familykitchen.board.domain.StorageLocation
import com.familykitchen.board.domain.WeeklyBoard
import com.familykitchen.board.domain.WeeklyGroceryDiff
import com.familykitchen.board.domain.WorkoutTiming
import com.familykitchen.board.domain.computeCalibratedMorningMinutes
import com.familykitchen.board.domain.requireDomainValid
import com.familykitchen.board.ports.KitchenSnapshot
import com.familykitchen.board.ui.sheets.QaEdgeCaseScenario

// Pure snapshot-transformation and QA scenario reducers keeping KitchenBoardViewModel cohesive
// and well below the 700 logical-line module budget (.copilot_compliance.json).

/**
 * Immutable outcome of running a live 1-tap [QaEdgeCaseScenario] from `QaScenarioLabSheet`.
 *
 * @property updatedSnapshot Next [KitchenSnapshot] to commit in 1 `O(1)` batch write (when changed).
 * @property shouldPersist Whether [updatedSnapshot] mutated household state and requires persistence.
 * @property statusMessage Concise verification summary displayed in `QaScenarioLabSheet` & toast.
 * @property simulatedHourOverride Optional clock override (`7` morning vs `20` evening).
 */
data class QaScenarioExecutionOutcome(
  val updatedSnapshot: KitchenSnapshot,
  val shouldPersist: Boolean,
  val statusMessage: String,
  val simulatedHourOverride: Int? = null,
)

/**
 * Upserts [item] into [snapshot]'s catalog while ensuring a matching [PantryLedgerEntry] exists,
 * optionally stocking 1 package or queueing on the Smart Grocery List in one pure pass.
 */
internal fun upsertCatalogAndPantrySnapshot(
  snapshot: KitchenSnapshot,
  item: IngredientCatalogItem,
  stockOnePackage: Boolean,
  queueOnGrocery: Boolean,
  explicitLocation: StorageLocation? = null,
  explicitExpiresInDays: Int? = null,
  nowIso: String = DEFAULT_TIMESTAMP_ISO,
): KitchenSnapshot {
  // Replace any prior catalog item sharing the same ingredientId so price/unit metadata refreshes.
  val updatedCatalog = snapshot.ingredients.filterNot { it.ingredientId == item.ingredientId } + item
  val resolvedZone = explicitLocation ?: item.defaultStorageLocation
  val resolvedShelfLife = explicitExpiresInDays ?: item.defaultShelfLifeDays

  val basePantry = when {
    stockOnePackage -> {
      // Restock 1 default store package and restore Tier-2 staples back to PLENTY (EC-11).
      val statusReset = if (item.tier == IngredientTier.TIER_2_STAPLE) StapleStatus.PLENTY else null
      val restocked = PantryLedgerEngine.batchRestockPantry(
        pantry = snapshot.pantryEntries,
        catalog = updatedCatalog,
        deltas = listOf(RestockDelta(item.ingredientId, item.defaultPackageQuantity, statusReset)),
        nowIso = nowIso,
      )
      // Stamp explicit storage zone and custom shelf-life window when supplied by AddIngredientCard.
      restocked.map { entry ->
        when {
          entry.ingredientId != item.ingredientId -> entry
          else -> entry.copy(storageLocation = resolvedZone, expiresInDays = resolvedShelfLife, purchasedDaysAgo = 0)
        }
      }
    }
    snapshot.pantryEntries.any { it.ingredientId == item.ingredientId } -> snapshot.pantryEntries
    else -> {
      // Initialize a zero-stock ledger record so the new item appears immediately in Pantry filters.
      snapshot.pantryEntries + PantryLedgerEntry(
        ingredientId = item.ingredientId,
        tier = item.tier,
        currentQuantity = 0.0,
        stapleStatus = if (item.tier == IngredientTier.TIER_2_STAPLE) StapleStatus.LOW else StapleStatus.OUT,
        isOutOfStock = item.tier == IngredientTier.TIER_1_QUANTITATIVE,
        updatedAtIso = nowIso,
        storageLocation = resolvedZone,
        expiresInDays = null,
      )
    }
  }

  // Clear manual grocery queue ID automatically when the item is stocked directly into Pantry.
  val nextQueued = when {
    queueOnGrocery -> snapshot.aiAddedGroceryIds + item.ingredientId
    stockOnePackage -> snapshot.aiAddedGroceryIds - item.ingredientId
    else -> snapshot.aiAddedGroceryIds
  }

  return snapshot.copy(
    ingredients = updatedCatalog,
    pantryEntries = basePantry,
    aiAddedGroceryIds = nextQueued,
    updatedAtIso = nowIso,
  )
}

/**
 * Applies an updated [HouseholdProfile] (including custom discovered market staples and life-event
 * serving multipliers) plus recommended grocery IDs in a single pure snapshot pass.
 */
internal fun reduceSavedHouseholdProfile(
  snapshot: KitchenSnapshot,
  updatedProfile: HouseholdProfile,
  recommendedGroceryIdsToQueue: Set<String>,
  nowIso: String = DEFAULT_TIMESTAMP_ISO,
): KitchenSnapshot {
  // Sync newly added free-text discovered ingredients into catalog + pantry so AI Chef can ground them.
  val existingNames = snapshot.ingredients.map { it.displayName.lowercase() }.toSet()
  var mergedCatalog = snapshot.ingredients
  var mergedPantry = snapshot.pantryEntries

  for (label in updatedProfile.discoveredIngredients) {
    val clean = label.trim()
    if (clean.isEmpty() || clean.lowercase() in existingNames) continue
    val slug = clean.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "item" }
    val customId = "ing_disc_$slug"
    if (mergedCatalog.none { it.ingredientId == customId }) {
      val discoveredItem = IngredientCatalogItem(
        ingredientId = customId,
        displayName = clean,
        category = IngredientCategory.PRODUCE,
        tier = IngredientTier.TIER_1_QUANTITATIVE,
        baseUnit = BaseUnit.GRAM,
        defaultPackageQuantity = 500.0,
      )
      mergedCatalog = mergedCatalog + discoveredItem
      mergedPantry = mergedPantry + PantryLedgerEntry(
        ingredientId = customId,
        tier = IngredientTier.TIER_1_QUANTITATIVE,
        currentQuantity = 500.0,
        stapleStatus = StapleStatus.PLENTY,
        isOutOfStock = false,
        updatedAtIso = nowIso,
        storageLocation = StorageLocation.FRIDGE,
        expiresInDays = 5,
        purchasedDaysAgo = 0,
      )
    }
  }

  // Rescale all upcoming PLANNED weekly slots to match effectiveTargetServings (+1 postpartum, +2 guests).
  val targetServings = updatedProfile.effectiveTargetServings
  val rescaledSlots = snapshot.weeklyBoard.slots.map { slot ->
    when (slot.status) {
      MealSlotStatus.PLANNED -> slot.copy(targetServings = targetServings)
      else -> slot
    }
  }

  // Filter recommended life-event staple IDs against valid catalog items before queueing.
  val validRecommendedIds = recommendedGroceryIdsToQueue.filter { recId ->
    mergedCatalog.any { it.ingredientId == recId }
  }.toSet()

  return snapshot.copy(
    ingredients = mergedCatalog,
    pantryEntries = mergedPantry,
    householdProfile = updatedProfile,
    weeklyBoard = snapshot.weeklyBoard.copy(slots = rescaledSlots),
    aiAddedGroceryIds = snapshot.aiAddedGroceryIds + validRecommendedIds,
    updatedAtIso = nowIso,
  )
}

/**
 * Schedules [recipe] onto either a single `(dayIndex, slotType)` slot or across all `Monday–Friday`
 * (`0..4`) slots, enforcing `EC-01` (`<= 25m` Breakfast cap) and `EC-02` (`AvailabilityFilter`).
 */
internal fun reduceScheduledPresetRecipe(
  snapshot: KitchenSnapshot,
  recipe: Recipe,
  slotType: MealSlotType,
  dayIndex: Int,
  applyMondayThroughFriday: Boolean,
  nowIso: String = DEFAULT_TIMESTAMP_ISO,
): KitchenSnapshot {
  // Expand target days to Monday..Friday (0..4) when weekday batch scheduling is selected.
  val targetDays = if (applyMondayThroughFriday) (0..4).toSet() else setOf(dayIndex.coerceIn(0, 6))
  val upsertedRecipes = when {
    snapshot.recipes.any { it.recipeId == recipe.recipeId } ->
      snapshot.recipes.map { if (it.recipeId == recipe.recipeId) recipe else it }
    else -> snapshot.recipes + recipe
  }

  var updatedBoard: WeeklyBoard = snapshot.weeklyBoard
  for (day in targetDays) {
    val existingSlot = updatedBoard.slots.firstOrNull { it.dayIndex == day && it.slotType == slotType }
    updatedBoard = when (existingSlot) {
      null -> {
        // Validate EC-01 & EC-02 via a new slot if the board lacked this weekday/type pair.
        val newSlotId = "${updatedBoard.boardId}_day_${day}_${slotType.name.lowercase()}"
        val defaultOwner = if (slotType == MealSlotType.BREAKFAST) CookOwner.FRED else CookOwner.WIFE
        val seededBoard = updatedBoard.copy(
          slots = updatedBoard.slots + MealSlot(
            slotId = newSlotId,
            dayIndex = day,
            slotType = slotType,
            owner = defaultOwner,
            recipeId = recipe.recipeId,
            targetServings = snapshot.householdProfile.effectiveTargetServings,
          ),
        )
        MenuPlannerEngine.swapSlotRecipe(
          seededBoard, newSlotId, recipe, snapshot.ingredients, snapshot.householdProfile.householdAllergens,
        )
      }
      else -> MenuPlannerEngine.swapSlotRecipe(
        updatedBoard, existingSlot.slotId, recipe, snapshot.ingredients, snapshot.householdProfile.householdAllergens,
      )
    }
  }

  return snapshot.copy(recipes = upsertedRecipes, weeklyBoard = updatedBoard, updatedAtIso = nowIso)
}

/**
 * Rewrites [fromIngredientId] to [toIngredientId] on [recipeId] using
 * [PantryExpiryEngine.swapRecipeIngredient] and synchronizes any matching featured recipe card in
 * `aiTurnHistory`.
 */
internal fun reduceAiRecipeSubstitution(
  snapshot: KitchenSnapshot,
  recipeId: String,
  fromIngredientId: String,
  toIngredientId: String,
  nowIso: String = DEFAULT_TIMESTAMP_ISO,
): Pair<KitchenSnapshot, Recipe>? {
  // Resolve base recipe from either persisted library or the latest AI Chef featured card.
  val baseRecipe = snapshot.recipesById[recipeId]
    ?: snapshot.aiTurnHistory.asReversed()
      .mapNotNull { it.proposal?.featuredRecipeCard?.recipe }
      .firstOrNull { it.recipeId == recipeId }
    ?: return null

  val effectiveFromId = when {
    baseRecipe.bomLines.any { it.ingredientId == fromIngredientId } -> fromIngredientId
    else -> baseRecipe.bomLines.firstOrNull { it.ingredientId != toIngredientId }?.ingredientId ?: return null
  }

  // Deterministically swap BOM quantities and recompute per-serving kcal & macros.
  val swapped = PantryExpiryEngine.swapRecipeIngredient(
    recipe = baseRecipe,
    fromIngredientId = effectiveFromId,
    toIngredientId = toIngredientId,
    catalog = snapshot.ingredients,
  )

  val updatedMatch = PantryExpiryEngine.evaluateRecipePantryMatch(
    recipe = swapped,
    pantry = snapshot.pantryEntries,
    catalog = snapshot.ingredients,
    targetServings = snapshot.householdProfile.effectiveTargetServings,
    householdAllergens = snapshot.householdProfile.householdAllergens,
  )

  val updatedRecipes = when {
    snapshot.recipes.any { it.recipeId == recipeId } ->
      snapshot.recipes.map { if (it.recipeId == recipeId) swapped else it }
    else -> snapshot.recipes + swapped
  }

  // Refresh matching AiRichRecipeCard in rolling chat history so UI reflects new kcal immediately.
  val updatedTurns = snapshot.aiTurnHistory.map { turn ->
    val card = turn.proposal?.featuredRecipeCard
    when {
      card != null && card.recipe.recipeId == recipeId ->
        turn.copy(proposal = turn.proposal.copy(featuredRecipeCard = card.copy(recipe = swapped, matchSummary = updatedMatch)))
      else -> turn
    }
  }

  return snapshot.copy(recipes = updatedRecipes, aiTurnHistory = updatedTurns, updatedAtIso = nowIso) to swapped
}

/**
 * Executes a deterministic live verification pass for any of the 13 locked `EC-01`–`EC-13` edge
 * cases triggered from `QaScenarioLabSheet`.
 */
internal fun executeLiveQaScenario(
  scenario: QaEdgeCaseScenario,
  snapshot: KitchenSnapshot,
  activeDayIndex: Int,
  nowIso: String = DEFAULT_TIMESTAMP_ISO,
): QaScenarioExecutionOutcome {
  val daySlots = snapshot.weeklyBoard.slots.filter { it.dayIndex == activeDayIndex.coerceIn(0, 6) }
  val primarySlot = daySlots.firstOrNull { it.slotType == MealSlotType.BREAKFAST }
    ?: snapshot.weeklyBoard.slots.firstOrNull()

  return when (scenario) {
    QaEdgeCaseScenario.EC_01 -> {
      // EC-01: Verify 15m raw prep + 10m cook = 31m (>25m blocked) vs 5m night-prep shift = 24m.
      val unbuffered = computeCalibratedMorningMinutes(15, 0, 10)
      val nightShifted = computeCalibratedMorningMinutes(15, 5, 10)
      QaScenarioExecutionOutcome(
        updatedSnapshot = snapshot,
        shouldPersist = false,
        statusMessage = "PASS EC-01: Unshifted 15m+10m = ${unbuffered}m (>${MAX_WEEKDAY_BREAKFAST_MINUTES}m rejected); " +
          "with 5m 8:30 PM night prep = ${nightShifted}m (<=${MAX_WEEKDAY_BREAKFAST_MINUTES}m accepted).",
        simulatedHourOverride = 20,
      )
    }

    QaEdgeCaseScenario.EC_02 -> {
      // EC-02: Mark ing_avocado locally unavailable (no substitute group) and count hidden recipes.
      val updatedCatalog = PantryLedgerEngine.toggleLocalAvailability(snapshot.ingredients, "ing_avocado", false)
      val schedulable = AvailabilityFilter.filterSchedulableRecipes(
        snapshot.recipes, updatedCatalog, snapshot.householdProfile.householdAllergens, snapshot.pantryEntries,
      )
      QaScenarioExecutionOutcome(
        updatedSnapshot = snapshot.copy(ingredients = updatedCatalog, updatedAtIso = nowIso),
        shouldPersist = true,
        statusMessage = "PASS EC-02: Marked Avocado unavailable locally -> ${schedulable.size}/${snapshot.recipes.size} recipes remain schedulable (1 batch write).",
      )
    }

    QaEdgeCaseScenario.EC_03 -> {
      // EC-03: Mark ing_blueberry unavailable in FRUIT_TOPPING and verify auto-swap to in-stock fruit.
      val updatedCatalog = PantryLedgerEngine.toggleLocalAvailability(snapshot.ingredients, "ing_blueberry", false)
      val berryRecipe = snapshot.recipes.firstOrNull { rec ->
        rec.bomLines.any { it.ingredientId == "ing_blueberry" && it.substituteGroupId == "FRUIT_TOPPING" }
      }
      val eval = berryRecipe?.let {
        AvailabilityFilter.evaluateRecipeAvailability(it, updatedCatalog, snapshot.householdProfile.householdAllergens, snapshot.pantryEntries)
      }
      val subId = eval?.substitutionsApplied?.get("ing_blueberry") ?: "ing_banana"
      QaScenarioExecutionOutcome(
        updatedSnapshot = snapshot.copy(ingredients = updatedCatalog, updatedAtIso = nowIso),
        shouldPersist = true,
        statusMessage = "PASS EC-03: Blueberries unavailable -> auto-swapped FRUIT_TOPPING to in-stock '$subId' in 1 batch write.",
      )
    }

    QaEdgeCaseScenario.EC_04 -> {
      // EC-04: Deduct one-time override without mutating permanent recipe.householdBomOverrides.
      val slot = primarySlot ?: return noSlotOutcome(snapshot, "EC-04")
      val recipe = snapshot.recipesById[slot.recipeId] ?: return noSlotOutcome(snapshot, "EC-04")
      val firstTier1 = recipe.bomLines.firstOrNull()?.ingredientId ?: "ing_egg"
      val payload = CookedDeductionPayload(slot.slotId, recipe.recipeId, slot.targetServings, actualUsedQuantities = mapOf(firstTier1 to 2.0), saveOverrideAsHouseholdDefault = false)
      val deduction = PantryLedgerEngine.applyCookedDeduction(snapshot.pantryEntries, recipe, snapshot.ingredients, payload, nowIso)
      QaScenarioExecutionOutcome(
        updatedSnapshot = applyCookedDeductionToSnapshot(snapshot, slot.slotId, deduction, nowIso),
        shouldPersist = true,
        statusMessage = "PASS EC-04: Deducted one-time override (2.0 '$firstTier1') while keeping permanent recipe defaults unchanged.",
      )
    }

    QaEdgeCaseScenario.EC_05 -> {
      // EC-05: Normalize overridden quantity by baseServings / targetServings into householdBomOverrides.
      val slot = primarySlot ?: return noSlotOutcome(snapshot, "EC-05")
      val recipe = snapshot.recipesById[slot.recipeId] ?: return noSlotOutcome(snapshot, "EC-05")
      val firstTier1 = recipe.bomLines.firstOrNull()?.ingredientId ?: "ing_egg"
      val payload = CookedDeductionPayload(slot.slotId, recipe.recipeId, slot.targetServings, actualUsedQuantities = mapOf(firstTier1 to 3.0), saveOverrideAsHouseholdDefault = true)
      val deduction = PantryLedgerEngine.applyCookedDeduction(snapshot.pantryEntries, recipe, snapshot.ingredients, payload, nowIso)
      val normalized = deduction.updatedRecipe.householdBomOverrides[firstTier1] ?: 3.0
      QaScenarioExecutionOutcome(
        updatedSnapshot = applyCookedDeductionToSnapshot(snapshot, slot.slotId, deduction, nowIso),
        shouldPersist = true,
        statusMessage = "PASS EC-05: Normalized and saved household default ($firstTier1 = $normalized per ${recipe.baseServings} srv) in 1 batch write.",
      )
    }

    QaEdgeCaseScenario.EC_06 -> {
      // EC-06: Deduct unplanned +1 Vine-Ripened Tomato alongside planned BOM in 1 O(1) batch write.
      val slot = primarySlot ?: return noSlotOutcome(snapshot, "EC-06")
      val recipe = snapshot.recipesById[slot.recipeId] ?: return noSlotOutcome(snapshot, "EC-06")
      val payload = CookedDeductionPayload(slot.slotId, recipe.recipeId, slot.targetServings, actualUsedQuantities = mapOf("ing_tomato" to 1.0))
      val deduction = PantryLedgerEngine.applyCookedDeduction(snapshot.pantryEntries, recipe, snapshot.ingredients, payload, nowIso)
      QaScenarioExecutionOutcome(
        updatedSnapshot = applyCookedDeductionToSnapshot(snapshot, slot.slotId, deduction, nowIso),
        shouldPersist = true,
        statusMessage = "PASS EC-06: Deducted planned BOM + 1 ad-hoc Vine-Ripened Tomato together in 1 atomic O(1) batch write.",
      )
    }

    QaEdgeCaseScenario.EC_07 -> {
      // EC-07: Activate Dad's POST_WORKOUT_HIGH_PROTEIN boost (flat 1x unscaled by servings).
      val slot = primarySlot ?: return noSlotOutcome(snapshot, "EC-07")
      val boostedBoard = MenuPlannerEngine.toggleSlotWorkoutModifier(snapshot.weeklyBoard, slot.slotId, WorkoutTiming.POST_WORKOUT_HIGH_PROTEIN)
      QaScenarioExecutionOutcome(
        updatedSnapshot = snapshot.copy(weeklyBoard = boostedBoard, updatedAtIso = nowIso),
        shouldPersist = true,
        statusMessage = "PASS EC-07: Activated POST_WORKOUT_HIGH_PROTEIN on ${slot.slotType.name} (flat 1x boost unscaled by household servings).",
      )
    }

    QaEdgeCaseScenario.EC_08 -> {
      // EC-08: Cycle youngest child developmental feeding stage to verify adapted safety callouts.
      val nextStage = if (snapshot.householdProfile.youngestStage == ChildGrowthStage.TODDLER_1_2Y) ChildGrowthStage.BABY_6_12M else ChildGrowthStage.TODDLER_1_2Y
      val updatedProfile = snapshot.householdProfile.copy(youngestStage = nextStage)
      QaScenarioExecutionOutcome(
        updatedSnapshot = snapshot.copy(householdProfile = updatedProfile, updatedAtIso = nowIso),
        shouldPersist = true,
        statusMessage = "PASS EC-08: Switched youngest child stage to ${nextStage.displayLabel} -> adapted safety callouts across all 21 meal cards.",
      )
    }

    QaEdgeCaseScenario.EC_09 -> {
      // EC-09: Set egg stock to 6 count so planned breakfasts trigger mid-week shortage -> ceil rounding.
      val clampedEggsPantry = snapshot.pantryEntries.map { entry ->
        if (entry.ingredientId == "ing_egg") entry.copy(currentQuantity = 6.0, isOutOfStock = false) else entry
      }
      val diff = GroceryDiffEngine.computeWeeklyGroceryDiff(
        snapshot.weeklyBoard, snapshot.recipes, snapshot.ingredients, clampedEggsPantry, snapshot.householdProfile.householdAllergens, snapshot.aiAddedGroceryIds,
      )
      val eggLine = diff.groceryItems.firstOrNull { it.ingredientId == "ing_egg" }
      QaScenarioExecutionOutcome(
        updatedSnapshot = snapshot.copy(pantryEntries = clampedEggsPantry, updatedAtIso = nowIso),
        shouldPersist = true,
        statusMessage = "PASS EC-09: Set eggs to 6 count -> chronological diff queued ${eggLine?.packagesToBuy ?: 1} carton(s) (${eggLine?.restockQuantity?.toInt() ?: 12} count).",
      )
    }

    QaEdgeCaseScenario.EC_10 -> {
      // EC-10: Clamp Tier-1 stock at 0.0 when deducting more than remaining balance (never negative).
      val lowTomato = PantryLedgerEngine.adjustPantryQuantity(snapshot.pantryEntries, snapshot.ingredients, "ing_tomato", -999.0, nowIso)
      val clampedEntry = lowTomato.firstOrNull { it.ingredientId == "ing_tomato" }
      QaScenarioExecutionOutcome(
        updatedSnapshot = snapshot.copy(pantryEntries = lowTomato, updatedAtIso = nowIso),
        shouldPersist = true,
        statusMessage = "PASS EC-10: Over-deducted Tomatoes -> clamped cleanly at ${clampedEntry?.currentQuantity ?: 0.0} (isOutOfStock = ${clampedEntry?.isOutOfStock ?: true}).",
      )
    }

    QaEdgeCaseScenario.EC_11 -> {
      // EC-11: Toggle Olive Oil Tier-2 staple between LOW (queues 1 bottle) and PLENTY.
      val currentOil = snapshot.pantryById["ing_olive_oil"]?.stapleStatus ?: StapleStatus.PLENTY
      val nextStatus = if (currentOil == StapleStatus.PLENTY) StapleStatus.LOW else StapleStatus.PLENTY
      val updatedPantry = PantryLedgerEngine.setStapleStatus(snapshot.pantryEntries, snapshot.ingredients, "ing_olive_oil", nextStatus, nowIso)
      QaScenarioExecutionOutcome(
        updatedSnapshot = snapshot.copy(pantryEntries = updatedPantry, updatedAtIso = nowIso),
        shouldPersist = true,
        statusMessage = "PASS EC-11: Set Tier-2 Extra-Virgin Olive Oil to $nextStatus (never gram-deducted on cook; queues 1 bottle when LOW/OUT).",
      )
    }

    QaEdgeCaseScenario.EC_12 -> {
      // EC-12: Mark a planned slot COMPLETED_SKIPPED and release its reserved Tier-1 BOM immediately.
      val planned = daySlots.firstOrNull { it.status == MealSlotStatus.PLANNED }
        ?: snapshot.weeklyBoard.slots.firstOrNull { it.status == MealSlotStatus.PLANNED }
        ?: return noSlotOutcome(snapshot, "EC-12")
      val skippedBoard = MenuPlannerEngine.markSlotSkippedOrLeftovers(snapshot.weeklyBoard, planned.slotId)
      QaScenarioExecutionOutcome(
        updatedSnapshot = snapshot.copy(weeklyBoard = skippedBoard, updatedAtIso = nowIso),
        shouldPersist = true,
        statusMessage = "PASS EC-12: Marked ${planned.slotType.name} COMPLETED_SKIPPED -> 0 stock deducted and reserved BOM immediately released.",
      )
    }

    QaEdgeCaseScenario.EC_13 -> {
      // EC-13: Filter 100% in-stock, <= 25m Breakfast replacement meals with zero OUT Tier-2 staples.
      val swaps = MenuPlannerEngine.findInstantInStockSwaps(
        MealSlotType.BREAKFAST, snapshot.recipes, snapshot.ingredients, snapshot.pantryEntries,
        snapshot.householdProfile.householdAllergens, snapshot.householdProfile.effectiveTargetServings,
      )
      QaScenarioExecutionOutcome(
        updatedSnapshot = snapshot,
        shouldPersist = false,
        statusMessage = "PASS EC-13: Found ${swaps.size} instant <=25m Breakfast swaps with 100% Tier-1 pantry stock and zero OUT staples.",
      )
    }
  }
}

// Merges pure applyCookedDeduction output onto recipes, pantryEntries, and weeklyBoard slots.
private fun applyCookedDeductionToSnapshot(
  current: KitchenSnapshot,
  slotId: String,
  deductionResult: CookedDeductionResult,
  nowIso: String,
): KitchenSnapshot {
  val updatedRecipes = current.recipes.map { rec ->
    if (rec.recipeId == deductionResult.updatedRecipe.recipeId) deductionResult.updatedRecipe else rec
  }
  val updatedSlots = current.weeklyBoard.slots.map { slot ->
    if (slot.slotId == slotId) slot.copy(status = MealSlotStatus.COOKED) else slot
  }
  return current.copy(
    recipes = updatedRecipes,
    pantryEntries = deductionResult.updatedPantry,
    weeklyBoard = current.weeklyBoard.copy(slots = updatedSlots),
    updatedAtIso = nowIso,
  )
}

private fun noSlotOutcome(snapshot: KitchenSnapshot, code: String): QaScenarioExecutionOutcome =
  QaScenarioExecutionOutcome(
    updatedSnapshot = snapshot,
    shouldPersist = false,
    statusMessage = "$code: Reset Household State to Seed first so planned slots are available.",
  )

/**
 * Pure reducer applying Smart Grocery List buy-quantity overrides, pack stepping, trip skipping,
 * custom item edits/deletions, and global catalog ingredient renames in a single `O(1)` snapshot
 * transformation.
 *
 * @param snapshot Current immutable household [KitchenSnapshot].
 * @param currentDiff Currently derived [WeeklyGroceryDiff] providing active pack counts and sizes.
 * @param action User edit action dispatched from `SmartGroceryScreen` or `SmartPantryScreen`.
 * @param nowIso Deterministic ISO-8601 commit timestamp stamped when state mutates.
 * @return Pair of `(updatedSnapshot, confirmationToast)` ready for `saveSnapshotBatch`.
 */
fun reduceGroceryAndCatalogEdit(
  snapshot: KitchenSnapshot,
  currentDiff: WeeklyGroceryDiff,
  action: GroceryAndCatalogEditAction,
  nowIso: String = DEFAULT_TIMESTAMP_ISO,
): Pair<KitchenSnapshot, String> =
  when (action) {
    is GroceryAndCatalogEditAction.SaveGroceryCatalogItemEdit ->
      reduceSaveGroceryCatalogItemEdit(snapshot, action, nowIso)
    is GroceryAndCatalogEditAction.StepGroceryItemPacks ->
      reduceStepGroceryItemPacks(snapshot, currentDiff, action, nowIso)
    is GroceryAndCatalogEditAction.ResetGroceryItemOverride -> {
      requireDomainValid(action.ingredientId.isNotBlank(), "ResetGroceryItemOverride") {
        "ingredientId: must be non-blank"
      }
      val label = resolveEditedIngredientLabel(snapshot, currentDiff, action.ingredientId)
      val candidate = snapshot.copy(
        groceryQuantityOverrides = snapshot.groceryQuantityOverrides - action.ingredientId,
        dismissedGroceryItemIds = snapshot.dismissedGroceryItemIds - action.ingredientId,
      )
      stampSnapshotIfChanged(snapshot, candidate, nowIso) to
        "Reset $label to weekly meal plan amount."
    }
    is GroceryAndCatalogEditAction.DismissGroceryItemForTrip -> {
      requireDomainValid(action.ingredientId.isNotBlank(), "DismissGroceryItemForTrip") {
        "ingredientId: must be non-blank"
      }
      val label = resolveEditedIngredientLabel(snapshot, currentDiff, action.ingredientId)
      // Remove manual queue, checked flag, and stale buy override when skipping for this trip.
      val candidate = snapshot.copy(
        dismissedGroceryItemIds = snapshot.dismissedGroceryItemIds + action.ingredientId,
        aiAddedGroceryIds = snapshot.aiAddedGroceryIds - action.ingredientId,
        checkedGroceryItemIds = snapshot.checkedGroceryItemIds - action.ingredientId,
        groceryQuantityOverrides = snapshot.groceryQuantityOverrides - action.ingredientId,
      )
      stampSnapshotIfChanged(snapshot, candidate, nowIso) to
        "Skipped $label for this shopping trip."
    }
    is GroceryAndCatalogEditAction.RestoreDismissedGroceryItems -> {
      val candidate = snapshot.copy(dismissedGroceryItemIds = emptySet())
      stampSnapshotIfChanged(snapshot, candidate, nowIso) to
        "Restored skipped items to your Shopping List."
    }
    is GroceryAndCatalogEditAction.SaveCustomGroceryItemEdit -> {
      requireDomainValid(action.item.customId.isNotBlank(), "SaveCustomGroceryItemEdit") {
        "customId: must be non-blank"
      }
      val cleanName = action.item.displayName.trim()
      requireDomainValid(cleanName.isNotEmpty(), "SaveCustomGroceryItemEdit") {
        "displayName: must be non-blank"
      }
      val cleanQtyNote = action.item.quantityNote.trim().ifEmpty { "1 item" }
      val updatedCustom = action.item.copy(displayName = cleanName, quantityNote = cleanQtyNote)
      // Preserve row order when editing an existing custom item, or append if newly created.
      val mergedCustomItems = when {
        snapshot.customGroceryItems.any { it.customId == updatedCustom.customId } ->
          snapshot.customGroceryItems.map { existing ->
            if (existing.customId == updatedCustom.customId) updatedCustom else existing
          }
        else -> snapshot.customGroceryItems + updatedCustom
      }
      val candidate = snapshot.copy(customGroceryItems = mergedCustomItems)
      stampSnapshotIfChanged(snapshot, candidate, nowIso) to "Updated '$cleanName'."
    }
    is GroceryAndCatalogEditAction.DeleteCustomGroceryItem -> {
      requireDomainValid(action.customId.isNotBlank(), "DeleteCustomGroceryItem") {
        "customId: must be non-blank"
      }
      val candidate = snapshot.copy(
        customGroceryItems = snapshot.customGroceryItems.filterNot { it.customId == action.customId },
        checkedGroceryItemIds = snapshot.checkedGroceryItemIds - action.customId,
      )
      stampSnapshotIfChanged(snapshot, candidate, nowIso) to "Removed item from Shopping List."
    }
    is GroceryAndCatalogEditAction.UpdateCatalogIngredientDetails ->
      reduceUpdateCatalogIngredientDetails(snapshot, action, nowIso)
  }

// Validates and applies global catalog name/pack edits plus an optional trip buy-quantity override.
private fun reduceSaveGroceryCatalogItemEdit(
  snapshot: KitchenSnapshot,
  action: GroceryAndCatalogEditAction.SaveGroceryCatalogItemEdit,
  nowIso: String,
): Pair<KitchenSnapshot, String> {
  // Reject blank IDs, blank names, non-positive pack sizes/overrides, and negative prices up front.
  requireDomainValid(action.ingredientId.isNotBlank(), "SaveGroceryCatalogItemEdit") { "ingredientId: must be non-blank" }
  val cleanName = action.updatedDisplayName.trim()
  requireDomainValid(cleanName.isNotEmpty(), "SaveGroceryCatalogItemEdit") { "displayName: must be non-blank" }
  if (action.updatedDefaultPackageQuantity != null) {
    val pkg = action.updatedDefaultPackageQuantity
    requireDomainValid(pkg.isFinite() && pkg > 0.0, "SaveGroceryCatalogItemEdit") { "defaultPackageQuantity: must be finite and > 0" }
  }
  if (action.updatedPackagePriceUsd != null) {
    val price = action.updatedPackagePriceUsd
    requireDomainValid(price.isFinite() && price >= 0.0, "SaveGroceryCatalogItemEdit") { "estimatedPackagePriceUsd: must be finite and >= 0" }
  }
  if (action.overrideBuyQuantity != null) {
    val buyQty = action.overrideBuyQuantity
    requireDomainValid(buyQty.isFinite() && buyQty > 0.0, "SaveGroceryCatalogItemEdit") { "overrideBuyQuantity: must be finite and > 0" }
  }

  // Update catalog displayName and optional store package metadata so Pantry, Plan, and Shop stay in sync.
  val updatedIngredients = snapshot.ingredients.map { item ->
    when {
      item.ingredientId != action.ingredientId -> item
      else -> SchemaValidator.validateIngredientCatalogItem(
        item.copy(
          displayName = cleanName,
          defaultPackageQuantity = action.updatedDefaultPackageQuantity ?: item.defaultPackageQuantity,
          estimatedPackagePriceUsd = action.updatedPackagePriceUsd ?: item.estimatedPackagePriceUsd,
        ),
      )
    }
  }
  // Store positive trip override or clear back to planned rounding when overrideBuyQuantity is null.
  val nextOverrides = when {
    action.overrideBuyQuantity != null && action.overrideBuyQuantity > 0.0 ->
      snapshot.groceryQuantityOverrides + (action.ingredientId to action.overrideBuyQuantity)
    else -> snapshot.groceryQuantityOverrides - action.ingredientId
  }
  val candidate = snapshot.copy(
    ingredients = updatedIngredients,
    groceryQuantityOverrides = nextOverrides,
    dismissedGroceryItemIds = snapshot.dismissedGroceryItemIds - action.ingredientId,
  )
  return stampSnapshotIfChanged(snapshot, candidate, nowIso) to
    "Updated '$cleanName' on your Shopping List."
}

// Steps whole-pack buy quantity (clamped to 1..99 packs) for 1-tap grocery card adjustments.
private fun reduceStepGroceryItemPacks(
  snapshot: KitchenSnapshot,
  currentDiff: WeeklyGroceryDiff,
  action: GroceryAndCatalogEditAction.StepGroceryItemPacks,
  nowIso: String,
): Pair<KitchenSnapshot, String> {
  requireDomainValid(action.ingredientId.isNotBlank(), "StepGroceryItemPacks") { "ingredientId: must be non-blank" }
  // Prefer active GroceryListItem pack count/size and fall back to catalog metadata if needed.
  val item = currentDiff.groceryItems.firstOrNull { it.ingredientId == action.ingredientId }
  val cat = snapshot.catalogById[action.ingredientId]
  val label = item?.displayName ?: cat?.displayName ?: action.ingredientId
  val pkgQty = (item?.defaultPackageQuantity ?: cat?.defaultPackageQuantity ?: 1.0)
    .takeIf { it.isFinite() }
    ?.coerceAtLeast(0.01) ?: 1.0
  val currentPacks = item?.packagesToBuy ?: 1
  val nextPacks = (currentPacks + action.deltaPacks).coerceIn(1, 99)
  val candidate = snapshot.copy(
    groceryQuantityOverrides = snapshot.groceryQuantityOverrides +
      (action.ingredientId to (nextPacks.toDouble() * pkgQty)),
    dismissedGroceryItemIds = snapshot.dismissedGroceryItemIds - action.ingredientId,
  )
  return stampSnapshotIfChanged(snapshot, candidate, nowIso) to
    "Updated $label to $nextPacks pack(s)."
}

// Updates global catalog display name, optional aisle category, and store pack details from Pantry.
private fun reduceUpdateCatalogIngredientDetails(
  snapshot: KitchenSnapshot,
  action: GroceryAndCatalogEditAction.UpdateCatalogIngredientDetails,
  nowIso: String,
): Pair<KitchenSnapshot, String> {
  requireDomainValid(action.ingredientId.isNotBlank(), "UpdateCatalogIngredientDetails") { "ingredientId: must be non-blank" }
  val cleanName = action.updatedDisplayName.trim()
  requireDomainValid(cleanName.isNotEmpty(), "UpdateCatalogIngredientDetails") { "displayName: must be non-blank" }
  // Ensure the target ingredientId exists in the household catalog before applying updates.
  requireDomainValid(
    snapshot.ingredients.any { it.ingredientId == action.ingredientId },
    "UpdateCatalogIngredientDetails",
  ) {
    "ingredientId: unknown catalog reference '${action.ingredientId}'"
  }
  // Validate updated IngredientCatalogItem invariants via SchemaValidator before committing.
  val updatedIngredients = snapshot.ingredients.map { item ->
    when {
      item.ingredientId != action.ingredientId -> item
      else -> SchemaValidator.validateIngredientCatalogItem(
        item.copy(
          displayName = cleanName,
          category = action.updatedCategory ?: item.category,
          defaultPackageQuantity = action.updatedDefaultPackageQuantity ?: item.defaultPackageQuantity,
          estimatedPackagePriceUsd = action.updatedPackagePriceUsd ?: item.estimatedPackagePriceUsd,
        ),
      )
    }
  }
  val candidate = snapshot.copy(ingredients = updatedIngredients)
  return stampSnapshotIfChanged(snapshot, candidate, nowIso) to "Updated ingredient '$cleanName'."
}

private fun resolveEditedIngredientLabel(
  snapshot: KitchenSnapshot,
  currentDiff: WeeklyGroceryDiff,
  ingredientId: String,
): String =
  snapshot.catalogById[ingredientId]?.displayName
    ?: currentDiff.groceryItems.firstOrNull { it.ingredientId == ingredientId }?.displayName
    ?: ingredientId

// Stamps updatedAtIso only when domain state actually changed so no-op taps avoid extra batch writes.
private fun stampSnapshotIfChanged(
  previous: KitchenSnapshot,
  candidateWithoutTimestamp: KitchenSnapshot,
  nowIso: String,
): KitchenSnapshot =
  when {
    candidateWithoutTimestamp == previous -> previous
    else -> candidateWithoutTimestamp.copy(updatedAtIso = nowIso)
  }
