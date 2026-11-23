package com.familykitchen.board.domain

import kotlin.math.max
import kotlin.math.roundToInt

// Pure functional AI Menu Optimizer, 4-Member 1-Pot Plating Guide, and Custom Family Meal Engine.
// Works side-by-side with AiVisualAndToolGrounding.kt while exposing the unified AiMenuAdvisorEngine
// facade for callers using either top-level imports or qualified AiMenuAdvisorEngine.* invocations.

/**
 * Output of applying an [AiMenuOptimizationProposal] in a single pure batch reduction.
 *
 * @property updatedBoard Weekly board with approved meal slot swaps applied.
 * @property updatedRecipes Recipe library including any enriched swap recipes.
 * @property updatedCatalog Ingredient catalog merged with newly suggested brain-booster items.
 * @property groceryRestockDeltas Restock deltas for suggested ingredients.
 * @property queuedGroceryIngredientIds Ingredient IDs ready to merge into `aiAddedGroceryIds`.
 */
data class AppliedAiMenuProposalBatch(
  val updatedBoard: WeeklyBoard,
  val updatedRecipes: List<Recipe>,
  val updatedCatalog: List<IngredientCatalogItem>,
  val groceryRestockDeltas: List<RestockDelta>,
  val queuedGroceryIngredientIds: Set<String>,
)

/**
 * Output of creating and scheduling a custom household meal across either a single slot or all
 * `Monday–Friday` (`0..4`) weekday slots in one atomic batch.
 *
 * @property customRecipe Validated custom [Recipe] added to the household library.
 * @property updatedBoard Updated [WeeklyBoard] with target slot(s) pointing to [customRecipe].
 * @property updatedRecipes Updated recipe collection containing [customRecipe].
 * @property queuedGroceryIngredientIds Optional ingredient IDs queued onto the Smart Grocery List.
 */
data class CustomFamilyMealBatchResult(
  val customRecipe: Recipe,
  val updatedBoard: WeeklyBoard,
  val updatedRecipes: List<Recipe>,
  val queuedGroceryIngredientIds: Set<String>,
)

internal data class BrainNutrientAnalysis(
  val nutrients: List<String>,
  val inStockIngredientIds: List<String>,
  val score: Int,
)

private data class NutrientRule(val keywords: List<String>, val nutrients: List<String>, val scoreWeight: Int)

// Pediatric neurodevelopmental knowledge table mapping core ingredients to Choline, DHA, and Iron.
private val BRAIN_BOOSTER_RULES: List<NutrientRule> = listOf(
  NutrientRule(listOf("egg"), listOf("Choline (Memory & Neurotransmitters)", "Vitamin B12", "Lutein"), 5),
  NutrientRule(
    listOf("salmon", "fish", "tuna", "sardine", "tilapia", "bangus", "shrimp", "tofu"),
    listOf("Omega-3 DHA, Iodine & Complete Protein", "Vitamin D & Choline"), 6,
  ),
  NutrientRule(
    listOf(
      "blueberry", "berry", "banana", "mango", "plantain", "avocado", "sweet_potato", "kamote",
      "cassava", "orange", "tangerine", "dalandan", "calamansi", "pineapple", "dragon",
    ),
    listOf("Vitamin C & Anthocyanin Antioxidants (2x Iron Absorption)", "Healthy Brain Fats, Prebiotic Fiber & Potassium"), 4,
  ),
  NutrientRule(listOf("oats", "oat", "cornmeal", "rice", "baguette"), listOf("Slow-Release Glucose (Classroom Focus)", "B-Vitamins"), 3),
  NutrientRule(
    listOf("spinach", "kale", "malunggay", "pechay", "kangkong", "beans", "monggo", "lentil", "egusi", "nuts", "kasuy", "seeds", "peanut"),
    listOf("Iron & Folate (Cerebral Oxygenation)", "Zinc & Vitamin E"), 5,
  ),
  NutrientRule(listOf("yogurt", "milk", "cheese"), listOf("Probiotics (Gut-Brain Axis)", "Iodine & Tyrosine"), 3),
)

/**
 * Infers the canonical [NutritionFocusTag] from a free-form natural-language household prompt.
 *
 * @param userPrompt Natural-language question or goal entered by the user.
 * @return Matched [NutritionFocusTag] driving scoring and system prompt synthesis.
 */
fun inferNutritionFocusFromPrompt(userPrompt: String): NutritionFocusTag {
  val normalized = userPrompt.trim().lowercase()
  // Route zero-waste, <20m morning, iron, and workout prompts before defaulting to brain boosters.
  return when {
    Regex("waste|perishable|use up|expir").containsMatchIn(normalized) -> NutritionFocusTag.PANTRY_ZERO_WASTE
    Regex("20m|under 20|<20|quick morning|fast breakfast").containsMatchIn(normalized) ->
      NutritionFocusTag.QUICK_MORNING_UNDER_20M
    Regex("iron|immunity|spinach").containsMatchIn(normalized) -> NutritionFocusTag.IRON_AND_IMMUNITY
    Regex("workout|high protein|muscle").containsMatchIn(normalized) -> NutritionFocusTag.HIGH_PROTEIN_FAMILY
    else -> NutritionFocusTag.KIDS_BRAIN_BOOSTER
  }
}

/**
 * Generates a deterministic, 100% offline pantry-grounded menu optimization and brain-booster
 * proposal enforcing `<= 25m` Breakfast caps (`EC-01`) and child safety callouts (`EC-08`).
 *
 * @param userPrompt Natural-language user request.
 * @param board Active 7-day [WeeklyBoard].
 * @param recipes Candidate recipe library.
 * @param catalog Household ingredient catalog.
 * @param pantry Current household pantry ledger.
 * @param householdAllergens Active household allergen exclusions.
 * @param householdProfile Optional full household profile.
 * @param todayDayIndex Active weekday index (`0 = Monday`..`6 = Sunday`) so swaps target today and tomorrow first.
 * @return Grounded [AiMenuOptimizationProposal].
 */
