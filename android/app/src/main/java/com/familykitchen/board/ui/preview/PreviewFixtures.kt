package com.familykitchen.board.ui.preview

import com.familykitchen.board.domain.BaseUnit
import com.familykitchen.board.domain.BomLine
import com.familykitchen.board.domain.ChildGrowthStage
import com.familykitchen.board.domain.CookModeEngine
import com.familykitchen.board.domain.CookModeSessionState
import com.familykitchen.board.domain.CookOwner
import com.familykitchen.board.domain.HouseholdCurrency
import com.familykitchen.board.domain.HouseholdLifeEventPreset
import com.familykitchen.board.domain.HouseholdProfile
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.IngredientCategory
import com.familykitchen.board.domain.IngredientTier
import com.familykitchen.board.domain.MealSlotStatus
import com.familykitchen.board.domain.MealSlotType
import com.familykitchen.board.domain.MorningStep
import com.familykitchen.board.domain.PantryLedgerEntry
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.RecipeDifficulty
import com.familykitchen.board.domain.ResolvedMealCard
import com.familykitchen.board.domain.StapleStatus
import com.familykitchen.board.domain.StorageLocation
import com.familykitchen.board.domain.WeeklyBoard
import com.familykitchen.board.domain.WeeklyPrepSessionPlan
import com.familykitchen.board.domain.WorkoutBoostModifier
import com.familykitchen.board.domain.WorkoutTiming
import com.familykitchen.board.domain.autoDraftWeeklyBoard
import com.familykitchen.board.domain.buildResolvedMealCard
import com.familykitchen.board.domain.buildWeeklyMealPrepSessionPlan
import com.familykitchen.board.domain.findInstantInStockSwaps

// Pure-Kotlin deterministic preview fixtures grounded in USDA FoodData Central macros,
// USDA FoodKeeper shelf-life windows, and SavorAI Clean Reader / Cook Mode step metadata.
// Zero Android runtime singletons, disk reads, or JNI calls so @Preview renders hermetically.

/**
 * Pure-Kotlin Family-of-4 (`Fred`, `Wife`, `8yo`, `2yo`) and consumer-distributable `SavorAI`
 * sample fixtures powering Jetpack Compose `@Preview` functions across Today, Cooked Override,
 * Morning Pacer, Weekly Prep, Clean Recipe Reader, and Step-by-Step Cook Mode screens.
 */
object PreviewFixtures {

