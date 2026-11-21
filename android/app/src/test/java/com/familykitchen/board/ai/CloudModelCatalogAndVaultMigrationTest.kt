package com.familykitchen.board.ai

import com.familykitchen.board.data.FakeKitchenStorage
import com.familykitchen.board.data.InMemoryCloudCredentialVault
import com.familykitchen.board.domain.AiChefAction
import com.familykitchen.board.domain.AiExecutionMode
import com.familykitchen.board.domain.BaseUnit
import com.familykitchen.board.domain.BomLine
import com.familykitchen.board.domain.CloudLlmConfig
import com.familykitchen.board.domain.CloudLlmProvider
import com.familykitchen.board.domain.CookOwner
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.IngredientCategory
import com.familykitchen.board.domain.IngredientTier
import com.familykitchen.board.domain.MealSlotType
import com.familykitchen.board.domain.MorningStep
import com.familykitchen.board.domain.PantryLedgerEntry
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.SeedCatalogDataset
import com.familykitchen.board.domain.StapleStatus
import com.familykitchen.board.domain.isDeprecatedCloudModelId
import com.familykitchen.board.domain.migrateDeprecatedCloudModelId
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ports.KitchenSnapshot
import com.familykitchen.board.ports.SeedCatalogPort
import com.familykitchen.board.ui.KitchenBoardViewModel
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 100% hermetic, zero-socket JUnit 4 suite verifying deprecated Cloud LLM ID migration, live
// GET /models catalog discovery across all 4 providers, and KitchenBoardViewModel sync lifecycle.

/**
 * Hermetic JUnit 4 verification suite for [migrateDeprecatedCloudModelId], [isDeprecatedCloudModelId],
 * [CloudModelCatalogFetcher], [InMemoryCloudCredentialVault], and [KitchenBoardViewModel] covering:
 * 1. Seamless automatic upgrade of all retired pre-late-2026 model slugs on vault load and update,
 * 2. Live `GET /models` catalog parsing, header-only auth, deprecated/non-chat filtering, `<= 16`
 *    chip capping, and SSRF allowlist enforcement across `GEMINI`, `OPENAI`, `ANTHROPIC`, and
 *    `OPENROUTER`,
 * 3. End-to-end [AiChefAction.RefreshCloudModels], `isRefreshingCloudModels` spinner transitions,
 *    and zero raw-key leakage in serialized [KitchenSnapshot] JSON.
 */
class CloudModelCatalogAndVaultMigrationTest {

  private val strictJson = Json { encodeDefaults = true }

  // Compact household seed fixture used when verifying KitchenBoardViewModel cloud actions.
  private val seedDataset: SeedCatalogDataset = SeedCatalogDataset(
    ingredients = listOf(
      IngredientCatalogItem("ing_egg", "Large Farm Eggs", IngredientCategory.DAIRY_EGGS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.COUNT, 12.0),
      IngredientCatalogItem("ing_olive_oil", "Extra-Virgin Olive Oil", IngredientCategory.PANTRY_STAPLE, IngredientTier.TIER_2_STAPLE, BaseUnit.MILLILITER, 750.0),
    ),
    recipes = listOf(
      Recipe(
        recipeId = "rec_savory_scramble", title = "Olive Oil Soft Scramble",
        sourceAttribution = "Household Test Kitchen",
        allowedSlots = listOf(MealSlotType.BREAKFAST, MealSlotType.LUNCH, MealSlotType.DINNER),
        preferredOwner = CookOwner.WIFE, rawPrepMinutes = 4, cookMinutes = 6, calibratedActiveMinutes = 12,
        morningSteps = listOf(MorningStep(1, "Whisk and gently fold eggs in olive oil.", 360)),
        toddlerPrepTip = "Cut soft egg curds into bite-sized ribbons for 2yo.",
        bomLines = listOf(BomLine("ing_egg", 4.0), BomLine("ing_olive_oil", 10.0)),
      ),
    ),
    initialPantry = listOf(
      PantryLedgerEntry("ing_egg", IngredientTier.TIER_1_QUANTITATIVE, 6.0),
      PantryLedgerEntry("ing_olive_oil", IngredientTier.TIER_2_STAPLE, 200.0, StapleStatus.LOW),
    ),
  )