fun generatePantryGroundedMenuProposal(
  userPrompt: String,
  board: WeeklyBoard,
  recipes: List<Recipe>,
  catalog: List<IngredientCatalogItem>,
  pantry: List<PantryLedgerEntry>,
  householdAllergens: List<String> = emptyList(),
  householdProfile: HouseholdProfile? = null,
  todayDayIndex: Int = 0,
): AiMenuOptimizationProposal {
  val effectiveAllergens = householdAllergens.ifEmpty { householdProfile?.householdAllergens.orEmpty() }
  val preferredCuisines = householdProfile?.preferredCuisines.orEmpty()
  val focusTag = inferNutritionFocusFromPrompt(userPrompt)
  val catalogById = indexCatalogById(catalog)
  val pantryById = indexPantryById(pantry)
  val normalizedPrompt = userPrompt.trim().lowercase()

  // Conversational fast path for pure greetings or pantry stock audits without swap card clutter.
  if (isPurelyConversationalPrompt(normalizedPrompt)) {
    val inStockLabels = pantry
      .filter { !it.isOutOfStock && (it.tier == IngredientTier.TIER_2_STAPLE || it.currentQuantity > 0.0) }
      .take(8).map { catalogById[it.ingredientId]?.displayName ?: it.ingredientId }
    val summary = when {
      Regex("pantry|stock|fridge").containsMatchIn(normalizedPrompt) ->
        "In stock right now: ${inStockLabels.joinToString(", ").ifEmpty { "no stocked items yet" }}. " +
          "Ask me for kids brain boosters, <20m breakfasts, or zero-waste swaps anytime!"
      else ->
        "Hello! I am your offline Family Kitchen Co-Pilot. You have ${inStockLabels.size}+ stocked items ready " +
          "(${inStockLabels.take(4).joinToString(", ")}). Would you like brain-booster meal swaps or a <20m morning plan?"
    }
    return AiMenuOptimizationProposal(
      proposalId = "ai_conv_${board.boardId}", focusTag = focusTag, assistantSummary = summary,
      isConversationalOnly = true,
      followUpPrompts = listOf("Brain boosters for 8yo & 2yo", "Quick morning under 20m", "Use up pantry perishables"),
    )
  }

  data class ScoredCandidate(val recipe: Recipe, val nutrients: List<String>, val inStockIds: List<String>, val score: Int)

  // Evaluate local-market availability (EC-02/EC-03), preferred cuisines, and brain-booster scores.
  val youngestStage = householdProfile?.youngestStage ?: ChildGrowthStage.TODDLER_1_2Y
  val scoredCandidates = recipes.mapNotNull { recipe ->
    val availability = evaluateRecipeAvailability(recipe, catalogById, effectiveAllergens, pantryById)
    if (!availability.isSchedulable) return@mapNotNull null
    val calibratedMinutes = computeCalibratedMorningMinutes(recipe)
    val safeTip = recipe.toddlerPrepTip?.trim()?.takeIf { it.isNotEmpty() }
      ?: "Serve in soft bite-sized portions and crush any seeds or nuts finely for 2yo."
    val resolved = recipe.copy(bomLines = availability.resolvedBomLines, calibratedActiveMinutes = calibratedMinutes, toddlerPrepTip = safeTip)
    val plating = buildFamilyGoalPlatingGuide(
      resolved, catalog, householdProfile?.members.orEmpty(), householdProfile?.activeLifeEvents.orEmpty(), youngestStage,
    )
    val enriched = resolved.copy(familyGoalPlating = plating)
    val analysis = analyzeRecipeBrainNutrients(enriched, catalogById, pantryById, focusTag)
    // Reward recipes matching household preferred cuisines, giving extra weight to native local stock.
    val cuisineBonus = if (preferredCuisines.isNotEmpty() && doesRecipeMatchPreferredCuisines(enriched, preferredCuisines)) {
      if (availability.substitutionsApplied.isEmpty()) 30 else 22
    } else {
      0
    }
    ScoredCandidate(enriched, analysis.nutrients, analysis.inStockIngredientIds, analysis.score + cuisineBonus)
  }.sortedByDescending { it.score }

  // Prioritize todayDayIndex and (todayDayIndex + 1) % 7 ahead of later days (never hardcoded Monday 0).
  val safeToday = ((todayDayIndex % 7) + 7) % 7
  // Order PLANNED slots by forward modular distance from safeToday so tonight/tomorrow win first.
  val plannedSlots = board.slots
    .filter { it.status == MealSlotStatus.PLANNED }
    .sortedWith(
      compareBy<MealSlot> { (it.dayIndex - safeToday + 7) % 7 }
        .thenBy { it.slotType.chronologicalRank },
    )
  val targetSlots = mutableListOf<MealSlot>()
  for (st in listOf(MealSlotType.BREAKFAST, MealSlotType.LUNCH, MealSlotType.DINNER)) {
    plannedSlots.firstOrNull { it.slotType == st }?.let(targetSlots::add)
  }
  for (extra in plannedSlots) {
    if (targetSlots.size >= 3) break
    if (targetSlots.none { it.slotId == extra.slotId }) targetSlots.add(extra)
  }

  val slotSwaps = mutableListOf<AiSlotSwapSuggestion>()
  val usedRecipeIds = mutableSetOf<String>()

  // Pair each selected planned slot with the highest-scoring distinct in-stock candidate.
  for (slot in targetSlots) {
    val eligible = scoredCandidates.filter { cand ->
      slot.slotType in cand.recipe.allowedSlots &&
        (slot.slotType != MealSlotType.BREAKFAST || cand.recipe.calibratedActiveMinutes <= MAX_WEEKDAY_BREAKFAST_MINUTES) &&
        cand.inStockIds.isNotEmpty()
    }
    val chosen = eligible.firstOrNull { it.recipe.recipeId != slot.recipeId && it.recipe.recipeId !in usedRecipeIds }
      ?: eligible.firstOrNull() ?: continue

    usedRecipeIds.add(chosen.recipe.recipeId)
    val inStockNames = chosen.inStockIds.take(4).joinToString(", ") { catalogById[it]?.displayName ?: it }
    slotSwaps.add(
      AiSlotSwapSuggestion(
        slotId = slot.slotId,
        dayIndex = slot.dayIndex,
        slotType = slot.slotType,
        previousRecipeId = slot.recipeId,
        suggestedRecipe = chosen.recipe,
        rationale = "Uses in-stock $inStockNames (${chosen.recipe.calibratedActiveMinutes}m active) " +
          "to boost ${chosen.nutrients.take(2).joinToString(" + ")} for the 8yo and ${youngestStage.shortLabel}.",
        brainBoosterNutrients = chosen.nutrients,
        usesInStockIngredientsIds = chosen.inStockIds,
        familyGoalPlating = chosen.recipe.familyGoalPlating,
      ),
    )
  }

  // Filter curated discovery candidates against active household allergen exclusions.
  val allergenTokens = effectiveAllergens.map(::canonicalizeAllergenToken).toSet()
  val newIngredientSuggestions = CURATED_BRAIN_BOOSTER_CANDIDATES.filter { candidate ->
    candidate.item.allergens.none { canonicalizeAllergenToken(it) in allergenTokens }
  }
  val topInStockSummary = slotSwaps.flatMap { it.usesInStockIngredientsIds }.distinct()
    .take(5).joinToString(", ") { catalogById[it]?.displayName ?: it }.ifEmpty { "current pantry staples" }
  val targetServings = householdProfile?.effectiveTargetServings ?: 4
  val activeCurrency = householdProfile?.currency ?: HouseholdCurrency.PHP
  val featuredCard = synthesizeFeaturedAiRecipeCard(
    userPrompt = userPrompt,
    focusTag = focusTag,
    slotSwaps = slotSwaps,
    candidateRecipes = scoredCandidates.map { it.recipe },
    catalog = catalog,
    pantry = pantry,
    effectiveAllergens = effectiveAllergens,
    targetServings = targetServings,
    board = board,
    preferredCuisines = preferredCuisines,
    todayDayIndex = safeToday,
  )
  val rescueSavingsSuffix = featuredCard?.matchSummary
    ?.takeIf { it.isZeroFoodWasteMatch && it.rescuedValueUsd > 0.0 }
    ?.let { " (rescuing ${activeCurrency.formatAmount(it.rescuedValueUsd)} in expiring stock)" }
    .orEmpty()

  // Direct read-only board lookup ("Hi what's for dinner??") surfaces the scheduled dish without swap noise.
  if (isCurrentMealPlanLookup(userPrompt) && featuredCard != null) {
    val slotWord = (featuredCard.preferredSlotType ?: MealSlotType.DINNER).name.lowercase()
    return AiMenuOptimizationProposal(
      proposalId = "ai_lookup_${focusTag.name.lowercase()}_${board.boardId}",
      focusTag = focusTag,
      assistantSummary = "Scheduled for $slotWord today: ${featuredCard.recipe.title} " +
        "(${featuredCard.recipe.calibratedActiveMinutes} min active, ${featuredCard.matchSummary.matchPercentage}% pantry match)$rescueSavingsSuffix.",
      slotSwaps = emptyList(),
      newIngredientSuggestions = emptyList(),
      isConversationalOnly = false,
      followUpPrompts = listOf("Swap tonight's dinner", "Rescue expiring items", "Check Smart Grocery List"),
      featuredRecipeCard = featuredCard,
    )
  }

  return AiMenuOptimizationProposal(
    proposalId = "ai_prop_${focusTag.name.lowercase()}_${board.boardId}",
    focusTag = focusTag,
    assistantSummary = "Optimized ${slotSwaps.size} meal slot(s) using your in-stock pantry ($topInStockSummary) " +
      "for Choline, Omega-3 DHA, Antioxidants & Iron, plus ${newIngredientSuggestions.size} brain-boosting grocery additions$rescueSavingsSuffix.",
    slotSwaps = slotSwaps,
    newIngredientSuggestions = newIngredientSuggestions,
    isConversationalOnly = false,
    followUpPrompts = listOf("Quick morning under 20m", "High-iron & Vitamin C meals", "Post-workout high protein"),
    featuredRecipeCard = featuredCard,
  )
}

