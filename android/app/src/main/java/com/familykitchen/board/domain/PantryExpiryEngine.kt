package com.familykitchen.board.domain

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

// Pure zero-waste pantry expiry analytics, live expiryDateIso calendar aging, recipe match scoring,
// yield scaling, and macro-recomputing swap engine.

/**
 * Rich UI-ready expiring ingredient model rendered inside Smart Pantry's `Action Required` banner.
 *
 * @property entry Underlying household pantry ledger stock entry.
 * @property catalogItem Catalog metadata for display name, unit, package size, and price.
 * @property effectiveStorageLocation Resolved storage zone (`FRIDGE`, `PANTRY`, or `FREEZER`).
 * @property expiryStatus Perishability classification (`EXPIRING_SOON` or `EXPIRED`).
 * @property expiresInDays Remaining shelf-life days (`<= 0` expired, `1..2` expiring soon).
 * @property urgencyLabel Human-readable urgency tag (`"Expired"`, `"Today"`, `"Tomorrow"`, `"In 2 days"`).
 * @property freshnessProgress Urgency bar fill fraction in `0.0f..1.0f` (`0.90f` urgent, `0.76f` 2-day).
 * @property atRiskValueUsd Estimated dollar value at risk if this stock spoils unused.
 * @property optimalPrepHint Culinary usage hint surfaced beneath the quantity row.
 * @property matchingRescueRecipes Schedulable household recipes that consume this ingredient.
 * @property coExpiringPairIngredient Optional second expiring ingredient for 1-tap zero-waste pairing.
 */
@Serializable
data class ExpiringPantryCardModel(
  val entry: PantryLedgerEntry,
  val catalogItem: IngredientCatalogItem,
  val effectiveStorageLocation: StorageLocation,
  val expiryStatus: ExpiryStatus,
  val expiresInDays: Int,
  val urgencyLabel: String,
  val freshnessProgress: Float,
  val atRiskValueUsd: Double,
  val optimalPrepHint: String,
  val matchingRescueRecipes: List<Recipe> = emptyList(),
  val coExpiringPairIngredient: IngredientCatalogItem? = null,
) {
  /** Currency-neutral alias for [atRiskValueUsd] (`PHP` `₱` baseline). */
  val atRiskValue: Double
    get() = atRiskValueUsd

  /** Formats [atRiskValue] using [currency] (`₱` by default). */
  fun formattedAtRiskValue(
    currency: HouseholdCurrency = HouseholdCurrency.PHP,
    includeDecimals: Boolean = false,
  ): String = currency.formatAmount(atRiskValueUsd, includeDecimals)
}

/**
 * Complete Smart Pantry dashboard snapshot computed deterministically from pantry, catalog, and
 * recipe state (`smart_pantry_expiry` spec).
 *
 * Active zone lists ([fridgeItems], [dryPantryItems], [freezerItems]) contain **actively stocked**
 * entries only so depleted `0g` rows never render phantom freshness bars, while depleted items are
 * separated cleanly into [outOfStockItems].
 *
 * @property totalItems Count of stocked household items across all storage zones.
 * @property expiringItems Sorted list of urgent `EXPIRING_SOON` and `EXPIRED` stock cards.
 * @property fridgeItems Actively stocked pantry ledger entries stored in [StorageLocation.FRIDGE].
 * @property dryPantryItems Actively stocked pantry ledger entries stored in [StorageLocation.PANTRY].
 * @property freezerItems Actively stocked pantry ledger entries stored in [StorageLocation.FREEZER].
 * @property atRiskValueUsd Total value across [expiringItems] at risk of food waste (`PHP` `₱` baseline).
 * @property wasteScorePercent Household food-rescue score (`0..100`, `96%` baseline on seed data).
 * @property rescueRecipes Distinct schedulable recipes ranked by number of expiring items rescued.
 * @property outOfStockItems Depleted (`0g` or `StapleStatus.OUT`) ledger entries ready for restock.
 */
@Serializable
data class PantryExpiryDashboard(
  val totalItems: Int,
  val expiringItems: List<ExpiringPantryCardModel>,
  val fridgeItems: List<PantryLedgerEntry>,
  val dryPantryItems: List<PantryLedgerEntry>,
  val freezerItems: List<PantryLedgerEntry>,
  val atRiskValueUsd: Double,
  val wasteScorePercent: Int,
  val rescueRecipes: List<Recipe> = emptyList(),
  val outOfStockItems: List<PantryLedgerEntry> = emptyList(),
) {
  /** Currency-neutral alias for [atRiskValueUsd] (`PHP` `₱` baseline). */
  val atRiskValue: Double
    get() = atRiskValueUsd

  /** Formats [atRiskValue] using [currency] (`₱` by default). */
  fun formattedAtRiskValue(
    currency: HouseholdCurrency = HouseholdCurrency.PHP,
    includeDecimals: Boolean = false,
  ): String = currency.formatAmount(atRiskValueUsd, includeDecimals)
}

