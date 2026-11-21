package com.familykitchen.board.domain

import com.familykitchen.board.data.FakeKitchenStorage
import kotlin.io.path.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// Hermetic JVM unit test suite verifying PantryExpiryEngine, CookModeEngine (bilingual voice +
// concurrent multi-timers), upgraded GroceryDiffEngine deduplication/sorting, ConsumerHouseholdTemplate
// onboarding, and KitchenStoragePort O(1) UI mutation helpers.

/**
 * Hermetic JUnit 4 verification suite covering [PantryExpiryEngine], [CookModeEngine], upgraded
 * [GroceryDiffEngine] deduplication & sort modes, [ConsumerHouseholdTemplate] onboarding presets,
 * and [com.familykitchen.board.ports.KitchenStoragePort] `O(1)` atomic mutation helpers.
 */
class PantryExpiryAndCookModeTest {

  // Compact catalog fixture with USDA-aligned shelf lives and BLS retail package prices.
  private val expiryCatalog: List<IngredientCatalogItem> = listOf(
    IngredientCatalogItem(
      ingredientId = "ing_salmon",
      displayName = "Atlantic Salmon Fillets",
      category = IngredientCategory.PROTEIN,
      tier = IngredientTier.TIER_1_QUANTITATIVE,
      baseUnit = BaseUnit.GRAM,
      defaultPackageQuantity = 800.0,
      allergens = listOf("FISH"),
      defaultStorageLocation = StorageLocation.FRIDGE,
      defaultShelfLifeDays = 2,
      estimatedPackagePriceUsd = 19.80,
    ),
    IngredientCatalogItem(
      ingredientId = "ing_fish",
      displayName = "Whole Fresh Snapper",
      category = IngredientCategory.PROTEIN,
      tier = IngredientTier.TIER_1_QUANTITATIVE,
      baseUnit = BaseUnit.GRAM,
      defaultPackageQuantity = 1000.0,
      allergens = listOf("FISH"),
      defaultStorageLocation = StorageLocation.FRIDGE,
      defaultShelfLifeDays = 2,
      estimatedPackagePriceUsd = 11.49,
    ),
    IngredientCatalogItem(
      ingredientId = "ing_spinach",
      displayName = "Fresh Baby Spinach",
      category = IngredientCategory.PRODUCE,
      tier = IngredientTier.TIER_1_QUANTITATIVE,
      baseUnit = BaseUnit.GRAM,
      defaultPackageQuantity = 300.0,
      substituteGroupIds = listOf("LEAFY_GREENS"),
      defaultStorageLocation = StorageLocation.FRIDGE,
      defaultShelfLifeDays = 5,
      estimatedPackagePriceUsd = 3.49,
    ),
    IngredientCatalogItem(
      ingredientId = "ing_butter",
      displayName = "Unsalted Creamery Butter",
      category = IngredientCategory.DAIRY_EGGS,
      tier = IngredientTier.TIER_1_QUANTITATIVE,
      baseUnit = BaseUnit.GRAM,
      defaultPackageQuantity = 250.0,
      allergens = listOf("DAIRY"),
      defaultStorageLocation = StorageLocation.FRIDGE,
      defaultShelfLifeDays = 30,
      estimatedPackagePriceUsd = 2.99,
    ),
    IngredientCatalogItem(
      ingredientId = "ing_greek_yogurt",
      displayName = "Plain Greek Yogurt",
      category = IngredientCategory.DAIRY_EGGS,
      tier = IngredientTier.TIER_1_QUANTITATIVE,
      baseUnit = BaseUnit.GRAM,
      defaultPackageQuantity = 1000.0,
      allergens = listOf("DAIRY"),
      defaultStorageLocation = StorageLocation.FRIDGE,
      defaultShelfLifeDays = 10,
      estimatedPackagePriceUsd = 5.49,
    ),
    IngredientCatalogItem(
      ingredientId = "ing_egg",
      displayName = "Large Farm Eggs",
      category = IngredientCategory.DAIRY_EGGS,
      tier = IngredientTier.TIER_1_QUANTITATIVE,
      baseUnit = BaseUnit.COUNT,
      defaultPackageQuantity = 12.0,
      allergens = listOf("EGGS"),
      defaultStorageLocation = StorageLocation.FRIDGE,
      defaultShelfLifeDays = 28,
      estimatedPackagePriceUsd = 2.35,
    ),
    IngredientCatalogItem(
      ingredientId = "ing_rice",
      displayName = "Jasmine Rice",
      category = IngredientCategory.BAKERY_GRAINS,
      tier = IngredientTier.TIER_1_QUANTITATIVE,
      baseUnit = BaseUnit.GRAM,
      defaultPackageQuantity = 2000.0,
      defaultStorageLocation = StorageLocation.PANTRY,
      defaultShelfLifeDays = 365,
      estimatedPackagePriceUsd = 5.10,
    ),
    IngredientCatalogItem(
      ingredientId = "ing_olive_oil",
      displayName = "Extra-Virgin Olive Oil",
      category = IngredientCategory.PANTRY_STAPLE,
      tier = IngredientTier.TIER_2_STAPLE,
      baseUnit = BaseUnit.MILLILITER,
      defaultPackageQuantity = 750.0,
      defaultStorageLocation = StorageLocation.PANTRY,
      defaultShelfLifeDays = 365,
      estimatedPackagePriceUsd = 12.49,
    ),
  )

