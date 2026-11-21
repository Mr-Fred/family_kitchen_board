package com.familykitchen.board.ui.aichef

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.AiMenuOptimizationProposal
import com.familykitchen.board.domain.BaseUnit
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.IngredientCategory
import com.familykitchen.board.domain.IngredientTier
import com.familykitchen.board.domain.IntakeCommitTarget
import com.familykitchen.board.domain.MealSlotType
import com.familykitchen.board.domain.StorageLocation
import com.familykitchen.board.domain.VisualIntakeResult
import com.familykitchen.board.domain.synthesizeCatalogItemFromLabel
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme
import kotlin.math.roundToInt

// Structured interactive Sous-Chef artifact cards (<= 3 badges max, 1.dp warm border, 0.dp shadow):
// 1. Interactive Selectable VisualIntakeConfirmationCard (catalog matches + newly discovered spices),
// 2. MealSwapsProposalCard (`Apply Meal Swaps`),
// 3. BrainBoosterIngredientsCard (`+ Add to Grocery`).

// 0-based weekday labels matching MealSlot.dayIndex (0 = Mon .. 6 = Sun).
private val WEEKDAY_SHORT_NAMES: List<String> =
  listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

private data class SelectableIntakeChipModel(
  val key: String,
  val chipLabel: String,
  val ingredientId: String?,
  val newLabel: String?,
  val isNewCatalogItem: Boolean,
)

/**
 * Returns `true` whenever [intakeResult] contains at least one detected item, proposed new catalog
 * item, or visible unrecognized label to surface in [VisualIntakeConfirmationCard].
 */
internal fun hasRenderableVisualIntake(intakeResult: VisualIntakeResult): Boolean =
  intakeResult.detectedItems.isNotEmpty() ||
    intakeResult.proposedNewCatalogItems.isNotEmpty() ||
    intakeResult.unrecognizedLabels.isNotEmpty()

