package com.familykitchen.board.ui.recipes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.BaseUnit
import com.familykitchen.board.domain.DEFAULT_PREFERRED_CUISINES
import com.familykitchen.board.domain.HouseholdProfile
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.IngredientTier
import com.familykitchen.board.domain.MAX_WEEKDAY_BREAKFAST_MINUTES
import com.familykitchen.board.domain.MealSlotType
import com.familykitchen.board.domain.ParsedIngredientDraftLine
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.RecipeCreationMode
import com.familykitchen.board.domain.SmartRecipeDraft
import com.familykitchen.board.domain.computeCalibratedMorningMinutes
import com.familykitchen.board.domain.parseRawRecipeTextToDraft
import com.familykitchen.board.ui.preview.PreviewFixtures
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme

// 3-Mode Recipe Creation & Editing Studio (`Smart Paste` · `Scan Cookbook` · `Quick Editor`)
// with zero pre-checked Eggs+Milk, Tier-1 + Tier-2 ingredient search, 0g local ingredient entry,
// non-silent Vision OCR gate, and context-aware `Add to Meal Plan` scheduling.

/**
 * Optional `(dayIndex, slotType)` plan target emitted when `Add to Meal Plan` is enabled inside
 * [AddOrEditRecipeSheet].
 *
 * @property dayIndex 0-based weekday index (`0 = Monday`..`6 = Sunday`).
 * @property slotType Target meal slot (`BREAKFAST`, `LUNCH`, or `DINNER`).
 */
data class RecipePlanScheduleTarget(
  val dayIndex: Int,
  val slotType: MealSlotType,
)

/**
 * Converts an existing [Recipe] into an editable [SmartRecipeDraft] for `Edit Recipe` sessions.
 *
 * @param recipe Source household [Recipe] to edit.
 * @param catalogById Lookup map resolving canonical display names, tiers, and units.
 * @return Populated [SmartRecipeDraft].
 */
fun buildDraftFromExistingRecipe(
  recipe: Recipe,
  catalogById: Map<String, IngredientCatalogItem>,
): SmartRecipeDraft =
  SmartRecipeDraft(
    title = recipe.title,
    servings = recipe.baseServings,
    rawPrepMinutes = recipe.rawPrepMinutes,
    cookMinutes = recipe.cookMinutes,
    allowedSlots = recipe.allowedSlots.ifEmpty { listOf(MealSlotType.DINNER) },
    cuisineTags = recipe.cuisineTags.ifEmpty { listOf(recipe.cuisineTag) },
    // Preserve exact BOM quantities, base units, and Tier-1 vs Tier-2 classification.
    ingredientLines = recipe.bomLines.map { bom ->
      val item = catalogById[bom.ingredientId]
      val unit = item?.baseUnit ?: BaseUnit.GRAM
      val tier = item?.tier ?: IngredientTier.TIER_1_QUANTITATIVE
      val name = item?.displayName ?: bom.ingredientId
      ParsedIngredientDraftLine(
        rawLine = "${bom.baseQuantity} ${unit.wireCode} $name",
        displayName = name,
        quantity = bom.baseQuantity,
        unit = unit,
        matchedCatalogId = item?.ingredientId,
        tier = tier,
      )
    },
    stepInstructions = recipe.morningSteps.map { it.instruction },
    toddlerPrepTip = recipe.toddlerPrepTip ?: "Cut into soft bite-sized pieces safe for young children.",
  )

