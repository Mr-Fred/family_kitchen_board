package com.familykitchen.board.ui

import androidx.compose.runtime.Immutable
import com.familykitchen.board.domain.AiMenuAdvisorEngine
import com.familykitchen.board.domain.AvailabilityFilter
import com.familykitchen.board.domain.BaseUnit
import com.familykitchen.board.domain.CloudLlmConfig
import com.familykitchen.board.domain.CookModeSessionState
import com.familykitchen.board.domain.CookOwner
import com.familykitchen.board.domain.EdgeDeviceProfile
import com.familykitchen.board.domain.ElephantKitchenObservation
import com.familykitchen.board.domain.GroceryDiffEngine
import com.familykitchen.board.domain.HouseholdElephantMemory
import com.familykitchen.board.domain.IngredientTier
import com.familykitchen.board.domain.MarketVenue
import com.familykitchen.board.domain.MealSlotStatus
import com.familykitchen.board.domain.MealSlotType
import com.familykitchen.board.domain.MenuPlannerEngine
import com.familykitchen.board.domain.ModelDownloadApprovalSpec
import com.familykitchen.board.domain.PantryExpiryDashboard
import com.familykitchen.board.domain.PantryExpiryEngine
import com.familykitchen.board.domain.ParsedIngredientDraftLine
import com.familykitchen.board.domain.PostCookCompletionSummary
import com.familykitchen.board.domain.PostCookReactionTag
import com.familykitchen.board.domain.PrepStationType
import com.familykitchen.board.domain.ProportionalStockLevel
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.RecipeCreationMode
import com.familykitchen.board.domain.ResolvedMealCard
import com.familykitchen.board.domain.SmartRecipeDraft
import com.familykitchen.board.domain.StapleStatus
import com.familykitchen.board.domain.StorageLocation
import com.familykitchen.board.domain.WeeklyBoard
import com.familykitchen.board.domain.WeeklyGroceryDiff
import com.familykitchen.board.domain.WeeklyPrepSessionPlan
import com.familykitchen.board.domain.WeeklyRolloverEngine
import com.familykitchen.board.domain.addDaysIso
import com.familykitchen.board.domain.distillActionIntoElephantMemory
import com.familykitchen.board.domain.setProportionalPantryLevel
import com.familykitchen.board.domain.setStapleStatus
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ports.KitchenSnapshot
import com.familykitchen.board.ports.VoiceInputState
import com.familykitchen.board.ui.pacer.PacerMode
import com.familykitchen.board.ui.sheets.CustomFamilyMealDraft
import com.familykitchen.board.ui.sheets.QA_MORNING_SIMULATED_HOUR_24

// Immutable unidirectional UI state contract and pure state-derivation pipeline for SavorAI v2.0.
// Precomputes PantryExpiryDashboard (with live calendar aging), WeeklyGroceryDiff, Prep, and swaps.

/**
 * Primary 4-tab destinations hosted in the `SavorAI` bottom `NavigationBar` and wide-counter
 * `NavigationRail` (with Sous-Chef invoked via a contextual modal sheet from any tab).
 *
 * All labels and subtitles remain strictly free of slashes and parentheses.
 *
 * @property navLabel Compact label displayed beneath navigation icons.
 * @property headerSubtitle Contextual screen subtitle rendered beside the `SavorAI` top-bar brand.
 */
enum class TopLevelDestination(
  val navLabel: String,
  val headerSubtitle: String,
) {
  // Tab 1: Real-date Weekly Meal Plan + 4-Station Weekly Batch Prep.
  /** 7-day Family Kitchen Meal Plan and Weekly Batch Prep. */
  PLAN(navLabel = "Plan", headerSubtitle = "Meal Plan"),

  // Tab 2: Smart Pantry stocked shelves, 48h Action Required rescue cards, and Shelf Audit.
  /** Two-tier household pantry and expiry dashboard (`smart_pantry_expiry` UI spec). */
  PANTRY(navLabel = "Pantry", headerSubtitle = "Pantry"),

  // Tab 3: 2-Column Visual Cookbook Grid and editorial Clean Recipe Reader.
  /** Cookbook grid browser and Clean Recipe Reader (`recipe_reader` UI spec). */
  RECIPES(navLabel = "Recipes", headerSubtitle = "Recipes"),

  // Tab 4: Smart Grocery List with Palengke vs Supermarket filtering and O(1) unpack batch.
  /** Smart Grocery List (`smart_grocery_list` UI spec). */
  GROCERIES(navLabel = "Shop", headerSubtitle = "Grocery List"),
}

/**
 * Sub-destinations hosted inside the [TopLevelDestination.GROCERIES] / `PLAN` switcher.
 *
 * @property displayLabel Human-readable segmented pill title.
 */
enum class GroceriesSubDestination(val displayLabel: String) {
  // Aisle-sorted Smart Grocery List with chronological reservations and O(1) batch restock.
  /** Smart Grocery List (`EC-09` package rounding, `EC-11` staple resets, deduplication banner). */
  GROCERY_LIST("Grocery List"),

  // 7-day Family Kitchen Board with 8:30 PM night-prep banner and 1-pot 4-way plating.
  /** 7-day Weekly Meal Plan (`TodayAndWeekScreen`). */
  WEEKLY_MEAL_PLAN("Weekly Meal Plan"),

  // 4-station Weekly Meal Prep Session batch planner.
  /** 4-station Weekly Batch Prep (`MorningPacerScreen`). */
  MORNING_PACER("Batch Prep"),
}

/**
 * Active microphone consumer target determining where finalized speech transcripts are routed.
 */
enum class VoiceCaptureTarget {
  // Routes finalized utterances into AiChefScreen's Sous-Chef composer bar.
  /** Standard SavorAI Sous-Chef composer voice scribe (`en-US` / `fr-FR`). */
  AI_CHEF,

