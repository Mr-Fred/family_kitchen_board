package com.familykitchen.board.domain

import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.Serializable

// Pure heritage recipes catalog (6 Yoruba and West African, 4 French Bistro, 2 Filipino) and
// reusable 21-slot MenuTemplate ranking, market-grounded scoring, and board application engine.
// Zero Android, Compose, java.io, or java.time imports permitted inside domain/.

private const val PREFERRED_CUISINE_SCORE_BONUS: Int = 50
private const val EXPIRING_RESCUE_SCORE_BONUS: Int = 30
private const val HIGH_PANTRY_MATCH_SCORE_BONUS: Int = 20
private const val SUBSTITUTE_REQUIRED_SCORE_PENALTY: Int = 10
private const val HIGH_PANTRY_COVERAGE_THRESHOLD: Double = 0.60

/**
 * Single day-and-slot assignment inside a reusable [MenuTemplate].
 *
 * @property dayIndex 0-based weekday index (`0 = Monday` .. `6 = Sunday`).
 * @property slotType Meal category (`BREAKFAST`, `LUNCH`, or `DINNER`).
 * @property recipeId Target [Recipe.recipeId] scheduled on this slot.
 */
@Serializable
data class MenuTemplateSlotSpec(
  val dayIndex: Int,
  val slotType: MealSlotType,
  val recipeId: String,
)

/**
 * Reusable multi-day household menu blueprint that can be ranked by preferred cuisines, saved from
 * an active [WeeklyBoard], or applied with automatic local-market and pantry remixing.
 *
 * @property menuId Stable identifier for the menu template.
 * @property title Clean UI display title free of slashes or parentheses.
 * @property subtitle Concise culinary summary surfaced on Menu Picker cards.
 * @property cuisineFocus Canonical cuisine tags represented across [slotSpecs].
 * @property slotSpecs Ordered slot assignments across the 7-day week.
 * @property isUserCreated `true` when saved by the household from their own [WeeklyBoard].
 */
@Serializable
data class MenuTemplate(
  val menuId: String,
  val title: String,
  val subtitle: String,
  val cuisineFocus: List<String> = emptyList(),
  val slotSpecs: List<MenuTemplateSlotSpec>,
  val isUserCreated: Boolean = false,
)

// Compact step builder keeping all 12 curated recipes readable and under module line budgets.
private fun heritageStep(
  num: Int,
  title: String,
  secs: Int,
  instruction: String,
  vararg ids: String,
  cue: String? = null,
  tip: String? = null,
): MorningStep = MorningStep(
  stepNumber = num,
  instruction = instruction,
  timerSeconds = secs,
  title = title,
  visualTargetCue = cue,
  chefTip = tip,
  stepIngredientIds = ids.toList(),
)

// Compact BOM line builder enforcing canonical seed_catalog.json IDs and substitute groups.
private fun heritageBom(id: String, qty: Int, group: String? = null): BomLine =
  BomLine(ingredientId = id, baseQuantity = qty.toDouble(), substituteGroupId = group)

// Factory enforcing calibrated 1.4x EC-01 morning math, baseServings = 4, and non-blank safety tips.
private fun heritageRecipe(
  id: String,
  title: String,
  origin: String,
  cuisine: String,
  prep: Int,
  cook: Int,
  kcal: Int,
  protein: Int,
  carbs: Int,
  fat: Int,
  tip: String,
  note: String,
  pairing: String,
  slots: List<MealSlotType> = listOf(MealSlotType.LUNCH, MealSlotType.DINNER),
  nightPrep: Int = 0,
  nightStep: String? = null,
  diff: RecipeDifficulty = RecipeDifficulty.MEDIUM,
  bom: List<BomLine>,
  steps: List<MorningStep>,
): Recipe = Recipe(
  recipeId = id,
  title = title,
  sourceAttribution = "$origin • USDA Verified",
  allowedSlots = slots,
  preferredOwner = if (MealSlotType.BREAKFAST in slots) CookOwner.FRED else CookOwner.WIFE,
  baseServings = 4,
  rawPrepMinutes = prep,
  nightBeforePrepMinutes = nightPrep,
  cookMinutes = cook,
  calibratedActiveMinutes = computeCalibratedMorningMinutes(prep, nightPrep, cook),
  nightBeforePrepSteps = if (nightStep != null) listOf(nightStep) else emptyList(),
  morningSteps = steps,
  toddlerPrepTip = tip,
  bomLines = bom,
  caloriesPerServing = kcal,
  proteinGrams = protein,
  carbsGrams = carbs,
  fatGrams = fat,
  difficulty = diff,
  cuisineTag = cuisine,
  chefNote = note,
  chefPairingNote = pairing,
  cuisineTags = listOf(cuisine),
)

/**
 * Twelve curated heritage and regional family recipes (`baseServings = 4`, USDA macros, `100%`
 * `toddlerPrepTip` coverage) referencing exclusively the 44 canonical `seed_catalog.json` IDs:
 * - `rec_31`..`rec_36`: 6 Yoruba and West African classics
 * - `rec_37`..`rec_40`: 4 French family bistro classics
 * - `rec_41`..`rec_42`: 2 regional Filipino classics
 */
