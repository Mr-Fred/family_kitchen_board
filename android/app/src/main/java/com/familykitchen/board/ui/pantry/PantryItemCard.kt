package com.familykitchen.board.ui.pantry

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.BaseUnit
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.IngredientCategory
import com.familykitchen.board.domain.IngredientTier
import com.familykitchen.board.domain.PantryLedgerEntry
import com.familykitchen.board.domain.StapleStatus
import com.familykitchen.board.domain.StorageLocation
import com.familykitchen.board.ui.theme.ExtendedKitchenColors
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme
import kotlin.math.abs
import kotlin.math.roundToInt

// SavorAI Fridge & Produce interactive cards, 2-column Dry Goods & Freezer grid tiles,
// and PantryLedgerItemCard enforcing EC-02, EC-03, EC-10, and EC-11 with 48.dp touch targets.

/**
 * Editorial storage-zone header pairing an icon + title with a singular/plural item count label.
 *
 * @param icon Leading zone [ImageVector] (`AcUnit` for Fridge/Freezer, `Kitchen` for Dry Goods).
 * @param title Zone heading (`"Fridge & Produce"`, `"Dry Goods & Pantry"`, or `"Freezer"`).
 * @param itemCount Number of stocked ledger records inside the zone.
 * @param modifier Optional Compose [Modifier].
 */
