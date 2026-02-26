package com.cssupport.companion

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * Single-step onboarding: shows clear steps, opens accessibility settings,
 * auto-advances to MainActivity when the user returns with it enabled.
 *
 * When the user returns with accessibility enabled, shows brief "All set!"
 * feedback before navigating, so they know it worked.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var btnAllowAccess: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        btnAllowAccess = findViewById(R.id.btnAllowAccess)
        btnAllowAccess.setOnClickListener {
            openAccessibilitySettings()
        }
    }

    override fun onResume() {
        super.onResume()
        if (SupportAccessibilityService.isRunning()) {
            // Show brief success feedback before navigating away.
            btnAllowAccess.text = getString(R.string.onboarding_success)
            btnAllowAccess.isEnabled = false

            window.decorView.postDelayed({
                // Guard against the activity being finished during the delay.
                if (isFinishing) return@postDelayed

                // If the user came from the "Use your own API key" path, route them
                // to Settings first so they can enter their key before reaching Main.
                val prefs = getSharedPreferences("resolve_prefs", MODE_PRIVATE)
                if (prefs.getBoolean("needs_api_key_setup", false)) {
                    prefs.edit().remove("needs_api_key_setup").apply()
                    // Start MainActivity first (bottom of stack), then Settings on top.
                    // The user sees Settings; pressing Back lands on Main.
                    startActivity(Intent(this, MainActivity::class.java))
                    startActivity(Intent(this, SettingsActivity::class.java))
                    finish()
                } else {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
            }, 800)
        }
    }

    private fun openAccessibilitySettings() {
        val flatComponent = ComponentName(
            packageName,
            SupportAccessibilityService::class.java.name,
        ).flattenToString()

        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            putExtra(":settings:fragment_args_key", flatComponent)
            putExtra("android.provider.extra.FRAGMENT_ARG_KEY", flatComponent)
        }

        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }
}
