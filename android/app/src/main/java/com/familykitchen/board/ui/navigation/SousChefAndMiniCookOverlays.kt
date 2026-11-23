package com.familykitchen.board.ui.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.AiChefAction
import com.familykitchen.board.domain.ConsumerHouseholdTemplate
import com.familykitchen.board.domain.CookModeSessionState
import com.familykitchen.board.domain.CookedDeductionPayload
import com.familykitchen.board.domain.HouseholdCurrency
import com.familykitchen.board.domain.HouseholdProfile
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.MealSlotType
import com.familykitchen.board.domain.MenuTemplate
import com.familykitchen.board.domain.PlanCadence
import com.familykitchen.board.domain.PostCookReactionTag
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.SlotSkipReason
import com.familykitchen.board.domain.SmartRecipeDraft
import com.familykitchen.board.domain.UnpackItemDirective
import com.familykitchen.board.domain.WorkoutTiming
import com.familykitchen.board.domain.addDaysIso
import com.familykitchen.board.domain.mondayOfIsoDate
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ports.KitchenSnapshot
import com.familykitchen.board.ui.KitchenUiState
import com.familykitchen.board.ui.TopLevelDestination
import com.familykitchen.board.ui.aichef.AiChefActivityHost
import com.familykitchen.board.ui.aichef.AiChefScreen
import com.familykitchen.board.ui.board.CookedOverrideSheet
import com.familykitchen.board.ui.board.SavedMenusSheet
import com.familykitchen.board.ui.board.StartNewWeekSheet
import com.familykitchen.board.ui.cookmode.PostCookCompletionSheet
import com.familykitchen.board.ui.grocery.UnpackGroceriesSheet
import com.familykitchen.board.ui.preview.PreviewFixtures
import com.familykitchen.board.ui.recipes.AddOrEditRecipeSheet
import com.familykitchen.board.ui.sheets.CustomFamilyMealDraft
import com.familykitchen.board.ui.sheets.CustomMealSheet
import com.familykitchen.board.ui.sheets.FamilyProfileSheet
import com.familykitchen.board.ui.sheets.QaEdgeCaseScenario
import com.familykitchen.board.ui.sheets.QaScenarioLabSheet
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme
import com.familykitchen.board.ui.theme.SavorBrandBadge

// Contextual Sous-Chef Modal Bottom Sheet (`SousChefModalSheet`), Persistent Docked Mini-Timer
// Bar (`ActiveCookMiniBar`), and Modal Bottom Sheet Overlay Layer (`SavorModalSheetsOverlay`)
// keeping all 4 primary tabs (`Plan · Pantry · Recipes · Shop`) crisp, modular, and <= 700 LOC.

/**
 * Expandable Material 3 [ModalBottomSheet] (`skipPartiallyExpanded = true`) hosting the contextual
 * `SavorAI Sous-Chef` assistant from any tab without hijacking primary bottom-navigation slots.
 *
 * Renders [AiChefScreen] directly when `LocalInspectionMode.current` is `true` for hermetic
 * `@Preview`s, and mounts [AiChefActivityHost] at runtime with real time, weekday, and catalog
 * context (`currentHour24`, `todayDayIndex`, `catalog`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SousChefModalSheet(
  uiState: KitchenUiState, expiringSoonCount: Int, currencySymbol: String,
  isAiModelSetupExpanded: Boolean, onToggleAiSetup: () -> Unit,
  onAiChefAction: (AiChefAction) -> Unit, onSelectDestination: (TopLevelDestination) -> Unit,
  onOpenFamilyProfile: () -> Unit, onUndoLastAgentAction: () -> Unit,
  onDismissAgentActionBanner: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier,
) {
  // Open fully expanded so chat history, starter chips, and multimodal composer fit cleanly.
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(
    onDismissRequest = onDismiss, sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.background, dragHandle = null, modifier = modifier,
  ) {
    SousChefModalSheetContent(
      uiState = uiState, expiringSoonCount = expiringSoonCount, currencySymbol = currencySymbol,
      isAiModelSetupExpanded = isAiModelSetupExpanded, onToggleAiSetup = onToggleAiSetup,
      onAiChefAction = onAiChefAction, onSelectDestination = onSelectDestination,
      onOpenFamilyProfile = onOpenFamilyProfile, onUndoLastAgentAction = onUndoLastAgentAction,
      onDismissAgentActionBanner = onDismissAgentActionBanner, onDismiss = onDismiss,
    )
  }
}

/**
 * Stateless inner content of [SousChefModalSheet] extracted so Compose `@Preview`s render the
 * `48.dp` `SavorAI Sous-Chef` header, contextual prompt pill, and `AiChefScreen` without a modal
 * window host.
 */
