package com.familykitchen.board.domain

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

// Pure consumer export formatter for SavorAI Smart Grocery List and Smart Pantry Inventory.
// Strictly imports only kotlin.* and kotlinx.serialization.* (zero Android, Java I/O, or Locale).

// Cap sanitized household filename slugs at 32 characters before appending ".pdf".
private const val MAX_EXPORT_SLUG_LENGTH: Int = 32

// Default filename slug when a household display name has zero ASCII alphanumeric characters.
private const val FALLBACK_EXPORT_SLUG: String = "Family_Kitchen"

private data class SupermarketAisleOrder(val category: IngredientCategory, val title: String)

// Physical perimeter-to-center supermarket walk sequence matching SmartGroceryScreen 1:1.
private val EXPORT_SUPERMARKET_AISLES: List<SupermarketAisleOrder> = listOf(
  SupermarketAisleOrder(IngredientCategory.PRODUCE, "Aisle 1: Produce & Herbs"),
  SupermarketAisleOrder(IngredientCategory.DAIRY_EGGS, "Aisle 2: Dairy & Refrigerated"),
  SupermarketAisleOrder(IngredientCategory.PROTEIN, "Aisle 4: Meat & Seafood"),
  SupermarketAisleOrder(IngredientCategory.BAKERY_GRAINS, "Aisle 5: Bakery & Grains"),
  SupermarketAisleOrder(IngredientCategory.PANTRY_STAPLE, "Aisle 7: Pantry & Baking"),
)

/** Leading checkbox visual state rendered on exported PDF rows and plain-text clipboard lines. */
@Serializable
enum class ExportCheckboxState {
  // Informational or inventory row rendered with a subtle bullet marker instead of a checkbox.
  /** No interactive checkbox (used for Pantry inventory rows and covered-board notices). */
  NONE,

  // Active shopping item still waiting to be picked up in the market or grocery aisle.
  /** Empty square checkbox (`[ ]`) for items remaining to purchase. */
  UNCHECKED,

  // Completed shopping item already placed into the basket or checked off at home.
  /** Checked square checkbox (`[x]`) for items already picked up. */
  CHECKED,
}

/**
 * Compact key-value summary pill displayed in the top banner of an [ExportedKitchenDocument].
 *
 * @property label Short metric caption (for example, `"To Buy"` or `"Saved Score"`).
 * @property value Formatted metric value (for example, `"6"` or `"₱1,325"`).
 */
@Serializable
data class ExportMetricChip(
  val label: String,
  val value: String,
)

/**
 * Single formatted line item inside an [ExportSection] for PDF layout and clipboard export.
 *
 * @property primaryText Main ingredient or item display title.
 * @property trailingBadge Right-aligned quantity, package, urgency, or price summary.
 * @property subtitleText Optional meal attribution, rescue recipe hint, or storage note.
 * @property checkboxState Leading checkbox state (`NONE`, `UNCHECKED`, or `CHECKED`).
 * @property isUrgentHighlight `true` when the row represents <= 48h expiring stock needing rescue.
 */
@Serializable
data class ExportLineItem(
  val primaryText: String,
  val trailingBadge: String = "",
  val subtitleText: String? = null,
  val checkboxState: ExportCheckboxState = ExportCheckboxState.NONE,
  val isUrgentHighlight: Boolean = false,
)

/**
 * Titled group of [ExportLineItem] rows corresponding to a supermarket aisle or kitchen zone.
 *
 * @property sectionTitle Human-readable aisle, sort-mode, urgency, or storage-zone heading.
 * @property itemCount Count of items summarized under this section.
 * @property items Ordered list of formatted [ExportLineItem] records.
 */
@Serializable
data class ExportSection(
  val sectionTitle: String,
  val itemCount: Int,
  val items: List<ExportLineItem>,
)

/**
 * Complete, serializable SavorAI document ready for multi-page A4 PDF rendering or clipboard share.
 *
 * @property title Primary document headline (`"SavorAI Smart Grocery List"` or `"SavorAI Pantry Inventory"`).
 * @property subtitle Household name, local market region, and scope summary line.
 * @property suggestedFileName Sanitized `.pdf` filename safe for Android `CreateDocument` intents.
 * @property metrics Four-chip executive summary strip rendered below the document header.
 * @property sections Non-empty list of grouped [ExportSection] blocks.
 * @property footerSummary Concise totals and zero-waste savings summary rendered in the footer.
 * @property clipboardText Clean plain-text representation formatted for Notes, SMS, or chat apps.
 */
