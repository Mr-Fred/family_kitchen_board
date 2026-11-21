package com.familykitchen.board.domain

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

// Pure chronological 21-slot BOM reservation and whole-package grocery diff engine.
// Enforces EC-09 (chronological contention + ceil rounding), EC-11 (Tier-2 LOW/OUT queueing),
// EC-12 (skipped/cooked reservation release), meal usage labels, and pantry deduplication savings.

private val GROCERY_WEEKDAY_LABELS: List<String> = listOf(
  "Monday",
  "Tuesday",
  "Wednesday",
  "Thursday",
  "Friday",
  "Saturday",
  "Sunday",
)

/**
 * Sorts weekly meal slots in strict chronological order (`dayIndex 0..6`, then
 * `BREAKFAST < LUNCH < DINNER`) without mutating the caller's list (`EC-09`).
 *
 * @param slots Unordered or partially ordered weekly meal slots.
 * @return Chronologically sorted immutable list of [MealSlot]s.
 */
fun sortSlotsChronologically(slots: List<MealSlot>): List<MealSlot> =
  // Order by Monday..Sunday first, then BREAKFAST(0) < LUNCH(1) < DINNER(2) within each day.
  slots.sortedWith(compareBy<MealSlot> { it.dayIndex }.thenBy { it.slotType.chronologicalRank })

/**
 * Computes chronological 21-slot Tier-1 reservations, mid-week shortage alerts, whole-package
 * ceiling rounding (`ceil(netDeficit / defaultPackageQuantity)`), Tier-2 `LOW`/`OUT` restock
 * queueing, skipped/cooked reservation release, planned meal attributions, package descriptors,
 * deduplication savings, and sort-mode ordering (`EC-09`, `EC-11`, `EC-12`).
 *
 * @param board Current 7-day household menu board.
 * @param recipes Recipe collection indexed by `recipeId`.
 * @param catalog Household ingredient catalog with package sizes, prices, and aisle categories.
 * @param pantry Current household pantry ledger list.
 * @param householdAllergens Optional active allergen exclusions for substitute resolution.
 * @param aiAddedGroceryIds Manually queued or AI-suggested catalog IDs forced onto the list.
 * @param checkedGroceryItemIds Optional set of checked grocery item IDs from `KitchenSnapshot`.
 * @param grocerySortMode Optional ordering mode (`AISLE_SORTED`, `BY_RECIPE`, or `URGENCY_FIRST`).
 * @return Complete [WeeklyGroceryDiff] containing per-slot shortages, grouped items, and deltas.
 */