/**
 * Material 3 [ModalBottomSheet] hosting the 3-mode [AddOrEditRecipeSheetContent] studio
 * (`Smart Paste` · `Scan Cookbook` · `Quick Editor`).
 *
 * @param catalog Household ingredient catalog (Tier-1 + Tier-2 items).
 * @param householdProfile Active household profile supplying `preferredCuisines`.
 * @param editingRecipe Optional existing custom [Recipe] opened in `Edit Recipe` mode.
 * @param initialDraft Optional pre-parsed [SmartRecipeDraft] (e.g., returned from Vision OCR).
 * @param initialMode Initial [RecipeCreationMode] tab (`QUICK_EDITOR` when editing, `SMART_PASTE` otherwise).
 * @param openedFromPlan Defaults `Add to Meal Plan` toggle to `true` when opened from `Plan`, `false` from `Recipes`.
 * @param defaultDayIndex Default weekday index (`0..6`) when scheduling onto `Plan`.
 * @param defaultSlotType Default meal slot when scheduling onto `Plan`.
 * @param isVisionModelReady Whether an active multimodal Vision model is currently loaded.
 * @param visionGateMessage Clear Non-Silent Vision Gate explanation displayed when `!isVisionModelReady`.
 * @param isScanningPhoto `true` while cookbook photo OCR is actively transcribing.
 * @param onParseRawText Optional observer callback invoked when `Extract Recipe` parses raw text.
 * @param onScanCookbookPhoto Launches camera capture for cookbook OCR (`Take Photo`).
 * @param onChooseCookbookPhoto Launches scoped photo picker for cookbook OCR (`Choose Photo`).
 * @param onAddLocalIngredient Registers a newly authored local ingredient at `0g` / `OUT` stock.
 * @param onSaveRecipeDraft Commits the validated [SmartRecipeDraft] and optional [RecipePlanScheduleTarget].
 * @param onDismiss Closes the modal bottom sheet.
 * @param modifier Optional Compose [Modifier].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddOrEditRecipeSheet(
  catalog: List<IngredientCatalogItem>,
  householdProfile: HouseholdProfile = HouseholdProfile(),
  editingRecipe: Recipe? = null,
  initialDraft: SmartRecipeDraft? = null,
  initialMode: RecipeCreationMode = if (editingRecipe != null) {
    RecipeCreationMode.QUICK_EDITOR
  } else {
    RecipeCreationMode.SMART_PASTE
  },
  openedFromPlan: Boolean = false,
  defaultDayIndex: Int = 0,
  defaultSlotType: MealSlotType = MealSlotType.DINNER,
  isVisionModelReady: Boolean = false,
  visionGateMessage: String =
    "No Vision model is currently active. Load Gemma 4 Vision or connect a Cloud Vision key in Chef settings to transcribe printed pages, or use Smart Paste and Quick Editor offline right now.",
  isScanningPhoto: Boolean = false,
  onParseRawText: (String) -> Unit = {},
  onScanCookbookPhoto: () -> Unit = {},
  onChooseCookbookPhoto: () -> Unit = onScanCookbookPhoto,
  onAddLocalIngredient: (IngredientCatalogItem) -> Unit = {},
  onSaveRecipeDraft: (
    draft: SmartRecipeDraft,
    existingRecipeId: String?,
    scheduleTarget: RecipePlanScheduleTarget?,
  ) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // Expand immediately so the 3-mode studio switcher and primary editor controls stay accessible.
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = KitchenTheme.extendedColors.linen,
    modifier = modifier,
  ) {
    AddOrEditRecipeSheetContent(
      catalog = catalog,
      householdProfile = householdProfile,
      editingRecipe = editingRecipe,
      initialDraft = initialDraft,
      initialMode = initialMode,
      openedFromPlan = openedFromPlan,
      defaultDayIndex = defaultDayIndex,
      defaultSlotType = defaultSlotType,
      isVisionModelReady = isVisionModelReady,
      visionGateMessage = visionGateMessage,
      isScanningPhoto = isScanningPhoto,
      onParseRawText = onParseRawText,
      onScanCookbookPhoto = onScanCookbookPhoto,
      onChooseCookbookPhoto = onChooseCookbookPhoto,
      onAddLocalIngredient = onAddLocalIngredient,
      onSaveRecipeDraft = { draft, existingId, target ->
        onSaveRecipeDraft(draft, existingId, target)
        onDismiss()
      },
      onDismiss = onDismiss,
    )
  }
}

/**
 * Stateless inner content of [AddOrEditRecipeSheet] extracted for hermetic Compose `@Preview`s.
 *
 * @param catalog Household ingredient catalog (Tier-1 + Tier-2 items).
 * @param householdProfile Active household profile supplying `preferredCuisines`.
 * @param editingRecipe Optional existing custom [Recipe] opened in `Edit Recipe` mode.
 * @param initialDraft Optional pre-parsed [SmartRecipeDraft] (e.g., returned from Vision OCR).
 * @param initialMode Initial [RecipeCreationMode] tab.
 * @param openedFromPlan Defaults `Add to Meal Plan` toggle to `true` when opened from `Plan`.
 * @param defaultDayIndex Default weekday index (`0..6`) when scheduling onto `Plan`.
 * @param defaultSlotType Default meal slot when scheduling onto `Plan`.
 * @param isVisionModelReady Whether an active multimodal Vision model is currently loaded.
 * @param visionGateMessage Clear Non-Silent Vision Gate explanation displayed when `!isVisionModelReady`.
 * @param isScanningPhoto `true` while cookbook photo OCR is actively transcribing.
 * @param onParseRawText Optional observer callback invoked when `Extract Recipe` parses raw text.
 * @param onScanCookbookPhoto Launches camera capture for cookbook OCR (`Take Photo`).
 * @param onChooseCookbookPhoto Launches scoped photo picker for cookbook OCR (`Choose Photo`).
 * @param onAddLocalIngredient Registers a newly authored local ingredient at `0g` / `OUT` stock.
 * @param onSaveRecipeDraft Commits the validated [SmartRecipeDraft] and optional [RecipePlanScheduleTarget].
 * @param onDismiss Closes the modal bottom sheet.
 * @param modifier Optional Compose [Modifier].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddOrEditRecipeSheetContent(
  catalog: List<IngredientCatalogItem>,
  householdProfile: HouseholdProfile = HouseholdProfile(),
  editingRecipe: Recipe? = null,
  initialDraft: SmartRecipeDraft? = null,
  initialMode: RecipeCreationMode = if (editingRecipe != null) {
    RecipeCreationMode.QUICK_EDITOR
  } else {
    RecipeCreationMode.SMART_PASTE
  },
  openedFromPlan: Boolean = false,
  defaultDayIndex: Int = 0,
  defaultSlotType: MealSlotType = MealSlotType.DINNER,
  isVisionModelReady: Boolean = false,
  visionGateMessage: String =
    "No Vision model is currently active. Load Gemma 4 Vision or connect a Cloud Vision key in Chef settings to transcribe printed pages, or use Smart Paste and Quick Editor offline right now.",
  isScanningPhoto: Boolean = false,
  onParseRawText: (String) -> Unit = {},
  onScanCookbookPhoto: () -> Unit = {},
  onChooseCookbookPhoto: () -> Unit = onScanCookbookPhoto,
  onAddLocalIngredient: (IngredientCatalogItem) -> Unit = {},
  onSaveRecipeDraft: (
    draft: SmartRecipeDraft,
    existingRecipeId: String?,
    scheduleTarget: RecipePlanScheduleTarget?,
  ) -> Unit = { _, _, _ -> },
  onDismiss: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  val catalogById = remember(catalog) { catalog.associateBy { it.ingredientId } }
  // Seed initial draft from OCR/Paste draft first, then editingRecipe, then clean empty defaults.
  val seedDraft = remember(editingRecipe, initialDraft, catalogById, householdProfile.preferredCuisines) {
    when {
      initialDraft != null -> initialDraft
      editingRecipe != null -> buildDraftFromExistingRecipe(editingRecipe, catalogById)
      else -> SmartRecipeDraft(
        title = "",
        servings = householdProfile.effectiveTargetServings.coerceIn(1, 24),
        rawPrepMinutes = 10,
        cookMinutes = 20,
        allowedSlots = listOf(defaultSlotType),
        cuisineTags = householdProfile.preferredCuisines.ifEmpty { DEFAULT_PREFERRED_CUISINES },
        ingredientLines = emptyList(),
        stepInstructions = emptyList(),
      )
    }
  }

  // Persist selected creation mode (`Smart Paste`, `Scan Cookbook`, `Quick Editor`) safely.
  var activeModeName by rememberSaveable(initialMode.name) { mutableStateOf(initialMode.name) }
  val activeMode = RecipeCreationMode.entries.find { it.name == activeModeName } ?: initialMode

  // Editable studio fields initialized from seedDraft without pre-populating fake ingredients.
  var rawPasteText by rememberSaveable { mutableStateOf("") }
  var title by rememberSaveable(editingRecipe?.recipeId) { mutableStateOf(seedDraft.title) }
  var servings by rememberSaveable(editingRecipe?.recipeId) { mutableIntStateOf(seedDraft.servings) }
  var prepMinutes by rememberSaveable(editingRecipe?.recipeId) { mutableIntStateOf(seedDraft.rawPrepMinutes) }
  var cookMinutes by rememberSaveable(editingRecipe?.recipeId) { mutableIntStateOf(seedDraft.cookMinutes) }
  var toddlerTip by rememberSaveable(editingRecipe?.recipeId) { mutableStateOf(seedDraft.toddlerPrepTip) }

  // Zero pre-checked Eggs+Milk: ingredientLines starts empty unless editing or hydrated from OCR/Paste.
  val ingredientLines = remember(editingRecipe?.recipeId) {
    mutableStateListOf<ParsedIngredientDraftLine>().apply { addAll(seedDraft.ingredientLines) }
  }
  // Ordered cooking step instructions editable inside Quick Editor.
  val stepLines = remember(editingRecipe?.recipeId) {
    mutableStateListOf<String>().apply { addAll(seedDraft.stepInstructions) }
  }
  // Multi-select cuisine tags defaulting to householdProfile.preferredCuisines.
  val selectedCuisines = remember(editingRecipe?.recipeId) {
    mutableStateListOf<String>().apply { addAll(seedDraft.cuisineTags.distinct()) }
  }
  // Multi-select allowed meal slot categories defaulting to defaultSlotType.
  val selectedSlots = remember(editingRecipe?.recipeId) {
    mutableStateListOf<MealSlotType>().apply {
      addAll(seedDraft.allowedSlots.distinct().ifEmpty { listOf(MealSlotType.DINNER) })
    }
  }

  // Apply external OCR / parsed draft updates whenever initialDraft arrives asynchronously.
  LaunchedEffect(initialDraft) {
    if (initialDraft != null) {
      title = initialDraft.title
      servings = initialDraft.servings
      prepMinutes = initialDraft.rawPrepMinutes
      cookMinutes = initialDraft.cookMinutes
      toddlerTip = initialDraft.toddlerPrepTip
      ingredientLines.clear()
      ingredientLines.addAll(initialDraft.ingredientLines)
      stepLines.clear()
      stepLines.addAll(initialDraft.stepInstructions)
      selectedCuisines.clear()
      selectedCuisines.addAll(initialDraft.cuisineTags.distinct())
      selectedSlots.clear()
      selectedSlots.addAll(initialDraft.allowedSlots.distinct().ifEmpty { listOf(MealSlotType.DINNER) })
      // Auto-advance to Quick Editor only when a non-empty OCR or parsed draft arrives.
      if (initialDraft.title.isNotBlank() || initialDraft.ingredientLines.isNotEmpty()) {
        activeModeName = RecipeCreationMode.QUICK_EDITOR.name
      }
    }
  }

  // Add to Meal Plan defaults OFF when opened from Recipes, ON when opened from Plan.
  var addToMealPlan by rememberSaveable(openedFromPlan) { mutableStateOf(openedFromPlan) }
  var planDayIndex by rememberSaveable(defaultDayIndex) { mutableIntStateOf(defaultDayIndex.coerceIn(0, 6)) }
  var planSlotName by rememberSaveable(defaultSlotType.name) { mutableStateOf(defaultSlotType.name) }

  // Compute 1.4x calibrated morning minutes so EC-01 weekday breakfast cap stays enforced live.
  val calibratedMinutes = computeCalibratedMorningMinutes(prepMinutes, 0, cookMinutes)

  // Scrollable .imePadding() studio body supporting compact 360dp phones and soft keyboards.
  Column(
    modifier = modifier
      .fillMaxWidth()
      .imePadding()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    // Top header distinguishing Edit Family Recipe vs Add Family Recipe + 48.dp Close button.
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = if (editingRecipe != null) "Edit Family Recipe" else "Add Family Recipe",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.weight(1f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      // 48.dp close icon button dismissing the studio cleanly.
      IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Default.Close, contentDescription = "Close Recipe Studio")
      }
    }

    // Segmented mode switcher: Smart Paste · Scan Cookbook · Quick Editor.
    // All 3 segmented tabs maintain >= 48.dp touch target height with zero slashes or parens.
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
      RecipeCreationMode.entries.forEachIndexed { idx, mode ->
        SegmentedButton(
          selected = activeMode == mode,
          onClick = { activeModeName = mode.name },
          shape = SegmentedButtonDefaults.itemShape(index = idx, count = RecipeCreationMode.entries.size),
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          label = {
            Text(
              text = mode.tabLabel,
              style = MaterialTheme.typography.labelSmall,
              maxLines = 1,
              softWrap = false,
              overflow = TextOverflow.Ellipsis,
            )
          },
        )
      }
    }

    // Route active RecipeCreationMode tab to its cohesive section Composable.
    when (activeMode) {
      // Tab 1: Offline free-form text paste + Extract Recipe parser.
      RecipeCreationMode.SMART_PASTE -> {
        recipeStudioSmartPasteSection(
          rawText = rawPasteText,
          onRawTextChange = { rawPasteText = it },
          onExtract = {
            // Run deterministic offline parser immediately so Quick Editor populates in < 5ms.
            val extracted = parseRawRecipeTextToDraft(
              rawText = rawPasteText,
              catalog = catalog,
              defaultCuisines = householdProfile.preferredCuisines,
            )
            // Populate editable Quick Editor fields from extracted title, times, BOM, and steps.
            title = extracted.title
            servings = extracted.servings
            prepMinutes = extracted.rawPrepMinutes
            cookMinutes = extracted.cookMinutes
            toddlerTip = extracted.toddlerPrepTip
            ingredientLines.clear()
            ingredientLines.addAll(extracted.ingredientLines)
            stepLines.clear()
            stepLines.addAll(extracted.stepInstructions)
            selectedCuisines.clear()
            selectedCuisines.addAll(extracted.cuisineTags.distinct())
            selectedSlots.clear()
            selectedSlots.addAll(extracted.allowedSlots.distinct().ifEmpty { listOf(MealSlotType.DINNER) })
            // Notify optional observer and switch directly to Quick Editor for review.
            onParseRawText(rawPasteText)
            activeModeName = RecipeCreationMode.QUICK_EDITOR.name
          },
        )
      }

      // Tab 2: Camera / Gallery Cookbook OCR guarded by the Non-Silent Vision Gate.
      RecipeCreationMode.SCAN_COOKBOOK -> {
        recipeStudioScanCookbookSection(
          isVisionModelReady = isVisionModelReady,
          visionGateMessage = visionGateMessage,
          isScanningPhoto = isScanningPhoto,
          onTakePhoto = onScanCookbookPhoto,
          onChoosePhoto = onChooseCookbookPhoto,
          onSwitchToSmartPaste = { activeModeName = RecipeCreationMode.SMART_PASTE.name },
        )
      }

      // Tab 3: Interactive Quick Editor with Tier-1 + Tier-2 picker and Add to Meal Plan toggle.
      RecipeCreationMode.QUICK_EDITOR -> {
        RecipeStudioQuickEditorSection(
          catalog = catalog,
          householdProfile = householdProfile,
          title = title,
          onTitleChange = { title = it },
          servings = servings,
          onServingsDelta = { servings = (servings + it).coerceIn(1, 24) },
          prepMinutes = prepMinutes,
          onPrepDelta = { prepMinutes = (prepMinutes + it).coerceIn(0, 180) },
          cookMinutes = cookMinutes,
          onCookDelta = { cookMinutes = (cookMinutes + it).coerceIn(0, 300) },
          calibratedMinutes = calibratedMinutes,
          selectedCuisines = selectedCuisines,
          selectedSlots = selectedSlots,
          ingredientLines = ingredientLines,
          stepLines = stepLines,
          toddlerTip = toddlerTip,
          onToddlerTipChange = { toddlerTip = it },
          addToMealPlan = addToMealPlan,
          onToggleAddToPlan = { addToMealPlan = it },
          planDayIndex = planDayIndex,
          onSelectPlanDay = { planDayIndex = it },
          planSlotName = planSlotName,
          onSelectPlanSlot = { planSlotName = it },
          onAddLocalIngredient = onAddLocalIngredient,
        )
      }
    }

    // Primary 52.dp Save button shown in Quick Editor or whenever title + >=1 ingredient are ready.
    val canSave = title.trim().isNotEmpty() && ingredientLines.isNotEmpty()
    if (activeMode == RecipeCreationMode.QUICK_EDITOR || canSave) {
      Button(
        onClick = {
          // Enforce EC-01 25m morning cap on allowedSlots before materializing the saved Recipe.
          val resolvedSlots = selectedSlots.toList().filter { slot ->
            slot != MealSlotType.BREAKFAST || calibratedMinutes <= MAX_WEEKDAY_BREAKFAST_MINUTES
          }.ifEmpty { listOf(MealSlotType.DINNER) }

          // Package immutable SmartRecipeDraft ready for O(1) persistence and 0g stock grounding.
          val finalDraft = SmartRecipeDraft(
            title = recipeQuickSanitizeText(title),
            servings = servings,
            rawPrepMinutes = prepMinutes,
            cookMinutes = cookMinutes,
            allowedSlots = resolvedSlots,
            cuisineTags = selectedCuisines.toList().ifEmpty { householdProfile.preferredCuisines },
            ingredientLines = ingredientLines.toList(),
            stepInstructions = stepLines.toList(),
            toddlerPrepTip = toddlerTip.trim().ifEmpty {
              "Cut into soft bite-sized pieces safe for young children."
            },
          )
          // Emit optional RecipePlanScheduleTarget only when Add to Meal Plan switch is ON.
          val scheduleTarget = if (addToMealPlan) {
            val targetSlot = MealSlotType.entries.find { it.name == planSlotName }
              ?.takeIf { it != MealSlotType.BREAKFAST || calibratedMinutes <= MAX_WEEKDAY_BREAKFAST_MINUTES }
              ?: MealSlotType.DINNER
            RecipePlanScheduleTarget(dayIndex = planDayIndex, slotType = targetSlot)
          } else {
            null
          }
          onSaveRecipeDraft(finalDraft, editingRecipe?.recipeId, scheduleTarget)
        },
        enabled = canSave,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
          containerColor = extendedColors.herbalSage,
          contentColor = Color.White,
        ),
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp),
      ) {
        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        val saveLabel = if (addToMealPlan) "Save and Add to Plan" else "Save Recipe"
        Text(saveLabel, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
      }
    }

    Spacer(modifier = Modifier.height(12.dp))
  }
}

@Composable
private fun recipeStudioSmartPasteSection(
  rawText: String,
  onRawTextChange: (String) -> Unit,
  onExtract: () -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Offline Smart Paste card converting pasted family chat / blog notes into a grounded draft.
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Text(
      text = "Paste a family message, notes list, or blog recipe. SavorAI matches local market ingredients and extracts steps offline.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
      value = rawText,
      onValueChange = onRawTextChange,
      label = { Text("Paste Recipe Text") },
      placeholder = {
        Text("Yoruba Efo Riro\nServes 4\nPrep 10 min\nCook 20 min\n- 300 g kulitis\n- 600 g chicken thighs\n1. Simmer seasoned chicken until tender.")
      },
      minLines = 6,
      maxLines = 10,
      shape = RoundedCornerShape(16.dp),
      modifier = Modifier.fillMaxWidth(),
    )
    Button(
      onClick = onExtract,
      enabled = rawText.isNotBlank(),
      shape = CircleShape,
      colors = ButtonDefaults.buttonColors(
        containerColor = extendedColors.herbalSage,
        contentColor = Color.White,
      ),
      modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
    ) {
      Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
      Spacer(modifier = Modifier.width(6.dp))
      Text("Extract Recipe", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
  }
}

@Composable
private fun recipeStudioScanCookbookSection(
  isVisionModelReady: Boolean,
  visionGateMessage: String,
  isScanningPhoto: Boolean,
  onTakePhoto: () -> Unit,
  onChoosePhoto: () -> Unit,
  onSwitchToSmartPaste: () -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(
      text = "Photograph a printed cookbook page or handwritten family recipe card to transcribe ingredients and steps into an editable draft.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // Mandatory Non-Silent Vision Gate banner when no active Vision model is loaded.
    if (!isVisionModelReady) {
      Surface(
        shape = RoundedCornerShape(16.dp),
        color = extendedColors.childSafetyContainer,
        border = BorderStroke(1.dp, extendedColors.childSafetyBorder),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(
          modifier = Modifier.padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = "Vision Model Needed for Photo OCR",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = extendedColors.onChildSafety,
          )
          Text(
            text = visionGateMessage,
            style = MaterialTheme.typography.bodySmall,
            color = extendedColors.onChildSafety,
          )
          OutlinedButton(
            onClick = onSwitchToSmartPaste,
            shape = CircleShape,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          ) {
            Text("Use Smart Paste Instead", maxLines = 1, softWrap = false)
          }
        }
      }
    }

    // Live multimodal transcription progress banner while Gemma Vision / Cloud Vision is running.
    if (isScanningPhoto) {
      Surface(
        shape = RoundedCornerShape(16.dp),
        color = extendedColors.sageContainer,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Row(
          modifier = Modifier.padding(14.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
          Text(
            text = "Transcribing cookbook page…",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = extendedColors.onSageContainer,
          )
        }
      }
    }

    // 48.dp Take Photo + Choose Photo buttons triggering scoped camera / photo-picker OCR.
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      // Primary 48.dp camera capture button launching scoped FileProvider photo capture.
      Button(
        onClick = onTakePhoto,
        enabled = !isScanningPhoto,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
          containerColor = extendedColors.herbalSage,
          contentColor = Color.White,
        ),
        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
      ) {
        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text("Take Photo", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
      }

      // Secondary 48.dp zero-permission PickVisualMedia gallery button.
      OutlinedButton(
        onClick = onChoosePhoto,
        enabled = !isScanningPhoto,
        shape = CircleShape,
        border = BorderStroke(1.dp, extendedColors.borderSubtle),
        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
      ) {
        Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text("Choose Photo", maxLines = 1, softWrap = false)
      }
    }
  }
}

/** Warm Daylight preview of `AddOrEditRecipeSheetContent` in Quick Editor mode (`Recipes` origin). */
@Preview(name = "AddOrEditRecipeSheet Quick Editor Daylight", showBackground = true, widthDp = 412, heightDp = 960)
@Composable
fun AddOrEditRecipeSheetQuickEditorPreview() {
  // Verify zero pre-checked Eggs+Milk and Add to Meal Plan defaulting OFF from Recipes.
  FamilyKitchenBoardTheme(darkTheme = false) {
    Surface(color = KitchenTheme.extendedColors.linen) {
      AddOrEditRecipeSheetContent(
        catalog = PreviewFixtures.catalog,
        householdProfile = PreviewFixtures.householdProfile,
        initialMode = RecipeCreationMode.QUICK_EDITOR,
        openedFromPlan = false,
      )
    }
  }
}

/** Ambient Counter Dark preview of `Scan Cookbook` mode demonstrating the Non-Silent Vision Gate. */
@Preview(name = "AddOrEditRecipeSheet Scan Gate Dark", showBackground = true, widthDp = 412, heightDp = 720)
@Composable
fun AddOrEditRecipeSheetScanGateDarkPreview() {
  // Verify Non-Silent Vision Gate guidance when no multimodal vision model is active.
  FamilyKitchenBoardTheme(darkTheme = true) {
    Surface(color = KitchenTheme.extendedColors.linen) {
      AddOrEditRecipeSheetContent(
        catalog = PreviewFixtures.catalog,
        householdProfile = PreviewFixtures.householdProfile,
        initialMode = RecipeCreationMode.SCAN_COOKBOOK,
        isVisionModelReady = false,
      )
    }
  }
}
