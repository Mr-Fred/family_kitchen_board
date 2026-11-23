package com.familykitchen.board.domain

import com.familykitchen.board.data.FakeKitchenStorage
import com.familykitchen.board.data.LocalJsonKitchenStorage
import com.familykitchen.board.ports.AiEdgePort
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ports.KitchenSnapshot
import com.familykitchen.board.ports.SeedCatalogPort
import com.familykitchen.board.ports.VoiceInputPort
import com.familykitchen.board.ports.VoiceInputState
import com.familykitchen.board.ui.DEFAULT_TOAST_AUTO_DISMISS_MS
import com.familykitchen.board.ui.EXTENDED_TOAST_AUTO_DISMISS_MS
import com.familykitchen.board.ui.KitchenBoardViewModel
import com.familykitchen.board.ui.KitchenUiState
import com.familykitchen.board.ui.PERMISSION_NOTICE_AUTO_DISMISS_MS
import com.familykitchen.board.ui.UNDO_BAR_AUTO_DISMISS_MS
import com.familykitchen.board.ui.computeToastAutoDismissMillis
import com.familykitchen.board.ui.preview.PreviewFixtures
import com.familykitchen.board.ui.withToast
import java.io.File
import java.io.StringReader
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.Path as KotlinPath
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource

// Hermetic JVM JUnit 4 suite verifying single-ingredient catalog untangling (44 canonical items +
// 26 local presets + 5 brain boosters), non-destructive 38 -> 44 LocalJsonKitchenStorage snapshot
// migration, auto-dismissing toast timing & monotonic toastEventIds, and adaptive launcher XMLs.

/**
 * Hermetic JUnit 4 verification suite covering single-ingredient catalog untangling, legacy
 * persisted snapshot upgrades, auto-dismissing notification timing, and `SavorAI` adaptive icons:
 * 1. Zero `" & "` / `" / "` compound bundles across `seed_catalog.json` (`44` items),
 *    `LOCAL_MARKET_PRESET_INGREDIENTS` (`26` items), `CURATED_BRAIN_BOOSTER_CANDIDATES` (`5` items),
 *    and `PreviewFixtures`, plus `EC-03` `NUT_SEED_TOPPING` allergen auto-swap (`Sliced Almonds` ->
 *    `Roasted Pumpkin Seeds`) on `rec_05_skillet_honey_oat_granola`,
 * 2. Seamless `LocalJsonKitchenStorage` upgrade of legacy 38-item bundled snapshots preserving
 *    custom user stock, `isLocallyAvailable = false`, custom `ing_custom_*` entries, and `0` user
 *    `batchWriteCallCount` while leaving synthetic unit-test catalogs untouched,
 * 3. `computeToastAutoDismissMillis` (`4s` vs `6s`), `PERMISSION_NOTICE_AUTO_DISMISS_MS` (`5s`),
 *    `UNDO_BAR_AUTO_DISMISS_MS` (`8s`), and monotonic `KitchenUiState.toastEventId` increments,
 * 4. Valid `AndroidManifest.xml` + `108dp` adaptive launcher vector/mipmap XML resources using only
 *    warm artisanal `SavorAI` palette tokens.
 */
class CatalogUntangleAndToastTimingTest {

  /** Temporary directory cleaned up deterministically after each storage migration test. */
  @get:Rule
  val tempFolder: TemporaryFolder = TemporaryFolder()

  // Six newly separated single-ingredient partner IDs introduced when untangling the 38-item seed.
  private val newlySplitPartnerIds: Set<String> = setOf(
    "ing_amaranth_greens",
    "ing_pumpkin_seeds",
    "ing_ginger",
    "ing_red_onion",
    "ing_chicken_breast",
    "ing_red_beans",
  )

