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
import com.familykitchen.board.domain.HouseholdProfile
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.IngredientCategory
import com.familykitchen.board.domain.IngredientTier
import com.familykitchen.board.domain.RestockDelta
import com.familykitchen.board.domain.StapleStatus
import com.familykitchen.board.domain.WeeklyGroceryDiff
import com.familykitchen.board.ui.export.KitchenExportActionRow
import com.familykitchen.board.ui.pantry.AddIngredientCard
import com.familykitchen.board.ui.theme.ExtendedKitchenColors
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme

// SavorAI Smart Grocery List Screen (`smart_grocery_list`) enforcing EC-09 chronological ceiling
// rounding, EC-11 Tier-2 PLENTY resets, O(1) batch commits, and above-the-fold aisle visibility.

private data class AisleSpec(
  val category: IngredientCategory,
  val headerTitle: String,
)

// Physical supermarket aisle mapping ordered along the shopper's perimeter walk.
private val SUPERMARKET_AISLES: List<AisleSpec> = listOf(
  AisleSpec(IngredientCategory.PRODUCE, "Aisle 1: Produce & Herbs"),
  AisleSpec(IngredientCategory.DAIRY_EGGS, "Aisle 2: Dairy & Refrigerated"),
  AisleSpec(IngredientCategory.PROTEIN, "Aisle 4: Meat & Seafood"),
  AisleSpec(IngredientCategory.BAKERY_GRAINS, "Aisle 5: Bakery & Grains"),
  AisleSpec(IngredientCategory.PANTRY_STAPLE, "Aisle 7: Pantry & Baking"),
)

