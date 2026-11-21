package com.familykitchen.board.ui.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.AiChefAction
import com.familykitchen.board.domain.ChildGrowthStage
import com.familykitchen.board.domain.CloudLlmConfig
import com.familykitchen.board.domain.ConsumerHouseholdTemplate
import com.familykitchen.board.domain.EdgeDeviceProfile
import com.familykitchen.board.domain.FirstRunOnboardingDraft
import com.familykitchen.board.domain.HouseholdCurrency
import com.familykitchen.board.domain.HouseholdProfile
import com.familykitchen.board.domain.StartingPantryMode
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ports.RuntimeCapabilities
import com.familykitchen.board.ui.computeToastAutoDismissMillis
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme
import com.familykitchen.board.ui.theme.SavorBrandBadge
import kotlinx.coroutines.delay

// Warm, serene 4-step SavorAI First-Run Onboarding Host (imperative SAF + BackHandler shell) and
// pure stateless FirstRunOnboardingScreen Composable with compact 360dp 1.35x font-scale resilience.

// Fixed 4-step guided structure: 1 Household -> 2 Market -> 3 Pantry -> 4 AI Sous-Chef.
private const val TOTAL_ONBOARDING_STEPS: Int = 4
private const val FINAL_ONBOARDING_STEP_INDEX: Int = TOTAL_ONBOARDING_STEPS - 1
private val ALLOWED_ONBOARDING_MODEL_EXTENSIONS: Set<String> = setOf("litertlm", "task", "bin")

/**
 * Imperative-shell onboarding host owning Bundle-safe `rememberSaveable` step/draft state, Android
 * system back navigation (`BackHandler`), and scoped SAF `OpenDocument` `.litertlm` model picking
 * (safely bypassed when `LocalInspectionMode.current` is `true`).
 *
 * @param initialProfile Current persisted [HouseholdProfile] used to seed initial draft defaults.
 * @param isReplayMode `false` on a brand-new install (`Skip · Demo`), `true` when re-launched (`Close`).
 * @param runtimeState Live on-device [AiRuntimeState] surfaced on Step 4.
 * @param activeDeviceProfile Selected on-device hardware profile.
 * @param cloudConfig Non-secret [CloudLlmConfig] state slice surfaced on Step 4.
 * @param isVerifyingCloudKey `true` while probing Cloud LLM connectivity.
 * @param isRefreshingCloudModels `true` while syncing live `/models` identifiers.
 * @param isSavingBatch `true` while committing the atomic `O(1)` onboarding snapshot batch.
 * @param toastMessage Optional transient feedback message (for example, Cloud API key verification).
 * @param toastEventId Monotonic toast event counter restarting auto-dismiss on repeated notices.
 * @param onDismissToast Callback dismissing [toastMessage].
 * @param onAiChefAction Callback dispatching [AiChefAction] intents to `KitchenBoardViewModel`.
 * @param onCompleteOnboarding Commits the completed [FirstRunOnboardingDraft] in 1 `O(1)` batch write.
 * @param onSkipWithDemoKitchen Skips setup on fresh installs and loads the 44-item sample kitchen.
 * @param onDismissReplay Closes the wizard without mutating state when [isReplayMode] is `true`.
 * @param modifier Optional Compose [Modifier].
 */