  /**
   * Verifies that [migrateDeprecatedCloudModelId] and [InMemoryCloudCredentialVault] upgrade every
   * retired Gemini, OpenAI, Claude, and OpenRouter model ID to its active late-2026 replacement.
   */
  @Test
  fun deprecatedCloudModelMigration_upgradesAllRetiredIdsAndPreservesActiveModels() = runTest {
    // 1. Verify exact canonical migration matrix across Gemini, OpenAI, Claude, and OpenRouter.
    assertEquals("gemini-3.5-flash-lite", migrateDeprecatedCloudModelId(CloudLlmProvider.GEMINI, "gemini-2.5-flash"))
    assertEquals("gemini-3.8-flash", migrateDeprecatedCloudModelId(CloudLlmProvider.GEMINI, "gemini-2.5-pro"))
    assertEquals("gemini-3.8-flash", migrateDeprecatedCloudModelId(CloudLlmProvider.GEMINI, "gemini-3.7-flash"))
    assertEquals("gemini-3.8-flash", migrateDeprecatedCloudModelId(CloudLlmProvider.GEMINI, "gemini-2.0-flash"))
    assertEquals("gpt-6-luna", migrateDeprecatedCloudModelId(CloudLlmProvider.OPENAI, "gpt-4o-mini"))
    assertEquals("gpt-6-luna", migrateDeprecatedCloudModelId(CloudLlmProvider.OPENAI, "gpt-5-mini"))
    assertEquals("gpt-6-luna", migrateDeprecatedCloudModelId(CloudLlmProvider.OPENAI, "o4-mini"))
    assertEquals("gpt-6.2-sol", migrateDeprecatedCloudModelId(CloudLlmProvider.OPENAI, "gpt-4o"))
    assertEquals("gpt-6.2-sol", migrateDeprecatedCloudModelId(CloudLlmProvider.OPENAI, "gpt-4.1"))
    assertEquals("gpt-6.2-sol", migrateDeprecatedCloudModelId(CloudLlmProvider.OPENAI, "gpt-5"))
    assertEquals("gpt-6.2-sol", migrateDeprecatedCloudModelId(CloudLlmProvider.OPENAI, "o3"))
    assertEquals("claude-haiku-5-5", migrateDeprecatedCloudModelId(CloudLlmProvider.ANTHROPIC, "claude-3-5-haiku-latest"))
    assertEquals("claude-sonnet-5-5", migrateDeprecatedCloudModelId(CloudLlmProvider.ANTHROPIC, "claude-3-7-sonnet-latest"))
    assertEquals("google/gemini-4-neon", migrateDeprecatedCloudModelId(CloudLlmProvider.OPENROUTER, "google/gemini-2.5-flash"))
    assertEquals("openai/gpt-6-luna", migrateDeprecatedCloudModelId(CloudLlmProvider.OPENROUTER, "openai/gpt-4o-mini"))
    assertEquals("anthropic/claude-opus-5.5", migrateDeprecatedCloudModelId(CloudLlmProvider.OPENROUTER, "anthropic/claude-3.5-haiku"))

    // Active 2026 presets and custom future non-deprecated slugs must pass through untouched.
    assertFalse(isDeprecatedCloudModelId("gemini-4-neon"))
    assertFalse(isDeprecatedCloudModelId("gpt-6.2-astra"))
    assertFalse(isDeprecatedCloudModelId("claude-opus-5-5"))
    assertEquals("gemini-4-experimental-next", migrateDeprecatedCloudModelId(CloudLlmProvider.GEMINI, "gemini-4-experimental-next"))

    // 2. Persisted legacy configs hydrated into InMemoryCloudCredentialVault upgrade seamlessly.
    val migratedGeminiVault = InMemoryCloudCredentialVault(
      CloudLlmConfig(AiExecutionMode.CLOUD_API, CloudLlmProvider.GEMINI, "gemini-2.5-flash"),
    )
    assertEquals("gemini-3.5-flash-lite", migratedGeminiVault.configFlow.value.modelId)

    val migratedOpenAiVault = InMemoryCloudCredentialVault(
      CloudLlmConfig(AiExecutionMode.CLOUD_API, CloudLlmProvider.OPENAI, "gpt-4o-mini"),
    )
    assertEquals("gpt-6-luna", migratedOpenAiVault.configFlow.value.modelId)

    val migratedClaudeVault = InMemoryCloudCredentialVault(
      CloudLlmConfig(AiExecutionMode.CLOUD_API, CloudLlmProvider.ANTHROPIC, "claude-3-5-haiku-latest"),
    )
    assertEquals("claude-haiku-5-5", migratedClaudeVault.configFlow.value.modelId)
  }

