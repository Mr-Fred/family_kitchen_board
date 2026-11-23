package com.familykitchen.board.ui.grocery

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.BaseUnit
import com.familykitchen.board.domain.CustomGroceryItem
import com.familykitchen.board.domain.GroceryAndCatalogEditAction
import com.familykitchen.board.domain.GroceryListItem
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.IngredientCategory
import com.familykitchen.board.domain.IngredientTier
import com.familykitchen.board.domain.StorageLocation
import com.familykitchen.board.domain.defaultShelfLifeDaysForZone
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

// Material 3 modal bottom sheets and skipped-items restore banner for editing Shop buy quantities,
// custom shopping items, and global household ingredient names, zones, shelf-life, and store packs.

// Unit conversion options enabling shoppers to type exact buy weights in kg/g or L/ml.
private enum class EditSheetBuyDisplayUnit(val chipLabel: String, val baseMultiplier: Double) {
  KILOGRAM("kg", 1000.0), GRAM("g", 1.0), LITER("L", 1000.0), MILLILITER("ml", 1.0), COUNT("count", 1.0),
}

// Common household shelf-life presets covering fresh fish (2d), greens (5d), dairy (14d), and freezer (90d).
private val EDIT_SHEET_SHELF_LIFE_PRESETS: List<Int> = listOf(2, 5, 14, 30, 90)

/**
 * Compact warm notice banner surfaced in `SmartGroceryScreen` whenever `skippedCount > 0` items
 * have been skipped for the current shopping trip, offering a 1-tap `48.dp` `Restore` action.
 */
@Composable
fun SkippedGroceryNoticeBanner(skippedCount: Int, onRestoreClick: () -> Unit, modifier: Modifier = Modifier) {
  if (skippedCount <= 0) return
  val extendedColors = KitchenTheme.extendedColors
  // Singular vs plural phrasing keeps trip-skipped item count grammatically natural.
  val countLabel = if (skippedCount == 1) "1 item skipped on this trip" else "$skippedCount items skipped on this trip"

  Surface(
    modifier = modifier.fillMaxWidth(), shape = KitchenTheme.shapes.card2xl,
    color = extendedColors.parchment, border = BorderStroke(1.dp, extendedColors.borderSubtle), shadowElevation = 0.dp,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.Refresh, contentDescription = null, tint = extendedColors.herbalSage, modifier = Modifier.size(18.dp))
        Text(countLabel, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
      }
      Spacer(modifier = Modifier.width(8.dp))
      TextButton(onClick = onRestoreClick, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
        Text("Restore", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = extendedColors.herbalSage, maxLines = 1, softWrap = false)
      }
    }
  }
}

/**
 * Material 3 [ModalBottomSheet] letting shoppers adjust buy quantity (`packs` or exact `kg` · `g` ·
 * `L` · `ml` · `count`), rename the ingredient globally, edit store pack size and price, skip for
 * this trip, or restock immediately.
 */
@Composable
fun EditGroceryCatalogItemSheet(
  item: GroceryListItem, catalogItem: IngredientCatalogItem? = null, currencySymbol: String = "₱",
  onDispatchEditAction: (GroceryAndCatalogEditAction) -> Unit, onQuickRestockOne: () -> Unit = {},
  onDismiss: () -> Unit = {}, modifier: Modifier = Modifier,
) = editSheetModalContainer(onDismiss = onDismiss, modifier = modifier) {
  // Delegate to stateless sheet content so Compose Previews can render without Window hosts.
  EditGroceryCatalogItemSheetContent(item, catalogItem, currencySymbol, onDispatchEditAction, onQuickRestockOne, onDismiss)
}

