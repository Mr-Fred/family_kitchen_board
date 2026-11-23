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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SwapHoriz
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
import com.familykitchen.board.domain.SlotSkipReason
import com.familykitchen.board.domain.WorkoutTiming
import com.familykitchen.board.domain.buildFamilyGoalPlatingGuide
import com.familykitchen.board.ui.preview.PreviewFixtures
import com.familykitchen.board.ui.theme.EditorialSerifFontFamily
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme

// Progressive-disclosure MealSlotCard with offline 72.dp RecipePlateThumbnail, Pinned and
// Leftover Lunch badges, distinct skip-reason status pills, and contextual Swap & Ask Chef actions.

/**
 * Stateless progressive-disclosure card representing one scheduled daily meal slot
 * (`Breakfast · Fred`, `Lunch · Wife`, or `Dinner · Wife`).
 *
 * Features a `72.dp` procedural [RecipePlateThumbnail], `Pinned` and `Leftover Lunch · 0 Cost`
 * badges, distinct `Ate Leftovers` vs `Eating Out` vs `Skipped` status pills, a `Newsreader`
 * 2-line dish title, a compact middle-dot macro summary, an expandable details drawer (`EC-08`
 * child safety, 4-member plating, and `EC-13` quick swaps), and `>= 48.dp` `Cook`, `Swap`, and
 * `Ask Chef` actions.
 *
 * @param card Resolved domain meal slot card for the active day.
 * @param instantSwapCandidates 100%-in-stock replacement candidates (`EC-13`).
 * @param householdProfile Active household profile driving cook labels and child safety stage.
 * @param catalog Household ingredient catalog used to synthesize 4-way plating tips.
 * @param initiallyExpanded Expands safety and plating details by default when `true`.
 * @param onStartCooking Invoked with `slotId` to launch Step-by-Step Cook Mode.
 * @param onQuickCookedIt Invoked with `slotId` for 1-tap `O(1)` atomic pantry deduction.
 * @param onOpenAdjustSheet Invoked with `slotId` to open `CookedOverrideSheet` (adjust or swap).
 * @param onQuickSwapRecipe Invoked with `(slotId, newRecipeId)` for 1-tap in-stock swaps.
 * @param onEditFamilyGoals Invoked when tapping `Edit Goals` inside the plating box.
 * @param onOpenRecipeReader Invoked with `recipeId` when tapping the header to open Clean Reader.
 * @param onAskChef Invoked with `slotId` when tapping the contextual `Ask Chef` action.
 * @param modifier Optional Compose [Modifier].
 */
@OptIn(ExperimentalLayoutApi::class)
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
  onAskChef: (String) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Persist per-slot accordion state across scroll and configuration changes.
  val expandedState = rememberSaveable(card.slot.slotId) { mutableStateOf(initiallyExpanded) }
  val recipe = card.recipe
  val isLeftoverSlot = !card.slot.leftoverSourceRecipeId.isNullOrBlank()

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
          verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            // Map CookOwner enum to configured household lead name ("Fred" or "Wife").
            val ownerName = householdProfile.displayLabelForCookOwner(card.slot.owner)
            val slotLabel = mealCardFormatSlotName(card.slot.slotType)
            Text(
              text = "$slotLabel · $ownerName",
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
              SlotCardCalibratedTimePill(
                calibratedMinutes = card.calibratedActiveMinutes,
                isPreppedAhead = card.isPreppedAhead,
                preppedSavedMinutes = card.preppedTimeSavedMinutes,
              )
              // Emit distinct Cooked, Ate Leftovers, Eating Out, or Skipped status pill when completed.
              if (card.slot.status != MealSlotStatus.PLANNED) {
                SlotCardStatusPill(
                  status = card.slot.status,
                  skipReason = card.slot.skipReason,
                )
              }
            }
          }

          // Surface Pinned routine anchor and 0-grocery-cost Leftover Lunch badges when active.
          if (card.slot.isPinned || isLeftoverSlot) {
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              if (card.slot.isPinned) {
                SlotCardPinnedBadge()
              }
              if (isLeftoverSlot) {
                SlotCardLeftoverLunchBadge()
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

          // Middle-dot macro summary free of slashes or parentheses.
          val macroLine = when {
            isLeftoverSlot ->
              "${recipe.caloriesPerServing} kcal · ${recipe.proteinGrams}g P · 0 extra groceries"
            else ->
              "${recipe.caloriesPerServing} kcal · ${recipe.proteinGrams}g P · ${recipe.carbsGrams}g C · ${recipe.fatGrams}g F"
          }
          Text(
            text = macroLine,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      // EC-07: Surface flat 1x Dad workout boost instruction whenever active on this slot.
      if (card.slot.activeWorkoutModifier != WorkoutTiming.NONE && !card.workoutInstructionAddOn.isNullOrBlank()) {
        SlotCardWorkoutBoostBanner(
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
          SlotCardFamilyGoalPlatingBox(
            platingGuide = platingGuide,
            onEditFamilyGoals = onEditFamilyGoals,
          )
          // EC-13: 100% pantry-covered instant swap chips requiring zero extra shopping.
          SlotCardInstantQuickSwapRow(
            slotId = card.slot.slotId,
            candidates = instantSwapCandidates,
            onQuickSwapRecipe = onQuickSwapRecipe,
          )
        }
      }

      HorizontalDivider(color = extendedColors.borderSubtle)

      // Primary 48.dp execution row (`Cook`, `Cooked`, `MoreHoriz`) + contextual `Swap` & `Ask Chef`.
      mealCardExecutionActionBlock(
        slotId = card.slot.slotId,
        status = card.slot.status,
        onStartCooking = onStartCooking,
        onQuickCookedIt = onQuickCookedIt,
        onOpenAdjustSheet = onOpenAdjustSheet,
        onAskChef = onAskChef,
      )
    }
  }
}