  // Routes spoken hands-free navigation, timer, and Q&A commands into CookModeEngine.
  /** Hands-free Step-by-Step Cook Mode voice control (`"Next step"`, `"Pause timer"`, Q&A). */
  COOK_MODE,

  // Routes spoken shopping items straight onto Smart Grocery List as CustomGroceryItems.
  /** Quick voice dictation bar on `SmartGroceryScreen`. */
  GROCERY_DICTATION,
}

/**
 * Complete immutable presentation state emitted by `KitchenBoardViewModel.uiState` and rendered by
 * `KitchenScaffold`.
 *
 * @property isLoading `true` only during initial cold-start asset/snapshot hydration.
 * @property activeDestination Active primary tab (`PLAN` default, `PANTRY`, `RECIPES`, `GROCERIES`).
 * @property groceriesSubDestination Active sub-view inside `GROCERIES` / `PLAN`.
 * @property activePacerMode Active sub-mode inside `MorningPacerScreen` (`DAILY_PACER` / `WEEKLY_PREP`).
 * @property activePacerSlotId Optional board `slotId` focused inside `MorningPacerScreen`.
 * @property snapshot Authoritative persisted [KitchenSnapshot] (`PHP` `₱` & Antipolo–Marikina defaults).
 * @property pantryExpiryDashboard Derived Smart Pantry shelf-life, rescue, and waste-score summary.
 * @property weeklyGroceryDiff Derived chronological 21-slot reservation and grocery diff (`EC-09`).
 * @property weeklyPrepSessionPlan Derived 4-station Weekly Meal Prep Session plan.
 * @property activeDayIndex Selected 0-based weekday index (`0 = Monday`..`6 = Sunday`).
 * @property todayDayIndex Highlighted real current weekday index (`0 = Monday`..`6 = Sunday`).
 * @property realTodayIso Current local calendar date (`"YYYY-MM-DD"`) synced from `KitchenClockPort`.
 * @property isSimulatedClockOverrideActive `true` when the user pinned a QA simulated clock hour.
 * @property currentSimulatedHour24 Active 24-hour clock (`0..23`, `>= 20` triggers 8:30 PM prep).
 * @property viewingNextWeek `true` when `Plan` is displaying [KitchenSnapshot.nextWeekBoard].
 * @property dayMealCards Chronologically ordered [ResolvedMealCard]s for [activeDayIndex].
 * @property tomorrowBreakfastRecipe Upcoming morning recipe checked for 8:30 PM night-prep steps.
 * @property instantSwapsBySlotId Precomputed `EC-13` 100% in-stock swaps keyed by `slotId`.
 * @property schedulableRecipes Locally available and allergen-safe recipes (`EC-02`/`EC-03`).
 * @property activeCookModeSession Non-null when `StepByStepCookModeScreen` (or Mini-Timer) is active.
 * @property isCookModeMinimized `true` when Cook Mode is collapsed into the persistent Mini-Timer bar.
 * @property postCookSummary Non-null when `PostCookCompletionSheet` is celebrating a finished meal.
 * @property selectedRecipeId Focused [Recipe.recipeId] in `CleanRecipeReaderScreen`, or `null` for `CookbookGridScreen`.
 * @property adjustingSlotId Non-null when `CookedOverrideSheet` is open for a specific `slotId`.
 * @property isCustomMealSheetOpen `true` when `CustomMealSheet` modal bottom sheet is visible.
 * @property customMealInitialSlotType Preselected [MealSlotType] when opening `CustomMealSheet`.
 * @property isSousChefSheetOpen `true` when the contextual Sous-Chef modal sheet is open.
 * @property sousChefContextPromptHint Optional contextual prompt hint surfaced when opening Sous-Chef.
 * @property isStartNewWeekSheetOpen `true` when `StartNewWeekSheet` is open.
 * @property isSavedMenusSheetOpen `true` when `SavedMenusSheet` is open.
 * @property isAddOrEditRecipeSheetOpen `true` when the 3-mode `AddOrEditRecipeSheet` studio is open.
 * @property editingRecipeDraft Editable [SmartRecipeDraft] staged inside `AddOrEditRecipeSheet`.
 * @property editingExistingRecipeId Non-null when editing an existing user-created recipe.
 * @property editingParentRecipeId Non-null when forking a built-in or household recipe (`Make Our Version`).
 * @property initialRecipeCreationMode Initial studio tab (`SMART_PASTE`, `SCAN_COOKBOOK`, `QUICK_EDITOR`).
 * @property scheduleOnSaveByDefault Pre-enables the `Add to Meal Plan` toggle when launched from `Plan`.
 * @property isUnpackGroceriesSheetOpen `true` when `UnpackGroceriesSheet` is open.
 * @property isShelfAuditModeActive `true` when in-place `Empty · Low · Half · Full` Shelf Audit pills are shown.
 * @property activeGroceryVenueFilter Optional `Palengke` vs `Supermarket` filter on `SmartGroceryScreen`.
 * @property isFamilyProfileSheetOpen `true` when `FamilyProfileSheet` modal bottom sheet is visible.
 * @property isQaLabSheetOpen `true` when `QaScenarioLabSheet` modal bottom sheet is visible.
 * @property lastQaScenarioStatusMessage Latest `EC-01`–`EC-13` live scenario verification banner.
 * @property isSearchBarExpanded `true` when the global top-bar search input is expanded.
 * @property searchQuery Current search filter string applied to Cookbook and Pantry views.
 * @property isWideCounterLayout Manual tablet / kitchen-counter multi-column layout override.
 * @property isDarkCounterTheme Ambient Counter Dark mode toggle (`false` = Warm Daylight default).
 * @property aiRuntimeState Live on-device `LiteRtMultimodalEngine` initialization/capability state.
 * @property voiceInputState Live `VoiceInputPort` speech recognition state (`en-US` / `fr-FR`).
 * @property activeVoiceTarget Target screen consuming the current microphone session.
 * @property activeDeviceProfile Selected universal Android RAM-tier on-device hardware profile.
 * @property attachedImagePath Preprocessed JPEG path staged in the Sous-Chef composer, or `null`.
 * @property attachedImageLabel Display filename badge for [attachedImagePath], or `null`.
 * @property isPreprocessingImage `true` while EXIF-rotating and downscaling a newly picked photo.
 * @property preprocessingImageLabel Display filename currently being optimized for Gemma Vision.
 * @property isAiGenerating `true` while an AI Sous-Chef text or multimodal vision turn is running.
 * @property isGeneratingVisionTurn `true` while [isAiGenerating] is executing an image+text scan.
 * @property isSavingBatch `true` while an `O(1)` atomic storage or domain batch write is active.
 * @property isAiContextBannerDismissed Whether the user dismissed `KITCHEN CONTEXT SYNCED`.
 * @property appliedSwapProposalIds Set of `proposalId`s whose board swaps have been committed.
 * @property queuedIngredientProposalIds Set of `proposalId`s whose discovery staples were queued.
 * @property confirmedVisualTurnIds Set of `turnId`s whose photo scan restocks were confirmed.
 * @property batchWriteCallCount Monotonic `KitchenStoragePort.batchWriteCallCount` (`O(1)` proof).
 * @property toastMessage Dismissible confirmation or validation banner shown in `KitchenScaffold`.
 * @property toastEventId Monotonic counter incremented on each non-blank toast so identical consecutive messages restart auto-dismiss timers.
 * @property canUndoLastAgentAction `true` when an undoable Sous-Chef snapshot is available.
 * @property lastAgentActionSummary Concise human summary for the inline Sous-Chef Undo/Adjust bar.
 * @property lastAgentActionAdjustTarget Destination tab opened when the user taps `Adjust`/`View`.
 * @property cloudLlmConfig Non-secret hybrid On-Device / Cloud LLM configuration and key badge state.
 * @property isVerifyingCloudKey `true` while a lightweight Cloud LLM API key health probe is active.
 * @property isRefreshingCloudModels `true` while syncing live `/models` for the active provider.
 * @property isOnboardingReplayOpen `true` when the user manually relaunches guided first-run setup.
 * @property pendingModelDownloadApproval Non-null when `ModelDownloadApprovalDialog` is open.
 */
