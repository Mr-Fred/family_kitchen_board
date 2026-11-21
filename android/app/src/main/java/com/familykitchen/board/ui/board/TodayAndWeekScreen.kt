package com.familykitchen.board.ui.board

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.EVENING_PREP_START_HOUR_24
import com.familykitchen.board.domain.HouseholdProfile
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.MealSlotStatus
import com.familykitchen.board.domain.MealSlotType
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.ResolvedMealCard
import com.familykitchen.board.domain.WeeklyBoard
import com.familykitchen.board.domain.WeeklyPrepSessionPlan
import com.familykitchen.board.ui.preview.PreviewFixtures
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme

// Planner & Daily Menu screen: 48.dp 7-day selector strip, calm DailyMacroTelemetryCard with
// 3 understated 4.dp micro-bars, 1-row utility pills, 8:30 PM night-prep banner, and MealSlotCards.

private val WEEKDAY_SHORT_LABELS: List<String> = listOf(
  "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun",
)

private val WEEKDAY_FULL_LABELS: List<String> = listOf(
  "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday",
)

// Daily macro reference denominators scaling the 3 understated 4.dp telemetry bars.
private const val DAILY_PROTEIN_TARGET_G: Float = 120f
private const val DAILY_CARBS_TARGET_G: Float = 220f
private const val DAILY_FAT_TARGET_G: Float = 75f

/**
 * Stateless primary `Plan` / `Today` screen Composable rendering the `48.dp` 7-day selector strip,
 * [DailyMacroTelemetryCard] (`kcal` pill + 3 understated `4.dp` micro-bars for `Protein`, `Carbs`,
 * `Fat`), 1-row utility pills (`+ Add Meal`, `Batch Prep`, `Goals`), conditional 8:30 PM
 * Night-Before 5-Min Prep card, and scannable [MealSlotCard]s (`initiallyExpanded = false` on portrait).
 *
 * @param weeklyBoard Active 21-slot [WeeklyBoard] used to render per-day completion dots.
 * @param dayMealCards Chronologically ordered [ResolvedMealCard]s for [activeDayIndex].
 * @param activeDayIndex Currently viewed weekday index (`0 = Mon`..`6 = Sun`).
 * @param todayDayIndex Real or simulated current weekday index highlighted with a terracotta dot.
 * @param currentHour24 24-hour clock value (`0..23`); activates night-prep card when `>= 20`.
 * @param isWideLayout Forces the 3-column counter layout even on medium-width screens when `true`.
 * @param householdProfile Active [HouseholdProfile] driving child stage labels and 4-way plating.
 * @param catalog Household ingredient catalog passed to [MealSlotCard] plating synthesis.
 * @param weeklyPrepPlan Optional [WeeklyPrepSessionPlan] displaying batch prep minutes.
 * @param instantSwapsBySlotId Map from `slotId` to `EC-13` 100% in-stock replacement recipes.
 * @param tomorrowBreakfastRecipe Optional tomorrow breakfast recipe checked for 8:30 PM prep steps.
 * @param onSelectDay Invoked with `0..6` when tapping a weekday pill in the 7-day strip.
 * @param onOpenCustomMealSheet Invoked when tapping the `+ Add Meal` utility pill.
 * @param onOpenWeeklyPrep Invoked when tapping the `Batch Prep` utility pill.
 * @param onOpenFamilyGoalsSheet Invoked when tapping `Goals` or `Edit Goals`.
 * @param onOpenAiChefTab Retained for backward-compatible callers.
 * @param onStartCookingSlot Invoked with `slotId` to launch Cook Mode / Pacer.
 * @param onQuickCookSlot Invoked with `slotId` for 1-tap `O(1)` atomic pantry deduction.
 * @param onOpenAdjustSlot Invoked with `slotId` to open `CookedOverrideSheet`.
 * @param onQuickSwapSlotRecipe Invoked with `(slotId, newRecipeId)` for 1-tap `EC-13` swaps.
 * @param onOpenRecipeReader Invoked with `recipeId` when tapping a meal title to open Clean Reader.
 * @param modifier Optional Compose [Modifier].
 */