@Serializable
data class ExportedKitchenDocument(
  val title: String,
  val subtitle: String,
  val suggestedFileName: String,
  val metrics: List<ExportMetricChip>,
  val sections: List<ExportSection>,
  val footerSummary: String,
  val clipboardText: String,
)

/**
 * Converts an arbitrary household display name into a safe, pure-ASCII filename slug containing
 * only `[A-Za-z0-9]` tokens joined by single underscores (`<= 32` characters).
 *
 * Eliminates path separators (`/` and `\`), `..` traversal sequences, and ASCII control characters
 * without touching platform `Locale` APIs.
 *
 * @param raw Unsanitized household or kitchen title string.
 * @return Safe ASCII slug (`"Family_Kitchen"` fallback when no ASCII alphanumeric characters exist).
 */
fun sanitizeExportFileSlug(raw: String): String {
  // Scan character-by-character to extract contiguous ASCII alphanumeric tokens deterministically.
  val tokens = mutableListOf<String>()
  val current = StringBuilder()
  for (ch in raw) {
    val isAsciiAlnum = ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9'
    when {
      isAsciiAlnum -> current.append(ch)
      current.isNotEmpty() -> {
        tokens.add(current.toString())
        current.clear()
      }
    }
  }
  if (current.isNotEmpty()) tokens.add(current.toString())
  if (tokens.isEmpty()) return FALLBACK_EXPORT_SLUG

  // Bound total slug length to <= 32 characters and strip any trailing underscore after slicing.
  val joined = tokens.joinToString(separator = "_")
  val bounded = when {
    joined.length <= MAX_EXPORT_SLUG_LENGTH -> joined
    else -> joined.substring(0, MAX_EXPORT_SLUG_LENGTH).trimEnd('_')
  }
  return bounded.ifEmpty { FALLBACK_EXPORT_SLUG }
}

/**
 * Builds a complete [ExportedKitchenDocument] from the active Smart Grocery List state, honoring
 * aisle walk order (or recipe/urgency sort), checked-off partitioning, custom items, and household
 * currency formatting.
 *
 * @param groceryDiff Chronological 21-slot weekly reservation and whole-package grocery diff.
 * @param checkedGroceryItemIds Persisted set of checked catalog `ingredientId`s.
 * @param customGroceryItems User-added or voice-dictated custom shopping items.
 * @param sortMode Active [GrocerySortMode] (`AISLE_SORTED`, `BY_RECIPE`, or `URGENCY_FIRST`).
 * @param householdProfile Active [HouseholdProfile] providing household name, region, and currency.
 * @return Immutable [ExportedKitchenDocument] with structured PDF sections and WhatsApp-ready text.
 */
