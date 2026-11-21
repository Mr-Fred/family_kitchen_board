package com.familykitchen.board.ai

import com.familykitchen.board.data.InMemoryCloudCredentialVault
import com.familykitchen.board.domain.CloudLlmProvider
import com.familykitchen.board.domain.ValidationException
import com.familykitchen.board.domain.VisionNotSupportedException
import com.familykitchen.board.domain.isValidCloudModelId
import com.familykitchen.board.domain.redactApiSecrets
import com.familykitchen.board.domain.requireDomainValid
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URISyntaxException
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// Zero-dependency HTTPS Cloud LLM adapter supporting Gemini, OpenAI, Claude, and OpenRouter.
// Enforces header-only authentication, strict host allowlisting (SSRF defense), and secret redaction.

/**
 * Immutable HTTP status and UTF-8 response payload returned by [CloudHttpTransport].
 *
 * @property statusCode Numeric HTTP response status code (for example, `200`, `401`, or `429`).
 * @property body Raw UTF-8 response or error body returned by the remote endpoint.
 */
data class CloudHttpResponse(
  val statusCode: Int,
  val body: String,
)

/**
 * Pluggable HTTPS JSON POST transport enabling 100% socket-free hermetic JUnit verification.
 */
fun interface CloudHttpTransport {
  /**
   * Executes an HTTPS `POST` request with [headers] and [bodyJson] against [url].
   *
   * @param url Validated `https://` endpoint URL on an allowlisted provider host.
   * @param headers Request headers carrying authentication tokens and JSON content negotiation.
   * @param bodyJson Serialized UTF-8 JSON request payload.
   * @return Raw [CloudHttpResponse] status code and body.
   */
  suspend fun postJson(
    url: String,
    headers: Map<String, String>,
    bodyJson: String,
  ): CloudHttpResponse
}

/**
 * Recoverable exception raised when a Cloud LLM endpoint returns an authentication, rate-limit,
 * upstream HTTP, or network connectivity failure.
 *
 * All diagnostic strings passed to [message] are scrubbed via [redactApiSecrets] prior to creation.
 *
 * @property statusCode Optional HTTP status code when an upstream response was received.
 * @property message Sanitized user-safe error explanation.
 * @property cause Optional underlying I/O failure cause.
 */
class CloudApiException(
  val statusCode: Int? = null,
  override val message: String,
  override val cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Production [CloudHttpTransport] backed by platform [HttpsURLConnection] on [Dispatchers.IO].
 *
 * Enforces `https://` scheme, [CloudLlmClient.ALLOWED_CLOUD_HOSTS] SSRF allowlist validation,
 * disabled caching, disabled cross-host redirects, and bounded response reading.
 *
 * @param ioDispatcher Background coroutine dispatcher executing blocking TLS socket I/O.
 */
class HttpsUrlConnectionCloudTransport(
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CloudHttpTransport {

  /** Sends an HTTPS JSON `POST` to an allowlisted cloud host with caching and redirects disabled. */
  override suspend fun postJson(
    url: String,
    headers: Map<String, String>,
    bodyJson: String,
  ): CloudHttpResponse {
    // Validate scheme, host allowlist, port, and query-free path BEFORE opening any socket.
    val validatedUri = CloudLlmClient.validateAllowedCloudEndpoint(url)
    return withContext(ioDispatcher) {
      val openedConnection = validatedUri.toURL().openConnection()
      val httpsConnection = openedConnection as? HttpsURLConnection
        ?: run {
          throw ValidationException(
            message = "Rejected non-HTTPS connection class for '${validatedUri.host}'.",
            issues = listOf("url: requires HttpsURLConnection"),
          )
        }

      try {
        httpsConnection.requestMethod = "POST"
        httpsConnection.useCaches = false
        // Disable automatic redirects so auth headers can never follow an open redirect off-host.
        httpsConnection.instanceFollowRedirects = false
        httpsConnection.connectTimeout = CloudLlmClient.CONNECT_TIMEOUT_MS
        httpsConnection.readTimeout = CloudLlmClient.READ_TIMEOUT_MS
        httpsConnection.doOutput = true

        // Apply baseline no-store JSON headers followed by provider authentication headers.
        httpsConnection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        httpsConnection.setRequestProperty("Accept", "application/json")
        httpsConnection.setRequestProperty("Cache-Control", "no-store")
        for ((headerName, headerValue) in headers) {
          httpsConnection.setRequestProperty(headerName, headerValue)
        }

        val payloadBytes = bodyJson.toByteArray(Charsets.UTF_8)
        // Stream fixed-length UTF-8 JSON bytes without buffering payload copies in URL caches.
        httpsConnection.setFixedLengthStreamingMode(payloadBytes.size)
        httpsConnection.outputStream.use { output ->
          output.write(payloadBytes)
          output.flush()
        }

        // Read inputStream on 2xx responses and errorStream on 4xx/5xx diagnostics.
        val status = httpsConnection.responseCode
        val responseStream = when {
          status in 200..299 -> httpsConnection.inputStream
          else -> httpsConnection.errorStream
        }
        val responseText = readBoundedUtf8(responseStream)
        CloudHttpResponse(statusCode = status, body = responseText)
      } finally {
        // Always release underlying TLS socket file descriptors on success, error, or cancel.
        httpsConnection.disconnect()
      }
    }
  }

  // Caps response body buffering at 512 KB so malformed upstream responses cannot exhaust heap RAM.
  private fun readBoundedUtf8(stream: InputStream?): String {
    if (stream == null) return ""
    return stream.bufferedReader(Charsets.UTF_8).use { reader ->
      val buffer = CharArray(4096)
      val builder = StringBuilder()
      while (builder.length < MAX_RESPONSE_CHARS) {
        val count = reader.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_CHARS - builder.length))
        if (count <= 0) break
        builder.append(buffer, 0, count)
      }
      builder.toString()
    }
  }

  private companion object {
    const val MAX_RESPONSE_CHARS: Int = 512 * 1024
  }
}

