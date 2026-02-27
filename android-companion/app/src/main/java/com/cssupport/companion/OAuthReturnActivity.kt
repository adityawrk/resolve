package com.cssupport.companion

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Invisible trampoline activity that receives the `intent://` URI from the
 * OAuth callback HTML page in the Chrome Custom Tab.
 *
 * When the user taps "Return to Resolve" in the Custom Tab, Chrome:
 * 1. Resolves the `intent://` URI to this activity
 * 2. Closes the Custom Tab (standard Chrome behaviour when navigating to an external app)
 * 3. Starts this activity
 *
 * This activity immediately finishes itself. The calling activity (Welcome or
 * Settings) is already in the back stack; its `onResume` handles the rest.
 *
 * If the app task was killed while the Custom Tab was showing, we restart
 * from WelcomeActivity.
 */
class OAuthReturnActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isTaskRoot) {
            // App task was killed — start fresh.
            startActivity(
                Intent(this, WelcomeActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                },
            )
        }
        finish()
    }
}
