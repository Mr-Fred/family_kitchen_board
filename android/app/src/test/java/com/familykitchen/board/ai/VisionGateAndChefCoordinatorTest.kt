package com.familykitchen.board.ai

import com.familykitchen.board.data.FakeKitchenStorage
import com.familykitchen.board.domain.AiChatRole
import com.familykitchen.board.domain.AiChatTurn
import com.familykitchen.board.domain.AiChefAction
import com.familykitchen.board.domain.BaseUnit
import com.familykitchen.board.domain.BomLine
import com.familykitchen.board.domain.CookOwner
import com.familykitchen.board.domain.EdgeDeviceProfile
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.IngredientCategory
import com.familykitchen.board.domain.IngredientTier
import com.familykitchen.board.domain.IntakeCommitTarget
import com.familykitchen.board.domain.MealSlotType
import com.familykitchen.board.domain.ModelNotReadyException
import com.familykitchen.board.domain.MorningStep
import com.familykitchen.board.domain.PantryLedgerEntry
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.SeedCatalogDataset
import com.familykitchen.board.domain.StapleStatus
import com.familykitchen.board.domain.ValidationException
import com.familykitchen.board.domain.VisionNotSupportedException
import com.familykitchen.board.ports.AiEdgePort
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ports.KitchenSnapshot
import com.familykitchen.board.ports.ModelFileFormat
import com.familykitchen.board.ports.RuntimeCapabilities
import com.familykitchen.board.ports.SeedCatalogPort
import com.familykitchen.board.ports.VoiceInputPort
import com.familykitchen.board.ports.VoiceInputState
import com.familykitchen.board.ui.KitchenBoardViewModel
import com.familykitchen.board.ui.TopLevelDestination
import com.familykitchen.board.ui.aichef.formatByteProgressLabel
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.CompletableDeferred
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

// Hermetic JVM unit test suite verifying AiChefCoordinator's multimodal vision pipeline,
// strict non-silent Vision Gate across text-only backends, temp-file cleanup, and ModelFileDiscovery.

/**
 * Hermetic JUnit 4 verification suite for [AiChefCoordinator] and [ModelFileDiscovery] covering:
 * 1. Authentic `preprocessedImagePath` forwarding when `supportsVision == true` with zero fake
 *    grocery strings, zero pre-inference pantry mutations, confirmation-gated `VisualIntakeResult`,
 *    and guaranteed temporary capture deletion (`tempImageCleanedUp == true`),
 * 2. Non-silent [VisionNotSupportedException] and [ModelNotReadyException] `VISION_GATE_BLOCKED`
 *    surfacing when `supportsVision == false` (text-only `.litertlm`, `Session`, or MediaPipe
 *    `LlmInference`) or uninitialized—with **zero** silent fallback to `generateText`, untouched
 *    pantry stock, and guaranteed temp-file cleanup in `finally`.
 */
class VisionGateAndChefCoordinatorTest {

  /** Temporary folder hosting simulated preprocessed camera/gallery JPEGs and model bundles. */
  @get:Rule
  val tempFolder: TemporaryFolder = TemporaryFolder()

  // Household catalog fixture covering Tier-1 brain-boosters, grains, and Tier-2 olive oil.
  private val testCatalog: List<IngredientCatalogItem> = listOf(
    IngredientCatalogItem("ing_egg", "Large Farm Eggs", IngredientCategory.DAIRY_EGGS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.COUNT, 12.0, allergens = listOf("EGGS")),
    IngredientCatalogItem("ing_oats", "Rolled Whole Grain Oats", IngredientCategory.BAKERY_GRAINS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 1000.0),
    IngredientCatalogItem("ing_blueberry", "Fresh Blueberries", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 250.0, substituteGroupIds = listOf("FRUIT_TOPPING")),
    IngredientCatalogItem("ing_baguette", "Crusty Artisan Baguette", IngredientCategory.BAKERY_GRAINS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.COUNT, 2.0, allergens = listOf("GLUTEN")),
    IngredientCatalogItem("ing_olive_oil", "Extra-Virgin Olive Oil", IngredientCategory.PANTRY_STAPLE, IngredientTier.TIER_2_STAPLE, BaseUnit.MILLILITER, 750.0),
  )

  // Stocked baseline pantry so pre-confirmation vs post-confirmation mutations are crystal clear.
  private val testInitialPantry: List<PantryLedgerEntry> = listOf(
    PantryLedgerEntry("ing_egg", IngredientTier.TIER_1_QUANTITATIVE, 6.0),
    PantryLedgerEntry("ing_oats", IngredientTier.TIER_1_QUANTITATIVE, 500.0),
    PantryLedgerEntry("ing_blueberry", IngredientTier.TIER_1_QUANTITATIVE, 250.0),
    PantryLedgerEntry("ing_baguette", IngredientTier.TIER_1_QUANTITATIVE, 2.0),
    PantryLedgerEntry("ing_olive_oil", IngredientTier.TIER_2_STAPLE, 150.0, StapleStatus.LOW),
  )

  // Fast 11m baseline toast recipe used as the initial scheduled breakfast slot.
  private val plainToastRecipe = Recipe(
    recipeId = "rec_plain_toast",
    title = "Plain Baguette Toast",
    sourceAttribution = "Morning Baseline",
    allowedSlots = listOf(MealSlotType.BREAKFAST, MealSlotType.LUNCH, MealSlotType.DINNER),
    preferredOwner = CookOwner.FRED,
    rawPrepMinutes = 4,
    cookMinutes = 5,
    calibratedActiveMinutes = 11,
    morningSteps = listOf(MorningStep(1, "Toast baguette slices.", 300)),
    toddlerPrepTip = "Serve soft inner bread crumb strips for 2yo.",
    bomLines = listOf(BomLine("ing_baguette", 1.0)),
  )

  private val brainBowlRecipe = Recipe(
    recipeId = "rec_choline_oat_berry_bowl",
    title = "Choline Egg, Berry & Oat Bowl",
    sourceAttribution = "Pediatric Brain Kitchen",
    allowedSlots = listOf(MealSlotType.BREAKFAST, MealSlotType.LUNCH, MealSlotType.DINNER),
    preferredOwner = CookOwner.FRED,
    rawPrepMinutes = 6,
    cookMinutes = 10,
    calibratedActiveMinutes = 19,
    morningSteps = listOf(MorningStep(1, "Simmer oats with berries and soft-scramble eggs.", 600)),
    toddlerPrepTip = "Quarter blueberries lengthwise and serve soft egg curds for 2yo.",
    bomLines = listOf(
      BomLine("ing_egg", 4.0),
      BomLine("ing_oats", 200.0),
      BomLine("ing_blueberry", 150.0, substituteGroupId = "FRUIT_TOPPING"),
      BomLine("ing_olive_oil", 10.0),
    ),
  )

