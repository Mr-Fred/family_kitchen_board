package com.familykitchen.board.ui.grocery

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.BaseUnit
import com.familykitchen.board.domain.CustomGroceryItem
import com.familykitchen.board.domain.GroceryListItem
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.IngredientCategory
import com.familykitchen.board.domain.IngredientTier
import com.familykitchen.board.domain.MarketVenue
import com.familykitchen.board.domain.PantryLedgerEntry
import com.familykitchen.board.domain.StorageLocation
import com.familykitchen.board.domain.UnpackItemDirective
import com.familykitchen.board.domain.defaultShelfLifeDaysFor
import com.familykitchen.board.domain.defaultShelfLifeDaysForZone
import com.familykitchen.board.domain.defaultStorageLocationFor
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme

// SavorAI Unpack Groceries Sheet (`UnpackGroceriesSheet`):
// Lets shoppers assign destination zones (`Fridge · Pantry · Freezer`, e.g. 1-tap Chicken Thighs to
// Freezer 90d) and choose `Stock in Pantry` vs `Clear Note Only` on custom items in 1 atomic write.

// Household supply keywords defaulting custom notes to `Clear Note Only` instead of Pantry stock.
private val UNPACK_NON_FOOD_KEYWORDS: List<String> = listOf(
  "towel", "paper", "foil", "wrap", "soap", "sponge",
  "detergent", "trash", "bag", "napkin", "tissue", "bleach",
)

/**
 * Material 3 [ModalBottomSheet] surfaced when tapping `Restock · N` on `SmartGroceryScreen`,
 * displaying checked catalog items with `Fridge · Pantry · Freezer` destination pills and checked
 * [CustomGroceryItem]s with `Stock in Pantry` vs `Clear Note Only` toggles, committing everything
 * in one `O(1)` atomic batch via `Confirm Unpack`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnpackGroceriesSheet(
  checkedCatalogItems: List<GroceryListItem>,
  checkedCustomItems: List<CustomGroceryItem> = emptyList(),
  catalogById: Map<String, IngredientCatalogItem> = emptyMap(),
  pantryById: Map<String, PantryLedgerEntry> = emptyMap(),
  onConfirmUnpack: (List<UnpackItemDirective>, Set<String>, Set<String>) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // Open fully expanded so shoppers review all checked items and Confirm Unpack without scrolling first.
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(
    onDismissRequest = onDismiss, sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.background, modifier = modifier,
  ) {
    UnpackGroceriesSheetContent(
      checkedCatalogItems = checkedCatalogItems, checkedCustomItems = checkedCustomItems,
      catalogById = catalogById, pantryById = pantryById,
      onConfirmUnpack = onConfirmUnpack, onDismiss = onDismiss,
    )
  }
}

/**
 * Stateless inner content of [UnpackGroceriesSheet] enabling hermetic `@Preview` rendering without
 * a modal window host.
 */