/**
 * Recipe-to-pantry readiness and zero-waste rescue evaluation rendered in Clean Recipe Reader and
 * SavorAI Sous-Chef suggestion cards.
 *
 * @property recipeId Evaluated [Recipe.recipeId].
 * @property matchedIngredients Catalog ingredients already available in sufficient home stock.
 * @property missingStaples Missing or insufficient catalog ingredients required by the recipe.
 * @property missingRestockDeltas Whole-package [RestockDelta]s ready for 1-tap Smart Grocery queueing.
 * @property totalRequiredCount Total required BOM ingredient lines evaluated.
 * @property matchedCount Count of required BOM lines satisfied by current home stock.
 * @property matchPercentage Readiness percentage (`0..100`, `100` when every required line is ready).
 * @property isZeroFoodWasteMatch `true` when cooking this recipe rescues `>= 1` expiring item.
 * @property rescuedExpiringIngredientIds Expiring `ingredientId`s consumed by this recipe.
 * @property rescuedValueUsd Estimated value of expiring stock rescued by cooking this meal (`PHP` `₱` baseline).
 */
@Serializable
data class RecipePantryMatchSummary(
  val recipeId: String,
  val matchedIngredients: List<IngredientCatalogItem>,
  val missingStaples: List<IngredientCatalogItem>,
  val missingRestockDeltas: List<RestockDelta>,
  val totalRequiredCount: Int = matchedIngredients.size + missingStaples.size,
  val matchedCount: Int = matchedIngredients.size,
  val matchPercentage: Int = 100,
  val isZeroFoodWasteMatch: Boolean = false,
  val rescuedExpiringIngredientIds: List<String> = emptyList(),
  val rescuedValueUsd: Double = 0.0,
) {
  /** Currency-neutral alias for [rescuedValueUsd] (`PHP` `₱` baseline). */
  val rescuedValue: Double
    get() = rescuedValueUsd

  /** Formats [rescuedValue] using [currency] (`₱` by default). */
  fun formattedRescuedValue(
    currency: HouseholdCurrency = HouseholdCurrency.PHP,
    includeDecimals: Boolean = false,
  ): String = currency.formatAmount(rescuedValueUsd, includeDecimals)
}

private data class ExpiryCoreMacroDensityProfile(
  val kcalPer100Unit: Double,
  val proteinPer100Unit: Double,
  val carbsPer100Unit: Double,
  val fatPer100Unit: Double,
  val gramsEquivalentPerCount: Double = 100.0,
)

/**
 * Computes the complete [PantryExpiryDashboard] for `SmartPantryScreen`, filtering active storage
 * zones to stocked items, isolating depleted items into `outOfStockItems`, aging `expiryDateIso`
 * dynamically when [todayIso] is supplied, ranking expiring stock, matching rescue recipes, and
 * scoring household waste prevention.
 *
 * @param pantry Current household pantry ledger entries.
 * @param catalog Household ingredient catalog metadata.
 * @param recipes Household recipe library for matching zero-waste rescue meals.
 * @param householdAllergens Active household allergen exclusions (`EC-02`).
 * @param rescuedMealsCount Historical rescued expiring meals counter (`KitchenSnapshot`).
 * @param todayIso Optional `"YYYY-MM-DD"` calendar date driving live `expiryDateIso` aging.
 * @return Immutable [PantryExpiryDashboard].
 */
