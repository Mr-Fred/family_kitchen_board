package com.familykitchen.board.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Pure immutable domain contracts matching data/seed_catalog.json 1:1 via kotlinx.serialization.
// Zero Android, Compose, Room, DataStore, LiteRT, MediaPipe, or java.io imports permitted here.

/** Default deterministic ISO-8601 timestamp used when pure domain callers omit wall-clock time. */
const val DEFAULT_TIMESTAMP_ISO: String = "2026-10-25T07:00:00.000Z"

/**
 * Canonical quantitative measurement units for Tier-1 pantry ingredients (`count`, `g`, `ml`).
 *
 * @property wireCode Lowercase unit symbol persisted in JSON and rendered on pantry badges.
 */
@Serializable
enum class BaseUnit(val wireCode: String) {
  // Serialized as lowercase "count", "g", "ml" to deserialize seed_catalog.json verbatim.
  /** Discrete countable items such as farm eggs, baguettes, avocados, or plantains. */
  @SerialName("count") COUNT("count"),

  /** Weight in grams for produce, proteins, cheeses, grains, and fruit toppings. */
  @SerialName("g") GRAM("g"),

  /** Volume in milliliters for milk, honey, and liquid cooking oils. */
  @SerialName("ml") MILLILITER("ml"),
}

/**
 * Grocery store aisle and kitchen storage categories used to group Smart Grocery List items.
 *
 * @property displayLabel Human-readable aisle heading displayed in Pantry and Grocery tabs.
 */
@Serializable
enum class IngredientCategory(val displayLabel: String) {
  /** Fresh fruits, leafy greens, tubers, and aromatic vegetables. */
  PRODUCE("Fresh Produce"),

  /** Poultry, fish, seafood, tofu, legumes, and melon seeds. */
  PROTEIN("Meat, Fish & Beans"),

  /** Eggs, milk, yogurt, butter, and cheeses. */
  DAIRY_EGGS("Dairy & Eggs"),

  /** Artisan baguettes, rolled oats, rice, pasta, wraps, cornmeal, and gari. */
  BAKERY_GRAINS("Bakery & Grains"),

  /** Cooking oils, spices, honey, nut/seed spreads, and dry pantry staples. */
  PANTRY_STAPLE("Pantry Staples & Oils"),
}

/** Two-tier closed-loop household pantry tracking classification. */
@Serializable
enum class IngredientTier {
  // Tier-1 tracks quantitative stock with chronological reservations and floor-at-zero clamping.
  /** Quantitative perishables and countables reserved chronologically and clamped at zero. */
  TIER_1_QUANTITATIVE,

  // Tier-2 tracks PLENTY/LOW/OUT status with zero gram-by-gram subtraction on meal completion.
  /** Tri-state pantry staples and spices (`PLENTY | LOW | OUT`) never auto-deducted gram-by-gram. */
  TIER_2_STAPLE,
}

/** Tri-state stock level for [IngredientTier.TIER_2_STAPLE] oils, spices, and aromatics. */
@Serializable
enum class StapleStatus {
  // Sufficient supply on the shelf; omitted from Smart Grocery List.
  /** Sufficient household supply on hand; not queued on the Smart Grocery List. */
  PLENTY,

  // Queues 1 package onto Smart Grocery List while still permitting EC-13 instant swaps.
  /** Running low; queues 1 default package onto the Smart Grocery List while still cookable. */
  LOW,

  // Blocks EC-13 instant swaps and queues 1 package to restore status back to PLENTY on restock.
  /** Completely depleted; blocks instant in-stock swaps and queues 1 package for restock. */
  OUT,
}

/**
 * Daily meal slot categories on the 21-slot Family Kitchen Board.
 *
 * @property chronologicalRank Intra-day ordering rank (`BREAKFAST < LUNCH < DINNER`).
 */
@Serializable
enum class MealSlotType(val chronologicalRank: Int) {
  // Rank 0..2 drives deterministic chronological Tier-1 reservation in GroceryDiffEngine (EC-09).
  /** Morning meal owned by Fred under the strict `<= 25` calibrated active minute cap. */
  BREAKFAST(0),

  /** Midday family meal owned by Wife. */
  LUNCH(1),

  /** Evening family meal owned by Wife. */
  DINNER(2),
}

/** Lifecycle state of a scheduled meal slot across the 7-day board. */
@Serializable
enum class MealSlotStatus {
  // PLANNED is the only state that reserves quantitative stock during chronological grocery diffs.
  /** Upcoming meal whose scaled BOM actively reserves Tier-1 pantry stock. */
  PLANNED,

  // COOKED meals already subtracted stock via applyCookedDeduction, so future reservation is 0.
  /** Completed meal whose ingredients were already deducted; reserves zero future stock. */
  COOKED,

  // EC-12: Eating leftovers or skipping releases reserved ingredients back to downstream slots.
  /** Skipped or replaced by leftovers (`EC-12`); deducts zero stock and releases BOM reservation. */
  COMPLETED_SKIPPED,
}

/** Ergonomic alias matching architecture docs that refer to [MealSlotStatus] as `SlotStatus`. */
typealias SlotStatus = MealSlotStatus

/** Designated household cook owner for a meal slot or curated recipe. */
@Serializable
enum class CookOwner {
  // Fred leads weekday 6:15 AM - 7:00 AM morning execution under the 1.4x prep buffer.
  /** Dad — owns weekday morning breakfasts (`<= 25m` calibrated active cap). */
  FRED,

  // Wife leads midday and evening Continental and Beninese/West African family meals.
  /** Mom — leads lunches, dinners, and West African / Continental one-pot family meals. */
  WIFE,

  // Shared ownership covers weekend batch meal-prep sessions and family feasts.
  /** Collaborative weekend or batch meal-prep ownership. */
  SHARED,
}

/** Optional 1-tap macro boost profile applied flat (`1x`) for Dad's workout days (`EC-07`). */
@Serializable
enum class WorkoutTiming {
  // Default unboosted slot state.
  /** Standard family meal with no extra workout add-on lines. */
  NONE,

  // Flat 1x carb add-on (e.g., +100g banana + 20ml honey) unscaled by household servings.
  /** Rapid glycogen carbohydrate add-on prior to training. */
  PRE_WORKOUT_CARB,

  // Flat 1x protein add-on (e.g., +2 eggs or +150g chicken) unscaled by household servings.
  /** Extra lean protein recovery add-on following strength or hypertrophy sessions. */
  POST_WORKOUT_HIGH_PROTEIN,
}

/**
 * Physical kitchen storage zone used by Smart Pantry filters and shelf-life heuristics.
 *
 * @property displayLabel Human-readable storage zone heading rendered in Smart Pantry.
 */
