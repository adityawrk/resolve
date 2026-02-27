package com.cssupport.companion

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure credential storage for LLM API keys and authentication tokens.
 *
 * Uses [EncryptedSharedPreferences] backed by Android Keystore for at-rest encryption.
 * Supports two auth paths:
 * - Bring your own API key (user enters provider + key + model)
 * - OAuth token storage (for future ChatGPT OAuth flow)
 */
class AuthManager(context: Context) {

    private val tag = "AuthManager"
    private val prefs: SharedPreferences

    /**
     * Plain-text backup prefs that always work, even when EncryptedSharedPreferences
     * fails after an app update (Samsung Keystore issues). Credentials are written to
     * BOTH encrypted and backup prefs; on read, encrypted is tried first, then backup.
     * This ensures API keys survive across app updates regardless of Keystore state.
     */
    private val backupPrefs: SharedPreferences

    /** Whether the primary prefs are encrypted (vs. fallback). */
    private val usingEncrypted: Boolean

    init {
        backupPrefs = context.getSharedPreferences(PREFS_FILE_NAME + "_backup", Context.MODE_PRIVATE)

        var encrypted: SharedPreferences? = null
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            encrypted = EncryptedSharedPreferences.create(
                context,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            // Samsung devices and certain OEMs can throw KeyStoreException or
            // GeneralSecurityException when the Android Keystore is in a bad state.
            Log.e(tag, "EncryptedSharedPreferences failed, using backup prefs", e)
        }

        if (encrypted != null) {
            prefs = encrypted
            usingEncrypted = true

            // Migrate: if encrypted prefs are empty but backup has credentials, copy them over.
            if (encrypted.getString(KEY_LLM_API_KEY, null) == null
                && backupPrefs.getString(KEY_LLM_API_KEY, null) != null
            ) {
                Log.i(tag, "Restoring credentials from backup to encrypted prefs")
                migratePrefs(from = backupPrefs, to = encrypted)
            }
        } else {
            prefs = backupPrefs
            usingEncrypted = false
        }
    }

    /**
     * Copy all credential keys from one SharedPreferences to another.
     */
    private fun migratePrefs(from: SharedPreferences, to: SharedPreferences) {
        val editor = to.edit()
        ALL_KEYS.forEach { key ->
            val value = from.getString(key, null)
            if (value != null) editor.putString(key, value)
        }
        // Also migrate OAuth long value.
        val expiresAt = from.getLong(KEY_OAUTH_EXPIRES_AT, 0L)
        if (expiresAt > 0L) editor.putLong(KEY_OAUTH_EXPIRES_AT, expiresAt)
        editor.apply()
    }

    // ── LLM API Key auth ────────────────────────────────────────────────────

    /**
     * Save LLM credentials for the "bring your own key" flow.
     */
    fun saveLLMCredentials(
        provider: LLMProvider,
        apiKey: String,
        model: String,
        endpoint: String? = null,
        apiVersion: String? = null,
    ) {
        // Write to primary prefs.
        prefs.edit()
            .putString(KEY_LLM_PROVIDER, provider.name)
            .putString(KEY_LLM_API_KEY, apiKey)
            .putString(KEY_LLM_MODEL, model)
            .putString(KEY_LLM_ENDPOINT, endpoint)
            .putString(KEY_LLM_API_VERSION, apiVersion)
            .apply()

        // Always mirror to backup prefs so credentials survive app updates
        // even if EncryptedSharedPreferences breaks (Samsung Keystore).
        if (usingEncrypted) {
            backupPrefs.edit()
                .putString(KEY_LLM_PROVIDER, provider.name)
                .putString(KEY_LLM_API_KEY, apiKey)
                .putString(KEY_LLM_MODEL, model)
                .putString(KEY_LLM_ENDPOINT, endpoint)
                .putString(KEY_LLM_API_VERSION, apiVersion)
                .apply()
        }

        Log.d(tag, "Saved LLM credentials for provider=${provider.name}, model=$model")
    }

