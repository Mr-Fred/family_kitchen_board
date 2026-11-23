package com.familykitchen.board.ui.grocery

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.BaseUnit
import com.familykitchen.board.domain.CustomGroceryItem
import com.familykitchen.board.domain.GroceryAndCatalogEditAction
import com.familykitchen.board.domain.GroceryListItem
import com.familykitchen.board.domain.GrocerySortMode
import com.familykitchen.board.domain.HouseholdCurrency
import com.familykitchen.board.domain.HouseholdProfile
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.IngredientCategory
import com.familykitchen.board.domain.IngredientTier
import com.familykitchen.board.domain.MarketVenue
import com.familykitchen.board.domain.RestockDelta
import com.familykitchen.board.domain.SkippedPantryItemSummary
import com.familykitchen.board.domain.StapleStatus
import com.familykitchen.board.domain.WeeklyGroceryDiff
import com.familykitchen.board.ui.export.KitchenExportActionRow
import com.familykitchen.board.ui.pantry.AddIngredientCard
import com.familykitchen.board.ui.theme.ExtendedKitchenColors
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme

// SavorAI Smart Grocery List Screen (`smart_grocery_list`) with `All · Palengke · Supermarket`
// venue pills, expandable skipped-staples drawer (`Ran Low`), `Uncheck · N`, and `Restock · N`.

// Canonical supermarket aisle traversal order keeping produce, dairy, meat, bakery, and pantry tidy.
private data class GroceryAisleSpec(val category: IngredientCategory, val headerTitle: String)

private val GROCERY_SUPERMARKET_AISLES: List<GroceryAisleSpec> = listOf(
  GroceryAisleSpec(IngredientCategory.PRODUCE, "Aisle 1: Produce & Herbs"),
  GroceryAisleSpec(IngredientCategory.DAIRY_EGGS, "Aisle 2: Dairy & Refrigerated"),
  GroceryAisleSpec(IngredientCategory.PROTEIN, "Aisle 4: Meat & Seafood"),
  GroceryAisleSpec(IngredientCategory.BAKERY_GRAINS, "Aisle 5: Bakery & Grains"),
  GroceryAisleSpec(IngredientCategory.PANTRY_STAPLE, "Aisle 7: Pantry & Baking"),
)

/**
 * Stateless Material 3 screen implementing `smart_grocery_list` with `All · Palengke · Supermarket`
 * venue filtering (adapting `"Palengke"` to `"Wet Market"` outside `PHP`), expandable pre-shop
 * skipped-staples verification (`Ran Low`), `Uncheck · N`, and `Restock · N` (`onOpenUnpackSheet`
 * falling back to `onRestockCheckedItems`).
 */