  /**
   * Verifies hermetic [CloudModelCatalogFetcher] `GET /models` parsing, header-only auth,
   * deprecated/modality exclusion, preset-first merging (`<= 16` chips), and SSRF rejection.
   */
  @Test
  fun cloudModelCatalogFetcher_parsesLiveModelsExcludesDeprecatedAndEnforcesHeaderOnlyAuth() = runTest {
    val capturedGets = mutableListOf<CapturedGetCall>()
    val getTransport = CloudHttpGetTransport { url, headers ->
      capturedGets.add(CapturedGetCall(url = url, headers = headers))
      // Simulate realistic live /models payloads containing both active 2026 and retired/non-chat IDs.
      val body = when {
        url.contains("generativelanguage.googleapis.com") -> """
          {"models":[
            {"name":"models/gemini-4.1-pro-exp","supportedGenerationMethods":["generateContent","countTokens"]},
            {"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-3.7-flash","supportedGenerationMethods":["generateContent"]},
            {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]},
            {"name":"models/imagen-3.0-generate-002","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-tts-preview","supportedGenerationMethods":["generateContent"]},
            {"name":"models/aqa","supportedGenerationMethods":["generateAnswer"]}
          ]}
        """.trimIndent()
        url.contains("api.openai.com") -> """
          {"object":"list","data":[
            {"id":"gpt-6.3-nova"},{"id":"gpt-4o-mini"},{"id":"gpt-5"},{"id":"o3-mini"},
            {"id":"whisper-1"},{"id":"dall-e-3"},{"id":"text-embedding-3-large"},
            {"id":"gpt-6-realtime-preview"},{"id":"omni-moderation-latest"}
          ]}
        """.trimIndent()
        url.contains("api.anthropic.com") -> """
          {"data":[
            {"id":"claude-opus-5-6"},{"id":"claude-3-5-haiku-latest"},
            {"id":"claude-3-7-sonnet-latest"},{"id":"claude-2.1"}
          ]}
        """.trimIndent()
        else -> {
          val extraModels = (1..20).joinToString(",") { idx -> """{"id":"google/gemini-4-live-$idx"}""" }
          """{"data":[{"id":"google/gemini-2.5-flash"},{"id":"openai/gpt-4o-mini"},{"id":"anthropic/claude-3.5-haiku"},{"id":"meta-llama/llama-4-maverick"},$extraModels]}"""
        }
      }
      CloudHttpResponse(statusCode = 200, body = body)
    }

    val fetcher = CloudModelCatalogFetcher(transport = getTransport)

    // 1. GEMINI GET /v1beta/models uses x-goog-api-key header (zero ?key= query parameters).
    val geminiKey = "AIzaSyLiveCatalogSyncKey123456"
    val geminiSynced = fetcher.fetchAvailableModels(CloudLlmProvider.GEMINI, geminiKey)
    val geminiGet = capturedGets.last()
    assertEquals("https://generativelanguage.googleapis.com/v1beta/models", geminiGet.url)
    assertFalse(geminiGet.url.contains("?"))
    assertEquals(geminiKey, geminiGet.headers["x-goog-api-key"])
    assertEquals(CloudLlmProvider.GEMINI.presetModelIds + "gemini-4.1-pro-exp", geminiSynced)
    assertTrue(geminiSynced.none { it.contains("2.5") || it.contains("embedding") || it.contains("imagen") })

    // 2. OPENAI GET /v1/models uses Authorization: Bearer and drops whisper/dall-e/realtime/4o.
    val openAiKey = "sk-proj-LiveCatalogOpenAiKey123456"
    val openAiSynced = fetcher.fetchAvailableModels(CloudLlmProvider.OPENAI, openAiKey)
    val openAiGet = capturedGets.last()
    assertEquals("https://api.openai.com/v1/models", openAiGet.url)
    assertFalse(openAiGet.url.contains("?"))
    assertEquals("Bearer $openAiKey", openAiGet.headers["Authorization"])
    assertEquals(CloudLlmProvider.OPENAI.presetModelIds + "gpt-6.3-nova", openAiSynced)
    assertTrue(openAiSynced.none { it.contains("whisper") || it.contains("realtime") || it.contains("4o") })

    // 3. ANTHROPIC GET /v1/models uses x-api-key + anthropic-version and drops retired claude-2/3.
    val claudeKey = "sk-ant-api03-LiveCatalogClaudeKey12345"
    val claudeSynced = fetcher.fetchAvailableModels(CloudLlmProvider.ANTHROPIC, claudeKey)
    val claudeGet = capturedGets.last()
    assertEquals("https://api.anthropic.com/v1/models", claudeGet.url)
    assertFalse(claudeGet.url.contains("?"))
    assertEquals(claudeKey, claudeGet.headers["x-api-key"])
    assertEquals("2023-06-01", claudeGet.headers["anthropic-version"])
    assertEquals(CloudLlmProvider.ANTHROPIC.presetModelIds + "claude-opus-5-6", claudeSynced)
    assertTrue(claudeSynced.none { it.startsWith("claude-3") || it.startsWith("claude-2") })

    // 4. OPENROUTER GET /api/v1/models places curated presets first and caps total chips at 16.
    val openRouterKey = "sk-or-v1-LiveCatalogOpenRouterKey12345"
    val openRouterSynced = fetcher.fetchAvailableModels(CloudLlmProvider.OPENROUTER, openRouterKey)
    val openRouterGet = capturedGets.last()
    assertEquals("https://openrouter.ai/api/v1/models", openRouterGet.url)
    assertEquals("Bearer $openRouterKey", openRouterGet.headers["Authorization"])
    assertEquals(16, openRouterSynced.size)
    assertEquals(CloudLlmProvider.OPENROUTER.presetModelIds, openRouterSynced.take(CloudLlmProvider.OPENROUTER.presetModelIds.size))
    assertTrue(openRouterSynced.contains("google/gemini-4-live-1"))
    assertTrue(openRouterSynced.none { it.startsWith("meta-llama/") || it.contains("2.5") })

    // 5. SSRF & ?key= query rejection on getValidatedJson executes zero extra transport calls.
    val getCountBeforeSsrf = capturedGets.size
    assertThrowsValidation { fetcher.getValidatedJson("https://generativelanguage.googleapis.com/v1beta/models?key=123", emptyMap()) }
    assertThrowsValidation { fetcher.getValidatedJson("https://evil.example.com/v1/models", emptyMap()) }
    assertEquals(getCountBeforeSsrf, capturedGets.size)
  }

