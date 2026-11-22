# Family Kitchen Board (`SavorAI`) v2.0 — Single-Gemma Elephant & Goldfish + Google ADK Kotlin Architecture Blueprint (MAD)

> [!IMPORTANT]
>
> **Halt-Check Boundary Directive**: *"Should architectural drift or runtime failure require core interface alterations against MAD documents, halt explicitly requesting manual MAD updates before patching local improvisations silently."*

---

## 1. Architectural Vision, Methodology & Core Paradigm

- **Methodology & Paradigm**: `DomainFirst` + **Functional Core / Imperative Shell (Hexagonal Architecture)** in idiomatic Kotlin `2.2.0` (`JVM 17`, Jetpack Compose Material 3, `kotlinx.serialization`, `kotlinx.coroutines`).
- **Core Thesis**: Small on-device multimodal models (`gemma-4-E2B-it.litertlm` / `gemma-4-E4B-it.litertlm` on 8 GB–12 GB Android 12+ phones) degrade sharply when fed monolithic system prompts packed with 44 pantry items, 21 weekly board slots, 30 recipes, and rolling multi-turn chat transcripts. **SavorAI v2.0** eliminates prompt bloat and hallucinations by uniting **Dave Rensin's Elephant and Goldfish Model (EGM)** with **Google ADK for Kotlin (`com.google.adk.kt`)** deterministic graph patterns—executing **100% offline on a single resident Gemma 4 `LiteRT-LM` engine in RAM**:
  1. **The Elephant (`HouseholdElephantMemory` / ADK `MemoryService`)**: Durable, bounded household memory storing compact `@Serializable` semantic facts (`ElephantSemanticFact`, capped per category) and compressed weekly episodic rollups (`ElephantEpisodicDigest`). Updated deterministically from real household actions (`distillActionIntoElephantMemory`)—such as renaming `"Fresh Amaranth Greens"` to `"Fresh Kulitis"`, overriding weekly chicken purchases from `4 kg` to `2 kg`, saving household BOM defaults (`EC-05`), or skipping meal slots (`EC-12`)—and **never** dumped wholesale into an LLM prompt.
  2. **The Goldfish (`GoldfishAgentRole` / ADK `LlmAgent` Specialists)**: Stateless, task-scoped specialist personas (`PANTRY_EXPIRY_SPECIALIST`, `MENU_NUTRITION_SPECIALIST`, `GROCERY_BUDGET_SPECIALIST`, `VISION_INTAKE_SPECIALIST`, `COOK_COACH_SPECIALIST`, `SAFETY_CONSTRAINT_AUDITOR`). Each Goldfish wakes up with **complete conversational amnesia (`0` raw chat history)**, receives a surgical `GoldfishTaskBrief` containing **only** its role instruction, the current user intent, `<= 5` role-relevant `ElephantSemanticFact`s, and its allowed ADK tool schemas, runs on a fresh `LiteRT-LM` `Conversation`, and destroys its KV-cache immediately in `finally`.
  3. **Deterministic + Adversarial Critic Loop (`LoopAgent`, `maxIterations = 2`)**: Every meal-plan swap proposal passes through `audit_candidate_meal_swaps` (plus an optional fresh-eyes `SAFETY_CONSTRAINT_AUDITOR` Goldfish pass) enforcing `EC-01` (`<= 25m` weekday breakfast cap with `1.4x` buffer), `EC-02` (allergens & local availability), `EC-08` (non-blank child-stage texture safety tip), and active `HouseholdLifeEvent` temperature/safety rules. Violations emit structured `SafetyConstraintViolation` records for at most `1` targeted repair pass (or deterministic safe fallback) before surfacing a Human-in-the-Loop (`HITL`) confirmation card (`requiresUserConfirmation = true`, `0` unconfirmed state mutations).
- **Offline-First Default + Dormant Hexagonal Cloud & Search Ports**:
  - Defaults strictly to `AiExecutionMode.ON_DEVICE` (`LiteRtMultimodalEngine` + `OfflineDisabledWebSearchAdapter`, `0` network sockets).
  - Keeps `CloudCredentialVaultPort` (`KeystoreCloudCredentialVault` + optional Backend Proxy) and `WebSearchToolPort` (`KeystoreCloudWebSearchAdapter` guarded by `ALLOWED_SEARCH_HOSTS` HTTPS allowlisting and header-only credentials) cleanly coded behind hexagonal ports for opt-in future activation.

---

## 2. Single-Model On-Device RAM & KV-Cache Budget Math

