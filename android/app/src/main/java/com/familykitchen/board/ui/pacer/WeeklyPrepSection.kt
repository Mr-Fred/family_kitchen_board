package com.familykitchen.board.ui.pacer

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.IngredientTier
import com.familykitchen.board.domain.PrepStationTask
import com.familykitchen.board.domain.PrepStationType
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.WeeklyPrepBatchCommitPayload
import com.familykitchen.board.domain.WeeklyPrepSessionPlan
import com.familykitchen.board.ui.preview.PreviewFixtures
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme
import com.familykitchen.board.ui.theme.TabularTimerTextStyle
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// Station-Grouped Weekly Meal Prep UI section consolidating PLANNED slots into 4 kitchen stations
// with warm parchment storage tips, Tier-1 BOM chips, capped meal badges (<= 3), and 48.dp+ buttons.

private const val DEFAULT_WEEKLY_PREP_TIMER_SECONDS: Int = 25 * 60

/**
 * Stateless Station-Grouped Weekly Meal Prep section rendered inside `MorningPacerScreen`.
 */
@Composable
fun WeeklyPrepSection(
  prepPlan: WeeklyPrepSessionPlan,
  catalog: List<IngredientCatalogItem> = emptyList(),
  recipes: List<Recipe> = emptyList(),
  initialCompletedTaskIds: Set<String> = emptySet(),
  onToggleStationTask: (taskId: String, isCompleted: Boolean) -> Unit = { _, _ -> },
  onCommitPrepSession: (WeeklyPrepBatchCommitPayload) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Index catalog and recipes once per snapshot for O(1) station chip and meal-title lookups.
  val catalogById = remember(catalog) { catalog.associateBy { it.ingredientId } }
  val recipeById = remember(recipes) { recipes.associateBy { it.recipeId } }

  // Persist selected station filter key and Tier-1 deduction preference across configuration changes.
  val selectedStationNameState = rememberSaveable { mutableStateOf("ALL") }
  val deductPantryState = rememberSaveable { mutableStateOf(true) }

  // Hydrate checked station task IDs from domain plan + persisted snapshot set.
  val checkedTaskIds = remember(prepPlan.sessionId, initialCompletedTaskIds) {
    mutableStateListOf<String>().apply {
      prepPlan.tasks.filter { it.isCompleted || it.taskId in initialCompletedTaskIds }
        .forEach { add(it.taskId) }
    }
  }

  // Scope 25:00 batch prep timer state to `prepPlan.sessionId`.
  val remainingSecondsState = rememberSaveable(prepPlan.sessionId) {
    mutableIntStateOf(DEFAULT_WEEKLY_PREP_TIMER_SECONDS)
  }
  val isRunningState = rememberSaveable(prepPlan.sessionId) { mutableStateOf(false) }

  // Coroutine 25:00 batch prep timer ticking every 1 second only while actively running.
  LaunchedEffect(isRunningState.value) {
    while (isRunningState.value && remainingSecondsState.intValue > 0) {
      delay(1_000L)
      remainingSecondsState.intValue = (remainingSecondsState.intValue - 1).coerceAtLeast(0)
      if (remainingSecondsState.intValue == 0) {
        // Stop countdown loop automatically when 25:00 session reaches zero.
        isRunningState.value = false
      }
    }
  }

  // Filter station cards by selected PrepStationType key ("ALL" shows all 4 stations).
  val visibleTasks = when (selectedStationNameState.value) {
    "ALL" -> prepPlan.tasks
    else -> prepPlan.tasks.filter { it.station.name == selectedStationNameState.value }
  }

  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // Hero banner showing total weekly active minutes saved + tabular 25:00 countdown + lambda progress bar.
    WeeklyPrepHeroCard(
      totalPrepMinutes = prepPlan.totalPrepMinutes,
      totalTimeSavedMinutes = prepPlan.totalTimeSavedMinutes,
      completedCount = checkedTaskIds.size,
      totalStationCount = prepPlan.tasks.size,
      remainingSeconds = remainingSecondsState.intValue,
      isTimerRunning = isRunningState.value,
      onToggleTimer = {
        if (remainingSecondsState.intValue == 0) {
          remainingSecondsState.intValue = DEFAULT_WEEKLY_PREP_TIMER_SECONDS
        }
        isRunningState.value = !isRunningState.value
      },
      onResetTimer = {
        isRunningState.value = false
        remainingSecondsState.intValue = DEFAULT_WEEKLY_PREP_TIMER_SECONDS
      },
    )

    // 48.dp horizontal station filter chips (`All`, `Wash & Chop`, `Grains & Beans`, `Sauces`, `Kid Portions`).
    StationFilterChipRow(
      selectedKey = selectedStationNameState.value,
      onSelectKey = { selectedStationNameState.value = it },
    )

    when {
      visibleTasks.isEmpty() -> {
        // Calm ceramic notice when every meal in the selected station is already prepped or cooked.
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = MaterialTheme.colorScheme.surfaceContainerLowest,
          border = BorderStroke(1.dp, extendedColors.borderSubtle),
        ) {
          Text(
            text = "All planned meals for this station are already cooked or prepped!",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
          )
        }
      }

      else -> {
        // Render one scannable station card per active prep group.
        visibleTasks.forEach { task ->
          val isChecked = task.taskId in checkedTaskIds
          PrepStationTaskCard(
            task = task,
            isCompleted = isChecked,
            catalogById = catalogById,
            recipeById = recipeById,
            onToggleCompleted = {
              val nextChecked = !isChecked
              if (nextChecked) {
                checkedTaskIds.add(task.taskId)
              } else {
                checkedTaskIds.remove(task.taskId)
              }
              onToggleStationTask(task.taskId, nextChecked)
            },
          )
        }
      }
    }

    // Optional Tier-1 pantry deduction toggle (never subtracts Tier-2 staples per EC-11).
    Surface(
      shape = RoundedCornerShape(14.dp),
      color = extendedColors.parchment,
      border = BorderStroke(1.dp, extendedColors.borderSubtle),
      modifier = Modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = 52.dp)
        .clickable { deductPantryState.value = !deductPantryState.value },
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Checkbox(
          checked = deductPantryState.value,
          onCheckedChange = { deductPantryState.value = it },
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Deduct prepped Tier-1 items from Pantry",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = "Subtracts measured Tier-1 produce/grains/proteins in 1 batch; Tier-2 staples stay untouched.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    // Primary commit CTA using defaultMinSize(minHeight = 52.dp) so text never clips vertically.
    Button(
      onClick = {
        // Default to all station tasks when user taps Finish Prep directly without individual checks.
        val targetTasks = when {
          checkedTaskIds.isNotEmpty() -> checkedTaskIds.toList()
          else -> prepPlan.tasks.map { it.taskId }
        }
        // Dispatch single O(1) WeeklyPrepBatchCommitPayload updating prepped flags + Tier-1 stock.
        onCommitPrepSession(
          WeeklyPrepBatchCommitPayload(
            sessionId = prepPlan.sessionId,
            boardId = prepPlan.boardId,
            completedTaskIds = targetTasks,
            deductPreppedIngredientsFromPantry = deductPantryState.value,
          ),
        )
      },
      enabled = prepPlan.tasks.isNotEmpty(),
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
        text = "Finish Prep & Update Plan",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        softWrap = false,
      )
    }
  }
}

