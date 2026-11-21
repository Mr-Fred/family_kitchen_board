package com.familykitchen.board.ui.recipes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.MorningStep
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.RestockDelta
import com.familykitchen.board.ui.theme.InterfaceSansFontFamily
import com.familykitchen.board.ui.theme.KitchenTheme
import kotlin.math.max

// Checkable Ingredients list, distraction-free 18.sp Method & Directions with 48.dp inline timers,
// Sous Chef Pairing card, and warm parchment Kitchen Notes drawer for CleanRecipeReaderScreen.

@Composable
internal fun ReaderIngredientsChecklistSection(
  recipe: Recipe,
  catalogById: Map<String, IngredientCatalogItem>,
  matchedIdSet: Set<String>,
  checkedIds: List<String>,
  missingRestockDeltas: List<RestockDelta>,
  onToggleChecked: (String) -> Unit,
  onUncheckAll: () -> Unit,
  onQueueSingleMissing: (String, RestockDelta?) -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Map missing RestockDeltas by ingredientId so single-item "Need +" chips queue whole packages.
  val deltaById = remember(missingRestockDeltas) {
    missingRestockDeltas.associateBy { it.ingredientId }
  }

  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    // Weighted left header + non-wrapping 48.dp TextButton fixes "Unchec\nk all" wrap on 360dp.
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(
        modifier = Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          text = "Ingredients",
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        // Live checked/total mise-en-place counter badge.
        Surface(
          shape = CircleShape,
          color = extendedColors.parchment,
          border = BorderStroke(1.dp, extendedColors.borderSubtle),
        ) {
          Text(
            text = "${checkedIds.size}/${recipe.bomLines.size}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
          )
        }
      }
      Spacer(modifier = Modifier.width(8.dp))
      // Non-wrapping 48.dp reset button clearing all checked mise-en-place rows in 1 tap.
      TextButton(
        onClick = onUncheckAll,
        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
      ) {
        Text(
          text = "Uncheck all",
          style = MaterialTheme.typography.labelMedium,
          color = extendedColors.herbalSage,
          maxLines = 1,
          softWrap = false,
        )
      }
    }

    // Render one 52.dp checkable ceramic row per scaled BOM line.
    recipe.bomLines.forEach { bomLine ->
      val item = catalogById[bomLine.ingredientId]
      val isChecked = bomLine.ingredientId in checkedIds
      val isInPantry = bomLine.ingredientId in matchedIdSet
      val qtyLabel = formatCleanNumber(bomLine.baseQuantity)
      val unitLabel = item?.baseUnit?.wireCode ?: "units"

      Surface(
        onClick = { onToggleChecked(bomLine.ingredientId) },
        shape = KitchenTheme.shapes.card2xl,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, extendedColors.borderSubtle),
        shadowElevation = 0.dp,
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp),
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          // Weight left checkbox + label so right-hand "In Pantry ✓" / "Need +" badge never wraps.
          Row(
            modifier = Modifier
              .weight(1f)
              .padding(end = 8.dp)
              .alpha(if (isChecked) 0.52f else 1f),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Checkbox(
              checked = isChecked,
              onCheckedChange = { onToggleChecked(bomLine.ingredientId) },
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = "$qtyLabel $unitLabel ${item?.displayName ?: bomLine.ingredientId}",
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.Medium,
              textDecoration = if (isChecked) TextDecoration.LineThrough else TextDecoration.None,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f),
            )
          }

          // Show non-wrapping "In Pantry ✓" when stocked or 1-tap "Need +" grocery queue pill when missing.
          when {
            isInPantry -> {
              Surface(
                shape = CircleShape,
                color = extendedColors.sageContainer,
              ) {
                Text(
                  text = "In Pantry ✓",
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = FontWeight.SemiBold,
                  color = extendedColors.onSageContainer,
                  maxLines = 1,
                  softWrap = false,
                  modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
              }
            }

            else -> {
              Surface(
                onClick = {
                  onQueueSingleMissing(bomLine.ingredientId, deltaById[bomLine.ingredientId])
                },
                shape = CircleShape,
                color = extendedColors.tertiaryFixed,
                modifier = Modifier.defaultMinSize(minHeight = 40.dp),
              ) {
                Box(
                  modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                  contentAlignment = Alignment.Center,
                ) {
                  Text(
                    text = "Need +",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = extendedColors.onTertiaryFixed,
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
  }
}

// Distraction-free 18.sp Method & Directions step cards with 48.dp live inline timers.
@Composable
internal fun MethodAndDirectionsSection(
  steps: List<MorningStep>,
  inlineTimerSeconds: MutableMap<Int, Int>,
  inlineTimerRunning: MutableMap<Int, Boolean>,
) {
  val extendedColors = KitchenTheme.extendedColors
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    // Weighted section title + singular/plural step count ("1 Step" vs "3 Steps").
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "Method & Directions",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = if (steps.size == 1) "1 Step" else "${steps.size} Steps",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        softWrap = false,
      )
    }

    steps.forEach { step ->
      // Resolve live countdown state for timed steps while leaving non-timed steps uncluttered.
      val baseDuration = step.timerSeconds ?: 0
      val currentRem = inlineTimerSeconds[step.stepNumber] ?: baseDuration
      val isRunning = inlineTimerRunning[step.stepNumber] == true
      val isFinished = baseDuration > 0 && inlineTimerSeconds.containsKey(step.stepNumber) && currentRem == 0

      // Clean ceramic card with 1.dp warm border replacing timeline vertical rail clutter.
      Surface(
        shape = KitchenTheme.shapes.card2xl,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, extendedColors.borderSubtle),
        shadowElevation = 0.dp,
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "Step ${step.stepNumber} • ${step.effectiveTitle}",
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.Bold,
              color = if (baseDuration > 0) extendedColors.terracotta else extendedColors.herbalSage,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f),
            )
            // Optional single-line culinary technique pill (e.g., "Pan-Sear", "Simmer").
            val badge = step.techniqueBadge?.takeIf { it.isNotBlank() }
            if (badge != null) {
              Spacer(modifier = Modifier.width(8.dp))
              Surface(
                shape = CircleShape,
                color = extendedColors.secondaryFixed,
              ) {
                Text(
                  text = badge,
                  style = MaterialTheme.typography.labelSmall,
                  color = extendedColors.onSecondaryFixed,
                  maxLines = 1,
                  softWrap = false,
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
              }
            }
          }

          // Countertop-legible 18.sp / 27.sp instruction prose.
          Text(
            text = step.instruction,
            style = MaterialTheme.typography.bodyLarge.copy(
              fontFamily = InterfaceSansFontFamily,
              fontSize = 18.sp,
              lineHeight = 27.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
          )

          if (baseDuration > 0) {
            // Map timer lifecycle (idle -> running -> paused -> finished) to calm semantic tokens.
            val (btnContainer, btnContent, btnText) = when {
              isFinished -> Triple(
                extendedColors.sageContainer,
                extendedColors.onSageContainer,
                "Done ✓ (Restart)",
              )
              isRunning -> Triple(
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
                "Running ${formatShortTimer(currentRem)}",
              )
              currentRem < baseDuration -> Triple(
                extendedColors.terracottaContainer,
                extendedColors.onTerracottaContainer,
                "Paused ${formatShortTimer(currentRem)}",
              )
              else -> Triple(
                extendedColors.terracottaContainer,
                extendedColors.onTerracottaContainer,
                "Timer ${formatShortTimer(baseDuration)}",
              )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
              // 48.dp inline timer pill allowing cooks to time steps directly inside Reader.
              Button(
                onClick = {
                  when {
                    isRunning -> inlineTimerRunning[step.stepNumber] = false
                    currentRem <= 0 -> {
                      inlineTimerSeconds[step.stepNumber] = baseDuration
                      inlineTimerRunning[step.stepNumber] = true
                    }
                    else -> {
                      inlineTimerSeconds[step.stepNumber] = currentRem
                      inlineTimerRunning[step.stepNumber] = true
                    }
                  }
                },
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                colors = ButtonDefaults.buttonColors(
                  containerColor = btnContainer,
                  contentColor = btnContent,
                ),
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
              ) {
                Icon(imageVector = Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                  text = btnText,
                  style = MaterialTheme.typography.labelMedium,
                  fontWeight = FontWeight.Bold,
                  maxLines = 1,
                  softWrap = false,
                )
              }

              // Optional sensory doneness cue ("Skin releases cleanly when golden").
              val sideTip = step.chefTip?.takeIf { it.isNotBlank() }
              if (sideTip != null) {
                Text(
                  text = sideTip,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }
    }
  }
}

// Editorial beverage & side-dish pairing card rendered below Method & Directions.
@Composable
internal fun SousChefPairingCard(pairingNote: String) {
  val extendedColors = KitchenTheme.extendedColors
  Surface(
    shape = KitchenTheme.shapes.card2xl,
    color = extendedColors.tertiaryFixed,
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalAlignment = Alignment.Top,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(
        modifier = Modifier
          .size(36.dp)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.tertiaryContainer),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Default.LocalBar,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onTertiaryContainer,
          modifier = Modifier.size(20.dp),
        )
      }
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          text = "SOUS CHEF PAIRING",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.tertiary,
        )
        Text(
          text = pairingNote,
          style = MaterialTheme.typography.bodyMedium,
          color = extendedColors.onTertiaryFixed,
        )
      }
    }
  }
}

