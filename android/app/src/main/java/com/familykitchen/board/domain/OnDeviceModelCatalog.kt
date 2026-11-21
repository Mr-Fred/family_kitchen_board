package com.familykitchen.board.domain

import kotlinx.serialization.Serializable

// Pure Kotlin on-device Gemma 4 (.litertlm) download catalog, user-approval precondition contracts,
// and zero-java.net HTTPS / SSRF / redirect allowlist validators for SavorAI.

/**
 * Official `litert-community` Hugging Face Gemma 4 `.litertlm` packages available for 1-tap
 * user-approved on-device download into private app storage (`filesDir/models`).
 *
 * @property presetId Stable identifier for telemetry-free state persistence and UI selection.
 * @property displayName Human-readable model title shown inside `ModelDownloadApprovalDialog`.
 * @property shortBadge Compact hardware + approximate size subtitle shown on preset filter chips.
 * @property fileName Canonical single-segment `.litertlm` filename stored under `filesDir/models`.
 * @property downloadUrl Official `https://huggingface.co/litert-community/` resolve endpoint URL.
 * @property approxSizeBytes Approximate compressed weight transfer size in bytes.
 * @property requiredFreeSpaceBytes Recommended free disk space headroom before starting download.
 * @property licenseLabel Open license and account-free source attribution note.
 * @property supportsVision `true` when the `.litertlm` bundle includes multimodal vision weights.
 * @property targetProfile Hardware [EdgeDeviceProfile] activated when this preset is selected.
 */
@Serializable
enum class DownloadableGemmaPreset(
  val presetId: String,
  val displayName: String,
  val shortBadge: String,
  val fileName: String,
  val downloadUrl: String,
  val approxSizeBytes: Long,
  val requiredFreeSpaceBytes: Long,
  val licenseLabel: String,
  val supportsVision: Boolean,
  val targetProfile: EdgeDeviceProfile,
) {
  // Recommended 2B multimodal vision + text bundle fitting comfortably on 8 GB+ RAM Android devices.
  /** Recommended ~2.0 GB Gemma 4 E2B multimodal vision + text `.litertlm` package for 8 GB+ RAM devices. */
  GEMMA_4_E2B_VISION(
    presetId = "gemma_4_e2b_it",
    displayName = "Gemma 4 E2B (Vision + Text)",
    shortBadge = "Recommended (8 GB+ RAM) • ~2.0 GB",
    fileName = "gemma-4-E2B-it.litertlm",
    downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
    approxSizeBytes = 2_050L * 1024L * 1024L,
    requiredFreeSpaceBytes = 3_100L * 1024L * 1024L,
    licenseLabel = "Apache 2.0 • Official litert-community (No account required)",
    supportsVision = true,
    targetProfile = EdgeDeviceProfile.GEMMA_4_E2B_MULTIMODAL,
  ),

  // Higher-capacity E4B multimodal bundle calibrated for 12 GB+ RAM Android phones and tablets.
  /** Pro ~3.2 GB Gemma 4 E4B multimodal vision + text `.litertlm` package for 12 GB+ RAM devices. */
  GEMMA_4_E4B_VISION(
    presetId = "gemma_4_e4b_it",
    displayName = "Gemma 4 E4B (Pro Vision + Text)",
    shortBadge = "12 GB+ RAM Devices • ~3.2 GB",
    fileName = "gemma-4-E4B-it.litertlm",
    downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
    approxSizeBytes = 3_300L * 1024L * 1024L,
    requiredFreeSpaceBytes = 4_500L * 1024L * 1024L,
    licenseLabel = "Apache 2.0 • Official litert-community (No account required)",
    supportsVision = true,
    targetProfile = EdgeDeviceProfile.GEMMA_4_E2B_MULTIMODAL,
  ),
}

/**
 * Active network connectivity and carrier-metering classification inspected prior to opening
 * `ModelDownloadApprovalDialog`.
 */
@Serializable
enum class NetworkMeteringStatus {
  // Unmetered Wi-Fi or Ethernet where multi-GB model downloads incur zero mobile carrier fees.
  /** Active internet connection over unmetered Wi-Fi or Ethernet. */
  UNMETERED_WIFI,

  // Metered mobile cellular or hotspot connection requiring an explicit carrier-data warning.
  /** Active internet connection over metered cellular or mobile hotspot data. */
  METERED_CELLULAR,

  // Airplane mode, disconnected Wi-Fi, or unverified captive portal with no internet route.
  /** No active internet capability detected (`Approve & Download` stays disabled until online). */
  OFFLINE_OR_UNKNOWN,
}

