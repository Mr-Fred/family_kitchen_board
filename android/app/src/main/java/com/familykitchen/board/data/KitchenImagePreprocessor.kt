package com.familykitchen.board.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.familykitchen.board.domain.KitchenBoardException
import com.familykitchen.board.domain.ValidationException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Scoped FileProvider camera capture and EXIF-normalized <= 1280px JPEG preprocessor.
// Eliminates legacy low-res extras["data"] thumbnails, file:// URIs, and 108MP heap spikes on S22.

/**
 * Scoped camera capture provisioning and EXIF-aware image downscaling helper for on-device
 * multimodal vision scans (`LiteRtMultimodalEngine`).
 *
 * All temporary captures and preprocessed inference JPEGs live strictly inside
 * `File(context.cacheDir, "captures")` (mapped to `@xml/file_paths` in `AndroidManifest.xml`) and
 * are cleaned up deterministically via [deleteQuietly] or [deletePathQuietly].
 */
object KitchenImagePreprocessor {

  /** Subdirectory under `Context.cacheDir` declared in `res/xml/file_paths.xml`. */
  const val CAPTURES_SUBDIR: String = "captures"

  /** `FileProvider` authority suffix matching `${applicationId}.fileprovider` in Manifest. */
  const val FILE_PROVIDER_SUFFIX: String = ".fileprovider"

  /** Maximum pixel width or height sent to Gemma Vision (`max(width, height) <= 1280`). */
  const val MAX_INFERENCE_DIMENSION_PX: Int = 1280

  /** Balanced JPEG encoding quality (`88`) preserving receipt OCR and produce label crispness. */
  const val INFERENCE_JPEG_QUALITY: Int = 88

  // Copy buffer size (64 KB) for streaming high-resolution S22 gallery URIs into staging cache.
  private const val COPY_BUFFER_BYTES: Int = 64 * 1024

  // Default max age (30 minutes) when pruning orphaned camera captures after process death.
  private const val DEFAULT_STALE_CAPTURE_TTL_MS: Long = 30L * 60L * 1000L

  // Monotonic sequence prevents filename collisions when two captures occur in the same millisecond.
  private val fileSequence = AtomicLong(0L)

  /**
   * Creates a timestamped `.jpg` file inside `File(context.cacheDir, "captures")` and returns its
   * backing [File] paired with a scoped `content://` [Uri] from [FileProvider] for
   * `ActivityResultContracts.TakePicture`.
   *
   * Never exposes raw `file://` URIs to external camera intents.
   *
   * @param context Android context used to resolve `cacheDir`, `packageName`, and [FileProvider].
   * @return `Pair(captureFile, contentUri)` ready for `TakePicture.launch(contentUri)`.
   */
  fun createCameraCaptureTarget(context: Context): Pair<File, Uri> {
    // Use applicationContext so FileProvider URI resolution never retains an Activity reference.
    val appContext = context.applicationContext ?: context
    val capturesDir = ensureCapturesDirectory(appContext)
    val uniqueSuffix = "${System.currentTimeMillis()}_${fileSequence.incrementAndGet()}"
    val captureFile = File(capturesDir, "camera_capture_$uniqueSuffix.jpg")
    try {
      // Pre-allocate the target file so TakePicture camera apps can open an output descriptor.
      if (!captureFile.exists()) {
        captureFile.createNewFile()
      }
    } catch (ioError: IOException) {
      val detail = ioError.message ?: "unable to allocate capture file"
      throw ValidationException("Failed to prepare camera capture target: $detail", listOf("captures: $detail"), ioError)
    }
    // Grant temporary content:// write access matching AndroidManifest ${applicationId}.fileprovider.
    val authority = "${appContext.packageName}$FILE_PROVIDER_SUFFIX"
    val contentUri = FileProvider.getUriForFile(appContext, authority, captureFile)
    return Pair(captureFile, contentUri)
  }

