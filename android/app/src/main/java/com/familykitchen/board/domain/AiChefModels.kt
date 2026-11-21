package com.familykitchen.board.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Pure Kotlin AI Chef domain contracts, Galaxy S22 / S22 Ultra hardware profiles, and UDF actions.
// Keeps multimodal visual intake and proposal models 100% decoupled from LiteRT-LM JNI classes.

/**
 * Nutritional optimization focus mode inferred from natural-language prompts in AI Chef.
 *
 * @property displayLabel Concise UI badge title shown on AI Chef proposal cards.
 */
@Serializable
enum class NutritionFocusTag(val displayLabel: String) {
  // Default pediatric neurodevelopment mode emphasizing Choline, marine DHA, and heme/non-heme Iron.
  /** Prioritizes Choline, Omega-3 DHA, Iron, and Lutein for the 8-year-old and 2-year-old. */
  KIDS_BRAIN_BOOSTER("Kids Brain Boosters (Choline, DHA & Iron)"),

  // Fridge-clearing mode weighting in-stock Tier-1 perishables at 2x score priority.
  /** Prioritizes in-stock Tier-1 perishables so nothing in the fridge spoils mid-week. */
  PANTRY_ZERO_WASTE("Zero-Waste Pantry Perishables"),

  // Weekday rush mode awarding +12 bonus points to recipes under 20 calibrated minutes.
  /** Enforces extra-fast `< 20m` calibrated weekday morning breakfasts for Fred. */
  QUICK_MORNING_UNDER_20M("Express Morning (< 20m Active)"),

  // Pairs spinach, legumes, and poultry with citrus/bell-pepper Vitamin C co-factors.
  /** Pairs heme/non-heme iron sources with Vitamin-C produce for 2x iron absorption. */
  IRON_AND_IMMUNITY("High-Iron + Vitamin C Immunity"),

  // Athletic hypertrophy mode prioritizing recipes with Dad workout modifiers.
  /** Emphasizes adult athletic hypertrophy and post-workout lean protein recovery. */
  HIGH_PROTEIN_FAMILY("High-Protein Family & Athletic Recovery"),
}

/**
 * Pre-calibrated on-device Edge AI hardware and model profiles targeting Samsung Galaxy S22
 * and Galaxy S22 Ultra (`minSdk = 31`, Adreno 730 / Exynos 2200 OpenCL GPU + CPU fallback).
 *
 * @property deviceId Stable profile identifier persisted in user preferences.
 * @property displayName Human-readable hardware + model profile selector label.
 * @property minAndroidApiLevel Minimum Android SDK level (`31` / Android 12+).
 * @property preferredDelegate Preferred LiteRT-LM hardware delegate (`"GPU"` with `"CPU"` fallback).
 * @property recommendedModelFileName Suggested `.litertlm` package filename for SAF import.
 * @property quantization Weight quantization scheme (`"int4"`).
 * @property maxTokens Generation context budget (`>= 1024` so JSON proposals never truncate).
 * @property supportsMultimodalVision `true` when the recommended model processes image inputs.
 * @property supportsAudioScribe `true` when paired with offline `SpeechRecognizer` voice input.
 */