/**
 * Pre-flight storage, network-metering, and resumable `.part` snapshot presented to the user in
 * `ModelDownloadApprovalDialog` before any network socket is opened.
 *
 * @property preset Selected [DownloadableGemmaPreset] candidate.
 * @property availableFreeBytes Free bytes in app storage from `StatFs`, or `-1L` when unknown.
 * @property networkStatus Current Wi-Fi vs metered cellular vs offline status.
 * @property existingPartialBytes Bytes already saved in `<fileName>.part` from an interrupted run.
 */
@Serializable
data class ModelDownloadApprovalSpec(
  val preset: DownloadableGemmaPreset = DownloadableGemmaPreset.GEMMA_4_E2B_VISION,
  val availableFreeBytes: Long = -1L,
  val networkStatus: NetworkMeteringStatus = NetworkMeteringStatus.UNMETERED_WIFI,
  val existingPartialBytes: Long = 0L,
) {
  /** Estimated remaining bytes to transfer after subtracting any resumable `.part` bytes. */
  val remainingDownloadBytes: Long
    get() = (preset.approxSizeBytes - existingPartialBytes.coerceAtLeast(0L)).coerceAtLeast(0L)

  /**
   * `true` when free disk space is unknown (`< 0L`) or meets the preset's remaining headroom
   * requirement (always enforcing at least `512 MB` of safety headroom).
   */
  val hasEnoughStorage: Boolean
    get() = availableFreeBytes < 0L ||
      availableFreeBytes >= (preset.requiredFreeSpaceBytes - existingPartialBytes.coerceAtLeast(0L))
        .coerceAtLeast(512L * 1024L * 1024L)

  /** `true` when a non-empty `<fileName>.part` file is ready for HTTP `Range` resumption. */
  val isResumingPartialDownload: Boolean
    get() = existingPartialBytes > 0L

  /** `true` when connected over metered mobile data so a carrier-charge notice is displayed. */
  val isMeteredWarningRequired: Boolean
    get() = networkStatus == NetworkMeteringStatus.METERED_CELLULAR

  /** `true` when no active internet connection is detected so download approval is gated. */
  val isOfflineWarning: Boolean
    get() = networkStatus == NetworkMeteringStatus.OFFLINE_OR_UNKNOWN
}

// Minimum 512 MB post-download disk headroom floor protecting SQLite/JSON household writes.
private const val HTTPS_SCHEME_PREFIX: String = "https://"
private const val INITIAL_HOST: String = "huggingface.co"
private const val INITIAL_PATH_PREFIX: String = "/litert-community/"
private const val LITERT_MODEL_EXTENSION: String = ".litertlm"

// Exact CDN / Hub hosts permitted during Hugging Face model resolution and LFS/Xet redirects.
private val EXACT_ALLOWED_REDIRECT_HOSTS: Set<String> = setOf(
  "huggingface.co",
  "storage.googleapis.com",
)

// Wildcard subdomain suffixes permitted for Hugging Face Xet, LFS, CloudFront, S3, and R2 CDNs.
private val WILDCARD_ALLOWED_REDIRECT_SUFFIXES: List<String> = listOf(
  ".huggingface.co",
  ".hf.co",
  ".cloudfront.net",
  ".amazonaws.com",
  ".r2.cloudflarestorage.com",
)

/**
 * Validates the initial model download URL using 100% pure Kotlin string parsing (zero `java.net`).
 *
 * Enforces:
 * - Strict `https://huggingface.co/litert-community/` prefix,
 * - Zero `?` query parameters and zero `#` fragments,
 * - Zero `@` userinfo credentials and zero custom `:port` segments,
 * - Zero `..` (or `%2e%2e`) path traversal segments and zero `\` backslashes,
 * - Non-empty model filename ending in `.litertlm`.
 *
 * @param rawUrl Candidate initial download URL string.
 * @return Trimmed, validated `https://` URL string.
 * @throws ValidationException When any HTTPS, host, org-prefix, query, or `.litertlm` check fails.
 */
fun validateInitialModelDownloadUrl(rawUrl: String): String {
  val parsed = parseStrictHttpsUrl(rawUrl = rawUrl, entityName = "InitialModelDownloadUrl")

  // Initial model requests must never include custom ports (not even explicit :443) or queries.
  requireDomainValid(parsed.explicitPort == null, "InitialModelDownloadUrl") {
    "Initial model download URL must not specify an explicit ':port'"
  }
  requireDomainValid(parsed.rawQuery == null, "InitialModelDownloadUrl") {
    "Initial model download URL must not contain '?' query parameters"
  }
  requireDomainValid(parsed.host == INITIAL_HOST, "InitialModelDownloadUrl") {
    "Initial model download host must be '$INITIAL_HOST' (got '${parsed.host}')"
  }
  requireDomainValid(
    condition = parsed.rawPath.startsWith(INITIAL_PATH_PREFIX) &&
      parsed.rawPath.length > INITIAL_PATH_PREFIX.length,
    entityName = "InitialModelDownloadUrl",
  ) {
    "Initial model download path must start with '$INITIAL_PATH_PREFIX'"
  }
  val leafName = parsed.rawPath.substringAfterLast('/')
  requireDomainValid(
    condition = parsed.rawPath.endsWith(LITERT_MODEL_EXTENSION) &&
      leafName.length > LITERT_MODEL_EXTENSION.length &&
      !parsed.rawPath.contains("//"),
    entityName = "InitialModelDownloadUrl",
  ) {
    "Initial model download path must end with a valid '$LITERT_MODEL_EXTENSION' filename"
  }
  return parsed.normalizedUrl
}

