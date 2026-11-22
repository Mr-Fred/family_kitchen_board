package com.familykitchen.board.domain

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

// Pure Kotlin domain contracts and deterministic distillation functions for SavorAI v2.0's
// Elephant & Goldfish Model (EGM) paired with Google ADK for Kotlin graph orchestration.
// Imports ONLY Kotlin stdlib and kotlinx.serialization (zero Android, java.io, or SDK imports).

/** Maximum semantic facts retained in [HouseholdElephantMemory] before oldest low-use pruning. */
const val DEFAULT_MAX_ELEPHANT_FACTS: Int = 32

/** Maximum compressed weekly episodic digests retained in [HouseholdElephantMemory]. */
const val DEFAULT_MAX_EPISODIC_DIGESTS: Int = 8

/** Hard ceiling of semantic facts injected into any single stateless [GoldfishTaskBrief]. */
const val MAX_GOLDFISH_FACT_SLICE: Int = 5

/**
 * Pure domain read-only projection of persisted household kitchen state consumed by deterministic
 * ADK tools in `AdkKitchenToolsEngine` without coupling `domain/` to `ports/`.
 */
interface KitchenDomainToolState {
  /** Active household ingredient catalog definitions. */
  val ingredients: List<IngredientCatalogItem>

  /** Active household recipe library including learned `householdBomOverrides`. */
  val recipes: List<Recipe>

  /** Current two-tier household pantry stock entries. */
  val pantryEntries: List<PantryLedgerEntry>

  /** Active 7-day (21-slot) household weekly meal board. */
  val weeklyBoard: WeeklyBoard

  /** Household member goals, child stage, allergens, life events, and `PHP` (`₱`) currency. */
  val householdProfile: HouseholdProfile

  /** Manually queued or AI-suggested Smart Grocery catalog IDs. */
  val aiAddedGroceryIds: Set<String>

  /** Checked catalog ingredient IDs on the Smart Grocery List. */
  val checkedGroceryItemIds: Set<String>

  /** Active Smart Grocery List sort strategy. */
  val grocerySortMode: GrocerySortMode

  /** Historical rescued expiring meals counter driving Waste Score `%`. */
  val rescuedExpiringMealsCount: Int

  /** Shopper buy-quantity overrides in canonical [BaseUnit] keyed by `ingredientId`. */
  val groceryQuantityOverrides: Map<String, Double>

  /** Catalog `ingredientId`s skipped for the current shopping trip. */
  val dismissedGroceryItemIds: Set<String>

  /** Bounded long-term semantic and episodic Elephant memory for the household. */
  val elephantMemory: HouseholdElephantMemory
}

/**
 * Semantic classification categories stored inside [HouseholdElephantMemory].
 *
 * Keeps durable household preferences partitioned so each [GoldfishAgentRole] receives only the
 * categories relevant to its narrow culinary task.
 */
@Serializable
enum class ElephantFactCategory {
  // Local market & household ingredient renames (e.g., "Fresh Amaranth Greens" -> "Fresh Kulitis").
  /** Familiar local or household alias learned when renaming catalog ingredients. */
  INGREDIENT_ALIAS,

  // Shopper quantity/pack overrides (e.g., overriding 4 kg weekly chicken down to 2 kg).
  /** Preferred market purchase size or pack count learned from Smart Grocery edits. */
  PACK_SIZE_PREFERENCE,

  // Per-member macro, satiety, hypertrophy, or life-event dietary habits.
  /** Durable per-member nutrition, athletic recovery, or life-stage feeding habit. */
  MEMBER_NUTRITION_HABIT,

  // Morning <= 25m rush patterns, skipped leftover slots, and batch-prep cadence.
  /** Household cooking schedule, leftover frequency, and weekday morning pacing habit. */
  COOKING_CADENCE,

  // Learned recipe BOM ratio defaults (EC-05) and child texture-safety preferences (EC-08).
  /** Household flavor ratios, saved BOM quantity overrides, and child texture preferences. */
  TASTE_AND_TEXTURE,
}

/**
 * Compact, structured semantic fact stored in [HouseholdElephantMemory].
 *
 * @property factId Deterministic identifier keyed by category and subject for `O(1)` upserts.
 * @property category Semantic domain category used to slice facts per [GoldfishAgentRole].
 * @property subjectId Referenced `ingredientId`, `recipeId`, `memberId`, `slotId`, or `"HOUSEHOLD"`.
 * @property summary Concise single-sentence fact injected into matching Goldfish briefs.
 * @property confidence Normalized certainty score clamped in `0.0..1.0`.
 * @property reinforcementCount Number of times household actions reinforced this fact (`>= 1`).
 * @property updatedAtIso ISO-8601 timestamp of the latest observation.
 */
@Serializable
data class ElephantSemanticFact(
  val factId: String,
  val category: ElephantFactCategory,
  val subjectId: String,
  val summary: String,
  val confidence: Double = 1.0,
  val reinforcementCount: Int = 1,
  val updatedAtIso: String = DEFAULT_TIMESTAMP_ISO,
)

