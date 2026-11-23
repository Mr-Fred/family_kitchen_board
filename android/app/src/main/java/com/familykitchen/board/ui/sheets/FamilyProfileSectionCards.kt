package com.familykitchen.board.ui.sheets

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.ChildGrowthStage
import com.familykitchen.board.domain.ConsumerHouseholdTemplate
import com.familykitchen.board.domain.CuisineTradition
import com.familykitchen.board.domain.FamilyMemberGoalSpec
import com.familykitchen.board.domain.HouseholdCurrency
import com.familykitchen.board.domain.HouseholdLifeEventPreset
import com.familykitchen.board.ui.theme.KitchenTheme

// Cohesive section cards and punctuation-clean label formatters for FamilyProfileSheet:
// Preferred Family Cuisines (`CuisineTradition.entries` + Add Cuisine), Member Goals, & Life Events.

// Punctuation-clean dietary focus presets selectable on any household member card.
private val PROFILE_SECTION_FOCUS_PRESETS: List<String> = listOf(
  "Muscle Building and 25m Morning Cap",
  "Lean Weight-Loss, High Satiety and Double Greens",
  "Kids Brain and Muscle Development · Choline, DHA and Protein",
  "Youngest Brain and Muscle Growth plus Safe Textures",
  "High-Iron and Vitamin C Synergy · 2x Iron Absorption",
  "Athletic Post-Workout Glycogen and Lean Protein",
  "Steady Classroom Focus and Whole Grains",
  "Heart-Healthy Omega-3 and Mediterranean Greens",
)

