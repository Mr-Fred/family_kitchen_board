package com.familykitchen.board.domain

import kotlin.math.max
import kotlinx.serialization.Serializable

// Pure SavorAI v2.0 weekly board lifecycle, rollover drafting, slot pinning, day/slot swapping,
// explicit skip-reason tagging, and 0-BOM post-cook leftover lunch scheduling engine.

private const val ROLLOVER_SCORE_EXPIRY_RESCUE: Int = 45
private const val ROLLOVER_SCORE_EXTRA_RESCUE_ITEM: Int = 8
private const val ROLLOVER_SCORE_PREFERRED_CUISINE: Int = 35
private const val ROLLOVER_SCORE_BOM_OVERLAP: Int = 15
private const val ROLLOVER_PENALTY_PREV_COOKED: Int = -40
private const val ROLLOVER_PENALTY_ALREADY_USED: Int = -60

// Ignore non-discriminating filler tokens when matching household preferredCuisines strings.
private val ROLLOVER_STOP_TOKENS: Set<String> = setOf(
  "and", "the", "for", "with", "style", "family", "kitchen", "local", "express", "global",
)

/**
 * Immutable outcome produced when starting a new week or refreshing the active [WeeklyBoard] via
 * [startOrRefreshWeeklyBoard].
 *
 * @property nextBoard Newly constructed 7-day [WeeklyBoard] respecting [PlanCadence] and `EC-01`–`EC-03`.
 * @property carriedOverCount Number of uncooked `PLANNED` meals carried forward to protect bought groceries.
 * @property pinnedRetainedCount Number of `isPinned == true` anchor slots preserved on their weekday/slot.
 * @property newlyDraftedCount Number of open cadence slots filled by expiry, cuisine, and variety scoring.
 * @property updatedElephantMemory Household Elephant memory updated with a compressed `WeeklyEpisodicRollup`.
 */
@Serializable
data class WeeklyRolloverResult(
  val nextBoard: WeeklyBoard,
  val carriedOverCount: Int,
  val pinnedRetainedCount: Int,
  val newlyDraftedCount: Int,
  val updatedElephantMemory: HouseholdElephantMemory,
)

/**
 * Starts a new weekly menu or refreshes an existing [WeeklyBoard] according to [cadence],
 * preserving pinned slots, optionally carrying forward uncooked `PLANNED` meals so purchased
 * groceries are cooked first, scoring remaining slots for `<= 2d` expiry rescue, preferred
 * cuisines, BOM overlap, and variety, and distilling a `WeeklyEpisodicRollup` into [elephantMemory].
 *
 * Strictly enforces `EC-01` (`<= 25m` calibrated morning cap on `BREAKFAST`), `EC-02` (local
 * market availability and allergen hard-hide), and `EC-03` (`substituteGroupId` BOM resolution).
 *
 * @param previousBoard Outgoing or current [WeeklyBoard] being rolled over or refreshed.
 * @param newWeekStartDateIso ISO-8601 Monday start date (`"YYYY-MM-DD"`) for the target week.
 * @param cadence Selected [PlanCadence] (`FULL_WEEK`, `BREAKFAST_AND_DINNER`, `DINNERS_ONLY`, or `BLANK_WEEK`).
 * @param carryOverUncooked Carries forward uncooked non-leftover `PLANNED` slots when `true`.
 * @param recipes Full household recipe library.
 * @param catalog Household ingredient catalog metadata.
 * @param pantryEntries Current household pantry ledger stock records.
 * @param householdProfile Active household profile (allergens, target servings, preferred cuisines).
 * @param elephantMemory Existing long-term Elephant memory receiving the weekly episodic digest.
 * @param todayIso Current calendar date (`"YYYY-MM-DD"`) driving live `expiryDateIso` shelf-life math.
 * @param nowIso Deterministic ISO-8601 timestamp stamped onto the episodic memory rollup.
 * @return Immutable [WeeklyRolloverResult].
 */
