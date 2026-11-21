package com.familykitchen.board.ai

import com.familykitchen.board.data.InMemoryCloudCredentialVault
import com.familykitchen.board.domain.AiExecutionMode
import com.familykitchen.board.domain.BaseUnit
import com.familykitchen.board.domain.BomLine
import com.familykitchen.board.domain.CloudLlmProvider
import com.familykitchen.board.domain.CookOwner
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.IngredientCategory
import com.familykitchen.board.domain.IngredientTier
import com.familykitchen.board.domain.MealSlotType
import com.familykitchen.board.domain.ModelNotReadyException
import com.familykitchen.board.domain.MorningStep
import com.familykitchen.board.domain.NutritionFocusTag
import com.familykitchen.board.domain.PantryLedgerEntry
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.SeedCatalogDataset
import com.familykitchen.board.domain.StapleStatus
import com.familykitchen.board.domain.VisionNotSupportedException
import com.familykitchen.board.domain.formatAiFallbackToastMessage
import com.familykitchen.board.domain.isMealOrRecipeProposalRequest
import com.familykitchen.board.domain.sanitizeLlmRawOutput
import com.familykitchen.board.ports.AiEdgePort
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ports.KitchenSnapshot
import com.familykitchen.board.ports.RuntimeCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Hermetic JVM JUnit 4 suite verifying Gemma 4 (<|thought|>...<|turn|>) and Cloud LLM text
// grounding across greetings, salvaged placeholder swaps, multi-turn RECENT_CONVERSATION Q&A,
// provenance labels, and HybridAiInferenceRouter keyless CLOUD_API -> ON_DEVICE recovery.

/**
 * Hermetic JUnit 4 verification suite covering:
 * 1. On-device Android `"Hello"` greeting grounding across all 4 Gemma 4 (`gemma-4-E2B-it.litertlm`)
 *    output shapes (thinking block + JSON echoing `"isConversationalOnly":false`, natural plain-text
 *    prose, JSON with copied `"..."` placeholder swap, and corrupt `"{not_valid_json"` fallback),
 * 2. Meal proposal hero cards alongside real LLM `assistantSummary` prose, multi-turn
 *    `RECENT_CONVERSATION` prompt memory, and conversational culinary-technique Q&A follow-ups,
 * 3. Automatic `HybridAiInferenceRouter` recovery to `AiExecutionMode.ON_DEVICE` on local model
 *    import and startup discovery whenever `CLOUD_API` was toggled without a stored API key.
 */
class Gemma4AndConversationalTurnTest {

  // Antipolo-Marikina household catalog fixture with expiring salmon, spinach, eggs, and olive oil.
  private val testCatalog: List<IngredientCatalogItem> = listOf(
    IngredientCatalogItem(
      ingredientId = "ing_salmon",
      displayName = "Fresh Salmon Fillet",
      category = IngredientCategory.PROTEIN,
      tier = IngredientTier.TIER_1_QUANTITATIVE,
      baseUnit = BaseUnit.GRAM,
      defaultPackageQuantity = 600.0,
      allergens = listOf("FISH"),
      estimatedPackagePriceUsd = 680.0,
    ),
    IngredientCatalogItem(
      ingredientId = "ing_spinach",
      displayName = "Baby Spinach",
      category = IngredientCategory.PRODUCE,
      tier = IngredientTier.TIER_1_QUANTITATIVE,
      baseUnit = BaseUnit.GRAM,
      defaultPackageQuantity = 250.0,
      substituteGroupIds = listOf("LEAFY_GREENS"),
      estimatedPackagePriceUsd = 140.0,
    ),
    IngredientCatalogItem(
      ingredientId = "ing_egg",
      displayName = "Large Farm Eggs",
      category = IngredientCategory.DAIRY_EGGS,
      tier = IngredientTier.TIER_1_QUANTITATIVE,
      baseUnit = BaseUnit.COUNT,
      defaultPackageQuantity = 12.0,
      allergens = listOf("EGGS"),
      estimatedPackagePriceUsd = 155.0,
    ),
    IngredientCatalogItem(
      ingredientId = "ing_olive_oil",
      displayName = "Extra-Virgin Olive Oil",
      category = IngredientCategory.PANTRY_STAPLE,
      tier = IngredientTier.TIER_2_STAPLE,
      baseUnit = BaseUnit.MILLILITER,
      defaultPackageQuantity = 750.0,
    ),
  )

