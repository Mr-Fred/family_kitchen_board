package com.familykitchen.board.ai

import com.familykitchen.board.data.FakeKitchenStorage
import com.familykitchen.board.data.InMemoryCloudCredentialVault
import com.familykitchen.board.domain.AiChefAction
import com.familykitchen.board.domain.AiExecutionMode
import com.familykitchen.board.domain.BaseUnit
import com.familykitchen.board.domain.BomLine
import com.familykitchen.board.domain.CookOwner
import com.familykitchen.board.domain.DownloadableGemmaPreset
import com.familykitchen.board.domain.EdgeDeviceProfile
import com.familykitchen.board.domain.IngredientCatalogItem
import com.familykitchen.board.domain.IngredientCategory
import com.familykitchen.board.domain.IngredientTier
import com.familykitchen.board.domain.MealSlotType
import com.familykitchen.board.domain.ModelDownloadApprovalSpec
import com.familykitchen.board.domain.MorningStep
import com.familykitchen.board.domain.NetworkMeteringStatus
import com.familykitchen.board.domain.PantryLedgerEntry
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.SeedCatalogDataset
import com.familykitchen.board.domain.StapleStatus
import com.familykitchen.board.domain.VisionNotSupportedException
import com.familykitchen.board.domain.validateInitialModelDownloadUrl
import com.familykitchen.board.domain.validateModelRedirectUrl
import com.familykitchen.board.ports.AiEdgePort
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ports.ModelFileFormat
import com.familykitchen.board.ports.RuntimeCapabilities
import com.familykitchen.board.ports.SeedCatalogPort
import com.familykitchen.board.ui.KitchenBoardViewModel
import com.familykitchen.board.ui.KitchenModelDownloadDelegate
import com.familykitchen.board.ui.aichef.formatByteProgressLabel
import com.familykitchen.board.ui.aichef.formatMbOrGb
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// 100% hermetic, socket-free JUnit 4 suite verifying Gemma 4 (.litertlm) SSRF/HTTPS redirect
// guards, multi-hop HTTP 206 .part resumption & cancellation cleanup, and ViewModel consent gates.

/**
 * Hermetic JUnit 4 verification suite for [DownloadableGemmaPreset], [ModelDownloadApprovalSpec],
 * [validateInitialModelDownloadUrl], [validateModelRedirectUrl], [OnDeviceModelDownloader],
 * [HybridAiInferenceRouter], and [KitchenBoardViewModel] covering:
 * 1. Strict HTTPS-only, `litert-community` org-prefix, and SSRF / private-IP / traversal blocking
 *    across initial Hugging Face resolve endpoints and Xet/LFS CDN redirect chains,
 * 2. Socket-free `302 -> 307` redirect resolution, interrupted `<fileName>.part` preservation on
 *    transient `IOException`, `HTTP 206 Partial Content` `Range` resumption + atomic promotion,
 *    verified fast-path network skip, and immediate `.part` deletion on `cancelActiveDownload()`,
 * 3. `ModelDownloadApprovalSpec` disk-headroom / metered-cellular / offline rules and end-to-end
 *    `KitchenBoardViewModel` user-consent gating with keyless `CLOUD_API -> ON_DEVICE` activation.
 */
class OnDeviceModelDownloaderTest {

  /** Temporary filesystem root hosting private `filesDir/models` directories per test run. */
  @get:Rule
  val tempFolder: TemporaryFolder = TemporaryFolder()

