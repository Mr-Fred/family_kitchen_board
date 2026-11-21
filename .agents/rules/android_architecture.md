---
trigger: always_on
description: "Mandatory Hexagonal Kotlin 2.2, Jetpack Compose Material 3, @Preview, LiteRT-LM multimodal vision, and personal-repo compliance invariants for Family Kitchen Board."
---

# Native Android Architecture & Engineering Rules

These invariants govern all architecture, Kotlin implementation, Jetpack Compose UI, on-device multimodal AI, testing, and repository hygiene inside `Family Kitchen Board` (`applicationId = "com.familykitchen.board"`, `minSdk = 31`, `targetSdk = 34`, offline-first targeting Samsung Galaxy S22 and Galaxy S22 Ultra).

## Hexagonal Boundaries & Pure `domain/` Invariant

- **Strict Package Topology (`android/app/src/main/java/com/familykitchen/board/`)**:
  - `domain/` — Pure Kotlin functional core (`KitchenModels.kt`, `AiChefModels.kt`, `CloudLlmModels.kt`, `DomainExceptions.kt`, `SchemaValidator.kt`, `AvailabilityFilter.kt`, `PantryLedgerEngine.kt`, `MenuPlannerEngine.kt`, `GroceryDiffEngine.kt`, `AiMenuAdvisorEngine.kt`).
  - `ports/` — Pure Kotlin coroutine boundary interfaces (`KitchenStoragePort`, `SeedCatalogPort`, `AiEdgePort`, `CloudCredentialVaultPort`, `VoiceInputPort`).
  - `data/` — Offline-first persistence, Keystore AES-256-GCM cloud credential vault, seed asset loading, image preprocessing, and test fakes (`AssetSeedCatalogLoader`, `LocalJsonKitchenStorage`, `KeystoreCloudCredentialVault`, `InMemoryCloudCredentialVault`, `KitchenImagePreprocessor`, and in-memory `FakeKitchenStorage` in `src/test/`).
  - `ai/` — Dual-mode on-device + opt-in HTTPS cloud multimodal inference, live `/models` catalog sync, model file discovery, grounded prompt/proposal coordination, and speech adapters (`LiteRtMultimodalEngine`, `HybridAiInferenceRouter`, `CloudLlmClient`, `CloudModelCatalogFetcher`, `ModelFileDiscovery`, `AiChefCoordinator`, `AndroidVoiceInputAdapter`).
  - `ui/` — Stateless Jetpack Compose Material 3 screens, sheets, adaptive navigation, theme tokens, `@Preview` fixtures, `KitchenBoardViewModel`, and thin `MainActivity.kt`.
- **100% Pure `domain/` Rule**:
  - Files in `com.familykitchen.board.domain` MUST import ONLY Kotlin standard library packages and `kotlinx.serialization.*`.
  - **ZERO** `android.*`, `androidx.*`, Compose (`androidx.compose.*`), Room, DataStore, LiteRT (`com.google.ai.edge.litertlm.*`), MediaPipe (`com.google.mediapipe.*`), or `java.io.*` imports are permitted inside `domain/`.
  - All `domain/` engines MUST remain pure deterministic functions from immutable input snapshots to immutable output values (no hidden clocks, random generators, singletons, or I/O).
- **Port-Only I/O & `O(1)` Atomic Batch Persistence**:
  - All persistence, asset loading, on-device LLM/VLM execution, and speech recognition MUST cross `com.familykitchen.board.ports` (`KitchenStoragePort`, `SeedCatalogPort`, `AiEdgePort`, `VoiceInputPort`).
  - **Zero N+1 Write Loops**: Every multi-ingredient cooked deduction (including quantity overrides, household default learning, flat `1x` Dad workout boosts, and ad-hoc extras), grocery restock, station-grouped weekly prep commit, or AI Chef board/grocery proposal commit MUST execute as a single `O(1)` atomic batch write (temp-file write + atomic rename or single DataStore transaction).
  - **Stateful Fake Verification (No Storage Mocks)**: JVM unit tests MUST verify persistence boundaries against stateful in-memory `FakeKitchenStorage` (never mock frameworks on storage ports) and explicitly assert `batchWriteCallCount == 1` per batch user action.

## Kotlin 2.2 Conventions & Concurrency Hygiene

- **Compiler & Formatting Baseline**:
  - Target Kotlin `2.2.0` (`org.jetbrains.kotlin.plugin.compose` and `org.jetbrains.kotlin.plugin.serialization`) with `JvmTarget.JVM_17`, 2-space indentation, and `<= 100`-character line width.
- **Immutable Data & Sealed Hierarchies**:
  - Model domain entities, persistence snapshots, and UI state as immutable `@Serializable data class`, `enum class`, and `sealed interface` types.
  - Prefer `val` over `var` everywhere and expose read-only `List`, `Set`, and `Map` collections on all public APIs.
  - Prefer `sealed interface` over `sealed class` for action, result, and UI state hierarchies that do not share constructor state.
