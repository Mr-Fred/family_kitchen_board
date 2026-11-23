package com.familykitchen.board.ui.cookmode

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.CookModeEngine
import com.familykitchen.board.domain.CookModeSessionState
import com.familykitchen.board.domain.CookModeVoiceIntent
import com.familykitchen.board.domain.HouseholdProfile
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.MorningStep
import com.familykitchen.board.domain.PantryExpiryEngine
import com.familykitchen.board.domain.PantryLedgerEntry
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.resolveEffectiveMealBom
import com.familykitchen.board.ui.preview.PreviewFixtures
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// Full-screen Step-by-Step Cook Mode screen built on a Material 3 Scaffold(topBar, bottomBar)
// with `Minimize` and `End Cook` controls and live pantry-grounded Sous-Chef Q&A.

/**
 * Controlled `StepByStepCookModeScreen` Composable accepting an immutable [CookModeSessionState]
 * and dispatching pure [CookModeEngine] transitions via [onSessionStateChange].
 *
 * Uses a Material 3 [Scaffold] (`topBar` with `End Cook` and optional `Minimize`, `72.dp`
 * `bottomBar`), resets `scrollState` to `0` whenever `sessionState.currentStepIndex` changes,
 * manages `FLAG_KEEP_SCREEN_ON` inside a [DisposableEffect], ticks concurrent step timers once per
 * second, and passes [pantryEntries] into [CookModeEngine.answerCookStepQuestion].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StepByStepCookModeScreen(
  sessionState: CookModeSessionState,
  catalog: List<IngredientCatalogItem> = emptyList(),
  pantryEntries: List<PantryLedgerEntry> = emptyList(),
  householdProfile: HouseholdProfile = HouseholdProfile(),
  onSessionStateChange: (CookModeSessionState) -> Unit = {},
  onToggleHandsFreeMic: (Boolean) -> Unit = {},
  onOpenPantryAdjuster: () -> Unit = {},
  onCompleteAndDeductPantry: (CookModeSessionState) -> Unit = {},
  onExitCookMode: () -> Unit = {},
  onMinimizeCookMode: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val localView = LocalView.current
  val showPantrySheetState = rememberSaveable(sessionState.recipe.recipeId) { mutableStateOf(false) }
  val scrollState = rememberScrollState()

  // Keep updated state references so the 1-second timer coroutine never reads stale step snapshots.
  val latestSessionRef = rememberUpdatedState(sessionState)
  val latestOnChangeRef = rememberUpdatedState(onSessionStateChange)

  // Reset scroll position to the top whenever the active step changes so Step 2 never opens sliced.
  LaunchedEffect(sessionState.currentStepIndex) { scrollState.animateScrollTo(0) }

  // Manage FLAG_KEEP_SCREEN_ON cleanly across entry, toggle, and teardown.
  DisposableEffect(sessionState.keepScreenAwake, context, localView) {
    localView.keepScreenOn = sessionState.keepScreenAwake
    val activityWindow = context.cookScreenFindActivityOrNull()?.window
    if (sessionState.keepScreenAwake) {
      activityWindow?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
      activityWindow?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    onDispose {
      localView.keepScreenOn = false
      activityWindow?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
  }

  // Concurrent 1-second timer ticker active whenever >= 1 foreground or background timer runs.
  val anyTimerRunning = sessionState.timersByStepNumber.values.any { it.isRunning }
  LaunchedEffect(anyTimerRunning, sessionState.recipe.recipeId) {
    while (latestSessionRef.value.timersByStepNumber.values.any { it.isRunning }) {
      delay(1_000L)
      val ticked = CookModeEngine.tickRunningTimers(latestSessionRef.value, 1)
      latestOnChangeRef.value(ticked)
    }
  }

  // Index catalog once and scale BOM quantities to sessionState.targetServings.
  val catalogById = remember(catalog) { catalog.associateBy { it.ingredientId } }
  val scaledBomQuantities = remember(sessionState.recipe, sessionState.targetServings, catalog) {
    resolveEffectiveMealBom(recipe = sessionState.recipe, targetServings = sessionState.targetServings, catalog = catalog)
  }

  // Provide a safe fallback step card if an imported recipe carried zero explicit morningSteps.
  val activeStep = sessionState.currentStep ?: MorningStep(
    stepNumber = 1, title = "Cook & Plate",
    instruction = "Cook ${sessionState.recipe.title} until hot and fragrant.", timerSeconds = 180,
  )

  // Format age-calibrated child choking/salt prep guidance (EC-08).
  val safetyTip = householdProfile.youngestStage.formatPrepTip(sessionState.recipe.toddlerPrepTip)
  val isImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = MaterialTheme.colorScheme.surface,
    topBar = {
      // Top header exposes both `End Cook` and optional `Minimize` into the Mini-Timer bar.
      CookModeTopHeader(
        steps = sessionState.steps, currentStepIndex = sessionState.currentStepIndex,
        keepScreenAwake = sessionState.keepScreenAwake, onExit = onExitCookMode,
        onToggleKeepAwake = { onSessionStateChange(sessionState.copy(keepScreenAwake = !sessionState.keepScreenAwake)) },
        onJumpToStep = { targetIdx -> onSessionStateChange(CookModeEngine.jumpToStep(sessionState, targetIdx)) },
        onMinimize = onMinimizeCookMode,
      )
    },
    bottomBar = {
      // Hide bottom step-navigation dock while typing a Sous-Chef question on the soft keyboard.
      if (!isImeVisible) {
        CookModeBottomDock(
          sessionState = sessionState,
          onToggleHandsFreeMic = {
            val nextActive = !sessionState.isHandsFreeVoiceActive
            onSessionStateChange(sessionState.copy(isHandsFreeVoiceActive = nextActive))
            onToggleHandsFreeMic(nextActive)
          },
          onOpenPantrySheet = { showPantrySheetState.value = true },
          onPreviousStep = { onSessionStateChange(CookModeEngine.previousStep(sessionState)) },
          onNextOrComplete = {
            when {
              sessionState.isFinalStep -> onCompleteAndDeductPantry(sessionState)
              else -> onSessionStateChange(CookModeEngine.advanceStep(sessionState))
            }
          },
        )
      }
    },
  ) { innerPadding ->
    Column(
      modifier = Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)
        .imePadding().verticalScroll(scrollState).padding(PaddingValues(horizontal = 16.dp, vertical = 12.dp)),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      // 1. Visual technique & sensory doneness strip.
      CookModeHeroVisualCard(
        step = activeStep,
        fallbackTechnique = "${sessionState.recipe.difficulty.displayLabel} Technique",
      )

      // 2. High-contrast 19.sp instruction card with interactive step-ingredient chips.
      CookModeInstructionCard(
        step = activeStep, scaledQuantities = scaledBomQuantities,
        catalogById = catalogById, checkedIngredientIds = sessionState.checkedIngredientIds,
        safetyCalloutText = safetyTip,
        onToggleStepIngredient = { ingId -> onSessionStateChange(CookModeEngine.toggleStepIngredient(sessionState, ingId)) },
      )

      // 3. Foreground step timer plus concurrent background step timer pills.
      CookModeMultiTimerCard(
        step = activeStep, activeTimer = sessionState.activeStepTimer,
        backgroundTimers = sessionState.backgroundRunningTimers,
        fallbackChefTip = sessionState.recipe.chefNote,
        onToggleTimer = { onSessionStateChange(CookModeEngine.toggleStepTimer(sessionState, activeStep.stepNumber)) },
        onAddOneMinute = { onSessionStateChange(CookModeEngine.addOneMinuteToTimer(sessionState, activeStep.stepNumber)) },
        onResetTimer = { onSessionStateChange(CookModeEngine.resetStepTimer(sessionState, activeStep.stepNumber)) },
        onJumpToBackgroundTimerStep = { stepIdx -> onSessionStateChange(CookModeEngine.jumpToStep(sessionState, stepIdx)) },
      )

      // 4. Hands-Free Culinary Sous-Chef Q&A Card grounded in live household pantryEntries.
      CookModeHandsFreeAiCard(
        sessionState = sessionState,
        onDispatchTranscriptOrQuestion = { utterance ->
          val parsedIntent = CookModeEngine.parseCookModeVoiceIntent(utterance)
          if (parsedIntent == CookModeVoiceIntent.OpenIngredients) {
            showPantrySheetState.value = true
          }
          // Pass real pantryEntries into both question answering and voice command evaluation.
          val nextState = when (parsedIntent) {
            is CookModeVoiceIntent.AskSousChefQuestion -> sessionState.copy(
              lastVoiceTranscript = utterance.trim(),
              lastSousChefAnswer = CookModeEngine.answerCookStepQuestion(
                recipe = sessionState.recipe, stepIndex = sessionState.currentStepIndex,
                question = parsedIntent.question, catalog = catalog,
                youngestStage = householdProfile.youngestStage, pantryEntries = pantryEntries,
              ),
            )
            else -> CookModeEngine.applyVoiceTranscriptToCookSession(
              state = sessionState, transcript = utterance, catalog = catalog,
              youngestStage = householdProfile.youngestStage, pantryEntries = pantryEntries,
            )
          }
          onSessionStateChange(nextState)
        },
      )
    }
  }

  // Modal Mise-en-Place checklist sheet showing live In Pantry vs Low Stock badges.
  if (showPantrySheetState.value) {
    cookScreenIngredientsSheet(
      sessionState = sessionState, scaledQuantities = scaledBomQuantities,
      catalog = catalog, pantryEntries = pantryEntries, householdProfile = householdProfile,
      onToggleIngredient = { ingId -> onSessionStateChange(CookModeEngine.toggleStepIngredient(sessionState, ingId)) },
      onOpenPantryAdjuster = onOpenPantryAdjuster,
      onDismiss = { showPantrySheetState.value = false },
    )
  }
}

/**
 * Convenience stateful overload of [StepByStepCookModeScreen] initializing and remembering a
 * [CookModeSessionState] directly from [recipe] via [CookModeEngine.startCookSession].
 */