@Composable
fun TodayAndWeekScreen(
  weeklyBoard: WeeklyBoard,
  dayMealCards: List<ResolvedMealCard>,
  activeDayIndex: Int,
  todayDayIndex: Int = 0,
  currentHour24: Int = 7,
  isWideLayout: Boolean = false,
  householdProfile: HouseholdProfile = HouseholdProfile(),
  catalog: List<IngredientCatalogItem> = emptyList(),
  weeklyPrepPlan: WeeklyPrepSessionPlan? = null,
  instantSwapsBySlotId: Map<String, List<Recipe>> = emptyMap(),
  tomorrowBreakfastRecipe: Recipe? = null,
  onSelectDay: (Int) -> Unit = {},
  onOpenCustomMealSheet: () -> Unit = {},
  onOpenWeeklyPrep: () -> Unit = {},
  onOpenFamilyGoalsSheet: () -> Unit = {},
  onOpenAiChefTab: () -> Unit = {},
  onStartCookingSlot: (String) -> Unit = {},
  onQuickCookSlot: (String) -> Unit = {},
  onOpenAdjustSlot: (String) -> Unit = {},
  onQuickSwapSlotRecipe: (slotId: String, newRecipeId: String) -> Unit = { _, _ -> },
  onOpenRecipeReader: (String) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Clamp incoming day index defensively to valid Monday..Sunday bounds (`0..6`).
  val safeDayIndex = activeDayIndex.coerceIn(0, 6)

  // Resolve night-before prep steps from tomorrow's breakfast first, then active day's breakfast.
  val activeDayBreakfastCard = dayMealCards.firstOrNull {
    it.slot.slotType == MealSlotType.BREAKFAST &&
      it.slot.status == MealSlotStatus.PLANNED &&
      !it.isPreppedAhead
  }
  val nightPrepTargetRecipe = when {
    tomorrowBreakfastRecipe != null && tomorrowBreakfastRecipe.nightBeforePrepSteps.isNotEmpty() ->
      tomorrowBreakfastRecipe
    activeDayBreakfastCard != null && activeDayBreakfastCard.recipe.nightBeforePrepSteps.isNotEmpty() ->
      activeDayBreakfastCard.recipe
    else -> null
  }

  // Switch automatically between 1-column portrait and 3-column wide counter mode at >= 840.dp.
  BoxWithConstraints(
    modifier = modifier
      .fillMaxSize()
      .background(extendedColors.linen),
  ) {
    val useThreeColumnGrid = isWideLayout || maxWidth >= 840.dp

    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(PaddingValues(horizontal = 16.dp, vertical = 12.dp)),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      // 1. Mon–Sun 7-day selector strip (48.dp minHeight, softWrap = false, 1.dp warm border).
      SevenDaySelectorStrip(
        weeklyBoard = weeklyBoard,
        activeDayIndex = safeDayIndex,
        todayDayIndex = todayDayIndex.coerceIn(0, 6),
        onSelectDay = onSelectDay,
      )

      // 2. Calm Daily Macro Telemetry Card summing planned per-person daily macros across slots.
      DailyMacroTelemetryCard(
        dayLabel = WEEKDAY_FULL_LABELS[safeDayIndex],
        effectiveServings = householdProfile.effectiveTargetServings,
        dayMealCards = dayMealCards,
      )

      // 3. Single-row compact utility strip (+ Add Meal | Batch Prep | Goals — zero sparkles).
      PlannerUtilityPillStrip(
        weeklyPrepPlan = weeklyPrepPlan,
        onOpenCustomMealSheet = onOpenCustomMealSheet,
        onOpenWeeklyPrep = onOpenWeeklyPrep,
        onOpenFamilyGoalsSheet = onOpenFamilyGoalsSheet,
      )

      // 4. Conditional 8:30 PM Night-Before 5-Min Prep card (EC-01 <= 25m morning pacing guard).
      if (currentHour24 >= EVENING_PREP_START_HOUR_24 && nightPrepTargetRecipe != null) {
        NightBeforePrepBannerCard(recipe = nightPrepTargetRecipe)
      }

      // 5. Responsive 1-column portrait (all collapsed for instant 3-meal scanning) or 3-column wide grid.
      when {
        dayMealCards.isEmpty() -> {
          // Offer 1-tap custom meal creation whenever an empty day is selected.
          EmptyDaySlotsCard(onOpenCustomMealSheet = onOpenCustomMealSheet)
        }

        useThreeColumnGrid -> {
          // Counter landscape mode expands all 3 meal slots side-by-side for hands-free reading.
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
          ) {
            dayMealCards.forEach { card ->
              MealSlotCard(
                card = card,
                instantSwapCandidates = instantSwapsBySlotId[card.slot.slotId].orEmpty(),
                householdProfile = householdProfile,
                catalog = catalog,
                initiallyExpanded = true,
                onStartCooking = onStartCookingSlot,
                onQuickCookedIt = onQuickCookSlot,
                onOpenAdjustSheet = onOpenAdjustSlot,
                onQuickSwapRecipe = onQuickSwapSlotRecipe,
                onEditFamilyGoals = onOpenFamilyGoalsSheet,
                onOpenRecipeReader = onOpenRecipeReader,
                modifier = Modifier.weight(1f),
              )
            }
          }
        }

        else -> {
          // Keep portrait cards collapsed by default so Breakfast, Lunch, and Dinner scan together.
          Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            dayMealCards.forEach { card ->
              MealSlotCard(
                card = card,
                instantSwapCandidates = instantSwapsBySlotId[card.slot.slotId].orEmpty(),
                householdProfile = householdProfile,
                catalog = catalog,
                initiallyExpanded = false,
                onStartCooking = onStartCookingSlot,
                onQuickCookedIt = onQuickCookSlot,
                onOpenAdjustSheet = onOpenAdjustSlot,
                onQuickSwapRecipe = onQuickSwapSlotRecipe,
                onEditFamilyGoals = onOpenFamilyGoalsSheet,
                onOpenRecipeReader = onOpenRecipeReader,
              )
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(16.dp))
    }
  }
}