  private val baselineSnapshot: KitchenSnapshot = KitchenSnapshot.fromSeedCatalog(
    SeedCatalogDataset(
      ingredients = testCatalog,
      recipes = listOf(plainToastRecipe, brainBowlRecipe),
      initialPantry = testInitialPantry,
    ),
  )

  /**
   * Verifies `(a)`: When `supportsVision == true`, [AiChefCoordinator] forwards the exact
   * `preprocessedImagePath` to [AiEdgePort.analyzeImageWithPrompt] with zero hardcoded fake grocery
   * strings and zero pre-inference pantry mutation, returns a confirmation-gated
   * `VisualIntakeResult`, and deletes the temp JPEG file (`tempImageCleanedUp == true`).
   */
  @Test
  fun supportsVisionTrue_forwardsExactImagePathZeroFakePromptGatesRestockAndCleansTempFile() = runTest {
    val preprocessedJpeg = createSimulatedCaptureFile("preprocessed_counter_1280.jpg")
    val visionJsonResponse = """
      <start_of_turn>model
      ```json
      {
        "summary": "Counter shows 1 carton of Large Farm Eggs, Extra-Virgin Olive Oil, and a ceramic mug.",
        "detectedItems": [
          {"rawLabel": "Large Farm Eggs", "matchedIngredientId": "ing_egg", "estimatedQuantity": 12, "confidence": 0.97},
          {"rawLabel": "Extra-Virgin Olive Oil", "matchedIngredientId": "ing_olive_oil", "estimatedQuantity": 0, "confidence": 0.93}
        ],
        "unrecognizedLabels": ["Ceramic Coffee Mug"]
      }
      ```<end_of_turn>
    """.trimIndent()

    val fakePort = FakeAiEdgePort(
      initialState = AiRuntimeState.Ready(
        RuntimeCapabilities(
          supportsText = true,
          supportsVision = true,
          backendLabel = "LiteRT-LM GPU + Vision",
          modelName = "gemma-e2b-it-multimodal-int4.litertlm",
        ),
      ),
      visionResponder = { _, _ -> visionJsonResponse },
    )
    val coordinator = AiChefCoordinator(aiEdgePort = fakePort)

    // Include "We bought Large Farm Eggs" in caption to prove regex tools NEVER run on image turns.
    val outcome = coordinator.handleSubmitAction(
      snapshot = baselineSnapshot,
      action = AiChefAction.SubmitPrompt(
        promptText = "We bought Large Farm Eggs—please inspect this counter photo",
        attachedImagePath = preprocessedJpeg.absolutePath,
      ),
      attachedImageLabel = "counter_photo.jpg",
    )

    // Verify exact path forwarding, zero text-only calls, and zero fake legacy grocery words.
    assertEquals(1, fakePort.analyzeImageCallCount)
    assertEquals(0, fakePort.generateTextCallCount)
    assertEquals(listOf(preprocessedJpeg.absolutePath), fakePort.capturedImagePaths)

    val sentVisionPrompt = fakePort.capturedVisionPrompts.single().lowercase()
    assertFalse(sentVisionPrompt.contains("shrimp"))
    assertFalse(sentVisionPrompt.contains("tilapia"))
    assertFalse(sentVisionPrompt.contains("pineapple"))
    assertFalse(sentVisionPrompt.contains("spinach"))

    // Confirmation gate: pantryEntries MUST remain untouched until user explicitly confirms restock!
    assertEquals(AiTurnResolutionStatus.MULTIMODAL_VISION_GROUNDED, outcome.status)
    assertNull(outcome.visionWarningMessage)
    assertNull(outcome.blockingException)
    assertEquals(baselineSnapshot.pantryEntries, outcome.updatedSnapshot.pantryEntries)

    val intakeResult = requireNotNull(outcome.assistantTurn.visualIntakeResult)
    assertEquals(2, intakeResult.groundedRestockDeltas.size)
    assertEquals(listOf("Ceramic Coffee Mug"), intakeResult.unrecognizedLabels)

    // Temp preprocessed JPEG must be deleted deterministically inside finally.
    assertTrue(outcome.tempImageCleanedUp)
    assertFalse(preprocessedJpeg.exists())

    // Explicit user confirmation merges grounded deltas (+12 eggs -> 18, olive oil -> PLENTY).
    val confirmedSnapshot = coordinator.applyConfirmedVisualRestock(outcome.updatedSnapshot, intakeResult)
    assertEquals(18.0, requireNotNull(confirmedSnapshot.pantryById["ing_egg"]).currentQuantity, 0.001)
    assertEquals(StapleStatus.PLENTY, requireNotNull(confirmedSnapshot.pantryById["ing_olive_oil"]).stapleStatus)
  }

  /**
   * Verifies `(b)`: Across all 3 non-vision runtime backends (text-only `.litertlm`, LiteRT
   * `Session`, and MediaPipe `LlmInference`), submitting an attached photo triggers
   * `VISION_GATE_BLOCKED` with [VisionNotSupportedException] and a non-silent `visionWarningMessage`
   * (**zero** silent fallback to `generateText`), leaves pantry untouched, and deletes the temp
   * capture in `finally`.
   */
  @Test
  fun supportsVisionFalse_acrossTextOnlyLitertSessionAndMediaPipe_blocksNonSilentlyAndCleansUp() = runTest {
    val nonVisionRuntimes = listOf(
      RuntimeCapabilities(true, false, "LiteRT-LM GPU Text-Only", "gemma-3-1b-it-gpu-int4.litertlm"),
      RuntimeCapabilities(true, false, "LiteRT Session CPU", "gemma2-2b-it-gpu-int4.litertlm"),
      RuntimeCapabilities(true, false, "MediaPipe LlmInference", "gemma-2b-it-gpu-int4.task"),
    )

    for ((index, capabilities) in nonVisionRuntimes.withIndex()) {
      val captureFile = createSimulatedCaptureFile("non_vision_capture_$index.jpg")
      val fakePort = FakeAiEdgePort(initialState = AiRuntimeState.Ready(capabilities))
      val cleanedPaths = mutableListOf<String>()
      val coordinator = AiChefCoordinator(
        aiEdgePort = fakePort,
        onCleanupTempImage = { path ->
          cleanedPaths.add(path)
          File(path).delete()
        },
      )

      val outcome = coordinator.submitTurn(
        snapshot = baselineSnapshot,
        promptText = "Scan our fridge shelf",
        attachedImagePath = captureFile.absolutePath,
      )

      // Must surface VISION_GATE_BLOCKED with VisionNotSupportedException and 0 generateText calls!
      assertEquals(AiTurnResolutionStatus.VISION_GATE_BLOCKED, outcome.status)
      assertEquals(0, fakePort.generateTextCallCount)
      assertTrue(outcome.blockingException is VisionNotSupportedException)
      val warning = requireNotNull(outcome.visionWarningMessage)
      assertTrue(warning.contains(capabilities.modelName))
      assertTrue(warning.contains(capabilities.backendLabel))

      // Pantry remains untouched and temp file cleanup executes in finally.
      assertEquals(baselineSnapshot.pantryEntries, outcome.updatedSnapshot.pantryEntries)
      assertTrue(outcome.tempImageCleanedUp)
      assertEquals(listOf(captureFile.absolutePath), cleanedPaths)
      assertFalse(captureFile.exists())
    }
  }

