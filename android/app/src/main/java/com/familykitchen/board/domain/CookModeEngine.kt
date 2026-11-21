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
      else -> inferStepIngredientIds(step.instruction, recipe.bomLines, catalogById, idx == 0)
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
 * Produces a 100% offline, recipe-grounded Sous-Chef answer for a spoken or tapped question on
 * [stepIndex] of [recipe], citing step visual cues, timers, substitutions, and child safety tips.
 *
 * @param recipe Active [Recipe] in Cook Mode.
 * @param stepIndex 0-based active step index.
 * @param question User question text.
 * @param catalog Optional ingredient catalog resolving substitute options.
 * @param youngestStage Active child growth stage for choking-safety questions (`EC-08`).
 * @return Concise grounded Sous-Chef response string.
 */
fun answerCookStepQuestion(
  recipe: Recipe,
  stepIndex: Int,
  question: String,
  catalog: List<IngredientCatalogItem> = emptyList(),
  youngestStage: ChildGrowthStage = ChildGrowthStage.TODDLER_1_2Y,
): String {
  val step = recipe.morningSteps.getOrNull(stepIndex.coerceAtLeast(0)) ?: recipe.morningSteps.firstOrNull()
  val lower = question.trim().lowercase()
  val catalogById = indexCatalogById(catalog)

  // 1. Child / toddler choking-safety & texture questions (EC-08).
  if (Regex("toddler|baby|kid|2yo|child|chok|safe|bébé|enfant").containsMatchIn(lower)) {
    val adapted = youngestStage.formatPrepTip(recipe.toddlerPrepTip)
    return adapted ?: "${youngestStage.badgePrefix}: ${youngestStage.prepGuidance}"
  }

  // 2. Substitution / swap questions during cooking.
  if (Regex("substitut|swap|instead|replace|remplac|heavy\\s+cream|yogurt").containsMatchIn(lower)) {
    if (lower.contains("cream") || lower.contains("yogurt") || lower.contains("butter")) {
      return "Sous-Chef Rule: Temper Plain Greek Yogurt off the heat at the end of the step—it saves ~65 kcal/serving, boosts protein, and prevents curdling."
    }
    val subLine = recipe.bomLines.firstOrNull { !it.substituteGroupId.isNullOrBlank() }
    if (subLine != null) {
      val group = subLine.substituteGroupId.orEmpty()
      val alts = catalog.filter { group in it.substituteGroupIds && it.ingredientId != subLine.ingredientId && it.isLocallyAvailable }
        .take(3).joinToString(", ") { it.displayName }
      if (alts.isNotEmpty()) {
        val origName = catalogById[subLine.ingredientId]?.displayName ?: subLine.ingredientId
        return "You can swap $origName 1:1 in the same base unit with $alts."
      }
    }
    return "Keep moisture and fat balanced: use olive oil for butter or Greek yogurt tempered off-heat for rich dairy."
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
 * @return Updated [CookModeSessionState].
 */
fun applyVoiceTranscriptToCookSession(
  state: CookModeSessionState,
  transcript: String,
  catalog: List<IngredientCatalogItem> = emptyList(),
  youngestStage: ChildGrowthStage = ChildGrowthStage.TODDLER_1_2Y,
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
        ),
      )
  }
}

// Auto-link step ingredient chips by matching BOM display words against step prose.
private fun inferStepIngredientIds(
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

  // Grounded offline Sous-Chef step Q&A synthesizer.
  /** Delegates to [com.familykitchen.board.domain.answerCookStepQuestion]. */
  fun answerCookStepQuestion(
    recipe: Recipe,
    stepIndex: Int,
    question: String,
    catalog: List<IngredientCatalogItem> = emptyList(),
    youngestStage: ChildGrowthStage = ChildGrowthStage.TODDLER_1_2Y,
  ): String =
    com.familykitchen.board.domain.answerCookStepQuestion(
      recipe = recipe,
      stepIndex = stepIndex,
      question = question,
      catalog = catalog,
      youngestStage = youngestStage,
    )

  /** Delegates to [com.familykitchen.board.domain.applyVoiceTranscriptToCookSession]. */
  fun applyVoiceTranscriptToCookSession(
    state: CookModeSessionState,
    transcript: String,
    catalog: List<IngredientCatalogItem> = emptyList(),
    youngestStage: ChildGrowthStage = ChildGrowthStage.TODDLER_1_2Y,
  ): CookModeSessionState =
    com.familykitchen.board.domain.applyVoiceTranscriptToCookSession(
      state = state,
      transcript = transcript,
      catalog = catalog,
      youngestStage = youngestStage,
    )
}