@Composable
fun FirstRunOnboardingHost(
  initialProfile: HouseholdProfile,
  isReplayMode: Boolean,
  runtimeState: AiRuntimeState,
  activeDeviceProfile: EdgeDeviceProfile,
  cloudConfig: CloudLlmConfig,
  isVerifyingCloudKey: Boolean = false,
  isRefreshingCloudModels: Boolean = false,
  isSavingBatch: Boolean = false,
  toastMessage: String? = null,
  toastEventId: Long = 0L,
  onDismissToast: () -> Unit = {},
  onAiChefAction: (AiChefAction) -> Unit = {},
  onCompleteOnboarding: (FirstRunOnboardingDraft) -> Unit = {},
  onSkipWithDemoKitchen: () -> Unit = {},
  onDismissReplay: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  // Detect Compose @Preview inspection so ActivityResultContracts and BackHandler stay hermetic.
  val isInspectionPreview = LocalInspectionMode.current
  val seedDraft = remember(initialProfile, isReplayMode) {
    FirstRunOnboardingDraft.fromHouseholdProfile(profile = initialProfile, isFreshInstall = !isReplayMode)
  }

  // Persist every enum and set via primitive String / Int / List<String> for Android Bundle safety.
  var currentStep by rememberSaveable { mutableIntStateOf(0) }
  var householdName by rememberSaveable { mutableStateOf(seedDraft.householdName) }
  var templateName by rememberSaveable { mutableStateOf(seedDraft.template.name) }
  var morningLeadName by rememberSaveable { mutableStateOf(seedDraft.morningLeadName) }
  var eveningLeadName by rememberSaveable { mutableStateOf(seedDraft.eveningLeadName) }
  var olderKidAgeYears by rememberSaveable { mutableIntStateOf(seedDraft.olderKidAgeYears) }
  var youngestStageName by rememberSaveable { mutableStateOf(seedDraft.youngestStage.name) }
  var selectedAllergensList by rememberSaveable { mutableStateOf<List<String>>(ArrayList(seedDraft.selectedAllergens)) }
  var currencyName by rememberSaveable { mutableStateOf(seedDraft.currency.name) }
  var marketRegionLabel by rememberSaveable { mutableStateOf(seedDraft.marketRegionLabel) }
  var pantryModeName by rememberSaveable { mutableStateOf(seedDraft.pantryMode.name) }
  var selectedBasicsList by rememberSaveable { mutableStateOf<List<String>>(ArrayList(seedDraft.selectedBasicIngredientIds)) }
  var importNotice by rememberSaveable { mutableStateOf<String?>(null) }

  // Reconstruct typed domain draft deterministically from Bundle-safe primitive state holders.
  val draft = FirstRunOnboardingDraft(
    householdName = householdName,
    template = ConsumerHouseholdTemplate.entries.find { it.name == templateName } ?: seedDraft.template,
    morningLeadName = morningLeadName,
    eveningLeadName = eveningLeadName,
    olderKidAgeYears = olderKidAgeYears,
    youngestStage = ChildGrowthStage.entries.find { it.name == youngestStageName } ?: seedDraft.youngestStage,
    selectedAllergens = selectedAllergensList.toSet(),
    currency = HouseholdCurrency.entries.find { it.name == currencyName } ?: seedDraft.currency,
    marketRegionLabel = marketRegionLabel,
    pantryMode = StartingPantryMode.entries.find { it.name == pantryModeName } ?: seedDraft.pantryMode,
    selectedBasicIngredientIds = selectedBasicsList.toSet(),
  )

  // Synchronize user edits back into individual primitive rememberSaveable slots.
  val applyDraftUpdate: (FirstRunOnboardingDraft) -> Unit = { updated ->
    householdName = updated.householdName
    templateName = updated.template.name
    morningLeadName = updated.morningLeadName
    eveningLeadName = updated.eveningLeadName
    olderKidAgeYears = updated.olderKidAgeYears
    youngestStageName = updated.youngestStage.name
    selectedAllergensList = ArrayList(updated.selectedAllergens)
    currencyName = updated.currency.name
    marketRegionLabel = updated.marketRegionLabel
    pantryModeName = updated.pantryMode.name
    selectedBasicsList = ArrayList(updated.selectedBasicIngredientIds)
  }

  // Intercept system back: step backward on Steps 2..4, or dismiss replay on Step 1.
  if (!isInspectionPreview) {
    BackHandler(enabled = currentStep > 0 || isReplayMode) {
      when {
        currentStep > 0 -> currentStep = (currentStep - 1).coerceAtLeast(0)
        isReplayMode -> onDismissReplay()
      }
    }
  }

  // Register SAF OpenDocument contract only outside headless @Preview inspection mode.
  val launchModelPicker: () -> Unit = when {
    isInspectionPreview -> ({})
    else -> rememberSafOnboardingModelPicker(runtimeState, onAiChefAction) { importNotice = it }
  }

  FirstRunOnboardingScreen(
    currentStep = currentStep, draft = draft, isReplayMode = isReplayMode,
    runtimeState = runtimeState, activeDeviceProfile = activeDeviceProfile, cloudConfig = cloudConfig,
    isVerifyingCloudKey = isVerifyingCloudKey, isRefreshingCloudModels = isRefreshingCloudModels,
    isSavingBatch = isSavingBatch, importNotice = importNotice, toastMessage = toastMessage,
    toastEventId = toastEventId,
    onStepChange = { targetStep -> currentStep = targetStep.coerceIn(0, FINAL_ONBOARDING_STEP_INDEX) },
    onDraftChange = applyDraftUpdate, onAiChefAction = onAiChefAction,
    onLaunchModelPicker = launchModelPicker, onDismissImportNotice = { importNotice = null },
    onDismissToast = onDismissToast, onCompleteOnboarding = onCompleteOnboarding,
    onSkipWithDemoKitchen = onSkipWithDemoKitchen, onDismissReplay = onDismissReplay,
    modifier = modifier,
  )
}