@Composable
fun UnpackGroceriesSheetContent(
  checkedCatalogItems: List<GroceryListItem>,
  checkedCustomItems: List<CustomGroceryItem> = emptyList(),
  catalogById: Map<String, IngredientCatalogItem> = emptyMap(),
  pantryById: Map<String, PantryLedgerEntry> = emptyMap(),
  onConfirmUnpack: (List<UnpackItemDirective>, Set<String>, Set<String>) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors

  // Initialize each checked catalog item to its smart household zone (Fridge, Pantry, or Freezer).
  val zoneByIngredientId = remember(checkedCatalogItems, catalogById, pantryById) {
    mutableStateMapOf<String, StorageLocation>().apply {
      checkedCatalogItems.forEach { item ->
        val smartZone = pantryById[item.ingredientId]?.storageLocation
          ?: catalogById[item.ingredientId]?.defaultStorageLocation
          ?: defaultStorageLocationFor(item.category, item.tier)
        put(item.ingredientId, smartZone)
      }
    }
  }

  // Initialize checked custom items: culinary items default to Stock in Pantry, paper goods to Clear Note Only.
  val promoteCustomById = remember(checkedCustomItems) {
    mutableStateMapOf<String, Boolean>().apply {
      checkedCustomItems.forEach { custom ->
        put(custom.customId, !unpackSheetLooksLikeSupplyNote(custom.displayName))
      }
    }
  }

  val totalItems = checkedCatalogItems.size + checkedCustomItems.size
  val countSubtitle = if (totalItems == 1) "1 checked item · Choose kitchen zones before stocking"
  else "$totalItems checked items · Choose kitchen zones before stocking"

  Column(
    modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
      .padding(horizontal = 16.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // Header row with ShoppingBag emblem, title, item count summary, and 48.dp Close button.
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Row(modifier = Modifier.weight(1f).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = CircleShape, color = extendedColors.sageContainer, modifier = Modifier.size(40.dp)) {
          Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.ShoppingBag, contentDescription = null, tint = extendedColors.onSageContainer, modifier = Modifier.size(20.dp))
          }
        }
        Column(modifier = Modifier.weight(1f)) {
          Text("Unpack Groceries", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
          Text(countSubtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
      }
      IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Filled.Close, contentDescription = "Close unpack sheet")
      }
    }

    // Section 1: Checked Catalog Items with 1-tap `Fridge · Pantry · Freezer` destination pills.
    if (checkedCatalogItems.isNotEmpty()) {
      Text("Pantry Stock · ${checkedCatalogItems.size}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
      checkedCatalogItems.forEach { item ->
        val selectedZone = zoneByIngredientId[item.ingredientId] ?: defaultStorageLocationFor(item.category, item.tier)
        val shelfDays = unpackSheetEstimateDays(item, catalogById[item.ingredientId], selectedZone)
        unpackSheetCatalogItemCard(
          item = item, selectedZone = selectedZone, estimatedShelfLifeDays = shelfDays,
          onSelectZone = { nextZone -> zoneByIngredientId[item.ingredientId] = nextZone },
        )
      }
    }

    // Section 2: Checked Custom Shopping Notes (`Stock in Pantry` vs `Clear Note Only`).
    if (checkedCustomItems.isNotEmpty()) {
      if (checkedCatalogItems.isNotEmpty()) HorizontalDivider(color = extendedColors.borderSubtle)
      Text("Custom Notes · ${checkedCustomItems.size}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
      checkedCustomItems.forEach { custom ->
        val stockInPantry = promoteCustomById[custom.customId] == true
        unpackSheetCustomItemCard(
          custom = custom, stockInPantry = stockInPantry,
          onSelectStockMode = { shouldPromote -> promoteCustomById[custom.customId] = shouldPromote },
        )
      }
    }

    // Primary 52.dp Confirm Unpack CTA committing all directives in 1 O(1) atomic write.
    Button(
      onClick = {
        // Build per-item UnpackItemDirective list carrying explicit target zone and shelf-life days.
        val directives = checkedCatalogItems.map { item ->
          val targetZone = zoneByIngredientId[item.ingredientId] ?: defaultStorageLocationFor(item.category, item.tier)
          val shelfLifeDays = unpackSheetEstimateDays(item, catalogById[item.ingredientId], targetZone)
          UnpackItemDirective(
            ingredientId = item.ingredientId, restockQuantity = item.restockQuantity,
            targetLocation = targetZone,
            overrideShelfLifeDays = if (item.tier == IngredientTier.TIER_1_QUANTITATIVE) shelfLifeDays else null,
          )
        }
        // Partition custom notes into Pantry-promoted items vs non-food notes cleared from cart only.
        val promotedIds = linkedSetOf<String>()
        val clearOnlyIds = linkedSetOf<String>()
        checkedCustomItems.forEach { custom ->
          if (promoteCustomById[custom.customId] == true) promotedIds.add(custom.customId)
          else clearOnlyIds.add(custom.customId)
        }
        onConfirmUnpack(directives, promotedIds, clearOnlyIds)
      },
      enabled = totalItems > 0, shape = CircleShape,
      colors = ButtonDefaults.buttonColors(containerColor = extendedColors.herbalSage, contentColor = Color.White),
      modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp),
    ) {
      Icon(Icons.Filled.Inventory2, contentDescription = null, modifier = Modifier.size(18.dp))
      Spacer(modifier = Modifier.width(8.dp))
      Text("Confirm Unpack", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }

    // Secondary 48.dp Cancel button dismissing the sheet without altering checked items.
    OutlinedButton(
      onClick = onDismiss, shape = CircleShape,
      modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).padding(bottom = 12.dp),
    ) { Text("Cancel", maxLines = 1, softWrap = false) }
  }
}

// Per-item card letting shoppers flip destination zone (`Fridge · Pantry · Freezer`) in 1 tap.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun unpackSheetCatalogItemCard(
  item: GroceryListItem,
  selectedZone: StorageLocation,
  estimatedShelfLifeDays: Int,
  onSelectZone: (StorageLocation) -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Reflect 90d+ freezer extension immediately on the freshness badge when Freezer is tapped.
  val shelfBadge = when (item.tier) {
    IngredientTier.TIER_2_STAPLE -> "Plenty"
    IngredientTier.TIER_1_QUANTITATIVE -> "${estimatedShelfLifeDays}d fresh"
  }

  Card(
    modifier = Modifier.fillMaxWidth(), shape = KitchenTheme.shapes.card2xl,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
          Text(item.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
          Text(humanizeGroceryPackageLabel(item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Surface(shape = CircleShape, color = extendedColors.sageContainer) {
          Text(shelfBadge, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = extendedColors.onSageContainer, maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp))
        }
      }

      // 48.dp 1-tap `Fridge · Pantry · Freezer` pills resilient on 360dp at 1.35x font scale.
      FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StorageLocation.entries.forEach { zone ->
          val isSelected = selectedZone == zone
          Surface(
            shape = CircleShape,
            color = if (isSelected) extendedColors.herbalSage else extendedColors.parchment,
            border = if (isSelected) null else BorderStroke(1.dp, extendedColors.borderSubtle),
            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).clip(CircleShape).clickable { onSelectZone(zone) },
          ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
              Text(
                text = unpackSheetZoneLabel(zone), style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold, color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                maxLines = 1, softWrap = false,
              )
            }
          }
        }
      }
    }
  }
}

