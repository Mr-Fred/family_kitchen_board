package com.familykitchen.board.ui.sheets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.EVENING_PREP_START_HOUR_24
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme

// Interactive QA & Scenario Lab modal bottom sheet + stateless content Composable with concise
// "7:00 AM" / "8:30 PM" segmented buttons and 48.dp+ non-wrapping action targets.

/** Standard simulated morning hour (`7` = 7:00 AM) used by the QA clock toggle. */
const val QA_MORNING_SIMULATED_HOUR_24: Int = 7

/** Standard simulated evening prep hour (`20` = 8:30 PM window) activating night-prep banners. */
const val QA_EVENING_SIMULATED_HOUR_24: Int = EVENING_PREP_START_HOUR_24

/**
 * Canonical identifiers and invariant descriptions for all 13 locked `EC-01`–`EC-13` edge-case
 * verification scenarios executable in 1 tap from [QaScenarioLabSheet].
 */
enum class QaEdgeCaseScenario(
  val code: String,
  val title: String,
  val invariantSummary: String,
) {
  /** `1.4x` slow-cooker morning buffer rejecting `> 25m` weekday breakfasts unless night-prepped. */
  EC_01("EC-01", "<= 25m Morning Cap & 1.4x Prep Buffer", "Verifies 15m prep + 10m cook = 31m (>25m blocked) vs 5m night-prep shift -> 24m (accepted)."),

  /** Hard-hides recipes requiring locally unavailable or allergen-blocked items without substitutes. */
  EC_02("EC-02", "Local Unavailability & Allergen Hard-Hide", "Marks Avocado unavailable locally (no substitute) and hides dependent recipes immediately."),

  /** Deterministic `substituteGroupId` rewrite preferring in-stock unit-compatible pantry items. */
  EC_03("EC-03", "SubstituteGroup Auto-Swap (In-Stock Tie-Break)", "Marks Blueberries unavailable in FRUIT_TOPPING and rewrites BOM to in-stock Bananas."),

  /** One-time cook quantity override leaving `householdBomOverrides` untouched. */
  EC_04("EC-04", "One-Time Cook Quantity Override", "Deducts 2 eggs instead of 4 for one meal without mutating permanent recipe defaults."),

  /** Permanent household override normalized across `baseServings / targetServings`. */
  EC_05("EC-05", "Save Permanent Household Default Override", "Saves overridden egg quantity normalized to baseServings in 1 atomic batch write."),

  /** Unplanned ad-hoc extra ingredient deducted in the same single `O(1)` cooked-meal pass. */
  EC_06("EC-06", "Ad-Hoc Extra Ingredient (+1 Tomato)", "Adds +1 unplanned tomato to a cooked meal and deducts everything in batchWriteCallCount +1."),

  /** Flat `1x` Dad workout boost unscaled by household serving multipliers. */
  EC_07("EC-07", "Flat 1x Dad Workout Boost", "Activates POST_WORKOUT_HIGH_PROTEIN (+2 eggs flat, unscaled even when guests visit)."),

  /** Developmental stage-adapted child choking-safety callout integrity. */
  EC_08("EC-08", "Child Growth-Stage Safety Callout", "Cycles BABY_6_12M / TODDLER_1_2Y safety prep callouts on active meal cards."),

  /** Chronological 21-slot Tier-1 reservation contention and whole-package `ceil` rounding. */
  EC_09("EC-09", "Chronological 21-Slot Contention & Ceil Rounding", "Reserves 8 eggs against 6 in stock -> flags Wed Breakfast -2 shortage -> rounds to 12 (1 carton)."),

  /** Floor-at-zero Tier-1 depletion clamping (`max(0, stock - used)`). */
  EC_10("EC-10", "Floor-at-Zero Pantry Depletion", "Cooks 2 eggs with only 1 egg in stock -> clamps stock cleanly to 0.0 and flags isOutOfStock."),

  /** Tier-2 `PLENTY | LOW | OUT` lifecycle with zero gram-by-gram cook subtraction. */
  EC_11("EC-11", "Tier-2 Staple Tri-State Lifecycle", "Sets Olive Oil to LOW -> queues 1 bottle on Grocery List -> batch restock resets to PLENTY."),

  /** `COMPLETED_SKIPPED` deduction of `0` and immediate downstream reservation release. */
  EC_12("EC-12", "Ate Leftovers / Skip Reservation Release", "Marks planned slot COMPLETED_SKIPPED, deducts 0 stock, and frees reserved BOM immediately."),

  /** 100% Tier-1 and non-`OUT` Tier-2 instant in-stock swap candidate filter. */
  EC_13("EC-13", "Instant 100% In-Stock Meal Swap", "Filters replacement meals strictly to locally available recipes with 100% pantry coverage."),
}

