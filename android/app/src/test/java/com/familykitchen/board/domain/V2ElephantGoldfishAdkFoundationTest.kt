package com.familykitchen.board.domain

import com.familykitchen.board.ai.CloudHttpResponse
import com.familykitchen.board.ai.EgmGraphStageOutcome
import com.familykitchen.board.ai.KeystoreCloudWebSearchAdapter
import com.familykitchen.board.ai.OfflineDisabledWebSearchAdapter
import com.familykitchen.board.ai.SingleGemmaEgmGraphRunner
import com.familykitchen.board.data.FakeKitchenStorage
import com.familykitchen.board.data.InMemoryCloudCredentialVault
import com.familykitchen.board.ports.AiEdgePort
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ports.KitchenSnapshot
import com.familykitchen.board.ports.RuntimeCapabilities
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// 14th hermetic JVM JUnit 4 suite verifying SavorAI v2.0's Single-Gemma Elephant & Goldfish (EGM)
// runtime, 8 bounded ADK domain tools, LoopAgent SafetyAuditor repair loop, Elephant distillation,
// and 100% offline WebSearchToolPort posture (0 sockets, 0 emulator).

/**
 * 14th hermetic JUnit 4 verification suite covering the **SavorAI v2.0** Single-Gemma Elephant &
 * Goldfish + ADK Tooling Foundation:
 * 1. **Single-Gemma Goldfish Context Isolation**: `GoldfishTaskBrief` and `SingleGemmaEgmGraphRunner`
 *    prompts strip 100% of rolling chat history (`aiTurnHistory`) and inject only `<= 5`
 *    role-matched `ElephantSemanticFact`s + role-allowed ADK tool schemas,
 * 2. **Bounded 8-Tool `AdkKitchenToolsEngine`**: Exposes household member nutrition goals, actual
 *    scheduled `Recipe.title` values on the 21-slot board, and exact `expiresInDays` + `₱` at-risk
 *    values on demand, plus `<|tool_call|>` and fenced JSON parsing,
 * 3. **`LoopAgent` + `SafetyAuditor` Self-Repair (`maxIterations = 2`)**: Catches a combined
 *    `EC-01` (`> 25m` breakfast) + `EC-02` (`PEANUTS` allergen) draft on iteration 1, feeds only
 *    structured `SafetyConstraintViolation`s into iteration 2, and repairs to a valid `<= 25m`
 *    allergen-safe HITL proposal with `0` unconfirmed pantry or board mutations,
 * 4. **`ElephantMemory` Distillation & Offline Web Search Posture**: Distills `"Fresh Kulitis"`
 *    catalog renames and `4 kg -> 2 kg` chicken pack overrides into deduplicated semantic facts,
 *    while `OfflineDisabledWebSearchAdapter` and `KeystoreCloudWebSearchAdapter` preserve 100%
 *    offline local-Gemma execution until `AiExecutionMode.CLOUD_API` and a vault key are active.
 */
class V2ElephantGoldfishAdkFoundationTest {

  private val canonicalSeed: SeedCatalogDataset by lazy {
    SchemaValidator.decodeSeedCatalogDatasetJson(readCanonicalSeedCatalogJson())
  }

