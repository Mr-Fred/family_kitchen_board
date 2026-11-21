package com.familykitchen.board.ui.grocery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.BaseUnit
import com.familykitchen.board.domain.CustomGroceryItem
import com.familykitchen.board.domain.GroceryListItem
import com.familykitchen.board.domain.GrocerySortMode
import com.familykitchen.board.domain.HouseholdProfile
import com.familykitchen.board.domain.IngredientTier
import com.familykitchen.board.domain.WeeklyGroceryDiff
import com.familykitchen.board.ui.theme.KitchenTheme
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// Modular SavorAI Smart Grocery List cards (`smart_grocery_list`):
// Editorial Pantry-Adjusted banner with 3.dp micro-bar, 48.dp Quick-Add + Mic + 3 direct sort pills,
// 48.dp checkable aisle cards with humanized units & readable meal attribution, and 56.dp summary bar.

// Weekday abbreviations keeping meal attributions ("Mon Dinner · Dish Title") scannable on 1 line.
private val FULL_TO_SHORT_WEEKDAYS: List<Pair<String, String>> = listOf(
  "Monday" to "Mon",
  "Tuesday" to "Tue",
  "Wednesday" to "Wed",
  "Thursday" to "Thu",
  "Friday" to "Fri",
  "Saturday" to "Sat",
  "Sunday" to "Sun",
)

/**
 * Top `List (N) | Meal Plan` `48.dp` pill segmented switcher preserved when rendered standalone.
 */
@Composable
fun GroceryPlanSegmentedHeader(
  remainingCount: Int,
  onOpenWeeklyMealPlan: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Warm parchment track with 1.dp subtle border and zero elevation drop shadow.
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = CircleShape,
    color = extendedColors.parchment,
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
    shadowElevation = 0.dp,
  ) {
    Row(modifier = Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, extendedColors.borderSubtle),
        shadowElevation = 0.dp,
        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
      ) {
        Row(
          modifier = Modifier.padding(vertical = 10.dp, horizontal = 10.dp),
          horizontalArrangement = Arrangement.Center,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(Icons.Filled.ShoppingCart, contentDescription = null, tint = extendedColors.herbalSage, modifier = Modifier.size(17.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("List ($remainingCount)", style = MaterialTheme.typography.labelMedium, color = extendedColors.herbalSage, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        }
      }

      Surface(
        shape = CircleShape,
        color = Color.Transparent,
        shadowElevation = 0.dp,
        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).clip(CircleShape).clickable(onClick = onOpenWeeklyMealPlan),
      ) {
        Row(
          modifier = Modifier.padding(vertical = 10.dp, horizontal = 10.dp),
          horizontalArrangement = Arrangement.Center,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(Icons.Filled.CalendarToday, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Meal Plan", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
        }
      }
    }
  }
}

/**
 * Optional QA Vault & `Batch #<batchWriteCallCount>` pill (hidden on consumer screens by default).
 */
