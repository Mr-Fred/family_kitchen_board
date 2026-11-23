package com.familykitchen.board.data

import android.app.backup.BackupManager
import android.content.Context
import com.familykitchen.board.domain.DEFAULT_STARTER_MENU_TEMPLATES
import com.familykitchen.board.domain.SchemaValidator
import com.familykitchen.board.domain.SeedCatalogDataset
import com.familykitchen.board.domain.ValidationException
import com.familykitchen.board.domain.mergeHeritageRecipesIntoLibrary
import com.familykitchen.board.domain.migrateUntangledCatalogData
import com.familykitchen.board.ports.KitchenSnapshot
import com.familykitchen.board.ports.KitchenStoragePort
import com.familykitchen.board.ports.storageV2SupportsHeritageLibrary
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

// Mutex-guarded atomic JSON persistence adapter for offline household state on Android 12+ devices.
// Streams every batch mutation to kitchen_snapshot_v1.json.tmp + fsync + atomic rename in O(1) writes.

/**
 * Offline-first persistent implementation of [KitchenStoragePort] backed by an atomic JSON snapshot
 * file (`kitchen_snapshot_v1.json`) inside app-private storage (`Context.filesDir`).
 *
 * Every cooked meal deduction, Smart Grocery restock, unpack batch, weekly station-prep commit, or
 * AI Chef proposal update executes inside [storageMutex] as a **single `O(1)` atomic batch write**:
 * 1. Serializes the complete [KitchenSnapshot] to `kitchen_snapshot_v1.json.tmp`,
 * 2. Flushes and syncs the OS file descriptor (`fd.sync()`) to survive sudden power loss,
 * 3. Atomically renames `.tmp` over `kitchen_snapshot_v1.json` (with safe overwrite copy fallback),
 * 4. Emits the committed snapshot to [snapshotFlow] and increments [batchWriteCallCount] once.
 *
 * @param storageDirectory App-private directory hosting [fileName] (`context.filesDir` in prod).
 * @param fileName Persisted JSON snapshot filename (`"kitchen_snapshot_v1.json"`).
 * @param ioDispatcher Background I/O dispatcher used for filesystem reads, fsync, and renames.
 * @param jsonCodec Strict JSON serializer enforcing `encodeDefaults = true` and finite floats.
 * @param onUserDataChanged Optional hook notifying Android's `BackupManager` after user batch commits.
 */
