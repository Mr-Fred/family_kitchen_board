package com.familykitchen.board.domain

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.serialization.Serializable

// Pure household profile, child growth-stage safety, life-event protocols, multi-currency (PHP ₱
// default), consumer onboarding templates, and Antipolo-Marikina local market preset catalog items.
// Zero Android, Compose, java.io, or java.util.Locale imports permitted inside domain/.

/**
 * Supported household display and budgeting currencies, defaulting to Philippine Peso (`PHP`, `₱`)
 * for Antipolo & Marikina markets while supporting global consumer distribution.
 *
 * @property isoCode ISO-4217 3-letter currency code.
 * @property symbol Prefix symbol rendered before formatted amounts.
 * @property displayLabel Human-readable selector label for Family Profile settings.
 */
@Serializable
enum class HouseholdCurrency(
  val isoCode: String,
  val symbol: String,
  val displayLabel: String,
) {
  // Default currency grounded in Antipolo & Marikina (Rizal / East Metro Manila) retail pricing.
  /** Philippine Peso (`₱`), default household currency. */
  PHP("PHP", "₱", "Philippine Peso (₱)"),

  /** United States Dollar (`$`) for North American consumer households. */
  USD("USD", "$", "US Dollar ($)"),

  /** Euro (`€`) for European consumer households. */
  EUR("EUR", "€", "Euro (€)"),

  /** British Pound (`£`) for UK consumer households. */
  GBP("GBP", "£", "British Pound (£)"),

  /** West African CFA Franc (`CFA`) for Francophone West African consumer households. */
  XOF("XOF", "CFA ", "West African CFA Franc (CFA)");

  /**
   * Formats [amount] deterministically using pure Kotlin string math (zero platform `Locale` I/O),
   * inserting comma thousands separators (for example, `"₱1,120"` or `"₱245.50"`).
   *
   * @param amount Monetary value to format (negative or non-finite values clamp cleanly to `0`).
   * @param includeDecimals Renders `.XX` centavos/cents when `true`, or rounded whole units when `false`.
   * @return Formatted currency string prefixed with [symbol].
   */
  fun formatAmount(amount: Double, includeDecimals: Boolean = false): String {
    val safeAmount = if (!amount.isFinite() || amount <= 0.0) 0.0 else amount
    return when {
      includeDecimals -> {
        val totalMinor = (safeAmount * 100.0).roundToLong()
        val major = totalMinor / 100L
        val minor = abs(totalMinor % 100L)
        "$symbol${formatGroupedWholeNumber(major)}.${minor.toString().padStart(2, '0')}"
      }
      else -> "$symbol${formatGroupedWholeNumber(safeAmount.roundToLong())}"
    }
  }
}

/**
 * Formats a kitchen cost or food-waste savings figure using [currency] (`PHP` `₱` by default).
 *
 * @param amount Monetary value to format.
 * @param currency Active household currency (`HouseholdCurrency.PHP` default).
 * @param includeDecimals Whether to include two fractional decimal places.
 * @return Cleanly formatted currency string (for example, `"₱1,120"`).
 */
fun formatKitchenCurrency(
  amount: Double,
  currency: HouseholdCurrency = HouseholdCurrency.PHP,
  includeDecimals: Boolean = false,
): String = currency.formatAmount(amount = amount, includeDecimals = includeDecimals)

// Group digits in 3-character clusters from right to left without java.util.Formatter dependencies.
private fun formatGroupedWholeNumber(nonNegativeValue: Long): String {
  val digits = nonNegativeValue.coerceAtLeast(0L).toString()
  if (digits.length <= 3) return digits
  val sb = StringBuilder(digits.length + digits.length / 3)
  digits.forEachIndexed { idx, ch ->
    if (idx > 0 && (digits.length - idx) % 3 == 0) {
      sb.append(',')
    }
    sb.append(ch)
  }
  return sb.toString()
}

/**
 * Developmental texture and choking-safety growth stage for the youngest family member (`EC-08`).
 *
 * @property displayLabel Full human-readable label for the Family Profile sheet.
 * @property shortLabel Compact badge label shown on meal cards.
 * @property badgePrefix Prefix prepended to adapted safety tips.
 * @property prepGuidance Default texture preparation rule for this stage.
 */