/**
 * Synthesizes a 4-member 1-pot personalized plating map (`Fred`, `Wife`, `8yo`, `Youngest`) from
 * [recipe], adapting dynamically to [memberGoals], [youngestStage], and [activeLifeEvents].
 *
 * @param recipe Target 1-pot family recipe.
 * @param catalog Optional ingredient catalog resolving display names.
 * @param memberGoals Household member nutrition goals (defaults to Family of 4 when empty).
 * @param activeLifeEvents Active life or health events (pregnancy, postpartum, guests, iron).
 * @param youngestStage Developmental growth stage for the youngest child.
 * @return Immutable map from `memberId` to personalized plating instructions.
 */
fun buildFamilyGoalPlatingGuide(
  recipe: Recipe,
  catalog: List<IngredientCatalogItem> = emptyList(),
  memberGoals: List<FamilyMemberGoalSpec> = emptyList(),
  activeLifeEvents: List<HouseholdLifeEvent> = emptyList(),
  youngestStage: ChildGrowthStage = ChildGrowthStage.TODDLER_1_2Y,
): Map<String, String> {
  if (recipe.familyGoalPlating.isNotEmpty() && memberGoals.isEmpty() && activeLifeEvents.isEmpty()) {
    return recipe.familyGoalPlating
  }

  val catalogById = indexCatalogById(catalog)
  val ingredientNames = recipe.bomLines.map { catalogById[it.ingredientId]?.displayName ?: it.ingredientId }
  val combinedLower = "${recipe.title} ${ingredientNames.joinToString(" ")}".lowercase()

  // Infer dominant protein label from BOM display names so plating guidance matches the dish.
  val proteinSource = when {
    combinedLower.contains("turkey") -> "lean turkey"
    Regex("shrimp|prawn|hipon").containsMatchIn(combinedLower) -> "lean shrimp & seafood"
    Regex("tofu|tokwa").containsMatchIn(combinedLower) -> "plant-protein tofu"
    Regex("fish|salmon|tilapia|bangus|tuna|sardine").containsMatchIn(combinedLower) -> "Omega-3 fish"
    Regex("chicken|poulet").containsMatchIn(combinedLower) -> "chicken"
    combinedLower.contains("egg") -> "farm eggs"
    Regex("bean|monggo|lentil|atassi|egusi").containsMatchIn(combinedLower) -> "beans & seeds"
    else -> "complete protein"
  }

  val activeMembers = memberGoals.ifEmpty { HouseholdProfile.defaultFamilyMembers(youngestStage) }
  val guide = linkedMapOf<String, String>()

  // Tailor 1-pot portion ratios per member goal (satiety greens vs +1.5x hypertrophy vs kid textures).
  for (member in activeMembers) {
    val goalLower = member.focusGoal.lowercase()
    val isChild = member.memberId == "YOUNGEST" || member.memberId == "OLDER_KID" ||
      member.growthStage != null || Regex("kid|child|toddler|baby|school").containsMatchIn(goalLower)

    val baseLine = when {
      member.memberId == "YOUNGEST" || member.growthStage != null -> {
        // Propagate explicit household youngestStage when YOUNGEST still carries the default stage.
        val stage = when {
          member.memberId == "YOUNGEST" &&
            member.growthStage == ChildGrowthStage.TODDLER_1_2Y &&
            youngestStage != ChildGrowthStage.TODDLER_1_2Y -> youngestStage
          else -> member.growthStage ?: youngestStage
        }
        val safetyCut = stage.formatPrepTip(recipe.toddlerPrepTip ?: "Serve in soft bite-sized pieces.") ?: stage.prepGuidance
        "${member.memberName} (${member.focusGoal}): $proteinSource + healthy fats & growth carbs ($safetyCut)."
      }
      Regex("weight\\s*loss|lean|satiety|light|trim|low[-\\s]*cal").containsMatchIn(goalLower) ->
        "${member.memberName} (${member.focusGoal}): Full $proteinSource over 2x leafy greens/veggies with 1/2 starch & light oil."
      !isChild && Regex("muscle|hypertrophy|workout|strength|protein").containsMatchIn(goalLower) ->
        "${member.memberName} (${member.focusGoal}): +1.5x $proteinSource portion + complex recovery carbs."
      Regex("brain|memory|choline|dha|school|growth").containsMatchIn(goalLower) -> {
        val safeNote = recipe.toddlerPrepTip ?: "Serve full brain-boosting kid portion."
        "${member.memberName} (${member.focusGoal}): $proteinSource + healthy fats & growth carbs ($safeNote)."
      }
      else -> "${member.memberName} (${member.focusGoal}): Balanced portion of $proteinSource with whole-food produce & grains."
    }

    val matchingEvents = activeLifeEvents.filter { ev ->
      ev.memberId.equals(member.memberId, ignoreCase = true) || ev.memberId.equals("HOUSEHOLD", ignoreCase = true)
    }
    guide[member.memberId] = when {
      matchingEvents.isEmpty() -> baseLine
      else -> "$baseLine | ${matchingEvents.joinToString(" | ") { buildLifeEventSafetyNote(it, combinedLower) }}"
    }
  }

  return guide
}