  /**
   * Verifies that `data/seed_catalog.json` and `android/app/src/main/assets/seed_catalog.json` are
   * byte-for-byte identical (`44` ingredients, `30` recipes, `44` pantry entries), zero compound
   * `" & "` or `" / "` bundles remain across seed/preset/discovery catalogs, distinct `LEAFY_GREENS`
   * and `NUT_SEED_TOPPING` items are properly enrolled, and `EC-03` rewrites `Sliced Almonds` ->
   * `Roasted Pumpkin Seeds` on `rec_05_skillet_honey_oat_granola` when `NUTS` is blocked.
   */
  @Test
  fun seedPresetsAndBrainBoosters_containZeroCompoundBundlesAndDistinctSpinachAmaranthPumpkinAlmonds() {
    val rootSeedPath = resolveRepoFile("data/seed_catalog.json")
    val assetSeedPath = resolveRepoFile("android/app/src/main/assets/seed_catalog.json")

    // Confirm strict byte-for-byte parity between root data/seed_catalog.json and bundled Android asset.
    val rootBytes = rootSeedPath.readBytes()
    val assetBytes = assetSeedPath.readBytes()
    assertArrayEquals(rootBytes, assetBytes)

    val dataset = SchemaValidator.decodeSeedCatalogDatasetJson(rootBytes.toString(Charsets.UTF_8))
    assertEquals(44, dataset.ingredients.size)
    assertEquals(38, dataset.ingredients.count { it.tier == IngredientTier.TIER_1_QUANTITATIVE })
    assertEquals(6, dataset.ingredients.count { it.tier == IngredientTier.TIER_2_STAPLE })
    assertEquals(30, dataset.recipes.size)
    assertEquals(44, dataset.initialPantry.size)
    assertEquals(26, LOCAL_MARKET_PRESET_INGREDIENTS.size)
    assertEquals(5, CURATED_BRAIN_BOOSTER_CANDIDATES.size)

    // Assert zero compound " & " or " / " display names across seed, presets, brain boosters, and previews.
    val allAuditedDisplayNames = buildList {
      addAll(dataset.ingredients.map { "seed:${it.ingredientId}=${it.displayName}" to it.displayName })
      addAll(LOCAL_MARKET_PRESET_INGREDIENTS.map { "preset:${it.ingredientId}=${it.displayName}" to it.displayName })
      addAll(CURATED_BRAIN_BOOSTER_CANDIDATES.map { "booster:${it.item.ingredientId}=${it.item.displayName}" to it.item.displayName })
      addAll(PreviewFixtures.catalog.map { "preview:${it.ingredientId}=${it.displayName}" to it.displayName })
    }
    val bundledViolations = allAuditedDisplayNames
      .filter { (_, displayName) -> displayName.contains(" & ") || displayName.contains(" / ") }
      .map { (tag, _) -> tag }
    assertEquals(emptyList<String>(), bundledViolations)

    // Verify ing_spinach and ing_amaranth_greens are distinct single-item LEAFY_GREENS entries.
    val catalogById = indexCatalogById(dataset.ingredients)
    val spinach = requireNotNull(catalogById["ing_spinach"])
    val amaranth = requireNotNull(catalogById["ing_amaranth_greens"])
    assertEquals("Fresh Baby Spinach", spinach.displayName)
    assertEquals(listOf("LEAFY_GREENS"), spinach.substituteGroupIds)
    assertEquals("Fresh Amaranth Greens", amaranth.displayName)
    assertEquals(listOf("LEAFY_GREENS"), amaranth.substituteGroupIds)

    // Verify ing_mixed_nuts_seeds (NUTS) and ing_pumpkin_seeds (nut-free) share NUT_SEED_TOPPING.
    val slicedAlmonds = requireNotNull(catalogById["ing_mixed_nuts_seeds"])
    val pumpkinSeeds = requireNotNull(catalogById["ing_pumpkin_seeds"])
    assertEquals("Sliced Almonds", slicedAlmonds.displayName)
    assertEquals(listOf("NUTS"), slicedAlmonds.allergens)
    assertEquals(listOf("NUT_SEED_TOPPING"), slicedAlmonds.substituteGroupIds)
    assertEquals("Roasted Pumpkin Seeds", pumpkinSeeds.displayName)
    assertEquals(emptyList<String>(), pumpkinSeeds.allergens)
    assertEquals(listOf("NUT_SEED_TOPPING"), pumpkinSeeds.substituteGroupIds)

    // Verify EC-03 allergen auto-swap keeps rec_05 schedulable under NUTS allergy via pumpkin seeds.
    val granolaRecipe = requireNotNull(dataset.recipes.find { it.recipeId == "rec_05_skillet_honey_oat_granola" })
    val nutAllergyEval = AvailabilityFilter.evaluateRecipeAvailability(
      recipe = granolaRecipe,
      catalog = dataset.ingredients,
      householdAllergens = listOf("NUTS"),
      pantry = dataset.initialPantry,
    )
    assertTrue(nutAllergyEval.isSchedulable)
    assertEquals("ing_pumpkin_seeds", nutAllergyEval.substitutionsApplied["ing_mixed_nuts_seeds"])
    assertTrue(nutAllergyEval.resolvedBomLines.any { it.ingredientId == "ing_pumpkin_seeds" })
    assertTrue(nutAllergyEval.resolvedBomLines.none { it.ingredientId == "ing_mixed_nuts_seeds" })
  }

