package com.familykitchen.board.ui.pantry

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.BaseUnit
import com.familykitchen.board.domain.BomLine
import com.familykitchen.board.domain.CookOwner
import com.familykitchen.board.domain.ExpiringPantryCardModel
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.IngredientCategory
import com.familykitchen.board.domain.IngredientTier
import com.familykitchen.board.domain.MealSlotType
import com.familykitchen.board.domain.PantryExpiryDashboard
import com.familykitchen.board.domain.PantryExpiryEngine
import com.familykitchen.board.domain.PantryLedgerEntry
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.StapleStatus
import com.familykitchen.board.domain.StorageLocation
import com.familykitchen.board.domain.indexCatalogById
import com.familykitchen.board.domain.indexPantryById
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme

// SavorAI Smart Pantry & Expiry Screen (`smart_pantry_expiry`) powered by PantryExpiryEngine
// with true lazy zone rendering and a slim 52.dp dismissible rescue bar.

/**
 * Stateless Material 3 screen implementing `smart_pantry_expiry` with editorial minimalism and
 * Galaxy S22 `1.35x` font-scale resilience: Top Stat Pulse Bar, 3-Column Quick Command Strip,
 * `48.dp` Zone Filter Scroller, `Action Required (Within 48h)` rescue cards, lazy `Fridge & Produce`
 * cards, lazy 2-column `Dry Goods & Pantry` + `Freezer` rows, and slim `52.dp` dismissible rescue bar.
 *
 * @param catalog Household ingredient catalog records.
 * @param pantryEntries Current two-tier household pantry ledger entries.
 * @param recipes Household recipe library used to match zero-waste rescue meals.
 * @param householdAllergens Active household allergen exclusions (`EC-02`).
 * @param rescuedMealsCount Historical rescued expiring meals counter (`96%`+ baseline).
 * @param precomputedDashboard Optional pre-evaluated [PantryExpiryDashboard] from `ViewModel`.
 * @param queuedGroceryIds Set of `ingredientId`s currently queued on the Smart Grocery List.
 * @param onAdjustPantryQuantity Applies a Fridge Pulse stepper delta (`±1 count`, `±100 g/ml`).
 * @param onSetStapleStatus Updates Tier-2 `PLENTY | LOW | OUT` status (`EC-11`).
 * @param onToggleLocalAvailability Flips `isLocallyAvailable` (`EC-02` hard-hide / `EC-03` swap).
 * @param onToggleManualGroceryQueue Toggles `+ Need to Buy` manual queueing.
 * @param onAddIngredientToGrocery Adds a preset or custom item to catalog and queues on Grocery.
 * @param onAddIngredientToPantry Adds a preset or custom item to catalog and stocks 1 package.
 * @param onAddIngredientToPantryWithExpiry Optional zone- and expiry-aware Pantry intake callback.
 * @param onScanReceiptAi Navigates to Sous-Chef camera scan (`TakePicture` / `PickVisualMedia`).
 * @param onRunAiAudit Launches Sous-Chef zero-waste pantry expiry audit prompt.
 * @param onFindRescueRecipes Opens rescue recipes for a tapped expiring item.
 * @param onPairExpiringIngredients Launches Sous-Chef pairing prompt for two co-expiring items.
 * @param onViewTonightRescuePlan Opens tonight's zero-waste rescue recipes from the rescue bar.
 * @param currencySymbol Active household currency symbol (`"₱"` default).
 * @param modifier Optional Compose [Modifier].
 */
