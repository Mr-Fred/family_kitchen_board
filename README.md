# SavorAI — Family Kitchen Board & Smart Pantry Co-Pilot

100% Native Android, offline-first culinary assistant, shelf-life & two-tier pantry ledger, clean cookbook reader, hands-free step-by-step cook mode, and 21-slot family meal planner built with **Kotlin 2.2**, **Jetpack Compose (Material 3)**, **LiteRT-LM on-device multimodal AI**, and opt-in **Keystore-encrypted HTTPS Cloud LLM routing** (`applicationId = "com.familykitchen.board"`, `minSdk = 31`, `targetSdk = 34`).

Calibrated out of the box for a **Family of 4** (`Fred`, `Wife`, `8-year-old`, and `2-year-old`) shopping in **Antipolo & Marikina, PH (`PHP`, `₱`)**, with built-in consumer household templates (`Solo Cook`, `Active Couple`, `Young Family + Baby`, `Family of 4`, `Large / Multi-Gen 6`) and multi-currency formatting (`PHP`, `USD`, `EUR`, `GBP`, `XOF`).

---

## Highlights & Design System (`SavorAI`)

- **Dual-Mode AI Architecture (100% Offline-First Default + 1-Tap Gemma 4 Download + Opt-In Encrypted Cloud API)**:
  - **On-Device Offline Default (`AiExecutionMode.ON_DEVICE`)**: Runs 100% locally via `LiteRtMultimodalEngine` on **Samsung Galaxy S22 & Galaxy S22 Ultra** OpenCL GPU with automatic CPU fallback, bundled fonts/artwork, bilingual speech sessions (`en-US` & `fr-FR`), **zero** `MANAGE_EXTERNAL_STORAGE` or `READ_EXTERNAL_STORAGE` permissions, and an approval-gated resumable Gemma 4 (`E2B` ~2.0 GB / `E4B` ~3.2 GB) `.litertlm` downloader (`ModelDownloadApprovalDialog.kt` + `OnDeviceModelDownloader.kt`, checking `StatFs` free space and `ACCESS_NETWORK_STATE` Wi-Fi vs metered cellular status before streaming into private `filesDir/models/<name>.part` with `HTTP 206` `Range` resumption).
  - **Opt-In Cloud API Key Mode (`AiExecutionMode.CLOUD_API`)**: Encrypts user-supplied credentials at rest in `KeystoreCloudCredentialVault` (`AndroidKeyStore` `AES-256-GCM`), enforces HTTPS-only `networkSecurityConfig` (`cleartextTrafficPermitted="false"`, `android:allowBackup="false"`, strict `ALLOWED_CLOUD_HOSTS` SSRF allowlist), and routes text + multimodal vision with header-only authentication across **Google Gemini** (`gemini-3.8-flash`, `gemini-4-*`), **OpenAI** (`gpt-6.2-sol`, `gpt-6.2-astra`), **Anthropic Claude** (`claude-sonnet-5-5`, `claude-opus-5-5`), and **OpenRouter** (`google/gemini-4-neon`), complete with 1-tap **`Sync Live Models`** (`GET /models` discovery) and automatic legacy model migration.