@Immutable
data class KitchenUiState(
  // True while initial cold-start snapshot and catalog hydration are still in flight.
  val isLoading: Boolean = true,
  // Default landing tab opens the 7-day Family Kitchen Meal Plan immediately.
  val activeDestination: TopLevelDestination = TopLevelDestination.PLAN,
  val groceriesSubDestination: GroceriesSubDestination = GroceriesSubDestination.GROCERY_LIST,
  val activePacerMode: PacerMode = PacerMode.DAILY_PACER,
  val activePacerSlotId: String? = null,
  val snapshot: KitchenSnapshot = uiStateV2EmptyPlaceholderSnapshot(),
  val pantryExpiryDashboard: PantryExpiryDashboard = uiStateV2EmptyExpiryDashboard(),
  val weeklyGroceryDiff: WeeklyGroceryDiff = uiStateV2EmptyGroceryDiff(),
  val weeklyPrepSessionPlan: WeeklyPrepSessionPlan = uiStateV2EmptyPrepPlan(),
  val activeDayIndex: Int = 0,
  val todayDayIndex: Int = 0,
  val realTodayIso: String = "2026-11-23",
  val isSimulatedClockOverrideActive: Boolean = false,
  val currentSimulatedHour24: Int = QA_MORNING_SIMULATED_HOUR_24,
  val viewingNextWeek: Boolean = false,
  val dayMealCards: List<ResolvedMealCard> = emptyList(),
  val tomorrowBreakfastRecipe: Recipe? = null,
  val instantSwapsBySlotId: Map<String, List<Recipe>> = emptyMap(),
  val schedulableRecipes: List<Recipe> = emptyList(),
  val activeCookModeSession: CookModeSessionState? = null,
  val isCookModeMinimized: Boolean = false,
  val postCookSummary: PostCookCompletionSummary? = null,
  val selectedRecipeId: String? = null,
  val adjustingSlotId: String? = null,
  val isCustomMealSheetOpen: Boolean = false,
  val customMealInitialSlotType: MealSlotType = MealSlotType.DINNER,
  val isSousChefSheetOpen: Boolean = false,
  val sousChefContextPromptHint: String? = null,
  val isStartNewWeekSheetOpen: Boolean = false,
  val isSavedMenusSheetOpen: Boolean = false,
  val isAddOrEditRecipeSheetOpen: Boolean = false,
  val editingRecipeDraft: SmartRecipeDraft? = null,
  val editingExistingRecipeId: String? = null,
  val editingParentRecipeId: String? = null,
  val initialRecipeCreationMode: RecipeCreationMode = RecipeCreationMode.QUICK_EDITOR,
  val scheduleOnSaveByDefault: Boolean = false,
  val isUnpackGroceriesSheetOpen: Boolean = false,
  val isShelfAuditModeActive: Boolean = false,
  val activeGroceryVenueFilter: MarketVenue? = null,
  val isFamilyProfileSheetOpen: Boolean = false,
  val isQaLabSheetOpen: Boolean = false,
  val lastQaScenarioStatusMessage: String? = null,
  val isSearchBarExpanded: Boolean = false,
  val searchQuery: String = "",
  val isWideCounterLayout: Boolean = false,
  val isDarkCounterTheme: Boolean = false,
  val aiRuntimeState: AiRuntimeState = AiRuntimeState.Uninitialized,
  val voiceInputState: VoiceInputState = VoiceInputState.Idle,
  val activeVoiceTarget: VoiceCaptureTarget = VoiceCaptureTarget.AI_CHEF,
  val activeDeviceProfile: EdgeDeviceProfile = EdgeDeviceProfile.BALANCED_GEMMA_2B_INT4,
  val attachedImagePath: String? = null,
  val attachedImageLabel: String? = null,
  val isPreprocessingImage: Boolean = false,
  val preprocessingImageLabel: String? = null,
  val isAiGenerating: Boolean = false,
  val isGeneratingVisionTurn: Boolean = false,
  val isSavingBatch: Boolean = false,
  val isAiContextBannerDismissed: Boolean = false,
  val appliedSwapProposalIds: Set<String> = emptySet(),
  val queuedIngredientProposalIds: Set<String> = emptySet(),
  val confirmedVisualTurnIds: Set<String> = emptySet(),
  val batchWriteCallCount: Int = 0,
  val toastMessage: String? = null,
  val toastEventId: Long = 0L,
  val canUndoLastAgentAction: Boolean = false,
  val lastAgentActionSummary: String? = null,
  val lastAgentActionAdjustTarget: TopLevelDestination? = null,
  val cloudLlmConfig: CloudLlmConfig = CloudLlmConfig(),
  val isVerifyingCloudKey: Boolean = false,
  val isRefreshingCloudModels: Boolean = false,
  val isOnboardingReplayOpen: Boolean = false,
  val pendingModelDownloadApproval: ModelDownloadApprovalSpec? = null,
) {
  // Derived flags keep top-bar, banner, and composer spinners synchronized across all 4 tabs.
  /** `true` whenever a brand-new install has not yet finished setup or the user replays onboarding. */
  val isOnboardingActive: Boolean
    get() = !isLoading && (!snapshot.hasCompletedOnboarding || isOnboardingReplayOpen)

  /** `true` whenever app-scoped model discovery, SAF `.part` copying, or GPU loading is active. */
  val isModelOperationInProgress: Boolean
    get() = aiRuntimeState.isBackgroundBusy

  /** `true` whenever vault hydration, model setup, photo preprocessing, AI turn, or batch save runs. */
  val isAnyBackgroundWorkActive: Boolean
    get() = isLoading ||
      isModelOperationInProgress ||
      isPreprocessingImage ||
      isAiGenerating ||
      isVerifyingCloudKey ||
      isRefreshingCloudModels ||
      isSavingBatch

  /** Weekly board currently displayed on `Plan` (`nextWeekBoard` when previewing Next Week, else `weeklyBoard`). */
  val activeDisplayedBoard: WeeklyBoard
    get() = when {
      viewingNextWeek && snapshot.nextWeekBoard != null -> snapshot.nextWeekBoard
      else -> snapshot.weeklyBoard
    }

  // Count unchecked catalog shortages + unchecked custom notes for the Groceries nav badge.
  /** Live count of unchecked shopping items surfaced on the `Shop` navigation badge. */
  val groceryBadgeCount: Int
    get() {
      val uncheckedCatalog = weeklyGroceryDiff.groceryItems.count { item ->
        !item.isChecked && item.ingredientId !in snapshot.checkedGroceryItemIds
      }
      val uncheckedCustom = snapshot.customGroceryItems.count { !it.isChecked }
      return uncheckedCatalog + uncheckedCustom
    }

  /** Count of upcoming `PLANNED` dinners across the active 7-day board for `KITCHEN CONTEXT SYNCED`. */
  val plannedDinnerCount: Int
    get() = activeDisplayedBoard.slots.count { slot ->
      slot.slotType == MealSlotType.DINNER && slot.status == MealSlotStatus.PLANNED
    }

  /**
   * Concise tonight dinner teaser displayed on `SmartGroceryScreen`, resolved strictly from real
   * [todayDayIndex] on [KitchenSnapshot.weeklyBoard] even when the user browsed another day on `Plan`.
   */
  val tonightMealSummary: String
    get() {
      val safeToday = todayDayIndex.coerceIn(0, 6)
      val tonightDinnerSlot = snapshot.weeklyBoard.slots.firstOrNull { slot ->
        slot.dayIndex == safeToday && slot.slotType == MealSlotType.DINNER
      }
      val dinnerTitle = tonightDinnerSlot?.let { snapshot.recipesById[it.recipeId]?.title }
      return when (dinnerTitle) {
        null -> "Tonight: Family One-Pot Dinner"
        else -> "Tonight: $dinnerTitle"
      }
    }

  /** Resolved [ResolvedMealCard] currently open in `CookedOverrideSheet`, or `null`. */
  val adjustingMealCard: ResolvedMealCard?
    get() {
      val targetId = adjustingSlotId ?: return null
      dayMealCards.firstOrNull { it.slot.slotId == targetId }?.let { return it }
      val fallbackSlot = activeDisplayedBoard.slots.firstOrNull { it.slotId == targetId }
        ?: snapshot.weeklyBoard.slots.firstOrNull { it.slotId == targetId }
        ?: return null
      val recipe = snapshot.recipesById[fallbackSlot.recipeId] ?: return null
      return MenuPlannerEngine.buildResolvedMealCard(
        slot = fallbackSlot,
        recipe = recipe,
        catalog = snapshot.ingredients,
        currentHour24 = currentSimulatedHour24,
        householdProfile = snapshot.householdProfile,
      )
    }

  /** Resolved [ResolvedMealCard] focused inside `MorningPacerScreen`, defaulting to breakfast. */
  val activePacerMealCard: ResolvedMealCard?
    get() {
      val explicitId = activePacerSlotId
      if (!explicitId.isNullOrBlank()) {
        dayMealCards.firstOrNull { it.slot.slotId == explicitId }?.let { return it }
        val slot = activeDisplayedBoard.slots.firstOrNull { it.slotId == explicitId }
          ?: snapshot.weeklyBoard.slots.firstOrNull { it.slotId == explicitId }
        val recipe = slot?.let { snapshot.recipesById[it.recipeId] }
        if (slot != null && recipe != null) {
          return MenuPlannerEngine.buildResolvedMealCard(
            slot = slot,
            recipe = recipe,
            catalog = snapshot.ingredients,
            currentHour24 = currentSimulatedHour24,
            householdProfile = snapshot.householdProfile,
          )
        }
      }
      return dayMealCards.firstOrNull { it.slot.slotType == MealSlotType.BREAKFAST }
        ?: dayMealCards.firstOrNull()
    }

  companion object {
    /**
     * Builds a fully derived [KitchenUiState] from [snapshot] for initial loads and `@Preview`s.
     *
     * @param snapshot Source household state snapshot.
     * @param activeDestination Initial top-level tab (`PLAN` default).
     * @param groceriesSubDestination Initial `Groceries` sub-destination.
     * @param isWideCounterLayout Wide kitchen-counter mode flag.
     * @param isDarkCounterTheme Ambient Counter Dark mode flag.
     * @param batchWriteCallCount Current `O(1)` atomic persistence count.
     * @return Fully derived immutable [KitchenUiState].
     */
    fun fromSnapshot(
      snapshot: KitchenSnapshot,
      activeDestination: TopLevelDestination = TopLevelDestination.PLAN,
      groceriesSubDestination: GroceriesSubDestination = GroceriesSubDestination.GROCERY_LIST,
      isWideCounterLayout: Boolean = false,
      isDarkCounterTheme: Boolean = false,
      batchWriteCallCount: Int = 0,
    ): KitchenUiState =
      deriveKitchenUiState(
        current = KitchenUiState(
          isLoading = false,
          activeDestination = activeDestination,
          groceriesSubDestination = groceriesSubDestination,
          isWideCounterLayout = isWideCounterLayout,
          isDarkCounterTheme = isDarkCounterTheme,
        ),
        snapshot = snapshot,
        batchWriteCallCount = batchWriteCallCount,
      )
  }
}