@Serializable
enum class EdgeDeviceProfile(
  val deviceId: String,
  val displayName: String,
  val minAndroidApiLevel: Int,
  val preferredDelegate: String,
  val recommendedModelFileName: String,
  val quantization: String,
  val maxTokens: Int,
  val supportsMultimodalVision: Boolean,
  val supportsAudioScribe: Boolean,
) {
  // Base S22 profile: 8GB RAM budget favors 1B int4 weights at 1024 max output tokens.
  /** Compact 1B int4 text model tailored for base Galaxy S22 8GB RAM headroom. */
  GALAXY_S22_GEMMA_1B_INT4(
    deviceId = "samsung_galaxy_s22_gemma_1b_int4",
    displayName = "Galaxy S22 (Gemma 1B int4 GPU)",
    minAndroidApiLevel = 31,
    preferredDelegate = "GPU",
    recommendedModelFileName = "gemma-3-1b-it-gpu-int4.litertlm",
    quantization = "int4",
    maxTokens = 1024,
    supportsMultimodalVision = false,
    supportsAudioScribe = true,
  ),

  // S22 Ultra profile: 12GB RAM comfortably hosts 2B int4 weights with 1536 max tokens.
  /** Higher-capacity 2B int4 text model tailored for Galaxy S22 Ultra 12GB RAM. */
  GALAXY_S22_ULTRA_GEMMA_2B_INT4(
    deviceId = "samsung_galaxy_s22_ultra_gemma_2b_int4",
    displayName = "Galaxy S22 Ultra (Gemma 2B int4 GPU)",
    minAndroidApiLevel = 31,
    preferredDelegate = "GPU",
    recommendedModelFileName = "gemma2-2b-it-gpu-int4.litertlm",
    quantization = "int4",
    maxTokens = 1536,
    supportsMultimodalVision = false,
    supportsAudioScribe = true,
  ),

  // Multimodal E2B profile: unlocks authentic Content.ImageFile camera and gallery scanning.
  /** Multimodal Gemma E2B int4 `.litertlm` profile enabling real camera and gallery vision scans. */
  GEMMA_4_E2B_MULTIMODAL(
    deviceId = "gemma_4_e2b_multimodal_int4",
    displayName = "Gemma E2B Multimodal Vision (S22 / S22 Ultra GPU)",
    minAndroidApiLevel = 31,
    preferredDelegate = "GPU",
    recommendedModelFileName = "gemma-e2b-it-multimodal-int4.litertlm",
    quantization = "int4",
    maxTokens = 1536,
    supportsMultimodalVision = true,
    supportsAudioScribe = true,
  ),
}

/**
 * Concrete pantry-grounded meal swap suggestion for a scheduled [WeeklyBoard] slot.
 *
 * @property slotId Target [MealSlot.slotId] on the active weekly board.
 * @property dayIndex 0-based day of the week (`0 = Monday`..`6 = Sunday`).
 * @property slotType Meal slot type (`BREAKFAST` swaps strictly enforce `<= 25m` per `EC-01`).
 * @property previousRecipeId Currently scheduled recipe ID before applying the swap.
 * @property suggestedRecipe Replacement [Recipe] grounded in the local catalog.
 * @property rationale Concise explanation referencing in-stock items and active cook time.
 * @property brainBoosterNutrients Highlighted pediatric/family micronutrients in the dish.
 * @property usesInStockIngredientsIds Catalog IDs from the recipe currently available in stock.
 * @property familyGoalPlating Dynamic 4-member 1-pot plating guide for this meal.
 */
@Serializable
data class AiSlotSwapSuggestion(
  val slotId: String,
  val dayIndex: Int,
  val slotType: MealSlotType,
  val previousRecipeId: String,
  val suggestedRecipe: Recipe,
  val rationale: String,
  val brainBoosterNutrients: List<String> = emptyList(),
  val usesInStockIngredientsIds: List<String> = emptyList(),
  val familyGoalPlating: Map<String, String> = emptyMap(),
)

/**
 * Curated brain-boosting or menu-optimizing ingredient suggestion with a 1-tap grocery [RestockDelta].
 *
 * @property item Catalog definition for the suggested ingredient.
 * @property recommendedPackageCount Whole-package count recommended for purchase.
 * @property restockDelta Pre-computed whole-package restock delta.
 * @property brainBoosterBenefit Developmental or nutritional benefit explanation.
 * @property pairedRecipeTitle Example family meal that pairs with this ingredient.
 */
@Serializable
data class AiNewIngredientSuggestion(
  val item: IngredientCatalogItem,
  val recommendedPackageCount: Int = 1,
  val restockDelta: RestockDelta,
  val brainBoosterBenefit: String,
  val pairedRecipeTitle: String,
)

/**
 * Rich interactive LLM recipe suggestion card rendered inside `SavorAI` (`ai_assistant` spec).
 *
 * @property recipe Catalog-grounded [Recipe] ready for 1-tap Cook Mode, Meal Plan, or Save.
 * @property matchSummary Live pantry readiness, missing staples, and rescued expiring value.
 * @property substitutionTip Optional Sous-Chef culinary substitution rule with calorie/macro delta.
 */
@Serializable
data class AiRichRecipeCard(
  val recipe: Recipe,
  val matchSummary: RecipePantryMatchSummary,
  val substitutionTip: String? = null,
)