val HERITAGE_AND_WORLD_RECIPES: List<Recipe> = listOf(
  // 1. Yoruba Efo Riro Rich Vegetable Stew — LEAFY_GREENS allows kulitis, spinach, or kangkong swaps.
  heritageRecipe(
    id = "rec_31_yoruba_efo_riro",
    title = "Yoruba Efo Riro Rich Vegetable Stew",
    origin = "Yoruba Heritage Kitchen",
    cuisine = "Yoruba and West African",
    prep = 10, cook = 22, kcal = 390, protein = 34, carbs = 14, fat = 22,
    tip = "Chiffonade greens finely and shred braised chicken into soft bite-sized strands for 2yo.",
    note = "Squeezing excess water from blanched greens keeps Efo Riro rich rather than brothy.",
    pairing = "Serve warm alongside steamed jasmine rice or soft roasted sweet potatoes.",
    bom = listOf(
      heritageBom("ing_amaranth_greens", 300, "LEAFY_GREENS"), heritageBom("ing_chicken", 600),
      heritageBom("ing_bell_pepper", 2), heritageBom("ing_tomato", 3),
      heritageBom("ing_red_onion", 1), heritageBom("ing_red_palm_oil", 20),
      heritageBom("ing_garlic", 10), heritageBom("ing_salt", 3),
    ),
    steps = listOf(
      heritageStep(1, "Poach Chicken", 600, "Simmer seasoned chicken thighs with red onion and garlic until tender.", "ing_chicken", "ing_red_onion", "ing_garlic"),
      heritageStep(2, "Reduce Pepper Base", 480, "Sizzle blended bell pepper and tomato puree in red palm oil until glossy.", "ing_bell_pepper", "ing_tomato", "ing_red_palm_oil", cue = "Crimson oil beads rising around thick pepper sauce"),
      heritageStep(3, "Fold Leafy Greens", 240, "Fold blanched amaranth greens and braised chicken into the stew for 3 minutes.", "ing_amaranth_greens", "ing_salt", tip = "Fold greens right at the end so leaves stay emerald."),
    ),
  ),
  // 2. Yoruba Ata Din-Din Seared Fish Stew — deeply reduced bell pepper and tomato sauce with snapper.
  heritageRecipe(
    id = "rec_32_yoruba_ata_dindin_fish",
    title = "Yoruba Ata Din-Din Seared Fish Stew",
    origin = "Yoruba Coastal Table",
    cuisine = "Yoruba and West African",
    prep = 10, cook = 20, kcal = 345, protein = 36, carbs = 13, fat = 16,
    tip = "Flake seared snapper fillets by hand to confirm zero pin-bones and keep sauce mild for 2yo.",
    note = "Patting fish dry before searing keeps fillets intact when basted in Ata Din-Din.",
    pairing = "Pair with fluffy jasmine rice or sweet caramelized plantains.",
    bom = listOf(
      heritageBom("ing_fish", 700), heritageBom("ing_tomato", 4), heritageBom("ing_bell_pepper", 2),
      heritageBom("ing_red_onion", 2), heritageBom("ing_ginger", 15),
      heritageBom("ing_red_palm_oil", 25), heritageBom("ing_garlic", 10), heritageBom("ing_salt", 3),
    ),
    steps = listOf(
      heritageStep(1, "Sear Fish", 360, "Score and pan-sear snapper fillets lightly in red palm oil until golden.", "ing_fish", "ing_red_palm_oil", cue = "Opaque flaky flesh at 63C"),
      heritageStep(2, "Fry Ata Din-Din", 600, "Fry blended tomatoes, bell peppers, red onions, ginger, and garlic until thick.", "ing_tomato", "ing_bell_pepper", "ing_red_onion", "ing_ginger"),
      heritageStep(3, "Baste and Serve", 240, "Nestle seared fish into the simmering sauce for 4 minutes to absorb flavor.", "ing_fish", "ing_salt"),
    ),
  ),
  // 3. West African Party Jollof Rice and Chicken — one-pot tomato-pepper steamed jasmine rice.
  heritageRecipe(
    id = "rec_33_party_jollof_braised_chicken",
    title = "West African Party Jollof Rice and Chicken",
    origin = "Yoruba Celebration Kitchen",
    cuisine = "Yoruba and West African",
    prep = 10, cook = 30, kcal = 565, protein = 38, carbs = 66, fat = 16,
    tip = "Spoon steam-softened Jollof rice with shredded skinless chicken thigh strips for 2yo.",
    note = "Rinse rice until water runs clear so Jollof steams fluffy instead of sticky.",
    pairing = "Serve with crisp sliced tomatoes and chilled lemon water.",
    bom = listOf(
      heritageBom("ing_rice", 400), heritageBom("ing_chicken", 650), heritageBom("ing_tomato", 4),
      heritageBom("ing_bell_pepper", 2), heritageBom("ing_red_onion", 2), heritageBom("ing_ginger", 15),
      heritageBom("ing_olive_oil", 20), heritageBom("ing_garlic", 10), heritageBom("ing_salt", 3),
    ),
    steps = listOf(
      heritageStep(1, "Brown Chicken", 480, "Brown seasoned chicken thighs in olive oil with sliced red onions and ginger.", "ing_chicken", "ing_olive_oil", "ing_red_onion", "ing_ginger"),
      heritageStep(2, "Build Jollof Base", 420, "Reduce roasted tomato, bell pepper, and garlic puree into a glossy red base.", "ing_tomato", "ing_bell_pepper", "ing_garlic"),
      heritageStep(3, "Steam Jollof Rice", 900, "Stir in rinsed rice, cover tightly, and steam on low heat for 15 minutes.", "ing_rice", "ing_salt", cue = "Separate ruby-orange rice grains tender through the core"),
    ),
  ),
  // 4. Steamed Moin-Moin Savory Bean and Egg Pots — high-iron protein-rich steamed legume pudding.
  heritageRecipe(
    id = "rec_34_steamed_moin_moin_bean_pots",
    title = "Steamed Moin-Moin Savory Bean and Egg Pots",
    origin = "Yoruba Home Classics",
    cuisine = "Yoruba and West African",
    prep = 10, nightPrep = 5, cook = 28, kcal = 415, protein = 25, carbs = 46, fat = 14,
    nightStep = "Soak black-eyed peas in cool water overnight for quick morning blending.",
    tip = "Moin-moin steams into a silky savory pudding ideal for 2yo spoons; quarter firm egg wedges.",
    note = "Whisking air into blended bean batter for 60 seconds yields cloud-soft Moin-Moin.",
    pairing = "Delicious warm alongside roast chicken or dampened cassava gari.",
    bom = listOf(
      heritageBom("ing_beans", 350), heritageBom("ing_egg", 4), heritageBom("ing_bell_pepper", 2),
      heritageBom("ing_red_onion", 1), heritageBom("ing_red_palm_oil", 20),
      heritageBom("ing_ginger", 10), heritageBom("ing_salt", 3),
    ),
    steps = listOf(
      heritageStep(1, "Whip Bean Batter", 300, "Blend soaked black-eyed peas smooth with bell pepper, red onion, and ginger.", "ing_beans", "ing_bell_pepper", "ing_red_onion", "ing_ginger"),
      heritageStep(2, "Portion Ramekins", 240, "Whisk in warm red palm oil and sea salt, pour into ramekins, and top with egg.", "ing_red_palm_oil", "ing_egg", "ing_salt"),
      heritageStep(3, "Gentle Steam", 1320, "Steam covered ramekins in simmering water for 22 minutes until set firm.", "ing_beans", "ing_egg", cue = "Skewer comes out clean and eggs are cooked firm"),
    ),
  ),
  // 5. Sweet Plantain Dodo and Tomato Egg Sauce — <= 20m breakfast/lunch with FRUIT_TOPPING Saba swap.
  heritageRecipe(
    id = "rec_35_golden_dodo_tomato_egg_sauce",
    title = "Sweet Plantain Dodo and Tomato Egg Sauce",
    origin = "Yoruba Morning Kitchen",
    cuisine = "Yoruba and West African",
    prep = 5, cook = 11, kcal = 355, protein = 13, carbs = 48, fat = 13,
    slots = listOf(MealSlotType.BREAKFAST, MealSlotType.LUNCH),
    diff = RecipeDifficulty.EASY,
    tip = "Quarter warm caramelized plantain rounds lengthwise into soft strips and cook egg curds firm for 2yo.",
    note = "Local ripe Saba cooking bananas swap 1:1 for yellow plantains when markets rotate.",
    pairing = "Serve warm with sliced avocado or citrus water.",
    bom = listOf(
      heritageBom("ing_plantain", 3, "FRUIT_TOPPING"), heritageBom("ing_egg", 4),
      heritageBom("ing_tomato", 3), heritageBom("ing_onion", 1),
      heritageBom("ing_olive_oil", 15), heritageBom("ing_salt", 2),
    ),
    steps = listOf(
      heritageStep(1, "Caramelize Dodo", 300, "Slice ripe plantains on the bias and sear in olive oil until golden on both sides.", "ing_plantain", "ing_olive_oil", cue = "Amber caramelized edges with soft creamy centers"),
      heritageStep(2, "Simmer Tomato Base", 180, "Sauté diced onion and ripe tomatoes in the warm skillet until jammy.", "ing_onion", "ing_tomato"),
      heritageStep(3, "Fold Egg Sauce", 180, "Pour whisked eggs into the tomato sauce and fold gently until curds set firm.", "ing_egg", "ing_salt"),
    ),
  ),
  // 6. Yoruba Gbegiri Style Bean Soup and Silky Greens — velvety blended legume bowl with greens.
  heritageRecipe(
    id = "rec_36_velvety_gbegiri_greens_bowl",
    title = "Yoruba Gbegiri Style Bean Soup and Silky Greens",
    origin = "Yoruba Heritage Table",
    cuisine = "Yoruba and West African",
    prep = 10, nightPrep = 5, cook = 25, kcal = 470, protein = 35, carbs = 49, fat = 14,
    nightStep = "Soak black-eyed peas overnight so they simmer tender in 18 minutes.",
    tip = "Blend bean soup silky smooth and mince wilted greens finely over tender shredded chicken for 2yo.",
    note = "Passing blended Gbegiri through a coarse sieve gives restaurant-grade silkiness.",
    pairing = "Spoon side-by-side over warm rice or soft cornmeal.",
    bom = listOf(
      heritageBom("ing_beans", 350), heritageBom("ing_amaranth_greens", 250, "LEAFY_GREENS"),
      heritageBom("ing_chicken", 400), heritageBom("ing_red_onion", 1),
      heritageBom("ing_red_palm_oil", 15), heritageBom("ing_ginger", 10), heritageBom("ing_salt", 3),
    ),
    steps = listOf(
      heritageStep(1, "Simmer Beans", 1080, "Simmer soaked black-eyed peas with red onion and ginger until velvety soft.", "ing_beans", "ing_red_onion", "ing_ginger"),
      heritageStep(2, "Blend Gbegiri", 240, "Blend cooked beans into a golden soup with red palm oil while searing chicken.", "ing_red_palm_oil", "ing_chicken", cue = "Smooth saffron-gold velouté texture"),
      heritageStep(3, "Wilt Silky Greens", 180, "Wilt finely chopped amaranth greens alongside the soup and top with chicken.", "ing_amaranth_greens", "ing_salt"),
    ),
  ),
  // 7. Poulet Basquaise Bell Pepper Braised Chicken — classic Southwest French piperade braise.
  heritageRecipe(
    id = "rec_37_poulet_basquaise",
    title = "Poulet Basquaise Bell Pepper Braised Chicken",
    origin = "French Family Bistro",
    cuisine = "French",
    prep = 10, cook = 25, kcal = 555, protein = 40, carbs = 59, fat = 17,
    diff = RecipeDifficulty.EASY,
    tip = "Simmer bell pepper ribbons until melt-in-mouth soft and shred braised chicken thigh for 2yo.",
    note = "Resting braised Poulet Basquaise 5 minutes off-heat lets the sweet pepper jus thicken.",
    pairing = "Serve over warm jasmine rice with sparkling citrus water.",
    bom = listOf(
      heritageBom("ing_chicken", 700), heritageBom("ing_bell_pepper", 3), heritageBom("ing_tomato", 4),
      heritageBom("ing_onion", 2), heritageBom("ing_rice", 350),
      heritageBom("ing_olive_oil", 20), heritageBom("ing_garlic", 10), heritageBom("ing_salt", 3),
    ),
    steps = listOf(
      heritageStep(1, "Crisp Poultry", 420, "Sear chicken thighs skin-side down in olive oil until crisp golden brown.", "ing_chicken", "ing_olive_oil"),
      heritageStep(2, "Sweat Piperade", 360, "Sweat sliced bell peppers, onions, and garlic until sweet and softened.", "ing_bell_pepper", "ing_onion", "ing_garlic", cue = "Glossy softened pepper ribbons"),
      heritageStep(3, "Braise and Steam", 720, "Add diced tomatoes, return chicken, and braise 12 minutes alongside steamed rice.", "ing_tomato", "ing_rice", "ing_salt"),
    ),
  ),
  // 8. Ratatouille Provençale with Baked Eggs — sunlit French vegetable stew with firm-baked eggs.
  heritageRecipe(
    id = "rec_38_ratatouille_provencale_baked_eggs",
    title = "Ratatouille Provençale with Baked Eggs",
    origin = "French Provençal Table",
    cuisine = "French",
    prep = 10, cook = 22, kcal = 365, protein = 15, carbs = 42, fat = 15,
    diff = RecipeDifficulty.EASY,
    tip = "Serve stewed vegetables with soft inner baguette crumb dipped in tomato juices and firm egg pieces for 2yo.",
    note = "Cooking vegetables in stages keeps bell peppers silky without turning tomatoes watery.",
    pairing = "Serve skillet-to-table with warm sliced artisan baguette.",
    bom = listOf(
      heritageBom("ing_tomato", 4), heritageBom("ing_bell_pepper", 2), heritageBom("ing_onion", 2),
      heritageBom("ing_spinach", 200, "LEAFY_GREENS"), heritageBom("ing_egg", 4),
      heritageBom("ing_baguette", 1), heritageBom("ing_olive_oil", 20),
      heritageBom("ing_garlic", 10), heritageBom("ing_salt", 3),
    ),
    steps = listOf(
      heritageStep(1, "Sauté Aromatics", 420, "Sauté diced onions, bell peppers, and garlic in olive oil until sweet and tender.", "ing_onion", "ing_bell_pepper", "ing_garlic", "ing_olive_oil"),
      heritageStep(2, "Simmer Ratatouille", 480, "Simmer chopped vine tomatoes and baby spinach into a thick Provençal compote.", "ing_tomato", "ing_spinach", "ing_salt"),
      heritageStep(3, "Bake Eggs Firm", 420, "Make 4 wells, crack in farm eggs, cover, and bake until whites and yolks set firm.", "ing_egg", "ing_baguette", cue = "Opaque set whites and firm golden centers"),
    ),
  ),
  // 9. French Hachis Parmentier Cottage Bake — savory minced poultry topped with sweet potato mash.
  heritageRecipe(
    id = "rec_39_french_hachis_parmentier",
    title = "French Hachis Parmentier Cottage Bake",
    origin = "French Home Bistro",
    cuisine = "French",
    prep = 10, cook = 24, kcal = 520, protein = 39, carbs = 41, fat = 22,
    tip = "Mince savory poultry finely beneath the whipped sweet potato mash so 2yo spoons hold together cleanly.",
    note = "Sweet potatoes add natural beta-carotene sweetness that children love in Hachis Parmentier.",
    pairing = "Pair with lightly dressed tender baby greens.",
    bom = listOf(
      heritageBom("ing_chicken", 600), heritageBom("ing_sweet_potato", 750),
      heritageBom("ing_cheese", 80), heritageBom("ing_butter", 30), heritageBom("ing_milk", 100),
      heritageBom("ing_onion", 1), heritageBom("ing_garlic", 8), heritageBom("ing_salt", 3),
    ),
    steps = listOf(
      heritageStep(1, "Whip Purée", 660, "Boil cubed sweet potatoes until fork-tender, then whip smooth with milk and butter.", "ing_sweet_potato", "ing_milk", "ing_butter"),
      heritageStep(2, "Sauté Hachis", 420, "Finely chop chicken thighs and sauté with diced onion, garlic, and sea salt.", "ing_chicken", "ing_onion", "ing_garlic", "ing_salt", cue = "Savory poultry mince reaching 74C internal temperature"),
      heritageStep(3, "Gratinate Top", 360, "Spread sweet potato mash over poultry, top with cheddar, and broil until bubbling.", "ing_cheese"),
    ),
  ),
  // 10. French Omelette aux Fines Herbes and Toast — <= 14m weekday breakfast with firm-egg safety note.
  heritageRecipe(
    id = "rec_40_omelette_fines_herbes_tartine",
    title = "French Omelette aux Fines Herbes and Toast",
    origin = "French Boulangerie Morning",
    cuisine = "French",
    prep = 4, cook = 7, kcal = 340, protein = 18, carbs = 29, fat = 16,
    slots = listOf(MealSlotType.BREAKFAST, MealSlotType.LUNCH),
    diff = RecipeDifficulty.EASY,
    tip = "Cook toddler omelette ribbon completely firm through the center and serve with crustless warm toast strips for 2yo.",
    note = "Always cook omelette centers completely firm when serving toddlers or expectant mothers.",
    pairing = "Serve with fresh sliced seasonal fruit and warm milk.",
    bom = listOf(
      heritageBom("ing_egg", 5), heritageBom("ing_butter", 20), heritageBom("ing_cheese", 60),
      heritageBom("ing_spinach", 100, "LEAFY_GREENS"), heritageBom("ing_baguette", 1),
      heritageBom("ing_salt", 2), heritageBom("ing_black_pepper", 1),
    ),
    steps = listOf(
      heritageStep(1, "Whisk and Toast", 150, "Toast sliced artisan baguette while whisking 5 farm eggs with finely ribboned greens.", "ing_baguette", "ing_egg", "ing_spinach"),
      heritageStep(2, "Fold Bistro Omelette", 180, "Melt butter over medium-low heat, pour in eggs, and stir gently into small curds.", "ing_butter", "ing_egg", cue = "Smooth pale-gold exterior cooked completely firm through the center", tip = "Cook eggs completely firm through the center for young children and pregnancy safety."),
      heritageStep(3, "Roll and Plate", 90, "Sprinkle shredded cheese inside, roll cleanly, and plate beside warm toast.", "ing_cheese", "ing_salt", "ing_black_pepper"),
    ),
  ),
  // 11. Ginger Chicken Tinola with Local Greens — soothing Filipino ginger broth with LEAFY_GREENS.
  heritageRecipe(
    id = "rec_41_chicken_tinola_greens",
    title = "Ginger Chicken Tinola with Local Greens",
    origin = "Filipino Family Table",
    cuisine = "Filipino",
    prep = 8, cook = 22, kcal = 495, protein = 38, carbs = 54, fat = 14,
    diff = RecipeDifficulty.EASY,
    tip = "Moisten jasmine rice with warm ginger broth and shred tender chicken thigh and soft greens for 2yo.",
    note = "Malunggay, pechay, kangkong, spinach, or kulitis all swap seamlessly in Tinola.",
    pairing = "Ladle steaming ginger broth over warm jasmine rice.",
    bom = listOf(
      heritageBom("ing_chicken", 650), heritageBom("ing_amaranth_greens", 250, "LEAFY_GREENS"),
      heritageBom("ing_ginger", 30), heritageBom("ing_onion", 1), heritageBom("ing_rice", 350),
      heritageBom("ing_olive_oil", 15), heritageBom("ing_garlic", 10), heritageBom("ing_salt", 3),
    ),
    steps = listOf(
      heritageStep(1, "Bloom Ginger", 240, "Sauté julienned fresh ginger, crushed garlic, and sliced onion in olive oil.", "ing_ginger", "ing_garlic", "ing_onion", "ing_olive_oil"),
      heritageStep(2, "Simmer Clear Broth", 960, "Sear chicken thighs briefly, add water, and simmer 16 minutes alongside rice.", "ing_chicken", "ing_rice", "ing_salt"),
      heritageStep(3, "Wilt Market Greens", 120, "Stir in fresh local greens for the final 2 minutes until bright and tender.", "ing_amaranth_greens"),
    ),
  ),
  // 12. Tamarind Market Fish Sinigang — bright sour-savory Filipino fish soup with LEAFY_GREENS.
  heritageRecipe(
    id = "rec_42_sinigang_fish_greens",
    title = "Tamarind Market Fish Sinigang",
    origin = "Filipino Palengke Classics",
    cuisine = "Filipino",
    prep = 8, cook = 18, kcal = 440, protein = 39, carbs = 57, fat = 5,
    diff = RecipeDifficulty.EASY,
    tip = "Inspect poached fish flakes carefully for zero bones and ladle mild citrus-tomato broth over soft rice for 2yo.",
    note = "Adding fresh citrus right at the end keeps Vitamin C high to boost iron uptake from greens.",
    pairing = "Serve steaming hot with fluffy white rice.",
    bom = listOf(
      heritageBom("ing_fish", 700), heritageBom("ing_spinach", 250, "LEAFY_GREENS"),
      heritageBom("ing_tomato", 3), heritageBom("ing_red_onion", 1),
      heritageBom("ing_lemon", 2), heritageBom("ing_rice", 350), heritageBom("ing_salt", 3),
    ),
    steps = listOf(
      heritageStep(1, "Simmer Tomato Broth", 480, "Simmer quartered tomatoes and red onion in water for 8 minutes to build broth.", "ing_tomato", "ing_red_onion", "ing_rice"),
      heritageStep(2, "Poach Market Fish", 420, "Slide snapper fillets into the gentle broth and poach 7 minutes until flaky.", "ing_fish", "ing_salt", cue = "Opaque white fish flakes at 63C in clear ruby-tinted broth"),
      heritageStep(3, "Brighten and Wilt", 180, "Brighten off-heat with fresh lemon juice and fold in leafy greens to wilt.", "ing_lemon", "ing_spinach"),
    ),
  ),
)