/**
 * Stateless Material 3 screen implementing `smart_grocery_list` with immediate above-the-fold
 * grocery item visibility, lazy aisle `items(...)`, a non-wrapping `Pantry-Adjusted List` card,
 * `48.dp` Quick-Add + `Mic` + 3 direct sort pills, and a slim `56.dp` docked summary bar.
 *
 * @param groceryDiff Chronological weekly grocery diff (`EC-09`, `EC-11`, `EC-12`).
 * @param checkedGroceryItemIds Persisted set of checked catalog `ingredientId`s.
 * @param customGroceryItems User-added or dictated custom shopping items.
 * @param sortMode Active [GrocerySortMode] (`AISLE_SORTED`, `BY_RECIPE`, `URGENCY_FIRST`).
 * @param householdProfile Household profile supplying name and cook initials.
 * @param batchWriteCallCount Live `KitchenStoragePort.batchWriteCallCount` metric.
 * @param tonightMealSummary Subtitle retained for backward-compatible callers.
 * @param showPlanSwitcher Renders the top `List | Meal Plan` segmented bar when `true`.
 * @param showVaultDebugPill Renders the internal `Batch #` QA vault pill when `true` (`false` default).
 * @param existingCatalogIds Existing catalog IDs surfaced on the local-preset quick-add drawer.
 * @param onToggleItemChecked Toggles checked state for a catalog `ingredientId` or `customId`.
 * @param onRestockCheckedItems Commits checked [RestockDelta]s in one `O(1)` atomic batch write.
 * @param onSelectSortMode Updates `KitchenSnapshot.grocerySortMode`.
 * @param onAddCustomGroceryItem Persists a custom or dictated [CustomGroceryItem].
 * @param onAddCatalogItemToGrocery Adds a preset or custom [IngredientCatalogItem] to Grocery.
 * @param onAddCatalogItemToPantry Stocks a preset or custom [IngredientCatalogItem] in Pantry.
 * @param onRemoveManualQueue Removes a manually queued catalog ID from `aiAddedGroceryIds`.
 * @param onClearCheckedItems Sweeps checked items in one `O(1)` snapshot write.
 * @param onStartVoiceDictation Triggers offline voice dictation (`en-US` / `fr-FR`).
 * @param onOpenWeeklyMealPlan Navigates to the Weekly Meal Plan / Today board.
 * @param onOpenQaLab Opens `QaScenarioLabSheet` when the `Batch #` vault pill is tapped.
 * @param currencySymbol Active household currency symbol (`"₱"` default).
 * @param marketRegionLabel Active local market region label.
 * @param isExportingPdf `true` while a scoped multi-page Grocery PDF export is writing on IO.
 * @param onCopyListToClipboard Copies the plain-text shopping list to the system clipboard.
 * @param onSaveListAsPdf Launches scoped `CreateDocument("application/pdf")` for the shopping list.
 * @param catalogById Household catalog lookup map supplying pack size and price inside edit sheets.
 * @param dismissedItemCount Count of items skipped on this shopping trip (`> 0` renders restore bar).
 * @param onEditGroceryOrCatalog Dispatches [GroceryAndCatalogEditAction] mutations in `O(1)` writes.
 * @param modifier Optional Compose [Modifier].
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
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  val isImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
  var showAddDrawer by rememberSaveable { mutableStateOf(false) }
  var quickCustomInput by rememberSaveable { mutableStateOf("") }
  var checkedAccordionOpen by rememberSaveable { mutableStateOf(true) }
  var inStoreFocusMode by rememberSaveable { mutableStateOf(false) }
  // Track open item-edit modal bottom sheets as Bundle-safe String IDs across recreation.
  var editingCatalogItemId by rememberSaveable { mutableStateOf<String?>(null) }
  var editingCustomItemId by rememberSaveable { mutableStateOf<String?>(null) }

  // Partition catalog and custom grocery rows into active unchecked vs completed checked buckets.
  val effectiveCheckedIds = remember(groceryDiff.groceryItems, checkedGroceryItemIds) {
    checkedGroceryItemIds + groceryDiff.groceryItems.filter { it.isChecked }.map { it.ingredientId }
  }
  // Apply selected GrocerySortMode deterministically to active unchecked catalog items.
  val uncheckedCatalogItems = remember(groceryDiff.groceryItems, effectiveCheckedIds, sortMode) {
    orderGroceryItems(
      items = groceryDiff.groceryItems.filterNot { it.ingredientId in effectiveCheckedIds },
      sortMode = sortMode,
    )
  }
  // Preserve checked catalog items separately so CheckedItemsAccordionCard can restore them in 1 tap.
  val checkedCatalogItems = remember(groceryDiff.groceryItems, effectiveCheckedIds) {
    groceryDiff.groceryItems.filter { it.ingredientId in effectiveCheckedIds }
  }
  // Split custom/dictated items into active aisle rows vs completed accordion pills.
  val uncheckedCustomItems = remember(customGroceryItems) { customGroceryItems.filterNot { it.isChecked } }
  val checkedCustomItems = remember(customGroceryItems) { customGroceryItems.filter { it.isChecked } }

  // Compute live cart counters and estimated remaining household currency cost.
  val remainingCount = uncheckedCatalogItems.size + uncheckedCustomItems.size
  val totalCheckedCount = checkedCatalogItems.size + checkedCustomItems.size
  val remainingCostUsd = remember(uncheckedCatalogItems) {
    uncheckedCatalogItems.sumOf { it.estimatedCostUsd }
  }

  // Build O(1) atomic RestockDeltas for checked catalog items (resetting Tier-2 staples to PLENTY).
  val checkedRestockDeltas = remember(checkedCatalogItems) {
    checkedCatalogItems.map { item ->
      // EC-11: Checking off a LOW/OUT Tier-2 staple restores its status directly to PLENTY.
      val reset = if (item.tier == IngredientTier.TIER_2_STAPLE) StapleStatus.PLENTY else null
      RestockDelta(item.ingredientId, item.restockQuantity, reset)
    }
  }

  // Reclaim above-the-fold height so Aisle 1 items appear immediately on compact 360dp screens.
  Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Box(modifier = modifier.fillMaxSize()) {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
          start = 16.dp,
          end = 16.dp,
          top = 10.dp,
          bottom = if (isImeVisible) 24.dp else 88.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        // Optional standalone List | Meal Plan switcher (disabled inside 5-tab SavorAI shell).
        if (showPlanSwitcher && !inStoreFocusMode) {
          item(key = "grocery_plan_switcher") {
            GroceryPlanSegmentedHeader(
              remainingCount = remainingCount,
              onOpenWeeklyMealPlan = onOpenWeeklyMealPlan,
            )
          }
        }

        // Hide internal Batch #0 QA pill by default (`showVaultDebugPill = false`) on consumer views.
        if (showVaultDebugPill && !inStoreFocusMode) {
          item(key = "grocery_vault_pill") {
            HouseholdVaultSyncPill(
              householdProfile = householdProfile,
              batchWriteCallCount = batchWriteCallCount,
              onOpenQaLab = onOpenQaLab,
              marketRegionLabel = marketRegionLabel,
            )
          }
        }

        // Editorial Pantry-Adjusted List banner showing skipped in-stock staples and PHP ₱ savings.
        if (!inStoreFocusMode) {
          item(key = "grocery_dedup_banner") {
            PantryDeduplicationBanner(groceryDiff = groceryDiff, currencySymbol = currencySymbol)
          }
        }

        // Weighted Quick-Add bar + on-screen 48.dp Mic button + 3 direct single-tap sort pills.
        item(key = "grocery_sort_and_add_bar") {
          StoreSortAndQuickAddBar(
            sortMode = sortMode,
            checkedCount = totalCheckedCount,
            onSelectSortMode = onSelectSortMode,
            onToggleAddDrawer = { showAddDrawer = !showAddDrawer },
            onStartDictation = onStartVoiceDictation,
            onClearChecked = onClearCheckedItems,
          )
        }

        // Tactile 48.dp Copy List & Save PDF export pills (zero storage permissions via SAF).
        item(key = "grocery_export_actions") {
          KitchenExportActionRow(
            copyLabel = "Copy List",
            pdfLabel = "Save PDF",
            isExportingPdf = isExportingPdf,
            onCopyClick = onCopyListToClipboard,
            onSavePdfClick = onSaveListAsPdf,
          )
        }

        // Surface skipped-items restore banner whenever >= 1 item was skipped for this trip.
        if (dismissedItemCount > 0) {
          item(key = "grocery_skipped_notice") {
            SkippedGroceryNoticeBanner(
              skippedCount = dismissedItemCount,
              onRestoreClick = { onEditGroceryOrCatalog(GroceryAndCatalogEditAction.RestoreDismissedGroceryItems) },
            )
          }
        }

        // Expandable quick custom note + local-market preset drawer.
        if (showAddDrawer) {
          item(key = "grocery_quick_custom_drawer") {
            // Normalize free-text shopping notes into deterministic snake_case custom IDs.
            val submitQuickCustom = {
              val clean = quickCustomInput.trim()
              if (clean.isNotEmpty()) {
                val slug = clean.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "note" }
                onAddCustomGroceryItem(CustomGroceryItem(customId = "custom_$slug", displayName = clean))
                quickCustomInput = ""
              }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                OutlinedTextField(
                  value = quickCustomInput,
                  onValueChange = { quickCustomInput = it },
                  label = { Text("Quick Shopping Item", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                  placeholder = { Text("e.g., Fresh Basil", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                  singleLine = true,
                  keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done,
                  ),
                  keyboardActions = KeyboardActions(onDone = { submitQuickCustom() }),
                  modifier = Modifier.weight(1f),
                )
                Button(
                  onClick = submitQuickCustom,
                  enabled = quickCustomInput.isNotBlank(),
                  shape = CircleShape,
                  modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                  Text("Add", maxLines = 1, softWrap = false)
                }
              }

              // Embed full catalog & 20-preset builder when adding structured pantry/grocery items.
              AddIngredientCard(
                existingIngredientIds = existingCatalogIds,
                queuedGroceryIds = groceryDiff.groceryItems.map { it.ingredientId }.toSet(),
                isExpanded = true,
                onToggleExpanded = { showAddDrawer = false },
                onAddIngredientToGrocery = onAddCatalogItemToGrocery,
                onAddIngredientToPantry = onAddCatalogItemToPantry,
              )
            }
          }
        }

        // Show zero-deficit celebration card when home stock already covers all 21 weekly slots.
        if (remainingCount == 0 && totalCheckedCount == 0) {
          item(key = "grocery_all_covered_card") {
            AllPlannedMealsCoveredCard()
          }
        }

        // Emit true lazy items(...) for aisle headers, catalog items, and custom items.
        when (sortMode) {
          GrocerySortMode.AISLE_SORTED -> {
            // Walk physical supermarket perimeter order and omit empty aisles cleanly.
            SUPERMARKET_AISLES.forEach { aisle ->
              val aisleCatalog = uncheckedCatalogItems.filter { it.category == aisle.category }
              val aisleCustom = uncheckedCustomItems.filter { it.category == aisle.category }
              val totalInAisle = aisleCatalog.size + aisleCustom.size
              if (totalInAisle > 0) {
                item(key = "aisle_header_${aisle.category.name}") {
                  AisleSectionHeader(
                    title = aisle.headerTitle,
                    dotColor = aisleDotColor(aisle.category, extendedColors),
                    itemCount = totalInAisle,
                  )
                }
                // Lazy key per ingredientId enables smooth placement animations when checking items.
                items(
                  items = aisleCatalog,
                  key = { item -> "aisle_cat_${item.ingredientId}" },
                ) { item ->
                  GroceryCheckableItemCard(
                    item = item,
                    isChecked = false,
                    onToggleChecked = { onToggleItemChecked(item.ingredientId) },
                    onQuickRestockOne = {
                      // Single-item overflow shortcut commits 1 whole-package RestockDelta immediately.
                      val reset = if (item.tier == IngredientTier.TIER_2_STAPLE) StapleStatus.PLENTY else null
                      onRestockCheckedItems(listOf(RestockDelta(item.ingredientId, item.restockQuantity, reset)))
                    },
                    onRemoveManualQueue = { onRemoveManualQueue(item.ingredientId) },
                    onEditItem = { editingCatalogItemId = item.ingredientId },
                    onSkipForTrip = {
                      onEditGroceryOrCatalog(GroceryAndCatalogEditAction.DismissGroceryItemForTrip(item.ingredientId))
                    },
                    currencySymbol = currencySymbol,
                  )
                }
                items(
                  items = aisleCustom,
                  key = { custom -> "aisle_custom_${custom.customId}" },
                ) { custom ->
                  CustomGroceryItemRow(
                    item = custom,
                    onToggleChecked = { onToggleItemChecked(custom.customId) },
                    onEditCustomItem = { editingCustomItemId = custom.customId },
                  )
                }
              }
            }
          }

          GrocerySortMode.BY_RECIPE, GrocerySortMode.URGENCY_FIRST -> {
            // Render flat sorted list when grouping by first chronological meal or shortage urgency.
            if (remainingCount > 0) {
              item(key = "sorted_flat_header_${sortMode.name}") {
                AisleSectionHeader(
                  title = sortMode.displayLabel,
                  dotColor = extendedColors.herbalSage,
                  itemCount = remainingCount,
                )
              }
              items(
                items = uncheckedCatalogItems,
                key = { item -> "flat_cat_${item.ingredientId}" },
              ) { item ->
                GroceryCheckableItemCard(
                  item = item,
                  isChecked = false,
                  onToggleChecked = { onToggleItemChecked(item.ingredientId) },
                  onQuickRestockOne = {
                    val reset = if (item.tier == IngredientTier.TIER_2_STAPLE) StapleStatus.PLENTY else null
                    onRestockCheckedItems(listOf(RestockDelta(item.ingredientId, item.restockQuantity, reset)))
                  },
                  onRemoveManualQueue = { onRemoveManualQueue(item.ingredientId) },
                  onEditItem = { editingCatalogItemId = item.ingredientId },
                  onSkipForTrip = {
                    onEditGroceryOrCatalog(GroceryAndCatalogEditAction.DismissGroceryItemForTrip(item.ingredientId))
                  },
                  currencySymbol = currencySymbol,
                )
              }
              items(
                items = uncheckedCustomItems,
                key = { custom -> "flat_custom_${custom.customId}" },
              ) { custom ->
                CustomGroceryItemRow(
                  item = custom,
                  onToggleChecked = { onToggleItemChecked(custom.customId) },
                  onEditCustomItem = { editingCustomItemId = custom.customId },
                )
              }
            }
          }
        }

        // Collapsible Checked Off tray keeps completed items accessible without cluttering active aisles.
        if (totalCheckedCount > 0) {
          item(key = "grocery_checked_accordion") {
            CheckedItemsAccordionCard(
              checkedCatalogItems = checkedCatalogItems,
              checkedCustomItems = checkedCustomItems,
              isAccordionExpanded = checkedAccordionOpen,
              onToggleAccordion = { checkedAccordionOpen = !checkedAccordionOpen },
              onUncheckItem = onToggleItemChecked,
            )
          }
        }
      }

      // Docked 56.dp warm summary bar with 3.dp cart-progress micro-bar (yields to IME when typing).
      if (!isImeVisible) {
        GroceryDockedSummaryBar(
          remainingItemCount = remainingCount,
          remainingCostUsd = remainingCostUsd,
          checkedCount = totalCheckedCount,
          checkedRestockCount = checkedRestockDeltas.size,
          inStoreFocusMode = inStoreFocusMode,
          onRestockCheckedBatch = {
            if (checkedRestockDeltas.isNotEmpty()) {
              onRestockCheckedItems(checkedRestockDeltas)
            }
          },
          onToggleInStoreMode = { inStoreFocusMode = !inStoreFocusMode },
          currencySymbol = currencySymbol,
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        )
      }

      // Modal bottom sheet for editing any catalog shopping item's quantity, name, or store pack.
      val activeEditCatalogItem = editingCatalogItemId?.let { targetId ->
        groceryDiff.groceryItems.firstOrNull { it.ingredientId == targetId }
      }
      if (activeEditCatalogItem != null) {
        EditGroceryCatalogItemSheet(
          item = activeEditCatalogItem,
          catalogItem = catalogById[activeEditCatalogItem.ingredientId],
          currencySymbol = currencySymbol,
          onDispatchEditAction = onEditGroceryOrCatalog,
          onQuickRestockOne = {
            val reset = if (activeEditCatalogItem.tier == IngredientTier.TIER_2_STAPLE) StapleStatus.PLENTY else null
            onRestockCheckedItems(listOf(RestockDelta(activeEditCatalogItem.ingredientId, activeEditCatalogItem.restockQuantity, reset)))
          },
          onDismiss = { editingCatalogItemId = null },
        )
      }

      // Modal bottom sheet for editing or deleting a user-added custom shopping note.
      val activeEditCustomItem = editingCustomItemId?.let { targetId ->
        customGroceryItems.firstOrNull { it.customId == targetId }
      }
      if (activeEditCustomItem != null) {
        EditCustomGroceryItemSheet(
          item = activeEditCustomItem,
          onDispatchEditAction = onEditGroceryOrCatalog,
          onDismiss = { editingCustomItemId = null },
        )
      }
    }
  }
}

private fun aisleDotColor(
  category: IngredientCategory,
  extendedColors: ExtendedKitchenColors,
): Color = when (category) {
  IngredientCategory.PRODUCE -> extendedColors.herbalSage
  IngredientCategory.DAIRY_EGGS -> extendedColors.warmApricot
  IngredientCategory.PROTEIN -> extendedColors.terracotta
  IngredientCategory.BAKERY_GRAINS -> extendedColors.expiringSoonIndicator
  IngredientCategory.PANTRY_STAPLE -> extendedColors.terracottaClay
}

// Weighted supermarket aisle header preventing "Refrigerated1\nitems" collision & "1 items" bug.
@Composable
private fun AisleSectionHeader(title: String, dotColor: Color, itemCount: Int) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(
      modifier = Modifier.weight(1f),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dotColor))
      Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Spacer(modifier = Modifier.width(8.dp))
    Text(
      text = if (itemCount == 1) "1 item" else "$itemCount items",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.outline,
      maxLines = 1,
      softWrap = false,
    )
  }
}

// Checkable 24.dp ceramic card with 48.dp touch box and 1-tap 48.dp Edit button for custom items.
@Composable
private fun CustomGroceryItemRow(
  item: CustomGroceryItem,
  onToggleChecked: () -> Unit,
  onEditCustomItem: (() -> Unit)? = null,
) {
  val extendedColors = KitchenTheme.extendedColors
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = KitchenTheme.shapes.card2xl,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onToggleChecked)
        .padding(horizontal = 4.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier = Modifier.size(48.dp),
        contentAlignment = Alignment.Center,
      ) {
        Box(
          modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
      }
      Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
        Text(
          text = item.displayName,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = item.quantityNote,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (onEditCustomItem != null) {
        IconButton(onClick = onEditCustomItem, modifier = Modifier.size(48.dp)) {
          Icon(
            imageVector = Icons.Filled.Edit,
            contentDescription = "Edit ${item.displayName}",
            tint = extendedColors.herbalSage,
            modifier = Modifier.size(19.dp),
          )
        }
      }
    }
  }
}

// Celebration card rendered when 100% of planned weekly BOM lines are satisfied by home stock.
@Composable
private fun AllPlannedMealsCoveredCard() {
  val extendedColors = KitchenTheme.extendedColors
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = KitchenTheme.shapes.card2xl,
    colors = CardDefaults.cardColors(containerColor = extendedColors.freshContainer),
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(Icons.Filled.Check, contentDescription = null, tint = extendedColors.onFreshContainer)
      Spacer(modifier = Modifier.width(2.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "All Planned Meals Covered!",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = extendedColors.onFreshContainer,
        )
        Text(
          text = "Zero packages needed — 100% of planned weekly recipes are covered by home stock.",
          style = MaterialTheme.typography.bodySmall,
          color = extendedColors.onFreshContainer,
        )
      }
    }
  }
}

// Deterministically orders unchecked GroceryListItems according to the selected GrocerySortMode.
private fun orderGroceryItems(items: List<GroceryListItem>, sortMode: GrocerySortMode): List<GroceryListItem> =
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
  val items = listOf(
    GroceryListItem("ing_avocado", "Ripe Avocados (Antipolo Market)", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.COUNT, 4.0, 1.0, 3.0, 4.0, 1, 4.0, mealUsageLabels = listOf("for Tuesday Breakfast • Avocado Egg Tartine"), estimatedCostUsd = 180.0, packageDescriptor = "4 count • Semi-ripe"),
    GroceryListItem("ing_butter", "Unsalted Cultured Butter", IngredientCategory.DAIRY_EGGS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 250.0, 50.0, 200.0, 250.0, 1, 250.0, estimatedCostUsd = 225.0, packageDescriptor = "250 g block"),
    GroceryListItem("ing_chicken", "Free-Range Chicken Thighs", IngredientCategory.PROTEIN, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 4000.0, 0.0, 4000.0, 4000.0, 4, 4000.0, mealUsageLabels = listOf("for Monday Dinner • Crispy Chicken & Tomato Baguette"), estimatedCostUsd = 920.0, packageDescriptor = "4 packs (4000 g)"),
    GroceryListItem("ing_olive_oil", "Extra Virgin Olive Oil", IngredientCategory.PANTRY_STAPLE, IngredientTier.TIER_2_STAPLE, BaseUnit.MILLILITER, 0.0, 500.0, 750.0, 750.0, 1, 750.0, StapleStatus.LOW, estimatedCostUsd = 695.0, packageDescriptor = "750 ml bottle", isChecked = true),
  )
  val diff = WeeklyGroceryDiff(
    slotSummaries = emptyList(),
    slotSummariesBySlotId = emptyMap(),
    groceryItems = items,
    groceryItemsByCategory = items.groupBy { it.category },
    restockDeltas = emptyList(),
    skippedStockedStapleCount = 8,
    deduplicationSavingsUsd = 7983.0,
    plannedMealCount = 21,
    estimatedTotalCostUsd = 1325.0,
  )
  FamilyKitchenBoardTheme {
    SmartGroceryScreen(
      groceryDiff = diff,
      checkedGroceryItemIds = setOf("ing_olive_oil"),
      batchWriteCallCount = 2,
      onToggleItemChecked = {},
      onRestockCheckedItems = {},
    )
  }
}
