package com.familykitchen.board.ui.pantry

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.BaseUnit
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.IngredientCategory
import com.familykitchen.board.domain.IngredientTier
import com.familykitchen.board.domain.LOCAL_MARKET_PRESET_INGREDIENTS
import com.familykitchen.board.domain.StorageLocation
import com.familykitchen.board.domain.defaultShelfLifeDaysFor
import com.familykitchen.board.domain.defaultStorageLocationFor
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme

// Cohesive SavorAI ingredient intake card and Kitchen filter enums.
// Places custom name/package inputs and 48.dp CTAs above live-filtered local market presets.

/**
 * Primary segmented sub-views inside the combined Kitchen wrapper.
 *
 * @property displayLabel Human-readable label shown inside segmented toggles.
 */
enum class PantrySubTab(val displayLabel: String) {
  /** Chronological whole-package rounded shopping checklist (`EC-09`, `EC-11`, `EC-12`). */
  GROCERY_LIST("Grocery"),

  /** Two-tier household stock ledger with expiry alerts, Fridge Pulse, and local market gates. */
  MY_PANTRY("Pantry"),
}

/**
 * Storage-zone and urgency filter tabs rendered along the `SmartPantryScreen` horizontal scroller.
 *
 * @property displayLabel Pill label rendered in the horizontal filter strip.
 */
enum class PantryCategoryFilter(val displayLabel: String) {
  /** Displays all household zones (`Action Required`, `Fridge & Produce`, `Dry Goods`, `Freezer`). */
  ALL("All"),

  /** Focuses strictly on urgent `EXPIRING_SOON` (`1..2d`) and `EXPIRED` (`<= 0d`) stock. */
  EXPIRING("Expiring"),

  /** Filters strictly to [StorageLocation.FRIDGE] perishables. */
  FRIDGE_FRESH("Fridge"),

  /** Filters strictly to [StorageLocation.PANTRY] dry goods, grains, and Tier-2 staples. */
  STAPLES_OILS("Pantry"),

  /** Filters strictly to [StorageLocation.FREEZER] frozen stock. */
  FREEZER("Freezer"),

  /** Highlights local market produce, proteins, and `EC-02`/`EC-03` availability overrides. */
  LOCAL_MARKET("Local Market"),
}

/**
 * Unit and tracking-tier choices exposed in the custom ingredient builder form.
 *
 * @property displayLabel Concise selector chip label.
 * @property tier Underlying [IngredientTier] (`TIER_1_QUANTITATIVE` vs `TIER_2_STAPLE`).
 * @property baseUnit Canonical [BaseUnit] persisted on the created [IngredientCatalogItem].
 * @property suggestedPackageQty Default store package size pre-filled when this unit is selected.
 */
enum class CustomUnitChoice(
  val displayLabel: String,
  val tier: IngredientTier,
  val baseUnit: BaseUnit,
  val suggestedPackageQty: Int,
) {
  /** Quantitative weight tracking in grams (`±100 g` Fridge Pulse step). */
  GRAM("Grams (g)", IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 500),

  /** Quantitative liquid volume tracking in milliliters (`±100 ml` Fridge Pulse step). */
  MILLILITER("Milliliters (ml)", IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.MILLILITER, 500),

  /** Discrete countable unit tracking (`±1 count` Fridge Pulse step). */
  COUNT("Count (items)", IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.COUNT, 6),

  /** Tri-state kitchen staple (`PLENTY | LOW | OUT`, never auto-deducted gram-by-gram). */
  TIER_2_STAPLE("Staple (Plenty/Low/Out)", IngredientTier.TIER_2_STAPLE, BaseUnit.MILLILITER, 250),
}

// Destination routed when a user taps one of the 1-tap local-market preset chips.
private enum class PresetQuickTarget(val label: String) {
  PANTRY("+ Pantry"),
  GROCERY("+ Grocery"),
}