  private val seedDataset: SeedCatalogDataset = SeedCatalogDataset(
    ingredients = listOf(
      IngredientCatalogItem(
        ingredientId = "ing_egg",
        displayName = "Large Farm Eggs",
        category = IngredientCategory.DAIRY_EGGS,
        tier = IngredientTier.TIER_1_QUANTITATIVE,
        baseUnit = BaseUnit.COUNT,
        defaultPackageQuantity = 12.0,
      ),
      IngredientCatalogItem(
        ingredientId = "ing_olive_oil",
        displayName = "Extra-Virgin Olive Oil",
        category = IngredientCategory.PANTRY_STAPLE,
        tier = IngredientTier.TIER_2_STAPLE,
        baseUnit = BaseUnit.MILLILITER,
        defaultPackageQuantity = 750.0,
      ),
    ),
    recipes = listOf(
      Recipe(
        recipeId = "rec_quick_scramble",
        title = "Warm Olive Oil Egg Scramble",
        sourceAttribution = "Household Test Kitchen",
        allowedSlots = listOf(MealSlotType.BREAKFAST, MealSlotType.LUNCH, MealSlotType.DINNER),
        preferredOwner = CookOwner.FRED,
        rawPrepMinutes = 4,
        cookMinutes = 6,
        calibratedActiveMinutes = 12,
        morningSteps = listOf(MorningStep(1, "Fold soft eggs in warm olive oil.", 360)),
        toddlerPrepTip = "Cut soft egg ribbons into bite-sized pieces for 2yo.",
        bomLines = listOf(BomLine("ing_egg", 4.0), BomLine("ing_olive_oil", 10.0)),
      ),
    ),
    initialPantry = listOf(
      PantryLedgerEntry("ing_egg", IngredientTier.TIER_1_QUANTITATIVE, 8.0),
      PantryLedgerEntry("ing_olive_oil", IngredientTier.TIER_2_STAPLE, 300.0, StapleStatus.PLENTY),
    ),
  )

  /**
   * Verifies pure-Kotlin [validateInitialModelDownloadUrl] and [validateModelRedirectUrl] security
   * invariants across official `litert-community` presets, Xet/LFS CDNs, and SSRF attack vectors.
   */
  @Test
  fun urlAndRedirectValidators_acceptOfficialLitertCdnHostsAndRejectSsrfDowngradesAndTraversal() = runTest {
    // 1. Official E2B and E4B presets pass strict initial URL validation unchanged.
    val e2bUrl = DownloadableGemmaPreset.GEMMA_4_E2B_VISION.downloadUrl
    val e4bUrl = DownloadableGemmaPreset.GEMMA_4_E4B_VISION.downloadUrl
    assertEquals(e2bUrl, validateInitialModelDownloadUrl(e2bUrl))
    assertEquals(e4bUrl, validateInitialModelDownloadUrl("  $e4bUrl  "))

    // Reject cleartext http://, non-litert-community orgs, queries, @ userinfo, custom ports, & '..'.
    val invalidInitialUrls = listOf(
      "http://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
      "https://huggingface.co/untrusted-user/gemma-4/resolve/main/gemma-4-E2B-it.litertlm",
      "https://evil.example.com/litert-community/gemma-4-E2B-it.litertlm",
      "$e2bUrl?token=abc",
      "$e2bUrl#fragment",
      "https://user:secret@huggingface.co/litert-community/gemma-4/resolve/main/gemma-4-E2B-it.litertlm",
      "https://huggingface.co%40evil.example.com/litert-community/gemma-4-E2B-it.litertlm",
      "https://huggingface.co:8443/litert-community/gemma-4/resolve/main/gemma-4-E2B-it.litertlm",
      "https://huggingface.co:443/litert-community/gemma-4/resolve/main/gemma-4-E2B-it.litertlm",
      "https://huggingface.co/litert-community/../other-org/gemma-4-E2B-it.litertlm",
      "https://huggingface.co/litert-community/%2e%2e/other-org/gemma-4-E2B-it.litertlm",
      "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/README.md",
    )
    for (rejectedInitial in invalidInitialUrls) {
      assertThrowsValidation { validateInitialModelDownloadUrl(rejectedInitial) }
    }

    // 2. Official Hugging Face Hub, Xet bridge/transfer, LFS, CloudFront, S3, R2, and GCS CDNs pass.
    val validRedirectUrls = listOf(
      "https://cas-bridge.xethub.hf.co/xet-bridge-us/67f1a2b3?X-Amz-Algorithm=AWS4-HMAC-SHA256",
      "https://transfer.xethub.hf.co/xorbs/default/abc123def456?token=signed_xet_chunk",
      "https://cdn-lfs.hf.co/repos/aa/bb/gemma4-sha256?Expires=1890000000",
      "https://cdn-lfs-us-1.huggingface.co/repos/11/22/gemma-4-E2B-it.litertlm",
      "https://d111.cloudfront.net/litert-community/gemma-4-E2B-it.litertlm?Policy=xyz",
      "https://s3.us-east-1.amazonaws.com/lfs.huggingface.co/oid/gemma4",
      "https://pub.r2.cloudflarestorage.com/hf-lfs/objects/gemma4",
      "https://storage.googleapis.com/hf-lfs-mirror/litert-community/gemma-4-E2B-it.litertlm",
      "https://huggingface.co:443/litert-community/gemma-4-E2B-it-litert-lm/resolve/abcd/gemma-4-E2B-it.litertlm",
    )
    for (allowedRedirect in validRedirectUrls) {
      assertEquals(allowedRedirect, validateModelRedirectUrl(allowedRedirect))
    }

    // Relative Hugging Face Hub Location headers resolve cleanly onto https://huggingface.co.
    assertEquals(
      "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/9f8e7d/gemma-4-E2B-it.litertlm",
      OnDeviceModelDownloader.resolveRedirectTargetUrl(
        currentUrl = e2bUrl,
        rawLocation = "/litert-community/gemma-4-E2B-it-litert-lm/resolve/9f8e7d/gemma-4-E2B-it.litertlm",
      ),
    )

    // Reject http:// downgrades, loopback/RFC1918/metadata IPs, IPv6 [::1], .local, & '..' paths.
    val invalidRedirectUrls = listOf(
      "http://cas-bridge.xethub.hf.co/xet-bridge-us/67f1a2b3",
      "https://localhost/models/gemma-4-E2B-it.litertlm",
      "https://127.0.0.1/models/gemma-4-E2B-it.litertlm",
      "https://169.254.169.254/latest/meta-data/",
      "https://10.0.0.8/models/gemma-4-E2B-it.litertlm",
      "https://192.168.1.5/models/gemma-4-E2B-it.litertlm",
      "https://172.20.0.1/models/gemma-4-E2B-it.litertlm",
      "https://[::1]/models/gemma-4-E2B-it.litertlm",
      "https://internal.corp.local/models/gemma-4-E2B-it.litertlm",
      "https://untrusted-mirror.example.org/models/gemma-4-E2B-it.litertlm",
      "https://cdn-lfs.hf.co:8443/repos/aa/bb/model",
      "https://token@cdn-lfs.hf.co/repos/aa/bb/model",
      "https://cdn-lfs.hf.co/repos/../../etc/passwd",
      "https://cdn-lfs.hf.co/repos/%2e%2e/secret",
    )
    for (rejectedRedirect in invalidRedirectUrls) {
      assertThrowsValidation { validateModelRedirectUrl(rejectedRedirect) }
    }
  }

