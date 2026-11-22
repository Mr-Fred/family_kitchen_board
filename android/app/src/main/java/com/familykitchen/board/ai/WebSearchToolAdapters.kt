package com.familykitchen.board.ai

import com.familykitchen.board.data.InMemoryCloudCredentialVault
import com.familykitchen.board.domain.AiExecutionMode
import com.familykitchen.board.domain.ValidationException
import com.familykitchen.board.domain.WebSearchHit
import com.familykitchen.board.domain.WebSearchQuery
import com.familykitchen.board.domain.WebSearchToolOutcome
import com.familykitchen.board.domain.WebSearchToolResult
import com.familykitchen.board.domain.redactApiSecrets
import com.familykitchen.board.domain.requireDomainValid
import com.familykitchen.board.ports.CloudCredentialVaultPort
import com.familykitchen.board.ports.WebSearchToolPort
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URISyntaxException
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

// Hexagonal adapters implementing WebSearchToolPort for SavorAI v2.0:
// 1. OfflineDisabledWebSearchAdapter (default 100% local Gemma posture — 0 network sockets).
// 2. KeystoreCloudWebSearchAdapter (dormant behind CloudCredentialVaultPort + ALLOWED_SEARCH_HOSTS).

/**
 * Pluggable HTTPS JSON POST transport for [KeystoreCloudWebSearchAdapter] enabling 100% hermetic,
 * socket-free JUnit verification of allowlist, offline-dormancy, and header-only auth rules.
 */
fun interface WebSearchHttpTransport {
  /**
   * Sends a validated HTTPS JSON `POST` to an allowlisted search endpoint.
   *
   * @param url Verified `https://` endpoint URL on an allowlisted search host.
   * @param headers Request headers carrying authentication credentials (never URL `?key=`).
   * @param bodyJson UTF-8 JSON search payload.
   * @return Raw [CloudHttpResponse] status code and body.
   */
  suspend fun postSearchJson(url: String, headers: Map<String, String>, bodyJson: String): CloudHttpResponse
}

/**
 * Default production [WebSearchToolPort] adapter active in SavorAI's 100% offline local-Gemma mode.
 *
 * Guarantees **zero** socket creation, DNS lookups, or credential reads—returning an immediate
 * [WebSearchToolOutcome.DISABLED_OFFLINE_MODE] result instructing the Goldfish specialist to rely
 * on the local Antipolo–Marikina catalog and household pantry state.
 */
class OfflineDisabledWebSearchAdapter : WebSearchToolPort {
  /** Returns [WebSearchToolOutcome.DISABLED_OFFLINE_MODE] immediately without opening any socket. */
  override suspend fun searchCulinaryWeb(query: WebSearchQuery): WebSearchToolResult =
    // Preserve 100% air-gapped local Gemma inference by declining external sockets deterministically.
    WebSearchToolResult(
      outcome = WebSearchToolOutcome.DISABLED_OFFLINE_MODE,
      query = query.query.trim(),
      hits = emptyList(),
      diagnosticMessage = "Web search is disabled in 100% offline On-Device Gemma mode. " +
        "Use local household catalog, pantry ledger, and Antipolo–Marikina presets.",
    )
}

/**
 * Production [WebSearchHttpTransport] using platform [HttpsURLConnection] with redirects and URL
 * caches disabled. Invoked **only** when [KeystoreCloudWebSearchAdapter] passes both the
 * `AiExecutionMode.CLOUD_API` gate and stored-credential gate.
 *
 * @param ioDispatcher Background coroutine dispatcher for blocking TLS socket operations.
 */
