package com.familykitchen.board.domain

import kotlin.math.min
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

// Pure deterministic Google ADK-style tool registry, bounded kitchen slice executors, adversarial
// SafetyConstraintAuditor gate, Gemma 4 / Cloud tool-call JSON parser, and Goldfish role router.
// Zero Android, java.io, java.net, or external SDK imports permitted inside domain/.

// Matches Gemma 4 native <|tool_call|>...<|/tool_call|> or <|tool_call|>...<|tool_call|> envelopes.
private val GEMMA_TOOL_CALL_TAG_REGEX: Regex =
  Regex("<\\|tool_call\\|>\\s*([\\s\\S]*?)(?:<\\|/tool_call\\|>|<\\|tool_call\\|>|$)", RegexOption.IGNORE_CASE)

// Matches Gemma 4 compact function-call syntax `call:tool_name{key:"val",num:2}`.
private val GEMMA_CALL_COLON_REGEX: Regex =
  Regex("^call:([a-z0-9_]+)\\s*\\{([\\s\\S]*)\\}$", RegexOption.IGNORE_CASE)

// Matches fenced ```tool_call ... ``` or ```json ... ``` blocks containing a tool invocation.
private val FENCED_TOOL_JSON_REGEX: Regex =
  Regex("```(?:tool_call|json)?\\s*(\\{[\\s\\S]*?\\})\\s*```", RegexOption.IGNORE_CASE)

// Pregnancy food-safety hazard phrases rejected by audit_candidate_meal_swaps when active.
private val PREGNANCY_UNSAFE_PREP_REGEX: Regex =
  Regex("\\b(runny\\s+yolks?|raw\\s+eggs?|unpasteurized|swordfish|king\\s+mackerel|shark|cold\\s+deli)\\b", RegexOption.IGNORE_CASE)

/**
 * Typed parameter schema for a single argument on an [AdkToolDeclaration].
 *
 * @property name Canonical JSON argument key.
 * @property type JSON Schema primitive/container type (`"string"`, `"integer"`, `"array"`).
 * @property description Concise explanation of how the Goldfish agent should populate [name].
 * @property required `true` when callers must supply [name].
 * @property enumValues Optional closed set of allowed string constants.
 */
@Serializable
data class AdkToolParameterSpec(
  val name: String,
  val type: String,
  val description: String,
  val required: Boolean = false,
  val enumValues: List<String> = emptyList(),
)

/**
 * Typed ADK-style `BaseTool` / `FunctionDeclaration` contract exposing a bounded SavorAI capability.
 *
 * @property name Stable snake_case tool identifier (`"get_expiring_pantry_slice"`, etc.).
 * @property description Single-sentence summary injected into allowed [GoldfishTaskBrief]s.
 * @property parameters Ordered typed parameter specifications.
 * @property isReadOnly `true` when executing the tool never mutates household state.
 * @property requiresUserConfirmation `true` when the tool's output must be confirmed by a human (`HITL`).
 */
@Serializable
data class AdkToolDeclaration(
  val name: String,
  val description: String,
  val parameters: List<AdkToolParameterSpec> = emptyList(),
  val isReadOnly: Boolean = true,
  val requiresUserConfirmation: Boolean = false,
) {
  /** Renders a token-thrifty `name(param: type): description` signature for Goldfish prompts. */
  fun toCompactSignature(): String {
    // Keep tool descriptions under ~18 tokens each so <=6 tools fit in <120 prompt tokens.
    val params = parameters.joinToString(", ") { "${it.name}${if (it.required) "" else "?"}:${it.type}" }
    return "$name($params) - $description"
  }
}

/**
 * Validated tool invocation parsed from Gemma 4 `<|tool_call|>`, fenced JSON, or test callers.
 *
 * @property toolName Target [AdkToolDeclaration.name] to invoke.
 * @property arguments String-encoded argument map (JSON arrays/objects serialized verbatim).
 */
@Serializable
data class AdkToolCall(
  val toolName: String,
  val arguments: Map<String, String> = emptyMap(),
)

/**
 * Candidate board swap pair (`slotId -> recipeId`) inspected by `audit_candidate_meal_swaps` and
 * packaged by `propose_meal_board_swaps`.
 *
 * @property slotId Target [MealSlot.slotId] on the active `WeeklyBoard`.
 * @property recipeId Candidate replacement [Recipe.recipeId].
 * @property rationale Optional culinary/nutritional explanation surfaced on proposal cards.
 */
@Serializable
data class CandidateMealSwapSpec(
  val slotId: String,
  val recipeId: String,
  val rationale: String = "",
)

/**
 * Immutable output returned by [executeKitchenDomainTool].
 *
 * @property toolName Invoked [AdkToolDeclaration.name].
 * @property isSuccess `true` when the tool executed cleanly without hard constraint rejection.
 * @property requiresUserConfirmation `true` when a human must confirm before mutating storage (`HITL`).
 * @property summaryText Concise human-readable summary of the returned slice or audit verdict.
 * @property payloadJson Compact JSON observation fed back into the stateless Goldfish synthesis pass.
 * @property safetyVerdict Populated on `audit_candidate_meal_swaps` and `propose_meal_board_swaps`.
 * @property proposedMenuOptimization Confirmation-gated proposal built by `propose_meal_board_swaps`.
 */
@Serializable
data class AdkToolResult(
  val toolName: String,
  val isSuccess: Boolean = true,
  val requiresUserConfirmation: Boolean = false,
  val summaryText: String,
  val payloadJson: String,
  val safetyVerdict: SafetyAuditVerdict? = null,
  val proposedMenuOptimization: AiMenuOptimizationProposal? = null,
)

