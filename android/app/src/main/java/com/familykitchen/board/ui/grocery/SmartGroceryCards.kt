package com.familykitchen.board.ui.grocery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.BaseUnit
import com.familykitchen.board.domain.CustomGroceryItem
import com.familykitchen.board.domain.GroceryListItem
import com.familykitchen.board.domain.GrocerySortMode
import com.familykitchen.board.domain.HouseholdCurrency
import com.familykitchen.board.domain.HouseholdProfile
import com.familykitchen.board.domain.IngredientTier
import com.familykitchen.board.domain.MarketVenue
import com.familykitchen.board.domain.WeeklyGroceryDiff
import com.familykitchen.board.ui.theme.KitchenTheme
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// Modular SavorAI Smart Grocery List cards (`smart_grocery_list`):
// Expandable Pantry-Adjusted skipped-staples drawer (`Ran Low`), `All · Palengke · Supermarket`
// venue filter pills, `Uncheck · N` and `Restock · N` actions, and humanized package labels.

// Weekday abbreviations used to condense multi-word meal usage attributions onto 1 card line.
private val GROCERY_CARD_SHORT_WEEKDAYS: List<Pair<String, String>> = listOf(
  "Monday" to "Mon", "Tuesday" to "Tue", "Wednesday" to "Wed", "Thursday" to "Thu",
  "Friday" to "Fri", "Saturday" to "Sat", "Sunday" to "Sun",
)

/**
 * Top `List · N | Meal Plan` `48.dp` pill segmented switcher preserved when rendered standalone.
 */
