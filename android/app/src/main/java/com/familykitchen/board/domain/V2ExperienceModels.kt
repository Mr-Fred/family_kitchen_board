package com.familykitchen.board.domain

import kotlinx.serialization.Serializable

// Pure SavorAI v2.0 experience-logic models covering flexible planning cadences, honest skip/leftover
// reasons, Palengke vs Supermarket venues, proportional shelf audits, unpack directives, and post-cook feedback.

/**
 * Household weekly board planning density chosen when starting or refreshing a week.
 *
 * All user-facing labels remain strictly free of slashes and parentheses for calm UI chip rendering.
 *
 * @property displayTitle Concise card/chip title rendered in the Weekly Refresh sheet.
 * @property subtitle Supporting description explaining which meal slots are populated.
 */
@Serializable
enum class PlanCadence(
  val displayTitle: String,
  val subtitle: String,
) {
  // Populates all 7 breakfasts, 7 lunches, and 7 dinners (21 total weekly slots).
  /** Complete 21-slot weekly schedule covering morning, midday, and evening meals. */
  FULL_WEEK("Full Week", "Breakfast, lunch, and dinner"),

  // Populates Monday..Friday breakfasts (5) plus Monday..Sunday dinners (7) = 12 slots.
  /** School-week morning breakfasts paired with seven family dinners and open lunches. */
  BREAKFAST_AND_DINNER("Breakfast and Dinner", "Weekday mornings plus family dinners"),

  // Populates Monday..Sunday dinners (7) while keeping mornings and lunches flexible.
  /** Lightweight evening-anchor cadence drafting only the seven family dinners. */
  DINNERS_ONLY("Dinners Only", "Seven evening meals with open days"),

  // Leaves all 21 slots open so households starting from an empty kitchen build at their own pace.
  /** Calm empty board with zero pre-filled meals, ideal for empty-kitchen starts. */
  BLANK_WEEK("Blank Week", "Empty board ready to fill at your pace");

  /**
   * Returns `true` when this [PlanCadence] automatically populates `(dayIndex, slotType)`.
   *
   * @param dayIndex 0-based weekday index (`0 = Monday` .. `6 = Sunday`).
   * @param slotType Candidate meal slot category (`BREAKFAST`, `LUNCH`, or `DINNER`).
   * @return `true` when [slotType] on [dayIndex] belongs to this cadence.
   */
  fun includesSlot(dayIndex: Int, slotType: MealSlotType): Boolean {
    if (dayIndex !in 0..6) return false
    return when (this) {
      FULL_WEEK -> true
      BREAKFAST_AND_DINNER ->
        slotType == MealSlotType.DINNER || (slotType == MealSlotType.BREAKFAST && dayIndex in 0..4)
      DINNERS_ONLY -> slotType == MealSlotType.DINNER
      BLANK_WEEK -> false
    }
  }
}

/**
 * Explicit reason recorded when a planned meal slot transitions to [MealSlotStatus.COMPLETED_SKIPPED]
 * (`EC-12`), distinguishing home leftovers from dining out or skipping.
 *
 * @property badgeLabel Compact chip text displayed on completed-skipped meal cards.
 */
@Serializable
enum class SlotSkipReason(val badgeLabel: String) {
  // Releases reserved BOM while signaling the family ate previously cooked home food.
  /** Household ate batch-cooked leftovers instead of cooking the scheduled recipe. */
  ATE_LEFTOVERS("Ate Leftovers"),

  // Releases reserved BOM and omits planned recipe macros from home cooking totals.
  /** Household dined out or ordered takeaway for this meal slot. */
  EATING_OUT("Eating Out"),

  // Generic skip releasing reserved BOM back to downstream planned slots.
  /** Slot was skipped without cooking. */
  SKIPPED("Skipped"),
}

/**
 * Local shopping venue classification separating wet-market (*Palengke*) fresh stalls from
 * packaged supermarket aisles on the Smart Grocery List.
 *
 * @property chipLabel Clean filter pill title free of slashes and parentheses.
 */
@Serializable
enum class MarketVenue(val chipLabel: String) {
  // Antipolo Palengke / Marikina Public Market fresh produce, seafood, poultry, farm eggs, & gata.
  /** Traditional open-air wet market (*Palengke*) stall sourcing. */
  WET_MARKET("Palengke"),

