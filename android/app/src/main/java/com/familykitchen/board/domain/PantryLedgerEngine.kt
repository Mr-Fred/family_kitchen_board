package com.familykitchen.board.domain

import kotlin.math.max
import kotlin.math.roundToInt

// Pure O(M) single-pass pantry ledger reduction, zone-aware shelf-life, FIFO restock, and audit engine.
// Enforces EC-04 (one-time override), EC-05 (normalized household defaults), EC-06 (ad-hoc extras),
// EC-07 (flat 1x Dad workout boost), EC-10 (floor-at-zero clamping), and EC-11 (Tier-2 tri-state).

/**
 * Computes the baseline shelf-life window in days when storing [item] inside [location], applying
 * USDA FoodKeeper freezer extensions (`90d` proteins/dairy/bakery, `180d` produce/staples) and
 * chilled pantry-to-fridge caps.
 *
 * @param item Catalog ingredient metadata providing category, default zone, and baseline shelf life.
 * @param location Target physical storage zone (`FRIDGE`, `PANTRY`, or `FREEZER`).
 * @return Positive shelf-life duration in days for [location].
 */
fun defaultShelfLifeDaysForZone(
  item: IngredientCatalogItem,
  location: StorageLocation,
): Int {
  val safeDefault = max(1, item.defaultShelfLifeDays)
  return when (location) {
    // Sub-zero storage extends perishable proteins/bread/dairy to >= 90d and produce/staples to >= 180d.
    StorageLocation.FREEZER -> {
      val freezerFloor = when (item.category) {
        IngredientCategory.PRODUCE, IngredientCategory.PANTRY_STAPLE -> 180
        IngredientCategory.PROTEIN, IngredientCategory.BAKERY_GRAINS, IngredientCategory.DAIRY_EGGS -> 90
      }
      maxOf(freezerFloor, safeDefault)
    }

    // Dry ambient shelves retain the catalog's canonical shelf-life window.
    StorageLocation.PANTRY -> safeDefault

    // Chilling an ambient pantry item caps its open/chilled window at 30d; native fridge items keep default.
    StorageLocation.FRIDGE -> when (item.defaultStorageLocation) {
      StorageLocation.PANTRY -> minOf(safeDefault, 30)
      else -> safeDefault
    }
  }
}

/**
 * Resolves the effective Bill-of-Materials quantities for a meal, combining learned
 * `householdBomOverrides` (`EC-05`), family serving scaling (`targetServings / baseServings`),
 * substitute group rewrites (`EC-03`), and flat `1x` Dad workout boosts (`EC-07`).
 *
 * @param recipe Recipe definition containing baseline BOM lines and workout modifiers.
 * @param targetServings Requested household serving count (defaults to [Recipe.baseServings]).
 * @param activeWorkoutModifier Active Dad workout boost (`NONE`, `PRE_WORKOUT_CARB`, or `POST_WORKOUT_HIGH_PROTEIN`).
 * @param catalogById Optional catalog lookup map for substitute resolution.
 * @param householdAllergens Optional active allergen exclusions.
 * @param pantryById Optional pantry map for in-stock substitute tie-breaking.
 * @return Immutable map from resolved `ingredientId` to required canonical quantity.
 */
fun resolveEffectiveMealBom(
  recipe: Recipe,
  targetServings: Int = recipe.baseServings,
  activeWorkoutModifier: WorkoutTiming = WorkoutTiming.NONE,
  catalogById: Map<String, IngredientCatalogItem>? = null,
  householdAllergens: List<String> = emptyList(),
  pantryById: Map<String, PantryLedgerEntry>? = null,
): Map<String, Double> {
  // Rewrite unavailable or allergen-conflicting BOM lines first when catalog context is provided.
  val resolvedLines = when (catalogById) {
    null -> recipe.bomLines
    else -> evaluateRecipeAvailability(recipe, catalogById, householdAllergens, pantryById).resolvedBomLines
  }

  // Guard serving ratio denominators against zero or malformed negative values.
  val safeBaseServings = if (recipe.baseServings > 0) recipe.baseServings else 4
  val safeTargetServings = if (targetServings > 0) targetServings else safeBaseServings
  val servingScale = safeTargetServings.toDouble() / safeBaseServings.toDouble()
  val effectiveQuantities = linkedMapOf<String, Double>()

  // Scale each resolved baseline BOM line by servingScale, preferring learned household overrides.
  // Checking both resolvedId and originalId ensures overrides survive EC-03 substitutions cleanly.
  resolvedLines.forEachIndexed { idx, resolvedLine ->
    val originalLine = recipe.bomLines.getOrNull(idx) ?: resolvedLine
    val overrideBaseQty =
      recipe.householdBomOverrides[resolvedLine.ingredientId]
        ?: recipe.householdBomOverrides[originalLine.ingredientId]
    val scaledQuantity = max(0.0, (overrideBaseQty ?: resolvedLine.baseQuantity) * servingScale)
    effectiveQuantities[resolvedLine.ingredientId] =
      (effectiveQuantities[resolvedLine.ingredientId] ?: 0.0) + scaledQuantity
  }

  // EC-07: Apply Dad's workout boost flat (1x), never multiplied by targetServings / baseServings.
  // Even if guests scale a 4-person meal to 8 servings, Dad's personal boost adds +2 eggs flat.
  if (activeWorkoutModifier != WorkoutTiming.NONE) {
    for (modifier in recipe.workoutModifiers) {
      if (modifier.profile != activeWorkoutModifier) continue
      for (extraLine in modifier.extraBomLines) {
        val targetId = pantryZoneResolveWorkoutIngredientId(
          recipe, extraLine, catalogById, householdAllergens, pantryById,
        )
        effectiveQuantities[targetId] =
          (effectiveQuantities[targetId] ?: 0.0) + max(0.0, extraLine.baseQuantity)
      }
    }
  }

  return effectiveQuantities
}