@Composable
private fun mealCardExecutionActionBlock(
  slotId: String,
  status: MealSlotStatus,
  onStartCooking: (String) -> Unit,
  onQuickCookedIt: (String) -> Unit,
  onOpenAdjustSheet: (String) -> Unit,
  onAskChef: (String) -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      when (status) {
        MealSlotStatus.PLANNED -> {
          // Primary 48.dp Herbal Sage CTA launching Step-by-Step Cook Mode.
          Button(
            onClick = { onStartCooking(slotId) },
            shape = CircleShape,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            colors = ButtonDefaults.buttonColors(
              containerColor = extendedColors.herbalSage,
              contentColor = Color.White,
            ),
            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
          ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Cook", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
          }

          // Secondary 48.dp 1-tap O(1) atomic pantry deduction pill.
          FilledTonalButton(
            onClick = { onQuickCookedIt(slotId) },
            shape = CircleShape,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
              containerColor = extendedColors.sageContainer,
              contentColor = extendedColors.onSageContainer,
            ),
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          ) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Cooked", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
          }
        }

        MealSlotStatus.COOKED, MealSlotStatus.COMPLETED_SKIPPED -> {
          // Completed slots offer non-wrapping 48.dp Adjust Slot access without slashes.
          OutlinedButton(
            onClick = { onOpenAdjustSheet(slotId) },
            shape = CircleShape,
            border = BorderStroke(1.dp, extendedColors.borderSubtle),
            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
          ) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Adjust Slot", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
          }
        }
      }

      // Compact 48.dp overflow icon button opening CookedOverrideSheet without text wrapping.
      OutlinedIconButton(
        onClick = { onOpenAdjustSheet(slotId) },
        border = BorderStroke(1.dp, extendedColors.borderSubtle),
        modifier = Modifier.size(48.dp),
      ) {
        Icon(Icons.Default.MoreHoriz, contentDescription = "Adjust meal or swap", modifier = Modifier.size(20.dp))
      }
    }

    // Contextual 2-pill row (`Swap` · `Ask Chef`) with zero sparkles and 48.dp touch height.
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      // Left 48.dp Swap pill opening CookedOverrideSheet directly onto the searchable swap picker.
      Surface(
        onClick = { onOpenAdjustSheet(slotId) },
        shape = CircleShape,
        color = extendedColors.parchment,
        border = BorderStroke(1.dp, extendedColors.borderSubtle),
        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
          horizontalArrangement = Arrangement.Center,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = extendedColors.herbalSage, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Swap", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        }
      }

      // Right 48.dp Ask Chef pill launching contextual Sous-Chef chat without generic AI sparkles.
      Surface(
        onClick = { onAskChef(slotId) },
        shape = CircleShape,
        color = extendedColors.parchment,
        border = BorderStroke(1.dp, extendedColors.borderSubtle),
        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
          horizontalArrangement = Arrangement.Center,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(Icons.Default.Forum, contentDescription = null, tint = extendedColors.terracotta, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Ask Chef", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        }
      }
    }
  }
}

