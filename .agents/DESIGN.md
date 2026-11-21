---
name: SavorAI
colors:
  surface: '#f9f9ff'
  surface-dim: '#d4daea'
  surface-bright: '#f9f9ff'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#f1f3ff'
  surface-container: '#e8eeff'
  surface-container-high: '#e3e8f9'
  surface-container-highest: '#dde2f3'
  on-surface: '#161c27'
  on-surface-variant: '#3f4943'
  inverse-surface: '#2a303d'
  inverse-on-surface: '#ecf0ff'
  outline: '#6f7a72'
  outline-variant: '#bec9c0'
  surface-tint: '#176b4a'
  primary: '#096443'
  on-primary: '#ffffff'
  primary-container: '#2e7d5a'
  on-primary-container: '#d0ffe2'
  inverse-primary: '#88d7ad'
  secondary: '#a13e28'
  on-secondary: '#ffffff'
  secondary-container: '#fd8367'
  on-secondary-container: '#721c09'
  tertiary: '#834800'
  on-tertiary: '#ffffff'
  tertiary-container: '#a65d00'
  on-tertiary-container: '#fff1e9'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#a4f3c8'
  primary-fixed-dim: '#88d7ad'
  on-primary-fixed: '#002113'
  on-primary-fixed-variant: '#005235'
  secondary-fixed: '#ffdad2'
  secondary-fixed-dim: '#ffb4a3'
  on-secondary-fixed: '#3d0600'
  on-secondary-fixed-variant: '#822714'
  tertiary-fixed: '#ffdcc1'
  tertiary-fixed-dim: '#ffb877'
  on-tertiary-fixed: '#2e1600'
  on-tertiary-fixed-variant: '#6c3a00'
  background: '#f9f9ff'
  on-background: '#161c27'
  surface-variant: '#dde2f3'
typography:
  headline-xl:
    fontFamily: Newsreader
    fontSize: 2.5rem
    fontWeight: '500'
    lineHeight: 2.875rem
    letterSpacing: -0.02em
  headline-xl-mobile:
    fontFamily: Newsreader
    fontSize: 2rem
    fontWeight: '500'
    lineHeight: 2.375rem
    letterSpacing: -0.015em
  headline-lg:
    fontFamily: Newsreader
    fontSize: 1.75rem
    fontWeight: '500'
    lineHeight: 2.125rem
    letterSpacing: -0.01em
  headline-sm:
    fontFamily: Plus Jakarta Sans
    fontSize: 1.25rem
    fontWeight: '700'
    lineHeight: 1.625rem
    letterSpacing: -0.01em
  body-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 1.0625rem
    fontWeight: '400'
    lineHeight: 1.625rem
    letterSpacing: -0.005em
  body-md:
    fontFamily: Plus Jakarta Sans
    fontSize: 0.9375rem
    fontWeight: '400'
    lineHeight: 1.4375rem
  body-sm:
    fontFamily: Plus Jakarta Sans
    fontSize: 0.8125rem
    fontWeight: '400'
    lineHeight: 1.1875rem
  label-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 0.875rem
    fontWeight: '600'
    lineHeight: 1.125rem
    letterSpacing: 0.01em
  label-md:
    fontFamily: Plus Jakarta Sans
    fontSize: 0.75rem
    fontWeight: '600'
    lineHeight: 1rem
    letterSpacing: 0.02em
  label-sm:
    fontFamily: Plus Jakarta Sans
    fontSize: 0.6875rem
    fontWeight: '700'
    lineHeight: 0.875rem
    letterSpacing: 0.04em
rounded:
  sm: 0.5rem
  DEFAULT: 1rem
  md: 1.5rem
  lg: 2rem
  xl: 3rem
  full: 9999px
spacing:
  gutter: 1rem
  gutter-tablet: 1.5rem
  margin: 1rem
  margin-tablet: 2rem
  space-xs: 0.25rem
  space-sm: 0.5rem
  space-md: 1rem
  space-lg: 1.5rem
  space-xl: 2.25rem
---

## Brand & Style
The design system pairs an intelligent, predictive culinary assistant with the warmth, craft, and tactility of an artisanal kitchen. Designed primarily for mobile touchpoints, it synthesizes **Modern Organic Minimalism** with subtle **Editorial Warmth**. 