/**
 * Pure stateless 4-step `SavorAI` First-Run Onboarding screen rendering the progress header,
 * scrollable culinary step card, and `.imePadding()`-safe persistent bottom navigation bar.
 *
 * @param currentStep Zero-based active step index (`0..3` -> `Step 1 of 4`..`Step 4 of 4`).
 * @param draft Immutable [FirstRunOnboardingDraft] state slice.
 * @param isReplayMode `false` on cold installs (`"Skip · Demo"`), `true` on replays (`"Close"`).
 * @param runtimeState Current on-device [AiRuntimeState].
 * @param activeDeviceProfile Active on-device hardware profile.
 * @param cloudConfig Non-secret [CloudLlmConfig] state slice.
 * @param isVerifyingCloudKey `true` while verifying an HTTPS Cloud API key.
 * @param isRefreshingCloudModels `true` while syncing live `/models` for the active provider.
 * @param isSavingBatch `true` while committing the `O(1)` atomic onboarding batch write.
 * @param importNotice Optional SAF model picker validation notice.
 * @param toastMessage Optional status toast banner (such as Cloud API key connectivity feedback).
 * @param toastEventId Monotonic toast event counter restarting auto-dismiss on repeated notices.
 * @param onStepChange Callback navigating to a target step index (`0..3`).
 * @param onDraftChange Callback emitting updated [FirstRunOnboardingDraft] edits.
 * @param onAiChefAction Callback dispatching [AiChefAction] intents on Step 4.
 * @param onLaunchModelPicker Callback opening SAF `OpenDocument` for `.litertlm` imports.
 * @param onDismissImportNotice Callback clearing [importNotice].
 * @param onDismissToast Callback dismissing [toastMessage].
 * @param onCompleteOnboarding Final CTA callback committing [draft] into household storage.
 * @param onSkipWithDemoKitchen Top-right `"Skip · Demo"` callback on brand-new installs.
 * @param onDismissReplay Top-right `"Close"` callback when re-running setup from Profile or QA Lab.
 * @param modifier Optional Compose [Modifier].
 */