@Composable
fun SmartPantryScreen(
  catalog: List<IngredientCatalogItem>,
  pantryEntries: List<PantryLedgerEntry>,
  recipes: List<Recipe> = emptyList(),
  householdAllergens: List<String> = emptyList(),
  rescuedMealsCount: Int = 12,
  precomputedDashboard: PantryExpiryDashboard? = null,
  queuedGroceryIds: Set<String> = emptySet(),
  onAdjustPantryQuantity: (ingredientId: String, deltaQuantity: Double) -> Unit,
  onSetStapleStatus: (ingredientId: String, newStatus: StapleStatus) -> Unit,
  onToggleLocalAvailability: (ingredientId: String, isLocallyAvailable: Boolean) -> Unit,
  onToggleManualGroceryQueue: (ingredientId: String) -> Unit,
  onAddIngredientToGrocery: (IngredientCatalogItem) -> Unit,
  onAddIngredientToPantry: (IngredientCatalogItem) -> Unit,
  onAddIngredientToPantryWithExpiry: ((IngredientCatalogItem, StorageLocation, Int) -> Unit)? = null,
  onScanReceiptAi: () -> Unit = {},
  onRunAiAudit: () -> Unit = {},
  onFindRescueRecipes: (ExpiringPantryCardModel) -> Unit = {},
  onPairExpiringIngredients: (IngredientCatalogItem, IngredientCatalogItem) -> Unit = { _, _ -> },
  onViewTonightRescuePlan: (List<Recipe>) -> Unit = {},
  currencySymbol: String = "₱",
  modifier: Modifier = Modifier,
) {
  // Persist filter enum name and dismiss state as Bundle-safe primitives across recreation.
  var activeFilterName by rememberSaveable { mutableStateOf(PantryCategoryFilter.ALL.name) }
  val activeFilter = PantryCategoryFilter.entries.find { it.name == activeFilterName }
    ?: PantryCategoryFilter.ALL
  var showAddCard by rememberSaveable { mutableStateOf(false) }
  var isRescueBannerDismissed by rememberSaveable { mutableStateOf(false) }
  val isImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
  val listState = rememberLazyListState()

  // Compute PantryExpiryDashboard deterministically whenever ViewModel omits a precomputed snapshot.
  val dashboard = remember(precomputedDashboard, pantryEntries, catalog, recipes, householdAllergens, rescuedMealsCount) {
    precomputedDashboard ?: PantryExpiryEngine.evaluatePantryExpiryDashboard(
      pantry = pantryEntries,
      catalog = catalog,
      recipes = recipes,
      householdAllergens = householdAllergens,
      rescuedMealsCount = rescuedMealsCount,
    )
  }

  // Index catalog and pantry maps once per snapshot change for O(1) lookup inside LazyColumn rows.
  val catalogById = remember(catalog) { indexCatalogById(catalog) }
  val pantryById = remember(pantryEntries) { indexPantryById(pantryEntries) }
  // Track known catalog IDs so AddIngredientCard highlights stocked items vs new entries.
  val existingIds = remember(catalog) { catalog.map { it.ingredientId }.toSet() }

  // Pre-resolve valid zone pairs so LazyColumn emits true individual lazy items with clean keys.
  val fridgePairs = remember(dashboard.fridgeItems, catalogById) {
    dashboard.fridgeItems.mapNotNull { entry -> catalogById[entry.ingredientId]?.let { entry to it } }
  }
  // Group Dry Goods into pairs so LazyColumn renders equal-height 2-column rows without nested scrolling.
  val dryPantryRows = remember(dashboard.dryPantryItems, catalogById) {
    dashboard.dryPantryItems
      .mapNotNull { entry -> catalogById[entry.ingredientId]?.let { entry to it } }
      .chunked(2)
  }
  // Sum paired tile counts so the Dry Goods header badge reflects exact stocked item totals.
  val dryPantryCount = remember(dryPantryRows) { dryPantryRows.sumOf { it.size } }
  // Group Freezer stock into pairs for balanced 2-column counter layout at 1.35x S22 font scale.
  val freezerRows = remember(dashboard.freezerItems, catalogById) {
    dashboard.freezerItems
      .mapNotNull { entry -> catalogById[entry.ingredientId]?.let { entry to it } }
      .chunked(2)
  }
  val freezerCount = remember(freezerRows) { freezerRows.sumOf { it.size } }

  // Auto-hide the bottom rescue bar when the Action Required panel is already visible in viewport.
  val isActionPanelVisible by remember {
    derivedStateOf {
      listState.layoutInfo.visibleItemsInfo.any { it.key == "smart_pantry_action_required" }
    }
  }
  // Suppress the rescue bar whenever the soft keyboard opens, user dismisses it, or EXPIRING is active.
  val showStickyBanner = dashboard.expiringItems.isNotEmpty() &&
    !isImeVisible &&
    !isRescueBannerDismissed &&
    activeFilter != PantryCategoryFilter.EXPIRING &&
    !isActionPanelVisible

  // Reserve 80.dp bottom contentPadding only while the slim 52.dp rescue bar is docked on screen.
  Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Box(modifier = Modifier.fillMaxSize()) {
      LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
          start = 16.dp,
          end = 16.dp,
          top = 12.dp,
          bottom = if (showStickyBanner) 80.dp else 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        // 1. Top Stat Pulse Bar (Stocked | Expiring | 96% Saved Score ring).
        item(key = "smart_pantry_pulse_bar") {
          PantryStatPulseBar(
            dashboard = dashboard,
            onExpiringPillClick = { activeFilterName = PantryCategoryFilter.EXPIRING.name },
          )
        }

        // 2. 3-Column Quick Command Strip (Scan | Add Item | Shelf Audit).
        item(key = "smart_pantry_command_strip") {
          PantryQuickCommandStrip(
            onScanReceiptClick = onScanReceiptAi,
            onAddItemClick = { showAddCard = !showAddCard },
            onAiAuditClick = onRunAiAudit,
          )
        }

        // Collapsible Add Ingredient builder with inputs above scrollable local presets.
        if (showAddCard) {
          item(key = "smart_pantry_add_card") {
            AddIngredientCard(
              existingIngredientIds = existingIds,
              queuedGroceryIds = queuedGroceryIds,
              isExpanded = true,
              onToggleExpanded = { showAddCard = false },
              onAddIngredientToGrocery = onAddIngredientToGrocery,
              onAddIngredientToPantry = onAddIngredientToPantry,
              onAddIngredientToPantryWithExpiry = onAddIngredientToPantryWithExpiry,
            )
          }
        }

        // 3. 48.dp Horizontal Filter Scroller (`All`, `Expiring`, `Fridge`, `Pantry`, `Freezer`, `Local Market`).
        item(key = "smart_pantry_filter_scroller") {
          SmartPantryFilterRow(
            activeFilter = activeFilter,
            dashboard = dashboard,
            catalogSize = catalog.size,
            onSelectFilter = { activeFilterName = it.name },
          )
        }

        // 4. Action Required (Within 48h) panel on ALL and EXPIRING filters.
        val showExpiringPanel =
          (activeFilter == PantryCategoryFilter.ALL || activeFilter == PantryCategoryFilter.EXPIRING) &&
            dashboard.expiringItems.isNotEmpty()
        if (showExpiringPanel) {
          item(key = "smart_pantry_action_required") {
            ActionRequiredExpiryPanel(
              expiringItems = dashboard.expiringItems,
              atRiskValueUsd = dashboard.atRiskValueUsd,
              onFindRecipes = onFindRescueRecipes,
              onPairIngredients = onPairExpiringIngredients,
              currencySymbol = currencySymbol,
            )
          }
        }

        // 5. Lazy Fridge & Produce cards (eliminates giant outer-card clipping on scroll).
        val showFridge =
          (activeFilter == PantryCategoryFilter.ALL || activeFilter == PantryCategoryFilter.FRIDGE_FRESH) &&
            fridgePairs.isNotEmpty()
        if (showFridge) {
          item(key = "smart_pantry_fridge_header") {
            PantryZoneSectionHeader(
              icon = Icons.Filled.AcUnit,
              title = "Fridge & Produce",
              itemCount = fridgePairs.size,
            )
          }
          items(items = fridgePairs, key = { (entry, _) -> "fridge_${entry.ingredientId}" }) { (entry, item) ->
            FridgeProduceInteractiveRow(
              catalogItem = item,
              entry = entry,
              isQueuedOnGrocery = item.ingredientId in queuedGroceryIds,
              onAdjustQuantity = { delta -> onAdjustPantryQuantity(item.ingredientId, delta) },
              onToggleLocallyAvailable = { next -> onToggleLocalAvailability(item.ingredientId, next) },
              onToggleManualQueue = { onToggleManualGroceryQueue(item.ingredientId) },
            )
          }
        }

        // 6. Lazy 2-column Dry Goods & Pantry rows.
        val showDryPantry =
          (activeFilter == PantryCategoryFilter.ALL || activeFilter == PantryCategoryFilter.STAPLES_OILS) &&
            dryPantryRows.isNotEmpty()
        if (showDryPantry) {
          item(key = "smart_pantry_dry_header") {
            PantryZoneSectionHeader(
              icon = Icons.Filled.Kitchen,
              title = "Dry Goods & Pantry",
              itemCount = dryPantryCount,
            )
          }
          items(
            items = dryPantryRows,
            key = { pair -> "dry_${pair.first().first.ingredientId}" },
          ) { pair ->
            DryGoodsGridPairRow(
              pair = pair,
              onSelectStapleStatus = onSetStapleStatus,
              onAdjustQuantity = onAdjustPantryQuantity,
            )
          }
        }

        // 7. Lazy 2-column Freezer rows.
        val showFreezer =
          (activeFilter == PantryCategoryFilter.ALL || activeFilter == PantryCategoryFilter.FREEZER) &&
            freezerRows.isNotEmpty()
        if (showFreezer) {
          item(key = "smart_pantry_freezer_header") {
            PantryZoneSectionHeader(
              icon = Icons.Filled.AcUnit,
              title = "Freezer",
              itemCount = freezerCount,
            )
          }
          items(
            items = freezerRows,
            key = { pair -> "freezer_${pair.first().first.ingredientId}" },
          ) { pair ->
            DryGoodsGridPairRow(
              pair = pair,
              onSelectStapleStatus = onSetStapleStatus,
              onAdjustQuantity = onAdjustPantryQuantity,
            )
          }
        }

        // 8. Full Local Market EC-02/EC-03 inspection cards when LOCAL_MARKET filter is selected.
        if (activeFilter == PantryCategoryFilter.LOCAL_MARKET) {
          val localMarketItems = catalog.filter {
            !it.isLocallyAvailable ||
              it.category == IngredientCategory.PRODUCE ||
              it.category == IngredientCategory.PROTEIN
          }.distinctBy { it.ingredientId }
          items(items = localMarketItems, key = { "local_${it.ingredientId}" }) { item ->
            PantryLedgerItemCard(
              catalogItem = item,
              pantryEntry = pantryById[item.ingredientId],
              isQueuedOnGrocery = item.ingredientId in queuedGroceryIds,
              onAdjustQuantity = { delta -> onAdjustPantryQuantity(item.ingredientId, delta) },
              onSelectStapleStatus = { status -> onSetStapleStatus(item.ingredientId, status) },
              onToggleLocallyAvailable = { next -> onToggleLocalAvailability(item.ingredientId, next) },
              onToggleManualQueue = { onToggleManualGroceryQueue(item.ingredientId) },
            )
          }
        }
      }

      // Slim 52.dp dismissible warm rescue bar docked cleanly above bottom navigation.
      if (showStickyBanner) {
        StickyWastePreventionBanner(
          expiringCount = dashboard.expiringItems.size,
          atRiskValueUsd = dashboard.atRiskValueUsd,
          rescueRecipes = dashboard.rescueRecipes,
          onViewRescueClick = { onViewTonightRescuePlan(dashboard.rescueRecipes) },
          currencySymbol = currencySymbol,
          onDismiss = { isRescueBannerDismissed = true },
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        )
      }
    }
  }
}

