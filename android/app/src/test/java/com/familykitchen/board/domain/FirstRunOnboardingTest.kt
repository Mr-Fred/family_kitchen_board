package com.familykitchen.board.domain

import com.familykitchen.board.data.FakeKitchenStorage
import com.familykitchen.board.data.LocalJsonKitchenStorage
import com.familykitchen.board.ports.AiEdgePort
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ports.KitchenSnapshot
import com.familykitchen.board.ports.SeedCatalogPort
import com.familykitchen.board.ports.VoiceInputPort
import com.familykitchen.board.ports.VoiceInputState
import com.familykitchen.board.ui.KitchenBoardViewModel
import com.familykitchen.board.ui.TopLevelDestination
import com.familykitchen.board.ui.onboarding.pruneAllergenConflictingBasics
import java.io.File
import kotlin.io.path.Path
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// Hermetic JVM JUnit 4 suite verifying SavorAI's 4-step First-Run Onboarding models, cold-install
// persistence detection, pantry initialization modes, EC-01/EC-02/EC-08 invariants, and ViewModel O(1) commits.

/**
 * Hermetic JUnit 4 verification suite covering SavorAI's First-Run Onboarding Experience:
 * 1. Brand-new cold-install detection (`hasCompletedOnboarding == false`, `batchWriteCallCount == 0`),
 *    durable pending-onboarding reload on mid-wizard exit, and completed-state preservation on
 *    corrupt-file recovery and `resetToSeed`,
 * 2. Neutralization of seed cook names (`"Fred"` / `"Wife"` -> `"Morning Cook"` / `"Evening Cook"`)
 *    on fresh installs while preserving saved names on replay,
 * 3. Exact two-tier pantry ledger materialization across `CURATED_BASICS`, `EMPTY_KITCHEN`, and
 *    `SAMPLE_DEMO_KITCHEN`,
 * 4. Consumer template serving scaling (`SOLO_COOK` -> `2`, `LARGE_MULTIGEN_FAMILY` -> `6`),
 *    `ChildGrowthStage.BABY_6_12M` (`EC-08`), `HouseholdCurrency` market regions, and allergen-safe
 *    21-slot weekly board auto-drafting (`<= 25m` `EC-01` morning cap),
 * 5. End-to-end `FakeKitchenStorage` + `KitchenBoardViewModel` onboarding completion, replay,
 *    dismissal, and demo-skip `O(1)` batch write invariants (`batchWriteCallCount == 1`).
 */
class FirstRunOnboardingTest {

  /** Temporary directory cleaned up deterministically after each storage test method. */
  @get:Rule
  val tempFolder: TemporaryFolder = TemporaryFolder()

  private val canonicalSeed: SeedCatalogDataset by lazy {
    SchemaValidator.decodeSeedCatalogDatasetJson(readCanonicalSeedCatalogJson())
  }