fun buildGroceryExportDocument(
  groceryDiff: WeeklyGroceryDiff,
  checkedGroceryItemIds: Set<String> = emptySet(),
  customGroceryItems: List<CustomGroceryItem> = emptyList(),
  sortMode: GrocerySortMode = GrocerySortMode.AISLE_SORTED,
  householdProfile: HouseholdProfile = HouseholdProfile(),
): ExportedKitchenDocument {
  // Union snapshot checked IDs with per-item checked flags so UI and export counts match 1:1.
  val effectiveCheckedIds = checkedGroceryItemIds +
    groceryDiff.groceryItems.filter { it.isChecked }.map { it.ingredientId }

  // Partition catalog and custom shopping rows into active unchecked vs completed checked buckets.
  val uncheckedCatalog = orderExportGroceryItems(
    items = groceryDiff.groceryItems.filterNot { it.ingredientId in effectiveCheckedIds },
    sortMode = sortMode,
  )
  val checkedCatalog = groceryDiff.groceryItems.filter { it.ingredientId in effectiveCheckedIds }
  val uncheckedCustom = customGroceryItems.filterNot { it.isChecked }
  val checkedCustom = customGroceryItems.filter { it.isChecked }

  // Compute remaining count, checked count, estimated spend, and pantry deduplication savings.
  val remainingCount = uncheckedCatalog.size + uncheckedCustom.size
  val checkedCount = checkedCatalog.size + checkedCustom.size
  val remainingCostText = householdProfile.formatMoney(uncheckedCatalog.sumOf { it.estimatedCostUsd })
  val pantrySavedText = householdProfile.formatMoney(groceryDiff.deduplicationSavingsUsd)
  val skippedStaples = groceryDiff.skippedStockedStapleCount

  // Assemble the 4-chip header summary strip rendered across the top of Page 1.
  val metrics = listOf(
    ExportMetricChip(label = "To Buy", value = remainingCount.toString()),
    ExportMetricChip(label = "Estimated Cost", value = remainingCostText),
    ExportMetricChip(label = "Pantry Saved", value = pantrySavedText),
    ExportMetricChip(label = "Checked Off", value = checkedCount.toString()),
  )

  val sections = mutableListOf<ExportSection>()

  // Group unchecked rows along the physical supermarket perimeter or flat recipe/urgency order.
  when (sortMode) {
    GrocerySortMode.AISLE_SORTED -> {
      for (aisle in EXPORT_SUPERMARKET_AISLES) {
        val aisleCatalog = uncheckedCatalog.filter { it.category == aisle.category }
          .map { formatCatalogGroceryLine(it, householdProfile, ExportCheckboxState.UNCHECKED) }
        val aisleCustom = uncheckedCustom.filter { it.category == aisle.category }
          .map { formatCustomGroceryLine(it, ExportCheckboxState.UNCHECKED) }
        val combined = aisleCatalog + aisleCustom
        // Omit empty supermarket aisles cleanly so shoppers scan only aisles with needed items.
        if (combined.isNotEmpty()) {
          sections.add(ExportSection(aisle.title, combined.size, combined))
        }
      }
    }

    GrocerySortMode.BY_RECIPE, GrocerySortMode.URGENCY_FIRST -> {
      val combined = uncheckedCatalog.map {
        formatCatalogGroceryLine(it, householdProfile, ExportCheckboxState.UNCHECKED)
      } + uncheckedCustom.map { formatCustomGroceryLine(it, ExportCheckboxState.UNCHECKED) }
      if (combined.isNotEmpty()) {
        sections.add(ExportSection(sortMode.displayLabel, combined.size, combined))
      }
    }
  }

  // Append completed items in a dedicated Checked Off tray so shoppers see full trip progress.
  if (checkedCount > 0) {
    val checkedLines = checkedCatalog.map {
      formatCatalogGroceryLine(it, householdProfile, ExportCheckboxState.CHECKED)
    } + checkedCustom.map { formatCustomGroceryLine(it, ExportCheckboxState.CHECKED) }
    sections.add(ExportSection("Checked Off", checkedLines.size, checkedLines))
  }

  // Emit a welcoming celebration section when home stock already covers every planned weekly meal.
  if (sections.isEmpty()) {
    val coveredItem = ExportLineItem(
      primaryText = "Zero grocery packages needed right now",
      trailingBadge = "100% stocked",
      subtitleText = "Current household Pantry stock covers all planned weekly slots.",
      checkboxState = ExportCheckboxState.NONE,
    )
    sections.add(ExportSection("All Planned Meals Covered", 1, listOf(coveredItem)))
  }

  // Format clean household subtitle, safe PDF filename, footer summary, and chat clipboard text.
  val cleanHousehold = householdProfile.householdName.trim().ifEmpty { "Our Family Kitchen" }
  val cleanRegion = householdProfile.marketRegionLabel.trim()
  val mealLabel =
    if (groceryDiff.plannedMealCount == 1) "1 planned meal" else "${groceryDiff.plannedMealCount} planned meals"
  val subtitle = listOfNotNull(cleanHousehold, cleanRegion.takeIf { it.isNotEmpty() }, mealLabel)
    .joinToString(" · ")
  val slug = sanitizeExportFileSlug(householdProfile.householdName)
  val footerSummary =
    "To buy: $remainingCount ($remainingCostText) · Pantry covered $skippedStaples stocked items (saved $pantrySavedText)"
  val clipboardText = renderGroceryClipboardText(
    cleanHousehold, cleanRegion, remainingCount, remainingCostText, pantrySavedText, skippedStaples, sections,
  )

  return ExportedKitchenDocument(
    title = "SavorAI Smart Grocery List",
    subtitle = subtitle,
    suggestedFileName = "SavorAI_Grocery_List_$slug.pdf",
    metrics = metrics,
    sections = sections,
    footerSummary = footerSummary,
    clipboardText = clipboardText,
  )
}

