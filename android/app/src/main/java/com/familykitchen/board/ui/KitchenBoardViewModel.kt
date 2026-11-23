package com.familykitchen.board.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.familykitchen.board.ai.AiChefCoordinator
import com.familykitchen.board.data.InMemoryCloudCredentialVault
import com.familykitchen.board.data.KitchenImagePreprocessor
import com.familykitchen.board.domain.AiChefAction
import com.familykitchen.board.domain.AiExecutionMode
import com.familykitchen.board.domain.ConsumerHouseholdTemplate
import com.familykitchen.board.domain.CookModeSessionState
import com.familykitchen.board.domain.CookedDeductionPayload
import com.familykitchen.board.domain.CustomGroceryItem
import com.familykitchen.board.domain.EdgeDeviceProfile
import com.familykitchen.board.domain.ExpiringPantryCardModel
import com.familykitchen.board.domain.FirstRunOnboardingDraft
import com.familykitchen.board.domain.GroceryAndCatalogEditAction
import com.familykitchen.board.domain.GrocerySortMode
import com.familykitchen.board.domain.HouseholdCurrency
import com.familykitchen.board.domain.HouseholdProfile
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.KitchenBoardException
import com.familykitchen.board.domain.MarketVenue
import com.familykitchen.board.domain.MealSlotType
import com.familykitchen.board.domain.MenuPlannerEngine
import com.familykitchen.board.domain.MenuTemplate
import com.familykitchen.board.domain.PantryLedgerEngine
import com.familykitchen.board.domain.PlanCadence
import com.familykitchen.board.domain.PostCookReactionTag
import com.familykitchen.board.domain.ProportionalStockLevel
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.RecipeCreationMode
import com.familykitchen.board.domain.RestockDelta
import com.familykitchen.board.domain.SeedCatalogDataset
import com.familykitchen.board.domain.SlotSkipReason
import com.familykitchen.board.domain.SmartRecipeDraft
import com.familykitchen.board.domain.StapleStatus
import com.familykitchen.board.domain.StorageLocation
import com.familykitchen.board.domain.UnpackItemDirective
import com.familykitchen.board.domain.WeeklyPrepBatchCommitPayload
import com.familykitchen.board.domain.WorkoutTiming
import com.familykitchen.board.ports.AiEdgePort
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ports.CloudCredentialVaultPort
import com.familykitchen.board.ports.KitchenClockPort
import com.familykitchen.board.ports.KitchenSnapshot
import com.familykitchen.board.ports.KitchenStoragePort
import com.familykitchen.board.ports.SeedCatalogPort
import com.familykitchen.board.ports.SystemKitchenClockAdapter
import com.familykitchen.board.ports.VoiceInputPort
import com.familykitchen.board.ports.VoiceInputState
import com.familykitchen.board.ui.pacer.PacerMode
import com.familykitchen.board.ui.sheets.CustomFamilyMealDraft
import com.familykitchen.board.ui.sheets.QaEdgeCaseScenario
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Unidirectional presentation orchestrator wiring SavorAI v2.0 UI intents across pure domain engines,
// KitchenClockPort, Single-Gemma EGM + ADK AI Sous-Chef, O(1) batch persistence, and voice ports.

/**
 * Primary Jetpack Lifecycle [ViewModel] orchestrating all 4 `SavorAI` tabs (`Plan`, `Pantry`,
 * `Recipes`, `Shop`), the contextual `SousChefSheet`, full-screen / minimized `Step-by-Step Cook Mode`,
 * and all modal bottom sheets (`StartNewWeekSheet`, `SavedMenusSheet`, `AddOrEditRecipeSheet`,
 * `UnpackGroceriesSheet`, `PostCookCompletionSheet`, `CookedOverrideSheet`, `CustomMealSheet`,
 * `FamilyProfileSheet`, `QaScenarioLabSheet`).
 *
 * Exposes a single immutable [uiState] `StateFlow`, executes every multi-item mutation through
 * `O(1)` atomic batch persistence on [storagePort], delegates multimodal AI / Cook Mode flows to
 * [KitchenAiAndCookDelegate] and planner / recipe / unpack flows to [KitchenPlannerAndRecipeDelegate],
 * and deterministically closes native JNI and speech handles inside [onCleared].
 */
