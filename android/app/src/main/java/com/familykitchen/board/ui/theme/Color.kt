package com.familykitchen.board.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// SavorAI Warm Culinary Dual-Layer Color System:
// 1. M3 Warm Tonal Scheme (#096443 primary, #2E7D5A Herbal Sage primaryContainer, #FAF7F2..#E2DDD3).
// 2. Organic Linen/Parchment, Muted Expiry Triad, EC-08 Honey-Amber Safety, & Evening Chamomile.

// --- Layer 1: SavorAI Material 3 Warm Tonal Light Palette ---

// Deep botanical emerald anchoring M3 primary text buttons, active nav icons, and brand logotype.
/** Primary M3 botanical green (`#096443`). */
val SavorPrimary: Color = Color(0xFF096443)

/** Crisp white ink rendered atop [SavorPrimary]. */
val SavorOnPrimary: Color = Color(0xFFFFFFFF)

/** Herbal Sage primary container (`#2E7D5A`) anchoring primary pill CTAs and high-confidence badges. */
val SavorPrimaryContainer: Color = Color(0xFF2E7D5A)

/** Luminous mint foreground rendered inside [SavorPrimaryContainer]. */
val SavorOnPrimaryContainer: Color = Color(0xFFD0FFE2)

/** Inverse mint primary accent (`#88D7AD`) for dark snackbars, voice HUDs, and counter dark mode. */
val SavorInversePrimary: Color = Color(0xFF88D7AD)

/** Fixed mint highlight (`#A4F3C8`) used on culinary capability pills. */
val SavorPrimaryFixed: Color = Color(0xFFA4F3C8)

/** Dimmed fixed mint (`#88D7AD`) for secondary botanical accents. */
val SavorPrimaryFixedDim: Color = Color(0xFF88D7AD)

/** Deep forest ink (`#002113`) paired with [SavorPrimaryFixed]. */
val SavorOnPrimaryFixed: Color = Color(0xFF002113)

/** Variant forest ink (`#005235`) paired with [SavorPrimaryFixed] subtitles. */
val SavorOnPrimaryFixedVariant: Color = Color(0xFF005235)

// Terracotta & coral secondary tokens driving active timers, culinary step numbers, and badges.
/** Secondary roasted terracotta (`#A13E28`). */
val SavorSecondary: Color = Color(0xFFA13E28)

/** White foreground ink rendered atop [SavorSecondary]. */
val SavorOnSecondary: Color = Color(0xFFFFFFFF)

/** Coral-terracotta secondary container (`#FD8367`) for active timers and culinary badges. */
val SavorSecondaryContainer: Color = Color(0xFFFD8367)

/** Deep clay ink (`#721C09`) paired with [SavorSecondaryContainer]. */
val SavorOnSecondaryContainer: Color = Color(0xFF721C09)

/** Soft peach fixed container (`#FFDAD2`) for culinary tags. */
val SavorSecondaryFixed: Color = Color(0xFFFFDAD2)

/** Warm coral fixed dim (`#FFB4A3`) used as secondary accent in counter dark mode. */
val SavorSecondaryFixedDim: Color = Color(0xFFFFB4A3)

/** Dark mahogany ink (`#3D0600`) paired with [SavorSecondaryFixed]. */
val SavorOnSecondaryFixed: Color = Color(0xFF3D0600)

/** Roasted sienna ink (`#822714`) for secondary fixed labels. */
val SavorOnSecondaryFixedVariant: Color = Color(0xFF822714)

// Warm amber/apricot tertiary tokens for chef pairing notes and 48-72h expiry alerts.
/** Tertiary toasted amber (`#834800`). */
val SavorTertiary: Color = Color(0xFF834800)

/** White foreground ink rendered atop [SavorTertiary]. */
val SavorOnTertiary: Color = Color(0xFFFFFFFF)

/** Rich amber tertiary container (`#A65D00`). */
val SavorTertiaryContainer: Color = Color(0xFFA65D00)

/** Warm cream foreground (`#FFF1E9`) paired with [SavorTertiaryContainer]. */
val SavorOnTertiaryContainer: Color = Color(0xFFFFF1E9)

/** Apricot-cream fixed container (`#FFDCC1`) for expiring-soon pills and rescued value badges. */
val SavorTertiaryFixed: Color = Color(0xFFFFDCC1)