@Composable
fun SmartGroceryScreen(
  groceryDiff: WeeklyGroceryDiff,
  checkedGroceryItemIds: Set<String> = emptySet(),
  customGroceryItems: List<CustomGroceryItem> = emptyList(),
  sortMode: GrocerySortMode = GrocerySortMode.AISLE_SORTED,
  householdProfile: HouseholdProfile = HouseholdProfile(),
  batchWriteCallCount: Int = 0,
  tonightMealSummary: String = "Tonight: Family One-Pot Dinner",
  showPlanSwitcher: Boolean = false,
  showVaultDebugPill: Boolean = false,
  existingCatalogIds: Set<String> = emptySet(),
  onToggleItemChecked: (itemId: String) -> Unit,
  onRestockCheckedItems: (List<RestockDelta>) -> Unit,
  onSelectSortMode: (GrocerySortMode) -> Unit = {},
  onAddCustomGroceryItem: (CustomGroceryItem) -> Unit = {},
  onAddCatalogItemToGrocery: (IngredientCatalogItem) -> Unit = {},
  onAddCatalogItemToPantry: (IngredientCatalogItem) -> Unit = {},
  onRemoveManualQueue: (ingredientId: String) -> Unit = {},
  onClearCheckedItems: () -> Unit = {},
  onStartVoiceDictation: () -> Unit = {},
  onOpenWeeklyMealPlan: () -> Unit = {},
  onOpenQaLab: () -> Unit = {},
  currencySymbol: String = householdProfile.currency.symbol,
  marketRegionLabel: String = householdProfile.marketRegionLabel,
  isExportingPdf: Boolean = false,
  onCopyListToClipboard: () -> Unit = {},
  onSaveListAsPdf: () -> Unit = {},
  catalogById: Map<String, IngredientCatalogItem> = emptyMap(),
  dismissedItemCount: Int = 0,
  onEditGroceryOrCatalog: (GroceryAndCatalogEditAction) -> Unit = {},
  activeVenueFilter: MarketVenue? = null,
  onSelectVenueFilter: (MarketVenue?) -> Unit = {},
  onRequeueSkippedStaple: (ingredientId: String) -> Unit = {},
  onOpenUnpackSheet: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  val isImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
  // Persist quick-add drawer, Shop Mode focus, checked accordion, and active edit targets.
  var showAddDrawer by rememberSaveable { mutableStateOf(false) }
  var quickCustomInput by rememberSaveable { mutableStateOf("") }
  var checkedAccordionOpen by rememberSaveable { mutableStateOf(true) }
  var inStoreFocusMode by rememberSaveable { mutableStateOf(false) }
  var editingCatalogItemId by rememberSaveable { mutableStateOf<String?>(null) }
  var editingCustomItemId by rememberSaveable { mutableStateOf<String?>(null) }
  // Sync venue filter across both controlled ViewModel state and standalone @Preview taps.
  var localVenueName by rememberSaveable { mutableStateOf(activeVenueFilter?.name) }
  LaunchedEffect(activeVenueFilter) { localVenueName = activeVenueFilter?.name }
  val effectiveVenue = activeVenueFilter ?: MarketVenue.entries.find { it.name == localVenueName }

  // Combine persisted checked IDs with any pre-checked GroceryListItem flags.
  val effectiveCheckedIds = remember(groceryDiff.groceryItems, checkedGroceryItemIds) {
    checkedGroceryItemIds + groceryDiff.groceryItems.filter { it.isChecked }.map { it.ingredientId }
  }

  // Compute venue counts across all unchecked items so `All · Palengke · Supermarket` badges stay live.
  val allUncheckedCatalog = remember(groceryDiff.groceryItems, effectiveCheckedIds) {
    groceryDiff.groceryItems.filterNot { it.ingredientId in effectiveCheckedIds }
  }
  val allUncheckedCustom = remember(customGroceryItems) { customGroceryItems.filterNot { it.isChecked } }
  val wetMarketCount = remember(allUncheckedCatalog, allUncheckedCustom) {
    allUncheckedCatalog.count { it.marketVenue == MarketVenue.WET_MARKET } +
      allUncheckedCustom.count { groceryScreenCustomVenue(it) == MarketVenue.WET_MARKET }
  }
  val supermarketCount = remember(allUncheckedCatalog, allUncheckedCustom) {
    allUncheckedCatalog.count { it.marketVenue == MarketVenue.SUPERMARKET } +
      allUncheckedCustom.count { groceryScreenCustomVenue(it) == MarketVenue.SUPERMARKET }
  }
  val allActiveCount = allUncheckedCatalog.size + allUncheckedCustom.size

  // Filter active items by `All · Palengke · Supermarket` before applying sortMode.
  val uncheckedCatalogItems = remember(allUncheckedCatalog, effectiveVenue, sortMode) {
    val venueFiltered = when (effectiveVenue) {
      null -> allUncheckedCatalog
      else -> allUncheckedCatalog.filter { it.marketVenue == effectiveVenue }
    }
    groceryScreenOrderItems(items = venueFiltered, sortMode = sortMode)
  }
  val uncheckedCustomItems = remember(allUncheckedCustom, effectiveVenue) {
    when (effectiveVenue) {
      null -> allUncheckedCustom
      else -> allUncheckedCustom.filter { groceryScreenCustomVenue(it) == effectiveVenue }
    }
  }
  // Keep checked items grouped inside the bottom Checked Off tray ready for UnpackGroceriesSheet.
  val checkedCatalogItems = remember(groceryDiff.groceryItems, effectiveCheckedIds) {
    groceryDiff.groceryItems.filter { it.ingredientId in effectiveCheckedIds }
  }
  val checkedCustomItems = remember(customGroceryItems) { customGroceryItems.filter { it.isChecked } }

  val remainingCount = uncheckedCatalogItems.size + uncheckedCustomItems.size
  val totalCheckedCount = checkedCatalogItems.size + checkedCustomItems.size
  val remainingCostUsd = remember(uncheckedCatalogItems) { uncheckedCatalogItems.sumOf { it.estimatedCostUsd } }

  // Build fallback RestockDelta list when legacy callers omit onOpenUnpackSheet.
  val checkedRestockDeltas = remember(checkedCatalogItems) {
    checkedCatalogItems.map { item ->
      val reset = if (item.tier == IngredientTier.TIER_2_STAPLE) StapleStatus.PLENTY else null
      RestockDelta(item.ingredientId, item.restockQuantity, reset)
    }
  }

  Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Box(modifier = Modifier.fillMaxSize()) {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = if (isImeVisible) 24.dp else 88.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        // Optional segmented Plan switcher when rendered outside KitchenScaffold.
        if (showPlanSwitcher && !inStoreFocusMode) {
          item(key = "grocery_plan_switcher") {
            GroceryPlanSegmentedHeader(remainingCount = remainingCount, onOpenWeeklyMealPlan = onOpenWeeklyMealPlan)
          }
        }

        // Optional QA Batch # pill hidden on normal consumer grocery trips.
        if (showVaultDebugPill && !inStoreFocusMode) {
          item(key = "grocery_vault_pill") {
            HouseholdVaultSyncPill(
              householdProfile = householdProfile, batchWriteCallCount = batchWriteCallCount,
              onOpenQaLab = onOpenQaLab, marketRegionLabel = marketRegionLabel,
            )
          }
        }

        // Expandable Pantry-Adjusted List banner with 1-tap `Ran Low` skipped-staples drawer.
        if (!inStoreFocusMode) {
          item(key = "grocery_dedup_banner") {
            PantryDeduplicationBanner(
              groceryDiff = groceryDiff, currencySymbol = currencySymbol,
              onRequeueSkippedStaple = onRequeueSkippedStaple,
            )
          }
        }

        // Multi-Market Venue Filter Pills (`All · Palengke · Supermarket` or `Wet Market`).
        item(key = "grocery_venue_filter_row") {
          MarketVenueFilterRow(
            activeVenue = effectiveVenue, currency = householdProfile.currency,
            allCount = allActiveCount, wetMarketCount = wetMarketCount, supermarketCount = supermarketCount,
            onSelectVenue = { nextVenue ->
              localVenueName = nextVenue?.name
              onSelectVenueFilter(nextVenue)
            },
          )
        }

        // Weighted Quick-Add bar, Mic, Uncheck · N pill, and 3 direct single-tap sort pills.
        item(key = "grocery_sort_and_add_bar") {
          StoreSortAndQuickAddBar(
            sortMode = sortMode, checkedCount = totalCheckedCount,
            onSelectSortMode = onSelectSortMode, onToggleAddDrawer = { showAddDrawer = !showAddDrawer },
            onStartDictation = onStartVoiceDictation, onClearChecked = onClearCheckedItems,
          )
        }

        // Copy List and Save PDF 48.dp export strip.
        item(key = "grocery_export_actions") {
          KitchenExportActionRow(
            copyLabel = "Copy List", pdfLabel = "Save PDF", isExportingPdf = isExportingPdf,
            onCopyClick = onCopyListToClipboard, onSavePdfClick = onSaveListAsPdf,
          )
        }

        // Restore banner shown whenever 1 or more items were skipped on the current trip.
        if (dismissedItemCount > 0) {
          item(key = "grocery_skipped_notice") {
            SkippedGroceryNoticeBanner(
              skippedCount = dismissedItemCount,
              onRestoreClick = { onEditGroceryOrCatalog(GroceryAndCatalogEditAction.RestoreDismissedGroceryItems) },
            )
          }
        }

        // Inline quick-add custom note input + catalog preset drawer when toggled open.
        if (showAddDrawer) {
          item(key = "grocery_quick_custom_drawer") {
            val submitQuickCustom = {
              val clean = quickCustomInput.trim()
              if (clean.isNotEmpty()) {
                val slug = clean.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "note" }
                onAddCustomGroceryItem(CustomGroceryItem(customId = "custom_$slug", displayName = clean))
                quickCustomInput = ""
              }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                  value = quickCustomInput, onValueChange = { quickCustomInput = it },
                  label = { Text("Quick Shopping Item", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                  placeholder = { Text("e.g., Fresh Basil", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                  singleLine = true,
                  keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                  keyboardActions = KeyboardActions(onDone = { submitQuickCustom() }),
                  modifier = Modifier.weight(1f),
                )
                Button(onClick = submitQuickCustom, enabled = quickCustomInput.isNotBlank(), shape = CircleShape, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                  Text("Add", maxLines = 1, softWrap = false)
                }
              }

              AddIngredientCard(
                existingIngredientIds = existingCatalogIds,
                queuedGroceryIds = groceryDiff.groceryItems.map { it.ingredientId }.toSet(),
                isExpanded = true, onToggleExpanded = { showAddDrawer = false },
                onAddIngredientToGrocery = onAddCatalogItemToGrocery,
                onAddIngredientToPantry = onAddCatalogItemToPantry,
              )
            }
          }
        }

        // Celebrate 100% home-pantry meal coverage when zero shopping items remain.
        if (allActiveCount == 0 && totalCheckedCount == 0) {
          item(key = "grocery_all_covered_card") { groceryScreenAllCoveredCard() }
        }
        // Calm venue-specific empty card when Palengke or Supermarket has zero items needed.
        if (allActiveCount > 0 && remainingCount == 0 && effectiveVenue != null) {
          item(key = "grocery_venue_empty_card") {
            val venueLabel = when {
              effectiveVenue == MarketVenue.SUPERMARKET -> "Supermarket"
              householdProfile.currency == HouseholdCurrency.PHP -> "Palengke"
              else -> "Wet Market"
            }
            groceryScreenEmptyVenueCard(venueLabel)
          }
        }

        // Render active shopping items either grouped by store aisle or sorted flat.
        when (sortMode) {
          GrocerySortMode.AISLE_SORTED -> {
            GROCERY_SUPERMARKET_AISLES.forEach { aisle ->
              val aisleCatalog = uncheckedCatalogItems.filter { it.category == aisle.category }
              val aisleCustom = uncheckedCustomItems.filter { it.category == aisle.category }
              val totalInAisle = aisleCatalog.size + aisleCustom.size
              if (totalInAisle > 0) {
                item(key = "aisle_header_${aisle.category.name}") {
                  groceryScreenAisleHeader(aisle.headerTitle, groceryScreenAisleDotColor(aisle.category, extendedColors), totalInAisle)
                }
                items(items = aisleCatalog, key = { item -> "aisle_cat_${item.ingredientId}" }) { item ->
                  GroceryCheckableItemCard(
                    item = item, isChecked = false,
                    onToggleChecked = { onToggleItemChecked(item.ingredientId) },
                    onQuickRestockOne = {
                      val reset = if (item.tier == IngredientTier.TIER_2_STAPLE) StapleStatus.PLENTY else null
                      onRestockCheckedItems(listOf(RestockDelta(item.ingredientId, item.restockQuantity, reset)))
                    },
                    onRemoveManualQueue = { onRemoveManualQueue(item.ingredientId) },
                    onEditItem = { editingCatalogItemId = item.ingredientId },
                    onSkipForTrip = { onEditGroceryOrCatalog(GroceryAndCatalogEditAction.DismissGroceryItemForTrip(item.ingredientId)) },
                    currencySymbol = currencySymbol,
                  )
                }
                items(items = aisleCustom, key = { custom -> "aisle_custom_${custom.customId}" }) { custom ->
                  groceryScreenCustomItemRow(
                    item = custom,
                    onToggleChecked = { onToggleItemChecked(custom.customId) },
                    onEditCustomItem = { editingCustomItemId = custom.customId },
                  )
                }
              }
            }
          }

          GrocerySortMode.BY_RECIPE, GrocerySortMode.URGENCY_FIRST -> {
            if (remainingCount > 0) {
              item(key = "sorted_flat_header_${sortMode.name}") {
                groceryScreenAisleHeader(sortMode.displayLabel, extendedColors.herbalSage, remainingCount)
              }
              items(items = uncheckedCatalogItems, key = { item -> "flat_cat_${item.ingredientId}" }) { item ->
                GroceryCheckableItemCard(
                  item = item, isChecked = false,
                  onToggleChecked = { onToggleItemChecked(item.ingredientId) },
                  onQuickRestockOne = {
                    val reset = if (item.tier == IngredientTier.TIER_2_STAPLE) StapleStatus.PLENTY else null
                    onRestockCheckedItems(listOf(RestockDelta(item.ingredientId, item.restockQuantity, reset)))
                  },
                  onRemoveManualQueue = { onRemoveManualQueue(item.ingredientId) },
                  onEditItem = { editingCatalogItemId = item.ingredientId },
                  onSkipForTrip = { onEditGroceryOrCatalog(GroceryAndCatalogEditAction.DismissGroceryItemForTrip(item.ingredientId)) },
                  currencySymbol = currencySymbol,
                )
              }
              items(items = uncheckedCustomItems, key = { custom -> "flat_custom_${custom.customId}" }) { custom ->
                groceryScreenCustomItemRow(
                  item = custom,
                  onToggleChecked = { onToggleItemChecked(custom.customId) },
                  onEditCustomItem = { editingCustomItemId = custom.customId },
                )
              }
            }
          }
        }

        if (totalCheckedCount > 0) {
          item(key = "grocery_checked_accordion") {
            CheckedItemsAccordionCard(
              checkedCatalogItems = checkedCatalogItems, checkedCustomItems = checkedCustomItems,
              isAccordionExpanded = checkedAccordionOpen,
              onToggleAccordion = { checkedAccordionOpen = !checkedAccordionOpen },
              onUncheckItem = onToggleItemChecked,
            )
          }
        }
      }

      // Docked 56.dp warm summary bar opening UnpackGroceriesSheet or committing O(1) restock.
      if (!isImeVisible) {
        GroceryDockedSummaryBar(
          remainingItemCount = remainingCount, remainingCostUsd = remainingCostUsd,
          checkedCount = totalCheckedCount, checkedRestockCount = checkedRestockDeltas.size,
          inStoreFocusMode = inStoreFocusMode,
          onRestockCheckedBatch = {
            when {
              onOpenUnpackSheet != null && totalCheckedCount > 0 -> onOpenUnpackSheet()
              checkedRestockDeltas.isNotEmpty() -> onRestockCheckedItems(checkedRestockDeltas)
            }
          },
          onToggleInStoreMode = { inStoreFocusMode = !inStoreFocusMode },
          currencySymbol = currencySymbol,
          modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 10.dp),
        )
      }

      // Modal bottom sheet for editing catalog-backed buy quantities, names, and pack pricing.
      val activeEditCatalogItem = editingCatalogItemId?.let { targetId ->
        groceryDiff.groceryItems.firstOrNull { it.ingredientId == targetId }
      }
      if (activeEditCatalogItem != null) {
        EditGroceryCatalogItemSheet(
          item = activeEditCatalogItem, catalogItem = catalogById[activeEditCatalogItem.ingredientId],
          currencySymbol = currencySymbol, onDispatchEditAction = onEditGroceryOrCatalog,
          onQuickRestockOne = {
            val reset = if (activeEditCatalogItem.tier == IngredientTier.TIER_2_STAPLE) StapleStatus.PLENTY else null
            onRestockCheckedItems(listOf(RestockDelta(activeEditCatalogItem.ingredientId, activeEditCatalogItem.restockQuantity, reset)))
          },
          onDismiss = { editingCatalogItemId = null },
        )
      }

      // Modal bottom sheet for editing or deleting user-added custom shopping notes.
      val activeEditCustomItem = editingCustomItemId?.let { targetId ->
        customGroceryItems.firstOrNull { it.customId == targetId }
      }
      if (activeEditCustomItem != null) {
        EditCustomGroceryItemSheet(
          item = activeEditCustomItem, onDispatchEditAction = onEditGroceryOrCatalog,
          onDismiss = { editingCustomItemId = null },
        )
      }
    }
  }
}