  // Baseline pantry with salmon (1d) and spinach (2d) in the 48-hour EXPIRING_SOON window.
  private val testInitialPantry: List<PantryLedgerEntry> = listOf(
    PantryLedgerEntry("ing_salmon", IngredientTier.TIER_1_QUANTITATIVE, 600.0, expiresInDays = 1),
    PantryLedgerEntry("ing_spinach", IngredientTier.TIER_1_QUANTITATIVE, 250.0, expiresInDays = 2),
    PantryLedgerEntry("ing_egg", IngredientTier.TIER_1_QUANTITATIVE, 10.0, expiresInDays = 8),
    PantryLedgerEntry("ing_olive_oil", IngredientTier.TIER_2_STAPLE, 500.0, StapleStatus.PLENTY),
  )

  // Fast 12m breakfast recipe satisfying EC-01 (<= 25m calibrated morning cap).
  private val morningEggScramble = Recipe(
    recipeId = "rec_morning_egg_spinach_scramble",
    title = "Soft Spinach & Farm Egg Scramble",
    sourceAttribution = "Household Morning Table",
    allowedSlots = listOf(MealSlotType.BREAKFAST, MealSlotType.LUNCH, MealSlotType.DINNER),
    preferredOwner = CookOwner.FRED,
    rawPrepMinutes = 4,
    cookMinutes = 6,
    calibratedActiveMinutes = 12,
    morningSteps = listOf(MorningStep(1, "Wilt spinach in olive oil and fold soft eggs.", 360)),
    toddlerPrepTip = "Serve soft egg ribbons finely chopped for 2yo.",
    bomLines = listOf(
      BomLine("ing_egg", 4.0),
      BomLine("ing_spinach", 100.0, substituteGroupId = "LEAFY_GREENS"),
      BomLine("ing_olive_oil", 10.0),
    ),
  )

  // Zero-waste evening dinner rescuing expiring salmon and spinach in one pan.
  private val panSearedSalmonDinner = Recipe(
    recipeId = "rec_pan_seared_salmon_spinach",
    title = "Crispy-Skin Pan-Seared Salmon & Garlic Spinach",
    sourceAttribution = "Antipolo Family Bistro",
    allowedSlots = listOf(MealSlotType.LUNCH, MealSlotType.DINNER),
    preferredOwner = CookOwner.WIFE,
    rawPrepMinutes = 8,
    cookMinutes = 12,
    calibratedActiveMinutes = 24,
    morningSteps = listOf(MorningStep(1, "Sear dry salmon skin-side down and wilt spinach.", 720)),
    toddlerPrepTip = "Flake boneless salmon center and chop wilted spinach for 2yo.",
    bomLines = listOf(
      BomLine("ing_salmon", 500.0),
      BomLine("ing_spinach", 200.0, substituteGroupId = "LEAFY_GREENS"),
      BomLine("ing_olive_oil", 15.0),
    ),
  )

  private val baselineSnapshot: KitchenSnapshot = KitchenSnapshot.fromSeedCatalog(
    SeedCatalogDataset(
      ingredients = testCatalog,
      recipes = listOf(morningEggScramble, panSearedSalmonDinner),
      initialPantry = testInitialPantry,
    ),
  )

  private val gemma4ReadyCapabilities = RuntimeCapabilities(
    supportsText = true,
    supportsVision = true,
    backendLabel = "LiteRT-LM GPU + Vision",
    modelName = "gemma-4-E2B-it.litertlm",
  )