  /**
   * Verifies `(b)` cold/unready gate: When the runtime is `Uninitialized` (no model loaded), an
   * image turn surfaces [ModelNotReadyException] as `VISION_GATE_BLOCKED` with zero silent text
   * fallback, zero pantry mutation, and guaranteed temp-file cleanup.
   */
  @Test
  fun uninitializedRuntimeWithImage_surfacesModelNotReadyGateLeavesPantryIntactAndCleansUp() = runTest {
    // Stage a real temporary capture file and keep AiEdgePort in cold Uninitialized state.
    val tempCapture = createSimulatedCaptureFile("uninitialized_receipt.jpg")
    val fakePort = FakeAiEdgePort(initialState = AiRuntimeState.Uninitialized)
    val coordinator = AiChefCoordinator(aiEdgePort = fakePort)

    // Caption intentionally mentions buying eggs/oil to verify regex tools never bypass vision!
    val outcome = coordinator.submitTurn(
      snapshot = baselineSnapshot,
      promptText = "We bought Large Farm Eggs and Extra-Virgin Olive Oil",
      attachedImagePath = tempCapture.absolutePath,
    )

    // Must surface ModelNotReadyException as VISION_GATE_BLOCKED with zero text-only calls.
    assertEquals(AiTurnResolutionStatus.VISION_GATE_BLOCKED, outcome.status)
    assertTrue(outcome.blockingException is ModelNotReadyException)
    assertFalse(outcome.visionWarningMessage.isNullOrBlank())
    assertEquals(0, fakePort.generateTextCallCount)
    // Pantry stays untouched and finally still deletes the temporary capture JPEG.
    assertEquals(baselineSnapshot.pantryEntries, outcome.updatedSnapshot.pantryEntries)
    assertTrue(outcome.tempImageCleanedUp)
    assertFalse(tempCapture.exists())
  }

  /**
   * Verifies that malformed or ungrounded JSON returned by a vision model produces
   * `VISION_INFERENCE_ERROR` without mutating pantry state while still deleting the temp image.
   */
  @Test
  fun malformedVisionOutput_returnsVisionInferenceErrorWithoutPantryMutationAndDeletesTempFile() = runTest {
    // Configure a vision-capable runtime returning an unexpected hallucinated JSON structure.
    val blurryCapture = createSimulatedCaptureFile("blurry_shelf.jpg")
    val fakePort = FakeAiEdgePort(
      initialState = AiRuntimeState.Ready(
        RuntimeCapabilities(true, true, "LiteRT-LM GPU + Vision", "gemma-e2b-it-multimodal-int4.litertlm"),
      ),
      visionResponder = { _, _ -> """{"unexpectedHallucinatedKey": 42}""" },
    )
    val coordinator = AiChefCoordinator(aiEdgePort = fakePort)

    val outcome = coordinator.submitTurn(
      snapshot = baselineSnapshot,
      promptText = "Inspect blurry shelf",
      attachedImagePath = blurryCapture.absolutePath,
    )

    // SchemaValidator rejects the ungrounded vision JSON cleanly without touching pantry stock.
    assertEquals(AiTurnResolutionStatus.VISION_INFERENCE_ERROR, outcome.status)
    assertTrue(outcome.blockingException is ValidationException)
    assertFalse(outcome.visionWarningMessage.isNullOrBlank())
    assertEquals(baselineSnapshot.pantryEntries, outcome.updatedSnapshot.pantryEntries)
    assertTrue(outcome.tempImageCleanedUp)
    assertFalse(blurryCapture.exists())
  }

  /**
   * Verifies text-only orchestration (`DETERMINISTIC_TOOL_EXECUTED`, `ON_DEVICE_TEXT_GROUNDED`,
   * and `OFFLINE_DETERMINISTIC_PROPOSAL` fallback), batch proposal reducers, and rolling turn caps.
   */
  @Test
  fun textOnlyTurns_routeToolsGroundedLlmAndDeterministicFallbackWithBoundedHistory() = runTest {
    val targetSlot = baselineSnapshot.weeklyBoard.slots.first { it.slotType == MealSlotType.BREAKFAST }
    val groundedTextJson = """
      {"focusTag":"KIDS_BRAIN_BOOSTER","assistantSummary":"Swapped breakfast to Choline Bowl.","suggestedSwaps":[{"slotId":"${targetSlot.slotId}","recipeId":"rec_choline_oat_berry_bowl","rationale":"In-stock choline."}]}
    """.trimIndent()

    val fakePort = FakeAiEdgePort(
      initialState = AiRuntimeState.Ready(
        RuntimeCapabilities(true, false, "LiteRT-LM GPU Text-Only", "gemma-3-1b-it-gpu-int4.litertlm"),
      ),
      textResponder = { groundedTextJson },
    )
    val coordinator = AiChefCoordinator(aiEdgePort = fakePort, maxTurnHistorySize = 4)

    // 1. Explicit grocery queue text command runs in < 5ms without invoking LLM generation.
    val toolOutcome = coordinator.submitTurn(baselineSnapshot, "Add blueberries to our grocery list")
    assertEquals(AiTurnResolutionStatus.DETERMINISTIC_TOOL_EXECUTED, toolOutcome.status)
    assertTrue("ing_blueberry" in toolOutcome.updatedSnapshot.aiAddedGroceryIds)
    assertEquals(0, fakePort.generateTextCallCount)
    assertFalse(toolOutcome.tempImageCleanedUp)

    // 2. Open-ended nutrition prompt invokes Ready on-device LLM and grounds suggested swaps.
    val llmOutcome = coordinator.submitTurn(toolOutcome.updatedSnapshot, "Brain boosters for our kids")
    assertEquals(AiTurnResolutionStatus.ON_DEVICE_TEXT_GROUNDED, llmOutcome.status)
    assertEquals(1, fakePort.generateTextCallCount)
    val proposal = requireNotNull(llmOutcome.assistantTurn.proposal)
    val swappedSnapshot = coordinator.applyConfirmedMealSwaps(llmOutcome.updatedSnapshot, proposal)
    assertEquals(
      "rec_choline_oat_berry_bowl",
      requireNotNull(swappedSnapshot.weeklyBoard.slots.find { it.slotId == targetSlot.slotId }).recipeId,
    )
    val queuedSnapshot = coordinator.queueSuggestedBrainBoosters(swappedSnapshot, proposal)
    assertTrue(queuedSnapshot.ingredients.size > baselineSnapshot.ingredients.size)

    // 3. Ungrounded LLM text output safely degrades on text-only turns to deterministic advisor,
    // and rolling turn history stays capped at maxTurnHistorySize = 4.
    fakePort.textResponder = { "{not_valid_json" }
    val fallbackOutcome = coordinator.submitTurn(llmOutcome.updatedSnapshot, "Use up pantry perishables")
    assertEquals(AiTurnResolutionStatus.OFFLINE_DETERMINISTIC_PROPOSAL, fallbackOutcome.status)
    assertNotNull(fallbackOutcome.assistantTurn.proposal)
    assertEquals(4, fallbackOutcome.updatedTurnHistory.size)
  }