@Serializable
enum class StorageLocation(val displayLabel: String) {
  // Cold storage zone for dairy, fresh produce, and chilled proteins.
  /** Refrigerator and crisper drawers (`<= 4C`). */
  FRIDGE("Fridge & Produce"),

  // Ambient dry storage zone for grains, oils, spices, and shelf-stable staples.
  /** Dry pantry shelves, jars, and countertop baskets. */
  PANTRY("Dry Goods & Pantry"),

  // Sub-zero storage zone extending protein and produce shelf life.
  /** Deep-freeze drawer (`<= -18C`). */
  FREEZER("Freezer"),
}

/**
 * Perishability urgency classification driving Smart Pantry badges and zero-waste rescue alerts.
 *
 * @property badgeLabel Concise status badge text displayed on pantry and AI Chef cards.
 */
@Serializable
enum class ExpiryStatus(val badgeLabel: String) {
  // Comfortable remaining shelf life (>= 3 days or shelf-stable).
  /** Fresh stock with `>= 3` days remaining or shelf-stable longevity. */
  FRESH("Fresh"),

  // Action-required window (1..2 days remaining) prioritized by AI zero-waste rescue.
  /** Approaching spoilage within `48–72` hours (`1..2` days left). */
  EXPIRING_SOON("Expiring Soon"),

  // Immediate attention required (<= 0 days remaining).
  /** At or past target use-by date (`<= 0` days left). */
  EXPIRED("Expired"),
}

/**
 * Culinary skill complexity tier surfaced in Clean Recipe Reader and Cookbook filters.
 *
 * @property displayLabel Human-readable difficulty badge text.
 */
@Serializable
enum class RecipeDifficulty(val displayLabel: String) {
  /** Straightforward weeknight or <= 25m morning preparation. */
  EASY("Easy"),

  /** Multi-stage pan-sear, braise, or emulsified sauce technique. */
  MEDIUM("Medium"),

  /** Traditional celebration or slow-crafted heritage preparation. */
  ARTISAN("Artisan"),
}

/**
 * Smart Grocery List grouping and ordering strategy persisted in `KitchenSnapshot`.
 *
 * @property displayLabel Label shown in the Store Optimization selector bar.
 */
@Serializable
enum class GrocerySortMode(val displayLabel: String) {
  /** Groups items by Antipolo Palengke / Marikina Market & Supermarket aisle category. */
  AISLE_SORTED("Antipolo–Marikina Market (Aisle Sorted)"),

  /** Orders items by their earliest planned meal attribution (`mealUsageLabels`). */
  BY_RECIPE("By Planned Meal"),

  /** Surfaces quantitative mid-week shortages ahead of routine staple top-ups. */
  URGENCY_FIRST("Shortages First"),
}

/**
 * Infers a deterministic default [StorageLocation] from an ingredient's [category] and [tier].
 *
 * @param category Store aisle and culinary category.
 * @param tier Quantitative Tier-1 or tri-state Tier-2 classification.
 * @return Sensible default household [StorageLocation].
 */
fun defaultStorageLocationFor(
  category: IngredientCategory,
  tier: IngredientTier,
): StorageLocation =
  // Keep Tier-2 spices/oils and dry grains in PANTRY while chilling fresh produce/proteins/dairy.
  when {
    tier == IngredientTier.TIER_2_STAPLE -> StorageLocation.PANTRY
    category == IngredientCategory.PANTRY_STAPLE -> StorageLocation.PANTRY
    category == IngredientCategory.BAKERY_GRAINS -> StorageLocation.PANTRY
    else -> StorageLocation.FRIDGE
  }

/**
 * Infers a deterministic default shelf-life duration (in days) from [category] and [tier].
 *
 * @param category Store aisle and culinary category.
 * @param tier Quantitative Tier-1 or tri-state Tier-2 classification.
 * @return Positive baseline shelf-life window in days.
 */
fun defaultShelfLifeDaysFor(
  category: IngredientCategory,
  tier: IngredientTier,
): Int =
  // Aligned with USDA FoodKeeper App guidelines by food group.
  when {
    tier == IngredientTier.TIER_2_STAPLE -> 365
    category == IngredientCategory.PANTRY_STAPLE -> 180
    category == IngredientCategory.BAKERY_GRAINS -> 14
    category == IngredientCategory.DAIRY_EGGS -> 10
    category == IngredientCategory.PRODUCE -> 5
    category == IngredientCategory.PROTEIN -> 2
    else -> 7
  }

/**
 * Infers a deterministic estimated store package price in Philippine Pesos (`PHP`, `₱`) from
 * [category] and [tier], aligned with DA Bantay Presyo & Antipolo–Marikina supermarket rates.
 *
 * @param category Store aisle and culinary category.
 * @param tier Quantitative Tier-1 or tri-state Tier-2 classification.
 * @return Positive estimated package price in active household currency (`PHP` baseline).
 */
fun defaultPackagePriceUsdFor(
  category: IngredientCategory,
  tier: IngredientTier,
): Double =
  when {
    tier == IngredientTier.TIER_2_STAPLE -> 185.0
    category == IngredientCategory.PROTEIN -> 220.0
    category == IngredientCategory.DAIRY_EGGS -> 145.0
    category == IngredientCategory.PRODUCE -> 95.0
    category == IngredientCategory.BAKERY_GRAINS -> 135.0
    else -> 165.0
  }

/**
 * Immutable catalog metadata describing a single culinary ingredient.
 *
 * @property ingredientId Unique stable identifier (for example, `"ing_egg"`).
 * @property displayName Human-readable ingredient label.
 * @property category Store aisle and storage grouping.
 * @property tier Quantitative Tier-1 vs tri-state Tier-2 tracking tier.
 * @property baseUnit Canonical measurement unit (`count`, `g`, or `ml`).
 * @property defaultPackageQuantity Standard purchasable store package size in [baseUnit].
 * @property isLocallyAvailable Whether local markets currently carry this ingredient (`EC-02`).
 * @property substituteGroupIds Substitute groups this ingredient can fulfill (`EC-03`).
 * @property allergens Normalized allergen tags present in this ingredient.
 * @property defaultStorageLocation Default kitchen storage zone (`FRIDGE`, `PANTRY`, or `FREEZER`).
 * @property defaultShelfLifeDays Baseline post-purchase freshness window in days.
 * @property estimatedPackagePriceUsd Estimated package price in active household currency (`PHP` `₱` baseline; field name kept for wire compatibility).
 * @property optimalPrepHint Optional culinary suggestion surfaced on expiring Smart Pantry cards.
 * @property preferredVenue Optional shopper override routing this item to `Palengke` vs `Supermarket`.
 */
