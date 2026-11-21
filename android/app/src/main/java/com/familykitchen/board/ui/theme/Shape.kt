package com.familykitchen.board.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp

// Tactile ceramic-inspired SavorAI corner radii (.agents/DESIGN.md: 8.dp, 16.dp, 24.dp, 32.dp, pill).

/**
 * Material 3 [Shapes] hierarchy mapping `.agents/DESIGN.md` radii (`8.dp`, `16.dp`, `24.dp`, `32.dp`).
 */
val FamilyKitchenShapes: Shapes = Shapes(
  // Small badges and inline checkboxes (0.5rem = 8.dp).
  extraSmall = RoundedCornerShape(8.dp),
  // Compact chips and secondary inputs (12.dp).
  small = RoundedCornerShape(12.dp),
  // Base inputs and compact action cards (1.0rem = 16.dp).
  medium = RoundedCornerShape(16.dp),
  // Standard pantry cards, recipe summaries, and suggestion containers (1.5rem = 24.dp).
  large = RoundedCornerShape(24.dp),
  // Hero visuals, Pantry stat pulse bar, Cook Mode cards, and modal bottom sheets (2.0rem = 32.dp).
  extraLarge = RoundedCornerShape(32.dp),
)

/**
 * Semantic `SavorAI` shape tokens for asymmetric chat bubbles, hero containers, and pill controls.
 *
 * @property badgeSm `8.dp` capsule corner for compact status and quantity tags.
 * @property inputDefault `16.dp` corner for text fields and nested sub-cards.
 * @property card2xl `24.dp` corner for standard pantry, grocery, and recipe cards.
 * @property hero3xl `32.dp` corner for Cook Mode cards, Pantry pulse header, and bottom sheets.
 * @property userChatBubble Asymmetric trailing chat bubble (`topEnd = 4.dp`, others `24.dp`).
 * @property assistantChatBubble Asymmetric leading AI bubble (`topStart = 4.dp`, others `24.dp`).
 * @property bottomSheetTop Top-only `32.dp` rounded sheet silhouette.
 * @property pillFull Fully circular pill (`CircleShape`) for CTAs, filter chips, and input bar.
 */
@Immutable
data class ExtendedKitchenShapes(
  val badgeSm: CornerBasedShape = RoundedCornerShape(8.dp),
  val inputDefault: CornerBasedShape = RoundedCornerShape(16.dp),
  val card2xl: CornerBasedShape = RoundedCornerShape(24.dp),
  val hero3xl: CornerBasedShape = RoundedCornerShape(32.dp),
  val userChatBubble: CornerBasedShape = RoundedCornerShape(
    topStart = 24.dp,
    topEnd = 4.dp,
    bottomEnd = 24.dp,
    bottomStart = 24.dp,
  ),
  val assistantChatBubble: CornerBasedShape = RoundedCornerShape(
    topStart = 4.dp,
    topEnd = 24.dp,
    bottomEnd = 24.dp,
    bottomStart = 24.dp,
  ),
  val bottomSheetTop: CornerBasedShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
  val pillFull: CornerBasedShape = CircleShape,
)

/** Canonical `SavorAI` shape presets shared across all tabs and modal sheets. */
val DefaultExtendedKitchenShapes: ExtendedKitchenShapes = ExtendedKitchenShapes()