/**
 * Pure single-pass derivation function recomputing [PantryExpiryDashboard] (with live calendar
 * aging when `expiryDateIso != null`), [WeeklyGroceryDiff], [WeeklyPrepSessionPlan],
 * [ResolvedMealCard]s, and `EC-13` instant swap maps whenever [snapshot], horizon, weekday, or
 * wall-clock state changes.
 *
 * @param current Previous [KitchenUiState] preserving transient UI flags, sheets, and search text.
 * @param snapshot Latest immutable [KitchenSnapshot].
 * @param batchWriteCallCount Latest `KitchenStoragePort.batchWriteCallCount` value.
 * @return Synchronized [KitchenUiState] with `isLoading = false`.
 */
fun deriveKitchenUiState(
  current: KitchenUiState,
  snapshot: KitchenSnapshot,
  batchWriteCallCount: Int = current.batchWriteCallCount,
): KitchenUiState {
  val safeDay = current.activeDayIndex.coerceIn(0, 6)
  val profile = snapshot.householdProfile
  val allergens = profile.householdAllergens
  val effectiveRecipes = snapshot.allHouseholdRecipes
  val recipesById = effectiveRecipes.associateBy { it.recipeId }
  val displayedBoard = when {
    current.viewingNextWeek && snapshot.nextWeekBoard != null -> snapshot.nextWeekBoard
    else -> snapshot.weeklyBoard
  }

  // Filter schedulable recipes across all 42+ household dishes (EC-02 hard-hide + EC-03 swaps).
  val schedulable = AvailabilityFilter.filterSchedulableRecipes(
    recipes = effectiveRecipes,
    catalog = snapshot.ingredients,
    householdAllergens = allergens,
    pantry = snapshot.pantryEntries,
  )

  // Activate live calendar shelf-life aging whenever pantry entries carry explicit expiryDateIso.
  val effectiveTodayIso = current.realTodayIso.takeIf { iso ->
    iso.isNotBlank() && snapshot.pantryEntries.any { !it.expiryDateIso.isNullOrBlank() }
  }

  // Compute Smart Pantry expiry cards, storage partitions, PHP ₱ at-risk value, and Waste Score.
  val expiryDashboard = PantryExpiryEngine.evaluatePantryExpiryDashboard(
    pantry = snapshot.pantryEntries,
    catalog = snapshot.ingredients,
    recipes = effectiveRecipes,
    householdAllergens = allergens,
    rescuedMealsCount = snapshot.rescuedExpiringMealsCount,
    todayIso = effectiveTodayIso,
  )

  // Compute chronological 21-slot Tier-1 reservations, shortages, and whole-package rounding (EC-09).
  val groceryDiff = GroceryDiffEngine.computeWeeklyGroceryDiff(
    board = snapshot.weeklyBoard,
    recipes = effectiveRecipes,
    catalog = snapshot.ingredients,
    pantry = snapshot.pantryEntries,
    householdAllergens = allergens,
    aiAddedGroceryIds = snapshot.aiAddedGroceryIds,
    checkedGroceryItemIds = snapshot.checkedGroceryItemIds,
    grocerySortMode = snapshot.grocerySortMode,
    groceryQuantityOverrides = snapshot.groceryQuantityOverrides,
    dismissedGroceryItemIds = snapshot.dismissedGroceryItemIds,
  )

  // Consolidate PLANNED slots on the displayed week into the 4-station Weekly Meal Prep Session plan.
  val prepPlan = MenuPlannerEngine.buildWeeklyMealPrepSessionPlan(
    board = displayedBoard,
    recipes = effectiveRecipes,
    catalog = snapshot.ingredients,
    completedTaskIds = snapshot.completedPrepTaskIds,
  )

  // Resolve chronological Breakfast -> Lunch -> Dinner cards for activeDayIndex on displayedBoard.
  val daySlots = displayedBoard.slots
    .filter { it.dayIndex == safeDay }
    .sortedBy { it.slotType.chronologicalRank }

  val resolvedDayCards = daySlots.mapNotNull { slot ->
    val recipe = recipesById[slot.recipeId] ?: return@mapNotNull null
    MenuPlannerEngine.buildResolvedMealCard(
      slot = slot,
      recipe = recipe,
      catalog = snapshot.ingredients,
      currentHour24 = current.currentSimulatedHour24,
      householdProfile = profile,
    )
  }

  // Resolve tomorrow's unprepped breakfast for the 8:30 PM Night-Before 5-Min Prep banner.
  val tomorrowDay = (safeDay + 1) % 7
  val tomorrowBreakfastSlot = displayedBoard.slots.firstOrNull { slot ->
    slot.dayIndex == tomorrowDay &&
      slot.slotType == MealSlotType.BREAKFAST &&
      slot.status == MealSlotStatus.PLANNED &&
      !slot.isPreppedAhead
  }
  val tomorrowBreakfast = tomorrowBreakfastSlot?.let { recipesById[it.recipeId] }

  // Precompute EC-13 100% in-stock swaps for active day slots (plus any open adjusting slot).
  val swapTargetSlots = buildList {
    addAll(daySlots)
    val adjustId = current.adjustingSlotId
    if (!adjustId.isNullOrBlank() && none { it.slotId == adjustId }) {
      val extra = displayedBoard.slots.firstOrNull { it.slotId == adjustId }
        ?: snapshot.weeklyBoard.slots.firstOrNull { it.slotId == adjustId }
      extra?.let(::add)
    }
  }
  val swapsBySlot = swapTargetSlots.associate { slot ->
    slot.slotId to MenuPlannerEngine.findInstantInStockSwaps(
      slotType = slot.slotType,
      recipes = effectiveRecipes,
      catalog = snapshot.ingredients,
      pantry = snapshot.pantryEntries,
      householdAllergens = allergens,
      targetServings = slot.targetServings,
      excludeRecipeId = slot.recipeId,
    )
  }

  // Keep selectedRecipeId null by default so Recipes opens onto the 2-Column Cookbook Grid;
  // when non-null, verify it still exists in effectiveRecipes (resetting to null if deleted).
  val validSelectedRecipeId = current.selectedRecipeId
    ?.takeIf { id -> effectiveRecipes.any { it.recipeId == id } }

  return current.copy(
    isLoading = false,
    activeDayIndex = safeDay,
    snapshot = snapshot,
    pantryExpiryDashboard = expiryDashboard,
    weeklyGroceryDiff = groceryDiff,
    weeklyPrepSessionPlan = prepPlan,
    dayMealCards = resolvedDayCards,
    tomorrowBreakfastRecipe = tomorrowBreakfast,
    instantSwapsBySlotId = swapsBySlot,
    schedulableRecipes = schedulable,
    selectedRecipeId = validSelectedRecipeId,
    batchWriteCallCount = batchWriteCallCount,
  )
}