/**
 * Validates an HTTP `301/302/303/307/308` redirect target URL during a Gemma 4 download using
 * 100% pure Kotlin string parsing (zero `java.net`).
 *
 * Enforces:
 * - Strict `https://` scheme (blocks HTTPS-to-HTTP downgrade redirects),
 * - Zero `@` userinfo credentials, zero `..` path traversal, and no custom `:port` (except `:443`),
 * - Explicit SSRF rejection of `localhost`, `127.*`, `10.*`, `192.168.*`, `169.254.*`,
 *   `172.16..31.*`, `[::1]`, `.local`, and `.internal`,
 * - Host matching the official Hugging Face & Xet/LFS CDN allowlist (`huggingface.co`,
 *   `*.huggingface.co`, `*.hf.co`, `*.cloudfront.net`, `*.amazonaws.com`,
 *   `*.r2.cloudflarestorage.com`, `storage.googleapis.com`).
 *
 * @param rawUrl Candidate redirect `Location` URL (already resolved if originally root-relative).
 * @return Trimmed, validated `https://` redirect URL string.
 * @throws ValidationException When any scheme, SSRF, port, traversal, or CDN allowlist rule fails.
 */
fun validateModelRedirectUrl(rawUrl: String): String {
  val parsed = parseStrictHttpsUrl(rawUrl = rawUrl, entityName = "ModelRedirectUrl")

  // Redirect URLs permit default port or explicit :443 only; any other port is rejected.
  requireDomainValid(
    condition = parsed.explicitPort == null || parsed.explicitPort == 443,
    entityName = "ModelRedirectUrl",
  ) {
    "Redirect URL port must be default HTTPS or 443 (got ':${parsed.explicitPort}')"
  }
  requireDomainValid(!isForbiddenLoopbackOrPrivateHost(parsed.host), "ModelRedirectUrl") {
    "SSRF guard rejected private, loopback, or internal redirect host '${parsed.host}'"
  }
  requireDomainValid(isAllowedModelCdnHost(parsed.host), "ModelRedirectUrl") {
    "Untrusted redirect host '${parsed.host}' is not in the Hugging Face / LFS CDN allowlist"
  }
  requireDomainValid(parsed.rawPath.startsWith("/") && parsed.rawPath.length > 1, "ModelRedirectUrl") {
    "Redirect URL must include a non-root resource path"
  }
  return parsed.normalizedUrl
}

// Immutable components extracted by pure-Kotlin RFC-3986 authority + path + query decomposition.
private data class ParsedHttpsComponents(
  val normalizedUrl: String,
  val host: String,
  val explicitPort: Int?,
  val rawPath: String,
  val rawQuery: String?,
)