/** Golden apricot fixed dim (`#FFB877`) for dark-mode tertiary highlights. */
val SavorTertiaryFixedDim: Color = Color(0xFFFFB877)

/** Deep espresso-amber ink (`#2E1600`) inside [SavorTertiaryFixed]. */
val SavorOnTertiaryFixed: Color = Color(0xFF2E1600)

/** Warm bronze ink (`#6C3A00`) for tertiary fixed metadata. */
val SavorOnTertiaryFixedVariant: Color = Color(0xFF6C3A00)

// Error & urgent spoilage tokens.
/** Urgent crimson error (`#BA1A1A`) for expired items and validation blockers. */
val SavorError: Color = Color(0xFFBA1A1A)

/** White foreground ink atop [SavorError]. */
val SavorOnError: Color = Color(0xFFFFFFFF)

/** Soft blush error container (`#FFDAD6`) for <= 24h urgent spoilage tags. */
val SavorErrorContainer: Color = Color(0xFFFFDAD6)

/** Deep burgundy ink (`#93000A`) inside [SavorErrorContainer]. */
val SavorOnErrorContainer: Color = Color(0xFF93000A)

// Layered warm linen & ceramic M3 surface hierarchy (#FAF7F2 -> #FFFFFF -> #F5F2EB -> #EFECE6 -> #E8E3D9 -> #E2DDD3).
/** Base M3 warm linen app surface (`#FAF7F2`). */
val SavorSurface: Color = Color(0xFFFAF7F2)

/** Warm stone divider and dimmed surface backdrop (`#E2DDD3`). */
val SavorSurfaceDim: Color = Color(0xFFE2DDD3)

/** Sunlit warm linen bright surface (`#FAF7F2`). */
val SavorSurfaceBright: Color = Color(0xFFFAF7F2)

/** Crisp ceramic card surface (`#FFFFFF`) for interactive pantry, recipe, and meal slot cards. */
val SavorSurfaceContainerLowest: Color = Color(0xFFFFFFFF)

/** Soft parchment container (`#F5F2EB`) for grouped sections and structured AI artifacts. */
val SavorSurfaceContainerLow: Color = Color(0xFFF5F2EB)

/** Warm stone mid container (`#EFECE6`) for segmented control tracks, macro bars, and icon badges. */
val SavorSurfaceContainer: Color = Color(0xFFEFECE6)

/** Tactile warm chip container (`#E8E3D9`) for ingredient chips and secondary pill controls. */
val SavorSurfaceContainerHigh: Color = Color(0xFFE8E3D9)

/** Warm border & highest surface variant (`#E2DDD3`). */
val SavorSurfaceContainerHighest: Color = Color(0xFFE2DDD3)

/** Primary M3 warm espresso-charcoal ink (`#1C1917`). */
val SavorOnSurface: Color = Color(0xFF1C1917)

/** Botanical slate muted ink (`#3F4943`). */
val SavorOnSurfaceVariant: Color = Color(0xFF3F4943)

/** Structural M3 outline (`#6F7A72`). */
val SavorOutline: Color = Color(0xFF6F7A72)

/** Subtle warm stone outline variant (`#E2DDD3`). */
val SavorOutlineVariant: Color = Color(0xFFE2DDD3)

/** Deep roasted-charcoal inverse surface (`#26231F`) for user chat bubbles and status strips. */
val SavorInverseSurface: Color = Color(0xFF26231F)

/** Warm linen-white ink (`#FAF7F2`) rendered atop [SavorInverseSurface]. */
val SavorInverseOnSurface: Color = Color(0xFFFAF7F2)

/** Botanical emerald M3 surface tint (`#176B4A`). */
val SavorSurfaceTint: Color = Color(0xFF176B4A)

// --- Layer 2: SavorAI Organic Parchment, Brand Prose & Muted Expiry Semantic Tokens ---

/** Glare-free warm linen canvas (`#FAF7F2`) eliminating clinical blue screen cast. */
val CanvasWarmLight: Color = Color(0xFFFAF7F2)

/** Soft parchment grouped container (`#F5F2EB`) for LLM recipe cards and step trackers. */
val ParchmentSurfaceLight: Color = Color(0xFFF5F2EB)