/**
 * Offline `72.dp × 72.dp` procedural ceramic plate & garnish thumbnail keyed deterministically
 * by [slotType] and [cuisineTag] (`Canvas` guarded with `if (size.minDimension <= 0f) return@Canvas`).
 *
 * @param slotType Meal category determining the primary culinary medallion hue.
 * @param cuisineTag Cuisine string hashed deterministically to pick garnish accents.
 * @param modifier Optional Compose [Modifier].
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
  // Select secondary garnish dot color from Warm Daylight / Counter Dark culinary tokens.
  val herbAccent = when (hash) {
    0 -> extendedColors.herbalSage
    1 -> extendedColors.terracottaClay
    else -> extendedColors.expiringSoonIndicator
  }
  val plateSurface = MaterialTheme.colorScheme.surfaceContainerLowest

  // Frame procedural plate inside a 72.dp rounded stone-bordered tile.
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
      // Ceramic plate base and subtle 1.5.dp rim stroke.
      drawCircle(color = plateSurface, radius = radius, center = center)
      drawCircle(color = rimColor, radius = radius, center = center, style = Stroke(width = 1.5.dp.toPx()))
      // Main entree portion + two herb/citrus garnish dots.
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

private fun mealCardFormatSlotName(slotType: MealSlotType): String =
  when (slotType) {
    MealSlotType.BREAKFAST -> "Breakfast"
    MealSlotType.LUNCH -> "Lunch"
    MealSlotType.DINNER -> "Dinner"
  }

/** Warm Daylight preview of `MealSlotCard` showing `Pinned` badge, `EC-08` safety, and plating. */
@Preview(name = "MealSlotCard Expanded Daylight", showBackground = true, widthDp = 412)
@Composable
fun MealSlotCardPreview() {
  // Preview Monday Breakfast slot with Pinned badge and expanded EC-08 safety & EC-13 swaps.
  val baseCard = PreviewFixtures.resolvedCardsForDay(dayIndex = 0, currentHour24 = 7).first()
  val pinnedCard = baseCard.copy(slot = baseCard.slot.copy(isPinned = true))
  val swaps = PreviewFixtures.mondayInstantSwapsBySlotId[baseCard.slot.slotId].orEmpty()
  FamilyKitchenBoardTheme(darkTheme = false) {
    Surface(color = KitchenTheme.extendedColors.linen, modifier = Modifier.padding(12.dp)) {
      MealSlotCard(
        card = pinnedCard,
        instantSwapCandidates = swaps,
        householdProfile = PreviewFixtures.householdProfile,
        catalog = PreviewFixtures.catalog,
        initiallyExpanded = true,
      )
    }
  }
}

/** Ambient Counter Dark preview of a `Leftover Lunch` `MealSlotCard` with `0` grocery cost. */
@Preview(name = "MealSlotCard Leftover Counter Dark", showBackground = true, widthDp = 412)
@Composable
fun MealSlotCardCounterDarkPreview() {
  // Preview evening Counter Dark card showcasing Leftover Lunch and Ate Leftovers status pill.
  val baseLunch = PreviewFixtures.resolvedCardsForDay(
    dayIndex = 0,
    currentHour24 = 20,
    profile = PreviewFixtures.eveningLifeEventProfile,
  )[1]
  val leftoverCard = baseLunch.copy(
    slot = baseLunch.slot.copy(
      leftoverSourceRecipeId = baseLunch.recipe.recipeId,
      status = MealSlotStatus.COMPLETED_SKIPPED,
      skipReason = SlotSkipReason.ATE_LEFTOVERS,
    ),
  )
  FamilyKitchenBoardTheme(darkTheme = true) {
    Surface(color = KitchenTheme.extendedColors.linen, modifier = Modifier.padding(12.dp)) {
      MealSlotCard(
        card = leftoverCard,
        instantSwapCandidates = emptyList(),
        householdProfile = PreviewFixtures.eveningLifeEventProfile,
        catalog = PreviewFixtures.catalog,
        initiallyExpanded = false,
      )
    }
  }
}
