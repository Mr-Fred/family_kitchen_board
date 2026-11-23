package com.familykitchen.board.ui.sheets

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.ChildGrowthStage
import com.familykitchen.board.domain.ConsumerHouseholdTemplate
import com.familykitchen.board.domain.DEFAULT_PREFERRED_CUISINES
import com.familykitchen.board.domain.HouseholdCurrency
import com.familykitchen.board.domain.HouseholdLifeEvent
import com.familykitchen.board.domain.HouseholdLifeEventPreset
import com.familykitchen.board.domain.HouseholdProfile
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme

// Family Dietary, Preferred Family Cuisines, Consumer Household Template, Counter Display Modes,
// Growth Profile, and Developer QA Lab modal bottom sheet with zero slashes or parentheses.

// Six major allergen codes surfaced as 48.dp exclusion chips in FamilyProfileSheet.
private val HOUSEHOLD_ALLERGEN_OPTIONS: List<String> =
  listOf("EGGS", "DAIRY", "GLUTEN", "NUTS", "PEANUTS", "FISH")

// Regional market staples offered as 1-tap Loved Local Ingredient suggestions.
private val QUICK_DISCOVERY_ITEMS: List<String> = listOf(
  "Malunggay Moringa Leaves",
  "Yellow Kamote Sweet Potato",
  "Ripe Saba Bananas",
  "Carabao Mango",
  "Boneless Bangus Milkfish",
  "Firm Tokwa Tofu",
  "Fresh Calamansi",
)

// Household-wide culinary guardrails toggled alongside allergen exclusions.
private val HOUSEHOLD_PREFERENCE_PRESETS: List<String> = listOf(
  "Kids Brain Boosters",
  "No Added Sugar",
  "Mild Spice for Kids",
  "Double Leafy Greens",
  "Low Sodium",
)

