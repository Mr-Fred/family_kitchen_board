package com.familykitchen.board.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// Hermetic JVM unit test suite verifying SchemaValidator strictness, multimodal vision prompts
// (zero fake grocery words), Gemma token stripping, visual/LLM JSON grounding, tool safety gates,
// 4-member pregnancy food-safety plating, and Mon-Fri custom family meal batch scheduling.

/**
 * Hermetic JUnit 4 test suite covering [SchemaValidator] strict boundary checks and
 * [AiMenuAdvisorEngine] multimodal prompt neutrality, Gemma token sanitization, untrusted LLM
 * response grounding, image-attachment tool-bypass safety, pregnancy food-safety plating, and
 * `Monday–Friday` custom meal batch scheduling.
 */
class AiAdvisorAndSchemaValidatorTest {

  // Compact catalog fixture covering Tier-1 brain-boosters, proteins, grains, and Tier-2 olive oil.
  private val advisorCatalog: List<IngredientCatalogItem> = listOf(
    IngredientCatalogItem("ing_egg", "Large Farm Eggs", IngredientCategory.DAIRY_EGGS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.COUNT, 12.0, allergens = listOf("EGGS")),
    IngredientCatalogItem("ing_oats", "Rolled Whole Grain Oats", IngredientCategory.BAKERY_GRAINS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 1000.0),
    IngredientCatalogItem("ing_blueberry", "Fresh Blueberries", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 250.0, substituteGroupIds = listOf("FRUIT_TOPPING")),
    IngredientCatalogItem("ing_banana", "Sweet Ripe Bananas", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 1000.0, substituteGroupIds = listOf("FRUIT_TOPPING")),
    IngredientCatalogItem("ing_fish", "Whole Fresh Snapper Fillets", IngredientCategory.PROTEIN, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 1000.0, allergens = listOf("FISH")),
    IngredientCatalogItem("ing_turkey_ham", "Sliced Turkey Ham", IngredientCategory.PROTEIN, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 400.0),
    IngredientCatalogItem("ing_baguette", "Crusty Artisan Baguette", IngredientCategory.BAKERY_GRAINS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.COUNT, 2.0, allergens = listOf("GLUTEN")),
    IngredientCatalogItem("ing_olive_oil", "Extra-Virgin Olive Oil", IngredientCategory.PANTRY_STAPLE, IngredientTier.TIER_2_STAPLE, BaseUnit.MILLILITER, 750.0),
  )

  // Stocked pantry baseline so AI Chef grounds meal swaps in real household inventory.
  private val advisorPantry: List<PantryLedgerEntry> = listOf(
    PantryLedgerEntry("ing_egg", IngredientTier.TIER_1_QUANTITATIVE, 12.0),
    PantryLedgerEntry("ing_oats", IngredientTier.TIER_1_QUANTITATIVE, 800.0),
    PantryLedgerEntry("ing_blueberry", IngredientTier.TIER_1_QUANTITATIVE, 250.0),
    PantryLedgerEntry("ing_banana", IngredientTier.TIER_1_QUANTITATIVE, 1000.0),
    PantryLedgerEntry("ing_fish", IngredientTier.TIER_1_QUANTITATIVE, 900.0),
    PantryLedgerEntry("ing_turkey_ham", IngredientTier.TIER_1_QUANTITATIVE, 400.0),
    PantryLedgerEntry("ing_baguette", IngredientTier.TIER_1_QUANTITATIVE, 2.0),
    PantryLedgerEntry("ing_olive_oil", IngredientTier.TIER_2_STAPLE, 500.0, StapleStatus.PLENTY),
  )

  private val plainToastRecipe = Recipe(
    recipeId = "rec_plain_baguette_toast",
    title = "Plain Baguette Toast",
    sourceAttribution = "Basic Morning Toast",
    allowedSlots = listOf(MealSlotType.BREAKFAST),
    preferredOwner = CookOwner.FRED,
    rawPrepMinutes = 4,
    cookMinutes = 5,
    calibratedActiveMinutes = 11,
    morningSteps = listOf(MorningStep(1, "Toast baguette slices lightly.", 300)),
    toddlerPrepTip = "Trim hard crust for 2yo.",
    bomLines = listOf(BomLine("ing_baguette", 1.0)),
  )

  private val brainBoostBowlRecipe = Recipe(
    recipeId = "rec_brain_boost_egg_berry_oat_scramble",
    title = "Choline Egg, Berry & Oat Brain-Booster Bowl",
    sourceAttribution = "Pediatric Nutrition Kitchen",
    allowedSlots = listOf(MealSlotType.BREAKFAST),
    preferredOwner = CookOwner.FRED,
    rawPrepMinutes = 6,
    cookMinutes = 10,
    calibratedActiveMinutes = 19,
    morningSteps = listOf(MorningStep(1, "Simmer oats with berries and soft-scramble eggs.", 600)),
    toddlerPrepTip = "Quarter blueberries lengthwise and serve soft egg curds for 2yo.",
    bomLines = listOf(
      BomLine("ing_egg", 4.0),
      BomLine("ing_oats", 200.0),
      BomLine("ing_blueberry", 150.0, substituteGroupId = "FRUIT_TOPPING"),
      BomLine("ing_olive_oil", 10.0),
    ),
  )