@Composable
fun PantryZoneSectionHeader(
  icon: ImageVector,
  title: String,
  itemCount: Int,
  modifier: Modifier = Modifier,
) {
  // Weight the left heading row so elevated 1.35x font scales never collide with the item count.
  Row(
    modifier = modifier.fillMaxWidth().padding(top = 4.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(
      modifier = Modifier.weight(1f),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Icon(icon, contentDescription = null, tint = KitchenTheme.extendedColors.herbalSage, modifier = Modifier.size(20.dp))
      Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Spacer(modifier = Modifier.width(8.dp))
    // Enforce non-wrapping singular/plural count ("1 item" vs "5 items").
    Text(
      text = formatItemCount(itemCount),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      softWrap = false,
    )
  }
}

/**
 * `Fridge & Produce` section wrapper preserved for standalone callers; renders
 * [PantryZoneSectionHeader] and individual [FridgeProduceInteractiveRow] cards.
 */
@Composable
fun FridgeProduceSection(
  entries: List<PantryLedgerEntry>,
  catalogById: Map<String, IngredientCatalogItem>,
  queuedGroceryIds: Set<String>,
  onAdjustQuantity: (ingredientId: String, deltaQuantity: Double) -> Unit,
  onToggleLocallyAvailable: (ingredientId: String, isLocallyAvailable: Boolean) -> Unit,
  onToggleManualQueue: (ingredientId: String) -> Unit,
  modifier: Modifier = Modifier,
) {
  // Skip orphaned ledger records cleanly if an item was removed from catalog.
  val validPairs = entries.mapNotNull { entry -> catalogById[entry.ingredientId]?.let { entry to it } }
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    PantryZoneSectionHeader(Icons.Filled.AcUnit, "Fridge & Produce", validPairs.size)
    validPairs.forEach { (entry, item) ->
      FridgeProduceInteractiveRow(
        catalogItem = item,
        entry = entry,
        isQueuedOnGrocery = item.ingredientId in queuedGroceryIds,
        onAdjustQuantity = { delta -> onAdjustQuantity(item.ingredientId, delta) },
        onToggleLocallyAvailable = { next -> onToggleLocallyAvailable(item.ingredientId, next) },
        onToggleManualQueue = { onToggleManualQueue(item.ingredientId) },
      )
    }
  }
}

/**
 * Individual `Fridge & Produce` card with true remaining shelf-life micro-bar (`3.dp`), 2-line
 * unwrapped ingredient title, singular/plural day countdown, and `48.dp` **Fridge Pulse** controls.
 */
@Composable
fun FridgeProduceInteractiveRow(
  catalogItem: IngredientCatalogItem,
  entry: PantryLedgerEntry,
  isQueuedOnGrocery: Boolean,
  onAdjustQuantity: (Double) -> Unit,
  onToggleLocallyAvailable: (Boolean) -> Unit,
  onToggleManualQueue: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var expanded by rememberSaveable(catalogItem.ingredientId) { mutableStateOf(false) }
  val extendedColors = KitchenTheme.extendedColors
  val daysLeft = entry.expiresInDays ?: catalogItem.defaultShelfLifeDays
  // Use understated botanical, toasted-amber, and terracotta tokens by remaining freshness window.
  val daysColor = when {
    daysLeft <= 2 -> extendedColors.terracotta
    daysLeft <= 4 -> extendedColors.expiringSoonIndicator
    else -> extendedColors.herbalSage
  }
  // Compute true REMAINING shelf-life fraction so fresh items look full and urgent items look low.
  val baselineDays = catalogItem.defaultShelfLifeDays.coerceAtLeast(3).toFloat()
  val remainingFraction = when {
    daysLeft <= 0 -> 0.08f
    else -> (daysLeft.toFloat() / baselineDays).coerceIn(0.12f, 1.0f)
  }
  // Fridge Pulse steps countable units by +-1 and weight/volume items by +-100 (clamped at 0 in domain).
  val pulseStep = if (catalogItem.baseUnit == BaseUnit.COUNT) 1.0 else 100.0

  // Tapping any Fridge card toggles inline 48.dp Fridge Pulse steppers and EC-02/EC-03 switch.
  Surface(
    shape = KitchenTheme.shapes.card2xl,
    color = if (expanded) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerLowest,
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
    shadowElevation = 0.dp,
    modifier = modifier.fillMaxWidth().clip(KitchenTheme.shapes.card2xl).clickable { expanded = !expanded },
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
          // Warm botanical badge replacing cold surface wells with zero network image dependencies.
          Box(
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(extendedColors.sageContainer),
            contentAlignment = Alignment.Center,
          ) {
            Icon(Icons.Filled.Eco, contentDescription = null, tint = extendedColors.onSageContainer, modifier = Modifier.size(20.dp))
          }
          Spacer(modifier = Modifier.width(12.dp))
          Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // Allow 2 lines so "Atlantic Salmon Fillets" never truncates prematurely.
            Text(
              text = catalogItem.displayName,
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
            val subZone = entry.storageSubLocation ?: "Fridge Shelf"
            val unavailableTag = if (!catalogItem.isLocallyAvailable) " · Unavailable" else ""
            Text(
              text = "${formatHumanQuantity(entry.currentQuantity, catalogItem.baseUnit)} · $subZone$unavailableTag",
              style = MaterialTheme.typography.bodySmall,
              color = if (!catalogItem.isLocallyAvailable) extendedColors.terracotta else MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
        Spacer(modifier = Modifier.width(12.dp))
        // Right-aligned singular/plural countdown + understated 3.dp remaining shelf-life micro-bar.
        Column(horizontalAlignment = Alignment.End) {
          Text(formatDaysLeft(daysLeft), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = daysColor, maxLines = 1, softWrap = false)
          Spacer(modifier = Modifier.height(6.dp))
          Box(modifier = Modifier.width(56.dp).height(3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer)) {
            Box(modifier = Modifier.fillMaxWidth(remainingFraction).fillMaxHeight().clip(CircleShape).background(daysColor))
          }
        }
      }

      // Expandable 48.dp Fridge Pulse +- steppers (EC-10), Local Market switch, and +Need to Buy.
      AnimatedVisibility(visible = expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          HorizontalDivider(color = extendedColors.borderSubtle)
          // 48.dp FilledTonalIconButtons prevent accidental mis-taps with wet kitchen hands.
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              // Disable minus stepper at 0.0 so UI mirrors domain EC-10 zero-floor clamp.
              FilledTonalIconButton(
                onClick = { onAdjustQuantity(-pulseStep) },
                enabled = entry.currentQuantity > 0.0,
                modifier = Modifier.size(48.dp),
              ) { Icon(Icons.Filled.Remove, contentDescription = "Decrease ${catalogItem.displayName}", modifier = Modifier.size(20.dp)) }
              Text("±${formatHumanQuantity(pulseStep, catalogItem.baseUnit)}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
              FilledTonalIconButton(
                onClick = { onAdjustQuantity(pulseStep) },
                modifier = Modifier.size(48.dp),
              ) { Icon(Icons.Filled.Add, contentDescription = "Increase ${catalogItem.displayName}", modifier = Modifier.size(20.dp)) }
            }
            // Manual +Need to Buy queue pill feeds directly into computeWeeklyGroceryDiff.
            OutlinedButton(
              onClick = onToggleManualQueue,
              shape = CircleShape,
              contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
              modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
              Text(if (isQueuedOnGrocery) "Queued ✓" else "+ Need to Buy", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
            }
          }
          // Real-time local market availability switch controlling EC-02 hide and EC-03 auto-swap.
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = if (catalogItem.isLocallyAvailable) "Local Market: Available" else "Unavailable Locally (EC-02/EC-03)",
              style = MaterialTheme.typography.labelSmall,
              modifier = Modifier.weight(1f),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Switch(checked = catalogItem.isLocallyAvailable, onCheckedChange = onToggleLocallyAvailable)
          }
        }
      }
    }
  }
}