// Route fresh produce custom notes to Wet Market and packaged items to Supermarket.
private fun groceryScreenCustomVenue(item: CustomGroceryItem): MarketVenue =
  when (item.category) {
    IngredientCategory.PRODUCE -> MarketVenue.WET_MARKET
    else -> MarketVenue.SUPERMARKET
  }

// Map each supermarket aisle category to its semantic Material 3 kitchen accent dot color.
private fun groceryScreenAisleDotColor(category: IngredientCategory, extendedColors: ExtendedKitchenColors): Color =
  when (category) {
    IngredientCategory.PRODUCE -> extendedColors.herbalSage
    IngredientCategory.DAIRY_EGGS -> extendedColors.warmApricot
    IngredientCategory.PROTEIN -> extendedColors.terracotta
    IngredientCategory.BAKERY_GRAINS -> extendedColors.expiringSoonIndicator
    IngredientCategory.PANTRY_STAPLE -> extendedColors.terracottaClay
  }

// Compact aisle heading row pairing a colored indicator dot with singular or plural item count.
@Composable
private fun groceryScreenAisleHeader(title: String, dotColor: Color, itemCount: Int) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dotColor))
      Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Spacer(modifier = Modifier.width(8.dp))
    Text(if (itemCount == 1) "1 item" else "$itemCount items", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, softWrap = false)
  }
}

