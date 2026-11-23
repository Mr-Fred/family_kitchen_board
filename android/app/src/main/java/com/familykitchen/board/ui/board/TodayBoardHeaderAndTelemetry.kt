package com.familykitchen.board.ui.board

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.Refresh
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
import com.familykitchen.board.domain.MealSlotStatus
import com.familykitchen.board.domain.ResolvedMealCard
import com.familykitchen.board.domain.SlotSkipReason
import com.familykitchen.board.domain.WeeklyBoard
import com.familykitchen.board.domain.WeeklyPrepSessionPlan
import com.familykitchen.board.domain.formatDayChipDateLabel
import com.familykitchen.board.domain.formatWeekRangeLabel
import com.familykitchen.board.ui.preview.PreviewFixtures
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme

// Cohesive Meal Plan top navigation and telemetry components: real-date week horizon header,
// 7-day stacked calendar strip, honest DailyMacroTelemetryCard, and 4-pill utility action bar.

// Daily macro reference denominators scaling the 3 understated 4.dp telemetry micro-bars.
private const val TELEMETRY_PROTEIN_TARGET_G: Float = 120f
private const val TELEMETRY_CARBS_TARGET_G: Float = 220f
private const val TELEMETRY_FAT_TARGET_G: Float = 75f

/**
 * Real-date week span header (`formatWeekRangeLabel`) paired with `This Week` · `Next Week`
 * `48.dp` toggle pills inside a warm parchment capsule.
 */
@Composable
internal fun BoardWeekHorizonHeader(
  weekStartDateIso: String,
  viewingNextWeek: Boolean,
  onSelectWeekHorizon: (Boolean) -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Format ISO Monday date into a human-friendly week span such as "Nov 23 – Nov 29".
  val rangeLabel = formatWeekRangeLabel(weekStartDateIso)

  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(
      modifier = Modifier.weight(1f).padding(end = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = Icons.Default.CalendarMonth,
        contentDescription = null,
        tint = extendedColors.herbalSage,
        modifier = Modifier.size(18.dp),
      )
      Spacer(modifier = Modifier.width(6.dp))
      Text(
        text = rangeLabel,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }

    // Two 48.dp toggle pills ("This Week" · "Next Week") inside a warm parchment capsule.
    Surface(
      shape = CircleShape,
      color = extendedColors.parchment,
      border = BorderStroke(1.dp, extendedColors.borderSubtle),
    ) {
      Row(modifier = Modifier.padding(2.dp)) {
        boardHeaderHorizonTogglePill(
          label = "This Week",
          selected = !viewingNextWeek,
          onClick = { onSelectWeekHorizon(false) },
        )
        boardHeaderHorizonTogglePill(
          label = "Next Week",
          selected = viewingNextWeek,
          onClick = { onSelectWeekHorizon(true) },
        )
      }
    }
  }
}

@Composable
private fun boardHeaderHorizonTogglePill(
  label: String,
  selected: Boolean,
  onClick: () -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Guarantee >= 48.dp vertical tap target on both horizon pills.
  Surface(
    onClick = onClick,
    shape = CircleShape,
    color = if (selected) extendedColors.herbalSage else Color.Transparent,
    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
  ) {
    Box(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
        color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        softWrap = false,
      )
    }
  }
}

/**
 * Editorial daily macro summary card displaying active day + servings, a warm `kcal` pill, and
 * 3 understated `4.dp` micro-bars (`Protein`, `Carbs`, `Fat`).
 *
 * Excludes slots marked [MealSlotStatus.COMPLETED_SKIPPED] unless [SlotSkipReason.ATE_LEFTOVERS]
 * is set, preventing skipped or restaurant meals from inflating daily home macro intake.
 *
 * @param dayLabel Full weekday title (`"Monday"`..`"Sunday"`).
 * @param effectiveServings Active household serving scale.
 * @param dayMealCards Resolved meal cards scheduled on the selected day.
 * @param modifier Optional Compose [Modifier].
 */