@Serializable
enum class ChildGrowthStage(
  val displayLabel: String,
  val shortLabel: String,
  val badgePrefix: String,
  val prepGuidance: String,
) {
  // Four progressive childhood feeding stages adapting every meal card's choking-safety callout.
  /** 6–12 month infant stage requiring smooth iron-rich purees with zero honey or added salt. */
  BABY_6_12M(
    "6-12m Baby (Purees)", "6-12m Baby", "For 6-12m Baby",
    "Mash or blend into a smooth iron-rich puree with warm broth (no honey or added salt).",
  ),

  /** 1–2 year toddler stage requiring soft finger-width strips, crushed seeds, and quartered berries. */
  TODDLER_1_2Y(
    "1-2 yr Toddler (Soft Strips)", "2yo", "For 2yo",
    "Serve soft crustless finger strips, crushed seeds, and quartered berries.",
  ),

  /** 3–4 year preschool stage supporting bite-sized squares and fork-mashed legumes. */
  PRESCHOOL_3_4Y(
    "3-4 yr Preschooler (Bite-Sized)", "3-4yo", "For 3-4yo",
    "Serve as bite-sized self-feeding pieces with mild crunch.",
  ),

  /** 5+ year kid stage supporting full kid portions with brain-boosting toppings. */
  KIDS_5_PLUS(
    "5+ yr Kid (Full Portions)", "5+yo", "For 5+yo",
    "Serve full kid portion with brain-boosting seed and fruit toppings.",
  );

  /**
   * Adapts a recipe's baseline 2yo prep tip to this [ChildGrowthStage] (`EC-08`).
   *
   * @param rawTip Raw `toddlerPrepTip` from [Recipe], or `null` if unspecified.
   * @return Stage-adapted plain-text callout string, or `null` when [rawTip] is blank.
   */
  fun formatPrepTip(rawTip: String?): String? {
    val cleaned = rawTip?.trim().orEmpty()
    if (cleaned.isEmpty()) return null
    // Rewrite age tokens deterministically per active developmental feeding stage.
    return when (this) {
      BABY_6_12M -> "$badgePrefix: $prepGuidance"
      TODDLER_1_2Y -> "$badgePrefix: $cleaned"
      PRESCHOOL_3_4Y -> {
        val adapted = cleaned
          .replace(Regex("2-year-old", RegexOption.IGNORE_CASE), "3-4yo preschooler")
          .replace(Regex("2yo", RegexOption.IGNORE_CASE), "3-4yo")
        "$badgePrefix: Serve as bite-sized self-feeding pieces ($adapted)"
      }
      KIDS_5_PLUS -> {
        val adapted = cleaned.replace(Regex("2-year-old|2yo", RegexOption.IGNORE_CASE), "5+yo")
        "$badgePrefix: Serve full kid portion with extra brain-booster toppings ($adapted)"
      }
    }
  }
}

/**
 * Dynamic life or health event triggering nutrition, serving-scale, and food-safety adaptations.
 *
 * @property eventId Stable identifier for deduplicating active events.
 * @property memberId Target household member ID (`"WIFE"`, `"FRED"`, or `"HOUSEHOLD"`).
 * @property title Descriptive banner title for the Family Profile and plating guide.
 * @property priorityNutrients Key micronutrients emphasized while this event is active.
 * @property safetyPrepRules Mandatory food-safety cooking temperatures and texture rules.
 * @property recommendedIngredientIds Catalog ingredient IDs recommended for Smart Grocery queueing.
 * @property servingsDelta Automatic adjustment added to baseline 4-person servings.
 */
@Serializable
data class HouseholdLifeEvent(
  val eventId: String,
  val memberId: String,
  val title: String,
  val priorityNutrients: List<String> = emptyList(),
  val safetyPrepRules: List<String> = emptyList(),
  val recommendedIngredientIds: List<String> = emptyList(),
  val servingsDelta: Int = 0,
)

/**
 * Five built-in household life and health event presets available as 1-tap toggles in Family Profile.
 *
 * @property eventId Canonical event ID.
 * @property memberId Target member or `"HOUSEHOLD"`.
 * @property badgeLabel Compact chip title for UI toggles.
 * @property title Full descriptive protocol title.
 * @property memberFocusOverride Optional focus goal applied when toggled.
 * @property priorityNutrients Targeted nutrients surfaced on 1-pot plating cards.
 * @property safetyPrepRules Food-safety preparation guardrails (firm yolks, 74C deli, 63C fish).
 * @property recommendedIngredientIds Suggested catalog staple IDs.
 * @property servingsDelta Serving count adjustment (`+1` postpartum, `+2` visiting guests).
 */