  private val salmonRescueRecipe: Recipe = Recipe(
    recipeId = "rec_salmon_spinach_skillet",
    title = "One-Pan Lemon Salmon & Spinach Rice",
    sourceAttribution = "Mediterranean Family Kitchen",
    allowedSlots = listOf(MealSlotType.LUNCH, MealSlotType.DINNER),
    preferredOwner = CookOwner.WIFE,
    baseServings = 4,
    rawPrepMinutes = 8,
    cookMinutes = 20,
    calibratedActiveMinutes = 32,
    morningSteps = listOf(
      MorningStep(
        stepNumber = 1,
        instruction = "Pat salmon dry and sear skin-side down in butter until crisp.",
        timerSeconds = 240,
        title = "The Sear",
        techniqueBadge = "Skin-Crisping",
        visualTargetCue = "Deep golden skin releasing cleanly",
        chefTip = "Press gently with a fish spatula for the first 20 seconds.",
        stepIngredientIds = listOf("ing_salmon", "ing_butter"),
      ),
      MorningStep(
        stepNumber = 2,
        instruction = "Simmer jasmine rice until fluffy and tender.",
        timerSeconds = 600,
        title = "Steam Rice",
        visualTargetCue = "Steam holes across surface",
        stepIngredientIds = listOf("ing_rice"),
      ),
      MorningStep(
        stepNumber = 3,
        instruction = "Fold fresh baby spinach into warm pan juices off the heat.",
        timerSeconds = 120,
        title = "Wilt Greens & Plate",
        stepIngredientIds = listOf("ing_spinach"),
      ),
    ),
    toddlerPrepTip = "Flake salmon center carefully to confirm zero pin-bones for 2yo.",
    bomLines = listOf(
      BomLine("ing_salmon", 600.0),
      BomLine("ing_spinach", 200.0, substituteGroupId = "LEAFY_GREENS"),
      BomLine("ing_butter", 40.0),
      BomLine("ing_rice", 350.0),
      BomLine("ing_olive_oil", 15.0),
    ),
    householdBomOverrides = mapOf("ing_rice" to 300.0),
    caloriesPerServing = 580,
    proteinGrams = 36,
    carbsGrams = 68,
    fatGrams = 29,
    chefPairingNote = "Serve with chilled lemon-mint sparkling water.",
  )

  /**
   * Verifies [PantryExpiryEngine.evaluatePantryExpiryDashboard] against canonical Antipolo–Marikina
   * PHP (`₱`) `seed_catalog.json` (`ing_salmon` `1d`, `ing_greek_yogurt` `2d`, `ing_spinach` `2d`),
   * checking `₱3,200` (`3200.0`) at-risk value, `96%` waste score, rescue recipes, and storage zones.
   */
  @Test
  fun pantryExpiryDashboard_evaluatesCanonicalSeedExpiringItemsAtRiskUsdAndRescueRecipes() {
    val rawSeedJson = readCanonicalSeedCatalogJson()
    val dataset = SchemaValidator.decodeSeedCatalogDatasetJson(rawSeedJson)

    // Consumer-distributable check: zero fictional surname strings in public seed attributions.
    assertFalse(rawSeedJson.lowercase().contains("adeniyi"))

    val dashboard = PantryExpiryEngine.evaluatePantryExpiryDashboard(
      pantry = dataset.initialPantry,
      catalog = dataset.ingredients,
      recipes = dataset.recipes,
      rescuedMealsCount = 12,
    )

    // Verify all 44 items are stocked and sweet potatoes stay in cool dark PANTRY per USDA FoodKeeper.
    assertEquals(44, dashboard.totalItems)
    assertTrue(dashboard.fridgeItems.isNotEmpty() && dashboard.dryPantryItems.isNotEmpty())
    assertTrue(dashboard.dryPantryItems.any { it.ingredientId == "ing_sweet_potato" })

    // Verify the 3 out-of-the-box expiring items ordered by urgency (1d Tomorrow ahead of 2d).
    assertEquals(
      listOf("ing_salmon", "ing_greek_yogurt", "ing_spinach"),
      dashboard.expiringItems.map { it.catalogItem.ingredientId },
    )
    val salmonCard = dashboard.expiringItems.first()
    assertEquals(ExpiryStatus.EXPIRING_SOON, salmonCard.expiryStatus)
    assertEquals(1, salmonCard.expiresInDays)
    assertEquals("Tomorrow", salmonCard.urgencyLabel)
    assertEquals(0.90f, salmonCard.freshnessProgress, 0.001f)
    assertEquals(2240.0, salmonCard.atRiskValueUsd, 0.01)
    assertEquals("₱2,240", salmonCard.formattedAtRiskValue())
    assertTrue(salmonCard.matchingRescueRecipes.any { it.recipeId == "rec_20_one_pan_lemon_herb_salmon_rice" })
    assertEquals("ing_spinach", requireNotNull(salmonCard.coExpiringPairIngredient).ingredientId)

    // Total Antipolo-Marikina PHP value at risk (₱2,240 + ₱790 + ₱170 = ₱3,200) and 96% Waste Score.
    assertEquals(3200.0, dashboard.atRiskValueUsd, 0.01)
    assertEquals("₱3,200", dashboard.formattedAtRiskValue())
    assertEquals(96, dashboard.wasteScorePercent)
    assertTrue(dashboard.rescueRecipes.isNotEmpty())
  }

