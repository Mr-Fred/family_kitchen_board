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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
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
import com.familykitchen.board.domain.FamilyMemberGoalSpec
import com.familykitchen.board.domain.HouseholdCurrency
import com.familykitchen.board.domain.HouseholdLifeEvent
import com.familykitchen.board.domain.HouseholdLifeEventPreset
import com.familykitchen.board.domain.HouseholdProfile
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme

// Family Dietary, Consumer Household Template, Counter Display Preferences, & Growth Profile
// modal bottom sheet with 48.dp+ steppers and compact 360dp 1.35x font-scale resilience.

// Canonical allergen exclusions enforced across AvailabilityFilter and GroceryDiffEngine (EC-02).
private val HOUSEHOLD_ALLERGEN_OPTIONS: List<String> =
  listOf("EGGS", "DAIRY", "GLUTEN", "NUTS", "PEANUTS", "FISH")

// Presets for 1-pot member plating goals (`Fred`, `Wife`, `8yo`, `Youngest`).
private val DIETARY_FOCUS_PRESETS: List<String> = listOf(
  "Muscle Building (Hypertrophy) & <=25m Morning Cap",
  "Lean Weight-Loss, High Satiety & Double Greens",
  "Kids Brain & Muscle Development (Choline, DHA & Protein)",
  "Youngest Brain & Muscle Growth + Safe Textures",
  "High-Iron + Vitamin C Synergy (2x Iron Absorption)",
  "Athletic Post-Workout Glycogen & Lean Protein",
  "Steady Classroom Focus & Low Glycemic Grains",
  "Heart-Healthy Omega-3 & Mediterranean Greens",
)

// Quick-add Antipolo & Marikina palengke + supermarket discovery staples boosted in Sous-Chef proposals.
private val QUICK_DISCOVERY_ITEMS: List<String> = listOf(
  "Malunggay (Moringa Leaves)", "Yellow Kamote (Sweet Potato)", "Ripe Saba Bananas",
  "Carabao Mango", "Boneless Bangus (Milkfish)", "Firm Tokwa (Tofu)", "Fresh Calamansi",
)

// Household-wide culinary tags steering seasoning, sugar limits, and vegetable ratios.
private val HOUSEHOLD_PREFERENCE_PRESETS: List<String> = listOf(
  "Kids Brain Boosters", "No Added Sugar", "Mild Spice for Kids", "Double Leafy Greens", "Low Sodium",
)

