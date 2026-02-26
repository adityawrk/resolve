package com.cssupport.companion

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * On-device agent loop that drives the observe-think-act cycle.
 *
 * ## Architecture (v3 -- Gold-Standard Agent Intelligence)
 *
 * Implements six research-backed improvements over the v2 agent:
 *
 * 1. **Numbered element references**: Every interactive element gets a [N] index.
 *    The LLM says `click_element(elementId=7)` instead of fuzzy text matching.
 *    (DroidRun, AppAgent, Minitap -- every top agent uses this.)
 *
 * 2. **Post-action verification**: After every action, capture the screen and verify
 *    the action actually worked. Report verification result to the LLM.
 *    (+15 points from Minitap ablation study.)
 *
 * 3. **Sub-goal decomposition + progress tracking**: Break the task into numbered
 *    sub-goals and track completion. Prevents goal drift over long runs.
 *    (+21 points from Minitap. Manus's "dynamic todo.md" pattern.)
 *
 * 4. **Observation masking**: Replace verbose screen dumps in older conversation turns
 *    with one-line summaries. Never remove messages (append-only preserves KV-cache).
 *    (JetBrains Dec 2025 research. Manus production system.)
 *
 * 5. **Screen stabilization**: Poll-compare-repeat until screen stops changing before
 *    acting. Prevents acting on transitioning/loading screens.
 *    (DroidRun technique.)
 *
 * 6. **Differential state tracking**: Tell the LLM WHAT changed between screens --
 *    not just "changed" vs "didn't change".
 *    (DroidRun's most impactful technique per their documentation.)
 *
 * Flow:
 * 1. Capture screen state via [AccessibilityEngine] (with stabilization)
 * 2. Detect what changed from previous screen (differential state)
 * 3. Determine navigation phase and update sub-goal progress
 * 4. Apply observation masking to older conversation turns
 * 5. Send multi-turn conversation to LLM with numbered element references
 * 6. Validate action against [SafetyPolicy]
 * 7. Execute action via [AccessibilityEngine]
 * 8. Verify action outcome (post-action verification)
 * 9. Record turn with verification result in conversation history
 * 10. Repeat until resolved, failed, paused, or max iterations
 */
class AgentLoop(
    private val engine: AccessibilityEngine,
    private val llmClient: LLMClient,
    private val caseContext: CaseContext,
    private val safetyPolicy: SafetyPolicy = SafetyPolicy(),
    private val onEvent: suspend (AgentEvent) -> Unit = {},
    private val launchTargetApp: (() -> Boolean)? = null,
) {

    private val tag = "AgentLoop"

    private val _state = MutableStateFlow(AgentLoopState.IDLE)
    val state = _state.asStateFlow()

    // -- Conversation history (multi-turn, append-only) ----------------------

    /** Full conversation history sent to the LLM for multi-turn context. */
    private val conversationHistory = mutableListOf<ConversationMessage>()

    /**
     * Estimated token count of the conversation history.
     * Rough heuristic: 1 token ~ 4 chars.
     */
    private var estimatedTokens = 0

    // -- State tracking -------------------------------------------------------

    private var iterationCount = 0
    private var consecutiveDuplicates = 0
    private var lastActionSignature = ""
    private var llmRetryCount = 0

    /** The previous screen state for differential tracking and verification. */
    private var previousScreenState: ScreenState? = null
    private var previousScreenFingerprint = ""

    /** Recent screen fingerprints for oscillation detection (A->B->A->B pattern). */
    private val recentFingerprints = mutableListOf<String>()

    /** Current navigation phase, detected from screen content. */
    private var currentPhase = NavigationPhase.NAVIGATING_TO_SUPPORT

    /** Count of consecutive screens with no meaningful change. */
    private var stagnationCount = 0

    /** Count of total scroll actions, to limit aimless scrolling. */
    private var totalScrollCount = 0

    // -- Sub-goal tracking (Manus's dynamic todo.md pattern) ------------------

    /** Ordered list of sub-goals for the current case. */
    private val subGoals = mutableListOf<SubGoal>()

    /** Whether sub-goals have been initialized. */
    private var subGoalsInitialized = false

    // -- Observation masking bookkeeping --------------------------------------

    /**
     * Index of the conversation turn boundary below which observations are masked.
     * Everything below this index has already been masked.
     */
    private var maskedUpToIndex = 0

    private companion object {
        const val OWN_PACKAGE = "com.cssupport.companion"
        const val MAX_FOREGROUND_WAIT_MS = 60_000L
        const val MAX_CONSECUTIVE_DUPLICATES = 3

        /**
         * Number of recent full-detail turns to keep when masking observations.
         * Each "turn" is 3 messages (observation + tool call + result).
         * Turns older than this get their observation content replaced with a summary.
         */
        const val OBSERVATION_MASK_KEEP_RECENT = 4

        /** Maximum estimated tokens before we aggressively mask more. */
        const val MAX_ESTIMATED_TOKENS = 12000

        /** Maximum scroll actions before the agent gets a warning. */
        const val MAX_SCROLL_ACTIONS = 8

        /** Recent fingerprints window for oscillation detection. */
        const val FINGERPRINT_WINDOW = 6
    }

    @Volatile
    private var paused = false

    /**
     * Run the main agent loop. Suspends until the loop completes (resolved/failed/cancelled).
     */
    suspend fun run(): AgentResult {
        _state.value = AgentLoopState.RUNNING
        iterationCount = 0
        conversationHistory.clear()
        estimatedTokens = 0
        previousScreenState = null
        previousScreenFingerprint = ""
        recentFingerprints.clear()
        currentPhase = NavigationPhase.NAVIGATING_TO_SUPPORT
        stagnationCount = 0
        totalScrollCount = 0
        subGoals.clear()
        subGoalsInitialized = false
        maskedUpToIndex = 0

        emit(AgentEvent.Started(caseId = caseContext.caseId))
        AgentLogStore.log("Agent loop started for case ${caseContext.caseId}", LogCategory.STATUS_UPDATE, "Starting...")

        return try {
            val result = executeLoop()
            _state.value = AgentLoopState.COMPLETED
            result
        } catch (e: CancellationException) {
            _state.value = AgentLoopState.CANCELLED
            emit(AgentEvent.Cancelled)
            AgentLogStore.log("Agent loop cancelled", LogCategory.STATUS_UPDATE, "Cancelled")
            AgentResult.Cancelled
        } catch (e: Exception) {
            _state.value = AgentLoopState.FAILED
            val displayMessage = when {
                e.message?.contains("401") == true ->
                    "Your API key appears to be invalid. Check Settings."
                e.message?.contains("429") == true ->
                    "Too many requests. Try again in a moment."
                e is java.net.UnknownHostException || e is java.net.ConnectException ->
                    "No internet connection. Please check your network."
                e is java.net.SocketTimeoutException ->
                    "The request timed out. Try again."
                e.message?.contains("HTTP") == true ->
                    "Could not reach the AI service. Check your internet connection."
                else ->
                    "Something went wrong. Please try again."
            }
            emit(AgentEvent.Failed(reason = displayMessage))
            AgentLogStore.log("Agent loop failed: ${e.message}", LogCategory.TERMINAL_FAILED, displayMessage)
            Log.e(tag, "Agent loop failed: ${e.message}", e)
            AgentResult.Failed(reason = displayMessage)
        }
    }

    fun pause() {
        paused = true
        _state.value = AgentLoopState.PAUSED
        AgentLogStore.log("Agent loop paused", LogCategory.STATUS_UPDATE, "Paused")
    }

    fun resume() {
        paused = false
        _state.value = AgentLoopState.RUNNING
        AgentLogStore.log("Agent loop resumed", LogCategory.STATUS_UPDATE, "Resuming...")
    }

    private suspend fun executeLoop(): AgentResult {
        // Initialize sub-goals based on case context.
        initializeSubGoals()

        while (iterationCount < SafetyPolicy.MAX_ITERATIONS) {
            currentCoroutineContext().ensureActive()

            // Handle pause state.
            while (paused) {
                currentCoroutineContext().ensureActive()
                delay(500)
            }

            iterationCount++
            Log.d(tag, "--- Iteration $iterationCount ---")

            // Step 1: Observe -- capture stable screen state.
            val screenState = if (iterationCount == 1) {
                // First iteration: just capture, no stabilization wait.
                engine.captureScreenState()
            } else {
                // Subsequent iterations: wait for screen to stabilize after last action.
                engine.waitForStableScreen(maxWaitMs = 4000, pollIntervalMs = 500)
            }

            emit(AgentEvent.ScreenCaptured(
                packageName = screenState.packageName,
                elementCount = screenState.elements.size,
            ))

            // Skip if we're looking at our own app -- try to launch target and wait.
            if (screenState.packageName == OWN_PACKAGE) {
                Log.d(tag, "Own app is in foreground, trying to launch target app...")
                iterationCount-- // Don't count this as a real iteration.

                if (launchTargetApp != null) {
                    launchTargetApp.invoke()
                }
                AgentLogStore.log("Opening target app", LogCategory.STATUS_UPDATE, "Opening app...")

                val waitStart = System.currentTimeMillis()
                var retryLaunchCount = 0
                while (engine.captureScreenState().packageName == OWN_PACKAGE) {
                    currentCoroutineContext().ensureActive()
                    val elapsed = System.currentTimeMillis() - waitStart

                    if (launchTargetApp != null && retryLaunchCount < 4 && elapsed > (retryLaunchCount + 1) * 8_000L) {
                        retryLaunchCount++
                        Log.d(tag, "Retrying app launch (attempt $retryLaunchCount)")
                        launchTargetApp.invoke()
                    }

                    if (elapsed > MAX_FOREGROUND_WAIT_MS) {
                        AgentLogStore.log("Could not open the app automatically", LogCategory.ERROR, "Please open the app manually and try again")
                        return AgentResult.Failed(
                            reason = "Could not open the app. Please open it manually and try again.",
                        )
                    }
                    delay(1000)
                }
                AgentLogStore.log("App opened", LogCategory.STATUS_UPDATE, "Navigating...")
                continue
            }

            // Step 2: Detect changes and update phase.
            val currentFingerprint = screenState.fingerprint()
            val changeDescription = detectChanges(currentFingerprint, screenState)
            updateNavigationPhase(screenState)
            updateSubGoalProgress(screenState)
            trackFingerprint(currentFingerprint)

            // Step 2b: Check for stagnation (screen not changing).
            if (changeDescription.startsWith("NO_CHANGE")) {
                stagnationCount++
                if (stagnationCount >= 3) {
                    Log.w(tag, "Screen has not changed for $stagnationCount turns")
                    addStagnationHint()
                }
            } else {
                stagnationCount = 0
            }

            // Step 2c: Check for oscillation (A->B->A->B pattern).
            val oscillationWarning = detectOscillation()

            // Step 3: Build the user message with numbered elements and progress tracker.
            val formattedScreen = screenState.formatForLLM(previousScreen = previousScreenState)
            val userMessage = buildUserMessage(formattedScreen, changeDescription, oscillationWarning)

            // Save state for next iteration's differential tracking.
            previousScreenState = screenState
            previousScreenFingerprint = currentFingerprint

            emit(AgentEvent.ThinkingStarted)
            AgentLogStore.log("[$iterationCount] Thinking...", LogCategory.STATUS_UPDATE, "Thinking...")

            // Step 3b: Apply observation masking to older conversation turns.
            applyObservationMasking()

            val decision: AgentDecision
            try {
                decision = llmClient.chatCompletion(
                    systemPrompt = buildSystemPrompt(),
                    userMessage = userMessage,
                    conversationMessages = conversationHistory.toList(),
                )
                llmRetryCount = 0
            } catch (e: Exception) {
                llmRetryCount++
                val backoffMs = (3000L * (1 shl llmRetryCount.coerceAtMost(4))).coerceAtMost(30_000L)
                val waitSec = backoffMs / 1000
                Log.e(tag, "LLM call failed: ${e.javaClass.simpleName}: ${e.message}", e)
                emit(AgentEvent.Error(message = "LLM error: ${e.javaClass.simpleName}: ${e.message}"))

                val shortError = when {
                    e.message?.contains("401") == true -> "API key invalid or expired"
                    e.message?.contains("403") == true -> "Access denied — check API key"
                    e.message?.contains("404") == true -> "Model not found — check Settings"
                    e.message?.contains("429") == true -> "Rate limited, retrying in ${waitSec}s..."
                    e is java.net.UnknownHostException -> "No internet connection"
                    e is java.net.ConnectException -> "Cannot reach AI service"
                    e is java.net.SocketTimeoutException -> "Request timed out, retrying..."
                    else -> "AI error: ${e.message?.take(80) ?: "unknown"}"
                }
                AgentLogStore.log(
                    "[$iterationCount] LLM error: ${e.javaClass.simpleName}: ${e.message}",
                    LogCategory.ERROR,
                    shortError,
                )

                if (llmRetryCount >= 5) {
                    return AgentResult.Failed(reason = shortError)
                }

                delay(backoffMs)
                continue
            }

            Log.d(tag, "Decision: action=${decision.action}, reasoning=${decision.reasoning.take(100)}")
            emit(AgentEvent.DecisionMade(
                action = describeAction(decision.action),
                reasoning = decision.reasoning,
            ))

            // Step 4: Validate against safety policy.
            val policyResult = safetyPolicy.validate(decision.action, iterationCount)
            when (policyResult) {
                is PolicyResult.Allowed -> { /* proceed */ }
                is PolicyResult.NeedsApproval -> {
                    emit(AgentEvent.ApprovalNeeded(reason = policyResult.reason))
                    AgentLogStore.log("[$iterationCount] NEEDS APPROVAL: ${policyResult.reason}", LogCategory.APPROVAL_NEEDED, "Needs your approval")
                    return AgentResult.NeedsHumanReview(
                        reason = policyResult.reason,
                        iterationsCompleted = iterationCount,
                    )
                }
                is PolicyResult.Blocked -> {
                    emit(AgentEvent.ActionBlocked(reason = policyResult.reason))
                    AgentLogStore.log("[$iterationCount] BLOCKED: ${policyResult.reason}", LogCategory.ERROR, "Action blocked: ${policyResult.reason}")
                    if (iterationCount >= SafetyPolicy.MAX_ITERATIONS) {
                        return AgentResult.Failed(
                            reason = "Maximum iterations reached without resolution",
                        )
                    }
                    delay(SafetyPolicy.MIN_ACTION_DELAY_MS)
                    continue
                }
            }

            // Step 4b: Detect repeated identical actions.
            val actionSignature = describeAction(decision.action)
            if (actionSignature == lastActionSignature) {
                consecutiveDuplicates++
                if (consecutiveDuplicates >= MAX_CONSECUTIVE_DUPLICATES) {
                    Log.w(tag, "Action repeated $consecutiveDuplicates times: $actionSignature")
                    AgentLogStore.log("[$iterationCount] Skipping repeated action (tried $consecutiveDuplicates times)", LogCategory.DEBUG)
                    consecutiveDuplicates = 0
                    lastActionSignature = ""

                    // Record the failed attempt in conversation so the LLM knows.
                    recordConversationTurn(
                        userMessage,
                        decision,
                        "FAILED: Action '$actionSignature' was repeated $MAX_CONSECUTIVE_DUPLICATES times and skipped. You MUST try a different approach.",
                    )

                    delay(SafetyPolicy.MIN_ACTION_DELAY_MS)
                    continue
                }
            } else {
                consecutiveDuplicates = 0
                lastActionSignature = actionSignature
            }

            // Step 4c: Track scroll actions.
            if (decision.action is AgentAction.ScrollDown || decision.action is AgentAction.ScrollUp) {
                totalScrollCount++
            }

            // Step 5: Execute the action.
            val actionDescription = actionSignature
            logAction(decision.action, actionDescription)

            // Capture pre-action screen for verification.
            val preActionFingerprint = currentFingerprint

            val actionResult = executeAction(decision.action, screenState)

            // Step 6: Post-action verification.
            val verificationResult = if (actionResult is ActionResult.Success) {
                verifyAction(decision.action, preActionFingerprint, screenState)
            } else {
                null // Skip verification for failed/terminal actions.
            }

            // Step 7: Record the turn in conversation history with verification feedback.
            val resultDescription = buildResultDescription(actionResult, verificationResult)
            recordConversationTurn(userMessage, decision, resultDescription)

            when (actionResult) {
                is ActionResult.Success -> {
                    emit(AgentEvent.ActionExecuted(description = actionDescription, success = true))
                }
                is ActionResult.Failed -> {
                    emit(AgentEvent.ActionExecuted(description = actionDescription, success = false))
                    AgentLogStore.log("[$iterationCount] Action failed: ${actionResult.reason}", LogCategory.ERROR, "Action failed")
                }
                is ActionResult.Resolved -> {
                    emit(AgentEvent.Resolved(summary = actionResult.summary))
                    AgentLogStore.log("Case resolved: ${actionResult.summary}", LogCategory.TERMINAL_RESOLVED, "Issue resolved!")
                    return AgentResult.Resolved(
                        summary = actionResult.summary,
                        iterationsCompleted = iterationCount,
                    )
                }
                is ActionResult.HumanReviewNeeded -> {
                    emit(AgentEvent.HumanReviewRequested(
                        reason = actionResult.reason,
                        inputPrompt = actionResult.inputPrompt,
                    ))
                    AgentLogStore.log("[$iterationCount] Human review requested: ${actionResult.reason}", LogCategory.APPROVAL_NEEDED, "Needs your input")
                    return AgentResult.NeedsHumanReview(
                        reason = actionResult.reason,
                        iterationsCompleted = iterationCount,
                    )
                }
            }

            // Step 8: Wait for screen to update before next iteration.
            delay(SafetyPolicy.MIN_ACTION_DELAY_MS)
        }

        // Exhausted iterations.
        return AgentResult.Failed(
            reason = "Reached maximum of $iterationCount iterations without resolving the issue",
        )
    }

    // == Sub-goal tracking (Manus's dynamic todo.md pattern) ==================

    /**
     * Initialize sub-goals based on the case context.
     * These are hardcoded templates that cover the common customer support flow.
     * The progress tracker is included in every user message to prevent goal drift.
     */
    private fun initializeSubGoals() {
        if (subGoalsInitialized) return
        subGoalsInitialized = true

        subGoals.addAll(listOf(
            SubGoal("Open ${caseContext.targetPlatform} app", SubGoalStatus.PENDING),
            SubGoal("Navigate to Account/Profile section", SubGoalStatus.PENDING),
            SubGoal("Find Orders or Order History", SubGoalStatus.PENDING),
            SubGoal(
                if (!caseContext.orderId.isNullOrBlank())
                    "Locate order ${caseContext.orderId}"
                else
                    "Find the relevant order",
                SubGoalStatus.PENDING,
            ),
            SubGoal("Open Help/Support for that order", SubGoalStatus.PENDING),
            SubGoal("Reach support chat interface", SubGoalStatus.PENDING),
            SubGoal("Describe issue and request ${caseContext.desiredOutcome}", SubGoalStatus.PENDING),
            SubGoal("Wait for and accept resolution", SubGoalStatus.PENDING),
        ))
    }

    /**
     * Update sub-goal completion based on navigation phase transitions and key events.
     */
    private fun updateSubGoalProgress(screenState: ScreenState) {
        if (subGoals.isEmpty()) return

        // Sub-goal 0: Open target app -- completed if we're in the right package.
        val targetPkg = caseContext.targetPlatform.lowercase()
        val currentPkg = screenState.packageName.lowercase()
        if (currentPkg != OWN_PACKAGE && currentPkg.isNotEmpty()) {
            markSubGoalDone(0)
        }

        // Sub-goal 1: Navigate to Account/Profile.
        val allText = screenState.elements.mapNotNull {
            it.text?.lowercase() ?: it.contentDescription?.lowercase()
        }.joinToString(" ")
        if (allText.contains("account") || allText.contains("profile") || allText.contains("my account")) {
            if (currentPhase != NavigationPhase.NAVIGATING_TO_SUPPORT || allText.contains("settings") || allText.contains("order")) {
                markSubGoalDone(1)
            }
        }

        // Sub-goal 2: Find Orders.
        if (currentPhase == NavigationPhase.ON_ORDER_PAGE || allText.contains("order history") || allText.contains("my orders") || allText.contains("your orders")) {
            markSubGoalDone(1)
            markSubGoalDone(2)
        }

        // Sub-goal 3: Locate specific order.
        if (!caseContext.orderId.isNullOrBlank() && allText.contains(caseContext.orderId.lowercase())) {
            markSubGoalDone(2)
            markSubGoalDone(3)
        } else if (currentPhase == NavigationPhase.ON_ORDER_PAGE && (allText.contains("help") || allText.contains("support"))) {
            markSubGoalDone(3) // On an order detail page with help option.
        }

        // Sub-goal 4: Open Help/Support.
        if (currentPhase == NavigationPhase.ON_SUPPORT_PAGE) {
            markSubGoalDone(0); markSubGoalDone(1); markSubGoalDone(2); markSubGoalDone(3)
            markSubGoalDone(4)
        }

        // Sub-goal 5: Reach support chat.
        if (currentPhase == NavigationPhase.IN_CHAT) {
            markSubGoalDone(0); markSubGoalDone(1); markSubGoalDone(2); markSubGoalDone(3)
            markSubGoalDone(4); markSubGoalDone(5)
        }
    }

    private fun markSubGoalDone(index: Int) {
        if (index in subGoals.indices && subGoals[index].status != SubGoalStatus.DONE) {
            subGoals[index] = subGoals[index].copy(status = SubGoalStatus.DONE)
        }
    }

    /**
     * Format the progress tracker for inclusion in user messages.
     */
    private fun formatProgressTracker(): String {
        if (subGoals.isEmpty()) return ""
        return buildString {
            appendLine("## Progress Tracker")
            for ((i, goal) in subGoals.withIndex()) {
                val marker = when (goal.status) {
                    SubGoalStatus.DONE -> "[x]"
                    SubGoalStatus.IN_PROGRESS -> "[~]"
                    SubGoalStatus.PENDING -> "[ ]"
                }
                appendLine("${i + 1}. $marker ${goal.description}")
            }
        }
    }

    // == Observation masking (append-only, JetBrains/Manus pattern) ===========

    /**
     * Apply observation masking to older conversation turns.
     *
     * Strategy (from JetBrains Dec 2025 + Manus production):
     * - NEVER remove messages from history (append-only preserves KV-cache prefix)
     * - For turns older than the last [OBSERVATION_MASK_KEEP_RECENT], replace
     *   the UserObservation content with a one-line summary
     * - Keep ALL AssistantToolCall and ToolResult messages intact
     *   (the LLM needs the full action trace)
     */
    private fun applyObservationMasking() {
        // Each turn is 3 messages: UserObservation, AssistantToolCall, ToolResult.
        val turnCount = conversationHistory.size / 3
        if (turnCount <= OBSERVATION_MASK_KEEP_RECENT) return

        val turnsToMask = turnCount - OBSERVATION_MASK_KEEP_RECENT
        val messagesToScan = turnsToMask * 3

        for (i in maskedUpToIndex until messagesToScan.coerceAtMost(conversationHistory.size)) {
            val msg = conversationHistory[i]
            if (msg is ConversationMessage.UserObservation && !msg.content.startsWith("[Screen:")) {
                // Replace verbose observation with a compact summary.
                val summary = extractObservationSummary(msg.content)
                conversationHistory[i] = ConversationMessage.UserObservation(content = summary)
            }
        }

        maskedUpToIndex = messagesToScan

        // Re-estimate tokens after masking.
        estimatedTokens = conversationHistory.sumOf { msg ->
            when (msg) {
                is ConversationMessage.UserObservation -> msg.content.length
                is ConversationMessage.AssistantToolCall -> msg.reasoning.length + msg.toolArguments.length + 100
                is ConversationMessage.ToolResult -> msg.result.length + 50
            }
        } / 4
    }

    /**
     * Extract a compact summary from a verbose user observation message.
     */
    private fun extractObservationSummary(content: String): String {
        // Try to extract package, element count, and phase from the content.
        val packageLine = content.lineSequence().firstOrNull { it.startsWith("Package:") }?.trim()
            ?: ""
        val screenLine = content.lineSequence().firstOrNull { it.startsWith("Screen:") }?.trim()
            ?: ""
        val phaseLine = content.lineSequence().firstOrNull { it.startsWith("Phase:") }?.trim()
            ?: "Phase: $currentPhase"
        val turnLine = content.lineSequence().firstOrNull { it.startsWith("Turn:") }?.trim()
            ?: ""

        return "[Screen: ${packageLine.removePrefix("Package: ")}/${screenLine.removePrefix("Screen: ")}, $phaseLine, $turnLine]"
    }

    // == Differential state detection (enhanced) ==============================

    /**
     * Compare current screen with previous screen and describe WHAT changed.
     * Returns a detailed description instead of just "CHANGED" / "NO_CHANGE".
     *
     * This gives the LLM much richer signal about the effect of its last action.
     * (DroidRun's most impactful technique.)
     */
    private fun detectChanges(currentFingerprint: String, currentState: ScreenState): String {
        if (previousScreenFingerprint.isEmpty()) return "FIRST_SCREEN"
        if (currentFingerprint == previousScreenFingerprint) return "NO_CHANGE: Screen is identical to the previous turn."

        val prevState = previousScreenState ?: return "SCREEN_CHANGED"

        // Detect what kind of change occurred.
        val prevPkg = prevState.packageName
        val curPkg = currentState.packageName
        val prevActivity = prevState.activityName?.substringAfterLast(".")
        val curActivity = currentState.activityName?.substringAfterLast(".")

        val sb = StringBuilder()

        if (prevPkg != curPkg) {
            sb.append("NEW APP: Changed from $prevPkg to $curPkg. ")
        } else if (prevActivity != curActivity && curActivity != null) {
            sb.append("NEW SCREEN: $curActivity (was: ${prevActivity ?: "unknown"}). ")
        } else {
            // Same app, same activity -- content within the page changed.
            val newLabels = currentState.newElementLabels(prevState)
            val removedLabels = currentState.removedElementLabels(prevState)

            if (newLabels.isNotEmpty() || removedLabels.isNotEmpty()) {
                sb.append("CONTENT_UPDATED: ")
                if (newLabels.isNotEmpty()) {
                    sb.append("New elements: ${newLabels.take(5).joinToString(", ") { "\"$it\"" }}. ")
                }
                if (removedLabels.isNotEmpty()) {
                    sb.append("Removed: ${removedLabels.take(5).joinToString(", ") { "\"$it\"" }}. ")
                }
            } else {
                sb.append("SCREEN_CHANGED: Layout or positions shifted. ")
            }
        }

        return sb.toString().trim()
    }

    /**
     * Track screen fingerprint for oscillation detection.
     */
    private fun trackFingerprint(fingerprint: String) {
        recentFingerprints.add(fingerprint)
        while (recentFingerprints.size > FINGERPRINT_WINDOW) {
            recentFingerprints.removeAt(0)
        }
    }

    /**
     * Detect A->B->A->B oscillation pattern.
     * Returns a warning string if oscillation is detected, or empty string.
     */
    private fun detectOscillation(): String {
        if (recentFingerprints.size < 4) return ""

        val last4 = recentFingerprints.takeLast(4)
        // Pattern: A B A B (alternating between two screens)
        if (last4[0] == last4[2] && last4[1] == last4[3] && last4[0] != last4[1]) {
            return "WARNING: You are oscillating between two screens. Your last 4 actions brought you back and forth. STOP and try a completely different navigation path."
        }

        // Pattern: A A A (stuck on same screen despite actions)
        val last3 = recentFingerprints.takeLast(3)
        if (last3.distinct().size == 1) {
            return "WARNING: The screen has not changed after your last 3 actions. Your actions are not having any effect. Try a fundamentally different approach."
        }

        return ""
    }

    /**
     * Add a hint to the conversation when the agent is stagnating.
     */
    private fun addStagnationHint() {
        val hint = "SYSTEM: The screen has not changed for $stagnationCount turns. " +
            "Your recent actions had no visible effect. Possible reasons: " +
            "(1) You are clicking elements that are not actually interactive, " +
            "(2) A popup or overlay is blocking interaction, " +
            "(3) You need to scroll to find the right element. " +
            "Try: press_back to dismiss any overlay, or look for a different navigation path."
        conversationHistory.add(ConversationMessage.UserObservation(content = hint))
        estimatedTokens += hint.length / 4
    }

    // == Navigation phase detection ===========================================

    /**
     * Detect what navigation phase the agent is currently in.
     */
    private fun updateNavigationPhase(screenState: ScreenState) {
        val allText = screenState.elements.mapNotNull {
            it.text?.lowercase() ?: it.contentDescription?.lowercase()
        }.joinToString(" ")
        val hasInputField = screenState.elements.any { it.isEditable }

        currentPhase = when {
            // Chat phase: there's an input field and the screen looks like a conversation.
            hasInputField && (allText.contains("send") || allText.contains("type a message")
                || allText.contains("type here") || allText.contains("write a message")
                || allText.contains("chat")) -> NavigationPhase.IN_CHAT

            // Help/support page.
            allText.contains("help") && (allText.contains("support") || allText.contains("contact")
                || allText.contains("faq") || allText.contains("get help")) -> NavigationPhase.ON_SUPPORT_PAGE

            // Order detail page.
            allText.contains("order") && (allText.contains("help") || allText.contains("support")
                || allText.contains("track") || allText.contains("details")) -> NavigationPhase.ON_ORDER_PAGE

            // Default: still navigating.
            else -> NavigationPhase.NAVIGATING_TO_SUPPORT
        }
    }

    // == Post-action verification (+15 points from Minitap) ===================

    /**
     * Verify that an action actually had its intended effect.
     *
     * Captures a fresh screen state and compares with pre-action state.
     * Returns a verification result that gets included in the tool result message,
     * giving the LLM immediate feedback about whether its action worked.
     */
    private suspend fun verifyAction(
        action: AgentAction,
        preActionFingerprint: String,
        preActionState: ScreenState,
    ): VerificationResult {
        // Short delay to let the action take effect.
        delay(600)
        val postState = engine.captureScreenState()
        val postFingerprint = postState.fingerprint()

        return when (action) {
            is AgentAction.ClickElement -> {
                if (postFingerprint != preActionFingerprint) {
                    val newActivity = postState.activityName?.substringAfterLast(".")
                    val prevActivity = preActionState.activityName?.substringAfterLast(".")
                    if (postState.packageName != preActionState.packageName) {
                        VerificationResult.Success("Screen changed to different app: ${postState.packageName}")
                    } else if (newActivity != prevActivity && newActivity != null) {
                        VerificationResult.Success("Screen changed to: $newActivity")
                    } else {
                        val newElements = postState.newElementLabels(preActionState)
                        if (newElements.isNotEmpty()) {
                            VerificationResult.Success("Screen updated. New elements: ${newElements.take(3).joinToString(", ") { "\"$it\"" }}")
                        } else {
                            VerificationResult.Success("Screen content changed.")
                        }
                    }
                } else {
                    VerificationResult.Warning("Click executed but screen did not change. The element may not be interactive or a popup may need dismissal.")
                }
            }

            is AgentAction.TypeMessage -> {
                // Minitap's key insight: read back the field content and verify.
                val fieldText = if (action.elementId != null) {
                    engine.readFieldTextByIndex(action.elementId, postState)
                } else {
                    engine.readFirstFieldText()
                }

                if (fieldText != null && fieldText.contains(action.text.take(20))) {
                    VerificationResult.Success("Text entered successfully.")
                } else if (fieldText.isNullOrBlank()) {
                    // Field may have been cleared after send -- check if screen changed.
                    if (postFingerprint != preActionFingerprint) {
                        VerificationResult.Success("Message sent (field cleared, screen updated).")
                    } else {
                        VerificationResult.Warning("Text was typed but field appears empty and screen unchanged. The message may not have been sent.")
                    }
                } else {
                    VerificationResult.Warning("Text typed but field content does not match expected text. Field contains: \"${fieldText.take(50)}\"")
                }
            }

            is AgentAction.ScrollDown, is AgentAction.ScrollUp -> {
                if (postFingerprint != preActionFingerprint) {
                    VerificationResult.Success("Content scrolled successfully.")
                } else {
                    VerificationResult.Warning("Scroll executed but content did not change. You may have reached the end of the scrollable area.")
                }
            }

            is AgentAction.PressBack -> {
                if (postFingerprint != preActionFingerprint) {
                    val newActivity = postState.activityName?.substringAfterLast(".")
                    VerificationResult.Success("Back pressed. Now on: ${newActivity ?: postState.packageName}")
                } else {
                    VerificationResult.Warning("Back pressed but screen did not change. The back action may have been consumed by a non-visible component.")
                }
            }

            // Actions that don't need verification.
            is AgentAction.Wait,
            is AgentAction.UploadFile,
            is AgentAction.RequestHumanReview,
            is AgentAction.MarkResolved -> null
        } ?: VerificationResult.Success("Action completed.")
    }

    /**
     * Build the result description from action result and verification.
     */
    private fun buildResultDescription(
        actionResult: ActionResult,
        verification: VerificationResult?,
    ): String {
        return when (actionResult) {
            is ActionResult.Success -> {
                when (verification) {
                    is VerificationResult.Success -> "OK: ${verification.observation}"
                    is VerificationResult.Warning -> "WARNING: ${verification.observation}"
                    null -> "Success."
                }
            }
            is ActionResult.Failed -> "FAILED: ${actionResult.reason}. Try a different approach."
            is ActionResult.Resolved -> "RESOLVED: ${actionResult.summary}"
            is ActionResult.HumanReviewNeeded -> "Pausing for human input: ${actionResult.reason}"
        }
    }

    // == Action execution =====================================================

    private suspend fun executeAction(action: AgentAction, currentScreenState: ScreenState): ActionResult {
        return when (action) {
            is AgentAction.TypeMessage -> {
                val success = if (action.elementId != null) {
                    engine.setTextByIndex(action.elementId, action.text, currentScreenState)
                } else {
                    val fields = engine.findInputFields()
                    if (fields.isEmpty()) {
                        return ActionResult.Failed("No input field found on screen")
                    }
                    try {
                        engine.setText(fields.first(), action.text)
                    } finally {
                        @Suppress("DEPRECATION")
                        fields.forEach { try { it.recycle() } catch (_: Exception) {} }
                    }
                }

                if (!success) {
                    return ActionResult.Failed("Could not set text in input field")
                }

                delay(300)
                trySendMessage()
                ActionResult.Success
            }

            is AgentAction.ClickElement -> {
                // Prefer index-based click (exact match) over text-based (fuzzy).
                val success = if (action.elementId != null) {
                    engine.clickByIndex(action.elementId, currentScreenState)
                } else if (!action.label.isNullOrBlank()) {
                    engine.clickByText(action.label)
                } else {
                    false
                }

                if (success) {
                    ActionResult.Success
                } else {
                    // Try content description fallback for icon buttons.
                    val fallbackLabel = action.label ?: action.expectedOutcome
                    if (fallbackLabel.isNotBlank()) {
                        val fallback = tryClickByContentDescription(fallbackLabel)
                        if (fallback) ActionResult.Success
                        else ActionResult.Failed("Could not find or click element${if (action.elementId != null) " [${ action.elementId }]" else ""}: '${action.label ?: action.expectedOutcome}'")
                    } else {
                        ActionResult.Failed("Could not click element: no elementId or label provided")
                    }
                }
            }

            is AgentAction.ScrollDown -> {
                val success = engine.scrollScreenForward()
                if (success) ActionResult.Success
                else ActionResult.Failed("No scrollable container found")
            }

            is AgentAction.ScrollUp -> {
                val success = engine.scrollScreenBackward()
                if (success) ActionResult.Success
                else ActionResult.Failed("No scrollable container found")
            }

            is AgentAction.Wait -> {
                engine.waitForContentChange(timeoutMs = 5000)
                ActionResult.Success
            }

            is AgentAction.UploadFile -> {
                val clicked = tryClickUploadButton()
                if (clicked) ActionResult.Success
                else ActionResult.Failed("Could not find upload/attachment button")
            }

            is AgentAction.PressBack -> {
                val success = engine.pressBack()
                if (success) ActionResult.Success
                else ActionResult.Failed("Could not press back")
            }

            is AgentAction.RequestHumanReview -> {
                ActionResult.HumanReviewNeeded(
                    reason = action.reason,
                    inputPrompt = action.inputPrompt,
                )
            }

            is AgentAction.MarkResolved -> {
                ActionResult.Resolved(summary = action.summary)
            }
        }
    }

    /**
     * Try to click an element by its content description (for icon buttons).
     */
    private fun tryClickByContentDescription(description: String): Boolean {
        val root = try {
            val service = SupportAccessibilityService.instance ?: return false
            service.rootInActiveWindow ?: return false
        } catch (e: Exception) {
            return false
        }

        return try {
            val found = root.findAccessibilityNodeInfosByText(description)
            if (found.isNullOrEmpty()) return false

            val target = found.firstOrNull { it.isClickable }
                ?: found.firstOrNull { it.contentDescription?.toString()?.contains(description, ignoreCase = true) == true }
                ?: found.firstOrNull()

            if (target != null) {
                val result = engine.clickNode(target)
                @Suppress("DEPRECATION")
                found.forEach { try { it.recycle() } catch (_: Exception) {} }
                result
            } else {
                @Suppress("DEPRECATION")
                found.forEach { try { it.recycle() } catch (_: Exception) {} }
                false
            }
        } finally {
            @Suppress("DEPRECATION")
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    /**
     * After typing a message, try to find and click a "Send" button.
     */
    private fun trySendMessage(): Boolean {
        val sendLabels = listOf("Send", "send", "Submit", "submit")
        for (label in sendLabels) {
            val node = engine.findNodeByText(label)
            if (node != null) {
                val result = engine.clickNode(node)
                @Suppress("DEPRECATION")
                try { node.recycle() } catch (_: Exception) {}
                if (result) return true
            }
        }

        val sendIds = listOf(
            "send_button", "btn_send", "send", "submit",
            "com.android.mms:id/send_button_sms",
        )
        for (id in sendIds) {
            val node = engine.findNodeById(id)
                ?: engine.findNodeById("*:id/$id")
            if (node != null) {
                val result = engine.clickNode(node)
                @Suppress("DEPRECATION")
                try { node.recycle() } catch (_: Exception) {}
                if (result) return true
            }
        }

        return false
    }

    /**
     * Try to find and click an upload/attachment button.
     */
    private fun tryClickUploadButton(): Boolean {
        val labels = listOf(
            "Attach", "attach", "Upload", "upload",
            "Add file", "add file", "Choose file",
            "Photo", "Image", "File",
        )
        for (label in labels) {
            val node = engine.findNodeByText(label)
            if (node != null) {
                val result = engine.clickNode(node)
                @Suppress("DEPRECATION")
                try { node.recycle() } catch (_: Exception) {}
                if (result) return true
            }
        }
        return false
    }

    // == Multi-turn conversation management ===================================

    /**
     * Record a complete turn (observation -> decision -> result) in conversation history.
     */
    private fun recordConversationTurn(
        userMessage: String,
        decision: AgentDecision,
        result: String,
    ) {
        // User observation.
        conversationHistory.add(ConversationMessage.UserObservation(content = userMessage))

        // Assistant's tool call.
        conversationHistory.add(ConversationMessage.AssistantToolCall(
            toolCallId = decision.toolCallId,
            toolName = decision.toolName.ifBlank { toolNameFromAction(decision.action) },
            toolArguments = decision.toolArguments.let {
                if (it == "{}") toolArgsFromAction(decision.action) else it
            },
            reasoning = decision.reasoning,
        ))

        // Tool result.
        conversationHistory.add(ConversationMessage.ToolResult(
            toolCallId = decision.toolCallId,
            result = result,
        ))

        // Update estimated token count (rough: 1 token ~ 4 chars).
        val turnChars = userMessage.length + decision.reasoning.length + result.length + 200
        estimatedTokens += turnChars / 4
    }

    // == Prompt building ======================================================

    private fun buildSystemPrompt(): String {
        return buildString {
            appendLine("You are Resolve, an AI agent that automates customer support on behalf of a real human customer.")
            appendLine("You control a real Android phone through an accessibility service. The UI elements shown are real interactive components.")
            appendLine("Each element has a [N] number. Use click_element(elementId=N) to click by number. This is MUCH more reliable than matching by text.")
            appendLine()

            // -- Core identity --
            appendLine("## Identity")
            appendLine("- You ARE the customer. Speak in first person. Never reveal you are AI.")
            appendLine("- You are polite but firm. You advocate for the customer's interests.")
            appendLine("- You are efficient. Every action should move toward the goal.")
            appendLine()

            // -- Customer's case --
            appendLine("## Customer's Issue")
            appendLine(caseContext.issue)
            appendLine()
            appendLine("## Desired Outcome")
            appendLine(caseContext.desiredOutcome)
            if (!caseContext.orderId.isNullOrBlank()) {
                appendLine()
                appendLine("## Order ID: ${caseContext.orderId}")
            }
            if (caseContext.hasAttachments) {
                appendLine()
                appendLine("## Evidence: Customer has photo/file evidence to upload when needed.")
            }
            appendLine()

            // -- Think-then-act protocol --
            appendLine("## Decision Protocol (MANDATORY)")
            appendLine("Before EVERY action, you MUST think through these steps:")
            appendLine("1. OBSERVE: What screen am I on? What elements are available (by [N] number)?")
            appendLine("2. THINK: What phase am I in? What sub-goal am I working on? What has/hasn't worked?")
            appendLine("3. PLAN: What are the next 2-3 steps to reach my current sub-goal?")
            appendLine("4. ACT: Choose exactly ONE tool call that advances the plan. Use elementId=[N] for clicks.")
            appendLine()

            // -- Element reference instructions --
            appendLine("## How to Reference Elements")
            appendLine("The screen state shows numbered elements like: [7] btn: \"Help\" (right)")
            appendLine("- To click element 7, use: click_element(elementId=7, expectedOutcome=\"Opens help page\")")
            appendLine("- ALWAYS use elementId when a number is shown. It is exact and reliable.")
            appendLine("- Only use the label fallback when element numbers are not available.")
            appendLine()

            // -- Navigation strategy --
            appendLine("## Navigation Strategy")
            appendLine()
            appendLine("### Phase 1: Find Customer Support (your FIRST priority)")
            appendLine("The FASTEST path to support in almost every app:")
            appendLine("1. Look for Profile/Account icon in the TOP BAR (usually top-right: person icon, avatar, initials, or \"Account\")")
            appendLine("2. In Profile/Account, find \"Orders\" or \"Order History\"")
            appendLine("3. Find the specific order (use Order ID if known)")
            appendLine("4. On the order detail page, find \"Help\", \"Support\", \"Report an issue\", or \"Get Help\"")
            appendLine("5. This opens the support flow for that specific order")
            appendLine()
            appendLine("### Alternative paths (if Profile approach fails):")
            appendLine("- Bottom navigation bar: look for \"Account\", \"Profile\", \"More\", or \"Menu\" tab")
            appendLine("- Hamburger menu (3 lines or 3 dots, usually top-left or top-right)")
            appendLine("- Direct \"Help\" or \"Support\" in bottom nav or settings")
            appendLine()
            appendLine("### App-specific patterns:")
            appendLine("- Food delivery (Dominos, Swiggy, Zomato, UberEats): Profile/Account (top-right) -> Orders -> [order] -> Help")
            appendLine("- E-commerce (Amazon, Flipkart): Account -> Your Orders -> [order] -> Problem/Help")
            appendLine("- Ride-hailing (Uber, Ola): Account -> Trips -> [trip] -> Help")
            appendLine("- Telecom/Banking: Menu -> Support/Help -> Live Chat")
            appendLine()

            // -- Phase 2: In the support chat --
            appendLine("### Phase 2: Communicating in Support Chat")
            appendLine("Once you reach the chat/support interface:")
            appendLine("1. If there are pre-set issue categories (buttons/chips), click the most relevant one")
            appendLine("2. When there's a text input, clearly describe the issue in 1-2 sentences")
            appendLine("3. Include the order ID if you have it")
            appendLine("4. State what you want (refund, replacement, etc.) clearly")
            appendLine("5. After sending a message, ALWAYS use wait_for_response")
            appendLine("6. If the bot offers options, pick the one closest to the desired outcome")
            appendLine("7. If asked for info you don't have, use request_human_review")
            appendLine()

            // -- Hard rules --
            appendLine("## Hard Rules")
            appendLine("1. NEVER scroll through product listings, menus, or promotional content. These are NOT paths to support.")
            appendLine("2. NEVER type into a search bar. Only type into chat/message input fields.")
            appendLine("3. If you see a popup (notifications, promotions, ads, location), DISMISS it immediately (press_back or click X/Close/Not now).")
            appendLine("4. If you see a home screen with products/food items, go to Profile/Account FIRST.")
            appendLine("5. NEVER share SSN, credit card numbers, or passwords. Use request_human_review if asked.")
            appendLine("6. After sending a chat message, ALWAYS wait_for_response before the next action.")
            appendLine("7. Only mark_resolved when you have EXPLICIT confirmation from support (refund issued, ticket created, etc.).")
            appendLine("8. If stuck after 3 attempts, try press_back and navigate via a different path.")
            appendLine("9. Maximum ${SafetyPolicy.MAX_ITERATIONS} actions available. Be efficient.")
            appendLine("10. Pay attention to verification feedback in tool results. If you see WARNING, your action may not have worked.")
        }
    }

    private fun buildUserMessage(
        formattedScreen: String,
        changeDescription: String,
        oscillationWarning: String,
    ): String {
        return buildString {
            // Target app context.
            appendLine("Target app: ${caseContext.targetPlatform}")
            appendLine("Phase: ${currentPhase.displayName}")
            appendLine("Turn: $iterationCount / ${SafetyPolicy.MAX_ITERATIONS}")
            appendLine()

            // Progress tracker (Manus's dynamic todo.md pattern).
            val progress = formatProgressTracker()
            if (progress.isNotBlank()) {
                append(progress)
                appendLine()
            }

            // Screen change feedback (enhanced differential state).
            when {
                changeDescription == "FIRST_SCREEN" -> appendLine("(First screen observed)")
                changeDescription.startsWith("NO_CHANGE") -> appendLine("WARNING: $changeDescription")
                else -> appendLine("Change: $changeDescription")
            }

            // Oscillation warning.
            if (oscillationWarning.isNotBlank()) {
                appendLine()
                appendLine(oscillationWarning)
            }

            // Scroll budget warning.
            if (totalScrollCount >= MAX_SCROLL_ACTIONS) {
                appendLine()
                appendLine("WARNING: You have used $totalScrollCount scroll actions. Excessive scrolling suggests you are on the wrong screen. Navigate to Profile/Account instead.")
            }

            appendLine()
            appendLine("## Screen State")
            append(formattedScreen)
            appendLine()

            // Concise phase-specific guidance.
            when (currentPhase) {
                NavigationPhase.NAVIGATING_TO_SUPPORT -> {
                    appendLine("GOAL: Find the path to customer support. Look for Profile/Account icon, or bottom navigation tabs.")
                }
                NavigationPhase.ON_ORDER_PAGE -> {
                    appendLine("GOAL: You are near orders. Find the specific order and its Help/Support option.")
                }
                NavigationPhase.ON_SUPPORT_PAGE -> {
                    appendLine("GOAL: You are on a support/help page. Find the chat option or select the relevant issue category.")
                }
                NavigationPhase.IN_CHAT -> {
                    appendLine("GOAL: You are in the support chat. Describe the issue and negotiate the resolution.")
                }
            }

            appendLine()
            appendLine("Choose exactly one tool. Use elementId=[N] for clicks. Think step by step about the shortest path to your goal.")
        }
    }

    // == Helpers ===============================================================

    /**
     * Derive the tool function name from an AgentAction (fallback when LLM response lacks it).
     */
    private fun toolNameFromAction(action: AgentAction): String = when (action) {
        is AgentAction.TypeMessage -> "type_message"
        is AgentAction.ClickElement -> "click_element"
        is AgentAction.ScrollDown -> "scroll_down"
        is AgentAction.ScrollUp -> "scroll_up"
        is AgentAction.Wait -> "wait_for_response"
        is AgentAction.UploadFile -> "upload_file"
        is AgentAction.PressBack -> "press_back"
        is AgentAction.RequestHumanReview -> "request_human_review"
        is AgentAction.MarkResolved -> "mark_resolved"
    }

    /**
     * Derive the tool arguments JSON from an AgentAction (fallback).
     */
    private fun toolArgsFromAction(action: AgentAction): String = when (action) {
        is AgentAction.TypeMessage -> {
            val escapedText = action.text.replace("\"", "\\\"")
            if (action.elementId != null) {
                """{"text":"$escapedText","elementId":${action.elementId}}"""
            } else {
                """{"text":"$escapedText"}"""
            }
        }
        is AgentAction.ClickElement -> {
            val parts = mutableListOf<String>()
            if (action.elementId != null) parts.add("\"elementId\":${action.elementId}")
            if (!action.label.isNullOrBlank()) parts.add("\"label\":\"${action.label.replace("\"", "\\\"")}\"")
            parts.add("\"expectedOutcome\":\"${action.expectedOutcome.replace("\"", "\\\"")}\"")
            "{${parts.joinToString(",")}}"
        }
        is AgentAction.ScrollDown -> """{"reason":"${action.reason.replace("\"", "\\\"")}"}"""
        is AgentAction.ScrollUp -> """{"reason":"${action.reason.replace("\"", "\\\"")}"}"""
        is AgentAction.Wait -> """{"reason":"${action.reason.replace("\"", "\\\"")}"}"""
        is AgentAction.UploadFile -> """{"fileDescription":"${action.fileDescription.replace("\"", "\\\"")}"}"""
        is AgentAction.PressBack -> """{"reason":"${action.reason.replace("\"", "\\\"")}"}"""
        is AgentAction.RequestHumanReview -> """{"reason":"${action.reason.replace("\"", "\\\"")}"}"""
        is AgentAction.MarkResolved -> """{"summary":"${action.summary.replace("\"", "\\\"")}"}"""
    }

    private fun logAction(action: AgentAction, description: String) {
        when (action) {
            is AgentAction.TypeMessage -> {
                val preview = action.text.take(200)
                AgentLogStore.log(description, LogCategory.AGENT_MESSAGE, preview)
            }
            is AgentAction.ClickElement -> {
                val target = if (action.elementId != null) "[${action.elementId}]" else action.label ?: "unknown"
                AgentLogStore.log(description, LogCategory.AGENT_ACTION, "Tapping $target")
            }
            is AgentAction.ScrollDown -> {
                AgentLogStore.log(description, LogCategory.AGENT_ACTION, "Scrolling down")
            }
            is AgentAction.ScrollUp -> {
                AgentLogStore.log(description, LogCategory.AGENT_ACTION, "Scrolling up")
            }
            is AgentAction.Wait -> {
                AgentLogStore.log(description, LogCategory.STATUS_UPDATE, "Waiting for response...")
            }
            is AgentAction.UploadFile -> {
                AgentLogStore.log(description, LogCategory.AGENT_ACTION, "Uploading file")
            }
            is AgentAction.PressBack -> {
                AgentLogStore.log(description, LogCategory.AGENT_ACTION, "Going back")
            }
            is AgentAction.RequestHumanReview -> {
                AgentLogStore.log(description, LogCategory.STATUS_UPDATE, "Needs your input: ${action.reason}")
            }
            is AgentAction.MarkResolved -> {
                AgentLogStore.log(description, LogCategory.STATUS_UPDATE, "Issue resolved!")
            }
        }
    }

    private fun describeAction(action: AgentAction): String {
        return when (action) {
            is AgentAction.TypeMessage -> "Type message: \"${action.text.take(60)}${if (action.text.length > 60) "..." else ""}\""
            is AgentAction.ClickElement -> {
                val target = when {
                    action.elementId != null && !action.label.isNullOrBlank() -> "[${action.elementId}] \"${action.label}\""
                    action.elementId != null -> "[${action.elementId}]"
                    !action.label.isNullOrBlank() -> "\"${action.label}\""
                    else -> "unknown"
                }
                "Click: $target"
            }
            is AgentAction.ScrollDown -> "Scroll down: ${action.reason}"
            is AgentAction.ScrollUp -> "Scroll up: ${action.reason}"
            is AgentAction.Wait -> "Wait: ${action.reason}"
            is AgentAction.UploadFile -> "Upload file: ${action.fileDescription}"
            is AgentAction.PressBack -> "Press back: ${action.reason}"
            is AgentAction.RequestHumanReview -> "Request human review: ${action.reason}"
            is AgentAction.MarkResolved -> "Mark resolved: ${action.summary.take(80)}"
        }
    }

    private suspend fun emit(event: AgentEvent) {
        try {
            onEvent(event)
        } catch (e: Exception) {
            Log.w(tag, "Failed to emit event: $event", e)
        }
    }
}