  private val omegaFishDinnerRecipe = Recipe(
    recipeId = "rec_omega_fish_moyo_dinner",
    title = "Omega-3 Roasted Fish & Banana Platter",
    sourceAttribution = "Coastal Family Table",
    allowedSlots = listOf(MealSlotType.LUNCH, MealSlotType.DINNER),
    preferredOwner = CookOwner.WIFE,
    rawPrepMinutes = 10,
    cookMinutes = 20,
    calibratedActiveMinutes = 34,
    morningSteps = listOf(MorningStep(1, "Roast seasoned fish fillets in olive oil.", 1200)),
    toddlerPrepTip = "Pinch through fish flakes carefully to verify zero bones for 2yo.",
    bomLines = listOf(BomLine("ing_fish", 700.0), BomLine("ing_banana", 300.0), BomLine("ing_olive_oil", 20.0)),
  )

  private val advisorRecipes: List<Recipe> = listOf(plainToastRecipe, brainBoostBowlRecipe, omegaFishDinnerRecipe)

  // Seven-day breakfast board + Monday dinner fixture for swap and Mon-Fri batch tests.
  private val advisorBoard: WeeklyBoard = WeeklyBoard(
    boardId = "board_ai_test",
    householdId = "household_family_4",
    weekStartDateIso = "2026-10-26",
    slots = (0..6).map { day ->
      MealSlot("slot_day_${day}_breakfast", day, MealSlotType.BREAKFAST, CookOwner.FRED, plainToastRecipe.recipeId)
    } + MealSlot("slot_mon_dinner", 0, MealSlotType.DINNER, CookOwner.WIFE, omegaFishDinnerRecipe.recipeId),
  )

  /**
   * Verifies that [SchemaValidator] rejects negative or zero package quantities and negative stock,
   * BOM, override, and restock quantities with [ValidationException].
   */
  @Test
  fun schemaValidator_rejectsNegativeQuantitiesAcrossCatalogBomPantryCookAndRestock() {
    // Package sizes must be strictly > 0 so whole-package ceiling rounding never divides by zero.
    assertThrows(ValidationException::class.java) {
      SchemaValidator.validateIngredientCatalogItem(advisorCatalog.first().copy(defaultPackageQuantity = 0.0))
    }
    // Reject negative recipe BOM, pantry stock, cook override, and restock delta quantities.
    assertThrows(ValidationException::class.java) {
      SchemaValidator.validateBomLine(BomLine("ing_egg", -1.0))
    }
    assertThrows(ValidationException::class.java) {
      SchemaValidator.validatePantryLedgerEntry(PantryLedgerEntry("ing_egg", IngredientTier.TIER_1_QUANTITATIVE, -2.5))
    }
    assertThrows(ValidationException::class.java) {
      SchemaValidator.validateCookedDeductionPayload(
        CookedDeductionPayload("slot_day_0_breakfast", plainToastRecipe.recipeId, actualUsedQuantities = mapOf("ing_egg" to -3.0)),
      )
    }
    assertThrows(ValidationException::class.java) {
      SchemaValidator.validateRestockDelta(RestockDelta("ing_egg", -12.0))
    }
  }

  /**
   * Verifies that [SchemaValidator.strictJson] (`ignoreUnknownKeys = false`) rejects unknown
   * injected JSON properties across cook-deduction, recipe, seed-dataset, and AI proposal decoders.
   */
  @Test
  fun schemaValidator_strictJson_rejectsUnknownInjectedKeysAcrossDecoders() {
    // Polluted CookedDeductionPayload JSON with injectedAdminFlag must fail strict decoding.
    val pollutedCookJson =
      """{"slotId":"s1","recipeId":"r1","targetServings":4,"activeWorkoutModifier":"NONE","saveOverrideAsHouseholdDefault":false,"injectedAdminFlag":true}"""
    val cookErr = assertThrows(ValidationException::class.java) {
      SchemaValidator.decodeCookedDeductionPayloadJson(pollutedCookJson)
    }
    assertEquals(ValidationException.ERROR_CODE_VALIDATION, cookErr.errorCode)

    // Polluted Recipe, RestockDelta, SeedCatalogDataset, and AiProposal JSON strings must also fail.
    val pollutedRecipeJson =
      """{"recipeId":"r1","title":"Toast","sourceAttribution":"Home","allowedSlots":["BREAKFAST"],"preferredOwner":"FRED","rawPrepMinutes":4,"cookMinutes":5,"calibratedActiveMinutes":11,"bomLines":[{"ingredientId":"ing_baguette","baseQuantity":1.0}],"unexpectedExtraProperty":"bad"}"""
    assertThrows(ValidationException::class.java) { SchemaValidator.decodeRecipeJson(pollutedRecipeJson) }

    val pollutedRestockJson = """{"ingredientId":"ing_egg","addedBaseQuantity":12.0,"rogueKey":1}"""
    assertThrows(ValidationException::class.java) { SchemaValidator.decodeRestockDeltaJson(pollutedRestockJson) }

    val pollutedSeedJson = """{"ingredients":[],"recipes":[],"initialPantry":[],"extraRootKey":true}"""
    assertThrows(ValidationException::class.java) { SchemaValidator.decodeSeedCatalogDatasetJson(pollutedSeedJson) }

    val pollutedAiProposalJson =
      """{"proposalId":"p1","focusTag":"KIDS_BRAIN_BOOSTER","assistantSummary":"Ok","injectedUntrustedField":"rejected"}"""
    assertThrows(ValidationException::class.java) {
      SchemaValidator.decodeAiProposalJson(pollutedAiProposalJson, advisorCatalog, advisorRecipes, advisorBoard)
    }
  }