  /**
   * Streams [sourceUri] on [ioDispatcher], normalizes camera/gallery rotation via [ExifInterface],
   * downscales proportionally so `max(width, height) <= maxDimensionPx` (`1280` px default), and
   * writes a JPEG at quality [jpegQuality] (`88` default) into `cacheDir/captures/`.
   *
   * Uses a two-pass bounds check + `inSampleSize` pre-subsample so 50 MP / 108 MP Samsung Galaxy
   * S22 / S22 Ultra photos never exhaust Java heap memory.
   *
   * @param context Android context providing `contentResolver` and `cacheDir`.
   * @param sourceUri Scoped `content://` URI from `PickVisualMedia` or `TakePicture`.
   * @param maxDimensionPx Maximum output pixel length of the longer edge (`<= 1280` default).
   * @param jpegQuality Output JPEG compression quality in `1..100` (`88` default).
   * @param ioDispatcher Background dispatcher executing stream copy, EXIF read, and Bitmap work.
   * @return Preprocessed JPEG [File] inside `cacheDir/captures/` ready for `Content.ImageFile`.
   */
  suspend fun preprocessUriToInferenceJpeg(
    context: Context,
    sourceUri: Uri,
    maxDimensionPx: Int = MAX_INFERENCE_DIMENSION_PX,
    jpegQuality: Int = INFERENCE_JPEG_QUALITY,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  ): File =
    withContext(ioDispatcher) {
      require(maxDimensionPx > 0) { "maxDimensionPx must be > 0" }
      val clampedQuality = jpegQuality.coerceIn(1, 100)
      val appContext = context.applicationContext ?: context
      val capturesDir = ensureCapturesDirectory(appContext)
      val seq = "${System.currentTimeMillis()}_${fileSequence.incrementAndGet()}"
      val stagingFile = File(capturesDir, "raw_staging_$seq.tmp")
      val outputFile = File(capturesDir, "inference_jpeg_$seq.jpg")

      var decodedBitmap: Bitmap? = null
      var transformedBitmap: Bitmap? = null
      try {
        // Stage the source URI once onto local cache disk so ExifInterface and two-pass
        // BitmapFactory bounds/pixel reads work reliably across one-shot cloud photo providers.
        copyUriToStagingFile(appContext, sourceUri, stagingFile)

        // Pass 1: inspect raw dimensions with zero pixel allocation to compute safe inSampleSize.
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(stagingFile.absolutePath, boundsOptions)
        val rawWidth = boundsOptions.outWidth
        val rawHeight = boundsOptions.outHeight
        if (rawWidth <= 0 || rawHeight <= 0) {
          throw ValidationException(
            message = "Selected photo could not be decoded as a valid bitmap image.",
            issues = listOf("imageDimensions: ${rawWidth}x$rawHeight"),
          )
        }

        val exifOrientation = readExifOrientation(stagingFile)
        val decodeOptions = BitmapFactory.Options().apply {
          inSampleSize = computePowerOfTwoSampleSize(rawWidth, rawHeight, maxDimensionPx)
          inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        // Pass 2: decode subsampled bitmap and apply combined EXIF orientation + <= 1280px scale.
        val rawBitmap = BitmapFactory.decodeFile(stagingFile.absolutePath, decodeOptions)
          ?: throw ValidationException(
            message = "Unable to decode pixel buffer from selected kitchen photo.",
            issues = listOf("decodeFile: returned null"),
          )
        decodedBitmap = rawBitmap

        val finalBitmap = normalizeOrientationAndScale(rawBitmap, exifOrientation, maxDimensionPx)
        transformedBitmap = finalBitmap

        // Encode normalized <= 1280px bitmap to JPEG quality 88 and sync file descriptor.
        FileOutputStream(outputFile).use { out ->
          val compressed = finalBitmap.compress(Bitmap.CompressFormat.JPEG, clampedQuality, out)
          if (!compressed) {
            throw ValidationException(
              message = "Failed to encode preprocessed kitchen photo to JPEG.",
              issues = listOf("Bitmap.compress: returned false"),
            )
          }
          out.flush()
          runCatching { out.fd.sync() }
        }

        if (!outputFile.isFile || outputFile.length() <= 0L) {
          throw ValidationException(
            message = "Preprocessed JPEG file is empty after compression.",
            issues = listOf("outputFile: 0 bytes"),
          )
        }
        outputFile
      } catch (cancel: CancellationException) {
        deleteQuietly(outputFile)
        throw cancel
      } catch (domainError: KitchenBoardException) {
        deleteQuietly(outputFile)
        throw domainError
      } catch (ioError: IOException) {
        deleteQuietly(outputFile)
        val detail = ioError.message ?: "image I/O error"
        throw ValidationException(
          message = "Failed to preprocess attached kitchen image: $detail",
          issues = listOf("imagePreprocess: $detail"),
          cause = ioError,
        )
      } catch (decodeError: Throwable) {
        // Catch OutOfMemoryError or native BitmapFactory errors cleanly without killing the app.
        deleteQuietly(outputFile)
        val detail = decodeError.message ?: "image decode error"
        throw ValidationException(
          message = "Failed to decode attached kitchen image: $detail",
          issues = listOf("imageDecode: $detail"),
        )
      } finally {
        // Always release temporary staging file and native pixel buffers deterministically.
        deleteQuietly(stagingFile)
        if (transformedBitmap != null && transformedBitmap !== decodedBitmap) {
          transformedBitmap.recycle()
        }
        decodedBitmap?.recycle()
      }
    }

  /**
   * Safely deletes a temporary capture or preprocessed JPEG [file] without throwing.
   *
   * @param file Optional temporary file reference to delete.
   * @return `true` when an existing regular file was deleted; `false` otherwise.
   */
  fun deleteQuietly(file: File?): Boolean {
    // Swallow filesystem cleanup races so finally blocks never mask primary domain exceptions.
    if (file == null) return false
    return runCatching { file.isFile && file.delete() }.getOrDefault(false)
  }

  /**
   * Safely deletes a temporary capture or preprocessed JPEG given its [filePath] string.
   *
   * @param filePath Optional filesystem path to delete.
   * @return `true` when an existing regular file at [filePath] was deleted; `false` otherwise.
   */
  fun deletePathQuietly(filePath: String?): Boolean {
    // Guard blank path strings before constructing a java.io.File handle.
    val cleanPath = filePath?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    return deleteQuietly(File(cleanPath))
  }

  /**
   * Prunes orphaned temporary files inside `cacheDir/captures/` older than [maxAgeMillis].
   *
   * @param context Android context providing `cacheDir`.
   * @param maxAgeMillis Minimum file age in milliseconds before cleanup (`30` minutes default).
   * @return Count of stale temporary capture files deleted.
   */
  fun pruneStaleCaptures(
    context: Context,
    maxAgeMillis: Long = DEFAULT_STALE_CAPTURE_TTL_MS,
  ): Int {
    // Reclaim cacheDir storage if an earlier camera intent was abandoned via system back/kill.
    val appContext = context.applicationContext ?: context
    val capturesDir = File(appContext.cacheDir, CAPTURES_SUBDIR)
    if (!capturesDir.isDirectory) return 0
    val cutoffEpochMs = System.currentTimeMillis() - max(0L, maxAgeMillis)
    val staleFiles = capturesDir.listFiles().orEmpty().filter { candidate ->
      candidate.isFile && candidate.lastModified() < cutoffEpochMs
    }
    return staleFiles.count(::deleteQuietly)
  }

  private fun ensureCapturesDirectory(appContext: Context): File {
    val capturesDir = File(appContext.cacheDir, CAPTURES_SUBDIR)
    if (!capturesDir.exists() && !capturesDir.mkdirs() && !capturesDir.isDirectory) {
      throw ValidationException(
        message = "Unable to create scoped image capture cache directory.",
        issues = listOf("cacheDir/$CAPTURES_SUBDIR: mkdirs failed"),
      )
    }
    return capturesDir
  }

  private fun copyUriToStagingFile(appContext: Context, sourceUri: Uri, stagingFile: File) {
    val inputStream = appContext.contentResolver.openInputStream(sourceUri)
      ?: throw ValidationException(
        message = "Unable to open selected photo URI for preprocessing.",
        issues = listOf("contentResolver.openInputStream returned null"),
      )
    inputStream.use { input ->
      FileOutputStream(stagingFile).use { output ->
        input.copyTo(output, bufferSize = COPY_BUFFER_BYTES)
        output.flush()
      }
    }
    if (!stagingFile.isFile || stagingFile.length() <= 0L) {
      throw ValidationException(
        message = "Selected photo stream contained zero bytes.",
        issues = listOf("stagingFile: empty"),
      )
    }
  }

  // Default to ORIENTATION_NORMAL if an image lacks EXIF headers (e.g., PNG screenshots).
  private fun readExifOrientation(file: File): Int =
    runCatching {
      ExifInterface(file.absolutePath).getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL,
      )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

  // Compute largest power-of-two subsample factor keeping decoded edge >= maxDimensionPx.
  private fun computePowerOfTwoSampleSize(width: Int, height: Int, maxDimensionPx: Int): Int {
    var sampleSize = 1
    val longestEdge = max(width, height)
    while (longestEdge / (sampleSize * 2) >= maxDimensionPx) {
      sampleSize *= 2
    }
    return sampleSize
  }

  // Combine EXIF rotation/mirroring and proportional <= maxDimensionPx downscaling in one Matrix.
  private fun normalizeOrientationAndScale(
    source: Bitmap,
    exifOrientation: Int,
    maxDimensionPx: Int,
  ): Bitmap {
    val matrix = Matrix()
    when (exifOrientation) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
      ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
        matrix.setRotate(180f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.setRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.setRotate(-90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
      else -> Unit
    }

    val swapsAxes = exifOrientation == ExifInterface.ORIENTATION_ROTATE_90 ||
      exifOrientation == ExifInterface.ORIENTATION_ROTATE_270 ||
      exifOrientation == ExifInterface.ORIENTATION_TRANSPOSE ||
      exifOrientation == ExifInterface.ORIENTATION_TRANSVERSE

    val orientedWidth = if (swapsAxes) source.height else source.width
    val orientedHeight = if (swapsAxes) source.width else source.height
    val longestOrientedEdge = max(orientedWidth, orientedHeight)
    val scaleFactor = min(1.0f, maxDimensionPx.toFloat() / longestOrientedEdge.toFloat())

    if (scaleFactor < 1.0f) {
      matrix.postScale(scaleFactor, scaleFactor)
    }

    if (matrix.isIdentity) {
      return source
    }

    // Ensure exact <= maxDimensionPx post-transform bounds even if floating-point rounding hits +1.
    val transformed = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    val postLongest = max(transformed.width, transformed.height)
    if (postLongest <= maxDimensionPx) {
      return transformed
    }
    val clampRatio = maxDimensionPx.toFloat() / postLongest.toFloat()
    val clampedWidth = max(1, (transformed.width * clampRatio).roundToInt())
    val clampedHeight = max(1, (transformed.height * clampRatio).roundToInt())
    val clamped = Bitmap.createScaledBitmap(transformed, clampedWidth, clampedHeight, true)
    if (clamped !== transformed) {
      transformed.recycle()
    }
    return clamped
  }
}