/**
 * Stateless inner content of [EditGroceryCatalogItemSheet] for direct `@Preview` rendering.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditGroceryCatalogItemSheetContent(
  item: GroceryListItem, catalogItem: IngredientCatalogItem? = null, currencySymbol: String = "₱",
  onDispatchEditAction: (GroceryAndCatalogEditAction) -> Unit, onQuickRestockOne: () -> Unit = {},
  onDismiss: () -> Unit = {}, modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  val availableUnits = editSheetUnitsFor(item.baseUnit)
  val initialUnit = editSheetInitialUnitFor(item.baseUnit, item.restockQuantity)
  val initialPackQty = (catalogItem?.defaultPackageQuantity ?: item.defaultPackageQuantity).coerceAtLeast(1.0)
  val fallbackPrice = if (item.packagesToBuy > 0) item.estimatedCostUsd / item.packagesToBuy.toDouble() else item.estimatedCostUsd
  val initialPackPrice = (catalogItem?.estimatedPackagePriceUsd ?: fallbackPrice).coerceAtLeast(0.0)

  // Saveable draft inputs survive configuration changes and soft-keyboard resizing.
  var nameInput by rememberSaveable(item.ingredientId) { mutableStateOf(catalogItem?.displayName ?: item.displayName) }
  var selectedUnitName by rememberSaveable(item.ingredientId, item.isQuantityOverridden) { mutableStateOf(initialUnit.name) }
  val selectedUnit = availableUnits.find { it.name == selectedUnitName } ?: initialUnit
  var buyAmountInput by rememberSaveable(item.ingredientId, item.restockQuantity, item.isQuantityOverridden) {
    mutableStateOf(editSheetFormatDecimal(item.restockQuantity / initialUnit.baseMultiplier))
  }
  var packSizeInput by rememberSaveable(item.ingredientId) { mutableStateOf(editSheetFormatDecimal(initialPackQty)) }
  var packPriceInput by rememberSaveable(item.ingredientId) { mutableStateOf(editSheetFormatDecimal(initialPackPrice)) }

  // Recompute canonical base quantity, whole-pack count (1..99), and prorated cost live on keystrokes.
  val effectivePackQty = editSheetParsePositiveDouble(packSizeInput) ?: initialPackQty
  val effectivePackPrice = editSheetParseNonNegativeDouble(packPriceInput) ?: initialPackPrice
  val parsedBuyAmount = editSheetParsePositiveDouble(buyAmountInput)
  val resolvedBaseBuyQty = parsedBuyAmount?.times(selectedUnit.baseMultiplier) ?: item.restockQuantity
  val derivedPacks = ceil(resolvedBaseBuyQty / effectivePackQty).toInt().coerceIn(1, 99)
  val liveEstimatedCost = (resolvedBaseBuyQty / effectivePackQty) * effectivePackPrice
  val baselineDeficitQty = if (item.netDeficit > 0.0) item.netDeficit else item.plannedRestockQuantity
  val isLocallyModified = abs(resolvedBaseBuyQty - item.plannedRestockQuantity) >= 0.01
  val canSave = nameInput.isNotBlank() && parsedBuyAmount != null &&
    editSheetParsePositiveDouble(packSizeInput) != null && editSheetParseNonNegativeDouble(packPriceInput) != null

  editSheetScrollableColumn(modifier = modifier) {
    editSheetTitleRow("Edit Shopping Item", "${item.displayName} · ${humanizeGroceryPackageLabel(item)}", onDismiss)

    // Section 1: Buy Quantity on This Trip (Weekly deficit baseline, Pack Stepper, Exact Unit Input).
    Card(
      modifier = Modifier.fillMaxWidth(), shape = KitchenTheme.shapes.card2xl,
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
      border = BorderStroke(1.dp, extendedColors.borderSubtle),
    ) {
      Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.Center) {
          Column(modifier = Modifier.padding(end = 8.dp)) {
            Text("Buy Quantity on This Trip", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("Weekly plan deficit · ${editSheetFormatHumanQty(baselineDeficitQty, item.baseUnit)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          // Surface 1-tap Reset to Plan whenever the shopper overrode the computed weekly buy qty.
          if (item.isQuantityOverridden || isLocallyModified) {
            TextButton(
              onClick = {
                val resetUnit = editSheetInitialUnitFor(item.baseUnit, item.plannedRestockQuantity)
                selectedUnitName = resetUnit.name
                buyAmountInput = editSheetFormatDecimal(item.plannedRestockQuantity / resetUnit.baseMultiplier)
                if (item.isQuantityOverridden) onDispatchEditAction(GroceryAndCatalogEditAction.ResetGroceryItemOverride(item.ingredientId))
              },
              modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) { Text("Reset to Plan", color = extendedColors.herbalSage, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false) }
          }
        }

        // Tactile 48.dp whole-pack stepper updating exact buy weight and live cost simultaneously.
        Surface(
          shape = RoundedCornerShape(16.dp), color = extendedColors.parchment,
          border = BorderStroke(1.dp, extendedColors.borderSubtle), modifier = Modifier.fillMaxWidth(),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
          ) {
            FilledTonalIconButton(
              onClick = {
                val nextBase = ((derivedPacks - 1).coerceIn(1, 99)).toDouble() * effectivePackQty
                buyAmountInput = editSheetFormatDecimal(nextBase / selectedUnit.baseMultiplier)
              },
              enabled = derivedPacks > 1, modifier = Modifier.size(48.dp),
            ) { Icon(Icons.Filled.Remove, contentDescription = "Decrease packs") }

            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
              val packWord = if (derivedPacks == 1) "1 pack" else "$derivedPacks packs"
              Text("$packWord · ${editSheetFormatHumanQty(resolvedBaseBuyQty, item.baseUnit)}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
              Text("Est. ${formatGroceryCurrency(liveEstimatedCost, currencySymbol)}", style = MaterialTheme.typography.labelSmall, color = extendedColors.herbalSage, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            FilledTonalIconButton(
              onClick = {
                val nextBase = ((derivedPacks + 1).coerceIn(1, 99)).toDouble() * effectivePackQty
                buyAmountInput = editSheetFormatDecimal(nextBase / selectedUnit.baseMultiplier)
              },
              enabled = derivedPacks < 99, modifier = Modifier.size(48.dp),
            ) { Icon(Icons.Filled.Add, contentDescription = "Increase packs") }
          }
        }

        // Decimal buy-quantity input paired with 48.dp kg/g or L/ml unit switcher pills.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          OutlinedTextField(
            value = buyAmountInput, onValueChange = { buyAmountInput = it }, singleLine = true,
            label = { Text("Exact Buy · ${selectedUnit.chipLabel}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
            modifier = Modifier.weight(1f),
          )
          availableUnits.forEach { unitOption ->
            val isSelected = selectedUnit == unitOption
            editSheetUnitPill(unitOption.chipLabel, isSelected) {
              if (!isSelected) {
                val currentBase = parsedBuyAmount?.times(selectedUnit.baseMultiplier) ?: item.restockQuantity
                selectedUnitName = unitOption.name
                buyAmountInput = editSheetFormatDecimal(currentBase / unitOption.baseMultiplier)
              }
            }
          }
        }
      }
    }

    // Section 2: Rename Ingredient and Store Pack Details propagating across Shop, Pantry, Plan, Recipes.
    Card(
      modifier = Modifier.fillMaxWidth(), shape = KitchenTheme.shapes.card2xl,
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
      border = BorderStroke(1.dp, extendedColors.borderSubtle),
    ) {
      Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Rename Ingredient & Store Pack Details", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        editSheetGlobalNameField(nameInput, { nameInput = it }, "Updates this ingredient's name across Shop, Pantry, Plan, and Recipes")
        editSheetPackSizeAndPriceRow(packSizeInput, { packSizeInput = it }, packPriceInput, { packPriceInput = it }, item.baseUnit.wireCode, currencySymbol)
      }
    }

    // Commit global rename, store pack sizing, and trip override in 1 atomic reducer action.
    editSheetPrimarySageButton("Save Changes", Icons.Filled.Edit, canSave, onClick = {
      val overrideQty = if (abs(resolvedBaseBuyQty - item.plannedRestockQuantity) >= 0.01) resolvedBaseBuyQty else null
      onDispatchEditAction(
        GroceryAndCatalogEditAction.SaveGroceryCatalogItemEdit(
          item.ingredientId, nameInput.trim(), overrideQty, effectivePackQty, effectivePackPrice,
        ),
      )
      onDismiss()
    })

    // Secondary 48.dp actions to skip on this trip or restock straight into Pantry.
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton(
        onClick = { onDispatchEditAction(GroceryAndCatalogEditAction.DismissGroceryItemForTrip(item.ingredientId)); onDismiss() },
        shape = CircleShape, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
      ) { Text("Skip This Trip", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }

      OutlinedButton(
        onClick = { onQuickRestockOne(); onDismiss() },
        shape = CircleShape, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
      ) {
        Icon(Icons.Filled.ShoppingBag, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text("Restock Now", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
  }
}

/**
 * Material 3 [ModalBottomSheet] editing a user-added or dictated [CustomGroceryItem] (`displayName`,
 * `quantityNote`, and store aisle `category`), with `Save Changes` and `Delete Item` actions.
 */
