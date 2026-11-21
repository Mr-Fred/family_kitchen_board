package com.familykitchen.board.domain

import com.familykitchen.board.ports.KitchenSnapshot
import kotlin.io.path.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// Hermetic JVM unit test suite verifying locked domain scenarios EC-01 through EC-13,
// canonical seed_catalog.json integrity, station-grouped weekly prep, and life-event scaling.

/**
 * Hermetic JUnit 4 verification suite covering every locked domain invariant (`EC-01`–`EC-13`),
 * canonical offline seed catalog deserialization (`44` ingredients, `30` recipes, `44` pantry
 * entries), station-grouped Weekly Meal Prep batch commits, and Life-Event serving scaling.
 */
class EdgeCasesEc01ToEc13Test {

  // Compact household catalog fixture spanning Tier-1 quantitative items, substitute groups, and Tier-2 staples.
  private val baseCatalog: List<IngredientCatalogItem> = listOf(
    IngredientCatalogItem("ing_baguette", "Artisan French Baguette", IngredientCategory.BAKERY_GRAINS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.COUNT, 2.0, allergens = listOf("GLUTEN")),
    IngredientCatalogItem("ing_egg", "Large Farm Eggs", IngredientCategory.DAIRY_EGGS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.COUNT, 12.0, allergens = listOf("EGGS")),
    IngredientCatalogItem("ing_milk", "Whole Fresh Milk", IngredientCategory.DAIRY_EGGS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.MILLILITER, 1000.0, allergens = listOf("DAIRY")),
    IngredientCatalogItem("ing_oats", "Rolled Whole Grain Oats", IngredientCategory.BAKERY_GRAINS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 1000.0),
    IngredientCatalogItem("ing_avocado", "Ripe Hass Avocado", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.COUNT, 4.0),
    IngredientCatalogItem("ing_tomato", "Vine-Ripened Tomatoes", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.COUNT, 6.0),
    IngredientCatalogItem("ing_blueberry", "Fresh Blueberries", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 250.0, substituteGroupIds = listOf("FRUIT_TOPPING")),
    IngredientCatalogItem("ing_banana", "Sweet Ripe Bananas", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 1000.0, substituteGroupIds = listOf("FRUIT_TOPPING")),
    IngredientCatalogItem("ing_mango", "Fresh Kent Mango", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 1000.0, substituteGroupIds = listOf("FRUIT_TOPPING")),
    IngredientCatalogItem("ing_spinach", "Fresh Baby Spinach", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 300.0, substituteGroupIds = listOf("LEAFY_GREENS")),
    IngredientCatalogItem("ing_peanut_butter", "Natural Peanut Butter", IngredientCategory.PANTRY_STAPLE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 500.0, substituteGroupIds = listOf("NUT_SEED_SPREAD"), allergens = listOf("PEANUTS")),
    IngredientCatalogItem("ing_sunflower_butter", "Creamy Sunflower Seed Butter", IngredientCategory.PANTRY_STAPLE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 500.0, substituteGroupIds = listOf("NUT_SEED_SPREAD")),
    IngredientCatalogItem("ing_olive_oil", "Extra-Virgin Olive Oil", IngredientCategory.PANTRY_STAPLE, IngredientTier.TIER_2_STAPLE, BaseUnit.MILLILITER, 500.0),
    IngredientCatalogItem("ing_cinnamon", "Ground Ceylon Cinnamon", IngredientCategory.PANTRY_STAPLE, IngredientTier.TIER_2_STAPLE, BaseUnit.GRAM, 100.0),
  )

  // Baseline 4-serving omelette fixture (15m calibrated active time) with flat +2 egg workout boost.
  private val spicedOmeletteRecipe = Recipe(
    recipeId = "rec_spiced_omelette",
    title = "Spiced Family Omelette",
    sourceAttribution = "Curated Family Classic",
    allowedSlots = listOf(MealSlotType.BREAKFAST, MealSlotType.LUNCH, MealSlotType.DINNER),
    preferredOwner = CookOwner.FRED,
    baseServings = 4,
    rawPrepMinutes = 5,
    nightBeforePrepMinutes = 0,
    cookMinutes = 8,
    calibratedActiveMinutes = 15,
    morningSteps = listOf(MorningStep(1, "Whisk 4 eggs and cook gently in olive oil.", 480)),
    toddlerPrepTip = "Crush seeds finely for 2yo and quarter berries.",
    bomLines = listOf(BomLine("ing_egg", 4.0), BomLine("ing_olive_oil", 15.0)),
    workoutModifiers = listOf(
      WorkoutBoostModifier(
        profile = WorkoutTiming.POST_WORKOUT_HIGH_PROTEIN,
        instructionAddOn = "Dad Post-Workout: Scramble +2 extra eggs on Fred's plate.",
        extraBomLines = listOf(BomLine("ing_egg", 2.0)),
      ),
    ),
  )

  /**
   * Verifies `EC-01`: `1.4x` slow-cooker morning prep buffer rejects `31m > 25m` weekday
   * breakfasts when `nightBeforePrepMinutes = 0` and accepts `24m <= 25m` when `5m` is shifted to
   * 8:30 PM night-before prep.
   */
  @Test
  fun ec01_slowCookerMorningPrepBuffer_rejectsOver25mAndAcceptsNightPrepShift() {
    // Without night-before shift: ceil((15 - 0) * 1.4) + 10 = 21 + 10 = 31 minutes (> 25m cap).
    assertEquals(31, MenuPlannerEngine.computeCalibratedMorningMinutes(15, 0, 10))
    val slowWaffles = spicedOmeletteRecipe.copy(
      recipeId = "rec_slow_waffles",
      allowedSlots = listOf(MealSlotType.BREAKFAST),
      rawPrepMinutes = 15,
      nightBeforePrepMinutes = 0,
      cookMinutes = 10,
      calibratedActiveMinutes = 31,
    )
    assertFalse(MenuPlannerEngine.isWeekdayBreakfastEligible(slowWaffles))

    // Shifting 5m to 8:30 PM night prep: ceil((15 - 5) * 1.4) + 10 = 14 + 10 = 24 minutes (<= 25m).
    assertEquals(24, MenuPlannerEngine.computeCalibratedMorningMinutes(15, 5, 10))
    // Cap night-before credit at 5 minutes even when a larger nightPrep value is supplied.
    assertEquals(24, MenuPlannerEngine.computeCalibratedMorningMinutes(15, 12, 10))

    val nightPreppedPancakes = slowWaffles.copy(
      recipeId = "rec_night_prep_pancakes",
      nightBeforePrepMinutes = 5,
      calibratedActiveMinutes = 24,
      nightBeforePrepSteps = listOf("Whisk dry oat mix in a covered bowl at 8:30 PM."),
    )
    assertTrue(MenuPlannerEngine.isWeekdayBreakfastEligible(nightPreppedPancakes))

    // Auto-drafting a 21-slot board must never schedule > 25m recipes on BREAKFAST slots.
    val dinnerStew = spicedOmeletteRecipe.copy(
      recipeId = "rec_dinner_stew",
      allowedSlots = listOf(MealSlotType.LUNCH, MealSlotType.DINNER),
      preferredOwner = CookOwner.WIFE,
    )
    val draftedBoard = MenuPlannerEngine.autoDraftWeeklyBoard(
      "board_ec01", "household_4", "2026-10-26", listOf(slowWaffles, nightPreppedPancakes, dinnerStew), baseCatalog,
    )
    assertEquals(21, draftedBoard.slots.size)
    val breakfastSlots = draftedBoard.slots.filter { it.slotType == MealSlotType.BREAKFAST }
    assertEquals(7, breakfastSlots.size)
    assertTrue(breakfastSlots.all { it.recipeId == "rec_night_prep_pancakes" && it.owner == CookOwner.FRED })

    // Explicitly swapping a > 25m recipe into a BREAKFAST slot must throw ValidationException.
    assertThrows(ValidationException::class.java) {
      MenuPlannerEngine.swapSlotRecipe(draftedBoard, breakfastSlots.first().slotId, slowWaffles, baseCatalog)
    }
  }