@Composable
fun GroceryPlanSegmentedHeader(
  remainingCount: Int,
  onOpenWeeklyMealPlan: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Parchment capsule housing the active List · N tab alongside a 1-tap Meal Plan shortcut.
  Surface(
    modifier = modifier.fillMaxWidth(), shape = CircleShape, color = extendedColors.parchment,
    border = BorderStroke(1.dp, extendedColors.borderSubtle), shadowElevation = 0.dp,
  ) {
    Row(modifier = Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      // Left segment highlights active shopping list item count.
      Surface(
        shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, extendedColors.borderSubtle), shadowElevation = 0.dp,
        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
      ) {
        Row(
          modifier = Modifier.padding(vertical = 10.dp, horizontal = 10.dp),
          horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(Icons.Filled.ShoppingCart, contentDescription = null, tint = extendedColors.herbalSage, modifier = Modifier.size(17.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("List · $remainingCount", style = MaterialTheme.typography.labelMedium, color = extendedColors.herbalSage, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        }
      }

      // Right segment jumps back to the Weekly Meal Plan board in 1 tap.
      Surface(
        shape = CircleShape, color = Color.Transparent, shadowElevation = 0.dp,
        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).clip(CircleShape).clickable(onClick = onOpenWeeklyMealPlan),
      ) {
        Row(
          modifier = Modifier.padding(vertical = 10.dp, horizontal = 10.dp),
          horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
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
 * Optional QA Vault and `Batch #<batchWriteCallCount>` pill hidden on consumer screens by default.
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
  // Diagnostic telemetry pill verifying O(1) snapshot batch-write counts during QA Scenarios.
  Surface(
    modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clip(KitchenTheme.shapes.card2xl).clickable(onClick = onOpenQaLab),
    shape = KitchenTheme.shapes.card2xl, color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, extendedColors.borderSubtle), shadowElevation = 0.dp,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
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
 * Horizontal `48.dp` multi-market venue filter strip (`All · Palengke · Supermarket`), adapting
 * `"Palengke"` to `"Wet Market"` automatically when [currency] is not [HouseholdCurrency.PHP].
 */
@Composable
fun MarketVenueFilterRow(
  activeVenue: MarketVenue?,
  currency: HouseholdCurrency,
  allCount: Int,
  wetMarketCount: Int,
  supermarketCount: Int,
  onSelectVenue: (MarketVenue?) -> Unit,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Localize wet-market terminology: "Palengke" in PHP households, "Wet Market" globally.
  val wetMarketTitle = when (currency) {
    HouseholdCurrency.PHP -> "Palengke"
    else -> "Wet Market"
  }
  val venueOptions: List<Pair<MarketVenue?, String>> = listOf(
    null to "All · $allCount",
    MarketVenue.WET_MARKET to "$wetMarketTitle · $wetMarketCount",
    MarketVenue.SUPERMARKET to "Supermarket · $supermarketCount",
  )

  // Horizontal 48.dp filter strip keeping split-errand shopping effortless on 360dp phones.
  Row(modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    venueOptions.forEach { (venue, label) ->
      val selected = activeVenue == venue
      Surface(
        shape = CircleShape,
        color = if (selected) extendedColors.herbalSage else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = if (selected) null else BorderStroke(1.dp, extendedColors.borderSubtle),
        shadowElevation = 0.dp,
        modifier = Modifier.defaultMinSize(minHeight = 48.dp).clip(CircleShape).clickable { onSelectVenue(venue) },
      ) {
        Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
          Text(
            text = label, style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
            maxLines = 1, softWrap = false,
          )
        }
      }
    }
  }
}

/**
 * Expandable `Pantry-Adjusted List` banner displaying deduplication savings, a `3.dp` botanical
 * micro-bar, and—when tapped while `skippedStockedItems` is non-empty—a pre-shop verification
 * drawer listing every skipped item (`displayName` and `currentQuantityLabel`) with a 1-tap
 * `Ran Low` chip (`onRequeueSkippedStaple`).
 */
@Composable
fun PantryDeduplicationBanner(
  groceryDiff: WeeklyGroceryDiff,
  currencySymbol: String = "₱",
  modifier: Modifier = Modifier,
  onRequeueSkippedStaple: (String) -> Unit = {},
) {
  val extendedColors = KitchenTheme.extendedColors
  var isSkippedDrawerExpanded by rememberSaveable { mutableStateOf(false) }
  val skippedItems = groceryDiff.skippedStockedItems
  val canExpandDrawer = skippedItems.isNotEmpty()
  val skipped = groceryDiff.skippedStockedStapleCount
  // Compute deduplication ratio for the 3.dp botanical progress micro-bar.
  val totalConsidered = max(1, skipped + groceryDiff.groceryItems.size)
  val dedupFraction = (skipped.toFloat() / totalConsidered.toFloat()).coerceIn(0.10f, 1.0f)

  Card(
    modifier = modifier.fillMaxWidth().then(
      if (canExpandDrawer) Modifier.clip(KitchenTheme.shapes.card2xl).clickable { isSkippedDrawerExpanded = !isSkippedDrawerExpanded }
      else Modifier,
    ),
    shape = KitchenTheme.shapes.card2xl,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      // Header row pairing Pantry-Adjusted List title with currency savings badge and chevron.
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Icon(Icons.Filled.Inventory2, contentDescription = null, tint = extendedColors.herbalSage, modifier = Modifier.size(16.dp))
          Text("Pantry-Adjusted List", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          Surface(shape = CircleShape, color = extendedColors.sageContainer) {
            Text(
              text = "Saved ${formatGroceryCurrency(groceryDiff.deduplicationSavingsUsd, currencySymbol)}",
              style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = extendedColors.onSageContainer,
              maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
          }
          if (canExpandDrawer) {
            Icon(
              imageVector = if (isSkippedDrawerExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
              contentDescription = if (isSkippedDrawerExpanded) "Hide skipped staples" else "Verify skipped staples",
              tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp),
            )
          }
        }
      }

      // 3.dp botanical progress micro-bar visualizing home-stock deduplication coverage.
      Box(modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer)) {
        Box(modifier = Modifier.fillMaxWidth(dedupFraction).fillMaxHeight().clip(CircleShape).background(extendedColors.herbalSage))
      }

      // Hint that tapping expands the skipped-staples drawer when skipped items exist.
      val subtitleSuffix = if (canExpandDrawer) " · Tap to verify stock" else ""
      Text(
        text = "$skipped in-stock staples skipped across ${groceryDiff.plannedMealCount} meals$subtitleSuffix",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2, overflow = TextOverflow.Ellipsis,
      )

      // Expandable pre-shop verification drawer catching snacked-on milk or eggs before leaving home.
      AnimatedVisibility(visible = canExpandDrawer && isSkippedDrawerExpanded) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          HorizontalDivider(color = extendedColors.borderSubtle)
          skippedItems.forEach { skippedEntry ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
              Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(skippedEntry.displayName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("In Pantry · ${skippedEntry.currentQuantityLabel}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
              }
              // 1-tap 48.dp `Ran Low` chip marks the home item low and puts it right onto Shop.
              Surface(
                shape = CircleShape, color = extendedColors.terracottaContainer,
                border = BorderStroke(1.dp, extendedColors.borderSubtle),
                modifier = Modifier.defaultMinSize(minHeight = 48.dp).clip(CircleShape).clickable { onRequeueSkippedStaple(skippedEntry.ingredientId) },
              ) {
                Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                  Text("Ran Low", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = extendedColors.onTerracottaContainer, maxLines = 1, softWrap = false)
                }
              }
            }
          }
        }
      }
    }
  }
}

