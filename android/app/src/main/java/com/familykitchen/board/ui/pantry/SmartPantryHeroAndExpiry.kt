package com.familykitchen.board.ui.pantry

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familykitchen.board.domain.ExpiringPantryCardModel
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.IngredientCategory
import com.familykitchen.board.domain.PantryExpiryDashboard
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.ui.theme.KitchenTheme
import kotlin.math.roundToLong

// SavorAI Smart Pantry editorial stat bar, 3-column quick command strip, Action Required (<= 48h)
// rescue cards, and slim 52.dp dismissible rescue bar (`smart_pantry_expiry` spec, zero sparkles).

// Leading descriptive adjectives stripped when formatting concise "+ Spinach" / "+ Salmon" pills.
private val LEADING_CULINARY_ADJECTIVES: Set<String> = setOf(
  "fresh",
  "plain",
  "atlantic",
  "large",
  "organic",
  "sweet",
  "ripe",
  "whole",
  "unsalted",
  "free-range",
  "wild",
  "baby",
  "extra-virgin",
  "raw",
  "farm",
  "local",
)

/**
 * Top Stat Pulse Bar displaying live `totalItems`, a `48.dp` clickable `X Expiring` alert pill,
 * and a `44.dp` non-wrapping `wasteScorePercent` circular ring.
 *
 * @param dashboard Evaluated [PantryExpiryDashboard] metrics.
 * @param onExpiringPillClick Switches the active filter directly to `PantryCategoryFilter.EXPIRING`.
 * @param modifier Optional Compose [Modifier].
 */
@Composable
fun PantryStatPulseBar(
  dashboard: PantryExpiryDashboard,
  onExpiringPillClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  val expiringCount = dashboard.expiringItems.size
  val ringTrackColor = MaterialTheme.colorScheme.surfaceContainer
  val ringFillColor = extendedColors.herbalSage

  Card(
    modifier = modifier.fillMaxWidth(),
    shape = KitchenTheme.shapes.card2xl,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // Metric 1: Total stocked household items across Fridge, Pantry, and Freezer.
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(extendedColors.sageContainer),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Filled.Inventory2,
            contentDescription = null,
            tint = extendedColors.onSageContainer,
            modifier = Modifier.size(18.dp),
          )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column {
          Text(
            text = dashboard.totalItems.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
          )
          Text(
            text = "Stocked",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
          )
        }
      }

      // Metric 2: 48.dp touch-target Expiring Soon alert pill jumping straight to urgent stock.
      Surface(
        shape = CircleShape,
        color = extendedColors.tertiaryFixed,
        border = BorderStroke(1.dp, extendedColors.borderSubtle),
        modifier = Modifier
          .defaultMinSize(minHeight = 48.dp)
          .clip(CircleShape)
          .clickable(onClick = onExpiringPillClick),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
          Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(15.dp),
          )
          Text(
            text = "$expiringCount Expiring",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = extendedColors.onTertiaryFixed,
            maxLines = 1,
            softWrap = false,
          )
        }
      }

      // Metric 3: 44.dp circular Waste Score progress ring preventing "96\n%" wrap at 1.35x scale.
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
          Canvas(modifier = Modifier.size(44.dp)) {
            // Guard Canvas drawing when layout bounds collapse to zero during transition frames.
            if (size.minDimension <= 0f) return@Canvas
            val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
            // Draw muted full-circle track first, then sweep clockwise from 12 o'clock (-90 deg).
            drawArc(color = ringTrackColor, startAngle = -90f, sweepAngle = 360f, useCenter = false, style = stroke)
            val sweep = (dashboard.wasteScorePercent.coerceIn(0, 100) / 100f) * 360f
            drawArc(color = ringFillColor, startAngle = -90f, sweepAngle = sweep, useCenter = false, style = stroke)
          }
          // Step down 1.sp at 3 digits ("100%") so the ring label never clips inside 44.dp.
          val scoreFontSize = if (dashboard.wasteScorePercent >= 100) 10.sp else 11.sp
          Text(
            text = "${dashboard.wasteScorePercent}%",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = scoreFontSize),
            color = extendedColors.herbalSage,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
          )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Column {
          Text(
            text = "Saved",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
          )
          Text(
            text = "Score",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
          )
        }
      }
    }
  }
}

/**
 * 3-Column Quick Command Strip (`Scan`, `Add Item`, `Shelf Audit`) with crisp `1.dp` warm borders,
 * `>= 56.dp` touch cards, and zero sparkle icons.
 *
 * @param onScanReceiptClick Triggers multimodal Sous-Chef fridge/spice/receipt camera scanning.
 * @param onAddItemClick Toggles the local-preset + custom `AddIngredientCard`.
 * @param onAiAuditClick Launches Sous-Chef zero-waste pantry expiry audit.
 * @param modifier Optional Compose [Modifier].
 */
