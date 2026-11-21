package com.familykitchen.board.domain

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Pure 7-day menu planner, morning pacer math, instant swap filter, and weekly station-prep engine.
// Enforces EC-01 (1.4x buffer <= 25m Breakfast cap), EC-07 (workout view), EC-08 (child safety),
// EC-12 (COMPLETED_SKIPPED reservation release), and EC-13 (100% in-stock instant meal swaps).

/** Maximum calibrated active morning minutes allowed for weekday `BREAKFAST` scheduling (`EC-01`). */
const val MAX_WEEKDAY_BREAKFAST_MINUTES: Int = 25

/** Maximum minutes of raw morning prep that can be shifted to 8:30 PM the night before (`EC-01`). */
const val MAX_NIGHT_BEFORE_SHIFT_MINUTES: Int = 5

/** Deliberate morning prep buffer multiplier calibrating raw prep estimates to Fred's pace. */
const val MORNING_PREP_BUFFER_MULTIPLIER: Double = 1.4

/** Evening hour (`20` = 8:00 PM) at or after which tomorrow's 5-min night-prep banner activates. */
const val EVENING_PREP_START_HOUR_24: Int = 20

/**
 * Computes Fred's calibrated active morning minutes using the `1.4x` prep buffer and optional
 * `<= 5m` night-before shift (`EC-01`).
 *
 * Formula: `ceil(max(0, rawPrepMinutes - min(5, max(0, nightBeforePrepMinutes))) * 1.4) + max(0, cookMinutes)`
 *
 * @param rawPrepMinutes Unbuffered hands-on preparation time in minutes.
 * @param nightBeforePrepMinutes Minutes shifted to 8:30 PM night-before prep (capped at `5`).
 * @param cookMinutes Active stove or oven cooking minutes.
 * @return Calibrated active morning minutes required between 6:15 AM and 7:00 AM.
 */
fun computeCalibratedMorningMinutes(
  rawPrepMinutes: Int,
  nightBeforePrepMinutes: Int = 0,
  cookMinutes: Int,
): Int {
  // Cap overnight shift at 5 minutes so unrealistic night-before work cannot game the 25m guard.
  val shiftedNightPrep = min(MAX_NIGHT_BEFORE_SHIFT_MINUTES, max(0, nightBeforePrepMinutes))
  val remainingMorningPrep = max(0, rawPrepMinutes - shiftedNightPrep)
  // Multiply active morning prep by 1.4x and round up so morning table deadlines never slip.
  val bufferedPrepMinutes = ceil(remainingMorningPrep * MORNING_PREP_BUFFER_MULTIPLIER).toInt()
  return bufferedPrepMinutes + max(0, cookMinutes)
}

/**
 * Overload of [computeCalibratedMorningMinutes] reading timing fields directly from [recipe].
 *
 * @param recipe Recipe whose timing properties are evaluated.
 * @return Calibrated active morning minutes.
 */
fun computeCalibratedMorningMinutes(recipe: Recipe): Int =
  computeCalibratedMorningMinutes(recipe.rawPrepMinutes, recipe.nightBeforePrepMinutes, recipe.cookMinutes)

/**
 * Returns `true` when [recipe] allows `BREAKFAST` and satisfies `calibratedActiveMinutes <= 25` (`EC-01`).
 *
 * @param recipe Candidate recipe to inspect for weekday morning eligibility.
 * @return `true` when eligible for weekday breakfast scheduling.
 */
fun isWeekdayBreakfastEligible(recipe: Recipe): Boolean =
  MealSlotType.BREAKFAST in recipe.allowedSlots &&
    computeCalibratedMorningMinutes(recipe) <= MAX_WEEKDAY_BREAKFAST_MINUTES

/**
 * Auto-drafts a complete 7-day (21-slot) [WeeklyBoard], assigning Fred to `<= 25m` `BREAKFAST`
 * slots and Wife to `LUNCH` and `DINNER` slots (`EC-01`–`EC-03`).
 *
 * @param boardId Unique identifier for the drafted board.
 * @param householdId Owning household identifier.
 * @param weekStartDateIso ISO-8601 Monday start date.
 * @param recipes Candidate recipe library.
 * @param catalog Household ingredient catalog.
 * @param householdAllergens Active household allergen exclusions.
 * @param defaultServings Default serving count per slot (`4` for 2 adults + 8yo + 2yo).
 * @param pantry Optional pantry ledger for substitute tie-breaking.
 * @return Immutable 21-slot [WeeklyBoard].
 */
