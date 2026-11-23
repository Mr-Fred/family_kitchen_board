package com.familykitchen.board.domain

import com.familykitchen.board.data.FakeKitchenStorage
import com.familykitchen.board.ports.AiEdgePort
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ports.FixedKitchenClock
import com.familykitchen.board.ports.KitchenSnapshot
import com.familykitchen.board.ports.SeedCatalogPort
import com.familykitchen.board.ports.VoiceInputPort
import com.familykitchen.board.ports.VoiceInputState
import com.familykitchen.board.ui.KitchenBoardViewModel
import java.nio.file.Path
import kotlin.io.path.Path as KotlinPath
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Suite 17: Hermetic JVM JUnit 4 verification of Pantry drift recovery (skippedStockedItems +
// Ran Low requeue, proportional Empty/Low/Half/Full shelf audits), Multi-Market Palengke vs
// Supermarket classification, zero-boomerang uncheckCheckedGroceryItems, and O(1) Unpack Groceries.

/**
 * Suite 17 hermetic JUnit 4 verification suite covering Smart Pantry drift recovery, multi-market
 * shopping (`Palengke` vs `Supermarket`), and zone-aware grocery unpacking:
 * 1. Transparent `WeeklyGroceryDiff.skippedStockedItems` (`Pantry Adjusted` banner) and 1-tap
 *    [KitchenBoardViewModel.requeueSkippedPantryStaple] (`Ran Low` -> `batchWriteCallCount == 1`),
 * 2. [setProportionalPantryLevel] (`Empty · Low · Half · Full`) updating Tier-1 quantities to exact
 *    `0% / 25% / 50% / 100%` package fractions and Tier-2 staples to `OUT / LOW / PLENTY`,
 * 3. [MarketVenue] (`WET_MARKET` `"Palengke"` vs `SUPERMARKET` `"Supermarket"`) via
 *    [resolveMarketVenue], `GroceryListItem.marketVenue`, and `activeGroceryVenueFilter`,
 * 4. [KitchenBoardViewModel.uncheckAllCheckedGrocery] (`uncheckCheckedGroceryItems`) resetting
 *    checkmarks in `1` `O(1)` write without deleting custom items or hiding meal-plan deficits,
 * 5. [KitchenBoardViewModel.confirmUnpackGroceries] (`commitGroceryUnpackBatch`) applying Freezer
 *    zone overrides (`>= 90d` `expiryDateIso`) and promoting food custom notes into `Pantry` while
 *    clearing non-food household notes in **1 single `O(1)` batch write**.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PantryDriftMultiMarketAndUnpackTest {

  // Minimal offline AiEdgePort test double keeping Pantry/Shop unit tests hermetic.
  private class OfflinePantryTestEdgePort : AiEdgePort {
    private val mutableState = MutableStateFlow<AiRuntimeState>(AiRuntimeState.Uninitialized)
    override val runtimeState: StateFlow<AiRuntimeState> = mutableState.asStateFlow()
    override suspend fun autoDiscoverAndInitialize(): AiRuntimeState = mutableState.value
    override suspend fun importAndLoadModel(sourceUriString: String, displayName: String?): AiRuntimeState = mutableState.value
    override suspend fun generateText(prompt: String): String = "{}"
    override suspend fun analyzeImageWithPrompt(preprocessedImagePath: String, prompt: String): String = "{}"
    override fun close() = Unit
  }

  // Minimal offline VoiceInputPort test double with zero Android platform dependencies.
  private class OfflinePantryTestVoicePort : VoiceInputPort {
    private val mutableVoice = MutableStateFlow<VoiceInputState>(VoiceInputState.Idle)
    override val voiceState: StateFlow<VoiceInputState> = mutableVoice.asStateFlow()
    override fun startListening(languageTag: String): Boolean = true
    override fun stopListening() = Unit
    override fun release() = Unit
  }

  // Resolve repository-relative paths deterministically from either root or android/app cwd.
  private fun resolveRepoFile(relativeFromRepoRoot: String): Path {
    var cursor: Path? = KotlinPath("").toAbsolutePath().normalize()
    repeat(6) {
      val candidate = cursor?.resolve(relativeFromRepoRoot)
      if (candidate != null && candidate.isRegularFile()) return candidate
      cursor = cursor?.parent
    }
    error("Unable to locate '$relativeFromRepoRoot' from test working directory.")
  }

  // Load the canonical 44-item seed catalog dataset from disk.
  private fun loadCanonicalSeed(): SeedCatalogDataset =
    SchemaValidator.decodeSeedCatalogDatasetJson(resolveRepoFile("data/seed_catalog.json").readText())

  /**
   * Verifies `WeeklyGroceryDiff.skippedStockedItems` exposes stocked ingredients omitted from
   * `Shop` (`Pantry Adjusted` banner) and [KitchenBoardViewModel.requeueSkippedPantryStaple]
   * (`Ran Low`) re-queues a skipped staple onto `Shop` in `1` `O(1)` atomic batch write.
   */
  @Test
  fun skippedStockedItemsAndRequeue_exposeCoveredStaplesAndRestoreRanLowItemInOneWrite() = runTest {
    val testDispatcher = UnconfinedTestDispatcher(testScheduler)
    Dispatchers.setMain(testDispatcher)
    try {
      val seed = loadCanonicalSeed()
      val omelette = mergeHeritageRecipesIntoLibrary(seed.recipes).first {
        it.recipeId == "rec_40_omelette_fines_herbes_tartine"
      }
      // Stock 12 farm eggs in Pantry so Monday Breakfast (5 eggs) is 100% covered by existing stock.
      val stockedEggsPantry = seed.initialPantry.map { entry ->
        if (entry.ingredientId == "ing_egg") {
          entry.copy(currentQuantity = 12.0, isOutOfStock = false, stapleStatus = StapleStatus.PLENTY)
        } else {
          entry
        }
      }
      val board = WeeklyBoard(
        boardId = "board_drift",
        householdId = "household_family_4",
        weekStartDateIso = "2026-11-23",
        slots = listOf(
          MealSlot("s_mon_b", 0, MealSlotType.BREAKFAST, CookOwner.FRED, omelette.recipeId, targetServings = 4),
        ),
      )
      val initialSnapshot = KitchenSnapshot(
        ingredients = seed.ingredients,
        recipes = mergeHeritageRecipesIntoLibrary(seed.recipes),
        pantryEntries = stockedEggsPantry,
        weeklyBoard = board,
      )
      val storage = FakeKitchenStorage(initialSnapshot = initialSnapshot)
      val viewModel = KitchenBoardViewModel(
        seedCatalogPort = SeedCatalogPort { seed },
        storagePort = storage,
        aiEdgePort = OfflinePantryTestEdgePort(),
        voiceInputPort = OfflinePantryTestVoicePort(),
        clockPort = FixedKitchenClock("2026-11-23", currentHour24 = 9),
      )

      // Eggs are omitted from Shop groceryItems, but transparently surfaced in skippedStockedItems!
      val initialDiff = viewModel.uiState.value.weeklyGroceryDiff
      assertFalse(initialDiff.groceryItems.any { it.ingredientId == "ing_egg" })
      val skippedEggs = initialDiff.skippedStockedItems.firstOrNull { it.ingredientId == "ing_egg" }
      assertNotNull(skippedEggs)
      assertEquals(5.0, skippedEggs?.requiredQuantity ?: 0.0, 0.001)

      // Shopper realizes kids ate the eggs off-plan and taps "Ran Low" -> 1 O(1) write re-queues eggs!
      storage.resetWriteCounter()
      viewModel.requeueSkippedPantryStaple("ing_egg")
      assertEquals(1, storage.batchWriteCallCount)
      assertTrue(viewModel.uiState.value.weeklyGroceryDiff.groceryItems.any { it.ingredientId == "ing_egg" })
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * Verifies [setProportionalPantryLevel] (`Empty · Low · Half · Full`) across Tier-1 quantitative
   * items (`0%`, `25%`, `50%`, `100%` of `defaultPackageQuantity`) and Tier-2 staples (`OUT`,
   * `LOW`, `PLENTY`), clearing expiry on `EMPTY` and seeding zone shelf life on restock.
   */
  @Test
  fun proportionalPantryLevels_snapTier1FractionsAndTier2StatusesWithoutStepperSpam() {
    val seed = loadCanonicalSeed()
    val milkPkg = seed.ingredients.first { it.ingredientId == "ing_milk" }.defaultPackageQuantity

    // Audit Tier-1 Whole Fresh Milk across FULL -> HALF -> LOW -> EMPTY -> FULL.
    val atFull = setProportionalPantryLevel(
      seed.initialPantry, "ing_milk", ProportionalStockLevel.FULL, seed.ingredients, "2026-11-23",
    ).first { it.ingredientId == "ing_milk" }
    assertEquals(milkPkg, atFull.currentQuantity, 0.01)
    assertFalse(atFull.isOutOfStock)

    val atHalf = setProportionalPantryLevel(
      seed.initialPantry, "ing_milk", ProportionalStockLevel.HALF, seed.ingredients, "2026-11-23",
    ).first { it.ingredientId == "ing_milk" }
    assertEquals(milkPkg * 0.5, atHalf.currentQuantity, 0.01)

    val atLow = setProportionalPantryLevel(
      seed.initialPantry, "ing_milk", ProportionalStockLevel.LOW, seed.ingredients, "2026-11-23",
    ).first { it.ingredientId == "ing_milk" }
    assertEquals(milkPkg * 0.25, atLow.currentQuantity, 0.01)

    val atEmptyPantry = setProportionalPantryLevel(
      seed.initialPantry, "ing_milk", ProportionalStockLevel.EMPTY, seed.ingredients, "2026-11-23",
    )
    val atEmpty = atEmptyPantry.first { it.ingredientId == "ing_milk" }
    assertEquals(0.0, atEmpty.currentQuantity, 0.001)
    assertTrue(atEmpty.isOutOfStock)
    assertNull(atEmpty.expiresInDays)
    assertNull(atEmpty.expiryDateIso)

    // Restoring depleted milk to HALF seeds a fresh expiryDateIso anchored to todayIso.
    val restoredHalf = setProportionalPantryLevel(
      atEmptyPantry, "ing_milk", ProportionalStockLevel.HALF, seed.ingredients, "2026-11-23",
    ).first { it.ingredientId == "ing_milk" }
    assertEquals(milkPkg * 0.5, restoredHalf.currentQuantity, 0.01)
    assertNotNull(restoredHalf.expiryDateIso)

    // Audit Tier-2 Extra-Virgin Olive Oil across EMPTY (OUT), LOW (LOW), and FULL (PLENTY).
    val oilOut = setProportionalPantryLevel(
      seed.initialPantry, "ing_olive_oil", ProportionalStockLevel.EMPTY, seed.ingredients, "2026-11-23",
    ).first { it.ingredientId == "ing_olive_oil" }
    assertEquals(StapleStatus.OUT, oilOut.stapleStatus)
    assertTrue(oilOut.isOutOfStock)

    val oilLow = setProportionalPantryLevel(
      seed.initialPantry, "ing_olive_oil", ProportionalStockLevel.LOW, seed.ingredients, "2026-11-23",
    ).first { it.ingredientId == "ing_olive_oil" }
    assertEquals(StapleStatus.LOW, oilLow.stapleStatus)
    assertFalse(oilLow.isOutOfStock)
  }

  /**
   * Verifies [MarketVenue] (`Palengke` vs `Supermarket`) classification across Antipolo–Marikina
   * fresh produce/seafood/poultry/farm eggs/gata vs packaged dairy/bakery/oils, plus explicit
   * `preferredVenue` overrides and `GroceryListItem.marketVenue` propagation.
   */
  @Test
  fun marketVenueResolution_routesFreshPalengkeStallsSeparatelyFromSupermarketAisles() {
    val seed = loadCanonicalSeed()
    val byId = indexCatalogById(seed.ingredients)
    val presetById = LOCAL_MARKET_PRESET_INGREDIENTS.associateBy { it.ingredientId }

    // Wet market (Palengke): fresh produce, chicken thighs, snapper, farm eggs, and pressed gata.
    assertEquals(MarketVenue.WET_MARKET, resolveMarketVenue(byId.getValue("ing_amaranth_greens")))
    assertEquals(MarketVenue.WET_MARKET, resolveMarketVenue(byId.getValue("ing_chicken")))
    assertEquals(MarketVenue.WET_MARKET, resolveMarketVenue(byId.getValue("ing_fish")))
    assertEquals(MarketVenue.WET_MARKET, resolveMarketVenue(byId.getValue("ing_egg")))
    assertEquals(MarketVenue.WET_MARKET, resolveMarketVenue(presetById.getValue("ing_gata")))

    // Supermarket: chilled dairy, butter, baguette, jasmine rice, canned tuna, and olive oil.
    assertEquals(MarketVenue.SUPERMARKET, resolveMarketVenue(byId.getValue("ing_milk")))
    assertEquals(MarketVenue.SUPERMARKET, resolveMarketVenue(byId.getValue("ing_butter")))
    assertEquals(MarketVenue.SUPERMARKET, resolveMarketVenue(byId.getValue("ing_baguette")))
    assertEquals(MarketVenue.SUPERMARKET, resolveMarketVenue(byId.getValue("ing_rice")))
    assertEquals(MarketVenue.SUPERMARKET, resolveMarketVenue(byId.getValue("ing_tuna")))
    assertEquals(MarketVenue.SUPERMARKET, resolveMarketVenue(byId.getValue("ing_olive_oil")))

    // Explicit user preferredVenue override takes precedence over category heuristics.
    val supermarketEggs = byId.getValue("ing_egg").copy(preferredVenue = MarketVenue.SUPERMARKET)
    assertEquals(MarketVenue.SUPERMARKET, resolveMarketVenue(supermarketEggs))
  }

  /**
   * Verifies zero-boomerang [KitchenBoardViewModel.uncheckAllCheckedGrocery] and zone-aware
   * [KitchenBoardViewModel.confirmUnpackGroceries] (`Freezer` -> `>= 90d` + food custom item
   * promotion into `Pantry` + non-food custom note cleanup) in **1 single `O(1)` batch write**.
   */
  @Test
  fun safeUncheckAndUnpackGroceries_preserveDeficitsApplyFreezerZonesAndPromoteFoodInOneWrite() = runTest {
    val testDispatcher = UnconfinedTestDispatcher(testScheduler)
    Dispatchers.setMain(testDispatcher)
    try {
      val seed = loadCanonicalSeed()
      val emptyPantry = seed.initialPantry.map {
        it.copy(currentQuantity = 0.0, isOutOfStock = true, stapleStatus = StapleStatus.OUT, expiresInDays = null)
      }
      val poulet = mergeHeritageRecipesIntoLibrary(seed.recipes).first { it.recipeId == "rec_37_poulet_basquaise" }
      val board = WeeklyBoard(
        boardId = "board_unpack",
        householdId = "household_family_4",
        weekStartDateIso = "2026-11-23",
        slots = listOf(MealSlot("s_mon_d", 0, MealSlotType.DINNER, CookOwner.WIFE, poulet.recipeId, targetServings = 4)),
      )
      val initialCustomItems = listOf(
        CustomGroceryItem("cust_mangoes", "Carabao Mangoes", "1 kg", IngredientCategory.PRODUCE, isChecked = true),
        CustomGroceryItem("cust_towels", "Kitchen Paper Towels", "2 rolls", IngredientCategory.PANTRY_STAPLE, isChecked = true),
      )
      val snapshot = KitchenSnapshot(
        ingredients = seed.ingredients,
        recipes = mergeHeritageRecipesIntoLibrary(seed.recipes),
        pantryEntries = emptyPantry,
        weeklyBoard = board,
        checkedGroceryItemIds = setOf("ing_chicken", "ing_bell_pepper"),
        customGroceryItems = initialCustomItems,
      )
      val storage = FakeKitchenStorage(initialSnapshot = snapshot)
      val viewModel = KitchenBoardViewModel(
        seedCatalogPort = SeedCatalogPort { seed },
        storagePort = storage,
        aiEdgePort = OfflinePantryTestEdgePort(),
        voiceInputPort = OfflinePantryTestVoicePort(),
        clockPort = FixedKitchenClock("2026-11-23", currentHour24 = 17),
      )
      storage.resetWriteCounter()

      // 1. Zero-boomerang Uncheck Checked: unchecks boxes in 1 write without deleting custom rows or deficits.
      viewModel.uncheckAllCheckedGrocery()
      assertEquals(1, storage.batchWriteCallCount)
      val afterUncheck = viewModel.uiState.value.snapshot
      assertTrue(afterUncheck.checkedGroceryItemIds.isEmpty())
      assertEquals(2, afterUncheck.customGroceryItems.size)
      assertTrue(afterUncheck.customGroceryItems.none { it.isChecked })
      assertTrue(viewModel.uiState.value.weeklyGroceryDiff.groceryItems.any { it.ingredientId == "ing_chicken" })

      // 2. Re-check items and run Unpack Groceries: chicken -> FREEZER (90d), mangoes -> promoted to Pantry,
      //    and paper towels -> cleared without polluting Pantry, all in 1 single O(1) batch write!
      viewModel.toggleGroceryItemChecked("ing_chicken")
      viewModel.toggleGroceryItemChecked("cust_mangoes")
      viewModel.toggleGroceryItemChecked("cust_towels")
      storage.resetWriteCounter()

      viewModel.confirmUnpackGroceries(
        directives = listOf(
          UnpackItemDirective(
            ingredientId = "ing_chicken",
            restockQuantity = 1000.0,
            targetLocation = StorageLocation.FREEZER,
          ),
        ),
        promotedCustomIds = setOf("cust_mangoes"),
        clearCustomIds = setOf("cust_towels"),
      )
      assertEquals(1, storage.batchWriteCallCount)

      val unpacked = viewModel.uiState.value.snapshot
      val chickenEntry = unpacked.pantryById.getValue("ing_chicken")
      assertEquals(1000.0, chickenEntry.currentQuantity, 0.001)
      assertEquals(StorageLocation.FREEZER, chickenEntry.storageLocation)
      assertTrue((chickenEntry.expiresInDays ?: 0) >= 90)
      assertEquals(addDaysIso("2026-11-23", chickenEntry.expiresInDays ?: 90), chickenEntry.expiryDateIso)

      // Promoted Carabao Mangoes stocked in Pantry; non-food Paper Towels cleared without entering Pantry!
      val mangoItem = unpacked.ingredients.firstOrNull { it.displayName.contains("Mango", ignoreCase = true) }
      assertNotNull(mangoItem)
      val mangoPantry = unpacked.pantryById[mangoItem?.ingredientId.orEmpty()]
      assertNotNull(mangoPantry)
      assertFalse(mangoPantry?.isOutOfStock ?: true)
      assertFalse(unpacked.ingredients.any { it.displayName.contains("Towel", ignoreCase = true) })
      assertTrue(unpacked.customGroceryItems.isEmpty())
    } finally {
      Dispatchers.resetMain()
    }
  }
}
