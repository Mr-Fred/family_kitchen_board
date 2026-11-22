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
import com.familykitchen.board.domain.AiMenuAdvisorEngine
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
import com.familykitchen.board.domain.MealSlotType
import com.familykitchen.board.domain.MenuPlannerEngine
import com.familykitchen.board.domain.PantryLedgerEngine
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.RestockDelta
import com.familykitchen.board.domain.SeedCatalogDataset
import com.familykitchen.board.domain.StapleStatus
import com.familykitchen.board.domain.StorageLocation
import com.familykitchen.board.domain.WeeklyPrepBatchCommitPayload
import com.familykitchen.board.domain.WorkoutTiming
import com.familykitchen.board.ports.AiEdgePort
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ports.CloudCredentialVaultPort
import com.familykitchen.board.ports.KitchenSnapshot
import com.familykitchen.board.ports.KitchenStoragePort
import com.familykitchen.board.ports.SeedCatalogPort
import com.familykitchen.board.ports.VoiceInputPort
import com.familykitchen.board.ports.VoiceInputState
import com.familykitchen.board.ui.pacer.PacerMode
import com.familykitchen.board.ui.sheets.CustomFamilyMealDraft
import com.familykitchen.board.ui.sheets.QaEdgeCaseScenario
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Unidirectional presentation orchestrator wiring SavorAI UI intents across pure domain engines
// and hexagonal persistence, multimodal LiteRT-LM, and bilingual speech ports.