/**
 * Interactive confirmation-gated photo scan artifact card displaying selectable `FilterChip`s
 * (`minHeight = 44.dp`, pre-selected `true`) for **every** matched catalog item AND **every**
 * `proposedNewCatalogItems` / culinary label (e.g., `[✓] Ground Turmeric · New`, `[✓] Bay Leaves · New`),
 * paired with `48.dp` **`Add to Pantry (N)`** and **`+ Grocery`** commit actions.
 *
 * @param intakeResult Grounded visual inspection result from `AiMenuAdvisorEngine`.
 * @param isConfirmed Whether the user has already committed this visual scan turn.
 * @param onConfirmRestock Legacy fallback callback committing default `groundedRestockDeltas`.
 * @param turnId Stable chat `turnId` scoping chip selection across scroll/recomposition.
 * @param onCommitSelection Emits `(selectedIngredientIds, selectedNewLabels, destination)` for `O(1)` commit.
 * @param modifier Optional Compose [Modifier].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VisualIntakeConfirmationCard(
  intakeResult: VisualIntakeResult,
  isConfirmed: Boolean,
  onConfirmRestock: () -> Unit = {},
  turnId: String = "turn_visual",
  onCommitSelection: ((Set<String>, List<String>, IntakeCommitTarget) -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  if (!hasRenderableVisualIntake(intakeResult)) return

  val extendedColors = KitchenTheme.extendedColors
  val (selectableChips, skippedNonFoodLabels) = remember(intakeResult) {
    buildSelectableIntakeChips(intakeResult)
  }
  // Track deselected keys so 100% of discovered culinary items default to pre-selected (true).
  var deselectedKeys by rememberSaveable(turnId) { mutableStateOf(emptyList<String>()) }
  val selectedChips = selectableChips.filter { it.key !in deselectedKeys }
  val selectedCount = selectedChips.size

  // Dispatch selective O(1) commit to Pantry or Smart Grocery List with fallback to legacy callback.
  val dispatchCommit: (IntakeCommitTarget) -> Unit = { target ->
    val selectedIds = selectedChips.mapNotNull { it.ingredientId }.toSet()
    val selectedLabels = selectedChips.mapNotNull { it.newLabel }.distinct()
    when {
      onCommitSelection != null -> onCommitSelection(selectedIds, selectedLabels, target)
      else -> onConfirmRestock()
    }
  }

  // Flat ceramic card with 1.dp warm stone border and zero artificial drop shadow.
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    color = extendedColors.cardSurface,
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
    shadowElevation = 0.dp,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      // Header capped strictly at <= 3 metadata badges: Title + item count pill + status pill.
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "Photo Scan",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.weight(1f),
        )
        // Badge group stays <= 2 capsules so compact 360dp screens never wrap title text.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
          // Display singular/plural detected ingredient count pill whenever grounded chips exist.
          if (selectableChips.isNotEmpty()) {
            // Sage pill keeps item count visually distinct from the Saved confirmation state.
            Surface(shape = CircleShape, color = extendedColors.sageContainer) {
              Text(
                text = "${selectableChips.size} ${if (selectableChips.size == 1) "item" else "items"}",
                style = MaterialTheme.typography.labelSmall,
                color = extendedColors.onSageContainer,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
              )
            }
          }
          // Render Saved pill once the user confirms Pantry or Grocery intake.
          if (isConfirmed) {
            Surface(shape = CircleShape, color = extendedColors.cookedSuccessContainer) {
              Text(
                text = "Saved ✓",
                style = MaterialTheme.typography.labelSmall,
                color = extendedColors.onCookedSuccess,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
              )
            }
          }
        }
      }

      // Concise 2-line max summary avoids repeating the chip list as a raw prose paragraph.
      if (intakeResult.summary.isNotBlank()) {
        Text(
          text = intakeResult.summary,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }

      // Interactive selectable FilterChips (minHeight = 44.dp) for matched & newly discovered items.
      if (selectableChips.isNotEmpty()) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          selectableChips.forEach { chip ->
            val isSelected = chip.key !in deselectedKeys
            // Toggle membership in deselectedKeys so newly rendered items start checked.
            FilterChip(
              selected = isSelected,
              enabled = !isConfirmed,
              onClick = {
                deselectedKeys = when {
                  isSelected -> deselectedKeys + chip.key
                  else -> deselectedKeys - chip.key
                }
              },
              leadingIcon = if (isSelected) {
                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
              } else {
                null
              },
              label = {
                Text(chip.chipLabel, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
              },
              // Distinguish existing catalog matches (sageContainer) from newly synthesized items (parchment).
              colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = if (chip.isNewCatalogItem) extendedColors.parchment else extendedColors.sageContainer,
                selectedLabelColor = if (chip.isNewCatalogItem) MaterialTheme.colorScheme.onSurface else extendedColors.onSageContainer,
                selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
              ),
              border = FilterChipDefaults.filterChipBorder(
                enabled = !isConfirmed,
                selected = isSelected,
                borderColor = extendedColors.borderSubtle,
                selectedBorderColor = if (chip.isNewCatalogItem) extendedColors.borderSubtle else extendedColors.herbalSage,
              ),
              modifier = Modifier.defaultMinSize(minHeight = 44.dp),
            )
          }
        }
      }

      // Surface non-food exclusions honestly without pre-selecting mugs/plates into Pantry.
      if (skippedNonFoodLabels.isNotEmpty()) {
        Text(
          text = "Skipped non-pantry: ${skippedNonFoodLabels.joinToString(", ")}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      // Dual 48.dp 1-tap commit row: Primary Add to Pantry (N) + Secondary + Grocery.
      if (selectableChips.isNotEmpty()) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          // Primary CTA stocks selected catalog & synthesized items in 1 O(1) snapshot write.
          Button(
            onClick = { dispatchCommit(IntakeCommitTarget.PANTRY) },
            enabled = !isConfirmed && selectedCount > 0,
            shape = CircleShape,
            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
          ) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = if (isConfirmed) "Added to Pantry ✓" else "Add to Pantry ($selectedCount)",
              maxLines = 1,
              softWrap = false,
              overflow = TextOverflow.Ellipsis,
            )
          }

          // Secondary CTA routes selected items onto Smart Grocery List instead of Pantry.
          OutlinedButton(
            onClick = { dispatchCommit(IntakeCommitTarget.GROCERY) },
            enabled = !isConfirmed && selectedCount > 0,
            shape = CircleShape,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          ) {
            Text("+ Grocery", maxLines = 1, softWrap = false)
          }
        }
      }
    }
  }
}

// Builds deduplicated selectable chips from matched items, proposedNewCatalogItems, and raw labels.
private fun buildSelectableIntakeChips(
  intakeResult: VisualIntakeResult,
): Pair<List<SelectableIntakeChipModel>, List<String>> {
  val chips = mutableListOf<SelectableIntakeChipModel>()
  val skippedNonFoodLabels = mutableListOf<String>()
  val seenIngredientIds = HashSet<String>()
  val coveredLowerNames = HashSet<String>()
  val seenSkippedLower = HashSet<String>()

  // 1. Existing household catalog matches from detectedItems.
  intakeResult.detectedItems.forEach { detected ->
    val matchedId = detected.matchedIngredientId
    if (!matchedId.isNullOrBlank() && seenIngredientIds.add(matchedId)) {
      val baseName = (detected.matchedDisplayName ?: detected.rawLabel).trim()
      coveredLowerNames.add(baseName.lowercase())
      coveredLowerNames.add(detected.rawLabel.trim().lowercase())
      val qtySuffix = when {
        detected.estimatedQuantity > 0.0 && detected.unit != null ->
          " · ${detected.estimatedQuantity.roundToInt()} ${detected.unit.wireCode}"
        else -> ""
      }
      chips.add(
        SelectableIntakeChipModel(
          key = "id:$matchedId",
          chipLabel = "$baseName$qtySuffix",
          ingredientId = matchedId,
          newLabel = null,
          isNewCatalogItem = false,
        ),
      )
    }
  }

  // 2. Synthesized proposedNewCatalogItems (e.g., Ground Turmeric, Bay Leaves, Parsley Flakes, Dried Oregano).
  intakeResult.proposedNewCatalogItems.forEach { newItem ->
    if (seenIngredientIds.add(newItem.ingredientId)) {
      coveredLowerNames.add(newItem.displayName.trim().lowercase())
      chips.add(
        SelectableIntakeChipModel(
          key = "new:${newItem.ingredientId}",
          chipLabel = "${newItem.displayName} · New",
          ingredientId = newItem.ingredientId,
          newLabel = newItem.displayName,
          isNewCatalogItem = true,
        ),
      )
    }
  }

  // 3. Unmatched detectedItems: synthesize via domain helper to deduplicate ("flakes of parsley" ->
  // "ing_custom_parsley_flakes") and route non-food items ("Ceramic Coffee Mug") to skipped notes.
  intakeResult.detectedItems.forEach { detected ->
    if (detected.matchedIngredientId.isNullOrBlank()) {
      val clean = detected.rawLabel.trim()
      if (clean.isNotEmpty()) {
        val synth = synthesizeCatalogItemFromLabel(clean)
        when {
          synth == null -> {
            if (!isAlreadyCoveredName(clean.lowercase(), coveredLowerNames) && seenSkippedLower.add(clean.lowercase())) {
              skippedNonFoodLabels.add(clean)
            }
          }
          seenIngredientIds.add(synth.ingredientId) && !isAlreadyCoveredName(synth.displayName.lowercase(), coveredLowerNames) -> {
            coveredLowerNames.add(synth.displayName.lowercase())
            coveredLowerNames.add(clean.lowercase())
            chips.add(
              SelectableIntakeChipModel(
                key = "new:${synth.ingredientId}",
                chipLabel = "${synth.displayName} · New",
                ingredientId = synth.ingredientId,
                newLabel = synth.displayName,
                isNewCatalogItem = true,
              ),
            )
          }
          else -> coveredLowerNames.add(clean.lowercase())
        }
      }
    }
  }

  // 4. Unrecognized labels: synthesize culinary labels if any, and keep non-food items strictly skipped.
  intakeResult.unrecognizedLabels.forEach { rawLabel ->
    val clean = rawLabel.trim()
    if (clean.isNotEmpty() && !isAlreadyCoveredName(clean.lowercase(), coveredLowerNames)) {
      val synth = synthesizeCatalogItemFromLabel(clean)
      when {
        synth == null -> {
          if (seenSkippedLower.add(clean.lowercase())) {
            skippedNonFoodLabels.add(clean)
          }
        }
        seenIngredientIds.add(synth.ingredientId) && !isAlreadyCoveredName(synth.displayName.lowercase(), coveredLowerNames) -> {
          coveredLowerNames.add(synth.displayName.lowercase())
          coveredLowerNames.add(clean.lowercase())
          chips.add(
            SelectableIntakeChipModel(
              key = "new:${synth.ingredientId}",
              chipLabel = "${synth.displayName} · New",
              ingredientId = synth.ingredientId,
              newLabel = synth.displayName,
              isNewCatalogItem = true,
            ),
          )
        }
        else -> coveredLowerNames.add(clean.lowercase())
      }
    }
  }

  return chips to skippedNonFoodLabels
}

private fun isAlreadyCoveredName(candidateLower: String, covered: Set<String>): Boolean =
  candidateLower in covered || covered.any { existing ->
    existing.contains(candidateLower) || candidateLower.contains(existing)
  }

/**
 * Actionable Meal Swaps Proposal Card rendering up to 3 pantry-grounded slot swaps with 1-pot
 * family plating previews and a 1-tap `Apply Meal Swaps` batch button (`48.dp` touch target).
 *
 * @param proposal Grounded [AiMenuOptimizationProposal] containing non-empty `slotSwaps`.
 * @param isApplied Whether these swaps have already been committed to the weekly board.
 * @param onApplySwaps Commits all swaps in `proposal` in one `O(1)` atomic batch write.
 * @param onNavigateToBoard Optional shortcut jumping to `Plan` after applying swaps.
 * @param modifier Optional Compose [Modifier].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MealSwapsProposalCard(
  proposal: AiMenuOptimizationProposal,
  isApplied: Boolean,
  onApplySwaps: () -> Unit,
  onNavigateToBoard: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  var showPlatingDetails by rememberSaveable(proposal.proposalId) { mutableStateOf(false) }

  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    color = extendedColors.cardSurface,
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
    shadowElevation = 0.dp,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "Meal Swaps (${proposal.slotSwaps.size})",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.weight(1f),
        )
        Surface(shape = CircleShape, color = extendedColors.sageContainer) {
          Text(
            text = proposal.focusTag.displayLabel,
            style = MaterialTheme.typography.labelSmall,
            color = extendedColors.onSageContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
          )
        }
      }

      // Each swap enforces EC-01 (<= 25m Breakfast cap) and EC-08 child safety tips in domain/.
      proposal.slotSwaps.forEachIndexed { idx, swap ->
        if (idx > 0) HorizontalDivider(color = extendedColors.borderSubtle)
        val dayLabel = WEEKDAY_SHORT_NAMES.getOrElse(swap.dayIndex) { "Day ${swap.dayIndex + 1}" }
        val slotLabel = when (swap.slotType) {
          MealSlotType.BREAKFAST -> "Bkfst"
          MealSlotType.LUNCH -> "Lunch"
          MealSlotType.DINNER -> "Dinner"
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "$dayLabel $slotLabel · ${swap.suggestedRecipe.title}",
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.SemiBold,
              modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            Text(
              text = "${swap.suggestedRecipe.calibratedActiveMinutes}m",
              style = MaterialTheme.typography.labelMedium,
              color = extendedColors.sage,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
              softWrap = false,
            )
          }
          Text(
            text = swap.rationale,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            swap.brainBoosterNutrients.take(2).forEach { nutrient ->
              Surface(shape = RoundedCornerShape(6.dp), color = extendedColors.parchment) {
                Text(
                  text = nutrient,
                  style = MaterialTheme.typography.labelSmall,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
              }
            }
          }
          if (showPlatingDetails && swap.familyGoalPlating.isNotEmpty()) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
              swap.familyGoalPlating.values.forEach { platingLine ->
                Text("• $platingLine", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            }
          }
        }
      }

      TextButton(
        onClick = { showPlatingDetails = !showPlatingDetails },
        modifier = Modifier.defaultMinSize(minHeight = 44.dp),
      ) {
        Text(if (showPlatingDetails) "Hide 4-Way Family Plating" else "Show 4-Way Family Plating")
      }

      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = onApplySwaps,
          enabled = !isApplied,
          shape = CircleShape,
          modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
        ) {
          Text(
            text = if (isApplied) "Swaps Applied ✓" else "Apply Meal Swaps",
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
          )
        }
        if (isApplied) {
          OutlinedButton(
            onClick = onNavigateToBoard,
            shape = CircleShape,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          ) {
            Text("View Plan", maxLines = 1, softWrap = false)
          }
        }
      }
    }
  }
}

/**
 * Actionable Suggested Brain-Booster Ingredients Card offering 1-tap grocery queueing (`48.dp` CTA)
 * for allergen-safe pediatric and family discovery items.
 *
 * @param proposal Grounded [AiMenuOptimizationProposal] containing `newIngredientSuggestions`.
 * @param isQueued Whether these suggested items have already been queued onto Smart Grocery.
 * @param onQueueIngredients Commits suggested items to catalog + `aiAddedGroceryIds` atomically.
 * @param onNavigateToGroceryList Optional shortcut jumping to `Shop` (`SmartGroceryScreen`).
 * @param modifier Optional Compose [Modifier].
 */