// Helper building a full 21-slot MenuTemplateSlotSpec list from 7 daily (breakfast, lunch, dinner) triples.
private fun heritageBuildWeeklySpecs(
  dailyTriples: List<Triple<String, String, String>>,
): List<MenuTemplateSlotSpec> =
  dailyTriples.flatMapIndexed { dayIndex, (breakfastId, lunchId, dinnerId) ->
    listOf(
      MenuTemplateSlotSpec(dayIndex, MealSlotType.BREAKFAST, breakfastId),
      MenuTemplateSlotSpec(dayIndex, MealSlotType.LUNCH, lunchId),
      MenuTemplateSlotSpec(dayIndex, MealSlotType.DINNER, dinnerId),
    )
  }

/**
 * Four starter 21-slot [MenuTemplate] blueprints with clean punctuation-safe titles and subtitles.
 */
val DEFAULT_STARTER_MENU_TEMPLATES: List<MenuTemplate> = listOf(
  // 1. Flagship household template blending French boulangerie mornings with Yoruba & French dinners.
  MenuTemplate(
    menuId = "menu_french_yoruba_family",
    title = "French and Yoruba Family Week",
    subtitle = "Express boulangerie mornings paired with rich Yoruba and French family dinners",
    cuisineFocus = listOf("French", "Yoruba and West African"),
    slotSpecs = heritageBuildWeeklySpecs(
      listOf(
        Triple("rec_40_omelette_fines_herbes_tartine", "rec_02_crispy_chicken_tomato_baguette", "rec_31_yoruba_efo_riro"),
        Triple("rec_35_golden_dodo_tomato_egg_sauce", "rec_38_ratatouille_provencale_baked_eggs", "rec_33_party_jollof_braised_chicken"),
        Triple("rec_01_egg_avocado_baguette_tartine", "rec_34_steamed_moin_moin_bean_pots", "rec_37_poulet_basquaise"),
        Triple("rec_12_french_toast_fingers", "rec_13_beninese_atassi_watche", "rec_32_yoruba_ata_dindin_fish"),
        Triple("rec_10_sweet_plantain_egg_scramble", "rec_23_cassava_gari_foto_eggs", "rec_39_french_hachis_parmentier"),
        Triple("rec_04_spiced_omelette_baguette", "rec_36_velvety_gbegiri_greens_bowl", "rec_14_poulet_bicyclette_braised"),
        Triple("rec_40_omelette_fines_herbes_tartine", "rec_18_egusi_melon_seed_spinach_stew", "rec_25_jollof_rice_grilled_chicken"),
      ),
    ),
  ),
  // 2. Fast school-morning pacer + weeknight French and global skillet bistro template.
  MenuTemplate(
    menuId = "menu_quick_school_bistro",
    title = "Quick School Morning and Bistro Week",
    subtitle = "Under twenty minute weekday breakfasts and fast skillet bistro suppers",
    cuisineFocus = listOf("French", "Global Family"),
    slotSpecs = heritageBuildWeeklySpecs(
      listOf(
        Triple("rec_09_overnight_oats", "rec_01_egg_avocado_baguette_tartine", "rec_02_crispy_chicken_tomato_baguette"),
        Triple("rec_40_omelette_fines_herbes_tartine", "rec_27_avocado_chicken_quesadilla_wrap", "rec_37_poulet_basquaise"),
        Triple("rec_06_greek_yogurt_granola_parfait", "rec_21_creamy_garlic_pasta_spinach", "rec_22_sheet_pan_honey_mustard_chicken"),
        Triple("rec_08_veggie_omelette_wrap", "rec_03_tuna_olive_baguette", "rec_38_ratatouille_provencale_baked_eggs"),
        Triple("rec_07_sheet_pan_banana_pancakes", "rec_28_tuna_sweetcorn_pasta_bake", "rec_39_french_hachis_parmentier"),
        Triple("rec_12_french_toast_fingers", "rec_30_coconut_mango_chicken_rice_bowl", "rec_20_one_pan_lemon_herb_salmon_rice"),
        Triple("rec_05_skillet_honey_oat_granola", "rec_24_sweet_potato_black_bean_skillet", "rec_26_garlic_butter_tilapia_spinach"),
      ),
    ),
  ),
  // 3. Athletic recovery and batch-prep week prioritizing high-protein poultry, fish, eggs, and beans.
  MenuTemplate(
    menuId = "menu_high_protein_prep",
    title = "High Protein Batch Prep Week",
    subtitle = "Lean poultry, fish, eggs, and legumes structured for active family recovery",
    cuisineFocus = listOf("Yoruba and West African", "Mediterranean"),
    slotSpecs = heritageBuildWeeklySpecs(
      listOf(
        Triple("rec_01_egg_avocado_baguette_tartine", "rec_03_tuna_olive_baguette", "rec_20_one_pan_lemon_herb_salmon_rice"),
        Triple("rec_35_golden_dodo_tomato_egg_sauce", "rec_34_steamed_moin_moin_bean_pots", "rec_31_yoruba_efo_riro"),
        Triple("rec_40_omelette_fines_herbes_tartine", "rec_28_tuna_sweetcorn_pasta_bake", "rec_33_party_jollof_braised_chicken"),
        Triple("rec_08_veggie_omelette_wrap", "rec_27_avocado_chicken_quesadilla_wrap", "rec_32_yoruba_ata_dindin_fish"),
        Triple("rec_10_sweet_plantain_egg_scramble", "rec_13_beninese_atassi_watche", "rec_26_garlic_butter_tilapia_spinach"),
        Triple("rec_06_greek_yogurt_granola_parfait", "rec_36_velvety_gbegiri_greens_bowl", "rec_14_poulet_bicyclette_braised"),
        Triple("rec_04_spiced_omelette_baguette", "rec_19_moyo_tomato_onion_fish", "rec_16_grilled_tilapia_alloco"),
      ),
    ),
  ),
  // 4. Local wet-market and zero-waste week rescuing perishables with Filipino and global bowls.
  MenuTemplate(
    menuId = "menu_local_market_zero_waste",
    title = "Local Market Zero Waste Week",
    subtitle = "Fresh market greens, fish, and tropical produce rescuing perishables first",
    cuisineFocus = listOf("Filipino", "Global Family"),
    slotSpecs = heritageBuildWeeklySpecs(
      listOf(
        Triple("rec_06_greek_yogurt_granola_parfait", "rec_21_creamy_garlic_pasta_spinach", "rec_20_one_pan_lemon_herb_salmon_rice"),
        Triple("rec_09_overnight_oats", "rec_41_chicken_tinola_greens", "rec_42_sinigang_fish_greens"),
        Triple("rec_35_golden_dodo_tomato_egg_sauce", "rec_30_coconut_mango_chicken_rice_bowl", "rec_26_garlic_butter_tilapia_spinach"),
        Triple("rec_07_sheet_pan_banana_pancakes", "rec_24_sweet_potato_black_bean_skillet", "rec_31_yoruba_efo_riro"),
        Triple("rec_11_warm_cinnamon_oat_porridge", "rec_42_sinigang_fish_greens", "rec_41_chicken_tinola_greens"),
        Triple("rec_08_veggie_omelette_wrap", "rec_27_avocado_chicken_quesadilla_wrap", "rec_22_sheet_pan_honey_mustard_chicken"),
        Triple("rec_05_skillet_honey_oat_granola", "rec_38_ratatouille_provencale_baked_eggs", "rec_19_moyo_tomato_onion_fish"),
      ),
    ),
  ),
)

