package com.familykitchen.board.ports

import com.familykitchen.board.domain.DEFAULT_STARTER_MENU_TEMPLATES
import com.familykitchen.board.domain.DEFAULT_TIMESTAMP_ISO
import com.familykitchen.board.domain.SchemaValidator
import com.familykitchen.board.domain.SeedCatalogDataset
import com.familykitchen.board.domain.ValidationException
import com.familykitchen.board.domain.mergeHeritageRecipesIntoLibrary
import com.familykitchen.board.domain.migrateUntangledCatalogData
import com.familykitchen.board.domain.sanitizeExportFileSlug
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

// Portable, zero-secret household JSON backup codec and O(1) KitchenStoragePort restore helpers.
// Enables cross-reinstall, cross-signing-key, and cross-device kitchen recovery without permissions.

/** Maximum accepted UTF-8 byte size (`8 MB`) for a portable household backup JSON document. */
const val MAX_KITCHEN_BACKUP_BYTES: Int = 8 * 1024 * 1024

/** Current portable envelope version stamped onto exported SavorAI household backup files. */
const val CURRENT_KITCHEN_BACKUP_FORMAT_VERSION: Int = 2

// Forward-tolerant JSON codec accepting newly added optional fields while writing explicit defaults.
private val portableBackupJsonCodec: Json = Json {
  encodeDefaults = true
  ignoreUnknownKeys = true
  allowSpecialFloatingPointValues = false
}

/**
 * Versioned, zero-secret portable backup envelope wrapping a complete [KitchenSnapshot].
 *
 * Raw Cloud LLM API keys are never stored inside [KitchenSnapshot], so exported `.json` backup
 * files are safe to save to Downloads, Google Drive, or device-to-device file transfers.
 *
 * @property formatVersion Portable backup schema version (`2` for SavorAI v2).
 * @property exportedAtIso ISO-8601 date or timestamp when the backup file was created.
 * @property appVersionName Human-readable SavorAI release identifier (`"2.0.0"` default).
 * @property snapshot Complete validated household state snapshot.
 */
@Serializable
data class KitchenBackupEnvelope(
  val formatVersion: Int = CURRENT_KITCHEN_BACKUP_FORMAT_VERSION,
  val exportedAtIso: String = DEFAULT_TIMESTAMP_ISO,
  val appVersionName: String = "2.0.0",
  val snapshot: KitchenSnapshot,
)

/**
 * Builds a traversal-safe `.json` filename (`"SavorAI_Backup_<Household>_<YYYY-MM-DD>.json"`) for
 * Android `ActivityResultContracts.CreateDocument("application/json")` exports.
 *
 * @param householdName Current household display name from `HouseholdProfile.householdName`.
 * @param todayIso Current ISO-8601 calendar date (`"2026-11-23"`).
 * @return Sanitized `.json` filename containing only safe ASCII characters.
 */
fun buildSuggestedKitchenBackupFileName(
  householdName: String,
  todayIso: String,
): String {
  val safeHousehold = sanitizeExportFileSlug(householdName)
  // Restrict date segment to YYYY-MM-DD digits and hyphens so filenames stay clean across OSes.
  val cleanDate = todayIso.trim().take(10).filter { ch -> ch.isDigit() || ch == '-' }
    .ifEmpty { "backup" }
  return "SavorAI_Backup_${safeHousehold}_$cleanDate.json"
}

/**
 * Validates [snapshot] and serializes it into a portable [KitchenBackupEnvelope] JSON string.
 *
 * @param snapshot Active [KitchenSnapshot] to export.
 * @param exportedAtIso ISO-8601 date or timestamp stamped onto the backup envelope.
 * @param appVersionName SavorAI release version label (`"2.0.0"` default).
 * @return UTF-8 JSON payload ready for zero-permission SAF `CreateDocument` streaming.
 */