fun evaluatePantryExpiryDashboard(
  pantry: List<PantryLedgerEntry>,
  catalog: List<IngredientCatalogItem>,
  recipes: List<Recipe>,
  householdAllergens: List<String> = emptyList(),
  rescuedMealsCount: Int = 12,
  todayIso: String? = null,
): PantryExpiryDashboard {
  val catalogById = indexCatalogById(catalog)
  val pantryById = indexPantryById(pantry)
  val schedulableRecipes = filterSchedulableRecipes(recipes, catalogById, householdAllergens, pantryById)

  val fridgeItems = mutableListOf<PantryLedgerEntry>()
  val dryPantryItems = mutableListOf<PantryLedgerEntry>()
  val freezerItems = mutableListOf<PantryLedgerEntry>()
  val outOfStockItems = mutableListOf<PantryLedgerEntry>()
  val stockedAgedEntries = mutableListOf<Pair<PantryLedgerEntry, Int?>>()

  // Partition stocked items into Fridge, Dry Pantry, and Freezer while isolating 0g/OUT rows.
  // Keeping 0g items out of active zone shelves prevents the Empty-Fridge Mirage on fresh onboarding.
  for (entry in pantry) {
    if (!expiryCoreIsEntryActivelyStocked(entry)) {
      outOfStockItems.add(entry)
      continue
    }
    val remainingDays = computeRemainingShelfLifeDays(entry, todayIso)
    // Stamp live calendar-aged expiresInDays when todayIso advances past the purchase day.
    val effectiveEntry = when {
      todayIso != null && remainingDays != entry.expiresInDays ->
        entry.copy(expiresInDays = remainingDays)
      else -> entry
    }
    stockedAgedEntries.add(effectiveEntry to remainingDays)

    // Fall back to catalog defaultStorageLocation when an entry has no explicit zone override.
    val item = catalogById[entry.ingredientId]
    val effectiveZone = effectiveEntry.storageLocation
      ?: item?.defaultStorageLocation
      ?: StorageLocation.PANTRY
    when (effectiveZone) {
      StorageLocation.FRIDGE -> fridgeItems.add(effectiveEntry)
      StorageLocation.PANTRY -> dryPantryItems.add(effectiveEntry)
      StorageLocation.FREEZER -> freezerItems.add(effectiveEntry)
    }
  }

  val stockedCount = stockedAgedEntries.size

  // Filter stocked items with EXPIRING_SOON (1..2d) or EXPIRED (<= 0d) status, most urgent first.
  val urgentPairs = stockedAgedEntries
    .mapNotNull { (entry, remainingDays) ->
      if (remainingDays != null && remainingDays <= 2) entry to remainingDays else null
    }
    .sortedWith(
      compareBy<Pair<PantryLedgerEntry, Int>> { it.second }
        .thenBy { it.first.ingredientId },
    )

  val urgentIds = urgentPairs.map { it.first.ingredientId }.toSet()

  val expiringCards = urgentPairs.mapNotNull { (entry, days) ->
    val item = catalogById[entry.ingredientId] ?: return@mapNotNull null
    val zone = entry.storageLocation ?: item.defaultStorageLocation
    val matchingRecipes = schedulableRecipes.filter { recipe ->
      recipe.bomLines.any { bom -> bom.ingredientId == entry.ingredientId }
    }

    // Prefer a co-expiring ingredient that shares a real schedulable rescue recipe with this item.
    val recipePartnerId = matchingRecipes
      .asSequence()
      .flatMap { it.bomLines.asSequence() }
      .map { it.ingredientId }
      .firstOrNull { id -> id != entry.ingredientId && id in urgentIds }
    val fallbackPartnerId = urgentPairs
      .firstOrNull { it.first.ingredientId != entry.ingredientId }
      ?.first
      ?.ingredientId
    val pairItem = (recipePartnerId ?: fallbackPartnerId)?.let { catalogById[it] }

    ExpiringPantryCardModel(
      entry = entry,
      catalogItem = item,
      effectiveStorageLocation = zone,
      expiryStatus = expiryCoreStatusForDays(days),
      expiresInDays = days,
      urgencyLabel = expiryCoreUrgencyBadgeForDays(days),
      freshnessProgress = expiryCoreComputeUrgencyBar(days),
      atRiskValueUsd = expiryCoreEstimateEntryValueUsd(entry, item),
      optimalPrepHint = item.optimalPrepHint?.takeIf { it.isNotBlank() }
        ?: expiryCoreDefaultPrepHint(item.category),
      matchingRescueRecipes = matchingRecipes,
      coExpiringPairIngredient = pairItem,
    )
  }

  // Rank rescue recipes by how many urgent expiring items they rescue simultaneously.
  val rescueRecipes = schedulableRecipes
    .mapNotNull { recipe ->
      val rescuedCount = recipe.bomLines.count { it.ingredientId in urgentIds }
      when {
        rescuedCount > 0 -> recipe to rescuedCount
        else -> null
      }
    }
    .sortedWith(
      compareByDescending<Pair<Recipe, Int>> { it.second }
        .thenBy { it.first.calibratedActiveMinutes },
    )
    .map { it.first }

  val totalAtRiskUsd = expiryCoreRoundUsd(expiringCards.sumOf { it.atRiskValueUsd })
  val expiredCount = expiringCards.count { it.expiryStatus == ExpiryStatus.EXPIRED }
  val expiringSoonCount = expiringCards.count { it.expiryStatus == ExpiryStatus.EXPIRING_SOON }
  val wasteScorePercent = expiryCoreComputeWasteScore(
    stockedCount = stockedCount,
    rescuedMealsCount = max(0, rescuedMealsCount),
    expiredCount = expiredCount,
    expiringSoonCount = expiringSoonCount,
  )

  return PantryExpiryDashboard(
    totalItems = stockedCount,
    expiringItems = expiringCards,
    fridgeItems = fridgeItems,
    dryPantryItems = dryPantryItems,
    freezerItems = freezerItems,
    atRiskValueUsd = totalAtRiskUsd,
    wasteScorePercent = wasteScorePercent,
    rescueRecipes = rescueRecipes,
    outOfStockItems = outOfStockItems,
  )
}

/**
 * Evaluates how completely [pantry] satisfies [recipe]'s scaled BOM, producing matched chips,
 * whole-package missing [RestockDelta]s, readiness percentage, and zero-waste rescued value.
 *
 * @param recipe Target recipe to inspect.
 * @param pantry Current household pantry ledger entries.
 * @param catalog Household ingredient catalog metadata.
 * @param targetServings Desired family serving count (defaults to [Recipe.baseServings]).
 * @param householdAllergens Active household allergen exclusions for `EC-03` substitutions.
 * @param todayIso Optional `"YYYY-MM-DD"` calendar date for live `expiryDateIso` rescue checks.
 * @return Immutable [RecipePantryMatchSummary].
 */