/**
 * Prominent `Preferred Family Cuisines` multi-select chip card (`CuisineTradition.entries` plus
 * inline `Add Cuisine`) inside [FamilyProfileSheetContent].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProfileSheetPreferredCuisinesSection(
  preferredCuisines: List<String>,
  customInput: String,
  onCustomInputChange: (String) -> Unit,
  onToggleCuisine: (String) -> Unit,
  onAddCustomCuisine: () -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  val canonicalSet = remember { CuisineTradition.entries.map { it.canonicalTag }.toSet() }
  // Isolate user-authored custom cuisine tags so they render as removable InputChips.
  val customTags = remember(preferredCuisines) {
    preferredCuisines.filter { tag -> CuisineTradition.fromTagOrNull(tag) == null && tag !in canonicalSet }
  }

  Surface(
    shape = RoundedCornerShape(18.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Public, contentDescription = null, tint = extendedColors.herbalSage, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
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
          val isSelected = preferredCuisines.any { CuisineTradition.fromTagOrNull(it) == tradition }
          FilterChip(
            selected = isSelected,
            onClick = { onToggleCuisine(tradition.canonicalTag) },
            colors = FilterChipDefaults.filterChipColors(
              selectedContainerColor = extendedColors.herbalSage,
              selectedLabelColor = Color.White,
            ),
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            label = { Text(tradition.chipLabel, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false) },
          )
        }
        customTags.forEach { customTag ->
          InputChip(
            selected = true,
            onClick = { onToggleCuisine(customTag) },
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            label = { Text(customTag, style = MaterialTheme.typography.labelMedium) },
            trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove $customTag", modifier = Modifier.size(14.dp)) },
          )
        }
      }

      // Inline custom cuisine input + 48.dp Add Cuisine button.
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OutlinedTextField(
          value = customInput,
          onValueChange = onCustomInputChange,
          label = { Text("Custom Cuisine", maxLines = 1, overflow = TextOverflow.Ellipsis) },
          placeholder = { Text("Lebanese or Ethiopian", maxLines = 1, overflow = TextOverflow.Ellipsis) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = { onAddCustomCuisine() }),
          modifier = Modifier.weight(1f),
        )
        Button(
          onClick = onAddCustomCuisine,
          enabled = profileSectionSanitizeLabel(customInput).isNotEmpty(),
          shape = CircleShape,
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        ) {
          Text("Add Cuisine", maxLines = 1, softWrap = false)
        }
      }
    }
  }
}

/**
 * Editable household member nutrition and child-stage safety card inside [FamilyProfileSheetContent].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProfileSheetMemberGoalCard(
  member: FamilyMemberGoalSpec,
  olderKidAgeYears: Int,
  youngestStage: ChildGrowthStage,
  onUpdateOlderKidAge: (Int) -> Unit,
  onUpdateYoungestStage: (ChildGrowthStage) -> Unit,
  onUpdateFocusGoal: (String) -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  var showPresets by rememberSaveable(member.memberId) { mutableStateOf(false) }

  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = KitchenTheme.shapes.card2xl,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(profileSectionSanitizeLabel(member.memberName), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        AssistChip(
          onClick = { showPresets = !showPresets },
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          label = { Text(if (showPresets) "Hide Presets" else "Presets", maxLines = 1, softWrap = false) },
        )
      }

      // School-age child stepper (`4..17` yrs) with 48.dp +/- buttons.
      if (member.memberId == "OLDER_KID") {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text("Age · 4 to 17 yrs", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalIconButton(onClick = { onUpdateOlderKidAge(olderKidAgeYears - 1) }, modifier = Modifier.size(48.dp)) {
              Icon(Icons.Filled.Remove, contentDescription = "Decrease child age", modifier = Modifier.size(18.dp))
            }
            Text("${olderKidAgeYears}yo", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            FilledTonalIconButton(onClick = { onUpdateOlderKidAge(olderKidAgeYears + 1) }, modifier = Modifier.size(48.dp)) {
              Icon(Icons.Filled.Add, contentDescription = "Increase child age", modifier = Modifier.size(18.dp))
            }
          }
        }
      }

      // EC-08 youngest child feeding & choking-safety stage selector.
      if (member.memberId == "YOUNGEST") {
        Text("Youngest Feeding Stage · Choking Safety", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          ChildGrowthStage.entries.forEach { stage ->
            FilterChip(
              selected = youngestStage == stage,
              onClick = { onUpdateYoungestStage(stage) },
              modifier = Modifier.defaultMinSize(minHeight = 48.dp),
              label = { Text(profileSectionFormatStageLabel(stage), style = MaterialTheme.typography.labelSmall) },
            )
          }
        }
        Surface(shape = RoundedCornerShape(10.dp), color = extendedColors.childSafetyContainer, border = BorderStroke(1.dp, extendedColors.childSafetyBorder)) {
          Text(profileSectionSanitizeLabel(youngestStage.prepGuidance), style = MaterialTheme.typography.bodySmall, color = extendedColors.onChildSafety, modifier = Modifier.padding(8.dp))
        }
      }

      // Expandable dietary focus preset chips for 1-tap household nutrition tuning.
      if (showPresets) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          PROFILE_SECTION_FOCUS_PRESETS.forEach { presetFocus ->
            FilterChip(
              selected = member.focusGoal == presetFocus,
              onClick = { onUpdateFocusGoal(presetFocus) },
              modifier = Modifier.defaultMinSize(minHeight = 48.dp),
              label = { Text(presetFocus, style = MaterialTheme.typography.labelSmall) },
            )
          }
        }
      }

      // Free-form per-member dietary & plating goal input consumed by buildFamilyGoalPlatingGuide.
      OutlinedTextField(
        value = member.focusGoal,
        onValueChange = onUpdateFocusGoal,
        label = { Text("Dietary and Plating Focus") },
        maxLines = 2,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

/**
 * Life and health event toggle card scaling effective household servings and safety guardrails.
 */
@Composable
internal fun ProfileSheetLifeEventCard(
  preset: HouseholdLifeEventPreset,
  isEnabled: Boolean,
  onToggle: (Boolean) -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  val cleanBadge = profileSectionFormatLifeEventLabel(preset)
  // Singularize serving noun cleanly when servingsDelta == 1.
  val scaleNoun = if (preset.servingsDelta == 1) "serving" else "servings"
  val scaleNote = if (preset.servingsDelta > 0) {
    "+${preset.servingsDelta} $scaleNoun · ${preset.memberId}"
  } else {
    "${preset.memberId} · +0 servings"
  }

  // Highlight active life-event cards in Herbal Sage container with 1.dp Sage border.
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = KitchenTheme.shapes.card2xl,
    colors = CardDefaults.cardColors(containerColor = if (isEnabled) extendedColors.sageContainer else MaterialTheme.colorScheme.surfaceContainerLowest),
    border = BorderStroke(1.dp, if (isEnabled) extendedColors.herbalSage else extendedColors.borderSubtle),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
          Text(cleanBadge, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
          Text(scaleNote, style = MaterialTheme.typography.labelSmall, color = extendedColors.herbalSage)
        }
        // Toggle switch activating or clearing the life-event preset.
        Switch(checked = isEnabled, onCheckedChange = onToggle)
      }
      // Priority nutrient tags sanitized and joined with middle dots.
      Text(
        text = "Nutrients: ${preset.priorityNutrients.joinToString(" · ") { profileSectionSanitizeLabel(it) }}",
        style = MaterialTheme.typography.bodySmall,
      )
      // Food-safety preparation guardrails (e.g., pregnancy pasteurization / full cook temps).
      preset.safetyPrepRules.forEach { safetyRule ->
        Text(
          text = "Safety guardrail: ${profileSectionSanitizeLabel(safetyRule)}",
          style = MaterialTheme.typography.labelSmall,
          color = extendedColors.terracotta,
        )
      }
    }
  }
}