// Per-note card offering `Stock in Pantry` vs `Clear Note Only` for checked CustomGroceryItems.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun unpackSheetCustomItemCard(
  custom: CustomGroceryItem,
  stockInPantry: Boolean,
  onSelectStockMode: (Boolean) -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  Card(
    modifier = Modifier.fillMaxWidth(), shape = KitchenTheme.shapes.card2xl,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(custom.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
      Text(custom.quantityNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
      FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(true to "Stock in Pantry", false to "Clear Note Only").forEach { (modeFlag, modeLabel) ->
          val selected = stockInPantry == modeFlag
          Surface(
            shape = CircleShape,
            color = if (selected) extendedColors.herbalSage else extendedColors.parchment,
            border = if (selected) null else BorderStroke(1.dp, extendedColors.borderSubtle),
            modifier = Modifier.defaultMinSize(minHeight = 48.dp).clip(CircleShape).clickable { onSelectStockMode(modeFlag) },
          ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
              Text(modeLabel, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false)
            }
          }
        }
      }
    }
  }
}

// Concise single-word storage zone names for compact 360dp pill strips.
private fun unpackSheetZoneLabel(zone: StorageLocation): String =
  when (zone) {
    StorageLocation.FRIDGE -> "Fridge"
    StorageLocation.PANTRY -> "Pantry"
    StorageLocation.FREEZER -> "Freezer"
  }

// Compute expected shelf-life days for `zone`, extending freezer storage to at least 90 days.
private fun unpackSheetEstimateDays(
  item: GroceryListItem,
  catalogItem: IngredientCatalogItem?,
  zone: StorageLocation,
): Int {
  if (catalogItem != null) return defaultShelfLifeDaysForZone(catalogItem, zone)
  val baseline = defaultShelfLifeDaysFor(item.category, item.tier)
  return when (zone) {
    StorageLocation.FREEZER -> maxOf(90, baseline)
    else -> baseline
  }
}

// Detect household paper/cleaning supplies so they default to Clear Note Only instead of Pantry stock.
private fun unpackSheetLooksLikeSupplyNote(displayName: String): Boolean {
  val lower = displayName.lowercase()
  return UNPACK_NON_FOOD_KEYWORDS.any { keyword -> lower.contains(keyword) }
}

@Preview(name = "Unpack Groceries Sheet — Warm Daylight", showBackground = true, widthDp = 360)
@Composable
private fun UnpackGroceriesSheetDaylightPreview() {
  // Verifies Fridge · Pantry · Freezer zone pills and Stock in Pantry vs Clear Note Only toggles.
  val checkedCatalog = listOf(
    GroceryListItem("ing_chicken", "Free-Range Chicken Thighs", IngredientCategory.PROTEIN, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 2000.0, 0.0, 2000.0, 1000.0, 2, 2000.0, estimatedCostUsd = 460.0, packageDescriptor = "2 packs · 2 kg", isChecked = true, marketVenue = MarketVenue.WET_MARKET),
    GroceryListItem("ing_milk", "Whole Fresh Milk", IngredientCategory.DAIRY_EGGS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.MILLILITER, 1000.0, 0.0, 1000.0, 1000.0, 1, 1000.0, estimatedCostUsd = 115.0, packageDescriptor = "1 carton · 1 L", isChecked = true, marketVenue = MarketVenue.SUPERMARKET),
  )
  val checkedCustom = listOf(
    CustomGroceryItem("custom_kulitis", "Fresh Kulitis", "2 bunches", IngredientCategory.PRODUCE, isChecked = true),
    CustomGroceryItem("custom_towels", "Kitchen Paper Towels", "2 rolls", IngredientCategory.PANTRY_STAPLE, isChecked = true),
  )
  FamilyKitchenBoardTheme(darkTheme = false) {
    Surface(color = MaterialTheme.colorScheme.background) {
      UnpackGroceriesSheetContent(
        checkedCatalogItems = checkedCatalog, checkedCustomItems = checkedCustom,
        onConfirmUnpack = { _, _, _ -> }, onDismiss = {},
      )
    }
  }
}

@Preview(name = "Unpack Groceries Sheet — Counter Dark", showBackground = true, widthDp = 412)
@Composable
private fun UnpackGroceriesSheetDarkPreview() {
  // Verifies Counter Dark contrast and 52.dp Confirm Unpack CTA.
  val checkedCatalog = listOf(
    GroceryListItem("ing_salmon", "Atlantic Salmon Fillets", IngredientCategory.PROTEIN, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 800.0, 0.0, 800.0, 800.0, 1, 800.0, estimatedCostUsd = 1120.0, packageDescriptor = "1 pack · 800 g", isChecked = true, marketVenue = MarketVenue.WET_MARKET),
  )
  FamilyKitchenBoardTheme(darkTheme = true) {
    Surface(color = MaterialTheme.colorScheme.background) {
      UnpackGroceriesSheetContent(
        checkedCatalogItems = checkedCatalog, checkedCustomItems = emptyList(),
        onConfirmUnpack = { _, _, _ -> }, onDismiss = {},
      )
    }
  }
}