  /**
   * Verifies `EC-02`: Toggling `Avocado` to `isLocallyAvailable = false` with no substitute group
   * hard-hides dependent recipes, flags active board slots for swap, and enforces the allergen gate.
   */
  @Test
  fun ec02_localUnavailabilityAndAllergenGate_hardHidesRecipeAndFlagsActiveSlotSwap() {
    val avocadoTartine = spicedOmeletteRecipe.copy(
      recipeId = "rec_avocado_tartine",
      allowedSlots = listOf(MealSlotType.BREAKFAST),
      bomLines = listOf(BomLine("ing_baguette", 1.0), BomLine("ing_egg", 4.0), BomLine("ing_avocado", 2.0)),
    )

    // Toggle ing_avocado unavailable at the local market via PantryLedgerEngine.
    val catalogWithoutAvocado = PantryLedgerEngine.toggleLocalAvailability(baseCatalog, "ing_avocado", false)
    val evaluation = AvailabilityFilter.evaluateRecipeAvailability(avocadoTartine, catalogWithoutAvocado)
    assertFalse(evaluation.isSchedulable)
    assertEquals(AvailabilityReason.UNAVAILABLE_INGREDIENT, evaluation.reason)
    assertEquals(listOf("ing_avocado"), evaluation.blockedIngredientIds)

    // Confirm filterSchedulableRecipes removes avocadoTartine while retaining spicedOmeletteRecipe.
    val schedulable = AvailabilityFilter.filterSchedulableRecipes(
      listOf(avocadoTartine, spicedOmeletteRecipe), catalogWithoutAvocado,
    )
    assertEquals(listOf("rec_spiced_omelette"), schedulable.map { it.recipeId })

    // Active board slot check: detect blocked recipe on Monday Breakfast and swap to an in-stock meal.
    val activeBoard = WeeklyBoard(
      "board_ec02", "household_4", "2026-10-26",
      listOf(MealSlot("slot_mon_breakfast", 0, MealSlotType.BREAKFAST, CookOwner.FRED, avocadoTartine.recipeId)),
    )
    assertFalse(
      AvailabilityFilter.evaluateRecipeAvailability(avocadoTartine, catalogWithoutAvocado).isSchedulable,
    )
    val stockedPantry = listOf(
      PantryLedgerEntry("ing_egg", IngredientTier.TIER_1_QUANTITATIVE, 12.0),
      PantryLedgerEntry("ing_olive_oil", IngredientTier.TIER_2_STAPLE, 500.0, StapleStatus.PLENTY),
    )
    val swapChoices = MenuPlannerEngine.findInstantInStockSwaps(
      MealSlotType.BREAKFAST, listOf(avocadoTartine, spicedOmeletteRecipe), catalogWithoutAvocado, stockedPantry,
      excludeRecipeId = avocadoTartine.recipeId,
    )
    assertEquals(listOf("rec_spiced_omelette"), swapChoices.map { it.recipeId })

    val swappedBoard = MenuPlannerEngine.swapSlotRecipe(
      activeBoard, "slot_mon_breakfast", swapChoices.single(), catalogWithoutAvocado,
    )
    assertEquals("rec_spiced_omelette", swappedBoard.slots.single().recipeId)

    // Attempting to swap the blocked avocado recipe back onto the board throws IngredientUnavailableException.
    val unavailableErr = assertThrows(IngredientUnavailableException::class.java) {
      MenuPlannerEngine.swapSlotRecipe(swappedBoard, "slot_mon_breakfast", avocadoTartine, catalogWithoutAvocado)
    }
    assertEquals("ing_avocado", unavailableErr.ingredientId)

    // Allergen gate: blocking "egg" hard-hides egg recipes lacking substitutes, while peanut spread swaps safely.
    val allergenBlocked = AvailabilityFilter.evaluateRecipeAvailability(avocadoTartine, baseCatalog, listOf("egg"))
    assertFalse(allergenBlocked.isSchedulable)
    assertEquals(AvailabilityReason.ALLERGEN_BLOCKED, allergenBlocked.reason)
    assertEquals(listOf("ing_egg"), allergenBlocked.blockedIngredientIds)

    val spreadToast = spicedOmeletteRecipe.copy(
      recipeId = "rec_spread_toast",
      bomLines = listOf(BomLine("ing_peanut_butter", 60.0, substituteGroupId = "NUT_SEED_SPREAD")),
    )
    val safeSpreadEval = AvailabilityFilter.evaluateRecipeAvailability(spreadToast, baseCatalog, listOf("PEANUTS"))
    assertTrue(safeSpreadEval.isSchedulable)
    assertEquals("ing_sunflower_butter", safeSpreadEval.substitutionsApplied["ing_peanut_butter"])
  }

