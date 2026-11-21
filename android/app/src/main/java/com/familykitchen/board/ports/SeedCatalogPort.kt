package com.familykitchen.board.ports

import com.familykitchen.board.domain.SeedCatalogDataset

// Pure outbound port isolating Android AssetManager I/O from domain and ViewModel callers.
// Enables fast hermetic JVM unit tests via single-expression SAM lambda fixtures.

/**
 * Hexagonal outbound port for loading the offline curated household seed catalog
 * (`assets/seed_catalog.json`) and validating its referential integrity via `SchemaValidator`.
 *
 * Declared as a Kotlin `fun interface` so unit tests can supply concise lambda fixtures without
 * touching Android `AssetManager` or filesystem I/O.
 */
fun interface SeedCatalogPort {
  /**
   * Loads and schema-validates the canonical [SeedCatalogDataset] containing curated Tier-1 and
   * Tier-2 ingredients, Family-of-4 recipes, and initial pantry ledger entries.
   *
   * @return Validated immutable [SeedCatalogDataset].
   */
  suspend fun loadSeedCatalog(): SeedCatalogDataset
}
