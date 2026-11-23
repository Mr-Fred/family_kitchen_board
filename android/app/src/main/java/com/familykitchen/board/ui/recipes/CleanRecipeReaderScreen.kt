package com.familykitchen.board.ui.recipes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.HouseholdProfile
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.MealSlotType
import com.familykitchen.board.domain.PantryExpiryEngine
import com.familykitchen.board.domain.PantryLedgerEntry
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.RestockDelta
import com.familykitchen.board.domain.isWeekdayBreakfastEligible
import com.familykitchen.board.ui.preview.PreviewFixtures
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import kotlin.math.max
import kotlinx.coroutines.delay

// Top-level Clean Recipe Reader stateless screen orchestrator wiring ReaderTopBackBar (`All Recipes`),
// CookbookHeaderAndHero, ownership remix/edit/delete pills, and 1-tap `Swap Ingredient` BOM pickers.

/**
 * Stateless `CleanRecipeReaderScreen` implementing the distraction-free Clean Recipe Reader
 * (`recipe_reader`) with an `All Recipes` top back button, `Make Our Version` / `Edit Recipe` /
 * `Delete Recipe` action pills, and 1-tap `Swap Ingredient` BOM pickers.
 *
 * @param recipes Household recipe collection.
 * @param catalog Household ingredient catalog.
 * @param pantryEntries Household pantry stock ledger.
 * @param householdProfile Active household dietary and child-safety profile.
 * @param selectedRecipeId Currently focused `recipeId`.
 * @param savedRecipeIds Persisted bookmarked recipe IDs.
 * @param checkedIngredientsByRecipeId Mise-en-place checked BOM IDs keyed by `recipeId`.
 * @param recipeNotesByRecipeId Persisted household kitchen notes keyed by `recipeId`.
 * @param searchQuery Active search query string.
 * @param defaultPlanDayIndex Default weekday index (`0..6`) for inline `Plan` scheduling.
 * @param onSelectRecipe Invoked when switching recipes via the horizontal pill strip.
 * @param onToggleSavedRecipe Invoked when toggling a recipe bookmark.
 * @param onToggleIngredientChecked Invoked when checking or unchecking a BOM ingredient row.
 * @param onResetCheckedIngredients Invoked when tapping `Uncheck all`.
 * @param onQueueMissingIngredients Invoked when queueing missing BOM items onto `Shop`.
 * @param onAddKitchenNote Invoked when saving a new household kitchen note.
 * @param onScheduleRecipeToPlan Invoked when confirming inline `(recipe, dayIndex, slotType)` scheduling.
 * @param onStartCookMode Invoked to launch Step-by-Step Cook Mode (`handsFreeVoiceActive` flag).
 * @param onOpenCustomMealBuilder Invoked when tapping `Add Recipe` or `Custom Meal`.
 * @param onEditFamilyGoals Invoked when tapping `Customize Household Goals`.
 * @param onShareRecipe Invoked after triggering offline `ACTION_SEND` recipe sharing.
 * @param showAllRecipesBackButton Displays the top `All Recipes` back bar when `true`.
 * @param onBackToCookbookGrid Invoked when tapping `All Recipes` to return to [CookbookGridScreen].
 * @param onForkRecipe Invoked with the seed [Recipe] when tapping `Make Our Version`.
 * @param onEditRecipe Invoked with a household-created [Recipe] when tapping `Edit Recipe`.
 * @param onDeleteRecipe Invoked with `recipeId` when tapping `Delete Recipe`.
 * @param onSwapRecipeIngredient Invoked with `(recipeId, fromIngredientId, toIngredientId)` on BOM swap.
 * @param modifier Optional Compose [Modifier].
 */