  /**
   * Proves that [buildGoldfishTaskBrief] and [SingleGemmaEgmGraphRunner] enforce Goldfish amnesia:
   * noisy historical chat turns never enter model prompts, semantic facts are capped at `<= 5`
   * role-relevant categories, and disallowed mutation/audit tools stay hidden from specialist roles.
   */
  @Test
  fun singleGemmaGoldfishIsolation_excludesChatTranscriptAndSlicesAtMostFiveRoleMatchedFacts() = runTest {
    // Seed 7 diverse semantic facts spanning all 5 ElephantFactCategory buckets.
    var elephant = HouseholdElephantMemory()
    val observations = listOf(
      ElephantKitchenObservation.IngredientRenamed("ing_amaranth_greens", "Amaranth Greens", "Fresh Kulitis", 250.0, BaseUnit.GRAM, 50.0),
      ElephantKitchenObservation.GroceryQuantityOverridden("ing_chicken", "Chicken Thighs", 4000.0, 2000.0, BaseUnit.GRAM),
      ElephantKitchenObservation.GroceryQuantityOverridden("ing_rice", "Jasmine Rice", 5000.0, 2500.0, BaseUnit.GRAM),
      ElephantKitchenObservation.GroceryQuantityOverridden("ing_milk", "Fresh Milk", 2000.0, 1000.0, BaseUnit.MILLILITER),
      ElephantKitchenObservation.MealSlotSkipped("slot_fri_dinner", 4, MealSlotType.DINNER, "Poulet Bicyclette"),
      ElephantKitchenObservation.MealSlotSkipped("slot_wed_lunch", 2, MealSlotType.LUNCH, "Atassi Rice & Beans"),
      ElephantKitchenObservation.MemberNutritionHabitUpdated("FRED", "Fred", "Post-workout +1.5x lean protein"),
      ElephantKitchenObservation.CookedBomDefaultSaved("rec_01", "Egg Tartine", mapOf("ing_avocado" to 150.0)),
    )
    for (obs in observations) {
      elephant = distillActionIntoElephantMemory(elephant, obs)
    }
    assertEquals(8, elephant.semanticFacts.size)

    // Attach noisy unrelated historical chat turns to KitchenSnapshot.aiTurnHistory.
    val noisyHistory = listOf(
      AiChatTurn("u_old_1", AiChatRole.USER, "UNRELATED_CROISSANT_HISTORY_MARKER_991", DEFAULT_TIMESTAMP_ISO),
      AiChatTurn("a_old_2", AiChatRole.ASSISTANT, "UNRELATED_SOURDOUGH_BUBBLE_MARKER_882", DEFAULT_TIMESTAMP_ISO),
    )
    val snapshot = KitchenSnapshot.fromSeedCatalog(canonicalSeed).copy(
      aiTurnHistory = noisyHistory,
      elephantMemory = elephant,
    )

    val brief = buildGoldfishTaskBrief(
      role = GoldfishAgentRole.GROCERY_BUDGET_SPECIALIST,
      userIntent = "How much chicken should we buy on our Marikina shopping list?",
      elephantMemory = elephant,
    )

    // 1. Strictly <= 5 facts, and ONLY categories relevant to GROCERY_BUDGET_SPECIALIST.
    assertTrue(brief.relevantFacts.size <= MAX_GOLDFISH_FACT_SLICE)
    assertEquals(5, brief.relevantFacts.size)
    assertTrue(
      brief.relevantFacts.all { fact ->
        fact.category in GoldfishAgentRole.GROCERY_BUDGET_SPECIALIST.relevantCategories
      },
    )
    // Keyword boost places the ing_chicken 2 kg override fact at index 0.
    assertEquals("ing_chicken", brief.relevantFacts.first().subjectId)
    assertFalse(brief.allowedToolNames.contains("propose_meal_board_swaps"))
    assertTrue(brief.allowedToolNames.contains("compute_smart_grocery_slice"))

    // 2. Execute through SingleGemmaEgmGraphRunner and prove 0 chat-history markers enter prompts.
    val scriptedGemma = ScriptedSingleGemmaPort(
      replies = listOf(
        """{"focusTag":"KIDS_BRAIN_BOOSTER","assistantSummary":"Your preferred Shop override sets Chicken Thighs to 2 kg.","isConversationalOnly":true,"suggestedSwaps":[]}""",
      ),
    )
    val runner = SingleGemmaEgmGraphRunner(aiEdgePort = scriptedGemma)
    val outcome = runner.executeTurn(
      snapshot = snapshot,
      promptText = "How much chicken should we buy on our Marikina shopping list?",
    )

    assertEquals(GoldfishAgentRole.GROCERY_BUDGET_SPECIALIST, outcome.selectedRole)
    assertEquals(EgmGraphStageOutcome.GOLDFISH_APPROVED_ITERATION_1, outcome.outcome)
    assertEquals(1, outcome.nodeTraces.size)
    val dispatchedPrompt = outcome.nodeTraces.single().promptSentToModel
    assertFalse(dispatchedPrompt.contains("UNRELATED_CROISSANT_HISTORY_MARKER_991"))
    assertFalse(dispatchedPrompt.contains("UNRELATED_SOURDOUGH_BUBBLE_MARKER_882"))
    assertFalse(dispatchedPrompt.contains("RECENT_CONVERSATION"))
    assertTrue(dispatchedPrompt.contains("2 kg"))
  }