  /**
   * Verifies [ModelFileDiscovery] hardware classification, `.litertlm` priority ranking, `.part`
   * partial-transfer filtering, path-traversal filename sanitization, and atomic `.part` imports.
   */
  @Test
  fun modelFileDiscovery_classifiesS22ProfilesIgnoresPartFilesAndImportsAtomically() = runTest {
    val s22Info = ModelFileDiscovery.detectHardwareEnvironment("samsung", "SM-S901U", "r0q", "qcom", "samsung/r0q")
    assertTrue(s22Info.isGalaxyS22Family)
    assertFalse(s22Info.isGalaxyS22Ultra)
    assertEquals(EdgeDeviceProfile.GALAXY_S22_GEMMA_1B_INT4, s22Info.recommendedProfile)

    val s22UltraInfo = ModelFileDiscovery.detectHardwareEnvironment("samsung", "SM-S908U", "b0q", "qcom", "samsung/b0q")
    assertTrue(s22UltraInfo.isGalaxyS22Ultra)
    assertEquals(EdgeDeviceProfile.GALAXY_S22_ULTRA_GEMMA_2B_INT4, s22UltraInfo.recommendedProfile)

    val emulatorInfo = ModelFileDiscovery.detectHardwareEnvironment("Google", "sdk_gphone64_arm64", "emu64a", "ranchu", "generic/emulator")
    assertTrue(emulatorInfo.isEmulator)
    assertEquals("gemma-e2b.litertlm", ModelFileDiscovery.sanitizeModelFileName("../../evil/path/gemma-e2b.litertlm"))

    // Verify directory discovery skips .part/.tmp/tiny files and ranks .litertlm ahead of .task.
    val modelsDir = tempFolder.newFolder("scoped_models")
    File(modelsDir, "gemma-2b.task").writeBytes(ByteArray(4096) { 1 })
    File(modelsDir, "gemma-e2b-vision.litertlm").writeBytes(ByteArray(2048) { 2 })
    File(modelsDir, "incomplete.litertlm.part").writeBytes(ByteArray(8192) { 3 })
    File(modelsDir, "tiny_stub.litertlm").writeBytes(ByteArray(64) { 4 })

    val discovered = ModelFileDiscovery.discoverModelsInDirectories(
      directories = listOf(modelsDir),
      minSizeBytes = 1024L,
      hardwareInfo = s22UltraInfo,
    )
    assertEquals(listOf("gemma-e2b-vision.litertlm", "gemma-2b.task"), discovered.map { it.fileName })
    assertEquals(EdgeDeviceProfile.GEMMA_4_E2B_MULTIMODAL, discovered.first().recommendedProfile)

    // Verify atomic .part stream import promotes complete files and unlinks truncated transfers.
    val importDir = tempFolder.newFolder("imported_models")
    val validPayload = ByteArray(2048) { 7 }
    val imported = ModelFileDiscovery.importStreamToModelsDir(
      inputStream = ByteArrayInputStream(validPayload),
      modelsDir = importDir,
      sanitizedFileName = "gemma-3-1b-it-gpu-int4.litertlm",
      format = ModelFileFormat.LITERT_LM,
      expectedTotalBytes = validPayload.size.toLong(),
      minSizeBytes = 1024L,
      hardwareInfo = s22Info,
    )
    assertTrue(File(imported.absolutePath).isFile)
    assertFalse(File(importDir, "gemma-3-1b-it-gpu-int4.litertlm.part").exists())

    try {
      ModelFileDiscovery.importStreamToModelsDir(
        inputStream = ByteArrayInputStream(ByteArray(512)),
        modelsDir = importDir,
        sanitizedFileName = "truncated.litertlm",
        format = ModelFileFormat.LITERT_LM,
        expectedTotalBytes = 2048L,
        minSizeBytes = 1024L,
        hardwareInfo = s22Info,
      )
      throw AssertionError("Expected ValidationException for truncated model stream")
    } catch (expected: ValidationException) {
      assertEquals(ValidationException.ERROR_CODE_VALIDATION, expected.errorCode)
    }
    assertFalse(File(importDir, "truncated.litertlm.part").exists())
  }

