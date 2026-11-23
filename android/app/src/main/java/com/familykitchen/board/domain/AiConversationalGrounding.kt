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
  "(?:\\s*(?:<\\|/turn\\|>|<turn\\|>|<\\|turn\\|>|</turn>))+\\s*$",
  RegexOption.IGNORE_CASE,
)

// Closed Gemma 4 <|thought|> or single-pipe <|thought> blocks terminated by <|/thought|>,
// <thought|>, </thought>, <|turn|>, or single-pipe <|turn>model.
private val CLOSED_GEMMA4_THOUGHT_REGEX: Regex = Regex(
  "(?:<\\|thought\\|>|<\\|thought>)[\\s\\S]*?(?:<\\|/thought\\|>|<thought\\|>|<\\|thought\\|>|</thought>|" +
    "(?:<turn\\|>\\s*)?(?:<\\|turn\\|>|<\\|turn>)\\s*(?:model|assistant)?(?=\\s*\\S))",
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

// Leading turn-role headers (<|turn|>model, <|turn>model, <start_of_turn>model) before output.
private val LEADING_TURN_HEADER_REGEX: Regex = Regex(
  "^(?:\\s*(?:<\\|turn\\|>|<\\|turn>|<start_of_turn>)\\s*(?:model|assistant|user)?)+",
  RegexOption.IGNORE_CASE,
)

// Unclosed leading thought prefix (<|thought|>, <|thought>, <thought>, <think>) lacking end tag.
private val UNCLOSED_THOUGHT_PREFIX_REGEX: Regex = Regex(
  "^\\s*(?:<\\|thought\\|>|<\\|thought>|<thought>|<think>)",
  RegexOption.IGNORE_CASE,
)

// Recovers a trailing Gemma tool_call block when an unclosed <|thought> block precedes it.
private val UNCLOSED_THOUGHT_TOOL_RECOVERY_REGEX: Regex = Regex(
  "(?:<\\|tool_call\\|>|<\\|tool_call>|<tool_call>)[\\s\\S]*",
  RegexOption.IGNORE_CASE,
)

// Remaining Gemma 4 and Gemma 2/3 turn, role, and thought delimiters (preserves <|tool_call>!).
private val REMAINING_CONTROL_TOKENS_REGEX: Regex = Regex(
  "<\\|(?:start_of_turn|end_of_turn|turn|bos|eos|eot|pad|unk|thought)\\|?\\s*(?:model|assistant|user)?>|" +
    "<\\|/(?:turn|thought)\\|>|" +
    "<(?:turn|thought|bos|eos|eot|pad|unk)\\|>|" +
    "<\\|turn\\|?>\\s*(?:model|assistant|user)?|" +
    "<start_of_turn>\\s*(?:model|assistant|user)?|" +
    "<(?:pad|unk|bos|eos|eot|end_of_turn)>|" +
    "</?(?:turn|thought|think)>|" +
    "<unused\\d+>",
  RegexOption.IGNORE_CASE,
)

// Strips unexecuted <|tool_call>...<tool_call|>, <|tool_response>, and <|call|> envelopes before UI display.
private val RESIDUAL_TOOL_TAG_BLOCK_REGEX: Regex = Regex(
  "(?:<\\|tool_call\\|>|<\\|tool_call>|<tool_call>)[\\s\\S]*?(?:<\\|/tool_call\\|>|<tool_call\\|>|</tool_call>|<\\|tool_call\\|>|$)|" +
    "(?:<\\|tool_response\\|>|<\\|tool_response>|<tool_response>)[\\s\\S]*?(?:<\\|/tool_response\\|>|<tool_response\\|>|</tool_response>|<\\|tool_response\\|>|$)|" +
    "<\\|/?(?:tool_call|tool_response|call)\\|>|<(?:tool_call|tool_response|call)\\|>|<\\|(?:tool_call|tool_response|call)>|</?(?:tool_call|tool_response)>",
  RegexOption.IGNORE_CASE,
)

// Opening prefix of bare Gemma `call:tool_name{...}` blocks stripped with balanced brace depth.
private val BARE_CALL_COLON_HEADER_REGEX: Regex = Regex(
  "\\bcall:[a-z0-9_]+\\s*\\{",
  RegexOption.IGNORE_CASE,
)

// Parenthesized or bracketed internal IDs like `(rec_06)` or `[ing_egg]` leaked by LLMs into prose.
private val BRACKETED_INTERNAL_ID_REGEX: Regex = Regex(
  "\\s*[\\(\\[](?:rec|ing|slot|board)_[a-z0-9_]+[\\)\\]]",
  RegexOption.IGNORE_CASE,
)

// Bare internal database tokens like `rec_37_poulet_basquaise`, `ing_salmon`, or `slot_mon_dinner`.
private val BARE_INTERNAL_ID_REGEX: Regex = Regex(
  "\\b(?:rec_\\d+[a-z0-9_]*|ing_[a-z0-9_]+|board_[a-z0-9_]+|slot_[a-z0-9_]+)\\b",
  RegexOption.IGNORE_CASE,
)

// Read-only current meal-plan lookup questions ("what's for dinner", "what are we having tonight").
private val CURRENT_MEAL_PLAN_LOOKUP_REGEX: Regex = Regex(
  "\\b(?:what(?:'s|s|\\s+is)\\s+for\\s+(?:dinners?|lunches|lunch|breakfasts?|supper|tonight)|" +
    "what\\s+are\\s+we\\s+(?:having|eating|cooking|making)\\s+(?:tonight|today|this\\s+evening|for\\s+(?:dinners?|lunches|lunch|breakfasts?|supper))|" +
    "what(?:'s|s|\\s+is)\\s+on\\s+(?:the\\s+|our\\s+)?(?:menu|plan|meal\\s+plan))\\b",
  RegexOption.IGNORE_CASE,
)

// Mutation/discovery verbs that turn a meal mention into a swap or rescue request rather than a lookup.
private val MEAL_PLAN_MUTATION_OR_RESCUE_VERB_REGEX: Regex = Regex(
  "\\b(?:swaps?|chang\\w*|replac\\w*|instead|different|suggest\\w*|optimiz\\w*|resc\\w*|expir\\w*|waste)\\b",
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

  // Step 3: Recover tool calls or fenced/balanced JSON if the model left <|thought|> unclosed.
  if (UNCLOSED_THOUGHT_PREFIX_REGEX.containsMatchIn(working)) {
    val toolRecovery = UNCLOSED_THOUGHT_TOOL_RECOVERY_REGEX.find(working)
    val lastJsonFence = FENCED_JSON_BLOCK_REGEX.findAll(working).lastOrNull()
    val trailingJson = findLastSummaryBearingJsonSlice(working)
    working = when {
      toolRecovery != null -> toolRecovery.value
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
 * Infers the target [MealSlotType] explicitly requested in [userPrompt] (`DINNER` for
 * `dinner`/`tonight`/`supper`/`this evening`, `LUNCH` for `lunch`/`midday`, `BREAKFAST` for
 * `breakfast`/`morning`/`20m`/`20-min`), or `null` when no meal slot is specified.
 *
 * @param userPrompt Natural-language prompt entered by the user.
 * @return Requested [MealSlotType] or `null`.
 */
fun inferRequestedMealSlotType(userPrompt: String): MealSlotType? {
  val lower = userPrompt.trim().lowercase()
  if (lower.isEmpty()) return null
  return when {
    Regex("\\b(?:dinners?|tonight|supper|this\\s+evening)\\b").containsMatchIn(lower) -> MealSlotType.DINNER
    Regex("\\b(?:lunches|lunch|midday)\\b").containsMatchIn(lower) -> MealSlotType.LUNCH
    Regex("\\b(?:breakfasts?|morning|20m|20-min|under\\s+20\\w*)\\b").containsMatchIn(lower) -> MealSlotType.BREAKFAST
    else -> null
  }
}

/**
 * Returns `true` when [userPrompt] asks what is **currently scheduled** on the household's meal
 * plan (e.g. `"Hi what's for dinner??"`, `"what is for lunch"`, `"what are we having tonight"`,
 * `"what's on the menu"`) without requesting swaps, replacements, or expiring-item rescues.
 *
 * @param userPrompt Natural-language prompt entered by the user.
 * @return `true` when the user is querying the active scheduled [WeeklyBoard] meal.
 */
fun isCurrentMealPlanLookup(userPrompt: String): Boolean {
  val normalized = userPrompt.trim().lowercase()
  if (normalized.isEmpty()) return false
  if (MEAL_PLAN_MUTATION_OR_RESCUE_VERB_REGEX.containsMatchIn(normalized)) return false
  return CURRENT_MEAL_PLAN_LOOKUP_REGEX.containsMatchIn(normalized)
}

/**
 * Builds a single compact pantry-grounded system prompt for on-device LiteRT-LM / Gemma or Cloud
 * LLM inference, incorporating time-of-day alignment (`LOCAL_TIME_CONTEXT`), household
 * `PREFERRED_CUISINES`, and human-readable `TODAY_MEAL_PLAN` titles while omitting raw `rec_*` and
 * `slot_*` IDs on conversational turns.
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
 * @param todayDayIndex Active weekday index (`0 = Monday`..`6 = Sunday`) for `TODAY_MEAL_PLAN`.
 * @param currentHour24 Optional 24-hour local clock (`0..23`) driving `LOCAL_TIME_CONTEXT`.
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
  todayDayIndex: Int = 0,
  currentHour24: Int? = null,
): String {
  val focusTag = inferNutritionFocusFromPrompt(userPrompt)
  val catalogById = indexCatalogById(catalog)
  val effectiveAllergens = householdAllergens.ifEmpty { householdProfile?.householdAllergens.orEmpty() }
  val defaultConv = !isMealOrRecipeProposalRequest(userPrompt)

  // Serialize Tier-1 stock and Tier-2 statuses in one pass (omitting unmapped raw IDs in chat mode).
  val inStockTier1 = mutableListOf<String>()
  val tier2Statuses = mutableListOf<String>()
  for (entry in pantry) {
    val item = catalogById[entry.ingredientId]
    val label = item?.displayName ?: if (defaultConv) continue else entry.ingredientId
    when {
      entry.tier == IngredientTier.TIER_2_STAPLE -> tier2Statuses.add("$label:${entry.stapleStatus.name}")
      !entry.isOutOfStock && entry.currentQuantity > 0.0 ->
        inStockTier1.add("$label (${entry.currentQuantity.toInt()} ${item?.baseUnit?.wireCode ?: "unit"})")
    }
  }

  val youngestStage = householdProfile?.youngestStage ?: ChildGrowthStage.TODDLER_1_2Y
  val activeCurrency = householdProfile?.currency ?: HouseholdCurrency.PHP
  val marketRegion = householdProfile?.marketRegionLabel ?: "Antipolo & Marikina, PH"
  val preferredCuisines = householdProfile?.preferredCuisines?.takeIf { it.isNotEmpty() }
    ?.joinToString(", ") ?: "French, Yoruba and West African"
  val effectiveHour = (currentHour24 ?: 12).coerceIn(0, 23)
  val localTimeContext = when {
    effectiveHour < 11 -> "Morning (${effectiveHour}:00) -> Focus on Breakfast"
    effectiveHour in 11..14 -> "Midday (${effectiveHour}:00) -> Focus on Lunch"
    else -> "Afternoon/Evening (${effectiveHour}:00) -> Focus on Tonight's Dinner"
  }
  val todayMealPlan = buildTodayMealPlanSummary(board, recipes, todayDayIndex)
  val lifeEventSummary = householdProfile?.activeLifeEvents?.takeIf { it.isNotEmpty() }
    ?.joinToString(" | ") { "${it.title} [Safety: ${it.safetyPrepRules.joinToString("; ")}]" } ?: "NONE"
  val recentContext = formatRecentTurnsContext(recentTurns)

  val responseRule = when {
    defaultConv ->
      "RESPONSE_RULE: Write a warm, specific, culinary-intelligent reply directly answering USER_REQUEST in " +
        "\"assistantSummary\". Keep greetings concise (1-2 warm sentences) aligned with LOCAL_TIME_CONTEXT and " +
        "PREFERRED_CUISINES (never pitch breakfast in the afternoon/evening), NEVER include internal IDs like rec_06 " +
        "or ing_egg, and NEVER emit <|tool_call> tags. Set \"isConversationalOnly\": true and \"suggestedSwaps\": []."
    else ->
      "RESPONSE_RULE: Write a warm, specific, culinary-intelligent reply directly answering USER_REQUEST in " +
        "\"assistantSummary\" (referencing real stocked items, cook times, and child-safe prep — NEVER include raw " +
        "internal IDs like rec_06 in assistantSummary). Set \"isConversationalOnly\": false and include 0-3 " +
        "\"suggestedSwaps\" using ONLY exact IDs from PLANNED_SLOTS and VALID_RECIPE_IDS (never \"...\")."
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
    add("LOCAL_TIME_CONTEXT: $localTimeContext")
    add("PREFERRED_CUISINES: $preferredCuisines")
    add("MARKET_AND_CURRENCY: $marketRegion (${activeCurrency.isoCode} ${activeCurrency.symbol}).")
    add("DEVICE_PROFILE: ${deviceProfile.displayName} (${deviceProfile.preferredDelegate}, ${deviceProfile.quantization}).")
    add("FOCUS_TAG: ${focusTag.name}")
    add(
      "CONSTRAINTS: Weekday BREAKFAST calibratedActiveMinutes <= ${MAX_WEEKDAY_BREAKFAST_MINUTES}m (1.4x buffer); " +
        "non-empty child safety tip for ${youngestStage.shortLabel}; allergens=(${effectiveAllergens.joinToString(",").ifEmpty { "NONE" }}).",
    )
    add("ACTIVE_LIFE_EVENTS: $lifeEventSummary")
    add("TODAY_MEAL_PLAN: $todayMealPlan")
    add("IN_STOCK_TIER1_PANTRY (${inStockTier1.size}): ${inStockTier1.joinToString("; ").ifEmpty { "None" }}")
    add("TIER2_STAPLES (${tier2Statuses.size}): ${tier2Statuses.joinToString(", ").ifEmpty { "None" }}")
    if (!defaultConv) {
      val plannedSlotIds = board.slots.filter { it.status == MealSlotStatus.PLANNED }
        .take(9).joinToString(",") { "${it.slotId}:${it.slotType.name}" }
      val validRecipeIds = recipes.take(30).joinToString(",") { it.recipeId }
      add("PLANNED_SLOTS: $plannedSlotIds")
      add("VALID_RECIPE_IDS: $validRecipeIds")
    }
    if (recentContext != null) add("RECENT_CONVERSATION: $recentContext")
    add(responseRule)
    add(outputFormat)
    add("USER_REQUEST: ${userPrompt.trim()}")
  }.joinToString("\n")
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
  if (isCurrentMealPlanLookup(normalized)) return true

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
 * @param todayDayIndex Active weekday index (`0 = Monday`..`6 = Sunday`) for meal-plan lookups.
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
  todayDayIndex: Int = 0,
): AiMenuOptimizationProposal {
  val sanitized = stripResidualToolCallMarkup(stripGemmaControlTokens(rawModelOutput)).trim()
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
        todayDayIndex = todayDayIndex,
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
        todayDayIndex = todayDayIndex,
      )
      if (salvaged != null) return salvaged
      // Preserve offline fallback contract when a reply starting with '{' or '[' is corrupt JSON.
      if (startsLikeJson) throw strictError
    }
  }

  // Branch 2: Natural plain-text prose reply from Gemma 4 or Cloud LLM (scrubbed of internal IDs).
  val cleanProse = cleanHumanAssistantSummary(sanitized)
  requireDomainValid(cleanProse.isNotBlank(), "AiResponse") { "empty assistant summary after cleaning internal IDs" }
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
      board = board,
      preferredCuisines = householdProfile.preferredCuisines,
      todayDayIndex = todayDayIndex,
    )
    else -> null
  }

  val naturalProposal = AiMenuOptimizationProposal(
    proposalId = "ai_natural_${focusTag.name.lowercase()}_${board.boardId}",
    focusTag = focusTag,
    assistantSummary = cleanProse,
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

// Remove unexecuted <|tool_call>...<tool_call|>, <|tool_response>, and bare call:name{...} markup.
internal fun stripResidualToolCallMarkup(text: String): String {
  if (text.isBlank()) return ""
  var cleaned = text.replace(RESIDUAL_TOOL_TAG_BLOCK_REGEX, "")
  // Strip bare `call:tool_name{...}` spans using quote-aware brace balancing for nested args.
  while (true) {
    val header = BARE_CALL_COLON_HEADER_REGEX.find(cleaned) ?: break
    val openBraceIdx = header.range.last
    var depth = 0
    var inDoubleQuotes = false
    var inSingleQuotes = false
    var escaped = false
    var endIdx = cleaned.length
    for (idx in openBraceIdx until cleaned.length) {
      val ch = cleaned[idx]
      when {
        escaped -> escaped = false
        ch == '\\' && (inDoubleQuotes || inSingleQuotes) -> escaped = true
        ch == '"' && !inSingleQuotes -> inDoubleQuotes = !inDoubleQuotes
        ch == '\'' && !inDoubleQuotes -> inSingleQuotes = !inSingleQuotes
        !inDoubleQuotes && !inSingleQuotes && ch == '{' -> depth += 1
        !inDoubleQuotes && !inSingleQuotes && ch == '}' -> {
          depth -= 1
          if (depth == 0) {
            endIdx = idx + 1
            break
          }
        }
      }
    }
    cleaned = cleaned.removeRange(header.range.first, endIdx)
  }
  return cleaned.trim()
}

// Strip parenthesized/bare internal IDs (`(rec_06)`, `ing_egg`, `slot_...`) from assistant prose.
internal fun cleanHumanAssistantSummary(rawSummary: String): String =
  rawSummary
    .replace(BRACKETED_INTERNAL_ID_REGEX, "")
    .replace(BARE_INTERNAL_ID_REGEX, "")
    .replace(Regex("[ \\t]{2,}"), " ")
    .replace(Regex("\\s+([,.;!?])"), "$1")
    .trim()

// Summarize today's scheduled Breakfast, Lunch, and Dinner using human recipe titles (or slot IDs for EGM).
internal fun buildTodayMealPlanSummary(
  board: WeeklyBoard,
  recipes: List<Recipe>,
  todayDayIndex: Int,
  includeSlotIds: Boolean = false,
): String {
  val safeToday = ((todayDayIndex % 7) + 7) % 7
  val dayNames = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
  val dayLabel = dayNames.getOrElse(safeToday) { "Monday" }
  val recipeById = recipes.associateBy { it.recipeId }
  val slotParts = listOf(MealSlotType.BREAKFAST, MealSlotType.LUNCH, MealSlotType.DINNER).map { slotType ->
    val slot = board.slots.firstOrNull {
      it.dayIndex == safeToday && it.slotType == slotType && it.status != MealSlotStatus.COMPLETED_SKIPPED
    }
    val humanTitle = slot?.let { recipeById[it.recipeId]?.title } ?: "Unscheduled"
    val slotWord = slotType.name.lowercase().replaceFirstChar { it.uppercaseChar() }
    when {
      includeSlotIds && slot != null -> "${slotType.name}[slotId=${slot.slotId}, recipeId=${slot.recipeId}]='$humanTitle'"
      includeSlotIds -> "${slotType.name}=Unscheduled"
      else -> "$slotWord: $humanTitle"
    }
  }
  return "$dayLabel — ${slotParts.joinToString(" | ")}"
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
  todayDayIndex: Int = 0,
): AiMenuOptimizationProposal? {
  val rootObj = try {
    LENIENT_SALVAGE_JSON.parseToJsonElement(candidateJson) as? JsonObject
  } catch (_: SerializationException) {
    null
  } catch (_: IllegalArgumentException) {
    null
  } ?: return null

  val summary = SALVAGE_SUMMARY_KEYS.firstNotNullOfOrNull { key ->
    (rootObj[key] as? JsonPrimitive)?.contentOrNull?.let(::cleanHumanAssistantSummary)?.takeIf { it.isNotEmpty() }
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
      userPrompt = userPrompt,
      focusTag = focusTag,
      slotSwaps = validSwaps,
      candidateRecipes = filterSchedulableRecipes(recipes, catalogById, allergens, pantryById),
      catalog = catalog,
      pantry = pantry,
      effectiveAllergens = allergens,
      targetServings = householdProfile.effectiveTargetServings,
      board = board,
      preferredCuisines = householdProfile.preferredCuisines,
      todayDayIndex = todayDayIndex,
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