fun autoDraftWeeklyBoard(
  boardId: String,
  householdId: String,
  weekStartDateIso: String,
  recipes: List<Recipe>,
  catalog: List<IngredientCatalogItem>,
  householdAllergens: List<String> = emptyList(),
  defaultServings: Int = 4,
  pantry: List<PantryLedgerEntry>? = null,
): WeeklyBoard {
  val schedulable = filterSchedulableRecipes(recipes, catalog, householdAllergens, pantry)
  val breakfastPool = schedulable.filter(::isWeekdayBreakfastEligible)
  val lunchPool = schedulable.filter { MealSlotType.LUNCH in it.allowedSlots }
  val dinnerPool = schedulable.filter { MealSlotType.DINNER in it.allowedSlots }
  val safeServings = if (defaultServings > 0) defaultServings else 4

  val slotTemplates = listOf(
    Triple(MealSlotType.BREAKFAST, CookOwner.FRED, breakfastPool),
    Triple(MealSlotType.LUNCH, CookOwner.WIFE, lunchPool),
    Triple(MealSlotType.DINNER, CookOwner.WIFE, dinnerPool),
  )

  val draftedSlots = mutableListOf<MealSlot>()
  for (dayIndex in 0..6) {
    for ((slotType, defaultOwner, pool) in slotTemplates) {
      // Never fall back to a > 25m recipe on BREAKFAST slots even when pools are sparse.
      val selectedRecipe = when {
        pool.isNotEmpty() -> pool[dayIndex % pool.size]
        slotType != MealSlotType.BREAKFAST && schedulable.isNotEmpty() -> schedulable[dayIndex % schedulable.size]
        else -> null
      }
      if (selectedRecipe != null) {
        draftedSlots.add(
          MealSlot(
            slotId = "${boardId}_day_${dayIndex}_${slotType.name.lowercase()}",
            dayIndex = dayIndex,
            slotType = slotType,
            owner = defaultOwner,
            recipeId = selectedRecipe.recipeId,
            targetServings = safeServings,
          ),
        )
      }
    }
  }

  return WeeklyBoard(boardId, householdId, weekStartDateIso, draftedSlots)
}

/**
 * Finds all locally available, allergen-safe replacement recipes for [slotType] where 100% of
 * required Tier-1 quantities are in stock and zero required Tier-2 staples are marked `OUT` (`EC-13`).
 *
 * @param slotType Target slot category (`BREAKFAST` enforces `<= 25m` per `EC-01`).
 * @param recipes Full candidate recipe collection.
 * @param catalog Household ingredient catalog.
 * @param pantry Current household pantry ledger snapshot.
 * @param householdAllergens Active household allergen exclusions.
 * @param targetServings Optional serving override scaling Tier-1 stock checks.
 * @param excludeRecipeId Optional currently scheduled recipe ID to omit from swap choices.
 * @return Ordered list of immediately cookable replacement recipes with resolved BOM lines.
 */
fun findInstantInStockSwaps(
  slotType: MealSlotType,
  recipes: List<Recipe>,
  catalog: List<IngredientCatalogItem>,
  pantry: List<PantryLedgerEntry>,
  householdAllergens: List<String> = emptyList(),
  targetServings: Int? = null,
  excludeRecipeId: String? = null,
): List<Recipe> {
  val catalogById = indexCatalogById(catalog)
  val pantryById = indexPantryById(pantry)
  val matchingSwaps = mutableListOf<Recipe>()

  for (recipe in recipes) {
    if (slotType !in recipe.allowedSlots) continue
    if (excludeRecipeId != null && recipe.recipeId == excludeRecipeId) continue

    // Enforce the <= 25m calibrated morning cap whenever swapping a BREAKFAST slot (EC-01).
    val calibratedMinutes = computeCalibratedMorningMinutes(recipe)
    if (slotType == MealSlotType.BREAKFAST && calibratedMinutes > MAX_WEEKDAY_BREAKFAST_MINUTES) continue

    // Check local market availability and household allergens with pantry-aware substitution (EC-03).
    val availability = evaluateRecipeAvailability(recipe, catalogById, householdAllergens, pantryById)
    if (!availability.isSchedulable) continue

    val safeBase = if (recipe.baseServings > 0) recipe.baseServings else 4
    val effectiveServings = if (targetServings != null && targetServings > 0) targetServings else safeBase
    val scaleFactor = effectiveServings.toDouble() / safeBase.toDouble()

    val requiredTier1Totals = linkedMapOf<String, Double>()
    var allRequiredInStock = true

    for ((idx, resolvedLine) in availability.resolvedBomLines.withIndex()) {
      if (resolvedLine.isOptional) continue
      val originalLine = recipe.bomLines.getOrNull(idx) ?: resolvedLine
      val pantryEntry = pantryById[resolvedLine.ingredientId]
      val tier = pantryEntry?.tier ?: catalogById[resolvedLine.ingredientId]?.tier
        ?: IngredientTier.TIER_1_QUANTITATIVE

      when (tier) {
        IngredientTier.TIER_2_STAPLE -> {
          // EC-11 / EC-13: LOW staples remain cookable; only explicit OUT blocks instant swaps.
          if (pantryEntry != null && (pantryEntry.stapleStatus == StapleStatus.OUT || pantryEntry.isOutOfStock)) {
            allRequiredInStock = false
            break
          }
        }
        IngredientTier.TIER_1_QUANTITATIVE -> {
          val overrideBase =
            recipe.householdBomOverrides[resolvedLine.ingredientId]
              ?: recipe.householdBomOverrides[originalLine.ingredientId]
          val scaledReq = max(0.0, (overrideBase ?: resolvedLine.baseQuantity) * scaleFactor)
          requiredTier1Totals[resolvedLine.ingredientId] =
            (requiredTier1Totals[resolvedLine.ingredientId] ?: 0.0) + scaledReq
        }
      }
    }

    if (!allRequiredInStock) continue

    // Verify 100% of required Tier-1 quantities are currently on hand in the fridge/pantry.
    for ((ingredientId, requiredQty) in requiredTier1Totals) {
      val pantryEntry = pantryById[ingredientId]
      if (pantryEntry == null || pantryEntry.isOutOfStock || pantryEntry.currentQuantity < requiredQty) {
        allRequiredInStock = false
        break
      }
    }

    if (allRequiredInStock) {
      matchingSwaps.add(recipe.copy(bomLines = availability.resolvedBomLines, calibratedActiveMinutes = calibratedMinutes))
    }
  }

  return matchingSwaps
}