/**
 * Material 3 [ModalBottomSheet] wrapper hosting [FamilyProfileSheetContent], including optional
 * `Counter Dark Mode` and `Wide Counter Layout` toggles relocated from the top app bar.
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
  modifier: Modifier = Modifier,
) {
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
    )
  }
}

/**
 * Stateless inner content of [FamilyProfileSheet], extracted for clean Compose `@Preview` rendering.
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
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Persist enum selections as Bundle-safe Strings across configuration changes.
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
  var customDiscoveryInput by rememberSaveable { mutableStateOf("") }

  // Seed editable member cards and life-event sets from persisted profile.
  var memberGoals by remember(householdProfile.members) {
    mutableStateOf(householdProfile.members.ifEmpty { HouseholdProfile.defaultFamilyMembers() })
  }
  var activeEventIds by remember(householdProfile.activeLifeEvents) {
    mutableStateOf(householdProfile.activeLifeEvents.map { it.eventId }.toSet())
  }
  var discoveredItems by remember(householdProfile.discoveredIngredients) { mutableStateOf(householdProfile.discoveredIngredients) }
  var selectedAllergens by remember(householdProfile.householdAllergens) {
    mutableStateOf(householdProfile.householdAllergens.map { it.uppercase() }.toSet())
  }
  var selectedPreferences by remember(householdProfile.dietaryPreferences) { mutableStateOf(householdProfile.dietaryPreferences.toSet()) }

  // Compute live serving-scale impact (+1 postpartum, +2 visiting guests) across active presets.
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
    // Header row displaying live effective household serving count and 48.dp close icon.
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.FamilyRestroom, contentDescription = null, tint = extendedColors.herbalSage)
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text("Family & Kitchen Profile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
          Text("Scale: $effectiveServings servings/meal", style = MaterialTheme.typography.bodySmall, color = extendedColors.herbalSage)
        }
      }
      IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Filled.Close, contentDescription = "Close Family Profile sheet")
      }
    }

    // Counter Display Mode toggles (`Counter Dark Mode` & `Wide Counter Layout`).
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = KitchenTheme.shapes.card2xl,
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
      border = BorderStroke(1.dp, extendedColors.borderSubtle),
    ) {
      // Relocated from top app bar so SavorTopAppBar stays decluttered (`<= 3` actions).
      Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        DisplayPreferenceToggleRow("Counter Dark Mode", "Low-glare warm OLED surface for evening prep", isDarkCounterTheme, onToggleDarkTheme)
        HorizontalDivider(color = extendedColors.borderSubtle)
        DisplayPreferenceToggleRow("Wide Counter Layout", "3-column stand layout with side navigation rail", isWideCounterLayout, onToggleWideLayout)
        HorizontalDivider(color = extendedColors.borderSubtle)
        // Re-open the 4-step First-Run Setup Wizard anytime to adjust household or starter pantry.
        OutlinedButton(
          onClick = onRelaunchOnboarding,
          shape = CircleShape,
          modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).defaultMinSize(minHeight = 48.dp),
        ) {
          Icon(Icons.Filled.Restaurant, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Run Guided First-Run Setup", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
      }
    }

    // Section 0: Consumer Household Template Selector & Uncramped Cook Inputs.
    Text("Household Template & Cook Names", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      ConsumerHouseholdTemplate.entries.forEach { template ->
        FilterChip(
          selected = selectedTemplate == template,
          onClick = {
            // Rebuild default member specs and base servings deterministically for the chosen template.
            selectedTemplateName = template.name
            val rebuilt = HouseholdProfile.fromConsumerTemplate(
              template = template,
              householdName = householdName.ifBlank { "Our Family Kitchen" },
              morningLeadName = morningLeadName.ifBlank { "Fred" },
              eveningLeadName = eveningLeadName.ifBlank { "Wife" },
              currency = selectedCurrency,
              marketRegionLabel = marketRegionLabel.ifBlank { "Antipolo & Marikina, PH" },
            )
            baseServings = rebuilt.baseHouseholdServings
            morningCapMinutes = rebuilt.fredMorningCapMinutes
            youngestStageName = rebuilt.youngestStage.name
            memberGoals = rebuilt.members
            onSelectConsumerTemplate(template)
          },
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          label = { Text(template.displayLabel, style = MaterialTheme.typography.labelSmall) },
        )
      }
    }

    // Full-width Kitchen Name + 2-column Morning / Evening Lead prevents label crushing on 360dp.
    OutlinedTextField(
      value = householdName,
      onValueChange = { householdName = it },
      label = { Text("Kitchen Name", maxLines = 1, overflow = TextOverflow.Ellipsis) },
      singleLine = true,
      keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
      modifier = Modifier.fillMaxWidth(),
    )
    // Two-column row gives Morning Lead and Evening Lead ~168dp each on a compact 360dp viewport.
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

    // Household Currency (`PHP` ₱ default + `USD`, `EUR`, `GBP`, `XOF`) & Local Market Region selector.
    Text("Market Region & Currency (${selectedCurrency.formatAmount(1120.0)})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      HouseholdCurrency.entries.forEach { currencyOption ->
        FilterChip(
          selected = selectedCurrency == currencyOption,
          onClick = {
            // Apply currency symbol immediately across Pantry, Grocery, and Sous-Chef cards.
            selectedCurrencyName = currencyOption.name
            onSelectCurrencyAndRegion(currencyOption, marketRegionLabel.ifBlank { "Antipolo & Marikina, PH" })
          },
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          label = { Text(currencyOption.displayLabel, style = MaterialTheme.typography.labelSmall) },
        )
      }
    }
    OutlinedTextField(
      value = marketRegionLabel,
      onValueChange = { marketRegionLabel = it },
      label = { Text("Local Market & Area") },
      placeholder = { Text("Antipolo & Marikina, PH") },
      singleLine = true,
      keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
      modifier = Modifier.fillMaxWidth(),
    )

    HorizontalDivider(color = extendedColors.borderSubtle)

    // Section 1: Editable Member Goal Cards with 48.dp x 48.dp age steppers.
    Text("1. Member Nutrition & Growth Goals (${memberGoals.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    memberGoals.forEach { member ->
      MemberGoalEditorCard(
        member = member,
        olderKidAgeYears = olderKidAge,
        youngestStage = youngestStage,
        onUpdateOlderKidAge = { nextAge ->
          // Clamp school-age child years between 4 and 17 and update member display label.
          val clamped = nextAge.coerceIn(4, 17)
          olderKidAge = clamped
          memberGoals = memberGoals.map { m -> if (m.memberId == "OLDER_KID") m.copy(memberName = "${clamped}yo School Kid", ageYears = clamped) else m }
        },
        onUpdateYoungestStage = { nextStage ->
          // Synchronize youngest growth stage so EC-08 choking-safety tips adapt immediately.
          youngestStageName = nextStage.name
          memberGoals = memberGoals.map { m -> if (m.memberId == "YOUNGEST") m.copy(growthStage = nextStage) else m }
        },
        onUpdateFocusGoal = { nextGoal ->
          memberGoals = memberGoals.map { m -> if (m.memberId == member.memberId) m.copy(focusGoal = nextGoal) else m }
        },
      )
    }

    HorizontalDivider(color = extendedColors.borderSubtle)

    // Section 2: 5 Major Life & Health Event Triggers showing serving-scale & food-safety notes.
    Text("2. Major Life & Health Event Triggers", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    HouseholdLifeEventPreset.entries.forEach { preset ->
      val isEnabled = preset.eventId in activeEventIds
      LifeEventPresetToggleCard(
        preset = preset,
        isEnabled = isEnabled,
        onToggle = { checked ->
          activeEventIds = if (checked) activeEventIds + preset.eventId else activeEventIds - preset.eventId
          // Auto-apply pregnancy/postpartum focus goal onto WIFE when toggled on.
          if (checked && preset.memberFocusOverride.isNotBlank() && preset.memberId == "WIFE") {
            memberGoals = memberGoals.map { m -> if (m.memberId == "WIFE") m.copy(focusGoal = preset.memberFocusOverride) else m }
          }
        },
      )
    }

    HorizontalDivider(color = extendedColors.borderSubtle)

    // Section 3: Newly Discovered & Loved Ingredients chips.
    Text("3. Loved Local Ingredients", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
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
    // 1-tap Antipolo & Marikina palengke discovery suggestions (Malunggay, Kamote, Bangus, Tokwa).
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      QUICK_DISCOVERY_ITEMS.forEach { suggestion ->
        AssistChip(
          onClick = { if (suggestion !in discoveredItems) discoveredItems = discoveredItems + suggestion },
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          label = { Text("+ $suggestion", style = MaterialTheme.typography.labelMedium) },
        )
      }
    }

    // Deduplicate custom discovery entries case-sensitively before appending.
    val addDiscovery = {
      val clean = customDiscoveryInput.trim()
      if (clean.isNotEmpty() && clean !in discoveredItems) {
        discoveredItems = discoveredItems + clean
        customDiscoveryInput = ""
      }
    }
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

    // Section 4: Household Allergen Avoidance toggles (`EGGS`, `DAIRY`, `GLUTEN`, `NUTS`, `PEANUTS`, `FISH`).
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.Filled.Shield, contentDescription = null, tint = extendedColors.terracotta)
      Spacer(modifier = Modifier.width(6.dp))
      Text("4. Allergen Avoidances (EC-02 / EC-03)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
    // Toggling an allergen immediately filters or rewrites recipes across Plan, Pantry, & Reader.
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
    // Household seasoning, sugar, and greens preference tags.
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

    // Commit updated HouseholdProfile and recommended life-event staples in 1 O(1) snapshot write.
    Button(
      onClick = {
        val resolvedRegion = marketRegionLabel.trim().ifEmpty { "Antipolo & Marikina, PH" }
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
          currency = selectedCurrency,
          marketRegionLabel = resolvedRegion,
        )
        onSelectCurrencyAndRegion(selectedCurrency, resolvedRegion)
        // Collect recommended folate/iron/DHA catalog IDs from active life-event triggers.
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
  }
}

@Composable
private fun DisplayPreferenceToggleRow(
  title: String,
  subtitle: String,
  checked: Boolean,
  onToggle: () -> Unit,
) {
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemberGoalEditorCard(
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
        Text(member.memberName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        AssistChip(
          onClick = { showPresets = !showPresets },
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          label = { Text(if (showPresets) "Hide Presets" else "Presets", maxLines = 1, softWrap = false) },
        )
      }

      // Weighted label + 48.dp x 48.dp age steppers prevent right-edge "+" button crush on 360dp.
      if (member.memberId == "OLDER_KID") {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text("Age (4–17 yrs)", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
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

      // Youngest child developmental stage selector driving EC-08 Honey-Amber choking-safety callouts.
      if (member.memberId == "YOUNGEST") {
        Text("Youngest Stage (EC-08 Choking Safety):", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          ChildGrowthStage.entries.forEach { stage ->
            FilterChip(
              selected = youngestStage == stage,
              onClick = { onUpdateYoungestStage(stage) },
              modifier = Modifier.defaultMinSize(minHeight = 48.dp),
              label = { Text(stage.displayLabel, style = MaterialTheme.typography.labelSmall) },
            )
          }
        }
        Surface(shape = RoundedCornerShape(10.dp), color = extendedColors.childSafetyContainer, border = BorderStroke(1.dp, extendedColors.childSafetyBorder)) {
          Text(youngestStage.prepGuidance, style = MaterialTheme.typography.bodySmall, color = extendedColors.onChildSafety, modifier = Modifier.padding(8.dp))
        }
      }

      if (showPresets) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          DIETARY_FOCUS_PRESETS.forEach { presetFocus ->
            FilterChip(
              selected = member.focusGoal == presetFocus,
              onClick = { onUpdateFocusGoal(presetFocus) },
              modifier = Modifier.defaultMinSize(minHeight = 48.dp),
              label = { Text(presetFocus, style = MaterialTheme.typography.labelSmall) },
            )
          }
        }
      }

      // Allow up to 2 lines so "Hypertrophy", "High Satiety", and "Development" never clip mid-word.
      OutlinedTextField(
        value = member.focusGoal,
        onValueChange = onUpdateFocusGoal,
        label = { Text("Dietary & Plating Focus") },
        maxLines = 2,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

@Composable
private fun LifeEventPresetToggleCard(
  preset: HouseholdLifeEventPreset,
  isEnabled: Boolean,
  onToggle: (Boolean) -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Display serving-scale impact and mandatory food-safety temperatures (firm yolks, 74C deli, 63C fish).
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
          Text(preset.badgeLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
          val scaleNote = if (preset.servingsDelta > 0) "+${preset.servingsDelta} serving(s) · ${preset.memberId}" else "${preset.memberId} · +0 servings"
          Text(scaleNote, style = MaterialTheme.typography.labelSmall, color = extendedColors.herbalSage)
        }
        Switch(checked = isEnabled, onCheckedChange = onToggle)
      }
      Text("Nutrients: ${preset.priorityNutrients.joinToString(" • ")}", style = MaterialTheme.typography.bodySmall)
      preset.safetyPrepRules.forEach { safetyRule ->
        Text("Safety guardrail: $safetyRule", style = MaterialTheme.typography.labelSmall, color = extendedColors.terracotta)
      }
    }
  }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 960)
@Composable
private fun FamilyProfileSheetPreview() {
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