private fun uiStateV2EmptyPlaceholderSnapshot(): KitchenSnapshot =
  KitchenSnapshot(
    ingredients = emptyList(),
    recipes = emptyList(),
    pantryEntries = emptyList(),
    weeklyBoard = WeeklyBoard(
      boardId = "board_loading",
      householdId = "household_family_4",
      weekStartDateIso = "2026-11-23",
      slots = emptyList(),
    ),
  )

private fun uiStateV2EmptyExpiryDashboard(): PantryExpiryDashboard =
  PantryExpiryDashboard(
    totalItems = 0,
    expiringItems = emptyList(),
    fridgeItems = emptyList(),
    dryPantryItems = emptyList(),
    freezerItems = emptyList(),
    atRiskValueUsd = 0.0,
    wasteScorePercent = 100,
    rescueRecipes = emptyList(),
  )

private fun uiStateV2EmptyGroceryDiff(): WeeklyGroceryDiff =
  WeeklyGroceryDiff(
    slotSummaries = emptyList(),
    slotSummariesBySlotId = emptyMap(),
    groceryItems = emptyList(),
    groceryItemsByCategory = emptyMap(),
    restockDeltas = emptyList(),
  )

private fun uiStateV2EmptyPrepPlan(): WeeklyPrepSessionPlan =
  WeeklyPrepSessionPlan(
    sessionId = "prep_loading",
    boardId = "board_loading",
    tasks = emptyList(),
    tasksByStation = PrepStationType.entries.associateWith { emptyList() },
    totalPrepMinutes = 0,
    totalTimeSavedMinutes = 0,
    coveredSlotIds = emptyList(),
  )

