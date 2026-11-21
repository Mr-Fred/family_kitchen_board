package com.familykitchen.board.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.AiChefAction
import com.familykitchen.board.domain.AiExecutionMode
import com.familykitchen.board.domain.ChildGrowthStage
import com.familykitchen.board.domain.CloudLlmConfig
import com.familykitchen.board.domain.ConsumerHouseholdTemplate
import com.familykitchen.board.domain.DEFAULT_ONBOARDING_STAPLE_PRESETS
import com.familykitchen.board.domain.DEFAULT_SELECTED_BASIC_INGREDIENT_IDS
import com.familykitchen.board.domain.EdgeDeviceProfile
import com.familykitchen.board.domain.FirstRunOnboardingDraft
import com.familykitchen.board.domain.HouseholdCurrency
import com.familykitchen.board.domain.OnboardingStaplePreset
import com.familykitchen.board.domain.StartingPantryMode
import com.familykitchen.board.domain.defaultMarketRegionForCurrency
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ui.aichef.AiModeSegmentedToggle
import com.familykitchen.board.ui.aichef.CloudApiKeySetupContent
import com.familykitchen.board.ui.aichef.ModelOperationProgressBanner
import com.familykitchen.board.ui.aichef.formatAiRuntimeBadgeLabel
import com.familykitchen.board.ui.aichef.formatBusyImportButtonLabel
import com.familykitchen.board.ui.theme.KitchenTheme

// Stateless, tactile 4-step First-Run Onboarding section cards calibrated for Warm Linen daylight,
// Ambient Counter Dark OLED legibility, 48.dp+ touch targets, and Galaxy S22 1.35x font scaling.

// Canonical allergen exclusions mirrored across AvailabilityFilter and GroceryDiffEngine (EC-02).
private val ONBOARDING_ALLERGEN_OPTIONS: List<String> =
  listOf("EGGS", "DAIRY", "GLUTEN", "NUTS", "PEANUTS", "FISH")

// Quick 1-tap neighborhood and local wet-market presets surfaced on Step 2.
private val REGIONAL_MARKET_PRESETS: List<String> = listOf(
  "Antipolo & Marikina, PH", "Metro Manila, PH", "Local Farmers Market",
  "Neighborhood Supermarket", "Cotonou & Neighborhood Market",
)

// Representative weekly family basket amount in each currency for live formatting previews.
private fun sampleBasketAmountForCurrency(currency: HouseholdCurrency): Double =
  when (currency) {
    HouseholdCurrency.PHP -> 1120.0
    HouseholdCurrency.USD -> 48.0
    HouseholdCurrency.EUR -> 44.0
    HouseholdCurrency.GBP -> 38.0
    HouseholdCurrency.XOF -> 18500.0
  }

// Concise culinary rhythm line keeping each ConsumerHouseholdTemplate card at <= 3 metadata lines.
private fun rhythmDescriptionForTemplate(template: ConsumerHouseholdTemplate): String =
  when (template) {
    ConsumerHouseholdTemplate.FAMILY_TWO_KIDS ->
      "One-pot 4-way family plating · <= ${template.defaultMorningCapMinutes}m weekday mornings"
    ConsumerHouseholdTemplate.PARENTS_AND_BABY ->
      "Express <= ${template.defaultMorningCapMinutes}m mornings · 6-12m baby purees"
    ConsumerHouseholdTemplate.ACTIVE_COUPLE -> "High-protein athletic balance & zero food waste"
    ConsumerHouseholdTemplate.SOLO_COOK -> "Cook once with built-in next-day lunch leftovers"
    ConsumerHouseholdTemplate.LARGE_MULTIGEN_FAMILY -> "Hearty 6-serving family pots & station batch prep"
  }