  /**
   * Verifies socket-free `302 -> 307` Xet CDN redirect following, `.part` preservation on mid-stream
   * `IOException("Wi-Fi dropped")`, `HTTP 206 Partial Content` `Range` resumption + atomic rename,
   * fast-path network skip when verified weights exist, and `.part` deletion on explicit cancel.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun downloader_followsMultiHopRedirectsPreservesPartOnDropResumes206AndDeletesPartOnCancel() = runTest {
    val testDispatcher = UnconfinedTestDispatcher(testScheduler)
    val modelsDir = tempFolder.newFolder("resumable_models")
    val preset = DownloadableGemmaPreset.GEMMA_4_E2B_VISION
    val fullBytes = ByteArray(8_192) { index -> ((index * 37 + 13) and 0xFF).toByte() }
    val firstHalf = fullBytes.copyOfRange(0, 4_096)
    val secondHalf = fullBytes.copyOfRange(4_096, fullBytes.size)

    val bridgeUrl = "https://cas-bridge.xethub.hf.co/xet-bridge-us/gemma-4-e2b-cas"
    val xetTransferUrl = "https://transfer.xethub.hf.co/xorbs/default/gemma-4-e2b-xorb?sig=valid"

    var attemptPhase = 1
    val capturedCalls = mutableListOf<RecordedDownloadHop>()
    val transport = ModelDownloadHttpTransport { url, headers ->
      val callRecord = RecordedDownloadHop(phase = attemptPhase, url = url, headers = headers)
      capturedCalls.add(callRecord)

      // Route 302 (huggingface.co -> cas-bridge) -> 307 (cas-bridge -> transfer.xethub.hf.co).
      when (url) {
        preset.downloadUrl -> ModelDownloadHttpResponse(
          statusCode = 302,
          headers = mapOf("Location" to bridgeUrl),
          onClose = { callRecord.closed = true },
        )
        bridgeUrl -> ModelDownloadHttpResponse(
          statusCode = 307,
          headers = mapOf("Location" to xetTransferUrl),
          onClose = { callRecord.closed = true },
        )
        xetTransferUrl -> when (attemptPhase) {
          // Attempt 1: stream first 4,096 bytes and drop connection with IOException("Wi-Fi dropped").
          1 -> ModelDownloadHttpResponse(
            statusCode = 200,
            headers = mapOf("Content-Length" to fullBytes.size.toString()),
            contentLength = fullBytes.size.toLong(),
            bodyStream = DroppingAfterPrefixInputStream(firstHalf, "Wi-Fi dropped"),
            onClose = { callRecord.closed = true },
          )
          // Attempt 2: honor Range: bytes=4096- with HTTP 206 Partial Content + Content-Range.
          2 -> {
            assertEquals("bytes=${firstHalf.size}-", headers["Range"])
            ModelDownloadHttpResponse(
              statusCode = 206,
              headers = mapOf(
                "Content-Range" to "bytes ${firstHalf.size}-${fullBytes.size - 1}/${fullBytes.size}",
                "Content-Length" to secondHalf.size.toString(),
              ),
              contentLength = secondHalf.size.toLong(),
              bodyStream = ByteArrayInputStream(secondHalf),
              onClose = { callRecord.closed = true },
            )
          }
          else -> throw AssertionError("Unexpected network call in phase $attemptPhase")
        }
        else -> throw AssertionError("Unexpected URL '$url'")
      }
    }

    val downloader = OnDeviceModelDownloader(
      modelsDir = modelsDir,
      transport = transport,
      availableBytesProvider = { 16_000L * 1024L * 1024L },
      networkStatusProvider = { NetworkMeteringStatus.UNMETERED_WIFI },
      ioDispatcher = testDispatcher,
    )

    val destFile = File(modelsDir, preset.fileName)
    val partFile = File(modelsDir, "${preset.fileName}${ModelFileDiscovery.PARTIAL_DOWNLOAD_SUFFIX}")

    // 1) Attempt 1: follows 302 -> 307, writes first half, throws IOException, and PRESERVES .part!
    val dropError = try {
      downloader.downloadPresetModel(preset = preset, minSizeBytes = 1_024L)
      throw AssertionError("Expected IOException when Wi-Fi dropped mid-stream")
    } catch (expected: IOException) {
      expected
    }
    assertEquals("Wi-Fi dropped", dropError.message)
    assertEquals(3, capturedCalls.size)
    assertTrue(capturedCalls.all { it.headers["Range"] == null })
    assertTrue(capturedCalls.all { it.closed })
    assertTrue(partFile.isFile)
    assertEquals(firstHalf.size.toLong(), partFile.length())
    assertArrayEquals(firstHalf, partFile.readBytes())
    assertFalse(destFile.exists())
    // Partial .part staging file must remain invisible to ModelFileDiscovery scans.
    assertTrue(ModelFileDiscovery.discoverModelsInDirectories(listOf(modelsDir), 1_024L).isEmpty())

    // 2) Attempt 2: inspectPreconditions detects .part -> sends Range across hops -> appends 206!
    val resumePreconditions = downloader.inspectPreconditions(preset)
    assertTrue(resumePreconditions.isResumingPartialDownload)
    assertEquals(firstHalf.size.toLong(), resumePreconditions.existingPartialBytes)
    assertEquals(3, capturedCalls.size)

    attemptPhase = 2
    val progressSnapshots = mutableListOf<Triple<Long, Long, Float>>()
    val completedInfo = downloader.downloadPresetModel(
      preset = preset,
      minSizeBytes = 1_024L,
      onProgress = { copied, total, fraction ->
        progressSnapshots.add(Triple(copied, total, fraction))
      },
    )

    // Verify Range header was forwarded across all 3 hops (huggingface.co -> cas-bridge -> transfer).
    assertEquals(6, capturedCalls.size)
    assertTrue(capturedCalls.drop(3).all { it.headers["Range"] == "bytes=${firstHalf.size}-" })
    assertTrue(capturedCalls.drop(3).all { it.closed })

    // Verify exact concatenated byte payload, atomic .part -> .litertlm promotion, and 0 .part files.
    assertEquals(preset.fileName, completedInfo.fileName)
    assertEquals(fullBytes.size.toLong(), completedInfo.sizeBytes)
    assertEquals(ModelFileFormat.LITERT_LM, completedInfo.format)
    assertTrue(completedInfo.supportsVisionCandidate)
    assertEquals(EdgeDeviceProfile.GEMMA_4_E2B_MULTIMODAL, completedInfo.recommendedProfile)
    assertTrue(destFile.isFile)
    assertFalse(partFile.exists())
    assertTrue(modelsDir.listFiles().orEmpty().none { it.name.endsWith(".part") })
    assertArrayEquals(fullBytes, destFile.readBytes())
    assertEquals(
      Triple(fullBytes.size.toLong(), fullBytes.size.toLong(), 1.0f),
      progressSnapshots.last(),
    )

    // Fast-path check: Attempt 3 skips network completely (0 extra HTTP calls) once verified on disk!
    attemptPhase = 3
    val cachedInfo = downloader.downloadPresetModel(preset = preset, minSizeBytes = 1_024L)
    assertEquals(6, capturedCalls.size)
    assertEquals(destFile.absolutePath, cachedInfo.absolutePath)

    // 3) Explicit cancelActiveDownload() mid-stream aborts with CancellationException & DELETES .part!
    val cancelDir = tempFolder.newFolder("cancelled_models")
    val e4bPreset = DownloadableGemmaPreset.GEMMA_4_E4B_VISION
    val e4bPartFile = File(cancelDir, "${e4bPreset.fileName}${ModelFileDiscovery.PARTIAL_DOWNLOAD_SUFFIX}")
    val e4bDestFile = File(cancelDir, e4bPreset.fileName)
    var cancelStreamClosed = false

    lateinit var cancellableDownloader: OnDeviceModelDownloader
    val cancelTransport = ModelDownloadHttpTransport { _, _ ->
      ModelDownloadHttpResponse(
        statusCode = 200,
        headers = mapOf("Content-Length" to fullBytes.size.toString()),
        contentLength = fullBytes.size.toLong(),
        bodyStream = ByteArrayInputStream(firstHalf),
        onClose = { cancelStreamClosed = true },
      )
    }
    cancellableDownloader = OnDeviceModelDownloader(
      modelsDir = cancelDir,
      transport = cancelTransport,
      ioDispatcher = testDispatcher,
    )

    var sawLivePartBeforeCancel = false
    val cancelEx = try {
      cancellableDownloader.downloadPresetModel(
        preset = e4bPreset,
        minSizeBytes = 1_024L,
        onProgress = { copied, _, _ ->
          if (copied >= firstHalf.size.toLong()) {
            sawLivePartBeforeCancel = e4bPartFile.isFile && e4bPartFile.length() == firstHalf.size.toLong()
            cancellableDownloader.cancelActiveDownload()
          }
        },
      )
      throw AssertionError("Expected CancellationException after cancelActiveDownload()")
    } catch (expected: CancellationException) {
      expected
    }

    assertTrue(sawLivePartBeforeCancel)
    assertTrue(cancelStreamClosed)
    assertEquals(OnDeviceModelDownloader.CANCELLED_EXCEPTION_MESSAGE, cancelEx.message)
    assertFalse(e4bPartFile.exists())
    assertFalse(e4bDestFile.exists())
  }

  /**
   * Verifies [ModelDownloadApprovalSpec] disk/metered/offline invariants and end-to-end
   * [KitchenBoardViewModel] user-approval gating, preset switching, keyless `CLOUD_API -> ON_DEVICE`
   * activation on completion, and clean mid-flight cancellation.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun approvalSpecAndViewModel_gateDownloadsUntilUserConfirmsAutoActivateOnDeviceAndCancelCleanly() = runTest {
    val mib = 1024L * 1024L
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))

    try {
      // 1. Verify ModelDownloadApprovalSpec storage headroom, 512 MB floor, metered, & offline flags.
      val lowDiskSpec = ModelDownloadApprovalSpec(
        preset = DownloadableGemmaPreset.GEMMA_4_E2B_VISION,
        availableFreeBytes = 1_800L * mib,
        networkStatus = NetworkMeteringStatus.UNMETERED_WIFI,
      )
      assertFalse(lowDiskSpec.hasEnoughStorage)
      assertFalse(lowDiskSpec.isMeteredWarningRequired)
      assertFalse(lowDiskSpec.isOfflineWarning)

      val meteredResumeSpec = ModelDownloadApprovalSpec(
        preset = DownloadableGemmaPreset.GEMMA_4_E2B_VISION,
        availableFreeBytes = 8_000L * mib,
        networkStatus = NetworkMeteringStatus.METERED_CELLULAR,
        existingPartialBytes = 650L * mib,
      )
      assertTrue(meteredResumeSpec.hasEnoughStorage)
      assertTrue(meteredResumeSpec.isResumingPartialDownload)
      assertTrue(meteredResumeSpec.isMeteredWarningRequired)
      assertFalse(meteredResumeSpec.isOfflineWarning)
      assertEquals((2_050L - 650L) * mib, meteredResumeSpec.remainingDownloadBytes)

      val belowFloorResumeSpec = ModelDownloadApprovalSpec(
        preset = DownloadableGemmaPreset.GEMMA_4_E2B_VISION,
        availableFreeBytes = 400L * mib,
        networkStatus = NetworkMeteringStatus.UNMETERED_WIFI,
        existingPartialBytes = 2_900L * mib,
      )
      assertFalse(belowFloorResumeSpec.hasEnoughStorage)

      val offlineSpec = ModelDownloadApprovalSpec(
        preset = DownloadableGemmaPreset.GEMMA_4_E4B_VISION,
        availableFreeBytes = 24_000L * mib,
        networkStatus = NetworkMeteringStatus.OFFLINE_OR_UNKNOWN,
      )
      assertTrue(offlineSpec.hasEnoughStorage)
      assertTrue(offlineSpec.isOfflineWarning)
      assertFalse(offlineSpec.isMeteredWarningRequired)

      // Verify deterministic download progress & size formatters used in dialogs and cross-tab strips.
      assertEquals("2,050 MB", formatMbOrGb(DownloadableGemmaPreset.GEMMA_4_E2B_VISION.approxSizeBytes))
      assertEquals("Connecting to Hugging Face CDN...", formatByteProgressLabel(0L, -1L, isNetworkDownload = true))
      assertEquals("640 MB downloaded...", formatByteProgressLabel(640L * mib, -1L, isNetworkDownload = true))

      // 2. End-to-end KitchenBoardViewModel + HybridAiInferenceRouter approval consent lifecycle.
      val vault = InMemoryCloudCredentialVault()
      // Start in keyless CLOUD_API mode to prove Ready download auto-switches router to ON_DEVICE!
      vault.setExecutionMode(AiExecutionMode.CLOUD_API)
      assertEquals(AiExecutionMode.CLOUD_API, vault.configFlow.value.executionMode)
      assertFalse(vault.configFlow.value.hasStoredApiKey)

      val controllablePort = ControllableDownloadEdgePort(mib = mib)
      val router = HybridAiInferenceRouter(onDevicePort = controllablePort, vaultPort = vault)
      val viewModel = KitchenBoardViewModel(
        seedCatalogPort = SeedCatalogPort { seedDataset },
        storagePort = FakeKitchenStorage(),
        aiEdgePort = router,
        voiceInputPort = SilentVoiceInputPort(),
        cloudVaultPort = vault,
        deleteTempImage = {},
      )

      // Zero downloads or precondition checks run prior to explicit user action.
      assertNull(viewModel.uiState.value.pendingModelDownloadApproval)
      assertEquals(0, controllablePort.downloadCallCount)

      // Step A: RequestModelDownloadApproval opens dialog with E2B telemetry and ZERO downloads.
      viewModel.onAiChefAction(AiChefAction.RequestModelDownloadApproval())
      val initialApproval = requireNotNull(viewModel.uiState.value.pendingModelDownloadApproval)
      assertEquals(DownloadableGemmaPreset.GEMMA_4_E2B_VISION, initialApproval.preset)
      assertTrue(initialApproval.isMeteredWarningRequired)
      assertTrue(initialApproval.isResumingPartialDownload)
      assertEquals(0, controllablePort.downloadCallCount)

      // Step B: Switching preset chip to E4B refreshes pendingModelDownloadApproval (0 downloads).
      viewModel.onAiChefAction(
        AiChefAction.RequestModelDownloadApproval(DownloadableGemmaPreset.GEMMA_4_E4B_VISION),
      )
      val switchedApproval = requireNotNull(viewModel.uiState.value.pendingModelDownloadApproval)
      assertEquals(DownloadableGemmaPreset.GEMMA_4_E4B_VISION, switchedApproval.preset)
      assertFalse(switchedApproval.isResumingPartialDownload)
      assertEquals(0, controllablePort.downloadCallCount)

      // Step C: DismissModelDownloadApproval ('Not Now') closes dialog with 0 downloads started.
      viewModel.onAiChefAction(AiChefAction.DismissModelDownloadApproval)
      assertNull(viewModel.uiState.value.pendingModelDownloadApproval)
      assertEquals(0, controllablePort.downloadCallCount)

      // Step D: Re-open and ConfirmApprovedModelDownload closes dialog & emits Importing(isNetworkDownload = true).
      viewModel.onAiChefAction(AiChefAction.RequestModelDownloadApproval())
      assertNotNull(viewModel.uiState.value.pendingModelDownloadApproval)

      val firstDownloadGate = CompletableDeferred<AiRuntimeState>()
      controllablePort.activeDownloadGate = firstDownloadGate
      viewModel.onAiChefAction(
        AiChefAction.ConfirmApprovedModelDownload(DownloadableGemmaPreset.GEMMA_4_E2B_VISION),
      )

      assertNull(viewModel.uiState.value.pendingModelDownloadApproval)
      assertEquals(1, controllablePort.downloadCallCount)
      val liveImporting = viewModel.uiState.value.aiRuntimeState as? AiRuntimeState.Importing
      assertNotNull(liveImporting)
      assertTrue(requireNotNull(liveImporting).isNetworkDownload)
      assertEquals("gemma-4-E2B-it.litertlm", liveImporting.fileName)
      assertTrue(viewModel.uiState.value.isModelOperationInProgress)

      // Duplicate ConfirmApprovedModelDownload while busy is blocked cleanly without a second call.
      viewModel.onAiChefAction(
        AiChefAction.ConfirmApprovedModelDownload(DownloadableGemmaPreset.GEMMA_4_E2B_VISION),
      )
      assertEquals(1, controllablePort.downloadCallCount)

      // Completing download flips keyless CLOUD_API -> ON_DEVICE and publishes Ready capabilities!
      val e2bReadyState = AiRuntimeState.Ready(
        RuntimeCapabilities(
          supportsText = true,
          supportsVision = true,
          backendLabel = "LiteRT-LM GPU + Vision",
          modelName = "gemma-4-E2B-it.litertlm",
        ),
      )
      firstDownloadGate.complete(e2bReadyState)

      assertEquals(AiExecutionMode.ON_DEVICE, vault.configFlow.value.executionMode)
      assertEquals(AiExecutionMode.ON_DEVICE, viewModel.uiState.value.cloudLlmConfig.executionMode)
      assertEquals(e2bReadyState, viewModel.uiState.value.aiRuntimeState)
      assertEquals(
        EdgeDeviceProfile.GEMMA_4_E2B_MULTIMODAL,
        viewModel.uiState.value.activeDeviceProfile,
      )
      assertFalse(viewModel.uiState.value.isModelOperationInProgress)

      // Step E: CancelActiveModelDownload aborts an in-flight E4B download and restores clean state.
      val secondDownloadGate = CompletableDeferred<AiRuntimeState>()
      controllablePort.activeDownloadGate = secondDownloadGate
      viewModel.onAiChefAction(
        AiChefAction.ConfirmApprovedModelDownload(DownloadableGemmaPreset.GEMMA_4_E4B_VISION),
      )
      assertEquals(2, controllablePort.downloadCallCount)
      assertTrue(viewModel.uiState.value.isModelOperationInProgress)

      viewModel.onAiChefAction(AiChefAction.CancelActiveModelDownload)
      assertEquals(1, controllablePort.cancelCallCount)
      assertFalse(viewModel.uiState.value.isModelOperationInProgress)
      assertTrue(viewModel.uiState.value.aiRuntimeState is AiRuntimeState.Error)
      assertEquals(
        KitchenModelDownloadDelegate.CANCELLED_DOWNLOAD_TOAST,
        viewModel.uiState.value.toastMessage,
      )

      router.close()
    } finally {
      Dispatchers.resetMain()
    }
  }

  // Mutable hop record verifying redirect URL chain, Range headers, and deterministic stream close.
  private class RecordedDownloadHop(
    val phase: Int,
    val url: String,
    val headers: Map<String, String>,
    var closed: Boolean = false,
  )

  // Deterministic InputStream yielding prefixBytes before raising IOException(dropMessage).
  private class DroppingAfterPrefixInputStream(
    private val prefixBytes: ByteArray,
    private val dropMessage: String,
  ) : InputStream() {
    private var cursor: Int = 0

    override fun read(): Int {
      if (cursor < prefixBytes.size) {
        val next = prefixBytes[cursor].toInt() and 0xFF
        cursor += 1
        return next
      }
      throw IOException(dropMessage)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
      if (len == 0) return 0
      if (cursor >= prefixBytes.size) {
        throw IOException(dropMessage)
      }
      val toCopy = minOf(len, prefixBytes.size - cursor)
      System.arraycopy(prefixBytes, cursor, b, off, toCopy)
      cursor += toCopy
      return toCopy
    }
  }

  // Controllable AiEdgePort double exercising ViewModel + HybridAiInferenceRouter download states.
  private class ControllableDownloadEdgePort(
    private val mib: Long,
  ) : AiEdgePort {
    private val mutableState = MutableStateFlow<AiRuntimeState>(
      AiRuntimeState.Error("No local model imported yet."),
    )
    override val runtimeState: StateFlow<AiRuntimeState> = mutableState.asStateFlow()

    var downloadCallCount: Int = 0
    var cancelCallCount: Int = 0
    var activeDownloadGate: CompletableDeferred<AiRuntimeState>? = null

    override suspend fun inspectDownloadPreconditions(
      preset: DownloadableGemmaPreset,
    ): ModelDownloadApprovalSpec =
      ModelDownloadApprovalSpec(
        preset = preset,
        availableFreeBytes = 14_000L * mib,
        networkStatus = NetworkMeteringStatus.METERED_CELLULAR,
        existingPartialBytes = when (preset) {
          DownloadableGemmaPreset.GEMMA_4_E2B_VISION -> 512L * mib
          DownloadableGemmaPreset.GEMMA_4_E4B_VISION -> 0L
        },
      )

    override suspend fun downloadAndLoadPresetModel(
      preset: DownloadableGemmaPreset,
    ): AiRuntimeState {
      downloadCallCount += 1
      mutableState.value = AiRuntimeState.Importing(
        fileName = preset.fileName,
        progressFraction = 0.35f,
        copiedBytes = 716L * mib,
        totalBytes = preset.approxSizeBytes,
        isNetworkDownload = true,
      )
      val gate = activeDownloadGate
      val next = when (gate) {
        null -> AiRuntimeState.Ready(
          RuntimeCapabilities(true, true, "LiteRT-LM GPU + Vision", preset.fileName),
        )
        else -> gate.await()
      }
      mutableState.value = next
      return next
    }

    override fun cancelActiveDownload() {
      cancelCallCount += 1
      mutableState.value = AiRuntimeState.Error(KitchenModelDownloadDelegate.CANCELLED_DOWNLOAD_TOAST)
      activeDownloadGate?.cancel(
        CancellationException(OnDeviceModelDownloader.CANCELLED_EXCEPTION_MESSAGE),
      )
    }

    override suspend fun autoDiscoverAndInitialize(): AiRuntimeState = mutableState.value

    override suspend fun importAndLoadModel(
      sourceUriString: String,
      displayName: String?,
    ): AiRuntimeState = mutableState.value

    override suspend fun generateText(prompt: String): String = "{}"

    override suspend fun analyzeImageWithPrompt(
      preprocessedImagePath: String,
      prompt: String,
    ): String = throw VisionNotSupportedException("Stub port", "stub")

    override fun close() = Unit
  }
}
