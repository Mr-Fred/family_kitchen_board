package com.familykitchen.board.ai

import com.familykitchen.board.data.InMemoryCloudCredentialVault
import com.familykitchen.board.domain.AiExecutionMode
import com.familykitchen.board.domain.BaseUnit
import com.familykitchen.board.domain.BomLine
import com.familykitchen.board.domain.CloudLlmProvider
import com.familykitchen.board.domain.CookOwner
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.IngredientCategory
import com.familykitchen.board.domain.IngredientTier
import com.familykitchen.board.domain.MealSlotType
import com.familykitchen.board.domain.ModelNotReadyException
import com.familykitchen.board.domain.MorningStep
import com.familykitchen.board.domain.PantryLedgerEntry
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.SeedCatalogDataset
import com.familykitchen.board.domain.StapleStatus
import com.familykitchen.board.domain.ValidationException
import com.familykitchen.board.domain.VisionNotSupportedException
import com.familykitchen.board.domain.isDeprecatedCloudModelId
import com.familykitchen.board.domain.redactApiSecrets
import com.familykitchen.board.ports.AiEdgePort
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ports.KitchenSnapshot
import com.familykitchen.board.ports.RuntimeCapabilities
import com.familykitchen.board.ports.VoiceInputPort
import com.familykitchen.board.ports.VoiceInputState
import java.io.File
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// 100% hermetic, zero-socket JUnit 4 suite verifying CloudLlmClient wire formats, thinking-block
// filters, HybridAiInferenceRouter multimodal vision routing, and SSRF / secret-redaction guards.

/**
 * Hermetic JUnit 4 verification suite for [CloudLlmClient], [HybridAiInferenceRouter], and
 * [AiChefCoordinator] covering:
 * 1. Active late-2026 default and preset model IDs (`gemini-3.8-flash`, `gpt-6.2-sol` with
 *    `max_completion_tokens`, `claude-sonnet-5-5`, `google/gemini-4-neon`) and header-only auth,
 * 2. Gemini 3.8 / Gemini 4 `{"thought": true}` part filtering, Anthropic Claude 5.x
 *    `{"type": "thinking"}` block filtering, and offline `ON_DEVICE` isolation,
 * 3. Secret redaction on `401` diagnostics plus SSRF and path-traversal (`../../evil`) rejection.
 */
class CloudLlmAndHybridRouterTest {

  /** Temporary directory hosting simulated preprocessed JPEG kitchen captures. */
  @get:Rule
  val tempFolder: TemporaryFolder = TemporaryFolder()

  private val strictJson = Json { encodeDefaults = true }

  // Minimal household catalog fixture covering Tier-1 brain-boosters, grains, and Tier-2 olive oil.
  private val testCatalog: List<IngredientCatalogItem> = listOf(
    IngredientCatalogItem("ing_egg", "Large Farm Eggs", IngredientCategory.DAIRY_EGGS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.COUNT, 12.0, allergens = listOf("EGGS")),
    IngredientCatalogItem("ing_oats", "Rolled Whole Grain Oats", IngredientCategory.BAKERY_GRAINS, IngredientTier.TIER_1_QUANTITATIVE, BaseUnit.GRAM, 1000.0),
    IngredientCatalogItem("ing_olive_oil", "Extra-Virgin Olive Oil", IngredientCategory.PANTRY_STAPLE, IngredientTier.TIER_2_STAPLE, BaseUnit.MILLILITER, 750.0),
  )

  // Stocked baseline pantry so pre-confirmation vs post-confirmation mutations are crystal clear.
  private val testInitialPantry: List<PantryLedgerEntry> = listOf(
    PantryLedgerEntry("ing_egg", IngredientTier.TIER_1_QUANTITATIVE, 6.0),
    PantryLedgerEntry("ing_oats", IngredientTier.TIER_1_QUANTITATIVE, 500.0),
    PantryLedgerEntry("ing_olive_oil", IngredientTier.TIER_2_STAPLE, 200.0, StapleStatus.LOW),
  )