@Composable
fun PantryQuickCommandStrip(
  onScanReceiptClick: () -> Unit,
  onAddItemClick: () -> Unit,
  onAiAuditClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    // Action 1: Visual Camera / Photo Scan.
    QuickCommandTile(
      label = "Scan",
      icon = Icons.Filled.DocumentScanner,
      iconBg = extendedColors.sageContainer,
      iconTint = extendedColors.onSageContainer,
      borderColor = extendedColors.borderSubtle,
      onClick = onScanReceiptClick,
      modifier = Modifier.weight(1f),
    )

    // Action 2: Manual / Local-Market Preset Add Item.
    QuickCommandTile(
      label = "Add Item",
      icon = Icons.Filled.Add,
      iconBg = extendedColors.parchment,
      iconTint = MaterialTheme.colorScheme.onSurface,
      borderColor = extendedColors.borderSubtle,
      onClick = onAddItemClick,
      modifier = Modifier.weight(1f),
    )

    // Action 3: Shelf Audit using a calm botanical CheckCircleOutline badge.
    QuickCommandTile(
      label = "Shelf Audit",
      icon = Icons.Filled.CheckCircleOutline,
      iconBg = extendedColors.terracottaContainer,
      iconTint = extendedColors.onTerracottaContainer,
      borderColor = extendedColors.borderSubtle,
      onClick = onAiAuditClick,
      modifier = Modifier.weight(1f),
    )
  }
}

@Composable
private fun QuickCommandTile(
  label: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  iconBg: Color,
  iconTint: Color,
  borderColor: Color,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Card(
    onClick = onClick,
    shape = KitchenTheme.shapes.card2xl,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    border = BorderStroke(1.dp, borderColor),
    modifier = modifier.defaultMinSize(minHeight = 64.dp),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Box(
        modifier = Modifier.size(36.dp).clip(CircleShape).background(iconBg),
        contentAlignment = Alignment.Center,
      ) {
        Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(19.dp))
      }
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/**
 * `Action Required (Within 48h)` container showing live `₱` value at risk and calm editorial rescue
 * cards (`<= 3` metadata badges, `3.dp` remaining shelf-life micro-bar, and `48.dp` rescue pills).
 *
 * @param expiringItems Urgent stock models computed by `PantryExpiryEngine`.
 * @param atRiskValueUsd Total monetary value at risk across [expiringItems] (default `PHP` `₱`).
 * @param onFindRecipes Opens rescue recipes consuming the tapped expiring item.
 * @param onPairIngredients Prompts Sous-Chef with a co-expiring culinary pairing.
 * @param currencySymbol Active household currency symbol (`"₱"` default for Antipolo & Marikina).
 * @param modifier Optional Compose [Modifier].
 */
@Composable
fun ActionRequiredExpiryPanel(
  expiringItems: List<ExpiringPantryCardModel>,
  atRiskValueUsd: Double,
  onFindRecipes: (ExpiringPantryCardModel) -> Unit,
  onPairIngredients: (IngredientCatalogItem, IngredientCatalogItem) -> Unit,
  currencySymbol: String = "₱",
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  Card(
    modifier = modifier.fillMaxWidth(),
    shape = KitchenTheme.shapes.card2xl,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      // Weight left heading row so "Action Required" never squeezes the 48h capsule on 360dp.
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
          Icon(
            imageVector = Icons.Filled.Timer,
            contentDescription = null,
            tint = extendedColors.terracotta,
            modifier = Modifier.size(20.dp),
          )
          Text(
            text = "Action Required",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Spacer(modifier = Modifier.width(8.dp))
        // Non-wrapping 48h at-risk household value badge formatted in PHP ₱ by default.
        Surface(shape = CircleShape, color = extendedColors.secondaryFixed) {
          Text(
            text = "48h · ${formatSmartCurrency(atRiskValueUsd, currencySymbol)}",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = extendedColors.onSecondaryFixed,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
          )
        }
      }

      // Render one calm rescue card per urgent perishable (<= 2 days remaining shelf life).
      expiringItems.forEach { card ->
        ExpiringItemRichCard(
          card = card,
          currencySymbol = currencySymbol,
          onFindRecipes = { onFindRecipes(card) },
          onPairClick = { partner -> onPairIngredients(card.catalogItem, partner) },
        )
      }
    }
  }
}

