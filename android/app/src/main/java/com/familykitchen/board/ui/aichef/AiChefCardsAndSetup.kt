package com.familykitchen.board.ui.aichef

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.AiChefAction
import com.familykitchen.board.domain.AiExecutionMode
import com.familykitchen.board.domain.CloudLlmConfig
import com.familykitchen.board.domain.EdgeDeviceProfile
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ports.RuntimeCapabilities
import com.familykitchen.board.ports.VoiceInputState
import com.familykitchen.board.ui.PERMISSION_NOTICE_AUTO_DISMISS_MS
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme
import com.familykitchen.board.ui.theme.SavorBrandBadge
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// Editorial SavorWelcomeBanner, Collapsible On-Device Edge AI Setup Panel, Non-Silent Vision Gate
// Banner, Staged Composer Thumbnail Row, and Live Bilingual Speech Status Strip.

/**
 * Returns `true` strictly when [runtimeState] is [AiRuntimeState.Ready] with an active multimodal
 * vision backend (`capabilities.supportsVision == true`).
 */
fun isRuntimeVisionCapable(runtimeState: AiRuntimeState): Boolean =
  (runtimeState as? AiRuntimeState.Ready)?.capabilities?.supportsVision == true

/**
 * Formats a compact one-line status badge describing [runtimeState].
 */
fun formatAiRuntimeBadgeLabel(runtimeState: AiRuntimeState): String =
  // Surface explicit Vision Ready, live MB/% progress, or Text-Only status transparently.
  when (runtimeState) {
    AiRuntimeState.Uninitialized -> "Offline Chef Ready"
    AiRuntimeState.Discovering -> "Scanning Models…"
    is AiRuntimeState.Importing -> {
      val verb = if (runtimeState.isNetworkDownload) "Downloading" else "Importing"
      when {
        runtimeState.copiedBytes > 0L ->
          "$verb ${runtimeState.fileName} (${formatByteProgressLabel(runtimeState.copiedBytes, runtimeState.totalBytes, runtimeState.isNetworkDownload)})"
        else -> {
          val pct = (runtimeState.progressFraction.coerceIn(0f, 1f) * 100f).roundToInt()
          "$verb ${runtimeState.fileName} ($pct%)"
        }
      }
    }
    is AiRuntimeState.Loading -> "Loading ${runtimeState.modelName}…"
    is AiRuntimeState.Ready -> {
      val visionSuffix = if (runtimeState.capabilities.supportsVision) "Vision" else "Text-Only"
      "${runtimeState.capabilities.backendLabel} ($visionSuffix)"
    }
    is AiRuntimeState.Error -> "Offline Grounded Chef"
  }

