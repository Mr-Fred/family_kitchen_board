package com.familykitchen.board.data

import com.familykitchen.board.domain.AiChatRole
import com.familykitchen.board.domain.AiChatTurn
import com.familykitchen.board.domain.BaseUnit
import com.familykitchen.board.domain.BomLine
import com.familykitchen.board.domain.CookOwner
import com.familykitchen.board.domain.CookedDeductionPayload
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.IngredientCategory
import com.familykitchen.board.domain.IngredientTier
import com.familykitchen.board.domain.MealSlotStatus
import com.familykitchen.board.domain.MealSlotType
import com.familykitchen.board.domain.MorningStep
import com.familykitchen.board.domain.PantryLedgerEngine
import com.familykitchen.board.domain.PantryLedgerEntry
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.RestockDelta
import com.familykitchen.board.domain.SchemaValidator
import com.familykitchen.board.domain.SeedCatalogDataset
import com.familykitchen.board.domain.StapleStatus
import com.familykitchen.board.domain.ValidationException
import com.familykitchen.board.domain.WorkoutBoostModifier
import com.familykitchen.board.domain.WorkoutTiming
import com.familykitchen.board.ports.AiEdgePort
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ports.FixedKitchenClock
import com.familykitchen.board.ports.KitchenSnapshot
import com.familykitchen.board.ports.SeedCatalogPort
import com.familykitchen.board.ports.VoiceInputPort
import com.familykitchen.board.ports.VoiceInputState
import com.familykitchen.board.ports.buildSuggestedKitchenBackupFileName
import com.familykitchen.board.ports.encodeKitchenBackupJson
import com.familykitchen.board.ui.KitchenBoardViewModel
import com.familykitchen.board.ui.TopLevelDestination
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// Hermetic JVM unit test suite verifying O(1) atomic batch persistence invariants (G-04) across
// FakeKitchenStorage and LocalJsonKitchenStorage (.tmp + fsync + rename), plus AssetSeedCatalogLoader.

/**
 * Hermetic JUnit 4 suite verifying `O(1)` atomic batch write guarantees (`batchWriteCallCount == 1`
 * per multi-item cooked meal deduction, Smart Grocery restock, and snapshot update) across
 * [FakeKitchenStorage] and [LocalJsonKitchenStorage], alongside [AssetSeedCatalogLoader.decodeFromStream].
 */
class BatchStorageInvariantTest {

  /** Temporary filesystem folder cleaned up deterministically after each JUnit test method. */
  @get:Rule
  val tempFolder: TemporaryFolder = TemporaryFolder()

  // Build a 12-item Tier-1 + 1 Tier-2 catalog to stress-test multi-ingredient batch commits.
  private val bulkCatalog: List<IngredientCatalogItem> =
    (1..12).map { index ->
      IngredientCatalogItem(
        ingredientId = "ing_bulk_$index",
        displayName = "Bulk Perishable $index",
        category = IngredientCategory.PRODUCE,
        tier = IngredientTier.TIER_1_QUANTITATIVE,
        baseUnit = BaseUnit.COUNT,
        defaultPackageQuantity = 10.0,
      )
    } + IngredientCatalogItem(
      ingredientId = "ing_olive_oil",
      displayName = "Extra-Virgin Olive Oil",
      category = IngredientCategory.PANTRY_STAPLE,
      tier = IngredientTier.TIER_2_STAPLE,
      baseUnit = BaseUnit.MILLILITER,
      defaultPackageQuantity = 500.0,
    )

  private val bulkInitialPantry: List<PantryLedgerEntry> =
    (1..12).map { index ->
      PantryLedgerEntry("ing_bulk_$index", IngredientTier.TIER_1_QUANTITATIVE, 20.0, StapleStatus.PLENTY)
    } + PantryLedgerEntry("ing_olive_oil", IngredientTier.TIER_2_STAPLE, 150.0, StapleStatus.LOW)

