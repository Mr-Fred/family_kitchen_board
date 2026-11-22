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
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

// Material 3 modal bottom sheets and skipped-items restore banner for editing Shop buy quantities,
// custom shopping items, and global household ingredient names/package details across Shop & Pantry.

// Unit toggle options letting shoppers type exact buy amounts in kg/g, L/ml, or count.
private enum class BuyDisplayUnit(val chipLabel: String, val baseMultiplier: Double) {
  KILOGRAM("kg", 1000.0), GRAM("g", 1.0), LITER("L", 1000.0), MILLILITER("ml", 1.0), COUNT("count", 1.0),
}

/**
 * Compact warm notice banner surfaced in `SmartGroceryScreen` whenever `skippedCount > 0` items
 * have been skipped for the current shopping trip, offering a 1-tap `48.dp` `Restore` action.
 */
@Composable
fun SkippedGroceryNoticeBanner(skippedCount: Int, onRestoreClick: () -> Unit, modifier: Modifier = Modifier) {
  // Omit the banner cleanly when zero shopping items are currently skipped.
  if (skippedCount <= 0) return
  val extendedColors = KitchenTheme.extendedColors
  val countLabel = if (skippedCount == 1) "1 item skipped on this trip" else "$skippedCount items skipped on this trip"

  // Weighted left label + non-wrapping 48.dp Restore pill keeps 360dp 1.35x layouts clip-free.
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
 * Material 3 [ModalBottomSheet] letting shoppers adjust buy quantity (`packs` or exact `kg`/`g`,
 * `L`/`ml`, `count`), rename the ingredient globally, edit store pack size/price, skip for this
 * trip, or restock immediately.
 */
@Composable
fun EditGroceryCatalogItemSheet(
  item: GroceryListItem, catalogItem: IngredientCatalogItem? = null, currencySymbol: String = "₱",
  onDispatchEditAction: (GroceryAndCatalogEditAction) -> Unit, onQuickRestockOne: () -> Unit = {},
  onDismiss: () -> Unit = {}, modifier: Modifier = Modifier,
) = SavorEditSheetContainer(onDismiss = onDismiss, modifier = modifier) {
  // Delegate to stateless inner content so Compose @Preview renders without a modal window host.
  EditGroceryCatalogItemSheetContent(item, catalogItem, currencySymbol, onDispatchEditAction, onQuickRestockOne, onDismiss)
}

/** Stateless inner content of [EditGroceryCatalogItemSheet] for direct `@Preview` rendering. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditGroceryCatalogItemSheetContent(
  item: GroceryListItem, catalogItem: IngredientCatalogItem? = null, currencySymbol: String = "₱",
  onDispatchEditAction: (GroceryAndCatalogEditAction) -> Unit, onQuickRestockOne: () -> Unit = {},
  onDismiss: () -> Unit = {}, modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  val availableUnits = availableUnitsFor(item.baseUnit)
  val initialUnit = initialUnitFor(item.baseUnit, item.restockQuantity)
  val initialPackQty = (catalogItem?.defaultPackageQuantity ?: item.defaultPackageQuantity).coerceAtLeast(1.0)
  // Derive per-pack baseline price safely even if catalogItem is omitted by standalone callers.
  val fallbackPrice = if (item.packagesToBuy > 0) item.estimatedCostUsd / item.packagesToBuy.toDouble() else item.estimatedCostUsd
  val initialPackPrice = (catalogItem?.estimatedPackagePriceUsd ?: fallbackPrice).coerceAtLeast(0.0)

  // Key editable state on ingredientId and restock/override state so Reset to Plan syncs cleanly.
  var nameInput by rememberSaveable(item.ingredientId) { mutableStateOf(catalogItem?.displayName ?: item.displayName) }
  var selectedUnitName by rememberSaveable(item.ingredientId, item.isQuantityOverridden) { mutableStateOf(initialUnit.name) }
  val selectedUnit = availableUnits.find { it.name == selectedUnitName } ?: initialUnit
  var buyAmountInput by rememberSaveable(item.ingredientId, item.restockQuantity, item.isQuantityOverridden) {
    mutableStateOf(formatEditableDecimal(item.restockQuantity / initialUnit.baseMultiplier))
  }
  var packSizeInput by rememberSaveable(item.ingredientId) { mutableStateOf(formatEditableDecimal(initialPackQty)) }
  var packPriceInput by rememberSaveable(item.ingredientId) { mutableStateOf(formatEditableDecimal(initialPackPrice)) }

  // Recompute canonical base quantity, whole-pack count (1..99), and prorated cost live on keystrokes.
  val effectivePackQty = parsePositiveDouble(packSizeInput) ?: initialPackQty
  val effectivePackPrice = parseNonNegativeDouble(packPriceInput) ?: initialPackPrice
  val parsedBuyAmount = parsePositiveDouble(buyAmountInput)
  val resolvedBaseBuyQty = parsedBuyAmount?.times(selectedUnit.baseMultiplier) ?: item.restockQuantity
  val derivedPacks = ceil(resolvedBaseBuyQty / effectivePackQty).toInt().coerceIn(1, 99)
  val liveEstimatedCost = (resolvedBaseBuyQty / effectivePackQty) * effectivePackPrice
  val baselineDeficitQty = if (item.netDeficit > 0.0) item.netDeficit else item.plannedRestockQuantity
  val isLocallyModified = abs(resolvedBaseBuyQty - item.plannedRestockQuantity) >= 0.01
  val canSave = nameInput.isNotBlank() && parsedBuyAmount != null &&
    parsePositiveDouble(packSizeInput) != null && parseNonNegativeDouble(packPriceInput) != null

  ScrollableSheetColumn(modifier = modifier) {
    SheetTitleRow("Edit Shopping Item", "${item.displayName} — ${humanizeGroceryPackageLabel(item)}", onDismiss)

    // Section 1: Buy Quantity on This Trip (Weekly deficit baseline + Pack Stepper + Exact Unit Input).
    Card(
      modifier = Modifier.fillMaxWidth(), shape = KitchenTheme.shapes.card2xl,
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
      border = BorderStroke(1.dp, extendedColors.borderSubtle),
    ) {
      Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.Center) {
          Column(modifier = Modifier.padding(end = 8.dp)) {
            Text("Buy Quantity on This Trip", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("Weekly plan deficit: ${formatHumanSheetQty(baselineDeficitQty, item.baseUnit)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          if (item.isQuantityOverridden || isLocallyModified) {
            TextButton(
              onClick = {
                // Restore planned baseline in local input and clear any persisted override in 1 tap.
                val resetUnit = initialUnitFor(item.baseUnit, item.plannedRestockQuantity)
                selectedUnitName = resetUnit.name
                buyAmountInput = formatEditableDecimal(item.plannedRestockQuantity / resetUnit.baseMultiplier)
                if (item.isQuantityOverridden) onDispatchEditAction(GroceryAndCatalogEditAction.ResetGroceryItemOverride(item.ingredientId))
              },
              modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) { Text("Reset to Plan", color = extendedColors.herbalSage, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false) }
          }
        }

        // 48.dp whole-pack stepper (1..99 packs) updating exact buy input and live cost preview.
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
                buyAmountInput = formatEditableDecimal(nextBase / selectedUnit.baseMultiplier)
              },
              enabled = derivedPacks > 1, modifier = Modifier.size(48.dp),
            ) { Icon(Icons.Filled.Remove, contentDescription = "Decrease packs") }

            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
              val packWord = if (derivedPacks == 1) "1 pack" else "$derivedPacks packs"
              Text("$packWord · ${formatHumanSheetQty(resolvedBaseBuyQty, item.baseUnit)}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
              Text("Est. ${formatGroceryCurrency(liveEstimatedCost, currencySymbol)}", style = MaterialTheme.typography.labelSmall, color = extendedColors.herbalSage, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            FilledTonalIconButton(
              onClick = {
                val nextBase = ((derivedPacks + 1).coerceIn(1, 99)).toDouble() * effectivePackQty
                buyAmountInput = formatEditableDecimal(nextBase / selectedUnit.baseMultiplier)
              },
              enabled = derivedPacks < 99, modifier = Modifier.size(48.dp),
            ) { Icon(Icons.Filled.Add, contentDescription = "Increase packs") }
          }
        }

        // Exact buy amount field paired with 48.dp unit toggle chips (kg/g, L/ml, or count).
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          OutlinedTextField(
            value = buyAmountInput, onValueChange = { buyAmountInput = it }, singleLine = true,
            label = { Text("Exact Buy (${selectedUnit.chipLabel})", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
            modifier = Modifier.weight(1f),
          )
          availableUnits.forEach { unitOption ->
            val isSelected = selectedUnit == unitOption
            UnitTogglePill(unitOption.chipLabel, isSelected) {
              if (!isSelected) {
                // Rescale current canonical quantity smoothly into the newly chosen display unit.
                val currentBase = parsedBuyAmount?.times(selectedUnit.baseMultiplier) ?: item.restockQuantity
                selectedUnitName = unitOption.name
                buyAmountInput = formatEditableDecimal(currentBase / unitOption.baseMultiplier)
              }
            }
          }
        }
      }
    }

    // Section 2: Rename Ingredient & Store Pack Details (propagates across Shop, Pantry, Plan, Recipes).
    Card(
      modifier = Modifier.fillMaxWidth(), shape = KitchenTheme.shapes.card2xl,
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
      border = BorderStroke(1.dp, extendedColors.borderSubtle),
    ) {
      Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Rename Ingredient & Store Pack Details", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        GlobalIngredientNameField(nameInput, { nameInput = it }, "Updates this ingredient's name across Shop, Pantry, Plan, and Recipes")
        PackSizeAndPriceFieldsRow(packSizeInput, { packSizeInput = it }, packPriceInput, { packPriceInput = it }, item.baseUnit.wireCode, currencySymbol)
      }
    }

    // Primary 52.dp Herbal Sage Save Changes CTA + 48.dp secondary Skip / Restock Now actions.
    PrimarySageSaveButton("Save Changes", Icons.Filled.Edit, canSave, onClick = {
      val overrideQty = if (abs(resolvedBaseBuyQty - item.plannedRestockQuantity) >= 0.01) resolvedBaseBuyQty else null
      onDispatchEditAction(
        GroceryAndCatalogEditAction.SaveGroceryCatalogItemEdit(
          item.ingredientId, nameInput.trim(), overrideQty, effectivePackQty, effectivePackPrice,
        ),
      )
      onDismiss()
    })

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
) = SavorEditSheetContainer(onDismiss = onDismiss, modifier = modifier) {
  // Delegate to stateless inner content so Compose @Preview renders cleanly without modal state.
  EditCustomGroceryItemSheetContent(item, onDispatchEditAction, onDismiss)
}

/** Stateless inner content of [EditCustomGroceryItemSheet] for `@Preview` rendering. */
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

  ScrollableSheetColumn(modifier = modifier) {
    SheetTitleRow("Edit Custom Shopping Item", item.displayName, onDismiss)
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
    AisleCategoryChipSelector(selected = selectedCategory, onSelect = { selectedCategoryName = it.name })
    HorizontalDivider(color = extendedColors.borderSubtle)

    // Persist trimmed custom item name, fallback quantity note, and chosen supermarket aisle.
    PrimarySageSaveButton("Save Changes", Icons.Filled.Edit, nameInput.isNotBlank(), onClick = {
      val updated = item.copy(
        displayName = nameInput.trim(),
        quantityNote = quantityNoteInput.trim().ifEmpty { "1 item" },
        category = selectedCategory,
      )
      onDispatchEditAction(GroceryAndCatalogEditAction.SaveCustomGroceryItemEdit(updated))
      onDismiss()
    })

    // Destructive terracotta outline button removing this custom item in 1 atomic write.
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
 * [IngredientCatalogItem] to a familiar local name, change its aisle, or update pack size & price.
 */
@Composable
fun EditCatalogIngredientSheet(
  catalogItem: IngredientCatalogItem, currencySymbol: String = "₱",
  onDispatchEditAction: (GroceryAndCatalogEditAction) -> Unit, onDismiss: () -> Unit = {}, modifier: Modifier = Modifier,
) = SavorEditSheetContainer(onDismiss = onDismiss, modifier = modifier) {
  // Render stateless Pantry ingredient editor inside the warm linen ModalBottomSheet container.
  EditCatalogIngredientSheetContent(catalogItem, currencySymbol, onDispatchEditAction, onDismiss)
}

/** Stateless inner content of [EditCatalogIngredientSheet] for `@Preview` rendering. */
@Composable
fun EditCatalogIngredientSheetContent(
  catalogItem: IngredientCatalogItem, currencySymbol: String = "₱",
  onDispatchEditAction: (GroceryAndCatalogEditAction) -> Unit, onDismiss: () -> Unit = {}, modifier: Modifier = Modifier,
) {
  var nameInput by rememberSaveable(catalogItem.ingredientId) { mutableStateOf(catalogItem.displayName) }
  var selectedCategoryName by rememberSaveable(catalogItem.ingredientId) { mutableStateOf(catalogItem.category.name) }
  val selectedCategory = IngredientCategory.entries.find { it.name == selectedCategoryName } ?: catalogItem.category
  var packSizeInput by rememberSaveable(catalogItem.ingredientId) { mutableStateOf(formatEditableDecimal(catalogItem.defaultPackageQuantity)) }
  var packPriceInput by rememberSaveable(catalogItem.ingredientId) { mutableStateOf(formatEditableDecimal(catalogItem.estimatedPackagePriceUsd)) }
  val parsedPackQty = parsePositiveDouble(packSizeInput)
  val parsedPrice = parseNonNegativeDouble(packPriceInput)

  ScrollableSheetColumn(modifier = modifier) {
    SheetTitleRow("Rename & Edit Ingredient", "${catalogItem.displayName} · ${catalogItem.category.displayLabel}", onDismiss)
    GlobalIngredientNameField(nameInput, { nameInput = it }, "Updates this ingredient's name across Pantry, Shop, Plan, and Recipes")
    AisleCategoryChipSelector(selected = selectedCategory, onSelect = { selectedCategoryName = it.name })
    PackSizeAndPriceFieldsRow(packSizeInput, { packSizeInput = it }, packPriceInput, { packPriceInput = it }, catalogItem.baseUnit.wireCode, currencySymbol)
    Spacer(modifier = Modifier.size(2.dp))
    // Commit renamed catalog label, aisle category, and store pack details in 1 O(1) snapshot write.
    PrimarySageSaveButton(
      label = "Save Changes", icon = Icons.Filled.Inventory2,
      enabled = nameInput.isNotBlank() && parsedPackQty != null && parsedPrice != null,
      modifier = Modifier.padding(bottom = 12.dp),
      onClick = {
        onDispatchEditAction(
          GroceryAndCatalogEditAction.UpdateCatalogIngredientDetails(
            catalogItem.ingredientId, nameInput.trim(), selectedCategory, parsedPackQty, parsedPrice,
          ),
        )
        onDismiss()
      },
    )
  }
}

// Shared skip-partially-expanded Material 3 ModalBottomSheet container in warm SavorAI linen.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavorEditSheetContainer(onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.background, modifier = modifier, content = content)
}

@Composable
private fun ScrollableSheetColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  // Combine verticalScroll + imePadding so 360dp 1.35x layouts stay accessible above soft keyboards.
  Column(
    modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 16.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp), content = content,
  )
}

