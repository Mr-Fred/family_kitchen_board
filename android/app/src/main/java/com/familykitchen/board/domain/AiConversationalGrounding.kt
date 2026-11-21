package com.familykitchen.board.domain

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

// Pure Kotlin Gemma 4 / Cloud LLM output sanitization, conversational vs proposal intent detection,
// lenient JSON summary salvage, natural plain-text grounding, and provenance badge formatting.
// Imports ONLY Kotlin standard library and kotlinx.serialization packages (100% pure domain/).

// Lenient JSON parser used strictly for salvaging real LLM summaries when optional swap items fail.
private val LENIENT_SALVAGE_JSON: Json = Json {
  ignoreUnknownKeys = true
  isLenient = true
  allowSpecialFloatingPointValues = false
}

// Non-structural BOS/EOS/EOT/PAD/UNK/unused tokens stripped ahead of reasoning-block extraction.
private val PURE_SPECIAL_TOKENS_REGEX: Regex = Regex(
  "<\\|?(?:bos|eos|eot|pad|unk|end_of_turn)\\|?>|<unused\\d+>",
  RegexOption.IGNORE_CASE,
)

// Trailing turn-closing markers stripped from the end so they never truncate unclosed thoughts.
private val TRAILING_TURN_CLOSERS_REGEX: Regex = Regex(
  "(?:\\s*(?:<\\|/turn\\|>|<turn\\|>|<\\|turn\\|>))+\\s*$",
  RegexOption.IGNORE_CASE,
)

// Closed Gemma 4 <|thought|> blocks terminated by <|/thought|>, <thought|>, </thought>, or <|turn|>.
private val CLOSED_GEMMA4_THOUGHT_REGEX: Regex = Regex(
  "<\\|thought\\|>[\\s\\S]*?(?:<\\|/thought\\|>|<thought\\|>|<\\|thought\\|>|</thought>|" +
    "(?:<turn\\|>\\s*)?<\\|turn\\|>\\s*(?:model|assistant)?(?=\\s*\\S))",
  RegexOption.IGNORE_CASE,
)

// Closed XML-style <thought>...</thought> and DeepSeek/OpenRouter <think>...</think> blocks.
private val CLOSED_XML_THOUGHT_REGEX: Regex = Regex(
  "<thought>[\\s\\S]*?(?:</thought>|<thought\\|>|<\\|/thought\\|>)",
  RegexOption.IGNORE_CASE,
)
private val CLOSED_THINK_BLOCK_REGEX: Regex = Regex(
  "<think>[\\s\\S]*?</think>",
  RegexOption.IGNORE_CASE,
)

// Prefill-opened thought prefix terminated by <|/thought|>, <thought|>, </thought>, or </think>
// when LiteRT-LM's C++ chat template pre-consumes the opening <|thought|> token during prefill.
private val ORPHAN_CLOSING_THOUGHT_REGEX: Regex = Regex(
  "^[\\s\\S]*?(?:<\\|/thought\\|>|<thought\\|>|</thought>|</think>)",
  RegexOption.IGNORE_CASE,
)

// Leading turn-role headers (<|turn|>model, <start_of_turn>model) emitted before thought or answer.
private val LEADING_TURN_HEADER_REGEX: Regex = Regex(
  "^(?:\\s*(?:<\\|turn\\|>|<start_of_turn>)\\s*(?:model|assistant|user)?)+",
  RegexOption.IGNORE_CASE,
)

// Unclosed leading thought prefix (<|thought|> or <thought> or <think>) when model omits end tag.
private val UNCLOSED_THOUGHT_PREFIX_REGEX: Regex = Regex(
  "^\\s*(?:<\\|thought\\|>|<thought>|<think>)",
  RegexOption.IGNORE_CASE,
)