// == Sub-goal tracking types ==================================================

enum class SubGoalStatus {
    PENDING,
    IN_PROGRESS,
    DONE,
}

data class SubGoal(
    val description: String,
    val status: SubGoalStatus,
)

// == Verification result ======================================================

sealed class VerificationResult {
    data class Success(val observation: String) : VerificationResult()
    data class Warning(val observation: String) : VerificationResult()
}

// == Navigation phases ========================================================

/**
 * Represents the current phase of the agent's navigation toward resolving the support case.
 * Phase detection is heuristic -- based on keywords and UI patterns on the current screen.
 */
enum class NavigationPhase(val displayName: String) {
    /** The agent is still looking for the path to customer support. */
    NAVIGATING_TO_SUPPORT("Navigating to support"),

    /** The agent is on an order detail or order list page. */
    ON_ORDER_PAGE("Viewing orders"),

    /** The agent is on a help/support/FAQ page. */
    ON_SUPPORT_PAGE("On support page"),

    /** The agent is in an active support chat conversation. */
    IN_CHAT("In support chat"),
}

// == Supporting types =========================================================

data class CaseContext(
    val caseId: String,
    val customerName: String,
    val issue: String,
    val desiredOutcome: String,
    val orderId: String?,
    val hasAttachments: Boolean,
    val targetPlatform: String,
)

