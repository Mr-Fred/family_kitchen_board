package com.familykitchen.board.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import com.familykitchen.board.domain.DownloadableGemmaPreset
import com.familykitchen.board.domain.ModelDownloadApprovalSpec
import com.familykitchen.board.domain.NetworkMeteringStatus
import com.familykitchen.board.domain.ValidationException
import com.familykitchen.board.domain.requireDomainValid
import com.familykitchen.board.domain.validateInitialModelDownloadUrl
import com.familykitchen.board.domain.validateModelRedirectUrl
import com.familykitchen.board.ports.DiscoveredModelInfo
import com.familykitchen.board.ports.ModelFileFormat
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

// Approval-gated Hugging Face Gemma 4 (.litertlm) downloader with manual SSRF-checked redirect
// following, HTTP Range .part resumption, and deterministic cancellation cleanup.

/**
 * Single-hop HTTP response handle returned by [ModelDownloadHttpTransport.openStream].
 *
 * Implements [Closeable] so both intermediate `3xx` redirect hops and active streaming bodies
 * deterministically release their underlying streams and TLS socket descriptors.
 *
 * @property statusCode Numeric HTTP response status (`200`, `206`, `302`, `307`, `416`, etc.).
 * @property headers Response header map (case-insensitively queried via [header]).
 * @property contentLength Reported `Content-Length` byte count, or `-1L` when unknown.
 * @property bodyStream Raw binary payload stream (`Empty` on `3xx` redirect hops).
 * @property onClose Callback invoked by [close] to disconnect underlying socket resources.
 */