@Composable
fun CleanRecipeReaderScreen(
  recipes: List<Recipe>,
  catalog: List<IngredientCatalogItem>,
  pantryEntries: List<PantryLedgerEntry>,
  householdProfile: HouseholdProfile = HouseholdProfile(),
  selectedRecipeId: String? = null,
  savedRecipeIds: Set<String> = emptySet(),
  checkedIngredientsByRecipeId: Map<String, Set<String>> = emptyMap(),
  recipeNotesByRecipeId: Map<String, List<String>> = emptyMap(),
  searchQuery: String = "",
  defaultPlanDayIndex: Int = 2,
  onSelectRecipe: (String) -> Unit = {},
  onToggleSavedRecipe: (String) -> Unit = {},
  onToggleIngredientChecked: (recipeId: String, ingredientId: String) -> Unit = { _, _ -> },
  onResetCheckedIngredients: (recipeId: String) -> Unit = {},
  onQueueMissingIngredients: (ingredientIds: Set<String>, deltas: List<RestockDelta>) -> Unit = { _, _ -> },
  onAddKitchenNote: (recipeId: String, noteText: String) -> Unit = { _, _ -> },
  onScheduleRecipeToPlan: (recipe: Recipe, dayIndex: Int, slotType: MealSlotType) -> Unit = { _, _, _ -> },
  onStartCookMode: (recipe: Recipe, handsFreeVoiceActive: Boolean) -> Unit = { _, _ -> },
  onOpenCustomMealBuilder: () -> Unit = {},
  onEditFamilyGoals: () -> Unit = {},
  onShareRecipe: (Recipe) -> Unit = {},
  showAllRecipesBackButton: Boolean = true,
  onBackToCookbookGrid: () -> Unit = {},
  onForkRecipe: (Recipe) -> Unit = {},
  onEditRecipe: (Recipe) -> Unit = {},
  onDeleteRecipe: (String) -> Unit = {},
  onSwapRecipeIngredient: (String, String, String) -> Unit = { _, _, _ -> },
  modifier: Modifier = Modifier,
) {
  // Resolve sharing context and O(1) catalog map once per composition snapshot.
  val shareContext = rememberShareContext()
  val catalogById = remember(catalog) { catalog.associateBy { it.ingredientId } }
  // Persist active reader filter pill across configuration changes.
  val filterTabNameState = rememberSaveable { mutableStateOf(CookbookFilterTab.ALL.name) }
  val selectedFilterTab = CookbookFilterTab.entries.find { it.name == filterTabNameState.value }
    ?: CookbookFilterTab.ALL
  // Synchronize local recipe selection with parent selectedRecipeId parameter.
  val localSelectedIdState = rememberSaveable(selectedRecipeId) {
    mutableStateOf(selectedRecipeId ?: recipes.firstOrNull()?.recipeId.orEmpty())
  }
  // Mirror saved recipe bookmarks locally for immediate < 16ms tap feedback.
  val localBookmarkedIds = remember(savedRecipeIds) {
    mutableStateListOf<String>().apply { addAll(savedRecipeIds) }
  }
  // Optimistic local BOM swap map so standalone / preview sessions reflect macro deltas immediately.
  val localSwappedRecipes = remember(recipes) { mutableStateMapOf<String, Recipe>() }

  // Filter recipes by search query and active CookbookFilterTab without blocking the UI thread.
  val filteredRecipes = remember(
    recipes,
    selectedFilterTab,
    searchQuery,
    localBookmarkedIds.toList(),
    pantryEntries,
    catalog,
    householdProfile,
  ) {
    val cleanQuery = searchQuery.trim().lowercase()
    recipes.filter { cand ->
      val matchesSearch = cleanQuery.isEmpty() ||
        cand.title.lowercase().contains(cleanQuery) ||
        cand.cuisineTag.lowercase().contains(cleanQuery) ||
        cand.sourceAttribution.lowercase().contains(cleanQuery)
      if (!matchesSearch) return@filter false

      // Evaluate EC-01 morning 25m cap, EC-13 100% stock, and expiring rescue per tab.
      when (selectedFilterTab) {
        CookbookFilterTab.ALL -> true
        CookbookFilterTab.SAVED -> cand.isSavedFavorite || cand.recipeId in localBookmarkedIds
        CookbookFilterTab.MORNING_25M -> isWeekdayBreakfastEligible(cand)
        CookbookFilterTab.IN_STOCK_100 -> PantryExpiryEngine.evaluateRecipePantryMatch(
          recipe = cand,
          pantry = pantryEntries,
          catalog = catalog,
          targetServings = householdProfile.effectiveTargetServings,
          householdAllergens = householdProfile.householdAllergens,
        ).missingStaples.isEmpty()
        CookbookFilterTab.EXPIRING_RESCUE -> PantryExpiryEngine.evaluateRecipePantryMatch(
          recipe = cand,
          pantry = pantryEntries,
          catalog = catalog,
          targetServings = householdProfile.effectiveTargetServings,
          householdAllergens = householdProfile.householdAllergens,
        ).isZeroFoodWasteMatch
      }
    }
  }

  // Fall back gracefully across filtered and unfiltered lists so search edits never blank the reader.
  val resolvedBaseRecipe = filteredRecipes.find { it.recipeId == localSelectedIdState.value }
    ?: recipes.find { it.recipeId == localSelectedIdState.value }
    ?: filteredRecipes.firstOrNull()
    ?: recipes.firstOrNull()

  // Guard empty recipe libraries cleanly without null-pointer assertions.
  if (resolvedBaseRecipe == null) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
      Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(24.dp)) {
        Text(text = "No recipes available yet. Tap Add Recipe to create one.")
      }
    }
    return
  }

  // Prefer optimistic swapped recipe when the user performed a 1-tap BOM ingredient swap.
  val baseRecipe = localSwappedRecipes[resolvedBaseRecipe.recipeId] ?: resolvedBaseRecipe

  // Yield Scale state (1x, 2x, 4x, or Custom serving count) powered by PantryExpiryEngine.
  val yieldPresetNameState = rememberSaveable(baseRecipe.recipeId) { mutableStateOf(YieldPreset.SCALE_1X.name) }
  val selectedYieldPreset = YieldPreset.entries.find { it.name == yieldPresetNameState.value }
    ?: YieldPreset.SCALE_1X
  val customServingsState = rememberSaveable(baseRecipe.recipeId) {
    mutableIntStateOf(max(1, baseRecipe.baseServings))
  }
  // Compute exact proportional serving multiplier while guarding against zero division.
  val yieldMultiplier = when (selectedYieldPreset) {
    YieldPreset.SCALE_1X -> 1.0
    YieldPreset.SCALE_2X -> 2.0
    YieldPreset.SCALE_4X -> 4.0
    YieldPreset.CUSTOM ->
      customServingsState.intValue.toDouble() / max(1, baseRecipe.baseServings).toDouble()
  }

  // Scale Tier-1 quantitative BOM lines proportionally while keeping Tier-2 staples unscaled.
  val scaledRecipe = remember(baseRecipe, yieldMultiplier, catalog) {
    PantryExpiryEngine.scaleRecipeForYield(baseRecipe, yieldMultiplier, catalog)
  }

  // Evaluate live pantry match, missing restock deltas, and rescued expiring stock value.
  val matchSummary = remember(scaledRecipe, pantryEntries, catalog, householdProfile) {
    PantryExpiryEngine.evaluateRecipePantryMatch(
      recipe = scaledRecipe,
      pantry = pantryEntries,
      catalog = catalog,
      targetServings = scaledRecipe.baseServings,
      householdAllergens = householdProfile.householdAllergens,
    )
  }
  // Cache in-stock ingredient IDs in a Set for O(1) BOM row badge lookups.
  val matchedIdSet = remember(matchSummary) {
    matchSummary.matchedIngredients.map { it.ingredientId }.toSet()
  }

  // Hydrate mise-en-place checked BOM IDs from persisted snapshot state.
  val persistedChecked = checkedIngredientsByRecipeId[baseRecipe.recipeId].orEmpty()
  val checkedIds = remember(baseRecipe.recipeId, persistedChecked) {
    mutableStateListOf<String>().apply { addAll(persistedChecked) }
  }

  // Track independent per-step countdown timers inside Method and Directions.
  val inlineTimerSeconds = remember(baseRecipe.recipeId) { mutableStateMapOf<Int, Int>() }
  val inlineTimerRunning = remember(baseRecipe.recipeId) { mutableStateMapOf<Int, Boolean>() }
  val anyInlineRunning = inlineTimerRunning.values.any { it }

  // Tick active inline reader step timers once per second while any step timer is running.
  LaunchedEffect(anyInlineRunning, baseRecipe.recipeId) {
    while (inlineTimerRunning.values.any { it }) {
      delay(1_000L)
      val runningKeys = inlineTimerRunning.filterValues { it }.keys.toList()
      for (stepNum in runningKeys) {
        val currentRem = inlineTimerSeconds[stepNum] ?: 0
        val nextRem = max(0, currentRem - 1)
        inlineTimerSeconds[stepNum] = nextRem
        if (nextRem == 0) {
          inlineTimerRunning[stepNum] = false
        }
      }
    }
  }

  val isBookmarked = baseRecipe.isSavedFavorite || baseRecipe.recipeId in localBookmarkedIds

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.surface)
      .verticalScroll(rememberScrollState())
      .padding(bottom = 28.dp),
  ) {
    // 0. Top `All Recipes` back navigation bar when opened from CookbookGridScreen.
    if (showAllRecipesBackButton) {
      ReaderTopBackBar(
        onBackToCookbookGrid = onBackToCookbookGrid,
        onOpenAddRecipe = onOpenCustomMealBuilder,
      )
    }

    // 1. Cookbook Filter Strip & Active Recipe Switcher.
    CookbookBrowserHeader(
      filteredRecipes = filteredRecipes,
      activeRecipeId = baseRecipe.recipeId,
      savedRecipeIds = localBookmarkedIds.toSet(),
      selectedFilter = selectedFilterTab,
      onSelectFilter = { filterTabNameState.value = it.name },
      onSelectRecipe = { nextId ->
        localSelectedIdState.value = nextId
        onSelectRecipe(nextId)
      },
      onOpenCustomMealBuilder = onOpenCustomMealBuilder,
    )

    // 2. Dynamic-height Hero Banner (Bookmark, Offline Share, Editorial Newsreader Title).
    RecipeReaderHeroBanner(
      recipe = scaledRecipe,
      isBookmarked = isBookmarked,
      onToggleBookmark = {
        if (baseRecipe.recipeId in localBookmarkedIds) {
          localBookmarkedIds.remove(baseRecipe.recipeId)
        } else {
          localBookmarkedIds.add(baseRecipe.recipeId)
        }
        onToggleSavedRecipe(baseRecipe.recipeId)
      },
      onShareRecipe = {
        shareRecipeOffline(shareContext, scaledRecipe, catalogById)
        onShareRecipe(scaledRecipe)
      },
    )

    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      // 3. Pantry Match + 3-Stat Grid + Start Cook Mode + Ownership Action Pills.
      PantryMatchAndQuickActionsSection(
        recipe = scaledRecipe,
        matchSummary = matchSummary,
        householdProfile = householdProfile,
        defaultDayIndex = defaultPlanDayIndex,
        isUserCreated = baseRecipe.isUserCreated,
        onQueueAllMissingIngredients = {
          val ids = matchSummary.missingStaples.map { it.ingredientId }.toSet()
          onQueueMissingIngredients(ids, matchSummary.missingRestockDeltas)
        },
        onStartCookMode = { onStartCookMode(scaledRecipe, false) },
        onStartHandsFreeCookMode = { onStartCookMode(scaledRecipe, true) },
        onScheduleToMealPlan = { dayIdx, slotType ->
          onScheduleRecipeToPlan(baseRecipe, dayIdx, slotType)
        },
        onShareWithKitchen = {
          shareRecipeOffline(shareContext, scaledRecipe, catalogById)
          onShareRecipe(scaledRecipe)
        },
        onForkRecipe = { onForkRecipe(baseRecipe) },
        onEditRecipe = { onEditRecipe(baseRecipe) },
        onDeleteRecipe = { onDeleteRecipe(baseRecipe.recipeId) },
      )

      // 4. EC-08 Child Safety Callout + Collapsible 4-Member Plating & EC-07 Boost.
      FamilySafetyAndPlatingAccordion(
        recipe = scaledRecipe,
        catalog = catalog,
        householdProfile = householdProfile,
        onEditFamilyGoals = onEditFamilyGoals,
      )

      // 5. Yield Scale Selector (1x · 2x · 4x · Custom).
      YieldScaleCard(
        servings = scaledRecipe.baseServings,
        selectedPreset = selectedYieldPreset,
        onSelectPreset = { preset -> yieldPresetNameState.value = preset.name },
        onDecrementCustomServings = {
          customServingsState.intValue = max(1, customServingsState.intValue - 1)
        },
        onIncrementCustomServings = {
          customServingsState.intValue = (customServingsState.intValue + 1).coerceAtMost(20)
        },
      )

      // 6. Interactive Checkable Ingredients Section with 1-tap Swap Ingredient BOM picker.
      ReaderIngredientsChecklistSection(
        recipe = scaledRecipe,
        catalogById = catalogById,
        matchedIdSet = matchedIdSet,
        checkedIds = checkedIds,
        missingRestockDeltas = matchSummary.missingRestockDeltas,
        catalog = catalog,
        householdAllergens = householdProfile.householdAllergens,
        onToggleChecked = { ingId ->
          if (ingId in checkedIds) checkedIds.remove(ingId) else checkedIds.add(ingId)
          onToggleIngredientChecked(baseRecipe.recipeId, ingId)
        },
        onUncheckAll = {
          checkedIds.clear()
          onResetCheckedIngredients(baseRecipe.recipeId)
        },
        onQueueSingleMissing = { ingId, delta ->
          onQueueMissingIngredients(setOf(ingId), listOfNotNull(delta))
        },
        onSwapIngredient = { fromId, toId ->
          runCatching {
            val swapped = PantryExpiryEngine.swapRecipeIngredient(baseRecipe, fromId, toId, catalog)
            localSwappedRecipes[baseRecipe.recipeId] = swapped
          }
          onSwapRecipeIngredient(baseRecipe.recipeId, fromId, toId)
        },
      )

      // 7. Distraction-Free 18.sp Method and Directions Cards with 48.dp Inline Step Timers.
      MethodAndDirectionsSection(
        steps = scaledRecipe.morningSteps,
        inlineTimerSeconds = inlineTimerSeconds,
        inlineTimerRunning = inlineTimerRunning,
      )

      // 8. Sous Chef Pairing Card.
      SousChefPairingCard(
        pairingNote = scaledRecipe.chefPairingNote
          ?: "Pair with crisp citrus-infused sparkling water and seasonal leafy greens.",
      )

      // 9. Warm Parchment Kitchen Notes Card with interactive Add Note.
      KitchenNotesCard(
        recipeId = baseRecipe.recipeId,
        builtInChefNote = baseRecipe.chefNote,
        persistedNotes = recipeNotesByRecipeId[baseRecipe.recipeId].orEmpty(),
        onAddNote = { noteText -> onAddKitchenNote(baseRecipe.recipeId, noteText) },
      )
    }
  }
}

