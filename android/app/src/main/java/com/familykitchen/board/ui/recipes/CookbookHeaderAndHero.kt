package com.familykitchen.board.ui.recipes

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.ui.theme.EditorialSerifFontFamily
import com.familykitchen.board.ui.theme.KitchenTheme
import kotlin.math.roundToInt

// Cookbook top back-bar (`All Recipes`), browser filter pills, dynamic-height offline gourmet Hero
// Banner, and offline plain-text ACTION_SEND recipe share helpers with zero slashes or parentheses.

/**
 * Cookbook browser filter tabs surfaced inside `CleanRecipeReaderScreen`.
 *
 * @property displayLabel Concise filter pill label free of slashes and parentheses.
 */
enum class CookbookFilterTab(val displayLabel: String) {
  // Displays the full household recipe library without readiness filtering.
  /** Shows all locally schedulable household recipes. */
  ALL("All"),

  // Filters down to household-bookmarked favorite dishes.
  /** Shows bookmarked favorite recipes (`isSavedFavorite` or in `savedRecipeIds`). */
  SAVED("Saved"),

  // Enforces EC-01 1.4x calibrated morning cap <= 25 minutes.
  /** Shows weekday-eligible breakfasts (`<= 25m` calibrated active cap per `EC-01`). */
  MORNING_25M("Quick Breakfast"),

  // Surfaces EC-13 recipes with 0 missing pantry items.
  /** Shows recipes with `100%` of required ingredients currently in stock (`EC-13`). */
  IN_STOCK_100("Ready Now"),

  // Prioritizes recipes rescuing EXPIRING_SOON or EXPIRED stock.
  /** Shows zero-waste rescue meals consuming `EXPIRING_SOON` or `EXPIRED` pantry stock. */
  EXPIRING_RESCUE("Expiring Rescue"),
}

/**
 * Top navigation row inside `CleanRecipeReaderScreen` offering a `48.dp` `All Recipes` back button
 * returning to `CookbookGridScreen` alongside a quick `Add Recipe` shortcut.
 */
@Composable
internal fun ReaderTopBackBar(
  onBackToCookbookGrid: () -> Unit,
  onOpenAddRecipe: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Keep both All Recipes and Add Recipe at >= 48.dp touch target height with 0 slashes.
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // Primary back navigation pill returning to the 2-column CookbookGridScreen.
    OutlinedButton(
      onClick = onBackToCookbookGrid,
      shape = CircleShape,
      border = BorderStroke(1.dp, extendedColors.borderSubtle),
      contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
      modifier = Modifier.defaultMinSize(minHeight = 48.dp),
    ) {
      Icon(
        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
        contentDescription = null,
        tint = extendedColors.herbalSage,
        modifier = Modifier.size(18.dp),
      )
      Spacer(modifier = Modifier.width(6.dp))
      Text(
        text = "All Recipes",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        softWrap = false,
      )
    }

    // Secondary quick-launch chip opening the 3-mode AddOrEditRecipeSheet studio.
    AssistChip(
      onClick = onOpenAddRecipe,
      shape = CircleShape,
      modifier = Modifier.defaultMinSize(minHeight = 48.dp),
      leadingIcon = {
        Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
      },
      label = {
        Text(
          text = "Add Recipe",
          style = MaterialTheme.typography.labelMedium,
          maxLines = 1,
          softWrap = false,
        )
      },
    )
  }
}

/**
 * Horizontal Cookbook filter strip (`All`, `Saved`, `Quick Breakfast`, `Ready Now`,
 * `Expiring Rescue`, `Add Recipe`) plus non-clipping active recipe selector pills.
 */