fun evaluateRecipePantryMatch(
  recipe: Recipe,
  pantry: List<PantryLedgerEntry>,
  catalog: List<IngredientCatalogItem>,
  targetServings: Int = recipe.baseServings,
  householdAllergens: List<String> = emptyList(),
  todayIso: String? = null,
): RecipePantryMatchSummary {
  val catalogById = indexCatalogById(catalog)
  val pantryById = indexPantryById(pantry)
  val effectiveBom = resolveEffectiveMealBom(
    recipe = recipe,
    targetServings = targetServings,
    activeWorkoutModifier = WorkoutTiming.NONE,
    catalogById = if (catalogById.isEmpty()) null else catalogById,
    householdAllergens = householdAllergens,
    pantryById = pantryById,
  )

  val matchedIngredients = mutableListOf<IngredientCatalogItem>()
  val missingStaples = mutableListOf<IngredientCatalogItem>()
  val missingRestockDeltas = mutableListOf<RestockDelta>()
  val rescuedExpiringIds = mutableListOf<String>()
  var rescuedValue = 0.0

  for ((ingredientId, requiredQty) in effectiveBom) {
    if (requiredQty <= 0.0) continue
    val item = catalogById[ingredientId] ?: expiryCoreFallbackCatalogItem(ingredientId)
    val entry = pantryById[ingredientId]
    val tier = entry?.tier ?: item.tier

    // Tier-2 staples stay cookable when PLENTY or LOW; Tier-1 requires full scaled quantity.
    val isSatisfied = when (tier) {
      IngredientTier.TIER_2_STAPLE ->
        entry != null && !entry.isOutOfStock && entry.stapleStatus != StapleStatus.OUT
      IngredientTier.TIER_1_QUANTITATIVE ->
        entry != null && !entry.isOutOfStock && entry.currentQuantity >= requiredQty
    }

    when {
      isSatisfied -> {
        matchedIngredients.add(item)
        // Credit rescued food-waste savings whenever an EXPIRING_SOON or EXPIRED item is used.
        val remDays = entry?.let { computeRemainingShelfLifeDays(it, todayIso) }
        if (entry != null && remDays != null && remDays <= 2) {
          rescuedExpiringIds.add(ingredientId)
          rescuedValue += expiryCoreEstimateRescuedPortionUsd(requiredQty, entry, item)
        }
      }
      else -> {
        missingStaples.add(item)
        // Pre-compute whole-package RestockDeltas so UI "+ Queue" buttons commit in O(1).
        val pkgQty = if (item.defaultPackageQuantity > 0.0) item.defaultPackageQuantity else 1.0
        when (tier) {
          IngredientTier.TIER_2_STAPLE ->
            missingRestockDeltas.add(RestockDelta(ingredientId, pkgQty, StapleStatus.PLENTY))
          IngredientTier.TIER_1_QUANTITATIVE -> {
            val available = if (entry == null || entry.isOutOfStock) 0.0 else max(0.0, entry.currentQuantity)
            val deficit = max(0.0, requiredQty - available)
            val packages = ceil(deficit / pkgQty).toInt().coerceAtLeast(1)
            missingRestockDeltas.add(RestockDelta(ingredientId, packages.toDouble() * pkgQty))
          }
        }
      }
    }
  }

  // Clamp readiness percentage in 0..100 (100% when every required BOM line is on hand).
  val totalRequired = matchedIngredients.size + missingStaples.size
  val matchPct = when {
    totalRequired == 0 -> 100
    else -> ((matchedIngredients.size * 100.0) / totalRequired.toDouble()).roundToInt().coerceIn(0, 100)
  }

  return RecipePantryMatchSummary(
    recipeId = recipe.recipeId,
    matchedIngredients = matchedIngredients,
    missingStaples = missingStaples,
    missingRestockDeltas = missingRestockDeltas,
    totalRequiredCount = totalRequired,
    matchedCount = matchedIngredients.size,
    matchPercentage = matchPct,
    isZeroFoodWasteMatch = rescuedExpiringIds.isNotEmpty(),
    rescuedExpiringIngredientIds = rescuedExpiringIds,
    rescuedValueUsd = expiryCoreRoundUsd(rescuedValue),
  )
}

/**
 * Scales a [Recipe]'s baseline servings, BOM quantities, and learned household overrides by
 * [yieldMultiplier] (`1.0` = `1x`, `2.0` = `2x`, `4.0` = `4x`, or custom ratio) for Clean Reader.
 *
 * @param recipe Source recipe to scale.
 * @param yieldMultiplier Strictly positive multiplier (`> 0.0`).
 * @param catalog Optional catalog list retained for overload symmetry.
 * @return Scaled immutable [Recipe] copy.
 */