/**
 * 2-Column `Dry Goods & Pantry` or `Freezer` grid section wrapper preserved for standalone callers.
 */
@Composable
fun DryGoodsAndFreezerGridSection(
  sectionTitle: String,
  entries: List<PantryLedgerEntry>,
  catalogById: Map<String, IngredientCatalogItem>,
  onSelectStapleStatus: (ingredientId: String, newStatus: StapleStatus) -> Unit,
  onAdjustQuantity: (ingredientId: String, deltaQuantity: Double) -> Unit,
  modifier: Modifier = Modifier,
) {
  val validPairs = entries.mapNotNull { entry -> catalogById[entry.ingredientId]?.let { entry to it } }
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    PantryZoneSectionHeader(Icons.Filled.Kitchen, sectionTitle, validPairs.size)
    validPairs.chunked(2).forEach { pair ->
      DryGoodsGridPairRow(pair = pair, onSelectStapleStatus = onSelectStapleStatus, onAdjustQuantity = onAdjustQuantity)
    }
  }
}

/**
 * Equal-height (`IntrinsicSize.Max`) 2-column row of [DryGoodsGridTile] cards emitted lazily inside
 * `SmartPantryScreen`.
 */
@Composable
fun DryGoodsGridPairRow(
  pair: List<Pair<PantryLedgerEntry, IngredientCatalogItem>>,
  onSelectStapleStatus: (ingredientId: String, newStatus: StapleStatus) -> Unit,
  onAdjustQuantity: (ingredientId: String, deltaQuantity: Double) -> Unit,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // IntrinsicSize.Max aligns paired tiles evenly when one card expands or has a 2-line title.
  Row(
    modifier = modifier.fillMaxWidth().height(IntrinsicSize.Max),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    pair.forEach { (entry, item) ->
      DryGoodsGridTile(
        item = item,
        entry = entry,
        extendedColors = extendedColors,
        onSelectStapleStatus = { nextStatus -> onSelectStapleStatus(item.ingredientId, nextStatus) },
        onAdjustTier1Quantity = { delta -> onAdjustQuantity(item.ingredientId, delta) },
        modifier = Modifier.weight(1f).fillMaxHeight(),
      )
    }
    if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
  }
}