@Composable
fun FirstRunOnboardingScreen(
  currentStep: Int,
  draft: FirstRunOnboardingDraft,
  isReplayMode: Boolean,
  runtimeState: AiRuntimeState = AiRuntimeState.Uninitialized,
  activeDeviceProfile: EdgeDeviceProfile = EdgeDeviceProfile.BALANCED_GEMMA_2B_INT4,
  cloudConfig: CloudLlmConfig = CloudLlmConfig(),
  isVerifyingCloudKey: Boolean = false,
  isRefreshingCloudModels: Boolean = false,
  isSavingBatch: Boolean = false,
  importNotice: String? = null,
  toastMessage: String? = null,
  toastEventId: Long = 0L,
  onStepChange: (Int) -> Unit = {},
  onDraftChange: (FirstRunOnboardingDraft) -> Unit = {},
  onAiChefAction: (AiChefAction) -> Unit = {},
  onLaunchModelPicker: () -> Unit = {},
  onDismissImportNotice: () -> Unit = {},
  onDismissToast: () -> Unit = {},
  onCompleteOnboarding: (FirstRunOnboardingDraft) -> Unit = {},
  onSkipWithDemoKitchen: () -> Unit = {},
  onDismissReplay: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  val safeStep = currentStep.coerceIn(0, FINAL_ONBOARDING_STEP_INDEX)
  val scrollState = rememberScrollState()

  // Reset vertical scroll position to the top whenever the user advances or steps backward.
  LaunchedEffect(safeStep) { scrollState.scrollTo(0) }

  // Chain statusBarsPadding, navigationBarsPadding, and imePadding so keyboards never hide CTAs.
  Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
      OnboardingProgressTopBar(
        safeStep = safeStep, isReplayMode = isReplayMode, isSavingBatch = isSavingBatch,
        onSkipWithDemoKitchen = onSkipWithDemoKitchen, onDismissReplay = onDismissReplay,
      )
      // Surface Cloud API key verification or validation feedback with smooth auto-dismiss.
      OnboardingFeedbackStrip(
        message = toastMessage,
        toastEventId = toastEventId,
        onDismiss = onDismissToast,
      )
      HorizontalDivider(color = extendedColors.borderSubtle)

      // Center scrollable step body up to 640.dp for compact 360dp Android and wide counter parity.
      Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
          modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()
            .verticalScroll(scrollState).padding(horizontal = 18.dp, vertical = 18.dp),
        ) {
          // Route each step index cleanly to its stateless domain-aligned section card.
          when (safeStep) {
            0 -> OnboardingHouseholdStepSection(draft = draft, onDraftChange = onDraftChange)
            1 -> OnboardingMarketStepSection(draft = draft, onDraftChange = onDraftChange)
            2 -> OnboardingPantryStepSection(draft = draft, onDraftChange = onDraftChange)
            else -> OnboardingAiSetupStepSection(
              runtimeState = runtimeState, activeDeviceProfile = activeDeviceProfile, cloudConfig = cloudConfig,
              isVerifyingCloudKey = isVerifyingCloudKey, isRefreshingCloudModels = isRefreshingCloudModels,
              importNotice = importNotice, onDismissImportNotice = onDismissImportNotice,
              onLaunchModelPicker = onLaunchModelPicker, onAiChefAction = onAiChefAction,
            )
          }
        }
      }

      HorizontalDivider(color = extendedColors.borderSubtle)
      OnboardingBottomNavigationBar(
        safeStep = safeStep, isSavingBatch = isSavingBatch,
        onBack = { onStepChange((safeStep - 1).coerceAtLeast(0)) },
        onContinue = { onStepChange((safeStep + 1).coerceAtMost(FINAL_ONBOARDING_STEP_INDEX)) },
        onFinish = { onCompleteOnboarding(draft) },
      )
    }
  }
}

