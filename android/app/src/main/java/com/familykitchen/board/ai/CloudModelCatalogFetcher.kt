package com.familykitchen.board.ai

import com.familykitchen.board.data.InMemoryCloudCredentialVault
import com.familykitchen.board.domain.CloudLlmProvider
import com.familykitchen.board.domain.ValidationException
import com.familykitchen.board.domain.isDeprecatedCloudModelId
import com.familykitchen.board.domain.isValidCloudModelId
import com.familykitchen.board.domain.redactApiSecrets
import java.io.IOException
import java.io.InputStream
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

// Zero-dependency HTTPS GET adapter syncing active non-deprecated model catalogs across Gemini,
// OpenAI, Anthropic Claude, and OpenRouter while enforcing strict SSRF and secret-redaction rules.

/**
 * Pluggable HTTPS JSON `GET` transport enabling 100% socket-free hermetic JUnit verification of
 * live provider `/models` catalog synchronization.
 */
fun interface CloudHttpGetTransport {
  /**
   * Executes an HTTPS `GET` request with [headers] against [url].
   *
   * @param url Validated `https://` endpoint URL on an allowlisted cloud provider host.
   * @param headers Request headers carrying authentication credentials and JSON content headers.
   * @return Raw [CloudHttpResponse] status code and UTF-8 JSON body.
   */
  suspend fun getJson(url: String, headers: Map<String, String>): CloudHttpResponse
}

/**
 * Production [CloudHttpGetTransport] backed by platform [HttpsURLConnection] on [Dispatchers.IO].
 *
 * Enforces `https://` scheme, [CloudLlmClient.ALLOWED_CLOUD_HOSTS] SSRF allowlist validation,
 * disabled caching, disabled cross-host redirects, and bounded `512 KB` response buffering.
 *
 * @param ioDispatcher Background coroutine dispatcher executing blocking TLS socket I/O.
 */