/**
 * Replaces the recipe assigned to [slotId] on [board], enforcing the `<= 25m` Breakfast cap
 * (`EC-01`) and optional local availability / allergen gating (`EC-02`).
 *
 * @param board Current 7-day weekly board.
 * @param slotId Target [MealSlot.slotId] to update.
 * @param newRecipe Replacement [Recipe] to assign.
 * @param catalog Optional catalog enforcing local market availability.
 * @param householdAllergens Optional allergen exclusions enforced when [catalog] is non-null.
 * @return Updated immutable [WeeklyBoard] with the target slot reset to `PLANNED`.
 */
fun swapSlotRecipe(
  board: WeeklyBoard,
  slotId: String,
  newRecipe: Recipe,
  catalog: List<IngredientCatalogItem>? = null,
  householdAllergens: List<String> = emptyList(),
): WeeklyBoard {
  val targetSlot = board.slots.find { it.slotId == slotId } ?: return board
  val calibrated = computeCalibratedMorningMinutes(newRecipe)
  if (targetSlot.slotType == MealSlotType.BREAKFAST && calibrated > MAX_WEEKDAY_BREAKFAST_MINUTES) {
    throw ValidationException(
      message = "Breakfast recipe '${newRecipe.title}' takes ${calibrated}m (> ${MAX_WEEKDAY_BREAKFAST_MINUTES}m cap).",
      issues = listOf("calibratedActiveMinutes ($calibrated) > $MAX_WEEKDAY_BREAKFAST_MINUTES"),
    )
  }

  if (catalog != null) {
    val availability = evaluateRecipeAvailability(newRecipe, catalog, householdAllergens)
    if (!availability.isSchedulable) {
      throw IngredientUnavailableException(
        ingredientId = availability.blockedIngredientIds.firstOrNull() ?: "unknown",
        recipeId = newRecipe.recipeId,
      )
    }
  }

  return swapSlotRecipe(board = board, slotId = slotId, newRecipeId = newRecipe.recipeId)
}

/**
 * ID-based overload of [swapSlotRecipe] updating [slotId] to [newRecipeId] in `PLANNED` status.
 *
 * @param board Current 7-day weekly board.
 * @param slotId Target [MealSlot.slotId] to update.
 * @param newRecipeId Replacement [Recipe.recipeId].
 * @return Updated immutable [WeeklyBoard].
 */
fun swapSlotRecipe(board: WeeklyBoard, slotId: String, newRecipeId: String): WeeklyBoard =
  board.copy(
    slots = board.slots.map { slot ->
      when {
        slot.slotId != slotId -> slot
        else -> slot.copy(recipeId = newRecipeId, status = MealSlotStatus.PLANNED, isPreppedAhead = false, preppedTimeSavedMinutes = 0)
      }
    },
  )

/**
 * Marks [slotId] as [MealSlotStatus.COMPLETED_SKIPPED] when the family eats leftovers or skips a
 * meal, releasing its reserved BOM back into the weekly pantry pool (`EC-12`).
 *
 * @param board Current 7-day weekly board.
 * @param slotId Target [MealSlot.slotId].
 * @return Updated immutable [WeeklyBoard].
 */
fun markSlotSkippedOrLeftovers(board: WeeklyBoard, slotId: String): WeeklyBoard =
  board.copy(
    slots = board.slots.map { slot ->
      when {
        slot.slotId != slotId -> slot
        else -> slot.copy(status = MealSlotStatus.COMPLETED_SKIPPED)
      }
    },
  )

/**
 * Updates the active Dad workout macro boost modifier on [slotId] (`EC-07`).
 *
 * @param board Current 7-day weekly board.
 * @param slotId Target [MealSlot.slotId].
 * @param modifier Desired [WorkoutTiming] (`NONE`, `PRE_WORKOUT_CARB`, or `POST_WORKOUT_HIGH_PROTEIN`).
 * @return Updated immutable [WeeklyBoard].
 */
fun toggleSlotWorkoutModifier(board: WeeklyBoard, slotId: String, modifier: WorkoutTiming): WeeklyBoard =
  board.copy(
    slots = board.slots.map { slot ->
      when {
        slot.slotId != slotId -> slot
        else -> slot.copy(activeWorkoutModifier = modifier)
      }
    },
  )

/**
 * Builds a resolved UI-ready [ResolvedMealCard] combining calibrated morning pacing, make-ahead
 * prep savings, flat `1x` workout add-ons (`EC-07`), stage-adapted child safety callouts (`EC-08`),
 * 4-member 1-pot plating, and 8:30 PM night-before prep banners.
 *
 * @param slot Scheduled [MealSlot].
 * @param recipe Associated [Recipe].
 * @param catalog Optional catalog list for substitute resolution and plating synthesis.
 * @param currentHour24 Optional 24-hour clock value (`0..23`); activates night banner at `>= 20`.
 * @param householdProfile Optional household profile driving child stage and 4-way goal plating.
 * @return Immutable [ResolvedMealCard].
 */
