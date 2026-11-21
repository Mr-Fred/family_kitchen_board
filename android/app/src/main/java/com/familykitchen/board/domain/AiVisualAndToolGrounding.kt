package com.familykitchen.board.domain

import kotlin.math.max
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

// Pure prompt synthesis, Gemma control-token sanitization, untrusted LLM JSON grounding,
// authentic multimodal visual intake parsing, and text-only deterministic tool routing.
// Zero Android, JNI, LiteRT, MediaPipe, or java.io imports are permitted inside domain/.

/**
 * Builds a single compact pantry-grounded system prompt for on-device LiteRT-LM / Gemma or Cloud LLM inference.
 *
 * @param userPrompt Natural-language request from the parent.
 * @param board Active 7-day [WeeklyBoard].
 * @param recipes Household recipe library.
 * @param catalog Household ingredient catalog.
 * @param pantry Current pantry ledger entries.
 * @param householdAllergens Active allergen exclusions.
 * @param householdProfile Optional full household profile (goals, growth stage, life events).
 * @param deviceProfile Active universal Android RAM-tier / Gemma hardware profile.
 * @param recentTurns Optional recent conversation turns (`<= 4`) providing multi-turn continuity.
 * @return Grounded single-pass prompt string.
 */
fun buildPantryAwareSystemPrompt(
  userPrompt: String,
  board: WeeklyBoard,
  recipes: List<Recipe>,
  catalog: List<IngredientCatalogItem>,
  pantry: List<PantryLedgerEntry>,
  householdAllergens: List<String> = emptyList(),
  householdProfile: HouseholdProfile? = null,
  deviceProfile: EdgeDeviceProfile = EdgeDeviceProfile.BALANCED_GEMMA_2B_INT4,
  recentTurns: List<AiChatTurn> = emptyList(),
): String {
  val focusTag = inferNutritionFocusFromPrompt(userPrompt)
  val catalogById = indexCatalogById(catalog)
  val effectiveAllergens = householdAllergens.ifEmpty { householdProfile?.householdAllergens.orEmpty() }

  // Serialize Tier-1 stock and Tier-2 statuses in one pass to eliminate N+1 chat roundtrips.
  val inStockTier1 = mutableListOf<String>()
  val tier2Statuses = mutableListOf<String>()
  for (entry in pantry) {
    val item = catalogById[entry.ingredientId]
    val label = item?.displayName ?: entry.ingredientId
    when {
      entry.tier == IngredientTier.TIER_2_STAPLE -> tier2Statuses.add("$label:${entry.stapleStatus.name}")
      !entry.isOutOfStock && entry.currentQuantity > 0.0 ->
        inStockTier1.add("$label (${entry.currentQuantity.toInt()} ${item?.baseUnit?.wireCode ?: "unit"})")
    }
  }

  // Embed active child growth stage, life-event safety temperatures, currency, and valid IDs.
  val youngestStage = householdProfile?.youngestStage ?: ChildGrowthStage.TODDLER_1_2Y
  val activeCurrency = householdProfile?.currency ?: HouseholdCurrency.PHP
  val marketRegion = householdProfile?.marketRegionLabel ?: "Antipolo & Marikina, PH"
  val lifeEventSummary = householdProfile?.activeLifeEvents?.takeIf { it.isNotEmpty() }
    ?.joinToString(" | ") { "${it.title} [Safety: ${it.safetyPrepRules.joinToString("; ")}]" } ?: "NONE"
  val validRecipeIds = recipes.take(30).joinToString(",") { it.recipeId }
  val plannedSlotIds = board.slots.filter { it.status == MealSlotStatus.PLANNED }
    .take(9).joinToString(",") { "${it.slotId}:${it.slotType.name}" }
  val defaultConv = !isMealOrRecipeProposalRequest(userPrompt)
  val recentContext = formatRecentTurnsContext(recentTurns)
  val responseRule = when {
    defaultConv ->
      "RESPONSE_RULE: Write a warm, specific, culinary-intelligent reply directly answering USER_REQUEST in " +
        "\"assistantSummary\" (referencing real household pantry items or culinary technique when helpful — never " +
        "generic canned filler). For greetings, chat, or cooking Q&A, set \"isConversationalOnly\": true and \"suggestedSwaps\": []."
    else ->
      "RESPONSE_RULE: Write a warm, specific, culinary-intelligent reply directly answering USER_REQUEST in " +
        "\"assistantSummary\" (referencing real stocked items, cook times, and child-safe prep — never generic filler). " +
        "Set \"isConversationalOnly\": false and include 0-3 \"suggestedSwaps\" using ONLY exact IDs from PLANNED_SLOTS and VALID_RECIPE_IDS (never \"...\")."
  }
  val outputFormat = when {
    defaultConv ->
      "OUTPUT_FORMAT: Return ONLY strict JSON {\"focusTag\":\"${focusTag.name}\",\"assistantSummary\":\"<your helpful response>\"," +
        "\"isConversationalOnly\":true,\"suggestedSwaps\":[]}"
    else ->
      "OUTPUT_FORMAT: Return ONLY strict JSON {\"focusTag\":\"${focusTag.name}\",\"assistantSummary\":\"<your culinary response>\"," +
        "\"isConversationalOnly\":false,\"suggestedSwaps\":[{\"slotId\":\"<exact PLANNED_SLOTS id>\",\"recipeId\":\"<exact VALID_RECIPE_IDS id>\",\"rationale\":\"<why>\"}]}"
  }

  return buildList {
    add("ROLE: SavorAI Kitchen & Nutrition Sous-Chef (Household: ${householdProfile?.householdName ?: "Family of 4"}, youngest: ${youngestStage.shortLabel}).")
    add("MARKET_AND_CURRENCY: $marketRegion (${activeCurrency.isoCode} ${activeCurrency.symbol}).")
    add("DEVICE_PROFILE: ${deviceProfile.displayName} (${deviceProfile.preferredDelegate}, ${deviceProfile.quantization}).")
    add("FOCUS_TAG: ${focusTag.name}")
    add(
      "CONSTRAINTS: Weekday BREAKFAST calibratedActiveMinutes <= ${MAX_WEEKDAY_BREAKFAST_MINUTES}m (1.4x buffer); " +
        "non-empty child safety tip for ${youngestStage.shortLabel}; allergens=(${effectiveAllergens.joinToString(",").ifEmpty { "NONE" }}).",
    )
    add("ACTIVE_LIFE_EVENTS: $lifeEventSummary")
    add("IN_STOCK_TIER1_PANTRY (${inStockTier1.size}): ${inStockTier1.joinToString("; ").ifEmpty { "None" }}")
    add("TIER2_STAPLES (${tier2Statuses.size}): ${tier2Statuses.joinToString(", ").ifEmpty { "None" }}")
    add("PLANNED_SLOTS: $plannedSlotIds")
    add("VALID_RECIPE_IDS: $validRecipeIds")
    if (recentContext != null) add("RECENT_CONVERSATION: $recentContext")
    add(responseRule)
    add(outputFormat)
    add("USER_REQUEST: ${userPrompt.trim()}")
  }.joinToString("\n")
}