fun startOrRefreshWeeklyBoard(
  previousBoard: WeeklyBoard,
  newWeekStartDateIso: String,
  cadence: PlanCadence = PlanCadence.FULL_WEEK,
  carryOverUncooked: Boolean = true,
  recipes: List<Recipe>,
  catalog: List<IngredientCatalogItem>,
  pantryEntries: List<PantryLedgerEntry>,
  householdProfile: HouseholdProfile,
  elephantMemory: HouseholdElephantMemory = HouseholdElephantMemory(),
  todayIso: String = newWeekStartDateIso,
  nowIso: String = DEFAULT_TIMESTAMP_ISO,
): WeeklyRolloverResult {
  // Pre-index catalog and pantry maps once so EC-02/EC-03 evaluation runs in linear time.
  val catalogById = indexCatalogById(catalog)
  val pantryById = indexPantryById(pantryEntries)
  val schedulable = filterSchedulableRecipes(
    recipes, catalogById, householdProfile.householdAllergens, pantryById,
  )
  val schedulableById = schedulable.associateBy { it.recipeId }
  val allRecipesById = recipes.associateBy { it.recipeId }

  // Identify stocked ingredients expiring within <= 2 days so rollover rescues them early in the week.
  val urgentExpiringIds = pantryEntries
    .mapNotNull { entry ->
      val remaining = computeRemainingShelfLifeDays(entry, todayIso)
      if (remaining != null && remaining <= 2) entry.ingredientId else null
    }
    .toSet()

  // Keep existing boardId when refreshing the same Monday; mint a date-keyed ID on week advance.
  val nextBoardId = when {
    previousBoard.weekStartDateIso == newWeekStartDateIso && previousBoard.boardId.isNotBlank() ->
      previousBoard.boardId
    else -> "board_${rolloverSanitizeDateSlug(newWeekStartDateIso)}"
  }
  val defaultServings = max(1, householdProfile.effectiveTargetServings)
  val morningCap = rolloverResolveMorningCap(householdProfile)

  val placedByCoord = linkedMapOf<Pair<Int, MealSlotType>, MealSlot>()
  val usedCountsInNext = linkedMapOf<String, Int>()
  val chosenTier1Ingredients = linkedSetOf<String>()
  var pinnedRetainedCount = 0
  var carriedOverCount = 0
  var newlyDraftedCount = 0

  // Step 1: Retain pinned anchor slots on non-blank cadences when still schedulable under EC-01..EC-03.
  if (cadence != PlanCadence.BLANK_WEEK) {
    for (prevSlot in sortSlotsChronologically(previousBoard.slots)) {
      if (!prevSlot.isPinned || prevSlot.dayIndex !in 0..6) continue
      val recipe = schedulableById[prevSlot.recipeId] ?: continue
      if (!rolloverIsEligibleForSlot(recipe, prevSlot.slotType, morningCap)) continue
      val coord = prevSlot.dayIndex to prevSlot.slotType
      if (coord in placedByCoord) continue
      placedByCoord[coord] = MealSlot(
        slotId = rolloverBuildSlotId(nextBoardId, prevSlot.dayIndex, prevSlot.slotType),
        dayIndex = prevSlot.dayIndex,
        slotType = prevSlot.slotType,
        owner = prevSlot.owner,
        recipeId = recipe.recipeId,
        targetServings = max(1, prevSlot.targetServings),
        activeWorkoutModifier = prevSlot.activeWorkoutModifier,
        status = MealSlotStatus.PLANNED,
        isPinned = true,
      )
      rolloverRecordChosenRecipe(recipe, catalogById, usedCountsInNext, chosenTier1Ingredients)
      pinnedRetainedCount += 1
    }
  }

  // Step 2: Carry forward uncooked non-leftover PLANNED meals into earliest open slots so bought food is used.
  if (carryOverUncooked && cadence != PlanCadence.BLANK_WEEK) {
    val uncookedCandidates = sortSlotsChronologically(previousBoard.slots).filter { slot ->
      slot.status == MealSlotStatus.PLANNED &&
        slot.leftoverSourceRecipeId.isNullOrBlank() &&
        !slot.isPinned
    }
    for (uncooked in uncookedCandidates) {
      val recipe = schedulableById[uncooked.recipeId] ?: continue
      val targetCoord = rolloverFindEarliestOpenCarrySlot(
        uncooked.slotType, recipe, cadence, placedByCoord, morningCap,
      ) ?: continue
      val (dayIdx, slotType) = targetCoord
      placedByCoord[targetCoord] = MealSlot(
        slotId = rolloverBuildSlotId(nextBoardId, dayIdx, slotType),
        dayIndex = dayIdx,
        slotType = slotType,
        owner = rolloverDefaultOwnerForSlot(slotType, null),
        recipeId = recipe.recipeId,
        targetServings = defaultServings,
        status = MealSlotStatus.PLANNED,
      )
      rolloverRecordChosenRecipe(recipe, catalogById, usedCountsInNext, chosenTier1Ingredients)
      carriedOverCount += 1
    }
  }

  // Step 3: Fill remaining cadence-included positions via expiry + preferred-cuisine + overlap + variety scoring.
  val previouslyCookedIds = previousBoard.slots
    .filter { it.status == MealSlotStatus.COOKED }
    .map { it.recipeId }
    .toSet()

  // Build slot-specific candidate pools once before iterating the 7-day calendar grid.
  val breakfastPool = schedulable.filter { rolloverIsEligibleForSlot(it, MealSlotType.BREAKFAST, morningCap) }
  val lunchPool = schedulable.filter { MealSlotType.LUNCH in it.allowedSlots }.ifEmpty { schedulable }
  val dinnerPool = schedulable.filter { MealSlotType.DINNER in it.allowedSlots }.ifEmpty { schedulable }

  for (dayIndex in 0..6) {
    for (slotType in listOf(MealSlotType.BREAKFAST, MealSlotType.LUNCH, MealSlotType.DINNER)) {
      if (!cadence.includesSlot(dayIndex, slotType)) continue
      val coord = dayIndex to slotType
      if (coord in placedByCoord) continue

      val pool = when (slotType) {
        // EC-01 hard guard: never schedule a > 25m recipe onto BREAKFAST even when breakfastPool is empty.
        MealSlotType.BREAKFAST -> breakfastPool
        MealSlotType.LUNCH -> lunchPool
        MealSlotType.DINNER -> dinnerPool
      }
      if (pool.isEmpty()) continue

      val selected = rolloverSelectBestRecipeForSlot(
        pool = pool,
        dayIndex = dayIndex,
        slotRank = slotType.chronologicalRank,
        urgentExpiringIds = urgentExpiringIds,
        preferredCuisines = householdProfile.preferredCuisines,
        chosenTier1Ingredients = chosenTier1Ingredients,
        catalogById = catalogById,
        previouslyCookedIds = previouslyCookedIds,
        usedCountsInNext = usedCountsInNext,
      ) ?: continue

      placedByCoord[coord] = MealSlot(
        slotId = rolloverBuildSlotId(nextBoardId, dayIndex, slotType),
        dayIndex = dayIndex,
        slotType = slotType,
        owner = rolloverDefaultOwnerForSlot(slotType, selected.preferredOwner),
        recipeId = selected.recipeId,
        targetServings = defaultServings,
        status = MealSlotStatus.PLANNED,
      )
      rolloverRecordChosenRecipe(selected, catalogById, usedCountsInNext, chosenTier1Ingredients)
      newlyDraftedCount += 1
    }
  }

  // Step 4: Distill outgoing week's cooked/skipped/rescued metrics into HouseholdElephantMemory.
  val updatedMemory = rolloverDistillPreviousWeekDigest(
    previousBoard, allRecipesById, pantryEntries, todayIso, elephantMemory, nowIso,
  )

  val nextBoard = WeeklyBoard(
    boardId = nextBoardId,
    householdId = householdProfile.householdId.ifBlank { previousBoard.householdId },
    weekStartDateIso = newWeekStartDateIso,
    slots = sortSlotsChronologically(placedByCoord.values.toList()),
  )
  return WeeklyRolloverResult(
    nextBoard = nextBoard,
    carriedOverCount = carriedOverCount,
    pinnedRetainedCount = pinnedRetainedCount,
    newlyDraftedCount = newlyDraftedCount,
    updatedElephantMemory = updatedMemory,
  )
}

