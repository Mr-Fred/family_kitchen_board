package com.familykitchen.board.domain

import com.familykitchen.board.data.FakeKitchenStorage
import com.familykitchen.board.data.KitchenPdfExporter
import com.familykitchen.board.data.PdfRowKind
import com.familykitchen.board.data.PlannedPdfPage
import com.familykitchen.board.ports.AiEdgePort
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ports.SeedCatalogPort
import com.familykitchen.board.ports.VoiceInputPort
import com.familykitchen.board.ports.VoiceInputState
import com.familykitchen.board.ui.DEFAULT_TOAST_AUTO_DISMISS_MS
import com.familykitchen.board.ui.EXTENDED_TOAST_AUTO_DISMISS_MS
import com.familykitchen.board.ui.KitchenBoardViewModel
import com.familykitchen.board.ui.computeToastAutoDismissMillis
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
import org.junit.Assert.assertTrue
import org.junit.Test

// Hermetic JVM JUnit 4 suite verifying KitchenExportFormatter (Grocery & Pantry plain-text + PDF
// models), KitchenPdfExporter pure multi-page A4 layout & orphan-header prevention, and ViewModel
// export feedback toast EventId monotonicity.

/**
 * 12th hermetic JUnit 4 verification suite covering `Save PDF` and `Copy to Clipboard` across
 * `Shop` (`SmartGroceryScreen`) and `Pantry` (`SmartPantryScreen`):
 * 1. [buildGroceryExportDocument] aisle perimeter walk (`Aisle 1..7` omitting empty aisles),
 *    `BY_RECIPE` & `URGENCY_FIRST` sort modes, `CustomGroceryItem`s, `[ ]` vs `[x]` partitioning,
 *    meal usage attributions, `PHP` / `USD` / `XOF` currencies, `"All Planned Meals Covered"`
 *    empty-list fallback, and [sanitizeExportFileSlug] path-traversal elimination,
 * 2. [buildPantryExportDocument] on the canonical 44-item `seed_catalog.json` (`44` stocked, `3`
 *    expiring `<= 48h`, `₱3,200` at-risk value, `96%` Saved Score, `"Action Required — Use Within
 *    48h"` rescue cards, humanized `kg`/`g`/`L`/`ml`/`count`/`Plenty`/`Low` stock badges, and
 *    `EMPTY_KITCHEN` `"Empty Pantry"` fallback),
 * 3. [KitchenPdfExporter.wrapTextToCharBudget] & [KitchenPdfExporter.planPdfPages] A4 pagination
 *    invariants (`totalPages >= 2`, `includesDocumentHeader` on page 1 only, bounded vertical
 *    heights, and zero orphaned [PdfRowKind.SECTION_HEADER] rows at page bottom) plus monotonic
 *    [KitchenBoardViewModel.postExportFeedback] toast notifications.
 */
class KitchenExportAndPdfLayoutTest {

