package com.familykitchen.board.domain

import kotlin.math.max

// Pure O(M) single-pass pantry ledger reduction engine.
// Enforces EC-04 (one-time override), EC-05 (normalized household defaults), EC-06 (ad-hoc extras),
// EC-07 (flat 1x Dad workout boost), EC-10 (floor-at-zero clamping), and EC-11 (Tier-2 tri-state).

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
    val baseQty = overrideBaseQty ?: resolvedLine.baseQuantity
    val scaledQuantity = max(0.0, baseQty * servingScale)
    effectiveQuantities[resolvedLine.ingredientId] =
      (effectiveQuantities[resolvedLine.ingredientId] ?: 0.0) + scaledQuantity
  }

  // EC-07: Apply Dad's workout boost flat (1x), never multiplied by targetServings / baseServings.
  // Even if guests scale a 4-person meal to 8 servings, Dad's personal boost adds +2 eggs flat.
  if (activeWorkoutModifier != WorkoutTiming.NONE) {
    for (modifier in recipe.workoutModifiers) {
      if (modifier.profile != activeWorkoutModifier) continue
      for (extraLine in modifier.extraBomLines) {
        val targetId = resolveWorkoutLineIngredientId(recipe, extraLine, catalogById, householdAllergens, pantryById)
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
private fun resolveWorkoutLineIngredientId(
  recipe: Recipe,
  extraLine: BomLine,
  catalogById: Map<String, IngredientCatalogItem>?,
  householdAllergens: List<String>,
  pantryById: Map<String, PantryLedgerEntry>?,
): String {
  if (catalogById == null || extraLine.substituteGroupId.isNullOrBlank()) return extraLine.ingredientId
  val subEval = evaluateRecipeAvailability(recipe.copy(bomLines = listOf(extraLine)), catalogById, householdAllergens, pantryById)
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
    recipe = recipe,
    targetServings = payload.targetServings,
    activeWorkoutModifier = payload.activeWorkoutModifier,
    catalogById = catalogById,
    householdAllergens = householdAllergens,
    pantryById = pantryById,
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
        val currentQty = existingEntry?.currentQuantity ?: 0.0
        val nextQuantity = max(0.0, currentQty - requestedDeduction)
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
    updatedPantry = updatedPantry,
    updatedPantryById = updatedPantryById,
    deductedQuantities = deductedQuantities,
    outOfStockIngredientIds = outOfStockIngredientIds,
    updatedRecipe = updatedRecipe,
    updatedSlotStatus = MealSlotStatus.COOKED,
  )
}

/**
 * Atomically merges a batch of Smart Grocery [RestockDelta]s into [pantry] in a single `O(M)` pass.
 *
 * @param pantry Current household pantry ledger list.
 * @param catalog Household ingredient catalog list for tier inference on newly stocked items.
 * @param deltas Batch of whole-package restock instructions.
 * @param nowIso Deterministic ISO-8601 timestamp stamped onto restocked entries.
 * @return Updated immutable pantry ledger list.
 */
fun batchRestockPantry(
  pantry: List<PantryLedgerEntry>,
  catalog: List<IngredientCatalogItem>,
  deltas: List<RestockDelta>,
  nowIso: String = DEFAULT_TIMESTAMP_ISO,
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

  // Restore Tier-2 staples to PLENTY (EC-11), increment Tier-1 balances, and refresh shelf life.
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
    // Preserve custom storage zone/sub-location while refreshing shelf life on restock.
    val resolvedLocation = existing?.storageLocation ?: catalogItem?.defaultStorageLocation
    val refreshedExpiresInDays = when {
      catalogItem != null -> catalogItem.defaultShelfLifeDays
      else -> existing?.expiresInDays
    }
    val refreshedPurchasedDaysAgo = when {
      catalogItem != null -> 0
      else -> existing?.purchasedDaysAgo
    }

    when (tier) {
      IngredientTier.TIER_2_STAPLE -> {
        val nextStatus = statusOverride ?: StapleStatus.PLENTY
        existing?.copy(
          tier = IngredientTier.TIER_2_STAPLE,
          currentQuantity = existing.currentQuantity + addedQty,
          stapleStatus = nextStatus,
          isOutOfStock = nextStatus == StapleStatus.OUT,
          updatedAtIso = nowIso,
          storageLocation = resolvedLocation,
          expiresInDays = existing.expiresInDays,
          purchasedDaysAgo = refreshedPurchasedDaysAgo,
        ) ?: PantryLedgerEntry(
          ingredientId = ingredientId,
          tier = IngredientTier.TIER_2_STAPLE,
          currentQuantity = addedQty,
          stapleStatus = nextStatus,
          isOutOfStock = nextStatus == StapleStatus.OUT,
          updatedAtIso = nowIso,
          storageLocation = resolvedLocation,
          purchasedDaysAgo = refreshedPurchasedDaysAgo,
        )
      }
      IngredientTier.TIER_1_QUANTITATIVE -> {
        val nextQuantity = max(0.0, (existing?.currentQuantity ?: 0.0) + addedQty)
        val isOut = nextQuantity == 0.0
        val nextStatus = statusOverride ?: if (isOut) StapleStatus.OUT else StapleStatus.PLENTY
        val effectiveExpires = when {
          isOut -> existing?.expiresInDays
          addedQty > 0.0 -> refreshedExpiresInDays
          else -> existing?.expiresInDays
        }
        existing?.copy(
          tier = IngredientTier.TIER_1_QUANTITATIVE,
          currentQuantity = nextQuantity,
          stapleStatus = nextStatus,
          isOutOfStock = isOut,
          updatedAtIso = nowIso,
          storageLocation = resolvedLocation,
          expiresInDays = effectiveExpires,
          purchasedDaysAgo = if (addedQty > 0.0) refreshedPurchasedDaysAgo else existing.purchasedDaysAgo,
        ) ?: PantryLedgerEntry(
          ingredientId = ingredientId,
          tier = IngredientTier.TIER_1_QUANTITATIVE,
          currentQuantity = nextQuantity,
          stapleStatus = nextStatus,
          isOutOfStock = isOut,
          updatedAtIso = nowIso,
          storageLocation = resolvedLocation,
          expiresInDays = effectiveExpires,
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
 * @return Updated immutable pantry ledger list.
 */
fun batchRestockPantry(
  pantry: List<PantryLedgerEntry>,
  deltas: List<RestockDelta>,
  nowIso: String = DEFAULT_TIMESTAMP_ISO,
): List<PantryLedgerEntry> = batchRestockPantry(pantry, emptyList(), deltas, nowIso)

/**
 * Applies a Fridge Pulse stepper delta (`±1 count` or `±100 g/ml`) to a single Tier-1 ingredient,
 * clamping at `0.0` (`EC-10`) and inserting a new ledger entry if not yet present.
 *
 * @param pantry Current household pantry ledger list.
 * @param catalog Household ingredient catalog list.
 * @param ingredientId Target ingredient ID to adjust.
 * @param deltaQuantity Signed quantity delta to apply.
 * @param nowIso Deterministic ISO-8601 timestamp.
 * @return Updated immutable pantry ledger list.
 */
fun adjustPantryQuantity(
  pantry: List<PantryLedgerEntry>,
  catalog: List<IngredientCatalogItem>,
  ingredientId: String,
  deltaQuantity: Double,
  nowIso: String = DEFAULT_TIMESTAMP_ISO,
): List<PantryLedgerEntry> {
  val catalogById = indexCatalogById(catalog)
  var matched = false

  // Update in-place immutably while clamping negative Fridge Pulse steps at 0.0.
  val updated = pantry.map { entry ->
    when {
      entry.ingredientId != ingredientId -> entry
      else -> {
        matched = true
        val nextQty = max(0.0, entry.currentQuantity + deltaQuantity)
        val isOut = entry.tier == IngredientTier.TIER_1_QUANTITATIVE && nextQty == 0.0
        val nextStatus = when {
          entry.tier == IngredientTier.TIER_2_STAPLE -> entry.stapleStatus
          isOut -> StapleStatus.OUT
          else -> StapleStatus.PLENTY
        }
        entry.copy(
          currentQuantity = nextQty,
          stapleStatus = nextStatus,
          isOutOfStock = isOut || (entry.tier == IngredientTier.TIER_2_STAPLE && nextStatus == StapleStatus.OUT),
          updatedAtIso = nowIso,
        )
      }
    }
  }

  if (matched) return updated

  // Seed a fresh ledger record when stepping an ingredient newly added to the household catalog.
  val catalogItem = catalogById[ingredientId]
  val tier = catalogItem?.tier ?: IngredientTier.TIER_1_QUANTITATIVE
  val initialQty = max(0.0, deltaQuantity)
  val isOut = tier == IngredientTier.TIER_1_QUANTITATIVE && initialQty == 0.0
  return updated + PantryLedgerEntry(
    ingredientId = ingredientId,
    tier = tier,
    currentQuantity = initialQty,
    stapleStatus = if (isOut) StapleStatus.OUT else StapleStatus.PLENTY,
    isOutOfStock = isOut,
    updatedAtIso = nowIso,
    storageLocation = catalogItem?.defaultStorageLocation,
    expiresInDays = if (tier == IngredientTier.TIER_1_QUANTITATIVE && !isOut) catalogItem?.defaultShelfLifeDays else null,
    purchasedDaysAgo = if (!isOut) 0 else null,
  )
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
  val catalogById = indexCatalogById(catalog)
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
  val catalogItem = catalogById[ingredientId]
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

/** Object namespace exposing pantry ledger reduction helpers for qualified `PantryLedgerEngine.*` calls. */
object PantryLedgerEngine {
  // Dispatches to null-catalog fast path when callers omit catalog substitution context.
  /** Delegates to [com.familykitchen.board.domain.resolveEffectiveMealBom]. */
  fun resolveEffectiveMealBom(
    recipe: Recipe,
    targetServings: Int = recipe.baseServings,
    activeWorkoutModifier: WorkoutTiming = WorkoutTiming.NONE,
    catalog: List<IngredientCatalogItem> = emptyList(),
    householdAllergens: List<String> = emptyList(),
  ): Map<String, Double> = when {
    catalog.isEmpty() -> com.familykitchen.board.domain.resolveEffectiveMealBom(
      recipe, targetServings, activeWorkoutModifier, null, householdAllergens,
    )
    else -> com.familykitchen.board.domain.resolveEffectiveMealBom(
      recipe, targetServings, activeWorkoutModifier, catalog, householdAllergens,
    )
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

  // Atomic Smart Grocery List restock entrypoint resetting Tier-2 staples to PLENTY.
  /** Delegates to [com.familykitchen.board.domain.batchRestockPantry]. */
  fun batchRestockPantry(
    pantry: List<PantryLedgerEntry>,
    catalog: List<IngredientCatalogItem>,
    deltas: List<RestockDelta>,
    nowIso: String = DEFAULT_TIMESTAMP_ISO,
  ): List<PantryLedgerEntry> =
    com.familykitchen.board.domain.batchRestockPantry(pantry, catalog, deltas, nowIso)

  // Catalog-free restock overload when tier metadata already lives on existing pantry entries.
  /** Convenience overload delegating to [com.familykitchen.board.domain.batchRestockPantry]. */
  fun batchRestockPantry(
    pantry: List<PantryLedgerEntry>,
    deltas: List<RestockDelta>,
    nowIso: String = DEFAULT_TIMESTAMP_ISO,
  ): List<PantryLedgerEntry> = com.familykitchen.board.domain.batchRestockPantry(pantry, deltas, nowIso)

  // Fridge Pulse +/- stepper entrypoint enforcing EC-10 floor-at-zero clamping.
  /** Delegates to [com.familykitchen.board.domain.adjustPantryQuantity]. */
  fun adjustPantryQuantity(
    pantry: List<PantryLedgerEntry>,
    catalog: List<IngredientCatalogItem>,
    ingredientId: String,
    deltaQuantity: Double,
    nowIso: String = DEFAULT_TIMESTAMP_ISO,
  ): List<PantryLedgerEntry> =
    com.familykitchen.board.domain.adjustPantryQuantity(pantry, catalog, ingredientId, deltaQuantity, nowIso)

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
