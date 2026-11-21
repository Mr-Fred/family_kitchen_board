package com.familykitchen.board.ui.cookmode

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.CookModeSessionState
import com.familykitchen.board.domain.MorningStep
import com.familykitchen.board.domain.StepTimerState
import com.familykitchen.board.ui.theme.KitchenTheme
import com.familykitchen.board.ui.theme.TabularTimerTextStyle

// Active Kitchen Multi-Timer card (52.dp controls, "+1m" without duplicate "+", deduplicated
// Sous-Chef Rule), Hands-Free Q&A card, and single unified 72.dp Messy-Hands Bottom Bar.

private val QUICK_COOK_QUESTIONS: List<String> = listOf(
  "When to flip?",
  "Toddler safe cut?",
  "Yogurt swap?",
  "Étape suivante",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CookModeMultiTimerCard(
  step: MorningStep,
  activeTimer: StepTimerState?,
  backgroundTimers: List<StepTimerState>,
  fallbackChefTip: String?,
  onToggleTimer: () -> Unit,
  onAddOneMinute: () -> Unit,
  onResetTimer: () -> Unit,
  onJumpToBackgroundTimerStep: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Resolve effective step duration from active timer state or step metadata (3:00 fallback).
  val effectiveTotal = activeTimer?.totalSeconds ?: (step.timerSeconds ?: 180)
  val totalMins = effectiveTotal / 60
  val totalSecs = effectiveTotal % 60
  // Format zero-padded tabular MM:SS so digits never jitter horizontally on 1-second ticks.
  val displayMmSs = activeTimer?.formattedRemainingMmSs
    ?: "${totalMins.toString().padStart(2, '0')}:${totalSecs.toString().padStart(2, '0')}"
  val remainingFraction = activeTimer?.remainingFraction ?: 1f
  val remainingPct = activeTimer?.remainingPercent ?: 100
  val isRunning = activeTimer?.isRunning == true
  val rawRuleTip = step.chefTip?.takeIf { it.isNotBlank() } ?: fallbackChefTip
  // Strip any pre-existing "Sous-Chef Rule:" prefix so it never renders twice.
  val cleanRuleTip = rawRuleTip?.trim()?.removePrefix("Sous-Chef Rule:")?.trim()

  // Warm terracotta-tinted timer card with 1.dp border and zero drop shadow.
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = KitchenTheme.shapes.card2xl,
    border = BorderStroke(1.dp, extendedColors.terracottaCookMode.copy(alpha = 0.25f)),
    shadowElevation = 0.dp,
  ) {
    Column(
      modifier = Modifier
        .background(
          Brush.linearGradient(
            colors = listOf(extendedColors.terracottaLight, MaterialTheme.colorScheme.surfaceContainerLowest),
          ),
        )
        .padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      // Top header row: weighted step timer title + 52.dp lambda CircularProgressIndicator ring.
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Box(
              modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(extendedColors.terracottaCookMode),
            )
            Text(
              text = "${step.effectiveTitle.uppercase()} TIMER",
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.Bold,
              color = extendedColors.terracottaCookMode,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          Text(
            text = "Duration: ${totalMins}m ${totalSecs.toString().padStart(2, '0')}s",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        Box(
          modifier = Modifier.size(52.dp),
          contentAlignment = Alignment.Center,
        ) {
          // Compose BOM 2024.10.01 lambda progress overload prevents unnecessary recompositions.
          CircularProgressIndicator(
            progress = { remainingFraction },
            modifier = Modifier.fillMaxSize(),
            color = extendedColors.terracottaCookMode,
            trackColor = extendedColors.terracottaCookMode.copy(alpha = 0.16f),
            strokeWidth = 3.5.dp,
          )
          Text(
            text = "$remainingPct%",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = extendedColors.terracottaCookMode,
            maxLines = 1,
            softWrap = false,
          )
        }
      }

      // Large tabular countdown digits readable from across a steamy kitchen counter.
      Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          text = displayMmSs,
          style = TabularTimerTextStyle,
          color = extendedColors.charcoalInk,
          maxLines = 1,
          softWrap = false,
        )
        Text(
          text = if (activeTimer?.isCompleted == true) "done!" else "left",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(bottom = 8.dp),
          maxLines = 1,
          softWrap = false,
        )
      }

      // 52.dp tactile timer controls (`Start`/`Pause` | `+1m` without duplicate Add icon | `Reset`).
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Button(
          onClick = onToggleTimer,
          modifier = Modifier.weight(1.25f).defaultMinSize(minHeight = 52.dp),
          shape = CircleShape,
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = extendedColors.terracottaCookMode,
            contentColor = Color.White,
          ),
        ) {
          Icon(
            imageVector = if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(4.dp))
          // Distinguish initial Start vs mid-countdown Resume vs active Pause.
          val startLabel = when {
            isRunning -> "Pause"
            activeTimer != null && activeTimer.remainingSeconds < activeTimer.totalSeconds && activeTimer.remainingSeconds > 0 ->
              "Resume"
            else -> "Start"
          }
          Text(
            text = startLabel,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
          )
        }

        // Single "+1m" text without a leading Add icon fixes the "+ +1" S22 audit collision.
        OutlinedButton(
          onClick = onAddOneMinute,
          modifier = Modifier.weight(0.85f).defaultMinSize(minHeight = 52.dp),
          shape = CircleShape,
          border = BorderStroke(1.dp, extendedColors.terracottaCookMode.copy(alpha = 0.4f)),
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
        ) {
          Text(
            text = "+1m",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = extendedColors.terracottaCookMode,
            maxLines = 1,
            softWrap = false,
          )
        }

        OutlinedButton(
          onClick = onResetTimer,
          modifier = Modifier.weight(0.9f).defaultMinSize(minHeight = 52.dp),
          shape = CircleShape,
          border = BorderStroke(1.dp, extendedColors.borderSubtle),
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
        ) {
          Text(
            text = "Reset",
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            softWrap = false,
          )
        }
      }

      // Concurrent background step timer chips letting cooks jump back to simmering pots in 1 tap.
      if (backgroundTimers.isNotEmpty()) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          backgroundTimers.forEach { bgTimer ->
            AssistChip(
              onClick = { onJumpToBackgroundTimerStep(bgTimer.stepNumber - 1) },
              shape = CircleShape,
              modifier = Modifier.defaultMinSize(minHeight = 48.dp),
              label = {
                Text(
                  text = "Step ${bgTimer.stepNumber}: ${bgTimer.formattedRemainingMmSs}",
                  style = MaterialTheme.typography.labelSmall,
                  maxLines = 1,
                  softWrap = false,
                )
              },
            )
          }
        }
      }

      // Single deduplicated Sous-Chef Rule tip line.
      if (!cleanRuleTip.isNullOrBlank()) {
        Row(
          verticalAlignment = Alignment.Top,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            tint = extendedColors.terracottaCookMode,
            modifier = Modifier.size(16.dp),
          )
          Text(
            text = "Sous-Chef Rule: $cleanRuleTip",
            style = MaterialTheme.typography.bodySmall,
            color = extendedColors.charcoalInk,
          )
        }
      }
    }
  }
}