  // SM Marikina / Robinsons Antipolo chilled dairy, bakery grains, oils, dry goods, & deli packs.
  /** Packaged grocery and supermarket aisle sourcing. */
  SUPERMARKET("Supermarket"),
}

/**
 * Resolves the default [MarketVenue] (`WET_MARKET` vs `SUPERMARKET`) for a catalog [item], honoring
 * any explicit [IngredientCatalogItem.preferredVenue] override first.
 *
 * @param item Catalog ingredient metadata to classify.
 * @return Resolved [MarketVenue] for Smart Grocery List venue filtering.
 */
fun resolveMarketVenue(item: IngredientCatalogItem): MarketVenue {
  // Honor user-configured venue preferences before applying category and Palengke heuristics.
  val explicit = item.preferredVenue
  if (explicit != null) return explicit

  val normalizedKey = "${item.ingredientId.lowercase()} ${item.displayName.lowercase()}"
  return when (item.category) {
    // All fresh vegetables, fruits, leafy greens, tubers, citrus, and aromatics map to Palengke.
    IngredientCategory.PRODUCE -> MarketVenue.WET_MARKET

    // Fresh fish, shrimp, poultry, and tokwa map to Palengke; canned/deli/dry legumes map to Supermarket.
    IngredientCategory.PROTEIN -> when {
      v2ExpIsSupermarketPackagedProtein(item, normalizedKey) -> MarketVenue.SUPERMARKET
      else -> MarketVenue.WET_MARKET
    }

    // Farm eggs come from Palengke egg stalls while milk, yogurt, butter, and cheese come from Supermarket.
    IngredientCategory.DAIRY_EGGS -> when {
      v2ExpIsFarmEggItem(item, normalizedKey) -> MarketVenue.WET_MARKET
      else -> MarketVenue.SUPERMARKET
    }

    // Freshly pressed gata (coconut milk) and wet herbs map to Palengke; dry staples/grains to Supermarket.
    IngredientCategory.PANTRY_STAPLE -> when {
      v2ExpIsWetMarketFreshStaple(item, normalizedKey) -> MarketVenue.WET_MARKET
      else -> MarketVenue.SUPERMARKET
    }

    IngredientCategory.BAKERY_GRAINS -> MarketVenue.SUPERMARKET
  }
}

/**
 * Quick 1-tap proportional shelf-audit stock presets avoiding repetitive `±` stepper taps.
 *
 * @property chipLabel Clean pill label rendered inside Smart Pantry Shelf Audit rows.
 * @property fractionOfPackage Multiplier in `0.0..1.0` applied to `defaultPackageQuantity`.
 */
@Serializable
enum class ProportionalStockLevel(
  val chipLabel: String,
  val fractionOfPackage: Double,
) {
  /** Marks the ingredient completely out of stock (`0%` of a standard package). */
  EMPTY("Empty", 0.0),

  /** Sets stock to roughly one quarter (`25%`) of a standard package (or Tier-2 `LOW`). */
  LOW("Low", 0.25),

  /** Sets stock to half (`50%`) of a standard package (or Tier-2 `PLENTY`). */
  HALF("Half", 0.5),

  /** Restores stock to one full standard package (`100%`, or Tier-2 `PLENTY`). */
  FULL("Full", 1.0),
}

/**
 * Transparent summary of a planned weekly ingredient omitted from the Smart Grocery List because
 * existing Pantry stock covered `100%` of scheduled demand.
 *
 * Surfaced inside the expandable `Pantry Adjusted` banner so shoppers can double-check milk, eggs,
 * or rice before leaving home if family members snacked off-plan.
 *
 * @property ingredientId Covered catalog `ingredientId`.
 * @property displayName Human-readable ingredient title.
 * @property currentQuantityLabel Humanized current home stock badge (for example, `"2 L"` or `"Plenty"`).
 * @property requiredQuantity Total planned weekly quantity across `PLANNED` non-leftover slots.
 * @property baseUnit Canonical measurement unit for [requiredQuantity].
 */
@Serializable
data class SkippedPantryItemSummary(
  val ingredientId: String,
  val displayName: String,
  val currentQuantityLabel: String,
  val requiredQuantity: Double,
  val baseUnit: BaseUnit,
)