/**
 * Editorial daily macro summary card displaying active day + servings, a warm `kcal` pill, and
 * 3 understated `4.dp` micro-bars (`Protein` in Herbal Sage, `Carbs` in Warm Amber, `Fat` in Terracotta).
 */
@Composable
fun DailyMacroTelemetryCard(
  dayLabel: String,
  effectiveServings: Int,
  dayMealCards: List<ResolvedMealCard>,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Sum per-serving macros across all scheduled Breakfast, Lunch, and Dinner slots for this day.
  val dailyKcal = dayMealCards.sumOf { it.recipe.caloriesPerServing }
  val dailyProteinG = dayMealCards.sumOf { it.recipe.proteinGrams }
  val dailyCarbsG = dayMealCards.sumOf { it.recipe.carbsGrams }
  val dailyFatG = dayMealCards.sumOf { it.recipe.fatGrams }

  // Crisp ceramic card with 1.dp warm border and zero elevation drop shadow.
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
    shadowElevation = 0.dp,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      // Weight day/servings heading so the right-hand kcal capsule never wraps onto two lines.
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "$dayLabel • $effectiveServings srv",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
          shape = CircleShape,
          color = extendedColors.parchment,
          border = BorderStroke(1.dp, extendedColors.borderSubtle),
        ) {
          Text(
            text = "$dailyKcal kcal",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = extendedColors.charcoalInk,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
          )
        }
      }

      // 3 understated 4.dp macro micro-bars (zero neon colors, zero circular chart clutter).
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        // Protein micro-bar tinted in muted Herbal Sage.
        MacroMicroBarColumn(
          label = "P ${dailyProteinG}g",
          fillFraction = (dailyProteinG.toFloat() / DAILY_PROTEIN_TARGET_G).coerceIn(0.08f, 1.0f),
          barColor = extendedColors.herbalSage,
          modifier = Modifier.weight(1f),
        )
        // Carbs micro-bar tinted in muted Warm Amber (`expiringSoonIndicator`).
        MacroMicroBarColumn(
          label = "C ${dailyCarbsG}g",
          fillFraction = (dailyCarbsG.toFloat() / DAILY_CARBS_TARGET_G).coerceIn(0.08f, 1.0f),
          barColor = extendedColors.expiringSoonIndicator,
          modifier = Modifier.weight(1f),
        )
        // Healthy Fats micro-bar tinted in muted Terracotta.
        MacroMicroBarColumn(
          label = "F ${dailyFatG}g",
          fillFraction = (dailyFatG.toFloat() / DAILY_FAT_TARGET_G).coerceIn(0.08f, 1.0f),
          barColor = extendedColors.terracotta,
          modifier = Modifier.weight(1f),
        )
      }
    }
  }
}

