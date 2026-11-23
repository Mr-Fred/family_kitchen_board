package com.familykitchen.board.ui.pantry

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.BaseUnit
import com.familykitchen.board.domain.BomLine
import com.familykitchen.board.domain.CookOwner
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.IngredientCategory
import com.familykitchen.board.domain.IngredientTier
import com.familykitchen.board.domain.MealSlot
import com.familykitchen.board.domain.MealSlotStatus
import com.familykitchen.board.domain.MealSlotType
import com.familykitchen.board.domain.PantryLedgerEntry
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.RestockDelta
import com.familykitchen.board.domain.StapleStatus
import com.familykitchen.board.domain.WeeklyBoard
import com.familykitchen.board.domain.WeeklyGroceryDiff
import com.familykitchen.board.domain.computeWeeklyGroceryDiff
import com.familykitchen.board.ui.grocery.SmartGroceryScreen
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme

// Combined Kitchen container bridging SmartGroceryScreen (`ui/grocery`) and SmartPantryScreen
// (`ui/pantry`) behind a 48.dp SavorAI pill switcher with crisp 1.dp warm borders.

/**
 * Combined Kitchen Composable hosting both [SmartGroceryScreen] and [SmartPantryScreen] behind a
 * `48.dp` `SavorAI` pill switcher while maintaining full backward compatibility with callers.
 *
 * @param catalog Household ingredient catalog records.
 * @param pantryEntries Current two-tier pantry ledger entries.
 * @param groceryDiff Chronological weekly grocery diff (`EC-09`, `EC-11`, `EC-12`).
 * @param queuedGroceryIds Manually queued or AI-suggested catalog `ingredientId`s.
 * @param onRestockCheckedItems Commits checked [RestockDelta]s in one `O(1)` atomic batch write.
 * @param onAdjustPantryQuantity Applies a Fridge Pulse stepper delta (`±1 count` or `±100 g/ml`).
 * @param onSetStapleStatus Updates Tier-2 `PLENTY | LOW | OUT` status (`EC-11`).
 * @param onToggleLocalAvailability Flips `isLocallyAvailable` (`EC-02` hard-hide / `EC-03` swap).
 * @param onToggleManualGroceryQueue Toggles `+ Need to Buy` manual queueing.
 * @param onAddIngredientToGrocery Adds a preset or custom item to catalog and queues on Grocery.
 * @param onAddIngredientToPantry Adds a preset or custom item to catalog and stocks 1 package.
 * @param initialSubTab Starting sub-tab (`GROCERY_LIST` by default).
 * @param currencySymbol Active household currency symbol (`"₱"` default).
 * @param marketRegionLabel Active local market region label.
 * @param modifier Optional Compose [Modifier].
 */