// Inline Hands-Free Sous-Chef Q&A card supporting en-US & fr-FR voice/chip/text queries.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CookModeHandsFreeAiCard(
  sessionState: CookModeSessionState,
  onDispatchTranscriptOrQuestion: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Persist typed question draft across orientation changes while cooking.
  val questionDraftState = rememberSaveable(sessionState.recipe.recipeId) { mutableStateOf("") }

  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = KitchenTheme.shapes.card2xl,
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
    shadowElevation = 0.dp,
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      // Header row with calm Mic badge and live Listening / EN-FR language indicator.
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
          Box(
            modifier = Modifier
              .size(28.dp)
              .clip(RoundedCornerShape(8.dp))
              .background(extendedColors.sageContainer),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = Icons.Default.Mic,
              contentDescription = null,
              tint = extendedColors.onSageContainer,
              modifier = Modifier.size(16.dp),
            )
          }
          Text(
            text = "SOUS-CHEF Q&A",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = extendedColors.charcoalInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Surface(shape = CircleShape, color = extendedColors.sageContainer) {
          Text(
            text = if (sessionState.isHandsFreeVoiceActive) "Listening" else "EN / FR",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = extendedColors.onSageContainer,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
          )
        }
      }

      // 48.dp 1-tap culinary & French voice command chips ("When to flip?", "Étape suivante").
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        QUICK_COOK_QUESTIONS.forEach { samplePrompt ->
          AssistChip(
            onClick = { onDispatchTranscriptOrQuestion(samplePrompt) },
            shape = CircleShape,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            label = {
              Text(
                text = samplePrompt,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                softWrap = false,
              )
            },
          )
        }
      }

      // Display last recognized voice transcript or typed question in a warm parchment bubble.
      if (!sessionState.lastVoiceTranscript.isNullOrBlank()) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
          Surface(
            shape = KitchenTheme.shapes.userChatBubble,
            color = extendedColors.parchment,
            border = BorderStroke(1.dp, extendedColors.borderSubtle),
          ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
              Text(
                text = "YOU ASKED",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Text(
                text = "\"${sessionState.lastVoiceTranscript}\"",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
              )
            }
          }
        }
      }

      // Render grounded Sous-Chef answer bubble with calm "S" monogram avatar (zero sparkles).
      if (!sessionState.lastSousChefAnswer.isNullOrBlank()) {
        Row(
          verticalAlignment = Alignment.Top,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Box(
            modifier = Modifier
              .size(26.dp)
              .clip(CircleShape)
              .background(extendedColors.herbalSage),
            contentAlignment = Alignment.Center,
          ) {
            Text(text = "S", style = MaterialTheme.typography.labelSmall, color = Color.White)
          }
          Surface(
            shape = KitchenTheme.shapes.assistantChatBubble,
            color = extendedColors.sageContainer,
            border = BorderStroke(1.dp, extendedColors.herbalSage.copy(alpha = 0.25f)),
          ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
              Text(
                text = "SOUS-CHEF",
                style = MaterialTheme.typography.labelSmall,
                color = extendedColors.onSageContainer,
              )
              Text(
                text = sessionState.lastSousChefAnswer,
                style = MaterialTheme.typography.bodySmall,
                color = extendedColors.charcoalInk,
              )
            }
          }
        }
      }

      // Trim and dispatch non-empty Cook Mode questions via CookModeEngine.
      val submitDraft = {
        val clean = questionDraftState.value.trim()
        if (clean.isNotEmpty()) {
          onDispatchTranscriptOrQuestion(clean)
          questionDraftState.value = ""
        }
      }
      // Single-line text fallback when kitchen hood noise prevents hands-free speech capture.
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedTextField(
          value = questionDraftState.value,
          onValueChange = { questionDraftState.value = it },
          placeholder = {
            Text(
              text = "Ask Sous-Chef…",
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          },
          singleLine = true,
          keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Send,
          ),
          keyboardActions = KeyboardActions(onSend = { submitDraft() }),
          modifier = Modifier.weight(1f),
          shape = RoundedCornerShape(16.dp),
        )
        // 48.dp Ask pill submitting the typed culinary question.
        Button(
          onClick = submitDraft,
          shape = CircleShape,
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        ) {
          Text(text = "Ask", maxLines = 1, softWrap = false)
        }
      }
    }
  }
}