// Remaining Gemma 4 and Gemma 2/3 turn, role, and thought control delimiters.
private val REMAINING_CONTROL_TOKENS_REGEX: Regex = Regex(
  "<\\|(?:start_of_turn|end_of_turn|turn|bos|eos|eot|pad|unk|thought)\\|?(?:model|assistant|user)?>|" +
    "<\\|/(?:turn|thought)\\|>|" +
    "<(?:turn|thought|bos|eos|eot|pad|unk)\\|>|" +
    "<\\|turn\\|>\\s*(?:model|assistant|user)?|" +
    "<start_of_turn>\\s*(?:model|assistant|user)?|" +
    "<(?:pad|unk|bos|eos|eot|end_of_turn)>|" +
    "</?(?:thought|think)>|" +
    "<unused\\d+>",
  RegexOption.IGNORE_CASE,
)

// Matches when the entire trimmed output is wrapped inside one Markdown code fence.
private val ENTIRE_FENCED_REPLY_REGEX: Regex = Regex(
  "^```(?:[A-Za-z0-9_-]+)?\\s*([\\s\\S]*?)```$",
)

// Matches a fenced JSON object or array block embedded after brief conversational preamble text.
private val FENCED_JSON_BLOCK_REGEX: Regex = Regex(
  "```(?:json)?\\s*(\\{[\\s\\S]*?\\}|\\[[\\s\\S]*?\\])\\s*```",
  RegexOption.IGNORE_CASE,
)

// Pure culinary technique / food-science question prefixes that should stay conversational.
private val PURE_TECHNIQUE_QUESTION_PREFIX: Regex = Regex(
  "^(?:how\\s+(?:do\\s+(?:i|we)|can\\s+(?:i|we)|to|long|much)|" +
    "why\\s+(?:did|does|do|is|are)|" +
    "what\\s+(?:temperature|temp|internal\\s+temp|is\\s+the\\s+difference))\\b",
  RegexOption.IGNORE_CASE,
)

// Explicit proposal keywords overriding technique-prefix suppression when user asks for a recipe.
private val EXPLICIT_PROPOSAL_OVERRIDE_REGEX: Regex = Regex(
  "\\b(recipes?|swaps?|plan|menu|suggest\\w*|ideas?|grocery|groceries|boosters?)\\b",
  RegexOption.IGNORE_CASE,
)

// Meal, recipe, expiry-rescue, brain-booster, and grocery discovery intent matcher.
private val MEAL_OR_RECIPE_PROPOSAL_REGEX: Regex = Regex(
  "\\b(recipes?|meals?|dinners?|lunches|lunch|breakfasts?|plan|planning|swaps?|" +
    "expir\\w*|waste|perish\\w*|brain|boosters?|cook\\s+tonight|make\\s+tonight|" +
    "eat\\s+tonight|what\\s+(?:can|should)\\s+(?:i|we)\\s+(?:cook|make|eat)|" +
    "suggest\\w*|ideas?|menu|grocery|groceries|iron|protein|workout|20m|20-min|under\\s+20\\w*)\\b",
  RegexOption.IGNORE_CASE,
)

// Candidate JSON string keys inspected when salvaging conversational summaries from lenient JSON.
private val SALVAGE_SUMMARY_KEYS: List<String> = listOf(
  "assistantSummary", "summary", "response", "message", "answer", "reply", "text",
)

// Model bundle file extensions stripped from on-device provenance badges in SavorChatTurnView.
private val MODEL_BUNDLE_EXTENSION_REGEX: Regex = Regex(
  "\\.(?:litertlm|task|bin)$",
  RegexOption.IGNORE_CASE,
)

/**
 * Strips Gemma 4 thinking blocks (`<|thought|>...<|/thought|>`, `<|thought|>...<|turn|>model`,
 * `<thought>...</thought>`, `<think>...</think>`), Gemma 2/3/4 control delimiters, and outer JSON
 * Markdown code fences while preserving inline prose snippets.
 *
 * @param rawModelOutput Raw text emitted by LiteRT-LM, MediaPipe GenAI, or a Cloud LLM endpoint.
 * @return Clean human-readable prose or unwrapped JSON string ready for grounding.
 */