- **4-Step First-Run Onboarding Experience (`ui/onboarding/`)**: Launches automatically on brand-new cold installs (`hasCompletedOnboarding == false`) to tailor household template & cook names (`EC-08` baby/toddler safety preview + allergen exclusions), local currency & neighborhood market (`PHP` `₱` default + `USD`, `EUR`, `GBP`, `XOF`), starting pantry stock (`Quick-Check Kitchen Basics` 16-staple checklist, `Empty Pantry`, or 44-item `Sample Kitchen`), and optional 1-tap On-Device Gemma 4 `.litertlm` download / SAF import / Cloud API key setup—committing in a single `O(1)` atomic batch write with a 1-tap `Skip · Demo` escape hatch and anytime replay from `Family Profile` or `QA Lab`.
- **Artisanal Material 3 Design System, Brand Emblem & Auto-Dismissing Notifications ([`.agents/DESIGN.md`](.agents/DESIGN.md) + [`docs/ui-spec/`](docs/ui-spec/))**: Pairs bundled offline `Newsreader` editorial serif headlines and tabular `Plus Jakarta Sans` (`tnum`) metrics with Warm Daylight (`#FBFBF9` Linen, `#2E7D5A` Herbal Sage, `#E06D53` Terracotta Clay, `#F48C06` Warm Apricot) and Ambient Counter Dark OLED palettes, a bespoke `108dp` adaptive Dutch-oven cocotte launcher icon + matching Compose `Canvas` brand emblem (`SavorBrandLogo.kt`), and smooth auto-dismissing notifications (`4s` confirmations, `6s` error/long alerts, `5s` permission notices, and `8s` `Undo` bar).
- **5-Tab High-Craft Adaptive Shell (`KitchenScaffold`)**:
  - **`Chef` (`ai_assistant`)**: 2-tier composer with real camera/gallery photo thumbnail staging & voice dictation, always-visible Sous-Chef prose bubbles with `SavorBrandBadge` avatars and model provenance badges (`Sous-Chef · gemma-4-E2B-it`, `Gemini · gemini-3.8-flash`, `Kitchen Tool`, `Offline Advisor`), Gemma 4 `<|thought|>` & natural plain-text conversational grounding, structured culinary & visual-intake artifacts (selective pantry/grocery chip commit, spice-rack catalog synthesis, zero-waste recipe cards, substitution calorie deltas), non-silent `VisionNotSupportedException` gating, and 1-tap **`Undo`** (`O(1)` atomic snapshot revert).
  - **`Plan` (`TodayAndWeekScreen`)**: 7-day (21-slot) Family Meal Plan featuring 3-bar macro telemetry (`Protein`, `Carbs`, `Fat`) & custom plate artwork, `<= 25m` morning cap (`EC-01`), 8:30 PM night-prep banner, `EC-08` child safety callouts, 4-member 1-pot goal plating, `EC-07` flat `1x` Dad workout boosts, `EC-13` instant in-stock swaps, and 4-station **Weekly Batch Prep** + `<= 25m` Morning Pacer.
  - **`Pantry` (`smart_pantry_expiry`)**: Top Stat Pulse Bar with live Waste Score ring (`96%+`), `Action Required (Within 48h)` rescue cards in `PHP` (`₱`), `Fridge & Produce` Fridge Pulse steppers (`EC-10`), `Dry Goods & Freezer` grids (`EC-11`), 44 single-ingredient canonical catalog items (`0` compound bundles), and 26 Antipolo–Marikina palengke & supermarket presets.
  - **`Recipes` (`recipe_reader`)**: Filterable Cookbook (`All`, `Saved`, `<=25m Breakfast`, `100% In Stock`, `Expiring Rescue`), `288.dp` editorial hero banner, `1x | 2x | 4x | Custom` yield scaling, interactive ingredient checklists, inline step timers, kitchen notes, and offline `Intent.ACTION_SEND` sharing.
  - **`Shop` (`smart_grocery_list`)**: Smart Grocery List with chronological 21-slot Tier-1 reservation, aisle-sorted whole-package `ceil` rounding (`EC-09`), deduplication `₱` savings banner, bilingual voice dictation, and 1-tap `O(1)` batch restock.
  - **`Step-by-Step Cook Mode` (`step_by_step_cook_mode`)**: Full-screen wake-locked (`FLAG_KEEP_SCREEN_ON`) cooking overlay with segmented progress bars, concurrent multi-step `44.sp` tabular timers (`+1 Min`, `Pause`, `Reset`), bilingual (`en-US` & `fr-FR`) hands-free voice commands, grounded Sous-Chef Q&A, and 1-tap final-step atomic pantry deduction.