fun encodeKitchenBackupJson(
  snapshot: KitchenSnapshot,
  exportedAtIso: String = DEFAULT_TIMESTAMP_ISO,
  appVersionName: String = "2.0.0",
): String {
  // Validate snapshot boundary invariants prior to export so corrupt state is never written out.
  validateBackupSnapshotBoundary(snapshot)
  val envelope = KitchenBackupEnvelope(
    formatVersion = CURRENT_KITCHEN_BACKUP_FORMAT_VERSION,
    exportedAtIso = exportedAtIso.trim().ifEmpty { DEFAULT_TIMESTAMP_ISO },
    appVersionName = appVersionName.trim().ifEmpty { "2.0.0" },
    snapshot = snapshot,
  )
  return portableBackupJsonCodec.encodeToString(KitchenBackupEnvelope.serializer(), envelope)
}

/**
 * Parses, validates, and upgrades a portable [KitchenBackupEnvelope] **or** raw [KitchenSnapshot]
 * JSON string (`kitchen_snapshot_v1.json`) into a ready-to-persist [KitchenSnapshot].
 *
 * Applies silent forward migrations (`migrateUntangledCatalogData`, canonical 12-dish heritage
 * recipe merging, starter `savedMenus` hydration, duplicate `aiTurnHistory` turn-ID healing, and
 * `hasCompletedOnboarding = true`) so backups from earlier builds restore seamlessly.
 *
 * @param rawBackupJson Raw UTF-8 JSON text read from a user-selected backup document.
 * @param seedCatalog Optional canonical seed catalog enabling legacy bundled-item untangling.
 * @param nowIso Deterministic ISO-8601 restore timestamp.
 * @return Validated and schema-upgraded [KitchenSnapshot] ready for `saveSnapshotBatch`.
 */
fun decodeAndValidateKitchenBackupJson(
  rawBackupJson: String,
  seedCatalog: SeedCatalogDataset? = null,
  nowIso: String = DEFAULT_TIMESTAMP_ISO,
): KitchenSnapshot {
  val trimmed = rawBackupJson.trim()
  if (trimmed.isEmpty()) {
    throw ValidationException(
      message = "Selected backup file is empty. Choose a valid SavorAI .json backup.",
      issues = listOf("rawBackupJson: blank"),
    )
  }
  val utf8Length = trimmed.encodeToByteArray().size
  if (utf8Length > MAX_KITCHEN_BACKUP_BYTES) {
    throw ValidationException(
      message = "Selected file exceeds the 8 MB kitchen backup limit.",
      issues = listOf("rawBackupJson: $utf8Length > $MAX_KITCHEN_BACKUP_BYTES"),
    )
  }

  // Decode either a versioned KitchenBackupEnvelope ("snapshot" root key) or raw KitchenSnapshot.
  val decodedSnapshot = try {
    val rootObj = portableBackupJsonCodec.parseToJsonElement(trimmed).jsonObject
    when {
      "snapshot" in rootObj ->
        portableBackupJsonCodec.decodeFromString(KitchenBackupEnvelope.serializer(), trimmed).snapshot
      else ->
        portableBackupJsonCodec.decodeFromString(KitchenSnapshot.serializer(), trimmed)
    }
  } catch (err: Throwable) {
    throw ValidationException(
      message = "Invalid kitchen backup file. Select a SavorAI JSON backup exported from Family Profile.",
      issues = listOf("jsonDecode: ${err.message ?: "malformed JSON"}"),
      cause = err,
    )
  }

  // Enforce non-empty catalog/recipes and full weeklyBoard + pantry domain boundary invariants.
  validateBackupSnapshotBoundary(decodedSnapshot)

  // Apply forward catalog untangling when seedCatalog is available, preserving all custom items.
  val migrated = when {
    seedCatalog != null -> {
      val untangled = migrateUntangledCatalogData(
        ingredients = decodedSnapshot.ingredients,
        pantryEntries = decodedSnapshot.pantryEntries,
        recipes = decodedSnapshot.recipes,
        seedCatalog = seedCatalog,
      )
      decodedSnapshot.copy(
        ingredients = untangled.ingredients,
        pantryEntries = untangled.pantryEntries,
        recipes = untangled.recipes,
      )
    }
    else -> decodedSnapshot
  }

  // Merge the 12 curated heritage recipes when operating on the full household ingredient catalog.
  val mergedRecipes = when {
    storageV2SupportsHeritageLibrary(migrated.ingredients) ->
      mergeHeritageRecipesIntoLibrary(migrated.recipes)
    else -> migrated.recipes
  }
  val mergedMenus = migrated.savedMenus.ifEmpty { DEFAULT_STARTER_MENU_TEMPLATES }
  val healedTurns = deduplicateRestoredTurnIds(migrated)

  return healedTurns.copy(
    recipes = mergedRecipes,
    savedMenus = mergedMenus,
    hasCompletedOnboarding = true,
    updatedAtIso = nowIso.trim().ifEmpty { migrated.updatedAtIso },
  )
}