/**
 * Creates a validated custom household [Recipe] strictly from locally available catalog
 * ingredients, enforcing calibrated morning math (`EC-01`), child safety callouts (`EC-08`), and
 * 4-member 1-pot plating.
 *
 * @param recipeId Unique identifier for the custom recipe.
 * @param title Household meal title.
 * @param allowedSlots Allowed [MealSlotType]s (`BREAKFAST` enforces `<= 25m` calibrated active cap).
 * @param rawPrepMinutes Hands-on prep time in minutes.
 * @param cookMinutes Active cook time in minutes.
 * @param bomLines Selected local-market BOM lines (must contain at least 1 locally available item).
 * @param catalog Household ingredient catalog verifying `isLocallyAvailable`.
 * @param nightBeforePrepMinutes Optional night-before shift (`0..5` minutes).
 * @param toddlerPrepTip Optional custom child-safety cut/texture tip.
 * @param householdProfile Optional household profile for 4-way plating synthesis.
 * @return Validated custom [Recipe].
 */
fun createCustomFamilyRecipe(
  recipeId: String,
  title: String,
  allowedSlots: List<MealSlotType>,
  rawPrepMinutes: Int,
  cookMinutes: Int,
  bomLines: List<BomLine>,
  catalog: List<IngredientCatalogItem>,
  nightBeforePrepMinutes: Int = 0,
  toddlerPrepTip: String? = null,
  householdProfile: HouseholdProfile? = null,
): Recipe {
  val cleanTitle = title.trim()
  requireDomainValid(cleanTitle.isNotEmpty(), "CustomRecipe") { "title must be non-blank" }
  requireDomainValid(allowedSlots.isNotEmpty(), "CustomRecipe") { "allowedSlots must not be empty" }
  requireDomainValid(bomLines.isNotEmpty(), "CustomRecipe") { "bomLines must contain at least 1 item" }

  // Enforce EC-02 local market gate on every custom BOM line.
  val catalogById = indexCatalogById(catalog)
  for (line in bomLines) {
    val item = catalogById[line.ingredientId]
    if (item == null || !item.isLocallyAvailable) {
      throw IngredientUnavailableException(ingredientId = line.ingredientId, recipeId = recipeId)
    }
  }

  // Enforce EC-01 calibrated <= 25m morning cap whenever BREAKFAST is included.
  val calibratedMinutes = computeCalibratedMorningMinutes(rawPrepMinutes, nightBeforePrepMinutes, cookMinutes)
  requireDomainValid(MealSlotType.BREAKFAST !in allowedSlots || calibratedMinutes <= MAX_WEEKDAY_BREAKFAST_MINUTES, "CustomRecipe") {
    "Custom breakfast '$cleanTitle' takes ${calibratedMinutes}m (> ${MAX_WEEKDAY_BREAKFAST_MINUTES}m cap)"
  }

  val firstBom = bomLines.first()
  val extraQty = max(1.0, (firstBom.baseQuantity * 0.5).roundToInt().toDouble())
  val provisional = Recipe(
    recipeId = recipeId.trim(),
    title = cleanTitle,
    sourceAttribution = "Custom Family Kitchen Recipe (Local Market)",
    allowedSlots = allowedSlots,
    preferredOwner = if (MealSlotType.BREAKFAST in allowedSlots) CookOwner.FRED else CookOwner.WIFE,
    baseServings = householdProfile?.baseHouseholdServings ?: 4,
    rawPrepMinutes = rawPrepMinutes,
    nightBeforePrepMinutes = nightBeforePrepMinutes,
    cookMinutes = cookMinutes,
    calibratedActiveMinutes = calibratedMinutes,
    morningSteps = listOf(
      MorningStep(1, "Prep and cook $cleanTitle using fresh local ingredients ($cookMinutes min).", max(180, cookMinutes * 60)),
    ),
    toddlerPrepTip = toddlerPrepTip?.trim()?.takeIf { it.isNotEmpty() }
      ?: "Cut into soft, bite-sized portions for the 2yo and serve with protein & healthy fats.",
    bomLines = bomLines,
    workoutModifiers = listOf(
      WorkoutBoostModifier(
        WorkoutTiming.POST_WORKOUT_HIGH_PROTEIN,
        "Dad's Muscle Boost: Add +1.5x lean protein portion to Fred's plate.",
        listOf(firstBom.copy(baseQuantity = extraQty)),
      ),
    ),
  )

  val plating = buildFamilyGoalPlatingGuide(
    provisional, catalog, householdProfile?.members.orEmpty(),
    householdProfile?.activeLifeEvents.orEmpty(), householdProfile?.youngestStage ?: ChildGrowthStage.TODDLER_1_2Y,
  )
  return SchemaValidator.validateRecipe(provisional.copy(familyGoalPlating = plating), catalogById)
}

/**
 * Creates a custom family recipe and schedules it onto [board] for either a single [targetDayIndex]
 * (`0..6`) or all `Monday–Friday` (`0..4`) weekday slots in one atomic batch.
 *
 * @param board Current 7-day weekly board.
 * @param existingRecipes Existing household recipe library.
 * @param catalog Household ingredient catalog.
 * @param recipeId Unique ID for the new custom recipe.
 * @param title Custom meal title.
 * @param targetSlotType Target meal slot (`BREAKFAST`, `LUNCH`, or `DINNER`).
 * @param targetDayIndex Target weekday (`0..6`) when [applyMondayThroughFriday] is `false`.
 * @param applyMondayThroughFriday Schedules across all 5 weekdays (`Mon`–`Fri`) when `true`.
 * @param rawPrepMinutes Hands-on prep minutes.
 * @param cookMinutes Active cook minutes.
 * @param bomLines Selected local-market BOM lines.
 * @param queueIngredientsToGrocery Queues BOM `ingredientId`s onto Smart Grocery when `true`.
 * @param existingQueuedGroceryIds Existing manual grocery queue set.
 * @param householdProfile Optional household profile for servings and 4-way plating.
 * @return Atomic [CustomFamilyMealBatchResult].
 */