### Target Audience & Emotional Intent
- **Audience:** Culinary enthusiasts, home cooks seeking food-waste reduction, and busy professionals who value thoughtful pantry management alongside generative recipe discovery.
- **Emotional Intent:** Calm, inspiring, trustworthy, and nourishing. The experience should feel like stepping into a sunlit kitchen equipped with a quiet, Michelin-trained sous chef—never clinical or overwhelmingly computational.

### Aesthetic Principles
- **Natural Tactility:** Digital parchment surfaces and botanical tones ground the technical AI features in organic culinary familiarity.
- **Micro-Precision AI:** AI interactions avoid cold robotic patterns; LLM outputs feel like bespoke recipe cards, pantry logs, and culinary notes with tactile micro-interactions and soft feedback.
- **Restraint & Appetite:** Generous breathing room, precise editorial typography, and high-fidelity photography heroing ingredients over heavy decorative UI elements.

## Colors
The palette balances fresh, garden-harvested botanicals with warm culinary terracotta, supported by layered warm paper backgrounds and deep charcoal typography for maximum legibility.

### Palette Architecture
- **Primary (`#2E7D5A` - Herbal Sage):** Anchors primary actions, high-confidence AI badges, affirmative buttons, and active bottom navigation states.
- **Secondary (`#E06D53` - Terracotta Clay):** Highlights culinary accents, preparation calls to action, saved items, and interactive timer/cooking steps.
- **Tertiary (`#F48C06` - Warm Apricot):** Used for proactive suggestions, culinary tips, and flavor pairing notices.
- **Neutral Primary (`#1A202C` - Deep Charcoal):** Headings, body copy, and high-emphasis interface elements.
- **Neutral Muted (`#4A5568` & `#718096` - Slate & Ash):** Subheadings, nutritional specs, timestamps, and input placeholder states.

### Surface System
- **Canvas / App Background (`#FBFBF9`):** A soft, warm off-white that eliminates screen glare and mimics natural linen.
- **Surface Elevation 1 (`#FFFFFF`):** High-priority interactive cards, input fields, and bottom sheets.
- **Surface Elevation 2 (`#F4F3EE` - Parchment):** Grouped item containers, prompt bubbles, recipe step trackers, and secondary tags.
- **Border Subtle (`#E2E8F0` / 60% opacity):** Whisper-thin boundaries for structural clarity under bright ambient light.

### State & Expiry Tokens
- **Fresh / Safe (`#10B981`):** Optimal shelf-life indicators and pantry intake verification.
- **Expiring Soon (`#F59E0B`):** 48-to-72-hour consumption recommendations and triage recipe catalysts.
- **Expired / Urgent (`#EF4444`):** Spoilage warnings and pantry archive triggers.

## Typography
The typographical hierarchy balances modern utility with sensory storytelling.

### Family Pairing
- **Editorial Headlines (`Newsreader`):** Infuses recipe names, chef notes, and conversational AI welcome banners with classic cookbook sophistication. Used deliberately at display scale.
- **Interface & Content (`Plus Jakarta Sans`):** Delivers clean readability on high-density ingredient tables, nutritional data, cooking instruction steps, and system controls.

### Implementation Guidelines
- **Recipe Titles:** Always employ `Newsreader` italic or medium variations for primary hero titles to convey culinary artistry.
- **Numbers & Metrics:** Use `Plus Jakarta Sans` with tabular figures enabled for prep times, calorie counts, and pantry weights.
- **Instructional Steps:** Step numbers use bold terracotta numerals paired with `body-md` for legibility while cooking.

## Layout & Spacing
Designed from a mobile-first perspective with comfortable ergonomics for single-handed kitchen use.

### Mobile Grid & Ergonomics
- **Grid Layout:** 4-column fluid grid on mobile devices (320px–480px width) with `1rem` margins and `1rem` gutters. Expands to an 8-column layout on tablets.
- **Touch Targets:** Minimum interactive area of 44x44 points across all active elements (ingredients checks, filter chips, sheet expanders).
- **Thumb Zone Layout:** Core navigation and culinary query inputs sit in the bottom third of the viewport, ensuring ease of use when cooking.

### Density & Rhythms
- **Pantry Grids:** Compact `0.5rem` internal gaps to maximize inventory visibility without vertical exhaustion.
- **Recipe Canvas:** Expanded vertical pacing (`1.5rem` to `2.25rem`) between prep phases, ingredients, and method steps to reduce cognitive overload during active preparation.