// Checkable 48.dp custom shopping note row with inline Edit icon button.
@Composable
private fun groceryScreenCustomItemRow(
  item: CustomGroceryItem,
  onToggleChecked: () -> Unit,
  onEditCustomItem: (() -> Unit)? = null,
) {
  val extendedColors = KitchenTheme.extendedColors
  Card(
    modifier = Modifier.fillMaxWidth(), shape = KitchenTheme.shapes.card2xl,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleChecked).padding(horizontal = 4.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerLowest).border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape))
      }
      Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
        Text(item.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(item.quantityNote, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      if (onEditCustomItem != null) {
        IconButton(onClick = onEditCustomItem, modifier = Modifier.size(48.dp)) {
          Icon(Icons.Filled.Edit, contentDescription = "Edit ${item.displayName}", tint = extendedColors.herbalSage, modifier = Modifier.size(19.dp))
        }
      }
    }
  }
}

// Serene confirmation card rendered when 100% of planned weekly meals are covered by home stock.
@Composable
private fun groceryScreenAllCoveredCard() {
  val extendedColors = KitchenTheme.extendedColors
  Card(
    modifier = Modifier.fillMaxWidth(), shape = KitchenTheme.shapes.card2xl,
    colors = CardDefaults.cardColors(containerColor = extendedColors.freshContainer),
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
  ) {
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Icon(Icons.Filled.Check, contentDescription = null, tint = extendedColors.onFreshContainer)
      Spacer(modifier = Modifier.width(2.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text("All Planned Meals Covered!", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = extendedColors.onFreshContainer)
        Text("Zero packages needed — 100% of planned weekly recipes are covered by home stock.", style = MaterialTheme.typography.bodySmall, color = extendedColors.onFreshContainer)
      }
    }
  }
}