// Formats ConsumerHouseholdTemplate chip labels without slashes or parentheses.
internal fun profileSectionFormatTemplateLabel(template: ConsumerHouseholdTemplate): String =
  when (template) {
    ConsumerHouseholdTemplate.FAMILY_TWO_KIDS -> "Family of 4 · 2 Parents and 2 Kids"
    ConsumerHouseholdTemplate.PARENTS_AND_BABY -> "Young Family · 2 Parents and Baby"
    ConsumerHouseholdTemplate.ACTIVE_COUPLE -> "Active Couple · 2 Adults"
    ConsumerHouseholdTemplate.SOLO_COOK -> "Solo Cook · 1 Adult and Leftovers"
    ConsumerHouseholdTemplate.LARGE_MULTIGEN_FAMILY -> "Large Multi-Gen Family · 6 Servings"
  }

// Formats HouseholdCurrency chip labels without slashes or parentheses.
internal fun profileSectionFormatCurrencyLabel(currency: HouseholdCurrency): String =
  when (currency) {
    HouseholdCurrency.PHP -> "PHP · Philippine Peso ${currency.symbol}"
    HouseholdCurrency.USD -> "USD · US Dollar ${currency.symbol}"
    HouseholdCurrency.EUR -> "EUR · Euro ${currency.symbol}"
    HouseholdCurrency.GBP -> "GBP · British Pound ${currency.symbol}"
    HouseholdCurrency.XOF -> "XOF · West African CFA Franc"
  }

// Formats ChildGrowthStage chip labels cleanly while keeping domain ChildGrowthStage.shortLabel intact.
internal fun profileSectionFormatStageLabel(stage: ChildGrowthStage): String =
  when (stage) {
    ChildGrowthStage.BABY_6_12M -> "6-12m Baby · Purees"
    ChildGrowthStage.TODDLER_1_2Y -> "1-2 yr Toddler · Soft Strips"
    ChildGrowthStage.PRESCHOOL_3_4Y -> "3-4 yr Preschooler · Bite-Sized"
    ChildGrowthStage.KIDS_5_PLUS -> "5+ yr Kid · Full Portions"
  }

// Formats HouseholdLifeEventPreset titles cleanly without slashes or parentheses.
internal fun profileSectionFormatLifeEventLabel(preset: HouseholdLifeEventPreset): String =
  when (preset) {
    HouseholdLifeEventPreset.PREGNANCY_TRIMESTER_1 -> "Pregnancy · 1st Trimester"
    HouseholdLifeEventPreset.PREGNANCY_TRIMESTER_2_3 -> "Pregnancy · 2nd and 3rd Trimester"
    HouseholdLifeEventPreset.POSTPARTUM_BREASTFEEDING -> "Postpartum and Breastfeeding · +1 Serving"
    HouseholdLifeEventPreset.HIGH_IRON_IMMUNITY -> "High-Iron and Immunity Boost"
    HouseholdLifeEventPreset.VISITING_FAMILY_GUESTS -> "Visiting Family and Guests · +2 Servings"
  }

// Strips slashes and parentheses from user-entered or preset labels for clean UI rendering.
internal fun profileSectionSanitizeLabel(raw: String): String =
  raw
    .replace(" / ", " and ")
    .replace("/", " · ")
    .replace(" (", " · ")
    .replace("(", "")
    .replace(")", "")
    .replace(Regex("\\s+"), " ")
    .trim()
