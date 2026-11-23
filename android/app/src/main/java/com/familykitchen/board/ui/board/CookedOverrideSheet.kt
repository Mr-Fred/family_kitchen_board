package com.familykitchen.board.ui.board

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.BaseUnit
import com.familykitchen.board.domain.CookedDeductionPayload
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.IngredientTier
import com.familykitchen.board.domain.MealSlotType
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.ResolvedMealCard
import com.familykitchen.board.domain.SlotSkipReason
import com.familykitchen.board.domain.WorkoutTiming
import com.familykitchen.board.domain.resolveEffectiveMealBom
import com.familykitchen.board.ui.preview.PreviewFixtures
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme
import kotlin.math.max
import kotlin.math.roundToInt

// Adjust, Swap, Move Day, Pin Routine, Skip Reason, Clear Slot, and Override Cooked Meal bottom
// sheet supporting EC-04..EC-07 and EC-11..EC-13 with 48.dp+ touch targets and zero truncation.

/**
 * Stateless Material 3 [ModalBottomSheet] presenting meal adjustments, full searchable recipe
 * swaps (`Ready Now` · `Preferred Cuisines` · `All Recipes`), 2-tap `Move Day`, `Pin Routine`,
 * split `Ate Leftovers` vs `Eating Out`, `Clear Slot`, and per-ingredient cooked overrides.
 *
 * @param card Resolved meal card currently selected for adjustment.
 * @param catalog Household ingredient catalog for unit steppers and extra-ingredient picking.
 * @param instantSwapCandidates 100%-in-stock replacement candidates (`EC-13`).
 * @param onDismiss Closes the modal bottom sheet.
 * @param onConfirmCookedDeduction Commits the `O(1)` [CookedDeductionPayload].
 * @param onSelectWorkoutModifier Updates the active flat `1x` workout boost (`EC-07`).
 * @param onSwapToInStockMeal Replaces the slot's `recipeId` with the chosen recipe.
 * @param onMarkSkippedOrLeftovers Legacy single-argument skip callback retained for compatibility.
 * @param onReplaceWithCustomMeal Opens `CustomMealSheet` targeting this slot.
 * @param allRecipes Full household recipe collection backing the searchable `All Recipes` picker.
 * @param preferredCuisines Household preferred cuisine tags backing `Preferred Cuisines` filter.
 * @param onMoveOrSwapSlot Relocates or exchanges this slot onto `(targetDayIndex, targetSlotType)`.
 * @param onToggleSlotPinned Toggles `isPinned` on this slot across weekly rollovers.
 * @param onMarkSkipReason Marks this slot `COMPLETED_SKIPPED` with an explicit [SlotSkipReason].
 * @param onClearSlot Clears this slot from the weekly board (`0..21` flexible cadence).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookedOverrideSheet(
  card: ResolvedMealCard,
  catalog: List<IngredientCatalogItem>,
  instantSwapCandidates: List<Recipe> = emptyList(),
  onDismiss: () -> Unit,
  onConfirmCookedDeduction: (CookedDeductionPayload) -> Unit,
  onSelectWorkoutModifier: (slotId: String, modifier: WorkoutTiming) -> Unit = { _, _ -> },
  onSwapToInStockMeal: (slotId: String, newRecipeId: String) -> Unit = { _, _ -> },
  onMarkSkippedOrLeftovers: (slotId: String) -> Unit = {},
  onReplaceWithCustomMeal: (slotId: String) -> Unit = {},
  allRecipes: List<Recipe> = emptyList(),
  preferredCuisines: List<String> = emptyList(),
  onMoveOrSwapSlot: (String, Int, MealSlotType) -> Unit = { _, _, _ -> },
  onToggleSlotPinned: (String) -> Unit = {},
  onMarkSkipReason: (String, SlotSkipReason) -> Unit = { slotId, _ -> onMarkSkippedOrLeftovers(slotId) },
  onClearSlot: (String) -> Unit = {},
) {
  // Skip partially expanded stop so swap filters and per-ingredient steppers appear immediately.
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = KitchenTheme.extendedColors.linen,
  ) {
    CookedOverrideSheetContent(
      card = card,
      catalog = catalog,
      instantSwapCandidates = instantSwapCandidates,
      onConfirmCookedDeduction = { payload ->
        onConfirmCookedDeduction(payload)
        onDismiss()
      },
      onSelectWorkoutModifier = onSelectWorkoutModifier,
      onSwapToInStockMeal = { slotId, recipeId ->
        onSwapToInStockMeal(slotId, recipeId)
        onDismiss()
      },
      onMarkSkippedOrLeftovers = { slotId ->
        onMarkSkippedOrLeftovers(slotId)
        onDismiss()
      },
      onReplaceWithCustomMeal = { slotId ->
        onReplaceWithCustomMeal(slotId)
        onDismiss()
      },
      allRecipes = allRecipes,
      preferredCuisines = preferredCuisines,
      onMoveOrSwapSlot = { slotId, dayIdx, slotType ->
        onMoveOrSwapSlot(slotId, dayIdx, slotType)
        onDismiss()
      },
      onToggleSlotPinned = onToggleSlotPinned,
      onMarkSkipReason = { slotId, reason ->
        onMarkSkipReason(slotId, reason)
        onDismiss()
      },
      onClearSlot = { slotId ->
        onClearSlot(slotId)
        onDismiss()
      },
    )
  }
}

/**
 * Extracted pure-Compose inner body for [CookedOverrideSheet], enabling standalone `@Preview`
 * inspection without requiring an active Android window token.
 *
 * @param card Resolved meal card currently selected for adjustment.
 * @param catalog Household ingredient catalog for unit steppers and extra-ingredient picking.
 * @param instantSwapCandidates 100%-in-stock replacement candidates (`EC-13`).
 * @param onConfirmCookedDeduction Commits the `O(1)` [CookedDeductionPayload].
 * @param onSelectWorkoutModifier Updates the active flat `1x` workout boost (`EC-07`).
 * @param onSwapToInStockMeal Replaces the slot's `recipeId` with the chosen recipe.
 * @param onMarkSkippedOrLeftovers Legacy single-argument skip callback retained for compatibility.
 * @param onReplaceWithCustomMeal Opens `CustomMealSheet` targeting this slot.
 * @param allRecipes Full household recipe collection backing the searchable `All Recipes` picker.
 * @param preferredCuisines Household preferred cuisine tags backing `Preferred Cuisines` filter.
 * @param onMoveOrSwapSlot Relocates or exchanges this slot onto `(targetDayIndex, targetSlotType)`.
 * @param onToggleSlotPinned Toggles `isPinned` on this slot across weekly rollovers.
 * @param onMarkSkipReason Marks this slot `COMPLETED_SKIPPED` with an explicit [SlotSkipReason].
 * @param onClearSlot Clears this slot from the weekly board (`0..21` flexible cadence).
 * @param modifier Optional Compose [Modifier].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CookedOverrideSheetContent(
  card: ResolvedMealCard,
  catalog: List<IngredientCatalogItem>,
  instantSwapCandidates: List<Recipe> = emptyList(),
  onConfirmCookedDeduction: (CookedDeductionPayload) -> Unit = {},
  onSelectWorkoutModifier: (slotId: String, modifier: WorkoutTiming) -> Unit = { _, _ -> },
  onSwapToInStockMeal: (slotId: String, newRecipeId: String) -> Unit = { _, _ -> },
  onMarkSkippedOrLeftovers: (slotId: String) -> Unit = {},
  onReplaceWithCustomMeal: (slotId: String) -> Unit = {},
  allRecipes: List<Recipe> = emptyList(),
  preferredCuisines: List<String> = emptyList(),
  onMoveOrSwapSlot: (String, Int, MealSlotType) -> Unit = { _, _, _ -> },
  onToggleSlotPinned: (String) -> Unit = {},
  onMarkSkipReason: (String, SlotSkipReason) -> Unit = { slotId, _ -> onMarkSkippedOrLeftovers(slotId) },
  onClearSlot: (String) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Index catalog by ingredientId once so stepper rows avoid linear scans per recomposition.
  val catalogById = remember(catalog) { catalog.associateBy { it.ingredientId } }

  // Mirror workout boost and pin state locally for instant tactile responsiveness.
  val workoutState = remember(card.slot.slotId, card.slot.activeWorkoutModifier) {
    mutableStateOf(card.slot.activeWorkoutModifier)
  }
  val isPinnedState = rememberSaveable(card.slot.slotId, card.slot.isPinned) {
    mutableStateOf(card.slot.isPinned)
  }
  val showMoveDayPickerState = rememberSaveable(card.slot.slotId) { mutableStateOf(false) }
  val saveDefaultState = remember(card.slot.slotId) { mutableStateOf(false) }
  val pickerExpandedState = remember { mutableStateOf(false) }
  val customOverrides = remember(card.slot.slotId) { mutableStateMapOf<String, Double>() }

  // Recompute baseline effective BOM dynamically whenever the flat 1x workout boost changes.
  val baselineBom = remember(card.recipe, card.slot.targetServings, workoutState.value, catalog) {
    resolveEffectiveMealBom(
      recipe = card.recipe,
      targetServings = card.slot.targetServings,
      activeWorkoutModifier = workoutState.value,
      catalog = catalog,
    )
  }

  // Merge planned BOM ids and user-added extra ingredient ids in deterministic insertion order.
  val orderedIngredientIds = remember(baselineBom, customOverrides.keys.toList()) {
    linkedSetOf<String>().apply {
      addAll(baselineBom.keys)
      addAll(customOverrides.keys)
    }.toList()
  }

  Column(
    modifier = modifier
      .fillMaxWidth()
      .imePadding()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        text = "Adjust Meal or Swap Slot",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = "${card.recipe.title} · ${card.slot.targetServings} family servings",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    // Slot routine actions: Pin Routine · Move Day · Custom Meal · Clear Slot.
    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      // 48.dp Pin Routine chip locking this dish onto its weekday across weekly rollovers.
      FilterChip(
        selected = isPinnedState.value,
        onClick = {
          isPinnedState.value = !isPinnedState.value
          onToggleSlotPinned(card.slot.slotId)
        },
        leadingIcon = {
          Icon(Icons.Default.PushPin, contentDescription = null, modifier = Modifier.size(16.dp))
        },
        label = { Text("Pin Routine", maxLines = 1, softWrap = false) },
        colors = FilterChipDefaults.filterChipColors(
          selectedContainerColor = extendedColors.sageContainer,
          selectedLabelColor = extendedColors.onSageContainer,
        ),
        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
      )

      // 48.dp Move Day chip expanding the 2-tap Mon..Sun + Breakfast/Lunch/Dinner target selector.
      FilterChip(
        selected = showMoveDayPickerState.value,
        onClick = { showMoveDayPickerState.value = !showMoveDayPickerState.value },
        leadingIcon = {
          Icon(Icons.Default.EventRepeat, contentDescription = null, modifier = Modifier.size(16.dp))
        },
        label = { Text("Move Day", maxLines = 1, softWrap = false) },
        colors = FilterChipDefaults.filterChipColors(
          selectedContainerColor = extendedColors.sageContainer,
          selectedLabelColor = extendedColors.onSageContainer,
        ),
        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
      )

      // 48.dp Custom Meal button launching CustomMealSheet targeting this slot.
      OutlinedButton(
        onClick = { onReplaceWithCustomMeal(card.slot.slotId) },
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
      ) {
        Icon(Icons.Default.EditNote, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text("Custom Meal", maxLines = 1, softWrap = false)
      }

      // 48.dp Clear Slot button removing this meal cleanly for flexible 0..21 slot cadences.
      OutlinedButton(
        onClick = { onClearSlot(card.slot.slotId) },
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
      ) {
        Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text("Clear Slot", maxLines = 1, softWrap = false)
      }
    }

    // 2-Tap Move Day inline selector (Mon..Sun + Breakfast · Lunch · Dinner).
    AnimatedVisibility(visible = showMoveDayPickerState.value) {
      OverrideSheetMoveDayCard(
        card = card,
        onMoveOrSwapSlot = onMoveOrSwapSlot,
      )
    }

    // Honest EC-12 skip split: Ate Leftovers (counts macros) vs Eating Out (omits macros).
    OverrideSheetSkipReasonRow(
      slotId = card.slot.slotId,
      onMarkSkipReason = onMarkSkipReason,
    )

    // Full searchable recipe swap picker (Ready Now · Preferred Cuisines · All Recipes).
    OverrideSheetSearchableSwapSection(
      card = card,
      instantSwapCandidates = instantSwapCandidates,
      allRecipes = allRecipes,
      preferredCuisines = preferredCuisines,
      onSwapToInStockMeal = onSwapToInStockMeal,
    )

    HorizontalDivider(color = extendedColors.borderSubtle)

    // EC-07: Flat 1x Pre-Workout Carb or Post-Workout Protein selector.
    overrideCoreWorkoutSelector(
      selectedWorkout = workoutState.value,
      onSelectWorkout = { nextProfile ->
        workoutState.value = nextProfile
        onSelectWorkoutModifier(card.slot.slotId, nextProfile)
      },
    )

    // Per-ingredient 48.dp +/- quantity steppers (EC-04) & Tier-2 staple protection (EC-11).
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      // Section header explaining exact stepper increments (1 count or 25 g/ml).
      Text(
        text = "Ingredients Used · Step by 1 count or 25 g and ml",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
      )

      // Emit one ceramic row per planned or ad-hoc ingredient in deterministic insertion order.
      orderedIngredientIds.forEach { ingredientId ->
        val item = catalogById[ingredientId]
        // Default unknown IDs defensively to Tier-1 COUNT so custom additions remain editable.
        val tier = item?.tier ?: IngredientTier.TIER_1_QUANTITATIVE
        val baseUnit = item?.baseUnit ?: BaseUnit.COUNT
        val plannedQty = baselineBom[ingredientId] ?: 0.0
        // Prefer user-stepped quantity when overridden, falling back to scaled planned BOM.
        val effectiveQty = customOverrides[ingredientId] ?: plannedQty
        val stepDelta = overrideCoreStepSizeForUnit(baseUnit)

        overrideCoreIngredientStepperRow(
          displayName = item?.displayName ?: ingredientId,
          tier = tier,
          baseUnit = baseUnit,
          plannedQuantity = plannedQty,
          effectiveQuantity = effectiveQty,
          isAdHocExtra = ingredientId !in baselineBom,
          // Floor decrements at 0.0 so negative deductions never inflate Pantry stock (EC-10).
          onDecrement = { customOverrides[ingredientId] = max(0.0, effectiveQty - stepDelta) },
          onIncrement = { customOverrides[ingredientId] = effectiveQty + stepDelta },
        )
      }
    }

    // EC-06: Quick "+1 Tomato" shortcut and full Tier-1 extra ingredient picker.
    overrideCoreExtraIngredientRow(
      catalog = catalog,
      pickerExpanded = pickerExpandedState.value,
      onTogglePicker = { pickerExpandedState.value = !pickerExpandedState.value },
      onDismissPicker = { pickerExpandedState.value = false },
      onAddExtraIngredient = { item ->
        // Add 1 unit step onto existing override or planned BOM baseline.
        val current = customOverrides[item.ingredientId] ?: (baselineBom[item.ingredientId] ?: 0.0)
        customOverrides[item.ingredientId] = current + overrideCoreStepSizeForUnit(item.baseUnit)
        pickerExpandedState.value = false
      },
      onAddOneTomatoShortcut = {
        // Increment canonical ing_tomato by +1.0 count for 1-tap EC-06 verification.
        val current = customOverrides["ing_tomato"] ?: (baselineBom["ing_tomato"] ?: 0.0)
        customOverrides["ing_tomato"] = current + 1.0
      },
    )

    // EC-05: Save normalized overrides as household default checkbox.
    // Full-row >= 52.dp tap surface toggling base-4-serving recipe learning.
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = extendedColors.parchment,
      border = BorderStroke(1.dp, extendedColors.borderSubtle),
      modifier = Modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = 52.dp)
        .clickable { saveDefaultState.value = !saveDefaultState.value },
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Checkbox(
          checked = saveDefaultState.value,
          onCheckedChange = { saveDefaultState.value = it },
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Save as household default for this recipe",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
          )
          // Explain how adjusted quantities normalize back to recipe.baseServings.
          Text(
            text = "Normalizes adjusted Tier-1 amounts to ${card.recipe.baseServings} base servings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    // Primary atomic confirmation CTA committing O(1) cooked deduction.
    Button(
      onClick = {
        // Pass null when untouched so PantryLedgerEngine uses exact scaled BOM without overhead.
        val overridesSnapshot = if (customOverrides.isEmpty()) null else customOverrides.toMap()
        onConfirmCookedDeduction(
          CookedDeductionPayload(
            slotId = card.slot.slotId,
            recipeId = card.recipe.recipeId,
            targetServings = card.slot.targetServings,
            activeWorkoutModifier = workoutState.value,
            actualUsedQuantities = overridesSnapshot,
            saveOverrideAsHouseholdDefault = saveDefaultState.value,
          ),
        )
      },
      modifier = Modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = 52.dp),
      shape = RoundedCornerShape(14.dp),
      colors = ButtonDefaults.buttonColors(
        containerColor = extendedColors.herbalSage,
        contentColor = MaterialTheme.colorScheme.onPrimary,
      ),
    ) {
      Icon(imageVector = Icons.Default.Check, contentDescription = null)
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = "Confirm Cooked and Deduct",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        softWrap = false,
      )
    }

    Spacer(modifier = Modifier.height(12.dp))
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun overrideCoreWorkoutSelector(
  selectedWorkout: WorkoutTiming,
  onSelectWorkout: (WorkoutTiming) -> Unit,
) {
  // Compact 1-word labels guarantee zero wrapping on 360dp phones at 1.35x font scale.
  val options = listOf(
    WorkoutTiming.NONE to "None",
    WorkoutTiming.PRE_WORKOUT_CARB to "Carb",
    WorkoutTiming.POST_WORKOUT_HIGH_PROTEIN to "Protein",
  )

  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(
      text = "Workout Boost · Flat 1x Add-On",
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurface,
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
      options.forEachIndexed { index, (profile, label) ->
        SegmentedButton(
          selected = selectedWorkout == profile,
          onClick = { onSelectWorkout(profile) },
          shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          label = {
            Text(
              text = label,
              maxLines = 1,
              softWrap = false,
              style = MaterialTheme.typography.labelMedium,
            )
          },
        )
      }
    }
  }
}

@Composable
private fun overrideCoreIngredientStepperRow(
  displayName: String,
  tier: IngredientTier,
  baseUnit: BaseUnit,
  plannedQuantity: Double,
  effectiveQuantity: Double,
  isAdHocExtra: Boolean,
  onDecrement: () -> Unit,
  onIncrement: () -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  val cleanName = overrideSwapSanitizeLabel(displayName)
  Surface(
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
        Text(
          text = if (isAdHocExtra) "$cleanName · Extra" else cleanName,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        // EC-11: Tier-2 staples stay protected from quantitative auto-deduction.
        val subtitle = when {
          tier == IngredientTier.TIER_2_STAPLE -> "Kitchen Staple · Never auto-deducted"
          isAdHocExtra -> "Ad-hoc extra deducted in batch"
          effectiveQuantity != plannedQuantity ->
            "Planned ${overrideCoreFormatQty(plannedQuantity, baseUnit)} · Adjusted"
          else -> "Planned ${overrideCoreFormatQty(plannedQuantity, baseUnit)}"
        }
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      when (tier) {
        IngredientTier.TIER_2_STAPLE -> {
          // Tier-2 staples show a non-editable Staple pill because they use PLENTY | LOW | OUT.
          Surface(shape = CircleShape, color = extendedColors.sageContainer) {
            Text(
              text = "Staple",
              style = MaterialTheme.typography.labelSmall,
              color = extendedColors.onSageContainer,
              maxLines = 1,
              softWrap = false,
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
          }
        }

        IngredientTier.TIER_1_QUANTITATIVE -> {
          // Tier-1 quantitative items expose 48.dp - / + steppers floored safely at 0.0 (EC-10).
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            OutlinedIconButton(
              onClick = onDecrement,
              enabled = effectiveQuantity > 0.0,
              shape = RoundedCornerShape(12.dp),
              border = BorderStroke(1.dp, extendedColors.borderSubtle),
              modifier = Modifier.size(48.dp),
            ) {
              Icon(Icons.Default.Remove, contentDescription = "Decrease $cleanName", modifier = Modifier.size(18.dp))
            }
            Text(
              text = overrideCoreFormatQty(effectiveQuantity, baseUnit),
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
              softWrap = false,
            )
            OutlinedIconButton(
              onClick = onIncrement,
              shape = RoundedCornerShape(12.dp),
              border = BorderStroke(1.dp, extendedColors.borderSubtle),
              modifier = Modifier.size(48.dp),
            ) {
              Icon(Icons.Default.Add, contentDescription = "Increase $cleanName", modifier = Modifier.size(18.dp))
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun overrideCoreExtraIngredientRow(
  catalog: List<IngredientCatalogItem>,
  pickerExpanded: Boolean,
  onTogglePicker: () -> Unit,
  onDismissPicker: () -> Unit,
  onAddExtraIngredient: (IngredientCatalogItem) -> Unit,
  onAddOneTomatoShortcut: () -> Unit,
) {
  // Offer only locally available Tier-1 quantitative ingredients as cook-time ad-hoc extras.
  val extraCandidates = remember(catalog) {
    catalog.filter { it.tier == IngredientTier.TIER_1_QUANTITATIVE && it.isLocallyAvailable }
  }

  FlowRow(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    // EC-06 quick 1-tap "+1 Tomato" shortcut verified by QA Scenario Lab.
    FilledTonalButton(
      onClick = onAddOneTomatoShortcut,
      contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
      colors = ButtonDefaults.filledTonalButtonColors(
        containerColor = KitchenTheme.extendedColors.terracottaContainer,
        contentColor = KitchenTheme.extendedColors.onTerracottaContainer,
      ),
      modifier = Modifier.defaultMinSize(minHeight = 48.dp),
    ) {
      Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
      Spacer(modifier = Modifier.width(4.dp))
      Text(text = "+1 Tomato", maxLines = 1, softWrap = false)
    }

    // General Tier-1 extra ingredient dropdown menu deducted in the same O(1) batch write.
    Box {
      OutlinedButton(
        onClick = onTogglePicker,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
      ) {
        Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = "Extra Ingredient", maxLines = 1, softWrap = false)
      }

      DropdownMenu(
        expanded = pickerExpanded,
        onDismissRequest = onDismissPicker,
      ) {
        extraCandidates.forEach { candidate ->
          val stepLabel = overrideCoreFormatQty(overrideCoreStepSizeForUnit(candidate.baseUnit), candidate.baseUnit)
          val cleanName = overrideSwapSanitizeLabel(candidate.displayName)
          DropdownMenuItem(
            text = { Text(text = "$cleanName · +$stepLabel") },
            onClick = { onAddExtraIngredient(candidate) },
          )
        }
      }
    }
  }
}

// Steps COUNT items by 1 unit and GRAM / MILLILITER items by 25 g or ml increments.
private fun overrideCoreStepSizeForUnit(unit: BaseUnit): Double = when (unit) {
  BaseUnit.COUNT -> 1.0
  BaseUnit.GRAM, BaseUnit.MILLILITER -> 25.0
}

// Formats numeric quantities cleanly without ".0" suffix on whole integers.
private fun overrideCoreFormatQty(quantity: Double, unit: BaseUnit): String {
  // Round to 2 decimal places to avoid IEEE-754 floating-point drift on repeated steps.
  val rounded = (quantity * 100.0).roundToInt() / 100.0
  val numeric = when {
    rounded == rounded.toLong().toDouble() -> rounded.toLong().toString()
    else -> rounded.toString()
  }
  return "$numeric ${unit.wireCode}"
}

/** Warm Daylight preview of `CookedOverrideSheetContent` with searchable swap filters. */
@Preview(name = "CookedOverrideSheet Daylight", showBackground = true, widthDp = 412, heightDp = 920)
@Composable
fun CookedOverrideSheetPreview() {
  // Verify searchable swap filter pills, Move Day, Pin Routine, and Ate Leftovers split.
  val breakfastCard = PreviewFixtures.resolvedCardsForDay(dayIndex = 0, currentHour24 = 7).first()
  val swaps = PreviewFixtures.mondayInstantSwapsBySlotId[breakfastCard.slot.slotId].orEmpty()
  FamilyKitchenBoardTheme(darkTheme = false) {
    Surface(color = KitchenTheme.extendedColors.linen) {
      CookedOverrideSheetContent(
        card = breakfastCard,
        catalog = PreviewFixtures.catalog,
        instantSwapCandidates = swaps,
        allRecipes = PreviewFixtures.recipes,
        preferredCuisines = PreviewFixtures.householdProfile.preferredCuisines,
      )
    }
  }
}

/** Ambient Counter Dark preview of `CookedOverrideSheetContent`. */
@Preview(name = "CookedOverrideSheet Counter Dark", showBackground = true, widthDp = 412, heightDp = 920)
@Composable
fun CookedOverrideSheetDarkPreview() {
  // Verify Tier-2 Staple pill and EC-05 household default toggle in Counter Dark mode.
  val dinnerCard = PreviewFixtures.resolvedCardsForDay(dayIndex = 0, currentHour24 = 20).last()
  val swaps = PreviewFixtures.mondayInstantSwapsBySlotId[dinnerCard.slot.slotId].orEmpty()
  FamilyKitchenBoardTheme(darkTheme = true) {
    Surface(color = KitchenTheme.extendedColors.linen) {
      CookedOverrideSheetContent(
        card = dinnerCard,
        catalog = PreviewFixtures.catalog,
        instantSwapCandidates = swaps,
        allRecipes = PreviewFixtures.recipes,
      )
    }
  }
}
