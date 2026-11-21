package com.familykitchen.board.domain

import kotlinx.serialization.Serializable

// Pure domain models, staple presets, and O(M) batch reducer for SavorAI's First-Run Experience.
// Strictly adheres to the 100% pure domain/ invariant (Kotlin stdlib + kotlinx.serialization only).

/**
 * Starting pantry stock configuration selected during Step 3 of SavorAI's first-run setup.
 *
 * @property displayLabel Human-readable title displayed on the tactile mode selection card.
 * @property subtitle Concise explanation of how initial pantry stock and grocery diffs behave.
 */
@Serializable
enum class StartingPantryMode(
  val displayLabel: String,
  val subtitle: String,
) {
  // Default low-friction path stocking 1 package of checked everyday basics in ~10 seconds.
  /** Stocks checked everyday kitchen staples while queueing remaining weekly needs on Grocery List. */
  CURATED_BASICS(
    "Quick-Check Kitchen Basics",
    "Tap the everyday staples you already have at home — ready in 10 seconds.",
  ),

  // Zero-stock path for households preferring to scan receipts/fridges or shop from scratch.
  /** Initializes all catalog items at zero stock (`OUT`) ready for scans or grocery restocks. */
  EMPTY_KITCHEN(
    "Start Fresh (Empty Pantry)",
    "Begin with 0 stock so every item comes from your own scans and shopping.",
  ),

  // Full 38-item demonstration kitchen including 3 expiring items for instant feature exploration.
  /** Pre-stocks the canonical 38-item kitchen (with 3 expiring items) for immediate exploration. */
  SAMPLE_DEMO_KITCHEN(
    "Explore Sample Kitchen",
    "Pre-stocked 38-item kitchen with 3 expiring items to try every feature immediately.",
  ),
}

/**
 * Checkable everyday kitchen staple chip surfaced when [StartingPantryMode.CURATED_BASICS] is active.
 *
 * @property ingredientId Canonical `seed_catalog.json` ingredient identifier.
 * @property shortLabel Compact chip label safe for `1.35x` system font scaling.
 * @property categoryHint Short storage or aisle badge (`"Fridge"`, `"Grains"`, `"Staple"`, etc.).
 * @property allergens Uppercase allergen tags (`"EGGS"`, `"DAIRY"`, `"GLUTEN"`) auto-filtered by Step 1.
 */
@Serializable
data class OnboardingStaplePreset(
  val ingredientId: String,
  val shortLabel: String,
  val categoryHint: String,
  val allergens: Set<String> = emptySet(),
)

/**
 * Sixteen canonical `seed_catalog.json` everyday basics offered as 1-tap chips during first-run setup.
 */
val DEFAULT_ONBOARDING_STAPLE_PRESETS: List<OnboardingStaplePreset> = listOf(
  OnboardingStaplePreset("ing_egg", "Farm Eggs", "Fridge", setOf("EGGS")),
  OnboardingStaplePreset("ing_rice", "Jasmine Rice", "Grains"),
  OnboardingStaplePreset("ing_olive_oil", "Olive Oil", "Staple"),
  OnboardingStaplePreset("ing_garlic", "Fresh Garlic", "Staple"),
  OnboardingStaplePreset("ing_onion", "Cooking Onions", "Produce"),
  OnboardingStaplePreset("ing_salt", "Sea Salt", "Staple"),
  OnboardingStaplePreset("ing_black_pepper", "Black Pepper", "Staple"),
  OnboardingStaplePreset("ing_milk", "Fresh Milk", "Fridge", setOf("DAIRY")),
  OnboardingStaplePreset("ing_butter", "Butter", "Fridge", setOf("DAIRY")),
  OnboardingStaplePreset("ing_oats", "Rolled Oats", "Grains"),
  OnboardingStaplePreset("ing_pasta", "Penne Pasta", "Grains", setOf("GLUTEN")),
  OnboardingStaplePreset("ing_chicken", "Chicken", "Protein"),
  OnboardingStaplePreset("ing_banana", "Ripe Bananas", "Produce"),
  OnboardingStaplePreset("ing_tomato", "Tomatoes", "Produce"),
  OnboardingStaplePreset("ing_lemon", "Fresh Lemons", "Produce"),
  OnboardingStaplePreset("ing_cinnamon", "Cinnamon", "Staple"),
)