@Composable
fun HouseholdVaultSyncPill(
  householdProfile: HouseholdProfile,
  batchWriteCallCount: Int,
  onOpenQaLab: () -> Unit,
  marketRegionLabel: String = "Antipolo & Marikina, PH",
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Tapping opens QaScenarioLabSheet for O(1) batch write verification during engineering audits.
  Surface(
    modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clip(KitchenTheme.shapes.card2xl).clickable(onClick = onOpenQaLab),
    shape = KitchenTheme.shapes.card2xl,
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
    shadowElevation = 0.dp,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
        Box(modifier = Modifier.size(24.dp).clip(CircleShape).background(extendedColors.herbalSage), contentAlignment = Alignment.Center) {
          val firstInitial = householdProfile.morningLeadName.firstOrNull()?.uppercase() ?: "F"
          Text(firstInitial, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1, softWrap = false)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text("${householdProfile.householdName} · $marketRegionLabel", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      Spacer(modifier = Modifier.width(8.dp))
      Surface(shape = CircleShape, color = extendedColors.sageContainer) {
        Text("Batch #$batchWriteCallCount", style = MaterialTheme.typography.labelSmall, color = extendedColors.onSageContainer, maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
      }
    }
  }
}

/**
 * Crisp editorial `Pantry-Adjusted List` banner (`~68dp`) with weighted title, non-wrapping
 * `Saved ₱...` capsule, and a `3.dp` botanical deduplication micro-bar.
 */
@Composable
fun PantryDeduplicationBanner(
  groceryDiff: WeeklyGroceryDiff,
  currencySymbol: String = "₱",
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  val skipped = groceryDiff.skippedStockedStapleCount
  val totalConsidered = max(1, skipped + groceryDiff.groceryItems.size)
  // Normalize skipped vs required items into a calm 3.dp botanical progress micro-bar.
  val dedupFraction = (skipped.toFloat() / totalConsidered.toFloat()).coerceIn(0.10f, 1.0f)

  Card(
    modifier = modifier.fillMaxWidth(),
    shape = KitchenTheme.shapes.card2xl,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      // Weighted title + non-wrapping savings pill prevents vertical "Sa\nve\nd" wrap on 360dp.
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          modifier = Modifier.weight(1f),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Icon(Icons.Filled.Inventory2, contentDescription = null, tint = extendedColors.herbalSage, modifier = Modifier.size(16.dp))
          Text(
            text = "Pantry-Adjusted List",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Surface(shape = CircleShape, color = extendedColors.sageContainer) {
          Text(
            text = "Saved ${formatGroceryCurrency(groceryDiff.deduplicationSavingsUsd, currencySymbol)}",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = extendedColors.onSageContainer,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
          )
        }
      }

      // 3.dp botanical deduplication micro-bar + single-line muted caption.
      Box(modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer)) {
        Box(modifier = Modifier.fillMaxWidth(dedupFraction).fillMaxHeight().clip(CircleShape).background(extendedColors.herbalSage))
      }
      Text(
        text = "$skipped in-stock staples skipped across ${groceryDiff.plannedMealCount} meals",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/**
 * Quick-Add & Dictate bar (`Modifier.weight(1f)` + `48.dp` `Mic` `IconButton` + conditional
 * `Clear (M)` pill) paired with 3 direct `48.dp` sort pills (`By Aisle | By Meal | Shortages`).
 */
@Composable
fun StoreSortAndQuickAddBar(
  sortMode: GrocerySortMode,
  checkedCount: Int,
  onSelectSortMode: (GrocerySortMode) -> Unit,
  onToggleAddDrawer: () -> Unit,
  onStartDictation: () -> Unit,
  onClearChecked: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Direct single-tap sort options replace the tall Store Optimization box + DropdownMenu.
  val sortOptions = listOf(
    GrocerySortMode.AISLE_SORTED to "By Aisle",
    GrocerySortMode.BY_RECIPE to "By Meal",
    GrocerySortMode.URGENCY_FIRST to "Shortages",
  )

  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    // Row 1: Weighted Quick-Add bar + guaranteed on-screen 48.dp Mic IconButton + conditional Clear pill.
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, extendedColors.borderSubtle),
        shadowElevation = 0.dp,
        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
      ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          // Modifier.weight(1f) keeps placeholder to 1 line and leaves full room for 48.dp Mic.
          Row(
            modifier = Modifier
              .weight(1f)
              .defaultMinSize(minHeight = 48.dp)
              .clip(CircleShape)
              .clickable(onClick = onToggleAddDrawer)
              .padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(Icons.Filled.AddCircle, contentDescription = null, tint = extendedColors.herbalSage, modifier = Modifier.size(20.dp))
            Text(
              text = "Add or dictate item…",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f),
            )
          }

          IconButton(onClick = onStartDictation, modifier = Modifier.size(48.dp)) {
            Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(extendedColors.terracottaContainer), contentAlignment = Alignment.Center) {
              Icon(Icons.Filled.Mic, contentDescription = "Dictate grocery item", tint = extendedColors.onTerracottaContainer, modifier = Modifier.size(18.dp))
            }
          }
        }
      }

      // Only show Clear pill when checkedCount > 0 so "Clear (0)" never steals width from Quick-Add.
      if (checkedCount > 0) {
        Surface(
          shape = CircleShape,
          color = extendedColors.parchment,
          border = BorderStroke(1.dp, extendedColors.borderSubtle),
          shadowElevation = 0.dp,
          modifier = Modifier.defaultMinSize(minHeight = 48.dp).clip(CircleShape).clickable(onClick = onClearChecked),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Icon(Icons.Filled.ClearAll, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Text("Clear ($checkedCount)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
          }
        }
      }
    }

    // Row 2: 3 direct single-tap 48.dp sort pills (`By Aisle | By Meal | Shortages`).
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      sortOptions.forEach { (mode, label) ->
        val selected = sortMode == mode
        Surface(
          shape = CircleShape,
          color = if (selected) extendedColors.herbalSage else MaterialTheme.colorScheme.surfaceContainerLowest,
          border = if (selected) null else BorderStroke(1.dp, extendedColors.borderSubtle),
          shadowElevation = 0.dp,
          modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).clip(CircleShape).clickable { onSelectSortMode(mode) },
        ) {
          Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(
              text = label,
              style = MaterialTheme.typography.labelMedium,
              fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
              color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
              softWrap = false,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
    }
  }
}