    /**
     * Load saved LLM config, or null if not configured.
     *
     * Defensive: wraps encrypted pref reads in try-catch because Samsung Keystore
     * can corrupt EncryptedSharedPreferences at any time, causing getString() to
     * throw instead of returning null. Without this, the backup fallback is bypassed.
     */
    fun loadLLMConfig(): LLMConfig? {
        // Try primary prefs first (with try-catch for Samsung Keystore crashes).
        val fromPrimary = try {
            loadLLMConfigFrom(prefs)
        } catch (e: Exception) {
            Log.e(tag, "Encrypted prefs read failed (Samsung Keystore?): ${e.message}")
            null
        }
        if (fromPrimary != null) return fromPrimary.withOAuthFlag()

        // Fallback to backup prefs.
        val fromBackup = try {
            loadLLMConfigFrom(backupPrefs)
        } catch (e: Exception) {
            Log.e(tag, "Backup prefs read also failed: ${e.message}")
            null
        }
        if (fromBackup != null) {
            Log.i(tag, "Loaded credentials from backup prefs (primary was empty/broken)")
            // Re-sync to primary so next load is faster.
            try { migratePrefs(from = backupPrefs, to = prefs) } catch (_: Exception) {}
        }
        return fromBackup?.withOAuthFlag()
    }

    /**
     * If the user authenticated via OAuth, flag the config to use the Responses API.
     * Codex OAuth tokens only work with /v1/responses, not /v1/chat/completions.
     */
    private fun LLMConfig.withOAuthFlag(): LLMConfig {
        if (provider == LLMProvider.OPENAI && isOAuthTokenValid()) {
            return copy(useResponsesApi = true)
        }
        return this
    }

    private fun loadLLMConfigFrom(source: SharedPreferences): LLMConfig? {
        val providerName = source.getString(KEY_LLM_PROVIDER, null) ?: return null
        val apiKey = source.getString(KEY_LLM_API_KEY, null) ?: return null
        val model = source.getString(KEY_LLM_MODEL, null) ?: return null

        val provider = try {
            LLMProvider.valueOf(providerName)
        } catch (_: Exception) {
            Log.w(tag, "Unknown provider: $providerName")
            return null
        }

        return LLMConfig(
            provider = provider,
            apiKey = apiKey,
            model = model,
            endpoint = source.getString(KEY_LLM_ENDPOINT, null),
            apiVersion = source.getString(KEY_LLM_API_VERSION, null),
        )
    }