fun scaleRecipeForYield(
  recipe: Recipe,
  yieldMultiplier: Double,
  catalog: List<IngredientCatalogItem> = emptyList(),
): Recipe {
  requireDomainValid(yieldMultiplier.isFinite() && yieldMultiplier > 0.0, "YieldScale") {
    "yieldMultiplier must be finite and > 0, got $yieldMultiplier"
  }
  val safeBaseServings = if (recipe.baseServings > 0) recipe.baseServings else 4
  val scaledServings = max(1, (safeBaseServings.toDouble() * yieldMultiplier).roundToInt())
  val scaledBomLines = recipe.bomLines.map { line ->
    val scaledQty = ((line.baseQuantity * yieldMultiplier) * 100.0).roundToInt() / 100.0
    line.copy(baseQuantity = max(0.0, scaledQty))
  }
  val scaledOverrides = recipe.householdBomOverrides.mapValues { (_, qty) ->
    val scaledQty = ((qty * yieldMultiplier) * 100.0).roundToInt() / 100.0
    max(0.0, scaledQty)
  }
  return if (catalog.isEmpty()) {
    recipe.copy(baseServings = scaledServings, bomLines = scaledBomLines, householdBomOverrides = scaledOverrides)
  } else {
    recipe.copy(baseServings = scaledServings, bomLines = scaledBomLines, householdBomOverrides = scaledOverrides)
  }
}

/**
 * Parameter-order convenience overload of [scaleRecipeForYield] accepting `(recipe, catalog, yieldMultiplier)`.
 *
 * @param recipe Source recipe to scale.
 * @param catalog Household ingredient catalog list.
 * @param yieldMultiplier Strictly positive multiplier (`> 0.0`).
 * @return Scaled immutable [Recipe] copy.
 */
fun scaleRecipeForYield(
  recipe: Recipe,
  catalog: List<IngredientCatalogItem>,
  yieldMultiplier: Double,
): Recipe = scaleRecipeForYield(recipe, yieldMultiplier, catalog)

/**
 * Swaps [fromIngredientId] with [toIngredientId] inside [recipe], rewriting BOM lines and step
 * ingredient tags while deterministically recomputing per-serving calories and macronutrients.
 *
 * @param recipe Source recipe to update.
 * @param fromIngredientId Original ingredient ID being replaced.
 * @param toIngredientId Locally available replacement catalog ingredient ID.
 * @param catalog Household ingredient catalog list.
 * @return Updated immutable [Recipe] with adjusted BOM and per-serving nutrition facts.
 */
fun swapRecipeIngredient(
  recipe: Recipe,
  fromIngredientId: String,
  toIngredientId: String,
  catalog: List<IngredientCatalogItem>,
): Recipe {
  requireDomainValid(fromIngredientId.isNotBlank() && toIngredientId.isNotBlank(), "IngredientSwap") {
    "fromIngredientId and toIngredientId must be non-blank"
  }
  val catalogById = indexCatalogById(catalog)
  val targetItem = catalogById[toIngredientId]
    ?: throw ValidationException(
      message = "Unknown replacement ingredient '$toIngredientId'.",
      issues = listOf("toIngredientId '$toIngredientId' not in catalog"),
    )
  if (!targetItem.isLocallyAvailable) {
    throw IngredientUnavailableException(ingredientId = toIngredientId, recipeId = recipe.recipeId)
  }

  val fromItem = catalogById[fromIngredientId]
  val safeServings = max(1, recipe.baseServings)
  var totalReplacedBaseQty = 0.0

  val updatedBomLines = recipe.bomLines.map { line ->
    when {
      line.ingredientId != fromIngredientId -> line
      else -> {
        val convertedQty = expiryCoreConvertSwapQuantity(line.baseQuantity, fromItem, targetItem)
        totalReplacedBaseQty += line.baseQuantity
        line.copy(ingredientId = toIngredientId, baseQuantity = convertedQty)
      }
    }
  }

  // If the recipe did not contain fromIngredientId, return unchanged without distorting nutrition.
  if (totalReplacedBaseQty <= 0.0) {
    return recipe
  }

  val updatedSteps = recipe.morningSteps.map { step ->
    when {
      fromIngredientId !in step.stepIngredientIds -> step
      else -> step.copy(
        stepIngredientIds = step.stepIngredientIds.map { id ->
          if (id == fromIngredientId) toIngredientId else id
        }.distinct(),
      )
    }
  }

  // Compute deterministic per-serving calorie and macro deltas between old and new ingredients.
  val oldProfile = expiryCoreInferMacroProfile(fromIngredientId, fromItem)
  val newProfile = expiryCoreInferMacroProfile(toIngredientId, targetItem)
  val oldGrams = expiryCoreToApproxGrams(totalReplacedBaseQty, fromItem?.baseUnit ?: BaseUnit.GRAM, oldProfile)
  val convertedTotalQty = updatedBomLines
    .filter { it.ingredientId == toIngredientId }
    .sumOf { it.baseQuantity }
  val newGrams = expiryCoreToApproxGrams(convertedTotalQty, targetItem.baseUnit, newProfile)

  val kcalDeltaPerServing =
    (((newGrams / 100.0) * newProfile.kcalPer100Unit - (oldGrams / 100.0) * oldProfile.kcalPer100Unit) / safeServings)
      .roundToInt()
  val proteinDeltaPerServing =
    (((newGrams / 100.0) * newProfile.proteinPer100Unit - (oldGrams / 100.0) * oldProfile.proteinPer100Unit) / safeServings)
      .roundToInt()
  val carbsDeltaPerServing =
    (((newGrams / 100.0) * newProfile.carbsPer100Unit - (oldGrams / 100.0) * oldProfile.carbsPer100Unit) / safeServings)
      .roundToInt()
  val fatDeltaPerServing =
    (((newGrams / 100.0) * newProfile.fatPer100Unit - (oldGrams / 100.0) * oldProfile.fatPer100Unit) / safeServings)
      .roundToInt()

  return recipe.copy(
    bomLines = updatedBomLines,
    morningSteps = updatedSteps,
    caloriesPerServing = max(80, recipe.caloriesPerServing + kcalDeltaPerServing),
    proteinGrams = max(2, recipe.proteinGrams + proteinDeltaPerServing),
    carbsGrams = max(2, recipe.carbsGrams + carbsDeltaPerServing),
    fatGrams = max(1, recipe.fatGrams + fatDeltaPerServing),
  )
}

