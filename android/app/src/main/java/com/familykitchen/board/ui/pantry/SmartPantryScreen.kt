package com.familykitchen.board.ui.pantry

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.BaseUnit
import com.familykitchen.board.domain.BomLine
import com.familykitchen.board.domain.CookOwner
import com.familykitchen.board.domain.ExpiringPantryCardModel
import com.familykitchen.board.domain.GroceryAndCatalogEditAction
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.IngredientCategory
import com.familykitchen.board.domain.IngredientTier
import com.familykitchen.board.domain.MealSlotType
import com.familykitchen.board.domain.PantryExpiryDashboard
import com.familykitchen.board.domain.PantryExpiryEngine
import com.familykitchen.board.domain.PantryLedgerEntry
import com.familykitchen.board.domain.ProportionalStockLevel
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.StapleStatus
import com.familykitchen.board.domain.StorageLocation
import com.familykitchen.board.domain.computeRemainingShelfLifeDays
import com.familykitchen.board.domain.indexCatalogById
import com.familykitchen.board.domain.indexPantryById
import com.familykitchen.board.ui.export.KitchenExportActionRow
import com.familykitchen.board.ui.grocery.EditCatalogIngredientSheet
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme

// SavorAI Smart Pantry & Expiry Screen (`smart_pantry_expiry`) with stocked-only shelves,
// middle-dot zone chips (`All · 18`), collapsible `Out of Stock · N`, and in-place Shelf Audit mode.