  /**
   * Verifies on-device Android `"Hello"` greeting grounding across all 4 Gemma 4 output
   * shapes, ensuring real AI greetings render with `isConversationalOnly == true`,
   * `featuredRecipeCard == null`, `responseSourceLabel == "gemma-4-E2B-it"`, salvaged summaries
   * on placeholder `"..."` swaps, and sanitized diagnostic toasts on corrupt JSON fallback.
   */
  @Test
  fun gemma4HelloGreeting_acrossFourOutputShapes_preservesAiProseWithoutGenericCards() = runTest {
    val fakePort = CapturingRecoverableOnDevicePort(
      initialState = AiRuntimeState.Ready(gemma4ReadyCapabilities),
    )
    val coordinator = AiChefCoordinator(aiEdgePort = fakePort)

    // Confirm conversational intent classifier keeps greetings and identity questions card-free.
    assertFalse(isMealOrRecipeProposalRequest("Hello"))
    assertFalse(isMealOrRecipeProposalRequest("Bonjour!"))
    assertFalse(isMealOrRecipeProposalRequest("Who are you?"))
    assertTrue(isMealOrRecipeProposalRequest("What should we cook tonight to rescue expiring items?"))

    // Shape A: Gemma 4 <|thought|> containing "{...}" + JSON echoing "isConversationalOnly":false.
    fakePort.textResponder = {
      "<|thought|>\nUser said Hello. Schema: {\"focusTag\":\"KIDS_BRAIN_BOOSTER\"}\n" +
        "<|turn|>{\"focusTag\":\"KIDS_BRAIN_BOOSTER\"," +
        "\"assistantSummary\":\"Hello! Ready to help in your Antipolo kitchen.\"," +
        "\"isConversationalOnly\":false,\"suggestedSwaps\":[]}<|turn|>"
    }
    val outcomeA = coordinator.submitTurn(baselineSnapshot, "Hello")
    assertEquals(AiTurnResolutionStatus.ON_DEVICE_TEXT_GROUNDED, outcomeA.status)
    assertEquals("Hello! Ready to help in your Antipolo kitchen.", outcomeA.assistantTurn.messageText)
    assertEquals("gemma-4-E2B-it", outcomeA.assistantTurn.responseSourceLabel)
    assertNull(outcomeA.toastMessage)
    val proposalA = requireNotNull(outcomeA.assistantTurn.proposal)
    assertTrue(proposalA.isConversationalOnly)
    assertNull(proposalA.featuredRecipeCard)
    assertTrue(proposalA.slotSwaps.isEmpty())
    assertTrue(proposalA.newIngredientSuggestions.isEmpty())

    // Verify system prompt sent for "Hello" explicitly instructed conversational-only JSON.
    val helloPrompt = fakePort.capturedTextPrompts.single()
    assertTrue(helloPrompt.contains("\"isConversationalOnly\":true,\"suggestedSwaps\":[]"))

    // Verify C++-prefilled orphan closing <thought|> (when prefill consumed opening <|thought|>).
    val prefillCleaned = sanitizeLlmRawOutput(
      "Reasoning with {\"focusTag\":\"KIDS_BRAIN_BOOSTER\"}\n<thought|>Bonjour from Antipolo!<turn|>",
    )
    assertEquals("Bonjour from Antipolo!", prefillCleaned)

    // Shape B: Natural plain-text prose reply without any JSON wrapper.
    fakePort.textResponder = {
      "<|thought|>Warm greeting.<|turn|>Bonjour! How can I help you cook today?<|turn|>"
    }
    val outcomeB = coordinator.submitTurn(baselineSnapshot, "Hello")
    assertEquals(AiTurnResolutionStatus.ON_DEVICE_TEXT_GROUNDED, outcomeB.status)
    assertEquals("Bonjour! How can I help you cook today?", outcomeB.assistantTurn.messageText)
    assertEquals("gemma-4-E2B-it", outcomeB.assistantTurn.responseSourceLabel)
    val proposalB = requireNotNull(outcomeB.assistantTurn.proposal)
    assertTrue(proposalB.isConversationalOnly)
    assertNull(proposalB.featuredRecipeCard)
    assertTrue(proposalB.slotSwaps.isEmpty())

    // Shape C: JSON reply with valid assistantSummary + copied "..." placeholder in suggestedSwaps.
    fakePort.textResponder = {
      "<|thought|>Drafting reply.<|turn|>{\"focusTag\":\"KIDS_BRAIN_BOOSTER\"," +
        "\"assistantSummary\":\"Good morning! Your salmon and spinach are ready whenever you want dinner ideas.\"," +
        "\"isConversationalOnly\":false," +
        "\"suggestedSwaps\":[{\"slotId\":\"...\",\"recipeId\":\"...\",\"rationale\":\"...\"}]}<|turn|>"
    }
    val outcomeC = coordinator.submitTurn(baselineSnapshot, "Hello")
    assertEquals(AiTurnResolutionStatus.ON_DEVICE_TEXT_GROUNDED, outcomeC.status)
    assertEquals(
      "Good morning! Your salmon and spinach are ready whenever you want dinner ideas.",
      outcomeC.assistantTurn.messageText,
    )
    val proposalC = requireNotNull(outcomeC.assistantTurn.proposal)
    assertTrue(proposalC.slotSwaps.isEmpty())
    assertTrue(proposalC.isConversationalOnly)
    assertNull(proposalC.featuredRecipeCard)

    // Shape D: Corrupt JSON ("{not_valid_json") degrades cleanly with Offline Advisor + toast.
    fakePort.textResponder = { "{not_valid_json" }
    val outcomeD = coordinator.submitTurn(baselineSnapshot, "Hello")
    assertEquals(AiTurnResolutionStatus.OFFLINE_DETERMINISTIC_PROPOSAL, outcomeD.status)
    assertEquals("Offline Advisor", outcomeD.assistantTurn.responseSourceLabel)
    val diagnosticToast = requireNotNull(outcomeD.toastMessage)
    assertTrue(diagnosticToast.isNotBlank())
    assertTrue(diagnosticToast.contains("offline pantry advisor"))

    // Ensure runtime failure fallback toasts scrub leaked API credentials completely.
    val leakedSecret = "AIzaSyLeakedFallbackKey987654321"
    val scrubbedToast = formatAiFallbackToastMessage(IllegalStateException("401 key $leakedSecret rejected"))
    assertFalse(scrubbedToast.contains(leakedSecret))
    assertTrue(scrubbedToast.contains("[REDACTED]"))
  }