/**
 * Refined editorial empty-state welcome card rendered before the first Sous-Chef turn:
 * `"Good evening, Chef"` (`Newsreader`) + calm human subtitle + single subtle context pill
 * (`"$expiringSoonCount expiring soon · $plannedDinnerCount dinners planned"`) + `"AI Setup"`
 * (or `"Cloud · <Provider>"` when Cloud Ready) toggle chip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SavorWelcomeBanner(
  expiringSoonCount: Int = 3,
  plannedDinnerCount: Int = 2,
  runtimeState: AiRuntimeState = AiRuntimeState.Uninitialized,
  isSetupExpanded: Boolean = false,
  onToggleSetup: () -> Unit = {},
  cloudConfig: CloudLlmConfig = CloudLlmConfig(),
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Highlight setup chip in warm sage whenever Cloud API vault or local Gemma Vision is Ready.
  val isCloudReady = cloudConfig.executionMode == AiExecutionMode.CLOUD_API && cloudConfig.hasStoredApiKey
  val isAiHighlighted = isSetupExpanded || isRuntimeVisionCapable(runtimeState) || isCloudReady
  // Surface active Cloud provider short name directly on the welcome chip when Cloud Ready.
  val setupChipLabel = when {
    isSetupExpanded -> "Hide AI Setup"
    isCloudReady -> "Cloud · ${cloudConfig.provider.shortLabel}"
    else -> "AI Setup"
  }

  // Calm ceramic card with 1.dp warm border and zero heavy shadow elevation.
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = KitchenTheme.shapes.card2xl,
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
    shadowElevation = 0.dp,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      // Pair the 36.dp SavorAI Dutch-oven cocotte emblem alongside the editorial greeting.
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        SavorBrandBadge(badgeSize = 36.dp, logoSize = 22.dp, shape = RoundedCornerShape(11.dp))
        Text(
          text = "Good evening, Chef",
          style = MaterialTheme.typography.headlineMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
      Text(
        text = "Ask about tonight's meals, scan your fridge or spice rack, or balance macros in one tap.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      // Single subtle live kitchen context pill paired with a 48.dp touch-target AI Setup chip.
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        // Non-intrusive 1-line summary keeps urgent expiry count and planned dinners grounded.
        Box(modifier = Modifier.defaultMinSize(minHeight = 44.dp), contentAlignment = Alignment.CenterStart) {
          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, extendedColors.borderSubtle),
            shadowElevation = 0.dp,
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              // Muted botanical status dot indicates local household vault synchronization.
              Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(extendedColors.freshIndicator))
              Text(
                text = "$expiringSoonCount expiring soon · $plannedDinnerCount dinners planned",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }

        // Inline AI Setup chip complements the top-bar Tune icon for 1-tap Cloud or SAF setup.
        Box(
          modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 44.dp).clip(CircleShape).clickable(onClick = onToggleSetup),
          contentAlignment = Alignment.Center,
        ) {
          Surface(
            shape = CircleShape,
            color = if (isAiHighlighted) extendedColors.sageContainer else MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, extendedColors.borderSubtle),
            shadowElevation = 0.dp,
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
              Icon(
                imageVector = Icons.Filled.Tune,
                contentDescription = null,
                tint = if (isAiHighlighted) extendedColors.onSageContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
              )
              Text(
                text = setupChipLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isAiHighlighted) extendedColors.onSageContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
              )
            }
          }
        }
      }
    }
  }
}

/**
 * Collapsible hybrid On-Device & Cloud AI Setup Panel (`"AI Model & Cloud Key Setup"`) switching
 * cleanly between Android Keystore Cloud API keys (`Gemini`, `OpenAI`, `Claude`, `OpenRouter`) and
 * universal Android RAM-tier on-device `.litertlm` models.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EdgeAiRuntimeSetupPanel(
  runtimeState: AiRuntimeState,
  activeProfile: EdgeDeviceProfile,
  isExpanded: Boolean,
  onToggleExpanded: () -> Unit,
  onSelectProfile: (EdgeDeviceProfile) -> Unit,
  onImportModelClick: () -> Unit,
  cloudConfig: CloudLlmConfig = CloudLlmConfig(),
  isVerifyingCloudKey: Boolean = false,
  isRefreshingCloudModels: Boolean = false,
  onAction: (AiChefAction) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  val visionReady = isRuntimeVisionCapable(runtimeState)
  // Treat local SAF/GPU initialization, key verification, and /models sync as active setup work.
  val isBusy = runtimeState.isBackgroundBusy || isVerifyingCloudKey || isRefreshingCloudModels
  val isCloudMode = cloudConfig.executionMode == AiExecutionMode.CLOUD_API
  // Show live verification or model-sync copy while busy; otherwise render runtime badge.
  val statusSubtitle = when {
    isVerifyingCloudKey -> "Verifying ${cloudConfig.provider.shortLabel} connection…"
    isRefreshingCloudModels -> "Syncing live ${cloudConfig.provider.shortLabel} models…"
    else -> formatAiRuntimeBadgeLabel(runtimeState)
  }

  Card(
    modifier = modifier.fillMaxWidth(),
    shape = KitchenTheme.shapes.card2xl,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .defaultMinSize(minHeight = 52.dp)
          .clickable(onClick = onToggleExpanded)
          .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (visionReady || isBusy) extendedColors.sageContainer else MaterialTheme.colorScheme.surfaceContainer,
          ) {
            Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
              when {
                isBusy -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.2.dp, color = MaterialTheme.colorScheme.primary)
                else -> Icon(
                  imageVector = if (isCloudMode) Icons.Filled.Cloud else Icons.Filled.Memory,
                  contentDescription = null,
                  tint = if (visionReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(20.dp),
                )
              }
            }
          }
          Spacer(modifier = Modifier.width(10.dp))
          Column {
            Text("AI Model & Cloud Key Setup", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
              text = statusSubtitle,
              style = MaterialTheme.typography.bodySmall,
              color = if (visionReady || isBusy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        Icon(
          imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
          contentDescription = if (isExpanded) "Collapse AI setup" else "Expand AI setup",
        )
      }

      // Keep live progress bar + byte/GPU label visible across all 3 on-device busy states even collapsed.
      if (runtimeState.isBackgroundBusy) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          when {
            runtimeState is AiRuntimeState.Importing && runtimeState.totalBytes > 0L && runtimeState.copiedBytes > 0L -> {
              val clamped = runtimeState.progressFraction.coerceIn(0f, 1f)
              LinearProgressIndicator(progress = { clamped }, modifier = Modifier.fillMaxWidth())
            }
            else -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
          }
          val progressNote = when (runtimeState) {
            is AiRuntimeState.Importing ->
              "${runtimeState.fileName} • ${formatByteProgressLabel(runtimeState.copiedBytes, runtimeState.totalBytes, runtimeState.isNetworkDownload)}"
            is AiRuntimeState.Loading -> "Initializing ${runtimeState.modelName} on device GPU (10–25s)…"
            AiRuntimeState.Discovering -> "Scanning local storage for Gemma models…"
            else -> ""
          }
          Text(progressNote, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }

      AnimatedVisibility(visible = isExpanded) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          // Separate collapsed runtime header from the 2-pill On-Device vs Cloud API Key switcher.
          HorizontalDivider(color = extendedColors.borderSubtle)
          AiModeSegmentedToggle(
            activeMode = cloudConfig.executionMode,
            onSelectMode = { mode -> onAction(AiChefAction.SelectAiExecutionMode(mode)) },
          )

          when (cloudConfig.executionMode) {
            // Render Android Keystore Cloud provider, model, and masked key vault controls.
            AiExecutionMode.CLOUD_API -> {
              CloudApiKeySetupContent(
                cloudConfig = cloudConfig,
                isVerifyingKey = isVerifyingCloudKey,
                onAction = onAction,
                isRefreshingModels = isRefreshingCloudModels,
              )
            }

            // Render universal Android .litertlm profiles, 1-tap Gemma 4 download, and SAF picker.
            AiExecutionMode.ON_DEVICE -> {
              // Hardware profile chips enforce >= 48.dp touch height for comfortable single-hand selection.
              FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EdgeDeviceProfile.entries.forEach { profile ->
                  val modeTag = if (profile.supportsMultimodalVision) "Vision + Text" else "Text"
                  FilterChip(
                    selected = activeProfile == profile,
                    onClick = { onSelectProfile(profile) },
                    label = { Text("${profile.displayName} • $modeTag", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                  )
                }
              }

              // Capability diagnostics surface active backend delegate and vision pixel eligibility.
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, extendedColors.borderSubtle),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                  // Exhaustively map all 6 AiRuntimeState branches into human-readable diagnostics.
                  when (runtimeState) {
                    is AiRuntimeState.Ready -> {
                      val caps = runtimeState.capabilities
                      Text("Model: ${caps.modelName}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                      Text("Backend: ${caps.backendLabel} • Vision=${caps.supportsVision}", style = MaterialTheme.typography.bodySmall)
                    }
                    is AiRuntimeState.Error -> Text(runtimeState.message, style = MaterialTheme.typography.bodySmall)
                    is AiRuntimeState.Loading -> Text("Initializing GPU/CPU buffers for ${runtimeState.modelName}…", style = MaterialTheme.typography.bodySmall)
                    AiRuntimeState.Discovering -> Text("Scanning app-scoped model directories…", style = MaterialTheme.typography.bodySmall)
                    is AiRuntimeState.Importing -> {
                      val verb = if (runtimeState.isNetworkDownload) "Downloading" else "Importing"
                      Text(
                        text = "$verb ${runtimeState.fileName} (${formatByteProgressLabel(runtimeState.copiedBytes, runtimeState.totalBytes, runtimeState.isNetworkDownload)})…",
                        style = MaterialTheme.typography.bodySmall,
                      )
                    }
                    AiRuntimeState.Uninitialized -> Text("Recommended: ${activeProfile.recommendedModelFileName}", style = MaterialTheme.typography.bodySmall)
                  }
                }
              }

              // 1-tap approval-gated Gemma 4 Vision download paired with Cancel and SAF local picker.
              OnDeviceModelDownloadCtaRow(
                runtimeState = runtimeState,
                onRequestDownloadApproval = { onAction(AiChefAction.RequestModelDownloadApproval()) },
                onPickLocalFile = onImportModelClick,
                onCancelDownload = { onAction(AiChefAction.CancelActiveModelDownload) },
              )
            }
          }
        }
      }
    }
  }
}

/**
 * Unmistakable Non-Silent Vision Gate warning card displayed whenever a photo is attached while
 * the active [AiRuntimeState] lacks multimodal vision (`supportsVision == false`). Lets users
 * download Gemma 4 Vision, connect a Cloud Vision API key, or pick a local `.litertlm` package.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VisionUnsupportedGateBanner(
  runtimeState: AiRuntimeState,
  onImportVisionModel: () -> Unit,
  onRemovePhoto: () -> Unit,
  onUseCloudApiKey: () -> Unit = {},
  onDownloadVisionModel: () -> Unit = {},
  onCancelDownload: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  val isBusy = runtimeState.isBackgroundBusy
  val isNetworkDownload = runtimeState is AiRuntimeState.Importing && runtimeState.isNetworkDownload
  val sageButtonColors = ButtonDefaults.buttonColors(
    containerColor = extendedColors.herbalSage,
    contentColor = MaterialTheme.colorScheme.onPrimary,
  )
  // Switch banner headline and body copy immediately when Gemma download, import, or GPU init begins.
  val bannerTitle = when {
    isBusy -> "Setting Up Gemma Vision Model…"
    else -> "Vision Model Required to Inspect Photo"
  }
  val reasonExplanation = when {
    isBusy ->
      "Keep this photo attached — Send unlocks automatically as soon as Gemma finishes loading."
    runtimeState is AiRuntimeState.Ready ->
      "Active model '${runtimeState.capabilities.modelName}' on '${runtimeState.capabilities.backendLabel}' " +
        "is text-only and cannot inspect image pixels. Silent text-only fallback is disabled so " +
        "SavorAI never guesses unseen ingredients."
    runtimeState is AiRuntimeState.Error ->
      "No multimodal vision model is active (${runtimeState.message}). Download Gemma 4 Vision, " +
        "connect a Cloud API key, or pick a local .litertlm model so your photo is never ignored."
    else ->
      "No multimodal vision model is Ready yet. Download Gemma 4 Vision (~2 GB), use a Cloud " +
        "Vision API key, pick a local .litertlm package, or remove the photo."
  }

  Card(
    modifier = modifier.fillMaxWidth(),
    shape = KitchenTheme.shapes.card2xl,
    colors = CardDefaults.cardColors(containerColor = extendedColors.childSafetyContainer),
    border = BorderStroke(1.dp, extendedColors.childSafetyBorder),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
          isBusy -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.2.dp, color = extendedColors.onChildSafety)
          else -> Icon(Icons.Filled.Warning, contentDescription = null, tint = extendedColors.onChildSafety)
        }
        Text(bannerTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = extendedColors.onChildSafety)
      }
      Text(reasonExplanation, style = MaterialTheme.typography.bodySmall, color = extendedColors.onChildSafety)
      // Embed live spinner, byte counter, progress bar, and 48.dp Cancel CTA while busy.
      if (isBusy) ModelOperationProgressBanner(runtimeState = runtimeState, onCancelDownload = onCancelDownload)
      // FlowRow wraps 48.dp actions cleanly across rows on compact 360dp screens at 1.35x scale.
      FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Button(
          onClick = onDownloadVisionModel,
          enabled = !isBusy,
          shape = CircleShape,
          colors = sageButtonColors,
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        ) {
          Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = if (isNetworkDownload) formatBusyImportButtonLabel(runtimeState, "Download Gemma 4") else "Download Gemma 4",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
          )
        }
        OutlinedButton(
          onClick = onUseCloudApiKey,
          enabled = !isBusy,
          shape = CircleShape,
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        ) {
          Text("Use Cloud API Key", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
        }
        OutlinedButton(
          onClick = onImportVisionModel,
          enabled = !isBusy,
          shape = CircleShape,
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        ) {
          if (isBusy && !isNetworkDownload) {
            CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(6.dp))
          }
          Text(
            text = if (!isNetworkDownload) formatBusyImportButtonLabel(runtimeState, "Pick Local .litertlm") else "Pick Local .litertlm",
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            softWrap = false,
          )
        }
        OutlinedButton(
          onClick = onRemovePhoto,
          shape = CircleShape,
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        ) {
          Text("Remove Photo", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
        }
      }
    }
  }
}

/**
 * Staged photo preview row rendered inside the top of `SavorAgentComposer` displaying the decoded
 * [ImageBitmap] thumbnail, filename, readiness status, and a `48.dp` remove `IconButton`.
 */