@Composable
private fun SmartPantryFilterRow(
  activeFilter: PantryCategoryFilter,
  dashboard: PantryExpiryDashboard,
  catalogSize: Int,
  onSelectFilter: (PantryCategoryFilter) -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Horizontal pill scroller keeps all 6 storage-zone filters single-line (`softWrap = false`) at 1.35x scale.
  Row(
    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    PantryCategoryFilter.entries.forEach { filter ->
      // Bind live zone item counts directly from PantryExpiryDashboard so pills never drift out of sync.
      val count = when (filter) {
        PantryCategoryFilter.ALL -> dashboard.totalItems
        PantryCategoryFilter.EXPIRING -> dashboard.expiringItems.size
        PantryCategoryFilter.FRIDGE_FRESH -> dashboard.fridgeItems.size
        PantryCategoryFilter.STAPLES_OILS -> dashboard.dryPantryItems.size
        PantryCategoryFilter.FREEZER -> dashboard.freezerItems.size
        PantryCategoryFilter.LOCAL_MARKET -> catalogSize
      }
      val isSelected = activeFilter == filter
      // Enforce >= 48.dp touch target height and 1.dp warm stone border on unselected chips.
      Surface(
        shape = CircleShape,
        color = if (isSelected) extendedColors.herbalSage else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = if (isSelected) null else BorderStroke(1.dp, extendedColors.borderSubtle),
        shadowElevation = 0.dp,
        modifier = Modifier
          .defaultMinSize(minHeight = 48.dp)
          .clip(CircleShape)
          .clickable { onSelectFilter(filter) },
      ) {
        Box(
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = "${filter.displayLabel} ($count)",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
          )
        }
      }
    }
  }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 920)