/** Herbal Sage primary button fill (`#2E7D5A`). */
val HerbalSage: Color = Color(0xFF2E7D5A)

/** Pressed Herbal Sage button fill (`#246347`). */
val HerbalSagePressed: Color = Color(0xFF246347)

/** Soft botanical sage pill background (`#EAF4EE`). */
val SageLightSurface: Color = Color(0xFFEAF4EE)

/** Terracotta Clay culinary action accent (`#E06D53`). */
val TerracottaClay: Color = Color(0xFFE06D53)

/** Cook Mode roasted terracotta (`#C85A32`) for step progress and countdown rings. */
val TerracottaCookMode: Color = Color(0xFFC85A32)

/** Soft terracotta tint (`#FDF1EB`) for active kitchen timer cards. */
val TerracottaLightSurface: Color = Color(0xFFFDF1EB)

/** Warm Apricot highlight (`#F48C06`) for proactive culinary tips and flavor pairings. */
val WarmApricot: Color = Color(0xFFF48C06)

/** Deep warm charcoal prose heading ink (`#1C1917`). */
val CharcoalInk: Color = Color(0xFF1C1917)

/** Warm slate secondary metadata ink (`#57534E`). */
val SlateMutedInk: Color = Color(0xFF57534E)

/** Muted ash placeholder & timestamp ink (`#78716C`). */
val AshMutedInk: Color = Color(0xFF78716C)

/** Crisp `1.dp` warm stone card border (`#E2DDD3`) for sunlit kitchen legibility. */
val BorderSubtleLight: Color = Color(0xFFE2DDD3)

// Pantry shelf-life & expiry semantic triad using calm muted botanical, amber, and terracotta.
/** Fresh shelf-life leading-edge indicator (`#2E7D5A`). */
val FreshIndicator: Color = Color(0xFF2E7D5A)

/** Fresh capsule tag background (`#EAF4EE`). */
val FreshTagContainerLight: Color = Color(0xFFEAF4EE)

/** Fresh capsule tag foreground (`#096443`). */
val OnFreshTagLight: Color = Color(0xFF096443)

/** Expiring-soon (48-72h) muted amber indicator (`#B45309`). */
val ExpiringSoonIndicator: Color = Color(0xFFB45309)

/** Expiring-soon capsule tag background (`#FFFBEB`). */
val ExpiringSoonTagContainerLight: Color = Color(0xFFFFFBEB)

/** Expiring-soon capsule tag foreground (`#B45309`). */
val OnExpiringSoonTagLight: Color = Color(0xFFB45309)

/** Expired / urgent (`<= 24h`) muted terracotta indicator (`#C85A32`). */
val ExpiredIndicator: Color = Color(0xFFC85A32)

/** Expired capsule tag background (`#FDF1EB`). */
val ExpiredTagContainerLight: Color = Color(0xFFFDF1EB)

/** Expired capsule tag foreground (`#9A3412`). */
val OnExpiredTagLight: Color = Color(0xFF9A3412)

// --- Preserved Family-of-4 Warm Daylight & Ambient Counter Dark Tokens (Backwards-Compatible) ---

/** Warm linen page background (`#FAF7F2`) preserved for existing board/pacer callers. */
val LinenBackground: Color = Color(0xFFFAF7F2)

/** Secondary warm stone canvas surface (`#EFECE6`). */
val CanvasSurface: Color = Color(0xFFEFECE6)

/** Crisp ceramic daylight card surface (`#FFFFFF`). */
val PureCardSurface: Color = Color(0xFFFFFFFF)

/** Subtle warm stone card border (`#E2DDD3`). */
val CardOutlineLight: Color = Color(0xFFE2DDD3)

/** Herbal sage brand accent (`#2D6A4F`). */
val SagePrimary: Color = Color(0xFF2D6A4F)

/** White foreground ink atop [SagePrimary]. */
val OnSagePrimary: Color = Color(0xFFFFFFFF)

/** Soft sage container tint (`#E8F3EE`). */
val SageContainerLight: Color = Color(0xFFE8F3EE)

/** Deep forest ink inside [SageContainerLight]. */
val OnSageContainerLight: Color = Color(0xFF143627)

/** Culinary terracotta accent (`#B84A24`). */
val TerracottaAccent: Color = Color(0xFFB84A24)