// 8 bounded SavorAI v2.0 ADK tools replacing v1's monolithic full-snapshot system prompt dumps.
/** Canonical registry of the 8 bounded SavorAI v2.0 ADK tools. */
val ALL_KITCHEN_ADK_TOOL_DECLARATIONS: List<AdkToolDeclaration> = listOf(
  // 1. Urgent expiring Tier-1 items + PHP value at risk.
  AdkToolDeclaration(
    "get_expiring_pantry_slice",
    "Returns stocked Tier-1 items expiring within withinDays with expiresInDays and PHP value at risk.",
    listOf(AdkToolParameterSpec("withinDays", "integer", "Shelf-life horizon in days (default 3).")),
  ),
  // 2. Household member goals, allergens, child stage, and active life events.
  AdkToolDeclaration(
    "get_family_goals_and_events",
    "Returns HouseholdProfile member nutrition goals, allergens, child growth stage, and active life events.",
  ),
  // 3. Scheduled MealSlots resolved to actual Recipe.title and morning minutes.
  AdkToolDeclaration(
    "get_weekly_board_dishes",
    "Resolves scheduled MealSlots to actual Recipe titles, morning active minutes, and status.",
    listOf(
      AdkToolParameterSpec("dayIndex", "integer", "Optional weekday 0=Monday..6=Sunday."),
      AdkToolParameterSpec("slotType", "string", "Optional slot filter.", enumValues = listOf("BREAKFAST", "LUNCH", "DINNER")),
    ),
  ),
  // 4. Allergen-safe, locally available recipes honoring EC-01 <=25m cap & EC-03 substitutes.
  AdkToolDeclaration(
    "search_schedulable_recipes",
    "Searches allergen-safe, locally available recipes honoring <=25m breakfast cap and EC-03 substitutes.",
    listOf(
      AdkToolParameterSpec("slotType", "string", "Optional target MealSlotType.", enumValues = listOf("BREAKFAST", "LUNCH", "DINNER")),
      AdkToolParameterSpec("maxActiveMinutes", "integer", "Optional calibrated active minute ceiling."),
      AdkToolParameterSpec("mustUseIngredientId", "string", "Optional ingredientId to rescue."),
    ),
  ),
  // 5. Smart Grocery shortages honoring pack overrides, skipped trip items, and PHP cost.
  AdkToolDeclaration(
    "compute_smart_grocery_slice",
    "Computes Smart Grocery shortages honoring quantity overrides, skipped trip items, and PHP cost.",
    listOf(AdkToolParameterSpec("maxItems", "integer", "Max items to return (default 10).")),
  ),
  // 6. Adversarial critic gate auditing candidate swaps against EC-01, EC-02, EC-08, & life events.
  AdkToolDeclaration(
    "audit_candidate_meal_swaps",
    "Audits candidate slotId->recipeId swaps against EC-01, EC-02, EC-08, and activeLifeEvents safety rules.",
    listOf(AdkToolParameterSpec("swaps", "array", "JSON array of {slotId, recipeId, rationale} pairs.", required = true)),
  ),
  // 7. Human-in-the-Loop confirmation-gated proposal builder (0 direct snapshot mutation).
  AdkToolDeclaration(
    "propose_meal_board_swaps",
    "Builds a confirmation-gated meal swap proposal (requiresUserConfirmation=true, zero direct mutation).",
    listOf(
      AdkToolParameterSpec("swaps", "array", "JSON array of {slotId, recipeId, rationale} pairs.", required = true),
      AdkToolParameterSpec("summary", "string", "Warm culinary summary for the household."),
    ),
    isReadOnly = false,
    requiresUserConfirmation = true,
  ),
  // 8. External recipe / regional market lookup delegated at the shell layer to WebSearchToolPort.
  AdkToolDeclaration(
    "web_culinary_search",
    "Searches external culinary references or regional market prices via WebSearchToolPort when online.",
    listOf(
      AdkToolParameterSpec("query", "string", "Culinary or local market search query.", required = true),
      AdkToolParameterSpec("maxResults", "integer", "Max hits 1..5 (default 3)."),
    ),
  ),
)

/**
 * Deterministically routes a user prompt (and optional photo flag) to the narrowest specialist
 * [GoldfishAgentRole] so unrelated tools and Elephant facts stay out of the model's KV-cache.
 *
 * @param promptText Raw natural-language prompt entered by the user.
 * @param hasImageAttachment `true` when a camera capture or gallery photo is attached.
 * @return Target [GoldfishAgentRole].
 */
fun routePromptToGoldfishRole(
  promptText: String,
  hasImageAttachment: Boolean = false,
): GoldfishAgentRole {
  // Always route attached photos exclusively to VISION_INTAKE_SPECIALIST.
  if (hasImageAttachment) return GoldfishAgentRole.VISION_INTAKE_SPECIALIST
  val lower = promptText.trim().lowercase()
  // Dispatch narrow intents (audit -> grocery -> expiry -> technique -> default meal nutrition).
  return when {
    Regex("\\b(audit|verify\\s+safety|constraint\\s+check|allergen\\s+audit)\\b").containsMatchIn(lower) ->
      GoldfishAgentRole.SAFETY_CONSTRAINT_AUDITOR
    Regex("\\b(grocery|groceries|shop|shopping|palengke|budget|cost|price|how\\s+much\\s+to\\s+buy)\\b").containsMatchIn(lower) ->
      GoldfishAgentRole.GROCERY_BUDGET_SPECIALIST
    Regex("\\b(expir\\w*|spoil\\w*|going\\s+bad|use\\s+up|48h|waste|rescue)\\b").containsMatchIn(lower) ->
      GoldfishAgentRole.PANTRY_EXPIRY_SPECIALIST
    Regex("^(how\\s+(do|can|to|long)|why\\s+(did|does)|what\\s+(temp|internal))\\b").containsMatchIn(lower) ||
      Regex("\\b(sear|simmer|curdl\\w*|substitut\\w*|timer|doneness)\\b").containsMatchIn(lower) ->
      GoldfishAgentRole.COOK_COACH_SPECIALIST
    else -> GoldfishAgentRole.MENU_NUTRITION_SPECIALIST
  }
}

