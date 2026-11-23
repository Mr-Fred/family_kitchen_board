package com.familykitchen.board.domain

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

// Strict perimeter validator mirroring Zod .strict() rules in pure Kotlin.
// Rejects unknown injected JSON keys, NaN/Infinity floats, negative stock, and ungrounded LLM IDs.

/**
 * Strict boundary schema and domain-invariant validator mirroring `schemas.ts` `.strict()` rules.
 *
 * Uses `ignoreUnknownKeys = false` on [strictJson] so any polluted or injected JSON property
 * (for example, `"injectedAdminFlag"` or `"unexpectedExtraProperty"`) immediately raises
 * [ValidationException], alongside deep structural checks on quantities, morning timing caps,
 * and catalog-ID grounding for untrusted on-device LLM outputs.
 */
object SchemaValidator {
  // Configure kotlinx.serialization with ignoreUnknownKeys = false for G-02 strictness parity.
  /** Canonical strict JSON codec rejecting unknown keys, NaN/Infinity literals, and malformed syntax. */
  val strictJson: Json = Json {
    ignoreUnknownKeys = false
    isLenient = false
    allowSpecialFloatingPointValues = false
    encodeDefaults = true
  }

  /**
   * Validates all invariants on an [IngredientCatalogItem] or throws [ValidationException].
   *
   * @param item Candidate catalog item to verify.
   * @return Validated [item].
   */
  fun validateIngredientCatalogItem(item: IngredientCatalogItem): IngredientCatalogItem {
    val issues = mutableListOf<String>()
    // Package sizes must be strictly positive so whole-package ceiling division never divides by 0.
    if (item.ingredientId.isBlank()) issues.add("ingredientId: must be non-blank")
    if (item.displayName.isBlank()) issues.add("displayName: must be non-blank")
    if (!item.defaultPackageQuantity.isFinite() || item.defaultPackageQuantity <= 0.0) {
      issues.add("defaultPackageQuantity: must be finite and > 0")
    }
    if (item.defaultShelfLifeDays <= 0) issues.add("defaultShelfLifeDays: must be > 0")
    if (!item.estimatedPackagePriceUsd.isFinite() || item.estimatedPackagePriceUsd < 0.0) {
      issues.add("estimatedPackagePriceUsd: must be finite and >= 0")
    }
    if (item.optimalPrepHint != null && item.optimalPrepHint.isBlank()) {
      issues.add("optimalPrepHint: must be non-blank when present")
    }
    if (item.substituteGroupIds.any { it.isBlank() }) issues.add("substituteGroupIds: entries must be non-blank")
    if (item.allergens.any { it.isBlank() }) issues.add("allergens: entries must be non-blank")
    throwIfIssues("IngredientCatalogItem", issues)
    return item
  }

  /**
   * Validates all invariants on a [BomLine] or throws [ValidationException].
   *
   * @param line Candidate BOM line to verify.
   * @param catalogById Optional catalog lookup map enforcing known `ingredientId` references.
   * @return Validated [line].
   */
  fun validateBomLine(
    line: BomLine,
    catalogById: Map<String, IngredientCatalogItem>? = null,
  ): BomLine {
    val issues = mutableListOf<String>()
    collectBomLineIssues("bomLine", line, catalogById, issues)
    throwIfIssues("BomLine", issues)
    return line
  }