  // Span COUNT, GRAM, and MILLILITER across Tier-1 perishables and Tier-2 staples priced in
  // Philippine Pesos (PHP ₱) at Antipolo Palengke, Marikina Public Market, & SM Marikina.
  /** Curated Tier-1 (`count`, `g`, `ml`) and Tier-2 (`PLENTY | LOW | OUT`) catalog items. */
  val catalog: List<IngredientCatalogItem> = listOf(
    // Dairy & eggs staples including count-tracked eggs (₱118/doz) and gram-tracked yogurt/cheese.
    IngredientCatalogItem("ing_egg", "Large Farm Eggs", IngredientCategory.DAIRY_EGGS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.COUNT, 12.0, allergens = listOf("EGGS"), defaultStorageLocation = StorageLocation.FRIDGE, defaultShelfLifeDays = 28, estimatedPackagePriceUsd = 118.0),
    IngredientCatalogItem("ing_baguette", "Crusty Artisan Baguette", IngredientCategory.BAKERY_GRAINS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.COUNT, 2.0, allergens = listOf("GLUTEN"), defaultStorageLocation = StorageLocation.PANTRY, defaultShelfLifeDays = 3, estimatedPackagePriceUsd = 95.0),
    IngredientCatalogItem("ing_avocado", "Local Rizal Hass Avocado", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.COUNT, 4.0, defaultStorageLocation = StorageLocation.FRIDGE, defaultShelfLifeDays = 4, estimatedPackagePriceUsd = 160.0),
    IngredientCatalogItem("ing_cheese", "Aged Cheddar Cheese", IngredientCategory.DAIRY_EGGS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 400.0, allergens = listOf("DAIRY"), defaultStorageLocation = StorageLocation.FRIDGE, defaultShelfLifeDays = 21, estimatedPackagePriceUsd = 265.0),
    IngredientCatalogItem("ing_oats", "Rolled Whole Grain Oats", IngredientCategory.BAKERY_GRAINS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 1000.0, defaultStorageLocation = StorageLocation.PANTRY, defaultShelfLifeDays = 270, estimatedPackagePriceUsd = 185.0),
    IngredientCatalogItem("ing_milk", "Whole Fresh Milk", IngredientCategory.DAIRY_EGGS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.MILLILITER, 1000.0, allergens = listOf("DAIRY"), defaultStorageLocation = StorageLocation.FRIDGE, defaultShelfLifeDays = 7, estimatedPackagePriceUsd = 115.0),
    IngredientCatalogItem("ing_greek_yogurt", "Plain Greek Yogurt", IngredientCategory.DAIRY_EGGS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 1000.0, allergens = listOf("DAIRY"), defaultStorageLocation = StorageLocation.FRIDGE, defaultShelfLifeDays = 10, estimatedPackagePriceUsd = 395.0),
    // FRUIT_TOPPING group keeps gram unit parity so EC-03 fruit substitutions never mix units.
    IngredientCatalogItem("ing_banana", "Ripe Cavendish Bananas", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 1000.0, substituteGroupIds = listOf("FRUIT_TOPPING"), defaultStorageLocation = StorageLocation.PANTRY, defaultShelfLifeDays = 5, estimatedPackagePriceUsd = 90.0),
    IngredientCatalogItem("ing_blueberry", "Fresh Blueberries", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 250.0, substituteGroupIds = listOf("FRUIT_TOPPING"), defaultStorageLocation = StorageLocation.FRIDGE, defaultShelfLifeDays = 7, estimatedPackagePriceUsd = 320.0),
    IngredientCatalogItem("ing_honey", "Raw Wildflower Honey", IngredientCategory.PANTRY_STAPLE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.MILLILITER, 500.0, defaultStorageLocation = StorageLocation.PANTRY, defaultShelfLifeDays = 365, estimatedPackagePriceUsd = 295.0),
    // Vine-ripened tomato powers the 1-tap "+1 Tomato (Extra Used)" EC-06 override shortcut.
    IngredientCatalogItem("ing_tomato", "Vine-Ripened Tomatoes", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.COUNT, 6.0, defaultStorageLocation = StorageLocation.PANTRY, defaultShelfLifeDays = 6, estimatedPackagePriceUsd = 85.0),
    IngredientCatalogItem("ing_onion", "Yellow Cooking Onions", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.COUNT, 8.0, defaultStorageLocation = StorageLocation.PANTRY, defaultShelfLifeDays = 30, estimatedPackagePriceUsd = 95.0),
    IngredientCatalogItem("ing_spinach", "Fresh Baby Spinach", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 300.0, substituteGroupIds = listOf("LEAFY_GREENS"), defaultStorageLocation = StorageLocation.FRIDGE, defaultShelfLifeDays = 5, estimatedPackagePriceUsd = 85.0),
    IngredientCatalogItem("ing_amaranth_greens", "Fresh Amaranth Greens", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 300.0, substituteGroupIds = listOf("LEAFY_GREENS"), defaultStorageLocation = StorageLocation.FRIDGE, defaultShelfLifeDays = 5, estimatedPackagePriceUsd = 65.0),
    // Savory Lunch/Dinner proteins, nut-free seeds, and single-ingredient grains/legumes in PHP (₱).
    IngredientCatalogItem("ing_chicken", "Free-Range Chicken Thighs", IngredientCategory.PROTEIN, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 1000.0, defaultStorageLocation = StorageLocation.FRIDGE, defaultShelfLifeDays = 2, estimatedPackagePriceUsd = 230.0),
    IngredientCatalogItem("ing_rice", "Jasmine White Rice", IngredientCategory.BAKERY_GRAINS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 2000.0, defaultStorageLocation = StorageLocation.PANTRY, defaultShelfLifeDays = 365, estimatedPackagePriceUsd = 116.0),
    IngredientCatalogItem("ing_beans", "Black-Eyed Peas", IngredientCategory.PROTEIN, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 1000.0, defaultStorageLocation = StorageLocation.PANTRY, defaultShelfLifeDays = 365, estimatedPackagePriceUsd = 125.0),
    IngredientCatalogItem("ing_pumpkin_seeds", "Roasted Pumpkin Seeds", IngredientCategory.PANTRY_STAPLE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 500.0, substituteGroupIds = listOf("NUT_SEED_TOPPING"), defaultStorageLocation = StorageLocation.PANTRY, defaultShelfLifeDays = 180, estimatedPackagePriceUsd = 260.0),
    IngredientCatalogItem("ing_salmon", "Atlantic Salmon Fillets", IngredientCategory.PROTEIN, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 800.0, allergens = listOf("FISH"), defaultStorageLocation = StorageLocation.FRIDGE, defaultShelfLifeDays = 2, estimatedPackagePriceUsd = 1120.0),
    IngredientCatalogItem("ing_lemon", "Fresh Lemons", IngredientCategory.PRODUCE, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.COUNT, 6.0, defaultStorageLocation = StorageLocation.FRIDGE, defaultShelfLifeDays = 14, estimatedPackagePriceUsd = 110.0),
    IngredientCatalogItem("ing_butter", "Unsalted Creamery Butter", IngredientCategory.DAIRY_EGGS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 250.0, allergens = listOf("DAIRY"), defaultStorageLocation = StorageLocation.FRIDGE, defaultShelfLifeDays = 30, estimatedPackagePriceUsd = 210.0),
    // Tier-2 pantry staples tracked via PLENTY | LOW | OUT tri-state (never auto-deducted per EC-11).
    IngredientCatalogItem("ing_olive_oil", "Extra-Virgin Olive Oil", IngredientCategory.PANTRY_STAPLE, IngredientTier.TIER_2_STAPLE, BaseUnit.MILLILITER, 750.0, defaultStorageLocation = StorageLocation.PANTRY, defaultShelfLifeDays = 365, estimatedPackagePriceUsd = 695.0),
    IngredientCatalogItem("ing_red_palm_oil", "Unrefined Zomi Red Palm Oil", IngredientCategory.PANTRY_STAPLE, IngredientTier.TIER_2_STAPLE, BaseUnit.MILLILITER, 750.0, defaultStorageLocation = StorageLocation.PANTRY, defaultShelfLifeDays = 365, estimatedPackagePriceUsd = 420.0),
    IngredientCatalogItem("ing_garlic", "Fresh Ilocos Garlic Bulbs", IngredientCategory.PANTRY_STAPLE, IngredientTier.TIER_2_STAPLE, BaseUnit.GRAM, 200.0, defaultStorageLocation = StorageLocation.PANTRY, defaultShelfLifeDays = 60, estimatedPackagePriceUsd = 75.0),
    IngredientCatalogItem("ing_salt", "Fine Sea Salt", IngredientCategory.PANTRY_STAPLE, IngredientTier.TIER_2_STAPLE, BaseUnit.GRAM, 500.0, defaultStorageLocation = StorageLocation.PANTRY, defaultShelfLifeDays = 365, estimatedPackagePriceUsd = 45.0),
    IngredientCatalogItem("ing_cinnamon", "Ground Ceylon Cinnamon", IngredientCategory.PANTRY_STAPLE, IngredientTier.TIER_2_STAPLE, BaseUnit.GRAM, 100.0, defaultStorageLocation = StorageLocation.PANTRY, defaultShelfLifeDays = 365, estimatedPackagePriceUsd = 95.0),
  )