  // Fast 15m baseline breakfast recipe respecting EC-01 (<= 25m morning cap).
  private val quickOatEggBowl = Recipe(
    recipeId = "rec_oat_egg_bowl", title = "Savory Oat & Soft Egg Bowl",
    sourceAttribution = "Household Test Kitchen",
    allowedSlots = listOf(MealSlotType.BREAKFAST, MealSlotType.LUNCH, MealSlotType.DINNER),
    preferredOwner = CookOwner.FRED, rawPrepMinutes = 5, cookMinutes = 8, calibratedActiveMinutes = 15,
    morningSteps = listOf(MorningStep(1, "Simmer oats and poach eggs.", 480)),
    toddlerPrepTip = "Serve warm soft oats and mashed yolk for 2yo.",
    bomLines = listOf(BomLine("ing_egg", 4.0), BomLine("ing_oats", 200.0), BomLine("ing_olive_oil", 10.0)),
  )

  // Secondary 12m choline scramble target for grounded AI swap proposals.
  private val savoryScramble = Recipe(
    recipeId = "rec_savory_scramble", title = "Olive Oil Soft Scramble",
    sourceAttribution = "Household Test Kitchen",
    allowedSlots = listOf(MealSlotType.BREAKFAST, MealSlotType.LUNCH, MealSlotType.DINNER),
    preferredOwner = CookOwner.WIFE, rawPrepMinutes = 4, cookMinutes = 6, calibratedActiveMinutes = 12,
    morningSteps = listOf(MorningStep(1, "Whisk and gently fold eggs in olive oil.", 360)),
    toddlerPrepTip = "Cut soft egg curds into bite-sized ribbons for 2yo.",
    bomLines = listOf(BomLine("ing_egg", 4.0), BomLine("ing_olive_oil", 10.0)),
  )

  // Canonical seed dataset shared across router and coordinator turns in this test suite.
  private val baselineSnapshot: KitchenSnapshot = KitchenSnapshot.fromSeedCatalog(
    SeedCatalogDataset(testCatalog, listOf(quickOatEggBowl, savoryScramble), testInitialPantry),
  )