@Composable
fun StepByStepCookModeScreen(
  recipe: Recipe,
  slotId: String? = null,
  targetServings: Int = recipe.baseServings,
  catalog: List<IngredientCatalogItem> = emptyList(),
  pantryEntries: List<PantryLedgerEntry> = emptyList(),
  householdProfile: HouseholdProfile = HouseholdProfile(),
  initialCheckedIngredients: Set<String> = emptySet(),
  initialHandsFreeVoiceActive: Boolean = false,
  externalVoiceTranscript: String? = null,
  onRequestVoiceListening: (Boolean) -> Unit = {},
  onCompleteRecipe: (recipe: Recipe, slotId: String?, targetServings: Int) -> Unit = { _, _, _ -> },
  onExitCookMode: () -> Unit = {},
  onMinimizeCookMode: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  // Initialize local CookModeSessionState when callers invoke the Recipe-based overload directly.
  val sessionStateHolder = remember(recipe.recipeId, slotId, targetServings) {
    mutableStateOf(
      CookModeEngine.startCookSession(
        recipe = recipe, slotId = slotId, targetServings = targetServings,
        catalog = catalog, initialCheckedIngredients = initialCheckedIngredients,
        handsFreeVoiceActive = initialHandsFreeVoiceActive,
      ),
    )
  }

  // Forward finalized SpeechRecognizer transcripts with pantryEntries for grounded substitutions.
  LaunchedEffect(externalVoiceTranscript) {
    val clean = externalVoiceTranscript?.trim().orEmpty()
    if (clean.isNotEmpty() && clean != sessionStateHolder.value.lastVoiceTranscript) {
      sessionStateHolder.value = CookModeEngine.applyVoiceTranscriptToCookSession(
        state = sessionStateHolder.value, transcript = clean, catalog = catalog,
        youngestStage = householdProfile.youngestStage, pantryEntries = pantryEntries,
      )
    }
  }

  StepByStepCookModeScreen(
    sessionState = sessionStateHolder.value, catalog = catalog,
    pantryEntries = pantryEntries, householdProfile = householdProfile,
    onSessionStateChange = { updated -> sessionStateHolder.value = updated },
    onToggleHandsFreeMic = onRequestVoiceListening,
    onCompleteAndDeductPantry = { finishedState ->
      onCompleteRecipe(finishedState.recipe, finishedState.slotId, finishedState.targetServings)
    },
    onExitCookMode = onExitCookMode, onMinimizeCookMode = onMinimizeCookMode, modifier = modifier,
  )
}

