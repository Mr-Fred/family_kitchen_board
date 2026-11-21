package com.familykitchen.board.domain

import kotlinx.serialization.Serializable

// Pure Kotlin domain models and security redaction helpers for opt-in Cloud LLM API execution.
// Imports ONLY Kotlin standard library and kotlinx.serialization packages (100% pure domain/).

// Strict allowlist pattern for cloud model identifiers blocking path traversal and query injection.
private val CLOUD_MODEL_ID_PATTERN = Regex("^[A-Za-z0-9._:/-]{2,80}$")

// Pattern matching Google AI Studio / Gemini API keys in untrusted error payloads.
private val GEMINI_KEY_SECRET_PATTERN = Regex("AIza[A-Za-z0-9_-]{12,}")

// Pattern matching OpenAI, Anthropic (sk-ant-...), and OpenRouter (sk-or-...) secret keys.
private val SK_KEY_SECRET_PATTERN = Regex("sk-[A-Za-z0-9_-]{10,}")

// Pattern matching Authorization Bearer credentials echoed in HTTP diagnostics.
private val BEARER_TOKEN_SECRET_PATTERN =
  Regex("Bearer\\s+[A-Za-z0-9._-]+", RegexOption.IGNORE_CASE)

// Placeholder token substituted whenever an API credential pattern is scrubbed.
private const val REDACTED_TOKEN = "[REDACTED]"

// Minimum trimmed length required before revealing the trailing 4 characters in UI previews.
private const val MIN_PREVIEWABLE_KEY_LENGTH = 10

/**
 * Selects whether `SavorAI` executes inference locally on-device or via a user-supplied Cloud API key.
 *
 * @property displayLabel Concise human-readable mode label rendered in the AI Setup toggle.
 */
@Serializable
enum class AiExecutionMode(val displayLabel: String) {
  // Default privacy-first posture running 100% offline via LiteRT-LM / MediaPipe on Android GPU/CPU.
  /** Runs text and vision inference locally on-device with zero network traffic. */
  ON_DEVICE("On-Device (Offline)"),

  // Opt-in HTTPS cloud mode using a Keystore-encrypted user API key and header-only auth.
  /** Routes text and multimodal vision prompts to the configured HTTPS Cloud LLM provider. */
  CLOUD_API("Cloud API Key"),
}

/**
 * Supported HTTPS Cloud LLM providers with curated default and preset multimodal model IDs.
 *
 * @property displayName Full provider name shown in setup cards and diagnostic banners.
 * @property shortLabel Compact provider badge label (`"Gemini"`, `"OpenAI"`, `"Claude"`, `"OpenRouter"`).
 * @property defaultModelId Recommended multimodal-capable model identifier selected out of the box.
 * @property presetModelIds Quick-select 1-tap model chips surfaced in Cloud AI Setup.
 * @property apiKeyHint Non-secret prefix hint shown as placeholder guidance in the password field.
 * @property supportsVision `true` when the provider supports multimodal JPEG + text inspection.
 */
@Serializable
enum class CloudLlmProvider(
  val displayName: String,
  val shortLabel: String,
  val defaultModelId: String,
  val presetModelIds: List<String>,
  val apiKeyHint: String,
  val supportsVision: Boolean = true,
) {
  // Uses x-goog-api-key header (never URL ?key= query parameters) against v1beta generateContent.
  /** Google Gemini API (`generativelanguage.googleapis.com`). */
  GEMINI(
    displayName = "Google Gemini API",
    shortLabel = "Gemini",
    defaultModelId = "gemini-3.8-flash",
    presetModelIds = listOf(
      "gemini-3.8-flash",
      "gemini-4-neon",
      "gemini-4-carbon",
      "gemini-4-argon",
      "gemini-4-hydrogen",
      "gemini-3.5-flash-lite",
    ),
    apiKeyHint = "AIza...",
  ),

  // Uses Authorization: Bearer header against /v1/chat/completions with max_completion_tokens.
  /** OpenAI Chat Completions API (`api.openai.com`). */
  OPENAI(
    displayName = "OpenAI API",
    shortLabel = "OpenAI",
    defaultModelId = "gpt-6.2-sol",
    presetModelIds = listOf(
      "gpt-6.2-sol",
      "gpt-6.2-astra",
      "gpt-6.1-terra",
      "gpt-6-luna",
    ),
    apiKeyHint = "sk-...",
  ),

  // Uses x-api-key + anthropic-version headers against /v1/messages with base64 image blocks.
  /** Anthropic Messages API (`api.anthropic.com`). */
  ANTHROPIC(
    displayName = "Anthropic Claude API",
    shortLabel = "Claude",
    defaultModelId = "claude-sonnet-5-5",
    presetModelIds = listOf(
      "claude-sonnet-5-5",
      "claude-haiku-5-5",
      "claude-opus-5-5",
      "claude-fable-5-2",
    ),
    apiKeyHint = "sk-ant-...",
  ),

  // Uses OpenAI-compatible Authorization: Bearer schema against openrouter.ai/api/v1/chat/completions.
  /** OpenRouter unified multi-provider gateway (`openrouter.ai`). */
  OPENROUTER(
    displayName = "OpenRouter API",
    shortLabel = "OpenRouter",
    defaultModelId = "google/gemini-4-neon",
    presetModelIds = listOf(
      "google/gemini-4-neon",
      "google/gemini-4-hydrogen",
      "openai/gpt-6.2-astra",
      "openai/gpt-6-luna",
      "anthropic/claude-opus-5.5",
      "anthropic/claude-fable-5.2",
    ),
    apiKeyHint = "sk-or-...",
  ),
}

