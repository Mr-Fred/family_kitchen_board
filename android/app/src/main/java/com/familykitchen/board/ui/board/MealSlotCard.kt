package com.familykitchen.board.ui.board

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familykitchen.board.domain.HouseholdProfile
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.MealSlotStatus
import com.familykitchen.board.domain.MealSlotType
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.ResolvedMealCard
import com.familykitchen.board.domain.WorkoutTiming
import com.familykitchen.board.domain.buildFamilyGoalPlatingGuide
import com.familykitchen.board.ui.preview.PreviewFixtures
import com.familykitchen.board.ui.theme.EditorialSerifFontFamily
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme

// Progressive-disclosure MealSlotCard with offline 72.dp RecipePlateThumbnail, <= 3 metadata
// badges, Newsreader 2-line title, 3-dot macro summary, and non-wrapping 48.dp action bar.

private val TRAILING_AGE_DUP_REGEX = Regex(
  "\\s+for\\s+(?:2yo|2-year-old|toddler|baby)\\.?$",
  RegexOption.IGNORE_CASE,
)

/**
 * Stateless progressive-disclosure card representing one scheduled daily meal slot
 * (`BREAKFAST • Fred`, `LUNCH • Wife`, or `DINNER • Wife`).
 *
 * Features a `72.dp` procedural [RecipePlateThumbnail], at most 3 metadata badges, a `Newsreader`
 * 2-line dish title, a compact 3-dot macro summary, an expandable details drawer (`EC-08` child
 * safety, 4-member plating, and `EC-13` quick swaps), and a single non-wrapping `48.dp` action bar.
 */
