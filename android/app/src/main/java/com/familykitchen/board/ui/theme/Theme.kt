package com.familykitchen.board.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

// SavorAI Warm Culinary Dual-Layer Material 3 Theme:
// Combines Warm Linen / Ceramic / Soft Parchment M3 Light & Ambient Counter Dark schemes
// (#096443 primary, #2E7D5A primaryContainer, #FAF7F2..#E2DDD3 warm surfaces) with
// LocalExtendedKitchenColors & FamilyKitchenShapes.

// Disable Android 12+ wallpaper dynamic color extraction on purpose so SavorAI muted expiry tags,
// EC-08 Honey-Amber safety callouts, and 8:30 PM Warm Chamomile prep banners stay exact.
/**
 * CompositionLocal providing [ExtendedKitchenColors] (`SavorAI` warm linen, soft parchment, Herbal
 * Sage, Terracotta Clay, Warm Apricot, Expiry triads, Honey-Amber safety, and Evening Chamomile).
 */
val LocalExtendedKitchenColors: ProvidableCompositionLocal<ExtendedKitchenColors> =
  staticCompositionLocalOf { WarmDaylightExtendedColors }

/**
 * CompositionLocal providing [ExtendedKitchenShapes] (`8.dp`, `16.dp`, `24.dp`, `32.dp`,
 * asymmetric chat bubbles, and full pill shapes).
 */
val LocalExtendedKitchenShapes: ProvidableCompositionLocal<ExtendedKitchenShapes> =
  staticCompositionLocalOf { DefaultExtendedKitchenShapes }

// Warm Daylight M3 Tonal Scheme anchored on Warm Linen (#FAF7F2) and Crisp Ceramic (#FFFFFF).
/**
 * Material 3 `SavorAI` Warm Daylight color scheme calibrated for sunlit kitchen legibility.
 */
val WarmDaylightColorScheme: ColorScheme = lightColorScheme(
  primary = SavorPrimary,
  onPrimary = SavorOnPrimary,
  primaryContainer = SavorPrimaryContainer,
  onPrimaryContainer = SavorOnPrimaryContainer,
  inversePrimary = SavorInversePrimary,
  secondary = SavorSecondary,
  onSecondary = SavorOnSecondary,
  secondaryContainer = SavorSecondaryContainer,
  onSecondaryContainer = SavorOnSecondaryContainer,
  tertiary = SavorTertiary,
  onTertiary = SavorOnTertiary,
  tertiaryContainer = SavorTertiaryContainer,
  onTertiaryContainer = SavorOnTertiaryContainer,
  background = SavorSurface,
  onBackground = SavorOnSurface,
  surface = SavorSurface,
  onSurface = SavorOnSurface,
  surfaceVariant = SavorSurfaceContainerHighest,
  onSurfaceVariant = SavorOnSurfaceVariant,
  surfaceTint = SavorSurfaceTint,
  inverseSurface = SavorInverseSurface,
  inverseOnSurface = SavorInverseOnSurface,
  error = SavorError,
  onError = SavorOnError,
  errorContainer = SavorErrorContainer,
  onErrorContainer = SavorOnErrorContainer,
  outline = SavorOutline,
  outlineVariant = SavorOutlineVariant,
  surfaceBright = SavorSurfaceBright,
  surfaceDim = SavorSurfaceDim,
  surfaceContainerLowest = SavorSurfaceContainerLowest,
  surfaceContainerLow = SavorSurfaceContainerLow,
  surfaceContainer = SavorSurfaceContainer,
  surfaceContainerHigh = SavorSurfaceContainerHigh,
  surfaceContainerHighest = SavorSurfaceContainerHighest,
)

// Ambient Counter Dark scheme lifting elevation across warm botanical-espresso OLED tiers.
/**
 * Material 3 `SavorAI` Ambient Counter Dark color scheme for 8:30 PM evening prep on OLED panels.
 */
val AmbientCounterDarkColorScheme: ColorScheme = darkColorScheme(
  primary = SagePrimaryDark,
  onPrimary = OnSagePrimaryDark,
  primaryContainer = SavorPrimaryContainer,
  onPrimaryContainer = SavorOnPrimaryContainer,
  inversePrimary = SavorPrimary,
  secondary = TerracottaAccentDark,
  onSecondary = OnTerracottaAccentDark,
  secondaryContainer = TerracottaContainerDark,
  onSecondaryContainer = OnTerracottaContainerDark,
  tertiary = SavorTertiaryFixedDim,
  onTertiary = SavorOnTertiaryFixed,
  tertiaryContainer = HoneyAmberSafetyContainerDark,
  onTertiaryContainer = SavorTertiaryFixed,
  background = CounterDarkBackground,
  onBackground = InkPrimaryDark,
  surface = CounterDarkBackground,
  onSurface = InkPrimaryDark,
  surfaceVariant = CounterDarkContainerHigh,
  onSurfaceVariant = InkSecondaryDark,
  surfaceTint = SagePrimaryDark,
  inverseSurface = SavorSurfaceContainerLow,
  inverseOnSurface = SavorOnSurface,
  error = ErrorShortageDark,
  onError = ErrorShortageContainerDark,
  errorContainer = ErrorShortageContainerDark,
  onErrorContainer = SavorErrorContainer,
  outline = CounterDarkOutline,
  outlineVariant = CounterDarkOutline,
  surfaceBright = CounterDarkContainerHigh,
  surfaceDim = CounterDarkBackground,
  surfaceContainerLowest = CounterDarkBackground,
  surfaceContainerLow = CounterDarkCanvas,
  surfaceContainer = CounterDarkCardSurface,
  surfaceContainerHigh = CounterDarkContainerHigh,
  surfaceContainerHighest = CounterDarkContainerHigh,
)

/**
 * Ergonomic accessor for reading [ExtendedKitchenColors] and [ExtendedKitchenShapes] inside
 * `@Composable` scopes.
 */
object KitchenTheme {
  /** Returns the active [ExtendedKitchenColors] for the current theme hierarchy. */
  val extendedColors: ExtendedKitchenColors
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedKitchenColors.current

  /** Returns the active [ExtendedKitchenShapes] for the current theme hierarchy. */
  val shapes: ExtendedKitchenShapes
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedKitchenShapes.current
}

/**
 * Root `SavorAI` Material 3 theme wrapper for Family Kitchen Board.
 *
 * Supplies Material 3 [ColorScheme], [FamilyKitchenTypography] (`Newsreader` serif + `Plus Jakarta
 * Sans` tabular figures), [FamilyKitchenShapes], and [LocalExtendedKitchenColors] without dynamic
 * wallpaper overrides so expiry badges, `EC-08` safety callouts, and `EC-01` evening-prep cards
 * retain consistent culinary contrast.
 *
 * @param darkTheme Selects [AmbientCounterDarkColorScheme] when `true`, or [WarmDaylightColorScheme] when `false`.
 * @param content Composable UI tree rendered inside the theme.
 */
@Composable
fun FamilyKitchenBoardTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  // Select matching Material 3 and extended SavorAI semantic color tokens deterministically.
  val colorScheme = when {
    darkTheme -> AmbientCounterDarkColorScheme
    else -> WarmDaylightColorScheme
  }
  val extendedColors = when {
    darkTheme -> AmbientCounterDarkExtendedColors
    else -> WarmDaylightExtendedColors
  }

  CompositionLocalProvider(
    LocalExtendedKitchenColors provides extendedColors,
    LocalExtendedKitchenShapes provides DefaultExtendedKitchenShapes,
  ) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = FamilyKitchenTypography,
      shapes = FamilyKitchenShapes,
      content = content,
    )
  }
}