// Returns true strictly for household templates that include infants, toddlers, or young kids.
private fun templateIncludesYoungChildren(template: ConsumerHouseholdTemplate): Boolean =
  when (template) {
    ConsumerHouseholdTemplate.FAMILY_TWO_KIDS,
    ConsumerHouseholdTemplate.PARENTS_AND_BABY,
    ConsumerHouseholdTemplate.LARGE_MULTIGEN_FAMILY,
    -> true
    ConsumerHouseholdTemplate.ACTIVE_COUPLE,
    ConsumerHouseholdTemplate.SOLO_COOK,
    -> false
  }

/**
 * Removes any staple `ingredientId` from [selectedIds] whose [OnboardingStaplePreset] declares an
 * allergen present in [activeAllergens] so allergen-blocked basics never stay checked.
 *
 * @param selectedIds Currently checked starter pantry `ingredientId`s.
 * @param activeAllergens Active household allergen exclusions (`EGGS`, `DAIRY`, `GLUTEN`, etc.).
 * @return Filtered set of allergen-safe `ingredientId`s.
 */
fun pruneAllergenConflictingBasics(
  selectedIds: Set<String>,
  activeAllergens: Set<String>,
): Set<String> {
  if (activeAllergens.isEmpty() || selectedIds.isEmpty()) return selectedIds
  val upperBlocked = activeAllergens.map { it.trim().uppercase() }.toSet()
  // Map preset allergens once so toggling any allergen chip immediately unchecks conflicting items.
  val blockedIds = DEFAULT_ONBOARDING_STAPLE_PRESETS
    .filter { preset -> preset.allergens.any { tag -> tag.uppercase() in upperBlocked } }
    .map { it.ingredientId }
    .toSet()
  return selectedIds - blockedIds
}

