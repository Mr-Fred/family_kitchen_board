package com.familykitchen.board.ui.sheets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.BaseUnit
import com.familykitchen.board.domain.BomLine
import com.familykitchen.board.domain.CookOwner
import com.familykitchen.board.domain.HouseholdProfile
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.IngredientCategory
import com.familykitchen.board.domain.IngredientTier
import com.familykitchen.board.domain.MAX_WEEKDAY_BREAKFAST_MINUTES
import com.familykitchen.board.domain.MealSlotType
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.buildFamilyGoalPlatingGuide
import com.familykitchen.board.domain.computeCalibratedMorningMinutes
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme
import kotlin.math.min

// Modal sheet and stateless content for creating or scheduling preset & custom family meals
// with 48.dp+ steppers stacked cleanly in 2 rows for Galaxy S22 font-scale legibility.

private val DAY_LABELS: List<String> = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/**
 * Immutable submission payload emitted when the user saves a custom household meal inside
 * [CustomMealSheet].
 */
data class CustomFamilyMealDraft(
  val title: String,
  val targetSlotType: MealSlotType,
  val targetDayIndex: Int,
  val applyMondayThroughFriday: Boolean,
  val rawPrepMinutes: Int,
  val cookMinutes: Int,
  val bomLines: List<BomLine>,
  val queueIngredientsToGrocery: Boolean,
)