/** White foreground ink atop [TerracottaAccent]. */
val OnTerracottaAccent: Color = Color(0xFFFFFFFF)

/** Soft terracotta container (`#FDF0EB`). */
val TerracottaContainerLight: Color = Color(0xFFFDF0EB)

/** Deep clay text color paired with [TerracottaContainerLight]. */
val OnTerracottaContainerLight: Color = Color(0xFF4A1B0B)

/** Honey-amber container (`#FEF3C7`) dedicated to `EC-08` child choking-safety callouts. */
val HoneyAmberSafetyContainerLight: Color = Color(0xFFFEF3C7)

/** Honey-amber border (`#F59E0B`) framing `EC-08` toddler/baby prep callouts. */
val HoneyAmberSafetyBorderLight: Color = Color(0xFFF59E0B)

/** High-contrast warm umber ink inside [HoneyAmberSafetyContainerLight]. */
val OnHoneyAmberSafetyLight: Color = Color(0xFF78350F)

/** Warm evening chamomile container (`#F6F1E7`) for the 8:30 PM Night-Before 5-Min Prep card. */
val IndigoNightPrepContainerLight: Color = Color(0xFFF6F1E7)

/** Warm evening bronze border (`#B08968`) framing 8:30 PM prep reminders without purple cast. */
val IndigoNightPrepBorderLight: Color = Color(0xFFB08968)

/** Deep roasted espresso ink (`#3E2C1C`) rendered inside [IndigoNightPrepContainerLight]. */
val OnIndigoNightPrepLight: Color = Color(0xFF3E2C1C)

/** Botanical green container indicating `COOKED` or completed prep stations. */
val CookedSuccessContainerLight: Color = Color(0xFFE6F4EA)

/** Crisp dark green ink inside [CookedSuccessContainerLight]. */
val OnCookedSuccessLight: Color = Color(0xFF1B5E20)

// Ambient Counter Dark palette tokens preserving warm culinary hierarchy on OLED displays.
/** Deep espresso-pine background (`#121715`) for low-glare evening counter mode. */
val CounterDarkBackground: Color = Color(0xFF121715)

/** Elevated dark canvas surface (`#1B221F`) for grouped strips and parchment cards in dark mode. */
val CounterDarkCanvas: Color = Color(0xFF1B221F)

/** Elevated card surface (`#222A26`) in Ambient Counter Dark mode. */
val CounterDarkCardSurface: Color = Color(0xFF222A26)

/** Higher dark container (`#2B3530`) for chips and modals. */
val CounterDarkContainerHigh: Color = Color(0xFF2B3530)

/** Warm dark botanical-stone border (`#323D37`) separating cards cleanly on OLED displays. */
val CounterDarkOutline: Color = Color(0xFF323D37)

/** Luminous mint-sage primary accent (`#88D7AD`) for dark surfaces. */
val SagePrimaryDark: Color = Color(0xFF88D7AD)

/** Deep forest ink (`#002113`) rendered atop [SagePrimaryDark]. */
val OnSagePrimaryDark: Color = Color(0xFF002113)

/** Muted forest container (`#1E4634`) for active chips in dark mode. */
val SageContainerDark: Color = Color(0xFF1E4634)

/** Soft mint foreground (`#D0FFE2`) inside [SageContainerDark]. */
val OnSageContainerDark: Color = Color(0xFFD0FFE2)

/** Warm coral-terracotta accent (`#FFB4A3`) legible against dark counter surfaces. */
val TerracottaAccentDark: Color = Color(0xFFFFB4A3)

/** Dark roasted-clay ink (`#471807`) atop [TerracottaAccentDark]. */
val OnTerracottaAccentDark: Color = Color(0xFF471807)

/** Deep terracotta container (`#5C2410`) for active timers and boosts in dark mode. */
val TerracottaContainerDark: Color = Color(0xFF5C2410)

/** Soft peach foreground (`#FFDAD2`) paired with [TerracottaContainerDark]. */
val OnTerracottaContainerDark: Color = Color(0xFFFFDAD2)

/** Warm parchment-white primary text (`#F5F2EB`) for Ambient Counter Dark mode. */
val InkPrimaryDark: Color = Color(0xFFF5F2EB)

/** Muted sage-stone secondary text (`#BEC9C0`) for Ambient Counter Dark mode. */
val InkSecondaryDark: Color = Color(0xFFBEC9C0)