/**
 * Parses an ADK tool call emitted by Gemma 4 (`<|tool_call|>...<|/tool_call|>` or
 * `call:tool_name{...}`) or Cloud LLMs (fenced or bare `{"tool":"...","args":{...}}`), returning
 * `null` when [rawOutput] is a final prose or proposal response.
 *
 * @param rawOutput Raw text returned by `AiEdgePort.generateText`.
 * @return Parsed [AdkToolCall] if [rawOutput] invokes a registered tool, or `null` otherwise.
 */
fun parseModelToolCallOrNull(rawOutput: String): AdkToolCall? {
  if (rawOutput.isBlank()) return null
  val knownNames = ALL_KITCHEN_ADK_TOOL_DECLARATIONS.map { it.name }.toSet()

  // Branch 1: Explicit Gemma 4 <|tool_call|> tag wrapper (supports JSON or call:name{...}).
  val tagMatch = GEMMA_TOOL_CALL_TAG_REGEX.find(rawOutput)
  if (tagMatch != null) {
    val inner = tagMatch.groupValues[1].trim()
    parseCallColonSyntax(inner, knownNames)?.let { return it }
    parseToolJsonObject(inner, knownNames)?.let { return it }
  }

  // Branch 2: Sanitize reasoning blocks and check fenced or balanced JSON tool-call envelopes.
  val sanitized = sanitizeLlmRawOutput(rawOutput).trim()
  parseCallColonSyntax(sanitized, knownNames)?.let { return it }
  val fencedCandidate = FENCED_TOOL_JSON_REGEX.find(rawOutput)?.groupValues?.get(1)?.trim()
  if (fencedCandidate != null) {
    parseToolJsonObject(fencedCandidate, knownNames)?.let { return it }
  }
  return parseToolJsonObject(sanitized, knownNames)
}

/**
 * Deterministic + Critic safety auditor checking candidate `slotId -> recipeId` swaps against
 * grounded IDs, `EC-01` (`<= 25m` breakfast cap), `EC-02` (allergens & local availability),
 * `EC-08` (child prep tip presence), and active `HouseholdLifeEvent` food-safety rules.
 *
 * @param snapshot Read-only household domain state.
 * @param candidateSwaps Candidate slot-to-recipe swap specifications to verify.
 * @return Complete [SafetyAuditVerdict] (`APPROVED` or `REJECTED_NEEDS_REPAIR`).
 */