  /**
   * Regression test for Samsung Galaxy S22 multi-session crash: verifies that instantiating a fresh
   * [AiChefCoordinator] (`turnSequence = 0`) against a persisted [KitchenSnapshot] that already
   * contains `"user_txt_1"` and `"asst_det_2"` (plus legacy duplicate IDs) produces 100% distinct
   * `turnId`s so Compose `LazyColumn` never throws `Key was already used`.
   */
  @Test
  fun restartedCoordinatorSession_withPersistedTurnHistory_generatesUniqueNonCollidingTurnIds() = runTest {
    val persistedHistory = listOf(
      AiChatTurn("user_txt_1", AiChatRole.USER, "Dinner ideas tonight?", "2026-04-06T08:00:00Z"),
      AiChatTurn("asst_det_2", AiChatRole.ASSISTANT, "Try Tuscan Salmon.", "2026-04-06T08:00:01Z"),
      // Simulate pre-existing duplicate key written before update.
      AiChatTurn("user_txt_1", AiChatRole.USER, "What about breakfast?", "2026-04-06T08:05:00Z"),
      AiChatTurn("asst_det_2", AiChatRole.ASSISTANT, "Try Egg & Spinach Bowl.", "2026-04-06T08:05:01Z"),
    )
    val snapshotAfterRelaunch = baselineSnapshot.copy(aiTurnHistory = persistedHistory)

    val fakePort = FakeAiEdgePort(initialState = AiRuntimeState.Error("Offline mode"))
    // Fresh coordinator instance simulates cold process restart with AtomicLong(0).
    val restartedCoordinator = AiChefCoordinator(aiEdgePort = fakePort, maxTurnHistorySize = 12)

    val turnOne = restartedCoordinator.submitTurn(
      snapshot = snapshotAfterRelaunch,
      promptText = "Plan brain boosters for our kids",
    )
    assertEquals(6, turnOne.updatedTurnHistory.size)
    assertEquals(
      turnOne.updatedTurnHistory.size,
      turnOne.updatedTurnHistory.map { it.turnId }.distinct().size,
    )

    // Simulate a third app restart against the newly persisted history.
    val thirdSessionCoordinator = AiChefCoordinator(aiEdgePort = fakePort, maxTurnHistorySize = 12)
    val turnTwo = thirdSessionCoordinator.submitTurn(
      snapshot = turnOne.updatedSnapshot,
      promptText = "Add spinach to our grocery list",
    )
    assertEquals(8, turnTwo.updatedTurnHistory.size)
    assertEquals(
      turnTwo.updatedTurnHistory.size,
      turnTwo.updatedTurnHistory.map { it.turnId }.distinct().size,
    )
  }

  /**
   * Regression test verifying that native JNI [UnsatisfiedLinkError] (`java.lang.Error`, which is a
   * [Throwable] rather than an [Exception]) thrown by [AiEdgePort] is caught cleanly in both
   * text-only and multimodal vision pipelines without crashing the app or mutating pantry stock.
   */
  @Test
  fun nativeJniUnsatisfiedLinkError_inTextAndVisionTurns_isCaughtCleanlyWithoutCrashing() = runTest {
    val fakePort = FakeAiEdgePort(
      initialState = AiRuntimeState.Ready(
        RuntimeCapabilities(true, true, "LiteRT-LM GPU + Vision", "gemma-e2b-it-multimodal-int4.litertlm"),
      ),
      textResponder = { throw UnsatisfiedLinkError("dlopen failed: liblitertlm_jni.so missing symbol") },
      visionResponder = { _, _ -> throw UnsatisfiedLinkError("JNI vision bridge linkage error") },
    )
    val coordinator = AiChefCoordinator(aiEdgePort = fakePort)

    // 1. Text turn catches UnsatisfiedLinkError and degrades cleanly to deterministic offline advisor.
    val textOutcome = coordinator.submitTurn(baselineSnapshot, "Suggest a quick family dinner")
    assertEquals(AiTurnResolutionStatus.OFFLINE_DETERMINISTIC_PROPOSAL, textOutcome.status)
    assertNotNull(textOutcome.assistantTurn.proposal)

    // 2. Vision turn catches UnsatisfiedLinkError, preserves pantry stock, and deletes temp JPEG.
    val tempPhoto = createSimulatedCaptureFile("s22_jni_error_scan.jpg")
    val visionOutcome = coordinator.submitTurn(
      snapshot = textOutcome.updatedSnapshot,
      promptText = "Scan this fridge shelf",
      attachedImagePath = tempPhoto.absolutePath,
    )
    assertEquals(AiTurnResolutionStatus.VISION_INFERENCE_ERROR, visionOutcome.status)
    assertEquals(baselineSnapshot.pantryEntries, visionOutcome.updatedSnapshot.pantryEntries)
    assertTrue(visionOutcome.tempImageCleanedUp)
    assertFalse(tempPhoto.exists())
    assertEquals(
      visionOutcome.updatedTurnHistory.size,
      visionOutcome.updatedTurnHistory.map { it.turnId }.distinct().size,
    )
  }

  /**
   * Verifies [ModelFileDiscovery.importStreamToModelsDir] byte-progress emissions across both known
   * SAF stream lengths (`expectedTotalBytes > 0L`) and unknown SAF stream lengths
   * (`expectedTotalBytes == -1L`, where in-flight chunks emit `progressFraction == 0f` alongside
   * live non-zero `copiedBytes` rather than appearing frozen at `50%`), plus [formatByteProgressLabel].
   */
  @Test
  fun modelFileDiscovery_emitsAccurateByteProgressAndZeroFractionWhenStreamSizeUnknown() = runTest {
    val modelsDir = tempFolder.newFolder("byte_progress_models")
    val knownBytes = ByteArray(2 * 1024 * 1024 + 512 * 1024) { 9 }
    val knownEvents = mutableListOf<Triple<Long, Long, Float>>()

    // 1. Known stream size (> 0L): emits rising copiedBytes, exact totalBytes, and 0..1 fraction.
    ModelFileDiscovery.importStreamToModelsDir(
      inputStream = ByteArrayInputStream(knownBytes),
      modelsDir = modelsDir,
      sanitizedFileName = "known_size.litertlm",
      format = ModelFileFormat.LITERT_LM,
      expectedTotalBytes = knownBytes.size.toLong(),
      minSizeBytes = 1024L,
      onProgress = { copied, total, fraction -> knownEvents.add(Triple(copied, total, fraction)) },
    )
    assertTrue(knownEvents.size >= 4)
    val firstChunk = knownEvents.first()
    assertTrue(firstChunk.first > 0L)
    assertEquals(knownBytes.size.toLong(), firstChunk.second)
    assertTrue(firstChunk.third > 0f && firstChunk.third < 1f)
    assertEquals(Triple(knownBytes.size.toLong(), knownBytes.size.toLong(), 1.0f), knownEvents.last())

    // 2. Unknown SAF stream size (-1L): in-flight chunks emit fraction == 0f with copiedBytes > 0L.
    val unknownBytes = ByteArray(1024 * 1024 + 512 * 1024) { 5 }
    val unknownEvents = mutableListOf<Triple<Long, Long, Float>>()
    ModelFileDiscovery.importStreamToModelsDir(
      inputStream = ByteArrayInputStream(unknownBytes),
      modelsDir = modelsDir,
      sanitizedFileName = "unknown_size.litertlm",
      format = ModelFileFormat.LITERT_LM,
      expectedTotalBytes = -1L,
      minSizeBytes = 1024L,
      onProgress = { copied, total, fraction -> unknownEvents.add(Triple(copied, total, fraction)) },
    )
    val streamingChunks = unknownEvents.filter { it.second == -1L }
    assertTrue(streamingChunks.isNotEmpty())
    for ((copied, total, fraction) in streamingChunks) {
      assertTrue(copied > 0L)
      assertEquals(-1L, total)
      assertEquals(0f, fraction, 0.0001f)
    }
    assertEquals(Triple(unknownBytes.size.toLong(), unknownBytes.size.toLong(), 1.0f), unknownEvents.last())

    // 3. Verify human-readable byte progress labels and AiRuntimeState.isBackgroundBusy contract.
    assertEquals("Starting file copy...", formatByteProgressLabel(0L, -1L))
    assertEquals("640 MB copied...", formatByteProgressLabel(640L * 1024L * 1024L, -1L))
    assertEquals("640 MB / 1,540 MB (42%)", formatByteProgressLabel(640L * 1024L * 1024L, 1540L * 1024L * 1024L))
    assertTrue(AiRuntimeState.Discovering.isBackgroundBusy)
    assertTrue(AiRuntimeState.Importing("m.litertlm", 0.42f, 640L, 1540L).isBackgroundBusy)
    assertTrue(AiRuntimeState.Loading("m.litertlm").isBackgroundBusy)
    assertFalse(AiRuntimeState.Uninitialized.isBackgroundBusy)
  }