  /**
   * Verifies active 2026 model lineups, header-only auth, zero `?key=` URL params, and OpenAI
   * `max_completion_tokens` (omitting `max_tokens` and `temperature`) across all 4 providers.
   */
  @Test
  fun allFourCloudProviders_useActive2026ModelsHeaderOnlyAuthAndModernWirePayloads() = runTest {
    // Confirm exact active late-2026 default and preset lineups across all 4 cloud providers.
    assertEquals("gemini-3.8-flash", CloudLlmProvider.GEMINI.defaultModelId)
    assertEquals(
      listOf("gemini-3.8-flash", "gemini-4-neon", "gemini-4-carbon", "gemini-4-argon", "gemini-4-hydrogen", "gemini-3.5-flash-lite"),
      CloudLlmProvider.GEMINI.presetModelIds,
    )
    assertEquals("gpt-6.2-sol", CloudLlmProvider.OPENAI.defaultModelId)
    assertEquals(listOf("gpt-6.2-sol", "gpt-6.2-astra", "gpt-6.1-terra", "gpt-6-luna"), CloudLlmProvider.OPENAI.presetModelIds)
    assertEquals("claude-sonnet-5-5", CloudLlmProvider.ANTHROPIC.defaultModelId)
    assertEquals(listOf("claude-sonnet-5-5", "claude-haiku-5-5", "claude-opus-5-5", "claude-fable-5-2"), CloudLlmProvider.ANTHROPIC.presetModelIds)
    assertEquals("google/gemini-4-neon", CloudLlmProvider.OPENROUTER.defaultModelId)
    assertEquals(
      listOf("google/gemini-4-neon", "google/gemini-4-hydrogen", "openai/gpt-6.2-astra", "openai/gpt-6-luna", "anthropic/claude-opus-5.5", "anthropic/claude-fable-5.2"),
      CloudLlmProvider.OPENROUTER.presetModelIds,
    )
    // Guarantee zero deprecated model IDs exist anywhere in active defaults or presets.
    for (provider in CloudLlmProvider.entries) {
      assertFalse(isDeprecatedCloudModelId(provider.defaultModelId))
      assertTrue(provider.presetModelIds.none(::isDeprecatedCloudModelId))
    }

    val capturedCalls = mutableListOf<CapturedHttpCall>()
    val transport = CloudHttpTransport { url, headers, bodyJson ->
      capturedCalls.add(CapturedHttpCall(url = url, headers = headers, bodyJson = bodyJson))
      // Return each provider's canonical JSON envelope based on the validated target hostname.
      val responseJson = when {
        url.contains("generativelanguage.googleapis.com") -> """{"candidates":[{"content":{"parts":[{"text":"GEMINI_OK"}]}}]}"""
        url.contains("api.anthropic.com") -> """{"content":[{"type":"text","text":"ANTHROPIC_OK"}]}"""
        else -> """{"choices":[{"message":{"content":"OPENAI_COMPAT_OK"}}]}"""
      }
      CloudHttpResponse(statusCode = 200, body = responseJson)
    }
    val client = CloudLlmClient(transport = transport)
    val samplePhoto = createTestJpeg("provider_matrix_photo.jpg")
    val expectedB64 = Base64.getEncoder().encodeToString(samplePhoto.readBytes())

    // 1. GEMINI: x-goog-api-key header, strict https v1beta URL, zero ?key= query string.
    val geminiSecret = "AIzaSyGeminiHeaderOnlyTestKey123456"
    assertEquals("GEMINI_OK", client.generateText(CloudLlmProvider.GEMINI, CloudLlmProvider.GEMINI.defaultModelId, geminiSecret, "Check pantry status"))
    val geminiCall = capturedCalls.last()
    assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent", geminiCall.url)
    assertFalse(geminiCall.url.contains("?"))
    assertFalse(geminiCall.url.contains(geminiSecret))
    assertEquals(geminiSecret, geminiCall.headers["x-goog-api-key"])
    assertNull(geminiCall.headers["Authorization"])
    assertEquals("no-store", geminiCall.headers["Cache-Control"])

    // 2. OPENAI: Authorization Bearer + max_completion_tokens (zero max_tokens, zero temperature).
    val openAiSecret = "sk-proj-OpenAiBearerHeaderKey123456789"
    assertEquals(
      "OPENAI_COMPAT_OK",
      client.analyzeImageWithPrompt(CloudLlmProvider.OPENAI, CloudLlmProvider.OPENAI.defaultModelId, openAiSecret, samplePhoto.absolutePath, "Inspect counter", 1024),
    )
    val openAiCall = capturedCalls.last()
    assertEquals("https://api.openai.com/v1/chat/completions", openAiCall.url)
    assertFalse(openAiCall.url.contains(openAiSecret))
    assertEquals("Bearer $openAiSecret", openAiCall.headers["Authorization"])
    assertTrue(openAiCall.bodyJson.contains("data:image/jpeg;base64,$expectedB64"))
    val openAiBody = strictJson.parseToJsonElement(openAiCall.bodyJson).jsonObject
    // Verify OpenAI GPT-6 wire payload uses max_completion_tokens and omits temperature.
    assertEquals("gpt-6.2-sol", openAiBody["model"]?.jsonPrimitive?.content)
    assertEquals(1024, openAiBody["max_completion_tokens"]?.jsonPrimitive?.int)
    assertFalse("max_tokens" in openAiBody)
    assertFalse("temperature" in openAiBody)

    // 3. OPENROUTER: Authorization Bearer + retains max_tokens & temperature for gateway compat.
    val openRouterSecret = "sk-or-v1-OpenRouterBearerKey987654321"
    assertEquals("OPENAI_COMPAT_OK", client.generateText(CloudLlmProvider.OPENROUTER, CloudLlmProvider.OPENROUTER.defaultModelId, openRouterSecret, "Draft breakfast", 768))
    val openRouterCall = capturedCalls.last()
    assertEquals("https://openrouter.ai/api/v1/chat/completions", openRouterCall.url)
    assertEquals("Bearer $openRouterSecret", openRouterCall.headers["Authorization"])
    val openRouterBody = strictJson.parseToJsonElement(openRouterCall.bodyJson).jsonObject
    assertEquals("google/gemini-4-neon", openRouterBody["model"]?.jsonPrimitive?.content)
    assertEquals(768, openRouterBody["max_tokens"]?.jsonPrimitive?.int)
    assertTrue("temperature" in openRouterBody)
    assertFalse("max_completion_tokens" in openRouterBody)

    // 4. ANTHROPIC: x-api-key + anthropic-version headers + claude-sonnet-5-5 + base64 image block.
    val anthropicSecret = "sk-ant-api03-AnthropicVaultSecretKey12345"
    assertEquals(
      "ANTHROPIC_OK",
      client.analyzeImageWithPrompt(CloudLlmProvider.ANTHROPIC, CloudLlmProvider.ANTHROPIC.defaultModelId, anthropicSecret, samplePhoto.absolutePath, "Inspect spice jars"),
    )
    val anthropicCall = capturedCalls.last()
    assertEquals("https://api.anthropic.com/v1/messages", anthropicCall.url)
    assertEquals(anthropicSecret, anthropicCall.headers["x-api-key"])
    assertEquals("2023-06-01", anthropicCall.headers["anthropic-version"])
    val anthropicBody = strictJson.parseToJsonElement(anthropicCall.bodyJson).jsonObject
    assertEquals("claude-sonnet-5-5", anthropicBody["model"]?.jsonPrimitive?.content)
    val imageSource = anthropicBody["messages"]?.jsonArray?.first()?.jsonObject?.get("content")?.jsonArray?.first()?.jsonObject?.get("source")?.jsonObject
    assertEquals("base64", imageSource?.get("type")?.jsonPrimitive?.content)
    assertEquals("image/jpeg", imageSource?.get("media_type")?.jsonPrimitive?.content)
    assertEquals(expectedB64, imageSource?.get("data")?.jsonPrimitive?.content)
  }