fun auditCandidateMealSwaps(
  snapshot: KitchenDomainToolState,
  candidateSwaps: List<CandidateMealSwapSpec>,
): SafetyAuditVerdict {
  if (candidateSwaps.isEmpty()) {
    return SafetyAuditVerdict(SafetyAuditStatus.APPROVED, emptyList(), 0, "Approved (0 candidate swaps submitted).")
  }

  val catalogById = indexCatalogById(snapshot.ingredients)
  val pantryById = indexPantryById(snapshot.pantryEntries)
  val recipeById = snapshot.recipes.associateBy { it.recipeId }
  val slotById = snapshot.weeklyBoard.slots.associateBy { it.slotId }
  val allergens = snapshot.householdProfile.householdAllergens
  val morningCap = min(MAX_WEEKDAY_BREAKFAST_MINUTES, snapshot.householdProfile.fredMorningCapMinutes.coerceAtLeast(1))
  val schedulablePool = filterSchedulableRecipes(snapshot.recipes, catalogById, allergens, pantryById)
  val safeBreakfastIds = schedulablePool
    .filter { MealSlotType.BREAKFAST in it.allowedSlots && computeCalibratedMorningMinutes(it) <= morningCap }
    .take(4).map { it.recipeId }

  val violations = mutableListOf<SafetyConstraintViolation>()

  // Evaluate every rule independently (never short-circuiting) so multi-fault drafts repair in 1 pass.
  for (candidate in candidateSwaps) {
    val slot = slotById[candidate.slotId.trim()]
    val recipe = recipeById[candidate.recipeId.trim()]
    if (slot == null || recipe == null) {
      violations.add(
        SafetyConstraintViolation(
          "UNGROUNDED_SLOT_OR_RECIPE",
          "Ungrounded swap slotId='${candidate.slotId}' or recipeId='${candidate.recipeId}'.",
          "Use only exact slotId values from get_weekly_board_dishes and recipeId values from search_schedulable_recipes.",
          candidate.slotId, candidate.recipeId,
        ),
      )
      continue
    }

    // Check 1 (EC-01): Enforce calibrated <= 25m morning cap on every BREAKFAST slot swap.
    val calibratedMinutes = computeCalibratedMorningMinutes(recipe)
    if (slot.slotType == MealSlotType.BREAKFAST && calibratedMinutes > morningCap) {
      val fallbackHint = safeBreakfastIds.joinToString(", ").ifEmpty { "rec_09_overnight_oats" }
      violations.add(
        SafetyConstraintViolation(
          "EC-01_BREAKFAST_CAP_EXCEEDED",
          "Swap on '${slot.slotId}' assigns '${recipe.title}' (${recipe.recipeId}) requiring ${calibratedMinutes}m (> ${morningCap}m EC-01 cap).",
          "Replace '${recipe.recipeId}' on '${slot.slotId}' with an eligible <= ${morningCap}m BREAKFAST recipe: $fallbackHint.",
          slot.slotId, recipe.recipeId,
        ),
      )
    }

    // Check 1b: Verify slot-type allowance when not already reported under EC-01 above.
    if (slot.slotType !in recipe.allowedSlots && !(slot.slotType == MealSlotType.BREAKFAST && calibratedMinutes > morningCap)) {
      val slotOptions = schedulablePool.filter { slot.slotType in it.allowedSlots }.take(3).map { it.recipeId }
      violations.add(
        SafetyConstraintViolation(
          "SLOT_TYPE_MISMATCH",
          "Recipe '${recipe.title}' (${recipe.recipeId}) allows ${recipe.allowedSlots} and cannot fill ${slot.slotType} slot '${slot.slotId}'.",
          "Select a ${slot.slotType} recipe (${slotOptions.joinToString(", ")}).",
          slot.slotId, recipe.recipeId,
        ),
      )
    }

    // Check 2 (EC-02): Enforce household allergen exclusions and local market availability.
    val availability = evaluateRecipeAvailability(recipe, catalogById, allergens, pantryById)
    if (!availability.isSchedulable) {
      val safeAlts = schedulablePool.filter { alt ->
        slot.slotType in alt.allowedSlots && (slot.slotType != MealSlotType.BREAKFAST || computeCalibratedMorningMinutes(alt) <= morningCap)
      }.take(3).map { it.recipeId }
      val code = if (availability.reason == AvailabilityReason.ALLERGEN_BLOCKED) "EC-02_ALLERGEN_BLOCKED" else "EC-02_INGREDIENT_UNAVAILABLE"
      val detail = if (availability.blockedAllergens.isNotEmpty()) {
        "allergen(s) ${availability.blockedAllergens.joinToString(", ")} via ${availability.blockedIngredientIds}"
      } else {
        "unavailable ingredient(s) ${availability.blockedIngredientIds.joinToString(", ")}"
      }
      violations.add(
        SafetyConstraintViolation(
          code,
          "Recipe '${recipe.title}' (${recipe.recipeId}) on '${slot.slotId}' is blocked by $detail.",
          "Choose an allergen-safe, locally available ${slot.slotType} recipe: ${safeAlts.joinToString(", ").ifEmpty { "none" }}.",
          slot.slotId, recipe.recipeId,
        ),
      )
    }

    // Check 3 (EC-08): Require non-blank child texture/choking safety instructions.
    if (recipe.toddlerPrepTip.isNullOrBlank()) {
      violations.add(
        SafetyConstraintViolation(
          "EC-08_MISSING_CHILD_PREP_TIP",
          "Recipe '${recipe.title}' (${recipe.recipeId}) lacks a required toddlerPrepTip for ${snapshot.householdProfile.youngestStage.shortLabel}.",
          "Select a family recipe carrying an explicit stage-safe toddlerPrepTip.",
          slot.slotId, recipe.recipeId,
        ),
      )
    }

    // Check 4: Enforce active HouseholdLifeEvent food-safety guardrails (pregnancy Listeria/Salmonella).
    val hasPregnancyEvent = snapshot.householdProfile.activeLifeEvents.any { ev ->
      ev.eventId.contains("pregnancy", ignoreCase = true) || Regex("pregnan|trimester", RegexOption.IGNORE_CASE).containsMatchIn(ev.title)
    }
    if (hasPregnancyEvent) {
      val combinedSteps = "${recipe.title} ${recipe.chefNote.orEmpty()} ${recipe.morningSteps.joinToString(" ") { it.instruction }}"
      val unsafeMatch = PREGNANCY_UNSAFE_PREP_REGEX.find(combinedSteps)
      if (unsafeMatch != null) {
        violations.add(
          SafetyConstraintViolation(
            "LIFE_EVENT_FOOD_SAFETY",
            "Recipe '${recipe.title}' contains pregnancy-unsafe preparation '${unsafeMatch.value}'.",
            "Cook eggs firm, heat poultry to 74C, and use only cooked low-mercury fish (63C).",
            slot.slotId, recipe.recipeId,
          ),
        )
      }
    }
  }

  val status = if (violations.isEmpty()) SafetyAuditStatus.APPROVED else SafetyAuditStatus.REJECTED_NEEDS_REPAIR
  val summary = when (status) {
    SafetyAuditStatus.APPROVED ->
      "Approved all ${candidateSwaps.size} candidate meal swap(s) against EC-01, EC-02, EC-08, and life events."
    SafetyAuditStatus.REJECTED_NEEDS_REPAIR ->
      "Rejected ${candidateSwaps.size} candidate swap batch with ${violations.size} violation(s): " +
        violations.joinToString("; ") { "${it.code} (${it.slotId}->${it.recipeId})" }
  }
  return SafetyAuditVerdict(status, violations, candidateSwaps.size, summary)
}

/**
 * Pure deterministic executor running any of the **8 bounded SavorAI ADK tools** against an
 * immutable [KitchenDomainToolState] and [HouseholdElephantMemory] with zero direct state mutation.
 *
 * @param snapshot Current immutable household state (`KitchenSnapshot` implements [KitchenDomainToolState]).
 * @param elephantMemory Bounded household Elephant memory (defaults to [KitchenDomainToolState.elephantMemory]).
 * @param call Parsed [AdkToolCall] naming the target tool and arguments.
 * @return Immutable [AdkToolResult] carrying compact JSON observations and optional HITL proposals.
 */
fun executeKitchenDomainTool(
  snapshot: KitchenDomainToolState,
  elephantMemory: HouseholdElephantMemory = snapshot.elephantMemory,
  call: AdkToolCall,
): AdkToolResult =
  when (call.toolName.trim()) {
    "get_expiring_pantry_slice" -> executeExpiringPantrySlice(snapshot, call)
    "get_family_goals_and_events" -> executeFamilyGoalsAndEvents(snapshot, elephantMemory)
    "get_weekly_board_dishes" -> executeWeeklyBoardDishes(snapshot, call)
    "search_schedulable_recipes" -> executeSearchSchedulableRecipes(snapshot, call)
    "compute_smart_grocery_slice" -> executeSmartGrocerySlice(snapshot, call)
    "audit_candidate_meal_swaps" -> executeAuditCandidateSwapsTool(snapshot, call)
    "propose_meal_board_swaps" -> executeProposeMealBoardSwapsTool(snapshot, call)
    "web_culinary_search" -> executePureDomainWebSearchPlaceholder(call)
    else -> AdkToolResult(
      toolName = call.toolName,
      isSuccess = false,
      summaryText = "Unknown ADK kitchen tool '${call.toolName}'.",
      payloadJson = """{"error":"unknown_tool","tool":"${escapeJsonText(call.toolName)}"}""",
    )
  }