fun sanitizeLlmRawOutput(rawModelOutput: String): String {
  if (rawModelOutput.isBlank()) return ""

  // Step 1: Remove non-structural BOS/EOS/PAD tokens and trailing turn closers at string end first.
  var working = rawModelOutput
    .replace(PURE_SPECIAL_TOKENS_REGEX, "")
    .replace(TRAILING_TURN_CLOSERS_REGEX, "")

  // Step 2: Strip closed Gemma 4 <|thought|>, <thought>, <think>, and C++-prefilled orphan thoughts.
  working = working
    .replace(CLOSED_GEMMA4_THOUGHT_REGEX, "")
    .replace(CLOSED_XML_THOUGHT_REGEX, "")
    .replace(CLOSED_THINK_BLOCK_REGEX, "")
    .replace(ORPHAN_CLOSING_THOUGHT_REGEX, "")
    .replace(LEADING_TURN_HEADER_REGEX, "")

  // Step 3: Recover fenced or balanced JSON if the model left a leading <|thought|> block unclosed.
  if (UNCLOSED_THOUGHT_PREFIX_REGEX.containsMatchIn(working)) {
    val lastJsonFence = FENCED_JSON_BLOCK_REGEX.findAll(working).lastOrNull()
    val trailingJson = findLastSummaryBearingJsonSlice(working)
    working = when {
      lastJsonFence != null -> lastJsonFence.groupValues[1]
      trailingJson != null -> trailingJson
      else -> return ""
    }
  }

  // Step 4: Strip any remaining Gemma 4 / Gemma 2/3 turn, role, and residual thought delimiters.
  val withoutTokens = working
    .replace(REMAINING_CONTROL_TOKENS_REGEX, "")
    .trim()
  if (withoutTokens.isEmpty()) return ""

  // Step 5: Unwrap when the entire reply is a single fence, or extract fenced JSON; preserve prose fences.
  val entireMatch = ENTIRE_FENCED_REPLY_REGEX.matchEntire(withoutTokens)
  if (entireMatch != null) {
    val inner = entireMatch.groupValues[1]
    if (!inner.contains("```")) {
      return inner.trim()
    }
  }
  val jsonFenceMatch = FENCED_JSON_BLOCK_REGEX.findAll(withoutTokens).lastOrNull()
  return when {
    jsonFenceMatch != null -> jsonFenceMatch.groupValues[1].trim()
    else -> withoutTokens
  }
}

/**
 * Returns `true` when [userPrompt] asks for meals, recipes, weekly plan swaps, expiring/zero-waste
 * rescues, brain-boosters, workout dishes, or grocery suggestions, and `false` for pure greetings,
 * meta/identity chat, pantry inventory questions, or culinary technique/science Q&A.
 *
 * @param userPrompt Raw natural-language prompt entered or spoken by the user.
 * @return `true` when structured recipe/swap proposal cards are appropriate for this turn.
 */
fun isMealOrRecipeProposalRequest(userPrompt: String): Boolean {
  val normalized = userPrompt.trim().lowercase()
  if (normalized.isEmpty()) return false

  // Keep how-to / why / internal-temperature food-science questions conversational unless recipes requested.
  if (
    PURE_TECHNIQUE_QUESTION_PREFIX.containsMatchIn(normalized) &&
    !EXPLICIT_PROPOSAL_OVERRIDE_REGEX.containsMatchIn(normalized)
  ) {
    return false
  }
  return MEAL_OR_RECIPE_PROPOSAL_REGEX.containsMatchIn(normalized)
}

/**
 * Grounds a flexible on-device Gemma 4 or Cloud LLM text reply into a validated
 * [AiMenuOptimizationProposal], supporting:
 * 1. Strict compact/full JSON via [parseAndGroundAiResponse],
 * 2. Lenient JSON summary salvage when `gemma-4-E2B-it` emits valid `"assistantSummary"` alongside
 *    placeholder `"slotId": "..."` entries (dropping only invalid swap entries while rethrowing
 *    [ValidationException] on genuinely corrupt JSON like `"{not_valid_json"`), and
 * 3. Natural plain-text conversational or culinary Q&A prose replies without discarding LLM output.
 *
 * @param rawModelOutput Raw string returned by `AiEdgePort.generateText`.
 * @param userPrompt Original user prompt driving conversational vs proposal card behavior.
 * @param board Active 7-day [WeeklyBoard].
 * @param recipes Household recipe library.
 * @param catalog Household ingredient catalog.
 * @param pantry Current household pantry ledger.
 * @param householdProfile Household profile supplying allergens, member goals, and life events.
 * @return Grounded, schema-validated [AiMenuOptimizationProposal].
 */
