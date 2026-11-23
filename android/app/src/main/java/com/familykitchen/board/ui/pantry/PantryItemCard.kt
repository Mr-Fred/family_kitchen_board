package com.familykitchen.board.ui.pantry

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
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
import com.familykitchen.board.domain.ProportionalStockLevel
import com.familykitchen.board.domain.StapleStatus
import com.familykitchen.board.domain.StorageLocation
import com.familykitchen.board.domain.computeRemainingShelfLifeDays
import com.familykitchen.board.ui.theme.ExtendedKitchenColors
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme
import kotlin.math.abs
import kotlin.math.roundToInt

// SavorAI Fridge & Produce interactive cards, 2-column Dry Goods & Freezer grid tiles, Shelf Audit
// proportional pills, and PantryLedgerItemCard enforcing EC-02, EC-03, EC-10, and EC-11.

/**
 * Editorial storage-zone header pairing an icon and title with a singular or plural item count label.
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
    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Icon(icon, contentDescription = null, tint = KitchenTheme.extendedColors.herbalSage, modifier = Modifier.size(20.dp))
      Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Spacer(modifier = Modifier.width(8.dp))
    // Enforce non-wrapping singular or plural count ("1 item" vs "5 items").
    Text(formatItemCount(itemCount), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
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
  onEditIngredientById: (String) -> Unit = {},
  todayIso: String? = null,
  isShelfAuditModeActive: Boolean = false,
  onSetProportionalLevel: (String, ProportionalStockLevel) -> Unit = { _, _ -> },
  modifier: Modifier = Modifier,
) {
  // Skip orphaned ledger records cleanly if an item was removed from catalog.
  val validPairs = entries.mapNotNull { entry -> catalogById[entry.ingredientId]?.let { entry to it } }
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    PantryZoneSectionHeader(Icons.Filled.AcUnit, "Fridge & Produce", validPairs.size)
    validPairs.forEach { (entry, item) ->
      FridgeProduceInteractiveRow(
        catalogItem = item, entry = entry,
        isQueuedOnGrocery = item.ingredientId in queuedGroceryIds,
        onAdjustQuantity = { delta -> onAdjustQuantity(item.ingredientId, delta) },
        onToggleLocallyAvailable = { next -> onToggleLocallyAvailable(item.ingredientId, next) },
        onToggleManualQueue = { onToggleManualQueue(item.ingredientId) },
        onEditIngredient = { onEditIngredientById(item.ingredientId) },
        todayIso = todayIso, isShelfAuditModeActive = isShelfAuditModeActive,
        onSetProportionalLevel = { level -> onSetProportionalLevel(item.ingredientId, level) },
      )
    }
  }
}

/**
 * Individual `Fridge & Produce` card with live [computeRemainingShelfLifeDays] countdown, `3.dp`
 * freshness micro-bar, in-place `Shelf Audit` proportional pills, and `48.dp` **Fridge Pulse** controls.
 *
 * Depleted items (`entry.isOutOfStock == true` or `currentQuantity <= 0.0`) always display
 * `"Out of stock"` and never synthesize a fallback freshness countdown.
 */