@Composable
fun DailyMacroTelemetryCard(
  dayLabel: String,
  effectiveServings: Int,
  dayMealCards: List<ResolvedMealCard>,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Exclude skipped and eating-out slots while counting home-cooked ATE_LEFTOVERS portions.
  val homeIntakeCards = dayMealCards.filterNot { card ->
    card.slot.status == MealSlotStatus.COMPLETED_SKIPPED &&
      card.slot.skipReason != SlotSkipReason.ATE_LEFTOVERS
  }
  val dailyKcal = homeIntakeCards.sumOf { it.recipe.caloriesPerServing }
  val dailyProteinG = homeIntakeCards.sumOf { it.recipe.proteinGrams }
  val dailyCarbsG = homeIntakeCards.sumOf { it.recipe.carbsGrams }
  val dailyFatG = homeIntakeCards.sumOf { it.recipe.fatGrams }

  // Flat 1.dp warm stone-bordered telemetry card with zero neon rings or drop shadows.
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
      // Top row: active weekday + effective servings on the left, warm kcal pill on the right.
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "$dayLabel · $effectiveServings srv",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        // Parchment energy badge showing honest per-person home intake.
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
      // Render 0f fill when all meals on the day were skipped or open so bars never fake intake.
      val minBar = if (homeIntakeCards.isEmpty()) 0f else 0.08f
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        // Protein micro-bar in Herbal Sage.
        boardHeaderMacroMicroBarColumn(
          label = "P ${dailyProteinG}g",
          fillFraction = (dailyProteinG.toFloat() / TELEMETRY_PROTEIN_TARGET_G).coerceIn(minBar, 1.0f),
          barColor = extendedColors.herbalSage,
          modifier = Modifier.weight(1f),
        )
        // Carbohydrates micro-bar in warm Harvest Gold.
        boardHeaderMacroMicroBarColumn(
          label = "C ${dailyCarbsG}g",
          fillFraction = (dailyCarbsG.toFloat() / TELEMETRY_CARBS_TARGET_G).coerceIn(minBar, 1.0f),
          barColor = extendedColors.expiringSoonIndicator,
          modifier = Modifier.weight(1f),
        )
        // Healthy fats micro-bar in Terracotta Clay.
        boardHeaderMacroMicroBarColumn(
          label = "F ${dailyFatG}g",
          fillFraction = (dailyFatG.toFloat() / TELEMETRY_FAT_TARGET_G).coerceIn(minBar, 1.0f),
          barColor = extendedColors.terracotta,
          modifier = Modifier.weight(1f),
        )
      }
    }
  }
}

@Composable
private fun boardHeaderMacroMicroBarColumn(
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
    // Background capsule track sized at 4.dp height.
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(4.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
      // Foreground filled fraction clamped safely within 0.0f..1.0f.
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

/**
 * Adaptive 4-pill utility action bar (`Add Meal` · `New Week` · `Menus` · `Batch Prep`) free of
 * slashes and parentheses.
 */
@Composable
internal fun BoardUtilityPillStrip(
  weeklyPrepPlan: WeeklyPrepSessionPlan?,
  singleRow: Boolean,
  onOpenCustomMealSheet: () -> Unit,
  onOpenStartNewWeek: () -> Unit,
  onOpenSavedMenus: () -> Unit,
  onOpenWeeklyPrep: () -> Unit,
) {
  // Use middle-dot separator with zero slashes or parentheses.
  val prepLabel = when {
    weeklyPrepPlan == null || weeklyPrepPlan.tasks.isEmpty() -> "Batch Prep"
    else -> "Batch Prep · ${weeklyPrepPlan.tasks.size}"
  }

  when {
    singleRow -> {
      // Wide-screen layout keeps all 4 utility pills on a single horizontal strip.
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        boardHeaderUtilityActionPill("Add Meal", Icons.Default.Add, onOpenCustomMealSheet, Modifier.weight(1f))
        boardHeaderUtilityActionPill("New Week", Icons.Default.Refresh, onOpenStartNewWeek, Modifier.weight(1f))
        boardHeaderUtilityActionPill("Menus", Icons.Default.AutoStories, onOpenSavedMenus, Modifier.weight(0.9f))
        boardHeaderUtilityActionPill(prepLabel, Icons.Default.Kitchen, onOpenWeeklyPrep, Modifier.weight(1.15f))
      }
    }

    else -> {
      // Two balanced 2-pill rows keep all 4 controls unclipped on 360dp at 1.35x font scaling.
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Row 1: Add Meal + New Week rollover trigger.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          boardHeaderUtilityActionPill("Add Meal", Icons.Default.Add, onOpenCustomMealSheet, Modifier.weight(1f))
          boardHeaderUtilityActionPill("New Week", Icons.Default.Refresh, onOpenStartNewWeek, Modifier.weight(1f))
        }
        // Row 2: Saved Family Menus + 4-Station Batch Prep shortcut.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          boardHeaderUtilityActionPill("Menus", Icons.Default.AutoStories, onOpenSavedMenus, Modifier.weight(1f))
          boardHeaderUtilityActionPill(prepLabel, Icons.Default.Kitchen, onOpenWeeklyPrep, Modifier.weight(1f))
        }
      }
    }
  }
}