/**
 * List-backed overload of [resolveEffectiveMealBom] accepting a raw catalog list.
 *
 * @param recipe Recipe definition containing baseline BOM lines and workout modifiers.
 * @param targetServings Requested household serving count.
 * @param activeWorkoutModifier Active Dad workout boost profile.
 * @param catalog Ingredient catalog list.
 * @param householdAllergens Active household allergen exclusions.
 * @return Immutable map from resolved `ingredientId` to required canonical quantity.
 */
fun resolveEffectiveMealBom(
  recipe: Recipe,
  targetServings: Int = recipe.baseServings,
  activeWorkoutModifier: WorkoutTiming = WorkoutTiming.NONE,
  catalog: List<IngredientCatalogItem>,
  householdAllergens: List<String> = emptyList(),
): Map<String, Double> =
  resolveEffectiveMealBom(recipe, targetServings, activeWorkoutModifier, indexCatalogById(catalog), householdAllergens)

// Resolve substituteGroupId on a workout extra BOM line if its primary item is unavailable.
private fun pantryZoneResolveWorkoutIngredientId(
  recipe: Recipe,
  extraLine: BomLine,
  catalogById: Map<String, IngredientCatalogItem>?,
  householdAllergens: List<String>,
  pantryById: Map<String, PantryLedgerEntry>?,
): String {
  if (catalogById == null || extraLine.substituteGroupId.isNullOrBlank()) return extraLine.ingredientId
  val subEval = evaluateRecipeAvailability(
    recipe.copy(bomLines = listOf(extraLine)), catalogById, householdAllergens, pantryById,
  )
  return subEval.resolvedBomLines.firstOrNull()?.ingredientId ?: extraLine.ingredientId
}

/**
 * Executes a single-pass `O(M)` batch pantry deduction for a cooked meal (`EC-04`–`EC-07`,
 * `EC-10`, `EC-11`), producing an immutable result ready for one `O(1)` storage commit.
 *
 * @param pantry Current household pantry ledger list.
 * @param recipe Recipe that was cooked.
 * @param catalog Household ingredient catalog list.
 * @param payload Validated cook-completion payload.
 * @param nowIso Deterministic ISO-8601 timestamp stamped onto modified Tier-1 entries.
 * @param householdAllergens Optional active allergen exclusions for substitute resolution.
 * @return [CookedDeductionResult] ready to commit in a single `O(1)` storage transaction.
 */