/**
 * Material 3 [ModalBottomSheet] wrapper hosting [CustomMealSheetContent].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomMealSheet(
  catalog: List<IngredientCatalogItem>,
  schedulableRecipes: List<Recipe>,
  householdProfile: HouseholdProfile,
  initialDayIndex: Int = 0,
  initialSlotType: MealSlotType = MealSlotType.DINNER,
  onSelectPresetRecipe: (
    recipe: Recipe,
    slotType: MealSlotType,
    dayIndex: Int,
    applyMondayThroughFriday: Boolean,
  ) -> Unit,
  onSaveCustomMeal: (CustomFamilyMealDraft) -> Unit,
  onAddLocalCatalogItem: (IngredientCatalogItem) -> Unit = {},
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = KitchenTheme.extendedColors.linen,
  ) {
    CustomMealSheetContent(
      catalog = catalog,
      schedulableRecipes = schedulableRecipes,
      householdProfile = householdProfile,
      initialDayIndex = initialDayIndex,
      initialSlotType = initialSlotType,
      onSelectPresetRecipe = onSelectPresetRecipe,
      onSaveCustomMeal = onSaveCustomMeal,
      onAddLocalCatalogItem = onAddLocalCatalogItem,
      onDismiss = onDismiss,
    )
  }
}

/**
 * Stateless inner content of [CustomMealSheet] extracted so Compose `@Preview`s render cleanly.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CustomMealSheetContent(
  catalog: List<IngredientCatalogItem>,
  schedulableRecipes: List<Recipe>,
  householdProfile: HouseholdProfile,
  initialDayIndex: Int = 0,
  initialSlotType: MealSlotType = MealSlotType.DINNER,
  onSelectPresetRecipe: (
    recipe: Recipe,
    slotType: MealSlotType,
    dayIndex: Int,
    applyMondayThroughFriday: Boolean,
  ) -> Unit,
  onSaveCustomMeal: (CustomFamilyMealDraft) -> Unit,
  onAddLocalCatalogItem: (IngredientCatalogItem) -> Unit = {},
  onDismiss: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Persist form selections as Bundle-safe primitives/Strings across configuration changes.
  var selectedDayIndex by rememberSaveable { mutableIntStateOf(initialDayIndex.coerceIn(0, 6)) }
  var applyMonThroughFri by rememberSaveable { mutableStateOf(false) }
  var selectedSlotTypeName by rememberSaveable { mutableStateOf(initialSlotType.name) }
  val selectedSlotType = MealSlotType.entries.find { it.name == selectedSlotTypeName } ?: initialSlotType
  var customTitle by rememberSaveable { mutableStateOf("") }
  var rawPrepMinutes by rememberSaveable { mutableIntStateOf(6) }
  var cookMinutes by rememberSaveable { mutableIntStateOf(12) }
  var queueToGrocery by rememberSaveable { mutableStateOf(true) }
  var inlineNewItemName by rememberSaveable { mutableStateOf("") }
  var showInlineNewItem by rememberSaveable { mutableStateOf(false) }

  // Filter BOM choices strictly to locally available Tier-1 items to satisfy EC-02 at authoring time.
  val availableTier1Items = remember(catalog) {
    catalog.filter { it.isLocallyAvailable && it.tier == IngredientTier.TIER_1_QUANTITATIVE }
  }
  // Pre-select the first 2 local Tier-1 items in remember (never raw Set inside rememberSaveable).
  var selectedIngredientIds by remember {
    mutableStateOf(availableTier1Items.take(2).map { it.ingredientId }.toSet())
  }

  // Enforce EC-01 calibrated morning time math live as prep/cook steppers change.
  val calibratedMinutes = computeCalibratedMorningMinutes(rawPrepMinutes, 0, cookMinutes)
  val exceedsBreakfastCap =
    selectedSlotType == MealSlotType.BREAKFAST && calibratedMinutes > MAX_WEEKDAY_BREAKFAST_MINUTES

  // Filter 1-tap preset recipes by slot eligibility and <= 25m morning cap on BREAKFAST.
  val slotEligiblePresets = remember(schedulableRecipes, selectedSlotType) {
    schedulableRecipes.filter { recipe ->
      selectedSlotType in recipe.allowedSlots &&
        (selectedSlotType != MealSlotType.BREAKFAST || recipe.calibratedActiveMinutes <= MAX_WEEKDAY_BREAKFAST_MINUTES)
    }
  }

  // Synthesize base-4-serving BomLines from selected Tier-1 ingredient chips.
  val draftBomLines = remember(selectedIngredientIds, availableTier1Items) {
    val byId = availableTier1Items.associateBy { it.ingredientId }
    selectedIngredientIds.mapNotNull { id ->
      val item = byId[id] ?: return@mapNotNull null
      val defaultMealPortion = when (item.baseUnit) {
        BaseUnit.COUNT -> min(4.0, item.defaultPackageQuantity)
        BaseUnit.GRAM, BaseUnit.MILLILITER -> min(350.0, item.defaultPackageQuantity)
      }
      BomLine(ingredientId = id, baseQuantity = defaultMealPortion)
    }
  }

  // Preview 1-pot 4-member family plating dynamically while authoring the custom dish.
  val livePlatingMap = remember(customTitle, draftBomLines, householdProfile, selectedSlotType) {
    val previewRecipe = Recipe(
      recipeId = "rec_custom_preview",
      title = customTitle.ifBlank { "Custom Family Dish" },
      sourceAttribution = "Custom Family Kitchen",
      allowedSlots = listOf(selectedSlotType),
      preferredOwner = if (selectedSlotType == MealSlotType.BREAKFAST) CookOwner.FRED else CookOwner.WIFE,
      rawPrepMinutes = rawPrepMinutes,
      cookMinutes = cookMinutes,
      calibratedActiveMinutes = calibratedMinutes,
      toddlerPrepTip = "Cut into soft bite-sized strips for the youngest child.",
      bomLines = draftBomLines.ifEmpty { listOf(BomLine("ing_placeholder", 1.0)) },
    )
    buildFamilyGoalPlatingGuide(
      recipe = previewRecipe,
      catalog = catalog,
      memberGoals = householdProfile.members,
      activeLifeEvents = householdProfile.activeLifeEvents,
      youngestStage = householdProfile.youngestStage,
    )
  }

  // Scrollable sheet column padded for IME keyboard insets on compact phones.
  Column(
    modifier = modifier
      .fillMaxWidth()
      .imePadding()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // Weighted sheet header + 48.dp Close IconButton.
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(
        modifier = Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(Icons.Filled.RestaurantMenu, contentDescription = null, tint = extendedColors.herbalSage)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "Add / Schedule Meal",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      IconButton(
        onClick = onDismiss,
        modifier = Modifier.size(48.dp),
      ) {
        Icon(Icons.Filled.Close, contentDescription = "Close Custom Meal sheet")
      }
    }

    // 48.dp Single-day (`Mon`..`Sun`) or `Mon–Fri` batch weekday selector chips.
    Text("Target Day(s)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      DAY_LABELS.forEachIndexed { dayIdx, label ->
        FilterChip(
          selected = !applyMonThroughFri && selectedDayIndex == dayIdx,
          onClick = {
            applyMonThroughFri = false
            selectedDayIndex = dayIdx
          },
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          label = { Text(label, maxLines = 1, softWrap = false) },
        )
      }
      FilterChip(
        selected = applyMonThroughFri,
        onClick = { applyMonThroughFri = !applyMonThroughFri },
        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        label = { Text("Mon–Fri", maxLines = 1, softWrap = false) },
      )
    }

    // 48.dp MealSlotType segmented control (`BREAKFAST | LUNCH | DINNER`).
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
      MealSlotType.entries.forEachIndexed { idx, slotType ->
        SegmentedButton(
          selected = selectedSlotType == slotType,
          onClick = { selectedSlotTypeName = slotType.name },
          shape = SegmentedButtonDefaults.itemShape(index = idx, count = MealSlotType.entries.size),
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          label = { Text(slotType.name, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall) },
        )
      }
    }

    // 1-tap preset scheduling cards pre-filtered for EC-01 and EC-02 compliance.
    if (slotEligiblePresets.isNotEmpty()) {
      Text(
        text = "1-Tap Presets (${slotEligiblePresets.size})",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
      )
      slotEligiblePresets.take(4).forEach { preset ->
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(14.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
          border = BorderStroke(1.dp, extendedColors.borderSubtle),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
              Text(
                text = preset.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )
              Text(
                text = "${preset.calibratedActiveMinutes}m active · ${preset.preferredOwner.name}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            FilledTonalButton(
              onClick = { onSelectPresetRecipe(preset, selectedSlotType, selectedDayIndex, applyMonThroughFri) },
              contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
              modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
              Text(
                text = if (applyMonThroughFri) "Mon–Fri" else "Schedule",
                maxLines = 1,
                softWrap = false,
              )
            }
          }
        }
      }
    }

    HorizontalDivider(color = extendedColors.borderSubtle)
    Text("Build Custom Family Meal", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

    OutlinedTextField(
      value = customTitle,
      onValueChange = { customTitle = it },
      label = { Text("Custom Meal Title") },
      placeholder = { Text("e.g., Plantain & Spinach Skillet") },
      singleLine = true,
      keyboardOptions = KeyboardOptions(
        capitalization = KeyboardCapitalization.Words,
        imeAction = ImeAction.Done,
      ),
      modifier = Modifier.fillMaxWidth(),
    )

    // Stack 48.dp Prep & Cook steppers cleanly in 2 rows to prevent horizontal S22 row crush.
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = extendedColors.parchment,
      border = BorderStroke(1.dp, extendedColors.borderSubtle),
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "Prep & Cook Timing",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
          )
          // Live calibrated active-minute capsule turns red when weekday BREAKFAST exceeds 25m.
          Surface(
            shape = CircleShape,
            color = if (exceedsBreakfastCap) MaterialTheme.colorScheme.errorContainer else extendedColors.sageContainer,
          ) {
            Text(
              text = "Active: ${calibratedMinutes}m",
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.Bold,
              color = if (exceedsBreakfastCap) MaterialTheme.colorScheme.onErrorContainer else extendedColors.onSageContainer,
              maxLines = 1,
              softWrap = false,
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
          }
        }

        // Row 1: Hands-on Prep minutes (multiplied by 1.4x slow-cooker calibration buffer).
        MinuteStepperRow(
          label = "Hands-on Prep",
          minutes = rawPrepMinutes,
          onDelta = { rawPrepMinutes = (rawPrepMinutes + it).coerceIn(1, 45) },
        )
        // Row 2: Active Cook minutes on the stove.
        MinuteStepperRow(
          label = "Active Cook",
          minutes = cookMinutes,
          onDelta = { cookMinutes = (cookMinutes + it).coerceIn(1, 60) },
        )
      }
    }

    // Inline EC-01 validation warning disabling Save until timing or target slot is valid.
    if (exceedsBreakfastCap) {
      Text(
        text = "EC-01 Guard: Weekday BREAKFAST requires <= ${MAX_WEEKDAY_BREAKFAST_MINUTES}m calibrated active time (currently ${calibratedMinutes}m).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
    }

    // Tier-1 ingredient selector header + inline "+ Local Item" quick creator.
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "Tier-1 Ingredients (${selectedIngredientIds.size})",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.weight(1f),
      )
      OutlinedButton(
        onClick = { showInlineNewItem = !showInlineNewItem },
        shape = CircleShape,
        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
      ) {
        Text("+ Local Item", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
      }
    }

    // Optional inline local-market ingredient creator registering directly into household catalog.
    if (showInlineNewItem) {
      val addInlineLocalItem = {
        val clean = inlineNewItemName.trim()
        if (clean.isNotEmpty()) {
          val slug = clean.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "local" }
          val newItem = IngredientCatalogItem("ing_local_$slug", clean, IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 500.0)
          onAddLocalCatalogItem(newItem)
          selectedIngredientIds = selectedIngredientIds + newItem.ingredientId
          inlineNewItemName = ""
          showInlineNewItem = false
        }
      }
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OutlinedTextField(
          value = inlineNewItemName,
          onValueChange = { inlineNewItemName = it },
          label = { Text("New Local Ingredient") },
          singleLine = true,
          keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Words,
            imeAction = ImeAction.Done,
          ),
          keyboardActions = KeyboardActions(onDone = { addInlineLocalItem() }),
          modifier = Modifier.weight(1f),
        )
        Button(
          onClick = addInlineLocalItem,
          enabled = inlineNewItemName.isNotBlank(),
          shape = CircleShape,
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        ) {
          Text("Add", maxLines = 1, softWrap = false)
        }
      }
    }

    // 48.dp FilterChips toggling BOM membership across locally available Tier-1 stock.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      availableTier1Items.forEach { item ->
        val selected = item.ingredientId in selectedIngredientIds
        FilterChip(
          selected = selected,
          onClick = {
            selectedIngredientIds = if (selected) selectedIngredientIds - item.ingredientId else selectedIngredientIds + item.ingredientId
          },
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          label = { Text(item.displayName, style = MaterialTheme.typography.labelMedium) },
        )
      }
    }

    // Optional toggle queueing selected BOM items onto the Smart Grocery List in the same batch.
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = 48.dp)
        .clickable { queueToGrocery = !queueToGrocery },
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Checkbox(checked = queueToGrocery, onCheckedChange = { queueToGrocery = it })
      Text(
        text = "Queue selected ingredients onto Grocery List",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.weight(1f),
      )
    }

    // Live 4-member plating preview card verifying 1-pot household goal synthesis before saving.
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(14.dp),
      colors = CardDefaults.cardColors(containerColor = extendedColors.sageContainer),
    ) {
      Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          text = "Live 1-Pot 4-Way Family Plating Preview",
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.Bold,
          color = extendedColors.onSageContainer,
        )
        livePlatingMap.values.forEach { line ->
          Text("• $line", style = MaterialTheme.typography.bodySmall, color = extendedColors.onSageContainer)
        }
      }
    }

    // Gate primary Save CTA on non-blank title, >= 1 BOM line, and EC-01 morning cap compliance.
    val canSave = customTitle.isNotBlank() && draftBomLines.isNotEmpty() && !exceedsBreakfastCap

    // Primary 52.dp Save CTA committing recipe + scheduled slot(s) in 1 O(1) snapshot write.
    Button(
      onClick = {
        onSaveCustomMeal(
          CustomFamilyMealDraft(
            title = customTitle.trim(),
            targetSlotType = selectedSlotType,
            targetDayIndex = selectedDayIndex,
            applyMondayThroughFriday = applyMonThroughFri,
            rawPrepMinutes = rawPrepMinutes,
            cookMinutes = cookMinutes,
            bomLines = draftBomLines,
            queueIngredientsToGrocery = queueToGrocery,
          ),
        )
      },
      enabled = canSave,
      shape = CircleShape,
      modifier = Modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = 52.dp),
    ) {
      Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
      Spacer(modifier = Modifier.width(6.dp))
      Text(
        text = "Save Custom Family Meal",
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        softWrap = false,
      )
    }
  }
}

// Full-width 48.dp x 48.dp minute stepper row preventing horizontal S22 row crush.
@Composable
private fun MinuteStepperRow(label: String, minutes: Int, onDelta: (Int) -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // Weight left label so right-hand 48.dp - / + steppers never collide or clip.
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.Medium,
      modifier = Modifier.weight(1f),
    )
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      // Step by +-2 minutes per tap for fast one-handed recipe timing entry.
      FilledTonalIconButton(
        onClick = { onDelta(-2) },
        modifier = Modifier.size(48.dp),
      ) {
        Icon(Icons.Filled.Remove, contentDescription = "Decrease $label minutes", modifier = Modifier.size(18.dp))
      }
      Text(
        text = "${minutes}m",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        softWrap = false,
      )
      FilledTonalIconButton(
        onClick = { onDelta(2) },
        modifier = Modifier.size(48.dp),
      ) {
        Icon(Icons.Filled.Add, contentDescription = "Increase $label minutes", modifier = Modifier.size(18.dp))
      }
    }
  }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 860)
@Composable
private fun CustomMealSheetPreview() {
  // Seed local Tier-1 produce/protein items and 1 <=25m breakfast preset for hermetic previewing.
  val catalog = listOf(
    IngredientCatalogItem("ing_egg", "Farm Eggs", IngredientCategory.DAIRY_EGGS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.COUNT, 12),
    IngredientCatalogItem("ing_spinach", "Fresh Baby Spinach", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 300),
    IngredientCatalogItem("ing_plantain", "Ripe Yellow Plantains", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.COUNT, 6),
  )
  // Verify 2-row 48.dp minute steppers and live 4-member plating preview in Warm Daylight theme.
  val preset = Recipe(
    recipeId = "rec_plantain_eggs", title = "Golden Plantain & Spinach Egg Skillet",
    sourceAttribution = "Family Kitchen", allowedSlots = listOf(MealSlotType.BREAKFAST, MealSlotType.DINNER),
    preferredOwner = CookOwner.FRED, rawPrepMinutes = 5, cookMinutes = 10, calibratedActiveMinutes = 17,
    toddlerPrepTip = "Slice plantains lengthwise into soft strips for 2yo.",
    bomLines = listOf(BomLine("ing_egg", 4), BomLine("ing_plantain", 3)),
  )
  // Render CustomMealSheetContent directly so preview requires zero modal window tokens.
  FamilyKitchenBoardTheme {
    Surface(color = KitchenTheme.extendedColors.linen) {
      CustomMealSheetContent(
        catalog = catalog, schedulableRecipes = listOf(preset), householdProfile = HouseholdProfile(),
        initialSlotType = MealSlotType.BREAKFAST, onSelectPresetRecipe = { _, _, _, _ -> }, onSaveCustomMeal = {},
      )
    }
  }
}