@Composable
private fun DryGoodsGridTile(
  item: IngredientCatalogItem,
  entry: PantryLedgerEntry,
  extendedColors: ExtendedKitchenColors,
  onSelectStapleStatus: (StapleStatus) -> Unit,
  onAdjustTier1Quantity: (Double) -> Unit,
  modifier: Modifier = Modifier,
) {
  var expanded by rememberSaveable(item.ingredientId) { mutableStateOf(false) }
  val isStaple = item.tier == IngredientTier.TIER_2_STAPLE
  val isLowStaple = isStaple && entry.stapleStatus == StapleStatus.LOW
  val isOut = entry.isOutOfStock || (isStaple && entry.stapleStatus == StapleStatus.OUT)
  val pkgCap = item.defaultPackageQuantity.coerceAtLeast(1.0)
  val isLowTier1 = !isStaple && !isOut && entry.currentQuantity < (pkgCap * 0.5)
  val pulseStep = if (item.baseUnit == BaseUnit.COUNT) 1.0 else 100.0

  // Map stock state to understated semantic dot, single status pill, and 3.dp micro-bar fill.
  val statusColor = when {
    isOut -> extendedColors.terracotta
    isLowStaple || isLowTier1 -> extendedColors.expiringSoonIndicator
    else -> extendedColors.herbalSage
  }
  val tagBg = when {
    isOut -> MaterialTheme.colorScheme.errorContainer
    isLowStaple || isLowTier1 -> extendedColors.tertiaryFixed
    else -> extendedColors.sageContainer
  }
  val tagFg = when {
    isOut -> MaterialTheme.colorScheme.onErrorContainer
    isLowStaple || isLowTier1 -> extendedColors.onTertiaryFixed
    else -> extendedColors.onSageContainer
  }
  val tagText = when {
    isOut -> "Out"
    isLowStaple || isLowTier1 -> "Low"
    isStaple -> "Plenty"
    else -> "Stocked"
  }
  val stockBarFill = when {
    isOut -> 0.08f
    isLowStaple || isLowTier1 -> 0.40f
    isStaple -> 1.0f
    else -> (entry.currentQuantity / pkgCap).toFloat().coerceIn(0.45f, 1.0f)
  }

  // Tapping reveals two-way 48.dp inline steppers (Tier-1 -/+) or Tier-2 status cycle pill.
  Card(
    onClick = { expanded = !expanded },
    modifier = modifier,
    shape = KitchenTheme.shapes.card2xl,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().fillMaxHeight().padding(12.dp),
      verticalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Row 1: Subtle 8.dp status dot + single non-wrapping status capsule (<= 3 metadata items).
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(statusColor))
          Surface(shape = CircleShape, color = tagBg) {
            Text(
              text = tagText,
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.Bold,
              color = tagFg,
              maxLines = 1,
              softWrap = false,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
          }
        }

        // Uniform 2-line title so "Crusty Artisan Baguette" & "Rolled Whole Grain Oats" read cleanly.
        Text(
          text = item.displayName,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          maxLines = 2,
          minLines = 2,
          overflow = TextOverflow.Ellipsis,
        )

        // Stack clean quantity above full-width sub-location to prevent zero-gap collisions & "•".
        val cleanPkgNote = entry.packageNote?.substringBefore("•")?.trim()?.takeIf { it.isNotEmpty() }
        val quantityLine = cleanPkgNote ?: formatHumanQuantity(entry.currentQuantity, item.baseUnit)
        Text(
          text = quantityLine,
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.fillMaxWidth(),
        )
        Text(
          text = entry.storageSubLocation ?: "Pantry Shelf",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.fillMaxWidth(),
        )
      }

      Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Understated 3.dp stock micro-bar across the base of every Dry Goods / Freezer tile.
        Box(modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer)) {
          Box(modifier = Modifier.fillMaxWidth(stockBarFill).fillMaxHeight().clip(CircleShape).background(statusColor))
        }

        // Inline 48.dp two-way controls so Tier-1 items decrement as easily as they increment.
        AnimatedVisibility(visible = expanded) {
          when {
            isStaple -> {
              // Cycle Tier-2 staples through PLENTY -> LOW -> OUT -> PLENTY (EC-11).
              val nextStatus = when (entry.stapleStatus) {
                StapleStatus.PLENTY -> StapleStatus.LOW
                StapleStatus.LOW -> StapleStatus.OUT
                StapleStatus.OUT -> StapleStatus.PLENTY
              }
              OutlinedButton(
                onClick = { onSelectStapleStatus(nextStatus) },
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
              ) {
                Text("Set ${nextStatus.name}", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
              }
            }

            else -> {
              // Provide symmetric 48.dp - and + buttons inside narrow 2-column Dry Goods / Freezer tiles.
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                FilledTonalIconButton(
                  onClick = { onAdjustTier1Quantity(-pulseStep) },
                  enabled = entry.currentQuantity > 0.0,
                  modifier = Modifier.size(48.dp),
                ) { Icon(Icons.Filled.Remove, contentDescription = "Decrease ${item.displayName}", modifier = Modifier.size(18.dp)) }
                FilledTonalIconButton(
                  onClick = { onAdjustTier1Quantity(pulseStep) },
                  modifier = Modifier.size(48.dp),
                ) { Icon(Icons.Filled.Add, contentDescription = "Increase ${item.displayName}", modifier = Modifier.size(18.dp)) }
              }
            }
          }
        }
      }
    }
  }
}

