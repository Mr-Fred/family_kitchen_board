package com.familykitchen.board.ui

import androidx.compose.runtime.Immutable
import com.familykitchen.board.domain.AvailabilityFilter
import com.familykitchen.board.domain.CloudLlmConfig
import com.familykitchen.board.domain.CookModeSessionState
import com.familykitchen.board.domain.EdgeDeviceProfile
import com.familykitchen.board.domain.GroceryDiffEngine
import com.familykitchen.board.domain.MealSlotStatus
import com.familykitchen.board.domain.MealSlotType
import com.familykitchen.board.domain.MenuPlannerEngine
import com.familykitchen.board.domain.PantryExpiryDashboard
import com.familykitchen.board.domain.PantryExpiryEngine
import com.familykitchen.board.domain.PrepStationType
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.ResolvedMealCard
import com.familykitchen.board.domain.WeeklyBoard
import com.familykitchen.board.domain.WeeklyGroceryDiff
import com.familykitchen.board.domain.WeeklyPrepSessionPlan
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ports.KitchenSnapshot
import com.familykitchen.board.ports.VoiceInputState
import com.familykitchen.board.ui.pacer.PacerMode
import com.familykitchen.board.ui.sheets.QA_MORNING_SIMULATED_HOUR_24

// Immutable unidirectional UI state contract and pure state-derivation pipeline for SavorAI.
// Precomputes PantryExpiryDashboard, WeeklyGroceryDiff, WeeklyPrepSessionPlan, and EC-13 swaps.

/**
 * Primary top-level destinations hosted in the `SavorAI` bottom `NavigationBar` and wide-counter
 * `NavigationRail`.
 *
 * @property navLabel Compact label displayed beneath navigation icons.
 * @property headerSubtitle Contextual screen subtitle rendered beside the `SavorAI` top-bar brand.
 */
enum class TopLevelDestination(
  val navLabel: String,
  val headerSubtitle: String,
) {
  // Tab 1: Multimodal SavorAI Sous-Chef chat, photo scan, and rich recipe match cards.
  /** Conversational and multimodal AI Sous-Chef (`ai_assistant` UI spec). */
  ASK_AI(navLabel = "Chef", headerSubtitle = "Sous-Chef"),

  // Tab 2: First-class 21-slot Weekly Meal Plan + 4-Station Weekly Batch Prep.
  /** 7-day Family Kitchen Meal Plan and Weekly Batch Prep. */
  PLAN(navLabel = "Plan", headerSubtitle = "Meal Plan"),

  // Tab 3: Smart Pantry shelf-life pulse bar, 48h Action Required rescue cards, and Fridge Pulse.
  /** Two-tier household pantry and expiry dashboard (`smart_pantry_expiry` UI spec). */
  PANTRY(navLabel = "Pantry", headerSubtitle = "Pantry"),

  // Tab 4: Cookbook filter browser and editorial Clean Recipe Reader.
  /** Cookbook browser and Clean Recipe Reader (`recipe_reader` UI spec). */
  RECIPES(navLabel = "Recipes", headerSubtitle = "Recipes"),

  // Tab 5: Smart Grocery List with chronological reservations and O(1) batch restock.
  /** Smart Grocery List (`smart_grocery_list` UI spec). */
  GROCERIES(navLabel = "Shop", headerSubtitle = "Grocery List"),
}

/**
 * Sub-destinations hosted inside the [TopLevelDestination.GROCERIES] workspace switcher.
 *
 * @property displayLabel Human-readable segmented pill title.
 */
enum class GroceriesSubDestination(val displayLabel: String) {
  // Aisle-sorted Smart Grocery List with chronological reservations and O(1) batch restock.
  /** Smart Grocery List (`EC-09` package rounding, `EC-11` staple resets, deduplication banner). */
  GROCERY_LIST("Grocery List"),

  // 7-day (21-slot) Family Kitchen Board with 8:30 PM night-prep banner and 1-pot 4-way plating.
  /** 7-day (21-slot) Weekly Meal Plan (`TodayAndWeekScreen`). */
  WEEKLY_MEAL_PLAN("Weekly Meal Plan"),

  // <= 25m daily Morning Pacer countdown ring + 4-station Weekly Meal Prep Session batch planner.
  /** Step-by-step `<= 25m` Morning Pacer and 4-station Weekly Batch Prep (`MorningPacerScreen`). */
  MORNING_PACER("Morning Pacer"),
}

/**
 * Active microphone consumer target determining where finalized speech transcripts are routed.
 */