// Tool 1: Urgent expiring Tier-1 pantry items with exact expiresInDays and PHP (₱) value at risk.
private fun executeExpiringPantrySlice(snapshot: KitchenDomainToolState, call: AdkToolCall): AdkToolResult {
  val withinDays = call.arguments["withinDays"]?.toIntOrNull()?.coerceIn(0, 14) ?: 3
  val currency = snapshot.householdProfile.currency
  val catalogById = indexCatalogById(snapshot.ingredients)
  val dashboard = evaluatePantryExpiryDashboard(
    snapshot.pantryEntries, snapshot.ingredients, snapshot.recipes,
    snapshot.householdProfile.householdAllergens, snapshot.rescuedExpiringMealsCount,
  )
  val dashboardById = dashboard.expiringItems.associateBy { it.entry.ingredientId }

  // Include both urgent <=2d dashboard cards and any stocked entries expiring within withinDays.
  val matchingEntries = snapshot.pantryEntries.filter { entry ->
    val days = entry.expiresInDays
    days != null && days <= withinDays && !entry.isOutOfStock &&
      (entry.tier == IngredientTier.TIER_2_STAPLE || entry.currentQuantity > 0.0)
  }.sortedBy { it.expiresInDays ?: Int.MAX_VALUE }

  val payloadObj = buildJsonObject {
    put("withinDays", withinDays)
    put("totalAtRiskFormatted", dashboard.formattedAtRiskValue(currency))
    put("wasteScorePercent", dashboard.wasteScorePercent)
    putJsonArray("expiringItems") {
      for (entry in matchingEntries) {
        val item = catalogById[entry.ingredientId]
        val card = dashboardById[entry.ingredientId]
        val atRisk = card?.formattedAtRiskValue(currency) ?: item?.formattedPackagePrice(currency) ?: currency.formatAmount(0.0)
        add(
          buildJsonObject {
            put("ingredientId", entry.ingredientId)
            put("displayName", item?.displayName ?: entry.ingredientId)
            put("expiresInDays", entry.expiresInDays ?: 0)
            put("urgencyLabel", entry.urgencyBadgeLabel)
            put("quantity", entry.currentQuantity)
            put("unit", item?.baseUnit?.wireCode ?: "unit")
            put("atRiskValue", atRisk)
            putJsonArray("rescueRecipeIds") {
              card?.matchingRescueRecipes?.take(3)?.forEach { add(JsonPrimitive(it.recipeId)) }
            }
          },
        )
      }
    }
  }
  val labels = matchingEntries.joinToString(", ") { "${catalogById[it.ingredientId]?.displayName ?: it.ingredientId} (${it.expiresInDays}d)" }.ifEmpty { "none" }
  return AdkToolResult(
    toolName = "get_expiring_pantry_slice",
    summaryText = "Found ${matchingEntries.size} expiring item(s) within ${withinDays}d ($labels; at risk: ${dashboard.formattedAtRiskValue(currency)}).",
    payloadJson = payloadObj.toString(),
  )
}

// Tool 2: Member nutrition goals, preferred cuisines, market region, allergens, and life events.
private fun executeFamilyGoalsAndEvents(snapshot: KitchenDomainToolState, elephantMemory: HouseholdElephantMemory): AdkToolResult {
  val profile = snapshot.householdProfile
  val memberFacts = selectRelevantElephantFacts(elephantMemory, GoldfishAgentRole.MENU_NUTRITION_SPECIALIST, maxFacts = 3)
  val payloadObj = buildJsonObject {
    put("householdName", profile.householdName)
    put("marketRegionLabel", profile.marketRegionLabel)
    put("effectiveTargetServings", profile.effectiveTargetServings)
    put("fredMorningCapMinutes", profile.fredMorningCapMinutes)
    put("youngestStage", profile.youngestStage.name)
    put("youngestPrepGuidance", profile.youngestStage.prepGuidance)
    putJsonArray("preferredCuisines") { profile.preferredCuisines.forEach { add(JsonPrimitive(it)) } }
    putJsonArray("householdAllergens") { profile.householdAllergens.forEach { add(JsonPrimitive(it)) } }
    putJsonArray("members") {
      for (m in profile.members) {
        add(
          buildJsonObject {
            put("memberId", m.memberId)
            put("memberName", m.memberName)
            put("focusGoal", m.focusGoal)
            m.ageYears?.let { put("ageYears", it) }
            m.growthStage?.let { put("growthStage", it.name) }
          },
        )
      }
    }
    putJsonArray("activeLifeEvents") {
      for (ev in profile.activeLifeEvents) {
        add(
          buildJsonObject {
            put("eventId", ev.eventId)
            put("memberId", ev.memberId)
            put("title", ev.title)
            put("servingsDelta", ev.servingsDelta)
            putJsonArray("safetyPrepRules") { ev.safetyPrepRules.forEach { add(JsonPrimitive(it)) } }
          },
        )
      }
    }
    putJsonArray("elephantHabitFacts") { memberFacts.forEach { add(JsonPrimitive(it.summary)) } }
  }
  return AdkToolResult(
    toolName = "get_family_goals_and_events",
    summaryText = "${profile.members.size} member goal(s), cuisines=${profile.preferredCuisines}, market=${profile.marketRegionLabel}, allergens=${profile.householdAllergens}.",
    payloadJson = payloadObj.toString(),
  )
}

