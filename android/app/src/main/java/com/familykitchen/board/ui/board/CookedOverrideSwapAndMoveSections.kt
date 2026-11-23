package com.familykitchen.board.ui.board

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.MAX_WEEKDAY_BREAKFAST_MINUTES
import com.familykitchen.board.domain.MealSlotType
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.ResolvedMealCard
import com.familykitchen.board.domain.SlotSkipReason
import com.familykitchen.board.domain.computeCalibratedMorningMinutes
import com.familykitchen.board.domain.doesRecipeMatchPreferredCuisines
import com.familykitchen.board.domain.isWeekdayBreakfastEligible
import com.familykitchen.board.ui.theme.KitchenTheme

// Cohesive swap, 2-tap Move Day, and honest skip-reason sections for CookedOverrideSheet:
// eliminates .take(4) truncation and surfaces Ready Now · Preferred Cuisines · All Recipes filters.

// Short Monday..Sunday chip labels rendered inside the 2-tap Move Day selector.
private val OVERRIDE_SWAP_WEEKDAYS: List<String> = listOf(
  "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun",
)

// Three punctuation-clean swap filter modes surfaced inside CookedOverrideSheet.
private enum class OverrideSwapFilterTab(val chipLabel: String) {
  // 100% pantry-covered dishes ready to cook with zero extra shopping (EC-13).
  READY_NOW("Ready Now"),
  // Dishes matching householdProfile.preferredCuisines (e.g. French, Yoruba and West African).
  PREFERRED_CUISINES("Preferred Cuisines"),
  // Complete slot-eligible household recipe library with zero .take(4) truncation.
  ALL_RECIPES("All Recipes"),
}

/**
 * 2-tap `Move Day` card allowing the user to pick `Mon..Sun` and `Breakfast · Lunch · Dinner`
 * while guarding `EC-01` (`<= 25m` morning active cap).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OverrideSheetMoveDayCard(
  card: ResolvedMealCard,
  onMoveOrSwapSlot: (String, Int, MealSlotType) -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Default target day to tomorrow so 1 tap on Move Day advances a postponed meal naturally.
  val defaultTargetDay = (card.slot.dayIndex + 1) % 7
  val targetDayState = rememberSaveable(card.slot.slotId) { mutableIntStateOf(defaultTargetDay) }
  val targetSlotNameState = rememberSaveable(card.slot.slotId) { mutableStateOf(card.slot.slotType.name) }

  // Guard EC-01: disallow moving a > 25m recipe onto a weekday Breakfast slot.
  val sourceMinutes = computeCalibratedMorningMinutes(card.recipe)
  val eligibleSlots = MealSlotType.entries.filter { slotType ->
    slotType != MealSlotType.BREAKFAST || sourceMinutes <= MAX_WEEKDAY_BREAKFAST_MINUTES
  }
  // Resolve valid target MealSlotType safely even when Breakfast is filtered out by EC-01.
  val resolvedTargetSlot = eligibleSlots.find { it.name == targetSlotNameState.value }
    ?: eligibleSlots.firstOrNull()
    ?: MealSlotType.DINNER

  Surface(
    shape = RoundedCornerShape(16.dp),
    color = extendedColors.parchment,
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = "Move or Swap with Another Day",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
      )
      // Weekday chip row with >= 48.dp touch height per weekday pill.
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        OVERRIDE_SWAP_WEEKDAYS.forEachIndexed { dayIdx, label ->
          FilterChip(
            selected = targetDayState.intValue == dayIdx,
            onClick = { targetDayState.intValue = dayIdx },
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            label = { Text(label, maxLines = 1, softWrap = false) },
          )
        }
      }
      // Target MealSlotType pills + 1-tap Move Day confirmation CTA.
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        eligibleSlots.forEach { slotType ->
          val slotLabel = when (slotType) {
            MealSlotType.BREAKFAST -> "Breakfast"
            MealSlotType.LUNCH -> "Lunch"
            MealSlotType.DINNER -> "Dinner"
          }
          FilterChip(
            selected = resolvedTargetSlot == slotType,
            onClick = { targetSlotNameState.value = slotType.name },
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            label = { Text(slotLabel, maxLines = 1, softWrap = false) },
          )
        }
        Button(
          onClick = { onMoveOrSwapSlot(card.slot.slotId, targetDayState.intValue, resolvedTargetSlot) },
          shape = CircleShape,
          colors = ButtonDefaults.buttonColors(
            containerColor = extendedColors.herbalSage,
            contentColor = Color.White,
          ),
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        ) {
          Text("Move Day", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        }
      }
    }
  }
}

/**
 * Split `Ate Leftovers` vs `Eating Out` row (`EC-12`) releasing reserved groceries while keeping
 * daily macro intake honest.
 */