@Serializable
data class IngredientCatalogItem(
  val ingredientId: String,
  val displayName: String,
  val category: IngredientCategory,
  val tier: IngredientTier,
  val baseUnit: BaseUnit,
  val defaultPackageQuantity: Double,
  val isLocallyAvailable: Boolean = true,
  val substituteGroupIds: List<String> = emptyList(),
  val allergens: List<String> = emptyList(),
  val defaultStorageLocation: StorageLocation = defaultStorageLocationFor(category, tier),
  val defaultShelfLifeDays: Int = defaultShelfLifeDaysFor(category, tier),
  val estimatedPackagePriceUsd: Double = defaultPackagePriceUsdFor(category, tier),
  val optimalPrepHint: String? = null,
  val preferredVenue: MarketVenue? = null,
) {
  /** Convenience secondary constructor accepting integer package quantities in tests and presets. */
  constructor(
    ingredientId: String,
    displayName: String,
    category: IngredientCategory,
    tier: IngredientTier,
    baseUnit: BaseUnit,
    defaultPackageQuantity: Int,
    isLocallyAvailable: Boolean = true,
    substituteGroupIds: List<String> = emptyList(),
    allergens: List<String> = emptyList(),
  ) : this(
    ingredientId, displayName, category, tier, baseUnit,
    defaultPackageQuantity.toDouble(), isLocallyAvailable, substituteGroupIds, allergens,
  )

  /** Currency-neutral alias for [estimatedPackagePriceUsd] (`PHP` `₱` default). */
  val estimatedPackagePrice: Double
    get() = estimatedPackagePriceUsd

  /** Formats [estimatedPackagePrice] using [currency] (`₱` by default). */
  fun formattedPackagePrice(
    currency: HouseholdCurrency = HouseholdCurrency.PHP,
    includeDecimals: Boolean = false,
  ): String = currency.formatAmount(estimatedPackagePriceUsd, includeDecimals)
}

/**
 * Single Bill-of-Materials (BOM) requirement line inside a [Recipe].
 *
 * @property ingredientId Target catalog ingredient identifier.
 * @property baseQuantity Baseline quantity required for [Recipe.baseServings].
 * @property isOptional When `true`, local unavailability does not hard-hide the parent recipe.
 * @property substituteGroupId Optional substitute group key (`"FRUIT_TOPPING"`, `"LEAFY_GREENS"`).
 */
@Serializable
data class BomLine(
  val ingredientId: String,
  val baseQuantity: Double,
  val isOptional: Boolean = false,
  val substituteGroupId: String? = null,
) {
  /** Convenience secondary constructor accepting integer baseline quantities. */
  constructor(
    ingredientId: String,
    baseQuantity: Int,
    isOptional: Boolean = false,
    substituteGroupId: String? = null,
  ) : this(ingredientId, baseQuantity.toDouble(), isOptional, substituteGroupId)
}

/**
 * Pre/post-workout macro boost add-on applied flat (`1x`) for Fred (`EC-07`).
 *
 * @property profile Active workout timing profile (`PRE_WORKOUT_CARB` or `POST_WORKOUT_HIGH_PROTEIN`).
 * @property instructionAddOn Extra plating note surfaced on the meal card.
 * @property extraBomLines Unscaled `1x` ingredient lines added to reservations and deductions.
 */
@Serializable
data class WorkoutBoostModifier(
  val profile: WorkoutTiming,
  val instructionAddOn: String,
  val extraBomLines: List<BomLine> = emptyList(),
)

/**
 * Single sequential timed step inside the Morning Pacer, Clean Recipe Reader, or Cook Mode flow.
 *
 * @property stepNumber 1-based sequential index of the step.
 * @property instruction Actionable plain-text cooking instruction.
 * @property timerSeconds Optional countdown timer duration in seconds.
 * @property title Optional phase title (for example, `"Preparation"`, `"The Sear"`).
 * @property headline Optional editorial headline rendered in Step-by-Step Cook Mode.
 * @property techniqueBadge Optional culinary technique capsule (for example, `"Skin-Crisping"`).
 * @property visualTargetCue Optional sensory doneness cue (for example, `"Deep golden skin"`).
 * @property chefTip Optional Sous-Chef pro-tip surfaced alongside active timers.
 * @property stepIngredientIds Catalog `ingredientId`s used specifically in this step.
 */
@Serializable
data class MorningStep(
  val stepNumber: Int,
  val instruction: String,
  val timerSeconds: Int? = null,
  val title: String? = null,
  val headline: String? = null,
  val techniqueBadge: String? = null,
  val visualTargetCue: String? = null,
  val chefTip: String? = null,
  val stepIngredientIds: List<String> = emptyList(),
) {
  /** Resolved step phase label falling back to `"Step N"` when [title] is omitted. */
  val effectiveTitle: String
    get() = title?.trim()?.takeIf { it.isNotEmpty() } ?: "Step $stepNumber"

  /** Resolved editorial step summary falling back to the lead clause of [instruction]. */
  val effectiveHeadline: String
    get() {
      val explicit = headline?.trim()?.takeIf { it.isNotEmpty() }
      if (explicit != null) return explicit
      val firstClause = instruction.substringBefore('.').trim()
      return if (firstClause.isEmpty()) instruction else "$firstClause."
    }
}

/**
 * Immutable recipe definition calibrated for a Family of 4 (`Fred`, `Wife`, `8yo`, `2yo`).
 *
 * @property recipeId Unique stable recipe identifier.
 * @property title Display title of the dish.
 * @property sourceAttribution Culinary origin or household collection label.
 * @property allowedSlots Meal slot types where this recipe may be scheduled.
 * @property preferredOwner Default cook owner (`FRED` for breakfasts, `WIFE` for lunches/dinners).
 * @property baseServings Baseline household serving count (defaults to `4`).
 * @property rawPrepMinutes Unbuffered hands-on preparation time in minutes.
 * @property nightBeforePrepMinutes Minutes (`0..5`) shifted to 8:30 PM night-before prep.
 * @property cookMinutes Active stove or oven cooking time in minutes.
 * @property calibratedActiveMinutes Calibrated morning minutes (`ceil(effPrep * 1.4) + cook`).
 * @property nightBeforePrepSteps Checklist steps surfaced at 8:30 PM (`currentHour24 >= 20`).
 * @property morningSteps Timed sequential steps rendered in Morning Pacer and Cook Mode.
 * @property toddlerPrepTip Choking-prevention texture/cut tip for the youngest child (`EC-08`).
 * @property familyGoalPlating Optional per-member 1-pot plating instructions keyed by member ID.
 * @property bomLines Baseline Bill-of-Materials ingredient lines.
 * @property householdBomOverrides Learned permanent overrides normalized to [baseServings] (`EC-05`).
 * @property workoutModifiers Dad's flat `1x` pre/post-workout macro boost definitions (`EC-07`).
 * @property caloriesPerServing Estimated energy per serving in kilocalories (`kcal`).
 * @property proteinGrams Estimated protein per serving in grams (`g`).
 * @property carbsGrams Estimated carbohydrates per serving in grams (`g`).
 * @property fatGrams Estimated dietary fat per serving in grams (`g`).
 * @property difficulty Culinary skill complexity badge (`EASY`, `MEDIUM`, or `ARTISAN`).
 * @property cuisineTag Regional or style descriptor (`"Beninese Heritage"`, `"French Bistro"`, etc.).
 * @property chefNote Optional culinary technique or make-ahead note for Clean Recipe Reader.
 * @property chefPairingNote Optional family-friendly beverage or side pairing suggestion.
 * @property isSavedFavorite Whether the household bookmarked this recipe in Clean Reader / AI Chef.
 * @property parentRecipeId Optional source `recipeId` when forked as a household variation.
 * @property isUserCreated `true` when created, imported, or forked by the household.
 * @property cuisineTags Multi-tradition tags driving preferred-cuisine ranking and Cookbook filters.
 */