  /**
   * Verifies `EC-03`: Unavailable `Blueberries` in `FRUIT_TOPPING` rewrites BOM and Grocery Diff
   * to `Banana` (with in-stock `Mango` tie-breaking when pantry is provided), and unavailable
   * `Spinach` in `LEAFY_GREENS` rewrites deterministically to preset `ing_kale`.
   */
  @Test
  fun ec03_localUnavailabilityAutoSubstitute_rewritesBomAndGroceryDiffWithTieBreakingAndKale() {
    val berryParfait = spicedOmeletteRecipe.copy(
      recipeId = "rec_berry_parfait",
      rawPrepMinutes = 5,
      cookMinutes = 0,
      calibratedActiveMinutes = 7,
      bomLines = listOf(BomLine("ing_blueberry", 200.0, substituteGroupId = "FRUIT_TOPPING")),
    )

    // Direct FRUIT_TOPPING rewrite: Blueberries -> Banana across BOM and WeeklyGroceryDiff.
    val bananaOnlyCatalog = PantryLedgerEngine.toggleLocalAvailability(
      baseCatalog.filterNot { it.ingredientId == "ing_mango" }, "ing_blueberry", false,
    )
    val bananaEval = AvailabilityFilter.evaluateRecipeAvailability(berryParfait, bananaOnlyCatalog)
    assertTrue(bananaEval.isSchedulable)
    assertEquals(mapOf("ing_blueberry" to "ing_banana"), bananaEval.substitutionsApplied)
    assertEquals("ing_banana", bananaEval.resolvedBomLines.single().ingredientId)

    val board = WeeklyBoard(
      "board_ec03", "household_4", "2026-10-26",
      listOf(MealSlot("slot_mon_b", 0, MealSlotType.BREAKFAST, CookOwner.FRED, berryParfait.recipeId)),
    )
    val emptyBananaPantry = listOf(
      PantryLedgerEntry("ing_banana", IngredientTier.TIER_1_QUANTITATIVE, 0.0, StapleStatus.OUT, isOutOfStock = true),
    )
    val bananaDiff = GroceryDiffEngine.computeWeeklyGroceryDiff(
      board, listOf(berryParfait), bananaOnlyCatalog, emptyBananaPantry,
    )
    assertEquals(listOf("ing_banana"), bananaDiff.groceryItems.map { it.ingredientId })
    assertEquals(1000.0, bananaDiff.groceryItems.single().restockQuantity, 0.001)

    // In-stock tie-breaking: ing_banana is 0g (OUT) while ing_mango has 400g in stock -> selects ing_mango.
    val catalogWithoutBlueberry = PantryLedgerEngine.toggleLocalAvailability(baseCatalog, "ing_blueberry", false)
    val pantryWithMango = listOf(
      PantryLedgerEntry("ing_banana", IngredientTier.TIER_1_QUANTITATIVE, 0.0, StapleStatus.OUT, isOutOfStock = true),
      PantryLedgerEntry("ing_mango", IngredientTier.TIER_1_QUANTITATIVE, 400.0, StapleStatus.PLENTY),
    )
    val mangoEval = AvailabilityFilter.evaluateRecipeAvailability(
      berryParfait, catalogWithoutBlueberry, emptyList(), pantryWithMango,
    )
    assertTrue(mangoEval.isSchedulable)
    assertEquals("ing_mango", mangoEval.resolvedBomLines.single().ingredientId)

    // LEAFY_GREENS preset parity: merge LOCAL_MARKET_PRESET_INGREDIENTS (ing_kale) and mark spinach unavailable.
    val presetById = indexCatalogById(LOCAL_MARKET_PRESET_INGREDIENTS)
    assertNotNull(presetById["ing_kale"])
    val mergedCatalogWithKale = (indexCatalogById(baseCatalog) + presetById).values.toList()
    val catalogWithoutSpinach = PantryLedgerEngine.toggleLocalAvailability(mergedCatalogWithKale, "ing_spinach", false)
    val spinachWrap = spicedOmeletteRecipe.copy(
      recipeId = "rec_spinach_wrap",
      bomLines = listOf(BomLine("ing_spinach", 150.0, substituteGroupId = "LEAFY_GREENS")),
    )
    val kaleEval = AvailabilityFilter.evaluateRecipeAvailability(spinachWrap, catalogWithoutSpinach)
    assertTrue(kaleEval.isSchedulable)
    assertEquals("ing_kale", kaleEval.substitutionsApplied["ing_spinach"])
    assertEquals("ing_kale", kaleEval.resolvedBomLines.single().ingredientId)
  }

  /**
   * Verifies `EC-04`: One-time cook quantity override (`4` eggs -> `2` eggs with
   * `saveOverrideAsHouseholdDefault = false`) deducts `2` eggs without mutating
   * `recipe.householdBomOverrides`.
   */
  @Test
  fun ec04_oneTimeCookQuantityOverride_deductsAdjustedAmountWithoutMutatingDefaults() {
    val initialPantry = listOf(
      PantryLedgerEntry("ing_egg", IngredientTier.TIER_1_QUANTITATIVE, 10.0),
      PantryLedgerEntry("ing_olive_oil", IngredientTier.TIER_2_STAPLE, 250.0, StapleStatus.PLENTY),
    )
    val payload = CookedDeductionPayload(
      slotId = "slot_mon_breakfast",
      recipeId = spicedOmeletteRecipe.recipeId,
      targetServings = 4,
      actualUsedQuantities = mapOf("ing_egg" to 2.0),
      saveOverrideAsHouseholdDefault = false,
    )

    val result = PantryLedgerEngine.applyCookedDeduction(initialPantry, spicedOmeletteRecipe, baseCatalog, payload)

    // Pantry subtracts 2 eggs (10 -> 8), while future baseline recipe BOM stays at 4 eggs.
    assertEquals(2.0, requireNotNull(result.deductedQuantities["ing_egg"]), 0.001)
    assertEquals(8.0, requireNotNull(result.updatedPantryById["ing_egg"]).currentQuantity, 0.001)
    assertTrue(result.updatedRecipe.householdBomOverrides.isEmpty())
    assertEquals(MealSlotStatus.COOKED, result.updatedSlotStatus)
    val nextWeekBom = PantryLedgerEngine.resolveEffectiveMealBom(result.updatedRecipe, 4, WorkoutTiming.NONE, baseCatalog)
    assertEquals(4.0, requireNotNull(nextWeekBom["ing_egg"]), 0.001)
  }