/**
 * Builds a complete [ExportedKitchenDocument] from the household's two-tier Smart Pantry ledger and
 * [PantryExpiryDashboard], highlighting `<= 48h` expiring items ahead of storage-zone inventories.
 *
 * @param catalog Household ingredient catalog metadata.
 * @param pantryEntries Current two-tier household pantry ledger stock records.
 * @param dashboard Evaluated [PantryExpiryDashboard] containing urgent cards and waste score.
 * @param householdProfile Active [HouseholdProfile] providing household name, region, and currency.
 * @return Immutable [ExportedKitchenDocument] ready for PDF printing or family chat sharing.
 */
fun buildPantryExportDocument(
  catalog: List<IngredientCatalogItem>,
  pantryEntries: List<PantryLedgerEntry>,
  dashboard: PantryExpiryDashboard,
  householdProfile: HouseholdProfile = HouseholdProfile(),
): ExportedKitchenDocument {
  // Index catalog metadata once in O(N) so per-zone item lookups remain O(1).
  val catalogById = indexCatalogById(catalog)
  val atRiskValueText = householdProfile.formatMoney(dashboard.atRiskValueUsd)

  // Build the 4-chip Pantry pulse metrics matching SmartPantryScreen's top telemetry bar.
  val metrics = listOf(
    ExportMetricChip(label = "Stocked", value = dashboard.totalItems.toString()),
    ExportMetricChip(label = "Expiring <= 48h", value = dashboard.expiringItems.size.toString()),
    ExportMetricChip(label = "At-Risk Value", value = atRiskValueText),
    ExportMetricChip(label = "Saved Score", value = "${dashboard.wasteScorePercent}%"),
  )

  val sections = mutableListOf<ExportSection>()

  // Surface urgent <= 48h expiring items at the very top with rescue meal or prep guidance.
  if (dashboard.expiringItems.isNotEmpty()) {
    val urgentRows = dashboard.expiringItems.map { card ->
      val humanizedQty = formatExportStockQuantity(card.entry, card.catalogItem.baseUnit)
      val riskText = householdProfile.formatMoney(card.atRiskValueUsd)
      val topRescue = card.matchingRescueRecipes.firstOrNull()?.title?.trim()?.takeIf { it.isNotEmpty() }
      val actionHint = if (topRescue != null) "Rescue meal: $topRescue" else card.optimalPrepHint
      ExportLineItem(
        primaryText = card.catalogItem.displayName,
        trailingBadge = "$humanizedQty · ${card.urgencyLabel} ($riskText)",
        subtitleText = "${card.effectiveStorageLocation.displayLabel} · $actionHint",
        checkboxState = ExportCheckboxState.NONE,
        isUrgentHighlight = true,
      )
    }
    sections.add(ExportSection("Action Required — Use Within 48h", urgentRows.size, urgentRows))
  }

  // Fall back to partitioning pantryEntries directly if a caller passed empty dashboard zone lists.
  val hasDashboardZones = dashboard.fridgeItems.isNotEmpty() ||
    dashboard.dryPantryItems.isNotEmpty() ||
    dashboard.freezerItems.isNotEmpty()
  val (fridgeRaw, dryRaw, freezerRaw) = when {
    hasDashboardZones -> Triple(dashboard.fridgeItems, dashboard.dryPantryItems, dashboard.freezerItems)
    else -> partitionEntriesByStorageZone(pantryEntries, catalogById)
  }

  // Emit stocked items for each non-empty storage zone in Fridge -> Dry Pantry -> Freezer order.
  appendZoneSectionIfStocked(sections, StorageLocation.FRIDGE.displayLabel, fridgeRaw, catalogById)
  appendZoneSectionIfStocked(sections, StorageLocation.PANTRY.displayLabel, dryRaw, catalogById)
  appendZoneSectionIfStocked(sections, StorageLocation.FREEZER.displayLabel, freezerRaw, catalogById)

  // Guarantee non-blank exports even right after onboarding an Empty Kitchen with 0 stocked items.
  if (sections.isEmpty()) {
    val emptyPlaceholder = ExportLineItem(
      primaryText = "No stocked ingredients in your kitchen ledger yet",
      trailingBadge = "Out of stock",
      subtitleText = "Scan a receipt, snap a fridge photo, or add local market staples in SavorAI.",
      checkboxState = ExportCheckboxState.NONE,
    )
    sections.add(ExportSection("Empty Pantry", 1, listOf(emptyPlaceholder)))
  }

  // Compose subtitle, safe PDF filename, zero-waste footer summary, and scannable clipboard text.
  val cleanHousehold = householdProfile.householdName.trim().ifEmpty { "Our Family Kitchen" }
  val cleanRegion = householdProfile.marketRegionLabel.trim()
  val stockedLabel = if (dashboard.totalItems == 1) "1 stocked item" else "${dashboard.totalItems} stocked items"
  val subtitle = listOfNotNull(cleanHousehold, cleanRegion.takeIf { it.isNotEmpty() }, stockedLabel)
    .joinToString(" · ")
  val slug = sanitizeExportFileSlug(householdProfile.householdName)
  val footerSummary =
    "Stocked: ${dashboard.totalItems} · Expiring <= 48h: ${dashboard.expiringItems.size} ($atRiskValueText) · Zero-Waste Saved Score: ${dashboard.wasteScorePercent}%"
  val clipboardText = renderPantryClipboardText(cleanHousehold, cleanRegion, dashboard, atRiskValueText, sections)

  return ExportedKitchenDocument(
    title = "SavorAI Pantry Inventory",
    subtitle = subtitle,
    suggestedFileName = "SavorAI_Pantry_Inventory_$slug.pdf",
    metrics = metrics,
    sections = sections,
    footerSummary = footerSummary,
    clipboardText = clipboardText,
  )
}

