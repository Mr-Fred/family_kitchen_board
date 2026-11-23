package com.familykitchen.board.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.AiChefAction
import com.familykitchen.board.domain.AiExecutionMode
import com.familykitchen.board.domain.CloudLlmConfig
import com.familykitchen.board.domain.DEFAULT_ONBOARDING_STAPLE_PRESETS
import com.familykitchen.board.domain.DEFAULT_SELECTED_BASIC_INGREDIENT_IDS
import com.familykitchen.board.domain.EdgeDeviceProfile
import com.familykitchen.board.domain.FirstRunOnboardingDraft
import com.familykitchen.board.domain.StartingPantryMode
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ui.aichef.AiModeSegmentedToggle
import com.familykitchen.board.ui.aichef.CloudApiKeySetupContent
import com.familykitchen.board.ui.aichef.ModelOperationProgressBanner
import com.familykitchen.board.ui.aichef.OnDeviceModelDownloadCtaRow
import com.familykitchen.board.ui.aichef.formatAiRuntimeBadgeLabel
import com.familykitchen.board.ui.computeToastAutoDismissMillis
import com.familykitchen.board.ui.theme.KitchenTheme
import kotlinx.coroutines.delay

// Stateless Step 3 (`Your Starting Pantry`) and Step 4 (`AI Sous-Chef Setup`) First-Run Onboarding
// section cards with allergen-pruned kitchen basics, 48.dp+ touch targets, and 0 slashes/parens.