@Composable
internal fun CookbookBrowserHeader(
  filteredRecipes: List<Recipe>,
  activeRecipeId: String,
  savedRecipeIds: Set<String>,
  selectedFilter: CookbookFilterTab,
  onSelectFilter: (CookbookFilterTab) -> Unit,
  onSelectRecipe: (String) -> Unit,
  onOpenCustomMealBuilder: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Stack filter tabs above the active recipe pill carousel for quick single-thumb switching.
  Column(
    modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    // Horizontal filter strip keeping 48.dp touch heights across all filter chips.
    LazyRow(
      contentPadding = PaddingValues(horizontal = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      items(items = CookbookFilterTab.entries, key = { it.name }) { tab ->
        val isSelected = tab == selectedFilter
        FilterChip(
          selected = isSelected,
          onClick = { onSelectFilter(tab) },
          shape = CircleShape,
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = extendedColors.herbalSage,
            selectedLabelColor = Color.White,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
          ),
          label = {
            Text(
              text = tab.displayLabel,
              style = MaterialTheme.typography.labelMedium,
              maxLines = 1,
              softWrap = false,
            )
          },
        )
      }
      // Trailing Custom Meal shortcut pill.
      item(key = "custom_meal_action") {
        AssistChip(
          onClick = onOpenCustomMealBuilder,
          shape = CircleShape,
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          leadingIcon = {
            Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
          },
          label = {
            Text(
              text = "Custom Meal",
              style = MaterialTheme.typography.labelMedium,
              maxLines = 1,
              softWrap = false,
            )
          },
        )
      }
    }

    // Deduplicate by recipeId before feeding LazyRow keys so duplicate snapshots never crash.
    val uniqueFilteredRecipes = filteredRecipes.distinctBy { it.recipeId }
    if (uniqueFilteredRecipes.isNotEmpty()) {
      // Secondary carousel letting readers jump between filtered dishes without leaving Reader.
      LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(items = uniqueFilteredRecipes, key = { it.recipeId }) { candidate ->
          val isCurrent = candidate.recipeId == activeRecipeId
          val isBookmarked = candidate.isSavedFavorite || candidate.recipeId in savedRecipeIds
          Surface(
            onClick = { onSelectRecipe(candidate.recipeId) },
            shape = CircleShape,
            color = if (isCurrent) extendedColors.sageContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
            border = BorderStroke(
              width = 1.dp,
              color = if (isCurrent) extendedColors.herbalSage else extendedColors.borderSubtle,
            ),
            shadowElevation = 0.dp,
            modifier = Modifier
              .widthIn(max = 260.dp)
              .defaultMinSize(minHeight = 48.dp),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              if (isBookmarked) {
                Icon(
                  imageVector = Icons.Default.Bookmark,
                  contentDescription = null,
                  tint = if (isCurrent) extendedColors.onSageContainer else extendedColors.terracotta,
                  modifier = Modifier.size(14.dp),
                )
              }
              Text(
                text = "${readerHeroSanitize(candidate.title)} · ${candidate.calibratedActiveMinutes}m",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) extendedColors.onSageContainer else MaterialTheme.colorScheme.onSurface,
              )
            }
          }
        }
      }
    }
  }
}

/**
 * Dynamic-height (`heightIn(min = 228.dp)`) offline culinary Hero Banner sizing naturally around
 * `recipe.title` (`maxLines = 3`, `Newsreader` `26.sp`) so elevated `1.35x` font scaling never clips.
 */