@Composable
fun EditCustomGroceryItemSheet(
  item: CustomGroceryItem, onDispatchEditAction: (GroceryAndCatalogEditAction) -> Unit,
  onDismiss: () -> Unit = {}, modifier: Modifier = Modifier,
) = editSheetModalContainer(onDismiss = onDismiss, modifier = modifier) {
  EditCustomGroceryItemSheetContent(item, onDispatchEditAction, onDismiss)
}

/**
 * Stateless inner content of [EditCustomGroceryItemSheet] for `@Preview` rendering.
 */
@Composable
fun EditCustomGroceryItemSheetContent(
  item: CustomGroceryItem, onDispatchEditAction: (GroceryAndCatalogEditAction) -> Unit,
  onDismiss: () -> Unit = {}, modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  var nameInput by rememberSaveable(item.customId) { mutableStateOf(item.displayName) }
  var quantityNoteInput by rememberSaveable(item.customId) { mutableStateOf(item.quantityNote) }
  var selectedCategoryName by rememberSaveable(item.customId) { mutableStateOf(item.category.name) }
  val selectedCategory = IngredientCategory.entries.find { it.name == selectedCategoryName } ?: item.category

  editSheetScrollableColumn(modifier = modifier) {
    editSheetTitleRow("Edit Custom Shopping Item", item.displayName, onDismiss)
    OutlinedTextField(
      value = nameInput, onValueChange = { nameInput = it }, singleLine = true,
      label = { Text("Item Name", maxLines = 1, overflow = TextOverflow.Ellipsis) },
      keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = quantityNoteInput, onValueChange = { quantityNoteInput = it }, singleLine = true,
      label = { Text("Quantity or Note", maxLines = 1, overflow = TextOverflow.Ellipsis) },
      placeholder = { Text("e.g., 2 bunches, 1 roll", maxLines = 1, overflow = TextOverflow.Ellipsis) },
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth(),
    )
    editSheetAisleSelector(selected = selectedCategory, onSelect = { selectedCategoryName = it.name })
    HorizontalDivider(color = extendedColors.borderSubtle)

    editSheetPrimarySageButton("Save Changes", Icons.Filled.Edit, nameInput.isNotBlank(), onClick = {
      val updated = item.copy(
        displayName = nameInput.trim(),
        quantityNote = quantityNoteInput.trim().ifEmpty { "1 item" },
        category = selectedCategory,
      )
      onDispatchEditAction(GroceryAndCatalogEditAction.SaveCustomGroceryItemEdit(updated))
      onDismiss()
    })

    // Terracotta-bordered Delete button removes custom shopping notes cleanly.
    OutlinedButton(
      onClick = { onDispatchEditAction(GroceryAndCatalogEditAction.DeleteCustomGroceryItem(item.customId)); onDismiss() },
      shape = CircleShape, border = BorderStroke(1.dp, extendedColors.terracotta),
      modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).padding(bottom = 12.dp),
    ) {
      Icon(Icons.Filled.Delete, contentDescription = null, tint = extendedColors.terracotta, modifier = Modifier.size(18.dp))
      Spacer(modifier = Modifier.width(6.dp))
      Text("Delete Item", color = extendedColors.terracotta, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
    }
  }
}