sealed class AgentResult {
    data class Resolved(val summary: String, val iterationsCompleted: Int) : AgentResult()
    data class Failed(val reason: String) : AgentResult()
    data class NeedsHumanReview(val reason: String, val iterationsCompleted: Int) : AgentResult()
    data object Cancelled : AgentResult()
}

sealed class ActionResult {
    data object Success : ActionResult()
    data class Failed(val reason: String) : ActionResult()
    data class Resolved(val summary: String) : ActionResult()
    data class HumanReviewNeeded(val reason: String, val inputPrompt: String?) : ActionResult()
}

enum class AgentLoopState {
    IDLE,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

sealed class AgentEvent {
    data class Started(val caseId: String) : AgentEvent()
    data class ScreenCaptured(val packageName: String, val elementCount: Int) : AgentEvent()
    data object ThinkingStarted : AgentEvent()
    data class DecisionMade(val action: String, val reasoning: String) : AgentEvent()
    data class ApprovalNeeded(val reason: String) : AgentEvent()
    data class ActionBlocked(val reason: String) : AgentEvent()
    data class ActionExecuted(val description: String, val success: Boolean) : AgentEvent()
    data class HumanReviewRequested(val reason: String, val inputPrompt: String?) : AgentEvent()
    data class Resolved(val summary: String) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
    data class Failed(val reason: String) : AgentEvent()
    data object Cancelled : AgentEvent()
}
