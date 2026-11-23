package com.familykitchen.board.ui.recipes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familykitchen.board.domain.HouseholdProfile
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.MealSlotType
import com.familykitchen.board.domain.PantryLedgerEntry
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.doesRecipeMatchPreferredCuisines
import com.familykitchen.board.domain.evaluateRecipeAvailability
import com.familykitchen.board.domain.evaluateRecipePantryMatch
import com.familykitchen.board.domain.indexCatalogById
import com.familykitchen.board.domain.indexPantryById
import com.familykitchen.board.domain.isWeekdayBreakfastEligible
import com.familykitchen.board.domain.mergeHeritageRecipesIntoLibrary
import com.familykitchen.board.ui.preview.PreviewFixtures
import com.familykitchen.board.ui.theme.EditorialSerifFontFamily
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme

// Scannable 2-column (3-column on wide counter) Visual Cookbook Card Grid backed by all 42+
// household recipes with dynamic preferred-cuisine filter pills and EC-02 / EC-03 readiness badges.

// Filter chip key constants avoiding raw magic strings across grid tabs and dynamic cuisine chips.
private const val GRID_FILTER_ALL: String = "ALL"
private const val GRID_FILTER_READY_NOW: String = "READY_NOW"
private const val GRID_FILTER_SAVED: String = "SAVED"
private const val GRID_FILTER_BREAKFAST: String = "BREAKFAST"
private const val GRID_FILTER_DINNER: String = "DINNER"
private const val GRID_FILTER_CUSTOM: String = "CUSTOM"
// Prefix identifying dynamic preferred-cuisine filter chips pinned from HouseholdProfile.
private const val GRID_FILTER_CUISINE_PREFIX: String = "CUISINE:"

// Semantic pantry/market readiness bucket rendered as a color-coded pill on each Cookbook card.
private enum class CookbookCardReadinessKind {
  // 100% of required ingredients are currently stocked in Pantry.
  READY_NOW,
  // Schedulable via an EC-03 substituteGroupId local replacement item.
  SWAP_READY,
  // Blocked locally by an EC-02 unavailable ingredient without astocked substitute.
  NEEDS_MARKET_ITEM,
  // Locally available at market, with a subset of required ingredients currently stocked.
  PARTIAL_STOCK,
}

// Precomputed readiness evaluation paired with a slash-free, parenthesis-free badge string.
private data class CookbookCardEvaluation(
  val kind: CookbookCardReadinessKind,
  val badgeLabel: String,
)

/**
 * Stateless 2-Column Visual Cookbook Card Grid (`3` columns on wide counters `>= 720.dp`) backed
 * by the full household recipe library (`42+` dishes), featuring a prominent `Add Recipe` CTA,
 * dynamic preferred-cuisine filter pills (`All` · `Ready Now` · `<Preferred Cuisines>` · `Saved` ·
 * `Breakfast` · `Dinner` · `Custom`), procedural category artwork, `EC-02`/`EC-03` pantry readiness
 * badges, and 1-tap bookmark hearts.
 *
 * @param recipes Full household recipe collection (canonical, heritage, and custom).
 * @param catalog Household ingredient catalog driving `EC-02` / `EC-03` availability checks.
 * @param pantryEntries Household pantry ledger driving `Ready Now` and `Swap Ready` badges.
 * @param householdProfile Active household profile supplying `preferredCuisines` and allergens.
 * @param savedRecipeIds Bookmarked recipe IDs persisted in `KitchenSnapshot`.
 * @param searchQuery Optional search query filtering recipe titles, cuisines, and attributions.
 * @param onSelectRecipe Invoked with `recipeId` when tapping a recipe card to open Clean Reader.
 * @param onToggleSavedRecipe Invoked with `recipeId` when tapping a card's heart icon.
 * @param onOpenAddRecipeSheet Invoked when tapping the prominent `Add Recipe` button.
 * @param modifier Optional Compose [Modifier].
 */
