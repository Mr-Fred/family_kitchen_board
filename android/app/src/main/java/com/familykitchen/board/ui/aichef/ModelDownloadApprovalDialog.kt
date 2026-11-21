package com.familykitchen.board.ui.aichef

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.familykitchen.board.domain.DownloadableGemmaPreset
import com.familykitchen.board.domain.ModelDownloadApprovalSpec
import com.familykitchen.board.domain.NetworkMeteringStatus
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme

// Warm Linen Material 3 approval dialog and reusable 1-tap CTA row gating multi-GB Hugging Face
// Gemma 4 (.litertlm) downloads behind explicit storage, resume, and Wi-Fi/cellular telemetry.

private const val BYTES_PER_KIB_DL: Long = 1024L
private const val BYTES_PER_MIB_DL: Long = 1024L * 1024L
private const val BYTES_PER_GIB_DL: Long = 1024L * 1024L * 1024L
private const val TEN_GIB_THRESHOLD_BYTES: Long = 10L * BYTES_PER_GIB_DL

/**
 * Formats a raw byte count into a deterministic human-readable MB or GB label (`"2,050 MB"`,
 * `"18.6 GB"`) without platform `Locale` dependencies.
 *
 * @param bytes Raw byte count to format (clamped at `0L`).
 * @return Human-readable storage or transfer size string.
 */
fun formatMbOrGb(bytes: Long): String {
  val nonNegative = bytes.coerceAtLeast(0L)
  return when {
    // Switch to tenths-of-GB above 10 GiB so large device free-space numbers stay concise.
    nonNegative >= TEN_GIB_THRESHOLD_BYTES -> {
      val tenthsGib = (nonNegative * 10L) / BYTES_PER_GIB_DL
      "${formatAsciiThousands(tenthsGib / 10L)}.${tenthsGib % 10L} GB"
    }
    // Keep 1 MB..9,999 MB in grouped megabytes ("2,050 MB", "3,100 MB") for clear transfer math.
    nonNegative >= BYTES_PER_MIB_DL -> "${formatAsciiThousands(nonNegative / BYTES_PER_MIB_DL)} MB"
    nonNegative >= BYTES_PER_KIB_DL -> "${formatAsciiThousands(nonNegative / BYTES_PER_KIB_DL)} KB"
    else -> "$nonNegative B"
  }
}

/**
 * Material 3 modal dialog gating multi-gigabyte on-device Gemma 4 `.litertlm` downloads behind
 * explicit user consent, live storage headroom checks, `.part` resume telemetry, and metered
 * mobile-data / offline warnings.
 *
 * @param approvalSpec Pre-flight [ModelDownloadApprovalSpec] containing preset, free bytes, and network state.
 * @param onSelectPreset Callback invoked when the user switches between `E2B` and `E4B` presets.
 * @param onApproveDownload Callback invoked when the user confirms downloading the selected preset.
 * @param onDismiss Callback dismissing the approval dialog without starting a network transfer.
 * @param modifier Optional Compose [Modifier].
 */
@Composable
fun ModelDownloadApprovalDialog(
  approvalSpec: ModelDownloadApprovalSpec,
  onSelectPreset: (DownloadableGemmaPreset) -> Unit,
  onApproveDownload: (DownloadableGemmaPreset) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // Disable narrow platform dialog width insets so compact 360dp Android screens retain full width.
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    ModelDownloadApprovalCard(
      approvalSpec = approvalSpec, onSelectPreset = onSelectPreset,
      onApproveDownload = onApproveDownload, onDismiss = onDismiss,
      modifier = modifier.padding(horizontal = 16.dp, vertical = 20.dp).widthIn(max = 520.dp).fillMaxWidth(),
    )
  }
}