@Composable
fun SousChefModalSheetContent(
  uiState: KitchenUiState, expiringSoonCount: Int, currencySymbol: String = "₱",
  isAiModelSetupExpanded: Boolean = false, onToggleAiSetup: () -> Unit = {},
  onAiChefAction: (AiChefAction) -> Unit = {}, onSelectDestination: (TopLevelDestination) -> Unit = {},
  onOpenFamilyProfile: () -> Unit = {}, onUndoLastAgentAction: () -> Unit = {},
  onDismissAgentActionBanner: () -> Unit = {}, onDismiss: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val isInspectionPreview = LocalInspectionMode.current
  val extendedColors = KitchenTheme.extendedColors
  // Dismiss sheet automatically when navigating to Plan, Shop, or Family Profile from Chef cards.
  val navBoard: () -> Unit = { onDismiss(); onSelectDestination(TopLevelDestination.PLAN) }
  val navGrocery: () -> Unit = { onDismiss(); onSelectDestination(TopLevelDestination.GROCERIES) }
  val openGoals: () -> Unit = { onDismiss(); onOpenFamilyProfile() }
  val onAdjustLastAction = uiState.lastAgentActionAdjustTarget?.let { target ->
    { onDismiss(); onSelectDestination(target) }
  }
  // Sanitize optional contextual prompt hint so zero slashes or parentheses enter the pill label.
  val cleanHint = uiState.sousChefContextPromptHint
    ?.replace("/", " ")?.replace("(", "")?.replace(")", "")?.trim()?.takeIf { it.isNotEmpty() }

  Column(modifier = modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
    // Clean 48.dp modal header with SavorAI Sous-Chef brand, Tune setup toggle, and Close button.
    OverlayV2SousChefSheetHeader(
      isAiSetupExpanded = isAiModelSetupExpanded,
      onToggleAiSetup = onToggleAiSetup, onDismiss = onDismiss,
    )

    // Optional 1-tap contextual launch pill when opened from a specific MealSlotCard or Pantry CTA.
    if (cleanHint != null) {
      Surface(
        color = extendedColors.sageContainer,
        border = BorderStroke(0.5.dp, extendedColors.herbalSage.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
          .clickable { onAiChefAction(AiChefAction.SubmitPrompt(promptText = cleanHint)) },
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f),
          ) {
            Icon(
              imageVector = Icons.Filled.ChatBubbleOutline, contentDescription = null,
              tint = extendedColors.onSageContainer, modifier = Modifier.size(16.dp),
            )
            Text(
              text = "Suggested prompt · $cleanHint", style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.SemiBold, color = extendedColors.onSageContainer,
              maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
          }
          Spacer(modifier = Modifier.width(8.dp))
          Text("Send", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
      }
    }

    // Mount pure AiChefScreen in @Preview inspection mode and AiChefActivityHost at runtime.
    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
      when {
        isInspectionPreview -> AiChefScreen(
          turnHistory = uiState.snapshot.aiTurnHistory, runtimeState = uiState.aiRuntimeState,
          voiceState = uiState.voiceInputState, activeDeviceProfile = uiState.activeDeviceProfile,
          attachedImagePath = uiState.attachedImagePath, attachedImageLabel = uiState.attachedImageLabel,
          onAction = onAiChefAction, onLaunchGalleryPicker = {}, onLaunchCameraCapture = {},
          onRequestVoiceInput = {}, onLaunchModelPicker = {}, expiringSoonCount = expiringSoonCount,
          plannedDinnerCount = uiState.plannedDinnerCount, savedRecipeIds = uiState.snapshot.savedRecipeIds,
          isContextBannerDismissed = uiState.isAiContextBannerDismissed, isGenerating = uiState.isAiGenerating,
          isPreprocessingImage = uiState.isPreprocessingImage, preprocessingImageLabel = uiState.preprocessingImageLabel,
          isGeneratingVisionTurn = uiState.isGeneratingVisionTurn,
          appliedSwapProposalIds = uiState.appliedSwapProposalIds,
          queuedIngredientProposalIds = uiState.queuedIngredientProposalIds,
          confirmedVisualTurnIds = uiState.confirmedVisualTurnIds,
          onOpenFamilyGoals = openGoals, onNavigateToBoard = navBoard,
          onNavigateToGroceryList = navGrocery, currencySymbol = currencySymbol,
          canUndoLastAgentAction = uiState.canUndoLastAgentAction,
          lastAgentActionSummary = uiState.lastAgentActionSummary,
          onUndoLastAgentAction = onUndoLastAgentAction,
          onDismissAgentActionBanner = onDismissAgentActionBanner,
          onAdjustLastAgentAction = onAdjustLastAction, isModelSetupExpanded = isAiModelSetupExpanded,
          onToggleModelSetup = onToggleAiSetup, cloudConfig = uiState.cloudLlmConfig,
          isVerifyingCloudKey = uiState.isVerifyingCloudKey,
          isRefreshingCloudModels = uiState.isRefreshingCloudModels,
          currentHour24 = uiState.currentSimulatedHour24, todayDayIndex = uiState.todayDayIndex,
          catalog = uiState.snapshot.ingredients,
        )

        else -> AiChefActivityHost(
          turnHistory = uiState.snapshot.aiTurnHistory, runtimeState = uiState.aiRuntimeState,
          voiceState = uiState.voiceInputState, activeDeviceProfile = uiState.activeDeviceProfile,
          attachedImagePath = uiState.attachedImagePath, attachedImageLabel = uiState.attachedImageLabel,
          onAction = onAiChefAction, expiringSoonCount = expiringSoonCount,
          plannedDinnerCount = uiState.plannedDinnerCount, savedRecipeIds = uiState.snapshot.savedRecipeIds,
          isContextBannerDismissed = uiState.isAiContextBannerDismissed, isGenerating = uiState.isAiGenerating,
          isPreprocessingImage = uiState.isPreprocessingImage, preprocessingImageLabel = uiState.preprocessingImageLabel,
          isGeneratingVisionTurn = uiState.isGeneratingVisionTurn,
          appliedSwapProposalIds = uiState.appliedSwapProposalIds,
          queuedIngredientProposalIds = uiState.queuedIngredientProposalIds,
          confirmedVisualTurnIds = uiState.confirmedVisualTurnIds,
          onOpenFamilyGoals = openGoals, onNavigateToBoard = navBoard,
          onNavigateToGroceryList = navGrocery, currencySymbol = currencySymbol,
          canUndoLastAgentAction = uiState.canUndoLastAgentAction,
          lastAgentActionSummary = uiState.lastAgentActionSummary,
          onUndoLastAgentAction = onUndoLastAgentAction,
          onDismissAgentActionBanner = onDismissAgentActionBanner,
          onAdjustLastAgentAction = onAdjustLastAction, isModelSetupExpanded = isAiModelSetupExpanded,
          onToggleModelSetup = onToggleAiSetup, cloudConfig = uiState.cloudLlmConfig,
          isVerifyingCloudKey = uiState.isVerifyingCloudKey,
          isRefreshingCloudModels = uiState.isRefreshingCloudModels,
          currentHour24 = uiState.currentSimulatedHour24, todayDayIndex = uiState.todayDayIndex,
          catalog = uiState.snapshot.ingredients,
        )
      }
    }
  }
}