- **Control Flow, Null Safety & Import Discipline**:
  - **`when` Over `if / else if` Chains**: Always use `when` expressions (`when (subject) { ... }` or argumentless `when { ... }`) instead of `if (...) { ... } else if (...) { ... }` chains (including two-branch `if / else if`).
  - **Zero `!!` Assertions**: The non-null assertion operator (`!!`) is strictly banned. Use explicit null checks (`if (value != null)`), Elvis early exits (`?: return` / `?: throw`), or `requireNotNull(value) { ... }`.
  - **Zero Wildcard Imports**: Wildcard imports (`import foo.*`) are strictly prohibited in all `.kt` and `.kts` files. Resolve symbol collisions with explicit `import ... as ...` aliases.
  - **Explicit Domain Exceptions**: Define typed domain failures as subclasses of `KitchenBoardException` (`ValidationException`, `IngredientUnavailableException`, `VisionNotSupportedException`), preserving underlying causes (`cause: Throwable? = null`). Never swallow exceptions with empty `catch (_: Throwable)` blocks; always rethrow `CancellationException` in `suspend` code.
  - **Functional Transformation Pipelines**: Favor `map`, `filter`, `associateBy`, `fold`, and guard clauses over nested imperative loops; use `asSequence()` when chaining 3+ transformations or short-circuiting over large collections.
- **Coroutines, `Mutex` Native Serialization & Lifecycle Cleanup**:
  - Expose asynchronous boundaries as `suspend` functions and state streams as `StateFlow` / `Flow` bound to managed `CoroutineScope`s (`viewModelScope` in ViewModels; injected `CoroutineDispatcher`s in adapters). Never call `Thread.sleep()` or `runBlocking` in production code, and never create uncancelled orphan `CoroutineScope`s.
  - **`Mutex`-Serialized Native Engine Access**: Guard every JNI call on `LiteRtMultimodalEngine` (`Engine`, `Conversation`, `Session`, and MediaPipe `LlmInference` initialization, inference, conversation reset, and teardown) inside a coroutine `Mutex` (`engineMutex.withLock { ... }`) to prevent concurrent native memory access or `SIGSEGV` during turn overlap or model reload.
  - **Deterministic `.close()` Teardown & Per-Scan Conversation Reset**: Create or reset a fresh `Conversation` for each image scan so multi-photo sessions do not exhaust KV-cache RAM on 8 GB devices, and deterministically call `.close()` on all active `Conversation`, `Session`, `Engine`, and `LlmInference` handles (plus `SpeechRecognizer.destroy()`) on model switch and `ViewModel.onCleared()` / `Activity.onDestroy()`.
  - **Atomic `.part` Model File Import**: Import user-selected `.litertlm`, `.task`, and `.bin` models via `ActivityResultContracts.OpenDocument` into `File(context.filesDir, "models")` by streaming to a `*.part` temporary file and renaming atomically only after full byte verification (plus zero-permission discovery in `context.getExternalFilesDir("models")`).

## Jetpack Compose + Material 3 & Mandatory `@Preview` Standard

- **100% Native Compose + Material 3 (Zero WebView / Zero AppCompat)**:
  - Render all screens, dialogs, and modal bottom sheets in Jetpack Compose with Material 3 (`Theme.FamilyKitchenBoard`, backed in XML only by `android:Theme.Material.Light.NoActionBar` in `android/app/src/main/res/values/themes.xml`).
  - Strictly forbid `android.webkit.WebView`, `androidx.webkit`, `androidx.appcompat`, bundled HTML/JS UI assets, and `@JavascriptInterface` bridges. Keep `MainActivity.kt` as a thin (`< 80` logical lines) `ComponentActivity` calling `enableEdgeToEdge()` and `setContent`.
- **Unidirectional Data Flow (UDF)**:
  - `KitchenBoardViewModel` owns presentation orchestration and exposes one immutable `StateFlow<KitchenUiState>` observed via `collectAsStateWithLifecycle()`.
  - Screen, card, and sheet composables MUST remain stateless: accept immutable state slices and explicit event callbacks (`(AiChefAction) -> Unit`, `(MealSlotId) -> Unit`, etc.), and never accept `ViewModel`, `Activity`, `Context`, or port implementations as composable parameters.
