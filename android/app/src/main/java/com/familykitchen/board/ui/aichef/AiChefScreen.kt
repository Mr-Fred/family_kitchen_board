package com.familykitchen.board.ui.aichef

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.AiChatRole
import com.familykitchen.board.domain.AiChatTurn
import com.familykitchen.board.domain.AiChefAction
import com.familykitchen.board.domain.AiExecutionMode
import com.familykitchen.board.domain.AiMenuOptimizationProposal
import com.familykitchen.board.domain.AiRichRecipeCard
import com.familykitchen.board.domain.BaseUnit
import com.familykitchen.board.domain.BomLine
import com.familykitchen.board.domain.CloudLlmConfig
import com.familykitchen.board.domain.CookOwner
import com.familykitchen.board.domain.EdgeDeviceProfile
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.IngredientCategory
import com.familykitchen.board.domain.IngredientTier
import com.familykitchen.board.domain.MealSlotType
import com.familykitchen.board.domain.NutritionFocusTag
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.RecipePantryMatchSummary
import com.familykitchen.board.domain.StorageLocation
import com.familykitchen.board.domain.VisualIntakeResult
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ports.RuntimeCapabilities
import com.familykitchen.board.ports.VoiceInputPort
import com.familykitchen.board.ports.VoiceInputState
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme
import com.familykitchen.board.ui.theme.SavorBrandBadge

// SavorAI Sous-Chef Screen featuring a decluttered conversational viewport, editorial welcome
// card, quiet single-line AgentStatusPill, interactive structured artifacts, 1-tap Undo bar,
// and a 2-tier persistent bottom SavorAgentComposer eliminating 360dp wrap & cursor glitches.

// Quick-action suggestion chips docked right above the composer when idle.
private val SAVOR_QUICK_ACTION_PROMPTS: List<String> = listOf(
  "Rescue expiring items", "Scan fridge or spices", "Swap tonight's dinner",
  "20-min kids breakfast", "Import recipe text",
)

/**
 * Stateless Material 3 Sous-Chef screen (`Chef` tab) featuring a decluttered chat canvas,
 * transparent agency pills, structured interactive recipe/intake artifacts, and a 2-tier
 * persistent bottom agent bar (`SavorAgentComposer`).
 */
