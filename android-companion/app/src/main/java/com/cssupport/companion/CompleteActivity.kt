package com.cssupport.companion

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * Case completion screen.
 *
 * Shows:
 * - Success card with resolution summary
 * - Details (app, outcome, time taken, steps)
 * - Expandable conversation transcript
 * - "New Issue" CTA
 */
class CompleteActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_complete)

        val summary = intent.getStringExtra(EXTRA_SUMMARY) ?: "Issue resolved successfully."
        val targetApp = intent.getStringExtra(EXTRA_TARGET_APP) ?: "App"
        val outcome = intent.getStringExtra(EXTRA_OUTCOME) ?: "Resolved"
        val startTime = intent.getLongExtra(EXTRA_START_TIME, 0L)

        // Resolution summary.
        findViewById<TextView>(R.id.resolutionSummary).text = summary

        // Details.
        findViewById<TextView>(R.id.detailApp).text = targetApp
        findViewById<TextView>(R.id.detailOutcome).text = outcome

        // Time taken.
        val elapsed = if (startTime > 0) {
            val durationMs = System.currentTimeMillis() - startTime
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            if (minutes > 0) "$minutes min $seconds sec" else "$seconds sec"
        } else {
            "N/A"
        }
        findViewById<TextView>(R.id.detailTimeTaken).text = elapsed

        // Steps taken (from log entries).
        val logEntries = AgentLogStore.entries.value
        val stepCount = logEntries.count { it.contains("] ") }
        findViewById<TextView>(R.id.detailSteps).text = stepCount.toString()

        // Transcript: build from log entries.
        val transcriptText = findViewById<TextView>(R.id.transcriptText)
        transcriptText.text = logEntries.joinToString("\n")

        // Expandable transcript card.
        val transcriptHeader = findViewById<View>(R.id.transcriptHeader)
        val transcriptExpandIcon = findViewById<ImageView>(R.id.transcriptExpandIcon)
        var expanded = false
        transcriptHeader.setOnClickListener {
            expanded = !expanded
            transcriptText.visibility = if (expanded) View.VISIBLE else View.GONE
            transcriptExpandIcon.rotation = if (expanded) 180f else 0f
        }

        // New Issue button.
        findViewById<MaterialButton>(R.id.btnNewIssue).setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            finish()
        }
    }

    companion object {
        const val EXTRA_SUMMARY = "extra_summary"
        const val EXTRA_TARGET_APP = "extra_target_app"
        const val EXTRA_OUTCOME = "extra_outcome"
        const val EXTRA_START_TIME = "extra_start_time"
    }
}