- **Adaptive Layout & Calm Culinary Material 3 Theme**:
  - `KitchenScaffold` MUST render a 4-destination bottom `NavigationBar` (`Today`, `Pacer`, `Kitchen` with live grocery deficit `Badge`, `AI Chef`) on compact portrait phones and switch automatically to a `NavigationRail` + multi-column counter layout in landscape / wide-screen counter mode.
  - Provide complete **Warm Daylight** (`#FAF7F2` Linen background, `#EFECE6` canvas, `#FFFFFF` surface cards, `#2D6A4F` Sage primary, `#B84A24` Terracotta accent, `#FEF3C7`/`#F59E0B` child-safety callout tokens, `#EEF2FF`/`#6366F1` 8:30 PM night-prep banner tokens) and **Ambient Counter Dark** Material 3 color schemes in `ui/theme/`.
- **Mandatory `@Preview` Requirement**:
  - **Every** screen composable (`TodayAndWeekScreen`, `MorningPacerScreen`, `PantryAndGroceryScreen`, `AiChefScreen`) and **every** major card or bottom-sheet composable (`MealSlotCard`, `CookedOverrideSheet`, `CustomMealSheet`, `FamilyProfileSheet`, `QaScenarioLabSheet`) MUST include `@Preview` functions wrapped in `FamilyKitchenBoardTheme`.
  - `@Preview` functions MUST pass realistic Family-of-4 (`Fred`, `Wife`, `8yo`, `2yo`) pure-Kotlin preview fixtures with **zero** runtime Android singletons, `ViewModel` instantiation, disk reads, or native JNI calls so Android Studio and CLI Compose Preview render cleanly out of the box.

## Authentic Multimodal Camera/Gallery, LiteRT-LM & Voice Rules

- **Zero Fake/Hardcoded Image Prompts or Pre-Inference Pantry Mutations**:
  - **Absolute Ban**: Never inject hardcoded ingredient strings (such as fake `"We bought shrimp, tilapia..."` text) or run regex/keyword pantry mutations when a user captures or picks an image.
  - Visual scans MUST construct a neutral visual inspection prompt (`AiMenuAdvisorEngine.buildVisualIntakePrompt`), run real multimodal inference on the attached image file, validate and ground returned JSON against `SeedCatalogDataset`, and mutate pantry/grocery state ONLY when the user explicitly confirms the grounded proposal.
- **Modern Scoped Capture, Photo Picker & Preprocessing (`KitchenImagePreprocessor`)**:
  - **Gallery Picker**: Use `ActivityResultContracts.PickVisualMedia` (`PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)`) with zero storage permissions.
  - **Full-Resolution Camera Capture**: Request `Manifest.permission.CAMERA` only when the user taps Camera (handling permission denial cleanly without launching capture), then launch `ActivityResultContracts.TakePicture` targeting a scoped `content://` URI from `androidx.core.content.FileProvider` (`cacheDir/captures/`, configured in `AndroidManifest.xml` and `android/app/src/main/res/xml/file_paths.xml`).
  - **Prohibited Legacy APIs & Permissions**: NEVER read low-resolution `data.extras["data"]` camera thumbnails, NEVER pass `file://` (`Uri.fromFile`) URIs to external intents, NEVER store pending images in unguarded global `@Volatile var` fields, and NEVER declare or request `MANAGE_EXTERNAL_STORAGE`, `READ_EXTERNAL_STORAGE`, or `WRITE_EXTERNAL_STORAGE`.
  - **Dual-Mode On-Device (Default Offline) + Encrypted HTTPS Cloud LLM Security**:
    - Default execution mode MUST remain `AiExecutionMode.ON_DEVICE` (`LiteRtMultimodalEngine`, 0 network sockets).
    - Opt-in `AiExecutionMode.CLOUD_API` (`HybridAiInferenceRouter` + `CloudLlmClient`) MUST enforce `android:allowBackup="false"`, `android:networkSecurityConfig="@xml/network_security_config"` (`cleartextTrafficPermitted="false"`), strict `ALLOWED_CLOUD_HOSTS` (`generativelanguage.googleapis.com`, `api.openai.com`, `api.anthropic.com`, `openrouter.ai`) SSRF validation, header-only API key authentication (never `?key=` URL query strings), secret redaction (`redactApiSecrets`), and hardware-backed `AndroidKeyStore` AES-256-GCM encryption at rest (`KeystoreCloudCredentialVault`). Raw API keys MUST NEVER be stored in `KitchenSnapshot` or `KitchenUiState`.
  - **EXIF Orientation, Bounded Resize & Temp Cleanup**: Normalize photo orientation with `androidx.exifinterface.media.ExifInterface`, downscale bitmaps proportionally so `max(width, height) <= 1280` px (JPEG quality ~88), pass the explicit preprocessed `File` per request, and delete temporary capture/preprocessed files deterministically in `finally` blocks or attachment removal.