/**
 * Actionable batch menu optimization and ingredient discovery proposal from AI Chef.
 *
 * @property proposalId Stable identifier for tracking applied state in UI cards.
 * @property focusTag Active [NutritionFocusTag] driving scoring and recommendations.
 * @property assistantSummary Conversational summary rendered inside the assistant chat bubble.
 * @property slotSwaps Up to 3 pantry-grounded meal swap suggestions across the week.
 * @property newIngredientSuggestions Allergen-safe nutrient-dense grocery discovery items.
 * @property isConversationalOnly When `true`, suppresses swap/grocery action cards in the chat feed.
 * @property followUpPrompts Quick-reply prompt chips displayed beneath the assistant turn.
 * @property featuredRecipeCard Optional hero [AiRichRecipeCard] with pantry match and rescue savings.
 */
@Serializable
data class AiMenuOptimizationProposal(
  val proposalId: String,
  val focusTag: NutritionFocusTag,
  val assistantSummary: String,
  val slotSwaps: List<AiSlotSwapSuggestion> = emptyList(),
  val newIngredientSuggestions: List<AiNewIngredientSuggestion> = emptyList(),
  val isConversationalOnly: Boolean = false,
  val followUpPrompts: List<String> = emptyList(),
  val featuredRecipeCard: AiRichRecipeCard? = null,
)

/**
 * Single ingredient or food item detected during authentic multimodal visual inspection of a photo.
 *
 * @property rawLabel Raw label reported by the on-device vision model.
 * @property matchedIngredientId Grounded `ingredientId` in `SeedCatalogDataset`, or `null` if unknown.
 * @property matchedDisplayName Canonical catalog display name when grounded.
 * @property estimatedQuantity Estimated quantity in canonical [unit] (falls back to 1 package).
 * @property unit Canonical [BaseUnit] when matched to a catalog item.
 * @property confidence Model confidence in `0.0..1.0`.
 */
@Serializable
data class VisualDetectedItem(
  val rawLabel: String,
  val matchedIngredientId: String? = null,
  val matchedDisplayName: String? = null,
  val estimatedQuantity: Double = 0.0,
  val unit: BaseUnit? = null,
  val confidence: Double = 1.0,
)

/**
 * Destination target when committing user-selected items from a [VisualIntakeResult] photo scan.
 */
@Serializable
enum class IntakeCommitTarget {
  // Commits selected items directly into PantryLedgerEntry stock (PLENTY / +1 package).
  /** Stocks selected items directly into the Household Pantry Ledger. */
  PANTRY,

  // Queues selected items onto Smart Grocery List (aiAddedGroceryIds) without mutating stock.
  /** Queues selected items onto the Smart Grocery List for upcoming shopping. */
  GROCERY,
}

/**
 * Validated, catalog-grounded result of scanning a kitchen fridge, counter, spice rack, or receipt photo.
 *
 * Never mutates pantry or grocery state automatically; the user must explicitly confirm the
 * grounded proposal in `AiChefScreen`.
 *
 * @property summary Human-readable summary of what was visually observed in the image.
 * @property detectedItems Structured items detected in the photo.
 * @property groundedRestockDeltas Validated [RestockDelta]s for existing catalog matches.
 * @property unrecognizedLabels Honest list of visible items not present in the household catalog.
 * @property suggestedProposal Optional follow-up meal proposal grounded in the detected items.
 * @property proposedNewCatalogItems Newly synthesized culinary catalog items for unmatched foods/spices.
 */
@Serializable
data class VisualIntakeResult(
  val summary: String,
  val detectedItems: List<VisualDetectedItem> = emptyList(),
  val groundedRestockDeltas: List<RestockDelta> = emptyList(),
  val unrecognizedLabels: List<String> = emptyList(),
  val suggestedProposal: AiMenuOptimizationProposal? = null,
  val proposedNewCatalogItems: List<IngredientCatalogItem> = emptyList(),
)

