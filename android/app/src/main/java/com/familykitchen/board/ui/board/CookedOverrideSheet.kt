package com.familykitchen.board.ui.board

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.ResolvedMealCard
import com.familykitchen.board.domain.WorkoutTiming
import com.familykitchen.board.domain.resolveEffectiveMealBom
import com.familykitchen.board.ui.preview.PreviewFixtures
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme
import kotlin.math.max
import kotlin.math.roundToInt

// Adjust / Override Cooked Meal bottom sheet supporting EC-04..EC-07, EC-11..EC-13 with 48.dp+
// steppers and defaultMinSize buttons for compact 360dp 1.35x font-scale resilience.

/**
 * Stateless Material 3 [ModalBottomSheet] presenting meal adjustments, instant swaps, and
 * per-ingredient cooked quantity overrides prior to a single `O(1)` atomic pantry deduction.
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
) {
  // Skip partially expanded stop so per-ingredient steppers and Confirm CTA appear immediately.
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
        // Commit O(1) cooked deduction payload and close modal bottom sheet cleanly.
        onConfirmCookedDeduction(payload)
        onDismiss()
      },
      onSelectWorkoutModifier = onSelectWorkoutModifier,
      onSwapToInStockMeal = { slotId, recipeId ->
        // Apply EC-13 100% in-stock replacement meal and dismiss sheet.
        onSwapToInStockMeal(slotId, recipeId)
        onDismiss()
      },
      onMarkSkippedOrLeftovers = { slotId ->
        // Mark slot COMPLETED_SKIPPED (EC-12: 0 deduction + release reservations) and dismiss.
        onMarkSkippedOrLeftovers(slotId)
        onDismiss()
      },
      onReplaceWithCustomMeal = { slotId ->
        // Hand off slot replacement to CustomMealSheet and close override sheet.
        onReplaceWithCustomMeal(slotId)
        onDismiss()
      },
    )
  }
}

/**
 * Extracted pure-Compose inner body for [CookedOverrideSheet], enabling standalone `@Preview`
 * inspection without requiring an active Android window token.
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
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Index catalog by ingredientId once per sheet session for fast stepper row rendering.
  val catalogById = remember(catalog) { catalog.associateBy { it.ingredientId } }

  // Mirror slot workout modifier locally so BOM quantities update immediately when toggled.
  val workoutState = remember(card.slot.slotId, card.slot.activeWorkoutModifier) {
    mutableStateOf(card.slot.activeWorkoutModifier)
  }
  // Track EC-05 learned household default checkbox and extra-ingredient dropdown state.
  val saveDefaultState = remember(card.slot.slotId) { mutableStateOf(false) }
  val pickerExpandedState = remember { mutableStateOf(false) }
  // Store user-adjusted cooked quantities keyed by ingredientId (`EC-04`, `EC-06`).
  val customOverrides = remember(card.slot.slotId) { mutableStateMapOf<String, Double>() }

  // Recompute baseline effective BOM dynamically whenever Dad's flat 1x workout boost changes.
  val baselineBom = remember(card.recipe, card.slot.targetServings, workoutState.value, catalog) {
    resolveEffectiveMealBom(
      recipe = card.recipe,
      targetServings = card.slot.targetServings,
      activeWorkoutModifier = workoutState.value,
      catalog = catalog,
    )
  }

  // Preserve stable insertion order across planned recipe ingredients and ad-hoc extras (`EC-06`).
  val orderedIngredientIds = remember(baselineBom, customOverrides.keys.toList()) {
    linkedSetOf<String>().apply {
      addAll(baselineBom.keys)
      addAll(customOverrides.keys)
    }.toList()
  }

  // Scrollable modal body padded for software keyboard (`imePadding`) on compact 360dp screens.
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
        text = "Adjust Meal or Confirm Cooked",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = "${card.recipe.title} • ${card.slot.targetServings} family servings",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    // EC-07: Flat 1x Pre-Workout Carb or Post-Workout Protein selector for Dad's plate.
    WorkoutBoostSelectorSection(
      selectedWorkout = workoutState.value,
      onSelectWorkout = { nextProfile ->
        workoutState.value = nextProfile
        onSelectWorkoutModifier(card.slot.slotId, nextProfile)
      },
    )

    // EC-12 / EC-13: 1-tap in-stock swaps, Leftovers/Skip without deduction, or Custom Meal builder.
    SlotAlternativeActionsSection(
      slotId = card.slot.slotId,
      instantSwapCandidates = instantSwapCandidates,
      onSwapToInStockMeal = onSwapToInStockMeal,
      onMarkSkippedOrLeftovers = onMarkSkippedOrLeftovers,
      onReplaceWithCustomMeal = onReplaceWithCustomMeal,
    )

    HorizontalDivider(color = extendedColors.borderSubtle)

    // Per-ingredient 48.dp +/- quantity steppers (EC-04) & Tier-2 staple protection (EC-11).
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        text = "Ingredients Used (±1 count / ±25 g·ml)",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
      )

      orderedIngredientIds.forEach { ingredientId ->
        val item = catalogById[ingredientId]
        val tier = item?.tier ?: IngredientTier.TIER_1_QUANTITATIVE
        val baseUnit = item?.baseUnit ?: BaseUnit.COUNT
        val plannedQty = baselineBom[ingredientId] ?: 0.0
        val effectiveQty = customOverrides[ingredientId] ?: plannedQty
        val stepDelta = stepSizeForUnit(baseUnit)

        IngredientOverrideStepperRow(
          displayName = item?.displayName ?: ingredientId,
          tier = tier,
          baseUnit = baseUnit,
          plannedQuantity = plannedQty,
          effectiveQuantity = effectiveQty,
          isAdHocExtra = ingredientId !in baselineBom,
          onDecrement = {
            // Clamp overridden cooked usage at >= 0.0 so negative deductions never occur.
            customOverrides[ingredientId] = max(0.0, effectiveQty - stepDelta)
          },
          onIncrement = {
            customOverrides[ingredientId] = effectiveQty + stepDelta
          },
        )
      }
    }

    // EC-06: Quick "+1 Tomato" shortcut and full Tier-1 extra ingredient picker.
    ExtraIngredientPickerRow(
      catalog = catalog,
      pickerExpanded = pickerExpandedState.value,
      onTogglePicker = { pickerExpandedState.value = !pickerExpandedState.value },
      onDismissPicker = { pickerExpandedState.value = false },
      onAddExtraIngredient = { item ->
        val current = customOverrides[item.ingredientId] ?: (baselineBom[item.ingredientId] ?: 0.0)
        customOverrides[item.ingredientId] = current + stepSizeForUnit(item.baseUnit)
        pickerExpandedState.value = false
      },
      onAddOneTomatoShortcut = {
        val current = customOverrides["ing_tomato"] ?: (baselineBom["ing_tomato"] ?: 0.0)
        customOverrides["ing_tomato"] = current + 1.0
      },
    )

    // EC-05: "Save as our household default for this recipe" checkbox.
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
          Text(
            text = "Normalizes adjusted Tier-1 amounts to ${card.recipe.baseServings} base servings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    // Primary atomic confirmation CTA with defaultMinSize(minHeight = 52.dp) so 2 lines never clip.
    Button(
      onClick = {
        // Emit null overrides when untouched so domain uses exact planned + workout BOM quantities.
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
        text = "Confirm Cooked & Deduct",
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
private fun WorkoutBoostSelectorSection(
  selectedWorkout: WorkoutTiming,
  onSelectWorkout: (WorkoutTiming) -> Unit,
) {
  // Short single-word labels ("None | Carb | Protein") prevent segmented clipping at 1.35x scale.
  val options = listOf(
    WorkoutTiming.NONE to "None",
    WorkoutTiming.PRE_WORKOUT_CARB to "Carb",
    WorkoutTiming.POST_WORKOUT_HIGH_PROTEIN to "Protein",
  )

  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(
      text = "Dad's Workout Boost (Flat 1x Add-On)",
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SlotAlternativeActionsSection(
  slotId: String,
  instantSwapCandidates: List<Recipe>,
  onSwapToInStockMeal: (slotId: String, newRecipeId: String) -> Unit,
  onMarkSkippedOrLeftovers: (slotId: String) -> Unit,
  onReplaceWithCustomMeal: (slotId: String) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      text = "Swap to In-Stock Meal (100% Pantry Covered)",
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurface,
    )
    when {
      instantSwapCandidates.isEmpty() -> {
        Text(
          text = "No alternate 100% in-stock meals available right now.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      else -> {
        // Deduplicate candidate IDs so LazyRow keys never collide.
        val uniqueSwaps = instantSwapCandidates.distinctBy { it.recipeId }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          items(items = uniqueSwaps, key = { it.recipeId }) { swapRecipe ->
            AssistChip(
              onClick = { onSwapToInStockMeal(slotId, swapRecipe.recipeId) },
              modifier = Modifier.defaultMinSize(minHeight = 48.dp),
              label = {
                Text(
                  text = "Swap: ${swapRecipe.title} (${swapRecipe.calibratedActiveMinutes}m)",
                  maxLines = 1,
                  softWrap = false,
                )
              },
              leadingIcon = {
                Icon(
                  imageVector = Icons.Default.SwapHoriz,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                )
              },
            )
          }
        }
      }
    }

    // EC-12 Leftovers / Skip marks slot COMPLETED_SKIPPED and releases reserved ingredients to rest of week.
    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      FilledTonalButton(
        onClick = { onMarkSkippedOrLeftovers(slotId) },
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
      ) {
        Icon(imageVector = Icons.Default.SkipNext, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = "Leftovers / Skip", maxLines = 1, softWrap = false)
      }

      OutlinedButton(
        onClick = { onReplaceWithCustomMeal(slotId) },
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
      ) {
        Icon(imageVector = Icons.Default.EditNote, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = "Custom Meal", maxLines = 1, softWrap = false)
      }
    }
  }
}

@Composable
private fun IngredientOverrideStepperRow(
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
  // Weight left ingredient column so right-hand 48.dp - / + steppers stay on-screen at 360dp.
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
          text = if (isAdHocExtra) "$displayName (Extra)" else displayName,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        // Explain EC-11 staple protection vs EC-04/EC-06 adjusted quantitative deductions.
        val subtitle = when {
          tier == IngredientTier.TIER_2_STAPLE -> "Kitchen Staple (Never auto-deducted)"
          isAdHocExtra -> "Ad-hoc extra deducted in batch"
          effectiveQuantity != plannedQuantity ->
            "Planned: ${formatQuantityLabel(plannedQuantity, baseUnit)} → Adjusted"
          else -> "Planned: ${formatQuantityLabel(plannedQuantity, baseUnit)}"
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
          // Tier-2 oils, vinegars, and spices show a read-only Staple pill instead of gram steppers.
          Surface(
            shape = RoundedCornerShape(999.dp),
            color = extendedColors.sageContainer,
          ) {
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
          // 48.dp square OutlinedIconButtons meet Android messy-hands countertop touch standards.
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
              Icon(
                imageVector = Icons.Default.Remove,
                contentDescription = "Decrease $displayName",
                modifier = Modifier.size(18.dp),
              )
            }
            Text(
              text = formatQuantityLabel(effectiveQuantity, baseUnit),
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
              Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Increase $displayName",
                modifier = Modifier.size(18.dp),
              )
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExtraIngredientPickerRow(
  catalog: List<IngredientCatalogItem>,
  pickerExpanded: Boolean,
  onTogglePicker: () -> Unit,
  onDismissPicker: () -> Unit,
  onAddExtraIngredient: (IngredientCatalogItem) -> Unit,
  onAddOneTomatoShortcut: () -> Unit,
) {
  // Restrict ad-hoc extra picker to locally available Tier-1 quantitative ingredients.
  val extraCandidates = remember(catalog) {
    catalog.filter { it.tier == IngredientTier.TIER_1_QUANTITATIVE && it.isLocallyAvailable }
  }

  FlowRow(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    // 1-tap "+1 Tomato" shortcut verifying EC-06 ad-hoc cooked extra deduction.
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

    Box {
      OutlinedButton(
        onClick = onTogglePicker,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
      ) {
        Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = "+ Extra Ingredient", maxLines = 1, softWrap = false)
      }

      DropdownMenu(
        expanded = pickerExpanded,
        onDismissRequest = onDismissPicker,
      ) {
        extraCandidates.forEach { candidate ->
          val stepLabel = formatQuantityLabel(stepSizeForUnit(candidate.baseUnit), candidate.baseUnit)
          DropdownMenuItem(
            text = { Text(text = "${candidate.displayName} (+$stepLabel)") },
            onClick = { onAddExtraIngredient(candidate) },
          )
        }
      }
    }
  }
}

// Steps COUNT items by 1 unit and weight/volume items by 25 g/ml for fine cooked adjustments.
private fun stepSizeForUnit(unit: BaseUnit): Double = when (unit) {
  BaseUnit.COUNT -> 1.0
  BaseUnit.GRAM, BaseUnit.MILLILITER -> 25.0
}

// Formats quantities cleanly without trailing ".0" noise on integer values.
private fun formatQuantityLabel(quantity: Double, unit: BaseUnit): String {
  val rounded = (quantity * 100.0).roundToInt() / 100.0
  val numeric = when {
    rounded == rounded.toLong().toDouble() -> rounded.toLong().toString()
    else -> rounded.toString()
  }
  return "$numeric ${unit.wireCode}"
}

/** Warm Daylight preview of `CookedOverrideSheetContent` without modal window dependencies. */
@Preview(name = "CookedOverrideSheet — Daylight", showBackground = true, widthDp = 412, heightDp = 880)
@Composable
fun CookedOverrideSheetPreview() {
  // Verify 48.dp +/- steppers and non-clipping Confirm CTA in Warm Daylight mode.
  val breakfastCard = PreviewFixtures.resolvedCardsForDay(dayIndex = 0, currentHour24 = 7).first()
  val swaps = PreviewFixtures.mondayInstantSwapsBySlotId[breakfastCard.slot.slotId].orEmpty()
  FamilyKitchenBoardTheme(darkTheme = false) {
    Surface(color = KitchenTheme.extendedColors.linen) {
      CookedOverrideSheetContent(
        card = breakfastCard,
        catalog = PreviewFixtures.catalog,
        instantSwapCandidates = swaps,
      )
    }
  }
}

/** Ambient Counter Dark preview of `CookedOverrideSheetContent`. */
@Preview(name = "CookedOverrideSheet — Counter Dark", showBackground = true, widthDp = 412, heightDp = 880)
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
      )
    }
  }
}