fun buildResolvedMealCard(
  slot: MealSlot,
  recipe: Recipe,
  catalog: List<IngredientCatalogItem> = emptyList(),
  currentHour24: Int? = null,
  householdProfile: HouseholdProfile? = null,
): ResolvedMealCard {
  val catalogById = if (catalog.isEmpty()) null else indexCatalogById(catalog)
  val allergens = householdProfile?.householdAllergens.orEmpty()
  val resolvedRecipe = when (catalogById) {
    null -> recipe
    else -> recipe.copy(bomLines = evaluateRecipeAvailability(recipe, catalogById, allergens).resolvedBomLines)
  }

  // Subtract completed weekly-prep savings down to a 5-minute active floor so meals stay realistic.
  val rawCalibrated = computeCalibratedMorningMinutes(resolvedRecipe)
  val defaultSaved = min(
    MAX_NIGHT_BEFORE_SHIFT_MINUTES,
    if (resolvedRecipe.nightBeforePrepMinutes > 0) resolvedRecipe.nightBeforePrepMinutes else 4,
  )
  val savedMinutes = when {
    slot.isPreppedAhead -> max(0, if (slot.preppedTimeSavedMinutes > 0) slot.preppedTimeSavedMinutes else defaultSaved)
    else -> 0
  }
  val calibratedActiveMinutes = if (slot.isPreppedAhead) max(5, rawCalibrated - savedMinutes) else rawCalibrated

  val effectiveBomQuantities = resolveEffectiveMealBom(
    recipe = resolvedRecipe,
    targetServings = slot.targetServings,
    activeWorkoutModifier = slot.activeWorkoutModifier,
    catalogById = catalogById,
    householdAllergens = allergens,
  )

  val matchingModifier = when (slot.activeWorkoutModifier) {
    WorkoutTiming.NONE -> null
    else -> resolvedRecipe.workoutModifiers.find { it.profile == slot.activeWorkoutModifier }
  }

  // Surface the 8:30 PM prep banner only for unprepped PLANNED meals with night-before steps.
  val hasNightWork = resolvedRecipe.nightBeforePrepSteps.isNotEmpty() || resolvedRecipe.nightBeforePrepMinutes > 0
  val isEveningWindow = currentHour24 == null || currentHour24 >= EVENING_PREP_START_HOUR_24
  val showNightBeforeBanner =
    slot.status == MealSlotStatus.PLANNED && hasNightWork && isEveningWindow && !slot.isPreppedAhead

  // EC-08: Preserve raw toddlerPrepTip and compute stage-adapted safety text for Compose Text.
  val cleanToddlerTip = resolvedRecipe.toddlerPrepTip?.trim()?.takeIf { it.isNotEmpty() }
  val activeStage = householdProfile?.youngestStage ?: ChildGrowthStage.TODDLER_1_2Y
  val stageAdaptedTip = activeStage.formatPrepTip(cleanToddlerTip)
  val goalPlating = buildFamilyGoalPlatingGuide(
    recipe = resolvedRecipe,
    catalog = catalog,
    memberGoals = householdProfile?.members.orEmpty(),
    activeLifeEvents = householdProfile?.activeLifeEvents.orEmpty(),
    youngestStage = activeStage,
  )

  return ResolvedMealCard(
    slot = slot,
    recipe = resolvedRecipe,
    calibratedActiveMinutes = calibratedActiveMinutes,
    effectiveBomQuantities = effectiveBomQuantities,
    workoutInstructionAddOn = matchingModifier?.instructionAddOn,
    toddlerPrepTip = cleanToddlerTip,
    stageAdaptedSafetyTip = stageAdaptedTip,
    familyGoalPlating = goalPlating,
    showNightBeforeBanner = showNightBeforeBanner,
    isPreppedAhead = slot.isPreppedAhead,
    preppedTimeSavedMinutes = savedMinutes,
  )
}

// Route Tier-1 catalog ingredients into one of the three culinary batch-prep stations.
private fun classifyIngredientPrepStation(
  ingredientId: String,
  catalogItem: IngredientCatalogItem?,
): PrepStationType? {
  val lowerId = ingredientId.lowercase()
  return when {
    catalogItem?.category == IngredientCategory.BAKERY_GRAINS ||
      Regex("(oat|rice|bean|lentil|grain|quinoa|cornmeal|gari)").containsMatchIn(lowerId) ->
      PrepStationType.GRAINS_OATS_BEANS
    catalogItem?.category == IngredientCategory.PRODUCE ||
      Regex("(banana|spinach|kale|berry|plantain|cassava|orange|tangerine|pineapple|dragon|tomato|onion|pepper|potato|avocado|mango|lemon)").containsMatchIn(lowerId) ->
      PrepStationType.WASH_CHOP_PRODUCE
    catalogItem?.category == IngredientCategory.PROTEIN ||
      catalogItem?.category == IngredientCategory.DAIRY_EGGS ||
      Regex("(egg|milk|chicken|fish|tilapia|shrimp|salmon|tuna|tofu|turkey|yogurt|cheese)").containsMatchIn(lowerId) ->
      PrepStationType.PROTEINS_MARINADES
    else -> null
  }
}