@Serializable
enum class HouseholdLifeEventPreset(
  val eventId: String,
  val memberId: String,
  val badgeLabel: String,
  val title: String,
  val memberFocusOverride: String,
  val priorityNutrients: List<String>,
  val safetyPrepRules: List<String>,
  val recommendedIngredientIds: List<String>,
  val servingsDelta: Int,
) {
  // Pregnancy & postpartum presets enforce Listeria/Salmonella heat rules and maternal nutrients.
  /** First trimester folate, choline, and nausea-gentle carbohydrate support with strict food safety. */
  PREGNANCY_TRIMESTER_1(
    eventId = "evt_pregnancy_t1", memberId = "WIFE",
    badgeLabel = "Pregnancy (1st Trimester)",
    title = "Pregnancy - 1st Trimester (Folate, Choline & Nausea-Gentle)",
    memberFocusOverride = "1st Trimester: Folate, Choline, B6 & Citrus Nausea-Gentle Meals",
    priorityNutrients = listOf("Folate (Spinach, Malunggay, Lentils, Avocado)", "Choline (Firm Eggs)", "Vitamin B6 & Steady Carbs"),
    safetyPrepRules = listOf(
      "Cook eggs until yolks and whites are completely firm (no runny yolks)",
      "Heat sliced turkey ham and poultry until steaming hot (74C / 165F)",
      "Choose low-mercury seafood only (Tilapia, Bangus, Salmon, Shrimp) cooked to 63C",
    ),
    recommendedIngredientIds = listOf("ing_spinach", "ing_lentils", "ing_egg", "ing_tangerine", "ing_avocado", "ing_banana"),
    servingsDelta = 0,
  ),

  /** Second and third trimester heme/non-heme iron + Vitamin C, Omega-3 DHA, and calcium protocol. */
  PREGNANCY_TRIMESTER_2_3(
    eventId = "evt_pregnancy_t2_t3", memberId = "WIFE",
    badgeLabel = "Pregnancy (2nd / 3rd Trimester)",
    title = "Pregnancy - 2nd & 3rd Trimester (Iron + Vit C, DHA, Calcium & Protein)",
    memberFocusOverride = "2nd/3rd Trimester: High-Iron + Vit C, Omega-3 DHA, Calcium & Protein",
    priorityNutrients = listOf("Iron + Vitamin C (Poultry, Beans + Calamansi/Citrus)", "Omega-3 DHA & Choline", "Calcium & Protein"),
    safetyPrepRules = listOf(
      "Cook egg yolks firm and heat turkey ham steaming hot (74C Listeria safeguard)",
      "Cook fish and shrimp to 63C opaque flake; wash leafy greens and fruit thoroughly",
    ),
    recommendedIngredientIds = listOf("ing_tilapia", "ing_spinach", "ing_beans", "ing_tangerine", "ing_greek_yogurt", "ing_tofu", "ing_egg"),
    servingsDelta = 0,
  ),

  /** Postpartum recovery and lactation support adding `+1` serving for maternal caloric/nutrient needs. */
  POSTPARTUM_BREASTFEEDING(
    eventId = "evt_postpartum_lactation", memberId = "WIFE",
    badgeLabel = "Postpartum & Breastfeeding (+1 Serving)",
    title = "Postpartum Recovery & Breastfeeding (Lactation Oats, Malunggay, Iron & DHA)",
    memberFocusOverride = "Postpartum & Nursing: Oats, Malunggay, Choline/DHA & Iron Recovery",
    priorityNutrients = listOf("Galactagogue Beta-Glucans (Rolled Oats & Malunggay)", "Choline, Iodine & DHA", "Postpartum Iron Replenishment"),
    safetyPrepRules = listOf("Prioritize warm nutrient-dense bowls and extra hydration alongside every meal"),
    recommendedIngredientIds = listOf("ing_oats", "ing_egg", "ing_shrimp", "ing_chicken", "ing_spinach", "ing_greek_yogurt"),
    servingsDelta = 1,
  ),

  /** Household-wide high-iron and Vitamin-C pairing protocol maximizing non-heme iron absorption. */
  HIGH_IRON_IMMUNITY(
    eventId = "evt_iron_immunity_protocol", memberId = "HOUSEHOLD",
    badgeLabel = "High-Iron & Immunity Boost",
    title = "High-Iron & Vitamin-C Immunity Protocol (2x Iron Absorption)",
    memberFocusOverride = "High-Iron + Vitamin C Synergy (2x Iron Absorption)",
    priorityNutrients = listOf("Iron-Rich Proteins & Legumes", "Vitamin C Co-Factors (Calamansi, Citrus, Bell Peppers, Tomatoes)"),
    safetyPrepRules = listOf("Pair iron-rich mains with fresh calamansi/citrus, bell pepper, or pineapple to double iron uptake"),
    recommendedIngredientIds = listOf("ing_lentils", "ing_beans", "ing_spinach", "ing_tangerine", "ing_pineapple", "ing_bell_pepper"),
    servingsDelta = 0,
  ),

  // Visiting guests scales 4-serving recipes to 6 servings so whole-package rounding adapts automatically.
  /** Visiting grandparents or guests mode scaling 4-person meals up to 6 servings automatically. */
  VISITING_FAMILY_GUESTS(
    eventId = "evt_visiting_family_guests", memberId = "HOUSEHOLD",
    badgeLabel = "Visiting Family / Guests (+2 Servings)",
    title = "Visiting Grandparents / Extended Family (+2 Adult Servings)",
    memberFocusOverride = "",
    priorityNutrients = listOf("Scales 4-serving family meals up to 6 servings for accurate package rounding"),
    safetyPrepRules = listOf("Auto-scales planned meal servings from 4 to 6 across the weekly board"),
    recommendedIngredientIds = listOf("ing_rice", "ing_chicken", "ing_fish", "ing_cassava", "ing_plantain"),
    servingsDelta = 2,
  );

  /** Converts this static preset into a serializable [HouseholdLifeEvent] instance. */
  fun toLifeEvent(): HouseholdLifeEvent =
    HouseholdLifeEvent(eventId, memberId, title, priorityNutrients, safetyPrepRules, recommendedIngredientIds, servingsDelta)
}

/**
 * Per-member nutrition and growth goal specification driving 1-pot 4-way family plating.
 *
 * @property memberId Stable member key (`"FRED"`, `"WIFE"`, `"OLDER_KID"`, `"YOUNGEST"`).
 * @property memberName Display name rendered in plating guides and Family Profile cards.
 * @property focusGoal Editable nutrition/athletic/developmental focus text.
 * @property ageYears Optional age in years (`4..17` for school kid, adult ages for parents).
 * @property growthStage Optional feeding stage for the youngest child.
 */
@Serializable
data class FamilyMemberGoalSpec(
  val memberId: String,
  val memberName: String,
  val focusGoal: String,
  val ageYears: Int? = null,
  val growthStage: ChildGrowthStage? = null,
)

/**
 * Consumer-ready household onboarding presets allowing SavorAI to serve both the default
 * Family-of-4 (2 parents + 8yo + 2yo) and arbitrary consumer household sizes out of the box.
 *
 * @property displayLabel Human-readable preset title for onboarding and Family Profile sheets.
 * @property defaultServings Baseline meal servings scheduled across the 21-slot board.
 * @property defaultMorningCapMinutes Active weekday morning breakfast cap in minutes.
 * @property defaultYoungestStage Default child texture-safety stage (when children are present).
 */