fun applyCookedDeduction(
  pantry: List<PantryLedgerEntry>,
  recipe: Recipe,
  catalog: List<IngredientCatalogItem>,
  payload: CookedDeductionPayload,
  nowIso: String = DEFAULT_TIMESTAMP_ISO,
  householdAllergens: List<String> = emptyList(),
): CookedDeductionResult {
  val catalogById = indexCatalogById(catalog)
  val pantryById = indexPantryById(pantry)

  // Step 1: Resolve scaled baseline BOM + flat 1x Dad workout boost.
  val plannedBom = resolveEffectiveMealBom(
    recipe, payload.targetServings, payload.activeWorkoutModifier, catalogById, householdAllergens, pantryById,
  )

  // Step 2: Merge one-time overrides and ad-hoc extra ingredients (EC-04, EC-05, EC-06).
  // Any entry in actualUsedQuantities overrides planned usage or deducts unplanned extras (+1 tomato).
  val targetDeductions = linkedMapOf<String, Double>().apply {
    putAll(plannedBom)
    payload.actualUsedQuantities?.forEach { (ingId, usedQty) -> put(ingId, max(0.0, usedQty)) }
  }

  // Step 3: Single-pass O(M) reduction across all existing pantry items and touched BOM IDs.
  val allIngredientIds = linkedSetOf<String>().apply {
    addAll(pantryById.keys)
    addAll(targetDeductions.keys)
  }

  val updatedPantry = mutableListOf<PantryLedgerEntry>()
  val updatedPantryById = linkedMapOf<String, PantryLedgerEntry>()
  val deductedQuantities = linkedMapOf<String, Double>()
  val outOfStockIngredientIds = mutableListOf<String>()

  for (ingredientId in allIngredientIds) {
    val existingEntry = pantryById[ingredientId]
    val catalogItem = catalogById[ingredientId]
    val tier = existingEntry?.tier ?: catalogItem?.tier ?: IngredientTier.TIER_1_QUANTITATIVE
    val requestedDeduction = targetDeductions[ingredientId]

    // EC-11: Tier-2 staples never subtract quantitative ml/g when cooking a meal.
    if (tier == IngredientTier.TIER_2_STAPLE) {
      if (requestedDeduction != null) deductedQuantities[ingredientId] = 0.0
      if (existingEntry != null) {
        val isOut = existingEntry.isOutOfStock || existingEntry.stapleStatus == StapleStatus.OUT
        val preserved = existingEntry.copy(isOutOfStock = isOut)
        updatedPantry.add(preserved)
        updatedPantryById[ingredientId] = preserved
        if (isOut) outOfStockIngredientIds.add(ingredientId)
      }
      continue
    }

    // EC-10: Clamp Tier-1 stock at 0.0 (never negative) and flag isOutOfStock when depleted.
    when {
      requestedDeduction != null -> {
        val nextQuantity = max(0.0, (existingEntry?.currentQuantity ?: 0.0) - requestedDeduction)
        val isOutOfStock = nextQuantity == 0.0
        val nextStatus = when {
          isOutOfStock -> StapleStatus.OUT
          existingEntry?.stapleStatus == StapleStatus.OUT -> StapleStatus.PLENTY
          else -> existingEntry?.stapleStatus ?: StapleStatus.PLENTY
        }
        val updatedEntry = existingEntry?.copy(
          tier = IngredientTier.TIER_1_QUANTITATIVE,
          currentQuantity = nextQuantity,
          stapleStatus = nextStatus,
          isOutOfStock = isOutOfStock,
          updatedAtIso = nowIso,
        ) ?: PantryLedgerEntry(
          ingredientId = ingredientId,
          tier = IngredientTier.TIER_1_QUANTITATIVE,
          currentQuantity = nextQuantity,
          stapleStatus = nextStatus,
          isOutOfStock = isOutOfStock,
          updatedAtIso = nowIso,
          storageLocation = catalogItem?.defaultStorageLocation,
          expiresInDays = if (isOutOfStock) null else catalogItem?.defaultShelfLifeDays,
        )
        updatedPantry.add(updatedEntry)
        updatedPantryById[ingredientId] = updatedEntry
        deductedQuantities[ingredientId] = requestedDeduction
        if (isOutOfStock) outOfStockIngredientIds.add(ingredientId)
      }
      existingEntry != null -> {
        updatedPantry.add(existingEntry)
        updatedPantryById[ingredientId] = existingEntry
        if (existingEntry.isOutOfStock || existingEntry.currentQuantity <= 0.0) {
          outOfStockIngredientIds.add(ingredientId)
        }
      }
    }
  }

  // Step 4: Normalize permanent overrides back to baseServings when requested (EC-04 vs EC-05).
  val overridesToSave = payload.actualUsedQuantities
  val updatedRecipe = when {
    payload.saveOverrideAsHouseholdDefault && !overridesToSave.isNullOrEmpty() -> {
      val safeBase = if (recipe.baseServings > 0) recipe.baseServings else 4
      val safeTarget = if (payload.targetServings > 0) payload.targetServings else safeBase
      val normFactor = safeBase.toDouble() / safeTarget.toDouble()
      val mergedOverrides = linkedMapOf<String, Double>().apply {
        putAll(recipe.householdBomOverrides)
        overridesToSave.forEach { (id, qty) -> put(id, max(0.0, qty) * normFactor) }
      }
      recipe.copy(householdBomOverrides = mergedOverrides)
    }
    else -> recipe
  }

  return CookedDeductionResult(
    updatedPantry, updatedPantryById, deductedQuantities, outOfStockIngredientIds, updatedRecipe, MealSlotStatus.COOKED,
  )
}

/**
 * Atomically merges a batch of Smart Grocery [RestockDelta]s into [pantry] in a single `O(M)` pass,
 * honoring per-item [zoneOverrides], computing zone-aware Freezer/Fridge/Pantry shelf life,
 * preserving urgent `<= 2d` FIFO stock against overwrite, and stamping `expiryDateIso` when
 * [todayIso] is supplied.
 *
 * @param pantry Current household pantry ledger list.
 * @param catalog Household ingredient catalog list for tier and zone shelf-life inference.
 * @param deltas Batch of whole-package restock instructions.
 * @param nowIso Deterministic ISO-8601 timestamp stamped onto restocked entries.
 * @param todayIso Optional `"YYYY-MM-DD"` calendar date used to stamp live `expiryDateIso`.
 * @param zoneOverrides Optional destination [StorageLocation] overrides keyed by `ingredientId`.
 * @return Updated immutable pantry ledger list.
 */
