package com.familykitchen.board.ui.navigation

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.familykitchen.board.data.KitchenImagePreprocessor
import com.familykitchen.board.domain.AiChefAction
import com.familykitchen.board.domain.ConsumerHouseholdTemplate
import com.familykitchen.board.domain.CookModeEngine
import com.familykitchen.board.domain.CookModeSessionState
import com.familykitchen.board.domain.CookedDeductionPayload
import com.familykitchen.board.domain.CustomGroceryItem
import com.familykitchen.board.domain.ExpiringPantryCardModel
import com.familykitchen.board.domain.FirstRunOnboardingDraft
import com.familykitchen.board.domain.GroceryAndCatalogEditAction
import com.familykitchen.board.domain.GrocerySortMode
import com.familykitchen.board.domain.HouseholdCurrency
import com.familykitchen.board.domain.HouseholdProfile
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.MarketVenue
import com.familykitchen.board.domain.MealSlotType
import com.familykitchen.board.domain.MenuTemplate
import com.familykitchen.board.domain.PlanCadence
import com.familykitchen.board.domain.PostCookReactionTag
import com.familykitchen.board.domain.ProportionalStockLevel
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.RecipeCreationMode
import com.familykitchen.board.domain.RestockDelta
import com.familykitchen.board.domain.SlotSkipReason
import com.familykitchen.board.domain.SmartRecipeDraft
import com.familykitchen.board.domain.StapleStatus
import com.familykitchen.board.domain.StorageLocation
import com.familykitchen.board.domain.UnpackItemDirective
import com.familykitchen.board.domain.WeeklyPrepBatchCommitPayload
import com.familykitchen.board.domain.WorkoutTiming
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ports.KitchenSnapshot
import com.familykitchen.board.ui.GroceriesSubDestination
import com.familykitchen.board.ui.KitchenBoardViewModel
import com.familykitchen.board.ui.KitchenUiState
import com.familykitchen.board.ui.TopLevelDestination
import com.familykitchen.board.ui.aichef.ModelDownloadApprovalDialog
import com.familykitchen.board.ui.board.TodayAndWeekScreen
import com.familykitchen.board.ui.cookmode.StepByStepCookModeScreen
import com.familykitchen.board.ui.export.KitchenExportController
import com.familykitchen.board.ui.export.rememberKitchenDocumentExportController
import com.familykitchen.board.ui.grocery.SmartGroceryScreen
import com.familykitchen.board.ui.onboarding.FirstRunOnboardingHost
import com.familykitchen.board.ui.onboarding.FirstRunOnboardingScreen
import com.familykitchen.board.ui.pacer.MorningPacerScreen
import com.familykitchen.board.ui.pacer.PacerMode
import com.familykitchen.board.ui.pantry.SmartPantryScreen
import com.familykitchen.board.ui.preview.PreviewFixtures
import com.familykitchen.board.ui.recipes.CleanRecipeReaderScreen
import com.familykitchen.board.ui.recipes.CookbookGridScreen
import com.familykitchen.board.ui.sheets.CustomFamilyMealDraft
import com.familykitchen.board.ui.sheets.QaEdgeCaseScenario
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import java.io.File
import kotlinx.coroutines.delay

// Unified SavorAI v2.0 4-Tab Material 3 Shell (`Plan · Pantry · Recipes · Shop`) routing all 4
// destinations, `ActiveCookMiniBar` + 1s live timer tick, `StepByStepCookModeScreen`, and
// `SavorModalSheetsOverlay` with scoped `PickVisualMedia` & `FileProvider` OCR launchers.

// Automatic horizontal breakpoint switching portrait NavigationBar to counter NavigationRail.
private const val SCAFFOLD_WIDE_COUNTER_BREAKPOINT_DP: Int = 720

/**
 * Stateful top-level `SavorAI` shell collecting [KitchenBoardViewModel.uiState] via
 * `collectAsStateWithLifecycle()`, gating `RECORD_AUDIO` and `CAMERA` runtime permissions on
 * demand, wiring scoped `PickVisualMedia(ImageOnly)` and `FileProvider` `TakePicture` launchers for
 * `Scan Cookbook`, and delegating rendering to [KitchenScaffoldContent].
 */