@Serializable
enum class ConsumerHouseholdTemplate(
  val displayLabel: String,
  val defaultServings: Int,
  val defaultMorningCapMinutes: Int,
  val defaultYoungestStage: ChildGrowthStage,
) {
  // Default profile matching 2 parents + school-age kid (8yo) + toddler (2yo).
  /** Busy 4-person family with a school-age child and a toddler (`4` servings, `<= 25m` mornings). */
  FAMILY_TWO_KIDS("Family of 4 (2 Parents + 2 Kids)", 4, 25, ChildGrowthStage.TODDLER_1_2Y),

  /** New parents introducing iron-rich first purees (`3` servings, `6–12m` baby safety rules). */
  PARENTS_AND_BABY("Young Family (2 Parents + Baby)", 3, 20, ChildGrowthStage.BABY_6_12M),

  /** Two-adult household focused on athletic recovery, satiety, and zero food waste. */
  ACTIVE_COUPLE("Active Couple (2 Adults)", 2, 25, ChildGrowthStage.KIDS_5_PLUS),

  /** Single-cook household cooking `2`-serving batches with built-in next-day lunch leftovers. */
  SOLO_COOK("Solo Cook (1 Adult + Lunch Leftovers)", 2, 20, ChildGrowthStage.KIDS_5_PLUS),

  /** Larger or multi-generational household scaling one-pot meals to `6` baseline servings. */
  LARGE_MULTIGEN_FAMILY("Large / Multi-Gen Family (6 Servings)", 6, 25, ChildGrowthStage.PRESCHOOL_3_4Y),
}

/**
 * Complete persisted household dietary, allergen, growth-stage, life-event, currency, and
 * market-region configuration.
 *
 * Defaults out-of-the-box to a Family of 4 (`2` parents + `8yo` + `2yo`) shopping in
 * **Antipolo & Marikina, PH (`PHP`, `₱`)**, while supporting any consumer household size and
 * currency via [fromConsumerTemplate].
 *
 * @property householdId Stable household identifier.
 * @property youngestStage Active feeding/safety growth stage for the youngest child (`EC-08`).
 * @property olderKidAgeYears Editable age (`4..17`) for the older school-age child.
 * @property fredMorningCapMinutes Hard weekday morning prep+cook cap (`25` minutes per `EC-01`).
 * @property baseHouseholdServings Baseline family serving count (`4` for 2 adults + 8yo + 2yo).
 * @property members Editable member goal specifications.
 * @property householdAllergens Active allergen exclusions enforced by `AvailabilityFilter` (`EC-02`).
 * @property dietaryPreferences Household culinary preferences (e.g., `"No Added Sugar"`).
 * @property discoveredIngredients Newly discovered and loved local market ingredients.
 * @property activeLifeEvents Currently active life or health event adaptations.
 * @property householdName Customizable household kitchen banner title for consumer onboarding.
 * @property morningLeadName Customizable display name for the morning breakfast cook.
 * @property eveningLeadName Customizable display name for the midday/evening cook.
 * @property activeTemplate Selected consumer household preset.
 * @property preferredCuisines Household cuisine affinities guiding recipe discovery.
 * @property currency Active household currency (`HouseholdCurrency.PHP` `₱` default).
 * @property marketRegionLabel Local wet-market and supermarket sourcing region label.
 */