fun computeWeeklyGroceryDiff(
  board: WeeklyBoard,
  recipes: List<Recipe>,
  catalog: List<IngredientCatalogItem>,
  pantry: List<PantryLedgerEntry>,
  householdAllergens: List<String> = emptyList(),
  aiAddedGroceryIds: Set<String> = emptySet(),
  checkedGroceryItemIds: Set<String> = emptySet(),
  grocerySortMode: GrocerySortMode = GrocerySortMode.AISLE_SORTED,
): WeeklyGroceryDiff {
  val recipeById = recipes.associateBy { it.recipeId }
  val catalogById = indexCatalogById(catalog)
  val pantryById = indexPantryById(pantry)

  // Seed initial and running Tier-1 stock, clamping out-of-stock or negative values at 0.0.
  val initialStock = linkedMapOf<String, Double>()
  val runningStock = linkedMapOf<String, Double>()
  for ((ingredientId, entry) in pantryById) {
    val availableQty = if (entry.isOutOfStock) 0.0 else max(0.0, entry.currentQuantity)
    initialStock[ingredientId] = availableQty
    runningStock[ingredientId] = availableQty
  }

  val sortedSlots = sortSlotsChronologically(board.slots)
  val slotSummaries = mutableListOf<SlotReservationSummary>()
  val slotSummariesBySlotId = linkedMapOf<String, SlotReservationSummary>()
  val totalPlannedUsage = linkedMapOf<String, Double>()
  val boardTouchedIngredients = linkedSetOf<String>()
  val usageLabelsByIngredientId = linkedMapOf<String, MutableSet<String>>()
  var plannedMealCount = 0

  for (slot in sortedSlots) {
    // EC-12: Only PLANNED slots reserve stock; COOKED and COMPLETED_SKIPPED release reservations.
    // Tapping "Ate Leftovers / Skip" immediately frees reserved stock for downstream weekday meals.
    val recipe = recipeById[slot.recipeId]
    if (slot.status != MealSlotStatus.PLANNED || recipe == null) {
      val inactiveSummary = SlotReservationSummary(slot.slotId, slot.dayIndex, slot.slotType, slot.status)
      slotSummaries.add(inactiveSummary)
      slotSummariesBySlotId[slot.slotId] = inactiveSummary
      continue
    }

    plannedMealCount += 1
    val slotUsageLabel = formatSlotAttributionLabel(slot.dayIndex, slot.slotType, recipe.title)

    // Resolve scaled BOM quantities, substitute rewrites (EC-03), and flat 1x Dad workout boosts (EC-07).
    val effectiveBom = resolveEffectiveMealBom(
      recipe = recipe,
      targetServings = slot.targetServings,
      activeWorkoutModifier = slot.activeWorkoutModifier,
      catalogById = catalogById,
      householdAllergens = householdAllergens,
      pantryById = pantryById,
    )

    val reservedQuantities = linkedMapOf<String, Double>()
    val shortages = mutableListOf<SlotIngredientShortage>()

    for ((ingredientId, requiredQty) in effectiveBom) {
      if (requiredQty <= 0.0) continue
      boardTouchedIngredients.add(ingredientId)
      usageLabelsByIngredientId.getOrPut(ingredientId) { linkedSetOf() }.add(slotUsageLabel)

      val pantryEntry = pantryById[ingredientId]
      val catalogItem = catalogById[ingredientId]
      val tier = pantryEntry?.tier ?: catalogItem?.tier ?: IngredientTier.TIER_1_QUANTITATIVE

      // EC-11: Tier-2 staples are managed via tri-state status, never gram-by-gram reservation.
      if (tier == IngredientTier.TIER_2_STAPLE) continue

      reservedQuantities[ingredientId] = requiredQty
      totalPlannedUsage[ingredientId] = (totalPlannedUsage[ingredientId] ?: 0.0) + requiredQty
      if (ingredientId !in initialStock) initialStock[ingredientId] = 0.0

      val availableBeforeSlot = runningStock[ingredientId] ?: 0.0
      // EC-09: Record exact mid-week slot contention whenever demand exceeds remaining stock.
      if (requiredQty > availableBeforeSlot) {
        shortages.add(
          SlotIngredientShortage(
            ingredientId = ingredientId,
            requiredQuantity = requiredQty,
            availableBeforeSlot = availableBeforeSlot,
            deficitQuantity = requiredQty - availableBeforeSlot,
          ),
        )
      }
      // Clamp running balance at zero so subsequent slots see accurate remaining availability.
      runningStock[ingredientId] = max(0.0, availableBeforeSlot - requiredQty)
    }

    val activeSummary = SlotReservationSummary(
      slotId = slot.slotId,
      dayIndex = slot.dayIndex,
      slotType = slot.slotType,
      status = slot.status,
      reservedQuantities = reservedQuantities,
      shortages = shortages,
    )
    slotSummaries.add(activeSummary)
    slotSummariesBySlotId[slot.slotId] = activeSummary
  }

  val rawGroceryItems = mutableListOf<GroceryListItem>()
  val restockDeltas = mutableListOf<RestockDelta>()
  val includedGroceryIds = mutableSetOf<String>()
  val boughtPackagesByIngredientId = linkedMapOf<String, Int>()

  // Step 1: Compute Tier-1 net weekly deficits and whole-package ceiling rounding (EC-09).
  // Example: needing 8 eggs with 6 in stock -> netDeficit = 2 -> ceil(2 / 12) = 1 carton (12 eggs).
  for ((ingredientId, totalReservedQty) in totalPlannedUsage) {
    val currentStockQty = initialStock[ingredientId] ?: 0.0
    val netDeficit = max(0.0, totalReservedQty - currentStockQty)
    if (netDeficit <= 0.0) continue

    val catalogItem = catalogById[ingredientId]
    val category = catalogItem?.category ?: IngredientCategory.PRODUCE
    val baseUnit = catalogItem?.baseUnit ?: BaseUnit.COUNT
    val pkgQty = safePackageQuantity(catalogItem)
    val packagesToBuy = ceil(netDeficit / pkgQty).toInt().coerceAtLeast(1)
    val restockQuantity = packagesToBuy.toDouble() * pkgQty
    val unitPkgPrice = safePackagePriceUsd(catalogItem, category, IngredientTier.TIER_1_QUANTITATIVE)

    rawGroceryItems.add(
      GroceryListItem(
        ingredientId = ingredientId,
        displayName = catalogItem?.displayName ?: ingredientId,
        category = category,
        tier = IngredientTier.TIER_1_QUANTITATIVE,
        baseUnit = baseUnit,
        totalReservedQuantity = totalReservedQty,
        currentStockQuantity = currentStockQty,
        netDeficit = netDeficit,
        defaultPackageQuantity = pkgQty,
        packagesToBuy = packagesToBuy,
        restockQuantity = restockQuantity,
        isManuallyQueued = ingredientId in aiAddedGroceryIds,
        mealUsageLabels = usageLabelsByIngredientId[ingredientId]?.toList().orEmpty(),
        estimatedCostUsd = roundGroceryUsd(packagesToBuy * unitPkgPrice),
        packageDescriptor = formatPackageDescriptor(packagesToBuy, restockQuantity, baseUnit, category, IngredientTier.TIER_1_QUANTITATIVE),
        isChecked = ingredientId in checkedGroceryItemIds,
      ),
    )
    includedGroceryIds.add(ingredientId)
    boughtPackagesByIngredientId[ingredientId] = packagesToBuy
    restockDeltas.add(RestockDelta(ingredientId = ingredientId, addedBaseQuantity = restockQuantity))
  }

  // Step 2: Queue 1 default package for any Tier-2 staple marked LOW or OUT (EC-11).
  // Restocking a Tier-2 item resets its stapleStatus back to PLENTY in 1 atomic batch.
  val candidateStapleIds = linkedSetOf<String>().apply {
    addAll(pantryById.keys)
    addAll(boardTouchedIngredients)
  }

  for (ingredientId in candidateStapleIds) {
    if (ingredientId in includedGroceryIds) continue
    val pantryEntry = pantryById[ingredientId] ?: continue
    if (pantryEntry.tier != IngredientTier.TIER_2_STAPLE) continue

    val needsRestock =
      pantryEntry.stapleStatus == StapleStatus.LOW ||
        pantryEntry.stapleStatus == StapleStatus.OUT ||
        pantryEntry.isOutOfStock
    if (!needsRestock) continue

    val catalogItem = catalogById[ingredientId]
    val category = catalogItem?.category ?: IngredientCategory.PANTRY_STAPLE
    val baseUnit = catalogItem?.baseUnit ?: BaseUnit.MILLILITER
    val pkgQty = safePackageQuantity(catalogItem)
    val unitPkgPrice = safePackagePriceUsd(catalogItem, category, IngredientTier.TIER_2_STAPLE)
    val usageLabels = usageLabelsByIngredientId[ingredientId]?.toList()
      ?.takeIf { it.isNotEmpty() } ?: listOf("for Pantry Staple Restock")

    rawGroceryItems.add(
      GroceryListItem(
        ingredientId = ingredientId,
        displayName = catalogItem?.displayName ?: ingredientId,
        category = category,
        tier = IngredientTier.TIER_2_STAPLE,
        baseUnit = baseUnit,
        totalReservedQuantity = 0.0,
        currentStockQuantity = pantryEntry.currentQuantity,
        netDeficit = pkgQty,
        defaultPackageQuantity = pkgQty,
        packagesToBuy = 1,
        restockQuantity = pkgQty,
        stapleStatus = pantryEntry.stapleStatus,
        isManuallyQueued = ingredientId in aiAddedGroceryIds,
        mealUsageLabels = usageLabels,
        estimatedCostUsd = roundGroceryUsd(unitPkgPrice),
        packageDescriptor = formatPackageDescriptor(1, pkgQty, baseUnit, category, IngredientTier.TIER_2_STAPLE),
        isChecked = ingredientId in checkedGroceryItemIds,
      ),
    )
    includedGroceryIds.add(ingredientId)
    boughtPackagesByIngredientId[ingredientId] = 1
    restockDeltas.add(RestockDelta(ingredientId, pkgQty, StapleStatus.PLENTY))
  }

  // Step 3: Append manually queued or AI-suggested items (`aiAddedGroceryIds`) not already covered.
  // Supports 1-tap "+ Need to Buy" in Pantry and "+ Add Suggested Ingredients" in AI Chef.
  for (manualId in aiAddedGroceryIds) {
    if (manualId.isBlank() || manualId in includedGroceryIds) continue
    val catalogItem = catalogById[manualId]
    val pantryEntry = pantryById[manualId]
    val tier = pantryEntry?.tier ?: catalogItem?.tier ?: IngredientTier.TIER_1_QUANTITATIVE
    val category = catalogItem?.category ?: IngredientCategory.PRODUCE
    val baseUnit = catalogItem?.baseUnit ?: BaseUnit.GRAM
    val pkgQty = safePackageQuantity(catalogItem)
    val unitPkgPrice = safePackagePriceUsd(catalogItem, category, tier)
    val currentStock = when {
      pantryEntry == null || pantryEntry.isOutOfStock -> 0.0
      else -> max(0.0, pantryEntry.currentQuantity)
    }
    val manualLabels = usageLabelsByIngredientId[manualId]?.toList()
      ?.takeIf { it.isNotEmpty() } ?: listOf("for Household Kitchen Queue")

    rawGroceryItems.add(
      GroceryListItem(
        ingredientId = manualId,
        displayName = catalogItem?.displayName ?: manualId,
        category = category,
        tier = tier,
        baseUnit = baseUnit,
        totalReservedQuantity = totalPlannedUsage[manualId] ?: 0.0,
        currentStockQuantity = currentStock,
        netDeficit = pkgQty,
        defaultPackageQuantity = pkgQty,
        packagesToBuy = 1,
        restockQuantity = pkgQty,
        stapleStatus = pantryEntry?.stapleStatus,
        isManuallyQueued = true,
        mealUsageLabels = manualLabels,
        estimatedCostUsd = roundGroceryUsd(unitPkgPrice),
        packageDescriptor = formatPackageDescriptor(1, pkgQty, baseUnit, category, tier),
        isChecked = manualId in checkedGroceryItemIds,
      ),
    )
    includedGroceryIds.add(manualId)
    boughtPackagesByIngredientId[manualId] = 1
    val resetStatus = if (tier == IngredientTier.TIER_2_STAPLE) StapleStatus.PLENTY else null
    restockDeltas.add(RestockDelta(manualId, pkgQty, resetStatus))
  }

  // Step 4: Quantify automatic pantry deduplication count and USD savings across planned meals.
  var skippedStockedStapleCount = 0
  var deduplicationSavings = 0.0
  for (ingredientId in boardTouchedIngredients) {
    val catalogItem = catalogById[ingredientId]
    val pantryEntry = pantryById[ingredientId]
    val tier = pantryEntry?.tier ?: catalogItem?.tier ?: IngredientTier.TIER_1_QUANTITATIVE
    val category = catalogItem?.category ?: IngredientCategory.PRODUCE
    val unitPkgPrice = safePackagePriceUsd(catalogItem, category, tier)
    val boughtPackages = boughtPackagesByIngredientId[ingredientId] ?: 0

    if (ingredientId !in includedGroceryIds) {
      skippedStockedStapleCount += 1
    }

    val unbufferedPackages = when (tier) {
      IngredientTier.TIER_2_STAPLE -> 1
      IngredientTier.TIER_1_QUANTITATIVE -> {
        val reservedQty = totalPlannedUsage[ingredientId] ?: 0.0
        val pkgQty = safePackageQuantity(catalogItem)
        if (reservedQty > 0.0) ceil(reservedQty / pkgQty).toInt().coerceAtLeast(1) else 0
      }
    }
    val savedPackages = max(0, unbufferedPackages - boughtPackages)
    deduplicationSavings += savedPackages.toDouble() * unitPkgPrice
  }

  // Step 5: Order items per active GrocerySortMode while preserving AISLE_SORTED discovery order.
  val groceryItems = sortGroceryItems(rawGroceryItems, grocerySortMode)
  val remainingTotalUsd = roundGroceryUsd(
    groceryItems.filterNot { it.isChecked }.sumOf { it.estimatedCostUsd },
  )

  return WeeklyGroceryDiff(
    slotSummaries = slotSummaries,
    slotSummariesBySlotId = slotSummariesBySlotId,
    groceryItems = groceryItems,
    groceryItemsByCategory = groceryItems.groupBy { it.category },
    restockDeltas = restockDeltas,
    skippedStockedStapleCount = skippedStockedStapleCount,
    deduplicationSavingsUsd = roundGroceryUsd(deduplicationSavings),
    plannedMealCount = plannedMealCount,
    estimatedTotalCostUsd = remainingTotalUsd,
  )
}