/**
 * Removes the meal slot identified by [slotId] from [board], leaving that weekday/meal position open
 * and releasing its BOM reservation from the Smart Grocery List.
 *
 * @param board Current 7-day [WeeklyBoard].
 * @param slotId Target [MealSlot.slotId] to clear.
 * @return Updated immutable [WeeklyBoard] without [slotId].
 */
fun clearMealSlot(board: WeeklyBoard, slotId: String): WeeklyBoard =
  // Filtering out the cleared slot lets EmptyDaySlotsCard render cleanly when a day has 0 meals.
  board.copy(slots = board.slots.filterNot { it.slotId == slotId })

/**
 * Toggles [MealSlot.isPinned] on [slotId] so weekly refreshes preserve or release the anchor meal.
 *
 * @param board Current 7-day [WeeklyBoard].
 * @param slotId Target [MealSlot.slotId] to pin or unpin.
 * @return Updated immutable [WeeklyBoard].
 */
fun toggleMealSlotPinned(board: WeeklyBoard, slotId: String): WeeklyBoard =
  // Pinned slots survive startOrRefreshWeeklyBoard on their exact (dayIndex, slotType) coordinate.
  board.copy(
    slots = board.slots.map { slot ->
      when {
        slot.slotId != slotId -> slot
        else -> slot.copy(isPinned = !slot.isPinned)
      }
    },
  )