@Composable
fun KitchenScaffold(
  viewModel: KitchenBoardViewModel,
  modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  var pendingAudioCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
  var pendingCookbookCapturePath by rememberSaveable { mutableStateOf<String?>(null) }
  var studioDefaultSlotType by rememberSaveable { mutableStateOf(MealSlotType.DINNER) }

  // Request RECORD_AUDIO on demand when Smart Grocery dictation or Cook Mode hands-free mic starts.
  val audioPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
  ) { granted ->
    val action = pendingAudioCallback
    pendingAudioCallback = null
    if (granted && action != null) action()
  }

  // Helper checking RECORD_AUDIO before starting SpeechRecognizer outside AiChefActivityHost.
  val runWithAudioPermission: (() -> Unit) -> Unit = { onGranted ->
    val isGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
      PackageManager.PERMISSION_GRANTED
    when {
      isGranted -> onGranted()
      else -> {
        pendingAudioCallback = onGranted
        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
      }
    }
  }

  // Zero-permission Photo Picker for `Scan Cookbook` OCR inside AddOrEditRecipeSheet.
  val cookbookGalleryLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickVisualMedia(),
  ) { pickedUri: Uri? ->
    if (pickedUri != null) viewModel.scanCookbookPhotoIntoDraft(pickedUri.toString())
  }

  // Scoped FileProvider camera capture for `Scan Cookbook` OCR (`cacheDir/captures/`).
  val cookbookCameraLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.TakePicture(),
  ) { saved: Boolean ->
    val capturedPath = pendingCookbookCapturePath
    pendingCookbookCapturePath = null
    when {
      saved && !capturedPath.isNullOrBlank() -> viewModel.scanCookbookPhotoIntoDraft(capturedPath)
      !capturedPath.isNullOrBlank() -> runCatching { File(capturedPath).delete() }
    }
  }

  // Allocate scoped FileProvider URI in cacheDir/captures/ before launching external camera intent.
  val launchScopedCookbookCamera: () -> Unit = {
    runCatching {
      val (targetFile, contentUri) = KitchenImagePreprocessor.createCameraCaptureTarget(context)
      pendingCookbookCapturePath = targetFile.absolutePath
      cookbookCameraLauncher.launch(contentUri)
    }.onFailure { err ->
      viewModel.postExportFeedback("Unable to prepare camera capture: ${err.message ?: "storage error"}.")
    }
  }

  // Request CAMERA permission strictly when user taps Take Photo in Scan Cookbook mode.
  val cookbookCameraPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
  ) { granted: Boolean ->
    when {
      granted -> launchScopedCookbookCamera()
      else -> viewModel.postExportFeedback("Camera permission declined. Grant Camera access or tap Choose Photo.")
    }
  }

  // Return from CleanRecipeReaderScreen back onto the 2-Column Cookbook Grid on system Back.
  BackHandler(
    enabled = uiState.activeDestination == TopLevelDestination.RECIPES &&
      uiState.selectedRecipeId != null &&
      (uiState.activeCookModeSession == null || uiState.isCookModeMinimized),
  ) {
    viewModel.closeRecipeReaderBackToGrid()
  }

  // Minimize full-screen Cook Mode into ActiveCookMiniBar on system Back so timers stay alive.
  BackHandler(enabled = uiState.activeCookModeSession != null && !uiState.isCookModeMinimized) {
    viewModel.minimizeCookMode()
  }

  // Remember zero-permission Clipboard + SAF CreateDocument("application/pdf") export controller.
  val exportController = rememberKitchenDocumentExportController(
    uiState = uiState, onPostToast = viewModel::postExportFeedback,
  )

  // Forward immutable UI state slices and explicit ViewModel method references into stateless shell.
  KitchenScaffoldContent(
    uiState = uiState, studioDefaultSlotType = studioDefaultSlotType,
    onSelectDestination = viewModel::selectDestination,
    onSelectGroceriesSubDestination = viewModel::selectGroceriesSubDestination,
    onOpenWeeklyPrep = viewModel::openWeeklyPrepSession, onSetPacerMode = viewModel::setPacerMode,
    onSelectDay = viewModel::selectActiveDay, onSelectWeekHorizon = viewModel::setViewingNextWeek,
    onOpenSousChefSheet = viewModel::openSousChefSheet, onDismissSousChefSheet = viewModel::dismissSousChefSheet,
    onOpenStartNewWeek = viewModel::openStartNewWeekSheet, onDismissStartNewWeek = viewModel::dismissStartNewWeekSheet,
    onConfirmStartNewWeek = viewModel::confirmStartNewWeek,
    onOpenSavedMenus = viewModel::openSavedMenusSheet, onDismissSavedMenus = viewModel::dismissSavedMenusSheet,
    onApplySavedMenu = viewModel::applySavedMenu, onSaveCurrentWeekAsMenu = viewModel::saveCurrentWeekAsMenu,
    onAddMealForSlot = { day, slot ->
      studioDefaultSlotType = slot
      viewModel.selectActiveDay(day)
      viewModel.openAddRecipeStudio(RecipeCreationMode.QUICK_EDITOR, scheduleByDefault = true)
    },
    onAskChefForSlot = { slotId -> viewModel.openSousChefSheet("Help swap slot $slotId") },
    onToggleSearch = viewModel::toggleSearchBar, onSearchQueryChange = viewModel::updateSearchQuery,
    onToggleDarkTheme = viewModel::toggleDarkCounterTheme, onToggleWideLayout = viewModel::toggleWideCounterLayout,
    onDismissToast = viewModel::dismissToast, onStartCookingSlot = viewModel::startCookingSlot,
    onQuickCookSlot = viewModel::quickCookSlot, onOpenAdjustSlot = viewModel::openAdjustSlotSheet,
    onDismissAdjustSlot = viewModel::dismissAdjustSlotSheet, onConfirmCookedOverride = viewModel::confirmCookedOverride,
    onSelectWorkoutModifier = viewModel::selectSlotWorkoutModifier, onQuickSwapSlotRecipe = viewModel::quickSwapSlotRecipe,
    onMarkSkippedOrLeftovers = viewModel::markSlotSkippedOrLeftovers, onMoveOrSwapSlot = viewModel::moveOrSwapSlot,
    onToggleSlotPinned = viewModel::toggleSlotPinned, onMarkSkipReason = viewModel::markSlotSkipWithReason,
    onClearSlot = viewModel::clearSlot, onOpenCustomMealSheet = { slotId -> viewModel.openCustomMealSheet(forSlotId = slotId) },
    onDismissCustomMealSheet = viewModel::dismissCustomMealSheet,
    onSchedulePresetRecipe = viewModel::schedulePresetRecipeFromSheet, onSaveCustomMeal = viewModel::saveCustomFamilyMeal,
    onTogglePrepStationTask = viewModel::togglePrepStationTask, onCommitWeeklyPrep = viewModel::commitWeeklyPrepSession,
    onAdjustPantryQuantity = viewModel::adjustPantryQuantity, onSetStapleStatus = viewModel::setStapleStatus,
    onToggleLocalAvailability = viewModel::toggleLocalAvailability,
    onSetProportionalLevel = viewModel::setPantryItemProportionalLevel,
    onMarkItemUsed = viewModel::markPantryItemUsed, onMoveItemToZone = viewModel::movePantryItemZone,
    onToggleManualGroceryQueue = viewModel::toggleManualGroceryQueue,
    onRemoveManualGroceryQueue = viewModel::removeManualGroceryQueue,
    onAddIngredientToGrocery = viewModel::addIngredientToGrocery, onAddIngredientToPantry = viewModel::addIngredientToPantry,
    onAddIngredientToPantryWithExpiry = viewModel::addIngredientToPantryWithExpiry,
    onScanReceiptFromPantry = viewModel::onScanReceiptFromPantry, onRunAiAuditFromPantry = viewModel::onRunAiAuditFromPantry,
    onFindRescueRecipesFromPantry = viewModel::onFindRescueRecipesFromPantry,
    onPairExpiringFromPantry = viewModel::onPairExpiringIngredientsFromPantry,
    onViewTonightRescueFromPantry = viewModel::onViewTonightRescuePlanFromPantry,
    onToggleGroceryChecked = viewModel::toggleGroceryItemChecked, onRestockCheckedGrocery = viewModel::commitGroceryRestockBatch,
    onSelectGrocerySortMode = viewModel::setGrocerySortMode, onSelectVenueFilter = viewModel::setGroceryVenueFilter,
    onRequeueSkippedStaple = viewModel::requeueSkippedPantryStaple, onOpenUnpackSheet = viewModel::openUnpackGroceriesSheet,
    onDismissUnpackSheet = viewModel::dismissUnpackGroceriesSheet, onConfirmUnpackGroceries = viewModel::confirmUnpackGroceries,
    onUncheckAllCheckedGrocery = viewModel::uncheckAllCheckedGrocery, onAddCustomGroceryItem = viewModel::addCustomGroceryItem,
    onGroceryCatalogEdit = viewModel::onGroceryAndCatalogEdit,
    onStartGroceryVoiceDictation = { runWithAudioPermission { viewModel.startGroceryVoiceDictation() } },
    onOpenRecipeInReader = viewModel::openRecipeInReader, onSelectRecipeInReader = viewModel::selectRecipe,
    onBackToCookbookGrid = viewModel::closeRecipeReaderBackToGrid,
    onOpenAddRecipeStudio = { mode, scheduleByDefault ->
      if (!scheduleByDefault) studioDefaultSlotType = MealSlotType.DINNER
      viewModel.openAddRecipeStudio(mode, scheduleByDefault)
    },
    onForkRecipe = viewModel::openForkRecipeStudio, onEditRecipe = viewModel::openEditCustomRecipeStudio,
    onDismissAddOrEditRecipeSheet = viewModel::dismissAddOrEditRecipeSheet,
    onParsePastedRecipeText = viewModel::parsePastedRecipeText,
    onLaunchCookbookCamera = {
      val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
      when {
        granted -> launchScopedCookbookCamera()
        else -> cookbookCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
      }
    },
    onLaunchCookbookPhotoPicker = {
      cookbookGalleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    },
    onSaveSmartRecipeDraft = viewModel::saveSmartRecipeDraft, onDeleteRecipe = viewModel::deleteCustomRecipe,
    onSwapRecipeIngredient = viewModel::swapIngredientInRecipe, onToggleSavedRecipe = viewModel::toggleSavedRecipe,
    onToggleReaderIngredientChecked = viewModel::toggleReaderIngredientChecked,
    onResetReaderCheckedIngredients = viewModel::resetCheckedReaderIngredients,
    onQueueMissingRecipeIngredients = viewModel::queueMissingRecipeIngredients,
    onAddRecipeKitchenNote = viewModel::addRecipeKitchenNote, onScheduleRecipeToPlan = viewModel::scheduleRecipeToPlan,
    onShareRecipe = viewModel::notifyRecipeShared,
    onStartCookModeFromReader = { recipe, handsFree ->
      when {
        handsFree -> runWithAudioPermission { viewModel.startCookMode(recipe, null, true) }
        else -> viewModel.startCookMode(recipe, null, false)
      }
    },
    onUpdateCookModeSession = viewModel::updateCookModeSession, onMinimizeCookMode = viewModel::minimizeCookMode,
    onResumeCookMode = viewModel::resumeMinimizedCookMode, onEndCookMode = viewModel::endCookModeSession,
    onToggleCookModeHandsFreeMic = { enabled ->
      when {
        enabled -> runWithAudioPermission { viewModel.toggleCookModeHandsFreeMic(true) }
        else -> viewModel.toggleCookModeHandsFreeMic(false)
      }
    },
    onOpenCookModePantryAdjuster = viewModel::openCookModePantryAdjuster,
    onCompleteCookModeAndDeduct = viewModel::completeCookModeAndDeductPantry,
    onDismissPostCookSummary = viewModel::dismissPostCookSummary,
    onCommitPostCookFollowUp = viewModel::commitPostCookFollowUp, onAiChefAction = viewModel::onAiChefAction,
    onUndoLastAgentAction = viewModel::undoLastAgentAction, onDismissAgentActionBanner = viewModel::dismissAgentActionBanner,
    onOpenFamilyProfile = viewModel::openFamilyProfileSheet, onDismissFamilyProfile = viewModel::dismissFamilyProfileSheet,
    onSaveHouseholdProfile = viewModel::saveHouseholdProfile, onSelectConsumerTemplate = viewModel::selectConsumerTemplate,
    onSelectCurrencyAndRegion = viewModel::selectCurrencyAndRegion, onOpenQaLab = viewModel::openQaLabSheet,
    onDismissQaLab = viewModel::dismissQaLabSheet, onSelectClockHour = viewModel::selectSimulatedClockHour,
    onResetHouseholdToSeed = viewModel::resetHouseholdStateToSeed, onTriggerQaScenario = viewModel::triggerQaScenario,
    onCompleteFirstRunOnboarding = viewModel::completeFirstRunOnboarding,
    onSkipFirstRunOnboarding = viewModel::skipFirstRunOnboardingWithDemo,
    onRelaunchFirstRunOnboarding = viewModel::relaunchFirstRunOnboarding,
    onDismissOnboardingReplay = viewModel::dismissOnboardingReplay,
    exportController = exportController, modifier = modifier,
  )
}