/**
 * Validates and restores [rawBackupJson] into this [KitchenStoragePort] in a single `O(1)` atomic
 * [KitchenStoragePort.saveSnapshotBatch] write (`batchWriteCallCount += 1`).
 *
 * @param rawBackupJson Raw UTF-8 JSON content from a SavorAI backup or `kitchen_snapshot_v1.json`.
 * @param seedCatalog Optional canonical seed catalog for forward catalog migration.
 * @param nowIso Deterministic ISO-8601 restore timestamp.
 * @return Restored and durably persisted [KitchenSnapshot].
 */
suspend fun KitchenStoragePort.restoreKitchenBackupBatch(
  rawBackupJson: String,
  seedCatalog: SeedCatalogDataset? = null,
  nowIso: String = DEFAULT_TIMESTAMP_ISO,
): KitchenSnapshot {
  // Validate and upgrade completely in memory before touching disk so invalid files never clobber state.
  val restored = decodeAndValidateKitchenBackupJson(
    rawBackupJson = rawBackupJson,
    seedCatalog = seedCatalog,
    nowIso = nowIso,
  )
  saveSnapshotBatch(restored)
  return restored
}

// Verify non-empty catalog/recipes, board slot bounds, and non-negative pantry stock quantities.
private fun validateBackupSnapshotBoundary(snapshot: KitchenSnapshot) {
  if (snapshot.ingredients.isEmpty()) {
    throw ValidationException(
      message = "Backup file is missing ingredient catalog entries.",
      issues = listOf("ingredients: must not be empty"),
    )
  }
  if (snapshot.recipes.isEmpty()) {
    throw ValidationException(
      message = "Backup file is missing household recipes.",
      issues = listOf("recipes: must not be empty"),
    )
  }
  SchemaValidator.validateWeeklyBoard(snapshot.weeklyBoard)
  snapshot.nextWeekBoard?.let(SchemaValidator::validateWeeklyBoard)
  snapshot.ingredients.forEach(SchemaValidator::validateIngredientCatalogItem)
  snapshot.pantryEntries.forEach(SchemaValidator::validatePantryLedgerEntry)
}

// Heal any duplicate aiTurnHistory IDs so Compose LazyColumn keys stay strictly unique on restore.
private fun deduplicateRestoredTurnIds(snapshot: KitchenSnapshot): KitchenSnapshot {
  if (snapshot.aiTurnHistory.size <= 1) return snapshot
  val seen = mutableSetOf<String>()
  var changed = false
  val healed = snapshot.aiTurnHistory.mapIndexed { idx, turn ->
    val base = turn.turnId.ifBlank { "turn_restored_$idx" }
    val unique = when {
      seen.add(base) -> base
      else -> {
        changed = true
        var n = 2
        while (!seen.add("${base}_dup$n")) n++
        "${base}_dup$n"
      }
    }
    if (unique == turn.turnId) turn else turn.copy(turnId = unique)
  }
  return if (changed) snapshot.copy(aiTurnHistory = healed) else snapshot
}