/**
 * Default pre-checked subset of 10 universal kitchen basics for [StartingPantryMode.CURATED_BASICS].
 */
val DEFAULT_SELECTED_BASIC_INGREDIENT_IDS: Set<String> = setOf(
  "ing_egg",
  "ing_rice",
  "ing_olive_oil",
  "ing_garlic",
  "ing_onion",
  "ing_salt",
  "ing_black_pepper",
  "ing_milk",
  "ing_chicken",
  "ing_banana",
)

/**
 * Returns the default neighborhood/wet-market sourcing label paired with [currency].
 *
 * @param currency Selected household display currency.
 * @return Consumer-friendly regional market label.
 */
fun defaultMarketRegionForCurrency(currency: HouseholdCurrency): String =
  // Keep regional defaults aligned with each currency's culinary market presets.
  when (currency) {
    HouseholdCurrency.PHP -> "Antipolo & Marikina, PH"
    HouseholdCurrency.USD -> "Local Neighborhood Market, US"
    HouseholdCurrency.EUR -> "Marché & Local Grocer, EU"
    HouseholdCurrency.GBP -> "High Street & Local Grocer, UK"
    HouseholdCurrency.XOF -> "Cotonou & Neighborhood Market"
  }

/**
 * Immutable 4-step user onboarding draft committed atomically when entering the kitchen.
 *
 * @property householdName Custom kitchen banner title (`"Our Family Kitchen"` default).
 * @property template Household size and rhythm preset (`FAMILY_TWO_KIDS` default).
 * @property morningLeadName Display name for the morning breakfast cook.
 * @property eveningLeadName Display name for the midday and evening cook.
 * @property olderKidAgeYears Age (`4..17`) of the older school-age child when applicable.
 * @property youngestStage Texture and choking-safety feeding stage (`EC-08`) when children are present.
 * @property selectedAllergens Normalized uppercase allergen exclusions (`EC-02`).
 * @property currency Selected household budgeting currency (`PHP` `₱` default).
 * @property marketRegionLabel Local wet-market or neighborhood grocer label.
 * @property pantryMode Selected starting pantry mode (`CURATED_BASICS` default).
 * @property selectedBasicIngredientIds Checked `ingredientId`s stocked when [pantryMode] is `CURATED_BASICS`.
 */