// Guard package denominator against zero/NaN so ceiling division never produces Infinity.
private fun safePackageQuantity(catalogItem: IngredientCatalogItem?): Double {
  val candidate = catalogItem?.defaultPackageQuantity ?: 1.0
  return if (candidate > 0.0 && candidate.isFinite()) candidate else 1.0
}

private fun safePackagePriceUsd(
  catalogItem: IngredientCatalogItem?,
  category: IngredientCategory,
  tier: IngredientTier,
): Double {
  val candidate = catalogItem?.estimatedPackagePriceUsd
  return when {
    candidate != null && candidate.isFinite() && candidate >= 0.0 -> candidate
    else -> defaultPackagePriceUsdFor(category, tier)
  }
}

private fun roundGroceryUsd(amount: Double): Double =
  if (!amount.isFinite() || amount <= 0.0) 0.0 else (amount * 100.0).roundToInt() / 100.0

private fun formatSlotAttributionLabel(
  dayIndex: Int,
  slotType: MealSlotType,
  recipeTitle: String,
): String {
  val dayName = GROCERY_WEEKDAY_LABELS.getOrElse(dayIndex) { "Weekday" }
  val slotName = when (slotType) {
    MealSlotType.BREAKFAST -> "Breakfast"
    MealSlotType.LUNCH -> "Lunch"
    MealSlotType.DINNER -> "Dinner"
  }
  return "for $dayName $slotName • $recipeTitle"
}

