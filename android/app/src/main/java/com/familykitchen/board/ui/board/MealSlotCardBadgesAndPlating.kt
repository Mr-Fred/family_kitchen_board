package com.familykitchen.board.ui.board

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.MealSlotStatus
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.SlotSkipReason
import com.familykitchen.board.domain.WorkoutTiming
import com.familykitchen.board.ui.theme.KitchenTheme

// Reusable status pills, Pinned & Leftover Lunch badges, EC-08 ChildSafetyCalloutBox, 1-pot 4-way
// household plating card, and EC-13 in-stock swap strip supporting MealSlotCard and CookMode.

// Matches redundant trailing child-stage suffixes when the prefix already states "For 2yo:".
private val BADGE_TRAILING_AGE_DUP_REGEX = Regex(
  "\\s+for\\s+(?:2yo|2-year-old|toddler|baby)\\.?$",
  RegexOption.IGNORE_CASE,
)

/**
 * Compact Herbal Sage `Pinned` routine badge rendered when `slot.isPinned == true`.
 */
@Composable
internal fun SlotCardPinnedBadge() {
  val extendedColors = KitchenTheme.extendedColors
  // Highlight recurring weekly anchor slots preserved across startOrRefreshWeeklyBoard.
  Surface(
    shape = CircleShape,
    color = extendedColors.sageContainer,
    border = BorderStroke(1.dp, extendedColors.herbalSage.copy(alpha = 0.35f)),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Icon(
        imageVector = Icons.Default.PushPin,
        contentDescription = null,
        tint = extendedColors.onSageContainer,
        modifier = Modifier.size(12.dp),
      )
      Text(
        text = "Pinned",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = extendedColors.onSageContainer,
        maxLines = 1,
        softWrap = false,
      )
    }
  }
}

/**
 * Warm `Leftover Lunch · 0 Cost` badge rendered when `slot.leftoverSourceRecipeId != null`.
 */
@Composable
internal fun SlotCardLeftoverLunchBadge() {
  val extendedColors = KitchenTheme.extendedColors
  // Signal 0-BOM batch-cooked leftover lunches so shoppers see zero extra grocery cost.
  Surface(
    shape = CircleShape,
    color = extendedColors.tertiaryFixed,
  ) {
    Text(
      text = "Leftover Lunch · 0 Cost",
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.Bold,
      color = extendedColors.onTertiaryFixed,
      maxLines = 1,
      softWrap = false,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
    )
  }
}

/**
 * Active minutes pill (or negative saved-minutes badge when Weekly Batch Prep completed ahead).
 */
@Composable
internal fun SlotCardCalibratedTimePill(
  calibratedMinutes: Int,
  isPreppedAhead: Boolean,
  preppedSavedMinutes: Int,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Highlight active-time savings ("-10m") whenever Weekly Prep already completed this meal's station.
  val timeText = when {
    isPreppedAhead && preppedSavedMinutes > 0 -> "-${preppedSavedMinutes}m"
    else -> "${calibratedMinutes}m"
  }
  Surface(
    shape = CircleShape,
    color = extendedColors.sageContainer,
  ) {
    Text(
      text = timeText,
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.Bold,
      color = extendedColors.onSageContainer,
      maxLines = 1,
      softWrap = false,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
    )
  }
}

/**
 * Distinct status pill rendering `Cooked`, `Ate Leftovers`, `Eating Out`, or `Skipped`.
 */
@Composable
internal fun SlotCardStatusPill(
  status: MealSlotStatus,
  skipReason: SlotSkipReason?,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Distinguish Cooked, Ate Leftovers, Eating Out, and Skipped using warm semantic containers.
  val (bgColor, fgColor, label) = when (status) {
    MealSlotStatus.COOKED -> Triple(
      extendedColors.cookedSuccessContainer,
      extendedColors.onCookedSuccess,
      "Cooked",
    )
    // Map each SlotSkipReason to a distinct warm container so Ate Leftovers vs Eating Out stands out.
    MealSlotStatus.COMPLETED_SKIPPED -> when (skipReason) {
      // Home-cooked leftovers count toward daily macros while releasing uncooked BOM reservations.
      SlotSkipReason.ATE_LEFTOVERS -> Triple(
        extendedColors.tertiaryFixed,
        extendedColors.onTertiaryFixed,
        SlotSkipReason.ATE_LEFTOVERS.badgeLabel,
      )
      // Restaurant or takeout meals release reservations and stay excluded from home macro intake.
      SlotSkipReason.EATING_OUT -> Triple(
        extendedColors.secondaryFixed,
        extendedColors.onSecondaryFixed,
        SlotSkipReason.EATING_OUT.badgeLabel,
      )
      // Generic skipped slots release reservations and stay excluded from home macro intake.
      SlotSkipReason.SKIPPED, null -> Triple(
        extendedColors.terracottaContainer,
        extendedColors.onTerracottaContainer,
        SlotSkipReason.SKIPPED.badgeLabel,
      )
    }
    // Planned slots omit status pills so upcoming cards stay visually calm.
    MealSlotStatus.PLANNED -> return
  }
  // Compact rounded status pill with single-line non-wrapping typography.
  Surface(shape = CircleShape, color = bgColor) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.Bold,
      color = fgColor,
      maxLines = 1,
      softWrap = false,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
    )
  }
}

/**
 * Flat `1x` workout modifier banner (`EC-07`) rendered inside [MealSlotCard] when active.
 */
