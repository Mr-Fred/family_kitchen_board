package com.familykitchen.board.ui.export

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.familykitchen.board.data.KitchenPdfExporter
import com.familykitchen.board.domain.ExportedKitchenDocument
import com.familykitchen.board.domain.PantryExpiryEngine
import com.familykitchen.board.domain.buildGroceryExportDocument
import com.familykitchen.board.domain.buildPantryExportDocument
import com.familykitchen.board.ports.MAX_KITCHEN_BACKUP_BYTES
import com.familykitchen.board.ports.buildSuggestedKitchenBackupFileName
import com.familykitchen.board.ports.encodeKitchenBackupJson
import com.familykitchen.board.ui.KitchenUiState
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Scoped Android Imperative-Shell export host wiring zero-permission SAF CreateDocument PDF writes
// and system ClipboardManager plain-text copies into stateless Pantry and Grocery Compose screens.

private const val EXPORT_KIND_GROCERY: String = "GROCERY"
private const val EXPORT_KIND_PANTRY: String = "PANTRY"

/**
 * Stateless UI callback bundle bridging one-tap plain-text clipboard copies and zero-permission SAF
 * `CreateDocument("application/pdf")` exports into `SmartGroceryScreen` and `SmartPantryScreen`.
 *
 * @property isExportingPdf `true` while a multi-page PDF stream is being rendered on `Dispatchers.IO`.
 * @property onCopyGroceryList Copies the formatted Smart Grocery List to the system clipboard.
 * @property onSaveGroceryPdf Opens the system Save-As picker and writes the Grocery List PDF.
 * @property onCopyPantryInventory Copies the formatted Smart Pantry inventory to the clipboard.
 * @property onSavePantryPdf Opens the system Save-As picker and writes the Pantry Inventory PDF.
 * @property isBackingUpOrRestoringJson `true` while streaming a portable `.json` backup or restore.
 * @property onSaveKitchenBackup Opens the system Save-As picker to export a portable `.json` backup.
 * @property onRestoreKitchenBackup Opens the system file picker to restore a `.json` kitchen backup.
 */
@Immutable
data class KitchenExportController(
  val isExportingPdf: Boolean = false,
  val onCopyGroceryList: () -> Unit = {},
  val onSaveGroceryPdf: () -> Unit = {},
  val onCopyPantryInventory: () -> Unit = {},
  val onSavePantryPdf: () -> Unit = {},
  val isBackingUpOrRestoringJson: Boolean = false,
  val onSaveKitchenBackup: () -> Unit = {},
  val onRestoreKitchenBackup: () -> Unit = {},
)

/**
 * Remembers a lifecycle-aware [KitchenExportController] backed by Android's `ClipboardManager`,
 * `ActivityResultContracts.CreateDocument("application/pdf")`, and portable `.json` backup/restore
 * contracts with zero storage permissions.
 *
 * Returns a no-op [KitchenExportController] automatically when `LocalInspectionMode.current` is
 * `true` so Android Studio `@Preview`s render without an `ActivityResultRegistryOwner`.
 *
 * @param uiState Current immutable [KitchenUiState] observed from `KitchenBoardViewModel`.
 * @param onPostToast Callback surfacing confirmation or diagnostic export feedback banners.
 * @param onRestoreBackupJson Callback validating and committing a restored `.json` backup payload.
 * @return Immutable [KitchenExportController] bound to the latest household state.
 */