/**
 * Persistent docked Mini-Timer bar rendered cleanly above [SavorBottomNavigationBar] whenever
 * `activeCookModeSession != null && isCookModeMinimized`, displaying the active dish title, live
 * step and `mm:ss` countdown timer, and `48.dp` **`Resume`** and **`End`** buttons.
 */
@Composable
fun ActiveCookMiniBar(
  sessionState: CookModeSessionState,
  onResumeCookMode: () -> Unit,
  onEndCookMode: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Strip any slashes or parentheses from imported recipe titles so Mini-Bar stays clean.
  val cleanTitle = sessionState.recipe.title.replace("/", " ").replace("(", "").replace(")", "").trim()
  val stepAndTimerLine = overlayV2FormatActiveTimerSummary(sessionState)

  // Warm ceramic card docked flush above the 4-tab bar so timers stay visible while browsing Pantry or Plan.
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    border = BorderStroke(1.dp, extendedColors.herbalSage.copy(alpha = 0.5f)),
    shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
    shadowElevation = 2.dp, modifier = modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp)
        .padding(horizontal = 14.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      // Botanical timer emblem anchoring the active cook status line.
      Box(
        modifier = Modifier.size(38.dp).clip(CircleShape).background(extendedColors.sageContainer),
        contentAlignment = Alignment.Center,
      ) {
        Icon(Icons.Filled.Timer, contentDescription = null, tint = extendedColors.onSageContainer, modifier = Modifier.size(20.dp))
      }

      // Active recipe title + current step and live mm:ss countdown readout.
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          text = cleanTitle, style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = stepAndTimerLine, style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
          maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
      }

      // 48.dp Resume button restoring full-screen Step-by-Step Cook Mode.
      Button(
        onClick = onResumeCookMode, shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(
          containerColor = MaterialTheme.colorScheme.primary,
          contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
      ) {
        Text("Resume", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
      }

      // 48.dp End button closing the Cook Mode session cleanly.
      OutlinedButton(
        onClick = onEndCookMode, shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        border = BorderStroke(1.dp, extendedColors.borderSubtle),
        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
      ) {
        Text(
          text = "End", style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1, softWrap = false,
        )
      }
    }
  }
}

