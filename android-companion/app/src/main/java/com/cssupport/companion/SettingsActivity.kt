package com.cssupport.companion

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText

/**
 * Settings screen for configuring:
 * - LLM provider (switch auth method, enter API keys)
 * - Accessibility service status
 * - Auto-approve toggle
 * - Case history (placeholder)
 * - Version info
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var authManager: AuthManager

    /** Masked placeholder shown when an API key is saved but not displayed for security. */
    private var maskedApiKeyPlaceholder: String? = null

    // Provider section
    private lateinit var currentProviderText: TextView
    private lateinit var btnSwitchAuth: MaterialButton
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

        android.util.Log.i("SettingsActivity", "Config injected via intent: provider=$provider, model=$model")
    }

    override fun onResume() {
        super.onResume()
        refreshAccessibilityStatus()
    }

    private fun bindViews() {
        currentProviderText = findViewById(R.id.currentProviderText)
        btnSwitchAuth = findViewById(R.id.btnSwitchAuth)
        apiKeySection = findViewById(R.id.apiKeySection)
        providerDropdown = findViewById(R.id.providerDropdown)
        apiEndpointInput = findViewById(R.id.apiEndpointInput)
        apiKeyInput = findViewById(R.id.apiKeyInput)
        apiModelInput = findViewById(R.id.apiModelInput)
        btnSaveApiConfig = findViewById(R.id.btnSaveApiConfig)
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
        btnSwitchAuth.setOnClickListener {
            val isVisible = apiKeySection.visibility == View.VISIBLE
            apiKeySection.visibility = if (isVisible) View.GONE else View.VISIBLE
            btnSwitchAuth.text = if (isVisible) {
                getString(R.string.settings_switch_auth)
            } else {
                getString(R.string.settings_hide_api_fields)
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

    private fun refreshUI() {
        // Current provider display.
        val config = authManager.loadLLMConfig()
        if (config != null) {
            currentProviderText.text = when (config.provider) {
                LLMProvider.AZURE_OPENAI -> "Azure OpenAI (${config.model})"
                LLMProvider.OPENAI -> "OpenAI (${config.model})"
                LLMProvider.ANTHROPIC -> "Anthropic (${config.model})"
                LLMProvider.CUSTOM -> "Custom (${config.model})"
            }

            // Pre-fill fields.
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

            // Show a masked version of the API key so the user knows it's saved.
            // Format: "••••••••xxxx" (last 4 chars visible).
            val savedKey = authManager.getSavedApiKey()
            if (savedKey != null && savedKey.length >= 4) {
                val lastFour = savedKey.takeLast(4)
                maskedApiKeyPlaceholder = "••••••••$lastFour"
                apiKeyInput.setText(maskedApiKeyPlaceholder)
            } else {
                maskedApiKeyPlaceholder = null
            }
        } else if (authManager.isOAuthTokenValid()) {
            currentProviderText.text = getString(R.string.settings_provider_oauth)
        } else {
            currentProviderText.text = getString(R.string.settings_not_configured)
            // Show the API key section by default if nothing is configured.
            apiKeySection.visibility = View.VISIBLE
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
        // The user only needs to re-enter the key if they want to change it.
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

        authManager.saveLLMCredentials(
            provider = provider,
            apiKey = key,
            model = model,
            endpoint = endpoint.ifBlank { null },
        )

        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        apiKeySection.visibility = View.GONE
        btnSwitchAuth.text = getString(R.string.settings_switch_auth)
        refreshUI()
    }
}