  /** Lookup map of [catalog] keyed by `ingredientId` for constant-time UI label resolution. */
  val catalogById: Map<String, IngredientCatalogItem> = catalog.associateBy { it.ingredientId }

  // Shared workout modifiers (EC-07: flat 1x unscaled extra lines) with consumer-ready copy.
  private val morningWorkoutModifiers: List<WorkoutBoostModifier> = listOf(
    WorkoutBoostModifier(
      profile = WorkoutTiming.PRE_WORKOUT_CARB,
      instructionAddOn = "Pre-Workout Carb Boost: Drizzle +20ml honey and add +100g sliced banana for rapid glycogen.",
      extraBomLines = listOf(BomLine("ing_banana", 100), BomLine("ing_honey", 20)),
    ),
    WorkoutBoostModifier(
      profile = WorkoutTiming.POST_WORKOUT_HIGH_PROTEIN,
      instructionAddOn = "Post-Workout High-Protein: Plate +2 extra farm eggs on the athlete's portion.",
      extraBomLines = listOf(BomLine("ing_egg", 2)),
    ),
  )

  private val savoryWorkoutModifiers: List<WorkoutBoostModifier> = listOf(
    WorkoutBoostModifier(
      profile = WorkoutTiming.PRE_WORKOUT_CARB,
      instructionAddOn = "Pre-Workout Carb Boost: Add +100g steamed jasmine rice on the athlete's plate.",
      extraBomLines = listOf(BomLine("ing_rice", 100)),
    ),
    WorkoutBoostModifier(
      profile = WorkoutTiming.POST_WORKOUT_HIGH_PROTEIN,
      instructionAddOn = "Post-Workout High-Protein: Sear +150g extra lean chicken for recovery.",
      extraBomLines = listOf(BomLine("ing_chicken", 150)),
    ),
  )

