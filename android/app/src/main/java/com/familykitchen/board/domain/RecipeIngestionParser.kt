package com.familykitchen.board.domain

import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

// Pure 3-mode custom recipe ingestion parser (Smart Paste, Cookbook Vision OCR, Quick Editor),
// regional ingredient matcher, zero-phantom-stock materializer, recipe forking, and deletion engine.
// Zero Android, Compose, java.io, or java.time imports permitted inside domain/.

private const val DEFAULT_CHILD_SAFETY_TIP: String =
  "Cut into soft bite-sized pieces safe for young children."

// Pipe-delimited Tagalog, Yoruba, and French culinary aliases mapped to canonical catalog IDs.
private val REGIONAL_INGREDIENT_SYNONYMS: List<Pair<String, String>> = listOf(
  "kulitis|amaranth|efo tete|efo shoko|tete" to "ing_amaranth_greens",
  "malunggay|moringa" to "ing_malunggay_pechay",
  "kangkong|water spinach" to "ing_kangkong",
  "pechay|bok choy" to "ing_pechay",
  "ewedu|baby spinach|epinard|épinard|spinach" to "ing_spinach",
  "saba banana|ripe saba|saba" to "ing_saba_banana",
  "plantain|dodo|alloco" to "ing_plantain",
  "carabao mango" to "ing_carabao_mango",
  "kamote|sweet potato|patate douce" to "ing_sweet_potato",
  "kamoteng kahoy|fresh cassava" to "ing_cassava",
  "gari|cassava gari" to "ing_cassava_gari",
  "gata|coconut milk|lait de coco" to "ing_gata",
  "calamansi|kalamansi" to "ing_calamansi",
  "hipon|suahe|shrimp|prawn|crevette" to "ing_shrimp",
  "bangus|milkfish" to "ing_bangus",
  "tilapia" to "ing_tilapia",
  "tokwa|tofu" to "ing_tofu",
  "monggo|mung bean" to "ing_monggo_beans",
  "red lentil|lentils|lentille" to "ing_lentils",
  "black-eyed pea|black eyed pea|ewa|niebe|niébé" to "ing_beans",
  "kidney bean|red bean|haricot rouge" to "ing_red_beans",
  "chicken breast|blanc de poulet" to "ing_chicken_breast",
  "chicken thigh|chicken|poulet" to "ing_chicken",
  "red snapper|snapper|whole fish|market fish|poisson" to "ing_fish",
  "salmon|saumon" to "ing_salmon",
  "tuna|thon" to "ing_tuna",
  "red palm oil|palm oil|zomi|epo pupa" to "ing_red_palm_oil",
  "olive oil|huile d'olive|extra-virgin olive" to "ing_olive_oil",
  "unsalted butter|creamery butter|butter|beurre" to "ing_butter",
  "greek yogurt|plain yogurt|yaourt" to "ing_greek_yogurt",
  "fresh milk|whole milk|lait" to "ing_milk",
  "cheddar|comte|comté|gruyere|cheese|fromage" to "ing_cheese",
  "farm egg|large egg|eggs|egg|oeuf|œuf" to "ing_egg",
  "red onion|sibuyas pula|oignon rouge" to "ing_red_onion",
  "yellow onion|cooking onion|onion|sibuyas|oignon" to "ing_onion",
  "bawang|garlic|gousse d'ail|ail" to "ing_garlic",
  "luya|ginger|gingembre" to "ing_ginger",
  "bell pepper|sweet pepper|poivron|tatashe" to "ing_bell_pepper",
  "tomato|kamatis|tomate" to "ing_tomato",
  "jasmine rice|white rice|sinandomeng|rice|riz" to "ing_rice",
  "baguette|french bread|crusty bread" to "ing_baguette",
  "rolled oat|oats|oatmeal|flocons d'avoine" to "ing_oats",
  "penne|pasta|spaghetti|pâtes" to "ing_pasta",
  "sea salt|kosher salt|salt|sel" to "ing_salt",
  "black pepper|cracked pepper|poivre" to "ing_black_pepper",
)

/**
 * Three tactile creation tabs inside the Add and Import Recipe flow.
 *
 * @property tabLabel Clean UI tab title free of slashes or parentheses.
 */
@Serializable
enum class RecipeCreationMode(val tabLabel: String) {
  // Tab 1: Free-form paste extraction from blogs, messages, or notes.
  /** Paste raw recipe text from notes, messages, or blogs for instant structured extraction. */
  SMART_PASTE("Smart Paste"),

  // Tab 2: Camera or gallery OCR transcription of cookbook pages.
  /** Photograph a printed cookbook page or handwritten family card via multimodal Vision OCR. */
  SCAN_COOKBOOK("Scan Cookbook"),

  // Tab 3: Direct structured authoring and remixing.
  /** Author or refine title, timings, ingredients, and steps directly in structured fields. */
  QUICK_EDITOR("Quick Editor"),
}

/**
 * Structured ingredient line extracted during Smart Paste, Cookbook OCR, or Quick Editor entry.
 *
 * @property rawLine Original unedited ingredient line text.
 * @property displayName Cleaned ingredient label shown in editable draft rows.
 * @property quantity Normalized numeric quantity in [unit].
 * @property unit Canonical measurement unit (`count`, `g`, or `ml`).
 * @property matchedCatalogId Matched `ingredientId` in catalog or local market presets, if found.
 * @property tier Tracking tier (`TIER_1_QUANTITATIVE` vs `TIER_2_STAPLE`).
 */
@Serializable
data class ParsedIngredientDraftLine(
  val rawLine: String,
  val displayName: String,
  val quantity: Double,
  val unit: BaseUnit,
  val matchedCatalogId: String? = null,
  val tier: IngredientTier = IngredientTier.TIER_1_QUANTITATIVE,
)

/**
 * Editable intermediate recipe draft produced by [parseRawRecipeTextToDraft] or
 * [parseCookbookVisionJsonToDraft] prior to final confirmation and materialization.
 *
 * @property title Dish display title.
 * @property servings Baseline recipe yield (`4` default).
 * @property rawPrepMinutes Unbuffered hands-on preparation minutes.
 * @property cookMinutes Active stove or oven cooking minutes.
 * @property allowedSlots Meal slot types where this recipe may be scheduled.
 * @property cuisineTags Detected or household-defaulted culinary traditions.
 * @property ingredientLines Extracted quantitative and staple BOM draft lines.
 * @property stepInstructions Sequential plain-text cooking instructions.
 * @property toddlerPrepTip Choking-prevention cut and texture note for young children (`EC-08`).
 */
@Serializable
data class SmartRecipeDraft(
  val title: String,
  val servings: Int = 4,
  val rawPrepMinutes: Int = 10,
  val cookMinutes: Int = 20,
  val allowedSlots: List<MealSlotType> = listOf(MealSlotType.DINNER),
  val cuisineTags: List<String> = emptyList(),
  val ingredientLines: List<ParsedIngredientDraftLine> = emptyList(),
  val stepInstructions: List<String> = emptyList(),
  val toddlerPrepTip: String = DEFAULT_CHILD_SAFETY_TIP,
)

