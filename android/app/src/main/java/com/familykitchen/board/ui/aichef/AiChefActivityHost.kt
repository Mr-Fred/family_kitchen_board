package com.familykitchen.board.ui.aichef

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.familykitchen.board.data.KitchenImagePreprocessor
import com.familykitchen.board.domain.AiChatTurn
import com.familykitchen.board.domain.AiChefAction
import com.familykitchen.board.domain.CloudLlmConfig
import com.familykitchen.board.domain.EdgeDeviceProfile
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ports.VoiceInputPort
import com.familykitchen.board.ports.VoiceInputState
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Imperative-Shell ActivityResult launcher wrapper isolating Android Context, FileProvider,
// PickVisualMedia, TakePicture, RECORD_AUDIO permissions, and SAF OpenDocument away from the
// pure stateless AiChefScreen Composable.

// Supported local model file extensions accepted by SAF OpenDocument import.
private val ALLOWED_MODEL_EXTENSIONS: Set<String> = setOf("litertlm", "task", "bin")
private val SAF_OPEN_DOCUMENT_MIMES: Array<String> = arrayOf(
  listOf("application", "octet-stream").joinToString("/"),
  listOf("*", "*").joinToString("/"),
)

/**
 * Outer Activity-result wrapper Composable bridging scoped Android platform contracts
 * (`PickVisualMedia`, `RequestPermission` and `TakePicture` via `FileProvider`,
 * `RECORD_AUDIO` permission with bilingual voice input, and SAF `OpenDocument` model import) into
 * the pure stateless [AiChefScreen].
 */