  // Calibrated morning math: ceil((5 - 0) * 1.4) + 8 = 7 + 8 = 15m (<= 25m weekday cap per EC-01).
  /** 15-minute weekday breakfast tartine with 3 timed Morning Pacer / Cook Mode steps. */
  val breakfastTartineRecipe: Recipe = Recipe(
    recipeId = "rec_01_egg_avocado_baguette_tartine",
    title = "Warm Egg, Cheese & Avocado Baguette Tartine",
    sourceAttribution = "French Bistro Morning Classics",
    allowedSlots = listOf(MealSlotType.BREAKFAST, MealSlotType.LUNCH),
    preferredOwner = CookOwner.FRED,
    baseServings = 4,
    rawPrepMinutes = 5,
    nightBeforePrepMinutes = 0,
    cookMinutes = 8,
    calibratedActiveMinutes = 15,
    nightBeforePrepSteps = listOf("Set skillet on stove and check avocado ripeness at 8:30 PM."),
    morningSteps = listOf(
      MorningStep(
        stepNumber = 1,
        title = "Toast Baguette",
        instruction = "Split baguette lengthwise and toast lightly with extra-virgin olive oil until crisp at the edges.",
        timerSeconds = 180,
        techniqueBadge = "Crisp Crumb",
        visualTargetCue = "Light golden rim with pillowy interior crumb",
        chefTip = "Brush olive oil right to the crust edge so melted cheese does not sog the bread.",
        stepIngredientIds = listOf("ing_baguette", "ing_olive_oil"),
      ),
      MorningStep(
        stepNumber = 2,
        title = "Avocado Mash",
        instruction = "Mash ripe Hass avocado with a pinch of sea salt and spread evenly over warm bread.",
        timerSeconds = 120,
        techniqueBadge = "Textured Mash",
        visualTargetCue = "Creamy spread with small tender chunks",
        stepIngredientIds = listOf("ing_avocado", "ing_salt"),
      ),
      MorningStep(
        stepNumber = 3,
        title = "Soft Scramble",
        instruction = "Soft-scramble 4 farm eggs over medium-low heat with shredded cheese and pile onto tartines.",
        timerSeconds = 300,
        techniqueBadge = "Custard Curds",
        visualTargetCue = "Glossy ribbons folded off-heat while cheese melts",
        chefTip = "Pull the pan 15 seconds early; residual skillet heat finishes setting the eggs.",
        stepIngredientIds = listOf("ing_egg", "ing_cheese"),
      ),
    ),
    toddlerPrepTip = "Cut 2yo portion onto soft inner baguette crumb with crust trimmed into bite-sized strips.",
    bomLines = listOf(
      BomLine("ing_baguette", 1),
      BomLine("ing_egg", 4),
      BomLine("ing_avocado", 2),
      BomLine("ing_cheese", 80),
      BomLine("ing_olive_oil", 10),
      BomLine("ing_salt", 2),
    ),
    workoutModifiers = morningWorkoutModifiers,
    caloriesPerServing = 410,
    proteinGrams = 19,
    carbsGrams = 34,
    fatGrams = 22,
    difficulty = RecipeDifficulty.EASY,
    cuisineTag = "French Bistro",
    chefPairingNote = "Pair with warm rooibos tea or fresh-squeezed orange slices to double egg-yolk iron uptake.",
  )

  // Shifting 5m of dry-mix prep to 8:30 PM drops active morning time to ceil(2 * 1.4) + 2 = 5m.
  /** 5-minute calibrated Overnight Oats highlighting 8:30 PM night-before prep (`EC-01`). */
  val overnightOatsRecipe: Recipe = Recipe(
    recipeId = "rec_09_overnight_oats",
    title = "5-Min Night-Before Overnight Oats",
    sourceAttribution = "Express Weekday Morning Bowls",
    allowedSlots = listOf(MealSlotType.BREAKFAST),
    preferredOwner = CookOwner.FRED,
    baseServings = 4,
    rawPrepMinutes = 7,
    nightBeforePrepMinutes = 5,
    cookMinutes = 2,
    calibratedActiveMinutes = 5,
    nightBeforePrepSteps = listOf(
      "Stir rolled oats, milk, Greek yogurt, and cinnamon in 4 jars at 8:30 PM.",
      "Seal jars on the middle fridge shelf for grab-and-top morning plating.",
    ),
    morningSteps = listOf(
      MorningStep(
        stepNumber = 1,
        title = "Top & Serve",
        instruction = "Pull chilled oat jars from the fridge and top with sliced banana and wildflower honey.",
        timerSeconds = 120,
        techniqueBadge = "Zero-Cook",
        visualTargetCue = "Creamy soaked oats with fresh fruit ribbons",
        stepIngredientIds = listOf("ing_oats", "ing_greek_yogurt", "ing_banana", "ing_honey"),
      ),
    ),
    toddlerPrepTip = "Overnight soaked oats are naturally soft; mash banana topping slightly for 2yo.",
    bomLines = listOf(
      BomLine("ing_oats", 240),
      BomLine("ing_milk", 400),
      BomLine("ing_greek_yogurt", 200),
      BomLine("ing_banana", 200, substituteGroupId = "FRUIT_TOPPING"),
      BomLine("ing_honey", 25),
      BomLine("ing_cinnamon", 2),
    ),
    workoutModifiers = morningWorkoutModifiers,
    caloriesPerServing = 355,
    proteinGrams = 14,
    carbsGrams = 58,
    fatGrams = 7,
    difficulty = RecipeDifficulty.EASY,
    cuisineTag = "Quick Morning Bowls",
  )