class HttpsUrlConnectionCloudGetTransport(
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CloudHttpGetTransport {

  /** Sends an HTTPS `GET` request to an allowlisted cloud host with caching and redirects off. */
  override suspend fun getJson(url: String, headers: Map<String, String>): CloudHttpResponse {
    // Validate scheme, host allowlist, port, and query-free path BEFORE opening any TLS socket.
    val validatedUri = CloudLlmClient.validateAllowedCloudEndpoint(url)
    return withContext(ioDispatcher) {
      val httpsConnection = validatedUri.toURL().openConnection() as? HttpsURLConnection
        ?: throw ValidationException(
          message = "Rejected non-HTTPS connection class for '${validatedUri.host}'.",
          issues = listOf("url: requires HttpsURLConnection"),
        )

      try {
        httpsConnection.requestMethod = "GET"
        httpsConnection.useCaches = false
        // Disable redirects so authentication headers never follow an open redirect off-host.
        httpsConnection.instanceFollowRedirects = false
        httpsConnection.connectTimeout = CloudLlmClient.CONNECT_TIMEOUT_MS
        httpsConnection.readTimeout = CloudLlmClient.READ_TIMEOUT_MS
        httpsConnection.doInput = true
        httpsConnection.setRequestProperty("Accept", "application/json")
        httpsConnection.setRequestProperty("Cache-Control", "no-store")
        for ((headerName, headerValue) in headers) {
          httpsConnection.setRequestProperty(headerName, headerValue)
        }

        // Read inputStream on 2xx responses and errorStream on 4xx/5xx diagnostics.
        val status = httpsConnection.responseCode
        val stream = if (status in 200..299) httpsConnection.inputStream else httpsConnection.errorStream
        CloudHttpResponse(statusCode = status, body = readBoundedUtf8(stream))
      } finally {
        // Deterministically release underlying TLS socket descriptors on success, error, or cancel.
        httpsConnection.disconnect()
      }
    }
  }

  // Caps catalog response buffering at 512 KB so large gateway catalogs never spike heap memory.
  private fun readBoundedUtf8(stream: InputStream?): String {
    if (stream == null) return ""
    return stream.bufferedReader(Charsets.UTF_8).use { reader ->
      val buffer = CharArray(4096)
      val builder = StringBuilder()
      while (builder.length < MAX_CATALOG_RESPONSE_CHARS) {
        val count = reader.read(buffer, 0, minOf(buffer.size, MAX_CATALOG_RESPONSE_CHARS - builder.length))
        if (count <= 0) break
        builder.append(buffer, 0, count)
      }
      builder.toString()
    }
  }

  private companion object {
    const val MAX_CATALOG_RESPONSE_CHARS: Int = 512 * 1024
  }
}

/**
 * Live model catalog fetcher querying each [CloudLlmProvider]'s official `GET /models` endpoint,
 * filtering out retired/deprecated and non-chat modalities, and merging curated active presets
 * ahead of newly discovered model IDs (capped at [MAX_SYNCED_MODELS]).
 *
 * @param transport Pluggable HTTPS `GET` transport (defaults to [HttpsUrlConnectionCloudGetTransport]).
 */
class CloudModelCatalogFetcher(
  private val transport: CloudHttpGetTransport = HttpsUrlConnectionCloudGetTransport(),
) {

  private val catalogJson = Json {
    ignoreUnknownKeys = true
    isLenient = false
  }

  /**
   * Queries the official `/models` endpoint for [provider] using header-only [apiKey] auth and
   * returns up to [MAX_SYNCED_MODELS] validated, non-deprecated model IDs (curated presets first).
   *
   * @param provider Target [CloudLlmProvider] (`GEMINI`, `OPENAI`, `ANTHROPIC`, or `OPENROUTER`).
   * @param apiKey Decrypted API key transmitted strictly inside HTTP request headers.
   * @return Ordered, deduplicated list of active model identifiers ready for UI selection chips.
   */
  suspend fun fetchAvailableModels(provider: CloudLlmProvider, apiKey: String): List<String> {
    // Reject blank, short, or CRLF-containing credentials before building HTTP request headers.
    val cleanApiKey = InMemoryCloudCredentialVault.validateApiKeyFormat(apiKey)
    val endpointUrl = buildModelsEndpointUrl(provider)
    val headers = buildCatalogHeaders(provider, cleanApiKey)

    val response = try {
      getValidatedJson(url = endpointUrl, headers = headers)
    } catch (cancel: CancellationException) {
      // Preserve structured coroutine cancellation without wrapping as a network failure.
      throw cancel
    } catch (validationErr: ValidationException) {
      throw validationErr
    } catch (ioErr: IOException) {
      throw CloudApiException(
        statusCode = null,
        message = "Network error syncing models from ${provider.displayName} — check connection.",
        cause = ioErr,
      )
    }

    // Scrub echoed credentials from all non-2xx upstream diagnostics before surfacing exceptions.
    if (response.statusCode !in 200..299) {
      throw mapCatalogHttpError(provider, response, cleanApiKey)
    }

    // Merge curated presets ahead of newly discovered live models so core recommendations lead.
    val liveModelIds = parseLiveModelIds(provider, response.body, cleanApiKey)
    return InMemoryCloudCredentialVault.mergePresetAndLiveModels(provider, liveModelIds)
  }

  /**
   * Validates [url] against [CloudLlmClient.ALLOWED_CLOUD_HOSTS] (`https://` only, zero `?key=`
   * query strings) before delegating to [transport].
   *
   * @param url Candidate `/models` endpoint URL.
   * @param headers Request headers.
   * @return Raw [CloudHttpResponse] from [transport].
   */
  suspend fun getValidatedJson(url: String, headers: Map<String, String>): CloudHttpResponse {
    CloudLlmClient.validateAllowedCloudEndpoint(url)
    return transport.getJson(url = url, headers = headers)
  }

  // Construct strict query-free HTTPS /models discovery URLs for all 4 allowlisted providers.
  private fun buildModelsEndpointUrl(provider: CloudLlmProvider): String =
    when (provider) {
      CloudLlmProvider.GEMINI -> "https://generativelanguage.googleapis.com/v1beta/models"
      CloudLlmProvider.OPENAI -> "https://api.openai.com/v1/models"
      CloudLlmProvider.ANTHROPIC -> "https://api.anthropic.com/v1/models"
      CloudLlmProvider.OPENROUTER -> "https://openrouter.ai/api/v1/models"
    }

  // Attach API keys strictly inside HTTP headers (never inside ?key= query parameters).
  private fun buildCatalogHeaders(provider: CloudLlmProvider, cleanApiKey: String): Map<String, String> =
    buildMap {
      put("Accept", "application/json")
      put("Cache-Control", "no-store")
      when (provider) {
        CloudLlmProvider.GEMINI -> put("x-goog-api-key", cleanApiKey)
        CloudLlmProvider.OPENAI, CloudLlmProvider.OPENROUTER -> put("Authorization", "Bearer $cleanApiKey")
        CloudLlmProvider.ANTHROPIC -> {
          put("x-api-key", cleanApiKey)
          put("anthropic-version", CloudLlmClient.ANTHROPIC_API_VERSION)
        }
      }
    }

  private fun parseLiveModelIds(provider: CloudLlmProvider, rawBody: String, activeApiKey: String): List<String> {
    val root = try {
      catalogJson.parseToJsonElement(rawBody).jsonObject
    } catch (parseErr: SerializationException) {
      val safeSnippet = redactApiSecrets(rawBody.take(160), activeApiKey)
      throw ValidationException("Malformed /models JSON from ${provider.displayName}: $safeSnippet", listOf("responseBody: invalid JSON"), parseErr)
    } catch (illegalErr: IllegalArgumentException) {
      val safeSnippet = redactApiSecrets(rawBody.take(160), activeApiKey)
      throw ValidationException("Unexpected non-object /models JSON from ${provider.displayName}: $safeSnippet", listOf("responseBody: expected JSON object"), illegalErr)
    }

    return when (provider) {
      CloudLlmProvider.GEMINI -> parseGeminiModels(root)
      CloudLlmProvider.OPENAI -> parseOpenAiModels(root)
      CloudLlmProvider.ANTHROPIC -> parseAnthropicModels(root)
      CloudLlmProvider.OPENROUTER -> parseOpenRouterModels(root)
    }
  }

  // Extracts Gemini generateContent models while excluding deprecated 1.5/2.0/2.5/3.7 & non-chat IDs.
  private fun parseGeminiModels(root: JsonObject): List<String> =
    root["models"]?.jsonArray.orEmpty().mapNotNull { element ->
      val obj = element as? JsonObject ?: return@mapNotNull null
      val methods = obj["supportedGenerationMethods"]?.jsonArray.orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
      if ("generateContent" !in methods) return@mapNotNull null
      val id = (obj["name"] as? JsonPrimitive)?.contentOrNull?.removePrefix("models/")?.trim().orEmpty()
      val lower = id.lowercase()
      when {
        !isValidCloudModelId(id) || id.contains('/') || id.contains(':') || isDeprecatedCloudModelId(id) -> null
        GEMINI_EXCLUDED_TOKENS.any { lower.contains(it) } -> null
        else -> id
      }
    }

  // Extracts active OpenAI GPT-6+ chat models, excluding retired GPT-3.5/4/5/o1/o3/o4 & audio/image APIs.
  private fun parseOpenAiModels(root: JsonObject): List<String> =
    root["data"]?.jsonArray.orEmpty().mapNotNull { element ->
      val id = ((element as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
      val lower = id.lowercase()
      val isChatFamily = lower.startsWith("gpt-") || lower.startsWith("chatgpt-") || lower.startsWith("o")
      when {
        !isValidCloudModelId(id) || !isChatFamily || isDeprecatedCloudModelId(id) -> null
        OPENAI_EXCLUDED_TOKENS.any { lower.contains(it) } -> null
        else -> id
      }
    }

  // Extracts active Claude 5.x+ models while excluding retired Claude 1/2/3/instant snapshots.
  private fun parseAnthropicModels(root: JsonObject): List<String> =
    root["data"]?.jsonArray.orEmpty().mapNotNull { element ->
      val id = ((element as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
      when {
        !isValidCloudModelId(id) || !id.lowercase().startsWith("claude-") || isDeprecatedCloudModelId(id) -> null
        else -> id
      }
    }

  // Extracts top active Gemini, GPT, and Claude gateway slugs on OpenRouter.
  private fun parseOpenRouterModels(root: JsonObject): List<String> =
    root["data"]?.jsonArray.orEmpty().mapNotNull { element ->
      val id = ((element as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
      val lower = id.lowercase()
      when {
        !isValidCloudModelId(id) || OPENROUTER_ALLOWED_PREFIXES.none { lower.startsWith(it) } || isDeprecatedCloudModelId(id) -> null
        OPENAI_EXCLUDED_TOKENS.any { lower.contains(it) } || GEMINI_EXCLUDED_TOKENS.any { lower.contains(it) } -> null
        else -> id
      }
    }

  // Redacts echoed API credentials from upstream 4xx/5xx /models responses before throwing.
  private fun mapCatalogHttpError(provider: CloudLlmProvider, response: CloudHttpResponse, activeApiKey: String): CloudApiException {
    val detail = runCatching {
      val errObj = catalogJson.parseToJsonElement(response.body).jsonObject["error"]
      (errObj as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.contentOrNull }
    }.getOrNull() ?: response.body
    val cleanSnippet = redactApiSecrets(detail.replace(Regex("\\s+"), " ").trim().take(160), activeApiKey)
    val baseMsg = when (response.statusCode) {
      401, 403 -> "Authentication failed (${response.statusCode}) syncing ${provider.displayName} models."
      429 -> "Rate limit reached (429) syncing ${provider.displayName} models."
      else -> "Model catalog sync failed (${response.statusCode}) on ${provider.displayName}."
    }
    val fullMsg = if (cleanSnippet.isNotEmpty()) "$baseMsg ($cleanSnippet)" else baseMsg
    return CloudApiException(statusCode = response.statusCode, message = redactApiSecrets(fullMsg, activeApiKey))
  }

  companion object {
    /** Maximum total preset + live-synced model chips surfaced per cloud provider. */
    const val MAX_SYNCED_MODELS: Int = InMemoryCloudCredentialVault.MAX_AVAILABLE_MODELS_PER_PROVIDER

    private val GEMINI_EXCLUDED_TOKENS: List<String> = listOf(
      "1.5", "2.0", "2.5", "3.7", "embedding", "tts", "imagen", "aqa", "veo", "learnlm",
    )

    private val OPENAI_EXCLUDED_TOKENS: List<String> = listOf(
      "tts", "whisper", "dall-e", "embedding", "moderation", "realtime", "audio",
      "transcribe", "search", "instruct", "babbage", "davinci", "sora", "codex",
    )

    private val OPENROUTER_ALLOWED_PREFIXES: List<String> = listOf(
      "google/gemini-", "openai/gpt-", "anthropic/claude-",
    )
  }
}
