package com.familykitchen.board.ui.pacer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familykitchen.board.domain.HouseholdProfile
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.MAX_WEEKDAY_BREAKFAST_MINUTES
import com.familykitchen.board.domain.MorningStep
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.ResolvedMealCard
import com.familykitchen.board.domain.WeeklyPrepBatchCommitPayload
import com.familykitchen.board.domain.WeeklyPrepSessionPlan
import com.familykitchen.board.ui.board.ChildSafetyCalloutBox
import com.familykitchen.board.ui.preview.PreviewFixtures
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.InterfaceSansFontFamily
import com.familykitchen.board.ui.theme.KitchenTheme
import com.familykitchen.board.ui.theme.TabularTimerTextStyle
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// Morning Pacer & Weekly Batch Prep screen with 48.dp+ non-clipping controls and 19.sp step prose.

private const val DEFAULT_STEP_FALLBACK_SECONDS: Int = 180

/**
 * Sub-mode selector states inside [MorningPacerScreen].
 */
enum class PacerMode {
  /** Step-by-step countdown pacer for daily morning execution (`<= 25m` per `EC-01`). */
  DAILY_PACER,

  /** Station-grouped weekly batch meal prep session across all `PLANNED` weekly slots. */
  WEEKLY_PREP,
}

/**
 * Stateless `Pacer` screen Composable hosting a non-clipping `48.dp` segmented switch between
 * Daily Morning Pacer (`<= 25m`, `19.sp` step text, `+1 min` timer control) and [WeeklyPrepSection].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MorningPacerScreen(
  activeMealCard: ResolvedMealCard?,
  weeklyPrepPlan: WeeklyPrepSessionPlan,
  householdProfile: HouseholdProfile = HouseholdProfile(),
  catalog: List<IngredientCatalogItem> = emptyList(),
  recipes: List<Recipe> = emptyList(),
  completedPrepTaskIds: Set<String> = emptySet(),
  initialMode: PacerMode = PacerMode.DAILY_PACER,
  onModeChanged: (PacerMode) -> Unit = {},
  onFinishAndDeductPantry: (String) -> Unit = {},
  onTogglePrepStationTask: (taskId: String, isCompleted: Boolean) -> Unit = { _, _ -> },
  onCommitWeeklyPrepSession: (WeeklyPrepBatchCommitPayload) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Persist active sub-mode as a Bundle-safe String name across orientation/process changes.
  val modeNameState = rememberSaveable(initialMode.name) { mutableStateOf(initialMode.name) }
  val activeMode = PacerMode.entries.find { it.name == modeNameState.value } ?: initialMode

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(extendedColors.linen)
      .verticalScroll(rememberScrollState())
      .padding(PaddingValues(horizontal = 16.dp, vertical = 12.dp)),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    // Concise segmented labels prevent "Weekly Prep (4" mid-parenthesis clipping on S22 scaling.
    val modes = listOf(
      PacerMode.DAILY_PACER to "Daily (≤${MAX_WEEKDAY_BREAKFAST_MINUTES}m)",
      PacerMode.WEEKLY_PREP to "Batch Prep (${weeklyPrepPlan.tasks.size})",
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
      modes.forEachIndexed { index, (targetMode, label) ->
        SegmentedButton(
          selected = activeMode == targetMode,
          onClick = {
            modeNameState.value = targetMode.name
            onModeChanged(targetMode)
          },
          shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          label = {
            Text(
              text = label,
              maxLines = 1,
              softWrap = false,
              overflow = TextOverflow.Ellipsis,
              style = MaterialTheme.typography.labelMedium,
            )
          },
        )
      }
    }

    // Route between <= 25m single-meal step pacer and 4-station Weekly Batch Prep.
    when (activeMode) {
      PacerMode.DAILY_PACER -> {
        DailyMorningPacerContent(
          activeMealCard = activeMealCard,
          householdProfile = householdProfile,
          catalog = catalog,
          onFinishAndDeductPantry = onFinishAndDeductPantry,
        )
      }

      PacerMode.WEEKLY_PREP -> {
        WeeklyPrepSection(
          prepPlan = weeklyPrepPlan,
          catalog = catalog,
          recipes = recipes,
          initialCompletedTaskIds = completedPrepTaskIds,
          onToggleStationTask = onTogglePrepStationTask,
          onCommitPrepSession = onCommitWeeklyPrepSession,
        )
      }
    }

    Spacer(modifier = Modifier.height(16.dp))
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DailyMorningPacerContent(
  activeMealCard: ResolvedMealCard?,
  householdProfile: HouseholdProfile,
  catalog: List<IngredientCatalogItem>,
  onFinishAndDeductPantry: (String) -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Show a calm editorial empty prompt when no scheduled meal slot is currently selected.
  if (activeMealCard == null) {
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
      border = BorderStroke(1.dp, extendedColors.borderSubtle),
    ) {
      Text(
        text = "Tap 'Cook' on any planned meal in Plan to pace its steps here.",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(16.dp),
      )
    }
    return
  }

  // Index catalog once per meal selection and synthesize a fallback step if recipe steps are empty.
  val catalogById = remember(catalog) { catalog.associateBy { it.ingredientId } }
  val steps = remember(activeMealCard.recipe) {
    activeMealCard.recipe.morningSteps.ifEmpty {
      listOf(
        MorningStep(
          stepNumber = 1,
          instruction = "Prepare and cook ${activeMealCard.recipe.title} for ${activeMealCard.slot.targetServings} servings.",
          timerSeconds = (activeMealCard.recipe.cookMinutes.coerceAtLeast(3)) * 60,
        ),
      )
    }
  }

  // Scope step index and timer state to `(slotId, safeIndex)` so switching meals resets cleanly.
  val stepIndexState = rememberSaveable(activeMealCard.slot.slotId) { mutableIntStateOf(0) }
  val safeIndex = stepIndexState.intValue.coerceIn(0, steps.lastIndex)
  val currentStep = steps[safeIndex]
  val initialStepSeconds = (currentStep.timerSeconds ?: DEFAULT_STEP_FALLBACK_SECONDS).coerceAtLeast(10)

  val totalStepSecondsState = rememberSaveable(activeMealCard.slot.slotId, safeIndex) {
    mutableIntStateOf(initialStepSeconds)
  }
  val remainingSecondsState = rememberSaveable(activeMealCard.slot.slotId, safeIndex) {
    mutableIntStateOf(initialStepSeconds)
  }
  val isTimerRunningState = rememberSaveable(activeMealCard.slot.slotId, safeIndex) {
    mutableStateOf(false)
  }
  // Track checked mise-en-place ingredients locally during the morning rush.
  val readyIngredientIds = remember(activeMealCard.slot.slotId) { mutableStateListOf<String>() }

  // Coroutine 1-second ticker active strictly while timer is unpaused and > 0 seconds remain.
  LaunchedEffect(isTimerRunningState.value, safeIndex, activeMealCard.slot.slotId) {
    while (isTimerRunningState.value && remainingSecondsState.intValue > 0) {
      delay(1_000L)
      remainingSecondsState.intValue = (remainingSecondsState.intValue - 1).coerceAtLeast(0)
      if (remainingSecondsState.intValue == 0) {
        // Auto-stop ticker at 00:00 so idle steps consume zero background CPU cycles.
        isTimerRunningState.value = false
      }
    }
  }

  // Resolve EC-08 child choking-safety prep guidance for the household's youngest stage.
  val safetyTip = activeMealCard.stageAdaptedSafetyTip
    ?: householdProfile.youngestStage.formatPrepTip(activeMealCard.toddlerPrepTip)

  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    // Segmented step progress bar + active calibrated minute badge (`<= 25m` weekday morning cap).
    StepProgressHeaderCard(
      recipeTitle = activeMealCard.recipe.title,
      calibratedMinutes = activeMealCard.calibratedActiveMinutes,
      currentStepOneBased = safeIndex + 1,
      totalSteps = steps.size,
    )

    // Countertop-legible 19.sp step instruction card + 48.dp mise-en-place chips.
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
      border = BorderStroke(1.dp, extendedColors.borderSubtle),
    ) {
      Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        // 19.sp / 28.sp lineHeight Plus Jakarta Sans stays legible from arm's length at the stove.
        Text(
          text = currentStep.instruction,
          style = MaterialTheme.typography.bodyLarge.copy(
            fontFamily = InterfaceSansFontFamily,
            fontSize = 19.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.Medium,
          ),
          color = MaterialTheme.colorScheme.onSurface,
        )

        // Interactive 48.dp FilterChips let cooks verify scaled mise-en-place before turning on heat.
        if (activeMealCard.effectiveBomQuantities.isNotEmpty()) {
          Text(
            text = "Mise-en-Place Checklist",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            activeMealCard.effectiveBomQuantities.entries.forEach { (ingId, qty) ->
              val item = catalogById[ingId]
              val rounded = (qty * 100.0).roundToInt() / 100.0
              val num = when {
                rounded == rounded.toLong().toDouble() -> rounded.toLong().toString()
                else -> rounded.toString()
              }
              val isReady = ingId in readyIngredientIds
              FilterChip(
                selected = isReady,
                onClick = {
                  if (isReady) readyIngredientIds.remove(ingId) else readyIngredientIds.add(ingId)
                },
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                label = {
                  Text(
                    text = "${item?.displayName ?: ingId} ($num ${item?.baseUnit?.wireCode ?: ""})".trim(),
                    style = MaterialTheme.typography.labelSmall,
                  )
                },
              )
            }
          }
        }
      }
    }

    // Circular tabular countdown timer with 48.dp Start/Pause, +1 min, and Reset buttons.
    CountdownRingCard(
      remainingSeconds = remainingSecondsState.intValue,
      totalSeconds = totalStepSecondsState.intValue,
      isRunning = isTimerRunningState.value,
      onToggleStartPause = {
        // Restart full step duration automatically when user presses Start after hitting 00:00.
        if (remainingSecondsState.intValue == 0) {
          remainingSecondsState.intValue = totalStepSecondsState.intValue
        }
        isTimerRunningState.value = !isTimerRunningState.value
      },
      onAddOneMinute = {
        // Extend both total and remaining seconds so ring progress stays proportional.
        totalStepSecondsState.intValue += 60
        remainingSecondsState.intValue += 60
      },
      onReset = {
        isTimerRunningState.value = false
        totalStepSecondsState.intValue = initialStepSeconds
        remainingSecondsState.intValue = initialStepSeconds
      },
    )

    // Surface EC-08 child choking-safety guidance directly above step navigation controls.
    if (!safetyTip.isNullOrBlank()) {
      ChildSafetyCalloutBox(safetyText = safetyTip)
    }

    // 48.dp Prev / Next Step row using AutoMirrored directional icons for RTL compatibility.
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      OutlinedButton(
        onClick = {
          isTimerRunningState.value = false
          stepIndexState.intValue = (safeIndex - 1).coerceAtLeast(0)
        },
        enabled = safeIndex > 0,
        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
      ) {
        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = "Prev", maxLines = 1, softWrap = false)
      }

      FilledTonalButton(
        onClick = {
          isTimerRunningState.value = false
          stepIndexState.intValue = (safeIndex + 1).coerceAtMost(steps.lastIndex)
        },
        enabled = safeIndex < steps.lastIndex,
        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
          containerColor = extendedColors.sageContainer,
          contentColor = extendedColors.onSageContainer,
        ),
      ) {
        Text(text = "Next Step", maxLines = 1, softWrap = false)
        Spacer(modifier = Modifier.width(6.dp))
        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
      }
    }

    // Primary 52.dp O(1) batch completion CTA marking slot COOKED and deducting Tier-1 BOM lines.
    Button(
      onClick = {
        isTimerRunningState.value = false
        onFinishAndDeductPantry(activeMealCard.slot.slotId)
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
        text = "Finish & Deduct Pantry",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        softWrap = false,
      )
    }
  }
}

// Compact step progress header pairing step pill, calibrated active time, and segmented bar.
@Composable
private fun StepProgressHeaderCard(
  recipeTitle: String,
  calibratedMinutes: Int,
  currentStepOneBased: Int,
  totalSteps: Int,
) {
  val extendedColors = KitchenTheme.extendedColors
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // Non-wrapping current/total step pill.
        Surface(
          shape = RoundedCornerShape(999.dp),
          color = extendedColors.sageContainer,
        ) {
          Text(
            text = "Step $currentStepOneBased of $totalSteps",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = extendedColors.onSageContainer,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
          )
        }
        // Calibrated active morning minutes (incorporating 1.4x prep buffer per EC-01).
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = Icons.Default.Visibility,
            contentDescription = null,
            tint = extendedColors.herbalSage,
            modifier = Modifier.size(15.dp),
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text(
            text = "${calibratedMinutes}m active",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
          )
        }
      }

      Text(
        text = recipeTitle,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )

      // Color completed step segments in sage, current step in terracotta, and remaining in muted track.
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        for (idx in 1..totalSteps) {
          val segmentColor = when {
            idx < currentStepOneBased -> extendedColors.herbalSage
            idx == currentStepOneBased -> extendedColors.terracotta
            else -> MaterialTheme.colorScheme.surfaceContainer
          }
          Box(
            modifier = Modifier
              .weight(1f)
              .height(5.dp)
              .clip(CircleShape)
              .background(segmentColor),
          )
        }
      }
    }
  }
}

// Terracotta-accented step countdown card using lambda CircularProgressIndicator and tabular digits.
@Composable
private fun CountdownRingCard(
  remainingSeconds: Int,
  totalSeconds: Int,
  isRunning: Boolean,
  onToggleStartPause: () -> Unit,
  onAddOneMinute: () -> Unit,
  onReset: () -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Guard denominator at >= 1 second so progressFraction stays finite in [0f, 1f].
  val safeTotal = totalSeconds.coerceAtLeast(1)
  val progressFraction = (remainingSeconds.toFloat() / safeTotal.toFloat()).coerceIn(0f, 1f)

  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(22.dp),
    colors = CardDefaults.cardColors(containerColor = extendedColors.terracottaContainer),
    border = BorderStroke(1.dp, extendedColors.terracotta.copy(alpha = 0.35f)),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(18.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(168.dp),
      ) {
        // Compose BOM 2024.10.01 lambda progress overload avoids full-card recomposition per tick.
        CircularProgressIndicator(
          progress = { progressFraction },
          modifier = Modifier.fillMaxSize(),
          color = extendedColors.terracotta,
          trackColor = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.65f),
          strokeWidth = 8.dp,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          // Tabular tnum numerals prevent horizontal digit shift during live countdowns.
          Text(
            text = formatMinutesSeconds(remainingSeconds),
            style = TabularTimerTextStyle,
            color = extendedColors.onTerracottaContainer,
            maxLines = 1,
            softWrap = false,
          )
          Text(
            text = when {
              remainingSeconds == 0 -> "Step Complete!"
              isRunning -> "Counting down…"
              else -> "Ready to start"
            },
            style = MaterialTheme.typography.labelMedium,
            color = extendedColors.onTerracottaContainer,
            maxLines = 1,
            softWrap = false,
          )
        }
      }

      // 48.dp+ non-wrapping timer controls ("Start"/"Pause", "+1 min", "Reset").
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Button(
          onClick = onToggleStartPause,
          modifier = Modifier.weight(1.25f).defaultMinSize(minHeight = 48.dp),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = extendedColors.terracotta,
            contentColor = MaterialTheme.colorScheme.onSecondary,
          ),
        ) {
          Icon(
            imageVector = if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text(
            text = if (isRunning) "Pause" else "Start",
            maxLines = 1,
            softWrap = false,
          )
        }

        // Concise "+1 min" label without duplicate leading Add icon prevents "+ +1" S22 wrap.
        OutlinedButton(
          onClick = onAddOneMinute,
          modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
        ) {
          Text(
            text = "+1 min",
            maxLines = 1,
            softWrap = false,
          )
        }

        OutlinedButton(
          onClick = onReset,
          modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
        ) {
          Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text(text = "Reset", maxLines = 1, softWrap = false)
        }
      }
    }
  }
}

/** Warm Daylight preview of `MorningPacerScreen` in Daily Morning Pacer (`<= 25m`) mode. */
@Preview(name = "MorningPacer — Daily Pacer Daylight", showBackground = true, widthDp = 412, heightDp = 915)
@Composable
fun MorningPacerDailyPreview() {
  // Verify 19.sp step instruction prose and 48.dp timer buttons in Warm Daylight theme.
  val breakfastCard = PreviewFixtures.resolvedCardsForDay(dayIndex = 0, currentHour24 = 7).first()
  FamilyKitchenBoardTheme(darkTheme = false) {
    MorningPacerScreen(
      activeMealCard = breakfastCard,
      weeklyPrepPlan = PreviewFixtures.weeklyPrepPlan,
      householdProfile = PreviewFixtures.householdProfile,
      catalog = PreviewFixtures.catalog,
      recipes = PreviewFixtures.recipes,
      initialMode = PacerMode.DAILY_PACER,
    )
  }
}

/** Ambient Counter Dark preview of `MorningPacerScreen` in Station-Grouped Weekly Meal Prep mode. */
@Preview(name = "MorningPacer — Weekly Prep Counter Dark", showBackground = true, widthDp = 412, heightDp = 915)
@Composable
fun MorningPacerWeeklyPrepDarkPreview() {
  // Verify Batch Prep station cards and warm storage tips in Ambient Counter Dark mode.
  val breakfastCard = PreviewFixtures.resolvedCardsForDay(dayIndex = 0, currentHour24 = 20).first()
  FamilyKitchenBoardTheme(darkTheme = true) {
    MorningPacerScreen(
      activeMealCard = breakfastCard,
      weeklyPrepPlan = PreviewFixtures.weeklyPrepPlan,
      householdProfile = PreviewFixtures.householdProfile,
      catalog = PreviewFixtures.catalog,
      recipes = PreviewFixtures.recipes,
      completedPrepTaskIds = setOf("prep_station_grains_oats_beans"),
      initialMode = PacerMode.WEEKLY_PREP,
    )
  }
}