@Composable
fun MealSlotCard(
  card: ResolvedMealCard,
  instantSwapCandidates: List<Recipe> = emptyList(),
  householdProfile: HouseholdProfile = HouseholdProfile(),
  catalog: List<IngredientCatalogItem> = emptyList(),
  initiallyExpanded: Boolean = false,
  onStartCooking: (String) -> Unit = {},
  onQuickCookedIt: (String) -> Unit = {},
  onOpenAdjustSheet: (String) -> Unit = {},
  onQuickSwapRecipe: (slotId: String, newRecipeId: String) -> Unit = { _, _ -> },
  onEditFamilyGoals: () -> Unit = {},
  onOpenRecipeReader: (String) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Persist per-slot accordion state across scroll and configuration changes.
  val expandedState = rememberSaveable(card.slot.slotId) { mutableStateOf(initiallyExpanded) }
  val recipe = card.recipe

  // Synthesize 4-member 1-pot plating deterministically if the incoming card map was left empty.
  val platingGuide = card.familyGoalPlating.ifEmpty {
    buildFamilyGoalPlatingGuide(
      recipe = recipe,
      catalog = catalog,
      memberGoals = householdProfile.members,
      activeLifeEvents = householdProfile.activeLifeEvents,
      youngestStage = householdProfile.youngestStage,
    )
  }

  // Format and deduplicate EC-08 child safety text so "For 2yo: ... for 2yo." never repeats.
  val rawSafetyTip = card.stageAdaptedSafetyTip
    ?: householdProfile.youngestStage.formatPrepTip(card.toddlerPrepTip)
  val safetyTipText = rawSafetyTip?.let(::deduplicateChildSafetyText)

  // Flat 1.dp warm-bordered surface card with 0.dp elevation drop shadow.
  Card(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      // Header row: 72.dp offline plate artwork + eyebrow/badges + 2-line Newsreader title + macros.
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .clickable { onOpenRecipeReader(recipe.recipeId) },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
      ) {
        // Offline procedural plate illustration anchored to the left of the editorial title.
        RecipePlateThumbnail(
          slotType = card.slot.slotType,
          cuisineTag = recipe.cuisineTag,
        )

        Column(
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          // Strict <= 3 metadata badges: Eyebrow + Time pill + optional non-PLANNED status pill.
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            // Map CookOwner enum to configured household lead name ("Fred" / "Wife").
            val ownerName = householdProfile.displayLabelForCookOwner(card.slot.owner)
            Text(
              text = "${card.slot.slotType.name} • $ownerName",
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.Bold,
              color = extendedColors.slateMuted,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Row(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              // Active calibrated minutes (or negative saved-minutes badge when batch prepped).
              CalibratedTimePill(
                calibratedMinutes = card.calibratedActiveMinutes,
                isPreppedAhead = card.isPreppedAhead,
                preppedSavedMinutes = card.preppedTimeSavedMinutes,
              )
              // Only emit a 3rd pill when status is COOKED or COMPLETED_SKIPPED.
              if (card.slot.status != MealSlotStatus.PLANNED) {
                NonPlannedStatusPill(status = card.slot.status)
              }
            }
          }

          // Editorial Newsreader dish title capped at 2 clean lines.
          Text(
            text = recipe.title,
            style = MaterialTheme.typography.headlineSmall.copy(
              fontFamily = EditorialSerifFontFamily,
              fontSize = 19.sp,
              lineHeight = 24.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )

          // Compact 3-dot macro summary directly beneath the title.
          Text(
            text = "${recipe.caloriesPerServing} kcal • ${recipe.proteinGrams}g P • ${recipe.carbsGrams}g C / ${recipe.fatGrams}g F",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      // EC-07: Surface flat 1x Dad workout boost instruction whenever active on this slot.
      if (card.slot.activeWorkoutModifier != WorkoutTiming.NONE && !card.workoutInstructionAddOn.isNullOrBlank()) {
        WorkoutBoostBanner(
          modifierProfile = card.slot.activeWorkoutModifier,
          instructionAddOn = card.workoutInstructionAddOn,
        )
      }

      // Progressive-disclosure accordion row with 48.dp touch height.
      Surface(
        onClick = { expandedState.value = !expandedState.value },
        shape = RoundedCornerShape(12.dp),
        color = extendedColors.parchment,
        border = BorderStroke(1.dp, extendedColors.borderSubtle),
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = "${householdProfile.youngestStage.shortLabel} Safety · 4-Way Plating · ${instantSwapCandidates.size} Swaps",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
          Icon(
            imageVector = if (expandedState.value) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expandedState.value) "Collapse meal details" else "Expand meal details",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      // Animated progressive-disclosure drawer hides dense plating & swaps until requested.
      AnimatedVisibility(visible = expandedState.value) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          // EC-08: Stage-adapted infant/toddler choking-safety callout in warm honey-amber.
          if (!safetyTipText.isNullOrBlank()) {
            ChildSafetyCalloutBox(safetyText = safetyTipText)
          }
          // 1-pot 4-member plating instructions tailored to household goals and active life events.
          FamilyGoalPlatingBox(
            platingGuide = platingGuide,
            onEditFamilyGoals = onEditFamilyGoals,
          )
          // EC-13: 100% pantry-covered instant swap chips requiring zero extra shopping.
          InstantQuickSwapRow(
            slotId = card.slot.slotId,
            candidates = instantSwapCandidates,
            onQuickSwapRecipe = onQuickSwapRecipe,
          )
        }
      }

      HorizontalDivider(color = extendedColors.borderSubtle)

      // Single non-wrapping 48.dp action bar (`Cook`, `Cooked ✓`, `MoreHoriz`).
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        when (card.slot.status) {
          MealSlotStatus.PLANNED -> {
            // Primary 48.dp Herbal Sage CTA launching Step-by-Step Cook Mode.
            Button(
              onClick = { onStartCooking(card.slot.slotId) },
              shape = CircleShape,
              contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
              colors = ButtonDefaults.buttonColors(
                containerColor = extendedColors.herbalSage,
                contentColor = Color.White,
              ),
              modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
            ) {
              Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = "Cook",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
              )
            }

            // Secondary 48.dp 1-tap O(1) atomic pantry deduction pill ("Cooked ✓").
            FilledTonalButton(
              onClick = { onQuickCookedIt(card.slot.slotId) },
              shape = CircleShape,
              contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
              colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = extendedColors.sageContainer,
                contentColor = extendedColors.onSageContainer,
              ),
              modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
              Text(
                text = "Cooked ✓",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
              )
            }
          }

          MealSlotStatus.COOKED, MealSlotStatus.COMPLETED_SKIPPED -> {
            // Completed slots offer non-wrapping 48.dp Review / Adjust access to CookedOverrideSheet.
            OutlinedButton(
              onClick = { onOpenAdjustSheet(card.slot.slotId) },
              shape = CircleShape,
              border = BorderStroke(1.dp, extendedColors.borderSubtle),
              modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
            ) {
              Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = "Review / Adjust",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                softWrap = false,
              )
            }
          }
        }

        // Compact 48.dp overflow icon button opening CookedOverrideSheet without text wrapping.
        OutlinedIconButton(
          onClick = { onOpenAdjustSheet(card.slot.slotId) },
          border = BorderStroke(1.dp, extendedColors.borderSubtle),
          modifier = Modifier.size(48.dp),
        ) {
          Icon(
            imageVector = Icons.Default.MoreHoriz,
            contentDescription = "Adjust meal or swap",
            modifier = Modifier.size(20.dp),
          )
        }
      }
    }
  }
}