/**
 * Moves [sourceSlotId] into an empty `(targetDayIndex, targetSlotType)` position or swaps its meal
 * assignment with an occupied target slot, enforcing `0..6` weekday bounds and `EC-01` (`<= 25m`
 * calibrated morning cap) whenever either moved recipe lands on `BREAKFAST`.
 *
 * @param board Current 7-day [WeeklyBoard].
 * @param sourceSlotId Origin [MealSlot.slotId] selected by the user.
 * @param targetDayIndex Destination weekday index (`0 = Monday` .. `6 = Sunday`).
 * @param targetSlotType Destination meal slot category (`BREAKFAST`, `LUNCH`, or `DINNER`).
 * @param recipesById Lookup map of household recipes keyed by `recipeId`.
 * @param morningCapMinutes Maximum calibrated morning minutes permitted on `BREAKFAST` (`EC-01`).
 * @return Updated immutable [WeeklyBoard] with slots sorted chronologically.
 */
fun moveOrSwapMealSlot(
  board: WeeklyBoard,
  sourceSlotId: String,
  targetDayIndex: Int,
  targetSlotType: MealSlotType,
  recipesById: Map<String, Recipe>,
  morningCapMinutes: Int = MAX_WEEKDAY_BREAKFAST_MINUTES,
): WeeklyBoard {
  // Reject out-of-range weekday indices immediately before inspecting slot coordinates.
  requireDomainValid(targetDayIndex in 0..6, "MealSlotMove") {
    "targetDayIndex must be in 0..6, got $targetDayIndex"
  }
  val sourceSlot = board.slots.find { it.slotId == sourceSlotId } ?: return board
  if (sourceSlot.dayIndex == targetDayIndex && sourceSlot.slotType == targetSlotType) {
    return board
  }

  val safeCap = max(1, morningCapMinutes)
  val sourceRecipe = recipesById[sourceSlot.recipeId]
  // Enforce EC-01 whenever moving the source recipe into a BREAKFAST slot.
  if (targetSlotType == MealSlotType.BREAKFAST && sourceRecipe != null) {
    val sourceActiveMinutes = computeCalibratedMorningMinutes(sourceRecipe)
    requireDomainValid(sourceActiveMinutes <= safeCap, "BreakfastCapMove") {
      "Recipe '${sourceRecipe.title}' takes ${sourceActiveMinutes}m, exceeding ${safeCap}m Breakfast cap"
    }
  }

  val targetSlot = board.slots.find { it.dayIndex == targetDayIndex && it.slotType == targetSlotType }

  // When swapping with an occupied slot and source is BREAKFAST, verify target recipe also fits EC-01.
  if (targetSlot != null && sourceSlot.slotType == MealSlotType.BREAKFAST) {
    val targetRecipe = recipesById[targetSlot.recipeId]
    if (targetRecipe != null) {
      val targetActiveMinutes = computeCalibratedMorningMinutes(targetRecipe)
      requireDomainValid(targetActiveMinutes <= safeCap, "BreakfastCapSwap") {
        "Swapped recipe '${targetRecipe.title}' takes ${targetActiveMinutes}m, exceeding ${safeCap}m Breakfast cap"
      }
    }
  }

  val updatedSlots = when (targetSlot) {
    null -> {
      // Destination position is open: relocate sourceSlot cleanly onto (targetDayIndex, targetSlotType).
      val movedId = rolloverBuildSlotId(board.boardId, targetDayIndex, targetSlotType)
      val finalSlotId = if (board.slots.any { it.slotId == movedId && it.slotId != sourceSlot.slotId }) {
        "${movedId}_moved"
      } else {
        movedId
      }
      board.slots.map { slot ->
        when {
          slot.slotId != sourceSlot.slotId -> slot
          else -> slot.copy(
            slotId = finalSlotId,
            dayIndex = targetDayIndex,
            slotType = targetSlotType,
            owner = rolloverDefaultOwnerForSlot(targetSlotType, slot.owner),
          )
        }
      }
    }

    else -> {
      // Destination is occupied: exchange meal assignments while preserving positional slot IDs/owners.
      board.slots.map { slot ->
        when (slot.slotId) {
          sourceSlot.slotId -> rolloverCopySlotPayload(slot, targetSlot)
          targetSlot.slotId -> rolloverCopySlotPayload(slot, sourceSlot)
          else -> slot
        }
      }
    }
  }

  return board.copy(slots = sortSlotsChronologically(updatedSlots))
}

/**
 * Transitions [slotId] to [MealSlotStatus.COMPLETED_SKIPPED] with an explicit [SlotSkipReason]
 * (`ATE_LEFTOVERS`, `EATING_OUT`, or `SKIPPED`), releasing reserved BOM (`EC-12`) while enabling
 * honest daily macro telemetry.
 *
 * @param board Current 7-day [WeeklyBoard].
 * @param slotId Target [MealSlot.slotId].
 * @param reason Explicit household skip classification.
 * @return Updated immutable [WeeklyBoard].
 */