Loading multiple concurrent specialist LLMs on a mobile phone is physically infeasible (`3 x 1.8 GB = 5.4 GB` causes immediate Android `LMKD` process termination). SavorAI v2.0 achieves multi-agent specialization using **one (`1x`) resident `.litertlm` weight allocation** and **sequential ephemeral `Conversation` KV-caches**:

```mermaid
flowchart LR
  User["Parent Prompt / Photo"] --> Step0["Step 0: < 5ms Reflex Fast-Path"]
  Step0 -->|Explicit Command| FastOut["AiToolCallResult (0 LLM Tokens)"]
  Step0 -->|Open-Ended / Advisory| Step1["Step 1: Elephant Triage & Slicing (<= 5 Facts, 0 Chat History)"]
  Step1 --> Step2["Step 2: Specialist Goldfish LlmAgent (Fresh Conversation #1 -> close())"]
  Step2 <-->|<= 2 Read-Only Calls| Tools["8 Bounded ADK Kitchen Tools + WebSearchToolPort"]
  Step2 --> Step3["Step 3: LoopAgent Safety Critic (audit_candidate_meal_swaps, maxIterations = 2)"]
  Step3 -->|APPROVED| Hitl["HITL Confirmation Proposal (0 Unconfirmed Mutations)"]
  Step3 -->|REJECTED_NEEDS_REPAIR (Iter 1)| Repair["Targeted Repair Goldfish (Violations Only -> Fresh Conversation #2 -> close())"]
  Repair --> Step3
```

### Physical Memory Equation (`8 GB` Android 12+ Baseline)

| Memory Component | V1 Monolithic Prompt (`3,200` tokens) | Naive Multi-Model Agents (`3` Engines) | **V2 Single-Gemma EGM Graph (`1` Engine)** |
| :--- | :--- | :--- | :--- |
| Resident `.litertlm` `int4` Weights + OpenCL Buffers | `1.75 GB` (`1x` Engine) | `5.25 GB` (`3x` Engines — **OOM**) | **`1.75 GB` (`1x` Resident `Engine` under `engineMutex`)** |
| Active Prompt Context Tokens per Turn | `~3,200` tokens (full pantry + board + chat) | `~1,800` tokens x `3` | **`~320 – 620` tokens (`GoldfishTaskBrief` + tool slice)** |
| Peak Transient KV-Cache Allocation | `~240 MB` (grows across follow-ups) | `~420 MB` retained concurrently | **`~42 – 64 MB` (`1` active `Conversation` at a time)** |
| Post-Node KV-Cache Retained in RAM | `~240 MB` until manual reset | `~420 MB` | **`0 MB` (`conversation.close()` inside `finally` per node)** |
| **Peak Total AI Footprint** | `~1.99 GB` (high attention dilution) | `~5.67 GB` (**crashes 8 GB device**) | **`~1.81 GB` (crisp attention, zero KV accumulation)** |

Invariant:

$$\text{RAM}_{\text{peak}} = \text{Weights}_{\text{Gemma4}} + \max_{k \in \text{GraphNodes}} \left(\text{KV}(T_{\text{Goldfish}, k})\right), \quad \text{with } T_{\text{Goldfish}, k} \le 650 \text{ tokens}$$

---

## 3. `visual_tree` — Hexagonal Package & Test Isolation Map

