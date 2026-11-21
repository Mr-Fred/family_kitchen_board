package com.familykitchen.board.data

import com.familykitchen.board.domain.SchemaValidator
import com.familykitchen.board.domain.SeedCatalogDataset
import com.familykitchen.board.ports.KitchenSnapshot
import com.familykitchen.board.ports.KitchenStoragePort
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Pure-Kotlin stateful in-memory KitchenStoragePort with zero Android framework imports.
// Enables fast hermetic JUnit 4 verification of O(1) batch write invariants and Compose Previews.

/**
 * Pure-Kotlin, coroutine-safe stateful in-memory implementation of [KitchenStoragePort].
 *
 * Contains **zero** `android.*` dependencies so JVM unit tests (`BatchStorageInvariantTest`) and
 * Jetpack Compose `@Preview` fixtures can instantiate and exercise full multi-ingredient cooked
 * deductions, grocery restocks, station prep commits, and AI Chef batch updates in microseconds.
 *
 * @param initialSnapshot Optional pre-populated [KitchenSnapshot] emitted immediately on creation.
 * @param householdId Default household ID used when cold-initializing from a [SeedCatalogDataset].
 * @param boardId Default weekly board ID used when cold-initializing from a [SeedCatalogDataset].
 * @param weekStartDateIso Default ISO-8601 Monday date used when cold-initializing a weekly board.
 */