@Serializable
data class HouseholdProfile(
  val householdId: String = "household_family_4",
  val youngestStage: ChildGrowthStage = ChildGrowthStage.TODDLER_1_2Y,
  val olderKidAgeYears: Int = 8,
  val fredMorningCapMinutes: Int = 25,
  val baseHouseholdServings: Int = 4,
  val members: List<FamilyMemberGoalSpec> = defaultFamilyMembers(youngestStage),
  val householdAllergens: List<String> = emptyList(),
  val dietaryPreferences: List<String> = listOf("Kids Brain Boosters", "No Added Sugar", "Mild Spice for Kids"),
  val discoveredIngredients: List<String> = listOf("Carabao Mango", "Local Rizal Avocado", "Antipolo Roasted Kasuy"),
  val activeLifeEvents: List<HouseholdLifeEvent> = emptyList(),
  val householdName: String = "Our Family Kitchen",
  val morningLeadName: String = "Fred",
  val eveningLeadName: String = "Wife",
  val activeTemplate: ConsumerHouseholdTemplate = ConsumerHouseholdTemplate.FAMILY_TWO_KIDS,
  val preferredCuisines: List<String> = listOf(
    "Beninese & West African",
    "Filipino & Antipolo-Marikina Local",
    "French Bistro",
    "Mediterranean Express",
  ),
  val currency: HouseholdCurrency = HouseholdCurrency.PHP,
  val marketRegionLabel: String = "Antipolo & Marikina, PH",
) {
  // Dynamically adds life-event deltas (+1 postpartum, +2 guests) to baseline household servings.
  /** Effective serving count summing [baseHouseholdServings] with active life-event adjustments. */
  val effectiveTargetServings: Int
    get() = (baseHouseholdServings + activeLifeEvents.sumOf { it.servingsDelta }).coerceAtLeast(1)

  /** Resolves a consumer-friendly cook label for [owner] using custom household lead names. */
  fun displayLabelForCookOwner(owner: CookOwner): String =
    when (owner) {
      CookOwner.FRED -> morningLeadName.ifBlank { "Morning Cook" }
      CookOwner.WIFE -> eveningLeadName.ifBlank { "Evening Cook" }
      CookOwner.SHARED -> "Shared Kitchen"
    }

  /** Formats [amount] in this household's active [currency] (`₱` by default). */
  fun formatMoney(amount: Double, includeDecimals: Boolean = false): String =
    currency.formatAmount(amount = amount, includeDecimals = includeDecimals)

  companion object {
    /**
     * Builds the default Family-of-4 member goal list (`Fred`, `Wife`, `8yo`, `Youngest`),
     * stamping [youngestStage] onto `"YOUNGEST"` for consistent 1-pot safety plating.
     */
    fun defaultFamilyMembers(
      youngestStage: ChildGrowthStage = ChildGrowthStage.TODDLER_1_2Y,
    ): List<FamilyMemberGoalSpec> = listOf(
      FamilyMemberGoalSpec("FRED", "Fred (Dad)", "Muscle Building (Hypertrophy) & <=25m Morning Cap", ageYears = 36),
      FamilyMemberGoalSpec("WIFE", "Wife (Mom)", "Lean Weight-Loss, High Satiety & Double Greens", ageYears = 34),
      FamilyMemberGoalSpec("OLDER_KID", "8yo School Kid", "Kids Brain & Muscle Development (Choline, DHA & Protein)", ageYears = 8),
      FamilyMemberGoalSpec("YOUNGEST", "Youngest Child", "Youngest Brain & Muscle Growth + Safe Textures", growthStage = youngestStage),
    )

    /**
     * Constructs a [HouseholdProfile] tailored to any consumer household [template] while keeping
     * stable member keys for deterministic 1-pot plating.
     *
     * @param template Selected consumer household size and life-stage preset.
     * @param householdName Custom kitchen display name.
     * @param morningLeadName Display name for the weekday breakfast lead.
     * @param eveningLeadName Display name for the lunch/dinner lead.
     * @param currency Selected household currency (`PHP` default).
     * @param marketRegionLabel Local market region label.
     * @return Initialized [HouseholdProfile].
     */
    fun fromConsumerTemplate(
      template: ConsumerHouseholdTemplate,
      householdName: String = "Our Kitchen",
      morningLeadName: String = "Morning Lead",
      eveningLeadName: String = "Evening Lead",
      currency: HouseholdCurrency = HouseholdCurrency.PHP,
      marketRegionLabel: String = "Antipolo & Marikina, PH",
    ): HouseholdProfile {
      val members = when (template) {
        ConsumerHouseholdTemplate.SOLO_COOK -> listOf(
          FamilyMemberGoalSpec("FRED", morningLeadName, "High-Protein Balanced Meals & Zero Food Waste", ageYears = 32),
        )
        ConsumerHouseholdTemplate.ACTIVE_COUPLE -> listOf(
          FamilyMemberGoalSpec("FRED", morningLeadName, "Athletic Hypertrophy & <=25m Morning Cap", ageYears = 34),
          FamilyMemberGoalSpec("WIFE", eveningLeadName, "Lean Satiety, Double Greens & Micronutrients", ageYears = 32),
        )
        ConsumerHouseholdTemplate.PARENTS_AND_BABY -> listOf(
          FamilyMemberGoalSpec("FRED", morningLeadName, "Steady Energy & <=20m Express Mornings", ageYears = 33),
          FamilyMemberGoalSpec("WIFE", eveningLeadName, "Postpartum Iron, DHA & Nutrient Density", ageYears = 31),
          FamilyMemberGoalSpec("YOUNGEST", "Baby (6-12m)", "Iron-Rich First Purees & Safe Textures", growthStage = ChildGrowthStage.BABY_6_12M),
        )
        ConsumerHouseholdTemplate.FAMILY_TWO_KIDS -> listOf(
          FamilyMemberGoalSpec("FRED", morningLeadName, "Muscle Building (Hypertrophy) & <=25m Morning Cap", ageYears = 36),
          FamilyMemberGoalSpec("WIFE", eveningLeadName, "Lean Weight-Loss, High Satiety & Double Greens", ageYears = 34),
          FamilyMemberGoalSpec("OLDER_KID", "8yo School Kid", "Kids Brain & Muscle Development (Choline, DHA & Protein)", ageYears = 8),
          FamilyMemberGoalSpec("YOUNGEST", "2yo Toddler", "Youngest Brain & Muscle Growth + Safe Textures", growthStage = ChildGrowthStage.TODDLER_1_2Y),
        )
        ConsumerHouseholdTemplate.LARGE_MULTIGEN_FAMILY -> listOf(
          FamilyMemberGoalSpec("FRED", morningLeadName, "Active Strength & Hearty Portions", ageYears = 38),
          FamilyMemberGoalSpec("WIFE", eveningLeadName, "Balanced Whole-Food Satiety & Greens", ageYears = 36),
          FamilyMemberGoalSpec("OLDER_KID", "School-Age Kids", "Brain Focus (Choline, Omega-3 DHA & Iron)", ageYears = 9),
          FamilyMemberGoalSpec("YOUNGEST", "Preschooler", "Bite-Sized Growth Portions", growthStage = ChildGrowthStage.PRESCHOOL_3_4Y),
        )
      }
      return HouseholdProfile(
        householdId = "household_${template.name.lowercase()}",
        youngestStage = template.defaultYoungestStage,
        fredMorningCapMinutes = template.defaultMorningCapMinutes,
        baseHouseholdServings = template.defaultServings,
        members = members,
        householdName = householdName,
        morningLeadName = morningLeadName,
        eveningLeadName = eveningLeadName,
        activeTemplate = template,
        currency = currency,
        marketRegionLabel = marketRegionLabel,
      )
    }
  }
}

// Local market presets grounded in Antipolo Palengke, Marikina Public Market, SM Marikina, and
// Robinsons Antipolo (PHP ₱ pricing). Every preset represents a single distinct culinary ingredient
// while preserving ing_kale as the first non-spinach LEAFY_GREENS candidate for EC-03 parity.
/**
 * Curated Antipolo–Marikina local wet-market & supermarket single-ingredient presets (`PHP` `₱`
 * priced) surfaced in Smart Pantry, Smart Grocery, and Custom Meal sheets.
 */