fun groundFlexibleLlmTextReply(
  rawModelOutput: String,
  userPrompt: String,
  board: WeeklyBoard,
  recipes: List<Recipe>,
  catalog: List<IngredientCatalogItem>,
  pantry: List<PantryLedgerEntry>,
  householdProfile: HouseholdProfile = HouseholdProfile(),
): AiMenuOptimizationProposal {
  val sanitized = stripGemmaControlTokens(rawModelOutput)
  requireDomainValid(sanitized.isNotBlank(), "AiResponse") { "empty model output after stripping tokens" }

  val trimmed = sanitized.trimStart()
  val startsLikeJson = trimmed.startsWith("{") || trimmed.startsWith("[")
  val balancedJsonSlice = findBalancedJsonObjectSlice(sanitized)

  // Branch 1: Strict JSON grounding first, followed by lenient summary salvage on schema friction.
  if (startsLikeJson || balancedJsonSlice != null) {
    try {
      return parseAndGroundAiResponse(
        rawModelOutput = sanitized,
        userPrompt = userPrompt,
        board = board,
        recipes = recipes,
        catalog = catalog,
        pantry = pantry,
        householdProfile = householdProfile,
      )
    } catch (strictError: ValidationException) {
      val salvaged = salvageLenientJsonProposal(
        candidateJson = balancedJsonSlice ?: sanitized,
        userPrompt = userPrompt,
        board = board,
        recipes = recipes,
        catalog = catalog,
        pantry = pantry,
        householdProfile = householdProfile,
      )
      if (salvaged != null) return salvaged
      // Preserve offline fallback contract when a reply starting with '{' or '[' is corrupt JSON.
      if (startsLikeJson) throw strictError
    }
  }

  // Branch 2: Natural plain-text prose reply from Gemma 4 or Cloud LLM.
  val focusTag = inferNutritionFocusFromPrompt(userPrompt)
  val wantsProposalCards = isMealOrRecipeProposalRequest(userPrompt)
  val catalogById = indexCatalogById(catalog)
  val pantryById = indexPantryById(pantry)
  val allergens = householdProfile.householdAllergens
  val featuredCard = when {
    wantsProposalCards -> synthesizeFeaturedAiRecipeCard(
      userPrompt = userPrompt,
      focusTag = focusTag,
      slotSwaps = emptyList(),
      candidateRecipes = filterSchedulableRecipes(recipes, catalogById, allergens, pantryById),
      catalog = catalog,
      pantry = pantry,
      effectiveAllergens = allergens,
      targetServings = householdProfile.effectiveTargetServings,
    )
    else -> null
  }

  val naturalProposal = AiMenuOptimizationProposal(
    proposalId = "ai_natural_${focusTag.name.lowercase()}_${board.boardId}",
    focusTag = focusTag,
    assistantSummary = sanitized,
    slotSwaps = emptyList(),
    newIngredientSuggestions = emptyList(),
    isConversationalOnly = !wantsProposalCards,
    followUpPrompts = buildContextualFollowUpPrompts(focusTag, isConversationalOnly = !wantsProposalCards),
    featuredRecipeCard = featuredCard,
  )
  return SchemaValidator.validateAiMenuOptimizationProposal(naturalProposal, catalog, recipes, board)
}