- **Composer Thumbnail & LiteRT-LM Multimodal Dispatch**:
  - Render the actual attached image thumbnail (with clear/remove action) inside the AI Chef composer bar prior to and during inference.
  - Dispatch multimodal turns to `LiteRtMultimodalEngine` (`com.google.ai.edge.litertlm:litertlm-android:0.16.1`) using:

    ```kotlin
    conversation.sendMessage(
      Contents.of(
        Content.ImageFile(preprocessedJpeg.absolutePath),
        Content.Text(groundedPrompt),
      )
    )
    ```

  - Strip raw Gemma control tokens (`<pad>`, `<unk>`, `<bos>`, `<eos>`, `<start_of_turn>`, `<end_of_turn>`, `<unused\d+>`) before schema validation.
- **Mandatory Non-Silent Vision Gate**:
  - Whenever an image is attached and `visionEnabled == false` (including text-only `.litertlm` GPU/CPU fallback, LiteRT `Session` fallback, MediaPipe `com.google.mediapipe:tasks-genai:0.10.22` `LlmInference`, or no loaded vision model), `AiEdgePort` MUST reject the call with `VisionNotSupportedException` and the UI MUST immediately surface a clear, non-silent state explaining why vision cannot run on the active model/backend and prompting the user to select a vision-capable `.litertlm` model or remove the photo.
  - **NEVER** silently drop an attached image and fall back to text-only generation.
- **Offline Voice Input (`AndroidVoiceInputAdapter`)**:
  - Implement `VoiceInputPort` on the Main thread via `android.speech.SpeechRecognizer` (`en-US` and `fr-FR` support with live partial transcripts) when `SpeechRecognizer.isRecognitionAvailable(context)` is true, with `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` fallback and the required Android 12+ `<queries>` declarations in `AndroidManifest.xml`.

## Module Sizing, KDoc, Comments & Personal-Repo Guardrail

- **Module Line Budgets (`.copilot_compliance.json`)**:
  - Respect `.copilot_compliance.json` (`default_max_lines: 700` logical executable code lines, excluding KDoc blocks, `//` comments, and blank lines).
  - Target cohesive single-responsibility files of `100–350` logical executable lines; split screens, bottom sheets, domain engines, and adapters by domain role rather than accumulating monolithic multi-screen or multi-engine files.
- **Mandatory KDoc & `>= 5%` Explanatory Inline Comments (`min_comment_ratio: 0.05`)**:
  - Write clear KDoc (`/** ... */`) on **every** public class, interface, `sealed interface`, `enum class`, `data class`, public property contract, and public/protected function.
  - Maintain `min_comment_ratio >= 0.05` (`>= 5%` `//` explanatory comment lines relative to logical executable lines) documenting *why* domain math (`1.4x` morning buffer, `ceil` package rounding, chronological Tier-1 reservation), concurrency `Mutex` invariants, and vision/privacy guardrails hold.
- **100% Hermetic JUnit Suite (`android/app/src/test/`)**:
  - Every domain and adapter change MUST maintain passing, hermetic, zero-emulator JUnit 4 + `kotlinx-coroutines-test` suites under `android/app/src/test/`:
    - All locked edge cases `EC-01` through `EC-13` (morning `<= 25m` cap with `1.4x` buffer, unavailable ingredient hard-hide, `substituteGroupId` auto-swap with in-stock tie-breaking and unit consistency across `FRUIT_TOPPING` / `LEAFY_GREENS`, cook-time overrides, household default normalization, ad-hoc extras, flat `1x` workout boosts, child-stage safety callouts, chronological 21-slot grocery reservation + `ceil` package rounding, floor-at-zero pantry depletion, Tier-2 `PLENTY | LOW | OUT` lifecycle, `COMPLETED_SKIPPED` reservation release, and instant in-stock swaps), plus weekly station prep and life-event food-safety guardrails.
    - `FakeKitchenStorage` `O(1)` atomic batch assertions (`batchWriteCallCount == 1`).
    - `SchemaValidator` and `AiMenuAdvisorEngine` untrusted LLM JSON grounding and rejection tests.
    - Multimodal vision-gate tests verifying `Content.ImageFile` forwarding when `visionEnabled == true`, immediate `VisionNotSupportedException` (zero silent fallback) when `visionEnabled == false` or on text-only `Session`/MediaPipe backends, and deterministic temp-file deletion.
- **Personal-Repo Zero-Leak Guardrail (`forbid_corporate_or_internal_leaks: true`)**:
  - This repository is strictly personal (`personal_project_only: true`). Every tracked and untracked text file MUST contain **zero** corporate email addresses or domains, internal shortlinks, internal monorepo/build tokens, internal infrastructure tool identifiers, workstation usernames/hostnames, or absolute workstation paths (ensure `local.properties`, `.gradle/`, `build/`, and model binaries remain gitignored).
  - Use clean repository-relative paths only, place a blank line before every list and fenced code block, and verify every change by running:

    ```bash
    node scripts/verify_clean_personal_repo.cjs
    ```