@Composable
internal fun SlotCardWorkoutBoostBanner(
  modifierProfile: WorkoutTiming,
  instructionAddOn: String,
) {
  val extendedColors = KitchenTheme.extendedColors
  // EC-07: Workout boost adds a flat 1x portion without multiplying all family servings.
  val profileBadge = when (modifierProfile) {
    WorkoutTiming.PRE_WORKOUT_CARB -> "Pre-Workout Carb Boost · 1x"
    WorkoutTiming.POST_WORKOUT_HIGH_PROTEIN -> "Post-Workout High-Protein · 1x"
    WorkoutTiming.NONE -> ""
  }
  // Terracotta container draws attention to the extra 1x cook-time add-on portion.
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = extendedColors.terracottaContainer,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = Icons.Default.FitnessCenter,
        contentDescription = null,
        tint = extendedColors.terracotta,
        modifier = Modifier.size(18.dp),
      )
      Spacer(modifier = Modifier.width(8.dp))
      Column {
        Text(
          text = profileBadge,
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.Bold,
          color = extendedColors.onTerracottaContainer,
        )
        Text(
          text = instructionAddOn,
          style = MaterialTheme.typography.bodySmall,
          color = extendedColors.onTerracottaContainer,
        )
      }
    }
  }
}

/**
 * Reusable Honey-Amber child choking-safety callout box (`EC-08`) shared across Planner, Cook Mode,
 * and Recipe Reader with automatic trailing age-phrase deduplication.
 *
 * @param safetyText Plain-text stage-adapted child preparation instruction.
 * @param modifier Optional Compose [Modifier].
 */
@Composable
fun ChildSafetyCalloutBox(
  safetyText: String,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Clean redundant trailing "for 2yo." when the prefix already states "For 2yo:".
  val cleanedSafetyText = deduplicateChildSafetyText(safetyText)
  // Honey-Amber childSafetyContainer ensures high salience in both Daylight and Counter Dark.
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp),
    color = extendedColors.childSafetyContainer,
    border = BorderStroke(1.dp, extendedColors.childSafetyBorder),
  ) {
    Row(
      modifier = Modifier.padding(12.dp),
      verticalAlignment = Alignment.Top,
    ) {
      Icon(
        imageVector = Icons.Default.ChildCare,
        contentDescription = "Child safety prep tip",
        tint = extendedColors.onChildSafety,
        modifier = Modifier.size(20.dp),
      )
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = cleanedSafetyText,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = extendedColors.onChildSafety,
      )
    }
  }
}

/**
 * Strips redundant trailing `"for 2yo."` or `"for 2-year-old."` when [rawText] already begins with
 * a stage prefix (`"For 2yo: ..."`).
 *
 * @param rawText Candidate safety guidance text.
 * @return Deduplicated plain-text safety guidance string.
 */
fun deduplicateChildSafetyText(rawText: String): String {
  val trimmed = rawText.trim()
  // Leave unprefixed strings untouched so standalone tips retain their age context.
  if (!trimmed.contains(":")) return trimmed
  val stripped = trimmed.replace(BADGE_TRAILING_AGE_DUP_REGEX, "")
  // Re-append terminal period if the stripped age suffix consumed the sentence period.
  return when {
    stripped != trimmed && !stripped.endsWith(".") -> "$stripped."
    else -> stripped
  }
}

/**
 * Warm parchment `1-Pot 4-Member Plating` box with a `48.dp` `Edit Goals` button.
 */
@Composable
internal fun SlotCardFamilyGoalPlatingBox(
  platingGuide: Map<String, String>,
  onEditFamilyGoals: () -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Warm parchment sub-card listing how 1 shared pot plates across household members.
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp),
    color = extendedColors.parchment,
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
  ) {
    Column(
      modifier = Modifier.padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(
          modifier = Modifier.weight(1f),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            imageVector = Icons.Default.RestaurantMenu,
            contentDescription = null,
            tint = extendedColors.herbalSage,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "1-Pot 4-Member Plating",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        // 48.dp Edit Goals link opening FamilyProfileSheet.
        TextButton(
          onClick = onEditFamilyGoals,
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        ) {
          Text(
            text = "Edit Goals",
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            softWrap = false,
          )
        }
      }

      // Render one bullet per configured household member.
      platingGuide.values.forEach { memberInstruction ->
        Text(
          text = "• $memberInstruction",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
    }
  }
}

/**
 * Horizontal `EC-13` 100%-pantry-ready instant swap strip inside [MealSlotCard]'s accordion.
 */
@Composable
internal fun SlotCardInstantQuickSwapRow(
  slotId: String,
  candidates: List<Recipe>,
  onQuickSwapRecipe: (slotId: String, newRecipeId: String) -> Unit,
) {
  // Display zero-shopping replacement options that already satisfy 100% of BOM from Pantry.
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(
      text = "In-Stock Swaps · 100% Pantry Ready",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    when {
      candidates.isEmpty() -> {
        // Honest fallback when no alternate 100% stocked dish fits this slot.
        Text(
          text = "No alternate 100% in-stock recipes right now.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      else -> {
        // Deduplicate by recipeId so LazyRow keys remain strictly unique across swaps.
        val uniqueCandidates = candidates.distinctBy { it.recipeId }
        // 48.dp AssistChips allowing 1-tap O(1) slot replacement.
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          items(items = uniqueCandidates, key = { it.recipeId }) { candidate ->
            AssistChip(
              onClick = { onQuickSwapRecipe(slotId, candidate.recipeId) },
              modifier = Modifier.defaultMinSize(minHeight = 48.dp),
              label = {
                Text(
                  text = "${candidate.title} · ${candidate.calibratedActiveMinutes}m",
                  style = MaterialTheme.typography.labelMedium,
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
              colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
              ),
            )
          }
        }
      }
    }
  }
}