@Composable
private fun boardHeaderUtilityActionPill(
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
      Spacer(modifier = Modifier.width(5.dp))
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

/**
 * 7-day Monday..Sunday strip stacking short weekday (`Mon`) above calendar date (`23`) derived
 * from [formatDayChipDateLabel], highlighting `todayDayIndex` with a Terracotta indicator.
 */
@Composable
internal fun BoardSevenDaySelectorStrip(
  weeklyBoard: WeeklyBoard,
  activeDayIndex: Int,
  todayDayIndex: Int,
  viewingNextWeek: Boolean,
  onSelectDay: (Int) -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Equal-weight 7-day strip stacking weekday ("Mon") above calendar day ("23") from ISO math.
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(5.dp),
  ) {
    for (dayIndex in 0..6) {
      val chipLabel = formatDayChipDateLabel(weeklyBoard.weekStartDateIso, dayIndex)
      val parts = chipLabel.split(' ')
      val weekdayShort = parts.firstOrNull().orEmpty().ifEmpty { "Day" }
      val dateNumber = parts.getOrNull(1)
      val isSelected = dayIndex == activeDayIndex
      // Highlight Today only on This Week so Next Week never misleads with a duplicate Today dot.
      val isToday = !viewingNextWeek && dayIndex == todayDayIndex
      val daySlots = weeklyBoard.slots.filter { it.dayIndex == dayIndex }
      // Mark day completed when at least 1 meal exists and 0 PLANNED slots remain.
      val allCompleted = daySlots.isNotEmpty() && daySlots.all { it.status != MealSlotStatus.PLANNED }

      // Emphasize Today with a 1.5.dp Terracotta border when another weekday is currently focused.
      val borderStroke = when {
        isSelected -> null
        isToday -> BorderStroke(1.5.dp, extendedColors.terracotta)
        else -> BorderStroke(1.dp, extendedColors.borderSubtle)
      }

      // 56.dp tall day pill ensuring comfortable single-thumb selection on 360dp screens.
      Surface(
        onClick = { onSelectDay(dayIndex) },
        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 56.dp),
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) extendedColors.herbalSage else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = borderStroke,
        shadowElevation = 0.dp,
      ) {
        Column(
          modifier = Modifier.padding(vertical = 6.dp, horizontal = 2.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
        ) {
          // Short weekday label ("Mon".."Sun").
          Text(
            text = weekdayShort,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
          )
          // Calendar date number ("23") derived deterministically from weekStartDateIso.
          if (!dateNumber.isNullOrBlank()) {
            Text(
              text = dateNumber,
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.Bold,
              color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
              softWrap = false,
            )
          }
          Spacer(modifier = Modifier.height(3.dp))
          // 5.dp status dot: Terracotta for Today, Herbal Sage for completed days.
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

/** Warm Daylight preview of `DailyMacroTelemetryCard` and the real-date 7-day strip. */
@Preview(name = "BoardHeaderAndTelemetry Daylight", showBackground = true, widthDp = 412)
@Composable
fun TodayBoardHeaderAndTelemetryPreview() {
  // Verify skipped Eating Out slot exclusion from daily macro intake totals.
  val cards = PreviewFixtures.resolvedCardsForDay(dayIndex = 0, currentHour24 = 12).mapIndexed { idx, c ->
    if (idx == 1) {
      c.copy(slot = c.slot.copy(status = MealSlotStatus.COMPLETED_SKIPPED, skipReason = SlotSkipReason.EATING_OUT))
    } else {
      c
    }
  }
  FamilyKitchenBoardTheme(darkTheme = false) {
    Surface(color = KitchenTheme.extendedColors.linen) {
      Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        BoardWeekHorizonHeader(
          weekStartDateIso = "2026-11-23",
          viewingNextWeek = false,
          onSelectWeekHorizon = {},
        )
        BoardSevenDaySelectorStrip(
          weeklyBoard = PreviewFixtures.weeklyBoard,
          activeDayIndex = 0,
          todayDayIndex = 0,
          viewingNextWeek = false,
          onSelectDay = {},
        )
        DailyMacroTelemetryCard(
          dayLabel = "Monday",
          effectiveServings = 4,
          dayMealCards = cards,
        )
      }
    }
  }
}
