package com.familykitchen.board.ui.aichef

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme
import kotlin.math.roundToInt

// Cohesive background progress banners, byte-level SAF transfer formatters, quiet single-line
// AgentStatusPill, and 1-tap AgentCommitUndoBar docked above the Sous-Chef composer.

private const val BYTES_PER_KIB: Long = 1024L
private const val BYTES_PER_MIB: Long = 1024L * 1024L

/**
 * Formats byte-level model copy or HTTPS `.part` download progress into a concise human-readable
 * label for `SavorAI` banners.
 *
 * Examples:
 * - `"640 MB / 2,050 MB (31%)"` when `totalBytes > 0` and `copiedBytes > 0`
 * - `"640 MB downloaded..."` / `"640 MB copied..."` when `totalBytes <= 0` and `copiedBytes > 0`
 * - `"Connecting to Hugging Face CDN..."` / `"Starting file copy..."` when `copiedBytes <= 0`
 *
 * @param copiedBytes Verified byte count written to the `<name>.part` staging file so far.
 * @param totalBytes Expected total byte length, or `<= 0` if unknown.
 * @param isNetworkDownload `true` when streaming an approved Hugging Face `.litertlm` download.
 * @return Human-readable transfer status string.
 */
fun formatByteProgressLabel(
  copiedBytes: Long,
  totalBytes: Long,
  isNetworkDownload: Boolean = false,
): String =
  when {
    // Reassure the user while HTTPS redirect negotiation or initial SAF read buffer starts.
    copiedBytes <= 0L && isNetworkDownload -> "Connecting to Hugging Face CDN..."
    copiedBytes <= 0L -> "Starting file copy..."
    // Determinate transfer when Content-Length or OpenableColumns.SIZE is known.
    totalBytes > 0L -> {
      val pct = ((copiedBytes.toDouble() / totalBytes.toDouble()) * 100.0)
        .roundToInt()
        .coerceIn(0, 100)
      "${formatStorageSizeLabel(copiedBytes)} / ${formatStorageSizeLabel(totalBytes)} ($pct%)"
    }
    // Indeterminate stream when total length was omitted: show live MB downloaded or copied.
    isNetworkDownload -> "${formatStorageSizeLabel(copiedBytes)} downloaded..."
    else -> "${formatStorageSizeLabel(copiedBytes)} copied..."
  }

/**
 * Formats a compact live status label rendered inside disabled model-import or download buttons
 * while [runtimeState] is busy (`"Downloading 42%..."`, `"Importing 42%..."`, `"Loading GPU..."`,
 * `"Scanning..."`).
 *
 * @param runtimeState Active [AiRuntimeState].
 * @param idleLabel Default button label displayed when no model background operation is running.
 * @return Status label matching the current background phase.
 */
fun formatBusyImportButtonLabel(runtimeState: AiRuntimeState, idleLabel: String): String =
  // Reflect real-time CDN/SAF transfer percentage or GPU shader initialization right inside CTAs.
  when (runtimeState) {
    is AiRuntimeState.Importing -> {
      val clamped = runtimeState.progressFraction.coerceIn(0f, 1f)
      val actionVerb = if (runtimeState.isNetworkDownload) "Downloading" else "Importing"
      // Prefer percentage when stream total is known; fall back to live MB transferred otherwise.
      when {
        runtimeState.totalBytes > 0L && runtimeState.copiedBytes > 0L ->
          "$actionVerb ${(clamped * 100f).roundToInt()}%..."
        clamped > 0f && runtimeState.totalBytes <= 0L && runtimeState.copiedBytes <= 0L ->
          "$actionVerb ${(clamped * 100f).roundToInt()}%..."
        runtimeState.copiedBytes > 0L ->
          "$actionVerb (${formatStorageSizeLabel(runtimeState.copiedBytes)})..."
        else ->
          "$actionVerb 0%..."
      }
    }
    is AiRuntimeState.Loading -> "Loading GPU..."
    AiRuntimeState.Discovering -> "Scanning..."
    AiRuntimeState.Uninitialized,
    is AiRuntimeState.Ready,
    is AiRuntimeState.Error,
    -> idleLabel
  }

/**
 * Unmistakable background progress card displaying a `22.dp` [CircularProgressIndicator], phase
 * headline, byte or OpenCL shader subtitle, optional `48.dp` `"Cancel"` button for active network
 * downloads, and Material 3 [LinearProgressIndicator] whenever [runtimeState] is busy.
 *
 * @param runtimeState Current [AiRuntimeState] driving spinner and progress bar mode.
 * @param modifier Optional Compose [Modifier].
 * @param onCancelDownload Optional callback cancelling an active network `.litertlm` download.
 */