  /**
   * Verifies [PantryExpiryEngine.evaluateRecipePantryMatch] distinguishes matched stock from
   * missing staples, pre-computes whole-package [RestockDelta]s, and credits rescued expiry value.
   */
  @Test
  fun recipePantryMatch_computesMatchedVsMissingStaplesAndZeroWasteRescueValue() {
    val fullPantry = listOf(
      PantryLedgerEntry("ing_salmon", IngredientTier.TIER_1_QUANTITATIVE, 800.0, expiresInDays = 1),
      PantryLedgerEntry("ing_spinach", IngredientTier.TIER_1_QUANTITATIVE, 300.0, expiresInDays = 2),
      PantryLedgerEntry("ing_butter", IngredientTier.TIER_1_QUANTITATIVE, 250.0, expiresInDays = 20),
      PantryLedgerEntry("ing_rice", IngredientTier.TIER_1_QUANTITATIVE, 1000.0, expiresInDays = 180),
      PantryLedgerEntry("ing_olive_oil", IngredientTier.TIER_2_STAPLE, 0.0, StapleStatus.PLENTY),
    )

    // 100% in-stock match rescuing both expiring salmon (1d) and spinach (2d).
    val fullMatch = PantryExpiryEngine.evaluateRecipePantryMatch(salmonRescueRecipe, fullPantry, expiryCatalog)
    assertEquals(100, fullMatch.matchPercentage)
    assertTrue(fullMatch.missingStaples.isEmpty())
    assertTrue(fullMatch.isZeroFoodWasteMatch)
    assertEquals(listOf("ing_salmon", "ing_spinach"), fullMatch.rescuedExpiringIngredientIds)
    assertTrue(fullMatch.rescuedValueUsd > 15.0)

    // Deplete salmon (0g) and mark Tier-2 olive oil OUT -> 3 of 5 matched (60%) + 2 whole-package deltas.
    val partialPantry = fullPantry.map { entry ->
      when (entry.ingredientId) {
        "ing_salmon" -> entry.copy(currentQuantity = 0.0, isOutOfStock = true)
        "ing_olive_oil" -> entry.copy(stapleStatus = StapleStatus.OUT, isOutOfStock = true)
        else -> entry
      }
    }
    val partialMatch = PantryExpiryEngine.evaluateRecipePantryMatch(salmonRescueRecipe, partialPantry, expiryCatalog)
    assertEquals(60, partialMatch.matchPercentage)
    assertEquals(setOf("ing_salmon", "ing_olive_oil"), partialMatch.missingStaples.map { it.ingredientId }.toSet())
    val salmonDelta = requireNotNull(partialMatch.missingRestockDeltas.find { it.ingredientId == "ing_salmon" })
    assertEquals(800.0, salmonDelta.addedBaseQuantity, 0.001)
  }

  /**
   * Verifies [PantryExpiryEngine.scaleRecipeForYield] (`1x`, `2x`, `4x`, custom `1.5x`) and
   * USDA-FDC [PantryExpiryEngine.swapRecipeIngredient] calorie and macro recalculation.
   */
  @Test
  fun scaleRecipeForYieldAndSwapIngredient_scalesServingsAndRecomputesUsdaMacros() {
    val oneX = PantryExpiryEngine.scaleRecipeForYield(salmonRescueRecipe, 1.0, expiryCatalog)
    val twoX = PantryExpiryEngine.scaleRecipeForYield(salmonRescueRecipe, 2.0, expiryCatalog)
    val fourX = PantryExpiryEngine.scaleRecipeForYield(salmonRescueRecipe, expiryCatalog, 4.0)
    val customOneHalfX = PantryExpiryEngine.scaleRecipeForYield(salmonRescueRecipe, 1.5)

    assertEquals(4, oneX.baseServings)
    assertEquals(8, twoX.baseServings)
    assertEquals(16, fourX.baseServings)
    assertEquals(6, customOneHalfX.baseServings)
    assertEquals(1200.0, twoX.bomLines.first { it.ingredientId == "ing_salmon" }.baseQuantity, 0.001)
    assertEquals(600.0, requireNotNull(twoX.householdBomOverrides["ing_rice"]), 0.001)

    assertThrows(ValidationException::class.java) {
      PantryExpiryEngine.scaleRecipeForYield(salmonRescueRecipe, 0.0)
    }

    // Swapping 40g butter (717 kcal/100g, 81.1g fat) -> 40g Greek yogurt (73 kcal/100g, 1.9g fat)
    // lowers calories (~-64 kcal/serving) and fat (~-8g/serving) while updating stepIngredientIds.
    val lighterRecipe = PantryExpiryEngine.swapRecipeIngredient(
      recipe = salmonRescueRecipe,
      fromIngredientId = "ing_butter",
      toIngredientId = "ing_greek_yogurt",
      catalog = expiryCatalog,
    )
    assertTrue(lighterRecipe.bomLines.any { it.ingredientId == "ing_greek_yogurt" })
    assertTrue("ing_greek_yogurt" in lighterRecipe.morningSteps.first().stepIngredientIds)
    assertTrue(lighterRecipe.caloriesPerServing < salmonRescueRecipe.caloriesPerServing)
    assertTrue(lighterRecipe.fatGrams < salmonRescueRecipe.fatGrams)

    // Swapping to a locally unavailable ingredient must throw IngredientUnavailableException.
    val catalogUnavailableFish = PantryLedgerEngine.toggleLocalAvailability(expiryCatalog, "ing_fish", false)
    assertThrows(IngredientUnavailableException::class.java) {
      PantryExpiryEngine.swapRecipeIngredient(salmonRescueRecipe, "ing_salmon", "ing_fish", catalogUnavailableFish)
    }
  }

