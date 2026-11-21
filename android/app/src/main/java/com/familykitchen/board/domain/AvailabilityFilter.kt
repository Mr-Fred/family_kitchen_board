package com.familykitchen.board.domain

// Pure domain filter enforcing EC-02 (unavailable/allergen hard-hide) and EC-03 (deterministic
// substituteGroupId auto-swap with canonical BaseUnit consistency and in-stock pantry priority).

/**
 * Indexes an ingredient catalog collection by [IngredientCatalogItem.ingredientId] for `O(1)` lookup.
 *
 * @param catalog Collection of ingredient catalog records.
 * @return Immutable map keyed by `ingredientId`.
 */
fun indexCatalogById(
  catalog: Iterable<IngredientCatalogItem>,
): Map<String, IngredientCatalogItem> = catalog.associateBy { it.ingredientId }

/**
 * Indexes a pantry ledger collection by [PantryLedgerEntry.ingredientId] for `O(1)` lookup.
 *
 * @param pantry Collection of household pantry ledger records.
 * @return Immutable map keyed by `ingredientId`.
 */
fun indexPantryById(
  pantry: Iterable<PantryLedgerEntry>,
): Map<String, PantryLedgerEntry> = pantry.associateBy { it.ingredientId }

/**
 * Canonicalizes an allergen label (for example `"egg"` vs `"EGGS"`, `"Avoid Dairy"` vs `"DAIRY"`)
 * into a deterministic uppercase token so household profile toggles match catalog tags reliably.
 */
internal fun canonicalizeAllergenToken(rawToken: String): String {
  // Strip UI prefixes like "Avoid " and normalize singular/plural variants (EGG/EGGS, NUT/NUTS).
  val cleaned = rawToken
    .trim()
    .uppercase()
    .removePrefix("AVOID ")
    .replace(Regex("[^A-Z0-9]+"), "_")
    .trim('_')
  return when (cleaned) {
    "EGG", "EGGS" -> "EGGS"
    "PEANUT", "PEANUTS" -> "PEANUTS"
    "NUT", "NUTS", "TREE_NUT", "TREE_NUTS" -> "NUTS"
    else -> cleaned
  }
}

/**
 * Checks whether a candidate substitute ingredient currently has usable stock in the pantry.
 */
private fun isSubstituteCandidateInStock(
  candidate: IngredientCatalogItem,
  requiredBaseQuantity: Double,
  pantryById: Map<String, PantryLedgerEntry>,
): Boolean {
  val entry = pantryById[candidate.ingredientId]
  // Tier-2 staples remain cookable when PLENTY or LOW; only explicit OUT blocks usage.
  return when (candidate.tier) {
    IngredientTier.TIER_2_STAPLE ->
      entry == null || (!entry.isOutOfStock && entry.stapleStatus != StapleStatus.OUT)
    IngredientTier.TIER_1_QUANTITATIVE ->
      entry != null && !entry.isOutOfStock && entry.currentQuantity >= requiredBaseQuantity
  }
}

/**
 * Selects the best locally available, allergen-safe, unit-compatible substitute for a blocked BOM
 * line (`EC-03`), preferring in-stock pantry items with lexicographical `ingredientId` tie-breaking.
 */
private fun findBestSubstituteCandidate(
  bomLine: BomLine,
  originalItem: IngredientCatalogItem?,
  catalogById: Map<String, IngredientCatalogItem>,
  canonicalAllergens: Set<String>,
  pantryById: Map<String, PantryLedgerEntry>?,
): IngredientCatalogItem? {
  val groupId = bomLine.substituteGroupId?.trim().orEmpty()
  if (groupId.isEmpty()) {
    return null
  }

  // Filter candidates strictly by group membership, local availability, unit parity, and allergens.
  // Unit parity prevents swapping 150g of berries into 150 count of whole fruit.
  val candidates = catalogById.values.filter { item ->
    val inGroup = groupId in item.substituteGroupIds
    val unitCompatible = originalItem == null || item.baseUnit == originalItem.baseUnit
    val allergenSafe = item.allergens.none { canonicalizeAllergenToken(it) in canonicalAllergens }
    item.ingredientId != bomLine.ingredientId &&
      item.isLocallyAvailable &&
      inGroup &&
      unitCompatible &&
      allergenSafe
  }

  if (candidates.isEmpty()) {
    return null
  }

  // Prefer in-stock pantry substitutes first when pantry context is supplied, then tie-break by ID.
  // This avoids queueing extra grocery purchases when a valid substitute is already on the shelf.
  return candidates.sortedWith { left, right ->
    val stockComparison = when (pantryById) {
      null -> 0
      else -> {
        val leftScore = if (isSubstituteCandidateInStock(left, bomLine.baseQuantity, pantryById)) 1 else 0
        val rightScore = if (isSubstituteCandidateInStock(right, bomLine.baseQuantity, pantryById)) 1 else 0
        rightScore.compareTo(leftScore)
      }
    }
    when {
      stockComparison != 0 -> stockComparison
      else -> left.ingredientId.compareTo(right.ingredientId)
    }
  }.firstOrNull()
}