// Explicit mapping of retired pre-late-2026 model slugs to active non-deprecated replacements.
private val EXACT_DEPRECATED_MODEL_MIGRATIONS: Map<String, String> = mapOf(
  "gemini-2.5-flash" to "gemini-3.5-flash-lite",
  "gemini-2.5-pro" to "gemini-3.8-flash",
  "gemini-3.7-flash" to "gemini-3.8-flash",
  "gemini-2.0-flash" to "gemini-3.8-flash",
  "gpt-4o-mini" to "gpt-6-luna",
  "gpt-5-mini" to "gpt-6-luna",
  "o4-mini" to "gpt-6-luna",
  "gpt-4o" to "gpt-6.2-sol",
  "gpt-4.1" to "gpt-6.2-sol",
  "gpt-5" to "gpt-6.2-sol",
  "o3" to "gpt-6.2-sol",
  "claude-3-5-haiku-latest" to "claude-haiku-5-5",
  "claude-3-7-sonnet-latest" to "claude-sonnet-5-5",
  "google/gemini-2.5-flash" to "google/gemini-4-neon",
  "openai/gpt-4o-mini" to "openai/gpt-6-luna",
  "anthropic/claude-3.5-haiku" to "anthropic/claude-opus-5.5",
)

/**
 * Non-secret presentation and routing configuration for hybrid On-Device / Cloud AI inference.
 *
 * Raw API keys are **never** stored in [CloudLlmConfig], `KitchenSnapshot`, or `KitchenUiState`;
 * only boolean presence flags and [maskedKeyPreview] (`"•••• 1234"`) cross into UI state.
 *
 * @property executionMode Active execution target (`ON_DEVICE` offline default vs `CLOUD_API`).
 * @property provider Selected [CloudLlmProvider] for cloud requests.
 * @property modelId Validated model identifier for [provider].
 * @property hasStoredApiKey `true` when an encrypted API key is stored for [provider].
 * @property maskedKeyPreview Non-secret masked suffix preview (`"•••• 1234"`), or `null` if unset.
 * @property configuredProviders Set of providers that currently have a saved key in the vault.
 * @property availableModelIds Curated presets merged with any live-synced `/models` identifiers.
 */
@Serializable
data class CloudLlmConfig(
  val executionMode: AiExecutionMode = AiExecutionMode.ON_DEVICE,
  val provider: CloudLlmProvider = CloudLlmProvider.GEMINI,
  val modelId: String = provider.defaultModelId,
  val hasStoredApiKey: Boolean = false,
  val maskedKeyPreview: String? = null,
  val configuredProviders: Set<CloudLlmProvider> = emptySet(),
  val availableModelIds: List<String> = provider.presetModelIds,
)

/**
 * Returns `true` when [modelId] belongs to a retired or deprecated pre-late-2026 cloud model family
 * (`gemini-1.5`/`2.0`/`2.5`/`3.7`, `gpt-3.5`/`4`/`4o`/`5`, `o1`/`o3`/`o4`, or `claude-2`/`3`).
 *
 * @param modelId Candidate raw or provider-namespaced cloud model identifier.
 * @return `true` when [modelId] should be excluded from live catalogs and migrated on load.
 */
fun isDeprecatedCloudModelId(modelId: String): Boolean {
  val normalized = modelId.trim().lowercase().removePrefix("models/")
  if (normalized.isEmpty()) return false
  if (normalized in EXACT_DEPRECATED_MODEL_MIGRATIONS) return true

  // Strip optional OpenRouter vendor namespace ("google/", "openai/", "anthropic/") for family checks.
  val bareSlug = normalized.substringAfter('/')
  return when {
    bareSlug.startsWith("gemini-1.") ||
      bareSlug.startsWith("gemini-2.0") ||
      bareSlug.startsWith("gemini-2.5") ||
      bareSlug.startsWith("gemini-3.7") -> true
    bareSlug.startsWith("gpt-3.5") ||
      bareSlug.startsWith("gpt-4") ||
      bareSlug.startsWith("gpt-5") -> true
    bareSlug == "o1" ||
      bareSlug.startsWith("o1-") ||
      bareSlug == "o3" ||
      bareSlug.startsWith("o3-") ||
      bareSlug == "o4" ||
      bareSlug.startsWith("o4-") -> true
    bareSlug.startsWith("claude-1") ||
      bareSlug.startsWith("claude-2") ||
      bareSlug.startsWith("claude-3") ||
      bareSlug.startsWith("claude-instant") -> true
    else -> false
  }
}