  /**
   * Verifies that a brand-new cold install on [LocalJsonKitchenStorage] (no prior
   * `kitchen_snapshot_v1.json`) seeds `hasCompletedOnboarding == false` with `0` user batch writes,
   * reloads `hasCompletedOnboarding == false` if the app closes mid-onboarding, and preserves
   * `hasCompletedOnboarding == true` during corrupt-file recovery and `resetToSeed`.
   */
  @Test
  fun localJsonKitchenStorage_coldInstallSeedsPendingOnboardingWhileRecoveryAndResetStayCompleted() = runTest {
    val freshInstallDir = tempFolder.newFolder("fresh_cold_install")
    val coldStorage = LocalJsonKitchenStorage(storageDirectory = freshInstallDir)
    assertFalse(coldStorage.snapshotFile.exists())

    // 1. Brand-new cold install initializes snapshot file with hasCompletedOnboarding = false and 0 writes.
    val coldSnapshot = coldStorage.loadOrInitialize(canonicalSeed)
    assertTrue(coldStorage.snapshotFile.isFile)
    assertFalse(coldSnapshot.hasCompletedOnboarding)
    assertEquals(0, coldStorage.batchWriteCallCount)

    // 2. Closing the app mid-onboarding and restarting reloads hasCompletedOnboarding == false cleanly.
    val restartedMidWizard = LocalJsonKitchenStorage(storageDirectory = freshInstallDir)
    val reloadedPending = restartedMidWizard.loadOrInitialize(canonicalSeed)
    assertFalse(reloadedPending.hasCompletedOnboarding)
    assertEquals(0, restartedMidWizard.batchWriteCallCount)

    // 3. Corrupt existing file (hadExistingFile == true) recovers with hasCompletedOnboarding == true.
    val corruptDir = tempFolder.newFolder("corrupt_existing_install")
    val corruptFile = File(corruptDir, LocalJsonKitchenStorage.SNAPSHOT_FILE_NAME)
    corruptFile.writeText("{corrupt_truncated_household_snapshot", Charsets.UTF_8)
    val recoveredStorage = LocalJsonKitchenStorage(storageDirectory = corruptDir)
    val recoveredSnapshot = recoveredStorage.loadOrInitialize(canonicalSeed)
    assertTrue(recoveredSnapshot.hasCompletedOnboarding)
    assertEquals(0, recoveredStorage.batchWriteCallCount)

    // 4. Explicit resetToSeed restores canonical state with hasCompletedOnboarding == true (+1 write).
    val resetSnapshot = coldStorage.resetToSeed(canonicalSeed)
    assertTrue(resetSnapshot.hasCompletedOnboarding)
    assertEquals(1, coldStorage.batchWriteCallCount)
  }

  /**
   * Verifies that [FirstRunOnboardingDraft.fromHouseholdProfile] neutralizes personal demo cook
   * names (`"Fred"` / `"Wife"`) to `"Morning Cook"` / `"Evening Cook"` when `isFreshInstall = true`,
   * while `isFreshInstall = false` preserves saved household cook names and prunes allergen basics.
   */
  @Test
  fun fromHouseholdProfile_neutralizesDemoCookNamesOnFreshInstallAndPreservesSavedNamesOnReplay() {
    val defaultSeedProfile = HouseholdProfile()
    assertEquals("Fred", defaultSeedProfile.morningLeadName)
    assertEquals("Wife", defaultSeedProfile.eveningLeadName)

    // Fresh cold install replaces personal seed placeholders with neutral consumer labels.
    val freshDraft = FirstRunOnboardingDraft.fromHouseholdProfile(
      profile = defaultSeedProfile,
      isFreshInstall = true,
    )
    assertEquals("Morning Cook", freshDraft.morningLeadName)
    assertEquals("Evening Cook", freshDraft.eveningLeadName)
    assertEquals(StartingPantryMode.CURATED_BASICS, freshDraft.pantryMode)
    assertEquals(DEFAULT_SELECTED_BASIC_INGREDIENT_IDS, freshDraft.selectedBasicIngredientIds)

    // Replay mode on default profile preserves saved "Fred" / "Wife" lead names.
    val replayDefaultDraft = FirstRunOnboardingDraft.fromHouseholdProfile(
      profile = defaultSeedProfile,
      isFreshInstall = false,
    )
    assertEquals("Fred", replayDefaultDraft.morningLeadName)
    assertEquals("Wife", replayDefaultDraft.eveningLeadName)

    // Replay mode on a customized family profile preserves custom names and prunes allergen basics.
    val customSavedProfile = HouseholdProfile(
      householdName = "Santos Family Kitchen",
      morningLeadName = "Marco",
      eveningLeadName = "Elena",
      householdAllergens = listOf("dairy", "eggs"),
    )
    val replayCustomDraft = FirstRunOnboardingDraft.fromHouseholdProfile(
      profile = customSavedProfile,
      isFreshInstall = false,
    )
    assertEquals("Santos Family Kitchen", replayCustomDraft.householdName)
    assertEquals("Marco", replayCustomDraft.morningLeadName)
    assertEquals("Elena", replayCustomDraft.eveningLeadName)
    assertEquals(setOf("DAIRY", "EGGS"), replayCustomDraft.selectedAllergens)
    assertFalse("ing_egg" in replayCustomDraft.selectedBasicIngredientIds)
    assertFalse("ing_milk" in replayCustomDraft.selectedBasicIngredientIds)

    // UI helper pruneAllergenConflictingBasics strips EGGS, DAIRY, and GLUTEN staples deterministically.
    val allSixteenIds = DEFAULT_ONBOARDING_STAPLE_PRESETS.map { it.ingredientId }.toSet()
    val pruned = pruneAllergenConflictingBasics(allSixteenIds, setOf("EGGS", "DAIRY", "GLUTEN"))
    assertFalse("ing_egg" in pruned)
    assertFalse("ing_milk" in pruned)
    assertFalse("ing_butter" in pruned)
    assertFalse("ing_pasta" in pruned)
    assertTrue("ing_rice" in pruned)
    assertTrue("ing_olive_oil" in pruned)
  }