/**
 * Evaluates whether [recipe] can be scheduled given local market availability (`EC-02`),
 * deterministic `substituteGroupId` resolution with unit-consistency and pantry tie-breaking
 * (`EC-03`), and active [householdAllergens].
 *
 * @param recipe Candidate recipe to inspect.
 * @param catalogById Map of catalog ingredients keyed by `ingredientId`.
 * @param householdAllergens Active household allergen exclusions (case-insensitive).
 * @param pantryById Optional pantry map enabling in-stock substitute tie-breaking.
 * @return [AvailabilityEvaluation] detailing schedulability and any rewritten BOM lines.
 */
fun evaluateRecipeAvailability(
  recipe: Recipe,
  catalogById: Map<String, IngredientCatalogItem>,
  householdAllergens: List<String> = emptyList(),
  pantryById: Map<String, PantryLedgerEntry>? = null,
): AvailabilityEvaluation {
  // Normalize all active household allergen exclusions once per evaluation call.
  val canonicalAllergens = householdAllergens
    .map(::canonicalizeAllergenToken)
    .filter { it.isNotEmpty() }
    .toSet()

  val blockedIngredientIds = mutableListOf<String>()
  val blockedAllergens = linkedSetOf<String>()
  val resolvedBomLines = mutableListOf<BomLine>()
  val substitutionsApplied = linkedMapOf<String, String>()
  var encounteredUnavailable = false
  var encounteredAllergen = false

  for (bomLine in recipe.bomLines) {
    val catalogItem = catalogById[bomLine.ingredientId]
    val matchingAllergens = catalogItem?.allergens?.filter { allergen ->
      canonicalizeAllergenToken(allergen) in canonicalAllergens
    }.orEmpty()
    val hasAllergenConflict = matchingAllergens.isNotEmpty()
    val isLocallyAvailable = catalogItem?.isLocallyAvailable ?: false

    // Fast path: locally available and allergen-safe BOM lines pass through unchanged.
    if (isLocallyAvailable && !hasAllergenConflict) {
      resolvedBomLines.add(bomLine)
      continue
    }

    // Attempt deterministic EC-03 substitute resolution within the same canonical BaseUnit.
    val substitute = findBestSubstituteCandidate(
      bomLine = bomLine,
      originalItem = catalogItem,
      catalogById = catalogById,
      canonicalAllergens = canonicalAllergens,
      pantryById = pantryById,
    )

    if (substitute != null) {
      resolvedBomLines.add(bomLine.copy(ingredientId = substitute.ingredientId))
      substitutionsApplied[bomLine.ingredientId] = substitute.ingredientId
      continue
    }

    // EC-02: No substitute found; block scheduling whenever the BOM line is required (!isOptional).
    resolvedBomLines.add(bomLine)
    if (!bomLine.isOptional) {
      if (bomLine.ingredientId !in blockedIngredientIds) {
        blockedIngredientIds.add(bomLine.ingredientId)
      }
      when {
        hasAllergenConflict -> {
          encounteredAllergen = true
          blockedAllergens.addAll(matchingAllergens)
        }
        else -> encounteredUnavailable = true
      }
    }
  }

  // Classify failure reason cleanly between allergen-only blocks vs local market unavailability.
  val isSchedulable = blockedIngredientIds.isEmpty()
  val reason = when {
    isSchedulable -> AvailabilityReason.AVAILABLE
    encounteredAllergen && !encounteredUnavailable -> AvailabilityReason.ALLERGEN_BLOCKED
    else -> AvailabilityReason.UNAVAILABLE_INGREDIENT
  }

  return AvailabilityEvaluation(
    recipeId = recipe.recipeId,
    isSchedulable = isSchedulable,
    reason = reason,
    blockedIngredientIds = blockedIngredientIds,
    blockedAllergens = blockedAllergens.toList(),
    resolvedBomLines = resolvedBomLines,
    substitutionsApplied = substitutionsApplied,
  )
}

/**
 * List-based overload of [evaluateRecipeAvailability] accepting raw catalog and optional pantry lists.
 *
 * @param recipe Candidate recipe to inspect.
 * @param catalog Household ingredient catalog list.
 * @param householdAllergens Active household allergen exclusions.
 * @param pantry Optional pantry ledger list for in-stock substitute tie-breaking.
 * @return [AvailabilityEvaluation] detailing schedulability and resolved BOM lines.
 */