/**
 * Builds an authentic, neutral multimodal visual inspection prompt for Gemma Vision (`.litertlm`).
 *
 * Contains **zero** hardcoded or assumed grocery names so the vision model inspects only what is
 * genuinely visible in the attached camera/gallery JPEG and grounds matches against [catalog].
 *
 * @param userNote Optional user caption accompanying the attached photo.
 * @param catalog Household ingredient catalog supplying valid `ingredientId` targets.
 * @param youngestStage Youngest child growth stage for texture-safety context.
 * @return Grounded multimodal vision prompt string.
 */
fun buildVisualIntakePrompt(
  userNote: String = "",
  catalog: List<IngredientCatalogItem> = emptyList(),
  youngestStage: ChildGrowthStage = ChildGrowthStage.TODDLER_1_2Y,
): String {
  // List available catalog IDs as passive reference targets without biasing what is in the photo.
  val catalogRef = catalog.filter { it.isLocallyAvailable }
    .joinToString("; ") { "${it.ingredientId}=${it.displayName} (${it.baseUnit.wireCode})" }
  val cleanNote = userNote.trim().ifEmpty {
    "Inspect the attached kitchen, fridge, counter, or receipt photo and identify visible food items."
  }

  // Strictly forbid inventing unseen items while capturing all culinary ingredients, herbs, and spices.
  return listOf(
    "ROLE: Authentic On-Device Kitchen Vision Inspector (Family of 4, youngest stage: ${youngestStage.shortLabel}).",
    "TASK: Inspect ONLY the attached image pixels. Do NOT guess or invent items that are not clearly visible.",
    "DETECTION_RULE: Populate detectedItems for every visible culinary ingredient, spice, herb, produce, protein, dairy, or packaged food (setting \"matchedIngredientId\": null when not in KNOWN_CATALOG_ITEMS). Reserve unrecognizedLabels strictly for non-food household objects.",
    "KNOWN_CATALOG_ITEMS: ${catalogRef.ifEmpty { "Use descriptive rawLabel strings" }}",
    "OUTPUT_SCHEMA: Return ONLY valid JSON matching {\"summary\":\" concise visual summary \"," +
      "\"detectedItems\":[{\"rawLabel\":\"...\",\"matchedIngredientId\":\"ing_... or null\"," +
      "\"estimatedQuantity\":0,\"confidence\":0.9}],\"unrecognizedLabels\":[\"...\"]}",
    "USER_NOTE: $cleanNote",
  ).joinToString("\n")
}

/**
 * Strips Gemma 4 reasoning blocks (`<|thought|>...`), Gemma 2/3/4 turn/control delimiters, and
 * outer JSON Markdown code fences before schema validation.
 *
 * @param rawModelOutput Raw string emitted by LiteRT-LM, MediaPipe `LlmInference`, or Cloud AI.
 * @return Sanitized text ready for strict JSON parsing or UI display.
 */
fun stripGemmaControlTokens(rawModelOutput: String): String =
  sanitizeLlmRawOutput(rawModelOutput)

/**
 * Parses and strictly catalog-grounds an untrusted LLM response into an
 * [AiMenuOptimizationProposal], rejecting malformed JSON, unknown injected keys, hallucinated
 * `recipeId`/`slotId` values, or `> 25m` Breakfast swaps with [ValidationException].
 *
 * @param rawModelOutput Raw string emitted by `AiEdgePort.generateText`.
 * @param userPrompt Original user prompt for focus-tag fallback context.
 * @param board Active 7-day [WeeklyBoard].
 * @param recipes Household recipe library.
 * @param catalog Household ingredient catalog.
 * @param pantry Current household pantry ledger.
 * @param householdProfile Household profile supplying allergens, member goals, and life events.
 * @return Grounded, validated [AiMenuOptimizationProposal].
 */
