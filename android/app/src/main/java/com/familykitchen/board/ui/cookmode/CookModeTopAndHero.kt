package com.familykitchen.board.ui.cookmode

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.MorningStep
import com.familykitchen.board.ui.board.ChildSafetyCalloutBox
import com.familykitchen.board.ui.theme.InterfaceSansFontFamily
import com.familykitchen.board.ui.theme.KitchenTheme
import kotlin.math.roundToInt

// Punctuation-free 48.dp Top Header (`End Cook`, `Minimize`, `Step k of N`, `Awake`), compact
// 80.dp Technique and Doneness Strip, and distraction-free 19.sp Step Instruction Card.

// Top bar hosting explicit `End Cook`, optional `Minimize` to Mini-Timer, step counter, and wake lock.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CookModeTopHeader(
  steps: List<MorningStep>,
  currentStepIndex: Int,
  keepScreenAwake: Boolean,
  onExit: () -> Unit,
  onToggleKeepAwake: () -> Unit,
  onJumpToStep: (Int) -> Unit,
  onMinimize: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Clamp step display indices defensively so empty or 1-step recipes render "Step 1 of 1".
  val totalSteps = steps.size.coerceAtLeast(1)
  val currentOneBased = (currentStepIndex + 1).coerceIn(1, totalSteps)

  Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shadowElevation = 0.dp) {
    Column(modifier = Modifier.statusBarsPadding().fillMaxWidth()) {
      Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // FlowRow wraps 48.dp controls gracefully on 360dp at 1.35x font scale with zero slashes.
        FlowRow(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            // Explicit 48.dp End Cook pill closing the active session completely.
            Surface(
              onClick = onExit, shape = CircleShape, color = extendedColors.parchment,
              border = BorderStroke(1.dp, extendedColors.borderSubtle),
              modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Icon(Icons.Default.Close, contentDescription = "End Cook", modifier = Modifier.size(16.dp))
                Text("End Cook", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
              }
            }

            // Optional 48.dp Minimize pill docking Cook Mode into the persistent Mini-Timer bar.
            if (onMinimize != null) {
              Surface(
                onClick = onMinimize, shape = CircleShape, color = extendedColors.sageContainer,
                border = BorderStroke(1.dp, extendedColors.borderSubtle),
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                  verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                  Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Minimize Cook Mode", tint = extendedColors.onSageContainer, modifier = Modifier.size(18.dp))
                  Text("Minimize", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = extendedColors.onSageContainer, maxLines = 1, softWrap = false)
                }
              }
            }
          }

          Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            // Slash-free step counter badge (`Step 2 of 4`).
            Surface(
              shape = CircleShape, color = extendedColors.sageContainer,
              border = BorderStroke(1.dp, extendedColors.herbalSage.copy(alpha = 0.25f)),
              modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(extendedColors.herbalSage))
                Text("Step $currentOneBased of $totalSteps", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = extendedColors.onSageContainer, maxLines = 1, softWrap = false)
              }
            }

            // 48.dp wake-lock toggle ("Awake") controlling FLAG_KEEP_SCREEN_ON.
            Surface(
              onClick = onToggleKeepAwake, shape = CircleShape,
              color = if (keepScreenAwake) extendedColors.sageContainer else extendedColors.parchment,
              border = BorderStroke(1.dp, extendedColors.borderSubtle),
              modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
              ) {
                Box(
                  modifier = Modifier.size(8.dp).clip(CircleShape)
                    .background(if (keepScreenAwake) extendedColors.herbalSage else MaterialTheme.colorScheme.outline),
                )
                Text("Awake", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false)
              }
            }
          }
        }

        // Clickable segmented multi-step progress bar allowing 1-tap jumps between steps.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          steps.forEachIndexed { idx, step ->
            val barColor = when {
              idx < currentStepIndex -> extendedColors.herbalSage
              idx == currentStepIndex -> extendedColors.terracottaCookMode
              else -> MaterialTheme.colorScheme.surfaceContainer
            }
            val labelColor = when {
              idx < currentStepIndex -> extendedColors.herbalSage
              idx == currentStepIndex -> extendedColors.terracottaCookMode
              else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            }

            Column(
              modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).clickable { onJumpToStep(idx) }.padding(vertical = 2.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Box(modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(barColor))
              Text(
                text = if (idx < currentStepIndex) "✓ ${step.effectiveTitle}" else step.effectiveTitle,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (idx == currentStepIndex) FontWeight.Bold else FontWeight.Medium,
                color = labelColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }
      }
      HorizontalDivider(color = extendedColors.borderSubtle)
    }
  }
}