fun batchRestockPantry(
  pantry: List<PantryLedgerEntry>,
  catalog: List<IngredientCatalogItem>,
  deltas: List<RestockDelta>,
  nowIso: String = DEFAULT_TIMESTAMP_ISO,
  todayIso: String? = null,
  zoneOverrides: Map<String, StorageLocation> = emptyMap(),
): List<PantryLedgerEntry> {
  val pantryById = indexPantryById(pantry)
  val catalogById = indexCatalogById(catalog)

  // Aggregate duplicate ingredient deltas in one pass before merging into the ledger.
  val aggregatedQuantities = linkedMapOf<String, Double>()
  val aggregatedStatuses = linkedMapOf<String, StapleStatus>()
  for (delta in deltas) {
    aggregatedQuantities[delta.ingredientId] =
      (aggregatedQuantities[delta.ingredientId] ?: 0.0) + max(0.0, delta.addedBaseQuantity)
    if (delta.newStapleStatus != null) aggregatedStatuses[delta.ingredientId] = delta.newStapleStatus
  }

  val allIngredientIds = linkedSetOf<String>().apply {
    addAll(pantryById.keys)
    addAll(aggregatedQuantities.keys)
  }

  // Restore Tier-2 staples to PLENTY (EC-11), increment Tier-1 balances, and guard urgent <= 2d FIFO stock.
  return allIngredientIds.mapNotNull { ingredientId ->
    val existing = pantryById[ingredientId]
    if (ingredientId !in aggregatedQuantities) return@mapNotNull existing

    val catalogItem = catalogById[ingredientId]
    val addedQty = aggregatedQuantities[ingredientId] ?: 0.0
    val statusOverride = aggregatedStatuses[ingredientId]
    val tier = existing?.tier ?: catalogItem?.tier ?: when {
      statusOverride != null -> IngredientTier.TIER_2_STAPLE
      else -> IngredientTier.TIER_1_QUANTITATIVE
    }
    // Respect unpack zoneOverrides first, then persisted custom zone, then catalog default zone.
    val targetZone =
      zoneOverrides[ingredientId] ?: existing?.storageLocation ?: catalogItem?.defaultStorageLocation
    val freshDays = when {
      catalogItem != null && targetZone != null -> defaultShelfLifeDaysForZone(catalogItem, targetZone)
      catalogItem != null -> catalogItem.defaultShelfLifeDays
      else -> existing?.expiresInDays
    }
    val refreshedPurchasedDaysAgo = if (catalogItem != null) 0 else existing?.purchasedDaysAgo

    when (tier) {
      IngredientTier.TIER_2_STAPLE -> {
        val nextStatus = statusOverride ?: StapleStatus.PLENTY
        val isOut = nextStatus == StapleStatus.OUT
        existing?.copy(
          tier = IngredientTier.TIER_2_STAPLE,
          currentQuantity = existing.currentQuantity + addedQty,
          stapleStatus = nextStatus,
          isOutOfStock = isOut,
          updatedAtIso = nowIso,
          storageLocation = targetZone,
          expiresInDays = existing.expiresInDays,
          purchasedDaysAgo = refreshedPurchasedDaysAgo,
        ) ?: PantryLedgerEntry(
          ingredientId = ingredientId,
          tier = IngredientTier.TIER_2_STAPLE,
          currentQuantity = addedQty,
          stapleStatus = nextStatus,
          isOutOfStock = isOut,
          updatedAtIso = nowIso,
          storageLocation = targetZone,
          purchasedDaysAgo = refreshedPurchasedDaysAgo,
        )
      }

      IngredientTier.TIER_1_QUANTITATIVE -> {
        val nextQuantity = max(0.0, (existing?.currentQuantity ?: 0.0) + addedQty)
        val isOut = nextQuantity == 0.0
        val nextStatus = statusOverride ?: if (isOut) StapleStatus.OUT else StapleStatus.PLENTY
        // FIFO Urgent Guard: never overwrite urgent <= 2d existing stock with a longer fresh window.
        val effectiveDays = pantryZoneComputeRestockEffectiveDays(existing, isOut, addedQty, freshDays, todayIso)
        // Stamp calendar expiryDateIso whenever todayIso is supplied so stock ages across days.
        val effectiveExpiryDateIso = when {
          isOut -> null
          todayIso != null && effectiveDays != null -> addDaysIso(todayIso, effectiveDays)
          else -> existing?.expiryDateIso
        }

        existing?.copy(
          tier = IngredientTier.TIER_1_QUANTITATIVE,
          currentQuantity = nextQuantity,
          stapleStatus = nextStatus,
          isOutOfStock = isOut,
          updatedAtIso = nowIso,
          storageLocation = targetZone,
          expiresInDays = effectiveDays,
          expiryDateIso = effectiveExpiryDateIso,
          purchasedDaysAgo = if (addedQty > 0.0) refreshedPurchasedDaysAgo else existing.purchasedDaysAgo,
        ) ?: PantryLedgerEntry(
          ingredientId = ingredientId,
          tier = IngredientTier.TIER_1_QUANTITATIVE,
          currentQuantity = nextQuantity,
          stapleStatus = nextStatus,
          isOutOfStock = isOut,
          updatedAtIso = nowIso,
          storageLocation = targetZone,
          expiresInDays = effectiveDays,
          expiryDateIso = effectiveExpiryDateIso,
          purchasedDaysAgo = if (!isOut) refreshedPurchasedDaysAgo else null,
        )
      }
    }
  }
}

/**
 * Convenience overload of [batchRestockPantry] when catalog metadata is not required.
 *
 * @param pantry Current household pantry ledger list.
 * @param deltas Batch of whole-package restock instructions.
 * @param nowIso Deterministic ISO-8601 timestamp stamped onto restocked entries.
 * @param todayIso Optional `"YYYY-MM-DD"` calendar date used to stamp `expiryDateIso`.
 * @param zoneOverrides Optional destination [StorageLocation] overrides keyed by `ingredientId`.
 * @return Updated immutable pantry ledger list.
 */
fun batchRestockPantry(
  pantry: List<PantryLedgerEntry>,
  deltas: List<RestockDelta>,
  nowIso: String = DEFAULT_TIMESTAMP_ISO,
  todayIso: String? = null,
  zoneOverrides: Map<String, StorageLocation> = emptyMap(),
): List<PantryLedgerEntry> =
  batchRestockPantry(pantry, emptyList(), deltas, nowIso, todayIso, zoneOverrides)

/**
 * Applies a Fridge Pulse stepper delta (`±1 count` or `±100 g/ml`) to a single Tier-1 ingredient,
 * clamping at `0.0` (`EC-10`), clearing expiry metadata on depletion, and seeding zone-aware
 * shelf life (`expiresInDays` + `expiryDateIso`) when stepping a depleted item above `0.0`.
 *
 * @param pantry Current household pantry ledger list.
 * @param catalog Household ingredient catalog list.
 * @param ingredientId Target ingredient ID to adjust.
 * @param deltaQuantity Signed quantity delta to apply.
 * @param nowIso Deterministic ISO-8601 timestamp.
 * @param todayIso Optional `"YYYY-MM-DD"` calendar date used to stamp `expiryDateIso`.
 * @return Updated immutable pantry ledger list.
 */