/**
 * Compressed weekly or session-level episodic summary retained by [HouseholdElephantMemory].
 *
 * @property digestId Unique identifier for the episodic rollup window.
 * @property weekStartDateIso ISO-8601 Monday start date of the summarized week.
 * @property cookedMealsCount Count of planned slots completed via `applyCookedDeduction`.
 * @property skippedSlotsCount Count of planned slots marked `COMPLETED_SKIPPED` (`EC-12`).
 * @property rescuedExpiringItemsCount Count of expiring perishables rescued before spoilage.
 * @property highlightSummary One-line human-readable digest of weekly kitchen patterns.
 * @property createdAtIso ISO-8601 creation timestamp.
 */
@Serializable
data class ElephantEpisodicDigest(
  val digestId: String,
  val weekStartDateIso: String,
  val cookedMealsCount: Int = 0,
  val skippedSlotsCount: Int = 0,
  val rescuedExpiringItemsCount: Int = 0,
  val highlightSummary: String,
  val createdAtIso: String = DEFAULT_TIMESTAMP_ISO,
)

/**
 * Bounded long-term household memory ("The Elephant" in Dave Rensin's Elephant & Goldfish Model).
 *
 * Never dumped raw into an LLM prompt; callers slice at most [MAX_GOLDFISH_FACT_SLICE] (`5`)
 * role-matched [ElephantSemanticFact]s per task via [selectRelevantElephantFacts].
 *
 * @property semanticFacts Deduplicated semantic facts ordered from most recently reinforced.
 * @property episodicDigests Bounded weekly episodic rollups (`<= maxEpisodicDigests`).
 * @property maxSemanticFacts Maximum semantic facts retained (`32` default).
 * @property maxEpisodicDigests Maximum episodic digests retained (`8` default).
 * @property updatedAtIso ISO-8601 timestamp of the latest distillation update.
 */
@Serializable
data class HouseholdElephantMemory(
  val semanticFacts: List<ElephantSemanticFact> = emptyList(),
  val episodicDigests: List<ElephantEpisodicDigest> = emptyList(),
  val maxSemanticFacts: Int = DEFAULT_MAX_ELEPHANT_FACTS,
  val maxEpisodicDigests: Int = DEFAULT_MAX_EPISODIC_DIGESTS,
  val updatedAtIso: String = DEFAULT_TIMESTAMP_ISO,
)

/**
 * Stateless, narrow-scope specialist personas ("The Goldfish") executed sequentially on the single
 * loaded on-device Gemma 4 engine (`LiteRT-LM`) with fresh per-node KV-caches.
 *
 * @property roleInstruction Focused system persona instruction free of cross-domain bloat.
 * @property relevantCategories [ElephantFactCategory] slices eligible for injection into this role.
 * @property defaultAllowedTools Canonical tool names from `AdkKitchenToolsEngine` exposed to this role.
 */