  /**
   * Verifies [computeFirstRunOnboardingOutcome] across all three [StartingPantryMode] options:
   * `CURATED_BASICS`, `EMPTY_KITCHEN`, and `SAMPLE_DEMO_KITCHEN`.
   */
  @Test
  fun computeFirstRunOnboardingOutcome_enforcesCuratedBasicsEmptyKitchenAndSampleDemoModes() {
    val baseSnapshot = KitchenSnapshot.fromSeedCatalog(canonicalSeed, hasCompletedOnboarding = false)
    val stampIso = "2026-11-21T08:00:00Z"

    // 1. CURATED_BASICS stocks selected non-allergen basics at defaultPackageQuantity / PLENTY
    // and zeroes unselected or allergen-conflicting items with rescuedExpiringMealsCount == 0.
    val chosenBasics = setOf("ing_rice", "ing_olive_oil", "ing_chicken", "ing_banana", "ing_milk")
    val curatedOutcome = computeFirstRunOnboardingOutcome(
      catalog = canonicalSeed.ingredients,
      recipes = canonicalSeed.recipes,
      seedPantry = canonicalSeed.initialPantry,
      currentBoard = baseSnapshot.weeklyBoard,
      draft = FirstRunOnboardingDraft(
        selectedAllergens = setOf("DAIRY"),
        pantryMode = StartingPantryMode.CURATED_BASICS,
        selectedBasicIngredientIds = chosenBasics,
      ),
      nowIso = stampIso,
    )
    assertEquals(44, curatedOutcome.pantryEntries.size)
    assertEquals(0, curatedOutcome.rescuedExpiringMealsCount)

    val expectedStockedIds = setOf("ing_rice", "ing_olive_oil", "ing_chicken", "ing_banana")
    val catalogById = indexCatalogById(canonicalSeed.ingredients)
    for (entry in curatedOutcome.pantryEntries) {
      val catalogItem = requireNotNull(catalogById[entry.ingredientId])
      when {
        entry.ingredientId in expectedStockedIds -> {
          assertEquals(catalogItem.defaultPackageQuantity, entry.currentQuantity, 0.001)
          assertEquals(StapleStatus.PLENTY, entry.stapleStatus)
          assertFalse(entry.isOutOfStock)
          assertEquals(catalogItem.defaultShelfLifeDays, entry.expiresInDays)
          assertEquals(stampIso, entry.updatedAtIso)
        }
        else -> {
          assertEquals(0.0, entry.currentQuantity, 0.001)
          assertEquals(StapleStatus.OUT, entry.stapleStatus)
          assertTrue(entry.isOutOfStock)
          assertNull(entry.expiresInDays)
        }
      }
    }

    // 2. EMPTY_KITCHEN zeroes all 38 Tier-1 quantitative and 6 Tier-2 staple items cleanly.
    val emptyOutcome = computeFirstRunOnboardingOutcome(
      catalog = canonicalSeed.ingredients,
      recipes = canonicalSeed.recipes,
      seedPantry = canonicalSeed.initialPantry,
      currentBoard = baseSnapshot.weeklyBoard,
      draft = FirstRunOnboardingDraft(pantryMode = StartingPantryMode.EMPTY_KITCHEN),
      nowIso = stampIso,
    )
    assertEquals(44, emptyOutcome.pantryEntries.size)
    assertEquals(0, emptyOutcome.rescuedExpiringMealsCount)
    assertEquals(38, emptyOutcome.pantryEntries.count { it.tier == IngredientTier.TIER_1_QUANTITATIVE })
    assertEquals(6, emptyOutcome.pantryEntries.count { it.tier == IngredientTier.TIER_2_STAPLE })
    assertTrue(
      emptyOutcome.pantryEntries.all { entry ->
        entry.currentQuantity == 0.0 && entry.stapleStatus == StapleStatus.OUT && entry.isOutOfStock
      },
    )

    // 3. SAMPLE_DEMO_KITCHEN restores the full 44-item seed pantry and rescuedExpiringMealsCount == 12.
    val demoOutcome = computeFirstRunOnboardingOutcome(
      catalog = canonicalSeed.ingredients,
      recipes = canonicalSeed.recipes,
      seedPantry = canonicalSeed.initialPantry,
      currentBoard = baseSnapshot.weeklyBoard,
      draft = FirstRunOnboardingDraft(pantryMode = StartingPantryMode.SAMPLE_DEMO_KITCHEN),
      nowIso = stampIso,
    )
    assertEquals(44, demoOutcome.pantryEntries.size)
    assertEquals(canonicalSeed.initialPantry, demoOutcome.pantryEntries)
    assertEquals(12, demoOutcome.rescuedExpiringMealsCount)
  }