  /**
   * Verifies [CookModeEngine] session initialization, step navigation, concurrent foreground and
   * background [StepTimerState] countdowns, `+1 Min` extension, and baseline reset.
   */
  @Test
  fun cookModeEngine_managesStepsConcurrentTimersPlusOneMinuteAndBaselineReset() {
    var session = CookModeEngine.startCookSession(
      recipe = salmonRescueRecipe,
      slotId = "slot_wed_dinner",
      catalog = expiryCatalog,
    )
    assertTrue(session.isFirstStep)
    assertFalse(session.isFinalStep)
    assertEquals("Steam Rice", session.nextStepTitle)
    assertEquals("04:00", requireNotNull(session.activeStepTimer).formattedRemainingMmSs)

    // Toggle ingredient checkmark and start Step 1 timer (240s), then advance to Step 2 (600s).
    session = CookModeEngine.toggleStepIngredient(session, "ing_salmon")
    assertTrue("ing_salmon" in session.checkedIngredientIds)
    session = CookModeEngine.toggleStepTimer(session)
    session = CookModeEngine.advanceStep(session)
    assertEquals(1, session.currentStepIndex)

    // Start Step 2 timer concurrently while Step 1 continues running in backgroundRunningTimers.
    session = CookModeEngine.toggleStepTimer(session)
    assertEquals(1, session.backgroundRunningTimers.size)
    assertEquals(1, session.backgroundRunningTimers.single().stepNumber)

    // Tick 100 elapsed seconds: Step 1 drops 240 -> 140s ("02:20") and Step 2 drops 600 -> 500s.
    session = CookModeEngine.tickRunningTimers(session, elapsedSeconds = 100)
    assertEquals(140, requireNotNull(session.timersByStepNumber[1]).remainingSeconds)
    assertEquals("02:20", requireNotNull(session.timersByStepNumber[1]).formattedRemainingMmSs)
    assertEquals(500, requireNotNull(session.activeStepTimer).remainingSeconds)

    // Add +1 Min (+60s) to Step 2 -> 560s, then reset Step 2 back to baseline 600s paused.
    session = CookModeEngine.addOneMinuteToTimer(session)
    assertEquals(560, requireNotNull(session.activeStepTimer).remainingSeconds)
    session = CookModeEngine.resetStepTimer(session)
    assertEquals(600, requireNotNull(session.activeStepTimer).remainingSeconds)
    assertFalse(requireNotNull(session.activeStepTimer).isRunning)

    // Tick 200 more seconds so background Step 1 timer completes cleanly at 00:00 (isCompleted = true).
    session = CookModeEngine.tickRunningTimers(session, elapsedSeconds = 200)
    val completedStep1Timer = requireNotNull(session.timersByStepNumber[1])
    assertEquals(0, completedStep1Timer.remainingSeconds)
    assertTrue(completedStep1Timer.isCompleted)
    assertFalse(completedStep1Timer.isRunning)

    // Verify jumpToStep and previousStep boundary clamping.
    session = CookModeEngine.jumpToStep(session, 2)
    assertTrue(session.isFinalStep)
    session = CookModeEngine.previousStep(session)
    assertEquals(1, session.currentStepIndex)
  }

  /**
   * Verifies bilingual (`en-US` and `fr-FR`) [CookModeEngine.parseCookModeVoiceIntent],
   * [CookModeEngine.answerCookStepQuestion], and [CookModeEngine.applyVoiceTranscriptToCookSession].
   */
  @Test
  fun cookModeEngine_parsesBilingualEnUsAndFrFrVoiceIntentsAndAnswersSousChefQuestions() {
    // English and French navigation & timer intents map deterministically to identical sealed objects.
    assertEquals(CookModeVoiceIntent.NextStep, CookModeEngine.parseCookModeVoiceIntent("next step"))
    assertEquals(CookModeVoiceIntent.NextStep, CookModeEngine.parseCookModeVoiceIntent("étape suivante"))
    assertEquals(CookModeVoiceIntent.PreviousStep, CookModeEngine.parseCookModeVoiceIntent("previous step"))
    assertEquals(CookModeVoiceIntent.PreviousStep, CookModeEngine.parseCookModeVoiceIntent("étape précédente"))
    assertEquals(CookModeVoiceIntent.ResumeTimer, CookModeEngine.parseCookModeVoiceIntent("start timer"))
    assertEquals(CookModeVoiceIntent.ResumeTimer, CookModeEngine.parseCookModeVoiceIntent("lance le minuteur"))
    assertEquals(CookModeVoiceIntent.PauseTimer, CookModeEngine.parseCookModeVoiceIntent("pause timer"))
    assertEquals(CookModeVoiceIntent.PauseTimer, CookModeEngine.parseCookModeVoiceIntent("mets en pause"))
    assertEquals(CookModeVoiceIntent.AddOneMinute, CookModeEngine.parseCookModeVoiceIntent("add one minute"))
    assertEquals(CookModeVoiceIntent.AddOneMinute, CookModeEngine.parseCookModeVoiceIntent("ajoute une minute"))
    assertEquals(CookModeVoiceIntent.ResetTimer, CookModeEngine.parseCookModeVoiceIntent("reset timer"))
    assertEquals(CookModeVoiceIntent.ResetTimer, CookModeEngine.parseCookModeVoiceIntent("réinitialiser"))
    assertEquals(CookModeVoiceIntent.OpenIngredients, CookModeEngine.parseCookModeVoiceIntent("ouvrir les ingrédients"))

    // Grounded Q&A answers toddler choking-safety, Greek yogurt substitution, and doneness questions.
    val toddlerAnswer = CookModeEngine.answerCookStepQuestion(
      salmonRescueRecipe, 0, "How do I make this safe for our toddler?", expiryCatalog, ChildGrowthStage.TODDLER_1_2Y,
    )
    assertTrue(toddlerAnswer.contains("For 2yo") && toddlerAnswer.contains("pin-bones"))

    val swapAnswer = CookModeEngine.answerCookStepQuestion(
      salmonRescueRecipe, 0, "Can I substitute butter or heavy cream with Greek yogurt?", expiryCatalog,
    )
    assertTrue(swapAnswer.contains("Greek Yogurt") && swapAnswer.contains("65 kcal"))

    // Apply spoken French command + English question directly onto an active CookModeSessionState.
    val initial = CookModeEngine.startCookSession(salmonRescueRecipe, catalog = expiryCatalog)
    val advancedFr = CookModeEngine.applyVoiceTranscriptToCookSession(initial, "étape suivante", expiryCatalog)
    assertEquals(1, advancedFr.currentStepIndex)
    val answered = CookModeEngine.applyVoiceTranscriptToCookSession(advancedFr, "What should we drink or pair with this?", expiryCatalog)
    assertTrue(requireNotNull(answered.lastSousChefAnswer).contains("lemon-mint"))
  }