@Composable
fun rememberKitchenDocumentExportController(
  uiState: KitchenUiState,
  onPostToast: (String) -> Unit,
  onRestoreBackupJson: (String) -> Unit = {},
): KitchenExportController {
  // Bypass ActivityResult registration during headless Compose @Preview inspection.
  if (LocalInspectionMode.current) return KitchenExportController()

  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  // Capture latest state & toast lambdas so SAF callbacks never read stale snapshot closures.
  val latestUiState by rememberUpdatedState(uiState)
  val latestOnPostToast by rememberUpdatedState(onPostToast)
  val latestOnRestoreBackupJson by rememberUpdatedState(onRestoreBackupJson)

  // Hold the prepared document in memory and persist kind name across device orientation changes.
  var pendingPdfDocument by remember { mutableStateOf<ExportedKitchenDocument?>(null) }
  var pendingExportKind by rememberSaveable { mutableStateOf<String?>(null) }
  var isExportingPdf by remember { mutableStateOf(false) }
  var isBackingUpJson by remember { mutableStateOf(false) }

  // Register scoped SAF document creator requiring zero READ/WRITE_EXTERNAL_STORAGE permissions.
  val createPdfLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("application/pdf"),
  ) { uri ->
    // User dismissed the system file picker; clear staged state silently without error noise.
    if (uri == null) {
      pendingPdfDocument = null
      pendingExportKind = null
      return@rememberLauncherForActivityResult
    }
    // Recover prepared document (rebuilding from latestUiState if Activity recreated mid-picker).
    val doc = pendingPdfDocument ?: rebuildExportDocument(pendingExportKind, latestUiState)
    if (doc == null) {
      pendingExportKind = null
      latestOnPostToast("Unable to save PDF: export session expired.")
      return@rememberLauncherForActivityResult
    }
    isExportingPdf = true
    coroutineScope.launch {
      try {
        // Reject unexpected URI schemes before opening a writable ContentResolver stream.
        val scheme = uri.scheme?.lowercase()
        require(scheme == "content" || scheme == "file") {
          "Unsupported destination URI scheme."
        }
        withContext(Dispatchers.IO) {
          // Open truncated write stream via ContentResolver and close deterministically via .use.
          val outStream = context.contentResolver.openOutputStream(uri, "w")
            ?: throw IOException("Destination output stream unavailable.")
          outStream.use { stream ->
            KitchenPdfExporter.writeKitchenPdfToStream(doc, stream)
          }
        }
        latestOnPostToast("Saved PDF: ${doc.suggestedFileName}")
      } catch (cancel: CancellationException) {
        // Preserve structured coroutine cancellation if the hosting screen leaves composition.
        throw cancel
      } catch (err: Throwable) {
        val detail = err.message?.trim()?.takeIf { it.isNotEmpty() } ?: "storage write failed."
        val message = when {
          detail.startsWith("Unable to save PDF", ignoreCase = true) -> detail
          else -> "Unable to save PDF: $detail"
        }
        latestOnPostToast(message)
      } finally {
        // Always release busy spinner and staged document reference even on IO failure.
        isExportingPdf = false
        pendingPdfDocument = null
        pendingExportKind = null
      }
    }
  }

  // Scoped SAF CreateDocument("application/json") launcher exporting zero-secret KitchenSnapshot.
  val createBackupJsonLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("application/json"),
  ) { uri: Uri? ->
    if (uri == null) return@rememberLauncherForActivityResult
    isBackingUpJson = true
    coroutineScope.launch {
      try {
        val state = latestUiState
        val suggestedName = buildSuggestedKitchenBackupFileName(
          householdName = state.snapshot.householdProfile.householdName,
          todayIso = state.realTodayIso,
        )
        withContext(Dispatchers.IO) {
          val payloadBytes = encodeKitchenBackupJson(
            snapshot = state.snapshot,
            exportedAtIso = state.realTodayIso,
          ).toByteArray(Charsets.UTF_8)
          val outStream = context.contentResolver.openOutputStream(uri, "w")
            ?: throw IOException("Backup destination stream unavailable.")
          outStream.use { stream ->
            stream.write(payloadBytes)
            stream.flush()
          }
        }
        latestOnPostToast("Saved kitchen backup: $suggestedName")
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (err: Throwable) {
        val detail = err.message?.trim()?.takeIf { it.isNotEmpty() } ?: "storage write failed."
        latestOnPostToast("Unable to save backup: $detail")
      } finally {
        isBackingUpJson = false
      }
    }
  }

  // Scoped SAF OpenDocument launcher reading bounded <= 8 MB UTF-8 JSON backup files safely.
  val openBackupJsonLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
  ) { uri: Uri? ->
    if (uri == null) return@rememberLauncherForActivityResult
    isBackingUpJson = true
    coroutineScope.launch {
      try {
        val rawJson = withContext(Dispatchers.IO) {
          readBoundedBackupUtf8FromUri(context, uri)
        }
        latestOnRestoreBackupJson(rawJson)
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (err: Throwable) {
        val detail = err.message?.trim()?.takeIf { it.isNotEmpty() } ?: "unable to read file."
        latestOnPostToast("Unable to restore backup: $detail")
      } finally {
        isBackingUpJson = false
      }
    }
  }

  // Memoize controller wrapper while reading live State-backed snapshots inside each action lambda.
  return remember(isExportingPdf, isBackingUpJson, context, createPdfLauncher, createBackupJsonLauncher, openBackupJsonLauncher) {
    KitchenExportController(
      isExportingPdf = isExportingPdf,
      onCopyGroceryList = {
        val doc = buildCurrentGroceryDocument(latestUiState)
        copyDocumentToClipboard(
          context = context,
          document = doc,
          successToast = "Copied shopping list to clipboard.",
          onPostToast = latestOnPostToast,
        )
      },
      onSaveGroceryPdf = {
        // Guard against overlapping SAF launches while a previous PDF render is still flushing.
        if (!isExportingPdf) {
          val doc = buildCurrentGroceryDocument(latestUiState)
          pendingPdfDocument = doc
          pendingExportKind = EXPORT_KIND_GROCERY
          try {
            createPdfLauncher.launch(doc.suggestedFileName)
          } catch (err: Throwable) {
            pendingPdfDocument = null
            pendingExportKind = null
            val detail = err.message?.trim()?.takeIf { it.isNotEmpty() } ?: "picker unavailable."
            latestOnPostToast("Unable to save PDF: $detail")
          }
        }
      },
      onCopyPantryInventory = {
        val doc = buildCurrentPantryDocument(latestUiState)
        copyDocumentToClipboard(
          context = context,
          document = doc,
          successToast = "Copied pantry inventory to clipboard.",
          onPostToast = latestOnPostToast,
        )
      },
      onSavePantryPdf = {
        // Stage pantry export snapshot before opening system Save-As sheet with suggested filename.
        if (!isExportingPdf) {
          val doc = buildCurrentPantryDocument(latestUiState)
          pendingPdfDocument = doc
          pendingExportKind = EXPORT_KIND_PANTRY
          try {
            createPdfLauncher.launch(doc.suggestedFileName)
          } catch (err: Throwable) {
            pendingPdfDocument = null
            pendingExportKind = null
            val detail = err.message?.trim()?.takeIf { it.isNotEmpty() } ?: "picker unavailable."
            latestOnPostToast("Unable to save PDF: $detail")
          }
        }
      },
      isBackingUpOrRestoringJson = isBackingUpJson,
      onSaveKitchenBackup = {
        if (!isBackingUpJson) {
          val state = latestUiState
          val fileName = buildSuggestedKitchenBackupFileName(
            householdName = state.snapshot.householdProfile.householdName,
            todayIso = state.realTodayIso,
          )
          try {
            createBackupJsonLauncher.launch(fileName)
          } catch (err: Throwable) {
            val detail = err.message?.trim()?.takeIf { it.isNotEmpty() } ?: "picker unavailable."
            latestOnPostToast("Unable to open backup Save picker: $detail")
          }
        }
      },
      onRestoreKitchenBackup = {
        if (!isBackingUpJson) {
          try {
            // Accept application/json and fallback MIME tags used by OEM DownloadManagers on .json.
            openBackupJsonLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
          } catch (err: Throwable) {
            val detail = err.message?.trim()?.takeIf { it.isNotEmpty() } ?: "picker unavailable."
            latestOnPostToast("Unable to open backup file picker: $detail")
          }
        }
      },
    )
  }
}