fun adjustPantryQuantity(
  pantry: List<PantryLedgerEntry>,
  catalog: List<IngredientCatalogItem>,
  ingredientId: String,
  deltaQuantity: Double,
  nowIso: String = DEFAULT_TIMESTAMP_ISO,
  todayIso: String? = null,
): List<PantryLedgerEntry> {
  val catalogItem = indexCatalogById(catalog)[ingredientId]
  var matched = false

  // Update in-place immutably while clamping negative steps at 0.0 and managing shelf-life transitions.
  val updated = pantry.map { entry ->
    when {
      entry.ingredientId != ingredientId -> entry
      else -> {
        matched = true
        val wasDepleted = entry.isOutOfStock || entry.currentQuantity <= 0.0
        val nextQty = max(0.0, entry.currentQuantity + deltaQuantity)
        val isOut = entry.tier == IngredientTier.TIER_1_QUANTITATIVE && nextQty == 0.0
        val nextStatus = when {
          entry.tier == IngredientTier.TIER_2_STAPLE -> entry.stapleStatus
          isOut -> StapleStatus.OUT
          else -> StapleStatus.PLENTY
        }
        val location = entry.storageLocation ?: catalogItem?.defaultStorageLocation ?: StorageLocation.FRIDGE
        // Seed fresh zone shelf-life when stepping 0g -> +qty; clear expiry completely when hitting 0g.
        val (nextDays, nextExpiryIso, nextPurchasedAgo) = when {
          entry.tier == IngredientTier.TIER_2_STAPLE ->
            Triple(entry.expiresInDays, entry.expiryDateIso, entry.purchasedDaysAgo)
          isOut -> Triple(null, null, null)
          wasDepleted || entry.expiresInDays == null -> {
            val seededDays = catalogItem?.let { defaultShelfLifeDaysForZone(it, location) } ?: 7
            Triple(seededDays, todayIso?.let { addDaysIso(it, seededDays) }, 0)
          }
          else -> Triple(entry.expiresInDays, entry.expiryDateIso, entry.purchasedDaysAgo)
        }
        entry.copy(
          currentQuantity = nextQty,
          stapleStatus = nextStatus,
          isOutOfStock = isOut || (entry.tier == IngredientTier.TIER_2_STAPLE && nextStatus == StapleStatus.OUT),
          updatedAtIso = nowIso,
          expiresInDays = nextDays,
          expiryDateIso = nextExpiryIso,
          purchasedDaysAgo = nextPurchasedAgo,
        )
      }
    }
  }

  if (matched) return updated

  // Seed a fresh ledger record when stepping an ingredient newly added to the household catalog.
  val tier = catalogItem?.tier ?: IngredientTier.TIER_1_QUANTITATIVE
  val initialQty = max(0.0, deltaQuantity)
  val isOut = tier == IngredientTier.TIER_1_QUANTITATIVE && initialQty == 0.0
  val location = catalogItem?.defaultStorageLocation ?: StorageLocation.FRIDGE
  val initialDays = if (tier == IngredientTier.TIER_1_QUANTITATIVE && !isOut) {
    catalogItem?.let { defaultShelfLifeDaysForZone(it, location) } ?: 7
  } else {
    null
  }
  return updated + PantryLedgerEntry(
    ingredientId = ingredientId,
    tier = tier,
    currentQuantity = initialQty,
    stapleStatus = if (isOut) StapleStatus.OUT else StapleStatus.PLENTY,
    isOutOfStock = isOut,
    updatedAtIso = nowIso,
    storageLocation = catalogItem?.defaultStorageLocation,
    expiresInDays = initialDays,
    expiryDateIso = if (initialDays != null && todayIso != null) addDaysIso(todayIso, initialDays) else null,
    purchasedDaysAgo = if (!isOut) 0 else null,
  )
}

/**
 * Relocates [ingredientId] into [targetLocation] (`FRIDGE`, `PANTRY`, or `FREEZER`), recalculating
 * its zone-appropriate shelf life (`+90d`/`+180d` on `Freeze to Rescue`) and `expiryDateIso`.
 *
 * @param pantry Current household pantry ledger list.
 * @param ingredientId Target catalog `ingredientId` to move.
 * @param targetLocation Destination kitchen storage zone.
 * @param catalog Household ingredient catalog list.
 * @param todayIso Optional `"YYYY-MM-DD"` calendar date used to stamp `expiryDateIso`.
 * @param nowIso Deterministic ISO-8601 timestamp.
 * @return Updated immutable pantry ledger list.
 */
