package com.familykitchen.board.domain

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

// Pure chronological 21-slot BOM reservation, 0-BOM leftover skip, Palengke/Supermarket venue tagging,
// transparent skipped-staples reporting, and whole-package grocery diff engine (EC-09, EC-11, EC-12).

private val GROCERY_CORE_WEEKDAY_LABELS: List<String> = listOf(
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
 * Computes chronological 21-slot Tier-1 reservations, `0`-BOM leftover-lunch bypass, mid-week
 * shortage alerts, whole-package ceiling rounding (`ceil(netDeficit / defaultPackageQuantity)`),
 * Tier-2 `LOW`/`OUT` restock queueing, `Palengke` vs `Supermarket` venue tags, transparent
 * `skippedStockedItems` summaries, deduplication savings, and sort-mode ordering (`EC-09`, `EC-11`, `EC-12`).
 *
 * @param board Current 7-day household menu board.
 * @param recipes Recipe collection indexed by `recipeId`.
 * @param catalog Household ingredient catalog with package sizes, prices, and aisle categories.
 * @param pantry Current household pantry ledger list.
 * @param householdAllergens Optional active allergen exclusions for substitute resolution.
 * @param aiAddedGroceryIds Manually queued or AI-suggested catalog IDs forced onto the list.
 * @param checkedGroceryItemIds Optional set of checked grocery item IDs from `KitchenSnapshot`.
 * @param grocerySortMode Optional ordering mode (`AISLE_SORTED`, `BY_RECIPE`, or `URGENCY_FIRST`).
 * @param groceryQuantityOverrides Optional custom buy-quantity overrides in canonical [BaseUnit] keyed by `ingredientId`.
 * @param dismissedGroceryItemIds Optional set of `ingredientId`s skipped for the current shopping trip.
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
  groceryQuantityOverrides: Map<String, Double> = emptyMap(),
  dismissedGroceryItemIds: Set<String> = emptySet(),
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
    // EC-12 + V2 Leftover Guard: COOKED, COMPLETED_SKIPPED, and 0-BOM leftover slots reserve 0 groceries.
    // Batch-cooking 2x tonight already reserved 2x BOM on the source meal, so tomorrow's lunch is free.
    val recipe = recipeById[slot.recipeId]
    val isLeftoverLunch = !slot.leftoverSourceRecipeId.isNullOrBlank()
    if (slot.status != MealSlotStatus.PLANNED || recipe == null || isLeftoverLunch) {
      val inactiveSummary = SlotReservationSummary(slot.slotId, slot.dayIndex, slot.slotType, slot.status)
      slotSummaries.add(inactiveSummary)
      slotSummariesBySlotId[slot.slotId] = inactiveSummary
      continue
    }

    plannedMealCount += 1
    val slotUsageLabel = groceryCoreFormatSlotAttribution(slot.dayIndex, slot.slotType, recipe.title)

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
  for ((ingredientId, totalReservedQty) in totalPlannedUsage) {
    val currentStockQty = initialStock[ingredientId] ?: 0.0
    val netDeficit = max(0.0, totalReservedQty - currentStockQty)
    if (netDeficit <= 0.0) continue
    // Suppress items explicitly skipped by the shopper for this trip without altering reservations.
    if (ingredientId in dismissedGroceryItemIds) continue

    val catalogItem = catalogById[ingredientId]
    val category = catalogItem?.category ?: IngredientCategory.PRODUCE
    val baseUnit = catalogItem?.baseUnit ?: BaseUnit.COUNT
    val pkgQty = groceryCoreSafePackageQuantity(catalogItem)
    // Ceiling-round uncovered weekly deficit to whole store packages before checking overrides.
    val packagesToBuy = ceil(netDeficit / pkgQty).toInt().coerceAtLeast(1)
    val unitPkgPrice = groceryCoreSafePackagePriceUsd(catalogItem, category, IngredientTier.TIER_1_QUANTITATIVE)
    // Apply any active shopper buy-quantity override (e.g. 4 kg -> 2 kg) and prorate package cost.
    val buyTerms = groceryCoreResolveBuyTerms(
      basePackagesToBuy = packagesToBuy,
      pkgQty = pkgQty,
      unitPkgPrice = unitPkgPrice,
      baseUnit = baseUnit,
      category = category,
      tier = IngredientTier.TIER_1_QUANTITATIVE,
      rawOverrideQty = groceryQuantityOverrides[ingredientId],
    )

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
        packagesToBuy = buyTerms.effectivePackages,
        restockQuantity = buyTerms.effectiveRestockQty,
        isManuallyQueued = ingredientId in aiAddedGroceryIds,
        mealUsageLabels = usageLabelsByIngredientId[ingredientId]?.toList().orEmpty(),
        estimatedCostUsd = buyTerms.effectiveCostUsd,
        packageDescriptor = buyTerms.descriptor,
        isChecked = ingredientId in checkedGroceryItemIds,
        isQuantityOverridden = buyTerms.isOverridden,
        plannedRestockQuantity = buyTerms.plannedRestockQty,
        marketVenue = groceryCoreResolveVenue(catalogItem, category),
      ),
    )
    includedGroceryIds.add(ingredientId)
    boughtPackagesByIngredientId[ingredientId] = buyTerms.effectivePackages
    restockDeltas.add(
      RestockDelta(ingredientId = ingredientId, addedBaseQuantity = buyTerms.effectiveRestockQty),
    )
  }

  // Step 2: Queue 1 default package for any Tier-2 staple marked LOW or OUT (EC-11).
  val candidateStapleIds = linkedSetOf<String>().apply {
    addAll(pantryById.keys)
    addAll(boardTouchedIngredients)
  }

  for (ingredientId in candidateStapleIds) {
    if (ingredientId in includedGroceryIds || ingredientId in dismissedGroceryItemIds) continue
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
    val pkgQty = groceryCoreSafePackageQuantity(catalogItem)
    val unitPkgPrice = groceryCoreSafePackagePriceUsd(catalogItem, category, IngredientTier.TIER_2_STAPLE)
    val buyTerms = groceryCoreResolveBuyTerms(
      basePackagesToBuy = 1,
      pkgQty = pkgQty,
      unitPkgPrice = unitPkgPrice,
      baseUnit = baseUnit,
      category = category,
      tier = IngredientTier.TIER_2_STAPLE,
      rawOverrideQty = groceryQuantityOverrides[ingredientId],
    )
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
        packagesToBuy = buyTerms.effectivePackages,
        restockQuantity = buyTerms.effectiveRestockQty,
        stapleStatus = pantryEntry.stapleStatus,
        isManuallyQueued = ingredientId in aiAddedGroceryIds,
        mealUsageLabels = usageLabels,
        estimatedCostUsd = buyTerms.effectiveCostUsd,
        packageDescriptor = buyTerms.descriptor,
        isChecked = ingredientId in checkedGroceryItemIds,
        isQuantityOverridden = buyTerms.isOverridden,
        plannedRestockQuantity = buyTerms.plannedRestockQty,
        marketVenue = groceryCoreResolveVenue(catalogItem, category),
      ),
    )
    includedGroceryIds.add(ingredientId)
    boughtPackagesByIngredientId[ingredientId] = buyTerms.effectivePackages
    restockDeltas.add(
      RestockDelta(
        ingredientId = ingredientId,
        addedBaseQuantity = buyTerms.effectiveRestockQty,
        newStapleStatus = StapleStatus.PLENTY,
      ),
    )
  }

  // Step 3: Append manually queued or AI-suggested items (`aiAddedGroceryIds`) not already covered.
  for (manualId in aiAddedGroceryIds) {
    if (manualId.isBlank() || manualId in includedGroceryIds || manualId in dismissedGroceryItemIds) {
      continue
    }
    val catalogItem = catalogById[manualId]
    val pantryEntry = pantryById[manualId]
    val tier = pantryEntry?.tier ?: catalogItem?.tier ?: IngredientTier.TIER_1_QUANTITATIVE
    val category = catalogItem?.category ?: IngredientCategory.PRODUCE
    val baseUnit = catalogItem?.baseUnit ?: BaseUnit.GRAM
    val pkgQty = groceryCoreSafePackageQuantity(catalogItem)
    val unitPkgPrice = groceryCoreSafePackagePriceUsd(catalogItem, category, tier)
    val buyTerms = groceryCoreResolveBuyTerms(
      basePackagesToBuy = 1,
      pkgQty = pkgQty,
      unitPkgPrice = unitPkgPrice,
      baseUnit = baseUnit,
      category = category,
      tier = tier,
      rawOverrideQty = groceryQuantityOverrides[manualId],
    )
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
        packagesToBuy = buyTerms.effectivePackages,
        restockQuantity = buyTerms.effectiveRestockQty,
        stapleStatus = pantryEntry?.stapleStatus,
        isManuallyQueued = true,
        mealUsageLabels = manualLabels,
        estimatedCostUsd = buyTerms.effectiveCostUsd,
        packageDescriptor = buyTerms.descriptor,
        isChecked = manualId in checkedGroceryItemIds,
        isQuantityOverridden = buyTerms.isOverridden,
        plannedRestockQuantity = buyTerms.plannedRestockQty,
        marketVenue = groceryCoreResolveVenue(catalogItem, category),
      ),
    )
    includedGroceryIds.add(manualId)
    boughtPackagesByIngredientId[manualId] = buyTerms.effectivePackages
    val resetStatus = if (tier == IngredientTier.TIER_2_STAPLE) StapleStatus.PLENTY else null
    restockDeltas.add(
      RestockDelta(
        ingredientId = manualId,
        addedBaseQuantity = buyTerms.effectiveRestockQty,
        newStapleStatus = resetStatus,
      ),
    )
  }

  // Step 4: Quantify pantry deduplication savings and populate transparent skippedStockedItems.
  val skippedStockedItems = mutableListOf<SkippedPantryItemSummary>()
  var skippedStockedStapleCount = 0
  var deduplicationSavings = 0.0

  for (ingredientId in boardTouchedIngredients) {
    val catalogItem = catalogById[ingredientId]
    val pantryEntry = pantryById[ingredientId]
    val tier = pantryEntry?.tier ?: catalogItem?.tier ?: IngredientTier.TIER_1_QUANTITATIVE
    val category = catalogItem?.category ?: IngredientCategory.PRODUCE
    val baseUnit = catalogItem?.baseUnit ?: BaseUnit.GRAM
    val unitPkgPrice = groceryCoreSafePackagePriceUsd(catalogItem, category, tier)
    val boughtPackages = boughtPackagesByIngredientId[ingredientId] ?: 0

    // Only credit items genuinely covered by Pantry stock (never items merely dismissed for this trip).
    if (ingredientId !in includedGroceryIds && ingredientId !in dismissedGroceryItemIds) {
      skippedStockedStapleCount += 1
      val requiredQty = totalPlannedUsage[ingredientId] ?: 0.0
      val stockBadge = groceryCoreFormatSkippedStockLabel(pantryEntry, tier, initialStock[ingredientId] ?: 0.0, baseUnit)
      skippedStockedItems.add(
        SkippedPantryItemSummary(
          ingredientId = ingredientId,
          displayName = catalogItem?.displayName ?: ingredientId,
          currentQuantityLabel = stockBadge,
          requiredQuantity = requiredQty,
          baseUnit = baseUnit,
        ),
      )
    }

    val unbufferedPackages = when (tier) {
      IngredientTier.TIER_2_STAPLE -> 1
      IngredientTier.TIER_1_QUANTITATIVE -> {
        val reservedQty = totalPlannedUsage[ingredientId] ?: 0.0
        val pkgQty = groceryCoreSafePackageQuantity(catalogItem)
        if (reservedQty > 0.0) ceil(reservedQty / pkgQty).toInt().coerceAtLeast(1) else 0
      }
    }
    val savedPackages = max(0, unbufferedPackages - boughtPackages)
    deduplicationSavings += savedPackages.toDouble() * unitPkgPrice
  }

  // Step 5: Order items per active GrocerySortMode while preserving AISLE_SORTED discovery order.
  val groceryItems = groceryCoreSortItems(rawGroceryItems, grocerySortMode)
  val remainingTotalUsd = groceryCoreRoundUsd(
    groceryItems.filterNot { it.isChecked }.sumOf { it.estimatedCostUsd },
  )

  return WeeklyGroceryDiff(
    slotSummaries = slotSummaries,
    slotSummariesBySlotId = slotSummariesBySlotId,
    groceryItems = groceryItems,
    groceryItemsByCategory = groceryItems.groupBy { it.category },
    restockDeltas = restockDeltas,
    skippedStockedStapleCount = skippedStockedStapleCount,
    deduplicationSavingsUsd = groceryCoreRoundUsd(deduplicationSavings),
    plannedMealCount = plannedMealCount,
    estimatedTotalCostUsd = remainingTotalUsd,
    skippedStockedItems = skippedStockedItems,
  )
}

