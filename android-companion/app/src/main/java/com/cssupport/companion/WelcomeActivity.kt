package com.cssupport.companion

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * First screen the user sees. Two auth paths:
 * 1. "Sign in with ChatGPT" (OAuth -- placeholder for now)
 * 2. "Use your own API key" → proceeds to onboarding then settings
 *
 * If the user already has LLM credentials saved, skips straight to the main screen.
 */
class WelcomeActivity : AppCompatActivity() {

    private lateinit var authManager: AuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        authManager = AuthManager(this)

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

        findViewById<MaterialButton>(R.id.btnSignInChatGPT).setOnClickListener {
            // TODO: Implement ChatGPT OAuth browser flow.
            // For now, proceed to onboarding with a placeholder token.
            authManager.saveOAuthToken(
                accessToken = "chatgpt-oauth-placeholder",
                refreshToken = null,
                expiresAtMs = System.currentTimeMillis() + 3_600_000L,
            )
            // Also save a default LLM config pointing to OpenAI.
            authManager.saveLLMCredentials(
                provider = LLMProvider.OPENAI,
                apiKey = "chatgpt-oauth-placeholder",
                model = "gpt-4o",
            )
            navigateToOnboarding()
        }

        findViewById<MaterialButton>(R.id.btnUseApiKey).setOnClickListener {
            // Go to onboarding, then settings will be accessible to enter the key.
            navigateToOnboarding()
        }
    }

    private fun navigateToOnboarding() {
        startActivity(Intent(this, OnboardingActivity::class.java))
        finish()
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