fun markSlotWithSkipReason(
  board: WeeklyBoard,
  slotId: String,
  reason: SlotSkipReason,
): WeeklyBoard =
  // Tagging skipReason distinguishes home leftover nights from restaurant meals in macro summaries.
  board.copy(
    slots = board.slots.map { slot ->
      when {
        slot.slotId != slotId -> slot
        else -> slot.copy(status = MealSlotStatus.COMPLETED_SKIPPED, skipReason = reason)
      }
    },
  )

/**
 * Upserts tomorrow's (`(cookedDayIndex + 1) % 7`) `LUNCH` slot with extra batch-cooked portions of
 * [cookedRecipe], stamping `leftoverSourceRecipeId = cookedRecipe.recipeId` so `GroceryDiffEngine`
 * reserves `0` extra ingredients for the leftover meal.
 *
 * @param board Current 7-day [WeeklyBoard].
 * @param cookedRecipe Batch-cooked [Recipe] producing extra portions.
 * @param cookedDayIndex Weekday index (`0..6`) when [cookedRecipe] was cooked.
 * @param leftoverServings Number of leftover portions saved (clamped to `>= 1`).
 * @param eveningLeadOwner Assigned household owner for the next-day lunch slot.
 * @return Updated immutable [WeeklyBoard] with tomorrow's leftover lunch scheduled.
 */
fun scheduleLeftoverLunchFromCook(
  board: WeeklyBoard,
  cookedRecipe: Recipe,
  cookedDayIndex: Int,
  leftoverServings: Int,
  eveningLeadOwner: CookOwner = CookOwner.WIFE,
): WeeklyBoard {
  // Wrap Sunday (index 6) cleanly around to Monday (index 0) using non-negative modulo arithmetic.
  val normalizedCookDay = ((cookedDayIndex % 7) + 7) % 7
  val tomorrowDayIndex = (normalizedCookDay + 1) % 7
  val safeServings = leftoverServings.coerceAtLeast(1)
  var replacedExisting = false

  val mappedSlots = board.slots.map { slot ->
    when {
      slot.dayIndex == tomorrowDayIndex && slot.slotType == MealSlotType.LUNCH -> {
        replacedExisting = true
        slot.copy(
          owner = eveningLeadOwner,
          recipeId = cookedRecipe.recipeId,
          targetServings = safeServings,
          activeWorkoutModifier = WorkoutTiming.NONE,
          status = MealSlotStatus.PLANNED,
          isPreppedAhead = false,
          preppedTimeSavedMinutes = 0,
          skipReason = null,
          leftoverSourceRecipeId = cookedRecipe.recipeId,
        )
      }
      else -> slot
    }
  }

  // Insert a fresh LUNCH slot when operating on DINNERS_ONLY, BREAKFAST_AND_DINNER, or BLANK_WEEK boards.
  val finalSlots = when {
    replacedExisting -> mappedSlots
    else -> mappedSlots + MealSlot(
      slotId = rolloverBuildSlotId(board.boardId, tomorrowDayIndex, MealSlotType.LUNCH),
      dayIndex = tomorrowDayIndex,
      slotType = MealSlotType.LUNCH,
      owner = eveningLeadOwner,
      recipeId = cookedRecipe.recipeId,
      targetServings = safeServings,
      status = MealSlotStatus.PLANNED,
      leftoverSourceRecipeId = cookedRecipe.recipeId,
    )
  }

  return board.copy(slots = sortSlotsChronologically(finalSlots))
}

// Track chosen recipe counts and non-Tier-2 BOM ingredients so subsequent slots consolidate packs.
private fun rolloverRecordChosenRecipe(
  recipe: Recipe,
  catalogById: Map<String, IngredientCatalogItem>,
  usedCountsInNext: MutableMap<String, Int>,
  chosenTier1Ingredients: MutableSet<String>,
) {
  usedCountsInNext[recipe.recipeId] = (usedCountsInNext[recipe.recipeId] ?: 0) + 1
  for (bom in recipe.bomLines) {
    if (catalogById[bom.ingredientId]?.tier != IngredientTier.TIER_2_STAPLE) {
      chosenTier1Ingredients.add(bom.ingredientId)
    }
  }
}