/**
 * Offline `72.dp × 72.dp` procedural ceramic plate & garnish thumbnail keyed deterministically
 * by [slotType] and [cuisineTag] (`Canvas` guarded with `if (size.minDimension <= 0f) return@Canvas`).
 */
@Composable
fun RecipePlateThumbnail(
  slotType: MealSlotType,
  cuisineTag: String,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Hash cuisineTag deterministically so every dish has a consistent garnish accent offline.
  val hash = (cuisineTag.hashCode() and 0x7FFFFFFF) % 3
  val backdrop = extendedColors.parchment
  val rimColor = extendedColors.borderSubtle
  // Color-code primary medallion by meal time (Apricot morning, Sage lunch, Terracotta dinner).
  val primaryFood = when (slotType) {
    MealSlotType.BREAKFAST -> extendedColors.warmApricot
    MealSlotType.LUNCH -> extendedColors.herbalSage
    MealSlotType.DINNER -> extendedColors.terracotta
  }
  val herbAccent = when (hash) {
    0 -> extendedColors.herbalSage
    1 -> extendedColors.terracottaClay
    else -> extendedColors.expiringSoonIndicator
  }
  val plateSurface = MaterialTheme.colorScheme.surfaceContainerLowest

  Box(
    modifier = modifier
      .size(72.dp)
      .clip(RoundedCornerShape(16.dp))
      .background(backdrop)
      .border(1.dp, rimColor, RoundedCornerShape(16.dp)),
    contentAlignment = Alignment.Center,
  ) {
    Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
      // Mandatory zero-dimension Canvas guard preventing divide-by-zero or negative-radius crashes.
      if (size.minDimension <= 0f) return@Canvas
      val radius = size.minDimension * 0.46f
      // Ceramic plate base and subtle rim ring.
      drawCircle(color = plateSurface, radius = radius, center = center)
      drawCircle(color = rimColor, radius = radius, center = center, style = Stroke(width = 1.5.dp.toPx()))
      // Plated protein/grain medallion + botanical garnish dots.
      drawCircle(
        color = primaryFood.copy(alpha = 0.85f),
        radius = radius * 0.52f,
        center = Offset(center.x - radius * 0.10f, center.y + radius * 0.06f),
      )
      drawCircle(
        color = herbAccent,
        radius = radius * 0.24f,
        center = Offset(center.x + radius * 0.26f, center.y - radius * 0.22f),
      )
      drawCircle(
        color = extendedColors.herbalSage.copy(alpha = 0.75f),
        radius = radius * 0.14f,
        center = Offset(center.x + radius * 0.16f, center.y + radius * 0.28f),
      )
    }
  }
}