/**
 * Full-detail Pantry Ledger card preserved for `LOCAL_MARKET` availability inspection and
 * standalone item editing (`EC-02`, `EC-03`, `EC-10`, `EC-11`) with `48.dp` touch controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantryLedgerItemCard(
  catalogItem: IngredientCatalogItem,
  pantryEntry: PantryLedgerEntry?,
  isQueuedOnGrocery: Boolean,
  onAdjustQuantity: (Double) -> Unit,
  onSelectStapleStatus: (StapleStatus) -> Unit,
  onToggleLocallyAvailable: (Boolean) -> Unit,
  onToggleManualQueue: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  val currentQty = pantryEntry?.currentQuantity ?: 0.0
  val activeStapleStatus = pantryEntry?.stapleStatus ?: StapleStatus.PLENTY
  val pulseStep = if (catalogItem.baseUnit == BaseUnit.COUNT) 1.0 else 100.0

  // Terracotta border warns when `isLocallyAvailable == false` (triggering EC-02 hide or EC-03 swap).
  Card(
    modifier = modifier.fillMaxWidth(),
    shape = KitchenTheme.shapes.card2xl,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    border = BorderStroke(1.dp, if (!catalogItem.isLocallyAvailable) extendedColors.terracotta else extendedColors.borderSubtle),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
          Text(catalogItem.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
          Text(catalogItem.category.displayLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(
          onClick = onToggleManualQueue,
          shape = CircleShape,
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        ) {
          Icon(if (isQueuedOnGrocery) Icons.Filled.Check else Icons.Filled.ShoppingCart, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text(if (isQueuedOnGrocery) "Queued" else "+ Need to Buy", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
        }
      }

      // Local market availability toggle controlling AvailabilityFilter schedulability in real time.
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = if (catalogItem.isLocallyAvailable) "Available Locally" else "Unavailable Locally (EC-02/EC-03)",
          style = MaterialTheme.typography.labelMedium,
          modifier = Modifier.weight(1f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Switch(checked = catalogItem.isLocallyAvailable, onCheckedChange = onToggleLocallyAvailable)
      }

      // Render Tier-2 tri-state segmented control (EC-11) vs Tier-1 48.dp steppers (EC-10).
      when (catalogItem.tier) {
        IngredientTier.TIER_2_STAPLE -> {
          SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            StapleStatus.entries.forEachIndexed { idx, status ->
              SegmentedButton(
                selected = activeStapleStatus == status,
                onClick = { onSelectStapleStatus(status) },
                shape = SegmentedButtonDefaults.itemShape(index = idx, count = StapleStatus.entries.size),
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                label = { Text(status.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false) },
              )
            }
          }
        }

        IngredientTier.TIER_1_QUANTITATIVE -> {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "In Stock: ${formatHumanQuantity(currentQty, catalogItem.baseUnit)}",
              style = MaterialTheme.typography.labelLarge,
              modifier = Modifier.weight(1f),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              FilledTonalIconButton(onClick = { onAdjustQuantity(-pulseStep) }, enabled = currentQty > 0.0, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Remove, contentDescription = "Decrease ${catalogItem.displayName}")
              }
              FilledTonalIconButton(onClick = { onAdjustQuantity(pulseStep) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "Increase ${catalogItem.displayName}")
              }
            }
          }
        }
      }
    }
  }
}

// Formats remaining freshness days with proper singular/plural grammar ("1 day left" vs "3 days left").
internal fun formatDaysLeft(days: Int): String = when {
  days <= 0 -> "Expired"
  days == 1 -> "1 day left"
  else -> "$days days left"
}

// Formats section item counts with proper singular/plural grammar ("1 item" vs "5 items").
internal fun formatItemCount(count: Int): String = when (count) {
  1 -> "1 item"
  else -> "$count items"
}

// Converts >= 1000 g/ml into clean human units ("1.6 kg", "2 kg", "1 L") for compact card labels.
internal fun formatHumanQuantity(quantity: Double, unit: BaseUnit): String = when {
  unit == BaseUnit.GRAM && quantity >= 1000.0 -> "${formatCompactDecimal(quantity / 1000.0)} kg"
  unit == BaseUnit.MILLILITER && quantity >= 1000.0 -> "${formatCompactDecimal(quantity / 1000.0)} L"
  else -> formatPantryQuantityWithUnit(quantity, unit)
}

// Rounds to whole numbers or 1 decimal place without trailing ".0" noise.
private fun formatCompactDecimal(value: Double): String {
  val rounded = value.roundToInt()
  return when {
    abs(value - rounded.toDouble()) < 0.05 -> rounded.toString()
    else -> ((value * 10.0).roundToInt() / 10.0).toString()
  }
}

// Formats a canonical quantity without trailing ".0" noise on whole numbers.
internal fun formatPantryQuantityWithUnit(quantity: Double, unit: BaseUnit): String =
  "${formatCompactDecimal(quantity)} ${unit.wireCode}"

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun PantryItemCardCompactScalePreview() {
  // Verify compact 360dp 2-column Dry Goods alignment and singular "1 day left" Fridge row.
  val baguette = IngredientCatalogItem("ing_baguette", "Crusty Artisan Baguette", IngredientCategory.BAKERY_GRAINS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.COUNT, 2.0, defaultStorageLocation = StorageLocation.PANTRY)
  val oats = IngredientCatalogItem("ing_oats", "Rolled Whole Grain Oats", IngredientCategory.BAKERY_GRAINS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 1000.0, defaultStorageLocation = StorageLocation.PANTRY)
  val salmon = IngredientCatalogItem("ing_salmon", "Atlantic Salmon Fillets", IngredientCategory.PROTEIN, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 800.0, defaultStorageLocation = StorageLocation.FRIDGE, defaultShelfLifeDays = 3)
  FamilyKitchenBoardTheme {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      FridgeProduceInteractiveRow(
        catalogItem = salmon,
        entry = PantryLedgerEntry("ing_salmon", IngredientTier.TIER_1_QUANTITATIVE, 400.0, expiresInDays = 1, storageSubLocation = "Chiller Drawer"),
        isQueuedOnGrocery = false,
        onAdjustQuantity = {},
        onToggleLocallyAvailable = {},
        onToggleManualQueue = {},
      )
      DryGoodsGridPairRow(
        pair = listOf(
          PantryLedgerEntry("ing_baguette", IngredientTier.TIER_1_QUANTITATIVE, 4.0, storageSubLocation = "Linen Bread Bag", packageNote = "4 loaves • Marikina Artisan Bakery") to baguette,
          PantryLedgerEntry("ing_oats", IngredientTier.TIER_1_QUANTITATIVE, 2000.0, storageSubLocation = "Airtight Canister #1", packageNote = "2 kg • Bulk Jar") to oats,
        ),
        onSelectStapleStatus = { _, _ -> },
        onAdjustQuantity = { _, _ -> },
      )
    }
  }
}