  /**
   * Verifies multi-turn conversation continuity (`RECENT_CONVERSATION`), preserving the LLM's
   * `assistantSummary` prose alongside a grounded `featuredRecipeCard` on Turn 1 and answering a
   * Turn 2 culinary-technique question conversationally (`featuredRecipeCard == null`).
   */
  @Test
  fun multiTurnMealProposalAndCulinaryTechniqueQa_preservesSummaryAndRecentConversationContext() = runTest {
    val fakePort = CapturingRecoverableOnDevicePort(
      initialState = AiRuntimeState.Ready(gemma4ReadyCapabilities),
    )
    val coordinator = AiChefCoordinator(aiEdgePort = fakePort)
    val dinnerSlot = baselineSnapshot.weeklyBoard.slots.first { it.slotType == MealSlotType.DINNER }

    // Turn 1: Expiring-item rescue request returns real AI summary prose + grounded recipe card.
    val turn1Summary =
      "Pan-sear your expiring salmon fillet with garlic baby spinach in 20 minutes tonight."
    fakePort.textResponder = {
      """
      <|thought|>Salmon expires in 1 day and spinach in 2 days.<|turn|>
      {
        "focusTag": "PANTRY_ZERO_WASTE",
        "assistantSummary": "$turn1Summary",
        "isConversationalOnly": false,
        "suggestedSwaps": [
          {
            "slotId": "${dinnerSlot.slotId}",
            "recipeId": "${panSearedSalmonDinner.recipeId}",
            "rationale": "Rescues expiring salmon and baby spinach in one skillet."
          }
        ]
      }
      <|turn|>
      """.trimIndent()
    }

    val turn1 = coordinator.submitTurn(
      snapshot = baselineSnapshot,
      promptText = "What should we cook tonight to rescue expiring items?",
    )
    assertEquals(AiTurnResolutionStatus.ON_DEVICE_TEXT_GROUNDED, turn1.status)
    assertEquals(turn1Summary, turn1.assistantTurn.messageText)
    assertEquals("gemma-4-E2B-it", turn1.assistantTurn.responseSourceLabel)
    val proposal1 = requireNotNull(turn1.assistantTurn.proposal)
    assertFalse(proposal1.isConversationalOnly)
    assertEquals(NutritionFocusTag.PANTRY_ZERO_WASTE, proposal1.focusTag)
    val heroCard = requireNotNull(proposal1.featuredRecipeCard)
    assertEquals(panSearedSalmonDinner.recipeId, heroCard.recipe.recipeId)
    assertEquals(1, proposal1.slotSwaps.size)

    // Turn 2: Follow-up culinary technique Q&A carries Turn 1 in RECENT_CONVERSATION and stays card-free.
    assertFalse(isMealOrRecipeProposalRequest("How do I sear the fillet so the skin stays crispy?"))
    val turn2TechniqueAnswer =
      "Pat the salmon skin bone-dry with paper towels, press skin-side down in hot oil for 4 minutes undisturbed, then flip briefly."
    fakePort.textResponder = {
      "<|thought|>Follow-up searing technique for Turn 1 salmon.<|turn|>$turn2TechniqueAnswer<|turn|>"
    }

    val turn2 = coordinator.submitTurn(
      snapshot = turn1.updatedSnapshot,
      promptText = "How do I sear the fillet so the skin stays crispy?",
    )
    assertEquals(2, fakePort.capturedTextPrompts.size)
    val secondSystemPrompt = fakePort.capturedTextPrompts.last()
    assertTrue(secondSystemPrompt.contains("RECENT_CONVERSATION:"))
    assertTrue(secondSystemPrompt.contains("USER: What should we cook tonight to rescue expiring items?"))
    assertTrue(secondSystemPrompt.contains("ASSISTANT: $turn1Summary"))
    assertTrue(secondSystemPrompt.contains("\"isConversationalOnly\":true"))

    assertEquals(AiTurnResolutionStatus.ON_DEVICE_TEXT_GROUNDED, turn2.status)
    assertEquals(turn2TechniqueAnswer, turn2.assistantTurn.messageText)
    val proposal2 = requireNotNull(turn2.assistantTurn.proposal)
    assertTrue(proposal2.isConversationalOnly)
    assertNull(proposal2.featuredRecipeCard)
    assertTrue(proposal2.slotSwaps.isEmpty())
  }