/**
 * Stateless Warm Linen / Counter Dark approval card rendered inside [ModelDownloadApprovalDialog]
 * and directly testable in headless Compose `@Preview`s with vertical scroll resilience at `1.35x`
 * font scaling on compact `360dp` Android screens.
 *
 * @param approvalSpec Pre-flight [ModelDownloadApprovalSpec] telemetry snapshot.
 * @param onSelectPreset Callback refreshing storage/resume math for a newly selected preset.
 * @param onApproveDownload Callback starting or resuming the approved `.litertlm` download.
 * @param onDismiss Callback closing the dialog without network activity.
 * @param modifier Optional Compose [Modifier].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelDownloadApprovalCard(
  approvalSpec: ModelDownloadApprovalSpec,
  onSelectPreset: (DownloadableGemmaPreset) -> Unit,
  onApproveDownload: (DownloadableGemmaPreset) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  val preset = approvalSpec.preset
  // Gate primary approval CTA whenever device storage is insufficient or no network route exists.
  val canStartDownload = approvalSpec.hasEnoughStorage && !approvalSpec.isOfflineWarning
  val chipColors = FilterChipDefaults.filterChipColors(
    selectedContainerColor = extendedColors.sageContainer, selectedLabelColor = extendedColors.onSageContainer,
  )
  val sageButtonColors = ButtonDefaults.buttonColors(
    containerColor = extendedColors.herbalSage, contentColor = MaterialTheme.colorScheme.onPrimary,
  )

  // Warm Linen (#FAF7F2) / Counter Dark dialog container with 1.dp stone border and 0.dp shadow.
  Surface(
    shape = KitchenTheme.shapes.card2xl, color = MaterialTheme.colorScheme.background,
    border = BorderStroke(1.dp, extendedColors.borderSubtle), shadowElevation = 0.dp, modifier = modifier,
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      // Header: CloudDownload icon inside soft sage circle + title + 48.dp Close icon button.
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
          Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(extendedColors.sageContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.CloudDownload, contentDescription = null, tint = extendedColors.onSageContainer, modifier = Modifier.size(20.dp))
          }
          Column {
            Text("Download On-Device Gemma 4?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text("Runs 100% locally on your device after download", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
          Icon(Icons.Filled.Close, contentDescription = "Close model download dialog", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }

      // Scrollable middle body keeps chips, telemetry, and notices accessible on compact 360dp screens.
      Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Preset FilterChips (48.dp+ minHeight) re-query StatFs & .part resume bytes immediately.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text("Choose Gemma 4 Multimodal Package", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DownloadableGemmaPreset.entries.forEach { candidate ->
              val isSelected = candidate == preset
              FilterChip(
                selected = isSelected, onClick = { onSelectPreset(candidate) },
                leadingIcon = if (isSelected) ({ Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }) else null,
                label = {
                  Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(candidate.displayName, style = MaterialTheme.typography.labelMedium, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold)
                    Text(candidate.shortBadge, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  }
                },
                colors = chipColors, modifier = Modifier.defaultMinSize(minHeight = 48.dp),
              )
            }
          }
        }

        // Crisp ceramic telemetry card summarizing license, transfer bytes, and free storage.
        DownloadTelemetrySummaryCard(approvalSpec = approvalSpec)

        // Network metering / offline notice banner calibrated in botanical sage, honey-amber, or clay.
        DownloadNetworkNoticeBanner(networkStatus = approvalSpec.networkStatus)
      }

      HorizontalDivider(color = extendedColors.borderSubtle)

      // Bottom actions (48.dp+): FlowRow wraps cleanly at 1.35x font scale on 360dp screens.
      FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onDismiss, shape = CircleShape, modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)) {
          Text("Not Now", style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
        }
        // Switch CTA verb to Resume Download whenever a partial .part staging file already exists.
        Button(
          onClick = { onApproveDownload(preset) }, enabled = canStartDownload, shape = CircleShape,
          colors = sageButtonColors, modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
        ) {
          Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(8.dp))
          val approveLabel = if (approvalSpec.isResumingPartialDownload) "Resume Download" else "Approve & Download"
          Text(approveLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        }
      }
    }
  }
}

/**
 * Reusable `48.dp+` on-device model action cluster pairing a primary Herbal Sage `"Download Gemma 4
 * Vision (~2 GB)"` button, an active `"Cancel Download"` escape hatch during network streaming, and
 * a secondary `"Pick Local File (.litertlm)"` SAF `OutlinedButton`.
 *
 * @param runtimeState Active [AiRuntimeState] driving busy labels and cancel visibility.
 * @param onRequestDownloadApproval Opens [ModelDownloadApprovalDialog] with pre-flight telemetry.
 * @param onPickLocalFile Launches the zero-permission SAF `OpenDocument` `.litertlm` file picker.
 * @param onCancelDownload Cancels an in-flight network model download and cleans up staging state.
 * @param modifier Optional Compose [Modifier].
 */