---

## Hexagonal Architecture (`Functional Core, Imperative Shell`)

All production code resides under [`android/app/src/main/java/com/familykitchen/board/`](android/app/src/main/java/com/familykitchen/board/):

- **`domain/` — 100% Pure Kotlin Functional Core**:
  - Zero `android.*`, `androidx.*`, Compose, `java.io.*`, or `java.util.Locale` imports.
  - Modules: `KitchenModels.kt`, `HouseholdAndMarketModels.kt` (`HouseholdCurrency.PHP` `₱`, `ConsumerHouseholdTemplate`, `LOCAL_MARKET_PRESET_INGREDIENTS`, `migrateUntangledCatalogData`), `FirstRunOnboardingModels.kt` (`StartingPantryMode`, `FirstRunOnboardingDraft`, `computeFirstRunOnboardingOutcome`), `AiChefModels.kt`, `OnDeviceModelCatalog.kt` (`DownloadableGemmaPreset`, `ModelDownloadApprovalSpec`, `validateInitialModelDownloadUrl`, `validateModelRedirectUrl`), `CloudLlmModels.kt` (`AiExecutionMode`, `CloudLlmProvider`, legacy model migration & API secret redaction), `DomainExceptions.kt`, `SchemaValidator.kt`, `AvailabilityFilter.kt` (`EC-02`/`EC-03` across `FRUIT_TOPPING`, `LEAFY_GREENS`, & `NUT_SEED_TOPPING`), `PantryLedgerEngine.kt` (`EC-04`–`EC-07`, `EC-10`, `EC-11`), `PantryExpiryEngine.kt`, `MenuPlannerEngine.kt` (`EC-01`, `EC-08`, `EC-12`, `EC-13`, 4-station prep), `CookModeEngine.kt`, `GroceryDiffEngine.kt` (`EC-09`), `AiMenuAdvisorEngine.kt`, `AiVisualAndToolGrounding.kt`, and `AiConversationalGrounding.kt` (`sanitizeLlmRawOutput`, `isMealOrRecipeProposalRequest`, `groundFlexibleLlmTextReply`, `formatAiResponseSourceLabel`).
- **`ports/` — Pure Coroutine Boundary Interfaces**:
  - `KitchenStoragePort` (`O(1)` atomic `KitchenSnapshot` commits + `batchWriteCallCount` + onboarding batch helpers), `SeedCatalogPort`, `AiEdgePort` (`AiRuntimeState.Importing(isNetworkDownload)`, `inspectDownloadPreconditions`, `downloadAndLoadPresetModel`, `cancelActiveDownload`), `CloudCredentialVaultPort`, and `VoiceInputPort`.
- **`data/` — Offline Persistence, Keystore Credential Vault, Seed Asset & Image Adapters**:
  - `AssetSeedCatalogLoader`, `LocalJsonKitchenStorage` (`Mutex`-serialized temp-file `fsync` + atomic rename + cold-install onboarding detection + non-destructive legacy 38 -> 44 untangled snapshot migration), `KeystoreCloudCredentialVault` (`AndroidKeyStore` `AES-256-GCM`), `InMemoryCloudCredentialVault`, `KitchenImagePreprocessor` (`FileProvider` `cacheDir/captures/` capture + `ExifInterface` rotation + `<= 1280px` JPEG downscale), and stateful in-memory `FakeKitchenStorage`.