/**
 * Stateless modal bottom-sheet layer mounting all 10 `SavorAI` v2.0 bottom sheets (`SousChefModalSheet`,
 * `StartNewWeekSheet`, `SavedMenusSheet`, `AddOrEditRecipeSheet`, `UnpackGroceriesSheet`,
 * `PostCookCompletionSheet`, `CookedOverrideSheet`, `CustomMealSheet`, `FamilyProfileSheet`, and
 * `QaScenarioLabSheet`) cleanly over `KitchenScaffoldContent`.
 */
@Composable
internal fun SavorModalSheetsOverlay(
  uiState: KitchenUiState, studioDefaultSlotType: MealSlotType,
  allHouseholdRecipes: List<Recipe>, allRecipesById: Map<String, Recipe>,
  isAiModelSetupExpanded: Boolean, onToggleAiSetup: () -> Unit,
  onSelectDestination: (TopLevelDestination) -> Unit, onDismissSousChefSheet: () -> Unit,
  onConfirmStartNewWeek: (PlanCadence, Boolean) -> Unit, onDismissStartNewWeek: () -> Unit,
  onApplySavedMenu: (MenuTemplate, Boolean) -> Unit, onSaveCurrentWeekAsMenu: (String, String) -> Unit,
  onDismissSavedMenus: () -> Unit, onParsePastedRecipeText: (String) -> Unit,
  onLaunchCookbookCamera: () -> Unit, onLaunchCookbookPhotoPicker: () -> Unit,
  onSaveSmartRecipeDraft: (SmartRecipeDraft, Int?, MealSlotType?) -> Unit,
  onDismissAddOrEditRecipeSheet: () -> Unit,
  onConfirmUnpackGroceries: (List<UnpackItemDirective>, Set<String>, Set<String>) -> Unit,
  onDismissUnpackSheet: () -> Unit,
  onCommitPostCookFollowUp: (Boolean, PostCookReactionTag?) -> Unit, onDismissPostCookSummary: () -> Unit,
  onDismissAdjustSlot: () -> Unit, onConfirmCookedOverride: (CookedDeductionPayload) -> Unit,
  onSelectWorkoutModifier: (String, WorkoutTiming) -> Unit, onQuickSwapSlotRecipe: (String, String) -> Unit,
  onMarkSkippedOrLeftovers: (String) -> Unit, onMoveOrSwapSlot: (String, Int, MealSlotType) -> Unit,
  onToggleSlotPinned: (String) -> Unit, onMarkSkipReason: (String, SlotSkipReason) -> Unit,
  onClearSlot: (String) -> Unit, onOpenCustomMealSheet: (String?) -> Unit,
  onDismissCustomMealSheet: () -> Unit, onSchedulePresetRecipe: (Recipe, MealSlotType, Int, Boolean) -> Unit,
  onSaveCustomMeal: (CustomFamilyMealDraft) -> Unit, onAddIngredientToPantry: (IngredientCatalogItem) -> Unit,
  onAiChefAction: (AiChefAction) -> Unit, onUndoLastAgentAction: () -> Unit,
  onDismissAgentActionBanner: () -> Unit, onOpenFamilyProfile: () -> Unit, onDismissFamilyProfile: () -> Unit,
  onSaveHouseholdProfile: (HouseholdProfile, Set<String>) -> Unit,
  onSelectConsumerTemplate: (ConsumerHouseholdTemplate) -> Unit,
  onSelectCurrencyAndRegion: (HouseholdCurrency, String) -> Unit,
  onToggleDarkTheme: () -> Unit, onToggleWideLayout: () -> Unit,
  onRelaunchFirstRunOnboarding: () -> Unit, onOpenQaLab: () -> Unit, onDismissQaLab: () -> Unit,
  onSelectClockHour: (Int) -> Unit, onResetHouseholdToSeed: () -> Unit,
  onTriggerQaScenario: (QaEdgeCaseScenario) -> Unit,
) {
  val profile = uiState.snapshot.householdProfile
  val currencySymbol = profile.currency.symbol
  val expiringCount = uiState.pantryExpiryDashboard.expiringItems.size

  // Sheet 1: Contextual SavorAI Sous-Chef accessible from every tab.
  if (uiState.isSousChefSheetOpen) {
    SousChefModalSheet(
      uiState = uiState, expiringSoonCount = expiringCount, currencySymbol = currencySymbol,
      isAiModelSetupExpanded = isAiModelSetupExpanded, onToggleAiSetup = onToggleAiSetup,
      onAiChefAction = onAiChefAction, onSelectDestination = onSelectDestination,
      onOpenFamilyProfile = onOpenFamilyProfile, onUndoLastAgentAction = onUndoLastAgentAction,
      onDismissAgentActionBanner = onDismissAgentActionBanner, onDismiss = onDismissSousChefSheet,
    )
  }

  // Sheet 2: Start New Week & Carry-Over Rollover (`WeeklyRolloverEngine`).
  if (uiState.isStartNewWeekSheetOpen) {
    val baseMonday = mondayOfIsoDate(uiState.realTodayIso)
    val targetMonday = if (uiState.viewingNextWeek) addDaysIso(baseMonday, 7) else baseMonday
    StartNewWeekSheet(
      currentBoard = uiState.activeDisplayedBoard, recipesById = allRecipesById,
      targetWeekStartDateIso = targetMonday, initialCadence = uiState.snapshot.planCadence,
      onConfirmStartNewWeek = onConfirmStartNewWeek, onDismiss = onDismissStartNewWeek,
    )
  }

  // Sheet 3: Household Saved Menus ranked by preferred cuisines.
  if (uiState.isSavedMenusSheetOpen) {
    SavedMenusSheet(
      savedMenus = uiState.snapshot.savedMenus, householdProfile = profile,
      currentBoard = uiState.activeDisplayedBoard, recipesById = allRecipesById,
      onApplyMenuTemplate = onApplySavedMenu, onSaveCurrentWeekAsMenu = onSaveCurrentWeekAsMenu,
      onDismiss = onDismissSavedMenus,
    )
  }

  // Sheet 4: 3-Mode Recipe Studio (`Smart Paste · Scan Cookbook · Quick Editor`).
  if (uiState.isAddOrEditRecipeSheetOpen) {
    val editingRecipe = uiState.editingExistingRecipeId?.let { allRecipesById[it] }
    val isVisionReady = (uiState.aiRuntimeState as? AiRuntimeState.Ready)?.capabilities?.supportsVision == true
    var skipPostSaveDismiss by remember { mutableStateOf(false) }
    AddOrEditRecipeSheet(
      catalog = uiState.snapshot.ingredients, householdProfile = profile,
      editingRecipe = editingRecipe, initialDraft = uiState.editingRecipeDraft,
      initialMode = uiState.initialRecipeCreationMode, openedFromPlan = uiState.scheduleOnSaveByDefault,
      defaultDayIndex = uiState.activeDayIndex, defaultSlotType = studioDefaultSlotType,
      isVisionModelReady = isVisionReady,
      isScanningPhoto = uiState.isPreprocessingImage || uiState.isGeneratingVisionTurn,
      onParseRawText = onParsePastedRecipeText, onScanCookbookPhoto = onLaunchCookbookCamera,
      onChooseCookbookPhoto = onLaunchCookbookPhotoPicker,
      onSaveRecipeDraft = { draft, _, scheduleTarget ->
        // Preserve editingExistingRecipeId & editingParentRecipeId until saveSmartRecipeDraft runs.
        skipPostSaveDismiss = true
        onSaveSmartRecipeDraft(draft, scheduleTarget?.dayIndex, scheduleTarget?.slotType)
      },
      onDismiss = {
        when {
          skipPostSaveDismiss -> skipPostSaveDismiss = false
          else -> onDismissAddOrEditRecipeSheet()
        }
      },
    )
  }

  // Sheet 5: Zone-Aware Smart Grocery Unpack (`Fridge · Pantry · Freezer`).
  if (uiState.isUnpackGroceriesSheetOpen) {
    val checkedCatalog = uiState.weeklyGroceryDiff.groceryItems.filter { item ->
      item.isChecked || item.ingredientId in uiState.snapshot.checkedGroceryItemIds
    }
    val checkedCustom = uiState.snapshot.customGroceryItems.filter { it.isChecked }
    UnpackGroceriesSheet(
      checkedCatalogItems = checkedCatalog, checkedCustomItems = checkedCustom,
      catalogById = uiState.snapshot.catalogById, pantryById = uiState.snapshot.pantryById,
      onConfirmUnpack = onConfirmUnpackGroceries, onDismiss = onDismissUnpackSheet,
    )
  }

  // Sheet 6: Post-Cook Celebration, 0-BOM Leftover Lunch, & Elephant Reaction Tags.
  val postCook = uiState.postCookSummary
  if (postCook != null) {
    PostCookCompletionSheet(
      summary = postCook, onConfirmPostCook = onCommitPostCookFollowUp, onDismiss = onDismissPostCookSummary,
    )
  }

  // Sheet 7: Upgraded Meal Adjust, Swap, Move Day, Pin, Skip Reason, & Clear.
  val adjustingCard = uiState.adjustingMealCard
  if (adjustingCard != null) {
    CookedOverrideSheet(
      card = adjustingCard, catalog = uiState.snapshot.ingredients,
      instantSwapCandidates = uiState.instantSwapsBySlotId[adjustingCard.slot.slotId].orEmpty(),
      onDismiss = onDismissAdjustSlot, onConfirmCookedDeduction = onConfirmCookedOverride,
      onSelectWorkoutModifier = onSelectWorkoutModifier, onSwapToInStockMeal = onQuickSwapSlotRecipe,
      onMarkSkippedOrLeftovers = onMarkSkippedOrLeftovers,
      onReplaceWithCustomMeal = { slotId -> onOpenCustomMealSheet(slotId) },
      allRecipes = allHouseholdRecipes, preferredCuisines = profile.preferredCuisines,
      onMoveOrSwapSlot = onMoveOrSwapSlot, onToggleSlotPinned = onToggleSlotPinned,
      onMarkSkipReason = onMarkSkipReason, onClearSlot = onClearSlot,
    )
  }

  // Sheet 8: Schedule Preset or Custom Family Meal (`Single Day` or `Mon–Fri`).
  if (uiState.isCustomMealSheetOpen) {
    CustomMealSheet(
      catalog = uiState.snapshot.ingredients, schedulableRecipes = uiState.schedulableRecipes,
      householdProfile = profile, initialDayIndex = uiState.activeDayIndex,
      initialSlotType = uiState.customMealInitialSlotType, onSelectPresetRecipe = onSchedulePresetRecipe,
      onSaveCustomMeal = onSaveCustomMeal, onAddLocalCatalogItem = onAddIngredientToPantry,
      onDismiss = onDismissCustomMealSheet,
    )
  }

  // Sheet 9: Family Profile, Preferred Cuisines, Market Settings, & QA Lab Link.
  if (uiState.isFamilyProfileSheetOpen) {
    FamilyProfileSheet(
      householdProfile = profile, onSaveProfile = onSaveHouseholdProfile,
      onSelectConsumerTemplate = onSelectConsumerTemplate,
      onSelectCurrencyAndRegion = onSelectCurrencyAndRegion,
      isDarkCounterTheme = uiState.isDarkCounterTheme,
      isWideCounterLayout = uiState.isWideCounterLayout,
      onToggleDarkTheme = onToggleDarkTheme, onToggleWideLayout = onToggleWideLayout,
      onRelaunchOnboarding = onRelaunchFirstRunOnboarding,
      onOpenQaLab = { onDismissFamilyProfile(); onOpenQaLab() },
      onDismiss = onDismissFamilyProfile,
    )
  }

  // Sheet 10: Developer QA Scenario Lab (`EC-01`–`EC-13` + O(1) Batch Counter).
  if (uiState.isQaLabSheetOpen) {
    QaScenarioLabSheet(
      batchWriteCallCount = uiState.batchWriteCallCount,
      schedulableRecipeCount = uiState.schedulableRecipes.size,
      totalRecipeCount = allHouseholdRecipes.size,
      currentSimulatedHour24 = uiState.currentSimulatedHour24,
      lastScenarioStatusMessage = uiState.lastQaScenarioStatusMessage,
      onSelectClockHour = onSelectClockHour, onResetHouseholdState = onResetHouseholdToSeed,
      onTriggerScenario = onTriggerQaScenario, onSimulateFreshInstall = onRelaunchFirstRunOnboarding,
      onDismiss = onDismissQaLab,
    )
  }
}