/**
 * Pure stateless 4-tab `SavorAI` shell Composable accepting an immutable [KitchenUiState] and
 * explicit event callbacks so Android Studio `@Preview`s render cleanly with zero `ViewModel` or
 * JNI calls.
 */
@Composable
fun KitchenScaffoldContent(
  uiState: KitchenUiState, studioDefaultSlotType: MealSlotType = MealSlotType.DINNER,
  onSelectDestination: (TopLevelDestination) -> Unit = {},
  onSelectGroceriesSubDestination: (GroceriesSubDestination) -> Unit = {},
  onOpenWeeklyPrep: () -> Unit = {}, onSetPacerMode: (PacerMode) -> Unit = {},
  onSelectDay: (Int) -> Unit = {}, onSelectWeekHorizon: (Boolean) -> Unit = {},
  onOpenSousChefSheet: (String?) -> Unit = {}, onDismissSousChefSheet: () -> Unit = {},
  onOpenStartNewWeek: () -> Unit = {}, onDismissStartNewWeek: () -> Unit = {},
  onConfirmStartNewWeek: (PlanCadence, Boolean) -> Unit = { _, _ -> },
  onOpenSavedMenus: () -> Unit = {}, onDismissSavedMenus: () -> Unit = {},
  onApplySavedMenu: (MenuTemplate, Boolean) -> Unit = { _, _ -> },
  onSaveCurrentWeekAsMenu: (String, String) -> Unit = { _, _ -> },
  onAddMealForSlot: (Int, MealSlotType) -> Unit = { _, _ -> }, onAskChefForSlot: (String) -> Unit = {},
  onToggleSearch: () -> Unit = {}, onSearchQueryChange: (String) -> Unit = {},
  onToggleDarkTheme: () -> Unit = {}, onToggleWideLayout: () -> Unit = {},
  onDismissToast: () -> Unit = {}, onStartCookingSlot: (String) -> Unit = {},
  onQuickCookSlot: (String) -> Unit = {}, onOpenAdjustSlot: (String) -> Unit = {},
  onDismissAdjustSlot: () -> Unit = {}, onConfirmCookedOverride: (CookedDeductionPayload) -> Unit = {},
  onSelectWorkoutModifier: (String, WorkoutTiming) -> Unit = { _, _ -> },
  onQuickSwapSlotRecipe: (String, String) -> Unit = { _, _ -> }, onMarkSkippedOrLeftovers: (String) -> Unit = {},
  onMoveOrSwapSlot: (String, Int, MealSlotType) -> Unit = { _, _, _ -> },
  onToggleSlotPinned: (String) -> Unit = {}, onMarkSkipReason: (String, SlotSkipReason) -> Unit = { _, _ -> },
  onClearSlot: (String) -> Unit = {}, onOpenCustomMealSheet: (String?) -> Unit = {},
  onDismissCustomMealSheet: () -> Unit = {},
  onSchedulePresetRecipe: (Recipe, MealSlotType, Int, Boolean) -> Unit = { _, _, _, _ -> },
  onSaveCustomMeal: (CustomFamilyMealDraft) -> Unit = {},
  onTogglePrepStationTask: (String, Boolean) -> Unit = { _, _ -> },
  onCommitWeeklyPrep: (WeeklyPrepBatchCommitPayload) -> Unit = {},
  onAdjustPantryQuantity: (String, Double) -> Unit = { _, _ -> },
  onSetStapleStatus: (String, StapleStatus) -> Unit = { _, _ -> },
  onToggleLocalAvailability: (String, Boolean) -> Unit = { _, _ -> },
  onSetProportionalLevel: (String, ProportionalStockLevel) -> Unit = { _, _ -> },
  onMarkItemUsed: (String) -> Unit = {}, onMoveItemToZone: (String, StorageLocation) -> Unit = { _, _ -> },
  onToggleManualGroceryQueue: (String) -> Unit = {}, onRemoveManualGroceryQueue: (String) -> Unit = {},
  onAddIngredientToGrocery: (IngredientCatalogItem) -> Unit = {},
  onAddIngredientToPantry: (IngredientCatalogItem) -> Unit = {},
  onAddIngredientToPantryWithExpiry: (IngredientCatalogItem, StorageLocation, Int) -> Unit = { _, _, _ -> },
  onScanReceiptFromPantry: () -> Unit = {}, onRunAiAuditFromPantry: () -> Unit = {},
  onFindRescueRecipesFromPantry: (ExpiringPantryCardModel) -> Unit = {},
  onPairExpiringFromPantry: (IngredientCatalogItem, IngredientCatalogItem) -> Unit = { _, _ -> },
  onViewTonightRescueFromPantry: (List<Recipe>) -> Unit = {},
  onToggleGroceryChecked: (String) -> Unit = {}, onRestockCheckedGrocery: (List<RestockDelta>) -> Unit = {},
  onSelectGrocerySortMode: (GrocerySortMode) -> Unit = {}, onSelectVenueFilter: (MarketVenue?) -> Unit = {},
  onRequeueSkippedStaple: (String) -> Unit = {}, onOpenUnpackSheet: () -> Unit = {},
  onDismissUnpackSheet: () -> Unit = {},
  onConfirmUnpackGroceries: (List<UnpackItemDirective>, Set<String>, Set<String>) -> Unit = { _, _, _ -> },
  onUncheckAllCheckedGrocery: () -> Unit = {}, onAddCustomGroceryItem: (CustomGroceryItem) -> Unit = {},
  onGroceryCatalogEdit: (GroceryAndCatalogEditAction) -> Unit = {}, onStartGroceryVoiceDictation: () -> Unit = {},
  onOpenRecipeInReader: (String) -> Unit = {}, onSelectRecipeInReader: (String) -> Unit = {},
  onBackToCookbookGrid: () -> Unit = {}, onOpenAddRecipeStudio: (RecipeCreationMode, Boolean) -> Unit = { _, _ -> },
  onForkRecipe: (Recipe) -> Unit = {}, onEditRecipe: (Recipe) -> Unit = {},
  onDismissAddOrEditRecipeSheet: () -> Unit = {}, onParsePastedRecipeText: (String) -> Unit = {},
  onLaunchCookbookCamera: () -> Unit = {}, onLaunchCookbookPhotoPicker: () -> Unit = {},
  onSaveSmartRecipeDraft: (SmartRecipeDraft, Int?, MealSlotType?) -> Unit = { _, _, _ -> },
  onDeleteRecipe: (String) -> Unit = {}, onSwapRecipeIngredient: (String, String, String) -> Unit = { _, _, _ -> },
  onToggleSavedRecipe: (String) -> Unit = {}, onToggleReaderIngredientChecked: (String, String) -> Unit = { _, _ -> },
  onResetReaderCheckedIngredients: (String) -> Unit = {},
  onQueueMissingRecipeIngredients: (Set<String>, List<RestockDelta>) -> Unit = { _, _ -> },
  onAddRecipeKitchenNote: (String, String) -> Unit = { _, _ -> },
  onScheduleRecipeToPlan: (Recipe, Int, MealSlotType) -> Unit = { _, _, _ -> }, onShareRecipe: (Recipe) -> Unit = {},
  onStartCookModeFromReader: (Recipe, Boolean) -> Unit = { _, _ -> },
  onUpdateCookModeSession: (CookModeSessionState) -> Unit = {},
  onMinimizeCookMode: () -> Unit = {}, onResumeCookMode: () -> Unit = {}, onEndCookMode: () -> Unit = {},
  onToggleCookModeHandsFreeMic: (Boolean) -> Unit = {}, onOpenCookModePantryAdjuster: () -> Unit = {},
  onCompleteCookModeAndDeduct: (CookModeSessionState) -> Unit = {},
  onDismissPostCookSummary: () -> Unit = {},
  onCommitPostCookFollowUp: (Boolean, PostCookReactionTag?) -> Unit = { _, _ -> },
  onAiChefAction: (AiChefAction) -> Unit = {}, onUndoLastAgentAction: () -> Unit = {},
  onDismissAgentActionBanner: () -> Unit = {}, onOpenFamilyProfile: () -> Unit = {},
  onDismissFamilyProfile: () -> Unit = {},
  onSaveHouseholdProfile: (HouseholdProfile, Set<String>) -> Unit = { _, _ -> },
  onSelectConsumerTemplate: (ConsumerHouseholdTemplate) -> Unit = {},
  onSelectCurrencyAndRegion: (HouseholdCurrency, String) -> Unit = { _, _ -> },
  onOpenQaLab: () -> Unit = {}, onDismissQaLab: () -> Unit = {}, onSelectClockHour: (Int) -> Unit = {},
  onResetHouseholdToSeed: () -> Unit = {}, onTriggerQaScenario: (QaEdgeCaseScenario) -> Unit = {},
  onCompleteFirstRunOnboarding: (FirstRunOnboardingDraft) -> Unit = {}, onSkipFirstRunOnboarding: () -> Unit = {},
  onRelaunchFirstRunOnboarding: () -> Unit = {}, onDismissOnboardingReplay: () -> Unit = {},
  exportController: KitchenExportController = KitchenExportController(), modifier: Modifier = Modifier,
) {
  val configuration = LocalConfiguration.current
  // Detect headless Compose @Preview rendering so ActivityResult launchers are bypassed safely.
  val isInspectionPreview = LocalInspectionMode.current
  // Detect live software keyboard visibility so bottom navigation yields space to text inputs.
  val isImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
  val isWideScreen = configuration.screenWidthDp >= SCAFFOLD_WIDE_COUNTER_BREAKPOINT_DP || uiState.isWideCounterLayout
  val profile = uiState.snapshot.householdProfile
  val currencySymbol = profile.currency.symbol
  val expiringCount = uiState.pantryExpiryDashboard.expiringItems.size
  val allHouseholdRecipes = uiState.snapshot.allHouseholdRecipes
  val allRecipesById = remember(allHouseholdRecipes) { allHouseholdRecipes.associateBy { it.recipeId } }
  var isAiModelSetupExpanded by rememberSaveable { mutableStateOf(false) }

  // Tick running step timers once per second while Cook Mode is minimized into ActiveCookMiniBar.
  val latestMinimizedSessionRef = rememberUpdatedState(uiState.activeCookModeSession)
  val latestOnUpdateCookRef = rememberUpdatedState(onUpdateCookModeSession)
  val isMinimizedTimerTicking = uiState.isCookModeMinimized &&
    uiState.activeCookModeSession?.timersByStepNumber?.values?.any { it.isRunning } == true
  LaunchedEffect(isMinimizedTimerTicking, uiState.activeCookModeSession?.recipe?.recipeId) {
    while (uiState.isCookModeMinimized) {
      val currentSession = latestMinimizedSessionRef.value ?: break
      if (currentSession.timersByStepNumber.values.none { it.isRunning }) break
      delay(1_000L)
      latestOnUpdateCookRef.value(CookModeEngine.tickRunningTimers(currentSession, 1))
    }
  }

  Box(modifier = modifier.fillMaxSize()) {
    when {
      // Render serene full-screen First-Run Onboarding instead of 4-tab chrome when setup is active.
      uiState.isOnboardingActive -> {
        val replay = uiState.isOnboardingReplayOpen && uiState.snapshot.hasCompletedOnboarding
        // Use stateless screen in Compose @Preview and stateful step host at runtime.
        when {
          isInspectionPreview -> FirstRunOnboardingScreen(
            currentStep = 0, draft = FirstRunOnboardingDraft.fromHouseholdProfile(profile, isFreshInstall = !replay),
            isReplayMode = replay, runtimeState = uiState.aiRuntimeState, activeDeviceProfile = uiState.activeDeviceProfile,
            cloudConfig = uiState.cloudLlmConfig, isVerifyingCloudKey = uiState.isVerifyingCloudKey,
            isRefreshingCloudModels = uiState.isRefreshingCloudModels, isSavingBatch = uiState.isSavingBatch,
            toastMessage = uiState.toastMessage, toastEventId = uiState.toastEventId, onDismissToast = onDismissToast,
            onAiChefAction = onAiChefAction, onCompleteOnboarding = onCompleteFirstRunOnboarding,
            onSkipWithDemoKitchen = onSkipFirstRunOnboarding, onDismissReplay = onDismissOnboardingReplay,
          )
          else -> FirstRunOnboardingHost(
            initialProfile = profile, isReplayMode = replay, runtimeState = uiState.aiRuntimeState,
            activeDeviceProfile = uiState.activeDeviceProfile, cloudConfig = uiState.cloudLlmConfig,
            isVerifyingCloudKey = uiState.isVerifyingCloudKey, isRefreshingCloudModels = uiState.isRefreshingCloudModels,
            isSavingBatch = uiState.isSavingBatch, toastMessage = uiState.toastMessage,
            toastEventId = uiState.toastEventId, onDismissToast = onDismissToast, onAiChefAction = onAiChefAction,
            onCompleteOnboarding = onCompleteFirstRunOnboarding, onSkipWithDemoKitchen = onSkipFirstRunOnboarding,
            onDismissReplay = onDismissOnboardingReplay,
          )
        }
      }

      // Primary Material 3 Scaffold switching cleanly between 4-tab bottom bar and wide counter rail.
      else -> {
        val minimizedCookSession = uiState.activeCookModeSession?.takeIf { uiState.isCookModeMinimized }
        Scaffold(
          containerColor = MaterialTheme.colorScheme.background,
          topBar = {
            Column {
              // Persistent top chrome housing Household switcher, Antipolo market pill, and Ask Chef CTA.
              SavorTopAppBar(
                activeDestination = uiState.activeDestination, householdName = profile.householdName,
                marketRegionLabel = profile.marketRegionLabel, isSearchExpanded = uiState.isSearchBarExpanded,
                isDarkTheme = uiState.isDarkCounterTheme, isWideLayout = isWideScreen,
                onOpenFamilyProfile = onOpenFamilyProfile, onOpenMarketSettings = onOpenFamilyProfile,
                onOpenSousChefSheet = { onOpenSousChefSheet(null) }, onToggleSearch = onToggleSearch,
                isBackgroundBusy = uiState.isAnyBackgroundWorkActive || uiState.isVerifyingCloudKey,
              )
              // Keep live Gemma download, SAF copy, or GPU load visible whenever Sous-Chef sheet is closed.
              if (!uiState.isSousChefSheetOpen && (uiState.aiRuntimeState is AiRuntimeState.Importing || uiState.aiRuntimeState is AiRuntimeState.Loading)) {
                SavorGlobalModelProgressStrip(
                  runtimeState = uiState.aiRuntimeState,
                  onOpenSousChefSheet = { onOpenSousChefSheet(null) },
                )
              }
              // Optional global search bar filtering Pantry items and Cookbook dishes.
              if (uiState.isSearchBarExpanded) {
                SavorExpandableSearchBar(
                  query = uiState.searchQuery,
                  onQueryChange = onSearchQueryChange,
                  onClearAndClose = onToggleSearch,
                )
              }
              // Smoothly animated, auto-dismissing toast confirming O(1) commits and EC-01..EC-13 gates.
              SavorDismissibleToastBanner(
                message = uiState.toastMessage,
                onDismiss = onDismissToast,
                toastEventId = uiState.toastEventId,
              )
            }
          },
          bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
              // Dock ActiveCookMiniBar cleanly above the 4-tab bottom NavigationBar when minimized.
              if (minimizedCookSession != null && !isWideScreen) {
                ActiveCookMiniBar(
                  sessionState = minimizedCookSession,
                  onResumeCookMode = onResumeCookMode,
                  onEndCookMode = onEndCookMode,
                )
              }
              // Render flat warm-linen 4-tab NavigationBar on portrait phones when keyboard is closed.
              if (!isWideScreen && !isImeVisible) {
                SavorBottomNavigationBar(
                  activeDestination = uiState.activeDestination,
                  groceryBadgeCount = uiState.groceryBadgeCount,
                  expiringSoonCount = expiringCount,
                  onSelectDestination = onSelectDestination,
                )
              }
            }
          },
        ) { innerPadding ->
          // Chain consumeWindowInsets(innerPadding).imePadding() so soft keyboards lift fields flush.
          Row(modifier = Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding).imePadding()) {
            // Render leading 4-tab NavigationRail in landscape or wide kitchen-counter mode.
            if (isWideScreen) {
              SavorWideNavigationRail(
                activeDestination = uiState.activeDestination,
                groceryBadgeCount = uiState.groceryBadgeCount,
                expiringSoonCount = expiringCount,
                onSelectDestination = onSelectDestination,
              )
            }

            // Route all 4 first-class SavorAI destinations inside the main workspace pane.
            Column(modifier = Modifier.weight(1f).fillMaxSize()) {
              Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                  uiState.isLoading -> SavorVaultLoadingView()
                  else -> when (uiState.activeDestination) {
                    TopLevelDestination.PLAN -> {
                      // Tab 1: Real-date Meal Plan workspace switching between Meal Plan & Batch Prep.
                      Column(modifier = Modifier.fillMaxSize()) {
                        PlanModeSwitcherBar(
                          activeMode = uiState.activePacerMode,
                          batchTaskCount = uiState.weeklyPrepSessionPlan.tasks.size,
                          onSelectMode = onSetPacerMode,
                        )
                        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                          when (uiState.activePacerMode) {
                            PacerMode.DAILY_PACER -> TodayAndWeekScreen(
                              weeklyBoard = uiState.activeDisplayedBoard, dayMealCards = uiState.dayMealCards,
                              activeDayIndex = uiState.activeDayIndex, todayDayIndex = uiState.todayDayIndex,
                              currentHour24 = uiState.currentSimulatedHour24, isWideLayout = isWideScreen,
                              householdProfile = profile, catalog = uiState.snapshot.ingredients,
                              weeklyPrepPlan = uiState.weeklyPrepSessionPlan, instantSwapsBySlotId = uiState.instantSwapsBySlotId,
                              tomorrowBreakfastRecipe = uiState.tomorrowBreakfastRecipe, onSelectDay = onSelectDay,
                              onOpenCustomMealSheet = { onOpenCustomMealSheet(null) },
                              onOpenWeeklyPrep = { onSetPacerMode(PacerMode.WEEKLY_PREP); onOpenWeeklyPrep() },
                              onOpenFamilyGoalsSheet = onOpenFamilyProfile, onOpenAiChefTab = { onOpenSousChefSheet(null) },
                              onStartCookingSlot = onStartCookingSlot, onQuickCookSlot = onQuickCookSlot,
                              onOpenAdjustSlot = onOpenAdjustSlot, onQuickSwapSlotRecipe = onQuickSwapSlotRecipe,
                              onOpenRecipeReader = onOpenRecipeInReader, viewingNextWeek = uiState.viewingNextWeek,
                              onSelectWeekHorizon = onSelectWeekHorizon, onOpenStartNewWeek = onOpenStartNewWeek,
                              onOpenSavedMenus = onOpenSavedMenus, onScanKitchen = onScanReceiptFromPantry,
                              onAddMealForSlot = onAddMealForSlot, onAskChefForSlot = onAskChefForSlot,
                            )
                            PacerMode.WEEKLY_PREP -> MorningPacerScreen(
                              activeMealCard = uiState.activePacerMealCard, weeklyPrepPlan = uiState.weeklyPrepSessionPlan,
                              householdProfile = profile, catalog = uiState.snapshot.ingredients, recipes = allHouseholdRecipes,
                              completedPrepTaskIds = uiState.snapshot.completedPrepTaskIds, initialMode = PacerMode.WEEKLY_PREP,
                              onModeChanged = onSetPacerMode, onFinishAndDeductPantry = onQuickCookSlot,
                              onTogglePrepStationTask = onTogglePrepStationTask, onCommitWeeklyPrepSession = onCommitWeeklyPrep,
                            )
                          }
                        }
                      }
                    }

                    TopLevelDestination.PANTRY -> {
                      // Tab 2: Smart Pantry stocked shelves, 48h Rescue cards, and Quick Shelf Check.
                      val cleanSearch = uiState.searchQuery.trim().lowercase()
                      val visiblePantry = when {
                        cleanSearch.isEmpty() -> uiState.snapshot.pantryEntries
                        else -> uiState.snapshot.pantryEntries.filter { entry ->
                          val name = uiState.snapshot.catalogById[entry.ingredientId]?.displayName.orEmpty().lowercase()
                          name.contains(cleanSearch) || entry.ingredientId.lowercase().contains(cleanSearch)
                        }
                      }
                      SmartPantryScreen(
                        catalog = uiState.snapshot.ingredients, pantryEntries = visiblePantry, recipes = allHouseholdRecipes,
                        householdAllergens = profile.householdAllergens, rescuedMealsCount = uiState.snapshot.rescuedExpiringMealsCount,
                        precomputedDashboard = if (cleanSearch.isEmpty()) uiState.pantryExpiryDashboard else null,
                        queuedGroceryIds = uiState.snapshot.aiAddedGroceryIds, onAdjustPantryQuantity = onAdjustPantryQuantity,
                        onSetStapleStatus = onSetStapleStatus, onToggleLocalAvailability = onToggleLocalAvailability,
                        onToggleManualGroceryQueue = onToggleManualGroceryQueue, onAddIngredientToGrocery = onAddIngredientToGrocery,
                        onAddIngredientToPantry = onAddIngredientToPantry, onAddIngredientToPantryWithExpiry = onAddIngredientToPantryWithExpiry,
                        onScanReceiptAi = onScanReceiptFromPantry, onRunAiAudit = onRunAiAuditFromPantry,
                        onFindRescueRecipes = onFindRescueRecipesFromPantry, onPairExpiringIngredients = onPairExpiringFromPantry,
                        onViewTonightRescuePlan = onViewTonightRescueFromPantry, currencySymbol = currencySymbol,
                        isExportingPdf = exportController.isExportingPdf, onCopyPantryToClipboard = exportController.onCopyPantryInventory,
                        onSavePantryAsPdf = exportController.onSavePantryPdf, onEditCatalogIngredient = onGroceryCatalogEdit,
                        todayIso = uiState.realTodayIso, isShelfAuditModeActive = uiState.isShelfAuditModeActive,
                        onSetProportionalLevel = onSetProportionalLevel, onMarkItemUsed = onMarkItemUsed,
                        onMoveItemToZone = onMoveItemToZone, onBrowseRecipes = { onSelectDestination(TopLevelDestination.RECIPES) },
                      )
                    }

                    TopLevelDestination.RECIPES -> {
                      // Tab 3: 2-Column Visual Cookbook Grid by default; Clean Recipe Reader on selection.
                      when (uiState.selectedRecipeId) {
                        null -> CookbookGridScreen(
                          recipes = allHouseholdRecipes, catalog = uiState.snapshot.ingredients,
                          pantryEntries = uiState.snapshot.pantryEntries, householdProfile = profile,
                          savedRecipeIds = uiState.snapshot.savedRecipeIds, searchQuery = uiState.searchQuery,
                          onSelectRecipe = onSelectRecipeInReader, onToggleSavedRecipe = onToggleSavedRecipe,
                          onOpenAddRecipeSheet = { onOpenAddRecipeStudio(RecipeCreationMode.QUICK_EDITOR, false) },
                        )
                        else -> CleanRecipeReaderScreen(
                          recipes = allHouseholdRecipes, catalog = uiState.snapshot.ingredients,
                          pantryEntries = uiState.snapshot.pantryEntries, householdProfile = profile,
                          selectedRecipeId = uiState.selectedRecipeId, savedRecipeIds = uiState.snapshot.savedRecipeIds,
                          checkedIngredientsByRecipeId = uiState.snapshot.checkedReaderIngredientsByRecipeId,
                          recipeNotesByRecipeId = uiState.snapshot.recipeNotesByRecipeId, searchQuery = uiState.searchQuery,
                          defaultPlanDayIndex = uiState.activeDayIndex, onSelectRecipe = onSelectRecipeInReader,
                          onToggleSavedRecipe = onToggleSavedRecipe, onToggleIngredientChecked = onToggleReaderIngredientChecked,
                          onResetCheckedIngredients = onResetReaderCheckedIngredients, onQueueMissingIngredients = onQueueMissingRecipeIngredients,
                          onAddKitchenNote = onAddRecipeKitchenNote, onScheduleRecipeToPlan = onScheduleRecipeToPlan,
                          onStartCookMode = onStartCookModeFromReader,
                          onOpenCustomMealBuilder = { onOpenAddRecipeStudio(RecipeCreationMode.QUICK_EDITOR, false) },
                          onEditFamilyGoals = onOpenFamilyProfile, onShareRecipe = onShareRecipe,
                          showAllRecipesBackButton = true, onBackToCookbookGrid = onBackToCookbookGrid,
                          onForkRecipe = onForkRecipe, onEditRecipe = onEditRecipe,
                          onDeleteRecipe = onDeleteRecipe, onSwapRecipeIngredient = onSwapRecipeIngredient,
                        )
                      }
                    }

                    TopLevelDestination.GROCERIES -> {
                      // Tab 4: Smart Grocery List with Palengke vs Supermarket filtering & Unpack sheet.
                      SmartGroceryScreen(
                        groceryDiff = uiState.weeklyGroceryDiff, checkedGroceryItemIds = uiState.snapshot.checkedGroceryItemIds,
                        customGroceryItems = uiState.snapshot.customGroceryItems, sortMode = uiState.snapshot.grocerySortMode,
                        householdProfile = profile, batchWriteCallCount = uiState.batchWriteCallCount,
                        tonightMealSummary = uiState.tonightMealSummary, showPlanSwitcher = false,
                        existingCatalogIds = uiState.snapshot.ingredients.map { it.ingredientId }.toSet(),
                        onToggleItemChecked = onToggleGroceryChecked, onRestockCheckedItems = onRestockCheckedGrocery,
                        onSelectSortMode = onSelectGrocerySortMode, onAddCustomGroceryItem = onAddCustomGroceryItem,
                        onAddCatalogItemToGrocery = onAddIngredientToGrocery, onAddCatalogItemToPantry = onAddIngredientToPantry,
                        onRemoveManualQueue = onRemoveManualGroceryQueue, onClearCheckedItems = onUncheckAllCheckedGrocery,
                        onStartVoiceDictation = onStartGroceryVoiceDictation,
                        onOpenWeeklyMealPlan = {
                          onSelectDestination(TopLevelDestination.PLAN)
                          onSelectGroceriesSubDestination(GroceriesSubDestination.WEEKLY_MEAL_PLAN)
                        },
                        onOpenQaLab = onOpenQaLab, currencySymbol = currencySymbol, marketRegionLabel = profile.marketRegionLabel,
                        isExportingPdf = exportController.isExportingPdf, onCopyListToClipboard = exportController.onCopyGroceryList,
                        onSaveListAsPdf = exportController.onSaveGroceryPdf, catalogById = uiState.snapshot.catalogById,
                        dismissedItemCount = uiState.snapshot.dismissedGroceryItemIds.size, onEditGroceryOrCatalog = onGroceryCatalogEdit,
                        activeVenueFilter = uiState.activeGroceryVenueFilter, onSelectVenueFilter = onSelectVenueFilter,
                        onRequeueSkippedStaple = onRequeueSkippedStaple, onOpenUnpackSheet = onOpenUnpackSheet,
                      )
                    }
                  }
                }
              }

              // Keep ActiveCookMiniBar docked at the bottom of wide-screen counter layouts as well.
              if (minimizedCookSession != null && isWideScreen) {
                ActiveCookMiniBar(
                  sessionState = minimizedCookSession,
                  onResumeCookMode = onResumeCookMode,
                  onEndCookMode = onEndCookMode,
                )
              }
            }
          }
        }

        // Full-screen Step-by-Step Cook Mode overlay when active and not minimized.
        val cookSession = uiState.activeCookModeSession
        if (cookSession != null && !uiState.isCookModeMinimized) {
          StepByStepCookModeScreen(
            sessionState = cookSession, catalog = uiState.snapshot.ingredients,
            pantryEntries = uiState.snapshot.pantryEntries, householdProfile = profile,
            onSessionStateChange = onUpdateCookModeSession, onToggleHandsFreeMic = onToggleCookModeHandsFreeMic,
            onOpenPantryAdjuster = onOpenCookModePantryAdjuster,
            onCompleteAndDeductPantry = onCompleteCookModeAndDeduct,
            onExitCookMode = onEndCookMode, onMinimizeCookMode = onMinimizeCookMode,
          )
        }

        // Mount all 10 modal bottom sheets (`SousChef`, `NewWeek`, `Menus`, `Studio`, `Unpack`, etc.).
        SavorModalSheetsOverlay(
          uiState = uiState, studioDefaultSlotType = studioDefaultSlotType,
          allHouseholdRecipes = allHouseholdRecipes, allRecipesById = allRecipesById,
          isAiModelSetupExpanded = isAiModelSetupExpanded,
          onToggleAiSetup = { isAiModelSetupExpanded = !isAiModelSetupExpanded },
          onSelectDestination = onSelectDestination, onDismissSousChefSheet = onDismissSousChefSheet,
          onConfirmStartNewWeek = onConfirmStartNewWeek, onDismissStartNewWeek = onDismissStartNewWeek,
          onApplySavedMenu = onApplySavedMenu, onSaveCurrentWeekAsMenu = onSaveCurrentWeekAsMenu,
          onDismissSavedMenus = onDismissSavedMenus, onParsePastedRecipeText = onParsePastedRecipeText,
          onLaunchCookbookCamera = onLaunchCookbookCamera, onLaunchCookbookPhotoPicker = onLaunchCookbookPhotoPicker,
          onSaveSmartRecipeDraft = onSaveSmartRecipeDraft,
          onDismissAddOrEditRecipeSheet = onDismissAddOrEditRecipeSheet,
          onConfirmUnpackGroceries = onConfirmUnpackGroceries, onDismissUnpackSheet = onDismissUnpackSheet,
          onCommitPostCookFollowUp = onCommitPostCookFollowUp, onDismissPostCookSummary = onDismissPostCookSummary,
          onDismissAdjustSlot = onDismissAdjustSlot, onConfirmCookedOverride = onConfirmCookedOverride,
          onSelectWorkoutModifier = onSelectWorkoutModifier, onQuickSwapSlotRecipe = onQuickSwapSlotRecipe,
          onMarkSkippedOrLeftovers = onMarkSkippedOrLeftovers, onMoveOrSwapSlot = onMoveOrSwapSlot,
          onToggleSlotPinned = onToggleSlotPinned, onMarkSkipReason = onMarkSkipReason,
          onClearSlot = onClearSlot, onOpenCustomMealSheet = onOpenCustomMealSheet,
          onDismissCustomMealSheet = onDismissCustomMealSheet, onSchedulePresetRecipe = onSchedulePresetRecipe,
          onSaveCustomMeal = onSaveCustomMeal, onAddIngredientToPantry = onAddIngredientToPantry,
          onAiChefAction = onAiChefAction, onUndoLastAgentAction = onUndoLastAgentAction,
          onDismissAgentActionBanner = onDismissAgentActionBanner, onOpenFamilyProfile = onOpenFamilyProfile,
          onDismissFamilyProfile = onDismissFamilyProfile, onSaveHouseholdProfile = onSaveHouseholdProfile,
          onSelectConsumerTemplate = onSelectConsumerTemplate, onSelectCurrencyAndRegion = onSelectCurrencyAndRegion,
          onToggleDarkTheme = onToggleDarkTheme, onToggleWideLayout = onToggleWideLayout,
          onRelaunchFirstRunOnboarding = onRelaunchFirstRunOnboarding, onOpenQaLab = onOpenQaLab,
          onDismissQaLab = onDismissQaLab, onSelectClockHour = onSelectClockHour,
          onResetHouseholdToSeed = onResetHouseholdToSeed, onTriggerQaScenario = onTriggerQaScenario,
        )
      }
    }

    // Approval-gated Gemma 4 download dialog rendered over both First-Run Onboarding and 4-tab shell.
    val approvalSpec = uiState.pendingModelDownloadApproval
    if (approvalSpec != null) {
      ModelDownloadApprovalDialog(
        approvalSpec = approvalSpec,
        onSelectPreset = { preset -> onAiChefAction(AiChefAction.RequestModelDownloadApproval(preset)) },
        onApproveDownload = { preset -> onAiChefAction(AiChefAction.ConfirmApprovedModelDownload(preset)) },
        onDismiss = { onAiChefAction(AiChefAction.DismissModelDownloadApproval) },
      )
    }
  }
}