// Route catalog items to Palengke vs Supermarket, falling back by category for synthetic test IDs.
private fun groceryCoreResolveVenue(
  catalogItem: IngredientCatalogItem?,
  fallbackCategory: IngredientCategory,
): MarketVenue =
  when {
    catalogItem != null -> resolveMarketVenue(catalogItem)
    fallbackCategory == IngredientCategory.PRODUCE -> MarketVenue.WET_MARKET
    else -> MarketVenue.SUPERMARKET
  }

// Format humanized home-stock badges ("2 kg", "1.5 L", "Plenty") for expandable Pantry Adjusted rows.
private fun groceryCoreFormatSkippedStockLabel(
  pantryEntry: PantryLedgerEntry?,
  tier: IngredientTier,
  stockQty: Double,
  baseUnit: BaseUnit,
): String =
  when (tier) {
    IngredientTier.TIER_2_STAPLE -> when (pantryEntry?.stapleStatus) {
      StapleStatus.LOW -> "Low"
      StapleStatus.OUT -> "Out"
      else -> "Plenty"
    }
    IngredientTier.TIER_1_QUANTITATIVE -> when {
      baseUnit == BaseUnit.GRAM && stockQty >= 1000.0 ->
        "${groceryCoreFormatNumber(stockQty / 1000.0)} kg"
      baseUnit == BaseUnit.MILLILITER && stockQty >= 1000.0 ->
        "${groceryCoreFormatNumber(stockQty / 1000.0)} L"
      else -> "${groceryCoreFormatNumber(stockQty)} ${baseUnit.wireCode}"
    }
  }

