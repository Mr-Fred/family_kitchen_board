package com.familykitchen.board

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.familykitchen.board.ai.AndroidVoiceInputAdapter
import com.familykitchen.board.ai.HybridAiInferenceRouter
import com.familykitchen.board.ai.LiteRtMultimodalEngine
import com.familykitchen.board.data.AssetSeedCatalogLoader
import com.familykitchen.board.data.KeystoreCloudCredentialVault
import com.familykitchen.board.data.KitchenImagePreprocessor
import com.familykitchen.board.data.LocalJsonKitchenStorage
import com.familykitchen.board.ui.KitchenBoardViewModel
import com.familykitchen.board.ui.navigation.KitchenScaffold
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Thin (< 80 logical lines) 100% Native Jetpack Compose entrypoint for SavorAI / Family Kitchen Board.
// Wires offline-first on-device LiteRT-LM + opt-in Keystore AES-256-GCM HTTPS Cloud LLM routing.

/**
 * Primary native Android [ComponentActivity] hosting the `SavorAI` (`Family Kitchen Board`)
 * Jetpack Compose Material 3 shell on Samsung Galaxy S22 and Galaxy S22 Ultra (`minSdk = 31`).
 *
 * Configures edge-to-edge system bars, prunes stale temporary camera captures in `cacheDir/captures/`,
 * and wires offline-first persistence (`LocalJsonKitchenStorage`), seed asset validation
 * (`AssetSeedCatalogLoader`), hardware Keystore AES-256-GCM credential protection
 * (`KeystoreCloudCredentialVault`), hybrid On-Device / Cloud multimodal routing
 * (`HybridAiInferenceRouter` wrapping `LiteRtMultimodalEngine`), and bilingual voice input
 * (`AndroidVoiceInputAdapter`) into [KitchenBoardViewModel].
 */
class MainActivity : ComponentActivity() {

  // Instantiate hexagonal production adapters once per ViewModel lifecycle using applicationContext.
  private val viewModel: KitchenBoardViewModel by viewModels {
    val appContext = applicationContext
    val cloudVault = KeystoreCloudCredentialVault(context = appContext)
    val hybridRouter = HybridAiInferenceRouter(
      onDevicePort = LiteRtMultimodalEngine(context = appContext),
      vaultPort = cloudVault,
    )
    KitchenBoardViewModel.Factory(
      seedCatalogPort = AssetSeedCatalogLoader(context = appContext),
      storagePort = LocalJsonKitchenStorage(context = appContext),
      aiEdgePort = hybridRouter,
      voiceInputPort = AndroidVoiceInputAdapter(context = appContext),
      cloudVaultPort = cloudVault,
      appContext = appContext,
      autoInitializeAiOnStart = true,
    )
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Extend warm linen / counter-dark surfaces cleanly behind translucent status and nav bars.
    enableEdgeToEdge()

    // Sweep orphaned camera/inference JPEGs (> 30 min old) off the UI thread on cold start.
    // On-device AI runtime initialization runs once via hydrateInitialSnapshot(autoInitializeAiOnStart = true).
    lifecycleScope.launch(Dispatchers.IO) {
      runCatching { KitchenImagePreprocessor.pruneStaleCaptures(applicationContext) }
    }

    setContent {
      val uiState by viewModel.uiState.collectAsStateWithLifecycle()
      FamilyKitchenBoardTheme(darkTheme = uiState.isDarkCounterTheme) {
        KitchenScaffold(viewModel = viewModel)
      }
    }
  }
}