  /**
   * Verifies that [HybridAiInferenceRouter] automatically switches back to
   * [AiExecutionMode.ON_DEVICE] whenever `CLOUD_API` has no stored API key and either:
   * 1) `importAndLoadModel(...)` loads a `Ready` local `.litertlm` model, or
   * 2) `autoDiscoverAndInitialize()` discovers a `Ready` local `.litertlm` model on startup,
   * while preserving `CLOUD_API` (and Cloud provenance labels) once a valid key is stored.
   */
  @Test
  fun hybridRouter_autoRecoversOnDeviceWhenCloudApiHasNoKeyOnImportAndAutoDiscover() = runTest {
    val localReadyState = AiRuntimeState.Ready(gemma4ReadyCapabilities)
    val onDevicePort = CapturingRecoverableOnDevicePort(
      initialState = AiRuntimeState.Uninitialized,
      importStateToEmit = localReadyState,
      discoverStateToEmit = localReadyState,
      textResponder = {
        "<|thought|>Local Gemma active.<|turn|>Hello from local on-device Gemma 4!<|turn|>"
      },
    )
    val vault = InMemoryCloudCredentialVault()
    val cloudClient = CloudLlmClient { _, _, _ ->
      CloudHttpResponse(200, """{"candidates":[{"content":{"parts":[{"text":"Hello from Gemini Cloud!"}]}}]}""")
    }
    val router = HybridAiInferenceRouter(onDevicePort, vault, cloudClient)

    try {
      // Simulate user previously toggling CLOUD_API without saving an API key.
      vault.setExecutionMode(AiExecutionMode.CLOUD_API)
      assertFalse(vault.configFlow.value.hasStoredApiKey)
      assertTrue(router.runtimeState.value is AiRuntimeState.Error)

      // 1) Importing a local .litertlm model switches vault back to ON_DEVICE and routes turns locally.
      val imported = router.importAndLoadModel(
        sourceUriString = "content://saf/gemma-4-E2B-it.litertlm",
        displayName = "gemma-4-E2B-it.litertlm",
      )
      assertEquals(localReadyState, imported)
      assertEquals(AiExecutionMode.ON_DEVICE, vault.configFlow.value.executionMode)
      assertEquals(localReadyState, router.runtimeState.value)

      val coordinator = AiChefCoordinator(aiEdgePort = router)
      val localHello = coordinator.submitTurn(baselineSnapshot, "Hello")
      assertEquals(1, onDevicePort.generateTextCallCount)
      assertEquals(AiTurnResolutionStatus.ON_DEVICE_TEXT_GROUNDED, localHello.status)
      assertEquals("Hello from local on-device Gemma 4!", localHello.assistantTurn.messageText)
      assertEquals("gemma-4-E2B-it", localHello.assistantTurn.responseSourceLabel)

      // 2) Flip back to CLOUD_API (still no key) -> autoDiscoverAndInitialize() recovers ON_DEVICE.
      vault.setExecutionMode(AiExecutionMode.CLOUD_API)
      assertTrue(router.runtimeState.value is AiRuntimeState.Error)

      val discovered = router.autoDiscoverAndInitialize()
      assertEquals(1, onDevicePort.autoDiscoverCallCount)
      assertEquals(localReadyState, discovered)
      assertEquals(AiExecutionMode.ON_DEVICE, vault.configFlow.value.executionMode)
      assertEquals(localReadyState, router.runtimeState.value)

      // 3) Once a real Cloud API key is saved, CLOUD_API stays active and stamps Cloud provenance.
      vault.saveApiKey(CloudLlmProvider.GEMINI, "AIzaSyConfiguredCloudKey123456")
      vault.setExecutionMode(AiExecutionMode.CLOUD_API)
      router.autoDiscoverAndInitialize()
      assertEquals(AiExecutionMode.CLOUD_API, vault.configFlow.value.executionMode)

      val cloudHello = coordinator.submitTurn(localHello.updatedSnapshot, "Hello")
      assertEquals(AiTurnResolutionStatus.ON_DEVICE_TEXT_GROUNDED, cloudHello.status)
      assertEquals("Hello from Gemini Cloud!", cloudHello.assistantTurn.messageText)
      assertEquals("Gemini · gemini-3.8-flash", cloudHello.assistantTurn.responseSourceLabel)
      assertEquals(1, onDevicePort.generateTextCallCount)
    } finally {
      router.close()
    }
  }