@Serializable
data class Recipe(
  val recipeId: String,
  val title: String,
  val sourceAttribution: String,
  val allowedSlots: List<MealSlotType>,
  val preferredOwner: CookOwner,
  val baseServings: Int = 4,
  val rawPrepMinutes: Int,
  val nightBeforePrepMinutes: Int = 0,
  val cookMinutes: Int,
  val calibratedActiveMinutes: Int,
  val nightBeforePrepSteps: List<String> = emptyList(),
  val morningSteps: List<MorningStep> = emptyList(),
  val toddlerPrepTip: String? = null,
  val familyGoalPlating: Map<String, String> = emptyMap(),
  val bomLines: List<BomLine>,
  val householdBomOverrides: Map<String, Double> = emptyMap(),
  val workoutModifiers: List<WorkoutBoostModifier> = emptyList(),
  val caloriesPerServing: Int = 420,
  val proteinGrams: Int = 24,
  val carbsGrams: Int = 38,
  val fatGrams: Int = 16,
  val difficulty: RecipeDifficulty = RecipeDifficulty.EASY,
  val cuisineTag: String = "Family Kitchen",
  val chefNote: String? = null,
  val chefPairingNote: String? = null,
  val isSavedFavorite: Boolean = false,
  val parentRecipeId: String? = null,
  val isUserCreated: Boolean = false,
  val cuisineTags: List<String> = emptyList(),
)

/**
 * Current stock record for a single ingredient in the Household Pantry Ledger.
 *
 * @property ingredientId Referenced catalog ingredient ID.
 * @property tier Quantitative Tier-1 or tri-state Tier-2 classification.
 * @property currentQuantity Clamped non-negative stock in canonical [BaseUnit] (`EC-10`).
 * @property stapleStatus Tri-state level (`PLENTY | LOW | OUT`), authoritative for Tier-2 (`EC-11`).
 * @property isOutOfStock `true` when Tier-1 quantity hits `0` or Tier-2 status is `OUT`.
 * @property updatedAtIso ISO-8601 timestamp of the most recent stock update.
 * @property storageLocation Optional kitchen zone override (`FRIDGE`, `PANTRY`, or `FREEZER`).
 * @property storageSubLocation Optional shelf/drawer label (`"Crisper Bin"`, `"Jar #2"`, etc.).
 * @property expiresInDays Remaining freshness window in days (`<= 0` expired, `1..2` expiring soon).
 * @property expiryDateIso Optional explicit ISO-8601 use-by date.
 * @property purchasedDaysAgo Optional days elapsed since purchase or restock (`>= 0`).
 * @property packageNote Optional container/portion descriptor (`"2 portions • Fresh Cut"`).
 */
@Serializable
data class PantryLedgerEntry(
  val ingredientId: String,
  val tier: IngredientTier,
  val currentQuantity: Double,
  val stapleStatus: StapleStatus = StapleStatus.PLENTY,
  val isOutOfStock: Boolean = false,
  val updatedAtIso: String = DEFAULT_TIMESTAMP_ISO,
  val storageLocation: StorageLocation? = null,
  val storageSubLocation: String? = null,
  val expiresInDays: Int? = null,
  val expiryDateIso: String? = null,
  val purchasedDaysAgo: Int? = null,
  val packageNote: String? = null,
) {
  /** Convenience secondary constructor accepting integer stock quantities. */
  constructor(
    ingredientId: String,
    tier: IngredientTier,
    currentQuantity: Int,
    stapleStatus: StapleStatus = StapleStatus.PLENTY,
    isOutOfStock: Boolean = false,
    updatedAtIso: String = DEFAULT_TIMESTAMP_ISO,
  ) : this(ingredientId, tier, currentQuantity.toDouble(), stapleStatus, isOutOfStock, updatedAtIso)

  // Map expiresInDays deterministically: <= 0 EXPIRED, 1..2 EXPIRING_SOON, >= 3 or null FRESH.
  /** Computed perishability status derived deterministically from [expiresInDays]. */
  val effectiveExpiryStatus: ExpiryStatus
    get() {
      val days = expiresInDays ?: return ExpiryStatus.FRESH
      return when {
        days <= 0 -> ExpiryStatus.EXPIRED
        days <= 2 -> ExpiryStatus.EXPIRING_SOON
        else -> ExpiryStatus.FRESH
      }
    }

  /** Human-readable urgency tag rendered on Smart Pantry pills (`"Tomorrow"`, `"In 2 days"`, etc.). */
  val urgencyBadgeLabel: String
    get() {
      val days = expiresInDays ?: return "Fresh"
      return when {
        days < 0 -> "Expired"
        days == 0 -> "Today"
        days == 1 -> "Tomorrow"
        else -> "In $days days"
      }
    }
}

/**
 * Single scheduled meal slot inside the 21-slot [WeeklyBoard].
 *
 * @property slotId Unique slot identifier within the board.
 * @property dayIndex 0-based weekday index (`0 = Monday` through `6 = Sunday`).
 * @property slotType Meal category (`BREAKFAST`, `LUNCH`, or `DINNER`).
 * @property owner Assigned household cook (`FRED`, `WIFE`, or `SHARED`).
 * @property recipeId Scheduled [Recipe.recipeId].
 * @property targetServings Requested family servings for this meal (scales base BOM).
 * @property activeWorkoutModifier Optional flat `1x` Dad workout boost active on this slot.
 * @property status Lifecycle status (`PLANNED` reserves BOM; `COOKED`/`COMPLETED_SKIPPED` reserve 0).
 * @property isPreppedAhead Whether station-grouped weekly prep has been completed for this slot.
 * @property preppedTimeSavedMinutes Active cook-time reduction earned from weekly batch prep.
 * @property isPinned When `true`, weekly rollover retains this slot on its `(dayIndex, slotType)`.
 * @property skipReason Optional explicit reason (`ATE_LEFTOVERS`, `EATING_OUT`, `SKIPPED`) on skip.
 * @property leftoverSourceRecipeId Non-null when this slot consumes `0`-BOM batch-cooked leftovers.
 */
