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
    private lateinit var appPrefs: AppPrefs

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
        appPrefs = AppPrefs(this)

        // Top bar back button.
        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { finish() }

        bindViews()
        setupProviderDropdown()
        setupListeners()

        // Allow ADB configuration via intent extras (debug only).
        handleConfigIntent(intent)

        refreshUI()
    }

    private fun handleConfigIntent(intent: Intent) {
        val provider = intent.getStringExtra("llm_provider") ?: return
        val apiKey = intent.getStringExtra("llm_api_key") ?: return
        val model = intent.getStringExtra("llm_model") ?: return
        val endpoint = intent.getStringExtra("llm_endpoint")

        val llmProvider = when (provider) {
            "azure" -> LLMProvider.AZURE_OPENAI
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
        val providers = arrayOf("Azure OpenAI", "OpenAI", "Anthropic", "Custom")
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
                "Hide API key fields"
            }
        }

        btnSaveApiConfig.setOnClickListener { saveApiConfig() }

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
            // Don't pre-fill the key for security.
        } else if (authManager.isOAuthTokenValid()) {
            currentProviderText.text = "ChatGPT (OAuth)"
        } else {
            currentProviderText.text = "Not configured"
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
        accessibilityStatusText.text = if (running) "Enabled and running" else "Not enabled"
    }

    private fun saveApiConfig() {
        val providerText = providerDropdown.text.toString()
        val key = apiKeyInput.text?.toString()?.trim().orEmpty()
        val model = apiModelInput.text?.toString()?.trim().orEmpty()
        val endpoint = apiEndpointInput.text?.toString()?.trim().orEmpty()

        if (key.length < 4) {
            Toast.makeText(this, "Enter a valid API key", Toast.LENGTH_SHORT).show()
            return
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

        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        apiKeySection.visibility = View.GONE
        btnSwitchAuth.text = getString(R.string.settings_switch_auth)
        refreshUI()
    }
}