// Auto-dismiss timing constants kept strictly in Compose UI effects so ViewModel JUnit tests
// remain 100% deterministic without background viewModelScope delay() timers.

/** Default auto-dismiss countdown (`4,000 ms`) for concise confirmation toasts. */
const val DEFAULT_TOAST_AUTO_DISMISS_MS: Long = 4_000L

/** Extended auto-dismiss countdown (`6,000 ms`) for long (`> 90` chars) or warning/error toasts. */
const val EXTENDED_TOAST_AUTO_DISMISS_MS: Long = 6_000L

/** Auto-dismiss countdown (`5,000 ms`) for camera, microphone, and SAF permission feedback strips. */
const val PERMISSION_NOTICE_AUTO_DISMISS_MS: Long = 5_000L

/** Auto-dismiss countdown (`8,000 ms`) for the post-commit Sous-Chef `Undo` / `View` bar. */
const val UNDO_BAR_AUTO_DISMISS_MS: Long = 8_000L

private val EXTENDED_TOAST_KEYWORDS: List<String> =
  listOf("unable", "failed", "error", "blocked", "invalid", "warning")

/**
 * Computes the Compose auto-dismiss duration in milliseconds for [message], granting `6,000 ms`
 * when the trimmed text exceeds `90` characters or contains diagnostic/error keywords, and
 * `4,000 ms` for routine confirmations.
 *
 * @param message Non-blank toast message displayed to the user.
 * @return Auto-dismiss delay in milliseconds (`4_000L` or `6_000L`).
 */
