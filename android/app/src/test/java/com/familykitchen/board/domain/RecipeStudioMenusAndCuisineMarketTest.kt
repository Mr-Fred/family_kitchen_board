package com.familykitchen.board.domain

import com.familykitchen.board.ai.AiChefCoordinator
import com.familykitchen.board.data.FakeKitchenStorage
import com.familykitchen.board.ports.AiEdgePort
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ports.FixedKitchenClock
import com.familykitchen.board.ports.KitchenSnapshot
import com.familykitchen.board.ports.RuntimeCapabilities
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// Suite 16: Hermetic JVM JUnit 4 verification of Preferred Family Cuisines (French + Yoruba and
// West African), Antipolo-Marikina PHP market defaults, Saved Menu ranking & EC-02/EC-03 remixing,
// 3-Mode Recipe Studio (Smart Paste 0-phantom-stock, Cookbook Vision OCR, Fork, & Custom Delete).

/**
 * Suite 16 hermetic JUnit 4 verification suite covering Preferred Family Cuisines, Saved Menus,
 * local-market grounding, and the 3-Mode Recipe Studio:
 * 1. Default [HouseholdProfile] (`preferredCuisines == listOf("French", "Yoruba and West African")`,
 *    `HouseholdCurrency.PHP` `₱`, `"Antipolo & Marikina, PH"`) and `42` total household recipes,
 * 2. [rankMenusForHousehold] ranking *`French and Yoruba Family Week`* `#1` by default, plus
 *    [scoreRecipeForHouseholdAndMarket] & [applyMenuTemplateToBoard] adapting BOMs via `EC-03`
 *    (*Fresh Kulitis* / *Saba Cooking Bananas*) while blocking/remixing `EC-02` unavailable items,
 * 3. [parseRawRecipeTextToDraft] + [materializeRecipeFromDraft] initializing new ingredients at
 *    `0.0` / `StapleStatus.OUT` (`0` phantom `500g` stock bug!) so scheduling immediately surfaces
 *    shopping deficits on [WeeklyGroceryDiff],
 * 4. [parseCookbookVisionJsonToDraft] + [AiChefCoordinator.scanCookbookRecipePhoto] enforcing the
 *    Non-Silent Vision Gate and guaranteed temporary JPEG cleanup,
 * 5. [forkRecipeForHousehold] (`parentRecipeId` lineage) and [removeCustomRecipeFromBoardAndList]
 *    protecting canonical `rec_01..rec_42` dishes while deleting custom/forked dishes in `O(1)`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeStudioMenusAndCuisineMarketTest {

  /** Temporary folder verifying guaranteed temp cookbook photo deletion across success and failure. */
  @get:Rule
  val tempFolder: TemporaryFolder = TemporaryFolder()

  // Configurable AiEdgePort double testing both multimodal Cookbook OCR JSON and Vision Gate errors.
  private class CookbookOcrEdgeDouble(
    private val supportsVision: Boolean,
    private val visionResponseJson: String = "{}",
  ) : AiEdgePort {
    private val mutableState = MutableStateFlow<AiRuntimeState>(
      AiRuntimeState.Ready(
        capabilities = RuntimeCapabilities(
          supportsText = true,
          supportsVision = supportsVision,
          backendLabel = if (supportsVision) "LiteRT-LM Vision" else "TEXT_ONLY",
          modelName = if (supportsVision) "gemma-4-e2b-it" else "gemma-text-only",
        ),
      ),
    )
    override val runtimeState: StateFlow<AiRuntimeState> = mutableState.asStateFlow()
    override suspend fun autoDiscoverAndInitialize(): AiRuntimeState = mutableState.value
    override suspend fun importAndLoadModel(sourceUriString: String, displayName: String?): AiRuntimeState = mutableState.value
    override suspend fun generateText(prompt: String): String = "{}"
    override suspend fun analyzeImageWithPrompt(preprocessedImagePath: String, prompt: String): String {
      if (!supportsVision) {
        throw VisionNotSupportedException(modelName = "gemma-text-only")
      }
      return visionResponseJson
    }
    override fun close() = Unit
  }

  // Minimal offline VoiceInputPort stub for ViewModel Recipe Studio verification.
  private class SilentVoicePort : VoiceInputPort {
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
   * Verifies default [HouseholdProfile] cuisines (`French` + `Yoruba and West African`), `PHP` (`₱`)
   * Antipolo–Marikina market defaults, `42` merged household recipes, and `#1` ranking of
   * *`French and Yoruba Family Week`* in [rankMenusForHousehold].
   */
  @Test
  fun householdDefaultsAndMenuRanking_prioritizeFrenchAndYorubaFamilyWeekInAntipoloMarket() {
    val seed = loadCanonicalSeed()
    val defaultProfile = HouseholdProfile()

    // Confirm default cuisines, Philippine Peso currency, and Antipolo & Marikina market region.
    assertEquals(listOf("French", "Yoruba and West African"), defaultProfile.preferredCuisines)
    assertEquals(HouseholdCurrency.PHP, defaultProfile.currency)
    assertEquals("₱", defaultProfile.currency.symbol)
    assertEquals("Antipolo & Marikina, PH", defaultProfile.marketRegionLabel)

    // Confirm KitchenSnapshot merges all 12 curated heritage recipes alongside the 30 seed recipes.
    val snapshot = KitchenSnapshot.fromSeedCatalog(seed)
    assertEquals(12, HERITAGE_AND_WORLD_RECIPES.size)
    assertEquals(42, snapshot.allHouseholdRecipes.size)
    assertTrue(HERITAGE_AND_WORLD_RECIPES.all { !it.toddlerPrepTip.isNullOrBlank() && it.baseServings == 4 })

    // Verify rankMenusForHousehold places French and Yoruba Family Week #1 for default household.
    val rankedDefault = rankMenusForHousehold(DEFAULT_STARTER_MENU_TEMPLATES, defaultProfile)
    assertEquals("menu_french_yoruba_family", rankedDefault.first().menuId)
    assertEquals("French and Yoruba Family Week", rankedDefault.first().title)

    // Switching preferredCuisines to Filipino promotes Local Market Zero Waste Week to #1.
    val filipinoProfile = defaultProfile.copy(preferredCuisines = listOf("Filipino"))
    val rankedFilipino = rankMenusForHousehold(DEFAULT_STARTER_MENU_TEMPLATES, filipinoProfile)
    assertEquals("menu_local_market_zero_waste", rankedFilipino.first().menuId)
  }

  /**
   * Verifies [scoreRecipeForHouseholdAndMarket] and [applyMenuTemplateToBoard] prioritize French &
   * Yoruba recipes while adapting BOMs via `EC-03` (*Fresh Kulitis* & *Saba Cooking Bananas*) and
   * blocking/remixing `EC-02` unavailable ingredients that lack substitutes.
   */
  @Test
  fun cuisineScoringAndMenuApplication_adaptLocalMarketSubstitutesAndBlockEc02UnavailableItems() {
    val seed = loadCanonicalSeed()
    val allRecipes = mergeHeritageRecipesIntoLibrary(seed.recipes)
    val profile = HouseholdProfile()

    // Enroll Saba Cooking Bananas from LOCAL_MARKET_PRESET_INGREDIENTS and mark Baby Spinach & Plantains unavailable.
    val sabaPreset = LOCAL_MARKET_PRESET_INGREDIENTS.first { it.ingredientId == "ing_saba_banana" }
    val adaptedCatalog = (seed.ingredients + sabaPreset).map { item ->
      when (item.ingredientId) {
        "ing_spinach", "ing_plantain" -> item.copy(isLocallyAvailable = false)
        else -> item
      }
    }
    val catalogById = indexCatalogById(adaptedCatalog)
    val pantryById = indexPantryById(seed.initialPantry)

    // EC-03 check 1: Ratatouille uses ing_spinach (LEAFY_GREENS) -> resolves to Fresh Kulitis (ing_amaranth_greens).
    val ratatouille = allRecipes.first { it.recipeId == "rec_38_ratatouille_provencale_baked_eggs" }
    val ratatouilleEval = evaluateRecipeAvailability(ratatouille, catalogById, emptyList(), pantryById)
    assertTrue(ratatouilleEval.isSchedulable)
    assertTrue(ratatouilleEval.resolvedBomLines.any { it.ingredientId == "ing_amaranth_greens" })

    // EC-03 check 2: Sweet Plantain Dodo uses ing_plantain (FRUIT_TOPPING) -> resolves to Saba Bananas (ing_saba_banana).
    val dodo = allRecipes.first { it.recipeId == "rec_35_golden_dodo_tomato_egg_sauce" }
    val dodoEval = evaluateRecipeAvailability(dodo, catalogById, emptyList(), pantryById)
    assertTrue(dodoEval.isSchedulable)
    assertTrue(dodoEval.resolvedBomLines.any { it.ingredientId == "ing_saba_banana" })

    // Preferred cuisine scoring: Poulet Basquaise (French) outscores non-preferred Pasta Spinach.
    val pouletBasquaise = allRecipes.first { it.recipeId == "rec_37_poulet_basquaise" }
    val garlicPasta = allRecipes.first { it.recipeId == "rec_21_creamy_garlic_pasta_spinach" }
    val frenchScore = scoreRecipeForHouseholdAndMarket(pouletBasquaise, profile, catalogById, pantryById) ?: 0
    val nonPrefScore = scoreRecipeForHouseholdAndMarket(garlicPasta, profile, catalogById, pantryById) ?: 0
    assertTrue(frenchScore > nonPrefScore)

    // EC-02 hard block: marking Red Snapper (ing_fish, no substituteGroupId on Ata Din-Din) unavailable returns null score.
    val fishBlockedCatalog = adaptedCatalog.map {
      if (it.ingredientId == "ing_fish") it.copy(isLocallyAvailable = false) else it
    }
    val ataDinDin = allRecipes.first { it.recipeId == "rec_32_yoruba_ata_dindin_fish" }
    assertNull(
      scoreRecipeForHouseholdAndMarket(
        recipe = ataDinDin,
        profile = profile,
        catalogById = indexCatalogById(fishBlockedCatalog),
        pantryById = pantryById,
      ),
    )

    // Applying French and Yoruba Family Week with ing_fish unavailable automatically remixes Thursday Dinner cleanly.
    val template = DEFAULT_STARTER_MENU_TEMPLATES.first { it.menuId == "menu_french_yoruba_family" }
    val appliedBoard = applyMenuTemplateToBoard(
      currentBoard = WeeklyBoard("board_menu_test", profile.householdId, "2026-11-23", emptyList()),
      template = template,
      recipes = allRecipes,
      catalog = fishBlockedCatalog,
      pantryEntries = seed.initialPantry,
      profile = profile,
      remixUnavailableOrMissing = true,
      newWeekStartDateIso = "2026-11-23",
    )
    assertEquals(21, appliedBoard.slots.size)
    assertFalse(appliedBoard.slots.any { it.recipeId == "rec_32_yoruba_ata_dindin_fish" })
  }

  /**
   * Verifies [parseRawRecipeTextToDraft] + [materializeRecipeFromDraft] initialize newly matched
   * preset/custom ingredients at `0.0` / `StapleStatus.OUT` (`0` phantom stock!), so scheduling the
   * recipe immediately queues missing packages on [WeeklyGroceryDiff].
   */
  @Test
  fun smartPasteAndMaterialize_initializeNewIngredientsAtZeroStockAndQueueOnGroceryList() {
    val seed = loadCanonicalSeed()
    val rawText = """
      Ginataang Hipon with Malunggay
      Servings: 4
      Prep: 10 min
      Cook: 15 min
      Ingredients:
      - 500 g fresh hipon shrimp
      - 400 ml fresh gata coconut milk
      - 200 g malunggay leaves
      - 10 g fresh ginger
      Instructions:
      1. Simmer fresh gata coconut milk with sliced ginger for 4 minutes.
      2. Add hipon shrimp and cook until pink and opaque.
      3. Fold in malunggay leaves for 2 minutes before serving warm.
      Toddler: Peel shrimp tails completely and chop tender bite-sized pieces for 2yo.
    """.trimIndent()

    // Parse free-form text and verify regional Tagalog synonyms match LOCAL_MARKET_PRESET_INGREDIENTS.
    val draft = parseRawRecipeTextToDraft(rawText, seed.ingredients, listOf("French", "Yoruba and West African"))
    assertEquals("Ginataang Hipon with Malunggay", draft.title)
    assertEquals(4, draft.servings)
    assertEquals(4, draft.ingredientLines.size)
    assertEquals(3, draft.stepInstructions.size)

    // Materialize against household seed catalog + pantry; newly added hipon/gata/malunggay MUST start at 0.0 / OUT!
    val materialized = materializeRecipeFromDraft(
      draft = draft,
      existingCatalog = seed.ingredients,
      existingPantry = seed.initialPantry,
      nowIso = "2026-11-23T09:00:00Z",
    )
    assertTrue(materialized.recipe.isUserCreated)
    for (newIngId in listOf("ing_shrimp", "ing_gata", "ing_malunggay_pechay")) {
      val pantryEntry = materialized.updatedPantry.first { it.ingredientId == newIngId }
      assertEquals(0.0, pantryEntry.currentQuantity, 0.001)
      assertEquals(StapleStatus.OUT, pantryEntry.stapleStatus)
      assertTrue(pantryEntry.isOutOfStock)
      assertNull(pantryEntry.expiresInDays)
    }

    // Schedule the materialized recipe onto Monday Dinner and verify Shop immediately surfaces all 3 missing items!
    val singleSlotBoard = WeeklyBoard(
      boardId = "board_paste",
      householdId = "household_family_4",
      weekStartDateIso = "2026-11-23",
      slots = listOf(
        MealSlot("slot_mon_d", 0, MealSlotType.DINNER, CookOwner.WIFE, materialized.recipe.recipeId, targetServings = 4),
      ),
    )
    val diff = GroceryDiffEngine.computeWeeklyGroceryDiff(
      board = singleSlotBoard,
      recipes = listOf(materialized.recipe),
      catalog = materialized.updatedCatalog,
      pantry = materialized.updatedPantry,
    )
    val groceryIds = diff.groceryItems.map { it.ingredientId }.toSet()
    assertTrue("ing_shrimp" in groceryIds)
    assertTrue("ing_gata" in groceryIds)
    assertTrue("ing_malunggay_pechay" in groceryIds)
  }

  /**
   * Verifies [parseCookbookVisionJsonToDraft], [AiChefCoordinator.scanCookbookRecipePhoto]
   * Non-Silent Vision Gate + temp file cleanup, [forkRecipeForHousehold], and
   * [removeCustomRecipeFromBoardAndList] via [KitchenBoardViewModel].
   */
  @Test
  fun cookbookVisionOcrForkAndDelete_enforceVisionGateCleanTempFilesAndProtectBuiltInRecipes() = runTest {
    val testDispatcher = UnconfinedTestDispatcher(testScheduler)
    Dispatchers.setMain(testDispatcher)
    try {
      val seed = loadCanonicalSeed()
      val snapshot = KitchenSnapshot.fromSeedCatalog(seed)

      // 1. Vision-capable Cookbook OCR transcribes JSON into SmartRecipeDraft and deletes temp JPEG.
      val tempPhotoOk = tempFolder.newFile("cookbook_bistro_card.jpg").apply { writeText("fake_jpeg_bytes") }
      val cleanedPaths = mutableListOf<String>()
      val ocrJson = """
        {"title":"Bistro Omelette Printaniere","servings":4,"rawPrepMinutes":5,"cookMinutes":8,
         "allowedSlots":["BREAKFAST","LUNCH"],"cuisineTags":["French"],
         "ingredients":["5 farm eggs","20 g butter","100 g baby spinach"],
         "steps":["Whisk eggs smoothly.","Fold spinach and cook omelette firm through center."],
         "toddlerPrepTip":"Cook egg center completely firm and slice soft strips for 2yo."}
      """.trimIndent()
      val visionCoordinator = AiChefCoordinator(
        aiEdgePort = CookbookOcrEdgeDouble(supportsVision = true, visionResponseJson = ocrJson),
        onCleanupTempImage = { path ->
          cleanedPaths.add(path)
          java.io.File(path).delete()
        },
      )
      val scannedDraft = visionCoordinator.scanCookbookRecipePhoto(snapshot, tempPhotoOk.absolutePath)
      assertEquals("Bistro Omelette Printaniere", scannedDraft.title)
      assertTrue(MealSlotType.BREAKFAST in scannedDraft.allowedSlots)
      assertFalse(tempPhotoOk.exists())
      assertEquals(listOf(tempPhotoOk.absolutePath), cleanedPaths)

      // 2. Non-Silent Vision Gate: text-only model throws VisionNotSupportedException AND still deletes temp JPEG.
      val tempPhotoBlocked = tempFolder.newFile("cookbook_blocked.jpg").apply { writeText("fake_jpeg_bytes") }
      val textOnlyCoordinator = AiChefCoordinator(
        aiEdgePort = CookbookOcrEdgeDouble(supportsVision = false),
        onCleanupTempImage = { path -> java.io.File(path).delete() },
      )
      try {
        textOnlyCoordinator.scanCookbookRecipePhoto(snapshot, tempPhotoBlocked.absolutePath)
        fail("Expected VisionNotSupportedException when scanning cookbook photo without vision support.")
      } catch (expected: VisionNotSupportedException) {
        assertNotNull(expected.message)
      }
      assertFalse(tempPhotoBlocked.exists())

      // 3. Fork a built-in French recipe in ViewModel, save in 1 O(1) write, and verify built-in deletion protection.
      val storage = FakeKitchenStorage(initialSnapshot = snapshot)
      val viewModel = KitchenBoardViewModel(
        seedCatalogPort = SeedCatalogPort { seed },
        storagePort = storage,
        aiEdgePort = CookbookOcrEdgeDouble(supportsVision = true, visionResponseJson = ocrJson),
        voiceInputPort = SilentVoicePort(),
        clockPort = FixedKitchenClock("2026-11-23", currentHour24 = 10),
      )
      val builtInPoulet = viewModel.uiState.value.snapshot.allHouseholdRecipes.first {
        it.recipeId == "rec_37_poulet_basquaise"
      }
      storage.resetWriteCounter()

      // Built-in canonical recipes (rec_01..rec_42) cannot be deleted.
      viewModel.deleteCustomRecipe(builtInPoulet.recipeId)
      assertEquals(0, storage.batchWriteCallCount)
      assertEquals(42, viewModel.uiState.value.snapshot.allHouseholdRecipes.size)

      // Fork Poulet Basquaise -> "Make Our Version", schedule onto Tuesday Dinner, and save in 1 O(1) write.
      viewModel.openForkRecipeStudio(builtInPoulet)
      val forkDraft = viewModel.uiState.value.editingRecipeDraft ?: error("Expected fork draft")
      viewModel.saveSmartRecipeDraft(forkDraft, scheduleDayIndex = 1, scheduleSlotType = MealSlotType.DINNER)
      assertEquals(1, storage.batchWriteCallCount)
      val savedFork = viewModel.uiState.value.snapshot.allHouseholdRecipes.first {
        it.parentRecipeId == "rec_37_poulet_basquaise"
      }
      assertTrue(savedFork.isUserCreated)
      assertTrue(viewModel.uiState.value.snapshot.weeklyBoard.slots.any { it.recipeId == savedFork.recipeId })

      // Deleting the custom fork removes both the recipe and its PLANNED slot in 1 O(1) write while keeping built-in intact.
      viewModel.deleteCustomRecipe(savedFork.recipeId)
      assertEquals(2, storage.batchWriteCallCount)
      assertFalse(viewModel.uiState.value.snapshot.allHouseholdRecipes.any { it.recipeId == savedFork.recipeId })
      assertFalse(viewModel.uiState.value.snapshot.weeklyBoard.slots.any { it.recipeId == savedFork.recipeId })
      assertTrue(viewModel.uiState.value.snapshot.allHouseholdRecipes.any { it.recipeId == "rec_37_poulet_basquaise" })
    } finally {
      Dispatchers.resetMain()
    }
  }
}