/**
 * Step 3 of 4 (`Your Starting Pantry`): offers 3 [StartingPantryMode] cards (`CURATED_BASICS`,
 * `EMPTY_KITCHEN`, and `SAMPLE_DEMO_KITCHEN`).
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
  // Normalize active allergens once so conflicting starter staples stay automatically unchecked.
  val upperAllergens = draft.selectedAllergens.map { it.uppercase() }.toSet()
  val safeSelectedIds = pruneAllergenConflictingBasics(draft.selectedBasicIngredientIds, upperAllergens)

  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("Your Starting Pantry", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
      Text("Pick how to stock your shelves right now. Missing meal ingredients automatically queue on your Grocery List.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    // Render one >= 56.dp selectable card per StartingPantryMode option.
    StartingPantryMode.entries.forEach { mode ->
      val isSelected = draft.pantryMode == mode
      val modeTitle = when (mode) {
        StartingPantryMode.CURATED_BASICS -> "Quick-Check Kitchen Basics"
        StartingPantryMode.EMPTY_KITCHEN -> "Start Fresh · Empty Pantry"
        StartingPantryMode.SAMPLE_DEMO_KITCHEN -> "Explore Sample Kitchen"
      }
      val modeDetail = when (mode) {
        StartingPantryMode.CURATED_BASICS -> "Check the basics you already have at home; everything else starts at 0 stock."
        StartingPantryMode.EMPTY_KITCHEN -> "All 44 catalog items start at 0 stock with a calm open week ready for scans or menus."
        StartingPantryMode.SAMPLE_DEMO_KITCHEN -> "Stocks all 44 demo items including salmon, spinach, and Greek yogurt immediately."
      }

      OnboardSelectableCard(isSelected = isSelected, onClick = { onDraftChange(draft.copy(pantryMode = mode)) }) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Icon(Icons.Filled.Kitchen, contentDescription = null, tint = extendedColors.herbalSage, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(modeTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
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

      // Expand interactive staple checklist only when Quick-Check Kitchen Basics is active.
      if (mode == StartingPantryMode.CURATED_BASICS && isSelected) {
        onboardPantryCuratedBasicsCard(
          safeSelectedIds = safeSelectedIds,
          upperAllergens = upperAllergens,
          onUpdateSelectedIds = { nextIds -> onDraftChange(draft.copy(selectedBasicIngredientIds = nextIds)) },
        )
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun onboardPantryCuratedBasicsCard(
  safeSelectedIds: Set<String>,
  upperAllergens: Set<String>,
  onUpdateSelectedIds: (Set<String>) -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  val chipColors = FilterChipDefaults.filterChipColors(
    selectedContainerColor = extendedColors.sageContainer,
    selectedLabelColor = extendedColors.onSageContainer,
  )
  val countNoun = if (safeSelectedIds.size == 1) "staple" else "staples"

  // Ceramic checklist card letting families check common kitchen basics in 1 tap.
  Surface(
    shape = KitchenTheme.shapes.card2xl,
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
    shadowElevation = 0.dp,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      // Live counter summarizing checked starter staples vs auto-queued grocery deficits.
      Text(
        text = "${safeSelectedIds.size} $countNoun selected — missing meal items will queue on your Grocery List",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = extendedColors.onSageContainer,
      )
      // Bulk 48.dp Select Basics + Clear All shortcuts.
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(
          onClick = { onUpdateSelectedIds(pruneAllergenConflictingBasics(DEFAULT_SELECTED_BASIC_INGREDIENT_IDS, upperAllergens)) },
          shape = CircleShape,
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        ) { Text("Select Basics", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false) }
        TextButton(
          onClick = { onUpdateSelectedIds(emptySet()) },
          shape = CircleShape,
          modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
        ) { Text("Clear All", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false) }
      }
      HorizontalDivider(color = extendedColors.borderSubtle)
      // 48.dp starter staple FilterChips automatically disabling allergen-conflicting presets.
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DEFAULT_ONBOARDING_STAPLE_PRESETS.forEach { preset ->
          val isBlocked = preset.allergens.any { tag -> tag.uppercase() in upperAllergens }
          val isChecked = !isBlocked && preset.ingredientId in safeSelectedIds
          val chipText = if (isBlocked) "${preset.shortLabel} · Excluded" else "${preset.shortLabel} · ${preset.categoryHint}"
          FilterChip(
            selected = isChecked,
            enabled = !isBlocked,
            onClick = { onUpdateSelectedIds(if (isChecked) safeSelectedIds - preset.ingredientId else safeSelectedIds + preset.ingredientId) },
            leadingIcon = if (isChecked) ({ Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }) else null,
            label = { Text(chipText, style = MaterialTheme.typography.labelSmall) },
            colors = chipColors,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          )
        }
      }
    }
  }
}

/**
 * Step 4 of 4 (`AI Sous-Chef Setup`): highlights SavorAI's immediate 100% offline readiness and
 * offers optional Cloud API Key or local `.litertlm` Gemma SAF model setup.
 *
 * @param runtimeState Live on-device [AiRuntimeState].
 * @param activeDeviceProfile Selected universal Android RAM-tier profile.
 * @param cloudConfig Non-secret [CloudLlmConfig] state slice.
 * @param isVerifyingCloudKey `true` while probing Cloud LLM connectivity.
 * @param isRefreshingCloudModels `true` while syncing live `/models` identifiers.
 * @param importNotice Optional SAF `.litertlm` validation notice.
 * @param onDismissImportNotice Callback clearing [importNotice].
 * @param onLaunchModelPicker Callback opening SAF `OpenDocument` for local model imports.
 * @param onAiChefAction Callback dispatching [AiChefAction] intents.
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
    selectedContainerColor = extendedColors.sageContainer,
    selectedLabelColor = extendedColors.onSageContainer,
  )

  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("Your AI Sous-Chef", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
      Text("Private by design — start cooking immediately offline, or connect a Cloud API key or local Gemma model anytime.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    // Reassurance banner confirming deterministic offline Sous-Chef works with 0 network sockets.
    Surface(
      shape = KitchenTheme.shapes.card2xl,
      color = extendedColors.sageContainer,
      border = BorderStroke(1.dp, extendedColors.herbalSage.copy(alpha = 0.45f)),
      shadowElevation = 0.dp,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = extendedColors.onSageContainer, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text("Works 100% offline immediately", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = extendedColors.onSageContainer)
          Text(
            text = "SavorAI deterministic USDA macro, expiry-rescue, and pantry-grounded Sous-Chef needs zero downloads or API keys. Optionally connect a Cloud LLM key or On-Device Gemma .litertlm model now or anytime later in the Chef tab.",
            style = MaterialTheme.typography.bodySmall,
            color = extendedColors.onSageContainer,
          )
        }
      }
    }

    // Segmented mode toggle switching cleanly between On-Device Gemma and opt-in HTTPS Cloud API.
    AiModeSegmentedToggle(
      activeMode = cloudConfig.executionMode,
      onSelectMode = { mode -> onAiChefAction(AiChefAction.SelectAiExecutionMode(mode)) },
    )

    when (cloudConfig.executionMode) {
      // Opt-in HTTPS Cloud API setup storing keys in AndroidKeyStore AES-256-GCM vault.
      AiExecutionMode.CLOUD_API -> Surface(
        shape = KitchenTheme.shapes.card2xl,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, extendedColors.borderSubtle),
        shadowElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
      ) {
        CloudApiKeySetupContent(
          cloudConfig = cloudConfig,
          isVerifyingKey = isVerifyingCloudKey,
          onAction = onAiChefAction,
          isRefreshingModels = isRefreshingCloudModels,
          modifier = Modifier.padding(14.dp),
        )
      }

      // Default 100% offline On-Device setup supporting universal RAM-tier profiles and SAF import.
      AiExecutionMode.ON_DEVICE -> Surface(
        shape = KitchenTheme.shapes.card2xl,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, extendedColors.borderSubtle),
        shadowElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          // Live runtime status row with punctuation-sanitized capability badge.
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Memory, contentDescription = null, tint = extendedColors.herbalSage, modifier = Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text("On-Device Runtime Status", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
              Text(onboardSanitizeLabel(formatAiRuntimeBadgeLabel(runtimeState)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
          }
          // Universal Android RAM-tier profile chips (Compact 1B, Balanced 2B, Gemma 4 Vision).
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            EdgeDeviceProfile.entries.forEach { profile ->
              val modeTag = if (profile.supportsMultimodalVision) "Vision and Text" else "Text"
              FilterChip(
                selected = activeDeviceProfile == profile,
                onClick = { onAiChefAction(AiChefAction.SelectDeviceProfile(profile)) },
                label = { Text("${onboardSanitizeLabel(profile.displayName)} · $modeTag", style = MaterialTheme.typography.labelSmall) },
                colors = chipColors,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
              )
            }
          }
          // Show .part download or OpenCL shader compilation progress when busy.
          if (runtimeState.isBackgroundBusy) {
            AiBackgroundProgressCard(
              runtimeState = runtimeState,
              onCancelDownload = { onAiChefAction(AiChefAction.CancelActiveModelDownload) },
            )
          }
          // Preserve last non-blank SAF import notice during animated exit transitions.
          var lastImportNotice by remember { mutableStateOf(importNotice.orEmpty()) }
          if (!importNotice.isNullOrBlank()) {
            lastImportNotice = importNotice
          }
          // Auto-dismiss SAF import validation notice after proportional reading delay.
          LaunchedEffect(importNotice) {
            if (!importNotice.isNullOrBlank()) {
              delay(computeToastAutoDismissMillis(importNotice))
              onDismissImportNotice()
            }
          }
          // Dismissible SAF file validation notice card.
          AnimatedVisibility(
            visible = !importNotice.isNullOrBlank(),
            enter = fadeIn(tween(180)) + expandVertically(tween(200)),
            exit = fadeOut(tween(220)) + shrinkVertically(tween(220)),
          ) {
            Surface(shape = KitchenTheme.shapes.inputDefault, color = extendedColors.secondaryFixed, border = BorderStroke(1.dp, extendedColors.borderSubtle), modifier = Modifier.fillMaxWidth()) {
              Row(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(lastImportNotice, style = MaterialTheme.typography.bodySmall, color = extendedColors.onSecondaryFixed, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                IconButton(onClick = onDismissImportNotice, modifier = Modifier.size(48.dp)) {
                  Icon(Icons.Filled.Close, contentDescription = "Dismiss model import notice", tint = extendedColors.onSecondaryFixed)
                }
              }
            }
          }
          // Download approval + local SAF .litertlm file picker CTA row.
          OnDeviceModelDownloadCtaRow(
            runtimeState = runtimeState,
            onRequestDownloadApproval = { onAiChefAction(AiChefAction.RequestModelDownloadApproval()) },
            onPickLocalFile = onLaunchModelPicker,
            onCancelDownload = { onAiChefAction(AiChefAction.CancelActiveModelDownload) },
          )
        }
      }
    }
  }
}

/**
 * Onboarding background progress card delegating `.part` transfer, OpenCL shader loading, and
 * active download cancellation to [ModelOperationProgressBanner].
 *
 * @param runtimeState Active [AiRuntimeState] surfaced during model download or initialization.
 * @param modifier Optional Compose [Modifier].
 * @param onCancelDownload Optional cancellation callback during active `.part` downloads.
 */
@Composable
fun AiBackgroundProgressCard(
  runtimeState: AiRuntimeState,
  modifier: Modifier = Modifier,
  onCancelDownload: (() -> Unit)? = null,
) {
  // Delegate directly to the shared SavorAI ModelOperationProgressBanner component.
  ModelOperationProgressBanner(
    runtimeState = runtimeState,
    modifier = modifier,
    onCancelDownload = onCancelDownload,
  )
}