// Mise-en-Place bottom sheet displaying scaled ingredient quantities and live home-stock status.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun cookScreenIngredientsSheet(
  sessionState: CookModeSessionState,
  scaledQuantities: Map<String, Double>,
  catalog: List<IngredientCatalogItem>,
  pantryEntries: List<PantryLedgerEntry>,
  householdProfile: HouseholdProfile,
  onToggleIngredient: (String) -> Unit,
  onOpenPantryAdjuster: () -> Unit,
  onDismiss: () -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val catalogById = remember(catalog) { catalog.associateBy { it.ingredientId } }
  // Evaluate live pantry match so cooks see which ingredients are ready vs low stock.
  val matchSummary = remember(sessionState.recipe, pantryEntries, catalog, sessionState.targetServings) {
    PantryExpiryEngine.evaluateRecipePantryMatch(
      recipe = sessionState.recipe, pantry = pantryEntries, catalog = catalog,
      targetServings = sessionState.targetServings, householdAllergens = householdProfile.householdAllergens,
    )
  }
  val matchedIds = remember(matchSummary) { matchSummary.matchedIngredients.map { it.ingredientId }.toSet() }

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) {
    Column(
      modifier = Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      // Punctuation-free Mise-en-Place · N servings heading with 48.dp Done button.
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = "Mise-en-Place · ${sessionState.targetServings} servings",
          style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f),
          maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onDismiss, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
          Text(text = "Done", maxLines = 1, softWrap = false)
        }
      }

      scaledQuantities.forEach { (ingId, qty) ->
        val item = catalogById[ingId]
        val isReady = ingId in sessionState.checkedIngredientIds
        val inStock = ingId in matchedIds
        val rounded = (qty * 100.0).roundToInt() / 100.0
        val cleanQty = if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()

        Surface(
          onClick = { onToggleIngredient(ingId) }, shape = KitchenTheme.shapes.inputDefault,
          color = MaterialTheme.colorScheme.surfaceContainerLowest,
          border = BorderStroke(1.dp, extendedColors.borderSubtle),
          modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
          ) {
            Row(modifier = Modifier.weight(1f).padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
              Checkbox(checked = isReady, onCheckedChange = { onToggleIngredient(ingId) })
              Text(
                text = "$cleanQty ${item?.baseUnit?.wireCode ?: "units"} ${item?.displayName ?: ingId}",
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
              )
            }
            Surface(shape = CircleShape, color = if (inStock) extendedColors.sageContainer else extendedColors.tertiaryFixed) {
              Text(
                text = if (inStock) "In Pantry ✓" else "Low Stock",
                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                color = if (inStock) extendedColors.onSageContainer else extendedColors.onTertiaryFixed,
                maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
              )
            }
          }
        }
      }

      // Open CookedOverrideSheet when the household scaled or substituted quantities mid-cook.
      OutlinedButton(
        onClick = { onDismiss(); onOpenPantryAdjuster() }, shape = CircleShape,
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
      ) { Text(text = "Adjust Meal Quantities", maxLines = 1, softWrap = false) }

      Spacer(modifier = Modifier.height(16.dp))
    }
  }
}