/**
 * Deterministic result of an explicit user text command routed to an on-device kitchen tool
 * (`restock_pantry_batch`, `deplete_pantry_items`, `queue_grocery_items`, `activate_life_event`,
 * `schedule_family_meal`).
 *
 * @property toolName Canonical tool function name invoked (`"restock_pantry_batch"`, etc.).
 * @property summaryMessage Assistant confirmation message rendered in the chat feed.
 * @property toastMessage Short confirmation banner message shown at the top of the scaffold.
 * @property restockDeltas Batch pantry restock deltas produced by `restock_pantry_batch`.
 * @property queuedGroceryIngredientIds Catalog IDs queued by `queue_grocery_items`.
 * @property activatedLifeEvent Life event activated by `activate_life_event`.
 * @property scheduledDayIndex Target weekday (`0..6`) for `schedule_family_meal`.
 * @property scheduledSlotType Target [MealSlotType] for `schedule_family_meal`.
 * @property scheduledRecipeId Target [Recipe.recipeId] for `schedule_family_meal`.
 * @property followUpPrompts Suggested next-step chips after executing the tool.
 * @property synthesizedCatalogItems Newly synthesized catalog definitions created on behalf of user.
 * @property outOfStockIngredientIds Catalog IDs marked out of stock by `deplete_pantry_items`.
 */
@Serializable
data class AiToolCallResult(
  val toolName: String,
  val summaryMessage: String,
  val toastMessage: String,
  val restockDeltas: List<RestockDelta> = emptyList(),
  val queuedGroceryIngredientIds: List<String> = emptyList(),
  val activatedLifeEvent: HouseholdLifeEvent? = null,
  val scheduledDayIndex: Int? = null,
  val scheduledSlotType: MealSlotType? = null,
  val scheduledRecipeId: String? = null,
  val followUpPrompts: List<String> = emptyList(),
  val synthesizedCatalogItems: List<IngredientCatalogItem> = emptyList(),
  val outOfStockIngredientIds: List<String> = emptyList(),
)

/**
 * Speaker role for turns persisted in the rolling AI Chef chat transcript.
 */
@Serializable
enum class AiChatRole {
  // Serialized in lowercase to preserve 1:1 compatibility with historical JSON snapshots.
  /** Prompt or photo submitted by a household member. */
  @SerialName("user")
  USER,

  /** Grounded response, visual scan report, or tool result produced by AI Chef. */
  @SerialName("assistant")
  ASSISTANT,
}

/**
 * Immutable conversational turn inside the rolling AI Chef chat history.
 *
 * @property turnId Unique turn identifier.
 * @property role Author role (`USER` or `ASSISTANT`).
 * @property messageText Plain-text message body rendered in Compose `Text`.
 * @property createdAtIso ISO-8601 creation timestamp.
 * @property attachedImageLabel Optional display filename/badge when the turn included a photo.
 * @property proposal Optional actionable menu/grocery optimization proposal card.
 * @property visualIntakeResult Optional grounded photo scan confirmation card.
 * @property toolCallResult Optional deterministic tool execution summary metadata.
 * @property responseSourceLabel Optional concise provenance label (`"gemma-4-E2B-it"`, `"Offline Advisor"`, etc.).
 */
@Serializable
data class AiChatTurn(
  val turnId: String,
  val role: AiChatRole,
  val messageText: String,
  val createdAtIso: String,
  val attachedImageLabel: String? = null,
  val proposal: AiMenuOptimizationProposal? = null,
  val visualIntakeResult: VisualIntakeResult? = null,
  val toolCallResult: AiToolCallResult? = null,
  val responseSourceLabel: String? = null,
)

/**
 * Unidirectional presentation intents emitted from `AiChefScreen` to `KitchenBoardViewModel`.
 */
sealed interface AiChefAction {
  // Explicit per-turn attachedImagePath eliminates legacy @Volatile global image side-channels.
  /** Submits a text prompt and optional preprocessed image path for inference. */
  data class SubmitPrompt(val promptText: String, val attachedImagePath: String? = null) : AiChefAction

  /** Populates or dispatches one of the curated quick-prompt suggestion chips. */
  data class SelectQuickPrompt(val promptText: String) : AiChefAction

  /** Attaches a captured camera JPEG or picked gallery URI for preview prior to sending. */
  data class AttachImage(
    val sourceUriOrPath: String,
    val displayLabel: String = "Attached Photo",
  ) : AiChefAction

  /** Removes the currently staged image preview chip and cleans up its temp file. */
  data object ClearAttachedImage : AiChefAction