// Parses and validates scheme, control chars, authority, userinfo, port, and '..' path traversal.
private fun parseStrictHttpsUrl(rawUrl: String, entityName: String): ParsedHttpsComponents {
  val trimmed = rawUrl.trim()
  requireDomainValid(trimmed.isNotEmpty(), entityName) {
    "URL must not be blank"
  }
  // Reject whitespace and ASCII control characters (prevents CRLF header/request splitting).
  requireDomainValid(
    condition = trimmed.none { ch -> ch.code <= 0x20 || ch.code == 0x7F },
    entityName = entityName,
  ) {
    "URL must not contain whitespace or control characters"
  }
  requireDomainValid(trimmed.startsWith(HTTPS_SCHEME_PREFIX), entityName) {
    "URL must use strict 'https://' scheme"
  }
  requireDomainValid(!trimmed.contains('#'), entityName) {
    "URL must not contain '#' fragment identifiers"
  }

  val afterScheme = trimmed.substring(HTTPS_SCHEME_PREFIX.length)
  val slashIdx = afterScheme.indexOf('/')
  val queryIdx = afterScheme.indexOf('?')
  val authorityEnd = when {
    slashIdx >= 0 && queryIdx >= 0 -> minOf(slashIdx, queryIdx)
    slashIdx >= 0 -> slashIdx
    queryIdx >= 0 -> queryIdx
    else -> afterScheme.length
  }

  val authority = afterScheme.substring(0, authorityEnd)
  val rest = afterScheme.substring(authorityEnd)
  val qIdxInRest = rest.indexOf('?')
  val rawPath = when {
    qIdxInRest >= 0 -> rest.substring(0, qIdxInRest)
    else -> rest
  }
  val rawQuery = when {
    qIdxInRest >= 0 -> rest.substring(qIdxInRest + 1)
    else -> null
  }

  // Block backslash authority/path confusion (e.g. https://huggingface.co\@127.0.0.1/).
  requireDomainValid(!authority.contains('\\') && !rawPath.contains('\\'), entityName) {
    "URL authority and path must not contain '\\' backslashes"
  }
  requireDomainValid(authority.isNotEmpty(), entityName) {
    "URL host authority must not be empty"
  }
  // Reject both literal '@' and percent-encoded '%40' userinfo spoofing inside authority.
  requireDomainValid(
    condition = !authority.contains('@') && !authority.lowercase().contains("%40"),
    entityName = entityName,
  ) {
    "URL must not contain '@' userinfo credentials"
  }
  // Reject IPv6 bracketed literals such as [::1] immediately before splitting host:port.
  requireDomainValid(!authority.contains('[') && !authority.contains(']'), entityName) {
    "IPv6 literal hosts such as '[::1]' are not permitted"
  }

  val colonCount = authority.count { it == ':' }
  requireDomainValid(colonCount <= 1, entityName) {
    "Malformed authority '$authority' with multiple ':' delimiters"
  }

  val rawHost: String
  val explicitPort: Int?
  when (colonCount) {
    1 -> {
      rawHost = authority.substringBefore(':')
      val portText = authority.substringAfter(':')
      val parsedPort = portText.toIntOrNull()
      requireDomainValid(
        condition = portText.isNotEmpty() && parsedPort != null && parsedPort in 1..65535,
        entityName = entityName,
      ) {
        "Invalid explicit port ':$portText' in URL"
      }
      explicitPort = parsedPort
    }
    else -> {
      rawHost = authority
      explicitPort = null
    }
  }

  val normalizedHost = rawHost.lowercase()
  requireDomainValid(normalizedHost.isNotEmpty(), entityName) {
    "URL hostname must not be empty"
  }

  // Decode percent-encoded dots (%2e / %2E) and slashes (%2f / %2F) to catch disguised '..'.
  val canonicalPathCheck = rawPath
    .lowercase()
    .replace("%2e", ".")
    .replace("%2f", "/")
    .replace("%5c", "/")
  requireDomainValid(
    condition = !rawPath.contains("..") && !canonicalPathCheck.contains(".."),
    entityName = entityName,
  ) {
    "URL path must not contain '..' traversal segments"
  }

  return ParsedHttpsComponents(
    normalizedUrl = trimmed,
    host = normalizedHost,
    explicitPort = explicitPort,
    rawPath = rawPath,
    rawQuery = rawQuery,
  )
}

// Strictly blocks loopback, RFC1918 private, link-local metadata (169.254.*), .local, and .internal.
private fun isForbiddenLoopbackOrPrivateHost(host: String): Boolean {
  if (
    host == "localhost" ||
    host.endsWith(".localhost") ||
    host.endsWith(".local") ||
    host.endsWith(".internal") ||
    host == "::1"
  ) {
    return true
  }
  val octets = host.split('.')
  if (octets.size == 4 && octets.all { part -> part.isNotEmpty() && part.all(Char::isDigit) }) {
    // Flag loopback (127.*), unspecified (0.*), link-local metadata (169.254.*), and RFC1918 ranges.
    val first = octets[0].toIntOrNull() ?: return true
    val second = octets[1].toIntOrNull() ?: return true
    return first == 0 ||
      first == 10 ||
      first == 127 ||
      (first == 169 && second == 254) ||
      (first == 172 && second in 16..31) ||
      (first == 192 && second == 168)
  }
  return false
}

// Verifies DNS label syntax and matches official Hugging Face & Xet/LFS CDN host allowlist.
private fun isAllowedModelCdnHost(host: String): Boolean {
  if (host.startsWith('.') || host.endsWith('.') || host.contains("..")) return false
  val labels = host.split('.')
  val hasValidDnsLabels = labels.size >= 2 && labels.all { label ->
    label.isNotEmpty() &&
      !label.startsWith('-') &&
      !label.endsWith('-') &&
      label.all { ch -> ch in 'a'..'z' || ch in '0'..'9' || ch == '-' }
  }
  if (!hasValidDnsLabels) return false

  return when {
    host in EXACT_ALLOWED_REDIRECT_HOSTS -> true
    WILDCARD_ALLOWED_REDIRECT_SUFFIXES.any { suffix ->
      host.endsWith(suffix) && host.length > suffix.length
    } -> true
    else -> false
  }
}
