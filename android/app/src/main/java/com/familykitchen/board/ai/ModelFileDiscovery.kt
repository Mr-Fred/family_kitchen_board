package com.familykitchen.board.ai

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.familykitchen.board.domain.EdgeDeviceProfile
import com.familykitchen.board.domain.KitchenBoardException
import com.familykitchen.board.domain.ValidationException
import com.familykitchen.board.ports.DiscoveredModelInfo
import com.familykitchen.board.ports.ModelFileFormat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

// Scoped model discovery and atomic .part SAF importer targeting Galaxy S22 and S22 Ultra.
// Requires zero MANAGE_EXTERNAL_STORAGE or READ_EXTERNAL_STORAGE permissions on Android 12+.

/**
 * Immutable snapshot of the host Android device hardware used to select OpenCL GPU vs CPU
 * execution delegates and default Gemma model profiles.
 *
 * @property manufacturer Device manufacturer string (for example, `"samsung"`).
 * @property model Device model identifier (for example, `"SM-S901U"` or `"SM-S908U"`).
 * @property hardware Kernel/SoC hardware identifier (for example, `"qcom"`, `"s5e9925"`, `"ranchu"`).
 * @property isEmulator `true` when running on an Android emulator lacking physical Adreno/Xclipse GPU.
 * @property isGalaxyS22Ultra `true` when running on a 12 GB Samsung Galaxy S22 Ultra (`SM-S908*`).
 * @property isGalaxyS22Family `true` when running on any Samsung Galaxy S22 / S22+ / S22 Ultra.
 * @property recommendedProfile Default [EdgeDeviceProfile] matched to device RAM and GPU capacity.
 */
data class HardwareEnvironmentInfo(
  val manufacturer: String,
  val model: String,
  val hardware: String,
  val isEmulator: Boolean,
  val isGalaxyS22Ultra: Boolean,
  val isGalaxyS22Family: Boolean,
  val recommendedProfile: EdgeDeviceProfile,
)

/**
 * App-scoped model file discovery and atomic SAF importer for on-device LiteRT-LM (`.litertlm`)
 * and MediaPipe GenAI (`.task`, `.bin`) weights.
 *
 * Scans **only** app-owned directories (`File(context.filesDir, "models")` and
 * `context.getExternalFilesDir("models")`) so users can either import models once via
 * `ActivityResultContracts.OpenDocument` or push weights via `adb push` with zero invasive
 * storage permissions.
 *
 * @param context Android context providing app-scoped internal and external model directories.
 * @param ioDispatcher Background dispatcher executing directory walks and multi-hundred-MB copies.
 */
