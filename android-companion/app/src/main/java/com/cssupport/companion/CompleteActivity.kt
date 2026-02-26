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

        // Steps taken -- prefer the snapshotted count from MonitorActivity to avoid race.
        val stepCount = if (intent.hasExtra(EXTRA_STEP_COUNT)) {
            intent.getIntExtra(EXTRA_STEP_COUNT, 0)
        } else {
            // Backwards compat: compute from AgentLogStore directly.
            AgentLogStore.entries.value.count {
                it.category == LogCategory.AGENT_ACTION || it.category == LogCategory.AGENT_MESSAGE
            }
        }
        findViewById<TextView>(R.id.detailSteps).text = stepCount.toString()

        // Transcript -- prefer the snapshotted transcript from MonitorActivity to avoid race.
        val transcriptText = findViewById<TextView>(R.id.transcriptText)
        val transcript = intent.getStringExtra(EXTRA_TRANSCRIPT)
        transcriptText.text = if (!transcript.isNullOrBlank()) {
            transcript
        } else {
            // Backwards compat: build from AgentLogStore directly.
            AgentLogStore.entries.value
                .filter { it.category != LogCategory.DEBUG }
                .joinToString("\n") { "[${it.formattedTime}] ${it.displayMessage}" }
        }

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

        // Share button.
        findViewById<MaterialButton>(R.id.btnShare).setOnClickListener {
            val shareText = buildString {
                appendLine("Resolve got my issue sorted!")
                appendLine()
                appendLine("App: $targetApp")
                appendLine("Outcome: $outcome")
                appendLine("Time: $elapsed")
                appendLine()
                append(summary)
            }
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.complete_share_chooser)))
        }
    }

    companion object {
        const val EXTRA_SUMMARY = "extra_summary"
        const val EXTRA_TARGET_APP = "extra_target_app"
        const val EXTRA_OUTCOME = "extra_outcome"
        const val EXTRA_START_TIME = "extra_start_time"
        const val EXTRA_TRANSCRIPT = "extra_transcript"
        const val EXTRA_STEP_COUNT = "extra_step_count"
    }
}