  private val twelveItemFeastRecipe: Recipe = Recipe(
    recipeId = "rec_twelve_item_feast",
    title = "Twelve-Ingredient Family One-Pot Feast",
    sourceAttribution = "Household Batch Proof",
    allowedSlots = listOf(MealSlotType.BREAKFAST, MealSlotType.LUNCH, MealSlotType.DINNER),
    preferredOwner = CookOwner.FRED,
    baseServings = 4,
    rawPrepMinutes = 5,
    cookMinutes = 10,
    calibratedActiveMinutes = 17,
    morningSteps = listOf(MorningStep(1, "Cook all 12 fresh ingredients in one skillet.", 600)),
    toddlerPrepTip = "Dice tender produce into soft pea-sized pieces for 2yo.",
    bomLines = (1..12).map { index -> BomLine("ing_bulk_$index", 2.0) } + BomLine("ing_olive_oil", 15.0),
    workoutModifiers = listOf(
      WorkoutBoostModifier(
        profile = WorkoutTiming.POST_WORKOUT_HIGH_PROTEIN,
        instructionAddOn = "Add +2 extra units of Bulk Perishable 1 for Dad.",
        extraBomLines = listOf(BomLine("ing_bulk_1", 2.0)),
      ),
    ),
  )

  private val seedDataset: SeedCatalogDataset = SeedCatalogDataset(
    ingredients = bulkCatalog,
    recipes = listOf(twelveItemFeastRecipe),
    initialPantry = bulkInitialPantry,
  )

  /**
   * Verifies that [FakeKitchenStorage.loadOrInitialize] populates the 21-slot snapshot with
   * `batchWriteCallCount == 0`, while [FakeKitchenStorage.updateSnapshotBatch] and
   * [FakeKitchenStorage.resetWriteCounter] track user commits accurately.
   */
  @Test
  fun fakeKitchenStorage_coldLoadInitializesWithZeroWritesAndTracksWriteHistory() = runTest {
    val storage = FakeKitchenStorage()
    assertNull(storage.snapshotFlow.value)
    assertEquals(0, storage.batchWriteCallCount)

    // Cold-start seeding populates snapshotFlow once without incrementing user write telemetry.
    val initialized = storage.loadOrInitialize(seedDataset)
    assertEquals(21, initialized.weeklyBoard.slots.size)
    assertEquals(0, storage.batchWriteCallCount)
    assertTrue(storage.writeHistory.isEmpty())

    // Subsequent loadOrInitialize calls return the cached snapshot idempotently with 0 writes.
    val cached = storage.loadOrInitialize(seedDataset)
    assertEquals(initialized, cached)
    assertEquals(0, storage.batchWriteCallCount)

    // Transforming state via updateSnapshotBatch increments batchWriteCallCount by +1.
    val updated = storage.updateSnapshotBatch { current ->
      current.copy(aiAddedGroceryIds = setOf("ing_bulk_1", "ing_bulk_2"))
    }
    assertEquals(1, storage.batchWriteCallCount)
    assertEquals(1, storage.writeHistory.size)
    assertEquals(setOf("ing_bulk_1", "ing_bulk_2"), updated.aiAddedGroceryIds)

    // Resetting metrics clears the counter and audit list while preserving active state.
    storage.resetWriteCounter()
    assertEquals(0, storage.batchWriteCallCount)
    assertTrue(storage.writeHistory.isEmpty())
    assertNotNull(storage.snapshotFlow.value)
  }