@Composable
fun ModelOperationProgressBanner(
  runtimeState: AiRuntimeState,
  modifier: Modifier = Modifier,
  onCancelDownload: (() -> Unit)? = null,
) {
  // Collapse completely when the runtime is idle, Ready, or resting on an Error notice.
  if (!runtimeState.isBackgroundBusy) return

  val extendedColors = KitchenTheme.extendedColors
  val headline: String
  val subtitle: String
  // Map active network download, local SAF copy, GPU shader init, and storage scan copy cleanly.
  when (runtimeState) {
    is AiRuntimeState.Importing -> {
      // Differentiate Hugging Face HTTPS downloads from local SAF .litertlm file copies.
      val verb = if (runtimeState.isNetworkDownload) "Downloading" else "Importing"
      headline = "$verb ${runtimeState.fileName}…"
      subtitle = formatByteProgressLabel(
        copiedBytes = runtimeState.copiedBytes,
        totalBytes = runtimeState.totalBytes,
        isNetworkDownload = runtimeState.isNetworkDownload,
      )
    }
    is AiRuntimeState.Loading -> {
      // Reassure Galaxy S22 users while OpenCL shaders compile during first model load.
      headline = "Initializing ${runtimeState.modelName} on Galaxy GPU…"
      subtitle = "Mapping weights & compiling OpenCL shaders (typically 10–25s)."
    }
    AiRuntimeState.Discovering -> {
      // Zero-permission startup discovery checks filesDir/models and externalFilesDir/models.
      headline = "Scanning local storage for models…"
      subtitle = "Checking app-scoped model folders with zero storage permissions."
    }
    AiRuntimeState.Uninitialized,
    is AiRuntimeState.Ready,
    is AiRuntimeState.Error,
    -> return
  }

  Card(
    modifier = modifier.fillMaxWidth(),
    shape = KitchenTheme.shapes.inputDefault,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // Dedicated 22.dp spinner inside a soft sage circle makes background activity unmistakable.
        Box(
          modifier = Modifier.size(34.dp).clip(CircleShape).background(extendedColors.sageContainer),
          contentAlignment = Alignment.Center,
        ) {
          CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            strokeWidth = 2.4.dp,
            color = MaterialTheme.colorScheme.primary,
          )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = headline,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        // Surface a 48.dp Cancel action right in the banner row during active network downloads.
        if (runtimeState is AiRuntimeState.Importing && runtimeState.isNetworkDownload && onCancelDownload != null) {
          Spacer(modifier = Modifier.width(6.dp))
          TextButton(
            onClick = onCancelDownload,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
          ) {
            Text(
              text = "Cancel",
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.error,
              maxLines = 1,
              softWrap = false,
            )
          }
        }
      }

      // Use lambda determinate overload only when both totalBytes and copiedBytes are positive.
      when {
        runtimeState is AiRuntimeState.Importing &&
          runtimeState.totalBytes > 0L &&
          runtimeState.copiedBytes > 0L -> {
          val clampedFraction = runtimeState.progressFraction.coerceIn(0f, 1f)
          LinearProgressIndicator(
            progress = { clampedFraction },
            modifier = Modifier.fillMaxWidth(),
          )
        }
        else -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      }
    }
  }
}

/**
 * Docked bottom progress pill displayed while `KitchenImagePreprocessor` normalizes EXIF rotation
 * and downscales a camera capture or gallery selection to `<= 1280px` off the UI thread.
 *
 * @param imageLabel Optional filename badge of the photo being prepared.
 * @param modifier Optional Compose [Modifier].
 */
@Composable
fun ImagePreprocessingBanner(
  imageLabel: String?,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  val cleanLabel = imageLabel?.trim()?.takeIf { it.isNotEmpty() } ?: "photo"
  // Surfaces immediate visual feedback right above the composer while EXIF bitmap decode runs.
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = CircleShape,
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
    shadowElevation = 0.dp,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      CircularProgressIndicator(
        modifier = Modifier.size(16.dp),
        strokeWidth = 2.dp,
        color = MaterialTheme.colorScheme.primary,
      )
      Text(
        text = "Preparing '$cleanLabel' for scan…",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
    }
  }
}

/**
 * Quiet single-line `36.dp` transparent-agency status capsule rendered in the Sous-Chef feed while
 * an on-device text or multimodal photo scan turn is running.
 *
 * @param isVisionTurn `true` when inspecting an attached kitchen photo, `false` for pantry/plan text.
 * @param modifier Optional Compose [Modifier].
 */