@Composable
fun PantryAndGroceryScreen(
  catalog: List<IngredientCatalogItem>,
  pantryEntries: List<PantryLedgerEntry>,
  groceryDiff: WeeklyGroceryDiff,
  queuedGroceryIds: Set<String>,
  onRestockCheckedItems: (List<RestockDelta>) -> Unit,
  onAdjustPantryQuantity: (ingredientId: String, deltaQuantity: Double) -> Unit,
  onSetStapleStatus: (ingredientId: String, newStatus: StapleStatus) -> Unit,
  onToggleLocalAvailability: (ingredientId: String, isLocallyAvailable: Boolean) -> Unit,
  onToggleManualGroceryQueue: (ingredientId: String) -> Unit,
  onAddIngredientToGrocery: (IngredientCatalogItem) -> Unit,
  onAddIngredientToPantry: (IngredientCatalogItem) -> Unit,
  initialSubTab: PantrySubTab = PantrySubTab.GROCERY_LIST,
  currencySymbol: String = "₱",
  marketRegionLabel: String = "Antipolo & Marikina, PH",
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Persist selected sub-tab name as a Bundle-safe String across configuration changes.
  var activeSubTabName by rememberSaveable { mutableStateOf(initialSubTab.name) }
  val activeSubTab = PantrySubTab.entries.find { it.name == activeSubTabName } ?: initialSubTab
  // Keep transient Set state in remember (never raw Set inside rememberSaveable).
  var localCheckedIds by remember { mutableStateOf(emptySet<String>()) }
  val existingIds = remember(catalog) { catalog.map { it.ingredientId }.toSet() }

  Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(modifier = Modifier.fillMaxSize()) {
      // 48.dp SavorAI pill switcher with 1.dp warm border and zero drop shadow.
      Surface(
        shape = CircleShape,
        color = extendedColors.parchment,
        border = BorderStroke(1.dp, extendedColors.borderSubtle),
        shadowElevation = 0.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      ) {
        Row(modifier = Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          PantrySubTab.entries.forEach { tab ->
            val selected = activeSubTab == tab
            // Surface live item counts inside each pill without wrapping (`softWrap = false`).
            val count = when (tab) {
              PantrySubTab.GROCERY_LIST -> groceryDiff.groceryItems.size
              PantrySubTab.MY_PANTRY -> catalog.size
            }
            // Active pill uses crisp ceramic fill + 1.dp border; inactive pill stays transparent.
            Surface(
              shape = CircleShape,
              color = if (selected) MaterialTheme.colorScheme.surfaceContainerLowest else Color.Transparent,
              border = if (selected) BorderStroke(1.dp, extendedColors.borderSubtle) else null,
              shadowElevation = 0.dp,
              modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = 48.dp)
                .clip(CircleShape)
                .clickable { activeSubTabName = tab.name },
            ) {
              Row(
                modifier = Modifier.padding(vertical = 10.dp, horizontal = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Icon(
                  imageVector = if (tab == PantrySubTab.GROCERY_LIST) Icons.Filled.ShoppingCart else Icons.Filled.Kitchen,
                  contentDescription = null,
                  tint = if (selected) extendedColors.herbalSage else MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(17.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                  text = "${tab.displayLabel} · $count",
                  style = MaterialTheme.typography.labelMedium,
                  fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                  color = if (selected) extendedColors.herbalSage else MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 1,
                  softWrap = false,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
          }
        }
      }

      // Route to either SmartGroceryScreen or SmartPantryScreen while sharing O(1) batch callbacks.
      when (activeSubTab) {
        PantrySubTab.GROCERY_LIST -> {
          // Suppress duplicate inner Plan switcher since the outer pill bar already frames sub-tabs.
          SmartGroceryScreen(
            groceryDiff = groceryDiff,
            checkedGroceryItemIds = localCheckedIds,
            showPlanSwitcher = false,
            existingCatalogIds = existingIds,
            onToggleItemChecked = { id ->
              localCheckedIds = if (id in localCheckedIds) localCheckedIds - id else localCheckedIds + id
            },
            onRestockCheckedItems = { deltas ->
              // Commit checked whole-package RestockDeltas in 1 atomic batch and reset checkmarks.
              onRestockCheckedItems(deltas)
              localCheckedIds = emptySet()
            },
            onAddCatalogItemToGrocery = onAddIngredientToGrocery,
            onAddCatalogItemToPantry = onAddIngredientToPantry,
            onRemoveManualQueue = onToggleManualGroceryQueue,
            onClearCheckedItems = { localCheckedIds = emptySet() },
            currencySymbol = currencySymbol,
            marketRegionLabel = marketRegionLabel,
            modifier = Modifier.weight(1f),
          )
        }

        PantrySubTab.MY_PANTRY -> {
          // Render two-tier Pantry stock zones, Fridge Pulse steppers, and 48h expiry rescue cards.
          SmartPantryScreen(
            catalog = catalog,
            pantryEntries = pantryEntries,
            queuedGroceryIds = queuedGroceryIds,
            onAdjustPantryQuantity = onAdjustPantryQuantity,
            onSetStapleStatus = onSetStapleStatus,
            onToggleLocalAvailability = onToggleLocalAvailability,
            onToggleManualGroceryQueue = onToggleManualGroceryQueue,
            onAddIngredientToGrocery = onAddIngredientToGrocery,
            onAddIngredientToPantry = onAddIngredientToPantry,
            currencySymbol = currencySymbol,
            modifier = Modifier.weight(1f),
          )
        }
      }
    }
  }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 780)
@Composable
private fun PantryAndGroceryCombinedPreview() {
  // Seed minimal two-tier catalog and pantry fixtures for hermetic Android Studio previewing.
  val catalog = listOf(
    IngredientCatalogItem("ing_egg", "Farm Eggs", IngredientCategory.DAIRY_EGGS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.COUNT, 12),
    IngredientCatalogItem("ing_spinach", "Fresh Baby Spinach", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 300),
    IngredientCatalogItem("ing_olive_oil", "Extra Virgin Olive Oil", IngredientCategory.PANTRY_STAPLE, IngredientTier.TIER_2_STAPLE, BaseUnit.MILLILITER, 500),
  )
  // Include 1 expiring produce item and 1 LOW Tier-2 staple to exercise both sub-tabs.
  val pantry = listOf(
    PantryLedgerEntry("ing_egg", IngredientTier.TIER_1_QUANTITATIVE, 6.0),
    PantryLedgerEntry("ing_spinach", IngredientTier.TIER_1_QUANTITATIVE, 150.0, expiresInDays = 2),
    PantryLedgerEntry("ing_olive_oil", IngredientTier.TIER_2_STAPLE, 500.0, StapleStatus.LOW),
  )
  // Build deterministic 2-slot weekly board so computeWeeklyGroceryDiff surfaces whole-carton rounding.
  val recipe = Recipe(
    recipeId = "rec_omelette", title = "Spinach & Herb Family Omelette",
    sourceAttribution = "Family Kitchen", allowedSlots = listOf(MealSlotType.BREAKFAST, MealSlotType.DINNER),
    preferredOwner = CookOwner.FRED, rawPrepMinutes = 5, cookMinutes = 10, calibratedActiveMinutes = 17,
    toddlerPrepTip = "Cut omelette into soft strips for 2yo.",
    bomLines = listOf(BomLine("ing_egg", 4), BomLine("ing_spinach", 150)),
  )
  val board = WeeklyBoard(
    "preview_board", "household_family_4", "2026-10-26",
    listOf(
      MealSlot("s1", 1, MealSlotType.DINNER, CookOwner.WIFE, "rec_omelette", 4, status = MealSlotStatus.PLANNED),
      MealSlot("s2", 2, MealSlotType.BREAKFAST, CookOwner.FRED, "rec_omelette", 4, status = MealSlotStatus.PLANNED),
    ),
  )
  // Wrap preview in Warm Culinary FamilyKitchenBoardTheme with no-op stateless lambdas.
  val diff = computeWeeklyGroceryDiff(board, listOf(recipe), catalog, pantry)
  FamilyKitchenBoardTheme {
    PantryAndGroceryScreen(
      catalog = catalog, pantryEntries = pantry, groceryDiff = diff, queuedGroceryIds = emptySet(),
      onRestockCheckedItems = {}, onAdjustPantryQuantity = { _, _ -> }, onSetStapleStatus = { _, _ -> },
      onToggleLocalAvailability = { _, _ -> }, onToggleManualGroceryQueue = {},
      onAddIngredientToGrocery = {}, onAddIngredientToPantry = {},
    )
  }
}