    /**
     * Get the saved API key (for showing a masked version in Settings UI).
     * Returns null if no key is stored.
     */
    fun getSavedApiKey(): String? {
        return try {
            prefs.getString(KEY_LLM_API_KEY, null)
        } catch (_: Exception) {
            null
        } ?: try {
            backupPrefs.getString(KEY_LLM_API_KEY, null)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Check if LLM credentials are configured.
     * Defensive try-catch for Samsung Keystore corruption.
     */
    fun hasLLMCredentials(): Boolean {
        val fromPrimary = try {
            prefs.getString(KEY_LLM_API_KEY, null) != null
        } catch (_: Exception) {
            false
        }
        if (fromPrimary) return true

        return try {
            backupPrefs.getString(KEY_LLM_API_KEY, null) != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Clear all LLM credentials.
     */
    fun clearLLMCredentials() {
        prefs.edit()
            .remove(KEY_LLM_PROVIDER)
            .remove(KEY_LLM_API_KEY)
            .remove(KEY_LLM_MODEL)
            .remove(KEY_LLM_ENDPOINT)
            .remove(KEY_LLM_API_VERSION)
            .apply()
        backupPrefs.edit()
            .remove(KEY_LLM_PROVIDER)
            .remove(KEY_LLM_API_KEY)
            .remove(KEY_LLM_MODEL)
            .remove(KEY_LLM_ENDPOINT)
            .remove(KEY_LLM_API_VERSION)
            .apply()
    }

    // ── OAuth token storage (for future ChatGPT OAuth flow) ─────────────────

    /**
     * Save an OAuth access token (e.g., from ChatGPT OAuth flow).
     */
    fun saveOAuthToken(accessToken: String, refreshToken: String?, expiresAtMs: Long) {
        prefs.edit()
            .putString(KEY_OAUTH_ACCESS_TOKEN, accessToken)
            .putString(KEY_OAUTH_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_OAUTH_EXPIRES_AT, expiresAtMs)
            .apply()
        if (usingEncrypted) {
            backupPrefs.edit()
                .putString(KEY_OAUTH_ACCESS_TOKEN, accessToken)
                .putString(KEY_OAUTH_REFRESH_TOKEN, refreshToken)
                .putLong(KEY_OAUTH_EXPIRES_AT, expiresAtMs)
                .apply()
        }
    }

    /**
     * Load the saved OAuth access token, or null if not set or expired.
     */
    fun loadOAuthToken(): OAuthToken? {
        return loadOAuthTokenFrom(prefs) ?: loadOAuthTokenFrom(backupPrefs)
    }

    private fun loadOAuthTokenFrom(source: SharedPreferences): OAuthToken? {
        val accessToken = source.getString(KEY_OAUTH_ACCESS_TOKEN, null) ?: return null
        val refreshToken = source.getString(KEY_OAUTH_REFRESH_TOKEN, null)
        val expiresAt = source.getLong(KEY_OAUTH_EXPIRES_AT, 0L)

        return OAuthToken(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtMs = expiresAt,
        )
    }

    /**
     * Check if the OAuth token is valid (exists and not expired).
     */
    fun isOAuthTokenValid(): Boolean {
        val token = loadOAuthToken() ?: return false
        return token.expiresAtMs > System.currentTimeMillis()
    }

    /**
     * Check if the OAuth token needs refreshing (expired or about to expire in 5 min).
     */
    fun needsOAuthRefresh(): Boolean {
        val token = loadOAuthToken() ?: return false
        // Refresh if expiring within 5 minutes
        return token.expiresAtMs < System.currentTimeMillis() + 300_000L
    }

    /**
     * Refresh the OAuth access token and update stored credentials.
     * Returns true on success, false on failure.
     */
    suspend fun refreshOAuthTokenIfNeeded(): Boolean {
        if (!needsOAuthRefresh()) return true // No refresh needed

        val currentToken = loadOAuthToken() ?: return false
        val refreshToken = currentToken.refreshToken ?: return false

        return try {
            val response = ChatGPTOAuth.refreshAccessToken(refreshToken)

            // Save new OAuth token
            saveOAuthToken(
                accessToken = response.accessToken,
                refreshToken = response.refreshToken ?: refreshToken,
                expiresAtMs = System.currentTimeMillis() + (response.expiresIn * 1000),
            )

            // Update LLM credentials with new access token
            saveLLMCredentials(
                provider = LLMProvider.OPENAI,
                apiKey = response.accessToken,
                model = ChatGPTOAuth.DEFAULT_MODEL,
            )

            Log.d(tag, "OAuth token refreshed successfully")
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to refresh OAuth token", e)
            false
        }
    }

    /**
     * Clear all OAuth tokens.
     */
    fun clearOAuthTokens() {
        prefs.edit()
            .remove(KEY_OAUTH_ACCESS_TOKEN)
            .remove(KEY_OAUTH_REFRESH_TOKEN)
            .remove(KEY_OAUTH_EXPIRES_AT)
            .apply()
        backupPrefs.edit()
            .remove(KEY_OAUTH_ACCESS_TOKEN)
            .remove(KEY_OAUTH_REFRESH_TOKEN)
            .remove(KEY_OAUTH_EXPIRES_AT)
            .apply()
    }

    // ── General ─────────────────────────────────────────────────────────────

    /**
     * Clear all stored credentials.
     */
    fun clearAll() {
        prefs.edit().clear().apply()
        backupPrefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_FILE_NAME = "resolve_secure_prefs"

        // LLM API key storage keys.
        private const val KEY_LLM_PROVIDER = "llm_provider"
        private const val KEY_LLM_API_KEY = "llm_api_key"
        private const val KEY_LLM_MODEL = "llm_model"
        private const val KEY_LLM_ENDPOINT = "llm_endpoint"
        private const val KEY_LLM_API_VERSION = "llm_api_version"

        // OAuth token storage keys.
        private const val KEY_OAUTH_ACCESS_TOKEN = "oauth_access_token"
        private const val KEY_OAUTH_REFRESH_TOKEN = "oauth_refresh_token"
        private const val KEY_OAUTH_EXPIRES_AT = "oauth_expires_at"

        // All string keys (used for backup migration).
        private val ALL_KEYS = listOf(
            KEY_LLM_PROVIDER, KEY_LLM_API_KEY, KEY_LLM_MODEL,
            KEY_LLM_ENDPOINT, KEY_LLM_API_VERSION,
            KEY_OAUTH_ACCESS_TOKEN, KEY_OAUTH_REFRESH_TOKEN,
        )
    }
}

data class OAuthToken(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMs: Long,
) {
    val isExpired: Boolean
        get() = expiresAtMs <= System.currentTimeMillis()
}