@Serializable
enum class GoldfishAgentRole(
  val roleInstruction: String,
  val relevantCategories: Set<ElephantFactCategory>,
  val defaultAllowedTools: List<String>,
) {
  // Rescues <= 48h/72h fridge perishables using exact expiresInDays and schedulable recipes.
  /** Zero-waste fridge & pantry rescue specialist prioritizing urgent `EXPIRING_SOON` stock. */
  PANTRY_EXPIRY_SPECIALIST(
    "Rescue expiring household ingredients first using exact shelf-life days and schedulable recipes.",
    setOf(ElephantFactCategory.INGREDIENT_ALIAS, ElephantFactCategory.TASTE_AND_TEXTURE, ElephantFactCategory.COOKING_CADENCE),
    listOf("get_expiring_pantry_slice", "search_schedulable_recipes", "get_weekly_board_dishes", "propose_meal_board_swaps"),
  ),

  // Balances 4-member 1-pot nutrition goals, <= 25m weekday breakfasts, and weekly board swaps.
  /** Family meal-plan & 4-member nutrition specialist enforcing `<= 25m` morning pacing. */
  MENU_NUTRITION_SPECIALIST(
    "Optimize 21-slot family meals for member nutrition goals, <= 25m weekday breakfasts, and child safety.",
    setOf(
      ElephantFactCategory.MEMBER_NUTRITION_HABIT,
      ElephantFactCategory.COOKING_CADENCE,
      ElephantFactCategory.TASTE_AND_TEXTURE,
      ElephantFactCategory.INGREDIENT_ALIAS,
    ),
    listOf(
      "get_family_goals_and_events", "get_weekly_board_dishes", "search_schedulable_recipes",
      "get_expiring_pantry_slice", "propose_meal_board_swaps", "web_culinary_search",
    ),
  ),

  // Audits PHP (₱) wet-market / supermarket basket totals, pack overrides, and skipped items.
  /** Antipolo–Marikina Smart Grocery & `PHP` (`₱`) budget specialist honoring pack overrides. */
  GROCERY_BUDGET_SPECIALIST(
    "Optimize Smart Grocery quantities, pack sizes, and PHP (₱) market spend using active overrides.",
    setOf(ElephantFactCategory.PACK_SIZE_PREFERENCE, ElephantFactCategory.INGREDIENT_ALIAS, ElephantFactCategory.COOKING_CADENCE),
    listOf("compute_smart_grocery_slice", "get_expiring_pantry_slice", "get_weekly_board_dishes", "web_culinary_search"),
  ),

  // Grounds camera/gallery food & spice detections strictly against household catalog IDs.
  /** Authentic multimodal camera/gallery intake specialist with confirmation-gated restocks. */
  VISION_INTAKE_SPECIALIST(
    "Inspect only visible food and spice items in the attached photo and map to catalog aliases.",
    setOf(ElephantFactCategory.INGREDIENT_ALIAS, ElephantFactCategory.PACK_SIZE_PREFERENCE),
    listOf("get_expiring_pantry_slice", "compute_smart_grocery_slice"),
  ),

  // Answers step-by-step culinary technique, substitution, internal temperature, and timer queries.
  /** Hands-free culinary technique, substitution math, and step-by-step cook coach specialist. */
  COOK_COACH_SPECIALIST(
    "Provide concise culinary technique, food-safety temperature, and macro-aware substitution guidance.",
    setOf(ElephantFactCategory.TASTE_AND_TEXTURE, ElephantFactCategory.INGREDIENT_ALIAS, ElephantFactCategory.MEMBER_NUTRITION_HABIT),
    listOf("get_family_goals_and_events", "search_schedulable_recipes", "web_culinary_search"),
  ),

  // Adversarial LoopAgent critic verifying EC-01, EC-02, EC-08, and activeLifeEvents rules.
  /** Fresh-eyes adversarial safety & constraint auditor gating every candidate board proposal. */
  SAFETY_CONSTRAINT_AUDITOR(
    "Audit candidate meal swaps strictly against EC-01 (<=25m breakfast), EC-02 allergens, EC-08 toddler prep, and life events.",
    setOf(ElephantFactCategory.MEMBER_NUTRITION_HABIT, ElephantFactCategory.TASTE_AND_TEXTURE),
    listOf("audit_candidate_meal_swaps", "get_family_goals_and_events", "search_schedulable_recipes"),
  ),
}

/** Verdict status returned by `audit_candidate_meal_swaps` and the `LoopAgent` safety critic. */
@Serializable
enum class SafetyAuditStatus {
  // All candidate swaps passed EC-01, EC-02, EC-08, and active life-event safety checks.
  /** Every candidate swap satisfied `EC-01`, `EC-02`, `EC-08`, and active life-event rules. */
  APPROVED,

  // At least one swap failed a hard domain invariant; triggers LoopAgent iteration-2 repair.
  /** At least one candidate swap violated a hard domain/safety rule and requires repair. */
  REJECTED_NEEDS_REPAIR,
}

/**
 * Structured constraint violation emitted when a candidate swap fails `audit_candidate_meal_swaps`.
 *
 * @property code Machine-readable rule code (`"EC-01_BREAKFAST_CAP_EXCEEDED"`, `"EC-02_ALLERGEN_BLOCKED"`).
 * @property message Human-readable explanation naming the offending slot, recipe, and metric.
 * @property suggestedFix Concrete remediation guidance naming valid replacement recipe IDs/rules.
 * @property slotId Optional board `slotId` where the violation occurred.
 * @property recipeId Optional candidate `recipeId` that triggered the violation.
 */
@Serializable
data class SafetyConstraintViolation(
  val code: String,
  val message: String,
  val suggestedFix: String,
  val slotId: String? = null,
  val recipeId: String? = null,
)

/**
 * Complete deterministic + critic audit outcome for a batch of candidate meal-board swaps.
 *
 * @property status Overall gate status (`APPROVED` vs `REJECTED_NEEDS_REPAIR`).
 * @property violations Ordered list of hard constraint violations (`emptyList()` when approved).
 * @property auditedSwapCount Number of candidate `slotId -> recipeId` pairs inspected.
 * @property auditorSummary Concise audit summary suitable for logs, tool results, and tests.
 */
@Serializable
data class SafetyAuditVerdict(
  val status: SafetyAuditStatus,
  val violations: List<SafetyConstraintViolation> = emptyList(),
  val auditedSwapCount: Int = 0,
  val auditorSummary: String = "",
) {
  /** `true` if and only if [status] is [SafetyAuditStatus.APPROVED] and [violations] is empty. */
  val isApproved: Boolean
    get() = status == SafetyAuditStatus.APPROVED && violations.isEmpty()
}