// Guard package denominator against zero/NaN so ceiling division never produces Infinity.
private fun groceryCoreSafePackageQuantity(catalogItem: IngredientCatalogItem?): Double {
  val candidate = catalogItem?.defaultPackageQuantity ?: 1.0
  return if (candidate > 0.0 && candidate.isFinite()) candidate else 1.0
}

// Fall back to category-level Bantay Presyo PHP defaults when an item omits package price.
private fun groceryCoreSafePackagePriceUsd(
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

// Clamp non-finite or negative currency totals to 0.0 and round to two decimal places.
private fun groceryCoreRoundUsd(amount: Double): Double =
  if (!amount.isFinite() || amount <= 0.0) 0.0 else (amount * 100.0).roundToInt() / 100.0

// Build meal usage attributions such as "for Wednesday Dinner • One-Pan Lemon Salmon".
private fun groceryCoreFormatSlotAttribution(
  dayIndex: Int,
  slotType: MealSlotType,
  recipeTitle: String,
): String {
  val dayName = GROCERY_CORE_WEEKDAY_LABELS.getOrElse(dayIndex) { "Weekday" }
  val slotName = when (slotType) {
    MealSlotType.BREAKFAST -> "Breakfast"
    MealSlotType.LUNCH -> "Lunch"
    MealSlotType.DINNER -> "Dinner"
  }
  return "for $dayName $slotName • $recipeTitle"
}

private data class GroceryCoreBuyTerms(
  val effectivePackages: Int,
  val effectiveRestockQty: Double,
  val plannedRestockQty: Double,
  val effectiveCostUsd: Double,
  val descriptor: String,
  val isOverridden: Boolean,
)

// Resolves custom buy-quantity overrides, prorated package cost, and whole-pack vs custom labels.
private fun groceryCoreResolveBuyTerms(
  basePackagesToBuy: Int,
  pkgQty: Double,
  unitPkgPrice: Double,
  baseUnit: BaseUnit,
  category: IngredientCategory,
  tier: IngredientTier,
  rawOverrideQty: Double?,
): GroceryCoreBuyTerms {
  val plannedRestockQty = basePackagesToBuy.toDouble() * pkgQty
  val overrideQty = rawOverrideQty?.takeIf { it.isFinite() && it > 0.0 }
  if (overrideQty == null) {
    return GroceryCoreBuyTerms(
      effectivePackages = basePackagesToBuy,
      effectiveRestockQty = plannedRestockQty,
      plannedRestockQty = plannedRestockQty,
      effectiveCostUsd = groceryCoreRoundUsd(basePackagesToBuy * unitPkgPrice),
      descriptor = groceryCoreFormatPackageDescriptor(basePackagesToBuy, plannedRestockQty, baseUnit, category, tier),
      isOverridden = false,
    )
  }

  // Prorate price linearly by (overrideQty / pkgQty) so 2000g or 1500g reflects exact market cost.
  val effectiveRestockQty = overrideQty
  val effectivePackages = ceil(effectiveRestockQty / pkgQty).toInt().coerceAtLeast(1)
  val effectiveCostUsd = groceryCoreRoundUsd((effectiveRestockQty / pkgQty) * unitPkgPrice)
  val isOverridden = abs(effectiveRestockQty - plannedRestockQty) >= 0.01
  val descriptor = when {
    abs(effectiveRestockQty - effectivePackages.toDouble() * pkgQty) < 0.01 ->
      groceryCoreFormatPackageDescriptor(effectivePackages, effectiveRestockQty, baseUnit, category, tier)
    else -> groceryCoreFormatCustomDescriptor(effectiveRestockQty, baseUnit)
  }
  return GroceryCoreBuyTerms(
    effectivePackages = effectivePackages,
    effectiveRestockQty = effectiveRestockQty,
    plannedRestockQty = plannedRestockQty,
    effectiveCostUsd = effectiveCostUsd,
    descriptor = descriptor,
    isOverridden = isOverridden,
  )
}

private fun groceryCoreFormatPackageDescriptor(
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
  val qtyText = groceryCoreFormatNumber(restockQuantity)
  return "$packagesToBuy $pkgLabel ($qtyText ${baseUnit.wireCode})"
}

// Formats non-integer-multiple buy overrides such as "Custom (1500 g)" for clear shopper scanning.
private fun groceryCoreFormatCustomDescriptor(
  restockQuantity: Double,
  baseUnit: BaseUnit,
): String {
  val qtyText = groceryCoreFormatNumber(restockQuantity)
  return "Custom ($qtyText ${baseUnit.wireCode})"
}

private fun groceryCoreFormatNumber(quantity: Double): String {
  // Round to 2 decimal places and drop ".0" on exact integers (e.g. 1500.0 -> "1500").
  val qtyRounded = (quantity * 100.0).roundToInt() / 100.0
  return if (qtyRounded == qtyRounded.toLong().toDouble()) {
    qtyRounded.toLong().toString()
  } else {
    qtyRounded.toString()
  }
}

// Order grocery items by aisle discovery order, earliest planned meal, or shortage urgency.
private fun groceryCoreSortItems(
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
  // Deterministic Monday..Sunday, BREAKFAST < LUNCH < DINNER slot comparator.
  /** Delegates to [com.familykitchen.board.domain.sortSlotsChronologically]. */
  fun sortSlotsChronologically(slots: List<MealSlot>): List<MealSlot> =
    com.familykitchen.board.domain.sortSlotsChronologically(slots)

  // Chronological 21-slot Tier-1 reservation, whole-pack rounding, and venue/dedup diff entrypoint.
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
    groceryQuantityOverrides: Map<String, Double> = emptyMap(),
    dismissedGroceryItemIds: Set<String> = emptySet(),
  ): WeeklyGroceryDiff =
    com.familykitchen.board.domain.computeWeeklyGroceryDiff(
      board, recipes, catalog, pantry, householdAllergens, aiAddedGroceryIds,
      checkedGroceryItemIds, grocerySortMode, groceryQuantityOverrides, dismissedGroceryItemIds,
    )
}