@Composable
fun FridgeProduceInteractiveRow(
  catalogItem: IngredientCatalogItem,
  entry: PantryLedgerEntry,
  isQueuedOnGrocery: Boolean,
  onAdjustQuantity: (Double) -> Unit,
  onToggleLocallyAvailable: (Boolean) -> Unit,
  onToggleManualQueue: () -> Unit,
  onEditIngredient: (() -> Unit)? = null,
  todayIso: String? = null,
  isShelfAuditModeActive: Boolean = false,
  onSetProportionalLevel: (ProportionalStockLevel) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  var expanded by rememberSaveable(catalogItem.ingredientId) { mutableStateOf(false) }
  val extendedColors = KitchenTheme.extendedColors
  val isDepleted = pantryCardIsDepleted(entry)
  // Guard against the Empty-Fridge Mirage: NEVER fall back to defaultShelfLifeDays when out of stock.
  val remainingDays = when {
    entry.isOutOfStock || isDepleted -> null
    else -> computeRemainingShelfLifeDays(entry, todayIso) ?: catalogItem.defaultShelfLifeDays
  }
  val countdownLabel = when {
    entry.isOutOfStock || isDepleted -> "Out of stock"
    remainingDays != null -> formatDaysLeft(remainingDays)
    else -> "Fresh"
  }
  // Use understated botanical, toasted-amber, and terracotta tokens by remaining freshness window.
  val daysColor = when {
    entry.isOutOfStock || isDepleted -> MaterialTheme.colorScheme.outline
    remainingDays != null && remainingDays <= 2 -> extendedColors.terracotta
    remainingDays != null && remainingDays <= 4 -> extendedColors.expiringSoonIndicator
    else -> extendedColors.herbalSage
  }
  // Compute true remaining shelf-life fraction so depleted rows render 0% bar fill.
  val baselineDays = catalogItem.defaultShelfLifeDays.coerceAtLeast(3).toFloat()
  val remainingFraction = when {
    entry.isOutOfStock || isDepleted || remainingDays == null -> 0f
    remainingDays <= 0 -> 0.08f
    else -> (remainingDays.toFloat() / baselineDays).coerceIn(0.12f, 1.0f)
  }
  val pulseStep = if (catalogItem.baseUnit == BaseUnit.COUNT) 1.0 else 100.0

  // Tapping any Fridge card toggles inline 48.dp Fridge Pulse steppers and Local Market switch.
  Surface(
    shape = KitchenTheme.shapes.card2xl,
    color = if (expanded) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerLowest,
    border = BorderStroke(1.dp, extendedColors.borderSubtle), shadowElevation = 0.dp,
    modifier = modifier.fillMaxWidth().clip(KitchenTheme.shapes.card2xl).clickable { expanded = !expanded },
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
          Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(extendedColors.sageContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Eco, contentDescription = null, tint = extendedColors.onSageContainer, modifier = Modifier.size(20.dp))
          }
          Spacer(modifier = Modifier.width(12.dp))
          Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(catalogItem.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val subZone = entry.storageSubLocation ?: "Fridge Shelf"
            val qtyText = if (entry.isOutOfStock || isDepleted) "Out of stock" else formatHumanQuantity(entry.currentQuantity, catalogItem.baseUnit)
            val unavailableTag = if (!catalogItem.isLocallyAvailable) " · Unavailable" else ""
            Text(
              text = "$qtyText · $subZone$unavailableTag", style = MaterialTheme.typography.bodySmall,
              color = if (!catalogItem.isLocallyAvailable) extendedColors.terracotta else MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
          }
        }
        Spacer(modifier = Modifier.width(12.dp))
        // Right-aligned singular or plural countdown plus understated 3.dp remaining micro-bar.
        Column(horizontalAlignment = Alignment.End) {
          Text(countdownLabel, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = daysColor, maxLines = 1, softWrap = false)
          if (remainingFraction > 0f) {
            Spacer(modifier = Modifier.height(6.dp))
            Box(modifier = Modifier.width(56.dp).height(3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer)) {
              Box(modifier = Modifier.fillMaxWidth(remainingFraction).fillMaxHeight().clip(CircleShape).background(daysColor))
            }
          }
        }
      }

      // In-Place Shelf Audit Mode: 1-tap Empty · Low · Half · Full pills on Tier-1 cards.
      if (isShelfAuditModeActive && catalogItem.tier == IngredientTier.TIER_1_QUANTITATIVE) {
        PantryShelfAuditPillStrip(entry = entry, defaultPackageQuantity = catalogItem.defaultPackageQuantity, onSelectLevel = onSetProportionalLevel)
      }

      // Expandable 48.dp Fridge Pulse steppers (EC-10), Local Market switch, and Edit pill.
      AnimatedVisibility(visible = expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          HorizontalDivider(color = extendedColors.borderSubtle)
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              FilledTonalIconButton(onClick = { onAdjustQuantity(-pulseStep) }, enabled = entry.currentQuantity > 0.0, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Remove, contentDescription = "Decrease ${catalogItem.displayName}", modifier = Modifier.size(20.dp))
              }
              Text("±${formatHumanQuantity(pulseStep, catalogItem.baseUnit)}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
              FilledTonalIconButton(onClick = { onAdjustQuantity(pulseStep) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "Increase ${catalogItem.displayName}", modifier = Modifier.size(20.dp))
              }
            }
            OutlinedButton(
              onClick = onToggleManualQueue, shape = CircleShape,
              contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
              modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
              Text(if (isQueuedOnGrocery) "Queued ✓" else "Queue in Shop", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
            }
          }
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = if (catalogItem.isLocallyAvailable) "Local Market · Available" else "Unavailable Locally",
              style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Switch(checked = catalogItem.isLocallyAvailable, onCheckedChange = onToggleLocallyAvailable)
          }
          // Punctuation-free 48.dp Edit pill opening EditCatalogIngredientSheet right from Fridge cards.
          if (onEditIngredient != null) {
            OutlinedButton(
              onClick = onEditIngredient, shape = CircleShape,
              contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
              modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
            ) {
              Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("Edit", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
            }
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
  onEditIngredientById: (String) -> Unit = {},
  todayIso: String? = null,
  isShelfAuditModeActive: Boolean = false,
  onSetProportionalLevel: (String, ProportionalStockLevel) -> Unit = { _, _ -> },
  modifier: Modifier = Modifier,
) {
  // Filter orphaned IDs and pair stocked entries into 2-column shelf rows.
  val validPairs = entries.mapNotNull { entry -> catalogById[entry.ingredientId]?.let { entry to it } }
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    PantryZoneSectionHeader(Icons.Filled.Kitchen, sectionTitle, validPairs.size)
    validPairs.chunked(2).forEach { pair ->
      DryGoodsGridPairRow(
        pair = pair, onSelectStapleStatus = onSelectStapleStatus, onAdjustQuantity = onAdjustQuantity,
        onEditIngredientById = onEditIngredientById, todayIso = todayIso,
        isShelfAuditModeActive = isShelfAuditModeActive, onSetProportionalLevel = onSetProportionalLevel,
      )
    }
  }
}