@Composable
fun AiChefScreen(
  turnHistory: List<AiChatTurn>,
  runtimeState: AiRuntimeState,
  voiceState: VoiceInputState,
  activeDeviceProfile: EdgeDeviceProfile,
  attachedImagePath: String?,
  attachedImageLabel: String?,
  onAction: (AiChefAction) -> Unit,
  onLaunchGalleryPicker: () -> Unit,
  onLaunchCameraCapture: () -> Unit,
  onRequestVoiceInput: (languageTag: String) -> Unit,
  onLaunchModelPicker: () -> Unit,
  expiringSoonCount: Int = 3,
  plannedDinnerCount: Int = 2,
  savedRecipeIds: Set<String> = emptySet(),
  isContextBannerDismissed: Boolean = false,
  isGenerating: Boolean = false,
  isPreprocessingImage: Boolean = false,
  preprocessingImageLabel: String? = null,
  isGeneratingVisionTurn: Boolean = false,
  permissionNotice: String? = null,
  onDismissPermissionNotice: () -> Unit = {},
  previewThumbnailBitmap: ImageBitmap? = null,
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
  var composerText by rememberSaveable { mutableStateOf("") }
  var internalSetupExpanded by rememberSaveable { mutableStateOf(false) }
  var selectedVoiceLocale by rememberSaveable { mutableStateOf(VoiceInputPort.LOCALE_EN_US) }
  var scrollToSetupTrigger by remember { mutableIntStateOf(0) }
  val listState = rememberLazyListState()
  val isImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
  // Support both top-bar controlled setup expansion and standalone @Preview toggle state.
  val setupExpanded = isModelSetupExpanded ?: internalSetupExpanded
  val toggleSetup: () -> Unit = onToggleModelSetup ?: { internalSetupExpanded = !internalSetupExpanded }
  // Defensively disambiguate any legacy duplicate turnIds so LazyColumn keys never collide.
  val safeTurnHistory = remember(turnHistory) { ensureUniqueTurnIds(turnHistory) }

  // Smoothly bring item 0 (AI Model & Cloud Key Setup) into view after 1-tap Cloud Vision switch.
  LaunchedEffect(scrollToSetupTrigger) {
    if (scrollToSetupTrigger > 0) listState.animateScrollToItem(0)
  }

  // Populate composer text automatically whenever SpeechRecognizer finalizes an utterance.
  LaunchedEffect(voiceState) {
    if (voiceState is VoiceInputState.Result && voiceState.transcript.isNotBlank()) {
      composerText = voiceState.transcript
    }
  }

  // Keep the latest chat turn and status pill visible above the soft keyboard.
  LaunchedEffect(safeTurnHistory.size, isGenerating, isImeVisible) {
    if (safeTurnHistory.isNotEmpty() || isGenerating) {
      val totalItems = listState.layoutInfo.totalItemsCount
      if (totalItems > 0) listState.animateScrollToItem(totalItems - 1)
    }
  }

  val hasAttachedPhoto = !attachedImagePath.isNullOrBlank()
  val visionSupported = isRuntimeVisionCapable(runtimeState)
  // Hard UI Vision Gate: block Send while a photo is attached on any non-vision runtime.
  val blockedByVisionGate = hasAttachedPhoto && !visionSupported
  val canSubmitTurn = !isGenerating && !isPreprocessingImage && !blockedByVisionGate &&
    (composerText.isNotBlank() || hasAttachedPhoto)
  val submitCurrentPrompt = {
    if (canSubmitTurn) {
      // Pass explicit per-turn attachedImagePath rather than mutable global state.
      onAction(AiChefAction.SubmitPrompt(composerText.trim(), attachedImagePath))
      composerText = ""
    }
  }

  Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(modifier = Modifier.fillMaxSize()) {
      // Main scrollable Sous-Chef transcript kept free of top utility & prompt chrome clutter.
      LazyColumn(
        state = listState,
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        // Render AI setup panel when toggled from top bar / chip, during SAF copy, or cloud sync.
        if (setupExpanded || runtimeState.isBackgroundBusy || isVerifyingCloudKey || isRefreshingCloudModels) {
          item(key = "savor_edge_ai_setup") {
            EdgeAiRuntimeSetupPanel(
              runtimeState = runtimeState, activeProfile = activeDeviceProfile, isExpanded = setupExpanded,
              onToggleExpanded = toggleSetup,
              onSelectProfile = { profile -> onAction(AiChefAction.SelectDeviceProfile(profile)) },
              onImportModelClick = onLaunchModelPicker,
              cloudConfig = cloudConfig, isVerifyingCloudKey = isVerifyingCloudKey,
              isRefreshingCloudModels = isRefreshingCloudModels, onAction = onAction,
            )
          }
        }

        // Surface camera, mic, or SAF file-extension guidance banners only when relevant.
        if (!permissionNotice.isNullOrBlank()) {
          item(key = "savor_permission_notice") {
            PermissionFeedbackBanner(message = permissionNotice, onDismiss = onDismissPermissionNotice)
          }
        }

        // Editorial empty-state welcome card with 1-line live context pill and AI Setup chip.
        if (safeTurnHistory.isEmpty()) {
          item(key = "savor_welcome_card") {
            SavorWelcomeBanner(
              expiringSoonCount, plannedDinnerCount, runtimeState, setupExpanded, toggleSetup, cloudConfig,
            )
          }
        }

        // Conversational Feed: warm charcoal User bubbles + Restaurant-avatar Sous-Chef artifacts.
        items(items = safeTurnHistory, key = { "turn_${it.turnId}" }) { turn ->
          SavorChatTurnView(
            turn = turn, savedRecipeIds = savedRecipeIds,
            isSwapsApplied = turn.proposal?.proposalId in appliedSwapProposalIds,
            isIngredientsQueued = turn.proposal?.proposalId in queuedIngredientProposalIds,
            isVisualRestockConfirmed = turn.turnId in confirmedVisualTurnIds,
            currencySymbol = currencySymbol, onAction = onAction,
            onQuickPromptFill = { prompt ->
              composerText = prompt
              onAction(AiChefAction.SelectQuickPrompt(prompt))
            },
            onNavigateToBoard = onNavigateToBoard, onNavigateToGroceryList = onNavigateToGroceryList,
          )
        }

        // Quiet single-line 36.dp AgentStatusPill replaces bulky generating cards during inference.
        if (isGenerating) {
          item(key = "savor_generating") { AgentStatusPill(isVisionTurn = isGeneratingVisionTurn) }
        }
      }

      // Docked Bottom Zone: Undo Bar, Vision Gate, Progress, Quick-Action Chips, & 2-Tier Composer.
      Column(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        // 1-tap Undo & View bar with smooth fade/shrink exit docked above composer.
        val isUndoVisible = canUndoLastAgentAction && !lastAgentActionSummary.isNullOrBlank()
        var lastUndoSummary by remember { mutableStateOf(lastAgentActionSummary.orEmpty()) }
        if (!lastAgentActionSummary.isNullOrBlank()) lastUndoSummary = lastAgentActionSummary
        AnimatedVisibility(
          visible = isUndoVisible,
          enter = fadeIn(tween(180)) + expandVertically(tween(200)),
          exit = fadeOut(tween(220)) + shrinkVertically(tween(220)),
        ) {
          AgentCommitUndoBar(
            summary = lastUndoSummary, onUndo = onUndoLastAgentAction,
            onViewOrAdjust = onAdjustLastAgentAction, onDismiss = onDismissAgentActionBanner,
          )
        }

        // Non-silent multimodal Vision Gate blocks Send until Gemma 4, Cloud Vision, or .litertlm loads.
        if (blockedByVisionGate) {
          VisionUnsupportedGateBanner(
            runtimeState = runtimeState,
            onImportVisionModel = {
              onAction(AiChefAction.SelectAiExecutionMode(AiExecutionMode.ON_DEVICE))
              onAction(AiChefAction.SelectDeviceProfile(EdgeDeviceProfile.GEMMA_4_E2B_MULTIMODAL))
              onLaunchModelPicker()
            },
            onRemovePhoto = { onAction(AiChefAction.ClearAttachedImage) },
            onUseCloudApiKey = {
              // Switch to Cloud API Key mode, expand setup drawer, and scroll smoothly to item 0.
              onAction(AiChefAction.SelectAiExecutionMode(AiExecutionMode.CLOUD_API))
              if (!setupExpanded) toggleSetup()
              scrollToSetupTrigger += 1
            },
            onDownloadVisionModel = { onAction(AiChefAction.RequestModelDownloadApproval()) },
            onCancelDownload = { onAction(AiChefAction.CancelActiveModelDownload) },
          )
        }

        // Keep Gemma download, SAF copy, or OpenCL shader progress visible above composer.
        if (!blockedByVisionGate && (runtimeState is AiRuntimeState.Importing || runtimeState is AiRuntimeState.Loading)) {
          ModelOperationProgressBanner(
            runtimeState = runtimeState,
            onCancelDownload = { onAction(AiChefAction.CancelActiveModelDownload) },
          )
        }

        if (isPreprocessingImage) ImagePreprocessingBanner(imageLabel = preprocessingImageLabel)

        VoiceInputStatusStrip(
          voiceState = voiceState, activeLocale = selectedVoiceLocale,
          onStopListening = { onAction(AiChefAction.StopVoiceInput) },
        )

        // Docked quick-action suggestion chips shown directly above composer when input is empty.
        if (composerText.isEmpty() && attachedImagePath == null && !isGenerating) {
          SavorQuickPromptChipRow { prompt ->
            composerText = prompt
            onAction(AiChefAction.SelectQuickPrompt(prompt))
          }
        }

        // Persistent 2-tier SavorAgentComposer placing full-width input above 48.dp media controls.
        SavorAgentComposer(
          composerText = composerText, onComposerTextChange = { composerText = it },
          attachedImagePath = attachedImagePath, attachedImageLabel = attachedImageLabel,
          visionSupported = visionSupported, previewThumbnailBitmap = previewThumbnailBitmap,
          isGenerating = isGenerating, isPreprocessingImage = isPreprocessingImage,
          isListening = voiceState is VoiceInputState.Listening, selectedLocale = selectedVoiceLocale,
          hasConversationHistory = safeTurnHistory.isNotEmpty(), canSubmitTurn = canSubmitTurn,
          onRemovePhoto = { onAction(AiChefAction.ClearAttachedImage) },
          onLaunchCameraCapture = onLaunchCameraCapture, onLaunchGalleryPicker = onLaunchGalleryPicker,
          onToggleVoice = {
            if (voiceState is VoiceInputState.Listening) onAction(AiChefAction.StopVoiceInput)
            else onRequestVoiceInput(selectedVoiceLocale)
          },
          onToggleLocale = {
            selectedVoiceLocale = if (selectedVoiceLocale == VoiceInputPort.LOCALE_EN_US) {
              VoiceInputPort.LOCALE_FR_FR
            } else {
              VoiceInputPort.LOCALE_EN_US
            }
          },
          onResetConversation = { onAction(AiChefAction.ResetConversation) },
          onSubmitPrompt = submitCurrentPrompt,
        )
      }
    }
  }
}