  /**
   * Verifies upgraded [GroceryDiffEngine.computeWeeklyGroceryDiff]: `mealUsageLabels`,
   * `packageDescriptor`, `skippedStockedStapleCount`, `deduplicationSavingsUsd`, checked cost
   * deduction, and `GrocerySortMode` (`AISLE_SORTED`, `BY_RECIPE`, `URGENCY_FIRST`).
   */
  @Test
  fun upgradedGroceryDiffEngine_populatesMealUsageLabelsDeduplicationSavingsAndSortModes() {
    val board = WeeklyBoard(
      boardId = "board_savor_grocery",
      householdId = "household_family_4",
      weekStartDateIso = "2026-10-26",
      slots = listOf(
        MealSlot("slot_wed_dinner", 2, MealSlotType.DINNER, CookOwner.WIFE, salmonRescueRecipe.recipeId),
        MealSlot("slot_fri_dinner", 4, MealSlotType.DINNER, CookOwner.WIFE, salmonRescueRecipe.recipeId),
      ),
    )
    // Stock rice (2000g) and butter (250g) in full while leaving salmon/spinach short and olive oil LOW.
    val pantry = listOf(
      PantryLedgerEntry("ing_salmon", IngredientTier.TIER_1_QUANTITATIVE, 200.0),
      PantryLedgerEntry("ing_spinach", IngredientTier.TIER_1_QUANTITATIVE, 0.0, StapleStatus.OUT, isOutOfStock = true),
      PantryLedgerEntry("ing_butter", IngredientTier.TIER_1_QUANTITATIVE, 250.0),
      PantryLedgerEntry("ing_rice", IngredientTier.TIER_1_QUANTITATIVE, 2000.0),
      PantryLedgerEntry("ing_olive_oil", IngredientTier.TIER_2_STAPLE, 100.0, StapleStatus.LOW),
    )

    val aisleDiff = GroceryDiffEngine.computeWeeklyGroceryDiff(
      board = board,
      recipes = listOf(salmonRescueRecipe),
      catalog = expiryCatalog,
      pantry = pantry,
      aiAddedGroceryIds = setOf("ing_egg"),
      grocerySortMode = GrocerySortMode.AISLE_SORTED,
    )

    // Rice and butter are 100% covered at home -> 2 skipped stocked staples with > $8 deduplication savings.
    assertEquals(2, aisleDiff.plannedMealCount)
    assertEquals(2, aisleDiff.skippedStockedStapleCount)
    assertTrue(aisleDiff.deduplicationSavingsUsd >= 8.0)

    val salmonItem = requireNotNull(aisleDiff.groceryItems.find { it.ingredientId == "ing_salmon" })
    assertTrue(salmonItem.mealUsageLabels.any { it.contains("for Wednesday Dinner • One-Pan Lemon Salmon") })
    assertTrue(salmonItem.packageDescriptor.contains("2 packs"))
    assertEquals(39.60, salmonItem.estimatedCostUsd, 0.01)

    // Checking off ing_salmon reduces remaining estimatedTotalCostUsd by $39.60.
    val checkedDiff = GroceryDiffEngine.computeWeeklyGroceryDiff(
      board = board,
      recipes = listOf(salmonRescueRecipe),
      catalog = expiryCatalog,
      pantry = pantry,
      aiAddedGroceryIds = setOf("ing_egg"),
      checkedGroceryItemIds = setOf("ing_salmon"),
      grocerySortMode = GrocerySortMode.URGENCY_FIRST,
    )
    assertEquals(aisleDiff.estimatedTotalCostUsd - 39.60, checkedDiff.estimatedTotalCostUsd, 0.02)
    // URGENCY_FIRST places multi-package quantitative shortages ahead of Tier-2 staples and manual items.
    assertEquals("ing_salmon", checkedDiff.groceryItems.first().ingredientId)
    assertEquals("ing_egg", checkedDiff.groceryItems.last().ingredientId)

    val byRecipeDiff = GroceryDiffEngine.computeWeeklyGroceryDiff(
      board = board,
      recipes = listOf(salmonRescueRecipe),
      catalog = expiryCatalog,
      pantry = pantry,
      aiAddedGroceryIds = setOf("ing_egg"),
      grocerySortMode = GrocerySortMode.BY_RECIPE,
    )
    assertEquals(aisleDiff.groceryItems.size, byRecipeDiff.groceryItems.size)
  }