@Serializable
data class FirstRunOnboardingDraft(
  val householdName: String = "Our Family Kitchen",
  val template: ConsumerHouseholdTemplate = ConsumerHouseholdTemplate.FAMILY_TWO_KIDS,
  val morningLeadName: String = "Morning Cook",
  val eveningLeadName: String = "Evening Cook",
  val olderKidAgeYears: Int = 8,
  val youngestStage: ChildGrowthStage = ChildGrowthStage.TODDLER_1_2Y,
  val selectedAllergens: Set<String> = emptySet(),
  val currency: HouseholdCurrency = HouseholdCurrency.PHP,
  val marketRegionLabel: String = "Antipolo & Marikina, PH",
  val pantryMode: StartingPantryMode = StartingPantryMode.CURATED_BASICS,
  val selectedBasicIngredientIds: Set<String> = DEFAULT_SELECTED_BASIC_INGREDIENT_IDS,
) {
  companion object {
    /**
     * Hydrates an editable [FirstRunOnboardingDraft] from an existing [HouseholdProfile].
     *
     * On a brand-new uncompleted install ([isFreshInstall] == `true`), seed cook names (`"Fred"` /
     * `"Wife"`) are replaced with neutral `"Morning Cook"` and `"Evening Cook"` defaults so new
     * households start with clean consumer placeholders while replays preserve saved names.
     *
     * @param profile Source persisted household profile.
     * @param isFreshInstall `true` when launching cold on a device that has never finished setup.
     * @return Pre-populated [FirstRunOnboardingDraft].
     */
    fun fromHouseholdProfile(
      profile: HouseholdProfile,
      isFreshInstall: Boolean = false,
    ): FirstRunOnboardingDraft {
      val cleanAllergens = profile.householdAllergens
        .map { it.trim().uppercase() }
        .filter { it.isNotEmpty() }
        .toSet()

      // Avoid pre-filling personal seed names ("Fred"/"Wife") on a stranger's brand-new install.
      val resolvedMorning = when {
        isFreshInstall && profile.morningLeadName == "Fred" -> "Morning Cook"
        else -> profile.morningLeadName.trim().ifEmpty { "Morning Cook" }
      }
      val resolvedEvening = when {
        isFreshInstall && profile.eveningLeadName == "Wife" -> "Evening Cook"
        else -> profile.eveningLeadName.trim().ifEmpty { "Evening Cook" }
      }

      // Automatically uncheck any default basic staple that conflicts with active allergens.
      val blockedPresetIds = DEFAULT_ONBOARDING_STAPLE_PRESETS
        .filter { preset -> preset.allergens.any { it.uppercase() in cleanAllergens } }
        .map { it.ingredientId }
        .toSet()

      return FirstRunOnboardingDraft(
        householdName = profile.householdName.trim().ifEmpty { "Our Family Kitchen" },
        template = profile.activeTemplate,
        morningLeadName = resolvedMorning,
        eveningLeadName = resolvedEvening,
        olderKidAgeYears = profile.olderKidAgeYears.coerceIn(4, 17),
        youngestStage = profile.youngestStage,
        selectedAllergens = cleanAllergens,
        currency = profile.currency,
        marketRegionLabel = profile.marketRegionLabel.trim().ifEmpty {
          defaultMarketRegionForCurrency(profile.currency)
        },
        pantryMode = StartingPantryMode.CURATED_BASICS,
        selectedBasicIngredientIds = DEFAULT_SELECTED_BASIC_INGREDIENT_IDS - blockedPresetIds,
      )
    }
  }
}

/**
 * Pure domain output produced by [computeFirstRunOnboardingOutcome] for `O(1)` snapshot persistence.
 *
 * @property householdProfile Configured household profile with template, cook names, currency, and allergens.
 * @property pantryEntries Initialized two-tier pantry stock records matching the chosen [StartingPantryMode].
 * @property weeklyBoard Freshly auto-drafted 21-slot board respecting `EC-01`–`EC-03` and target servings.
 * @property rescuedExpiringMealsCount Initial waste-rescue baseline (`12` for sample demo, `0` otherwise).
 */
@Serializable
data class FirstRunOnboardingOutcome(
  val householdProfile: HouseholdProfile,
  val pantryEntries: List<PantryLedgerEntry>,
  val weeklyBoard: WeeklyBoard,
  val rescuedExpiringMealsCount: Int,
)

/**
 * Pure deterministic reducer transforming a 4-step [FirstRunOnboardingDraft] into a tailored
 * [HouseholdProfile], two-tier [PantryLedgerEntry] ledger, and 21-slot [WeeklyBoard].
 *
 * Enforces `EC-01` (`<= 25m` weekday breakfast cap), `EC-02`/`EC-03` (allergen exclusions and
 * pantry-aware substitute tie-breaking), `EC-08` (child growth-stage texture safety), and `EC-11`
 * (Tier-2 `PLENTY`/`OUT` staple status alignment).
 *
 * @param catalog Household ingredient catalog (`38` canonical items).
 * @param recipes Household recipe library (`30` canonical recipes).
 * @param seedPantry Pre-stocked demo pantry ledger used for [StartingPantryMode.SAMPLE_DEMO_KITCHEN].
 * @param currentBoard Current weekly board providing stable `boardId`, `householdId`, and `weekStartDateIso`.
 * @param draft User-configured 4-step first-run onboarding selections.
 * @param nowIso Deterministic ISO-8601 timestamp stamped onto initialized pantry entries.
 * @return Immutable [FirstRunOnboardingOutcome] ready for a single `O(1)` atomic batch write.
 */
