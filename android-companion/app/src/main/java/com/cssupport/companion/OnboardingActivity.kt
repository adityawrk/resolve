package com.cssupport.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

/**
 * Onboarding screen that guides the user through granting required permissions:
 * 1. Accessibility Service (required)
 * 2. Notifications (recommended)
 *
 * The "Continue" button is only enabled once the accessibility service is running.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var btnEnableAccessibility: MaterialButton
    private lateinit var accessibilityGrantedRow: LinearLayout
    private lateinit var btnEnableNotifications: MaterialButton
    private lateinit var notificationsGrantedRow: LinearLayout
    private lateinit var btnContinue: MaterialButton

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        updatePermissionStates()
        if (granted) {
            Toast.makeText(this, "Notifications enabled", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)
        accessibilityGrantedRow = findViewById(R.id.accessibilityGrantedRow)
        btnEnableNotifications = findViewById(R.id.btnEnableNotifications)
        notificationsGrantedRow = findViewById(R.id.notificationsGrantedRow)
        btnContinue = findViewById(R.id.btnContinue)

        btnEnableAccessibility.setOnClickListener {
            // Open system accessibility settings.
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnEnableNotifications.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // Pre-13, notifications are enabled by default.
                Toast.makeText(this, "Notifications are enabled", Toast.LENGTH_SHORT).show()
                updatePermissionStates()
            }
        }

        btnContinue.setOnClickListener {
            navigateToMain()
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStates()
    }

    private fun updatePermissionStates() {
        val accessibilityEnabled = SupportAccessibilityService.isRunning()
        val notificationsEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        // Accessibility
        if (accessibilityEnabled) {
            btnEnableAccessibility.visibility = View.GONE
            accessibilityGrantedRow.visibility = View.VISIBLE
        } else {
            btnEnableAccessibility.visibility = View.VISIBLE
            accessibilityGrantedRow.visibility = View.GONE
        }

        // Notifications
        if (notificationsEnabled) {
            btnEnableNotifications.visibility = View.GONE
            notificationsGrantedRow.visibility = View.VISIBLE
        } else {
            btnEnableNotifications.visibility = View.VISIBLE
            notificationsGrantedRow.visibility = View.GONE
        }

        // Continue button: enabled only when accessibility is granted.
        btnContinue.isEnabled = accessibilityEnabled
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