@Composable
private fun SavorQuickPromptChipRow(onSelectPrompt: (String) -> Unit) {
  val extendedColors = KitchenTheme.extendedColors
  // 36.dp visual pills inside 44.dp+ vertical touch area with 1.dp warm border and 0.dp shadow.
  Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    SAVOR_QUICK_ACTION_PROMPTS.forEach { prompt ->
      Box(modifier = Modifier.defaultMinSize(minHeight = 44.dp).clip(CircleShape).clickable { onSelectPrompt(prompt) }, contentAlignment = Alignment.Center) {
        Surface(
          shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLow,
          border = BorderStroke(1.dp, extendedColors.borderSubtle), shadowElevation = 0.dp,
          modifier = Modifier.defaultMinSize(minHeight = 36.dp),
        ) {
          Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(prompt, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false)
          }
        }
      }
    }
  }
}

/**
 * 2-tier persistent bottom agent composer (`RoundedCornerShape(24.dp)`, `1.dp` warm border, `0.dp`
 * shadow): optional staged photo row + Row 1 `100%`-width `BasicTextField` (`maxLines = 1`
 * placeholder preventing 360dp 3-line wrap and mid-word line-2 cursor glitches) + Row 2 `48.dp`
 * tactile Camera, Photo, Voice + EN/FR, Reset, and Send controls.
 */