/**
 * Step 1 of 4 (`Welcome & Your Household`): captures household name, [ConsumerHouseholdTemplate],
 * cook lead names, conditional `EC-08` [ChildGrowthStage] safety preview, and allergen exclusions.
 *
 * @param draft Current immutable [FirstRunOnboardingDraft] state slice.
 * @param onDraftChange Callback emitting updated [FirstRunOnboardingDraft] values.
 * @param modifier Optional Compose [Modifier].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingHouseholdStepSection(
  draft: FirstRunOnboardingDraft,
  onDraftChange: (FirstRunOnboardingDraft) -> Unit,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  val chipColors = FilterChipDefaults.filterChipColors(
    selectedContainerColor = extendedColors.sageContainer, selectedLabelColor = extendedColors.onSageContainer,
  )
  val wordImeNext = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next)
  val wordImeDone = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done)

  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    // Editorial Newsreader welcome header establishing calm, non-clinical kitchen warmth.
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("Welcome to SavorAI", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
      Text(
        text = "Tell your sous-chef who you're cooking for so portions, prep timers, and safety tips fit from day one.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    // Full-width Kitchen Name input using 16.dp organic shape tokens.
    OutlinedTextField(
      value = draft.householdName, onValueChange = { onDraftChange(draft.copy(householdName = it)) },
      label = { Text("Kitchen Name", maxLines = 1, overflow = TextOverflow.Ellipsis) },
      placeholder = { Text("Our Family Kitchen", maxLines = 1, overflow = TextOverflow.Ellipsis) },
      singleLine = true, shape = KitchenTheme.shapes.inputDefault, keyboardOptions = wordImeNext,
      modifier = Modifier.fillMaxWidth(),
    )

    // Five tactile ConsumerHouseholdTemplate cards capped at <= 3 metadata lines/pills per card.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Filled.FamilyRestroom, contentDescription = null, tint = extendedColors.herbalSage, modifier = Modifier.size(18.dp))
        Text("Who's at the table?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
      }
      ConsumerHouseholdTemplate.entries.forEach { template ->
        val isSelected = draft.template == template
        OnboardingSelectableCard(
          isSelected = isSelected,
          onClick = {
            // Sync primary cook name when switching to SOLO_COOK and align default child stage.
            val syncedEvening = if (template == ConsumerHouseholdTemplate.SOLO_COOK) draft.morningLeadName else draft.eveningLeadName
            val nextStage = if (templateIncludesYoungChildren(template)) template.defaultYoungestStage else draft.youngestStage
            onDraftChange(draft.copy(template = template, eveningLeadName = syncedEvening, youngestStage = nextStage))
          },
        ) {
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(template.displayLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(end = 8.dp))
            Surface(
              shape = CircleShape, border = BorderStroke(1.dp, extendedColors.borderSubtle),
              color = if (isSelected) MaterialTheme.colorScheme.surfaceContainerLowest else extendedColors.parchment,
            ) {
              Text("${template.defaultServings} servings", style = MaterialTheme.typography.labelSmall, color = extendedColors.onSageContainer, maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
          }
          Text(rhythmDescriptionForTemplate(template), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    }

    // Single Primary Cook input for SOLO_COOK; 2-column Morning & Evening Cook inputs otherwise.
    when (draft.template) {
      ConsumerHouseholdTemplate.SOLO_COOK -> OutlinedTextField(
        value = draft.morningLeadName,
        onValueChange = { name ->
          // Keep morning and evening cook names synchronized for single-cook households.
          onDraftChange(draft.copy(morningLeadName = name, eveningLeadName = name))
        },
        label = { Text("Primary Cook Name", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true, shape = KitchenTheme.shapes.inputDefault, keyboardOptions = wordImeDone,
        modifier = Modifier.fillMaxWidth(),
      )
      else -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // Two equal-weight columns keep labels uncrushed at 1.35x Galaxy S22 font scale.
        OutlinedTextField(
          value = draft.morningLeadName, onValueChange = { onDraftChange(draft.copy(morningLeadName = it)) },
          label = { Text("Morning Cook", maxLines = 1, overflow = TextOverflow.Ellipsis) },
          singleLine = true, shape = KitchenTheme.shapes.inputDefault, keyboardOptions = wordImeNext,
          modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
          value = draft.eveningLeadName, onValueChange = { onDraftChange(draft.copy(eveningLeadName = it)) },
          label = { Text("Evening Cook", maxLines = 1, overflow = TextOverflow.Ellipsis) },
          singleLine = true, shape = KitchenTheme.shapes.inputDefault, keyboardOptions = wordImeDone,
          modifier = Modifier.weight(1f),
        )
      }
    }

    // Conditional child texture & choking-safety selector shown only when kids/baby are present.
    if (templateIncludesYoungChildren(draft.template)) {
      ChildSafetyOnboardingCard(draft = draft, onDraftChange = onDraftChange)
    }

    // Household Allergen Exclusions (48.dp FilterChips) auto-unchecking conflicting pantry basics.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Filled.Shield, contentDescription = null, tint = extendedColors.terracotta, modifier = Modifier.size(18.dp))
        Text("Allergens to Exclude (Optional)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
      }
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ONBOARDING_ALLERGEN_OPTIONS.forEach { allergen ->
          val isBlocked = allergen in draft.selectedAllergens
          FilterChip(
            selected = isBlocked,
            onClick = {
              val nextAllergens = if (isBlocked) draft.selectedAllergens - allergen else draft.selectedAllergens + allergen
              // Immediately uncheck any starter pantry basics that conflict with active allergens.
              val safeBasics = pruneAllergenConflictingBasics(draft.selectedBasicIngredientIds, nextAllergens)
              onDraftChange(draft.copy(selectedAllergens = nextAllergens, selectedBasicIngredientIds = safeBasics))
            },
            leadingIcon = if (isBlocked) ({ Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }) else null,
            label = { Text("Avoid $allergen", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false) },
            colors = chipColors, modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChildSafetyOnboardingCard(
  draft: FirstRunOnboardingDraft,
  onDraftChange: (FirstRunOnboardingDraft) -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  val chipColors = FilterChipDefaults.filterChipColors(
    selectedContainerColor = extendedColors.sageContainer, selectedLabelColor = extendedColors.onSageContainer,
  )
  // Show school-age kid stepper for 4-person and multi-gen family templates.
  val hasSchoolAgeChild = draft.template == ConsumerHouseholdTemplate.FAMILY_TWO_KIDS ||
    draft.template == ConsumerHouseholdTemplate.LARGE_MULTIGEN_FAMILY

  Surface(
    shape = KitchenTheme.shapes.card2xl, color = MaterialTheme.colorScheme.surfaceContainerLowest,
    border = BorderStroke(1.dp, extendedColors.borderSubtle), shadowElevation = 0.dp, modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text("Youngest Child Texture & Safety Stage", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ChildGrowthStage.entries.forEach { stage ->
          FilterChip(
            selected = draft.youngestStage == stage, onClick = { onDraftChange(draft.copy(youngestStage = stage)) },
            label = { Text(stage.displayLabel, style = MaterialTheme.typography.labelSmall) },
            colors = chipColors, modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          )
        }
      }
      // Dedicated Honey-Amber EC-08 safety preview showing how meal cards adapt texture tips.
      Surface(
        shape = RoundedCornerShape(12.dp), color = extendedColors.childSafetyContainer,
        border = BorderStroke(1.dp, extendedColors.childSafetyBorder), shadowElevation = 0.dp, modifier = Modifier.fillMaxWidth(),
      ) {
        Text("${draft.youngestStage.badgePrefix}: ${draft.youngestStage.prepGuidance}", style = MaterialTheme.typography.bodySmall, color = extendedColors.onChildSafety, modifier = Modifier.padding(10.dp))
      }
      // 48.dp age steppers for older school-age child when FAMILY_TWO_KIDS or LARGE_MULTIGEN_FAMILY.
      if (hasSchoolAgeChild) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Text("Older Kid Age (4–17 yrs)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalIconButton(onClick = { onDraftChange(draft.copy(olderKidAgeYears = (draft.olderKidAgeYears - 1).coerceIn(4, 17))) }, modifier = Modifier.size(48.dp)) {
              Icon(Icons.Filled.Remove, contentDescription = "Decrease older child age", modifier = Modifier.size(18.dp))
            }
            Text("${draft.olderKidAgeYears}yo", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            FilledTonalIconButton(onClick = { onDraftChange(draft.copy(olderKidAgeYears = (draft.olderKidAgeYears + 1).coerceIn(4, 17))) }, modifier = Modifier.size(48.dp)) {
              Icon(Icons.Filled.Add, contentDescription = "Increase older child age", modifier = Modifier.size(18.dp))
            }
          }
        }
      }
    }
  }
}

/**
 * Step 2 of 4 (`Currency & Local Market`): selects household [HouseholdCurrency] with live basket
 * formatting previews and neighborhood wet-market / supermarket region labels.
 *
 * @param draft Current immutable [FirstRunOnboardingDraft] state slice.
 * @param onDraftChange Callback emitting updated [FirstRunOnboardingDraft] values.
 * @param modifier Optional Compose [Modifier].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingMarketStepSection(
  draft: FirstRunOnboardingDraft,
  onDraftChange: (FirstRunOnboardingDraft) -> Unit,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  val chipColors = FilterChipDefaults.filterChipColors(
    selectedContainerColor = extendedColors.sageContainer, selectedLabelColor = extendedColors.onSageContainer,
  )
  val previewBasket = draft.currency.formatAmount(sampleBasketAmountForCurrency(draft.currency))

  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("Currency & Local Market", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
      Text("Grocery estimates, whole-package rounding, and rescued expiry savings use your local currency.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    // Warm parchment live preview banner displaying deterministic pure-Kotlin currency formatting.
    Surface(
      shape = KitchenTheme.shapes.card2xl, color = extendedColors.parchment,
      border = BorderStroke(1.dp, extendedColors.borderSubtle), shadowElevation = 0.dp, modifier = Modifier.fillMaxWidth(),
    ) {
      Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
          Icon(Icons.Filled.ShoppingBag, contentDescription = null, tint = extendedColors.herbalSage, modifier = Modifier.size(20.dp))
          Spacer(modifier = Modifier.width(10.dp))
          Column {
            Text("Sample Weekly Basket", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(draft.marketRegionLabel.ifBlank { defaultMarketRegionForCurrency(draft.currency) }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
        }
        Text(previewBasket, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = extendedColors.onSageContainer, maxLines = 1, softWrap = false)
      }
    }

    // Five tactile currency cards updating defaultMarketRegionForCurrency when unedited.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Household Currency", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
      HouseholdCurrency.entries.forEach { option ->
        val isSelected = draft.currency == option
        OnboardingSelectableCard(
          isSelected = isSelected,
          onClick = {
            // Auto-update marketRegionLabel only if still blank or matching previous currency default.
            val prevDefault = defaultMarketRegionForCurrency(draft.currency)
            val currentTrimmed = draft.marketRegionLabel.trim()
            val nextRegion = if (currentTrimmed.isEmpty() || currentTrimmed == prevDefault) defaultMarketRegionForCurrency(option) else draft.marketRegionLabel
            onDraftChange(draft.copy(currency = option, marketRegionLabel = nextRegion))
          },
        ) {
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${option.isoCode} ${option.symbol.trim()} · ${option.displayLabel}", style = MaterialTheme.typography.titleSmall, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(end = 8.dp))
            Text(option.formatAmount(sampleBasketAmountForCurrency(option)), style = MaterialTheme.typography.labelLarge, color = extendedColors.onSageContainer, maxLines = 1, softWrap = false)
          }
        }
      }
    }

    // Quick 48.dp regional market preset chips + custom neighborhood OutlinedTextField.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Quick Market Presets", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        REGIONAL_MARKET_PRESETS.forEach { regionPreset ->
          FilterChip(
            selected = draft.marketRegionLabel.trim() == regionPreset,
            onClick = { onDraftChange(draft.copy(marketRegionLabel = regionPreset)) },
            label = { Text(regionPreset, style = MaterialTheme.typography.labelSmall) },
            colors = chipColors, modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          )
        }
      }
      OutlinedTextField(
        value = draft.marketRegionLabel, onValueChange = { onDraftChange(draft.copy(marketRegionLabel = it)) },
        label = { Text("Neighborhood or Local Market", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        placeholder = { Text(defaultMarketRegionForCurrency(draft.currency), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true, shape = KitchenTheme.shapes.inputDefault,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

/**
 * Step 3 of 4 (`Your Starting Pantry`): offers 3 [StartingPantryMode] cards (`CURATED_BASICS`
 * recommended default with 16 checkable `48.dp` staple chips, `EMPTY_KITCHEN`, and
 * `SAMPLE_DEMO_KITCHEN`).
 *
 * @param draft Current immutable [FirstRunOnboardingDraft] state slice.
 * @param onDraftChange Callback emitting updated [FirstRunOnboardingDraft] values.
 * @param modifier Optional Compose [Modifier].
 */