/**
 * Atomic pure output of [materializeRecipeFromDraft], bundling the validated custom [Recipe] with
 * any newly added catalog definitions and `0`-stock pantry ledger entries.
 *
 * @property recipe Validated household [Recipe] (`isUserCreated = true`) with step timers and macros.
 * @property updatedCatalog Catalog list including any newly matched preset or synthesized items.
 * @property updatedPantry Pantry list initializing every newly added item at `0.0` / `OUT` (`0` phantom stock).
 */
@Serializable
data class MaterializedCustomRecipeResult(
  val recipe: Recipe,
  val updatedCatalog: List<IngredientCatalogItem>,
  val updatedPantry: List<PantryLedgerEntry>,
)

/**
 * Parses free-form pasted recipe text (blog posts, family messages, notes) into an editable
 * [SmartRecipeDraft], fuzzy-matching ingredients against [catalog] and [LOCAL_MARKET_PRESET_INGREDIENTS].
 *
 * @param rawText Raw multi-line recipe text pasted by the user.
 * @param catalog Current household ingredient catalog.
 * @param defaultCuisines Fallback household preferred cuisines applied when text omits explicit origin.
 * @return Populated [SmartRecipeDraft].
 */
fun parseRawRecipeTextToDraft(
  rawText: String,
  catalog: List<IngredientCatalogItem>,
  defaultCuisines: List<String> = emptyList(),
): SmartRecipeDraft {
  val lookupPool = recipeIngestBuildLookupPool(catalog)
  val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
  if (lines.isEmpty()) {
    return SmartRecipeDraft(
      title = "Custom Family Recipe",
      cuisineTags = defaultCuisines.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
    )
  }

  var servings = 4
  var prepMinutes: Int? = null
  var cookMinutes: Int? = null
  var extractedTitle: String? = null
  var toddlerTip: String? = null
  // Section state: 0 = header/unclassified, 1 = ingredients block, 2 = instructions block.
  var section = 0
  val ingredientLines = mutableListOf<ParsedIngredientDraftLine>()
  val stepInstructions = mutableListOf<String>()

  for (rawLine in lines) {
    val cleanHeader = rawLine.trimStart('#', '*', '-', '•').trim()
    val lower = cleanHeader.lowercase()

    // Extract inline metadata lines (Servings, Prep, Cook, Toddler safety note) before section routing.
    recipeIngestExtractServings(lower)?.let { servings = it }
    recipeIngestExtractMinutes(lower, isPrep = true)?.let { prepMinutes = it }
    recipeIngestExtractMinutes(lower, isPrep = false)?.let { cookMinutes = it }
    if (Regex("^(toddler|baby|kid\\s+tip|child\\s+safety)\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(cleanHeader)) {
      toddlerTip = cleanHeader.substringAfter(':').trim().takeIf { it.isNotEmpty() }
      continue
    }

    when {
      recipeIngestIsIngredientsHeader(lower) -> { section = 1; continue }
      recipeIngestIsStepsHeader(lower) -> { section = 2; continue }
      recipeIngestIsPureMetadataLine(lower) -> continue
      extractedTitle == null && section == 0 && !recipeIngestLooksLikeIngredientLine(rawLine) -> {
        extractedTitle = cleanHeader.removePrefix("Title:").removePrefix("Recipe:").trim()
        continue
      }
    }

    // Route body lines into BOM draft items vs sequential cooking instructions.
    when {
      section == 1 -> recipeIngestParseSingleIngredientLine(rawLine, lookupPool)?.let(ingredientLines::add)
      section == 2 -> recipeIngestSplitStepText(rawLine).forEach(stepInstructions::add)
      recipeIngestLooksLikeIngredientLine(rawLine) ->
        recipeIngestParseSingleIngredientLine(rawLine, lookupPool)?.let(ingredientLines::add)
      else -> recipeIngestSplitStepText(rawLine).forEach(stepInstructions::add)
    }
  }

  val resolvedTitle = extractedTitle?.takeIf { it.isNotBlank() } ?: "Custom Family Recipe"
  val resolvedPrep = (prepMinutes ?: 10).coerceIn(0, 180)
  val resolvedCook = (cookMinutes ?: 20).coerceIn(0, 300)
  return SmartRecipeDraft(
    title = resolvedTitle,
    servings = servings.coerceIn(1, 24),
    rawPrepMinutes = resolvedPrep,
    cookMinutes = resolvedCook,
    allowedSlots = recipeIngestInferAllowedSlots(rawText, resolvedPrep, resolvedCook),
    cuisineTags = recipeIngestInferCuisines(resolvedTitle, rawText, defaultCuisines),
    ingredientLines = ingredientLines,
    stepInstructions = stepInstructions,
    toddlerPrepTip = toddlerTip ?: DEFAULT_CHILD_SAFETY_TIP,
  )
}

/**
 * Builds a pixel-neutral multimodal OCR prompt instructing Gemma 4 Vision or Cloud Vision to
 * transcribe a photographed cookbook page or handwritten recipe card into strict JSON.
 *
 * @param preferredCuisines Household preferred cuisine labels aiding culinary term recognition.
 * @return Grounded multimodal cookbook OCR prompt string.
 */
fun buildCookbookPhotoOcrPrompt(preferredCuisines: List<String>): String {
  val cuisineContext = preferredCuisines.map { it.trim() }.filter { it.isNotEmpty() }
    .joinToString(", ").ifEmpty { "Global Family" }
  // Never inject fake example food names so vision transcribes strictly what appears on the page.
  return listOf(
    "ROLE: SavorAI Cookbook Page OCR & Culinary Transcription Specialist.",
    "TASK: Read ONLY the printed or handwritten recipe text visible in the attached image. Do NOT invent unseen ingredients or steps.",
    "HOUSEHOLD_CUISINE_CONTEXT: Recognize culinary terms from $cuisineContext when present on the page.",
    "OUTPUT_SCHEMA: Return ONLY valid JSON matching " +
      "{\"title\":\"...\",\"servings\":4,\"rawPrepMinutes\":10,\"cookMinutes\":20," +
      "\"allowedSlots\":[\"DINNER\"],\"cuisineTags\":[\"$cuisineContext\"]," +
      "\"ingredients\":[\"<qty> <unit> <ingredient>\"],\"steps\":[\"<step 1>\"]," +
      "\"toddlerPrepTip\":\"Cut into soft bite-sized pieces safe for young children.\"}",
  ).joinToString("\n")
}

/**
 * Parses raw multimodal vision output from a photographed cookbook page into a structured
 * [SmartRecipeDraft], supporting strict JSON responses as well as plain-text OCR fallbacks.
 *
 * @param rawOutput Raw text emitted by `AiEdgePort.analyzeImageWithPrompt`.
 * @param catalog Household ingredient catalog for fuzzy ingredient matching.
 * @param defaultCuisines Fallback household preferred cuisines.
 * @return Validated [SmartRecipeDraft].
 */
fun parseCookbookVisionJsonToDraft(
  rawOutput: String,
  catalog: List<IngredientCatalogItem>,
  defaultCuisines: List<String> = emptyList(),
): SmartRecipeDraft {
  val sanitized = stripGemmaControlTokens(rawOutput).trim()
  requireDomainValid(sanitized.isNotEmpty(), "CookbookVisionOcr") { "Cookbook OCR output is blank" }

  val openBrace = sanitized.indexOf('{')
  val closeBrace = sanitized.lastIndexOf('}')
  // Gracefully support on-device VLMs that transcribe plain text while rejecting broken JSON braces.
  if (openBrace < 0 || closeBrace <= openBrace) {
    if (openBrace >= 0) {
      throw ValidationException("Malformed Cookbook OCR JSON envelope", listOf("unclosed JSON object"))
    }
    return parseRawRecipeTextToDraft(sanitized, catalog, defaultCuisines)
  }

  val rootObj = try {
    SchemaValidator.strictJson.parseToJsonElement(sanitized.substring(openBrace, closeBrace + 1)) as? JsonObject
  } catch (err: SerializationException) {
    throw ValidationException("Invalid Cookbook OCR JSON: ${err.message}", listOf("invalid JSON syntax"), err)
  } ?: throw ValidationException("Cookbook OCR root must be a JSON object", listOf("root not object"))

  val lookupPool = recipeIngestBuildLookupPool(catalog)
  val title = (rootObj["title"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    ?: "Scanned Cookbook Recipe"
  val servings = ((rootObj["servings"] as? JsonPrimitive)?.intOrNull ?: 4).coerceIn(1, 24)
  val prepMins = (
    (rootObj["rawPrepMinutes"] as? JsonPrimitive)?.intOrNull
      ?: (rootObj["prepMinutes"] as? JsonPrimitive)?.intOrNull ?: 10
    ).coerceIn(0, 180)
  val cookMins = ((rootObj["cookMinutes"] as? JsonPrimitive)?.intOrNull ?: 20).coerceIn(0, 300)
  val parsedIngredients = recipeIngestExtractJsonIngredients(rootObj, lookupPool)
  val parsedSteps = recipeIngestExtractJsonSteps(rootObj)
  val explicitCuisines = (rootObj["cuisineTags"] as? JsonArray)
    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()
  val resolvedCuisines = explicitCuisines.ifEmpty {
    recipeIngestInferCuisines(title, parsedSteps.joinToString(" "), defaultCuisines)
  }
  val tip = (rootObj["toddlerPrepTip"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    ?: DEFAULT_CHILD_SAFETY_TIP

  return SmartRecipeDraft(
    title = title,
    servings = servings,
    rawPrepMinutes = prepMins,
    cookMinutes = cookMins,
    allowedSlots = recipeIngestExtractJsonSlots(rootObj, prepMins, cookMins),
    cuisineTags = resolvedCuisines.distinct(),
    ingredientLines = parsedIngredients,
    stepInstructions = parsedSteps,
    toddlerPrepTip = tip,
  )
}

/**
 * Materializes an editable [SmartRecipeDraft] into a validated custom [Recipe] (`isUserCreated = true`),
 * appending any newly matched preset or synthesized catalog items into `updatedCatalog` and
 * initializing their pantry records at **`currentQuantity = 0.0`, `stapleStatus = StapleStatus.OUT`,
 * `isOutOfStock = true`, `expiresInDays = null`** (`0` phantom stock) so `GroceryDiffEngine`
 * accurately queues packages to purchase when scheduled.
 *
 * @param draft Confirmed user recipe draft.
 * @param existingCatalog Current household ingredient catalog.
 * @param existingPantry Current household pantry ledger entries.
 * @param existingRecipeId Optional ID when saving edits to an existing custom recipe.
 * @param parentRecipeId Optional source `recipeId` when forking an existing recipe.
 * @param nowIso Deterministic ISO-8601 timestamp stamped onto newly created `0`-stock pantry rows.
 * @return Atomic [MaterializedCustomRecipeResult].
 */
fun materializeRecipeFromDraft(
  draft: SmartRecipeDraft,
  existingCatalog: List<IngredientCatalogItem>,
  existingPantry: List<PantryLedgerEntry>,
  existingRecipeId: String? = null,
  parentRecipeId: String? = null,
  nowIso: String = DEFAULT_TIMESTAMP_ISO,
): MaterializedCustomRecipeResult {
  val cleanTitle = draft.title.trim()
  requireDomainValid(cleanTitle.isNotEmpty(), "SmartRecipeDraft") { "title must be non-blank" }
  requireDomainValid(draft.ingredientLines.isNotEmpty(), "SmartRecipeDraft") { "ingredientLines must not be empty" }

  val safeNowIso = nowIso.trim().ifEmpty { DEFAULT_TIMESTAMP_ISO }
  val safeServings = draft.servings.coerceIn(1, 24)
  val safePrep = max(0, draft.rawPrepMinutes)
  val safeCook = max(0, draft.cookMinutes)
  val calibratedMinutes = computeCalibratedMorningMinutes(safePrep, 0, safeCook)

  val catalogMap = LinkedHashMap<String, IngredientCatalogItem>().apply { existingCatalog.forEach { put(it.ingredientId, it) } }
  val presetById = LOCAL_MARKET_PRESET_INGREDIENTS.associateBy { it.ingredientId }
  val pantryMap = LinkedHashMap<String, PantryLedgerEntry>().apply { existingPantry.forEach { put(it.ingredientId, it) } }
  val lookupPool = recipeIngestBuildLookupPool(existingCatalog)
  val bomQuantitiesById = LinkedHashMap<String, Double>()

  for (line in draft.ingredientLines) {
    val resolvedItem = recipeIngestResolveOrSynthesizeCatalogItem(line, catalogMap, presetById, lookupPool)
    if (resolvedItem.ingredientId !in catalogMap) catalogMap[resolvedItem.ingredientId] = resolvedItem
    // CRITICAL ZERO-PHANTOM-STOCK INVARIANT: every newly introduced ingredient starts at 0.0 / OUT
    // so scheduling the custom recipe onto the board immediately queues missing packages in Shop.
    if (resolvedItem.ingredientId !in pantryMap) {
      pantryMap[resolvedItem.ingredientId] = PantryLedgerEntry(
        ingredientId = resolvedItem.ingredientId,
        tier = resolvedItem.tier,
        currentQuantity = 0.0,
        stapleStatus = StapleStatus.OUT,
        isOutOfStock = true,
        updatedAtIso = safeNowIso,
        storageLocation = resolvedItem.defaultStorageLocation,
        expiresInDays = null,
        expiryDateIso = null,
        purchasedDaysAgo = null,
      )
    }
    val normalizedQty = recipeIngestNormalizeLineQuantity(line, resolvedItem)
    bomQuantitiesById[resolvedItem.ingredientId] = (bomQuantitiesById[resolvedItem.ingredientId] ?: 0.0) + normalizedQty
  }

  val bomLines = bomQuantitiesById.map { (ingId, qty) ->
    val subGroup = catalogMap.getValue(ingId).substituteGroupIds.firstOrNull()
    BomLine(ingredientId = ingId, baseQuantity = max(0.5, qty), substituteGroupId = subGroup)
  }

  // Respect multi-slot selections while guarding weekday BREAKFAST against > 25m EC-01 violations.
  val requestedSlots = draft.allowedSlots.distinct().ifEmpty { listOf(MealSlotType.DINNER) }
  val resolvedSlots = when {
    MealSlotType.BREAKFAST in requestedSlots && calibratedMinutes > MAX_WEEKDAY_BREAKFAST_MINUTES ->
      (requestedSlots - MealSlotType.BREAKFAST).ifEmpty { listOf(MealSlotType.LUNCH, MealSlotType.DINNER) }
    else -> requestedSlots
  }

  val morningSteps = recipeIngestBuildMorningSteps(cleanTitle, draft.stepInstructions, safeCook, bomLines, catalogMap)
  val macros = recipeIngestEstimateMacrosFromBom(bomLines, safeServings, catalogMap)
  val cleanCuisines = draft.cuisineTags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
  val slug = cleanTitle.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "dish" }
  val resolvedRecipeId = existingRecipeId?.trim()?.takeIf { it.isNotEmpty() } ?: "rec_custom_$slug"

  val rawRecipe = Recipe(
    recipeId = resolvedRecipeId,
    title = cleanTitle,
    sourceAttribution = if (parentRecipeId != null) "Household Remix" else "Household Custom Cookbook",
    allowedSlots = resolvedSlots,
    preferredOwner = if (MealSlotType.BREAKFAST in resolvedSlots) CookOwner.FRED else CookOwner.WIFE,
    baseServings = safeServings,
    rawPrepMinutes = safePrep,
    cookMinutes = safeCook,
    calibratedActiveMinutes = calibratedMinutes,
    morningSteps = morningSteps,
    toddlerPrepTip = draft.toddlerPrepTip.trim().ifEmpty { DEFAULT_CHILD_SAFETY_TIP },
    bomLines = bomLines,
    caloriesPerServing = macros.kcalPerServing,
    proteinGrams = macros.proteinGrams,
    carbsGrams = macros.carbsGrams,
    fatGrams = macros.fatGrams,
    difficulty = if (safePrep + safeCook <= 25) RecipeDifficulty.EASY else RecipeDifficulty.MEDIUM,
    cuisineTag = cleanCuisines.firstOrNull() ?: "Family Kitchen",
    parentRecipeId = parentRecipeId,
    isUserCreated = true,
    cuisineTags = cleanCuisines,
  )
  val validated = SchemaValidator.validateRecipe(rawRecipe, catalogMap)
  return MaterializedCustomRecipeResult(validated, catalogMap.values.toList(), pantryMap.values.toList())
}

/**
 * Forks any built-in or custom [source] recipe into an editable household remix (`isUserCreated = true`)
 * linked back via `parentRecipeId = source.recipeId`.
 *
 * @param source Original recipe to fork.
 * @param customTitle Title stamped onto the forked household version.
 * @param newRecipeId Unique identifier assigned to the forked recipe.
 * @return Immutable forked [Recipe].
 */
fun forkRecipeForHousehold(
  source: Recipe,
  customTitle: String = "${source.title} Family Version",
  newRecipeId: String = "rec_fork_${source.recipeId}",
): Recipe {
  // Stamp parentRecipeId and isUserCreated = true so Clean Reader links back to the source dish.
  val cleanTitle = customTitle.trim().ifEmpty { "${source.title} Family Version" }
  val cleanId = newRecipeId.trim().ifEmpty { "rec_fork_${source.recipeId}" }
  return source.copy(
    recipeId = cleanId,
    title = cleanTitle,
    sourceAttribution = "Household Remix • ${source.title}",
    parentRecipeId = source.recipeId,
    isUserCreated = true,
  )
}

/**
 * Deletes a custom recipe from [recipes] and removes any upcoming `PLANNED` slots referencing
 * [recipeIdToDelete] from [board] (preserving historical `COOKED` records intact).
 *
 * @param recipes Current household recipe library.
 * @param board Current weekly board.
 * @param recipeIdToDelete Target custom `recipeId` to remove.
 * @return Pair of `(updatedRecipes, updatedBoard)`.
 */
fun removeCustomRecipeFromBoardAndList(
  recipes: List<Recipe>,
  board: WeeklyBoard,
  recipeIdToDelete: String,
): Pair<List<Recipe>, WeeklyBoard> {
  val targetId = recipeIdToDelete.trim()
  if (targetId.isEmpty()) return recipes to board
  // Protect canonical built-in rec_01..rec_42 recipes while allowing user-created/forked removals.
  val target = recipes.find { it.recipeId == targetId }
  val isDeletable = target != null && (target.isUserCreated || !Regex("^rec_\\d{2}_").containsMatchIn(target.recipeId))
  if (!isDeletable) return recipes to board

  val updatedRecipes = recipes.filterNot { it.recipeId == targetId }
  val updatedSlots = board.slots.filterNot { it.recipeId == targetId && it.status == MealSlotStatus.PLANNED }
  return updatedRecipes to board.copy(slots = updatedSlots)
}

private data class RecipeIngestMacroTotals(val kcalPerServing: Int, val proteinGrams: Int, val carbsGrams: Int, val fatGrams: Int)

// Combine household catalog with local market presets so regional names match out of the box.
private fun recipeIngestBuildLookupPool(catalog: List<IngredientCatalogItem>): List<IngredientCatalogItem> {
  val byId = LinkedHashMap<String, IngredientCatalogItem>()
  catalog.forEach { byId[it.ingredientId] = it }
  LOCAL_MARKET_PRESET_INGREDIENTS.forEach { byId.putIfAbsent(it.ingredientId, it) }
  return byId.values.toList()
}

// Match English and French ingredient section headings ("Ingredients:", "For the sauce:").
private fun recipeIngestIsIngredientsHeader(lower: String): Boolean =
  Regex("^(ingredients?|ingrédients?|for\\s+the\\s+[a-z\\s]+)\\s*:?\\s*$").matches(lower)

// Match English and French cooking step section headings ("Instructions:", "Method:", "Étapes:").
private fun recipeIngestIsStepsHeader(lower: String): Boolean =
  Regex("^(instructions?|steps?|directions?|method|preparation|préparation|étapes?|etapes?)\\s*:?\\s*$").matches(lower)

// Skip standalone yield and timing metadata headers so they are not misread as ingredients or steps.
private fun recipeIngestIsPureMetadataLine(lower: String): Boolean =
  Regex("^(serves?|servings?|yield|prep(\\s*time)?|cook(\\s*time)?|total(\\s*time)?|pour\\s+\\d+)\\b.*$").matches(lower)

// Parse integer serving yield across "Serves: 4", "4 portions", and French "Pour 4 personnes".
private fun recipeIngestExtractServings(lower: String): Int? {
  val m = Regex("(?:serves?|servings?|yield|pour)\\s*:?\\s*(\\d{1,2})").find(lower)
    ?: Regex("\\b(\\d{1,2})\\s+(?:servings?|portions?|personnes)\\b").find(lower)
  return m?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 24)
}

// Parse explicit prep or cook durations in minutes ("Prep: 10 min", "Cook time: 25 minutes").
private fun recipeIngestExtractMinutes(lower: String, isPrep: Boolean): Int? {
  val key = if (isPrep) "prep" else "cook"
  return Regex("\\b$key(?:\\s*time)?\\s*:?\\s*(\\d{1,3})\\s*(?:m|min|mins|minutes)?\\b")
    .find(lower)?.groupValues?.getOrNull(1)?.toIntOrNull()
}

// Identify bulleted or quantity-prefixed ingredient lines vs prose cooking instructions.
private fun recipeIngestLooksLikeIngredientLine(rawLine: String): Boolean {
  val trimmed = rawLine.trim()
  if (Regex("^[-*•]\\s+.+").matches(trimmed)) return true
  if (Regex("^\\d+[.)]\\s+[A-Z]").containsMatchIn(trimmed) && trimmed.length > 42) return false
  return Regex(
    "^(?:\\d+(?:[./]\\d+)?|[¼½¾])\\s*(?:g|kg|ml|l|tbsp|tsp|cups?|oz|lbs?|cloves?|pinch|packs?|bunches?|cans?)?\\b\\s+[a-zA-Z]",
    RegexOption.IGNORE_CASE,
  ).containsMatchIn(trimmed) && trimmed.length <= 90
}

// Parse a single bulleted or quantity-prefixed ingredient line into quantity, BaseUnit, and match.
private fun recipeIngestParseSingleIngredientLine(
  rawLine: String,
  lookupPool: List<IngredientCatalogItem>,
): ParsedIngredientDraftLine? {
  val stripped = rawLine.trim().replace(Regex("^[-*•]+\\s*"), "").trim()
  if (stripped.isEmpty()) return null
  val normalized = stripped.replace("½", "0.5").replace("¼", "0.25").replace("¾", "0.75")
  val qtyUnitRegex = Regex(
    "^(?:(\\d+\\s+\\d+/\\d+|\\d+/\\d+|\\d+(?:\\.\\d+)?)\\s*)?" +
      "(kg|kilograms?|g|gm|grams?|lbs?|pounds?|oz|ounces?|l|liters?|litres?|ml|milliliters?|" +
      "tbsp|tablespoons?|tsp|teaspoons?|cups?|cloves?|pinch(?:es)?|bunches|bunch|packs?|cans?|pieces?|count)?\\b\\s*(?:of\\s+)?(.+)$",
    RegexOption.IGNORE_CASE,
  )
  val match = qtyUnitRegex.find(normalized) ?: return null
  val parsedNumber = recipeIngestParseFractionOrDouble(match.groupValues[1].trim())
  val rawUnitToken = match.groupValues[2].trim().lowercase()
  val namePart = match.groupValues[3].substringBefore(',').replace(Regex("\\([^)]*\\)"), " ")
    .replace(Regex("\\s+"), " ").trim().ifEmpty { stripped }

  val matchedItem = recipeIngestMatchCatalogItem(namePart, lookupPool)
  val (convertedQty, resolvedUnit) = recipeIngestConvertUnitAndQty(parsedNumber, rawUnitToken, matchedItem)
  // Classify small-spoon oils and spices as TIER_2_STAPLE when unmatched in catalog.
  val isStapleMeasure = rawUnitToken in setOf("tbsp", "tsp", "pinch", "pinches", "clove", "cloves") &&
    Regex("oil|salt|pepper|spice|cinnamon|garlic|turmeric|oregano").containsMatchIn(namePart.lowercase())
  val resolvedTier = matchedItem?.tier ?: if (isStapleMeasure) IngredientTier.TIER_2_STAPLE else IngredientTier.TIER_1_QUANTITATIVE

  return ParsedIngredientDraftLine(
    rawLine = rawLine.trim(),
    displayName = matchedItem?.displayName ?: recipeIngestTitleCase(namePart),
    quantity = max(0.5, convertedQty),
    unit = resolvedUnit,
    matchedCatalogId = matchedItem?.ingredientId,
    tier = resolvedTier,
  )
}

// Convert mixed fractions ("1 1/2"), simple fractions ("3/4"), and decimals into finite Doubles.
private fun recipeIngestParseFractionOrDouble(token: String): Double? {
  if (token.isEmpty()) return null
  // Handle mixed whole + fraction strings such as "1 1/2" before simple slash fractions.
  if (' ' in token && '/' in token) {
    val parts = token.split(Regex("\\s+"), limit = 2)
    return (parts[0].toDoubleOrNull() ?: 0.0) + (recipeIngestParseFractionOrDouble(parts[1]) ?: 0.0)
  }
  // Guard against division by zero on malformed slash fractions.
  if ('/' in token) {
    val nums = token.split('/', limit = 2)
    val num = nums[0].toDoubleOrNull() ?: return null
    val den = nums.getOrNull(1)?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: return null
    return num / den
  }
  return token.toDoubleOrNull()
}

// Convert culinary volume/weight/package units into canonical BaseUnit aligned with matched catalog item.
private fun recipeIngestConvertUnitAndQty(
  parsedQty: Double?,
  unitToken: String,
  matchedItem: IngredientCatalogItem?,
): Pair<Double, BaseUnit> {
  val n = parsedQty?.takeIf { it > 0.0 && it.isFinite() } ?: 1.0
  val targetUnit = matchedItem?.baseUnit
  val rawPair: Pair<Double, BaseUnit> = when (unitToken) {
    "kg", "kilogram", "kilograms" -> (n * 1000.0) to BaseUnit.GRAM
    "g", "gm", "gram", "grams" -> n to BaseUnit.GRAM
    "lb", "lbs", "pound", "pounds" -> (n * 454.0).roundToInt().toDouble() to BaseUnit.GRAM
    "oz", "ounce", "ounces" -> (n * 28.35).roundToInt().toDouble() to BaseUnit.GRAM
    "l", "liter", "liters", "litre", "litres" -> (n * 1000.0) to BaseUnit.MILLILITER
    "ml", "milliliter", "milliliters" -> n to BaseUnit.MILLILITER
    "tbsp", "tablespoon", "tablespoons" -> (n * 15.0) to (if (targetUnit == BaseUnit.GRAM) BaseUnit.GRAM else BaseUnit.MILLILITER)
    "tsp", "teaspoon", "teaspoons" -> (n * 5.0) to (if (targetUnit == BaseUnit.GRAM) BaseUnit.GRAM else BaseUnit.MILLILITER)
    "cup", "cups" -> if (targetUnit == BaseUnit.GRAM) (n * 200.0) to BaseUnit.GRAM else (n * 240.0) to BaseUnit.MILLILITER
    "clove", "cloves" -> (n * 5.0) to (targetUnit ?: BaseUnit.GRAM)
    "pinch", "pinches" -> (n * 2.0) to (targetUnit ?: BaseUnit.GRAM)
    "bunch", "bunches", "pack", "packs", "can", "cans" ->
      if (matchedItem != null) (n * matchedItem.defaultPackageQuantity) to matchedItem.baseUnit else n to BaseUnit.COUNT
    else -> when {
      parsedQty == null && matchedItem != null -> matchedItem.defaultPackageQuantity to matchedItem.baseUnit
      targetUnit == BaseUnit.GRAM && n <= 12.0 -> (n * 120.0) to BaseUnit.GRAM
      targetUnit == BaseUnit.MILLILITER && n <= 12.0 -> (n * 100.0) to BaseUnit.MILLILITER
      else -> n to (targetUnit ?: BaseUnit.COUNT)
    }
  }
  if (targetUnit == null || rawPair.second == targetUnit) return rawPair
  return when (targetUnit) {
    BaseUnit.COUNT -> max(1.0, (rawPair.first / 120.0).roundToInt().toDouble()) to BaseUnit.COUNT
    BaseUnit.GRAM, BaseUnit.MILLILITER -> rawPair.first to targetUnit
  }
}

// Match raw ingredient phrases against regional synonyms first, then displayName & stem overlap.
private fun recipeIngestMatchCatalogItem(rawName: String, lookupPool: List<IngredientCatalogItem>): IngredientCatalogItem? {
  val lower = rawName.trim().lowercase()
  if (lower.isEmpty()) return null
  val byId = lookupPool.associateBy { it.ingredientId }
  // 1. Exact displayName or regional culinary alias match ("kulitis" -> ing_amaranth_greens).
  lookupPool.firstOrNull { it.displayName.lowercase() == lower }?.let { return it }
  for ((pipeAliases, targetId) in REGIONAL_INGREDIENT_SYNONYMS) {
    if (pipeAliases.split('|').any { lower.contains(it) }) byId[targetId]?.let { return it }
  }
  // 2. Plural-normalized token stem match against household catalog and local market presets.
  val queryTokens = lower.split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }.map(::recipeIngestSingularize).toSet()
  if (queryTokens.isEmpty()) return null
  return lookupPool.firstOrNull { item ->
    val idStems = item.ingredientId.removePrefix("ing_").split('_').filter { it.length >= 3 }.map(::recipeIngestSingularize)
    val nameStems = item.displayName.lowercase().split(Regex("[^a-z0-9]+"))
      .filter { it.length >= 4 && it !in setOf("fresh", "whole", "large", "sweet", "roasted", "organic") }
      .map(::recipeIngestSingularize)
    (idStems.isNotEmpty() && idStems.all { it in queryTokens }) || (nameStems.isNotEmpty() && nameStems.all { it in queryTokens })
  }
}

// Normalize plural English ingredient nouns so "thighs" and "tomatoes" match singular stems.
private fun recipeIngestSingularize(word: String): String = when {
  word.length >= 4 && word.endsWith("ies") -> "${word.dropLast(3)}y"
  word.length >= 5 && (word.endsWith("oes") || word.endsWith("ches") || word.endsWith("shes")) -> word.dropLast(2)
  word.length >= 4 && word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
  else -> word
}

// Strip step numbering ("1.", "Step 2:") and split dense multi-sentence paragraphs when needed.
private fun recipeIngestSplitStepText(rawLine: String): List<String> {
  val cleaned = rawLine.trim()
    .replace(Regex("^(?:step|étape|etape)\\s*\\d+\\s*[:.)-]?\\s*", RegexOption.IGNORE_CASE), "")
    .replace(Regex("^\\d+\\s*[.):-]\\s*"), "").trim()
  if (cleaned.isEmpty()) return emptyList()
  return if (cleaned.length <= 180) listOf(cleaned) else cleaned.split(Regex("(?<=[.!?])\\s+(?=[A-Z])")).map { it.trim() }.filter { it.isNotEmpty() }
}

// Infer allowed MealSlotTypes from text keywords while enforcing <= 25m calibrated cap on BREAKFAST.
private fun recipeIngestInferAllowedSlots(rawText: String, prepMinutes: Int, cookMinutes: Int): List<MealSlotType> {
  val lower = rawText.lowercase()
  val calibrated = computeCalibratedMorningMinutes(prepMinutes, 0, cookMinutes)
  val mentionsBreakfast = Regex("\\b(breakfast|morning|omelette|pancake|porridge|granola|oatmeal)\\b").containsMatchIn(lower)
  val mentionsLunch = lower.contains("lunch")
  val mentionsDinner = lower.contains("dinner") || lower.contains("supper")
  return buildList {
    if (mentionsBreakfast && calibrated <= MAX_WEEKDAY_BREAKFAST_MINUTES) add(MealSlotType.BREAKFAST)
    if (mentionsLunch || (!mentionsBreakfast && !mentionsDinner)) add(MealSlotType.LUNCH)
    if (mentionsDinner || !mentionsBreakfast) add(MealSlotType.DINNER)
  }.distinct().ifEmpty { listOf(MealSlotType.DINNER) }
}

// Detect regional culinary traditions from recipe title/prose or fall back to household defaults.
private fun recipeIngestInferCuisines(title: String, bodyText: String, defaultCuisines: List<String>): List<String> {
  val probe = Recipe(
    recipeId = "probe_ingest", title = title, sourceAttribution = bodyText.take(240),
    allowedSlots = listOf(MealSlotType.DINNER), preferredOwner = CookOwner.WIFE,
    rawPrepMinutes = 5, cookMinutes = 10, calibratedActiveMinutes = 17, bomLines = listOf(BomLine("ing_salt", 1.0)),
  )
  val detected = CuisineTradition.entries
    .filter { it != CuisineTradition.GLOBAL_COMFORT && doesRecipeMatchPreferredCuisines(probe, listOf(it.canonicalTag)) }
    .map { it.canonicalTag }
  return detected.ifEmpty { defaultCuisines.map { it.trim() }.filter { it.isNotEmpty() }.distinct() }
}

// Support both string-array ("500 g chicken") and object-array ingredient shapes from vision JSON.
private fun recipeIngestExtractJsonIngredients(rootObj: JsonObject, lookupPool: List<IngredientCatalogItem>): List<ParsedIngredientDraftLine> {
  val arr = (rootObj["ingredients"] as? JsonArray) ?: (rootObj["ingredientLines"] as? JsonArray) ?: return emptyList()
  return arr.mapNotNull { el ->
    when (el) {
      is JsonPrimitive -> el.contentOrNull?.let { recipeIngestParseSingleIngredientLine(it, lookupPool) }
      is JsonObject -> {
        val raw = (el["rawLine"] as? JsonPrimitive)?.contentOrNull
        val name = (el["displayName"] as? JsonPrimitive)?.contentOrNull ?: (el["name"] as? JsonPrimitive)?.contentOrNull ?: raw.orEmpty()
        val qty = (el["quantity"] as? JsonPrimitive)?.doubleOrNull ?: 1.0
        val unitStr = (el["unit"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        recipeIngestParseSingleIngredientLine(raw ?: "$qty $unitStr $name".trim(), lookupPool)
      }
      else -> null
    }
  }
}

// Extract ordered step strings from "steps", "stepInstructions", or multi-line "instructions".
private fun recipeIngestExtractJsonSteps(rootObj: JsonObject): List<String> {
  val arr = (rootObj["steps"] as? JsonArray) ?: (rootObj["stepInstructions"] as? JsonArray) ?: (rootObj["instructions"] as? JsonArray)
  if (arr != null) return arr.flatMap { recipeIngestSplitStepText((it as? JsonPrimitive)?.contentOrNull.orEmpty()) }
  return ((rootObj["instructions"] as? JsonPrimitive)?.contentOrNull.orEmpty()).lines().flatMap(::recipeIngestSplitStepText)
}

// Filter JSON allowedSlots against calibrated EC-01 morning timing limits.
private fun recipeIngestExtractJsonSlots(rootObj: JsonObject, prepMins: Int, cookMins: Int): List<MealSlotType> {
  val rawSlots = (rootObj["allowedSlots"] as? JsonArray)?.mapNotNull { el ->
    val code = (el as? JsonPrimitive)?.contentOrNull?.trim()?.uppercase().orEmpty()
    MealSlotType.entries.find { it.name == code }
  }.orEmpty()
  val calibrated = computeCalibratedMorningMinutes(prepMins, 0, cookMins)
  val filtered = if (calibrated > MAX_WEEKDAY_BREAKFAST_MINUTES) rawSlots - MealSlotType.BREAKFAST else rawSlots
  return filtered.distinct().ifEmpty {
    if (calibrated <= MAX_WEEKDAY_BREAKFAST_MINUTES) listOf(MealSlotType.BREAKFAST, MealSlotType.LUNCH) else listOf(MealSlotType.LUNCH, MealSlotType.DINNER)
  }
}

// Resolve existing or preset item first, or synthesize a clean custom catalog record aligned to line.unit.
private fun recipeIngestResolveOrSynthesizeCatalogItem(
  line: ParsedIngredientDraftLine,
  catalogMap: Map<String, IngredientCatalogItem>,
  presetById: Map<String, IngredientCatalogItem>,
  lookupPool: List<IngredientCatalogItem>,
): IngredientCatalogItem {
  line.matchedCatalogId?.let { id -> catalogMap[id] ?: presetById[id] }?.let { return it }
  recipeIngestMatchCatalogItem(line.displayName, lookupPool)?.let { return catalogMap[it.ingredientId] ?: it }

  val pkgQty = if (line.unit == BaseUnit.COUNT) 6.0 else 500.0
  val synthesized = synthesizeCatalogItemFromLabel(line.displayName)
  if (synthesized != null) {
    return if (synthesized.tier == IngredientTier.TIER_1_QUANTITATIVE && synthesized.baseUnit != line.unit) {
      synthesized.copy(baseUnit = line.unit, defaultPackageQuantity = pkgQty)
    } else {
      synthesized
    }
  }
  val cleanName = recipeIngestTitleCase(line.displayName).ifEmpty { "Custom Ingredient" }
  val slug = cleanName.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "item" }
  return IngredientCatalogItem("ing_custom_$slug", cleanName, IngredientCategory.PRODUCE, line.tier, line.unit, pkgQty)
}

// Convert draft line quantities into the resolved catalog item's canonical BaseUnit.
private fun recipeIngestNormalizeLineQuantity(line: ParsedIngredientDraftLine, item: IngredientCatalogItem): Double {
  val safeQty = if (line.quantity.isFinite() && line.quantity > 0.0) line.quantity else 1.0
  if (line.unit == item.baseUnit) return safeQty
  // Approximate 1 countable produce/protein piece as ~120g when crossing COUNT <-> GRAM boundaries.
  return when (item.baseUnit) {
    BaseUnit.COUNT -> max(1.0, (safeQty / 120.0).roundToInt().toDouble())
    BaseUnit.GRAM, BaseUnit.MILLILITER -> if (line.unit == BaseUnit.COUNT) safeQty * 120.0 else safeQty
  }
}

// Auto-detect countdown timers ("simmer 15 min" -> 900s) and step ingredient IDs across instructions.
private fun recipeIngestBuildMorningSteps(
  cleanTitle: String,
  stepInstructions: List<String>,
  safeCookMinutes: Int,
  bomLines: List<BomLine>,
  catalogById: Map<String, IngredientCatalogItem>,
): List<MorningStep> {
  if (stepInstructions.isEmpty()) {
    return listOf(MorningStep(1, "Prepare and cook $cleanTitle until hot and fragrant.", max(180, safeCookMinutes * 60), "Cook and Plate", stepIngredientIds = bomLines.take(3).map { it.ingredientId }))
  }
  return stepInstructions.mapIndexed { idx, text ->
    val lowerStep = text.lowercase()
    val linkedIds = bomLines.mapNotNull { bom ->
      val tokens = bom.ingredientId.removePrefix("ing_").split('_').filter { it.length >= 3 } +
        catalogById[bom.ingredientId]?.displayName?.lowercase()?.split(Regex("[^a-z0-9]+"))?.filter { it.length >= 4 }.orEmpty()
      if (tokens.any { lowerStep.contains(it) }) bom.ingredientId else null
    }.distinct()
    val resolvedStepIds = when {
      linkedIds.isNotEmpty() -> linkedIds
      idx == 0 -> bomLines.take(2).map { it.ingredientId }
      else -> emptyList()
    }
    MorningStep(idx + 1, text, recipeIngestDetectTimerSeconds(text), "Step ${idx + 1}", stepIngredientIds = resolvedStepIds)
  }
}

// Extract seconds from phrases like "simmer 15 min", "12-15 minutes", "45 sec", or "1 hour".
private fun recipeIngestDetectTimerSeconds(instruction: String): Int? {
  val lower = instruction.lowercase()
  Regex("(?:\\d+\\s*(?:-|to)\\s*)?(\\d{1,3})\\s*(?:min|mins|minute|minutes)\\b").find(lower)
    ?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }?.let { return it * 60 }
  Regex("(\\d{1,2})\\s*(?:hr|hrs|hour|hours)\\b").find(lower)
    ?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }?.let { return it * 3600 }
  return Regex("(\\d{1,3})\\s*(?:sec|secs|second|seconds)\\b").find(lower)
    ?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
}

// Estimate USDA-aligned per-serving calories, protein, carbs, and fat from resolved BOM weights.
private fun recipeIngestEstimateMacrosFromBom(
  bomLines: List<BomLine>,
  servings: Int,
  catalogById: Map<String, IngredientCatalogItem>,
): RecipeIngestMacroTotals {
  var totalKcal = 0.0; var totalProtein = 0.0; var totalCarbs = 0.0; var totalFat = 0.0
  for (bom in bomLines) {
    val item = catalogById[bom.ingredientId]
    val key = "${bom.ingredientId} ${item?.displayName.orEmpty()}".lowercase()
    val unitWeight = when {
      key.contains("egg") -> 50.0
      key.contains("baguette") -> 250.0
      else -> 130.0
    }
    val grams = if ((item?.baseUnit ?: BaseUnit.GRAM) == BaseUnit.COUNT) bom.baseQuantity * unitWeight else bom.baseQuantity
    val f = max(0.0, grams) / 100.0
    when {
      key.contains("oil") -> { totalKcal += f * 813.0; totalFat += f * 92.0 }
      key.contains("butter") -> { totalKcal += f * 717.0; totalFat += f * 81.1 }
      key.contains("chicken") || key.contains("turkey") -> { totalKcal += f * 165.0; totalProtein += f * 24.0; totalFat += f * 7.0 }
      key.contains("salmon") -> { totalKcal += f * 208.0; totalProtein += f * 20.4; totalFat += f * 13.4 }
      key.contains("fish") || key.contains("tilapia") || key.contains("bangus") || key.contains("shrimp") || key.contains("tuna") -> {
        totalKcal += f * 115.0; totalProtein += f * 22.0; totalFat += f * 2.5
      }
      key.contains("egg") -> { totalKcal += f * 143.0; totalProtein += f * 12.6; totalCarbs += f * 0.7; totalFat += f * 9.5 }
      key.contains("rice") || key.contains("pasta") || key.contains("oat") || key.contains("cornmeal") || key.contains("gari") -> {
        totalKcal += f * 365.0; totalProtein += f * 9.0; totalCarbs += f * 76.0; totalFat += f * 2.0
      }
      key.contains("baguette") || key.contains("wrap") -> {
        totalKcal += f * 280.0; totalProtein += f * 10.0; totalCarbs += f * 52.0; totalFat += f * 3.5
      }
      key.contains("bean") || key.contains("lentil") || key.contains("monggo") -> {
        totalKcal += f * 125.0; totalProtein += f * 8.5; totalCarbs += f * 21.0; totalFat += f * 0.8
      }
      key.contains("cheese") -> { totalKcal += f * 403.0; totalProtein += f * 24.9; totalFat += f * 33.1 }
      key.contains("plantain") || key.contains("saba") || key.contains("banana") || key.contains("potato") || key.contains("kamote") -> {
        totalKcal += f * 105.0; totalProtein += f * 1.4; totalCarbs += f * 25.0; totalFat += f * 0.3
      }
      else -> { totalKcal += f * 38.0; totalProtein += f * 2.0; totalCarbs += f * 7.0; totalFat += f * 0.4 }
    }
  }
  val div = max(1, servings).toDouble()
  return RecipeIngestMacroTotals(
    max(120, (totalKcal / div).roundToInt()), max(4, (totalProtein / div).roundToInt()),
    max(4, (totalCarbs / div).roundToInt()), max(2, (totalFat / div).roundToInt()),
  )
}

private fun recipeIngestTitleCase(raw: String): String =
  raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercaseChar) }