  /**
   * Verifies `G-04`: Committing a 12-ingredient cooked meal deduction (with workout boost and
   * learned household default override) via [FakeKitchenStorage.commitCookedMealBatch] executes in
   * **exactly `1`** atomic batch write (`batchWriteCallCount == 1`).
   */
  @Test
  fun fakeKitchenStorage_twelveItemCookedDeduction_commitsInSingleO1BatchWrite() = runTest {
    val storage = FakeKitchenStorage()
    val initial = storage.loadOrInitialize(seedDataset)
    val targetSlot = initial.weeklyBoard.slots.first()
    storage.resetWriteCounter()

    // Cook all 12 Tier-1 items + Tier-2 oil, overriding ing_bulk_2 to 5.0 and saving as default.
    val payload = CookedDeductionPayload(
      slotId = targetSlot.slotId,
      recipeId = twelveItemFeastRecipe.recipeId,
      targetServings = 4,
      activeWorkoutModifier = WorkoutTiming.POST_WORKOUT_HIGH_PROTEIN,
      actualUsedQuantities = mapOf("ing_bulk_2" to 5.0),
      saveOverrideAsHouseholdDefault = true,
    )
    val deduction = PantryLedgerEngine.applyCookedDeduction(
      pantry = initial.pantryEntries,
      recipe = twelveItemFeastRecipe,
      catalog = initial.ingredients,
      payload = payload,
    )

    val committed = storage.commitCookedMealBatch(initial, targetSlot.slotId, deduction)

    // Regardless of 13 BOM lines + recipe override + slot status, exactly 1 batch write occurred.
    assertEquals(1, storage.batchWriteCallCount)
    assertEquals(1, storage.writeHistory.size)
    assertEquals(16.0, requireNotNull(committed.pantryById["ing_bulk_1"]).currentQuantity, 0.001)
    assertEquals(15.0, requireNotNull(committed.pantryById["ing_bulk_2"]).currentQuantity, 0.001)
    assertEquals(18.0, requireNotNull(committed.pantryById["ing_bulk_12"]).currentQuantity, 0.001)
    assertEquals(StapleStatus.LOW, requireNotNull(committed.pantryById["ing_olive_oil"]).stapleStatus)

    val persistedSlot = requireNotNull(committed.weeklyBoard.slots.find { it.slotId == targetSlot.slotId })
    assertEquals(MealSlotStatus.COOKED, persistedSlot.status)
    val persistedRecipe = requireNotNull(committed.recipesById[twelveItemFeastRecipe.recipeId])
    assertEquals(5.0, requireNotNull(persistedRecipe.householdBomOverrides["ing_bulk_2"]), 0.001)
  }

  /**
   * Verifies `G-04`: Restocking 13 checked Smart Grocery List items (12 Tier-1 + 1 Tier-2 staple)
   * via [FakeKitchenStorage.commitGroceryRestockBatch] executes in **exactly `1`** atomic batch
   * write and clears fulfilled manual queue IDs.
   */
  @Test
  fun fakeKitchenStorage_multiItemGroceryRestock_commitsInSingleO1BatchWriteAndClearsQueue() = runTest {
    val preQueuedSnapshot = KitchenSnapshot.fromSeedCatalog(seedDataset).copy(
      aiAddedGroceryIds = setOf("ing_bulk_1", "ing_bulk_2", "ing_bulk_3"),
    )
    val storage = FakeKitchenStorage(initialSnapshot = preQueuedSnapshot)
    assertEquals(0, storage.batchWriteCallCount)

    val deltas = (1..12).map { index -> RestockDelta("ing_bulk_$index", 10.0) } +
      RestockDelta("ing_olive_oil", 500.0, StapleStatus.PLENTY)

    val restocked = storage.commitGroceryRestockBatch(preQueuedSnapshot, deltas)

    // Entire 13-item grocery trip commits in 1 O(1) write, restoring Tier-2 oil to PLENTY.
    assertEquals(1, storage.batchWriteCallCount)
    assertEquals(30.0, requireNotNull(restocked.pantryById["ing_bulk_1"]).currentQuantity, 0.001)
    assertEquals(30.0, requireNotNull(restocked.pantryById["ing_bulk_12"]).currentQuantity, 0.001)
    assertEquals(StapleStatus.PLENTY, requireNotNull(restocked.pantryById["ing_olive_oil"]).stapleStatus)
    assertEquals(setOf("ing_bulk_3").intersect(restocked.aiAddedGroceryIds), emptySet<String>())
  }

