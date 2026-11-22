package com.familykitchen.board.domain

import com.familykitchen.board.data.FakeKitchenStorage
import com.familykitchen.board.ports.AiEdgePort
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ports.KitchenSnapshot
import com.familykitchen.board.ports.SeedCatalogPort
import com.familykitchen.board.ports.VoiceInputPort
import com.familykitchen.board.ports.VoiceInputState
import com.familykitchen.board.ui.KitchenBoardViewModel
import com.familykitchen.board.ui.KitchenUiState
import com.familykitchen.board.ui.deriveKitchenUiState
import com.familykitchen.board.ui.grocery.humanizeGroceryPackageLabel
import com.familykitchen.board.ui.reduceGroceryAndCatalogEdit
import kotlin.io.path.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// Hermetic JVM JUnit 4 suite verifying editable Smart Grocery quantities, prorated PHP ₱ costs,
// whole-pack stepping, trip-level item skipping/restoring, custom shopping item edits/deletions,
// and global household ingredient renaming across Pantry, Shop, Meal Plan, and PDF/Clipboard exports.

/**
 * 13th hermetic JUnit 4 verification suite covering `Shop` & `Pantry` quantity overrides, trip
 * skips, custom item edits, and global catalog ingredient renaming:
 * 1. Rescaling a `4000 g` (`4 packs`, `₱920`) weekly `ing_chicken` deficit down to `2000 g` (`2
 *    packs`, `₱460`) or fractional `1500 g` (`"Custom (1500 g)"` -> `"Custom · 1.5 kg"`, `₱345`),
 *    stepping whole packs (`2 -> 3 -> 1` clamped at `>= 1`), resetting back to plan (`4000 g`), and
 *    restocking the exact edited `+2000 g` into Pantry in `1` `O(1)` batch write while clearing
 *    fulfilled overrides,
 * 2. Skipping items for the current shopping trip (`DismissGroceryItemForTrip`), restoring skipped
 *    items (`RestoreDismissedGroceryItems`), and editing/deleting `CustomGroceryItem`s in `O(1)`
 *    writes reflected cleanly in `buildGroceryExportDocument`,
 * 3. Globally renaming catalog ingredients (`"Fresh Amaranth Greens"` -> `"Fresh Kulitis"`) across
 *    `Pantry`, `Shop`, `MenuPlannerEngine.buildResolvedMealCard`, `WeeklyPrepSessionPlan`, and
 *    both export formatters while rejecting blank names with [ValidationException].
 */
class GroceryAndCatalogEditTest {

  private val canonicalSeed: SeedCatalogDataset by lazy {
    SchemaValidator.decodeSeedCatalogDatasetJson(readCanonicalSeedCatalogJson())
  }