@Composable
fun CookbookGridScreen(
  recipes: List<Recipe>,
  catalog: List<IngredientCatalogItem>,
  pantryEntries: List<PantryLedgerEntry>,
  householdProfile: HouseholdProfile = HouseholdProfile(),
  savedRecipeIds: Set<String> = emptySet(),
  searchQuery: String = "",
  onSelectRecipe: (String) -> Unit = {},
  onToggleSavedRecipe: (String) -> Unit = {},
  onOpenAddRecipeSheet: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  val selectedFilterKeyState = rememberSaveable { mutableStateOf(GRID_FILTER_ALL) }
  // Mirror bookmarks locally so heart taps respond in < 16ms prior to snapshot reload.
  val localSavedIds = remember(savedRecipeIds) {
    mutableStateListOf<String>().apply { addAll(savedRecipeIds) }
  }

  // Index catalog and pantry maps once per snapshot so evaluating 42+ cards stays O(R * B).
  val catalogById = remember(catalog) { indexCatalogById(catalog) }
  val pantryById = remember(pantryEntries) { indexPantryById(pantryEntries) }

  // Precompute EC-02 / EC-03 / pantry-match readiness badges for all recipes once per state change.
  val evaluationsById = remember(recipes, catalog, catalogById, pantryEntries, pantryById, householdProfile) {
    recipes.associate { recipe ->
      recipe.recipeId to cookbookGridEvaluateCard(
        recipe = recipe,
        catalog = catalog,
        catalogById = catalogById,
        pantryEntries = pantryEntries,
        pantryById = pantryById,
        householdProfile = householdProfile,
      )
    }
  }

  // Pin household preferred cuisines right after Ready Now with zero slashes or parentheses.
  val dynamicFilterChips = remember(householdProfile.preferredCuisines) {
    val cleanPrefs = householdProfile.preferredCuisines
      .map(::cookbookGridSanitizeLabel)
      .filter { it.isNotEmpty() }
      .distinct()
    buildList {
      add(GRID_FILTER_ALL to "All")
      add(GRID_FILTER_READY_NOW to "Ready Now")
      cleanPrefs.forEach { cuisine ->
        add("$GRID_FILTER_CUISINE_PREFIX$cuisine" to cuisine)
      }
      add(GRID_FILTER_SAVED to "Saved")
      add(GRID_FILTER_BREAKFAST to "Breakfast")
      add(GRID_FILTER_DINNER to "Dinner")
      add(GRID_FILTER_CUSTOM to "Custom")
    }
  }

  // Fall back to ALL if the user edited preferredCuisines and removed an active cuisine filter.
  val activeFilterKey = selectedFilterKeyState.value.takeIf { key ->
    dynamicFilterChips.any { it.first == key }
  } ?: GRID_FILTER_ALL

  // Filter all 42+ household recipes by live search query and active category/cuisine pill.
  val filteredRecipes = remember(
    recipes,
    activeFilterKey,
    searchQuery,
    localSavedIds.toList(),
    evaluationsById,
  ) {
    val cleanQuery = searchQuery.trim().lowercase()
    recipes.distinctBy { it.recipeId }.filter { recipe ->
      // Match search query against title, primary cuisine, attribution, and multi-cuisine tags.
      val matchesSearch = cleanQuery.isEmpty() ||
        recipe.title.lowercase().contains(cleanQuery) ||
        recipe.cuisineTag.lowercase().contains(cleanQuery) ||
        recipe.sourceAttribution.lowercase().contains(cleanQuery) ||
        recipe.cuisineTags.any { it.lowercase().contains(cleanQuery) }
      if (!matchesSearch) return@filter false

      val eval = evaluationsById[recipe.recipeId]
      // Evaluate category, readiness, EC-01 breakfast cap, custom ownership, or heritage cuisine.
      when {
        activeFilterKey == GRID_FILTER_ALL -> true
        activeFilterKey == GRID_FILTER_READY_NOW ->
          eval?.kind == CookbookCardReadinessKind.READY_NOW ||
            eval?.kind == CookbookCardReadinessKind.SWAP_READY
        activeFilterKey == GRID_FILTER_SAVED ->
          recipe.isSavedFavorite || recipe.recipeId in localSavedIds
        activeFilterKey == GRID_FILTER_BREAKFAST -> isWeekdayBreakfastEligible(recipe)
        activeFilterKey == GRID_FILTER_DINNER -> MealSlotType.DINNER in recipe.allowedSlots
        activeFilterKey == GRID_FILTER_CUSTOM -> recipe.isUserCreated
        activeFilterKey.startsWith(GRID_FILTER_CUISINE_PREFIX) -> {
          val cuisineName = activeFilterKey.removePrefix(GRID_FILTER_CUISINE_PREFIX)
          doesRecipeMatchPreferredCuisines(recipe, listOf(cuisineName))
        }
        else -> true
      }
    }
  }

  // Adaptive 2-column grid on portrait phones (360dp+) and 3-column grid on wide counters (>=720dp).
  BoxWithConstraints(
    modifier = modifier
      .fillMaxSize()
      .background(extendedColors.linen),
  ) {
    val columnCount = if (maxWidth >= 720.dp) 3 else 2

    Column(modifier = Modifier.fillMaxSize()) {
      // 1. Editorial Cookbook header + prominent 48.dp Add Recipe button.
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
          Text(
            text = "Family Cookbook",
            style = MaterialTheme.typography.headlineSmall.copy(
              fontFamily = EditorialSerifFontFamily,
              fontSize = 23.sp,
              lineHeight = 28.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = "${filteredRecipes.size} dishes · ${cookbookGridSanitizeLabel(householdProfile.marketRegionLabel)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }

        Button(
          onClick = onOpenAddRecipeSheet,
          shape = CircleShape,
          contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = extendedColors.herbalSage,
            contentColor = Color.White,
          ),
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        ) {
          Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Add Recipe", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        }
      }

      // 2. Dynamic filter chip strip pinning preferred cuisines upfront.
      LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(items = dynamicFilterChips, key = { it.first }) { (filterKey, label) ->
          val isSelected = activeFilterKey == filterKey
          FilterChip(
            selected = isSelected,
            onClick = { selectedFilterKeyState.value = filterKey },
            shape = CircleShape,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            colors = FilterChipDefaults.filterChipColors(
              selectedContainerColor = extendedColors.herbalSage,
              selectedLabelColor = Color.White,
              containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            ),
            label = {
              Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                softWrap = false,
              )
            },
          )
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      // 3. Adaptive 2-column portrait / 3-column wide counter visual recipe grid.
      when {
        filteredRecipes.isEmpty() -> {
          Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
          ) {
            Surface(
              shape = RoundedCornerShape(20.dp),
              color = MaterialTheme.colorScheme.surfaceContainerLowest,
              border = BorderStroke(1.dp, extendedColors.borderSubtle),
            ) {
              Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                Text(
                  text = "No recipes match this filter yet",
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold,
                )
                Text(
                  text = "Switch to All to explore the full library or tap Add Recipe to paste, scan, or create a dish.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }

        else -> {
          LazyVerticalGrid(
            columns = GridCells.Fixed(columnCount),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
          ) {
            items(items = filteredRecipes, key = { it.recipeId }) { recipe ->
              val isSaved = recipe.isSavedFavorite || recipe.recipeId in localSavedIds
              val evaluation = evaluationsById[recipe.recipeId] ?: CookbookCardEvaluation(
                kind = CookbookCardReadinessKind.READY_NOW,
                badgeLabel = "Ready Now",
              )
              cookbookGridRecipeCard(
                recipe = recipe,
                isSaved = isSaved,
                evaluation = evaluation,
                onClick = { onSelectRecipe(recipe.recipeId) },
                onToggleSaved = {
                  if (recipe.recipeId in localSavedIds) {
                    localSavedIds.remove(recipe.recipeId)
                  } else {
                    localSavedIds.add(recipe.recipeId)
                  }
                  onToggleSavedRecipe(recipe.recipeId)
                },
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun cookbookGridRecipeCard(
  recipe: Recipe,
  isSaved: Boolean,
  evaluation: CookbookCardEvaluation,
  onClick: () -> Unit,
  onToggleSaved: () -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Normalize primary cuisine tag so no slashes or parentheses leak into card metadata.
  val cleanCuisine = cookbookGridSanitizeLabel(
    recipe.cuisineTags.firstOrNull()?.takeIf { it.isNotBlank() } ?: recipe.cuisineTag,
  )
  // Derive a concise top-left category pill (Custom, Breakfast, Lunch, or Dinner).
  val categoryBadge = when {
    recipe.isUserCreated -> "Custom"
    MealSlotType.BREAKFAST in recipe.allowedSlots -> "Breakfast"
    MealSlotType.LUNCH in recipe.allowedSlots && MealSlotType.DINNER !in recipe.allowedSlots -> "Lunch"
    else -> "Dinner"
  }

  // Flat 1.dp stone-bordered editorial recipe card with 0.dp shadow elevation.
  Card(
    onClick = onClick,
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      // Top culinary artwork banner + category badge + 48.dp bookmark heart button.
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(92.dp),
      ) {
        cookbookGridCardArtwork(recipe = recipe, modifier = Modifier.matchParentSize())

        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 4.dp, top = 4.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          // Semi-opaque warm parchment pill keeping category text legible over artwork gradients.
          Surface(
            shape = CircleShape,
            color = extendedColors.parchment.copy(alpha = 0.94f),
            border = BorderStroke(1.dp, extendedColors.borderSubtle),
          ) {
            Text(
              text = categoryBadge,
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.Bold,
              color = extendedColors.charcoalInk,
              maxLines = 1,
              softWrap = false,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
          }

          // Full 48.dp outer IconButton touch target wrapping a compact 34.dp visual medallion.
          IconButton(
            onClick = onToggleSaved,
            modifier = Modifier.size(48.dp),
          ) {
            Surface(
              shape = CircleShape,
              color = extendedColors.parchment.copy(alpha = 0.92f),
              modifier = Modifier.size(34.dp),
            ) {
              Box(contentAlignment = Alignment.Center) {
                Icon(
                  imageVector = if (isSaved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                  contentDescription = if (isSaved) "Remove saved recipe" else "Save recipe",
                  tint = if (isSaved) extendedColors.terracotta else MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(18.dp),
                )
              }
            }
          }
        }
      }

      // Card body: 2-line Newsreader title, active minutes + cuisine tag, and readiness badge.
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          text = recipe.title,
          style = MaterialTheme.typography.titleMedium.copy(
            fontFamily = EditorialSerifFontFamily,
            fontSize = 17.sp,
            lineHeight = 22.sp,
          ),
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 2,
          minLines = 2,
          overflow = TextOverflow.Ellipsis,
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Icon(
            imageVector = Icons.Default.Timer,
            contentDescription = null,
            tint = extendedColors.herbalSage,
            modifier = Modifier.size(14.dp),
          )
          Text(
            text = "${recipe.calibratedActiveMinutes}m · $cleanCuisine",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
        }

        cookbookGridReadinessPill(evaluation = evaluation)
      }
    }
  }
}

@Composable
private fun cookbookGridCardArtwork(
  recipe: Recipe,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Deterministically select a warm culinary palette (sage, terracotta, apricot) with zero cold hexes.
  val hash = (recipe.recipeId.hashCode() and 0x7FFFFFFF) % 3
  val gradientColors = when {
    MealSlotType.BREAKFAST in recipe.allowedSlots -> listOf(
      Color(0xFF4A5D4E),
      Color(0xFF9C6B3B),
      Color(0xFFE3C39D),
    )
    hash == 0 -> listOf(
      Color(0xFF2B4336),
      Color(0xFF4F6D56),
      Color(0xFFC28C67),
    )
    hash == 1 -> listOf(
      Color(0xFF5E3424),
      Color(0xFF9E5334),
      Color(0xFFD9AE89),
    )
    else -> listOf(
      Color(0xFF35473E),
      Color(0xFF7B5B3A),
      Color(0xFFD7BC9B),
    )
  }

  // Render procedural ceramic bowl + garnish circles offline without network image fetches.
  Canvas(
    modifier = modifier.clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
  ) {
    // Mandatory zero-dimension Canvas guard preventing negative or zero radius crashes.
    if (size.width <= 0f || size.height <= 0f) return@Canvas
    drawRect(
      brush = Brush.linearGradient(
        colors = gradientColors,
        start = Offset.Zero,
        end = Offset(size.width, size.height),
      ),
    )
    // Outer soft parchment plate silhouette.
    drawCircle(
      color = extendedColors.parchment.copy(alpha = 0.22f),
      radius = size.minDimension * 0.48f,
      center = Offset(size.width * 0.68f, size.height * 0.56f),
    )
    // Inner terracotta glaze medallion.
    drawCircle(
      color = extendedColors.terracottaClay.copy(alpha = 0.30f),
      radius = size.minDimension * 0.28f,
      center = Offset(size.width * 0.36f, size.height * 0.62f),
    )
  }
}

@Composable
private fun cookbookGridReadinessPill(evaluation: CookbookCardEvaluation) {
  val extendedColors = KitchenTheme.extendedColors
  // Map readiness bucket to warm semantic container tokens (Sage, Honey, Terracotta, Parchment).
  val (bgColor, fgColor) = when (evaluation.kind) {
    CookbookCardReadinessKind.READY_NOW ->
      extendedColors.sageContainer to extendedColors.onSageContainer
    CookbookCardReadinessKind.SWAP_READY ->
      extendedColors.tertiaryFixed to extendedColors.onTertiaryFixed
    CookbookCardReadinessKind.NEEDS_MARKET_ITEM ->
      extendedColors.terracottaContainer to extendedColors.onTerracottaContainer
    CookbookCardReadinessKind.PARTIAL_STOCK ->
      extendedColors.parchment to MaterialTheme.colorScheme.onSurfaceVariant
  }

  Surface(
    shape = CircleShape,
    color = bgColor,
    border = if (evaluation.kind == CookbookCardReadinessKind.PARTIAL_STOCK) {
      BorderStroke(1.dp, extendedColors.borderSubtle)
    } else {
      null
    },
  ) {
    Text(
      text = evaluation.badgeLabel,
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.Bold,
      color = fgColor,
      maxLines = 1,
      softWrap = false,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
    )
  }
}

private fun cookbookGridEvaluateCard(
  recipe: Recipe,
  catalog: List<IngredientCatalogItem>,
  catalogById: Map<String, IngredientCatalogItem>,
  pantryEntries: List<PantryLedgerEntry>,
  pantryById: Map<String, PantryLedgerEntry>,
  householdProfile: HouseholdProfile,
): CookbookCardEvaluation {
  // Check EC-02 local market availability and EC-03 substitute group resolution first.
  val availability = evaluateRecipeAvailability(
    recipe = recipe,
    catalogById = catalogById,
    householdAllergens = householdProfile.householdAllergens,
    pantryById = pantryById,
  )
  // Flag unschedulable EC-02 dishes with Needs Market Item rather than silently dropping them.
  if (!availability.isSchedulable) {
    return CookbookCardEvaluation(
      kind = CookbookCardReadinessKind.NEEDS_MARKET_ITEM,
      badgeLabel = "Needs Market Item",
    )
  }

  // Evaluate quantitative + Tier-2 staple pantry coverage for the active household serving scale.
  val match = evaluateRecipePantryMatch(
    recipe = recipe,
    pantry = pantryEntries,
    catalog = catalog,
    targetServings = householdProfile.effectiveTargetServings,
    householdAllergens = householdProfile.householdAllergens,
  )
  // Prefer Swap Ready whenever EC-03 local market substitution resolved a missing base ingredient.
  return when {
    availability.substitutionsApplied.isNotEmpty() && match.missingStaples.isEmpty() ->
      CookbookCardEvaluation(CookbookCardReadinessKind.SWAP_READY, "Swap Ready")
    match.missingStaples.isEmpty() ->
      CookbookCardEvaluation(CookbookCardReadinessKind.READY_NOW, "Ready Now")
    availability.substitutionsApplied.isNotEmpty() ->
      CookbookCardEvaluation(CookbookCardReadinessKind.SWAP_READY, "Swap Ready")
    else ->
      CookbookCardEvaluation(
        kind = CookbookCardReadinessKind.PARTIAL_STOCK,
        badgeLabel = "${match.matchedCount} of ${match.totalRequiredCount} Stocked",
      )
  }
}

// Strips slashes and parentheses from region or cuisine strings so card badges stay clean.
private fun cookbookGridSanitizeLabel(raw: String): String =
  raw
    .replace(" / ", " and ")
    .replace("/", " · ")
    .replace("(", "")
    .replace(")", "")
    .replace(Regex("\\s+"), " ")
    .trim()

/** Portrait `360dp` Warm Daylight preview of `CookbookGridScreen` with heritage & bistro cards. */
@Preview(name = "CookbookGrid Portrait 360dp Daylight", showBackground = true, widthDp = 360, heightDp = 820)
@Composable
fun CookbookGridScreenDaylightPreview() {
  // Merge canonical and heritage recipes so preview renders 42+ scannable visual cards.
  val fullRecipes = mergeHeritageRecipesIntoLibrary(PreviewFixtures.recipes)
  FamilyKitchenBoardTheme(darkTheme = false) {
    CookbookGridScreen(
      recipes = fullRecipes,
      catalog = PreviewFixtures.catalog,
      pantryEntries = PreviewFixtures.pantryEntries,
      householdProfile = PreviewFixtures.householdProfile,
    )
  }
}

/** Wide Counter `840dp` Ambient Dark preview of `CookbookGridScreen` in 3-column mode. */
@Preview(name = "CookbookGrid Wide Counter Dark", showBackground = true, widthDp = 840, heightDp = 760)
@Composable
fun CookbookGridScreenWideDarkPreview() {
  // Verify 3-column wide counter adaptation and Counter Dark tokens across heritage cards.
  val fullRecipes = mergeHeritageRecipesIntoLibrary(PreviewFixtures.recipes)
  FamilyKitchenBoardTheme(darkTheme = true) {
    CookbookGridScreen(
      recipes = fullRecipes,
      catalog = PreviewFixtures.catalog,
      pantryEntries = PreviewFixtures.pantryEntries,
      householdProfile = PreviewFixtures.eveningLifeEventProfile,
    )
  }
}