/**
 * Surgical, amnesia-by-design context envelope passed to a stateless Goldfish specialist pass.
 *
 * Contains **zero** raw rolling chat history (`0` `AiChatTurn` messages), bounding input tokens to
 * `~300–650` tokens so a single on-device Gemma 4 `Engine` keeps crisp attention and minimal KV RAM.
 *
 * @property role Target [GoldfishAgentRole] executing this node.
 * @property roleInstruction Focused persona instruction for [role].
 * @property userIntent Current trimmed user request (never concatenated with historical turns).
 * @property relevantFacts At most [MAX_GOLDFISH_FACT_SLICE] (`<= 5`) role-matched Elephant facts.
 * @property allowedToolNames Canonical ADK tool names permitted during this Goldfish pass.
 * @property allowedToolSignatures Compact `name(params): description` schemas for [allowedToolNames].
 * @property repairViolations Structured critic violations injected **only** on `LoopAgent` repair pass.
 */
@Serializable
data class GoldfishTaskBrief(
  val role: GoldfishAgentRole,
  val roleInstruction: String,
  val userIntent: String,
  val relevantFacts: List<ElephantSemanticFact> = emptyList(),
  val allowedToolNames: List<String> = emptyList(),
  val allowedToolSignatures: List<String> = emptyList(),
  val repairViolations: List<SafetyConstraintViolation> = emptyList(),
) {
  /**
   * Renders this isolated task brief into a deterministic single-node prompt string containing
   * zero historical chat turns.
   *
   * @param outputContractHint Explicit JSON or tool-call output contract appended to the envelope.
   * @return Compact prompt string ready for `AiEdgePort.generateText`.
   */
  fun renderIsolatedPromptEnvelope(outputContractHint: String = DEFAULT_GOLDFISH_OUTPUT_HINT): String =
    buildList {
      // Emit strictly bounded role, <=5 facts, allowed tools, optional critic feedback, & user intent.
      add("GOLDFISH_ROLE: ${role.name}")
      add("INSTRUCTION: $roleInstruction")
      val factsLine = if (relevantFacts.isEmpty()) "NONE" else relevantFacts.joinToString(" | ") { "[${it.category.name}] ${it.summary}" }
      add("ELEPHANT_FACTS (${relevantFacts.size}/$MAX_GOLDFISH_FACT_SLICE): $factsLine")
      val toolsLine = when {
        allowedToolSignatures.isNotEmpty() -> allowedToolSignatures.joinToString(" ; ")
        allowedToolNames.isNotEmpty() -> allowedToolNames.joinToString(", ")
        else -> "NONE"
      }
      add("ALLOWED_TOOLS: $toolsLine")
      // Inject structured SafetyConstraintViolations ONLY during LoopAgent iteration-2 repair passes.
      if (repairViolations.isNotEmpty()) {
        val repairText = repairViolations.joinToString(" | ") { "${it.code}: ${it.message} -> FIX: ${it.suggestedFix}" }
        add("CRITIC_VIOLATIONS_TO_REPAIR: $repairText")
      }
      add("OUTPUT_CONTRACT: $outputContractHint")
      add("USER_INTENT: $userIntent")
    }.joinToString("\n")

  companion object {
    /** Default compact tool-or-JSON output instruction for Gemma 4 / Cloud Goldfish passes. */
    const val DEFAULT_GOLDFISH_OUTPUT_HINT: String =
      "Either emit ONE read-only tool call JSON {\"tool\":\"<ALLOWED_TOOLS name>\",\"args\":{...}} " +
        "or return final grounded JSON {\"focusTag\":\"KIDS_BRAIN_BOOSTER\",\"assistantSummary\":\"...\"," +
        "\"isConversationalOnly\":false,\"suggestedSwaps\":[{\"slotId\":\"...\",\"recipeId\":\"...\",\"rationale\":\"...\"}]}."
  }
}

/**
 * Validated culinary web search request passed across `WebSearchToolPort`.
 *
 * @property query Trimmed culinary, recipe, or local market pricing search query.
 * @property maxResults Bounded hit ceiling (`1..5`, defaults to `3`).
 * @property marketRegionHint Household market region context (`"Antipolo & Marikina, PH"` default).
 */
@Serializable
data class WebSearchQuery(
  val query: String,
  val maxResults: Int = 3,
  val marketRegionHint: String = "Antipolo & Marikina, PH",
)

/**
 * Single grounded web search snippet returned by an active cloud search adapter.
 *
 * @property title Sanitized result title.
 * @property url Verified `https://` source URL.
 * @property snippet Concise plain-text summary snippet.
 * @property sourceDomain Hostname badge (for example, `"panlasangpinoy.com"`).
 */
@Serializable
data class WebSearchHit(
  val title: String,
  val url: String,
  val snippet: String,
  val sourceDomain: String = "",
)

/** Deterministic outcome code returned by `WebSearchToolPort` implementations. */
@Serializable
enum class WebSearchToolOutcome {
  /** Cloud search executed over allowlisted HTTPS and returned grounded [WebSearchHit]s. */
  EXECUTED,