@Composable
internal fun RecipeReaderHeroBanner(
  recipe: Recipe,
  isBookmarked: Boolean,
  onToggleBookmark: () -> Unit,
  onShareRecipe: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  val surfaceColor = MaterialTheme.colorScheme.surface

  // Use heightIn(min = 228.dp) rather than fixed height so 3-line titles at 1.35x never clip.
  Box(
    modifier = modifier
      .fillMaxWidth()
      .heightIn(min = 228.dp),
  ) {
    // Offline warm procedural culinary hero backdrop (zero cold blue or purple hexes).
    Canvas(modifier = Modifier.matchParentSize()) {
      // Mandatory zero-dimension Canvas guard preventing zero or negative radius draws.
      if (size.width <= 0f || size.height <= 0f) return@Canvas
      drawRect(
        brush = Brush.linearGradient(
          colors = listOf(
            Color(0xFF2B3A32),
            Color(0xFF4B604F),
            Color(0xFF8C583A),
            Color(0xFFD7B899),
          ),
          start = Offset.Zero,
          end = Offset(size.width, size.height),
        ),
      )
      // Soft golden-hour highlight glow in the upper right quadrant.
      drawCircle(
        color = Color(0x33FDE68A),
        radius = size.minDimension * 0.46f,
        center = Offset(size.width * 0.72f, size.height * 0.34f),
      )
      // Subtle ceramic shadow grounding the plate silhouette.
      drawCircle(
        color = Color(0x44161C27),
        radius = size.minDimension * 0.42f,
        center = Offset(size.width * 0.48f, size.height * 0.52f),
      )
      // Warm terracotta glaze center accent.
      drawCircle(
        color = Color(0x55E06D53),
        radius = size.minDimension * 0.24f,
        center = Offset(size.width * 0.46f, size.height * 0.48f),
      )
    }

    // Bottom scrim blending smoothly into MaterialTheme.colorScheme.surface for WCAG AA contrast.
    Box(
      modifier = Modifier
        .matchParentSize()
        .background(
          Brush.verticalGradient(
            0.0f to Color.Transparent,
            0.40f to surfaceColor.copy(alpha = 0.45f),
            0.85f to surfaceColor.copy(alpha = 0.92f),
            1.0f to surfaceColor,
          ),
        ),
    )

    Column(
      modifier = Modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.SpaceBetween,
    ) {
      // Top hero action row: active time + difficulty pill, 48.dp Bookmark, and 48.dp Share.
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Surface(
          shape = CircleShape,
          color = extendedColors.parchment.copy(alpha = 0.94f),
          border = BorderStroke(1.dp, extendedColors.borderSubtle),
          shadowElevation = 0.dp,
        ) {
          Text(
            text = "${recipe.calibratedActiveMinutes}m · ${recipe.difficulty.displayLabel}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = extendedColors.charcoalInk,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
          )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Surface(
            shape = CircleShape,
            color = extendedColors.parchment.copy(alpha = 0.94f),
            border = BorderStroke(1.dp, extendedColors.borderSubtle),
            shadowElevation = 0.dp,
          ) {
            IconButton(onClick = onToggleBookmark, modifier = Modifier.size(48.dp)) {
              Icon(
                imageVector = if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                contentDescription = if (isBookmarked) "Remove bookmark" else "Bookmark recipe",
                tint = if (isBookmarked) extendedColors.terracotta else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
              )
            }
          }

          Surface(
            shape = CircleShape,
            color = extendedColors.parchment.copy(alpha = 0.94f),
            border = BorderStroke(1.dp, extendedColors.borderSubtle),
            shadowElevation = 0.dp,
          ) {
            IconButton(onClick = onShareRecipe, modifier = Modifier.size(48.dp)) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = "Share recipe offline",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
              )
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(68.dp))

      // Bottom hero editorial stack: sanitized attribution + Newsreader 26.sp title.
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        // Warm parchment origin pill highlighting cuisine and household source attribution.
        Surface(
          shape = CircleShape,
          color = extendedColors.parchment,
          border = BorderStroke(1.dp, extendedColors.borderSubtle),
        ) {
          // Strip slashes and parentheses from legacy seed attributions before display.
          val cleanOrigin = readerHeroSanitize("${recipe.sourceAttribution} · ${recipe.cuisineTag}")
          Text(
            text = cleanOrigin,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = extendedColors.herbalSage,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
          )
        }

        // Newsreader display headline capped at 3 clean lines.
        Text(
          text = recipe.title,
          style = MaterialTheme.typography.displaySmall.copy(
            fontFamily = EditorialSerifFontFamily,
            fontSize = 26.sp,
            lineHeight = 32.sp,
          ),
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 3,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

// Replaces slashes and strips parentheses so UI badges stay 100% punctuation-clean.
private fun readerHeroSanitize(raw: String): String =
  raw
    .replace(" / ", " and ")
    .replace("/", " · ")
    .replace("(", "")
    .replace(")", "")
    .replace(Regex("\\s+"), " ")
    .trim()

// Dispatches an offline Android ACTION_SEND text chooser with scaled BOM quantities and steps.
internal fun shareRecipeOffline(
  context: Context,
  recipe: Recipe,
  catalogById: Map<String, IngredientCatalogItem>,
) {
  // Format each BOM line with clean integer/fractional numbers and wire units.
  val ingredientsBlock = recipe.bomLines.joinToString("\n") { line ->
    val item = catalogById[line.ingredientId]
    val qty = formatCleanNumber(line.baseQuantity)
    "- $qty ${item?.baseUnit?.wireCode ?: "units"} ${item?.displayName ?: line.ingredientId}"
  }
  // Format numbered cooking method steps for plain-text offline messaging apps.
  val stepsBlock = recipe.morningSteps.joinToString("\n") { step ->
    "${step.stepNumber}. ${step.effectiveTitle}: ${step.instruction}"
  }
  // Assemble complete shareable recipe card without requiring cloud links.
  val body = buildString {
    appendLine("${recipe.title} · ${recipe.baseServings} servings · ${recipe.caloriesPerServing} kcal per serving")
    appendLine("Prep: ${recipe.rawPrepMinutes}m · Cook: ${recipe.cookMinutes}m")
    appendLine()
    appendLine("Ingredients:")
    appendLine(ingredientsBlock)
    appendLine()
    appendLine("Method:")
    append(stepsBlock)
  }
  // Wrap startActivity in runCatching so headless preview or test contexts never throw.
  runCatching {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_SUBJECT, recipe.title)
      putExtra(Intent.EXTRA_TEXT, body)
    }
    val chooser = Intent.createChooser(sendIntent, "Share Recipe with Kitchen")
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
  }
}

// Formats whole numbers without trailing ".0" while preserving fractional yields (e.g. 0.5, 1.25).
internal fun formatCleanNumber(value: Double): String {
  val rounded = (value * 100.0).roundToInt() / 100.0
  return when {
    rounded == rounded.toLong().toDouble() -> rounded.toLong().toString()
    else -> rounded.toString()
  }
}

/** Returns the current [Context] for dispatching offline `ACTION_SEND` recipe shares. */
@Composable
internal fun rememberShareContext(): Context = LocalContext.current