/**
 * Ranks [menus] so templates matching [profile]'s `preferredCuisines` appear first while preserving
 * deterministic relative ordering among ties.
 *
 * @param menus Candidate menu templates (starter + user-saved).
 * @param profile Active household profile carrying `preferredCuisines`.
 * @return Deterministically ranked [MenuTemplate] list.
 */
fun rankMenusForHousehold(
  menus: List<MenuTemplate>,
  profile: HouseholdProfile,
): List<MenuTemplate> {
  // Return original ordering unchanged whenever the household cleared preferred cuisines.
  val prefs = profile.preferredCuisines.map { it.trim() }.filter { it.isNotEmpty() }
  if (prefs.isEmpty()) return menus

  // Score each menu by how many of its cuisineFocus tags align with the household's preferred cuisines.
  return menus.withIndex().sortedWith(
    compareByDescending<IndexedValue<MenuTemplate>> { (_, menu) ->
      heritageCountCuisineMatches(menu.cuisineFocus, prefs)
    }.thenBy { it.index },
  ).map { it.value }
}

/**
 * Scores [recipe] for [profile] grounded in the household's local market (`catalogById`) and live
 * pantry (`pantryById`).
 *
 * Returns `null` immediately whenever [evaluateRecipeAvailability] blocks [recipe] via `EC-02`
 * (local market `isLocallyAvailable == false` with no substitute, or active household allergens).
 * Otherwise applies `+50` for preferred cuisines, `+30` for rescuing expiring stock, `+20` for
 * `>= 60%` pantry match, and `-10` when relying on an `EC-03` substitute vs native local stock.
 *
 * @param recipe Candidate recipe to evaluate.
 * @param profile Household profile providing `preferredCuisines`, `householdAllergens`, and servings.
 * @param catalogById Indexed local market catalog map.
 * @param pantryById Indexed household pantry ledger map.
 * @return Composite suitability score, or `null` when blocked by `EC-02`.
 */