// Swap recipe, status, workout, pin, and leftover metadata onto an anchor slot shell.
private fun rolloverCopySlotPayload(anchorSlot: MealSlot, payloadSource: MealSlot): MealSlot =
  anchorSlot.copy(
    recipeId = payloadSource.recipeId,
    targetServings = payloadSource.targetServings,
    activeWorkoutModifier = payloadSource.activeWorkoutModifier,
    status = payloadSource.status,
    isPreppedAhead = payloadSource.isPreppedAhead,
    preppedTimeSavedMinutes = payloadSource.preppedTimeSavedMinutes,
    isPinned = payloadSource.isPinned,
    skipReason = payloadSource.skipReason,
    leftoverSourceRecipeId = payloadSource.leftoverSourceRecipeId,
  )

// Clamp household morning cap to 1..25 minutes so EC-01 can never be bypassed by misconfiguration.
private fun rolloverResolveMorningCap(profile: HouseholdProfile): Int {
  val raw = profile.fredMorningCapMinutes
  return if (raw in 1..MAX_WEEKDAY_BREAKFAST_MINUTES) raw else MAX_WEEKDAY_BREAKFAST_MINUTES
}

// Enforce EC-01 <= 25m cap on BREAKFAST while honoring allowedSlots on LUNCH and DINNER.
private fun rolloverIsEligibleForSlot(
  recipe: Recipe,
  slotType: MealSlotType,
  morningCap: Int,
): Boolean =
  when (slotType) {
    MealSlotType.BREAKFAST ->
      MealSlotType.BREAKFAST in recipe.allowedSlots &&
        computeCalibratedMorningMinutes(recipe) <= minOf(MAX_WEEKDAY_BREAKFAST_MINUTES, morningCap)
    MealSlotType.LUNCH, MealSlotType.DINNER ->
      slotType in recipe.allowedSlots || recipe.allowedSlots.isEmpty()
  }

private fun rolloverFindEarliestOpenCarrySlot(
  preferredSlotType: MealSlotType,
  recipe: Recipe,
  cadence: PlanCadence,
  placedByCoord: Map<Pair<Int, MealSlotType>, MealSlot>,
  morningCap: Int,
): Pair<Int, MealSlotType>? {
  // Prefer matching the original meal slot type first (e.g. uncooked Dinner -> Monday Dinner).
  if (rolloverIsEligibleForSlot(recipe, preferredSlotType, morningCap)) {
    for (dayIdx in 0..6) {
      val candidate = dayIdx to preferredSlotType
      if (cadence.includesSlot(dayIdx, preferredSlotType) && candidate !in placedByCoord) {
        return candidate
      }
    }
  }
  // Fall back to another compatible cadence-included slot (e.g. Lunch -> Dinner on DINNERS_ONLY).
  for (dayIdx in 0..6) {
    for (altType in listOf(MealSlotType.DINNER, MealSlotType.LUNCH, MealSlotType.BREAKFAST)) {
      if (altType == preferredSlotType || !cadence.includesSlot(dayIdx, altType)) continue
      val candidate = dayIdx to altType
      if (candidate !in placedByCoord && rolloverIsEligibleForSlot(recipe, altType, morningCap)) {
        return candidate
      }
    }
  }
  return null
}

private fun rolloverSelectBestRecipeForSlot(
  pool: List<Recipe>,
  dayIndex: Int,
  slotRank: Int,
  urgentExpiringIds: Set<String>,
  preferredCuisines: List<String>,
  chosenTier1Ingredients: Set<String>,
  catalogById: Map<String, IngredientCatalogItem>,
  previouslyCookedIds: Set<String>,
  usedCountsInNext: Map<String, Int>,
): Recipe? {
  if (pool.isEmpty()) return null
  var bestRecipe: Recipe? = null
  var bestScore = Int.MIN_VALUE
  var bestRescuedCount = -1
  val rotationOffset = (dayIndex * 3 + slotRank).coerceAtLeast(0)

  for (idx in pool.indices) {
    // Rotate traversal index so equal-scored candidates distribute evenly across days.
    val candidate = pool[(idx + rotationOffset) % pool.size]
    val rescuedCount = candidate.bomLines.count { !it.isOptional && it.ingredientId in urgentExpiringIds }
    var score = 0

    // +45 when rescuing stocked <= 2d expiring perishables (+ small bonus for multi-item rescues).
    if (rescuedCount > 0) {
      score += ROLLOVER_SCORE_EXPIRY_RESCUE + (rescuedCount - 1) * ROLLOVER_SCORE_EXTRA_RESCUE_ITEM
    }
    // +35 when matching the household's preferred culinary traditions (e.g. French, Yoruba).
    if (rolloverMatchesPreferredCuisines(candidate, preferredCuisines)) {
      score += ROLLOVER_SCORE_PREFERRED_CUISINE
    }
    // +15 when sharing Tier-1 produce/protein/grain ingredients already planned this week.
    val hasTier1Overlap = candidate.bomLines.any { bom ->
      bom.ingredientId in chosenTier1Ingredients &&
        catalogById[bom.ingredientId]?.tier != IngredientTier.TIER_2_STAPLE
    }
    if (hasTier1Overlap) score += ROLLOVER_SCORE_BOM_OVERLAP
    // -40 when cooked last week and -60 per already-scheduled occurrence on nextBoard.
    if (candidate.recipeId in previouslyCookedIds) score += ROLLOVER_PENALTY_PREV_COOKED
    val usedCount = usedCountsInNext[candidate.recipeId] ?: 0
    if (usedCount > 0) score += ROLLOVER_PENALTY_ALREADY_USED * usedCount

    val isBetter = score > bestScore || (score == bestScore && rescuedCount > bestRescuedCount)
    if (isBetter) {
      bestScore = score
      bestRescuedCount = rescuedCount
      bestRecipe = candidate
    }
  }
  return bestRecipe
}