val LOCAL_MARKET_PRESET_INGREDIENTS: List<IngredientCatalogItem> = listOf(
  // 1. Lean turkey breast in 500g packs at SM Marikina / Robinsons Antipolo.
  IngredientCatalogItem("ing_turkey_breast", "Lean Turkey Breast", IngredientCategory.PROTEIN, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 500.0, estimatedPackagePriceUsd = 265.0),
  // 2. Sliced turkey ham in 400g deli packs (heated to 74C when pregnancy life events are active).
  IngredientCatalogItem("ing_turkey_ham", "Sliced Deli Turkey Ham", IngredientCategory.PROTEIN, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 400.0, estimatedPackagePriceUsd = 215.0),
  // 3. Fresh Marikina Public Market Suahe / Hipon shrimp in 500g packs tagged SHELLFISH.
  IngredientCatalogItem("ing_shrimp", "Fresh Suahe Shrimp (Hipon)", IngredientCategory.PROTEIN, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 500.0, allergens = listOf("SHELLFISH"), estimatedPackagePriceUsd = 240.0),
  // 4. Fresh Rizal / Laguna Tilapia fillets in 600g packs tagged FISH.
  IngredientCatalogItem("ing_tilapia", "Fresh Tilapia Fillets", IngredientCategory.PROTEIN, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 600.0, allergens = listOf("FISH"), estimatedPackagePriceUsd = 135.0),
  // 5. Boneless Dagupan Bangus (Milkfish) in 600g packs at Marikina / Antipolo wet markets.
  IngredientCatalogItem("ing_bangus", "Boneless Dagupan Bangus (Milkfish)", IngredientCategory.PROTEIN, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 600.0, allergens = listOf("FISH"), estimatedPackagePriceUsd = 145.0),
  // 6. Fresh Marikina Tokwa (Firm Tofu) in 400g blocks tagged SOY.
  IngredientCatalogItem("ing_tofu", "Fresh Firm Tokwa (Tofu)", IngredientCategory.PROTEIN, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 400.0, allergens = listOf("SOY"), estimatedPackagePriceUsd = 65.0),
  // 7. Split red lentils in 1000g packs supporting quick folate and iron stews.
  IngredientCatalogItem("ing_lentils", "Red Lentils", IngredientCategory.PROTEIN, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 1000.0, defaultStorageLocation = StorageLocation.PANTRY, defaultShelfLifeDays = 365, estimatedPackagePriceUsd = 140.0),
  // 8. Whole green monggo beans in 500g dry packs for Friday Ginisang Monggo.
  IngredientCatalogItem("ing_monggo_beans", "Green Monggo Beans", IngredientCategory.PROTEIN, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 500.0, defaultStorageLocation = StorageLocation.PANTRY, defaultShelfLifeDays = 365, estimatedPackagePriceUsd = 75.0),
  // 9. Curly kale in 300g highland packs enrolled in LEAFY_GREENS for deterministic EC-03 swaps.
  IngredientCatalogItem("ing_kale", "Baguio Curly Kale", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 300.0, substituteGroupIds = listOf("LEAFY_GREENS"), estimatedPackagePriceUsd = 110.0),
  // 10. Fresh Malunggay (moringa) leaves in 300g bundles enrolled in LEAFY_GREENS.
  IngredientCatalogItem("ing_malunggay_pechay", "Fresh Malunggay Leaves", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 300.0, substituteGroupIds = listOf("LEAFY_GREENS"), estimatedPackagePriceUsd = 45.0),
  // 11. Crisp white-stemmed Pechay Tagalog in 250g bundles enrolled in LEAFY_GREENS.
  IngredientCatalogItem("ing_pechay", "Fresh Pechay", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 250.0, substituteGroupIds = listOf("LEAFY_GREENS"), defaultStorageLocation = StorageLocation.FRIDGE, defaultShelfLifeDays = 4, estimatedPackagePriceUsd = 50.0),
  // 12. Tender Kangkong (water spinach) in 250g bundles enrolled in LEAFY_GREENS.
  IngredientCatalogItem("ing_kangkong", "Fresh Kangkong", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 250.0, substituteGroupIds = listOf("LEAFY_GREENS"), defaultStorageLocation = StorageLocation.FRIDGE, defaultShelfLifeDays = 4, estimatedPackagePriceUsd = 45.0),
  // 13. Fresh baby spinach in 300g washed packs matching seed_catalog.json 1:1.
  IngredientCatalogItem("ing_spinach", "Fresh Baby Spinach", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 300.0, substituteGroupIds = listOf("LEAFY_GREENS"), estimatedPackagePriceUsd = 85.0),
  // 14. Fresh Rizal Kamoteng Kahoy (Cassava Tuber) in 1000g packs.
  IngredientCatalogItem("ing_cassava", "Fresh Cassava (Kamoteng Kahoy)", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 1000.0, estimatedPackagePriceUsd = 65.0),
  // 15. Orange Kamote (Sweet Potatoes) in 1500g cool-pantry packs.
  IngredientCatalogItem("ing_sweet_potato", "Orange Kamote (Sweet Potatoes)", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 1500.0, defaultStorageLocation = StorageLocation.PANTRY, estimatedPackagePriceUsd = 120.0),
  // 16. Ripe Saba cooking bananas in 6-count bunches at Antipolo Palengke.
  IngredientCatalogItem("ing_saba_banana", "Ripe Saba Bananas", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.COUNT, 6.0, defaultStorageLocation = StorageLocation.PANTRY, defaultShelfLifeDays = 6, estimatedPackagePriceUsd = 75.0),
  // 17. Fresh Calamansi citrus in 12-count net bags for marinades and iron absorption.
  IngredientCatalogItem("ing_calamansi", "Fresh Calamansi Citrus", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.COUNT, 12.0, estimatedPackagePriceUsd = 35.0),
  // 18. Fresh Gata (Pure Pressed Coconut Milk) in 400ml packs for stews and rice bowls.
  IngredientCatalogItem("ing_gata", "Fresh Gata (Coconut Milk)", IngredientCategory.PANTRY_STAPLE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.MILLILITER, 400.0, defaultStorageLocation = StorageLocation.FRIDGE, defaultShelfLifeDays = 4, estimatedPackagePriceUsd = 75.0),
  // 19. Sweet Carabao Mango in 1000g packs enrolled in FRUIT_TOPPING.
  IngredientCatalogItem("ing_carabao_mango", "Guimaras Carabao Mango", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 1000.0, substituteGroupIds = listOf("FRUIT_TOPPING"), estimatedPackagePriceUsd = 175.0),
  // 20. Juicy Valencia oranges in 1000g bags enrolled in FRUIT_TOPPING.
  IngredientCatalogItem("ing_orange", "Sweet Valencia Oranges", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 1000.0, substituteGroupIds = listOf("FRUIT_TOPPING"), estimatedPackagePriceUsd = 140.0),
  // 21. Easy-peel Ponkan mandarins in 8-count bags enrolled in FRUIT_TOPPING.
  IngredientCatalogItem("ing_ponkan", "Sweet Ponkan Mandarins", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.COUNT, 8.0, substituteGroupIds = listOf("FRUIT_TOPPING"), defaultStorageLocation = StorageLocation.PANTRY, defaultShelfLifeDays = 10, estimatedPackagePriceUsd = 135.0),
  // 22. Aromatic local green Dalandan citrus in 800g bags enrolled in FRUIT_TOPPING.
  IngredientCatalogItem("ing_dalandan", "Fresh Dalandan Citrus", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 800.0, substituteGroupIds = listOf("FRUIT_TOPPING"), defaultStorageLocation = StorageLocation.FRIDGE, defaultShelfLifeDays = 10, estimatedPackagePriceUsd = 110.0),
  // 23. Sweet seedless tangerines in 8-count chilled packs enrolled in FRUIT_TOPPING.
  IngredientCatalogItem("ing_tangerine", "Sweet Tangerines", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.COUNT, 8.0, substituteGroupIds = listOf("FRUIT_TOPPING"), defaultStorageLocation = StorageLocation.FRIDGE, defaultShelfLifeDays = 10, estimatedPackagePriceUsd = 140.0),
  // 24. Tagaytay / Bukidnon Sweet Pineapple in 1000g units enrolled in FRUIT_TOPPING.
  IngredientCatalogItem("ing_pineapple", "Sweet Tagaytay Pineapple", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 1000.0, substituteGroupIds = listOf("FRUIT_TOPPING"), estimatedPackagePriceUsd = 95.0),
  // 25. Fresh Rizal Pitaya (Dragon Fruit) in 600g packs enrolled in FRUIT_TOPPING.
  IngredientCatalogItem("ing_dragon_fruit", "Fresh Red Dragon Fruit", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 600.0, substituteGroupIds = listOf("FRUIT_TOPPING"), estimatedPackagePriceUsd = 130.0),
  // 26. Nut-free roasted pumpkin seeds (pepitas) in 500g packs enrolled in NUT_SEED_TOPPING.
  IngredientCatalogItem("ing_pumpkin_seeds", "Roasted Pumpkin Seeds", IngredientCategory.PANTRY_STAPLE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 500.0, substituteGroupIds = listOf("NUT_SEED_TOPPING"), defaultStorageLocation = StorageLocation.PANTRY, defaultShelfLifeDays = 180, estimatedPackagePriceUsd = 260.0),
)