fun movePantryItemToZone(
  pantry: List<PantryLedgerEntry>,
  ingredientId: String,
  targetLocation: StorageLocation,
  catalog: List<IngredientCatalogItem>,
  todayIso: String? = null,
  nowIso: String = DEFAULT_TIMESTAMP_ISO,
): List<PantryLedgerEntry> {
  val catalogItem = indexCatalogById(catalog)[ingredientId]
  var matched = false

  // Recompute zone shelf-life on active Tier-1 stock so "Freeze to Rescue" immediately lifts urgency.
  val updated = pantry.map { entry ->
    when {
      entry.ingredientId != ingredientId -> entry
      else -> {
        matched = true
        val isDepleted = entry.isOutOfStock ||
          (entry.tier == IngredientTier.TIER_1_QUANTITATIVE && entry.currentQuantity <= 0.0) ||
          (entry.tier == IngredientTier.TIER_2_STAPLE && entry.stapleStatus == StapleStatus.OUT)
        when {
          isDepleted -> entry.copy(
            storageLocation = targetLocation, expiresInDays = null, expiryDateIso = null, updatedAtIso = nowIso,
          )
          entry.tier == IngredientTier.TIER_2_STAPLE -> entry.copy(
            storageLocation = targetLocation, updatedAtIso = nowIso,
          )
          else -> {
            val zoneDays = when {
              catalogItem != null -> defaultShelfLifeDaysForZone(catalogItem, targetLocation)
              targetLocation == StorageLocation.FREEZER -> 90
              else -> entry.expiresInDays ?: 7
            }
            entry.copy(
              storageLocation = targetLocation,
              expiresInDays = zoneDays,
              expiryDateIso = todayIso?.let { addDaysIso(it, zoneDays) },
              updatedAtIso = nowIso,
            )
          }
        }
      }
    }
  }

  if (matched || catalogItem == null) return updated
  return updated + PantryLedgerEntry(
    ingredientId = ingredientId,
    tier = catalogItem.tier,
    currentQuantity = 0.0,
    stapleStatus = StapleStatus.OUT,
    isOutOfStock = true,
    updatedAtIso = nowIso,
    storageLocation = targetLocation,
  )
}

/**
 * Sets [ingredientId] directly to a [ProportionalStockLevel] (`EMPTY`, `LOW`, `HALF`, or `FULL`)
 * relative to `defaultPackageQuantity`, enabling 1-tap Shelf Audit drift recovery without 15+
 * manual stepper taps.
 *
 * @param pantry Current household pantry ledger list.
 * @param ingredientId Target catalog `ingredientId` to audit.
 * @param level Desired [ProportionalStockLevel] fraction.
 * @param catalog Household ingredient catalog list.
 * @param todayIso Optional `"YYYY-MM-DD"` calendar date used to stamp `expiryDateIso`.
 * @param nowIso Deterministic ISO-8601 timestamp.
 * @return Updated immutable pantry ledger list.
 */
fun setProportionalPantryLevel(
  pantry: List<PantryLedgerEntry>,
  ingredientId: String,
  level: ProportionalStockLevel,
  catalog: List<IngredientCatalogItem>,
  todayIso: String? = null,
  nowIso: String = DEFAULT_TIMESTAMP_ISO,
): List<PantryLedgerEntry> {
  val catalogItem = indexCatalogById(catalog)[ingredientId]
  val pkgQty = catalogItem?.defaultPackageQuantity?.takeIf { it > 0.0 && it.isFinite() } ?: 1.0
  // Multiply package size by 0.0 / 0.25 / 0.5 / 1.0 so e.g. 1000ml milk snaps to 0 / 250 / 500 / 1000ml.
  val targetQty = max(0.0, ((pkgQty * level.fractionOfPackage) * 100.0).roundToInt() / 100.0)
  var matched = false

  val updated = pantry.map { entry ->
    when {
      entry.ingredientId != ingredientId -> entry
      entry.tier == IngredientTier.TIER_2_STAPLE -> {
        matched = true
        val tier2Status = pantryZoneStapleStatusForLevel(level)
        entry.copy(
          currentQuantity = targetQty,
          stapleStatus = tier2Status,
          isOutOfStock = tier2Status == StapleStatus.OUT,
          updatedAtIso = nowIso,
        )
      }
      else -> {
        matched = true
        val isOut = level == ProportionalStockLevel.EMPTY || targetQty <= 0.0
        val wasDepleted = entry.isOutOfStock || entry.currentQuantity <= 0.0 || entry.expiresInDays == null
        val location = entry.storageLocation ?: catalogItem?.defaultStorageLocation ?: StorageLocation.FRIDGE
        val (nextDays, nextIso) = when {
          isOut -> null to null
          wasDepleted -> {
            val freshDays = catalogItem?.let { defaultShelfLifeDaysForZone(it, location) } ?: 7
            freshDays to todayIso?.let { addDaysIso(it, freshDays) }
          }
          else -> entry.expiresInDays to entry.expiryDateIso
        }
        entry.copy(
          currentQuantity = if (isOut) 0.0 else targetQty,
          stapleStatus = if (isOut) StapleStatus.OUT else StapleStatus.PLENTY,
          isOutOfStock = isOut,
          expiresInDays = nextDays,
          expiryDateIso = nextIso,
          purchasedDaysAgo = if (isOut) null else (entry.purchasedDaysAgo ?: 0),
          updatedAtIso = nowIso,
        )
      }
    }
  }

  if (matched) return updated
  // Append a new ledger entry when auditing a catalog item not yet materialized in pantryEntries.
  val tier = catalogItem?.tier ?: IngredientTier.TIER_1_QUANTITATIVE
  val location = catalogItem?.defaultStorageLocation ?: StorageLocation.FRIDGE
  return when (tier) {
    IngredientTier.TIER_2_STAPLE -> {
      val status = pantryZoneStapleStatusForLevel(level)
      updated + PantryLedgerEntry(
        ingredientId = ingredientId,
        tier = IngredientTier.TIER_2_STAPLE,
        currentQuantity = targetQty,
        stapleStatus = status,
        isOutOfStock = status == StapleStatus.OUT,
        updatedAtIso = nowIso,
        storageLocation = location,
      )
    }
    IngredientTier.TIER_1_QUANTITATIVE -> {
      val isOut = level == ProportionalStockLevel.EMPTY || targetQty <= 0.0
      val days = if (isOut) null else (catalogItem?.let { defaultShelfLifeDaysForZone(it, location) } ?: 7)
      updated + PantryLedgerEntry(
        ingredientId = ingredientId,
        tier = IngredientTier.TIER_1_QUANTITATIVE,
        currentQuantity = if (isOut) 0.0 else targetQty,
        stapleStatus = if (isOut) StapleStatus.OUT else StapleStatus.PLENTY,
        isOutOfStock = isOut,
        updatedAtIso = nowIso,
        storageLocation = location,
        expiresInDays = days,
        expiryDateIso = if (days != null && todayIso != null) addDaysIso(todayIso, days) else null,
        purchasedDaysAgo = if (isOut) null else 0,
      )
    }
  }
}

