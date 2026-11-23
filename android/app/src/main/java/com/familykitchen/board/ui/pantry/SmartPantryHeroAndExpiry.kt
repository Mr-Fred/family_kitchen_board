package com.familykitchen.board.ui.pantry

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familykitchen.board.domain.ExpiringPantryCardModel
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.IngredientCategory
import com.familykitchen.board.domain.PantryExpiryDashboard
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.StorageLocation
import com.familykitchen.board.ui.theme.KitchenTheme
import kotlin.math.roundToLong

// SavorAI Smart Pantry editorial stat bar, 3-column quick command strip, Action Required 48h
// rescue and triage cards (`Mark Used`, `Move to Freezer`), Empty Pantry card, and slim rescue bar.

// Leading descriptive adjectives stripped when formatting concise "+ Spinach" or "+ Salmon" pills.
private val PANTRY_HERO_LEADING_ADJECTIVES: Set<String> = setOf(
  "fresh", "plain", "atlantic", "large", "organic", "sweet", "ripe", "whole",
  "unsalted", "free-range", "wild", "baby", "extra-virgin", "raw", "farm", "local",
)

/**
 * Top Stat Pulse Bar displaying live `totalItems`, a `48.dp` clickable `X Expiring` alert pill,
 * and a `44.dp` non-wrapping `wasteScorePercent` circular ring.
 */
@Composable
fun PantryStatPulseBar(
  dashboard: PantryExpiryDashboard,
  onExpiringPillClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // Compute arc track and fill colors once per recomposition.
  val extendedColors = KitchenTheme.extendedColors
  val expiringCount = dashboard.expiringItems.size
  val ringTrackColor = MaterialTheme.colorScheme.surfaceContainer
  val ringFillColor = extendedColors.herbalSage

  // Warm ceramic card framing stocked count, urgent alert chip, and household rescue score.
  Card(
    modifier = modifier.fillMaxWidth(), shape = KitchenTheme.shapes.card2xl,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
      // Metric 1: Total stocked household items across Fridge, Pantry, and Freezer.
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          modifier = Modifier.size(38.dp).clip(CircleShape).background(extendedColors.sageContainer),
          contentAlignment = Alignment.Center,
        ) {
          Icon(Icons.Filled.Inventory2, contentDescription = null, tint = extendedColors.onSageContainer, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column {
          Text(dashboard.totalItems.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
          Text("Stocked", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
        }
      }

      // Metric 2: 48.dp touch-target Expiring Soon alert pill jumping straight to urgent stock.
      Surface(
        shape = CircleShape, color = extendedColors.tertiaryFixed,
        border = BorderStroke(1.dp, extendedColors.borderSubtle),
        modifier = Modifier.defaultMinSize(minHeight = 48.dp).clip(CircleShape).clickable(onClick = onExpiringPillClick),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
          Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(15.dp))
          Text(
            text = "$expiringCount Expiring", style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold, color = extendedColors.onTertiaryFixed, maxLines = 1, softWrap = false,
          )
        }
      }

      // Metric 3: 44.dp circular Waste Score progress ring preventing text wrap at 1.35x scale.
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
          Canvas(modifier = Modifier.size(44.dp)) {
            if (size.minDimension <= 0f) return@Canvas
            val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
            drawArc(color = ringTrackColor, startAngle = -90f, sweepAngle = 360f, useCenter = false, style = stroke)
            val sweep = (dashboard.wasteScorePercent.coerceIn(0, 100) / 100f) * 360f
            drawArc(color = ringFillColor, startAngle = -90f, sweepAngle = sweep, useCenter = false, style = stroke)
          }
          val scoreFontSize = if (dashboard.wasteScorePercent >= 100) 10.sp else 11.sp
          Text(
            text = "${dashboard.wasteScorePercent}%",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = scoreFontSize),
            color = extendedColors.herbalSage, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
          )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Column {
          Text("Saved", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
          Text("Score", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
        }
      }
    }
  }
}