  /**
   * Verifies [buildGroceryExportDocument] across `AISLE_SORTED` (omitting empty supermarket
   * aisles), `BY_RECIPE`, and `URGENCY_FIRST`, including [CustomGroceryItem]s, `[ ]` vs `[x]`
   * checkmarks, meal attributions, `PHP` (`₱`), `USD` (`$`), and `XOF` (`CFA`) currencies,
   * `"All Planned Meals Covered"` fallback, and [sanitizeExportFileSlug] traversal stripping.
   */
  @Test
  fun groceryExportFormatter_formatsAislesCustomItemsCheckedSectionCurrenciesAndSafeFilenames() {
    // 1. Verify sanitizeExportFileSlug strips traversal tokens, slashes, and control punctuation.
    val weirdSlug = sanitizeExportFileSlug("../../My / Weird*Kitchen?!")
    assertEquals("My_Weird_Kitchen", weirdSlug)
    assertFalse(weirdSlug.contains('/') || weirdSlug.contains(".."))
    assertEquals("Family_Kitchen", sanitizeExportFileSlug("../../../?!*"))
    val longSlug = sanitizeExportFileSlug("Antipolo Marikina Grand Multigenerational Family Kitchen")
    assertTrue(longSlug.length <= 32 && !longSlug.endsWith("_"))

    // 2. Build a realistic Grocery diff omitting DAIRY_EGGS (Aisle 2) to prove empty aisles skip.
    val avocadoItem = GroceryListItem(
      ingredientId = "ing_avocado", displayName = "Ripe Hass Avocado",
      category = IngredientCategory.PRODUCE, tier = IngredientTier.TIER_1_QUANTITATIVE,
      baseUnit = BaseUnit.COUNT, totalReservedQuantity = 6.0, currentStockQuantity = 2.0,
      netDeficit = 4.0, defaultPackageQuantity = 4.0, packagesToBuy = 1, restockQuantity = 4.0,
      mealUsageLabels = listOf(
        "for Tue Breakfast • Avocado Tartine",
        "For Thu Lunch • Sunshine Bowl",
        "for Sat Breakfast • Egg Toast",
      ),
      estimatedCostUsd = 165.0, packageDescriptor = "1 bag (4 count)",
    )
    val chickenItem = GroceryListItem(
      ingredientId = "ing_chicken", displayName = "Free-Range Chicken Thighs",
      category = IngredientCategory.PROTEIN, tier = IngredientTier.TIER_1_QUANTITATIVE,
      baseUnit = BaseUnit.GRAM, totalReservedQuantity = 3000.0, currentStockQuantity = 1000.0,
      netDeficit = 2000.0, defaultPackageQuantity = 1000.0, packagesToBuy = 2, restockQuantity = 2000.0,
      mealUsageLabels = listOf("for Mon Dinner • Poulet Braise"),
      estimatedCostUsd = 460.0, packageDescriptor = "2 packs (2 kg)",
    )
    val oliveOilLowStaple = GroceryListItem(
      ingredientId = "ing_olive_oil", displayName = "Extra-Virgin Olive Oil",
      category = IngredientCategory.PANTRY_STAPLE, tier = IngredientTier.TIER_2_STAPLE,
      baseUnit = BaseUnit.MILLILITER, totalReservedQuantity = 0.0, currentStockQuantity = 0.0,
      netDeficit = 0.0, defaultPackageQuantity = 750.0, packagesToBuy = 1, restockQuantity = 750.0,
      stapleStatus = StapleStatus.LOW, isManuallyQueued = true,
      estimatedCostUsd = 695.0, packageDescriptor = "1 bottle (750 ml)",
    )
    val checkedRiceItem = GroceryListItem(
      ingredientId = "ing_rice", displayName = "Jasmine White Rice",
      category = IngredientCategory.BAKERY_GRAINS, tier = IngredientTier.TIER_1_QUANTITATIVE,
      baseUnit = BaseUnit.GRAM, totalReservedQuantity = 2000.0, currentStockQuantity = 0.0,
      netDeficit = 2000.0, defaultPackageQuantity = 2000.0, packagesToBuy = 1, restockQuantity = 2000.0,
      estimatedCostUsd = 116.0, packageDescriptor = "1 sack (2 kg)",
    )
    val allCatalogItems = listOf(avocadoItem, chickenItem, oliveOilLowStaple, checkedRiceItem)
    val diff = WeeklyGroceryDiff(
      slotSummaries = emptyList(), slotSummariesBySlotId = emptyMap(),
      groceryItems = allCatalogItems, groceryItemsByCategory = allCatalogItems.groupBy { it.category },
      restockDeltas = emptyList(), skippedStockedStapleCount = 9,
      deduplicationSavingsUsd = 1840.0, plannedMealCount = 21, estimatedTotalCostUsd = 1436.0,
    )
    val customItems = listOf(
      CustomGroceryItem("custom_pandesal", "Warm Marikina Pandesal", "2 dozen bag", IngredientCategory.BAKERY_GRAINS, false),
      CustomGroceryItem("custom_napkins", "Unbleached Paper Towels", "2 rolls", IngredientCategory.PANTRY_STAPLE, true),
    )

    // 3. Verify AISLE_SORTED perimeter walk (Aisle 1 -> 4 -> 5 -> 7, omitting empty Aisle 2) + PHP ₱.
    val phpProfile = HouseholdProfile(
      householdName = "../../My / Weird*Kitchen?!",
      currency = HouseholdCurrency.PHP,
      marketRegionLabel = "Antipolo & Marikina, PH",
    )
    val aisleDoc = buildGroceryExportDocument(
      groceryDiff = diff, checkedGroceryItemIds = setOf("ing_rice"),
      customGroceryItems = customItems, sortMode = GrocerySortMode.AISLE_SORTED, householdProfile = phpProfile,
    )
    assertEquals("SavorAI Smart Grocery List", aisleDoc.title)
    assertEquals("SavorAI_Grocery_List_My_Weird_Kitchen.pdf", aisleDoc.suggestedFileName)
    assertFalse(aisleDoc.suggestedFileName.contains('/') || aisleDoc.suggestedFileName.contains(".."))
    assertEquals(
      listOf(
        ExportMetricChip("To Buy", "4"),
        ExportMetricChip("Estimated Cost", "₱1,320"),
        ExportMetricChip("Pantry Saved", "₱1,840"),
        ExportMetricChip("Checked Off", "2"),
      ),
      aisleDoc.metrics,
    )
    assertEquals(
      listOf(
        "Aisle 1: Produce & Herbs", "Aisle 4: Meat & Seafood",
        "Aisle 5: Bakery & Grains", "Aisle 7: Pantry & Baking", "Checked Off",
      ),
      aisleDoc.sections.map { it.sectionTitle },
    )

    // Verify meal attribution formatting (+N overflow & manual queue) and custom/checked states.
    val produceRow = aisleDoc.sections[0].items.single()
    assertEquals(ExportCheckboxState.UNCHECKED, produceRow.checkboxState)
    assertEquals(
      "For: Tue Breakfast • Avocado Tartine; Thu Lunch • Sunshine Bowl (+1 more)",
      produceRow.subtitleText,
    )
    val customBakeryRow = aisleDoc.sections[2].items.single()
    assertEquals("Warm Marikina Pandesal", customBakeryRow.primaryText)
    assertEquals("Custom item", customBakeryRow.trailingBadge)
    assertEquals("2 dozen bag", customBakeryRow.subtitleText)
    assertEquals("For: Household Kitchen Queue", aisleDoc.sections[3].items.single().subtitleText)
    assertEquals(2, aisleDoc.sections[4].itemCount)
    assertTrue(aisleDoc.sections[4].items.all { it.checkboxState == ExportCheckboxState.CHECKED })
    assertTrue(aisleDoc.clipboardText.contains("[ ] Ripe Hass Avocado — 1 bag (4 count) · ₱165"))
    assertTrue(aisleDoc.clipboardText.contains("[ ] Warm Marikina Pandesal — Custom item"))
    assertTrue(aisleDoc.clipboardText.contains("[x] Jasmine White Rice — 1 sack (2 kg) · ₱116"))
    assertTrue(aisleDoc.clipboardText.contains("[x] Unbleached Paper Towels — Custom item"))

    // Confirm kotlinx.serialization round-trip integrity on ExportedKitchenDocument.
    val encodedJson = SchemaValidator.strictJson.encodeToString(ExportedKitchenDocument.serializer(), aisleDoc)
    assertEquals(aisleDoc, SchemaValidator.strictJson.decodeFromString(ExportedKitchenDocument.serializer(), encodedJson))

    // 4. Verify BY_RECIPE and URGENCY_FIRST ordering plus USD ($) and XOF (CFA) currency formatting.
    val usdByRecipeDoc = buildGroceryExportDocument(
      groceryDiff = diff, checkedGroceryItemIds = setOf("ing_rice"), customGroceryItems = customItems,
      sortMode = GrocerySortMode.BY_RECIPE,
      householdProfile = phpProfile.copy(
        householdName = "Our Family Kitchen", currency = HouseholdCurrency.USD,
        marketRegionLabel = "Local Neighborhood Market, US",
      ),
    )
    assertEquals("SavorAI_Grocery_List_Our_Family_Kitchen.pdf", usdByRecipeDoc.suggestedFileName)
    assertEquals(listOf("By Planned Meal", "Checked Off"), usdByRecipeDoc.sections.map { it.sectionTitle })
    assertEquals("$1,320", usdByRecipeDoc.metrics[1].value)
    assertEquals("$1,840", usdByRecipeDoc.metrics[2].value)
    // Empty mealUsageLabels ("") sorts first, then "for Mon Dinner...", then "for Tue Breakfast...".
    assertEquals(
      listOf("Extra-Virgin Olive Oil", "Free-Range Chicken Thighs", "Ripe Hass Avocado", "Warm Marikina Pandesal"),
      usdByRecipeDoc.sections.first().items.map { it.primaryText },
    )

    val xofUrgencyDoc = buildGroceryExportDocument(
      groceryDiff = diff, checkedGroceryItemIds = setOf("ing_rice"), customGroceryItems = customItems,
      sortMode = GrocerySortMode.URGENCY_FIRST,
      householdProfile = phpProfile.copy(
        householdName = "Maison Cotonou", currency = HouseholdCurrency.XOF,
        marketRegionLabel = "Cotonou & Neighborhood Market",
      ),
    )
    assertEquals(listOf("Shortages First", "Checked Off"), xofUrgencyDoc.sections.map { it.sectionTitle })
    assertEquals("CFA 1,320", xofUrgencyDoc.metrics[1].value)
    assertTrue(xofUrgencyDoc.footerSummary.contains("CFA 1,320"))
    // Tier-1 quantitative shortages rank by netDeficit descending ahead of Tier-2 LOW staples.
    assertEquals(
      listOf("Free-Range Chicken Thighs", "Ripe Hass Avocado", "Extra-Virgin Olive Oil", "Warm Marikina Pandesal"),
      xofUrgencyDoc.sections.first().items.map { it.primaryText },
    )

    // 5. Verify "All Planned Meals Covered" empty-list celebration fallback.
    val emptyDiff = WeeklyGroceryDiff(
      emptyList(), emptyMap(), emptyList(), emptyMap(), emptyList(),
      skippedStockedStapleCount = 21, deduplicationSavingsUsd = 3450.0, plannedMealCount = 21,
    )
    val coveredDoc = buildGroceryExportDocument(groceryDiff = emptyDiff)
    assertEquals(listOf("All Planned Meals Covered"), coveredDoc.sections.map { it.sectionTitle })
    assertEquals(ExportCheckboxState.NONE, coveredDoc.sections.single().items.single().checkboxState)
    assertEquals("100% stocked", coveredDoc.sections.single().items.single().trailingBadge)
    assertTrue(coveredDoc.clipboardText.contains("All Planned Meals Covered (1 item)"))
  }

