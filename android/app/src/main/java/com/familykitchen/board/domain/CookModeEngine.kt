package com.familykitchen.board.domain

import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

// Pure Step-by-Step Cook Mode state machine, concurrent multi-timer reducer, bilingual voice
// command parser (en-US + fr-FR), and grounded Sous-Chef step Q&A engine.
// Zero Android, Compose, SpeechRecognizer, LiteRT, or java.io imports permitted inside domain/.

private const val DEFAULT_AD_HOC_STEP_TIMER_SECONDS: Int = 180
private const val ONE_MINUTE_SECONDS: Int = 60

/**
 * Immutable countdown timer state for a single cooking step in Step-by-Step Cook Mode or Clean
 * Recipe Reader.
 *
 * @property stepNumber 1-based step number owning this timer.
 * @property label Concise timer title (for example, `"The Sear"`, `"Step 2 Timer"`).
 * @property totalSeconds Baseline or extended duration in seconds (`> 0`).
 * @property remainingSeconds Clamped non-negative remaining seconds (`0..totalSeconds`).
 * @property isRunning `true` while actively counting down.
 */
@Serializable
data class StepTimerState(
  val stepNumber: Int,
  val label: String,
  val totalSeconds: Int,
  val remainingSeconds: Int,
  val isRunning: Boolean = false,
) {
  /** `true` when this timer had a positive duration and has reached `00:00`. */
  val isCompleted: Boolean
    get() = totalSeconds > 0 && remainingSeconds == 0

  /** Clamped progress fraction (`0.0f..1.0f`) representing remaining countdown capacity. */
  val remainingFraction: Float
    get() = when {
      totalSeconds <= 0 -> 0f
      else -> (remainingSeconds.toFloat() / totalSeconds.toFloat()).coerceIn(0f, 1f)
    }

  /** Integer remaining percentage (`0..100`) rendered inside circular timer rings. */
  val remainingPercent: Int
    get() = (remainingFraction * 100f).roundToInt().coerceIn(0, 100)

  /** Tabular `MM:SS` formatted string (`"03:42"`) computed purely without platform `Locale`. */
  val formattedRemainingMmSs: String
    get() {
      val clamped = max(0, remainingSeconds)
      val minutes = clamped / ONE_MINUTE_SECONDS
      val seconds = clamped % ONE_MINUTE_SECONDS
      return "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }
}

/**
 * Complete immutable state of an active Step-by-Step Cook Mode session (`step_by_step_cook_mode`).
 *
 * @property recipe Active [Recipe] being cooked.
 * @property slotId Optional scheduled [MealSlot.slotId] deducted upon final step completion.
 * @property targetServings Active household serving count for BOM scaling.
 * @property currentStepIndex 0-based index into [steps].
 * @property steps Enriched sequential steps with auto-linked `stepIngredientIds`.
 * @property timersByStepNumber Multi-step timer map keyed by 1-based [MorningStep.stepNumber].
 * @property checkedIngredientIds Ingredient IDs marked ready during prep/cooking.
 * @property keepScreenAwake Whether counter wake-lock (`FLAG_KEEP_SCREEN_ON`) is enabled.
 * @property isHandsFreeVoiceActive Whether hands-free voice listening is toggled on.
 * @property lastVoiceTranscript Latest spoken command or question recognized by `VoiceInputPort`.
 * @property lastSousChefAnswer Grounded Sous-Chef answer surfaced in the Hands-Free AI card.
 */
@Serializable
data class CookModeSessionState(
  val recipe: Recipe,
  val slotId: String? = null,
  val targetServings: Int = recipe.baseServings,
  val currentStepIndex: Int = 0,
  val steps: List<MorningStep> = recipe.morningSteps,
  val timersByStepNumber: Map<Int, StepTimerState> = emptyMap(),
  val checkedIngredientIds: Set<String> = emptySet(),
  val keepScreenAwake: Boolean = true,
  val isHandsFreeVoiceActive: Boolean = false,
  val lastVoiceTranscript: String? = null,
  val lastSousChefAnswer: String? = null,
) {
  /** Currently focused [MorningStep] at [currentStepIndex], or `null` if [steps] is empty. */
  val currentStep: MorningStep?
    get() = steps.getOrNull(currentStepIndex)

  /** Total sequential cooking steps in this session. */
  val totalSteps: Int
    get() = steps.size

  /** `true` when viewing the opening step (`currentStepIndex == 0`). */
  val isFirstStep: Boolean
    get() = currentStepIndex <= 0

  /** `true` when viewing the final step where primary CTA becomes `"Complete & Deduct Pantry"`. */
  val isFinalStep: Boolean
    get() = steps.isEmpty() || currentStepIndex >= steps.lastIndex

  /** Phase title of the upcoming step (`"Next: The Sear"`), or `null` on the final step. */
  val nextStepTitle: String?
    get() = steps.getOrNull(currentStepIndex + 1)?.effectiveTitle

  /** Countdown timer associated with [currentStep], if present. */
  val activeStepTimer: StepTimerState?
    get() = currentStep?.let { timersByStepNumber[it.stepNumber] }

  /** Concurrent running timers belonging to steps other than [currentStep]. */
  val backgroundRunningTimers: List<StepTimerState>
    get() {
      val currentNum = currentStep?.stepNumber
      return timersByStepNumber.values.filter { it.isRunning && it.stepNumber != currentNum }
    }
}

/**
 * Bilingual (`en-US` and `fr-FR`) hands-free voice command intents recognized in Cook Mode.
 */
sealed interface CookModeVoiceIntent {
  /** Advances to the next step (`"next step"`, `"étape suivante"`). */
  data object NextStep : CookModeVoiceIntent

  /** Returns to the previous step (`"back"`, `"previous step"`, `"étape précédente"`). */
  data object PreviousStep : CookModeVoiceIntent

  /** Pauses the active step timer (`"pause timer"`, `"mets en pause"`). */
  data object PauseTimer : CookModeVoiceIntent

  /** Starts or resumes the active step timer (`"start timer"`, `"lance le minuteur"`). */
  data object ResumeTimer : CookModeVoiceIntent

  /** Adds `+60` seconds to the active step timer (`"add one minute"`, `"ajoute une minute"`). */
  data object AddOneMinute : CookModeVoiceIntent

  /** Resets the active step timer back to its baseline duration (`"reset timer"`, `"réinitialiser"`). */
  data object ResetTimer : CookModeVoiceIntent

  /** Opens the step/recipe pantry ingredients drawer (`"open ingredients"`, `"ingrédients"`). */
  data object OpenIngredients : CookModeVoiceIntent

  /** Routes a free-form culinary question to the grounded Sous-Chef Q&A helper. */
  data class AskSousChefQuestion(val question: String) : CookModeVoiceIntent
}

/**
 * Initializes a fresh [CookModeSessionState] for [recipe], synthesizing step ingredient chips and
 * pre-allocating [StepTimerState] entries for every timed step.
 *
 * @param recipe Target [Recipe] to cook.
 * @param slotId Optional scheduled [MealSlot.slotId] to mark cooked on completion.
 * @param targetServings Household serving count (defaults to [Recipe.baseServings]).
 * @param catalog Optional ingredient catalog used to auto-match step ingredients from text.
 * @param initialCheckedIngredients Optional pre-checked ingredient IDs synced from Clean Reader.
 * @param handsFreeVoiceActive Enables hands-free voice mode immediately when `true`.
 * @return Initialized [CookModeSessionState].
 */
fun startCookSession(
  recipe: Recipe,
  slotId: String? = null,
  targetServings: Int = recipe.baseServings,
  catalog: List<IngredientCatalogItem> = emptyList(),
  initialCheckedIngredients: Set<String> = emptySet(),
  handsFreeVoiceActive: Boolean = false,
): CookModeSessionState {
  val catalogById = indexCatalogById(catalog)
  val rawSteps = recipe.morningSteps.ifEmpty {
    listOf(
      MorningStep(
        stepNumber = 1,
        instruction = "Prepare and cook ${recipe.title} until hot and fragrant.",
        timerSeconds = max(DEFAULT_AD_HOC_STEP_TIMER_SECONDS, recipe.cookMinutes * ONE_MINUTE_SECONDS),
        title = "Cook & Plate",
      ),
    )
  }

  // Enrich steps lacking explicit stepIngredientIds by matching BOM items mentioned in text.
  val enrichedSteps = rawSteps.mapIndexed { idx, step ->
    val resolvedIds = when {
      step.stepIngredientIds.isNotEmpty() -> step.stepIngredientIds
      else -> cookQaInferStepIngredientIds(step.instruction, recipe.bomLines, catalogById, idx == 0)
    }
    step.copy(stepIngredientIds = resolvedIds)
  }

  val initialTimers = linkedMapOf<Int, StepTimerState>()
  for (step in enrichedSteps) {
    val duration = step.timerSeconds
    if (duration != null && duration > 0) {
      initialTimers[step.stepNumber] = StepTimerState(
        stepNumber = step.stepNumber,
        label = step.effectiveTitle,
        totalSeconds = duration,
        remainingSeconds = duration,
        isRunning = false,
      )
    }
  }

  return CookModeSessionState(
    recipe = recipe,
    slotId = slotId,
    targetServings = max(1, targetServings),
    currentStepIndex = 0,
    steps = enrichedSteps,
    timersByStepNumber = initialTimers,
    checkedIngredientIds = initialCheckedIngredients,
    keepScreenAwake = true,
    isHandsFreeVoiceActive = handsFreeVoiceActive,
  )
}

/**
 * Advances [state] forward by one step, clamping cleanly at the final step.
 *
 * @param state Active cook session state.
 * @return Updated [CookModeSessionState].
 */
fun advanceStep(state: CookModeSessionState): CookModeSessionState {
  // Keep background timers running across step transitions so multi-pan dishes stay tracked.
  if (state.steps.isEmpty()) return state
  val nextIdx = (state.currentStepIndex + 1).coerceAtMost(state.steps.lastIndex)
  return state.copy(currentStepIndex = nextIdx)
}

/**
 * Moves [state] backward by one step, clamping cleanly at step index `0`.
 *
 * @param state Active cook session state.
 * @return Updated [CookModeSessionState].
 */
fun previousStep(state: CookModeSessionState): CookModeSessionState {
  // Clamp at step 0 so hands-free "previous step" voice commands never underflow.
  if (state.steps.isEmpty()) return state
  val prevIdx = (state.currentStepIndex - 1).coerceAtLeast(0)
  return state.copy(currentStepIndex = prevIdx)
}

/**
 * Jumps [state] directly to [stepIndex] (`0..steps.lastIndex`) when tapping progress bar segments.
 *
 * @param state Active cook session state.
 * @param stepIndex Target 0-based step index.
 * @return Updated [CookModeSessionState].
 */
fun jumpToStep(state: CookModeSessionState, stepIndex: Int): CookModeSessionState {
  // Coerce arbitrary UI tap indices into valid bounds for safe segmented progress navigation.
  if (state.steps.isEmpty()) return state
  return state.copy(currentStepIndex = stepIndex.coerceIn(0, state.steps.lastIndex))
}

/**
 * Toggles readiness checkmark state for [ingredientId] inside [state].
 *
 * @param state Active cook session state.
 * @param ingredientId Target catalog `ingredientId` tapped by the cook.
 * @return Updated [CookModeSessionState].
 */
fun toggleStepIngredient(state: CookModeSessionState, ingredientId: String): CookModeSessionState {
  // Ignore blank IDs and toggle set membership immutably for Compose chip animations.
  if (ingredientId.isBlank()) return state
  val nextChecked = when {
    ingredientId in state.checkedIngredientIds -> state.checkedIngredientIds - ingredientId
    else -> state.checkedIngredientIds + ingredientId
  }
  return state.copy(checkedIngredientIds = nextChecked)
}

/**
 * Starts, pauses, or restarts the countdown timer for [stepNumber] (defaulting to [CookModeSessionState.currentStep]).
 *
 * @param state Active cook session state.
 * @param stepNumber 1-based step number whose timer is toggled.
 * @return Updated [CookModeSessionState].
 */
fun toggleStepTimer(
  state: CookModeSessionState,
  stepNumber: Int = state.currentStep?.stepNumber ?: 1,
): CookModeSessionState {
  val existing = state.timersByStepNumber[stepNumber]
  // Provision a 3:00 ad-hoc timer if a step lacked preset seconds; restart cleanly from 00:00.
  val updatedTimer = when {
    existing == null -> {
      val stepLabel = state.steps.find { it.stepNumber == stepNumber }?.effectiveTitle ?: "Step $stepNumber"
      StepTimerState(
        stepNumber = stepNumber,
        label = stepLabel,
        totalSeconds = DEFAULT_AD_HOC_STEP_TIMER_SECONDS,
        remainingSeconds = DEFAULT_AD_HOC_STEP_TIMER_SECONDS,
        isRunning = true,
      )
    }
    existing.remainingSeconds <= 0 -> existing.copy(
      remainingSeconds = max(ONE_MINUTE_SECONDS, existing.totalSeconds),
      isRunning = true,
    )
    else -> existing.copy(isRunning = !existing.isRunning)
  }
  return state.copy(timersByStepNumber = state.timersByStepNumber + (stepNumber to updatedTimer))
}

/**
 * Adds `+60` seconds (`+1 Min`) to the countdown timer for [stepNumber].
 *
 * @param state Active cook session state.
 * @param stepNumber 1-based step number to extend.
 * @return Updated [CookModeSessionState].
 */
fun addOneMinuteToTimer(
  state: CookModeSessionState,
  stepNumber: Int = state.currentStep?.stepNumber ?: 1,
): CookModeSessionState {
  val existing = state.timersByStepNumber[stepNumber]
  // Grow totalSeconds alongside remainingSeconds when extending past baseline so ring % <= 100%.
  val updatedTimer = when (existing) {
    null -> {
      val stepLabel = state.steps.find { it.stepNumber == stepNumber }?.effectiveTitle ?: "Step $stepNumber"
      StepTimerState(stepNumber, stepLabel, ONE_MINUTE_SECONDS, ONE_MINUTE_SECONDS, isRunning = false)
    }
    else -> {
      val nextRemaining = existing.remainingSeconds + ONE_MINUTE_SECONDS
      val nextTotal = max(existing.totalSeconds, nextRemaining)
      existing.copy(totalSeconds = nextTotal, remainingSeconds = nextRemaining)
    }
  }
  return state.copy(timersByStepNumber = state.timersByStepNumber + (stepNumber to updatedTimer))
}

/**
 * Resets the countdown timer for [stepNumber] back to its baseline step duration and pauses it.
 *
 * @param state Active cook session state.
 * @param stepNumber 1-based step number to reset.
 * @return Updated [CookModeSessionState].
 */
fun resetStepTimer(
  state: CookModeSessionState,
  stepNumber: Int = state.currentStep?.stepNumber ?: 1,
): CookModeSessionState {
  val step = state.steps.find { it.stepNumber == stepNumber }
  val existing = state.timersByStepNumber[stepNumber] ?: return state
  // Restore original MorningStep.timerSeconds even if "+1 Min" previously extended totalSeconds.
  val baselineTotal = step?.timerSeconds?.takeIf { it > 0 } ?: existing.totalSeconds
  val reset = existing.copy(
    totalSeconds = baselineTotal,
    remainingSeconds = baselineTotal,
    isRunning = false,
  )
  return state.copy(timersByStepNumber = state.timersByStepNumber + (stepNumber to reset))
}

/**
 * Decrements every concurrently running timer in [state] by [elapsedSeconds] (`>= 0`), clamping
 * completed timers cleanly at `0` (`isRunning = false`).
 *
 * @param state Active cook session state.
 * @param elapsedSeconds Seconds elapsed since last tick (defaults to `1`).
 * @return Updated [CookModeSessionState].
 */
fun tickRunningTimers(
  state: CookModeSessionState,
  elapsedSeconds: Int = 1,
): CookModeSessionState {
  // Fast-exit without allocating a new map when no step timers are currently running.
  if (elapsedSeconds <= 0 || state.timersByStepNumber.values.none { it.isRunning }) {
    return state
  }
  // Advance foreground and background timers together in one deterministic pass.
  val nextTimers = state.timersByStepNumber.mapValues { (_, timer) ->
    when {
      !timer.isRunning -> timer
      else -> {
        val nextRem = max(0, timer.remainingSeconds - elapsedSeconds)
        timer.copy(remainingSeconds = nextRem, isRunning = nextRem > 0)
      }
    }
  }
  return state.copy(timersByStepNumber = nextTimers)
}

/**
 * Parses a spoken hands-free transcript in English (`en-US`) or French (`fr-FR`) into a typed
 * [CookModeVoiceIntent].
 *
 * @param transcript Raw speech recognition transcript from `VoiceInputPort`.
 * @return Matched [CookModeVoiceIntent].
 */
fun parseCookModeVoiceIntent(transcript: String): CookModeVoiceIntent {
  val cleaned = transcript.trim()
  val lower = cleaned.lowercase()
  // Match deterministic navigation and timer control phrases (plus phonetic near-matches) first.
  return when {
    cleaned.isEmpty() -> CookModeVoiceIntent.AskSousChefQuestion("What is the key tip for this step?")
    Regex("\\b(next(\\s+step|\\s+up)?|nest\\s+step|nekst\\s+step|continue|go\\s+forward|suivant(e)?|étape\\s+suivante|etape\\s+suivante|continuer)\\b")
      .containsMatchIn(lower) -> CookModeVoiceIntent.NextStep
    Regex("\\b(previous(\\s+step)?|go\\s+back|^back$|précédent(e)?|precedent(e)?|étape\\s+précédente|etape\\s+precedente|retour)\\b")
      .containsMatchIn(lower) -> CookModeVoiceIntent.PreviousStep
    Regex("(\\+\\s*1|add\\s+(one|1|a)\\s+min(ute)?|one\\s+more\\s+minute|plus\\s+une\\s+minute|ajout(e|er)\\s+une\\s+minute)")
      .containsMatchIn(lower) -> CookModeVoiceIntent.AddOneMinute
    Regex("\\b(reset(\\s+timer)?|restart\\s+timer|réinitialis(e|er)|reinitialis(e|er)|remet(s|tre)\\s+à\\s+zéro|remet(s|tre)\\s+a\\s+zero)\\b")
      .containsMatchIn(lower) -> CookModeVoiceIntent.ResetTimer
    Regex("\\b(pause(\\s+timer)?|stop\\s+timer|hold\\s+timer|mets?\\s+en\\s+pause|arrêt(e|er)\\s+le\\s+minuteur|arret(e|er)\\s+le\\s+minuteur)\\b")
      .containsMatchIn(lower) -> CookModeVoiceIntent.PauseTimer
    Regex("\\b((start|resume|set)\\s+.*timer|^resume$|lanc(e|er)\\s+le\\s+minuteur|démarr(e|er)\\s+le\\s+minuteur|demarr(e|er)\\s+le\\s+minuteur|reprendre)\\b")
      .containsMatchIn(lower) -> CookModeVoiceIntent.ResumeTimer
    Regex("\\b(open\\s+ingredients|show\\s+(ingredients|pantry)|^ingredients$|^ingrédients$|ouvrir\\s+les\\s+ingrédients|garde-manger)\\b")
      .containsMatchIn(lower) -> CookModeVoiceIntent.OpenIngredients
    else -> CookModeVoiceIntent.AskSousChefQuestion(cleaned)
  }
}

/**
 * Produces a 100% offline, recipe- and pantry-grounded Sous-Chef answer for a spoken or tapped
 * question on [stepIndex] of [recipe], citing in-stock pantry substitutes and quantities first on
 * missing-ingredient questions without echoing [MorningStep.instruction] verbatim.
 *
 * @param recipe Active [Recipe] in Cook Mode.
 * @param stepIndex 0-based active step index.
 * @param question User question text.
 * @param catalog Optional ingredient catalog resolving display names and substitute groups.
 * @param youngestStage Active child growth stage for choking-safety questions (`EC-08`).
 * @param pantryEntries Optional live household pantry entries for in-stock substitute resolution.
 * @return Concise grounded Sous-Chef response string.
 */
fun answerCookStepQuestion(
  recipe: Recipe,
  stepIndex: Int,
  question: String,
  catalog: List<IngredientCatalogItem> = emptyList(),
  youngestStage: ChildGrowthStage = ChildGrowthStage.TODDLER_1_2Y,
  pantryEntries: List<PantryLedgerEntry> = emptyList(),
): String {
  val step = recipe.morningSteps.getOrNull(stepIndex.coerceAtLeast(0)) ?: recipe.morningSteps.firstOrNull()
  val lower = question.trim().lowercase()

  // 1. Child / toddler choking-safety & texture questions (EC-08).
  if (Regex("toddler|baby|kid|2yo|child|chok|safe|bébé|enfant").containsMatchIn(lower)) {
    val adapted = youngestStage.formatPrepTip(recipe.toddlerPrepTip)
    return adapted ?: "${youngestStage.badgePrefix}: ${youngestStage.prepGuidance}"
  }

  // 2. Missing-ingredient & substitution Q&A grounded in live pantry stock first.
  if (cookQaIsMissingOrSubstituteQuestion(lower)) {
    return cookQaResolveMissingOrSubstituteAnswer(recipe, lower, catalog, pantryEntries)
  }

  // 3. Pairing / serving / beverage questions.
  if (Regex("pair|drink|side|serve\\s+with|accompagn").containsMatchIn(lower)) {
    return recipe.chefPairingNote?.takeIf { it.isNotBlank() }
      ?: "Serve warm family-style with crisp citrus water and extra greens on Mom's plate."
  }

  // 4. Timing / visual doneness / temperature questions.
  if (Regex("how\\s+long|timer|minute|done|ready|look\\s+like|temp|combien|prêt|cuisson").containsMatchIn(lower) && step != null) {
    val timerClause = step.timerSeconds?.takeIf { it > 0 }?.let { secs ->
      val mins = max(1, (secs / 60.0).roundToInt())
      "Target step time is ~$mins min (${secs}s)."
    } ?: "Cook until aromatic."
    val cueClause = step.visualTargetCue?.takeIf { it.isNotBlank() }?.let { " Visual cue: $it." }.orEmpty()
    val tipClause = step.chefTip?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
    return "$timerClause$cueClause$tipClause"
  }

  // 5. Default grounded guidance combining active step tip, visual cue, and instruction.
  if (step != null) {
    val parts = buildList {
      step.chefTip?.takeIf { it.isNotBlank() }?.let(::add)
      step.visualTargetCue?.takeIf { it.isNotBlank() }?.let { add("Target: $it.") }
      if (isEmpty()) add(step.instruction)
    }
    return parts.joinToString(" ")
  }

  return recipe.chefNote?.takeIf { it.isNotBlank() }
    ?: "Follow gentle medium heat and season lightly before plating toddler portions."
}

/**
 * Applies a spoken [transcript] directly onto [state], executing step/timer transitions or
 * populating [CookModeSessionState.lastSousChefAnswer].
 *
 * @param state Current cook session state.
 * @param transcript Recognized voice transcript.
 * @param catalog Optional ingredient catalog for Q&A grounding.
 * @param youngestStage Active child growth stage for safety Q&A.
 * @param pantryEntries Optional live pantry ledger entries for in-stock substitute Q&A.
 * @return Updated [CookModeSessionState].
 */
fun applyVoiceTranscriptToCookSession(
  state: CookModeSessionState,
  transcript: String,
  catalog: List<IngredientCatalogItem> = emptyList(),
  youngestStage: ChildGrowthStage = ChildGrowthStage.TODDLER_1_2Y,
  pantryEntries: List<PantryLedgerEntry> = emptyList(),
): CookModeSessionState {
  val clean = transcript.trim()
  if (clean.isEmpty()) return state
  val intent = parseCookModeVoiceIntent(clean)
  val stepNum = state.currentStep?.stepNumber ?: 1
  val recorded = state.copy(lastVoiceTranscript = clean)

  return when (intent) {
    CookModeVoiceIntent.NextStep -> advanceStep(recorded)
    CookModeVoiceIntent.PreviousStep -> previousStep(recorded)
    CookModeVoiceIntent.PauseTimer -> {
      val currentTimer = recorded.timersByStepNumber[stepNum]
      when {
        currentTimer != null && currentTimer.isRunning -> toggleStepTimer(recorded, stepNum)
        else -> recorded
      }
    }
    CookModeVoiceIntent.ResumeTimer -> {
      val currentTimer = recorded.timersByStepNumber[stepNum]
      when {
        currentTimer == null || !currentTimer.isRunning -> toggleStepTimer(recorded, stepNum)
        else -> recorded
      }
    }
    CookModeVoiceIntent.AddOneMinute -> addOneMinuteToTimer(recorded, stepNum)
    CookModeVoiceIntent.ResetTimer -> resetStepTimer(recorded, stepNum)
    CookModeVoiceIntent.OpenIngredients ->
      recorded.copy(lastSousChefAnswer = "Ingredients for ${recorded.currentStep?.effectiveTitle ?: "this step"} are highlighted above.")
    is CookModeVoiceIntent.AskSousChefQuestion ->
      recorded.copy(
        lastSousChefAnswer = answerCookStepQuestion(
          recipe = recorded.recipe,
          stepIndex = recorded.currentStepIndex,
          question = intent.question,
          catalog = catalog,
          youngestStage = youngestStage,
          pantryEntries = pantryEntries,
        ),
      )
  }
}

private data class CookQaTargetIngredient(
  val conceptKey: String,
  val displayLabel: String,
  val catalogId: String?,
  val substituteGroups: Set<String>,
)

// Detect missing-stock or culinary swap prompts in English and French so step text is never echoed.
private fun cookQaIsMissingOrSubstituteQuestion(lower: String): Boolean =
  Regex(
    "out\\s+of|ran\\s+out|don'?t\\s+have|do\\s+not\\s+have|missing|no\\s+more|without|" +
      "no\\s+[a-z]+\\s+left|substitut|swap|instead|replace|remplac|alternative|sans\\s+|plus\\s+de\\s+",
  ).containsMatchIn(lower)

// Check in-stock substituteGroupId items and culinary equivalents before generic offline rules.
private fun cookQaResolveMissingOrSubstituteAnswer(
  recipe: Recipe,
  lowerQuestion: String,
  catalog: List<IngredientCatalogItem>,
  pantryEntries: List<PantryLedgerEntry>,
): String {
  val lookupById = LinkedHashMap<String, IngredientCatalogItem>().apply {
    LOCAL_MARKET_PRESET_INGREDIENTS.forEach { put(it.ingredientId, it) }
    catalog.forEach { put(it.ingredientId, it) }
  }
  val stockedEntries = pantryEntries.filter(::cookQaIsEntryStocked)
  val stockedById = stockedEntries.associateBy { it.ingredientId }
  val target = cookQaIdentifyTargetIngredient(recipe, lowerQuestion, lookupById)

  if (stockedById.isNotEmpty()) {
    // Priority 1: Check in-stock items sharing an explicit EC-03 substituteGroupId with the missing item.
    if (target.substituteGroups.isNotEmpty()) {
      val groupMatch = stockedEntries.firstOrNull { entry ->
        val item = lookupById[entry.ingredientId]
        entry.ingredientId != target.catalogId &&
          item != null &&
          item.isLocallyAvailable &&
          item.substituteGroupIds.any { it in target.substituteGroups }
      }
      if (groupMatch != null) {
        val partnerItem = lookupById.getValue(groupMatch.ingredientId)
        val badge = cookQaFormatStockBadge(groupMatch, partnerItem)
        return "Use your in-stock ${partnerItem.displayName} ($badge in stock) 1:1 in place of " +
          "${target.displayLabel}—it belongs to the same culinary group and keeps cook times identical."
      }
    }

    // Priority 2: Check curated culinary equivalents currently stocked in the household pantry.
    for ((candidateId, techniqueNote) in cookQaCulinaryEquivalentsFor(target.conceptKey, target.catalogId)) {
      if (candidateId == target.catalogId) continue
      val entry = stockedById[candidateId] ?: continue
      val item = lookupById[candidateId]
      val itemName = item?.displayName ?: candidateId.removePrefix("ing_").replace('_', ' ')
      val badge = cookQaFormatStockBadge(entry, item)
      return "Use your in-stock $itemName ($badge in stock) in place of ${target.displayLabel}: $techniqueNote"
    }
  }

  // Priority 3: Deterministic culinary guidance when pantry context is omitted or equivalents are out.
  if (lowerQuestion.contains("cream") || lowerQuestion.contains("yogurt") || lowerQuestion.contains("butter")) {
    return "Sous-Chef Rule: Temper Plain Greek Yogurt off the heat at the end of the step—it saves ~65 kcal/serving, boosts protein, and prevents curdling."
  }
  if (target.conceptKey == "coconut_milk") {
    return "Sous-Chef Rule: Whisk Plain Greek Yogurt or Whole Fresh Milk off the heat with a teaspoon of olive oil or peanut paste to replace coconut milk without curdling."
  }
  if (target.conceptKey == "milk") {
    return "Sous-Chef Rule: Thin Plain Greek Yogurt 1:1 with warm water off-heat, or whisk warm broth with a small pat of butter in place of milk."
  }
  val subLine = recipe.bomLines.firstOrNull { line ->
    !line.substituteGroupId.isNullOrBlank() && (target.catalogId == null || line.ingredientId == target.catalogId)
  } ?: recipe.bomLines.firstOrNull { !it.substituteGroupId.isNullOrBlank() }
  if (subLine != null) {
    val group = subLine.substituteGroupId.orEmpty()
    val alts = lookupById.values
      .filter { group in it.substituteGroupIds && it.ingredientId != subLine.ingredientId && it.isLocallyAvailable }
      .take(3).joinToString(", ") { it.displayName }
    if (alts.isNotEmpty()) {
      val origName = lookupById[subLine.ingredientId]?.displayName ?: target.displayLabel
      return "You can swap $origName 1:1 in the same base unit with $alts."
    }
  }
  return "To replace ${target.displayLabel}, balance moisture and fat gently: use extra-virgin olive oil for pan fats or Plain Greek Yogurt tempered off-heat for creamy sauces."
}

// Check whether a pantry ledger row represents usable positive stock in the kitchen.
private fun cookQaIsEntryStocked(entry: PantryLedgerEntry): Boolean =
  when (entry.tier) {
    IngredientTier.TIER_2_STAPLE -> !entry.isOutOfStock && entry.stapleStatus != StapleStatus.OUT
    IngredientTier.TIER_1_QUANTITATIVE -> !entry.isOutOfStock && entry.currentQuantity > 0.0
  }

// Format stocked pantry quantity or Tier-2 status for spoken and visual Sous-Chef replies.
private fun cookQaFormatStockBadge(
  entry: PantryLedgerEntry,
  item: IngredientCatalogItem?,
): String =
  when (entry.tier) {
    // Tier-2 spices and oils display "Plenty" or "Low" rather than noisy gram counts.
    IngredientTier.TIER_2_STAPLE -> entry.stapleStatus.name.lowercase().replaceFirstChar { it.uppercaseChar() }
    // Tier-1 items render exact integer or 1-decimal quantities paired with their wire unit.
    IngredientTier.TIER_1_QUANTITATIVE -> {
      val rounded = entry.currentQuantity.roundToInt()
      val qtyStr = if (kotlin.math.abs(entry.currentQuantity - rounded.toDouble()) < 0.05) {
        rounded.toString()
      } else {
        ((entry.currentQuantity * 10.0).roundToInt() / 10.0).toString()
      }
      "$qtyStr ${item?.baseUnit?.wireCode ?: "units"}"
    }
  }

// Identify the missing ingredient mentioned in the cook's question and gather its substitute groups.
private fun cookQaIdentifyTargetIngredient(
  recipe: Recipe,
  lower: String,
  lookupById: Map<String, IngredientCatalogItem>,
): CookQaTargetIngredient {
  val bomGroupById = recipe.bomLines
    .filter { !it.substituteGroupId.isNullOrBlank() }
    .associate { it.ingredientId to it.substituteGroupId.orEmpty() }

  fun targetFor(concept: String, fallbackLabel: String, id: String?): CookQaTargetIngredient {
    val item = id?.let { lookupById[it] }
    val groups = buildSet {
      item?.substituteGroupIds?.let(::addAll)
      id?.let { bomGroupById[it]?.let(::add) }
    }
    return CookQaTargetIngredient(concept, item?.displayName ?: fallbackLabel, id, groups)
  }

  return when {
    lower.contains("coconut milk") || lower.contains("gata") -> targetFor("coconut_milk", "coconut milk", "ing_gata")
    lower.contains("spinach") || lower.contains("épinard") -> targetFor("leafy_greens", "Fresh Baby Spinach", "ing_spinach")
    lower.contains("amaranth") || lower.contains("kulitis") -> targetFor("leafy_greens", "Fresh Amaranth Greens", "ing_amaranth_greens")
    lower.contains("kale") || lower.contains("malunggay") || lower.contains("kangkong") || lower.contains("pechay") ->
      targetFor("leafy_greens", "leafy greens", null)
    lower.contains("butter") || lower.contains("beurre") -> targetFor("butter", "Unsalted Creamery Butter", "ing_butter")
    lower.contains("heavy cream") || lower.contains("cream") -> targetFor("cream", "heavy cream", null)
    lower.contains("milk") || lower.contains("lait") -> targetFor("milk", "Whole Fresh Milk", "ing_milk")
    lower.contains("yogurt") -> targetFor("yogurt", "Plain Greek Yogurt", "ing_greek_yogurt")
    lower.contains("olive oil") -> targetFor("oil", "Extra-Virgin Olive Oil", "ing_olive_oil")
    lower.contains("palm oil") || lower.contains("zomi") -> targetFor("oil", "Unrefined Zomi Red Palm Oil", "ing_red_palm_oil")
    lower.contains("plantain") || lower.contains("dodo") || lower.contains("saba") ->
      targetFor("plantain", "Ripe Yellow Plantains", "ing_plantain")
    lower.contains("banana") -> targetFor("fruit", "Sweet Ripe Bananas", "ing_banana")
    lower.contains("lemon") || lower.contains("lime") || lower.contains("calamansi") ->
      targetFor("citrus", "Fresh Lemons", "ing_lemon")
    else -> {
      val matchedBom = recipe.bomLines.firstOrNull { bom ->
        val name = lookupById[bom.ingredientId]?.displayName?.lowercase().orEmpty()
        val stem = bom.ingredientId.removePrefix("ing_").replace('_', ' ')
        (name.isNotEmpty() && lower.contains(name)) || lower.contains(stem)
      }
      if (matchedBom != null) {
        targetFor("bom_item", matchedBom.ingredientId, matchedBom.ingredientId)
      } else {
        CookQaTargetIngredient("generic", "that ingredient", null, emptySet())
      }
    }
  }
}

// Ordered pantry fallback candidates with step-safe culinary instructions per ingredient family.
private fun cookQaCulinaryEquivalentsFor(
  conceptKey: String,
  catalogId: String?,
): List<Pair<String, String>> =
  when (conceptKey) {
    "coconut_milk" -> listOf(
      "ing_gata" to "pour it in 1:1 over low simmer for rich coconut body.",
      "ing_greek_yogurt" to "temper it off the heat with 2 tbsp warm cooking liquid so it stays silky without curdling.",
      "ing_milk" to "warm it gently with a small drizzle of olive oil or butter for creamy body.",
      "ing_peanut_butter" to "whisk 2 tbsp smooth with warm cooking broth for a velvety West African sauce base.",
    )
    "butter" -> listOf(
      "ing_olive_oil" to "use 3/4 the amount for clean medium-heat searing and basting.",
      "ing_greek_yogurt" to "fold in off the heat at the end to save ~65 kcal/serving and add protein.",
      "ing_red_palm_oil" to "warm gently in the pan for rich savory depth.",
    )
    "milk", "cream" -> listOf(
      "ing_greek_yogurt" to "thin 1:1 with warm water and fold in off the heat so it never curdles.",
      "ing_gata" to "stir in 1:1 over gentle heat as a lush dairy-free cream swap.",
      "ing_milk" to "use 1:1 over gentle low heat.",
      "ing_butter" to "whisk a small pat into warm cooking water for smooth richness.",
    )
    "yogurt" -> listOf(
      "ing_milk" to "use a splash over low heat to loosen the sauce.",
      "ing_gata" to "swirl in off-heat for creamy richness.",
    )
    "leafy_greens" -> listOf(
      "ing_amaranth_greens" to "fold in during the final 2 to 3 minutes until silky and bright.",
      "ing_spinach" to "wilt in residual pan heat right before plating.",
      "ing_malunggay_pechay" to "simmer 2 minutes until tender.",
      "ing_kangkong" to "flash-wilt tender leaves in the final 2 minutes.",
      "ing_pechay" to "slice stems thin and wilt leaves gently at the end.",
      "ing_kale" to "ribbon finely and simmer 4 minutes until tender.",
    )
    "oil" -> listOf(
      "ing_olive_oil" to "heat gently for clean pan searing.",
      "ing_butter" to "melt over medium-low heat so milk solids stay golden.",
      "ing_red_palm_oil" to "warm lightly as an aromatic stew base.",
    )
    "plantain", "fruit" -> listOf(
      "ing_saba_banana" to "slice on the bias and pan-sear golden 1:1 like plantain Dodo.",
      "ing_plantain" to "slice and caramelize in a lightly oiled skillet.",
      "ing_banana" to "sear firm slices briefly or fold in gently.",
      "ing_sweet_potato" to "dice small and pan-roast until caramelized and tender.",
    )
    "citrus" -> listOf(
      "ing_calamansi" to "squeeze fresh off-heat for bright citrus acidity and iron uptake.",
      "ing_lemon" to "add fresh juice off-heat right before serving.",
      "ing_orange" to "use a squeeze of fresh juice to brighten the pan glaze.",
    )
    else -> buildList {
      if (catalogId != "ing_olive_oil") add("ing_olive_oil" to "use a light drizzle to keep pan moisture balanced.")
      if (catalogId != "ing_greek_yogurt") add("ing_greek_yogurt" to "temper off-heat if you need creamy body.")
    }
  }

// Auto-link step ingredient chips by matching BOM display words against step prose.
private fun cookQaInferStepIngredientIds(
  instruction: String,
  bomLines: List<BomLine>,
  catalogById: Map<String, IngredientCatalogItem>,
  isFirstStep: Boolean,
): List<String> {
  val lowerInstruction = instruction.lowercase()
  val matched = bomLines.mapNotNull { bom ->
    val item = catalogById[bom.ingredientId]
    val tokens = buildList {
      add(bom.ingredientId.removePrefix("ing_").replace('_', ' ').lowercase())
      addAll(bom.ingredientId.removePrefix("ing_").lowercase().split('_').filter { it.length >= 3 })
      item?.displayName?.lowercase()?.split(Regex("[^a-z0-9]+"))?.filter { it.length >= 4 }?.let(::addAll)
    }
    if (tokens.any { token -> token.isNotEmpty() && lowerInstruction.contains(token) }) {
      bom.ingredientId
    } else {
      null
    }
  }.distinct()

  // Seed opening step with top 3 BOM lines if prose omits explicit ingredient nouns.
  return when {
    matched.isNotEmpty() -> matched
    isFirstStep -> bomLines.take(3).map { it.ingredientId }
    else -> emptyList()
  }
}

/**
 * Object namespace exposing pure Step-by-Step Cook Mode reducers, bilingual voice parsing, and
 * grounded Sous-Chef Q&A helpers.
 */
object CookModeEngine {
  // Session initializer populating step ingredient chips and step-indexed timers.
  /** Delegates to [com.familykitchen.board.domain.startCookSession]. */
  fun startCookSession(
    recipe: Recipe,
    slotId: String? = null,
    targetServings: Int = recipe.baseServings,
    catalog: List<IngredientCatalogItem> = emptyList(),
    initialCheckedIngredients: Set<String> = emptySet(),
    handsFreeVoiceActive: Boolean = false,
  ): CookModeSessionState =
    com.familykitchen.board.domain.startCookSession(
      recipe = recipe,
      slotId = slotId,
      targetServings = targetServings,
      catalog = catalog,
      initialCheckedIngredients = initialCheckedIngredients,
      handsFreeVoiceActive = handsFreeVoiceActive,
    )

  // Step forward reducer clamped at final step.
  /** Delegates to [com.familykitchen.board.domain.advanceStep]. */
  fun advanceStep(state: CookModeSessionState): CookModeSessionState =
    com.familykitchen.board.domain.advanceStep(state)

  // Step backward reducer clamped at opening step.
  /** Delegates to [com.familykitchen.board.domain.previousStep]. */
  fun previousStep(state: CookModeSessionState): CookModeSessionState =
    com.familykitchen.board.domain.previousStep(state)

  // Direct segmented progress bar step jump reducer.
  /** Delegates to [com.familykitchen.board.domain.jumpToStep]. */
  fun jumpToStep(state: CookModeSessionState, stepIndex: Int): CookModeSessionState =
    com.familykitchen.board.domain.jumpToStep(state, stepIndex)

  // Step-level ingredient readiness pill toggle.
  /** Delegates to [com.familykitchen.board.domain.toggleStepIngredient]. */
  fun toggleStepIngredient(state: CookModeSessionState, ingredientId: String): CookModeSessionState =
    com.familykitchen.board.domain.toggleStepIngredient(state, ingredientId)

  // Start / pause / restart multi-step timer reducer.
  /** Delegates to [com.familykitchen.board.domain.toggleStepTimer]. */
  fun toggleStepTimer(
    state: CookModeSessionState,
    stepNumber: Int = state.currentStep?.stepNumber ?: 1,
  ): CookModeSessionState = com.familykitchen.board.domain.toggleStepTimer(state, stepNumber)

  // +60s timer extension reducer preserving 0..100% ring bounds.
  /** Delegates to [com.familykitchen.board.domain.addOneMinuteToTimer]. */
  fun addOneMinuteToTimer(
    state: CookModeSessionState,
    stepNumber: Int = state.currentStep?.stepNumber ?: 1,
  ): CookModeSessionState = com.familykitchen.board.domain.addOneMinuteToTimer(state, stepNumber)

  // Timer baseline reset reducer.
  /** Delegates to [com.familykitchen.board.domain.resetStepTimer]. */
  fun resetStepTimer(
    state: CookModeSessionState,
    stepNumber: Int = state.currentStep?.stepNumber ?: 1,
  ): CookModeSessionState = com.familykitchen.board.domain.resetStepTimer(state, stepNumber)

  // Concurrent 1-second multi-timer countdown tick reducer.
  /** Delegates to [com.familykitchen.board.domain.tickRunningTimers]. */
  fun tickRunningTimers(
    state: CookModeSessionState,
    elapsedSeconds: Int = 1,
  ): CookModeSessionState = com.familykitchen.board.domain.tickRunningTimers(state, elapsedSeconds)

  // Bilingual (en-US / fr-FR) hands-free voice command classifier.
  /** Delegates to [com.familykitchen.board.domain.parseCookModeVoiceIntent]. */
  fun parseCookModeVoiceIntent(transcript: String): CookModeVoiceIntent =
    com.familykitchen.board.domain.parseCookModeVoiceIntent(transcript)

  // Grounded offline Sous-Chef step Q&A synthesizer with live pantry substitute resolution.
  /** Delegates to [com.familykitchen.board.domain.answerCookStepQuestion]. */
  fun answerCookStepQuestion(
    recipe: Recipe,
    stepIndex: Int,
    question: String,
    catalog: List<IngredientCatalogItem> = emptyList(),
    youngestStage: ChildGrowthStage = ChildGrowthStage.TODDLER_1_2Y,
    pantryEntries: List<PantryLedgerEntry> = emptyList(),
  ): String =
    com.familykitchen.board.domain.answerCookStepQuestion(
      recipe = recipe,
      stepIndex = stepIndex,
      question = question,
      catalog = catalog,
      youngestStage = youngestStage,
      pantryEntries = pantryEntries,
    )

  /** Delegates to [com.familykitchen.board.domain.applyVoiceTranscriptToCookSession]. */
  fun applyVoiceTranscriptToCookSession(
    state: CookModeSessionState,
    transcript: String,
    catalog: List<IngredientCatalogItem> = emptyList(),
    youngestStage: ChildGrowthStage = ChildGrowthStage.TODDLER_1_2Y,
    pantryEntries: List<PantryLedgerEntry> = emptyList(),
  ): CookModeSessionState =
    com.familykitchen.board.domain.applyVoiceTranscriptToCookSession(
      state = state,
      transcript = transcript,
      catalog = catalog,
      youngestStage = youngestStage,
      pantryEntries = pantryEntries,
    )
}