  /**
   * Reproduces the exact `4 kg -> 2 kg / 1.5 kg` shopping override scenario on `ing_chicken`
   * (`Free-Range Chicken Thighs`, `1000 g` pack @ `₱230`), verifying prorated costs, humanized
   * custom labels, whole-pack stepping (`>= 1` clamp), plan reset, and `1`-write `+2000 g` Pantry
   * restock with automatic override cleanup.
   */
  @Test
  fun groceryQuantityOverrides_rescaleChicken4kgTo2kgOr1_5kgProrateCostAndRestockExactEditedAmount() = runTest {
    val stampIso = "2026-11-22T08:00:00Z"
    val braisedChickenRecipe = Recipe(
      recipeId = "rec_test_braised_chicken",
      title = "Family Braised Chicken Thighs",
      sourceAttribution = "Family Kitchen",
      allowedSlots = listOf(MealSlotType.DINNER),
      preferredOwner = CookOwner.WIFE,
      baseServings = 4,
      rawPrepMinutes = 10,
      cookMinutes = 25,
      calibratedActiveMinutes = 39,
      toddlerPrepTip = "Shred tender chicken thighs into soft strips for 2yo.",
      bomLines = listOf(BomLine(ingredientId = "ing_chicken", baseQuantity = 1000.0)),
    )

    // Schedule 4 weekday dinners x 1000g with 0g chicken in stock -> 4000g (4 packs, ₱920) deficit.
    val fourDinnerSlots = (0..3).map { day ->
      MealSlot(
        slotId = "board_chicken_day_${day}_dinner",
        dayIndex = day,
        slotType = MealSlotType.DINNER,
        owner = CookOwner.WIFE,
        recipeId = braisedChickenRecipe.recipeId,
        targetServings = 4,
        status = MealSlotStatus.PLANNED,
      )
    }
    val zeroChickenPantry = canonicalSeed.initialPantry.map { entry ->
      when (entry.ingredientId) {
        "ing_chicken" -> entry.copy(currentQuantity = 0.0, stapleStatus = StapleStatus.OUT, isOutOfStock = true)
        else -> entry
      }
    }
    val initialSnapshot = KitchenSnapshot(
      ingredients = canonicalSeed.ingredients,
      recipes = canonicalSeed.recipes + braisedChickenRecipe,
      pantryEntries = zeroChickenPantry,
      weeklyBoard = WeeklyBoard("board_chicken", "household_family_4", "2026-10-26", fourDinnerSlots),
    )

    // 1. Confirm un-overridden weekly baseline: 4000g deficit -> 4 packs @ ₱230 = ₱920.
    val baselineDiff = deriveKitchenUiState(KitchenUiState(), initialSnapshot).weeklyGroceryDiff
    val baselineChicken = requireNotNull(baselineDiff.groceryItems.find { it.ingredientId == "ing_chicken" })
    assertEquals("Free-Range Chicken Thighs", baselineChicken.displayName)
    assertEquals(4, baselineChicken.packagesToBuy)
    assertEquals(4000.0, baselineChicken.restockQuantity, 0.001)
    assertEquals(4000.0, baselineChicken.plannedRestockQuantity, 0.001)
    assertFalse(baselineChicken.isQuantityOverridden)
    assertEquals(920.0, baselineChicken.estimatedCostUsd, 0.001)
    assertEquals("₱920", baselineChicken.formattedEstimatedCost())
    assertEquals("4 packs (4000 g)", baselineChicken.packageDescriptor)
    assertEquals("4 packs · 4 kg", humanizeGroceryPackageLabel(baselineChicken))

    // 2. Rename to "Chicken Thighs" and override buy quantity from 4000g down to 2000g (2 packs, ₱460).
    val (twoKgSnapshot, twoKgToast) = reduceGroceryAndCatalogEdit(
      snapshot = initialSnapshot,
      currentDiff = baselineDiff,
      action = GroceryAndCatalogEditAction.SaveGroceryCatalogItemEdit(
        ingredientId = "ing_chicken",
        updatedDisplayName = "Chicken Thighs",
        overrideBuyQuantity = 2000.0,
      ),
      nowIso = stampIso,
    )
    assertEquals("Updated 'Chicken Thighs' on your Shopping List.", twoKgToast)
    val twoKgDiff = deriveKitchenUiState(KitchenUiState(), twoKgSnapshot).weeklyGroceryDiff
    val twoKgChicken = requireNotNull(twoKgDiff.groceryItems.find { it.ingredientId == "ing_chicken" })
    assertEquals("Chicken Thighs", twoKgChicken.displayName)
    assertEquals(2, twoKgChicken.packagesToBuy)
    assertEquals(2000.0, twoKgChicken.restockQuantity, 0.001)
    assertEquals(4000.0, twoKgChicken.plannedRestockQuantity, 0.001)
    assertTrue(twoKgChicken.isQuantityOverridden)
    assertEquals(460.0, twoKgChicken.estimatedCostUsd, 0.001)
    assertEquals("₱460", twoKgChicken.formattedEstimatedCost())
    assertEquals("2 packs (2000 g)", twoKgChicken.packageDescriptor)
    assertEquals("2 packs · 2 kg", humanizeGroceryPackageLabel(twoKgChicken))

    // 3. Apply fractional 1500g (1.5 kg) custom override -> prorated ₱345 & "Custom · 1.5 kg".
    val (customKgSnapshot, _) = reduceGroceryAndCatalogEdit(
      snapshot = twoKgSnapshot,
      currentDiff = twoKgDiff,
      action = GroceryAndCatalogEditAction.SaveGroceryCatalogItemEdit(
        ingredientId = "ing_chicken",
        updatedDisplayName = "Chicken Thighs",
        overrideBuyQuantity = 1500.0,
      ),
      nowIso = stampIso,
    )
    val customKgDiff = deriveKitchenUiState(KitchenUiState(), customKgSnapshot).weeklyGroceryDiff
    val customKgChicken = requireNotNull(customKgDiff.groceryItems.find { it.ingredientId == "ing_chicken" })
    assertEquals(1500.0, customKgChicken.restockQuantity, 0.001)
    assertEquals(4000.0, customKgChicken.plannedRestockQuantity, 0.001)
    assertTrue(customKgChicken.isQuantityOverridden)
    assertEquals(345.0, customKgChicken.estimatedCostUsd, 0.001)
    assertEquals("₱345", customKgChicken.formattedEstimatedCost())
    assertEquals("Custom (1500 g)", customKgChicken.packageDescriptor)
    assertEquals("Custom · 1.5 kg", humanizeGroceryPackageLabel(customKgChicken))

    // 4. Step whole packs cleanly (2 -> 3 -> 1, clamped at >= 1) and reset back to 4000g plan.
    val (threePackSnapshot, _) = reduceGroceryAndCatalogEdit(
      twoKgSnapshot, twoKgDiff, GroceryAndCatalogEditAction.StepGroceryItemPacks("ing_chicken", 1), stampIso,
    )
    val threePackDiff = deriveKitchenUiState(KitchenUiState(), threePackSnapshot).weeklyGroceryDiff
    val threePackChicken = requireNotNull(threePackDiff.groceryItems.find { it.ingredientId == "ing_chicken" })
    assertEquals(3, threePackChicken.packagesToBuy)
    assertEquals(3000.0, threePackChicken.restockQuantity, 0.001)
    assertEquals(690.0, threePackChicken.estimatedCostUsd, 0.001)

    val (onePackSnapshot, _) = reduceGroceryAndCatalogEdit(
      threePackSnapshot, threePackDiff, GroceryAndCatalogEditAction.StepGroceryItemPacks("ing_chicken", -2), stampIso,
    )
    val onePackDiff = deriveKitchenUiState(KitchenUiState(), onePackSnapshot).weeklyGroceryDiff
    assertEquals(1, requireNotNull(onePackDiff.groceryItems.find { it.ingredientId == "ing_chicken" }).packagesToBuy)

    // Attempting to step below 1 pack clamps safely at 1 pack (1000g).
    val (clampedSnapshot, _) = reduceGroceryAndCatalogEdit(
      onePackSnapshot, onePackDiff, GroceryAndCatalogEditAction.StepGroceryItemPacks("ing_chicken", -5), stampIso,
    )
    val clampedChicken = requireNotNull(
      deriveKitchenUiState(KitchenUiState(), clampedSnapshot).weeklyGroceryDiff.groceryItems
        .find { it.ingredientId == "ing_chicken" },
    )
    assertEquals(1, clampedChicken.packagesToBuy)
    assertEquals(1000.0, clampedChicken.restockQuantity, 0.001)

    // Resetting the override restores the full 4000g (4 packs, ₱920) weekly meal plan requirement.
    val (resetSnapshot, _) = reduceGroceryAndCatalogEdit(
      clampedSnapshot, onePackDiff, GroceryAndCatalogEditAction.ResetGroceryItemOverride("ing_chicken"), stampIso,
    )
    val resetChicken = requireNotNull(
      deriveKitchenUiState(KitchenUiState(), resetSnapshot).weeklyGroceryDiff.groceryItems
        .find { it.ingredientId == "ing_chicken" },
    )
    assertEquals(4, resetChicken.packagesToBuy)
    assertEquals(4000.0, resetChicken.restockQuantity, 0.001)
    assertEquals(920.0, resetChicken.estimatedCostUsd, 0.001)
    assertFalse(resetChicken.isQuantityOverridden)

    // 5. Restocking the overridden 2kg delta adds exact +2000g in 1 O(1) write & clears override!
    val storage = FakeKitchenStorage(initialSnapshot = twoKgSnapshot)
    assertEquals(0, storage.batchWriteCallCount)
    val twoKgDelta = requireNotNull(twoKgDiff.restockDeltas.find { it.ingredientId == "ing_chicken" })
    assertEquals(2000.0, twoKgDelta.addedBaseQuantity, 0.001)

    val restockedSnapshot = storage.commitGroceryRestockBatch(twoKgSnapshot, listOf(twoKgDelta), stampIso)
    assertEquals(1, storage.batchWriteCallCount)
    val restockedChickenEntry = requireNotNull(restockedSnapshot.pantryById["ing_chicken"])
    assertEquals(2000.0, restockedChickenEntry.currentQuantity, 0.001)
    assertFalse(restockedChickenEntry.isOutOfStock)
    assertEquals(StapleStatus.PLENTY, restockedChickenEntry.stapleStatus)
    assertFalse("ing_chicken" in restockedSnapshot.groceryQuantityOverrides)
  }