  /**
   * Validates all invariants on a [Recipe] (mirroring `RecipeSchema.strict()`) or throws
   * [ValidationException].
   *
   * @param recipe Candidate recipe to verify.
   * @param catalogById Optional catalog lookup map enforcing valid BOM `ingredientId` references.
   * @return Validated [recipe].
   */
  fun validateRecipe(
    recipe: Recipe,
    catalogById: Map<String, IngredientCatalogItem>? = null,
  ): Recipe {
    val issues = mutableListOf<String>()
    // Verify identity, serving baseline, and <= 5m night-before shift limits (EC-01).
    if (recipe.recipeId.isBlank()) issues.add("recipeId: must be non-blank")
    if (recipe.title.isBlank()) issues.add("title: must be non-blank")
    if (recipe.sourceAttribution.isBlank()) issues.add("sourceAttribution: must be non-blank")
    if (recipe.allowedSlots.isEmpty()) issues.add("allowedSlots: must contain at least 1 MealSlotType")
    if (recipe.baseServings <= 0) issues.add("baseServings: must be > 0")
    if (recipe.rawPrepMinutes < 0) issues.add("rawPrepMinutes: must be >= 0")
    if (recipe.nightBeforePrepMinutes !in 0..MAX_NIGHT_BEFORE_SHIFT_MINUTES) {
      issues.add("nightBeforePrepMinutes: must be between 0 and $MAX_NIGHT_BEFORE_SHIFT_MINUTES")
    }
    if (recipe.cookMinutes < 0) issues.add("cookMinutes: must be >= 0")
    if (recipe.calibratedActiveMinutes < 0) issues.add("calibratedActiveMinutes: must be >= 0")
    if (recipe.toddlerPrepTip != null && recipe.toddlerPrepTip.isBlank()) {
      issues.add("toddlerPrepTip: must be non-blank when present")
    }
    if (recipe.caloriesPerServing < 0) issues.add("caloriesPerServing: must be >= 0")
    if (recipe.proteinGrams < 0) issues.add("proteinGrams: must be >= 0")
    if (recipe.carbsGrams < 0) issues.add("carbsGrams: must be >= 0")
    if (recipe.fatGrams < 0) issues.add("fatGrams: must be >= 0")
    if (recipe.cuisineTag.isBlank()) issues.add("cuisineTag: must be non-blank")
    if (recipe.bomLines.isEmpty()) issues.add("bomLines: must contain at least 1 BomLine")

    // Validate morning pacer steps, BOM lines, household overrides, and workout boost modifiers.
    recipe.morningSteps.forEachIndexed { idx, step ->
      if (step.stepNumber <= 0) issues.add("morningSteps[$idx].stepNumber: must be > 0")
      if (step.instruction.isBlank()) issues.add("morningSteps[$idx].instruction: must be non-blank")
      if (step.timerSeconds != null && step.timerSeconds < 0) {
        issues.add("morningSteps[$idx].timerSeconds: must be >= 0")
      }
      if (step.stepIngredientIds.any { it.isBlank() }) {
        issues.add("morningSteps[$idx].stepIngredientIds: entries must be non-blank")
      }
      if (catalogById != null) {
        step.stepIngredientIds.forEach { stepIngId ->
          if (stepIngId !in catalogById) {
            issues.add("morningSteps[$idx].stepIngredientIds: unknown catalog reference '$stepIngId'")
          }
        }
      }
    }
    recipe.bomLines.forEachIndexed { idx, bom -> collectBomLineIssues("bomLines[$idx]", bom, catalogById, issues) }
    recipe.householdBomOverrides.forEach { (ingId, qty) ->
      if (ingId.isBlank() || !qty.isFinite() || qty < 0.0) {
        issues.add("householdBomOverrides[$ingId]: must have non-blank key and finite qty >= 0")
      }
    }
    recipe.workoutModifiers.forEachIndexed { idx, modifier ->
      if (modifier.profile == WorkoutTiming.NONE) {
        issues.add("workoutModifiers[$idx].profile: must be PRE_WORKOUT_CARB or POST_WORKOUT_HIGH_PROTEIN")
      }
      if (modifier.instructionAddOn.isBlank()) {
        issues.add("workoutModifiers[$idx].instructionAddOn: must be non-blank")
      }
      modifier.extraBomLines.forEachIndexed { extraIdx, extraBom ->
        collectBomLineIssues("workoutModifiers[$idx].extraBomLines[$extraIdx]", extraBom, catalogById, issues)
      }
    }

    throwIfIssues("Recipe", issues)
    return recipe
  }