/**
 * Checkable Smart Grocery item card with `48.dp` check target (`1.5.dp` visible unchecked border),
 * `maxLines = 2` title, humanized `kg`/`L` package + price line, full-width single-line meal
 * attribution (`"Mon Dinner · Dish Title"`), and `48.dp` `MoreVert` menu (`<= 3` metadata elements).
 */
@Composable
fun GroceryCheckableItemCard(
  item: GroceryListItem,
  isChecked: Boolean,
  onToggleChecked: () -> Unit,
  onQuickRestockOne: () -> Unit,
  onRemoveManualQueue: () -> Unit,
  currencySymbol: String = "₱",
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  var menuExpanded by remember { mutableStateOf(false) }
  // Combine humanized package text and currency estimate onto Metadata Line #1.
  val pkgAndPriceLine = "${humanizeGroceryPackageLabel(item)} · ${formatGroceryCurrency(item.estimatedCostUsd, currencySymbol)}"
  // Give meal attribution 100% of column width on Line #2 so dish titles never clip to "for Mon...".
  val primaryAttribution = item.mealUsageLabels.firstOrNull()?.let { firstLabel ->
    val clean = shortenMealAttribution(firstLabel)
    val extraCount = item.mealUsageLabels.size - 1
    if (extraCount > 0) "$clean +$extraCount" else clean
  }

  Card(
    modifier = modifier.fillMaxWidth(),
    shape = KitchenTheme.shapes.card2xl,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleChecked).padding(horizontal = 4.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // Leading 48.dp touch box framing a crisp 24.dp circle with 1.5.dp outline when unchecked.
      Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        val circleMod = if (isChecked) {
          Modifier.background(extendedColors.herbalSage)
        } else {
          Modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest).border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
        }
        Box(modifier = Modifier.size(24.dp).clip(CircleShape).then(circleMod), contentAlignment = Alignment.Center) {
          if (isChecked) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
        }
      }

      // Center column capped strictly at <= 3 metadata lines.
      Column(modifier = Modifier.weight(1f).padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        // Line 1: Up to 2 lines so market ingredient names ("Ripe Avocados (Antipolo Market)") breathe.
        Text(
          text = item.displayName,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          textDecoration = if (isChecked) TextDecoration.LineThrough else TextDecoration.None,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        // Line 2: Humanized whole-package restock quantity paired with estimated PHP ₱ price.
        Text(
          text = pkgAndPriceLine,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        // Line 3: Full-width meal attribution ("Mon Dinner · Dish Title") avoiding "for Mon..." clip.
        if (primaryAttribution != null) {
          Text(
            text = primaryAttribution,
            style = MaterialTheme.typography.bodySmall,
            color = extendedColors.herbalSage,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }

      // Trailing 48.dp overflow button offering 1-item restock or manual queue removal.
      Box {
        IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(48.dp)) {
          Icon(Icons.Filled.MoreVert, contentDescription = "More options for ${item.displayName}", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
          // Restocks this single item immediately when picked up early at a neighborhood stall.
          DropdownMenuItem(
            text = { Text("Restock into Pantry Now") },
            onClick = {
              menuExpanded = false
              onQuickRestockOne()
            },
          )
          // Only offer manual-queue removal when the item was added ad-hoc rather than from meal BOMs.
          if (item.isManuallyQueued) {
            DropdownMenuItem(
              text = { Text("Remove Manual Queue") },
              onClick = {
                menuExpanded = false
                onRemoveManualQueue()
              },
            )
          }
        }
      }
    }
  }
}

/**
 * Collapsible `"Checked Off"` accordion (`48.dp` touch rows, singular/plural count).
 */
@Composable
fun CheckedItemsAccordionCard(
  checkedCatalogItems: List<GroceryListItem>,
  checkedCustomItems: List<CustomGroceryItem>,
  isAccordionExpanded: Boolean,
  onToggleAccordion: () -> Unit,
  onUncheckItem: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Omit the Checked Off card completely when zero cart items are marked off.
  val totalChecked = checkedCatalogItems.size + checkedCustomItems.size
  if (totalChecked <= 0) return

  Card(
    modifier = modifier.fillMaxWidth(),
    shape = KitchenTheme.shapes.card2xl,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      // 48.dp accordion header row with proper "1 item" vs "N items" pluralization.
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .defaultMinSize(minHeight = 48.dp)
          .clip(RoundedCornerShape(12.dp))
          .clickable(onClick = onToggleAccordion)
          .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Box(modifier = Modifier.size(24.dp).clip(CircleShape).background(extendedColors.sageContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.DoneAll, contentDescription = null, tint = extendedColors.onSageContainer, modifier = Modifier.size(14.dp))
          }
          Text("Checked Off", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
          Surface(shape = CircleShape, color = extendedColors.parchment) {
            Text(
              text = if (totalChecked == 1) "1 item" else "$totalChecked items",
              style = MaterialTheme.typography.labelSmall,
              maxLines = 1,
              softWrap = false,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
          }
        }
        Icon(if (isAccordionExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
      }

      // Tapping any checked pill restores the item back into its active supermarket aisle.
      AnimatedVisibility(visible = isAccordionExpanded) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          checkedCatalogItems.forEach { item ->
            // Show "Plenty" for Tier-2 staples to preview EC-11 restock behavior before batch commit.
            CheckedRowPill(
              title = item.displayName,
              rightStatus = if (item.tier == IngredientTier.TIER_2_STAPLE) "Plenty" else "Ready",
              onUncheck = { onUncheckItem(item.ingredientId) },
            )
          }
          checkedCustomItems.forEach { custom ->
            CheckedRowPill(title = custom.displayName, rightStatus = custom.quantityNote, onUncheck = { onUncheckItem(custom.customId) })
          }
        }
      }
    }
  }
}

