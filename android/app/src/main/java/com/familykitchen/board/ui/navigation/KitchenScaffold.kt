package com.familykitchen.board.ui.navigation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.familykitchen.board.domain.AiChefAction
import com.familykitchen.board.domain.ConsumerHouseholdTemplate
import com.familykitchen.board.domain.CookModeSessionState
import com.familykitchen.board.domain.CookedDeductionPayload
import com.familykitchen.board.domain.CustomGroceryItem
import com.familykitchen.board.domain.ExpiringPantryCardModel
import com.familykitchen.board.domain.FirstRunOnboardingDraft
import com.familykitchen.board.domain.GrocerySortMode
import com.familykitchen.board.domain.HouseholdCurrency
import com.familykitchen.board.domain.HouseholdProfile
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.MealSlotType
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.RestockDelta
import com.familykitchen.board.domain.StapleStatus
import com.familykitchen.board.domain.StorageLocation
import com.familykitchen.board.domain.WeeklyPrepBatchCommitPayload
import com.familykitchen.board.domain.WorkoutTiming
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ports.KitchenSnapshot
import com.familykitchen.board.ui.GroceriesSubDestination
import com.familykitchen.board.ui.KitchenBoardViewModel
import com.familykitchen.board.ui.KitchenUiState
import com.familykitchen.board.ui.TopLevelDestination
import com.familykitchen.board.ui.aichef.AiChefActivityHost
import com.familykitchen.board.ui.aichef.AiChefScreen
import com.familykitchen.board.ui.aichef.ModelDownloadApprovalDialog
import com.familykitchen.board.ui.board.CookedOverrideSheet
import com.familykitchen.board.ui.board.TodayAndWeekScreen
import com.familykitchen.board.ui.cookmode.StepByStepCookModeScreen
import com.familykitchen.board.ui.grocery.SmartGroceryScreen
import com.familykitchen.board.ui.onboarding.FirstRunOnboardingHost
import com.familykitchen.board.ui.onboarding.FirstRunOnboardingScreen
import com.familykitchen.board.ui.pacer.MorningPacerScreen
import com.familykitchen.board.ui.pacer.PacerMode
import com.familykitchen.board.ui.pantry.SmartPantryScreen
import com.familykitchen.board.ui.preview.PreviewFixtures
import com.familykitchen.board.ui.recipes.CleanRecipeReaderScreen
import com.familykitchen.board.ui.sheets.CustomFamilyMealDraft
import com.familykitchen.board.ui.sheets.CustomMealSheet
import com.familykitchen.board.ui.sheets.FamilyProfileSheet
import com.familykitchen.board.ui.sheets.QaEdgeCaseScenario
import com.familykitchen.board.ui.sheets.QaScenarioLabSheet
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme

// Unified SavorAI Material 3 Shell routing all 5 first-class tabs (Chef, Plan, Pantry, Recipes,
// Shop), full-screen Step-by-Step Cook Mode, and all 4 modal bottom sheets with zero dead controls.

// Automatic horizontal breakpoint switching portrait NavigationBar to counter NavigationRail.
private const val WIDE_COUNTER_BREAKPOINT_DP: Int = 720

/**
 * Stateful top-level `SavorAI` shell collecting [KitchenBoardViewModel.uiState] via
 * `collectAsStateWithLifecycle()`, gating `RECORD_AUDIO` runtime permissions for Smart Grocery
 * dictation and hands-free Cook Mode, and delegating all UI rendering to [KitchenScaffoldContent].
 */
