package com.cssupport.companion

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.activity.OnBackPressedCallback
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch

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
    private lateinit var emptyFeedState: LinearLayout
    private lateinit var btnShareLog: MaterialButton

    private val feedAdapter = ChatFeedAdapter()
    private var isPaused = false
    private var hasNavigatedToComplete = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_monitor)

        try {
            bindViews()
            if (!BuildConfig.DEBUG) btnShareLog.visibility = View.GONE
            setupRecycler()
            setupControls()
            observeLogs()

            val targetApp = intent.getStringExtra(EXTRA_TARGET_APP) ?: "app"
            statusText.text = getString(R.string.monitor_status_working, targetApp)

            // Intercept back press: confirm if agent is still active.
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val agentActive = !isPaused && progressBar.isIndeterminate
                    if (!agentActive) {
                        finish()
                        return
                    }
                    MaterialAlertDialogBuilder(this@MonitorActivity)
                        .setTitle(getString(R.string.monitor_back_title))
                        .setMessage(getString(R.string.monitor_back_message))
                        .setNeutralButton(getString(R.string.monitor_back_stay)) { dialog, _ ->
                            dialog.dismiss()
                        }
                        .setPositiveButton(getString(R.string.monitor_back_leave)) { _, _ ->
                            finish()
                        }
                        .setNegativeButton(getString(R.string.monitor_back_stop)) { _, _ ->
                            CompanionAgentService.stop(this@MonitorActivity)
                            finish()
                        }
                        .show()
                }
            })
        } catch (e: Exception) {
            android.util.Log.e("MonitorActivity", "onCreate crashed", e)
            android.widget.Toast.makeText(this, "Monitor error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            finish()
        }
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
        emptyFeedState = findViewById(R.id.emptyFeedState)
        btnShareLog = findViewById(R.id.btnShareLog)
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
                btnPause.text = getString(R.string.monitor_btn_resume)
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
            CompanionAgentService.stop(this)
            statusText.text = getString(R.string.monitor_status_stopped)
            progressBar.isIndeterminate = false
            progressBar.progress = 100
            btnPause.isEnabled = false
        }

        btnAllow.setOnClickListener {
            approvalOverlay.visibility = View.GONE
            CompanionAgentService.resume(this)
        }

        btnShareLog.setOnClickListener {
            shareDebugLog()
        }
    }

    private fun shareDebugLog() {
        val logFile = DebugLogger.getLogFile()
        if (logFile == null || !logFile.exists()) {
            Toast.makeText(this, "No debug log available yet", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                logFile,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Resolve Agent Debug Log")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share debug log"))
        } catch (e: Exception) {
            Toast.makeText(this, "Could not share log: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeLogs() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AgentLogStore.entries.collect { allEntries ->
                    // Filter out DEBUG entries for the UI.
                    val displayEntries = allEntries.filter { it.category != LogCategory.DEBUG }
                    feedAdapter.submitList(displayEntries)

                    // Toggle empty state vs. feed visibility.
                    if (displayEntries.isEmpty()) {
                        emptyFeedState.visibility = View.VISIBLE
                        activityFeedRecycler.visibility = View.GONE
                    } else {
                        emptyFeedState.visibility = View.GONE
                        activityFeedRecycler.visibility = View.VISIBLE
                        activityFeedRecycler.scrollToPosition(displayEntries.size - 1)
                    }

                    // Step counter.
                    val actionCount = allEntries.count {
                        it.category == LogCategory.AGENT_ACTION || it.category == LogCategory.AGENT_MESSAGE
                    }
                    stepCounterText.text = getString(
                        R.string.monitor_step_progress,
                        actionCount,
                    )

                    // Detect terminal states via explicit categories.
                    val last = allEntries.lastOrNull() ?: return@collect
                    when (last.category) {
                        LogCategory.TERMINAL_RESOLVED -> {
                            if (!hasNavigatedToComplete) {
                                hasNavigatedToComplete = true
                                navigateToComplete(last.displayMessage)
                            }
                        }
                        LogCategory.TERMINAL_FAILED -> {
                            statusText.text = getString(R.string.monitor_status_stopped)
                            progressBar.isIndeterminate = false
                            progressBar.progress = 100
                            btnPause.isEnabled = false
                        }
                        LogCategory.APPROVAL_NEEDED -> {
                            showApprovalOverlay(last.displayMessage)
                        }
                        LogCategory.ERROR -> {
                            statusText.text = getString(R.string.monitor_status_stopped)
                            progressBar.isIndeterminate = false
                            progressBar.progress = 100
                        }
                        else -> {}
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
        // Snapshot the log entries now to avoid a race where AgentLogStore is
        // cleared before CompleteActivity reads them.
        val allEntries = AgentLogStore.entries.value
        val transcript = allEntries
            .filter { it.category != LogCategory.DEBUG }
            .joinToString("\n") { "[${it.formattedTime}] ${it.displayMessage}" }
        val stepCount = allEntries.count {
            it.category == LogCategory.AGENT_ACTION || it.category == LogCategory.AGENT_MESSAGE
        }

        val intent = Intent(this, CompleteActivity::class.java).apply {
            putExtra(CompleteActivity.EXTRA_SUMMARY, summary)
            putExtra(CompleteActivity.EXTRA_TARGET_APP, this@MonitorActivity.intent.getStringExtra(EXTRA_TARGET_APP))
            putExtra(CompleteActivity.EXTRA_OUTCOME, this@MonitorActivity.intent.getStringExtra(EXTRA_OUTCOME))
            putExtra(CompleteActivity.EXTRA_START_TIME, this@MonitorActivity.intent.getLongExtra(EXTRA_START_TIME, 0L))
            putExtra(CompleteActivity.EXTRA_TRANSCRIPT, transcript)
            putExtra(CompleteActivity.EXTRA_STEP_COUNT, stepCount)
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

// ── Multi-view-type chat feed adapter ────────────────────────────────────────

class ChatFeedAdapter : ListAdapter<LogEntry, RecyclerView.ViewHolder>(LogEntryDiff) {

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position).category) {
            LogCategory.AGENT_MESSAGE -> VIEW_TYPE_AGENT_MESSAGE
            LogCategory.AGENT_ACTION -> VIEW_TYPE_AGENT_ACTION
            LogCategory.STATUS_UPDATE, LogCategory.ERROR, LogCategory.DEBUG,
            LogCategory.TERMINAL_RESOLVED, LogCategory.TERMINAL_FAILED,
            LogCategory.APPROVAL_NEEDED -> VIEW_TYPE_STATUS
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_AGENT_MESSAGE -> AgentMessageVH(
                inflater.inflate(R.layout.item_chat_bubble_sent, parent, false),
            )
            VIEW_TYPE_AGENT_ACTION -> ActionVH(
                inflater.inflate(R.layout.item_activity_action, parent, false),
            )
            else -> StatusVH(
                inflater.inflate(R.layout.item_status_update, parent, false),
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val entry = getItem(position)
        when (holder) {
            is AgentMessageVH -> holder.bind(entry)
            is ActionVH -> holder.bind(entry)
            is StatusVH -> holder.bind(entry)
        }
    }

    class AgentMessageVH(view: View) : RecyclerView.ViewHolder(view) {
        private val bubbleText: TextView = view.findViewById(R.id.bubbleText)
        fun bind(entry: LogEntry) {
            bubbleText.text = entry.displayMessage
        }
    }

    class ActionVH(view: View) : RecyclerView.ViewHolder(view) {
        private val actionText: TextView = view.findViewById(R.id.actionText)
        private val timestamp: TextView = view.findViewById(R.id.actionTimestamp)
        fun bind(entry: LogEntry) {
            actionText.text = entry.displayMessage
            timestamp.text = entry.formattedTime
        }
    }

    class StatusVH(view: View) : RecyclerView.ViewHolder(view) {
        private val text: TextView = view.findViewById(R.id.statusText)
        fun bind(entry: LogEntry) {
            text.text = entry.displayMessage
        }
    }

    companion object {
        private const val VIEW_TYPE_AGENT_MESSAGE = 0
        private const val VIEW_TYPE_AGENT_ACTION = 1
        private const val VIEW_TYPE_STATUS = 2
    }
}

private object LogEntryDiff : DiffUtil.ItemCallback<LogEntry>() {
    override fun areItemsTheSame(a: LogEntry, b: LogEntry) = a.id == b.id
    override fun areContentsTheSame(a: LogEntry, b: LogEntry) = a == b
}