/**
 * Slim `56.dp` warm bottom summary bar with a `3.dp` cart-progress micro-bar, single-line
 * remaining items/cost summary, and `48.dp` primary action pill (`Restock (M)` or `Shop Mode`).
 */
@Composable
fun GroceryDockedSummaryBar(
  remainingItemCount: Int,
  remainingCostUsd: Double,
  checkedCount: Int,
  checkedRestockCount: Int,
  inStoreFocusMode: Boolean,
  onRestockCheckedBatch: () -> Unit,
  onToggleInStoreMode: () -> Unit,
  currencySymbol: String = "₱",
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Normalize checked vs total cart count for the 3.dp top-edge progress micro-bar.
  val totalCartItems = max(1, checkedCount + remainingItemCount)
  val progressFraction = (checkedCount.toFloat() / totalCartItems.toFloat()).coerceIn(0f, 1f)
  val itemWord = if (remainingItemCount == 1) "1 item" else "$remainingItemCount items"
  // Switch primary pill automatically to O(1) batch "Restock (M)" as soon as >= 1 item is checked.
  val primaryLabel = when {
    checkedRestockCount > 0 -> "Restock ($checkedRestockCount)"
    inStoreFocusMode -> "Exit Focus"
    else -> "Shop Mode"
  }

  // Warm parchment bar (`<= 1.dp` shadow) replaces heavy solid-dark floating footers.
  Surface(
    shape = RoundedCornerShape(20.dp),
    color = extendedColors.parchment,
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
    shadowElevation = 1.dp,
    modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp),
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      // Top inner 3.dp cart-progress micro-bar tracking checked vs remaining items.
      Box(modifier = Modifier.fillMaxWidth().height(3.dp).background(MaterialTheme.colorScheme.surfaceContainer)) {
        if (progressFraction > 0f) {
          Box(modifier = Modifier.fillMaxWidth(progressFraction).fillMaxHeight().background(extendedColors.herbalSage))
        }
      }

      // Single-line summary + 48.dp action pill resilient at 1.35x font scale on 360dp screens.
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "$itemWord left · ~${formatGroceryCurrency(remainingCostUsd, currencySymbol)}",
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.SemiBold,
          color = extendedColors.charcoalInk,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(10.dp))
        // Primary 48.dp herbal-sage pill executing O(1) batch restock or toggling Shop Focus mode.
        Surface(
          shape = CircleShape,
          color = extendedColors.herbalSage,
          modifier = Modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(CircleShape)
            .clickable { if (checkedRestockCount > 0) onRestockCheckedBatch() else onToggleInStoreMode() },
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Icon(Icons.Filled.ShoppingBag, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Text(primaryLabel, style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
          }
        }
      }
    }
  }
}

/**
 * Combined footer wrapper preserved for backward-compatible callers; delegates to
 * [CheckedItemsAccordionCard] and [GroceryDockedSummaryBar] without duplicate Meal Plan clutter.
 */