class KitchenBoardViewModel(
  private val seedCatalogPort: SeedCatalogPort,
  private val storagePort: KitchenStoragePort,
  private val aiEdgePort: AiEdgePort,
  private val voiceInputPort: VoiceInputPort,
  private val cloudVaultPort: CloudCredentialVaultPort = InMemoryCloudCredentialVault(),
  preprocessImageUri: suspend (String) -> String = { it },
  private val deleteTempImage: (String?) -> Unit = { path -> KitchenImagePreprocessor.deletePathQuietly(path) },
  autoInitializeAiOnStart: Boolean = false,
  private val clockPort: KitchenClockPort = SystemKitchenClockAdapter(),
) : ViewModel() {

  // Sample wall clock immediately so initial realTodayIso, todayDayIndex, and hour are synchronized.
  private val mutableUiState: MutableStateFlow<KitchenUiState> = run {
    val bootClock = clockPort.now()
    val safeBootDay = bootClock.dayOfWeekIndex.coerceIn(0, 6)
    MutableStateFlow(
      KitchenUiState(
        realTodayIso = bootClock.todayIso, todayDayIndex = safeBootDay, activeDayIndex = safeBootDay,
        currentSimulatedHour24 = bootClock.currentHour24.coerceIn(0, 23),
        cloudLlmConfig = cloudVaultPort.configFlow.value,
      ),
    )
  }

  /** Hot immutable UI state stream observed via `collectAsStateWithLifecycle()` in `KitchenScaffold`. */
  val uiState: StateFlow<KitchenUiState> = mutableUiState.asStateFlow()

  // Cached offline seed dataset enabling instant 1-tap Reset Household State in QA Lab.
  private var cachedSeedCatalog: SeedCatalogDataset? = null

  // Pre-commit snapshot retained for 1-tap transparent-agency Undo after Sous-Chef mutations.
  private var lastUndoableSnapshot: KitchenSnapshot? = null

  // Tracks active O(1) storage/domain jobs so top-bar spinners reflect in-flight persistence.
  private val activeStorageJobs = AtomicInteger(0)

  // Shared pure-Kotlin AI Chef coordinator wired with SingleGemmaEgmGraphRunner + Cookbook OCR.
  private val sharedAiChefCoordinator = AiChefCoordinator(aiEdgePort, { path -> deleteTempImage(path) })

  // Cohesive planner, rollover, saved-menus, 3-mode Recipe Studio, Pantry audit, & Unpack delegate.
  private val plannerAndRecipeDelegate = KitchenPlannerAndRecipeDelegate(
    scope = viewModelScope, mutableUiState = mutableUiState, storagePort = storagePort,
    aiChefCoordinator = sharedAiChefCoordinator, preprocessImageUri = preprocessImageUri,
    deleteTempImage = deleteTempImage, nowIso = ::vmCoreNowIso, runGuarded = ::vmCoreLaunchDomainGuarded,
  )

  // Cohesive AI Sous-Chef & Step-by-Step Cook Mode delegate keeping this ViewModel well under 700 LOC.
  private val aiAndCookDelegate = KitchenAiAndCookDelegate(
    scope = viewModelScope, mutableUiState = mutableUiState, storagePort = storagePort,
    aiEdgePort = aiEdgePort, voiceInputPort = voiceInputPort, aiChefCoordinator = sharedAiChefCoordinator,
    preprocessImageUri = preprocessImageUri, deleteTempImage = deleteTempImage, nowIso = ::vmCoreNowIso,
    runGuarded = ::vmCoreLaunchDomainGuarded,
    onOpenAddRecipeStudio = { mode -> plannerAndRecipeDelegate.openAddRecipeStudio(mode, false) },
    onSchedulePresetRecipe = ::schedulePresetRecipeFromSheet,
    onToggleSavedRecipe = ::toggleSavedRecipe,
    onQueueMissingIngredients = ::queueMissingRecipeIngredients,
    onOpenAdjustSlotSheet = ::openAdjustSlotSheet,
    onAddCustomGroceryItem = ::addCustomGroceryItem,
    recordUndoSnapshot = ::vmCoreRecordAgentUndoSnapshot,
    onUndoLastAgentAction = ::undoLastAgentAction,
    onDismissAgentActionBanner = ::dismissAgentActionBanner,
    cloudVaultPort = cloudVaultPort,
  )

  init {
    // Wire hot StateFlow collectors before hydrating persisted disk state and real wall clock.
    vmCoreObserveRuntimeAndVoiceStreams()
    vmCoreHydrateInitialSnapshot(autoInitializeAiOnStart)
  }

  // Subscribe to storage, LiteRT-LM runtime, and SpeechRecognizer StateFlows on viewModelScope.
  private fun vmCoreObserveRuntimeAndVoiceStreams() {
    viewModelScope.launch {
      storagePort.snapshotFlow.collect { latest ->
        if (latest != null) mutableUiState.update { deriveKitchenUiState(it, latest, storagePort.batchWriteCallCount) }
      }
    }
    viewModelScope.launch {
      cloudVaultPort.configFlow.collect { cfg -> mutableUiState.update { it.copy(cloudLlmConfig = cfg) } }
    }
    viewModelScope.launch {
      aiEdgePort.runtimeState.collect { nextRuntime ->
        mutableUiState.update { current ->
          val profile = when {
            nextRuntime is AiRuntimeState.Ready && nextRuntime.capabilities.supportsVision &&
              current.cloudLlmConfig.executionMode == AiExecutionMode.ON_DEVICE ->
              EdgeDeviceProfile.GEMMA_4_E2B_MULTIMODAL
            else -> current.activeDeviceProfile
          }
          current.copy(aiRuntimeState = nextRuntime, activeDeviceProfile = profile)
        }
      }
    }
    viewModelScope.launch {
      voiceInputPort.voiceState.collect { nextVoice ->
        mutableUiState.update { it.copy(voiceInputState = nextVoice) }
        if (nextVoice is VoiceInputState.Result && nextVoice.transcript.isNotBlank()) {
          aiAndCookDelegate.routeVoiceTranscript(nextVoice.transcript)
        }
      }
    }
  }

  // Hydrate initial persisted household snapshot and synchronize wall-clock date/day/hour.
  private fun vmCoreHydrateInitialSnapshot(autoInitializeAiOnStart: Boolean) = vmCoreLaunchDomainGuarded {
    val seed = seedCatalogPort.loadSeedCatalog()
    cachedSeedCatalog = seed
    val loaded = storagePort.loadOrInitialize(seed)
    val nowClock = clockPort.now()
    val safeDay = nowClock.dayOfWeekIndex.coerceIn(0, 6)
    mutableUiState.update { current ->
      // Preserve QA simulated clock override if a test set one before hydration completed.
      val resolvedHour = if (current.isSimulatedClockOverrideActive) {
        current.currentSimulatedHour24
      } else {
        nowClock.currentHour24.coerceIn(0, 23)
      }
      val synced = current.copy(
        realTodayIso = nowClock.todayIso, todayDayIndex = safeDay,
        activeDayIndex = safeDay, currentSimulatedHour24 = resolvedHour,
      )
      deriveKitchenUiState(synced, loaded, storagePort.batchWriteCallCount)
    }
    if (autoInitializeAiOnStart) initializeOnDeviceAiRuntime()
  }

  /** Refreshes `realTodayIso`, `todayDayIndex`, and (unless QA-overridden) `currentSimulatedHour24`. */
  fun refreshWallClock() {
    // Re-sample KitchenClockPort on Activity resume so midnight rollovers and 8:30 PM prep update live.
    val nowClock = clockPort.now()
    val safeToday = nowClock.dayOfWeekIndex.coerceIn(0, 6)
    mutableUiState.update { current ->
      val hour = if (current.isSimulatedClockOverrideActive) current.currentSimulatedHour24 else nowClock.currentHour24.coerceIn(0, 23)
      val updated = current.copy(realTodayIso = nowClock.todayIso, todayDayIndex = safeToday, currentSimulatedHour24 = hour)
      deriveKitchenUiState(updated, updated.snapshot, storagePort.batchWriteCallCount)
    }
  }

  /** Triggers background app-scoped model discovery (`filesDir/models` & `externalFilesDir/models`). */
  fun initializeOnDeviceAiRuntime() {
    viewModelScope.launch {
      try {
        aiEdgePort.autoDiscoverAndInitialize()
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (_: Throwable) {
        // Recoverable runtime errors already publish AiRuntimeState.Error via runtimeState.
      }
    }
  }

  // --- Navigation, Sous-Chef Modal, Layout, Theme, Search & Toast Actions ---

  /** Switches the active primary tab (`PLAN`, `PANTRY`, `RECIPES`, `GROCERIES`). */
  fun selectDestination(destination: TopLevelDestination) =
    mutableUiState.update { it.copy(activeDestination = destination) }

  /** Opens the contextual Sous-Chef modal sheet with an optional [contextHint]. */
  fun openSousChefSheet(contextHint: String? = null) = plannerAndRecipeDelegate.openSousChefSheet(contextHint)

  /** Closes the contextual Sous-Chef modal sheet. */
  fun dismissSousChefSheet() = plannerAndRecipeDelegate.dismissSousChefSheet()

  /** Routes `GroceriesSubDestination` selections to `GROCERIES` or `PLAN`. */
  fun selectGroceriesSubDestination(subDestination: GroceriesSubDestination) =
    mutableUiState.update { current ->
      when (subDestination) {
        GroceriesSubDestination.GROCERY_LIST -> current.copy(
          activeDestination = TopLevelDestination.GROCERIES, groceriesSubDestination = subDestination,
        )
        GroceriesSubDestination.WEEKLY_MEAL_PLAN -> current.copy(
          activeDestination = TopLevelDestination.PLAN, activePacerMode = PacerMode.DAILY_PACER,
          groceriesSubDestination = subDestination,
        )
        GroceriesSubDestination.MORNING_PACER -> current.copy(
          activeDestination = TopLevelDestination.PLAN, activePacerMode = PacerMode.WEEKLY_PREP,
          groceriesSubDestination = subDestination,
        )
      }
    }

  /** Jumps straight to the 4-station Weekly Meal Prep Session view on the `PLAN` tab. */
  fun openWeeklyPrepSession() = mutableUiState.update {
    it.copy(
      activeDestination = TopLevelDestination.PLAN, activePacerMode = PacerMode.WEEKLY_PREP,
      groceriesSubDestination = GroceriesSubDestination.MORNING_PACER,
    )
  }

  /** Updates the active [PacerMode] (`DAILY_PACER` vs `WEEKLY_PREP`) inside `MorningPacerScreen`. */
  fun setPacerMode(mode: PacerMode) = mutableUiState.update { it.copy(activePacerMode = mode) }

  /** Selects the active weekday (`0 = Monday`..`6 = Sunday`) on the Weekly Meal Plan. */
  fun selectActiveDay(dayIndex: Int) = mutableUiState.update { current ->
    // Note: tonightMealSummary stays anchored to todayDayIndex even when browsing other days!
    val updated = current.copy(activeDayIndex = dayIndex.coerceIn(0, 6), activePacerSlotId = null)
    deriveKitchenUiState(updated, updated.snapshot, storagePort.batchWriteCallCount)
  }

  /** Switches `Plan` between `This Week` (`false`) and `Next Week` (`true`). */
  fun setViewingNextWeek(nextWeek: Boolean) = plannerAndRecipeDelegate.setViewingNextWeek(nextWeek)

  /** Expands or collapses the global top-bar search input, clearing query text when closed. */
  fun toggleSearchBar() = mutableUiState.update { current ->
    val next = !current.isSearchBarExpanded
    current.copy(isSearchBarExpanded = next, searchQuery = if (next) current.searchQuery else "")
  }

  /** Updates the active global search query filtering Cookbook and Pantry views. */
  fun updateSearchQuery(query: String) = mutableUiState.update { it.copy(searchQuery = query) }

  /** Toggles between compact mobile layout and multi-column wide kitchen-counter layout. */
  fun toggleWideCounterLayout() = mutableUiState.update { current ->
    val nextWide = !current.isWideCounterLayout
    current.copy(isWideCounterLayout = nextWide)
      .withToast(if (nextWide) "Wide Counter Mode enabled." else "Compact Mobile Mode enabled.")
  }

  /** Toggles between Warm Daylight (`#FAF7F2`) and Ambient Counter Dark OLED themes. */
  fun toggleDarkCounterTheme() = mutableUiState.update { it.copy(isDarkCounterTheme = !it.isDarkCounterTheme) }

  /** Dismisses the sticky top confirmation or warning toast banner. */
  fun dismissToast() = mutableUiState.update { it.withToast(null) }

  /** Posts a dismissible export status or diagnostic banner (`Copy` / `Save PDF`) to [uiState]. */
  fun postExportFeedback(message: String) = vmCorePostToast(message)

  // --- Weekly Rollover, Saved Menus, Slot Pin/Move/Clear/Skip & Cooked Deductions ---

  /** Opens `StartNewWeekSheet`. */
  fun openStartNewWeekSheet() = plannerAndRecipeDelegate.openStartNewWeekSheet()

  /** Closes `StartNewWeekSheet`. */
  fun dismissStartNewWeekSheet() = plannerAndRecipeDelegate.dismissStartNewWeekSheet()

  /** Starts a new week or refreshes the active board with [cadence] and [carryOverUncooked] (`1` write). */
  fun confirmStartNewWeek(cadence: PlanCadence, carryOverUncooked: Boolean = true) =
    plannerAndRecipeDelegate.confirmStartNewWeek(cadence, carryOverUncooked)

  /** Opens `SavedMenusSheet` ranked by household preferred cuisines. */
  fun openSavedMenusSheet() = plannerAndRecipeDelegate.openSavedMenusSheet()

  /** Closes `SavedMenusSheet`. */
  fun dismissSavedMenusSheet() = plannerAndRecipeDelegate.dismissSavedMenusSheet()

  /** Applies [template] onto the active board with optional pantry/market remixing (`1` `O(1)` write). */
  fun applySavedMenu(template: MenuTemplate, remixWithPantry: Boolean = true) =
    plannerAndRecipeDelegate.applySavedMenu(template, remixWithPantry)

  /** Captures the active weekly board into `savedMenus` as a reusable household menu (`1` write). */
  fun saveCurrentWeekAsMenu(title: String, subtitle: String = "") =
    plannerAndRecipeDelegate.saveCurrentWeekAsMenu(title, subtitle)

  /** Clears [slotId] from the active weekly board, leaving that slot open (`1` `O(1)` write). */
  fun clearSlot(slotId: String) = plannerAndRecipeDelegate.clearSlot(slotId)

  /** Toggles `isPinned` on [slotId] so weekly rollovers preserve anchor family traditions (`1` write). */
  fun toggleSlotPinned(slotId: String) = plannerAndRecipeDelegate.toggleSlotPinned(slotId)

  /** Moves or swaps [sourceSlotId] onto `(targetDayIndex, targetSlotType)` enforcing `EC-01` (`1` write). */
  fun moveOrSwapSlot(sourceSlotId: String, targetDayIndex: Int, targetSlotType: MealSlotType) =
    plannerAndRecipeDelegate.moveOrSwapSlot(sourceSlotId, targetDayIndex, targetSlotType)

  /** Marks [slotId] `COMPLETED_SKIPPED` with explicit [reason] and distills into `ElephantMemory`. */
  fun markSlotSkipWithReason(slotId: String, reason: SlotSkipReason) =
    plannerAndRecipeDelegate.markSlotSkipWithReason(slotId, reason)

  /** Launches full-screen `StepByStepCookModeScreen` for [slotId]. */
  fun startCookingSlot(slotId: String) {
    val state = mutableUiState.value
    val slot = state.activeDisplayedBoard.slots.firstOrNull { it.slotId == slotId }
      ?: state.snapshot.weeklyBoard.slots.firstOrNull { it.slotId == slotId } ?: return
    val recipe = state.snapshot.recipesById[slot.recipeId] ?: return
    mutableUiState.update { it.copy(activePacerSlotId = slotId) }
    aiAndCookDelegate.startCookMode(recipe = recipe, slotId = slot.slotId, handsFreeVoiceActive = false)
  }

  /** Executes 1-tap `Cooked It` fast-path pantry deduction (`O(1)` atomic batch commit). */
  fun quickCookSlot(slotId: String) = plannerAndRecipeDelegate.quickCookSlot(slotId)

  /** Opens `CookedOverrideSheet` for [slotId] (`EC-04`–`EC-07`, `EC-12`, `EC-13`). */
  fun openAdjustSlotSheet(slotId: String) = mutableUiState.update { current ->
    // Recompute instantSwapsBySlotId immediately so EC-13 swaps include adjustingSlotId.
    deriveKitchenUiState(current.copy(adjustingSlotId = slotId), current.snapshot, storagePort.batchWriteCallCount)
  }

  /** Closes `CookedOverrideSheet`. */
  fun dismissAdjustSlotSheet() = mutableUiState.update { it.copy(adjustingSlotId = null) }

  /** Commits an adjusted `CookedDeductionPayload` (`EC-04`–`EC-06`) + `ElephantMemory` in 1 `O(1)` write. */
  fun confirmCookedOverride(payload: CookedDeductionPayload) =
    plannerAndRecipeDelegate.confirmCookedOverride(payload)

  /** Updates Dad's flat `1x` workout macro boost (`EC-07`) on [slotId] in 1 `O(1)` write. */
  fun selectSlotWorkoutModifier(slotId: String, modifier: WorkoutTiming) = vmCoreLaunchDomainGuarded {
    // Apply flat 1x protein/carb boost unscaled by household servings (EC-07).
    val current = mutableUiState.value.snapshot
    val updatedBoard = MenuPlannerEngine.toggleSlotWorkoutModifier(current.weeklyBoard, slotId, modifier)
    storagePort.saveSnapshotBatch(current.copy(weeklyBoard = updatedBoard, updatedAtIso = vmCoreNowIso()))
    vmCorePostToast("Updated workout boost in 1 batch write.")
  }

  /** Swaps [slotId] to 100% in-stock [newRecipeId] (`EC-01` & `EC-13` verified) in 1 `O(1)` write. */
  fun quickSwapSlotRecipe(slotId: String, newRecipeId: String) =
    plannerAndRecipeDelegate.quickSwapSlotRecipe(slotId, newRecipeId)

  /** Marks [slotId] `COMPLETED_SKIPPED` (`EC-12`), deducting `0` stock and distilling into `ElephantMemory`. */
  fun markSlotSkippedOrLeftovers(slotId: String) =
    plannerAndRecipeDelegate.markSlotSkippedOrLeftovers(slotId)

  /** Opens `CustomMealSheet` preselected to [initialSlotType] (or inferred from [forSlotId]). */
  fun openCustomMealSheet(initialSlotType: MealSlotType = MealSlotType.DINNER, forSlotId: String? = null) =
    plannerAndRecipeDelegate.openCustomMealSheet(initialSlotType, forSlotId)

  /** Closes `CustomMealSheet`. */
  fun dismissCustomMealSheet() = mutableUiState.update { it.copy(isCustomMealSheetOpen = false) }

  /** Schedules a preset [recipe] onto a single day or `Mon–Fri` in 1 `O(1)` batch write. */
  fun schedulePresetRecipeFromSheet(
    recipe: Recipe,
    slotType: MealSlotType,
    dayIndex: Int,
    applyMondayThroughFriday: Boolean,
  ) = plannerAndRecipeDelegate.schedulePresetRecipeFromSheet(recipe, slotType, dayIndex, applyMondayThroughFriday)

  /** Creates and schedules a custom household meal (`Single Day` or `Mon–Fri`) in 1 `O(1)` write. */
  fun saveCustomFamilyMeal(draft: CustomFamilyMealDraft) =
    plannerAndRecipeDelegate.saveCustomFamilyMeal(draft)

  /** Toggles completion of an individual Weekly Prep station task in 1 `O(1)` write. */
  fun togglePrepStationTask(taskId: String, isCompleted: Boolean) = vmCoreLaunchDomainGuarded {
    val current = mutableUiState.value.snapshot
    val nextIds = if (isCompleted) current.completedPrepTaskIds + taskId else current.completedPrepTaskIds - taskId
    storagePort.saveSnapshotBatch(current.copy(completedPrepTaskIds = nextIds, updatedAtIso = vmCoreNowIso()))
  }

  /** Commits a 4-station Weekly Meal Prep Session batch in 1 `O(1)` snapshot write. */
  fun commitWeeklyPrepSession(payload: WeeklyPrepBatchCommitPayload) =
    plannerAndRecipeDelegate.commitWeeklyPrepSession(payload)

  // --- Smart Pantry, Zone Moves, Shelf Audit & Expiry Actions (`EC-02`, `EC-03`, `EC-10`, `EC-11`) ---
  // Every Pantry stepper, zone move, and shelf-life update persists via a single O(1) batch write.

  /** Applies a Fridge Pulse stepper delta (`±1 count` or `±100 g/ml`, clamped at `0.0` per `EC-10`). */
  fun adjustPantryQuantity(ingredientId: String, deltaQuantity: Double) = vmCoreLaunchDomainGuarded {
    // Clamp negative over-deductions at 0.0 and refresh live expiryDateIso on positive restocks.
    val state = mutableUiState.value
    val current = state.snapshot
    val stamp = vmCoreNowIso()
    val updated = PantryLedgerEngine.adjustPantryQuantity(
      current.pantryEntries, current.ingredients, ingredientId, deltaQuantity, stamp, state.realTodayIso,
    )
    storagePort.saveSnapshotBatch(current.copy(pantryEntries = updated, updatedAtIso = stamp))
  }

  /** Updates Tier-2 `PLENTY | LOW | OUT` status (`EC-11`) in 1 `O(1)` snapshot write. */
  fun setStapleStatus(ingredientId: String, newStatus: StapleStatus) = vmCoreLaunchDomainGuarded {
    // Tier-2 staples never gram-deplete on cook; LOW or OUT queues 1 package on Smart Grocery.
    val current = mutableUiState.value.snapshot
    val stamp = vmCoreNowIso()
    val updated = PantryLedgerEngine.setStapleStatus(
      current.pantryEntries, current.ingredients, ingredientId, newStatus, stamp,
    )
    storagePort.saveSnapshotBatch(current.copy(pantryEntries = updated, updatedAtIso = stamp))
    vmCorePostToast("Updated staple status to ${newStatus.name} in 1 batch write.")
  }

  /** Flips `isLocallyAvailable` (`EC-02` hard-hide / `EC-03` auto-substitute) in 1 `O(1)` write. */
  fun toggleLocalAvailability(ingredientId: String, isLocallyAvailable: Boolean) = vmCoreLaunchDomainGuarded {
    // Trigger EC-02 hard-hide or EC-03 substituteGroupId swap across schedulableRecipes immediately.
    val current = mutableUiState.value.snapshot
    val updatedCatalog = PantryLedgerEngine.toggleLocalAvailability(current.ingredients, ingredientId, isLocallyAvailable)
    storagePort.saveSnapshotBatch(current.copy(ingredients = updatedCatalog, updatedAtIso = vmCoreNowIso()))
    val label = current.catalogById[ingredientId]?.displayName ?: ingredientId
    vmCorePostToast(if (isLocallyAvailable) "Marked $label available locally." else "Marked $label unavailable locally.")
  }

  /** Moves [ingredientId] into [targetZone] (`+90d`/`+180d` on Freezer rescue) in 1 `O(1)` write. */
  fun movePantryItemZone(ingredientId: String, targetZone: StorageLocation) =
    plannerAndRecipeDelegate.movePantryItemZone(ingredientId, targetZone)

  /** Marks [ingredientId] used up (`0.0` / `OUT`) in 1 `O(1)` write. */
  fun markPantryItemUsed(ingredientId: String) = plannerAndRecipeDelegate.markPantryItemUsed(ingredientId)

  /** Sets [ingredientId] to [level] (`EMPTY`, `LOW`, `HALF`, `FULL`) during Quick Shelf Check. */
  fun setPantryItemProportionalLevel(ingredientId: String, level: ProportionalStockLevel) =
    plannerAndRecipeDelegate.setPantryItemProportionalLevel(ingredientId, level)

  /** Toggles in-place `Empty · Low · Half · Full` Shelf Audit mode on `SmartPantryScreen`. */
  fun toggleShelfAuditMode() = plannerAndRecipeDelegate.toggleShelfAuditMode()

  /** Sets in-place Shelf Audit mode explicitly to [active]. */
  fun setShelfAuditMode(active: Boolean) = plannerAndRecipeDelegate.setShelfAuditMode(active)

  /** Updates default storage zone and shelf-life days on [ingredientId] across catalog & pantry. */
  fun updatePantryItemStorageAndShelfLife(
    ingredientId: String,
    storageLocation: StorageLocation,
    shelfLifeDays: Int,
  ) = plannerAndRecipeDelegate.updatePantryItemStorageAndShelfLife(ingredientId, storageLocation, shelfLifeDays)

  /** Toggles `+ Need to Buy` manual queueing on the Smart Grocery List in 1 `O(1)` write. */
  fun toggleManualGroceryQueue(ingredientId: String) = vmCoreLaunchDomainGuarded {
    val current = mutableUiState.value.snapshot
    val isQueued = ingredientId in current.aiAddedGroceryIds
    val nextQueued = if (isQueued) current.aiAddedGroceryIds - ingredientId else current.aiAddedGroceryIds + ingredientId
    storagePort.saveSnapshotBatch(current.copy(aiAddedGroceryIds = nextQueued, updatedAtIso = vmCoreNowIso()))
    val label = current.catalogById[ingredientId]?.displayName ?: ingredientId
    vmCorePostToast(if (isQueued) "Removed $label from Grocery queue." else "Queued $label onto Smart Grocery List.")
  }

  /** Removes a manually queued catalog ID from `aiAddedGroceryIds` in 1 `O(1)` write. */
  fun removeManualGroceryQueue(ingredientId: String) = vmCoreLaunchDomainGuarded {
    val current = mutableUiState.value.snapshot
    if (ingredientId in current.aiAddedGroceryIds) {
      storagePort.saveSnapshotBatch(current.copy(aiAddedGroceryIds = current.aiAddedGroceryIds - ingredientId, updatedAtIso = vmCoreNowIso()))
    }
  }

  /** Adds a local-market preset or custom [IngredientCatalogItem] and queues it on Smart Grocery. */
  fun addIngredientToGrocery(item: IngredientCatalogItem) =
    plannerAndRecipeDelegate.upsertIngredientItem(item, false, true, toast = "Added ${item.displayName} to Smart Grocery List.")

  /** Adds [item] to the household catalog and stocks 1 full package in Pantry (`O(1)` write). */
  fun addIngredientToPantry(item: IngredientCatalogItem) =
    plannerAndRecipeDelegate.upsertIngredientItem(item, true, false, toast = "Stocked 1 package of ${item.displayName} in Pantry.")

  /** Adds [item] to Pantry with explicit [location] (`FRIDGE`/`PANTRY`/`FREEZER`) and [expiresInDays]. */
  fun addIngredientToPantryWithExpiry(item: IngredientCatalogItem, location: StorageLocation, expiresInDays: Int) =
    plannerAndRecipeDelegate.upsertIngredientItem(
      item, true, false, location, expiresInDays, "Stocked ${item.displayName} in ${location.displayLabel}.",
    )

  /** Opens `SousChefSheet` ready for camera or gallery receipt/fridge scan (zero fake prompts). */
  fun onScanReceiptFromPantry() = mutableUiState.update {
    // Open SousChefSheet in-place without switching away from the active Pantry tab.
    it.copy(isSousChefSheetOpen = true, sousChefContextPromptHint = "Scan receipt or fridge photo")
      .withToast("Tap Camera or Gallery in Sous-Chef to scan your receipt or fridge.")
  }

  /** Toggles in-place `Quick Shelf Check` (`isShelfAuditModeActive`) directly on `SmartPantryScreen`. */
  fun onRunAiAuditFromPantry() = plannerAndRecipeDelegate.toggleShelfAuditMode()

  /** Opens `CleanRecipeReaderScreen` (or filtered Cookbook Grid) for rescue recipes consuming [expiringCard]. */
  fun onFindRescueRecipesFromPantry(expiringCard: ExpiringPantryCardModel) =
    plannerAndRecipeDelegate.findRescueRecipesFromPantry(expiringCard)

  /** Opens `SousChefSheet` and submits a zero-waste pairing prompt for [primary] and [partner]. */
  fun onPairExpiringIngredientsFromPantry(primary: IngredientCatalogItem, partner: IngredientCatalogItem) {
    mutableUiState.update { it.copy(isSousChefSheetOpen = true) }
    aiAndCookDelegate.submitTurn("Create a family zero-waste meal using expiring ${primary.displayName} and ${partner.displayName}", null)
  }

  /** Opens tonight's top rescue dish from Smart Pantry's sticky bottom Waste-Prevention CTA. */
  fun onViewTonightRescuePlanFromPantry(rescueRecipes: List<Recipe>) {
    val topRescue = rescueRecipes.firstOrNull()
    when (topRescue) {
      null -> {
        mutableUiState.update { it.copy(isSousChefSheetOpen = true) }
        aiAndCookDelegate.submitTurn("Use expiring items in 2 days and optimize our family meals for zero food waste", null)
      }
      else -> mutableUiState.update {
        it.copy(activeDestination = TopLevelDestination.RECIPES, selectedRecipeId = topRescue.recipeId, searchQuery = "")
          .withToast("Rescue tonight · ${topRescue.title}.")
      }
    }
  }

  // --- Smart Grocery List, Venue Filter, Unpack Batch, 3-Mode Recipe Studio & Cook Mode ---
  // Keeps Shop, Cookbook Grid, Clean Recipe Reader, and Cook Mode synchronized over one StateFlow.

  /** Toggles checked status on a catalog `ingredientId` or `CustomGroceryItem.customId` (`O(1)` write). */
  fun toggleGroceryItemChecked(itemId: String) = vmCoreLaunchDomainGuarded {
    // Checking an item marks it in-cart without deleting its row until Unpack Groceries runs.
    storagePort.toggleGroceryItemChecked(mutableUiState.value.snapshot, itemId, vmCoreNowIso())
  }

  /** Commits checked Smart Grocery [RestockDelta]s into Pantry in 1 `O(1)` atomic batch write. */
  fun commitGroceryRestockBatch(deltas: List<RestockDelta>) {
    // No-op when zero checked deltas were supplied so batchWriteCallCount stays exact.
    if (deltas.isEmpty()) return
    vmCoreLaunchDomainGuarded {
      storagePort.commitGroceryRestockBatch(mutableUiState.value.snapshot, deltas, vmCoreNowIso())
      vmCorePostToast("Restocked ${deltas.size} checked items into Pantry in 1 atomic batch.")
    }
  }

  /** Updates the active `All · Palengke · Supermarket` filter on `SmartGroceryScreen`. */
  fun setGroceryVenueFilter(venue: MarketVenue?) = plannerAndRecipeDelegate.setGroceryVenueFilter(venue)

  /** Re-queues a skipped `Pantry Adjusted` staple (`Ran Low` in `Shop`) in 1 `O(1)` write. */
  fun requeueSkippedPantryStaple(ingredientId: String) =
    plannerAndRecipeDelegate.requeueSkippedPantryStaple(ingredientId)

  /** Opens `UnpackGroceriesSheet` for zone-aware Smart Grocery unpacking. */
  fun openUnpackGroceriesSheet() = plannerAndRecipeDelegate.openUnpackGroceriesSheet()

  /** Closes `UnpackGroceriesSheet`. */
  fun dismissUnpackGroceriesSheet() = plannerAndRecipeDelegate.dismissUnpackGroceriesSheet()

  /** Commits checked [directives] + promoted/cleared custom notes in 1 `O(1)` unpack batch write. */
  fun confirmUnpackGroceries(
    directives: List<UnpackItemDirective>,
    promotedCustomIds: Set<String> = emptySet(),
    clearCustomIds: Set<String> = emptySet(),
  ) = plannerAndRecipeDelegate.confirmUnpackGroceries(directives, promotedCustomIds, clearCustomIds)

  /** Safely unchecks all checked grocery items without deleting needed meal-plan deficits (`1` write). */
  fun uncheckAllCheckedGrocery() = plannerAndRecipeDelegate.uncheckAllCheckedGrocery()

  /** Updates the active [GrocerySortMode] (`AISLE_SORTED`, `BY_RECIPE`, `URGENCY_FIRST`). */
  fun setGrocerySortMode(sortMode: GrocerySortMode) = vmCoreLaunchDomainGuarded {
    // Persist user-preferred aisle/recipe/urgency shopping sort order across sessions.
    storagePort.setGrocerySortMode(mutableUiState.value.snapshot, sortMode, vmCoreNowIso())
  }

  /** Adds a custom shopping note onto the Smart Grocery List in 1 `O(1)` snapshot write. */
  fun addCustomGroceryItem(item: CustomGroceryItem) = vmCoreLaunchDomainGuarded {
    // Custom notes stay in customGroceryItems until unpacked or explicitly promoted to Pantry.
    storagePort.addCustomGroceryItem(mutableUiState.value.snapshot, item, vmCoreNowIso())
    vmCorePostToast("Added ${item.displayName} to Grocery List.")
  }

  /** Clears all checked catalog and custom grocery rows in 1 `O(1)` snapshot write. */
  fun clearCheckedGroceryItems() = vmCoreLaunchDomainGuarded {
    storagePort.clearCheckedGroceryItems(mutableUiState.value.snapshot, vmCoreNowIso())
    vmCorePostToast("Cleared checked grocery items.")
  }

  /** Applies a Smart Grocery or Pantry catalog edit + `ElephantMemory` distillation in 1 `O(1)` write. */
  fun onGroceryAndCatalogEdit(action: GroceryAndCatalogEditAction) = vmCoreLaunchDomainGuarded {
    // Distill renames and default pack overrides into ElephantMemory inside the same batch write.
    val current = mutableUiState.value
    val (nextSnapshot, toast) = reduceGroceryAndCatalogEdit(current.snapshot, current.weeklyGroceryDiff, action, vmCoreNowIso())
    if (nextSnapshot != current.snapshot) storagePort.saveSnapshotBatch(nextSnapshot)
    vmCorePostToast(toast)
  }

  /** Starts offline voice dictation (`en-US` / `fr-FR`) targeting the Smart Grocery List. */
  fun startGroceryVoiceDictation(languageTag: String = VoiceInputPort.LOCALE_EN_US) {
    // Route finalized spoken transcript to CustomGroceryItem creation via VoiceCaptureTarget.
    mutableUiState.update { it.copy(activeVoiceTarget = VoiceCaptureTarget.GROCERY_DICTATION) }
    if (!voiceInputPort.startListening(languageTag)) {
      vmCorePostToast("Offline speech service unavailable on this device; use quick text input.")
    }
  }

  /** Opens `CleanRecipeReaderScreen` focused on [recipeId]. */
  fun openRecipeInReader(recipeId: String) =
    mutableUiState.update { it.copy(activeDestination = TopLevelDestination.RECIPES, selectedRecipeId = recipeId) }

  /** Updates the selected [Recipe.recipeId] inside `CleanRecipeReaderScreen`. */
  fun selectRecipe(recipeId: String) = mutableUiState.update { it.copy(selectedRecipeId = recipeId) }

  /** Closes `CleanRecipeReaderScreen` back onto the 2-Column Cookbook Grid (`selectedRecipeId = null`). */
  fun closeRecipeReaderBackToGrid() = plannerAndRecipeDelegate.closeRecipeReaderBackToGrid()

  /** Opens the 3-mode `AddOrEditRecipeSheet` studio (`SMART_PASTE`, `SCAN_COOKBOOK`, `QUICK_EDITOR`). */
  fun openAddRecipeStudio(
    mode: RecipeCreationMode = RecipeCreationMode.QUICK_EDITOR,
    scheduleByDefault: Boolean = false,
  ) = plannerAndRecipeDelegate.openAddRecipeStudio(mode, scheduleByDefault)

  /** Opens `AddOrEditRecipeSheet` pre-populated with a forked remix of [source] (`Make Our Version`). */
  fun openForkRecipeStudio(source: Recipe) = plannerAndRecipeDelegate.openForkRecipeStudio(source)

  /** Opens `AddOrEditRecipeSheet` pre-populated with an existing custom [recipe]. */
  fun openEditCustomRecipeStudio(recipe: Recipe) = plannerAndRecipeDelegate.openEditCustomRecipeStudio(recipe)

  /** Closes `AddOrEditRecipeSheet`. */
  fun dismissAddOrEditRecipeSheet() = plannerAndRecipeDelegate.dismissAddOrEditRecipeSheet()

  /** Updates the staged [SmartRecipeDraft] inside `AddOrEditRecipeSheet`. */
  fun updateEditingRecipeDraft(draft: SmartRecipeDraft) =
    plannerAndRecipeDelegate.updateEditingRecipeDraft(draft)

  /** Parses pasted raw recipe text into `editingRecipeDraft`. */
  fun parsePastedRecipeText(rawText: String) = plannerAndRecipeDelegate.parsePastedRecipeText(rawText)

  /** Scans a cookbook/recipe-card photo via Gemma Vision OCR into `editingRecipeDraft`. */
  fun scanCookbookPhotoIntoDraft(sourceUri: String) =
    plannerAndRecipeDelegate.scanCookbookPhotoIntoDraft(sourceUri)

  /** Materializes and saves [draft] (with optional board scheduling) in 1 `O(1)` batch write. */
  fun saveSmartRecipeDraft(
    draft: SmartRecipeDraft,
    scheduleDayIndex: Int? = null,
    scheduleSlotType: MealSlotType? = null,
  ) = plannerAndRecipeDelegate.saveSmartRecipeDraft(draft, scheduleDayIndex, scheduleSlotType)

  /** Deletes a user-created or forked recipe and removes its upcoming `PLANNED` slots (`1` write). */
  fun deleteCustomRecipe(recipeId: String) = plannerAndRecipeDelegate.deleteCustomRecipe(recipeId)

  /** Swaps [fromId] with [toId] on [recipeId] and recomputes per-serving nutrition (`1` write). */
  fun swapIngredientInRecipe(recipeId: String, fromId: String, toId: String) =
    plannerAndRecipeDelegate.swapIngredientInRecipe(recipeId, fromId, toId)

  /** Bookmarks or unbookmarks [recipeId] in `KitchenSnapshot.savedRecipeIds` (`O(1)` write). */
  fun toggleSavedRecipe(recipeId: String) = vmCoreLaunchDomainGuarded {
    val current = mutableUiState.value.snapshot
    val hydrated = if (current.recipes.any { it.recipeId == recipeId }) current else current.copy(recipes = current.allHouseholdRecipes)
    val next = storagePort.toggleSavedRecipe(hydrated, recipeId, vmCoreNowIso())
    vmCorePostToast(if (recipeId in next.savedRecipeIds) "Saved recipe to Bookmarks." else "Removed recipe from Bookmarks.")
  }

  /** Toggles an ingredient checkbox in `CleanRecipeReaderScreen` (`O(1)` write). */
  fun toggleReaderIngredientChecked(recipeId: String, ingredientId: String) = vmCoreLaunchDomainGuarded {
    storagePort.toggleReaderIngredientChecked(mutableUiState.value.snapshot, recipeId, ingredientId, false, vmCoreNowIso())
  }

  /** Clears all checked ingredients for [recipeId] in `CleanRecipeReaderScreen` (`O(1)` write). */
  fun resetCheckedReaderIngredients(recipeId: String) = vmCoreLaunchDomainGuarded {
    storagePort.toggleReaderIngredientChecked(mutableUiState.value.snapshot, recipeId, "", true, vmCoreNowIso())
  }

  /** Queues missing recipe ingredients onto Smart Grocery List in 1 `O(1)` write. */
  fun queueMissingRecipeIngredients(ingredientIds: Set<String>, deltas: List<RestockDelta>) {
    val combinedIds = ingredientIds + deltas.map { it.ingredientId }
    if (combinedIds.isEmpty()) return
    vmCoreLaunchDomainGuarded {
      val current = mutableUiState.value.snapshot
      storagePort.saveSnapshotBatch(current.copy(aiAddedGroceryIds = current.aiAddedGroceryIds + combinedIds, updatedAtIso = vmCoreNowIso()))
      vmCorePostToast("Queued ${combinedIds.size} missing staples onto Smart Grocery List.")
    }
  }

  /** Appends a custom household note to [recipeId] in `CleanRecipeReaderScreen` (`O(1)` write). */
  fun addRecipeKitchenNote(recipeId: String, noteText: String) {
    if (noteText.isBlank()) return
    vmCoreLaunchDomainGuarded {
      storagePort.addRecipeKitchenNote(mutableUiState.value.snapshot, recipeId, noteText, vmCoreNowIso())
      vmCorePostToast("Saved kitchen note.")
    }
  }

  /** Schedules [recipe] onto `(dayIndex, slotType)` enforcing `EC-01` (`<= 25m`) and `EC-02`. */
  fun scheduleRecipeToPlan(recipe: Recipe, dayIndex: Int, slotType: MealSlotType) =
    schedulePresetRecipeFromSheet(recipe, slotType, dayIndex, applyMondayThroughFriday = false)

  /** Confirms an offline `Intent.ACTION_SEND` plain-text recipe share from `CleanRecipeReaderScreen`. */
  fun notifyRecipeShared(recipe: Recipe) = vmCorePostToast("Prepared ${recipe.title} for offline sharing.")

  /** Launches full-screen `StepByStepCookModeScreen` for [recipe] with optional hands-free mic. */
  fun startCookMode(recipe: Recipe, slotId: String? = null, handsFreeVoiceActive: Boolean = false) =
    aiAndCookDelegate.startCookMode(recipe, slotId, handsFreeVoiceActive)

  /** Updates the active [CookModeSessionState] from pure `CookModeEngine` step/timer reducers. */
  fun updateCookModeSession(updatedSession: CookModeSessionState) =
    mutableUiState.update { it.copy(activeCookModeSession = updatedSession) }

  /** Minimizes Cook Mode into the persistent Mini-Timer bar while keeping countdowns running. */
  fun minimizeCookMode() = aiAndCookDelegate.minimizeCookMode()

  /** Restores a minimized Cook Mode session back to full screen. */
  fun resumeMinimizedCookMode() = aiAndCookDelegate.resumeMinimizedCookMode()

  /** Ends Cook Mode (both full-screen and minimized) without deducting pantry stock. */
  fun endCookModeSession() = aiAndCookDelegate.exitCookMode()

  /** Toggles hands-free voice listening inside `StepByStepCookModeScreen`. */
  fun toggleCookModeHandsFreeMic(enabled: Boolean) = aiAndCookDelegate.toggleCookModeHandsFreeMic(enabled)

  /** Opens `CookedOverrideSheet` for the active Cook Mode meal. */
  fun openCookModePantryAdjuster() = aiAndCookDelegate.openCookModePantryAdjuster()

  /** Completes Step-by-Step Cook Mode, commits `commitCookedMealBatch`, and opens `PostCookCompletionSheet`. */
  fun completeCookModeAndDeductPantry(session: CookModeSessionState) =
    aiAndCookDelegate.completeCookModeAndDeductPantry(session)

  /** Closes full-screen `StepByStepCookModeScreen` without deducting pantry stock. */
  fun exitCookMode() = aiAndCookDelegate.exitCookMode()

  /** Dismisses `PostCookCompletionSheet`. */
  fun dismissPostCookSummary() = plannerAndRecipeDelegate.dismissPostCookSummary()

  /** Commits `0`-BOM tomorrow Leftover Lunch and/or distills [reactionTag] into `ElephantMemory` (`1` write). */
  fun commitPostCookFollowUp(saveLeftoverLunch: Boolean, reactionTag: PostCookReactionTag?) =
    plannerAndRecipeDelegate.commitPostCookFollowUp(saveLeftoverLunch, reactionTag)

  /** Dispatches all unidirectional [AiChefAction] events emitted from `AiChefScreen` / `SousChefSheet`. */
  fun onAiChefAction(action: AiChefAction) = aiAndCookDelegate.handleAiChefAction(action)

  /** Restores `lastUndoableSnapshot` in 1 `O(1)` atomic batch write and clears Undo banner state. */
  fun undoLastAgentAction() {
    val prev = lastUndoableSnapshot ?: return
    lastUndoableSnapshot = null
    vmCoreLaunchDomainGuarded {
      // Restore pre-mutation household snapshot in 1 atomic write and clear confirmation badges.
      storagePort.saveSnapshotBatch(prev)
      mutableUiState.update {
        it.copy(
          canUndoLastAgentAction = false, lastAgentActionSummary = null, lastAgentActionAdjustTarget = null,
          confirmedVisualTurnIds = emptySet(), appliedSwapProposalIds = emptySet(), queuedIngredientProposalIds = emptySet(),
        ).withToast("Undid last Sous-Chef change.")
      }
    }
  }

  /** Dismisses the transparent-agency Undo/Adjust banner without reverting committed state. */
  fun dismissAgentActionBanner() {
    lastUndoableSnapshot = null
    mutableUiState.update {
      it.copy(canUndoLastAgentAction = false, lastAgentActionSummary = null, lastAgentActionAdjustTarget = null)
    }
  }

  // Record pre-mutation snapshot so every Sous-Chef mutation offers immediate 1-tap Undo.
  private fun vmCoreRecordAgentUndoSnapshot(
    previousSnapshot: KitchenSnapshot,
    summary: String,
    adjustTarget: TopLevelDestination,
  ) {
    lastUndoableSnapshot = previousSnapshot
    mutableUiState.update {
      it.copy(canUndoLastAgentAction = true, lastAgentActionSummary = summary, lastAgentActionAdjustTarget = adjustTarget)
    }
  }

  // --- First-Run Onboarding, Family Profile (`PHP` `₱` & Cuisines) & QA Scenario Lab ---

  /** Commits the 4-step [FirstRunOnboardingDraft] into profile, pantry, and 7-day plan in 1 `O(1)` write. */
  fun completeFirstRunOnboarding(draft: FirstRunOnboardingDraft) = vmCoreLaunchDomainGuarded {
    lastUndoableSnapshot = null
    val (seed, current) = vmCoreResolveSeedAndBaseSnapshot(defaultCompleted = false)
    val committed = storagePort.commitFirstRunOnboardingBatch(current, seed.initialPantry, draft, vmCoreNowIso())
    mutableUiState.update { state ->
      // Always land on TopLevelDestination.PLAN with SousChefSheet closed after setup completes.
      val refreshed = state.copy(
        isOnboardingReplayOpen = false, activeDestination = TopLevelDestination.PLAN,
        isSousChefSheetOpen = false, viewingNextWeek = false,
        canUndoLastAgentAction = false, lastAgentActionSummary = null, lastAgentActionAdjustTarget = null,
      ).withToast("Welcome to ${committed.householdProfile.householdName}! Your meal plan and pantry are ready.")
      deriveKitchenUiState(refreshed, committed, storagePort.batchWriteCallCount)
    }
  }

  /** Skips guided onboarding into the pre-stocked 44-item Sample Kitchen in 1 `O(1)` batch write. */
  fun skipFirstRunOnboardingWithDemo() = vmCoreLaunchDomainGuarded {
    val (seed, current) = vmCoreResolveSeedAndBaseSnapshot(defaultCompleted = true)
    val demo = storagePort.skipFirstRunOnboardingBatch(current, seed.initialPantry, vmCoreNowIso())
    mutableUiState.update { state ->
      // Land on TopLevelDestination.PLAN with SousChefSheet closed so sample week is front and center.
      val nextUi = state.copy(
        isOnboardingReplayOpen = false, activeDestination = TopLevelDestination.PLAN, isSousChefSheetOpen = false,
      ).withToast("Exploring Sample Kitchen — customize anytime in Family Profile.")
      deriveKitchenUiState(nextUi, demo, storagePort.batchWriteCallCount)
    }
  }

  /** Relaunches the 4-step guided setup wizard from `FamilyProfileSheet` or `QaScenarioLabSheet`. */
  fun relaunchFirstRunOnboarding() = mutableUiState.update {
    it.copy(
      isFamilyProfileSheetOpen = false, isQaLabSheetOpen = false,
      isSousChefSheetOpen = false, isOnboardingReplayOpen = true,
    )
  }

  /** Closes the replayed 4-step setup wizard without mutating persisted household state. */
  fun dismissOnboardingReplay() = mutableUiState.update { it.copy(isOnboardingReplayOpen = false) }

  // Lazily load canonical seed dataset when onboarding executes before initial hydration finishes.
  private suspend fun vmCoreResolveSeedAndBaseSnapshot(defaultCompleted: Boolean): Pair<SeedCatalogDataset, KitchenSnapshot> {
    val seed = cachedSeedCatalog ?: seedCatalogPort.loadSeedCatalog().also { cachedSeedCatalog = it }
    val snap = mutableUiState.value.snapshot
    val base = if (snap.ingredients.isEmpty()) KitchenSnapshot.fromSeedCatalog(seed, hasCompletedOnboarding = defaultCompleted) else snap
    return seed to base
  }

  /** Opens `FamilyProfileSheet`. */
  fun openFamilyProfileSheet() = mutableUiState.update { it.copy(isFamilyProfileSheetOpen = true) }

  /** Closes `FamilyProfileSheet`. */
  fun dismissFamilyProfileSheet() = mutableUiState.update { it.copy(isFamilyProfileSheetOpen = false) }

  /** Saves edited [HouseholdProfile] + `ElephantMemory` cuisine distillation in 1 `O(1)` write. */
  fun saveHouseholdProfile(updatedProfile: HouseholdProfile, recommendedGroceryIdsToQueue: Set<String>) =
    vmCoreLaunchDomainGuarded {
      val state = mutableUiState.value
      val next = reduceSavedHouseholdProfile(
        state.snapshot, updatedProfile, recommendedGroceryIdsToQueue, vmCoreNowIso(), state.realTodayIso,
      )
      storagePort.saveSnapshotBatch(next)
      mutableUiState.update {
        it.copy(isFamilyProfileSheetOpen = false)
          .withToast("Saved ${updatedProfile.householdName} · ${updatedProfile.currency.symbol} ${updatedProfile.marketRegionLabel}.")
      }
    }

  /** Applies a [ConsumerHouseholdTemplate] preset and rescales `PLANNED` slots in 1 `O(1)` write. */
  fun selectConsumerTemplate(template: ConsumerHouseholdTemplate) = vmCoreLaunchDomainGuarded {
    storagePort.applyConsumerHouseholdTemplate(mutableUiState.value.snapshot, template, nowIso = vmCoreNowIso())
    vmCorePostToast("Applied ${template.displayLabel} template.")
  }

  /** Updates [HouseholdCurrency] (`PHP` `₱` default) and [marketRegionLabel] in 1 `O(1)` write. */
  fun selectCurrencyAndRegion(currency: HouseholdCurrency, marketRegionLabel: String) = vmCoreLaunchDomainGuarded {
    storagePort.setHouseholdCurrencyAndRegion(mutableUiState.value.snapshot, currency, marketRegionLabel, vmCoreNowIso())
    vmCorePostToast("Updated currency to ${currency.isoCode} · $marketRegionLabel.")
  }

  /** Opens `QaScenarioLabSheet` showing live `batchWriteCallCount` & `EC-01`–`EC-13` runners. */
  fun openQaLabSheet() = mutableUiState.update { it.copy(isFamilyProfileSheetOpen = false, isQaLabSheetOpen = true) }

  /** Closes `QaScenarioLabSheet`. */
  fun dismissQaLabSheet() = mutableUiState.update { it.copy(isQaLabSheetOpen = false) }

  /** Switches simulated clock between `7:00 AM` (`7`) and `8:30 PM` (`20`) night-prep mode. */
  fun selectSimulatedClockHour(targetHour24: Int) = mutableUiState.update { current ->
    // Pin isSimulatedClockOverrideActive = true so wall-clock refreshes preserve QA Lab overrides.
    val updated = current.copy(isSimulatedClockOverrideActive = true, currentSimulatedHour24 = targetHour24.coerceIn(0, 23))
    deriveKitchenUiState(updated, updated.snapshot, storagePort.batchWriteCallCount)
  }

  /** Resets household state back to the canonical `seed_catalog.json` dataset in 1 `O(1)` write. */
  fun resetHouseholdStateToSeed() = vmCoreLaunchDomainGuarded {
    lastUndoableSnapshot = null
    val seed = cachedSeedCatalog ?: seedCatalogPort.loadSeedCatalog().also { cachedSeedCatalog = it }
    val fresh = storagePort.resetToSeed(seed)
    val nowClock = clockPort.now()
    val safeDay = nowClock.dayOfWeekIndex.coerceIn(0, 6)
    mutableUiState.update { current ->
      val resetUi = current.copy(
        realTodayIso = nowClock.todayIso, todayDayIndex = safeDay, activeDayIndex = safeDay,
        isSimulatedClockOverrideActive = false, currentSimulatedHour24 = nowClock.currentHour24.coerceIn(0, 23),
        viewingNextWeek = false, canUndoLastAgentAction = false,
        lastAgentActionSummary = null, lastAgentActionAdjustTarget = null,
        lastQaScenarioStatusMessage = "Household state restored to canonical Antipolo–Marikina PHP seed.",
      ).withToast("Reset household state to canonical seed in 1 atomic write.")
      deriveKitchenUiState(resetUi, fresh, storagePort.batchWriteCallCount)
    }
  }

  /** Executes a live deterministic `EC-01`–`EC-13` verification scenario in `QaScenarioLabSheet`. */
  fun triggerQaScenario(scenario: QaEdgeCaseScenario) = vmCoreLaunchDomainGuarded {
    val state = mutableUiState.value
    val outcome = executeLiveQaScenario(scenario, state.snapshot, state.activeDayIndex, vmCoreNowIso())
    if (outcome.shouldPersist) storagePort.saveSnapshotBatch(outcome.updatedSnapshot)
    mutableUiState.update { current ->
      val withStatus = current.copy(
        isSimulatedClockOverrideActive = outcome.simulatedHourOverride != null || current.isSimulatedClockOverrideActive,
        currentSimulatedHour24 = outcome.simulatedHourOverride ?: current.currentSimulatedHour24,
        lastQaScenarioStatusMessage = outcome.statusMessage,
      ).withToast(outcome.statusMessage)
      deriveKitchenUiState(withStatus, outcome.updatedSnapshot, storagePort.batchWriteCallCount)
    }
  }

  // Rethrows CancellationException and catches Throwable so JNI LinkageError/OOM never kill the app.
  private fun vmCoreLaunchDomainGuarded(block: suspend () -> Unit) {
    viewModelScope.launch {
      activeStorageJobs.incrementAndGet()
      mutableUiState.update { it.copy(isSavingBatch = true) }
      try {
        block()
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (domainError: KitchenBoardException) {
        vmCorePostToast(domainError.message ?: "Validation constraint blocked this kitchen action.")
      } catch (err: Throwable) {
        vmCorePostToast(err.message ?: "Unexpected offline error.")
      } finally {
        val remaining = activeStorageJobs.decrementAndGet().coerceAtLeast(0)
        mutableUiState.update { it.copy(isSavingBatch = remaining > 0) }
      }
    }
  }

  private fun vmCorePostToast(message: String) = mutableUiState.update { it.withToast(message) }

  // Sample real ISO-8601 instant from injected KitchenClockPort so JUnit tests stay deterministic.
  private fun vmCoreNowIso(): String = clockPort.now().nowTimestampIso

  /** Deterministically closes native LiteRT-LM / MediaPipe handles and `SpeechRecognizer` on teardown. */
  override fun onCleared() {
    // Delete any unsubmitted preprocessed attachment and release native speech/LLM handles.
    runCatching { deleteTempImage(mutableUiState.value.attachedImagePath) }
    runCatching { voiceInputPort.release() }
    runCatching { aiEdgePort.close() }
    super.onCleared()
  }

  /**
   * Lifecycle-safe [ViewModelProvider.Factory] wiring hexagonal ports, [KitchenClockPort], and
   * scoped EXIF/downscale preprocessing into [KitchenBoardViewModel].
   */
  class Factory(
    private val seedCatalogPort: SeedCatalogPort,
    private val storagePort: KitchenStoragePort,
    private val aiEdgePort: AiEdgePort,
    private val voiceInputPort: VoiceInputPort,
    private val cloudVaultPort: CloudCredentialVaultPort = InMemoryCloudCredentialVault(),
    private val appContext: Context? = null,
    private val autoInitializeAiOnStart: Boolean = false,
    private val clockPort: KitchenClockPort = SystemKitchenClockAdapter(),
  ) : ViewModelProvider.Factory {

    /** Instantiates [KitchenBoardViewModel] with EXIF-normalized `<= 1280px` image preprocessing. */
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      require(modelClass.isAssignableFrom(KitchenBoardViewModel::class.java)) {
        "Unsupported ViewModel class: ${modelClass.name}"
      }
      val safeContext = appContext?.applicationContext ?: appContext
      return KitchenBoardViewModel(
        seedCatalogPort = seedCatalogPort, storagePort = storagePort,
        aiEdgePort = aiEdgePort, voiceInputPort = voiceInputPort, cloudVaultPort = cloudVaultPort,
        preprocessImageUri = { raw -> preprocessImagePathOrUri(safeContext, raw) },
        deleteTempImage = { path -> KitchenImagePreprocessor.deletePathQuietly(path) },
        autoInitializeAiOnStart = autoInitializeAiOnStart, clockPort = clockPort,
      ) as T
    }
  }
}