// Quiet empty-venue card shown when all remaining items belong to the other market venue.
@Composable
private fun groceryScreenEmptyVenueCard(venueLabel: String) {
  val extendedColors = KitchenTheme.extendedColors
  Card(
    modifier = Modifier.fillMaxWidth(), shape = KitchenTheme.shapes.card2xl,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
  ) {
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Icon(Icons.Filled.Check, contentDescription = null, tint = extendedColors.herbalSage)
      Text("Zero items needed from $venueLabel right now.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

// Order unchecked catalog items according to aisle, meal attribution, or acute shortage priority.
private fun groceryScreenOrderItems(items: List<GroceryListItem>, sortMode: GrocerySortMode): List<GroceryListItem> =
  when (sortMode) {
    GrocerySortMode.AISLE_SORTED -> items
    GrocerySortMode.BY_RECIPE -> items.sortedBy { it.mealUsageLabels.firstOrNull().orEmpty() }
    GrocerySortMode.URGENCY_FIRST -> items.sortedWith(
      compareByDescending<GroceryListItem> { it.netDeficit > 0.0 && it.tier == IngredientTier.TIER_1_QUANTITATIVE }
        .thenByDescending { it.estimatedCostUsd },
    )
  }

@Preview(showBackground = true, widthDp = 412, heightDp = 920)
@Composable
private fun SmartGroceryScreenPreview() {
  // Verifies All · Palengke · Supermarket venue pills, skipped-staples drawer, and Restock · 1 bar.
  val items = listOf(
    GroceryListItem("ing_avocado", "Ripe Avocados · Antipolo Market", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.COUNT, 4.0, 1.0, 3.0, 4.0, 1, 4.0, mealUsageLabels = listOf("for Tuesday Breakfast • Avocado Egg Tartine"), estimatedCostUsd = 180.0, packageDescriptor = "4 count · Semi-ripe", marketVenue = MarketVenue.WET_MARKET),
    GroceryListItem("ing_butter", "Unsalted Cultured Butter", IngredientCategory.DAIRY_EGGS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 250.0, 50.0, 200.0, 250.0, 1, 250.0, estimatedCostUsd = 225.0, packageDescriptor = "250 g block", marketVenue = MarketVenue.SUPERMARKET),
    GroceryListItem("ing_chicken", "Free-Range Chicken Thighs", IngredientCategory.PROTEIN, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 4000.0, 0.0, 4000.0, 4000.0, 4, 4000.0, mealUsageLabels = listOf("for Monday Dinner • Crispy Chicken & Tomato Baguette"), estimatedCostUsd = 920.0, packageDescriptor = "4 packs · 4 kg", marketVenue = MarketVenue.WET_MARKET),
    GroceryListItem("ing_olive_oil", "Extra Virgin Olive Oil", IngredientCategory.PANTRY_STAPLE, IngredientTier.TIER_2_STAPLE, BaseUnit.MILLILITER, 0.0, 500.0, 750.0, 750.0, 1, 750.0, StapleStatus.LOW, estimatedCostUsd = 695.0, packageDescriptor = "750 ml bottle", isChecked = true, marketVenue = MarketVenue.SUPERMARKET),
  )
  val skipped = listOf(
    SkippedPantryItemSummary("ing_milk", "Whole Fresh Milk", "2 L", 800.0, BaseUnit.MILLILITER),
    SkippedPantryItemSummary("ing_egg", "Large Farm Eggs", "12 count", 8.0, BaseUnit.COUNT),
  )
  val diff = WeeklyGroceryDiff(
    slotSummaries = emptyList(), slotSummariesBySlotId = emptyMap(), groceryItems = items,
    groceryItemsByCategory = items.groupBy { it.category }, restockDeltas = emptyList(),
    skippedStockedStapleCount = 8, deduplicationSavingsUsd = 7983.0, plannedMealCount = 21,
    estimatedTotalCostUsd = 1325.0, skippedStockedItems = skipped,
  )
  FamilyKitchenBoardTheme {
    SmartGroceryScreen(
      groceryDiff = diff, checkedGroceryItemIds = setOf("ing_olive_oil"),
      batchWriteCallCount = 2, onToggleItemChecked = {}, onRestockCheckedItems = {},
    )
  }
}