@Composable
fun OnDeviceModelDownloadCtaRow(
  runtimeState: AiRuntimeState,
  onRequestDownloadApproval: () -> Unit,
  onPickLocalFile: () -> Unit,
  onCancelDownload: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  val isBusy = runtimeState.isBackgroundBusy
  // Distinguish HTTPS CDN streaming from local SAF file copies and OpenCL shader compilation.
  val isNetworkDownload = runtimeState is AiRuntimeState.Importing && runtimeState.isNetworkDownload
  val sageButtonColors = ButtonDefaults.buttonColors(
    containerColor = extendedColors.herbalSage, contentColor = MaterialTheme.colorScheme.onPrimary,
  )

  // Stack full-width 48.dp pills vertically so labels never truncate on 360dp screens at 1.35x.
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Button(
      onClick = onRequestDownloadApproval, enabled = !isBusy, shape = CircleShape, colors = sageButtonColors,
      modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
    ) {
      when {
        isNetworkDownload -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
        else -> Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
      }
      Spacer(modifier = Modifier.width(8.dp))
      val primaryLabel = if (isNetworkDownload) formatBusyImportButtonLabel(runtimeState, "Download Gemma 4 Vision (~2 GB)") else "Download Gemma 4 Vision (~2 GB)"
      Text(primaryLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }

    // Surface an explicit 48.dp Cancel Download button whenever a network .litertlm transfer is live.
    if (isNetworkDownload) {
      OutlinedButton(
        onClick = onCancelDownload, shape = CircleShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
      ) {
        Icon(Icons.Filled.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text("Cancel Download", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false)
      }
    }

    // Secondary SAF local picker remains available for offline side-loaded .litertlm packages.
    OutlinedButton(
      onClick = onPickLocalFile, enabled = !isBusy, shape = CircleShape,
      modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
    ) {
      if (isBusy && !isNetworkDownload) {
        CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
        Spacer(modifier = Modifier.width(8.dp))
      }
      val secondaryLabel = if (isBusy && !isNetworkDownload) formatBusyImportButtonLabel(runtimeState, "Pick Local File (.litertlm)") else "Pick Local File (.litertlm)"
      Text(secondaryLabel, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

@Composable
private fun DownloadTelemetrySummaryCard(approvalSpec: ModelDownloadApprovalSpec) {
  val extendedColors = KitchenTheme.extendedColors
  val preset = approvalSpec.preset
  // Highlight partial .part HTTP Range resume telemetry in Herbal Sage when prior bytes exist.
  val transferLabel = when {
    approvalSpec.isResumingPartialDownload ->
      "Resuming from ${formatMbOrGb(approvalSpec.existingPartialBytes)} (~${formatMbOrGb(approvalSpec.remainingDownloadBytes)} left)"
    else -> "~${formatMbOrGb(preset.approxSizeBytes)}"
  }
  val transferColor = if (approvalSpec.isResumingPartialDownload) extendedColors.onSageContainer else MaterialTheme.colorScheme.onSurface

  // Group license, transfer size, and StatFs storage verification inside a 1.dp stone card.
  Surface(
    shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest,
    border = BorderStroke(1.dp, extendedColors.borderSubtle), shadowElevation = 0.dp, modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Package & License", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${preset.fileName} • ${preset.licenseLabel}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
      }
      HorizontalDivider(color = extendedColors.borderSubtle)
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Transfer Size", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(transferLabel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = transferColor)
      }
      // Show live StatFs free-space check whenever availableFreeBytes was measured (>= 0L).
      if (approvalSpec.availableFreeBytes >= 0L) {
        HorizontalDivider(color = extendedColors.borderSubtle)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text("Storage Check", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          val storageColor = if (approvalSpec.hasEnoughStorage) extendedColors.onSageContainer else MaterialTheme.colorScheme.error
          Text("${formatMbOrGb(approvalSpec.availableFreeBytes)} free on device", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = storageColor)
          // Explain exact required free storage in Terracotta whenever headroom falls short.
          if (!approvalSpec.hasEnoughStorage) {
            Surface(
              shape = RoundedCornerShape(10.dp), color = extendedColors.terracottaContainer,
              border = BorderStroke(1.dp, extendedColors.terracotta.copy(alpha = 0.55f)), modifier = Modifier.fillMaxWidth(),
            ) {
              Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = extendedColors.onTerracottaContainer, modifier = Modifier.size(16.dp))
                Text(
                  text = "Need at least ${formatMbOrGb(preset.requiredFreeSpaceBytes)} free storage before downloading.",
                  style = MaterialTheme.typography.bodySmall, color = extendedColors.onTerracottaContainer,
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun DownloadNetworkNoticeBanner(networkStatus: NetworkMeteringStatus) {
  val extendedColors = KitchenTheme.extendedColors
  // Map Wi-Fi, metered mobile data, and offline states onto SavorAI warm semantic containers.
  when (networkStatus) {
    // Unmetered Wi-Fi reassures users that model weights live privately inside filesDir/models.
    NetworkMeteringStatus.UNMETERED_WIFI -> Surface(
      shape = RoundedCornerShape(12.dp), color = extendedColors.sageContainer,
      border = BorderStroke(1.dp, extendedColors.herbalSage.copy(alpha = 0.45f)), modifier = Modifier.fillMaxWidth(),
    ) {
      Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = extendedColors.onSageContainer, modifier = Modifier.padding(top = 1.dp).size(16.dp))
        Text(
          text = "Wi-Fi / unmetered connection ready • Stored privately in app storage (no duplicate Downloads copy).",
          style = MaterialTheme.typography.bodySmall, color = extendedColors.onSageContainer,
        )
      }
    }

    // Metered cellular warns about carrier data usage in Honey-Amber without blocking explicit consent.
    NetworkMeteringStatus.METERED_CELLULAR -> Surface(
      shape = RoundedCornerShape(12.dp), color = extendedColors.childSafetyContainer,
      border = BorderStroke(1.dp, extendedColors.childSafetyBorder), modifier = Modifier.fillMaxWidth(),
    ) {
      Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = extendedColors.onChildSafety, modifier = Modifier.padding(top = 1.dp).size(16.dp))
        Text(
          text = "You are on mobile data — downloading ~2 GB may incur carrier charges. Wi-Fi is recommended.",
          style = MaterialTheme.typography.bodySmall, color = extendedColors.onChildSafety,
        )
      }
    }

    // Offline warning disables Approve & Download until Wi-Fi or cellular connectivity returns.
    NetworkMeteringStatus.OFFLINE_OR_UNKNOWN -> Surface(
      shape = RoundedCornerShape(12.dp), color = extendedColors.terracottaContainer,
      border = BorderStroke(1.dp, extendedColors.terracotta.copy(alpha = 0.55f)), modifier = Modifier.fillMaxWidth(),
    ) {
      Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = extendedColors.onTerracottaContainer, modifier = Modifier.padding(top = 1.dp).size(16.dp))
        Text(
          text = "No active internet connection detected — connect to Wi-Fi before starting.",
          style = MaterialTheme.typography.bodySmall, color = extendedColors.onTerracottaContainer,
        )
      }
    }
  }
}

// Pure ASCII thousand-separator formatter ("2050" -> "2,050") deterministic on Android and JVM.
private fun formatAsciiThousands(value: Long): String {
  val digits = value.coerceAtLeast(0L).toString()
  if (digits.length <= 3) return digits
  val out = StringBuilder(digits.length + digits.length / 3)
  for (idx in digits.indices) {
    if (idx > 0 && (digits.length - idx) % 3 == 0) out.append(',')
    out.append(digits[idx])
  }
  return out.toString()
}

@Preview(name = "Approval Dialog — Wi-Fi Fresh Download", showBackground = true, widthDp = 412)
@Composable
private fun ModelDownloadApprovalWifiPreview() {
  // Showcases fresh E2B Wi-Fi approval telemetry alongside the idle 1-tap CTA row.
  FamilyKitchenBoardTheme(darkTheme = false) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      ModelDownloadApprovalCard(
        approvalSpec = ModelDownloadApprovalSpec(
          preset = DownloadableGemmaPreset.GEMMA_4_E2B_VISION,
          availableFreeBytes = 18_600L * BYTES_PER_MIB_DL,
          networkStatus = NetworkMeteringStatus.UNMETERED_WIFI,
        ),
        onSelectPreset = {}, onApproveDownload = {}, onDismiss = {},
      )
      OnDeviceModelDownloadCtaRow(
        runtimeState = AiRuntimeState.Uninitialized,
        onRequestDownloadApproval = {}, onPickLocalFile = {}, onCancelDownload = {},
      )
    }
  }
}

@Preview(name = "Approval Dialog — Cellular Metered Resume", showBackground = true, widthDp = 360)
@Composable
private fun ModelDownloadApprovalCellularResumePreview() {
  // Showcases compact 360dp partial .part resume telemetry, Honey-Amber warning, and Cancel CTA.
  FamilyKitchenBoardTheme(darkTheme = false) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      ModelDownloadApprovalCard(
        approvalSpec = ModelDownloadApprovalSpec(
          preset = DownloadableGemmaPreset.GEMMA_4_E2B_VISION,
          availableFreeBytes = 8_400L * BYTES_PER_MIB_DL,
          networkStatus = NetworkMeteringStatus.METERED_CELLULAR,
          existingPartialBytes = 640L * BYTES_PER_MIB_DL,
        ),
        onSelectPreset = {}, onApproveDownload = {}, onDismiss = {},
      )
      OnDeviceModelDownloadCtaRow(
        runtimeState = AiRuntimeState.Importing(
          fileName = "gemma-4-E2B-it.litertlm", progressFraction = 0.42f,
          copiedBytes = 860L * BYTES_PER_MIB_DL, totalBytes = 2_050L * BYTES_PER_MIB_DL,
          isNetworkDownload = true,
        ),
        onRequestDownloadApproval = {}, onPickLocalFile = {}, onCancelDownload = {},
      )
    }
  }
}

@Preview(name = "Approval Dialog — Counter Dark Storage Gate", showBackground = true, widthDp = 412)
@Composable
private fun ModelDownloadApprovalCounterDarkPreview() {
  // Showcases Ambient Counter Dark OLED contrast and Terracotta low-storage block on E4B preset.
  FamilyKitchenBoardTheme(darkTheme = true) {
    Surface(color = MaterialTheme.colorScheme.background) {
      ModelDownloadApprovalCard(
        approvalSpec = ModelDownloadApprovalSpec(
          preset = DownloadableGemmaPreset.GEMMA_4_E4B_VISION,
          availableFreeBytes = 1_800L * BYTES_PER_MIB_DL,
          networkStatus = NetworkMeteringStatus.UNMETERED_WIFI,
        ),
        onSelectPreset = {}, onApproveDownload = {}, onDismiss = {},
        modifier = Modifier.padding(12.dp),
      )
    }
  }
}