/**
 * Collapsible `"Add Ingredient to Kitchen"` card placing direct name/package inputs and `48.dp`
 * `+ Grocery` / `+ Pantry` CTAs above a live-filtered horizontal 2-row strip of
 * [LOCAL_MARKET_PRESET_INGREDIENTS].
 *
 * @param existingIngredientIds Set of `ingredientId`s already present in the household catalog.
 * @param queuedGroceryIds Set of `ingredientId`s currently queued on the Smart Grocery List.
 * @param isExpanded Whether the custom builder and preset chips are currently expanded.
 * @param onToggleExpanded Callback invoked when the card header is tapped to expand or collapse.
 * @param onAddIngredientToGrocery Commits a preset or custom [IngredientCatalogItem] onto Grocery.
 * @param onAddIngredientToPantry Commits a preset or custom [IngredientCatalogItem] into Pantry.
 * @param onAddIngredientToPantryWithExpiry Optional zone- and expiry-aware Pantry intake callback.
 * @param modifier Optional Compose [Modifier] applied to the outer card container.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AddIngredientCard(
  existingIngredientIds: Set<String>,
  queuedGroceryIds: Set<String>,
  isExpanded: Boolean,
  onToggleExpanded: () -> Unit,
  onAddIngredientToGrocery: (IngredientCatalogItem) -> Unit,
  onAddIngredientToPantry: (IngredientCatalogItem) -> Unit,
  onAddIngredientToPantryWithExpiry: ((IngredientCatalogItem, StorageLocation, Int) -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Derive exact preset count from domain list (20) so subtitle never shows a stale hardcoded number.
  val presetCount = LOCAL_MARKET_PRESET_INGREDIENTS.size
  // Persist enum selections as Bundle-safe String names across Activity/process recreation.
  var presetTargetName by rememberSaveable { mutableStateOf(PresetQuickTarget.PANTRY.name) }
  val presetTarget = PresetQuickTarget.entries.find { it.name == presetTargetName }
    ?: PresetQuickTarget.PANTRY
  var customName by rememberSaveable { mutableStateOf("") }
  var selectedCategoryName by rememberSaveable { mutableStateOf(IngredientCategory.PRODUCE.name) }
  val selectedCategory = IngredientCategory.entries.find { it.name == selectedCategoryName }
    ?: IngredientCategory.PRODUCE
  var selectedUnitChoiceName by rememberSaveable { mutableStateOf(CustomUnitChoice.GRAM.name) }
  val selectedUnitChoice = CustomUnitChoice.entries.find { it.name == selectedUnitChoiceName }
    ?: CustomUnitChoice.GRAM
  var selectedLocationName by rememberSaveable { mutableStateOf(StorageLocation.FRIDGE.name) }
  val selectedLocation = StorageLocation.entries.find { it.name == selectedLocationName }
    ?: StorageLocation.FRIDGE
  // Default freshness window starts at 5 days for Fridge produce and updates when zone changes.
  var expiresInDays by rememberSaveable { mutableIntStateOf(5) }
  var packageQtyInput by rememberSaveable { mutableStateOf("500") }

  // Filter presets dynamically as the user types in the Ingredient Name input above.
  val filteredPresets = remember(customName) {
    val cleanQuery = customName.trim().lowercase()
    when {
      cleanQuery.isEmpty() -> LOCAL_MARKET_PRESET_INGREDIENTS
      else -> LOCAL_MARKET_PRESET_INGREDIENTS.filter { preset ->
        preset.displayName.lowercase().contains(cleanQuery)
      }
    }
  }

  Card(
    modifier = modifier.fillMaxWidth(),
    shape = KitchenTheme.shapes.card2xl,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      // Header bar toggles collapse state with a 48.dp+ touch target.
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .defaultMinSize(minHeight = 56.dp)
          .clickable(onClick = onToggleExpanded)
          .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
          Surface(shape = CircleShape, color = extendedColors.sageContainer) {
            Icon(
              imageVector = Icons.Filled.Add,
              contentDescription = null,
              tint = extendedColors.onSageContainer,
              modifier = Modifier.padding(8.dp),
            )
          }
          Spacer(modifier = Modifier.width(12.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Add Ingredient to Kitchen",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = "$presetCount local presets · Fridge / Pantry / Freezer",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
        Icon(
          imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
          contentDescription = if (isExpanded) "Collapse add ingredient panel" else "Expand add ingredient panel",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      AnimatedVisibility(visible = isExpanded) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          HorizontalDivider(color = extendedColors.borderSubtle)

          // 1. Primary Custom Name + Package Input Row FIRST so users never scroll past 20 chips.
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
              value = customName,
              onValueChange = { customName = it },
              label = { Text("Ingredient Name", maxLines = 1, overflow = TextOverflow.Ellipsis) },
              placeholder = { Text("e.g., Ground Turmeric", maxLines = 1, overflow = TextOverflow.Ellipsis) },
              singleLine = true,
              keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Next,
              ),
              modifier = Modifier.weight(1.5f),
            )
            OutlinedTextField(
              value = packageQtyInput,
              onValueChange = { next -> if (next.all { ch -> ch.isDigit() || ch == '.' }) packageQtyInput = next },
              label = { Text("Pkg (${selectedUnitChoice.baseUnit.wireCode})", maxLines = 1, softWrap = false) },
              keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Done,
              ),
              singleLine = true,
              modifier = Modifier.weight(0.9f),
            )
          }

          // Require non-blank display name and strictly positive finite package quantity before submission.
          val parsedQty = packageQtyInput.toDoubleOrNull() ?: 0.0
          val canSubmitCustom = customName.trim().isNotEmpty() && parsedQty > 0.0

          // 2. Dual 48.dp+ explicit CTAs directly under the input fields.
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Secondary CTA queues the synthesized catalog item onto the Smart Grocery List.
            OutlinedButton(
              onClick = {
                buildCustomCatalogItem(customName, selectedCategory, selectedUnitChoice, parsedQty, selectedLocation, expiresInDays)?.let {
                  onAddIngredientToGrocery(it)
                  customName = ""
                }
              },
              enabled = canSubmitCustom,
              shape = CircleShape,
              modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
            ) {
              Text("+ Grocery", maxLines = 1, softWrap = false)
            }

            // Primary CTA stocks 1 full package directly into the chosen storage zone and expiry window.
            Button(
              onClick = {
                buildCustomCatalogItem(customName, selectedCategory, selectedUnitChoice, parsedQty, selectedLocation, expiresInDays)?.let { built ->
                  onAddIngredientToPantryWithExpiry?.invoke(built, selectedLocation, expiresInDays)
                    ?: onAddIngredientToPantry(built)
                  customName = ""
                }
              },
              enabled = canSubmitCustom,
              shape = CircleShape,
              modifier = Modifier.weight(1.25f).defaultMinSize(minHeight = 48.dp),
            ) {
              Text("+ Pantry (${expiresInDays}d)", maxLines = 1, softWrap = false)
            }
          }

          // 3. Storage Zone, Freshness Window, Category, and Unit chips.
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Selecting Fridge / Pantry / Freezer updates default shelf-life days automatically.
            StorageLocation.entries.forEach { zone ->
              FilterChip(
                selected = selectedLocation == zone,
                onClick = {
                  selectedLocationName = zone.name
                  expiresInDays = when (zone) {
                    StorageLocation.FRIDGE -> 5
                    StorageLocation.PANTRY -> 90
                    StorageLocation.FREEZER -> 90
                  }
                },
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                label = { Text(zone.displayLabel, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false) },
              )
            }
            // Quick 48.dp shelf-life day presets (2d urgent produce through 90d dry/frozen stock).
            listOf(2, 5, 10, 30, 90).forEach { daysOption ->
              FilterChip(
                selected = expiresInDays == daysOption,
                onClick = { expiresInDays = daysOption },
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                label = { Text("${daysOption}d", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false) },
              )
            }
          }

          // Category and tracking-unit chips auto-suggest sensible storage zone and package size.
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            IngredientCategory.entries.forEach { category ->
              FilterChip(
                selected = selectedCategory == category,
                onClick = {
                  selectedCategoryName = category.name
                  selectedLocationName = defaultStorageLocationFor(category, selectedUnitChoice.tier).name
                  expiresInDays = defaultShelfLifeDaysFor(category, selectedUnitChoice.tier)
                },
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                label = { Text(category.displayLabel, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false) },
              )
            }
            // Unit choice determines whether stock is tracked quantitatively (Tier 1) or as a staple (Tier 2).
            CustomUnitChoice.entries.forEach { choice ->
              FilterChip(
                selected = selectedUnitChoice == choice,
                onClick = {
                  selectedUnitChoiceName = choice.name
                  packageQtyInput = choice.suggestedPackageQty.toString()
                  selectedLocationName = defaultStorageLocationFor(selectedCategory, choice.tier).name
                },
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                label = { Text(choice.displayLabel, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false) },
              )
            }
          }

          HorizontalDivider(color = extendedColors.borderSubtle)

          // 4. Live-filtered 2-row horizontal preset strip below inputs (`20` local market presets).
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            // Weight label so elevated font scaling never squeezes the +Pantry / +Grocery target toggle.
            Text(
              text = "${filteredPresets.size} Local Presets",
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.SemiBold,
              modifier = Modifier.weight(1f),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.width(8.dp))
            SingleChoiceSegmentedButtonRow {
              PresetQuickTarget.entries.forEachIndexed { idx, target ->
                SegmentedButton(
                  selected = presetTarget == target,
                  onClick = { presetTargetName = target.name },
                  shape = SegmentedButtonDefaults.itemShape(index = idx, count = PresetQuickTarget.entries.size),
                  modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                  label = { Text(target.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false) },
                )
              }
            }
          }

          // Split presets across 2 horizontally scrollable rows so vertical height stays compact.
          val topRowPresets = filteredPresets.filterIndexed { index, _ -> index % 2 == 0 }
          val bottomRowPresets = filteredPresets.filterIndexed { index, _ -> index % 2 == 1 }
          Column(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            listOf(topRowPresets, bottomRowPresets).filter { it.isNotEmpty() }.forEach { presetRow ->
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presetRow.forEach { preset ->
                  // Highlight presets already stocked in Pantry or queued on the Smart Grocery List.
                  val isQueued = preset.ingredientId in queuedGroceryIds
                  val isKnown = preset.ingredientId in existingIngredientIds
                  // Humanize 1000g/1000ml preset quantities into clean 1 kg / 1 L chip suffixes.
                  val qtyText = formatHumanQuantity(preset.defaultPackageQuantity, preset.baseUnit)
                  AssistChip(
                    onClick = {
                      when (presetTarget) {
                        PresetQuickTarget.GROCERY -> onAddIngredientToGrocery(preset)
                        PresetQuickTarget.PANTRY -> {
                          onAddIngredientToPantryWithExpiry?.invoke(
                            preset,
                            preset.defaultStorageLocation,
                            preset.defaultShelfLifeDays,
                          ) ?: onAddIngredientToPantry(preset)
                        }
                      }
                    },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    label = {
                      Text(
                        text = "${preset.displayName} ($qtyText)",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        softWrap = false,
                      )
                    },
                    leadingIcon = {
                      Icon(
                        imageVector = when {
                          isQueued || isKnown -> Icons.Filled.Check
                          presetTarget == PresetQuickTarget.GROCERY -> Icons.Filled.ShoppingCart
                          else -> Icons.Filled.Kitchen
                        },
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                      )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                      containerColor = when {
                        isQueued -> extendedColors.secondaryFixed
                        isKnown -> extendedColors.sageLight
                        else -> MaterialTheme.colorScheme.surfaceContainerLow
                      },
                    ),
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

// Builds a deterministic, schema-valid IngredientCatalogItem carrying StorageLocation & shelf-life metadata.
private fun buildCustomCatalogItem(
  rawName: String,
  category: IngredientCategory,
  unitChoice: CustomUnitChoice,
  packageQuantity: Double,
  storageLocation: StorageLocation,
  shelfLifeDays: Int,
): IngredientCatalogItem? {
  // Reject blank names or non-positive/NaN quantities so corrupted catalog records can never form.
  val cleanName = rawName.trim()
  if (cleanName.isEmpty() || packageQuantity <= 0.0 || !packageQuantity.isFinite()) return null
  // Normalize name into a deterministic snake_case `ing_custom_*` identifier for idempotent merges.
  val slug = cleanName.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "item" }

  return IngredientCatalogItem(
    ingredientId = "ing_custom_$slug",
    displayName = cleanName,
    category = category,
    tier = unitChoice.tier,
    baseUnit = unitChoice.baseUnit,
    defaultPackageQuantity = packageQuantity,
    isLocallyAvailable = true,
    defaultStorageLocation = storageLocation,
    defaultShelfLifeDays = shelfLifeDays.coerceAtLeast(1),
  )
}

@Preview(showBackground = true, widthDp = 412)
@Composable
private fun AddIngredientCardExpandedPreview() {
  FamilyKitchenBoardTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
      AddIngredientCard(
        existingIngredientIds = setOf("ing_spinach", "ing_plantain", "ing_sweet_potato"),
        queuedGroceryIds = setOf("ing_kale"),
        isExpanded = true,
        onToggleExpanded = {},
        onAddIngredientToGrocery = {},
        onAddIngredientToPantry = {},
        modifier = Modifier.padding(16.dp),
      )
    }
  }
}