/**
 * Upgrades any deprecated persisted [modelId] to its active late-2026 replacement for [provider],
 * while preserving valid custom or active non-deprecated model IDs unchanged.
 *
 * @param provider Target [CloudLlmProvider] owning the persisted model configuration.
 * @param modelId Persisted or user-configured model identifier.
 * @return Active non-deprecated model identifier for [provider].
 */
fun migrateDeprecatedCloudModelId(provider: CloudLlmProvider, modelId: String): String {
  val trimmed = modelId.trim()
  if (trimmed.isEmpty()) return provider.defaultModelId
  if (!isDeprecatedCloudModelId(trimmed)) return trimmed

  val lower = trimmed.lowercase()
  val withoutModelsPrefix = lower.removePrefix("models/")
  // Check exact canonical migration table first (handling OpenRouter namespaces vs direct slugs).
  val directMatch = when (provider) {
    CloudLlmProvider.OPENROUTER -> EXACT_DEPRECATED_MODEL_MIGRATIONS[lower]
    else -> EXACT_DEPRECATED_MODEL_MIGRATIONS[withoutModelsPrefix]
  }
  if (directMatch != null) return directMatch

  // Fall back to provider-aligned family upgrades for dated snapshots (e.g. gpt-4o-2024-08-06).
  val bareSlug = withoutModelsPrefix.substringAfter('/')
  return when (provider) {
    CloudLlmProvider.GEMINI -> when {
      bareSlug.startsWith("gemini-2.5-flash") -> "gemini-3.5-flash-lite"
      else -> provider.defaultModelId
    }
    CloudLlmProvider.OPENAI -> when {
      bareSlug.contains("mini") -> "gpt-6-luna"
      else -> provider.defaultModelId
    }
    CloudLlmProvider.ANTHROPIC -> when {
      bareSlug.contains("haiku") -> "claude-haiku-5-5"
      else -> provider.defaultModelId
    }
    CloudLlmProvider.OPENROUTER -> when {
      lower.startsWith("openai/") && bareSlug.contains("mini") -> "openai/gpt-6-luna"
      lower.startsWith("openai/") -> "openai/gpt-6.2-astra"
      lower.startsWith("anthropic/") -> "anthropic/claude-opus-5.5"
      else -> provider.defaultModelId
    }
  }
}

/**
 * Produces a non-secret masked preview of [rawKey] safe to display in UI status badges.
 *
 * Keys with at least `10` trimmed characters reveal only the final 4 characters (`"•••• ab12"`);
 * shorter inputs return `"••••••••"` so short test or partial secrets never leak entropy.
 *
 * @param rawKey Raw user-entered or decrypted API key string.
 * @return Masked key preview string containing zero prefix characters.
 */
fun maskApiKeyPreview(rawKey: String): String {
  val trimmed = rawKey.trim()
  return when {
    trimmed.length >= MIN_PREVIEWABLE_KEY_LENGTH -> "•••• ${trimmed.takeLast(4)}"
    else -> "••••••••"
  }
}

/**
 * Validates that [modelId] is a safe, well-formed cloud model identifier (`2..80` allowed ASCII
 * characters) and contains zero `".."` path-traversal sequences.
 *
 * @param modelId Candidate model identifier supplied by the user or preset chip.
 * @return `true` when [modelId] is safe to interpolate into cloud API requests.
 */
fun isValidCloudModelId(modelId: String): Boolean =
  CLOUD_MODEL_ID_PATTERN.matches(modelId) && !modelId.contains("..")

/**
 * Scrubs raw API keys and bearer tokens from untrusted HTTP error bodies or exception messages
 * before surfacing diagnostics to UI toasts or chat turns.
 *
 * @param rawMessage Raw error message or HTTP response snippet.
 * @param activeSecret Optional exact secret currently in use, scrubbed literally first if non-blank.
 * @return Sanitized diagnostic string with credential substrings replaced by `"[REDACTED]"`.
 */
fun redactApiSecrets(rawMessage: String, activeSecret: String? = null): String {
  if (rawMessage.isEmpty()) return rawMessage
  // Scrub any exact active key literal first so custom key formats cannot slip past regexes.
  val trimmedSecret = activeSecret?.trim().orEmpty()
  val withoutActiveSecret = when {
    trimmedSecret.isNotEmpty() -> rawMessage.replace(trimmedSecret, REDACTED_TOKEN)
    else -> rawMessage
  }
  return withoutActiveSecret
    .replace(BEARER_TOKEN_SECRET_PATTERN, REDACTED_TOKEN)
    .replace(GEMINI_KEY_SECRET_PATTERN, REDACTED_TOKEN)
    .replace(SK_KEY_SECRET_PATTERN, REDACTED_TOKEN)
}