/**
 * Tactile 2-pill equal-weight export bar offering `48.dp` minimum touch targets for one-tap
 * plain-text clipboard copy and scoped `Save PDF` document generation.
 *
 * Calibrated for compact `360dp` Android viewports up to `1.35x` system font scaling using
 * single-line ellipsis (`maxLines = 1`, `softWrap = false`, `TextOverflow.Ellipsis`) and crisp
 * `1.dp` warm stone borders with `0.dp` shadow elevation.
 *
 * @param copyLabel Label rendered on the leading clipboard pill (`"Copy List"` / `"Copy Pantry"`).
 * @param pdfLabel Label rendered on the trailing PDF pill when idle (`"Save PDF"` default).
 * @param isExportingPdf `true` while a PDF write is running, disabling repeat launches.
 * @param onCopyClick Invoked when the user taps the clipboard pill.
 * @param onSavePdfClick Invoked when the user taps the PDF export pill.
 * @param modifier Optional Compose [Modifier].
 */
@Composable
fun KitchenExportActionRow(
  copyLabel: String,
  pdfLabel: String = "Save PDF",
  isExportingPdf: Boolean = false,
  onCopyClick: () -> Unit,
  onSavePdfClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // Left pill: one-tap plain-text clipboard copy accented with botanical Herbal Sage.
    Surface(
      onClick = onCopyClick,
      shape = CircleShape,
      color = extendedColors.parchment,
      border = BorderStroke(1.dp, extendedColors.borderSubtle),
      shadowElevation = 0.dp,
      modifier = Modifier
        .weight(1f)
        .defaultMinSize(minHeight = 48.dp),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .defaultMinSize(minHeight = 48.dp)
          .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = Icons.Filled.ContentCopy,
          contentDescription = null,
          tint = extendedColors.herbalSage,
          modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        // Use weight(1f, fill = false) so icon+label stay centered yet ellipsize at 1.35x scale.
        Text(
          text = copyLabel,
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          softWrap = false,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false),
        )
      }
    }

    // Right pill: zero-permission SAF PDF export accented with culinary Terracotta.
    Surface(
      onClick = onSavePdfClick,
      enabled = !isExportingPdf,
      shape = CircleShape,
      color = extendedColors.parchment,
      border = BorderStroke(1.dp, extendedColors.borderSubtle),
      shadowElevation = 0.dp,
      modifier = Modifier
        .weight(1f)
        .defaultMinSize(minHeight = 48.dp),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .defaultMinSize(minHeight = 48.dp)
          .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // Swap Terracotta PDF icon for a 15.dp progress indicator while flushing pages on IO.
        when {
          isExportingPdf -> {
            CircularProgressIndicator(
              modifier = Modifier.size(15.dp),
              strokeWidth = 2.dp,
              color = extendedColors.terracotta,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = "Saving PDF…",
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              softWrap = false,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f, fill = false),
            )
          }
          else -> {
            Icon(
              imageVector = Icons.Filled.PictureAsPdf,
              contentDescription = null,
              tint = extendedColors.terracotta,
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = pdfLabel,
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
              softWrap = false,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f, fill = false),
            )
          }
        }
      }
    }
  }
}