```text
family_kitchen_board/
├── AGENTS.md                                      # V2 architecture & 14-suite verification guide
├── docs/
│   └── architecture/
│       └── v2-elephant-goldfish-adk-mad.md        # Canonical V2 MAD blueprint (this document)
└── android/
    └── app/
        ├── build.gradle.kts                       # versionCode = 2, versionName = "2.0.0-alpha1" (100% offline-safe)
        ├── src/main/java/com/familykitchen/board/
        │   ├── domain/                            # 100% PURE KOTLIN + kotlinx.serialization ONLY (0 Android/IO imports)
        │   │   ├── KitchenModels.kt               # Core TIER_1/TIER_2, Recipe, MealSlot, WeeklyBoard, Grocery edit actions
        │   │   ├── HouseholdAndMarketModels.kt    # HouseholdProfile, ChildGrowthStage, HouseholdLifeEvent, PHP (₱) math
        │   │   ├── AiChefModels.kt                # AiMenuOptimizationProposal, AiSlotSwapSuggestion, VisualIntakeResult
        │   │   ├── CloudLlmModels.kt              # AiExecutionMode (ON_DEVICE default | CLOUD_API), secret redaction
        │   │   ├── EgmMemoryAndGraphModels.kt     # [NEW V2] Elephant semantic/episodic memory, Goldfish briefs, SafetyAuditVerdict
        │   │   ├── AdkKitchenToolsEngine.kt       # [NEW V2] 8 typed ADK tools, deterministic executor, tool-call JSON parser
        │   │   ├── AvailabilityFilter.kt          # EC-02 allergen/market hard-hide & EC-03 substituteGroupId auto-swap
        │   │   ├── PantryExpiryEngine.kt          # Zero-waste urgency cards, PHP (₱) value at risk, recipe pantry match
        │   │   ├── MenuPlannerEngine.kt           # EC-01 <= 25m 1.4x morning buffer, EC-08 child safety, EC-13 swaps
        │   │   └── GroceryDiffEngine.kt           # EC-09 chronological 21-slot reservation, pack overrides & trip skips
        │   ├── ports/                             # Pure coroutine hexagonal boundary interfaces
        │   │   ├── AiEdgePort.kt                  # Serialized text/vision inference + approval-gated model download
        │   │   ├── CloudCredentialVaultPort.kt    # Hardware-backed Keystore API key vault + execution mode state
        │   │   ├── KitchenStoragePort.kt          # O(1) atomic KitchenSnapshot persistence contract
        │   │   └── WebSearchToolPort.kt           # [NEW V2] Coroutine culinary web search port (offline-safe by default)
        │   ├── ai/                                # Imperative Shell AI orchestrators & hardware/network adapters
        │   │   ├── LiteRtMultimodalEngine.kt      # Single resident Gemma 4 Engine + per-call fresh Conversation teardown
        │   │   ├── HybridAiInferenceRouter.kt     # Seamless ON_DEVICE <-> CLOUD_API routing
        │   │   ├── WebSearchToolAdapters.kt       # [NEW V2] OfflineDisabledWebSearchAdapter + KeystoreCloudWebSearchAdapter
        │   │   ├── SingleGemmaEgmGraphRunner.kt   # [NEW V2] Step 0..3 Sequential + LoopAgent EGM orchestrator
        │   │   └── AiChefCoordinator.kt           # Multimodal vision gate & rolling chat turn coordinator
        │   ├── data/                              # LocalJsonKitchenStorage, KeystoreCloudCredentialVault, PDF exporter
        │   └── ui/                                # Stateless Material 3 Jetpack Compose screens, sheets & ViewModel
        └── src/test/java/com/familykitchen/board/
            └── domain/
                └── V2ElephantGoldfishAdkFoundationTest.kt # [NEW V2] 14th hermetic JUnit 4 suite (0 sockets, 0 emulator)
```

---

## 4. `typed_dependency_mapping` — Pure Contracts, ADK Tools & Port Schemas

### 4.1 Dependency Direction Rules (Zero Cycles)

- `domain/` -> depends **only** on Kotlin stdlib + `kotlinx.serialization.*`. Never imports `ports/`, `ai/`, `data/`, `ui/`, `android.*`, `java.io.*`, or `java.net.*`.
- `ports/` -> depends **only** on `domain/` + `kotlinx.coroutines.*` + `kotlinx.serialization.*`.
- `ai/` (`SingleGemmaEgmGraphRunner`, `WebSearchToolAdapters`) -> depends on `domain/` + `ports/` + standard JVM/coroutine primitives (`HttpsURLConnection` strictly inside allowlisted cloud adapter).

### 4.2 Core V2 Domain & Port Type Signatures