// Warm parchment Kitchen Notes card persisting household recipe notes via O(1) batch write.
@Composable
internal fun KitchenNotesCard(
  recipeId: String,
  builtInChefNote: String?,
  persistedNotes: List<String>,
  onAddNote: (String) -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Scope note composer state to `recipeId` so draft text never leaks across recipes.
  val composerOpenState = rememberSaveable(recipeId) { mutableStateOf(false) }
  val draftNoteState = rememberSaveable(recipeId) { mutableStateOf("") }
  val sessionAddedNotes = remember(recipeId, persistedNotes) {
    mutableStateListOf<String>().apply { addAll(persistedNotes) }
  }

  // Merge built-in chef note ahead of persisted household notes.
  val allNotes = buildList {
    builtInChefNote?.takeIf { it.isNotBlank() }?.let(::add)
    addAll(sessionAddedNotes)
  }

  // Warm parchment surface replaces cold lavender/indigo note cards.
  Surface(
    shape = KitchenTheme.shapes.card2xl,
    color = extendedColors.parchment,
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      // Weighted header row + 48.dp Add Note toggle inside KitchenNotesCard.
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          modifier = Modifier.weight(1f),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            imageVector = Icons.Default.EditNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "Kitchen Notes (${allNotes.size})",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        // 48.dp TextButton toggles the single-line household note input row.
        TextButton(
          onClick = { composerOpenState.value = !composerOpenState.value },
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        ) {
          Text(
            text = "Add Note",
            style = MaterialTheme.typography.labelSmall,
            color = extendedColors.herbalSage,
            maxLines = 1,
            softWrap = false,
          )
        }
      }

      // Render italic quote styling for built-in chef guidance and saved family notes.
      when {
        allNotes.isEmpty() -> {
          Text(
            text = "\"Tap Add Note to save household tweaks, kid portion reactions, or pan adjustments.\"",
            style = MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        else -> {
          allNotes.forEach { note ->
            Text(
              text = "\"$note\"",
              style = MaterialTheme.typography.bodySmall,
              fontStyle = FontStyle.Italic,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }

      // Trim whitespace and ignore blank notes before dispatching O(1) persistence callback.
      val saveDraftNote = {
        val clean = draftNoteState.value.trim()
        if (clean.isNotEmpty()) {
          sessionAddedNotes.add(clean)
          onAddNote(clean)
          draftNoteState.value = ""
          composerOpenState.value = false
        }
      }
      // Expandable note input with IME Done action and 48.dp Save button.
      AnimatedVisibility(visible = composerOpenState.value) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedTextField(
            value = draftNoteState.value,
            onValueChange = { draftNoteState.value = it },
            placeholder = { Text(text = "e.g., Temper yogurt off-heat…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
              capitalization = KeyboardCapitalization.Sentences,
              imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { saveDraftNote() }),
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp),
          )
          Button(
            onClick = saveDraftNote,
            shape = CircleShape,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          ) {
            Text(text = "Save", maxLines = 1, softWrap = false)
          }
        }
      }
    }
  }
}

// Formats inline step timer durations as compact M:SS ("4:00", "0:45").
private fun formatShortTimer(totalSeconds: Int): String {
  // Clamp negative values at 0 so expired timers display "0:00" cleanly.
  val clamped = max(0, totalSeconds)
  val mins = clamped / 60
  val secs = clamped % 60
  return "$mins:${secs.toString().padStart(2, '0')}"
}