  // Stateful on-device AiEdgePort test double recording system prompts, imports, and discoveries.
  private class CapturingRecoverableOnDevicePort(
    initialState: AiRuntimeState,
    private val importStateToEmit: AiRuntimeState = initialState,
    private val discoverStateToEmit: AiRuntimeState = initialState,
    var textResponder: (String) -> String = { "{}" },
  ) : AiEdgePort {
    private val mutableState = MutableStateFlow(initialState)
    override val runtimeState: StateFlow<AiRuntimeState> = mutableState.asStateFlow()

    var autoDiscoverCallCount: Int = 0
    var generateTextCallCount: Int = 0
    val capturedTextPrompts: MutableList<String> = mutableListOf()

    override suspend fun autoDiscoverAndInitialize(): AiRuntimeState {
      autoDiscoverCallCount += 1
      mutableState.value = discoverStateToEmit
      return discoverStateToEmit
    }

    override suspend fun importAndLoadModel(sourceUriString: String, displayName: String?): AiRuntimeState {
      mutableState.value = importStateToEmit
      return importStateToEmit
    }

    override suspend fun generateText(prompt: String): String {
      val current = mutableState.value
      if (current !is AiRuntimeState.Ready) {
        throw ModelNotReadyException(runtimeStatus = current::class.simpleName ?: "UNINITIALIZED")
      }
      generateTextCallCount += 1
      capturedTextPrompts.add(prompt)
      return sanitizeLlmRawOutput(textResponder(prompt))
    }

    override suspend fun analyzeImageWithPrompt(preprocessedImagePath: String, prompt: String): String =
      throw VisionNotSupportedException("Vision path unused in text-grounding suite", "stub")

    override fun close() = Unit
  }
}