/**
 * Formats a concise, UI-friendly provenance badge from active runtime capabilities (for example
 * `"gemma-4-E2B-it"` for local `.litertlm` weights or `"Gemini · gemini-3.8-flash"` for Cloud AI).
 *
 * @param backendLabel Runtime backend label from `RuntimeCapabilities.backendLabel`.
 * @param modelName Model filename or cloud slug from `RuntimeCapabilities.modelName`.
 * @return Concise provenance label safe for single-line chat turn headers.
 */
fun formatAiResponseSourceLabel(backendLabel: String, modelName: String): String {
  val cleanModel = modelName.trim().replace(MODEL_BUNDLE_EXTENSION_REGEX, "").trim()
  val cleanBackend = backendLabel.trim()
  return when {
    cleanBackend.startsWith("Cloud", ignoreCase = true) -> {
      val providerLabel = cleanBackend.removePrefix("Cloud").removePrefix("cloud")
        .trimStart(' ', '·', '-', ':')
        .trim()
      when {
        providerLabel.isNotEmpty() && cleanModel.isNotEmpty() -> "$providerLabel · $cleanModel"
        cleanModel.isNotEmpty() -> cleanModel
        else -> cleanBackend
      }
    }
    cleanModel.isNotEmpty() -> cleanModel
    cleanBackend.isNotEmpty() -> cleanBackend
    else -> "On-Device AI"
  }
}

/**
 * Builds a secret-redacted diagnostic toast explaining why a `Ready` on-device or Cloud LLM turn
 * degraded to the deterministic offline pantry advisor.
 *
 * @param error Underlying validation, HTTP, or native runtime failure.
 * @return Sanitized single-line banner message with zero API secret leakage.
 */
fun formatAiFallbackToastMessage(error: Throwable): String {
  if (error is ValidationException) {
    return "AI model reply could not be grounded — showing offline pantry advisor."
  }
  val scrubbed = redactApiSecrets(error.message?.trim().orEmpty())
    .lineSequence()
    .map { it.trim() }
    .firstOrNull { it.isNotEmpty() }
    ?.take(110)
    ?.trim()
  return when {
    scrubbed.isNullOrEmpty() ->
      "AI model reply could not be grounded — showing offline pantry advisor."
    else ->
      "AI model unavailable ($scrubbed) — showing offline pantry advisor."
  }
}

// Format up to the last 4 chat turns into single-line snippets so multi-turn follow-ups stay coherent.
internal fun formatRecentTurnsContext(recentTurns: List<AiChatTurn>): String? {
  if (recentTurns.isEmpty()) return null
  val snippets = recentTurns.takeLast(4).mapNotNull { turn ->
    val cleanText = turn.messageText.replace(Regex("\\s+"), " ").trim().take(180)
    when {
      cleanText.isEmpty() -> null
      turn.role == AiChatRole.USER -> "USER: $cleanText"
      else -> "ASSISTANT: $cleanText"
    }
  }
  return snippets.takeIf { it.isNotEmpty() }?.joinToString(" | ")
}

// Locate the outermost '{...}' JSON object inside raw model text or fail with ValidationException.
internal fun extractJsonObjectSlice(text: String): String {
  val balanced = findBalancedJsonObjectSlice(text)
  if (balanced != null) return balanced
  val start = text.indexOf('{')
  val end = text.lastIndexOf('}')
  requireDomainValid(start >= 0 && end > start, "JsonSlice") { "missing '{...}' JSON object boundaries" }
  return text.substring(start, end + 1)
}

// Parse JSON via SchemaValidator.strictJson so malformed syntax maps to ValidationException.
internal fun parseJsonObjectStrict(entityName: String, rawJson: String): JsonObject =
  try {
    val parsed = SchemaValidator.strictJson.parseToJsonElement(rawJson)
    parsed as? JsonObject
      ?: throw ValidationException("Invalid $entityName: expected JSON object", listOf("<root>: not object"))
  } catch (err: SerializationException) {
    val detail = err.message ?: "malformed JSON"
    throw ValidationException("Invalid $entityName JSON: $detail", listOf(detail), err)
  }