  /** Blocked intentionally with zero sockets because SavorAI is in offline `ON_DEVICE` Gemma mode. */
  DISABLED_OFFLINE_MODE,

  /** Cloud mode is selected, but no encrypted search/cloud API key is configured in the vault. */
  MISSING_API_KEY,

  /** Upstream HTTPS request timed out or returned a non-2xx HTTP status (secrets redacted). */
  NETWORK_ERROR,
}

/**
 * Complete result envelope returned by `WebSearchToolPort.searchCulinaryWeb`.
 *
 * @property outcome Deterministic execution or offline/keyless gate status.
 * @property query Echoed search query string.
 * @property hits Grounded web hits (`emptyList()` unless [outcome] is [WebSearchToolOutcome.EXECUTED]).
 * @property diagnosticMessage Clear user- and agent-safe explanation of the outcome.
 */
@Serializable
data class WebSearchToolResult(
  val outcome: WebSearchToolOutcome,
  val query: String,
  val hits: List<WebSearchHit> = emptyList(),
  val diagnosticMessage: String = "",
)

/** Structured household events distilled deterministically into [HouseholdElephantMemory]. */
@Serializable
sealed interface ElephantKitchenObservation {
  /** Recorded when the household renames or reprices a catalog ingredient (e.g. `"Fresh Kulitis"`). */
  @Serializable
  data class IngredientRenamed(
    val ingredientId: String,
    val previousDisplayName: String,
    val updatedDisplayName: String,
    val defaultPackageQuantity: Double? = null,
    val baseUnit: BaseUnit = BaseUnit.GRAM,
    val packagePricePhp: Double? = null,
  ) : ElephantKitchenObservation

  /** Recorded when the shopper overrides a Smart Grocery buy quantity (e.g. `4 kg -> 2 kg` chicken). */
  @Serializable
  data class GroceryQuantityOverridden(
    val ingredientId: String,
    val displayName: String,
    val plannedQuantity: Double,
    val overriddenQuantity: Double,
    val baseUnit: BaseUnit,
  ) : ElephantKitchenObservation

  /** Recorded when `saveOverrideAsHouseholdDefault` persists custom BOM ratios on cook (`EC-05`). */
  @Serializable
  data class CookedBomDefaultSaved(
    val recipeId: String,
    val recipeTitle: String,
    val overriddenQuantities: Map<String, Double>,
    val unitsByIngredientId: Map<String, BaseUnit> = emptyMap(),
  ) : ElephantKitchenObservation

  /** Recorded when a planned meal slot is marked `COMPLETED_SKIPPED` for leftovers (`EC-12`). */
  @Serializable
  data class MealSlotSkipped(
    val slotId: String,
    val dayIndex: Int,
    val slotType: MealSlotType,
    val recipeTitle: String,
  ) : ElephantKitchenObservation

  /** Recorded when a member nutrition focus or life-event protocol is updated in Family Profile. */
  @Serializable
  data class MemberNutritionHabitUpdated(
    val memberId: String,
    val memberName: String,
    val focusSummary: String,
  ) : ElephantKitchenObservation

  /** Recorded at week rollover to compress completed/skipped/rescued counts into an episodic digest. */
  @Serializable
  data class WeeklyEpisodicRollup(
    val weekStartDateIso: String,
    val cookedMealsCount: Int,
    val skippedSlotsCount: Int,
    val rescuedExpiringItemsCount: Int,
    val highlightSummary: String,
  ) : ElephantKitchenObservation
}

/**
 * Distills a structured [ElephantKitchenObservation] into [memory], reinforcing existing facts on
 * matching `(category, subjectId)` keys and bounding total stored facts to `maxSemanticFacts`.
 *
 * @param memory Current immutable household Elephant memory.
 * @param observation Kitchen action observation to learn from.
 * @param nowIso Deterministic ISO-8601 timestamp stamped onto updated records.
 * @return Updated immutable [HouseholdElephantMemory].
 */