  // Zero-cook 9-minute yogurt parfait ensures Monday Breakfast has >= 2 EC-13 instant swaps.
  /** Instant in-stock 9-minute Greek Yogurt Parfait candidate for `EC-13` quick swaps. */
  val yogurtParfaitRecipe: Recipe = Recipe(
    recipeId = "rec_06_greek_yogurt_granola_parfait",
    title = "Greek Yogurt, Oats & Berry Parfait",
    sourceAttribution = "Mediterranean Morning Bar",
    allowedSlots = listOf(MealSlotType.BREAKFAST),
    preferredOwner = CookOwner.FRED,
    baseServings = 4,
    rawPrepMinutes = 6,
    nightBeforePrepMinutes = 0,
    cookMinutes = 0,
    calibratedActiveMinutes = 9,
    morningSteps = listOf(
      MorningStep(1, "Spoon creamy Greek yogurt into 4 family bowls.", 120, title = "Base Layer"),
      MorningStep(2, "Top with rolled oats, honey drizzle, and quartered fresh berries.", 180, title = "Berry Crown"),
    ),
    toddlerPrepTip = "Quarter whole blueberries lengthwise for 2yo choking prevention.",
    bomLines = listOf(
      BomLine("ing_greek_yogurt", 500),
      BomLine("ing_oats", 160),
      BomLine("ing_blueberry", 150, substituteGroupId = "FRUIT_TOPPING"),
      BomLine("ing_honey", 30),
    ),
    workoutModifiers = morningWorkoutModifiers,
    caloriesPerServing = 290,
    proteinGrams = 16,
    carbsGrams = 42,
    fatGrams = 5,
    difficulty = RecipeDifficulty.EASY,
    cuisineTag = "Mediterranean Express",
    isSavedFavorite = true,
  )

  // Heritage West African one-pot rice & beans (45m > 25m, so LUNCH/DINNER only).
  /** Beninese Atassi (Watché) one-pot rice and beans midday meal. */
  val lunchAtassiRecipe: Recipe = Recipe(
    recipeId = "rec_13_beninese_atassi_watche",
    title = "Beninese Atassi (Watché) Rice & Beans",
    sourceAttribution = "West African Heritage Kitchen",
    allowedSlots = listOf(MealSlotType.LUNCH, MealSlotType.DINNER),
    preferredOwner = CookOwner.WIFE,
    baseServings = 4,
    rawPrepMinutes = 12,
    nightBeforePrepMinutes = 5,
    cookMinutes = 35,
    calibratedActiveMinutes = 45,
    nightBeforePrepSteps = listOf("Rinse and soak black-eyed beans in a bowl of water at 8:30 PM."),
    morningSteps = listOf(
      MorningStep(1, "Simmer soaked beans until tender, then fold in washed jasmine rice.", 1500, title = "One-Pot Simmer"),
      MorningStep(2, "Simmer mild tomato-onion Zomi sauce and serve with boiled farm eggs.", 600, title = "Zomi Tomato Jus"),
    ),
    toddlerPrepTip = "Mash beans slightly with soft rice and mild tomato sauce (before chili) for 2yo.",
    bomLines = listOf(
      BomLine("ing_rice", 400),
      BomLine("ing_beans", 300),
      BomLine("ing_tomato", 4),
      BomLine("ing_onion", 2),
      BomLine("ing_egg", 4),
      BomLine("ing_red_palm_oil", 30),
      BomLine("ing_salt", 4),
    ),
    workoutModifiers = savoryWorkoutModifiers,
    caloriesPerServing = 520,
    proteinGrams = 22,
    carbsGrams = 74,
    fatGrams = 14,
    difficulty = RecipeDifficulty.MEDIUM,
    cuisineTag = "Beninese & West African",
    chefPairingNote = "Bright hibiscus-ginger iced infusion or fresh tangerine wedges balance the savoury Zomi stew.",
  )