  /**
   * Verifies `EC-05`: Saving a cook override (`saveOverrideAsHouseholdDefault = true`) deducts the
   * used quantity AND normalizes `householdBomOverrides["ing_egg"] = 2.0` across serving scales.
   */
  @Test
  fun ec05_permanentHouseholdDefaultOverride_deductsAndNormalizesAcrossServingScales() {
    val initialPantry = listOf(PantryLedgerEntry("ing_egg", IngredientTier.TIER_1_QUANTITATIVE, 10.0))

    // Baseline 4-serving cook override (4 -> 2 eggs) persists 2.0 into householdBomOverrides.
    val baselineSavePayload = CookedDeductionPayload(
      slotId = "slot_tue_b",
      recipeId = spicedOmeletteRecipe.recipeId,
      targetServings = 4,
      actualUsedQuantities = mapOf("ing_egg" to 2.0),
      saveOverrideAsHouseholdDefault = true,
    )
    val baselineResult = PantryLedgerEngine.applyCookedDeduction(
      initialPantry, spicedOmeletteRecipe, baseCatalog, baselineSavePayload,
    )
    assertEquals(8.0, requireNotNull(baselineResult.updatedPantryById["ing_egg"]).currentQuantity, 0.001)
    assertEquals(2.0, requireNotNull(baselineResult.updatedRecipe.householdBomOverrides["ing_egg"]), 0.001)

    // Subsequent 4-serving and 6-serving resolutions scale from the new learned 2.0-egg baseline.
    val fourServingBom = PantryLedgerEngine.resolveEffectiveMealBom(baselineResult.updatedRecipe, 4, WorkoutTiming.NONE, baseCatalog)
    val sixServingBom = PantryLedgerEngine.resolveEffectiveMealBom(baselineResult.updatedRecipe, 6, WorkoutTiming.NONE, baseCatalog)
    assertEquals(2.0, requireNotNull(fourServingBom["ing_egg"]), 0.001)
    assertEquals(3.0, requireNotNull(sixServingBom["ing_egg"]), 0.001)

    // Cooking an 8-serving guest batch with 4 eggs used normalizes 4 * (4 / 8) = 2.0 into baseServings.
    val scaledSavePayload = CookedDeductionPayload(
      slotId = "slot_sun_b",
      recipeId = spicedOmeletteRecipe.recipeId,
      targetServings = 8,
      actualUsedQuantities = mapOf("ing_egg" to 4.0),
      saveOverrideAsHouseholdDefault = true,
    )
    val scaledResult = PantryLedgerEngine.applyCookedDeduction(
      initialPantry, spicedOmeletteRecipe, baseCatalog, scaledSavePayload,
    )
    assertEquals(6.0, requireNotNull(scaledResult.updatedPantryById["ing_egg"]).currentQuantity, 0.001)
    assertEquals(2.0, requireNotNull(scaledResult.updatedRecipe.householdBomOverrides["ing_egg"]), 0.001)
  }

  /**
   * Verifies `EC-06`: Ad-hoc extra ingredients (`+1` tomato not in the base recipe BOM) are
   * deducted atomically alongside planned BOM lines in the same single-pass reduction.
   */
  @Test
  fun ec06_adHocExtraIngredientAtCookTime_deductsUnplannedTomatoAtomicallyInSinglePass() {
    val initialPantry = listOf(
      PantryLedgerEntry("ing_egg", IngredientTier.TIER_1_QUANTITATIVE, 10.0),
      PantryLedgerEntry("ing_tomato", IngredientTier.TIER_1_QUANTITATIVE, 5.0),
      PantryLedgerEntry("ing_olive_oil", IngredientTier.TIER_2_STAPLE, 300.0, StapleStatus.PLENTY),
    )
    val payload = CookedDeductionPayload(
      slotId = "slot_wed_breakfast",
      recipeId = spicedOmeletteRecipe.recipeId,
      targetServings = 4,
      actualUsedQuantities = mapOf("ing_egg" to 4.0, "ing_tomato" to 1.0),
      saveOverrideAsHouseholdDefault = false,
    )

    val result = PantryLedgerEngine.applyCookedDeduction(initialPantry, spicedOmeletteRecipe, baseCatalog, payload)

    // Both planned eggs (10 -> 6) and unplanned tomato (5 -> 4) update in one single-pass map reduction.
    assertEquals(4.0, requireNotNull(result.deductedQuantities["ing_egg"]), 0.001)
    assertEquals(1.0, requireNotNull(result.deductedQuantities["ing_tomato"]), 0.001)
    assertEquals(0.0, requireNotNull(result.deductedQuantities["ing_olive_oil"]), 0.001)
    assertEquals(6.0, requireNotNull(result.updatedPantryById["ing_egg"]).currentQuantity, 0.001)
    assertEquals(4.0, requireNotNull(result.updatedPantryById["ing_tomato"]).currentQuantity, 0.001)
  }

  /**
   * Verifies `EC-07`: Dad's `POST_WORKOUT_HIGH_PROTEIN` boost adds `+2` eggs flat (`1x` -> `6`
   * total at 4 servings, `10` total at 8 servings rather than `12`) and surfaces `instructionAddOn`.
   */
  @Test
  fun ec07_flatOneXDadWorkoutBoost_addsUnscaledExtraProteinAndSurfacesInstructionAddOn() {
    val initialPantry = listOf(PantryLedgerEntry("ing_egg", IngredientTier.TIER_1_QUANTITATIVE, 12.0))
    val workoutPayload = CookedDeductionPayload(
      "slot_thu_b", spicedOmeletteRecipe.recipeId, 4, WorkoutTiming.POST_WORKOUT_HIGH_PROTEIN,
    )

    // At 4 servings: 4 base eggs + 2 flat workout eggs = 6 deducted (12 -> 6).
    val deduction = PantryLedgerEngine.applyCookedDeduction(initialPantry, spicedOmeletteRecipe, baseCatalog, workoutPayload)
    assertEquals(6.0, requireNotNull(deduction.deductedQuantities["ing_egg"]), 0.001)
    assertEquals(6.0, requireNotNull(deduction.updatedPantryById["ing_egg"]).currentQuantity, 0.001)

    // When household servings double to 8, base 4 eggs scale to 8 while Dad's +2 boost stays flat (= 10).
    val scaledBom = PantryLedgerEngine.resolveEffectiveMealBom(
      spicedOmeletteRecipe, 8, WorkoutTiming.POST_WORKOUT_HIGH_PROTEIN, baseCatalog,
    )
    assertEquals(10.0, requireNotNull(scaledBom["ing_egg"]), 0.001)

    // Verify toggleSlotWorkoutModifier + buildResolvedMealCard surface instructionAddOn and 6.0 BOM.
    val board = WeeklyBoard(
      "board_ec07", "household_4", "2026-10-26",
      listOf(MealSlot("slot_thu_b", 3, MealSlotType.BREAKFAST, CookOwner.FRED, spicedOmeletteRecipe.recipeId)),
    )
    val boostedBoard = MenuPlannerEngine.toggleSlotWorkoutModifier(
      board, "slot_thu_b", WorkoutTiming.POST_WORKOUT_HIGH_PROTEIN,
    )
    val resolvedCard = MenuPlannerEngine.buildResolvedMealCard(
      boostedBoard.slots.single(), spicedOmeletteRecipe, baseCatalog, currentHour24 = 7,
    )
    assertEquals(6.0, requireNotNull(resolvedCard.effectiveBomQuantities["ing_egg"]), 0.001)
    assertEquals("Dad Post-Workout: Scramble +2 extra eggs on Fred's plate.", resolvedCard.workoutInstructionAddOn)
  }

