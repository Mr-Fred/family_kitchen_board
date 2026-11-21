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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
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

// Cookbook browser filter pills, dynamic-height offline gourmet Hero Banner, and offline plain-text
// ACTION_SEND recipe share helpers for CleanRecipeReaderScreen (zero sparkles, S22-scale resilient).

/**
 * Cookbook browser filter tabs surfaced at the top of `CleanRecipeReaderScreen`.
 *
 * @property displayLabel Concise filter pill label.
 */
enum class CookbookFilterTab(val displayLabel: String) {
  /** Shows all locally schedulable household recipes. */
  ALL("All"),

  /** Shows bookmarked favorite recipes (`isSavedFavorite` or in `savedRecipeIds`). */
  SAVED("Saved"),

  /** Shows weekday-eligible breakfasts (`<= 25m` calibrated active cap per `EC-01`). */
  MORNING_25M("≤25m Breakfast"),

  /** Shows recipes with `100%` of required ingredients currently in stock (`EC-13`). */
  IN_STOCK_100("100% In Stock"),

  /** Shows zero-waste rescue meals consuming `EXPIRING_SOON` or `EXPIRED` pantry stock. */
  EXPIRING_RESCUE("Expiring Rescue"),
}

/**
 * Horizontal Cookbook filter strip (`All`, `Saved`, `≤25m Breakfast`, `100% In Stock`,
 * `Expiring Rescue`, `+ Custom Meal`) plus non-clipping active recipe selector pills.
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
  Column(
    modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    // Pass horizontal padding via contentPadding so pills never clip mid-letter at the screen edge.
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

    // Deduplicate recipe IDs so LazyRow keys never collide when presets & custom meals coexist.
    val uniqueFilteredRecipes = filteredRecipes.distinctBy { it.recipeId }
    if (uniqueFilteredRecipes.isNotEmpty()) {
      // Apply 16.dp horizontal contentPadding so recipe switcher pills never clip at screen edges.
      LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(items = uniqueFilteredRecipes, key = { it.recipeId }) { candidate ->
          val isCurrent = candidate.recipeId == activeRecipeId
          val isBookmarked = candidate.isSavedFavorite || candidate.recipeId in savedRecipeIds
          // Enforce 48.dp touch height and 260.dp max width so multiple dishes peek in viewport.
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
                text = "${candidate.title} (${candidate.calibratedActiveMinutes}m)",
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
 * `recipe.title` (`maxLines = 3`, `Newsreader` `26.sp`) so elevated S22 font scaling never clips
 * or hides the dish title.
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

  // Dynamic heightIn(min = 228.dp) grows naturally on 3-line titles instead of clipping at fixed 288.dp.
  Box(
    modifier = modifier
      .fillMaxWidth()
      .heightIn(min = 228.dp),
  ) {
    // 100% offline procedural cast-iron & warm botanical culinary artwork matching parent height.
    Canvas(modifier = Modifier.matchParentSize()) {
      // Mandatory zero-dimension Canvas guard preventing zero/negative radius draws.
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
      // Ambient golden warmth, cast-iron skillet shadow, and terracotta reduction glaze accents.
      drawCircle(
        color = Color(0x33FDE68A),
        radius = size.minDimension * 0.46f,
        center = Offset(size.width * 0.72f, size.height * 0.34f),
      )
      drawCircle(
        color = Color(0x44161C27),
        radius = size.minDimension * 0.42f,
        center = Offset(size.width * 0.48f, size.height * 0.52f),
      )
      drawCircle(
        color = Color(0x55E06D53),
        radius = size.minDimension * 0.24f,
        center = Offset(size.width * 0.46f, size.height * 0.48f),
      )
    }

    // Bottom gradient blending hero illustration seamlessly into the warm linen surface.
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

    // Dynamic Column guarantees top actions and bottom 3-line title never overlap or clip.
    Column(
      modifier = Modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.SpaceBetween,
    ) {
      // Top row: concise culinary difficulty badge + 48.dp Bookmark & Share buttons (zero sparkles).
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // Warm parchment pill surfacing calibrated active time + difficulty level.
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

        // Right-hand 48.dp Bookmark and Offline Share circular buttons.
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

      // Bottom title block sizing naturally around up to 3 lines of Newsreader serif typography.
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        // Source attribution + cuisine pill in warm parchment above the editorial headline.
        Surface(
          shape = CircleShape,
          color = extendedColors.parchment,
          border = BorderStroke(1.dp, extendedColors.borderSubtle),
        ) {
          Text(
            text = "${recipe.sourceAttribution} · ${recipe.cuisineTag}",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = extendedColors.herbalSage,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
          )
        }

        // Newsreader 26.sp / 32.sp headline guaranteed visible at 1.35x S22 display/font scaling.
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

// Dispatches an offline Android ACTION_SEND text chooser with scaled BOM quantities and steps.
internal fun shareRecipeOffline(
  context: Context,
  recipe: Recipe,
  catalogById: Map<String, IngredientCatalogItem>,
) {
  // Format scaled BOM lines and numbered steps into a clean plain-text message card.
  val ingredientsBlock = recipe.bomLines.joinToString("\n") { line ->
    val item = catalogById[line.ingredientId]
    val qty = formatCleanNumber(line.baseQuantity)
    "- $qty ${item?.baseUnit?.wireCode ?: "units"} ${item?.displayName ?: line.ingredientId}"
  }
  val stepsBlock = recipe.morningSteps.joinToString("\n") { step ->
    "${step.stepNumber}. ${step.effectiveTitle}: ${step.instruction}"
  }
  val body = buildString {
    appendLine("${recipe.title} (${recipe.baseServings} servings • ${recipe.caloriesPerServing} kcal/srv)")
    appendLine("Prep: ${recipe.rawPrepMinutes}m | Cook: ${recipe.cookMinutes}m")
    appendLine()
    appendLine("Ingredients:")
    appendLine(ingredientsBlock)
    appendLine()
    appendLine("Method:")
    append(stepsBlock)
  }
  // Wrap startActivity in runCatching so headless preview/test contexts never throw ActivityNotFoundException.
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