  // Hero Clean Reader & Step-by-Step Cook Mode recipe matching recipe_reader & cook_mode specs.
  // Step 2 ("The Sear") carries exact 4:00 (240s) duration, Skin-Crisping badge, and doneness cue.
  /** Pan-Seared Tuscan Salmon with Creamy Garlic Lemon Spinach & Rice hero dinner. */
  val dinnerSalmonRecipe: Recipe = Recipe(
    recipeId = "rec_20_one_pan_lemon_herb_salmon_rice",
    title = "Pan-Seared Tuscan Salmon with Creamy Garlic Lemon Spinach",
    sourceAttribution = "Coastal Bistro Collection",
    allowedSlots = listOf(MealSlotType.LUNCH, MealSlotType.DINNER),
    preferredOwner = CookOwner.WIFE,
    baseServings = 4,
    rawPrepMinutes = 10,
    nightBeforePrepMinutes = 0,
    cookMinutes = 15,
    calibratedActiveMinutes = 29,
    morningSteps = listOf(
      MorningStep(
        stepNumber = 1,
        title = "Prep",
        headline = "Pat salmon fillets completely dry and season evenly.",
        instruction = "Pat Atlantic salmon fillets completely dry with paper towels and season lightly with fine sea salt. Dry skin guarantees a golden, crackling sear.",
        timerSeconds = 120,
        techniqueBadge = "Dry-Brine Prep",
        visualTargetCue = "Matte, moisture-free salmon skin surface",
        chefTip = "Press gently with a clean towel right before oiling the pan.",
        stepIngredientIds = listOf("ing_salmon", "ing_salt"),
      ),
      MorningStep(
        stepNumber = 2,
        title = "The Sear",
        headline = "Sear salmon skin-side down undisturbed until crackling and 75% cooked.",
        instruction = "Heat extra-virgin olive oil in a heavy stainless steel or cast iron skillet over medium-high heat until shimmering. Add salmon skin-side down; press lightly with a spatula for 10 seconds, then cook undisturbed.",
        timerSeconds = 240,
        techniqueBadge = "Skin-Crisping Technique",
        visualTargetCue = "Deep golden skin, 75% opaque flesh",
        chefTip = "Do not touch or slide fish during this sear. Moisture releasing naturally creates the golden crust; it will release cleanly once crisped!",
        stepIngredientIds = listOf("ing_salmon", "ing_olive_oil"),
      ),
      MorningStep(
        stepNumber = 3,
        title = "Garlic Sauce",
        headline = "Flip gently for 3 minutes, then rest on a warm plate.",
        instruction = "Flip fillets gently using a fish spatula, add butter and minced garlic, and baste for 3 minutes until opaque flake. Transfer salmon to a warm plate.",
        timerSeconds = 180,
        techniqueBadge = "Aromatic Baste",
        visualTargetCue = "63C internal opaque flake that separates cleanly",
        chefTip = "Keep heat at medium so garlic turns sweet and golden rather than bitter.",
        stepIngredientIds = listOf("ing_butter", "ing_garlic"),
      ),
      MorningStep(
        stepNumber = 4,
        title = "Simmer & Plate",
        headline = "Wilt baby spinach with lemon juice and tempered Greek yogurt.",
        instruction = "Fold baby spinach into the pan juices until wilted, squeeze fresh lemon, temper in creamy Greek yogurt off-heat, and serve alongside steamed jasmine rice.",
        timerSeconds = 180,
        techniqueBadge = "Off-Heat Emulsion",
        visualTargetCue = "Glossy emerald spinach ribboned in velvety citrus sauce",
        chefTip = "Tempering Greek yogurt off the burner keeps the sauce silky without curdling.",
        stepIngredientIds = listOf("ing_spinach", "ing_greek_yogurt", "ing_lemon", "ing_rice"),
      ),
    ),
    toddlerPrepTip = "Flake soft center of salmon fillet and verify zero pin-bones; finely chop wilted spinach for 2yo.",
    bomLines = listOf(
      BomLine("ing_salmon", 600),
      BomLine("ing_olive_oil", 15),
      BomLine("ing_garlic", 12),
      BomLine("ing_spinach", 200, substituteGroupId = "LEAFY_GREENS"),
      BomLine("ing_greek_yogurt", 150),
      BomLine("ing_lemon", 1),
      BomLine("ing_butter", 20),
      BomLine("ing_rice", 350),
    ),
    workoutModifiers = savoryWorkoutModifiers,
    caloriesPerServing = 480,
    proteinGrams = 38,
    carbsGrams = 36,
    fatGrams = 20,
    difficulty = RecipeDifficulty.EASY,
    cuisineTag = "Mediterranean Bistro",
    chefNote = "Used Greek yogurt instead of heavy cream last time — reduced pan juices slightly first to avoid curdling, turned out exceptionally light!",
    chefPairingNote = "Crisp Italian Vermentino, unoaked Chardonnay, or chilled sparkling lemon-mint water cuts cleanly through the creamy citrus pan sauce.",
    isSavedFavorite = true,
  )

  /** Crispy Chicken & Tomato Baguette serving as an in-stock Lunch/Dinner swap candidate. */
  val dinnerChickenBaguetteRecipe: Recipe = Recipe(
    recipeId = "rec_02_crispy_chicken_tomato_baguette",
    title = "Crispy Chicken & Tomato Baguette",
    sourceAttribution = "French Bistro Rotation",
    allowedSlots = listOf(MealSlotType.LUNCH, MealSlotType.DINNER),
    preferredOwner = CookOwner.WIFE,
    baseServings = 4,
    rawPrepMinutes = 10,
    nightBeforePrepMinutes = 0,
    cookMinutes = 12,
    calibratedActiveMinutes = 26,
    morningSteps = listOf(
      MorningStep(1, "Pan-sear seasoned chicken strips in olive oil until golden.", 480, title = "Pan Sear"),
      MorningStep(2, "Layer chicken, ripe tomatoes, spinach, and cheese inside warm baguettes.", 240, title = "Assemble"),
    ),
    toddlerPrepTip = "Shred chicken finely and serve deconstructed with soft diced tomatoes for 2yo.",
    bomLines = listOf(
      BomLine("ing_baguette", 2),
      BomLine("ing_chicken", 500),
      BomLine("ing_tomato", 3),
      BomLine("ing_spinach", 100, substituteGroupId = "LEAFY_GREENS"),
      BomLine("ing_cheese", 100),
      BomLine("ing_olive_oil", 15),
    ),
    workoutModifiers = savoryWorkoutModifiers,
    caloriesPerServing = 495,
    proteinGrams = 36,
    carbsGrams = 46,
    fatGrams = 18,
    difficulty = RecipeDifficulty.EASY,
    cuisineTag = "French Bistro",
  )