// Pure-Kotlin preview helper avoiding Android disk or JNI calls during IDE @Preview rendering.
private fun scaffoldV2BuildPreviewUiState(
  destination: TopLevelDestination,
  subDestination: GroceriesSubDestination = GroceriesSubDestination.WEEKLY_MEAL_PLAN,
  isWideLayout: Boolean = false,
  isDarkTheme: Boolean = false,
): KitchenUiState {
  // Hydrate deterministic preview snapshot with 1 queued olive-oil reminder.
  val snapshot = KitchenSnapshot(
    ingredients = PreviewFixtures.catalog, recipes = PreviewFixtures.recipes,
    pantryEntries = PreviewFixtures.pantryEntries, weeklyBoard = PreviewFixtures.weeklyBoard,
    householdProfile = PreviewFixtures.householdProfile, aiAddedGroceryIds = setOf("ing_olive_oil"),
  )
  return KitchenUiState.fromSnapshot(
    snapshot = snapshot, activeDestination = destination, groceriesSubDestination = subDestination,
    isWideCounterLayout = isWideLayout, isDarkCounterTheme = isDarkTheme, batchWriteCallCount = 2,
  )
}

/** Portrait Warm Daylight `@Preview` of the unified 4-tab `SavorAI` shell (`Plan` active). */
@Preview(name = "KitchenScaffold — Portrait Daylight", showBackground = true, widthDp = 412, heightDp = 915)
@Composable
fun KitchenScaffoldPortraitDaylightPreview() {
  // Showcase portrait 4-tab bottom NavigationBar + first-class Meal Plan cards in Warm Daylight.
  FamilyKitchenBoardTheme(darkTheme = false) {
    KitchenScaffoldContent(
      uiState = scaffoldV2BuildPreviewUiState(
        destination = TopLevelDestination.PLAN,
        subDestination = GroceriesSubDestination.WEEKLY_MEAL_PLAN,
      ),
    )
  }
}

/** Landscape Ambient Counter Dark `@Preview` of `KitchenScaffold` with 4-tab `NavigationRail`. */
@Preview(name = "KitchenScaffold — Landscape Counter Dark", showBackground = true, widthDp = 960, heightDp = 580)
@Composable
fun KitchenScaffoldLandscapeCounterDarkPreview() {
  // Showcase wide counter NavigationRail + Smart Pantry Expiry in Ambient Counter Dark mode.
  FamilyKitchenBoardTheme(darkTheme = true) {
    KitchenScaffoldContent(
      uiState = scaffoldV2BuildPreviewUiState(
        destination = TopLevelDestination.PANTRY, isWideLayout = true, isDarkTheme = true,
      ),
    )
  }
}