@Serializable
data class MealSlot(
  val slotId: String,
  val dayIndex: Int,
  val slotType: MealSlotType,
  val owner: CookOwner,
  val recipeId: String,
  val targetServings: Int = 4,
  val activeWorkoutModifier: WorkoutTiming = WorkoutTiming.NONE,
  val status: MealSlotStatus = MealSlotStatus.PLANNED,
  val isPreppedAhead: Boolean = false,
  val preppedTimeSavedMinutes: Int = 0,
  val isPinned: Boolean = false,
  val skipReason: SlotSkipReason? = null,
  val leftoverSourceRecipeId: String? = null,
)

/**
 * Complete 7-day (21-slot) household menu board.
 *
 * @property boardId Unique weekly board identifier.
 * @property householdId Owning household profile identifier.
 * @property weekStartDateIso ISO-8601 Monday start date for the planned week.
 * @property slots Scheduled meal slots across Monday (`0`) through Sunday (`6`).
 */
@Serializable
data class WeeklyBoard(
  val boardId: String,
  val householdId: String,
  val weekStartDateIso: String,
  val slots: List<MealSlot>,
)

/**
 * Payload dispatched when marking a meal slot as cooked (1-tap fast path or adjusted override).
 *
 * @property slotId Target [MealSlot.slotId] being marked cooked.
 * @property recipeId Cooked [Recipe.recipeId].
 * @property targetServings Household serving count cooked.
 * @property activeWorkoutModifier Active Dad workout boost applied to the cooked meal.
 * @property actualUsedQuantities Optional per-ingredient override & ad-hoc extra map (`EC-04`, `EC-06`).
 * @property saveOverrideAsHouseholdDefault Persists normalized overrides when `true` (`EC-05`).
 */
@Serializable
data class CookedDeductionPayload(
  val slotId: String,
  val recipeId: String,
  val targetServings: Int = 4,
  val activeWorkoutModifier: WorkoutTiming = WorkoutTiming.NONE,
  val actualUsedQuantities: Map<String, Double>? = null,
  val saveOverrideAsHouseholdDefault: Boolean = false,
)

/**
 * Output of an `O(M)` single-pass cooked meal deduction in `PantryLedgerEngine`.
 *
 * @property updatedPantry Updated list of household pantry entries.
 * @property updatedPantryById `O(1)` lookup map of updated pantry entries keyed by `ingredientId`.
 * @property deductedQuantities Effective quantities subtracted per ingredient (`0.0` for Tier-2).
 * @property outOfStockIngredientIds Ingredient IDs that are out of stock after the deduction.
 * @property updatedRecipe Recipe snapshot (with normalized `householdBomOverrides` if saved).
 * @property updatedSlotStatus Resulting slot lifecycle state ([MealSlotStatus.COOKED]).
 */
@Serializable
data class CookedDeductionResult(
  val updatedPantry: List<PantryLedgerEntry>,
  val updatedPantryById: Map<String, PantryLedgerEntry>,
  val deductedQuantities: Map<String, Double>,
  val outOfStockIngredientIds: List<String>,
  val updatedRecipe: Recipe,
  val updatedSlotStatus: MealSlotStatus = MealSlotStatus.COOKED,
)

/**
 * Atomic restock instruction committed from the Smart Grocery List or visual intake confirmation.
 *
 * @property ingredientId Target catalog ingredient ID to restock.
 * @property addedBaseQuantity Quantity added in canonical [BaseUnit] (whole-package rounded).
 * @property newStapleStatus Optional tri-state reset (`PLENTY` when restocking Tier-2 staples).
 */
@Serializable
data class RestockDelta(
  val ingredientId: String,
  val addedBaseQuantity: Double,
  val newStapleStatus: StapleStatus? = null,
) {
  /** Convenience secondary constructor accepting integer restock quantities. */
  constructor(
    ingredientId: String,
    addedBaseQuantity: Int,
    newStapleStatus: StapleStatus? = null,
  ) : this(ingredientId, addedBaseQuantity.toDouble(), newStapleStatus)
}

/**
 * Canonical offline seed catalog dataset deserialized 1:1 from `assets/seed_catalog.json`.
 *
 * @property ingredients Curated Tier-1 and Tier-2 ingredient definitions (38 canonical items).
 * @property recipes Curated Family-of-4 recipes (30 canonical recipes).
 * @property initialPantry Initial household pantry ledger stock records (38 entries).
 */
@Serializable
data class SeedCatalogDataset(
  val ingredients: List<IngredientCatalogItem>,
  val recipes: List<Recipe>,
  val initialPantry: List<PantryLedgerEntry>,
)

/**
 * Four kitchen station categories for grouping Weekly Meal Prep Session make-ahead tasks.
 *
 * @property displayLabel Human-readable station header rendered in the Pacer Weekly Prep tab.
 */
@Serializable
enum class PrepStationType(val displayLabel: String) {
  // Station 1: Dry grain measuring and overnight legume soaking (middle fridge shelf, 5 days).
  /** Dry measuring, rinsing, and overnight soaking of oats, rice, cornmeal, and beans. */
  GRAINS_OATS_BEANS("Grains, Oats & Beans"),

  // Station 2: Vented paper-towel crisper storage for diced produce and washed greens (4-5 days).
  /** Batch washing, peeling, and dicing of weekly fruits, greens, and aromatics. */
  WASH_CHOP_PRODUCE("Wash, Chop & Produce"),

  // Station 3: Coldest bottom shelf (<= 4C) storage for marinated proteins and egg bases (3-4 days).
  /** Protein portioning, overnight marinades, and pre-whisked egg/dairy bases. */
  PROTEINS_MARINADES("Proteins & Marinades"),

  // Station 4: Color-coded silicone cups for 2yo choking-safe strips and 8yo snack portions.
  /** Toddler choking-safe cuts, crushed seed toppers, and school-kid snack portions. */
  KID_PORTIONS("Kid & Toddler Portions"),
}