@Composable
private fun OverlayV2SousChefSheetHeader(
  isAiSetupExpanded: Boolean,
  onToggleAiSetup: () -> Unit,
  onDismiss: () -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Clean 48.dp+ top sheet bar hosting brand badge, Tune toggle pill, and Close button.
  Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp)
          .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f),
        ) {
          SavorBrandBadge(badgeSize = 32.dp, logoSize = 20.dp, shape = RoundedCornerShape(10.dp))
          Text(
            text = "SavorAI Sous-Chef", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
          )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          // 48.dp Tune pill toggling On-Device Gemma 4 and Cloud LLM runtime setup.
          Box(
            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
              .clip(CircleShape).clickable(onClick = onToggleAiSetup),
            contentAlignment = Alignment.Center,
          ) {
            Surface(
              shape = CircleShape,
              color = if (isAiSetupExpanded) extendedColors.sageContainer else MaterialTheme.colorScheme.surfaceContainerLow,
              border = BorderStroke(1.dp, extendedColors.borderSubtle),
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
              ) {
                Icon(
                  imageVector = Icons.Filled.Tune, contentDescription = "Tune model setup",
                  tint = if (isAiSetupExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(16.dp),
                )
                Text(
                  text = "Tune", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                  color = if (isAiSetupExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 1, softWrap = false,
                )
              }
            }
          }

          // 48.dp Close button dismissing SousChefModalSheet cleanly back to the active tab.
          IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Close Sous-Chef", tint = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }
      HorizontalDivider(thickness = 1.dp, color = extendedColors.borderSubtle)
    }
  }
}

