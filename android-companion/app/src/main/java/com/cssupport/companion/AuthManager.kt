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

    init {
        prefs = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            // Samsung devices and certain OEMs can throw KeyStoreException or
            // GeneralSecurityException when the Android Keystore is in a bad state.
            // Fall back to regular SharedPreferences so the app doesn't crash.
            Log.e(tag, "EncryptedSharedPreferences failed, falling back to plain prefs", e)
            context.getSharedPreferences(PREFS_FILE_NAME + "_fallback", Context.MODE_PRIVATE)
        }
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
        prefs.edit()
            .putString(KEY_LLM_PROVIDER, provider.name)
            .putString(KEY_LLM_API_KEY, apiKey)
            .putString(KEY_LLM_MODEL, model)
            .putString(KEY_LLM_ENDPOINT, endpoint)
            .putString(KEY_LLM_API_VERSION, apiVersion)
            .apply()

        Log.d(tag, "Saved LLM credentials for provider=${provider.name}, model=$model")
    }

    /**
     * Load saved LLM config, or null if not configured.
     */
    fun loadLLMConfig(): LLMConfig? {
        val providerName = prefs.getString(KEY_LLM_PROVIDER, null) ?: return null
        val apiKey = prefs.getString(KEY_LLM_API_KEY, null) ?: return null
        val model = prefs.getString(KEY_LLM_MODEL, null) ?: return null

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
            endpoint = prefs.getString(KEY_LLM_ENDPOINT, null),
            apiVersion = prefs.getString(KEY_LLM_API_VERSION, null),
        )
    }

    /**
     * Check if LLM credentials are configured.
     */
    fun hasLLMCredentials(): Boolean {
        return prefs.getString(KEY_LLM_API_KEY, null) != null
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
    }

    /**
     * Load the saved OAuth access token, or null if not set or expired.
     */
    fun loadOAuthToken(): OAuthToken? {
        val accessToken = prefs.getString(KEY_OAUTH_ACCESS_TOKEN, null) ?: return null
        val refreshToken = prefs.getString(KEY_OAUTH_REFRESH_TOKEN, null)
        val expiresAt = prefs.getLong(KEY_OAUTH_EXPIRES_AT, 0L)

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
    }

    // ── General ─────────────────────────────────────────────────────────────

    /**
     * Clear all stored credentials.
     */
    fun clearAll() {
        prefs.edit().clear().apply()
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