  /**
   * Verifies that [LocalJsonKitchenStorage.loadOrInitialize] upgrades a persisted legacy 38-item
   * bundled `kitchen_snapshot_v1.json` in place without wiping custom stock (`175.0g`), losing
   * `isLocallyAvailable = false`, dropping custom `ing_custom_ube`, inventing phantom stock on the
   * 6 newly split partner items, or incrementing `batchWriteCallCount`, while leaving synthetic
   * non-canonical test catalogs (`ing_bulk_1..3`) untouched.
   */
  @Test
  fun localJsonKitchenStorage_upgradesLegacy38ItemBundledSnapshotWithoutWipingStockOrIncrementingWriteCount() =
    runTest {
      val canonicalSeed = SchemaValidator.decodeSeedCatalogDatasetJson(
        resolveRepoFile("data/seed_catalog.json").readText(),
      )

      // Construct a realistic pre-upgrade 38-item legacy catalog + 1 user custom item ("ing_custom_ube").
      val customUbe = IngredientCatalogItem(
        ingredientId = "ing_custom_ube",
        displayName = "Purple Ube Halaya",
        category = IngredientCategory.PRODUCE,
        tier = IngredientTier.TIER_1_QUANTITATIVE,
        baseUnit = BaseUnit.GRAM,
        defaultPackageQuantity = 500.0,
      )
      val legacy38Catalog = canonicalSeed.ingredients
        .filterNot { it.ingredientId in newlySplitPartnerIds }
        .map { item ->
          when (item.ingredientId) {
            "ing_spinach" -> item.copy(
              displayName = "Fresh Baby Spinach & Amaranth Greens",
              isLocallyAvailable = false,
            )
            "ing_mixed_nuts_seeds" -> item.copy(
              displayName = "Pumpkin Seeds & Sliced Almonds",
              substituteGroupIds = emptyList(),
            )
            "ing_cheese" -> item.copy(displayName = "Comte & Cheddar Cheese")
            else -> item
          }
        } + customUbe
      assertEquals(39, legacy38Catalog.size)

      // Customize ing_spinach stock to 175.0g and include ing_custom_ube at 400.0g in legacy pantry.
      val legacyPantry = canonicalSeed.initialPantry
        .filterNot { it.ingredientId in newlySplitPartnerIds }
        .map { entry ->
          when (entry.ingredientId) {
            "ing_spinach" -> entry.copy(currentQuantity = 175.0, isOutOfStock = false)
            else -> entry
          }
        } + PantryLedgerEntry("ing_custom_ube", IngredientTier.TIER_1_QUANTITATIVE, 400.0, StapleStatus.PLENTY)

      // Strip NUT_SEED_TOPPING from rec_05's ing_mixed_nuts_seeds BOM line to mirror pre-upgrade JSON.
      val legacyRecipes = canonicalSeed.recipes.map { recipe ->
        when (recipe.recipeId) {
          "rec_05_skillet_honey_oat_granola" -> recipe.copy(
            bomLines = recipe.bomLines.map { line ->
              when (line.ingredientId) {
                "ing_mixed_nuts_seeds" -> line.copy(substituteGroupId = null)
                else -> line
              }
            },
          )
          else -> recipe
        }
      }

      val baseSnapshot = KitchenSnapshot.fromSeedCatalog(canonicalSeed, hasCompletedOnboarding = true)
      val legacySnapshot = baseSnapshot.copy(
        ingredients = legacy38Catalog,
        pantryEntries = legacyPantry,
        recipes = legacyRecipes,
      )

      val storageDir = tempFolder.newFolder("legacy_38_bundled_store")
      val snapshotFile = File(storageDir, LocalJsonKitchenStorage.SNAPSHOT_FILE_NAME)
      snapshotFile.writeText(
        SchemaValidator.strictJson.encodeToString(KitchenSnapshot.serializer(), legacySnapshot),
        Charsets.UTF_8,
      )

      // Load via LocalJsonKitchenStorage and verify silent non-destructive upgrade (0 user writes).
      val storage = LocalJsonKitchenStorage(storageDirectory = storageDir)
      val upgraded = storage.loadOrInitialize(canonicalSeed)
      assertEquals(0, storage.batchWriteCallCount)
      assertEquals(45, upgraded.ingredients.size)
      assertEquals(45, upgraded.pantryEntries.size)

      // 1. Existing bundled items rename cleanly while preserving isLocallyAvailable and stock.
      val upgradedSpinach = requireNotNull(upgraded.catalogById["ing_spinach"])
      assertEquals("Fresh Baby Spinach", upgradedSpinach.displayName)
      assertFalse(upgradedSpinach.isLocallyAvailable)
      assertEquals(175.0, requireNotNull(upgraded.pantryById["ing_spinach"]).currentQuantity, 0.001)

      val upgradedAlmonds = requireNotNull(upgraded.catalogById["ing_mixed_nuts_seeds"])
      assertEquals("Sliced Almonds", upgradedAlmonds.displayName)
      assertEquals(listOf("NUT_SEED_TOPPING"), upgradedAlmonds.substituteGroupIds)
      assertEquals("Aged Cheddar Cheese", requireNotNull(upgraded.catalogById["ing_cheese"]).displayName)

      // Custom user catalog & pantry entries survive intact.
      assertEquals("Purple Ube Halaya", requireNotNull(upgraded.catalogById["ing_custom_ube"]).displayName)
      assertEquals(400.0, requireNotNull(upgraded.pantryById["ing_custom_ube"]).currentQuantity, 0.001)

      // 2. rec_05_skillet_honey_oat_granola BOM line upgrades to substituteGroupId == "NUT_SEED_TOPPING".
      val upgradedGranola = requireNotNull(upgraded.recipesById["rec_05_skillet_honey_oat_granola"])
      val granolaNutLine = requireNotNull(upgradedGranola.bomLines.find { it.ingredientId == "ing_mixed_nuts_seeds" })
      assertEquals("NUT_SEED_TOPPING", granolaNutLine.substituteGroupId)

      // 3. All 6 newly split partner items append at 0.0 / OUT / isOutOfStock == true (0 phantom stock).
      for (partnerId in newlySplitPartnerIds) {
        assertNotNull(upgraded.catalogById[partnerId])
        val partnerPantry = requireNotNull(upgraded.pantryById[partnerId])
        assertEquals(0.0, partnerPantry.currentQuantity, 0.001)
        assertTrue(partnerPantry.isOutOfStock)
        assertEquals(StapleStatus.OUT, partnerPantry.stapleStatus)
        assertNull(partnerPantry.expiresInDays)
      }

      // 4. Synthetic unit-test catalogs (lacking canonical "ing_spinach") remain 100% untouched.
      val syntheticCatalog = (1..3).map { idx ->
        IngredientCatalogItem(
          ingredientId = "ing_bulk_$idx",
          displayName = "Bulk Perishable $idx",
          category = IngredientCategory.PRODUCE,
          tier = IngredientTier.TIER_1_QUANTITATIVE,
          baseUnit = BaseUnit.COUNT,
          defaultPackageQuantity = 10.0,
        )
      }
      val syntheticPantry = (1..3).map { idx ->
        PantryLedgerEntry("ing_bulk_$idx", IngredientTier.TIER_1_QUANTITATIVE, 12.0, StapleStatus.PLENTY)
      }
      val syntheticMigration = migrateUntangledCatalogData(
        ingredients = syntheticCatalog,
        pantryEntries = syntheticPantry,
        recipes = emptyList(),
        seedCatalog = canonicalSeed,
      )
      assertFalse(syntheticMigration.didMutate)
      assertEquals(syntheticCatalog, syntheticMigration.ingredients)
      assertEquals(syntheticPantry, syntheticMigration.pantryEntries)
    }