@Composable
fun BrainBoosterIngredientsCard(
  proposal: AiMenuOptimizationProposal,
  isQueued: Boolean,
  onQueueIngredients: () -> Unit,
  onNavigateToGroceryList: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    color = extendedColors.cardSurface,
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
    shadowElevation = 0.dp,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
        text = "Suggested Ingredients (${proposal.newIngredientSuggestions.size})",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
      )

      // Suggestions are pre-filtered against HouseholdProfile.householdAllergens in domain/.
      proposal.newIngredientSuggestions.forEachIndexed { idx, suggestion ->
        if (idx > 0) HorizontalDivider(color = extendedColors.borderSubtle)
        val pkgQty = "${suggestion.item.defaultPackageQuantity.roundToInt()} ${suggestion.item.baseUnit.wireCode}"
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text("${suggestion.item.displayName} ($pkgQty)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
          Text(suggestion.brainBoosterBenefit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Text("Pairs with: ${suggestion.pairedRecipeTitle}", style = MaterialTheme.typography.labelSmall, color = extendedColors.sage)
        }
      }

      // Queues all suggested items onto Smart Grocery List in 1 O(1) atomic snapshot write.
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
          onClick = onQueueIngredients,
          enabled = !isQueued,
          shape = CircleShape,
          modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
        ) {
          Icon(Icons.Filled.ShoppingCart, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = if (isQueued) "Added to Grocery ✓" else "+ Add to Grocery List",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        // Surface direct Shop navigation shortcut once items are queued.
        if (isQueued) {
          TextButton(
            onClick = onNavigateToGroceryList,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          ) {
            Text("Open Shop", maxLines = 1, softWrap = false)
          }
        }
      }
    }
  }
}

