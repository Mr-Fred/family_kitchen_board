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

// Single-line 48.dp Top Header, compact 80.dp Technique & Doneness Strip, and distraction-free
// 19.sp Step Instruction Card for StepByStepCookModeScreen.

@Composable
internal fun CookModeTopHeader(
  steps: List<MorningStep>,
  currentStepIndex: Int,
  keepScreenAwake: Boolean,
  onExit: () -> Unit,
  onToggleKeepAwake: () -> Unit,
  onJumpToStep: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  val totalSteps = steps.size.coerceAtLeast(1)
  val currentOneBased = (currentStepIndex + 1).coerceIn(1, totalSteps)

  Surface(
    modifier = modifier.fillMaxWidth(),
    color = MaterialTheme.colorScheme.surface,
    shadowElevation = 0.dp,
  ) {
    Column(
      modifier = Modifier
        .statusBarsPadding()
        .fillMaxWidth(),
    ) {
      Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        // Single-line 48.dp header row (`Exit` | `Step k/N` | `Awake` — never wraps "Screen\nAwake").
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          // 48.dp Exit pill returning cleanly to Plan or Recipe Reader.
          Surface(
            onClick = onExit,
            shape = CircleShape,
            color = extendedColors.parchment,
            border = BorderStroke(1.dp, extendedColors.borderSubtle),
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Exit Cook Mode",
                modifier = Modifier.size(16.dp),
              )
              Text(
                text = "Exit",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
              )
            }
          }

          // Centered non-wrapping step counter badge (`Step 2/4`).
          Surface(
            shape = CircleShape,
            color = extendedColors.sageContainer,
            border = BorderStroke(1.dp, extendedColors.herbalSage.copy(alpha = 0.25f)),
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Box(
                modifier = Modifier
                  .size(7.dp)
                  .clip(CircleShape)
                  .background(extendedColors.herbalSage),
              )
              Text(
                text = "Step $currentOneBased/$totalSteps",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = extendedColors.onSageContainer,
                maxLines = 1,
                softWrap = false,
              )
            }
          }

          // 48.dp wake-lock toggle ("Awake") controlling WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.
          Surface(
            onClick = onToggleKeepAwake,
            shape = CircleShape,
            color = if (keepScreenAwake) extendedColors.sageContainer else extendedColors.parchment,
            border = BorderStroke(1.dp, extendedColors.borderSubtle),
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
              Box(
                modifier = Modifier
                  .size(8.dp)
                  .clip(CircleShape)
                  .background(
                    if (keepScreenAwake) extendedColors.herbalSage else MaterialTheme.colorScheme.outline,
                  ),
              )
              Text(
                text = "Awake",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
              )
            }
          }
        }

        // Clickable segmented multi-step progress bar allowing 1-tap jumps between steps.
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          steps.forEachIndexed { idx, step ->
            // Completed steps render in Herbal Sage, active step in Terracotta, future steps muted.
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
              modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .clickable { onJumpToStep(idx) }
                .padding(vertical = 2.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .height(6.dp)
                  .clip(CircleShape)
                  .background(barColor),
              )
              Text(
                text = if (idx < currentStepIndex) "✓ ${step.effectiveTitle}" else step.effectiveTitle,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (idx == currentStepIndex) FontWeight.Bold else FontWeight.Medium,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }
      }
      HorizontalDivider(color = extendedColors.borderSubtle)
    }
  }
}