/**
 * 3-Column Quick Command Strip (`Scan`, `Add Item`, `Shelf Audit`) with crisp `1.dp` warm borders,
 * `>= 64.dp` touch cards, and active highlight state when in-place `Shelf Audit` mode is open.
 */
@Composable
fun PantryQuickCommandStrip(
  onScanReceiptClick: () -> Unit,
  onAddItemClick: () -> Unit,
  onAiAuditClick: () -> Unit,
  isShelfAuditActive: Boolean = false,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Equal-width 3-column command bar keeping camera scan, manual intake, and shelf audit 1 tap away.
  Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    pantryHeroQuickCommandTile(
      label = "Scan", icon = Icons.Filled.DocumentScanner,
      iconBg = extendedColors.sageContainer, iconTint = extendedColors.onSageContainer,
      borderColor = extendedColors.borderSubtle, containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
      onClick = onScanReceiptClick, modifier = Modifier.weight(1f),
    )
    pantryHeroQuickCommandTile(
      label = "Add Item", icon = Icons.Filled.Add,
      iconBg = extendedColors.parchment, iconTint = MaterialTheme.colorScheme.onSurface,
      borderColor = extendedColors.borderSubtle, containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
      onClick = onAddItemClick, modifier = Modifier.weight(1f),
    )
    pantryHeroQuickCommandTile(
      label = "Shelf Audit", icon = Icons.Filled.CheckCircleOutline,
      iconBg = if (isShelfAuditActive) extendedColors.herbalSage else extendedColors.terracottaContainer,
      iconTint = if (isShelfAuditActive) Color.White else extendedColors.onTerracottaContainer,
      borderColor = if (isShelfAuditActive) extendedColors.herbalSage else extendedColors.borderSubtle,
      containerColor = if (isShelfAuditActive) extendedColors.sageContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
      onClick = onAiAuditClick, modifier = Modifier.weight(1f),
    )
  }
}

// Shared >= 64.dp command tile centering a circular 36.dp emblem above a non-wrapping label.
@Composable
private fun pantryHeroQuickCommandTile(
  label: String,
  icon: ImageVector,
  iconBg: Color,
  iconTint: Color,
  borderColor: Color,
  containerColor: Color,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Card(
    onClick = onClick, shape = KitchenTheme.shapes.card2xl,
    colors = CardDefaults.cardColors(containerColor = containerColor),
    border = BorderStroke(1.dp, borderColor), modifier = modifier.defaultMinSize(minHeight = 64.dp),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp),
      horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
      Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(iconBg), contentAlignment = Alignment.Center) {
        Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(19.dp))
      }
      Spacer(modifier = Modifier.height(6.dp))
      Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
  }
}

/**
 * Serene empty-state welcome card displayed on `SmartPantryScreen` when `totalItems == 0`
 * (`StartingPantryMode.EMPTY_KITCHEN`), offering `48.dp` `Scan Kitchen` and `Browse Recipes` CTAs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmptyPantryReadyCard(
  onScanKitchenClick: () -> Unit,
  onBrowseRecipesClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Replace noisy 0g warning lists with a calm invitation to scan shelves or explore recipes.
  Card(
    modifier = modifier.fillMaxWidth(), shape = KitchenTheme.shapes.card2xl,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(extendedColors.sageContainer), contentAlignment = Alignment.Center) {
          Icon(Icons.Filled.Inventory2, contentDescription = null, tint = extendedColors.onSageContainer, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
          Text("Your Pantry Is Ready", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
          Text(
            text = "Scan your fridge or shelves to log what you have on hand, or browse recipes to plan your week.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      // FlowRow wraps both 48.dp CTAs cleanly on 360dp devices at 1.35x accessibility scale.
      FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = onScanKitchenClick, shape = CircleShape,
          colors = ButtonDefaults.buttonColors(containerColor = extendedColors.herbalSage, contentColor = Color.White),
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        ) {
          Icon(Icons.Filled.DocumentScanner, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Scan Kitchen", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        }
        OutlinedButton(
          onClick = onBrowseRecipesClick, shape = CircleShape,
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        ) {
          Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Browse Recipes", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        }
      }
    }
  }
}

/**
 * `Action Required` container showing live `₱` value at risk and editorial rescue cards with 1-tap
 * `Mark Used` and `Move to Freezer` (`90+` day extension) triage actions.
 */