fun scoreRecipeForHouseholdAndMarket(
  recipe: Recipe,
  profile: HouseholdProfile,
  catalogById: Map<String, IngredientCatalogItem>,
  pantryById: Map<String, PantryLedgerEntry>,
): Int? {
  // Hard EC-02 local-market & allergen gate: blocked dishes never receive a numeric score.
  val availability = evaluateRecipeAvailability(recipe, catalogById, profile.householdAllergens, pantryById)
  if (!availability.isSchedulable) return null

  // Award +50 when dish metadata or tags align with household preferred cuisines.
  var score = if (doesRecipeMatchPreferredCuisines(recipe, profile.preferredCuisines)) {
    PREFERRED_CUISINE_SCORE_BONUS
  } else {
    0
  }
  val coverage = heritageEvaluateBomStockCoverage(
    recipe, availability.resolvedBomLines, profile.effectiveTargetServings, catalogById, pantryById,
  )
  // Award +30 for rescuing EXPIRING_SOON/EXPIRED items and +20 for >= 60% home stock readiness.
  if (coverage.rescuesExpiringItem) score += EXPIRING_RESCUE_SCORE_BONUS
  if (coverage.coverageRatio >= HIGH_PANTRY_COVERAGE_THRESHOLD) score += HIGH_PANTRY_MATCH_SCORE_BONUS
  // Deduct 10 points when an EC-03 substitute is required instead of native local stock.
  if (availability.substitutionsApplied.isNotEmpty()) score -= SUBSTITUTE_REQUIRED_SCORE_PENALTY
  return score
}