class LocalJsonKitchenStorage(
  private val storageDirectory: File,
  private val fileName: String = SNAPSHOT_FILE_NAME,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val jsonCodec: Json = SchemaValidator.strictJson,
  private val onUserDataChanged: () -> Unit = {},
) : KitchenStoragePort {

  /**
   * Production convenience constructor resolving `File(context.filesDir, fileName)` via
   * `applicationContext` so Activity contexts are never retained, and scheduling scoped Android
   * Auto Backup notifications on user commits.
   *
   * @param context Android context providing app-private `filesDir`.
   * @param fileName Persisted JSON snapshot filename (`"kitchen_snapshot_v1.json"`).
   * @param ioDispatcher Background I/O dispatcher used for filesystem operations.
   */
  constructor(
    context: Context,
    fileName: String = SNAPSHOT_FILE_NAME,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  ) : this(
    storageDirectory = (context.applicationContext ?: context).filesDir,
    fileName = fileName,
    ioDispatcher = ioDispatcher,
    onUserDataChanged = buildBackupDataChangedCallback(context),
  )

  // Coroutine Mutex serializes concurrent load, save, and reset calls without blocking threads.
  private val storageMutex = Mutex()

  // Monotonic telemetry counter verifying zero N+1 write loops per user batch action.
  private val writeCounter = AtomicInteger(0)

  // Hot in-memory state cache mirrored 1:1 with the latest durable JSON file on disk.
  private val mutableSnapshotFlow = MutableStateFlow<KitchenSnapshot?>(null)

  /** Target canonical snapshot file (`kitchen_snapshot_v1.json`). */
  val snapshotFile: File
    get() = File(storageDirectory, fileName)

  /** Monotonic count of atomic user batch writes (`+1` per [saveSnapshotBatch] or [resetToSeed]). */
  override val batchWriteCallCount: Int
    get() = writeCounter.get()

  /** Hot reactive stream emitting the latest persisted [KitchenSnapshot] (or `null` pre-load). */
  override val snapshotFlow: StateFlow<KitchenSnapshot?> = mutableSnapshotFlow.asStateFlow()

  /**
   * Loads the existing persisted [KitchenSnapshot] from [snapshotFile], or initializes and persists
   * a fresh snapshot from [seedCatalog] when starting cold (or recovering from corrupt disk state).
   *
   * Cold-start seeding and silent v2 schema upgrades (`migrateUntangledCatalogData`, starter
   * `savedMenus`, and canonical heritage recipes) do not increment [batchWriteCallCount] so user
   * mutation counters always start at `0` after app launch.
   *
   * @param seedCatalog Validated offline seed catalog dataset.
   * @return Loaded or freshly seeded [KitchenSnapshot].
   */
  override suspend fun loadOrInitialize(seedCatalog: SeedCatalogDataset): KitchenSnapshot {
    try {
      return storageMutex.withLock {
        val cached = mutableSnapshotFlow.value
        if (cached != null) {
          return@withLock cached
        }
        withContext(ioDispatcher) {
          val targetFile = snapshotFile
          // Detect whether a durable snapshot file existed prior to cold-start evaluation.
          val hadExistingFile = targetFile.isFile && targetFile.length() > 0L
          val existingSnapshot = when {
            hadExistingFile -> storageV2TryReadExistingSnapshot(targetFile)
            else -> null
          }
          when {
            existingSnapshot != null -> {
              // Seamlessly upgrade legacy bundled catalog names, starter savedMenus, and canonical
              // 12-dish heritage recipes without incrementing user batchWriteCallCount telemetry.
              val (upgradedSnapshot, didUpgrade) = storageV2UpgradeExistingSnapshot(
                existingSnapshot = existingSnapshot,
                seedCatalog = seedCatalog,
              )
              when {
                didUpgrade -> {
                  storageV2WriteSnapshotAtomicallyLocked(upgradedSnapshot, incrementWriteCounter = false)
                  mutableSnapshotFlow.value = upgradedSnapshot
                  upgradedSnapshot
                }
                else -> {
                  mutableSnapshotFlow.value = existingSnapshot
                  existingSnapshot
                }
              }
            }
            else -> {
              // Brand-new installs (hadExistingFile == false) seed hasCompletedOnboarding = false;
              // corrupt-file recoveries (hadExistingFile == true) preserve completed status.
              SchemaValidator.validateSeedCatalogDataset(seedCatalog)
              val seeded = KitchenSnapshot.fromSeedCatalog(
                seedCatalog = seedCatalog,
                hasCompletedOnboarding = hadExistingFile,
              )
              storageV2WriteSnapshotAtomicallyLocked(seeded, incrementWriteCounter = false)
              seeded
            }
          }
        }
      }
    } catch (cancel: CancellationException) {
      throw cancel
    }
  }

  /**
   * Atomically persists [snapshot] to `kitchen_snapshot_v1.json` via a synced `.tmp` file + rename,
   * updates [snapshotFlow], and increments [batchWriteCallCount] by `1`.
   *
   * @param snapshot Complete immutable household state snapshot to persist.
   */
  override suspend fun saveSnapshotBatch(snapshot: KitchenSnapshot) {
    try {
      storageMutex.withLock {
        withContext(ioDispatcher) {
          storageV2WriteSnapshotAtomicallyLocked(snapshot, incrementWriteCounter = true)
        }
      }
    } catch (cancel: CancellationException) {
      throw cancel
    }
  }

  /**
   * Resets persisted household state back to [seedCatalog] in a single atomic `.tmp` + rename batch
   * transaction, incrementing [batchWriteCallCount] by `1`.
   *
   * @param seedCatalog Validated offline seed catalog dataset.
   * @return Freshly restored and persisted [KitchenSnapshot].
   */
  override suspend fun resetToSeed(seedCatalog: SeedCatalogDataset): KitchenSnapshot {
    try {
      return storageMutex.withLock {
        withContext(ioDispatcher) {
          SchemaValidator.validateSeedCatalogDataset(seedCatalog)
          val restored = KitchenSnapshot.fromSeedCatalog(seedCatalog)
          storageV2WriteSnapshotAtomicallyLocked(restored, incrementWriteCounter = true)
          restored
        }
      }
    } catch (cancel: CancellationException) {
      throw cancel
    }
  }

  // Upgrade legacy bundled catalog items, empty savedMenus, and canonical heritage recipes cleanly.
  private fun storageV2UpgradeExistingSnapshot(
    existingSnapshot: KitchenSnapshot,
    seedCatalog: SeedCatalogDataset,
  ): Pair<KitchenSnapshot, Boolean> {
    val migration = migrateUntangledCatalogData(
      ingredients = existingSnapshot.ingredients,
      pantryEntries = existingSnapshot.pantryEntries,
      recipes = existingSnapshot.recipes,
      seedCatalog = seedCatalog,
    )
    val baseRecipes = migration.recipes
    val upgradedRecipes = when {
      storageV2SupportsHeritageLibrary(migration.ingredients) ->
        mergeHeritageRecipesIntoLibrary(baseRecipes)
      else -> baseRecipes
    }
    val upgradedMenus = existingSnapshot.savedMenus.ifEmpty { DEFAULT_STARTER_MENU_TEMPLATES }
    val didUpgrade = migration.didMutate ||
      upgradedRecipes.size != existingSnapshot.recipes.size ||
      existingSnapshot.savedMenus.isEmpty()

    val next = when {
      didUpgrade -> existingSnapshot.copy(
        ingredients = migration.ingredients,
        pantryEntries = migration.pantryEntries,
        recipes = upgradedRecipes,
        savedMenus = upgradedMenus,
      )
      else -> existingSnapshot
    }
    return next to didUpgrade
  }

  // Attempt strict JSON decode + domain validation; heal duplicate turn IDs or return null if corrupt.
  private fun storageV2TryReadExistingSnapshot(file: File): KitchenSnapshot? =
    try {
      val rawJson = file.readText(Charsets.UTF_8)
      if (rawJson.isBlank()) {
        null
      } else {
        val decoded = jsonCodec.decodeFromString(KitchenSnapshot.serializer(), rawJson)
        storageV2ValidateSnapshotBoundary(decoded)
        storageV2SanitizeSnapshotTurnIds(decoded)
      }
    } catch (_: Throwable) {
      null
    }

  // Deduplicate any legacy colliding aiTurnHistory keys so LazyColumn never crashes after app update.
  private fun storageV2SanitizeSnapshotTurnIds(snapshot: KitchenSnapshot): KitchenSnapshot {
    if (snapshot.aiTurnHistory.size <= 1) return snapshot
    val seenIds = mutableSetOf<String>()
    var mutated = false
    val repairedTurns = snapshot.aiTurnHistory.mapIndexed { index, turn ->
      val baseId = turn.turnId.ifBlank { "turn_healed_$index" }
      val uniqueId = when {
        seenIds.add(baseId) -> baseId
        else -> {
          mutated = true
          var suffix = 2
          while (!seenIds.add("${baseId}_dup$suffix")) suffix++
          "${baseId}_dup$suffix"
        }
      }
      if (uniqueId == turn.turnId) turn else {
        mutated = true
        turn.copy(turnId = uniqueId)
      }
    }
    return if (mutated) snapshot.copy(aiTurnHistory = repairedTurns) else snapshot
  }

  // Core O(1) atomic persistence routine: validate -> write .tmp -> fsync -> rename -> emit StateFlow.
  private fun storageV2WriteSnapshotAtomicallyLocked(
    snapshot: KitchenSnapshot,
    incrementWriteCounter: Boolean,
  ) {
    storageV2ValidateSnapshotBoundary(snapshot)
    if (!storageDirectory.exists() && !storageDirectory.mkdirs() && !storageDirectory.isDirectory) {
      throw ValidationException(
        message = "Unable to create offline storage directory '${storageDirectory.name}'.",
        issues = listOf("storageDirectory: mkdirs failed"),
      )
    }

    val targetFile = snapshotFile
    val tempFile = File(storageDirectory, "$fileName$TEMP_FILE_SUFFIX")
    val encodedBytes = jsonCodec
      .encodeToString(KitchenSnapshot.serializer(), snapshot)
      .toByteArray(Charsets.UTF_8)

    try {
      // Stream full JSON payload to .tmp and sync the file descriptor before replacing targetFile.
      FileOutputStream(tempFile).use { output ->
        output.write(encodedBytes)
        output.flush()
        runCatching { output.fd.sync() }
      }

      // Prefer POSIX atomic rename; fall back to overwrite-copy + temp cleanup if rename returns false.
      val renamed = tempFile.renameTo(targetFile)
      if (!renamed) {
        tempFile.copyTo(targetFile, overwrite = true)
        tempFile.delete()
      }

      mutableSnapshotFlow.value = snapshot
      if (incrementWriteCounter) {
        writeCounter.incrementAndGet()
        // Notify Android BackupManager so allowlist-only kitchen_snapshot_v1.json queues for backup.
        runCatching { onUserDataChanged() }
      }
    } catch (ioError: IOException) {
      // Remove any incomplete .tmp artifact so future launches never observe torn writes.
      runCatching { if (tempFile.exists()) tempFile.delete() }
      val detail = ioError.message ?: "disk write error"
      throw ValidationException(
        message = "Atomic kitchen snapshot write failed: $detail",
        issues = listOf("$fileName: $detail"),
        cause = ioError,
      )
    }
  }

  // Verify board slot bounds, optional nextWeekBoard, catalog items, and non-negative pantry quantities.
  private fun storageV2ValidateSnapshotBoundary(snapshot: KitchenSnapshot) {
    SchemaValidator.validateWeeklyBoard(snapshot.weeklyBoard)
    snapshot.nextWeekBoard?.let(SchemaValidator::validateWeeklyBoard)
    snapshot.ingredients.forEach(SchemaValidator::validateIngredientCatalogItem)
    snapshot.pantryEntries.forEach { entry ->
      SchemaValidator.validatePantryLedgerEntry(entry)
    }
  }

  companion object {
    /** Canonical persisted JSON snapshot filename inside `Context.filesDir`. */
    const val SNAPSHOT_FILE_NAME: String = "kitchen_snapshot_v1.json"

    /** Temporary staging suffix used for atomic write-and-rename persistence. */
    const val TEMP_FILE_SUFFIX: String = ".tmp"

    // Builds an Activity-leak-free BackupManager callback outside secondary-constructor <this> scope.
    private fun buildBackupDataChangedCallback(context: Context): () -> Unit {
      val appCtx = context.applicationContext ?: context
      return {
        val pkg = appCtx.packageName
        if (!pkg.isNullOrBlank()) {
          kotlin.runCatching { BackupManager.dataChanged(pkg) }
        }
      }
    }
  }
}