// Scan string-aware brace depth to extract the first balanced '{...}' JSON object when present.
private fun findBalancedJsonObjectSlice(text: String): String? {
  var startIdx = -1
  var depth = 0
  var inQuotes = false
  var escaped = false

  for (idx in text.indices) {
    val ch = text[idx]
    when {
      escaped -> escaped = false
      ch == '\\' && inQuotes -> escaped = true
      ch == '"' -> inQuotes = !inQuotes
      !inQuotes && ch == '{' -> {
        if (depth == 0) startIdx = idx
        depth += 1
      }
      !inQuotes && ch == '}' && depth > 0 -> {
        depth -= 1
        if (depth == 0 && startIdx >= 0) {
          return text.substring(startIdx, idx + 1)
        }
      }
    }
  }
  return null
}

// Recover the final balanced JSON object containing a summary field when <|thought|> was unclosed.
private fun findLastSummaryBearingJsonSlice(text: String): String? {
  var cursor = 0
  var lastSummaryJson: String? = null
  while (cursor < text.length) {
    val nextBrace = text.indexOf('{', cursor)
    if (nextBrace < 0) break
    val candidate = findBalancedJsonObjectSlice(text.substring(nextBrace))
    if (candidate == null) {
      cursor = nextBrace + 1
    } else {
      if (candidate.contains("\"assistantSummary\"") || candidate.contains("\"summary\"")) {
        lastSummaryJson = candidate
      }
      cursor = nextBrace + candidate.length
    }
  }
  return lastSummaryJson
}

