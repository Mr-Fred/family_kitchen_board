package com.familykitchen.board.data

import android.content.Context
import com.familykitchen.board.domain.KitchenBoardException
import com.familykitchen.board.domain.SchemaValidator
import com.familykitchen.board.domain.SeedCatalogDataset
import com.familykitchen.board.domain.ValidationException
import com.familykitchen.board.ports.SeedCatalogPort
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Offline Android AssetManager adapter for the canonical Family-of-4 seed catalog JSON.
// Performs all buffered UTF-8 asset reads and strict schema checks off the UI thread.

/**
 * Production Android adapter implementing [SeedCatalogPort] by reading `seed_catalog.json` from
 * `Context.assets` on [ioDispatcher] and validating referential and morning-cap invariants via
 * [SchemaValidator.decodeSeedCatalogDatasetJson].
 *
 * Retains only `applicationContext` (falling back safely to `context` in lightweight test stubs)
 * so Activity instances are never leaked across configuration changes.
 *
 * @param context Android context providing access to bundled APK `AssetManager` resources.
 * @param assetFileName Relative asset path inside `src/main/assets/` (`"seed_catalog.json"`).
 * @param ioDispatcher Background dispatcher executing asset I/O and JSON validation.
 */
class AssetSeedCatalogLoader(
  context: Context,
  private val assetFileName: String = DEFAULT_SEED_ASSET_FILE_NAME,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SeedCatalogPort {

  // Hold applicationContext when available so callers passing an Activity never leak UI references.
  private val appContext: Context = context.applicationContext ?: context

  /**
   * Reads [assetFileName] from bundled APK assets on [ioDispatcher], enforces strict unknown-key
   * rejection, and verifies 100% BOM and `1.4x` morning pacing integrity.
   *
   * @return Validated immutable [SeedCatalogDataset].
   * @throws ValidationException When the asset is missing, unreadable, blank, or schema-invalid.
   */
  override suspend fun loadSeedCatalog(): SeedCatalogDataset =
    withContext(ioDispatcher) {
      if (assetFileName.isBlank()) {
        throw ValidationException(
          message = "Seed catalog asset filename must not be blank.",
          issues = listOf("assetFileName: blank"),
        )
      }
      try {
        // Stream UTF-8 bytes inside use {} so the native AssetInputStream closes deterministically.
        val rawJson = appContext.assets.open(assetFileName).use { stream ->
          readUtf8Text(stream)
        }
        SchemaValidator.decodeSeedCatalogDatasetJson(rawJson)
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (domainError: KitchenBoardException) {
        // Preserve structured field-level issues emitted by SchemaValidator without double-wrapping.
        throw domainError
      } catch (ioError: IOException) {
        val detail = ioError.message ?: "unable to open bundled asset"
        throw ValidationException(
          message = "Failed to read offline seed catalog '$assetFileName': $detail",
          issues = listOf("assets/$assetFileName: $detail"),
          cause = ioError,
        )
      }
    }

  companion object {
    /** Canonical bundled APK asset filename mirrored from `data/seed_catalog.json`. */
    const val DEFAULT_SEED_ASSET_FILE_NAME: String = "seed_catalog.json"

    // Buffered 16 KB reader keeps asset deserialization fast (< 5ms) without large temp arrays.
    private const val STREAM_BUFFER_CHARS: Int = 16 * 1024

    /**
     * Decodes and validates a [SeedCatalogDataset] directly from an open UTF-8 [inputStream].
     *
     * Exposed as a pure stream helper so hermetic JVM tests can exercise identical decoding logic
     * without constructing an Android `Context`.
     *
     * @param inputStream Open UTF-8 JSON stream positioned at the start of `seed_catalog.json`.
     * @return Validated immutable [SeedCatalogDataset].
     */
    fun decodeFromStream(inputStream: InputStream): SeedCatalogDataset {
      val rawJson = try {
        inputStream.use { stream -> readUtf8Text(stream) }
      } catch (ioError: IOException) {
        val detail = ioError.message ?: "stream read failure"
        throw ValidationException(
          message = "Failed to read seed catalog stream: $detail",
          issues = listOf("stream: $detail"),
          cause = ioError,
        )
      }
      return SchemaValidator.decodeSeedCatalogDatasetJson(rawJson)
    }

    // Read UTF-8 text explicitly rather than relying on platform default charset.
    private fun readUtf8Text(stream: InputStream): String =
      stream.reader(Charsets.UTF_8).buffered(STREAM_BUFFER_CHARS).readText()
  }
}