// Botanical-sage batch prep hero card displaying weekly active minutes saved and 25:00 timer.
@Composable
private fun WeeklyPrepHeroCard(
  totalPrepMinutes: Int,
  totalTimeSavedMinutes: Int,
  completedCount: Int,
  totalStationCount: Int,
  remainingSeconds: Int,
  isTimerRunning: Boolean,
  onToggleTimer: () -> Unit,
  onResetTimer: () -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Normalize completed/total stations into [0f, 1f] for lambda LinearProgressIndicator.
  val completionRatio = when {
    totalStationCount <= 0 -> 1f
    else -> (completedCount.toFloat() / totalStationCount.toFloat()).coerceIn(0f, 1f)
  }

  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(containerColor = extendedColors.sageContainer),
    border = BorderStroke(1.dp, extendedColors.herbalSage.copy(alpha = 0.35f)),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      // Weight left savings summary so right-hand tabular MM:SS clock never wraps.
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
          Text(
            text = "Saves ${totalTimeSavedMinutes}m this week",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = extendedColors.onSageContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = "$completedCount/$totalStationCount stations · ~${totalPrepMinutes}m active",
            style = MaterialTheme.typography.bodySmall,
            color = extendedColors.onSageContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Text(
          text = formatMinutesSeconds(remainingSeconds),
          style = TabularTimerTextStyle.copy(fontSize = MaterialTheme.typography.headlineMedium.fontSize),
          color = extendedColors.onSageContainer,
          maxLines = 1,
          softWrap = false,
        )
      }

      // Compose BOM 2024.10.01 lambda progress overload.
      LinearProgressIndicator(
        progress = { completionRatio },
        modifier = Modifier
          .fillMaxWidth()
          .height(6.dp),
        color = extendedColors.herbalSage,
        trackColor = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.6f),
      )

      // Non-wrapping 48.dp timer buttons prevent "Re\n↻ se\nt" vertical crush on S22 scaling.
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Button(
          onClick = onToggleTimer,
          contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = extendedColors.herbalSage,
            contentColor = MaterialTheme.colorScheme.onPrimary,
          ),
          modifier = Modifier.weight(1.4f).defaultMinSize(minHeight = 48.dp),
        ) {
          Icon(
            imageVector = if (isTimerRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text(
            text = if (isTimerRunning) "Pause" else "Start 25m",
            maxLines = 1,
            softWrap = false,
          )
        }
        OutlinedButton(
          onClick = onResetTimer,
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
          modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
        ) {
          Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text(
            text = "Reset",
            maxLines = 1,
            softWrap = false,
          )
        }
      }
    }
  }
}