// Tool 3: Resolves scheduled weekly MealSlots to actual Recipe.title, calibrated minutes, & status.
private fun executeWeeklyBoardDishes(snapshot: KitchenDomainToolState, call: AdkToolCall): AdkToolResult {
  val filterDay = call.arguments["dayIndex"]?.toIntOrNull()
  val filterSlot = call.arguments["slotType"]?.trim()?.uppercase()?.let { raw -> MealSlotType.entries.find { it.name == raw } }
  val recipeById = snapshot.recipes.associateBy { it.recipeId }
  val dayNames = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

  // Sort chronologically so Monday Breakfast..Sunday Dinner reads deterministically.
  val matchedSlots = sortSlotsChronologically(snapshot.weeklyBoard.slots).filter { slot ->
    (filterDay == null || slot.dayIndex == filterDay) && (filterSlot == null || slot.slotType == filterSlot)
  }
  val payloadObj = buildJsonObject {
    put("boardId", snapshot.weeklyBoard.boardId)
    put("slotCount", matchedSlots.size)
    putJsonArray("scheduledDishes") {
      for (slot in matchedSlots) {
        val recipe = recipeById[slot.recipeId]
        add(
          buildJsonObject {
            put("slotId", slot.slotId)
            put("dayIndex", slot.dayIndex)
            put("dayName", dayNames.getOrElse(slot.dayIndex) { "Weekday" })
            put("slotType", slot.slotType.name)
            put("status", slot.status.name)
            put("recipeId", slot.recipeId)
            put("recipeTitle", recipe?.title ?: slot.recipeId)
            put("calibratedActiveMinutes", recipe?.let(::computeCalibratedMorningMinutes) ?: 0)
            put("targetServings", slot.targetServings)
          },
        )
      }
    }
  }
  val preview = matchedSlots.take(3).joinToString(", ") { "${it.slotId}=${recipeById[it.recipeId]?.title ?: it.recipeId}" }
  return AdkToolResult("get_weekly_board_dishes", summaryText = "Resolved ${matchedSlots.size} board slot(s) ($preview).", payloadJson = payloadObj.toString())
}

// Tool 4: Searches schedulable recipes enforcing EC-01/EC-02/EC-03 and ranking preferred cuisines + rescues first.
private fun executeSearchSchedulableRecipes(snapshot: KitchenDomainToolState, call: AdkToolCall): AdkToolResult {
  val requestedSlot = call.arguments["slotType"]?.trim()?.uppercase()?.let { raw -> MealSlotType.entries.find { it.name == raw } }
  val explicitMaxMin = call.arguments["maxActiveMinutes"]?.toIntOrNull()
  val mustUseId = call.arguments["mustUseIngredientId"]?.trim()?.takeIf { it.isNotEmpty() }
  val limit = call.arguments["limit"]?.toIntOrNull()?.coerceIn(1, 20) ?: 10
  val profile = snapshot.householdProfile
  val morningCap = min(MAX_WEEKDAY_BREAKFAST_MINUTES, profile.fredMorningCapMinutes.coerceAtLeast(1))
  val catalogById = indexCatalogById(snapshot.ingredients)
  val pantryById = indexPantryById(snapshot.pantryEntries)

  // Strictly enforce EC-02 allergen/local-market gate and EC-03 substituteGroupId BOM rewrites.
  val filtered = snapshot.recipes.mapNotNull { rawRecipe ->
    val availability = evaluateRecipeAvailability(rawRecipe, catalogById, profile.householdAllergens, pantryById)
    if (!availability.isSchedulable) return@mapNotNull null
    val resolved = rawRecipe.copy(bomLines = availability.resolvedBomLines)
    val calibrated = computeCalibratedMorningMinutes(resolved)
    val slotOk = requestedSlot == null || (requestedSlot in resolved.allowedSlots && (requestedSlot != MealSlotType.BREAKFAST || calibrated <= morningCap))
    val timeOk = explicitMaxMin == null || calibrated <= explicitMaxMin
    val ingOk = mustUseId == null || resolved.bomLines.any { it.ingredientId == mustUseId }
    if (!slotOk || !timeOk || !ingOk) return@mapNotNull null
    val match = evaluateRecipePantryMatch(resolved, snapshot.pantryEntries, snapshot.ingredients, profile.effectiveTargetServings, profile.householdAllergens)
    val cuisineHit = doesRecipeMatchPreferredCuisines(resolved, profile.preferredCuisines)
    val nativeLocalBonus = if (availability.substitutionsApplied.isEmpty()) 1 else 0
    Triple(resolved, match, (if (cuisineHit) 50 else 0) + (if (match.rescuedExpiringIngredientIds.isNotEmpty()) 35 else 0) + nativeLocalBonus)
  }.sortedWith(
    compareByDescending<Triple<Recipe, RecipePantryMatchSummary, Int>> { it.third }
      .thenByDescending { it.second.rescuedExpiringIngredientIds.size }
      .thenByDescending { it.second.matchPercentage }
      .thenBy { computeCalibratedMorningMinutes(it.first) },
  ).take(limit)

  val payloadObj = buildJsonObject {
    put("count", filtered.size)
    putJsonArray("recipes") {
      for ((recipe, match, _) in filtered) {
        add(
          buildJsonObject {
            put("recipeId", recipe.recipeId)
            put("title", recipe.title)
            put("matchesPreferredCuisine", doesRecipeMatchPreferredCuisines(recipe, profile.preferredCuisines))
            put("calibratedActiveMinutes", computeCalibratedMorningMinutes(recipe))
            put("pantryMatchPercentage", match.matchPercentage)
            put("proteinGrams", recipe.proteinGrams)
            put("toddlerPrepTip", recipe.toddlerPrepTip.orEmpty())
            putJsonArray("allowedSlots") { recipe.allowedSlots.forEach { add(JsonPrimitive(it.name)) } }
          },
        )
      }
    }
  }
  val names = filtered.joinToString(", ") { "${it.first.recipeId} (${it.first.title})" }
  return AdkToolResult("search_schedulable_recipes", summaryText = "Matched ${filtered.size} schedulable recipe(s): $names.", payloadJson = payloadObj.toString())
}