/** Dark warm-amber surface keeping `EC-08` child safety callouts unmistakable at night. */
val HoneyAmberSafetyContainerDark: Color = Color(0xFF3B2A0A)

/** Luminous amber border (`#FBBF24`) framing `EC-08` child safety callouts in dark mode. */
val HoneyAmberSafetyBorderDark: Color = Color(0xFFFBBF24)

/** Pale honey foreground (`#FEF3C7`) inside [HoneyAmberSafetyContainerDark]. */
val OnHoneyAmberSafetyDark: Color = Color(0xFFFEF3C7)

/** Deep warm chamomile-umber container (`#26211B`) highlighting the 8:30 PM Night-Before Prep card. */
val IndigoNightPrepContainerDark: Color = Color(0xFF26211B)

/** Luminous warm bronze border (`#C89F78`) framing the 8:30 PM prep card in dark mode. */
val IndigoNightPrepBorderDark: Color = Color(0xFFC89F78)

/** Soft warm cream text (`#F5EBE0`) inside [IndigoNightPrepContainerDark]. */
val OnIndigoNightPrepDark: Color = Color(0xFFF5EBE0)

/** Dark emerald container (`#133827`) for completed meal and Fresh expiry tags. */
val CookedSuccessContainerDark: Color = Color(0xFF133827)

/** Mint-emerald foreground (`#A4F3C8`) for Fresh and Cooked tags in dark mode. */
val OnCookedSuccessDark: Color = Color(0xFFA4F3C8)

/** Soft coral error accent (`#FFB4AB`) for dark-mode stock shortages and Urgent expiry. */
val ErrorShortageDark: Color = Color(0xFFFFB4AB)

/** Dark crimson container (`#690005`) for Urgent/Expired warnings in dark mode. */
val ErrorShortageContainerDark: Color = Color(0xFF690005)

/**
 * Dual-layer semantic color tokens extending Material 3 `ColorScheme` with `SavorAI` warm linen,
 * parchment, Herbal Sage / Terracotta Clay / Warm Apricot brand accents, Muted Pantry Expiry triads
 * (`FRESH`, `EXPIRING_SOON`, `EXPIRED`), M3 fixed tonal roles, `EC-08` Honey-Amber child safety,
 * and `EC-01` Warm Evening Chamomile/Bronze 8:30 PM night-before prep tokens.
 *
 * Preserves 100% of existing property names (`linen`..`onTertiaryFixedVariant`) so every screen
 * and modal sheet across the codebase compiles cleanly.
 */
@Immutable
data class ExtendedKitchenColors(
  val linen: Color,
  val canvas: Color,
  val cardSurface: Color,
  val sage: Color,
  val sageContainer: Color,
  val onSageContainer: Color,
  val terracotta: Color,
  val terracottaContainer: Color,
  val onTerracottaContainer: Color,
  val childSafetyContainer: Color,
  val childSafetyBorder: Color,
  val onChildSafety: Color,
  val nightPrepContainer: Color,
  val nightPrepBorder: Color,
  val onNightPrep: Color,
  val cookedSuccessContainer: Color,
  val onCookedSuccess: Color,
  // SavorAI warm linen, soft parchment, brand, & subtle stone border tokens
  val canvasWarm: Color = CanvasWarmLight,
  val parchment: Color = ParchmentSurfaceLight,
  val herbalSage: Color = HerbalSage,
  val herbalSagePressed: Color = HerbalSagePressed,
  val sageLight: Color = SageLightSurface,
  val terracottaClay: Color = TerracottaClay,
  val terracottaCookMode: Color = TerracottaCookMode,
  val terracottaLight: Color = TerracottaLightSurface,
  val warmApricot: Color = WarmApricot,
  val charcoalInk: Color = CharcoalInk,
  val slateMuted: Color = SlateMutedInk,
  val ashMuted: Color = AshMutedInk,
  val borderSubtle: Color = BorderSubtleLight,
  // Pantry shelf-life & muted expiry triad (FRESH / EXPIRING_SOON / EXPIRED)
  val freshIndicator: Color = FreshIndicator,
  val freshContainer: Color = FreshTagContainerLight,
  val onFreshContainer: Color = OnFreshTagLight,
  val expiringSoonIndicator: Color = ExpiringSoonIndicator,
  val expiringSoonContainer: Color = ExpiringSoonTagContainerLight,
  val onExpiringSoonContainer: Color = OnExpiringSoonTagLight,
  val expiredIndicator: Color = ExpiredIndicator,
  val expiredContainer: Color = ExpiredTagContainerLight,
  val onExpiredContainer: Color = OnExpiredTagLight,
  // M3 fixed tonal tokens
  val primaryFixed: Color = SavorPrimaryFixed,
  val primaryFixedDim: Color = SavorPrimaryFixedDim,
  val onPrimaryFixed: Color = SavorOnPrimaryFixed,
  val onPrimaryFixedVariant: Color = SavorOnPrimaryFixedVariant,
  val secondaryFixed: Color = SavorSecondaryFixed,
  val secondaryFixedDim: Color = SavorSecondaryFixedDim,
  val onSecondaryFixed: Color = SavorOnSecondaryFixed,
  val onSecondaryFixedVariant: Color = SavorOnSecondaryFixedVariant,
  val tertiaryFixed: Color = SavorTertiaryFixed,
  val tertiaryFixedDim: Color = SavorTertiaryFixedDim,
  val onTertiaryFixed: Color = SavorOnTertiaryFixed,
  val onTertiaryFixedVariant: Color = SavorOnTertiaryFixedVariant,
)