private fun formatAggregatedIngredientsSummary(
  aggregatedIngredients: Map<String, Double>,
  catalogById: Map<String, IngredientCatalogItem>,
): String =
  aggregatedIngredients.entries.filter { it.value > 0.0 }.joinToString(", ") { (ingId, quantity) ->
    val item = catalogById[ingId]
    val rounded = (quantity * 100.0).roundToInt() / 100.0
    val qtyText = if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
    "${item?.displayName ?: ingId} ($qtyText ${item?.baseUnit?.wireCode ?: "units"})"
  }

/**
 * Consolidates all `PLANNED` slots across [board] into a station-grouped [WeeklyPrepSessionPlan]
 * spanning `GRAINS_OATS_BEANS`, `WASH_CHOP_PRODUCE`, `PROTEINS_MARINADES`, and `KID_PORTIONS`.
 *
 * @param board Current 7-day weekly board.
 * @param recipes Recipe collection referenced by board slots.
 * @param catalog Household ingredient catalog.
 * @param completedTaskIds Optional set of station task IDs already checked off.
 * @return Immutable [WeeklyPrepSessionPlan].
 */
fun buildWeeklyMealPrepSessionPlan(
  board: WeeklyBoard,
  recipes: List<Recipe>,
  catalog: List<IngredientCatalogItem> = emptyList(),
  completedTaskIds: Set<String> = emptySet(),
): WeeklyPrepSessionPlan {
  val recipeById = recipes.associateBy { it.recipeId }
  val catalogById = indexCatalogById(catalog)
  // Exclude COOKED and COMPLETED_SKIPPED slots so batch prep only aggregates upcoming meals.
  val plannedSlots = sortSlotsChronologically(board.slots).filter { it.status == MealSlotStatus.PLANNED }

  data class StationAcc(
    val slotIds: MutableList<String> = mutableListOf(),
    val recipeIds: MutableList<String> = mutableListOf(),
    val recipeTitles: MutableList<String> = mutableListOf(),
    val aggregated: LinkedHashMap<String, Double> = linkedMapOf(),
    val notes: MutableList<String> = mutableListOf(),
  )

  val accumulators = PrepStationType.entries.associateWith { StationAcc() }

  fun registerSlot(station: PrepStationType, slotId: String, recipe: Recipe) {
    val acc = accumulators.getValue(station)
    if (slotId !in acc.slotIds) acc.slotIds.add(slotId)
    if (recipe.recipeId !in acc.recipeIds) {
      acc.recipeIds.add(recipe.recipeId)
      acc.recipeTitles.add(recipe.title)
    }
  }

  for (slot in plannedSlots) {
    val recipe = recipeById[slot.recipeId] ?: continue
    val effectiveBom = resolveEffectiveMealBom(
      recipe = recipe,
      targetServings = slot.targetServings,
      activeWorkoutModifier = slot.activeWorkoutModifier,
      catalogById = if (catalogById.isEmpty()) null else catalogById,
    )

    val grainsNotes = accumulators.getValue(PrepStationType.GRAINS_OATS_BEANS).notes
    recipe.nightBeforePrepSteps.filter { it !in grainsNotes }.forEach(grainsNotes::add)

    // Partition effective Tier-1 BOM quantities across culinary stations and toddler portions,
    // excluding TIER_2_STAPLE oils, salts, and spices from Station 4 KID_PORTIONS aggregation.
    for ((ingredientId, qty) in effectiveBom) {
      if (qty <= 0.0) continue
      val catalogItem = catalogById[ingredientId]
      val tier = catalogItem?.tier ?: IngredientTier.TIER_1_QUANTITATIVE
      if (tier != IngredientTier.TIER_1_QUANTITATIVE) continue
      val station = classifyIngredientPrepStation(ingredientId, catalogItem)
      when {
        station != null -> {
          registerSlot(station, slot.slotId, recipe)
          val map = accumulators.getValue(station).aggregated
          map[ingredientId] = (map[ingredientId] ?: 0.0) + qty
        }
        !recipe.toddlerPrepTip.isNullOrBlank() -> {
          registerSlot(PrepStationType.KID_PORTIONS, slot.slotId, recipe)
          val kidMap = accumulators.getValue(PrepStationType.KID_PORTIONS).aggregated
          kidMap[ingredientId] = (kidMap[ingredientId] ?: 0.0) + qty
        }
      }
    }

    val cleanTip = recipe.toddlerPrepTip?.trim().orEmpty()
    if (cleanTip.isNotEmpty()) {
      registerSlot(PrepStationType.KID_PORTIONS, slot.slotId, recipe)
      val kidNotes = accumulators.getValue(PrepStationType.KID_PORTIONS).notes
      if (cleanTip !in kidNotes) kidNotes.add(cleanTip)
    }
  }

  data class StationMeta(
    val taskId: String,
    val title: String,
    val leadIn: String,
    val storageTip: String,
    val minPerSlot: Int,
    val savedPerSlot: Int,
  )

  // Fixed metadata per kitchen prep station driving instructions, storage safety, and time math.
  val stationMetadata = mapOf(
    // Dry grains & soaked beans station: 3m prep / 4m weekday savings per covered meal slot.
    PrepStationType.GRAINS_OATS_BEANS to StationMeta(
      "prep_station_grains_oats_beans", "Grains, Oats & Legumes Dry/Soak Station",
      "Batch-measure and pre-soak dry oats, grains, and legumes for the week",
      "Store dry grains in airtight jars; keep soaked beans/oats sealed on the middle fridge shelf (fresh 5 days).",
      3, 4,
    ),
    // Produce wash & chop station: 4m prep / 5m weekday savings per covered meal slot.
    PrepStationType.WASH_CHOP_PRODUCE to StationMeta(
      "prep_station_wash_chop_produce", "Wash, Peel & Chop Produce Station",
      "Wash, peel, and dice weekly fruits, greens, and aromatics in one batch",
      "Line vented produce containers with a dry paper towel and store in the crisper drawer (fresh 4-5 days).",
      4, 5,
    ),
    // Proteins & marinades station: 4m prep / 5m weekday savings per covered meal slot.
    PrepStationType.PROTEINS_MARINADES to StationMeta(
      "prep_station_proteins_marinades", "Proteins, Eggs & Marinades Station",
      "Pre-portion proteins, whisk egg/dairy bases, and marinate ahead",
      "Keep marinated proteins and pre-whisked egg bases sealed on the coldest bottom fridge shelf (<= 4C, use within 3-4 days).",
      4, 5,
    ),
    // Toddler & school-kid portioning station: 3m prep / 4m weekday savings per covered meal slot.
    PrepStationType.KID_PORTIONS to StationMeta(
      "prep_station_kid_portions", "2yo Choking-Safe & 8yo Kid Portions Station",
      "Pre-portion toddler-safe crushed seeds, soft bite-sized cuts, and kid bowls",
      "Pack 2yo choking-safe portions and 8yo snack cups into color-coded containers on the lower fridge shelf.",
      3, 4,
    ),
  )

  val tasks = mutableListOf<PrepStationTask>()
  val tasksByStation = linkedMapOf<PrepStationType, List<PrepStationTask>>()
  val coveredSlotIds = linkedSetOf<String>()

  // Emit station tasks in deterministic station enum order for stable Compose list keys.
  for (station in PrepStationType.entries) {
    val acc = accumulators.getValue(station)
    if (acc.slotIds.isEmpty()) {
      // Keep an explicit empty list entry so UI station tabs can index every PrepStationType safely.
      tasksByStation[station] = emptyList()
      continue
    }
    val meta = stationMetadata.getValue(station)
    val summary = formatAggregatedIngredientsSummary(acc.aggregated, catalogById)
    val ingClause = if (summary.isNotEmpty()) ": $summary." else "."
    val notesSuffix = if (acc.notes.isNotEmpty()) " Notes: ${acc.notes.joinToString(" | ")}." else ""
    // Enforce a 4-minute floor on both prep duration and time saved per active station.
    val task = PrepStationTask(
      taskId = meta.taskId,
      station = station,
      title = meta.title,
      instruction = "${meta.leadIn} across ${acc.slotIds.size} planned meal(s) (${acc.recipeTitles.joinToString(", ")})$ingClause$notesSuffix",
      slotIds = acc.slotIds.toList(),
      recipeIds = acc.recipeIds.toList(),
      aggregatedIngredients = acc.aggregated.toMap(),
      estimatedPrepMinutes = max(4, acc.slotIds.size * meta.minPerSlot),
      timeSavedMinutes = max(4, acc.slotIds.size * meta.savedPerSlot),
      storageTip = meta.storageTip,
      isCompleted = meta.taskId in completedTaskIds,
    )
    tasks.add(task)
    tasksByStation[station] = listOf(task)
    coveredSlotIds.addAll(acc.slotIds)
  }

  // Return consolidated plan with pre-summed prep duration, weekday time saved, and slot IDs.
  return WeeklyPrepSessionPlan(
    sessionId = "prep_session_${board.boardId}",
    boardId = board.boardId,
    tasks = tasks,
    tasksByStation = tasksByStation,
    totalPrepMinutes = tasks.sumOf { it.estimatedPrepMinutes },
    totalTimeSavedMinutes = tasks.sumOf { it.timeSavedMinutes },
    coveredSlotIds = coveredSlotIds.toList(),
  )
}