  /**
   * Verifies Gemini `{"thought": true}` and Claude `{"type": "thinking"}` filtering plus end-to-end
   * multimodal `spice_rack.jpg` grounding and `ON_DEVICE` zero-network isolation.
   */
  @Test
  fun hybridRouterAndThinkingFilters_stripGeminiAndClaudeThoughtsAndIsolateOnDeviceMode() = runTest {
    // 1. Direct unit checks for Gemini thought-part filtering + fallback & Claude thinking blocks.
    var nextStubBody = ""
    val directClient = CloudLlmClient(CloudHttpTransport { _, _, _ -> CloudHttpResponse(200, nextStubBody) })

    // Gemini 3.8 / 4 emits {"thought": true} ahead of the JSON completion part.
    nextStubBody = """{"candidates":[{"content":{"parts":[{"thought":true,"text":"Chain of thought..."},{"text":"{\"status\":\"FINAL_GEMINI_JSON\"}"}]}}]}"""
    assertEquals("""{"status":"FINAL_GEMINI_JSON"}""", directClient.generateText(CloudLlmProvider.GEMINI, "gemini-4-neon", "AIzaSyTestKey12345678", "Test"))

    // Fallback preserves text when every emitted Gemini part carried thought == true.
    nextStubBody = """{"candidates":[{"content":{"parts":[{"thought":true,"text":"ONLY_THOUGHT_FALLBACK"}]}}]}"""
    assertEquals("ONLY_THOUGHT_FALLBACK", directClient.generateText(CloudLlmProvider.GEMINI, "gemini-4-neon", "AIzaSyTestKey12345678", "Test"))

    // Claude 5.x adaptive thinking + redacted_thinking blocks are skipped in favor of type == "text".
    nextStubBody = """{"content":[{"type":"thinking","thinking":"Reasoning...","text":"DECOY"},{"type":"redacted_thinking","data":"enc"},{"type":"text","text":"{\"status\":\"FINAL_CLAUDE_JSON\"}"}]}"""
    assertEquals("""{"status":"FINAL_CLAUDE_JSON"}""", directClient.generateText(CloudLlmProvider.ANTHROPIC, "claude-sonnet-5-5", "sk-ant-api03-TestKey12345", "Test"))

    // 2. End-to-end HybridAiInferenceRouter + AiChefCoordinator with Gemini 3.8 thinking parts.
    val capturedCloudCalls = mutableListOf<CapturedHttpCall>()
    val targetBreakfastSlot = baselineSnapshot.weeklyBoard.slots.first { it.slotType == MealSlotType.BREAKFAST }

    val stubCloudTransport = CloudHttpTransport { url, headers, bodyJson ->
      capturedCloudCalls.add(CapturedHttpCall(url = url, headers = headers, bodyJson = bodyJson))
      val firstParts = strictJson.parseToJsonElement(bodyJson).jsonObject["contents"]
        ?.jsonArray?.first()?.jsonObject?.get("parts")?.jsonArray.orEmpty()
      val hasInlineImage = firstParts.any { "inline_data" in it.jsonObject }

      // Emit leading {"thought": true} part ahead of fenced JSON to prove end-to-end JSON grounding.
      val modelText = when {
        hasInlineImage -> """
          ```json
          {"summary":"Spice rack jars with ground turmeric, bay leaves, parsley flakes, and oregano.","detectedItems":[{"rawLabel":"ground turmeric","matchedIngredientId":null,"estimatedQuantity":0,"confidence":0.97},{"rawLabel":"bay leaves","matchedIngredientId":null,"estimatedQuantity":0,"confidence":0.95},{"rawLabel":"flakes of parsley","matchedIngredientId":null,"estimatedQuantity":0,"confidence":0.93},{"rawLabel":"oregano","matchedIngredientId":null,"estimatedQuantity":0,"confidence":0.94}],"unrecognizedLabels":["Ceramic Coffee Mug"]}
          ```
        """.trimIndent()
        else -> """
          ```json
          {"focusTag":"KIDS_BRAIN_BOOSTER","assistantSummary":"Swapped breakfast to Olive Oil Soft Scramble.","suggestedSwaps":[{"slotId":"${targetBreakfastSlot.slotId}","recipeId":"rec_savory_scramble","rationale":"Fast choline."}]}
          ```
        """.trimIndent()
      }
      val escapedText = strictJson.encodeToString(String.serializer(), modelText)
      CloudHttpResponse(200, """{"candidates":[{"content":{"parts":[{"thought":true,"text":"Thinking..."},{"text":$escapedText}]}}]}""")
    }

    val onDevicePort = StubOnDeviceAiPort(AiRuntimeState.Error("No local .litertlm model imported"))
    val vault = InMemoryCloudCredentialVault()
    val router = HybridAiInferenceRouter(onDevicePort, vault, CloudLlmClient(stubCloudTransport))

    try {
      // Cold default mirrors ON_DEVICE offline state with zero cloud calls.
      assertTrue(router.runtimeState.value is AiRuntimeState.Error)
      assertEquals(0, capturedCloudCalls.size)

      // Save Gemini API key and switch to CLOUD_API -> Ready("Cloud · Gemini", "gemini-3.8-flash").
      vault.saveApiKey(CloudLlmProvider.GEMINI, "AIzaSyHybridRouterTestKey987654321")
      vault.setExecutionMode(AiExecutionMode.CLOUD_API)
      assertEquals(
        AiRuntimeState.Ready(RuntimeCapabilities(true, true, "Cloud · Gemini", "gemini-3.8-flash")),
        router.runtimeState.value,
      )

      // Submit spice_rack.jpg and verify Base64 inline_data, temp deletion, and 4 grounded spices.
      val spiceBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x53, 0x50, 0xFF.toByte(), 0xD9.toByte())
      val spiceRackFile = tempFolder.newFile("spice_rack.jpg").apply { writeBytes(spiceBytes) }
      val expectedSpiceBase64 = Base64.getEncoder().encodeToString(spiceBytes)
      val coordinator = AiChefCoordinator(aiEdgePort = router)

      val visionOutcome = coordinator.submitTurn(baselineSnapshot, "Inspect spice rack", spiceRackFile.absolutePath, "spice_rack.jpg")
      assertEquals(1, capturedCloudCalls.size)
      assertEquals(0, onDevicePort.analyzeImageCallCount)
      val inlineDataObj = strictJson.parseToJsonElement(capturedCloudCalls.single().bodyJson).jsonObject["contents"]
        ?.jsonArray?.single()?.jsonObject?.get("parts")?.jsonArray?.first()?.jsonObject?.get("inline_data")?.jsonObject
      assertEquals(expectedSpiceBase64, inlineDataObj?.get("data")?.jsonPrimitive?.content)

      assertEquals(AiTurnResolutionStatus.MULTIMODAL_VISION_GROUNDED, visionOutcome.status)
      assertFalse(spiceRackFile.exists())
      assertEquals(baselineSnapshot.pantryEntries, visionOutcome.updatedSnapshot.pantryEntries)

      val expectedSpiceIds = setOf("ing_custom_ground_turmeric", "ing_custom_bay_leaves", "ing_custom_parsley_flakes", "ing_custom_dried_oregano")
      val intake = requireNotNull(visionOutcome.assistantTurn.visualIntakeResult)
      assertEquals(expectedSpiceIds, intake.proposedNewCatalogItems.map { it.ingredientId }.toSet())
      val stockedSnapshot = coordinator.applyConfirmedVisualRestock(visionOutcome.updatedSnapshot, intake)
      assertTrue(expectedSpiceIds.all { stockedSnapshot.pantryById[it]?.stapleStatus == StapleStatus.PLENTY })

      // Verify CLOUD_API text turn routes cleanly before flipping back to ON_DEVICE.
      val cloudTextOutcome = coordinator.submitTurn(stockedSnapshot, "Suggest brain boosters for our kids")
      assertEquals(AiTurnResolutionStatus.ON_DEVICE_TEXT_GROUNDED, cloudTextOutcome.status)
      assertEquals(2, capturedCloudCalls.size)

      // Switch back to ON_DEVICE: cloudCallCount MUST remain frozen at 2 while onDevicePort runs!
      val localReady = AiRuntimeState.Ready(RuntimeCapabilities(true, true, "LiteRT-LM GPU + Vision", "gemma-e2b-it-multimodal-int4.litertlm"))
      onDevicePort.setRuntimeState(localReady)
      onDevicePort.textResponder = { """{"focusTag":"PANTRY_ZERO_WASTE","assistantSummary":"Local ready.","suggestedSwaps":[]}""" }
      onDevicePort.visionResponder = { _, _ -> """{"summary":"Eggs.","detectedItems":[{"rawLabel":"Eggs","matchedIngredientId":"ing_egg","estimatedQuantity":12,"confidence":0.98}]}""" }

      vault.setExecutionMode(AiExecutionMode.ON_DEVICE)
      assertEquals(localReady, router.runtimeState.value)
      val offlineText = coordinator.submitTurn(stockedSnapshot, "Optimize perishables offline")
      val offlineVision = coordinator.submitTurn(offlineText.updatedSnapshot, "Inspect egg carton", createTestJpeg("offline_eggs.jpg").absolutePath)
      assertEquals(AiTurnResolutionStatus.MULTIMODAL_VISION_GROUNDED, offlineVision.status)
      assertEquals(2, capturedCloudCalls.size)
      assertEquals(1, onDevicePort.generateTextCallCount)
      assertEquals(1, onDevicePort.analyzeImageCallCount)
    } finally {
      router.close()
      assertTrue(onDevicePort.closed)
    }
  }

  /**
   * Verifies `401` secret redaction, path-traversal/query-injection `modelId` rejection, SSRF host
   * allowlist enforcement, CRLF header-injection blocking, and missing-key gates.
   */
  @Test
  fun securityHardening_redacts401EchoedKeysAndBlocksSsrfPathTraversalAndHeaderInjection() = runTest {
    val leakedGeminiKey = "AIzaSySecretKey1234567890"
    var transportInvocations = 0
    val client = CloudLlmClient { _, _, _ ->
      transportInvocations += 1
      CloudHttpResponse(401, """{"error":{"message":"Invalid key $leakedGeminiKey provided in header"}}""")
    }

    // 1. Simulated 401 echoing raw API key in upstream error body is scrubbed completely.
    val authError = try {
      client.generateText(CloudLlmProvider.GEMINI, "gemini-3.8-flash", leakedGeminiKey, "Reply with OK")
      throw AssertionError("Expected CloudApiException on HTTP 401")
    } catch (expected: CloudApiException) {
      expected
    }
    assertEquals(401, authError.statusCode)
    assertFalse(authError.message.contains(leakedGeminiKey))
    assertTrue(authError.message.contains("[REDACTED]"))
    assertEquals(1, transportInvocations)

    // Verify direct redaction helper across Gemini, Anthropic, and Bearer credentials.
    val scrubbed = redactApiSecrets("Echoed $leakedGeminiKey and sk-ant-api03-Secret987654321 and Bearer eyJhbGci.token.sig")
    assertFalse(scrubbed.contains(leakedGeminiKey))
    assertFalse(scrubbed.contains("sk-ant-api03-Secret987654321"))
    assertFalse(scrubbed.contains("eyJhbGci.token.sig"))

    // 2. Path-traversal & query-injection modelIds rejected with ValidationException (0 HTTP calls).
    val vault = InMemoryCloudCredentialVault()
    for (malicious in listOf("../../evil", "../v1/files", "gemini-3.8-flash?key=AIzaLeak", "models/../../secret", "bad space")) {
      assertThrowsValidation { vault.setModelId(malicious, CloudLlmProvider.GEMINI) }
      assertThrowsValidation { client.generateText(CloudLlmProvider.GEMINI, malicious, leakedGeminiKey, "Hi") }
    }
    assertThrowsValidation { client.generateText(CloudLlmProvider.GEMINI, "google/gemini-4-neon", leakedGeminiKey, "Hi") }
    assertEquals(1, transportInvocations)

    // 3. SSRF & URL hardening: non-HTTPS, metadata IPs, unknown hosts, custom ports, userinfo, & ?key=.
    val forbiddenUrls = listOf(
      "http://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent",
      "https://169.254.169.254/latest/meta-data/",
      "https://localhost/v1/chat/completions",
      "https://evil-attacker.example.com/v1/chat/completions",
      "https://api.openai.com:8443/v1/chat/completions",
      "https://admin:secret@api.openai.com/v1/chat/completions",
      "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent?key=123",
      "https://api.openai.com/v1/../internal",
    )
    for (badUrl in forbiddenUrls) {
      assertThrowsValidation { CloudLlmClient.validateAllowedCloudEndpoint(badUrl) }
      assertThrowsValidation { client.postValidatedJson(badUrl, emptyMap(), "{}") }
    }
    assertEquals(1, transportInvocations)

    // 4. Header-injection CRLF keys, 0-byte images, and unconfigured CLOUD_API missing-key gate.
    assertThrowsValidation { vault.saveApiKey(CloudLlmProvider.OPENAI, "sk-validPrefix\r\nX-Evil: injected") }
    val emptyJpeg = tempFolder.newFile("zero_byte.jpg")
    assertThrowsValidation { client.analyzeImageWithPrompt(CloudLlmProvider.GEMINI, "gemini-3.8-flash", leakedGeminiKey, emptyJpeg.absolutePath, "Inspect") }

    vault.setExecutionMode(AiExecutionMode.CLOUD_API)
    val unconfiguredRouter = HybridAiInferenceRouter(StubOnDeviceAiPort(AiRuntimeState.Uninitialized), vault, client)
    try {
      assertTrue(unconfiguredRouter.runtimeState.value is AiRuntimeState.Error)
      val missingErr = try {
        unconfiguredRouter.generateText("Plan dinner")
        throw AssertionError("Expected ModelNotReadyException")
      } catch (expected: ModelNotReadyException) {
        expected
      }
      assertEquals(HybridAiInferenceRouter.STATUS_CLOUD_API_KEY_MISSING, missingErr.runtimeStatus)
    } finally {
      unconfiguredRouter.close()
    }
  }

  private fun createTestJpeg(fileName: String): File =
    tempFolder.newFile(fileName).apply {
      writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x11, 0x22, 0xFF.toByte(), 0xD9.toByte()))
    }
}