  /**
   * Verifies that [AdkKitchenToolsEngine] exposes all 8 bounded tools and returns member goals,
   * resolved scheduled `Recipe.title` dishes, and exact `expiresInDays` + `₱` risk slices on demand.
   */
  @Test
  fun adkKitchenToolsEngine_exposesMemberGoalsScheduledDishTitlesExpiringDaysAndToolCallParsing() {
    assertEquals(8, AdkKitchenToolsEngine.allToolDeclarations().size)

    val snapshot = KitchenSnapshot.fromSeedCatalog(canonicalSeed).copy(
      householdProfile = HouseholdProfile().copy(
        householdAllergens = listOf("PEANUTS"),
        activeLifeEvents = listOf(HouseholdLifeEventPreset.HIGH_IRON_IMMUNITY.toLifeEvent()),
      ),
    )

    // 1. get_expiring_pantry_slice exposes seed expiring items (ing_salmon=1d, ing_spinach=2d, ing_greek_yogurt=2d).
    val expiryCall = parseModelToolCallOrNull(
      """<|thought|>Check urgent fridge stock first.<|/thought|><|tool_call|>{"tool":"get_expiring_pantry_slice","args":{"withinDays":2}}<|/tool_call|>""",
    )
    assertNotNull(expiryCall)
    val expiryResult = executeKitchenDomainTool(snapshot, snapshot.elephantMemory, requireNotNull(expiryCall))
    assertTrue(expiryResult.isSuccess)
    assertTrue(expiryResult.payloadJson.contains("\"ingredientId\":\"ing_salmon\""))
    assertTrue(expiryResult.payloadJson.contains("\"expiresInDays\":1"))
    assertTrue(expiryResult.payloadJson.contains("\"ingredientId\":\"ing_spinach\""))
    assertTrue(expiryResult.payloadJson.contains("₱"))

    // 2. get_family_goals_and_events exposes member focus goals, allergens, and active life events.
    val goalsResult = executeKitchenDomainTool(
      snapshot = snapshot,
      call = AdkToolCall(toolName = "get_family_goals_and_events"),
    )
    assertTrue(goalsResult.isSuccess)
    assertTrue(goalsResult.payloadJson.contains("Muscle Building (Hypertrophy)"))
    assertTrue(goalsResult.payloadJson.contains("PEANUTS"))
    assertTrue(goalsResult.payloadJson.contains("evt_iron_immunity_protocol"))

    // 3. get_weekly_board_dishes resolves actual Recipe.title values (fixing v1's bare slotId blindness).
    val fencedBoardCall = parseModelToolCallOrNull(
      "```json\n{\"tool\":\"get_weekly_board_dishes\",\"args\":{\"dayIndex\":0}}\n```",
    )
    val boardResult = executeKitchenDomainTool(snapshot, snapshot.elephantMemory, requireNotNull(fencedBoardCall))
    assertTrue(boardResult.isSuccess)
    val mondayBreakfastRecipe = requireNotNull(
      snapshot.recipesById[snapshot.weeklyBoard.slots.first { it.dayIndex == 0 && it.slotType == MealSlotType.BREAKFAST }.recipeId],
    )
    assertTrue(boardResult.payloadJson.contains(mondayBreakfastRecipe.title))
    assertTrue(boardResult.payloadJson.contains("\"calibratedActiveMinutes\":"))

    // 4. search_schedulable_recipes enforces <=25m BREAKFAST cap (EC-01) and PEANUTS exclusion (EC-02).
    val breakfastSearch = executeKitchenDomainTool(
      snapshot = snapshot,
      call = AdkToolCall("search_schedulable_recipes", mapOf("slotType" to "BREAKFAST")),
    )
    assertTrue(breakfastSearch.isSuccess)
    assertFalse(breakfastSearch.payloadJson.contains("rec_17_west_african_peanut_stew_mafe"))
    assertFalse(breakfastSearch.payloadJson.contains("rec_20_one_pan_lemon_herb_salmon_rice"))
    assertTrue(breakfastSearch.payloadJson.contains("rec_09_overnight_oats"))
  }