/**
 * Updates the tri-state [StapleStatus] (`PLENTY | LOW | OUT`) on a Tier-2 pantry staple (`EC-11`).
 *
 * @param pantry Current household pantry ledger list.
 * @param catalog Household ingredient catalog list.
 * @param ingredientId Target Tier-2 staple ingredient ID.
 * @param newStatus Desired tri-state status (`PLENTY`, `LOW`, or `OUT`).
 * @param nowIso Deterministic ISO-8601 timestamp.
 * @return Updated immutable pantry ledger list.
 */
fun setStapleStatus(
  pantry: List<PantryLedgerEntry>,
  catalog: List<IngredientCatalogItem>,
  ingredientId: String,
  newStatus: StapleStatus,
  nowIso: String = DEFAULT_TIMESTAMP_ISO,
): List<PantryLedgerEntry> {
  val catalogItem = indexCatalogById(catalog)[ingredientId]
  var matched = false

  // Synchronize isOutOfStock with StapleStatus.OUT so instant swap filters stay consistent.
  val updated = pantry.map { entry ->
    when {
      entry.ingredientId != ingredientId -> entry
      else -> {
        matched = true
        entry.copy(stapleStatus = newStatus, isOutOfStock = newStatus == StapleStatus.OUT, updatedAtIso = nowIso)
      }
    }
  }

  if (matched) return updated
  val tier = catalogItem?.tier ?: IngredientTier.TIER_2_STAPLE
  return updated + PantryLedgerEntry(
    ingredientId = ingredientId,
    tier = tier,
    currentQuantity = 0.0,
    stapleStatus = newStatus,
    isOutOfStock = newStatus == StapleStatus.OUT,
    updatedAtIso = nowIso,
    storageLocation = catalogItem?.defaultStorageLocation ?: StorageLocation.PANTRY,
  )
}

/**
 * Toggles (or sets an explicit value for) [IngredientCatalogItem.isLocallyAvailable] (`EC-02`/`EC-03`).
 *
 * @param catalog Current household ingredient catalog list.
 * @param ingredientId Target catalog ingredient ID.
 * @param isLocallyAvailable Optional explicit target flag; inverts current value when `null`.
 * @return Updated immutable catalog list.
 */
fun toggleLocalAvailability(
  catalog: List<IngredientCatalogItem>,
  ingredientId: String,
  isLocallyAvailable: Boolean? = null,
): List<IngredientCatalogItem> =
  catalog.map { item ->
    when {
      item.ingredientId != ingredientId -> item
      else -> item.copy(isLocallyAvailable = isLocallyAvailable ?: !item.isLocallyAvailable)
    }
  }

// Preserve urgent 1d/2d FIFO expiry when restocking onto non-empty perishable stock.
private fun pantryZoneComputeRestockEffectiveDays(
  existing: PantryLedgerEntry?,
  isOut: Boolean,
  addedQty: Double,
  freshDays: Int?,
  todayIso: String?,
): Int? {
  if (isOut) return null
  if (addedQty <= 0.0) return existing?.expiresInDays
  if (existing != null && !existing.isOutOfStock && existing.currentQuantity > 0.0) {
    val existingRemaining = computeRemainingShelfLifeDays(existing, todayIso)
    if (existingRemaining != null && existingRemaining <= 2) {
      return if (freshDays != null) minOf(existingRemaining, freshDays) else existingRemaining
    }
  }
  return freshDays
}

// Map proportional Shelf Audit pills onto Tier-2 PLENTY/LOW/OUT staple status values.
private fun pantryZoneStapleStatusForLevel(level: ProportionalStockLevel): StapleStatus =
  when (level) {
    ProportionalStockLevel.EMPTY -> StapleStatus.OUT
    ProportionalStockLevel.LOW -> StapleStatus.LOW
    ProportionalStockLevel.HALF, ProportionalStockLevel.FULL -> StapleStatus.PLENTY
  }

/** Object namespace exposing pantry ledger reduction helpers for qualified `PantryLedgerEngine.*` calls. */
object PantryLedgerEngine {
  // Zone-aware shelf-life calculator applying 90d/180d Freezer extensions.
  /** Delegates to [com.familykitchen.board.domain.defaultShelfLifeDaysForZone]. */
  fun defaultShelfLifeDaysForZone(item: IngredientCatalogItem, location: StorageLocation): Int =
    com.familykitchen.board.domain.defaultShelfLifeDaysForZone(item, location)

  // Dispatches to null-catalog fast path when callers omit catalog substitution context.
  /** Delegates to [com.familykitchen.board.domain.resolveEffectiveMealBom]. */
  fun resolveEffectiveMealBom(
    recipe: Recipe,
    targetServings: Int = recipe.baseServings,
    activeWorkoutModifier: WorkoutTiming = WorkoutTiming.NONE,
    catalog: List<IngredientCatalogItem> = emptyList(),
    householdAllergens: List<String> = emptyList(),
  ): Map<String, Double> = when {
    catalog.isEmpty() ->
      com.familykitchen.board.domain.resolveEffectiveMealBom(recipe, targetServings, activeWorkoutModifier, null, householdAllergens)
    else ->
      com.familykitchen.board.domain.resolveEffectiveMealBom(recipe, targetServings, activeWorkoutModifier, catalog, householdAllergens)
  }