// Shared hermetic assertion helper verifying ValidationException boundary rejections.
internal suspend fun assertThrowsValidation(block: suspend () -> Unit) {
  try {
    block()
    throw AssertionError("Expected ValidationException to be thrown")
  } catch (expected: ValidationException) {
    assertEquals(ValidationException.ERROR_CODE_VALIDATION, expected.errorCode)
  }
}

// Immutable capture record for hermetic socket-free CloudHttpTransport POST verification.
internal data class CapturedHttpCall(
  val url: String,
  val headers: Map<String, String>,
  val bodyJson: String,
)

// Pure-Kotlin VoiceInputPort test double avoiding Android SpeechRecognizer dependencies.
internal class SilentVoiceInputPort : VoiceInputPort {
  private val mutableVoice = MutableStateFlow<VoiceInputState>(VoiceInputState.Idle)
  override val voiceState: StateFlow<VoiceInputState> = mutableVoice.asStateFlow()
  override fun startListening(languageTag: String): Boolean = false
  override fun stopListening() = Unit
  override fun release() = Unit
}

// Stateful on-device AiEdgePort test double tracking offline isolation and close() lifecycle.
internal class StubOnDeviceAiPort(
  initialState: AiRuntimeState,
  var textResponder: (String) -> String = { "{}" },
  var visionResponder: (String, String) -> String = { _, _ -> "{}" },
) : AiEdgePort {
  private val mutableState = MutableStateFlow(initialState)
  override val runtimeState: StateFlow<AiRuntimeState> = mutableState.asStateFlow()

  var generateTextCallCount: Int = 0
  var analyzeImageCallCount: Int = 0
  var closed: Boolean = false

  fun setRuntimeState(next: AiRuntimeState) {
    mutableState.value = next
  }

  override suspend fun autoDiscoverAndInitialize(): AiRuntimeState = mutableState.value

  override suspend fun importAndLoadModel(sourceUriString: String, displayName: String?): AiRuntimeState =
    mutableState.value

  override suspend fun generateText(prompt: String): String {
    val current = mutableState.value
    if (current !is AiRuntimeState.Ready) {
      throw ModelNotReadyException(runtimeStatus = current::class.simpleName ?: "UNINITIALIZED")
    }
    generateTextCallCount += 1
    return textResponder(prompt)
  }

  override suspend fun analyzeImageWithPrompt(preprocessedImagePath: String, prompt: String): String {
    val current = mutableState.value
    if (current !is AiRuntimeState.Ready) {
      throw ModelNotReadyException(runtimeStatus = current::class.simpleName ?: "UNINITIALIZED")
    }
    if (!current.capabilities.supportsVision) {
      throw VisionNotSupportedException(current.capabilities.backendLabel, current.capabilities.modelName)
    }
    analyzeImageCallCount += 1
    return visionResponder(preprocessedImagePath, prompt)
  }

  override fun close() {
    closed = true
  }
}