@Composable
fun CheckedAccordionAndFloatingFooter(
  checkedCatalogItems: List<GroceryListItem>,
  checkedCustomItems: List<CustomGroceryItem>,
  isAccordionExpanded: Boolean,
  onToggleAccordion: () -> Unit,
  onUncheckItem: (String) -> Unit,
  plannedMealCount: Int,
  tonightMealSummary: String,
  onOpenWeeklyMealPlan: () -> Unit,
  remainingItemCount: Int,
  remainingCostUsd: Double,
  checkedRestockCount: Int,
  inStoreFocusMode: Boolean,
  onRestockCheckedBatch: () -> Unit,
  onToggleInStoreMode: () -> Unit,
  currencySymbol: String = "₱",
) {
  // Sum catalog and custom checked rows so cart progress stays accurate across standalone callers.
  val totalChecked = checkedCatalogItems.size + checkedCustomItems.size
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    CheckedItemsAccordionCard(
      checkedCatalogItems = checkedCatalogItems,
      checkedCustomItems = checkedCustomItems,
      isAccordionExpanded = isAccordionExpanded,
      onToggleAccordion = onToggleAccordion,
      onUncheckItem = onUncheckItem,
    )
    GroceryDockedSummaryBar(
      remainingItemCount = remainingItemCount,
      remainingCostUsd = remainingCostUsd,
      checkedCount = totalChecked,
      checkedRestockCount = checkedRestockCount,
      inStoreFocusMode = inStoreFocusMode,
      onRestockCheckedBatch = onRestockCheckedBatch,
      onToggleInStoreMode = onToggleInStoreMode,
      currencySymbol = currencySymbol,
    )
  }
}

@Composable
private fun CheckedRowPill(title: String, rightStatus: String, onUncheck: () -> Unit) {
  val extendedColors = KitchenTheme.extendedColors
  // Enforce >= 48.dp touch target so shoppers can uncheck accidentally tapped items effortlessly.
  Surface(
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onUncheck),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
        Box(modifier = Modifier.size(22.dp).clip(CircleShape).background(extendedColors.herbalSage), contentAlignment = Alignment.Center) {
          Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
        }
        Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, textDecoration = TextDecoration.LineThrough, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
      }
      Spacer(modifier = Modifier.width(8.dp))
      Text(rightStatus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, softWrap = false)
    }
  }
}

// Humanizes raw gram/ml package strings ("4 packs · 4 kg") for clean supermarket scanning.
internal fun humanizeGroceryPackageLabel(item: GroceryListItem): String {
  val humanTotal = formatHumanGroceryQty(item.restockQuantity, item.baseUnit)
  val rawDesc = item.packageDescriptor.trim()
  // Use Char code 40 ('(') so both Kotlin stdlib and regex static analyzers stay balanced.
  val openParen = 40.toChar()
  return when {
    rawDesc.isEmpty() -> {
      val pkgWord = if (item.packagesToBuy == 1) "1 pack" else "${item.packagesToBuy} packs"
      "$pkgWord · $humanTotal"
    }
    rawDesc.contains(openParen) && (item.restockQuantity >= 1000.0 && item.baseUnit != BaseUnit.COUNT) ->
      "${rawDesc.substringBefore(openParen).trim()} · $humanTotal"
    else -> rawDesc.replace("•", "·")
  }
}

// Converts >= 1000 g/ml shopping quantities into kg / L for at-a-glance aisle legibility.
private fun formatHumanGroceryQty(quantity: Double, unit: BaseUnit): String = when {
  unit == BaseUnit.GRAM && quantity >= 1000.0 -> "${formatCompactGroceryDec(quantity / 1000.0)} kg"
  unit == BaseUnit.MILLILITER && quantity >= 1000.0 -> "${formatCompactGroceryDec(quantity / 1000.0)} L"
  else -> "${formatCompactGroceryDec(quantity)} ${unit.wireCode}"
}

private fun formatCompactGroceryDec(value: Double): String {
  val rounded = value.roundToInt()
  return when {
    abs(value - rounded.toDouble()) < 0.05 -> rounded.toString()
    else -> ((value * 10.0).roundToInt() / 10.0).toString()
  }
}

// Shortens "for Monday Dinner • Dish Title" -> "Mon Dinner · Dish Title" so dish names stay legible.
internal fun shortenMealAttribution(rawAttribution: String): String {
  var result = rawAttribution.trim()
  if (result.startsWith("for ", ignoreCase = true)) result = result.drop(4).trim()
  FULL_TO_SHORT_WEEKDAYS.forEach { (fullDay, shortDay) -> result = result.replace(fullDay, shortDay) }
  return result.replace(" • ", " · ")
}

// Formats estimated household currency totals in pure Kotlin stdlib (`₱1,240` default for PHP).
internal fun formatGroceryCurrency(amount: Double, currencySymbol: String = "₱"): String {
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
