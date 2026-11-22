package com.familykitchen.board.ai

import com.familykitchen.board.domain.AdkKitchenToolsEngine
import com.familykitchen.board.domain.AdkToolCall
import com.familykitchen.board.domain.AdkToolResult
import com.familykitchen.board.domain.AiMenuAdvisorEngine
import com.familykitchen.board.domain.AiMenuOptimizationProposal
import com.familykitchen.board.domain.AiToolCallResult
import com.familykitchen.board.domain.CandidateMealSwapSpec
import com.familykitchen.board.domain.GoldfishAgentRole
import com.familykitchen.board.domain.GoldfishTaskBrief
import com.familykitchen.board.domain.HouseholdElephantMemory
import com.familykitchen.board.domain.SafetyAuditStatus
import com.familykitchen.board.domain.SafetyAuditVerdict
import com.familykitchen.board.domain.SafetyConstraintViolation
import com.familykitchen.board.domain.SchemaValidator
import com.familykitchen.board.domain.ValidationException
import com.familykitchen.board.domain.WebSearchQuery
import com.familykitchen.board.domain.WebSearchToolOutcome
import com.familykitchen.board.domain.WebSearchToolResult
import com.familykitchen.board.domain.auditCandidateMealSwaps
import com.familykitchen.board.domain.buildGoldfishTaskBrief
import com.familykitchen.board.domain.formatAiFallbackToastMessage
import com.familykitchen.board.domain.formatAiResponseSourceLabel
import com.familykitchen.board.domain.parseCandidateSwapList
import com.familykitchen.board.domain.parseModelToolCallOrNull
import com.familykitchen.board.domain.requireDomainValid
import com.familykitchen.board.domain.routePromptToGoldfishRole
import com.familykitchen.board.domain.sanitizeLlmRawOutput
import com.familykitchen.board.domain.tryExecuteDeterministicToolIntent
import com.familykitchen.board.ports.AiEdgePort
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ports.KitchenSnapshot
import com.familykitchen.board.ports.WebSearchToolPort
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// Single-Model Elephant & Goldfish (EGM) + Google ADK Kotlin graph runner (`SequentialAgent` +
// `LoopAgent` over a single resident Gemma 4 LiteRT-LM Engine with fresh per-call KV-caches).

/** Resolution pathway taken by [SingleGemmaEgmGraphRunner.executeTurn] across its 4-stage graph. */
enum class EgmGraphStageOutcome {
  /** Step 0: `< 5ms` deterministic reflex command matched with `0` LLM calls. */
  DETERMINISTIC_REFLEX_EXECUTED,

  /** Step 2 + Step 3: Stateless Goldfish specialist proposal passed `SafetyAuditor` on iteration 1. */
  GOLDFISH_APPROVED_ITERATION_1,

  /** Step 3 `LoopAgent`: Iteration-1 violations were repaired cleanly on iteration 2. */
  GOLDFISH_REPAIRED_ITERATION_2,

  /** Model was not `Ready` or exhausted repair budget; served audited deterministic safe advisor. */
  OFFLINE_SAFE_ADVISOR_FALLBACK,
}

/**
 * Per-node telemetry trace proving context isolation (`0` raw chat transcript) and KV-cache reset
 * across each Goldfish invocation on the shared Gemma 4 runtime.
 *
 * @property nodeIndex 1-based sequential Goldfish pass index within the turn.
 * @property role Specialist [GoldfishAgentRole] executed on this pass.
 * @property promptSentToModel Exact isolated prompt string dispatched to [AiEdgePort.generateText].
 * @property injectedFactIds Semantic fact IDs (`<= 5`) sliced from [HouseholdElephantMemory].
 * @property rawModelReply Sanitized completion returned by the model for this node.
 */
data class GoldfishNodeTrace(
  val nodeIndex: Int,
  val role: GoldfishAgentRole,
  val promptSentToModel: String,
  val injectedFactIds: List<String>,
  val rawModelReply: String,
)