fun createAndScheduleCustomFamilyMealBatch(
  board: WeeklyBoard,
  existingRecipes: List<Recipe>,
  catalog: List<IngredientCatalogItem>,
  recipeId: String,
  title: String,
  targetSlotType: MealSlotType,
  targetDayIndex: Int = 0,
  applyMondayThroughFriday: Boolean = false,
  rawPrepMinutes: Int,
  cookMinutes: Int,
  bomLines: List<BomLine>,
  queueIngredientsToGrocery: Boolean = false,
  existingQueuedGroceryIds: Set<String> = emptySet(),
  householdProfile: HouseholdProfile? = null,
): CustomFamilyMealBatchResult {
  val customRecipe = createCustomFamilyRecipe(
    recipeId, title, listOf(targetSlotType), rawPrepMinutes, cookMinutes, bomLines, catalog,
    householdProfile = householdProfile,
  )
  // Support both single-day slot assignment and Mon-Fri (0..4) weekday batch assignment.
  val targetDays = if (applyMondayThroughFriday) (0..4).toSet() else setOf(targetDayIndex.coerceIn(0, 6))
  val updatedSlots = board.slots.map { slot ->
    when {
      slot.slotType == targetSlotType && slot.dayIndex in targetDays ->
        slot.copy(recipeId = customRecipe.recipeId, status = MealSlotStatus.PLANNED, isPreppedAhead = false, preppedTimeSavedMinutes = 0)
      else -> slot
    }
  }
  val updatedRecipes = existingRecipes.filterNot { it.recipeId == customRecipe.recipeId } + customRecipe
  val nextQueued = if (queueIngredientsToGrocery) existingQueuedGroceryIds + bomLines.map { it.ingredientId } else existingQueuedGroceryIds
  return CustomFamilyMealBatchResult(customRecipe, board.copy(slots = updatedSlots), updatedRecipes, nextQueued)
}

/**
 * Pure batch reducer applying an [AiMenuOptimizationProposal] to [board], [recipes], and [catalog]
 * in a single `O(N)` pass ready for one `O(1)` storage write.
 *
 * @param board Current 7-day weekly board.
 * @param recipes Current recipe collection.
 * @param catalog Current ingredient catalog.
 * @param proposal Validated [AiMenuOptimizationProposal] to apply.
 * @param applySlotSwaps Applies meal slot swaps to [board] and [recipes] when `true`.
 * @param includeNewIngredients Merges new suggested brain-booster items into [catalog] when `true`.
 * @return Immutable [AppliedAiMenuProposalBatch].
 */
fun applyAiMenuProposalBatch(
  board: WeeklyBoard,
  recipes: List<Recipe>,
  catalog: List<IngredientCatalogItem>,
  proposal: AiMenuOptimizationProposal,
  applySlotSwaps: Boolean = true,
  includeNewIngredients: Boolean = true,
): AppliedAiMenuProposalBatch {
  // Index requested slot swaps once so the 21-slot board updates in a single O(N) pass.
  val swapBySlotId = if (applySlotSwaps) proposal.slotSwaps.associateBy { it.slotId } else emptyMap()
  val updatedBoard = when {
    applySlotSwaps -> board.copy(
      slots = board.slots.map { slot ->
        val swap = swapBySlotId[slot.slotId]
        when (swap) {
          null -> slot
          else -> slot.copy(recipeId = swap.suggestedRecipe.recipeId, status = MealSlotStatus.PLANNED, isPreppedAhead = false, preppedTimeSavedMinutes = 0)
        }
      },
    )
    else -> board
  }

  val recipeMap = LinkedHashMap<String, Recipe>().apply {
    recipes.forEach { put(it.recipeId, it) }
    if (applySlotSwaps) {
      proposal.slotSwaps.forEach { put(it.suggestedRecipe.recipeId, it.suggestedRecipe) }
      proposal.featuredRecipeCard?.recipe?.let { put(it.recipeId, it) }
    }
  }
  val catalogMap = LinkedHashMap<String, IngredientCatalogItem>().apply { catalog.forEach { put(it.ingredientId, it) } }
  val groceryRestockDeltas = mutableListOf<RestockDelta>()
  val queuedIds = linkedSetOf<String>()

  if (includeNewIngredients) {
    for (suggestion in proposal.newIngredientSuggestions) {
      catalogMap[suggestion.item.ingredientId] = suggestion.item
      groceryRestockDeltas.add(suggestion.restockDelta)
      queuedIds.add(suggestion.item.ingredientId)
    }
  }

  return AppliedAiMenuProposalBatch(updatedBoard, recipeMap.values.toList(), catalogMap.values.toList(), groceryRestockDeltas, queuedIds)
}

// Score candidate recipes by multiplying nutrient weights 2x when ingredients are already in stock.
internal fun analyzeRecipeBrainNutrients(
  recipe: Recipe,
  catalogById: Map<String, IngredientCatalogItem>,
  pantryById: Map<String, PantryLedgerEntry>,
  focusTag: NutritionFocusTag,
): BrainNutrientAnalysis {
  val nutrientSet = linkedSetOf<String>()
  val inStockIds = mutableListOf<String>()
  var score = 0

  for (bomLine in recipe.bomLines) {
    val item = catalogById[bomLine.ingredientId]
    val searchTarget = "${bomLine.ingredientId} ${item?.displayName.orEmpty()}".lowercase()
    val entry = pantryById[bomLine.ingredientId]
    val tier = entry?.tier ?: item?.tier ?: IngredientTier.TIER_1_QUANTITATIVE
    val inStock = when (tier) {
      IngredientTier.TIER_2_STAPLE -> entry != null && !entry.isOutOfStock && entry.stapleStatus != StapleStatus.OUT
      IngredientTier.TIER_1_QUANTITATIVE -> entry != null && !entry.isOutOfStock && entry.currentQuantity > 0.0
    }
    if (inStock && bomLine.ingredientId !in inStockIds) {
      inStockIds.add(bomLine.ingredientId)
      score += 3
      // Elevate recipes rescuing EXPIRING_SOON (1..2d) or EXPIRED (<=0d) stock in zero-waste mode.
      if (entry != null) {
        score += when (entry.effectiveExpiryStatus) {
          ExpiryStatus.EXPIRED -> if (focusTag == NutritionFocusTag.PANTRY_ZERO_WASTE) 28 else 16
          ExpiryStatus.EXPIRING_SOON -> if (focusTag == NutritionFocusTag.PANTRY_ZERO_WASTE) 20 else 8
          ExpiryStatus.FRESH -> 0
        }
      }
    }
    for (rule in BRAIN_BOOSTER_RULES) {
      if (rule.keywords.any { searchTarget.contains(it) }) {
        nutrientSet.addAll(rule.nutrients)
        score += if (inStock) rule.scoreWeight * 2 else rule.scoreWeight
      }
    }
  }

  // Apply focus-specific tie-breakers (<20m morning rush bonus, workout modifier bonus, toddler tip).
  if (focusTag == NutritionFocusTag.QUICK_MORNING_UNDER_20M && recipe.calibratedActiveMinutes <= 20) score += 12
  // Reward recipes carrying Dad's pre/post-workout boosts when athletic recovery is requested.
  if (focusTag == NutritionFocusTag.HIGH_PROTEIN_FAMILY && recipe.workoutModifiers.isNotEmpty()) score += 10
  // Reward explicit toddler choking-safety instructions on family meals.
  if (!recipe.toddlerPrepTip.isNullOrBlank()) score += 4
  // Guarantee at least one micronutrient badge so UI swap cards never render an empty tag list.
  if (nutrientSet.isEmpty()) nutrientSet.add("Balanced Family Macronutrients & Choline Support")
  return BrainNutrientAnalysis(nutrientSet.toList(), inStockIds, score)
}