fun distillActionIntoElephantMemory(
  memory: HouseholdElephantMemory,
  observation: ElephantKitchenObservation,
  nowIso: String = DEFAULT_TIMESTAMP_ISO,
): HouseholdElephantMemory =
  when (observation) {
    is ElephantKitchenObservation.IngredientRenamed -> {
      // Persist familiar local market aliases (e.g. Kulitis, Talbos) with pack size & PHP price.
      val cleanUpdated = observation.updatedDisplayName.trim()
      if (cleanUpdated.isEmpty()) {
        memory
      } else {
        val packSuffix = observation.defaultPackageQuantity?.takeIf { it.isFinite() && it > 0.0 }?.let { qty ->
          val priceText = observation.packagePricePhp?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { " @ ${HouseholdCurrency.PHP.formatAmount(it)}" }.orEmpty()
          " (${formatHumanizedUnitQuantity(qty, observation.baseUnit)} pack$priceText)"
        }.orEmpty()
        val prevClause = observation.previousDisplayName.trim()
          .takeIf { it.isNotEmpty() && !it.equals(cleanUpdated, ignoreCase = true) }
          ?.let { " (formerly '$it')" }.orEmpty()
        val summary = "Household calls ${observation.ingredientId} '$cleanUpdated'$prevClause$packSuffix."
        upsertSemanticFact(memory, ElephantFactCategory.INGREDIENT_ALIAS, observation.ingredientId, summary, nowIso)
      }
    }

    is ElephantKitchenObservation.GroceryQuantityOverridden -> {
      // Capture recurring shopper pack preferences (e.g. buying 2 kg chicken instead of 4 kg).
      if (!observation.overriddenQuantity.isFinite() || observation.overriddenQuantity <= 0.0) {
        memory
      } else {
        val humanOverride = formatHumanizedUnitQuantity(observation.overriddenQuantity, observation.baseUnit)
        val humanPlanned = formatHumanizedUnitQuantity(observation.plannedQuantity, observation.baseUnit)
        val summary = "Preferred Shop buy quantity for '${observation.displayName}' (${observation.ingredientId}) " +
          "is $humanOverride instead of planned $humanPlanned."
        upsertSemanticFact(memory, ElephantFactCategory.PACK_SIZE_PREFERENCE, observation.ingredientId, summary, nowIso)
      }
    }

    is ElephantKitchenObservation.CookedBomDefaultSaved -> {
      // Distill EC-05 normalized household BOM ratios so recipe suggestions mirror family portions.
      if (observation.overriddenQuantities.isEmpty()) {
        memory
      } else {
        val parts = observation.overriddenQuantities.entries.take(4).joinToString(", ") { (ingId, qty) ->
          "$ingId=${formatHumanizedUnitQuantity(qty, observation.unitsByIngredientId[ingId] ?: BaseUnit.GRAM)}"
        }
        val summary = "Saved household recipe BOM ratio on '${observation.recipeTitle}' (${observation.recipeId}): $parts."
        upsertSemanticFact(memory, ElephantFactCategory.TASTE_AND_TEXTURE, observation.recipeId, summary, nowIso)
      }
    }

    is ElephantKitchenObservation.MealSlotSkipped -> {
      // Learn which weekday slots frequently turn into leftover nights (EC-12 cadence).
      val dayLabel = EGM_WEEKDAY_NAMES.getOrElse(observation.dayIndex) { "Weekday" }
      val slotLabel = observation.slotType.name.lowercase().replaceFirstChar { it.uppercaseChar() }
      val summary = "Household skipped $dayLabel $slotLabel ('${observation.recipeTitle}') in favor of leftovers."
      val subject = "${dayLabel.lowercase()}_${observation.slotType.name.lowercase()}"
      upsertSemanticFact(memory, ElephantFactCategory.COOKING_CADENCE, subject, summary, nowIso)
    }

    is ElephantKitchenObservation.MemberNutritionHabitUpdated -> {
      // Record per-member athletic hypertrophy, satiety, or neurodevelopment focus changes.
      val cleanFocus = observation.focusSummary.trim()
      if (cleanFocus.isEmpty()) {
        memory
      } else {
        val summary = "${observation.memberName} (${observation.memberId}) focus: $cleanFocus."
        upsertSemanticFact(memory, ElephantFactCategory.MEMBER_NUTRITION_HABIT, observation.memberId, summary, nowIso)
      }
    }

    is ElephantKitchenObservation.WeeklyEpisodicRollup -> {
      // Compress weekly cooked/skipped/rescued counters into 1 bounded digest (never raw chat).
      val digest = ElephantEpisodicDigest(
        digestId = "ep_${observation.weekStartDateIso}",
        weekStartDateIso = observation.weekStartDateIso,
        cookedMealsCount = observation.cookedMealsCount.coerceAtLeast(0),
        skippedSlotsCount = observation.skippedSlotsCount.coerceAtLeast(0),
        rescuedExpiringItemsCount = observation.rescuedExpiringItemsCount.coerceAtLeast(0),
        highlightSummary = observation.highlightSummary.trim(),
        createdAtIso = nowIso,
      )
      val merged = (listOf(digest) + memory.episodicDigests.filterNot { it.digestId == digest.digestId })
        .take(memory.maxEpisodicDigests.coerceAtLeast(1))
      memory.copy(episodicDigests = merged, updatedAtIso = nowIso)
    }
  }

/**
 * Convenience overload distilling Smart Grocery & Pantry catalog edit actions directly into
 * [HouseholdElephantMemory] (capturing global ingredient renames like `"Fresh Kulitis"` and pack
 * quantity overrides like `4 kg -> 2 kg` chicken in one pure pass).
 *
 * @param memory Current household Elephant memory.
 * @param editAction Executed [GroceryAndCatalogEditAction] from `Shop` or `Pantry`.
 * @param catalogById Pre-edit catalog map resolving previous display names and [BaseUnit]s.
 * @param plannedQuantitiesById Optional planned weekly restock quantities keyed by `ingredientId`.
 * @param nowIso Deterministic ISO-8601 timestamp.
 * @return Updated [HouseholdElephantMemory].
 */