@Composable
fun AttachedImageThumbnailCard(
  imagePath: String,
  displayLabel: String,
  visionSupported: Boolean,
  onRemove: () -> Unit,
  overrideBitmap: ImageBitmap? = null,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Decode local JPEG path off the main thread when no pre-decoded content:// bitmap was passed.
  val decodedBitmap by produceState<ImageBitmap?>(initialValue = overrideBitmap, imagePath, overrideBitmap) {
    value = overrideBitmap ?: withContext(Dispatchers.IO) { decodeSampledThumbnailOrNull(imagePath, 256) }
  }

  // Soft parchment staging row nested cleanly inside the top of SavorAgentComposer.
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
    shadowElevation = 0.dp,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      // 48.dp rounded-12 thumbnail box displays real pixels before and during inference.
      Box(
        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(extendedColors.sageContainer),
        contentAlignment = Alignment.Center,
      ) {
        val bmp = decodedBitmap
        // Render real decoded image pixels when available; fall back to vector placeholder otherwise.
        when {
          bmp != null -> Image(
            bitmap = bmp,
            contentDescription = "Attached kitchen photo $displayLabel",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
          )
          else -> Icon(Icons.Filled.PhotoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
      }

      // Weighted filename + vision readiness caption prevents pushing the 48.dp Close icon off-card.
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = displayLabel,
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = if (visionSupported) "Ready for photo scan" else "Vision model required to scan",
          style = MaterialTheme.typography.labelSmall,
          color = if (visionSupported) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      // Full 48.dp touch target for effortless one-handed attachment dismissal.
      IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
        Icon(
          imageVector = Icons.Filled.Close,
          contentDescription = "Remove attached photo",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(18.dp),
        )
      }
    }
  }
}