enum class VoiceCaptureTarget {
  // Routes finalized utterances into AiChefScreen's composer bar.
  /** Standard SavorAI composer voice scribe (`en-US` / `fr-FR`). */
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
 * @property activeDestination Active primary tab (`ASK_AI`, `PANTRY`, `RECIPES`, `GROCERIES`).
 * @property groceriesSubDestination Active sub-view inside `GROCERIES`.
 * @property activePacerMode Active sub-mode inside `MorningPacerScreen` (`DAILY_PACER` / `WEEKLY_PREP`).
 * @property activePacerSlotId Optional board `slotId` focused inside `MorningPacerScreen`.
 * @property snapshot Authoritative persisted [KitchenSnapshot] (`PHP` `₱` & Antipolo–Marikina defaults).
 * @property pantryExpiryDashboard Derived Smart Pantry shelf-life, rescue, and waste-score summary.
 * @property weeklyGroceryDiff Derived chronological 21-slot reservation and grocery diff (`EC-09`).
 * @property weeklyPrepSessionPlan Derived 4-station Weekly Meal Prep Session plan.
 * @property activeDayIndex Selected 0-based weekday index (`0 = Monday`..`6 = Sunday`).
 * @property todayDayIndex Highlighted current weekday index (`0 = Monday` default).
 * @property currentSimulatedHour24 Active 24-hour clock (`7` morning vs `>= 20` 8:30 PM prep).
 * @property dayMealCards Chronologically ordered [ResolvedMealCard]s for [activeDayIndex].
 * @property tomorrowBreakfastRecipe Upcoming morning recipe checked for 8:30 PM night-prep steps.
 * @property instantSwapsBySlotId Precomputed `EC-13` 100% in-stock swaps keyed by `slotId`.
 * @property schedulableRecipes Locally available and allergen-safe recipes (`EC-02`/`EC-03`).
 * @property activeCookModeSession Non-null when full-screen `StepByStepCookModeScreen` is open.
 * @property selectedRecipeId Currently focused [Recipe.recipeId] in `CleanRecipeReaderScreen`.
 * @property adjustingSlotId Non-null when `CookedOverrideSheet` is open for a specific `slotId`.
 * @property isCustomMealSheetOpen `true` when `CustomMealSheet` modal bottom sheet is visible.
 * @property customMealInitialSlotType Preselected [MealSlotType] when opening `CustomMealSheet`.
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
 * @property activeDeviceProfile Selected Galaxy S22 / S22 Ultra / Gemma E2B hardware profile.
 * @property attachedImagePath Preprocessed JPEG path staged in the AI Chef composer, or `null`.
 * @property attachedImageLabel Display filename badge for [attachedImagePath], or `null`.
 * @property isPreprocessingImage `true` while EXIF-rotating and downscaling a newly picked photo.
 * @property preprocessingImageLabel Display filename currently being optimized for Gemma Vision.
 * @property isAiGenerating `true` while an AI Chef text or multimodal vision turn is running.
 * @property isGeneratingVisionTurn `true` while [isAiGenerating] is executing an image+text scan.
 * @property isSavingBatch `true` while an `O(1)` atomic storage or domain batch write is active.
 * @property isAiContextBannerDismissed Whether the user dismissed `KITCHEN CONTEXT SYNCED`.
 * @property appliedSwapProposalIds Set of `proposalId`s whose board swaps have been committed.
 * @property queuedIngredientProposalIds Set of `proposalId`s whose discovery staples were queued.
 * @property confirmedVisualTurnIds Set of `turnId`s whose photo scan restocks were confirmed.
 * @property batchWriteCallCount Monotonic `KitchenStoragePort.batchWriteCallCount` (`O(1)` proof).
 * @property toastMessage Dismissible confirmation or validation banner shown in `KitchenScaffold`.
 * @property canUndoLastAgentAction `true` when an undoable Sous-Chef snapshot is available.
 * @property lastAgentActionSummary Concise human summary for the inline Sous-Chef Undo/Adjust bar.
 * @property lastAgentActionAdjustTarget Destination tab opened when the user taps `Adjust`/`View`.
 * @property cloudLlmConfig Non-secret hybrid On-Device / Cloud LLM configuration and key badge state.
 * @property isVerifyingCloudKey `true` while a lightweight Cloud LLM API key health probe is active.
 * @property isRefreshingCloudModels `true` while syncing live `/models` for the active provider.
 */
@Immutable
data class KitchenUiState(
  val isLoading: Boolean = true,
  val activeDestination: TopLevelDestination = TopLevelDestination.ASK_AI,
  val groceriesSubDestination: GroceriesSubDestination = GroceriesSubDestination.GROCERY_LIST,
  val activePacerMode: PacerMode = PacerMode.DAILY_PACER,
  val activePacerSlotId: String? = null,
  val snapshot: KitchenSnapshot = emptyPlaceholderSnapshot(),
  val pantryExpiryDashboard: PantryExpiryDashboard = emptyExpiryDashboard(),
  val weeklyGroceryDiff: WeeklyGroceryDiff = emptyGroceryDiff(),
  val weeklyPrepSessionPlan: WeeklyPrepSessionPlan = emptyPrepPlan(),
  val activeDayIndex: Int = 0,
  val todayDayIndex: Int = 0,
  val currentSimulatedHour24: Int = QA_MORNING_SIMULATED_HOUR_24,
  val dayMealCards: List<ResolvedMealCard> = emptyList(),
  val tomorrowBreakfastRecipe: Recipe? = null,
  val instantSwapsBySlotId: Map<String, List<Recipe>> = emptyMap(),
  val schedulableRecipes: List<Recipe> = emptyList(),
  val activeCookModeSession: CookModeSessionState? = null,
  val selectedRecipeId: String? = null,
  val adjustingSlotId: String? = null,
  val isCustomMealSheetOpen: Boolean = false,
  val customMealInitialSlotType: MealSlotType = MealSlotType.DINNER,
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
  val activeDeviceProfile: EdgeDeviceProfile = EdgeDeviceProfile.GALAXY_S22_ULTRA_GEMMA_2B_INT4,
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
  val canUndoLastAgentAction: Boolean = false,
  val lastAgentActionSummary: String? = null,
  val lastAgentActionAdjustTarget: TopLevelDestination? = null,
  val cloudLlmConfig: CloudLlmConfig = CloudLlmConfig(),
  val isVerifyingCloudKey: Boolean = false,
  val isRefreshingCloudModels: Boolean = false,
) {
  // Derived flags keep top-bar, banner, and composer spinners synchronized across all tabs.
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

  // Count unchecked catalog shortages + unchecked custom notes for the Groceries nav badge.
  /** Live count of unchecked shopping items surfaced on the `Groceries` navigation badge. */
  val groceryBadgeCount: Int
    get() {
      val uncheckedCatalog = weeklyGroceryDiff.groceryItems.count { item ->
        !item.isChecked && item.ingredientId !in snapshot.checkedGroceryItemIds
      }
      val uncheckedCustom = snapshot.customGroceryItems.count { !it.isChecked }
      return uncheckedCatalog + uncheckedCustom
    }

  /** Count of upcoming `PLANNED` dinners across the 7-day board for `KITCHEN CONTEXT SYNCED`. */
  val plannedDinnerCount: Int
    get() = snapshot.weeklyBoard.slots.count { slot ->
      slot.slotType == MealSlotType.DINNER && slot.status == MealSlotStatus.PLANNED
    }

  /** Concise tonight dinner teaser displayed on `SmartGroceryScreen`. */
  val tonightMealSummary: String
    get() {
      val dinnerCard = dayMealCards.firstOrNull { it.slot.slotType == MealSlotType.DINNER }
      return when (dinnerCard) {
        null -> "Tonight: Family One-Pot Dinner"
        else -> "Tonight: ${dinnerCard.recipe.title}"
      }
    }

  /** Resolved [ResolvedMealCard] currently open in `CookedOverrideSheet`, or `null`. */
  val adjustingMealCard: ResolvedMealCard?
    get() {
      val targetId = adjustingSlotId ?: return null
      dayMealCards.firstOrNull { it.slot.slotId == targetId }?.let { return it }
      val fallbackSlot = snapshot.weeklyBoard.slots.firstOrNull { it.slotId == targetId } ?: return null
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
        val slot = snapshot.weeklyBoard.slots.firstOrNull { it.slotId == explicitId }
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
     * @param activeDestination Initial top-level tab (`ASK_AI` default).
     * @param groceriesSubDestination Initial `Groceries` sub-destination.
     * @param isWideCounterLayout Wide kitchen-counter mode flag.
     * @param isDarkCounterTheme Ambient Counter Dark mode flag.
     * @param batchWriteCallCount Current `O(1)` atomic persistence count.
     * @return Fully derived immutable [KitchenUiState].
     */
    fun fromSnapshot(
      snapshot: KitchenSnapshot,
      activeDestination: TopLevelDestination = TopLevelDestination.ASK_AI,
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
 * Pure single-pass derivation function recomputing [PantryExpiryDashboard], [WeeklyGroceryDiff],
 * [WeeklyPrepSessionPlan], [ResolvedMealCard]s, and `EC-13` instant swap maps whenever the
 * underlying [KitchenSnapshot], selected weekday, or simulated clock changes.
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
  val recipesById = snapshot.recipesById

  // Filter schedulable recipes (EC-02 hard-hide + EC-03 in-stock substitute tie-breaking).
  val schedulable = AvailabilityFilter.filterSchedulableRecipes(
    recipes = snapshot.recipes,
    catalog = snapshot.ingredients,
    householdAllergens = allergens,
    pantry = snapshot.pantryEntries,
  )

  // Compute Smart Pantry expiry cards, storage partitions, PHP ₱ at-risk value, and Waste Score.
  val expiryDashboard = PantryExpiryEngine.evaluatePantryExpiryDashboard(
    pantry = snapshot.pantryEntries,
    catalog = snapshot.ingredients,
    recipes = snapshot.recipes,
    householdAllergens = allergens,
    rescuedMealsCount = snapshot.rescuedExpiringMealsCount,
  )

  // Compute chronological 21-slot Tier-1 reservations, shortages, and whole-package rounding (EC-09).
  val groceryDiff = GroceryDiffEngine.computeWeeklyGroceryDiff(
    board = snapshot.weeklyBoard,
    recipes = snapshot.recipes,
    catalog = snapshot.ingredients,
    pantry = snapshot.pantryEntries,
    householdAllergens = allergens,
    aiAddedGroceryIds = snapshot.aiAddedGroceryIds,
    checkedGroceryItemIds = snapshot.checkedGroceryItemIds,
    grocerySortMode = snapshot.grocerySortMode,
  )

  // Consolidate PLANNED slots into the 4-station Weekly Meal Prep Session plan.
  val prepPlan = MenuPlannerEngine.buildWeeklyMealPrepSessionPlan(
    board = snapshot.weeklyBoard,
    recipes = snapshot.recipes,
    catalog = snapshot.ingredients,
    completedTaskIds = snapshot.completedPrepTaskIds,
  )

  // Resolve chronological Breakfast -> Lunch -> Dinner cards for activeDayIndex (EC-07, EC-08).
  val daySlots = snapshot.weeklyBoard.slots
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
  val tomorrowBreakfastSlot = snapshot.weeklyBoard.slots.firstOrNull { slot ->
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
      snapshot.weeklyBoard.slots.firstOrNull { it.slotId == adjustId }?.let(::add)
    }
  }
  val swapsBySlot = swapTargetSlots.associate { slot ->
    slot.slotId to MenuPlannerEngine.findInstantInStockSwaps(
      slotType = slot.slotType,
      recipes = snapshot.recipes,
      catalog = snapshot.ingredients,
      pantry = snapshot.pantryEntries,
      householdAllergens = allergens,
      targetServings = slot.targetServings,
      excludeRecipeId = slot.recipeId,
    )
  }

  // Keep selectedRecipeId anchored to a valid dish when catalog filters change.
  val validSelectedRecipeId = current.selectedRecipeId
    ?.takeIf { id -> snapshot.recipes.any { it.recipeId == id } }
    ?: schedulable.firstOrNull()?.recipeId
    ?: snapshot.recipes.firstOrNull()?.recipeId

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

private fun emptyPlaceholderSnapshot(): KitchenSnapshot =
  KitchenSnapshot(
    ingredients = emptyList(),
    recipes = emptyList(),
    pantryEntries = emptyList(),
    weeklyBoard = WeeklyBoard(
      boardId = "board_loading",
      householdId = "household_family_4",
      weekStartDateIso = "2026-10-26",
      slots = emptyList(),
    ),
  )

private fun emptyExpiryDashboard(): PantryExpiryDashboard =
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

private fun emptyGroceryDiff(): WeeklyGroceryDiff =
  WeeklyGroceryDiff(
    slotSummaries = emptyList(),
    slotSummariesBySlotId = emptyMap(),
    groceryItems = emptyList(),
    groceryItemsByCategory = emptyMap(),
    restockDeltas = emptyList(),
  )

private fun emptyPrepPlan(): WeeklyPrepSessionPlan =
  WeeklyPrepSessionPlan(
    sessionId = "prep_loading",
    boardId = "board_loading",
    tasks = emptyList(),
    tasksByStation = PrepStationType.entries.associateWith { emptyList() },
    totalPrepMinutes = 0,
    totalTimeSavedMinutes = 0,
    coveredSlotIds = emptyList(),
  )