// Exclude out-of-stock or zero-quantity rows from active household storage zone lists.
private fun expiryCoreIsEntryActivelyStocked(entry: PantryLedgerEntry): Boolean =
  when (entry.tier) {
    IngredientTier.TIER_2_STAPLE -> !entry.isOutOfStock && entry.stapleStatus != StapleStatus.OUT
    IngredientTier.TIER_1_QUANTITATIVE -> !entry.isOutOfStock && entry.currentQuantity > 0.0
  }

// Classify <= 0 days as EXPIRED and 1..2 days as EXPIRING_SOON for 48h Action Required banners.
private fun expiryCoreStatusForDays(days: Int): ExpiryStatus =
  when {
    days <= 0 -> ExpiryStatus.EXPIRED
    days <= 2 -> ExpiryStatus.EXPIRING_SOON
    else -> ExpiryStatus.FRESH
  }

// Format concise urgency pill text matching PantryLedgerEntry.urgencyBadgeLabel semantics.
private fun expiryCoreUrgencyBadgeForDays(days: Int): String =
  when {
    days < 0 -> "Expired"
    days == 0 -> "Today"
    days == 1 -> "Tomorrow"
    else -> "In $days days"
  }

// Map remaining shelf-life days to SavorAI urgency bar fill ratios (90% for Tomorrow, 76% for 2d).
private fun expiryCoreComputeUrgencyBar(expiresInDays: Int): Float =
  when {
    expiresInDays <= 0 -> 0.96f
    expiresInDays == 1 -> 0.90f
    expiresInDays == 2 -> 0.76f
    else -> 0.40f
  }

// Prorate dollar value at risk from remaining quantity vs standard package size (0.25x..4.0x).
private fun expiryCoreEstimateEntryValueUsd(
  entry: PantryLedgerEntry,
  item: IngredientCatalogItem,
): Double {
  val pkgQty = if (item.defaultPackageQuantity > 0.0) item.defaultPackageQuantity else 1.0
  val ratio = (entry.currentQuantity / pkgQty).coerceIn(0.25, 4.0)
  return expiryCoreRoundUsd(ratio * max(0.50, item.estimatedPackagePriceUsd))
}

// Cap rescued portion credit at the stock actually on hand so savings never exceed pantry value.
private fun expiryCoreEstimateRescuedPortionUsd(
  usedQty: Double,
  entry: PantryLedgerEntry,
  item: IngredientCatalogItem,
): Double {
  val pkgQty = if (item.defaultPackageQuantity > 0.0) item.defaultPackageQuantity else 1.0
  val cappedQty = minOf(max(0.0, entry.currentQuantity), usedQty)
  val ratio = (cappedQty / pkgQty).coerceAtLeast(0.25)
  return expiryCoreRoundUsd(ratio * max(0.50, item.estimatedPackagePriceUsd))
}

private fun expiryCoreComputeWasteScore(
  stockedCount: Int,
  rescuedMealsCount: Int,
  expiredCount: Int,
  expiringSoonCount: Int,
): Int {
  // Combine stocked items + rescued meals against weighted spoilage risk (96% on clean seed data).
  val denominator = max(10, stockedCount + rescuedMealsCount)
  val soonPenalty = if (expiringSoonCount > 0) max(1, (expiringSoonCount + 1) / 2) else 0
  val totalPenalty = expiredCount * 4 + soonPenalty
  val rawPercent = (((denominator - totalPenalty).toDouble() * 100.0) / denominator.toDouble()).roundToInt()
  return rawPercent.coerceIn(40, 100)
}

// Provide culinary preparation guidance whenever an ingredient omits an explicit optimalPrepHint.
private fun expiryCoreDefaultPrepHint(category: IngredientCategory): String =
  when (category) {
    IngredientCategory.PROTEIN -> "Optimal: Pan-Sear, Sheet-Pan Roast or Braise"
    IngredientCategory.PRODUCE -> "Optimal: Garlic Sauté, Omelette Fold or Fresh Bowl"
    IngredientCategory.DAIRY_EGGS -> "Optimal: Parfait Bowl, Creamy Sauce or Morning Bake"
    IngredientCategory.BAKERY_GRAINS -> "Optimal: Toasted Tartine or One-Pot Grain Base"
    IngredientCategory.PANTRY_STAPLE -> "Optimal: Finishing Drizzle or Marinade Base"
  }

