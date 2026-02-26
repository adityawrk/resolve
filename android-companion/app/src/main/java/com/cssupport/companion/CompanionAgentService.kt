package com.cssupport.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that orchestrates the on-device agent automation loop.
 *
 * Runs fully standalone -- no backend communication. When started it:
 * 1. Launches the target app
 * 2. Waits for the [AccessibilityEngine] to be available
 * 3. Creates an [AgentLoop] with the LLM client and case context
 * 4. Runs the observe-think-act loop with live progress via [AgentLogStore]
 * 5. Reports completion or failure locally
 *
 * Supports pause/resume/stop from the user via intent actions.
 */
class CompanionAgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tag = "CSCompanionService"

    private var running = false
    private var currentAgentLoop: AgentLoop? = null
    private var currentAgentJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_STOP -> {
                    Log.i(tag, "Received ACTION_STOP")
                    AgentLogStore.log("Service stopping")
                    currentAgentLoop?.pause()
                    currentAgentJob?.cancel()
                    stopSelf()
                    return START_NOT_STICKY
                }

                ACTION_PAUSE -> {
                    Log.i(tag, "Received ACTION_PAUSE")
                    currentAgentLoop?.pause()
                    updateNotification("Agent paused")
                    return START_STICKY
                }

                ACTION_RESUME -> {
                    Log.i(tag, "Received ACTION_RESUME")
                    currentAgentLoop?.resume()
                    updateNotification("Agent running")
                    return START_STICKY
                }

                ACTION_START -> {
                    Log.i(tag, "Received ACTION_START")
                    if (!running) {
                        running = true
                        val caseId = intent.getStringExtra(EXTRA_CASE_ID) ?: "local"
                        val issue = intent.getStringExtra(EXTRA_ISSUE) ?: ""
                        val desiredOutcome = intent.getStringExtra(EXTRA_DESIRED_OUTCOME) ?: "Resolve the issue"
                        val orderId = intent.getStringExtra(EXTRA_ORDER_ID)
                        val targetPlatform = intent.getStringExtra(EXTRA_TARGET_PLATFORM) ?: ""
                        val hasAttachments = intent.getBooleanExtra(EXTRA_HAS_ATTACHMENTS, false)

                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                startForeground(
                                    NOTIFICATION_ID,
                                    buildNotification("Agent starting..."),
                                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                                )
                            } else {
                                startForeground(NOTIFICATION_ID, buildNotification("Agent starting..."))
                            }
                        } catch (e: Exception) {
                            Log.e(tag, "startForeground failed", e)
                            AgentLogStore.log("Failed to start foreground service: ${e.message}", LogCategory.ERROR, "Service start failed")
                            running = false
                            stopSelf()
                            return START_NOT_STICKY
                        }

                        scope.launch {
                            runLocalLoop(
                                caseId = caseId,
                                issue = issue,
                                desiredOutcome = desiredOutcome,
                                orderId = orderId,
                                targetPlatform = targetPlatform,
                                hasAttachments = hasAttachments,
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "onStartCommand crashed", e)
            AgentLogStore.log("Service error: ${e.message}", LogCategory.ERROR, "Service error: ${e.message}")
            running = false
            try { stopSelf() } catch (_: Exception) {}
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        currentAgentJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    // ── Standalone (on-device) loop ─────────────────────────────────────────

    private suspend fun runLocalLoop(
        caseId: String,
        issue: String,
        desiredOutcome: String,
        orderId: String?,
        targetPlatform: String,
        hasAttachments: Boolean,
    ) {
        Log.i(tag, "Local agent loop starting for case=$caseId, target=$targetPlatform")
        AgentLogStore.clear()
        AgentLogStore.log("Starting agent for $targetPlatform", LogCategory.STATUS_UPDATE, "Starting...")

        // Step 1: Launch the target app.
        val launched = tryLaunchApp(targetPlatform)
        if (launched) {
            AgentLogStore.log("Launched $targetPlatform", LogCategory.STATUS_UPDATE, "Opening app...")
        } else {
            // Don't alarm the user — the agent loop will actively try to launch it.
            Log.w(tag, "Initial launch of $targetPlatform failed, agent loop will retry")
        }

        // Step 2: Wait for the accessibility service.
        delay(2000)
        val engine = waitForAccessibilityEngine(timeoutMs = 10_000)
        if (engine == null) {
            AgentLogStore.log("FAILED: Accessibility service not available", LogCategory.ERROR, "Accessibility service not available")
            running = false
            stopSelf()
            return
        }

        // Step 3: Load LLM config.
        val authManager = AuthManager(this@CompanionAgentService)
        var llmConfig = authManager.loadLLMConfig()
        if (llmConfig == null) {
            AgentLogStore.log("FAILED: No LLM API key configured", LogCategory.ERROR, "No API key configured")
            running = false
            stopSelf()
            return
        }

        // Auto-migrate deprecated models (4o series retired as of 2026).
        llmConfig = migrateDeprecatedModel(authManager, llmConfig)

        // Step 3b: Refresh OAuth token if needed.
        if (authManager.needsOAuthRefresh()) {
            val refreshed = authManager.refreshOAuthTokenIfNeeded()
            if (refreshed) {
                // Reload config with fresh token.
                llmConfig = authManager.loadLLMConfig()
                if (llmConfig == null) {
                    AgentLogStore.log("FAILED: Token refresh failed", LogCategory.ERROR, "Authentication expired")
                    running = false
                    stopSelf()
                    return
                }
            }
        }

        val llmClient = LLMClient(llmConfig)

        // Step 4: Build case context.
        val caseContext = CaseContext(
            caseId = caseId,
            customerName = "Customer",
            issue = issue,
            desiredOutcome = desiredOutcome,
            orderId = orderId,
            hasAttachments = hasAttachments,
            targetPlatform = targetPlatform,
        )

        // Step 5: Run the agent loop with the user's auto-approve preference.
        val autoApprove = getSharedPreferences("resolve_prefs", MODE_PRIVATE)
            .getBoolean("auto_approve", false)
        val safetyPolicy = SafetyPolicy(autoApproveSafeActions = autoApprove)

        val agentLoop = AgentLoop(
            engine = engine,
            llmClient = llmClient,
            caseContext = caseContext,
            safetyPolicy = safetyPolicy,
            launchTargetApp = { tryLaunchApp(targetPlatform) },
            onEvent = { event ->
                // Update notification with current action.
                val eventMsg = when (event) {
                    is AgentEvent.DecisionMade -> event.action
                    is AgentEvent.Resolved -> "Resolved: ${event.summary.take(50)}"
                    is AgentEvent.Failed -> "Failed: ${event.reason.take(50)}"
                    else -> null
                }
                if (eventMsg != null) {
                    updateNotification(eventMsg)
                }
            },
        )
        currentAgentLoop = agentLoop

        updateNotification("Working in $targetPlatform...")
        AgentLogStore.log("Agent loop running", LogCategory.STATUS_UPDATE, "Agent is working...")

        currentAgentJob = scope.launch {
            val result = agentLoop.run()
            when (result) {
                is AgentResult.Resolved -> {
                    AgentLogStore.log("Case resolved: ${result.summary}", LogCategory.TERMINAL_RESOLVED, "Issue resolved!")
                    updateNotification("Resolved")
                }
                is AgentResult.Failed -> {
                    AgentLogStore.log("Case failed: ${result.reason}", LogCategory.TERMINAL_FAILED, "Agent stopped: ${result.reason}")
                    updateNotification("Failed")
                }
                is AgentResult.NeedsHumanReview -> {
                    AgentLogStore.log("Needs your input: ${result.reason}", LogCategory.APPROVAL_NEEDED, "Needs your input")
                    updateNotification("Needs your input")
                }
                is AgentResult.Cancelled -> {
                    AgentLogStore.log("Agent cancelled", LogCategory.STATUS_UPDATE, "Cancelled")
                }
            }
            // Bring MonitorActivity to foreground so the customer sees the result
            // instead of being left staring at the target app.
            bringMonitorToForeground()
        }

        currentAgentJob?.join()
        currentAgentLoop = null
        currentAgentJob = null
        running = false
        updateNotification("Agent idle")
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Wait for the accessibility engine to become available.
     * Returns null if timeout expires.
     */
    private suspend fun waitForAccessibilityEngine(timeoutMs: Long): AccessibilityEngine? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val engine = SupportAccessibilityService.getEngine()
            if (engine != null) return engine
            delay(500)
        }
        return null
    }

    /**
     * Migrate deprecated model names to current equivalents.
     * The GPT-4o series was retired — upgrade to gpt-5-mini.
     */
    private fun migrateDeprecatedModel(authManager: AuthManager, config: LLMConfig): LLMConfig {
        val deprecated = setOf("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-3.5-turbo")
        if (config.model in deprecated) {
            val newModel = "gpt-5-mini"
            Log.i(tag, "Migrating deprecated model ${config.model} → $newModel")
            authManager.saveLLMCredentials(
                provider = config.provider,
                apiKey = config.apiKey,
                model = newModel,
                endpoint = config.endpoint,
                apiVersion = config.apiVersion,
            )
            return config.copy(model = newModel)
        }
        return config
    }

    /**
     * Bring MonitorActivity to foreground so the customer sees results.
     */
    private fun bringMonitorToForeground() {
        try {
            val intent = Intent(this, MonitorActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(tag, "Could not bring MonitorActivity to foreground", e)
        }
    }

    private fun tryLaunchApp(packageName: String): Boolean {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            startActivity(launchIntent)
            true
        }.getOrElse { false }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.agent_notification_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        return try {
            val openAppIntent = Intent(this, MonitorActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                this, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE,
            )

            val stopIntent = Intent(this, CompanionAgentService::class.java).apply {
                action = ACTION_STOP
            }
            val stopPendingIntent = PendingIntent.getService(
                this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE,
            )

            val pauseIntent = Intent(this, CompanionAgentService::class.java).apply {
                action = ACTION_PAUSE
            }
            val pausePendingIntent = PendingIntent.getService(
                this, 2, pauseIntent, PendingIntent.FLAG_IMMUTABLE,
            )

            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_agent)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(contentText.ifBlank { getString(R.string.notification_tap_progress) })
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .addAction(0, getString(R.string.notification_view_progress), pendingIntent)
                .addAction(0, getString(R.string.notification_stop), stopPendingIntent)
                .build()
        } catch (e: Exception) {
            Log.e(tag, "Failed to build notification", e)
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Resolve")
                .setContentText(contentText)
                .build()
        }
    }

    companion object {
        private const val ACTION_START = "com.cssupport.companion.action.START"
        private const val ACTION_STOP = "com.cssupport.companion.action.STOP"
        private const val ACTION_PAUSE = "com.cssupport.companion.action.PAUSE"
        private const val ACTION_RESUME = "com.cssupport.companion.action.RESUME"

        private const val EXTRA_CASE_ID = "extra_case_id"
        private const val EXTRA_ISSUE = "extra_issue"
        private const val EXTRA_DESIRED_OUTCOME = "extra_desired_outcome"
        private const val EXTRA_ORDER_ID = "extra_order_id"
        private const val EXTRA_TARGET_PLATFORM = "extra_target_platform"
        private const val EXTRA_HAS_ATTACHMENTS = "extra_has_attachments"

        private const val CHANNEL_ID = "cs_support_companion"
        private const val NOTIFICATION_ID = 10011

        /**
         * Start the service in standalone on-device mode.
         * Runs the agent loop directly with the provided case context.
         */
        fun start(
            context: Context,
            caseId: String,
            issue: String,
            desiredOutcome: String,
            orderId: String?,
            targetPlatform: String,
            hasAttachments: Boolean,
        ) {
            val intent = Intent(context, CompanionAgentService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CASE_ID, caseId)
                putExtra(EXTRA_ISSUE, issue)
                putExtra(EXTRA_DESIRED_OUTCOME, desiredOutcome)
                putExtra(EXTRA_ORDER_ID, orderId)
                putExtra(EXTRA_TARGET_PLATFORM, targetPlatform)
                putExtra(EXTRA_HAS_ATTACHMENTS, hasAttachments)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CompanionAgentService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun pause(context: Context) {
            val intent = Intent(context, CompanionAgentService::class.java).apply {
                action = ACTION_PAUSE
            }
            context.startService(intent)
        }

        fun resume(context: Context) {
            val intent = Intent(context, CompanionAgentService::class.java).apply {
                action = ACTION_RESUME
            }
            context.startService(intent)
        }
    }
}