// Decodes a downsampled Bitmap thumbnail from a local file path without blocking the UI thread.
private fun decodeSampledThumbnailOrNull(rawPath: String, targetEdgePx: Int): ImageBitmap? {
  // Content URIs are decoded inside AiChefActivityHost via ContentResolver; skip non-file schemes.
  if (rawPath.isBlank() || rawPath.startsWith("content://")) return null
  val candidateFile = File(rawPath.removePrefix("file://"))
  // Guard against deleted or 0-byte cancelled camera files before invoking BitmapFactory.
  if (!candidateFile.isFile || candidateFile.length() <= 0L) return null

  return runCatching {
    // First pass reads image dimensions only (zero pixel allocation).
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(candidateFile.absolutePath, bounds)
    val maxDim = max(bounds.outWidth, bounds.outHeight)
    if (maxDim <= 0) return null
    // Compute power-of-two sample factor so high-MP camera JPEGs decode in < 2ms.
    var sampleSize = 1
    while (maxDim / (sampleSize * 2) >= targetEdgePx) sampleSize *= 2
    // Second pass decodes the bounded thumbnail into an ImageBitmap for composer rendering.
    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    BitmapFactory.decodeFile(candidateFile.absolutePath, decodeOpts)?.asImageBitmap()
  }.getOrNull()
}