@Composable
private fun OnboardingProgressTopBar(
  safeStep: Int,
  isReplayMode: Boolean,
  isSavingBatch: Boolean,
  onSkipWithDemoKitchen: () -> Unit,
  onDismissReplay: () -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  val stepLabel = when (safeStep) {
    0 -> "Household"
    1 -> "Currency & Market"
    2 -> "Starting Pantry"
    else -> "AI Sous-Chef"
  }

  Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Row(modifier = Modifier.weight(1f).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // 36.dp circular SavorAI Dutch-oven cocotte brand badge (zero generic AI sparkles).
        SavorBrandBadge(badgeSize = 36.dp, logoSize = 21.dp, shape = CircleShape)
        Column {
          Text("Step ${safeStep + 1} of $TOTAL_ONBOARDING_STEPS", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
          Text(stepLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }

      // 48.dp right-hand escape hatch: "Skip · Demo" on cold installs, "Close" on replay mode.
      OutlinedButton(
        onClick = if (isReplayMode) onDismissReplay else onSkipWithDemoKitchen, enabled = !isSavingBatch,
        shape = CircleShape, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
      ) {
        Text(if (isReplayMode) "Close" else "Skip · Demo", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
      }
    }

    // Four thin horizontal muted-sage progress bars tracking progress across Steps 1..4.
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      for (stepIdx in 0 until TOTAL_ONBOARDING_STEPS) {
        val barColor = if (stepIdx <= safeStep) extendedColors.herbalSage else extendedColors.borderSubtle
        Box(modifier = Modifier.weight(1f).height(4.dp).clip(CircleShape).background(barColor))
      }
    }
  }
}

@Composable
private fun OnboardingBottomNavigationBar(
  safeStep: Int,
  isSavingBatch: Boolean,
  onBack: () -> Unit,
  onContinue: () -> Unit,
  onFinish: () -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  val isFinalStep = safeStep >= FINAL_ONBOARDING_STEP_INDEX
  // Herbal Sage fill paired with M3 onPrimary maintains high contrast in both Daylight and Dark.
  val sageButtonColors = ButtonDefaults.buttonColors(
    containerColor = extendedColors.herbalSage, contentColor = MaterialTheme.colorScheme.onPrimary,
  )

  Box(
    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = 18.dp, vertical = 12.dp),
    contentAlignment = Alignment.Center,
  ) {
    Row(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
      // Show 52.dp Back button on Steps 2..4 so users can revise earlier choices effortlessly.
      if (safeStep > 0) {
        OutlinedButton(
          onClick = onBack, enabled = !isSavingBatch, shape = CircleShape,
          contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
          modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 52.dp),
        ) {
          Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Back", style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
        }
      }

      when {
        // Step 4 primary 52.dp Herbal Sage CTA committing onboarding in 1 O(1) atomic write.
        isFinalStep -> Button(
          onClick = onFinish, enabled = !isSavingBatch, shape = CircleShape, colors = sageButtonColors,
          modifier = Modifier.weight(1f).defaultMinSize(minHeight = 52.dp),
        ) {
          when {
            isSavingBatch -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            else -> Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
          }
          Spacer(modifier = Modifier.width(8.dp))
          Text(if (isSavingBatch) "Preparing Kitchen…" else "Enter My Kitchen", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }

        // Steps 1..3 primary 52.dp Continue CTA advancing to the next guided step.
        else -> Button(
          onClick = onContinue, shape = CircleShape, colors = sageButtonColors,
          modifier = Modifier.weight(1f).defaultMinSize(minHeight = 52.dp),
        ) {
          Text("Continue", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
          Spacer(modifier = Modifier.width(8.dp))
          Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
        }
      }
    }
  }
}

@Composable
private fun OnboardingFeedbackStrip(
  message: String?,
  toastEventId: Long = 0L,
  onDismiss: () -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  val isVisible = !message.isNullOrBlank()
  // Retain latest non-blank toast text so fade/shrink exit transitions render cleanly.
  var lastNonBlankMessage by remember { mutableStateOf(message.orEmpty()) }
  if (!message.isNullOrBlank()) {
    lastNonBlankMessage = message
  }
  LaunchedEffect(toastEventId, message) {
    if (!message.isNullOrBlank()) {
      delay(computeToastAutoDismissMillis(message))
      onDismiss()
    }
  }

  AnimatedVisibility(
    visible = isVisible,
    enter = fadeIn(tween(180)) + expandVertically(tween(200)),
    exit = fadeOut(tween(220)) + shrinkVertically(tween(220)),
  ) {
    Surface(
      shape = RoundedCornerShape(12.dp), color = extendedColors.sageContainer,
      border = BorderStroke(1.dp, extendedColors.herbalSage.copy(alpha = 0.45f)),
      modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
    ) {
      Row(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(lastNonBlankMessage, style = MaterialTheme.typography.bodySmall, color = extendedColors.onSageContainer, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
          Icon(Icons.Filled.Close, contentDescription = "Dismiss notification", tint = extendedColors.onSageContainer, modifier = Modifier.size(16.dp))
        }
      }
    }
  }
}

@Composable
private fun rememberSafOnboardingModelPicker(
  runtimeState: AiRuntimeState,
  onAction: (AiChefAction) -> Unit,
  onImportNotice: (String?) -> Unit,
): () -> Unit {
  val context = LocalContext.current
  // Zero-permission SAF OpenDocument launcher streaming .litertlm into app-scoped filesDir/models.
  val launcher = rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument()) { docUri: Uri? ->
    if (docUri != null) {
      when {
        runtimeState.isBackgroundBusy -> onImportNotice("Model setup is already running — check the progress bar above.")
        else -> {
          runCatching { context.contentResolver.takePersistableUriPermission(docUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
          val displayName = resolveOnboardingDocName(context, docUri)
          val ext = displayName.substringAfterLast('.', "").lowercase()
          when {
            ext in ALLOWED_ONBOARDING_MODEL_EXTENSIONS -> {
              onImportNotice(null)
              onAction(AiChefAction.ImportModelUri(sourceUriString = docUri.toString(), displayName = displayName))
            }
            else -> onImportNotice("Unsupported file '$displayName'. Select a .litertlm, .task, or .bin model package.")
          }
        }
      }
    }
  }

  return {
    when {
      runtimeState.isBackgroundBusy -> onImportNotice("Model setup is already running — check the progress bar above.")
      else -> launcher.launch(arrayOf("application/octet-stream", "*/*"))
    }
  }
}

// Queries OpenableColumns.DISPLAY_NAME safely with a clean .litertlm fallback filename.
private fun resolveOnboardingDocName(context: Context, uri: Uri): String =
  runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
      val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
      if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    }
  }.getOrNull()?.takeIf { it.isNotBlank() } ?: "imported_model.litertlm"