  /**
   * Verifies that [SchemaValidator] rejects invalid `calibratedActiveMinutes`, excessive
   * `nightBeforePrepMinutes` shifts (`> 5`), and ungrounded catalog, recipe, or board slot IDs.
   */
  @Test
  fun schemaValidator_rejectsInvalidCalibratedMinutesAndUngroundedCatalogOrBoardIds() {
    // Reject negative calibratedActiveMinutes and > 5m night-before shift attempts.
    assertThrows(ValidationException::class.java) {
      SchemaValidator.validateRecipe(plainToastRecipe.copy(calibratedActiveMinutes = -1))
    }
    assertThrows(ValidationException::class.java) {
      SchemaValidator.validateRecipe(plainToastRecipe.copy(nightBeforePrepMinutes = 8))
    }

    // Reject seed datasets where recipe.calibratedActiveMinutes mismatches 1.4x morning buffer math.
    val mismatchedDataset = SeedCatalogDataset(
      advisorCatalog, listOf(plainToastRecipe.copy(calibratedActiveMinutes = 99)), advisorPantry,
    )
    assertThrows(ValidationException::class.java) { SchemaValidator.validateSeedCatalogDataset(mismatchedDataset) }

    // Reject recipe BOM lines referencing hallucinated ingredient IDs absent from catalogById.
    val catalogById = indexCatalogById(advisorCatalog)
    assertThrows(ValidationException::class.java) {
      SchemaValidator.validateRecipe(
        plainToastRecipe.copy(bomLines = listOf(BomLine("ing_hallucinated_truffle", 1.0))), catalogById,
      )
    }

    // Reject AI proposals targeting nonexistent board slots, unknown recipes, or > 25m breakfasts.
    val ungroundedSlotProposal = AiMenuOptimizationProposal(
      proposalId = "prop_bad_slot",
      focusTag = NutritionFocusTag.KIDS_BRAIN_BOOSTER,
      assistantSummary = "Test",
      slotSwaps = listOf(
        AiSlotSwapSuggestion(
          slotId = "slot_nonexistent",
          dayIndex = 0,
          slotType = MealSlotType.BREAKFAST,
          previousRecipeId = plainToastRecipe.recipeId,
          suggestedRecipe = brainBoostBowlRecipe,
          rationale = "Swap",
        ),
      ),
    )
    assertThrows(ValidationException::class.java) {
      SchemaValidator.validateAiMenuOptimizationProposal(ungroundedSlotProposal, advisorCatalog, advisorRecipes, advisorBoard)
    }

    val overCapBreakfastProposal = ungroundedSlotProposal.copy(
      slotSwaps = listOf(
        AiSlotSwapSuggestion(
          slotId = "slot_day_0_breakfast",
          dayIndex = 0,
          slotType = MealSlotType.BREAKFAST,
          previousRecipeId = plainToastRecipe.recipeId,
          suggestedRecipe = omegaFishDinnerRecipe,
          rationale = "Too slow",
        ),
      ),
    )
    assertThrows(ValidationException::class.java) {
      SchemaValidator.validateAiMenuOptimizationProposal(overCapBreakfastProposal, advisorCatalog, advisorRecipes, advisorBoard)
    }
  }

  /**
   * Verifies that [AiMenuAdvisorEngine.buildVisualIntakePrompt] injects **zero** fake hardcoded
   * grocery words (such as `"shrimp"`, `"tilapia"`, `"pineapple"`, or `"spinach"`).
   */
  @Test
  fun buildVisualIntakePrompt_injectsZeroFakeGroceryWordsAndRemainsPixelNeutral() {
    val minimalCatalog = listOf(advisorCatalog.first { it.ingredientId == "ing_egg" })
    val prompt = AiMenuAdvisorEngine.buildVisualIntakePrompt("", minimalCatalog, ChildGrowthStage.TODDLER_1_2Y)
    val promptLower = prompt.lowercase()

    // Guarantee complete elimination of the legacy hardcoded fake grocery prompt bug.
    assertFalse(promptLower.contains("shrimp"))
    assertFalse(promptLower.contains("tilapia"))
    assertFalse(promptLower.contains("pineapple"))
    assertFalse(promptLower.contains("spinach"))
    assertFalse(promptLower.contains("we bought"))

    // Confirm neutral pixel-only directive and passive catalog ID reference are present.
    assertTrue(prompt.contains("Inspect ONLY the attached image pixels"))
    assertTrue(prompt.contains("ing_egg=Large Farm Eggs (count)"))
  }

  /**
   * Verifies that [AiMenuAdvisorEngine.stripGemmaControlTokens] strips `<pad>`, `<unk>`, `<bos>`,
   * `<eos>`, `<start_of_turn>`, `<end_of_turn>`, `<unusedN>`, and Markdown ` ```json ` fences.
   */
  @Test
  fun stripGemmaControlTokens_removesAllControlDelimitersAndUnwrapsJsonCodeFence() {
    // Simulate instruction-tuned Gemma output with turn delimiters, unused tokens, and json fences.
    val rawGemmaOutput =
      "<bos><start_of_turn>model\n<unused0><unused12>```json\n{\"summary\":\"Spotted 1 carton of farm eggs\"}\n```<end_of_turn><eos><pad><unk>"
    val cleaned = AiMenuAdvisorEngine.stripGemmaControlTokens(rawGemmaOutput)
    assertEquals("""{"summary":"Spotted 1 carton of farm eggs"}""", cleaned)
  }