// Salvage valid summary text and individually grounded swaps when an LLM emits placeholder swap IDs.
private fun salvageLenientJsonProposal(
  candidateJson: String,
  userPrompt: String,
  board: WeeklyBoard,
  recipes: List<Recipe>,
  catalog: List<IngredientCatalogItem>,
  pantry: List<PantryLedgerEntry>,
  householdProfile: HouseholdProfile,
): AiMenuOptimizationProposal? {
  val rootObj = try {
    LENIENT_SALVAGE_JSON.parseToJsonElement(candidateJson) as? JsonObject
  } catch (_: SerializationException) {
    null
  } catch (_: IllegalArgumentException) {
    null
  } ?: return null

  val summary = SALVAGE_SUMMARY_KEYS.firstNotNullOfOrNull { key ->
    (rootObj[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
  } ?: return null

  val focusTag = (rootObj["focusTag"] as? JsonPrimitive)?.contentOrNull
    ?.let { raw -> NutritionFocusTag.entries.find { it.name == raw.trim().uppercase() } }
    ?: inferNutritionFocusFromPrompt(userPrompt)

  val catalogById = indexCatalogById(catalog)
  val pantryById = indexPantryById(pantry)
  val recipeById = recipes.associateBy { it.recipeId }
  val boardSlotById = board.slots.associateBy { it.slotId }
  val allergens = householdProfile.householdAllergens
  val validSwaps = mutableListOf<AiSlotSwapSuggestion>()
  val seenSlots = mutableSetOf<String>()

  // Keep only swaps that pass real board slot, catalog recipe, allergen, and <= 25m morning checks.
  val rawSwaps = (rootObj["suggestedSwaps"] as? JsonArray) ?: (rootObj["slotSwaps"] as? JsonArray)
  rawSwaps?.forEach { element ->
    val swapObj = element as? JsonObject ?: return@forEach
    val slotId = (swapObj["slotId"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    val recipeId = (swapObj["recipeId"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    val slot = boardSlotById[slotId] ?: return@forEach
    val baseRecipe = recipeById[recipeId] ?: return@forEach
    if (!seenSlots.add(slot.slotId)) return@forEach

    val availability = evaluateRecipeAvailability(baseRecipe, catalogById, allergens, pantryById)
    if (!availability.isSchedulable) return@forEach
    val calibrated = computeCalibratedMorningMinutes(baseRecipe)
    if (slot.slotType == MealSlotType.BREAKFAST && calibrated > MAX_WEEKDAY_BREAKFAST_MINUTES) return@forEach

    val rationale = (swapObj["rationale"] as? JsonPrimitive)?.contentOrNull?.trim()
      ?.takeIf { it.isNotEmpty() && it != "..." }
      ?: "Pantry-grounded swap selected for family nutrition goals."
    val analysis = analyzeRecipeBrainNutrients(baseRecipe, catalogById, pantryById, focusTag)
    val plating = buildFamilyGoalPlatingGuide(
      baseRecipe, catalog, householdProfile.members, householdProfile.activeLifeEvents, householdProfile.youngestStage,
    )
    val tip = baseRecipe.toddlerPrepTip?.takeIf { it.isNotBlank() }
      ?: "Serve in soft bite-sized portions and crush any seeds finely for 2yo."
    val enriched = baseRecipe.copy(
      bomLines = availability.resolvedBomLines,
      calibratedActiveMinutes = calibrated,
      toddlerPrepTip = tip,
      familyGoalPlating = plating,
    )
    validSwaps.add(
      AiSlotSwapSuggestion(
        slot.slotId, slot.dayIndex, slot.slotType, slot.recipeId,
        enriched, rationale, analysis.nutrients, analysis.inStockIngredientIds, plating,
      ),
    )
  }

  val explicitConvFlag = (rootObj["isConversationalOnly"] as? JsonPrimitive)?.booleanOrNull ?: false
  val effectiveConversationalOnly =
    explicitConvFlag || (validSwaps.isEmpty() && !isMealOrRecipeProposalRequest(userPrompt))
  val allergenTokens = allergens.map(::canonicalizeAllergenToken).toSet()
  val safeSuggestions = when {
    effectiveConversationalOnly -> emptyList()
    else -> CURATED_BRAIN_BOOSTER_CANDIDATES.filter { cand ->
      cand.item.allergens.none { canonicalizeAllergenToken(it) in allergenTokens }
    }
  }
  val featuredCard = when {
    effectiveConversationalOnly -> null
    else -> synthesizeFeaturedAiRecipeCard(
      userPrompt, focusTag, validSwaps,
      filterSchedulableRecipes(recipes, catalogById, allergens, pantryById),
      catalog, pantry, allergens, householdProfile.effectiveTargetServings,
    )
  }
  val salvagedFollowUps = resolveGroundedFollowUps(rootObj, focusTag, effectiveConversationalOnly)

  val proposal = AiMenuOptimizationProposal(
    proposalId = "ai_salvaged_${focusTag.name.lowercase()}_${board.boardId}",
    focusTag = focusTag,
    assistantSummary = summary,
    slotSwaps = if (effectiveConversationalOnly) emptyList() else validSwaps,
    newIngredientSuggestions = safeSuggestions,
    isConversationalOnly = effectiveConversationalOnly,
    followUpPrompts = salvagedFollowUps,
    featuredRecipeCard = featuredCard,
  )
  return SchemaValidator.validateAiMenuOptimizationProposal(proposal, catalog, recipes, board)
}

// Extract up to 4 non-blank followUpPrompts from JSON or synthesize calm contextual quick-reply chips.
internal fun resolveGroundedFollowUps(
  rootObj: JsonObject,
  focusTag: NutritionFocusTag,
  isConversationalOnly: Boolean,
): List<String> =
  (rootObj["followUpPrompts"] as? JsonArray)
    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
    ?.take(4)
    .orEmpty()
    .ifEmpty { buildContextualFollowUpPrompts(focusTag, isConversationalOnly) }

private fun buildContextualFollowUpPrompts(
  focusTag: NutritionFocusTag,
  isConversationalOnly: Boolean,
): List<String> = when {
  isConversationalOnly -> listOf(
    "Rescue expiring items",
    "20-min kids breakfast",
    "What can we cook tonight?",
  )
  focusTag == NutritionFocusTag.PANTRY_ZERO_WASTE -> listOf(
    "Swap tonight's dinner",
    "20-min kids breakfast",
    "Check Smart Grocery List",
  )
  else -> listOf(
    "Quick morning under 20m",
    "Rescue expiring items",
    "Post-workout high protein",
  )
}