private fun formatPackageDescriptor(
  packagesToBuy: Int,
  restockQuantity: Double,
  baseUnit: BaseUnit,
  category: IngredientCategory,
  tier: IngredientTier,
): String {
  val singularNoun = when {
    tier == IngredientTier.TIER_2_STAPLE && baseUnit == BaseUnit.MILLILITER -> "bottle"
    tier == IngredientTier.TIER_2_STAPLE -> "jar"
    category == IngredientCategory.DAIRY_EGGS && baseUnit == BaseUnit.COUNT -> "carton"
    category == IngredientCategory.DAIRY_EGGS && baseUnit == BaseUnit.MILLILITER -> "carton"
    category == IngredientCategory.BAKERY_GRAINS && baseUnit == BaseUnit.COUNT -> "pack"
    category == IngredientCategory.PRODUCE && baseUnit == BaseUnit.GRAM -> "bag"
    else -> "pack"
  }
  val pkgLabel = if (packagesToBuy == 1) singularNoun else "${singularNoun}s"
  val qtyRounded = (restockQuantity * 100.0).roundToInt() / 100.0
  val qtyText = if (qtyRounded == qtyRounded.toLong().toDouble()) {
    qtyRounded.toLong().toString()
  } else {
    qtyRounded.toString()
  }
  return "$packagesToBuy $pkgLabel ($qtyText ${baseUnit.wireCode})"
}