  /**
   * Verifies that [AiMenuAdvisorEngine.parseVisualIntakeJson] grounds known catalog items into
   * validated [RestockDelta]s, isolates unknown labels into `unrecognizedLabels`, and rejects
   * unexpected root JSON keys.
   */
  @Test
  fun parseVisualIntakeJson_groundsCatalogItemsIsolatesUnknownLabelsAndRejectsExtraKeys() {
    val rawVisionOutput = """
      <start_of_turn>model
      ```json
      {
        "summary": "Counter has farm eggs, extra-virgin olive oil, and dragonbeard candy.",
        "detectedItems": [
          {"rawLabel": "Carton of Eggs", "matchedIngredientId": "ing_egg", "estimatedQuantity": 12, "confidence": 0.96},
          {"rawLabel": "Extra-Virgin Olive Oil", "matchedIngredientId": null, "estimatedQuantity": 0, "confidence": 0.91},
          {"rawLabel": "Artisanal Dragonbeard Candy", "matchedIngredientId": "ing_nonexistent", "estimatedQuantity": 1, "confidence": 0.72}
        ],
        "unrecognizedLabels": ["Paper Receipt"]
      }
      ```<end_of_turn>
    """.trimIndent()

    val result = AiMenuAdvisorEngine.parseVisualIntakeJson(rawVisionOutput, advisorCatalog)
    assertEquals(3, result.detectedItems.size)
    assertEquals(2, result.groundedRestockDeltas.size)

    // Tier-1 eggs map to +12.0 count; Tier-2 olive oil defaults to 750.0 ml and resets to PLENTY.
    val eggDelta = requireNotNull(result.groundedRestockDeltas.find { it.ingredientId == "ing_egg" })
    assertEquals(12.0, eggDelta.addedBaseQuantity, 0.001)
    assertNull(eggDelta.newStapleStatus)

    val oilDelta = requireNotNull(result.groundedRestockDeltas.find { it.ingredientId == "ing_olive_oil" })
    assertEquals(750.0, oilDelta.addedBaseQuantity, 0.001)
    assertEquals(StapleStatus.PLENTY, oilDelta.newStapleStatus)

    // Unrecognized items stay isolated and never produce hallucinated RestockDeltas.
    assertEquals(listOf("Paper Receipt", "Artisanal Dragonbeard Candy"), result.unrecognizedLabels)

    // Polluted vision JSON with unexpected fields must throw ValidationException.
    assertThrows(ValidationException::class.java) {
      AiMenuAdvisorEngine.parseVisualIntakeJson("""{"summary":"Scan","detectedItems":[],"injectedAutoCommit":true}""", advisorCatalog)
    }
  }

  /**
   * Verifies that [AiMenuAdvisorEngine.parseAndGroundAiResponse] grounds valid compact Gemma JSON
   * against real board slots and catalog recipes while rejecting hallucinated IDs and `> 25m`
   * breakfast swaps.
   */
  @Test
  fun parseAndGroundAiResponse_groundsValidGemmaSwapsAndRejectsHallucinatedIdsOrSlowBreakfasts() {
    val validCompactGemmaJson = """
      <start_of_turn>model
      ```json
      {
        "focusTag": "KIDS_BRAIN_BOOSTER",
        "assistantSummary": "Swapped Monday breakfast to Choline Egg, Berry & Oat Bowl (19m active).",
        "isConversationalOnly": false,
        "suggestedSwaps": [
          {"slotId": "slot_day_0_breakfast", "recipeId": "rec_brain_boost_egg_berry_oat_scramble", "rationale": "In-stock choline boost."}
        ]
      }
      ```<end_of_turn>
    """.trimIndent()

    // Verify grounding against active board slots, <= 25m morning cap, and FISH allergen filtering.
    val grounded = AiMenuAdvisorEngine.parseAndGroundAiResponse(
      validCompactGemmaJson, "Brain boosters", advisorBoard, advisorRecipes, advisorCatalog, advisorPantry,
      HouseholdProfile(householdAllergens = listOf("FISH")),
    )
    assertEquals(NutritionFocusTag.KIDS_BRAIN_BOOSTER, grounded.focusTag)
    assertEquals("rec_brain_boost_egg_berry_oat_scramble", grounded.slotSwaps.single().suggestedRecipe.recipeId)
    assertTrue(grounded.slotSwaps.single().suggestedRecipe.calibratedActiveMinutes <= MAX_WEEKDAY_BREAKFAST_MINUTES)
    assertTrue(grounded.newIngredientSuggestions.none { "FISH" in it.item.allergens })

    // Hallucinated recipeId and > 25m breakfast swaps must both throw ValidationException.
    val hallucinatedJson = """{"assistantSummary":"Swap","suggestedSwaps":[{"slotId":"slot_day_0_breakfast","recipeId":"rec_fake_bowl"}]}"""
    assertThrows(ValidationException::class.java) {
      AiMenuAdvisorEngine.parseAndGroundAiResponse(hallucinatedJson, "", advisorBoard, advisorRecipes, advisorCatalog, advisorPantry)
    }
    val slowBreakfastJson = """{"assistantSummary":"Swap","suggestedSwaps":[{"slotId":"slot_day_0_breakfast","recipeId":"rec_omega_fish_moyo_dinner"}]}"""
    assertThrows(ValidationException::class.java) {
      AiMenuAdvisorEngine.parseAndGroundAiResponse(slowBreakfastJson, "", advisorBoard, advisorRecipes, advisorCatalog, advisorPantry)
    }
  }