/**
 * Stateless Material 3 screen implementing `smart_pantry_expiry` with stocked-only zone shelves,
 * middle-dot filter pills (`All · N`, `Fridge · N`, `Pantry · N`, `Freezer · N`), serene
 * `Your Pantry Is Ready` empty-kitchen state, in-place `Shelf Audit` proportional pills, 1-tap
 * `Mark Used` and `Move to Freezer` expiring triage, and a collapsible `Out of Stock · N` section.
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
  isExportingPdf: Boolean = false,
  onCopyPantryToClipboard: () -> Unit = {},
  onSavePantryAsPdf: () -> Unit = {},
  onEditCatalogIngredient: (GroceryAndCatalogEditAction) -> Unit = {},
  todayIso: String? = null,
  isShelfAuditModeActive: Boolean = false,
  onSetProportionalLevel: (ingredientId: String, level: ProportionalStockLevel) -> Unit = { _, _ -> },
  onMarkItemUsed: (ingredientId: String) -> Unit = {},
  onMoveItemToZone: (ingredientId: String, targetLocation: StorageLocation) -> Unit = { _, _ -> },
  onBrowseRecipes: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  // Persist filter, intake drawer, out-of-stock accordion, and rescue banner across rotations.
  var activeFilterName by rememberSaveable { mutableStateOf(PantryCategoryFilter.ALL.name) }
  val activeFilter = PantryCategoryFilter.entries.find { it.name == activeFilterName } ?: PantryCategoryFilter.ALL
  var showAddCard by rememberSaveable { mutableStateOf(false) }
  var isOutOfStockExpanded by rememberSaveable { mutableStateOf(false) }
  var isRescueBannerDismissed by rememberSaveable { mutableStateOf(false) }
  var editingIngredientId by rememberSaveable { mutableStateOf<String?>(null) }
  val isImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
  val listState = rememberLazyListState()

  // Compute PantryExpiryDashboard with todayIso whenever ViewModel omits a precomputed snapshot.
  val dashboard = remember(precomputedDashboard, pantryEntries, catalog, recipes, householdAllergens, rescuedMealsCount, todayIso) {
    precomputedDashboard ?: PantryExpiryEngine.evaluatePantryExpiryDashboard(
      pantry = pantryEntries, catalog = catalog, recipes = recipes,
      householdAllergens = householdAllergens, rescuedMealsCount = rescuedMealsCount, todayIso = todayIso,
    )
  }

  // Build O(1) lookup maps once per catalog or ledger change to keep LazyColumn composition fast.
  val catalogById = remember(catalog) { indexCatalogById(catalog) }
  val pantryById = remember(pantryEntries) { indexPantryById(pantryEntries) }
  val existingIds = remember(catalog) { catalog.map { it.ingredientId }.toSet() }

  // Enforce Empty-Fridge Mirage guard: active zone shelves render ONLY stocked entries.
  val fridgePairs = remember(dashboard.fridgeItems, catalogById) {
    dashboard.fridgeItems.filter(::pantryScreenIsStocked).mapNotNull { entry -> catalogById[entry.ingredientId]?.let { entry to it } }
  }
  // Group stocked dry-pantry entries into 2-column rows for compact shelf scannability.
  val dryPantryRows = remember(dashboard.dryPantryItems, catalogById) {
    dashboard.dryPantryItems.filter(::pantryScreenIsStocked)
      .mapNotNull { entry -> catalogById[entry.ingredientId]?.let { entry to it } }
      .chunked(2)
  }
  val dryPantryCount = remember(dryPantryRows) { dryPantryRows.sumOf { it.size } }
  // Group stocked freezer entries into 2-column rows matching dry-goods shelf geometry.
  val freezerRows = remember(dashboard.freezerItems, catalogById) {
    dashboard.freezerItems.filter(::pantryScreenIsStocked)
      .mapNotNull { entry -> catalogById[entry.ingredientId]?.let { entry to it } }
      .chunked(2)
  }
  val freezerCount = remember(freezerRows) { freezerRows.sumOf { it.size } }
  val totalStockedCount = fridgePairs.size + dryPantryCount + freezerCount

  // Collect depleted entries into a clean collapsible `Out of Stock · N` section at the bottom.
  val depletedPairs = remember(dashboard.outOfStockItems, pantryEntries, catalogById) {
    val source = when {
      dashboard.outOfStockItems.isNotEmpty() -> dashboard.outOfStockItems
      else -> pantryEntries.filterNot(::pantryScreenIsStocked)
    }
    source.mapNotNull { entry -> catalogById[entry.ingredientId]?.let { entry to it } }
  }

  // Hide the floating bottom rescue bar whenever the inline 48h Action Required panel is visible.
  val isActionPanelVisible by remember {
    derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { it.key == "smart_pantry_action_required" } }
  }
  val showStickyBanner = dashboard.expiringItems.isNotEmpty() && !isImeVisible &&
    !isRescueBannerDismissed && activeFilter != PantryCategoryFilter.EXPIRING && !isActionPanelVisible

  Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Box(modifier = Modifier.fillMaxSize()) {
      LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = if (showStickyBanner) 80.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        // 1. Top Stat Pulse Bar (Stocked, Expiring pill, and Saved Score ring).
        item(key = "smart_pantry_pulse_bar") {
          PantryStatPulseBar(
            dashboard = dashboard.copy(totalItems = totalStockedCount),
            onExpiringPillClick = { activeFilterName = PantryCategoryFilter.EXPIRING.name },
          )
        }

        // 2. 3-Column Quick Command Strip (Scan, Add Item, and Shelf Audit toggle).
        item(key = "smart_pantry_command_strip") {
          PantryQuickCommandStrip(
            onScanReceiptClick = onScanReceiptAi,
            onAddItemClick = { showAddCard = !showAddCard },
            onAiAuditClick = onRunAiAudit,
            isShelfAuditActive = isShelfAuditModeActive,
          )
        }

        // 3. Tactile 48.dp Copy Pantry and Save PDF export pills.
        item(key = "smart_pantry_export_actions") {
          KitchenExportActionRow(
            copyLabel = "Copy Pantry", pdfLabel = "Save PDF", isExportingPdf = isExportingPdf,
            onCopyClick = onCopyPantryToClipboard, onSavePdfClick = onSavePantryAsPdf,
          )
        }

        // Expandable custom/preset ingredient intake card when Add Item is toggled.
        if (showAddCard) {
          item(key = "smart_pantry_add_card") {
            AddIngredientCard(
              existingIngredientIds = existingIds, queuedGroceryIds = queuedGroceryIds,
              isExpanded = true, onToggleExpanded = { showAddCard = false },
              onAddIngredientToGrocery = onAddIngredientToGrocery,
              onAddIngredientToPantry = onAddIngredientToPantry,
              onAddIngredientToPantryWithExpiry = onAddIngredientToPantryWithExpiry,
            )
          }
        }

        // 4. 48.dp Horizontal Filter Scroller (`All · N`, `Fridge · N`, `Pantry · N`, `Freezer · N`).
        item(key = "smart_pantry_filter_scroller") {
          pantryScreenFilterRow(
            activeFilter = activeFilter, stockedTotal = totalStockedCount,
            expiringCount = dashboard.expiringItems.size, fridgeCount = fridgePairs.size,
            dryPantryCount = dryPantryCount, freezerCount = freezerCount,
            catalogSize = catalog.size, onSelectFilter = { activeFilterName = it.name },
          )
        }

        // In-Place Shelf Audit banner surfaced whenever Shelf Audit mode is active.
        if (isShelfAuditModeActive) {
          item(key = "smart_pantry_shelf_audit_banner") { pantryScreenShelfAuditBanner(onDoneAudit = onRunAiAudit) }
        }

        // Serene `Your Pantry Is Ready` empty-kitchen state when All has 0 stocked items.
        if (activeFilter == PantryCategoryFilter.ALL && totalStockedCount == 0) {
          item(key = "smart_pantry_empty_ready_card") {
            EmptyPantryReadyCard(onScanKitchenClick = onScanReceiptAi, onBrowseRecipesClick = onBrowseRecipes)
          }
        }

        // 5. Action Required within-48h panel on ALL and EXPIRING filters.
        val showExpiringPanel = (activeFilter == PantryCategoryFilter.ALL || activeFilter == PantryCategoryFilter.EXPIRING) &&
          dashboard.expiringItems.isNotEmpty()
        if (showExpiringPanel) {
          item(key = "smart_pantry_action_required") {
            ActionRequiredExpiryPanel(
              expiringItems = dashboard.expiringItems, atRiskValueUsd = dashboard.atRiskValueUsd,
              onFindRecipes = onFindRescueRecipes, onPairIngredients = onPairExpiringIngredients,
              currencySymbol = currencySymbol, onMarkItemUsed = onMarkItemUsed, onMoveItemToZone = onMoveItemToZone,
            )
          }
        }
        if (activeFilter == PantryCategoryFilter.EXPIRING && dashboard.expiringItems.isEmpty()) {
          item(key = "smart_pantry_empty_expiring") {
            pantryScreenCalmEmptyZoneNotice("Zero items expiring within 48 hours")
          }
        }

        // 6. Lazy Fridge & Produce stocked cards.
        val showFridge = (activeFilter == PantryCategoryFilter.ALL || activeFilter == PantryCategoryFilter.FRIDGE_FRESH) &&
          fridgePairs.isNotEmpty()
        if (showFridge) {
          item(key = "smart_pantry_fridge_header") {
            PantryZoneSectionHeader(icon = Icons.Filled.AcUnit, title = "Fridge & Produce", itemCount = fridgePairs.size)
          }
          items(items = fridgePairs, key = { (entry, _) -> "fridge_${entry.ingredientId}" }) { (entry, item) ->
            FridgeProduceInteractiveRow(
              catalogItem = item, entry = entry,
              isQueuedOnGrocery = item.ingredientId in queuedGroceryIds,
              onAdjustQuantity = { delta -> onAdjustPantryQuantity(item.ingredientId, delta) },
              onToggleLocallyAvailable = { next -> onToggleLocalAvailability(item.ingredientId, next) },
              onToggleManualQueue = { onToggleManualGroceryQueue(item.ingredientId) },
              onEditIngredient = { editingIngredientId = item.ingredientId },
              todayIso = todayIso, isShelfAuditModeActive = isShelfAuditModeActive,
              onSetProportionalLevel = { level -> onSetProportionalLevel(item.ingredientId, level) },
            )
          }
        }
        if (activeFilter == PantryCategoryFilter.FRIDGE_FRESH && fridgePairs.isEmpty()) {
          item(key = "smart_pantry_empty_fridge") {
            pantryScreenCalmEmptyZoneNotice("No stocked items in Fridge right now")
          }
        }

        // 7. Lazy 2-column Dry Goods & Pantry stocked rows.
        val showDryPantry = (activeFilter == PantryCategoryFilter.ALL || activeFilter == PantryCategoryFilter.STAPLES_OILS) &&
          dryPantryRows.isNotEmpty()
        if (showDryPantry) {
          item(key = "smart_pantry_dry_header") {
            PantryZoneSectionHeader(icon = Icons.Filled.Kitchen, title = "Dry Goods & Pantry", itemCount = dryPantryCount)
          }
          items(items = dryPantryRows, key = { pair -> "dry_${pair.first().first.ingredientId}" }) { pair ->
            DryGoodsGridPairRow(
              pair = pair, onSelectStapleStatus = onSetStapleStatus, onAdjustQuantity = onAdjustPantryQuantity,
              onEditIngredientById = { id -> editingIngredientId = id },
              todayIso = todayIso, isShelfAuditModeActive = isShelfAuditModeActive,
              onSetProportionalLevel = onSetProportionalLevel,
            )
          }
        }
        if (activeFilter == PantryCategoryFilter.STAPLES_OILS && dryPantryRows.isEmpty()) {
          item(key = "smart_pantry_empty_dry") {
            pantryScreenCalmEmptyZoneNotice("No stocked items in Pantry right now")
          }
        }

        // 8. Lazy 2-column Freezer stocked rows.
        val showFreezer = (activeFilter == PantryCategoryFilter.ALL || activeFilter == PantryCategoryFilter.FREEZER) &&
          freezerRows.isNotEmpty()
        if (showFreezer) {
          item(key = "smart_pantry_freezer_header") {
            PantryZoneSectionHeader(icon = Icons.Filled.AcUnit, title = "Freezer", itemCount = freezerCount)
          }
          items(items = freezerRows, key = { pair -> "freezer_${pair.first().first.ingredientId}" }) { pair ->
            DryGoodsGridPairRow(
              pair = pair, onSelectStapleStatus = onSetStapleStatus, onAdjustQuantity = onAdjustPantryQuantity,
              onEditIngredientById = { id -> editingIngredientId = id },
              todayIso = todayIso, isShelfAuditModeActive = isShelfAuditModeActive,
              onSetProportionalLevel = onSetProportionalLevel,
            )
          }
        }
        if (activeFilter == PantryCategoryFilter.FREEZER && freezerRows.isEmpty()) {
          item(key = "smart_pantry_empty_freezer") {
            pantryScreenCalmEmptyZoneNotice("No stocked items in Freezer right now")
          }
        }

        // 9. Local Market inspection cards when LOCAL_MARKET filter is selected.
        if (activeFilter == PantryCategoryFilter.LOCAL_MARKET) {
          val localMarketItems = catalog.filter {
            !it.isLocallyAvailable || it.category == IngredientCategory.PRODUCE || it.category == IngredientCategory.PROTEIN
          }.distinctBy { it.ingredientId }
          items(items = localMarketItems, key = { "local_${it.ingredientId}" }) { item ->
            PantryLedgerItemCard(
              catalogItem = item, pantryEntry = pantryById[item.ingredientId],
              isQueuedOnGrocery = item.ingredientId in queuedGroceryIds,
              onAdjustQuantity = { delta -> onAdjustPantryQuantity(item.ingredientId, delta) },
              onSelectStapleStatus = { status -> onSetStapleStatus(item.ingredientId, status) },
              onToggleLocallyAvailable = { next -> onToggleLocalAvailability(item.ingredientId, next) },
              onToggleManualQueue = { onToggleManualGroceryQueue(item.ingredientId) },
              onEditIngredient = { editingIngredientId = item.ingredientId },
              todayIso = todayIso, isShelfAuditModeActive = isShelfAuditModeActive,
              onSetProportionalLevel = { level -> onSetProportionalLevel(item.ingredientId, level) },
            )
          }
        }

        // 10. Collapsible `Out of Stock · N` section at bottom of SmartPantryScreen.
        val showOutOfStockSection = depletedPairs.isNotEmpty() &&
          activeFilter != PantryCategoryFilter.EXPIRING && activeFilter != PantryCategoryFilter.LOCAL_MARKET
        if (showOutOfStockSection) {
          item(key = "smart_pantry_out_of_stock_section") {
            pantryScreenOutOfStockSection(
              depletedPairs = depletedPairs, queuedGroceryIds = queuedGroceryIds,
              isExpanded = isOutOfStockExpanded,
              onToggleExpanded = { isOutOfStockExpanded = !isOutOfStockExpanded },
              onQueueInShop = onToggleManualGroceryQueue,
              onStockOnePack = { item ->
                // Restore 1 full package for Tier-1 perishables or PLENTY status for Tier-2 staples.
                when (item.tier) {
                  IngredientTier.TIER_2_STAPLE -> onSetStapleStatus(item.ingredientId, StapleStatus.PLENTY)
                  IngredientTier.TIER_1_QUANTITATIVE ->
                    onAdjustPantryQuantity(item.ingredientId, item.defaultPackageQuantity.coerceAtLeast(1.0))
                }
              },
            )
          }
        }
      }

      // Floating bottom rescue bar surfaces urgent value at risk when scrolled past the top panel.
      if (showStickyBanner) {
        StickyWastePreventionBanner(
          expiringCount = dashboard.expiringItems.size, atRiskValueUsd = dashboard.atRiskValueUsd,
          rescueRecipes = dashboard.rescueRecipes,
          onViewRescueClick = { onViewTonightRescuePlan(dashboard.rescueRecipes) },
          currencySymbol = currencySymbol, onDismiss = { isRescueBannerDismissed = true },
          modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 10.dp),
        )
      }

      // Modal catalog editor sheet with StorageLocation and shelf-life days editing.
      val activeEditingIngredient = editingIngredientId?.let { id -> catalogById[id] }
      if (activeEditingIngredient != null) {
        val activeEntry = pantryById[activeEditingIngredient.ingredientId]
        val currentZone = activeEntry?.storageLocation ?: activeEditingIngredient.defaultStorageLocation
        val currentDays = activeEntry?.let { computeRemainingShelfLifeDays(it, todayIso) }
          ?: activeEditingIngredient.defaultShelfLifeDays
        EditCatalogIngredientSheet(
          catalogItem = activeEditingIngredient, currencySymbol = currencySymbol,
          onDispatchEditAction = onEditCatalogIngredient, onDismiss = { editingIngredientId = null },
          currentStorageLocation = currentZone, currentShelfLifeDays = currentDays,
          onMoveItemToZone = onMoveItemToZone,
          onUpdateShelfLifeAndZone = { updatedItem, targetZone, targetDays ->
            onAddIngredientToPantryWithExpiry?.invoke(updatedItem, targetZone, targetDays)
          },
        )
      }
    }
  }
}

// Check whether a ledger entry has usable positive home stock (`!isOutOfStock` and `> 0.0` or non-OUT Tier-2).
private fun pantryScreenIsStocked(entry: PantryLedgerEntry): Boolean =
  !entry.isOutOfStock && when (entry.tier) {
    IngredientTier.TIER_2_STAPLE -> entry.stapleStatus != StapleStatus.OUT
    IngredientTier.TIER_1_QUANTITATIVE -> entry.currentQuantity > 0.0
  }

// Render 48.dp horizontal filter pills formatted with punctuation-free middle-dot counts.
@Composable
private fun pantryScreenFilterRow(
  activeFilter: PantryCategoryFilter,
  stockedTotal: Int,
  expiringCount: Int,
  fridgeCount: Int,
  dryPantryCount: Int,
  freezerCount: Int,
  catalogSize: Int,
  onSelectFilter: (PantryCategoryFilter) -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Horizontal pill scroller using middle-dot counts (`All · 18`, `Fridge · 10`, `Freezer · 2`).
  Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    PantryCategoryFilter.entries.forEach { filter ->
      val count = when (filter) {
        PantryCategoryFilter.ALL -> stockedTotal
        PantryCategoryFilter.EXPIRING -> expiringCount
        PantryCategoryFilter.FRIDGE_FRESH -> fridgeCount
        PantryCategoryFilter.STAPLES_OILS -> dryPantryCount
        PantryCategoryFilter.FREEZER -> freezerCount
        PantryCategoryFilter.LOCAL_MARKET -> catalogSize
      }
      val isSelected = activeFilter == filter
      Surface(
        shape = CircleShape,
        color = if (isSelected) extendedColors.herbalSage else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = if (isSelected) null else BorderStroke(1.dp, extendedColors.borderSubtle),
        shadowElevation = 0.dp,
        modifier = Modifier.defaultMinSize(minHeight = 48.dp).clip(CircleShape).clickable { onSelectFilter(filter) },
      ) {
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
          Text(
            text = "${filter.displayLabel} · $count",
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1, softWrap = false,
          )
        }
      }
    }
  }
}

// Calm sage instruction banner displayed while in-place Shelf Audit mode is active.
@Composable
private fun pantryScreenShelfAuditBanner(onDoneAudit: () -> Unit) {
  val extendedColors = KitchenTheme.extendedColors
  Surface(
    shape = KitchenTheme.shapes.card2xl, color = extendedColors.sageContainer,
    border = BorderStroke(1.dp, extendedColors.herbalSage), modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.CheckCircleOutline, contentDescription = null, tint = extendedColors.onSageContainer, modifier = Modifier.size(18.dp))
        Text(
          text = "Quick Shelf Check · Tap a level to update stock",
          style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
          color = extendedColors.onSageContainer,
        )
      }
      Spacer(modifier = Modifier.width(8.dp))
      // 48.dp Done pill exits in-place Shelf Audit mode in one tap.
      Surface(
        shape = CircleShape, color = extendedColors.herbalSage,
        modifier = Modifier.defaultMinSize(minHeight = 48.dp).clip(CircleShape).clickable(onClick = onDoneAudit),
      ) {
        Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
          Text("Done", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, softWrap = false)
        }
      }
    }
  }
}

// Quiet 1-line zone notice rendered when a specific storage zone currently has zero stocked items.
@Composable
private fun pantryScreenCalmEmptyZoneNotice(message: String) {
  val extendedColors = KitchenTheme.extendedColors
  Surface(
    shape = KitchenTheme.shapes.card2xl, color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, extendedColors.borderSubtle), modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(Icons.Filled.Inventory2, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
      Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

// Collapsible bottom drawer isolating depleted `0g` / `OUT` entries away from active shelves.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun pantryScreenOutOfStockSection(
  depletedPairs: List<Pair<PantryLedgerEntry, IngredientCatalogItem>>,
  queuedGroceryIds: Set<String>,
  isExpanded: Boolean,
  onToggleExpanded: () -> Unit,
  onQueueInShop: (String) -> Unit,
  onStockOnePack: (IngredientCatalogItem) -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  Card(
    modifier = Modifier.fillMaxWidth(), shape = KitchenTheme.shapes.card2xl,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      // 48.dp accordion header displaying `Out of Stock · N` without parentheses.
      Row(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clip(RoundedCornerShape(12.dp))
          .clickable(onClick = onToggleExpanded).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Icon(Icons.Filled.Inventory2, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
          Text(
            text = "Out of Stock · ${depletedPairs.size}",
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
          )
        }
        Icon(
          imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
          contentDescription = if (isExpanded) "Collapse out of stock items" else "Expand out of stock items",
        )
      }

      // Expanded list offers 1-tap `Queue in Shop` and `+ Stock Pack` actions per depleted item.
      AnimatedVisibility(visible = isExpanded) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          depletedPairs.forEach { (_, item) ->
            val isQueued = item.ingredientId in queuedGroceryIds
            Surface(
              shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest,
              border = BorderStroke(1.dp, extendedColors.borderSubtle), modifier = Modifier.fillMaxWidth(),
            ) {
              Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                  Text(
                    text = item.displayName, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                  Text("Out of stock", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, softWrap = false)
                }
                FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                  OutlinedButton(
                    onClick = { onQueueInShop(item.ingredientId) }, shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                  ) {
                    Icon(if (isQueued) Icons.Filled.Check else Icons.Filled.ShoppingCart, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(if (isQueued) "Queued in Shop" else "Queue in Shop", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                  }
                  OutlinedButton(
                    onClick = { onStockOnePack(item) }, shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                  ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("+ Stock Pack", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 920)
@Composable
private fun SmartPantryExpiryScreenPreview() {
  // Verifies stocked shelves, Shelf Audit mode banner, and collapsible Out of Stock drawer.
  val catalog = listOf(
    IngredientCatalogItem("ing_salmon", "Atlantic Salmon Fillets", IngredientCategory.PROTEIN, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 800.0, defaultStorageLocation = StorageLocation.FRIDGE, defaultShelfLifeDays = 3, estimatedPackagePriceUsd = 1120.0),
    IngredientCatalogItem("ing_spinach", "Fresh Baby Spinach", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 300.0, substituteGroupIds = listOf("LEAFY_GREENS"), defaultStorageLocation = StorageLocation.FRIDGE, defaultShelfLifeDays = 5, estimatedPackagePriceUsd = 85.0),
    IngredientCatalogItem("ing_greek_yogurt", "Plain Greek Yogurt", IngredientCategory.DAIRY_EGGS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 1000.0, defaultStorageLocation = StorageLocation.FRIDGE, defaultShelfLifeDays = 10, estimatedPackagePriceUsd = 395.0),
    IngredientCatalogItem("ing_baguette", "Crusty Artisan Baguette", IngredientCategory.BAKERY_GRAINS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.COUNT, 2.0, defaultStorageLocation = StorageLocation.PANTRY, defaultShelfLifeDays = 4, estimatedPackagePriceUsd = 140.0),
    IngredientCatalogItem("ing_oats", "Rolled Whole Grain Oats", IngredientCategory.BAKERY_GRAINS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 1000.0, defaultStorageLocation = StorageLocation.PANTRY, defaultShelfLifeDays = 365, estimatedPackagePriceUsd = 180.0),
    IngredientCatalogItem("ing_olive_oil", "Extra-Virgin Olive Oil", IngredientCategory.PANTRY_STAPLE, IngredientTier.TIER_2_STAPLE, BaseUnit.MILLILITER, 750.0, defaultStorageLocation = StorageLocation.PANTRY, defaultShelfLifeDays = 365, estimatedPackagePriceUsd = 695.0),
    IngredientCatalogItem("ing_chicken", "Free-Range Chicken Thighs", IngredientCategory.PROTEIN, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 1000.0, defaultStorageLocation = StorageLocation.FRIDGE, defaultShelfLifeDays = 2, estimatedPackagePriceUsd = 230.0),
  )
  val pantry = listOf(
    PantryLedgerEntry("ing_salmon", IngredientTier.TIER_1_QUANTITATIVE, 400.0, storageLocation = StorageLocation.FRIDGE, storageSubLocation = "Chiller Drawer", expiresInDays = 1, purchasedDaysAgo = 1),
    PantryLedgerEntry("ing_spinach", IngredientTier.TIER_1_QUANTITATIVE, 150.0, storageLocation = StorageLocation.FRIDGE, storageSubLocation = "Crisper Bin", expiresInDays = 2),
    PantryLedgerEntry("ing_greek_yogurt", IngredientTier.TIER_1_QUANTITATIVE, 500.0, storageLocation = StorageLocation.FRIDGE, storageSubLocation = "Top Shelf", expiresInDays = 2),
    PantryLedgerEntry("ing_baguette", IngredientTier.TIER_1_QUANTITATIVE, 4.0, storageLocation = StorageLocation.PANTRY, storageSubLocation = "Linen Bread Bag", packageNote = "4 loaves • Marikina Artisan Bakery"),
    PantryLedgerEntry("ing_oats", IngredientTier.TIER_1_QUANTITATIVE, 2000.0, storageLocation = StorageLocation.PANTRY, storageSubLocation = "Airtight Canister #1", packageNote = "2 kg • Bulk Jar"),
    PantryLedgerEntry("ing_olive_oil", IngredientTier.TIER_2_STAPLE, 250.0, StapleStatus.LOW, storageLocation = StorageLocation.PANTRY, storageSubLocation = "Counter"),
    PantryLedgerEntry("ing_chicken", IngredientTier.TIER_1_QUANTITATIVE, 0.0, StapleStatus.OUT, isOutOfStock = true, storageLocation = StorageLocation.FRIDGE),
  )
  val rescueRecipe = Recipe(
    "rec_tuscan_salmon", "Pan-Seared Salmon with Garlic Lemon Spinach", "Mediterranean Bistro",
    listOf(MealSlotType.DINNER), CookOwner.WIFE, 4, 10, 0, 15, 25,
    toddlerPrepTip = "Flake boneless salmon finely for 2yo.",
    bomLines = listOf(BomLine("ing_salmon", 400), BomLine("ing_spinach", 150)),
  )
  FamilyKitchenBoardTheme {
    SmartPantryScreen(
      catalog = catalog, pantryEntries = pantry, recipes = listOf(rescueRecipe),
      onAdjustPantryQuantity = { _, _ -> }, onSetStapleStatus = { _, _ -> },
      onToggleLocalAvailability = { _, _ -> }, onToggleManualGroceryQueue = {},
      onAddIngredientToGrocery = {}, onAddIngredientToPantry = {}, isShelfAuditModeActive = true,
    )
  }
}

@Preview(name = "Smart Pantry — Empty Kitchen Ready", showBackground = true, widthDp = 360)
@Composable
private fun SmartPantryEmptyKitchenPreview() {
  // Verifies serene `Your Pantry Is Ready` empty-kitchen state in Counter Dark theme.
  val catalog = listOf(
    IngredientCatalogItem("ing_egg", "Farm Eggs", IngredientCategory.DAIRY_EGGS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.COUNT, 12.0),
    IngredientCatalogItem("ing_milk", "Whole Fresh Milk", IngredientCategory.DAIRY_EGGS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.MILLILITER, 1000.0),
  )
  val emptyPantry = catalog.map { item ->
    PantryLedgerEntry(item.ingredientId, item.tier, 0.0, StapleStatus.OUT, isOutOfStock = true)
  }
  FamilyKitchenBoardTheme(darkTheme = true) {
    SmartPantryScreen(
      catalog = catalog, pantryEntries = emptyPantry,
      onAdjustPantryQuantity = { _, _ -> }, onSetStapleStatus = { _, _ -> },
      onToggleLocalAvailability = { _, _ -> }, onToggleManualGroceryQueue = {},
      onAddIngredientToGrocery = {}, onAddIngredientToPantry = {},
    )
  }
}