/**
 * Applies a completed Weekly Meal Prep Session batch commit in a single `O(M)` pass, stamping
 * `isPreppedAhead = true` and `preppedTimeSavedMinutes` onto covered slots and optionally deducting
 * aggregated Tier-1 ingredients from the pantry (`EC-10`, `EC-11`).
 *
 * @param board Current 7-day weekly board.
 * @param pantry Current household pantry ledger list.
 * @param catalog Household ingredient catalog list.
 * @param plan Active [WeeklyPrepSessionPlan].
 * @param payload Validated batch commit payload.
 * @param nowIso Deterministic ISO-8601 timestamp stamped onto deducted pantry entries.
 * @return Atomic [WeeklyPrepBatchCommitResult].
 */
fun applyWeeklyPrepSessionBatch(
  board: WeeklyBoard,
  pantry: List<PantryLedgerEntry>,
  catalog: List<IngredientCatalogItem>,
  plan: WeeklyPrepSessionPlan,
  payload: WeeklyPrepBatchCommitPayload,
  nowIso: String = DEFAULT_TIMESTAMP_ISO,
): WeeklyPrepBatchCommitResult {
  val catalogById = indexCatalogById(catalog)
  val pantryById = indexPantryById(pantry)
  val completedSet = payload.completedTaskIds.toSet()
  val matchedTasks = plan.tasks.filter { it.taskId in completedSet }

  val slotSavedMinutesMap = linkedMapOf<String, Int>()
  val targetDeductions = linkedMapOf<String, Double>()
  var totalTimeSavedMinutes = 0

  for (task in matchedTasks) {
    totalTimeSavedMinutes += task.timeSavedMinutes
    val perSlotSaved = max(4, (task.timeSavedMinutes.toDouble() / max(1, task.slotIds.size)).roundToInt())
    for (slotId in task.slotIds) {
      slotSavedMinutesMap[slotId] = (slotSavedMinutesMap[slotId] ?: 0) + perSlotSaved
    }
    if (payload.deductPreppedIngredientsFromPantry) {
      task.aggregatedIngredients.forEach { (ingId, qty) ->
        if (qty > 0.0) targetDeductions[ingId] = (targetDeductions[ingId] ?: 0.0) + qty
      }
    }
  }

  val stampedSlotIds = mutableListOf<String>()
  val updatedSlots = board.slots.map { slot ->
    val addedSaved = slotSavedMinutesMap[slot.slotId]
    when (addedSaved) {
      null -> slot
      else -> {
        stampedSlotIds.add(slot.slotId)
        slot.copy(isPreppedAhead = true, preppedTimeSavedMinutes = slot.preppedTimeSavedMinutes + addedSaved)
      }
    }
  }

  val allIngredientIds = linkedSetOf<String>().apply {
    addAll(pantryById.keys)
    if (payload.deductPreppedIngredientsFromPantry) addAll(targetDeductions.keys)
  }

  val updatedPantry = mutableListOf<PantryLedgerEntry>()
  val deductedQuantities = linkedMapOf<String, Double>()

  // Deduct aggregated Tier-1 batch-prep ingredients in one pass while preserving Tier-2 staples.
  for (ingredientId in allIngredientIds) {
    val existingEntry = pantryById[ingredientId]
    val tier = existingEntry?.tier ?: catalogById[ingredientId]?.tier ?: IngredientTier.TIER_1_QUANTITATIVE
    val requestedDeduction = if (payload.deductPreppedIngredientsFromPantry) targetDeductions[ingredientId] else null

    if (tier == IngredientTier.TIER_2_STAPLE) {
      if (requestedDeduction != null) deductedQuantities[ingredientId] = 0.0
      if (existingEntry != null) updatedPantry.add(existingEntry)
      continue
    }

    when {
      requestedDeduction != null && requestedDeduction > 0.0 -> {
        val nextQuantity = max(0.0, (existingEntry?.currentQuantity ?: 0.0) - requestedDeduction)
        val isOut = nextQuantity == 0.0
        val status = if (isOut) StapleStatus.OUT else (existingEntry?.stapleStatus ?: StapleStatus.PLENTY)
        val updatedEntry = existingEntry?.copy(
          tier = IngredientTier.TIER_1_QUANTITATIVE,
          currentQuantity = nextQuantity,
          stapleStatus = status,
          isOutOfStock = isOut,
          updatedAtIso = nowIso,
        ) ?: PantryLedgerEntry(ingredientId, IngredientTier.TIER_1_QUANTITATIVE, nextQuantity, status, isOut, nowIso)
        updatedPantry.add(updatedEntry)
        deductedQuantities[ingredientId] = requestedDeduction
      }
      existingEntry != null -> updatedPantry.add(existingEntry)
    }
  }

  return WeeklyPrepBatchCommitResult(board.copy(slots = updatedSlots), updatedPantry, deductedQuantities, stampedSlotIds, totalTimeSavedMinutes)
}