// Tool 5: Smart Grocery diff honoring buy-quantity overrides, skipped trip items, & PHP (₱) cost.
private fun executeSmartGrocerySlice(snapshot: KitchenDomainToolState, call: AdkToolCall): AdkToolResult {
  val maxItems = call.arguments["maxItems"]?.toIntOrNull()?.coerceIn(1, 30) ?: 10
  val currency = snapshot.householdProfile.currency
  val diff = computeWeeklyGroceryDiff(
    snapshot.weeklyBoard, snapshot.recipes, snapshot.ingredients, snapshot.pantryEntries,
    snapshot.householdProfile.householdAllergens, snapshot.aiAddedGroceryIds, snapshot.checkedGroceryItemIds,
    snapshot.grocerySortMode, snapshot.groceryQuantityOverrides, snapshot.dismissedGroceryItemIds,
  )
  val totalFormatted = diff.formattedEstimatedTotalCost(currency)
  val savingsFormatted = diff.formattedDeduplicationSavings(currency)
  val payloadObj = buildJsonObject {
    put("totalItems", diff.groceryItems.size)
    put("dismissedTripItemsCount", snapshot.dismissedGroceryItemIds.size)
    put("estimatedTotalCost", totalFormatted)
    put("deduplicationSavings", savingsFormatted)
    putJsonArray("items") {
      for (item in diff.groceryItems.take(maxItems)) {
        add(
          buildJsonObject {
            put("ingredientId", item.ingredientId)
            put("displayName", item.displayName)
            put("packageDescriptor", item.packageDescriptor)
            put("restockQuantity", item.restockQuantity)
            put("unit", item.baseUnit.wireCode)
            put("isQuantityOverridden", item.isQuantityOverridden)
            put("estimatedCost", item.formattedEstimatedCost(currency))
          },
        )
      }
    }
  }
  return AdkToolResult(
    toolName = "compute_smart_grocery_slice",
    summaryText = "Smart Grocery basket has ${diff.groceryItems.size} item(s) totaling $totalFormatted (saved $savingsFormatted).",
    payloadJson = payloadObj.toString(),
  )
}

// Tool 6: Deterministic + Critic Safety Gate returning SafetyAuditVerdict.
private fun executeAuditCandidateSwapsTool(snapshot: KitchenDomainToolState, call: AdkToolCall): AdkToolResult {
  val candidateSwaps = parseCandidateSwapList(call.arguments["swaps"].orEmpty())
  val verdict = auditCandidateMealSwaps(snapshot, candidateSwaps)
  val payloadJson = SchemaValidator.strictJson.encodeToString(SafetyAuditVerdict.serializer(), verdict)
  return AdkToolResult("audit_candidate_meal_swaps", verdict.isApproved, false, verdict.auditorSummary, payloadJson, verdict)
}

// Tool 7: HITL confirmation-gated proposal builder (zero direct snapshot mutation).
private fun executeProposeMealBoardSwapsTool(snapshot: KitchenDomainToolState, call: AdkToolCall): AdkToolResult {
  val candidateSwaps = parseCandidateSwapList(call.arguments["swaps"].orEmpty())
  // Always gate proposal assembly through auditCandidateMealSwaps before building HITL cards.
  val verdict = auditCandidateMealSwaps(snapshot, candidateSwaps)
  if (!verdict.isApproved) {
    val verdictJson = SchemaValidator.strictJson.encodeToString(SafetyAuditVerdict.serializer(), verdict)
    return AdkToolResult("propose_meal_board_swaps", false, true, verdict.auditorSummary, verdictJson, verdict, null)
  }

  val summaryArg = call.arguments["summary"]?.trim()?.takeIf { it.isNotEmpty() }
    ?: "Prepared ${candidateSwaps.size} safety-audited family meal swap(s) awaiting your confirmation."
  val compactJson = buildJsonObject {
    put("focusTag", NutritionFocusTag.KIDS_BRAIN_BOOSTER.name)
    put("assistantSummary", summaryArg)
    put("isConversationalOnly", false)
    putJsonArray("suggestedSwaps") {
      for (swap in candidateSwaps) {
        add(
          buildJsonObject {
            put("slotId", swap.slotId)
            put("recipeId", swap.recipeId)
            if (swap.rationale.isNotBlank()) put("rationale", swap.rationale)
          },
        )
      }
    }
  }.toString()

  val grounded = parseAndGroundAiResponse(
    compactJson, summaryArg, snapshot.weeklyBoard, snapshot.recipes,
    snapshot.ingredients, snapshot.pantryEntries, snapshot.householdProfile,
  )
  val payloadObj = buildJsonObject {
    put("proposalId", grounded.proposalId)
    put("requiresUserConfirmation", true)
    put("swapCount", grounded.slotSwaps.size)
    put("assistantSummary", grounded.assistantSummary)
  }
  return AdkToolResult("propose_meal_board_swaps", true, true, grounded.assistantSummary, payloadObj.toString(), verdict, grounded)
}

// Tool 8: Pure domain placeholder when web_culinary_search is inspected without WebSearchToolPort.
private fun executePureDomainWebSearchPlaceholder(call: AdkToolCall): AdkToolResult {
  val offlineResult = WebSearchToolResult(
    outcome = WebSearchToolOutcome.DISABLED_OFFLINE_MODE,
    query = call.arguments["query"]?.trim().orEmpty(),
    hits = emptyList(),
    diagnosticMessage = "Delegate web_culinary_search to WebSearchToolPort at the shell layer (offline by default).",
  )
  return AdkToolResult(
    "web_culinary_search", false, false, offlineResult.diagnosticMessage,
    SchemaValidator.strictJson.encodeToString(WebSearchToolResult.serializer(), offlineResult),
  )
}

