package com.familykitchen.board.ui.recipes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AssistChip
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
import com.familykitchen.board.domain.BomLine
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.MorningStep
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.RestockDelta
import com.familykitchen.board.ui.theme.InterfaceSansFontFamily
import com.familykitchen.board.ui.theme.KitchenTheme
import kotlin.math.max

// Checkable Ingredients list with 1-tap `Swap Ingredient` BOM picker, distraction-free 18.sp
// Method & Directions with 48.dp inline timers, Sous Chef Pairing, and warm Kitchen Notes drawer.

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderIngredientsChecklistSection(
  recipe: Recipe,
  catalogById: Map<String, IngredientCatalogItem>,
  matchedIdSet: Set<String>,
  checkedIds: List<String>,
  missingRestockDeltas: List<RestockDelta>,
  catalog: List<IngredientCatalogItem> = emptyList(),
  householdAllergens: List<String> = emptyList(),
  onToggleChecked: (String) -> Unit,
  onUncheckAll: () -> Unit,
  onQueueSingleMissing: (String, RestockDelta?) -> Unit,
  onSwapIngredient: (fromIngredientId: String, toIngredientId: String) -> Unit = { _, _ -> },
) {
  val extendedColors = KitchenTheme.extendedColors
  // Index missing restock deltas by ingredientId for O(1) single-item Queue button lookups.
  val deltaById = remember(missingRestockDeltas) {
    missingRestockDeltas.associateBy { it.ingredientId }
  }
  // Track which single BOM row currently has its inline Swap Ingredient picker expanded.
  val activeSwapIngIdState = rememberSaveable(recipe.recipeId) { mutableStateOf<String?>(null) }
  // Normalize household allergen codes once so swap candidates exclude conflicting items.
  val upperAllergens = remember(householdAllergens) {
    householdAllergens.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
  }

  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    // Section header displaying checked mise-en-place progress + 48.dp Uncheck all reset.
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
        Surface(
          shape = CircleShape,
          color = extendedColors.parchment,
          border = BorderStroke(1.dp, extendedColors.borderSubtle),
        ) {
          Text(
            text = "${checkedIds.size} of ${recipe.bomLines.size}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
          )
        }
      }
      Spacer(modifier = Modifier.width(8.dp))
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

    // Render one checkable ceramic card per scaled BOM line with 1-tap Swap Ingredient picker.
    recipe.bomLines.forEach { bomLine ->
      val item = catalogById[bomLine.ingredientId]
      val isChecked = bomLine.ingredientId in checkedIds
      val isInPantry = bomLine.ingredientId in matchedIdSet
      // Format scaled BOM quantity without trailing .0 noise.
      val qtyLabel = formatCleanNumber(bomLine.baseQuantity)
      val unitLabel = item?.baseUnit?.wireCode ?: "units"
      // Sanitize catalog display name so BOM rows stay free of slashes and parentheses.
      val cleanDisplay = readerStepsSanitize(item?.displayName ?: bomLine.ingredientId)

      // Compute EC-03 substituteGroupId and same-category local replacement candidates.
      val swapCandidates = remember(bomLine, item, catalog, upperAllergens) {
        readerStepsFindSwapCandidates(bomLine, item, catalog, upperAllergens)
      }
      val isSwapOpen = activeSwapIngIdState.value == bomLine.ingredientId

      // Ceramic surface card per ingredient row with 1.dp warm stone border.
      Surface(
        shape = KitchenTheme.shapes.card2xl,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, extendedColors.borderSubtle),
        shadowElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            // Left mise-en-place checkbox + strikethrough ingredient amount and title.
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
                text = "$qtyLabel $unitLabel $cleanDisplay",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                textDecoration = if (isChecked) TextDecoration.LineThrough else TextDecoration.None,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
              )
            }

            // Right action cluster: 48.dp Swap Ingredient pill + In Pantry / Queue pill.
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              if (swapCandidates.isNotEmpty()) {
                // 48.dp pill toggling the inline 1-tap local market substitute selector.
                Surface(
                  onClick = {
                    activeSwapIngIdState.value =
                      if (isSwapOpen) null else bomLine.ingredientId
                  },
                  shape = CircleShape,
                  color = extendedColors.parchment,
                  border = BorderStroke(1.dp, extendedColors.borderSubtle),
                  modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                  Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Icon(
                      imageVector = Icons.Default.SwapHoriz,
                      contentDescription = null,
                      tint = extendedColors.herbalSage,
                      modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                      text = "Swap Ingredient",
                      style = MaterialTheme.typography.labelSmall,
                      fontWeight = FontWeight.SemiBold,
                      maxLines = 1,
                      softWrap = false,
                    )
                  }
                }
              }

              when {
                isInPantry -> {
                  // Herbal Sage badge confirming this BOM item is currently stocked in Pantry.
                  Surface(
                    shape = CircleShape,
                    color = extendedColors.sageContainer,
                  ) {
                    Text(
                      text = "In Pantry",
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
                  // 48.dp Queue pill adding a single missing item onto Smart Grocery List.
                  Surface(
                    onClick = {
                      onQueueSingleMissing(bomLine.ingredientId, deltaById[bomLine.ingredientId])
                    },
                    shape = CircleShape,
                    color = extendedColors.tertiaryFixed,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                  ) {
                    Box(
                      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                      contentAlignment = Alignment.Center,
                    ) {
                      Text(
                        text = "Queue",
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

          // Inline 1-tap substitute picker surfacing group-compatible and same-category local items.
          AnimatedVisibility(visible = isSwapOpen && swapCandidates.isNotEmpty()) {
            Column(
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Text(
                text = "Choose a local market replacement:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              // FlowRow keeps all 48.dp replacement chips legible down to 360dp width.
              FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                swapCandidates.forEach { candidate ->
                  // Append · Stocked when the replacement ingredient is already in Pantry.
                  val inStockBadge = if (candidate.ingredientId in matchedIdSet) " · Stocked" else ""
                  AssistChip(
                    onClick = {
                      // Trigger 1-tap BOM swap and collapse the inline picker immediately.
                      onSwapIngredient(bomLine.ingredientId, candidate.ingredientId)
                      activeSwapIngIdState.value = null
                    },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    label = {
                      Text(
                        text = "${readerStepsSanitize(candidate.displayName)}$inStockBadge",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        softWrap = false,
                      )
                    },
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

private fun readerStepsFindSwapCandidates(
  bomLine: BomLine,
  fromItem: IngredientCatalogItem?,
  catalog: List<IngredientCatalogItem>,
  upperAllergens: Set<String>,
): List<IngredientCatalogItem> {
  if (catalog.isEmpty()) return emptyList()
  val sharedGroups = buildSet {
    bomLine.substituteGroupId?.takeIf { it.isNotBlank() }?.let(::add)
    fromItem?.substituteGroupIds?.forEach(::add)
  }

  // Prioritize explicit substituteGroupIds first, then locally available same-category items.
  return catalog
    .filter { cand ->
      cand.ingredientId != bomLine.ingredientId &&
        cand.isLocallyAvailable &&
        cand.allergens.none { it.trim().uppercase() in upperAllergens } &&
        (cand.substituteGroupIds.any { it in sharedGroups } ||
          (fromItem != null && cand.category == fromItem.category))
    }
    .sortedByDescending { cand -> cand.substituteGroupIds.any { it in sharedGroups } }
    .take(10)
}

@Composable
internal fun MethodAndDirectionsSection(
  steps: List<MorningStep>,
  inlineTimerSeconds: MutableMap<Int, Int>,
  inlineTimerRunning: MutableMap<Int, Boolean>,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Render distraction-free 18.sp instruction cards with independent 48.dp inline timers.
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "Method and Directions",
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

    // Emit one spacious ceramic instruction card per MorningStep.
    steps.forEach { step ->
      val baseDuration = step.timerSeconds ?: 0
      val currentRem = inlineTimerSeconds[step.stepNumber] ?: baseDuration
      val isRunning = inlineTimerRunning[step.stepNumber] == true
      // Detect completed countdowns so the pill switches to Done · Restart.
      val isFinished = baseDuration > 0 && inlineTimerSeconds.containsKey(step.stepNumber) && currentRem == 0

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
            // Color-code timed cooking steps in Terracotta and prep steps in Herbal Sage.
            Text(
              text = "Step ${step.stepNumber} · ${readerStepsSanitize(step.effectiveTitle)}",
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.Bold,
              color = if (baseDuration > 0) extendedColors.terracotta else extendedColors.herbalSage,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f),
            )
            val badge = step.techniqueBadge?.takeIf { it.isNotBlank() }
            if (badge != null) {
              Spacer(modifier = Modifier.width(8.dp))
              // Optional culinary technique pill (e.g., Sear, Simmer, Fold).
              Surface(
                shape = CircleShape,
                color = extendedColors.secondaryFixed,
              ) {
                Text(
                  text = readerStepsSanitize(badge),
                  style = MaterialTheme.typography.labelSmall,
                  color = extendedColors.onSecondaryFixed,
                  maxLines = 1,
                  softWrap = false,
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
              }
            }
          }

          // Large 18.sp / 27.sp line-height body copy legible at arm's length on the counter.
          Text(
            text = step.instruction,
            style = MaterialTheme.typography.bodyLarge.copy(
              fontFamily = InterfaceSansFontFamily,
              fontSize = 18.sp,
              lineHeight = 27.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
          )

          // Render interactive 48.dp timer button whenever the step specifies timerSeconds > 0.
          if (baseDuration > 0) {
            // Map timer state to semantic container colors and clean status text.
            val (btnContainer, btnContent, btnText) = when {
              isFinished -> Triple(
                extendedColors.sageContainer,
                extendedColors.onSageContainer,
                "Done · Restart",
              )
              isRunning -> Triple(
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
                "Running ${readerStepsFormatShortTimer(currentRem)}",
              )
              currentRem < baseDuration -> Triple(
                extendedColors.terracottaContainer,
                extendedColors.onTerracottaContainer,
                "Paused ${readerStepsFormatShortTimer(currentRem)}",
              )
              else -> Triple(
                extendedColors.terracottaContainer,
                extendedColors.onTerracottaContainer,
                "Timer ${readerStepsFormatShortTimer(baseDuration)}",
              )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Button(
                onClick = {
                  // Toggle pause, restart from 0, or resume remaining seconds cleanly.
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

@Composable
internal fun SousChefPairingCard(pairingNote: String) {
  val extendedColors = KitchenTheme.extendedColors
  // Warm tertiary pairing card suggesting refreshing side dishes or non-alcoholic pairings.
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
      // Circular beverage/herb medallion anchored beside the pairing note.
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

@Composable
internal fun KitchenNotesCard(
  recipeId: String,
  builtInChefNote: String?,
  persistedNotes: List<String>,
  onAddNote: (String) -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Persist composer toggle and draft note per recipeId across configuration changes.
  val composerOpenState = rememberSaveable(recipeId) { mutableStateOf(false) }
  val draftNoteState = rememberSaveable(recipeId) { mutableStateOf("") }
  // Mirror persisted household notes locally so newly saved notes render immediately.
  val sessionAddedNotes = remember(recipeId, persistedNotes) {
    mutableStateListOf<String>().apply { addAll(persistedNotes) }
  }

  // Prepend optional built-in chef note ahead of chronological family tweaks.
  val allNotes = buildList {
    builtInChefNote?.takeIf { it.isNotBlank() }?.let(::add)
    addAll(sessionAddedNotes)
  }

  // Warm parchment household notebook card with 48.dp Add Note toggle.
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
            text = "Kitchen Notes · ${allNotes.size}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        // 48.dp tap target opening or closing the inline note composer.
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

      // Show warm italic prompt when empty, or list saved household notes in quotation marks.
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

      // Commit non-blank note to both local session state and persisted KitchenSnapshot.
      val saveDraftNote = {
        val clean = draftNoteState.value.trim()
        if (clean.isNotEmpty()) {
          sessionAddedNotes.add(clean)
          onAddNote(clean)
          draftNoteState.value = ""
          composerOpenState.value = false
        }
      }
      // Inline text field + 48.dp Save pill supporting IME Done submission.
      AnimatedVisibility(visible = composerOpenState.value) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedTextField(
            value = draftNoteState.value,
            onValueChange = { draftNoteState.value = it },
            placeholder = { Text(text = "Temper yogurt off-heat…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
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

// Strips slashes and parentheses from step headers and catalog names for clean UI badges.
private fun readerStepsSanitize(raw: String): String =
  raw
    .replace(" / ", " and ")
    .replace("/", " · ")
    .replace("(", "")
    .replace(")", "")
    .replace(Regex("\\s+"), " ")
    .trim()

// Formats remaining step timer seconds as M:SS with zero-padded seconds.
private fun readerStepsFormatShortTimer(totalSeconds: Int): String {
  // Clamp at 0 so expired timers never render negative durations.
  val clamped = max(0, totalSeconds)
  val mins = clamped / 60
  val secs = clamped % 60
  return "$mins:${secs.toString().padStart(2, '0')}"
}