  /**
   * Validates a [PantryLedgerEntry] for non-negative finite quantities and tier alignment.
   *
   * @param entry Candidate pantry record.
   * @param catalogById Optional catalog lookup map checking tier alignment.
   * @return Validated [entry].
   */
  fun validatePantryLedgerEntry(
    entry: PantryLedgerEntry,
    catalogById: Map<String, IngredientCatalogItem>? = null,
  ): PantryLedgerEntry {
    val issues = mutableListOf<String>()
    // Reject negative quantities at boundaries so EC-10 floor-at-zero invariant starts clean.
    if (entry.ingredientId.isBlank()) issues.add("ingredientId: must be non-blank")
    if (!entry.currentQuantity.isFinite() || entry.currentQuantity < 0.0) {
      issues.add("currentQuantity: must be finite and >= 0")
    }
    if (entry.updatedAtIso.isBlank()) issues.add("updatedAtIso: must be non-blank")
    if (entry.purchasedDaysAgo != null && entry.purchasedDaysAgo < 0) {
      issues.add("purchasedDaysAgo: must be >= 0 when present")
    }
    val catalogItem = catalogById?.get(entry.ingredientId)
    if (catalogById != null && catalogItem == null) {
      issues.add("ingredientId: unknown catalog reference '${entry.ingredientId}'")
    }
    if (catalogItem != null && catalogItem.tier != entry.tier) {
      issues.add("tier: pantry tier ${entry.tier} mismatches catalog tier ${catalogItem.tier}")
    }
    throwIfIssues("PantryLedgerEntry", issues)
    return entry
  }

  /**
   * Validates a [CookedDeductionPayload] (`EC-04`–`EC-06`) or throws [ValidationException].
   *
   * @param payload Candidate cook-deduction payload.
   * @return Validated [payload].
   */
  fun validateCookedDeductionPayload(payload: CookedDeductionPayload): CookedDeductionPayload {
    val issues = mutableListOf<String>()
    if (payload.slotId.isBlank()) issues.add("slotId: must be non-blank")
    if (payload.recipeId.isBlank()) issues.add("recipeId: must be non-blank")
    if (payload.targetServings <= 0) issues.add("targetServings: must be > 0")
    payload.actualUsedQuantities?.forEach { (ingId, qty) ->
      if (ingId.isBlank() || !qty.isFinite() || qty < 0.0) {
        issues.add("actualUsedQuantities[$ingId]: must have non-blank key and finite qty >= 0")
      }
    }
    throwIfIssues("CookedDeductionPayload", issues)
    return payload
  }

  /**
   * Validates an atomic [RestockDelta] or throws [ValidationException].
   *
   * @param delta Candidate restock instruction.
   * @return Validated [delta].
   */
  fun validateRestockDelta(delta: RestockDelta): RestockDelta {
    val issues = mutableListOf<String>()
    if (delta.ingredientId.isBlank()) issues.add("ingredientId: must be non-blank")
    if (!delta.addedBaseQuantity.isFinite() || delta.addedBaseQuantity < 0.0) {
      issues.add("addedBaseQuantity: must be finite and >= 0")
    }
    throwIfIssues("RestockDelta", issues)
    return delta
  }

  /**
   * Validates a [MealSlot] and its weekday index (`0..6`) or throws [ValidationException].
   *
   * @param slot Candidate meal slot.
   * @return Validated [slot].
   */
  fun validateMealSlot(slot: MealSlot): MealSlot {
    val issues = mutableListOf<String>()
    if (slot.slotId.isBlank()) issues.add("slotId: must be non-blank")
    if (slot.dayIndex !in 0..6) issues.add("dayIndex: must be in 0..6")
    if (slot.recipeId.isBlank()) issues.add("recipeId: must be non-blank")
    if (slot.targetServings <= 0) issues.add("targetServings: must be > 0")
    if (slot.preppedTimeSavedMinutes < 0) issues.add("preppedTimeSavedMinutes: must be >= 0")
    throwIfIssues("MealSlot", issues)
    return slot
  }