/**
 * Pure deterministic output of [migrateUntangledCatalogData] when upgrading a persisted
 * `kitchen_snapshot_v1.json` from legacy bundled ingredient names to single-ingredient entries.
 *
 * @property ingredients Upgraded catalog list preserving custom items and `isLocallyAvailable` flags.
 * @property pantryEntries Upgraded pantry ledger preserving user quantities and adding 0-stock seed partners.
 * @property recipes Upgraded recipe list stamping `NUT_SEED_TOPPING` onto `rec_05_skillet_honey_oat_granola`.
 * @property didMutate `true` if and only if at least one catalog, pantry, or recipe record changed.
 */
@Serializable
data class UntangledCatalogMigration(
  val ingredients: List<IngredientCatalogItem>,
  val pantryEntries: List<PantryLedgerEntry>,
  val recipes: List<Recipe>,
  val didMutate: Boolean,
)

// Detect legacy compound display labels ("Comte & Cheddar", "Whole Fresh Tilapia / Snapper") while
// leaving synthetic test fixtures ("Bulk Perishable 1") and custom user items untouched.
private fun hasLegacyBundledDisplayName(displayName: String): Boolean =
  displayName.contains(" & ") || displayName.contains(" / ")

/**
 * Pure domain migration upgrading persisted household snapshots from legacy bundled ingredient
 * labels (`" & "` / `" / "`) to distinct single-ingredient catalog definitions without altering
 * existing stock balances, `isLocallyAvailable` flags, or custom `ing_custom_*` / `ing_disc_*` items.
 *
 * Missing canonical [seedCatalog] partner items are appended at `0.0` (`OUT`) stock **only** when
 * [ingredients] already contains canonical seed identifiers (`"ing_spinach"`), ensuring synthetic
 * unit-test catalogs (`ing_bulk_*`) are never mutated on reload.
 *
 * @param ingredients Persisted catalog definitions from `KitchenSnapshot.ingredients`.
 * @param pantryEntries Persisted stock records from `KitchenSnapshot.pantryEntries`.
 * @param recipes Persisted recipe definitions from `KitchenSnapshot.recipes`.
 * @param seedCatalog Current bundled `seed_catalog.json` dataset.
 * @return Immutable [UntangledCatalogMigration] indicating whether an atomic disk rewrite is needed.
 */
