package com.familykitchen.board.ui.board

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.familykitchen.board.ui.theme.EditorialSerifFontFamily
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme

// Planner & Daily Menu screen: real ISO week range header + This Week / Next Week horizon pills,
// real calendar 7-day strip, honest DailyMacroTelemetryCard, 4-pill utility bar, Blank Week
// starter card, and partial-day ghost Add Breakfast / Add Lunch / Add Dinner cards.

// Full weekday labels indexed 0 = Monday through 6 = Sunday for telemetry headers.
private val BOARD_SCREEN_WEEKDAY_FULL_LABELS: List<String> = listOf(
  "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday",
)

/**
 * Stateless primary `Plan` screen Composable rendering the real-date week range + `This Week` ·
 * `Next Week` horizon toggle, stacked weekday+date 7-day strip, honest [DailyMacroTelemetryCard],
 * punctuation-clean utility bar (`Add Meal` · `New Week` · `Menus` · `Batch Prep`), conditional
 * 8:30 PM Night-Before Prep card, `Your Kitchen Week Is Open` starter card on blank weeks, and
 * ghost `Add Breakfast` / `Add Lunch` / `Add Dinner` cards on partial days.
 *
 * @param weeklyBoard Active [WeeklyBoard] supplying `weekStartDateIso` and scheduled slots.
 * @param dayMealCards Chronologically ordered [ResolvedMealCard]s for [activeDayIndex].
 * @param activeDayIndex Currently viewed weekday index (`0 = Mon`..`6 = Sun`).
 * @param todayDayIndex Real or simulated current weekday index highlighted with a Terracotta dot.
 * @param currentHour24 24-hour clock value (`0..23`); activates night-prep card when `>= 20`.
 * @param isWideLayout Forces the 3-column counter layout even on medium-width screens when `true`.
 * @param householdProfile Active [HouseholdProfile] driving child stage labels and 4-way plating.
 * @param catalog Household ingredient catalog passed to [MealSlotCard] plating synthesis.
 * @param weeklyPrepPlan Optional [WeeklyPrepSessionPlan] displaying active station count.
 * @param instantSwapsBySlotId Map from `slotId` to `EC-13` 100% in-stock replacement recipes.
 * @param tomorrowBreakfastRecipe Optional tomorrow breakfast recipe checked for 8:30 PM prep steps.
 * @param onSelectDay Invoked with `0..6` when tapping a weekday pill in the 7-day strip.
 * @param onOpenCustomMealSheet Invoked when tapping the `Add Meal` utility pill.
 * @param onOpenWeeklyPrep Invoked when tapping the `Batch Prep` utility pill.
 * @param onOpenFamilyGoalsSheet Invoked when tapping `Edit Goals` inside plating boxes.
 * @param onOpenAiChefTab Invoked when opening Sous-Chef from contextual actions.
 * @param onStartCookingSlot Invoked with `slotId` to launch Step-by-Step Cook Mode.
 * @param onQuickCookSlot Invoked with `slotId` for 1-tap `O(1)` atomic pantry deduction.
 * @param onOpenAdjustSlot Invoked with `slotId` to open `CookedOverrideSheet`.
 * @param onQuickSwapSlotRecipe Invoked with `(slotId, newRecipeId)` for 1-tap `EC-13` swaps.
 * @param onOpenRecipeReader Invoked with `recipeId` when tapping a meal title to open Clean Reader.
 * @param viewingNextWeek `true` when previewing or planning next week's horizon.
 * @param onSelectWeekHorizon Invoked with `false` for `This Week` or `true` for `Next Week`.
 * @param onOpenStartNewWeek Invoked when tapping `New Week` or `Start New Week`.
 * @param onOpenSavedMenus Invoked when tapping `Menus` or `Open Saved Menus`.
 * @param onScanKitchen Invoked when tapping `Scan Kitchen` on the blank-week starter card.
 * @param onAddMealForSlot Invoked with `(dayIndex, slotType)` when tapping a ghost slot card.
 * @param onAskChefForSlot Invoked with `slotId` when tapping `Ask Chef` on a [MealSlotCard].
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
  viewingNextWeek: Boolean = false,
  onSelectWeekHorizon: (Boolean) -> Unit = {},
  onOpenStartNewWeek: () -> Unit = {},
  onOpenSavedMenus: () -> Unit = {},
  onScanKitchen: () -> Unit = onOpenAiChefTab,
  onAddMealForSlot: (Int, MealSlotType) -> Unit = { _, _ -> onOpenCustomMealSheet() },
  onAskChefForSlot: (String) -> Unit = { _ -> onOpenAiChefTab() },
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
  // Prefer tomorrow's morning meal when after 8:00 PM so overnight prep aligns with next sunrise.
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
    // Render 4 utility pills on 1 row on wide devices and 2x2 balanced rows on 360dp phones.
    val useSingleRowUtilities = maxWidth >= 520.dp

    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(PaddingValues(horizontal = 16.dp, vertical = 12.dp)),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      // 1. Real week date span ("Nov 23 – Nov 29") + This Week / Next Week horizon toggle pills.
      BoardWeekHorizonHeader(
        weekStartDateIso = weeklyBoard.weekStartDateIso,
        viewingNextWeek = viewingNextWeek,
        onSelectWeekHorizon = onSelectWeekHorizon,
      )

      // 2. Mon–Sun 7-day strip stacking weekday ("Mon") above calendar day ("23") with Today dot.
      BoardSevenDaySelectorStrip(
        weeklyBoard = weeklyBoard,
        activeDayIndex = safeDayIndex,
        todayDayIndex = todayDayIndex.coerceIn(0, 6),
        viewingNextWeek = viewingNextWeek,
        onSelectDay = onSelectDay,
      )

      // 3. Honest Daily Macro Telemetry Card excluding skipped / eating-out meals from intake.
      DailyMacroTelemetryCard(
        dayLabel = BOARD_SCREEN_WEEKDAY_FULL_LABELS[safeDayIndex],
        effectiveServings = householdProfile.effectiveTargetServings,
        dayMealCards = dayMealCards,
      )

      // 4. Punctuation-free utility bar (Add Meal · New Week · Menus · Batch Prep).
      BoardUtilityPillStrip(
        weeklyPrepPlan = weeklyPrepPlan,
        singleRow = useSingleRowUtilities,
        onOpenCustomMealSheet = onOpenCustomMealSheet,
        onOpenStartNewWeek = onOpenStartNewWeek,
        onOpenSavedMenus = onOpenSavedMenus,
        onOpenWeeklyPrep = onOpenWeeklyPrep,
      )

      // 5. Conditional 8:30 PM Night-Before 5-Min Prep card (EC-01 <= 25m morning pacing guard).
      if (currentHour24 >= EVENING_PREP_START_HOUR_24 && nightPrepTargetRecipe != null) {
        boardScreenNightBeforePrepBanner(recipe = nightPrepTargetRecipe)
      }

      // 6. Blank week starter card (`0` total slots) vs partial/full day slot cards + ghost slots.
      when {
        weeklyBoard.slots.isEmpty() -> {
          boardScreenOpenWeekStarterCard(
            onScanKitchen = onScanKitchen,
            onOpenSavedMenus = onOpenSavedMenus,
            onOpenStartNewWeek = onOpenStartNewWeek,
          )
          // Also offer compact ghost slot cards so users can drop a single meal onto an open week.
          boardScreenDaySlotsWithGhosts(
            dayIndex = safeDayIndex,
            dayMealCards = emptyList(),
            useThreeColumnGrid = useThreeColumnGrid,
            householdProfile = householdProfile,
            catalog = catalog,
            instantSwapsBySlotId = instantSwapsBySlotId,
            onAddMealForSlot = onAddMealForSlot,
            onStartCookingSlot = onStartCookingSlot,
            onQuickCookSlot = onQuickCookSlot,
            onOpenAdjustSlot = onOpenAdjustSlot,
            onQuickSwapSlotRecipe = onQuickSwapSlotRecipe,
            onOpenFamilyGoalsSheet = onOpenFamilyGoalsSheet,
            onOpenRecipeReader = onOpenRecipeReader,
            onAskChefForSlot = onAskChefForSlot,
          )
        }

        else -> {
          // Render scheduled MealSlotCards plus compact Add Breakfast/Lunch/Dinner ghost cards.
          boardScreenDaySlotsWithGhosts(
            dayIndex = safeDayIndex,
            dayMealCards = dayMealCards,
            useThreeColumnGrid = useThreeColumnGrid,
            householdProfile = householdProfile,
            catalog = catalog,
            instantSwapsBySlotId = instantSwapsBySlotId,
            onAddMealForSlot = onAddMealForSlot,
            onStartCookingSlot = onStartCookingSlot,
            onQuickCookSlot = onQuickCookSlot,
            onOpenAdjustSlot = onOpenAdjustSlot,
            onQuickSwapSlotRecipe = onQuickSwapSlotRecipe,
            onOpenFamilyGoalsSheet = onOpenFamilyGoalsSheet,
            onOpenRecipeReader = onOpenRecipeReader,
            onAskChefForSlot = onAskChefForSlot,
          )
        }
      }

      Spacer(modifier = Modifier.height(16.dp))
    }
  }
}

@Composable
private fun boardScreenDaySlotsWithGhosts(
  dayIndex: Int,
  dayMealCards: List<ResolvedMealCard>,
  useThreeColumnGrid: Boolean,
  householdProfile: HouseholdProfile,
  catalog: List<IngredientCatalogItem>,
  instantSwapsBySlotId: Map<String, List<Recipe>>,
  onAddMealForSlot: (Int, MealSlotType) -> Unit,
  onStartCookingSlot: (String) -> Unit,
  onQuickCookSlot: (String) -> Unit,
  onOpenAdjustSlot: (String) -> Unit,
  onQuickSwapSlotRecipe: (String, String) -> Unit,
  onOpenFamilyGoalsSheet: () -> Unit,
  onOpenRecipeReader: (String) -> Unit,
  onAskChefForSlot: (String) -> Unit,
) {
  // Group day meal cards by MealSlotType so missing cadences (e.g. Dinners Only) get ghost cards.
  val cardsBySlotType = dayMealCards.groupBy { it.slot.slotType }

  when {
    useThreeColumnGrid -> {
      // Wide-counter tablet layout renders Breakfast, Lunch, and Dinner side-by-side.
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
      ) {
        MealSlotType.entries.forEach { slotType ->
          val cards = cardsBySlotType[slotType].orEmpty()
          Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            // Show ghost Add <Meal> card whenever a partial cadence leaves this column empty.
            if (cards.isEmpty()) {
              boardScreenGhostAddSlotCard(
                slotType = slotType,
                onClick = { onAddMealForSlot(dayIndex, slotType) },
              )
            } else {
              // Expand safety and plating by default in wide 3-column counter mode.
              cards.forEach { card ->
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
                  onAskChef = onAskChefForSlot,
                )
              }
            }
          }
        }
      }
    }

    else -> {
      // Portrait stack renders scheduled MealSlotCards interspersed with compact ghost Add cards.
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MealSlotType.entries.forEach { slotType ->
          val cards = cardsBySlotType[slotType].orEmpty()
          // Render ghost Add Breakfast / Add Lunch / Add Dinner card on unscheduled slots.
          if (cards.isEmpty()) {
            boardScreenGhostAddSlotCard(
              slotType = slotType,
              onClick = { onAddMealForSlot(dayIndex, slotType) },
            )
          } else {
            // Keep portrait MealSlotCards collapsed by default so all 3 meals fit cleanly.
            cards.forEach { card ->
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
                onAskChef = onAskChefForSlot,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun boardScreenGhostAddSlotCard(
  slotType: MealSlotType,
  onClick: () -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Natural slot-specific CTA labels with zero slashes or parentheses.
  val actionTitle = when (slotType) {
    MealSlotType.BREAKFAST -> "Add Breakfast"
    MealSlotType.LUNCH -> "Add Lunch"
    MealSlotType.DINNER -> "Add Dinner"
  }
  // Concise culinary guidance subtitle explaining the slot's role in the family rhythm.
  val hintText = when (slotType) {
    MealSlotType.BREAKFAST -> "Open morning slot · Under 25m weekday cap"
    MealSlotType.LUNCH -> "Open midday slot · Schedule a dish or leftovers"
    MealSlotType.DINNER -> "Open evening slot · Pick a family recipe"
  }

  // Soft parchment ghost card with >= 56.dp touch target for 1-tap meal slot filling.
  Surface(
    onClick = onClick,
    shape = RoundedCornerShape(18.dp),
    color = extendedColors.parchment.copy(alpha = 0.72f),
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
        Text(
          text = actionTitle,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          color = extendedColors.herbalSage,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = hintText,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      // Circular sage medallion visually inviting 1-tap slot creation.
      Surface(
        shape = CircleShape,
        color = extendedColors.sageContainer,
        modifier = Modifier.size(36.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = Icons.Default.Add,
            contentDescription = actionTitle,
            tint = extendedColors.onSageContainer,
            modifier = Modifier.size(18.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun boardScreenOpenWeekStarterCard(
  onScanKitchen: () -> Unit,
  onOpenSavedMenus: () -> Unit,
  onOpenStartNewWeek: () -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Warm editorial starter card shown when weeklyBoard.slots.isEmpty() (e.g. Blank Week / Empty Kitchen).
  Surface(
    shape = RoundedCornerShape(22.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
    shadowElevation = 0.dp,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      // Newsreader 22.sp headline welcoming households who prefer Blank Week planning.
      Text(
        text = "Your Kitchen Week Is Open",
        style = MaterialTheme.typography.headlineSmall.copy(
          fontFamily = EditorialSerifFontFamily,
          fontSize = 22.sp,
          lineHeight = 28.sp,
        ),
        color = MaterialTheme.colorScheme.onSurface,
      )
      // Supportive copy offering 3 stress-free entry paths into weekly meal planning.
      Text(
        text = "Start at your own pace: scan what is in your fridge, apply a saved family menu, or draft a fresh cadence for the week.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      // Primary 48.dp Herbal Sage CTA launching StartNewWeekSheet.
      Button(
        onClick = onOpenStartNewWeek,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
          containerColor = extendedColors.herbalSage,
          contentColor = Color.White,
        ),
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
      ) {
        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text("Start New Week", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
      }

      // Secondary 48.dp pair: Scan Kitchen + Open Saved Menus.
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedButton(
          onClick = onScanKitchen,
          shape = CircleShape,
          border = BorderStroke(1.dp, extendedColors.borderSubtle),
          modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
        ) {
          Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Scan Kitchen", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }

        OutlinedButton(
          onClick = onOpenSavedMenus,
          shape = CircleShape,
          border = BorderStroke(1.dp, extendedColors.borderSubtle),
          modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
        ) {
          Icon(Icons.Default.AutoStories, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Open Saved Menus", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
      }
    }
  }
}

@Composable
private fun boardScreenNightBeforePrepBanner(recipe: Recipe) {
  val extendedColors = KitchenTheme.extendedColors
  // Uses warm chamomile nightPrepContainer token (zero banned purple or indigo hexes).
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
          text = "8:30 PM Night-Before Prep · ${recipe.title}",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          color = extendedColors.onNightPrep,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      // Explain EC-01 morning 25m cap protection clearly without parentheses.
      Text(
        text = "Prep ${recipe.nightBeforePrepMinutes.coerceAtLeast(3)}m tonight so morning stays at ${recipe.calibratedActiveMinutes}m under the 25m cap:",
        style = MaterialTheme.typography.bodySmall,
        color = extendedColors.onNightPrep,
      )
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

/** Portrait Warm Daylight preview of `TodayAndWeekScreen` with real calendar dates. */
@Preview(name = "TodayAndWeek Daylight Phone", showBackground = true, widthDp = 412, heightDp = 915)
@Composable
fun TodayAndWeekScreenDaylightPreview() {
  // Verify real-date 7-day strip, horizon toggle, and collapsed portrait MealSlotCards.
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

/** Blank Week Warm Daylight preview verifying `Your Kitchen Week Is Open` and ghost slot cards. */
@Preview(name = "TodayAndWeek Open Week Daylight", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
fun TodayAndWeekScreenOpenWeekPreview() {
  // Verify compact 360dp resilience on an open 0-slot weekly board.
  val openBoard = PreviewFixtures.weeklyBoard.copy(slots = emptyList())
  FamilyKitchenBoardTheme(darkTheme = false) {
    TodayAndWeekScreen(
      weeklyBoard = openBoard,
      dayMealCards = emptyList(),
      activeDayIndex = 2,
      todayDayIndex = 2,
      householdProfile = PreviewFixtures.householdProfile,
      catalog = PreviewFixtures.catalog,
    )
  }
}

/** Wide Counter Ambient Dark preview at 8:30 PM showing the 3-column grid and night-prep banner. */
@Preview(name = "TodayAndWeek Counter Wide Dark", showBackground = true, widthDp = 1024, heightDp = 768)
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
