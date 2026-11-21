package com.familykitchen.board.ui.aichef

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.familykitchen.board.domain.AiRichRecipeCard
import com.familykitchen.board.domain.BaseUnit
import com.familykitchen.board.domain.BomLine
import com.familykitchen.board.domain.CookOwner
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.IngredientCategory
import com.familykitchen.board.domain.IngredientTier
import com.familykitchen.board.domain.MealSlotType
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.RecipePantryMatchSummary
import com.familykitchen.board.domain.RestockDelta
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme
import kotlin.math.roundToLong

// SavorAI Structured Rich Recipe Artifact Card & Culinary Substitution Tip Card:
// Enforces <= 3 inline metadata pills, 1.dp warm stone borders, 0.dp drop shadows, and 48.dp CTAs.

/**
 * Structured **LLM Recipe Suggestion Card** (`AiRichRecipeCard`) rendered inside `SavorAI`
 * assistant turns: clean `152.dp` procedural stoneware dish banner, `Newsreader` title, **at most
 * 3 inline metadata pills** (`"% Pantry match • X min"`, `"kcal"`, optional `"Saves ₱..."`),
 * collapsible pantry ingredient list, and `48.dp` action buttons (`Start Cook Mode`, `Add to Plan`,
 * `Save`).
 *
 * @param richCard Grounded [AiRichRecipeCard] containing recipe, pantry match, and rescue savings.
 * @param isSaved Whether this recipe is currently bookmarked in `KitchenSnapshot.savedRecipeIds`.
 * @param onStartCookingMode Launches Step-by-Step Cook Mode (`AiChefAction.StartCookModeFromAi`).
 * @param onAddToPlan Schedules the recipe onto `WeeklyBoard` (`AiChefAction.AddRecipeToPlanFromAi`).
 * @param onToggleSave Bookmarks or unbookmarks the recipe (`AiChefAction.ToggleSaveRecipe`).
 * @param onQueueMissingStaples Queues missing staples onto Smart Grocery (`O(1)` batch write).
 * @param currencySymbol Household currency symbol (`"₱"` default).
 * @param modifier Optional Compose [Modifier].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiRichRecipeSuggestionCard(
  richCard: AiRichRecipeCard,
  isSaved: Boolean,
  onStartCookingMode: () -> Unit,
  onAddToPlan: () -> Unit,
  onToggleSave: () -> Unit,
  onQueueMissingStaples: (List<RestockDelta>) -> Unit,
  currencySymbol: String = "₱",
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  val recipe = richCard.recipe
  val match = richCard.matchSummary
  var matchedExpanded by rememberSaveable(recipe.recipeId) { mutableStateOf(false) }

  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = KitchenTheme.shapes.card2xl,
    color = extendedColors.parchment,
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
    shadowElevation = 0.dp,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      // Clean distraction-free stoneware plate banner with zero floating badge clutter.
      OfflineCulinaryHeroIllustration(
        modifier = Modifier
          .fillMaxWidth()
          .height(148.dp)
          .clip(KitchenTheme.shapes.inputDefault),
      )

      // Editorial Newsreader title + consolidated inline row capped strictly at <= 3 pills.
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = recipe.title,
          style = MaterialTheme.typography.headlineSmall,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )

        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          // Pill 1: Pantry match percentage + calibrated active minutes.
          Surface(shape = CircleShape, color = extendedColors.sageContainer) {
            Text(
              text = "${match.matchPercentage}% Pantry match • ${recipe.calibratedActiveMinutes} min",
              style = MaterialTheme.typography.labelSmall,
              color = extendedColors.onSageContainer,
              maxLines = 1,
              softWrap = false,
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
          }

          // Pill 2: Per-serving energy in tabular figures.
          Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer) {
            Text(
              text = "${recipe.caloriesPerServing} kcal",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              softWrap = false,
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
          }

          // Pill 3 (optional): Rescued expiring stock value when > 0.
          if (match.rescuedValueUsd > 0.0) {
            Surface(shape = CircleShape, color = extendedColors.tertiaryFixed) {
              Text(
                text = "Saves ${formatChefCurrency(match.rescuedValueUsd, currencySymbol)}",
                style = MaterialTheme.typography.labelSmall,
                color = extendedColors.onTertiaryFixed,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
              )
            }
          }
        }
      }

      // Crisp ceramic Pantry Checklist accordion (collapsed by default so card stays scannable).
      Surface(
        shape = KitchenTheme.shapes.inputDefault,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, extendedColors.borderSubtle),
        shadowElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          // Accordion toggle row avoids repeating a second "% Ready" pill inside the card.
          Row(
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 40.dp).clickable { matchedExpanded = !matchedExpanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "In Pantry (${match.matchedCount}/${match.totalRequiredCount} ingredients)",
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.weight(1f),
            )
            Icon(
              imageVector = if (matchedExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
              contentDescription = if (matchedExpanded) "Hide matched ingredients" else "Show matched ingredients",
              modifier = Modifier.size(18.dp),
            )
          }

          // Progressive-disclosure chips list in-stock catalog matches only when expanded.
          AnimatedVisibility(visible = matchedExpanded && match.matchedIngredients.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              match.matchedIngredients.forEach { item ->
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                  Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                  ) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp))
                    Text(item.displayName, style = MaterialTheme.typography.labelSmall)
                  }
                }
              }
            }
          }

          // Offer 1-tap Smart Grocery queueing whenever the recipe requires out-of-stock staples.
          if (match.missingStaples.isNotEmpty()) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = "Missing: ${match.missingStaples.joinToString(", ") { it.displayName }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
              )
              // 44.dp+ touch target queues missingRestockDeltas in 1 atomic snapshot write.
              Box(
                modifier = Modifier.defaultMinSize(minHeight = 44.dp).clip(CircleShape).clickable { onQueueMissingStaples(match.missingRestockDeltas) },
                contentAlignment = Alignment.Center,
              ) {
                Surface(
                  shape = CircleShape,
                  color = extendedColors.secondaryFixed,
                  border = BorderStroke(1.dp, extendedColors.borderSubtle),
                ) {
                  Text(
                    text = "+ Queue",
                    style = MaterialTheme.typography.labelSmall,
                    color = extendedColors.onSecondaryFixed,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                  )
                }
              }
            }
          }
        }
      }

      // 48.dp+ Action Buttons: Primary Start Cook Mode + Secondary Add to Plan & Save.
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Primary 48.dp pill opens Step-by-Step Cook Mode with live multi-timer session state.
        Button(
          onClick = onStartCookingMode,
          shape = CircleShape,
          modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
        ) {
          Icon(Icons.Filled.PlayCircle, contentDescription = null, modifier = Modifier.size(19.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = "Start Cook Mode (${recipe.calibratedActiveMinutes}m)",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
          )
        }

        // Secondary 48.dp buttons replace legacy 40.dp pills to prevent S22 text clipping.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(
            onClick = onAddToPlan,
            shape = CircleShape,
            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
          ) {
            Icon(Icons.Filled.CalendarToday, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Add to Plan", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
          }

          // Synchronizes Recipe.isSavedFavorite and KitchenSnapshot.savedRecipeIds in 1 write.
          OutlinedButton(
            onClick = onToggleSave,
            shape = CircleShape,
            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
          ) {
            Icon(
              imageVector = if (isSaved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.secondary,
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (isSaved) "Saved ✓" else "Save", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
          }
        }
      }
    }
  }
}

/**
 * Warm Culinary Substitution Tip Card with `44.dp+` interactive `"Apply swap"` and `"Check calories"`
 * chips and automatic `"Sous-Chef Rule:"` prefix deduplication.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CulinarySubstitutionTipCard(
  tipText: String,
  onApplySwapClick: () -> Unit,
  onCheckCaloriesClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Strip any leading "Sous-Chef Rule:" prefix already emitted by AiMenuAdvisorEngine to avoid duplication.
  val normalizedTip = tipText.removePrefix("Sous-Chef Rule:").trim()
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Surface(
      shape = KitchenTheme.shapes.inputDefault,
      color = extendedColors.secondaryFixed.copy(alpha = 0.45f),
      border = BorderStroke(1.dp, extendedColors.borderSubtle),
      shadowElevation = 0.dp,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Row(
        modifier = Modifier.padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
      ) {
        Icon(Icons.Filled.Lightbulb, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
        Text(
          text = "Sous-Chef Rule: $normalizedTip",
          style = MaterialTheme.typography.bodySmall,
          color = extendedColors.onSecondaryFixed,
        )
      }
    }

    // Interactive 44.dp+ chips trigger PantryExpiryEngine.swapRecipeIngredient and calorie audit.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Box(
        modifier = Modifier.defaultMinSize(minHeight = 44.dp).clip(CircleShape).clickable(onClick = onApplySwapClick),
        contentAlignment = Alignment.Center,
      ) {
        Surface(
          shape = CircleShape,
          color = MaterialTheme.colorScheme.surfaceContainerLow,
          border = BorderStroke(1.dp, extendedColors.borderSubtle),
          shadowElevation = 0.dp,
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Icon(Icons.Filled.SwapHoriz, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
            Text("Update recipe swap", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
          }
        }
      }

      Box(
        modifier = Modifier.defaultMinSize(minHeight = 44.dp).clip(CircleShape).clickable(onClick = onCheckCaloriesClick),
        contentAlignment = Alignment.Center,
      ) {
        Surface(
          shape = CircleShape,
          color = MaterialTheme.colorScheme.surfaceContainerLow,
          border = BorderStroke(1.dp, extendedColors.borderSubtle),
          shadowElevation = 0.dp,
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Icon(Icons.Filled.QueryStats, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(15.dp))
            Text("Check new calories", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
          }
        }
      }
    }
  }
}

// Offline artisanal stoneware plate & seared dish illustration (zero INTERNET image fetches).
@Composable
private fun OfflineCulinaryHeroIllustration(modifier: Modifier = Modifier) {
  // Warm rustic wood-grain gradient backdrop beneath the ceramic plate composition.
  Canvas(
    modifier = modifier.background(
      Brush.linearGradient(listOf(Color(0xFFD9C5B2), Color(0xFF8C6239), Color(0xFF3E2723))),
    ),
  ) {
    if (size.minDimension <= 0f) return@Canvas
    val center = Offset(size.width * 0.5f, size.height * 0.54f)
    // Ceramic stoneware plate rim & warm glaze interior.
    drawCircle(color = Color(0xFFF5F2EB), radius = size.minDimension * 0.44f, center = center)
    drawCircle(color = Color(0xFFE8E3D9), radius = size.minDimension * 0.36f, center = center)
    // Emerald wilted greens bed & golden pan-seared centerpiece.
    drawCircle(color = Color(0xFF2E7D5A), radius = size.minDimension * 0.24f, center = Offset(center.x - 14f, center.y + 8f))
    drawCircle(color = Color(0xFFE06D53), radius = size.minDimension * 0.19f, center = Offset(center.x + 8f, center.y - 6f))
    drawCircle(color = Color(0xFFF48C06), radius = size.minDimension * 0.07f, center = Offset(center.x + 22f, center.y - 14f))
  }
}

// Formats rescued pantry value in pure Kotlin stdlib (`₱1,120` default for PHP).
private fun formatChefCurrency(amount: Double, currencySymbol: String = "₱"): String {
  val clamped = amount.coerceAtLeast(0.0)
  val trimmed = currencySymbol.trim()
  if (trimmed == "₱" || trimmed == "CFA") {
    val whole = clamped.roundToLong().toString().reversed().chunked(3).joinToString(",").reversed()
    return if (trimmed == "CFA") "CFA $whole" else "$trimmed$whole"
  }
  val cents = (clamped * 100.0).roundToLong()
  val whole = (cents / 100L).toString().reversed().chunked(3).joinToString(",").reversed()
  val frac = (cents % 100L).toString().padStart(2, '0')
  return "$trimmed$whole.$frac"
}

@Preview(name = "Rich Recipe Card — <= 3 Badges", showBackground = true, widthDp = 412)
@Composable
private fun AiRichRecipeSuggestionCardPreview() {
  // Verifies <= 3 inline metadata pills, 1.dp warm border, 0.dp shadow, and 48.dp action buttons.
  val salmon = IngredientCatalogItem("ing_salmon", "Salmon fillet", IngredientCategory.PROTEIN, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 600.0)
  val spinach = IngredientCatalogItem("ing_spinach", "Baby spinach", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 300.0)
  val recipe = Recipe(
    "rec_tuscan_salmon",
    "Pan-Seared Tuscan Salmon with Garlic Spinach",
    "Mediterranean Bistro",
    listOf(MealSlotType.DINNER),
    CookOwner.WIFE,
    4,
    10,
    0,
    15,
    25,
    toddlerPrepTip = "Flake boneless fillet finely for 2yo.",
    caloriesPerServing = 490,
    bomLines = listOf(BomLine("ing_salmon", 400.0), BomLine("ing_spinach", 200.0)),
  )
  val card = AiRichRecipeCard(
    recipe = recipe,
    matchSummary = RecipePantryMatchSummary(
      recipe.recipeId,
      listOf(salmon, spinach),
      emptyList(),
      emptyList(),
      matchPercentage = 100,
      isZeroFoodWasteMatch = true,
      rescuedExpiringIngredientIds = listOf("ing_salmon", "ing_spinach"),
      rescuedValueUsd = 845.0,
    ),
    substitutionTip = "Sous-Chef Rule: Remove skillet from heat before whisking in Greek yogurt.",
  )
  FamilyKitchenBoardTheme {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      AiRichRecipeSuggestionCard(
        richCard = card,
        isSaved = false,
        onStartCookingMode = {},
        onAddToPlan = {},
        onToggleSave = {},
        onQueueMissingStaples = {},
      )
      CulinarySubstitutionTipCard(
        tipText = card.substitutionTip.orEmpty(),
        onApplySwapClick = {},
        onCheckCaloriesClick = {},
      )
    }
  }
}