/**
 * Complete immutable turn result produced by [SingleGemmaEgmGraphRunner.executeTurn].
 *
 * @property outcome Exact 4-stage resolution pathway taken.
 * @property selectedRole Specialist [GoldfishAgentRole] selected during Step 1 Elephant triage.
 * @property initialBrief Isolated Step-1 [GoldfishTaskBrief] (`<= 5` facts, `0` chat history).
 * @property reflexToolResult Populated when Step 0 `< 5ms` deterministic reflex fast-path matched.
 * @property proposal HITL confirmation-gated [AiMenuOptimizationProposal] for advisory turns.
 * @property executedTools Ordered ADK read-only / web-search tool results gathered in Step 2.
 * @property criticVerdicts `SafetyAuditVerdict` history across `LoopAgent` iterations (`1..2`).
 * @property loopIterationsUsed Number of `LoopAgent` critic iterations executed (`0`, `1`, or `2`).
 * @property llmInvocationCount Total [AiEdgePort.generateText] calls made on the single Gemma engine.
 * @property requiresUserConfirmation Always `true` when [proposal] carries board/grocery changes.
 * @property nodeTraces Per-call prompt and fact traces verifying Goldfish context isolation.
 * @property responseSourceLabel Concise model or offline provenance badge label.
 * @property fallbackToastMessage Optional secret-scrubbed diagnostic banner when falling back.
 */
data class EgmGraphTurnOutcome(
  val outcome: EgmGraphStageOutcome,
  val selectedRole: GoldfishAgentRole,
  val initialBrief: GoldfishTaskBrief,
  val reflexToolResult: AiToolCallResult? = null,
  val proposal: AiMenuOptimizationProposal? = null,
  val executedTools: List<AdkToolResult> = emptyList(),
  val criticVerdicts: List<SafetyAuditVerdict> = emptyList(),
  val loopIterationsUsed: Int = 0,
  val llmInvocationCount: Int = 0,
  val requiresUserConfirmation: Boolean = true,
  val nodeTraces: List<GoldfishNodeTrace> = emptyList(),
  val responseSourceLabel: String,
  val fallbackToastMessage: String? = null,
)

/**
 * Orchestrates the **Single-Model Elephant & Goldfish ADK Graph** over [AiEdgePort] and
 * [WebSearchToolPort] using one resident Gemma 4 `Engine` and ephemeral per-node `Conversation`s.
 *
 * @param aiEdgePort Shared single-model on-device (`LiteRtMultimodalEngine`) or hybrid AI port.
 * @param webSearchToolPort Hexagonal search port (defaults to [OfflineDisabledWebSearchAdapter]).
 * @param maxReadOnlyToolsPerTurn Maximum read-only ADK tool calls per specialist pass (`2` default).
 * @param maxLoopIterations Maximum `LoopAgent` critic-repair iterations (`2` default).
 * @param runFreshEyesLlmCritic When `true`, appends an extra `SAFETY_CONSTRAINT_AUDITOR` LLM pass.
 */