  /**
   * Verifies [ConsumerHouseholdTemplate] onboarding presets and confirms every
   * [com.familykitchen.board.ports.KitchenStoragePort] UI helper executes in **exactly `1`**
   * atomic batch write (`batchWriteCallCount == 1`), including expiry-rescue score increments.
   */
  @Test
  fun consumerTemplatesAndStorageHelpers_commitEveryMutationInSingleO1BatchWrite() = runTest {
    // Verify all 5 consumer household templates initialize accurate baseline servings and stages.
    assertEquals(4, HouseholdProfile.fromConsumerTemplate(ConsumerHouseholdTemplate.FAMILY_TWO_KIDS).effectiveTargetServings)
    assertEquals(3, HouseholdProfile.fromConsumerTemplate(ConsumerHouseholdTemplate.PARENTS_AND_BABY).effectiveTargetServings)
    assertEquals(ChildGrowthStage.BABY_6_12M, HouseholdProfile.fromConsumerTemplate(ConsumerHouseholdTemplate.PARENTS_AND_BABY).youngestStage)
    assertEquals(2, HouseholdProfile.fromConsumerTemplate(ConsumerHouseholdTemplate.ACTIVE_COUPLE).effectiveTargetServings)
    assertEquals(2, HouseholdProfile.fromConsumerTemplate(ConsumerHouseholdTemplate.SOLO_COOK).effectiveTargetServings)
    val multiGen = HouseholdProfile.fromConsumerTemplate(
      ConsumerHouseholdTemplate.LARGE_MULTIGEN_FAMILY, "Grandma's Table", "Alex", "Sam",
    )
    assertEquals(6, multiGen.effectiveTargetServings)
    assertEquals("Alex", multiGen.displayLabelForCookOwner(CookOwner.FRED))
    assertEquals("Sam", multiGen.displayLabelForCookOwner(CookOwner.WIFE))

    // Seed FakeKitchenStorage with expiring salmon (1d) so cooking it increments rescuedExpiringMealsCount.
    val expiringSeed = SeedCatalogDataset(
      ingredients = expiryCatalog,
      recipes = listOf(salmonRescueRecipe.copy(allowedSlots = MealSlotType.entries)),
      initialPantry = listOf(
        PantryLedgerEntry("ing_salmon", IngredientTier.TIER_1_QUANTITATIVE, 800.0, expiresInDays = 1),
        PantryLedgerEntry("ing_spinach", IngredientTier.TIER_1_QUANTITATIVE, 300.0, expiresInDays = 2),
        PantryLedgerEntry("ing_butter", IngredientTier.TIER_1_QUANTITATIVE, 250.0, expiresInDays = 14),
        PantryLedgerEntry("ing_rice", IngredientTier.TIER_1_QUANTITATIVE, 1000.0, expiresInDays = 180),
        PantryLedgerEntry("ing_olive_oil", IngredientTier.TIER_2_STAPLE, 500.0, StapleStatus.PLENTY),
      ),
    )
    val storage = FakeKitchenStorage()
    var snapshot = storage.loadOrInitialize(expiringSeed)
    assertEquals(0, storage.batchWriteCallCount)

    // 1. toggleGroceryItemChecked -> 1 O(1) write.
    snapshot = storage.toggleGroceryItemChecked(snapshot, "ing_salmon")
    assertEquals(1, storage.batchWriteCallCount)
    assertTrue("ing_salmon" in snapshot.checkedGroceryItemIds)

    // 2. addCustomGroceryItem -> 1 O(1) write.
    storage.resetWriteCounter()
    snapshot = storage.addCustomGroceryItem(
      snapshot, CustomGroceryItem("cust_parchment", "Parchment Paper", "1 roll", isChecked = true),
    )
    assertEquals(1, storage.batchWriteCallCount)
    assertEquals(1, snapshot.customGroceryItems.size)

    // 3. clearCheckedGroceryItems -> 1 O(1) write sweeping checked catalog & custom items.
    storage.resetWriteCounter()
    snapshot = storage.clearCheckedGroceryItems(snapshot)
    assertEquals(1, storage.batchWriteCallCount)
    assertTrue(snapshot.checkedGroceryItemIds.isEmpty() && snapshot.customGroceryItems.isEmpty())

    // 4. toggleSavedRecipe -> 1 O(1) write synchronizing savedRecipeIds and Recipe.isSavedFavorite.
    storage.resetWriteCounter()
    snapshot = storage.toggleSavedRecipe(snapshot, salmonRescueRecipe.recipeId)
    assertEquals(1, storage.batchWriteCallCount)
    assertTrue(salmonRescueRecipe.recipeId in snapshot.savedRecipeIds)
    assertTrue(requireNotNull(snapshot.recipesById[salmonRescueRecipe.recipeId]).isSavedFavorite)

    // 5. addRecipeKitchenNote -> 1 O(1) write (and 0 writes on blank whitespace no-op).
    storage.resetWriteCounter()
    snapshot = storage.addRecipeKitchenNote(snapshot, salmonRescueRecipe.recipeId, "   ")
    assertEquals(0, storage.batchWriteCallCount)
    snapshot = storage.addRecipeKitchenNote(snapshot, salmonRescueRecipe.recipeId, "Kids loved extra lemon zest!")
    assertEquals(1, storage.batchWriteCallCount)
    assertEquals(listOf("Kids loved extra lemon zest!"), snapshot.recipeNotesByRecipeId[salmonRescueRecipe.recipeId])

    // 6. toggleReaderIngredientChecked & setGrocerySortMode -> 1 O(1) write each.
    storage.resetWriteCounter()
    snapshot = storage.toggleReaderIngredientChecked(snapshot, salmonRescueRecipe.recipeId, "ing_salmon")
    assertEquals(1, storage.batchWriteCallCount)
    assertEquals(setOf("ing_salmon"), snapshot.checkedReaderIngredientsByRecipeId[salmonRescueRecipe.recipeId])
    storage.resetWriteCounter()
    snapshot = storage.setGrocerySortMode(snapshot, GrocerySortMode.URGENCY_FIRST)
    assertEquals(1, storage.batchWriteCallCount)
    assertEquals(GrocerySortMode.URGENCY_FIRST, snapshot.grocerySortMode)

    // 7. applyConsumerHouseholdTemplate -> 1 O(1) write rescaling PLANNED slots to 6 servings.
    storage.resetWriteCounter()
    snapshot = storage.applyConsumerHouseholdTemplate(snapshot, ConsumerHouseholdTemplate.LARGE_MULTIGEN_FAMILY)
    assertEquals(1, storage.batchWriteCallCount)
    assertTrue(snapshot.weeklyBoard.slots.all { it.targetServings == 6 })

    // 8. commitCookedMealBatch rescuing expiring salmon increments rescuedExpiringMealsCount (12 -> 13) in 1 write.
    storage.resetWriteCounter()
    val slot = snapshot.weeklyBoard.slots.first()
    val deduction = PantryLedgerEngine.applyCookedDeduction(
      pantry = snapshot.pantryEntries,
      recipe = salmonRescueRecipe,
      catalog = snapshot.ingredients,
      payload = CookedDeductionPayload(slot.slotId, salmonRescueRecipe.recipeId, targetServings = 4),
    )
    snapshot = storage.commitCookedMealBatch(snapshot, slot.slotId, deduction)
    assertEquals(1, storage.batchWriteCallCount)
    assertEquals(13, snapshot.rescuedExpiringMealsCount)
  }