  /**
   * Verifies template serving scaling (`SOLO_COOK` -> `2`, `LARGE_MULTIGEN_FAMILY` -> `6`),
   * `ChildGrowthStage.BABY_6_12M` (`EC-08`), [HouseholdCurrency] + [defaultMarketRegionForCurrency],
   * and `EGGS` + `DAIRY` + `PEANUTS` allergen filtering across basics and all 21 `<= 25m` board slots.
   */
  @Test
  fun computeFirstRunOnboardingOutcome_scalesTemplatesAdaptsBabySafetyCurrencyAndAllergenBoard() {
    val baseBoard = KitchenSnapshot.fromSeedCatalog(canonicalSeed).weeklyBoard

    // 1. SOLO_COOK scales all 21 drafted slots to 2 servings and syncs blank eveningLeadName.
    val soloOutcome = computeFirstRunOnboardingOutcome(
      catalog = canonicalSeed.ingredients,
      recipes = canonicalSeed.recipes,
      seedPantry = canonicalSeed.initialPantry,
      currentBoard = baseBoard,
      draft = FirstRunOnboardingDraft(
        template = ConsumerHouseholdTemplate.SOLO_COOK,
        morningLeadName = "Alex",
        eveningLeadName = "   ",
      ),
    )
    assertEquals(2, soloOutcome.householdProfile.effectiveTargetServings)
    assertEquals("Alex", soloOutcome.householdProfile.eveningLeadName)
    assertEquals(21, soloOutcome.weeklyBoard.slots.size)
    assertTrue(soloOutcome.weeklyBoard.slots.all { it.targetServings == 2 })

    // 2. LARGE_MULTIGEN_FAMILY scales all 21 drafted slots to 6 servings.
    val multiGenOutcome = computeFirstRunOnboardingOutcome(
      catalog = canonicalSeed.ingredients,
      recipes = canonicalSeed.recipes,
      seedPantry = canonicalSeed.initialPantry,
      currentBoard = baseBoard,
      draft = FirstRunOnboardingDraft(template = ConsumerHouseholdTemplate.LARGE_MULTIGEN_FAMILY),
    )
    assertEquals(6, multiGenOutcome.householdProfile.effectiveTargetServings)
    assertEquals(21, multiGenOutcome.weeklyBoard.slots.size)
    assertTrue(multiGenOutcome.weeklyBoard.slots.all { it.targetServings == 6 })

    // 3. BABY_6_12M (EC-08), HouseholdCurrency + defaultMarketRegionForCurrency, and EGGS+DAIRY+PEANUTS.
    assertEquals("Antipolo & Marikina, PH", defaultMarketRegionForCurrency(HouseholdCurrency.PHP))
    assertEquals("Local Neighborhood Market, US", defaultMarketRegionForCurrency(HouseholdCurrency.USD))
    assertEquals("Marché & Local Grocer, EU", defaultMarketRegionForCurrency(HouseholdCurrency.EUR))
    assertEquals("High Street & Local Grocer, UK", defaultMarketRegionForCurrency(HouseholdCurrency.GBP))
    assertEquals("Cotonou & Neighborhood Market", defaultMarketRegionForCurrency(HouseholdCurrency.XOF))

    val blockedAllergens = setOf("EGGS", "DAIRY", "PEANUTS")
    val babyAllergenOutcome = computeFirstRunOnboardingOutcome(
      catalog = canonicalSeed.ingredients,
      recipes = canonicalSeed.recipes,
      seedPantry = canonicalSeed.initialPantry,
      currentBoard = baseBoard,
      draft = FirstRunOnboardingDraft(
        householdName = "Cotonou Baby Kitchen",
        template = ConsumerHouseholdTemplate.FAMILY_TWO_KIDS,
        olderKidAgeYears = 10,
        youngestStage = ChildGrowthStage.BABY_6_12M,
        selectedAllergens = blockedAllergens,
        currency = HouseholdCurrency.XOF,
        marketRegionLabel = "",
        pantryMode = StartingPantryMode.CURATED_BASICS,
        selectedBasicIngredientIds = DEFAULT_SELECTED_BASIC_INGREDIENT_IDS + "ing_peanut_butter",
      ),
    )

    val profile = babyAllergenOutcome.householdProfile
    assertEquals(ChildGrowthStage.BABY_6_12M, profile.youngestStage)
    assertEquals(HouseholdCurrency.XOF, profile.currency)
    assertEquals("Cotonou & Neighborhood Market", profile.marketRegionLabel)
    val olderMember = requireNotNull(profile.members.find { it.memberId == "OLDER_KID" })
    assertEquals("10yo School Kid", olderMember.memberName)
    val youngestMember = requireNotNull(profile.members.find { it.memberId == "YOUNGEST" })
    assertEquals(ChildGrowthStage.BABY_6_12M, youngestMember.growthStage)
    assertEquals("Baby (6-12m)", youngestMember.memberName)

    // Conflicting basics (egg, milk, peanut butter) must remain unstocked at 0.0 / OUT.
    val pantryById = indexPantryById(babyAllergenOutcome.pantryEntries)
    for (blockedId in listOf("ing_egg", "ing_milk", "ing_peanut_butter")) {
      val blockedEntry = requireNotNull(pantryById[blockedId])
      assertEquals(0.0, blockedEntry.currentQuantity, 0.001)
      assertEquals(StapleStatus.OUT, blockedEntry.stapleStatus)
      assertTrue(blockedEntry.isOutOfStock)
    }

    // All 21 drafted slots must be allergen-safe, <= 25m on BREAKFAST (EC-01), and emit BABY_6_12M tips (EC-08).
    assertEquals(21, babyAllergenOutcome.weeklyBoard.slots.size)
    val recipesById = canonicalSeed.recipes.associateBy { it.recipeId }
    for (slot in babyAllergenOutcome.weeklyBoard.slots) {
      val recipe = requireNotNull(recipesById[slot.recipeId])
      val availability = AvailabilityFilter.evaluateRecipeAvailability(
        recipe = recipe,
        catalog = canonicalSeed.ingredients,
        householdAllergens = profile.householdAllergens,
        pantry = babyAllergenOutcome.pantryEntries,
      )
      assertTrue(availability.isSchedulable)
      if (slot.slotType == MealSlotType.BREAKFAST) {
        assertTrue(MenuPlannerEngine.computeCalibratedMorningMinutes(recipe) <= MAX_WEEKDAY_BREAKFAST_MINUTES)
      }
      val resolvedCard = MenuPlannerEngine.buildResolvedMealCard(
        slot = slot,
        recipe = recipe,
        catalog = canonicalSeed.ingredients,
        householdProfile = profile,
      )
      assertNotNull(resolvedCard.stageAdaptedSafetyTip)
      assertTrue(requireNotNull(resolvedCard.stageAdaptedSafetyTip).startsWith("For 6-12m Baby:"))
    }
  }