  /** Complete sample recipe list spanning `<= 25m` breakfasts and heritage/bistro lunches & dinners. */
  val recipes: List<Recipe> = listOf(
    breakfastTartineRecipe,
    overnightOatsRecipe,
    yogurtParfaitRecipe,
    lunchAtassiRecipe,
    dinnerSalmonRecipe,
    dinnerChickenBaguetteRecipe,
  )

  /** Lookup map of [recipes] keyed by `recipeId`. */
  val recipesById: Map<String, Recipe> = recipes.associateBy { it.recipeId }

  // Stock 2 full packages of every Tier-1 item and stamp 3 realistic expiring items (salmon 1d,
  // spinach 2d, Greek yogurt 2d) so Zero-Waste Rescue & EC-13 instant swaps both shine in @Preview.
  /** Stocked household pantry ledger with 3 realistic `EXPIRING_SOON` fridge perishables. */
  val pantryEntries: List<PantryLedgerEntry> = catalog.map { item ->
    val expiringDays = when (item.ingredientId) {
      "ing_salmon" -> 1
      "ing_spinach", "ing_greek_yogurt" -> 2
      else -> item.defaultShelfLifeDays
    }
    when (item.tier) {
      // Tier-2 oils and spices track tri-state PLENTY status rather than gram depletion.
      IngredientTier.TIER_2_STAPLE -> PantryLedgerEntry(
        ingredientId = item.ingredientId,
        tier = IngredientTier.TIER_2_STAPLE,
        currentQuantity = item.defaultPackageQuantity,
        stapleStatus = StapleStatus.PLENTY,
        storageLocation = item.defaultStorageLocation,
        expiresInDays = expiringDays,
      )
      // Tier-1 perishables start with 2x package stock to satisfy 4-to-6 serving scales.
      IngredientTier.TIER_1_QUANTITATIVE -> PantryLedgerEntry(
        ingredientId = item.ingredientId,
        tier = IngredientTier.TIER_1_QUANTITATIVE,
        currentQuantity = item.defaultPackageQuantity * 2.0,
        stapleStatus = StapleStatus.PLENTY,
        storageLocation = item.defaultStorageLocation,
        expiresInDays = expiringDays,
      )
    }
  }

  // Baseline Family-of-4 profile grounded in Antipolo & Marikina, PH (PHP ₱ default currency).
  /** Default Family-of-4 (`Fred`, `Wife`, `8yo`, `2yo`) household dietary & growth profile. */
  val householdProfile: HouseholdProfile = HouseholdProfile(
    youngestStage = ChildGrowthStage.TODDLER_1_2Y,
    olderKidAgeYears = 8,
    currency = HouseholdCurrency.PHP,
    marketRegionLabel = "Antipolo & Marikina, PH",
  )

  // Pregnancy 2nd/3rd trimester preset exercises firm-yolk & 63C fish safety notes in 4-way plating.
  /** Evening profile demonstrating active life-event safety guardrails (Pregnancy 2nd/3rd Trimester). */
  val eveningLifeEventProfile: HouseholdProfile = householdProfile.copy(
    activeLifeEvents = listOf(HouseholdLifeEventPreset.PREGNANCY_TRIMESTER_2_3.toLifeEvent()),
  )

  // Customize Monday slots to showcase EC-07 workout boost, -5m prepped badge, and Tuesday oats.
  /**
   * Sample 7-day (21-slot) [WeeklyBoard] where Monday Lunch is stamped `isPreppedAhead = true`
   * (`-5m prepped`) and Monday Breakfast activates post-workout high-protein boost (`EC-07`).
   */
  val weeklyBoard: WeeklyBoard = run {
    val baseBoard = autoDraftWeeklyBoard(
      boardId = "board_week_preview",
      householdId = householdProfile.householdId,
      weekStartDateIso = "2026-10-26",
      recipes = recipes,
      catalog = catalog,
      pantry = pantryEntries,
    )
    baseBoard.copy(
      slots = baseBoard.slots.map { slot ->
        when {
          slot.dayIndex == 0 && slot.slotType == MealSlotType.BREAKFAST ->
            slot.copy(
              recipeId = breakfastTartineRecipe.recipeId,
              activeWorkoutModifier = WorkoutTiming.POST_WORKOUT_HIGH_PROTEIN,
            )
          slot.dayIndex == 0 && slot.slotType == MealSlotType.LUNCH ->
            slot.copy(
              recipeId = lunchAtassiRecipe.recipeId,
              isPreppedAhead = true,
              preppedTimeSavedMinutes = 5,
            )
          slot.dayIndex == 0 && slot.slotType == MealSlotType.DINNER ->
            slot.copy(
              recipeId = dinnerSalmonRecipe.recipeId,
              status = MealSlotStatus.PLANNED,
            )
          slot.dayIndex == 1 && slot.slotType == MealSlotType.BREAKFAST ->
            slot.copy(recipeId = overnightOatsRecipe.recipeId)
          else -> slot
        }
      },
    )
  }