fun distillActionIntoElephantMemory(
  memory: HouseholdElephantMemory,
  editAction: GroceryAndCatalogEditAction,
  catalogById: Map<String, IngredientCatalogItem> = emptyMap(),
  plannedQuantitiesById: Map<String, Double> = emptyMap(),
  nowIso: String = DEFAULT_TIMESTAMP_ISO,
): HouseholdElephantMemory =
  when (editAction) {
    is GroceryAndCatalogEditAction.UpdateCatalogIngredientDetails -> {
      val existing = catalogById[editAction.ingredientId]
      distillActionIntoElephantMemory(
        memory = memory,
        observation = ElephantKitchenObservation.IngredientRenamed(
          ingredientId = editAction.ingredientId,
          previousDisplayName = existing?.displayName ?: editAction.ingredientId,
          updatedDisplayName = editAction.updatedDisplayName,
          defaultPackageQuantity = editAction.updatedDefaultPackageQuantity ?: existing?.defaultPackageQuantity,
          baseUnit = existing?.baseUnit ?: BaseUnit.GRAM,
          packagePricePhp = editAction.updatedPackagePriceUsd ?: existing?.estimatedPackagePriceUsd,
        ),
        nowIso = nowIso,
      )
    }

    is GroceryAndCatalogEditAction.SaveGroceryCatalogItemEdit -> {
      val existing = catalogById[editAction.ingredientId]
      val baseUnit = existing?.baseUnit ?: BaseUnit.GRAM
      val cleanName = editAction.updatedDisplayName.trim()
      // Record alias change first whenever the shopper also renamed the catalog item in Shop.
      val afterRename = when {
        cleanName.isNotEmpty() && existing != null && !existing.displayName.equals(cleanName, ignoreCase = true) ->
          distillActionIntoElephantMemory(
            memory = memory,
            observation = ElephantKitchenObservation.IngredientRenamed(
              ingredientId = editAction.ingredientId,
              previousDisplayName = existing.displayName,
              updatedDisplayName = cleanName,
              defaultPackageQuantity = editAction.updatedDefaultPackageQuantity ?: existing.defaultPackageQuantity,
              baseUnit = baseUnit,
              packagePricePhp = editAction.updatedPackagePriceUsd ?: existing.estimatedPackagePriceUsd,
            ),
            nowIso = nowIso,
          )
        else -> memory
      }
      // Record pack-size preference whenever a positive buy-quantity override is saved.
      val overrideQty = editAction.overrideBuyQuantity
      when {
        overrideQty != null && overrideQty.isFinite() && overrideQty > 0.0 -> {
          val plannedQty = plannedQuantitiesById[editAction.ingredientId] ?: existing?.defaultPackageQuantity ?: overrideQty
          val label = cleanName.ifEmpty { existing?.displayName ?: editAction.ingredientId }
          distillActionIntoElephantMemory(
            memory = afterRename,
            observation = ElephantKitchenObservation.GroceryQuantityOverridden(
              editAction.ingredientId, label, plannedQty, overrideQty, baseUnit,
            ),
            nowIso = nowIso,
          )
        }
        else -> afterRename
      }
    }

    else -> memory
  }

/**
 * Selects at most [maxFacts] (`<= 5`) [ElephantSemanticFact]s relevant to [role] and optional
 * [promptHint] keywords, preventing unrelated long-term memories from polluting a Goldfish pass.
 *
 * @param memory Household Elephant memory store.
 * @param role Target [GoldfishAgentRole] requesting context.
 * @param maxFacts Requested fact cap (clamped to `0..MAX_GOLDFISH_FACT_SLICE`, default `5`).
 * @param promptHint Optional current user prompt used solely to boost keyword-matching facts.
 * @return Ranked, role-filtered list of at most `5` [ElephantSemanticFact]s.
 */
fun selectRelevantElephantFacts(
  memory: HouseholdElephantMemory,
  role: GoldfishAgentRole,
  maxFacts: Int = MAX_GOLDFISH_FACT_SLICE,
  promptHint: String = "",
): List<ElephantSemanticFact> {
  // Clamp strictly to <= 5 facts so small 2B/4B Gemma KV-caches stay razor-sharp.
  val safeCap = maxFacts.coerceIn(0, MAX_GOLDFISH_FACT_SLICE)
  if (safeCap == 0 || memory.semanticFacts.isEmpty()) return emptyList()

  val promptTokens = promptHint.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }.toSet()
  // Filter strictly to categories owned by this Goldfish role so cross-domain facts stay out.
  return memory.semanticFacts
    .asSequence()
    .filter { fact -> fact.category in role.relevantCategories }
    .sortedWith(
      compareByDescending<ElephantSemanticFact> { fact ->
        val searchable = "${fact.subjectId} ${fact.summary}".lowercase()
        promptTokens.count { token -> searchable.contains(token) }
      }
        .thenByDescending { it.reinforcementCount }
        .thenByDescending { it.confidence },
    )
    .take(safeCap)
    .toList()
}