// Orders unchecked GroceryListItems matching SmartGroceryScreen's recipe and shortage comparators.
private fun orderExportGroceryItems(
  items: List<GroceryListItem>,
  sortMode: GrocerySortMode,
): List<GroceryListItem> =
  when (sortMode) {
    GrocerySortMode.AISLE_SORTED -> items
    GrocerySortMode.BY_RECIPE -> items.sortedWith(
      compareBy<GroceryListItem> { it.mealUsageLabels.firstOrNull().orEmpty() }.thenBy { it.displayName },
    )
    GrocerySortMode.URGENCY_FIRST -> items.sortedWith(
      compareByDescending<GroceryListItem> {
        it.netDeficit > 0.0 && it.tier == IngredientTier.TIER_1_QUANTITATIVE
      }
        .thenByDescending { it.netDeficit }
        .thenByDescending { it.estimatedCostUsd }
        .thenBy { it.displayName },
    )
  }

// Formats a catalog GroceryListItem with whole-package descriptor, currency cost, and meal usage.
private fun formatCatalogGroceryLine(
  item: GroceryListItem,
  householdProfile: HouseholdProfile,
  checkboxState: ExportCheckboxState,
): ExportLineItem {
  val pkgText = item.packageDescriptor.trim().ifEmpty {
    val noun = if (item.packagesToBuy == 1) "pack" else "packs"
    "${item.packagesToBuy} $noun (${formatHumanizedUnitQuantity(item.restockQuantity, item.baseUnit)})"
  }
  // Strip redundant leading "for " prefixes before joining planned meal attributions under "For:".
  val cleanedUsages = item.mealUsageLabels
    .map { label -> label.trim().removePrefix("for ").removePrefix("For ").trim() }
    .filter { it.isNotEmpty() }
  val subtitle = when {
    cleanedUsages.isEmpty() && item.isManuallyQueued -> "For: Household Kitchen Queue"
    cleanedUsages.isEmpty() -> null
    cleanedUsages.size <= 2 -> "For: ${cleanedUsages.joinToString("; ")}"
    else -> "For: ${cleanedUsages.take(2).joinToString("; ")} (+${cleanedUsages.size - 2} more)"
  }
  return ExportLineItem(
    primaryText = item.displayName,
    trailingBadge = "$pkgText · ${householdProfile.formatMoney(item.estimatedCostUsd)}",
    subtitleText = subtitle,
    checkboxState = checkboxState,
  )
}