/**
 * Consolidated make-ahead prep task combining quantities across multiple planned weekly meals.
 *
 * @property taskId Deterministic station task identifier.
 * @property station Assigned kitchen prep station.
 * @property title Actionable station card title.
 * @property instruction Consolidated batch-prep instruction summarizing meals and quantities.
 * @property slotIds Planned [MealSlot.slotId]s covered by this station task.
 * @property recipeIds Distinct [Recipe.recipeId]s covered by this station task.
 * @property aggregatedIngredients Total Tier-1/Tier-2 quantities grouped by `ingredientId`.
 * @property estimatedPrepMinutes Estimated hands-on batch prep time in minutes.
 * @property timeSavedMinutes Cumulative weekday active cooking minutes saved across covered slots.
 * @property storageTip Fridge/pantry container and shelf food-safety storage guidance.
 * @property isCompleted Whether this station task has already been checked off.
 */
@Serializable
data class PrepStationTask(
  val taskId: String,
  val station: PrepStationType,
  val title: String,
  val instruction: String,
  val slotIds: List<String>,
  val recipeIds: List<String>,
  val aggregatedIngredients: Map<String, Double>,
  val estimatedPrepMinutes: Int,
  val timeSavedMinutes: Int,
  val storageTip: String,
  val isCompleted: Boolean = false,
)

/**
 * Complete station-grouped Weekly Meal Prep Session plan across all `PLANNED` board slots.
 *
 * @property sessionId Deterministic prep session identifier for the board.
 * @property boardId Associated [WeeklyBoard.boardId].
 * @property tasks Ordered list of non-empty station prep tasks.
 * @property tasksByStation Station lookup map covering all [PrepStationType] values.
 * @property totalPrepMinutes Sum of [PrepStationTask.estimatedPrepMinutes] across active stations.
 * @property totalTimeSavedMinutes Sum of [PrepStationTask.timeSavedMinutes] across active stations.
 * @property coveredSlotIds Distinct planned slot IDs accelerated by this session.
 */
@Serializable
data class WeeklyPrepSessionPlan(
  val sessionId: String,
  val boardId: String,
  val tasks: List<PrepStationTask>,
  val tasksByStation: Map<PrepStationType, List<PrepStationTask>>,
  val totalPrepMinutes: Int,
  val totalTimeSavedMinutes: Int,
  val coveredSlotIds: List<String>,
)

/**
 * Atomic batch commit payload for completing checked tasks in a Weekly Meal Prep Session.
 *
 * @property sessionId Active [WeeklyPrepSessionPlan.sessionId].
 * @property boardId Target [WeeklyBoard.boardId].
 * @property completedTaskIds Checked [PrepStationTask.taskId]s to stamp onto planned slots.
 * @property deductPreppedIngredientsFromPantry Deducts aggregated Tier-1 items when `true`.
 */
@Serializable
data class WeeklyPrepBatchCommitPayload(
  val sessionId: String,
  val boardId: String,
  val completedTaskIds: List<String>,
  val deductPreppedIngredientsFromPantry: Boolean = true,
)

/**
 * Result of applying a Weekly Meal Prep Session batch commit to the board and pantry ledger.
 *
 * @property updatedBoard Weekly board with covered slots stamped `isPreppedAhead = true`.
 * @property updatedPantry Updated household pantry entries (clamped at zero if deducted).
 * @property deductedQuantities Quantities deducted per `ingredientId` during batch prep.
 * @property stampedSlotIds Slot IDs whose `preppedTimeSavedMinutes` were updated.
 * @property totalTimeSavedMinutes Total weekday cooking minutes saved by the committed tasks.
 */
@Serializable
data class WeeklyPrepBatchCommitResult(
  val updatedBoard: WeeklyBoard,
  val updatedPantry: List<PantryLedgerEntry>,
  val deductedQuantities: Map<String, Double>,
  val stampedSlotIds: List<String>,
  val totalTimeSavedMinutes: Int,
)

/** Classification reason explaining why `AvailabilityFilter` accepted or blocked a recipe. */
@Serializable
enum class AvailabilityReason {
  // Recipe passed both local market availability and household allergen avoidance gates.
  /** All required BOM lines are locally available (directly or via safe substitution). */
  AVAILABLE,

  // Blocked because at least one required ingredient is unavailable locally with no substitute.
  /** One or more required BOM lines are locally unavailable with no valid substitute (`EC-02`). */
  UNAVAILABLE_INGREDIENT,

  // Blocked solely due to overlap with HouseholdProfile.householdAllergens.
  /** One or more required BOM lines conflict with active household allergens (`EC-02`). */
  ALLERGEN_BLOCKED,
}

/**
 * Result of evaluating a [Recipe] against local market availability, substitute groups, and allergens.
 *
 * @property recipeId Evaluated [Recipe.recipeId].
 * @property isSchedulable `true` when zero required BOM lines remain blocked.
 * @property reason Summary classification ([AvailabilityReason]).
 * @property blockedIngredientIds Required ingredient IDs that could not be fulfilled or substituted.
 * @property blockedAllergens Matched allergen tags blocking scheduling.
 * @property resolvedBomLines BOM lines with deterministic `substituteGroupId` rewrites applied (`EC-03`).
 * @property substitutionsApplied Map from original blocked `ingredientId` to chosen substitute ID.
 */
@Serializable
data class AvailabilityEvaluation(
  val recipeId: String,
  val isSchedulable: Boolean,
  val reason: AvailabilityReason = AvailabilityReason.AVAILABLE,
  val blockedIngredientIds: List<String> = emptyList(),
  val blockedAllergens: List<String> = emptyList(),
  val resolvedBomLines: List<BomLine> = emptyList(),
  val substitutionsApplied: Map<String, String> = emptyMap(),
)

/**
 * Resolved UI-ready meal card combining calibrated morning pacing, workout add-ons, stage-adapted
 * child safety callouts (`EC-08`), 4-member 1-pot plating, and 8:30 PM prep banners.
 *
 * @property slot Underlying scheduled [MealSlot].
 * @property recipe Recipe definition with substitute-resolved BOM lines.
 * @property calibratedActiveMinutes Effective active minutes (reduced when [isPreppedAhead] is true).
 * @property effectiveBomQuantities Scaled BOM + flat `1x` Dad workout boost map (`EC-07`).
 * @property workoutInstructionAddOn Optional workout plating banner text when modifier is active.
 * @property toddlerPrepTip Raw non-empty recipe toddler safety callout (`EC-08`).
 * @property stageAdaptedSafetyTip Growth-stage adapted child safety callout (`BABY_6_12M`..`KIDS_5_PLUS`).
 * @property familyGoalPlating 1-pot 4-member goal plating instructions keyed by member ID.
 * @property showNightBeforeBanner `true` at `20:00+` when planned meal has unprepped night steps.
 * @property isPreppedAhead Whether make-ahead batch prep was completed for this slot.
 * @property preppedTimeSavedMinutes Minutes saved by completed weekly prep tasks.
 */