- **`ai/` — Hybrid On-Device & Cloud Multimodal LLM, Resumable Gemma 4 Downloader, Live Model Sync & Bilingual Speech**:
  - `LiteRtMultimodalEngine` (`com.google.ai.edge.litertlm:litertlm-android:0.16.1` GPU/CPU + `tasks-genai:0.10.22` fallback, `Mutex`-serialized JNI calls, fresh per-image `Conversation` reset, hard `VisionNotSupportedException` gate), `OnDeviceModelDownloader` (approval-gated, SSRF-checked Hugging Face / Xet CDN `.part` downloader with `StatFs` + `ACCESS_NETWORK_STATE` pre-flight checks, `HTTP 206` `Range` resumption, and clean cancel `.part` deletion), `HybridAiInferenceRouter`, `CloudLlmClient`, `CloudModelCatalogFetcher` (1-tap `GET /models` sync), `ModelFileDiscovery` (`*.part` atomic SAF import), `AiChefCoordinator`, and `AndroidVoiceInputAdapter`.
- **`ui/` — Stateless Material 3 Compose Screens, `@Preview`s & Thin `MainActivity`**:
  - `MainActivity.kt` (`< 80` LOC `ComponentActivity`), `KitchenUiState.kt` (`toastEventId`, `computeToastAutoDismissMillis`, `withToast`), `KitchenBoardViewModel.kt`, `KitchenViewModelReducers.kt`, `KitchenAiAndCookDelegate.kt`, `KitchenModelDownloadDelegate.kt`, `KitchenCloudConfigDelegate.kt`, `onboarding/` (`FirstRunOnboardingScreen.kt`, `OnboardingStepSections.kt`), `navigation/` (`KitchenScaffold.kt`, `KitchenScaffoldBars.kt`), `theme/` (including `SavorBrandLogo.kt`), `aichef/` (including `ModelDownloadApprovalDialog.kt`), `pantry/`, `grocery/`, `recipes/`, `cookmode/`, `board/`, `pacer/`, `sheets/`, and `preview/`.

---

## Build, Test & Repository Hygiene Commands

Run all 11 hermetic JVM unit test suites and assemble the debug APK from `android/`:

```bash
cd android
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

Simulate a brand-new cold install on a connected Android device or emulator to launch the 4-step First-Run Onboarding flow (or replay anytime from `Family Profile` or `QA & Scenario Lab`):

```bash
adb shell pm clear com.familykitchen.board
```

Run the zero-dependency personal repository leak guardrail from the repository root:

```bash
node scripts/verify_clean_personal_repo.cjs
```

### Hermetic JUnit 4 Verification Suites (`android/app/src/test/`)

1. **`EdgeCasesEc01ToEc13Test`**: Verifies all 13 locked domain edge cases (`EC-01` `<= 25m` `1.4x` morning cap through `EC-13` 100% in-stock instant swaps), 4-station weekly batch prep, and pregnancy/guest life-event guardrails.
2. **`AiAdvisorAndSchemaValidatorTest`**: Verifies strict `SchemaValidator` rejection of malformed/unknown LLM JSON, `seed_catalog.json` referential integrity, spice-rack visual catalog synthesis, and deterministic NL tool grounding in `AiMenuAdvisorEngine`.
3. **`PantryExpiryAndCookModeTest`**: Verifies `PantryExpiryEngine` Antipolo–Marikina `PHP` (`₱`) at-risk & zero-waste rescue math, yield scaling, macro-recomputing ingredient swaps, `HouseholdCurrency` formatting, and `CookModeEngine` multi-timer & bilingual/phonetic voice reducers.
4. **`BatchStorageInvariantTest`**: Verifies `O(1)` atomic batch persistence (`batchWriteCallCount == 1`) across `FakeKitchenStorage` and `LocalJsonKitchenStorage` with zero `N+1` write loops.
5. **`VisionGateAndChefCoordinatorTest`**: Verifies `Content.ImageFile` multimodal forwarding when `supportsVision == true`, non-silent `VisionNotSupportedException` gating when `supportsVision == false`, selective intake chip commits, 1-tap `Undo` snapshot restoration, and guaranteed temp-capture deletion in `finally`.
6. **`CloudLlmAndHybridRouterTest`**: Verifies zero-socket `CloudLlmClient` request/response wire formats across Gemini, OpenAI, Anthropic, and OpenRouter, reasoning/thinking block filtering, `HybridAiInferenceRouter` multimodal vision routing, SSRF host allowlisting, and API secret redaction.
7. **`CloudModelCatalogAndVaultMigrationTest`**: Verifies automatic migration of deprecated pre-late-2026 cloud model IDs on vault load/update, live `GET /models` catalog parsing & header-only auth in `CloudModelCatalogFetcher`, and `KitchenBoardViewModel` live model sync lifecycle.
8. **`FirstRunOnboardingTest`**: Verifies cold-install onboarding detection (`hasCompletedOnboarding == false`) and corrupt/reset preservation on `LocalJsonKitchenStorage`, fresh-install demo cook-name neutralization, `CURATED_BASICS` / `EMPTY_KITCHEN` / `SAMPLE_DEMO_KITCHEN` pantry initialization, consumer template serving scaling, `BABY_6_12M` (`EC-08`) & allergen-safe `<= 25m` (`EC-01`) 21-slot board drafting, and `KitchenBoardViewModel` `O(1)` onboarding commit/replay/skip lifecycle.
9. **`Gemma4AndConversationalTurnTest`**: Verifies Gemma 4 `<|thought|>...<|turn|>` and C++-prefilled `<thought|>` stripping across all 4 Galaxy S22 `"Hello"` output shapes (card-free conversational greetings, natural plain-text replies, salvaged summaries on placeholder `"..."` swaps, and sanitized corrupt-JSON fallbacks), multi-turn `RECENT_CONVERSATION` culinary Q&A continuity, model provenance badges (`gemma-4-E2B-it`, `Gemini · gemini-3.8-flash`, `Offline Advisor`), and `HybridAiInferenceRouter` automatic `ON_DEVICE` recovery when `CLOUD_API` has no stored key.
10. **`OnDeviceModelDownloaderTest`**: Verifies pure-Kotlin `validateInitialModelDownloadUrl` (`litert-community` HTTPS-only) and `validateModelRedirectUrl` (Hugging Face Hub, Xet bridge/transfer, LFS, CloudFront, S3, R2, and GCS CDN allowlisting + SSRF/loopback/private-IP/downgrade/traversal rejection), socket-free `302 -> 307` multi-hop redirect streaming with `.part` preservation on transient `IOException`, `HTTP 206 Partial Content` `Range` resumption + atomic `.part -> .litertlm` promotion + fast-path network skip, immediate `.part` deletion on `cancelActiveDownload()`, `ModelDownloadApprovalSpec` storage/metered-cellular/offline rules, and `KitchenBoardViewModel` + `HybridAiInferenceRouter` user-approval gating with automatic keyless `CLOUD_API -> ON_DEVICE` activation.
11. **`CatalogUntangleAndToastTimingTest`**: Verifies byte-for-byte `seed_catalog.json` parity (`44` single-item ingredients = `38` Tier-1 + `6` Tier-2, `0` `" & "` / `" / "` compound bundles across seed, `26` local presets, `5` brain boosters, and preview fixtures), distinct `LEAFY_GREENS` (`ing_spinach` vs `ing_amaranth_greens`) and `NUT_SEED_TOPPING` (`ing_mixed_nuts_seeds` `NUTS` vs nut-free `ing_pumpkin_seeds` `EC-03` auto-swap on `rec_05_skillet_honey_oat_granola`), non-destructive `LocalJsonKitchenStorage` upgrade of legacy 38-item bundled snapshots (`0` wiped stock, `0` phantom stock, `batchWriteCallCount == 0`, synthetic test catalogs untouched), `computeToastAutoDismissMillis` (`4s`/`6s`/`5s`/`8s`) + monotonic `KitchenUiState.toastEventId` increments across identical back-to-back toasts, and `108dp` adaptive launcher vector/mipmap XML resources (`#FAF7F2`, `#2E7D5A`, `#C85A32`, `0` cold blue/purple hues).