class ModelFileDiscovery(
  context: Context,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

  // Retain applicationContext when non-null to avoid holding Activity references across rotations.
  private val appContext: Context = context.applicationContext ?: context

  /**
   * Returns the ordered list of app-scoped model directories (`filesDir/models` first, followed by
   * `getExternalFilesDir("models")` when mounted).
   *
   * @return Read-only list of scoped model directories requiring zero storage permissions.
   */
  fun resolveScopedModelDirectories(): List<File> {
    // Prefer internal filesDir/models first, then app-specific externalFilesDir/models for adb push.
    val internalModelsDir = File(appContext.filesDir, MODELS_SUBDIR)
    val externalModelsDir = runCatching { appContext.getExternalFilesDir(MODELS_SUBDIR) }.getOrNull()
    return listOfNotNull(internalModelsDir, externalModelsDir)
      .distinctBy { it.absolutePath }
  }

  /**
   * Scans `filesDir/models` and `getExternalFilesDir("models")` on [ioDispatcher] for complete
   * `.litertlm`, `.task`, and `.bin` packages exceeding [minSizeBytes] (`100 MB` default), sorted
   * with `.litertlm` prioritized first and then descending by file size.
   *
   * @param minSizeBytes Minimum valid model byte length (`100 MB` default; filters partial copies).
   * @return Ranked list of validated [DiscoveredModelInfo] candidates.
   */
  suspend fun discoverModels(
    minSizeBytes: Long = MIN_MODEL_FILE_SIZE_BYTES,
  ): List<DiscoveredModelInfo> =
    withContext(ioDispatcher) {
      // Perform filesystem directory walks off the UI thread to prevent startup frame drops.
      val hardwareInfo = detectHardwareEnvironment()
      discoverModelsInDirectories(resolveScopedModelDirectories(), minSizeBytes, hardwareInfo)
    }

  /**
   * Streams a user-selected SAF `OpenDocument` [sourceUri] into `File(context.filesDir, "models")`
   * via an atomic `*.part` temporary file + `fd.sync()` + `renameTo`, invoking [onProgress] as
   * chunks transfer.
   *
   * Interrupted or cancelled copies delete the `*.part` staging file immediately so corrupt partial
   * weights can never be mistaken for a loadable model on subsequent launches.
   *
   * @param sourceUri Scoped `content://` URI returned by `ActivityResultContracts.OpenDocument`.
   * @param preferredDisplayName Optional display filename provided by caller metadata.
   * @param minSizeBytes Minimum accepted completed file size (`100 MB` default in production).
   * @param onProgress Byte-transfer callback receiving `(copiedBytes, totalBytes, fraction0To1)`.
   * @return Validated [DiscoveredModelInfo] pointing to the atomically finalized model file.
   */
  suspend fun importModelFromUri(
    sourceUri: Uri,
    preferredDisplayName: String? = null,
    minSizeBytes: Long = MIN_MODEL_FILE_SIZE_BYTES,
    onProgress: (copiedBytes: Long, totalBytes: Long, progressFraction: Float) -> Unit = { _, _, _ -> },
  ): DiscoveredModelInfo =
    withContext(ioDispatcher) {
      // Query OpenableColumns metadata first to obtain true DISPLAY_NAME and expected byte length.
      val metadata = queryDocumentMetadata(sourceUri)
      val rawName = preferredDisplayName?.takeIf { it.isNotBlank() }
        ?: metadata.first
        ?: sourceUri.lastPathSegment?.substringAfterLast('/')
        ?: DEFAULT_IMPORTED_MODEL_NAME
      // Strip path separators and '..' segments so untrusted URIs cannot escape filesDir/models.
      val sanitizedName = sanitizeModelFileName(rawName)
      val format = classifyModelFormat(sanitizedName)
        ?: throw ValidationException(
          message = "Unsupported model file '$sanitizedName'. Select a .litertlm, .task, or .bin package.",
          issues = listOf("fileName: unsupported extension '$sanitizedName'"),
        )

      // Ensure app-private models directory exists before opening the SAF document stream.
      val modelsDir = File(appContext.filesDir, MODELS_SUBDIR)
      if (!modelsDir.exists() && !modelsDir.mkdirs() && !modelsDir.isDirectory) {
        throw ValidationException("Unable to create app-private models directory.", listOf("filesDir/$MODELS_SUBDIR"))
      }

      val inputStream = appContext.contentResolver.openInputStream(sourceUri)
        ?: throw ValidationException("Unable to read selected model document '$sanitizedName'.", listOf("openInputStream: null"))

      // Stream into *.part and rename atomically only after full byte verification succeeds.
      importStreamToModelsDir(
        inputStream = inputStream,
        modelsDir = modelsDir,
        sanitizedFileName = sanitizedName,
        format = format,
        expectedTotalBytes = metadata.second,
        minSizeBytes = minSizeBytes,
        hardwareInfo = detectHardwareEnvironment(),
        onProgress = onProgress,
      )
    }

  // Extract DISPLAY_NAME and SIZE safely; return (-1L) when a SAF provider omits stream length.
  private fun queryDocumentMetadata(uri: Uri): Pair<String?, Long> =
    runCatching {
      appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use Pair(null, -1L)
        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
        val resolvedName = if (nameIdx >= 0 && !cursor.isNull(nameIdx)) cursor.getString(nameIdx) else null
        val resolvedSize = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else -1L
        Pair(resolvedName, resolvedSize)
      } ?: Pair(null, -1L)
    }.getOrDefault(Pair(null, -1L))

  companion object {
    /** App-scoped subdirectory name under `filesDir` and `getExternalFilesDir`. */
    const val MODELS_SUBDIR: String = "models"

    /** Temporary suffix appended while streaming SAF model imports prior to atomic rename. */
    const val PARTIAL_DOWNLOAD_SUFFIX: String = ".part"

    /** Minimum byte threshold (`100 MB`) distinguishing real quantized weights from stub files. */
    const val MIN_MODEL_FILE_SIZE_BYTES: Long = 100L * 1024L * 1024L

    // Fallback basename used only if SAF document metadata omits DISPLAY_NAME completely.
    private const val DEFAULT_IMPORTED_MODEL_NAME: String = "imported_gemma_model.litertlm"

    // 1 MB copy buffer minimizes syscall overhead when streaming 1 GB - 2.5 GB quantized weights.
    private const val IMPORT_BUFFER_BYTES: Int = 1024 * 1024

    // Limit directory traversal depth so accidental nested folders cannot stall discovery.
    private const val MAX_SCAN_DEPTH: Int = 3

    /**
     * Inspects `android.os.Build` properties safely (handling `null` platform strings in local JVM
     * unit tests) to classify Samsung Galaxy S22, Galaxy S22 Ultra, and Android Emulator hosts.
     *
     * @param manufacturer Raw `Build.MANUFACTURER` override for deterministic JVM testing.
     * @param model Raw `Build.MODEL` override for deterministic JVM testing.
     * @param device Raw `Build.DEVICE` override for deterministic JVM testing.
     * @param hardware Raw `Build.HARDWARE` override for deterministic JVM testing.
     * @param fingerprint Raw `Build.FINGERPRINT` override for deterministic JVM testing.
     * @return Resolved [HardwareEnvironmentInfo] with recommended [EdgeDeviceProfile].
     */
    fun detectHardwareEnvironment(
      manufacturer: String = Build.MANUFACTURER.orEmpty(),
      model: String = Build.MODEL.orEmpty(),
      device: String = Build.DEVICE.orEmpty(),
      hardware: String = Build.HARDWARE.orEmpty(),
      fingerprint: String = Build.FINGERPRINT.orEmpty(),
    ): HardwareEnvironmentInfo {
      val modelLower = model.lowercase()
      val deviceLower = device.lowercase()
      val hardwareLower = hardware.lowercase()
      val fingerprintLower = fingerprint.lowercase()

      // Detect QEMU/Goldfish/Ranchu emulators where CPU backend avoids OpenCL stub crashes.
      val isEmulator = fingerprintLower.contains("generic") ||
        fingerprintLower.contains("emulator") ||
        modelLower.contains("sdk_gphone") ||
        modelLower.contains("emulator") ||
        modelLower.contains("android sdk built for") ||
        hardwareLower.contains("ranchu") ||
        hardwareLower.contains("goldfish")

      // SM-S908* / b0q / b0s identify Samsung Galaxy S22 Ultra (12 GB RAM configuration).
      val isS22Ultra = modelLower.contains("s908") ||
        modelLower.contains("s22 ultra") ||
        deviceLower == "b0q" ||
        deviceLower == "b0s"

      // SM-S901* (S22), SM-S906* (S22+), and SM-S908* (S22 Ultra) share Adreno 730 / Exynos 2200.
      val isS22Family = isS22Ultra ||
        modelLower.contains("s901") ||
        modelLower.contains("s906") ||
        modelLower.contains("galaxy s22") ||
        deviceLower in setOf("r0q", "r0s", "g0q", "g0s")

      val defaultProfile = when {
        isS22Ultra -> EdgeDeviceProfile.GALAXY_S22_ULTRA_GEMMA_2B_INT4
        else -> EdgeDeviceProfile.GALAXY_S22_GEMMA_1B_INT4
      }

      return HardwareEnvironmentInfo(
        manufacturer = manufacturer.ifBlank { "Android" },
        model = model.ifBlank { "Device" },
        hardware = hardware.ifBlank { "unknown" },
        isEmulator = isEmulator,
        isGalaxyS22Ultra = isS22Ultra,
        isGalaxyS22Family = isS22Family,
        recommendedProfile = defaultProfile,
      )
    }

    /**
     * Pure directory scanner usable directly in JVM unit tests without an Android `Context`.
     *
     * Rejects `*.part` / `*.tmp` files and files `<= minSizeBytes`, sorting `.litertlm` ahead of
     * `.task`/`.bin` and then descending by byte size.
     *
     * @param directories Candidate directories to walk up to depth `3`.
     * @param minSizeBytes Strict minimum file size (`> minSizeBytes`, `100 MB` default).
     * @param hardwareInfo Detected or test-supplied host hardware profile.
     * @return Deterministically ranked list of [DiscoveredModelInfo].
     */
    fun discoverModelsInDirectories(
      directories: List<File>,
      minSizeBytes: Long = MIN_MODEL_FILE_SIZE_BYTES,
      hardwareInfo: HardwareEnvironmentInfo = detectHardwareEnvironment(),
    ): List<DiscoveredModelInfo> {
      val seenPaths = linkedSetOf<String>()
      val candidates = mutableListOf<DiscoveredModelInfo>()

      for (root in directories) {
        if (!root.isDirectory) continue
        root.walkTopDown()
          .maxDepth(MAX_SCAN_DEPTH)
          .forEach { file ->
            if (!file.isFile) return@forEach
            val lowerName = file.name.lowercase()
            // Ignore in-flight atomic *.part or *.tmp transfers so partial files never load.
            if (lowerName.endsWith(PARTIAL_DOWNLOAD_SUFFIX) || lowerName.endsWith(".tmp")) {
              return@forEach
            }
            val format = classifyModelFormat(file.name) ?: return@forEach
            val lengthBytes = file.length()
            if (lengthBytes <= minSizeBytes) return@forEach
            val canonicalOrAbs = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            if (!seenPaths.add(canonicalOrAbs)) return@forEach

            candidates.add(
              DiscoveredModelInfo(
                fileName = file.name,
                absolutePath = file.absolutePath,
                sizeBytes = lengthBytes,
                format = format,
                supportsVisionCandidate = format == ModelFileFormat.LITERT_LM,
                recommendedProfile = inferRecommendedProfile(file.name, format, hardwareInfo),
              ),
            )
          }
      }

      // Prioritize .litertlm first, then largest weight bundle, then lexical filename tie-breaker.
      return candidates.sortedWith(
        compareByDescending<DiscoveredModelInfo> { it.format == ModelFileFormat.LITERT_LM }
          .thenByDescending { it.sizeBytes }
          .thenBy { it.fileName },
      )
    }

    /**
     * Classifies a filename into a supported [ModelFileFormat] (`.litertlm`, `.task`, `.bin`), or
     * returns `null` when unsupported.
     *
     * @param fileName Candidate filename to inspect.
     * @return Matching [ModelFileFormat] or `null`.
     */
    fun classifyModelFormat(fileName: String): ModelFileFormat? {
      val clean = fileName.trim().lowercase()
      return when {
        clean.endsWith(".litertlm") -> ModelFileFormat.LITERT_LM
        clean.endsWith(".task") -> ModelFileFormat.MEDIAPIPE_TASK
        clean.endsWith(".bin") -> ModelFileFormat.MEDIAPIPE_BIN
        else -> null
      }
    }

    /**
     * Sanitizes an untrusted SAF display filename to prevent path traversal (`..`, `/`, `\`) when
     * writing inside `filesDir/models/`.
     *
     * @param rawFileName Raw display name from document metadata.
     * @return Safe single-segment filename preserving `.litertlm` / `.task` / `.bin` suffix.
     */
    fun sanitizeModelFileName(rawFileName: String): String {
      val leaf = rawFileName.trim().substringAfterLast('/').substringAfterLast('\\')
      val safeChars = leaf.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_', '.')
      return safeChars.ifEmpty { DEFAULT_IMPORTED_MODEL_NAME }
    }

    /**
     * Pure stream-to-atomic-file helper (`*.part` + `fd.sync()` + `renameTo`) shared between
     * Android SAF imports and hermetic JVM tests.
     *
     * @param inputStream Source weight stream (closed automatically).
     * @param modelsDir Destination `models` directory.
     * @param sanitizedFileName Sanitized target filename.
     * @param format Validated [ModelFileFormat].
     * @param expectedTotalBytes Expected byte size from metadata (`<= 0` when unknown).
     * @param minSizeBytes Minimum valid byte count required before committing the rename.
     * @param hardwareInfo Host hardware descriptor for profile mapping.
     * @param onProgress Progress callback invoked after each buffered chunk.
     * @return Finalized [DiscoveredModelInfo].
     */
    suspend fun importStreamToModelsDir(
      inputStream: InputStream,
      modelsDir: File,
      sanitizedFileName: String,
      format: ModelFileFormat,
      expectedTotalBytes: Long = -1L,
      minSizeBytes: Long = MIN_MODEL_FILE_SIZE_BYTES,
      hardwareInfo: HardwareEnvironmentInfo = detectHardwareEnvironment(),
      onProgress: (copiedBytes: Long, totalBytes: Long, progressFraction: Float) -> Unit = { _, _, _ -> },
    ): DiscoveredModelInfo {
      val destFile = File(modelsDir, sanitizedFileName)
      val partFile = File(modelsDir, "$sanitizedFileName$PARTIAL_DOWNLOAD_SUFFIX")

      try {
        var copiedBytes = 0L
        inputStream.use { input ->
          FileOutputStream(partFile).use { output ->
            val buffer = ByteArray(IMPORT_BUFFER_BYTES)
            while (true) {
              // Cooperate with structured concurrency so cancelling import halts disk writes fast.
              coroutineContext.ensureActive()
              val readCount = input.read(buffer)
              if (readCount < 0) break
              if (readCount > 0) {
                output.write(buffer, 0, readCount)
                copiedBytes += readCount
                // Emit 0f when SAF omits OpenableColumns.SIZE so UI shows indeterminate + live MB
                // rather than appearing frozen at 50% throughout a multi-GB copy.
                val fraction = when {
                  expectedTotalBytes > 0L ->
                    (copiedBytes.toFloat() / expectedTotalBytes.toFloat()).coerceIn(0f, 0.99f)
                  else -> 0f
                }
                onProgress(copiedBytes, expectedTotalBytes, fraction)
              }
            }
            output.flush()
            runCatching { output.fd.sync() }
          }
        }

        // Verify exact byte count against SAF metadata and > 100 MB threshold before rename.
        if (expectedTotalBytes > 0L && copiedBytes != expectedTotalBytes) {
          throw ValidationException(
            message = "Incomplete model transfer for '$sanitizedFileName' ($copiedBytes of $expectedTotalBytes bytes).",
            issues = listOf("copiedBytes: expected $expectedTotalBytes, got $copiedBytes"),
          )
        }
        if (copiedBytes <= minSizeBytes) {
          throw ValidationException(
            message = "Model file '$sanitizedFileName' is too small ($copiedBytes bytes; must exceed $minSizeBytes bytes).",
            issues = listOf("sizeBytes: $copiedBytes <= $minSizeBytes"),
          )
        }

        // Atomically promote *.part -> final model filename (with overwrite fallback if needed).
        if (destFile.exists()) {
          destFile.delete()
        }
        val renamed = partFile.renameTo(destFile)
        if (!renamed) {
          // Cross-filesystem fallback copies verified .part bytes and removes temporary staging.
          partFile.copyTo(destFile, overwrite = true)
          partFile.delete()
        }
        onProgress(copiedBytes, copiedBytes, 1.0f)

        return DiscoveredModelInfo(
          fileName = destFile.name,
          absolutePath = destFile.absolutePath,
          sizeBytes = destFile.length(),
          format = format,
          supportsVisionCandidate = format == ModelFileFormat.LITERT_LM,
          recommendedProfile = inferRecommendedProfile(destFile.name, format, hardwareInfo),
        )
      } catch (cancel: CancellationException) {
        // Always unlink incomplete .part files when coroutine scope cancels mid-copy.
        runCatching { if (partFile.exists()) partFile.delete() }
        throw cancel
      } catch (domainError: KitchenBoardException) {
        // Clean up rejected .part artifacts so discovery never sees truncated weights.
        runCatching { if (partFile.exists()) partFile.delete() }
        throw domainError
      } catch (ioError: IOException) {
        // Delete partial staging file on out-of-space or broken SAF pipe failures.
        runCatching { if (partFile.exists()) partFile.delete() }
        val detail = ioError.message ?: "disk copy error"
        throw ValidationException(
          message = "Failed to import model '$sanitizedFileName': $detail",
          issues = listOf("modelImport: $detail"),
          cause = ioError,
        )
      }
    }

    // Map multimodal E2B, 2B S22 Ultra, and 1B base S22 packages to their calibrated profiles.
    private fun inferRecommendedProfile(
      fileName: String,
      format: ModelFileFormat,
      hardwareInfo: HardwareEnvironmentInfo,
    ): EdgeDeviceProfile {
      val lower = fileName.lowercase()
      return when {
        format == ModelFileFormat.LITERT_LM &&
          (lower.contains("e2b") || lower.contains("multimodal") || lower.contains("vision") || lower.contains("gemma-4") || lower.contains("gemma4")) ->
          EdgeDeviceProfile.GEMMA_4_E2B_MULTIMODAL
        lower.contains("2b") || hardwareInfo.isGalaxyS22Ultra ->
          EdgeDeviceProfile.GALAXY_S22_ULTRA_GEMMA_2B_INT4
        else ->
          EdgeDeviceProfile.GALAXY_S22_GEMMA_1B_INT4
      }
    }
  }
}