/**
 * Per-item unpack confirmation directive specifying destination storage zone and optional custom
 * shelf-life override when checking groceries into Smart Pantry.
 *
 * @property ingredientId Target catalog `ingredientId` being unpacked.
 * @property restockQuantity Canonical [BaseUnit] quantity added to the pantry ledger.
 * @property targetLocation Destination kitchen zone (`FRIDGE`, `PANTRY`, or `FREEZER`).
 * @property overrideShelfLifeDays Optional explicit freshness countdown override in days.
 */
@Serializable
data class UnpackItemDirective(
  val ingredientId: String,
  val restockQuantity: Double,
  val targetLocation: StorageLocation,
  val overrideShelfLifeDays: Int? = null,
)

/**
 * Lightweight 1-tap post-cook family feedback chips distilled into `HouseholdElephantMemory` after
 * finishing a recipe in Step-by-Step Cook Mode.
 *
 * @property chipLabel Clean chip label free of slashes and parentheses.
 * @property memoryNote Durable household semantic note recorded for future Sous-Chef recommendations.
 */
@Serializable
enum class PostCookReactionTag(
  val chipLabel: String,
  val memoryNote: String,
) {
  /** Signals high kid and family approval so future weekly drafts favor this dish. */
  KIDS_LOVED("Kids Loved", "Family and kids loved this dish"),

  /** Records a gentle-heat note for toddler and young-child portions. */
  ADJUST_SPICE("Adjust Spice", "Keep spice mild for youngest child"),

  /** Marks the recipe as a reliable low-effort weeknight staple. */
  QUICK_WIN("Quick Win", "Fast effortless weeknight win"),
}

/**
 * Summary payload surfaced on the Post-Cook Completion sheet when finishing a meal, enabling 1-tap
 * scheduling of extra batch-cooked portions into tomorrow's `0`-BOM Leftover Lunch.
 *
 * @property recipeId Completed [Recipe.recipeId].
 * @property recipeTitle Display title of the cooked dish.
 * @property cookedSlotId Optional board `slotId` marked `COOKED`.
 * @property cookedDayIndex Weekday index (`0..6`) on which the meal was cooked.
 * @property cookedServings Total servings prepared during this cook session.
 * @property householdServings Baseline servings eaten at the current meal.
 * @property extraLeftoverServings Remaining portions (`>= 0`) available for tomorrow's lunch.
 */
@Serializable
data class PostCookCompletionSummary(
  val recipeId: String,
  val recipeTitle: String,
  val cookedSlotId: String? = null,
  val cookedDayIndex: Int = 0,
  val cookedServings: Int = 4,
  val householdServings: Int = 4,
  val extraLeftoverServings: Int = 0,
)

// Identify shelf-stable canned proteins, dry legumes, or sliced deli meats sold at supermarkets.
private fun v2ExpIsSupermarketPackagedProtein(
  item: IngredientCatalogItem,
  normalizedKey: String,
): Boolean {
  if (item.defaultStorageLocation == StorageLocation.PANTRY) {
    return true
  }
  return normalizedKey.contains("turkey_ham") ||
    normalizedKey.contains("turkey_breast") ||
    normalizedKey.contains("deli") ||
    normalizedKey.contains("canned") ||
    normalizedKey.contains("tuna")
}

// Match farm eggs to Palengke egg stalls while keeping milk, yogurt, butter, and cheese in Supermarket.
private fun v2ExpIsFarmEggItem(item: IngredientCatalogItem, normalizedKey: String): Boolean =
  item.ingredientId == "ing_egg" || normalizedKey.contains("egg")

// Route fresh chilled gata (pressed coconut milk) and fresh wet-market herbs to Palengke stalls.
private fun v2ExpIsWetMarketFreshStaple(item: IngredientCatalogItem, normalizedKey: String): Boolean =
  item.ingredientId == "ing_gata" ||
    (item.tier == IngredientTier.TIER_1_QUANTITATIVE && item.defaultStorageLocation == StorageLocation.FRIDGE &&
      (normalizedKey.contains("gata") || normalizedKey.contains("coconut milk") || normalizedKey.contains("herb")))