fun computeFirstRunOnboardingOutcome(
  catalog: List<IngredientCatalogItem>,
  recipes: List<Recipe>,
  seedPantry: List<PantryLedgerEntry>,
  currentBoard: WeeklyBoard,
  draft: FirstRunOnboardingDraft,
  nowIso: String = DEFAULT_TIMESTAMP_ISO,
): FirstRunOnboardingOutcome {
  val safeNowIso = nowIso.trim().ifEmpty { DEFAULT_TIMESTAMP_ISO }
  // Normalize allergen tags once so profile filtering, staple exclusion, and board drafting agree.
  val cleanAllergens = draft.selectedAllergens
    .map { it.trim().uppercase() }
    .filter { it.isNotEmpty() }
    .distinct()
  val cleanAllergensSet = cleanAllergens.toSet()

  val cleanHouseholdName = draft.householdName.trim().ifEmpty { "Our Family Kitchen" }
  val cleanMorningLead = draft.morningLeadName.trim().ifEmpty { "Morning Cook" }
  // Sync evening lead to the primary cook name when Solo Cook template is chosen with a blank field.
  val cleanEveningLead = when (draft.template) {
    ConsumerHouseholdTemplate.SOLO_COOK -> draft.eveningLeadName.trim().ifEmpty { cleanMorningLead }
    else -> draft.eveningLeadName.trim().ifEmpty { "Evening Cook" }
  }
  val cleanRegion = draft.marketRegionLabel.trim().ifEmpty {
    defaultMarketRegionForCurrency(draft.currency)
  }
  val safeOlderKidAge = draft.olderKidAgeYears.coerceIn(4, 17)

  val templated = HouseholdProfile.fromConsumerTemplate(
    template = draft.template,
    householdName = cleanHouseholdName,
    morningLeadName = cleanMorningLead,
    eveningLeadName = cleanEveningLead,
    currency = draft.currency,
    marketRegionLabel = cleanRegion,
  )

  val resolvedHouseholdId = currentBoard.householdId.trim().ifEmpty { templated.householdId }
  val hasChildMembers = when (draft.template) {
    ConsumerHouseholdTemplate.FAMILY_TWO_KIDS,
    ConsumerHouseholdTemplate.PARENTS_AND_BABY,
    ConsumerHouseholdTemplate.LARGE_MULTIGEN_FAMILY -> true
    ConsumerHouseholdTemplate.ACTIVE_COUPLE,
    ConsumerHouseholdTemplate.SOLO_COOK -> false
  }
  val effectiveYoungestStage = if (hasChildMembers) draft.youngestStage else templated.youngestStage

  // Synchronize OLDER_KID age and YOUNGEST feeding stage across 1-pot family goal plating specs.
  val updatedMembers = templated.members.map { member ->
    when (member.memberId) {
      "OLDER_KID" -> {
        val nextName = when (draft.template) {
          ConsumerHouseholdTemplate.FAMILY_TWO_KIDS -> "${safeOlderKidAge}yo School Kid"
          else -> member.memberName
        }
        member.copy(memberName = nextName, ageYears = safeOlderKidAge)
      }
      "YOUNGEST" -> member.copy(
        memberName = youngestMemberLabelForStage(effectiveYoungestStage),
        growthStage = effectiveYoungestStage,
      )
      else -> member
    }
  }

  val configuredProfile = templated.copy(
    householdId = resolvedHouseholdId,
    youngestStage = effectiveYoungestStage,
    olderKidAgeYears = safeOlderKidAge,
    members = updatedMembers,
    householdAllergens = cleanAllergens,
  )

  // Materialize initial two-tier pantry ledger and waste-rescue counter for the chosen pantry mode.
  val pantryEntries: List<PantryLedgerEntry>
  val rescuedExpiringMealsCount: Int
  when (draft.pantryMode) {
    StartingPantryMode.SAMPLE_DEMO_KITCHEN -> {
      pantryEntries = when {
        seedPantry.isNotEmpty() -> seedPantry
        else -> catalog.map { item -> buildStockedPantryEntry(item, safeNowIso) }
      }
      rescuedExpiringMealsCount = 12
    }
    StartingPantryMode.EMPTY_KITCHEN -> {
      pantryEntries = catalog.map { item -> buildZeroStockPantryEntry(item, safeNowIso) }
      rescuedExpiringMealsCount = 0
    }
    StartingPantryMode.CURATED_BASICS -> {
      // Strip any checked basic whose catalog allergens overlap household allergen exclusions.
      val safeSelectedIds = catalog
        .asSequence()
        .filter { item ->
          item.ingredientId in draft.selectedBasicIngredientIds &&
            item.allergens.none { it.trim().uppercase() in cleanAllergensSet }
        }
        .map { it.ingredientId }
        .toSet()

      pantryEntries = catalog.map { item ->
        when {
          item.ingredientId in safeSelectedIds -> buildStockedPantryEntry(item, safeNowIso)
          else -> buildZeroStockPantryEntry(item, safeNowIso)
        }
      }
      rescuedExpiringMealsCount = 0
    }
  }

  // Auto-draft all 21 weekly slots calibrated to target servings, allergens, and initial pantry.
  val draftedBoard = autoDraftWeeklyBoard(
    boardId = currentBoard.boardId.trim().ifEmpty { "board_week_1" },
    householdId = configuredProfile.householdId,
    weekStartDateIso = currentBoard.weekStartDateIso.trim().ifEmpty { "2026-10-26" },
    recipes = recipes,
    catalog = catalog,
    householdAllergens = cleanAllergens,
    defaultServings = configuredProfile.effectiveTargetServings,
    pantry = pantryEntries,
  )

  return FirstRunOnboardingOutcome(
    householdProfile = configuredProfile,
    pantryEntries = pantryEntries,
    weeklyBoard = draftedBoard,
    rescuedExpiringMealsCount = rescuedExpiringMealsCount,
  )
}

