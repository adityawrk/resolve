package com.cssupport.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that orchestrates the real agent automation loop.
 *
 * When a command is received from the backend, it:
 * 1. Launches the target app
 * 2. Waits for the [AccessibilityEngine] to be available
 * 3. Creates an [AgentLoop] with the LLM client and case context
 * 4. Runs the observe-think-act loop, posting real progress events to the backend
 * 5. Reports completion or failure
 *
 * Supports pause/resume/stop from the user via intent actions.
 */
class CompanionAgentService : Service() {

    private val backendClient = BackendClient()
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
                    val baseUrl = intent.getStringExtra(EXTRA_BASE_URL)
                    val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
                    val deviceToken = intent.getStringExtra(EXTRA_DEVICE_TOKEN)

                    if (baseUrl.isNullOrBlank() || deviceId.isNullOrBlank() || deviceToken.isNullOrBlank()) {
                        Log.e(tag, "Cannot start: missing required config")
                        AgentLogStore.log("Cannot start: missing required config")
                        stopSelf()
                        return START_NOT_STICKY
                    }

                    startForeground(NOTIFICATION_ID, buildNotification("Agent running"))
                    Log.i(tag, "Foreground service started for device=$deviceId")
                    scope.launch {
                        runLoop(
                            baseUrl = baseUrl,
                            credentials = DeviceCredentials(deviceId = deviceId, deviceToken = deviceToken),
                        )
                    }
                }
            }

            ACTION_START_LOCAL -> {
                Log.i(tag, "Received ACTION_START_LOCAL")
                if (!running) {
                    running = true
                    val caseId = intent.getStringExtra(EXTRA_CASE_ID) ?: "local"
                    val issue = intent.getStringExtra(EXTRA_ISSUE) ?: ""
                    val desiredOutcome = intent.getStringExtra(EXTRA_DESIRED_OUTCOME) ?: "Resolve the issue"
                    val orderId = intent.getStringExtra(EXTRA_ORDER_ID)
                    val targetPlatform = intent.getStringExtra(EXTRA_TARGET_PLATFORM) ?: ""
                    val hasAttachments = intent.getBooleanExtra(EXTRA_HAS_ATTACHMENTS, false)

                    startForeground(NOTIFICATION_ID, buildNotification("Agent starting..."))
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
        AgentLogStore.log("Starting agent for $targetPlatform")

        // Step 1: Launch the target app.
        val launched = tryLaunchApp(targetPlatform)
        if (!launched) {
            AgentLogStore.log("Could not launch $targetPlatform -- proceeding with current foreground app")
        } else {
            AgentLogStore.log("Launched $targetPlatform")
        }

        // Step 2: Wait for the accessibility service.
        delay(2000)
        val engine = waitForAccessibilityEngine(timeoutMs = 10_000)
        if (engine == null) {
            AgentLogStore.log("FAILED: Accessibility service not available")
            running = false
            stopSelf()
            return
        }

        // Step 3: Load LLM config.
        val authManager = AuthManager(this@CompanionAgentService)
        val llmConfig = authManager.loadLLMConfig()
        if (llmConfig == null) {
            AgentLogStore.log("FAILED: No LLM API key configured")
            running = false
            stopSelf()
            return
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

        // Step 5: Run the agent loop.
        val agentLoop = AgentLoop(
            engine = engine,
            llmClient = llmClient,
            caseContext = caseContext,
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
        AgentLogStore.log("Agent loop running")

        currentAgentJob = scope.launch {
            val result = agentLoop.run()
            when (result) {
                is AgentResult.Resolved -> {
                    AgentLogStore.log("Case resolved: ${result.summary}")
                    updateNotification("Resolved")
                }
                is AgentResult.Failed -> {
                    AgentLogStore.log("Case failed: ${result.reason}")
                    updateNotification("Failed")
                }
                is AgentResult.NeedsHumanReview -> {
                    AgentLogStore.log("Needs your input: ${result.reason}")
                    updateNotification("Needs your input")
                }
                is AgentResult.Cancelled -> {
                    AgentLogStore.log("Agent cancelled")
                }
            }
        }

        currentAgentJob?.join()
        currentAgentLoop = null
        currentAgentJob = null
        running = false
        updateNotification("Agent idle")
    }

    // ── Main polling loop ───────────────────────────────────────────────────

    private suspend fun runLoop(
        baseUrl: String,
        credentials: DeviceCredentials,
    ) {
        Log.i(tag, "Polling loop started for ${credentials.deviceId}")
        AgentLogStore.log("Polling for commands as ${credentials.deviceId}")

        while (scope.isActive) {
            runCatching {
                backendClient.pollCommand(baseUrl = baseUrl, credentials = credentials)
            }.onSuccess { command ->
                if (command == null) {
                    Log.d(tag, "No command; polling again")
                    delay(1500)
                    return@onSuccess
                }

                Log.i(tag, "Received command ${command.id} case=${command.payload.caseId}")
                AgentLogStore.log("Received command ${command.id} for case ${command.payload.caseId}")
                updateNotification("Working on case ${command.payload.caseId.take(8)}...")
                executeCommand(baseUrl, credentials, command)
            }.onFailure { error ->
                Log.e(tag, "Poll failure", error)
                AgentLogStore.log("Poll failure: ${error.message}")
                delay(3000)
            }
        }
    }

    // ── Command execution with real agent loop ──────────────────────────────

    private suspend fun executeCommand(
        baseUrl: String,
        credentials: DeviceCredentials,
        command: CompanionCommand,
    ) {
        runCatching {
            // Step 1: Report that we are starting.
            backendClient.postCommandEvent(
                baseUrl = baseUrl,
                credentials = credentials,
                commandId = command.id,
                message = "Agent starting: launching ${command.payload.targetPlatform}",
                stage = "start",
            )

            // Step 2: Launch the target app.
            val launched = tryLaunchApp(command.payload.targetPlatform)
            if (!launched) {
                backendClient.postCommandEvent(
                    baseUrl = baseUrl,
                    credentials = credentials,
                    commandId = command.id,
                    message = "App '${command.payload.targetPlatform}' not installed; attempting to proceed",
                )
            }

            // Step 3: Wait for the accessibility service to pick up the new window.
            delay(2000)

            // Step 4: Get the accessibility engine.
            val engine = waitForAccessibilityEngine(timeoutMs = 10_000)
            if (engine == null) {
                backendClient.failCommand(
                    baseUrl = baseUrl,
                    credentials = credentials,
                    commandId = command.id,
                    errorMessage = "Accessibility service is not enabled. " +
                        "Please enable the Resolve accessibility service in Settings > Accessibility.",
                )
                AgentLogStore.log("FAILED: Accessibility service not available")
                return
            }

            // Step 5: Get LLM config.
            val authManager = AuthManager(this@CompanionAgentService)
            val llmConfig = authManager.loadLLMConfig()
            if (llmConfig == null) {
                backendClient.failCommand(
                    baseUrl = baseUrl,
                    credentials = credentials,
                    commandId = command.id,
                    errorMessage = "No LLM API key configured. " +
                        "Please configure your API key in the Resolve app settings.",
                )
                AgentLogStore.log("FAILED: No LLM credentials configured")
                return
            }

            val llmClient = LLMClient(llmConfig)

            // Step 6: Build case context.
            val caseContext = CaseContext(
                caseId = command.payload.caseId,
                customerName = command.payload.customerName,
                issue = command.payload.issue,
                desiredOutcome = command.payload.desiredOutcome,
                orderId = command.payload.orderId,
                hasAttachments = command.payload.attachmentPaths.isNotEmpty(),
                targetPlatform = command.payload.targetPlatform,
            )

            // Step 7: Create and run the agent loop.
            val agentLoop = AgentLoop(
                engine = engine,
                llmClient = llmClient,
                caseContext = caseContext,
                onEvent = { event ->
                    // Forward significant events to the backend as progress updates.
                    handleAgentEvent(baseUrl, credentials, command.id, event)
                },
            )
            currentAgentLoop = agentLoop

            backendClient.postCommandEvent(
                baseUrl = baseUrl,
                credentials = credentials,
                commandId = command.id,
                message = "Agent loop starting: observing screen and planning actions",
                stage = "step",
            )

            currentAgentJob = scope.launch {
                val result = agentLoop.run()
                handleAgentResult(baseUrl, credentials, command.id, result)
            }

            // Wait for the agent job to complete.
            currentAgentJob?.join()

        }.onFailure { error ->
            val reason = error.message ?: "Unknown execution failure"
            Log.e(tag, "Command ${command.id} failed: $reason", error)
            AgentLogStore.log("Command ${command.id} failed: $reason")
            runCatching {
                backendClient.failCommand(
                    baseUrl = baseUrl,
                    credentials = credentials,
                    commandId = command.id,
                    errorMessage = reason,
                )
            }
        }

        currentAgentLoop = null
        currentAgentJob = null
        updateNotification("Agent idle -- waiting for commands")
    }

    // ── Agent event forwarding ──────────────────────────────────────────────

    private suspend fun handleAgentEvent(
        baseUrl: String,
        credentials: DeviceCredentials,
        commandId: String,
        event: AgentEvent,
    ) {
        val message = when (event) {
            is AgentEvent.Started -> "Agent loop started for case ${event.caseId}"
            is AgentEvent.ScreenCaptured -> "Screen captured: ${event.packageName} (${event.elementCount} elements)"
            is AgentEvent.ThinkingStarted -> null  // Too noisy for backend.
            is AgentEvent.DecisionMade -> "Decision: ${event.action}"
            is AgentEvent.ApprovalNeeded -> "APPROVAL NEEDED: ${event.reason}"
            is AgentEvent.ActionBlocked -> "ACTION BLOCKED: ${event.reason}"
            is AgentEvent.ActionExecuted -> {
                if (event.success) "Executed: ${event.description}"
                else "Failed to execute: ${event.description}"
            }
            is AgentEvent.HumanReviewRequested -> "HUMAN REVIEW: ${event.reason}"
            is AgentEvent.Resolved -> "RESOLVED: ${event.summary}"
            is AgentEvent.Error -> "ERROR: ${event.message}"
            is AgentEvent.Failed -> "FAILED: ${event.reason}"
            is AgentEvent.Cancelled -> "Agent cancelled"
        }

        if (message != null) {
            runCatching {
                backendClient.postCommandEvent(
                    baseUrl = baseUrl,
                    credentials = credentials,
                    commandId = commandId,
                    message = message,
                    stage = "step",
                )
            }.onFailure { e ->
                Log.w(tag, "Failed to post agent event to backend", e)
            }
        }
    }

    // ── Agent result handling ───────────────────────────────────────────────

    private suspend fun handleAgentResult(
        baseUrl: String,
        credentials: DeviceCredentials,
        commandId: String,
        result: AgentResult,
    ) {
        when (result) {
            is AgentResult.Resolved -> {
                Log.i(tag, "Command $commandId resolved: ${result.summary}")
                AgentLogStore.log("Command $commandId resolved after ${result.iterationsCompleted} iterations")
                runCatching {
                    backendClient.completeCommand(
                        baseUrl = baseUrl,
                        credentials = credentials,
                        commandId = commandId,
                        resultSummary = result.summary,
                    )
                }
            }

            is AgentResult.Failed -> {
                Log.e(tag, "Command $commandId failed: ${result.reason}")
                AgentLogStore.log("Command $commandId failed: ${result.reason}")
                runCatching {
                    backendClient.failCommand(
                        baseUrl = baseUrl,
                        credentials = credentials,
                        commandId = commandId,
                        errorMessage = result.reason,
                    )
                }
            }

            is AgentResult.NeedsHumanReview -> {
                Log.i(tag, "Command $commandId needs human review: ${result.reason}")
                AgentLogStore.log("Command $commandId paused for human review: ${result.reason}")
                runCatching {
                    backendClient.postCommandEvent(
                        baseUrl = baseUrl,
                        credentials = credentials,
                        commandId = commandId,
                        message = "Agent paused: human review required -- ${result.reason}",
                        stage = "paused",
                    )
                }
            }

            is AgentResult.Cancelled -> {
                Log.i(tag, "Command $commandId cancelled")
                AgentLogStore.log("Command $commandId cancelled by user")
                runCatching {
                    backendClient.failCommand(
                        baseUrl = baseUrl,
                        credentials = credentials,
                        commandId = commandId,
                        errorMessage = "Cancelled by user",
                    )
                }
            }
        }
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
            NotificationManager.IMPORTANCE_LOW,
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_agent)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText.ifBlank { getString(R.string.agent_notification_text) })
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(0, "Pause", pausePendingIntent)
            .addAction(0, "Stop", stopPendingIntent)
            .build()
    }

    companion object {
        private const val ACTION_START = "com.cssupport.companion.action.START"
        private const val ACTION_START_LOCAL = "com.cssupport.companion.action.START_LOCAL"
        private const val ACTION_STOP = "com.cssupport.companion.action.STOP"
        private const val ACTION_PAUSE = "com.cssupport.companion.action.PAUSE"
        private const val ACTION_RESUME = "com.cssupport.companion.action.RESUME"

        private const val EXTRA_BASE_URL = "extra_base_url"
        private const val EXTRA_DEVICE_ID = "extra_device_id"
        private const val EXTRA_DEVICE_TOKEN = "extra_device_token"

        // Extras for local (standalone) mode.
        private const val EXTRA_CASE_ID = "extra_case_id"
        private const val EXTRA_ISSUE = "extra_issue"
        private const val EXTRA_DESIRED_OUTCOME = "extra_desired_outcome"
        private const val EXTRA_ORDER_ID = "extra_order_id"
        private const val EXTRA_TARGET_PLATFORM = "extra_target_platform"
        private const val EXTRA_HAS_ATTACHMENTS = "extra_has_attachments"

        private const val CHANNEL_ID = "cs_support_companion"
        private const val NOTIFICATION_ID = 10011

        /**
         * Start the service in backend-connected mode (polls for commands).
         */
        fun start(
            context: Context,
            baseUrl: String,
            credentials: DeviceCredentials,
        ) {
            val intent = Intent(context, CompanionAgentService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_BASE_URL, baseUrl)
                putExtra(EXTRA_DEVICE_ID, credentials.deviceId)
                putExtra(EXTRA_DEVICE_TOKEN, credentials.deviceToken)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Start the service in standalone on-device mode (no backend needed).
         * Runs the agent loop directly with the provided case context.
         */
        fun startLocal(
            context: Context,
            caseId: String,
            issue: String,
            desiredOutcome: String,
            orderId: String?,
            targetPlatform: String,
            hasAttachments: Boolean,
        ) {
            val intent = Intent(context, CompanionAgentService::class.java).apply {
                action = ACTION_START_LOCAL
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