/** Warm Daylight `@Preview` showcasing Step 1 (`Welcome & Your Household`) on a fresh install. */
@Preview(name = "Onboarding Step 1 — Warm Daylight", showBackground = true, widthDp = 412, heightDp = 915)
@Composable
fun FirstRunOnboardingStep1DaylightPreview() {
  // Verify Step 1 household templates, EC-08 toddler safety preview, and Skip · Demo top action.
  FamilyKitchenBoardTheme(darkTheme = false) {
    FirstRunOnboardingScreen(currentStep = 0, draft = FirstRunOnboardingDraft(), isReplayMode = false)
  }
}

/** Warm Daylight `@Preview` showcasing Step 3 (`Your Starting Pantry` Curated Basics checklist). */
@Preview(name = "Onboarding Step 3 — Curated Basics", showBackground = true, widthDp = 412, heightDp = 915)
@Composable
fun FirstRunOnboardingStep3BasicsDaylightPreview() {
  // Verify Step 3 Curated Basics checklist with DAIRY allergen automatically excluding Milk/Butter.
  FamilyKitchenBoardTheme(darkTheme = false) {
    val safeBasics = pruneAllergenConflictingBasics(FirstRunOnboardingDraft().selectedBasicIngredientIds, setOf("DAIRY"))
    FirstRunOnboardingScreen(
      currentStep = 2,
      draft = FirstRunOnboardingDraft(selectedAllergens = setOf("DAIRY"), selectedBasicIngredientIds = safeBasics),
      isReplayMode = false,
    )
  }
}

/** Counter Dark `@Preview` showcasing Step 4 (`AI Sous-Chef Setup`) with zero JNI/Context calls. */
@Preview(name = "Onboarding Step 4 — Counter Dark", showBackground = true, widthDp = 412, heightDp = 915)
@Composable
fun FirstRunOnboardingStep4CounterDarkPreview() {
  // Verify Step 4 Ambient Counter Dark legibility, Close replay button, and Enter My Kitchen CTA.
  FamilyKitchenBoardTheme(darkTheme = true) {
    FirstRunOnboardingScreen(
      currentStep = 3, draft = FirstRunOnboardingDraft(), isReplayMode = true,
      runtimeState = AiRuntimeState.Ready(
        RuntimeCapabilities(supportsText = true, supportsVision = true, backendLabel = "LiteRT-LM GPU", modelName = "gemma-e2b-it-multimodal-int4.litertlm"),
      ),
    )
  }
}