| Layer & File | Public Types & Pure / Coroutine Signatures | ADK Kotlin (`1.6.0`) Counterpart |
| :--- | :--- | :--- |
| `domain/EgmMemoryAndGraphModels.kt` | `ElephantFactCategory`, `ElephantSemanticFact`, `ElephantEpisodicDigest`, `HouseholdElephantMemory`, `ElephantDistillationEvent`, `distillActionIntoElephantMemory(...)`, `selectRelevantElephantFacts(...)` | `com.google.adk.memory.MemoryService` & `MemoryEntry` |
| `domain/EgmMemoryAndGraphModels.kt` | `GoldfishAgentRole`, `GoldfishTaskBrief`, `buildGoldfishTaskBrief(...)` (`<= 5` facts, `0` raw chat history) | `com.google.adk.agents.LlmAgent` instruction + scoped session state |
| `domain/EgmMemoryAndGraphModels.kt` | `SafetyAuditStatus`, `SafetyConstraintViolation`, `SafetyAuditVerdict`, `CandidateMealSwapDraft` | Structured `LoopAgent` escalation / critic state |
| `domain/EgmMemoryAndGraphModels.kt` | `WebSearchQuery`, `WebSearchHit`, `WebSearchToolOutcome`, `WebSearchToolResult` | Grounded external tool output contract |
| `domain/AdkKitchenToolsEngine.kt` | `AdkToolParameterSpec`, `AdkToolDeclaration`, `AdkToolCall`, `AdkToolResult`, `executeKitchenDomainTool(...)`, `auditCandidateMealSwaps(...)`, `parseModelToolCallOrNull(...)`, `routePromptToGoldfishRole(...)` | `com.google.adk.tools.BaseTool` & `FunctionDeclaration` |
| `ports/WebSearchToolPort.kt` | `interface WebSearchToolPort { suspend fun searchCulinaryWeb(query: WebSearchQuery): WebSearchToolResult }` | External search `BaseTool` adapter port |
| `ai/WebSearchToolAdapters.kt` | `OfflineDisabledWebSearchAdapter` (`0` sockets, `DISABLED_OFFLINE_MODE`), `KeystoreCloudWebSearchAdapter` (`ALLOWED_SEARCH_HOSTS`, dormant when `ON_DEVICE` or keyless) | Concrete offline/cloud `BaseTool` implementations |
| `ai/SingleGemmaEgmGraphRunner.kt` | `EgmGraphResolutionPath`, `EgmGraphTurnResult`, `SingleGemmaEgmGraphRunner.runTurn(...)` | `SequentialAgent` + `LoopAgent(maxIterations = 2)` + `InMemoryRunner` |

### 4.3 The 8 Bounded SavorAI ADK Tools (`AdkKitchenToolsEngine`)