/**
 * Material 3 [ModalBottomSheet] wrapper hosting [FamilyProfileSheetContent], including
 * `Preferred Family Cuisines`, display preferences, and bottom `Open QA Scenario Lab` utility.
 *
 * @param householdProfile Active persisted [HouseholdProfile].
 * @param onSaveProfile Commits the updated [HouseholdProfile] and optional recommended grocery IDs.
 * @param onSelectConsumerTemplate Callback applying a [ConsumerHouseholdTemplate] preset.
 * @param onSelectCurrencyAndRegion Callback syncing [HouseholdCurrency] and market region label.
 * @param onDismiss Closes the modal bottom sheet.
 * @param isDarkCounterTheme Current Ambient Counter Dark mode toggle state.
 * @param isWideCounterLayout Current 3-column wide counter stand mode toggle state.
 * @param onToggleDarkTheme Toggles Ambient Counter Dark mode.
 * @param onToggleWideLayout Toggles 3-column wide counter layout.
 * @param onRelaunchOnboarding Relaunches the 4-step guided First-Run Setup wizard.
 * @param onOpenQaLab Opens `QaScenarioLabSheet` from the bottom developer utility section.
 * @param modifier Optional Compose [Modifier].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyProfileSheet(
  householdProfile: HouseholdProfile,
  onSaveProfile: (
    updatedProfile: HouseholdProfile,
    recommendedGroceryIdsToQueue: Set<String>,
  ) -> Unit,
  onSelectConsumerTemplate: (ConsumerHouseholdTemplate) -> Unit = {},
  onSelectCurrencyAndRegion: (HouseholdCurrency, String) -> Unit = { _, _ -> },
  onDismiss: () -> Unit = {},
  isDarkCounterTheme: Boolean = false,
  isWideCounterLayout: Boolean = false,
  onToggleDarkTheme: () -> Unit = {},
  onToggleWideLayout: () -> Unit = {},
  onRelaunchOnboarding: () -> Unit = {},
  onOpenQaLab: () -> Unit = {},
  isBackupBusy: Boolean = false,
  onSaveKitchenBackup: () -> Unit = {},
  onRestoreKitchenBackup: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  // Expand immediately so Preferred Family Cuisines and display toggles appear without extra drag.
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.background,
    modifier = modifier,
  ) {
    FamilyProfileSheetContent(
      householdProfile = householdProfile,
      onSaveProfile = onSaveProfile,
      onSelectConsumerTemplate = onSelectConsumerTemplate,
      onSelectCurrencyAndRegion = onSelectCurrencyAndRegion,
      onDismiss = onDismiss,
      isDarkCounterTheme = isDarkCounterTheme,
      isWideCounterLayout = isWideCounterLayout,
      onToggleDarkTheme = onToggleDarkTheme,
      onToggleWideLayout = onToggleWideLayout,
      onRelaunchOnboarding = onRelaunchOnboarding,
      onOpenQaLab = {
        onDismiss()
        onOpenQaLab()
      },
      isBackupBusy = isBackupBusy,
      onSaveKitchenBackup = onSaveKitchenBackup,
      onRestoreKitchenBackup = onRestoreKitchenBackup,
    )
  }
}

/**
 * Stateless inner content of [FamilyProfileSheet], extracted for clean Compose `@Preview` rendering.
 *
 * @param householdProfile Active persisted [HouseholdProfile].
 * @param onSaveProfile Commits the updated [HouseholdProfile] and optional recommended grocery IDs.
 * @param onSelectConsumerTemplate Callback applying a [ConsumerHouseholdTemplate] preset.
 * @param onSelectCurrencyAndRegion Callback syncing [HouseholdCurrency] and market region label.
 * @param onDismiss Closes the modal bottom sheet.
 * @param isDarkCounterTheme Current Ambient Counter Dark mode toggle state.
 * @param isWideCounterLayout Current 3-column wide counter stand mode toggle state.
 * @param onToggleDarkTheme Toggles Ambient Counter Dark mode.
 * @param onToggleWideLayout Toggles 3-column wide counter layout.
 * @param onRelaunchOnboarding Relaunches the 4-step guided First-Run Setup wizard.
 * @param onOpenQaLab Opens `QaScenarioLabSheet` from the bottom developer utility section.
 * @param modifier Optional Compose [Modifier].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FamilyProfileSheetContent(
  householdProfile: HouseholdProfile,
  onSaveProfile: (
    updatedProfile: HouseholdProfile,
    recommendedGroceryIdsToQueue: Set<String>,
  ) -> Unit,
  onSelectConsumerTemplate: (ConsumerHouseholdTemplate) -> Unit = {},
  onSelectCurrencyAndRegion: (HouseholdCurrency, String) -> Unit = { _, _ -> },
  onDismiss: () -> Unit = {},
  isDarkCounterTheme: Boolean = false,
  isWideCounterLayout: Boolean = false,
  onToggleDarkTheme: () -> Unit = {},
  onToggleWideLayout: () -> Unit = {},
  onRelaunchOnboarding: () -> Unit = {},
  onOpenQaLab: () -> Unit = {},
  isBackupBusy: Boolean = false,
  onSaveKitchenBackup: () -> Unit = {},
  onRestoreKitchenBackup: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Persist primitive Bundle-safe form fields across rotation and IME visibility changes.
  var selectedTemplateName by rememberSaveable { mutableStateOf(householdProfile.activeTemplate.name) }
  val selectedTemplate = ConsumerHouseholdTemplate.entries.find { it.name == selectedTemplateName } ?: householdProfile.activeTemplate
  var selectedCurrencyName by rememberSaveable { mutableStateOf(householdProfile.currency.name) }
  val selectedCurrency = HouseholdCurrency.entries.find { it.name == selectedCurrencyName } ?: householdProfile.currency
  var marketRegionLabel by rememberSaveable { mutableStateOf(householdProfile.marketRegionLabel) }
  var householdName by rememberSaveable { mutableStateOf(householdProfile.householdName) }
  var morningLeadName by rememberSaveable { mutableStateOf(householdProfile.morningLeadName) }
  var eveningLeadName by rememberSaveable { mutableStateOf(householdProfile.eveningLeadName) }
  var baseServings by rememberSaveable { mutableIntStateOf(householdProfile.baseHouseholdServings) }
  var morningCapMinutes by rememberSaveable { mutableIntStateOf(householdProfile.fredMorningCapMinutes) }
  var olderKidAge by rememberSaveable { mutableIntStateOf(householdProfile.olderKidAgeYears.coerceIn(4, 17)) }
  var youngestStageName by rememberSaveable { mutableStateOf(householdProfile.youngestStage.name) }
  val youngestStage = ChildGrowthStage.entries.find { it.name == youngestStageName } ?: householdProfile.youngestStage
  var customCuisineInput by rememberSaveable { mutableStateOf("") }
  var customDiscoveryInput by rememberSaveable { mutableStateOf("") }

  // Normalize incoming preferredCuisines so legacy slashes/parentheses never appear on chips.
  var preferredCuisines by remember(householdProfile.preferredCuisines) {
    mutableStateOf(
      householdProfile.preferredCuisines
        .map(::profileSectionSanitizeLabel)
        .filter { it.isNotEmpty() }
        .distinct()
        .ifEmpty { DEFAULT_PREFERRED_CUISINES },
    )
  }
  var memberGoals by remember(householdProfile.members) {
    mutableStateOf(householdProfile.members.ifEmpty { HouseholdProfile.defaultFamilyMembers() })
  }
  var activeEventIds by remember(householdProfile.activeLifeEvents) {
    mutableStateOf(householdProfile.activeLifeEvents.map { it.eventId }.toSet())
  }
  var discoveredItems by remember(householdProfile.discoveredIngredients) {
    mutableStateOf(householdProfile.discoveredIngredients.map(::profileSectionSanitizeLabel))
  }
  var selectedAllergens by remember(householdProfile.householdAllergens) {
    mutableStateOf(householdProfile.householdAllergens.map { it.uppercase() }.toSet())
  }
  var selectedPreferences by remember(householdProfile.dietaryPreferences) {
    mutableStateOf(householdProfile.dietaryPreferences.toSet())
  }

  // Merge preset life events with any custom Sous-Chef-authored life events cleanly.
  val activeLifeEvents: List<HouseholdLifeEvent> = remember(activeEventIds, householdProfile.activeLifeEvents) {
    val presetEvents = HouseholdLifeEventPreset.entries.filter { it.eventId in activeEventIds }.map { it.toLifeEvent() }
    val customPreserved = householdProfile.activeLifeEvents.filter { existing ->
      existing.eventId in activeEventIds && HouseholdLifeEventPreset.entries.none { p -> p.eventId == existing.eventId }
    }
    presetEvents + customPreserved
  }
  val effectiveServings = (baseServings + activeLifeEvents.sumOf { it.servingsDelta }).coerceAtLeast(1)

  Column(
    modifier = modifier
      .fillMaxWidth()
      .imePadding()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.FamilyRestroom, contentDescription = null, tint = extendedColors.herbalSage)
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text("Family and Kitchen Profile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
          Text("Scale · $effectiveServings servings per meal", style = MaterialTheme.typography.bodySmall, color = extendedColors.herbalSage)
        }
      }
      IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Filled.Close, contentDescription = "Close Family Profile sheet")
      }
    }

    // Counter Display Mode toggles + Guided First-Run Setup replay shortcut.
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = KitchenTheme.shapes.card2xl,
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
      border = BorderStroke(1.dp, extendedColors.borderSubtle),
    ) {
      Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        // Warm OLED ambient counter dark toggle for evening kitchen prep.
        profileSheetDisplayToggleRow("Counter Dark Mode", "Low-glare warm OLED surface for evening prep", isDarkCounterTheme, onToggleDarkTheme)
        HorizontalDivider(color = extendedColors.borderSubtle)
        // Wide counter 3-column stand layout toggle with side NavigationRail.
        profileSheetDisplayToggleRow("Wide Counter Layout", "3-column stand layout with side navigation rail", isWideCounterLayout, onToggleWideLayout)
        HorizontalDivider(color = extendedColors.borderSubtle)
        // 48.dp replay button opening the 4-step guided First-Run Onboarding flow.
        OutlinedButton(
          onClick = onRelaunchOnboarding,
          shape = CircleShape,
          modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).defaultMinSize(minHeight = 48.dp),
        ) {
          Icon(Icons.Filled.Restaurant, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Run Guided First-Run Setup", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider(color = extendedColors.borderSubtle)
        // Portable zero-permission JSON backup and restore actions surviving clean reinstalls.
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Kitchen Data Backup", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
          Text(
            text = "Keep your cuisines, pantry, recipes, and meal plans safe across reinstalls",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
              onClick = onSaveKitchenBackup,
              enabled = !isBackupBusy,
              shape = CircleShape,
              modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
            ) {
              Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("Save Backup", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(
              onClick = onRestoreKitchenBackup,
              enabled = !isBackupBusy,
              shape = CircleShape,
              modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
            ) {
              Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("Restore Backup", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
          }
        }
      }
    }

    // Prominent Preferred Family Cuisines section (`CuisineTradition.entries` + inline Add Cuisine).
    ProfileSheetPreferredCuisinesSection(
      preferredCuisines = preferredCuisines,
      customInput = customCuisineInput,
      onCustomInputChange = { customCuisineInput = it },
      onToggleCuisine = { tag ->
        preferredCuisines = if (tag in preferredCuisines) {
          preferredCuisines - tag
        } else {
          preferredCuisines + tag
        }
      },
      onAddCustomCuisine = {
        val clean = profileSectionSanitizeLabel(customCuisineInput)
        if (clean.isNotEmpty() && preferredCuisines.none { it.equals(clean, ignoreCase = true) }) {
          preferredCuisines = preferredCuisines + clean
          customCuisineInput = ""
        }
      },
    )

    HorizontalDivider(color = extendedColors.borderSubtle)

    // Consumer Household Template Selector & Cook Inputs.
    Text("Household Template and Cook Names", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    // Multi-template chips rebuilding serving counts, morning caps, and child safety stages.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      ConsumerHouseholdTemplate.entries.forEach { template ->
        FilterChip(
          selected = selectedTemplate == template,
          onClick = {
            selectedTemplateName = template.name
            // Rebuild default member goals while preserving household name, currency, and cuisines.
            val rebuilt = HouseholdProfile.fromConsumerTemplate(
              template = template,
              householdName = householdName.ifBlank { "Our Family Kitchen" },
              morningLeadName = morningLeadName.ifBlank { "Fred" },
              eveningLeadName = eveningLeadName.ifBlank { "Wife" },
              currency = selectedCurrency,
              marketRegionLabel = marketRegionLabel.ifBlank { "Antipolo & Marikina, PH" },
              preferredCuisines = preferredCuisines.ifEmpty { DEFAULT_PREFERRED_CUISINES },
            )
            baseServings = rebuilt.baseHouseholdServings
            morningCapMinutes = rebuilt.fredMorningCapMinutes
            youngestStageName = rebuilt.youngestStage.name
            memberGoals = rebuilt.members
            onSelectConsumerTemplate(template)
          },
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          label = { Text(profileSectionFormatTemplateLabel(template), style = MaterialTheme.typography.labelSmall) },
        )
      }
    }

    // Household Kitchen Name + Morning/Evening Lead Cook input fields.
    OutlinedTextField(
      value = householdName,
      onValueChange = { householdName = it },
      label = { Text("Kitchen Name", maxLines = 1, overflow = TextOverflow.Ellipsis) },
      singleLine = true,
      keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
      modifier = Modifier.fillMaxWidth(),
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      OutlinedTextField(
        value = morningLeadName,
        onValueChange = { morningLeadName = it },
        label = { Text("Morning Lead", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        modifier = Modifier.weight(1f),
      )
      OutlinedTextField(
        value = eveningLeadName,
        onValueChange = { eveningLeadName = it },
        label = { Text("Evening Lead", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        modifier = Modifier.weight(1f),
      )
    }

    // Currency and Local Market selector using punctuation-clean currency labels.
    Text("Market Region and Currency · ${selectedCurrency.formatAmount(1120.0)}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      HouseholdCurrency.entries.forEach { currencyOption ->
        FilterChip(
          selected = selectedCurrency == currencyOption,
          onClick = {
            selectedCurrencyName = currencyOption.name
            onSelectCurrencyAndRegion(currencyOption, marketRegionLabel.ifBlank { "Antipolo & Marikina, PH" })
          },
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          label = { Text(profileSectionFormatCurrencyLabel(currencyOption), style = MaterialTheme.typography.labelSmall) },
        )
      }
    }
    OutlinedTextField(
      value = marketRegionLabel,
      onValueChange = { marketRegionLabel = it },
      label = { Text("Local Market and Area") },
      placeholder = { Text("Antipolo & Marikina, PH") },
      singleLine = true,
      keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
      modifier = Modifier.fillMaxWidth(),
    )

    HorizontalDivider(color = extendedColors.borderSubtle)

    // Editable Member Goal Cards with 48.dp age steppers and EC-08 child safety stages.
    Text("Member Nutrition and Growth Goals · ${memberGoals.size}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    memberGoals.forEach { member ->
      ProfileSheetMemberGoalCard(
        member = member,
        olderKidAgeYears = olderKidAge,
        youngestStage = youngestStage,
        onUpdateOlderKidAge = { nextAge ->
          val clamped = nextAge.coerceIn(4, 17)
          olderKidAge = clamped
          memberGoals = memberGoals.map { m ->
            if (m.memberId == "OLDER_KID") m.copy(memberName = "${clamped}yo School Kid", ageYears = clamped) else m
          }
        },
        onUpdateYoungestStage = { nextStage ->
          youngestStageName = nextStage.name
          memberGoals = memberGoals.map { m ->
            if (m.memberId == "YOUNGEST") m.copy(growthStage = nextStage) else m
          }
        },
        onUpdateFocusGoal = { nextGoal ->
          memberGoals = memberGoals.map { m ->
            if (m.memberId == member.memberId) m.copy(focusGoal = nextGoal) else m
          }
        },
      )
    }

    HorizontalDivider(color = extendedColors.borderSubtle)

    // Major Life and Health Event Triggers scaling servings and food-safety guardrails.
    Text("Major Life and Health Event Triggers", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    HouseholdLifeEventPreset.entries.forEach { preset ->
      val isEnabled = preset.eventId in activeEventIds
      ProfileSheetLifeEventCard(
        preset = preset,
        isEnabled = isEnabled,
        onToggle = { checked ->
          activeEventIds = if (checked) activeEventIds + preset.eventId else activeEventIds - preset.eventId
          if (checked && preset.memberFocusOverride.isNotBlank() && preset.memberId == "WIFE") {
            memberGoals = memberGoals.map { m ->
              if (m.memberId == "WIFE") m.copy(focusGoal = preset.memberFocusOverride) else m
            }
          }
        },
      )
    }

    HorizontalDivider(color = extendedColors.borderSubtle)

    // Loved Local Ingredients chips + inline custom discovery entry.
    Text("Loved Local Ingredients", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    // Render active discovered local ingredients as 48.dp removable InputChips.
    if (discoveredItems.isNotEmpty()) {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        discoveredItems.forEach { itemLabel ->
          InputChip(
            selected = true,
            onClick = { discoveredItems = discoveredItems - itemLabel },
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            label = { Text(itemLabel) },
            trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove $itemLabel", modifier = Modifier.size(14.dp)) },
          )
        }
      }
    }
    // 1-tap regional wet-market discovery suggestions (Malunggay, Kamote, Bangus, Calamansi).
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      QUICK_DISCOVERY_ITEMS.forEach { suggestion ->
        AssistChip(
          onClick = { if (suggestion !in discoveredItems) discoveredItems = discoveredItems + suggestion },
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          label = { Text("+ $suggestion", style = MaterialTheme.typography.labelMedium) },
        )
      }
    }

    // Sanitize and append user-typed market discovery item without duplicates.
    val addDiscovery = {
      val clean = profileSectionSanitizeLabel(customDiscoveryInput)
      if (clean.isNotEmpty() && clean !in discoveredItems) {
        discoveredItems = discoveredItems + clean
        customDiscoveryInput = ""
      }
    }
    // Custom market discovery text field + 48.dp Add button.
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      OutlinedTextField(
        value = customDiscoveryInput,
        onValueChange = { customDiscoveryInput = it },
        label = { Text("Discovered Ingredient", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { addDiscovery() }),
        modifier = Modifier.weight(1f),
      )
      Button(
        onClick = addDiscovery,
        enabled = customDiscoveryInput.isNotBlank(),
        shape = CircleShape,
        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
      ) { Text("Add", maxLines = 1, softWrap = false) }
    }

    HorizontalDivider(color = extendedColors.borderSubtle)

    // Allergen Avoidances & Dietary Guardrail Chips.
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.Filled.Shield, contentDescription = null, tint = extendedColors.terracotta)
      Spacer(modifier = Modifier.width(6.dp))
      Text("Allergen Avoidances", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
    // Six major allergen chips immediately excluding conflicting dishes from Plan & Recipes.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      HOUSEHOLD_ALLERGEN_OPTIONS.forEach { allergen ->
        val isBlocked = allergen in selectedAllergens
        FilterChip(
          selected = isBlocked,
          onClick = { selectedAllergens = if (isBlocked) selectedAllergens - allergen else selectedAllergens + allergen },
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          label = { Text("Avoid $allergen", maxLines = 1, softWrap = false) },
        )
      }
    }
    // Household culinary preference chips (Mild Spice for Kids, Double Leafy Greens, etc.).
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      HOUSEHOLD_PREFERENCE_PRESETS.forEach { pref ->
        val active = pref in selectedPreferences
        FilterChip(
          selected = active,
          onClick = { selectedPreferences = if (active) selectedPreferences - pref else selectedPreferences + pref },
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          label = { Text(pref, style = MaterialTheme.typography.labelMedium) },
        )
      }
    }

    // Save Family Profile primary 52.dp CTA committing O(1) household profile update.
    Button(
      onClick = {
        // Fall back to default local market label when the text field was cleared.
        val resolvedRegion = marketRegionLabel.trim().ifEmpty { "Antipolo & Marikina, PH" }
        // Build immutable HouseholdProfile preserving preferredCuisines and safety stages.
        val updated = householdProfile.copy(
          youngestStage = youngestStage,
          olderKidAgeYears = olderKidAge,
          fredMorningCapMinutes = morningCapMinutes,
          baseHouseholdServings = baseServings,
          members = memberGoals,
          householdAllergens = selectedAllergens.toList(),
          dietaryPreferences = selectedPreferences.toList(),
          discoveredIngredients = discoveredItems,
          activeLifeEvents = activeLifeEvents,
          householdName = householdName.trim().ifEmpty { "Our Family Kitchen" },
          morningLeadName = morningLeadName.trim().ifEmpty { "Fred" },
          eveningLeadName = eveningLeadName.trim().ifEmpty { "Wife" },
          activeTemplate = selectedTemplate,
          preferredCuisines = preferredCuisines.ifEmpty { DEFAULT_PREFERRED_CUISINES },
          currency = selectedCurrency,
          marketRegionLabel = resolvedRegion,
        )
        // Synchronize currency + region and queue life-event nutrient staples in 1 batch.
        onSelectCurrencyAndRegion(selectedCurrency, resolvedRegion)
        val recommendedGroceryIds = activeLifeEvents.flatMap { it.recommendedIngredientIds }.toSet()
        onSaveProfile(updated, recommendedGroceryIds)
      },
      shape = CircleShape,
      modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp),
    ) {
      Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
      Spacer(modifier = Modifier.width(6.dp))
      Text("Save Family Profile", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
    }

    // Bottom Developer & Verification Utility card hosting `Open QA Scenario Lab`.
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
          text = "Developer and Scenario Verification",
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 48.dp utility button launching QaScenarioLabSheet (EC-01..EC-13 live simulator).
        OutlinedButton(
          onClick = onOpenQaLab,
          shape = CircleShape,
          border = BorderStroke(1.dp, extendedColors.borderSubtle),
          modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
        ) {
          Icon(Icons.Filled.Science, contentDescription = null, tint = extendedColors.herbalSage, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Open QA Scenario Lab", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
      }
    }
  }
}

@Composable
private fun profileSheetDisplayToggleRow(
  title: String,
  subtitle: String,
  checked: Boolean,
  onToggle: () -> Unit,
) {
  // Full-row >= 52.dp clickable surface simplifying Counter Dark & Wide Layout switches.
  Row(
    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).clickable(onClick = onToggle).padding(vertical = 6.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
      Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
      Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Switch(checked = checked, onCheckedChange = { onToggle() })
  }
}

/** Warm Daylight preview of `FamilyProfileSheetContent` with Preferred Family Cuisines & QA Lab. */
@Preview(name = "FamilyProfileSheet Daylight", showBackground = true, widthDp = 412, heightDp = 960)
@Composable
fun FamilyProfileSheetPreview() {
  // Verify Preferred Family Cuisines chips, pregnancy life-event card, and Open QA Scenario Lab.
  val sampleProfile = HouseholdProfile(
    activeLifeEvents = listOf(HouseholdLifeEventPreset.PREGNANCY_TRIMESTER_2_3.toLifeEvent()),
    householdAllergens = listOf("PEANUTS"),
  )
  FamilyKitchenBoardTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
      FamilyProfileSheetContent(householdProfile = sampleProfile, onSaveProfile = { _, _ -> })
    }
  }
}
