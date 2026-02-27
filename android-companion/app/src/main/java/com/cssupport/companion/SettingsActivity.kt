package com.cssupport.companion

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings screen for configuring:
 * - LLM provider: two cards -- ChatGPT (OAuth) and API Key
 * - Accessibility service status
 * - Auto-approve toggle
 * - Case history (placeholder)
 * - Version info
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var authManager: AuthManager

    /** Masked placeholder shown when an API key is saved but not displayed for security. */
    private var maskedApiKeyPlaceholder: String? = null

    // OAuth card
    private lateinit var oauthCard: MaterialCardView
    private lateinit var oauthStatusDot: View
    private lateinit var oauthStatusText: TextView
    private lateinit var btnOAuthAction: MaterialButton

    // API Key card
    private lateinit var apiKeyCard: MaterialCardView
    private lateinit var apiKeyStatusDot: View
    private lateinit var apiKeyStatusText: TextView
    private lateinit var btnConfigureApi: MaterialButton
    private lateinit var apiKeySection: LinearLayout
    private lateinit var providerDropdown: MaterialAutoCompleteTextView
    private lateinit var apiEndpointInput: TextInputEditText
    private lateinit var apiKeyInput: TextInputEditText
    private lateinit var apiModelInput: TextInputEditText
    private lateinit var btnSaveApiConfig: MaterialButton

    // Accessibility
    private lateinit var accessibilityStatusDot: View
    private lateinit var accessibilityStatusText: TextView

    // Auto-approve
    private lateinit var autoApproveSwitch: MaterialSwitch

    // Version
    private lateinit var versionText: TextView

    /** PKCE verifier and state persisted to survive process death. */
    private val oauthPrefs by lazy {
        getSharedPreferences(OAUTH_PREFS, MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        authManager = AuthManager(this)

        // Top bar back button.
        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { finish() }

        bindViews()
        setupProviderDropdown()
        setupListeners()

        // Allow ADB configuration via intent extras (debug only).
        handleConfigIntent(intent)

        refreshUI()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleConfigIntent(intent)
        refreshUI()
    }

    private fun handleConfigIntent(intent: Intent) {
        // Only allow intent-based config injection in debug builds.
        if (!BuildConfig.DEBUG) return

        // Accept both naming conventions for ADB injection.
        val provider = intent.getStringExtra("llm_provider")
            ?: intent.getStringExtra("debug_provider") ?: return
        val apiKey = intent.getStringExtra("llm_api_key")
            ?: intent.getStringExtra("debug_key") ?: return
        val model = intent.getStringExtra("llm_model")
            ?: intent.getStringExtra("debug_model") ?: return
        val endpoint = intent.getStringExtra("llm_endpoint")
            ?: intent.getStringExtra("debug_endpoint")

        val llmProvider = when (provider.lowercase()) {
            "azure", "azure openai", "azure_openai" -> LLMProvider.AZURE_OPENAI
            "openai" -> LLMProvider.OPENAI
            "anthropic" -> LLMProvider.ANTHROPIC
            else -> LLMProvider.CUSTOM
        }

        authManager.saveLLMCredentials(
            provider = llmProvider,
            apiKey = apiKey,
            model = model,
            endpoint = endpoint,
        )

        Log.i(TAG, "Config injected via intent: provider=$provider, model=$model")
    }

    override fun onResume() {
        super.onResume()
        // Refresh the full UI — if OAuth completed while the Custom Tab was
        // in the foreground, the auth cards need to update when the user returns.
        refreshUI()
    }

    private fun bindViews() {
        // OAuth card
        oauthCard = findViewById(R.id.oauthCard)
        oauthStatusDot = findViewById(R.id.oauthStatusDot)
        oauthStatusText = findViewById(R.id.oauthStatusText)
        btnOAuthAction = findViewById(R.id.btnOAuthAction)

        // API Key card
        apiKeyCard = findViewById(R.id.apiKeyCard)
        apiKeyStatusDot = findViewById(R.id.apiKeyStatusDot)
        apiKeyStatusText = findViewById(R.id.apiKeyStatusText)
        btnConfigureApi = findViewById(R.id.btnConfigureApi)
        apiKeySection = findViewById(R.id.apiKeySection)
        providerDropdown = findViewById(R.id.providerDropdown)
        apiEndpointInput = findViewById(R.id.apiEndpointInput)
        apiKeyInput = findViewById(R.id.apiKeyInput)
        apiModelInput = findViewById(R.id.apiModelInput)
        btnSaveApiConfig = findViewById(R.id.btnSaveApiConfig)

        // Other sections
        accessibilityStatusDot = findViewById(R.id.accessibilityStatusDot)
        accessibilityStatusText = findViewById(R.id.accessibilityStatusText)
        autoApproveSwitch = findViewById(R.id.autoApproveSwitch)
        versionText = findViewById(R.id.versionText)
    }

    private fun setupProviderDropdown() {
        val providers = resources.getStringArray(R.array.settings_provider_list)
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, providers)
        providerDropdown.setAdapter(adapter)
    }

    private fun setupListeners() {
        // OAuth card action button.
        btnOAuthAction.setOnClickListener {
            if (authManager.isOAuthTokenValid()) {
                // Disconnect OAuth.
                authManager.clearOAuthTokens()
                // Also clear LLM credentials if they were OAuth-sourced.
                val config = authManager.loadLLMConfig()
                if (config?.provider == LLMProvider.OPENAI) {
                    authManager.clearLLMCredentials()
                }
                Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show()
                refreshUI()
            } else {
                // Start OAuth flow.
                launchOAuthFlow()
            }
        }

        // API Key card configure button.
        btnConfigureApi.setOnClickListener {
            val isVisible = apiKeySection.visibility == View.VISIBLE
            apiKeySection.visibility = if (isVisible) View.GONE else View.VISIBLE
            btnConfigureApi.text = if (isVisible) {
                getString(R.string.settings_configure_api)
            } else {
                getString(R.string.settings_hide_form)
            }
        }

        btnSaveApiConfig.setOnClickListener { saveApiConfig() }

        findViewById<MaterialButton>(R.id.btnSignOut).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setMessage(getString(R.string.settings_sign_out_confirm))
                .setPositiveButton(getString(R.string.settings_sign_out)) { _, _ ->
                    authManager.clearAll()
                    // Navigate to WelcomeActivity and clear back stack.
                    val intent = Intent(this, WelcomeActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                    finish()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        findViewById<LinearLayout>(R.id.accessibilityRow).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Auto-approve preference.
        autoApproveSwitch.isChecked = getSharedPreferences("resolve_prefs", MODE_PRIVATE)
            .getBoolean("auto_approve", false)
        autoApproveSwitch.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences("resolve_prefs", MODE_PRIVATE)
                .edit().putBoolean("auto_approve", isChecked).apply()
        }
    }

    // ── OAuth Flow ──────────────────────────────────────────────────────────

    private fun launchOAuthFlow() {
        val codeVerifier = ChatGPTOAuth.generateCodeVerifier()
        val codeChallenge = ChatGPTOAuth.generateCodeChallenge(codeVerifier)
        val state = ChatGPTOAuth.generateState()

        oauthPrefs.edit()
            .putString(KEY_CODE_VERIFIER, codeVerifier)
            .putString(KEY_EXPECTED_STATE, state)
            .apply()

        btnOAuthAction.isEnabled = false
        btnOAuthAction.text = getString(R.string.oauth_loading)

        lifecycleScope.launch {
            val callbackJob = launch {
                val result = ChatGPTOAuth.startCallbackServer()

                if (result == null) {
                    Log.w(TAG, "Callback server returned null (timeout or error)")
                    resetOAuthButton()
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.oauth_error),
                        Toast.LENGTH_LONG,
                    ).show()
                    clearOAuthPrefs()
                    return@launch
                }

                val expectedState = oauthPrefs.getString(KEY_EXPECTED_STATE, null)
                if (result.state != expectedState) {
                    Log.w(TAG, "OAuth state mismatch")
                    resetOAuthButton()
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.oauth_state_mismatch),
                        Toast.LENGTH_LONG,
                    ).show()
                    clearOAuthPrefs()
                    return@launch
                }

                val verifier = oauthPrefs.getString(KEY_CODE_VERIFIER, null)
                if (verifier.isNullOrBlank()) {
                    Log.w(TAG, "Callback received but no code_verifier saved")
                    resetOAuthButton()
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.oauth_error),
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }

                exchangeCodeForTokens(result.code, verifier)
            }

            delay(100)

            val authUrl = ChatGPTOAuth.buildAuthorizationUrl(codeChallenge, state)
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            customTabsIntent.launchUrl(this@SettingsActivity, Uri.parse(authUrl))
        }
    }

    private fun exchangeCodeForTokens(code: String, codeVerifier: String) {
        lifecycleScope.launch {
            try {
                val response = ChatGPTOAuth.exchangeCodeForTokens(code, codeVerifier)

                // Clear any existing API key config when switching to OAuth.
                authManager.clearLLMCredentials()

                authManager.saveOAuthToken(
                    accessToken = response.accessToken,
                    refreshToken = response.refreshToken,
                    expiresAtMs = System.currentTimeMillis() + (response.expiresIn * 1000),
                )

                authManager.saveLLMCredentials(
                    provider = LLMProvider.OPENAI,
                    apiKey = response.accessToken,
                    model = ChatGPTOAuth.DEFAULT_MODEL,
                )

                clearOAuthPrefs()

                // Don't try to bring the app to the foreground here —
                // FLAG_ACTIVITY_REORDER_TO_FRONT is blocked on Samsung.
                // The user taps "Return to Resolve" in the Custom Tab,
                // which triggers OAuthReturnActivity → finishes → reveals us.
                // Our onResume() calls refreshUI() which updates the cards.
            } catch (e: Exception) {
                Log.e(TAG, "Token exchange failed", e)
                resetOAuthButton()
                Toast.makeText(
                    this@SettingsActivity,
                    getString(R.string.oauth_error),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun resetOAuthButton() {
        btnOAuthAction.isEnabled = true
        btnOAuthAction.text = getString(R.string.settings_oauth_sign_in)
    }

    private fun clearOAuthPrefs() {
        oauthPrefs.edit()
            .remove(KEY_CODE_VERIFIER)
            .remove(KEY_EXPECTED_STATE)
            .apply()
    }

    // ── UI Refresh ──────────────────────────────────────────────────────────

    private fun refreshUI() {
        val oauthConnected = authManager.isOAuthTokenValid()
        val config = authManager.loadLLMConfig()
        // API key is configured if we have credentials that are NOT from OAuth.
        val apiKeyConfigured = config != null && !oauthConnected

        // ── OAuth card ──
        oauthStatusDot.setBackgroundResource(
            if (oauthConnected) R.drawable.bg_status_dot_success
            else R.drawable.bg_status_dot_error,
        )
        oauthStatusText.text = if (oauthConnected) {
            getString(R.string.settings_oauth_connected)
        } else {
            getString(R.string.settings_oauth_not_connected)
        }
        btnOAuthAction.text = if (oauthConnected) {
            getString(R.string.settings_oauth_disconnect)
        } else {
            getString(R.string.settings_oauth_sign_in)
        }
        btnOAuthAction.isEnabled = true
        oauthCard.strokeWidth = if (oauthConnected) dpToPx(2) else 0
        oauthCard.strokeColor = if (oauthConnected) getColorAttr(com.google.android.material.R.attr.colorPrimary) else 0

        // ── API Key card ──
        apiKeyStatusDot.setBackgroundResource(
            if (apiKeyConfigured) R.drawable.bg_status_dot_success
            else R.drawable.bg_status_dot_error,
        )
        apiKeyStatusText.text = if (apiKeyConfigured) {
            when (config!!.provider) {
                LLMProvider.AZURE_OPENAI -> "Azure (${config.model})"
                LLMProvider.OPENAI -> "OpenAI (${config.model})"
                LLMProvider.ANTHROPIC -> "Anthropic (${config.model})"
                LLMProvider.CUSTOM -> "Custom (${config.model})"
            }
        } else {
            getString(R.string.settings_api_not_configured)
        }
        apiKeyCard.strokeWidth = if (apiKeyConfigured) dpToPx(2) else 0
        apiKeyCard.strokeColor = if (apiKeyConfigured) getColorAttr(com.google.android.material.R.attr.colorPrimary) else 0

        // Pre-fill API key form fields if config exists.
        if (config != null && !oauthConnected) {
            providerDropdown.setText(
                when (config.provider) {
                    LLMProvider.AZURE_OPENAI -> "Azure OpenAI"
                    LLMProvider.OPENAI -> "OpenAI"
                    LLMProvider.ANTHROPIC -> "Anthropic"
                    LLMProvider.CUSTOM -> "Custom"
                },
                false,
            )
            apiEndpointInput.setText(config.endpoint ?: "")
            apiModelInput.setText(config.model)

            val savedKey = authManager.getSavedApiKey()
            if (savedKey != null && savedKey.length >= 4) {
                val lastFour = savedKey.takeLast(4)
                maskedApiKeyPlaceholder = "••••••••$lastFour"
                apiKeyInput.setText(maskedApiKeyPlaceholder)
            } else {
                maskedApiKeyPlaceholder = null
            }
        } else if (!apiKeyConfigured) {
            maskedApiKeyPlaceholder = null
        }

        // If nothing is configured at all, expand the API key form by default.
        if (!oauthConnected && !apiKeyConfigured) {
            apiKeySection.visibility = View.VISIBLE
            btnConfigureApi.text = getString(R.string.settings_hide_form)
        }

        refreshAccessibilityStatus()

        // Version.
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: PackageManager.NameNotFoundException) {
            "unknown"
        }
        versionText.text = getString(R.string.settings_version, versionName)
    }

    private fun refreshAccessibilityStatus() {
        val running = SupportAccessibilityService.isRunning()
        accessibilityStatusDot.setBackgroundResource(
            if (running) R.drawable.bg_status_dot_success
            else R.drawable.bg_status_dot_error,
        )
        accessibilityStatusText.text = if (running) {
            getString(R.string.settings_accessibility_enabled)
        } else {
            getString(R.string.settings_accessibility_disabled)
        }
    }

    private fun saveApiConfig() {
        val providerText = providerDropdown.text.toString()
        val rawKey = apiKeyInput.text?.toString()?.trim().orEmpty()
        val model = apiModelInput.text?.toString()?.trim().orEmpty()
        val endpoint = apiEndpointInput.text?.toString()?.trim().orEmpty()

        // If the key field still shows the masked placeholder, preserve the existing key.
        val key: String
        if (rawKey == maskedApiKeyPlaceholder || rawKey.isBlank()) {
            val existingKey = authManager.getSavedApiKey()
            if (existingKey != null) {
                key = existingKey
            } else {
                Toast.makeText(this, "Enter a valid API key", Toast.LENGTH_SHORT).show()
                return
            }
        } else {
            if (rawKey.length < 4) {
                Toast.makeText(this, "Enter a valid API key", Toast.LENGTH_SHORT).show()
                return
            }
            key = rawKey
        }

        if (model.isBlank()) {
            Toast.makeText(this, "Enter a model name", Toast.LENGTH_SHORT).show()
            return
        }

        val provider = when (providerText) {
            "Azure OpenAI" -> LLMProvider.AZURE_OPENAI
            "OpenAI" -> LLMProvider.OPENAI
            "Anthropic" -> LLMProvider.ANTHROPIC
            else -> LLMProvider.CUSTOM
        }

        if (provider == LLMProvider.AZURE_OPENAI && endpoint.isBlank()) {
            Toast.makeText(this, "Azure OpenAI requires an endpoint URL", Toast.LENGTH_SHORT).show()
            return
        }
        if (provider == LLMProvider.CUSTOM && endpoint.isBlank()) {
            Toast.makeText(this, "Custom provider requires an endpoint URL", Toast.LENGTH_SHORT).show()
            return
        }

        // Clear OAuth tokens when switching to API key auth.
        authManager.clearOAuthTokens()

        authManager.saveLLMCredentials(
            provider = provider,
            apiKey = key,
            model = model,
            endpoint = endpoint.ifBlank { null },
        )

        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        apiKeySection.visibility = View.GONE
        btnConfigureApi.text = getString(R.string.settings_configure_api)
        refreshUI()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun getColorAttr(attr: Int): Int {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    companion object {
        private const val TAG = "SettingsActivity"
        private const val OAUTH_PREFS = "resolve_oauth_flow"
        private const val KEY_CODE_VERIFIER = "pkce_code_verifier"
        private const val KEY_EXPECTED_STATE = "pkce_expected_state"
    }
}
