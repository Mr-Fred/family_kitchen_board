package com.familykitchen.board.domain

// Pure domain exception hierarchy: zero Android framework or java.io dependencies allowed here.
// Every failure mode carries a stable machine-readable errorCode for deterministic JUnit checks.

/**
 * Typed domain exception hierarchy for the Family Kitchen Board functional core.
 *
 * All recoverable validation, local market availability, and on-device multimodal runtime gates
 * surface explicit subclasses of [KitchenBoardException] so presentation and coordinator layers
 * never swallow errors or silently degrade image scans into text-only completions.
 *
 * @property message Human-readable diagnostic message safe to display in UI banners.
 * @property errorCode Stable machine-readable category code for telemetry and test assertions.
 * @property cause Optional underlying parser or runtime exception preserved for debugging.
 */
sealed class KitchenBoardException(
  override val message: String,
  val errorCode: String,
  override val cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Thrown when an external JSON payload, recipe definition, cook override, or untrusted LLM output
 * violates strict schema or domain math invariants.
 *
 * @property message Summary description of the failed boundary validation.
 * @property issues Ordered list of field-level invariant violation messages.
 * @property cause Optional underlying serialization or parsing failure.
 */
class ValidationException(
  override val message: String,
  val issues: List<String> = emptyList(),
  override val cause: Throwable? = null,
) : KitchenBoardException(message = message, errorCode = ERROR_CODE_VALIDATION, cause = cause) {
  companion object {
    /** Stable error code emitted on all schema and boundary invariant failures. */
    const val ERROR_CODE_VALIDATION: String = "VALIDATION_ERROR"
  }
}

/**
 * Thrown when scheduling or building a recipe that requires an ingredient marked unavailable at
 * the local market (`isLocallyAvailable == false`) with no valid substitute group alternative.
 *
 * @property ingredientId Canonical catalog identifier of the unavailable required ingredient.
 * @property recipeId Optional recipe identifier blocked by the unavailable ingredient.
 * @property message Human-readable explanation naming the blocked ingredient and recipe context.
 * @property cause Optional underlying exception cause.
 */
class IngredientUnavailableException(
  val ingredientId: String,
  val recipeId: String? = null,
  message: String = buildUnavailableMessage(ingredientId, recipeId),
  override val cause: Throwable? = null,
) : KitchenBoardException(
  message = message,
  errorCode = ERROR_CODE_INGREDIENT_UNAVAILABLE,
  cause = cause,
) {
  companion object {
    /** Stable error code emitted when local market availability blocks a required BOM line. */
    const val ERROR_CODE_INGREDIENT_UNAVAILABLE: String = "INGREDIENT_UNAVAILABLE"

    // Build a deterministic diagnostic message without nullable interpolation ambiguity.
    // Preserves exact context on EC-02 hard-hide and custom meal availability failures.
    private fun buildUnavailableMessage(ingredientId: String, recipeId: String?): String {
      val recipeSuffix = when {
        recipeId.isNullOrBlank() -> ""
        else -> " for recipe \"$recipeId\""
      }
      return "Required ingredient \"$ingredientId\" is unavailable locally$recipeSuffix."
    }
  }
}

/**
 * Thrown when the user submits a camera capture or gallery photo to an on-device runtime whose
 * active model or delegate does not support multimodal vision input (`supportsVision == false`).
 *
 * Enforces the non-silent vision gate: attached photos must never be silently dropped while
 * executing text-only completion.
 *
 * @property activeBackendLabel Human-readable label of the active non-vision backend or session.
 * @property modelName Optional file or model identifier currently loaded in memory.
 * @property message Clear guidance prompting the user to load a multimodal `.litertlm` model.
 * @property cause Optional underlying native initialization cause.
 */
class VisionNotSupportedException(
  val activeBackendLabel: String = "TEXT_ONLY",
  val modelName: String? = null,
  message: String = buildVisionMessage(activeBackendLabel, modelName),
  override val cause: Throwable? = null,
) : KitchenBoardException(
  message = message,
  errorCode = ERROR_CODE_VISION_NOT_SUPPORTED,
  cause = cause,
) {
  companion object {
    /** Stable error code emitted whenever an image turn hits a non-vision model or backend. */
    const val ERROR_CODE_VISION_NOT_SUPPORTED: String = "VISION_NOT_SUPPORTED"

    // Explain why photo analysis was halted and how the user can proceed safely.
    // Prevents legacy WebView behavior where photos were silently ignored on text-only fallbacks.
    private fun buildVisionMessage(activeBackendLabel: String, modelName: String?): String {
      val modelClause = when {
        modelName.isNullOrBlank() -> "active runtime ($activeBackendLabel)"
        else -> "model \"$modelName\" ($activeBackendLabel)"
      }
      return "Multimodal photo scanning is not supported by $modelClause. " +
        "Import a vision-capable Gemma .litertlm model in AI Setup or remove the attached photo."
    }
  }
}

/**
 * Thrown when on-device inference is requested before a `.litertlm`, `.task`, or `.bin` model
 * has finished initializing into the `Ready` state.
 *
 * @property runtimeStatus Current lifecycle state name of the on-device AI port.
 * @property message Actionable message guiding the user to import a local model or use offline NL.
 * @property cause Optional underlying initialization cause.
 */
class ModelNotReadyException(
  val runtimeStatus: String = "UNINITIALIZED",
  message: String = buildNotReadyMessage(runtimeStatus),
  override val cause: Throwable? = null,
) : KitchenBoardException(message = message, errorCode = ERROR_CODE_MODEL_NOT_READY, cause = cause) {
  companion object {
    /** Stable error code emitted when generation is invoked before model readiness. */
    const val ERROR_CODE_MODEL_NOT_READY: String = "MODEL_NOT_READY"

    // Provide deterministic offline guidance whenever no local model file is loaded yet.
    // AiChefCoordinator catches this on text turns to route smoothly to pantry-grounded NL.
    private fun buildNotReadyMessage(runtimeStatus: String): String =
      "On-device AI model is not ready (state: $runtimeStatus). " +
        "Import a local .litertlm or .task model in AI Setup to run GPU inference."
  }
}

/**
 * Validates that [condition] holds at a domain boundary or throws [ValidationException].
 *
 * @param condition Boolean invariant check to enforce.
 * @param entityName Domain entity name included in the error prefix.
 * @param lazyIssue Lazy builder returning the specific field violation description.
 */
fun requireDomainValid(condition: Boolean, entityName: String, lazyIssue: () -> String) {
  // Short-circuit cleanly when the boundary invariant holds to avoid unnecessary string work.
  if (!condition) {
    val issue = lazyIssue()
    throw ValidationException(message = "Invalid $entityName payload: $issue", issues = listOf(issue))
  }
}