@Composable
fun KitchenScaffold(
  viewModel: KitchenBoardViewModel,
  modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  var pendingAudioCallback by remember { mutableStateOf<(() -> Unit)?>(null) }

  // Request RECORD_AUDIO on demand when Smart Grocery dictation or Cook Mode hands-free mic starts.
  // Never requests microphone access eagerly on app startup so privacy posture stays minimal.
  val audioPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
  ) { granted ->
    // Resume the deferred voice dictation or hands-free Cook Mode action once granted.
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

  // Intercept Android back button when Step-by-Step Cook Mode is open so it exits cleanly.
  BackHandler(enabled = uiState.activeCookModeSession != null) {
    viewModel.exitCookMode()
  }

  // Forward all stateless UI state slices and explicit ViewModel method references.
  KitchenScaffoldContent(
    uiState = uiState, onSelectDestination = viewModel::selectDestination,
    onSelectGroceriesSubDestination = viewModel::selectGroceriesSubDestination,
    onOpenWeeklyPrep = viewModel::openWeeklyPrepSession, onSetPacerMode = viewModel::setPacerMode,
    onSelectDay = viewModel::selectActiveDay, onToggleSearch = viewModel::toggleSearchBar,
    onSearchQueryChange = viewModel::updateSearchQuery, onToggleDarkTheme = viewModel::toggleDarkCounterTheme,
    onToggleWideLayout = viewModel::toggleWideCounterLayout, onDismissToast = viewModel::dismissToast,
    onStartCookingSlot = viewModel::startCookingSlot, onQuickCookSlot = viewModel::quickCookSlot,
    onOpenAdjustSlot = viewModel::openAdjustSlotSheet, onDismissAdjustSlot = viewModel::dismissAdjustSlotSheet,
    onConfirmCookedOverride = viewModel::confirmCookedOverride,
    onSelectWorkoutModifier = viewModel::selectSlotWorkoutModifier,
    onQuickSwapSlotRecipe = viewModel::quickSwapSlotRecipe,
    onMarkSkippedOrLeftovers = viewModel::markSlotSkippedOrLeftovers,
    onOpenCustomMealSheet = { slotId -> viewModel.openCustomMealSheet(forSlotId = slotId) },
    onDismissCustomMealSheet = viewModel::dismissCustomMealSheet,
    onSchedulePresetRecipe = viewModel::schedulePresetRecipeFromSheet,
    onSaveCustomMeal = viewModel::saveCustomFamilyMeal,
    onTogglePrepStationTask = viewModel::togglePrepStationTask,
    onCommitWeeklyPrep = viewModel::commitWeeklyPrepSession,
    onAdjustPantryQuantity = viewModel::adjustPantryQuantity, onSetStapleStatus = viewModel::setStapleStatus,
    onToggleLocalAvailability = viewModel::toggleLocalAvailability,
    onToggleManualGroceryQueue = viewModel::toggleManualGroceryQueue,
    onRemoveManualGroceryQueue = viewModel::removeManualGroceryQueue,
    onAddIngredientToGrocery = viewModel::addIngredientToGrocery,
    onAddIngredientToPantry = viewModel::addIngredientToPantry,
    onAddIngredientToPantryWithExpiry = viewModel::addIngredientToPantryWithExpiry,
    onScanReceiptFromPantry = viewModel::onScanReceiptFromPantry,
    onRunAiAuditFromPantry = viewModel::onRunAiAuditFromPantry,
    onFindRescueRecipesFromPantry = viewModel::onFindRescueRecipesFromPantry,
    onPairExpiringFromPantry = viewModel::onPairExpiringIngredientsFromPantry,
    onViewTonightRescueFromPantry = viewModel::onViewTonightRescuePlanFromPantry,
    onToggleGroceryChecked = viewModel::toggleGroceryItemChecked,
    onRestockCheckedGrocery = viewModel::commitGroceryRestockBatch,
    onSelectGrocerySortMode = viewModel::setGrocerySortMode,
    onAddCustomGroceryItem = viewModel::addCustomGroceryItem,
    onClearCheckedGrocery = viewModel::clearCheckedGroceryItems,
    onStartGroceryVoiceDictation = { runWithAudioPermission { viewModel.startGroceryVoiceDictation() } },
    onOpenRecipeInReader = viewModel::openRecipeInReader, onSelectRecipeInReader = viewModel::selectRecipe,
    onToggleSavedRecipe = viewModel::toggleSavedRecipe,
    onToggleReaderIngredientChecked = viewModel::toggleReaderIngredientChecked,
    onResetReaderCheckedIngredients = viewModel::resetCheckedReaderIngredients,
    onQueueMissingRecipeIngredients = viewModel::queueMissingRecipeIngredients,
    onAddRecipeKitchenNote = viewModel::addRecipeKitchenNote,
    onScheduleRecipeToPlan = viewModel::scheduleRecipeToPlan, onShareRecipe = viewModel::notifyRecipeShared,
    onStartCookModeFromReader = { recipe, handsFree ->
      when {
        handsFree -> runWithAudioPermission { viewModel.startCookMode(recipe, null, true) }
        else -> viewModel.startCookMode(recipe, null, false)
      }
    },
    onUpdateCookModeSession = viewModel::updateCookModeSession,
    onToggleCookModeHandsFreeMic = { enabled ->
      when {
        enabled -> runWithAudioPermission { viewModel.toggleCookModeHandsFreeMic(true) }
        else -> viewModel.toggleCookModeHandsFreeMic(false)
      }
    },
    onOpenCookModePantryAdjuster = viewModel::openCookModePantryAdjuster,
    onCompleteCookModeAndDeduct = viewModel::completeCookModeAndDeductPantry,
    onExitCookMode = viewModel::exitCookMode, onAiChefAction = viewModel::onAiChefAction,
    onUndoLastAgentAction = viewModel::undoLastAgentAction,
    onDismissAgentActionBanner = viewModel::dismissAgentActionBanner,
    onOpenFamilyProfile = viewModel::openFamilyProfileSheet,
    onDismissFamilyProfile = viewModel::dismissFamilyProfileSheet,
    onSaveHouseholdProfile = viewModel::saveHouseholdProfile,
    onSelectConsumerTemplate = viewModel::selectConsumerTemplate,
    onSelectCurrencyAndRegion = viewModel::selectCurrencyAndRegion,
    onOpenQaLab = viewModel::openQaLabSheet, onDismissQaLab = viewModel::dismissQaLabSheet,
    onSelectClockHour = viewModel::selectSimulatedClockHour,
    onResetHouseholdToSeed = viewModel::resetHouseholdStateToSeed,
    onTriggerQaScenario = viewModel::triggerQaScenario,
    onCompleteFirstRunOnboarding = viewModel::completeFirstRunOnboarding,
    onSkipFirstRunOnboarding = viewModel::skipFirstRunOnboardingWithDemo,
    onRelaunchFirstRunOnboarding = viewModel::relaunchFirstRunOnboarding,
    onDismissOnboardingReplay = viewModel::dismissOnboardingReplay,
    modifier = modifier,
  )
}

/**
 * Pure stateless `SavorAI` shell Composable accepting an immutable [KitchenUiState] and explicit
 * event callbacks so Android Studio `@Preview`s render cleanly with zero `ViewModel` or JNI calls.
 */