  /**
   * Proves that [SingleGemmaEgmGraphRunner]'s `LoopAgent` + `SafetyAuditor` catches both an `EC-01`
   * (`> 25m` breakfast) violation and an `EC-02` (`PEANUTS` allergen) violation on iteration 1,
   * injects only the structured [SafetyConstraintViolation] list into iteration 2, and repairs to
   * a valid `<= 25m` allergen-safe HITL proposal with `0` unconfirmed pantry or board mutations.
   */
  @Test
  fun loopAgentSafetyAuditor_catchesEc01AndEc02OnIteration1AndRepairsOnIteration2WithoutMutation() = runTest {
    val baseSnapshot = KitchenSnapshot.fromSeedCatalog(canonicalSeed)
    val allergySnapshot = baseSnapshot.copy(
      householdProfile = baseSnapshot.householdProfile.copy(
        householdAllergens = listOf("PEANUTS"),
      ),
    )
    val storage = FakeKitchenStorage(initialSnapshot = allergySnapshot)
    val pantryBefore = allergySnapshot.pantryEntries
    val boardBefore = allergySnapshot.weeklyBoard

    val monBreakfastSlotId = allergySnapshot.weeklyBoard.slots
      .first { it.dayIndex == 0 && it.slotType == MealSlotType.BREAKFAST }
      .slotId
    val monDinnerSlotId = allergySnapshot.weeklyBoard.slots
      .first { it.dayIndex == 0 && it.slotType == MealSlotType.DINNER }
      .slotId

    // Pass 1: Read-only tool call -> Pass 2: Unsafe draft (>25m breakfast + PEANUTS stew) -> Pass 3: Repaired!
    val scriptedGemma = ScriptedSingleGemmaPort(
      replies = listOf(
        """<|tool_call|>{"tool":"get_weekly_board_dishes","args":{"dayIndex":0}}<|/tool_call|>""",
        """
        {
          "focusTag": "PANTRY_ZERO_WASTE",
          "assistantSummary": "Drafting Monday swaps.",
          "isConversationalOnly": false,
          "suggestedSwaps": [
            {"slotId": "$monBreakfastSlotId", "recipeId": "rec_20_one_pan_lemon_herb_salmon_rice", "rationale": "Uses salmon (32m - violates EC-01)"},
            {"slotId": "$monDinnerSlotId", "recipeId": "rec_17_west_african_peanut_stew_mafe", "rationale": "Peanut stew (violates EC-02 PEANUTS)"}
          ]
        }
        """.trimIndent(),
        """
        {
          "focusTag": "PANTRY_ZERO_WASTE",
          "assistantSummary": "Repaired Monday breakfast to 5-min Overnight Oats (<=25m) and dinner to peanut-free Lemon Herb Salmon!",
          "isConversationalOnly": false,
          "suggestedSwaps": [
            {"slotId": "$monBreakfastSlotId", "recipeId": "rec_09_overnight_oats", "rationale": "5m active morning <= 25m EC-01 cap"},
            {"slotId": "$monDinnerSlotId", "recipeId": "rec_20_one_pan_lemon_herb_salmon_rice", "rationale": "Peanut-free DHA dinner rescuing 1d expiring salmon"}
          ]
        }
        """.trimIndent(),
      ),
    )

    val runner = SingleGemmaEgmGraphRunner(aiEdgePort = scriptedGemma)
    val outcome = runner.executeTurn(
      snapshot = allergySnapshot,
      promptText = "Suggest Monday breakfast and dinner swaps that rescue expiring salmon safely.",
    )

    assertEquals(EgmGraphStageOutcome.GOLDFISH_REPAIRED_ITERATION_2, outcome.outcome)
    assertEquals(2, outcome.loopIterationsUsed)
    assertEquals(1, outcome.executedTools.size)
    assertEquals("get_weekly_board_dishes", outcome.executedTools.single().toolName)
    assertEquals(3, outcome.llmInvocationCount)

    // Verify Iteration 1 caught BOTH EC-01 (>25m breakfast) and EC-02 (PEANUTS allergen).
    assertEquals(2, outcome.criticVerdicts.size)
    val iter1Verdict = outcome.criticVerdicts[0]
    assertEquals(SafetyAuditStatus.REJECTED_NEEDS_REPAIR, iter1Verdict.status)
    assertTrue(iter1Verdict.violations.any { it.code.startsWith("EC-01") })
    assertTrue(iter1Verdict.violations.any { it.code.startsWith("EC-02") })

    // Verify Iteration 2 prompt received structured violation feedback and passed audit cleanly.
    val repairPrompt = outcome.nodeTraces.last().promptSentToModel
    assertTrue(repairPrompt.contains("CRITIC_VIOLATIONS_TO_REPAIR"))
    assertTrue(repairPrompt.contains("EC-01_BREAKFAST_CAP_EXCEEDED"))
    assertTrue(repairPrompt.contains("EC-02_ALLERGEN_BLOCKED"))
    assertEquals(SafetyAuditStatus.APPROVED, outcome.criticVerdicts[1].status)

    // Verify repaired HITL proposal is valid and ZERO unconfirmed pantry/board mutations occurred!
    assertTrue(outcome.requiresUserConfirmation)
    val proposal = requireNotNull(outcome.proposal)
    assertEquals(2, proposal.slotSwaps.size)
    val bSwap = proposal.slotSwaps.first { it.slotType == MealSlotType.BREAKFAST }
    val dSwap = proposal.slotSwaps.first { it.slotType == MealSlotType.DINNER }
    assertEquals("rec_09_overnight_oats", bSwap.suggestedRecipe.recipeId)
    assertTrue(bSwap.suggestedRecipe.calibratedActiveMinutes <= MAX_WEEKDAY_BREAKFAST_MINUTES)
    assertEquals("rec_20_one_pan_lemon_herb_salmon_rice", dSwap.suggestedRecipe.recipeId)

    assertEquals(0, storage.batchWriteCallCount)
    assertEquals(pantryBefore, storage.snapshotFlow.value?.pantryEntries)
    assertEquals(boardBefore, storage.snapshotFlow.value?.weeklyBoard)
  }