  /**
   * Verifies that [AiMenuAdvisorEngine.tryExecuteDeterministicToolIntent] **always** returns `null`
   * whenever `hasAttachedImage == true` (preventing pre-inference regex pantry mutations on photos)
   * while executing all 4 explicit text-only tools when `hasAttachedImage == false`.
   */
  @Test
  fun tryExecuteDeterministicToolIntent_returnsNullWheneverImageAttachedAndRoutesTextTools() {
    // Hard multimodal guardrail: even explicit purchase/queue/life-event text MUST return null with an image!
    assertNull(
      AiMenuAdvisorEngine.tryExecuteDeterministicToolIntent(
        userPrompt = "We bought Large Farm Eggs, Rolled Whole Grain Oats, and Extra-Virgin Olive Oil",
        hasAttachedImage = true,
        catalog = advisorCatalog,
        recipes = advisorRecipes,
        board = advisorBoard,
      ),
    )
    assertNull(
      AiMenuAdvisorEngine.tryExecuteDeterministicToolIntent(
        userPrompt = "Add blueberries to my grocery list",
        hasAttachedImage = true,
        catalog = advisorCatalog,
      ),
    )

    // Text-only Tool 1: restock_pantry_batch
    val restockTool = AiMenuAdvisorEngine.tryExecuteDeterministicToolIntent(
      "We bought Large Farm Eggs and Extra-Virgin Olive Oil", false, advisorCatalog,
    )
    assertNotNull(restockTool)
    assertEquals("restock_pantry_batch", requireNotNull(restockTool).toolName)
    assertEquals(setOf("ing_egg", "ing_olive_oil"), restockTool.restockDeltas.map { it.ingredientId }.toSet())

    // Text-only Tool 2: queue_grocery_items
    val queueTool = AiMenuAdvisorEngine.tryExecuteDeterministicToolIntent(
      "Add blueberries and oats to our grocery list", false, advisorCatalog,
    )
    assertEquals("queue_grocery_items", requireNotNull(queueTool).toolName)
    assertTrue("ing_blueberry" in queueTool.queuedGroceryIngredientIds && "ing_oats" in queueTool.queuedGroceryIngredientIds)

    // Text-only Tool 3: activate_life_event
    val lifeEventTool = AiMenuAdvisorEngine.tryExecuteDeterministicToolIntent(
      "Activate first trimester pregnancy protocol", false, advisorCatalog,
    )
    assertEquals("activate_life_event", requireNotNull(lifeEventTool).toolName)
    assertEquals("evt_pregnancy_t1", requireNotNull(lifeEventTool.activatedLifeEvent).eventId)

    // Text-only Tool 4: schedule_family_meal
    val scheduleTool = AiMenuAdvisorEngine.tryExecuteDeterministicToolIntent(
      "Schedule Omega-3 Roasted Fish & Banana Platter for Monday dinner", false, advisorCatalog, advisorRecipes, advisorBoard,
    )
    assertEquals("schedule_family_meal", requireNotNull(scheduleTool).toolName)
    assertEquals(0, requireNotNull(scheduleTool.scheduledDayIndex))
    assertEquals(MealSlotType.DINNER, scheduleTool.scheduledSlotType)
    assertEquals(omegaFishDinnerRecipe.recipeId, scheduleTool.scheduledRecipeId)
  }