@Serializable
data class ResolvedMealCard(
  val slot: MealSlot,
  val recipe: Recipe,
  val calibratedActiveMinutes: Int,
  val effectiveBomQuantities: Map<String, Double>,
  val workoutInstructionAddOn: String? = null,
  val toddlerPrepTip: String? = null,
  val stageAdaptedSafetyTip: String? = null,
  val familyGoalPlating: Map<String, String> = emptyMap(),
  val showNightBeforeBanner: Boolean = false,
  val isPreppedAhead: Boolean = false,
  val preppedTimeSavedMinutes: Int = 0,
)

/**
 * Quantitative Tier-1 deficit detected on a specific chronologically scheduled meal slot (`EC-09`).
 *
 * @property ingredientId Depleted Tier-1 ingredient identifier.
 * @property requiredQuantity Effective quantity required by this meal slot.
 * @property availableBeforeSlot Remaining running stock immediately prior to this slot.
 * @property deficitQuantity Uncovered quantity (`requiredQuantity - availableBeforeSlot`).
 */
@Serializable
data class SlotIngredientShortage(
  val ingredientId: String,
  val requiredQuantity: Double,
  val availableBeforeSlot: Double,
  val deficitQuantity: Double,
)

/**
 * Chronological BOM reservation and shortage summary for a single scheduled [MealSlot].
 *
 * @property slotId Summarized [MealSlot.slotId].
 * @property dayIndex Weekday index (`0..6`).
 * @property slotType Meal slot type (`BREAKFAST`, `LUNCH`, `DINNER`).
 * @property status Slot lifecycle status (`COOKED` and `COMPLETED_SKIPPED` reserve empty maps).
 * @property reservedQuantities Tier-1 quantities reserved by this slot.
 * @property shortages Mid-week Tier-1 deficits triggered on this slot (`EC-09`).
 */
@Serializable
data class SlotReservationSummary(
  val slotId: String,
  val dayIndex: Int,
  val slotType: MealSlotType,
  val status: MealSlotStatus,
  val reservedQuantities: Map<String, Double> = emptyMap(),
  val shortages: List<SlotIngredientShortage> = emptyList(),
)

/**
 * Ad-hoc custom household shopping item added via text or voice dictation on Smart Grocery List.
 *
 * @property customId Unique identifier for toggling or removing this custom item.
 * @property displayName User-supplied item name (for example, `"Parchment Baking Paper"`).
 * @property quantityNote Optional free-form quantity descriptor (`"1 roll"`, `"2 packs"`).
 * @property category Store aisle category for grouped display.
 * @property isChecked Whether this item has been checked off in the store.
 */
@Serializable
data class CustomGroceryItem(
  val customId: String,
  val displayName: String,
  val quantityNote: String = "1 item",
  val category: IngredientCategory = IngredientCategory.PRODUCE,
  val isChecked: Boolean = false,
)

/**
 * Computed Smart Grocery List line item with whole-package ceiling rounding (`EC-09`, `EC-11`).
 *
 * @property ingredientId Catalog ingredient ID to purchase.
 * @property displayName Human-readable ingredient name.
 * @property category Store aisle category for grouped shopping checklists.
 * @property tier Quantitative Tier-1 vs tri-state Tier-2 classification.
 * @property baseUnit Canonical measurement unit.
 * @property totalReservedQuantity Cumulative weekly planned demand across `PLANNED` slots.
 * @property currentStockQuantity Starting household stock in [baseUnit].
 * @property netDeficit Net uncovered weekly deficit prior to package rounding.
 * @property defaultPackageQuantity Standard store package size in [baseUnit].
 * @property packagesToBuy Whole store packages required (`ceil(netDeficit / defaultPackageQuantity)`).
 * @property restockQuantity Total [baseUnit] added on restock (`packagesToBuy * defaultPackageQuantity`).
 * @property stapleStatus Current Tier-2 status when queued due to `LOW` or `OUT` (`EC-11`).
 * @property isManuallyQueued `true` when explicitly queued via Pantry `+ Need to Buy` or AI Chef.
 * @property mealUsageLabels Planned meal attributions (`"for Wednesday Dinner • <Recipe>"`).
 * @property estimatedCostUsd Estimated cost in USD (`packagesToBuy * estimatedPackagePriceUsd`).
 * @property packageDescriptor Human-readable package string (`"2 cartons (24 count)"`).
 * @property isChecked Whether this item is checked off in `KitchenSnapshot.checkedGroceryItemIds`.
 * @property isQuantityOverridden `true` when the shopper customized the buy amount away from plan.
 * @property plannedRestockQuantity Un-overridden whole-package quantity required by the meal plan.
 * @property marketVenue Resolved `Palengke` vs `Supermarket` shopping venue for store-split filtering.
 */
@Serializable
data class GroceryListItem(
  val ingredientId: String,
  val displayName: String,
  val category: IngredientCategory,
  val tier: IngredientTier,
  val baseUnit: BaseUnit,
  val totalReservedQuantity: Double,
  val currentStockQuantity: Double,
  val netDeficit: Double,
  val defaultPackageQuantity: Double,
  val packagesToBuy: Int,
  val restockQuantity: Double,
  val stapleStatus: StapleStatus? = null,
  val isManuallyQueued: Boolean = false,
  val mealUsageLabels: List<String> = emptyList(),
  val estimatedCostUsd: Double = 0.0,
  val packageDescriptor: String = "",
  val isChecked: Boolean = false,
  val isQuantityOverridden: Boolean = false,
  val plannedRestockQuantity: Double = restockQuantity,
  val marketVenue: MarketVenue = MarketVenue.SUPERMARKET,
) {
  /** Currency-neutral alias for [estimatedCostUsd] (`PHP` `₱` default). */
  val estimatedCost: Double
    get() = estimatedCostUsd

  /** Formats [estimatedCost] using [currency] (`₱` by default). */
  fun formattedEstimatedCost(
    currency: HouseholdCurrency = HouseholdCurrency.PHP,
    includeDecimals: Boolean = false,
  ): String = currency.formatAmount(estimatedCostUsd, includeDecimals)
}

/**
 * Pure user-intent actions for editing Smart Grocery List buy quantities, custom shopping items,
 * trip-level item skips, and global household catalog ingredient names and package metadata.
 */