fun evaluateRecipeAvailability(
  recipe: Recipe,
  catalog: List<IngredientCatalogItem>,
  householdAllergens: List<String> = emptyList(),
  pantry: List<PantryLedgerEntry>? = null,
): AvailabilityEvaluation =
  evaluateRecipeAvailability(
    recipe = recipe,
    catalogById = indexCatalogById(catalog),
    householdAllergens = householdAllergens,
    pantryById = pantry?.let(::indexPantryById),
  )

/**
 * Filters [recipes] down to schedulable dishes (`EC-02`) with substitute-rewritten BOM lines
 * applied directly on each returned [Recipe] (`EC-03`).
 *
 * @param recipes Candidate recipe collection.
 * @param catalogById Map of catalog ingredients keyed by `ingredientId`.
 * @param householdAllergens Active household allergen exclusions.
 * @param pantryById Optional pantry map for in-stock substitute tie-breaking.
 * @return Ordered list of schedulable recipes with resolved `bomLines`.
 */
fun filterSchedulableRecipes(
  recipes: List<Recipe>,
  catalogById: Map<String, IngredientCatalogItem>,
  householdAllergens: List<String> = emptyList(),
  pantryById: Map<String, PantryLedgerEntry>? = null,
): List<Recipe> =
  recipes.mapNotNull { recipe ->
    // Rewrite resolvedBomLines onto returned recipes so downstream planners reserve the substitute.
    val evaluation = evaluateRecipeAvailability(recipe, catalogById, householdAllergens, pantryById)
    when {
      evaluation.isSchedulable -> recipe.copy(bomLines = evaluation.resolvedBomLines)
      else -> null
    }
  }

/**
 * List-based overload of [filterSchedulableRecipes] accepting raw catalog and optional pantry lists.
 *
 * @param recipes Candidate recipe collection.
 * @param catalog Household ingredient catalog list.
 * @param householdAllergens Active household allergen exclusions.
 * @param pantry Optional pantry ledger list for in-stock substitute tie-breaking.
 * @return Ordered list of schedulable recipes with resolved `bomLines`.
 */
fun filterSchedulableRecipes(
  recipes: List<Recipe>,
  catalog: List<IngredientCatalogItem>,
  householdAllergens: List<String> = emptyList(),
  pantry: List<PantryLedgerEntry>? = null,
): List<Recipe> =
  filterSchedulableRecipes(
    recipes = recipes,
    catalogById = indexCatalogById(catalog),
    householdAllergens = householdAllergens,
    pantryById = pantry?.let(::indexPantryById),
  )

/**
 * Object namespace exposing availability and substitution operations for qualified calls.
 */
object AvailabilityFilter {
  // List-backed entrypoints build temporary O(1) maps once per call for ergonomic UI/test usage.
  /** Delegates to top-level `evaluateRecipeAvailability` with list inputs. */
  fun evaluateRecipeAvailability(
    recipe: Recipe,
    catalog: List<IngredientCatalogItem>,
    householdAllergens: List<String> = emptyList(),
    pantry: List<PantryLedgerEntry>? = null,
  ): AvailabilityEvaluation =
    com.familykitchen.board.domain.evaluateRecipeAvailability(recipe, catalog, householdAllergens, pantry)

  // Map-backed entrypoints avoid re-indexing inside tight 30-recipe planner loops.
  /** Delegates to top-level `evaluateRecipeAvailability` with pre-indexed map inputs. */
  fun evaluateRecipeAvailability(
    recipe: Recipe,
    catalogById: Map<String, IngredientCatalogItem>,
    householdAllergens: List<String> = emptyList(),
    pantryById: Map<String, PantryLedgerEntry>? = null,
  ): AvailabilityEvaluation =
    com.familykitchen.board.domain.evaluateRecipeAvailability(recipe, catalogById, householdAllergens, pantryById)

  // Returns only schedulable recipes with EC-03 substitute BOM rewrites already applied.
  /** Delegates to top-level `filterSchedulableRecipes` with list inputs. */
  fun filterSchedulableRecipes(
    recipes: List<Recipe>,
    catalog: List<IngredientCatalogItem>,
    householdAllergens: List<String> = emptyList(),
    pantry: List<PantryLedgerEntry>? = null,
  ): List<Recipe> =
    com.familykitchen.board.domain.filterSchedulableRecipes(recipes, catalog, householdAllergens, pantry)

  // Map-backed batch filter used by autoDraftWeeklyBoard and instant swap engines.
  /** Delegates to top-level `filterSchedulableRecipes` with pre-indexed map inputs. */
  fun filterSchedulableRecipes(
    recipes: List<Recipe>,
    catalogById: Map<String, IngredientCatalogItem>,
    householdAllergens: List<String> = emptyList(),
    pantryById: Map<String, PantryLedgerEntry>? = null,
  ): List<Recipe> =
    com.familykitchen.board.domain.filterSchedulableRecipes(recipes, catalogById, householdAllergens, pantryById)
}