@Composable
private fun SavorAgentComposer(
  composerText: String,
  onComposerTextChange: (String) -> Unit,
  attachedImagePath: String?,
  attachedImageLabel: String?,
  visionSupported: Boolean,
  previewThumbnailBitmap: ImageBitmap?,
  isGenerating: Boolean,
  isPreprocessingImage: Boolean,
  isListening: Boolean,
  selectedLocale: String,
  hasConversationHistory: Boolean,
  canSubmitTurn: Boolean,
  onRemovePhoto: () -> Unit,
  onLaunchCameraCapture: () -> Unit,
  onLaunchGalleryPicker: () -> Unit,
  onToggleVoice: () -> Unit,
  onToggleLocale: () -> Unit,
  onResetConversation: () -> Unit,
  onSubmitPrompt: () -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  val mediaButtonsEnabled = !isPreprocessingImage && !isGenerating
  val hasAttachedPhoto = !attachedImagePath.isNullOrBlank()

  // Crisp ceramic rounded-24 container bounded by 1.dp warm stone border and 0.dp shadow.
  Surface(
    shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest,
    border = BorderStroke(1.dp, extendedColors.borderSubtle), shadowElevation = 0.dp,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 6.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      // Staged photo preview row right inside top of composer card with 48.dp remove target.
      if (attachedImagePath != null) {
        AttachedImageThumbnailCard(
          imagePath = attachedImagePath, displayLabel = attachedImageLabel ?: "Attached Photo",
          visionSupported = visionSupported, onRemove = onRemovePhoto, overrideBitmap = previewThumbnailBitmap,
        )
      }

      // Row 1: Full-width BasicTextField with single-line Ellipsis placeholder anchored at index 0.
      BasicTextField(
        value = composerText, onValueChange = onComposerTextChange,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onSubmitPrompt() }),
        maxLines = 4,
        decorationBox = { innerTextField ->
          // CenterStart 1-line box guarantees cursor lands at column 0 on line 1 when focused empty.
          Box(
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 28.dp).padding(start = 2.dp, end = 6.dp),
            contentAlignment = Alignment.CenterStart,
          ) {
            if (composerText.isEmpty()) {
              Text(
                text = if (hasAttachedPhoto) "Add note for photo scan…" else "Ask sous-chef, scan fridge, or swap meal…",
                style = MaterialTheme.typography.bodyLarge, color = extendedColors.ashMuted,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
              )
            }
            innerTextField()
          }
        },
        modifier = Modifier.fillMaxWidth(),
      )

      // Row 2: 48.dp tactile Camera, Photo, Voice + EN/FR locale pill, Reset, and circular Send.
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
          // Camera action writes full-resolution JPEG via scoped FileProvider in cacheDir/captures/.
          IconButton(onClick = onLaunchCameraCapture, enabled = mediaButtonsEnabled, modifier = Modifier.size(48.dp)) {
            Icon(
              imageVector = Icons.Filled.CameraAlt, contentDescription = "Take kitchen photo",
              tint = if (mediaButtonsEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
              modifier = Modifier.size(22.dp),
            )
          }

          // Photo Picker action swaps icon for 18.dp spinner while EXIF bitmap downscaling runs.
          IconButton(onClick = onLaunchGalleryPicker, enabled = mediaButtonsEnabled, modifier = Modifier.size(48.dp)) {
            when {
              isPreprocessingImage -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
              else -> Icon(
                imageVector = Icons.Filled.PhotoLibrary, contentDescription = "Pick photo from gallery",
                tint = if (mediaButtonsEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(22.dp),
              )
            }
          }

          // Bilingual voice input button toggles offline SpeechRecognizer listening session.
          IconButton(onClick = onToggleVoice, modifier = Modifier.size(48.dp)) {
            Icon(
              imageVector = if (isListening) Icons.Filled.Stop else Icons.Filled.Mic,
              contentDescription = if (isListening) "Stop voice input" else "Start voice input",
              tint = if (isListening) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(22.dp),
            )
          }

          // 48.dp touch box hosting compact EN / FR speech locale toggle pill.
          Box(
            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).clip(CircleShape).clickable(onClick = onToggleLocale),
            contentAlignment = Alignment.Center,
          ) {
            Surface(
              shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer,
              border = BorderStroke(1.dp, extendedColors.borderSubtle), shadowElevation = 0.dp,
            ) {
              Text(
                text = if (selectedLocale == VoiceInputPort.LOCALE_FR_FR) "FR" else "EN",
                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
              )
            }
          }

          // Conversation reset icon appears only once chat history is non-empty.
          if (hasConversationHistory) {
            IconButton(onClick = onResetConversation, modifier = Modifier.size(48.dp)) {
              Icon(Icons.Filled.RestartAlt, contentDescription = "Reset Sous-Chef conversation", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
          }
        }

        // 48.dp circular Send CTA swapping arrow for 18.dp spinner during turn or photo prep.
        Surface(
          onClick = onSubmitPrompt, enabled = canSubmitTurn, shape = CircleShape,
          color = if (canSubmitTurn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
          shadowElevation = 0.dp, modifier = Modifier.size(48.dp),
        ) {
          Box(contentAlignment = Alignment.Center) {
            when {
              isGenerating || isPreprocessingImage -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.2.dp, color = MaterialTheme.colorScheme.primary)
              else -> Icon(
                imageVector = Icons.Filled.ArrowUpward, contentDescription = "Send prompt to Sous-Chef",
                tint = if (canSubmitTurn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SavorChatTurnView(
  turn: AiChatTurn,
  savedRecipeIds: Set<String>,
  isSwapsApplied: Boolean,
  isIngredientsQueued: Boolean,
  isVisualRestockConfirmed: Boolean,
  currencySymbol: String,
  onAction: (AiChefAction) -> Unit,
  onQuickPromptFill: (String) -> Unit,
  onNavigateToBoard: () -> Unit,
  onNavigateToGroceryList: () -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  val isUser = turn.role == AiChatRole.USER
  val timeBadge = remember(turn.createdAtIso) { formatTurnClockLabel(turn.createdAtIso) }

  if (isUser) {
    // Trailing-aligned warm charcoal user bubble with attached photo echo badge.
    Column(modifier = Modifier.fillMaxWidth().padding(start = 32.dp), horizontalAlignment = Alignment.End) {
      Surface(shape = KitchenTheme.shapes.userChatBubble, color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 0.dp) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          if (!turn.attachedImageLabel.isNullOrBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
              Icon(Icons.Filled.PhotoLibrary, contentDescription = null, tint = extendedColors.primaryFixed, modifier = Modifier.size(14.dp))
              Text(turn.attachedImageLabel, style = MaterialTheme.typography.labelSmall, color = extendedColors.primaryFixed, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
          }
          if (turn.messageText.isNotBlank()) {
            Text(turn.messageText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.inverseOnSurface)
          }
        }
      }
      Text(timeBadge, style = MaterialTheme.typography.labelSmall, color = extendedColors.ashMuted, modifier = Modifier.padding(top = 4.dp, end = 4.dp))
    }
  } else {
    val proposal = turn.proposal
    val richCard = proposal?.featuredRecipeCard
    val intake = turn.visualIntakeResult
    val hasVisualArtifact = intake != null && hasRenderableVisualIntake(intake)
    val sourceTag = turn.responseSourceLabel?.trim()?.takeIf { it.isNotBlank() }
    val headerLabel = if (sourceTag != null) "Sous-Chef · $sourceTag · $timeBadge" else "Sous-Chef · $timeBadge"

    Column(modifier = Modifier.fillMaxWidth().padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      // 26.dp circular SavorAI cocotte Sous-Chef avatar (zero sparkles, zero cold blue pills).
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SavorBrandBadge(badgeSize = 26.dp, logoSize = 16.dp, shape = CircleShape)
        Text(
          text = headerLabel, style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
      }

      // Always render the AI's actual assistantSummary prose bubble above any recipe or swap cards;
      // suppress duplicate text only when VisualIntakeConfirmationCard renders its own scan header.
      if (!hasVisualArtifact && turn.messageText.isNotBlank()) {
        Surface(
          shape = KitchenTheme.shapes.assistantChatBubble, color = MaterialTheme.colorScheme.surfaceContainerLowest,
          border = BorderStroke(1.dp, extendedColors.borderSubtle), shadowElevation = 0.dp, modifier = Modifier.fillMaxWidth(),
        ) {
          Text(turn.messageText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(14.dp))
        }
      }

      // Structured Rich Recipe Suggestion Card (<= 3 pills) + food-science substitution tip.
      if (richCard != null) {
        val isSaved = richCard.recipe.isSavedFavorite || richCard.recipe.recipeId in savedRecipeIds
        AiRichRecipeSuggestionCard(
          richCard = richCard, isSaved = isSaved,
          onStartCookingMode = { onAction(AiChefAction.StartCookModeFromAi(richCard.recipe)) },
          onAddToPlan = { onAction(AiChefAction.AddRecipeToPlanFromAi(richCard.recipe)) },
          onToggleSave = { onAction(AiChefAction.ToggleSaveRecipe(richCard.recipe.recipeId)) },
          onQueueMissingStaples = { deltas -> onAction(AiChefAction.QueueMissingRecipeStaples(deltas)) },
          currencySymbol = currencySymbol,
        )
        richCard.substitutionTip?.takeIf { it.isNotBlank() }?.let { tip ->
          CulinarySubstitutionTipCard(
            tipText = tip,
            onApplySwapClick = {
              val targetFromId = resolveDairyOrSubstituteBomId(richCard.recipe.bomLines)
              if (targetFromId != null) {
                onAction(AiChefAction.ApplyRecipeSubstitution(richCard.recipe.recipeId, targetFromId, "ing_greek_yogurt"))
              }
            },
            onCheckCaloriesClick = { onQuickPromptFill("What is the updated calorie count?") },
          )
        }
      }

      // Interactive confirmation-gated photo scan artifact with selectable spice/catalog chips.
      if (intake != null && hasRenderableVisualIntake(intake)) {
        VisualIntakeConfirmationCard(
          intakeResult = intake, isConfirmed = isVisualRestockConfirmed,
          onConfirmRestock = { onAction(AiChefAction.ConfirmVisualRestock(intake)) },
          turnId = turn.turnId,
          onCommitSelection = { selectedIds, selectedNewLabels, destination ->
            onAction(AiChefAction.CommitVisualIntakeSelection(turn.turnId, intake, selectedIds, selectedNewLabels, destination))
          },
        )
      }

      // Actionable weekly meal swap & grocery discovery cards (suppressed on conversational turns).
      if (proposal != null && !proposal.isConversationalOnly) {
        if (proposal.slotSwaps.isNotEmpty()) {
          MealSwapsProposalCard(
            proposal = proposal, isApplied = isSwapsApplied,
            onApplySwaps = { onAction(AiChefAction.ApplyMealSwaps(proposal)) }, onNavigateToBoard = onNavigateToBoard,
          )
        }
        if (proposal.newIngredientSuggestions.isNotEmpty()) {
          BrainBoosterIngredientsCard(
            proposal = proposal, isQueued = isIngredientsQueued,
            onQueueIngredients = { onAction(AiChefAction.QueueSuggestedIngredients(proposal)) },
            onNavigateToGroceryList = onNavigateToGroceryList,
          )
        }
      }

      // Warm 44.dp+ follow-up prompt pills beneath the assistant turn.
      val followUps = proposal?.followUpPrompts ?: turn.toolCallResult?.followUpPrompts.orEmpty()
      if (followUps.isNotEmpty()) {
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          followUps.forEach { followUp ->
            Box(modifier = Modifier.defaultMinSize(minHeight = 44.dp).clip(CircleShape).clickable { onQuickPromptFill(followUp) }, contentAlignment = Alignment.Center) {
              Surface(
                shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, extendedColors.borderSubtle), shadowElevation = 0.dp,
              ) {
                Text(followUp, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
              }
            }
          }
        }
      }
    }
  }
}

// Guarantees unique LazyColumn item keys even if a legacy persisted snapshot carried duplicate turnIds.
private fun ensureUniqueTurnIds(turns: List<AiChatTurn>): List<AiChatTurn> {
  val seenIds = HashSet<String>(turns.size)
  return turns.mapIndexed { index, turn ->
    val candidate = turn.turnId.ifBlank { "turn_idx_$index" }
    when {
      seenIds.add(candidate) -> if (candidate == turn.turnId) turn else turn.copy(turnId = candidate)
      else -> {
        val uniqueId = "${candidate}_dup_$index"
        seenIds.add(uniqueId)
        turn.copy(turnId = uniqueId)
      }
    }
  }
}

private fun formatTurnClockLabel(createdAtIso: String): String =
  createdAtIso.substringAfter('T', "").take(5).ifBlank { "Just now" }

// Selects butter/milk/cheese BOM lines before substitutable non-protein lines, avoiding self-swaps.
private val DAIRY_SWAP_TARGETS: List<String> = listOf("ing_butter", "ing_milk", "ing_cheese")
private val PRIMARY_PROTEIN_IDS: Set<String> =
  setOf("ing_salmon", "ing_chicken", "ing_fish", "ing_bangus", "ing_turkey_breast", "ing_turkey_ham", "ing_egg", "ing_tofu")

private fun resolveDairyOrSubstituteBomId(bomLines: List<BomLine>): String? {
  DAIRY_SWAP_TARGETS.firstOrNull { id -> bomLines.any { it.ingredientId == id } }?.let { return it }
  bomLines.firstOrNull { !it.substituteGroupId.isNullOrBlank() && it.ingredientId != "ing_greek_yogurt" }?.let { return it.ingredientId }
  return bomLines.firstOrNull { it.ingredientId != "ing_greek_yogurt" && it.ingredientId !in PRIMARY_PROTEIN_IDS }?.ingredientId
}

// Pure in-memory procedural preview bitmap avoiding Android asset or disk reads in IDE @Preview.
private fun buildPreviewThumbnailBitmap(): ImageBitmap? =
  runCatching {
    val image = ImageBitmap(96, 96)
    val canvas = Canvas(image)
    canvas.drawRect(0f, 0f, 96f, 96f, Paint().apply { color = Color(0xFF2E7D5A) })
    canvas.drawCircle(Offset(34f, 48f), 20f, Paint().apply { color = Color(0xFFB45309) })
    canvas.drawCircle(Offset(64f, 52f), 16f, Paint().apply { color = Color(0xFFD0FFE2) })
    image
  }.getOrNull()

@Preview(name = "Sous-Chef — Rich Recipe & Undo Bar", showBackground = true, widthDp = 412, heightDp = 900)
@Composable
private fun SavorAiAssistantRichRecipePreview() {
  // Showcases structured recipe match card, docked Undo bar, quick chips, and 2-tier composer.
  val salmon = IngredientCatalogItem("ing_salmon", "Salmon fillet", IngredientCategory.PROTEIN, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 600.0)
  val spinach = IngredientCatalogItem("ing_spinach", "Baby spinach", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 300.0)
  val recipe = Recipe(
    "rec_tuscan_salmon", "Pan-Seared Tuscan Salmon with Garlic Spinach",
    "Mediterranean Bistro", listOf(MealSlotType.DINNER), CookOwner.WIFE, 4, 10, 0, 15, 25,
    toddlerPrepTip = "Flake boneless fillet finely for 2yo.", caloriesPerServing = 490,
    bomLines = listOf(BomLine("ing_salmon", 400.0), BomLine("ing_spinach", 200.0)),
  )
  val richCard = AiRichRecipeCard(
    recipe = recipe,
    matchSummary = RecipePantryMatchSummary(
      recipe.recipeId, listOf(salmon, spinach), emptyList(), emptyList(),
      matchPercentage = 100, isZeroFoodWasteMatch = true,
      rescuedExpiringIngredientIds = listOf("ing_salmon", "ing_spinach"), rescuedValueUsd = 845.0,
    ),
    substitutionTip = "Remove skillet from heat before whisking in Greek yogurt.",
  )
  val proposal = AiMenuOptimizationProposal(
    proposalId = "prop_savor_1", focusTag = NutritionFocusTag.PANTRY_ZERO_WASTE,
    assistantSummary = "Matched your expiring salmon and spinach.", featuredRecipeCard = richCard,
  )
  val turns = listOf(
    AiChatTurn("u1", AiChatRole.USER, "Rescue expiring salmon and spinach", "2026-10-25T18:42:00Z"),
    AiChatTurn(
      "a1", AiChatRole.ASSISTANT, proposal.assistantSummary, "2026-10-25T18:43:00Z",
      proposal = proposal, responseSourceLabel = "gemma-4-E2B-it",
    ),
  )
  FamilyKitchenBoardTheme {
    AiChefScreen(
      turnHistory = turns,
      runtimeState = AiRuntimeState.Ready(RuntimeCapabilities(true, true, "LiteRT-LM GPU", "gemma-e2b-it-multimodal-int4.litertlm")),
      voiceState = VoiceInputState.Idle, activeDeviceProfile = EdgeDeviceProfile.GEMMA_4_E2B_MULTIMODAL,
      attachedImagePath = null, attachedImageLabel = null, onAction = {},
      onLaunchGalleryPicker = {}, onLaunchCameraCapture = {}, onRequestVoiceInput = {}, onLaunchModelPicker = {},
      canUndoLastAgentAction = true, lastAgentActionSummary = "Stocked 4 spices in Pantry", onAdjustLastAgentAction = {},
    )
  }
}

@Preview(name = "Sous-Chef — Spice Photo Scan & Staged Thumbnail", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun AiChefSpicePhotoArtifactPreview() {
  // Showcases compact 360dp spice-rack VisualIntakeConfirmationCard and staged composer preview.
  val readyVision = AiRuntimeState.Ready(RuntimeCapabilities(true, true, "LiteRT-LM GPU + Vision", "gemma-e2b-it-multimodal-int4.litertlm"))
  val spices = listOf(
    IngredientCatalogItem("ing_custom_ground_turmeric", "Ground Turmeric", IngredientCategory.PANTRY_STAPLE, IngredientTier.TIER_2_STAPLE, BaseUnit.GRAM, 100.0, defaultStorageLocation = StorageLocation.PANTRY),
    IngredientCatalogItem("ing_custom_bay_leaves", "Bay Leaves", IngredientCategory.PANTRY_STAPLE, IngredientTier.TIER_2_STAPLE, BaseUnit.GRAM, 100.0, defaultStorageLocation = StorageLocation.PANTRY),
  )
  val intakeTurn = AiChatTurn(
    "turn_vis_1", AiChatRole.ASSISTANT, "Spotted 2 spices on your rack.", "2026-10-25T21:33:00Z",
    visualIntakeResult = VisualIntakeResult("Spotted 2 spices — select items below to stock.", proposedNewCatalogItems = spices),
  )
  FamilyKitchenBoardTheme {
    AiChefScreen(
      turnHistory = listOf(intakeTurn), runtimeState = readyVision, voiceState = VoiceInputState.Idle,
      activeDeviceProfile = EdgeDeviceProfile.GEMMA_4_E2B_MULTIMODAL,
      attachedImagePath = "/cache/captures/spice_rack.jpg", attachedImageLabel = "spice_rack.jpg",
      onAction = {}, onLaunchGalleryPicker = {}, onLaunchCameraCapture = {},
      onRequestVoiceInput = {}, onLaunchModelPicker = {},
      previewThumbnailBitmap = remember { buildPreviewThumbnailBitmap() },
    )
  }
}