  /**
   * Verifies `computeToastAutoDismissMillis` (`4_000L` vs `6_000L`), `PERMISSION_NOTICE_AUTO_DISMISS_MS`
   * (`5_000L`), `UNDO_BAR_AUTO_DISMISS_MS` (`8_000L`), and monotonic `KitchenUiState.toastEventId`
   * increments across identical consecutive toasts and [KitchenBoardViewModel] user actions.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun toastAutoDismissTimingAndEventIds_scaleDurationAndIncrementMonotonicallyAcrossIdenticalToasts() =
    runTest {
      // 1. Verify exact auto-dismiss timing constants across toast, permission, and Undo surfaces.
      assertEquals(4_000L, DEFAULT_TOAST_AUTO_DISMISS_MS)
      assertEquals(6_000L, EXTENDED_TOAST_AUTO_DISMISS_MS)
      assertEquals(5_000L, PERMISSION_NOTICE_AUTO_DISMISS_MS)
      assertEquals(8_000L, UNDO_BAR_AUTO_DISMISS_MS)

      // Short positive confirmations stay crisp at 4,000 ms.
      assertEquals(
        4_000L,
        computeToastAutoDismissMillis("Loaded gemma-4-E2B-it.litertlm (LiteRT-LM GPU + Vision) — ready!"),
      )
      assertEquals(4_000L, computeToastAutoDismissMillis("Undid last Sous-Chef change."))

      // Error/warning keywords and messages exceeding 90 characters scale up to 6,000 ms.
      assertEquals(6_000L, computeToastAutoDismissMillis("Unable to import model"))
      assertEquals(6_000L, computeToastAutoDismissMillis("Connection check failed"))
      assertEquals(6_000L, computeToastAutoDismissMillis("Validation error on pantry snapshot"))
      assertEquals(6_000L, computeToastAutoDismissMillis("Action blocked by allergen guardrail"))
      assertEquals(6_000L, computeToastAutoDismissMillis("Invalid cloud model identifier"))
      assertEquals(6_000L, computeToastAutoDismissMillis("Metered cellular warning active"))
      val overNinetyCharConfirmation =
        "Scheduled 'One-Pan Lemon Herb Salmon & Jasmine Rice' across Monday through Friday dinners cleanly."
      assertTrue(overNinetyCharConfirmation.length > 90)
      assertEquals(6_000L, computeToastAutoDismissMillis(overNinetyCharConfirmation))

      // 2. Verify KitchenUiState.withToast increments toastEventId even on identical back-to-back strings.
      val initialUi = KitchenUiState()
      assertEquals(0L, initialUi.toastEventId)
      assertNull(initialUi.toastMessage)

      val firstToastUi = initialUi.withToast("Saved kitchen note.")
      assertEquals("Saved kitchen note.", firstToastUi.toastMessage)
      assertEquals(1L, firstToastUi.toastEventId)

      val repeatedSameToastUi = firstToastUi.withToast("Saved kitchen note.")
      assertEquals("Saved kitchen note.", repeatedSameToastUi.toastMessage)
      assertEquals(2L, repeatedSameToastUi.toastEventId)

      // Clearing via null or blank whitespace removes toastMessage while preserving toastEventId.
      val clearedNullUi = repeatedSameToastUi.withToast(null)
      assertNull(clearedNullUi.toastMessage)
      assertEquals(2L, clearedNullUi.toastEventId)

      val clearedBlankUi = repeatedSameToastUi.withToast("   ")
      assertNull(clearedBlankUi.toastMessage)
      assertEquals(2L, clearedBlankUi.toastEventId)

      // 3. Verify KitchenBoardViewModel increments toastEventId monotonically across live actions.
      Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
      try {
        val canonicalSeed = SchemaValidator.decodeSeedCatalogDatasetJson(
          resolveRepoFile("data/seed_catalog.json").readText(),
        )
        val storage = FakeKitchenStorage()
        val viewModel = KitchenBoardViewModel(
          seedCatalogPort = SeedCatalogPort { canonicalSeed },
          storagePort = storage,
          aiEdgePort = SilentToastTestAiEdgePort(),
          voiceInputPort = SilentToastTestVoicePort(),
          deleteTempImage = {},
        )
        assertEquals(0L, viewModel.uiState.value.toastEventId)

        viewModel.toggleWideCounterLayout()
        assertEquals("Wide Counter Mode enabled.", viewModel.uiState.value.toastMessage)
        assertEquals(1L, viewModel.uiState.value.toastEventId)

        viewModel.dismissToast()
        assertNull(viewModel.uiState.value.toastMessage)
        assertEquals(1L, viewModel.uiState.value.toastEventId)

        viewModel.resetHouseholdStateToSeed()
        assertEquals(
          "Reset household state to canonical seed in 1 atomic write.",
          viewModel.uiState.value.toastMessage,
        )
        assertEquals(2L, viewModel.uiState.value.toastEventId)
        assertEquals(1, storage.batchWriteCallCount)

        // Consecutive identical action emits the same toast text and bumps toastEventId 2L -> 3L.
        viewModel.resetHouseholdStateToSeed()
        assertEquals(3L, viewModel.uiState.value.toastEventId)
        assertEquals(2, storage.batchWriteCallCount)
      } finally {
        Dispatchers.resetMain()
      }
    }

  /**
   * Verifies `AndroidManifest.xml` and all 5 adaptive launcher XML resources (`ic_launcher.xml`,
   * `ic_launcher_round.xml`, `ic_launcher_background.xml`, `ic_launcher_foreground.xml`, and
   * `ic_launcher_monochrome.xml`) parse cleanly, bind `@mipmap/ic_launcher` & `@mipmap/ic_launcher_round`,
   * declare `108dp` viewports, and use warm `SavorAI` hexes (`#FAF7F2`, `#2E7D5A`, `#C85A32`) with
   * zero cold blue or purple hues.
   */
  @Test
  fun launcherAdaptiveIconsAndManifest_declareValidVectorAndMipmapResources() {
    val androidNs = "http://schemas.android.com/apk/res/android"
    val manifestText = resolveRepoFile("android/app/src/main/AndroidManifest.xml").readText()
    val manifestDoc = parseSecureXml(manifestText)
    val appElement = manifestDoc.getElementsByTagName("application").item(0) as Element

    // Confirm <application> wires adaptive launcher mipmaps, HTTPS network config, fragile-data
    // uninstall retention, and allowlist-only backup rules (kitchen_snapshot_v1.json only).
    assertEquals("@mipmap/ic_launcher", appElement.getAttributeNS(androidNs, "icon"))
    assertEquals("@mipmap/ic_launcher_round", appElement.getAttributeNS(androidNs, "roundIcon"))
    assertEquals("true", appElement.getAttributeNS(androidNs, "allowBackup"))
    assertEquals("true", appElement.getAttributeNS(androidNs, "hasFragileUserData"))
    assertEquals("@xml/backup_rules", appElement.getAttributeNS(androidNs, "fullBackupContent"))
    assertEquals("@xml/data_extraction_rules", appElement.getAttributeNS(androidNs, "dataExtractionRules"))
    assertEquals("@xml/network_security_config", appElement.getAttributeNS(androidNs, "networkSecurityConfig"))

    // Confirm both mipmap-anydpi-v26 XML descriptors bind background, foreground, and monochrome layers.
    val mipmapPaths = listOf(
      "android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml",
      "android/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml",
    )
    for (mipmapRelPath in mipmapPaths) {
      val doc = parseSecureXml(resolveRepoFile(mipmapRelPath).readText())
      assertEquals("adaptive-icon", doc.documentElement.tagName)
      val bg = doc.getElementsByTagName("background").item(0) as Element
      val fg = doc.getElementsByTagName("foreground").item(0) as Element
      val mono = doc.getElementsByTagName("monochrome").item(0) as Element
      assertEquals("@drawable/ic_launcher_background", bg.getAttributeNS(androidNs, "drawable"))
      assertEquals("@drawable/ic_launcher_foreground", fg.getAttributeNS(androidNs, "drawable"))
      assertEquals("@drawable/ic_launcher_monochrome", mono.getAttributeNS(androidNs, "drawable"))
    }

    // Confirm all 3 vector drawables declare 108dp x 108dp bounds and 108x108 viewports.
    val backgroundText = resolveRepoFile("android/app/src/main/res/drawable/ic_launcher_background.xml").readText()
    val foregroundText = resolveRepoFile("android/app/src/main/res/drawable/ic_launcher_foreground.xml").readText()
    val monochromeText = resolveRepoFile("android/app/src/main/res/drawable/ic_launcher_monochrome.xml").readText()
    for (vectorText in listOf(backgroundText, foregroundText, monochromeText)) {
      val root = parseSecureXml(vectorText).documentElement
      assertEquals("vector", root.tagName)
      assertEquals("108dp", root.getAttributeNS(androidNs, "width"))
      assertEquals("108dp", root.getAttributeNS(androidNs, "height"))
      assertEquals("108", root.getAttributeNS(androidNs, "viewportWidth"))
      assertEquals("108", root.getAttributeNS(androidNs, "viewportHeight"))
    }

    // Verify warm Linen (#FAF7F2), Herbal Sage (#2E7D5A), and Terracotta (#C85A32) brand hexes.
    assertTrue(backgroundText.uppercase().contains("#FAF7F2"))
    assertTrue(foregroundText.uppercase().contains("#2E7D5A"))
    assertTrue(foregroundText.uppercase().contains("#C85A32"))
    assertTrue(monochromeText.uppercase().contains("#FFFFFFFF"))

    // Scan every #RRGGBB / #AARRGGBB color literal across all launcher XMLs to guarantee 0 cold blue/purple hues.
    val hexRegex = Regex("#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})\\b")
    val allColorTokens = listOf(backgroundText, foregroundText, monochromeText)
      .flatMap { xml -> hexRegex.findAll(xml).map { it.groupValues[1] }.toList() }
    assertTrue(allColorTokens.isNotEmpty())
    for (rawHex in allColorTokens) {
      val rgbHex = if (rawHex.length == 8) rawHex.substring(2) else rawHex
      val red = rgbHex.substring(0, 2).toInt(16)
      val green = rgbHex.substring(2, 4).toInt(16)
      val blue = rgbHex.substring(4, 6).toInt(16)
      val isColdBlueOrPurple = blue > red && blue > green
      assertFalse("Unexpected cold blue/purple color #$rawHex in launcher icon XML", isColdBlueOrPurple)
    }
  }