/**
 * Quick-Add and Dictate bar (`Modifier.weight(1f)` with `48.dp` `Mic` `IconButton` and conditional
 * `Uncheck · M` pill) paired with 3 direct `48.dp` sort pills (`By Aisle` · `By Meal` · `Shortages`).
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
  val sortOptions = listOf(
    GrocerySortMode.AISLE_SORTED to "By Aisle",
    GrocerySortMode.BY_RECIPE to "By Meal",
    GrocerySortMode.URGENCY_FIRST to "Shortages",
  )

  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      Surface(
        shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, extendedColors.borderSubtle), shadowElevation = 0.dp,
        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
      ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Row(
            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).clip(CircleShape)
              .clickable(onClick = onToggleAddDrawer).padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(Icons.Filled.AddCircle, contentDescription = null, tint = extendedColors.herbalSage, modifier = Modifier.size(20.dp))
            Text("Add or dictate item…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
          }
          // 48.dp offline speech recognition mic button for hands-free cart additions.
          IconButton(onClick = onStartDictation, modifier = Modifier.size(48.dp)) {
            Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(extendedColors.terracottaContainer), contentAlignment = Alignment.Center) {
              Icon(Icons.Filled.Mic, contentDescription = "Dictate grocery item", tint = extendedColors.onTerracottaContainer, modifier = Modifier.size(18.dp))
            }
          }
        }
      }

      // Punctuation-free Uncheck · M pill safely unchecks items without deleting planned deficits.
      if (checkedCount > 0) {
        Surface(
          shape = CircleShape, color = extendedColors.parchment,
          border = BorderStroke(1.dp, extendedColors.borderSubtle), shadowElevation = 0.dp,
          modifier = Modifier.defaultMinSize(minHeight = 48.dp).clip(CircleShape).clickable(onClick = onClearChecked),
        ) {
          Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Filled.ClearAll, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Text("Uncheck · $checkedCount", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
          }
        }
      }
    }

    // Direct 3-way 48.dp sort pills ordering by store aisle, meal usage, or acute shortages.
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
              text = label, style = MaterialTheme.typography.labelMedium,
              fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
              color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
              maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
    }
  }
}

/**
 * Checkable Smart Grocery item card with `48.dp` check target, humanized `kg` or `L` package and price
 * line (`" · Edited"` when overridden), single-line meal attribution, 1-tap `48.dp` `Edit` button,
 * and `48.dp` overflow menu.
 */