  /**
   * Verifies that [distillActionIntoElephantMemory] records `"Fresh Kulitis"` renames and `2 kg`
   * chicken pack overrides cleanly, while [OfflineDisabledWebSearchAdapter] and
   * [KeystoreCloudWebSearchAdapter] preserve 100% offline local-Gemma posture until cloud mode and
   * vault credentials are explicitly enabled.
   */
  @Test
  fun elephantDistillationAndWebSearchAdapters_learnKulitisAnd2kgChickenWhileKeepingOfflinePosture() = runTest {
    val catalogById = indexCatalogById(canonicalSeed.ingredients)

    // 1. Distill Pantry rename ("Fresh Kulitis") and Shop pack override (4000g -> 2000g = 2 kg chicken).
    val afterRename = distillActionIntoElephantMemory(
      memory = HouseholdElephantMemory(),
      editAction = GroceryAndCatalogEditAction.UpdateCatalogIngredientDetails(
        ingredientId = "ing_amaranth_greens",
        updatedDisplayName = "Fresh Kulitis",
        updatedDefaultPackageQuantity = 250.0,
        updatedPackagePriceUsd = 50.0,
      ),
      catalogById = catalogById,
    )
    val afterChickenOverride = distillActionIntoElephantMemory(
      memory = afterRename,
      editAction = GroceryAndCatalogEditAction.SaveGroceryCatalogItemEdit(
        ingredientId = "ing_chicken",
        updatedDisplayName = "Free-Range Chicken Thighs",
        overrideBuyQuantity = 2000.0,
      ),
      catalogById = catalogById,
      plannedQuantitiesById = mapOf("ing_chicken" to 4000.0),
    )
    // Reinforce chicken override a second time -> deduplicated factId with reinforcementCount == 2.
    val reinforcedMemory = distillActionIntoElephantMemory(
      memory = afterChickenOverride,
      editAction = GroceryAndCatalogEditAction.SaveGroceryCatalogItemEdit(
        ingredientId = "ing_chicken",
        updatedDisplayName = "Free-Range Chicken Thighs",
        overrideBuyQuantity = 2000.0,
      ),
      catalogById = catalogById,
      plannedQuantitiesById = mapOf("ing_chicken" to 4000.0),
    )

    assertEquals(2, reinforcedMemory.semanticFacts.size)
    val kulitisFact = requireNotNull(
      reinforcedMemory.semanticFacts.find { it.category == ElephantFactCategory.INGREDIENT_ALIAS },
    )
    assertTrue(kulitisFact.summary.contains("Fresh Kulitis"))
    assertTrue(kulitisFact.summary.contains("250 g pack @ ₱50"))

    val chickenFact = requireNotNull(
      reinforcedMemory.semanticFacts.find { it.category == ElephantFactCategory.PACK_SIZE_PREFERENCE },
    )
    assertTrue(chickenFact.summary.contains("2 kg"))
    assertTrue(chickenFact.summary.contains("4 kg"))
    assertEquals(2, chickenFact.reinforcementCount)

    // 2. Default OfflineDisabledWebSearchAdapter opens 0 sockets and returns DISABLED_OFFLINE_MODE.
    val offlineAdapter = OfflineDisabledWebSearchAdapter()
    val offlineOutcome = offlineAdapter.searchCulinaryWeb(WebSearchQuery("bangus belly marikina price"))
    assertEquals(WebSearchToolOutcome.DISABLED_OFFLINE_MODE, offlineOutcome.outcome)
    assertTrue(offlineOutcome.hits.isEmpty())

    // 3. KeystoreCloudWebSearchAdapter stays 100% dormant when ON_DEVICE or keyless, and blocks SSRF.
    val vault = InMemoryCloudCredentialVault()
    val transportCalls = AtomicInteger(0)
    var capturedHeaders: Map<String, String> = emptyMap()
    var capturedUrl = ""

    val cloudSearchAdapter = KeystoreCloudWebSearchAdapter(
      credentialVault = vault,
      endpointUrl = "https://api.tavily.com/search",
      transport = { url, headers, _ ->
        transportCalls.incrementAndGet()
        capturedUrl = url
        capturedHeaders = headers
        CloudHttpResponse(
          statusCode = 200,
          body = """{"results":[{"title":"Sinigang na Bangus","url":"https://panlasangpinoy.com/sinigang","content":"Fresh tamarind broth with milkfish and kangkong."}]}""",
        )
      },
    )

    // Even if a key is stored, ON_DEVICE mode must NEVER touch network transport!
    vault.saveApiKey(CloudLlmProvider.GEMINI, "AIzaSyTestKeyForVaultDormancyCheck1234")
    vault.setExecutionMode(AiExecutionMode.ON_DEVICE)
    val dormantWhileOnDevice = cloudSearchAdapter.searchCulinaryWeb(WebSearchQuery("sinigang bangus"))
    assertEquals(WebSearchToolOutcome.DISABLED_OFFLINE_MODE, dormantWhileOnDevice.outcome)
    assertEquals(0, transportCalls.get())

    // Switch to CLOUD_API, clear key -> MISSING_API_KEY with 0 network calls.
    vault.clearApiKey(CloudLlmProvider.GEMINI)
    vault.setExecutionMode(AiExecutionMode.CLOUD_API)
    val missingKeyResult = cloudSearchAdapter.searchCulinaryWeb(WebSearchQuery("sinigang bangus"))
    assertEquals(WebSearchToolOutcome.MISSING_API_KEY, missingKeyResult.outcome)
    assertEquals(0, transportCalls.get())

    // Untrusted SSRF host fails hard with ValidationException before transport executes.
    assertThrows(ValidationException::class.java) {
      KeystoreCloudWebSearchAdapter.validateAllowedSearchEndpoint("https://untrusted.example.org/search")
    }

    // Save key + CLOUD_API -> executes header-authenticated search (zero ?key= query parameter).
    vault.saveApiKey(CloudLlmProvider.GEMINI, "AIzaSyTestKeyForVaultDormancyCheck1234")
    vault.setExecutionMode(AiExecutionMode.CLOUD_API)
    val executedResult = cloudSearchAdapter.searchCulinaryWeb(WebSearchQuery("sinigang bangus"))
    assertEquals(WebSearchToolOutcome.EXECUTED, executedResult.outcome)
    assertEquals(1, transportCalls.get())
    assertFalse(capturedUrl.contains("?"))
    assertTrue(capturedHeaders["Authorization"].orEmpty().startsWith("Bearer AIza"))
    assertEquals(1, executedResult.hits.size)
    assertEquals("panlasangpinoy.com", executedResult.hits.single().sourceDomain)
  }