  /**
   * Verifies `HouseholdCurrency.PHP` (`₱`) formatting, default `"Antipolo & Marikina, PH"` market
   * region, `KitchenStoragePort.setHouseholdCurrencyAndRegion` (`batchWriteCallCount == 1`), and
   * Antipolo–Marikina `LOCAL_MARKET_PRESET_INGREDIENTS` (`ing_malunggay_pechay`, `ing_carabao_mango`,
   * `ing_bangus`, `ing_calamansi`, `ing_gata`).
   */
  @Test
  fun antipoloMarikinaPhpCurrencyAndPresets_formatsAmountsSwitchesRegionAndSubstitutesLocalItems() = runTest {
    val defaultProfile = HouseholdProfile()
    assertEquals(HouseholdCurrency.PHP, defaultProfile.currency)
    assertEquals("Antipolo & Marikina, PH", defaultProfile.marketRegionLabel)
    assertEquals("₱1,120", formatKitchenCurrency(1120.0))
    assertEquals("₱245.50", HouseholdCurrency.PHP.formatAmount(245.5, includeDecimals = true))
    assertEquals("₱3,200", defaultProfile.formatMoney(3200.0))
    assertEquals("$1,120", formatKitchenCurrency(1120.0, HouseholdCurrency.USD))

    // Verify setHouseholdCurrencyAndRegion commits in 1 O(1) write and falls back cleanly on blank label.
    val seed = SeedCatalogDataset(
      ingredients = expiryCatalog,
      recipes = listOf(salmonRescueRecipe.copy(allowedSlots = MealSlotType.entries)),
      initialPantry = listOf(PantryLedgerEntry("ing_salmon", IngredientTier.TIER_1_QUANTITATIVE, 800.0)),
    )
    val storage = FakeKitchenStorage()
    var snapshot = storage.loadOrInitialize(seed)
    assertEquals(0, storage.batchWriteCallCount)

    snapshot = storage.setHouseholdCurrencyAndRegion(snapshot, HouseholdCurrency.USD, "Metro Manila & Global")
    assertEquals(1, storage.batchWriteCallCount)
    assertEquals(HouseholdCurrency.USD, snapshot.householdProfile.currency)
    assertEquals("Metro Manila & Global", snapshot.householdProfile.marketRegionLabel)

    storage.resetWriteCounter()
    snapshot = storage.setHouseholdCurrencyAndRegion(snapshot, HouseholdCurrency.PHP, "   ")
    assertEquals(1, storage.batchWriteCallCount)
    assertEquals(HouseholdCurrency.PHP, snapshot.householdProfile.currency)
    assertEquals("Antipolo & Marikina, PH", snapshot.householdProfile.marketRegionLabel)

    // Verify Antipolo-Marikina local market presets and substituteGroupId enrollment.
    val presetsById = indexCatalogById(LOCAL_MARKET_PRESET_INGREDIENTS)
    assertEquals(26, LOCAL_MARKET_PRESET_INGREDIENTS.size)
    LOCAL_MARKET_PRESET_INGREDIENTS.forEach(SchemaValidator::validateIngredientCatalogItem)

    val malunggay = requireNotNull(presetsById["ing_malunggay_pechay"])
    assertEquals(listOf("LEAFY_GREENS"), malunggay.substituteGroupIds)
    assertEquals(BaseUnit.GRAM, malunggay.baseUnit)

    val carabaoMango = requireNotNull(presetsById["ing_carabao_mango"])
    assertEquals(listOf("FRUIT_TOPPING"), carabaoMango.substituteGroupIds)
    assertEquals(BaseUnit.GRAM, carabaoMango.baseUnit)

    assertEquals(listOf("FISH"), requireNotNull(presetsById["ing_bangus"]).allergens)
    assertEquals(BaseUnit.COUNT, requireNotNull(presetsById["ing_calamansi"]).baseUnit)
    val gata = requireNotNull(presetsById["ing_gata"])
    assertEquals(BaseUnit.MILLILITER, gata.baseUnit)
    assertEquals(StorageLocation.FRIDGE, gata.defaultStorageLocation)

    // Confirm EC-03 pantry-aware tie-breaking picks in-stock malunggay when spinach is unavailable.
    val catalogWithoutSpinach = PantryLedgerEngine.toggleLocalAvailability(
      catalog = LOCAL_MARKET_PRESET_INGREDIENTS,
      ingredientId = "ing_spinach",
      isLocallyAvailable = false,
    )
    val greensEval = AvailabilityFilter.evaluateRecipeAvailability(
      recipe = salmonRescueRecipe.copy(
        bomLines = listOf(BomLine("ing_spinach", 200.0, substituteGroupId = "LEAFY_GREENS")),
      ),
      catalog = catalogWithoutSpinach,
      pantry = listOf(PantryLedgerEntry("ing_malunggay_pechay", IngredientTier.TIER_1_QUANTITATIVE, 300.0)),
    )
    assertTrue(greensEval.isSchedulable)
    assertEquals("ing_malunggay_pechay", greensEval.substitutionsApplied["ing_spinach"])
  }

