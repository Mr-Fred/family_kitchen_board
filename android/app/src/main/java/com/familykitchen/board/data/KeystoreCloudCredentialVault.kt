package com.familykitchen.board.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.familykitchen.board.domain.AiExecutionMode
import com.familykitchen.board.domain.CloudLlmConfig
import com.familykitchen.board.domain.CloudLlmProvider
import com.familykitchen.board.domain.isDeprecatedCloudModelId
import com.familykitchen.board.domain.isValidCloudModelId
import com.familykitchen.board.domain.maskApiKeyPreview
import com.familykitchen.board.domain.migrateDeprecatedCloudModelId
import com.familykitchen.board.domain.requireDomainValid
import com.familykitchen.board.ports.CloudCredentialVaultPort
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Hardware-backed Android Keystore AES-256-GCM credential vault for opt-in Cloud LLM API keys.
// Persists ONLY "{ivBase64}:{ciphertextBase64}" + non-secret provider metadata in MODE_PRIVATE prefs.

/**
 * Production Android implementation of [CloudCredentialVaultPort] encrypting API keys at rest via
 * hardware-backed `AndroidKeyStore` (`AES/GCM/NoPadding`, 256-bit key `savor_cloud_llm_aes256_v1`,
 * 128-bit authentication tag, per-provider GCM AAD binding).
 *
 * Raw API keys are **never** written to disk, `KitchenSnapshot`, or `KitchenUiState`. Only
 * `{ivBase64}:{ciphertextBase64}` ciphertext records and non-secret routing preferences
 * (`executionMode`, `activeProvider`, `modelIdByProvider`, and `•••• 1234` masked badges) are stored
 * inside `MODE_PRIVATE` `SharedPreferences` (`savor_cloud_ai_vault_v1`, excluded from backups by
 * `android:allowBackup="false"`).
 *
 * @param context Android context resolved to `applicationContext` to avoid Activity leaks.
 * @param ioDispatcher Background dispatcher serializing Keystore IPC and `SharedPreferences` commits.
 */