  /** Switches the active Samsung S22 / S22 Ultra / Gemma E2B hardware profile. */
  data class SelectDeviceProfile(val profile: EdgeDeviceProfile) : AiChefAction

  /** Imports a user-selected `.litertlm`, `.task`, or `.bin` model via SAF `OpenDocument`. */
  data class ImportModelUri(val sourceUriString: String, val displayName: String? = null) : AiChefAction

  // Proposal commit actions execute as single O(1) atomic storage batch writes.
  /** Commits all suggested meal swaps in [proposal] to the weekly board in 1 atomic batch. */
  data class ApplyMealSwaps(val proposal: AiMenuOptimizationProposal) : AiChefAction

  /** Queues all suggested brain-booster ingredients in [proposal] onto the Smart Grocery List. */
  data class QueueSuggestedIngredients(val proposal: AiMenuOptimizationProposal) : AiChefAction

  /** Confirms and commits grounded [VisualIntakeResult.groundedRestockDeltas] into the pantry. */
  data class ConfirmVisualRestock(val intakeResult: VisualIntakeResult) : AiChefAction

  /**
   * Commits user-selected catalog IDs and newly discovered food/spice labels from [intakeResult]
   * into either [IntakeCommitTarget.PANTRY] or [IntakeCommitTarget.GROCERY] in 1 `O(1)` write.
   *
   * @property turnId Originating chat turn identifier marked confirmed on commit.
   * @property intakeResult Grounded visual inspection result containing matched and new items.
   * @property selectedIngredientIds Selected catalog or proposed `ingredientId`s.
   * @property selectedNewLabels Selected raw or display labels for newly discovered items.
   * @property destination Target kitchen surface (`PANTRY` or `GROCERY`).
   */
  data class CommitVisualIntakeSelection(
    val turnId: String,
    val intakeResult: VisualIntakeResult,
    val selectedIngredientIds: Set<String>,
    val selectedNewLabels: List<String>,
    val destination: IntakeCommitTarget = IntakeCommitTarget.PANTRY,
  ) : AiChefAction

  /** Restores the pre-commit household snapshot from the latest Sous-Chef mutation (`O(1)` write). */
  data object UndoLastAgentAction : AiChefAction

  /** Dismisses the inline Sous-Chef Undo/Adjust confirmation banner without reverting state. */
  data object DismissAgentActionBanner : AiChefAction

  // Voice recognition actions route to VoiceInputPort on the Main thread (`en-US` / `fr-FR`).
  /** Starts offline voice recognition in `"en-US"` or `"fr-FR"`. */
  data class StartVoiceInput(val languageTag: String = "en-US") : AiChefAction

  /** Stops an active voice recognition session. */
  data object StopVoiceInput : AiChefAction

  /** Clears the rolling chat history back to the initial welcome state. */
  data object ResetConversation : AiChefAction

  // Rich SavorAI recipe suggestion card & context banner actions (`ai_assistant` UI spec).
  /** Launches full-screen Step-by-Step Cook Mode directly from an AI Chef recipe card. */
  data class StartCookModeFromAi(
    val recipe: Recipe,
    val slotId: String? = null,
  ) : AiChefAction

  /** Schedules [recipe] onto the active `WeeklyBoard` in a single `O(1)` batch write. */
  data class AddRecipeToPlanFromAi(
    val recipe: Recipe,
    val dayIndex: Int? = null,
    val slotType: MealSlotType? = null,
  ) : AiChefAction

  /** Bookmarks or unbookmarks [recipeId] in `KitchenSnapshot.savedRecipeIds` (`O(1)` write). */
  data class ToggleSaveRecipe(val recipeId: String) : AiChefAction

  /** Rewrites [fromIngredientId] to [toIngredientId] on [recipeId] and updates macro estimates. */
  data class ApplyRecipeSubstitution(
    val recipeId: String,
    val fromIngredientId: String,
    val toIngredientId: String,
  ) : AiChefAction

  /** Queues missing recipe staples onto the Smart Grocery List in 1 atomic write. */
  data class QueueMissingRecipeStaples(
    val deltas: List<RestockDelta>,
    val ingredientIds: List<String> = deltas.map { it.ingredientId },
  ) : AiChefAction