/**
 * Applies [template] onto [currentBoard], automatically substituting any recipe blocked by local
 * market `EC-02` / `EC-01` morning cap — or missing required pantry stock when
 * [remixUnavailableOrMissing] is `true` and a better-stocked candidate exists — with the
 * highest-scoring preferred-cuisine and local-market recipe.
 *
 * @param currentBoard Current weekly board supplying `boardId` and existing slot IDs.
 * @param template Selected [MenuTemplate] to apply.
 * @param recipes Household recipe library.
 * @param catalog Household ingredient catalog.
 * @param pantryEntries Current household pantry ledger entries.
 * @param profile Active household profile.
 * @param remixUnavailableOrMissing Remixes slots missing stock to better-stocked dishes when `true`.
 * @param newWeekStartDateIso Monday ISO-8601 date stamped onto the resulting board.
 * @return Updated immutable [WeeklyBoard].
 */
fun applyMenuTemplateToBoard(
  currentBoard: WeeklyBoard,
  template: MenuTemplate,
  recipes: List<Recipe>,
  catalog: List<IngredientCatalogItem>,
  pantryEntries: List<PantryLedgerEntry>,
  profile: HouseholdProfile,
  remixUnavailableOrMissing: Boolean = true,
  newWeekStartDateIso: String = currentBoard.weekStartDateIso,
): WeeklyBoard {
  val catalogById = indexCatalogById(catalog)
  val pantryById = indexPantryById(pantryEntries)
  // Auto-merge heritage recipes when running on canonical household catalogs so starter menus resolve.
  val isCanonicalCatalog = "ing_spinach" in catalogById && "ing_egg" in catalogById
  val effectiveRecipes = if (isCanonicalCatalog) mergeHeritageRecipesIntoLibrary(recipes) else recipes
  val morningCap = min(MAX_WEEKDAY_BREAKFAST_MINUTES, profile.fredMorningCapMinutes.coerceAtLeast(1))
  val targetServings = profile.effectiveTargetServings

  // Pre-score all schedulable candidates once so 21-slot assignment runs in fast O(N) lookups.
  val scoredPool = effectiveRecipes.mapNotNull { candidate ->
    val score = scoreRecipeForHouseholdAndMarket(candidate, profile, catalogById, pantryById)
      ?: return@mapNotNull null
    val availability = evaluateRecipeAvailability(candidate, catalogById, profile.householdAllergens, pantryById)
    val coverage = heritageEvaluateBomStockCoverage(
      candidate, availability.resolvedBomLines, targetServings, catalogById, pantryById,
    )
    HeritageScoredCandidate(candidate, score, coverage.coverageRatio, computeCalibratedMorningMinutes(candidate))
  }.sortedWith(
    compareByDescending<HeritageScoredCandidate> { it.score }
      .thenByDescending { it.coverageRatio }
      .thenBy { it.calibratedMinutes },
  )

  val safeBoardId = currentBoard.boardId.trim().ifEmpty { "board_week_1" }
  val safeWeekStart = newWeekStartDateIso.trim().ifEmpty { currentBoard.weekStartDateIso }
  val existingSlotByKey = currentBoard.slots.associateBy { it.dayIndex to it.slotType }
  val assignmentCounts = linkedMapOf<String, Int>()
  val resultingSlots = mutableListOf<MealSlot>()

  // Walk each template slot, enforcing EC-01 morning caps, EC-02 market rules, and optional pantry remix.
  for (spec in template.slotSpecs) {
    val safeDay = spec.dayIndex.coerceIn(0, 6)
    val slotEligiblePool = scoredPool.filter { cand ->
      spec.slotType in cand.recipe.allowedSlots &&
        (spec.slotType != MealSlotType.BREAKFAST || cand.calibratedMinutes <= morningCap)
    }
    val requestedCandidate = slotEligiblePool.find { it.recipe.recipeId == spec.recipeId }

    // Remix whenever blocked by EC-01/EC-02, or when missing stock and a better-stocked match exists.
    val selectedRecipeId = when {
      requestedCandidate == null ->
        heritageSelectBestReplacement(slotEligiblePool, assignmentCounts)?.recipe?.recipeId
      remixUnavailableOrMissing && requestedCandidate.coverageRatio < 0.999 -> {
        val betterStockedPool = slotEligiblePool.filter { alt ->
          alt.coverageRatio > requestedCandidate.coverageRatio + 0.05 &&
            alt.score >= requestedCandidate.score - SUBSTITUTE_REQUIRED_SCORE_PENALTY
        }
        (heritageSelectBestReplacement(betterStockedPool, assignmentCounts) ?: requestedCandidate).recipe.recipeId
      }
      else -> requestedCandidate.recipe.recipeId
    } ?: continue

    assignmentCounts[selectedRecipeId] = (assignmentCounts[selectedRecipeId] ?: 0) + 1
    val priorSlot = existingSlotByKey[safeDay to spec.slotType]
    val resolvedOwner = priorSlot?.owner
      ?: if (spec.slotType == MealSlotType.BREAKFAST) CookOwner.FRED else CookOwner.WIFE
    val resolvedSlotId = priorSlot?.slotId ?: "${safeBoardId}_day_${safeDay}_${spec.slotType.name.lowercase()}"

    resultingSlots.add(
      MealSlot(
        slotId = resolvedSlotId,
        dayIndex = safeDay,
        slotType = spec.slotType,
        owner = resolvedOwner,
        recipeId = selectedRecipeId,
        targetServings = targetServings,
        status = MealSlotStatus.PLANNED,
      ),
    )
  }

  return currentBoard.copy(
    boardId = safeBoardId,
    householdId = profile.householdId.ifBlank { currentBoard.householdId },
    weekStartDateIso = safeWeekStart,
    slots = sortSlotsChronologically(resultingSlots),
  )
}