@Composable
private fun SheetTitleRow(title: String, subtitle: String, onClose: () -> Unit) {
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
      Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Close, contentDescription = "Close sheet") }
  }
}

@Composable
private fun GlobalIngredientNameField(value: String, onValueChange: (String) -> Unit, note: String) {
  OutlinedTextField(
    value = value, onValueChange = onValueChange, singleLine = true,
    label = { Text("Ingredient Name", maxLines = 1, overflow = TextOverflow.Ellipsis) },
    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
    supportingText = { Text(note) }, modifier = Modifier.fillMaxWidth(),
  )
}

@Composable
private fun PackSizeAndPriceFieldsRow(
  packSizeInput: String, onPackSizeChange: (String) -> Unit,
  packPriceInput: String, onPackPriceChange: (String) -> Unit,
  unitWireCode: String, currencySymbol: String,
) {
  // Compact 2-column numeric row editing default store package size and household currency price.
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    OutlinedTextField(
      value = packSizeInput, onValueChange = onPackSizeChange, singleLine = true,
      label = { Text("Store Pack ($unitWireCode)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
      modifier = Modifier.weight(1f),
    )
    OutlinedTextField(
      value = packPriceInput, onValueChange = onPackPriceChange, singleLine = true,
      label = { Text("Pack Price ($currencySymbol)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
      modifier = Modifier.weight(1f),
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AisleCategoryChipSelector(selected: IngredientCategory, onSelect: (IngredientCategory) -> Unit) {
  Text("Store Aisle", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
  // FlowRow wraps 48.dp aisle chips cleanly across multiple rows on compact 360dp phones.
  FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    IngredientCategory.entries.forEach { category ->
      FilterChip(
        selected = selected == category, onClick = { onSelect(category) },
        label = { Text(category.displayLabel, maxLines = 1) }, modifier = Modifier.defaultMinSize(minHeight = 48.dp),
      )
    }
  }
}

@Composable
private fun PrimarySageSaveButton(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val extendedColors = KitchenTheme.extendedColors
  // Primary 52.dp Herbal Sage pill button ensuring thumb-friendly confirmation on 360dp phones.
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

@Composable
private fun UnitTogglePill(label: String, selected: Boolean, onClick: () -> Unit) {
  val extendedColors = KitchenTheme.extendedColors
  // Tactile 48.dp pill letting shoppers flip between kg/g or L/ml in 1 tap.
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

// Maps canonical BaseUnit to shopper-friendly exact-buy unit toggle options.
private fun availableUnitsFor(baseUnit: BaseUnit): List<BuyDisplayUnit> = when (baseUnit) {
  BaseUnit.GRAM -> listOf(BuyDisplayUnit.KILOGRAM, BuyDisplayUnit.GRAM)
  BaseUnit.MILLILITER -> listOf(BuyDisplayUnit.LITER, BuyDisplayUnit.MILLILITER)
  BaseUnit.COUNT -> listOf(BuyDisplayUnit.COUNT)
}

// Defaults >= 1000 g/ml items to kg/L so 4000 g Chicken Thighs opens directly as "4" kg.
private fun initialUnitFor(baseUnit: BaseUnit, quantity: Double): BuyDisplayUnit = when {
  baseUnit == BaseUnit.GRAM && quantity >= 1000.0 -> BuyDisplayUnit.KILOGRAM
  baseUnit == BaseUnit.GRAM -> BuyDisplayUnit.GRAM
  baseUnit == BaseUnit.MILLILITER && quantity >= 1000.0 -> BuyDisplayUnit.LITER
  baseUnit == BaseUnit.MILLILITER -> BuyDisplayUnit.MILLILITER
  else -> BuyDisplayUnit.COUNT
}

// Formats g/ml >= 1000 into kg/L with up to 3 decimal digits for crisp deficit and pack labels.
private fun formatHumanSheetQty(quantity: Double, unit: BaseUnit): String = when {
  unit == BaseUnit.GRAM && quantity >= 1000.0 -> "${formatEditableDecimal(quantity / 1000.0)} kg"
  unit == BaseUnit.MILLILITER && quantity >= 1000.0 -> "${formatEditableDecimal(quantity / 1000.0)} L"
  else -> "${formatEditableDecimal(quantity)} ${unit.wireCode}"
}

// Pure-Kotlin decimal formatter avoiding scientific notation and locale comma drift.
private fun formatEditableDecimal(value: Double): String {
  val clamped = value.coerceAtLeast(0.0)
  val whole = clamped.roundToLong()
  if (abs(clamped - whole.toDouble()) < 0.0005) return whole.toString()
  val millis = (clamped * 1000.0).roundToLong()
  val fracPart = (millis % 1000L).toString().padStart(3, '0').trimEnd('0')
  return if (fracPart.isEmpty()) (millis / 1000L).toString() else "${millis / 1000L}.$fracPart"
}

// Accepts both period and comma decimal separators for en-US and fr-FR soft keyboards.
private fun parsePositiveDouble(raw: String): Double? =
  raw.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }

// Parses non-negative store package prices while guarding against NaN or Infinity inputs.
private fun parseNonNegativeDouble(raw: String): Double? =
  raw.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }

@Preview(name = "Grocery Edit Sheet — Warm Daylight", showBackground = true, widthDp = 360)
@Composable
private fun GroceryEditSheetDaylightPreview() {
  // Showcase compact 360dp Warm Daylight editing for 4 packs -> 2 packs (2 kg) Chicken Thighs.
  val chickenItem = GroceryListItem(
    "ing_chicken", "Free-Range Chicken Thighs", IngredientCategory.PROTEIN, IngredientTier.TIER_1_QUANTITATIVE,
    BaseUnit.GRAM, 4000.0, 0.0, 4000.0, 1000.0, 2, 2000.0, estimatedCostUsd = 460.0,
    packageDescriptor = "2 packs (2000 g)", isQuantityOverridden = true, plannedRestockQuantity = 4000.0,
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
  // Showcase Ambient Counter Dark editing for Pantry catalog items and custom shopping notes.
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