  /**
   * Verifies [AiMenuAdvisorEngine.generatePantryGroundedMenuProposal], focus inference, system
   * prompt construction, conversational fast-path, and pure batch proposal application.
   */
  @Test
  fun generatePantryGroundedMenuProposal_prioritizesInStockBrainBoostersAndAppliesBatch() {
    // Check all five natural-language nutrition focus classifications.
    assertEquals(NutritionFocusTag.KIDS_BRAIN_BOOSTER, AiMenuAdvisorEngine.inferNutritionFocusFromPrompt("Kids brain boosters"))
    assertEquals(NutritionFocusTag.PANTRY_ZERO_WASTE, AiMenuAdvisorEngine.inferNutritionFocusFromPrompt("Use up perishables zero waste"))
    assertEquals(NutritionFocusTag.QUICK_MORNING_UNDER_20M, AiMenuAdvisorEngine.inferNutritionFocusFromPrompt("Fast breakfast under 20m"))
    assertEquals(NutritionFocusTag.IRON_AND_IMMUNITY, AiMenuAdvisorEngine.inferNutritionFocusFromPrompt("High iron and spinach meals"))
    assertEquals(NutritionFocusTag.HIGH_PROTEIN_FAMILY, AiMenuAdvisorEngine.inferNutritionFocusFromPrompt("Post workout high protein"))

    val systemPrompt = AiMenuAdvisorEngine.buildPantryAwareSystemPrompt(
      "Brain boosters for 8yo and 2yo", advisorBoard, advisorRecipes, advisorCatalog, advisorPantry,
    )
    assertTrue(systemPrompt.contains("Large Farm Eggs") && systemPrompt.contains("KIDS_BRAIN_BOOSTER"))

    // Verify offline proposal prioritizes Choline/Berry/Oat bowl for Monday BREAKFAST under 25m.
    val proposal = AiMenuAdvisorEngine.generatePantryGroundedMenuProposal(
      "We want brain boosters for the kids based on our pantry", advisorBoard, advisorRecipes, advisorCatalog, advisorPantry,
    )
    assertFalse(proposal.isConversationalOnly)
    val breakfastSwap = requireNotNull(proposal.slotSwaps.find { it.slotType == MealSlotType.BREAKFAST })
    assertEquals(brainBoostBowlRecipe.recipeId, breakfastSwap.suggestedRecipe.recipeId)
    assertTrue(breakfastSwap.suggestedRecipe.calibratedActiveMinutes <= MAX_WEEKDAY_BREAKFAST_MINUTES)
    assertFalse(breakfastSwap.suggestedRecipe.toddlerPrepTip.isNullOrBlank())

    // Pure batch application updates the board slot, merges catalog suggestions, and emits deltas.
    val applied = AiMenuAdvisorEngine.applyAiMenuProposalBatch(advisorBoard, advisorRecipes, advisorCatalog, proposal)
    val updatedMonBreakfast = requireNotNull(applied.updatedBoard.slots.find { it.slotId == "slot_day_0_breakfast" })
    assertEquals(brainBoostBowlRecipe.recipeId, updatedMonBreakfast.recipeId)
    assertTrue(applied.updatedCatalog.size > advisorCatalog.size && applied.groceryRestockDeltas.isNotEmpty())

    // Conversational pantry audit prompt sets isConversationalOnly = true with 0 swap clutter.
    val auditTurn = AiMenuAdvisorEngine.generatePantryGroundedMenuProposal(
      "What is in our pantry?", advisorBoard, advisorRecipes, advisorCatalog, advisorPantry,
    )
    assertTrue(auditTurn.isConversationalOnly && auditTurn.slotSwaps.isEmpty())
  }

  /**
   * Verifies [AiMenuAdvisorEngine.buildFamilyGoalPlatingGuide] across 4-member family goals and
   * enforces dynamic pregnancy food-safety guardrails (firm yolks, `74C` deli meat, `63C` fish).
   */
  @Test
  fun buildFamilyGoalPlatingGuide_adaptsFourMemberGoalsAndEnforcesPregnancySafetyGuardrails() {
    val eggTurkeyFishBowl = Recipe(
      recipeId = "rec_surf_turf_egg_bowl",
      title = "Family Egg, Turkey Ham & Snapper Rice Bowl",
      sourceAttribution = "Household One-Pot",
      allowedSlots = listOf(MealSlotType.DINNER),
      preferredOwner = CookOwner.WIFE,
      rawPrepMinutes = 8,
      cookMinutes = 15,
      calibratedActiveMinutes = 27,
      toddlerPrepTip = "Flake fish for zero bones and cut soft strips for 2yo.",
      bomLines = listOf(BomLine("ing_egg", 4.0), BomLine("ing_turkey_ham", 300.0), BomLine("ing_fish", 500.0)),
    )

    val pregnancyProfile = HouseholdProfile(
      youngestStage = ChildGrowthStage.BABY_6_12M,
      activeLifeEvents = listOf(HouseholdLifeEventPreset.PREGNANCY_TRIMESTER_1.toLifeEvent()),
    )
    val plating = AiMenuAdvisorEngine.buildFamilyGoalPlatingGuide(
      eggTurkeyFishBowl, advisorCatalog, pregnancyProfile.members, pregnancyProfile.activeLifeEvents, pregnancyProfile.youngestStage,
    )

    // Verify all 4 household members receive personalized 1-pot plating instructions.
    assertEquals(setOf("FRED", "WIFE", "OLDER_KID", "YOUNGEST"), plating.keys)
    assertTrue(requireNotNull(plating["FRED"]).contains("+1.5x"))
    assertTrue(requireNotNull(plating["YOUNGEST"]).contains("6-12m Baby"))

    // Wife's pregnancy plating must surface Folate/Choline AND strict heat/yolk food-safety rules.
    val wifeLine = requireNotNull(plating["WIFE"])
    assertTrue(wifeLine.contains("2x leafy greens") && wifeLine.contains("Folate"))
    assertTrue(wifeLine.contains("no runny yolks") && wifeLine.contains("74C") && wifeLine.contains("63C"))
  }