  /** Dismisses the top `KITCHEN CONTEXT SYNCED` status banner inside `AiChefScreen`. */
  data object DismissContextBanner : AiChefAction

  // Dual-mode On-Device + Cloud API Key configuration actions routed to CloudCredentialVaultPort.
  /** Switches between local [AiExecutionMode.ON_DEVICE] and [AiExecutionMode.CLOUD_API] execution. */
  data class SelectAiExecutionMode(val mode: AiExecutionMode) : AiChefAction

  /** Selects the active [CloudLlmProvider] (`GEMINI`, `OPENAI`, `ANTHROPIC`, or `OPENROUTER`). */
  data class SelectCloudProvider(val provider: CloudLlmProvider) : AiChefAction

  /** Updates the active cloud model identifier for the selected [CloudLlmProvider]. */
  data class UpdateCloudModelId(val modelId: String) : AiChefAction

  /**
   * Encrypts and stores [rawApiKey] for [provider] in the credential vault, switches execution to
   * [AiExecutionMode.CLOUD_API], optionally updates [modelId], and optionally runs [verifyConnection].
   *
   * @property provider Target cloud provider receiving the credential.
   * @property rawApiKey User-supplied API key (scrubbed from UI memory immediately on dispatch).
   * @property modelId Optional model identifier override saved alongside the key.
   * @property verifyConnection When `true`, runs a lightweight connectivity check after saving.
   */
  data class SaveCloudApiKey(
    val provider: CloudLlmProvider,
    val rawApiKey: String,
    val modelId: String? = null,
    val verifyConnection: Boolean = true,
  ) : AiChefAction

  /** Deletes the stored API key for [provider] from the credential vault. */
  data class ClearCloudApiKey(val provider: CloudLlmProvider) : AiChefAction

  /** Runs a lightweight health check against the active [CloudLlmProvider] and stored API key. */
  data object VerifyCloudConnection : AiChefAction

  /** Fetches the live non-deprecated `/models` catalog for the active [CloudLlmProvider]. */
  data object RefreshCloudModels : AiChefAction

  // Approval-gated Hugging Face Gemma 4 (.litertlm) download & cancellation intents.
  /**
   * Inspects free storage, network metering, and resumable `.part` state for [preset] to populate
   * `ModelDownloadApprovalDialog` before any network connection is opened.
   *
   * @property preset Candidate Gemma 4 `.litertlm` preset (`GEMMA_4_E2B_VISION` default).
   */
  data class RequestModelDownloadApproval(
    val preset: DownloadableGemmaPreset = DownloadableGemmaPreset.GEMMA_4_E2B_VISION,
  ) : AiChefAction

  /**
   * Confirms user approval and starts (or resumes via HTTP `Range`) downloading [preset].
   *
   * @property preset User-approved Gemma 4 `.litertlm` package preset.
   */
  data class ConfirmApprovedModelDownload(
    val preset: DownloadableGemmaPreset,
  ) : AiChefAction

  /** Dismisses `ModelDownloadApprovalDialog` without opening any network connection. */
  data object DismissModelDownloadApproval : AiChefAction

  /** Cancels the active background Gemma 4 download and cleans up its incomplete `.part` file. */
  data object CancelActiveModelDownload : AiChefAction
}