class FakeKitchenStorage(
  initialSnapshot: KitchenSnapshot? = null,
  private val householdId: String = DEFAULT_HOUSEHOLD_ID,
  private val boardId: String = DEFAULT_BOARD_ID,
  private val weekStartDateIso: String = DEFAULT_WEEK_START_ISO,
) : KitchenStoragePort {

  // Serialize concurrent state mutations so writeHistory and snapshotFlow stay strictly ordered.
  private val stateMutex = Mutex()

  // Atomic counter tracks exact user-triggered O(1) batch commits (+1 per saveSnapshotBatch/reset).
  private val writeCounter = AtomicInteger(0)

  // Backing hot StateFlow observed by ViewModels and storage invariant tests.
  private val mutableSnapshotFlow = MutableStateFlow(initialSnapshot)

  // Append-only audit trail of committed snapshots for deterministic multi-step test assertions.
  private val committedSnapshots = mutableListOf<KitchenSnapshot>()

  /** Monotonic count of atomic batch writes (`+1` per [saveSnapshotBatch] or [resetToSeed]). */
  override val batchWriteCallCount: Int
    get() = writeCounter.get()

  /** Hot reactive stream emitting the latest in-memory [KitchenSnapshot] (or `null` pre-load). */
  override val snapshotFlow: StateFlow<KitchenSnapshot?> = mutableSnapshotFlow.asStateFlow()

  /**
   * Read-only chronological snapshot of every [KitchenSnapshot] committed via [saveSnapshotBatch]
   * or [resetToSeed].
   */
  val writeHistory: List<KitchenSnapshot>
    get() = synchronized(committedSnapshots) { committedSnapshots.toList() }

  /**
   * Returns the existing in-memory [KitchenSnapshot] if already initialized, or drafts and stores
   * a fresh snapshot from [seedCatalog] without incrementing [batchWriteCallCount].
   *
   * @param seedCatalog Validated offline seed catalog dataset.
   * @return Active or freshly initialized [KitchenSnapshot].
   */
  override suspend fun loadOrInitialize(seedCatalog: SeedCatalogDataset): KitchenSnapshot {
    try {
      return stateMutex.withLock {
        val existing = mutableSnapshotFlow.value
        when {
          // Cold-start initialization populates memory once without counting as a user mutation.
          existing != null -> existing
          else -> {
            SchemaValidator.validateSeedCatalogDataset(seedCatalog)
            val initialized = KitchenSnapshot.fromSeedCatalog(
              seedCatalog = seedCatalog,
              householdId = householdId,
              boardId = boardId,
              weekStartDateIso = weekStartDateIso,
            )
            validateSnapshotInvariants(initialized)
            mutableSnapshotFlow.value = initialized
            initialized
          }
        }
      }
    } catch (cancel: CancellationException) {
      throw cancel
    }
  }

  /**
   * Atomically validates and stores [snapshot] in memory, updating [snapshotFlow] and incrementing
   * [batchWriteCallCount] by exactly `1`.
   *
   * @param snapshot Complete immutable household state snapshot to commit.
   */
  override suspend fun saveSnapshotBatch(snapshot: KitchenSnapshot) {
    try {
      stateMutex.withLock {
        commitSnapshotLocked(snapshot)
      }
    } catch (cancel: CancellationException) {
      throw cancel
    }
  }

  /**
   * Restores household state back to [seedCatalog] in a single atomic batch commit, incrementing
   * [batchWriteCallCount] by `1`.
   *
   * @param seedCatalog Validated offline seed catalog dataset.
   * @return Freshly restored [KitchenSnapshot].
   */
  override suspend fun resetToSeed(seedCatalog: SeedCatalogDataset): KitchenSnapshot {
    try {
      return stateMutex.withLock {
        SchemaValidator.validateSeedCatalogDataset(seedCatalog)
        val restored = KitchenSnapshot.fromSeedCatalog(
          seedCatalog = seedCatalog,
          householdId = householdId,
          boardId = boardId,
          weekStartDateIso = weekStartDateIso,
        )
        // Delegate to commitSnapshotLocked once so resetToSeed increments batchWriteCallCount by +1.
        commitSnapshotLocked(restored)
        restored
      }
    } catch (cancel: CancellationException) {
      throw cancel
    }
  }

  /**
   * Atomically transforms the active [KitchenSnapshot] via [transform] and commits the resulting
   * state in one `O(1)` batch write (`batchWriteCallCount += 1`).
   *
   * @param transform Pure state reducer mapping the current snapshot to the next snapshot.
   * @return Committed [KitchenSnapshot].
   */
  suspend fun updateSnapshotBatch(transform: (KitchenSnapshot) -> KitchenSnapshot): KitchenSnapshot {
    try {
      return stateMutex.withLock {
        val current = requireNotNull(mutableSnapshotFlow.value) {
          "Cannot transform KitchenSnapshot before loadOrInitialize or initialSnapshot setup."
        }
        val updated = transform(current)
        commitSnapshotLocked(updated)
        updated
      }
    } catch (cancel: CancellationException) {
      throw cancel
    }
  }

  /**
   * Resets [batchWriteCallCount] and clears [writeHistory] between scenario runs in QA Lab or
   * multi-phase JUnit tests while keeping the active [snapshotFlow] value intact.
   */
  fun resetWriteCounter() {
    writeCounter.set(0)
    synchronized(committedSnapshots) {
      committedSnapshots.clear()
    }
  }

  // Shared locked commit helper guaranteeing structural validity before bumping batchWriteCallCount.
  private fun commitSnapshotLocked(snapshot: KitchenSnapshot) {
    validateSnapshotInvariants(snapshot)
    mutableSnapshotFlow.value = snapshot
    synchronized(committedSnapshots) {
      committedSnapshots.add(snapshot)
    }
    writeCounter.incrementAndGet()
  }

  // Enforce non-negative pantry quantities and valid 21-slot board bounds at the storage boundary.
  private fun validateSnapshotInvariants(snapshot: KitchenSnapshot) {
    SchemaValidator.validateWeeklyBoard(snapshot.weeklyBoard)
    snapshot.ingredients.forEach(SchemaValidator::validateIngredientCatalogItem)
    snapshot.pantryEntries.forEach { entry ->
      SchemaValidator.validatePantryLedgerEntry(entry)
    }
  }

  companion object {
    /** Default household identifier matching [KitchenSnapshot.fromSeedCatalog]. */
    const val DEFAULT_HOUSEHOLD_ID: String = "household_family_4"

    /** Default initial weekly board identifier matching [KitchenSnapshot.fromSeedCatalog]. */
    const val DEFAULT_BOARD_ID: String = "board_week_1"

    /** Default deterministic Monday ISO-8601 week start date for offline test fixtures. */
    const val DEFAULT_WEEK_START_ISO: String = "2026-10-26"
  }
}