/**
 * Primary Jetpack Lifecycle [ViewModel] orchestrating all 4 `SavorAI` tabs (`Ask AI`, `Pantry`,
 * `Recipes`, `Groceries`), full-screen `Step-by-Step Cook Mode`, and all 4 modal bottom sheets
 * (`CookedOverrideSheet`, `CustomMealSheet`, `FamilyProfileSheet`, `QaScenarioLabSheet`).
 *
 * Exposes a single immutable [uiState] `StateFlow`, executes every multi-item mutation through
 * `O(1)` atomic batch persistence on [storagePort], delegates multimodal AI and Cook Mode voice
 * flows to [KitchenAiAndCookDelegate], and deterministically closes native JNI and speech handles
 * inside [onCleared].
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
) : ViewModel() {

  // Backing state holder synchronized with KitchenStoragePort.snapshotFlow on every O(1) commit.
  private val mutableUiState = MutableStateFlow(
    KitchenUiState(cloudLlmConfig = cloudVaultPort.configFlow.value),
  )

  /** Hot immutable UI state stream observed via `collectAsStateWithLifecycle()` in `KitchenScaffold`. */
  val uiState: StateFlow<KitchenUiState> = mutableUiState.asStateFlow()

  // Cached offline seed dataset enabling instant 1-tap Reset Household State in QA Lab.
  private var cachedSeedCatalog: SeedCatalogDataset? = null

  // Pre-commit snapshot retained for 1-tap transparent-agency Undo after Sous-Chef mutations.
  private var lastUndoableSnapshot: KitchenSnapshot? = null

  // Tracks active O(1) storage/domain jobs so top-bar spinners reflect in-flight persistence.
  private val activeStorageJobs = AtomicInteger(0)

  // Cohesive AI Chef & Step-by-Step Cook Mode delegate keeping this ViewModel well under 700 LOC.
  private val aiAndCookDelegate = KitchenAiAndCookDelegate(
    scope = viewModelScope,
    mutableUiState = mutableUiState,
    storagePort = storagePort,
    aiEdgePort = aiEdgePort,
    voiceInputPort = voiceInputPort,
    aiChefCoordinator = AiChefCoordinator(
      aiEdgePort = aiEdgePort,
      onCleanupTempImage = { path -> deleteTempImage(path) },
    ),
    preprocessImageUri = preprocessImageUri,
    deleteTempImage = deleteTempImage,
    nowIso = ::nowIso,
    runGuarded = ::launchDomainGuarded,
    onOpenCustomMealSheet = { slotType -> openCustomMealSheet(slotType) },
    onSchedulePresetRecipe = ::schedulePresetRecipeFromSheet,
    onToggleSavedRecipe = ::toggleSavedRecipe,
    onQueueMissingIngredients = ::queueMissingRecipeIngredients,
    onOpenAdjustSlotSheet = ::openAdjustSlotSheet,
    onAddCustomGroceryItem = ::addCustomGroceryItem,
    recordUndoSnapshot = ::recordAgentUndoSnapshot,
    onUndoLastAgentAction = ::undoLastAgentAction,
    onDismissAgentActionBanner = ::dismissAgentActionBanner,
    cloudVaultPort = cloudVaultPort,
  )

  init {
    observeRuntimeAndVoiceStreams()
    hydrateInitialSnapshot(autoInitializeAiOnStart)
  }

  // Subscribe to storage, LiteRT-LM runtime, and SpeechRecognizer StateFlows on viewModelScope.
  private fun observeRuntimeAndVoiceStreams() {
    // Re-derive PantryExpiryDashboard, WeeklyGroceryDiff, and EC-13 swaps on each atomic write.
    viewModelScope.launch {
      storagePort.snapshotFlow.collect { latestSnapshot ->
        if (latestSnapshot != null) {
          mutableUiState.update { deriveKitchenUiState(it, latestSnapshot, storagePort.batchWriteCallCount) }
        }
      }
    }

    // Synchronize non-secret CloudLlmConfig metadata whenever the credential vault updates.
    viewModelScope.launch {
      cloudVaultPort.configFlow.collect { latestCloudConfig ->
        mutableUiState.update { it.copy(cloudLlmConfig = latestCloudConfig) }
      }
    }

    // Keep UI vision gate and hardware profile synchronized with loaded LiteRT-LM capabilities.
    viewModelScope.launch {
      aiEdgePort.runtimeState.collect { nextRuntime ->
        mutableUiState.update { current ->
          val profile = when {
            nextRuntime is AiRuntimeState.Ready &&
              nextRuntime.capabilities.supportsVision &&
              current.cloudLlmConfig.executionMode == AiExecutionMode.ON_DEVICE ->
              EdgeDeviceProfile.GEMMA_4_E2B_MULTIMODAL
            else -> current.activeDeviceProfile
          }
          current.copy(aiRuntimeState = nextRuntime, activeDeviceProfile = profile)
        }
      }
    }

    // Route finalized bilingual voice utterances to Cook Mode, Grocery Dictation, or AI Chef.
    viewModelScope.launch {
      voiceInputPort.voiceState.collect { nextVoice ->
        mutableUiState.update { it.copy(voiceInputState = nextVoice) }
        if (nextVoice is VoiceInputState.Result && nextVoice.transcript.isNotBlank()) {
          aiAndCookDelegate.routeVoiceTranscript(nextVoice.transcript)
        }
      }
    }
  }

  // Hydrate initial persisted household snapshot (or seed Antipolo-Marikina PHP ₱ defaults).
  private fun hydrateInitialSnapshot(autoInitializeAiOnStart: Boolean) = launchDomainGuarded {
    val seed = seedCatalogPort.loadSeedCatalog()
    cachedSeedCatalog = seed
    val loaded = storagePort.loadOrInitialize(seed)
    mutableUiState.update { deriveKitchenUiState(it, loaded, storagePort.batchWriteCallCount) }
    if (autoInitializeAiOnStart) initializeOnDeviceAiRuntime()
  }

  /** Triggers background app-scoped model discovery (`filesDir/models` & `externalFilesDir/models`). */
  fun initializeOnDeviceAiRuntime() {
    // Zero MANAGE_EXTERNAL_STORAGE required: scans app-scoped internal/external model dirs only.
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

  // --- Navigation, Layout, Theme, Search & Toast Actions ---

  /** Switches the active primary tab (`ASK_AI`, `PLAN`, `PANTRY`, `RECIPES`, `GROCERIES`). */
  fun selectDestination(destination: TopLevelDestination) =
    mutableUiState.update { it.copy(activeDestination = destination) }

  /** Routes legacy `GroceriesSubDestination` selections to `GROCERIES` or first-class `PLAN`. */
  fun selectGroceriesSubDestination(subDestination: GroceriesSubDestination) =
    mutableUiState.update { current ->
      when (subDestination) {
        GroceriesSubDestination.GROCERY_LIST -> current.copy(
          activeDestination = TopLevelDestination.GROCERIES,
          groceriesSubDestination = GroceriesSubDestination.GROCERY_LIST,
        )
        GroceriesSubDestination.WEEKLY_MEAL_PLAN -> current.copy(
          activeDestination = TopLevelDestination.PLAN,
          activePacerMode = PacerMode.DAILY_PACER,
          groceriesSubDestination = GroceriesSubDestination.WEEKLY_MEAL_PLAN,
        )
        GroceriesSubDestination.MORNING_PACER -> current.copy(
          activeDestination = TopLevelDestination.PLAN,
          activePacerMode = PacerMode.WEEKLY_PREP,
          groceriesSubDestination = GroceriesSubDestination.MORNING_PACER,
        )
      }
    }

  /** Jumps straight to the 4-station Weekly Meal Prep Session view on the `PLAN` tab. */
  fun openWeeklyPrepSession() = mutableUiState.update {
    it.copy(
      activeDestination = TopLevelDestination.PLAN,
      activePacerMode = PacerMode.WEEKLY_PREP,
      groceriesSubDestination = GroceriesSubDestination.MORNING_PACER,
    )
  }

  /** Updates the active [PacerMode] (`DAILY_PACER` vs `WEEKLY_PREP`) inside `MorningPacerScreen`. */
  fun setPacerMode(mode: PacerMode) = mutableUiState.update { it.copy(activePacerMode = mode) }

  /** Selects the active weekday (`0 = Monday`..`6 = Sunday`) on the 21-slot Weekly Meal Plan. */
  fun selectActiveDay(dayIndex: Int) = mutableUiState.update { current ->
    // Reset pacer slot focus and recompute resolved meal cards for the chosen day.
    val updated = current.copy(activeDayIndex = dayIndex.coerceIn(0, 6), activePacerSlotId = null)
    deriveKitchenUiState(updated, updated.snapshot, storagePort.batchWriteCallCount)
  }

  /** Expands or collapses the global top-bar search input, clearing query text when closed. */
  fun toggleSearchBar() = mutableUiState.update { current ->
    val nextExpanded = !current.isSearchBarExpanded
    current.copy(isSearchBarExpanded = nextExpanded, searchQuery = if (nextExpanded) current.searchQuery else "")
  }

  /** Updates the active global search query filtering Cookbook and Pantry views. */
  fun updateSearchQuery(query: String) = mutableUiState.update { it.copy(searchQuery = query) }

  /** Toggles between compact mobile layout and multi-column wide kitchen-counter layout. */
  fun toggleWideCounterLayout() = mutableUiState.update { current ->
    val nextWide = !current.isWideCounterLayout
    current.copy(isWideCounterLayout = nextWide)
      .withToast(if (nextWide) "Wide Counter Mode enabled." else "Compact Mobile Mode enabled.")
  }

  /** Toggles between Warm Daylight (`#FBFBF9`) and Ambient Counter Dark OLED themes. */
  fun toggleDarkCounterTheme() =
    mutableUiState.update { it.copy(isDarkCounterTheme = !it.isDarkCounterTheme) }

  /** Dismisses the sticky top confirmation or warning toast banner. */
  fun dismissToast() = mutableUiState.update { it.withToast(null) }

  /** Posts a dismissible export status or diagnostic banner (`Copy` / `Save PDF`) to [uiState]. */
  fun postExportFeedback(message: String) = postToast(message)

  // --- Board, Cooked Deduction (`EC-04`–`EC-07`), Swap (`EC-13`), Skip (`EC-12`) & Prep ---

  /** Launches full-screen `StepByStepCookModeScreen` (and syncs `MorningPacerScreen`) for [slotId]. */
  fun startCookingSlot(slotId: String) {
    val state = mutableUiState.value
    val slot = state.snapshot.weeklyBoard.slots.firstOrNull { it.slotId == slotId } ?: return
    val recipe = state.snapshot.recipesById[slot.recipeId] ?: return
    mutableUiState.update { it.copy(activePacerSlotId = slotId) }
    aiAndCookDelegate.startCookMode(recipe = recipe, slotId = slot.slotId, handsFreeVoiceActive = false)
  }

  /** Executes 1-tap `Cooked It` fast-path pantry deduction (`O(1)` atomic batch commit). */
  fun quickCookSlot(slotId: String) = launchDomainGuarded {
    // Fast-path cooked deduction subtracts scaled Tier-1 BOM + flat 1x workout boost in O(1) write.
    val current = mutableUiState.value.snapshot
    val slot = current.weeklyBoard.slots.firstOrNull { it.slotId == slotId } ?: return@launchDomainGuarded
    val recipe = current.recipesById[slot.recipeId] ?: return@launchDomainGuarded
    val payload = CookedDeductionPayload(slot.slotId, recipe.recipeId, slot.targetServings, slot.activeWorkoutModifier)
    val deduction = PantryLedgerEngine.applyCookedDeduction(
      current.pantryEntries, recipe, current.ingredients, payload, nowIso(), current.householdProfile.householdAllergens,
    )
    storagePort.commitCookedMealBatch(current, slot.slotId, deduction, nowIso())
    postToast("Cooked '${recipe.title}' & deducted pantry in 1 atomic batch.")
  }

  /** Opens `CookedOverrideSheet` for [slotId] (`EC-04`–`EC-07`, `EC-12`, `EC-13`). */
  fun openAdjustSlotSheet(slotId: String) = mutableUiState.update { current ->
    deriveKitchenUiState(current.copy(adjustingSlotId = slotId), current.snapshot, storagePort.batchWriteCallCount)
  }

  /** Closes `CookedOverrideSheet`. */
  fun dismissAdjustSlotSheet() = mutableUiState.update { it.copy(adjustingSlotId = null) }

  /** Commits an adjusted `CookedDeductionPayload` (`EC-04`, `EC-05`, `EC-06`) in 1 `O(1)` batch write. */
  fun confirmCookedOverride(payload: CookedDeductionPayload) = launchDomainGuarded {
    // Merges one-time overrides (EC-04), normalized defaults (EC-05), and ad-hoc extras (EC-06).
    val current = mutableUiState.value.snapshot
    val recipe = current.recipesById[payload.recipeId] ?: return@launchDomainGuarded
    val deduction = PantryLedgerEngine.applyCookedDeduction(
      current.pantryEntries, recipe, current.ingredients, payload, nowIso(), current.householdProfile.householdAllergens,
    )
    storagePort.commitCookedMealBatch(current, payload.slotId, deduction, nowIso())
    val savedNote = if (payload.saveOverrideAsHouseholdDefault) " (saved family default)" else ""
    mutableUiState.update {
      it.copy(adjustingSlotId = null).withToast("Cooked '${recipe.title}'$savedNote in 1 atomic batch.")
    }
  }

  /** Updates Dad's flat `1x` workout macro boost (`EC-07`) on [slotId] in 1 `O(1)` write. */
  fun selectSlotWorkoutModifier(slotId: String, modifier: WorkoutTiming) = launchDomainGuarded {
    // EC-07 extra BOM lines apply flat (1x) regardless of household serving multipliers.
    val current = mutableUiState.value.snapshot
    val updatedBoard = MenuPlannerEngine.toggleSlotWorkoutModifier(current.weeklyBoard, slotId, modifier)
    storagePort.saveSnapshotBatch(current.copy(weeklyBoard = updatedBoard, updatedAtIso = nowIso()))
    postToast("Updated workout boost (${modifier.name.replace('_', ' ').lowercase()}) in 1 batch write.")
  }

  /** Swaps [slotId] to 100% in-stock [newRecipeId] (`EC-01` & `EC-13` verified) in 1 `O(1)` write. */
  fun quickSwapSlotRecipe(slotId: String, newRecipeId: String) = launchDomainGuarded {
    // Enforces <= 25m morning cap on BREAKFAST slots and local availability / allergen gates.
    val current = mutableUiState.value.snapshot
    val replacement = current.recipesById[newRecipeId] ?: return@launchDomainGuarded
    val updatedBoard = MenuPlannerEngine.swapSlotRecipe(
      current.weeklyBoard, slotId, replacement, current.ingredients, current.householdProfile.householdAllergens,
    )
    storagePort.saveSnapshotBatch(current.copy(weeklyBoard = updatedBoard, updatedAtIso = nowIso()))
    postToast("Swapped meal to '${replacement.title}' (100% in stock).")
  }

  /** Marks [slotId] `COMPLETED_SKIPPED` (`EC-12`), deducting `0` stock and releasing BOM reservations. */
  fun markSlotSkippedOrLeftovers(slotId: String) = launchDomainGuarded {
    // EC-12 immediately releases reserved Tier-1 quantities back to downstream weekday slots.
    val current = mutableUiState.value.snapshot
    val updatedBoard = MenuPlannerEngine.markSlotSkippedOrLeftovers(current.weeklyBoard, slotId)
    storagePort.saveSnapshotBatch(current.copy(weeklyBoard = updatedBoard, updatedAtIso = nowIso()))
    mutableUiState.update {
      it.copy(adjustingSlotId = null).withToast("Marked Leftovers / Skipped—reserved pantry stock released.")
    }
  }

  /** Opens `CustomMealSheet` preselected to [initialSlotType] (or inferred from [forSlotId]). */
  fun openCustomMealSheet(initialSlotType: MealSlotType = MealSlotType.DINNER, forSlotId: String? = null) {
    val matched = forSlotId?.let { id -> mutableUiState.value.snapshot.weeklyBoard.slots.firstOrNull { it.slotId == id } }
    mutableUiState.update {
      it.copy(
        adjustingSlotId = null,
        activeDayIndex = matched?.dayIndex ?: it.activeDayIndex,
        customMealInitialSlotType = matched?.slotType ?: initialSlotType,
        isCustomMealSheetOpen = true,
      )
    }
  }

  /** Closes `CustomMealSheet`. */
  fun dismissCustomMealSheet() = mutableUiState.update { it.copy(isCustomMealSheetOpen = false) }

  /** Schedules a preset [recipe] onto a single day or `All Mon–Fri` in 1 `O(1)` batch write. */
  fun schedulePresetRecipeFromSheet(
    recipe: Recipe,
    slotType: MealSlotType,
    dayIndex: Int,
    applyMondayThroughFriday: Boolean,
  ) = launchDomainGuarded {
    val next = reduceScheduledPresetRecipe(
      mutableUiState.value.snapshot, recipe, slotType, dayIndex, applyMondayThroughFriday, nowIso(),
    )
    storagePort.saveSnapshotBatch(next)
    val scopeLabel = if (applyMondayThroughFriday) "Mon–Fri" else "Day ${dayIndex + 1}"
    mutableUiState.update {
      it.copy(isCustomMealSheetOpen = false)
        .withToast("Scheduled '${recipe.title}' for $scopeLabel ${slotType.name.lowercase()}.")
    }
  }

  /** Creates and schedules a custom household meal (`Single Day` or `Mon–Fri`) in 1 `O(1)` write. */
  fun saveCustomFamilyMeal(draft: CustomFamilyMealDraft) = launchDomainGuarded {
    // Validates EC-01 1.4x morning cap on BREAKFAST custom meals and EC-02 local availability.
    val current = mutableUiState.value.snapshot
    val slug = draft.title.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "meal" }
    val batch = AiMenuAdvisorEngine.createAndScheduleCustomFamilyMealBatch(
      board = current.weeklyBoard,
      existingRecipes = current.recipes,
      catalog = current.ingredients,
      recipeId = "rec_custom_${slug}_${current.recipes.size + 1}",
      title = draft.title,
      targetSlotType = draft.targetSlotType,
      targetDayIndex = draft.targetDayIndex,
      applyMondayThroughFriday = draft.applyMondayThroughFriday,
      rawPrepMinutes = draft.rawPrepMinutes,
      cookMinutes = draft.cookMinutes,
      bomLines = draft.bomLines,
      queueIngredientsToGrocery = draft.queueIngredientsToGrocery,
      existingQueuedGroceryIds = current.aiAddedGroceryIds,
      householdProfile = current.householdProfile,
    )
    storagePort.saveSnapshotBatch(
      current.copy(
        weeklyBoard = batch.updatedBoard,
        recipes = batch.updatedRecipes,
        aiAddedGroceryIds = batch.queuedGroceryIngredientIds,
        updatedAtIso = nowIso(),
      ),
    )
    mutableUiState.update {
      it.copy(isCustomMealSheetOpen = false, selectedRecipeId = batch.customRecipe.recipeId)
        .withToast("Saved custom meal '${batch.customRecipe.title}' in 1 atomic batch.")
    }
  }

  /** Toggles completion of an individual Weekly Prep station task in 1 `O(1)` write. */
  fun togglePrepStationTask(taskId: String, isCompleted: Boolean) = launchDomainGuarded {
    val current = mutableUiState.value.snapshot
    val nextIds = if (isCompleted) current.completedPrepTaskIds + taskId else current.completedPrepTaskIds - taskId
    storagePort.saveSnapshotBatch(current.copy(completedPrepTaskIds = nextIds, updatedAtIso = nowIso()))
  }

  /** Commits a 4-station Weekly Meal Prep Session batch in 1 `O(1)` snapshot write. */
  fun commitWeeklyPrepSession(payload: WeeklyPrepBatchCommitPayload) = launchDomainGuarded {
    // Stamps isPreppedAhead + preppedTimeSavedMinutes and optionally deducts Tier-1 batch stock.
    val state = mutableUiState.value
    val current = state.snapshot
    val result = MenuPlannerEngine.applyWeeklyPrepSessionBatch(
      current.weeklyBoard, current.pantryEntries, current.ingredients, state.weeklyPrepSessionPlan, payload, nowIso(),
    )
    storagePort.saveSnapshotBatch(
      current.copy(
        weeklyBoard = result.updatedBoard,
        pantryEntries = result.updatedPantry,
        completedPrepTaskIds = current.completedPrepTaskIds + payload.completedTaskIds,
        updatedAtIso = nowIso(),
      ),
    )
    postToast("Weekly Prep complete: saved ${result.totalTimeSavedMinutes}m across ${result.stampedSlotIds.size} meal(s)!")
  }

  // --- Smart Pantry & Expiry Actions (`EC-02`, `EC-03`, `EC-10`, `EC-11`) ---

  /** Applies a Fridge Pulse stepper delta (`±1 count` or `±100 g/ml`, clamped at `0.0` per `EC-10`). */
  fun adjustPantryQuantity(ingredientId: String, deltaQuantity: Double) = launchDomainGuarded {
    // EC-10 clamps Tier-1 stock at zero and sets isOutOfStock = true when depleted.
    val current = mutableUiState.value.snapshot
    val updated = PantryLedgerEngine.adjustPantryQuantity(
      current.pantryEntries, current.ingredients, ingredientId, deltaQuantity, nowIso(),
    )
    storagePort.saveSnapshotBatch(current.copy(pantryEntries = updated, updatedAtIso = nowIso()))
  }

  /** Updates Tier-2 `PLENTY | LOW | OUT` status (`EC-11`) in 1 `O(1)` snapshot write. */
  fun setStapleStatus(ingredientId: String, newStatus: StapleStatus) = launchDomainGuarded {
    // EC-11 queues 1 default package onto Smart Grocery whenever status transitions to LOW or OUT.
    val current = mutableUiState.value.snapshot
    val updated = PantryLedgerEngine.setStapleStatus(
      current.pantryEntries, current.ingredients, ingredientId, newStatus, nowIso(),
    )
    storagePort.saveSnapshotBatch(current.copy(pantryEntries = updated, updatedAtIso = nowIso()))
    postToast("Updated staple status to ${newStatus.name} in 1 batch write.")
  }

  /** Flips `isLocallyAvailable` (`EC-02` hard-hide / `EC-03` auto-substitute) in 1 `O(1)` write. */
  fun toggleLocalAvailability(ingredientId: String, isLocallyAvailable: Boolean) = launchDomainGuarded {
    val current = mutableUiState.value.snapshot
    val updatedCatalog = PantryLedgerEngine.toggleLocalAvailability(current.ingredients, ingredientId, isLocallyAvailable)
    storagePort.saveSnapshotBatch(current.copy(ingredients = updatedCatalog, updatedAtIso = nowIso()))
    val label = current.catalogById[ingredientId]?.displayName ?: ingredientId
    postToast(if (isLocallyAvailable) "Marked $label available locally." else "Marked $label unavailable locally (EC-02/EC-03 active).")
  }

  /** Toggles `+ Need to Buy` manual queueing on the Smart Grocery List in 1 `O(1)` write. */
  fun toggleManualGroceryQueue(ingredientId: String) = launchDomainGuarded {
    val current = mutableUiState.value.snapshot
    val isQueued = ingredientId in current.aiAddedGroceryIds
    val nextQueued = if (isQueued) current.aiAddedGroceryIds - ingredientId else current.aiAddedGroceryIds + ingredientId
    storagePort.saveSnapshotBatch(current.copy(aiAddedGroceryIds = nextQueued, updatedAtIso = nowIso()))
    val label = current.catalogById[ingredientId]?.displayName ?: ingredientId
    postToast(if (isQueued) "Removed $label from Grocery queue." else "Queued $label onto Smart Grocery List.")
  }

  /** Removes a manually queued catalog ID from `aiAddedGroceryIds` in 1 `O(1)` write. */
  fun removeManualGroceryQueue(ingredientId: String) = launchDomainGuarded {
    val current = mutableUiState.value.snapshot
    if (ingredientId in current.aiAddedGroceryIds) {
      storagePort.saveSnapshotBatch(current.copy(aiAddedGroceryIds = current.aiAddedGroceryIds - ingredientId, updatedAtIso = nowIso()))
    }
  }

  /** Adds a local-market preset or custom [IngredientCatalogItem] and queues it on Smart Grocery. */
  fun addIngredientToGrocery(item: IngredientCatalogItem) = launchDomainGuarded {
    val next = upsertCatalogAndPantrySnapshot(mutableUiState.value.snapshot, item, false, true, nowIso = nowIso())
    storagePort.saveSnapshotBatch(next)
    postToast("Added '${item.displayName}' to Smart Grocery List.")
  }

  /** Adds [item] to the household catalog and stocks 1 full package in Pantry (`O(1)` write). */
  fun addIngredientToPantry(item: IngredientCatalogItem) = launchDomainGuarded {
    val next = upsertCatalogAndPantrySnapshot(mutableUiState.value.snapshot, item, true, false, nowIso = nowIso())
    storagePort.saveSnapshotBatch(next)
    postToast("Stocked 1 package of '${item.displayName}' in Pantry.")
  }

  /** Adds [item] to Pantry with explicit [location] (`FRIDGE`/`PANTRY`/`FREEZER`) and [expiresInDays]. */
  fun addIngredientToPantryWithExpiry(item: IngredientCatalogItem, location: StorageLocation, expiresInDays: Int) =
    launchDomainGuarded {
      val next = upsertCatalogAndPantrySnapshot(mutableUiState.value.snapshot, item, true, false, location, expiresInDays, nowIso())
      storagePort.saveSnapshotBatch(next)
      postToast("Stocked '${item.displayName}' in ${location.displayLabel} ($expiresInDays days).")
    }

  /** Routes Smart Pantry's `Scan Receipt` command to `Ask AI` camera/gallery scan bar. */
  fun onScanReceiptFromPantry() = mutableUiState.update {
    // Zero fake prompt injection: prompts user to attach a real photo via FileProvider / Picker.
    it.copy(activeDestination = TopLevelDestination.ASK_AI)
      .withToast("Tap Camera or Gallery in the composer below to scan your receipt or fridge.")
  }

  /** Routes Smart Pantry's `AI Audit` command to `Ask AI` with an instant zero-waste audit prompt. */
  fun onRunAiAuditFromPantry() {
    mutableUiState.update { it.copy(activeDestination = TopLevelDestination.ASK_AI) }
    aiAndCookDelegate.submitTurn("Use expiring items in 2 days and optimize our family meals for zero food waste", null)
  }

  /** Opens `CleanRecipeReaderScreen` filtered to rescue recipes consuming [expiringCard]. */
  fun onFindRescueRecipesFromPantry(expiringCard: ExpiringPantryCardModel) = mutableUiState.update {
    val firstRescue = expiringCard.matchingRescueRecipes.firstOrNull()
    val msg = when (firstRescue) {
      null -> "Showing cookbook matches for ${expiringCard.catalogItem.displayName}."
      else -> "Found ${expiringCard.matchingRescueRecipes.size} rescue recipe(s) for ${expiringCard.catalogItem.displayName}."
    }
    it.copy(
      activeDestination = TopLevelDestination.RECIPES,
      selectedRecipeId = firstRescue?.recipeId ?: it.selectedRecipeId,
      searchQuery = if (firstRescue != null) "" else expiringCard.catalogItem.displayName,
      isSearchBarExpanded = firstRescue == null,
    ).withToast(msg)
  }

  /** Routes Smart Pantry's `Pair with <Co-Expiring>` pill to `Ask AI` zero-waste pairing. */
  fun onPairExpiringIngredientsFromPantry(primary: IngredientCatalogItem, partner: IngredientCatalogItem) {
    mutableUiState.update { it.copy(activeDestination = TopLevelDestination.ASK_AI) }
    aiAndCookDelegate.submitTurn("Create a family zero-waste meal using expiring ${primary.displayName} and ${partner.displayName}", null)
  }

  /** Opens tonight's top rescue dish from Smart Pantry's sticky bottom Waste-Prevention CTA. */
  fun onViewTonightRescuePlanFromPantry(rescueRecipes: List<Recipe>) {
    val topRescue = rescueRecipes.firstOrNull()
    when (topRescue) {
      null -> onRunAiAuditFromPantry()
      else -> mutableUiState.update {
        it.copy(activeDestination = TopLevelDestination.RECIPES, selectedRecipeId = topRescue.recipeId, searchQuery = "")
          .withToast("Rescue tonight: '${topRescue.title}'.")
      }
    }
  }

  // --- Smart Grocery List, Clean Recipe Reader, Cook Mode & AI Chef Delegated Entrypoints ---

  /** Toggles checked status on a catalog `ingredientId` or `CustomGroceryItem.customId` (`O(1)` write). */
  fun toggleGroceryItemChecked(itemId: String) = launchDomainGuarded {
    storagePort.toggleGroceryItemChecked(mutableUiState.value.snapshot, itemId, nowIso())
  }

  /** Commits checked Smart Grocery [RestockDelta]s into Pantry in 1 `O(1)` atomic batch write. */
  fun commitGroceryRestockBatch(deltas: List<RestockDelta>) {
    // EC-09 + EC-11: merges whole-package deltas and resets restocked Tier-2 staples to PLENTY.
    if (deltas.isEmpty()) return
    launchDomainGuarded {
      storagePort.commitGroceryRestockBatch(mutableUiState.value.snapshot, deltas, nowIso())
      postToast("Restocked ${deltas.size} checked item(s) into Pantry in 1 atomic batch.")
    }
  }

  /** Updates the active [GrocerySortMode] (`AISLE_SORTED`, `BY_RECIPE`, `URGENCY_FIRST`). */
  fun setGrocerySortMode(sortMode: GrocerySortMode) = launchDomainGuarded {
    storagePort.setGrocerySortMode(mutableUiState.value.snapshot, sortMode, nowIso())
  }

  /** Adds a custom shopping note onto the Smart Grocery List in 1 `O(1)` snapshot write. */
  fun addCustomGroceryItem(item: CustomGroceryItem) = launchDomainGuarded {
    storagePort.addCustomGroceryItem(mutableUiState.value.snapshot, item, nowIso())
    postToast("Added '${item.displayName}' to Grocery List.")
  }

  /** Clears all checked catalog and custom grocery rows in 1 `O(1)` snapshot write. */
  fun clearCheckedGroceryItems() = launchDomainGuarded {
    storagePort.clearCheckedGroceryItems(mutableUiState.value.snapshot, nowIso())
    postToast("Cleared checked grocery items.")
  }

  /** Applies a Smart Grocery or Pantry catalog edit (`rename`, `qty override`, `skip`, `restore`) in 1 `O(1)` write. */
  fun onGroceryAndCatalogEdit(action: GroceryAndCatalogEditAction) = launchDomainGuarded {
    // Purely reduces edit action and commits 1 O(1) snapshot write only when state changed.
    val current = mutableUiState.value
    val (nextSnapshot, toast) = reduceGroceryAndCatalogEdit(current.snapshot, current.weeklyGroceryDiff, action, nowIso())
    if (nextSnapshot != current.snapshot) storagePort.saveSnapshotBatch(nextSnapshot)
    postToast(toast)
  }

  /** Starts offline voice dictation (`en-US` / `fr-FR`) targeting the Smart Grocery List. */
  fun startGroceryVoiceDictation(languageTag: String = VoiceInputPort.LOCALE_EN_US) {
    mutableUiState.update { it.copy(activeVoiceTarget = VoiceCaptureTarget.GROCERY_DICTATION) }
    if (!voiceInputPort.startListening(languageTag)) {
      postToast("Offline speech service unavailable on this device; use quick text input.")
    }
  }

  /** Opens `CleanRecipeReaderScreen` focused on [recipeId]. */
  fun openRecipeInReader(recipeId: String) =
    mutableUiState.update { it.copy(activeDestination = TopLevelDestination.RECIPES, selectedRecipeId = recipeId) }

  /** Updates the selected [Recipe.recipeId] inside `CleanRecipeReaderScreen`. */
  fun selectRecipe(recipeId: String) = mutableUiState.update { it.copy(selectedRecipeId = recipeId) }

  /** Bookmarks or unbookmarks [recipeId] in `KitchenSnapshot.savedRecipeIds` (`O(1)` write). */
  fun toggleSavedRecipe(recipeId: String) = launchDomainGuarded {
    val next = storagePort.toggleSavedRecipe(mutableUiState.value.snapshot, recipeId, nowIso())
    postToast(if (recipeId in next.savedRecipeIds) "Saved recipe to Bookmarks." else "Removed recipe from Bookmarks.")
  }

  /** Toggles an ingredient checkbox in `CleanRecipeReaderScreen` (`O(1)` write). */
  fun toggleReaderIngredientChecked(recipeId: String, ingredientId: String) = launchDomainGuarded {
    storagePort.toggleReaderIngredientChecked(mutableUiState.value.snapshot, recipeId, ingredientId, false, nowIso())
  }

  /** Clears all checked ingredients for [recipeId] in `CleanRecipeReaderScreen` (`O(1)` write). */
  fun resetCheckedReaderIngredients(recipeId: String) = launchDomainGuarded {
    storagePort.toggleReaderIngredientChecked(mutableUiState.value.snapshot, recipeId, "", true, nowIso())
  }

  /** Queues missing recipe ingredients onto Smart Grocery List in 1 `O(1)` write. */
  fun queueMissingRecipeIngredients(ingredientIds: Set<String>, deltas: List<RestockDelta>) {
    val combinedIds = ingredientIds + deltas.map { it.ingredientId }
    if (combinedIds.isEmpty()) return
    launchDomainGuarded {
      val current = mutableUiState.value.snapshot
      storagePort.saveSnapshotBatch(current.copy(aiAddedGroceryIds = current.aiAddedGroceryIds + combinedIds, updatedAtIso = nowIso()))
      postToast("Queued ${combinedIds.size} missing staple(s) onto Smart Grocery List.")
    }
  }

  /** Appends a custom household note to [recipeId] in `CleanRecipeReaderScreen` (`O(1)` write). */
  fun addRecipeKitchenNote(recipeId: String, noteText: String) {
    if (noteText.isBlank()) return
    launchDomainGuarded {
      storagePort.addRecipeKitchenNote(mutableUiState.value.snapshot, recipeId, noteText, nowIso())
      postToast("Saved kitchen note.")
    }
  }

  /** Schedules [recipe] onto `(dayIndex, slotType)` enforcing `EC-01` (`<= 25m`) and `EC-02`. */
  fun scheduleRecipeToPlan(recipe: Recipe, dayIndex: Int, slotType: MealSlotType) =
    schedulePresetRecipeFromSheet(recipe, slotType, dayIndex, applyMondayThroughFriday = false)

  /** Confirms an offline `Intent.ACTION_SEND` plain-text recipe share from `CleanRecipeReaderScreen`. */
  fun notifyRecipeShared(recipe: Recipe) = postToast("Prepared '${recipe.title}' for offline sharing.")

  /** Launches full-screen `StepByStepCookModeScreen` for [recipe] with optional hands-free mic. */
  fun startCookMode(recipe: Recipe, slotId: String? = null, handsFreeVoiceActive: Boolean = false) =
    aiAndCookDelegate.startCookMode(recipe, slotId, handsFreeVoiceActive)

  /** Updates the active [CookModeSessionState] from pure `CookModeEngine` step/timer reducers. */
  fun updateCookModeSession(updatedSession: CookModeSessionState) =
    mutableUiState.update { it.copy(activeCookModeSession = updatedSession) }

  /** Toggles hands-free voice listening inside `StepByStepCookModeScreen`. */
  fun toggleCookModeHandsFreeMic(enabled: Boolean) = aiAndCookDelegate.toggleCookModeHandsFreeMic(enabled)

  /** Opens `CookedOverrideSheet` for the active Cook Mode meal. */
  fun openCookModePantryAdjuster() = aiAndCookDelegate.openCookModePantryAdjuster()

  /** Completes Step-by-Step Cook Mode and commits `commitCookedMealBatch` in 1 `O(1)` write. */
  fun completeCookModeAndDeductPantry(session: CookModeSessionState) =
    aiAndCookDelegate.completeCookModeAndDeductPantry(session)

  /** Closes full-screen `StepByStepCookModeScreen` without deducting pantry stock. */
  fun exitCookMode() = aiAndCookDelegate.exitCookMode()

  /** Dispatches all unidirectional [AiChefAction] events emitted from `AiChefScreen`. */
  fun onAiChefAction(action: AiChefAction) = aiAndCookDelegate.handleAiChefAction(action)

  /** Restores `lastUndoableSnapshot` in 1 `O(1)` atomic batch write and clears Undo banner state. */
  fun undoLastAgentAction() {
    val prev = lastUndoableSnapshot ?: return
    lastUndoableSnapshot = null
    launchDomainGuarded {
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
      it.copy(
        canUndoLastAgentAction = false,
        lastAgentActionSummary = null,
        lastAgentActionAdjustTarget = null,
      )
    }
  }

  private fun recordAgentUndoSnapshot(
    previousSnapshot: KitchenSnapshot,
    summary: String,
    adjustTarget: TopLevelDestination,
  ) {
    lastUndoableSnapshot = previousSnapshot
    mutableUiState.update {
      it.copy(
        canUndoLastAgentAction = true,
        lastAgentActionSummary = summary,
        lastAgentActionAdjustTarget = adjustTarget,
      )
    }
  }

  // --- First-Run Onboarding, Family Profile (`PHP` `₱` & Templates) & QA Scenario Lab ---

  /** Commits the 4-step [FirstRunOnboardingDraft] into profile, pantry, and 7-day plan in 1 `O(1)` write. */
  fun completeFirstRunOnboarding(draft: FirstRunOnboardingDraft) = launchDomainGuarded {
    // Clear prior agent undo history so a stale pre-onboarding snapshot cannot overwrite setup.
    lastUndoableSnapshot = null
    val (seed, current) = resolveSeedAndBaseSnapshot(defaultCompleted = false)
    // Single O(1) atomic batch write applying profile, starting pantry mode, and 21-slot board.
    val committed = storagePort.commitFirstRunOnboardingBatch(current, seed.initialPantry, draft, nowIso())
    mutableUiState.update { state ->
      val refreshed = state.copy(
        isOnboardingReplayOpen = false, activeDestination = TopLevelDestination.PLAN,
        canUndoLastAgentAction = false, lastAgentActionSummary = null, lastAgentActionAdjustTarget = null,
      ).withToast("Welcome to ${committed.householdProfile.householdName}! Your 7-day plan & pantry are ready.")
      deriveKitchenUiState(refreshed, committed, storagePort.batchWriteCallCount)
    }
  }

  /** Skips guided onboarding into the pre-stocked 44-item Sample Kitchen in 1 `O(1)` batch write. */
  fun skipFirstRunOnboardingWithDemo() = launchDomainGuarded {
    // Commit canonical 44-item demo stock in 1 O(1) write so impatient users explore immediately.
    val (seed, current) = resolveSeedAndBaseSnapshot(defaultCompleted = true)
    val demo = storagePort.skipFirstRunOnboardingBatch(current, seed.initialPantry, nowIso())
    mutableUiState.update { state ->
      val msg = "Exploring Sample Kitchen — customize anytime in Family Profile."
      deriveKitchenUiState(state.copy(isOnboardingReplayOpen = false).withToast(msg), demo, storagePort.batchWriteCallCount)
    }
  }

  /** Relaunches the 4-step guided setup wizard from `FamilyProfileSheet` or `QaScenarioLabSheet`. */
  fun relaunchFirstRunOnboarding() = mutableUiState.update {
    // Dismiss open sheets first so modal scrims never stack behind the full-screen setup wizard.
    it.copy(isFamilyProfileSheetOpen = false, isQaLabSheetOpen = false, isOnboardingReplayOpen = true)
  }

  /** Closes the replayed 4-step setup wizard without mutating persisted household state. */
  fun dismissOnboardingReplay() = mutableUiState.update { it.copy(isOnboardingReplayOpen = false) }

  private suspend fun resolveSeedAndBaseSnapshot(defaultCompleted: Boolean): Pair<SeedCatalogDataset, KitchenSnapshot> {
    val seed = cachedSeedCatalog ?: seedCatalogPort.loadSeedCatalog().also { cachedSeedCatalog = it }
    val snap = mutableUiState.value.snapshot
    val base = if (snap.ingredients.isEmpty()) KitchenSnapshot.fromSeedCatalog(seed, hasCompletedOnboarding = defaultCompleted) else snap
    return seed to base
  }

  /** Opens `FamilyProfileSheet`. */
  fun openFamilyProfileSheet() = mutableUiState.update { it.copy(isFamilyProfileSheetOpen = true) }

  /** Closes `FamilyProfileSheet`. */
  fun dismissFamilyProfileSheet() = mutableUiState.update { it.copy(isFamilyProfileSheetOpen = false) }

  /** Saves edited [HouseholdProfile] + life-event recommended staples in 1 `O(1)` batch write. */
  fun saveHouseholdProfile(updatedProfile: HouseholdProfile, recommendedGroceryIdsToQueue: Set<String>) =
    launchDomainGuarded {
      val next = reduceSavedHouseholdProfile(mutableUiState.value.snapshot, updatedProfile, recommendedGroceryIdsToQueue, nowIso())
      storagePort.saveSnapshotBatch(next)
      mutableUiState.update {
        it.copy(isFamilyProfileSheetOpen = false)
          .withToast("Saved ${updatedProfile.householdName} (${updatedProfile.currency.symbol} • ${updatedProfile.marketRegionLabel}).")
      }
    }

  /** Applies a [ConsumerHouseholdTemplate] preset and rescales `PLANNED` slots in 1 `O(1)` write. */
  fun selectConsumerTemplate(template: ConsumerHouseholdTemplate) = launchDomainGuarded {
    storagePort.applyConsumerHouseholdTemplate(mutableUiState.value.snapshot, template, nowIso = nowIso())
    postToast("Applied '${template.displayLabel}' template.")
  }

  /** Updates [HouseholdCurrency] (`PHP` `₱` default) and [marketRegionLabel] in 1 `O(1)` write. */
  fun selectCurrencyAndRegion(currency: HouseholdCurrency, marketRegionLabel: String) = launchDomainGuarded {
    storagePort.setHouseholdCurrencyAndRegion(mutableUiState.value.snapshot, currency, marketRegionLabel, nowIso())
    postToast("Updated currency to ${currency.displayLabel} ($marketRegionLabel).")
  }

  /** Opens `QaScenarioLabSheet` showing live `batchWriteCallCount` & `EC-01`–`EC-13` runners. */
  fun openQaLabSheet() = mutableUiState.update { it.copy(isQaLabSheetOpen = true) }

  /** Closes `QaScenarioLabSheet`. */
  fun dismissQaLabSheet() = mutableUiState.update { it.copy(isQaLabSheetOpen = false) }

  /** Switches simulated clock between `7:00 AM` (`7`) and `8:30 PM` (`20`) night-prep mode. */
  fun selectSimulatedClockHour(targetHour24: Int) = mutableUiState.update { current ->
    // Activating hour >= 20 reveals the Indigo 8:30 PM 5-Min Night-Before Prep card on Today.
    val updated = current.copy(currentSimulatedHour24 = targetHour24.coerceIn(0, 23))
    deriveKitchenUiState(updated, updated.snapshot, storagePort.batchWriteCallCount)
  }

  /** Resets household state back to the canonical `seed_catalog.json` dataset in 1 `O(1)` write. */
  fun resetHouseholdStateToSeed() = launchDomainGuarded {
    lastUndoableSnapshot = null
    val seed = cachedSeedCatalog ?: seedCatalogPort.loadSeedCatalog().also { cachedSeedCatalog = it }
    val fresh = storagePort.resetToSeed(seed)
    mutableUiState.update { current ->
      val resetUi = current.copy(
        currentSimulatedHour24 = 7,
        canUndoLastAgentAction = false,
        lastAgentActionSummary = null,
        lastAgentActionAdjustTarget = null,
        lastQaScenarioStatusMessage = "Household state restored to canonical Antipolo–Marikina PHP (₱) seed.",
      ).withToast("Reset household state to canonical seed in 1 atomic write.")
      deriveKitchenUiState(resetUi, fresh, storagePort.batchWriteCallCount)
    }
  }

  /** Executes a live deterministic `EC-01`–`EC-13` verification scenario in `QaScenarioLabSheet`. */
  fun triggerQaScenario(scenario: QaEdgeCaseScenario) = launchDomainGuarded {
    val state = mutableUiState.value
    val outcome = executeLiveQaScenario(scenario, state.snapshot, state.activeDayIndex, nowIso())
    if (outcome.shouldPersist) storagePort.saveSnapshotBatch(outcome.updatedSnapshot)
    mutableUiState.update { current ->
      val withStatus = current.copy(
        currentSimulatedHour24 = outcome.simulatedHourOverride ?: current.currentSimulatedHour24,
        lastQaScenarioStatusMessage = outcome.statusMessage,
      ).withToast(outcome.statusMessage)
      deriveKitchenUiState(withStatus, outcome.updatedSnapshot, storagePort.batchWriteCallCount)
    }
  }

  // Rethrows CancellationException and catches Throwable so JNI LinkageError/OOM never kill the app.
  private fun launchDomainGuarded(block: suspend () -> Unit) {
    viewModelScope.launch {
      // Increment active O(1) batch job count with zero artificial delay so tests stay instant.
      activeStorageJobs.incrementAndGet()
      mutableUiState.update { it.copy(isSavingBatch = true) }
      try {
        block()
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (domainError: KitchenBoardException) {
        postToast(domainError.message ?: "Validation constraint blocked this kitchen action.")
      } catch (err: Throwable) {
        postToast(err.message ?: "Unexpected offline error.")
      } finally {
        val remaining = activeStorageJobs.decrementAndGet().coerceAtLeast(0)
        mutableUiState.update { it.copy(isSavingBatch = remaining > 0) }
      }
    }
  }

  private fun postToast(message: String) = mutableUiState.update { it.withToast(message) }

  private fun nowIso(): String = Instant.now().toString()

  /** Deterministically closes native LiteRT-LM / MediaPipe handles and `SpeechRecognizer` on teardown. */
  override fun onCleared() {
    // Delete any unsubmitted staged capture file and release JNI + SpeechRecognizer handles safely.
    runCatching { deleteTempImage(mutableUiState.value.attachedImagePath) }
    runCatching { voiceInputPort.release() }
    runCatching { aiEdgePort.close() }
    super.onCleared()
  }

  /**
   * Lifecycle-safe [ViewModelProvider.Factory] wiring hexagonal ports and scoped EXIF/downscale
   * preprocessing into [KitchenBoardViewModel].
   */
  class Factory(
    private val seedCatalogPort: SeedCatalogPort,
    private val storagePort: KitchenStoragePort,
    private val aiEdgePort: AiEdgePort,
    private val voiceInputPort: VoiceInputPort,
    private val cloudVaultPort: CloudCredentialVaultPort = InMemoryCloudCredentialVault(),
    private val appContext: Context? = null,
    private val autoInitializeAiOnStart: Boolean = false,
  ) : ViewModelProvider.Factory {

    /** Instantiates [KitchenBoardViewModel] with EXIF-normalized `<= 1280px` image preprocessing. */
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      require(modelClass.isAssignableFrom(KitchenBoardViewModel::class.java)) {
        "Unsupported ViewModel class: ${modelClass.name}"
      }
      val safeContext = appContext?.applicationContext ?: appContext
      return KitchenBoardViewModel(
        seedCatalogPort = seedCatalogPort,
        storagePort = storagePort,
        aiEdgePort = aiEdgePort,
        voiceInputPort = voiceInputPort,
        cloudVaultPort = cloudVaultPort,
        preprocessImageUri = { raw -> preprocessImagePathOrUri(safeContext, raw) },
        deleteTempImage = { path -> KitchenImagePreprocessor.deletePathQuietly(path) },
        autoInitializeAiOnStart = autoInitializeAiOnStart,
      ) as T
    }
  }
}