// Walk ContextWrapper chain safely so Compose @Previews without an Activity never crash.
private tailrec fun Context.cookScreenFindActivityOrNull(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.cookScreenFindActivityOrNull()
  else -> null
}

/** Daylight preview of `StepByStepCookModeScreen` on Step 2 with `Minimize` enabled. */
@Preview(name = "StepByStepCookMode — Step 2 Daylight", showBackground = true, widthDp = 412, heightDp = 1040)
@Composable
fun StepByStepCookModeDaylightPreview() {
  // Verifies End Cook + Minimize pills, Step 2 of N badge, and daylight readability.
  FamilyKitchenBoardTheme(darkTheme = false) {
    StepByStepCookModeScreen(
      sessionState = PreviewFixtures.activeSalmonCookSession,
      catalog = PreviewFixtures.catalog, pantryEntries = PreviewFixtures.pantryEntries,
      householdProfile = PreviewFixtures.householdProfile, onMinimizeCookMode = {},
    )
  }
}

/** Ambient Counter Dark preview on the final step showing `"Finish & Deduct ✓"`. */
@Preview(name = "StepByStepCookMode — Final Step Dark", showBackground = true, widthDp = 412, heightDp = 1040)
@Composable
fun StepByStepCookModeFinalStepDarkPreview() {
  // Verifies final-step completion CTA and Counter Dark contrast.
  val finalStepState = CookModeEngine.jumpToStep(
    state = PreviewFixtures.activeSalmonCookSession,
    stepIndex = PreviewFixtures.activeSalmonCookSession.steps.lastIndex,
  )
  FamilyKitchenBoardTheme(darkTheme = true) {
    StepByStepCookModeScreen(
      sessionState = finalStepState, catalog = PreviewFixtures.catalog,
      pantryEntries = PreviewFixtures.pantryEntries,
      householdProfile = PreviewFixtures.eveningLifeEventProfile, onMinimizeCookMode = {},
    )
  }
}