/**
 * Equal-height (`IntrinsicSize.Max`) 2-column row of Dry Goods or Freezer tiles emitted lazily inside
 * `SmartPantryScreen`.
 */
@Composable
fun DryGoodsGridPairRow(
  pair: List<Pair<PantryLedgerEntry, IngredientCatalogItem>>,
  onSelectStapleStatus: (ingredientId: String, newStatus: StapleStatus) -> Unit,
  onAdjustQuantity: (ingredientId: String, deltaQuantity: Double) -> Unit,
  onEditIngredientById: (String) -> Unit = {},
  todayIso: String? = null,
  isShelfAuditModeActive: Boolean = false,
  onSetProportionalLevel: (String, ProportionalStockLevel) -> Unit = { _, _ -> },
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // IntrinsicSize.Max aligns paired tiles evenly when one card expands or has a 2-line title.
  Row(modifier = modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    pair.forEach { (entry, item) ->
      DryGoodsGridTile(
        item = item, entry = entry, extendedColors = extendedColors,
        onSelectStapleStatus = { nextStatus -> onSelectStapleStatus(item.ingredientId, nextStatus) },
        onAdjustTier1Quantity = { delta -> onAdjustQuantity(item.ingredientId, delta) },
        onEditIngredient = { onEditIngredientById(item.ingredientId) },
        todayIso = todayIso, isShelfAuditModeActive = isShelfAuditModeActive,
        onSetProportionalLevel = { level -> onSetProportionalLevel(item.ingredientId, level) },
        modifier = Modifier.weight(1f).fillMaxHeight(),
      )
    }
    if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
  }
}