// Formats an ad-hoc text or voice-dictated CustomGroceryItem with a clear "Custom item" badge.
private fun formatCustomGroceryLine(
  custom: CustomGroceryItem,
  checkboxState: ExportCheckboxState,
): ExportLineItem = ExportLineItem(
  primaryText = custom.displayName,
  trailingBadge = "Custom item",
  subtitleText = custom.quantityNote.trim().takeIf { it.isNotEmpty() },
  checkboxState = checkboxState,
)

// Filters a storage zone to stocked items, sorting soonest-expiring items ahead of shelf-stable stock.
private fun appendZoneSectionIfStocked(
  sections: MutableList<ExportSection>,
  zoneTitle: String,
  entries: List<PantryLedgerEntry>,
  catalogById: Map<String, IngredientCatalogItem>,
) {
  val stockedEntries = entries
    .filter(::isExportEntryStocked)
    .sortedWith(
      compareBy<PantryLedgerEntry> { it.expiresInDays ?: Int.MAX_VALUE }
        .thenBy { entry -> catalogById[entry.ingredientId]?.displayName ?: entry.ingredientId },
    )
  if (stockedEntries.isEmpty()) return

  val rows = stockedEntries.map { entry ->
    val item = catalogById[entry.ingredientId]
    val qtyText = formatExportStockQuantity(entry, item?.baseUnit ?: BaseUnit.GRAM)
    val notes = listOfNotNull(
      entry.storageSubLocation?.trim()?.takeIf { it.isNotEmpty() },
      entry.packageNote?.trim()?.takeIf { it.isNotEmpty() },
    ).joinToString(" · ").takeIf { it.isNotEmpty() }
    ExportLineItem(
      primaryText = item?.displayName ?: entry.ingredientId,
      trailingBadge = "$qtyText · ${entry.urgencyBadgeLabel}",
      subtitleText = notes,
      checkboxState = ExportCheckboxState.NONE,
      isUrgentHighlight = entry.effectiveExpiryStatus != ExpiryStatus.FRESH,
    )
  }
  sections.add(ExportSection(zoneTitle, rows.size, rows))
}

// Partitions raw ledger entries across Fridge, Dry Pantry, and Freezer when dashboard zones are empty.
private fun partitionEntriesByStorageZone(
  entries: List<PantryLedgerEntry>,
  catalogById: Map<String, IngredientCatalogItem>,
): Triple<List<PantryLedgerEntry>, List<PantryLedgerEntry>, List<PantryLedgerEntry>> {
  val fridge = mutableListOf<PantryLedgerEntry>()
  val dryPantry = mutableListOf<PantryLedgerEntry>()
  val freezer = mutableListOf<PantryLedgerEntry>()
  for (entry in entries) {
    val zone = entry.storageLocation ?: catalogById[entry.ingredientId]?.defaultStorageLocation ?: StorageLocation.PANTRY
    when (zone) {
      StorageLocation.FRIDGE -> fridge.add(entry)
      StorageLocation.PANTRY -> dryPantry.add(entry)
      StorageLocation.FREEZER -> freezer.add(entry)
    }
  }
  return Triple(fridge, dryPantry, freezer)
}

// Returns true only when a Tier-1 item has positive quantity or a Tier-2 staple is PLENTY/LOW.
private fun isExportEntryStocked(entry: PantryLedgerEntry): Boolean =
  when (entry.tier) {
    IngredientTier.TIER_2_STAPLE -> !entry.isOutOfStock && entry.stapleStatus != StapleStatus.OUT
    IngredientTier.TIER_1_QUANTITATIVE -> !entry.isOutOfStock && entry.currentQuantity > 0.0
  }