/**
 * Constructs a stateless [GoldfishTaskBrief] carrying **only** the specialist's role instruction,
 * trimmed user intent, `<= 5` role-matched [ElephantSemanticFact]s, allowed ADK tool schemas, and
 * optional `LoopAgent` critic violations—with **zero** raw rolling chat history.
 *
 * @param role Specialist [GoldfishAgentRole] for this graph node.
 * @param userIntent Current user request text (historical chat turns are never accepted here).
 * @param elephantMemory Household Elephant memory sliced via [selectRelevantElephantFacts].
 * @param toolDeclarations Available ADK tool declarations filtered to [role]'s allowed tools.
 * @param maxFacts Maximum semantic facts to inject (`<= 5`).
 * @param repairViolations Optional `SafetyConstraintViolation` list on `LoopAgent` iteration 2.
 * @return Immutable, context-isolated [GoldfishTaskBrief].
 */
fun buildGoldfishTaskBrief(
  role: GoldfishAgentRole,
  userIntent: String,
  elephantMemory: HouseholdElephantMemory = HouseholdElephantMemory(),
  toolDeclarations: List<AdkToolDeclaration> = AdkKitchenToolsEngine.declarationsForRole(role),
  maxFacts: Int = MAX_GOLDFISH_FACT_SLICE,
  repairViolations: List<SafetyConstraintViolation> = emptyList(),
): GoldfishTaskBrief {
  // Bind only tools explicitly whitelisted on the active Goldfish specialist role.
  val cleanIntent = userIntent.trim()
  val allowedSet = role.defaultAllowedTools.toSet()
  val matchedDeclarations = toolDeclarations.filter { it.name in allowedSet }
  val slicedFacts = selectRelevantElephantFacts(elephantMemory, role, maxFacts, cleanIntent)
  return GoldfishTaskBrief(
    role = role,
    roleInstruction = role.roleInstruction,
    userIntent = cleanIntent,
    relevantFacts = slicedFacts,
    allowedToolNames = matchedDeclarations.map { it.name }.ifEmpty { role.defaultAllowedTools },
    allowedToolSignatures = matchedDeclarations.map { it.toCompactSignature() },
    repairViolations = repairViolations,
  )
}

private val EGM_WEEKDAY_NAMES: List<String> =
  listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

// Upsert a semantic fact by (category, subjectId), incrementing reinforcementCount and bounding size.
private fun upsertSemanticFact(
  memory: HouseholdElephantMemory,
  category: ElephantFactCategory,
  subjectId: String,
  summary: String,
  nowIso: String,
): HouseholdElephantMemory {
  val cleanSubject = subjectId.trim().ifEmpty { "HOUSEHOLD" }
  val factId = "fact_${category.name.lowercase()}_${cleanSubject.lowercase()}"
  val existing = memory.semanticFacts.find { it.factId == factId }
  val nextFact = ElephantSemanticFact(
    factId = factId,
    category = category,
    subjectId = cleanSubject,
    summary = summary.trim(),
    confidence = 1.0,
    reinforcementCount = (existing?.reinforcementCount ?: 0) + 1,
    updatedAtIso = nowIso,
  )
  // Keep most-recently reinforced facts at head and drop overflow past maxSemanticFacts.
  val retained = (listOf(nextFact) + memory.semanticFacts.filterNot { it.factId == factId })
    .take(memory.maxSemanticFacts.coerceAtLeast(1))
  return memory.copy(semanticFacts = retained, updatedAtIso = nowIso)
}

// Humanize 2000 g -> "2 kg", 1500 g -> "1.5 kg", 1000 ml -> "1 L" for crisp Elephant summaries.
internal fun formatHumanizedUnitQuantity(quantity: Double, unit: BaseUnit): String {
  val safe = if (quantity.isFinite() && quantity >= 0.0) quantity else 0.0
  return when {
    unit == BaseUnit.GRAM && safe >= 1000.0 -> "${formatTrimmedDecimal(safe / 1000.0)} kg"
    unit == BaseUnit.MILLILITER && safe >= 1000.0 -> "${formatTrimmedDecimal(safe / 1000.0)} L"
    else -> "${formatTrimmedDecimal(safe)} ${unit.wireCode}"
  }
}

// Drop trailing ".0" on exact integers while retaining up to 2 fractional digits (e.g. "1.5").
private fun formatTrimmedDecimal(value: Double): String {
  val rounded = (value * 100.0).roundToInt() / 100.0
  val whole = rounded.toLong()
  return if (abs(rounded - whole.toDouble()) < 0.005) whole.toString() else rounded.toString()
}