/**
 * Auto-dismissing (`5s`) permission or SAF model-picker feedback banner displayed inside
 * `AiChefScreen` with a `48.dp` manual close action.
 */
@Composable
internal fun PermissionFeedbackBanner(message: String, onDismiss: () -> Unit) {
  val extendedColors = KitchenTheme.extendedColors
  // Automatically retire transient camera/microphone/SAF guidance after 5 seconds.
  LaunchedEffect(message) {
    if (message.isNotBlank()) {
      delay(PERMISSION_NOTICE_AUTO_DISMISS_MS)
      onDismiss()
    }
  }
  // Soft coral secondaryFixed container surfaces non-blocking camera/microphone guidance.
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = KitchenTheme.shapes.inputDefault,
    color = extendedColors.secondaryFixed,
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
    shadowElevation = 0.dp,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = extendedColors.onSecondaryFixed,
        modifier = Modifier.weight(1f).padding(vertical = 6.dp),
      )
      IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
        Icon(
          imageVector = Icons.Filled.Close,
          contentDescription = "Dismiss notice",
          tint = extendedColors.onSecondaryFixed,
          modifier = Modifier.size(18.dp),
        )
      }
    }
  }
}

/**
 * Live bilingual speech-recognition status pill (`en-US` / `fr-FR`) docked above the composer.
 */