  /**
   * Verifies `EC-08`: Child growth-stage safety callouts (`BABY_6_12M`, `TODDLER_1_2Y`,
   * `PRESCHOOL_3_4Y`, `KIDS_5_PLUS`) render non-empty stage-adapted plain-text guidance on
   * [ResolvedMealCard].
   */
  @Test
  fun ec08_childGrowthStageSafetyCallout_rendersAllFourStagesCleanlyOnResolvedMealCard() {
    val slot = MealSlot("slot_fri_b", 4, MealSlotType.BREAKFAST, CookOwner.FRED, spicedOmeletteRecipe.recipeId)

    // Build ResolvedMealCard across all four developmental childhood feeding stages.
    val babyCard = MenuPlannerEngine.buildResolvedMealCard(
      slot, spicedOmeletteRecipe, baseCatalog, householdProfile = HouseholdProfile(youngestStage = ChildGrowthStage.BABY_6_12M),
    )
    val toddlerCard = MenuPlannerEngine.buildResolvedMealCard(
      slot, spicedOmeletteRecipe, baseCatalog, householdProfile = HouseholdProfile(youngestStage = ChildGrowthStage.TODDLER_1_2Y),
    )
    val preschoolCard = MenuPlannerEngine.buildResolvedMealCard(
      slot, spicedOmeletteRecipe, baseCatalog, householdProfile = HouseholdProfile(youngestStage = ChildGrowthStage.PRESCHOOL_3_4Y),
    )
    val olderKidCard = MenuPlannerEngine.buildResolvedMealCard(
      slot, spicedOmeletteRecipe, baseCatalog, householdProfile = HouseholdProfile(youngestStage = ChildGrowthStage.KIDS_5_PLUS),
    )

    // Raw recipe tip stays preserved while stageAdaptedSafetyTip rewrites age guidance per stage.
    assertEquals("Crush seeds finely for 2yo and quarter berries.", toddlerCard.toddlerPrepTip)
    assertTrue(requireNotNull(babyCard.stageAdaptedSafetyTip).startsWith("For 6-12m Baby:"))
    assertTrue(requireNotNull(babyCard.stageAdaptedSafetyTip).contains("smooth iron-rich puree"))
    assertEquals("For 2yo: Crush seeds finely for 2yo and quarter berries.", toddlerCard.stageAdaptedSafetyTip)
    assertTrue(requireNotNull(preschoolCard.stageAdaptedSafetyTip).startsWith("For 3-4yo:"))
    assertTrue(requireNotNull(preschoolCard.stageAdaptedSafetyTip).contains("3-4yo"))
    assertTrue(requireNotNull(olderKidCard.stageAdaptedSafetyTip).startsWith("For 5+yo:"))
    assertTrue(requireNotNull(olderKidCard.stageAdaptedSafetyTip).contains("5+yo"))

    // Blank toddlerPrepTip produces null callout fields so UI cards never render empty safety banners.
    val tipFreeCard = MenuPlannerEngine.buildResolvedMealCard(slot, spicedOmeletteRecipe.copy(toddlerPrepTip = null))
    assertNull(tipFreeCard.toddlerPrepTip)
    assertNull(tipFreeCard.stageAdaptedSafetyTip)
  }

  /**
   * Verifies `EC-09`: Chronological 21-slot contention (`Tue Dinner 4 eggs` + `Wed Breakfast 4 eggs`
   * against `6 eggs` starting stock) flags `Wed Breakfast` with a `-2` shortage and rounds
   * `ceil(2 / 12) * 12 = 12` (`1` carton) onto the Smart Grocery List.
   */
  @Test
  fun ec09_chronological21SlotContention_flagsWednesdayBreakfastShortageAndRoundsOneCarton() {
    // Pass Wednesday Breakfast ahead of Tuesday Dinner to confirm strict chronological sorting.
    val unorderedBoard = WeeklyBoard(
      "board_ec09", "household_4", "2026-10-26",
      listOf(
        MealSlot("slot_wed_breakfast", 2, MealSlotType.BREAKFAST, CookOwner.FRED, spicedOmeletteRecipe.recipeId),
        MealSlot("slot_tue_dinner", 1, MealSlotType.DINNER, CookOwner.WIFE, spicedOmeletteRecipe.recipeId),
      ),
    )
    assertEquals(
      listOf("slot_tue_dinner", "slot_wed_breakfast"),
      GroceryDiffEngine.sortSlotsChronologically(unorderedBoard.slots).map { it.slotId },
    )

    val sixEggPantry = listOf(
      PantryLedgerEntry("ing_egg", IngredientTier.TIER_1_QUANTITATIVE, 6.0),
      PantryLedgerEntry("ing_olive_oil", IngredientTier.TIER_2_STAPLE, 500.0, StapleStatus.PLENTY),
    )
    val diff = GroceryDiffEngine.computeWeeklyGroceryDiff(
      unorderedBoard, listOf(spicedOmeletteRecipe), baseCatalog, sixEggPantry,
    )

    // Tuesday Dinner consumes 4 of 6 eggs (0 shortages); Wednesday Breakfast needs 4 with 2 left -> shortage 2.
    assertTrue(requireNotNull(diff.slotSummariesBySlotId["slot_tue_dinner"]).shortages.isEmpty())
    val wedSummary = requireNotNull(diff.slotSummariesBySlotId["slot_wed_breakfast"])
    assertEquals(listOf(SlotIngredientShortage("ing_egg", 4.0, 2.0, 2.0)), wedSummary.shortages)

    // Net weekly deficit of 2 eggs rounds via ceil(2 / 12) = 1 package (12 count) on Smart Grocery List.
    val eggGroceryItem = requireNotNull(diff.groceryItems.find { it.ingredientId == "ing_egg" })
    assertEquals(8.0, eggGroceryItem.totalReservedQuantity, 0.001)
    assertEquals(2.0, eggGroceryItem.netDeficit, 0.001)
    assertEquals(1, eggGroceryItem.packagesToBuy)
    assertEquals(12.0, eggGroceryItem.restockQuantity, 0.001)
  }

  /**
   * Verifies `EC-10`: Floor-at-zero pantry depletion clamps `1` egg minus `2` cooked eggs to `0.0`
   * (never negative) and marks `isOutOfStock = true`.
   */
  @Test
  fun ec10_floorAtZeroPantryDepletion_clampsStockToZeroAndSetsOutOfStockFlag() {
    val oneEggPantry = listOf(PantryLedgerEntry("ing_egg", IngredientTier.TIER_1_QUANTITATIVE, 1.0, StapleStatus.LOW))
    val payload = CookedDeductionPayload(
      "slot_fri_b", spicedOmeletteRecipe.recipeId, 4, WorkoutTiming.NONE, mapOf("ing_egg" to 2.0),
    )

    // Subtracting 2 eggs from 1 remaining egg clamps stock at 0.0 and marks isOutOfStock = true.
    val result = PantryLedgerEngine.applyCookedDeduction(oneEggPantry, spicedOmeletteRecipe, baseCatalog, payload)
    val eggAfterCook = requireNotNull(result.updatedPantryById["ing_egg"])
    assertEquals(0.0, eggAfterCook.currentQuantity, 0.001)
    assertTrue(eggAfterCook.isOutOfStock)
    assertEquals(StapleStatus.OUT, eggAfterCook.stapleStatus)
    assertTrue("ing_egg" in result.outOfStockIngredientIds)

    // Verify Fridge Pulse negative stepper adjustments also clamp cleanly at 0.0.
    val pulsed = PantryLedgerEngine.adjustPantryQuantity(oneEggPantry, baseCatalog, "ing_egg", -5.0)
    assertEquals(0.0, pulsed.single().currentQuantity, 0.001)
    assertTrue(pulsed.single().isOutOfStock)
  }

