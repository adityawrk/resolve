package com.cssupport.companion

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * Two-phase onboarding:
 *
 * **Phase 1** (Android 13+ sideloaded apps only):
 * Guide the user to "Allow restricted settings" in App Info. Without this,
 * Samsung/Android blocks sideloaded apps from enabling accessibility services.
 *
 * **Phase 2** (all devices):
 * Guide the user to enable the Resolve accessibility service.
 *
 * Auto-advances to MainActivity when the user returns with accessibility enabled.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var btnAllowAccess: MaterialButton
    private lateinit var cardRestricted: MaterialCardView
    private lateinit var btnOpenAppInfo: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        btnAllowAccess = findViewById(R.id.btnAllowAccess)
        cardRestricted = findViewById(R.id.cardRestricted)
        btnOpenAppInfo = findViewById(R.id.btnOpenAppInfo)

        // Show the restricted settings card on Android 13+ (API 33).
        // Sideloaded apps (installed from Google Drive APK) are blocked from
        // enabling accessibility services unless the user explicitly allows
        // restricted settings in App Info.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cardRestricted.visibility = View.VISIBLE
        }

        btnOpenAppInfo.setOnClickListener {
            openAppInfoSettings()
        }

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
            }, 300)
        }
    }

    /**
     * Open the App Info page for this app.
     * On this page, the user can tap the three-dot menu (⋮) in the top-right
     * and select "Allow restricted settings".
     */
    private fun openAppInfoSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        } catch (_: Exception) {
            // Fallback: open general app settings.
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS))
            } catch (_: Exception) {
                // Nothing we can do.
            }
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