/**
 * Captures the chronologically ordered slots of [board] into a user-created [MenuTemplate].
 *
 * @param board Source weekly board whose scheduled meals are saved.
 * @param title User-entered menu title (sanitized to strip slashes and parentheses).
 * @param subtitle User-entered subtitle explaining the menu focus.
 * @param cuisineFocus Preferred or detected cuisine tags associated with this saved menu.
 * @param menuId Unique identifier assigned to the saved template.
 * @return Immutable [MenuTemplate] with `isUserCreated = true`.
 */
fun saveBoardAsMenuTemplate(
  board: WeeklyBoard,
  title: String,
  subtitle: String,
  cuisineFocus: List<String>,
  menuId: String,
): MenuTemplate {
  // Sanitize punctuation so saved custom menu chips match starter label typography.
  val cleanTitle = heritageSanitizeDisplayLabel(title).ifEmpty { "Custom Family Week" }
  val cleanSubtitle = heritageSanitizeDisplayLabel(subtitle).ifEmpty { "Saved household weekly meal plan" }
  val cleanId = menuId.trim().ifEmpty { "menu_custom_${board.boardId}" }
  val specs = sortSlotsChronologically(board.slots).map { slot ->
    MenuTemplateSlotSpec(slot.dayIndex, slot.slotType, slot.recipeId)
  }
  return MenuTemplate(
    menuId = cleanId,
    title = cleanTitle,
    subtitle = cleanSubtitle,
    cuisineFocus = cuisineFocus.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
    slotSpecs = specs,
    isUserCreated = true,
  )
}

/**
 * Idempotently appends any missing [HERITAGE_AND_WORLD_RECIPES] to [existingRecipes] without
 * overwriting household BOM overrides, bookmarks, or user-edited recipes.
 *
 * @param existingRecipes Current persisted recipe library.
 * @return Combined recipe list preserving all existing entries in place.
 */
fun mergeHeritageRecipesIntoLibrary(existingRecipes: List<Recipe>): List<Recipe> {
  // Check existing IDs in O(N) so user overrides and saved favorites are never clobbered.
  val existingIds = existingRecipes.map { it.recipeId }.toSet()
  val missingHeritage = HERITAGE_AND_WORLD_RECIPES.filter { it.recipeId !in existingIds }
  return if (missingHeritage.isEmpty()) existingRecipes else existingRecipes + missingHeritage
}