  /**
   * Verifies full Cloud API key lifecycle, [AiChefAction.RefreshCloudModels] live sync, `401`
   * toast redaction, and zero raw-secret leakage in [KitchenBoardViewModel].
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun kitchenBoardViewModel_managesCloudKeyLifecycleSyncsLiveModelsAndNeverLeaksSecrets() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))

    var verifyGate: CompletableDeferred<CloudHttpResponse>? = null
    val recordedVerifyCalls = mutableListOf<CapturedHttpCall>()
    val postTransport = CloudHttpTransport { url, headers, bodyJson ->
      recordedVerifyCalls.add(CapturedHttpCall(url = url, headers = headers, bodyJson = bodyJson))
      val activeGate = verifyGate
      if (activeGate != null) activeGate.await() else CloudHttpResponse(200, """{"candidates":[{"content":{"parts":[{"text":"OK"}]}}]}""")
    }

    var catalogGate: CompletableDeferred<CloudHttpResponse>? = null
    val getTransport = CloudHttpGetTransport { _, _ ->
      val activeGate = catalogGate
      if (activeGate != null) activeGate.await() else CloudHttpResponse(200, """{"models":[]}""")
    }

    val storage = FakeKitchenStorage()
    val vault = InMemoryCloudCredentialVault()
    val router = HybridAiInferenceRouter(
      onDevicePort = StubOnDeviceAiPort(AiRuntimeState.Error("Offline mode")),
      vaultPort = vault,
      cloudClient = CloudLlmClient(transport = postTransport),
      catalogFetcher = CloudModelCatalogFetcher(transport = getTransport),
    )

    try {
      val viewModel = KitchenBoardViewModel(
        seedCatalogPort = SeedCatalogPort { seedDataset },
        storagePort = storage,
        aiEdgePort = router,
        voiceInputPort = SilentVoiceInputPort(),
        cloudVaultPort = vault,
        deleteTempImage = { path -> if (path != null) File(path).delete() },
      )

      // Cold start defaults to ON_DEVICE with zero stored keys.
      assertEquals(AiExecutionMode.ON_DEVICE, viewModel.uiState.value.cloudLlmConfig.executionMode)
      assertFalse(viewModel.uiState.value.cloudLlmConfig.hasStoredApiKey)

      // 1. SaveCloudApiKey with gemini-4-neon switches to CLOUD_API and runs 16-token health check.
      val rawVaultSecret = "AIzaSySuperPrivateHouseholdKey7744"
      viewModel.onAiChefAction(AiChefAction.SaveCloudApiKey(CloudLlmProvider.GEMINI, "  $rawVaultSecret  ", "gemini-4-neon", true))
      val savedCfg = viewModel.uiState.value.cloudLlmConfig
      assertEquals(AiExecutionMode.CLOUD_API, savedCfg.executionMode)
      assertEquals("gemini-4-neon", savedCfg.modelId)
      assertEquals("•••• 7744", savedCfg.maskedKeyPreview)
      assertEquals(1, recordedVerifyCalls.size)

      // 2. RefreshCloudModels toggles isRefreshingCloudModels and retains synced availableModelIds!
      val pendingCatalog = CompletableDeferred<CloudHttpResponse>()
      catalogGate = pendingCatalog
      viewModel.onAiChefAction(AiChefAction.RefreshCloudModels)
      assertTrue(viewModel.uiState.value.isRefreshingCloudModels)
      assertTrue(viewModel.uiState.value.isAnyBackgroundWorkActive)

      pendingCatalog.complete(
        CloudHttpResponse(
          statusCode = 200,
          body = """{"models":[{"name":"models/gemini-4.2-ultra-preview","supportedGenerationMethods":["generateContent"]},{"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent"]}]}""",
        ),
      )
      catalogGate = null
      assertFalse(viewModel.uiState.value.isRefreshingCloudModels)
      val syncedList = viewModel.uiState.value.cloudLlmConfig.availableModelIds
      assertTrue("gemini-4.2-ultra-preview" in syncedList)
      assertFalse("gemini-2.5-flash" in syncedList)

      // Tapping the newly synced chip updates modelId without wiping live availableModelIds.
      viewModel.onAiChefAction(AiChefAction.UpdateCloudModelId("gemini-4.2-ultra-preview"))
      assertEquals("gemini-4.2-ultra-preview", viewModel.uiState.value.cloudLlmConfig.modelId)
      assertTrue("gemini-4.2-ultra-preview" in viewModel.uiState.value.cloudLlmConfig.availableModelIds)

      // 3. Commit a household snapshot and verify serialized JSON contains zero raw API key substrings.
      viewModel.toggleManualGroceryQueue("ing_olive_oil")
      val serializedSnapshotJson = strictJson.encodeToString(KitchenSnapshot.serializer(), requireNotNull(storage.snapshotFlow.value))
      assertFalse(serializedSnapshotJson.contains(rawVaultSecret))
      assertFalse(viewModel.uiState.value.toString().contains(rawVaultSecret))

      // 4. VerifyCloudConnection scrubs echoed 401 secrets from UI toast diagnostics.
      val pendingCheck = CompletableDeferred<CloudHttpResponse>()
      verifyGate = pendingCheck
      viewModel.onAiChefAction(AiChefAction.VerifyCloudConnection)
      assertTrue(viewModel.uiState.value.isVerifyingCloudKey)
      pendingCheck.complete(CloudHttpResponse(401, """{"error":{"message":"Key $rawVaultSecret was revoked"}}"""))
      verifyGate = null
      assertFalse(viewModel.uiState.value.isVerifyingCloudKey)
      val redactedToast = requireNotNull(viewModel.uiState.value.toastMessage)
      assertFalse(redactedToast.contains(rawVaultSecret))
      assertTrue(redactedToast.contains("Authentication failed (401)"))

      // 5. SelectCloudProvider defaults ANTHROPIC to claude-sonnet-5-5; ClearCloudApiKey purges vault.
      viewModel.onAiChefAction(AiChefAction.SelectCloudProvider(CloudLlmProvider.ANTHROPIC))
      assertEquals("claude-sonnet-5-5", viewModel.uiState.value.cloudLlmConfig.modelId)
      viewModel.onAiChefAction(AiChefAction.SelectCloudProvider(CloudLlmProvider.GEMINI))
      viewModel.onAiChefAction(AiChefAction.ClearCloudApiKey(CloudLlmProvider.GEMINI))
      assertFalse(viewModel.uiState.value.cloudLlmConfig.hasStoredApiKey)
      assertNull(vault.readApiKey(CloudLlmProvider.GEMINI))
      assertTrue(viewModel.uiState.value.aiRuntimeState is AiRuntimeState.Error)
    } finally {
      router.close()
      Dispatchers.resetMain()
    }
  }

  // Immutable capture record for hermetic socket-free CloudHttpGetTransport verification.
  private data class CapturedGetCall(
    val url: String,
    val headers: Map<String, String>,
  )
}