@Composable
fun AiChefActivityHost(
  turnHistory: List<AiChatTurn>,
  runtimeState: AiRuntimeState,
  voiceState: VoiceInputState,
  activeDeviceProfile: EdgeDeviceProfile,
  attachedImagePath: String?,
  attachedImageLabel: String?,
  onAction: (AiChefAction) -> Unit,
  expiringSoonCount: Int = 3,
  plannedDinnerCount: Int = 2,
  savedRecipeIds: Set<String> = emptySet(),
  isContextBannerDismissed: Boolean = false,
  isGenerating: Boolean = false,
  isPreprocessingImage: Boolean = false,
  preprocessingImageLabel: String? = null,
  isGeneratingVisionTurn: Boolean = false,
  appliedSwapProposalIds: Set<String> = emptySet(),
  queuedIngredientProposalIds: Set<String> = emptySet(),
  confirmedVisualTurnIds: Set<String> = emptySet(),
  onOpenFamilyGoals: () -> Unit = {},
  onNavigateToBoard: () -> Unit = {},
  onNavigateToGroceryList: () -> Unit = {},
  currencySymbol: String = "₱",
  canUndoLastAgentAction: Boolean = false,
  lastAgentActionSummary: String? = null,
  onUndoLastAgentAction: () -> Unit = { onAction(AiChefAction.UndoLastAgentAction) },
  onDismissAgentActionBanner: () -> Unit = { onAction(AiChefAction.DismissAgentActionBanner) },
  onAdjustLastAgentAction: (() -> Unit)? = null,
  isModelSetupExpanded: Boolean? = null,
  onToggleModelSetup: (() -> Unit)? = null,
  cloudConfig: CloudLlmConfig = CloudLlmConfig(),
  isVerifyingCloudKey: Boolean = false,
  isRefreshingCloudModels: Boolean = false,
  currentHour24: Int = 12,
  todayDayIndex: Int = 0,
  catalog: List<IngredientCatalogItem> = emptyList(),
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  // Saveable UI state survives Activity recreation during external camera or SAF picker flows.
  var permissionNotice by rememberSaveable { mutableStateOf<String?>(null) }
  var pendingCaptureFilePath by rememberSaveable { mutableStateOf<String?>(null) }
  var pendingCaptureLabel by rememberSaveable { mutableStateOf<String?>(null) }
  var requestedVoiceLocale by rememberSaveable { mutableStateOf(VoiceInputPort.LOCALE_EN_US) }
  val contentUriPrefix = "content" + "://"

  // Decode gallery URIs off the main thread so thumbnails render immediately pre-copy.
  val uriThumbnailBitmap by produceState<ImageBitmap?>(initialValue = null, attachedImagePath) {
    val currentPath = attachedImagePath
    value = when {
      currentPath != null && currentPath.startsWith(contentUriPrefix) ->
        withContext(Dispatchers.IO) { chefHostDecodeContentUriThumbnailOrNull(context, Uri.parse(currentPath)) }
      else -> null
    }
  }

  // Zero-permission Android 12+ Photo Picker for gallery photos.
  val galleryPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickVisualMedia(),
  ) { pickedUri: Uri? ->
    if (pickedUri != null) {
      val fileName = chefHostQueryDisplayNameOrDefault(context, pickedUri, "gallery_photo.jpg")
      permissionNotice = null
      onAction(AiChefAction.AttachImage(sourceUriOrPath = pickedUri.toString(), displayLabel = fileName))
    }
  }

  // Full-resolution camera capture writing directly to scoped FileProvider URI.
  val takePictureLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.TakePicture(),
  ) { saved: Boolean ->
    val capturedPath = pendingCaptureFilePath
    val capturedName = pendingCaptureLabel ?: "camera_capture.jpg"
    pendingCaptureFilePath = null
    pendingCaptureLabel = null
    when {
      saved && !capturedPath.isNullOrBlank() -> {
        permissionNotice = null
        onAction(AiChefAction.AttachImage(sourceUriOrPath = capturedPath, displayLabel = capturedName))
      }
      !capturedPath.isNullOrBlank() -> {
        // Delete empty placeholder capture file immediately when user cancels the camera app.
        runCatching { File(capturedPath).delete() }
      }
    }
  }

  val launchScopedCameraCapture: () -> Unit = {
    runCatching {
      val (targetFile, contentUri) = chefHostCreateScopedCameraCaptureTarget(context)
      pendingCaptureFilePath = targetFile.absolutePath
      pendingCaptureLabel = targetFile.name
      takePictureLauncher.launch(contentUri)
    }.onFailure { err ->
      permissionNotice = "Unable to prepare scoped camera capture file: ${err.message ?: "storage error"}."
    }
  }

  // Request CAMERA only when user taps Camera, handling denial cleanly without launching capture.
  val cameraPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
  ) { isGranted: Boolean ->
    when {
      isGranted -> launchScopedCameraCapture()
      else -> {
        permissionNotice =
          "Camera permission was declined. Grant Camera access to take a photo, or use Photo Picker."
      }
    }
  }

  // Request RECORD_AUDIO only when user taps Voice Scribe, handling denial cleanly.
  val audioPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
  ) { isGranted: Boolean ->
    when {
      isGranted -> {
        permissionNotice = null
        onAction(AiChefAction.StartVoiceInput(requestedVoiceLocale))
      }
      else -> {
        permissionNotice =
          "Microphone permission was declined. You can still type prompts or tap any suggestion chip."
      }
    }
  }

  // Scoped SAF OpenDocument picker for .litertlm, .task, and .bin model packages.
  val openModelDocumentLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
  ) { docUri: Uri? ->
    if (docUri != null) {
      when {
        runtimeState.isBackgroundBusy -> {
          permissionNotice = "Model setup is already in progress — watch the indicator below."
        }
        else -> {
          runCatching {
            context.contentResolver.takePersistableUriPermission(docUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
          }
          val displayName = chefHostQueryDisplayNameOrDefault(context, docUri, "imported_model.litertlm")
          val ext = displayName.substringAfterLast('.', "").lowercase()
          when {
            ext in ALLOWED_MODEL_EXTENSIONS -> {
              permissionNotice = null
              onAction(AiChefAction.ImportModelUri(sourceUriString = docUri.toString(), displayName = displayName))
            }
            else -> {
              permissionNotice =
                "Unsupported file $displayName. Select a .litertlm, .task, or .bin model package."
            }
          }
        }
      }
    }
  }

  // Forward stateless state slices, platform launcher callbacks, and time and calendar context.
  AiChefScreen(
    turnHistory = turnHistory,
    runtimeState = runtimeState,
    voiceState = voiceState,
    activeDeviceProfile = activeDeviceProfile,
    attachedImagePath = attachedImagePath,
    attachedImageLabel = attachedImageLabel,
    onAction = onAction,
    onLaunchGalleryPicker = {
      galleryPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    },
    onLaunchCameraCapture = {
      // Check runtime CAMERA permission before allocating scoped FileProvider capture target.
      val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
      when {
        granted -> launchScopedCameraCapture()
        else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
      }
    },
    onRequestVoiceInput = { localeTag ->
      // Gate SpeechRecognizer session behind runtime RECORD_AUDIO consent.
      requestedVoiceLocale = localeTag
      val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
      when {
        granted -> {
          permissionNotice = null
          onAction(AiChefAction.StartVoiceInput(localeTag))
        }
        else -> audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
      }
    },
    onLaunchModelPicker = {
      // Guard against duplicate SAF document launches while an import or GPU load is active.
      when {
        runtimeState.isBackgroundBusy -> {
          permissionNotice = "Model setup is already in progress — watch the indicator below."
        }
        else -> openModelDocumentLauncher.launch(SAF_OPEN_DOCUMENT_MIMES)
      }
    },
    expiringSoonCount = expiringSoonCount,
    plannedDinnerCount = plannedDinnerCount,
    savedRecipeIds = savedRecipeIds,
    isContextBannerDismissed = isContextBannerDismissed,
    isGenerating = isGenerating,
    isPreprocessingImage = isPreprocessingImage,
    preprocessingImageLabel = preprocessingImageLabel,
    isGeneratingVisionTurn = isGeneratingVisionTurn,
    permissionNotice = permissionNotice,
    onDismissPermissionNotice = { permissionNotice = null },
    previewThumbnailBitmap = uriThumbnailBitmap,
    appliedSwapProposalIds = appliedSwapProposalIds,
    queuedIngredientProposalIds = queuedIngredientProposalIds,
    confirmedVisualTurnIds = confirmedVisualTurnIds,
    onOpenFamilyGoals = onOpenFamilyGoals,
    onNavigateToBoard = onNavigateToBoard,
    onNavigateToGroceryList = onNavigateToGroceryList,
    currencySymbol = currencySymbol,
    canUndoLastAgentAction = canUndoLastAgentAction,
    lastAgentActionSummary = lastAgentActionSummary,
    onUndoLastAgentAction = onUndoLastAgentAction,
    onDismissAgentActionBanner = onDismissAgentActionBanner,
    onAdjustLastAgentAction = onAdjustLastAgentAction,
    isModelSetupExpanded = isModelSetupExpanded,
    onToggleModelSetup = onToggleModelSetup,
    cloudConfig = cloudConfig,
    isVerifyingCloudKey = isVerifyingCloudKey,
    isRefreshingCloudModels = isRefreshingCloudModels,
    currentHour24 = currentHour24,
    todayDayIndex = todayDayIndex,
    catalog = catalog,
    modifier = modifier,
  )
}

// Provisions a pre-allocated timestamped JPEG inside scoped captures storage via FileProvider.
private fun chefHostCreateScopedCameraCaptureTarget(context: Context): Pair<File, Uri> =
  KitchenImagePreprocessor.createCameraCaptureTarget(context)

// Queries ContentResolver for a human-readable OpenableColumns.DISPLAY_NAME with safe fallback.
private fun chefHostQueryDisplayNameOrDefault(context: Context, uri: Uri, fallback: String): String =
  runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
      val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
      if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    }
  }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback

// Two-pass downsampled ContentResolver Bitmap decode keeping gallery photo previews under 256px.
private fun chefHostDecodeContentUriThumbnailOrNull(context: Context, uri: Uri): ImageBitmap? =
  runCatching {
    // First pass inspects bounds without allocating full camera pixel buffers.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val maxDim = max(bounds.outWidth, bounds.outHeight)
    if (maxDim <= 0) return null
    var sampleSize = 1
    while (maxDim / (sampleSize * 2) >= 256) sampleSize *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts)?.asImageBitmap() }
  }.getOrNull()