@Composable
private fun SmartPantryExpiryScreenPreview() {
  val catalog = listOf(
    IngredientCatalogItem("ing_salmon", "Atlantic Salmon Fillets", IngredientCategory.PROTEIN, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 800.0, defaultStorageLocation = StorageLocation.FRIDGE, defaultShelfLifeDays = 3, estimatedPackagePriceUsd = 1120.0),
    IngredientCatalogItem("ing_spinach", "Fresh Baby Spinach", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 300.0, substituteGroupIds = listOf("LEAFY_GREENS"), defaultStorageLocation = StorageLocation.FRIDGE, defaultShelfLifeDays = 5, estimatedPackagePriceUsd = 85.0),
    IngredientCatalogItem("ing_greek_yogurt", "Plain Greek Yogurt", IngredientCategory.DAIRY_EGGS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 1000.0, defaultStorageLocation = StorageLocation.FRIDGE, defaultShelfLifeDays = 10, estimatedPackagePriceUsd = 395.0),
    IngredientCatalogItem("ing_baguette", "Crusty Artisan Baguette", IngredientCategory.BAKERY_GRAINS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.COUNT, 2.0, defaultStorageLocation = StorageLocation.PANTRY, defaultShelfLifeDays = 4, estimatedPackagePriceUsd = 140.0),
    IngredientCatalogItem("ing_oats", "Rolled Whole Grain Oats", IngredientCategory.BAKERY_GRAINS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 1000.0, defaultStorageLocation = StorageLocation.PANTRY, defaultShelfLifeDays = 365, estimatedPackagePriceUsd = 180.0),
    IngredientCatalogItem("ing_olive_oil", "Extra-Virgin Olive Oil", IngredientCategory.PANTRY_STAPLE, IngredientTier.TIER_2_STAPLE, BaseUnit.MILLILITER, 750.0, defaultStorageLocation = StorageLocation.PANTRY, defaultShelfLifeDays = 365, estimatedPackagePriceUsd = 695.0),
  )
  val pantry = listOf(
    PantryLedgerEntry("ing_salmon", IngredientTier.TIER_1_QUANTITATIVE, 400.0, storageLocation = StorageLocation.FRIDGE, storageSubLocation = "Chiller Drawer", expiresInDays = 1, purchasedDaysAgo = 1),
    PantryLedgerEntry("ing_spinach", IngredientTier.TIER_1_QUANTITATIVE, 150.0, storageLocation = StorageLocation.FRIDGE, storageSubLocation = "Crisper Bin", expiresInDays = 2),
    PantryLedgerEntry("ing_greek_yogurt", IngredientTier.TIER_1_QUANTITATIVE, 500.0, storageLocation = StorageLocation.FRIDGE, storageSubLocation = "Top Shelf", expiresInDays = 2),
    PantryLedgerEntry("ing_baguette", IngredientTier.TIER_1_QUANTITATIVE, 4.0, storageLocation = StorageLocation.PANTRY, storageSubLocation = "Linen Bread Bag", packageNote = "4 loaves • Marikina Artisan Bakery"),
    PantryLedgerEntry("ing_oats", IngredientTier.TIER_1_QUANTITATIVE, 2000.0, storageLocation = StorageLocation.PANTRY, storageSubLocation = "Airtight Canister #1", packageNote = "2 kg • Bulk Jar"),
    PantryLedgerEntry("ing_olive_oil", IngredientTier.TIER_2_STAPLE, 250.0, StapleStatus.LOW, storageLocation = StorageLocation.PANTRY, storageSubLocation = "Counter"),
  )
  val rescueRecipe = Recipe(
    recipeId = "rec_tuscan_salmon",
    title = "Pan-Seared Salmon with Garlic Lemon Spinach",
    sourceAttribution = "Mediterranean Bistro",
    allowedSlots = listOf(MealSlotType.DINNER),
    preferredOwner = CookOwner.WIFE,
    rawPrepMinutes = 10,
    cookMinutes = 15,
    calibratedActiveMinutes = 25,
    toddlerPrepTip = "Flake boneless salmon finely for 2yo.",
    bomLines = listOf(BomLine("ing_salmon", 400), BomLine("ing_spinach", 150)),
  )
  FamilyKitchenBoardTheme {
    SmartPantryScreen(
      catalog = catalog,
      pantryEntries = pantry,
      recipes = listOf(rescueRecipe),
      onAdjustPantryQuantity = { _, _ -> },
      onSetStapleStatus = { _, _ -> },
      onToggleLocalAvailability = { _, _ -> },
      onToggleManualGroceryQueue = {},
      onAddIngredientToGrocery = {},
      onAddIngredientToPantry = {},
    )
  }
}