// Synthesize recipe-specific food-safety temperatures (firm yolks, 74C deli/poultry, 63C fish) for pregnancy.
private fun buildLifeEventSafetyNote(event: HouseholdLifeEvent, recipeTextLower: String): String {
  val isPregnancy = Regex("pregnan|trimester", RegexOption.IGNORE_CASE).containsMatchIn(event.title)
  val dynamicGuards = mutableListOf<String>()
  if (isPregnancy) {
    if (recipeTextLower.contains("egg")) dynamicGuards.add("cook egg yolks & whites completely firm (no runny yolks)")
    if (recipeTextLower.contains("turkey") || recipeTextLower.contains("chicken")) {
      dynamicGuards.add("heat poultry/deli meat steaming hot (74C)")
    }
    if (Regex("fish|tilapia|salmon|shrimp|tuna").containsMatchIn(recipeTextLower)) {
      dynamicGuards.add("cook low-mercury seafood to 63C opaque flake")
    }
  }
  val allRules = (dynamicGuards + event.safetyPrepRules).distinct()
  val safetyClause = if (allRules.isNotEmpty()) " [Safety: ${allRules.joinToString("; ")}]" else ""
  val nutrientsClause = event.priorityNutrients.joinToString(", ").ifEmpty { "Targeted household support" }
  return "${event.title} -> Focus on $nutrientsClause$safetyClause"
}

private fun isPurelyConversationalPrompt(promptLower: String): Boolean {
  val greeting = Regex("^(hi|hello|hey|bonjour|good morning|good evening)[!?.\\s]*$").matches(promptLower)
  val audit = Regex("(what('s|\\s+is)\\s+in\\s+(my|our)\\s+(pantry|fridge)|list\\s+(my|our)\\s+pantry)").containsMatchIn(promptLower)
  return greeting || audit
}

// Synthesize an interactive AiRichRecipeCard grounded in scheduled meals, slot type, cuisines, & rescues.
internal fun synthesizeFeaturedAiRecipeCard(
  userPrompt: String,
  focusTag: NutritionFocusTag,
  slotSwaps: List<AiSlotSwapSuggestion>,
  candidateRecipes: List<Recipe>,
  catalog: List<IngredientCatalogItem>,
  pantry: List<PantryLedgerEntry>,
  effectiveAllergens: List<String>,
  targetServings: Int = 4,
  board: WeeklyBoard? = null,
  preferredCuisines: List<String> = emptyList(),
  todayDayIndex: Int = 0,
): AiRichRecipeCard? {
  val lowerPrompt = userPrompt.trim().lowercase()
  val requestedSlot = inferRequestedMealSlotType(userPrompt)
  val safeToday = ((todayDayIndex % 7) + 7) % 7
  val pool = (slotSwaps.map { it.suggestedRecipe } + candidateRecipes).distinctBy { it.recipeId }
  if (pool.isEmpty()) return null

  // 1. Read-only meal plan lookup ("Hi what's for dinner??") features the exact scheduled slot dish!
  if (isCurrentMealPlanLookup(userPrompt) && board != null) {
    val targetSlot = requestedSlot ?: MealSlotType.DINNER
    val scheduledSlot = board.slots.firstOrNull {
      it.dayIndex == safeToday && it.slotType == targetSlot && it.status != MealSlotStatus.COMPLETED_SKIPPED
    }
    val scheduledRecipe = scheduledSlot?.let { slot -> pool.firstOrNull { it.recipeId == slot.recipeId } }
    if (scheduledRecipe != null) {
      val matchSummary = evaluateRecipePantryMatch(scheduledRecipe, pantry, catalog, targetServings, effectiveAllergens)
      val tip = buildCulinarySubstitutionTip(scheduledRecipe, lowerPrompt, catalog)
      return AiRichRecipeCard(
        recipe = scheduledRecipe,
        matchSummary = matchSummary,
        substitutionTip = tip,
        preferredSlotType = targetSlot.takeIf { it in scheduledRecipe.allowedSlots },
      )
    }
  }

  // 2. Trigger expiry-rescue sorting strictly on explicit zero-waste / spoilage terms (never bare "tonight").
  val prefersExpiryRescue = focusTag == NutritionFocusTag.PANTRY_ZERO_WASTE ||
    Regex("expir|waste|2\\s*days|perishable|rescue|use\\s+up|spoil").containsMatchIn(lowerPrompt)

  val evaluatedPool = pool.map { recipe ->
    recipe to evaluateRecipePantryMatch(recipe, pantry, catalog, targetServings, effectiveAllergens)
  }
  // 3. Constrain candidates to requestedSlot (and <=25m EC-01 cap when BREAKFAST), falling back only if empty.
  val slotFilteredPool = when (requestedSlot) {
    null -> evaluatedPool
    else -> evaluatedPool.filter { (recipe, _) ->
      requestedSlot in recipe.allowedSlots &&
        (requestedSlot != MealSlotType.BREAKFAST || computeCalibratedMorningMinutes(recipe) <= MAX_WEEKDAY_BREAKFAST_MINUTES)
    }.ifEmpty { evaluatedPool }
  }

  val cuisineAndMatchComparator =
    compareByDescending<Pair<Recipe, RecipePantryMatchSummary>> {
      preferredCuisines.isNotEmpty() && doesRecipeMatchPreferredCuisines(it.first, preferredCuisines)
    }
      .thenByDescending { it.second.matchPercentage }
      .thenByDescending { it.second.rescuedExpiringIngredientIds.size }

  val selectedPair = when {
    prefersExpiryRescue ->
      slotFilteredPool.sortedWith(
        compareByDescending<Pair<Recipe, RecipePantryMatchSummary>> { it.second.rescuedExpiringIngredientIds.size }
          .thenByDescending { it.second.rescuedValueUsd }
          .thenByDescending { preferredCuisines.isNotEmpty() && doesRecipeMatchPreferredCuisines(it.first, preferredCuisines) }
          .thenByDescending { it.second.matchPercentage },
      ).firstOrNull()
    requestedSlot != null ->
      slotSwaps.firstOrNull { it.slotType == requestedSlot }?.let { matchingSwap ->
        slotFilteredPool.find { it.first.recipeId == matchingSwap.suggestedRecipe.recipeId }
      } ?: slotFilteredPool.sortedWith(cuisineAndMatchComparator).firstOrNull()
    else ->
      slotSwaps.firstOrNull()?.let { topSwap ->
        slotFilteredPool.find { it.first.recipeId == topSwap.suggestedRecipe.recipeId }
      } ?: slotFilteredPool.sortedWith(cuisineAndMatchComparator).firstOrNull()
  } ?: return null

  val (featuredRecipe, matchSummary) = selectedPair
  val tip = buildCulinarySubstitutionTip(featuredRecipe, lowerPrompt, catalog)
  return AiRichRecipeCard(
    recipe = featuredRecipe,
    matchSummary = matchSummary,
    substitutionTip = tip,
    preferredSlotType = requestedSlot?.takeIf { it in featuredRecipe.allowedSlots },
  )
}