// Reconstructs the staged export document if the Activity recreated while SAF picker was open.
private fun rebuildExportDocument(
  exportKind: String?,
  uiState: KitchenUiState,
): ExportedKitchenDocument? = when (exportKind) {
  EXPORT_KIND_GROCERY -> buildCurrentGroceryDocument(uiState)
  EXPORT_KIND_PANTRY -> buildCurrentPantryDocument(uiState)
  else -> null
}

// Builds the Grocery List export model respecting active sort mode, checkmarks, and PHP ₱ totals.
private fun buildCurrentGroceryDocument(uiState: KitchenUiState): ExportedKitchenDocument =
  buildGroceryExportDocument(
    groceryDiff = uiState.weeklyGroceryDiff,
    checkedGroceryItemIds = uiState.snapshot.checkedGroceryItemIds,
    customGroceryItems = uiState.snapshot.customGroceryItems,
    sortMode = uiState.snapshot.grocerySortMode,
    householdProfile = uiState.snapshot.householdProfile,
  )

// Builds the Pantry Inventory export model using precomputed or fallback shelf-life telemetry.
private fun buildCurrentPantryDocument(uiState: KitchenUiState): ExportedKitchenDocument {
  val snapshot = uiState.snapshot
  val dashboard = when {
    uiState.pantryExpiryDashboard.totalItems == 0 && snapshot.pantryEntries.isNotEmpty() ->
      PantryExpiryEngine.evaluatePantryExpiryDashboard(
        pantry = snapshot.pantryEntries,
        catalog = snapshot.ingredients,
        recipes = snapshot.recipes,
        householdAllergens = snapshot.householdProfile.householdAllergens,
        rescuedMealsCount = snapshot.rescuedExpiringMealsCount,
      )
    else -> uiState.pantryExpiryDashboard
  }
  return buildPantryExportDocument(
    catalog = snapshot.ingredients,
    pantryEntries = snapshot.pantryEntries,
    dashboard = dashboard,
    householdProfile = snapshot.householdProfile,
  )
}