  /**
   * Verifies [GroceryAndCatalogEditAction.DismissGroceryItemForTrip],
   * [GroceryAndCatalogEditAction.RestoreDismissedGroceryItems],
   * [GroceryAndCatalogEditAction.SaveCustomGroceryItemEdit], and
   * [GroceryAndCatalogEditAction.DeleteCustomGroceryItem] across [KitchenBoardViewModel],
   * [FakeKitchenStorage] (`1` `O(1)` write per action), and [buildGroceryExportDocument].
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun tripDismissRestoreAndCustomItemEdits_filterActiveListExportCleanlyAndPersistInSingleBatch() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    try {
      // Seed a snapshot with depleted Chicken Thighs & Avocados plus a guaranteed chicken dinner slot.
      val chickenDinnerSlot = MealSlot(
        slotId = "board_week_1_day_0_dinner",
        dayIndex = 0,
        slotType = MealSlotType.DINNER,
        owner = CookOwner.WIFE,
        recipeId = canonicalSeed.recipes.first { rec -> rec.bomLines.any { it.ingredientId == "ing_chicken" } }.recipeId,
        targetServings = 4,
        status = MealSlotStatus.PLANNED,
      )
      val depletedPantry = canonicalSeed.initialPantry.map { entry ->
        when (entry.ingredientId) {
          "ing_chicken", "ing_avocado" ->
            entry.copy(currentQuantity = 0.0, stapleStatus = StapleStatus.OUT, isOutOfStock = true)
          else -> entry
        }
      }
      val seedSnap = KitchenSnapshot.fromSeedCatalog(canonicalSeed)
      val guaranteedBoard = seedSnap.weeklyBoard.copy(
        slots = seedSnap.weeklyBoard.slots.filterNot { it.slotId == chickenDinnerSlot.slotId } + chickenDinnerSlot,
      )
      val baseSnapshot = seedSnap.copy(
        pantryEntries = depletedPantry,
        weeklyBoard = guaranteedBoard,
        aiAddedGroceryIds = setOf("ing_avocado"),
      )
      val storage = FakeKitchenStorage(initialSnapshot = baseSnapshot)
      val viewModel = KitchenBoardViewModel(
        seedCatalogPort = SeedCatalogPort { canonicalSeed },
        storagePort = storage,
        aiEdgePort = StubGroceryTestAiEdgePort(),
        voiceInputPort = SilentGroceryTestVoicePort(),
        deleteTempImage = {},
      )
      assertEquals(0, storage.batchWriteCallCount)
      assertTrue(viewModel.uiState.value.weeklyGroceryDiff.groceryItems.any { it.ingredientId == "ing_chicken" })

      // 1. Dismissing ing_chicken removes it from groceryItems, restockDeltas, and export in 1 write.
      viewModel.onGroceryAndCatalogEdit(GroceryAndCatalogEditAction.DismissGroceryItemForTrip("ing_chicken"))
      assertEquals(1, storage.batchWriteCallCount)
      val afterSkipState = viewModel.uiState.value
      assertTrue("ing_chicken" in afterSkipState.snapshot.dismissedGroceryItemIds)
      assertFalse(afterSkipState.weeklyGroceryDiff.groceryItems.any { it.ingredientId == "ing_chicken" })
      assertFalse(afterSkipState.weeklyGroceryDiff.restockDeltas.any { it.ingredientId == "ing_chicken" })

      val skippedExport = buildGroceryExportDocument(
        groceryDiff = afterSkipState.weeklyGroceryDiff,
        checkedGroceryItemIds = afterSkipState.snapshot.checkedGroceryItemIds,
        customGroceryItems = afterSkipState.snapshot.customGroceryItems,
        householdProfile = afterSkipState.snapshot.householdProfile,
      )
      assertFalse(skippedExport.sections.flatMap { it.items }.any { it.primaryText.contains("Chicken") })
      assertFalse(skippedExport.clipboardText.contains("Free-Range Chicken Thighs"))

      // 2. Restoring dismissed items brings ing_chicken back onto Shop and export in 1 O(1) write.
      viewModel.onGroceryAndCatalogEdit(GroceryAndCatalogEditAction.RestoreDismissedGroceryItems)
      assertEquals(2, storage.batchWriteCallCount)
      val afterRestoreState = viewModel.uiState.value
      assertTrue(afterRestoreState.snapshot.dismissedGroceryItemIds.isEmpty())
      assertTrue(afterRestoreState.weeklyGroceryDiff.groceryItems.any { it.ingredientId == "ing_chicken" })
      assertTrue(afterRestoreState.weeklyGroceryDiff.restockDeltas.any { it.ingredientId == "ing_chicken" })
      val restoredExport = buildGroceryExportDocument(
        groceryDiff = afterRestoreState.weeklyGroceryDiff,
        checkedGroceryItemIds = afterRestoreState.snapshot.checkedGroceryItemIds,
        customGroceryItems = afterRestoreState.snapshot.customGroceryItems,
        householdProfile = afterRestoreState.snapshot.householdProfile,
      )
      assertTrue(restoredExport.clipboardText.contains("Free-Range Chicken Thighs"))

      // 3. Add, edit (rename + "2 rolls" + PANTRY_STAPLE), and delete a CustomGroceryItem in O(1) writes.
      viewModel.addCustomGroceryItem(
        CustomGroceryItem(
          customId = "custom_paper_towels",
          displayName = "Paper Towels",
          quantityNote = "1 roll",
          category = IngredientCategory.PRODUCE,
        ),
      )
      assertEquals(3, storage.batchWriteCallCount)

      viewModel.onGroceryAndCatalogEdit(
        GroceryAndCatalogEditAction.SaveCustomGroceryItemEdit(
          CustomGroceryItem(
            customId = "custom_paper_towels",
            displayName = "Unbleached Kitchen Towels",
            quantityNote = "2 rolls",
            category = IngredientCategory.PANTRY_STAPLE,
          ),
        ),
      )
      assertEquals(4, storage.batchWriteCallCount)
      val editedCustom = viewModel.uiState.value.snapshot.customGroceryItems.single()
      assertEquals("Unbleached Kitchen Towels", editedCustom.displayName)
      assertEquals("2 rolls", editedCustom.quantityNote)
      assertEquals(IngredientCategory.PANTRY_STAPLE, editedCustom.category)

      val customExport = buildGroceryExportDocument(
        groceryDiff = viewModel.uiState.value.weeklyGroceryDiff,
        customGroceryItems = viewModel.uiState.value.snapshot.customGroceryItems,
        sortMode = GrocerySortMode.AISLE_SORTED,
      )
      val pantryAisle = requireNotNull(customExport.sections.find { it.sectionTitle == "Aisle 7: Pantry & Baking" })
      val exportedCustomLine = requireNotNull(pantryAisle.items.find { it.primaryText == "Unbleached Kitchen Towels" })
      assertEquals("2 rolls", exportedCustomLine.subtitleText)

      // Deleting the custom item removes it cleanly in 1 single O(1) atomic write.
      viewModel.onGroceryAndCatalogEdit(GroceryAndCatalogEditAction.DeleteCustomGroceryItem("custom_paper_towels"))
      assertEquals(5, storage.batchWriteCallCount)
      assertTrue(viewModel.uiState.value.snapshot.customGroceryItems.isEmpty())
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * Verifies that [GroceryAndCatalogEditAction.UpdateCatalogIngredientDetails] updates
   * `snapshot.ingredients` in `1` `O(1)` write so `Pantry`, `Shop`,
   * [MenuPlannerEngine.buildResolvedMealCard], `WeeklyPrepSessionPlan`, and both
   * [buildPantryExportDocument] / [buildGroceryExportDocument] reflect `"Fresh Kulitis"`
   * immediately, while blank names fail with [ValidationException].
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun globalCatalogRename_propagatesFamiliarHouseholdNamesAcrossPantryShopBoardAndExports() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    try {
      // Schedule a planned Monday dinner requiring 350g ing_amaranth_greens with 100g expiring in Pantry.
      val kulitisDinner = Recipe(
        recipeId = "rec_test_kulitis_saute",
        title = "Garlic Sautéed Greens & Rice",
        sourceAttribution = "Antipolo Family Kitchen",
        allowedSlots = listOf(MealSlotType.DINNER),
        preferredOwner = CookOwner.WIFE,
        baseServings = 4,
        rawPrepMinutes = 8,
        cookMinutes = 12,
        calibratedActiveMinutes = 24,
        toddlerPrepTip = "Chop tender greens finely and mix into warm rice for 2yo.",
        bomLines = listOf(BomLine(ingredientId = "ing_amaranth_greens", baseQuantity = 350.0)),
      )
      val dinnerSlot = MealSlot(
        slotId = "board_kulitis_day_0_dinner",
        dayIndex = 0,
        slotType = MealSlotType.DINNER,
        owner = CookOwner.WIFE,
        recipeId = kulitisDinner.recipeId,
        targetServings = 4,
        status = MealSlotStatus.PLANNED,
      )
      val pantryWithExpiringGreens = canonicalSeed.initialPantry.map { entry ->
        when (entry.ingredientId) {
          "ing_amaranth_greens" -> entry.copy(
            currentQuantity = 100.0,
            stapleStatus = StapleStatus.PLENTY,
            isOutOfStock = false,
            storageLocation = StorageLocation.FRIDGE,
            expiresInDays = 2,
          )
          else -> entry
        }
      }
      val seededSnapshot = KitchenSnapshot(
        ingredients = canonicalSeed.ingredients,
        recipes = canonicalSeed.recipes + kulitisDinner,
        pantryEntries = pantryWithExpiringGreens,
        weeklyBoard = WeeklyBoard("board_kulitis", "household_family_4", "2026-10-26", listOf(dinnerSlot)),
      )
      val storage = FakeKitchenStorage(initialSnapshot = seededSnapshot)
      val viewModel = KitchenBoardViewModel(
        seedCatalogPort = SeedCatalogPort { canonicalSeed },
        storagePort = storage,
        aiEdgePort = StubGroceryTestAiEdgePort(),
        voiceInputPort = SilentGroceryTestVoicePort(),
        deleteTempImage = {},
      )
      assertEquals(0, storage.batchWriteCallCount)

      // 1. Rename "Fresh Amaranth Greens" -> "Fresh Kulitis" (250g pack @ ₱50) from Pantry.
      viewModel.onGroceryAndCatalogEdit(
        GroceryAndCatalogEditAction.UpdateCatalogIngredientDetails(
          ingredientId = "ing_amaranth_greens",
          updatedDisplayName = "Fresh Kulitis",
          updatedDefaultPackageQuantity = 250.0,
          updatedPackagePriceUsd = 50.0,
        ),
      )
      assertEquals(1, storage.batchWriteCallCount)
      val state = viewModel.uiState.value
      val renamedCatalogItem = requireNotNull(state.snapshot.catalogById["ing_amaranth_greens"])
      assertEquals("Fresh Kulitis", renamedCatalogItem.displayName)
      assertEquals(250.0, renamedCatalogItem.defaultPackageQuantity, 0.001)
      assertEquals(50.0, renamedCatalogItem.estimatedPackagePriceUsd, 0.001)

      // 2. Verify Pantry dashboard & Shop grocery diff both surface "Fresh Kulitis" immediately.
      val expiringKulitis = requireNotNull(
        state.pantryExpiryDashboard.expiringItems.find { it.entry.ingredientId == "ing_amaranth_greens" },
      )
      assertEquals("Fresh Kulitis", expiringKulitis.catalogItem.displayName)
      val shopKulitis = requireNotNull(
        state.weeklyGroceryDiff.groceryItems.find { it.ingredientId == "ing_amaranth_greens" },
      )
      assertEquals("Fresh Kulitis", shopKulitis.displayName)
      assertEquals(1, shopKulitis.packagesToBuy)
      assertEquals(250.0, shopKulitis.restockQuantity, 0.001)
      assertEquals(50.0, shopKulitis.estimatedCostUsd, 0.001)

      // 3. Verify Board resolved meal card, recipe pantry match, and batch-prep station instructions.
      val resolvedCard = MenuPlannerEngine.buildResolvedMealCard(
        slot = dinnerSlot,
        recipe = kulitisDinner,
        catalog = state.snapshot.ingredients,
        householdProfile = state.snapshot.householdProfile,
      )
      val resolvedBomLabels = resolvedCard.effectiveBomQuantities.keys.map { id ->
        state.snapshot.catalogById[id]?.displayName ?: id
      }
      assertEquals(listOf("Fresh Kulitis"), resolvedBomLabels)
      val matchSummary = PantryExpiryEngine.evaluateRecipePantryMatch(
        recipe = kulitisDinner,
        pantry = state.snapshot.pantryEntries,
        catalog = state.snapshot.ingredients,
      )
      assertEquals(listOf("Fresh Kulitis"), matchSummary.missingStaples.map { it.displayName })
      assertTrue(state.weeklyPrepSessionPlan.tasks.any { it.instruction.contains("Fresh Kulitis (350 g)") })

      // 4. Verify Pantry & Grocery export documents emit "Fresh Kulitis" with zero stale labels.
      val pantryDoc = buildPantryExportDocument(
        state.snapshot.ingredients, state.snapshot.pantryEntries, state.pantryExpiryDashboard, state.snapshot.householdProfile,
      )
      assertTrue(pantryDoc.clipboardText.contains("Fresh Kulitis"))
      assertFalse(pantryDoc.clipboardText.contains("Fresh Amaranth Greens"))

      val groceryDoc = buildGroceryExportDocument(
        state.weeklyGroceryDiff, state.snapshot.checkedGroceryItemIds, state.snapshot.customGroceryItems,
        state.snapshot.grocerySortMode, state.snapshot.householdProfile,
      )
      assertTrue(groceryDoc.clipboardText.contains("[ ] Fresh Kulitis — 1 bag (250 g) · ₱50"))
      assertFalse(groceryDoc.clipboardText.contains("Fresh Amaranth Greens"))

      // 5. Reject blank names with ValidationException and guard ViewModel with 0 extra batch writes.
      val blankAction = GroceryAndCatalogEditAction.UpdateCatalogIngredientDetails(
        ingredientId = "ing_amaranth_greens",
        updatedDisplayName = "   ",
      )
      assertThrows(ValidationException::class.java) {
        reduceGroceryAndCatalogEdit(state.snapshot, state.weeklyGroceryDiff, blankAction)
      }
      viewModel.onGroceryAndCatalogEdit(blankAction)
      assertEquals(1, storage.batchWriteCallCount)
      assertEquals("Fresh Kulitis", viewModel.uiState.value.snapshot.catalogById["ing_amaranth_greens"]?.displayName)
      assertTrue(requireNotNull(viewModel.uiState.value.toastMessage).contains("displayName: must be non-blank"))
    } finally {
      Dispatchers.resetMain()
    }
  }

  private fun readCanonicalSeedCatalogJson(): String {
    // Support running JUnit from android/app, android/, or repository root working directories.
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

  // Hermetic no-op AiEdgePort fake keeping ViewModel unit tests free of JNI or socket side effects.
  private class StubGroceryTestAiEdgePort : AiEdgePort {
    private val state = MutableStateFlow<AiRuntimeState>(AiRuntimeState.Uninitialized)
    override val runtimeState: StateFlow<AiRuntimeState> = state.asStateFlow()
    override suspend fun autoDiscoverAndInitialize(): AiRuntimeState = state.value
    override suspend fun importAndLoadModel(sourceUriString: String, displayName: String?): AiRuntimeState = state.value
    override suspend fun generateText(prompt: String): String = "{}"
    override suspend fun analyzeImageWithPrompt(preprocessedImagePath: String, prompt: String): String =
      throw VisionNotSupportedException("Stub port does not enable vision.")
    override fun close() = Unit
  }

  // Hermetic no-op VoiceInputPort fake avoiding Android SpeechRecognizer calls in JVM unit tests.
  private class SilentGroceryTestVoicePort : VoiceInputPort {
    private val state = MutableStateFlow<VoiceInputState>(VoiceInputState.Idle)
    override val voiceState: StateFlow<VoiceInputState> = state.asStateFlow()
    override fun startListening(languageTag: String): Boolean = false
    override fun stopListening() = Unit
    override fun release() = Unit
  }
}
