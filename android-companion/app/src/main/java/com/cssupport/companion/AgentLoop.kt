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
 * Flow:
 * 1. Capture screen state via [AccessibilityEngine]
 * 2. Format state + case context for the LLM
 * 3. Call LLM with tool definitions
 * 4. Validate action against [SafetyPolicy]
 * 5. Execute action via [AccessibilityEngine]
 * 6. Wait for screen to update
 * 7. Repeat until resolved, failed, paused, or max iterations
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

    private val previousActions = mutableListOf<String>()
    /** Sliding window of recent turns: screen summary + reasoning + action taken. */
    private val turnHistory = mutableListOf<TurnRecord>()
    private var iterationCount = 0
    private var consecutiveDuplicates = 0
    private var lastActionSignature = ""
    private var llmRetryCount = 0

    private companion object {
        const val OWN_PACKAGE = "com.cssupport.companion"
        const val MAX_FOREGROUND_WAIT_MS = 60_000L
        const val MAX_CONSECUTIVE_DUPLICATES = 3
        /** How many previous turns of reasoning to include in the prompt. */
        const val MAX_TURN_HISTORY = 5
    }

    @Volatile
    private var paused = false

    /**
     * Run the main agent loop. Suspends until the loop completes (resolved/failed/cancelled).
     */
    suspend fun run(): AgentResult {
        _state.value = AgentLoopState.RUNNING
        iterationCount = 0
        previousActions.clear()

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

                // Actively try to launch the target app.
                if (launchTargetApp != null) {
                    launchTargetApp.invoke()
                }
                AgentLogStore.log("Opening target app", LogCategory.STATUS_UPDATE, "Opening app...")

                val waitStart = System.currentTimeMillis()
                var retryLaunchCount = 0
                while (engine.captureScreenState().packageName == OWN_PACKAGE) {
                    currentCoroutineContext().ensureActive()
                    val elapsed = System.currentTimeMillis() - waitStart

                    // Retry launching every 8 seconds (up to 4 retries).
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

            // Step 2: Think -- ask LLM for next action.
            val formattedScreen = screenState.formatForLLM()
            val userMessage = buildUserMessage(formattedScreen)

            emit(AgentEvent.ThinkingStarted)
            AgentLogStore.log("[$iterationCount] Thinking...", LogCategory.STATUS_UPDATE, "Thinking...")

            val decision: AgentDecision
            try {
                decision = llmClient.chatCompletion(
                    systemPrompt = buildSystemPrompt(),
                    userMessage = userMessage,
                )
                llmRetryCount = 0
            } catch (e: Exception) {
                llmRetryCount++
                val backoffMs = (3000L * (1 shl llmRetryCount.coerceAtMost(4))).coerceAtMost(30_000L)
                val waitSec = backoffMs / 1000
                Log.e(tag, "LLM call failed: ${e.javaClass.simpleName}: ${e.message}", e)
                emit(AgentEvent.Error(message = "LLM error: ${e.javaClass.simpleName}: ${e.message}"))

                // Show the actual error reason to the user, not just "Retrying".
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

                // Give up after too many consecutive failures.
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

            // Record this turn for context in future iterations.
            val screenSummary = "${screenState.packageName} — ${screenState.elements.size} elements"
            turnHistory.add(TurnRecord(
                iteration = iterationCount,
                screenSummary = screenSummary,
                reasoning = decision.reasoning,
                action = describeAction(decision.action),
            ))
            // Keep only the last N turns to avoid unbounded growth.
            while (turnHistory.size > MAX_TURN_HISTORY) {
                turnHistory.removeAt(0)
            }

            // Step 3: Validate against safety policy.
            val policyResult = safetyPolicy.validate(decision.action, iterationCount)
            when (policyResult) {
                is PolicyResult.Allowed -> { /* proceed */ }
                is PolicyResult.NeedsApproval -> {
                    emit(AgentEvent.ApprovalNeeded(reason = policyResult.reason))
                    AgentLogStore.log("[$iterationCount] NEEDS APPROVAL: ${policyResult.reason}", LogCategory.APPROVAL_NEEDED, "Needs your approval")
                    // For now, pause and request human review.
                    return AgentResult.NeedsHumanReview(
                        reason = policyResult.reason,
                        iterationsCompleted = iterationCount,
                    )
                }
                is PolicyResult.Blocked -> {
                    emit(AgentEvent.ActionBlocked(reason = policyResult.reason))
                    AgentLogStore.log("[$iterationCount] BLOCKED: ${policyResult.reason}", LogCategory.ERROR, "Action blocked: ${policyResult.reason}")
                    // If blocked due to max iterations, fail gracefully.
                    if (iterationCount >= SafetyPolicy.MAX_ITERATIONS) {
                        return AgentResult.Failed(
                            reason = "Maximum iterations reached without resolution",
                        )
                    }
                    // Skip this action and continue the loop.
                    delay(SafetyPolicy.MIN_ACTION_DELAY_MS)
                    continue
                }
            }

            // Step 3b: Detect repeated identical actions.
            val actionSignature = describeAction(decision.action)
            if (actionSignature == lastActionSignature) {
                consecutiveDuplicates++
                if (consecutiveDuplicates >= MAX_CONSECUTIVE_DUPLICATES) {
                    Log.w(tag, "Action repeated $consecutiveDuplicates times: $actionSignature")
                    AgentLogStore.log("[$iterationCount] Skipping repeated action (tried $consecutiveDuplicates times)", LogCategory.DEBUG)
                    consecutiveDuplicates = 0
                    lastActionSignature = ""
                    // Inject a hint for the LLM on the next iteration.
                    previousActions.add("REPEATED ACTION SKIPPED: $actionSignature — try a different approach")
                    delay(SafetyPolicy.MIN_ACTION_DELAY_MS)
                    continue
                }
            } else {
                consecutiveDuplicates = 0
                lastActionSignature = actionSignature
            }

            // Step 4: Execute the action.
            val actionDescription = actionSignature
            logAction(decision.action, actionDescription)

            val actionResult = executeAction(decision.action)

            when (actionResult) {
                is ActionResult.Success -> {
                    previousActions.add(actionDescription)
                    emit(AgentEvent.ActionExecuted(description = actionDescription, success = true))
                }
                is ActionResult.Failed -> {
                    previousActions.add("FAILED: $actionDescription (${actionResult.reason})")
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

            // Step 5: Wait for screen to update before next iteration.
            delay(SafetyPolicy.MIN_ACTION_DELAY_MS)
            engine.waitForContentChange(timeoutMs = 3000)
        }

        // Exhausted iterations.
        return AgentResult.Failed(
            reason = "Reached maximum of $iterationCount iterations without resolving the issue",
        )
    }

    // ── Action execution ────────────────────────────────────────────────────

    private suspend fun executeAction(action: AgentAction): ActionResult {
        return when (action) {
            is AgentAction.TypeMessage -> {
                // Find an input field and type the message.
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

                // Look for a send button and click it.
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
                else ActionResult.Failed("Could not find or click button: '${action.buttonLabel}'")
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
                // Wait for content changes (agent typing, etc.).
                engine.waitForContentChange(timeoutMs = 5000)
                ActionResult.Success
            }

            is AgentAction.UploadFile -> {
                // Look for an attachment/upload button and click it.
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
     * After typing a message, try to find and click a "Send" button.
     * Common patterns: "Send", send icon (content description), arrow button.
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

        // Try common send button IDs.
        val sendIds = listOf(
            "send_button", "btn_send", "send", "submit",
            "com.android.mms:id/send_button_sms",
        )
        for (id in sendIds) {
            // Try with and without package prefix.
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
            appendLine("You are Resolve, an AI agent acting on behalf of a customer to resolve their support issue.")
            appendLine("You are operating inside a real Android app on the customer's device, automating the support chat interface.")
            appendLine()
            appendLine("## Your Role")
            appendLine("You are the customer's advocate. Speak AS the customer (first person), not as a third party.")
            appendLine()
            appendLine("## Customer's Issue")
            appendLine(caseContext.issue)
            appendLine()
            appendLine("## Desired Outcome")
            appendLine(caseContext.desiredOutcome)
            appendLine()
            if (!caseContext.orderId.isNullOrBlank()) {
                appendLine("## Order ID")
                appendLine(caseContext.orderId)
                appendLine()
            }
            if (caseContext.hasAttachments) {
                appendLine("## Evidence")
                appendLine("The customer has provided attachment(s) as evidence. Upload them when relevant.")
                appendLine()
            }
            appendLine("## Navigation Strategy — CRITICAL")
            appendLine("Your FIRST goal is to reach the customer support chat. Do NOT browse menus, products, or content.")
            appendLine()
            appendLine("### Where to find support in most apps:")
            appendLine("1. **Profile/Account icon** — usually in the TOP-RIGHT corner (person icon, avatar, or initials)")
            appendLine("2. **Inside Profile → Orders / Order History** — find the specific order, then look for 'Help' or 'Support' on that order")
            appendLine("3. **Hamburger menu (≡ or ⋮)** — usually top-left or top-right, contains 'Help', 'Support', 'Contact Us'")
            appendLine("4. **Bottom navigation bar** — some apps have 'Account', 'Profile', or 'More' tab at the bottom")
            appendLine("5. **Help / Support / Contact Us** — direct links, often in profile/account section or app settings")
            appendLine()
            appendLine("### Common patterns for specific app categories:")
            appendLine("- **Food delivery** (Dominos, Swiggy, Zomato, UberEats, DoorDash): Profile icon (top-right) → Orders → Select order → Help/Support")
            appendLine("- **E-commerce** (Amazon, Flipkart, Myntra): Account/Profile → Orders → Select order → Help/Problem with order")
            appendLine("- **Ride-hailing** (Uber, Ola, Lyft): Account → Trips/Rides → Select trip → Help")
            appendLine("- **Telecom** (Airtel, Jio, Vi): Menu → Support/Help/Chat → Live chat")
            appendLine("- **Banking** (PayTM, PhonePe, GPay): Profile → Help/Support → Chat")
            appendLine()
            appendLine("### Navigation rules:")
            appendLine("- ALWAYS start from Profile/Account — this is the fastest path to support in 90% of apps")
            appendLine("- NEVER scroll through product listings, menus, or promotional content")
            appendLine("- NEVER type into a search bar — only type into chat/message input fields")
            appendLine("- If you see a home screen with products/offers, look for Profile/Account FIRST, do NOT scroll the feed")
            appendLine("- If you see a popup (notifications, promotions, location), dismiss it immediately")
            appendLine("- If the specific order is known, navigate to that order FIRST, then find help on that order page")
            appendLine()
            appendLine("## Rules")
            appendLine("1. Be polite but assertive. You represent the customer's interests.")
            appendLine("2. NEVER reveal that you are an AI or automation tool. Speak as the customer.")
            appendLine("3. NEVER share sensitive personal information (SSN, credit card, passwords). Use request_human_review if asked.")
            appendLine("4. If the support agent asks for information you don't have, use request_human_review.")
            appendLine("5. Stay focused on resolving the specific issue.")
            appendLine("6. If presented with options, choose the one closest to the desired outcome.")
            appendLine("7. If the support bot offers a resolution matching the desired outcome, accept it.")
            appendLine("8. If the conversation seems stuck, try a different approach.")
            appendLine("9. Upload evidence proactively when it strengthens the case.")
            appendLine("10. After sending a message, always wait_for_response before taking the next action.")
            appendLine("11. Mark the case resolved only when you have confirmation from the support side.")
            appendLine("12. Use press_back to dismiss dialogs or navigate back if needed.")
            appendLine("13. If you see a dialog or popup (e.g. 'Allow notifications', 'Sign in'), dismiss it or handle it before proceeding.")
            appendLine()
            appendLine("## Important")
            appendLine("You are looking at a REAL Android screen. The elements listed are actual UI components.")
            appendLine("Choose exactly ONE action per turn. Analyze the screen carefully before acting.")
            appendLine("Think about WHERE you are in the app and what is the SHORTEST path to customer support.")
        }
    }

    private fun buildUserMessage(formattedScreen: String): String {
        return buildString {
            appendLine("## Target App: ${caseContext.targetPlatform}")
            appendLine()

            // Include recent turn history so the agent remembers its reasoning.
            if (turnHistory.isNotEmpty()) {
                appendLine("## Recent History (last ${turnHistory.size} turns)")
                for (turn in turnHistory.takeLast(MAX_TURN_HISTORY)) {
                    appendLine("Turn ${turn.iteration}: [${turn.screenSummary}] → Reasoning: ${turn.reasoning.take(120)} → Action: ${turn.action}")
                }
                appendLine()
            }

            appendLine("## Current Screen")
            append(formattedScreen)
            appendLine()
            appendLine("Iteration: $iterationCount / ${SafetyPolicy.MAX_ITERATIONS}")
            appendLine()
            appendLine("Analyze the screen carefully. Where are you in the app? What is the SHORTEST path to customer support? Choose exactly one tool.")
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

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
            is AgentAction.ClickButton -> "Click button: \"${action.buttonLabel}\""
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

/** A single turn of the agent loop, kept for context in future LLM calls. */
data class TurnRecord(
    val iteration: Int,
    val screenSummary: String,
    val reasoning: String,
    val action: String,
)

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