// Synthesize a safe placeholder catalog record if a custom recipe references an unindexed ID.
private fun expiryCoreFallbackCatalogItem(ingredientId: String): IngredientCatalogItem =
  IngredientCatalogItem(
    ingredientId = ingredientId,
    displayName = ingredientId,
    category = IngredientCategory.PRODUCE,
    tier = IngredientTier.TIER_1_QUANTITATIVE,
    baseUnit = BaseUnit.GRAM,
    defaultPackageQuantity = 100.0,
  )

// Convert quantities cleanly across count/g/ml boundaries using culinary unit-weight equivalents.
private fun expiryCoreConvertSwapQuantity(
  baseQty: Double,
  fromItem: IngredientCatalogItem?,
  toItem: IngredientCatalogItem,
): Double {
  if (fromItem == null || fromItem.baseUnit == toItem.baseUnit) return baseQty
  val fromProfile = expiryCoreInferMacroProfile(fromItem.ingredientId, fromItem)
  val toProfile = expiryCoreInferMacroProfile(toItem.ingredientId, toItem)
  val approxGrams = expiryCoreToApproxGrams(baseQty, fromItem.baseUnit, fromProfile)
  return when (toItem.baseUnit) {
    BaseUnit.GRAM, BaseUnit.MILLILITER -> max(1.0, approxGrams.roundToInt().toDouble())
    BaseUnit.COUNT -> max(1.0, (approxGrams / max(20.0, toProfile.gramsEquivalentPerCount)).roundToInt().toDouble())
  }
}

// Normalize count/ml/g quantities into approximate gram weights for per-serving kcal/macro math.
private fun expiryCoreToApproxGrams(
  quantity: Double,
  unit: BaseUnit,
  profile: ExpiryCoreMacroDensityProfile,
): Double =
  when (unit) {
    BaseUnit.GRAM, BaseUnit.MILLILITER -> max(0.0, quantity)
    BaseUnit.COUNT -> max(0.0, quantity * profile.gramsEquivalentPerCount)
  }

// Deterministic USDA FoodData Central per-100g macro lookup powering offline swap calorie deltas.
private fun expiryCoreInferMacroProfile(
  ingredientId: String,
  item: IngredientCatalogItem?,
): ExpiryCoreMacroDensityProfile {
  val key = "${ingredientId.lowercase()} ${item?.displayName?.lowercase().orEmpty()}"
  // Order specific USDA FoodData Central items (butter 717, olive oil 813/100ml, yogurt 73, eggs 143).
  return when {
    key.contains("butter") -> ExpiryCoreMacroDensityProfile(717.0, 0.9, 0.1, 81.1)
    key.contains("oil") -> ExpiryCoreMacroDensityProfile(813.0, 0.0, 0.0, 92.0)
    key.contains("greek_yogurt") || key.contains("yogurt") -> ExpiryCoreMacroDensityProfile(73.0, 10.0, 3.9, 1.9)
    key.contains("cheese") -> ExpiryCoreMacroDensityProfile(403.0, 24.9, 1.3, 33.1)
    key.contains("milk") -> ExpiryCoreMacroDensityProfile(61.0, 3.2, 4.8, 3.3)
    key.contains("egg") -> ExpiryCoreMacroDensityProfile(143.0, 12.6, 0.7, 9.5, gramsEquivalentPerCount = 50.0)
    key.contains("salmon") -> ExpiryCoreMacroDensityProfile(208.0, 20.4, 0.0, 13.4)
    key.contains("tuna") -> ExpiryCoreMacroDensityProfile(198.0, 29.1, 0.0, 8.2)
    key.contains("chicken") || key.contains("turkey") -> ExpiryCoreMacroDensityProfile(165.0, 24.0, 0.0, 7.0)
    key.contains("fish") || key.contains("tilapia") || key.contains("bangus") || key.contains("shrimp") ->
      ExpiryCoreMacroDensityProfile(96.0, 20.1, 0.0, 1.7)
    key.contains("gata") || key.contains("coconut") -> ExpiryCoreMacroDensityProfile(197.0, 2.0, 2.8, 21.3)
    key.contains("tofu") || key.contains("tokwa") -> ExpiryCoreMacroDensityProfile(94.0, 10.0, 2.0, 5.0)
    key.contains("bean") || key.contains("lentil") || key.contains("monggo") ->
      ExpiryCoreMacroDensityProfile(116.0, 7.7, 20.8, 0.5)
    key.contains("egusi") -> ExpiryCoreMacroDensityProfile(567.0, 28.4, 10.6, 47.4)
    key.contains("peanut") || key.contains("nut") || key.contains("kasuy") ||
      key.contains("almond") || key.contains("pumpkin_seed") || key.contains("seed") ->
      ExpiryCoreMacroDensityProfile(588.0, 25.1, 20.0, 50.4)
    key.contains("avocado") -> ExpiryCoreMacroDensityProfile(160.0, 2.0, 8.5, 14.7, gramsEquivalentPerCount = 150.0)
    key.contains("spinach") || key.contains("amaranth") || key.contains("kale") ||
      key.contains("malunggay") || key.contains("pechay") || key.contains("kangkong") ->
      ExpiryCoreMacroDensityProfile(23.0, 2.9, 3.6, 0.4)
    key.contains("oat") -> ExpiryCoreMacroDensityProfile(389.0, 16.9, 66.3, 6.9)
    key.contains("rice") || key.contains("pasta") || key.contains("cornmeal") || key.contains("gari") ->
      ExpiryCoreMacroDensityProfile(365.0, 8.5, 78.0, 1.5)
    key.contains("baguette") ->
      ExpiryCoreMacroDensityProfile(272.0, 10.8, 51.9, 2.4, gramsEquivalentPerCount = 250.0)
    key.contains("wrap") ->
      ExpiryCoreMacroDensityProfile(306.0, 9.3, 52.2, 6.9, gramsEquivalentPerCount = 45.0)
    key.contains("plantain") || key.contains("saba") ->
      ExpiryCoreMacroDensityProfile(122.0, 1.3, 31.9, 0.4, gramsEquivalentPerCount = 179.0)
    key.contains("sweet_potato") || key.contains("kamote") -> ExpiryCoreMacroDensityProfile(86.0, 1.6, 20.1, 0.1)
    key.contains("banana") -> ExpiryCoreMacroDensityProfile(89.0, 1.1, 22.8, 0.3)
    else -> ExpiryCoreMacroDensityProfile(55.0, 1.2, 12.5, 0.3, gramsEquivalentPerCount = 115.0)
  }
}