// Parse JSON array `[{"slotId":"...","recipeId":"..."}]` or compact `"slotId=recipeId,..."` pairs.
internal fun parseCandidateSwapList(rawSwapsArg: String): List<CandidateMealSwapSpec> {
  val trimmed = rawSwapsArg.trim()
  if (trimmed.isEmpty()) return emptyList()
  if (trimmed.startsWith("[")) {
    val parsedArray = try {
      SchemaValidator.strictJson.parseToJsonElement(trimmed) as? JsonArray
    } catch (_: SerializationException) {
      null
    }
    if (parsedArray != null) {
      return parsedArray.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val slotId = (obj["slotId"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val recipeId = (obj["recipeId"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val rationale = (obj["rationale"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (slotId.isNotEmpty() && recipeId.isNotEmpty()) CandidateMealSwapSpec(slotId, recipeId, rationale) else null
      }
    }
  }
  return trimmed.split(',', ';').mapNotNull { pairToken ->
    val parts = pairToken.split('=', ':').map { it.trim() }
    if (parts.size >= 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) CandidateMealSwapSpec(parts[0], parts[1]) else null
  }
}

// Decode {"tool":"...","args":{...}} while ignoring final {"assistantSummary":"..."} proposal JSON.
private fun parseToolJsonObject(candidateText: String, knownNames: Set<String>): AdkToolCall? {
  val start = candidateText.indexOf('{')
  val end = candidateText.lastIndexOf('}')
  if (start < 0 || end <= start) return null
  val obj = try {
    SchemaValidator.strictJson.parseToJsonElement(candidateText.substring(start, end + 1)) as? JsonObject
  } catch (_: SerializationException) {
    return null
  } ?: return null

  val rawName = listOf("tool", "toolName", "name")
    .firstNotNullOfOrNull { key -> (obj[key] as? JsonPrimitive)?.contentOrNull?.trim() } ?: return null
  if (rawName !in knownNames) return null

  val argsMap = linkedMapOf<String, String>()
  (obj["args"] ?: obj["arguments"] ?: obj["parameters"])?.let { argsEl ->
    if (argsEl is JsonObject) argsEl.forEach { (k, v) -> argsMap[k] = jsonElementToArgString(v) }
  }
  return AdkToolCall(toolName = rawName, arguments = argsMap)
}

// Parse Gemma 4 native `call:tool_name{withinDays:2}` compact function syntax.
private fun parseCallColonSyntax(rawInner: String, knownNames: Set<String>): AdkToolCall? {
  val match = GEMMA_CALL_COLON_REGEX.matchEntire(rawInner.trim()) ?: return null
  val toolName = match.groupValues[1].trim()
  if (toolName !in knownNames) return null
  val body = match.groupValues[2].trim()
  if (body.isEmpty()) return AdkToolCall(toolName = toolName)
  val argsMap = linkedMapOf<String, String>()
  for (entry in body.split(',')) {
    val idx = entry.indexOf(':')
    if (idx > 0) {
      val key = entry.substring(0, idx).trim().trim('"', '\'')
      val value = entry.substring(idx + 1).trim().trim('"', '\'')
      if (key.isNotEmpty()) argsMap[key] = value
    }
  }
  return AdkToolCall(toolName = toolName, arguments = argsMap)
}

private fun jsonElementToArgString(element: JsonElement): String =
  when (element) {
    is JsonPrimitive -> element.contentOrNull ?: element.toString()
    else -> element.toString()
  }

private fun escapeJsonText(raw: String): String = raw.replace("\\", "\\\\").replace("\"", "\\\"")

/** Object facade exposing SavorAI's 8 bounded ADK tools, parser, safety critic, and role router. */
object AdkKitchenToolsEngine {
  /** Returns all 8 typed [AdkToolDeclaration] contracts in SavorAI v2.0. */
  fun allToolDeclarations(): List<AdkToolDeclaration> = ALL_KITCHEN_ADK_TOOL_DECLARATIONS

  /** Returns only the [AdkToolDeclaration]s permitted for [role]. */
  fun declarationsForRole(role: GoldfishAgentRole): List<AdkToolDeclaration> {
    val allowed = role.defaultAllowedTools.toSet()
    return ALL_KITCHEN_ADK_TOOL_DECLARATIONS.filter { it.name in allowed }
  }

  /** Delegates to top-level [com.familykitchen.board.domain.executeKitchenDomainTool]. */
  fun executeKitchenDomainTool(
    snapshot: KitchenDomainToolState,
    elephantMemory: HouseholdElephantMemory = snapshot.elephantMemory,
    call: AdkToolCall,
  ): AdkToolResult = com.familykitchen.board.domain.executeKitchenDomainTool(snapshot, elephantMemory, call)

  /** Delegates to top-level [com.familykitchen.board.domain.auditCandidateMealSwaps]. */
  fun auditCandidateMealSwaps(
    snapshot: KitchenDomainToolState,
    candidateSwaps: List<CandidateMealSwapSpec>,
  ): SafetyAuditVerdict = com.familykitchen.board.domain.auditCandidateMealSwaps(snapshot, candidateSwaps)

  /** Delegates to top-level [com.familykitchen.board.domain.parseModelToolCallOrNull]. */
  fun parseModelToolCallOrNull(rawOutput: String): AdkToolCall? =
    com.familykitchen.board.domain.parseModelToolCallOrNull(rawOutput)

  /** Delegates to top-level [com.familykitchen.board.domain.routePromptToGoldfishRole]. */
  fun routePromptToGoldfishRole(promptText: String, hasImageAttachment: Boolean = false): GoldfishAgentRole =
    com.familykitchen.board.domain.routePromptToGoldfishRole(promptText, hasImageAttachment)
}