private fun buildCulinarySubstitutionTip(
  recipe: Recipe,
  promptLower: String,
  catalog: List<IngredientCatalogItem>,
): String? {
  val catalogById = indexCatalogById(catalog)
  // Compute exact calorie/protein delta when Greek yogurt can replace butter/cream in this dish.
  val hasButter = recipe.bomLines.any { it.ingredientId == "ing_butter" }
  val yogurtAvailable = catalogById["ing_greek_yogurt"]?.isLocallyAvailable == true
  if (Regex("cream|yogurt|butter|substitut|swap|lighter|calorie").containsMatchIn(promptLower) || (hasButter && yogurtAvailable)) {
    if (hasButter && yogurtAvailable) {
      val swapped = swapRecipeIngredient(recipe, "ing_butter", "ing_greek_yogurt", catalog)
      val kcalSaved = recipe.caloriesPerServing - swapped.caloriesPerServing
      val proteinGain = max(1, swapped.proteinGrams - recipe.proteinGrams)
      val deltaText = if (kcalSaved > 0) "saves ~$kcalSaved kcal/serving and adds +${proteinGain}g protein" else "keeps richness at ${swapped.caloriesPerServing} kcal/serving"
      return "Sous-Chef Rule: Swap Unsalted Creamery Butter 1:1 off-heat with Plain Greek Yogurt—$deltaText."
    }
    return "Sous-Chef Rule: Replace heavy cream 1:1 with Plain Greek Yogurt tempered off-heat to save ~110 kcal/serving and add +8g protein without curdling."
  }

  val subBom = recipe.bomLines.firstOrNull { !it.substituteGroupId.isNullOrBlank() }
  if (subBom != null) {
    val group = subBom.substituteGroupId.orEmpty()
    val alt = catalog.firstOrNull { group in it.substituteGroupIds && it.ingredientId != subBom.ingredientId && it.isLocallyAvailable }
    if (alt != null) {
      val swapped = swapRecipeIngredient(recipe, subBom.ingredientId, alt.ingredientId, catalog)
      val origName = catalogById[subBom.ingredientId]?.displayName ?: subBom.ingredientId
      return "Sous-Chef Rule: Swap $origName 1:1 with ${alt.displayName} (${swapped.caloriesPerServing} kcal/serving)."
    }
  }
  return recipe.chefNote?.takeIf { it.isNotBlank() }?.let { "Sous-Chef Rule: $it" }
}

/** Object namespace exposing AI Chef domain advisor functions for qualified `AiMenuAdvisorEngine.*` calls. */
object AiMenuAdvisorEngine {
  // Infers KIDS_BRAIN_BOOSTER, PANTRY_ZERO_WASTE, QUICK_MORNING_UNDER_20M, IRON, or HIGH_PROTEIN.
  /** Delegates to [com.familykitchen.board.domain.inferNutritionFocusFromPrompt]. */
  fun inferNutritionFocusFromPrompt(userPrompt: String): NutritionFocusTag =
    com.familykitchen.board.domain.inferNutritionFocusFromPrompt(userPrompt)

  // Builds single-pass pantry-aware system prompt for LiteRT-LM / Gemma / Cloud LLM text turns.
  /** Delegates to [com.familykitchen.board.domain.buildPantryAwareSystemPrompt]. */
  fun buildPantryAwareSystemPrompt(
    userPrompt: String, board: WeeklyBoard, recipes: List<Recipe>,
    catalog: List<IngredientCatalogItem>, pantry: List<PantryLedgerEntry>,
    householdAllergens: List<String> = emptyList(), householdProfile: HouseholdProfile? = null,
    deviceProfile: EdgeDeviceProfile = EdgeDeviceProfile.BALANCED_GEMMA_2B_INT4,
    recentTurns: List<AiChatTurn> = emptyList(), todayDayIndex: Int = 0, currentHour24: Int? = null,
  ): String = com.familykitchen.board.domain.buildPantryAwareSystemPrompt(
    userPrompt, board, recipes, catalog, pantry, householdAllergens,
    householdProfile, deviceProfile, recentTurns, todayDayIndex, currentHour24,
  )

  // Builds authentic neutral multimodal vision prompt (zero hardcoded fake grocery items).
  /** Delegates to [com.familykitchen.board.domain.buildVisualIntakePrompt]. */
  fun buildVisualIntakePrompt(
    userNote: String = "", catalog: List<IngredientCatalogItem> = emptyList(),
    youngestStage: ChildGrowthStage = ChildGrowthStage.TODDLER_1_2Y,
  ): String = com.familykitchen.board.domain.buildVisualIntakePrompt(userNote, catalog, youngestStage)

  // Strips <pad>, <unk>, <bos>, <eos>, <start_of_turn>, <end_of_turn>, and <unusedN> tokens.
  /** Delegates to [com.familykitchen.board.domain.stripGemmaControlTokens]. */
  fun stripGemmaControlTokens(rawModelOutput: String): String =
    com.familykitchen.board.domain.stripGemmaControlTokens(rawModelOutput)