/** Object namespace exposing menu planning and weekly prep functions for qualified calls. */
object MenuPlannerEngine {
  // Calibrates morning active minutes using ceil((rawPrep - min(5, nightPrep)) * 1.4) + cook.
  /** Delegates to [com.familykitchen.board.domain.computeCalibratedMorningMinutes]. */
  fun computeCalibratedMorningMinutes(rawPrepMinutes: Int, nightBeforePrepMinutes: Int = 0, cookMinutes: Int): Int =
    com.familykitchen.board.domain.computeCalibratedMorningMinutes(rawPrepMinutes, nightBeforePrepMinutes, cookMinutes)

  // Convenience overload evaluating calibrated minutes directly from a Recipe model.
  /** Recipe overload delegating to [com.familykitchen.board.domain.computeCalibratedMorningMinutes]. */
  fun computeCalibratedMorningMinutes(recipe: Recipe): Int =
    com.familykitchen.board.domain.computeCalibratedMorningMinutes(recipe)

  // Checks both BREAKFAST slot allowance and the <= 25m calibrated morning cap (EC-01).
  /** Delegates to [com.familykitchen.board.domain.isWeekdayBreakfastEligible]. */
  fun isWeekdayBreakfastEligible(recipe: Recipe): Boolean =
    com.familykitchen.board.domain.isWeekdayBreakfastEligible(recipe)