@Composable
fun KitchenScaffoldContent(
  uiState: KitchenUiState,
  onSelectDestination: (TopLevelDestination) -> Unit = {},
  onSelectGroceriesSubDestination: (GroceriesSubDestination) -> Unit = {},
  onOpenWeeklyPrep: () -> Unit = {},
  onSetPacerMode: (PacerMode) -> Unit = {},
  onSelectDay: (Int) -> Unit = {},
  onToggleSearch: () -> Unit = {},
  onSearchQueryChange: (String) -> Unit = {},
  onToggleDarkTheme: () -> Unit = {},
  onToggleWideLayout: () -> Unit = {},
  onDismissToast: () -> Unit = {},
  onStartCookingSlot: (String) -> Unit = {},
  onQuickCookSlot: (String) -> Unit = {},
  onOpenAdjustSlot: (String) -> Unit = {},
  onDismissAdjustSlot: () -> Unit = {},
  onConfirmCookedOverride: (CookedDeductionPayload) -> Unit = {},
  onSelectWorkoutModifier: (String, WorkoutTiming) -> Unit = { _, _ -> },
  onQuickSwapSlotRecipe: (String, String) -> Unit = { _, _ -> },
  onMarkSkippedOrLeftovers: (String) -> Unit = {},
  onOpenCustomMealSheet: (String?) -> Unit = {},
  onDismissCustomMealSheet: () -> Unit = {},
  onSchedulePresetRecipe: (Recipe, MealSlotType, Int, Boolean) -> Unit = { _, _, _, _ -> },
  onSaveCustomMeal: (CustomFamilyMealDraft) -> Unit = {},
  onTogglePrepStationTask: (String, Boolean) -> Unit = { _, _ -> },
  onCommitWeeklyPrep: (WeeklyPrepBatchCommitPayload) -> Unit = {},
  onAdjustPantryQuantity: (String, Double) -> Unit = { _, _ -> },
  onSetStapleStatus: (String, StapleStatus) -> Unit = { _, _ -> },
  onToggleLocalAvailability: (String, Boolean) -> Unit = { _, _ -> },
  onToggleManualGroceryQueue: (String) -> Unit = {},
  onRemoveManualGroceryQueue: (String) -> Unit = {},
  onAddIngredientToGrocery: (IngredientCatalogItem) -> Unit = {},
  onAddIngredientToPantry: (IngredientCatalogItem) -> Unit = {},
  onAddIngredientToPantryWithExpiry: (IngredientCatalogItem, StorageLocation, Int) -> Unit = { _, _, _ -> },
  onScanReceiptFromPantry: () -> Unit = {},
  onRunAiAuditFromPantry: () -> Unit = {},
  onFindRescueRecipesFromPantry: (ExpiringPantryCardModel) -> Unit = {},
  onPairExpiringFromPantry: (IngredientCatalogItem, IngredientCatalogItem) -> Unit = { _, _ -> },
  onViewTonightRescueFromPantry: (List<Recipe>) -> Unit = {},
  onToggleGroceryChecked: (String) -> Unit = {},
  onRestockCheckedGrocery: (List<RestockDelta>) -> Unit = {},
  onSelectGrocerySortMode: (GrocerySortMode) -> Unit = {},
  onAddCustomGroceryItem: (CustomGroceryItem) -> Unit = {},
  onClearCheckedGrocery: () -> Unit = {},
  onStartGroceryVoiceDictation: () -> Unit = {},
  onOpenRecipeInReader: (String) -> Unit = {},
  onSelectRecipeInReader: (String) -> Unit = {},
  onToggleSavedRecipe: (String) -> Unit = {},
  onToggleReaderIngredientChecked: (String, String) -> Unit = { _, _ -> },
  onResetReaderCheckedIngredients: (String) -> Unit = {},
  onQueueMissingRecipeIngredients: (Set<String>, List<RestockDelta>) -> Unit = { _, _ -> },
  onAddRecipeKitchenNote: (String, String) -> Unit = { _, _ -> },
  onScheduleRecipeToPlan: (Recipe, Int, MealSlotType) -> Unit = { _, _, _ -> },
  onShareRecipe: (Recipe) -> Unit = {},
  onStartCookModeFromReader: (Recipe, Boolean) -> Unit = { _, _ -> },
  onUpdateCookModeSession: (CookModeSessionState) -> Unit = {},
  onToggleCookModeHandsFreeMic: (Boolean) -> Unit = {},
  onOpenCookModePantryAdjuster: () -> Unit = {},
  onCompleteCookModeAndDeduct: (CookModeSessionState) -> Unit = {},
  onExitCookMode: () -> Unit = {},
  onAiChefAction: (AiChefAction) -> Unit = {},
  onUndoLastAgentAction: () -> Unit = {},
  onDismissAgentActionBanner: () -> Unit = {},
  onOpenFamilyProfile: () -> Unit = {},
  onDismissFamilyProfile: () -> Unit = {},
  onSaveHouseholdProfile: (HouseholdProfile, Set<String>) -> Unit = { _, _ -> },
  onSelectConsumerTemplate: (ConsumerHouseholdTemplate) -> Unit = {},
  onSelectCurrencyAndRegion: (HouseholdCurrency, String) -> Unit = { _, _ -> },
  onOpenQaLab: () -> Unit = {},
  onDismissQaLab: () -> Unit = {},
  onSelectClockHour: (Int) -> Unit = {},
  onResetHouseholdToSeed: () -> Unit = {},
  onTriggerQaScenario: (QaEdgeCaseScenario) -> Unit = {},
  onCompleteFirstRunOnboarding: (FirstRunOnboardingDraft) -> Unit = {},
  onSkipFirstRunOnboarding: () -> Unit = {},
  onRelaunchFirstRunOnboarding: () -> Unit = {},
  onDismissOnboardingReplay: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val configuration = LocalConfiguration.current
  // Detect headless Compose @Preview rendering so ActivityResult launchers are bypassed safely.
  val isInspectionPreview = LocalInspectionMode.current
  // Detect live software keyboard visibility so bottom navigation yields space to chat composers.
  val isImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
  val isWideScreen = configuration.screenWidthDp >= WIDE_COUNTER_BREAKPOINT_DP || uiState.isWideCounterLayout
  val profile = uiState.snapshot.householdProfile
  val currencySymbol = profile.currency.symbol
  val expiringCount = uiState.pantryExpiryDashboard.expiringItems.size
  // Synchronize top-bar Tune button with the collapsible Edge AI runtime setup drawer in Chef.
  var isAiModelSetupExpanded by rememberSaveable { mutableStateOf(false) }

  Box(modifier = modifier.fillMaxSize()) {
    when {
      // Render serene full-screen First-Run Onboarding instead of 5-tab chrome when setup is active.
      uiState.isOnboardingActive -> {
        val replay = uiState.isOnboardingReplayOpen && uiState.snapshot.hasCompletedOnboarding
        when {
          isInspectionPreview -> FirstRunOnboardingScreen(
            currentStep = 0,
            draft = FirstRunOnboardingDraft.fromHouseholdProfile(profile, isFreshInstall = !replay),
            isReplayMode = replay, runtimeState = uiState.aiRuntimeState,
            activeDeviceProfile = uiState.activeDeviceProfile, cloudConfig = uiState.cloudLlmConfig,
            isVerifyingCloudKey = uiState.isVerifyingCloudKey,
            isRefreshingCloudModels = uiState.isRefreshingCloudModels, isSavingBatch = uiState.isSavingBatch,
            toastMessage = uiState.toastMessage, toastEventId = uiState.toastEventId, onDismissToast = onDismissToast,
            onAiChefAction = onAiChefAction, onCompleteOnboarding = onCompleteFirstRunOnboarding,
            onSkipWithDemoKitchen = onSkipFirstRunOnboarding, onDismissReplay = onDismissOnboardingReplay,
          )
          else -> FirstRunOnboardingHost(
            initialProfile = profile, isReplayMode = replay, runtimeState = uiState.aiRuntimeState,
            activeDeviceProfile = uiState.activeDeviceProfile, cloudConfig = uiState.cloudLlmConfig,
            isVerifyingCloudKey = uiState.isVerifyingCloudKey,
            isRefreshingCloudModels = uiState.isRefreshingCloudModels, isSavingBatch = uiState.isSavingBatch,
            toastMessage = uiState.toastMessage, toastEventId = uiState.toastEventId, onDismissToast = onDismissToast,
            onAiChefAction = onAiChefAction, onCompleteOnboarding = onCompleteFirstRunOnboarding,
            onSkipWithDemoKitchen = onSkipFirstRunOnboarding, onDismissReplay = onDismissOnboardingReplay,
          )
        }
      }

      // Primary Material 3 Scaffold switching cleanly between 5-tab bottom bar and wide counter rail.
      else -> {
        Scaffold(
          containerColor = MaterialTheme.colorScheme.background,
          topBar = {
            Column {
              SavorTopAppBar(
                activeDestination = uiState.activeDestination, householdName = profile.householdName,
                marketRegionLabel = profile.marketRegionLabel, isSearchExpanded = uiState.isSearchBarExpanded,
                isDarkTheme = uiState.isDarkCounterTheme, isWideLayout = isWideScreen,
                isBackgroundBusy = uiState.isAnyBackgroundWorkActive || uiState.isVerifyingCloudKey,
                isAiSetupExpanded = isAiModelSetupExpanded, onOpenQaLab = onOpenQaLab,
                onToggleSearch = onToggleSearch, onToggleDarkTheme = onToggleDarkTheme,
                onToggleWideLayout = onToggleWideLayout,
                onToggleAiSetup = { isAiModelSetupExpanded = !isAiModelSetupExpanded },
                onOpenFamilyProfile = onOpenFamilyProfile,
              )
              // Keep live Gemma download, SAF copy, or GPU shader load visible across non-Chef tabs.
              if (
                uiState.activeDestination != TopLevelDestination.ASK_AI &&
                (uiState.aiRuntimeState is AiRuntimeState.Importing || uiState.aiRuntimeState is AiRuntimeState.Loading)
              ) {
                SavorGlobalModelProgressStrip(
                  runtimeState = uiState.aiRuntimeState,
                  onOpenAiTab = { onSelectDestination(TopLevelDestination.ASK_AI) },
                )
              }
              // Optional global search bar filtering Pantry items and Clean Recipe Reader dishes.
              if (uiState.isSearchBarExpanded) {
                SavorExpandableSearchBar(
                  query = uiState.searchQuery, onQueryChange = onSearchQueryChange,
                  onClearAndClose = onToggleSearch,
                )
              }
              // Smoothly animated, auto-dismissing toast confirming O(1) commits and EC-01..EC-13 gates.
              SavorDismissibleToastBanner(
                message = uiState.toastMessage,
                toastEventId = uiState.toastEventId,
                onDismiss = onDismissToast,
              )
            }
          },
          bottomBar = {
            // Render flat warm-linen 5-tab NavigationBar on portrait phones when keyboard is closed.
            if (!isWideScreen && !isImeVisible) {
              SavorBottomNavigationBar(
                activeDestination = uiState.activeDestination,
                groceryBadgeCount = uiState.groceryBadgeCount, expiringSoonCount = expiringCount,
                onSelectDestination = onSelectDestination,
              )
            }
          },
        ) { innerPadding ->
          // Chain consumeWindowInsets(innerPadding).imePadding() so soft keyboards lift composers flush.
          Row(
            modifier = Modifier.fillMaxSize().padding(innerPadding)
              .consumeWindowInsets(innerPadding).imePadding(),
          ) {
            // Render leading 5-tab NavigationRail in landscape or wide kitchen-counter mode.
            if (isWideScreen) {
              SavorWideNavigationRail(
                activeDestination = uiState.activeDestination,
                groceryBadgeCount = uiState.groceryBadgeCount, expiringSoonCount = expiringCount,
                onSelectDestination = onSelectDestination,
              )
            }

            // Route all 5 first-class SavorAI destinations (or cold-start vault loader) inside main pane.
            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
              when {
                uiState.isLoading -> SavorVaultLoadingView()
                else -> when (uiState.activeDestination) {
                  TopLevelDestination.ASK_AI -> {
                    // Tab 1: SavorAI Sous-Chef with persistent 2-tier composer and 1-tap Undo/Adjust.
                    val navBoard = { onSelectDestination(TopLevelDestination.PLAN) }
                    val navGrocery = { onSelectDestination(TopLevelDestination.GROCERIES) }
                    val onAdjustLastAction = uiState.lastAgentActionAdjustTarget?.let { target ->
                      { onSelectDestination(target) }
                    }
                    when {
                      isInspectionPreview -> AiChefScreen(
                        turnHistory = uiState.snapshot.aiTurnHistory, runtimeState = uiState.aiRuntimeState,
                        voiceState = uiState.voiceInputState, activeDeviceProfile = uiState.activeDeviceProfile,
                        attachedImagePath = uiState.attachedImagePath, attachedImageLabel = uiState.attachedImageLabel,
                        onAction = onAiChefAction, onLaunchGalleryPicker = {}, onLaunchCameraCapture = {},
                        onRequestVoiceInput = {}, onLaunchModelPicker = {}, expiringSoonCount = expiringCount,
                        plannedDinnerCount = uiState.plannedDinnerCount, savedRecipeIds = uiState.snapshot.savedRecipeIds,
                        isContextBannerDismissed = uiState.isAiContextBannerDismissed, isGenerating = uiState.isAiGenerating,
                        isPreprocessingImage = uiState.isPreprocessingImage, preprocessingImageLabel = uiState.preprocessingImageLabel,
                        isGeneratingVisionTurn = uiState.isGeneratingVisionTurn,
                        appliedSwapProposalIds = uiState.appliedSwapProposalIds,
                        queuedIngredientProposalIds = uiState.queuedIngredientProposalIds,
                        confirmedVisualTurnIds = uiState.confirmedVisualTurnIds,
                        onOpenFamilyGoals = onOpenFamilyProfile, onNavigateToBoard = navBoard,
                        onNavigateToGroceryList = navGrocery, currencySymbol = currencySymbol,
                        canUndoLastAgentAction = uiState.canUndoLastAgentAction,
                        lastAgentActionSummary = uiState.lastAgentActionSummary,
                        onUndoLastAgentAction = onUndoLastAgentAction,
                        onDismissAgentActionBanner = onDismissAgentActionBanner,
                        onAdjustLastAgentAction = onAdjustLastAction, isModelSetupExpanded = isAiModelSetupExpanded,
                        onToggleModelSetup = { isAiModelSetupExpanded = !isAiModelSetupExpanded },
                        cloudConfig = uiState.cloudLlmConfig, isVerifyingCloudKey = uiState.isVerifyingCloudKey,
                        isRefreshingCloudModels = uiState.isRefreshingCloudModels,
                      )

                      else -> AiChefActivityHost(
                        turnHistory = uiState.snapshot.aiTurnHistory, runtimeState = uiState.aiRuntimeState,
                        voiceState = uiState.voiceInputState, activeDeviceProfile = uiState.activeDeviceProfile,
                        attachedImagePath = uiState.attachedImagePath, attachedImageLabel = uiState.attachedImageLabel,
                        onAction = onAiChefAction, expiringSoonCount = expiringCount,
                        plannedDinnerCount = uiState.plannedDinnerCount, savedRecipeIds = uiState.snapshot.savedRecipeIds,
                        isContextBannerDismissed = uiState.isAiContextBannerDismissed, isGenerating = uiState.isAiGenerating,
                        isPreprocessingImage = uiState.isPreprocessingImage, preprocessingImageLabel = uiState.preprocessingImageLabel,
                        isGeneratingVisionTurn = uiState.isGeneratingVisionTurn,
                        appliedSwapProposalIds = uiState.appliedSwapProposalIds,
                        queuedIngredientProposalIds = uiState.queuedIngredientProposalIds,
                        confirmedVisualTurnIds = uiState.confirmedVisualTurnIds,
                        onOpenFamilyGoals = onOpenFamilyProfile, onNavigateToBoard = navBoard,
                        onNavigateToGroceryList = navGrocery, currencySymbol = currencySymbol,
                        canUndoLastAgentAction = uiState.canUndoLastAgentAction,
                        lastAgentActionSummary = uiState.lastAgentActionSummary,
                        onUndoLastAgentAction = onUndoLastAgentAction,
                        onDismissAgentActionBanner = onDismissAgentActionBanner,
                        onAdjustLastAgentAction = onAdjustLastAction, isModelSetupExpanded = isAiModelSetupExpanded,
                        onToggleModelSetup = { isAiModelSetupExpanded = !isAiModelSetupExpanded },
                        cloudConfig = uiState.cloudLlmConfig, isVerifyingCloudKey = uiState.isVerifyingCloudKey,
                        isRefreshingCloudModels = uiState.isRefreshingCloudModels,
                      )
                    }
                  }

                  TopLevelDestination.PLAN -> {
                    // Tab 2: First-class Meal Plan workspace switching cleanly between Daily Plan & Batch Prep.
                    Column(modifier = Modifier.fillMaxSize()) {
                      PlanModeSwitcherBar(
                        activeMode = uiState.activePacerMode,
                        batchTaskCount = uiState.weeklyPrepSessionPlan.tasks.size, onSelectMode = onSetPacerMode,
                      )
                      Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                        // Render 7-day Family-of-4 board by default; pivot to Weekly Prep when selected.
                        when (uiState.activePacerMode) {
                          PacerMode.DAILY_PACER -> TodayAndWeekScreen(
                            weeklyBoard = uiState.snapshot.weeklyBoard, dayMealCards = uiState.dayMealCards,
                            activeDayIndex = uiState.activeDayIndex, todayDayIndex = uiState.todayDayIndex,
                            currentHour24 = uiState.currentSimulatedHour24, isWideLayout = isWideScreen,
                            householdProfile = profile, catalog = uiState.snapshot.ingredients,
                            weeklyPrepPlan = uiState.weeklyPrepSessionPlan,
                            instantSwapsBySlotId = uiState.instantSwapsBySlotId,
                            tomorrowBreakfastRecipe = uiState.tomorrowBreakfastRecipe,
                            onSelectDay = onSelectDay, onOpenCustomMealSheet = { onOpenCustomMealSheet(null) },
                            onOpenWeeklyPrep = {
                              // Synchronize local Plan switcher and ViewModel pacer mode together.
                              onSetPacerMode(PacerMode.WEEKLY_PREP)
                              onOpenWeeklyPrep()
                            },
                            onOpenFamilyGoalsSheet = onOpenFamilyProfile,
                            onOpenAiChefTab = { onSelectDestination(TopLevelDestination.ASK_AI) },
                            onStartCookingSlot = onStartCookingSlot, onQuickCookSlot = onQuickCookSlot,
                            onOpenAdjustSlot = onOpenAdjustSlot, onQuickSwapSlotRecipe = onQuickSwapSlotRecipe,
                            onOpenRecipeReader = onOpenRecipeInReader,
                          )

                          // Station-grouped Weekly Batch Prep session committing in 1 O(1) snapshot write.
                          PacerMode.WEEKLY_PREP -> MorningPacerScreen(
                            activeMealCard = uiState.activePacerMealCard,
                            weeklyPrepPlan = uiState.weeklyPrepSessionPlan, householdProfile = profile,
                            catalog = uiState.snapshot.ingredients, recipes = uiState.snapshot.recipes,
                            completedPrepTaskIds = uiState.snapshot.completedPrepTaskIds,
                            initialMode = PacerMode.WEEKLY_PREP, onModeChanged = onSetPacerMode,
                            onFinishAndDeductPantry = onQuickCookSlot,
                            onTogglePrepStationTask = onTogglePrepStationTask,
                            onCommitWeeklyPrepSession = onCommitWeeklyPrep,
                          )
                        }
                      }
                    }
                  }

                  TopLevelDestination.PANTRY -> {
                    // Tab 3: Smart Pantry & 48h Expiry Rescue Dashboard priced in household currency.
                    val cleanSearch = uiState.searchQuery.trim().lowercase()
                    val visiblePantry = when {
                      cleanSearch.isEmpty() -> uiState.snapshot.pantryEntries
                      else -> uiState.snapshot.pantryEntries.filter { entry ->
                        val name = uiState.snapshot.catalogById[entry.ingredientId]?.displayName.orEmpty().lowercase()
                        name.contains(cleanSearch) || entry.ingredientId.lowercase().contains(cleanSearch)
                      }
                    }
                    SmartPantryScreen(
                      catalog = uiState.snapshot.ingredients, pantryEntries = visiblePantry,
                      recipes = uiState.snapshot.recipes, householdAllergens = profile.householdAllergens,
                      rescuedMealsCount = uiState.snapshot.rescuedExpiringMealsCount,
                      precomputedDashboard = if (cleanSearch.isEmpty()) uiState.pantryExpiryDashboard else null,
                      queuedGroceryIds = uiState.snapshot.aiAddedGroceryIds,
                      onAdjustPantryQuantity = onAdjustPantryQuantity, onSetStapleStatus = onSetStapleStatus,
                      onToggleLocalAvailability = onToggleLocalAvailability,
                      onToggleManualGroceryQueue = onToggleManualGroceryQueue,
                      onAddIngredientToGrocery = onAddIngredientToGrocery,
                      onAddIngredientToPantry = onAddIngredientToPantry,
                      onAddIngredientToPantryWithExpiry = onAddIngredientToPantryWithExpiry,
                      onScanReceiptAi = onScanReceiptFromPantry, onRunAiAudit = onRunAiAuditFromPantry,
                      onFindRescueRecipes = onFindRescueRecipesFromPantry,
                      onPairExpiringIngredients = onPairExpiringFromPantry,
                      onViewTonightRescuePlan = onViewTonightRescueFromPantry,
                      currencySymbol = currencySymbol,
                    )
                  }

                  TopLevelDestination.RECIPES -> {
                    // Tab 4: Cookbook Filter Browser + Clean Recipe Reader + Offline ACTION_SEND Share.
                    CleanRecipeReaderScreen(
                      recipes = uiState.schedulableRecipes.ifEmpty { uiState.snapshot.recipes },
                      catalog = uiState.snapshot.ingredients, pantryEntries = uiState.snapshot.pantryEntries,
                      householdProfile = profile, selectedRecipeId = uiState.selectedRecipeId,
                      savedRecipeIds = uiState.snapshot.savedRecipeIds,
                      checkedIngredientsByRecipeId = uiState.snapshot.checkedReaderIngredientsByRecipeId,
                      recipeNotesByRecipeId = uiState.snapshot.recipeNotesByRecipeId,
                      searchQuery = uiState.searchQuery, defaultPlanDayIndex = uiState.activeDayIndex,
                      onSelectRecipe = onSelectRecipeInReader, onToggleSavedRecipe = onToggleSavedRecipe,
                      onToggleIngredientChecked = onToggleReaderIngredientChecked,
                      onResetCheckedIngredients = onResetReaderCheckedIngredients,
                      onQueueMissingIngredients = onQueueMissingRecipeIngredients,
                      onAddKitchenNote = onAddRecipeKitchenNote,
                      onScheduleRecipeToPlan = onScheduleRecipeToPlan,
                      onStartCookMode = onStartCookModeFromReader,
                      onOpenCustomMealBuilder = { onOpenCustomMealSheet(null) },
                      onEditFamilyGoals = onOpenFamilyProfile, onShareRecipe = onShareRecipe,
                    )
                  }

                  TopLevelDestination.GROCERIES -> {
                    // Tab 5: First-class Smart Grocery List rendered directly with zero sub-tab bar.
                    SmartGroceryScreen(
                      groceryDiff = uiState.weeklyGroceryDiff,
                      checkedGroceryItemIds = uiState.snapshot.checkedGroceryItemIds,
                      customGroceryItems = uiState.snapshot.customGroceryItems,
                      sortMode = uiState.snapshot.grocerySortMode, householdProfile = profile,
                      batchWriteCallCount = uiState.batchWriteCallCount,
                      tonightMealSummary = uiState.tonightMealSummary, showPlanSwitcher = false,
                      existingCatalogIds = uiState.snapshot.ingredients.map { it.ingredientId }.toSet(),
                      onToggleItemChecked = onToggleGroceryChecked,
                      onRestockCheckedItems = onRestockCheckedGrocery,
                      onSelectSortMode = onSelectGrocerySortMode,
                      onAddCustomGroceryItem = onAddCustomGroceryItem,
                      onAddCatalogItemToGrocery = onAddIngredientToGrocery,
                      onAddCatalogItemToPantry = onAddIngredientToPantry,
                      onRemoveManualQueue = onRemoveManualGroceryQueue,
                      onClearCheckedItems = onClearCheckedGrocery,
                      onStartVoiceDictation = onStartGroceryVoiceDictation,
                      onOpenWeeklyMealPlan = {
                        onSelectDestination(TopLevelDestination.PLAN)
                        onSelectGroceriesSubDestination(GroceriesSubDestination.WEEKLY_MEAL_PLAN)
                      },
                      onOpenQaLab = onOpenQaLab, currencySymbol = currencySymbol,
                      marketRegionLabel = profile.marketRegionLabel,
                    )
                  }
                }
              }
            }
          }
        }

        // Full-screen Step-by-Step Cook Mode modal overlay (`step_by_step_cook_mode` spec).
        // Holds FLAG_KEEP_SCREEN_ON wake lock and ticks concurrent step timers once per second.
        val cookSession = uiState.activeCookModeSession
        if (cookSession != null) {
          StepByStepCookModeScreen(
            sessionState = cookSession, catalog = uiState.snapshot.ingredients,
            pantryEntries = uiState.snapshot.pantryEntries, householdProfile = profile,
            onSessionStateChange = onUpdateCookModeSession,
            onToggleHandsFreeMic = onToggleCookModeHandsFreeMic,
            onOpenPantryAdjuster = onOpenCookModePantryAdjuster,
            onCompleteAndDeductPantry = onCompleteCookModeAndDeduct, onExitCookMode = onExitCookMode,
          )
        }

        // Modal Bottom Sheet 1: Cooked Override & Meal Adjuster (`EC-04`–`EC-07`, `EC-12`, `EC-13`).
        val adjustingCard = uiState.adjustingMealCard
        if (adjustingCard != null) {
          CookedOverrideSheet(
            card = adjustingCard, catalog = uiState.snapshot.ingredients,
            instantSwapCandidates = uiState.instantSwapsBySlotId[adjustingCard.slot.slotId].orEmpty(),
            onDismiss = onDismissAdjustSlot, onConfirmCookedDeduction = onConfirmCookedOverride,
            onSelectWorkoutModifier = onSelectWorkoutModifier,
            onSwapToInStockMeal = onQuickSwapSlotRecipe,
            onMarkSkippedOrLeftovers = onMarkSkippedOrLeftovers,
            onReplaceWithCustomMeal = { slotId -> onOpenCustomMealSheet(slotId) },
          )
        }

        // Modal Bottom Sheet 2: Add / Schedule Custom Family Meal (`Single Day` or `Mon–Fri`).
        if (uiState.isCustomMealSheetOpen) {
          CustomMealSheet(
            catalog = uiState.snapshot.ingredients, schedulableRecipes = uiState.schedulableRecipes,
            householdProfile = profile, initialDayIndex = uiState.activeDayIndex,
            initialSlotType = uiState.customMealInitialSlotType,
            onSelectPresetRecipe = onSchedulePresetRecipe, onSaveCustomMeal = onSaveCustomMeal,
            onAddLocalCatalogItem = onAddIngredientToPantry, onDismiss = onDismissCustomMealSheet,
          )
        }

        // Modal Bottom Sheet 3: Family Profile, Consumer Templates, Display Modes, & Currency.
        if (uiState.isFamilyProfileSheetOpen) {
          FamilyProfileSheet(
            householdProfile = profile, onSaveProfile = onSaveHouseholdProfile,
            onSelectConsumerTemplate = onSelectConsumerTemplate,
            onSelectCurrencyAndRegion = onSelectCurrencyAndRegion,
            isDarkCounterTheme = uiState.isDarkCounterTheme,
            isWideCounterLayout = uiState.isWideCounterLayout,
            onToggleDarkTheme = onToggleDarkTheme, onToggleWideLayout = onToggleWideLayout,
            onRelaunchOnboarding = onRelaunchFirstRunOnboarding, onDismiss = onDismissFamilyProfile,
          )
        }

        // Modal Bottom Sheet 4: QA & Scenario Verification Lab (`EC-01`–`EC-13` + O(1) Batch Counter).
        if (uiState.isQaLabSheetOpen) {
          QaScenarioLabSheet(
            batchWriteCallCount = uiState.batchWriteCallCount,
            schedulableRecipeCount = uiState.schedulableRecipes.size,
            totalRecipeCount = uiState.snapshot.recipes.size,
            currentSimulatedHour24 = uiState.currentSimulatedHour24,
            lastScenarioStatusMessage = uiState.lastQaScenarioStatusMessage,
            onSelectClockHour = onSelectClockHour, onResetHouseholdState = onResetHouseholdToSeed,
            onTriggerScenario = onTriggerQaScenario,
            onSimulateFreshInstall = onRelaunchFirstRunOnboarding, onDismiss = onDismissQaLab,
          )
        }
      }
    }

    // Approval-gated Gemma 4 download dialog rendered over both First-Run Onboarding and 5-tab shell.
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
private fun buildPreviewUiState(
  destination: TopLevelDestination,
  subDestination: GroceriesSubDestination = GroceriesSubDestination.WEEKLY_MEAL_PLAN,
  isWideLayout: Boolean = false,
  isDarkTheme: Boolean = false,
): KitchenUiState {
  val snapshot = KitchenSnapshot(
    ingredients = PreviewFixtures.catalog,
    recipes = PreviewFixtures.recipes,
    pantryEntries = PreviewFixtures.pantryEntries,
    weeklyBoard = PreviewFixtures.weeklyBoard,
    householdProfile = PreviewFixtures.householdProfile,
    aiAddedGroceryIds = setOf("ing_olive_oil"),
  )
  return KitchenUiState.fromSnapshot(
    snapshot = snapshot,
    activeDestination = destination,
    groceriesSubDestination = subDestination,
    isWideCounterLayout = isWideLayout,
    isDarkCounterTheme = isDarkTheme,
    batchWriteCallCount = 2,
  )
}