// Stock 1 full default package with PLENTY status and baseline USDA FoodKeeper shelf life.
private fun buildStockedPantryEntry(
  item: IngredientCatalogItem,
  nowIso: String,
): PantryLedgerEntry =
  PantryLedgerEntry(
    ingredientId = item.ingredientId,
    tier = item.tier,
    currentQuantity = item.defaultPackageQuantity,
    stapleStatus = StapleStatus.PLENTY,
    isOutOfStock = false,
    updatedAtIso = nowIso,
    storageLocation = item.defaultStorageLocation,
    expiresInDays = item.defaultShelfLifeDays,
    purchasedDaysAgo = 0,
  )

// Initialize a depleted 0-stock record so planned weekly meals queue exact packages onto Grocery.
private fun buildZeroStockPantryEntry(
  item: IngredientCatalogItem,
  nowIso: String,
): PantryLedgerEntry =
  PantryLedgerEntry(
    ingredientId = item.ingredientId,
    tier = item.tier,
    currentQuantity = 0.0,
    stapleStatus = StapleStatus.OUT,
    isOutOfStock = true,
    updatedAtIso = nowIso,
    storageLocation = item.defaultStorageLocation,
    expiresInDays = null,
    purchasedDaysAgo = 0,
  )

// Map youngest feeding stage to a clear 1-pot plating card member label.
private fun youngestMemberLabelForStage(stage: ChildGrowthStage): String =
  when (stage) {
    ChildGrowthStage.BABY_6_12M -> "Baby (6-12m)"
    ChildGrowthStage.TODDLER_1_2Y -> "2yo Toddler"
    ChildGrowthStage.PRESCHOOL_3_4Y -> "3-4yo Preschooler"
    ChildGrowthStage.KIDS_5_PLUS -> "5+yo Kid"
  }