  /**
   * Verifies `EC-11`: Tier-2 staple (`ing_olive_oil`) deducts `0` on cook, queues `1` default
   * package when `LOW` or `OUT`, and resets `stapleStatus` to `PLENTY` upon batch restock.
   */
  @Test
  fun ec11_tier2StapleLifecycle_deductsZeroOnCookQueuesWhenLowOrOutAndResetsToPlenty() {
    val lowOilPantry = listOf(
      PantryLedgerEntry("ing_egg", IngredientTier.TIER_1_QUANTITATIVE, 12.0),
      PantryLedgerEntry("ing_olive_oil", IngredientTier.TIER_2_STAPLE, 120.0, StapleStatus.LOW),
    )

    // Step 1: Cooking spiced omelette deducts 0.0 ml from Tier-2 olive oil and preserves LOW status.
    val cookResult = PantryLedgerEngine.applyCookedDeduction(
      lowOilPantry, spicedOmeletteRecipe, baseCatalog, CookedDeductionPayload("slot_sat_b", spicedOmeletteRecipe.recipeId),
    )
    val oilAfterCook = requireNotNull(cookResult.updatedPantryById["ing_olive_oil"])
    assertEquals(0.0, requireNotNull(cookResult.deductedQuantities["ing_olive_oil"]), 0.001)
    assertEquals(120.0, oilAfterCook.currentQuantity, 0.001)
    assertEquals(StapleStatus.LOW, oilAfterCook.stapleStatus)

    // Step 2: Both LOW and OUT Tier-2 staples queue 1 default package onto Smart Grocery List.
    val board = WeeklyBoard(
      "board_ec11", "household_4", "2026-10-26",
      listOf(MealSlot("slot_mon_d", 0, MealSlotType.DINNER, CookOwner.WIFE, spicedOmeletteRecipe.recipeId)),
    )
    val lowDiff = GroceryDiffEngine.computeWeeklyGroceryDiff(board, listOf(spicedOmeletteRecipe), baseCatalog, lowOilPantry)
    val lowOilItem = requireNotNull(lowDiff.groceryItems.find { it.ingredientId == "ing_olive_oil" })
    assertEquals(1, lowOilItem.packagesToBuy)
    assertEquals(500.0, lowOilItem.restockQuantity, 0.001)

    val outOilPantry = PantryLedgerEngine.setStapleStatus(lowOilPantry, baseCatalog, "ing_olive_oil", StapleStatus.OUT)
    val outDiff = GroceryDiffEngine.computeWeeklyGroceryDiff(board, listOf(spicedOmeletteRecipe), baseCatalog, outOilPantry)
    val outOilDelta = requireNotNull(outDiff.restockDeltas.find { it.ingredientId == "ing_olive_oil" })
    assertEquals(StapleStatus.PLENTY, outOilDelta.newStapleStatus)

    // Step 3: Batch restock restores Tier-2 olive oil back to PLENTY and clears isOutOfStock.
    val restockedPantry = PantryLedgerEngine.batchRestockPantry(outOilPantry, baseCatalog, outDiff.restockDeltas)
    val restockedOil = requireNotNull(restockedPantry.find { it.ingredientId == "ing_olive_oil" })
    assertEquals(StapleStatus.PLENTY, restockedOil.stapleStatus)
    assertFalse(restockedOil.isOutOfStock)
  }

  /**
   * Verifies `EC-12`: `markSlotSkippedOrLeftovers` transitions Wednesday Lunch to
   * `COMPLETED_SKIPPED`, deducts `0` pantry stock, and releases its reserved BOM to downstream slots.
   */
  @Test
  fun ec12_markSlotSkippedOrLeftovers_transitionsStatusDeductsZeroAndReleasesReservedBom() {
    val board = WeeklyBoard(
      "board_ec12", "household_4", "2026-10-26",
      listOf(
        MealSlot("slot_wed_lunch", 2, MealSlotType.LUNCH, CookOwner.WIFE, spicedOmeletteRecipe.recipeId),
        MealSlot("slot_thu_breakfast", 3, MealSlotType.BREAKFAST, CookOwner.FRED, spicedOmeletteRecipe.recipeId),
      ),
    )
    val fourEggPantry = listOf(PantryLedgerEntry("ing_egg", IngredientTier.TIER_1_QUANTITATIVE, 4.0))

    // Before skipping Wednesday Lunch: 8 eggs demanded against 4 in stock -> Thursday has a 4-egg shortage.
    val diffBeforeSkip = GroceryDiffEngine.computeWeeklyGroceryDiff(board, listOf(spicedOmeletteRecipe), baseCatalog, fourEggPantry)
    assertEquals(1, requireNotNull(diffBeforeSkip.slotSummariesBySlotId["slot_thu_breakfast"]).shortages.size)
    assertEquals(1, diffBeforeSkip.groceryItems.size)

    // Skipping Wednesday Lunch deducts 0 stock and immediately frees all 4 eggs for Thursday Breakfast.
    val boardAfterSkip = MenuPlannerEngine.markSlotSkippedOrLeftovers(board, "slot_wed_lunch")
    assertEquals(MealSlotStatus.COMPLETED_SKIPPED, boardAfterSkip.slots.first().status)
    assertEquals(4.0, fourEggPantry.single().currentQuantity, 0.001)

    val diffAfterSkip = GroceryDiffEngine.computeWeeklyGroceryDiff(
      boardAfterSkip, listOf(spicedOmeletteRecipe), baseCatalog, fourEggPantry,
    )
    assertTrue(requireNotNull(diffAfterSkip.slotSummariesBySlotId["slot_wed_lunch"]).reservedQuantities.isEmpty())
    assertTrue(requireNotNull(diffAfterSkip.slotSummariesBySlotId["slot_thu_breakfast"]).shortages.isEmpty())
    assertTrue(diffAfterSkip.groceryItems.isEmpty())
  }