// Compact Dry Goods or Freezer tile supporting Tier-2 status cycling and Tier-1 Shelf Audit pills.
@Composable
private fun DryGoodsGridTile(
  item: IngredientCatalogItem,
  entry: PantryLedgerEntry,
  extendedColors: ExtendedKitchenColors,
  onSelectStapleStatus: (StapleStatus) -> Unit,
  onAdjustTier1Quantity: (Double) -> Unit,
  onEditIngredient: () -> Unit,
  todayIso: String?,
  isShelfAuditModeActive: Boolean,
  onSetProportionalLevel: (ProportionalStockLevel) -> Unit,
  modifier: Modifier = Modifier,
) {
  var expanded by rememberSaveable(item.ingredientId) { mutableStateOf(false) }
  val isStaple = item.tier == IngredientTier.TIER_2_STAPLE
  val isLowStaple = isStaple && entry.stapleStatus == StapleStatus.LOW
  val isOut = pantryCardIsDepleted(entry)
  val pkgCap = item.defaultPackageQuantity.coerceAtLeast(1.0)
  val isLowTier1 = !isStaple && !isOut && entry.currentQuantity < (pkgCap * 0.5)
  val pulseStep = if (item.baseUnit == BaseUnit.COUNT) 1.0 else 100.0
  // Never compute fallback shelf-life countdowns on depleted items.
  val liveRemainingDays = if (isOut || isStaple) null else computeRemainingShelfLifeDays(entry, todayIso)

  // Map stock health into botanical sage, toasted amber, or terracotta indicator tokens.
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
    liveRemainingDays != null && liveRemainingDays <= 14 -> "${liveRemainingDays}d"
    else -> "Stocked"
  }
  val stockBarFill = when {
    isOut -> 0f
    isLowStaple || isLowTier1 -> 0.40f
    isStaple -> 1.0f
    else -> (entry.currentQuantity / pkgCap).toFloat().coerceIn(0.45f, 1.0f)
  }

  Card(
    onClick = { expanded = !expanded }, modifier = modifier, shape = KitchenTheme.shapes.card2xl,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
  ) {
    Column(modifier = Modifier.fillMaxWidth().fillMaxHeight().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(statusColor))
            Surface(shape = CircleShape, color = tagBg) {
              Text(tagText, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = tagFg, maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
            }
          }
          // 48.dp touch target around Edit icon for accessible single-hand tapping.
          IconButton(onClick = onEditIngredient, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Edit, contentDescription = "Edit ${item.displayName}", tint = extendedColors.herbalSage, modifier = Modifier.size(16.dp))
          }
        }

        Text(item.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)

        // Prefer human package notes ("4 loaves") when stocked; show "Out of stock" when depleted.
        val cleanPkgNote = entry.packageNote?.substringBefore("•")?.trim()?.takeIf { it.isNotEmpty() }
        val quantityLine = if (isOut) "Out of stock" else cleanPkgNote ?: formatHumanQuantity(entry.currentQuantity, item.baseUnit)
        Text(quantityLine, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())

        // Resolve human shelf label from explicit storageLocation before catalog default.
        val defaultZoneTitle = when (entry.storageLocation ?: item.defaultStorageLocation) {
          StorageLocation.FREEZER -> "Freezer"
          StorageLocation.FRIDGE -> "Fridge Shelf"
          StorageLocation.PANTRY -> "Pantry Shelf"
        }
        Text(entry.storageSubLocation ?: defaultZoneTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
      }

      Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Render 3.dp stock fill micro-bar strictly when stockBarFill > 0f.
        if (stockBarFill > 0f) {
          Box(modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer)) {
            Box(modifier = Modifier.fillMaxWidth(stockBarFill).fillMaxHeight().clip(CircleShape).background(statusColor))
          }
        }

        // In-Place Shelf Audit pills wrap cleanly across 2x2 inside narrow 360dp tiles.
        if (isShelfAuditModeActive && !isStaple) {
          PantryShelfAuditPillStrip(entry = entry, defaultPackageQuantity = item.defaultPackageQuantity, onSelectLevel = onSetProportionalLevel)
        }

        // Expandable Tier-2 status cycler or Tier-1 48.dp minus and plus buttons.
        AnimatedVisibility(visible = expanded) {
          when {
            isStaple -> {
              val nextStatus = when (entry.stapleStatus) {
                StapleStatus.PLENTY -> StapleStatus.LOW
                StapleStatus.LOW -> StapleStatus.OUT
                StapleStatus.OUT -> StapleStatus.PLENTY
              }
              OutlinedButton(
                onClick = { onSelectStapleStatus(nextStatus) }, shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
              ) {
                Text("Set ${pantryCardFormatStapleLabel(nextStatus)}", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
              }
            }
            else -> {
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { onAdjustTier1Quantity(-pulseStep) }, enabled = entry.currentQuantity > 0.0, modifier = Modifier.size(48.dp)) {
                  Icon(Icons.Filled.Remove, contentDescription = "Decrease ${item.displayName}", modifier = Modifier.size(18.dp))
                }
                FilledTonalIconButton(onClick = { onAdjustTier1Quantity(pulseStep) }, modifier = Modifier.size(48.dp)) {
                  Icon(Icons.Filled.Add, contentDescription = "Increase ${item.displayName}", modifier = Modifier.size(18.dp))
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
  onEditIngredient: (() -> Unit)? = null,
  todayIso: String? = null,
  isShelfAuditModeActive: Boolean = false,
  onSetProportionalLevel: (ProportionalStockLevel) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  val currentQty = pantryEntry?.currentQuantity ?: 0.0
  val activeStapleStatus = pantryEntry?.stapleStatus ?: StapleStatus.PLENTY
  val isDepleted = pantryEntry == null || pantryCardIsDepleted(pantryEntry)
  // Omit countdown suffix whenever the item is depleted so 0g rows never show stale days left.
  val liveDays = pantryEntry?.takeUnless { isDepleted }?.let { computeRemainingShelfLifeDays(it, todayIso) }
  val pulseStep = if (catalogItem.baseUnit == BaseUnit.COUNT) 1.0 else 100.0

  // Terracotta border warns when `isLocallyAvailable == false` (triggering EC-02 hide or EC-03 swap).
  Card(
    modifier = modifier.fillMaxWidth(), shape = KitchenTheme.shapes.card2xl,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    border = BorderStroke(1.dp, if (!catalogItem.isLocallyAvailable) extendedColors.terracotta else extendedColors.borderSubtle),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
          Text(catalogItem.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
          val countdownSuffix = if (liveDays != null) " · ${formatDaysLeft(liveDays)}" else ""
          Text("${catalogItem.category.displayLabel}$countdownSuffix", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onEditIngredient != null) {
          IconButton(onClick = onEditIngredient, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Edit, contentDescription = "Edit ${catalogItem.displayName}", tint = extendedColors.herbalSage, modifier = Modifier.size(19.dp))
          }
        }
        OutlinedButton(
          onClick = onToggleManualQueue, shape = CircleShape,
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        ) {
          Icon(if (isQueuedOnGrocery) Icons.Filled.Check else Icons.Filled.ShoppingCart, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text(if (isQueuedOnGrocery) "Queued" else "Queue in Shop", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
        }
      }

      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = if (catalogItem.isLocallyAvailable) "Available Locally" else "Unavailable Locally",
          style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Switch(checked = catalogItem.isLocallyAvailable, onCheckedChange = onToggleLocallyAvailable)
      }

      if (isShelfAuditModeActive && catalogItem.tier == IngredientTier.TIER_1_QUANTITATIVE && pantryEntry != null) {
        PantryShelfAuditPillStrip(entry = pantryEntry, defaultPackageQuantity = catalogItem.defaultPackageQuantity, onSelectLevel = onSetProportionalLevel)
      }

      when (catalogItem.tier) {
        IngredientTier.TIER_2_STAPLE -> {
          SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            StapleStatus.entries.forEachIndexed { idx, status ->
              SegmentedButton(
                selected = activeStapleStatus == status, onClick = { onSelectStapleStatus(status) },
                shape = SegmentedButtonDefaults.itemShape(index = idx, count = StapleStatus.entries.size),
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                label = { Text(pantryCardFormatStapleLabel(status), style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false) },
              )
            }
          }
        }
        IngredientTier.TIER_1_QUANTITATIVE -> {
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            val stockCopy = if (isDepleted) "Out of stock" else "In Stock · ${formatHumanQuantity(currentQty, catalogItem.baseUnit)}"
            Text(stockCopy, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
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

// 1-tap Empty · Low · Half · Full proportional stock presets eliminating repetitive stepper taps.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PantryShelfAuditPillStrip(
  entry: PantryLedgerEntry,
  defaultPackageQuantity: Double,
  onSelectLevel: (ProportionalStockLevel) -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  val activeLevel = pantryCardMatchProportionalLevel(entry, defaultPackageQuantity)
  FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    ProportionalStockLevel.entries.forEach { level ->
      val isSelected = activeLevel == level
      Surface(
        shape = CircleShape,
        color = if (isSelected) extendedColors.herbalSage else extendedColors.parchment,
        border = if (isSelected) null else BorderStroke(1.dp, extendedColors.borderSubtle),
        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).clip(CircleShape).clickable { onSelectLevel(level) },
      ) {
        Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
          Text(
            text = level.chipLabel, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false,
          )
        }
      }
    }
  }
}

// Check whether a ledger record is out of stock (0g Tier-1 or OUT Tier-2) so no phantom days show.
private fun pantryCardIsDepleted(entry: PantryLedgerEntry): Boolean =
  entry.isOutOfStock || when (entry.tier) {
    IngredientTier.TIER_1_QUANTITATIVE -> entry.currentQuantity <= 0.0
    IngredientTier.TIER_2_STAPLE -> entry.stapleStatus == StapleStatus.OUT
  }

// Highlight whichever proportional stock bucket best represents the entry's current fill level.
private fun pantryCardMatchProportionalLevel(
  entry: PantryLedgerEntry,
  defaultPackageQuantity: Double,
): ProportionalStockLevel {
  if (pantryCardIsDepleted(entry)) return ProportionalStockLevel.EMPTY
  val safePkg = defaultPackageQuantity.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
  val ratio = entry.currentQuantity / safePkg
  return when {
    ratio <= 0.37 -> ProportionalStockLevel.LOW
    ratio <= 0.75 -> ProportionalStockLevel.HALF
    else -> ProportionalStockLevel.FULL
  }
}

// Convert StapleStatus enum constants into clean Title Case button labels.
private fun pantryCardFormatStapleLabel(status: StapleStatus): String =
  when (status) {
    StapleStatus.PLENTY -> "Plenty"
    StapleStatus.LOW -> "Low"
    StapleStatus.OUT -> "Out"
  }

// Formats remaining freshness days with proper singular and plural grammar ("1 day left" vs "3 days left").
internal fun formatDaysLeft(days: Int): String = when {
  days <= 0 -> "Expired"
  days == 1 -> "1 day left"
  else -> "$days days left"
}

// Formats section item counts with proper singular and plural grammar ("1 item" vs "5 items").
internal fun formatItemCount(count: Int): String = when (count) {
  1 -> "1 item"
  else -> "$count items"
}

// Converts >= 1000 g or ml into clean human units ("1.6 kg", "2 kg", "1 L") for compact card labels.
internal fun formatHumanQuantity(quantity: Double, unit: BaseUnit): String = when {
  unit == BaseUnit.GRAM && quantity >= 1000.0 -> "${pantryCardFormatDecimal(quantity / 1000.0)} kg"
  unit == BaseUnit.MILLILITER && quantity >= 1000.0 -> "${pantryCardFormatDecimal(quantity / 1000.0)} L"
  else -> formatPantryQuantityWithUnit(quantity, unit)
}

// Rounds to whole numbers or 1 decimal place without trailing ".0" noise.
private fun pantryCardFormatDecimal(value: Double): String {
  val rounded = value.roundToInt()
  return when {
    abs(value - rounded.toDouble()) < 0.05 -> rounded.toString()
    else -> ((value * 10.0).roundToInt() / 10.0).toString()
  }
}

// Formats a canonical quantity without trailing ".0" noise on whole numbers.
internal fun formatPantryQuantityWithUnit(quantity: Double, unit: BaseUnit): String =
  "${pantryCardFormatDecimal(quantity)} ${unit.wireCode}"

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun PantryItemCardCompactScalePreview() {
  // Verify compact 360dp Shelf Audit pills, "Out of stock" guard, and 2-column Dry Goods alignment.
  val baguette = IngredientCatalogItem("ing_baguette", "Crusty Artisan Baguette", IngredientCategory.BAKERY_GRAINS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.COUNT, 2.0, defaultStorageLocation = StorageLocation.PANTRY)
  val oats = IngredientCatalogItem("ing_oats", "Rolled Whole Grain Oats", IngredientCategory.BAKERY_GRAINS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 1000.0, defaultStorageLocation = StorageLocation.PANTRY)
  val salmon = IngredientCatalogItem("ing_salmon", "Atlantic Salmon Fillets", IngredientCategory.PROTEIN, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 800.0, defaultStorageLocation = StorageLocation.FRIDGE, defaultShelfLifeDays = 3)
  FamilyKitchenBoardTheme {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      FridgeProduceInteractiveRow(
        catalogItem = salmon,
        entry = PantryLedgerEntry("ing_salmon", IngredientTier.TIER_1_QUANTITATIVE, 400.0, expiresInDays = 1, storageSubLocation = "Chiller Drawer"),
        isQueuedOnGrocery = false, onAdjustQuantity = {}, onToggleLocallyAvailable = {},
        onToggleManualQueue = {}, isShelfAuditModeActive = true,
      )
      DryGoodsGridPairRow(
        pair = listOf(
          PantryLedgerEntry("ing_baguette", IngredientTier.TIER_1_QUANTITATIVE, 4.0, storageSubLocation = "Linen Bread Bag", packageNote = "4 loaves • Marikina Artisan Bakery") to baguette,
          PantryLedgerEntry("ing_oats", IngredientTier.TIER_1_QUANTITATIVE, 2000.0, storageSubLocation = "Airtight Canister #1", packageNote = "2 kg • Bulk Jar") to oats,
        ),
        onSelectStapleStatus = { _, _ -> }, onAdjustQuantity = { _, _ -> },
      )
    }
  }
}