/**
 * Material 3 [ModalBottomSheet] opened from **Smart Pantry** so households can rename any
 * [IngredientCatalogItem], change its storage zone (`Fridge · Pantry · Freezer`), adjust shelf-life
 * days, change its aisle, or update store pack size and price.
 */
@Composable
fun EditCatalogIngredientSheet(
  catalogItem: IngredientCatalogItem,
  currencySymbol: String = "₱",
  onDispatchEditAction: (GroceryAndCatalogEditAction) -> Unit,
  onDismiss: () -> Unit = {},
  modifier: Modifier = Modifier,
  currentStorageLocation: StorageLocation = catalogItem.defaultStorageLocation,
  currentShelfLifeDays: Int = catalogItem.defaultShelfLifeDays,
  onMoveItemToZone: ((String, StorageLocation) -> Unit)? = null,
  onUpdateShelfLifeAndZone: ((IngredientCatalogItem, StorageLocation, Int) -> Unit)? = null,
) = editSheetModalContainer(onDismiss = onDismiss, modifier = modifier) {
  EditCatalogIngredientSheetContent(
    catalogItem = catalogItem, currencySymbol = currencySymbol,
    onDispatchEditAction = onDispatchEditAction, onDismiss = onDismiss,
    currentStorageLocation = currentStorageLocation, currentShelfLifeDays = currentShelfLifeDays,
    onMoveItemToZone = onMoveItemToZone, onUpdateShelfLifeAndZone = onUpdateShelfLifeAndZone,
  )
}