  // Parse XML strings with XXE protections enabled and Android namespace awareness turned on.
  private fun parseSecureXml(xmlContent: String): Document {
    val factory = DocumentBuilderFactory.newInstance().apply {
      isNamespaceAware = true
      setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
      setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }
    return factory.newDocumentBuilder().parse(InputSource(StringReader(xmlContent)))
  }

  // Resolve repository-relative paths regardless of whether JUnit runs from android/app, android/, or root.
  private fun resolveRepoFile(repoRelativePath: String): Path {
    val candidates = listOf(
      KotlinPath(repoRelativePath),
      KotlinPath("..").resolve(repoRelativePath),
      KotlinPath("../..").resolve(repoRelativePath),
    )
    return requireNotNull(candidates.firstOrNull { it.isRegularFile() }) {
      "Unable to resolve repository file: $repoRelativePath"
    }
  }

  // Hermetic no-op AiEdgePort fake keeping ViewModel toast tests free of JNI or socket side effects.
  private class SilentToastTestAiEdgePort : AiEdgePort {
    private val state = MutableStateFlow<AiRuntimeState>(AiRuntimeState.Uninitialized)
    override val runtimeState: StateFlow<AiRuntimeState> = state.asStateFlow()

    override suspend fun autoDiscoverAndInitialize(): AiRuntimeState = state.value

    override suspend fun importAndLoadModel(sourceUriString: String, displayName: String?): AiRuntimeState =
      state.value

    override suspend fun generateText(prompt: String): String = "{}"

    override suspend fun analyzeImageWithPrompt(preprocessedImagePath: String, prompt: String): String =
      throw VisionNotSupportedException("Stub port does not enable vision.")

    override fun close() = Unit
  }

  // Hermetic no-op VoiceInputPort fake avoiding Android SpeechRecognizer calls in JVM unit tests.
  private class SilentToastTestVoicePort : VoiceInputPort {
    private val state = MutableStateFlow<VoiceInputState>(VoiceInputState.Idle)
    override val voiceState: StateFlow<VoiceInputState> = state.asStateFlow()

    override fun startListening(languageTag: String): Boolean = false

    override fun stopListening() = Unit

    override fun release() = Unit
  }
}