  /**
   * Validates a 7-day [WeeklyBoard] and all contained [MealSlot] records.
   *
   * @param board Candidate weekly board.
   * @return Validated [board].
   */
  fun validateWeeklyBoard(board: WeeklyBoard): WeeklyBoard {
    val issues = mutableListOf<String>()
    if (board.boardId.isBlank()) issues.add("boardId: must be non-blank")
    if (board.householdId.isBlank()) issues.add("householdId: must be non-blank")
    if (board.weekStartDateIso.isBlank()) issues.add("weekStartDateIso: must be non-blank")
    throwIfIssues("WeeklyBoard", issues)
    board.slots.forEach(::validateMealSlot)
    return board
  }

  /**
   * Validates the complete offline [SeedCatalogDataset] (`assets/seed_catalog.json`), enforcing
   * unique IDs, 100% referential integrity across recipe BOMs and initial pantry entries, and
   * calibrated morning math consistency (`EC-01`).
   *
   * @param dataset Deserialized seed catalog dataset.
   * @return Validated [dataset].
   */
  fun validateSeedCatalogDataset(dataset: SeedCatalogDataset): SeedCatalogDataset {
    val issues = mutableListOf<String>()
    if (dataset.ingredients.isEmpty()) issues.add("ingredients: must not be empty")
    if (dataset.recipes.isEmpty()) issues.add("recipes: must not be empty")

    // Check uniqueness of ingredientId before building the catalog lookup map.
    val seenIngredientIds = mutableSetOf<String>()
    for (item in dataset.ingredients) {
      validateIngredientCatalogItem(item)
      if (!seenIngredientIds.add(item.ingredientId)) {
        issues.add("ingredients: duplicate ingredientId '${item.ingredientId}'")
      }
    }

    // Verify every recipe's BOM references known catalog items and matches calibrated 1.4x math.
    val catalogById = indexCatalogById(dataset.ingredients)
    val seenRecipeIds = mutableSetOf<String>()
    for (recipe in dataset.recipes) {
      validateRecipe(recipe, catalogById)
      if (!seenRecipeIds.add(recipe.recipeId)) {
        issues.add("recipes: duplicate recipeId '${recipe.recipeId}'")
      }
      val expectedMinutes = computeCalibratedMorningMinutes(recipe)
      if (recipe.calibratedActiveMinutes != expectedMinutes) {
        issues.add("recipes[${recipe.recipeId}].calibratedActiveMinutes: expected $expectedMinutes, got ${recipe.calibratedActiveMinutes}")
      }
    }

    // Verify every initial pantry record aligns with its catalog item's tracking tier.
    for (entry in dataset.initialPantry) {
      validatePantryLedgerEntry(entry, catalogById)
    }

    throwIfIssues("SeedCatalogDataset", issues)
    return dataset
  }

