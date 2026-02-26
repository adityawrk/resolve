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
 * ## Architecture (informed by DroidRun, AppAgent, Mobile-Agent research)
 *
 * Key design decisions:
 * 1. **Multi-turn conversation**: Full message history sent to the LLM each turn,
 *    giving it memory of prior screens, reasoning, and action outcomes.
 * 2. **Navigation phases**: The agent tracks what phase it is in (NAVIGATING,
 *    CHATTING, WAITING) and adjusts its behavior accordingly.
 * 3. **Differential state tracking**: Detects what changed between screens to
 *    give the LLM focused context on the effect of its last action.
 * 4. **Smart stuck detection**: Catches not just consecutive duplicates but also
 *    screen oscillation (A->B->A->B) and stagnation.
 * 5. **Spatially-aware screen representation**: Elements are grouped by screen
 *    zone (top bar, content, bottom bar) with position hints.
 * 6. **Context compression**: Older turns are summarized to stay within token limits
 *    while preserving critical information.
 *
 * Flow:
 * 1. Capture screen state via [AccessibilityEngine]
 * 2. Detect changes from previous screen (differential state)
 * 3. Determine navigation phase and build context-appropriate prompt
 * 4. Send multi-turn conversation to LLM with tool definitions
 * 5. Validate action against [SafetyPolicy]
 * 6. Execute action via [AccessibilityEngine]
 * 7. Record action and result in conversation history
 * 8. Wait for screen to update
 * 9. Repeat until resolved, failed, paused, or max iterations
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

    // ── Conversation history (multi-turn) ────────────────────────────────────

    /** Full conversation history sent to the LLM for multi-turn context. */
    private val conversationHistory = mutableListOf<ConversationMessage>()

    /**
     * Estimated token count of the conversation history.
     * Used to trigger compression when approaching context limits.
     * Rough heuristic: 1 token ~ 4 chars.
     */
    private var estimatedTokens = 0

    // ── State tracking ───────────────────────────────────────────────────────

    private var iterationCount = 0
    private var consecutiveDuplicates = 0
    private var lastActionSignature = ""
    private var llmRetryCount = 0

    /** The previous screen fingerprint for differential state detection. */
    private var previousScreenFingerprint = ""
    private var previousScreenFormatted = ""

    /** Recent screen fingerprints for oscillation detection (A->B->A->B pattern). */
    private val recentFingerprints = mutableListOf<String>()

    /** Current navigation phase, detected from screen content. */
    private var currentPhase = NavigationPhase.NAVIGATING_TO_SUPPORT

    /** Count of consecutive screens with no meaningful change. */
    private var stagnationCount = 0

    /** Count of total scroll actions, to limit aimless scrolling. */
    private var totalScrollCount = 0

    private companion object {
        const val OWN_PACKAGE = "com.cssupport.companion"
        const val MAX_FOREGROUND_WAIT_MS = 60_000L
        const val MAX_CONSECUTIVE_DUPLICATES = 3

        /**
         * Maximum number of conversation turns to keep before compressing.
         * Each "turn" is ~3 messages (user observation + assistant tool call + tool result).
         * At ~500 tokens per turn, 8 turns is ~4000 tokens of history.
         */
        const val MAX_CONVERSATION_TURNS = 8

        /** Maximum estimated tokens before forcing compression. */
        const val MAX_ESTIMATED_TOKENS = 6000

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
        previousScreenFingerprint = ""
        previousScreenFormatted = ""
        recentFingerprints.clear()
        currentPhase = NavigationPhase.NAVIGATING_TO_SUPPORT
        stagnationCount = 0
        totalScrollCount = 0

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
        while (iterationCount < SafetyPolicy.MAX_ITERATIONS) {
            currentCoroutineContext().ensureActive()

            // Handle pause state.
            while (paused) {
                currentCoroutineContext().ensureActive()
                delay(500)
            }

            iterationCount++
            Log.d(tag, "--- Iteration $iterationCount ---")

            // Step 1: Observe -- capture screen state.
            val screenState = engine.captureScreenState()
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
            val formattedScreen = screenState.formatForLLM()
            val currentFingerprint = screenState.fingerprint()
            val changeDescription = detectChanges(currentFingerprint, formattedScreen)
            updateNavigationPhase(screenState)
            trackFingerprint(currentFingerprint)

            previousScreenFingerprint = currentFingerprint
            previousScreenFormatted = formattedScreen

            // Step 2b: Check for stagnation (screen not changing).
            if (changeDescription == "NO_CHANGE") {
                stagnationCount++
                if (stagnationCount >= 3) {
                    Log.w(tag, "Screen has not changed for $stagnationCount turns")
                    // Inject a hint to try something different.
                    addStagnationHint()
                }
            } else {
                stagnationCount = 0
            }

            // Step 2c: Check for oscillation (A->B->A->B pattern).
            val oscillationWarning = detectOscillation()

            // Step 3: Think -- build the user message and ask the LLM.
            val userMessage = buildUserMessage(formattedScreen, changeDescription, oscillationWarning)

            emit(AgentEvent.ThinkingStarted)
            AgentLogStore.log("[$iterationCount] Thinking...", LogCategory.STATUS_UPDATE, "Thinking...")

            // Compress conversation history if it's getting too long.
            maybeCompressHistory()

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

            val actionResult = executeAction(decision.action)

            // Step 6: Record the turn in conversation history.
            val resultDescription = when (actionResult) {
                is ActionResult.Success -> "Success. Screen may have changed."
                is ActionResult.Failed -> "FAILED: ${actionResult.reason}. Try a different approach."
                is ActionResult.Resolved -> "RESOLVED: ${actionResult.summary}"
                is ActionResult.HumanReviewNeeded -> "Pausing for human input: ${actionResult.reason}"
            }
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

            // Step 7: Wait for screen to update before next iteration.
            delay(SafetyPolicy.MIN_ACTION_DELAY_MS)
            engine.waitForContentChange(timeoutMs = 3000)
        }

        // Exhausted iterations.
        return AgentResult.Failed(
            reason = "Reached maximum of $iterationCount iterations without resolving the issue",
        )
    }

    // ── Multi-turn conversation management ───────────────────────────────────

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

    /**
     * Compress conversation history when it exceeds token limits.
     *
     * Strategy: Keep the most recent MAX_CONVERSATION_TURNS turns intact.
     * Summarize older turns into a single compact "memory" message.
     * This mirrors the approach used by DroidRun and JetBrains' context management research.
     */
    private fun maybeCompressHistory() {
        // Each turn is 3 messages (observation, tool call, result).
        val turnCount = conversationHistory.size / 3
        if (turnCount <= MAX_CONVERSATION_TURNS && estimatedTokens <= MAX_ESTIMATED_TOKENS) return

        Log.d(tag, "Compressing conversation history: $turnCount turns, ~$estimatedTokens tokens")

        // Keep the last MAX_CONVERSATION_TURNS turns (most recent context).
        val keepCount = MAX_CONVERSATION_TURNS * 3
        if (conversationHistory.size <= keepCount) return

        val oldMessages = conversationHistory.take(conversationHistory.size - keepCount)
        val recentMessages = conversationHistory.takeLast(keepCount)

        // Build a compact summary of old turns.
        val summary = buildString {
            appendLine("[COMPRESSED HISTORY - Earlier actions in this session]")
            var turnIdx = 0
            var i = 0
            while (i < oldMessages.size) {
                turnIdx++
                val msg = oldMessages[i]
                if (msg is ConversationMessage.AssistantToolCall) {
                    val resultMsg = if (i + 1 < oldMessages.size) oldMessages[i + 1] else null
                    val resultText = if (resultMsg is ConversationMessage.ToolResult) resultMsg.result else "?"
                    val reasoningSnippet = msg.reasoning.take(60).let {
                        if (msg.reasoning.length > 60) "$it..." else it
                    }
                    append("T$turnIdx: ${msg.toolName}(${msg.toolArguments.take(50)}) -> $resultText")
                    if (reasoningSnippet.isNotBlank()) append(" [$reasoningSnippet]")
                    appendLine()
                }
                i++
            }
        }

        // Replace history with summary + recent turns.
        conversationHistory.clear()
        conversationHistory.add(ConversationMessage.UserObservation(content = summary))
        conversationHistory.addAll(recentMessages)

        // Re-estimate tokens.
        estimatedTokens = conversationHistory.sumOf { msg ->
            when (msg) {
                is ConversationMessage.UserObservation -> msg.content.length
                is ConversationMessage.AssistantToolCall -> msg.reasoning.length + msg.toolArguments.length + 100
                is ConversationMessage.ToolResult -> msg.result.length + 50
            }
        } / 4

        Log.d(tag, "Compressed to ${conversationHistory.size} messages, ~$estimatedTokens tokens")
    }

    // ── Differential state detection ─────────────────────────────────────────

    /**
     * Compare current screen with previous screen and describe what changed.
     * Returns "NO_CHANGE" if screens are identical, or a human-readable description of changes.
     */
    private fun detectChanges(currentFingerprint: String, currentFormatted: String): String {
        if (previousScreenFingerprint.isEmpty()) return "FIRST_SCREEN"
        if (currentFingerprint == previousScreenFingerprint) return "NO_CHANGE"

        // Screen changed. Try to describe what's different at a high level.
        // We don't diff the full text (too expensive), but we can compare key signals.
        return "SCREEN_CHANGED"
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

    // ── Navigation phase detection ───────────────────────────────────────────

    /**
     * Detect what navigation phase the agent is currently in.
     * This allows the prompt to give phase-appropriate guidance.
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

    // ── Action execution ────────────────────────────────────────────────────

    private suspend fun executeAction(action: AgentAction): ActionResult {
        return when (action) {
            is AgentAction.TypeMessage -> {
                val fields = engine.findInputFields()
                if (fields.isEmpty()) {
                    return ActionResult.Failed("No input field found on screen")
                }
                val success = try {
                    engine.setText(fields.first(), action.text)
                } finally {
                    @Suppress("DEPRECATION")
                    fields.forEach { try { it.recycle() } catch (_: Exception) {} }
                }

                if (!success) {
                    return ActionResult.Failed("Could not set text in input field")
                }

                delay(300)
                val sent = trySendMessage()
                if (sent) {
                    ActionResult.Success
                } else {
                    // Text was entered but send button not found -- still partial success.
                    ActionResult.Success
                }
            }

            is AgentAction.ClickButton -> {
                val success = engine.clickByText(action.buttonLabel)
                if (success) ActionResult.Success
                else {
                    // Try content description match as fallback (icon buttons often only have this).
                    val fallback = tryClickByContentDescription(action.buttonLabel)
                    if (fallback) ActionResult.Success
                    else ActionResult.Failed("Could not find or click: '${action.buttonLabel}'")
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
     * This is a fallback when clickByText fails, which is common for icon-only buttons
     * like profile avatars, hamburger menus, and back arrows.
     */
    private fun tryClickByContentDescription(description: String): Boolean {
        val root = try {
            val service = SupportAccessibilityService.instance ?: return false
            service.rootInActiveWindow ?: return false
        } catch (e: Exception) {
            return false
        }

        return try {
            // Search for nodes with matching content description.
            val found = root.findAccessibilityNodeInfosByText(description)
            if (found.isNullOrEmpty()) return false

            // Prefer clickable nodes.
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
     * Searches text, content descriptions, and common resource IDs.
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

    // ── Prompt building ─────────────────────────────────────────────────────

    private fun buildSystemPrompt(): String {
        return buildString {
            appendLine("You are Resolve, an AI agent that automates customer support on behalf of a real human customer.")
            appendLine("You control a real Android phone through an accessibility service. The UI elements shown are real interactive components.")
            appendLine()

            // ── Core identity ──
            appendLine("## Identity")
            appendLine("- You ARE the customer. Speak in first person. Never reveal you are AI.")
            appendLine("- You are polite but firm. You advocate for the customer's interests.")
            appendLine("- You are efficient. Every action should move toward the goal.")
            appendLine()

            // ── Customer's case ──
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

            // ── Think-then-act protocol ──
            appendLine("## Decision Protocol (MANDATORY)")
            appendLine("Before EVERY action, you MUST think through these steps:")
            appendLine("1. OBSERVE: What screen am I on? What app section is this? What elements are available?")
            appendLine("2. ORIENT: What phase am I in? (navigating to support / on order page / in chat)")
            appendLine("3. PLAN: What is the shortest path from here to my goal?")
            appendLine("4. ACT: Choose exactly ONE action that advances the plan.")
            appendLine()

            // ── Navigation strategy (the critical knowledge) ──
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

            // ── Phase 2: In the support chat ──
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

            // ── Hard rules ──
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

            // Screen change feedback (differential state).
            when (changeDescription) {
                "FIRST_SCREEN" -> appendLine("(First screen observed)")
                "NO_CHANGE" -> appendLine("WARNING: Screen has NOT changed since your last action. Your action may have failed or had no effect.")
                "SCREEN_CHANGED" -> appendLine("(Screen updated since last action)")
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
            appendLine("Choose exactly one tool. Think step by step about what you see and the shortest path to your goal.")
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Derive the tool function name from an AgentAction (fallback when LLM response lacks it).
     */
    private fun toolNameFromAction(action: AgentAction): String = when (action) {
        is AgentAction.TypeMessage -> "type_message"
        is AgentAction.ClickButton -> "click_button"
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
        is AgentAction.TypeMessage -> """{"text":"${action.text.replace("\"", "\\\"")}"}"""
        is AgentAction.ClickButton -> """{"buttonLabel":"${action.buttonLabel.replace("\"", "\\\"")}"}"""
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
            is AgentAction.ClickButton -> {
                AgentLogStore.log(description, LogCategory.AGENT_ACTION, "Tapping \"${action.buttonLabel}\"")
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
            is AgentAction.ClickButton -> "Click: \"${action.buttonLabel}\""
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

// ── Navigation phases ────────────────────────────────────────────────────────

/**
 * Represents the current phase of the agent's navigation toward resolving the support case.
 * Phase detection is heuristic — based on keywords and UI patterns on the current screen.
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

// ── Supporting types ────────────────────────────────────────────────────────

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