// Round currency figures to two decimal places while guarding against negative or NaN values.
private fun expiryCoreRoundUsd(value: Double): Double =
  if (!value.isFinite() || value <= 0.0) 0.0 else (value * 100.0).roundToInt() / 100.0

/**
 * Object namespace exposing Smart Pantry expiry, recipe match, yield scaling, and swap functions.
 */
object PantryExpiryEngine {
  // Dashboard aggregator backing SmartPantryScreen's top pulse bar, filters, and Action Required cards.
  /** Delegates to [com.familykitchen.board.domain.evaluatePantryExpiryDashboard]. */
  fun evaluatePantryExpiryDashboard(
    pantry: List<PantryLedgerEntry>,
    catalog: List<IngredientCatalogItem>,
    recipes: List<Recipe>,
    householdAllergens: List<String> = emptyList(),
    rescuedMealsCount: Int = 12,
    todayIso: String? = null,
  ): PantryExpiryDashboard =
    com.familykitchen.board.domain.evaluatePantryExpiryDashboard(
      pantry, catalog, recipes, householdAllergens, rescuedMealsCount, todayIso,
    )

  // Recipe readiness evaluator backing Clean Recipe Reader and SavorAI Sous-Chef cards.
  /** Delegates to [com.familykitchen.board.domain.evaluateRecipePantryMatch]. */
  fun evaluateRecipePantryMatch(
    recipe: Recipe,
    pantry: List<PantryLedgerEntry>,
    catalog: List<IngredientCatalogItem>,
    targetServings: Int = recipe.baseServings,
    householdAllergens: List<String> = emptyList(),
    todayIso: String? = null,
  ): RecipePantryMatchSummary =
    com.familykitchen.board.domain.evaluateRecipePantryMatch(
      recipe, pantry, catalog, targetServings, householdAllergens, todayIso,
    )

  // Yield multiplier (1x, 2x, 4x, Custom) scaling BOM quantities with tabular precision.
  /** Delegates to [com.familykitchen.board.domain.scaleRecipeForYield]. */
  fun scaleRecipeForYield(
    recipe: Recipe,
    yieldMultiplier: Double,
    catalog: List<IngredientCatalogItem> = emptyList(),
  ): Recipe = com.familykitchen.board.domain.scaleRecipeForYield(recipe, yieldMultiplier, catalog)

  // Alternate parameter-order overload matching (recipe, catalog, yieldMultiplier) callers.
  /** Parameter-order overload delegating to [com.familykitchen.board.domain.scaleRecipeForYield]. */
  fun scaleRecipeForYield(
    recipe: Recipe,
    catalog: List<IngredientCatalogItem>,
    yieldMultiplier: Double,
  ): Recipe = com.familykitchen.board.domain.scaleRecipeForYield(recipe, catalog, yieldMultiplier)

  // Deterministic ingredient swapper recomputing per-serving kcal, protein, carbs, and fat.
  /** Delegates to [com.familykitchen.board.domain.swapRecipeIngredient]. */
  fun swapRecipeIngredient(
    recipe: Recipe,
    fromIngredientId: String,
    toIngredientId: String,
    catalog: List<IngredientCatalogItem>,
  ): Recipe =
    com.familykitchen.board.domain.swapRecipeIngredient(recipe, fromIngredientId, toIngredientId, catalog)
}