  /**
   * Verifies [KitchenBoardViewModel] background spinners and duplicate-tap guards:
   * 1. Suspending `preprocessImageUri` toggles `isPreprocessingImage == true` immediately with the
   *    photo label, blocks overlapping `AttachImage` calls, and resets `isPreprocessingImage == false`
   *    once optimization completes.
   * 2. Duplicate `AiChefAction.ImportModelUri` calls while `AiRuntimeState.Importing` or
   *    `AiRuntimeState.Loading` is active are rejected with an informative `toastMessage` and do NOT
   *    invoke `importAndLoadModel` a second time.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun kitchenBoardViewModel_guardsDuplicateModelImportsAndTracksImagePreprocessingState() = runTest {
    val mainDispatcher = UnconfinedTestDispatcher(testScheduler)
    Dispatchers.setMain(mainDispatcher)
    try {
      val seedDataset = SeedCatalogDataset(
        ingredients = testCatalog,
        recipes = listOf(plainToastRecipe, brainBowlRecipe),
        initialPantry = testInitialPantry,
      )
      val importReleaseGate = CompletableDeferred<AiRuntimeState>()
      val preprocessReleaseGate = CompletableDeferred<String>()
      val fakePort = FakeAiEdgePort(
        initialState = AiRuntimeState.Error("No model loaded yet"),
        importHandler = { _, displayName ->
          importReleaseGate.await().also { _ ->
            requireNotNull(displayName)
          }
        },
        emitImportingBeforeAwait = true,
      )
      val viewModel = KitchenBoardViewModel(
        seedCatalogPort = SeedCatalogPort { seedDataset },
        storagePort = FakeKitchenStorage(),
        aiEdgePort = fakePort,
        voiceInputPort = NoOpVoiceInputPort(),
        preprocessImageUri = { preprocessReleaseGate.await() },
        deleteTempImage = {},
      )

      // Cold-start hydration settles with isLoading == false and zero active background flags.
      assertFalse(viewModel.uiState.value.isLoading)
      assertFalse(viewModel.uiState.value.isAnyBackgroundWorkActive)

      // 1. AttachImage immediately flips isPreprocessingImage == true while suspended.
      viewModel.onAiChefAction(AiChefAction.AttachImage("content://picker/101", "fridge_photo.jpg"))
      assertTrue(viewModel.uiState.value.isPreprocessingImage)
      assertEquals("fridge_photo.jpg", viewModel.uiState.value.preprocessingImageLabel)
      assertTrue(viewModel.uiState.value.isAnyBackgroundWorkActive)

      // Overlapping AttachImage while preprocessing posts a friendly notice without resetting state.
      viewModel.onAiChefAction(AiChefAction.AttachImage("content://picker/102", "second.jpg"))
      assertTrue(viewModel.uiState.value.isPreprocessingImage)
      assertTrue(requireNotNull(viewModel.uiState.value.toastMessage).contains("Still optimizing"))

      // Completing preprocessing clears isPreprocessingImage and populates attachedImagePath.
      preprocessReleaseGate.complete("/cache/captures/fridge_1280.jpg")
      assertFalse(viewModel.uiState.value.isPreprocessingImage)
      assertNull(viewModel.uiState.value.preprocessingImageLabel)
      assertEquals("/cache/captures/fridge_1280.jpg", viewModel.uiState.value.attachedImagePath)

      // 2. First ImportModelUri starts background Importing; duplicate tap is blocked cleanly.
      viewModel.onAiChefAction(
        AiChefAction.ImportModelUri("content://saf/gemma4.litertlm", "gemma-4-e2b-vision.litertlm"),
      )
      assertEquals(1, fakePort.importModelCallCount)
      assertTrue(viewModel.uiState.value.isModelOperationInProgress)
      assertTrue(viewModel.uiState.value.isAnyBackgroundWorkActive)

      // Duplicate tap during Importing is rejected with toast and does NOT call importAndLoadModel again.
      viewModel.onAiChefAction(
        AiChefAction.ImportModelUri("content://saf/gemma4.litertlm", "gemma-4-e2b-vision.litertlm"),
      )
      assertEquals(1, fakePort.importModelCallCount)
      assertTrue(requireNotNull(viewModel.uiState.value.toastMessage).contains("already running"))

      // Transition to GPU Loading and verify duplicate tap is STILL blocked without a second call.
      fakePort.setRuntimeState(AiRuntimeState.Loading("gemma-4-e2b-vision.litertlm"))
      assertTrue(viewModel.uiState.value.isModelOperationInProgress)
      viewModel.onAiChefAction(
        AiChefAction.ImportModelUri("content://saf/gemma4.litertlm", "gemma-4-e2b-vision.litertlm"),
      )
      assertEquals(1, fakePort.importModelCallCount)
      assertTrue(requireNotNull(viewModel.uiState.value.toastMessage).contains("already running"))

      // Finish loading -> Ready state clears busy indicators and announces readiness.
      val readyState = AiRuntimeState.Ready(
        RuntimeCapabilities(true, true, "LiteRT-LM GPU + Vision", "gemma-4-e2b-vision.litertlm"),
      )
      fakePort.setRuntimeState(readyState)
      importReleaseGate.complete(readyState)
      assertFalse(viewModel.uiState.value.isModelOperationInProgress)
      assertFalse(viewModel.uiState.value.isAnyBackgroundWorkActive)
      assertTrue(requireNotNull(viewModel.uiState.value.toastMessage).contains("ready!"))
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * Reproduces the user's exact Spice Rack photo scan end-to-end across **both** Gemma Vision JSON
   * shapes (`detectedItems` with `matchedIngredientId = null` AND `summary`/`unrecognizedLabels`
   * fallback), verifying synthesized `PANTRY_STAPLE` / `TIER_2_STAPLE` catalog items (excluding
   * non-food `"Ceramic Coffee Mug"`), `O(1)` batch commits via [AiChefAction.ConfirmVisualRestock]
   * and [AiChefAction.CommitVisualIntakeSelection], `O(1)` exact snapshot restoration via
   * [AiChefAction.UndoLastAgentAction] / [KitchenBoardViewModel.undoLastAgentAction], and expanded
   * text-only agentic tool execution (`restock_pantry_batch`, `deplete_pantry_items`,
   * `queue_grocery_items`, `schedule_family_meal`).
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun spicePhotoScanSelectiveCommitExpandedToolsAndUndo_commitAndRestoreInSingleO1Writes() = runTest {
    val mainDispatcher = UnconfinedTestDispatcher(testScheduler)
    Dispatchers.setMain(mainDispatcher)
    try {
      val exactSpiceSummary =
        "The image displays several jars and bottles containing spices and herbs, " +
          "including ground turmeric, bay leaves, and flakes of parsley, oregano"
      val expectedSpiceIds = setOf(
        "ing_custom_ground_turmeric", "ing_custom_bay_leaves",
        "ing_custom_parsley_flakes", "ing_custom_dried_oregano",
      )
      val seedDataset = SeedCatalogDataset(testCatalog, listOf(plainToastRecipe, brainBowlRecipe), testInitialPantry)
      val storage = FakeKitchenStorage()
      val fakePort = FakeAiEdgePort(
        AiRuntimeState.Ready(RuntimeCapabilities(true, true, "LiteRT-LM GPU + Vision", "gemma-e2b-it-multimodal-int4.litertlm")),
      )
      val viewModel = KitchenBoardViewModel(SeedCatalogPort { seedDataset }, storage, fakePort, NoOpVoiceInputPort())

      // 1. Summary/unrecognized fallback shape: Gemma lists spices in summary + Ceramic Coffee Mug.
      fakePort.visionResponder = { _, _ ->
        """{"summary":"$exactSpiceSummary","detectedItems":[],"unrecognizedLabels":["Ceramic Coffee Mug"]}"""
      }
      val fallbackPhoto = createSimulatedCaptureFile("spice_rack_summary_fallback.jpg")
      viewModel.onAiChefAction(AiChefAction.SubmitPrompt("", fallbackPhoto.absolutePath))
      assertFalse(fallbackPhoto.exists())

      val turnA = viewModel.uiState.value.snapshot.aiTurnHistory.last()
      assertFalse(turnA.messageText.contains("No known catalog items"))
      val intakeA = requireNotNull(turnA.visualIntakeResult)
      assertEquals(expectedSpiceIds, intakeA.proposedNewCatalogItems.map { it.ingredientId }.toSet())
      assertTrue(intakeA.proposedNewCatalogItems.all {
        it.category == IngredientCategory.PANTRY_STAPLE && it.tier == IngredientTier.TIER_2_STAPLE
      })
      assertTrue(intakeA.proposedNewCatalogItems.none { it.displayName.contains("Mug", ignoreCase = true) })

      // ConfirmVisualRestock adds all 4 spices as PLENTY in 1 O(1) write; Undo restores exact prior state.
      val snapshotBeforeConfirm = viewModel.uiState.value.snapshot
      storage.resetWriteCounter()
      viewModel.onAiChefAction(AiChefAction.ConfirmVisualRestock(intakeA))
      assertEquals(1, storage.batchWriteCallCount)
      assertTrue(expectedSpiceIds.all { id ->
        id in viewModel.uiState.value.snapshot.catalogById &&
          viewModel.uiState.value.snapshot.pantryById[id]?.stapleStatus == StapleStatus.PLENTY
      })
      assertTrue(viewModel.uiState.value.canUndoLastAgentAction)
      assertEquals(TopLevelDestination.PANTRY, viewModel.uiState.value.lastAgentActionAdjustTarget)

      storage.resetWriteCounter()
      viewModel.onAiChefAction(AiChefAction.UndoLastAgentAction)
      assertEquals(1, storage.batchWriteCallCount)
      assertEquals(snapshotBeforeConfirm, viewModel.uiState.value.snapshot)
      assertFalse(viewModel.uiState.value.canUndoLastAgentAction)

      // 2. detectedItems shape (matchedIngredientId = null) + CommitVisualIntakeSelection + undoLastAgentAction().
      fakePort.visionResponder = { _, _ ->
        """
        {
          "summary": "$exactSpiceSummary",
          "detectedItems": [
            {"rawLabel": "ground turmeric", "matchedIngredientId": null, "estimatedQuantity": 0, "confidence": 0.96},
            {"rawLabel": "bay leaves", "matchedIngredientId": null, "estimatedQuantity": 0, "confidence": 0.94},
            {"rawLabel": "flakes of parsley", "matchedIngredientId": null, "estimatedQuantity": 0, "confidence": 0.92},
            {"rawLabel": "oregano", "matchedIngredientId": null, "estimatedQuantity": 0, "confidence": 0.93}
          ],
          "unrecognizedLabels": ["Ceramic Coffee Mug"]
        }
        """.trimIndent()
      }
      val detectedPhoto = createSimulatedCaptureFile("spice_rack_detected_items.jpg")
      viewModel.onAiChefAction(AiChefAction.SubmitPrompt("", detectedPhoto.absolutePath))
      assertFalse(detectedPhoto.exists())

      val turnB = viewModel.uiState.value.snapshot.aiTurnHistory.last()
      val intakeB = requireNotNull(turnB.visualIntakeResult)
      assertEquals(expectedSpiceIds, intakeB.proposedNewCatalogItems.map { it.ingredientId }.toSet())
      assertEquals(
        listOf("Ground Turmeric", "Bay Leaves", "Parsley Flakes", "Dried Oregano"),
        intakeB.proposedNewCatalogItems.map { it.displayName },
      )

      val snapshotBeforeSelective = viewModel.uiState.value.snapshot
      storage.resetWriteCounter()
      viewModel.onAiChefAction(
        AiChefAction.CommitVisualIntakeSelection(turnB.turnId, intakeB, expectedSpiceIds, emptyList(), IntakeCommitTarget.PANTRY),
      )
      assertEquals(1, storage.batchWriteCallCount)
      assertTrue(expectedSpiceIds.all { viewModel.uiState.value.snapshot.pantryById[it]?.stapleStatus == StapleStatus.PLENTY })

      storage.resetWriteCounter()
      viewModel.undoLastAgentAction()
      assertEquals(1, storage.batchWriteCallCount)
      assertEquals(snapshotBeforeSelective, viewModel.uiState.value.snapshot)

      // 3. Expanded text-only agentic tools commit in 1 O(1) write each without calling LLM generateText.
      val textCoordinator = AiChefCoordinator(aiEdgePort = fakePort)
      val stockedMix = textCoordinator.submitTurn(
        viewModel.uiState.value.snapshot, "Add ground turmeric, bay leaves, and eggs to my pantry",
      )
      assertEquals(AiTurnResolutionStatus.DETERMINISTIC_TOOL_EXECUTED, stockedMix.status)
      assertEquals(18.0, requireNotNull(stockedMix.updatedSnapshot.pantryById["ing_egg"]).currentQuantity, 0.001)
      assertEquals(StapleStatus.PLENTY, requireNotNull(stockedMix.updatedSnapshot.pantryById["ing_custom_ground_turmeric"]).stapleStatus)
      assertEquals(StapleStatus.PLENTY, requireNotNull(stockedMix.updatedSnapshot.pantryById["ing_custom_bay_leaves"]).stapleStatus)

      val depletedOil = textCoordinator.submitTurn(stockedMix.updatedSnapshot, "We ran out of olive oil")
      assertEquals(StapleStatus.OUT, requireNotNull(depletedOil.updatedSnapshot.pantryById["ing_olive_oil"]).stapleStatus)

      val queuedCalamansi = textCoordinator.submitTurn(stockedMix.updatedSnapshot, "Add calamansi to our grocery list")
      assertTrue("ing_custom_calamansi" in queuedCalamansi.updatedSnapshot.aiAddedGroceryIds)

      val swappedDinner = textCoordinator.submitTurn(stockedMix.updatedSnapshot, "Swap tonight's dinner", activeDayIndex = 0)
      assertEquals("schedule_family_meal", requireNotNull(swappedDinner.assistantTurn.toolCallResult).toolName)
      assertEquals(0, fakePort.generateTextCallCount)
    } finally {
      Dispatchers.resetMain()
    }
  }

  private fun createSimulatedCaptureFile(fileName: String): File =
    tempFolder.newFile(fileName).apply {
      writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
    }

  /** Minimal pure-Kotlin [VoiceInputPort] test double with zero Android platform dependencies. */
  private class NoOpVoiceInputPort : VoiceInputPort {
    private val mutableVoice = MutableStateFlow<VoiceInputState>(VoiceInputState.Idle)
    override val voiceState: StateFlow<VoiceInputState> = mutableVoice.asStateFlow()
    override fun startListening(languageTag: String): Boolean = false
    override fun stopListening() = Unit
    override fun release() = Unit
  }