// Match recipe cuisineTags, cuisineTag, sourceAttribution, and title against household preferred cuisines.
private fun rolloverMatchesPreferredCuisines(
  recipe: Recipe,
  preferredCuisines: List<String>,
): Boolean {
  if (preferredCuisines.isEmpty()) return false
  val searchable = buildString {
    recipe.cuisineTags.forEach { append(it.lowercase()).append(' ') }
    append(recipe.cuisineTag.lowercase()).append(' ')
    append(recipe.sourceAttribution.lowercase()).append(' ')
    append(recipe.title.lowercase())
  }

  for (rawPref in preferredCuisines) {
    val cleanPref = rawPref.trim().lowercase()
    if (cleanPref.isEmpty()) continue
    if (searchable.contains(cleanPref)) return true

    // Match regional culinary stems (Bistro -> French, Beninese/Jollof/Efo -> Yoruba/West African).
    val tokens = cleanPref
      .split(Regex("[^a-z0-9]+"))
      .filter { it.length >= 4 && it !in ROLLOVER_STOP_TOKENS }
    for (token in tokens) {
      val matched = when (token) {
        "french" ->
          searchable.contains("french") || searchable.contains("bistro") ||
            searchable.contains("boulangerie") || searchable.contains("proven")
        "yoruba", "african", "west", "beninese" ->
          searchable.contains("yoruba") || searchable.contains("west african") ||
            searchable.contains("beninese") || searchable.contains("jollof") ||
            searchable.contains("efo") || searchable.contains("egusi")
        "filipino" ->
          searchable.contains("filipino") || searchable.contains("tinola") ||
            searchable.contains("sinigang") || searchable.contains("monggo")
        else -> searchable.contains(token)
      }
      if (matched) return true
    }
  }
  return false
}

// Compress outgoing board completion metrics into a bounded Elephant WeeklyEpisodicRollup digest.
private fun rolloverDistillPreviousWeekDigest(
  previousBoard: WeeklyBoard,
  allRecipesById: Map<String, Recipe>,
  pantryEntries: List<PantryLedgerEntry>,
  todayIso: String,
  elephantMemory: HouseholdElephantMemory,
  nowIso: String,
): HouseholdElephantMemory {
  if (previousBoard.weekStartDateIso.isBlank()) return elephantMemory
  val cookedSlots = previousBoard.slots.filter { it.status == MealSlotStatus.COOKED }
  val skippedCount = previousBoard.slots.count { it.status == MealSlotStatus.COMPLETED_SKIPPED }
  val expiringIds = pantryEntries
    .filter { entry ->
      val rem = computeRemainingShelfLifeDays(entry, todayIso)
      (rem != null && rem <= 2) || (entry.expiresInDays != null && entry.expiresInDays <= 2)
    }
    .map { it.ingredientId }
    .toSet()

  val rescuedItems = cookedSlots
    .asSequence()
    .mapNotNull { allRecipesById[it.recipeId] }
    .flatMap { it.bomLines.asSequence() }
    .map { it.ingredientId }
    .filter { it in expiringIds }
    .toSet()

  val summary =
    "Week of ${previousBoard.weekStartDateIso}: cooked ${cookedSlots.size} meals, " +
      "skipped $skippedCount slots, rescued ${rescuedItems.size} expiring items."
  return distillActionIntoElephantMemory(
    memory = elephantMemory,
    observation = ElephantKitchenObservation.WeeklyEpisodicRollup(
      weekStartDateIso = previousBoard.weekStartDateIso,
      cookedMealsCount = cookedSlots.size,
      skippedSlotsCount = skippedCount,
      rescuedExpiringItemsCount = rescuedItems.size,
      highlightSummary = summary,
    ),
    nowIso = nowIso,
  )
}