/**
 * Single unified `72.dp` docked messy-hands bottom bar (`[Pantry]`, `[Mic]`, `[<]`, and
 * `weight(1f)` `"Next Step →"` / `"Finish & Deduct ✓"`), replacing the double-stacked `220.dp` HUD.
 */
@Composable
internal fun CookModeBottomDock(
  sessionState: CookModeSessionState,
  onToggleHandsFreeMic: () -> Unit,
  onOpenPantrySheet: () -> Unit,
  onPreviousStep: () -> Unit,
  onNextOrComplete: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Highlight Mic button and live pulse dot whenever hands-free SpeechRecognizer is active.
  val isVoiceActive = sessionState.isHandsFreeVoiceActive

  // Flat ceramic dock with 1.dp top/outer border and 0.dp shadow so step timer stays crisp above.
  Surface(
    modifier = modifier.fillMaxWidth(),
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
    shadowElevation = 0.dp,
  ) {
    // Respect gesture navigation bar insets while guaranteeing >= 72.dp messy-hands height.
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .navigationBarsPadding()
        .defaultMinSize(minHeight = 72.dp)
        .padding(horizontal = 14.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      // 1. 48.dp Pantry / Mise-en-Place sheet button.
      FilledTonalIconButton(
        onClick = onOpenPantrySheet,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
          containerColor = extendedColors.parchment,
          contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = Modifier.size(48.dp),
      ) {
        Icon(
          imageVector = Icons.Default.Inventory2,
          contentDescription = "Open Step Ingredients",
          modifier = Modifier.size(20.dp),
        )
      }

      // 2. 48.dp Hands-Free Voice toggle with live emerald pulse dot.
      Box(contentAlignment = Alignment.TopEnd) {
        FilledTonalIconButton(
          onClick = onToggleHandsFreeMic,
          colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = if (isVoiceActive) extendedColors.sageContainer else extendedColors.parchment,
            contentColor = if (isVoiceActive) extendedColors.onSageContainer else MaterialTheme.colorScheme.onSurface,
          ),
          modifier = Modifier.size(48.dp),
        ) {
          Icon(
            imageVector = if (isVoiceActive) Icons.Default.Mic else Icons.Default.MicOff,
            contentDescription = "Toggle Hands-Free Voice",
            modifier = Modifier.size(20.dp),
          )
        }
        // Render 9.dp indicator dot only while microphone listening is active.
        if (isVoiceActive) {
          Box(
            modifier = Modifier
              .padding(3.dp)
              .size(9.dp)
              .clip(CircleShape)
              .background(extendedColors.freshIndicator),
          )
        }
      }

      // 3. 48.dp Previous-Step button (disabled cleanly on Step 1).
      OutlinedIconButton(
        onClick = onPreviousStep,
        enabled = !sessionState.isFirstStep,
        border = BorderStroke(1.dp, extendedColors.borderSubtle),
        modifier = Modifier.size(48.dp),
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
          contentDescription = "Previous step",
          modifier = Modifier.size(22.dp),
        )
      }

      // 4. Primary 52.dp forward CTA with non-truncating labels ("Next Step →" / "Finish & Deduct ✓").
      Button(
        onClick = onNextOrComplete,
        modifier = Modifier
          .weight(1f)
          .defaultMinSize(minHeight = 52.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
          containerColor = extendedColors.herbalSage,
          contentColor = Color.White,
        ),
      ) {
        // Concise labels fit comfortably at 1.35x S22 font scale without truncating "Pantry".
        val primaryLabel = if (sessionState.isFinalStep) "Finish & Deduct ✓" else "Next Step →"
        Text(
          text = primaryLabel,
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          softWrap = false,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}