  private fun readCanonicalSeedCatalogJson(): String {
    val candidates = listOf(
      Path("data/seed_catalog.json"),
      Path("../data/seed_catalog.json"),
      Path("../../data/seed_catalog.json"),
    )
    val matched = requireNotNull(candidates.firstOrNull { it.isRegularFile() }) {
      "Unable to locate canonical data/seed_catalog.json"
    }
    return matched.readText()
  }

  // Deterministic fake modeling a SINGLE loaded Gemma 4 LiteRT-LM engine opening ephemeral per-call conversations.
  private class ScriptedSingleGemmaPort(
    private val replies: List<String>,
  ) : AiEdgePort {
    private var callIndex: Int = 0
    private val state = MutableStateFlow<AiRuntimeState>(
      AiRuntimeState.Ready(
        RuntimeCapabilities(
          supportsText = true,
          supportsVision = true,
          backendLabel = "LiteRT-LM GPU + Vision",
          modelName = "gemma-4-E2B-it.litertlm",
        ),
      ),
    )

    override val runtimeState: StateFlow<AiRuntimeState> = state.asStateFlow()
    override suspend fun autoDiscoverAndInitialize(): AiRuntimeState = state.value
    override suspend fun importAndLoadModel(sourceUriString: String, displayName: String?): AiRuntimeState = state.value

    override suspend fun generateText(prompt: String): String {
      val idx = callIndex.coerceAtMost(replies.lastIndex)
      callIndex += 1
      return replies[idx]
    }

    override suspend fun analyzeImageWithPrompt(preprocessedImagePath: String, prompt: String): String =
      throw VisionNotSupportedException("Text-only scripted test fixture.")

    override fun close() = Unit
  }
}