class ModelDownloadHttpResponse(
  val statusCode: Int,
  val headers: Map<String, String> = emptyMap(),
  val contentLength: Long = -1L,
  val bodyStream: InputStream = ByteArrayInputStream(ByteArray(0)),
  private val onClose: () -> Unit = {},
) : Closeable {

  /** Looks up a response header value case-insensitively (`Location`, `Content-Range`, etc.). */
  fun header(name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.trim()

  /** Closes [bodyStream] and releases the underlying transport connection quietly. */
  override fun close() {
    runCatching { bodyStream.close() }
    runCatching { onClose() }
  }
}

/**
 * Pluggable single-hop HTTPS streaming transport enabling 100% socket-free hermetic JUnit tests
 * for redirects, SSRF blocking, `206` `Range` resumption, `416` recovery, and cancellation.
 */
fun interface ModelDownloadHttpTransport {
  /**
   * Opens a single-hop (`instanceFollowRedirects = false`) HTTPS `GET` connection to [url].
   *
   * @param url Validated `https://` URL on an allowlisted Hugging Face or CDN host.
   * @param headers Request headers (such as `Range: bytes=N-` and `Accept-Encoding: identity`).
   * @return Open [ModelDownloadHttpResponse] owned and closed by the caller.
   */
  suspend fun openStream(url: String, headers: Map<String, String>): ModelDownloadHttpResponse
}

/**
 * Production [ModelDownloadHttpTransport] backed by platform [HttpsURLConnection] with automatic
 * redirects disabled (`instanceFollowRedirects = false`) so every hop passes SSRF validation.
 *
 * @param ioDispatcher Background coroutine dispatcher executing blocking TLS socket operations.
 */
class HttpsUrlConnectionModelDownloadTransport(
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ModelDownloadHttpTransport {

  /** Opens a validated `https://` single-hop stream with redirects and response caching disabled. */
  override suspend fun openStream(
    url: String,
    headers: Map<String, String>,
  ): ModelDownloadHttpResponse {
    // Re-validate scheme, port, traversal, and CDN host allowlist before touching any socket.
    val validatedUrl = validateModelRedirectUrl(url)
    return withContext(ioDispatcher) {
      val httpsConnection = URI(validatedUrl).toURL().openConnection() as? HttpsURLConnection
        ?: throw ValidationException(
          message = "Rejected non-HTTPS connection class while downloading model.",
          issues = listOf("url: requires HttpsURLConnection"),
        )

      try {
        httpsConnection.requestMethod = "GET"
        httpsConnection.useCaches = false
        // Disable automatic redirects so Location targets cannot bypass validateModelRedirectUrl.
        httpsConnection.instanceFollowRedirects = false
        httpsConnection.connectTimeout = OnDeviceModelDownloader.CONNECT_TIMEOUT_MS
        httpsConnection.readTimeout = OnDeviceModelDownloader.READ_TIMEOUT_MS
        httpsConnection.doInput = true
        // Request identity encoding so byte offsets match raw .litertlm file positions 1:1.
        httpsConnection.setRequestProperty("Accept-Encoding", "identity")
        httpsConnection.setRequestProperty("Cache-Control", "no-store")
        for ((headerName, headerValue) in headers) {
          httpsConnection.setRequestProperty(headerName, headerValue)
        }

        val status = httpsConnection.responseCode
        // Flatten multi-value HTTP headers into a case-preserving map for Location & Content-Range.
        val flatHeaders = buildMap {
          for ((rawKey, values) in httpsConnection.headerFields) {
            if (rawKey != null && !values.isNullOrEmpty()) {
              put(rawKey, values.filterNotNull().joinToString(", "))
            }
          }
        }
        // Skip opening inputStream on 3xx hops so redirect sockets close with zero payload reads.
        val bodyStream = when {
          status in 300..399 -> ByteArrayInputStream(ByteArray(0))
          status in 200..299 -> httpsConnection.inputStream
          else -> httpsConnection.errorStream ?: ByteArrayInputStream(ByteArray(0))
        }
        ModelDownloadHttpResponse(
          statusCode = status,
          headers = flatHeaders,
          contentLength = httpsConnection.contentLengthLong,
          bodyStream = bodyStream,
          onClose = { httpsConnection.disconnect() },
        )
      } catch (err: Throwable) {
        // Release socket descriptor immediately when handshake or header parsing fails.
        httpsConnection.disconnect()
        throw err
      }
    }
  }
}

/**
 * Resumable, SSRF-hardened Hugging Face Gemma 4 `.litertlm` downloader writing directly into
 * private app storage (`filesDir/models/<fileName>.part`) prior to atomic `fd.sync()` + `renameTo`.
 *
 * Key guarantees:
 * 1. **Zero Duplicate Storage**: Streams directly into `filesDir/models/<fileName>.part` so a 2 GB
 *    Gemma 4 bundle never leaves a second 2 GB copy in shared `Downloads/`.
 * 2. **SSRF-Checked Redirects**: Validates the initial URL with [validateInitialModelDownloadUrl]
 *    and manually follows up to [MAX_REDIRECTS] (`6`) `301/302/303/307/308` hops validated via
 *    [validateModelRedirectUrl], forwarding `Range: bytes=$existingBytes-` across every hop.
 * 3. **Resumable `.part` vs Clean Cancel**: Preserves `<fileName>.part` on transient [IOException]
 *    so retries resume via HTTP `206 Partial Content`, while explicit [cancelActiveDownload]
 *    immediately closes the active stream, **deletes** `<fileName>.part`, and throws
 *    [CancellationException].
 *
 * @param modelsDirProvider Lazy provider resolving the private `filesDir/models` directory.
 * @param transport Injectable HTTP streaming transport (0 network sockets in JUnit tests).
 * @param availableBytesProvider Queries free disk space in bytes (`-1L` when unknown).
 * @param networkStatusProvider Queries Wi-Fi vs metered cellular vs offline connectivity.
 * @param ioDispatcher Background coroutine dispatcher for disk and stream transfers.
 */
class OnDeviceModelDownloader(
  private val modelsDirProvider: () -> File,
  private val transport: ModelDownloadHttpTransport = HttpsUrlConnectionModelDownloadTransport(),
  private val availableBytesProvider: (File) -> Long = ::queryStatFsAvailableBytes,
  private val networkStatusProvider: () -> NetworkMeteringStatus = {
    NetworkMeteringStatus.UNMETERED_WIFI
  },
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

  /**
   * Convenience constructor targeting an explicit [modelsDir] (ideal for hermetic JUnit suites).
   */
  constructor(
    modelsDir: File,
    transport: ModelDownloadHttpTransport = HttpsUrlConnectionModelDownloadTransport(),
    availableBytesProvider: (File) -> Long = { -1L },
    networkStatusProvider: () -> NetworkMeteringStatus = { NetworkMeteringStatus.UNMETERED_WIFI },
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  ) : this(
    modelsDirProvider = { modelsDir },
    transport = transport,
    availableBytesProvider = availableBytesProvider,
    networkStatusProvider = networkStatusProvider,
    ioDispatcher = ioDispatcher,
  )

  /**
   * Production Android constructor resolving `File(context.filesDir, "models")`, `StatFs` free
   * bytes, and `ConnectivityManager` Wi-Fi / cellular metering safely inside `runCatching`.
   */
  constructor(
    context: Context,
    transport: ModelDownloadHttpTransport = HttpsUrlConnectionModelDownloadTransport(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  ) : this(
    modelsDirProvider = {
      val appCtx = context.applicationContext ?: context
      File(appCtx.filesDir, ModelFileDiscovery.MODELS_SUBDIR)
    },
    transport = transport,
    availableBytesProvider = ::queryStatFsAvailableBytes,
    networkStatusProvider = {
      inspectAndroidNetworkMetering(context.applicationContext ?: context)
    },
    ioDispatcher = ioDispatcher,
  )

  // Cooperative + interruptive user cancellation flag paired with the active HTTP response handle.
  private val cancelRequested = AtomicBoolean(false)

  @Volatile
  private var activeResponse: Closeable? = null

  /**
   * Inspects local free storage (`StatFs`), active network metering (`ConnectivityManager`), and
   * any resumable `<fileName>.part` byte offset for [preset] without opening any network socket.
   *
   * @param preset Candidate Gemma 4 `.litertlm` preset to inspect.
   * @return Populated [ModelDownloadApprovalSpec] for `ModelDownloadApprovalDialog`.
   */
  suspend fun inspectPreconditions(
    preset: DownloadableGemmaPreset = DownloadableGemmaPreset.GEMMA_4_E2B_VISION,
  ): ModelDownloadApprovalSpec =
    withContext(ioDispatcher) {
      // Inspect disk headroom, Wi-Fi/cellular metering, and .part resume size off the main thread.
      val modelsDir = modelsDirProvider()
      val sanitizedName = ModelFileDiscovery.sanitizeModelFileName(preset.fileName)
      val partFile = File(modelsDir, "$sanitizedName${ModelFileDiscovery.PARTIAL_DOWNLOAD_SUFFIX}")
      // Report non-zero partial bytes only when a regular .part staging file is present on disk.
      val partialBytes = when {
        partFile.isFile -> partFile.length().coerceAtLeast(0L)
        else -> 0L
      }
      // Fall back safely to -1L / UNMETERED_WIFI when platform services are unmocked in JVM tests.
      val freeBytes = runCatching { availableBytesProvider(modelsDir) }.getOrDefault(-1L)
      val netStatus = runCatching { networkStatusProvider() }
        .getOrDefault(NetworkMeteringStatus.UNMETERED_WIFI)
      ModelDownloadApprovalSpec(
        preset = preset,
        availableFreeBytes = freeBytes,
        networkStatus = netStatus,
        existingPartialBytes = partialBytes,
      )
    }

  /**
   * Requests immediate cancellation of any active download, closing the underlying stream to wake
   * blocked socket reads and unlinking the incomplete `<fileName>.part` file.
   */
  fun cancelActiveDownload() {
    // Flip cancelRequested BEFORE closing activeResponse so read() IOException maps to cancel.
    cancelRequested.set(true)
    runCatching { activeResponse?.close() }
  }

  /**
   * Downloads (or resumes via HTTP `Range`) [preset] into `filesDir/models/<fileName>.part`,
   * verifies final byte integrity, and atomically promotes it to `<fileName>`.
   *
   * @param preset User-approved [DownloadableGemmaPreset] to download.
   * @param minSizeBytes Minimum valid completed model byte length (`100 MB` default).
   * @param onProgress Callback receiving `(copiedBytes, totalBytes, fraction0To1)`.
   * @return Validated [DiscoveredModelInfo] ready for `LiteRtMultimodalEngine` initialization.
   */
  suspend fun downloadPresetModel(
    preset: DownloadableGemmaPreset = DownloadableGemmaPreset.GEMMA_4_E2B_VISION,
    minSizeBytes: Long = ModelFileDiscovery.MIN_MODEL_FILE_SIZE_BYTES,
    onProgress: (copiedBytes: Long, totalBytes: Long, progressFraction: Float) -> Unit = { _, _, _ -> },
  ): DiscoveredModelInfo =
    withContext(ioDispatcher) {
      // Enforce strict https://huggingface.co/litert-community/ validation before any file/net I/O.
      val initialUrl = validateInitialModelDownloadUrl(preset.downloadUrl)
      // Sanitize preset filename to prevent directory traversal outside private filesDir/models.
      val sanitizedName = ModelFileDiscovery.sanitizeModelFileName(preset.fileName)
      val modelsDir = modelsDirProvider()
      if (!modelsDir.exists() && !modelsDir.mkdirs() && !modelsDir.isDirectory) {
        throw ValidationException(
          message = "Unable to create private models directory for '${preset.fileName}'.",
          issues = listOf("modelsDir: mkdirs failed"),
        )
      }

      val destFile = File(modelsDir, sanitizedName)
      val partFile = File(modelsDir, "$sanitizedName${ModelFileDiscovery.PARTIAL_DOWNLOAD_SUFFIX}")

      // Fast-path: skip network transfer completely when verified model weights already exist.
      if (destFile.isFile && destFile.length() > minSizeBytes) {
        val readySize = destFile.length()
        onProgress(readySize, readySize, 1.0f)
        return@withContext DiscoveredModelInfo(
          fileName = destFile.name,
          absolutePath = destFile.absolutePath,
          sizeBytes = readySize,
          format = ModelFileFormat.LITERT_LM,
          supportsVisionCandidate = preset.supportsVision,
          recommendedProfile = preset.targetProfile,
        )
      }

      cancelRequested.set(false)
      try {
        var existingBytes = when {
          partFile.isFile -> partFile.length().coerceAtLeast(0L)
          else -> 0L
        }
        val initialHeaders = buildRangeHeaders(existingBytes)
        var response = openValidatedFollowingRedirects(initialUrl = initialUrl, headers = initialHeaders)

        // HTTP 416 Range Not Satisfiable: delete stale .part file and retry once from byte 0.
        if (response.statusCode == HTTP_RANGE_NOT_SATISFIABLE) {
          response.close()
          deleteQuietly(partFile)
          existingBytes = 0L
          response = openValidatedFollowingRedirects(initialUrl = initialUrl, headers = emptyMap())
        }

        activeResponse = response
        response.use { activeResp ->
          checkNotCancelled(partFile)

          val appendMode: Boolean
          val startingOffset: Long
          val expectedTotalBytes: Long
          when (activeResp.statusCode) {
            HTTP_PARTIAL_CONTENT -> {
              val rangeStart = parseContentRangeStartOffset(activeResp.header("Content-Range"))
              // Verify server honored our exact byte offset (or started cleanly from 0).
              when {
                existingBytes > 0L && (rangeStart < 0L || rangeStart == existingBytes) -> {
                  appendMode = true
                  startingOffset = existingBytes
                }
                rangeStart == 0L || existingBytes <= 0L -> {
                  appendMode = false
                  startingOffset = 0L
                }
                else -> throw ValidationException(
                  message = "Mismatched Content-Range start ($rangeStart vs $existingBytes) for '$sanitizedName'.",
                  issues = listOf("Content-Range: expected start $existingBytes, got $rangeStart"),
                )
              }
              val rangeTotal = parseContentRangeTotalBytes(activeResp.header("Content-Range"))
              val bodyLen = resolveResponseContentLength(activeResp)
              expectedTotalBytes = when {
                rangeTotal > 0L -> rangeTotal
                bodyLen > 0L -> startingOffset + bodyLen
                else -> -1L
              }
            }

            HTTP_OK -> {
              // Server ignored Range or fresh transfer: overwrite .part cleanly from byte 0.
              appendMode = false
              startingOffset = 0L
              expectedTotalBytes = resolveResponseContentLength(activeResp)
            }

            else -> throw ValidationException(
              message = "Download failed with HTTP ${activeResp.statusCode} for '$sanitizedName'.",
              issues = listOf("httpStatus: ${activeResp.statusCode}"),
            )
          }

          var writtenBytes = startingOffset
          // Publish immediate resume progress when appending onto an existing .part prefix.
          if (writtenBytes > 0L) {
            val resumeFraction = when {
              expectedTotalBytes > 0L ->
                (writtenBytes.toFloat() / expectedTotalBytes.toFloat()).coerceIn(0f, 0.99f)
              else -> 0f
            }
            onProgress(writtenBytes, expectedTotalBytes, resumeFraction)
          }

          // Stream buffered 256 KB chunks directly into filesDir/models/<name>.part (zero extra copy).
          FileOutputStream(partFile, appendMode).use { output ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            val input = activeResp.bodyStream
            while (true) {
              checkNotCancelled(partFile)
              val readCount = input.read(buffer)
              if (readCount < 0) break
              if (readCount > 0) {
                checkNotCancelled(partFile)
                output.write(buffer, 0, readCount)
                writtenBytes += readCount
                // Cap live fraction at 0.99f until fd.sync() and atomic renameTo complete.
                val fraction = when {
                  expectedTotalBytes > 0L ->
                    (writtenBytes.toFloat() / expectedTotalBytes.toFloat()).coerceIn(0f, 0.99f)
                  else -> 0f
                }
                onProgress(writtenBytes, expectedTotalBytes, fraction)
              }
            }
            checkNotCancelled(partFile)
            // Flush userspace stream and fsync kernel buffers before promoting .part -> .litertlm.
            output.flush()
            runCatching { output.fd.sync() }
          }

          val finalBytes = partFile.length()
          if (expectedTotalBytes > 0L && finalBytes != expectedTotalBytes) {
            // Unlink overflowed .part files while preserving truncated prefixes for 206 resume.
            if (finalBytes > expectedTotalBytes) {
              deleteQuietly(partFile)
            }
            throw ValidationException(
              message = "Incomplete download for '$sanitizedName' ($finalBytes of $expectedTotalBytes bytes).",
              issues = listOf("sizeBytes: expected $expectedTotalBytes, got $finalBytes"),
            )
          }
          // Reject tiny HTML error pages or corrupt stubs smaller than minSizeBytes (100 MB prod).
          if (finalBytes <= minSizeBytes) {
            deleteQuietly(partFile)
            throw ValidationException(
              message = "Downloaded model '$sanitizedName' is too small ($finalBytes bytes; must exceed $minSizeBytes bytes).",
              issues = listOf("sizeBytes: $finalBytes <= $minSizeBytes"),
            )
          }

          // Atomically promote verified .part weights to final .litertlm destination.
          if (destFile.exists()) {
            destFile.delete()
          }
          val renamed = partFile.renameTo(destFile)
          if (!renamed) {
            // Fallback copy+unlink if underlying test/filesystem mount rejects renameTo.
            partFile.copyTo(destFile, overwrite = true)
            deleteQuietly(partFile)
          }
          onProgress(finalBytes, finalBytes, 1.0f)

          DiscoveredModelInfo(
            fileName = destFile.name,
            absolutePath = destFile.absolutePath,
            sizeBytes = destFile.length(),
            format = ModelFileFormat.LITERT_LM,
            supportsVisionCandidate = preset.supportsVision,
            recommendedProfile = preset.targetProfile,
          )
        }
      } catch (cancel: CancellationException) {
        // Explicit user or coroutine cancellation always unlinks the incomplete .part file.
        deleteQuietly(partFile)
        throw CancellationException(CANCELLED_EXCEPTION_MESSAGE).also { wrapped ->
          if (wrapped !== cancel) {
            wrapped.initCause(cancel)
          }
        }
      } catch (ioError: IOException) {
        // Closing the socket during cancelActiveDownload() surfaces as IOException on read():
        // convert to CancellationException and delete .part; otherwise preserve .part for resume!
        if (cancelRequested.get() || !coroutineContext.isActive) {
          deleteQuietly(partFile)
          throw CancellationException(CANCELLED_EXCEPTION_MESSAGE).also { wrapped ->
            wrapped.initCause(ioError)
          }
        }
        throw ioError
      } finally {
        activeResponse = null
      }
    }

  // Follows up to MAX_REDIRECTS (6) validated HTTPS redirect hops while forwarding Range headers.
  private suspend fun openValidatedFollowingRedirects(
    initialUrl: String,
    headers: Map<String, String>,
  ): ModelDownloadHttpResponse {
    var currentUrl = validateInitialModelDownloadUrl(initialUrl)
    for (hop in 0..MAX_REDIRECTS) {
      if (cancelRequested.get()) {
        throw CancellationException(CANCELLED_EXCEPTION_MESSAGE)
      }
      coroutineContext.ensureActive()
      val response = transport.openStream(currentUrl, headers)
      when (response.statusCode) {
        in REDIRECT_STATUS_CODES -> {
          val rawLocation = response.header("Location")
          // Always close intermediate 3xx hop sockets immediately before inspecting next URL.
          response.close()
          if (hop >= MAX_REDIRECTS) {
            throw ValidationException(
              message = "Too many HTTP redirects (> $MAX_REDIRECTS) while resolving model download.",
              issues = listOf("redirects: exceeded $MAX_REDIRECTS"),
            )
          }
          if (rawLocation.isNullOrBlank()) {
            throw ValidationException(
              message = "Redirect response (${response.statusCode}) omitted Location header.",
              issues = listOf("Location: missing on ${response.statusCode}"),
            )
          }
          currentUrl = resolveRedirectTargetUrl(currentUrl = currentUrl, rawLocation = rawLocation)
        }
        else -> return response
      }
    }
    throw ValidationException(
      message = "Exceeded redirect budget ($MAX_REDIRECTS) while resolving model download.",
      issues = listOf("redirects: exceeded $MAX_REDIRECTS"),
    )
  }

  private suspend fun checkNotCancelled(partFile: File) {
    if (cancelRequested.get() || !coroutineContext.isActive) {
      deleteQuietly(partFile)
      throw CancellationException(CANCELLED_EXCEPTION_MESSAGE)
    }
  }

  companion object {
    /** Maximum HTTP `301/302/303/307/308` redirect hops permitted per download attempt. */
    const val MAX_REDIRECTS: Int = 6

    /** TLS connection handshake timeout (`20s`). */
    const val CONNECT_TIMEOUT_MS: Int = 20_000

    /** Socket chunk read timeout (`60s`) accommodating cellular jitter on multi-GB weights. */
    const val READ_TIMEOUT_MS: Int = 60_000

    /** Canonical cancellation message emitted when [cancelActiveDownload] aborts a transfer. */
    const val CANCELLED_EXCEPTION_MESSAGE: String = "Model download cancelled"

    private const val HTTP_OK: Int = 200
    private const val HTTP_PARTIAL_CONTENT: Int = 206
    private const val HTTP_RANGE_NOT_SATISFIABLE: Int = 416
    private const val DOWNLOAD_BUFFER_BYTES: Int = 256 * 1024

    private val REDIRECT_STATUS_CODES: Set<Int> = setOf(301, 302, 303, 307, 308)

    private fun buildRangeHeaders(existingBytes: Long): Map<String, String> =
      when {
        existingBytes > 0L -> mapOf("Range" to "bytes=$existingBytes-")
        else -> emptyMap()
      }

    private fun resolveResponseContentLength(response: ModelDownloadHttpResponse): Long =
      when {
        response.contentLength > 0L -> response.contentLength
        else -> response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L } ?: -1L
      }

    private fun deleteQuietly(file: File) {
      runCatching {
        if (file.exists()) {
          file.delete()
        }
      }
    }

    /**
     * Resolves a relative or absolute HTTP `Location` header against [currentUrl] and validates
     * the target with [validateModelRedirectUrl].
     */
    internal fun resolveRedirectTargetUrl(currentUrl: String, rawLocation: String): String {
      val trimmed = rawLocation.trim()
      requireDomainValid(trimmed.isNotEmpty(), "ModelRedirectUrl") {
        "Redirect Location header must not be blank"
      }
      // Pass protocol-relative '//' or explicit scheme 'http://...' directly so validator rejects.
      val resolved = when {
        trimmed.startsWith("//") || trimmed.contains("://") -> trimmed
        trimmed.startsWith("/") -> {
          val baseUri = URI(currentUrl)
          val portClause = when {
            baseUri.port == -1 || baseUri.port == 443 -> ""
            else -> ":${baseUri.port}"
          }
          "https://${baseUri.host}$portClause$trimmed"
        }
        else -> URI(currentUrl).resolve(trimmed).toString()
      }
      return validateModelRedirectUrl(resolved)
    }

    /** Parses total resource length from an HTTP `Content-Range` header (`bytes start-end/total`). */
    internal fun parseContentRangeTotalBytes(contentRangeHeader: String?): Long {
      // RFC 9110 Content-Range places total byte length after '/'; '*' denotes unknown total.
      val raw = contentRangeHeader?.trim().orEmpty()
      if (raw.isEmpty() || !raw.lowercase().startsWith("bytes")) return -1L
      val slashIdx = raw.lastIndexOf('/')
      if (slashIdx < 0 || slashIdx == raw.length - 1) return -1L
      val totalToken = raw.substring(slashIdx + 1).trim()
      if (totalToken == "*") return -1L
      return totalToken.toLongOrNull()?.takeIf { it > 0L } ?: -1L
    }

    /** Parses start byte offset from an HTTP `Content-Range` header (`bytes start-end/total`). */
    internal fun parseContentRangeStartOffset(contentRangeHeader: String?): Long {
      // Extract the inclusive start byte index preceding '-' so 206 append alignment is verified.
      val raw = contentRangeHeader?.trim().orEmpty()
      if (raw.isEmpty() || !raw.lowercase().startsWith("bytes")) return -1L
      val rangePart = raw.substring(5).trim().substringBefore('/')
      if (rangePart == "*" || !rangePart.contains('-')) return -1L
      return rangePart.substringBefore('-').trim().toLongOrNull()?.takeIf { it >= 0L } ?: -1L
    }

    // Queries StatFs.availableBytes on modelsDir (or parent filesDir before first download).
    internal fun queryStatFsAvailableBytes(modelsDir: File): Long =
      runCatching {
        val targetDir = when {
          modelsDir.exists() -> modelsDir
          modelsDir.parentFile?.exists() == true -> modelsDir.parentFile ?: modelsDir
          else -> modelsDir.parentFile ?: modelsDir
        }
        StatFs(targetDir.absolutePath).availableBytes
      }.getOrDefault(-1L)

    // Classifies active network as UNMETERED_WIFI, METERED_CELLULAR, or OFFLINE_OR_UNKNOWN safely.
    internal fun inspectAndroidNetworkMetering(context: Context): NetworkMeteringStatus =
      runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
          ?: (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
          ?: return@runCatching NetworkMeteringStatus.OFFLINE_OR_UNKNOWN
        val activeNetwork = connectivity.activeNetwork
          ?: return@runCatching NetworkMeteringStatus.OFFLINE_OR_UNKNOWN
        val capabilities = connectivity.getNetworkCapabilities(activeNetwork)
          ?: return@runCatching NetworkMeteringStatus.OFFLINE_OR_UNKNOWN
        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        when {
          !hasInternet -> NetworkMeteringStatus.OFFLINE_OR_UNKNOWN
          capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ->
            NetworkMeteringStatus.UNMETERED_WIFI
          else -> NetworkMeteringStatus.METERED_CELLULAR
        }
      }.getOrDefault(NetworkMeteringStatus.UNMETERED_WIFI)
  }
}