/** Object facade exposing custom recipe parsing, OCR, materialization, forking, and deletion. */
object RecipeIngestionParser {
  /** Delegates to [com.familykitchen.board.domain.parseRawRecipeTextToDraft]. */
  fun parseRawRecipeTextToDraft(
    rawText: String,
    catalog: List<IngredientCatalogItem>,
    defaultCuisines: List<String> = emptyList(),
  ): SmartRecipeDraft = com.familykitchen.board.domain.parseRawRecipeTextToDraft(rawText, catalog, defaultCuisines)

  /** Delegates to [com.familykitchen.board.domain.buildCookbookPhotoOcrPrompt]. */
  fun buildCookbookPhotoOcrPrompt(preferredCuisines: List<String>): String =
    com.familykitchen.board.domain.buildCookbookPhotoOcrPrompt(preferredCuisines)

  /** Delegates to [com.familykitchen.board.domain.parseCookbookVisionJsonToDraft]. */
  fun parseCookbookVisionJsonToDraft(
    rawOutput: String,
    catalog: List<IngredientCatalogItem>,
    defaultCuisines: List<String> = emptyList(),
  ): SmartRecipeDraft = com.familykitchen.board.domain.parseCookbookVisionJsonToDraft(rawOutput, catalog, defaultCuisines)