@Composable
internal fun VoiceInputStatusStrip(
  voiceState: VoiceInputState,
  activeLocale: String,
  onStopListening: () -> Unit,
) {
  // Collapse completely when idle or finalized so the persistent composer stays compact.
  when (voiceState) {
    VoiceInputState.Idle, is VoiceInputState.Result -> Unit
    is VoiceInputState.Listening -> {
      // Warm roasted-charcoal inverseSurface capsule streaming partial speech hypotheses.
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.inverseSurface,
        shadowElevation = 0.dp,
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            CircularProgressIndicator(
              modifier = Modifier.size(14.dp),
              strokeWidth = 2.dp,
              color = KitchenTheme.extendedColors.primaryFixed,
            )
            Spacer(modifier = Modifier.width(8.dp))
            val liveText = voiceState.partialTranscript.ifBlank { "Listening ($activeLocale)… speak your request" }
            Text(
              text = liveText,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.inverseOnSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          TextButton(
            onClick = onStopListening,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
          ) {
            Text(
              text = "Stop",
              color = KitchenTheme.extendedColors.primaryFixed,
              maxLines = 1,
              softWrap = false,
            )
          }
        }
      }
    }
    is VoiceInputState.Error -> Text(
      text = "Voice note: ${voiceState.message}",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.error,
    )
  }
}

@Preview(name = "Editorial Welcome & Model Setup", showBackground = true, widthDp = 412)
@Composable
private fun SavorWelcomeAndSetupPreview() {
  // Verifies editorial greeting, subtle context pill, Model toggle chip, and setup card.
  val ready = AiRuntimeState.Ready(
    RuntimeCapabilities(true, true, "LiteRT-LM GPU", "gemma-e2b-it-multimodal-int4.litertlm"),
  )
  FamilyKitchenBoardTheme {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      SavorWelcomeBanner(
        expiringSoonCount = 3,
        plannedDinnerCount = 2,
        runtimeState = ready,
      )
      EdgeAiRuntimeSetupPanel(
        runtimeState = ready,
        activeProfile = EdgeDeviceProfile.GEMMA_4_E2B_MULTIMODAL,
        isExpanded = true,
        onToggleExpanded = {},
        onSelectProfile = {},
        onImportModelClick = {},
      )
    }
  }
}