@Composable
private fun ExpiringItemRichCard(
  card: ExpiringPantryCardModel,
  currencySymbol: String,
  onFindRecipes: () -> Unit,
  onPairClick: (IngredientCatalogItem) -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Escalate <= 1 day items to terracotta urgency while 2-day items use warm amber tokens.
  val isUrgentOneDay = card.expiresInDays <= 1
  val badgeBg = if (isUrgentOneDay) MaterialTheme.colorScheme.errorContainer else extendedColors.tertiaryFixed
  val badgeFg = if (isUrgentOneDay) MaterialTheme.colorScheme.onErrorContainer else extendedColors.onTertiaryFixed
  val barColor = if (isUrgentOneDay) extendedColors.terracotta else extendedColors.expiringSoonIndicator
  // Compute true remaining shelf-life fraction while leaving domain `freshnessProgress` untouched.
  val remainingBarFill = (
    card.expiresInDays.coerceAtLeast(0).toFloat() /
      card.catalogItem.defaultShelfLifeDays.coerceAtLeast(3).toFloat()
    ).coerceIn(0.12f, 0.45f)

  // Flat 1.dp warm-bordered surface card with zero elevation shadow and <= 3 metadata lines.
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = KitchenTheme.shapes.card2xl,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
      ) {
        CulinaryCategoryTile(category = card.catalogItem.category)

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
          // Metadata #1: 2-line dish/ingredient title + single non-wrapping urgency pill.
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
          ) {
            Text(
              text = card.catalogItem.displayName,
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Bold,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Surface(shape = CircleShape, color = badgeBg) {
              Text(
                text = card.urgencyLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = badgeFg,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
              )
            }
          }

          // Metadata #2: Humanized quantity + sub-zone + at-risk value (omits "Purchased 1 days ago").
          val subZone = card.entry.storageSubLocation ?: card.effectiveStorageLocation.displayLabel
          val qtySubtitle =
            "${formatHumanQuantity(card.entry.currentQuantity, card.catalogItem.baseUnit)} · $subZone · ${formatSmartCurrency(card.atRiskValueUsd, currencySymbol)}"
          Text(
            text = qtySubtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )

          // Metadata #3: Understated 3.dp remaining shelf-life micro-bar.
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(3.dp)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.surfaceContainer),
          ) {
            Box(
              modifier = Modifier
                .fillMaxWidth(remainingBarFill)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(barColor),
            )
          }
        }
      }

      // Dedicated full-width 48.dp 2-button action row preventing "Find\nRecip\nes" & "Pair with Fresh".
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // Append matched rescue recipe count when > 0 so users see actionable zero-waste options.
        val recipeCount = card.matchingRescueRecipes.size
        val countSuffix = if (recipeCount > 0) " ($recipeCount)" else ""
        // Tint <= 1d rescue CTA in warm terracotta container and 2d CTA in sage container.
        val primaryPillBg = if (isUrgentOneDay) extendedColors.terracottaContainer else extendedColors.sageContainer
        val primaryPillFg = if (isUrgentOneDay) extendedColors.onTerracottaContainer else extendedColors.onSageContainer

        // Weight(1f) + softWrap=false prevents multi-line text slicing at 1.35x font scale.
        Surface(
          shape = CircleShape,
          color = primaryPillBg,
          border = BorderStroke(1.dp, extendedColors.borderSubtle),
          modifier = Modifier
            .weight(1f)
            .defaultMinSize(minHeight = 48.dp)
            .clip(CircleShape)
            .clickable(onClick = onFindRecipes),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.MenuBook,
              contentDescription = null,
              tint = primaryPillFg,
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = "Rescue Recipes$countSuffix",
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.Bold,
              color = primaryPillFg,
              maxLines = 1,
              softWrap = false,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }

        // Optional co-expiring pairing chip stripping verbose adjectives ("Fresh Baby Spinach" -> "+ Spinach").
        card.coExpiringPairIngredient?.let { partner ->
          Surface(
            shape = CircleShape,
            color = extendedColors.parchment,
            border = BorderStroke(1.dp, extendedColors.borderSubtle),
            modifier = Modifier
              .defaultMinSize(minHeight = 48.dp)
              .clip(CircleShape)
              .clickable { onPairClick(partner) },
          ) {
            Box(
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = "+ ${shortCulinaryNoun(partner.displayName)}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
              )
            }
          }
        }
      }
    }
  }
}