/** Daylight `SavorAI` preview of `CleanRecipeReaderScreen` on Tuscan Salmon & Spinach. */
@Preview(name = "CleanRecipeReader Daylight", showBackground = true, widthDp = 412, heightDp = 1100)
@Composable
fun CleanRecipeReaderDaylightPreview() {
  FamilyKitchenBoardTheme(darkTheme = false) {
    CleanRecipeReaderScreen(
      recipes = PreviewFixtures.recipes,
      catalog = PreviewFixtures.catalog,
      pantryEntries = PreviewFixtures.pantryEntries,
      householdProfile = PreviewFixtures.householdProfile,
      selectedRecipeId = PreviewFixtures.dinnerSalmonRecipe.recipeId,
    )
  }
}

/** Ambient Counter Dark preview of a user-created recipe showing `Edit Recipe` & `Delete Recipe`. */
@Preview(name = "CleanRecipeReader Custom Counter Dark", showBackground = true, widthDp = 412, heightDp = 1100)
@Composable
fun CleanRecipeReaderDarkPreview() {
  val forkedSalmon = PreviewFixtures.dinnerSalmonRecipe.copy(
    recipeId = "rec_fork_salmon",
    title = "Tuscan Salmon Family Version",
    isUserCreated = true,
  )
  FamilyKitchenBoardTheme(darkTheme = true) {
    CleanRecipeReaderScreen(
      recipes = listOf(forkedSalmon) + PreviewFixtures.recipes,
      catalog = PreviewFixtures.catalog,
      pantryEntries = PreviewFixtures.pantryEntries,
      householdProfile = PreviewFixtures.eveningLifeEventProfile,
      selectedRecipeId = forkedSalmon.recipeId,
    )
  }
}