private fun rolloverBuildSlotId(boardId: String, dayIndex: Int, slotType: MealSlotType): String {
  val prefix = boardId.trim().ifEmpty { "board_week" }
  return "${prefix}_day_${dayIndex}_${slotType.name.lowercase()}"
}

private fun rolloverDefaultOwnerForSlot(slotType: MealSlotType, preferred: CookOwner?): CookOwner =
  when {
    preferred == CookOwner.SHARED -> CookOwner.SHARED
    slotType == MealSlotType.BREAKFAST -> CookOwner.FRED
    else -> CookOwner.WIFE
  }

private fun rolloverSanitizeDateSlug(rawIso: String): String =
  rawIso.trim().take(10).replace(Regex("[^a-zA-Z0-9]+"), "_").trim('_').ifEmpty { "week" }

/** Object namespace exposing weekly rollover and slot actions for qualified `WeeklyRolloverEngine.*` calls. */
object WeeklyRolloverEngine {
  // Primary weekly board lifecycle entrypoint supporting cadence selection and uncooked carry-over.
  /** Delegates to [com.familykitchen.board.domain.startOrRefreshWeeklyBoard]. */
  fun startOrRefreshWeeklyBoard(
    previousBoard: WeeklyBoard,
    newWeekStartDateIso: String,
    cadence: PlanCadence = PlanCadence.FULL_WEEK,
    carryOverUncooked: Boolean = true,
    recipes: List<Recipe>,
    catalog: List<IngredientCatalogItem>,
    pantryEntries: List<PantryLedgerEntry>,
    householdProfile: HouseholdProfile,
    elephantMemory: HouseholdElephantMemory = HouseholdElephantMemory(),
    todayIso: String = newWeekStartDateIso,
    nowIso: String = DEFAULT_TIMESTAMP_ISO,
  ): WeeklyRolloverResult = com.familykitchen.board.domain.startOrRefreshWeeklyBoard(
    previousBoard, newWeekStartDateIso, cadence, carryOverUncooked, recipes, catalog,
    pantryEntries, householdProfile, elephantMemory, todayIso, nowIso,
  )

  // Clears a single slot so households can keep open flexible days without 21-slot rigidity.
  /** Delegates to [com.familykitchen.board.domain.clearMealSlot]. */
  fun clearMealSlot(board: WeeklyBoard, slotId: String): WeeklyBoard =
    com.familykitchen.board.domain.clearMealSlot(board, slotId)

  // Flips slot pin state to lock recurring family traditions across weekly rollovers.
  /** Delegates to [com.familykitchen.board.domain.toggleMealSlotPinned]. */
  fun toggleMealSlotPinned(board: WeeklyBoard, slotId: String): WeeklyBoard =
    com.familykitchen.board.domain.toggleMealSlotPinned(board, slotId)

  // Relocates or exchanges two board slots while enforcing EC-01 morning cap invariants.
  /** Delegates to [com.familykitchen.board.domain.moveOrSwapMealSlot]. */
  fun moveOrSwapMealSlot(
    board: WeeklyBoard,
    sourceSlotId: String,
    targetDayIndex: Int,
    targetSlotType: MealSlotType,
    recipesById: Map<String, Recipe>,
    morningCapMinutes: Int = MAX_WEEKDAY_BREAKFAST_MINUTES,
  ): WeeklyBoard = com.familykitchen.board.domain.moveOrSwapMealSlot(
    board, sourceSlotId, targetDayIndex, targetSlotType, recipesById, morningCapMinutes,
  )

  // Marks a slot COMPLETED_SKIPPED with explicit leftover vs eating-out semantics.
  /** Delegates to [com.familykitchen.board.domain.markSlotWithSkipReason]. */
  fun markSlotWithSkipReason(board: WeeklyBoard, slotId: String, reason: SlotSkipReason): WeeklyBoard =
    com.familykitchen.board.domain.markSlotWithSkipReason(board, slotId, reason)

  // Upserts tomorrow's 0-BOM leftover lunch from extra batch-cooked portions.
  /** Delegates to [com.familykitchen.board.domain.scheduleLeftoverLunchFromCook]. */
  fun scheduleLeftoverLunchFromCook(
    board: WeeklyBoard,
    cookedRecipe: Recipe,
    cookedDayIndex: Int,
    leftoverServings: Int,
    eveningLeadOwner: CookOwner = CookOwner.WIFE,
  ): WeeklyBoard = com.familykitchen.board.domain.scheduleLeftoverLunchFromCook(
    board, cookedRecipe, cookedDayIndex, leftoverServings, eveningLeadOwner,
  )
}
