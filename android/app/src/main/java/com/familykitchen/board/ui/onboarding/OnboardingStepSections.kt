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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.ChildGrowthStage
import com.familykitchen.board.domain.ConsumerHouseholdTemplate
import com.familykitchen.board.domain.CuisineTradition
import com.familykitchen.board.domain.DEFAULT_ONBOARDING_STAPLE_PRESETS
import com.familykitchen.board.domain.FirstRunOnboardingDraft
import com.familykitchen.board.domain.HouseholdCurrency
import com.familykitchen.board.domain.defaultMarketRegionForCurrency
import com.familykitchen.board.ui.theme.KitchenTheme
import com.familykitchen.board.ui.theme.SavorBrandBadge

// Stateless Step 1 (`Welcome & Your Household` + `Preferred Family Cuisines`) and Step 2
// (`Currency and Local Market`) First-Run Onboarding section cards with 48.dp+ touch targets.

// Six major allergen codes surfaced as 48.dp exclusion chips on Step 1.
private val ONBOARDING_ALLERGEN_OPTIONS: List<String> =
  listOf("EGGS", "DAIRY", "GLUTEN", "NUTS", "PEANUTS", "FISH")

// Regional market presets offered on Step 2 with zero slashes or parentheses.
private val REGIONAL_MARKET_PRESETS: List<String> = listOf(
  "Antipolo & Marikina, PH",
  "Metro Manila, PH",
  "Local Farmers Market",
  "Neighborhood Supermarket",
  "Cotonou & Neighborhood Market",
)

// Realistic weekly sample basket totals demonstrating each currency symbol live on Step 2.
private fun onboardSampleBasketForCurrency(currency: HouseholdCurrency): Double =
  when (currency) {
    HouseholdCurrency.PHP -> 1120.0
    HouseholdCurrency.USD -> 48.0
    HouseholdCurrency.EUR -> 44.0
    HouseholdCurrency.GBP -> 38.0
    HouseholdCurrency.XOF -> 18500.0
  }

// Summarizes each ConsumerHouseholdTemplate rhythm cleanly without slashes or parentheses.
private fun onboardRhythmForTemplate(template: ConsumerHouseholdTemplate): String =
  when (template) {
    ConsumerHouseholdTemplate.FAMILY_TWO_KIDS ->
      "One-pot 4-way family plating · Under ${template.defaultMorningCapMinutes}m weekday mornings"
    ConsumerHouseholdTemplate.PARENTS_AND_BABY ->
      "Express ${template.defaultMorningCapMinutes}m mornings · 6-12m baby purees"
    ConsumerHouseholdTemplate.ACTIVE_COUPLE -> "High-protein athletic balance and zero food waste"
    ConsumerHouseholdTemplate.SOLO_COOK -> "Cook once with built-in next-day lunch leftovers"
    ConsumerHouseholdTemplate.LARGE_MULTIGEN_FAMILY -> "Hearty 6-serving family pots and station batch prep"
  }