@Composable
internal fun OverrideSheetSkipReasonRow(
  slotId: String,
  onMarkSkipReason: (String, SlotSkipReason) -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(
      text = "Skip Cooking Today · Release Reserved Groceries",
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      // Ate Leftovers releases reserved uncooked BOM while still counting home-cooked macros.
      FilledTonalButton(
        onClick = { onMarkSkipReason(slotId, SlotSkipReason.ATE_LEFTOVERS) },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
          containerColor = extendedColors.tertiaryFixed,
          contentColor = extendedColors.onTertiaryFixed,
        ),
        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
      ) {
        Icon(Icons.Default.Restaurant, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text("Ate Leftovers", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
      }

      // Eating Out releases reserved uncooked BOM and excludes the slot from home macro totals.
      OutlinedButton(
        onClick = { onMarkSkipReason(slotId, SlotSkipReason.EATING_OUT) },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        border = BorderStroke(1.dp, extendedColors.borderSubtle),
        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
      ) {
        Text("Eating Out", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
      }
    }
  }
}

/**
 * Searchable recipe swap section presenting `Ready Now` · `Preferred Cuisines` · `All Recipes`
 * filter pills without any `.take(4)` truncation.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OverrideSheetSearchableSwapSection(
  card: ResolvedMealCard,
  instantSwapCandidates: List<Recipe>,
  allRecipes: List<Recipe>,
  preferredCuisines: List<String>,
  onSwapToInStockMeal: (slotId: String, newRecipeId: String) -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  val searchQueryState = rememberSaveable(card.slot.slotId) { mutableStateOf("") }
  val defaultFilter = if (instantSwapCandidates.isNotEmpty()) {
    OverrideSwapFilterTab.READY_NOW
  } else {
    OverrideSwapFilterTab.ALL_RECIPES
  }
  val filterNameState = rememberSaveable(card.slot.slotId) { mutableStateOf(defaultFilter.name) }
  val activeFilter = OverrideSwapFilterTab.entries.find { it.name == filterNameState.value } ?: defaultFilter

  val readyIds = remember(instantSwapCandidates) { instantSwapCandidates.map { it.recipeId }.toSet() }
  // Combine instant swaps and full recipe catalog without any .take(4) truncation.
  val eligiblePool = remember(allRecipes, instantSwapCandidates, card.slot.slotType, card.recipe.recipeId) {
    (instantSwapCandidates + allRecipes)
      .distinctBy { it.recipeId }
      .filter { cand ->
        cand.recipeId != card.recipe.recipeId &&
          (card.slot.slotType != MealSlotType.BREAKFAST || isWeekdayBreakfastEligible(cand))
      }
  }

  // Filter by live query and selected filter tab (Ready Now / Preferred Cuisines / All Recipes).
  val visibleCandidates = remember(eligiblePool, activeFilter, searchQueryState.value, preferredCuisines, readyIds) {
    val cleanQuery = searchQueryState.value.trim().lowercase()
    eligiblePool.filter { cand ->
      val matchesQuery = cleanQuery.isEmpty() ||
        cand.title.lowercase().contains(cleanQuery) ||
        cand.cuisineTag.lowercase().contains(cleanQuery)
      if (!matchesQuery) return@filter false
      when (activeFilter) {
        OverrideSwapFilterTab.READY_NOW -> cand.recipeId in readyIds
        OverrideSwapFilterTab.PREFERRED_CUISINES ->
          preferredCuisines.isEmpty() || doesRecipeMatchPreferredCuisines(cand, preferredCuisines)
        OverrideSwapFilterTab.ALL_RECIPES -> true
      }
    }
  }

  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      text = "Swap Meal · ${visibleCandidates.size} options",
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onSurface,
    )

    OutlinedTextField(
      value = searchQueryState.value,
      onValueChange = { searchQueryState.value = it },
      placeholder = { Text("Search dishes or cuisines", maxLines = 1, overflow = TextOverflow.Ellipsis) },
      leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
      singleLine = true,
      shape = RoundedCornerShape(14.dp),
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
      modifier = Modifier.fillMaxWidth(),
    )

    // Filter pills: Ready Now · Preferred Cuisines · All Recipes (zero slashes or parentheses).
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      OverrideSwapFilterTab.entries.forEach { tab ->
        val isSelected = activeFilter == tab
        FilterChip(
          selected = isSelected,
          onClick = { filterNameState.value = tab.name },
          shape = CircleShape,
          colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = extendedColors.herbalSage,
            selectedLabelColor = Color.White,
          ),
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          label = { Text(tab.chipLabel, maxLines = 1, softWrap = false) },
        )
      }
    }

    when {
      visibleCandidates.isEmpty() -> {
        // Guide the user to switch to All Recipes when Ready Now or Preferred Cuisines is empty.
        Text(
          text = "No matching recipes in this filter. Switch to All Recipes to browse every dish.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      else -> {
        // Render one ceramic swap card per matching recipe with a 48.dp Swap button.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          visibleCandidates.forEach { swapRecipe ->
            val isReadyNow = swapRecipe.recipeId in readyIds
            // Strip slashes and parentheses from cuisine tags before rendering subtitle metadata.
            val cleanCuisine = overrideSwapSanitizeLabel(swapRecipe.cuisineTag)
            Surface(
              shape = RoundedCornerShape(14.dp),
              color = MaterialTheme.colorScheme.surfaceContainerLowest,
              border = BorderStroke(1.dp, extendedColors.borderSubtle),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                  Text(
                    text = swapRecipe.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                  )
                  // Highlight Ready Now in Herbal Sage when 100% of BOM is already stocked.
                  val statusText = if (isReadyNow) "Ready Now" else cleanCuisine
                  Text(
                    text = "${swapRecipe.calibratedActiveMinutes}m · $statusText",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isReadyNow) extendedColors.herbalSage else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }

                // 48.dp 1-tap Swap button committing O(1) slot replacement.
                FilledTonalButton(
                  onClick = { onSwapToInStockMeal(card.slot.slotId, swapRecipe.recipeId) },
                  contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                  colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = extendedColors.sageContainer,
                    contentColor = extendedColors.onSageContainer,
                  ),
                  modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                  Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                  Spacer(modifier = Modifier.width(4.dp))
                  Text("Swap", maxLines = 1, softWrap = false)
                }
              }
            }
          }
        }
      }
    }
  }
}

/**
 * Normalizes catalog or cuisine strings so rendered UI pills contain zero slashes or parentheses.
 */
internal fun overrideSwapSanitizeLabel(raw: String): String =
  // Replace slash/parenthesis punctuation with natural connectors or middle dots.
  raw
    .replace(" / ", " and ")
    .replace("/", " · ")
    .replace(" (", " · ")
    .replace("(", "")
    .replace(")", "")
    .trim()
