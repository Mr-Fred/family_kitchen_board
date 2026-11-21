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

// Full-screen Step-by-Step Cook Mode screen built on a true Material 3 Scaffold(topBar, bottomBar)
// so CookModeMultiTimerCard is never occluded and scroll resets cleanly on every step transition.

/**
 * Controlled `StepByStepCookModeScreen` Composable accepting an immutable [CookModeSessionState]
 * and dispatching pure [CookModeEngine] transitions via [onSessionStateChange].
 *
 * Uses a true Material 3 [Scaffold] (`topBar` + `72.dp` `bottomBar`), resets `scrollState` to `0`
 * whenever `sessionState.currentStepIndex` changes, manages `FLAG_KEEP_SCREEN_ON` inside a
 * [DisposableEffect], and ticks concurrent step timers once per second.
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
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val localView = LocalView.current
  val showPantrySheetState = rememberSaveable(sessionState.recipe.recipeId) { mutableStateOf(false) }
  val scrollState = rememberScrollState()

  val latestSessionRef = rememberUpdatedState(sessionState)
  val latestOnChangeRef = rememberUpdatedState(onSessionStateChange)

  // Reset scroll position to the top whenever the active step changes so Step 2 never opens sliced.
  LaunchedEffect(sessionState.currentStepIndex) {
    scrollState.animateScrollTo(0)
  }

  // Manage FLAG_KEEP_SCREEN_ON cleanly across entry, toggle, and teardown.
  DisposableEffect(sessionState.keepScreenAwake, context, localView) {
    localView.keepScreenOn = sessionState.keepScreenAwake
    val activityWindow = context.findActivityOrNull()?.window
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

  // Index catalog once per session and compute target-servings BOM quantities deterministically.
  val catalogById = remember(catalog) { catalog.associateBy { it.ingredientId } }
  val scaledBomQuantities = remember(sessionState.recipe, sessionState.targetServings, catalog) {
    resolveEffectiveMealBom(
      recipe = sessionState.recipe,
      targetServings = sessionState.targetServings,
      catalog = catalog,
    )
  }

  // Provide a non-null fallback MorningStep if a custom recipe was saved with empty steps.
  val activeStep = sessionState.currentStep ?: MorningStep(
    stepNumber = 1,
    title = "Cook & Plate",
    instruction = "Cook ${sessionState.recipe.title} until hot and fragrant.",
    timerSeconds = 180,
  )

  // Format EC-08 child safety guidance and detect IME visibility so bottom dock yields to keyboard.
  val safetyTip = householdProfile.youngestStage.formatPrepTip(sessionState.recipe.toddlerPrepTip)
  val isImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

  // True Material 3 Scaffold guarantees innerPadding reserves full bottom height for CookModeBottomDock.
  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = MaterialTheme.colorScheme.surface,
    topBar = {
      // Single-line 48.dp header (`Exit` | `Step k/N` | `Awake`) + clickable progress segments.
      CookModeTopHeader(
        steps = sessionState.steps,
        currentStepIndex = sessionState.currentStepIndex,
        keepScreenAwake = sessionState.keepScreenAwake,
        onExit = onExitCookMode,
        onToggleKeepAwake = {
          onSessionStateChange(sessionState.copy(keepScreenAwake = !sessionState.keepScreenAwake))
        },
        onJumpToStep = { targetIdx ->
          onSessionStateChange(CookModeEngine.jumpToStep(sessionState, targetIdx))
        },
      )
    },
    bottomBar = {
      // Hide 72.dp bottom dock while typing in the Sous-Chef Q&A field so keyboard sits flush.
      if (!isImeVisible) {
        CookModeBottomDock(
          sessionState = sessionState,
          onToggleHandsFreeMic = {
            val nextActive = !sessionState.isHandsFreeVoiceActive
            onSessionStateChange(sessionState.copy(isHandsFreeVoiceActive = nextActive))
            onToggleHandsFreeMic(nextActive)
          },
          onOpenPantrySheet = { showPantrySheetState.value = true },
          onPreviousStep = {
            onSessionStateChange(CookModeEngine.previousStep(sessionState))
          },
          onNextOrComplete = {
            // Final step commits O(1) atomic pantry deduction; earlier steps advance cleanly.
            when {
              sessionState.isFinalStep -> onCompleteAndDeductPantry(sessionState)
              else -> onSessionStateChange(CookModeEngine.advanceStep(sessionState))
            }
          },
        )
      }
    },
  ) { innerPadding ->
    // Chain consumeWindowInsets(innerPadding).imePadding() so timer and Q&A cards never clip.
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .consumeWindowInsets(innerPadding)
        .imePadding()
        .verticalScroll(scrollState)
        .padding(PaddingValues(horizontal = 16.dp, vertical = 12.dp)),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      // 1. Compact 80.dp Technique & Sensory Doneness Strip.
      CookModeHeroVisualCard(
        step = activeStep,
        fallbackTechnique = "${sessionState.recipe.difficulty.displayLabel} Technique",
      )

      // 2. Distraction-Free 19.sp Step Instruction Card + EC-08 Safety + 48.dp Step Chips.
      CookModeInstructionCard(
        step = activeStep,
        scaledQuantities = scaledBomQuantities,
        catalogById = catalogById,
        checkedIngredientIds = sessionState.checkedIngredientIds,
        safetyCalloutText = safetyTip,
        onToggleStepIngredient = { ingId ->
          onSessionStateChange(CookModeEngine.toggleStepIngredient(sessionState, ingId))
        },
      )

      // 3. Active Kitchen Multi-Timer Card (guaranteed unoccluded inside Scaffold).
      CookModeMultiTimerCard(
        step = activeStep,
        activeTimer = sessionState.activeStepTimer,
        backgroundTimers = sessionState.backgroundRunningTimers,
        fallbackChefTip = sessionState.recipe.chefNote,
        onToggleTimer = {
          onSessionStateChange(CookModeEngine.toggleStepTimer(sessionState, activeStep.stepNumber))
        },
        onAddOneMinute = {
          onSessionStateChange(CookModeEngine.addOneMinuteToTimer(sessionState, activeStep.stepNumber))
        },
        onResetTimer = {
          onSessionStateChange(CookModeEngine.resetStepTimer(sessionState, activeStep.stepNumber))
        },
        onJumpToBackgroundTimerStep = { stepIdx ->
          onSessionStateChange(CookModeEngine.jumpToStep(sessionState, stepIdx))
        },
      )

      // 4. Hands-Free Culinary Sous-Chef Q&A Card (en-US / fr-FR voice intent routing).
      CookModeHandsFreeAiCard(
        sessionState = sessionState,
        onDispatchTranscriptOrQuestion = { utterance ->
          // Parse bilingual voice commands ("next step", "nest step", "étape suivante", "open ingredients").
          val parsedIntent = CookModeEngine.parseCookModeVoiceIntent(utterance)
          if (parsedIntent == CookModeVoiceIntent.OpenIngredients) {
            showPantrySheetState.value = true
          }
          val nextState = CookModeEngine.applyVoiceTranscriptToCookSession(
            state = sessionState,
            transcript = utterance,
            catalog = catalog,
            youngestStage = householdProfile.youngestStage,
          )
          onSessionStateChange(nextState)
        },
      )
    }
  }

  // Quick Mise-en-Place Ingredients Sheet with weighted rows preventing vertical character wrap.
  if (showPantrySheetState.value) {
    CookModeIngredientsSheet(
      sessionState = sessionState,
      scaledQuantities = scaledBomQuantities,
      catalog = catalog,
      pantryEntries = pantryEntries,
      householdProfile = householdProfile,
      onToggleIngredient = { ingId ->
        onSessionStateChange(CookModeEngine.toggleStepIngredient(sessionState, ingId))
      },
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
  modifier: Modifier = Modifier,
) {
  // Initialize pure CookModeSessionState once per `(recipeId, slotId, targetServings)` tuple.
  val sessionStateHolder = remember(recipe.recipeId, slotId, targetServings) {
    mutableStateOf(
      CookModeEngine.startCookSession(
        recipe = recipe,
        slotId = slotId,
        targetServings = targetServings,
        catalog = catalog,
        initialCheckedIngredients = initialCheckedIngredients,
        handsFreeVoiceActive = initialHandsFreeVoiceActive,
      ),
    )
  }

  // Forward incoming external SpeechRecognizer transcripts into CookModeEngine idempotently.
  LaunchedEffect(externalVoiceTranscript) {
    val clean = externalVoiceTranscript?.trim().orEmpty()
    if (clean.isNotEmpty() && clean != sessionStateHolder.value.lastVoiceTranscript) {
      sessionStateHolder.value = CookModeEngine.applyVoiceTranscriptToCookSession(
        state = sessionStateHolder.value,
        transcript = clean,
        catalog = catalog,
        youngestStage = householdProfile.youngestStage,
      )
    }
  }

  // Delegate all rendering and wake-lock lifecycle to the primary stateless overload.
  StepByStepCookModeScreen(
    sessionState = sessionStateHolder.value,
    catalog = catalog,
    pantryEntries = pantryEntries,
    householdProfile = householdProfile,
    onSessionStateChange = { updated -> sessionStateHolder.value = updated },
    onToggleHandsFreeMic = onRequestVoiceListening,
    onCompleteAndDeductPantry = { finishedState ->
      onCompleteRecipe(finishedState.recipe, finishedState.slotId, finishedState.targetServings)
    },
    onExitCookMode = onExitCookMode,
    modifier = modifier,
  )
}

// Weighted ingredient rows prevent "I\nn\nP\na\nn\ntr\ny\n✓" character-by-character vertical crush.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CookModeIngredientsSheet(
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
  val matchSummary = remember(sessionState.recipe, pantryEntries, catalog, sessionState.targetServings) {
    PantryExpiryEngine.evaluateRecipePantryMatch(
      recipe = sessionState.recipe,
      pantry = pantryEntries,
      catalog = catalog,
      targetServings = sessionState.targetServings,
      householdAllergens = householdProfile.householdAllergens,
    )
  }
  val matchedIds = remember(matchSummary) { matchSummary.matchedIngredients.map { it.ingredientId }.toSet() }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .imePadding()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "Mise-en-Place (${sessionState.targetServings} srv)",
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.weight(1f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        TextButton(
          onClick = onDismiss,
          modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        ) {
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
          onClick = { onToggleIngredient(ingId) },
          shape = KitchenTheme.shapes.inputDefault,
          color = MaterialTheme.colorScheme.surfaceContainerLowest,
          border = BorderStroke(1.dp, extendedColors.borderSubtle),
          modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp),
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            // Modifier.weight(1f).padding(end = 10.dp) gives left text priority and prevents badge crush.
            Row(
              modifier = Modifier
                .weight(1f)
                .padding(end = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Checkbox(checked = isReady, onCheckedChange = { onToggleIngredient(ingId) })
              Text(
                text = "$cleanQty ${item?.baseUnit?.wireCode ?: "units"} ${item?.displayName ?: ingId}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
              )
            }
            // Non-wrapping `softWrap = false, maxLines = 1` capsule fixes vertical "I\nn\nP\na\nn\ntr\ny".
            Surface(
              shape = CircleShape,
              color = if (inStock) extendedColors.sageContainer else extendedColors.tertiaryFixed,
            ) {
              Text(
                text = if (inStock) "In Pantry ✓" else "Low Stock",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (inStock) extendedColors.onSageContainer else extendedColors.onTertiaryFixed,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
              )
            }
          }
        }
      }

      // 48.dp shortcut opening CookedOverrideSheet when actual stove usage differs from plan.
      OutlinedButton(
        onClick = {
          onDismiss()
          onOpenPantryAdjuster()
        },
        shape = CircleShape,
        modifier = Modifier
          .fillMaxWidth()
          .defaultMinSize(minHeight = 48.dp),
      ) {
        Text(text = "Adjust Meal / Overrides", maxLines = 1, softWrap = false)
      }

      Spacer(modifier = Modifier.height(16.dp))
    }
  }
}

// Tail-recursive Context unwrapper locating the host Activity window for FLAG_KEEP_SCREEN_ON.
private tailrec fun Context.findActivityOrNull(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivityOrNull()
  else -> null
}

/** Daylight preview of `StepByStepCookModeScreen` on Step 2. */
@Preview(name = "StepByStepCookMode — Step 2 Daylight", showBackground = true, widthDp = 412, heightDp = 1040)
@Composable
fun StepByStepCookModeDaylightPreview() {
  FamilyKitchenBoardTheme(darkTheme = false) {
    StepByStepCookModeScreen(
      sessionState = PreviewFixtures.activeSalmonCookSession,
      catalog = PreviewFixtures.catalog,
      pantryEntries = PreviewFixtures.pantryEntries,
      householdProfile = PreviewFixtures.householdProfile,
    )
  }
}

/** Ambient Counter Dark preview on the final step showing `"Finish & Deduct ✓"`. */
@Preview(name = "StepByStepCookMode — Final Step Dark", showBackground = true, widthDp = 412, heightDp = 1040)
@Composable
fun StepByStepCookModeFinalStepDarkPreview() {
  val finalStepState = CookModeEngine.jumpToStep(
    state = PreviewFixtures.activeSalmonCookSession,
    stepIndex = PreviewFixtures.activeSalmonCookSession.steps.lastIndex,
  )
  FamilyKitchenBoardTheme(darkTheme = true) {
    StepByStepCookModeScreen(
      sessionState = finalStepState,
      catalog = PreviewFixtures.catalog,
      pantryEntries = PreviewFixtures.pantryEntries,
      householdProfile = PreviewFixtures.eveningLifeEventProfile,
    )
  }
}