// Formats Tier-1 quantities with humanized g/kg/ml/L/count units and Tier-2 staples as Plenty/Low.
private fun formatExportStockQuantity(entry: PantryLedgerEntry, unit: BaseUnit): String =
  when (entry.tier) {
    IngredientTier.TIER_2_STAPLE -> when {
      entry.isOutOfStock || entry.stapleStatus == StapleStatus.OUT -> "Out of stock"
      entry.stapleStatus == StapleStatus.LOW -> "Low"
      else -> "Plenty"
    }
    IngredientTier.TIER_1_QUANTITATIVE -> when {
      entry.isOutOfStock || entry.currentQuantity <= 0.0 -> "Out of stock"
      else -> formatHumanizedUnitQuantity(entry.currentQuantity, unit)
    }
  }

// Scales >= 1000g to kg and >= 1000ml to L so bulk grains and milk read naturally on exports.
private fun formatHumanizedUnitQuantity(quantity: Double, unit: BaseUnit): String =
  when {
    unit == BaseUnit.GRAM && quantity >= 1000.0 -> "${formatCompactExportDecimal(quantity / 1000.0)} kg"
    unit == BaseUnit.MILLILITER && quantity >= 1000.0 -> "${formatCompactExportDecimal(quantity / 1000.0)} L"
    else -> "${formatCompactExportDecimal(quantity)} ${unit.wireCode}"
  }

// Formats decimal quantities without ".0" noise on whole numbers using pure Kotlin rounding.
private fun formatCompactExportDecimal(value: Double): String {
  val safeValue = if (value.isFinite() && value >= 0.0) value else 0.0
  val nearestInt = safeValue.roundToInt()
  return when {
    abs(safeValue - nearestInt.toDouble()) < 0.05 -> nearestInt.toString()
    else -> ((safeValue * 10.0).roundToInt() / 10.0).toString()
  }
}

// Renders WhatsApp/Notes-friendly plain text with [ ] and [x] checkboxes for grocery sharing.
private fun renderGroceryClipboardText(
  householdName: String,
  regionLabel: String,
  remainingCount: Int,
  remainingCostText: String,
  pantrySavedText: String,
  skippedStaples: Int,
  sections: List<ExportSection>,
): String = buildString {
  appendLine("SavorAI Smart Grocery List — $householdName")
  if (regionLabel.isNotEmpty()) appendLine("Market: $regionLabel")
  appendLine("To Buy: $remainingCount ($remainingCostText) · Pantry Saved: $pantrySavedText ($skippedStaples stocked items skipped)")
  appendSectionsToClipboard(this, sections)
}.trimEnd()

// Renders scannable plain-text Pantry inventory with urgent "!" markers for family chats.
private fun renderPantryClipboardText(
  householdName: String,
  regionLabel: String,
  dashboard: PantryExpiryDashboard,
  atRiskValueText: String,
  sections: List<ExportSection>,
): String = buildString {
  appendLine("SavorAI Pantry Inventory — $householdName")
  if (regionLabel.isNotEmpty()) appendLine("Market: $regionLabel")
  appendLine("Stocked: ${dashboard.totalItems} · Expiring <= 48h: ${dashboard.expiringItems.size} ($atRiskValueText) · Saved Score: ${dashboard.wasteScorePercent}%")
  appendSectionsToClipboard(this, sections)
}.trimEnd()

// Shared section-to-plaintext formatter emitting singular/plural item counts and indented subtitles.
private fun appendSectionsToClipboard(builder: StringBuilder, sections: List<ExportSection>) {
  for (section in sections) {
    builder.appendLine()
    val countNoun = if (section.itemCount == 1) "1 item" else "${section.itemCount} items"
    builder.appendLine("${section.sectionTitle} ($countNoun)")
    for (item in section.items) {
      val prefix = when (item.checkboxState) {
        ExportCheckboxState.UNCHECKED -> "[ ] "
        ExportCheckboxState.CHECKED -> "[x] "
        ExportCheckboxState.NONE -> if (item.isUrgentHighlight) "! " else "• "
      }
      val badgeSuffix = if (item.trailingBadge.isNotEmpty()) " — ${item.trailingBadge}" else ""
      builder.appendLine("$prefix${item.primaryText}$badgeSuffix")
      if (!item.subtitleText.isNullOrBlank()) builder.appendLine("    ${item.subtitleText}")
    }
  }
}
