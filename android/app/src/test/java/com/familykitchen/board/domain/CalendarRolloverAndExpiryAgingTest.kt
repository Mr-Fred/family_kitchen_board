package com.familykitchen.board.domain

import com.familykitchen.board.data.FakeKitchenStorage
import com.familykitchen.board.ports.AiEdgePort
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ports.FixedKitchenClock
import com.familykitchen.board.ports.KitchenClockPort
import com.familykitchen.board.ports.KitchenClockSnapshot
import com.familykitchen.board.ports.KitchenSnapshot
import com.familykitchen.board.ports.SeedCatalogPort
import com.familykitchen.board.ports.VoiceInputPort
import com.familykitchen.board.ports.VoiceInputState
import com.familykitchen.board.ui.KitchenBoardViewModel
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
import org.junit.Assert.fail
import org.junit.Test

// Suite 15: Hermetic JVM JUnit 4 verification of civil-date calendar math, FixedKitchenClock
// ViewModel Today + 8:30 PM sync, EMPTY_KITCHEN Blank Week onboarding, WeeklyRolloverEngine
// carry-over/pinning/cadence/ElephantMemory, moveOrSwapMealSlot EC-01 guard, and live expiry aging.

/**
 * Suite 15 hermetic JUnit 4 verification suite covering real calendar grounding, weekly board
 * rollover, and live pantry shelf-life aging:
 * 1. Pure Howard Hinnant civil-date math (`Mon 23`..`Sun 29`, leap-year Feb 29 vs non-leap Mar 1,
 *    cross-month week range spans, and Monday anchoring with zero `java.time` in `domain/`),
 * 2. `FixedKitchenClock` synchronization in [KitchenBoardViewModel] (`realTodayIso`,
 *    `todayDayIndex`, `activeDayIndex`, `8:30 PM` `currentSimulatedHour24 >= 20` night-prep banner,
 *    and live `refreshWallClock()` transitions),
 * 3. `StartingPantryMode.EMPTY_KITCHEN` producing a calm `0`-slot `Blank Week` (`PlanCadence.BLANK_WEEK`),
 * 4. [startOrRefreshWeeklyBoard] carrying forward uncooked `PLANNED` meals, retaining `isPinned`
 *    anchor slots, honoring `PlanCadence`, and distilling a `WeeklyEpisodicRollup` into
 *    `HouseholdElephantMemory` (`batchWriteCallCount == 1`),
 * 5. [moveOrSwapMealSlot] enforcing `EC-01` (`<= 25m` calibrated morning cap on `BREAKFAST`),
 * 6. Live `expiryDateIso` aging, `90d`+ Freezer shelf-life extensions, FIFO urgent `<= 2d` restock
 *    protection, and zero `"0 g · 3 days left"` mirages (`outOfStockItems` separated from active shelves).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarRolloverAndExpiryAgingTest {

  // Mutable clock test double enabling deterministic wall-clock advancement on Activity resume.
  private class MutableClockDouble(initial: KitchenClockSnapshot) : KitchenClockPort {
    var current: KitchenClockSnapshot = initial
    override fun now(): KitchenClockSnapshot = current
  }

  // Minimal offline AiEdgePort stub isolating ViewModel calendar and storage tests from JNI.
  private class IdleEdgePort : AiEdgePort {
    private val mutableState = MutableStateFlow<AiRuntimeState>(AiRuntimeState.Uninitialized)
    override val runtimeState: StateFlow<AiRuntimeState> = mutableState.asStateFlow()
    override suspend fun autoDiscoverAndInitialize(): AiRuntimeState = mutableState.value
    override suspend fun importAndLoadModel(sourceUriString: String, displayName: String?): AiRuntimeState = mutableState.value
    override suspend fun generateText(prompt: String): String = "{}"
    override suspend fun analyzeImageWithPrompt(preprocessedImagePath: String, prompt: String): String = "{}"
    override fun close() = Unit
  }

  // Minimal offline VoiceInputPort stub with zero Android SpeechRecognizer dependencies.
  private class IdleVoicePort : VoiceInputPort {
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

  // Decode the canonical 44-ingredient, 30-seed-recipe dataset once per test method.
  private fun loadCanonicalSeed(): SeedCatalogDataset =
    SchemaValidator.decodeSeedCatalogDatasetJson(resolveRepoFile("data/seed_catalog.json").readText())

  /**
   * Verifies proleptic Gregorian civil-date math (`Mon 23`..`Sun 29`, cross-month spans, leap years
   * `2024-02-29` vs non-leap `2026-02-28 + 1 == 2026-03-01`, and Monday week anchoring).
   */
  @Test
  fun civilDateMath_formatsWeekdayChipsMonthBoundariesAndLeapYearsAccurately() {
    // Verify all 7 weekday chip labels for the week of Monday 2026-11-23..Sunday 2026-11-29.
    val expectedChips = listOf("Mon 23", "Tue 24", "Wed 25", "Thu 26", "Fri 27", "Sat 28", "Sun 29")
    val actualChips = (0..6).map { dayIdx -> formatDayChipDateLabel("2026-11-23", dayIdx) }
    assertEquals(expectedChips, actualChips)
    assertTrue(actualChips.all { '/' !in it && '(' !in it && ')' !in it })

    // Verify week range labels within a single month and across an October-to-November boundary.
    assertEquals("Nov 23 – Nov 29", formatWeekRangeLabel("2026-11-23"))
    assertEquals("Oct 26 – Nov 1", formatWeekRangeLabel("2026-10-26"))

    // Verify leap-year February 29 handling in 2024 (leap) vs 2026 (non-leap) and 2000 (century leap).
    assertEquals("2024-02-29", addDaysIso("2024-02-28", 1))
    assertEquals("2024-03-01", addDaysIso("2024-02-28", 2))
    assertEquals("2026-03-01", addDaysIso("2026-02-28", 1))
    assertNotNull(isoDateToEpochDay("2024-02-29"))
    assertNotNull(isoDateToEpochDay("2000-02-29"))
    assertNull(isoDateToEpochDay("2026-02-29"))
    assertNull(isoDateToEpochDay("1900-02-29"))

    // Verify Monday anchor resolution across every day of the 2026-11-23..2026-11-29 week.
    for (offset in 0..6) {
      val dateIso = addDaysIso("2026-11-23", offset)
      assertEquals(offset, dayOfWeekIndexZeroMonday(dateIso))
      assertEquals("2026-11-23", mondayOfIsoDate(dateIso))
    }
  }

  /**
   * Verifies that injecting [FixedKitchenClock] into [KitchenBoardViewModel] synchronizes `Today`
   * (`todayDayIndex`, `activeDayIndex`), surfaces `8:30 PM` night-before prep (`hour >= 20`), and
   * updates live on [KitchenBoardViewModel.refreshWallClock].
   */
  @Test
  fun fixedKitchenClock_syncsTodayEveningPrepBannerAndMidnightRefreshInViewModel() = runTest {
    val testDispatcher = UnconfinedTestDispatcher(testScheduler)
    Dispatchers.setMain(testDispatcher)
    try {
      val seed = loadCanonicalSeed()
      val storage = FakeKitchenStorage(weekStartDateIso = "2026-11-23")
      val wednesdayEveningClock = FixedKitchenClock(todayIso = "2026-11-25", currentHour24 = 20).now()
      val mutableClock = MutableClockDouble(wednesdayEveningClock)

      // Schedule Overnight Oats (which has nightBeforePrepSteps) onto Thursday Breakfast (dayIndex = 3).
      val viewModel = KitchenBoardViewModel(
        seedCatalogPort = SeedCatalogPort { seed },
        storagePort = storage,
        aiEdgePort = IdleEdgePort(),
        voiceInputPort = IdleVoicePort(),
        clockPort = mutableClock,
      )
      val overnightOats = seed.recipes.first { it.recipeId == "rec_09_overnight_oats" }
      viewModel.scheduleRecipeToPlan(overnightOats, dayIndex = 3, slotType = MealSlotType.BREAKFAST)

      // Confirm Wednesday (index 2) at 20:00 (8 PM) highlights Today and exposes tomorrow's night prep.
      val eveningState = viewModel.uiState.value
      assertEquals("2026-11-25", eveningState.realTodayIso)
      assertEquals(2, eveningState.todayDayIndex)
      assertEquals(2, eveningState.activeDayIndex)
      assertEquals(20, eveningState.currentSimulatedHour24)
      assertEquals("rec_09_overnight_oats", eveningState.tomorrowBreakfastRecipe?.recipeId)

      // Advance wall clock past midnight to Thursday 2026-11-26 at 07:00 AM and invoke refreshWallClock.
      mutableClock.current = FixedKitchenClock(todayIso = "2026-11-26", currentHour24 = 7).now()
      viewModel.refreshWallClock()

      val morningState = viewModel.uiState.value
      assertEquals("2026-11-26", morningState.realTodayIso)
      assertEquals(3, morningState.todayDayIndex)
      assertEquals(7, morningState.currentSimulatedHour24)
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * Verifies that `StartingPantryMode.EMPTY_KITCHEN` produces a calm `0`-slot `Blank Week`
   * (`PlanCadence.BLANK_WEEK`) with all `44` pantry entries at `0.0` / `OUT` and `1` `O(1)` write.
   */
  @Test
  fun emptyKitchenOnboarding_createsZeroSlotBlankWeekAndSeparatesOutOfStockFromFridge() = runTest {
    val testDispatcher = UnconfinedTestDispatcher(testScheduler)
    Dispatchers.setMain(testDispatcher)
    try {
      val seed = loadCanonicalSeed()
      val storage = FakeKitchenStorage(simulateFreshInstall = true)
      val viewModel = KitchenBoardViewModel(
        seedCatalogPort = SeedCatalogPort { seed },
        storagePort = storage,
        aiEdgePort = IdleEdgePort(),
        voiceInputPort = IdleVoicePort(),
        clockPort = FixedKitchenClock("2026-11-23", currentHour24 = 9),
      )
      storage.resetWriteCounter()

      // Complete onboarding with StartingPantryMode.EMPTY_KITCHEN and verify 0 pre-filled meals.
      viewModel.completeFirstRunOnboarding(
        FirstRunOnboardingDraft(pantryMode = StartingPantryMode.EMPTY_KITCHEN),
      )
      assertEquals(1, storage.batchWriteCallCount)
      val committedState = viewModel.uiState.value
      assertTrue(committedState.snapshot.weeklyBoard.slots.isEmpty())
      assertEquals(PlanCadence.BLANK_WEEK, committedState.snapshot.planCadence)
      assertEquals(0, committedState.weeklyGroceryDiff.groceryItems.size)

      // Confirm 0 "0 g · 3 days left" mirages: active shelves are empty and all 44 items sit in outOfStockItems.
      val dashboard = committedState.pantryExpiryDashboard
      assertEquals(0, dashboard.totalItems)
      assertTrue(dashboard.fridgeItems.isEmpty())
      assertTrue(dashboard.dryPantryItems.isEmpty())
      assertTrue(dashboard.freezerItems.isEmpty())
      assertEquals(44, dashboard.outOfStockItems.size)
      assertTrue(dashboard.outOfStockItems.all { computeRemainingShelfLifeDays(it, "2026-11-23") == null })
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * Verifies [startOrRefreshWeeklyBoard] and [KitchenBoardViewModel.confirmStartNewWeek] across
   * pinned-slot retention, uncooked `PLANNED` carry-over, [PlanCadence] slot counts, and
   * `WeeklyEpisodicRollup` distillation in `1` `O(1)` atomic batch write.
   */
  @Test
  fun weeklyRolloverAndMoveOrSwap_retainsPinsCarriesUncookedDistillsMemoryAndEnforcesEc01() = runTest {
    val testDispatcher = UnconfinedTestDispatcher(testScheduler)
    Dispatchers.setMain(testDispatcher)
    try {
      val seed = loadCanonicalSeed()
      val allRecipes = mergeHeritageRecipesIntoLibrary(seed.recipes)
      val profile = HouseholdProfile()

      // Construct an outgoing week with 1 COOKED meal, 1 SKIPPED meal, 1 PINNED anchor, and 1 uncooked PLANNED dinner.
      val outgoingBoard = WeeklyBoard(
        boardId = "board_2026_11_16",
        householdId = profile.householdId,
        weekStartDateIso = "2026-11-16",
        slots = listOf(
          MealSlot("s_mon_b", 0, MealSlotType.BREAKFAST, CookOwner.FRED, "rec_40_omelette_fines_herbes_tartine", status = MealSlotStatus.COOKED),
          MealSlot("s_tue_l", 1, MealSlotType.LUNCH, CookOwner.WIFE, "rec_02_crispy_chicken_tomato_baguette", status = MealSlotStatus.COMPLETED_SKIPPED, skipReason = SlotSkipReason.EATING_OUT),
          MealSlot("s_fri_d", 4, MealSlotType.DINNER, CookOwner.WIFE, "rec_33_party_jollof_braised_chicken", status = MealSlotStatus.PLANNED, isPinned = true),
          MealSlot("s_sat_d", 5, MealSlotType.DINNER, CookOwner.WIFE, "rec_37_poulet_basquaise", status = MealSlotStatus.PLANNED, isPinned = false),
        ),
      )

      // Roll forward to Monday 2026-11-23 under BREAKFAST_AND_DINNER cadence (5 weekday breakfasts + 7 dinners = 12 slots).
      val rollover = startOrRefreshWeeklyBoard(
        previousBoard = outgoingBoard,
        newWeekStartDateIso = "2026-11-23",
        cadence = PlanCadence.BREAKFAST_AND_DINNER,
        carryOverUncooked = true,
        recipes = allRecipes,
        catalog = seed.ingredients,
        pantryEntries = seed.initialPantry,
        householdProfile = profile,
        elephantMemory = HouseholdElephantMemory(),
        todayIso = "2026-11-23",
        nowIso = "2026-11-23T08:00:00Z",
      )

      assertEquals(12, rollover.nextBoard.slots.size)
      assertEquals(1, rollover.pinnedRetainedCount)
      assertEquals(1, rollover.carriedOverCount)
      // Friday Dinner stays pinned to Party Jollof, and uncooked Poulet Basquaise carries into Monday Dinner.
      val fridayDinner = rollover.nextBoard.slots.first { it.dayIndex == 4 && it.slotType == MealSlotType.DINNER }
      assertEquals("rec_33_party_jollof_braised_chicken", fridayDinner.recipeId)
      assertTrue(fridayDinner.isPinned)
      assertTrue(rollover.nextBoard.slots.any { it.recipeId == "rec_37_poulet_basquaise" })

      // Verify WeeklyEpisodicRollup recorded 1 cooked meal and 1 skipped slot into ElephantMemory.
      val digest = rollover.updatedElephantMemory.episodicDigests.single()
      assertEquals("2026-11-16", digest.weekStartDateIso)
      assertEquals(1, digest.cookedMealsCount)
      assertEquals(1, digest.skippedSlotsCount)

      // Verify DINNERS_ONLY (7 slots) and BLANK_WEEK (0 slots) cadences via ViewModel in 1 O(1) write.
      val storage = FakeKitchenStorage(
        initialSnapshot = KitchenSnapshot.fromSeedCatalog(seed).copy(weeklyBoard = outgoingBoard),
      )
      val viewModel = KitchenBoardViewModel(
        seedCatalogPort = SeedCatalogPort { seed },
        storagePort = storage,
        aiEdgePort = IdleEdgePort(),
        voiceInputPort = IdleVoicePort(),
        clockPort = FixedKitchenClock("2026-11-23", currentHour24 = 8),
      )
      storage.resetWriteCounter()
      viewModel.confirmStartNewWeek(cadence = PlanCadence.DINNERS_ONLY, carryOverUncooked = true)
      assertEquals(1, storage.batchWriteCallCount)
      assertEquals(7, viewModel.uiState.value.snapshot.weeklyBoard.slots.size)
      assertTrue(viewModel.uiState.value.snapshot.elephantMemory.episodicDigests.isNotEmpty())

      // Verify moveOrSwapMealSlot EC-01 guard: moving > 25m Party Jollof onto Monday BREAKFAST throws ValidationException.
      val recipesById = allRecipes.associateBy { it.recipeId }
      val jollofSlot = viewModel.uiState.value.snapshot.weeklyBoard.slots.first {
        it.recipeId == "rec_33_party_jollof_braised_chicken"
      }
      try {
        moveOrSwapMealSlot(
          board = viewModel.uiState.value.snapshot.weeklyBoard,
          sourceSlotId = jollofSlot.slotId,
          targetDayIndex = 0,
          targetSlotType = MealSlotType.BREAKFAST,
          recipesById = recipesById,
        )
        fail("Expected ValidationException when moving > 25m dinner onto weekday BREAKFAST.")
      } catch (expected: ValidationException) {
        assertTrue(expected.message.orEmpty().contains("Breakfast cap"))
      }
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * Verifies live `expiryDateIso` calendar aging, `>= 90d` Freezer shelf-life extensions on
   * [movePantryItemToZone], and the FIFO urgent `<= 2d` restock guard in [batchRestockPantry].
   */
  @Test
  fun liveExpiryDateAging_freezerRescueAndFifoUrgentRestockGuardProtectPerishables() {
    val seed = loadCanonicalSeed()
    val allRecipes = mergeHeritageRecipesIntoLibrary(seed.recipes)

    // Restock fresh salmon on Monday 2026-11-23; verify it ages from Fresh -> Expiring Soon -> Expired across days.
    val depletedSalmon = seed.initialPantry.map { entry ->
      if (entry.ingredientId == "ing_salmon") {
        entry.copy(currentQuantity = 0.0, isOutOfStock = true, stapleStatus = StapleStatus.OUT, expiresInDays = null)
      } else {
        entry
      }
    }
    val restockedMon = batchRestockPantry(
      pantry = depletedSalmon,
      catalog = seed.ingredients,
      deltas = listOf(RestockDelta("ing_salmon", 500.0)),
      nowIso = "2026-11-23T10:00:00Z",
      todayIso = "2026-11-23",
    )
    val salmonMon = restockedMon.first { it.ingredientId == "ing_salmon" }
    val defaultSalmonDays = seed.ingredients.first { it.ingredientId == "ing_salmon" }.defaultShelfLifeDays
    assertEquals(addDaysIso("2026-11-23", defaultSalmonDays), salmonMon.expiryDateIso)

    // Advance calendar to 1 day before salmon's expiryDateIso: remaining shelf life must drop to 1 day.
    val oneDayBeforeExpiry = addDaysIso(salmonMon.expiryDateIso.orEmpty(), -1)
    assertEquals(1, computeRemainingShelfLifeDays(salmonMon, oneDayBeforeExpiry))
    val agedDashboard = evaluatePantryExpiryDashboard(
      pantry = restockedMon,
      catalog = seed.ingredients,
      recipes = allRecipes,
      todayIso = oneDayBeforeExpiry,
    )
    assertTrue(agedDashboard.expiringItems.any { it.entry.ingredientId == "ing_salmon" && it.expiresInDays == 1 })

    // FIFO Urgent Guard: restocking +500g more salmon on oneDayBeforeExpiry must NOT overwrite the urgent 1d stock!
    val toppedUpSalmon = batchRestockPantry(
      pantry = restockedMon,
      catalog = seed.ingredients,
      deltas = listOf(RestockDelta("ing_salmon", 500.0)),
      nowIso = "${oneDayBeforeExpiry}T12:00:00Z",
      todayIso = oneDayBeforeExpiry,
    ).first { it.ingredientId == "ing_salmon" }
    assertEquals(1000.0, toppedUpSalmon.currentQuantity, 0.001)
    assertEquals(1, computeRemainingShelfLifeDays(toppedUpSalmon, oneDayBeforeExpiry))

    // Freeze to Rescue: moving urgent salmon into StorageLocation.FREEZER extends shelf life to >= 90 days.
    val frozenPantry = movePantryItemToZone(
      pantry = restockedMon,
      ingredientId = "ing_salmon",
      targetLocation = StorageLocation.FREEZER,
      catalog = seed.ingredients,
      todayIso = oneDayBeforeExpiry,
      nowIso = "${oneDayBeforeExpiry}T13:00:00Z",
    )
    val frozenSalmon = frozenPantry.first { it.ingredientId == "ing_salmon" }
    assertEquals(StorageLocation.FREEZER, frozenSalmon.storageLocation)
    val frozenDays = computeRemainingShelfLifeDays(frozenSalmon, oneDayBeforeExpiry) ?: 0
    assertTrue(frozenDays >= 90)
    val postFreezeDashboard = evaluatePantryExpiryDashboard(
      pantry = frozenPantry,
      catalog = seed.ingredients,
      recipes = allRecipes,
      todayIso = oneDayBeforeExpiry,
    )
    assertFalse(postFreezeDashboard.expiringItems.any { it.entry.ingredientId == "ing_salmon" })
    assertTrue(postFreezeDashboard.freezerItems.any { it.ingredientId == "ing_salmon" })
  }
}