fun migrateUntangledCatalogData(
  ingredients: List<IngredientCatalogItem>,
  pantryEntries: List<PantryLedgerEntry>,
  recipes: List<Recipe>,
  seedCatalog: SeedCatalogDataset,
): UntangledCatalogMigration {
  // Layer discovery presets, local market presets, and canonical seed items (seed takes top precedence).
  val untangledById = LinkedHashMap<String, IngredientCatalogItem>()
  for (candidate in CURATED_BRAIN_BOOSTER_CANDIDATES) {
    untangledById[candidate.item.ingredientId] = candidate.item
  }
  // Support legacy pre-untangle AI discovery IDs if previously queued onto a persisted snapshot.
  LEGACY_DISCOVERY_UNTANGLED_LABELS.forEach { (legacyId, cleanName) ->
    val existing = ingredients.find { it.ingredientId == legacyId }
    if (existing != null) {
      untangledById[legacyId] = existing.copy(displayName = cleanName)
    }
  }
  for (preset in LOCAL_MARKET_PRESET_INGREDIENTS) {
    untangledById[preset.ingredientId] = preset
  }
  for (seedItem in seedCatalog.ingredients) {
    untangledById[seedItem.ingredientId] = seedItem
  }

  var didMutate = false

  // Step 1: Upgrade existing items ONLY when they still carry a legacy bundled display name (or
  // lack the new NUT_SEED_TOPPING group on ing_mixed_nuts_seeds), preserving isLocallyAvailable.
  val upgradedExistingIngredients = ingredients.map { item ->
    val canonical = untangledById[item.ingredientId]
    val needsNutSeedGroup =
      item.ingredientId == "ing_mixed_nuts_seeds" && "NUT_SEED_TOPPING" !in item.substituteGroupIds
    when {
      canonical != null && (hasLegacyBundledDisplayName(item.displayName) || needsNutSeedGroup) -> {
        val updated = item.copy(
          displayName = canonical.displayName,
          allergens = canonical.allergens,
          substituteGroupIds = canonical.substituteGroupIds,
          optimalPrepHint = canonical.optimalPrepHint ?: item.optimalPrepHint,
        )
        if (updated != item) {
          didMutate = true
        }
        updated
      }
      else -> item
    }
  }

  // Step 2: Append newly split canonical seed partner items at 0-stock ONLY on real canonical catalogs.
  val existingIds = upgradedExistingIngredients.map { it.ingredientId }.toSet()
  val isCanonicalHouseholdCatalog = "ing_spinach" in existingIds
  val missingSeedItems = when {
    isCanonicalHouseholdCatalog ->
      seedCatalog.ingredients.filter { seedItem -> seedItem.ingredientId !in existingIds }
    else -> emptyList()
  }

  val finalIngredients = when {
    missingSeedItems.isEmpty() -> upgradedExistingIngredients
    else -> {
      didMutate = true
      upgradedExistingIngredients + missingSeedItems
    }
  }

  val existingPantryIds = pantryEntries.map { it.ingredientId }.toSet()
  val missingZeroStockEntries = missingSeedItems
    .filter { seedItem -> seedItem.ingredientId !in existingPantryIds }
    .map { seedItem ->
      PantryLedgerEntry(
        ingredientId = seedItem.ingredientId,
        tier = seedItem.tier,
        currentQuantity = 0.0,
        stapleStatus = StapleStatus.OUT,
        isOutOfStock = true,
        storageLocation = seedItem.defaultStorageLocation,
        expiresInDays = null,
      )
    }
  val finalPantryEntries = when {
    missingZeroStockEntries.isEmpty() -> pantryEntries
    else -> {
      didMutate = true
      pantryEntries + missingZeroStockEntries
    }
  }

  // Step 3: Enroll rec_05_skillet_honey_oat_granola's ing_mixed_nuts_seeds BOM in NUT_SEED_TOPPING.
  val finalRecipes = recipes.map { recipe ->
    when {
      recipe.recipeId != "rec_05_skillet_honey_oat_granola" -> recipe
      else -> {
        var recipeChanged = false
        val updatedBom = recipe.bomLines.map { line ->
          when {
            line.ingredientId == "ing_mixed_nuts_seeds" && line.substituteGroupId == null -> {
              recipeChanged = true
              line.copy(substituteGroupId = "NUT_SEED_TOPPING")
            }
            else -> line
          }
        }
        when {
          recipeChanged -> {
            didMutate = true
            recipe.copy(bomLines = updatedBom)
          }
          else -> recipe
        }
      }
    }
  }

  return UntangledCatalogMigration(
    ingredients = finalIngredients,
    pantryEntries = finalPantryEntries,
    recipes = finalRecipes,
    didMutate = didMutate,
  )
}

// Legacy AI Chef discovery IDs mapped to single-ingredient titles for seamless snapshot upgrades.
private val LEGACY_DISCOVERY_UNTANGLED_LABELS: Map<String, String> = mapOf(
  "ing_chia_flax_seeds" to "Organic Chia Seeds",
  "ing_wild_salmon" to "Sardine Fillets in Olive Oil",
  "ing_walnut_butter" to "Smooth Almond Butter",
  "ing_dark_cocoa" to "Unsweetened Tablea Cacao",
)