  /**
   * Verifies [AiMenuAdvisorEngine.createAndScheduleCustomFamilyMealBatch] schedules custom meals
   * across all `Monday–Friday` (`0..4`) slots in one atomic batch while leaving weekend slots
   * untouched and enforcing `EC-01` morning caps and `EC-02` local market availability.
   */
  @Test
  fun createAndScheduleCustomFamilyMealBatch_schedulesMonThroughFriAndEnforcesGates() {
    val batchResult = AiMenuAdvisorEngine.createAndScheduleCustomFamilyMealBatch(
      board = advisorBoard,
      existingRecipes = advisorRecipes,
      catalog = advisorCatalog,
      recipeId = "rec_custom_weekday_oat_scramble",
      title = "Weekday Egg & Banana Power Scramble",
      targetSlotType = MealSlotType.BREAKFAST,
      applyMondayThroughFriday = true,
      rawPrepMinutes = 5,
      cookMinutes = 10,
      bomLines = listOf(BomLine("ing_egg", 4.0), BomLine("ing_banana", 200.0)),
      queueIngredientsToGrocery = true,
      existingQueuedGroceryIds = setOf("ing_oats"),
    )

    // Calibrated time: ceil(5 * 1.4) + 10 = 7 + 10 = 17m (<= 25m cap).
    assertEquals(17, batchResult.customRecipe.calibratedActiveMinutes)
    assertTrue(batchResult.updatedRecipes.any { it.recipeId == "rec_custom_weekday_oat_scramble" })
    assertEquals(setOf("ing_oats", "ing_egg", "ing_banana"), batchResult.queuedGroceryIngredientIds)

    // Monday..Friday (0..4) BREAKFAST slots update; Saturday..Sunday (5..6) stay on plain toast.
    val breakfastsByDay = batchResult.updatedBoard.slots
      .filter { it.slotType == MealSlotType.BREAKFAST }
      .associateBy { it.dayIndex }
    for (weekday in 0..4) {
      assertEquals("rec_custom_weekday_oat_scramble", requireNotNull(breakfastsByDay[weekday]).recipeId)
    }
    assertEquals(plainToastRecipe.recipeId, requireNotNull(breakfastsByDay[5]).recipeId)
    assertEquals(plainToastRecipe.recipeId, requireNotNull(breakfastsByDay[6]).recipeId)

    // Reject > 25m custom breakfasts (EC-01) and locally unavailable ingredients (EC-02).
    assertThrows(ValidationException::class.java) {
      AiMenuAdvisorEngine.createCustomFamilyRecipe(
        "rec_too_slow_b", "Slow Waffles", listOf(MealSlotType.BREAKFAST), 15, 12, listOf(BomLine("ing_egg", 4.0)), advisorCatalog,
      )
    }
    val catalogWithoutFish = PantryLedgerEngine.toggleLocalAvailability(advisorCatalog, "ing_fish", false)
    assertThrows(IngredientUnavailableException::class.java) {
      AiMenuAdvisorEngine.createCustomFamilyRecipe(
        "rec_unavailable_fish", "Fish Bowl", listOf(MealSlotType.DINNER), 8, 15, listOf(BomLine("ing_fish", 600.0)), catalogWithoutFish,
      )
    }
  }

  /**
   * Verifies [synthesizeCatalogItemFromLabel] and [AiMenuAdvisorEngine.parseVisualIntakeJson] on the
   * user's exact Spice Photo scenario (`"The image displays several jars and bottles containing
   * spices and herbs, including ground turmeric, bay leaves, and flakes of parsley, oregano"`) across
   * both `detectedItems` (`matchedIngredientId = null`) and `summary` fallback while excluding
   * non-food household objects (`"Ceramic Coffee Mug"`).
   */
  @Test
  fun spicePhotoSynthesisAndVisualParsing_synthesizesTier2SpicesAndExcludesNonFoodMug() {
    // Container prefixes strip cleanly ("flakes of parsley" -> "Parsley Flakes") and non-foods return null.
    assertNull(synthesizeCatalogItemFromLabel("Ceramic Coffee Mug"))
    assertNull(synthesizeCatalogItemFromLabel("Paper Receipt"))
    assertNull(synthesizeCatalogItemFromLabel("glass jars"))

    val parsley = requireNotNull(synthesizeCatalogItemFromLabel("flakes of parsley"))
    assertEquals("ing_custom_parsley_flakes", parsley.ingredientId)
    assertEquals("Parsley Flakes", parsley.displayName)
    assertEquals(IngredientCategory.PANTRY_STAPLE, parsley.category)
    assertEquals(IngredientTier.TIER_2_STAPLE, parsley.tier)
    assertEquals(StorageLocation.PANTRY, parsley.defaultStorageLocation)

    val spiceSummary =
      "The image displays several jars and bottles containing spices and herbs, " +
        "including ground turmeric, bay leaves, and flakes of parsley, oregano"
    val expectedNames = listOf("Ground Turmeric", "Bay Leaves", "Parsley Flakes", "Dried Oregano")

    // 1. detectedItems with matchedIngredientId = null + non-food "Ceramic Coffee Mug".
    val detectedJson = """
      {
        "summary": "$spiceSummary",
        "detectedItems": [
          {"rawLabel": "ground turmeric", "matchedIngredientId": null, "estimatedQuantity": 0, "confidence": 0.95},
          {"rawLabel": "bay leaves", "matchedIngredientId": null, "estimatedQuantity": 0, "confidence": 0.93},
          {"rawLabel": "flakes of parsley", "matchedIngredientId": null, "estimatedQuantity": 0, "confidence": 0.91},
          {"rawLabel": "oregano", "matchedIngredientId": null, "estimatedQuantity": 0, "confidence": 0.92}
        ],
        "unrecognizedLabels": ["Ceramic Coffee Mug"]
      }
    """.trimIndent()
    val fromDetected = AiMenuAdvisorEngine.parseVisualIntakeJson(detectedJson, advisorCatalog)
    assertTrue(fromDetected.groundedRestockDeltas.isEmpty())
    assertEquals(expectedNames, fromDetected.proposedNewCatalogItems.map { it.displayName })
    assertTrue(fromDetected.proposedNewCatalogItems.all { it.tier == IngredientTier.TIER_2_STAPLE })
    assertTrue("Ceramic Coffee Mug" in fromDetected.unrecognizedLabels)
    assertTrue(fromDetected.proposedNewCatalogItems.none { it.displayName.contains("Mug", ignoreCase = true) })

    // 2. Empty detectedItems fallback extracting spices from summary while keeping Mug excluded.
    val summaryOnlyJson =
      """{"summary":"$spiceSummary","detectedItems":[],"unrecognizedLabels":["Ceramic Coffee Mug"]}"""
    val fromSummary = AiMenuAdvisorEngine.parseVisualIntakeJson(summaryOnlyJson, advisorCatalog)
    assertEquals(expectedNames, fromSummary.proposedNewCatalogItems.map { it.displayName })
    assertTrue(fromSummary.proposedNewCatalogItems.none { it.displayName.contains("Mug", ignoreCase = true) })
  }