@Composable
private fun MacroMicroBarColumn(
  label: String,
  fillFraction: Float,
  barColor: Color,
  modifier: Modifier = Modifier,
) {
  // Stack non-wrapping tabular macro label above a 4.dp rounded micro-bar track.
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      softWrap = false,
    )
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(4.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
      Box(
        modifier = Modifier
          .fillMaxWidth(fillFraction)
          .fillMaxHeight()
          .clip(CircleShape)
          .background(barColor),
      )
    }
  }
}

@Composable
private fun PlannerUtilityPillStrip(
  weeklyPrepPlan: WeeklyPrepSessionPlan?,
  onOpenCustomMealSheet: () -> Unit,
  onOpenWeeklyPrep: () -> Unit,
  onOpenFamilyGoalsSheet: () -> Unit,
) {
  // Append active batch prep minutes when stations remain unprepped.
  val prepLabel = when {
    weeklyPrepPlan == null || weeklyPrepPlan.tasks.isEmpty() -> "Batch Prep"
    else -> "Batch Prep (${weeklyPrepPlan.totalPrepMinutes}m)"
  }
  // Balanced weights ensure all 3 48.dp utility pills fit cleanly on one row at 1.35x S22 scale.
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    UtilityActionPill(
      label = "+ Add Meal",
      icon = Icons.Default.Add,
      onClick = onOpenCustomMealSheet,
      modifier = Modifier.weight(1f),
    )
    UtilityActionPill(
      label = prepLabel,
      icon = Icons.Default.Kitchen,
      onClick = onOpenWeeklyPrep,
      modifier = Modifier.weight(1.15f),
    )
    UtilityActionPill(
      label = "Goals",
      icon = Icons.Default.ChildCare,
      onClick = onOpenFamilyGoalsSheet,
      modifier = Modifier.weight(0.85f),
    )
  }
}

@Composable
private fun UtilityActionPill(
  label: String,
  icon: ImageVector,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Enforce >= 48.dp touch target height and warm parchment surface with 1.dp stone border.
  Surface(
    shape = CircleShape,
    color = extendedColors.parchment,
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
    shadowElevation = 0.dp,
    modifier = modifier
      .defaultMinSize(minHeight = 48.dp)
      .clip(CircleShape)
      .clickable(onClick = onClick),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = extendedColors.herbalSage,
        modifier = Modifier.size(16.dp),
      )
      Spacer(modifier = Modifier.width(4.dp))
      Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun SevenDaySelectorStrip(
  weeklyBoard: WeeklyBoard,
  activeDayIndex: Int,
  todayDayIndex: Int,
  onSelectDay: (Int) -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Equal-weight 7-day strip with 48.dp minHeight and softWrap = false preventing "Mon"->"Mo\nn".
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(5.dp),
  ) {
    WEEKDAY_SHORT_LABELS.forEachIndexed { dayIndex, label ->
      val isSelected = dayIndex == activeDayIndex
      val isToday = dayIndex == todayDayIndex
      val daySlots = weeklyBoard.slots.filter { it.dayIndex == dayIndex }
      // Mark weekday dot green once all scheduled slots on that day are cooked or completed.
      val allCompleted = daySlots.isNotEmpty() && daySlots.all { it.status != MealSlotStatus.PLANNED }

      Surface(
        onClick = { onSelectDay(dayIndex) },
        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) extendedColors.herbalSage else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = if (isSelected) null else BorderStroke(1.dp, extendedColors.borderSubtle),
        shadowElevation = 0.dp,
      ) {
        Column(
          modifier = Modifier.padding(vertical = 8.dp, horizontal = 2.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
        ) {
          Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
          )
          Spacer(modifier = Modifier.height(4.dp))
          Box(
            modifier = Modifier
              .size(5.dp)
              .clip(CircleShape)
              .background(
                when {
                  isToday && isSelected -> Color.White
                  isToday -> extendedColors.terracotta
                  allCompleted -> extendedColors.herbalSage
                  else -> Color.Transparent
                },
              ),
          )
        }
      }
    }
  }
}