class KeystoreCloudCredentialVault(
  context: Context,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CloudCredentialVaultPort {

  private val appContext: Context = context.applicationContext ?: context

  // App-private preferences storing ONLY AES-GCM ciphertexts and non-secret routing metadata.
  private val preferences: SharedPreferences by lazy {
    appContext.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
  }

  // Serializes Keystore cipher operations and preference writes across concurrent coroutines.
  private val vaultMutex = Mutex()

  private val mutableConfigFlow = MutableStateFlow(readConfigMetadataFromPrefs())

  /** Hot reactive stream of non-secret [CloudLlmConfig] state. */
  override val configFlow: StateFlow<CloudLlmConfig> = mutableConfigFlow.asStateFlow()

  /** Persists [mode] (`ON_DEVICE` vs `CLOUD_API`) and emits the refreshed [CloudLlmConfig]. */
  override suspend fun setExecutionMode(mode: AiExecutionMode): CloudLlmConfig =
    withVaultLock {
      preferences.edit().putString(PREF_EXECUTION_MODE, mode.name).commit()
      publishRefreshedConfigLocked()
    }

  /** Persists [provider] as the active cloud provider and emits its model and key metadata. */
  override suspend fun selectProvider(provider: CloudLlmProvider): CloudLlmConfig =
    withVaultLock {
      preferences.edit().putString(PREF_ACTIVE_PROVIDER, provider.name).commit()
      publishRefreshedConfigLocked()
    }

  /** Validates and persists [modelId] for [provider] after migrating deprecated IDs and checking safety. */
  override suspend fun setModelId(
    modelId: String,
    provider: CloudLlmProvider,
  ): CloudLlmConfig {
    val cleanModelId = modelId.trim()
    requireDomainValid(isValidCloudModelId(cleanModelId), "CloudLlmConfig") {
      "Invalid cloud model ID '$cleanModelId'"
    }
    val migratedModelId = migrateDeprecatedCloudModelId(provider, cleanModelId)
    return withVaultLock {
      preferences.edit().putString(modelIdPrefKey(provider), migratedModelId).commit()
      publishRefreshedConfigLocked()
    }
  }

  /**
   * Encrypts [rawApiKey] under `AndroidKeyStore` AES-256-GCM (`128`-bit tag) and persists only
   * `{ivBase64}:{ciphertextBase64}` plus a masked suffix preview (`"•••• 1234"`).
   */
  override suspend fun saveApiKey(
    provider: CloudLlmProvider,
    rawApiKey: String,
  ): CloudLlmConfig {
    val cleanKey = InMemoryCloudCredentialVault.validateApiKeyFormat(rawApiKey)
    val maskedPreview = maskApiKeyPreview(cleanKey)
    return withVaultLock {
      // Encrypt secret under AndroidKeyStore AES-256-GCM before committing preference transaction.
      val encryptedBlob = encryptApiKeyLocked(provider, cleanKey)
      // Store only "{ivBase64}:{ciphertextBase64}" + masked suffix preview; never store raw keys.
      preferences.edit()
        .putString(encryptedKeyPrefKey(provider), encryptedBlob)
        .putString(maskedPreviewPrefKey(provider), maskedPreview)
        .putString(PREF_ACTIVE_PROVIDER, provider.name)
        .commit()
      publishRefreshedConfigLocked()
    }
  }

  /**
   * Decrypts the `{ivBase64}:{ciphertextBase64}` entry for [provider] via `AndroidKeyStore`
   * AES-256-GCM, clearing corrupted ciphertext gracefully if the Keystore key was invalidated.
   */
  override suspend fun readApiKey(provider: CloudLlmProvider): String? =
    withVaultLock {
      val encodedBlob = preferences.getString(encryptedKeyPrefKey(provider), null)
        ?.takeIf { it.isNotBlank() }
        ?: return@withVaultLock null
      try {
        decryptApiKeyLocked(provider, encodedBlob)
      } catch (_: GeneralSecurityException) {
        // Keystore key rotation or tamper failure: purge unreadable ciphertext and refresh UI state.
        preferences.edit()
          .remove(encryptedKeyPrefKey(provider))
          .remove(maskedPreviewPrefKey(provider))
          .commit()
        publishRefreshedConfigLocked()
        null
      } catch (_: IllegalArgumentException) {
        preferences.edit()
          .remove(encryptedKeyPrefKey(provider))
          .remove(maskedPreviewPrefKey(provider))
          .commit()
        publishRefreshedConfigLocked()
        null
      }
    }

  /** Deletes the encrypted ciphertext and masked preview for [provider] from `SharedPreferences`. */
  override suspend fun clearApiKey(provider: CloudLlmProvider): CloudLlmConfig =
    withVaultLock {
      preferences.edit()
        .remove(encryptedKeyPrefKey(provider))
        .remove(maskedPreviewPrefKey(provider))
        .commit()
      publishRefreshedConfigLocked()
    }

  /** Persists live-synced non-deprecated [modelIds] merged with [provider]'s curated presets. */
  override suspend fun setAvailableModels(
    provider: CloudLlmProvider,
    modelIds: List<String>,
  ): CloudLlmConfig {
    val merged = InMemoryCloudCredentialVault.mergePresetAndLiveModels(provider, modelIds)
    return withVaultLock {
      preferences.edit()
        .putString(availableModelsPrefKey(provider), merged.joinToString("\n"))
        .commit()
      publishRefreshedConfigLocked()
    }
  }

  private suspend fun <T> withVaultLock(block: () -> T): T {
    try {
      return vaultMutex.withLock {
        withContext(ioDispatcher) { block() }
      }
    } catch (cancel: CancellationException) {
      throw cancel
    }
  }

  private fun publishRefreshedConfigLocked(): CloudLlmConfig {
    val refreshed = readConfigMetadataFromPrefs()
    mutableConfigFlow.value = refreshed
    return refreshed
  }

  // Hydrates non-secret CloudLlmConfig fields without decrypting any stored secrets.
  private fun readConfigMetadataFromPrefs(): CloudLlmConfig {
    val storedModeName = preferences.getString(PREF_EXECUTION_MODE, null)
    val resolvedMode = AiExecutionMode.entries.firstOrNull { it.name == storedModeName }
      ?: AiExecutionMode.ON_DEVICE

    val storedProviderName = preferences.getString(PREF_ACTIVE_PROVIDER, null)
    val resolvedProvider = CloudLlmProvider.entries.firstOrNull { it.name == storedProviderName }
      ?: CloudLlmProvider.GEMINI

    // Seamlessly migrate any legacy pre-late-2026 persisted modelId to its active replacement.
    val rawStoredModelId = preferences.getString(modelIdPrefKey(resolvedProvider), null)
      ?.trim()
      ?.takeIf(::isValidCloudModelId)
    val resolvedModelId = when (rawStoredModelId) {
      null -> resolvedProvider.defaultModelId
      else -> migrateDeprecatedCloudModelId(resolvedProvider, rawStoredModelId)
    }

    val storedSyncedModels = preferences.getString(availableModelsPrefKey(resolvedProvider), null)
      ?.lineSequence()
      ?.map { it.trim() }
      ?.filter { it.isNotEmpty() && !isDeprecatedCloudModelId(it) }
      ?.toList()
      .orEmpty()
    val resolvedAvailableModels = InMemoryCloudCredentialVault.mergePresetAndLiveModels(
      provider = resolvedProvider,
      liveModelIds = storedSyncedModels,
    )

    val configured = CloudLlmProvider.entries.filterTo(linkedSetOf()) { candidate ->
      hasValidCiphertextFormat(preferences.getString(encryptedKeyPrefKey(candidate), null))
    }

    val activeHasKey = resolvedProvider in configured
    val maskedPreview = when {
      activeHasKey -> preferences.getString(maskedPreviewPrefKey(resolvedProvider), null) ?: "••••••••"
      else -> null
    }

    return CloudLlmConfig(
      executionMode = resolvedMode,
      provider = resolvedProvider,
      modelId = resolvedModelId,
      hasStoredApiKey = activeHasKey,
      maskedKeyPreview = maskedPreview,
      configuredProviders = configured,
      availableModelIds = resolvedAvailableModels,
    )
  }

  // Encrypts plaintext bytes with hardware-backed AES-256-GCM and provider-bound AAD.
  private fun encryptApiKeyLocked(provider: CloudLlmProvider, cleanKey: String): String {
    val secretKey = getOrCreateAesKeyLocked()
    val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
    // AndroidKeyStore generates a fresh random 96-bit GCM IV per encryption operation.
    cipher.init(Cipher.ENCRYPT_MODE, secretKey)
    cipher.updateAAD(provider.name.toByteArray(Charsets.UTF_8))

    val plaintextBytes = cleanKey.toByteArray(Charsets.UTF_8)
    val cipherBytes = try {
      cipher.doFinal(plaintextBytes)
    } finally {
      // Zero transient plaintext byte buffer immediately after encryption completes.
      plaintextBytes.fill(0)
    }
    val ivBytes = cipher.iv
    requireDomainValid(ivBytes != null && ivBytes.isNotEmpty(), "KeystoreVault") {
      "AndroidKeyStore cipher failed to emit a GCM initialization vector"
    }
    val encoder = Base64.getEncoder()
    return "${encoder.encodeToString(ivBytes)}:${encoder.encodeToString(cipherBytes)}"
  }

  // Verifies 128-bit GCM auth tag and provider AAD before returning the decrypted API key.
  private fun decryptApiKeyLocked(provider: CloudLlmProvider, encodedBlob: String): String {
    val parts = encodedBlob.split(':', limit = 2)
    require(parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
      "Malformed encrypted credential record"
    }
    val decoder = Base64.getDecoder()
    val ivBytes = decoder.decode(parts[0])
    val cipherBytes = decoder.decode(parts[1])

    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }
    val secretKey = keyStore.getKey(KEYSTORE_KEY_ALIAS, null) as? SecretKey
      ?: throw GeneralSecurityException("Missing Keystore key '$KEYSTORE_KEY_ALIAS'")

    // Bind provider.name as GCM Additional Authenticated Data so cross-provider swaps fail auth.
    val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_AUTH_TAG_BITS, ivBytes))
    cipher.updateAAD(provider.name.toByteArray(Charsets.UTF_8))

    val decryptedBytes = cipher.doFinal(cipherBytes)
    return try {
      String(decryptedBytes, Charsets.UTF_8)
    } finally {
      // Zero transient decrypted byte array as soon as the UTF-8 String is materialized.
      decryptedBytes.fill(0)
    }
  }

  // Lazily provisions a non-exportable 256-bit AES-GCM master key inside AndroidKeyStore.
  private fun getOrCreateAesKeyLocked(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }
    val existing = keyStore.getKey(KEYSTORE_KEY_ALIAS, null) as? SecretKey
    if (existing != null) return existing

    val keyGenerator = KeyGenerator.getInstance(
      KeyProperties.KEY_ALGORITHM_AES,
      ANDROID_KEYSTORE_PROVIDER,
    )
    val spec = KeyGenParameterSpec.Builder(
      KEYSTORE_KEY_ALIAS,
      KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
    )
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setKeySize(AES_KEY_SIZE_BITS)
      .setRandomizedEncryptionRequired(true)
      .build()
    keyGenerator.init(spec)
    return keyGenerator.generateKey()
  }

  private fun hasValidCiphertextFormat(rawBlob: String?): Boolean {
    if (rawBlob.isNullOrBlank()) return false
    val colonIndex = rawBlob.indexOf(':')
    return colonIndex > 0 && colonIndex < rawBlob.lastIndex
  }

  private fun modelIdPrefKey(provider: CloudLlmProvider): String = "model_id_${provider.name}"

  private fun availableModelsPrefKey(provider: CloudLlmProvider): String =
    "available_models_${provider.name}"

  private fun encryptedKeyPrefKey(provider: CloudLlmProvider): String = "enc_api_key_${provider.name}"

  private fun maskedPreviewPrefKey(provider: CloudLlmProvider): String =
    "masked_preview_${provider.name}"

  companion object {
    /** Canonical Android hardware-backed KeyStore provider name. */
    const val ANDROID_KEYSTORE_PROVIDER: String = "AndroidKeyStore"

    /** Non-exportable 256-bit AES-GCM key alias inside `AndroidKeyStore`. */
    const val KEYSTORE_KEY_ALIAS: String = "savor_cloud_llm_aes256_v1"

    /** Authenticated AEAD cipher transformation used for API key encryption at rest. */
    const val CIPHER_TRANSFORMATION: String = "AES/GCM/NoPadding"

    /** Symmetric AES key length in bits. */
    const val AES_KEY_SIZE_BITS: Int = 256

    /** Authenticated GCM tag length in bits. */
    const val GCM_AUTH_TAG_BITS: Int = 128

    /** App-private `MODE_PRIVATE` `SharedPreferences` filename storing encrypted blobs. */
    const val PREFS_FILE_NAME: String = "savor_cloud_ai_vault_v1"

    private const val PREF_EXECUTION_MODE: String = "execution_mode"
    private const val PREF_ACTIVE_PROVIDER: String = "active_provider"
  }
}