class SingleGemmaEgmGraphRunner(
  private val aiEdgePort: AiEdgePort,
  private val webSearchToolPort: WebSearchToolPort = OfflineDisabledWebSearchAdapter(),
  private val maxReadOnlyToolsPerTurn: Int = DEFAULT_MAX_READ_ONLY_TOOLS,
  private val maxLoopIterations: Int = DEFAULT_MAX_LOOP_ITERATIONS,
  private val runFreshEyesLlmCritic: Boolean = false,
) {

  // Lenient pre-grounding JSON reader extracts raw suggestedSwaps so EC-01/EC-02 faults are audited.
  private val lenientInspectJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  /**
   * Executes one user prompt through the 4-stage Single-Gemma Elephant & Goldfish graph without
   * mutating [snapshot] directly.
   *
   * @param snapshot Current immutable household state snapshot.
   * @param promptText Natural-language request entered or spoken by the user.
   * @param elephantMemory Household Elephant memory (defaults to [KitchenSnapshot.elephantMemory]).
   * @param activeDayIndex Active weekday index (`0 = Monday`..`6 = Sunday`) for `"tonight"` commands.
   * @return Complete [EgmGraphTurnOutcome] with traces, critic verdicts, and HITL proposal.
   */
  suspend fun executeTurn(
    snapshot: KitchenSnapshot,
    promptText: String,
    elephantMemory: HouseholdElephantMemory = snapshot.elephantMemory,
    activeDayIndex: Int = 0,
  ): EgmGraphTurnOutcome {
    val cleanPrompt = promptText.trim()
    requireDomainValid(cleanPrompt.isNotEmpty(), "EgmGraphPrompt") { "promptText must be non-blank" }

    // Step 1: Select specialist Goldfish role & <=5 Elephant semantic facts (0 chat history).
    val targetRole = routePromptToGoldfishRole(cleanPrompt, hasImageAttachment = false)
    val initialBrief = buildGoldfishTaskBrief(targetRole, cleanPrompt, elephantMemory)

    // Step 0: < 5ms deterministic reflex fast-path for explicit batch commands (0 LLM invocations).
    val reflexHit = tryExecuteDeterministicToolIntent(
      cleanPrompt, false, snapshot.ingredients, snapshot.recipes,
      snapshot.weeklyBoard, snapshot.pantryEntries, snapshot.householdProfile.householdAllergens, activeDayIndex,
    )
    if (reflexHit != null) {
      return EgmGraphTurnOutcome(
        outcome = EgmGraphStageOutcome.DETERMINISTIC_REFLEX_EXECUTED,
        selectedRole = targetRole,
        initialBrief = initialBrief,
        reflexToolResult = reflexHit,
        requiresUserConfirmation = false,
        responseSourceLabel = SOURCE_LABEL_REFLEX_TOOL,
      )
    }

    // Route directly to deterministic offline advisor when local Gemma runtime is not Ready yet.
    val runtime = aiEdgePort.runtimeState.value
    if (runtime !is AiRuntimeState.Ready) {
      return buildSafeDeterministicFallbackOutcome(snapshot, cleanPrompt, targetRole, initialBrief, emptyList(), emptyList(), 0, 0, emptyList(), null)
    }

    val sourceLabel = formatAiResponseSourceLabel(runtime.capabilities.backendLabel, runtime.capabilities.modelName)
    val executedTools = mutableListOf<AdkToolResult>()
    val criticVerdicts = mutableListOf<SafetyAuditVerdict>()
    val traces = mutableListOf<GoldfishNodeTrace>()
    var llmCalls = 0

    try {
      // Step 2: Stateless Specialist Goldfish pass on a fresh per-call LiteRT-LM Conversation.
      var activePrompt = initialBrief.renderIsolatedPromptEnvelope()
      var currentRawReply = invokeStatelessGoldfishPass(targetRole, initialBrief, activePrompt, traces)
      llmCalls += 1

      // Execute up to 2 read-only ADK / WebSearch tool calls and feed compact JSON observations back.
      val safeToolBudget = maxReadOnlyToolsPerTurn.coerceIn(0, MAX_HARD_TOOL_CAP)
      var toolRound = 0
      while (toolRound < safeToolBudget) {
        val requestedTool = parseModelToolCallOrNull(currentRawReply) ?: break
        // Proposal or audit tool calls exit the read-only loop directly into Step 3 Critic!
        if (requestedTool.toolName == "propose_meal_board_swaps" || requestedTool.toolName == "audit_candidate_meal_swaps") break
        val toolResult = dispatchReadOnlyOrWebTool(snapshot, elephantMemory, requestedTool)
        executedTools.add(toolResult)
        toolRound += 1

        // Re-prompt on a fresh stateless Conversation carrying ONLY brief + compact tool outputs.
        activePrompt = buildObservationSynthesisEnvelope(initialBrief, executedTools)
        currentRawReply = invokeStatelessGoldfishPass(targetRole, initialBrief, activePrompt, traces)
        llmCalls += 1
      }

      // Step 3: LoopAgent Adversarial Safety Critic (maxIterations = 2).
      val safeMaxLoops = maxLoopIterations.coerceIn(1, 2)
      val iter1CandidateSwaps = extractCandidateSwaps(currentRawReply)
      val iter1Verdict = runCombinedSafetyAudit(snapshot, elephantMemory, iter1CandidateSwaps, traces) { llmCalls += 1 }
      criticVerdicts.add(iter1Verdict)

      if (iter1Verdict.isApproved) {
        // Iteration 1 passed all EC-01, EC-02, EC-08, and life-event gates: emit HITL proposal.
        val approvedProposal = groundApprovedReplyOrProposalTool(currentRawReply, cleanPrompt, snapshot)
        return EgmGraphTurnOutcome(
          EgmGraphStageOutcome.GOLDFISH_APPROVED_ITERATION_1, targetRole, initialBrief, null,
          approvedProposal, executedTools, criticVerdicts, 1, llmCalls, true, traces, sourceLabel,
        )
      }

      // Iteration 1 was REJECTED_NEEDS_REPAIR: feed ONLY SafetyConstraintViolations into Iteration 2.
      if (safeMaxLoops >= 2) {
        val repairBrief = buildGoldfishTaskBrief(targetRole, cleanPrompt, elephantMemory, repairViolations = iter1Verdict.violations)
        val repairEnvelope = repairBrief.renderIsolatedPromptEnvelope(REPAIR_PASS_OUTPUT_HINT)
        val repairedRawReply = invokeStatelessGoldfishPass(targetRole, repairBrief, repairEnvelope, traces)
        llmCalls += 1

        val iter2Swaps = extractCandidateSwaps(repairedRawReply)
        val iter2Verdict = runCombinedSafetyAudit(snapshot, elephantMemory, iter2Swaps, traces) { llmCalls += 1 }
        criticVerdicts.add(iter2Verdict)

        if (iter2Verdict.isApproved) {
          // Iteration 2 repaired all flagged violations cleanly without mutating snapshot state!
          val repairedProposal = groundApprovedReplyOrProposalTool(repairedRawReply, cleanPrompt, snapshot)
          return EgmGraphTurnOutcome(
            EgmGraphStageOutcome.GOLDFISH_REPAIRED_ITERATION_2, targetRole, initialBrief, null,
            repairedProposal, executedTools, criticVerdicts, 2, llmCalls, true, traces, sourceLabel,
          )
        }
      }

      // Exhausted LoopAgent repair budget: guarantee safety via deterministic audited fallback.
      return buildSafeDeterministicFallbackOutcome(
        snapshot, cleanPrompt, targetRole, initialBrief, executedTools, criticVerdicts,
        criticVerdicts.size, llmCalls, traces, "Repaired unsafe AI swap draft using offline safety-audited pantry advisor.",
      )
    } catch (cancel: CancellationException) {
      // Preserve coroutine cancellation without degrading into fallback output.
      throw cancel
    } catch (err: Throwable) {
      return buildSafeDeterministicFallbackOutcome(
        snapshot, cleanPrompt, targetRole, initialBrief, executedTools, criticVerdicts,
        criticVerdicts.size, llmCalls, traces, formatAiFallbackToastMessage(err),
      )
    }
  }

  // Record each stateless Goldfish prompt & reply trace so tests can verify 0 chat-history leakage.
  private suspend fun invokeStatelessGoldfishPass(
    role: GoldfishAgentRole,
    brief: GoldfishTaskBrief,
    promptEnvelope: String,
    traces: MutableList<GoldfishNodeTrace>,
  ): String {
    val raw = aiEdgePort.generateText(promptEnvelope)
    traces.add(GoldfishNodeTrace(traces.size + 1, role, promptEnvelope, brief.relevantFacts.map { it.factId }, raw))
    return raw
  }

  // Route web_culinary_search to WebSearchToolPort and all other tools to pure AdkKitchenToolsEngine.
  private suspend fun dispatchReadOnlyOrWebTool(
    snapshot: KitchenSnapshot,
    elephantMemory: HouseholdElephantMemory,
    call: AdkToolCall,
  ): AdkToolResult =
    when (call.toolName) {
      "web_culinary_search" -> {
        val searchQuery = WebSearchQuery(
          query = call.arguments["query"]?.trim().orEmpty().ifEmpty { "healthy family recipe" },
          maxResults = call.arguments["maxResults"]?.toIntOrNull()?.coerceIn(1, 5) ?: 3,
          marketRegionHint = snapshot.householdProfile.marketRegionLabel,
        )
        val result: WebSearchToolResult = webSearchToolPort.searchCulinaryWeb(searchQuery)
        AdkToolResult(
          toolName = "web_culinary_search",
          isSuccess = result.outcome == WebSearchToolOutcome.EXECUTED,
          summaryText = result.diagnosticMessage,
          payloadJson = SchemaValidator.strictJson.encodeToString(WebSearchToolResult.serializer(), result),
        )
      }
      else -> AdkKitchenToolsEngine.executeKitchenDomainTool(snapshot, elephantMemory, call)
    }

  private fun buildObservationSynthesisEnvelope(brief: GoldfishTaskBrief, observations: List<AdkToolResult>): String {
    val base = brief.renderIsolatedPromptEnvelope(SYNTHESIS_AFTER_TOOLS_HINT)
    val obsLines = observations.joinToString("\n") { "TOOL_RESULT[${it.toolName}]: ${it.payloadJson}" }
    return "$base\n$obsLines"
  }

  // Combine deterministic EC-01/EC-02/EC-08 audit with optional fresh-eyes Goldfish LLM critic pass.
  private suspend fun runCombinedSafetyAudit(
    snapshot: KitchenSnapshot,
    elephantMemory: HouseholdElephantMemory,
    candidateSwaps: List<CandidateMealSwapSpec>,
    traces: MutableList<GoldfishNodeTrace>,
    onLlmCall: () -> Unit,
  ): SafetyAuditVerdict {
    val deterministicVerdict = auditCandidateMealSwaps(snapshot, candidateSwaps)
    if (!deterministicVerdict.isApproved || !runFreshEyesLlmCritic || candidateSwaps.isEmpty()) {
      return deterministicVerdict
    }
    // Optional fresh-eyes Goldfish critic pass on a clean Conversation (0 specialist bias).
    val swapSummary = candidateSwaps.joinToString(", ") { "${it.slotId}->${it.recipeId}" }
    val criticBrief = buildGoldfishTaskBrief(GoldfishAgentRole.SAFETY_CONSTRAINT_AUDITOR, "Audit candidate swaps: $swapSummary", elephantMemory)
    val criticPrompt = criticBrief.renderIsolatedPromptEnvelope("Return ONLY JSON {\"approved\":true} or {\"approved\":false,\"reason\":\"...\"}.")
    val criticReply = invokeStatelessGoldfishPass(GoldfishAgentRole.SAFETY_CONSTRAINT_AUDITOR, criticBrief, criticPrompt, traces)
    onLlmCall()
    val sanitized = sanitizeLlmRawOutput(criticReply).lowercase()
    return when {
      sanitized.contains("\"approved\":false") || sanitized.contains("\"approved\": false") ->
        SafetyAuditVerdict(
          SafetyAuditStatus.REJECTED_NEEDS_REPAIR,
          listOf(SafetyConstraintViolation("CRITIC_LLM_REJECTION", "Fresh-eyes Goldfish safety auditor flagged swap batch ($swapSummary).", "Select allergen-safe <=25m breakfast and family recipes.")),
          candidateSwaps.size,
          "Rejected by fresh-eyes Goldfish critic pass.",
        )
      else -> deterministicVerdict
    }
  }

  // Extract candidate swaps BEFORE grounding so EC-01/EC-02 violations produce structured feedback.
  private fun extractCandidateSwaps(rawOutput: String): List<CandidateMealSwapSpec> {
    val toolCall = parseModelToolCallOrNull(rawOutput)
    if (toolCall != null && toolCall.toolName == "propose_meal_board_swaps") {
      return parseCandidateSwapList(toolCall.arguments["swaps"].orEmpty())
    }
    val sanitized = sanitizeLlmRawOutput(rawOutput)
    val start = sanitized.indexOf('{')
    val end = sanitized.lastIndexOf('}')
    if (start < 0 || end <= start) return emptyList()

    val rootObj = try {
      lenientInspectJson.parseToJsonElement(sanitized.substring(start, end + 1)) as? JsonObject
    } catch (_: SerializationException) {
      null
    } catch (_: IllegalArgumentException) {
      null
    } ?: return emptyList()

    val swapsArray = (rootObj["suggestedSwaps"] as? JsonArray) ?: (rootObj["slotSwaps"] as? JsonArray) ?: return emptyList()
    return swapsArray.mapNotNull { element ->
      val obj = element as? JsonObject ?: return@mapNotNull null
      val slotId = (obj["slotId"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
      val recipeId = (
        (obj["recipeId"] as? JsonPrimitive)?.contentOrNull
          ?: ((obj["suggestedRecipe"] as? JsonObject)?.get("recipeId") as? JsonPrimitive)?.contentOrNull
        )?.trim().orEmpty()
      val rationale = (obj["rationale"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
      if (slotId.isNotEmpty() && recipeId.isNotEmpty() && slotId != "..." && recipeId != "...") {
        CandidateMealSwapSpec(slotId, recipeId, rationale)
      } else {
        null
      }
    }
  }

  private fun groundApprovedReplyOrProposalTool(rawOutput: String, userPrompt: String, snapshot: KitchenSnapshot): AiMenuOptimizationProposal {
    val toolCall = parseModelToolCallOrNull(rawOutput)
    if (toolCall != null && toolCall.toolName == "propose_meal_board_swaps") {
      val result = AdkKitchenToolsEngine.executeKitchenDomainTool(snapshot, snapshot.elephantMemory, toolCall)
      return result.proposedMenuOptimization
        ?: throw ValidationException("propose_meal_board_swaps did not produce an approved proposal.", listOf(result.summaryText))
    }
    return AiMenuAdvisorEngine.groundFlexibleLlmTextReply(
      rawOutput, userPrompt, snapshot.weeklyBoard, snapshot.recipes,
      snapshot.ingredients, snapshot.pantryEntries, snapshot.householdProfile,
    )
  }

  private fun buildSafeDeterministicFallbackOutcome(
    snapshot: KitchenSnapshot,
    cleanPrompt: String,
    selectedRole: GoldfishAgentRole,
    initialBrief: GoldfishTaskBrief,
    executedTools: List<AdkToolResult>,
    criticVerdicts: List<SafetyAuditVerdict>,
    loopIterationsUsed: Int,
    llmCalls: Int,
    nodeTraces: List<GoldfishNodeTrace>,
    diagnosticToast: String?,
  ): EgmGraphTurnOutcome {
    // Offline advisor strictly enforces EC-01 <=25m breakfast caps and EC-02 allergen exclusions.
    val safeProposal = AiMenuAdvisorEngine.generatePantryGroundedMenuProposal(
      cleanPrompt, snapshot.weeklyBoard, snapshot.recipes, snapshot.ingredients,
      snapshot.pantryEntries, snapshot.householdProfile.householdAllergens, snapshot.householdProfile,
    )
    return EgmGraphTurnOutcome(
      EgmGraphStageOutcome.OFFLINE_SAFE_ADVISOR_FALLBACK, selectedRole, initialBrief, null,
      safeProposal, executedTools, criticVerdicts, loopIterationsUsed, llmCalls, true,
      nodeTraces, SOURCE_LABEL_OFFLINE_EGM_ADVISOR, diagnosticToast,
    )
  }

  companion object {
    /** Default read-only ADK tool call ceiling per specialist Goldfish turn. */
    const val DEFAULT_MAX_READ_ONLY_TOOLS: Int = 2

    /** Default `LoopAgent` critic-repair iteration ceiling (`1` initial + `1` repair). */
    const val DEFAULT_MAX_LOOP_ITERATIONS: Int = 2

    private const val MAX_HARD_TOOL_CAP: Int = 3
    private const val SOURCE_LABEL_REFLEX_TOOL: String = "Kitchen Reflex Tool"
    private const val SOURCE_LABEL_OFFLINE_EGM_ADVISOR: String = "Offline Advisor"

    private const val SYNTHESIS_AFTER_TOOLS_HINT: String =
      "Using the TOOL_RESULT observations below, return final grounded JSON " +
        "{\"focusTag\":\"KIDS_BRAIN_BOOSTER\",\"assistantSummary\":\"...\",\"isConversationalOnly\":false," +
        "\"suggestedSwaps\":[{\"slotId\":\"...\",\"recipeId\":\"...\",\"rationale\":\"...\"}]}."

    private const val REPAIR_PASS_OUTPUT_HINT: String =
      "Repair EVERY violation in CRITIC_VIOLATIONS_TO_REPAIR and return ONLY valid grounded JSON " +
        "{\"focusTag\":\"KIDS_BRAIN_BOOSTER\",\"assistantSummary\":\"...\",\"isConversationalOnly\":false," +
        "\"suggestedSwaps\":[{\"slotId\":\"...\",\"recipeId\":\"...\",\"rationale\":\"...\"}]}."
  }
}