/** Portrait Warm Daylight `@Preview` of the unified 5-tab `SavorAI` shell (`Plan` active). */
@Preview(name = "KitchenScaffold — Portrait Daylight", showBackground = true, widthDp = 412, heightDp = 915)
@Composable
fun KitchenScaffoldPortraitDaylightPreview() {
  // Showcase portrait 5-tab bottom NavigationBar + first-class Meal Plan cards in Warm Daylight.
  FamilyKitchenBoardTheme(darkTheme = false) {
    KitchenScaffoldContent(
      uiState = buildPreviewUiState(
        destination = TopLevelDestination.PLAN,
        subDestination = GroceriesSubDestination.WEEKLY_MEAL_PLAN,
      ),
    )
  }
}

/** Landscape Ambient Counter Dark `@Preview` of `KitchenScaffold` with 5-tab `NavigationRail`. */
@Preview(name = "KitchenScaffold — Landscape Counter Dark", showBackground = true, widthDp = 960, heightDp = 580)
@Composable
fun KitchenScaffoldLandscapeCounterDarkPreview() {
  // Showcase wide counter NavigationRail + Smart Pantry Expiry in Ambient Counter Dark mode.
  FamilyKitchenBoardTheme(darkTheme = true) {
    KitchenScaffoldContent(
      uiState = buildPreviewUiState(
        destination = TopLevelDestination.PANTRY,
        isWideLayout = true,
        isDarkTheme = true,
      ),
    )
  }
}