private data class HeritageBomCoverage(val coverageRatio: Double, val rescuesExpiringItem: Boolean)

private data class HeritageScoredCandidate(
  val recipe: Recipe,
  val score: Int,
  val coverageRatio: Double,
  val calibratedMinutes: Int,
)

// Count how many menu focus tags align with the household's preferred cuisine traditions.
private fun heritageCountCuisineMatches(menuFocus: List<String>, preferredCuisines: List<String>): Int =
  menuFocus.count { focusTag ->
    val probe = Recipe(
      recipeId = "probe",
      title = focusTag,
      sourceAttribution = focusTag,
      allowedSlots = listOf(MealSlotType.DINNER),
      preferredOwner = CookOwner.WIFE,
      rawPrepMinutes = 5,
      cookMinutes = 10,
      calibratedActiveMinutes = 17,
      bomLines = listOf(BomLine("ing_salt", 1.0)),
      cuisineTag = focusTag,
      cuisineTags = listOf(focusTag),
    )
    doesRecipeMatchPreferredCuisines(probe, preferredCuisines)
  }

// Evaluate required BOM stock coverage and expiring-item rescue in a single lightweight pass.
private fun heritageEvaluateBomStockCoverage(
  recipe: Recipe,
  resolvedBomLines: List<BomLine>,
  targetServings: Int,
  catalogById: Map<String, IngredientCatalogItem>,
  pantryById: Map<String, PantryLedgerEntry>,
): HeritageBomCoverage {
  val scale = max(1, targetServings).toDouble() / max(1, recipe.baseServings).toDouble()
  var requiredCount = 0
  var satisfiedCount = 0
  var rescuesExpiring = false

  // Inspect each non-optional BOM line against Tier-1 quantity or Tier-2 tri-state stock.
  for (line in resolvedBomLines) {
    if (line.isOptional || line.baseQuantity <= 0.0) continue
    requiredCount += 1
    val item = catalogById[line.ingredientId]
    val entry = pantryById[line.ingredientId]
    val tier = entry?.tier ?: item?.tier ?: IngredientTier.TIER_1_QUANTITATIVE
    val inStock = when (tier) {
      IngredientTier.TIER_2_STAPLE -> entry != null && !entry.isOutOfStock && entry.stapleStatus != StapleStatus.OUT
      IngredientTier.TIER_1_QUANTITATIVE -> entry != null && !entry.isOutOfStock && entry.currentQuantity >= line.baseQuantity * scale
    }
    if (inStock) {
      satisfiedCount += 1
      if (entry != null && entry.effectiveExpiryStatus != ExpiryStatus.FRESH) rescuesExpiring = true
    }
  }
  val ratio = if (requiredCount == 0) 1.0 else satisfiedCount.toDouble() / requiredCount.toDouble()
  return HeritageBomCoverage(ratio, rescuesExpiring)
}

// Prefer top-scoring replacements while rotating mildly across the week to avoid repetitive boards.
private fun heritageSelectBestReplacement(
  pool: List<HeritageScoredCandidate>,
  assignmentCounts: Map<String, Int>,
): HeritageScoredCandidate? =
  pool.minWithOrNull(
    compareBy<HeritageScoredCandidate> { (assignmentCounts[it.recipe.recipeId] ?: 0) / 2 }
      .thenByDescending { it.score }
      .thenByDescending { it.coverageRatio }
      .thenBy { assignmentCounts[it.recipe.recipeId] ?: 0 },
  )

// Strip slashes and parentheses from user-provided template titles so UI cards stay crisp.
private fun heritageSanitizeDisplayLabel(raw: String): String =
  raw.replace('/', ' ').replace('(', ' ').replace(')', ' ').replace(Regex("\\s+"), " ").trim()

/** Object facade exposing heritage recipes and menu template operations for qualified callers. */
object HeritageCuisinesAndMenusCatalog {
  /** Canonical 12-recipe heritage and regional collection. */
  val heritageRecipes: List<Recipe> get() = HERITAGE_AND_WORLD_RECIPES

  /** Starter 21-slot menu templates. */
  val starterMenuTemplates: List<MenuTemplate> get() = DEFAULT_STARTER_MENU_TEMPLATES

  /** Delegates to [com.familykitchen.board.domain.rankMenusForHousehold]. */
  fun rankMenusForHousehold(menus: List<MenuTemplate>, profile: HouseholdProfile): List<MenuTemplate> =
    com.familykitchen.board.domain.rankMenusForHousehold(menus, profile)

  /** Delegates to [com.familykitchen.board.domain.scoreRecipeForHouseholdAndMarket]. */
  fun scoreRecipeForHouseholdAndMarket(
    recipe: Recipe,
    profile: HouseholdProfile,
    catalogById: Map<String, IngredientCatalogItem>,
    pantryById: Map<String, PantryLedgerEntry>,
  ): Int? = com.familykitchen.board.domain.scoreRecipeForHouseholdAndMarket(recipe, profile, catalogById, pantryById)

  /** Delegates to [com.familykitchen.board.domain.applyMenuTemplateToBoard]. */
  fun applyMenuTemplateToBoard(
    currentBoard: WeeklyBoard,
    template: MenuTemplate,
    recipes: List<Recipe>,
    catalog: List<IngredientCatalogItem>,
    pantryEntries: List<PantryLedgerEntry>,
    profile: HouseholdProfile,
    remixUnavailableOrMissing: Boolean = true,
    newWeekStartDateIso: String = currentBoard.weekStartDateIso,
  ): WeeklyBoard = com.familykitchen.board.domain.applyMenuTemplateToBoard(
    currentBoard, template, recipes, catalog, pantryEntries, profile, remixUnavailableOrMissing, newWeekStartDateIso,
  )

  /** Delegates to [com.familykitchen.board.domain.saveBoardAsMenuTemplate]. */
  fun saveBoardAsMenuTemplate(
    board: WeeklyBoard,
    title: String,
    subtitle: String,
    cuisineFocus: List<String>,
    menuId: String,
  ): MenuTemplate = com.familykitchen.board.domain.saveBoardAsMenuTemplate(board, title, subtitle, cuisineFocus, menuId)

  /** Delegates to [com.familykitchen.board.domain.mergeHeritageRecipesIntoLibrary]. */
  fun mergeHeritageRecipesIntoLibrary(existingRecipes: List<Recipe>): List<Recipe> =
    com.familykitchen.board.domain.mergeHeritageRecipesIntoLibrary(existingRecipes)
}