// Warm chamomile/bronze 8:30 PM Night-Before 5-Min Prep card (EC-01: shifts <= 5m prep out of rush).
@Composable
private fun NightBeforePrepBannerCard(recipe: Recipe) {
  val extendedColors = KitchenTheme.extendedColors
  // Uses warm chamomile nightPrepContainer token (zero banned purple/indigo hexes).
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    color = extendedColors.nightPrepContainer,
    border = BorderStroke(1.dp, extendedColors.nightPrepBorder),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.Default.Bedtime,
          contentDescription = "8:30 PM Night-Before Prep",
          tint = extendedColors.onNightPrep,
          modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "8:30 PM Night-Before Prep • ${recipe.title}",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          color = extendedColors.onNightPrep,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      // Explain how <= 5m evening prep keeps tomorrow's weekday rush within the 25m EC-01 cap.
      Text(
        text = "Prep ${recipe.nightBeforePrepMinutes.coerceAtLeast(3)}m tonight so morning stays at ${recipe.calibratedActiveMinutes}m (<= 25m cap):",
        style = MaterialTheme.typography.bodySmall,
        color = extendedColors.onNightPrep,
      )
      // Render concise bulleted night-before prep steps (soaking oats, whisking batter, etc.).
      recipe.nightBeforePrepSteps.forEach { stepText ->
        Text(
          text = "• $stepText",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Medium,
          color = extendedColors.onNightPrep,
        )
      }
    }
  }
}

// Calm ceramic empty-day card inviting 1-tap custom meal scheduling.
@Composable
private fun EmptyDaySlotsCard(onOpenCustomMealSheet: () -> Unit) {
  val extendedColors = KitchenTheme.extendedColors
  Card(
    onClick = onOpenCustomMealSheet,
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
  ) {
    Column(
      modifier = Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(
        text = "No meals scheduled for this day yet",
        style = MaterialTheme.typography.titleMedium,
      )
      Text(
        text = "Tap to build a 1-pot family meal from locally available pantry ingredients.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** Portrait Warm Daylight preview of `TodayAndWeekScreen` at 7:00 AM. */
@Preview(name = "TodayAndWeek — Daylight Phone", showBackground = true, widthDp = 412, heightDp = 915)
@Composable
fun TodayAndWeekScreenDaylightPreview() {
  // Verify collapsed portrait MealSlotCards so Breakfast, Lunch, and Dinner scan together.
  FamilyKitchenBoardTheme(darkTheme = false) {
    TodayAndWeekScreen(
      weeklyBoard = PreviewFixtures.weeklyBoard,
      dayMealCards = PreviewFixtures.resolvedCardsForDay(dayIndex = 0, currentHour24 = 7),
      activeDayIndex = 0,
      todayDayIndex = 0,
      currentHour24 = 7,
      isWideLayout = false,
      householdProfile = PreviewFixtures.householdProfile,
      catalog = PreviewFixtures.catalog,
      weeklyPrepPlan = PreviewFixtures.weeklyPrepPlan,
      instantSwapsBySlotId = PreviewFixtures.mondayInstantSwapsBySlotId,
    )
  }
}

/** Wide Counter Ambient Dark preview at 8:30 PM showing the 3-column grid and night-prep banner. */
@Preview(name = "TodayAndWeek — 8:30 PM Counter Wide Dark", showBackground = true, widthDp = 1024, heightDp = 768)
@Composable
fun TodayAndWeekScreenCounterWideDarkPreview() {
  // Verify 3-column wide counter mode and 8:30 PM Night-Before Prep card in Counter Dark theme.
  FamilyKitchenBoardTheme(darkTheme = true) {
    TodayAndWeekScreen(
      weeklyBoard = PreviewFixtures.weeklyBoard,
      dayMealCards = PreviewFixtures.resolvedCardsForDay(
        dayIndex = 0,
        currentHour24 = 21,
        profile = PreviewFixtures.eveningLifeEventProfile,
      ),
      activeDayIndex = 0,
      todayDayIndex = 0,
      currentHour24 = 21,
      isWideLayout = true,
      householdProfile = PreviewFixtures.eveningLifeEventProfile,
      catalog = PreviewFixtures.catalog,
      weeklyPrepPlan = PreviewFixtures.weeklyPrepPlan,
      instantSwapsBySlotId = PreviewFixtures.mondayInstantSwapsBySlotId,
      tomorrowBreakfastRecipe = PreviewFixtures.overnightOatsRecipe,
    )
  }
}