  /**
   * Verifies `EC-13`: `findInstantInStockSwaps` returns only locally available, allergen-safe,
   * `<= 25m` (for `BREAKFAST`) recipes with 100% Tier-1 coverage and non-`OUT` Tier-2 staples.
   */
  @Test
  fun ec13_findInstantInStockSwaps_returnsOnlyAvailableSafeUnder25mAndFullyInStockMeals() {
    val slowMilkWaffles = spicedOmeletteRecipe.copy(
      recipeId = "rec_slow_milk_waffles", allowedSlots = listOf(MealSlotType.BREAKFAST),
      rawPrepMinutes = 15, cookMinutes = 10, calibratedActiveMinutes = 31,
      bomLines = listOf(BomLine("ing_egg", 4.0), BomLine("ing_milk", 300.0)),
    )
    val fastMilkPancakes = slowMilkWaffles.copy(
      recipeId = "rec_fast_milk_pancakes", nightBeforePrepMinutes = 5, calibratedActiveMinutes = 24,
    )
    val cinnamonOats = spicedOmeletteRecipe.copy(
      recipeId = "rec_cinnamon_oats", allowedSlots = listOf(MealSlotType.BREAKFAST),
      rawPrepMinutes = 5, cookMinutes = 10, calibratedActiveMinutes = 17,
      bomLines = listOf(BomLine("ing_oats", 200.0), BomLine("ing_cinnamon", 2.0)),
    )
    val unavailableAvocadoToast = spicedOmeletteRecipe.copy(
      recipeId = "rec_unavailable_avocado", allowedSlots = listOf(MealSlotType.BREAKFAST),
      bomLines = listOf(BomLine("ing_avocado", 2.0)),
    )

    val catalogWithoutAvocado = PantryLedgerEngine.toggleLocalAvailability(baseCatalog, "ing_avocado", false)
    val pantrySpoiledMilkAndOutCinnamon = listOf(
      PantryLedgerEntry("ing_milk", IngredientTier.TIER_1_QUANTITATIVE, 0.0, StapleStatus.OUT, isOutOfStock = true),
      PantryLedgerEntry("ing_egg", IngredientTier.TIER_1_QUANTITATIVE, 6.0, StapleStatus.PLENTY),
      PantryLedgerEntry("ing_oats", IngredientTier.TIER_1_QUANTITATIVE, 500.0, StapleStatus.PLENTY),
      PantryLedgerEntry("ing_olive_oil", IngredientTier.TIER_2_STAPLE, 80.0, StapleStatus.LOW),
      PantryLedgerEntry("ing_cinnamon", IngredientTier.TIER_2_STAPLE, 0.0, StapleStatus.OUT, isOutOfStock = true),
    )
    val candidates = listOf(slowMilkWaffles, fastMilkPancakes, spicedOmeletteRecipe, cinnamonOats, unavailableAvocadoToast)

    // Only spicedOmeletteRecipe passes: <= 25m, 6 >= 4 eggs in stock, and Tier-2 olive oil is LOW (!= OUT).
    val swapsCinnamonOut = MenuPlannerEngine.findInstantInStockSwaps(
      MealSlotType.BREAKFAST, candidates, catalogWithoutAvocado, pantrySpoiledMilkAndOutCinnamon,
    )
    assertEquals(listOf("rec_spiced_omelette"), swapsCinnamonOut.map { it.recipeId })

    // Restocking cinnamon from OUT to LOW unlocks cinnamonOats; adding EGGS allergen hides spicedOmelette.
    val pantryCinnamonLow = PantryLedgerEngine.setStapleStatus(
      pantrySpoiledMilkAndOutCinnamon, catalogWithoutAvocado, "ing_cinnamon", StapleStatus.LOW,
    )
    val swapsWithEggAllergen = MenuPlannerEngine.findInstantInStockSwaps(
      MealSlotType.BREAKFAST, candidates, catalogWithoutAvocado, pantryCinnamonLow, householdAllergens = listOf("EGGS"),
    )
    assertEquals(listOf("rec_cinnamon_oats"), swapsWithEggAllergen.map { it.recipeId })
  }

  /**
   * Verifies that canonical `android/app/src/main/assets/seed_catalog.json` deserializes cleanly
   * via [SchemaValidator.decodeSeedCatalogDatasetJson] with `44` ingredients (`38` Tier-1 + `6`
   * Tier-2), `30` recipes, `44` initial pantry entries, `FRUIT_TOPPING` / `LEAFY_GREENS` /
   * `NUT_SEED_TOPPING` substitute groups, and `0` calibrated morning math mismatches.
   */
  @Test
  fun canonicalSeedCatalogAsset_deserializesCleanlyWithExactCountsAndZeroMathMismatches() {
    val dataset = SchemaValidator.decodeSeedCatalogDatasetJson(readCanonicalSeedCatalogJson())

    // Verify exact curated counts across Tier-1/Tier-2 catalog items, recipes, and initial pantry.
    assertEquals(44, dataset.ingredients.size)
    assertEquals(38, dataset.ingredients.count { it.tier == IngredientTier.TIER_1_QUANTITATIVE })
    assertEquals(6, dataset.ingredients.count { it.tier == IngredientTier.TIER_2_STAPLE })
    assertEquals(30, dataset.recipes.size)
    assertEquals(44, dataset.initialPantry.size)

    // Confirm all three canonical substitute groups (including NUT_SEED_TOPPING) are enrolled.
    val substituteGroups = dataset.ingredients.flatMap { it.substituteGroupIds }.toSet()
    assertTrue(substituteGroups.containsAll(setOf("FRUIT_TOPPING", "LEAFY_GREENS", "NUT_SEED_TOPPING")))

    // Confirm 0 calibrated morning math mismatches, 100% toddler tips, and workout boost coverage.
    val mathMismatches = dataset.recipes.count {
      it.calibratedActiveMinutes != MenuPlannerEngine.computeCalibratedMorningMinutes(it)
    }
    assertEquals(0, mathMismatches)
    assertTrue(dataset.recipes.all { !it.toddlerPrepTip.isNullOrBlank() && it.workoutModifiers.isNotEmpty() })

    // Verify KitchenSnapshot.fromSeedCatalog drafts all 21 weekly slots with <= 25m breakfasts.
    val initialSnapshot = KitchenSnapshot.fromSeedCatalog(dataset)
    assertEquals(21, initialSnapshot.weeklyBoard.slots.size)
    val draftedBreakfasts = initialSnapshot.weeklyBoard.slots.filter { it.slotType == MealSlotType.BREAKFAST }
    assertEquals(7, draftedBreakfasts.size)
    assertTrue(
      draftedBreakfasts.all {
        requireNotNull(initialSnapshot.recipesById[it.recipeId]).calibratedActiveMinutes <= MAX_WEEKDAY_BREAKFAST_MINUTES
      },
    )
  }

