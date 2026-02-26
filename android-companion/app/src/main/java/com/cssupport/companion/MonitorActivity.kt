package com.cssupport.companion

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Live agent monitor screen.
 *
 * Displays a real-time activity feed of agent actions, a progress bar,
 * pause/stop controls, and an approval overlay when the agent needs permission.
 *
 * Listens to [AgentLogStore] for log entries and (optionally) to the
 * [CompanionAgentService] for state changes.
 */
class MonitorActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var stepCounterText: TextView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var activityFeedRecycler: RecyclerView
    private lateinit var btnPause: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var approvalOverlay: FrameLayout
    private lateinit var approvalDescription: TextView
    private lateinit var btnDeny: MaterialButton
    private lateinit var btnAllow: MaterialButton

    private val feedAdapter = ActivityFeedAdapter()
    private var isPaused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_monitor)

        bindViews()
        setupRecycler()
        setupControls()
        observeLogs()

        val targetApp = intent.getStringExtra(EXTRA_TARGET_APP) ?: "app"
        statusText.text = getString(R.string.monitor_status_working, targetApp)
    }

    private fun bindViews() {
        statusText = findViewById(R.id.statusText)
        stepCounterText = findViewById(R.id.stepCounterText)
        progressBar = findViewById(R.id.progressBar)
        activityFeedRecycler = findViewById(R.id.activityFeedRecycler)
        btnPause = findViewById(R.id.btnPause)
        btnStop = findViewById(R.id.btnStop)
        approvalOverlay = findViewById(R.id.approvalOverlay)
        approvalDescription = findViewById(R.id.approvalDescription)
        btnDeny = findViewById(R.id.btnDeny)
        btnAllow = findViewById(R.id.btnAllow)
    }

    private fun setupRecycler() {
        activityFeedRecycler.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        activityFeedRecycler.adapter = feedAdapter
    }

    private fun setupControls() {
        btnPause.setOnClickListener {
            isPaused = !isPaused
            if (isPaused) {
                CompanionAgentService.pause(this)
                btnPause.text = "Resume"
                progressBar.isIndeterminate = false
                progressBar.progress = 50
                statusText.text = getString(R.string.monitor_status_paused)
            } else {
                CompanionAgentService.resume(this)
                btnPause.text = getString(R.string.monitor_btn_pause)
                progressBar.isIndeterminate = true
                val targetApp = intent.getStringExtra(EXTRA_TARGET_APP) ?: "app"
                statusText.text = getString(R.string.monitor_status_working, targetApp)
            }
        }

        btnStop.setOnClickListener {
            CompanionAgentService.stop(this)
            finish()
        }

        btnDeny.setOnClickListener {
            approvalOverlay.visibility = View.GONE
            // For now, denying just resumes the loop (the policy will block the action).
            CompanionAgentService.resume(this)
        }

        btnAllow.setOnClickListener {
            approvalOverlay.visibility = View.GONE
            // TODO: Signal approval to the agent loop.
            CompanionAgentService.resume(this)
        }
    }

    private fun observeLogs() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AgentLogStore.entries.collect { entries ->
                    feedAdapter.submitList(entries)
                    if (entries.isNotEmpty()) {
                        activityFeedRecycler.scrollToPosition(entries.size - 1)
                    }

                    // Update step counter from entry count (rough heuristic).
                    val actionCount = entries.count { it.contains("] ") }
                    stepCounterText.text = getString(
                        R.string.monitor_step_progress,
                        actionCount,
                        SafetyPolicy.MAX_ITERATIONS,
                    )

                    // Detect resolution or failure from logs.
                    val lastEntry = entries.lastOrNull() ?: ""
                    when {
                        lastEntry.contains("Case resolved:") || lastEntry.contains("RESOLVED:") -> {
                            navigateToComplete(lastEntry)
                        }
                        lastEntry.contains("NEEDS APPROVAL:") || lastEntry.contains("APPROVAL NEEDED:") -> {
                            showApprovalOverlay(lastEntry)
                        }
                    }
                }
            }
        }
    }

    private fun showApprovalOverlay(description: String) {
        approvalDescription.text = description
        approvalOverlay.visibility = View.VISIBLE
    }

    private fun navigateToComplete(summary: String) {
        val intent = Intent(this, CompleteActivity::class.java).apply {
            putExtra(CompleteActivity.EXTRA_SUMMARY, summary)
            putExtra(CompleteActivity.EXTRA_TARGET_APP, this@MonitorActivity.intent.getStringExtra(EXTRA_TARGET_APP))
            putExtra(CompleteActivity.EXTRA_OUTCOME, this@MonitorActivity.intent.getStringExtra(EXTRA_OUTCOME))
            putExtra(CompleteActivity.EXTRA_START_TIME, this@MonitorActivity.intent.getLongExtra(EXTRA_START_TIME, 0L))
        }
        startActivity(intent)
        finish()
    }

    companion object {
        const val EXTRA_TARGET_APP = "extra_target_app"
        const val EXTRA_OUTCOME = "extra_outcome"
        const val EXTRA_START_TIME = "extra_start_time"
    }
}

// ── Activity feed adapter ────────────────────────────────────────────────────

class ActivityFeedAdapter : RecyclerView.Adapter<ActivityFeedAdapter.ViewHolder>() {

    private var items: List<String> = emptyList()

    fun submitList(newItems: List<String>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_activity_action, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val actionText: TextView = itemView.findViewById(R.id.actionText)

        fun bind(entry: String) {
            actionText.text = entry
        }
    }
}