@Serializable
sealed interface GroceryAndCatalogEditAction {
  // Persists global catalog rename/pack edits alongside an optional trip buy-quantity override.
  /**
   * Updates a catalog-backed Smart Grocery List item's name, optional store package metadata, and
   * optional custom buy quantity override for the active shopping trip.
   *
   * @property ingredientId Target catalog `ingredientId` to update.
   * @property updatedDisplayName New non-blank household ingredient display name.
   * @property overrideBuyQuantity Optional custom buy quantity in canonical [BaseUnit] (`null` resets to plan).
   * @property updatedDefaultPackageQuantity Optional updated store package size (`> 0.0`).
   * @property updatedPackagePriceUsd Optional updated store package price (`>= 0.0`).
   */
  @Serializable
  data class SaveGroceryCatalogItemEdit(
    val ingredientId: String,
    val updatedDisplayName: String,
    val overrideBuyQuantity: Double? = null,
    val updatedDefaultPackageQuantity: Double? = null,
    val updatedPackagePriceUsd: Double? = null,
  ) : GroceryAndCatalogEditAction

  // Adjusts whole-package count (1..99) in 1 tap without leaving the Smart Grocery List.
  /**
   * Steps a Smart Grocery List item's whole-package buy count up or down (`1..99` packs).
   *
   * @property ingredientId Target catalog `ingredientId` on the Smart Grocery List.
   * @property deltaPacks Signed pack delta (for example, `-1` or `+1`).
   */
  @Serializable
  data class StepGroceryItemPacks(
    val ingredientId: String,
    val deltaPacks: Int,
  ) : GroceryAndCatalogEditAction

  // Clears any custom buy-quantity override or trip skip back to the weekly meal plan deficit.
  /**
   * Resets any custom buy-quantity override on [ingredientId] back to the weekly meal plan amount.
   *
   * @property ingredientId Target catalog `ingredientId` to restore to planned rounding.
   */
  @Serializable
  data class ResetGroceryItemOverride(
    val ingredientId: String,
  ) : GroceryAndCatalogEditAction

  // Suppresses an item for the current shopping trip without altering recipes or pantry balances.
  /**
   * Skips [ingredientId] on the current shopping trip without modifying planned weekly recipes.
   *
   * @property ingredientId Target catalog `ingredientId` to hide for this shopping trip.
   */
  @Serializable
  data class DismissGroceryItemForTrip(
    val ingredientId: String,
  ) : GroceryAndCatalogEditAction

  // Restores all trip-skipped catalog items back onto the active Smart Grocery List at once.
  /** Restores all skipped grocery items back onto the Smart Grocery List. */
  @Serializable
  data object RestoreDismissedGroceryItems : GroceryAndCatalogEditAction

  // Upserts edited name, quantity note, and aisle category on a free-form CustomGroceryItem.
  /**
   * Saves edits to a user-added or voice-dictated [CustomGroceryItem].
   *
   * @property item Updated custom shopping item record to upsert by `customId`.
   */
  @Serializable
  data class SaveCustomGroceryItemEdit(
    val item: CustomGroceryItem,
  ) : GroceryAndCatalogEditAction

  // Deletes a free-form CustomGroceryItem from the Smart Grocery List and checked IDs.
  /**
   * Removes a [CustomGroceryItem] from the Smart Grocery List.
   *
   * @property customId Identifier of the custom shopping item to remove.
   */
  @Serializable
  data class DeleteCustomGroceryItem(
    val customId: String,
  ) : GroceryAndCatalogEditAction

  // Updates global catalog name, aisle category, and store pack details from Smart Pantry.
  /**
   * Renames a household catalog ingredient or updates its aisle category, package size, and price
   * across Pantry, Shop, Plan, and Recipes.
   *
   * @property ingredientId Target catalog `ingredientId` in `KitchenSnapshot.ingredients`.
   * @property updatedDisplayName New non-blank household ingredient display name.
   * @property updatedCategory Optional updated [IngredientCategory] store aisle grouping.
   * @property updatedDefaultPackageQuantity Optional updated store package size (`> 0.0`).
   * @property updatedPackagePriceUsd Optional updated store package price (`>= 0.0`).
   */
  @Serializable
  data class UpdateCatalogIngredientDetails(
    val ingredientId: String,
    val updatedDisplayName: String,
    val updatedCategory: IngredientCategory? = null,
    val updatedDefaultPackageQuantity: Double? = null,
    val updatedPackagePriceUsd: Double? = null,
  ) : GroceryAndCatalogEditAction
}

/**
 * Complete weekly chronological reservation summary and aisle-grouped Smart Grocery List output.
 *
 * @property slotSummaries Chronologically ordered per-slot reservation and shortage summaries.
 * @property slotSummariesBySlotId `O(1)` lookup map from `slotId` to [SlotReservationSummary].
 * @property groceryItems Flat list of whole-package rounded Smart Grocery List items.
 * @property groceryItemsByCategory Smart Grocery List items grouped by store aisle category.
 * @property restockDeltas Ready-to-commit [RestockDelta] entries for 1-tap atomic batch restock.
 * @property skippedStockedStapleCount Planned ingredients skipped because home stock covered 100%.
 * @property deduplicationSavingsUsd Estimated amount saved by automatic pantry deduplication (`PHP` `₱` baseline).
 * @property plannedMealCount Active `PLANNED` meal slots evaluated across the 7-day board.
 * @property estimatedTotalCostUsd Estimated total for remaining unchecked grocery items (`PHP` `₱` baseline).
 * @property skippedStockedItems Transparent summaries of planned items 100% covered by Pantry stock.
 */
@Serializable
data class WeeklyGroceryDiff(
  val slotSummaries: List<SlotReservationSummary>,
  val slotSummariesBySlotId: Map<String, SlotReservationSummary>,
  val groceryItems: List<GroceryListItem>,
  val groceryItemsByCategory: Map<IngredientCategory, List<GroceryListItem>>,
  val restockDeltas: List<RestockDelta>,
  val skippedStockedStapleCount: Int = 0,
  val deduplicationSavingsUsd: Double = 0.0,
  val plannedMealCount: Int = 0,
  val estimatedTotalCostUsd: Double = 0.0,
  val skippedStockedItems: List<SkippedPantryItemSummary> = emptyList(),
) {
  /** Currency-neutral alias for [deduplicationSavingsUsd]. */
  val deduplicationSavings: Double
    get() = deduplicationSavingsUsd

  /** Currency-neutral alias for [estimatedTotalCostUsd]. */
  val estimatedTotalCost: Double
    get() = estimatedTotalCostUsd

  /** Formats [deduplicationSavings] using [currency] (`₱` by default). */
  fun formattedDeduplicationSavings(
    currency: HouseholdCurrency = HouseholdCurrency.PHP,
    includeDecimals: Boolean = false,
  ): String = currency.formatAmount(deduplicationSavingsUsd, includeDecimals)

  /** Formats [estimatedTotalCost] using [currency] (`₱` by default). */
  fun formattedEstimatedTotalCost(
    currency: HouseholdCurrency = HouseholdCurrency.PHP,
    includeDecimals: Boolean = false,
  ): String = currency.formatAmount(estimatedTotalCostUsd, includeDecimals)
}