class HttpsUrlConnectionWebSearchTransport(
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : WebSearchHttpTransport {

  /** Executes a no-redirect, no-store HTTPS JSON `POST` against an allowlisted search host. */
  override suspend fun postSearchJson(url: String, headers: Map<String, String>, bodyJson: String): CloudHttpResponse {
    // Re-verify allowlist invariants right before opening any platform TLS connection.
    val validatedUri = KeystoreCloudWebSearchAdapter.validateAllowedSearchEndpoint(url)
    return withContext(ioDispatcher) {
      val connection = validatedUri.toURL().openConnection() as? HttpsURLConnection
        ?: throw ValidationException("Rejected non-HTTPS search connection for '${validatedUri.host}'.", listOf("url: https"))
      try {
        connection.requestMethod = "POST"
        connection.useCaches = false
        // Block automatic redirects so header credentials never follow cross-host hops.
        connection.instanceFollowRedirects = false
        connection.connectTimeout = KeystoreCloudWebSearchAdapter.CONNECT_TIMEOUT_MS
        connection.readTimeout = KeystoreCloudWebSearchAdapter.READ_TIMEOUT_MS
        connection.doOutput = true

        // Set baseline JSON headers before applying header-only API credentials.
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Cache-Control", "no-store")
        for ((name, value) in headers) connection.setRequestProperty(name, value)

        val payloadBytes = bodyJson.toByteArray(Charsets.UTF_8)
        connection.setFixedLengthStreamingMode(payloadBytes.size)
        connection.outputStream.use { out ->
          out.write(payloadBytes)
          out.flush()
        }

        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        CloudHttpResponse(statusCode = status, body = readBoundedSearchUtf8(stream))
      } finally {
        // Deterministically release socket descriptors across success, HTTP error, or cancellation.
        connection.disconnect()
      }
    }
  }

  // Bound upstream search response buffering to 256 KB to protect 8 GB phone heap memory.
  private fun readBoundedSearchUtf8(stream: InputStream?): String {
    if (stream == null) return ""
    return stream.bufferedReader(Charsets.UTF_8).use { reader ->
      val buf = CharArray(4096)
      val out = StringBuilder()
      while (out.length < MAX_SEARCH_RESPONSE_CHARS) {
        val count = reader.read(buf, 0, minOf(buf.size, MAX_SEARCH_RESPONSE_CHARS - out.length))
        if (count <= 0) break
        out.append(buf, 0, count)
      }
      out.toString()
    }
  }

  private companion object {
    const val MAX_SEARCH_RESPONSE_CHARS: Int = 256 * 1024
  }
}

/**
 * Opt-in HTTPS culinary web search adapter backed by [CloudCredentialVaultPort] and strict
 * [ALLOWED_SEARCH_HOSTS] SSRF allowlisting.
 *
 * @param credentialVault Hardware-backed (`KeystoreCloudCredentialVault`) or in-memory vault port.
 * @param endpointUrl Allowlisted HTTPS search endpoint (`DEFAULT_SEARCH_ENDPOINT_URL` default).
 * @param transport Pluggable HTTPS JSON transport (defaults to [HttpsUrlConnectionWebSearchTransport]).
 * @param dedicatedSearchApiKeyReader Optional reader for a dedicated search key before vault fallback.
 */
class KeystoreCloudWebSearchAdapter(
  private val credentialVault: CloudCredentialVaultPort,
  private val endpointUrl: String = DEFAULT_SEARCH_ENDPOINT_URL,
  private val transport: WebSearchHttpTransport = HttpsUrlConnectionWebSearchTransport(),
  private val dedicatedSearchApiKeyReader: (suspend () -> String?)? = null,
) : WebSearchToolPort {

  // Ignore extraneous provider metadata fields while requiring strict JSON syntax.
  private val responseJson = Json {
    ignoreUnknownKeys = true
    isLenient = false
  }

  /**
   * Executes a culinary search only when `executionMode == CLOUD_API` and a valid key exists;
   * stays completely dormant (`0` sockets) in local-Gemma mode.
   */
  override suspend fun searchCulinaryWeb(query: WebSearchQuery): WebSearchToolResult {
    val cleanQuery = query.query.trim()
    requireDomainValid(cleanQuery.isNotEmpty(), "WebSearchQuery") { "query must be non-blank" }

    // Gate 1: Stay 100% dormant whenever SavorAI is running in local On-Device Gemma mode.
    val config = credentialVault.configFlow.value
    if (config.executionMode == AiExecutionMode.ON_DEVICE) {
      return WebSearchToolResult(
        WebSearchToolOutcome.DISABLED_OFFLINE_MODE, cleanQuery, emptyList(),
        "Cloud web search is dormant while AiExecutionMode is ON_DEVICE (100% offline Gemma mode).",
      )
    }

    // Gate 2: Require a non-blank dedicated search key or stored provider API key before networking.
    val rawKey = dedicatedSearchApiKeyReader?.invoke()?.trim()?.takeIf { it.isNotEmpty() }
      ?: credentialVault.readApiKey(config.provider)?.trim()?.takeIf { it.isNotEmpty() }
    if (rawKey == null) {
      return WebSearchToolResult(
        WebSearchToolOutcome.MISSING_API_KEY, cleanQuery, emptyList(),
        "No API key stored in CloudCredentialVaultPort; configure a key to enable culinary web search.",
      )
    }

    // Gate 3: Validate key format & strict HTTPS allowlist URL before touching transport.
    val cleanKey = InMemoryCloudCredentialVault.validateApiKeyFormat(rawKey)
    val validatedUri = validateAllowedSearchEndpoint(endpointUrl)
    val clampedMax = query.maxResults.coerceIn(1, MAX_ALLOWED_HITS)
    val contextualQuery = if (query.marketRegionHint.isBlank()) cleanQuery else "$cleanQuery (${query.marketRegionHint.trim()})"

    val headers = buildSearchAuthHeaders(validatedUri.host.orEmpty().lowercase(), cleanKey)
    val requestBody = buildJsonObject {
      put("query", contextualQuery)
      put("max_results", clampedMax)
    }.toString()

    val response = try {
      transport.postSearchJson(url = endpointUrl, headers = headers, bodyJson = requestBody)
    } catch (cancel: CancellationException) {
      // Rethrow coroutine cancellation so ViewModel teardown completes immediately.
      throw cancel
    } catch (validationErr: ValidationException) {
      throw validationErr
    } catch (ioErr: IOException) {
      // Redact any echoed credentials before surfacing socket diagnostics.
      val safeDetail = redactApiSecrets(ioErr.message.orEmpty(), cleanKey).take(120)
      return WebSearchToolResult(WebSearchToolOutcome.NETWORK_ERROR, cleanQuery, emptyList(), "Culinary web search network error ($safeDetail).")
    }

    if (response.statusCode !in 200..299) {
      val safeSnippet = redactApiSecrets(response.body.take(120), cleanKey)
      return WebSearchToolResult(WebSearchToolOutcome.NETWORK_ERROR, cleanQuery, emptyList(), "Culinary web search HTTP ${response.statusCode} ($safeSnippet).")
    }

    val hits = parseSearchHits(response.body, clampedMax)
    return WebSearchToolResult(WebSearchToolOutcome.EXECUTED, cleanQuery, hits, "Retrieved ${hits.size} grounded culinary web result(s).")
  }

  // Send search credentials strictly inside HTTP headers (never in URL query strings).
  private fun buildSearchAuthHeaders(host: String, cleanKey: String): Map<String, String> =
    buildMap {
      put("Content-Type", "application/json; charset=utf-8")
      put("Accept", "application/json")
      put("Cache-Control", "no-store")
      when (host) {
        "api.search.brave.com" -> put("X-Subscription-Token", cleanKey)
        "customsearch.googleapis.com", "generativelanguage.googleapis.com" -> put("x-goog-api-key", cleanKey)
        else -> put("Authorization", "Bearer $cleanKey")
      }
    }

  private fun parseSearchHits(rawBody: String, maxHits: Int): List<WebSearchHit> {
    if (rawBody.isBlank()) return emptyList()
    val root = try {
      responseJson.parseToJsonElement(rawBody).jsonObject
    } catch (_: SerializationException) {
      return emptyList()
    } catch (_: IllegalArgumentException) {
      return emptyList()
    }

    // Support Tavily (`results`), Google CSE (`items`), and Brave (`web.results`) envelopes.
    val rawArray = (root["results"] as? JsonArray)
      ?: (root["items"] as? JsonArray)
      ?: ((root["web"] as? JsonObject)?.get("results") as? JsonArray)
      ?: return emptyList()

    return rawArray.asSequence().mapNotNull { el ->
      val obj = el as? JsonObject ?: return@mapNotNull null
      val title = ((obj["title"] as? JsonPrimitive)?.contentOrNull).orEmpty().trim()
      val url = ((obj["url"] ?: obj["link"]) as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
      val snippet = ((obj["content"] ?: obj["snippet"] ?: obj["description"]) as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
      // Accept only clean https:// result links to block javascript:/file:/http: injection.
      if (title.isEmpty() || !url.startsWith("https://")) {
        null
      } else {
        val domain = runCatching { URI(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")
        WebSearchHit(title.take(140), url, snippet.take(280), domain)
      }
    }.take(maxHits).toList()
  }

  companion object {
    /** Strict HTTPS hostname allowlist for opt-in culinary web search (SSRF defense). */
    val ALLOWED_SEARCH_HOSTS: Set<String> = setOf(
      "api.tavily.com",
      "api.search.brave.com",
      "customsearch.googleapis.com",
      "generativelanguage.googleapis.com",
    )

    /** Default allowlisted HTTPS culinary search endpoint. */
    const val DEFAULT_SEARCH_ENDPOINT_URL: String = "https://api.tavily.com/search"

    /** Connection timeout in milliseconds (`10s`). */
    const val CONNECT_TIMEOUT_MS: Int = 10_000

    /** Read timeout in milliseconds (`20s`). */
    const val READ_TIMEOUT_MS: Int = 20_000

    private const val MAX_ALLOWED_HITS: Int = 5

    /**
     * Validates that [url] uses `https://`, targets an exact host in [ALLOWED_SEARCH_HOSTS], uses
     * port `443`, and carries zero userinfo, query parameters (`?key=`), or `".."` segments.
     *
     * @param url Candidate search endpoint URL.
     * @return Validated [URI].
     */
    fun validateAllowedSearchEndpoint(url: String): URI {
      // Enforce https:// upfront before URI component inspection.
      requireDomainValid(url.startsWith("https://"), "WebSearchEndpoint") { "Search endpoint must use strict https:// scheme" }
      val uri = try {
        URI(url)
      } catch (syntaxErr: URISyntaxException) {
        throw ValidationException("Malformed search endpoint URL.", listOf("url: invalid URI syntax"), syntaxErr)
      }
      val host = uri.host?.lowercase().orEmpty()
      requireDomainValid(uri.scheme == "https", "WebSearchEndpoint") { "Search endpoint scheme must be https" }
      requireDomainValid(uri.userInfo == null, "WebSearchEndpoint") { "Search endpoint must not include userinfo credentials" }
      requireDomainValid(host in ALLOWED_SEARCH_HOSTS, "WebSearchEndpoint") { "Untrusted search host '$host' is not in ALLOWED_SEARCH_HOSTS" }
      requireDomainValid(uri.port == -1 || uri.port == 443, "WebSearchEndpoint") { "Search endpoint port must be default HTTPS (443)" }
      requireDomainValid(uri.rawQuery.isNullOrEmpty(), "WebSearchEndpoint") { "Search endpoint URL must not include query parameters" }
      requireDomainValid(!uri.rawPath.orEmpty().contains(".."), "WebSearchEndpoint") { "Search endpoint path must not contain '..' traversal segments" }
      return uri
    }
  }
}
