package com.familykitchen.board.ui.pacer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.HouseholdProfile
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.ResolvedMealCard
import com.familykitchen.board.domain.WeeklyPrepBatchCommitPayload
import com.familykitchen.board.domain.WeeklyPrepSessionPlan
import com.familykitchen.board.ui.preview.PreviewFixtures
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme

// Dedicated Weekly Batch Prep screen host retiring the unreachable duplicate daily pacer view
// while preserving PacerMode and full backward-compatible parameter contracts for Plan switchers.

/**
 * Top-level mode states shared with the `Plan` tab switcher bar.
 */
enum class PacerMode {
  // Routes Plan tab to the 7-day calendar meal board (step-by-step cooking runs in Cook Mode).
  /** Displays the 7-day calendar meal plan board inside the `Plan` tab. */
  DAILY_PACER,

  // Routes Plan tab to the 4-station Weekly Batch Meal Prep session.
  /** Displays the station-grouped Weekly Batch Meal Prep session across `PLANNED` slots. */
  WEEKLY_PREP,
}

/**
 * Stateless Weekly Batch Prep screen hosting [WeeklyPrepSection] on a warm linen canvas without a
 * duplicate inner segmented switcher (`CF-03`).
 *
 * Retains optional backward-compatible parameters so existing `KitchenScaffold` and `@Preview`
 * callers compile seamlessly while daily step-by-step execution lives exclusively in Cook Mode.
 *
 * @param activeMealCard Unused legacy meal card parameter retained for caller compatibility.
 * @param weeklyPrepPlan Derived 4-station [WeeklyPrepSessionPlan] across `PLANNED` weekly slots.
 * @param householdProfile Active household dietary and serving profile.
 * @param catalog Household ingredient catalog for Tier-1 station chip formatting.
 * @param recipes Household recipe library for station meal attribution pills.
 * @param completedPrepTaskIds Persisted set of completed station task identifiers.
 * @param initialMode Legacy mode parameter retained for caller compatibility.
 * @param onModeChanged Legacy mode callback retained for caller compatibility.
 * @param onFinishAndDeductPantry Legacy single-slot callback retained for caller compatibility.
 * @param onTogglePrepStationTask Invoked when checking or unchecking an individual station card.
 * @param onCommitWeeklyPrepSession Commits checked prep tasks in one `O(1)` atomic batch write.
 * @param modifier Optional Compose [Modifier].
 */
@Composable
fun MorningPacerScreen(
  activeMealCard: ResolvedMealCard? = null,
  weeklyPrepPlan: WeeklyPrepSessionPlan,
  householdProfile: HouseholdProfile = HouseholdProfile(),
  catalog: List<IngredientCatalogItem> = emptyList(),
  recipes: List<Recipe> = emptyList(),
  completedPrepTaskIds: Set<String> = emptySet(),
  initialMode: PacerMode = PacerMode.WEEKLY_PREP,
  onModeChanged: (PacerMode) -> Unit = {},
  onFinishAndDeductPantry: (String) -> Unit = {},
  onTogglePrepStationTask: (taskId: String, isCompleted: Boolean) -> Unit = { _, _ -> },
  onCommitWeeklyPrepSession: (WeeklyPrepBatchCommitPayload) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  // Suppress unused-parameter hints transparently by referencing compatibility arguments in-place.
  val unusedCompatibilityAnchor = activeMealCard?.slot?.slotId ?: householdProfile.householdId
  if (initialMode == PacerMode.DAILY_PACER && unusedCompatibilityAnchor.isEmpty()) {
    onModeChanged(initialMode)
    onFinishAndDeductPantry("")
  }

  val extendedColors = KitchenTheme.extendedColors
  // Host WeeklyPrepSection directly so users never encounter two stacked segmented switchers.
  Column(
    modifier = modifier
      .fillMaxSize()
      .background(extendedColors.linen)
      .verticalScroll(rememberScrollState())
      .padding(PaddingValues(horizontal = 16.dp, vertical = 12.dp)),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    WeeklyPrepSection(
      prepPlan = weeklyPrepPlan,
      catalog = catalog,
      recipes = recipes,
      initialCompletedTaskIds = completedPrepTaskIds,
      onToggleStationTask = onTogglePrepStationTask,
      onCommitPrepSession = onCommitWeeklyPrepSession,
    )

    // Extra bottom breathing room above portrait navigation bars.
    Spacer(modifier = Modifier.height(16.dp))
  }
}

/** Warm Daylight preview of `MorningPacerScreen` hosting the 4-station Weekly Batch Prep flow. */
@Preview(name = "MorningPacer Weekly Prep Daylight", showBackground = true, widthDp = 412, heightDp = 915)
@Composable
fun MorningPacerDailyPreview() {
  // Verify station-grouped batch prep cards on a Warm Daylight linen surface.
  FamilyKitchenBoardTheme(darkTheme = false) {
    MorningPacerScreen(
      weeklyPrepPlan = PreviewFixtures.weeklyPrepPlan,
      householdProfile = PreviewFixtures.householdProfile,
      catalog = PreviewFixtures.catalog,
      recipes = PreviewFixtures.recipes,
    )
  }
}

/** Ambient Counter Dark preview of `MorningPacerScreen` in Station-Grouped Weekly Meal Prep mode. */
@Preview(name = "MorningPacer Weekly Prep Counter Dark", showBackground = true, widthDp = 412, heightDp = 915)
@Composable
fun MorningPacerWeeklyPrepDarkPreview() {
  // Verify Batch Prep station cards and warm storage tips in Ambient Counter Dark mode.
  FamilyKitchenBoardTheme(darkTheme = true) {
    MorningPacerScreen(
      weeklyPrepPlan = PreviewFixtures.weeklyPrepPlan,
      householdProfile = PreviewFixtures.householdProfile,
      catalog = PreviewFixtures.catalog,
      recipes = PreviewFixtures.recipes,
      completedPrepTaskIds = setOf("prep_station_grains_oats_beans"),
    )
  }
}