  // Drafts all 21 weekly slots across Monday..Sunday for Fred (Breakfast) and Wife (Lunch/Dinner).
  /** Delegates to [com.familykitchen.board.domain.autoDraftWeeklyBoard]. */
  fun autoDraftWeeklyBoard(
    boardId: String,
    householdId: String,
    weekStartDateIso: String,
    recipes: List<Recipe>,
    catalog: List<IngredientCatalogItem>,
    householdAllergens: List<String> = emptyList(),
    defaultServings: Int = 4,
    pantry: List<PantryLedgerEntry>? = null,
  ): WeeklyBoard = com.familykitchen.board.domain.autoDraftWeeklyBoard(
    boardId, householdId, weekStartDateIso, recipes, catalog, householdAllergens, defaultServings, pantry,
  )

  // EC-13 emergency 6:15 AM filter returning 100% in-stock, allergen-safe replacement meals.
  /** Delegates to [com.familykitchen.board.domain.findInstantInStockSwaps]. */
  fun findInstantInStockSwaps(
    slotType: MealSlotType,
    recipes: List<Recipe>,
    catalog: List<IngredientCatalogItem>,
    pantry: List<PantryLedgerEntry>,
    householdAllergens: List<String> = emptyList(),
    targetServings: Int? = null,
    excludeRecipeId: String? = null,
  ): List<Recipe> = com.familykitchen.board.domain.findInstantInStockSwaps(
    slotType, recipes, catalog, pantry, householdAllergens, targetServings, excludeRecipeId,
  )

  // Validates EC-01 morning cap and EC-02 local availability before replacing a board slot's recipe.
  /** Delegates to [com.familykitchen.board.domain.swapSlotRecipe]. */
  fun swapSlotRecipe(
    board: WeeklyBoard,
    slotId: String,
    newRecipe: Recipe,
    catalog: List<IngredientCatalogItem>? = null,
    householdAllergens: List<String> = emptyList(),
  ): WeeklyBoard = com.familykitchen.board.domain.swapSlotRecipe(board, slotId, newRecipe, catalog, householdAllergens)

  // Direct recipeId replacement overload resetting prepped-ahead state on the target slot.
  /** ID overload delegating to [com.familykitchen.board.domain.swapSlotRecipe]. */
  fun swapSlotRecipe(board: WeeklyBoard, slotId: String, newRecipeId: String): WeeklyBoard =
    com.familykitchen.board.domain.swapSlotRecipe(board, slotId, newRecipeId)

  // EC-12 transition releasing reserved BOM stock when the household eats leftovers or skips.
  /** Delegates to [com.familykitchen.board.domain.markSlotSkippedOrLeftovers]. */
  fun markSlotSkippedOrLeftovers(board: WeeklyBoard, slotId: String): WeeklyBoard =
    com.familykitchen.board.domain.markSlotSkippedOrLeftovers(board, slotId)

  // EC-07 toggle switching Dad's flat 1x workout boost profile on an individual slot.
  /** Delegates to [com.familykitchen.board.domain.toggleSlotWorkoutModifier]. */
  fun toggleSlotWorkoutModifier(board: WeeklyBoard, slotId: String, modifier: WorkoutTiming): WeeklyBoard =
    com.familykitchen.board.domain.toggleSlotWorkoutModifier(board, slotId, modifier)

  // EC-08 view builder resolving child growth-stage safety tips, 1-pot plating, and 8:30 PM prep.
  /** Delegates to [com.familykitchen.board.domain.buildResolvedMealCard]. */
  fun buildResolvedMealCard(
    slot: MealSlot,
    recipe: Recipe,
    catalog: List<IngredientCatalogItem> = emptyList(),
    currentHour24: Int? = null,
    householdProfile: HouseholdProfile? = null,
  ): ResolvedMealCard = com.familykitchen.board.domain.buildResolvedMealCard(
    slot, recipe, catalog, currentHour24, householdProfile,
  )

  // Consolidates PLANNED meals into the 4-station Weekly Meal Prep Session plan.
  /** Delegates to [com.familykitchen.board.domain.buildWeeklyMealPrepSessionPlan]. */
  fun buildWeeklyMealPrepSessionPlan(
    board: WeeklyBoard,
    recipes: List<Recipe>,
    catalog: List<IngredientCatalogItem> = emptyList(),
    completedTaskIds: Set<String> = emptySet(),
  ): WeeklyPrepSessionPlan = com.familykitchen.board.domain.buildWeeklyMealPrepSessionPlan(
    board, recipes, catalog, completedTaskIds,
  )

  // Commits checked prep stations and optional Tier-1 pantry deductions in 1 O(M) pass.
  /** Delegates to [com.familykitchen.board.domain.applyWeeklyPrepSessionBatch]. */
  fun applyWeeklyPrepSessionBatch(
    board: WeeklyBoard,
    pantry: List<PantryLedgerEntry>,
    catalog: List<IngredientCatalogItem>,
    plan: WeeklyPrepSessionPlan,
    payload: WeeklyPrepBatchCommitPayload,
    nowIso: String = DEFAULT_TIMESTAMP_ISO,
  ): WeeklyPrepBatchCommitResult = com.familykitchen.board.domain.applyWeeklyPrepSessionBatch(
    board, pantry, catalog, plan, payload, nowIso,
  )
}