@Composable
fun OnboardingPantryStepSection(
  draft: FirstRunOnboardingDraft,
  onDraftChange: (FirstRunOnboardingDraft) -> Unit,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  val upperAllergens = draft.selectedAllergens.map { it.uppercase() }.toSet()
  val safeSelectedIds = pruneAllergenConflictingBasics(draft.selectedBasicIngredientIds, upperAllergens)

  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("Your Starting Pantry", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
      Text("Pick how to stock your shelves right now. Missing meal ingredients automatically queue on your Grocery List.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    // Render all 3 StartingPantryMode cards with crisp 1.dp warm stone / sage borders.
    StartingPantryMode.entries.forEach { mode ->
      val isSelected = draft.pantryMode == mode
      // Explain exact 38-item pantry behavior so empty vs demo vs basics expectations stay clear.
      val modeDetail = when (mode) {
        StartingPantryMode.CURATED_BASICS -> "Check the basics you already have at home; everything else starts at 0 stock."
        StartingPantryMode.EMPTY_KITCHEN -> "All 38 catalog items start at 0 stock — ready for receipt/fridge photo scans or manual additions."
        StartingPantryMode.SAMPLE_DEMO_KITCHEN -> "Stocks all 38 demo items (including 3 expiring items: salmon, spinach, and Greek yogurt) immediately."
      }

      OnboardingSelectableCard(isSelected = isSelected, onClick = { onDraftChange(draft.copy(pantryMode = mode)) }) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Icon(Icons.Filled.Kitchen, contentDescription = null, tint = extendedColors.herbalSage, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(mode.displayLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
          }
          if (mode == StartingPantryMode.CURATED_BASICS) {
            Surface(shape = CircleShape, color = extendedColors.sageContainer, border = BorderStroke(1.dp, extendedColors.herbalSage.copy(alpha = 0.45f))) {
              Text("Recommended", style = MaterialTheme.typography.labelSmall, color = extendedColors.onSageContainer, maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
          }
        }
        Text(mode.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        Text(modeDetail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }

      // Expand the 16-item everyday basics checklist right beneath CURATED_BASICS when active.
      if (mode == StartingPantryMode.CURATED_BASICS && isSelected) {
        CuratedBasicsChecklistCard(
          safeSelectedIds = safeSelectedIds, upperAllergens = upperAllergens,
          onUpdateSelectedIds = { nextIds -> onDraftChange(draft.copy(selectedBasicIngredientIds = nextIds)) },
        )
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CuratedBasicsChecklistCard(
  safeSelectedIds: Set<String>,
  upperAllergens: Set<String>,
  onUpdateSelectedIds: (Set<String>) -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  val chipColors = FilterChipDefaults.filterChipColors(
    selectedContainerColor = extendedColors.sageContainer, selectedLabelColor = extendedColors.onSageContainer,
  )
  val countNoun = if (safeSelectedIds.size == 1) "staple" else "staples"

  Surface(
    shape = KitchenTheme.shapes.card2xl, color = MaterialTheme.colorScheme.surfaceContainerLowest,
    border = BorderStroke(1.dp, extendedColors.borderSubtle), shadowElevation = 0.dp, modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(
        text = "${safeSelectedIds.size} $countNoun selected — missing meal items will queue on your Grocery List",
        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = extendedColors.onSageContainer,
      )
      // 48.dp helper buttons to restore recommended 10 basics or clear all in 1 tap.
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(
          onClick = { onUpdateSelectedIds(pruneAllergenConflictingBasics(DEFAULT_SELECTED_BASIC_INGREDIENT_IDS, upperAllergens)) },
          shape = CircleShape, modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        ) { Text("Select Basics", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false) }
        TextButton(
          onClick = { onUpdateSelectedIds(emptySet()) }, shape = CircleShape,
          modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
        ) { Text("Clear All", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false) }
      }
      HorizontalDivider(color = extendedColors.borderSubtle)
      // 16 canonical seed_catalog.json staple chips disabling any allergen-conflicting item.
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DEFAULT_ONBOARDING_STAPLE_PRESETS.forEach { preset ->
          val isBlocked = preset.allergens.any { tag -> tag.uppercase() in upperAllergens }
          val isChecked = !isBlocked && preset.ingredientId in safeSelectedIds
          val chipText = if (isBlocked) "${preset.shortLabel} · Excluded" else "${preset.shortLabel} · ${preset.categoryHint}"
          FilterChip(
            selected = isChecked, enabled = !isBlocked,
            onClick = { onUpdateSelectedIds(if (isChecked) safeSelectedIds - preset.ingredientId else safeSelectedIds + preset.ingredientId) },
            leadingIcon = if (isChecked) ({ Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }) else null,
            label = { Text(chipText, style = MaterialTheme.typography.labelSmall) },
            colors = chipColors, modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          )
        }
      }
    }
  }
}

/**
 * Step 4 of 4 (`AI Sous-Chef Setup`): highlights SavorAI's immediate 100% offline readiness and
 * offers optional Cloud API Key (`Gemini`, `OpenAI`, `Claude`, `OpenRouter`) or local `.litertlm`
 * Gemma SAF model setup using shared `com.familykitchen.board.ui.aichef` components.
 *
 * @param runtimeState Live on-device [AiRuntimeState] (`Uninitialized`, `Importing`, `Loading`, `Ready`).
 * @param activeDeviceProfile Selected Samsung Galaxy S22 / S22 Ultra / Gemma E2B hardware profile.
 * @param cloudConfig Active non-secret [CloudLlmConfig] state slice.
 * @param isVerifyingCloudKey `true` while verifying an HTTPS Cloud LLM key.
 * @param isRefreshingCloudModels `true` while syncing live `/models` for the selected provider.
 * @param importNotice Optional SAF file-picker notice message, or `null`.
 * @param onDismissImportNotice Dismisses [importNotice].
 * @param onLaunchModelPicker Launches the scoped SAF `OpenDocument` `.litertlm` file picker.
 * @param onAiChefAction Dispatches [AiChefAction] configuration intents to `KitchenBoardViewModel`.
 * @param modifier Optional Compose [Modifier].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingAiSetupStepSection(
  runtimeState: AiRuntimeState,
  activeDeviceProfile: EdgeDeviceProfile,
  cloudConfig: CloudLlmConfig,
  isVerifyingCloudKey: Boolean = false,
  isRefreshingCloudModels: Boolean = false,
  importNotice: String? = null,
  onDismissImportNotice: () -> Unit = {},
  onLaunchModelPicker: () -> Unit = {},
  onAiChefAction: (AiChefAction) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  val chipColors = FilterChipDefaults.filterChipColors(
    selectedContainerColor = extendedColors.sageContainer, selectedLabelColor = extendedColors.onSageContainer,
  )

  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("Your AI Sous-Chef", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
      Text("Private by design — start cooking immediately offline, or connect a Cloud API key or local Gemma model anytime.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    // Reassuring warm summary banner confirming zero mandatory downloads or API keys on day one.
    Surface(
      shape = KitchenTheme.shapes.card2xl, color = extendedColors.sageContainer,
      border = BorderStroke(1.dp, extendedColors.herbalSage.copy(alpha = 0.45f)), shadowElevation = 0.dp, modifier = Modifier.fillMaxWidth(),
    ) {
      Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = extendedColors.onSageContainer, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text("Works 100% offline immediately", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = extendedColors.onSageContainer)
          Text(
            text = "SavorAI's deterministic USDA macro, expiry-rescue, and pantry-grounded Sous-Chef needs zero downloads or API keys. Optionally connect a Cloud LLM key or On-Device Gemma (.litertlm) model now or anytime later in the Chef tab.",
            style = MaterialTheme.typography.bodySmall, color = extendedColors.onSageContainer,
          )
        }
      }
    }

    // Reuse 2-pill On-Device vs Cloud API Key segmented switcher from ui.aichef.
    AiModeSegmentedToggle(
      activeMode = cloudConfig.executionMode,
      onSelectMode = { mode -> onAiChefAction(AiChefAction.SelectAiExecutionMode(mode)) },
    )

    when (cloudConfig.executionMode) {
      // Reuse encrypted Android Keystore Cloud API provider + model picker from ui.aichef.
      AiExecutionMode.CLOUD_API -> Surface(
        shape = KitchenTheme.shapes.card2xl, color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, extendedColors.borderSubtle), shadowElevation = 0.dp, modifier = Modifier.fillMaxWidth(),
      ) {
        CloudApiKeySetupContent(
          cloudConfig = cloudConfig, isVerifyingKey = isVerifyingCloudKey, onAction = onAiChefAction,
          isRefreshingModels = isRefreshingCloudModels, modifier = Modifier.padding(14.dp),
        )
      }

      // On-Device Gemma runtime diagnostics + 48.dp SAF .litertlm picker + progress card.
      AiExecutionMode.ON_DEVICE -> Surface(
        shape = KitchenTheme.shapes.card2xl, color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, extendedColors.borderSubtle), shadowElevation = 0.dp, modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Memory, contentDescription = null, tint = extendedColors.herbalSage, modifier = Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text("On-Device Runtime Status", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
              Text(formatAiRuntimeBadgeLabel(runtimeState), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
          }
          // Hardware profile chips enforce 48.dp touch targets on compact Galaxy S22 screens.
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            EdgeDeviceProfile.entries.forEach { profile ->
              val modeTag = if (profile.supportsMultimodalVision) "Vision + Text" else "Text"
              FilterChip(
                selected = activeDeviceProfile == profile,
                onClick = { onAiChefAction(AiChefAction.SelectDeviceProfile(profile)) },
                label = { Text("${profile.displayName} · $modeTag", style = MaterialTheme.typography.labelSmall) },
                colors = chipColors, modifier = Modifier.defaultMinSize(minHeight = 48.dp),
              )
            }
          }
          // Surface live byte/shader progress banner whenever SAF copy or GPU load is running.
          if (runtimeState.isBackgroundBusy) AiBackgroundProgressCard(runtimeState = runtimeState)
          if (!importNotice.isNullOrBlank()) {
            Surface(shape = KitchenTheme.shapes.inputDefault, color = extendedColors.secondaryFixed, border = BorderStroke(1.dp, extendedColors.borderSubtle), modifier = Modifier.fillMaxWidth()) {
              Row(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(importNotice, style = MaterialTheme.typography.bodySmall, color = extendedColors.onSecondaryFixed, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                IconButton(onClick = onDismissImportNotice, modifier = Modifier.size(48.dp)) {
                  Icon(Icons.Filled.Close, contentDescription = "Dismiss model import notice", tint = extendedColors.onSecondaryFixed)
                }
              }
            }
          }
          // 48.dp SAF OpenDocument launcher button importing local .litertlm packages atomically.
          OutlinedButton(
            onClick = onLaunchModelPicker, enabled = !runtimeState.isBackgroundBusy, shape = CircleShape,
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
          ) {
            if (runtimeState.isBackgroundBusy) {
              CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
              Spacer(modifier = Modifier.width(8.dp))
            }
            Text(formatBusyImportButtonLabel(runtimeState, "Import On-Device Gemma (.litertlm)"), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
        }
      }
    }
  }
}

/**
 * Onboarding background progress card delegating SAF `.part` transfer and OpenCL shader loading
 * telemetry to [ModelOperationProgressBanner].
 *
 * @param runtimeState Active [AiRuntimeState] driving determinate or indeterminate progress UI.
 * @param modifier Optional Compose [Modifier].
 */
@Composable
fun AiBackgroundProgressCard(runtimeState: AiRuntimeState, modifier: Modifier = Modifier) {
  // Reuse the tested ModelOperationProgressBanner lambda determinate progress bar.
  ModelOperationProgressBanner(runtimeState = runtimeState, modifier = modifier)
}

@Composable
private fun OnboardingSelectableCard(
  isSelected: Boolean,
  onClick: () -> Unit,
  content: @Composable () -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Highlight selected option in muted botanical sage with 1.dp herbalSage border and 0.dp shadow.
  Surface(
    shape = KitchenTheme.shapes.card2xl,
    color = if (isSelected) extendedColors.sageContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
    border = BorderStroke(1.dp, if (isSelected) extendedColors.herbalSage else extendedColors.borderSubtle),
    shadowElevation = 0.dp,
    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).clip(KitchenTheme.shapes.card2xl).clickable(onClick = onClick),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      content()
    }
  }
}