  /**
   * Verifies that [LocalJsonKitchenStorage] writes atomically via `.tmp` + rename (`0` leftover
   * `.tmp` files), increments `batchWriteCallCount` by `1` per batch action, and reloads durable
   * state cleanly across adapter restarts.
   */
  @Test
  fun localJsonKitchenStorage_atomicTmpWriteAndRename_persistsAndReloadsInO1Writes() = runTest {
    val storageDir = tempFolder.newFolder("offline_kitchen_store")
    val localStorage = LocalJsonKitchenStorage(storageDirectory = storageDir)
    val tempStagingFile = File(storageDir, "${LocalJsonKitchenStorage.SNAPSHOT_FILE_NAME}${LocalJsonKitchenStorage.TEMP_FILE_SUFFIX}")

    // Cold-start creates kitchen_snapshot_v1.json with 0 user batch writes and 0 leftover .tmp files.
    val seeded = localStorage.loadOrInitialize(seedDataset)
    assertTrue(localStorage.snapshotFile.isFile)
    assertFalse(tempStagingFile.exists())
    assertEquals(0, localStorage.batchWriteCallCount)

    // Batch Action 1: commit a 12-ingredient cooked meal deduction -> batchWriteCallCount == 1.
    val slotId = seeded.weeklyBoard.slots.first().slotId
    val deduction = PantryLedgerEngine.applyCookedDeduction(
      pantry = seeded.pantryEntries,
      recipe = twelveItemFeastRecipe,
      catalog = seeded.ingredients,
      payload = CookedDeductionPayload(slotId = slotId, recipeId = twelveItemFeastRecipe.recipeId),
    )
    val afterCook = localStorage.commitCookedMealBatch(seeded, slotId, deduction)
    assertEquals(1, localStorage.batchWriteCallCount)
    assertFalse(tempStagingFile.exists())

    // Batch Action 2: commit a multi-item Smart Grocery restock -> batchWriteCallCount == 2.
    val restockDeltas = listOf(
      RestockDelta("ing_bulk_1", 10.0),
      RestockDelta("ing_olive_oil", 500.0, StapleStatus.PLENTY),
    )
    val afterRestock = localStorage.commitGroceryRestockBatch(afterCook, restockDeltas)
    assertEquals(2, localStorage.batchWriteCallCount)
    assertFalse(tempStagingFile.exists())

    // Simulate cold process restart against the same directory and verify durable reload.
    val restartedStorage = LocalJsonKitchenStorage(storageDirectory = storageDir)
    val reloaded = restartedStorage.loadOrInitialize(seedDataset)
    assertEquals(0, restartedStorage.batchWriteCallCount)
    assertEquals(28.0, requireNotNull(reloaded.pantryById["ing_bulk_1"]).currentQuantity, 0.001)
    assertEquals(StapleStatus.PLENTY, requireNotNull(reloaded.pantryById["ing_olive_oil"]).stapleStatus)
    assertEquals(
      MealSlotStatus.COOKED,
      requireNotNull(reloaded.weeklyBoard.slots.find { it.slotId == slotId }).status,
    )
    assertEquals(afterRestock.pantryEntries, reloaded.pantryEntries)
  }

  /**
   * Verifies that [LocalJsonKitchenStorage] recovers from corrupt disk JSON on cold start, supports
   * atomic `resetToSeed` (`+1` write), and rejects invalid negative-stock snapshots before touching
   * disk.
   */
  @Test
  fun localJsonKitchenStorage_recoversFromCorruptJsonSupportsResetAndRejectsInvalidSnapshot() = runTest {
    val storageDir = tempFolder.newFolder("corrupt_recovery_store")
    val snapshotFile = File(storageDir, LocalJsonKitchenStorage.SNAPSHOT_FILE_NAME)
    snapshotFile.writeText("{corrupt_truncated_json_payload", Charsets.UTF_8)

    val storage = LocalJsonKitchenStorage(storageDirectory = storageDir)
    val recovered = storage.loadOrInitialize(seedDataset)
    assertEquals(21, recovered.weeklyBoard.slots.size)
    assertEquals(0, storage.batchWriteCallCount)

    // Invalid negative pantry balances fail boundary validation without bumping batchWriteCallCount.
    val invalidSnapshot = recovered.copy(
      pantryEntries = listOf(PantryLedgerEntry("ing_bulk_1", IngredientTier.TIER_1_QUANTITATIVE, -4.0)),
    )
    try {
      storage.saveSnapshotBatch(invalidSnapshot)
      throw AssertionError("Expected ValidationException for negative pantry quantity")
    } catch (expected: ValidationException) {
      assertEquals(ValidationException.ERROR_CODE_VALIDATION, expected.errorCode)
    }
    assertEquals(0, storage.batchWriteCallCount)

    // Mutate state and resetToSeed -> restores baseline in 1 atomic write.
    val mutated = recovered.copy(aiAddedGroceryIds = setOf("ing_bulk_5"))
    storage.saveSnapshotBatch(mutated)
    assertEquals(1, storage.batchWriteCallCount)

    val restored = storage.resetToSeed(seedDataset)
    assertEquals(2, storage.batchWriteCallCount)
    assertTrue(restored.aiAddedGroceryIds.isEmpty())
  }