@Composable
fun ActionRequiredExpiryPanel(
  expiringItems: List<ExpiringPantryCardModel>,
  atRiskValueUsd: Double,
  onFindRecipes: (ExpiringPantryCardModel) -> Unit,
  onPairIngredients: (IngredientCatalogItem, IngredientCatalogItem) -> Unit,
  currencySymbol: String = "₱",
  onMarkItemUsed: (String) -> Unit = {},
  onMoveItemToZone: (String, StorageLocation) -> Unit = { _, _ -> },
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Group all within-48h perishables under a single calm container with total value-at-risk badge.
  Card(
    modifier = modifier.fillMaxWidth(), shape = KitchenTheme.shapes.card2xl,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Icon(Icons.Filled.Timer, contentDescription = null, tint = extendedColors.terracotta, modifier = Modifier.size(20.dp))
          Text("Action Required", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Surface(shape = CircleShape, color = extendedColors.secondaryFixed) {
          Text(
            text = "48h · ${formatSmartCurrency(atRiskValueUsd, currencySymbol)}",
            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
            color = extendedColors.onSecondaryFixed, maxLines = 1, softWrap = false,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
          )
        }
      }

      expiringItems.forEach { card ->
        ExpiringItemRichCard(
          card = card, currencySymbol = currencySymbol,
          onFindRecipes = { onFindRecipes(card) },
          onPairClick = { partner -> onPairIngredients(card.catalogItem, partner) },
          onMarkItemUsed = onMarkItemUsed, onMoveItemToZone = onMoveItemToZone,
        )
      }
    }
  }
}