/**
 * Multi-provider HTTPS Cloud LLM client supporting [CloudLlmProvider.GEMINI],
 * [CloudLlmProvider.OPENAI], [CloudLlmProvider.ANTHROPIC], and [CloudLlmProvider.OPENROUTER].
 *
 * Security invariants:
 * 1. **Header-Only Credentials**: API keys travel strictly inside HTTP headers (`x-goog-api-key`,
 *    `Authorization: Bearer`, or `x-api-key`) and **never** inside URL query strings (`?key=`).
 * 2. **SSRF & Path-Traversal Defense**: Validates `modelId` via [isValidCloudModelId] and validates
 *    target URLs against [ALLOWED_CLOUD_HOSTS] (`https://` only) before calling [transport].
 * 3. **Secret Redaction**: Scrubs all non-2xx HTTP bodies and I/O exceptions via [redactApiSecrets].
 *
 * @param transport Pluggable HTTPS JSON POST transport (defaults to [HttpsUrlConnectionCloudTransport]).
 */
class CloudLlmClient(
  private val transport: CloudHttpTransport = HttpsUrlConnectionCloudTransport(),
) {

  // Envelope parser tolerates provider telemetry fields (usageMetadata, id, model) outside content.
  private val envelopeJson = Json {
    ignoreUnknownKeys = true
    isLenient = false
  }

  /**
   * Executes a single-turn cloud text completion against [provider] using [modelId] and [apiKey].
   *
   * @param provider Target cloud LLM provider.
   * @param modelId Validated model identifier (`2..80` characters, zero `".."` segments).
   * @param apiKey Decrypted provider API key sent strictly via HTTP request headers.
   * @param prompt Grounded system and user prompt string.
   * @param maxTokens Maximum completion tokens (`1536` default).
   * @return Extracted plain-text completion emitted by the provider.
   */
  suspend fun generateText(
    provider: CloudLlmProvider,
    modelId: String,
    apiKey: String,
    prompt: String,
    maxTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
  ): String {
    val cleanPrompt = prompt.trim()
    requireDomainValid(cleanPrompt.isNotEmpty(), "CloudLlmRequest") {
      "prompt must be non-blank"
    }
    return executeProviderCompletion(
      provider = provider,
      modelId = modelId,
      apiKey = apiKey,
      prompt = cleanPrompt,
      base64Jpeg = null,
      maxTokens = maxTokens,
    )
  }

  /**
   * Executes an authentic multimodal JPEG + text inspection turn against [provider].
   *
   * Validates that [provider] supports vision, verifies that [preprocessedImagePath] exists and is
   * within `1 B..6 MB`, encodes the JPEG bytes via JVM/Android `java.util.Base64`, and dispatches
   * the provider-specific vision payload.
   *
   * @param provider Target cloud LLM provider.
   * @param modelId Validated multimodal model identifier.
   * @param apiKey Decrypted provider API key sent strictly via HTTP request headers.
   * @param preprocessedImagePath Path to the EXIF-normalized `<= 1280px` JPEG file.
   * @param prompt Neutral visual inspection prompt from `AiMenuAdvisorEngine.buildVisualIntakePrompt`.
   * @param maxTokens Maximum completion tokens (`1536` default).
   * @return Extracted model output string ready for strict catalog grounding.
   */
  suspend fun analyzeImageWithPrompt(
    provider: CloudLlmProvider,
    modelId: String,
    apiKey: String,
    preprocessedImagePath: String,
    prompt: String,
    maxTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
  ): String {
    if (!provider.supportsVision) {
      throw VisionNotSupportedException(
        activeBackendLabel = "Cloud · ${provider.shortLabel}",
        modelName = modelId,
      )
    }
    val cleanPrompt = prompt.trim()
    requireDomainValid(cleanPrompt.isNotEmpty(), "CloudVisionRequest") {
      "prompt must be non-blank"
    }
    val base64Jpeg = readAndEncodeBoundedJpeg(preprocessedImagePath)
    return executeProviderCompletion(
      provider = provider,
      modelId = modelId,
      apiKey = apiKey,
      prompt = cleanPrompt,
      base64Jpeg = base64Jpeg,
      maxTokens = maxTokens,
    )
  }

  /**
   * Sends a minimal `16`-token health probe (`"Reply with OK"`) to verify [apiKey] and [modelId]
   * connectivity against [provider].
   *
   * @param provider Target cloud LLM provider to verify.
   * @param modelId Model identifier to probe.
   * @param apiKey Decrypted API key to authenticate.
   * @return Trimmed verification response text from [provider].
   */
  suspend fun verifyConnection(
    provider: CloudLlmProvider,
    modelId: String,
    apiKey: String,
  ): String =
    generateText(
      provider = provider,
      modelId = modelId,
      apiKey = apiKey,
      prompt = VERIFY_HEALTH_PROMPT,
      maxTokens = VERIFY_MAX_OUTPUT_TOKENS,
    )

  /**
   * Validates [url] against [ALLOWED_CLOUD_HOSTS] (`https://` only) before delegating to [transport].
   *
   * @param url Candidate endpoint URL.
   * @param headers Request headers.
   * @param bodyJson Serialized JSON payload.
   * @return Raw [CloudHttpResponse] from [transport].
   */
  suspend fun postValidatedJson(
    url: String,
    headers: Map<String, String>,
    bodyJson: String,
  ): CloudHttpResponse {
    validateAllowedCloudEndpoint(url)
    return transport.postJson(url = url, headers = headers, bodyJson = bodyJson)
  }

  private suspend fun executeProviderCompletion(
    provider: CloudLlmProvider,
    modelId: String,
    apiKey: String,
    prompt: String,
    base64Jpeg: String?,
    maxTokens: Int,
  ): String {
    // Reject path-traversal ("..") or malformed model IDs before constructing any URL or JSON body.
    val safeModelId = modelId.trim()
    requireDomainValid(isValidCloudModelId(safeModelId), "CloudLlmRequest") {
      "Invalid or unsafe cloud model ID '$safeModelId'"
    }
    // Reject blank, short, or CRLF-containing keys before building HTTP headers.
    val cleanApiKey = InMemoryCloudCredentialVault.validateApiKeyFormat(apiKey)
    val clampedTokens = maxTokens.coerceIn(1, MAX_ALLOWED_OUTPUT_TOKENS)

    val endpointUrl = buildEndpointUrl(provider, safeModelId)
    val headers = buildRequestHeaders(provider, cleanApiKey)
    val bodyJson = buildRequestPayloadJson(provider, safeModelId, prompt, base64Jpeg, clampedTokens)

    val response = try {
      postValidatedJson(url = endpointUrl, headers = headers, bodyJson = bodyJson)
    } catch (cancel: CancellationException) {
      // Preserve structured concurrency cancellation without wrapping as a network failure.
      throw cancel
    } catch (validationErr: ValidationException) {
      throw validationErr
    } catch (ioErr: IOException) {
      // Surface actionable offline fallback guidance without leaking socket internals or headers.
      throw CloudApiException(
        statusCode = null,
        message = "Network error contacting ${provider.displayName} — check connection or switch to On-Device mode.",
        cause = ioErr,
      )
    }

    // Scrub secrets from all non-2xx upstream responses before raising CloudApiException.
    if (response.statusCode !in 200..299) {
      throw mapHttpErrorToException(provider, response, cleanApiKey)
    }
    return parseCompletionText(provider, response.body, cleanApiKey)
  }

  // Construct strict query-free HTTPS endpoint URLs (never appending ?key= query parameters).
  private fun buildEndpointUrl(provider: CloudLlmProvider, safeModelId: String): String =
    when (provider) {
      CloudLlmProvider.GEMINI -> {
        val modelSegment = safeModelId.removePrefix("models/")
        requireDomainValid(
          condition = modelSegment.isNotEmpty() &&
            !modelSegment.contains('/') &&
            !modelSegment.contains(':'),
          entityName = "GeminiModelId",
        ) {
          "Gemini model ID '$safeModelId' must not contain path separators or colons"
        }
        "https://generativelanguage.googleapis.com/v1beta/models/$modelSegment:generateContent"
      }
      CloudLlmProvider.OPENAI -> "https://api.openai.com/v1/chat/completions"
      CloudLlmProvider.ANTHROPIC -> "https://api.anthropic.com/v1/messages"
      CloudLlmProvider.OPENROUTER -> "https://openrouter.ai/api/v1/chat/completions"
    }

  // Attach credentials strictly in HTTP headers + baseline no-store JSON headers.
  private fun buildRequestHeaders(
    provider: CloudLlmProvider,
    cleanApiKey: String,
  ): Map<String, String> =
    buildMap {
      put("Content-Type", "application/json; charset=utf-8")
      put("Accept", "application/json")
      put("Cache-Control", "no-store")
      when (provider) {
        CloudLlmProvider.GEMINI -> put("x-goog-api-key", cleanApiKey)
        CloudLlmProvider.OPENAI,
        CloudLlmProvider.OPENROUTER,
        -> put("Authorization", "Bearer $cleanApiKey")
        CloudLlmProvider.ANTHROPIC -> {
          put("x-api-key", cleanApiKey)
          put("anthropic-version", ANTHROPIC_API_VERSION)
        }
      }
    }

  // Serialize provider-specific text and multimodal vision request bodies safely via JsonObject.
  private fun buildRequestPayloadJson(
    provider: CloudLlmProvider,
    safeModelId: String,
    prompt: String,
    base64Jpeg: String?,
    maxTokens: Int,
  ): String {
    val payloadObject: JsonObject = when (provider) {
      CloudLlmProvider.GEMINI -> buildJsonObject {
        // Gemini v1beta generateContent places inline_data JPEG part ahead of prompt text part.
        putJsonArray("contents") {
          add(
            buildJsonObject {
              putJsonArray("parts") {
                if (base64Jpeg != null) {
                  add(
                    buildJsonObject {
                      putJsonObject("inline_data") {
                        put("mime_type", JPEG_MIME_TYPE)
                        put("data", base64Jpeg)
                      }
                    },
                  )
                }
                add(buildJsonObject { put("text", prompt) })
              }
            },
          )
        }
        putJsonObject("generationConfig") {
          put("temperature", DEFAULT_TEMPERATURE)
          put("maxOutputTokens", maxTokens)
        }
      }

      CloudLlmProvider.OPENAI,
      CloudLlmProvider.OPENROUTER,
      -> buildJsonObject {
        // OpenAI and OpenRouter share /v1/chat/completions with data:image/jpeg;base64 vision URLs.
        put("model", safeModelId)
        when (provider) {
          CloudLlmProvider.OPENAI -> {
            // OpenAI GPT-6 / reasoning tiers require max_completion_tokens and reject temperature.
            put("max_completion_tokens", maxTokens)
          }
          else -> {
            put("max_tokens", maxTokens)
            put("temperature", DEFAULT_TEMPERATURE)
          }
        }
        putJsonArray("messages") {
          add(
            buildJsonObject {
              put("role", "user")
              when (base64Jpeg) {
                null -> put("content", prompt)
                else -> putJsonArray("content") {
                  add(
                    buildJsonObject {
                      put("type", "text")
                      put("text", prompt)
                    },
                  )
                  add(
                    buildJsonObject {
                      put("type", "image_url")
                      putJsonObject("image_url") {
                        put("url", "data:$JPEG_MIME_TYPE;base64,$base64Jpeg")
                      }
                    },
                  )
                }
              }
            },
          )
        }
      }

      CloudLlmProvider.ANTHROPIC -> buildJsonObject {
        // Anthropic /v1/messages accepts typed content blocks (base64 image source + text prompt).
        put("model", safeModelId)
        put("max_tokens", maxTokens)
        putJsonArray("messages") {
          add(
            buildJsonObject {
              put("role", "user")
              putJsonArray("content") {
                if (base64Jpeg != null) {
                  add(
                    buildJsonObject {
                      put("type", "image")
                      putJsonObject("source") {
                        put("type", "base64")
                        put("media_type", JPEG_MIME_TYPE)
                        put("data", base64Jpeg)
                      }
                    },
                  )
                }
                add(
                  buildJsonObject {
                    put("type", "text")
                    put("text", prompt)
                  },
                )
              }
            },
          )
        }
      }
    }
    return envelopeJson.encodeToString(JsonObject.serializer(), payloadObject)
  }

  // Extracts completion text across Gemini candidates, OpenAI choices, and Anthropic content blocks.
  private fun parseCompletionText(
    provider: CloudLlmProvider,
    rawBody: String,
    activeApiKey: String,
  ): String {
    val root = try {
      envelopeJson.parseToJsonElement(rawBody).jsonObject
    } catch (parseErr: SerializationException) {
      // Redact any echoed credentials before surfacing malformed JSON diagnostics.
      val safeSnippet = redactApiSecrets(rawBody.take(160), activeApiKey)
      throw ValidationException(
        message = "Malformed JSON response from ${provider.displayName}: $safeSnippet",
        issues = listOf("responseBody: invalid JSON"),
        cause = parseErr,
      )
    } catch (illegalErr: IllegalArgumentException) {
      val safeSnippet = redactApiSecrets(rawBody.take(160), activeApiKey)
      throw ValidationException(
        message = "Unexpected non-object JSON response from ${provider.displayName}: $safeSnippet",
        issues = listOf("responseBody: expected JSON object"),
        cause = illegalErr,
      )
    }

    val extracted = when (provider) {
      CloudLlmProvider.GEMINI -> {
        // Filter out Gemini 3.8 / Gemini 4 {"thought": true} reasoning parts before joining text.
        val partObjects = root["candidates"]
          ?.jsonArray
          ?.firstOrNull()
          ?.jsonObject
          ?.get("content")
          ?.jsonObject
          ?.get("parts")
          ?.jsonArray
          .orEmpty()
          .mapNotNull { it as? JsonObject }
        val nonThoughtTexts = partObjects
          .filterNot { partObj -> (partObj["thought"] as? JsonPrimitive)?.booleanOrNull == true }
          .mapNotNull { partObj ->
            (partObj["text"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
          }
        // Fall back to all text parts only when every emitted part carried thought == true.
        val effectiveTexts = when {
          nonThoughtTexts.isNotEmpty() -> nonThoughtTexts
          else -> partObjects.mapNotNull { partObj ->
            (partObj["text"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
          }
        }
        effectiveTexts.joinToString("\n")
      }

      CloudLlmProvider.OPENAI,
      CloudLlmProvider.OPENROUTER,
      -> {
        // Support both plain string choices[0].message.content and structured content arrays.
        val contentElement = root["choices"]
          ?.jsonArray
          ?.firstOrNull()
          ?.jsonObject
          ?.get("message")
          ?.jsonObject
          ?.get("content")
        when (contentElement) {
          is JsonPrimitive -> contentElement.contentOrNull.orEmpty()
          is JsonArray -> contentElement.mapNotNull { part ->
            val partObj = part as? JsonObject ?: return@mapNotNull null
            val partType = (partObj["type"] as? JsonPrimitive)?.contentOrNull
            when {
              partType == null || partType == "text" || partType == "output_text" ->
                (partObj["text"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
              else -> null
            }
          }.joinToString("\n")
          else -> ""
        }
      }

      CloudLlmProvider.ANTHROPIC -> {
        // Skip Claude 5.x adaptive {"type": "thinking"} blocks; keep only null or "text" blocks.
        val blocks = root["content"]?.jsonArray
        blocks.orEmpty().mapNotNull { block ->
          val blockObj = block as? JsonObject ?: return@mapNotNull null
          val blockType = (blockObj["type"] as? JsonPrimitive)?.contentOrNull
          when {
            blockType == null || blockType == "text" ->
              (blockObj["text"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            else -> null
          }
        }.joinToString("\n")
      }
    }.trim()

    requireDomainValid(extracted.isNotEmpty(), "CloudLlmResponse") {
      "Empty text completion returned by ${provider.displayName}"
    }
    return extracted
  }

  // Map non-2xx HTTP status codes to clear user-safe guidance with all credential tokens scrubbed.
  private fun mapHttpErrorToException(
    provider: CloudLlmProvider,
    response: CloudHttpResponse,
    activeApiKey: String,
  ): CloudApiException {
    val redactedDetail = extractRedactedErrorDetail(response.body, activeApiKey)
    val safeMessage = when (response.statusCode) {
      401, 403 -> {
        val base = "Authentication failed (${response.statusCode}) for ${provider.displayName} — check your API key."
        when {
          redactedDetail.isNotEmpty() -> "$base ($redactedDetail)"
          else -> base
        }
      }
      429 -> "Rate limit reached (429) on ${provider.displayName}."
      else -> {
        val suffix = when {
          redactedDetail.isNotEmpty() -> ": $redactedDetail"
          else -> "."
        }
        "Cloud request failed (${response.statusCode}) on ${provider.displayName}$suffix"
      }
    }
    return CloudApiException(
      statusCode = response.statusCode,
      message = redactApiSecrets(safeMessage, activeApiKey),
    )
  }

  // Parses structured provider error.message fields and scrubs all secret patterns before display.
  private fun extractRedactedErrorDetail(rawBody: String, activeApiKey: String): String {
    if (rawBody.isBlank()) return ""
    val parsedMessage = runCatching {
      val root = envelopeJson.parseToJsonElement(rawBody).jsonObject
      when (val errField = root["error"]) {
        is JsonObject -> errField["message"]?.jsonPrimitive?.contentOrNull
        is JsonPrimitive -> errField.contentOrNull
        else -> root["message"]?.jsonPrimitive?.contentOrNull
      }
    }.getOrNull()
    val candidate = (parsedMessage ?: rawBody).replace(Regex("\\s+"), " ").trim().take(160)
    return redactApiSecrets(candidate, activeApiKey)
  }

  // Enforces 1 B..6 MB size bounds on preprocessed JPEGs and encodes via JVM/Android java.util.Base64.
  private fun readAndEncodeBoundedJpeg(preprocessedImagePath: String): String {
    val cleanPath = preprocessedImagePath.trim()
    requireDomainValid(cleanPath.isNotEmpty(), "CloudVisionRequest") {
      "preprocessedImagePath must be non-blank"
    }
    val imageFile = File(cleanPath)
    requireDomainValid(imageFile.isFile, "CloudVisionRequest") {
      "Preprocessed kitchen photo does not exist"
    }
    val byteLength = imageFile.length()
    // Bound Base64 memory footprint to <= 6 MB so vision turns stay fast and OOM-safe.
    requireDomainValid(byteLength in 1L..MAX_CLOUD_IMAGE_BYTES, "CloudVisionRequest") {
      "Kitchen photo size ($byteLength bytes) must be between 1 byte and 6 MB"
    }
    val imageBytes = try {
      imageFile.readBytes()
    } catch (ioErr: IOException) {
      throw ValidationException(
        message = "Unable to read preprocessed kitchen photo for vision upload.",
        issues = listOf("preprocessedImagePath: read failed"),
        cause = ioErr,
      )
    }
    requireDomainValid(imageBytes.size.toLong() in 1L..MAX_CLOUD_IMAGE_BYTES, "CloudVisionRequest") {
      "Encoded kitchen photo exceeded 6 MB safety cap"
    }
    return Base64.getEncoder().encodeToString(imageBytes)
  }

  companion object {
    /** Strict allowlist of permitted HTTPS Cloud LLM hostnames (SSRF defense). */
    val ALLOWED_CLOUD_HOSTS: Set<String> = setOf(
      "generativelanguage.googleapis.com",
      "api.openai.com",
      "api.anthropic.com",
      "openrouter.ai",
    )

    /** Socket connection timeout in milliseconds (`15s`). */
    const val CONNECT_TIMEOUT_MS: Int = 15_000

    /** Socket read timeout in milliseconds (`45s`). */
    const val READ_TIMEOUT_MS: Int = 45_000

    /** Default max output token budget ensuring JSON menu/vision proposals never truncate. */
    const val DEFAULT_MAX_OUTPUT_TOKENS: Int = 1536

    /** Minimal output token budget for lightweight API key connectivity checks. */
    const val VERIFY_MAX_OUTPUT_TOKENS: Int = 16

    /** Minimal deterministic health-check prompt dispatched by [verifyConnection]. */
    const val VERIFY_HEALTH_PROMPT: String = "Reply with OK"

    /** Required Anthropic Messages API version header value. */
    const val ANTHROPIC_API_VERSION: String = "2023-06-01"

    /** Maximum permitted preprocessed JPEG file size (`6 MB`) for Base64 vision uploads. */
    const val MAX_CLOUD_IMAGE_BYTES: Long = 6L * 1024L * 1024L

    private const val MAX_ALLOWED_OUTPUT_TOKENS: Int = 4096
    private const val DEFAULT_TEMPERATURE: Double = 0.2
    private const val JPEG_MIME_TYPE: String = "image/jpeg"

    /**
     * Validates that [url] uses `https://`, contains zero userinfo or query parameters (`?key=`),
     * contains zero `".."` traversal segments, and targets an exact host in [ALLOWED_CLOUD_HOSTS].
     *
     * @param url Candidate request URL string.
     * @return Validated [URI] instance.
     */
    fun validateAllowedCloudEndpoint(url: String): URI {
      // Block cleartext http:// or custom schemes before parsing URI components.
      requireDomainValid(url.startsWith("https://"), "CloudEndpointUrl") {
        "Cloud LLM endpoint must use strict https:// scheme"
      }
      val parsedUri = try {
        URI(url)
      } catch (syntaxErr: URISyntaxException) {
        throw ValidationException(
          message = "Malformed Cloud LLM endpoint URL.",
          issues = listOf("url: invalid URI syntax"),
          cause = syntaxErr,
        )
      }
      // Enforce exact host allowlist, no @userinfo spoofing, port 443, and zero query parameters.
      val normalizedHost = parsedUri.host?.lowercase().orEmpty()
      requireDomainValid(parsedUri.scheme == "https", "CloudEndpointUrl") {
        "Cloud LLM scheme must be https"
      }
      requireDomainValid(parsedUri.userInfo == null, "CloudEndpointUrl") {
        "Cloud LLM URL must not contain userinfo credentials"
      }
      requireDomainValid(normalizedHost in ALLOWED_CLOUD_HOSTS, "CloudEndpointUrl") {
        "Untrusted Cloud LLM host '$normalizedHost' is not in ALLOWED_CLOUD_HOSTS"
      }
      requireDomainValid(parsedUri.port == -1 || parsedUri.port == 443, "CloudEndpointUrl") {
        "Cloud LLM port must be default HTTPS (443)"
      }
      // Disallowing query strings guarantees API keys can never be leaked in ?key= parameters.
      requireDomainValid(parsedUri.rawQuery.isNullOrEmpty(), "CloudEndpointUrl") {
        "Cloud LLM URL must not include query string parameters"
      }
      requireDomainValid(!parsedUri.rawPath.orEmpty().contains(".."), "CloudEndpointUrl") {
        "Cloud LLM URL path must not contain '..' traversal segments"
      }
      return parsedUri
    }
  }
}