  // Grounds untrusted LLM JSON against real board slotIds, recipeIds, and <= 25m morning cap.
  /** Delegates to [com.familykitchen.board.domain.parseAndGroundAiResponse]. */
  fun parseAndGroundAiResponse(
    rawModelOutput: String, userPrompt: String = "", board: WeeklyBoard, recipes: List<Recipe>,
    catalog: List<IngredientCatalogItem>, pantry: List<PantryLedgerEntry>,
    householdProfile: HouseholdProfile = HouseholdProfile(), todayDayIndex: Int = 0,
  ): AiMenuOptimizationProposal = com.familykitchen.board.domain.parseAndGroundAiResponse(
    rawModelOutput, userPrompt, board, recipes, catalog, pantry, householdProfile, todayDayIndex,
  )

  // Grounds strict JSON, lenient JSON summaries, and natural plain-text LLM replies seamlessly.
  /** Delegates to [com.familykitchen.board.domain.groundFlexibleLlmTextReply]. */
  fun groundFlexibleLlmTextReply(
    rawModelOutput: String, userPrompt: String, board: WeeklyBoard, recipes: List<Recipe>,
    catalog: List<IngredientCatalogItem>, pantry: List<PantryLedgerEntry>,
    householdProfile: HouseholdProfile = HouseholdProfile(), todayDayIndex: Int = 0,
  ): AiMenuOptimizationProposal = com.familykitchen.board.domain.groundFlexibleLlmTextReply(
    rawModelOutput, userPrompt, board, recipes, catalog, pantry, householdProfile, todayDayIndex,
  )

  // Parses multimodal vision JSON and grounds detected items to verified catalog RestockDeltas.
  /** Delegates to [com.familykitchen.board.domain.parseVisualIntakeJson]. */
  fun parseVisualIntakeJson(rawModelOutput: String, catalog: List<IngredientCatalogItem>): VisualIntakeResult =
    com.familykitchen.board.domain.parseVisualIntakeJson(rawModelOutput, catalog)

  // Routes explicit text-only commands to deterministic tools (never runs when hasAttachedImage).
  /** Delegates to [com.familykitchen.board.domain.tryExecuteDeterministicToolIntent]. */
  fun tryExecuteDeterministicToolIntent(
    userPrompt: String, hasAttachedImage: Boolean = false, catalog: List<IngredientCatalogItem>,
    recipes: List<Recipe> = emptyList(), board: WeeklyBoard? = null,
    pantry: List<PantryLedgerEntry> = emptyList(), householdAllergens: List<String> = emptyList(),
    activeDayIndex: Int = 0,
  ): AiToolCallResult? = com.familykitchen.board.domain.tryExecuteDeterministicToolIntent(
    userPrompt, hasAttachedImage, catalog, recipes, board, pantry, householdAllergens, activeDayIndex,
  )

  // Generates 100% offline pantry-grounded menu swaps targeting todayDayIndex and preferred cuisines.
  /** Delegates to [com.familykitchen.board.domain.generatePantryGroundedMenuProposal]. */
  fun generatePantryGroundedMenuProposal(
    userPrompt: String, board: WeeklyBoard, recipes: List<Recipe>,
    catalog: List<IngredientCatalogItem>, pantry: List<PantryLedgerEntry>,
    householdAllergens: List<String> = emptyList(), householdProfile: HouseholdProfile? = null,
    todayDayIndex: Int = 0,
  ): AiMenuOptimizationProposal = com.familykitchen.board.domain.generatePantryGroundedMenuProposal(
    userPrompt, board, recipes, catalog, pantry, householdAllergens, householdProfile, todayDayIndex,
  )

  // Synthesizes 4-member 1-pot plating guide with pregnancy/guest life-event safety guardrails.
  /** Delegates to [com.familykitchen.board.domain.buildFamilyGoalPlatingGuide]. */
  fun buildFamilyGoalPlatingGuide(
    recipe: Recipe, catalog: List<IngredientCatalogItem> = emptyList(),
    memberGoals: List<FamilyMemberGoalSpec> = emptyList(),
    activeLifeEvents: List<HouseholdLifeEvent> = emptyList(),
    youngestStage: ChildGrowthStage = ChildGrowthStage.TODDLER_1_2Y,
  ): Map<String, String> = com.familykitchen.board.domain.buildFamilyGoalPlatingGuide(
    recipe, catalog, memberGoals, activeLifeEvents, youngestStage,
  )

  // Builds a custom recipe from locally available catalog items enforcing <= 25m Breakfast cap.
  /** Delegates to [com.familykitchen.board.domain.createCustomFamilyRecipe]. */
  fun createCustomFamilyRecipe(
    recipeId: String, title: String, allowedSlots: List<MealSlotType>,
    rawPrepMinutes: Int, cookMinutes: Int, bomLines: List<BomLine>,
    catalog: List<IngredientCatalogItem>, nightBeforePrepMinutes: Int = 0,
    toddlerPrepTip: String? = null, householdProfile: HouseholdProfile? = null,
  ): Recipe = com.familykitchen.board.domain.createCustomFamilyRecipe(
    recipeId, title, allowedSlots, rawPrepMinutes, cookMinutes, bomLines, catalog,
    nightBeforePrepMinutes, toddlerPrepTip, householdProfile,
  )

  // Schedules a custom household meal onto a single day or all Mon-Fri (0..4) slots atomically.
  /** Delegates to [com.familykitchen.board.domain.createAndScheduleCustomFamilyMealBatch]. */
  fun createAndScheduleCustomFamilyMealBatch(
    board: WeeklyBoard, existingRecipes: List<Recipe>, catalog: List<IngredientCatalogItem>,
    recipeId: String, title: String, targetSlotType: MealSlotType,
    targetDayIndex: Int = 0, applyMondayThroughFriday: Boolean = false,
    rawPrepMinutes: Int, cookMinutes: Int, bomLines: List<BomLine>,
    queueIngredientsToGrocery: Boolean = false, existingQueuedGroceryIds: Set<String> = emptySet(),
    householdProfile: HouseholdProfile? = null,
  ): CustomFamilyMealBatchResult = com.familykitchen.board.domain.createAndScheduleCustomFamilyMealBatch(
    board, existingRecipes, catalog, recipeId, title, targetSlotType, targetDayIndex,
    applyMondayThroughFriday, rawPrepMinutes, cookMinutes, bomLines, queueIngredientsToGrocery,
    existingQueuedGroceryIds, householdProfile,
  )

  // Applies AI meal swaps and suggested catalog ingredients in 1 O(N) pass for O(1) persistence.
  /** Delegates to [com.familykitchen.board.domain.applyAiMenuProposalBatch]. */
  fun applyAiMenuProposalBatch(
    board: WeeklyBoard, recipes: List<Recipe>, catalog: List<IngredientCatalogItem>,
    proposal: AiMenuOptimizationProposal, applySlotSwaps: Boolean = true,
    includeNewIngredients: Boolean = true,
  ): AppliedAiMenuProposalBatch = com.familykitchen.board.domain.applyAiMenuProposalBatch(
    board, recipes, catalog, proposal, applySlotSwaps, includeNewIngredients,
  )
}