// Returns true when the selected household template includes infants, toddlers, or school kids.
private fun onboardTemplateHasChildren(template: ConsumerHouseholdTemplate): Boolean =
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
 * cook lead names, `Preferred Family Cuisines`, conditional `EC-08` [ChildGrowthStage] safety
 * preview, and allergen exclusions.
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
    selectedContainerColor = extendedColors.sageContainer,
    selectedLabelColor = extendedColors.onSageContainer,
  )
  val wordImeNext = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next)
  val wordImeDone = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done)

  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Warm Dutch-oven cocotte brand badge (zero generic AI sparkles).
        SavorBrandBadge(badgeSize = 40.dp, logoSize = 24.dp, shape = RoundedCornerShape(12.dp))
        Text("Welcome to SavorAI", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
      }
      Text(
        text = "Tell your sous-chef who you are cooking for so portions, preferred cuisines, prep timers, and safety tips fit from day one.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    OutlinedTextField(
      value = draft.householdName,
      onValueChange = { onDraftChange(draft.copy(householdName = it)) },
      label = { Text("Kitchen Name", maxLines = 1, overflow = TextOverflow.Ellipsis) },
      placeholder = { Text("Our Family Kitchen", maxLines = 1, overflow = TextOverflow.Ellipsis) },
      singleLine = true,
      shape = KitchenTheme.shapes.inputDefault,
      keyboardOptions = wordImeNext,
      modifier = Modifier.fillMaxWidth(),
    )

    // Five tactile ConsumerHouseholdTemplate cards with punctuation-clean labels.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Filled.FamilyRestroom, contentDescription = null, tint = extendedColors.herbalSage, modifier = Modifier.size(18.dp))
        Text("Who is at the table?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
      }
      ConsumerHouseholdTemplate.entries.forEach { template ->
        val isSelected = draft.template == template
        OnboardSelectableCard(
          isSelected = isSelected,
          onClick = {
            val syncedEvening = if (template == ConsumerHouseholdTemplate.SOLO_COOK) draft.morningLeadName else draft.eveningLeadName
            val nextStage = if (onboardTemplateHasChildren(template)) template.defaultYoungestStage else draft.youngestStage
            onDraftChange(draft.copy(template = template, eveningLeadName = syncedEvening, youngestStage = nextStage))
          },
        ) {
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(onboardFormatTemplateLabel(template), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(end = 8.dp))
            Surface(
              shape = CircleShape,
              border = BorderStroke(1.dp, extendedColors.borderSubtle),
              color = if (isSelected) MaterialTheme.colorScheme.surfaceContainerLowest else extendedColors.parchment,
            ) {
              Text("${template.defaultServings} servings", style = MaterialTheme.typography.labelSmall, color = extendedColors.onSageContainer, maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
          }
          Text(onboardRhythmForTemplate(template), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    }

    // Single cook input for Solo Cook vs Morning + Evening cook inputs for shared households.
    when (draft.template) {
      ConsumerHouseholdTemplate.SOLO_COOK -> OutlinedTextField(
        value = draft.morningLeadName,
        onValueChange = { name -> onDraftChange(draft.copy(morningLeadName = name, eveningLeadName = name)) },
        label = { Text("Primary Cook Name", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        shape = KitchenTheme.shapes.inputDefault,
        keyboardOptions = wordImeDone,
        modifier = Modifier.fillMaxWidth(),
      )
      else -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
          value = draft.morningLeadName,
          onValueChange = { onDraftChange(draft.copy(morningLeadName = it)) },
          label = { Text("Morning Cook", maxLines = 1, overflow = TextOverflow.Ellipsis) },
          singleLine = true,
          shape = KitchenTheme.shapes.inputDefault,
          keyboardOptions = wordImeNext,
          modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
          value = draft.eveningLeadName,
          onValueChange = { onDraftChange(draft.copy(eveningLeadName = it)) },
          label = { Text("Evening Cook", maxLines = 1, overflow = TextOverflow.Ellipsis) },
          singleLine = true,
          shape = KitchenTheme.shapes.inputDefault,
          keyboardOptions = wordImeDone,
          modifier = Modifier.weight(1f),
        )
      }
    }

    // Prominent Preferred Family Cuisines multi-select section + inline Add Cuisine field.
    onboardPreferredCuisinesCard(draft = draft, onDraftChange = onDraftChange)

    // Conditional EC-08 child choking-safety stage card for child-bearing household templates.
    if (onboardTemplateHasChildren(draft.template)) {
      onboardChildSafetyCard(draft = draft, onDraftChange = onDraftChange)
    }

    // Allergen exclusion chips automatically pruning conflicting Step 3 starter basics.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Filled.Shield, contentDescription = null, tint = extendedColors.terracotta, modifier = Modifier.size(18.dp))
        Text("Allergens to Exclude · Optional", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
      }
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ONBOARDING_ALLERGEN_OPTIONS.forEach { allergen ->
          val isBlocked = allergen in draft.selectedAllergens
          FilterChip(
            selected = isBlocked,
            onClick = {
              val nextAllergens = if (isBlocked) draft.selectedAllergens - allergen else draft.selectedAllergens + allergen
              val safeBasics = pruneAllergenConflictingBasics(draft.selectedBasicIngredientIds, nextAllergens)
              onDraftChange(draft.copy(selectedAllergens = nextAllergens, selectedBasicIngredientIds = safeBasics))
            },
            leadingIcon = if (isBlocked) ({ Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }) else null,
            label = { Text("Avoid $allergen", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false) },
            colors = chipColors,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun onboardPreferredCuisinesCard(
  draft: FirstRunOnboardingDraft,
  onDraftChange: (FirstRunOnboardingDraft) -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  var customCuisineInput by rememberSaveable { mutableStateOf("") }
  val activeCuisines = draft.preferredCuisines
  val canonicalSet = remember { CuisineTradition.entries.map { it.canonicalTag }.toSet() }
  // Separate custom user-entered cuisines so they render as removable InputChips.
  val customCuisines = remember(activeCuisines) {
    activeCuisines.filter { tag -> CuisineTradition.fromTagOrNull(tag) == null && tag !in canonicalSet }
  }

  Surface(
    shape = KitchenTheme.shapes.card2xl,
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
    shadowElevation = 0.dp,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Filled.Public, contentDescription = null, tint = extendedColors.herbalSage, modifier = Modifier.size(18.dp))
        Text("Preferred Family Cuisines", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
      }
      // Exact mandatory helper text explaining local-market grounded cuisine prioritization.
      Text(
        text = "Meal plans prioritize your family cuisines using ingredients available in your local market.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      // Multi-select CuisineTradition chips (`French`, `Yoruba and West African`, `Filipino`, etc.).
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CuisineTradition.entries.forEach { tradition ->
          val isSelected = activeCuisines.any { CuisineTradition.fromTagOrNull(it) == tradition }
          FilterChip(
            selected = isSelected,
            onClick = {
              // Add or remove canonical cuisine tag on FirstRunOnboardingDraft.
              val next = if (isSelected) {
                activeCuisines.filterNot { CuisineTradition.fromTagOrNull(it) == tradition }
              } else {
                activeCuisines + tradition.canonicalTag
              }
              onDraftChange(draft.copy(preferredCuisines = next))
            },
            colors = FilterChipDefaults.filterChipColors(
              selectedContainerColor = extendedColors.herbalSage,
              selectedLabelColor = Color.White,
            ),
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            label = { Text(tradition.chipLabel, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false) },
          )
        }
        // Removable InputChips for any custom family cuisine tags added below.
        customCuisines.forEach { customTag ->
          InputChip(
            selected = true,
            onClick = { onDraftChange(draft.copy(preferredCuisines = activeCuisines - customTag)) },
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            label = { Text(customTag, style = MaterialTheme.typography.labelSmall) },
            trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove $customTag", modifier = Modifier.size(14.dp)) },
          )
        }
      }

      // Sanitize and append non-duplicate custom cuisine tag onto FirstRunOnboardingDraft.
      val commitCustomCuisine = {
        val clean = onboardSanitizeLabel(customCuisineInput)
        if (clean.isNotEmpty() && activeCuisines.none { it.equals(clean, ignoreCase = true) }) {
          onDraftChange(draft.copy(preferredCuisines = activeCuisines + clean))
          customCuisineInput = ""
        }
      }

      // Inline custom cuisine input + 48.dp Add Cuisine button.
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OutlinedTextField(
          value = customCuisineInput,
          onValueChange = { customCuisineInput = it },
          label = { Text("Custom Cuisine", maxLines = 1, overflow = TextOverflow.Ellipsis) },
          placeholder = { Text("Lebanese or Ethiopian", maxLines = 1, overflow = TextOverflow.Ellipsis) },
          singleLine = true,
          shape = KitchenTheme.shapes.inputDefault,
          keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = { commitCustomCuisine() }),
          modifier = Modifier.weight(1f),
        )
        // Enable Add Cuisine as soon as sanitized custom text is non-empty.
        Button(
          onClick = commitCustomCuisine,
          enabled = onboardSanitizeLabel(customCuisineInput).isNotEmpty(),
          shape = CircleShape,
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        ) {
          Text("Add Cuisine", maxLines = 1, softWrap = false)
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun onboardChildSafetyCard(
  draft: FirstRunOnboardingDraft,
  onDraftChange: (FirstRunOnboardingDraft) -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  val chipColors = FilterChipDefaults.filterChipColors(
    selectedContainerColor = extendedColors.sageContainer,
    selectedLabelColor = extendedColors.onSageContainer,
  )
  // Show school-age stepper only on templates that include an older sibling (`4..17` yrs).
  val hasSchoolAgeChild = draft.template == ConsumerHouseholdTemplate.FAMILY_TWO_KIDS ||
    draft.template == ConsumerHouseholdTemplate.LARGE_MULTIGEN_FAMILY

  Surface(
    shape = KitchenTheme.shapes.card2xl,
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
    shadowElevation = 0.dp,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text("Youngest Child Texture and Safety Stage", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
      // EC-08 48.dp feeding stage chips (6-12m Baby, 1-2 yr Toddler, 3-4 yr Preschooler, 5+ yr Kid).
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ChildGrowthStage.entries.forEach { stage ->
          FilterChip(
            selected = draft.youngestStage == stage,
            onClick = { onDraftChange(draft.copy(youngestStage = stage)) },
            label = { Text(onboardFormatStageLabel(stage), style = MaterialTheme.typography.labelSmall) },
            colors = chipColors,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          )
        }
      }
      // Honey-Amber preview banner showing how EC-08 choking-safety tips adapt to the chosen stage.
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = extendedColors.childSafetyContainer,
        border = BorderStroke(1.dp, extendedColors.childSafetyBorder),
        shadowElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = "${draft.youngestStage.badgePrefix}: ${onboardSanitizeLabel(draft.youngestStage.prepGuidance)}",
          style = MaterialTheme.typography.bodySmall,
          color = extendedColors.onChildSafety,
          modifier = Modifier.padding(10.dp),
        )
      }
      // Show school-age child stepper (`4..17` yrs) for 2-kid and multi-generational templates.
      if (hasSchoolAgeChild) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Text("Older Kid Age · 4 to 17 yrs", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
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
 * Step 2 of 4 (`Currency and Local Market`): selects household [HouseholdCurrency] with live basket
 * formatting previews and neighborhood wet-market or supermarket region labels.
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
    selectedContainerColor = extendedColors.sageContainer,
    selectedLabelColor = extendedColors.onSageContainer,
  )
  // Format sample weekly basket in the active HouseholdCurrency for instant visual confirmation.
  val previewBasket = draft.currency.formatAmount(onboardSampleBasketForCurrency(draft.currency))

  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("Currency and Local Market", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
      Text("Grocery estimates, whole-package rounding, and rescued expiry savings use your local currency.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    // Live currency preview banner showing sample weekly basket formatting.
    Surface(
      shape = KitchenTheme.shapes.card2xl,
      color = extendedColors.parchment,
      border = BorderStroke(1.dp, extendedColors.borderSubtle),
      shadowElevation = 0.dp,
      modifier = Modifier.fillMaxWidth(),
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

    // Selectable >= 56.dp currency cards (PHP, USD, EUR, GBP, XOF) with auto-synced default market.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Household Currency", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
      HouseholdCurrency.entries.forEach { option ->
        val isSelected = draft.currency == option
        OnboardSelectableCard(
          isSelected = isSelected,
          onClick = {
            // Update marketRegionLabel automatically only when untouched or still on prior default.
            val prevDefault = defaultMarketRegionForCurrency(draft.currency)
            val currentTrimmed = draft.marketRegionLabel.trim()
            val nextRegion = if (currentTrimmed.isEmpty() || currentTrimmed == prevDefault) defaultMarketRegionForCurrency(option) else draft.marketRegionLabel
            onDraftChange(draft.copy(currency = option, marketRegionLabel = nextRegion))
          },
        ) {
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(onboardFormatCurrencyLabel(option), style = MaterialTheme.typography.titleSmall, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(end = 8.dp))
            Text(option.formatAmount(onboardSampleBasketForCurrency(option)), style = MaterialTheme.typography.labelLarge, color = extendedColors.onSageContainer, maxLines = 1, softWrap = false)
          }
        }
      }
    }

    // 1-tap regional market preset chips + custom neighborhood text field.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Quick Market Presets", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        REGIONAL_MARKET_PRESETS.forEach { regionPreset ->
          FilterChip(
            selected = draft.marketRegionLabel.trim() == regionPreset,
            onClick = { onDraftChange(draft.copy(marketRegionLabel = regionPreset)) },
            label = { Text(regionPreset, style = MaterialTheme.typography.labelSmall) },
            colors = chipColors,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          )
        }
      }
      OutlinedTextField(
        value = draft.marketRegionLabel,
        onValueChange = { onDraftChange(draft.copy(marketRegionLabel = it)) },
        label = { Text("Neighborhood or Local Market", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        placeholder = { Text(defaultMarketRegionForCurrency(draft.currency), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        shape = KitchenTheme.shapes.inputDefault,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

/**
 * Shared `>= 56.dp` tactile selectable card surface used across all 4 onboarding steps.
 */
@Composable
internal fun OnboardSelectableCard(
  isSelected: Boolean,
  onClick: () -> Unit,
  content: @Composable () -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  Surface(
    shape = KitchenTheme.shapes.card2xl,
    color = if (isSelected) extendedColors.sageContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
    border = BorderStroke(1.dp, if (isSelected) extendedColors.herbalSage else extendedColors.borderSubtle),
    shadowElevation = 0.dp,
    modifier = Modifier
      .fillMaxWidth()
      .defaultMinSize(minHeight = 56.dp)
      .clip(KitchenTheme.shapes.card2xl)
      .clickable(onClick = onClick),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      content()
    }
  }
}

// Formats ConsumerHouseholdTemplate titles without slashes or parentheses.
private fun onboardFormatTemplateLabel(template: ConsumerHouseholdTemplate): String =
  when (template) {
    ConsumerHouseholdTemplate.FAMILY_TWO_KIDS -> "Family of 4 · 2 Parents and 2 Kids"
    ConsumerHouseholdTemplate.PARENTS_AND_BABY -> "Young Family · 2 Parents and Baby"
    ConsumerHouseholdTemplate.ACTIVE_COUPLE -> "Active Couple · 2 Adults"
    ConsumerHouseholdTemplate.SOLO_COOK -> "Solo Cook · 1 Adult and Leftovers"
    ConsumerHouseholdTemplate.LARGE_MULTIGEN_FAMILY -> "Large Multi-Gen Family · 6 Servings"
  }

// Formats ChildGrowthStage chip labels without slashes or parentheses.
private fun onboardFormatStageLabel(stage: ChildGrowthStage): String =
  when (stage) {
    ChildGrowthStage.BABY_6_12M -> "6-12m Baby · Purees"
    ChildGrowthStage.TODDLER_1_2Y -> "1-2 yr Toddler · Soft Strips"
    ChildGrowthStage.PRESCHOOL_3_4Y -> "3-4 yr Preschooler · Bite-Sized"
    ChildGrowthStage.KIDS_5_PLUS -> "5+ yr Kid · Full Portions"
  }

// Formats HouseholdCurrency card titles without slashes or parentheses.
private fun onboardFormatCurrencyLabel(option: HouseholdCurrency): String =
  when (option) {
    HouseholdCurrency.PHP -> "PHP ${option.symbol.trim()} · Philippine Peso"
    HouseholdCurrency.USD -> "USD ${option.symbol.trim()} · US Dollar"
    HouseholdCurrency.EUR -> "EUR ${option.symbol.trim()} · Euro"
    HouseholdCurrency.GBP -> "GBP ${option.symbol.trim()} · British Pound"
    HouseholdCurrency.XOF -> "XOF · West African CFA Franc"
  }

/**
 * Strips slashes and parentheses from preset or user-entered onboarding labels.
 */
internal fun onboardSanitizeLabel(raw: String): String =
  // Normalize slash and parenthesis punctuation into clean words or middle dots.
  raw
    .replace(" / ", " and ")
    .replace("/", " · ")
    .replace(" (", " · ")
    .replace("(", "")
    .replace(")", "")
    .replace(Regex("\\s+"), " ")
    .trim()