  /**
   * Verifies [buildPantryExportDocument] against the canonical 44-item `seed_catalog.json` (`44`
   * stocked, `3` expiring `<= 48h`, `₱3,200` at-risk value, `96%` Saved Score, urgent `ing_salmon`
   * / `ing_greek_yogurt` / `ing_spinach` rescue cards, humanized `kg`/`g`/`L`/`ml`/`count`/`Plenty`
   * badges) and the zero-stock `EMPTY_KITCHEN` `"Empty Pantry"` fallback.
   */
  @Test
  fun pantryExportFormatter_formatsCanonical44ItemSeedUrgent48hZonesAndEmptyFallback() {
    val seed = SchemaValidator.decodeSeedCatalogDatasetJson(
      resolveRepoFile("data/seed_catalog.json").readText(),
    )
    assertEquals(44, seed.ingredients.size)
    assertEquals(44, seed.initialPantry.size)

    val dashboard = PantryExpiryEngine.evaluatePantryExpiryDashboard(
      pantry = seed.initialPantry, catalog = seed.ingredients, recipes = seed.recipes, rescuedMealsCount = 12,
    )
    val pantryDoc = buildPantryExportDocument(seed.ingredients, seed.initialPantry, dashboard, HouseholdProfile())

    // 1. Verify header identity, safe filename, and exact 44-item Antipolo-Marikina PHP ₱ metrics.
    assertEquals("SavorAI Pantry Inventory", pantryDoc.title)
    assertEquals("SavorAI_Pantry_Inventory_Our_Family_Kitchen.pdf", pantryDoc.suggestedFileName)
    assertEquals(
      listOf(
        ExportMetricChip("Stocked", "44"),
        ExportMetricChip("Expiring <= 48h", "3"),
        ExportMetricChip("At-Risk Value", "₱3,200"),
        ExportMetricChip("Saved Score", "96%"),
      ),
      pantryDoc.metrics,
    )

    // 2. Verify leading "Action Required — Use Within 48h" section and all 3 storage zones.
    assertEquals(
      listOf("Action Required — Use Within 48h", "Fridge & Produce", "Dry Goods & Pantry", "Freezer"),
      pantryDoc.sections.map { it.sectionTitle },
    )
    val urgentSection = pantryDoc.sections.first()
    assertEquals(3, urgentSection.itemCount)
    assertEquals(
      listOf("Atlantic Salmon Fillets", "Plain Greek Yogurt", "Fresh Baby Spinach"),
      urgentSection.items.map { it.primaryText },
    )
    assertTrue(urgentSection.items.all { it.isUrgentHighlight && it.checkboxState == ExportCheckboxState.NONE })
    assertTrue(urgentSection.items[0].trailingBadge.contains("1.6 kg · Tomorrow (₱2,240)"))
    assertTrue(urgentSection.items[1].trailingBadge.contains("2 kg · In 2 days (₱790)"))
    assertTrue(urgentSection.items[2].trailingBadge.contains("600 g · In 2 days (₱170)"))
    assertTrue(urgentSection.items.all { it.subtitleText?.contains("Rescue meal:") == true })

    // 3. Verify humanized quantities (kg, g, L, count, Plenty) and crisp clipboard output on seed.
    val zoneBadges = pantryDoc.sections.drop(1).flatMap { sec -> sec.items.map { it.trailingBadge } }
    assertTrue(zoneBadges.any { it.startsWith("1.6 kg · ") || it.startsWith("4 kg · ") })
    assertTrue(zoneBadges.any { it.startsWith("600 g · ") || it.startsWith("500 g · ") })
    assertTrue(zoneBadges.any { it.startsWith("2 L · ") })
    assertTrue(zoneBadges.any { it.startsWith("24 count · ") || it.startsWith("4 count · ") })
    assertTrue(zoneBadges.any { it.startsWith("Plenty · ") })
    assertTrue(pantryDoc.clipboardText.contains("! Atlantic Salmon Fillets — 1.6 kg · Tomorrow (₱2,240)"))
    assertTrue(pantryDoc.clipboardText.contains("• Extra-Virgin Olive Oil — Plenty · Fresh"))

    // Also verify sub-liter "ml" and Tier-2 "Low" badges when milk is partially used (450 ml).
    val partialMilkPantry = seed.initialPantry.map { entry ->
      when (entry.ingredientId) {
        "ing_milk" -> entry.copy(currentQuantity = 450.0)
        "ing_olive_oil" -> entry.copy(stapleStatus = StapleStatus.LOW)
        else -> entry
      }
    }
    val partialDashboard = PantryExpiryEngine.evaluatePantryExpiryDashboard(
      partialMilkPantry, seed.ingredients, seed.recipes,
    )
    val partialBadges = buildPantryExportDocument(seed.ingredients, partialMilkPantry, partialDashboard)
      .sections.flatMap { sec -> sec.items.map { it.trailingBadge } }
    assertTrue(partialBadges.any { it.startsWith("450 ml · ") })
    assertTrue(partialBadges.any { it.startsWith("Low · ") })

    // 4. Verify zero-stock EMPTY_KITCHEN onboarding produces the "Empty Pantry" fallback cleanly.
    val emptyOutcome = computeFirstRunOnboardingOutcome(
      catalog = seed.ingredients, recipes = seed.recipes, seedPantry = seed.initialPantry,
      currentBoard = WeeklyBoard("board_empty", "household_family_4", "2026-10-26", emptyList()),
      draft = FirstRunOnboardingDraft(pantryMode = StartingPantryMode.EMPTY_KITCHEN),
    )
    val emptyDashboard = PantryExpiryEngine.evaluatePantryExpiryDashboard(
      emptyOutcome.pantryEntries, seed.ingredients, seed.recipes, rescuedMealsCount = 0,
    )
    val emptyPantryDoc = buildPantryExportDocument(
      seed.ingredients, emptyOutcome.pantryEntries, emptyDashboard, emptyOutcome.householdProfile,
    )
    assertEquals("0", emptyPantryDoc.metrics.first().value)
    assertEquals(listOf("Empty Pantry"), emptyPantryDoc.sections.map { it.sectionTitle })
    assertEquals("Out of stock", emptyPantryDoc.sections.single().items.single().trailingBadge)
    assertTrue(emptyPantryDoc.clipboardText.contains("Empty Pantry (1 item)"))
  }

