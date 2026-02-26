package com.cssupport.companion

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * First screen the user sees. Two auth paths:
 * 1. "Sign in with ChatGPT" -- starts a localhost callback server on port 1455, launches
 *    a Chrome Custom Tab for OAuth login, waits for the redirect to
 *    http://localhost:1455/auth/callback, exchanges the auth code for tokens, saves
 *    credentials, and proceeds to onboarding.
 * 2. "Use your own API key" -- proceeds to onboarding, then routes to Settings for
 *    multi-provider key entry (Azure, OpenAI, Anthropic, Custom).
 *
 * If the user already has LLM credentials saved, skips straight to the appropriate screen.
 */
class WelcomeActivity : AppCompatActivity() {

    private lateinit var authManager: AuthManager

    private lateinit var btnSignIn: MaterialButton
    private lateinit var btnUseApiKey: MaterialButton
    private lateinit var loadingIndicator: ProgressBar

    /** PKCE verifier and state are persisted to SharedPreferences to survive process death. */
    private val oauthPrefs by lazy {
        getSharedPreferences(OAUTH_PREFS, MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show last crash info if available (helps debug issues on user's device).
        val lastCrash = CrashLogger.getLastCrash(this)
        if (lastCrash != null) {
            CrashLogger.clearLastCrash(this)
            MaterialAlertDialogBuilder(this)
                .setTitle("Crash Report")
                .setMessage(lastCrash.take(2000))
                .setPositiveButton("OK", null)
                .setNeutralButton("Copy") { _, _ ->
                    val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("crash", lastCrash))
                    Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        authManager = AuthManager(this)

        // Detect expired OAuth tokens and clear them so the user re-authenticates.
        if (authManager.loadOAuthToken() != null && !authManager.isOAuthTokenValid()) {
            authManager.clearOAuthTokens()
            authManager.clearLLMCredentials()
        }

        // Skip to main screen if already configured.
        if (authManager.hasLLMCredentials() || authManager.isOAuthTokenValid()) {
            if (SupportAccessibilityService.isRunning()) {
                navigateToMain()
                return
            }
            // Credentials exist but accessibility not enabled yet -- go to onboarding.
            navigateToOnboarding()
            return
        }

        setContentView(R.layout.activity_welcome)

        btnSignIn = findViewById(R.id.btnSignInChatGPT)
        btnUseApiKey = findViewById(R.id.btnUseApiKey)
        loadingIndicator = findViewById(R.id.oauthLoadingIndicator)

        btnSignIn.setOnClickListener {
            launchOAuthFlow()
        }

        btnUseApiKey.setOnClickListener {
            if (SupportAccessibilityService.isRunning()) {
                // Accessibility already enabled -- skip onboarding entirely.
                // Start MainActivity first (bottom of stack), then Settings on top.
                startActivity(Intent(this, MainActivity::class.java))
                startActivity(Intent(this, SettingsActivity::class.java))
                finish()
            } else {
                // Flag that this user needs to configure an API key after onboarding.
                getSharedPreferences("resolve_prefs", MODE_PRIVATE)
                    .edit().putBoolean("needs_api_key_setup", true).apply()
                navigateToOnboarding()
            }
        }
    }

    // ── OAuth Flow ──────────────────────────────────────────────────────────

    /**
     * Launch the full OAuth flow:
     * 1. Generate PKCE params and persist them
     * 2. Start a localhost callback server (port 1455) in a coroutine
     * 3. Open the authorization URL in a Chrome Custom Tab
     * 4. When the callback server receives the redirect, verify state and exchange the code
     */
    private fun launchOAuthFlow() {
        val codeVerifier = ChatGPTOAuth.generateCodeVerifier()
        val codeChallenge = ChatGPTOAuth.generateCodeChallenge(codeVerifier)
        val state = ChatGPTOAuth.generateState()

        // Persist PKCE state so it survives if the process is briefly killed.
        oauthPrefs.edit()
            .putString(KEY_CODE_VERIFIER, codeVerifier)
            .putString(KEY_EXPECTED_STATE, state)
            .apply()

        showLoading(true)

        // Start the callback server FIRST, then open the browser.
        lifecycleScope.launch {
            // Launch callback server -- it blocks until the browser redirects back or timeout.
            val callbackJob = launch {
                val result = ChatGPTOAuth.startCallbackServer()

                if (result == null) {
                    Log.w(TAG, "Callback server returned null (timeout or error)")
                    showLoading(false)
                    Toast.makeText(
                        this@WelcomeActivity,
                        getString(R.string.oauth_error),
                        Toast.LENGTH_LONG,
                    ).show()
                    clearOAuthPrefs()
                    return@launch
                }

                // Verify state to prevent CSRF.
                val expectedState = oauthPrefs.getString(KEY_EXPECTED_STATE, null)
                if (result.state != expectedState) {
                    Log.w(TAG, "OAuth state mismatch: expected=$expectedState, got=${result.state}")
                    showLoading(false)
                    Toast.makeText(
                        this@WelcomeActivity,
                        getString(R.string.oauth_state_mismatch),
                        Toast.LENGTH_LONG,
                    ).show()
                    clearOAuthPrefs()
                    return@launch
                }

                val verifier = oauthPrefs.getString(KEY_CODE_VERIFIER, null)
                if (verifier.isNullOrBlank()) {
                    Log.w(TAG, "Callback received but no code_verifier saved")
                    showLoading(false)
                    Toast.makeText(
                        this@WelcomeActivity,
                        getString(R.string.oauth_error),
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }

                exchangeCodeForTokens(result.code, verifier)
            }

            // Give the server socket a moment to bind before opening the browser.
            delay(100)

            // Open the authorization URL in a Chrome Custom Tab.
            val authUrl = ChatGPTOAuth.buildAuthorizationUrl(codeChallenge, state)
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            customTabsIntent.launchUrl(this@WelcomeActivity, Uri.parse(authUrl))
        }
    }

    /**
     * Exchange the authorization code for access + refresh tokens.
     * Shows a loading indicator while the exchange is in progress.
     */
    private fun exchangeCodeForTokens(code: String, codeVerifier: String) {
        showLoading(true)

        lifecycleScope.launch {
            try {
                val response = ChatGPTOAuth.exchangeCodeForTokens(code, codeVerifier)

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
                navigateToOnboarding()
            } catch (e: Exception) {
                Log.e(TAG, "Token exchange failed", e)
                showLoading(false)
                Toast.makeText(
                    this@WelcomeActivity,
                    getString(R.string.oauth_error),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    // ── UI Helpers ───────────────────────────────────────────────────────────

    private fun showLoading(loading: Boolean) {
        loadingIndicator.visibility = if (loading) View.VISIBLE else View.GONE
        btnSignIn.isEnabled = !loading
        btnUseApiKey.isEnabled = !loading

        if (loading) {
            btnSignIn.text = getString(R.string.oauth_loading)
        } else {
            btnSignIn.text = getString(R.string.sign_in_chatgpt)
        }
    }

    private fun clearOAuthPrefs() {
        oauthPrefs.edit()
            .remove(KEY_CODE_VERIFIER)
            .remove(KEY_EXPECTED_STATE)
            .apply()
    }

    // ── Navigation ──────────────────────────────────────────────────────────

    private fun navigateToOnboarding() {
        startActivity(Intent(this, OnboardingActivity::class.java))
        finish()
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        private const val TAG = "WelcomeActivity"
        private const val OAUTH_PREFS = "resolve_oauth_flow"
        private const val KEY_CODE_VERIFIER = "pkce_code_verifier"
        private const val KEY_EXPECTED_STATE = "pkce_expected_state"
    }
}