/**
 * Urgent expiring stock card featuring a `3.dp` freshness micro-bar, `Rescue Recipes · N` CTA,
 * co-expiring pairing chip, and `48.dp` 1-tap `Mark Used` and `Move to Freezer` triage pills.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExpiringItemRichCard(
  card: ExpiringPantryCardModel,
  currencySymbol: String = "₱",
  onFindRecipes: () -> Unit = {},
  onPairClick: (IngredientCatalogItem) -> Unit = {},
  onMarkItemUsed: (String) -> Unit = {},
  onMoveItemToZone: (String, StorageLocation) -> Unit = { _, _ -> },
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Escalate badge and micro-bar to terracotta when 1 day or less of freshness remains.
  val isUrgentOneDay = card.expiresInDays <= 1
  val badgeBg = if (isUrgentOneDay) MaterialTheme.colorScheme.errorContainer else extendedColors.tertiaryFixed
  val badgeFg = if (isUrgentOneDay) MaterialTheme.colorScheme.onErrorContainer else extendedColors.onTertiaryFixed
  val barColor = if (isUrgentOneDay) extendedColors.terracotta else extendedColors.expiringSoonIndicator
  val remainingBarFill = (
    card.expiresInDays.coerceAtLeast(0).toFloat() / card.catalogItem.defaultShelfLifeDays.coerceAtLeast(3).toFloat()
    ).coerceIn(0.12f, 0.45f)
  // Show Move to Freezer whenever the item is not already stored inside FREEZER.
  val effectiveLocation = card.entry.storageLocation ?: card.effectiveStorageLocation
  val canMoveToFreezer = card.entry.storageLocation != StorageLocation.FREEZER && effectiveLocation != StorageLocation.FREEZER

  Card(
    modifier = modifier.fillMaxWidth(), shape = KitchenTheme.shapes.card2xl,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      // Top metadata row: category tile, ingredient title, urgency pill, quantity, and 3.dp bar.
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        pantryHeroCategoryTile(category = card.catalogItem.category)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Text(
              text = card.catalogItem.displayName, style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Surface(shape = CircleShape, color = badgeBg) {
              Text(
                text = card.urgencyLabel, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                color = badgeFg, maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
              )
            }
          }
          val subZone = card.entry.storageSubLocation ?: effectiveLocation.displayLabel
          val qtySubtitle =
            "${formatHumanQuantity(card.entry.currentQuantity, card.catalogItem.baseUnit)} · $subZone · ${formatSmartCurrency(card.atRiskValueUsd, currencySymbol)}"
          Text(qtySubtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
          Box(modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer)) {
            Box(modifier = Modifier.fillMaxWidth(remainingBarFill).fillMaxHeight().clip(CircleShape).background(barColor))
          }
        }
      }

      // Row 1: Rescue Recipes · N primary CTA plus optional co-expiring pairing chip.
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val recipeCount = card.matchingRescueRecipes.size
        val countSuffix = if (recipeCount > 0) " · $recipeCount" else ""
        val primaryPillBg = if (isUrgentOneDay) extendedColors.terracottaContainer else extendedColors.sageContainer
        val primaryPillFg = if (isUrgentOneDay) extendedColors.onTerracottaContainer else extendedColors.onSageContainer

        Surface(
          shape = CircleShape, color = primaryPillBg, border = BorderStroke(1.dp, extendedColors.borderSubtle),
          modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).clip(CircleShape).clickable(onClick = onFindRecipes),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = primaryPillFg, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = "Rescue Recipes$countSuffix", style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.Bold, color = primaryPillFg, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            )
          }
        }

        // Optional 48.dp pairing pill prompting Sous-Chef with both co-expiring ingredients.
        card.coExpiringPairIngredient?.let { partner ->
          Surface(
            shape = CircleShape, color = extendedColors.parchment, border = BorderStroke(1.dp, extendedColors.borderSubtle),
            modifier = Modifier.defaultMinSize(minHeight = 48.dp).clip(CircleShape).clickable { onPairClick(partner) },
          ) {
            // Shorten verbose display names ("Atlantic Salmon Fillets" -> "+ Salmon") for 360dp fit.
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
              Text(
                text = "+ ${shortCulinaryNoun(partner.displayName)}", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false,
              )
            }
          }
        }
      }

      // Row 2: 48.dp 1-tap Expiring Triage and Freezer Rescue pills (`Mark Used`, `Move to Freezer`).
      FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 1-tap Mark Used zeros out consumed perishables without opening a stepper dialog.
        Surface(
          shape = CircleShape, color = extendedColors.parchment, border = BorderStroke(1.dp, extendedColors.borderSubtle),
          modifier = Modifier.defaultMinSize(minHeight = 48.dp).clip(CircleShape).clickable { onMarkItemUsed(card.entry.ingredientId) },
        ) {
          Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = extendedColors.herbalSage, modifier = Modifier.size(15.dp))
            Text("Mark Used", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
          }
        }

        if (canMoveToFreezer) {
          // Moving a fresh or expiring item to FREEZER extends remaining life to 90+ days.
          Surface(
            shape = CircleShape, color = extendedColors.sageContainer, border = BorderStroke(1.dp, extendedColors.borderSubtle),
            modifier = Modifier.defaultMinSize(minHeight = 48.dp).clip(CircleShape).clickable { onMoveItemToZone(card.entry.ingredientId, StorageLocation.FREEZER) },
          ) {
            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
              Icon(Icons.Filled.AcUnit, contentDescription = null, tint = extendedColors.onSageContainer, modifier = Modifier.size(15.dp))
              Text("Move to Freezer", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = extendedColors.onSageContainer, maxLines = 1, softWrap = false)
            }
          }
        }
      }
    }
  }
}

/**
 * Slim `52.dp` dismissible warm rescue bar (`"3 expiring items · ₱3,098"` with `48.dp` `"Rescue →"`
 * and `48.dp` `Close` button).
 */