@Composable
fun GroceryCheckableItemCard(
  item: GroceryListItem,
  isChecked: Boolean,
  onToggleChecked: () -> Unit,
  onQuickRestockOne: () -> Unit,
  onRemoveManualQueue: () -> Unit,
  onEditItem: (() -> Unit)? = null,
  onSkipForTrip: (() -> Unit)? = null,
  currencySymbol: String = "₱",
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  var menuExpanded by remember { mutableStateOf(false) }
  // Append " · Edited" whenever the shopper overrode the computed buy quantity for this trip.
  val editedSuffix = if (item.isQuantityOverridden) " · Edited" else ""
  val pkgAndPriceLine = "${humanizeGroceryPackageLabel(item)} · ${formatGroceryCurrency(item.estimatedCostUsd, currencySymbol)}$editedSuffix"
  val primaryAttribution = item.mealUsageLabels.firstOrNull()?.let { firstLabel ->
    val clean = shortenMealAttribution(firstLabel)
    val extraCount = item.mealUsageLabels.size - 1
    if (extraCount > 0) "$clean +$extraCount" else clean
  }

  Card(
    modifier = modifier.fillMaxWidth(), shape = KitchenTheme.shapes.card2xl,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleChecked).padding(horizontal = 4.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
      // 48.dp circular check hitbox for easy one-handed cart checking in the aisle.
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

      // Center column displays item title, humanized package + price, and meal attribution.
      Column(modifier = Modifier.weight(1f).padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
          text = item.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
          textDecoration = if (isChecked) TextDecoration.LineThrough else TextDecoration.None,
          maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Text(pkgAndPriceLine, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // Show condensed weekday meal attribution when scheduled meals reserved this item.
        if (primaryAttribution != null) {
          Text(primaryAttribution, style = MaterialTheme.typography.bodySmall, color = extendedColors.herbalSage, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
        }
      }

      // Direct 48.dp Edit pencil icon opening EditGroceryCatalogItemSheet in 1 tap.
      if (onEditItem != null) {
        IconButton(onClick = onEditItem, modifier = Modifier.size(48.dp)) {
          Icon(Icons.Filled.Edit, contentDescription = "Edit ${item.displayName}", tint = extendedColors.herbalSage, modifier = Modifier.size(19.dp))
        }
      }

      // Overflow menu providing punctuation-clean `Edit`, quick restock, trip skip, and queue removal.
      Box {
        IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(48.dp)) {
          Icon(Icons.Filled.MoreVert, contentDescription = "More options for ${item.displayName}", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
          if (onEditItem != null) {
            DropdownMenuItem(text = { Text("Edit") }, onClick = { menuExpanded = false; onEditItem() })
          }
          DropdownMenuItem(text = { Text("Restock into Pantry Now") }, onClick = { menuExpanded = false; onQuickRestockOne() })
          if (onSkipForTrip != null) {
            DropdownMenuItem(text = { Text("Skip on This Trip") }, onClick = { menuExpanded = false; onSkipForTrip() })
          }
          if (item.isManuallyQueued) {
            DropdownMenuItem(text = { Text("Remove Manual Queue") }, onClick = { menuExpanded = false; onRemoveManualQueue() })
          }
        }
      }
    }
  }
}

/**
 * Collapsible `"Checked Off"` accordion (`48.dp` touch rows, singular or plural count).
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
  val totalChecked = checkedCatalogItems.size + checkedCustomItems.size
  if (totalChecked <= 0) return

  // Keep checked-off cart items neatly tucked at the bottom until ready to unpack at home.
  Card(
    modifier = modifier.fillMaxWidth(), shape = KitchenTheme.shapes.card2xl,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clip(RoundedCornerShape(12.dp))
          .clickable(onClick = onToggleAccordion).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Box(modifier = Modifier.size(24.dp).clip(CircleShape).background(extendedColors.sageContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.DoneAll, contentDescription = null, tint = extendedColors.onSageContainer, modifier = Modifier.size(14.dp))
          }
          Text("Checked Off", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
          Surface(shape = CircleShape, color = extendedColors.parchment) {
            Text(
              text = if (totalChecked == 1) "1 item" else "$totalChecked items",
              style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
          }
        }
        Icon(if (isAccordionExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
      }

      AnimatedVisibility(visible = isAccordionExpanded) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          checkedCatalogItems.forEach { item ->
            groceryCardCheckedRowPill(
              title = item.displayName,
              rightStatus = if (item.tier == IngredientTier.TIER_2_STAPLE) "Plenty" else "Ready",
              onUncheck = { onUncheckItem(item.ingredientId) },
            )
          }
          checkedCustomItems.forEach { custom ->
            groceryCardCheckedRowPill(title = custom.displayName, rightStatus = custom.quantityNote, onUncheck = { onUncheckItem(custom.customId) })
          }
        }
      }
    }
  }
}

/**
 * Slim `56.dp` warm bottom summary bar with a `3.dp` cart-progress micro-bar, single-line
 * remaining items and cost summary, and `48.dp` primary action pill (`Restock · M` or `Shop Mode`).
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
  // Compute cart completion progress ratio across checked and remaining items.
  val extendedColors = KitchenTheme.extendedColors
  val totalCartItems = max(1, checkedCount + remainingItemCount)
  val progressFraction = (checkedCount.toFloat() / totalCartItems.toFloat()).coerceIn(0f, 1f)
  val itemWord = if (remainingItemCount == 1) "1 item" else "$remainingItemCount items"
  val actionableCheckedCount = if (checkedCount > 0) checkedCount else checkedRestockCount
  // Punctuation-free Restock · M label opens UnpackGroceriesSheet or commits O(1) restock.
  val primaryLabel = when {
    actionableCheckedCount > 0 -> "Restock · $actionableCheckedCount"
    inStoreFocusMode -> "Exit Focus"
    else -> "Shop Mode"
  }

  // Docked warm parchment card floating cleanly above bottom navigation.
  Surface(
    shape = RoundedCornerShape(20.dp), color = extendedColors.parchment,
    border = BorderStroke(1.dp, extendedColors.borderSubtle), shadowElevation = 1.dp,
    modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp),
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      // Top 3.dp cart-completion bar fills in herbal sage as items are checked off.
      Box(modifier = Modifier.fillMaxWidth().height(3.dp).background(MaterialTheme.colorScheme.surfaceContainer)) {
        if (progressFraction > 0f) {
          Box(modifier = Modifier.fillMaxWidth(progressFraction).fillMaxHeight().background(extendedColors.herbalSage))
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
      ) {
        // Weighted summary text prevents pushing the 48.dp Restock · M CTA off narrow 360dp screens.
        Text(
          text = "$itemWord left · ~${formatGroceryCurrency(remainingCostUsd, currencySymbol)}",
          style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
          color = extendedColors.charcoalInk, maxLines = 1, overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(10.dp))
        // Primary 48.dp pill triggers UnpackGroceriesSheet when items are checked, else Shop Mode.
        Surface(
          shape = CircleShape, color = extendedColors.herbalSage,
          modifier = Modifier.defaultMinSize(minHeight = 48.dp).clip(CircleShape)
            .clickable { if (actionableCheckedCount > 0) onRestockCheckedBatch() else onToggleInStoreMode() },
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
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
 * [CheckedItemsAccordionCard] and [GroceryDockedSummaryBar].
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
  val totalChecked = checkedCatalogItems.size + checkedCustomItems.size
  // Render Checked Off accordion directly above the docked Restock · M summary bar.
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    CheckedItemsAccordionCard(
      checkedCatalogItems = checkedCatalogItems, checkedCustomItems = checkedCustomItems,
      isAccordionExpanded = isAccordionExpanded, onToggleAccordion = onToggleAccordion, onUncheckItem = onUncheckItem,
    )
    GroceryDockedSummaryBar(
      remainingItemCount = remainingItemCount, remainingCostUsd = remainingCostUsd,
      checkedCount = totalChecked, checkedRestockCount = checkedRestockCount,
      inStoreFocusMode = inStoreFocusMode, onRestockCheckedBatch = onRestockCheckedBatch,
      onToggleInStoreMode = onToggleInStoreMode, currencySymbol = currencySymbol,
    )
  }
}

// Single 48.dp checked-off row pill allowing 1-tap uncheck inside CheckedItemsAccordionCard.
@Composable
private fun groceryCardCheckedRowPill(title: String, rightStatus: String, onUncheck: () -> Unit) {
  val extendedColors = KitchenTheme.extendedColors
  Surface(
    shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest,
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onUncheck),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
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

// Preserved verbatim for GroceryAndCatalogEditTest.kt:117 ("4 packs · 4 kg", "Custom · 1.5 kg").
internal fun humanizeGroceryPackageLabel(item: GroceryListItem): String {
  val humanTotal = groceryCardFormatHumanQty(item.restockQuantity, item.baseUnit)
  val rawDesc = item.packageDescriptor.trim()
  val openParen = 40.toChar()
  return when {
    rawDesc.isEmpty() -> {
      val pkgWord = if (item.packagesToBuy == 1) "1 pack" else "${item.packagesToBuy} packs"
      "$pkgWord · $humanTotal"
    }
    rawDesc.startsWith("Custom") && rawDesc.contains(openParen) -> "Custom · $humanTotal"
    rawDesc.contains(openParen) && (item.restockQuantity >= 1000.0 && item.baseUnit != BaseUnit.COUNT) ->
      "${rawDesc.substringBefore(openParen).trim()} · $humanTotal"
    else -> rawDesc.replace("•", "·")
  }
}

// Convert >= 1000 g or ml into clean kg or L strings for compact shopping row subtitles.
private fun groceryCardFormatHumanQty(quantity: Double, unit: BaseUnit): String = when {
  unit == BaseUnit.GRAM && quantity >= 1000.0 -> "${groceryCardFormatCompactDec(quantity / 1000.0)} kg"
  unit == BaseUnit.MILLILITER && quantity >= 1000.0 -> "${groceryCardFormatCompactDec(quantity / 1000.0)} L"
  else -> "${groceryCardFormatCompactDec(quantity)} ${unit.wireCode}"
}

// Format whole numbers without ".0" noise and fractional quantities to 1 decimal place.
private fun groceryCardFormatCompactDec(value: Double): String {
  val rounded = value.roundToInt()
  return when {
    abs(value - rounded.toDouble()) < 0.05 -> rounded.toString()
    else -> ((value * 10.0).roundToInt() / 10.0).toString()
  }
}

// Condense verbose weekday meal attributions into crisp single-line shopping context.
internal fun shortenMealAttribution(rawAttribution: String): String {
  var result = rawAttribution.trim()
  if (result.startsWith("for ", ignoreCase = true)) result = result.drop(4).trim()
  GROCERY_CARD_SHORT_WEEKDAYS.forEach { (fullDay, shortDay) -> result = result.replace(fullDay, shortDay) }
  return result.replace(" • ", " · ")
}

// Format monetary cart totals in PHP (`₱1,325`), CFA, or decimal Western currencies.
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