// Writes plain-text culinary checklist output to Android ClipboardManager safely.
private fun copyDocumentToClipboard(
  context: Context,
  document: ExportedKitchenDocument,
  successToast: String,
  onPostToast: (String) -> Unit,
) {
  val clipboard = context.getSystemService(ClipboardManager::class.java)
  if (clipboard == null) {
    onPostToast("Unable to access clipboard on this device.")
    return
  }
  try {
    val clip = ClipData.newPlainText(document.title, document.clipboardText)
    clipboard.setPrimaryClip(clip)
    onPostToast(successToast)
  } catch (err: Throwable) {
    val detail = err.message?.trim()?.takeIf { it.isNotEmpty() } ?: "clipboard unavailable."
    onPostToast("Unable to copy to clipboard: $detail")
  }
}

// Streams at most MAX_KITCHEN_BACKUP_BYTES from a SAF Uri so large binaries never exhaust heap RAM.
private fun readBoundedBackupUtf8FromUri(context: Context, uri: Uri): String {
  val declaredSize = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
      if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
    } ?: -1L
  }.getOrDefault(-1L)
  if (declaredSize > MAX_KITCHEN_BACKUP_BYTES) {
    throw IOException("Selected file exceeds the 8 MB kitchen backup limit.")
  }
  val inputStream = context.contentResolver.openInputStream(uri)
    ?: throw IOException("Unable to open selected backup file.")
  val bytes = inputStream.use { input ->
    val out = ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    var total = 0
    while (true) {
      val n = input.read(buf)
      if (n < 0) break
      if (n > 0) {
        total += n
        if (total > MAX_KITCHEN_BACKUP_BYTES) {
          throw IOException("Selected file exceeds the 8 MB kitchen backup limit.")
        }
        out.write(buf, 0, n)
      }
    }
    out.toByteArray()
  }
  return String(bytes, Charsets.UTF_8)
}

/** Warm Daylight `@Preview` showcasing idle and active PDF export states of [KitchenExportActionRow]. */
@Preview(name = "KitchenExportActionRow — Warm Daylight", showBackground = true, widthDp = 380)
@Composable
private fun KitchenExportActionRowDaylightPreview() {
  // Verify both Grocery idle state and Pantry active PDF progress state in sunlit Warm Daylight.
  FamilyKitchenBoardTheme(darkTheme = false) {
    Surface(color = MaterialTheme.colorScheme.background) {
      Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        KitchenExportActionRow(
          copyLabel = "Copy List",
          pdfLabel = "Save PDF",
          isExportingPdf = false,
          onCopyClick = {},
          onSavePdfClick = {},
        )
        KitchenExportActionRow(
          copyLabel = "Copy Pantry",
          pdfLabel = "Save PDF",
          isExportingPdf = true,
          onCopyClick = {},
          onSavePdfClick = {},
        )
      }
    }
  }
}

/** Counter Dark `@Preview` verifying compact `360dp` `1.35x` font-scale legibility. */
@Preview(
  name = "KitchenExportActionRow — Counter Dark 1.35x",
  showBackground = true,
  widthDp = 360,
  fontScale = 1.35f,
)
@Composable
private fun KitchenExportActionRowCounterDarkPreview() {
  // Confirm zero text wrapping or icon clipping at 360dp width and 1.35x accessibility scale.
  FamilyKitchenBoardTheme(darkTheme = true) {
    Surface(color = MaterialTheme.colorScheme.background) {
      Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        KitchenExportActionRow(
          copyLabel = "Copy Pantry",
          pdfLabel = "Save PDF",
          isExportingPdf = false,
          onCopyClick = {},
          onSavePdfClick = {},
        )
      }
    }
  }
}