@Composable
private fun CalibratedTimePill(
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

@Composable
private fun NonPlannedStatusPill(status: MealSlotStatus) {
  val extendedColors = KitchenTheme.extendedColors
  // Omit redundant "Planned" badges so PLANNED cards stay within the <= 3 metadata budget.
  val (bgColor, fgColor, label) = when (status) {
    MealSlotStatus.COOKED -> Triple(
      extendedColors.cookedSuccessContainer,
      extendedColors.onCookedSuccess,
      "Cooked ✓",
    )
    MealSlotStatus.COMPLETED_SKIPPED -> Triple(
      extendedColors.terracottaContainer,
      extendedColors.onTerracottaContainer,
      "Skipped",
    )
    MealSlotStatus.PLANNED -> return
  }
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

@Composable
private fun WorkoutBoostBanner(modifierProfile: WorkoutTiming, instructionAddOn: String) {
  val extendedColors = KitchenTheme.extendedColors
  // EC-07: Dad's workout boost adds a flat 1x portion without multiplying all 4 family servings.
  val profileBadge = when (modifierProfile) {
    WorkoutTiming.PRE_WORKOUT_CARB -> "Pre-Workout Carb Boost (1x)"
    WorkoutTiming.POST_WORKOUT_HIGH_PROTEIN -> "Post-Workout High-Protein (1x)"
    WorkoutTiming.NONE -> ""
  }
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
 * Reusable Honey-Amber child choking-safety callout box (`EC-08`) shared across Planner, Pacer,
 * and Recipe Reader with automatic trailing age-phrase deduplication (`"For 2yo: ... for 2yo."`).
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
 * Strips redundant trailing `"for 2yo."` / `"for 2-year-old."` when the string already begins with
 * a stage prefix (`"For 2yo: ..."`).
 */
fun deduplicateChildSafetyText(rawText: String): String {
  val trimmed = rawText.trim()
  // Leave unprefixed strings untouched so standalone tips retain their age context.
  if (!trimmed.contains(":")) return trimmed
  val stripped = trimmed.replace(TRAILING_AGE_DUP_REGEX, "")
  // Re-append terminal punctuation when stripping removed the trailing period.
  return when {
    stripped != trimmed && !stripped.endsWith(".") -> "$stripped."
    else -> stripped
  }
}

@Composable
private fun FamilyGoalPlatingBox(
  platingGuide: Map<String, String>,
  onEditFamilyGoals: () -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Warm parchment sub-card listing how 1 shared pot plates across Dad, Mom, 8yo, and 2yo.
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
      // Weight title row so "Edit Goals" button stays single-line at 1.35x S22 font scaling.
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

@Composable
private fun InstantQuickSwapRow(
  slotId: String,
  candidates: List<Recipe>,
  onQuickSwapRecipe: (slotId: String, newRecipeId: String) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(
      text = "1-Tap In-Stock Swaps (100% Pantry Covered)",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    when {
      candidates.isEmpty() -> {
        Text(
          text = "No alternate 100% in-stock recipes right now.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      else -> {
        // Deduplicate by recipeId so LazyRow keys remain strictly unique across swaps.
        val uniqueCandidates = candidates.distinctBy { it.recipeId }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          items(items = uniqueCandidates, key = { it.recipeId }) { candidate ->
            AssistChip(
              onClick = { onQuickSwapRecipe(slotId, candidate.recipeId) },
              modifier = Modifier.defaultMinSize(minHeight = 48.dp),
              label = {
                Text(
                  text = "${candidate.title} (${candidate.calibratedActiveMinutes}m)",
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

/** Warm Daylight preview of `MealSlotCard` with expanded `EC-08` safety & 4-member plating. */
@Preview(name = "MealSlotCard — Expanded Daylight", showBackground = true, widthDp = 412)
@Composable
fun MealSlotCardPreview() {
  // Preview Monday Breakfast slot with expanded EC-08 safety and EC-13 quick-swap chips.
  val card = PreviewFixtures.resolvedCardsForDay(dayIndex = 0, currentHour24 = 7).first()
  val swaps = PreviewFixtures.mondayInstantSwapsBySlotId[card.slot.slotId].orEmpty()
  FamilyKitchenBoardTheme(darkTheme = false) {
    Surface(color = KitchenTheme.extendedColors.linen, modifier = Modifier.padding(12.dp)) {
      MealSlotCard(
        card = card,
        instantSwapCandidates = swaps,
        householdProfile = PreviewFixtures.householdProfile,
        catalog = PreviewFixtures.catalog,
        initiallyExpanded = true,
      )
    }
  }
}

/** Ambient Counter Dark preview of a prepped-ahead (`-5m`) Lunch `MealSlotCard`. */
@Preview(name = "MealSlotCard — Prepped Counter Dark", showBackground = true, widthDp = 412)
@Composable
fun MealSlotCardCounterDarkPreview() {
  // Preview evening Counter Dark card with active life-event scaling (+1 postpartum serving).
  val lunchCard = PreviewFixtures.resolvedCardsForDay(
    dayIndex = 0,
    currentHour24 = 20,
    profile = PreviewFixtures.eveningLifeEventProfile,
  )[1]
  val swaps = PreviewFixtures.mondayInstantSwapsBySlotId[lunchCard.slot.slotId].orEmpty()
  FamilyKitchenBoardTheme(darkTheme = true) {
    Surface(color = KitchenTheme.extendedColors.linen, modifier = Modifier.padding(12.dp)) {
      MealSlotCard(
        card = lunchCard,
        instantSwapCandidates = swaps,
        householdProfile = PreviewFixtures.eveningLifeEventProfile,
        catalog = PreviewFixtures.catalog,
        initiallyExpanded = true,
      )
    }
  }
}