fun parseAndGroundAiResponse(
  rawModelOutput: String,
  userPrompt: String = "",
  board: WeeklyBoard,
  recipes: List<Recipe>,
  catalog: List<IngredientCatalogItem>,
  pantry: List<PantryLedgerEntry>,
  householdProfile: HouseholdProfile = HouseholdProfile(),
): AiMenuOptimizationProposal {
  val sanitized = stripGemmaControlTokens(rawModelOutput)
  requireDomainValid(sanitized.isNotBlank(), "AiResponse") { "empty model output after stripping tokens" }

  val jsonSlice = extractJsonObjectSlice(sanitized)
  val rootObj = parseJsonObjectStrict("AiResponse", jsonSlice)

  // Full AiMenuOptimizationProposal payload branch (enforces SchemaValidator.strictJson).
  if ("slotSwaps" in rootObj && "proposalId" in rootObj) {
    return SchemaValidator.decodeAiProposalJson(jsonSlice, catalog, recipes, board)
  }

  // Compact on-device Gemma schema branch: reject any unexpected injected keys strictly.
  val allowedKeys = setOf(
    "focusTag", "assistantSummary", "isConversationalOnly",
    "suggestedSwaps", "suggestedIngredientIds", "followUpPrompts",
  )
  val unknownKeys = rootObj.keys - allowedKeys
  if (unknownKeys.isNotEmpty()) {
    throw ValidationException("Invalid compact AI response: unknown keys $unknownKeys", unknownKeys.map { "unknown '$it'" })
  }

  val summary = rootObj["assistantSummary"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
  requireDomainValid(summary.isNotEmpty(), "AiResponse") { "assistantSummary must be non-blank" }

  val focusTag = rootObj["focusTag"]?.jsonPrimitive?.contentOrNull
    ?.let { raw -> NutritionFocusTag.entries.find { it.name == raw.trim().uppercase() } }
    ?: inferNutritionFocusFromPrompt(userPrompt)
  val isConversationalOnly = rootObj["isConversationalOnly"]?.jsonPrimitive?.booleanOrNull ?: false

  val catalogById = indexCatalogById(catalog)
  val pantryById = indexPantryById(pantry)
  val recipeById = recipes.associateBy { it.recipeId }
  val boardSlotById = board.slots.associateBy { it.slotId }
  val allergens = householdProfile.householdAllergens
  val groundedSwaps = mutableListOf<AiSlotSwapSuggestion>()

  // Ground each suggested swap against real board slots, catalog recipes, and <= 25m morning caps.
  val swapsElement = rootObj["suggestedSwaps"]
  if (swapsElement is JsonArray) {
    for ((idx, element) in swapsElement.withIndex()) {
      val swapObj = element as? JsonObject
        ?: throw ValidationException("suggestedSwaps[$idx] must be a JSON object", listOf("suggestedSwaps[$idx]"))
      val slotId = swapObj["slotId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
      val recipeId = swapObj["recipeId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
      val rationale = swapObj["rationale"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        ?: "Pantry-grounded swap selected for family nutrition goals."

      val slot = boardSlotById[slotId]
        ?: throw ValidationException("Ungrounded slotId '$slotId' in AI response", listOf("slotId '$slotId'"))
      val baseRecipe = recipeById[recipeId]
        ?: throw ValidationException("Ungrounded recipeId '$recipeId' in AI response", listOf("recipeId '$recipeId'"))

      val availability = evaluateRecipeAvailability(baseRecipe, catalogById, allergens, pantryById)
      requireDomainValid(availability.isSchedulable, "AiResponse") { "recipe '$recipeId' blocked (${availability.reason})" }
      val calibrated = computeCalibratedMorningMinutes(baseRecipe)
      requireDomainValid(slot.slotType != MealSlotType.BREAKFAST || calibrated <= MAX_WEEKDAY_BREAKFAST_MINUTES, "AiResponse") {
        "BREAKFAST swap '$recipeId' exceeds 25m cap (${calibrated}m)"
      }

      val analysis = analyzeRecipeBrainNutrients(baseRecipe, catalogById, pantryById, focusTag)
      val plating = buildFamilyGoalPlatingGuide(
        baseRecipe, catalog, householdProfile.members, householdProfile.activeLifeEvents, householdProfile.youngestStage,
      )
      val tip = baseRecipe.toddlerPrepTip?.takeIf { it.isNotBlank() }
        ?: "Serve in soft bite-sized portions and crush any seeds finely for 2yo."
      val enriched = baseRecipe.copy(
        bomLines = availability.resolvedBomLines, calibratedActiveMinutes = calibrated,
        toddlerPrepTip = tip, familyGoalPlating = plating,
      )
      groundedSwaps.add(
        AiSlotSwapSuggestion(
          slot.slotId, slot.dayIndex, slot.slotType, slot.recipeId,
          enriched, rationale, analysis.nutrients, analysis.inStockIngredientIds, plating,
        ),
      )
    }
  }

  // Suppress unsolicited generic recipe/grocery cards on pure greetings or Q&A with 0 swaps.
  val effectiveConversationalOnly =
    isConversationalOnly || (groundedSwaps.isEmpty() && !isMealOrRecipeProposalRequest(userPrompt))
  val allergenTokens = allergens.map(::canonicalizeAllergenToken).toSet()
  val safeSuggestions = when {
    effectiveConversationalOnly -> emptyList()
    else -> CURATED_BRAIN_BOOSTER_CANDIDATES.filter { c -> c.item.allergens.none { canonicalizeAllergenToken(it) in allergenTokens } }
  }
  val featuredCard = when {
    effectiveConversationalOnly -> null
    else -> synthesizeFeaturedAiRecipeCard(
      userPrompt, focusTag, groundedSwaps,
      filterSchedulableRecipes(recipes, catalogById, allergens, pantryById),
      catalog, pantry, allergens, householdProfile.effectiveTargetServings,
    )
  }

  val proposal = AiMenuOptimizationProposal(
    proposalId = "ai_grounded_${focusTag.name.lowercase()}_${board.boardId}",
    focusTag = focusTag, assistantSummary = summary, slotSwaps = groundedSwaps,
    newIngredientSuggestions = safeSuggestions, isConversationalOnly = effectiveConversationalOnly,
    followUpPrompts = resolveGroundedFollowUps(rootObj, focusTag, effectiveConversationalOnly),
    featuredRecipeCard = featuredCard,
  )
  return SchemaValidator.validateAiMenuOptimizationProposal(proposal, catalog, recipes, board)
}

/**
 * Synthesizes a validated [IngredientCatalogItem] from a raw vision or natural-language food label
 * (such as spices, dried herbs, produce, dairy, proteins, or grains) while rejecting non-food
 * household objects (`mug`, `cup`, `plate`, `receipt`, `counter`, `shelf`, bare `jar`/`bottle`, etc.).
 *
 * @param rawLabel Raw label from multimodal vision output or an explicit user command.
 * @return Synthesized [IngredientCatalogItem] priced in `PHP` (`₱`), or `null` if non-food/blank.
 */
fun synthesizeCatalogItemFromLabel(rawLabel: String): IngredientCatalogItem? {
  // Trim punctuation, bullets, and determiners before stripping packaging prefixes.
  var cleaned = rawLabel.trim().trim('.', ',', ';', ':', '!', '?', '"', '\'', '-', '*', '•', '(', ')')
    .replace(Regex("\\s+"), " ")
  if (cleaned.isEmpty()) return null

  // Strip trailing conversational or location qualifiers left over from command clauses.
  val locRegex = Regex("\\s+(?:in|into|to|on|onto|from)\\s+(?:my\\s+|our\\s+|the\\s+)?(?:smart\\s+)?(?:pantry|fridge|kitchen|grocery(?:\\s+list)?|shopping(?:\\s+list)?).*$", RegexOption.IGNORE_CASE)
  cleaned = cleaned.replace(locRegex, "").replace(Regex("\\s+(?:please|today|now|right\\s+now)$", RegexOption.IGNORE_CASE), "").trim()

  // Iteratively strip leading quantities, articles, and container prefixes ("jar of ", "bottle of ").
  val leadDetRegex = Regex("^(?:\\d+\\s+|a\\s+|an\\s+|the\\s+|my\\s+|our\\s+|some\\s+|several\\s+)", RegexOption.IGNORE_CASE)
  val pkgPrefixRegex = Regex("^(?:glass\\s+|plastic\\s+|small\\s+|large\\s+)?(?:jars?|bottles?|packs?|packets?|bunches|bunch|cans?|bags?|boxes|box|cartons?|containers?|pouches|pouch|tins?|shakers?|pinch(?:es)?|cloves?|pieces?)\\s+of\\s+", RegexOption.IGNORE_CASE)
  var previous: String
  do {
    previous = cleaned
    cleaned = cleaned.replace(leadDetRegex, "").trim()
    val flakesStem = Regex("^flakes\\s+of\\s+(.+)$", RegexOption.IGNORE_CASE).find(cleaned)?.groupValues?.get(1)?.trim()
    val leavesStem = Regex("^leaves\\s+of\\s+(.+)$", RegexOption.IGNORE_CASE).find(cleaned)?.groupValues?.get(1)?.trim()
    cleaned = when {
      flakesStem != null -> if (flakesStem.lowercase().endsWith("flakes")) flakesStem else "$flakesStem flakes"
      leavesStem != null -> if (leavesStem.lowercase().endsWith("leaves")) leavesStem else "$leavesStem leaves"
      else -> cleaned.replace(pkgPrefixRegex, "").trim()
    }
  } while (cleaned != previous && cleaned.isNotEmpty())

  val lower = cleaned.lowercase()
  val words = tokenizeIngredientWords(lower)
  if (words.isEmpty() || words.size > 6 || lower.length < 2) return null

  // Hard non-food object filter: keep household objects ("Ceramic Coffee Mug", "Paper Receipt") out.
  if (words.any { it in NON_FOOD_HOUSEHOLD_WORDS }) return null
  if (words.all { it in GENERIC_UMBRELLA_FOOD_WORDS || it in GENERIC_INGREDIENT_ADJECTIVES }) return null

  // Normalize canonical spice and herb labels so bare "turmeric" / "oregano" read cleanly in UI.
  val displayName = when (lower) {
    "turmeric" -> "Ground Turmeric"
    "oregano" -> "Dried Oregano"
    "bay leaf", "bay leaves" -> "Bay Leaves"
    "parsley flake", "parsley flakes" -> "Parsley Flakes"
    else -> toCulinaryTitleCase(cleaned)
  }
  val slug = displayName.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
  if (slug.isEmpty()) return null
  val ingredientId = "ing_custom_$slug"

  val spec = classifySynthesizedCulinaryItem(displayName.lowercase(), words.toSet())
  val candidate = IngredientCatalogItem(
    ingredientId = ingredientId, displayName = displayName, category = spec.category,
    tier = spec.tier, baseUnit = spec.baseUnit, defaultPackageQuantity = spec.defaultPackageQuantity,
    defaultStorageLocation = spec.storageLocation, defaultShelfLifeDays = spec.shelfLifeDays,
    estimatedPackagePriceUsd = spec.estimatedPricePhp,
  )
  return SchemaValidator.validateIngredientCatalogItem(candidate)
}

/**
 * Parses and grounds the JSON output of `AiEdgePort.analyzeImageWithPrompt` against [catalog],
 * converting verified catalog detections into [RestockDelta]s, synthesizing selectable
 * [VisualIntakeResult.proposedNewCatalogItems] for newly discovered food/spices, and isolating
 * non-food items into [VisualIntakeResult.unrecognizedLabels].
 *
 * @param rawModelOutput Raw string returned by multimodal vision inference.
 * @param catalog Household ingredient catalog for ID and name grounding.
 * @return Validated, catalog-grounded [VisualIntakeResult].
 */
fun parseVisualIntakeJson(
  rawModelOutput: String,
  catalog: List<IngredientCatalogItem>,
): VisualIntakeResult {
  val sanitized = stripGemmaControlTokens(rawModelOutput)
  requireDomainValid(sanitized.isNotBlank(), "VisualIntakeResult") { "blank vision model output" }

  // Enforce strict root keys so arbitrary hallucinated fields fail fast with ValidationException.
  val rootObj = parseJsonObjectStrict("VisualIntakeResult", extractJsonObjectSlice(sanitized))
  val extraKeys = rootObj.keys - setOf("summary", "detectedItems", "unrecognizedLabels")
  if (extraKeys.isNotEmpty()) {
    throw ValidationException("Invalid VisualIntakeResult JSON: unexpected keys $extraKeys", extraKeys.map { "unknown '$it'" })
  }

  val summary = rootObj["summary"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
  requireDomainValid(summary.isNotEmpty(), "VisualIntakeResult") { "summary must be non-blank" }

  val catalogById = indexCatalogById(catalog)
  val detectedItems = mutableListOf<VisualDetectedItem>()
  val groundedDeltasById = linkedMapOf<String, RestockDelta>()
  val unrecognizedLabels = linkedSetOf<String>()
  val proposedNewById = linkedMapOf<String, IngredientCatalogItem>()

  (rootObj["unrecognizedLabels"] as? JsonArray)?.forEach { entry ->
    entry.jsonPrimitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let(unrecognizedLabels::add)
  }

  // Ground each detected visual item against locally available catalog entries before building deltas.
  (rootObj["detectedItems"] as? JsonArray)?.forEachIndexed { idx, element ->
    val obj = element as? JsonObject
      ?: throw ValidationException("detectedItems[$idx] must be a JSON object", listOf("detectedItems[$idx]"))
    val rawLabel = obj["rawLabel"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    requireDomainValid(rawLabel.isNotEmpty(), "VisualIntakeResult") { "detectedItems[$idx].rawLabel must be non-blank" }

    val candidateId = obj["matchedIngredientId"]?.jsonPrimitive?.contentOrNull?.trim()
      ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    val rawQty = obj["estimatedQuantity"]?.jsonPrimitive?.doubleOrNull ?: 0.0
    val confidence = (obj["confidence"]?.jsonPrimitive?.doubleOrNull ?: 1.0).coerceIn(0.0, 1.0)
    val matchedItem = matchCatalogItemFromVisualLabel(candidateId, rawLabel, catalog, catalogById)

    when {
      matchedItem != null && matchedItem.isLocallyAvailable -> {
        val resolvedQty = if (rawQty > 0.0 && rawQty.isFinite()) rawQty else matchedItem.defaultPackageQuantity
        detectedItems.add(VisualDetectedItem(rawLabel, matchedItem.ingredientId, matchedItem.displayName, resolvedQty, matchedItem.baseUnit, confidence))
        val prevQty = groundedDeltasById[matchedItem.ingredientId]?.addedBaseQuantity ?: 0.0
        val newStatus = if (matchedItem.tier == IngredientTier.TIER_2_STAPLE) StapleStatus.PLENTY else null
        groundedDeltasById[matchedItem.ingredientId] = RestockDelta(matchedItem.ingredientId, prevQty + resolvedQty, newStatus)
      }
      else -> {
        detectedItems.add(VisualDetectedItem(rawLabel, null, null, max(0.0, rawQty), null, confidence))
        unrecognizedLabels.add(rawLabel)
        val synthesized = synthesizeCatalogItemFromLabel(rawLabel)
        if (synthesized != null && synthesized.ingredientId !in catalogById) {
          proposedNewById.putIfAbsent(synthesized.ingredientId, synthesized)
        }
      }
    }
  }

  // Synthesize culinary catalog proposals for food labels placed in unrecognizedLabels first,
  // while keeping non-food household items ("Ceramic Coffee Mug", "Paper Receipt") excluded.
  for (unmatchedLabel in unrecognizedLabels) {
    if (matchCatalogItemFromVisualLabel(null, unmatchedLabel, catalog, catalogById) == null) {
      val synthesized = synthesizeCatalogItemFromLabel(unmatchedLabel)
      if (synthesized != null && synthesized.ingredientId !in catalogById) {
        proposedNewById.putIfAbsent(synthesized.ingredientId, synthesized)
      }
    }
  }

  // Fallback for VLMs that describe visible spices/ingredients in summary while emitting zero food arrays.
  if (groundedDeltasById.isEmpty() && proposedNewById.isEmpty()) {
    for (candidatePhrase in extractCandidateFoodLabelsFromSummary(summary)) {
      val matched = matchCatalogItemFromVisualLabel(null, candidatePhrase, catalog, catalogById)
      when {
        matched != null && matched.isLocallyAvailable -> {
          val qty = matched.defaultPackageQuantity
          detectedItems.add(VisualDetectedItem(candidatePhrase, matched.ingredientId, matched.displayName, qty, matched.baseUnit, 0.9))
          val prevQty = groundedDeltasById[matched.ingredientId]?.addedBaseQuantity ?: 0.0
          val status = if (matched.tier == IngredientTier.TIER_2_STAPLE) StapleStatus.PLENTY else null
          groundedDeltasById[matched.ingredientId] = RestockDelta(matched.ingredientId, prevQty + qty, status)
        }
        else -> {
          val synthesized = synthesizeCatalogItemFromLabel(candidatePhrase)
          if (synthesized != null) {
            detectedItems.add(VisualDetectedItem(candidatePhrase, null, null, 0.0, null, 0.85))
            unrecognizedLabels.add(candidatePhrase)
            if (synthesized.ingredientId !in catalogById) {
              proposedNewById.putIfAbsent(synthesized.ingredientId, synthesized)
            }
          }
        }
      }
    }
  }

  return VisualIntakeResult(
    summary, detectedItems, groundedDeltasById.values.toList(),
    unrecognizedLabels.toList(), null, proposedNewById.values.toList(),
  )
}

/**
 * Matches explicit natural-language user text commands to deterministic `< 5ms` on-device tools
 * (`restock_pantry_batch`, `deplete_pantry_items`, `queue_grocery_items`, `activate_life_event`,
 * `schedule_family_meal`).
 *
 * **Hard Multimodal Safety Rule**: Immediately returns `null` whenever [hasAttachedImage] is `true`
 * so an uninspected camera/gallery photo NEVER triggers regex pantry mutations before vision runs!
 *
 * @param userPrompt Explicit text command typed or spoken by the user.
 * @param hasAttachedImage `true` when an image is attached to the current turn.
 * @param catalog Household ingredient catalog for matching mentioned items.
 * @param recipes Household recipe library for `schedule_family_meal` matching.
 * @param board Optional active weekly board for resolving target slot IDs.
 * @param pantry Optional current pantry ledger for `EC-13` 100%-in-stock meal swaps.
 * @param householdAllergens Optional active allergen exclusions for swap eligibility.
 * @param activeDayIndex Default weekday (`0 = Monday`..`6 = Sunday`) for `"tonight"` / `"today"`.
 * @return [AiToolCallResult] when an explicit tool command matched, or `null` otherwise.
 */
fun tryExecuteDeterministicToolIntent(
  userPrompt: String,
  hasAttachedImage: Boolean = false,
  catalog: List<IngredientCatalogItem>,
  recipes: List<Recipe> = emptyList(),
  board: WeeklyBoard? = null,
  pantry: List<PantryLedgerEntry> = emptyList(),
  householdAllergens: List<String> = emptyList(),
  activeDayIndex: Int = 0,
): AiToolCallResult? {
  // Hard multimodal safety guardrail: never run regex pantry mutations when a photo is attached.
  if (hasAttachedImage) return null
  val trimmed = userPrompt.trim()
  val lower = trimmed.lowercase()
  if (lower.isEmpty()) return null

  // Tool 1: deplete_pantry_items ("remove X from pantry", "ran out of X", "mark X out of stock").
  val depleteClause = extractDepleteItemsClause(trimmed)
  if (depleteClause != null) {
    val depleted = findMentionedCatalogItems(depleteClause.lowercase(), catalog)
      .ifEmpty { findMentionedCatalogItems(lower, catalog) }
    if (depleted.isNotEmpty()) {
      val namesList = formatNaturalItemList(depleted.map { it.displayName })
      return AiToolCallResult(
        toolName = "deplete_pantry_items",
        summaryMessage = "Marked $namesList out of stock in your Pantry.",
        toastMessage = "Marked ${depleted.size} item(s) out of stock.",
        followUpPrompts = listOf("Add to grocery list", "Swap tonight's dinner"),
        outOfStockIngredientIds = depleted.map { it.ingredientId },
      )
    }
  }

  // Tool 2: queue_grocery_items ("add ... to grocery list", "need to buy ...", "put ... on shopping").
  val queueClause = extractQueueGroceryClause(trimmed)
  if (queueClause != null) {
    val (resolved, synthesized) = resolveCatalogAndSynthesizedItems(queueClause, catalog)
    if (resolved.isNotEmpty()) {
      val namesList = formatNaturalItemList(resolved.map { it.displayName })
      return AiToolCallResult(
        toolName = "queue_grocery_items",
        summaryMessage = "Added $namesList to your Grocery List.",
        toastMessage = "Added ${resolved.size} item(s) to Grocery List.",
        queuedGroceryIngredientIds = resolved.map { it.ingredientId },
        followUpPrompts = listOf("What meals use our pantry?", "20-min kids breakfast"),
        synthesizedCatalogItems = synthesized,
      )
    }
  }

  // Tool 3: restock_pantry_batch ("add X to pantry", "put X in pantry", "stock X", "we bought X").
  val restockClause = extractRestockPantryClause(trimmed)
  if (restockClause != null) {
    val (resolved, synthesized) = resolveCatalogAndSynthesizedItems(restockClause, catalog)
    if (resolved.isNotEmpty()) {
      val deltas = resolved.map { item ->
        RestockDelta(item.ingredientId, item.defaultPackageQuantity, if (item.tier == IngredientTier.TIER_2_STAPLE) StapleStatus.PLENTY else null)
      }
      val namesList = formatNaturalItemList(resolved.map { it.displayName })
      return AiToolCallResult(
        toolName = "restock_pantry_batch",
        summaryMessage = "Stocked $namesList in your Pantry.",
        toastMessage = "Stocked ${resolved.size} item(s) in Pantry.",
        restockDeltas = deltas,
        followUpPrompts = listOf("What can we cook now?", "Swap tonight's dinner"),
        synthesizedCatalogItems = synthesized,
      )
    }
  }

  // Tool 4: activate_life_event ("activate pregnancy", "enable postpartum", "turn on visiting guests").
  if (Regex("\\b(activate|enable|turn\\s+on|start)\\b.*(pregnan|trimester|postpartum|breastfeed|lactation|guest|visit|iron)").containsMatchIn(lower)) {
    val preset = when {
      Regex("postpartum|breastfeed|lactation").containsMatchIn(lower) -> HouseholdLifeEventPreset.POSTPARTUM_BREASTFEEDING
      Regex("guest|visit").containsMatchIn(lower) -> HouseholdLifeEventPreset.VISITING_FAMILY_GUESTS
      Regex("iron|immunity").containsMatchIn(lower) -> HouseholdLifeEventPreset.HIGH_IRON_IMMUNITY
      Regex("1st|first").containsMatchIn(lower) -> HouseholdLifeEventPreset.PREGNANCY_TRIMESTER_1
      else -> HouseholdLifeEventPreset.PREGNANCY_TRIMESTER_2_3
    }
    return AiToolCallResult(
      toolName = "activate_life_event",
      summaryMessage = "Activated ${preset.title} and updated 1-pot food-safety plating rules.",
      toastMessage = "Activated life event: ${preset.badgeLabel}",
      queuedGroceryIngredientIds = preset.recommendedIngredientIds,
      activatedLifeEvent = preset.toLifeEvent(),
      followUpPrompts = listOf("Refresh menu for ${preset.badgeLabel}", "Check food-safety plating"),
    )
  }

  // Tool 5: schedule_family_meal ("schedule X for friday dinner", "swap tonight's dinner", "swap monday breakfast").
  val isExplicitSchedule = Regex("\\b(schedule|assign|put)\\b.*\\b(breakfast|lunch|dinner)\\b").containsMatchIn(lower)
  val isSlotSwapCommand = Regex("\\bswap\\b.*\\b(breakfast|lunch|dinner)\\b").containsMatchIn(lower)
  if ((isExplicitSchedule || isSlotSwapCommand) && recipes.isNotEmpty()) {
    val slotType = when {
      lower.contains("breakfast") -> MealSlotType.BREAKFAST
      lower.contains("lunch") -> MealSlotType.LUNCH
      else -> MealSlotType.DINNER
    }
    val parsedDay = parseDayIndexFromPrompt(lower)
    val dayIndex = when {
      parsedDay != null -> parsedDay
      lower.contains("tomorrow") -> (activeDayIndex.coerceIn(0, 6) + 1) % 7
      else -> activeDayIndex.coerceIn(0, 6)
    }
    val currentSlot = board?.slots?.firstOrNull { it.dayIndex == dayIndex && it.slotType == slotType }
    val namedRecipe = recipes.firstOrNull { recipe ->
      val words = recipe.title.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 4 }
      val matchesTitle = lower.contains(recipe.title.lowercase()) || words.count { lower.contains(it) } >= 2
      val slotAllowed = slotType in recipe.allowedSlots &&
        (slotType != MealSlotType.BREAKFAST || isWeekdayBreakfastEligible(recipe))
      matchesTitle && slotAllowed
    }
    val matchedRecipe = when {
      namedRecipe != null -> namedRecipe
      isSlotSwapCommand -> pickBestInStockOrSchedulableSwap(
        slotType, recipes, catalog, pantry, householdAllergens, currentSlot?.targetServings, currentSlot?.recipeId,
      )
      else -> null
    }
    val validBoardSlot = board?.slots?.any { it.dayIndex == dayIndex && it.slotType == slotType } ?: true
    if (matchedRecipe != null && validBoardSlot) {
      val dayNames = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
      val dayLabel = dayNames.getOrElse(dayIndex) { "Monday" }
      val isAutoSwap = isSlotSwapCommand && namedRecipe == null
      val verb = if (isAutoSwap) "Swapped" else "Scheduled"
      val summaryMsg = if (isAutoSwap) {
        "Swapped $dayLabel ${slotType.name.lowercase()} to '${matchedRecipe.title}'."
      } else {
        "Scheduled '${matchedRecipe.title}' for $dayLabel ${slotType.name.lowercase()}."
      }
      return AiToolCallResult(
        toolName = "schedule_family_meal", summaryMessage = summaryMsg,
        toastMessage = "$verb ${matchedRecipe.title} on $dayLabel.",
        scheduledDayIndex = dayIndex, scheduledSlotType = slotType,
        scheduledRecipeId = matchedRecipe.recipeId,
        followUpPrompts = listOf("Check Smart Grocery List", "Build Weekly Prep Session"),
      )
    }
  }

  return null
}

private data class SynthesizedCatalogSpec(
  val category: IngredientCategory, val tier: IngredientTier, val baseUnit: BaseUnit,
  val defaultPackageQuantity: Double, val storageLocation: StorageLocation,
  val shelfLifeDays: Int, val estimatedPricePhp: Double,
)

// Non-food household and container nouns strictly blocked from catalog synthesis.
private val NON_FOOD_HOUSEHOLD_WORDS: Set<String> = setOf(
  "mug", "mugs", "cup", "cups", "plate", "plates", "bowl", "bowls", "fork", "forks", "spoon",
  "spoons", "knife", "knives", "utensil", "utensils", "cutlery", "counter", "countertop", "shelf",
  "shelves", "table", "chair", "cabinet", "drawer", "jar", "jars", "bottle", "bottles", "phone",
  "smartphone", "camera", "bag", "bags", "receipt", "receipts", "invoice", "ceramic", "porcelain",
  "glass", "plastic", "cardboard", "paper", "napkin", "napkins", "towel", "towels", "sponge",
  "soap", "detergent", "trash", "pan", "skillet", "pot", "lid", "lids", "tray", "appliance",
  "fridge", "refrigerator", "freezer", "stove", "oven", "sink", "hand", "hands", "person", "rack",
  "box", "boxes", "can", "cans", "container", "containers",
)

// Umbrella terms rejected when standing alone without a concrete ingredient noun.
private val GENERIC_UMBRELLA_FOOD_WORDS: Set<String> = setOf(
  "spice", "spices", "herb", "herbs", "seasoning", "seasonings", "condiment", "condiments",
  "ingredient", "ingredients", "item", "items", "food", "foods", "grocery", "groceries",
  "pantry", "kitchen", "list", "shopping", "stock", "meal", "meals", "dinner", "lunch", "breakfast",
)

// Generic culinary adjectives and packaging words excluded from single-token ingredient matching.
private val GENERIC_INGREDIENT_ADJECTIVES: Set<String> = setOf(
  "fresh", "whole", "large", "plain", "natural", "sweet", "organic", "extra", "virgin", "roasted",
  "dried", "sliced", "ground", "ripe", "raw", "fine", "cracked", "baby", "crusty", "artisan",
  "rolled", "grain", "long", "unsalted", "creamery", "farm", "free", "range", "lean", "firm",
  "pure", "pressed", "boneless", "curly", "cooking", "poached", "wildflower", "unrefined",
  "choice", "deli", "soft", "creamy", "smooth", "unsweetened", "chilled", "warm", "fillet",
  "pack", "carton", "bottle", "bunch", "bulb",
)

// Head nouns shared across distinct compound catalog items requiring full compound stem match.
private val SHARED_INGREDIENT_HEAD_NOUNS: Set<String> = setOf(
  "oil", "butter", "pepper", "seed", "nut", "fruit", "milk", "bean",
)

// Classify synthesized culinary items across spices/condiments, produce, dairy, proteins, & grains.
private fun classifySynthesizedCulinaryItem(displayLower: String, wordSet: Set<String>): SynthesizedCatalogSpec {
  val isBellOrChiliProduce = displayLower.contains("bell pepper") || displayLower.contains("capsicum")
  val spiceKeywords = setOf(
    "turmeric", "bay", "parsley", "oregano", "thyme", "rosemary", "paprika", "cumin", "pepper",
    "peppercorn", "cinnamon", "curry", "chili", "cayenne", "coriander", "nutmeg", "clove", "cloves",
    "cardamom", "anise", "achiote", "annatto", "oil", "vinegar", "sauce", "soy", "patis", "toyo",
    "bagoong", "salt", "sugar", "flour", "cornstarch", "vanilla", "honey", "syrup", "mustard",
    "ketchup", "mayo", "seasoning", "powder", "flake", "flakes",
  )
  val dairyKeywords = setOf("milk", "cheese", "cheddar", "mozzarella", "parmesan", "yogurt", "egg", "eggs", "butter", "cream", "ghee")
  val proteinKeywords = setOf(
    "chicken", "beef", "pork", "turkey", "lamb", "duck", "salmon", "tilapia", "bangus", "fish",
    "tuna", "sardine", "shrimp", "prawn", "hipon", "crab", "squid", "tofu", "tokwa", "tempeh",
    "meat", "steak", "sausage", "bacon", "ham", "lentil", "lentils", "chickpea", "monggo", "beans",
  )
  val grainKeywords = setOf(
    "bread", "baguette", "pandesal", "loaf", "bun", "tortilla", "wrap", "pita", "rice", "pasta",
    "spaghetti", "penne", "macaroni", "noodle", "noodles", "pancit", "bihon", "canton", "oat",
    "oats", "oatmeal", "quinoa", "couscous", "barley", "cornmeal", "gari", "granola",
  )
  val produceKeywords = setOf(
    "calamansi", "ginger", "luya", "garlic", "onion", "shallot", "scallion", "leek", "tomato",
    "potato", "kamote", "cassava", "ube", "taro", "carrot", "celery", "broccoli", "cauliflower",
    "spinach", "kale", "malunggay", "pechay", "kangkong", "lettuce", "cabbage", "cucumber",
    "zucchini", "eggplant", "talong", "ampalaya", "okra", "sitaw", "squash", "kalabasa", "sayote",
    "radish", "mushroom", "corn", "avocado", "banana", "plantain", "mango", "pineapple", "papaya",
    "orange", "tangerine", "dalandan", "lemon", "lime", "apple", "pear", "grape", "berry",
    "blueberry", "strawberry", "watermelon", "melon", "dragon", "cilantro", "basil", "mint", "dill",
  )
  val isNutOrCoconut = wordSet.any { it in setOf("peanut", "almond", "cashew", "walnut", "sunflower", "coconut") }

  return when {
    !isBellOrChiliProduce && wordSet.any { it in spiceKeywords } ->
      SynthesizedCatalogSpec(IngredientCategory.PANTRY_STAPLE, IngredientTier.TIER_2_STAPLE, BaseUnit.GRAM, 100.0, StorageLocation.PANTRY, 365, 85.0)
    !isNutOrCoconut && wordSet.any { it in dairyKeywords } ->
      SynthesizedCatalogSpec(IngredientCategory.DAIRY_EGGS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 500.0, StorageLocation.FRIDGE, 7, 180.0)
    wordSet.any { it in proteinKeywords } ->
      SynthesizedCatalogSpec(IngredientCategory.PROTEIN, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 500.0, StorageLocation.FRIDGE, 3, 320.0)
    wordSet.any { it in grainKeywords } ->
      SynthesizedCatalogSpec(IngredientCategory.BAKERY_GRAINS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 500.0, StorageLocation.PANTRY, 30, 140.0)
    isBellOrChiliProduce || wordSet.any { it in produceKeywords } ->
      SynthesizedCatalogSpec(IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 300.0, StorageLocation.FRIDGE, 5, 120.0)
    else ->
      SynthesizedCatalogSpec(IngredientCategory.PANTRY_STAPLE, IngredientTier.TIER_2_STAPLE, BaseUnit.GRAM, 100.0, StorageLocation.PANTRY, 365, 85.0)
  }
}

private fun toCulinaryTitleCase(phrase: String): String {
  val minorWords = setOf("of", "and", "in", "with")
  return phrase.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.mapIndexed { idx, word ->
    val lower = word.lowercase()
    if (idx > 0 && lower in minorWords) lower else lower.replaceFirstChar { it.uppercaseChar() }
  }.joinToString(" ")
}

private fun formatNaturalItemList(names: List<String>): String = when (names.size) {
  0 -> ""
  1 -> names[0]
  2 -> "${names[0]} and ${names[1]}"
  else -> "${names.dropLast(1).joinToString(", ")}, and ${names.last()}"
}

// Extract comma/and-separated food phrases after "including" / "such as" / "containing" in vision summary.
private fun extractCandidateFoodLabelsFromSummary(summary: String): List<String> {
  val preferredMatch = Regex("\\b(?:including|such\\s+as)\\s*:?\\s*([^.!?\\n]+)", RegexOption.IGNORE_CASE).find(summary)
    ?: Regex("\\bcontaining\\s*:?\\s*([^.!?\\n]+)", RegexOption.IGNORE_CASE).find(summary)
    ?: return emptyList()
  return splitNaturalItemClause(preferredMatch.groupValues[1].trim())
}

private fun splitNaturalItemClause(clause: String): List<String> =
  clause.split(Regex("\\s*,\\s*(?:and\\s+)?|\\s+and\\s+", RegexOption.IGNORE_CASE))
    .map { it.trim().trim('.', ',', ';', ':') }
    .filter { it.isNotEmpty() }

private fun matchFirstGroup(prompt: String, patterns: List<Regex>): String? =
  patterns.firstNotNullOfOrNull { regex -> regex.find(prompt)?.groupValues?.getOrNull(1)?.trim() }

private fun extractDepleteItemsClause(prompt: String): String? = matchFirstGroup(
  prompt,
  listOf(
    Regex("^(?:please\\s+)?remove\\s+(.+?)\\s+from\\s+(?:my\\s+|our\\s+|the\\s+)?(?:pantry|fridge|stock)\\b", RegexOption.IGNORE_CASE),
    Regex("^(?:please\\s+)?mark\\s+(.+?)\\s+out\\s+of\\s+stock\\b", RegexOption.IGNORE_CASE),
    Regex("\\b(?:ran\\s+out\\s+of|(?:we\\s+are|we're|i\\s+am|i'm)\\s+out\\s+of|^out\\s+of)\\s+(.+)$", RegexOption.IGNORE_CASE),
  ),
)

private fun extractQueueGroceryClause(prompt: String): String? = matchFirstGroup(
  prompt,
  listOf(
    Regex("^(?:please\\s+)?(?:add|put|queue)\\s+(.+?)\\s+(?:to|on|onto|in|into)\\s+(?:my\\s+|our\\s+|the\\s+)?(?:smart\\s+)?(?:grocery|shopping)(?:\\s+list)?\\b", RegexOption.IGNORE_CASE),
    Regex("\\bneed\\s+to\\s+buy\\s+(.+)$", RegexOption.IGNORE_CASE),
  ),
)

private fun extractRestockPantryClause(prompt: String): String? = matchFirstGroup(
  prompt,
  listOf(
    Regex("^(?:please\\s+)?add\\s+(.+?)\\s+(?:to|into|in)\\s+(?:my\\s+|our\\s+|the\\s+)?pantry\\b", RegexOption.IGNORE_CASE),
    Regex("^(?:please\\s+)?put\\s+(.+?)\\s+(?:in|into|to)\\s+(?:my\\s+|our\\s+|the\\s+)?pantry\\b", RegexOption.IGNORE_CASE),
    Regex("\\b(?:we\\s+bought|i\\s+bought|just\\s+bought)\\s+(.+)$", RegexOption.IGNORE_CASE),
    Regex("^(?:please\\s+)?restock\\s+(.+)$", RegexOption.IGNORE_CASE),
    Regex("^(?:please\\s+)?(?:we\\s+)?stock(?:ed)?\\s+(.+)$", RegexOption.IGNORE_CASE),
  ),
)

// Resolve mentioned catalog items in natural order while synthesizing catalog entries for unknown foods.
private fun resolveCatalogAndSynthesizedItems(
  itemsClause: String,
  catalog: List<IngredientCatalogItem>,
): Pair<List<IngredientCatalogItem>, List<IngredientCatalogItem>> {
  val resolvedById = linkedMapOf<String, IngredientCatalogItem>()
  val synthesizedById = linkedMapOf<String, IngredientCatalogItem>()
  val catalogIds = catalog.map { it.ingredientId }.toSet()

  for (segment in splitNaturalItemClause(itemsClause)) {
    val segmentMatches = findMentionedCatalogItems(segment.lowercase(), catalog)
    when {
      segmentMatches.isNotEmpty() -> segmentMatches.forEach { resolvedById.putIfAbsent(it.ingredientId, it) }
      else -> synthesizeCatalogItemFromLabel(segment)?.let { synthesized ->
        resolvedById.putIfAbsent(synthesized.ingredientId, synthesized)
        if (synthesized.ingredientId !in catalogIds) synthesizedById.putIfAbsent(synthesized.ingredientId, synthesized)
      }
    }
  }
  // Ensure compound catalog names containing "and" still resolve if splitNaturalItemClause divided them.
  findMentionedCatalogItems(itemsClause.lowercase(), catalog).forEach { resolvedById.putIfAbsent(it.ingredientId, it) }
  return resolvedById.values.toList() to synthesizedById.values.toList()
}

private fun pickBestInStockOrSchedulableSwap(
  slotType: MealSlotType, recipes: List<Recipe>, catalog: List<IngredientCatalogItem>,
  pantry: List<PantryLedgerEntry>, householdAllergens: List<String>,
  targetServings: Int?, excludeRecipeId: String?,
): Recipe? {
  // Prefer EC-13 100%-in-stock swaps whenever household pantry stock is available.
  if (pantry.isNotEmpty()) {
    findInstantInStockSwaps(slotType, recipes, catalog, pantry, householdAllergens, targetServings, excludeRecipeId)
      .firstOrNull()?.let { return it }
  }
  val eligible = filterSchedulableRecipes(recipes, catalog, householdAllergens, pantry.takeIf { it.isNotEmpty() })
    .filter { slotType in it.allowedSlots && (slotType != MealSlotType.BREAKFAST || isWeekdayBreakfastEligible(it)) }
  return eligible.firstOrNull { it.recipeId != excludeRecipeId } ?: eligible.firstOrNull()
}

// Normalize English plural suffixes (ies -> y, oes/ches/shes/xes/zes -> strip es, trailing s -> strip s).
private fun normalizeIngredientTokenStem(rawToken: String): String {
  val word = rawToken.trim().lowercase()
  return when {
    word.length >= 4 && word.endsWith("ies") -> "${word.dropLast(3)}y"
    word.length >= 5 && (
      word.endsWith("oes") || word.endsWith("ches") || word.endsWith("shes") ||
        word.endsWith("xes") || word.endsWith("zes")
      ) -> word.dropLast(2)
    word.length >= 4 && word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
    else -> word
  }
}

private fun tokenizeIngredientWords(text: String): List<String> =
  text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

// Match a catalog item against normalized user prompt or vision label stems without adjective noise.
private fun matchesCatalogIngredientText(
  textLower: String,
  textStems: Set<String>,
  item: IngredientCatalogItem,
): Boolean {
  val displayLower = item.displayName.lowercase()
  val shortId = item.ingredientId.removePrefix("ing_").replace('_', ' ').lowercase()
  if (textLower.contains(displayLower)) return true

  val shortTokens = tokenizeIngredientWords(shortId).filter { it.length >= 3 }
  val shortStems = shortTokens.map(::normalizeIngredientTokenStem)
  // Match when every stem in shortId appears in the text (e.g., "blueberries" -> "blueberry", "oats" -> "oat").
  if (shortStems.isNotEmpty() && shortStems.all { it in textStems }) return true

  // Match distinctive multi-word shortId stems (e.g., "yogurt" -> greek_yogurt, "wraps" -> tortilla_wrap).
  val distinctiveShortStems = shortTokens
    .filter { it.length >= 4 && it !in GENERIC_INGREDIENT_ADJECTIVES }
    .map(::normalizeIngredientTokenStem)
    .filter { it !in GENERIC_INGREDIENT_ADJECTIVES && it !in SHARED_INGREDIENT_HEAD_NOUNS }
  if (distinctiveShortStems.any { it in textStems }) return true

  // Match when all non-generic displayName tokens (>= 4 chars) appear in normalized text stems.
  val displayNonGenericStems = tokenizeIngredientWords(displayLower)
    .filter { it.length >= 4 && it !in GENERIC_INGREDIENT_ADJECTIVES }
    .map(::normalizeIngredientTokenStem)
    .filter { it !in GENERIC_INGREDIENT_ADJECTIVES }
  return displayNonGenericStems.isNotEmpty() && displayNonGenericStems.all { it in textStems }
}

// Match catalog items referenced by display name or plural-normalized stem in user text.
private fun findMentionedCatalogItems(promptLower: String, catalog: List<IngredientCatalogItem>): List<IngredientCatalogItem> {
  val promptStems = tokenizeIngredientWords(promptLower)
    .filter { it.length >= 3 }
    .map(::normalizeIngredientTokenStem)
    .toSet()
  return catalog.filter { item -> matchesCatalogIngredientText(promptLower, promptStems, item) }
}

// Resolve a vision model's raw label or candidate ID against known household catalog records.
private fun matchCatalogItemFromVisualLabel(
  candidateId: String?,
  rawLabel: String,
  catalog: List<IngredientCatalogItem>,
  catalogById: Map<String, IngredientCatalogItem>,
): IngredientCatalogItem? {
  if (candidateId != null) catalogById[candidateId]?.let { return it }
  val labelLower = rawLabel.trim().lowercase()
  if (labelLower.isEmpty()) return null
  val labelStems = tokenizeIngredientWords(labelLower)
    .filter { it.length >= 3 }
    .map(::normalizeIngredientTokenStem)
    .toSet()
  return catalog.firstOrNull { item -> matchesCatalogIngredientText(labelLower, labelStems, item) }
}

private fun parseDayIndexFromPrompt(promptLower: String): Int? = when {
  Regex("\\bmon(day)?\\b").containsMatchIn(promptLower) -> 0
  Regex("\\btue(s|sday)?\\b").containsMatchIn(promptLower) -> 1
  Regex("\\bwed(nesday)?\\b").containsMatchIn(promptLower) -> 2
  Regex("\\bthu(r|rs|rsday)?\\b").containsMatchIn(promptLower) -> 3
  Regex("\\bfri(day)?\\b").containsMatchIn(promptLower) -> 4
  Regex("\\bsat(urday)?\\b").containsMatchIn(promptLower) -> 5
  Regex("\\bsun(day)?\\b").containsMatchIn(promptLower) -> 6
  else -> null
}