  /**
   * Verifies [KitchenPdfExporter.wrapTextToCharBudget] line-length caps, [KitchenPdfExporter.planPdfPages]
   * multi-page A4 pagination and orphan-header prevention across the 44-item Pantry document, a
   * multi-section Grocery document, and a tight boundary stress case, plus monotonic
   * [KitchenBoardViewModel.postExportFeedback] toast notifications.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun pdfPaginationPlannerAndViewModel_preventOrphanHeadersWrapLongLinesAndEmitExportToasts() = runTest {
    // 1. Verify wrapTextToCharBudget wraps multi-word prose and hard-slices unbroken tokens cleanly.
    assertEquals(emptyList<String>(), KitchenPdfExporter.wrapTextToCharBudget("   \n  ", 24))
    val multiWordWrapped = KitchenPdfExporter.wrapTextToCharBudget(
      text = "For: Monday Dinner • Poulet Bicyclette Braise; Wednesday Lunch • Lemon Herb Salmon\nKeep chilled at 4C",
      maxCharsPerLine = 28,
    )
    assertTrue(multiWordWrapped.size >= 4 && multiWordWrapped.all { it.isNotBlank() && it.length <= 28 })
    assertEquals("Keep chilled at 4C", multiWordWrapped.last())

    val unbrokenToken = "SuperLongUnbrokenHouseholdIngredientIdentifierTokenWithoutSpaces12345"
    val unbrokenWrapped = KitchenPdfExporter.wrapTextToCharBudget(unbrokenToken, maxCharsPerLine = 16)
    assertEquals(5, unbrokenWrapped.size)
    assertTrue(unbrokenWrapped.all { it.length <= 16 })
    assertEquals(unbrokenToken, unbrokenWrapped.joinToString(separator = ""))

    // 2. Paginate the canonical 44-item Pantry Inventory and verify multi-page A4 layout invariants.
    val seed = SchemaValidator.decodeSeedCatalogDatasetJson(
      resolveRepoFile("data/seed_catalog.json").readText(),
    )
    val pantryDashboard = PantryExpiryEngine.evaluatePantryExpiryDashboard(
      seed.initialPantry, seed.ingredients, seed.recipes,
    )
    val pantryPages = KitchenPdfExporter.planPdfPages(
      buildPantryExportDocument(seed.ingredients, seed.initialPantry, pantryDashboard),
    )
    assertValidMultiPageA4Plan(pantryPages)

    // 3. Paginate a full multi-section Grocery List from an EMPTY_KITCHEN 0-stock pantry + 21-slot board.
    val emptyKitchenOutcome = computeFirstRunOnboardingOutcome(
      catalog = seed.ingredients, recipes = seed.recipes, seedPantry = seed.initialPantry,
      currentBoard = WeeklyBoard("board_shop", "household_family_4", "2026-10-26", emptyList()),
      draft = FirstRunOnboardingDraft(pantryMode = StartingPantryMode.EMPTY_KITCHEN),
    )
    val draftedTwentyOneSlotBoard = MenuPlannerEngine.autoDraftWeeklyBoard(
      boardId = "board_shop",
      householdId = "household_family_4",
      weekStartDateIso = "2026-10-26",
      recipes = seed.recipes,
      catalog = seed.ingredients,
      householdAllergens = emptyKitchenOutcome.householdProfile.householdAllergens,
      defaultServings = emptyKitchenOutcome.householdProfile.effectiveTargetServings,
      pantry = emptyKitchenOutcome.pantryEntries,
    )
    val fullWeekDiff = GroceryDiffEngine.computeWeeklyGroceryDiff(
      draftedTwentyOneSlotBoard, seed.recipes, seed.ingredients, emptyKitchenOutcome.pantryEntries,
    )
    val groceryDoc = buildGroceryExportDocument(
      groceryDiff = fullWeekDiff,
      checkedGroceryItemIds = fullWeekDiff.groceryItems.take(3).map { it.ingredientId }.toSet(),
      customGroceryItems = listOf(
        CustomGroceryItem("custom_foil", "Heavy-Duty Baking Foil", "1 box", IngredientCategory.PANTRY_STAPLE),
      ),
      sortMode = GrocerySortMode.AISLE_SORTED,
      householdProfile = emptyKitchenOutcome.householdProfile,
    )
    assertValidMultiPageA4Plan(KitchenPdfExporter.planPdfPages(groceryDoc))

    // 4. Stress-test exact page-bottom boundary where SECTION_HEADER fits alone (34pt) but
    //    SECTION_HEADER + first LINE_ITEM (62pt) overflows Page 1's 642pt body budget (610pt used).
    val boundarySectionOne = ExportSection(
      "Aisle 1: Produce & Herbs", 21, (1..21).map { idx -> ExportLineItem("Produce Item $idx", "1 pack") },
    )
    val boundarySectionTwo = ExportSection(
      "Aisle 4: Meat & Seafood", 2,
      listOf(ExportLineItem("Atlantic Salmon Fillets", "2 packs"), ExportLineItem("Chicken Thighs", "2 packs")),
    )
    val boundaryPages = KitchenPdfExporter.planPdfPages(
      groceryDoc.copy(sections = listOf(boundarySectionOne, boundarySectionTwo)),
    )
    assertValidMultiPageA4Plan(boundaryPages)
    // Confirm Section 2's header moved atomically with its first item onto Page 2 (zero orphan!).
    assertEquals(PdfRowKind.SECTION_HEADER, boundaryPages[1].rows.first().kind)
    assertEquals(listOf("Aisle 4: Meat & Seafood"), boundaryPages[1].rows.first().primaryLines)
    assertEquals(PdfRowKind.LINE_ITEM, boundaryPages[1].rows[1].kind)

    // 5. Verify KitchenBoardViewModel.postExportFeedback sets toastMessage & bumps toastEventId.
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    try {
      val viewModel = KitchenBoardViewModel(
        seedCatalogPort = SeedCatalogPort { seed },
        storagePort = FakeKitchenStorage(),
        aiEdgePort = SilentExportTestAiEdgePort(),
        voiceInputPort = SilentExportTestVoicePort(),
        deleteTempImage = {},
      )
      assertEquals(0L, viewModel.uiState.value.toastEventId)

      val savedMsg = "Saved PDF: SavorAI_Grocery_List_Our_Family_Kitchen.pdf"
      viewModel.postExportFeedback(savedMsg)
      assertEquals(savedMsg, viewModel.uiState.value.toastMessage)
      assertEquals(1L, viewModel.uiState.value.toastEventId)
      assertEquals(DEFAULT_TOAST_AUTO_DISMISS_MS, computeToastAutoDismissMillis(savedMsg))

      // Identical consecutive export confirmation still increments toastEventId (1L -> 2L).
      viewModel.postExportFeedback(savedMsg)
      assertEquals(2L, viewModel.uiState.value.toastEventId)

      // Diagnostic export failure increments toastEventId (2L -> 3L) and grants 6,000ms reading window.
      val errMsg = "Unable to save PDF: Destination output stream unavailable."
      viewModel.postExportFeedback(errMsg)
      assertEquals(errMsg, viewModel.uiState.value.toastMessage)
      assertEquals(3L, viewModel.uiState.value.toastEventId)
      assertEquals(EXTENDED_TOAST_AUTO_DISMISS_MS, computeToastAutoDismissMillis(errMsg))
    } finally {
      Dispatchers.resetMain()
    }
  }

  // Asserts multi-page A4 height budgets, Page 1 header exclusivity, and zero orphaned headers.
  private fun assertValidMultiPageA4Plan(pages: List<PlannedPdfPage>) {
    assertTrue("Expected multi-page PDF layout (>= 2 pages), got ${pages.size}", pages.size >= 2)
    assertEquals((1..pages.size).toList(), pages.map { it.pageNumber })
    val firstPageMaxBodyPt = 842f - 28f - 128f - 44f
    val continuationMaxBodyPt = 842f - 28f - 28f - 44f

    for (page in pages) {
      assertEquals(pages.size, page.totalPages)
      assertEquals(page.pageNumber == 1, page.includesDocumentHeader)
      assertTrue(page.rows.isNotEmpty())
      assertEquals(0f, page.rows.first().topSpacingPt, 0.001f)
      assertTrue(page.rows.last().kind != PdfRowKind.SECTION_HEADER)

      // Every SECTION_HEADER must be followed immediately on the same page by a LINE_ITEM.
      for (idx in page.rows.indices) {
        if (page.rows[idx].kind == PdfRowKind.SECTION_HEADER) {
          assertTrue(idx + 1 < page.rows.size && page.rows[idx + 1].kind == PdfRowKind.LINE_ITEM)
        }
      }

      // Total vertical points consumed by rows on this page must fit strictly inside A4 body margins.
      val usedBodyPt = page.rows.map { it.topSpacingPt + it.heightPt }.sum()
      val allowedPt = if (page.includesDocumentHeader) firstPageMaxBodyPt else continuationMaxBodyPt
      assertTrue("Page ${page.pageNumber} overflow: $usedBodyPt > $allowedPt", usedBodyPt <= allowedPt + 0.01f)
    }
  }

  // Resolve repository-relative paths regardless of whether JUnit runs from android/app, android/, or root.
  private fun resolveRepoFile(repoRelativePath: String): Path {
    val candidates = listOf(
      KotlinPath(repoRelativePath),
      KotlinPath("..").resolve(repoRelativePath),
      KotlinPath("../..").resolve(repoRelativePath),
    )
    return requireNotNull(candidates.firstOrNull { it.isRegularFile() }) {
      "Unable to resolve repository file: $repoRelativePath"
    }
  }

  // Hermetic no-op AiEdgePort fake keeping ViewModel export toast tests free of JNI side effects.
  private class SilentExportTestAiEdgePort : AiEdgePort {
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
  private class SilentExportTestVoicePort : VoiceInputPort {
    private val state = MutableStateFlow<VoiceInputState>(VoiceInputState.Idle)
    override val voiceState: StateFlow<VoiceInputState> = state.asStateFlow()
    override fun startListening(languageTag: String): Boolean = false
    override fun stopListening() = Unit
    override fun release() = Unit
  }
}