/**
 * Slim `52.dp` dismissible warm rescue bar (`"3 expiring items · ₱3,098"` + `48.dp` `"Rescue →"`
 * + `48.dp` `Close` button) replacing the oversized `#096443` 4-line slab.
 *
 * @param expiringCount Number of urgent expiring items ready for tonight's rescue dinner.
 * @param atRiskValueUsd Monetary value rescued by cooking these items before spoilage.
 * @param rescueRecipes Ranked rescue recipes matched by `PantryExpiryEngine`.
 * @param onViewRescueClick Opens Sous-Chef / Recipe Reader with the top zero-waste rescue meals.
 * @param currencySymbol Household currency symbol (`"₱"` default).
 * @param onDismiss Dismisses the floating rescue bar for the current Pantry session.
 * @param modifier Optional Compose [Modifier].
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
  val extendedColors = KitchenTheme.extendedColors
  // Use singular/plural noun and single-line warm parchment capsule so list content stays visible.
  val noun = if (expiringCount == 1) "item" else "items"
  val summaryLabel = when {
    rescueRecipes.isEmpty() -> "$expiringCount expiring $noun · ${formatSmartCurrency(atRiskValueUsd, currencySymbol)}"
    else -> "$expiringCount expiring $noun · ${formatSmartCurrency(atRiskValueUsd, currencySymbol)}"
  }

  // Limit shadowElevation to 1.dp max per editorial surface rules.
  Surface(
    modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp),
    shape = CircleShape,
    color = extendedColors.parchment,
    border = BorderStroke(1.dp, extendedColors.herbalSage.copy(alpha = 0.4f)),
    shadowElevation = 1.dp,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      // Terracotta pulse dot anchors the single-line summary without shouting in solid neon fill.
      Box(
        modifier = Modifier.size(8.dp).clip(CircleShape).background(extendedColors.terracotta),
      )
      Text(
        text = summaryLabel,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = extendedColors.charcoalInk,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      // 48.dp herbal-sage primary pill launching zero-waste rescue recipes in 1 tap.
      Surface(
        shape = CircleShape,
        color = extendedColors.herbalSage,
        modifier = Modifier
          .defaultMinSize(minHeight = 48.dp)
          .clip(CircleShape)
          .clickable(onClick = onViewRescueClick),
      ) {
        Box(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = "Rescue →",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            softWrap = false,
          )
        }
      }
      // 48.dp dismiss button so users can clear the docked pill while browsing lower shelves.
      IconButton(
        onClick = onDismiss,
        modifier = Modifier.size(48.dp),
      ) {
        Icon(
          imageVector = Icons.Filled.Close,
          contentDescription = "Dismiss rescue bar",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(18.dp),
        )
      }
    }
  }
}

// Calm 44.dp tonal category tile with 1.dp subtle border (zero harsh gradients or cold hexes).
@Composable
private fun CulinaryCategoryTile(category: IngredientCategory) {
  val extendedColors = KitchenTheme.extendedColors
  // Map each culinary category to warm linen/parchment/sage/terracotta container tokens.
  val (bgColor, fgColor) = when (category) {
    IngredientCategory.PRODUCE -> extendedColors.sageContainer to extendedColors.onSageContainer
    IngredientCategory.PROTEIN -> extendedColors.terracottaContainer to extendedColors.onTerracottaContainer
    IngredientCategory.DAIRY_EGGS -> extendedColors.tertiaryFixed to extendedColors.onTertiaryFixed
    IngredientCategory.BAKERY_GRAINS -> extendedColors.parchment to extendedColors.charcoalInk
    IngredientCategory.PANTRY_STAPLE -> extendedColors.parchment to extendedColors.herbalSage
  }
  Box(
    modifier = Modifier
      .size(44.dp)
      .clip(RoundedCornerShape(12.dp))
      .background(bgColor)
      .border(1.dp, extendedColors.borderSubtle, RoundedCornerShape(12.dp)),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = Icons.Filled.Restaurant,
      contentDescription = null,
      tint = fgColor,
      modifier = Modifier.size(20.dp),
    )
  }
}

// Strips leading adjectives ("Fresh", "Plain", "Atlantic") so "+ Spinach" / "+ Salmon" reads cleanly.
internal fun shortCulinaryNoun(displayName: String): String {
  // Trim slash variants or parenthetical notes before tokenizing display names.
  val basePart = displayName.substringBefore("/").substringBefore("(").trim()
  val tokens = basePart
    .split(Regex("\\s+"))
    .map { it.trim(',', '.') }
    .filter { it.isNotEmpty() }
  val stripped = tokens.dropWhile { it.lowercase() in LEADING_CULINARY_ADJECTIVES }
  // Prefer the head noun when the trailing word is a cut/unit plural ("Salmon Fillets" -> "Salmon").
  val coreWord = when {
    stripped.isEmpty() -> tokens.lastOrNull() ?: displayName
    stripped.size >= 2 && stripped.last().lowercase() in setOf("fillets", "fillet", "bulbs", "cloves", "leaves") ->
      stripped.first()
    else -> stripped.last()
  }
  return coreWord
}

// Formats household currency amounts in pure Kotlin stdlib (`₱1,120` default for PHP, or 2-decimal `$`/`€`).
internal fun formatSmartCurrency(amount: Double, currencySymbol: String = "₱"): String {
  // Whole-peso and CFA formatting omits ".00" clutter while USD/EUR retain 2 decimal places.
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