fun computeToastAutoDismissMillis(message: String): Long {
  val trimmed = message.trim()
  val lower = trimmed.lowercase()
  return when {
    trimmed.length > 90 || EXTENDED_TOAST_KEYWORDS.any { keyword -> lower.contains(keyword) } ->
      EXTENDED_TOAST_AUTO_DISMISS_MS
    else -> DEFAULT_TOAST_AUTO_DISMISS_MS
  }
}

/**
 * Updates [KitchenUiState.toastMessage] and increments [KitchenUiState.toastEventId] whenever
 * [message] is non-blank so even two consecutive identical toast messages restart their Compose
 * auto-dismiss countdown cleanly.
 *
 * @param message Toast banner copy to display, or `null` / blank to clear the active banner.
 * @return Updated immutable [KitchenUiState].
 */
fun KitchenUiState.withToast(message: String?): KitchenUiState =
  when {
    message.isNullOrBlank() -> copy(toastMessage = null)
    else -> copy(toastMessage = message, toastEventId = toastEventId + 1L)
  }

// --- Pure v2 Snapshot & Recipe Transition Helpers Shared by ViewModel & Planner Delegate ---
// Keeping these pure immutable reducers here guarantees 0 I/O side effects and fast JUnit execution.

/** Applies [transform] to whichever board (`nextWeekBoard` vs `weeklyBoard`) owns [slotId]. */
internal fun uiStateV2TransformBoardContainingSlot(
  current: KitchenSnapshot,
  viewingNextWeek: Boolean,
  slotId: String,
  nowIso: String,
  transform: (WeeklyBoard) -> WeeklyBoard,
): KitchenSnapshot {
  // Route slot edits to nextWeekBoard when viewingNextWeek or when slotId lives exclusively there.
  // Also hydrate allHouseholdRecipes so any newly scheduled heritage dish persists on snapshot.
  val nextBoard = current.nextWeekBoard
  return when {
    nextBoard != null &&
      (viewingNextWeek || nextBoard.slots.any { it.slotId == slotId }) &&
      (viewingNextWeek || current.weeklyBoard.slots.none { it.slotId == slotId }) ->
      current.copy(recipes = current.allHouseholdRecipes, nextWeekBoard = transform(nextBoard), updatedAtIso = nowIso)
    else ->
      current.copy(recipes = current.allHouseholdRecipes, weeklyBoard = transform(current.weeklyBoard), updatedAtIso = nowIso)
  }
}

/** Converts an existing [Recipe] into an editable [SmartRecipeDraft] for Fork / Edit Studio flows. */
internal fun uiStateV2RecipeToEditableDraft(recipe: Recipe, snapshot: KitchenSnapshot): SmartRecipeDraft {
  // Map BOM lines (respecting learned householdBomOverrides) into editable draft ingredient rows.
  // Preserves matchedCatalogId so subsequent draft saves keep existing Pantry stock untouched.
  val catalogById = snapshot.catalogById
  val lines = recipe.bomLines.map { bom ->
    val item = catalogById[bom.ingredientId]
    val qty = recipe.householdBomOverrides[bom.ingredientId] ?: bom.baseQuantity
    val unit = item?.baseUnit ?: BaseUnit.GRAM
    val tier = item?.tier ?: IngredientTier.TIER_1_QUANTITATIVE
    val name = item?.displayName ?: bom.ingredientId
    ParsedIngredientDraftLine(
      rawLine = "${qty.toInt()} ${unit.wireCode} $name",
      displayName = name, quantity = qty, unit = unit,
      matchedCatalogId = item?.ingredientId, tier = tier,
    )
  }
  // Carry forward multi-cuisine tags, ordered step instructions, and toddler safety prep tip.
  return SmartRecipeDraft(
    title = recipe.title, servings = recipe.baseServings,
    rawPrepMinutes = recipe.rawPrepMinutes, cookMinutes = recipe.cookMinutes,
    allowedSlots = recipe.allowedSlots,
    cuisineTags = recipe.cuisineTags.ifEmpty { listOf(recipe.cuisineTag) },
    ingredientLines = lines, stepInstructions = recipe.morningSteps.map { it.instruction },
    toddlerPrepTip = recipe.toddlerPrepTip ?: "Cut into soft bite-sized pieces safe for young children.",
  )
}

/** Pure snapshot update changing default storage zone and shelf-life days across catalog & pantry. */
internal fun uiStateV2ApplyStorageAndShelfLifeEdit(
  current: KitchenSnapshot,
  ingredientId: String,
  storageLocation: StorageLocation,
  shelfLifeDays: Int,
  todayIso: String,
  nowIso: String,
): KitchenSnapshot {
  // Clamp shelf life to >= 1 day and stamp live expiryDateIso only on stocked non-empty entries.
  // Out-of-stock rows update storageLocation without fabricating phantom expiry dates.
  val safeDays = shelfLifeDays.coerceAtLeast(1)
  val updatedCatalog = current.ingredients.map { item ->
    if (item.ingredientId == ingredientId) {
      item.copy(defaultStorageLocation = storageLocation, defaultShelfLifeDays = safeDays)
    } else {
      item
    }
  }
  // Recompute calendar expiryDateIso relative to realTodayIso so Pantry rescue cards refresh live.
  val updatedPantry = current.pantryEntries.map { entry ->
    when {
      entry.ingredientId != ingredientId -> entry
      entry.isOutOfStock -> entry.copy(storageLocation = storageLocation, updatedAtIso = nowIso)
      else -> entry.copy(
        storageLocation = storageLocation, expiresInDays = safeDays,
        expiryDateIso = addDaysIso(todayIso, safeDays), updatedAtIso = nowIso,
      )
    }
  }
  return current.copy(ingredients = updatedCatalog, pantryEntries = updatedPantry, updatedAtIso = nowIso)
}

