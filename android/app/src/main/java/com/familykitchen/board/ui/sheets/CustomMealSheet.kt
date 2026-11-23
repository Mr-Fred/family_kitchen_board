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
import androidx.compose.material.icons.filled.Search
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

// Modal sheet and stateless content for scheduling any preset recipe (zero .take(4) truncation)
// or authoring custom family meals with full Tier-1 + Tier-2 catalog search and zero pre-checked items.

// Short Monday..Sunday labels rendered in the Target Days selector strip.
private val DAY_LABELS: List<String> = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/**
 * Immutable submission payload emitted when the user saves a custom household meal inside
 * [CustomMealSheet].
 *
 * @property title User-authored custom meal title.
 * @property targetSlotType Target meal slot category (`BREAKFAST`, `LUNCH`, or `DINNER`).
 * @property targetDayIndex Target 0-based weekday index (`0 = Mon`..`6 = Sun`).
 * @property applyMondayThroughFriday Schedules across Monday through Friday when `true`.
 * @property rawPrepMinutes Hands-on preparation duration in minutes.
 * @property cookMinutes Active cooking duration in minutes.
 * @property bomLines Selected Tier-1 and Tier-2 [BomLine] requirements.
 * @property queueIngredientsToGrocery Queues missing selected items onto `Shop` when `true`.
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
  // Persist primitive form state across IME resize and device rotation.
  var selectedDayIndex by rememberSaveable { mutableIntStateOf(initialDayIndex.coerceIn(0, 6)) }
  var applyMonThroughFri by rememberSaveable { mutableStateOf(false) }
  var selectedSlotTypeName by rememberSaveable { mutableStateOf(initialSlotType.name) }
  val selectedSlotType = MealSlotType.entries.find { it.name == selectedSlotTypeName } ?: initialSlotType
  // Independent search queries for Cookbook preset recipes vs custom BOM ingredients.
  var presetSearchQuery by rememberSaveable { mutableStateOf("") }
  var customTitle by rememberSaveable { mutableStateOf("") }
  var rawPrepMinutes by rememberSaveable { mutableIntStateOf(6) }
  var cookMinutes by rememberSaveable { mutableIntStateOf(12) }
  var ingredientSearchQuery by rememberSaveable { mutableStateOf("") }
  var queueToGrocery by rememberSaveable { mutableStateOf(true) }
  var inlineNewItemName by rememberSaveable { mutableStateOf("") }
  var showInlineNewItem by rememberSaveable { mutableStateOf(false) }

  // Include ALL locally available catalog items (both Tier-1 perishables and Tier-2 spices/oils).
  val availableCatalogItems = remember(catalog) {
    catalog.filter { it.isLocallyAvailable }
  }
  // Start with ZERO pre-selected Eggs + Milk so custom recipes contain only user-chosen items.
  var selectedIngredientIds by remember {
    mutableStateOf(emptySet<String>())
  }

  // Keep already-checked items visible alongside live query matches across Tier-1 and Tier-2.
  val filteredCatalogItems = remember(availableCatalogItems, ingredientSearchQuery, selectedIngredientIds) {
    val cleanQuery = ingredientSearchQuery.trim().lowercase()
    availableCatalogItems.filter { item ->
      item.ingredientId in selectedIngredientIds ||
        cleanQuery.isEmpty() ||
        item.displayName.lowercase().contains(cleanQuery) ||
        item.category.displayLabel.lowercase().contains(cleanQuery)
    }
  }

  // Compute EC-01 1.4x morning buffer live to guard weekday Breakfast scheduling.
  val calibratedMinutes = computeCalibratedMorningMinutes(rawPrepMinutes, 0, cookMinutes)
  val exceedsBreakfastCap =
    selectedSlotType == MealSlotType.BREAKFAST && calibratedMinutes > MAX_WEEKDAY_BREAKFAST_MINUTES

  // Filter ALL slot-eligible recipes with zero .take(4) truncation.
  val slotEligiblePresets = remember(schedulableRecipes, selectedSlotType, presetSearchQuery) {
    val cleanPresetQuery = presetSearchQuery.trim().lowercase()
    schedulableRecipes.filter { recipe ->
      val fitsSlot = selectedSlotType in recipe.allowedSlots &&
        (selectedSlotType != MealSlotType.BREAKFAST || recipe.calibratedActiveMinutes <= MAX_WEEKDAY_BREAKFAST_MINUTES)
      val fitsSearch = cleanPresetQuery.isEmpty() ||
        recipe.title.lowercase().contains(cleanPresetQuery) ||
        recipe.cuisineTag.lowercase().contains(cleanPresetQuery)
      fitsSlot && fitsSearch
    }
  }

  // Synthesize base-4-serving BomLines across both Tier-1 measured items and Tier-2 staples.
  val draftBomLines = remember(selectedIngredientIds, availableCatalogItems) {
    val byId = availableCatalogItems.associateBy { it.ingredientId }
    selectedIngredientIds.mapNotNull { id ->
      val item = byId[id] ?: return@mapNotNull null
      // Use a light 10 g/ml culinary pinch for Tier-2 spices/oils vs full meal portions for Tier-1.
      val defaultMealPortion = when {
        item.tier == IngredientTier.TIER_2_STAPLE && item.baseUnit != BaseUnit.COUNT -> 10.0
        item.baseUnit == BaseUnit.COUNT -> min(4.0, item.defaultPackageQuantity)
        else -> min(350.0, item.defaultPackageQuantity)
      }
      BomLine(ingredientId = id, baseQuantity = defaultMealPortion)
    }
  }

  // Synthesize live 4-member plating instructions from selected ingredients and household goals.
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

  // Scrollable .imePadding() body resilient down to 360dp width at 1.35x font scaling.
  Column(
    modifier = modifier
      .fillMaxWidth()
      .imePadding()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // Sheet header with 48.dp Close button.
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
          text = "Add or Schedule Meal",
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
    Text("Target Days", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
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

    // 48.dp MealSlotType segmented control (`Breakfast · Lunch · Dinner`).
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
      MealSlotType.entries.forEachIndexed { idx, slotType ->
        val slotLabel = slotType.name.lowercase().replaceFirstChar { it.uppercase() }
        SegmentedButton(
          selected = selectedSlotType == slotType,
          onClick = { selectedSlotTypeName = slotType.name },
          shape = SegmentedButtonDefaults.itemShape(index = idx, count = MealSlotType.entries.size),
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          label = { Text(slotLabel, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall) },
        )
      }
    }

    // Full searchable preset list without .take(4) truncation.
    if (schedulableRecipes.isNotEmpty()) {
      // Header displaying exact count of slot-eligible Cookbook recipes.
      Text(
        text = "Cookbook Recipes · ${slotEligiblePresets.size}",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
      )
      // Search bar filtering Cookbook presets by dish title or cuisine tag.
      OutlinedTextField(
        value = presetSearchQuery,
        onValueChange = { presetSearchQuery = it },
        placeholder = { Text("Search cookbook dishes", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
      )
      // Render every matching Cookbook dish without any artificial 4-item cap.
      slotEligiblePresets.forEach { preset ->
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
              // Map CookOwner enum to configured household cook name ("Fred" or "Wife").
              val ownerText = householdProfile.displayLabelForCookOwner(preset.preferredOwner)
              Text(
                text = "${preset.calibratedActiveMinutes}m active · $ownerText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            // 48.dp 1-tap Schedule button committing single-day or Mon-Fri batch assignment.
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
    // Section 2: Custom Family Meal builder starting with 0 pre-checked ingredients.
    Text("Build Custom Family Meal", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

    // Custom dish title field supporting sentence/word capitalization.
    OutlinedTextField(
      value = customTitle,
      onValueChange = { customTitle = it },
      label = { Text("Custom Meal Title") },
      placeholder = { Text("Plantain and Spinach Skillet") },
      singleLine = true,
      keyboardOptions = KeyboardOptions(
        capitalization = KeyboardCapitalization.Words,
        imeAction = ImeAction.Done,
      ),
      modifier = Modifier.fillMaxWidth(),
    )

    // Warm parchment timing card computing live 1.4x calibrated active minutes.
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
            text = "Prep and Cook Timing",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
          )
          // Color-code active timing pill red if Breakfast exceeds the 25m weekday cap.
          Surface(
            shape = CircleShape,
            color = if (exceedsBreakfastCap) MaterialTheme.colorScheme.errorContainer else extendedColors.sageContainer,
          ) {
            Text(
              text = "Active · ${calibratedMinutes}m",
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.Bold,
              color = if (exceedsBreakfastCap) MaterialTheme.colorScheme.onErrorContainer else extendedColors.onSageContainer,
              maxLines = 1,
              softWrap = false,
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
          }
        }

        customMealMinuteStepperRow(
          label = "Hands-on Prep",
          minutes = rawPrepMinutes,
          onDelta = { rawPrepMinutes = (rawPrepMinutes + it).coerceIn(1, 45) },
        )
        customMealMinuteStepperRow(
          label = "Active Cook",
          minutes = cookMinutes,
          onDelta = { cookMinutes = (cookMinutes + it).coerceIn(1, 60) },
        )
      }
    }

    if (exceedsBreakfastCap) {
      Text(
        text = "Weekday Breakfast requires at most ${MAX_WEEKDAY_BREAKFAST_MINUTES}m calibrated active time · currently ${calibratedMinutes}m.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
    }

    // Searchable Tier-1 + Tier-2 catalog ingredient selector + inline local item creator.
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "Ingredients · ${selectedIngredientIds.size} selected",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.weight(1f),
      )
      OutlinedButton(
        onClick = { showInlineNewItem = !showInlineNewItem },
        shape = CircleShape,
        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
      ) {
        Text("Add Local Item", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
      }
    }

    // Inline 0g-stock local ingredient entry when a neighborhood market item is missing.
    if (showInlineNewItem) {
      val addInlineLocalItem = {
        val clean = customMealSanitizeLabel(inlineNewItemName)
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
          enabled = customMealSanitizeLabel(inlineNewItemName).isNotBlank(),
          shape = CircleShape,
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        ) {
          Text("Add", maxLines = 1, softWrap = false)
        }
      }
    }

    // Search input filtering both Tier-1 perishables and Tier-2 pantry staples/spices.
    OutlinedTextField(
      value = ingredientSearchQuery,
      onValueChange = { ingredientSearchQuery = it },
      placeholder = { Text("Search all produce, proteins, grains, oils, and spices", maxLines = 1, overflow = TextOverflow.Ellipsis) },
      leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
      singleLine = true,
      shape = RoundedCornerShape(14.dp),
      modifier = Modifier.fillMaxWidth(),
    )

    // Multi-select FilterChip grid marking Tier-2 spices/oils with a · Staple hint.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      filteredCatalogItems.forEach { item ->
        val selected = item.ingredientId in selectedIngredientIds
        val cleanName = customMealSanitizeLabel(item.displayName)
        val tierSuffix = if (item.tier == IngredientTier.TIER_2_STAPLE) " · Staple" else ""
        FilterChip(
          selected = selected,
          onClick = {
            selectedIngredientIds = if (selected) selectedIngredientIds - item.ingredientId else selectedIngredientIds + item.ingredientId
          },
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          label = { Text("$cleanName$tierSuffix", style = MaterialTheme.typography.labelMedium) },
        )
      }
    }

    // 48.dp row toggling automatic Smart Grocery List queueing on save.
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

    // Live 1-Pot 4-Way Family Plating preview synthesized from buildFamilyGoalPlatingGuide.
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

    // Require non-blank title, >= 1 user-selected ingredient, and EC-01 morning cap compliance.
    val canSave = customTitle.isNotBlank() && draftBomLines.isNotEmpty() && !exceedsBreakfastCap

    // Primary 52.dp Save Custom Family Meal CTA.
    Button(
      onClick = {
        onSaveCustomMeal(
          CustomFamilyMealDraft(
            title = customMealSanitizeLabel(customTitle),
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

@Composable
private fun customMealMinuteStepperRow(label: String, minutes: Int, onDelta: (Int) -> Unit) {
  // Reusable 48.dp +/- 2-minute stepper row updating live calibrated morning timing.
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
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
      // 48.dp decrement button (-2 minutes).
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
      // 48.dp increment button (+2 minutes).
      FilledTonalIconButton(
        onClick = { onDelta(2) },
        modifier = Modifier.size(48.dp),
      ) {
        Icon(Icons.Filled.Add, contentDescription = "Increase $label minutes", modifier = Modifier.size(18.dp))
      }
    }
  }
}

// Strips slashes and parentheses from user-entered titles and catalog display names.
private fun customMealSanitizeLabel(raw: String): String =
  raw
    .replace(" / ", " and ")
    .replace("/", " · ")
    .replace(" (", " · ")
    .replace("(", "")
    .replace(")", "")
    .replace(Regex("\\s+"), " ")
    .trim()

// Hermetic Warm Daylight preview verifying zero pre-checked Eggs+Milk and full Tier-1/Tier-2 search.
@Preview(name = "CustomMealSheet Daylight", showBackground = true, widthDp = 412, heightDp = 860)
@Composable
private fun CustomMealSheetPreview() {
  // Include both Tier-1 quantitative items and a Tier-2 oil staple in preview fixtures.
  val catalog = listOf(
    IngredientCatalogItem("ing_egg", "Farm Eggs", IngredientCategory.DAIRY_EGGS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.COUNT, 12),
    IngredientCatalogItem("ing_spinach", "Fresh Baby Spinach", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 300),
    IngredientCatalogItem("ing_olive_oil", "Extra-Virgin Olive Oil", IngredientCategory.PANTRY_STAPLE, IngredientTier.TIER_2_STAPLE, BaseUnit.MILLILITER, 750),
  )
  val preset = Recipe(
    recipeId = "rec_plantain_eggs",
    title = "Golden Plantain and Spinach Egg Skillet",
    sourceAttribution = "Family Kitchen",
    allowedSlots = listOf(MealSlotType.BREAKFAST, MealSlotType.DINNER),
    preferredOwner = CookOwner.FRED,
    rawPrepMinutes = 5,
    cookMinutes = 10,
    calibratedActiveMinutes = 17,
    toddlerPrepTip = "Slice plantains lengthwise into soft strips for 2yo.",
    bomLines = listOf(BomLine("ing_egg", 4), BomLine("ing_spinach", 150)),
  )
  FamilyKitchenBoardTheme {
    Surface(color = KitchenTheme.extendedColors.linen) {
      CustomMealSheetContent(
        catalog = catalog,
        schedulableRecipes = listOf(preset),
        householdProfile = HouseholdProfile(),
        initialSlotType = MealSlotType.BREAKFAST,
        onSelectPresetRecipe = { _, _, _, _ -> },
        onSaveCustomMeal = {},
      )
    }
  }
}