// Compact 80.dp espresso-terracotta gradient card highlighting active heat technique and visual cue.
@Composable
internal fun CookModeHeroVisualCard(
  step: MorningStep,
  fallbackTechnique: String,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Fall back gracefully when imported custom recipes omit explicit technique or visual cues.
  val technique = step.techniqueBadge?.takeIf { it.isNotBlank() } ?: fallbackTechnique
  val targetCue = step.visualTargetCue?.takeIf { it.isNotBlank() } ?: "Fragrant aroma and even golden color"

  Surface(
    modifier = modifier.fillMaxWidth().heightIn(min = 80.dp), shape = RoundedCornerShape(20.dp),
    border = BorderStroke(1.dp, extendedColors.borderSubtle), shadowElevation = 0.dp,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth()
        .background(Brush.linearGradient(colors = listOf(Color(0xFF232C27), Color(0xFF473125), Color(0xFF703B23))))
        .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = extendedColors.terracottaCookMode) {
          Text(
            text = technique.uppercase(), style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, softWrap = false,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
          )
        }
        Text(
          text = "Step ${step.stepNumber} · ${step.effectiveTitle}",
          style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
          color = Color.White.copy(alpha = 0.92f), maxLines = 1, overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
      }

      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(extendedColors.freshIndicator))
        Text("Target: $targetCue", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.92f), maxLines = 2, overflow = TextOverflow.Ellipsis)
      }
    }
  }
}

// Primary counter-distance instruction card rendering 19.sp prose, child safety box, and BOM chips.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CookModeInstructionCard(
  step: MorningStep,
  scaledQuantities: Map<String, Double>,
  catalogById: Map<String, IngredientCatalogItem>,
  checkedIngredientIds: Set<String>,
  safetyCalloutText: String?,
  onToggleStepIngredient: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Suppress duplicate step headline when it matches the body instruction verbatim.
  val explicitHeadline = step.headline?.trim()?.takeIf {
    it.isNotEmpty() && !it.equals(step.instruction.trim(), ignoreCase = true)
  }

  Surface(
    modifier = modifier.fillMaxWidth(), shape = KitchenTheme.shapes.card2xl,
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    border = BorderStroke(1.dp, extendedColors.borderSubtle), shadowElevation = 0.dp,
  ) {
    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      if (explicitHeadline != null) {
        Text(explicitHeadline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
      }

      // 19.sp medium-weight Plus Jakarta Sans keeps stove-side instructions legible at arm's length.
      Text(
        text = step.instruction,
        style = MaterialTheme.typography.bodyLarge.copy(
          fontFamily = InterfaceSansFontFamily, fontSize = 19.sp, lineHeight = 29.sp, fontWeight = FontWeight.Medium,
        ),
        color = MaterialTheme.colorScheme.onSurface,
      )

      // Inline pediatric safety callout box (EC-08) when youngest member needs modified texture/salt.
      if (!safetyCalloutText.isNullOrBlank()) {
        ChildSafetyCalloutBox(safetyText = safetyCalloutText)
      }

      // Interactive 48.dp checkable step-ingredient pills scaled to sessionState.targetServings.
      if (step.stepIngredientIds.isNotEmpty()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Text("STEP INGREDIENTS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
          Spacer(modifier = Modifier.width(8.dp))
          Text("Tap to check", style = MaterialTheme.typography.labelSmall, color = extendedColors.herbalSage, maxLines = 1, softWrap = false)
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          step.stepIngredientIds.forEach { ingId ->
            val item = catalogById[ingId]
            val qty = scaledQuantities[ingId]
            val isReady = ingId in checkedIngredientIds
            val qtyPrefix = when (qty) {
              null -> ""
              else -> {
                val rounded = (qty * 100.0).roundToInt() / 100.0
                val clean = if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
                "$clean ${item?.baseUnit?.wireCode ?: ""} "
              }
            }

            Surface(
              onClick = { onToggleStepIngredient(ingId) }, shape = CircleShape,
              color = if (isReady) extendedColors.sageContainer else extendedColors.parchment,
              border = BorderStroke(1.dp, if (isReady) extendedColors.herbalSage else extendedColors.borderSubtle),
              modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                Box(
                  modifier = Modifier.size(20.dp).clip(CircleShape)
                    .background(if (isReady) extendedColors.herbalSage else MaterialTheme.colorScheme.surfaceContainerLowest),
                  contentAlignment = Alignment.Center,
                ) {
                  if (isReady) Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                }
                Text("$qtyPrefix${item?.displayName ?: ingId}".trim(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
              }
            }
          }
        }
      }
    }
  }
}