  /**
   * Verifies that phonetic SpeechRecognizer near-matches (`"nest step"`, `"nekst step"`, `"next up"`)
   * advance [CookModeEngine] cleanly to Step 2 (`currentStepIndex == 1`), and confirms Station 4
   * (`PrepStationType.KID_PORTIONS`) in [MenuPlannerEngine.buildWeeklyMealPrepSessionPlan] excludes
   * `TIER_2_STAPLE` salt and oil items while aggregating real `TIER_1_QUANTITATIVE` toddler portions.
   */
  @Test
  fun phoneticNextStepVoiceAndKidPortionsPrepStation_advancesToStepTwoAndExcludesTier2Staples() {
    // 1. Phonetic "nest step" parses to NextStep and advances CookModeSessionState from Step 1 -> Step 2.
    assertEquals(CookModeVoiceIntent.NextStep, CookModeEngine.parseCookModeVoiceIntent("nest step"))
    assertEquals(CookModeVoiceIntent.NextStep, CookModeEngine.parseCookModeVoiceIntent("nekst step"))
    assertEquals(CookModeVoiceIntent.NextStep, CookModeEngine.parseCookModeVoiceIntent("next up"))

    val started = CookModeEngine.startCookSession(salmonRescueRecipe, catalog = expiryCatalog)
    assertEquals(0, started.currentStepIndex)
    val afterNestStep = CookModeEngine.applyVoiceTranscriptToCookSession(started, "nest step", expiryCatalog)
    assertEquals(1, afterNestStep.currentStepIndex)
    assertEquals("Steam Rice", requireNotNull(afterNestStep.currentStep).effectiveTitle)

    // 2. Station 4 KID_PORTIONS aggregates Tier-1 toddler items while excluding Tier-2 salt & olive oil.
    val seaSalt = IngredientCatalogItem(
      ingredientId = "ing_sea_salt",
      displayName = "Fine Sea Salt",
      category = IngredientCategory.PANTRY_STAPLE,
      tier = IngredientTier.TIER_2_STAPLE,
      baseUnit = BaseUnit.GRAM,
      defaultPackageQuantity = 500.0,
    )
    val milledChia = IngredientCatalogItem(
      ingredientId = "ing_milled_chia",
      displayName = "Milled Chia Toddler Topper",
      category = IngredientCategory.PANTRY_STAPLE,
      tier = IngredientTier.TIER_1_QUANTITATIVE,
      baseUnit = BaseUnit.GRAM,
      defaultPackageQuantity = 400.0,
    )
    val stationCatalog = expiryCatalog + listOf(seaSalt, milledChia)
    val toddlerRecipe = salmonRescueRecipe.copy(
      bomLines = salmonRescueRecipe.bomLines + listOf(
        BomLine("ing_sea_salt", 4.0),
        BomLine("ing_milled_chia", 30.0),
      ),
    )
    val prepBoard = WeeklyBoard(
      boardId = "board_kid_station_check",
      householdId = "household_family_4",
      weekStartDateIso = "2026-10-26",
      slots = listOf(MealSlot("slot_mon_dinner", 0, MealSlotType.DINNER, CookOwner.WIFE, toddlerRecipe.recipeId)),
    )

    val prepPlan = MenuPlannerEngine.buildWeeklyMealPrepSessionPlan(
      board = prepBoard,
      recipes = listOf(toddlerRecipe),
      catalog = stationCatalog,
    )
    val kidStationTask = requireNotNull(prepPlan.tasksByStation[PrepStationType.KID_PORTIONS]?.singleOrNull())
    assertEquals(30.0, requireNotNull(kidStationTask.aggregatedIngredients["ing_milled_chia"]), 0.001)
    assertFalse("ing_olive_oil" in kidStationTask.aggregatedIngredients)
    assertFalse("ing_sea_salt" in kidStationTask.aggregatedIngredients)
    assertFalse(kidStationTask.instruction.contains("Olive Oil"))
    assertFalse(kidStationTask.instruction.contains("Sea Salt"))
  }

  private fun readCanonicalSeedCatalogJson(): String {
    // Support running JUnit from android/app (AGP module dir), android/, or repository root.
    val candidates = listOf(
      Path("src/main/assets/seed_catalog.json"),
      Path("app/src/main/assets/seed_catalog.json"),
      Path("android/app/src/main/assets/seed_catalog.json"),
    )
    val matched = requireNotNull(candidates.firstOrNull { it.isRegularFile() }) {
      "Canonical seed_catalog.json asset not found from current working directory"
    }
    return matched.readText()
  }
}