/**
 * Stateless inner content of [EditCatalogIngredientSheet] for `@Preview` rendering.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditCatalogIngredientSheetContent(
  catalogItem: IngredientCatalogItem,
  currencySymbol: String = "₱",
  onDispatchEditAction: (GroceryAndCatalogEditAction) -> Unit,
  onDismiss: () -> Unit = {},
  modifier: Modifier = Modifier,
  currentStorageLocation: StorageLocation = catalogItem.defaultStorageLocation,
  currentShelfLifeDays: Int = catalogItem.defaultShelfLifeDays,
  onMoveItemToZone: ((String, StorageLocation) -> Unit)? = null,
  onUpdateShelfLifeAndZone: ((IngredientCatalogItem, StorageLocation, Int) -> Unit)? = null,
) {
  val extendedColors = KitchenTheme.extendedColors
  val safeInitialDays = currentShelfLifeDays.coerceAtLeast(1)
  // Saveable fields preserve edits to name, storage zone, shelf-life days, aisle, and pack price.
  var nameInput by rememberSaveable(catalogItem.ingredientId) { mutableStateOf(catalogItem.displayName) }
  var selectedCategoryName by rememberSaveable(catalogItem.ingredientId) { mutableStateOf(catalogItem.category.name) }
  val selectedCategory = IngredientCategory.entries.find { it.name == selectedCategoryName } ?: catalogItem.category
  var selectedZoneName by rememberSaveable(catalogItem.ingredientId) { mutableStateOf(currentStorageLocation.name) }
  val selectedZone = StorageLocation.entries.find { it.name == selectedZoneName } ?: currentStorageLocation
  var shelfLifeDaysInput by rememberSaveable(catalogItem.ingredientId) { mutableStateOf(safeInitialDays.toString()) }
  var packSizeInput by rememberSaveable(catalogItem.ingredientId) { mutableStateOf(editSheetFormatDecimal(catalogItem.defaultPackageQuantity)) }
  var packPriceInput by rememberSaveable(catalogItem.ingredientId) { mutableStateOf(editSheetFormatDecimal(catalogItem.estimatedPackagePriceUsd)) }
  val parsedPackQty = editSheetParsePositiveDouble(packSizeInput)
  val parsedPrice = editSheetParseNonNegativeDouble(packPriceInput)
  val parsedShelfDays = shelfLifeDaysInput.trim().toIntOrNull()?.takeIf { it in 1..730 }

  editSheetScrollableColumn(modifier = modifier) {
    editSheetTitleRow("Edit Pantry Ingredient", "${catalogItem.displayName} · ${catalogItem.category.displayLabel}", onDismiss)
    editSheetGlobalNameField(nameInput, { nameInput = it }, "Updates this ingredient's name across Pantry, Shop, Plan, and Recipes")

    // Storage Zone selector (`Fridge · Pantry · Freezer`) auto-updating shelf-life days on zone flip.
    Text("Storage Zone · Fridge · Pantry · Freezer", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      StorageLocation.entries.forEach { zone ->
        val isSelected = selectedZone == zone
        Surface(
          shape = CircleShape,
          color = if (isSelected) extendedColors.herbalSage else MaterialTheme.colorScheme.surfaceContainerLow,
          border = if (isSelected) null else BorderStroke(1.dp, extendedColors.borderSubtle),
          modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).clip(CircleShape).clickable {
            selectedZoneName = zone.name
            shelfLifeDaysInput = defaultShelfLifeDaysForZone(catalogItem, zone).toString()
          },
        ) {
          Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(
              text = editSheetShortZoneLabel(zone), style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.Bold, color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
              maxLines = 1, softWrap = false,
            )
          }
        }
      }
    }

    // Shelf-life days numeric editor paired with 1-tap presets (`2d · 5d · 14d · 30d · 90d`).
    OutlinedTextField(
      value = shelfLifeDaysInput,
      onValueChange = { next -> if (next.all { ch -> ch.isDigit() }) shelfLifeDaysInput = next },
      singleLine = true, label = { Text("Shelf Life Days", maxLines = 1, overflow = TextOverflow.Ellipsis) },
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
      modifier = Modifier.fillMaxWidth(),
    )
    FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      EDIT_SHEET_SHELF_LIFE_PRESETS.forEach { presetDays ->
        FilterChip(
          selected = parsedShelfDays == presetDays, onClick = { shelfLifeDaysInput = presetDays.toString() },
          label = { Text("${presetDays}d", maxLines = 1, softWrap = false) },
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        )
      }
    }

    editSheetAisleSelector(selected = selectedCategory, onSelect = { selectedCategoryName = it.name })
    editSheetPackSizeAndPriceRow(packSizeInput, { packSizeInput = it }, packPriceInput, { packPriceInput = it }, catalogItem.baseUnit.wireCode, currencySymbol)
    Spacer(modifier = Modifier.size(2.dp))

    // Dispatch catalog metadata edit and propagate zone relocation or custom shelf-life window.
    editSheetPrimarySageButton(
      label = "Save Changes", icon = Icons.Filled.Inventory2,
      enabled = nameInput.isNotBlank() && parsedPackQty != null && parsedPrice != null && parsedShelfDays != null,
      modifier = Modifier.padding(bottom = 12.dp),
      onClick = {
        val cleanName = nameInput.trim()
        val effectiveDays = parsedShelfDays ?: safeInitialDays
        onDispatchEditAction(
          GroceryAndCatalogEditAction.UpdateCatalogIngredientDetails(
            catalogItem.ingredientId, cleanName, selectedCategory, parsedPackQty, parsedPrice,
          ),
        )
        if (selectedZone != currentStorageLocation) {
          onMoveItemToZone?.invoke(catalogItem.ingredientId, selectedZone)
        }
        if (selectedZone != currentStorageLocation || effectiveDays != safeInitialDays) {
          val updatedModel = catalogItem.copy(
            displayName = cleanName, category = selectedCategory,
            defaultPackageQuantity = parsedPackQty ?: catalogItem.defaultPackageQuantity,
            estimatedPackagePriceUsd = parsedPrice ?: catalogItem.estimatedPackagePriceUsd,
            defaultStorageLocation = selectedZone, defaultShelfLifeDays = effectiveDays,
          )
          onUpdateShelfLifeAndZone?.invoke(updatedModel, selectedZone, effectiveDays)
        }
        onDismiss()
      },
    )
  }
}

// Shared skipPartiallyExpanded ModalBottomSheet host keeping sheet height consistent on compact phones.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun editSheetModalContainer(onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.background, modifier = modifier, content = content)
}

// IME-padded scrollable sheet column preventing soft-keyboard occlusion on numeric fields.
@Composable
private fun editSheetScrollableColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  Column(
    modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 16.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp), content = content,
  )
}

// Sheet header row pairing title + subtitle with an accessible 48.dp Close IconButton.
@Composable
private fun editSheetTitleRow(title: String, subtitle: String, onClose: () -> Unit) {
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
      Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Close, contentDescription = "Close sheet") }
  }
}

// Global ingredient display-name input field with propagation note beneath.
@Composable
private fun editSheetGlobalNameField(value: String, onValueChange: (String) -> Unit, note: String) {
  OutlinedTextField(
    value = value, onValueChange = onValueChange, singleLine = true,
    label = { Text("Ingredient Name", maxLines = 1, overflow = TextOverflow.Ellipsis) },
    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
    supportingText = { Text(note) }, modifier = Modifier.fillMaxWidth(),
  )
}

@Composable
private fun editSheetPackSizeAndPriceRow(
  packSizeInput: String, onPackSizeChange: (String) -> Unit,
  packPriceInput: String, onPackPriceChange: (String) -> Unit,
  unitWireCode: String, currencySymbol: String,
) {
  // Compact 2-column numeric row using middle-dot labels free of slashes or parentheses.
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    OutlinedTextField(
      value = packSizeInput, onValueChange = onPackSizeChange, singleLine = true,
      label = { Text("Store Pack · $unitWireCode", maxLines = 1, overflow = TextOverflow.Ellipsis) },
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
      modifier = Modifier.weight(1f),
    )
    OutlinedTextField(
      value = packPriceInput, onValueChange = onPackPriceChange, singleLine = true,
      label = { Text("Pack Price · $currencySymbol", maxLines = 1, overflow = TextOverflow.Ellipsis) },
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
      modifier = Modifier.weight(1f),
    )
  }
}

// Store aisle FilterChip selector enforcing >= 48.dp touch targets across wrapped rows.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun editSheetAisleSelector(selected: IngredientCategory, onSelect: (IngredientCategory) -> Unit) {
  Text("Store Aisle", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
  FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    IngredientCategory.entries.forEach { category ->
      FilterChip(
        selected = selected == category, onClick = { onSelect(category) },
        label = { Text(category.displayLabel, maxLines = 1) }, modifier = Modifier.defaultMinSize(minHeight = 48.dp),
      )
    }
  }
}

// Primary 52.dp herbal-sage confirmation button shared across all three edit modal sheets.
@Composable
private fun editSheetPrimarySageButton(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val extendedColors = KitchenTheme.extendedColors
  Button(
    onClick = onClick, enabled = enabled, shape = CircleShape,
    colors = ButtonDefaults.buttonColors(containerColor = extendedColors.herbalSage, contentColor = Color.White),
    modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp),
  ) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(modifier = Modifier.width(8.dp))
    Text(label, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
  }
}

// 48.dp unit toggle pill switching between kg/g or L/ml without losing the underlying base quantity.
@Composable
private fun editSheetUnitPill(label: String, selected: Boolean, onClick: () -> Unit) {
  val extendedColors = KitchenTheme.extendedColors
  Surface(
    shape = CircleShape,
    color = if (selected) extendedColors.herbalSage else MaterialTheme.colorScheme.surfaceContainerLow,
    border = if (selected) null else BorderStroke(1.dp, extendedColors.borderSubtle),
    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).clip(CircleShape).clickable(onClick = onClick),
  ) {
    Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
      Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false)
    }
  }
}

// Concise single-word storage zone labels preventing clipping inside 3-column 360dp rows.
private fun editSheetShortZoneLabel(zone: StorageLocation): String =
  when (zone) {
    StorageLocation.FRIDGE -> "Fridge"
    StorageLocation.PANTRY -> "Pantry"
    StorageLocation.FREEZER -> "Freezer"
  }

// Compatible display units for a canonical BaseUnit (`kg` + `g`, `L` + `ml`, or `count`).
private fun editSheetUnitsFor(baseUnit: BaseUnit): List<EditSheetBuyDisplayUnit> = when (baseUnit) {
  BaseUnit.GRAM -> listOf(EditSheetBuyDisplayUnit.KILOGRAM, EditSheetBuyDisplayUnit.GRAM)
  BaseUnit.MILLILITER -> listOf(EditSheetBuyDisplayUnit.LITER, EditSheetBuyDisplayUnit.MILLILITER)
  BaseUnit.COUNT -> listOf(EditSheetBuyDisplayUnit.COUNT)
}

// Pick kg or L automatically when the initial quantity is >= 1000 base units.
private fun editSheetInitialUnitFor(baseUnit: BaseUnit, quantity: Double): EditSheetBuyDisplayUnit = when {
  baseUnit == BaseUnit.GRAM && quantity >= 1000.0 -> EditSheetBuyDisplayUnit.KILOGRAM
  baseUnit == BaseUnit.GRAM -> EditSheetBuyDisplayUnit.GRAM
  baseUnit == BaseUnit.MILLILITER && quantity >= 1000.0 -> EditSheetBuyDisplayUnit.LITER
  baseUnit == BaseUnit.MILLILITER -> EditSheetBuyDisplayUnit.MILLILITER
  else -> EditSheetBuyDisplayUnit.COUNT
}

// Format base quantities into human-friendly kg, L, g, ml, or count strings.
private fun editSheetFormatHumanQty(quantity: Double, unit: BaseUnit): String = when {
  unit == BaseUnit.GRAM && quantity >= 1000.0 -> "${editSheetFormatDecimal(quantity / 1000.0)} kg"
  unit == BaseUnit.MILLILITER && quantity >= 1000.0 -> "${editSheetFormatDecimal(quantity / 1000.0)} L"
  else -> "${editSheetFormatDecimal(quantity)} ${unit.wireCode}"
}

// Format decimal edit field values cleanly up to 3 decimal places without trailing zeros.
private fun editSheetFormatDecimal(value: Double): String {
  val clamped = value.coerceAtLeast(0.0)
  val whole = clamped.roundToLong()
  if (abs(clamped - whole.toDouble()) < 0.0005) return whole.toString()
  val millis = (clamped * 1000.0).roundToLong()
  val fracPart = (millis % 1000L).toString().padStart(3, '0').trimEnd('0')
  return if (fracPart.isEmpty()) (millis / 1000L).toString() else "${millis / 1000L}.$fracPart"
}

// Parse strictly positive finite numbers (`> 0.0`), accepting comma or dot decimals.
private fun editSheetParsePositiveDouble(raw: String): Double? =
  raw.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }

// Parse non-negative finite numbers (`>= 0.0`) for optional zero-cost homegrown items.
private fun editSheetParseNonNegativeDouble(raw: String): Double? =
  raw.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }

@Preview(name = "Grocery Edit Sheet — Warm Daylight", showBackground = true, widthDp = 360)
@Composable
private fun GroceryEditSheetDaylightPreview() {
  // Verifies Buy Quantity stepper, exact kg input, and SkippedGroceryNoticeBanner on 360dp.
  val chickenItem = GroceryListItem(
    "ing_chicken", "Free-Range Chicken Thighs", IngredientCategory.PROTEIN, IngredientTier.TIER_1_QUANTITATIVE,
    BaseUnit.GRAM, 4000.0, 0.0, 4000.0, 1000.0, 2, 2000.0, estimatedCostUsd = 460.0,
    packageDescriptor = "2 packs · 2 kg", isQuantityOverridden = true, plannedRestockQuantity = 4000.0,
  )
  FamilyKitchenBoardTheme(darkTheme = false) {
    Surface(color = MaterialTheme.colorScheme.background) {
      Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SkippedGroceryNoticeBanner(skippedCount = 2, onRestoreClick = {})
        EditGroceryCatalogItemSheetContent(item = chickenItem, onDispatchEditAction = {})
      }
    }
  }
}

@Preview(name = "Pantry & Custom Edit Sheets — Counter Dark", showBackground = true, widthDp = 412)
@Composable
private fun CatalogAndCustomEditSheetDarkPreview() {
  // Verifies Fridge · Pantry · Freezer zone pills and Shelf Life Days editor in Counter Dark.
  val salmon = IngredientCatalogItem(
    "ing_salmon", "Atlantic Salmon Fillets", IngredientCategory.PROTEIN, IngredientTier.TIER_1_QUANTITATIVE,
    BaseUnit.GRAM, 800.0, defaultStorageLocation = StorageLocation.FRIDGE, estimatedPackagePriceUsd = 1120.0,
  )
  FamilyKitchenBoardTheme(darkTheme = true) {
    Surface(color = MaterialTheme.colorScheme.background) {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        EditCatalogIngredientSheetContent(catalogItem = salmon, onDispatchEditAction = {})
        EditCustomGroceryItemSheetContent(CustomGroceryItem("custom_basil", "Fresh Basil", "2 bunches"), {})
      }
    }
  }
}