  /**
   * Verifies the end-to-end onboarding lifecycle across [FakeKitchenStorage] and
   * [KitchenBoardViewModel]: cold load (`isOnboardingActive == true`), `completeFirstRunOnboarding`
   * (`batchWriteCallCount == 1`, `activeDestination == TopLevelDestination.PLAN`), replay + dismiss
   * (`0` extra writes), and `skipFirstRunOnboardingWithDemo()` (`batchWriteCallCount == 1`).
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun kitchenBoardViewModel_completesReplaysDismissesAndSkipsOnboardingInSingleO1Writes() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    try {
      // 1. Cold install load surfaces active onboarding with 0 storage writes.
      val coldStorage = FakeKitchenStorage(simulateFreshInstall = true)
      val viewModel = KitchenBoardViewModel(
        seedCatalogPort = SeedCatalogPort { canonicalSeed },
        storagePort = coldStorage,
        aiEdgePort = StubOnboardingAiEdgePort(),
        voiceInputPort = SilentOnboardingVoicePort(),
        deleteTempImage = {},
      )
      assertFalse(viewModel.uiState.value.isLoading)
      assertFalse(viewModel.uiState.value.snapshot.hasCompletedOnboarding)
      assertTrue(viewModel.uiState.value.isOnboardingActive)
      assertEquals(0, coldStorage.batchWriteCallCount)

      // 2. Completing onboarding commits in 1 O(1) batch write and lands on TopLevelDestination.PLAN.
      val draft = FirstRunOnboardingDraft(
        householdName = "Marikina Family Table",
        template = ConsumerHouseholdTemplate.FAMILY_TWO_KIDS,
        morningLeadName = "Marco",
        eveningLeadName = "Clara",
        pantryMode = StartingPantryMode.CURATED_BASICS,
      )
      viewModel.completeFirstRunOnboarding(draft)
      assertEquals(1, coldStorage.batchWriteCallCount)
      assertEquals(1, viewModel.uiState.value.batchWriteCallCount)
      assertTrue(viewModel.uiState.value.snapshot.hasCompletedOnboarding)
      assertFalse(viewModel.uiState.value.isOnboardingActive)
      assertEquals(TopLevelDestination.PLAN, viewModel.uiState.value.activeDestination)
      assertEquals("Marikina Family Table", viewModel.uiState.value.snapshot.householdProfile.householdName)

      // 3. Relaunching onboarding closes open sheets and activates replay with 0 extra storage writes.
      viewModel.openFamilyProfileSheet()
      assertTrue(viewModel.uiState.value.isFamilyProfileSheetOpen)
      viewModel.relaunchFirstRunOnboarding()
      assertFalse(viewModel.uiState.value.isFamilyProfileSheetOpen)
      assertTrue(viewModel.uiState.value.isOnboardingReplayOpen)
      assertTrue(viewModel.uiState.value.isOnboardingActive)
      assertEquals(1, coldStorage.batchWriteCallCount)

      // Dismissing replay returns cleanly to the kitchen with 0 extra storage writes.
      viewModel.dismissOnboardingReplay()
      assertFalse(viewModel.uiState.value.isOnboardingReplayOpen)
      assertFalse(viewModel.uiState.value.isOnboardingActive)
      assertEquals(1, coldStorage.batchWriteCallCount)

      // 4. Skipping onboarding on a fresh install stocks the 44-item demo kitchen in 1 O(1) write.
      val skipStorage = FakeKitchenStorage(simulateFreshInstall = true)
      val skipViewModel = KitchenBoardViewModel(
        seedCatalogPort = SeedCatalogPort { canonicalSeed },
        storagePort = skipStorage,
        aiEdgePort = StubOnboardingAiEdgePort(),
        voiceInputPort = SilentOnboardingVoicePort(),
        deleteTempImage = {},
      )
      assertTrue(skipViewModel.uiState.value.isOnboardingActive)
      assertEquals(0, skipStorage.batchWriteCallCount)

      skipViewModel.skipFirstRunOnboardingWithDemo()
      assertEquals(1, skipStorage.batchWriteCallCount)
      assertTrue(skipViewModel.uiState.value.snapshot.hasCompletedOnboarding)
      assertFalse(skipViewModel.uiState.value.isOnboardingActive)
      assertEquals(44, skipViewModel.uiState.value.snapshot.pantryEntries.size)
      assertEquals(canonicalSeed.initialPantry, skipViewModel.uiState.value.snapshot.pantryEntries)
      assertEquals(12, skipViewModel.uiState.value.snapshot.rescuedExpiringMealsCount)
    } finally {
      Dispatchers.resetMain()
    }
  }

  private fun readCanonicalSeedCatalogJson(): String {
    // Support running JUnit from android/app, android/, or repository root working directories.
    val candidates = listOf(
      Path("src/main/assets/seed_catalog.json"),
      Path("app/src/main/assets/seed_catalog.json"),
      Path("android/app/src/main/assets/seed_catalog.json"),
    )
    val matched = requireNotNull(candidates.firstOrNull { it.isRegularFile() }) {
      "Unable to locate canonical seed_catalog.json asset"
    }
    return matched.readText()
  }

  // Hermetic no-op AiEdgePort fake keeping ViewModel unit tests free of JNI or socket side effects.
  private class StubOnboardingAiEdgePort : AiEdgePort {
    private val state = MutableStateFlow<AiRuntimeState>(AiRuntimeState.Uninitialized)
    override val runtimeState: StateFlow<AiRuntimeState> = state.asStateFlow()

    override suspend fun autoDiscoverAndInitialize(): AiRuntimeState = state.value

    override suspend fun importAndLoadModel(sourceUriString: String, displayName: String?): AiRuntimeState =
      state.value

    override suspend fun generateText(prompt: String): String = "{}"

    override suspend fun analyzeImageWithPrompt(preprocessedImagePath: String, prompt: String): String =
      throw VisionNotSupportedException("Stub port does not enable vision.")

    override fun close() = Unit
  }

  // Hermetic no-op VoiceInputPort fake avoiding Android SpeechRecognizer calls in JVM unit tests.
  private class SilentOnboardingVoicePort : VoiceInputPort {
    private val state = MutableStateFlow<VoiceInputState>(VoiceInputState.Idle)
    override val voiceState: StateFlow<VoiceInputState> = state.asStateFlow()

    override fun startListening(languageTag: String): Boolean = false

    override fun stopListening() = Unit

    override fun release() = Unit
  }
}
