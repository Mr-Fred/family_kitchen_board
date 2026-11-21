package com.familykitchen.board.ui.recipes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.HouseholdProfile
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.MAX_WEEKDAY_BREAKFAST_MINUTES
import com.familykitchen.board.domain.MealSlotType
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.RecipePantryMatchSummary
import com.familykitchen.board.domain.WorkoutTiming
import com.familykitchen.board.domain.buildFamilyGoalPlatingGuide
import com.familykitchen.board.domain.isWeekdayBreakfastEligible
import com.familykitchen.board.ui.board.ChildSafetyCalloutBox
import com.familykitchen.board.ui.theme.KitchenTheme

// Pantry Match card, IntrinsicSize.Max 3-stat grid + muted macro bars, non-clipping 52.dp Cook Mode
// + Mic CTA, EC-08/EC-07 Family Goal Plating accordion, and 48.dp Yield Scale card.

// Short 3-letter weekday pills used inside the inline Plan scheduler.
private val PLAN_WEEKDAY_LABELS: List<String> = listOf(
  "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun",
)

// Yield multiplier presets bound to PantryExpiryEngine.scaleRecipeForYield.
internal enum class YieldPreset {
  SCALE_1X,
  SCALE_2X,
  SCALE_4X,
  CUSTOM,
}