  /**
   * Verifies expanded natural-language text tools (`"Add ground turmeric, bay leaves, and eggs to my
   * pantry"`, `"We ran out of olive oil"`, `"Add calamansi to our grocery list"`, `"Swap tonight's
   * dinner"`) while ensuring `hasAttachedImage == true` **always** returns `null`.
   */
  @Test
  fun expandedTextOnlyTools_stockMixedSpicesDepleteOilQueueCalamansiSwapDinnerAndBlockImages() {
    val secondDinnerRecipe = Recipe(
      recipeId = "rec_turkey_baguette_melt",
      title = "Warm Turkey Ham & Egg Baguette Melt",
      sourceAttribution = "Evening Bistro",
      allowedSlots = listOf(MealSlotType.LUNCH, MealSlotType.DINNER),
      preferredOwner = CookOwner.WIFE,
      rawPrepMinutes = 6,
      cookMinutes = 12,
      calibratedActiveMinutes = 21,
      morningSteps = listOf(MorningStep(1, "Layer turkey ham and eggs in warm baguette.", 720)),
      toddlerPrepTip = "Cut soft strips without hard crust for 2yo.",
      bomLines = listOf(BomLine("ing_baguette", 1.0), BomLine("ing_turkey_ham", 200.0), BomLine("ing_egg", 4.0)),
    )
    val dinnerSwapRecipes = advisorRecipes + secondDinnerRecipe

    // Hard image safety gate: every new NL command phrasing still returns null when an image is attached!
    for (prompt in listOf(
      "Add ground turmeric, bay leaves, and eggs to my pantry",
      "We ran out of olive oil",
      "Add calamansi to our grocery list",
      "Swap tonight's dinner",
    )) {
      assertNull(
        tryExecuteDeterministicToolIntent(
          userPrompt = prompt,
          hasAttachedImage = true,
          catalog = advisorCatalog,
          recipes = dinnerSwapRecipes,
          board = advisorBoard,
          pantry = advisorPantry,
        ),
      )
    }

    // 1. Mixed existing + newly synthesized spices restock into Pantry with calm human summary.
    val restockMix = requireNotNull(
      tryExecuteDeterministicToolIntent(
        userPrompt = "Add ground turmeric, bay leaves, and eggs to my pantry",
        hasAttachedImage = false,
        catalog = advisorCatalog,
      ),
    )
    assertEquals("restock_pantry_batch", restockMix.toolName)
    assertFalse(restockMix.summaryMessage.contains("Executed on-device tool"))
    assertEquals(
      setOf("ing_custom_ground_turmeric", "ing_custom_bay_leaves", "ing_egg"),
      restockMix.restockDeltas.map { it.ingredientId }.toSet(),
    )
    assertEquals(2, restockMix.synthesizedCatalogItems.size)

    // 2. Deplete pantry item marks Tier-2 olive oil out of stock.
    val depleteTool = requireNotNull(
      tryExecuteDeterministicToolIntent("We ran out of olive oil", false, advisorCatalog),
    )
    assertEquals("deplete_pantry_items", depleteTool.toolName)
    assertEquals(listOf("ing_olive_oil"), depleteTool.outOfStockIngredientIds)

    // 3. Queue unknown local market produce ("calamansi") synthesizes catalog item + grocery queue ID.
    val queueCalamansi = requireNotNull(
      tryExecuteDeterministicToolIntent("Add calamansi to our grocery list", false, advisorCatalog),
    )
    assertEquals("queue_grocery_items", queueCalamansi.toolName)
    assertTrue("ing_custom_calamansi" in queueCalamansi.queuedGroceryIngredientIds)
    assertEquals(IngredientCategory.PRODUCE, queueCalamansi.synthesizedCatalogItems.single().category)

    // 4. "Swap tonight's dinner" selects the 100%-in-stock EC-13 replacement for Monday dinner.
    val swapTonight = requireNotNull(
      tryExecuteDeterministicToolIntent(
        userPrompt = "Swap tonight's dinner",
        hasAttachedImage = false,
        catalog = advisorCatalog,
        recipes = dinnerSwapRecipes,
        board = advisorBoard,
        pantry = advisorPantry,
        activeDayIndex = 0,
      ),
    )
    assertEquals("schedule_family_meal", swapTonight.toolName)
    assertEquals(0, requireNotNull(swapTonight.scheduledDayIndex))
    assertEquals(MealSlotType.DINNER, swapTonight.scheduledSlotType)
    assertEquals(secondDinnerRecipe.recipeId, swapTonight.scheduledRecipeId)
  }
}
