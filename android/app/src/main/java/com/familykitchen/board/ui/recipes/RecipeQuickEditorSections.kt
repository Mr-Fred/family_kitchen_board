package com.familykitchen.board.ui.recipes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.BaseUnit
import com.familykitchen.board.domain.CuisineTradition
import com.familykitchen.board.domain.HouseholdProfile
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.IngredientCategory
import com.familykitchen.board.domain.IngredientTier
import com.familykitchen.board.domain.LOCAL_MARKET_PRESET_INGREDIENTS
import com.familykitchen.board.domain.MAX_WEEKDAY_BREAKFAST_MINUTES
import com.familykitchen.board.domain.MealSlotType
import com.familykitchen.board.domain.ParsedIngredientDraftLine
import com.familykitchen.board.ui.theme.KitchenTheme
import kotlin.math.max
import kotlin.math.roundToInt

// Cohesive Quick Editor form sections for AddOrEditRecipeSheet: title, steppers, CuisineTradition
// chips, MealSlotType chips, Tier-1 + Tier-2 ingredient picker, 0g local item entry, and Steps.

// Short Monday..Sunday chip labels rendered inside the optional Add to Meal Plan section.
private val RECIPE_QUICK_DAYS: List<String> = listOf(
  "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun",
)

/**
 * Interactive `Quick Editor` form section inside [AddOrEditRecipeSheetContent] supporting Tier-1
 * and Tier-2 spice search, `0g`-stock local ingredient creation, numbered steps, and optional
 * `Add to Meal Plan` scheduling.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecipeStudioQuickEditorSection(
  catalog: List<IngredientCatalogItem>,
  householdProfile: HouseholdProfile,
  title: String,
  onTitleChange: (String) -> Unit,
  servings: Int,
  onServingsDelta: (Int) -> Unit,
  prepMinutes: Int,
  onPrepDelta: (Int) -> Unit,
  cookMinutes: Int,
  onCookDelta: (Int) -> Unit,
  calibratedMinutes: Int,
  selectedCuisines: MutableList<String>,
  selectedSlots: MutableList<MealSlotType>,
  ingredientLines: MutableList<ParsedIngredientDraftLine>,
  stepLines: MutableList<String>,
  toddlerTip: String,
  onToddlerTipChange: (String) -> Unit,
  addToMealPlan: Boolean,
  onToggleAddToPlan: (Boolean) -> Unit,
  planDayIndex: Int,
  onSelectPlanDay: (Int) -> Unit,
  planSlotName: String,
  onSelectPlanSlot: (String) -> Unit,
  onAddLocalIngredient: (IngredientCatalogItem) -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  var ingredientQuery by rememberSaveable { mutableStateOf("") }
  var showNewLocalForm by rememberSaveable { mutableStateOf(false) }
  var newLocalName by rememberSaveable { mutableStateOf("") }
  var newLocalIsStaple by rememberSaveable { mutableStateOf(false) }
  var newLocalUnitName by rememberSaveable { mutableStateOf(BaseUnit.GRAM.name) }
  var newStepDraft by rememberSaveable { mutableStateOf("") }

  // Combine household catalog + local market presets (both Tier-1 and Tier-2 spices).
  val searchableCatalog = remember(catalog) {
    val merged = LinkedHashMap<String, IngredientCatalogItem>()
    catalog.filter { it.isLocallyAvailable }.forEach { merged[it.ingredientId] = it }
    LOCAL_MARKET_PRESET_INGREDIENTS.forEach { merged.putIfAbsent(it.ingredientId, it) }
    merged.values.toList()
  }

  // Filter catalog items by live query while excluding items already added to the draft BOM.
  val matchingCatalogItems = remember(searchableCatalog, ingredientQuery, ingredientLines.toList()) {
    val q = ingredientQuery.trim().lowercase()
    val addedIds = ingredientLines.mapNotNull { it.matchedCatalogId }.toSet()
    searchableCatalog
      .filter { item ->
        item.ingredientId !in addedIds &&
          (q.isEmpty() || item.displayName.lowercase().contains(q) || item.category.displayLabel.lowercase().contains(q))
      }
      .take(if (q.isEmpty()) 12 else 24)
  }

  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    OutlinedTextField(
      value = title,
      onValueChange = onTitleChange,
      label = { Text("Recipe Title") },
      placeholder = { Text("Braised Poulet Basquaise") },
      singleLine = true,
      shape = RoundedCornerShape(14.dp),
      keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
      modifier = Modifier.fillMaxWidth(),
    )

    // Servings, Prep, and Cook steppers with 1.4x calibrated morning pace feedback.
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = extendedColors.parchment,
      border = BorderStroke(1.dp, extendedColors.borderSubtle),
    ) {
      Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        recipeQuickStepperRow("Base Servings", "$servings srv", { onServingsDelta(-1) }, { onServingsDelta(1) })
        recipeQuickStepperRow("Hands-on Prep", "${prepMinutes}m", { onPrepDelta(-2) }, { onPrepDelta(2) })
        recipeQuickStepperRow("Active Cook", "${cookMinutes}m", { onCookDelta(-2) }, { onCookDelta(2) })
        Text(
          text = "Calibrated morning pace · ${calibratedMinutes}m",
          style = MaterialTheme.typography.labelSmall,
          color = extendedColors.herbalSage,
        )
      }
    }

    // CuisineTradition multi-select chips defaulting to householdProfile.preferredCuisines.
    Text("Cuisine Traditions", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    val allCuisineOptions = remember(householdProfile.preferredCuisines) {
      (CuisineTradition.entries.map { it.chipLabel } + householdProfile.preferredCuisines.map(::recipeQuickSanitizeText))
        .filter { it.isNotEmpty() }
        .distinct()
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      allCuisineOptions.forEach { cuisine ->
        val isSelected = cuisine in selectedCuisines
        FilterChip(
          selected = isSelected,
          onClick = { if (isSelected) selectedCuisines.remove(cuisine) else selectedCuisines.add(cuisine) },
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          label = { Text(cuisine, style = MaterialTheme.typography.labelSmall) },
        )
      }
    }

    // Allowed MealSlotType multi-select chips (Breakfast · Lunch · Dinner) using `when` control flow.
    Text("Eligible Meal Slots", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      MealSlotType.entries.forEach { slot ->
        val slotLabel = slot.name.lowercase().replaceFirstChar { it.uppercase() }
        val breakfastBlocked = slot == MealSlotType.BREAKFAST && calibratedMinutes > MAX_WEEKDAY_BREAKFAST_MINUTES
        val isSelected = !breakfastBlocked && slot in selectedSlots
        FilterChip(
          selected = isSelected,
          enabled = !breakfastBlocked,
          onClick = {
            when {
              isSelected && selectedSlots.size > 1 -> selectedSlots.remove(slot)
              !isSelected -> selectedSlots.add(slot)
            }
          },
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          label = { Text(slotLabel, maxLines = 1, softWrap = false) },
        )
      }
    }

    HorizontalDivider(color = extendedColors.borderSubtle)

    // Searchable Ingredient Picker (Tier-1 + Tier-2 spices, ZERO pre-checked Eggs+Milk).
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "Ingredients · ${ingredientLines.size}",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.weight(1f),
      )
      OutlinedButton(
        onClick = { showNewLocalForm = !showNewLocalForm },
        shape = CircleShape,
        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
      ) {
        Text("Add New Local Ingredient", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
      }
    }

    // Inline 0g-stock local ingredient creator preventing phantom stock inflation.
    AnimatedVisibility(visible = showNewLocalForm) {
      recipeQuickLocalCreatorCard(
        newLocalName = newLocalName,
        newLocalIsStaple = newLocalIsStaple,
        newLocalUnitName = newLocalUnitName,
        onNameChange = { newLocalName = it },
        onStapleToggle = { newLocalIsStaple = it },
        onUnitSelect = { newLocalUnitName = it },
        onCommitNewLocal = {
          val clean = recipeQuickSanitizeText(newLocalName)
          if (clean.isNotEmpty()) {
            val newUnit = BaseUnit.entries.find { it.name == newLocalUnitName } ?: BaseUnit.GRAM
            val slug = clean.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "local" }
            val tier = if (newLocalIsStaple) IngredientTier.TIER_2_STAPLE else IngredientTier.TIER_1_QUANTITATIVE
            val cat = if (newLocalIsStaple) IngredientCategory.PANTRY_STAPLE else IngredientCategory.PRODUCE
            val pkg = if (newUnit == BaseUnit.COUNT) 6.0 else 500.0
            val defaultPortion = when {
              newLocalIsStaple -> 10.0
              newUnit == BaseUnit.COUNT -> 2.0
              else -> 250.0
            }
            val item = IngredientCatalogItem("ing_custom_$slug", clean, cat, tier, newUnit, pkg)
            onAddLocalIngredient(item)
            ingredientLines.add(
              ParsedIngredientDraftLine(
                rawLine = "$defaultPortion ${newUnit.wireCode} $clean",
                displayName = clean,
                quantity = defaultPortion,
                unit = newUnit,
                matchedCatalogId = item.ingredientId,
                tier = tier,
              ),
            )
            newLocalName = ""
            showNewLocalForm = false
          }
        },
      )
    }

    OutlinedTextField(
      value = ingredientQuery,
      onValueChange = { ingredientQuery = it },
      placeholder = { Text("Search produce, proteins, grains, oils, or spices", maxLines = 1, overflow = TextOverflow.Ellipsis) },
      leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
      singleLine = true,
      shape = RoundedCornerShape(14.dp),
      modifier = Modifier.fillMaxWidth(),
    )

    // Candidate ingredient chips supporting both Tier-1 measured items and Tier-2 spices/oils.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      matchingCatalogItems.forEach { item ->
        val cleanName = recipeQuickSanitizeText(item.displayName)
        val tierSuffix = if (item.tier == IngredientTier.TIER_2_STAPLE) " · Staple" else ""
        FilterChip(
          selected = false,
          onClick = {
            val defaultQty = when {
              item.tier == IngredientTier.TIER_2_STAPLE && item.baseUnit != BaseUnit.COUNT -> 10.0
              item.baseUnit == BaseUnit.COUNT -> 2.0
              else -> 250.0
            }
            ingredientLines.add(
              ParsedIngredientDraftLine(
                rawLine = "$defaultQty ${item.baseUnit.wireCode} $cleanName",
                displayName = cleanName,
                quantity = defaultQty,
                unit = item.baseUnit,
                matchedCatalogId = item.ingredientId,
                tier = item.tier,
              ),
            )
          },
          leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp)) },
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          label = { Text("$cleanName$tierSuffix", style = MaterialTheme.typography.labelSmall) },
        )
      }
    }

    // Editable added ingredient rows with quantity steppers, unit cycle pill, and remove button.
    ingredientLines.forEachIndexed { idx, line ->
      recipeQuickIngredientLineRow(
        line = line,
        onUpdateLine = { ingredientLines[idx] = it },
        onRemoveLine = { ingredientLines.removeAt(idx) },
      )
    }

    HorizontalDivider(color = extendedColors.borderSubtle)

    // Numbered Steps Editor with 48.dp delete buttons and inline step entry.
    Text("Cooking Steps · ${stepLines.size}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    stepLines.forEachIndexed { stepIdx, instruction ->
      // Ceramic card per numbered step with 48.dp delete icon.
      Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, extendedColors.borderSubtle),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "${stepIdx + 1}. $instruction",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f).padding(end = 6.dp),
          )
          IconButton(onClick = { stepLines.removeAt(stepIdx) }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete step ${stepIdx + 1}", modifier = Modifier.size(18.dp))
          }
        }
      }
    }

    // Append trimmed non-empty step instruction and clear the draft input field.
    val commitNewStep = {
      val cleanStep = newStepDraft.trim()
      if (cleanStep.isNotEmpty()) {
        stepLines.add(cleanStep)
        newStepDraft = ""
      }
    }
    // Inline step text field + 48.dp Add Step button supporting IME Done submission.
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      OutlinedTextField(
        value = newStepDraft,
        onValueChange = { newStepDraft = it },
        label = { Text("Add Step Instruction") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commitNewStep() }),
        modifier = Modifier.weight(1f),
      )
      Button(
        onClick = commitNewStep,
        enabled = newStepDraft.isNotBlank(),
        shape = CircleShape,
        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
      ) { Text("Add Step", maxLines = 1, softWrap = false) }
    }

    // EC-08 child choking-safety prep guidance field saved on the materialized Recipe.
    OutlinedTextField(
      value = toddlerTip,
      onValueChange = onToddlerTipChange,
      label = { Text("Child Safety Prep Tip") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )

    // Optional Add to Meal Plan toggle (OFF from Recipes, ON from Plan).
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = extendedColors.parchment,
      border = BorderStroke(1.dp, extendedColors.borderSubtle),
      modifier = Modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = 56.dp)
        .clickable { onToggleAddToPlan(!addToMealPlan) },
    ) {
      Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text("Add to Meal Plan", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("Save to your Cookbook only, or schedule onto a day right away.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          Switch(checked = addToMealPlan, onCheckedChange = onToggleAddToPlan)
        }

        // Expand Mon..Sun + Breakfast/Lunch/Dinner picker only when Add to Meal Plan is ON.
        AnimatedVisibility(visible = addToMealPlan) {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Weekday chip strip (`Mon`..`Sun`) with 48.dp touch height.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              RECIPE_QUICK_DAYS.forEachIndexed { idx, day ->
                FilterChip(
                  selected = planDayIndex == idx,
                  onClick = { onSelectPlanDay(idx) },
                  modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                  label = { Text(day, maxLines = 1, softWrap = false) },
                )
              }
            }
            // Target meal slot chips guarding EC-01 25m morning cap on Breakfast.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              MealSlotType.entries.forEach { slot ->
                val blocked = slot == MealSlotType.BREAKFAST && calibratedMinutes > MAX_WEEKDAY_BREAKFAST_MINUTES
                val label = slot.name.lowercase().replaceFirstChar { it.uppercase() }
                FilterChip(
                  selected = !blocked && planSlotName == slot.name,
                  enabled = !blocked,
                  onClick = { onSelectPlanSlot(slot.name) },
                  colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = extendedColors.sageContainer,
                    selectedLabelColor = extendedColors.onSageContainer,
                  ),
                  modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                  label = { Text(label, maxLines = 1, softWrap = false) },
                )
              }
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun recipeQuickLocalCreatorCard(
  newLocalName: String,
  newLocalIsStaple: Boolean,
  newLocalUnitName: String,
  onNameChange: (String) -> Unit,
  onStapleToggle: (Boolean) -> Unit,
  onUnitSelect: (String) -> Unit,
  onCommitNewLocal: () -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  val newUnit = BaseUnit.entries.find { it.name == newLocalUnitName } ?: BaseUnit.GRAM
  // Initializes new local ingredients at 0g / OUT stock so GroceryDiffEngine queues real deficits.
  Surface(
    shape = RoundedCornerShape(14.dp),
    color = extendedColors.parchment,
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Starts at 0g stock so Shop queues exact packages when planned.", style = MaterialTheme.typography.bodySmall)
      OutlinedTextField(
        value = newLocalName,
        onValueChange = onNameChange,
        label = { Text("Local Ingredient Name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Tier-1 quantitative option measured in grams, milliliters, or whole counts.
        FilterChip(
          selected = !newLocalIsStaple,
          onClick = { onStapleToggle(false) },
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          label = { Text("Tier 1 Measured") },
        )
        // Tier-2 staple option tracked qualitatively via PLENTY | LOW | OUT.
        FilterChip(
          selected = newLocalIsStaple,
          onClick = { onStapleToggle(true) },
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          label = { Text("Tier 2 Spice or Oil") },
        )
        // Base unit chips (`g`, `ml`, `count`) determining package rounding math.
        BaseUnit.entries.forEach { u ->
          FilterChip(
            selected = newUnit == u,
            onClick = { onUnitSelect(u.name) },
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            label = { Text(u.wireCode) },
          )
        }
      }
      // 48.dp confirmation button registering the new local ingredient at 0.0 stock.
      Button(
        onClick = onCommitNewLocal,
        enabled = recipeQuickSanitizeText(newLocalName).isNotEmpty(),
        shape = CircleShape,
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
      ) {
        Text("Add at 0g Stock", maxLines = 1, softWrap = false)
      }
    }
  }
}

@Composable
private fun recipeQuickIngredientLineRow(
  line: ParsedIngredientDraftLine,
  onUpdateLine: (ParsedIngredientDraftLine) -> Unit,
  onRemoveLine: () -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Step COUNT items by 1 unit and GRAM / MILLILITER items by 25 g or ml increments.
  val stepAmt = if (line.unit == BaseUnit.COUNT) 1.0 else 25.0
  // Ceramic card per added BOM line with editable quantity, unit cycle pill, and delete icon.
  Surface(
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
        Text(recipeQuickSanitizeText(line.displayName), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // Clarify Tier-2 staple status vs Tier-1 measured deduction behavior.
        val tierTag = if (line.tier == IngredientTier.TIER_2_STAPLE) "Tier 2 Staple" else "Tier 1 Measured"
        Text(tierTag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // 48.dp decrement button clamped above 0.5 so lines never hit 0 accidentally.
        FilledTonalIconButton(
          onClick = { onUpdateLine(line.copy(quantity = max(0.5, line.quantity - stepAmt))) },
          modifier = Modifier.size(48.dp),
        ) { Icon(Icons.Default.Remove, contentDescription = "Decrease quantity", modifier = Modifier.size(16.dp)) }

        // 48.dp tappable unit pill cycling g -> ml -> count cleanly without dropdown clutter.
        Surface(
          onClick = {
            val nextUnit = when (line.unit) {
              BaseUnit.GRAM -> BaseUnit.MILLILITER
              BaseUnit.MILLILITER -> BaseUnit.COUNT
              BaseUnit.COUNT -> BaseUnit.GRAM
            }
            onUpdateLine(line.copy(unit = nextUnit))
          },
          shape = CircleShape,
          color = extendedColors.parchment,
          border = BorderStroke(1.dp, extendedColors.borderSubtle),
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        ) {
          Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${recipeQuickFormatNumber(line.quantity)} ${line.unit.wireCode}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
          }
        }

        // 48.dp increment button adding +1 count or +25 g/ml.
        FilledTonalIconButton(
          onClick = { onUpdateLine(line.copy(quantity = line.quantity + stepAmt)) },
          modifier = Modifier.size(48.dp),
        ) { Icon(Icons.Default.Add, contentDescription = "Increase quantity", modifier = Modifier.size(16.dp)) }

        // 48.dp remove icon deleting this BOM line from the recipe draft.
        IconButton(onClick = onRemoveLine, modifier = Modifier.size(48.dp)) {
          Icon(Icons.Default.DeleteOutline, contentDescription = "Remove ingredient", modifier = Modifier.size(18.dp))
        }
      }
    }
  }
}

@Composable
private fun recipeQuickStepperRow(
  label: String,
  valueText: String,
  onDecrement: () -> Unit,
  onIncrement: () -> Unit,
) {
  // Reusable 48.dp +/- stepper row shared by Base Servings, Hands-on Prep, and Active Cook.
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FilledTonalIconButton(onClick = onDecrement, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Default.Remove, contentDescription = "Decrease $label", modifier = Modifier.size(18.dp))
      }
      Text(valueText, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
      FilledTonalIconButton(onClick = onIncrement, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Default.Add, contentDescription = "Increase $label", modifier = Modifier.size(18.dp))
      }
    }
  }
}

// Formats numeric quantities without trailing ".0" on whole integer amounts.
private fun recipeQuickFormatNumber(value: Double): String {
  // Round to 2 decimal places so fractional quantities display cleanly.
  val rounded = (value * 100.0).roundToInt() / 100.0
  return when {
    rounded == rounded.toLong().toDouble() -> rounded.toLong().toString()
    else -> rounded.toString()
  }
}

/**
 * Strips slashes and parentheses from user-entered or catalog strings for clean UI presentation.
 */
internal fun recipeQuickSanitizeText(raw: String): String =
  raw
    .replace(" / ", " and ")
    .replace("/", " · ")
    .replace("(", "")
    .replace(")", "")
    .replace(Regex("\\s+"), " ")
    .trim()