  /**
   * Validates and catalog-grounds an [AiMenuOptimizationProposal] produced by an untrusted LLM.
   *
   * @param proposal Candidate proposal to verify.
   * @param catalog Optional catalog for grounding recipe BOMs and suggested ingredients.
   * @param recipes Optional recipe library for verifying known `recipeId`s.
   * @param board Optional active weekly board for verifying `slotId` references.
   * @return Validated [proposal].
   */
  fun validateAiMenuOptimizationProposal(
    proposal: AiMenuOptimizationProposal,
    catalog: List<IngredientCatalogItem>? = null,
    recipes: List<Recipe>? = null,
    board: WeeklyBoard? = null,
  ): AiMenuOptimizationProposal {
    val issues = mutableListOf<String>()
    if (proposal.proposalId.isBlank()) issues.add("proposalId: must be non-blank")
    if (proposal.assistantSummary.isBlank()) issues.add("assistantSummary: must be non-blank")

    val catalogById = catalog?.let(::indexCatalogById)
    val recipeIds = recipes?.map { it.recipeId }?.toSet()
    val boardSlotsById = board?.slots?.associateBy { it.slotId }

    // Enforce catalog/board ID grounding, <= 25m Breakfast cap (EC-01), and toddler safety (EC-08).
    proposal.slotSwaps.forEachIndexed { idx, swap ->
      if (swap.slotId.isBlank()) issues.add("slotSwaps[$idx].slotId: must be non-blank")
      if (swap.dayIndex !in 0..6) issues.add("slotSwaps[$idx].dayIndex: must be in 0..6")
      if (swap.previousRecipeId.isBlank()) issues.add("slotSwaps[$idx].previousRecipeId: must be non-blank")
      if (swap.rationale.isBlank()) issues.add("slotSwaps[$idx].rationale: must be non-blank")
      if (boardSlotsById != null && swap.slotId !in boardSlotsById) {
        issues.add("slotSwaps[$idx].slotId: unknown board slot '${swap.slotId}'")
      }
      if (recipeIds != null && swap.suggestedRecipe.recipeId !in recipeIds) {
        issues.add("slotSwaps[$idx].suggestedRecipe.recipeId: ungrounded recipe '${swap.suggestedRecipe.recipeId}'")
      }
      validateRecipe(swap.suggestedRecipe, catalogById)
      val calibrated = computeCalibratedMorningMinutes(swap.suggestedRecipe)
      if (swap.slotType == MealSlotType.BREAKFAST && calibrated > MAX_WEEKDAY_BREAKFAST_MINUTES) {
        issues.add("slotSwaps[$idx]: BREAKFAST swap '${swap.suggestedRecipe.recipeId}' exceeds 25m cap ($calibrated min)")
      }
      if (swap.suggestedRecipe.toddlerPrepTip.isNullOrBlank()) {
        issues.add("slotSwaps[$idx].suggestedRecipe.toddlerPrepTip: must be non-blank for child safety")
      }
    }

    proposal.newIngredientSuggestions.forEachIndexed { idx, suggestion ->
      validateIngredientCatalogItem(suggestion.item)
      validateRestockDelta(suggestion.restockDelta)
      if (suggestion.recommendedPackageCount <= 0) {
        issues.add("newIngredientSuggestions[$idx].recommendedPackageCount: must be > 0")
      }
      if (suggestion.brainBoosterBenefit.isBlank()) {
        issues.add("newIngredientSuggestions[$idx].brainBoosterBenefit: must be non-blank")
      }
      if (suggestion.pairedRecipeTitle.isBlank()) {
        issues.add("newIngredientSuggestions[$idx].pairedRecipeTitle: must be non-blank")
      }
      if (suggestion.restockDelta.ingredientId != suggestion.item.ingredientId) {
        issues.add("newIngredientSuggestions[$idx].restockDelta.ingredientId: must match item.ingredientId")
      }
    }

    val featured = proposal.featuredRecipeCard
    if (featured != null) {
      validateRecipe(featured.recipe, catalogById)
      if (recipeIds != null && featured.recipe.recipeId !in recipeIds) {
        issues.add("featuredRecipeCard.recipe.recipeId: ungrounded recipe '${featured.recipe.recipeId}'")
      }
      if (featured.preferredSlotType != null && featured.preferredSlotType !in featured.recipe.allowedSlots) {
        issues.add(
          "featuredRecipeCard.preferredSlotType: ${featured.preferredSlotType} not in allowedSlots ${featured.recipe.allowedSlots}",
        )
      }
      if (featured.matchSummary.matchPercentage !in 0..100) {
        issues.add("featuredRecipeCard.matchSummary.matchPercentage: must be in 0..100")
      }
      if (!featured.matchSummary.rescuedValueUsd.isFinite() || featured.matchSummary.rescuedValueUsd < 0.0) {
        issues.add("featuredRecipeCard.matchSummary.rescuedValueUsd: must be finite and >= 0")
      }
      featured.matchSummary.missingRestockDeltas.forEach(::validateRestockDelta)
    }

    throwIfIssues("AiMenuOptimizationProposal", issues)
    return proposal
  }