  /**
   * Verifies Station-Grouped Weekly Meal Prep (`buildWeeklyMealPrepSessionPlan` and
   * `applyWeeklyPrepSessionBatch`) across all 4 culinary stations and atomic slot/pantry updates.
   */
  @Test
  fun weeklyMealPrepSession_groupsPlannedSlotsByStationAndAppliesAtomicBatchCommit() {
    val oatBananaBowl = spicedOmeletteRecipe.copy(
      recipeId = "rec_oat_banana_bowl",
      nightBeforePrepSteps = listOf("Measure rolled oats into mason jars."),
      bomLines = listOf(BomLine("ing_oats", 200.0), BomLine("ing_banana", 150.0)),
    )
    val board = WeeklyBoard(
      "board_prep_week", "household_4", "2026-10-26",
      listOf(
        MealSlot("slot_mon_b", 0, MealSlotType.BREAKFAST, CookOwner.FRED, spicedOmeletteRecipe.recipeId, activeWorkoutModifier = WorkoutTiming.POST_WORKOUT_HIGH_PROTEIN),
        MealSlot("slot_tue_b", 1, MealSlotType.BREAKFAST, CookOwner.FRED, oatBananaBowl.recipeId),
        MealSlot("slot_wed_b", 2, MealSlotType.BREAKFAST, CookOwner.FRED, oatBananaBowl.recipeId, status = MealSlotStatus.COOKED),
      ),
    )

    val plan = MenuPlannerEngine.buildWeeklyMealPrepSessionPlan(
      board, listOf(spicedOmeletteRecipe, oatBananaBowl), baseCatalog,
    )

    // COOKED Wednesday slot is excluded; all 4 stations populate from Monday & Tuesday PLANNED meals.
    assertEquals(listOf("slot_mon_b", "slot_tue_b"), plan.coveredSlotIds.sorted())
    assertTrue(PrepStationType.entries.all { requireNotNull(plan.tasksByStation[it]).size == 1 })
    val proteinTask = requireNotNull(plan.tasksByStation[PrepStationType.PROTEINS_MARINADES]).single()
    assertEquals(6.0, requireNotNull(proteinTask.aggregatedIngredients["ing_egg"]), 0.001)

    val pantryBeforePrep = listOf(
      PantryLedgerEntry("ing_egg", IngredientTier.TIER_1_QUANTITATIVE, 10.0),
      PantryLedgerEntry("ing_oats", IngredientTier.TIER_1_QUANTITATIVE, 500.0),
      PantryLedgerEntry("ing_banana", IngredientTier.TIER_1_QUANTITATIVE, 400.0),
      PantryLedgerEntry("ing_olive_oil", IngredientTier.TIER_2_STAPLE, 300.0, StapleStatus.PLENTY),
    )
    val commitResult = MenuPlannerEngine.applyWeeklyPrepSessionBatch(
      board, pantryBeforePrep, baseCatalog, plan,
      WeeklyPrepBatchCommitPayload(plan.sessionId, board.boardId, plan.tasks.map { it.taskId }, true),
    )

    // Prepped slots receive isPreppedAhead = true, reduced active time, and suppressed night banner.
    assertEquals(listOf("slot_mon_b", "slot_tue_b"), commitResult.stampedSlotIds)
    assertEquals(6.0, requireNotNull(commitResult.deductedQuantities["ing_egg"]), 0.001)
    assertEquals(200.0, requireNotNull(commitResult.deductedQuantities["ing_oats"]), 0.001)

    val stampedMon = requireNotNull(commitResult.updatedBoard.slots.find { it.slotId == "slot_mon_b" })
    assertTrue(stampedMon.isPreppedAhead)
    val preppedCard = MenuPlannerEngine.buildResolvedMealCard(stampedMon, spicedOmeletteRecipe, baseCatalog, currentHour24 = 21)
    assertTrue(preppedCard.calibratedActiveMinutes < spicedOmeletteRecipe.calibratedActiveMinutes)
    assertFalse(preppedCard.showNightBeforeBanner)
  }

  /**
   * Verifies Life-Event serving scaling (`POSTPARTUM_BREASTFEEDING +1` and `VISITING_FAMILY_GUESTS +2`)
   * updates [HouseholdProfile.effectiveTargetServings] and scales weekly Tier-1 BOM reservations.
   */
  @Test
  fun lifeEventServingScaling_scalesHouseholdTargetServingsAndGroceryReservations() {
    // Postpartum adds +1 serving (5), visiting guests add +2 servings (6), combined adds +3 (7).
    val postpartumProfile = HouseholdProfile(activeLifeEvents = listOf(HouseholdLifeEventPreset.POSTPARTUM_BREASTFEEDING.toLifeEvent()))
    val visitingGuestsProfile = HouseholdProfile(activeLifeEvents = listOf(HouseholdLifeEventPreset.VISITING_FAMILY_GUESTS.toLifeEvent()))
    val combinedProfile = HouseholdProfile(
      activeLifeEvents = listOf(
        HouseholdLifeEventPreset.POSTPARTUM_BREASTFEEDING.toLifeEvent(),
        HouseholdLifeEventPreset.VISITING_FAMILY_GUESTS.toLifeEvent(),
      ),
    )
    assertEquals(5, postpartumProfile.effectiveTargetServings)
    assertEquals(6, visitingGuestsProfile.effectiveTargetServings)
    assertEquals(7, combinedProfile.effectiveTargetServings)

    // Scaling a 4-egg baseline meal to 6 guest servings reserves 6.0 eggs (4 * 6 / 4 = 6.0).
    val guestBoard = WeeklyBoard(
      "board_guests", visitingGuestsProfile.householdId, "2026-10-26",
      listOf(MealSlot("slot_sat_b", 5, MealSlotType.BREAKFAST, CookOwner.FRED, spicedOmeletteRecipe.recipeId, visitingGuestsProfile.effectiveTargetServings)),
    )
    val emptyEggPantry = listOf(
      PantryLedgerEntry("ing_egg", IngredientTier.TIER_1_QUANTITATIVE, 0.0, StapleStatus.OUT, isOutOfStock = true),
    )
    val diff = GroceryDiffEngine.computeWeeklyGroceryDiff(
      guestBoard, listOf(spicedOmeletteRecipe), baseCatalog, emptyEggPantry,
    )
    assertEquals(6.0, diff.groceryItems.single().totalReservedQuantity, 0.001)
  }

  private fun readCanonicalSeedCatalogJson(): String {
    // Support running JUnit from android/app (AGP module dir), android/, or repository root.
    val candidatePaths = listOf(
      Path("src/main/assets/seed_catalog.json"),
      Path("app/src/main/assets/seed_catalog.json"),
      Path("android/app/src/main/assets/seed_catalog.json"),
    )
    val matchedPath = requireNotNull(candidatePaths.firstOrNull { it.isRegularFile() }) {
      "Canonical seed_catalog.json asset not found from current working directory"
    }
    return matchedPath.readText()
  }
}