  /**
   * Stateful in-memory [AiEdgePort] test double enforcing identical [ModelNotReadyException] and
   * [VisionNotSupportedException] contracts to `LiteRtMultimodalEngine`.
   */
  private class FakeAiEdgePort(
    initialState: AiRuntimeState,
    var textResponder: (String) -> String = { "{}" },
    var visionResponder: (String, String) -> String = { _, _ -> "{}" },
    private val importHandler: (suspend (String, String?) -> AiRuntimeState)? = null,
    private val emitImportingBeforeAwait: Boolean = false,
  ) : AiEdgePort {
    private val mutableState = MutableStateFlow(initialState)
    override val runtimeState: StateFlow<AiRuntimeState> = mutableState.asStateFlow()

    var importModelCallCount: Int = 0
    var generateTextCallCount: Int = 0
    var analyzeImageCallCount: Int = 0
    val capturedImagePaths: MutableList<String> = mutableListOf()
    val capturedVisionPrompts: MutableList<String> = mutableListOf()

    fun setRuntimeState(next: AiRuntimeState) {
      mutableState.value = next
    }

    override suspend fun autoDiscoverAndInitialize(): AiRuntimeState = mutableState.value

    override suspend fun importAndLoadModel(sourceUriString: String, displayName: String?): AiRuntimeState {
      importModelCallCount += 1
      if (emitImportingBeforeAwait) {
        mutableState.value = AiRuntimeState.Importing(
          fileName = displayName ?: "model.litertlm",
          progressFraction = 0.42f,
          copiedBytes = 640L * 1024L * 1024L,
          totalBytes = 1540L * 1024L * 1024L,
        )
      }
      val customHandler = importHandler
      return if (customHandler != null) customHandler(sourceUriString, displayName) else mutableState.value
    }

    override suspend fun generateText(prompt: String): String {
      val current = mutableState.value
      if (current !is AiRuntimeState.Ready) {
        throw ModelNotReadyException(runtimeStatus = current::class.simpleName ?: "UNINITIALIZED")
      }
      generateTextCallCount += 1
      return textResponder(prompt)
    }

    override suspend fun analyzeImageWithPrompt(preprocessedImagePath: String, prompt: String): String {
      analyzeImageCallCount += 1
      val current = mutableState.value
      if (current !is AiRuntimeState.Ready) {
        throw ModelNotReadyException(runtimeStatus = current::class.simpleName ?: "UNINITIALIZED")
      }
      // Mirror LiteRtMultimodalEngine's strict non-silent Vision Gate whenever !supportsVision.
      if (!current.capabilities.supportsVision) {
        throw VisionNotSupportedException(
          activeBackendLabel = current.capabilities.backendLabel,
          modelName = current.capabilities.modelName,
        )
      }
      capturedImagePaths.add(preprocessedImagePath)
      capturedVisionPrompts.add(prompt)
      return visionResponder(preprocessedImagePath, prompt)
    }

    override fun close() = Unit
  }
}