@Composable
fun StickyWastePreventionBanner(
  expiringCount: Int,
  atRiskValueUsd: Double,
  rescueRecipes: List<Recipe>,
  onViewRescueClick: () -> Unit,
  currencySymbol: String = "₱",
  onDismiss: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  // Singular vs plural item phrasing keeps the floating rescue bar concise on 360dp phones.
  val extendedColors = KitchenTheme.extendedColors
  val noun = if (expiringCount == 1) "item" else "items"
  val summaryLabel = "$expiringCount expiring $noun · ${formatSmartCurrency(atRiskValueUsd, currencySymbol)}"

  // Keep rescue reminder anchored cleanly above bottom navigation when scrolled down the pantry.
  Surface(
    modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp), shape = CircleShape,
    color = extendedColors.parchment, border = BorderStroke(1.dp, extendedColors.herbalSage.copy(alpha = 0.4f)),
    shadowElevation = 1.dp,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      // Terracotta urgency dot signals actionable 48h stock without loud alarm banners.
      Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(extendedColors.terracotta))
      Text(
        text = summaryLabel, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
        color = extendedColors.charcoalInk, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
      )
      // Primary 48.dp Rescue pill opens matched zero-waste dinner recipes.
      Surface(
        shape = CircleShape, color = extendedColors.herbalSage,
        modifier = Modifier.defaultMinSize(minHeight = 48.dp).clip(CircleShape).clickable(onClick = onViewRescueClick),
      ) {
        Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
          Text("Rescue →", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, softWrap = false)
        }
      }
      // Manual 48.dp close icon dismisses the floating rescue bar for the active session.
      IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Filled.Close, contentDescription = "Dismiss rescue bar", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
      }
    }
  }
}

// Color-coded 44.dp category icon tile distinguishing Produce, Protein, Dairy, Bakery, and Staples.
@Composable
private fun pantryHeroCategoryTile(category: IngredientCategory) {
  val extendedColors = KitchenTheme.extendedColors
  val (bgColor, fgColor) = when (category) {
    IngredientCategory.PRODUCE -> extendedColors.sageContainer to extendedColors.onSageContainer
    IngredientCategory.PROTEIN -> extendedColors.terracottaContainer to extendedColors.onTerracottaContainer
    IngredientCategory.DAIRY_EGGS -> extendedColors.tertiaryFixed to extendedColors.onTertiaryFixed
    IngredientCategory.BAKERY_GRAINS -> extendedColors.parchment to extendedColors.charcoalInk
    IngredientCategory.PANTRY_STAPLE -> extendedColors.parchment to extendedColors.herbalSage
  }
  Box(
    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(bgColor)
      .border(1.dp, extendedColors.borderSubtle, RoundedCornerShape(12.dp)),
    contentAlignment = Alignment.Center,
  ) {
    Icon(imageVector = Icons.Filled.Restaurant, contentDescription = null, tint = fgColor, modifier = Modifier.size(20.dp))
  }
}

// Strips leading adjectives ("Fresh", "Plain", "Atlantic") so "+ Spinach" or "+ Salmon" reads cleanly.
internal fun shortCulinaryNoun(displayName: String): String {
  val slashChar = 47.toChar()
  val openParenChar = 40.toChar()
  val basePart = displayName.substringBefore(slashChar).substringBefore(openParenChar).trim()
  val tokens = basePart.split(Regex("\\s+")).map { it.trim(',', '.') }.filter { it.isNotEmpty() }
  val stripped = tokens.dropWhile { it.lowercase() in PANTRY_HERO_LEADING_ADJECTIVES }
  return when {
    stripped.isEmpty() -> tokens.lastOrNull() ?: displayName
    stripped.size >= 2 && stripped.last().lowercase() in setOf("fillets", "fillet", "bulbs", "cloves", "leaves") ->
      stripped.first()
    else -> stripped.last()
  }
}

// Formats household currency amounts in pure Kotlin stdlib (`₱1,120` default for PHP).
internal fun formatSmartCurrency(amount: Double, currencySymbol: String = "₱"): String {
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
