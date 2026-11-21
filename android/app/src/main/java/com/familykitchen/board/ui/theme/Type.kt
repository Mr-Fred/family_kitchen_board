package com.familykitchen.board.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// SavorAI 100% offline typography scale (.agents/DESIGN.md):
// - Editorial Serif ("Newsreader" scale via FontFamily.Serif — zero network downloadable fonts!)
// - Interface Sans ("Plus Jakarta Sans" scale via FontFamily.SansSerif + "tnum" tabular figures).

/** Offline editorial serif family representing `Newsreader` for recipe titles and chef notes. */
val EditorialSerifFontFamily: FontFamily = FontFamily.Serif

/** Offline geometric interface sans family representing `Plus Jakarta Sans` for UI & metrics. */
val InterfaceSansFontFamily: FontFamily = FontFamily.SansSerif

/**
 * Editorial italic hero style (`Newsreader` Medium Italic, `32.sp`) used on Clean Recipe Reader
 * hero dish titles and SavorAI welcome headers to convey cookbook artistry.
 */
val EditorialHeroItalicTextStyle: TextStyle = TextStyle(
  fontFamily = EditorialSerifFontFamily,
  fontWeight = FontWeight.Medium,
  fontStyle = FontStyle.Italic,
  fontSize = 32.sp,
  lineHeight = 38.sp,
  letterSpacing = (-0.48).sp,
)

/**
 * Dedicated tabular-numeral (`tnum`) display style ensuring Cook Mode and Morning Pacer
 * `MM:SS` countdown rings (`03:42`) never jitter horizontally while seconds decrement.
 */
val TabularTimerTextStyle: TextStyle = TextStyle(
  fontFamily = InterfaceSansFontFamily,
  fontWeight = FontWeight.Black,
  fontSize = 44.sp,
  lineHeight = 50.sp,
  letterSpacing = (-0.5).sp,
  fontFeatureSettings = "tnum",
)

/**
 * Material 3 [Typography] scale implementing `.agents/DESIGN.md` (`headline-xl` through `label-sm`).
 *
 * Pairs [EditorialSerifFontFamily] at display/headline scale with [InterfaceSansFontFamily] and
 * `fontFeatureSettings = "tnum"` across titles, nutrition metrics, prep durations, and labels.
 */
val FamilyKitchenTypography: Typography = Typography(
  // headline-xl (2.5rem / 40.sp, Newsreader Medium 500, -0.02em) for tablet hero headers.
  displayLarge = TextStyle(
    fontFamily = EditorialSerifFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 40.sp,
    lineHeight = 46.sp,
    letterSpacing = (-0.8).sp,
  ),
  // High-contrast tabular display for large kitchen metric counters.
  displayMedium = TextStyle(
    fontFamily = InterfaceSansFontFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 38.sp,
    lineHeight = 44.sp,
    fontFeatureSettings = "tnum",
  ),
  // headline-xl-mobile (2.0rem / 32.sp, Newsreader Medium 500, -0.015em) for mobile hero titles.
  displaySmall = TextStyle(
    fontFamily = EditorialSerifFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 32.sp,
    lineHeight = 38.sp,
    letterSpacing = (-0.48).sp,
  ),
  // headline-lg (1.75rem / 28.sp, Newsreader Medium/SemiBold, -0.01em) for Cook Mode step titles.
  headlineLarge = TextStyle(
    fontFamily = EditorialSerifFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 28.sp,
    lineHeight = 34.sp,
    letterSpacing = (-0.28).sp,
  ),
  // Editorial section headers ("Action Required", "Monday Menu").
  headlineMedium = TextStyle(
    fontFamily = EditorialSerifFontFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 24.sp,
    lineHeight = 30.sp,
    letterSpacing = (-0.24).sp,
  ),
  // Recipe card titles and step instruction callouts.
  headlineSmall = TextStyle(
    fontFamily = EditorialSerifFontFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 20.sp,
    lineHeight = 26.sp,
    letterSpacing = (-0.2).sp,
  ),
  // headline-sm (1.25rem / 20.sp, Plus Jakarta Sans Bold 700, tnum) for "SavorAI" & stat counters.
  titleLarge = TextStyle(
    fontFamily = InterfaceSansFontFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 20.sp,
    lineHeight = 26.sp,
    letterSpacing = (-0.2).sp,
    fontFeatureSettings = "tnum",
  ),
  // Pantry item names, modal titles, and card subheadings.
  titleMedium = TextStyle(
    fontFamily = InterfaceSansFontFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 16.sp,
    lineHeight = 22.sp,
    letterSpacing = 0.1.sp,
    fontFeatureSettings = "tnum",
  ),
  // Compact card titles and group headers.
  titleSmall = TextStyle(
    fontFamily = InterfaceSansFontFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 14.sp,
    lineHeight = 20.sp,
    letterSpacing = 0.1.sp,
    fontFeatureSettings = "tnum",
  ),
  // body-lg (1.0625rem / 17.sp, Plus Jakarta Sans 400, -0.005em) for Cook Mode & Reader prose.
  bodyLarge = TextStyle(
    fontFamily = InterfaceSansFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 17.sp,
    lineHeight = 26.sp,
    letterSpacing = (-0.08).sp,
  ),
  // body-md (0.9375rem / 15.sp, Plus Jakarta Sans 400) for chat turns, steps, and BOM lists.
  bodyMedium = TextStyle(
    fontFamily = InterfaceSansFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 15.sp,
    lineHeight = 23.sp,
  ),
  // body-sm (0.8125rem / 13.sp, Plus Jakarta Sans 400, tnum) for storage notes and nutrition specs.
  bodySmall = TextStyle(
    fontFamily = InterfaceSansFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 19.sp,
    fontFeatureSettings = "tnum",
  ),
  // label-lg (0.875rem / 14.sp, SemiBold 600, 0.01em, tnum) for primary CTAs and aisle headers.
  labelLarge = TextStyle(
    fontFamily = InterfaceSansFontFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 14.sp,
    lineHeight = 18.sp,
    letterSpacing = 0.14.sp,
    fontFeatureSettings = "tnum",
  ),
  // label-md (0.75rem / 12.sp, SemiBold 600, 0.02em, tnum) for filter chips & match counts.
  labelMedium = TextStyle(
    fontFamily = InterfaceSansFontFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.24.sp,
    fontFeatureSettings = "tnum",
  ),
  // label-sm (0.6875rem / 11.sp, Bold 700, 0.04em, tnum) for expiry capsule tags & bottom nav.
  labelSmall = TextStyle(
    fontFamily = InterfaceSansFontFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 11.sp,
    lineHeight = 14.sp,
    letterSpacing = 0.44.sp,
    fontFeatureSettings = "tnum",
  ),
)
