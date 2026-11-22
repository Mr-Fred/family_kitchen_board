package com.familykitchen.board.ports

import com.familykitchen.board.domain.WebSearchQuery
import com.familykitchen.board.domain.WebSearchToolResult

// Hexagonal coroutine boundary port isolating external culinary / market price web search from the
// 100% offline-first SavorAI functional core and default local Gemma 4 runtime.

/**
 * Hexagonal port for bounded culinary and regional market-price web search (`web_culinary_search`).
 *
 * Architectural posture:
 * - **Default (`OfflineDisabledWebSearchAdapter`)**: Active whenever SavorAI runs in its default
 *   `100%` offline `AiExecutionMode.ON_DEVICE` Gemma 4 posture; opens **zero** network sockets and
 *   returns `WebSearchToolOutcome.DISABLED_OFFLINE_MODE`.
 * - **Opt-In Cloud (`KeystoreCloudWebSearchAdapter`)**: Coded behind `CloudCredentialVaultPort` and
 *   `ALLOWED_SEARCH_HOSTS` HTTPS allowlisting, remaining completely dormant until the household
 *   explicitly switches to `AiExecutionMode.CLOUD_API` and supplies a valid encrypted credential.
 */
interface WebSearchToolPort {
  /**
   * Executes a bounded culinary web search for [query] or returns a deterministic offline/keyless
   * gate status without throwing on disabled offline mode.
   *
   * @param query Validated search terms, max hit count, and household market region hint.
   * @return Structured [WebSearchToolResult] with outcome status and grounded hits (if executed).
   */
  suspend fun searchCulinaryWeb(query: WebSearchQuery): WebSearchToolResult
}