// Compact 80.dp Technique & Doneness Strip keeping step prose and timer visible above the fold.
@Composable
internal fun CookModeHeroVisualCard(
  step: MorningStep,
  fallbackTechnique: String,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Resolve culinary technique badge and visual/aroma target cue without network images.
  val technique = step.techniqueBadge?.takeIf { it.isNotBlank() } ?: fallbackTechnique
  val targetCue = step.visualTargetCue?.takeIf { it.isNotBlank() }
    ?: "Fragrant aroma and even golden color"

  // Replaces oversized 200.dp decorative box so 19.sp step instructions sit immediately in view.
  Surface(
    modifier = modifier
      .fillMaxWidth()
      .heightIn(min = 80.dp),
    shape = RoundedCornerShape(20.dp),
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
    shadowElevation = 0.dp,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          Brush.linearGradient(
            colors = listOf(
              Color(0xFF232C27),
              Color(0xFF473125),
              Color(0xFF703B23),
            ),
          ),
        )
        .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      // Badge #1: Active technique capsule + step title.
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Surface(shape = CircleShape, color = extendedColors.terracottaCookMode) {
          Text(
            text = technique.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
          )
        }
        Text(
          text = "Step ${step.stepNumber} · ${step.effectiveTitle}",
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.SemiBold,
          color = Color.White.copy(alpha = 0.92f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
      }

      // Badge #2: Sensory doneness target cue.
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Box(
          modifier = Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(extendedColors.freshIndicator),
        )
        Text(
          text = "Target: $targetCue",
          style = MaterialTheme.typography.bodySmall,
          color = Color.White.copy(alpha = 0.92f),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

// Distraction-free 19.sp Step Instruction card suppressing duplicate headline when step.headline == null.
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
  // Only render a bold sub-headline when an explicit non-duplicate `step.headline` exists.
  val explicitHeadline = step.headline?.trim()?.takeIf {
    it.isNotEmpty() && !it.equals(step.instruction.trim(), ignoreCase = true)
  }

  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = KitchenTheme.shapes.card2xl,
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
    shadowElevation = 0.dp,
  ) {
    Column(
      modifier = Modifier.padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (explicitHeadline != null) {
        Text(
          text = explicitHeadline,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }

      // Countertop-legible 19.sp / 29.sp instruction prose (never obscured by duplicate 24.sp headline).
      Text(
        text = step.instruction,
        style = MaterialTheme.typography.bodyLarge.copy(
          fontFamily = InterfaceSansFontFamily,
          fontSize = 19.sp,
          lineHeight = 29.sp,
          fontWeight = FontWeight.Medium,
        ),
        color = MaterialTheme.colorScheme.onSurface,
      )

      // EC-08 child choking-safety reminder embedded inline for family plating steps.
      if (!safetyCalloutText.isNullOrBlank()) {
        ChildSafetyCalloutBox(safetyText = safetyCalloutText)
      }

      // Weighted STEP INGREDIENTS header prevents "INGREDIENTS FOR THIS STEPmark" collision.
      if (step.stepIngredientIds.isNotEmpty()) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "STEP INGREDIENTS",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = "Tap to check",
            style = MaterialTheme.typography.labelSmall,
            color = extendedColors.herbalSage,
            maxLines = 1,
            softWrap = false,
          )
        }

        // 48.dp touch-height ingredient pills scaled to household targetServings.
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          step.stepIngredientIds.forEach { ingId ->
            val item = catalogById[ingId]
            val qty = scaledQuantities[ingId]
            val isReady = ingId in checkedIngredientIds
            // Format scaled step quantity cleanly without trailing ".0" decimals.
            val qtyPrefix = when (qty) {
              null -> ""
              else -> {
                val rounded = (qty * 100.0).roundToInt() / 100.0
                val clean = if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
                "$clean ${item?.baseUnit?.wireCode ?: ""} "
              }
            }

            // Warm sage container highlights ready step ingredients; parchment marks unchecked items.
            Surface(
              onClick = { onToggleStepIngredient(ingId) },
              shape = CircleShape,
              color = if (isReady) extendedColors.sageContainer else extendedColors.parchment,
              border = BorderStroke(
                width = 1.dp,
                color = if (isReady) extendedColors.herbalSage else extendedColors.borderSubtle,
              ),
              modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                // 20.dp inner indicator circle inside the >= 48.dp touch-height pill.
                Box(
                  modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(
                      if (isReady) extendedColors.herbalSage else MaterialTheme.colorScheme.surfaceContainerLowest,
                    ),
                  contentAlignment = Alignment.Center,
                ) {
                  if (isReady) {
                    Icon(
                      imageVector = Icons.Default.Check,
                      contentDescription = null,
                      tint = Color.White,
                      modifier = Modifier.size(12.dp),
                    )
                  }
                }
                // Combine scaled quantity, unit wire code, and ingredient display name.
                Text(
                  text = "$qtyPrefix${item?.displayName ?: ingId}".trim(),
                  style = MaterialTheme.typography.labelMedium,
                  fontWeight = FontWeight.Medium,
                  color = MaterialTheme.colorScheme.onSurface,
                )
              }
            }
          }
        }
      }
    }
  }
}