// Formats "Step k of N · mm:ss" from the active or background running step timer with 0 "/" or "()".
private fun overlayV2FormatActiveTimerSummary(sessionState: CookModeSessionState): String {
  val totalSteps = sessionState.steps.size.coerceAtLeast(1)
  val stepHumanIndex = (sessionState.currentStepIndex + 1).coerceIn(1, totalSteps)
  val primaryTimer = sessionState.activeStepTimer ?: sessionState.backgroundRunningTimers.firstOrNull()
  return when {
    primaryTimer != null -> {
      val stateWord = when {
        primaryTimer.isRunning -> "Live"
        primaryTimer.isCompleted -> "Done"
        else -> "Paused"
      }
      "Step $stepHumanIndex of $totalSteps · ${primaryTimer.formattedRemainingMmSs} $stateWord"
    }
    else -> "Step $stepHumanIndex of $totalSteps"
  }
}

@Preview(name = "ActiveCookMiniBar — Daylight", showBackground = true, widthDp = 390)
@Composable
private fun ActiveCookMiniBarDaylightPreview() {
  // Verifies docked mini-timer layout, mm:ss countdown, and 48.dp Resume + End pills in Daylight.
  FamilyKitchenBoardTheme(darkTheme = false) {
    ActiveCookMiniBar(
      sessionState = PreviewFixtures.activeSalmonCookSession,
      onResumeCookMode = {}, onEndCookMode = {},
    )
  }
}

@Preview(name = "SousChefModalSheet — Counter Dark", showBackground = true, widthDp = 412, heightDp = 760)
@Composable
private fun SousChefModalSheetDarkPreview() {
  // Verifies SavorAI Sous-Chef modal sheet header, Tune toggle, and preview chat host in Dark mode.
  val snapshot = KitchenSnapshot(
    ingredients = PreviewFixtures.catalog, recipes = PreviewFixtures.recipes,
    pantryEntries = PreviewFixtures.pantryEntries, weeklyBoard = PreviewFixtures.weeklyBoard,
    householdProfile = PreviewFixtures.householdProfile,
  )
  val state = KitchenUiState.fromSnapshot(snapshot = snapshot).copy(
    isSousChefSheetOpen = true, sousChefContextPromptHint = "Help swap Monday dinner",
    currentSimulatedHour24 = 19,
  )
  FamilyKitchenBoardTheme(darkTheme = true) {
    SousChefModalSheetContent(uiState = state, expiringSoonCount = 3, currencySymbol = "₱")
  }
}