  // Strict JSON entrypoints combining kotlinx.serialization unknown-key rejection with domain math checks.
  /** Decodes and strictly validates a [SeedCatalogDataset] JSON string (rejecting unknown keys). */
  fun decodeSeedCatalogDatasetJson(rawJson: String): SeedCatalogDataset =
    validateSeedCatalogDataset(decodeStrict("SeedCatalogDataset", rawJson))

  // Rejects polluted Recipe JSON payloads (such as injected unknown properties in G-02 tests).
  /** Decodes and strictly validates a [Recipe] JSON string (rejecting unknown injected keys). */
  fun decodeRecipeJson(rawJson: String): Recipe = validateRecipe(decodeStrict("Recipe", rawJson))

  // Rejects polluted CookedDeductionPayload JSON strings and negative override quantities.
  /** Decodes and strictly validates a [CookedDeductionPayload] JSON string (`G-02` strictness). */
  fun decodeCookedDeductionPayloadJson(rawJson: String): CookedDeductionPayload =
    validateCookedDeductionPayload(decodeStrict("CookedDeductionPayload", rawJson))

  // Ensures grocery restock deltas decoded from storage or intake carry finite >= 0 quantities.
  /** Decodes and strictly validates a [RestockDelta] JSON string. */
  fun decodeRestockDeltaJson(rawJson: String): RestockDelta =
    validateRestockDelta(decodeStrict("RestockDelta", rawJson))

  // Grounds untrusted LLM proposal JSON against active catalog IDs, recipes, and board slot IDs.
  /** Decodes and catalog-grounds an untrusted [AiMenuOptimizationProposal] JSON string. */
  fun decodeAiProposalJson(
    rawJson: String,
    catalog: List<IngredientCatalogItem>? = null,
    recipes: List<Recipe>? = null,
    board: WeeklyBoard? = null,
  ): AiMenuOptimizationProposal =
    validateAiMenuOptimizationProposal(decodeStrict("AiMenuOptimizationProposal", rawJson), catalog, recipes, board)

  // Decode using strictJson so unknown keys or type mismatches become domain ValidationExceptions.
  // Never leaks raw kotlinx.serialization internals past the domain boundary.
  private inline fun <reified T> decodeStrict(entityName: String, rawJson: String): T {
    if (rawJson.isBlank()) {
      throw ValidationException("Invalid $entityName payload: JSON string is blank", listOf("<root>: blank JSON"))
    }
    return try {
      strictJson.decodeFromString<T>(rawJson)
    } catch (err: SerializationException) {
      val detail = err.message ?: "strict JSON deserialization error"
      throw ValidationException("Invalid $entityName payload: $detail", listOf(detail), err)
    } catch (err: IllegalArgumentException) {
      val detail = err.message ?: "invalid JSON argument"
      throw ValidationException("Invalid $entityName payload: $detail", listOf(detail), err)
    }
  }

  // Shared helper checking BomLine fields and optional catalog referential integrity.
  private fun collectBomLineIssues(
    prefix: String,
    line: BomLine,
    catalogById: Map<String, IngredientCatalogItem>?,
    issues: MutableList<String>,
  ) {
    if (line.ingredientId.isBlank()) issues.add("$prefix.ingredientId: must be non-blank")
    if (!line.baseQuantity.isFinite() || line.baseQuantity < 0.0) {
      issues.add("$prefix.baseQuantity: must be finite and >= 0")
    }
    if (line.substituteGroupId != null && line.substituteGroupId.isBlank()) {
      issues.add("$prefix.substituteGroupId: must be non-blank when provided")
    }
    if (catalogById != null && line.ingredientId !in catalogById) {
      issues.add("$prefix.ingredientId: unknown catalog reference '${line.ingredientId}'")
    }
  }

  // Aggregate all collected field violations into a single structured ValidationException.
  private fun throwIfIssues(entityName: String, issues: List<String>) {
    if (issues.isNotEmpty()) {
      throw ValidationException("Invalid $entityName payload: ${issues.joinToString("; ")}", issues)
    }
  }
}