/**
 * Renders the Pantry Inventory Status card, equal-height 3-column `Prep | Cook | Energy` grid with
 * muted macro micro-bars, and Primary Quick Actions (`Start Cook Mode` + `52.dp` `Mic`, `Plan`, `Share`).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PantryMatchAndQuickActionsSection(
  recipe: Recipe,
  matchSummary: RecipePantryMatchSummary,
  householdProfile: HouseholdProfile,
  defaultDayIndex: Int,
  onQueueAllMissingIngredients: () -> Unit,
  onStartCookMode: () -> Unit,
  onStartHandsFreeCookMode: () -> Unit = {},
  onScheduleToMealPlan: (dayIndex: Int, slotType: MealSlotType) -> Unit,
  onShareWithKitchen: () -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Scope match-detail and inline-plan-picker state to `recipe.recipeId` so switching recipes resets.
  val showMatchDetailsState = rememberSaveable(recipe.recipeId) { mutableStateOf(false) }
  val showPlanPickerState = rememberSaveable(recipe.recipeId) { mutableStateOf(false) }
  val chosenDayState = rememberSaveable(recipe.recipeId) { mutableIntStateOf(defaultDayIndex.coerceIn(0, 6)) }
  // Default target slot to Dinner -> Lunch -> Breakfast depending on recipe eligibility.
  val defaultSlot = when {
    MealSlotType.DINNER in recipe.allowedSlots -> MealSlotType.DINNER
    MealSlotType.LUNCH in recipe.allowedSlots -> MealSlotType.LUNCH
    else -> MealSlotType.BREAKFAST
  }
  val chosenSlotNameState = rememberSaveable(recipe.recipeId) { mutableStateOf(defaultSlot.name) }
  // True when 100% of required BOM lines are covered by current Fridge/Pantry/Freezer stock.
  val allAvailable = matchSummary.missingStaples.isEmpty()

  Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
    // 1. Pantry Inventory Status Pill Card with 1.dp warm border and 48.dp Details toggle.
    Surface(
      shape = KitchenTheme.shapes.card2xl,
      color = MaterialTheme.colorScheme.surfaceContainerLowest,
      border = BorderStroke(1.dp, extendedColors.borderSubtle),
      shadowElevation = 0.dp,
    ) {
      Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Box(
              modifier = Modifier.size(30.dp).clip(CircleShape).background(if (allAvailable) extendedColors.sageContainer else extendedColors.tertiaryFixed),
              contentAlignment = Alignment.Center,
            ) {
              Icon(Icons.Default.Check, contentDescription = null, tint = if (allAvailable) extendedColors.onSageContainer else extendedColors.onTertiaryFixed, modifier = Modifier.size(16.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
              // Summarize matched vs missing BOM lines and expiring-stock value rescued.
              val headline = when {
                allAvailable -> "All ${matchSummary.totalRequiredCount} ingredients in stock"
                else -> "${matchSummary.matchedCount}/${matchSummary.totalRequiredCount} in stock · ${matchSummary.missingStaples.size} missing"
              }
              val subline = when {
                matchSummary.isZeroFoodWasteMatch -> "Saves ${householdProfile.formatMoney(matchSummary.rescuedValueUsd)} expiring stock"
                else -> householdProfile.marketRegionLabel
              }
              Text(headline, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = extendedColors.herbalSage, maxLines = 2, overflow = TextOverflow.Ellipsis)
              Text(subline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
          }
          Spacer(modifier = Modifier.width(8.dp))
          Surface(
            onClick = { showMatchDetailsState.value = !showMatchDetailsState.value },
            shape = CircleShape,
            color = extendedColors.parchment,
            border = BorderStroke(1.dp, extendedColors.borderSubtle),
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          ) {
            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
              Text("Details", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            }
          }
        }

        // Expandable match drawer allowing 1-tap queueing of missing whole packages.
        AnimatedVisibility(visible = showMatchDetailsState.value) {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (matchSummary.matchedIngredients.isNotEmpty()) {
              Text("In Kitchen: ${matchSummary.matchedIngredients.joinToString(", ") { it.displayName }}", style = MaterialTheme.typography.bodySmall)
            }
            if (matchSummary.missingStaples.isNotEmpty()) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = "Missing: ${matchSummary.missingStaples.joinToString(", ") { it.displayName }}",
                  style = MaterialTheme.typography.bodySmall,
                  color = extendedColors.terracotta,
                  modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                Button(
                  onClick = onQueueAllMissingIngredients,
                  contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                  shape = CircleShape,
                  modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                  Text("+ Queue All", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                }
              }
            }
          }
        }
      }
    }

    // 2. Equal-Height (`IntrinsicSize.Max`) 3-Stat Grid + Compact 3-Dot Macro Micro-Bars.
    Surface(
      shape = KitchenTheme.shapes.card2xl,
      color = MaterialTheme.colorScheme.surfaceContainerLow,
      border = BorderStroke(1.dp, extendedColors.borderSubtle),
      shadowElevation = 0.dp,
    ) {
      Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // IntrinsicSize.Max keeps all 3 tiles uniform on elevated S22 font scaling.
        Row(
          modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          MetadataStatTile(Icons.Default.Timer, extendedColors.herbalSage, "Prep", "${recipe.rawPrepMinutes}m", Modifier.weight(1f).fillMaxHeight())
          MetadataStatTile(Icons.Default.Restaurant, extendedColors.terracotta, "Cook", "${recipe.cookMinutes}m", Modifier.weight(1f).fillMaxHeight())
          MetadataStatTile(Icons.Default.LocalFireDepartment, extendedColors.expiringSoonIndicator, "Energy", "${recipe.caloriesPerServing} kcal", Modifier.weight(1f).fillMaxHeight())
        }

        // Per-serving macro summary + 3 understated 3.dp warm micro-bars.
        Column(modifier = Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(
            text = "${recipe.proteinGrams}g P • ${recipe.carbsGrams}g C • ${recipe.fatGrams}g F per serving",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          val macroSum = (recipe.proteinGrams + recipe.carbsGrams + recipe.fatGrams).coerceAtLeast(1).toFloat()
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReaderMacroMicroBar((recipe.proteinGrams / macroSum).coerceIn(0.12f, 1.0f), extendedColors.herbalSage, Modifier.weight(1f))
            ReaderMacroMicroBar((recipe.carbsGrams / macroSum).coerceIn(0.12f, 1.0f), extendedColors.expiringSoonIndicator, Modifier.weight(1f))
            ReaderMacroMicroBar((recipe.fatGrams / macroSum).coerceIn(0.12f, 1.0f), extendedColors.terracotta, Modifier.weight(1f))
          }
        }
      }
    }

    // 3. Primary Quick Actions: defaultMinSize(minHeight = 52.dp) prevents horizontal word slicing.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Button(
          onClick = onStartCookMode,
          modifier = Modifier.weight(1f).defaultMinSize(minHeight = 52.dp),
          shape = CircleShape,
          colors = ButtonDefaults.buttonColors(containerColor = extendedColors.herbalSage, contentColor = Color.White),
        ) {
          Icon(Icons.Default.PlayCircle, contentDescription = null, modifier = Modifier.size(20.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text("Start Cook Mode", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }

        // 52.dp Mic button launches Hands-Free Cook Mode directly, replacing FloatingHandsFreeVoiceBar.
        FilledTonalIconButton(
          onClick = onStartHandsFreeCookMode,
          colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = extendedColors.terracottaContainer,
            contentColor = extendedColors.onTerracottaContainer,
          ),
          modifier = Modifier.size(52.dp),
        ) {
          Icon(Icons.Default.Mic, contentDescription = "Start Hands-Free Voice Cook Mode", modifier = Modifier.size(22.dp))
        }
      }

      // Secondary 2-column action row using defaultMinSize(minHeight = 48.dp) so text never chops.
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Short 3-letter weekday pill ("Plan (Mon)") keeps secondary button on 1 clean line at 1.35x scale.
        val dayShort = PLAN_WEEKDAY_LABELS.getOrElse(chosenDayState.intValue) { "Mon" }
        Surface(
          onClick = { showPlanPickerState.value = !showPlanPickerState.value },
          modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
          shape = CircleShape,
          color = extendedColors.parchment,
          border = BorderStroke(1.dp, extendedColors.borderSubtle),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
          ) {
            Icon(Icons.Default.CalendarToday, contentDescription = null, tint = extendedColors.herbalSage, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Plan ($dayShort)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
          }
        }

        // Offline plain-text ACTION_SEND share pill ("Share" replaces truncated "Share with").
        Surface(
          onClick = onShareWithKitchen,
          modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
          shape = CircleShape,
          color = extendedColors.parchment,
          border = BorderStroke(1.dp, extendedColors.borderSubtle),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
          ) {
            Icon(Icons.Default.GroupAdd, contentDescription = null, tint = extendedColors.terracotta, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Share", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
          }
        }
      }

      // Inline EC-01-validated Day & Slot scheduler revealed when tapping "Plan (Day)".
      AnimatedVisibility(visible = showPlanPickerState.value) {
        Surface(
          shape = KitchenTheme.shapes.inputDefault,
          color = MaterialTheme.colorScheme.surfaceContainerLowest,
          border = BorderStroke(1.dp, extendedColors.borderSubtle),
        ) {
          Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Schedule '${recipe.title}' onto Plan:", style = MaterialTheme.typography.labelMedium)
            // 48.dp weekday FilterChips (`Mon`..`Sun`).
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              PLAN_WEEKDAY_LABELS.forEachIndexed { idx, label ->
                FilterChip(
                  selected = chosenDayState.intValue == idx,
                  onClick = { chosenDayState.intValue = idx },
                  modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                  label = { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false) },
                )
              }
            }
            // Enforce EC-01: only allow BREAKFAST selection if calibratedActiveMinutes <= 25m.
            val eligibleSlots = MealSlotType.entries.filter { slot ->
              slot != MealSlotType.BREAKFAST || isWeekdayBreakfastEligible(recipe)
            }
            // FlowRow prevents horizontal button clipping when Confirm sits next to slot chips.
            FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              eligibleSlots.forEach { slot ->
                FilterChip(
                  selected = chosenSlotNameState.value == slot.name,
                  onClick = { chosenSlotNameState.value = slot.name },
                  modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                  label = { Text(slot.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false) },
                )
              }
              Button(
                onClick = {
                  // Resolve chosen slot safely against EC-01 eligible slots before committing.
                  val targetSlot = eligibleSlots.find { it.name == chosenSlotNameState.value } ?: eligibleSlots.firstOrNull() ?: MealSlotType.DINNER
                  onScheduleToMealPlan(chosenDayState.intValue, targetSlot)
                  showPlanPickerState.value = false
                },
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
              ) {
                Text("Confirm", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
              }
            }
            // Explain clearly why >25m dishes omit weekday Breakfast scheduling (`EC-01`).
            if (!isWeekdayBreakfastEligible(recipe)) {
              Text(
                text = "Takes ${recipe.calibratedActiveMinutes}m (> ${MAX_WEEKDAY_BREAKFAST_MINUTES}m weekday breakfast cap); Lunch & Dinner enabled.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }
  }
}

// Understated 3.dp rounded macro bar segment inside the 3-stat reader card.
@Composable
private fun ReaderMacroMicroBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
  Box(modifier = modifier.height(3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer)) {
    Box(modifier = Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(CircleShape).background(color))
  }
}

// Equal-height ceramic stat tile used for Prep, Cook, and Energy metrics.
@Composable
private fun MetadataStatTile(
  icon: ImageVector,
  iconTint: Color,
  label: String,
  value: String,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    border = BorderStroke(1.dp, KitchenTheme.extendedColors.borderSubtle),
    shadowElevation = 0.dp,
  ) {
    Column(
      modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
      Spacer(modifier = Modifier.height(2.dp))
      Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
      Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
  }
}

// Renders EC-08 Honey-Amber child safety callout + collapsible 4-member 1-pot plating & EC-07 boost.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FamilySafetyAndPlatingAccordion(
  recipe: Recipe,
  catalog: List<IngredientCatalogItem>,
  householdProfile: HouseholdProfile,
  onEditFamilyGoals: () -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Scope accordion and workout preview state to `recipe.recipeId`.
  val expandedState = rememberSaveable(recipe.recipeId) { mutableStateOf(false) }
  val workoutNameState = rememberSaveable(recipe.recipeId) { mutableStateOf(WorkoutTiming.NONE.name) }
  val selectedWorkout = WorkoutTiming.entries.find { it.name == workoutNameState.value } ?: WorkoutTiming.NONE

  // Synthesize stage-adapted safety guidance and 4-member plating instructions deterministically.
  val safetyTip = householdProfile.youngestStage.formatPrepTip(recipe.toddlerPrepTip)
  val platingGuide = remember(recipe, catalog, householdProfile) {
    buildFamilyGoalPlatingGuide(
      recipe = recipe,
      catalog = catalog,
      memberGoals = householdProfile.members,
      activeLifeEvents = householdProfile.activeLifeEvents,
      youngestStage = householdProfile.youngestStage,
    )
  }

  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    // ChildSafetyCalloutBox automatically deduplicates trailing "for 2yo." age repetitions.
    if (!safetyTip.isNullOrBlank()) {
      ChildSafetyCalloutBox(safetyText = safetyTip)
    }

    Surface(
      shape = KitchenTheme.shapes.card2xl,
      color = MaterialTheme.colorScheme.surfaceContainerLow,
      border = BorderStroke(1.dp, extendedColors.borderSubtle),
    ) {
      Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clickable { expandedState.value = !expandedState.value },
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "1-Pot Family Plating & 1x Workout Boost",
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
          Icon(if (expandedState.value) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = "Toggle family plating")
        }

        // EC-07 invariant: workout extraBomLines apply flat (1x), unscaled by Yield Scale.
        AnimatedVisibility(visible = expandedState.value) {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val workoutChoices = listOf(
              WorkoutTiming.NONE to "Standard",
              WorkoutTiming.PRE_WORKOUT_CARB to "Carb (1x)",
              WorkoutTiming.POST_WORKOUT_HIGH_PROTEIN to "Protein (1x)",
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
              workoutChoices.forEachIndexed { idx, (timing, label) ->
                SegmentedButton(
                  selected = selectedWorkout == timing,
                  onClick = { workoutNameState.value = timing.name },
                  shape = SegmentedButtonDefaults.itemShape(index = idx, count = workoutChoices.size),
                  modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                  label = { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false) },
                )
              }
            }

            val activeBoost = recipe.workoutModifiers.find { it.profile == selectedWorkout }
            if (activeBoost != null && selectedWorkout != WorkoutTiming.NONE) {
              Text(activeBoost.instructionAddOn, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = extendedColors.terracotta)
            }

            platingGuide.values.forEach { line ->
              Text("• $line", style = MaterialTheme.typography.bodySmall)
            }

            TextButton(onClick = onEditFamilyGoals, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
              Text("Customize Household Goals", style = MaterialTheme.typography.labelMedium)
            }
          }
        }
      }
    }
  }
}

// Yield Scale card (1x | 2x | 4x | Custom) with 44.dp+ preset pills and 48.dp custom steppers.
@Composable
internal fun YieldScaleCard(
  servings: Int,
  selectedPreset: YieldPreset,
  onSelectPreset: (YieldPreset) -> Unit,
  onDecrementCustomServings: () -> Unit,
  onIncrementCustomServings: () -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  val presets = listOf(
    YieldPreset.SCALE_1X to "1x",
    YieldPreset.SCALE_2X to "2x",
    YieldPreset.SCALE_4X to "4x",
    YieldPreset.CUSTOM to "Custom",
  )

  Surface(
    shape = KitchenTheme.shapes.card2xl,
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
    shadowElevation = 0.dp,
  ) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Default.Restaurant, contentDescription = null, tint = extendedColors.herbalSage, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Yield ($servings srv)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        Surface(shape = CircleShape, color = extendedColors.parchment, border = BorderStroke(1.dp, extendedColors.borderSubtle)) {
          Row(modifier = Modifier.padding(2.dp)) {
            presets.forEach { (preset, label) ->
              val isSelected = selectedPreset == preset
              Surface(
                onClick = { onSelectPreset(preset) },
                shape = CircleShape,
                color = if (isSelected) extendedColors.herbalSage else Color.Transparent,
                modifier = Modifier.defaultMinSize(minHeight = 44.dp),
              ) {
                Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                  Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                  )
                }
              }
            }
          }
        }
      }

      // Custom serving stepper shown with 48.dp OutlinedIconButtons when "Custom" is selected.
      if (selectedPreset == YieldPreset.CUSTOM) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text("Custom servings:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedIconButton(
              onClick = onDecrementCustomServings,
              border = BorderStroke(1.dp, extendedColors.borderSubtle),
              modifier = Modifier.size(48.dp),
            ) { Icon(Icons.Default.Remove, contentDescription = "Decrease servings", modifier = Modifier.size(18.dp)) }
            Text("$servings srv", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            OutlinedIconButton(
              onClick = onIncrementCustomServings,
              border = BorderStroke(1.dp, extendedColors.borderSubtle),
              modifier = Modifier.size(48.dp),
            ) { Icon(Icons.Default.Add, contentDescription = "Increase servings", modifier = Modifier.size(18.dp)) }
          }
        }
      }
    }
  }
}
