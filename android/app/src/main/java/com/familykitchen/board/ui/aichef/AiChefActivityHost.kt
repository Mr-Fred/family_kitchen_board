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

private val ALLOWED_MODEL_EXTENSIONS: Set<String> = setOf("litertlm", "task", "bin")

/**
 * Outer Activity-result wrapper Composable bridging scoped Android platform contracts
 * (`PickVisualMedia`, `RequestPermission` + `TakePicture` via `FileProvider` in `cacheDir/captures/`,
 * `RECORD_AUDIO` permission + bilingual voice input, and SAF `OpenDocument` model import) into the
 * pure stateless [AiChefScreen].
 *
 * @param turnHistory Rolling 6-turn AI Chef conversation transcript.
 * @param runtimeState Current on-device LiteRT-LM / MediaPipe runtime state.
 * @param voiceState Current speech-to-text capture state (`en-US` / `fr-FR`).
 * @param activeDeviceProfile Selected universal Android RAM-tier on-device hardware profile.
 * @param attachedImagePath Currently staged image URI or preprocessed file path, or `null`.
 * @param attachedImageLabel Display filename for the staged image, or `null`.
 * @param onAction Unidirectional callback dispatching [AiChefAction] intents to `ViewModel`.
 * @param expiringSoonCount Live count of `EXPIRING_SOON` / `EXPIRED` pantry items.
 * @param plannedDinnerCount Live count of planned dinners across the weekly board.
 * @param savedRecipeIds Persisted bookmarked `recipeId`s from `KitchenSnapshot.savedRecipeIds`.
 * @param isContextBannerDismissed Legacy banner state flag preserved for caller compatibility.
 * @param isGenerating `true` while an inference turn is actively executing.
 * @param isPreprocessingImage `true` while EXIF-rotating and downscaling a newly selected photo.
 * @param preprocessingImageLabel Display filename for the photo currently being optimized.
 * @param isGeneratingVisionTurn `true` while [isGenerating] is executing an image+text GPU scan.
 * @param appliedSwapProposalIds Set of `proposalId`s whose meal swaps were already committed.
 * @param queuedIngredientProposalIds Set of `proposalId`s whose discovery items were queued.
 * @param confirmedVisualTurnIds Set of `turnId`s whose visual intake restocks were confirmed.
 * @param onOpenFamilyGoals Opens the Family Profile sheet to edit member goals or life events.
 * @param onNavigateToBoard Navigates to `Plan` after applying meal swaps.
 * @param onNavigateToGroceryList Navigates to `Shop` (`SmartGroceryScreen`) after queueing items.
 * @param currencySymbol Household currency symbol (`"₱"` default).
 * @param canUndoLastAgentAction `true` when an undoable Sous-Chef snapshot is recorded.
 * @param lastAgentActionSummary Concise human summary of the last committed Sous-Chef action.
 * @param onUndoLastAgentAction Restores the pre-action snapshot in 1 `O(1)` batch write.
 * @param onDismissAgentActionBanner Dismisses the docked undo summary bar.
 * @param onAdjustLastAgentAction Optional navigation callback jumping to the affected kitchen tab.
 * @param isModelSetupExpanded Optional externally controlled state for `EdgeAiRuntimeSetupPanel`.
 * @param onToggleModelSetup Optional callback toggling [isModelSetupExpanded].
 * @param cloudConfig Active [CloudLlmConfig] state slice for Android Keystore Cloud API keys.
 * @param isVerifyingCloudKey `true` while an HTTPS ping is verifying the active Cloud API key.
 * @param isRefreshingCloudModels `true` while syncing live `/models` for the active provider.
 * @param modifier Optional Compose [Modifier].
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
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var permissionNotice by rememberSaveable { mutableStateOf<String?>(null) }
  var pendingCaptureFilePath by rememberSaveable { mutableStateOf<String?>(null) }
  var pendingCaptureLabel by rememberSaveable { mutableStateOf<String?>(null) }
  var requestedVoiceLocale by rememberSaveable { mutableStateOf(VoiceInputPort.LOCALE_EN_US) }

  // Decode content:// gallery URIs off the main thread so thumbnails render immediately pre-copy.
  val uriThumbnailBitmap by produceState<ImageBitmap?>(initialValue = null, attachedImagePath) {
    val currentPath = attachedImagePath
    value = when {
      currentPath != null && currentPath.startsWith("content://") ->
        withContext(Dispatchers.IO) { decodeContentUriThumbnailOrNull(context, Uri.parse(currentPath)) }
      else -> null
    }
  }

  // Zero-permission Android 12+ Photo Picker (`PickVisualMedia.ImageOnly`) for gallery photos.
  val galleryPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickVisualMedia(),
  ) { pickedUri: Uri? ->
    if (pickedUri != null) {
      val fileName = queryDisplayNameOrDefault(context, pickedUri, "gallery_photo.jpg")
      permissionNotice = null
      onAction(AiChefAction.AttachImage(sourceUriOrPath = pickedUri.toString(), displayLabel = fileName))
    }
  }

  // Full-resolution camera capture writing directly to FileProvider content:// URI in cacheDir/captures/.
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
      val (targetFile, contentUri) = createScopedCameraCaptureTarget(context)
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
          "Camera permission was declined. Grant Camera access to take a photo, or use Photo Picker (zero permissions)."
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

  // Scoped SAF OpenDocument picker for .litertlm, .task, and .bin model packages (zero storage permissions).
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
          val displayName = queryDisplayNameOrDefault(context, docUri, "imported_model.litertlm")
          val ext = displayName.substringAfterLast('.', "").lowercase()
          when {
            ext in ALLOWED_MODEL_EXTENSIONS -> {
              permissionNotice = null
              onAction(AiChefAction.ImportModelUri(sourceUriString = docUri.toString(), displayName = displayName))
            }
            else -> {
              permissionNotice =
                "Unsupported file '$displayName'. Select a .litertlm (Gemma Vision/GPU), .task, or .bin model package."
            }
          }
        }
      }
    }
  }

  // Forward stateless state slices, platform launcher callbacks, and 1-tap Undo/Adjust hooks.
  AiChefScreen(
    turnHistory = turnHistory,
    runtimeState = runtimeState,
    voiceState = voiceState,
    activeDeviceProfile = activeDeviceProfile,
    attachedImagePath = attachedImagePath,
    attachedImageLabel = attachedImageLabel,
    onAction = onAction,
    onLaunchGalleryPicker = {
      // Launch scoped Android 12+ photo picker without requesting broad storage permissions.
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
      // Gate offline SpeechRecognizer behind runtime RECORD_AUDIO consent.
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
      // Block launching a duplicate SAF document picker while an import or GPU load is active.
      when {
        runtimeState.isBackgroundBusy -> {
          permissionNotice = "Model setup is already in progress — watch the indicator below."
        }
        else -> openModelDocumentLauncher.launch(arrayOf("application/octet-stream", "*/*"))
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
    modifier = modifier,
  )
}

// Provisions a pre-allocated timestamped JPEG inside cacheDir/captures/ via FileProvider authority.
private fun createScopedCameraCaptureTarget(context: Context): Pair<File, Uri> =
  KitchenImagePreprocessor.createCameraCaptureTarget(context)

// Queries ContentResolver for a human-readable OpenableColumns.DISPLAY_NAME with safe fallback.
private fun queryDisplayNameOrDefault(context: Context, uri: Uri, fallback: String): String =
  runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
      val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
      if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    }
  }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback

// Two-pass downsampled ContentResolver Bitmap decode keeping gallery photo previews under 256px.
private fun decodeContentUriThumbnailOrNull(context: Context, uri: Uri): ImageBitmap? =
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