  /** Delegates to [com.familykitchen.board.domain.materializeRecipeFromDraft]. */
  fun materializeRecipeFromDraft(
    draft: SmartRecipeDraft,
    existingCatalog: List<IngredientCatalogItem>,
    existingPantry: List<PantryLedgerEntry>,
    existingRecipeId: String? = null,
    parentRecipeId: String? = null,
    nowIso: String = DEFAULT_TIMESTAMP_ISO,
  ): MaterializedCustomRecipeResult = com.familykitchen.board.domain.materializeRecipeFromDraft(
    draft, existingCatalog, existingPantry, existingRecipeId, parentRecipeId, nowIso,
  )

  /** Delegates to [com.familykitchen.board.domain.forkRecipeForHousehold]. */
  fun forkRecipeForHousehold(
    source: Recipe,
    customTitle: String = "${source.title} Family Version",
    newRecipeId: String = "rec_fork_${source.recipeId}",
  ): Recipe = com.familykitchen.board.domain.forkRecipeForHousehold(source, customTitle, newRecipeId)

  /** Delegates to [com.familykitchen.board.domain.removeCustomRecipeFromBoardAndList]. */
  fun removeCustomRecipeFromBoardAndList(
    recipes: List<Recipe>,
    board: WeeklyBoard,
    recipeIdToDelete: String,
  ): Pair<List<Recipe>, WeeklyBoard> =
    com.familykitchen.board.domain.removeCustomRecipeFromBoardAndList(recipes, board, recipeIdToDelete)
}