  /**
   * Regression test verifying that [LocalJsonKitchenStorage.loadOrInitialize] automatically heals
   * colliding legacy `aiTurnHistory` keys (`"user_txt_1"`, `"asst_det_2"`) persisted on disk prior
   * to an APK update so Compose `LazyColumn` never crashes on relaunch.
   */
  @Test
  fun localJsonKitchenStorage_healsLegacyDuplicateTurnIdsOnLoad() = runTest {
    val storageDir = tempFolder.newFolder("legacy_duplicate_turns_store")
    val snapshotFile = File(storageDir, LocalJsonKitchenStorage.SNAPSHOT_FILE_NAME)
    val legacySnapshot = KitchenSnapshot.fromSeedCatalog(seedDataset).copy(
      aiTurnHistory = listOf(
        AiChatTurn("user_txt_1", AiChatRole.USER, "First session prompt", "2026-04-06T07:00:00Z"),
        AiChatTurn("asst_det_2", AiChatRole.ASSISTANT, "First session reply", "2026-04-06T07:00:01Z"),
        AiChatTurn("user_txt_1", AiChatRole.USER, "Second session prompt", "2026-04-06T08:00:00Z"),
        AiChatTurn("asst_det_2", AiChatRole.ASSISTANT, "Second session reply", "2026-04-06T08:00:01Z"),
      ),
    )
    snapshotFile.writeText(
      SchemaValidator.strictJson.encodeToString(KitchenSnapshot.serializer(), legacySnapshot),
      Charsets.UTF_8,
    )

    val storage = LocalJsonKitchenStorage(storageDirectory = storageDir)
    val healed = storage.loadOrInitialize(seedDataset)
    assertEquals(4, healed.aiTurnHistory.size)
    assertEquals(4, healed.aiTurnHistory.map { it.turnId }.distinct().size)
  }

  /**
   * Verifies that [AssetSeedCatalogLoader.decodeFromStream] validates the canonical bundled
   * `seed_catalog.json` (`44` ingredients, `30` recipes, `44` pantry records) and rejects polluted
   * or failing streams with [ValidationException].
   */
  @Test
  fun assetSeedCatalogLoader_decodeFromStream_validatesCanonicalAssetAndRejectsBadStreams() {
    val canonicalBytes = readCanonicalSeedAssetText().toByteArray(Charsets.UTF_8)
    val dataset = AssetSeedCatalogLoader.decodeFromStream(ByteArrayInputStream(canonicalBytes))

    assertEquals(44, dataset.ingredients.size)
    assertEquals(30, dataset.recipes.size)
    assertEquals(44, dataset.initialPantry.size)

    // Reject unknown injected JSON keys at the asset loader boundary.
    val pollutedBytes = """{"ingredients":[],"recipes":[],"initialPantry":[],"rogue":1}"""
      .toByteArray(Charsets.UTF_8)
    assertThrows(ValidationException::class.java) {
      AssetSeedCatalogLoader.decodeFromStream(ByteArrayInputStream(pollutedBytes))
    }

    // Wrap underlying stream IOExceptions cleanly into domain ValidationException.
    val brokenStream = object : InputStream() {
      override fun read(): Int = throw IOException("simulated asset read failure")
    }
    assertThrows(ValidationException::class.java) {
      AssetSeedCatalogLoader.decodeFromStream(brokenStream)
    }
  }