private fun sortGroceryItems(
  items: List<GroceryListItem>,
  sortMode: GrocerySortMode,
): List<GroceryListItem> =
  when (sortMode) {
    GrocerySortMode.AISLE_SORTED -> items
    GrocerySortMode.BY_RECIPE -> items.sortedWith(
      compareBy<GroceryListItem> { it.mealUsageLabels.firstOrNull().orEmpty() }
        .thenBy { it.displayName },
    )
    GrocerySortMode.URGENCY_FIRST -> items.sortedWith(
      compareBy<GroceryListItem> { it.isManuallyQueued }
        .thenBy { it.tier == IngredientTier.TIER_2_STAPLE }
        .thenByDescending { it.packagesToBuy }
        .thenByDescending { it.netDeficit },
    )
  }

/**
 * Object namespace exposing weekly grocery diff calculations for qualified `GroceryDiffEngine.*` calls.
 */
object GroceryDiffEngine {
  /** Delegates to [com.familykitchen.board.domain.sortSlotsChronologically]. */
  fun sortSlotsChronologically(slots: List<MealSlot>): List<MealSlot> =
    com.familykitchen.board.domain.sortSlotsChronologically(slots)

  /** Delegates to [com.familykitchen.board.domain.computeWeeklyGroceryDiff]. */
  fun computeWeeklyGroceryDiff(
    board: WeeklyBoard,
    recipes: List<Recipe>,
    catalog: List<IngredientCatalogItem>,
    pantry: List<PantryLedgerEntry>,
    householdAllergens: List<String> = emptyList(),
    aiAddedGroceryIds: Set<String> = emptySet(),
    checkedGroceryItemIds: Set<String> = emptySet(),
    grocerySortMode: GrocerySortMode = GrocerySortMode.AISLE_SORTED,
  ): WeeklyGroceryDiff =
    com.familykitchen.board.domain.computeWeeklyGroceryDiff(
      board = board,
      recipes = recipes,
      catalog = catalog,
      pantry = pantry,
      householdAllergens = householdAllergens,
      aiAddedGroceryIds = aiAddedGroceryIds,
      checkedGroceryItemIds = checkedGroceryItemIds,
      grocerySortMode = grocerySortMode,
    )
}