// Curated single-ingredient discovery items filtered against householdAllergens before surfacing in
// AI Chef cards. Every candidate carries a pre-computed whole-package RestockDelta for 1-tap queueing.
internal val CURATED_BRAIN_BOOSTER_CANDIDATES: List<AiNewIngredientSuggestion> = listOf(
  // Omega-3 ALA + soluble fiber chia topper safe for soaked toddler porridge and 8yo parfaits.
  AiNewIngredientSuggestion(
    item = IngredientCatalogItem(
      ingredientId = "ing_disc_chia_flax",
      displayName = "Organic Chia Seeds",
      category = IngredientCategory.PANTRY_STAPLE,
      tier = IngredientTier.TIER_1_QUANTITATIVE,
      baseUnit = BaseUnit.GRAM,
      defaultPackageQuantity = 500.0,
      estimatedPackagePriceUsd = 265.0,
    ),
    recommendedPackageCount = 1,
    restockDelta = RestockDelta("ing_disc_chia_flax", 500.0),
    brainBoosterBenefit =
      "Packed with plant-based Omega-3 ALA, calcium, and soluble fiber to sustain 8yo classroom focus and 2yo neural growth.",
    pairedRecipeTitle = "5-Min Night-Before Overnight Oats",
  ),
  // Finely milled golden flaxseed delivering bioavailable ALA lignans with zero whole-seed texture risk.
  AiNewIngredientSuggestion(
    item = IngredientCatalogItem(
      ingredientId = "ing_disc_milled_flax",
      displayName = "Milled Golden Flaxseed",
      category = IngredientCategory.PANTRY_STAPLE,
      tier = IngredientTier.TIER_1_QUANTITATIVE,
      baseUnit = BaseUnit.GRAM,
      defaultPackageQuantity = 400.0,
      estimatedPackagePriceUsd = 225.0,
    ),
    recommendedPackageCount = 1,
    restockDelta = RestockDelta("ing_disc_milled_flax", 400.0),
    brainBoosterBenefit =
      "Finely milled for maximum Omega-3 ALA absorption and Iron support in toddler oatmeal and granola bowls.",
    pairedRecipeTitle = "15-Min Skillet Honey-Oat Granola",
  ),
  // Marine Omega-3 DHA, Choline, and Vitamin D pantry fish option (tagged FISH for allergen gate).
  AiNewIngredientSuggestion(
    item = IngredientCatalogItem(
      ingredientId = "ing_disc_sardines_salmon",
      displayName = "Sardine Fillets in Olive Oil",
      category = IngredientCategory.PROTEIN,
      tier = IngredientTier.TIER_1_QUANTITATIVE,
      baseUnit = BaseUnit.GRAM,
      defaultPackageQuantity = 400.0,
      allergens = listOf("FISH"),
      defaultStorageLocation = StorageLocation.PANTRY,
      defaultShelfLifeDays = 365,
      estimatedPackagePriceUsd = 240.0,
    ),
    recommendedPackageCount = 1,
    restockDelta = RestockDelta("ing_disc_sardines_salmon", 400.0),
    brainBoosterBenefit =
      "Delivers bioavailable marine Omega-3 DHA, Choline, and Vitamin D for cognitive memory and visual development.",
    pairedRecipeTitle = "Tuna & Olive Tapenade Baguette",
  ),
  // Smooth roasted almond spread delivering Vitamin E with zero whole-nut choking risk (tagged NUTS).
  AiNewIngredientSuggestion(
    item = IngredientCatalogItem(
      ingredientId = "ing_disc_walnut_kasuy",
      displayName = "Smooth Almond Butter",
      category = IngredientCategory.PANTRY_STAPLE,
      tier = IngredientTier.TIER_1_QUANTITATIVE,
      baseUnit = BaseUnit.GRAM,
      defaultPackageQuantity = 400.0,
      allergens = listOf("NUTS"),
      estimatedPackagePriceUsd = 340.0,
    ),
    recommendedPackageCount = 1,
    restockDelta = RestockDelta("ing_disc_walnut_kasuy", 400.0),
    brainBoosterBenefit =
      "Provides Vitamin E, magnesium, and healthy fats in a smooth 2yo-safe texture with zero whole-nut choking risk.",
    pairedRecipeTitle = "Warm Cinnamon Oat Porridge",
  ),
  // Local pure unsweetened tablea cacao flavanol antioxidants + plant iron supporting focus.
  AiNewIngredientSuggestion(
    item = IngredientCatalogItem(
      ingredientId = "ing_disc_cacao_lentils",
      displayName = "Unsweetened Tablea Cacao",
      category = IngredientCategory.PANTRY_STAPLE,
      tier = IngredientTier.TIER_1_QUANTITATIVE,
      baseUnit = BaseUnit.GRAM,
      defaultPackageQuantity = 250.0,
      estimatedPackagePriceUsd = 220.0,
    ),
    recommendedPackageCount = 1,
    restockDelta = RestockDelta("ing_disc_cacao_lentils", 250.0),
    brainBoosterBenefit =
      "Rich in cacao flavanol antioxidants for cerebral blood flow and plant-based Iron for sustained energy.",
    pairedRecipeTitle = "Sheet-Pan Banana Pancakes",
  ),
)