  /**
   * Builds resolved meal cards for [dayIndex] (`0 = Monday`..`6 = Sunday`) at [currentHour24].
   *
   * @param dayIndex 0-based weekday index.
   * @param currentHour24 Simulated 24-hour clock (`7` morning vs `21` evening night-prep window).
   * @param profile Active [HouseholdProfile] driving child stage and 4-way 1-pot plating.
   * @return Chronologically ordered list of [ResolvedMealCard]s for the selected day.
   */
  fun resolvedCardsForDay(
    dayIndex: Int = 0,
    currentHour24: Int = 7,
    profile: HouseholdProfile = householdProfile,
  ): List<ResolvedMealCard> =
    weeklyBoard.slots
      .filter { it.dayIndex == dayIndex }
      .sortedBy { it.slotType.chronologicalRank }
      .mapNotNull { slot ->
        val recipe = recipesById[slot.recipeId] ?: return@mapNotNull null
        buildResolvedMealCard(
          slot = slot,
          recipe = recipe,
          catalog = catalog,
          currentHour24 = currentHour24,
          householdProfile = profile,
        )
      }

  /** Pre-computed `EC-13` instant in-stock swap candidates keyed by `slotId` for Monday's 3 meals. */
  val mondayInstantSwapsBySlotId: Map<String, List<Recipe>> =
    weeklyBoard.slots
      .filter { it.dayIndex == 0 }
      .associate { slot ->
        slot.slotId to findInstantInStockSwaps(
          slotType = slot.slotType,
          recipes = recipes,
          catalog = catalog,
          pantry = pantryEntries,
          householdAllergens = householdProfile.householdAllergens,
          targetServings = slot.targetServings,
          excludeRecipeId = slot.recipeId,
        )
      }

  /** Station-grouped [WeeklyPrepSessionPlan] spanning all 4 kitchen batch-prep stations. */
  val weeklyPrepPlan: WeeklyPrepSessionPlan = buildWeeklyMealPrepSessionPlan(
    board = weeklyBoard,
    recipes = recipes,
    catalog = catalog,
    completedTaskIds = setOf("prep_station_grains_oats_beans"),
  )

  // Pre-build a live Step 2 Cook Mode session at 03:42 (222s = 92%) so @Preview matches screen.png.
  /**
   * Sample active [CookModeSessionState] focused on Step 2 (`"The Sear"`, `03:42` remaining = `92%`)
   * with grounded Sous-Chef Q&A pre-populated to mirror `step_by_step_cook_mode/screen.png`.
   */
  val activeSalmonCookSession: CookModeSessionState = run {
    // Initialize enriched step ingredients and step-indexed timers from CookModeEngine.
    val started = CookModeEngine.startCookSession(
      recipe = dinnerSalmonRecipe,
      slotId = "board_week_preview_day_0_dinner",
      targetServings = 4,
      catalog = catalog,
      initialCheckedIngredients = setOf("ing_salmon", "ing_olive_oil"),
      handsFreeVoiceActive = true,
    )
    // Advance focus to Step 2 ("The Sear") and set remainingSeconds = 222 (03:42 / 92%).
    val atStepTwo = CookModeEngine.jumpToStep(started, 1)
    val stepTwoTimer = atStepTwo.timersByStepNumber[2]
    val tunedTimers = when (stepTwoTimer) {
      null -> atStepTwo.timersByStepNumber
      else -> atStepTwo.timersByStepNumber + (2 to stepTwoTimer.copy(remainingSeconds = 222, isRunning = true))
    }
    // Populate grounded offline Sous-Chef Q&A turn without invoking any JNI/network engine.
    atStepTwo.copy(
      timersByStepNumber = tunedTimers,
      lastVoiceTranscript = "How do I know exactly when to flip?",
      lastSousChefAnswer = CookModeEngine.answerCookStepQuestion(
        recipe = dinnerSalmonRecipe,
        stepIndex = 1,
        question = "How do I know when it is ready to flip?",
        catalog = catalog,
        youngestStage = householdProfile.youngestStage,
      ),
    )
  }
}