  // Single-pass O(M) cooked meal deduction entrypoint for ViewModel and JUnit callers.
  /** Delegates to [com.familykitchen.board.domain.applyCookedDeduction]. */
  fun applyCookedDeduction(
    pantry: List<PantryLedgerEntry>,
    recipe: Recipe,
    catalog: List<IngredientCatalogItem>,
    payload: CookedDeductionPayload,
    nowIso: String = DEFAULT_TIMESTAMP_ISO,
    householdAllergens: List<String> = emptyList(),
  ): CookedDeductionResult =
    com.familykitchen.board.domain.applyCookedDeduction(pantry, recipe, catalog, payload, nowIso, householdAllergens)

  // Atomic Smart Grocery List restock entrypoint honoring FIFO urgent guards and zoneOverrides.
  /** Delegates to [com.familykitchen.board.domain.batchRestockPantry]. */
  fun batchRestockPantry(
    pantry: List<PantryLedgerEntry>,
    catalog: List<IngredientCatalogItem>,
    deltas: List<RestockDelta>,
    nowIso: String = DEFAULT_TIMESTAMP_ISO,
    todayIso: String? = null,
    zoneOverrides: Map<String, StorageLocation> = emptyMap(),
  ): List<PantryLedgerEntry> =
    com.familykitchen.board.domain.batchRestockPantry(pantry, catalog, deltas, nowIso, todayIso, zoneOverrides)

  // Catalog-free restock overload when tier metadata already lives on existing pantry entries.
  /** Convenience overload delegating to [com.familykitchen.board.domain.batchRestockPantry]. */
  fun batchRestockPantry(
    pantry: List<PantryLedgerEntry>,
    deltas: List<RestockDelta>,
    nowIso: String = DEFAULT_TIMESTAMP_ISO,
    todayIso: String? = null,
    zoneOverrides: Map<String, StorageLocation> = emptyMap(),
  ): List<PantryLedgerEntry> =
    com.familykitchen.board.domain.batchRestockPantry(pantry, deltas, nowIso, todayIso, zoneOverrides)

  // Fridge Pulse +/- stepper entrypoint enforcing EC-10 clamping and 0->+qty expiry seeding.
  /** Delegates to [com.familykitchen.board.domain.adjustPantryQuantity]. */
  fun adjustPantryQuantity(
    pantry: List<PantryLedgerEntry>,
    catalog: List<IngredientCatalogItem>,
    ingredientId: String,
    deltaQuantity: Double,
    nowIso: String = DEFAULT_TIMESTAMP_ISO,
    todayIso: String? = null,
  ): List<PantryLedgerEntry> =
    com.familykitchen.board.domain.adjustPantryQuantity(pantry, catalog, ingredientId, deltaQuantity, nowIso, todayIso)

  // 1-tap Freeze-to-Rescue and zone relocation entrypoint.
  /** Delegates to [com.familykitchen.board.domain.movePantryItemToZone]. */
  fun movePantryItemToZone(
    pantry: List<PantryLedgerEntry>,
    ingredientId: String,
    targetLocation: StorageLocation,
    catalog: List<IngredientCatalogItem>,
    todayIso: String? = null,
    nowIso: String = DEFAULT_TIMESTAMP_ISO,
  ): List<PantryLedgerEntry> =
    com.familykitchen.board.domain.movePantryItemToZone(pantry, ingredientId, targetLocation, catalog, todayIso, nowIso)

  // Proportional Shelf Audit quick-set (Empty | Low | Half | Full) entrypoint.
  /** Delegates to [com.familykitchen.board.domain.setProportionalPantryLevel]. */
  fun setProportionalPantryLevel(
    pantry: List<PantryLedgerEntry>,
    ingredientId: String,
    level: ProportionalStockLevel,
    catalog: List<IngredientCatalogItem>,
    todayIso: String? = null,
    nowIso: String = DEFAULT_TIMESTAMP_ISO,
  ): List<PantryLedgerEntry> =
    com.familykitchen.board.domain.setProportionalPantryLevel(pantry, ingredientId, level, catalog, todayIso, nowIso)

  // Tier-2 staple tri-state toggle (PLENTY | LOW | OUT) entrypoint.
  /** Delegates to [com.familykitchen.board.domain.setStapleStatus]. */
  fun setStapleStatus(
    pantry: List<PantryLedgerEntry>,
    catalog: List<IngredientCatalogItem>,
    ingredientId: String,
    newStatus: StapleStatus,
    nowIso: String = DEFAULT_TIMESTAMP_ISO,
  ): List<PantryLedgerEntry> =
    com.familykitchen.board.domain.setStapleStatus(pantry, catalog, ingredientId, newStatus, nowIso)

  // Local market availability switch triggering EC-02 hard-hide or EC-03 auto-substitution.
  /** Delegates to [com.familykitchen.board.domain.toggleLocalAvailability]. */
  fun toggleLocalAvailability(
    catalog: List<IngredientCatalogItem>,
    ingredientId: String,
    isLocallyAvailable: Boolean? = null,
  ): List<IngredientCatalogItem> =
    com.familykitchen.board.domain.toggleLocalAvailability(catalog, ingredientId, isLocallyAvailable)
}