// Horizontal 48.dp FilterChip strip filtering across All and individual PrepStationTypes.
@Composable
private fun StationFilterChipRow(
  selectedKey: String,
  onSelectKey: (String) -> Unit,
) {
  // Memoize station chip pairs so LazyRow avoids reallocating on timer ticks.
  val filterItems = remember {
    listOf("ALL" to "All") + PrepStationType.entries.map { it.name to it.displayLabel }
  }

  LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    items(items = filterItems, key = { it.first }) { (stationKey, label) ->
      FilterChip(
        selected = selectedKey == stationKey,
        onClick = { onSelectKey(stationKey) },
        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        label = {
          Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            softWrap = false,
          )
        },
      )
    }
  }
}

// Individual station prep card with capped Tier-1 BOM chips, warm storage tip, and <= 3 meal pills.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrepStationTaskCard(
  task: PrepStationTask,
  isCompleted: Boolean,
  catalogById: Map<String, IngredientCatalogItem>,
  recipeById: Map<String, Recipe>,
  onToggleCompleted: () -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Filter out Tier-2 oils/salts/spices so Kid Portions & station cards show real Tier-1 food items.
  val tier1BomChips = remember(task.aggregatedIngredients, catalogById) {
    task.aggregatedIngredients.entries
      .filter { (ingId, qty) ->
        qty > 0.0 && catalogById[ingId]?.tier != IngredientTier.TIER_2_STAPLE
      }
      .map { (ingId, qty) ->
        val item = catalogById[ingId]
        val rounded = (qty * 100.0).roundToInt() / 100.0
        val num = when {
          rounded == rounded.toLong().toDouble() -> rounded.toLong().toString()
          else -> rounded.toString()
        }
        "${item?.displayName ?: ingId} ($num ${item?.baseUnit?.wireCode ?: "u"})"
      }
  }

  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    border = BorderStroke(
      width = 1.dp,
      color = if (isCompleted) extendedColors.herbalSage else extendedColors.borderSubtle,
    ),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      // Weighted left station header + non-wrapping savings pill prevents "-84m\nsaved" clipping.
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          modifier = Modifier.weight(1f),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            imageVector = Icons.Default.Kitchen,
            contentDescription = null,
            tint = extendedColors.herbalSage,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = task.station.displayLabel,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = extendedColors.herbalSage,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
          shape = CircleShape,
          color = extendedColors.terracottaContainer,
        ) {
          Text(
            text = "${task.estimatedPrepMinutes}m • -${task.timeSavedMinutes}m",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = extendedColors.onTerracottaContainer,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
          )
        }
      }

      Text(
        text = task.title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
      )

      // Render up to 4 compact Tier-1 BOM chips + "+N more" instead of a 7-line prose wall.
      if (tier1BomChips.isNotEmpty()) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          tier1BomChips.take(4).forEach { chipLabel ->
            Surface(
              shape = CircleShape,
              color = extendedColors.parchment,
              border = BorderStroke(1.dp, extendedColors.borderSubtle),
            ) {
              Text(
                text = chipLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
              )
            }
          }
          if (tier1BomChips.size > 4) {
            Surface(
              shape = CircleShape,
              color = extendedColors.parchment,
              border = BorderStroke(1.dp, extendedColors.borderSubtle),
            ) {
              Text(
                text = "+${tier1BomChips.size - 4} more",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
              )
            }
          }
        }
      }

      // Warm parchment Fridge Storage Tip (replaces banned purple nightPrepContainer).
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = extendedColors.parchment,
        border = BorderStroke(1.dp, extendedColors.borderSubtle),
      ) {
        Text(
          text = "Fridge Storage Tip: ${task.storageTip}",
          style = MaterialTheme.typography.bodySmall,
          color = extendedColors.charcoalInk,
          modifier = Modifier.padding(10.dp),
        )
      }

      // Cap covered meal pills at <= 3 total badges (2 named meals + "+N more meals").
      if (task.recipeIds.isNotEmpty()) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          task.recipeIds.take(2).forEach { recipeId ->
            val mealTitle = recipeById[recipeId]?.title ?: recipeId
            Surface(
              shape = CircleShape,
              color = extendedColors.sageContainer,
            ) {
              Text(
                text = mealTitle,
                style = MaterialTheme.typography.labelSmall,
                color = extendedColors.onSageContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
              )
            }
          }
          if (task.recipeIds.size > 2) {
            Surface(
              shape = CircleShape,
              color = extendedColors.parchment,
              border = BorderStroke(1.dp, extendedColors.borderSubtle),
            ) {
              Text(
                text = "+${task.recipeIds.size - 2} more meals",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
              )
            }
          }
        }
      }

      // Toggle button switches between primary sage CTA and outlined completed state (`48.dp+`).
      when {
        isCompleted -> {
          OutlinedButton(
            onClick = onToggleCompleted,
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
          ) {
            Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = "Station Prepped ✓", maxLines = 1, softWrap = false)
          }
        }

        else -> {
          Button(
            onClick = onToggleCompleted,
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
            colors = ButtonDefaults.buttonColors(
              containerColor = extendedColors.herbalSage,
              contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
          ) {
            Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = "Prep This Station", maxLines = 1, softWrap = false)
          }
        }
      }
    }
  }
}

// Formats non-negative seconds as zero-padded tabular MM:SS using pure Kotlin stdlib.
internal fun formatMinutesSeconds(totalSeconds: Int): String {
  // Clamp negative edge values to 0 so display never renders "-01:00".
  val clamped = totalSeconds.coerceAtLeast(0)
  val minutes = (clamped / 60).toString().padStart(2, '0')
  val seconds = (clamped % 60).toString().padStart(2, '0')
  return "$minutes:$seconds"
}

/** Standalone Warm Daylight preview of `WeeklyPrepSection`. */
@Preview(name = "WeeklyPrepSection — Daylight", showBackground = true, widthDp = 412, heightDp = 900)
@Composable
fun WeeklyPrepSectionPreview() {
  // Render all 4 weekly prep stations against canonical Family-of-4 preview fixtures.
  FamilyKitchenBoardTheme(darkTheme = false) {
    Surface(color = KitchenTheme.extendedColors.linen, modifier = Modifier.padding(12.dp)) {
      WeeklyPrepSection(
        prepPlan = PreviewFixtures.weeklyPrepPlan,
        catalog = PreviewFixtures.catalog,
        recipes = PreviewFixtures.recipes,
      )
    }
  }
}