/**
 * Material 3 [ModalBottomSheet] wrapper hosting [QaScenarioLabSheetContent].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QaScenarioLabSheet(
  batchWriteCallCount: Int,
  schedulableRecipeCount: Int,
  totalRecipeCount: Int,
  currentSimulatedHour24: Int,
  lastScenarioStatusMessage: String? = null,
  onSelectClockHour: (targetHour24: Int) -> Unit,
  onResetHouseholdState: () -> Unit,
  onTriggerScenario: (QaEdgeCaseScenario) -> Unit,
  onDismiss: () -> Unit,
  onSimulateFreshInstall: () -> Unit = {},
) {
  // Expand sheet fully so clock simulator, reset button, and EC-01..EC-13 runners scan cleanly.
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = KitchenTheme.extendedColors.linen,
  ) {
    // Delegate all interactive verification controls to stateless QaScenarioLabSheetContent.
    QaScenarioLabSheetContent(
      batchWriteCallCount = batchWriteCallCount,
      schedulableRecipeCount = schedulableRecipeCount,
      totalRecipeCount = totalRecipeCount,
      currentSimulatedHour24 = currentSimulatedHour24,
      lastScenarioStatusMessage = lastScenarioStatusMessage,
      onSelectClockHour = onSelectClockHour,
      onResetHouseholdState = onResetHouseholdState,
      onTriggerScenario = onTriggerScenario,
      onDismiss = onDismiss,
      onSimulateFreshInstall = onSimulateFreshInstall,
    )
  }
}

/**
 * Stateless inner content of [QaScenarioLabSheet], extracted for hermetic Compose `@Preview`s.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QaScenarioLabSheetContent(
  batchWriteCallCount: Int,
  schedulableRecipeCount: Int,
  totalRecipeCount: Int,
  currentSimulatedHour24: Int,
  lastScenarioStatusMessage: String? = null,
  onSelectClockHour: (targetHour24: Int) -> Unit,
  onResetHouseholdState: () -> Unit,
  onTriggerScenario: (QaEdgeCaseScenario) -> Unit,
  onDismiss: () -> Unit = {},
  onSimulateFreshInstall: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // True when simulated 24h clock is at or past 20:00 (activating 8:30 PM Night-Before 5m Prep).
  val isEveningMode = currentSimulatedHour24 >= EVENING_PREP_START_HOUR_24

  // Scrollable QA verification sheet accessible via long-press on the top-bar SavorAI brand mark.
  Column(
    modifier = modifier
      .fillMaxWidth()
      .imePadding()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // Weighted sheet header + 48.dp Close IconButton.
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(
        modifier = Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(Icons.Filled.Science, contentDescription = null, tint = extendedColors.herbalSage)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "QA & Scenario Lab",
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
        Icon(Icons.Filled.Close, contentDescription = "Close QA Scenario Lab")
      }
    }

    // Live telemetry badges: O(1) batchWriteCallCount + Schedulable Recipe Count.
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      QaMetricCard("Atomic Writes", batchWriteCallCount.toString(), "O(1) commits", Modifier.weight(1f))
      QaMetricCard("Schedulable", "$schedulableRecipeCount / $totalRecipeCount", "EC-02 / EC-03", Modifier.weight(1f))
    }

    // Concise "7:00 AM" / "8:30 PM" clock simulator + 48.dp Reset Household State button.
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
      border = BorderStroke(1.dp, extendedColors.borderSubtle),
    ) {
      Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Filled.Schedule, contentDescription = null, tint = extendedColors.herbalSage, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "Clock Simulator (Night-Prep Trigger)",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
          )
        }

        // Concise non-wrapping time labels prevent "7:00 AM..." / "8:30 PM..." 360dp clipping.
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
          SegmentedButton(
            selected = !isEveningMode,
            onClick = { onSelectClockHour(QA_MORNING_SIMULATED_HOUR_24) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            label = { Text("7:00 AM", maxLines = 1, softWrap = false) },
          )
          SegmentedButton(
            selected = isEveningMode,
            onClick = { onSelectClockHour(QA_EVENING_SIMULATED_HOUR_24) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            label = { Text("8:30 PM", maxLines = 1, softWrap = false) },
          )
        }

        // DefaultMinSize(minHeight = 48.dp) replaces fixed .height(44.dp) so text never clips.
        OutlinedButton(
          onClick = onResetHouseholdState,
          modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp),
        ) {
          Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "Reset Household State",
            maxLines = 1,
            softWrap = false,
          )
        }

        // 1-tap QA launcher opening the 4-step First-Run Setup Wizard directly.
        OutlinedButton(
          onClick = onSimulateFreshInstall,
          modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp),
        ) {
          Icon(Icons.Filled.Restaurant, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "Launch First-Run Setup Wizard",
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }

    // Live assertion banner reporting pass/fail telemetry from the most recently executed EC scenario.
    if (!lastScenarioStatusMessage.isNullOrBlank()) {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = extendedColors.cookedSuccessContainer),
        border = BorderStroke(1.dp, extendedColors.herbalSage),
      ) {
        Text(
          text = lastScenarioStatusMessage,
          style = MaterialTheme.typography.bodySmall,
          fontWeight = FontWeight.Medium,
          color = extendedColors.onCookedSuccess,
          modifier = Modifier.padding(12.dp),
        )
      }
    }

    HorizontalDivider(color = extendedColors.borderSubtle)
    Text(
      text = "1-Tap Edge-Case Scenarios (EC-01 – EC-13)",
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.Bold,
    )

    // Render all 13 locked edge-case scenarios with weighted descriptions and 48.dp Run buttons.
    QaEdgeCaseScenario.entries.forEach { scenario ->
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
          // Weight left scenario summary so right-hand 48.dp Run button stays single-line.
          Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              // Non-wrapping EC-XX code badge.
              Surface(shape = RoundedCornerShape(6.dp), color = extendedColors.sageContainer) {
                Text(
                  text = scenario.code,
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = FontWeight.Bold,
                  color = extendedColors.onSageContainer,
                  maxLines = 1,
                  softWrap = false,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
              }
              Text(
                text = scenario.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )
            }
            Text(
              text = scenario.invariantSummary,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Spacer(modifier = Modifier.width(8.dp))
          // 48.dp minHeight Run button executing scenario deterministically in 1 O(1) batch commit.
          FilledTonalButton(
            onClick = { onTriggerScenario(scenario) },
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          ) {
            Text("Run", maxLines = 1, softWrap = false)
          }
        }
      }
    }
  }
}

// Compact sage telemetry card surfacing live atomic write count and schedulable recipe coverage.
@Composable
private fun QaMetricCard(label: String, value: String, subtitle: String, modifier: Modifier = Modifier) {
  val extendedColors = KitchenTheme.extendedColors
  // Non-wrapping headline figure ensures batchWriteCallCount stays legible at 1.35x font scale.
  Card(
    modifier = modifier,
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = extendedColors.sageContainer),
  ) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(label, style = MaterialTheme.typography.labelMedium, color = extendedColors.onSageContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = extendedColors.onSageContainer, maxLines = 1, softWrap = false)
      Text(subtitle, style = MaterialTheme.typography.labelSmall, color = extendedColors.onSageContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 880)
@Composable
private fun QaScenarioLabSheetPreview() {
  // Preview evening 8:30 PM QA Lab state with EC-09 pass telemetry banner.
  FamilyKitchenBoardTheme {
    // Render stateless content directly inside Warm Linen surface for hermetic IDE preview.
    Surface(color = KitchenTheme.extendedColors.linen) {
      QaScenarioLabSheetContent(
        batchWriteCallCount = 3,
        schedulableRecipeCount = 30,
        totalRecipeCount = 30,
        currentSimulatedHour24 = QA_EVENING_SIMULATED_HOUR_24,
        lastScenarioStatusMessage = "PASS EC-09: Wed Breakfast flagged -2 eggs; rounded to 1 carton (12 count) in 1 batch write.",
        onSelectClockHour = {},
        onResetHouseholdState = {},
        onTriggerScenario = {},
      )
    }
  }
}
