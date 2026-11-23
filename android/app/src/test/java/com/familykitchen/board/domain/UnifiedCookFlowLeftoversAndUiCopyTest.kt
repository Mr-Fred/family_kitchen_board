package com.familykitchen.board.domain

import com.familykitchen.board.data.FakeKitchenStorage
import com.familykitchen.board.ports.AiEdgePort
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ports.FixedKitchenClock
import com.familykitchen.board.ports.KitchenSnapshot
import com.familykitchen.board.ports.SeedCatalogPort
import com.familykitchen.board.ports.VoiceInputPort
import com.familykitchen.board.ports.VoiceInputState
import com.familykitchen.board.ui.KitchenBoardViewModel
import com.familykitchen.board.ui.KitchenUiState
import com.familykitchen.board.ui.TopLevelDestination
import java.nio.file.Path
import kotlin.io.path.Path as KotlinPath
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Suite 18: Hermetic JVM JUnit 4 verification of 4-tab TopLevelDestination navigation, persistent
// Mini-Timer Cook Mode minimize/resume, pantry-aware CookModeEngine Q&A, 2x batch cook -> 0-BOM
// tomorrow Leftover Lunch, live ElephantMemory distillation, and zero "/" or "()" UI copy labels.

/**
 * Suite 18 hermetic JUnit 4 verification suite covering the 4-tab `SavorAI` shell, unified Cook
 * Flow, `0`-BOM Leftover Lunch scheduling, live `ElephantMemory` distillation, and clean UI copy:
 * 1. 4-tab [TopLevelDestination.entries] (`PLAN, PANTRY, RECIPES, GROCERIES`, `default = PLAN`),
 * 2. [KitchenBoardViewModel.minimizeCookMode] and [KitchenBoardViewModel.resumeMinimizedCookMode]
 *    preserving active step progress and live countdown timers across tab navigation,
 * 3. Pantry-aware [CookModeEngine.answerCookStepQuestion] citing in-stock `EC-03` substitutes and
 *    culinary equivalents instead of echoing step text,
 * 4. `2x` (`8` servings) batch cook -> [KitchenBoardViewModel.completeCookModeAndDeductPantry] ->
 *    [KitchenBoardViewModel.commitPostCookFollowUp] / [scheduleLeftoverLunchFromCook] scheduling
 *    tomorrow's lunch with **`0` extra grocery BOM** reserved on [WeeklyGroceryDiff],
 * 5. Live `HouseholdElephantMemory` distillation across Profile, Cook Override, Skip Reason,
 *    Catalog Pack Edit, and Post-Cook Reaction actions,
 * 6. Strict `0` `'/'` and `0` `'('` / `')'` punctuation compliance across all v2 UI enums and
 *    starter menu templates.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnifiedCookFlowLeftoversAndUiCopyTest {

  // Minimal offline AiEdgePort test double keeping Cook Flow and ElephantMemory tests hermetic.
  private class OfflineCookFlowEdgePort : AiEdgePort {
    private val mutableState = MutableStateFlow<AiRuntimeState>(AiRuntimeState.Uninitialized)
    override val runtimeState: StateFlow<AiRuntimeState> = mutableState.asStateFlow()
    override suspend fun autoDiscoverAndInitialize(): AiRuntimeState = mutableState.value
    override suspend fun importAndLoadModel(sourceUriString: String, displayName: String?): AiRuntimeState = mutableState.value
    override suspend fun generateText(prompt: String): String = "{}"
    override suspend fun analyzeImageWithPrompt(preprocessedImagePath: String, prompt: String): String = "{}"
    override fun close() = Unit
  }

  // Minimal offline VoiceInputPort test double with zero Android platform dependencies.
  private class OfflineCookFlowVoicePort : VoiceInputPort {
    private val mutableVoice = MutableStateFlow<VoiceInputState>(VoiceInputState.Idle)
    override val voiceState: StateFlow<VoiceInputState> = mutableVoice.asStateFlow()
    override fun startListening(languageTag: String): Boolean = true
    override fun stopListening() = Unit
    override fun release() = Unit
  }

  // Resolve repository-relative paths deterministically from either root or android/app cwd.
  private fun resolveRepoFile(relativeFromRepoRoot: String): Path {
    var cursor: Path? = KotlinPath("").toAbsolutePath().normalize()
    repeat(6) {
      val candidate = cursor?.resolve(relativeFromRepoRoot)
      if (candidate != null && candidate.isRegularFile()) return candidate
      cursor = cursor?.parent
    }
    error("Unable to locate '$relativeFromRepoRoot' from test working directory.")
  }

  // Load the canonical 44-item seed catalog dataset from disk.
  private fun loadCanonicalSeed(): SeedCatalogDataset =
    SchemaValidator.decodeSeedCatalogDatasetJson(resolveRepoFile("data/seed_catalog.json").readText())

  /**
   * Verifies 4-tab [TopLevelDestination] (`PLAN, PANTRY, RECIPES, GROCERIES`, `default = PLAN`),
   * [KitchenBoardViewModel.minimizeCookMode] / [KitchenBoardViewModel.resumeMinimizedCookMode]
   * timer preservation, and pantry-aware [CookModeEngine.answerCookStepQuestion].
   */
  @Test
  fun fourTabNavigationMinimizeCookTimersAndPantryAwareStepQa_workSeamlesslyOffline() = runTest {
    val testDispatcher = UnconfinedTestDispatcher(testScheduler)
    Dispatchers.setMain(testDispatcher)
    try {
      // 1. Verify 4-tab TopLevelDestination contract and default PLAN landing tab.
      assertEquals(
        listOf(
          TopLevelDestination.PLAN,
          TopLevelDestination.PANTRY,
          TopLevelDestination.RECIPES,
          TopLevelDestination.GROCERIES,
        ),
        TopLevelDestination.entries,
      )
      assertEquals(listOf("Plan", "Pantry", "Recipes", "Shop"), TopLevelDestination.entries.map { it.navLabel })
      assertEquals(TopLevelDestination.PLAN, KitchenUiState().activeDestination)

      // 2. Start Cook Mode, start step 1 timer, minimize to ActiveCookMiniBar, switch tabs, tick 5s, and resume!
      val seed = loadCanonicalSeed()
      val storage = FakeKitchenStorage(initialSnapshot = KitchenSnapshot.fromSeedCatalog(seed))
      val viewModel = KitchenBoardViewModel(
        seedCatalogPort = SeedCatalogPort { seed },
        storagePort = storage,
        aiEdgePort = OfflineCookFlowEdgePort(),
        voiceInputPort = OfflineCookFlowVoicePort(),
        clockPort = FixedKitchenClock("2026-11-23", currentHour24 = 18),
      )
      val efoRiro = viewModel.uiState.value.snapshot.allHouseholdRecipes.first {
        it.recipeId == "rec_31_yoruba_efo_riro"
      }
      viewModel.startCookMode(efoRiro, slotId = null, handsFreeVoiceActive = false)
      val started = viewModel.uiState.value.activeCookModeSession ?: error("Expected active CookModeSessionState")
      val withRunningTimer = CookModeEngine.toggleStepTimer(CookModeEngine.advanceStep(started))
      val initialRemaining = withRunningTimer.activeStepTimer?.remainingSeconds ?: 0
      viewModel.updateCookModeSession(withRunningTimer)

      viewModel.minimizeCookMode()
      viewModel.selectDestination(TopLevelDestination.PANTRY)
      assertTrue(viewModel.uiState.value.isCookModeMinimized)
      assertEquals(TopLevelDestination.PANTRY, viewModel.uiState.value.activeDestination)

      // Simulate 5 live seconds ticking on ActiveCookMiniBar while browsing Pantry, then resume full screen.
      val ticked = CookModeEngine.tickRunningTimers(
        viewModel.uiState.value.activeCookModeSession ?: error("Expected minimized session"),
        elapsedSeconds = 5,
      )
      viewModel.updateCookModeSession(ticked)
      viewModel.resumeMinimizedCookMode()
      assertFalse(viewModel.uiState.value.isCookModeMinimized)
      assertEquals(1, viewModel.uiState.value.activeCookModeSession?.currentStepIndex)
      assertEquals(initialRemaining - 5, viewModel.uiState.value.activeCookModeSession?.activeStepTimer?.remainingSeconds)

      viewModel.endCookModeSession()
      assertNull(viewModel.uiState.value.activeCookModeSession)

      // 3. Pantry-aware CookModeEngine Q&A cites in-stock Fresh Amaranth Greens when out of Baby Spinach.
      val ratatouille = viewModel.uiState.value.snapshot.allHouseholdRecipes.first {
        it.recipeId == "rec_38_ratatouille_provencale_baked_eggs"
      }
      val pantryNoSpinachWithAmaranth = seed.initialPantry.map { entry ->
        when (entry.ingredientId) {
          "ing_spinach" -> entry.copy(currentQuantity = 0.0, isOutOfStock = true, stapleStatus = StapleStatus.OUT)
          "ing_amaranth_greens" -> entry.copy(currentQuantity = 300.0, isOutOfStock = false, stapleStatus = StapleStatus.PLENTY)
          else -> entry
        }
      }
      val answer = CookModeEngine.answerCookStepQuestion(
        recipe = ratatouille,
        stepIndex = 1,
        question = "We ran out of baby spinach, what substitute do I have in stock?",
        catalog = seed.ingredients,
        youngestStage = ChildGrowthStage.TODDLER_1_2Y,
        pantryEntries = pantryNoSpinachWithAmaranth,
      )
      assertTrue(answer.contains("Amaranth", ignoreCase = true))
      assertTrue(answer.contains("in stock", ignoreCase = true))
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * Verifies `2x` (`8` servings) batch cooking emits `extraLeftoverServings == 4` and schedules
   * tomorrow's `0`-BOM Leftover Lunch (`leftoverSourceRecipeId != null`) with **zero extra grocery
   * deficit** on [GroceryDiffEngine.computeWeeklyGroceryDiff].
   */
  @Test
  fun batchCookDoubleYield_schedulesZeroBomTomorrowLeftoverLunchInOneFollowUpWrite() = runTest {
    val testDispatcher = UnconfinedTestDispatcher(testScheduler)
    Dispatchers.setMain(testDispatcher)
    try {
      val seed = loadCanonicalSeed()
      val allRecipes = mergeHeritageRecipesIntoLibrary(seed.recipes)
      val jollof = allRecipes.first { it.recipeId == "rec_33_party_jollof_braised_chicken" }

      // Start with an empty pantry and only Monday Dinner (8 servings = 2x batch cook of Jollof).
      val zeroPantry = seed.initialPantry.map {
        it.copy(currentQuantity = 0.0, isOutOfStock = true, stapleStatus = StapleStatus.OUT, expiresInDays = null)
      }
      val monDinnerSlot = MealSlot(
        slotId = "board_cook_day_0_dinner",
        dayIndex = 0,
        slotType = MealSlotType.DINNER,
        owner = CookOwner.WIFE,
        recipeId = jollof.recipeId,
        targetServings = 8,
        status = MealSlotStatus.PLANNED,
      )
      val initialSnapshot = KitchenSnapshot(
        ingredients = seed.ingredients,
        recipes = allRecipes,
        pantryEntries = zeroPantry,
        weeklyBoard = WeeklyBoard("board_cook", "household_family_4", "2026-11-23", listOf(monDinnerSlot)),
      )
      val storage = FakeKitchenStorage(initialSnapshot = initialSnapshot)
      val viewModel = KitchenBoardViewModel(
        seedCatalogPort = SeedCatalogPort { seed },
        storagePort = storage,
        aiEdgePort = OfflineCookFlowEdgePort(),
        voiceInputPort = OfflineCookFlowVoicePort(),
        clockPort = FixedKitchenClock("2026-11-23", currentHour24 = 19),
      )
      storage.resetWriteCounter()

      // Launch Cook Mode for Monday's 8-serving Jollof slot and complete cooking (1 O(1) write).
      viewModel.startCookingSlot(monDinnerSlot.slotId)
      val activeSession = viewModel.uiState.value.activeCookModeSession ?: error("Expected CookModeSessionState")
      assertEquals(8, activeSession.targetServings)
      viewModel.completeCookModeAndDeductPantry(activeSession)
      assertEquals(1, storage.batchWriteCallCount)

      val summary = viewModel.uiState.value.postCookSummary
      assertNotNull(summary)
      assertEquals(8, summary?.cookedServings)
      assertEquals(4, summary?.householdServings)
      assertEquals(4, summary?.extraLeftoverServings)

      // Save 4 extra portions into Tuesday Lunch + record KIDS_LOVED reaction in 1 O(1) follow-up write.
      viewModel.commitPostCookFollowUp(saveLeftoverLunch = true, reactionTag = PostCookReactionTag.KIDS_LOVED)
      assertEquals(2, storage.batchWriteCallCount)
      assertNull(viewModel.uiState.value.postCookSummary)

      val updatedBoard = viewModel.uiState.value.snapshot.weeklyBoard
      val tuesdayLeftoverLunch = updatedBoard.slots.first {
        it.dayIndex == 1 && it.slotType == MealSlotType.LUNCH
      }
      assertEquals(jollof.recipeId, tuesdayLeftoverLunch.recipeId)
      assertEquals(jollof.recipeId, tuesdayLeftoverLunch.leftoverSourceRecipeId)
      assertEquals(4, tuesdayLeftoverLunch.targetServings)

      // CRITICAL INVARIANT: Monday Dinner is COOKED and Tuesday Lunch has leftoverSourceRecipeId set,
      // so computeWeeklyGroceryDiff MUST reserve 0 extra chicken/rice packages on the Grocery List!
      val diffAfterLeftover = viewModel.uiState.value.weeklyGroceryDiff
      assertFalse(diffAfterLeftover.groceryItems.any { it.ingredientId == "ing_chicken" })
      assertFalse(diffAfterLeftover.groceryItems.any { it.ingredientId == "ing_rice" })
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * Verifies live `HouseholdElephantMemory` distillation across Profile save, Cook Override save,
   * Skip-Reason tagging, and Catalog Pack-Size editing in [KitchenBoardViewModel].
   */
  @Test
  fun liveElephantMemoryDistillation_capturesProfileCuisinesOverridesSkipsAndPackEdits() = runTest {
    val testDispatcher = UnconfinedTestDispatcher(testScheduler)
    Dispatchers.setMain(testDispatcher)
    try {
      val seed = loadCanonicalSeed()
      val storage = FakeKitchenStorage(initialSnapshot = KitchenSnapshot.fromSeedCatalog(seed))
      val viewModel = KitchenBoardViewModel(
        seedCatalogPort = SeedCatalogPort { seed },
        storagePort = storage,
        aiEdgePort = OfflineCookFlowEdgePort(),
        voiceInputPort = OfflineCookFlowVoicePort(),
        clockPort = FixedKitchenClock("2026-11-23", currentHour24 = 12),
      )

      // 1. Save HouseholdProfile with French, Yoruba and West African, and Filipino cuisines.
      val updatedProfile = viewModel.uiState.value.snapshot.householdProfile.copy(
        preferredCuisines = listOf("French", "Yoruba and West African", "Filipino"),
      )
      viewModel.saveHouseholdProfile(updatedProfile, emptySet())

      // 2. Save a permanent family BOM override (EC-05) on Monday's first planned slot.
      val firstSlot = viewModel.uiState.value.snapshot.weeklyBoard.slots.first()
      val firstRecipe = viewModel.uiState.value.snapshot.recipesById.getValue(firstSlot.recipeId)
      val firstBomId = firstRecipe.bomLines.first().ingredientId
      viewModel.confirmCookedOverride(
        CookedDeductionPayload(
          slotId = firstSlot.slotId,
          recipeId = firstRecipe.recipeId,
          targetServings = 4,
          actualUsedQuantities = mapOf(firstBomId to 250.0),
          saveOverrideAsHouseholdDefault = true,
        ),
      )

      // 3. Mark a second planned slot skipped with SlotSkipReason.EATING_OUT.
      val secondSlot = viewModel.uiState.value.snapshot.weeklyBoard.slots.first { it.status == MealSlotStatus.PLANNED }
      viewModel.markSlotSkipWithReason(secondSlot.slotId, SlotSkipReason.EATING_OUT)

      // 4. Edit catalog default package size for Fresh Amaranth Greens -> Fresh Kulitis (250g pack).
      viewModel.onGroceryAndCatalogEdit(
        GroceryAndCatalogEditAction.UpdateCatalogIngredientDetails(
          ingredientId = "ing_amaranth_greens",
          updatedDisplayName = "Fresh Kulitis",
          updatedDefaultPackageQuantity = 250.0,
          updatedPackagePriceUsd = 50.0,
        ),
      )

      val facts = viewModel.uiState.value.snapshot.elephantMemory.semanticFacts
      assertTrue(facts.any { it.summary.contains("French", ignoreCase = true) && it.summary.contains("Filipino", ignoreCase = true) })
      assertTrue(facts.any { it.category == ElephantFactCategory.TASTE_AND_TEXTURE })
      assertTrue(facts.any { it.category == ElephantFactCategory.COOKING_CADENCE })
      assertTrue(facts.any { it.category == ElephantFactCategory.INGREDIENT_ALIAS && it.summary.contains("Fresh Kulitis", ignoreCase = true) })
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * Enforces the global SavorAI v2.0 UI copy rule: **zero slashes (`/`) and zero parentheses (`(` `)` )**
   * across every user-facing enum label and starter menu template title/subtitle.
   */
  @Test
  fun uiCopyPunctuationGuard_containsZeroSlashesAndZeroParenthesesAcrossAllV2EnumsAndMenus() {
    val auditedLabels: List<Pair<String, String>> = buildList {
      TopLevelDestination.entries.forEach {
        add("TopLevelDestination.${it.name}.navLabel" to it.navLabel)
        add("TopLevelDestination.${it.name}.headerSubtitle" to it.headerSubtitle)
      }
      PlanCadence.entries.forEach {
        add("PlanCadence.${it.name}.displayTitle" to it.displayTitle)
        add("PlanCadence.${it.name}.subtitle" to it.subtitle)
      }
      SlotSkipReason.entries.forEach {
        add("SlotSkipReason.${it.name}.badgeLabel" to it.badgeLabel)
      }
      MarketVenue.entries.forEach {
        add("MarketVenue.${it.name}.chipLabel" to it.chipLabel)
      }
      ProportionalStockLevel.entries.forEach {
        add("ProportionalStockLevel.${it.name}.chipLabel" to it.chipLabel)
      }
      CuisineTradition.entries.forEach {
        add("CuisineTradition.${it.name}.canonicalTag" to it.canonicalTag)
        add("CuisineTradition.${it.name}.chipLabel" to it.chipLabel)
      }
      RecipeCreationMode.entries.forEach {
        add("RecipeCreationMode.${it.name}.tabLabel" to it.tabLabel)
      }
      PostCookReactionTag.entries.forEach {
        add("PostCookReactionTag.${it.name}.chipLabel" to it.chipLabel)
      }
      DEFAULT_STARTER_MENU_TEMPLATES.forEach { menu ->
        add("MenuTemplate.${menu.menuId}.title" to menu.title)
        add("MenuTemplate.${menu.menuId}.subtitle" to menu.subtitle)
      }
    }

    val violations = auditedLabels.filter { (_, text) ->
      '/' in text || '(' in text || ')' in text
    }
    assertTrue("Expected 0 slashes or parentheses in UI labels, found: $violations", violations.isEmpty())
  }
}