  /**
   * Verifies Android 12+ reinstall data-retention configuration in `AndroidManifest.xml`,
   * `backup_rules.xml`, and `data_extraction_rules.xml`: `android:hasFragileUserData="true"` and
   * allowlist-only backup of `kitchen_snapshot_v1.json` (excluding API key vaults and `.litertlm`
   * model weights).
   */
  @Test
  fun reinstallRetentionManifestAndScopedBackupRules_preserveSnapshotAndExcludeVaultSecrets() {
    val manifestXml = readAppFileText("src/main/AndroidManifest.xml")
    val backupRulesXml = readAppFileText("src/main/res/xml/backup_rules.xml")
    val extractionRulesXml = readAppFileText("src/main/res/xml/data_extraction_rules.xml")

    assertTrue(manifestXml.contains("""android:hasFragileUserData="true""""))
    assertTrue(manifestXml.contains("""android:allowBackup="true""""))
    assertTrue(manifestXml.contains("""android:fullBackupContent="@xml/backup_rules""""))
    assertTrue(manifestXml.contains("""android:dataExtractionRules="@xml/data_extraction_rules""""))

    // Verify strict allowlist-only inclusion of kitchen_snapshot_v1.json across cloud and transfer.
    assertTrue(backupRulesXml.contains("""<include domain="file" path="kitchen_snapshot_v1.json" />"""))
    assertTrue(extractionRulesXml.contains("<cloud-backup>"))
    assertTrue(extractionRulesXml.contains("<device-transfer>"))
    assertTrue(extractionRulesXml.contains("""<include domain="file" path="kitchen_snapshot_v1.json" />"""))
    assertFalse(backupRulesXml.contains("sharedpref"))
    assertFalse(extractionRulesXml.contains("sharedpref"))
    assertFalse(extractionRulesXml.contains("savor_cloud_ai_vault_v1"))
  }

  /**
   * Verifies end-to-end portable `.json` backup export, `onUserDataChanged` notification hooks,
   * raw `kitchen_snapshot_v1.json` compatibility, corrupt-file rejection, and `1`-write `O(1)`
   * restore exiting First-Run Onboarding after a clean reinstall.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun portableKitchenBackupAndRestore_roundTripsAcrossCleanReinstallInSingleO1BatchWrite() = runTest {
    val testDispatcher = UnconfinedTestDispatcher(testScheduler)
    Dispatchers.setMain(testDispatcher)
    try {
      var backupNotifyCount = 0
      val sourceDir = tempFolder.newFolder("source_install_store")
      val sourceStorage = LocalJsonKitchenStorage(
        storageDirectory = sourceDir,
        ioDispatcher = testDispatcher,
        onUserDataChanged = { backupNotifyCount++ },
      )
      val initial = sourceStorage.loadOrInitialize(seedDataset)
      // Cold initialization must not schedule spurious cloud backup notifications.
      assertEquals(0, backupNotifyCount)

      val customized = initial.copy(
        householdProfile = initial.householdProfile.copy(
          householdName = "Fred West African Bistro",
          preferredCuisines = listOf("French", "Yoruba and West African"),
        ),
        savedRecipeIds = setOf(twelveItemFeastRecipe.recipeId),
        hasCompletedOnboarding = true,
      )
      sourceStorage.saveSnapshotBatch(customized)
      assertEquals(1, backupNotifyCount)

      val suggestedName = buildSuggestedKitchenBackupFileName(
        householdName = customized.householdProfile.householdName,
        todayIso = "2026-11-23",
      )
      assertEquals("SavorAI_Backup_Fred_West_African_Bistro_2026-11-23.json", suggestedName)
      val exportedEnvelopeJson = encodeKitchenBackupJson(customized, exportedAtIso = "2026-11-23")

      // Simulate a fresh clean reinstall (no existing snapshot file -> isOnboardingActive == true).
      val reinstallDir = tempFolder.newFolder("clean_reinstall_store")
      val reinstallStorage = LocalJsonKitchenStorage(
        storageDirectory = reinstallDir,
        ioDispatcher = testDispatcher,
      )
      val viewModel = KitchenBoardViewModel(
        seedCatalogPort = SeedCatalogPort { seedDataset },
        storagePort = reinstallStorage,
        aiEdgePort = NoOpBackupTestEdgePort(),
        voiceInputPort = NoOpBackupTestVoicePort(),
        clockPort = FixedKitchenClock("2026-11-23", currentHour24 = 18),
      )
      assertTrue(viewModel.uiState.value.isOnboardingActive)
      assertEquals(0, reinstallStorage.batchWriteCallCount)

      // Restore portable envelope -> exits onboarding onto PLAN in 1 O(1) atomic write!
      viewModel.restoreHouseholdBackupFromJson(exportedEnvelopeJson)
      assertFalse(viewModel.uiState.value.isOnboardingActive)
      assertEquals(TopLevelDestination.PLAN, viewModel.uiState.value.activeDestination)
      assertEquals(1, reinstallStorage.batchWriteCallCount)
      assertEquals(
        "Fred West African Bistro",
        viewModel.uiState.value.snapshot.householdProfile.householdName,
      )
      assertEquals(
        listOf("French", "Yoruba and West African"),
        viewModel.uiState.value.snapshot.householdProfile.preferredCuisines,
      )
      assertTrue(twelveItemFeastRecipe.recipeId in viewModel.uiState.value.snapshot.savedRecipeIds)

      // Raw kitchen_snapshot_v1.json pulled via adb also restores seamlessly; corrupt JSON is rejected.
      val rawDiskJson = sourceStorage.snapshotFile.readText(Charsets.UTF_8)
      viewModel.restoreHouseholdBackupFromJson(rawDiskJson)
      assertEquals(2, reinstallStorage.batchWriteCallCount)

      viewModel.restoreHouseholdBackupFromJson("{not_valid_json")
      // Invalid backup leaves batchWriteCallCount and restored household state untouched.
      assertEquals(2, reinstallStorage.batchWriteCallCount)
      assertEquals(
        "Fred West African Bistro",
        viewModel.uiState.value.snapshot.householdProfile.householdName,
      )
    } finally {
      Dispatchers.resetMain()
    }
  }

  private fun readCanonicalSeedAssetText(): String = readAppFileText("src/main/assets/seed_catalog.json")

  private fun readAppFileText(relativeUnderModule: String): String {
    // Support running JUnit from android/app (AGP module dir), android/, or repository root.
    val candidates = listOf(
      Path(relativeUnderModule),
      Path("app/$relativeUnderModule"),
      Path("android/app/$relativeUnderModule"),
    )
    val matched = requireNotNull(candidates.firstOrNull { it.isRegularFile() }) {
      "Unable to locate '$relativeUnderModule'"
    }
    return matched.readText()
  }

  private class NoOpBackupTestEdgePort : AiEdgePort {
    private val state = MutableStateFlow<AiRuntimeState>(AiRuntimeState.Uninitialized)
    override val runtimeState: StateFlow<AiRuntimeState> = state.asStateFlow()
    override suspend fun autoDiscoverAndInitialize(): AiRuntimeState = state.value
    override suspend fun importAndLoadModel(sourceUriString: String, displayName: String?): AiRuntimeState = state.value
    override suspend fun generateText(prompt: String): String = "{}"
    override suspend fun analyzeImageWithPrompt(preprocessedImagePath: String, prompt: String): String = "{}"
    override fun close() = Unit
  }

  private class NoOpBackupTestVoicePort : VoiceInputPort {
    private val state = MutableStateFlow<VoiceInputState>(VoiceInputState.Idle)
    override val voiceState: StateFlow<VoiceInputState> = state.asStateFlow()
    override fun startListening(languageTag: String): Boolean = true
    override fun stopListening() = Unit
    override fun release() = Unit
  }
}
