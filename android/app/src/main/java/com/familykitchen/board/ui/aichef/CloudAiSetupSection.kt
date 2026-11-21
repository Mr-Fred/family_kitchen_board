package com.familykitchen.board.ui.aichef

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.AiChefAction
import com.familykitchen.board.domain.AiExecutionMode
import com.familykitchen.board.domain.CloudLlmConfig
import com.familykitchen.board.domain.CloudLlmProvider
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme

// Warm parchment & botanical sage Cloud AI setup controls: 2-pill On-Device vs Cloud API Key
// switcher, 4-provider selector, 1-tap preset + custom Model ID picker, and Keystore vault UI.

/**
 * Warm parchment and botanical-sage 2-pill execution mode switcher (`"On-Device"` vs
 * `"Cloud API Key"`) enforcing `48.dp` touch targets, `1.dp` warm stone border, and `0.dp` shadow.
 *
 * @param activeMode Currently selected [AiExecutionMode] (`ON_DEVICE` or `CLOUD_API`).
 * @param onSelectMode Callback invoked when the user selects a mode pill.
 * @param modifier Optional Compose [Modifier].
 */
@Composable
fun AiModeSegmentedToggle(
  activeMode: AiExecutionMode,
  onSelectMode: (AiExecutionMode) -> Unit,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  val modeOptions = listOf(
    AiExecutionMode.ON_DEVICE to "On-Device",
    AiExecutionMode.CLOUD_API to "Cloud API Key",
  )

  // Warm parchment segmented track with 1.dp subtle stone border and zero drop shadow.
  Surface(
    shape = CircleShape, color = extendedColors.parchment,
    border = BorderStroke(1.dp, extendedColors.borderSubtle), shadowElevation = 0.dp,
    modifier = modifier.fillMaxWidth(),
  ) {
    Row(modifier = Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      // Render equal-weight 48.dp non-wrapping pills so labels never clip at 1.35x font scale.
      modeOptions.forEach { (mode, label) ->
        val isSelected = activeMode == mode
        // Map hardware Memory icon to local Gemma and Cloud icon to HTTPS provider mode.
        val leadingIcon = when (mode) {
          AiExecutionMode.ON_DEVICE -> Icons.Filled.Memory
          AiExecutionMode.CLOUD_API -> Icons.Filled.Cloud
        }
        val contentTint = if (isSelected) extendedColors.onSageContainer else MaterialTheme.colorScheme.onSurfaceVariant
        Surface(
          shape = CircleShape,
          color = if (isSelected) extendedColors.sageContainer else Color.Transparent,
          border = if (isSelected) BorderStroke(1.dp, extendedColors.borderSubtle) else null,
          shadowElevation = 0.dp,
          modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).clip(CircleShape).clickable { onSelectMode(mode) },
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
          ) {
            Icon(leadingIcon, contentDescription = null, tint = contentTint, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = label, style = MaterialTheme.typography.labelLarge, color = contentTint,
              fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
              maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
    }
  }
}

/**
 * Stateless Cloud LLM setup section letting household members choose a provider (`Gemini`, `OpenAI`,
 * `Claude`, `OpenRouter`), pick or type any model ID, and manage their Android Keystore API key.
 *
 * @param cloudConfig Immutable [CloudLlmConfig] slice from `KitchenUiState`.
 * @param isVerifyingKey `true` while an HTTPS health-check ping is verifying the active key.
 * @param onAction Unidirectional intent callback dispatching [AiChefAction] events.
 * @param isRefreshingModels `true` while syncing live `/models` for the active provider.
 * @param modifier Optional Compose [Modifier].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CloudApiKeySetupContent(
  cloudConfig: CloudLlmConfig,
  isVerifyingKey: Boolean,
  onAction: (AiChefAction) -> Unit,
  isRefreshingModels: Boolean = false,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  val focusManager = LocalFocusManager.current
  val chipColors = FilterChipDefaults.filterChipColors(
    selectedContainerColor = extendedColors.sageContainer,
    selectedLabelColor = extendedColors.onSageContainer,
  )

  // Synchronize editable model draft whenever active provider or persisted modelId changes.
  var draftModelId by rememberSaveable(cloudConfig.provider, cloudConfig.modelId) {
    mutableStateOf(cloudConfig.modelId)
  }
  // Keep draftKey in transient remember (NEVER rememberSaveable) so raw keys skip IPC Bundles.
  var draftKey by remember(cloudConfig.provider) { mutableStateOf("") }
  // Default key visibility to masked dots whenever switching between Cloud providers.
  var isKeyVisible by remember(cloudConfig.provider) { mutableStateOf(false) }
  var isEditingKey by rememberSaveable(cloudConfig.provider, cloudConfig.hasStoredApiKey) {
    mutableStateOf(false)
  }

  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    // 1. Provider Selector: 48.dp touch-target chips with subtle checkmark on configured vaults.
    Text("Cloud Provider", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      CloudLlmProvider.entries.forEach { provider ->
        val isSelected = cloudConfig.provider == provider
        // Append subtle ✓ marker when a Keystore secret is already provisioned for this provider.
        val chipLabel = if (provider in cloudConfig.configuredProviders) "${provider.shortLabel} ✓" else provider.shortLabel
        FilterChip(
          selected = isSelected,
          onClick = { onAction(AiChefAction.SelectCloudProvider(provider)) },
          label = {
            Text(
              text = chipLabel, style = MaterialTheme.typography.labelMedium,
              fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
              maxLines = 1, softWrap = false,
            )
          },
          colors = chipColors,
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        )
      }
    }

    // 2. Model Selector: 1-tap preset + live-synced FilterChips + 48.dp "Sync Live Models" chip.
    val modelChoices = cloudConfig.availableModelIds.ifEmpty { cloudConfig.provider.presetModelIds }
    Text("Model (${cloudConfig.provider.displayName})", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      modelChoices.forEach { presetId ->
        FilterChip(
          selected = draftModelId.trim() == presetId,
          onClick = {
            // Update both local text field and ViewModel state in 1 tap.
            draftModelId = presetId
            onAction(AiChefAction.UpdateCloudModelId(presetId))
          },
          label = { Text(presetId, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
          colors = chipColors,
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        )
      }

      // 48.dp live GET /models catalog sync chip with 14.dp spinner right next to model presets.
      FilterChip(
        selected = false,
        onClick = {
          if (!isRefreshingModels) onAction(AiChefAction.RefreshCloudModels)
        },
        enabled = !isRefreshingModels && !isVerifyingKey,
        leadingIcon = {
          when {
            isRefreshingModels -> CircularProgressIndicator(
              modifier = Modifier.size(14.dp),
              strokeWidth = 2.dp,
              color = MaterialTheme.colorScheme.primary,
            )
            else -> Icon(
              imageVector = Icons.Filled.Refresh,
              contentDescription = null,
              modifier = Modifier.size(14.dp),
            )
          }
        },
        label = {
          Text(
            text = if (isRefreshingModels) "Syncing Models…" else "Sync Live Models",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
          )
        },
        colors = chipColors,
        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
      )
    }

    // Custom Model ID field allows entering newly released provider model slugs without app updates.
    OutlinedTextField(
      value = draftModelId,
      onValueChange = { updated ->
        draftModelId = updated
        val trimmed = updated.trim()
        if (trimmed.isNotEmpty()) onAction(AiChefAction.UpdateCloudModelId(trimmed))
      },
      label = { Text("Model ID") },
      placeholder = { Text(cloudConfig.provider.defaultModelId, maxLines = 1, overflow = TextOverflow.Ellipsis) },
      singleLine = true,
      keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
      keyboardActions = KeyboardActions(
        onDone = {
          // Fall back to provider's canonical default model if user clears the field and taps Done.
          val resolved = draftModelId.trim().ifEmpty { cloudConfig.provider.defaultModelId }
          draftModelId = resolved
          onAction(AiChefAction.UpdateCloudModelId(resolved))
          focusManager.clearFocus()
        },
      ),
      shape = KitchenTheme.shapes.inputDefault,
      modifier = Modifier.fillMaxWidth(),
    )

    // 3. Keystore API Key Vault Card switching between Ready vault state and masked entry form.
    CloudKeystoreVaultCard(
      cloudConfig = cloudConfig, draftKey = draftKey,
      isKeyVisible = isKeyVisible, isEditingKey = isEditingKey, isVerifyingKey = isVerifyingKey,
      onDraftKeyChange = { draftKey = it },
      onToggleKeyVisible = { isKeyVisible = !isKeyVisible },
      onStartEditingKey = {
        draftKey = ""
        isKeyVisible = false
        isEditingKey = true
      },
      onCancelEditingKey = {
        draftKey = ""
        isKeyVisible = false
        isEditingKey = false
      },
      onSaveAndConnect = {
        val cleanKey = draftKey.trim()
        if (cleanKey.length >= 8 && !isVerifyingKey) {
          val targetModel = draftModelId.trim().ifEmpty { cloudConfig.modelId }
          onAction(AiChefAction.SaveCloudApiKey(cloudConfig.provider, cleanKey, targetModel, verifyConnection = true))
          // Scrub raw API token from local UI state immediately after Keystore handoff.
          draftKey = ""
          isKeyVisible = false
          isEditingKey = false
          focusManager.clearFocus()
        }
      },
      onAction = onAction,
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CloudKeystoreVaultCard(
  cloudConfig: CloudLlmConfig,
  draftKey: String,
  isKeyVisible: Boolean,
  isEditingKey: Boolean,
  isVerifyingKey: Boolean,
  onDraftKeyChange: (String) -> Unit,
  onToggleKeyVisible: () -> Unit,
  onStartEditingKey: () -> Unit,
  onCancelEditingKey: () -> Unit,
  onSaveAndConnect: () -> Unit,
  onAction: (AiChefAction) -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Enforce >= 8 trimmed characters before enabling Keystore encryption and HTTPS ping.
  val canSaveKey = draftKey.trim().length >= 8 && !isVerifyingKey

  // Soft parchment vault container bounded by 1.dp warm stone border and 0.dp shadow.
  Surface(
    shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, extendedColors.borderSubtle), shadowElevation = 0.dp,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      when {
        cloudConfig.hasStoredApiKey && !isEditingKey -> {
          val readyCapability = if (cloudConfig.provider.supportsVision) "Vision & Text Ready" else "Text Ready"
          val maskedLabel = cloudConfig.maskedKeyPreview ?: "••••••••"
          // Calm botanical sage status row confirming hardware-backed Keystore protection.
          Surface(
            shape = RoundedCornerShape(12.dp), color = extendedColors.sageContainer,
            border = BorderStroke(1.dp, extendedColors.herbalSage.copy(alpha = 0.45f)), shadowElevation = 0.dp,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Icon(Icons.Filled.Lock, contentDescription = null, tint = extendedColors.onSageContainer, modifier = Modifier.size(16.dp))
              Text(
                text = "Saved in Android Keystore ($maskedLabel) · $readyCapability",
                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = extendedColors.onSageContainer,
              )
            }
          }

          // 48.dp action row wrapping gracefully on compact 360dp viewports at 1.35x font scale.
          FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Test Connection pings provider health endpoint with header-only auth.
            OutlinedButton(
              onClick = { onAction(AiChefAction.VerifyCloudConnection) }, enabled = !isVerifyingKey,
              shape = CircleShape, modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
              if (isVerifyingKey) {
                CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(6.dp))
              }
              Text(if (isVerifyingKey) "Verifying…" else "Test Connection", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
            }

            // Replace Key opens empty masked field without exposing the existing Keystore secret.
            OutlinedButton(
              onClick = onStartEditingKey, enabled = !isVerifyingKey,
              shape = CircleShape, modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
              Text("Replace Key", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
            }

            // Remove deletes the encrypted ciphertext for the active provider from Keystore vault.
            TextButton(
              onClick = { onAction(AiChefAction.ClearCloudApiKey(cloudConfig.provider)) }, enabled = !isVerifyingKey,
              contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
              modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
            ) {
              Text("Remove", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false)
            }
          }
        }

        else -> {
          // Masked password field disables OEM/system keyboard dictionary caching for API keys.
          OutlinedTextField(
            value = draftKey, onValueChange = onDraftKeyChange,
            label = { Text("${cloudConfig.provider.shortLabel} API Key") },
            placeholder = {
              Text("Paste ${cloudConfig.provider.shortLabel} key (${cloudConfig.provider.apiKeyHint})", maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            visualTransformation = if (isKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
              // 48.dp visibility toggle lets users inspect pasted characters before saving.
              IconButton(onClick = onToggleKeyVisible, modifier = Modifier.size(48.dp)) {
                Icon(
                  imageVector = if (isKeyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                  contentDescription = if (isKeyVisible) "Hide API key" else "Show API key",
                )
              }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSaveAndConnect() }),
            singleLine = true, shape = KitchenTheme.shapes.inputDefault, modifier = Modifier.fillMaxWidth(),
          )

          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Primary 48.dp Save & Connect CTA scrubbing draftKey immediately on dispatch.
            Button(
              onClick = onSaveAndConnect, enabled = canSaveKey, shape = CircleShape,
              modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
            ) {
              if (isVerifyingKey) {
                CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(modifier = Modifier.width(8.dp))
              }
              Text(if (isVerifyingKey) "Verifying…" else "Save & Connect", style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
            }

            // Allow backing out of key replacement while keeping the existing Keystore secret intact.
            if (isEditingKey && cloudConfig.hasStoredApiKey) {
              TextButton(onClick = onCancelEditingKey, modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)) {
                Text("Cancel", maxLines = 1, softWrap = false)
              }
            }
          }
        }
      }

      // Subtle 1-line security caption reassuring users about at-rest encryption and HTTPS headers.
      Text(
        text = "Encrypted at rest via Android Keystore AES-256-GCM; sent only over HTTPS headers.",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Preview(name = "Cloud AI Setup — Empty Key Entry", showBackground = true, widthDp = 412)
@Composable
private fun CloudAiSetupEmptyKeyPreview() {
  // Showcases 2-pill mode switcher, 4-provider chips, preset models, and masked API key input.
  FamilyKitchenBoardTheme {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      AiModeSegmentedToggle(activeMode = AiExecutionMode.CLOUD_API, onSelectMode = {})
      CloudApiKeySetupContent(
        cloudConfig = CloudLlmConfig(executionMode = AiExecutionMode.CLOUD_API, provider = CloudLlmProvider.GEMINI),
        isVerifyingKey = false, onAction = {},
      )
    }
  }
}

@Preview(name = "Cloud AI Setup — Keystore Ready", showBackground = true, widthDp = 412)
@Composable
private fun CloudAiSetupSavedKeyReadyPreview() {
  // Showcases saved Android Keystore badge, configured provider checkmarks, and 48.dp vault CTAs.
  FamilyKitchenBoardTheme {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      AiModeSegmentedToggle(activeMode = AiExecutionMode.CLOUD_API, onSelectMode = {})
      CloudApiKeySetupContent(
        cloudConfig = CloudLlmConfig(
          executionMode = AiExecutionMode.CLOUD_API, provider = CloudLlmProvider.GEMINI,
          modelId = "gemini-3.8-flash", hasStoredApiKey = true, maskedKeyPreview = "•••• 9A2F",
          configuredProviders = setOf(CloudLlmProvider.GEMINI, CloudLlmProvider.ANTHROPIC),
        ),
        isVerifyingKey = false, onAction = {},
      )
    }
  }
}