## Elevation & Depth
Depth avoids artificial drop-shadows, relying on soft, diffused ambient light, warm surface layering, and crisp 1px borders.

### Surface Tiers
- **Base Tier (`#FBFBF9`):** Canvas backdrop. Flat with no drop shadow.
- **Card Tier (`#FFFFFF`):** Sits on the canvas. Uses a layered micro-shadow tinted with charcoal: `0px 2px 8px rgba(26, 32, 44, 0.04), 0px 1px 2px rgba(26, 32, 44, 0.02)`, bounded by a 1px border of `#E2E8F0` at 60% opacity.
- **Floating Modals & Sheets:** Elevated using warm ambient occlusion: `0px 12px 32px rgba(26, 32, 44, 0.08), 0px 4px 12px rgba(26, 32, 44, 0.04)`.
- **LLM Interactive Highlights:** Interactive AI generated content containers feature a slight inner glow using `inset 0 1px 0 rgba(255, 255, 255, 0.8)` layered over `#FFFFFF` or `#F4F3EE`.

## Shapes
A deeply curved, tactile shape language evokes the round edges of kitchenware, ceramic plates, and natural ingredients.

### Corner Radii Guidelines
- **Base Components (Inputs, Small Badges):** `rounded-md` (`0.5rem`) to `rounded-lg` (`1rem`).
- **Standard Cards & Modals:** `rounded-2xl` (`1.5rem`) for standard pantry cards, chat bubbles, and recipe summaries.
- **Primary Sheets & Hero Visuals:** `rounded-3xl` (`2rem`) for bottom sheets, pantry category panels, and hero ingredient containers.
- **Action Elements & Chips:** Fully pill-shaped (`9999px`) for filter pills, active aisle tags, and primary CTA buttons.

## Components

### Buttons
- **Primary Button:** Pill-shaped, herbal sage (`#2E7D5A`) fill with crisp white text (`label-lg`), featuring a subtle internal highlight and 48px height. Press state scales down slightly to `0.98` with darkened background (`#246347`).
- **Secondary Button:** Terracotta (`#E06D53`) fill or outline with warm apricot accents for culinary actions (e.g., "Start Cooking", "Add to Grocery List").
- **Ghost/Parchment Button:** `#F4F3EE` fill with `#1A202C` text for inline dismissals and secondary prompts.

### Chips & Filter Badges
- **Aisle / Category Chips:** Pill-shaped, `0.75rem` vertical and `1rem` horizontal padding. Inactive state: `#FFFFFF` with `#E2E8F0` border; active state: `#2E7D5A` background with crisp white typography.
- **Expiry Status Tags:** Compact capsule badges (`0.25rem` padding vertical, `0.5rem` horizontal, `label-sm`).
  - *Fresh:* Tinted `#ECFDF5` background with `#047857` text.
  - *Expiring Soon:* Tinted `#FFFBEB` background with `#B45309` text.
  - *Expired:* Tinted `#FEF2F2` background with `#B91C1C` text.

### Cards
- **Pantry Item Card:** Elevated white card, `rounded-2xl`, subtle border, containing thumbnail, food name (`headline-sm`), quantity badge, and colored expiry indicator strip on the leading edge.
- **LLM Recipe Suggestion Card:** Parchment-accented background (`#F4F3EE`) with a subtle `#2E7D5A` border accent (2px left border). Contains structured collapsible sections: "Ingredients Matched", "Missing Staples", and a floating pill CTA ("Cook This Recipe").

### Conversational UI & Input
- **User Prompt Bubbles:** Pill-styled, rich charcoal (`#1A202C`) background with white typography, aligned to the trailing side.
- **AI Response Bubbles:** Seamless parchment background (`#FFFFFF`), leading-aligned with an herbal sage sous-chef badge avatar, displaying streamed text with embedded action chips.
- **Kitchen Input Bar:** Floating docked bar (`rounded-full`) resting above the bottom sheet bar with voice input icon, camera pantry-scan trigger, and prompt input.

### Bottom Sheet Tabs
- Docked frosted off-white bar (`#FBFBF9` at 92% opacity with 16px blur), containing 4 key culinary spaces: **Pantry**, **SavorAI (Chat/Vision)**, **Cookbook**, and **Plan**. Active items display an organic sage indicator dot beneath the icon.