@Composable
fun AgentStatusPill(
  isVisionTurn: Boolean,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  val statusCopy = when {
    isVisionTurn -> "Inspecting kitchen photo…"
    else -> "Checking pantry inventory…"
  }
  // Calm parchment capsule avoids heavy full-width cards and linear progress bars during turns.
  Surface(
    shape = CircleShape,
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
    shadowElevation = 0.dp,
    modifier = modifier.defaultMinSize(minHeight = 36.dp),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      // Compact 14.dp progress spinner matches single-line Sous-Chef agency typography.
      CircularProgressIndicator(
        modifier = Modifier.size(14.dp),
        strokeWidth = 1.8.dp,
        color = MaterialTheme.colorScheme.primary,
      )
      // Single-line status copy prevents vertical layout jump when turns begin or finish.
      Text(
        text = statusCopy,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/**
 * Backwards-compatible wrapper delegating turn-generation status rendering to [AgentStatusPill].
 *
 * @param isVisionTurn `true` when inspecting an attached kitchen photo, `false` for text.
 * @param modifier Optional Compose [Modifier].
 */
@Composable
fun AiGeneratingTurnCard(
  isVisionTurn: Boolean,
  modifier: Modifier = Modifier,
) {
  // Delegate directly to quiet single-line AgentStatusPill for consistent agency feedback.
  AgentStatusPill(isVisionTurn = isVisionTurn, modifier = modifier)
}

/**
 * Docked post-action agency bar (`[✓ Stocked 4 spices in Pantry   Undo · View]`) displayed above
 * the composer whenever `canUndoLastAgentAction` is `true`, exposing `48.dp` `Undo` and `View`
 * controls for 1-tap `O(1)` snapshot rollback or surface inspection.
 *
 * @param summary Concise human-readable summary of the committed Sous-Chef action.
 * @param onUndo Restores the pre-action `KitchenSnapshot` in a single `O(1)` batch write.
 * @param onViewOrAdjust Optional navigation callback opening the affected destination (`Pantry`, `Plan`, `Shop`).
 * @param onDismiss Dismisses the undo banner without reverting state.
 * @param viewLabel Short label for [onViewOrAdjust] (`"View"` default).
 * @param modifier Optional Compose [Modifier].
 */
@Composable
fun AgentCommitUndoBar(
  summary: String,
  onUndo: () -> Unit,
  onViewOrAdjust: (() -> Unit)? = null,
  onDismiss: () -> Unit = {},
  viewLabel: String = "View",
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Soft sage confirmation strip with 1.dp warm botanical border and 48.dp action touch targets.
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = extendedColors.sageContainer,
    border = BorderStroke(1.dp, extendedColors.herbalSage.copy(alpha = 0.45f)),
    shadowElevation = 0.dp,
    modifier = modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // Checkmark affirms O(1) atomic persistence completed locally on device.
      Icon(
        imageVector = Icons.Filled.Check,
        contentDescription = null,
        tint = extendedColors.onSageContainer,
        modifier = Modifier.size(16.dp),
      )
      Spacer(modifier = Modifier.width(8.dp))
      // Weighted summary leaves guaranteed horizontal room for 48.dp Undo and View buttons.
      Text(
        text = summary,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = extendedColors.onSageContainer,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f).padding(vertical = 10.dp),
      )
      // 1-tap Undo restores the pre-mutation snapshot in 1 atomic storage write.
      TextButton(
        onClick = onUndo,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
      ) {
        Text(
          text = "Undo",
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.primary,
          maxLines = 1,
          softWrap = false,
        )
      }
      // Show View target shortcut when destination is provided; otherwise render 48.dp dismiss icon.
      if (onViewOrAdjust != null) {
        TextButton(
          onClick = onViewOrAdjust,
          contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
          modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
        ) {
          Text(
            text = viewLabel,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = extendedColors.onSageContainer,
            maxLines = 1,
            softWrap = false,
          )
        }
      } else {
        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
          Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "Dismiss action summary",
            tint = extendedColors.onSageContainer,
            modifier = Modifier.size(16.dp),
          )
        }
      }
    }
  }
}

// Convert raw byte counts into grouped MB / KB / B strings without platform Locale formatting.
private fun formatStorageSizeLabel(bytes: Long): String {
  val nonNegative = bytes.coerceAtLeast(0L)
  return when {
    nonNegative >= BYTES_PER_MIB -> "${formatGroupedWholeNumber(nonNegative / BYTES_PER_MIB)} MB"
    nonNegative >= BYTES_PER_KIB -> "${formatGroupedWholeNumber(nonNegative / BYTES_PER_KIB)} KB"
    else -> "$nonNegative B"
  }
}

// Pure ASCII thousand-separator formatter ("1540" -> "1,540") deterministic on Android and JVM.
private fun formatGroupedWholeNumber(value: Long): String {
  val digits = value.coerceAtLeast(0L).toString()
  if (digits.length <= 3) return digits
  val builder = StringBuilder(digits.length + digits.length / 3)
  for (index in digits.indices) {
    if (index > 0 && (digits.length - index) % 3 == 0) {
      builder.append(',')
    }
    builder.append(digits[index])
  }
  return builder.toString()
}

@Preview(name = "Agency Status Pill & Undo Bar", showBackground = true, widthDp = 412)
@Composable
private fun AgencyPillsAndImportProgressPreview() {
  // Showcase quiet single-line AgentStatusPill, 1-tap AgentCommitUndoBar, and SAF import banner.
  FamilyKitchenBoardTheme {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      AgentStatusPill(isVisionTurn = true)
      AgentStatusPill(isVisionTurn = false)
      AgentCommitUndoBar(
        summary = "Stocked 4 spices in Pantry",
        onUndo = {},
        onViewOrAdjust = {},
      )
      ModelOperationProgressBanner(
        runtimeState = AiRuntimeState.Importing(
          fileName = "gemma-4-E2B-it.litertlm",
          progressFraction = 0.42f,
          copiedBytes = 860L * BYTES_PER_MIB,
          totalBytes = 2050L * BYTES_PER_MIB,
          isNetworkDownload = true,
        ),
        onCancelDownload = {},
      )
    }
  }
}