/** Default Warm Daylight `SavorAI` semantic tokens anchored in Warm Linen and Soft Parchment. */
val WarmDaylightExtendedColors: ExtendedKitchenColors = ExtendedKitchenColors(
  // Base warm linen, stone canvas, and ceramic card tiers for daylight kitchen surfaces.
  linen = LinenBackground,
  canvas = CanvasSurface,
  cardSurface = SavorSurfaceContainerLowest,
  // Botanical Herbal Sage and Roasted Terracotta brand accents.
  sage = HerbalSage,
  sageContainer = SageLightSurface,
  onSageContainer = SavorPrimary,
  terracotta = TerracottaCookMode,
  terracottaContainer = TerracottaLightSurface,
  onTerracottaContainer = SavorOnSecondaryContainer,
  // Dedicated EC-08 child choking-safety honey-amber callout colors.
  childSafetyContainer = HoneyAmberSafetyContainerLight,
  childSafetyBorder = HoneyAmberSafetyBorderLight,
  onChildSafety = OnHoneyAmberSafetyLight,
  // Warm Evening Chamomile & Bronze 8:30 PM prep tokens replacing legacy indigo/purple.
  nightPrepContainer = IndigoNightPrepContainerLight,
  nightPrepBorder = IndigoNightPrepBorderLight,
  onNightPrep = OnIndigoNightPrepLight,
  // Completed station and cooked meal confirmation tokens.
  cookedSuccessContainer = FreshTagContainerLight,
  onCookedSuccess = OnFreshTagLight,
  // Extended SavorAI warm linen, parchment, ink, and crisp 1.dp stone border tokens.
  canvasWarm = CanvasWarmLight,
  parchment = ParchmentSurfaceLight,
  herbalSage = HerbalSage,
  herbalSagePressed = HerbalSagePressed,
  sageLight = SageLightSurface,
  terracottaClay = TerracottaClay,
  terracottaCookMode = TerracottaCookMode,
  terracottaLight = TerracottaLightSurface,
  warmApricot = WarmApricot,
  charcoalInk = CharcoalInk,
  slateMuted = SlateMutedInk,
  ashMuted = AshMutedInk,
  borderSubtle = BorderSubtleLight,
  // Muted botanical, amber, and terracotta shelf-life telemetry triad.
  freshIndicator = FreshIndicator,
  freshContainer = FreshTagContainerLight,
  onFreshContainer = OnFreshTagLight,
  expiringSoonIndicator = ExpiringSoonIndicator,
  expiringSoonContainer = ExpiringSoonTagContainerLight,
  onExpiringSoonContainer = OnExpiringSoonTagLight,
  expiredIndicator = ExpiredIndicator,
  expiredContainer = ExpiredTagContainerLight,
  onExpiredContainer = OnExpiredTagLight,
  // Material 3 fixed tonal tokens shared across light and dark modes.
  primaryFixed = SavorPrimaryFixed,
  primaryFixedDim = SavorPrimaryFixedDim,
  onPrimaryFixed = SavorOnPrimaryFixed,
  onPrimaryFixedVariant = SavorOnPrimaryFixedVariant,
  secondaryFixed = SavorSecondaryFixed,
  secondaryFixedDim = SavorSecondaryFixedDim,
  onSecondaryFixed = SavorOnSecondaryFixed,
  onSecondaryFixedVariant = SavorOnSecondaryFixedVariant,
  tertiaryFixed = SavorTertiaryFixed,
  tertiaryFixedDim = SavorTertiaryFixedDim,
  onTertiaryFixed = SavorOnTertiaryFixed,
  onTertiaryFixedVariant = SavorOnTertiaryFixedVariant,
)