1. `get_expiring_pantry_slice(withinDays)` — Queries `evaluatePantryExpiryDashboard` to return urgent Tier-1 stock with exact `expiresInDays`, storage zone, and `₱` value at risk.
2. `get_family_goals_and_events()` — Returns `HouseholdProfile.members` nutrition goals, `householdAllergens`, `youngestStage` (`ChildGrowthStage`), and `activeLifeEvents` food-safety rules.
3. `get_weekly_board_dishes(dayIndex?, slotType?)` — Resolves scheduled `MealSlot`s to actual `Recipe.title`, calibrated morning minutes, servings, and `MealSlotStatus` (eliminating v1's blind `slotId`-only prompt limitation).
4. `search_schedulable_recipes(slotType?, maxActiveMinutes?, mustUseIngredientId?)` — Filters via `filterSchedulableRecipes` enforcing `EC-01` (`<= 25m` breakfast cap) and `EC-02`/`EC-03` allergen-safe `substituteGroupId` rewrites.
5. `compute_smart_grocery_slice(maxItems?)` — Queries `computeWeeklyGroceryDiff` honoring `groceryQuantityOverrides`, `dismissedGroceryItemIds`, and prorated `₱` package costs.
6. `audit_candidate_meal_swaps(swaps)` — **Deterministic + Critic Safety Gate** checking candidate `slotId -> recipeId` pairs against `EC-01` (`<= 25m` morning cap), `EC-02` (allergens & local availability), `EC-08` (non-blank `toddlerPrepTip`), and `activeLifeEvents` cooking-temperature rules, returning `SafetyAuditVerdict`.
7. `propose_meal_board_swaps(swaps, summary)` — Builds a confirmation-gated `AiMenuOptimizationProposal` (`requiresUserConfirmation = true`, zero direct snapshot mutation).
8. `web_culinary_search(query, maxResults)` — Schema declaration for external recipe or regional market price lookup, delegated at the imperative shell (`SingleGemmaEgmGraphRunner`) to `WebSearchToolPort`.

### 4.4 Phase-2 Native Google ADK for Kotlin (`1.6.0`) Gradle Wiring

Phase 1 keeps `android/app/build.gradle.kts` 100% offline/Mac build-safe with existing cached dependencies (`versionCode = 2`, `versionName = "2.0.0-alpha1"`). In Phase 2, once Maven Central / Google Maven mirrors publish the `1.6.0` artifacts to the offline build cache, wire the thin ADK bridge in `android/app/build.gradle.kts` without touching a single line of `domain/`:

```kotlin
dependencies {
  // Phase-2 Google ADK for Kotlin graph runtime & LiteRT-LM on-device model adapter
  implementation("com.google.adk:google-adk-kotlin-core:1.6.0")
  implementation("com.google.adk:google-adk-kotlin-litertlm:1.6.0")
}
```

In Phase 2, each `AdkToolDeclaration` in `AdkKitchenToolsEngine.allToolDeclarations()` maps 1:1 to a `com.google.adk.tools.BaseTool` subclass delegating directly to `executeKitchenDomainTool`, while `SingleGemmaEgmGraphRunner` swaps its internal coroutine step loop for `SequentialAgent` + `LoopAgent` driven by `InMemoryRunner` over the shared `LiteRtMultimodalEngine` handle.

---

## 5. `implementation_dag` — 5-Step Autonomous Execution Plan

> [!WARNING]
>
> **Mandatory Halt-Check Warning**: *"Should architectural drift or runtime failure require core interface alterations against MAD documents, halt explicitly requesting manual MAD updates before patching local improvisations silently."*

1. **Step 1 — Zero-Dependency Pure Domain EGM Memory & Safety Contracts (`domain/EgmMemoryAndGraphModels.kt`)**:
   - Implement `@Serializable` `ElephantFactCategory`, `ElephantSemanticFact`, `ElephantEpisodicDigest`, `HouseholdElephantMemory`, `GoldfishAgentRole`, `GoldfishTaskBrief`, `SafetyAuditStatus`, `SafetyConstraintViolation`, `SafetyAuditVerdict`, and `WebSearchQuery`/`WebSearchHit`/`WebSearchToolOutcome`/`WebSearchToolResult` with zero external I/O or Android imports.
   - Implement pure deterministic `distillActionIntoElephantMemory`, `selectRelevantElephantFacts(memory, role, maxFacts = 5)`, and `buildGoldfishTaskBrief(...)` (`0` raw chat history).
2. **Step 2 — Pure Deterministic 8-Tool ADK Engine & Parser (`domain/AdkKitchenToolsEngine.kt`)**:
   - Implement `AdkToolParameterSpec`, `AdkToolDeclaration`, `AdkToolCall`, `AdkToolResult`, `executeKitchenDomainTool`, `auditCandidateMealSwaps`, `parseModelToolCallOrNull` (`<|tool_call|>` and fenced JSON), and `routePromptToGoldfishRole`.
   - Enforce `requiresUserConfirmation = true` on `propose_meal_board_swaps` and strict `EC-01`/`EC-02`/`EC-08`/life-event checks in `audit_candidate_meal_swaps`.
3. **Step 3 — Hexagonal Culinary Web Search Port & Offline/Cloud Adapters (`ports/WebSearchToolPort.kt`, `ai/WebSearchToolAdapters.kt`)**:
   - Define coroutine `WebSearchToolPort` and implement `OfflineDisabledWebSearchAdapter` (default active adapter in local Gemma mode; `0` sockets) plus `KeystoreCloudWebSearchAdapter` (strictly gated behind `AiExecutionMode.CLOUD_API`, `CloudCredentialVaultPort`, header-only auth, and `ALLOWED_SEARCH_HOSTS`).
4. **Step 4 — Single-Gemma Elephant & Goldfish Graph Runner (`ai/SingleGemmaEgmGraphRunner.kt`)**:
   - Orchestrate Step 0 (`< 5ms` deterministic reflex fast-path), Step 1 (Elephant role triage + `<= 5` semantic fact slicing), Step 2 (stateless specialist Goldfish pass on `AiEdgePort.generateText` with up to `2` read-only tool calls), and Step 3 (`LoopAgent` adversarial critic with `maxIterations = 2` feeding only `SafetyConstraintViolation`s into `1` repair pass or safe deterministic fallback).
   - Isolate configuration bindings (`AiExecutionMode`, `EdgeDeviceProfile`, `maxLoopIterations`, `maxToolCallsPerTurn`) cleanly at runner construction boundaries.
5. **Step 5 — Hermetic 14th JUnit 4 Verification Suite & Repo Hygiene Gates (`V2ElephantGoldfishAdkFoundationTest.kt`)**:
   - Verify Goldfish context isolation (`0` leaked chat turns), on-demand `AdkKitchenToolsEngine` slices (member goals, resolved scheduled dish titles, `expiresInDays`), `LoopAgent` iteration-1 rejection of `> 25m` breakfast (`EC-01`) + allergen (`EC-02`) swaps repaired to valid `<= 25m` allergen-safe swaps on iteration 2 (`0` unconfirmed mutations), `ElephantMemory` distillation (`"Fresh Kulitis"` rename & `2 kg` chicken override), and `OfflineDisabledWebSearchAdapter` / `KeystoreCloudWebSearchAdapter` offline guarantees.
   - Execute `./gradlew testDebugUnitTest`, `./gradlew assembleDebug`, and `node scripts/verify_clean_personal_repo.cjs` on branch `v2-adk-elephant-goldfish` while leaving `main` untouched at `2799e6f`.