/** Pure snapshot update re-queueing a skipped `Pantry Adjusted` staple (`Ran Low` in `Shop`). */
internal fun uiStateV2ApplySkippedStapleRequeue(
  current: KitchenSnapshot,
  ingredientId: String,
  todayIso: String,
  nowIso: String,
): KitchenSnapshot {
  // Mark Tier-2 staples LOW and Tier-1 items EMPTY so GroceryDiffEngine computes exact deficit packs.
  // Clear dismissedGroceryItemIds simultaneously so the re-queued row reappears immediately on Shop.
  val tier = current.pantryById[ingredientId]?.tier
    ?: current.catalogById[ingredientId]?.tier ?: IngredientTier.TIER_1_QUANTITATIVE
  val updatedPantry = when (tier) {
    IngredientTier.TIER_2_STAPLE ->
      setStapleStatus(current.pantryEntries, current.ingredients, ingredientId, StapleStatus.LOW, nowIso)
    IngredientTier.TIER_1_QUANTITATIVE ->
      setProportionalPantryLevel(
        current.pantryEntries, ingredientId, ProportionalStockLevel.EMPTY, current.ingredients, todayIso, nowIso,
      )
  }
  return current.copy(
    pantryEntries = updatedPantry,
    aiAddedGroceryIds = current.aiAddedGroceryIds + ingredientId,
    dismissedGroceryItemIds = current.dismissedGroceryItemIds - ingredientId,
    updatedAtIso = nowIso,
  )
}

/** Pure snapshot update applying Post-Cook `0`-BOM Leftover Lunch and/or reaction distillation. */
internal fun uiStateV2ApplyPostCookFollowUpBatch(
  current: KitchenSnapshot,
  summary: PostCookCompletionSummary,
  saveLeftoverLunch: Boolean,
  reactionTag: PostCookReactionTag?,
  nowIso: String,
): KitchenSnapshot {
  // Schedule tomorrow's 0-BOM leftover lunch without re-reserving raw ingredients in GroceryDiff.
  val cookedRecipe = current.recipesById[summary.recipeId]
  val nextBoard = when {
    saveLeftoverLunch && cookedRecipe != null -> WeeklyRolloverEngine.scheduleLeftoverLunchFromCook(
      board = current.weeklyBoard, cookedRecipe = cookedRecipe,
      cookedDayIndex = summary.cookedDayIndex,
      leftoverServings = summary.extraLeftoverServings.coerceAtLeast(1),
      eveningLeadOwner = CookOwner.WIFE,
    )
    else -> current.weeklyBoard
  }
  // Distill positive/kid-reaction chip into HouseholdElephantMemory in the same atomic commit.
  val nextMemory = when (reactionTag) {
    null -> current.elephantMemory
    else -> distillActionIntoElephantMemory(
      memory = current.elephantMemory,
      observation = ElephantKitchenObservation.MemberNutritionHabitUpdated(
        memberId = "RECIPE_${summary.recipeId}_${reactionTag.name}",
        memberName = summary.recipeTitle, focusSummary = reactionTag.memoryNote,
      ),
      nowIso = nowIso,
    )
  }
  return current.copy(weeklyBoard = nextBoard, elephantMemory = nextMemory, updatedAtIso = nowIso)
}

/** Distills saved household BOM ratios (`EC-05`) into [HouseholdElephantMemory] on cooked override. */
internal fun uiStateV2DistillCookedOverrideMemory(
  current: KitchenSnapshot,
  recipe: Recipe,
  saveAsDefault: Boolean,
  actualUsedQuantities: Map<String, Double>?,
  overriddenQuantities: Map<String, Double>,
  nowIso: String,
): HouseholdElephantMemory {
  // Skip distillation when override was one-time only (EC-04) so temporary guests don't skew defaults.
  if (!saveAsDefault || actualUsedQuantities.isNullOrEmpty()) return current.elephantMemory
  val unitsById = overriddenQuantities.keys.associateWith { id ->
    current.catalogById[id]?.baseUnit ?: BaseUnit.GRAM
  }
  return distillActionIntoElephantMemory(
    memory = current.elephantMemory,
    observation = ElephantKitchenObservation.CookedBomDefaultSaved(
      recipeId = recipe.recipeId, recipeTitle = recipe.title,
      overriddenQuantities = overriddenQuantities, unitsByIngredientId = unitsById,
    ),
    nowIso = nowIso,
  )
}

/** Pure snapshot reducer creating and scheduling a [CustomFamilyMealDraft] in one pass. */
internal fun uiStateV2CreateCustomMealSnapshot(
  current: KitchenSnapshot,
  draft: CustomFamilyMealDraft,
  nowIso: String,
): Pair<KitchenSnapshot, Recipe> {
  // Generate deterministic custom recipe ID and delegate EC-01/EC-02 validation to AiMenuAdvisorEngine.
  val slug = draft.title.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "meal" }
  val batch = AiMenuAdvisorEngine.createAndScheduleCustomFamilyMealBatch(
    board = current.weeklyBoard, existingRecipes = current.allHouseholdRecipes,
    catalog = current.ingredients, recipeId = "rec_custom_${slug}_${current.allHouseholdRecipes.size + 1}",
    title = draft.title, targetSlotType = draft.targetSlotType, targetDayIndex = draft.targetDayIndex,
    applyMondayThroughFriday = draft.applyMondayThroughFriday, rawPrepMinutes = draft.rawPrepMinutes,
    cookMinutes = draft.cookMinutes, bomLines = draft.bomLines,
    queueIngredientsToGrocery = draft.queueIngredientsToGrocery,
    existingQueuedGroceryIds = current.aiAddedGroceryIds, householdProfile = current.householdProfile,
  )
  val next = current.copy(
    weeklyBoard = batch.updatedBoard, recipes = batch.updatedRecipes,
    aiAddedGroceryIds = batch.queuedGroceryIngredientIds, updatedAtIso = nowIso,
  )
  return next to batch.customRecipe
}