/** Ambient Counter Dark `SavorAI` semantic tokens optimized for evening OLED kitchen legibility. */
val AmbientCounterDarkExtendedColors: ExtendedKitchenColors = ExtendedKitchenColors(
  // Deep espresso-pine OLED backdrop and elevated dark stone card surfaces.
  linen = CounterDarkBackground,
  canvas = CounterDarkCanvas,
  cardSurface = CounterDarkCardSurface,
  // Luminous mint-sage and coral-terracotta accents legible at arms-length night prep.
  sage = SagePrimaryDark,
  sageContainer = SageContainerDark,
  onSageContainer = OnSageContainerDark,
  terracotta = TerracottaAccentDark,
  terracottaContainer = TerracottaContainerDark,
  onTerracottaContainer = OnTerracottaContainerDark,
  // High-contrast dark amber EC-08 toddler safety callout tokens.
  childSafetyContainer = HoneyAmberSafetyContainerDark,
  childSafetyBorder = HoneyAmberSafetyBorderDark,
  onChildSafety = OnHoneyAmberSafetyDark,
  // Warm dark chamomile & luminous bronze 8:30 PM night-prep tokens.
  nightPrepContainer = IndigoNightPrepContainerDark,
  nightPrepBorder = IndigoNightPrepBorderDark,
  onNightPrep = OnIndigoNightPrepDark,
  cookedSuccessContainer = CookedSuccessContainerDark,
  onCookedSuccess = OnCookedSuccessDark,
  canvasWarm = CounterDarkBackground,
  parchment = CounterDarkCanvas,
  herbalSage = SagePrimaryDark,
  herbalSagePressed = HerbalSage,
  sageLight = SageContainerDark,
  terracottaClay = TerracottaAccentDark,
  terracottaCookMode = SavorSecondaryContainer,
  terracottaLight = TerracottaContainerDark,
  warmApricot = SavorTertiaryFixedDim,
  charcoalInk = InkPrimaryDark,
  slateMuted = InkSecondaryDark,
  ashMuted = InkSecondaryDark,
  // Crisp warm dark border (0xFF323D37) separating OLED cards without harsh white lines.
  borderSubtle = CounterDarkOutline,
  freshIndicator = FreshIndicator,
  freshContainer = CookedSuccessContainerDark,
  onFreshContainer = OnCookedSuccessDark,
  expiringSoonIndicator = ExpiringSoonIndicator,
  expiringSoonContainer = HoneyAmberSafetyContainerDark,
  onExpiringSoonContainer = SavorTertiaryFixed,
  expiredIndicator = ExpiredIndicator,
  expiredContainer = ErrorShortageContainerDark,
  onExpiredContainer = SavorErrorContainer,
  primaryFixed = SavorPrimaryFixed,
  primaryFixedDim = SavorPrimaryFixedDim,
  onPrimaryFixed = SavorOnPrimaryFixed,
  onPrimaryFixedVariant = SavorOnPrimaryFixedVariant,
  secondaryFixed = SavorSecondaryFixed,
  secondaryFixedDim = SavorSecondaryFixedDim,
  onSecondaryFixed = SavorOnSecondaryFixed,
  onSecondaryFixedVariant = SavorOnSecondaryFixedVariant,
  tertiaryFixed = SavorTertiaryFixed,
  tertiaryFixedDim = SavorTertiaryFixedDim,
  onTertiaryFixed = SavorOnTertiaryFixed,
  onTertiaryFixedVariant = SavorOnTertiaryFixedVariant,
)