@Preview(name = "Spice Rack Visual Intake Artifact", showBackground = true, widthDp = 412)
@Composable
private fun SpiceRackVisualIntakeCardPreview() {
  // Verifies interactive selectable FilterChips for newly discovered spices + Ceramic Mug exclusion.
  val spices = listOf(
    IngredientCatalogItem("ing_custom_ground_turmeric", "Ground Turmeric", IngredientCategory.PANTRY_STAPLE, IngredientTier.TIER_2_STAPLE, BaseUnit.GRAM, 100.0, defaultStorageLocation = StorageLocation.PANTRY),
    IngredientCatalogItem("ing_custom_bay_leaves", "Bay Leaves", IngredientCategory.PANTRY_STAPLE, IngredientTier.TIER_2_STAPLE, BaseUnit.GRAM, 100.0, defaultStorageLocation = StorageLocation.PANTRY),
    IngredientCatalogItem("ing_custom_parsley_flakes", "Parsley Flakes", IngredientCategory.PANTRY_STAPLE, IngredientTier.TIER_2_STAPLE, BaseUnit.GRAM, 100.0, defaultStorageLocation = StorageLocation.PANTRY),
    IngredientCatalogItem("ing_custom_dried_oregano", "Dried Oregano", IngredientCategory.PANTRY_STAPLE, IngredientTier.TIER_2_STAPLE, BaseUnit.GRAM, 100.0, defaultStorageLocation = StorageLocation.PANTRY),
  )
  val result = VisualIntakeResult(
    summary = "Spotted 4 spices and dried herbs on your rack.",
    unrecognizedLabels = listOf("Ceramic Coffee Mug"),
    proposedNewCatalogItems = spices,
  )
  FamilyKitchenBoardTheme {
    Column(modifier = Modifier.padding(16.dp)) {
      VisualIntakeConfirmationCard(
        intakeResult = result,
        isConfirmed = false,
      )
    }
  }
}
