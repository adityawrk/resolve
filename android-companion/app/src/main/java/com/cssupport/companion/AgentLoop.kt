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
) {

    private val tag = "AgentLoop"

    private val _state = MutableStateFlow(AgentLoopState.IDLE)
    val state = _state.asStateFlow()

    private val previousActions = mutableListOf<String>()
    private var iterationCount = 0

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
        AgentLogStore.log("Agent loop started for case ${caseContext.caseId}")

        return try {
            val result = executeLoop()
            _state.value = AgentLoopState.COMPLETED
            result
        } catch (e: CancellationException) {
            _state.value = AgentLoopState.CANCELLED
            emit(AgentEvent.Cancelled)
            AgentLogStore.log("Agent loop cancelled")
            AgentResult.Cancelled
        } catch (e: Exception) {
            _state.value = AgentLoopState.FAILED
            val msg = "${e.javaClass.simpleName}: ${e.message ?: "Unknown error"}"
            emit(AgentEvent.Failed(reason = msg))
            AgentLogStore.log("Agent loop failed: $msg")
            Log.e(tag, "Agent loop failed: $msg", e)
            AgentResult.Failed(reason = msg)
        }
    }

    fun pause() {
        paused = true
        _state.value = AgentLoopState.PAUSED
        AgentLogStore.log("Agent loop paused")
    }

    fun resume() {
        paused = false
        _state.value = AgentLoopState.RUNNING
        AgentLogStore.log("Agent loop resumed")
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

            // Step 2: Think -- ask LLM for next action.
            val formattedScreen = screenState.formatForLLM()
            val userMessage = buildUserMessage(formattedScreen)

            emit(AgentEvent.ThinkingStarted)
            AgentLogStore.log("[$iterationCount] Thinking...")

            val decision: AgentDecision
            try {
                decision = llmClient.chatCompletion(
                    systemPrompt = buildSystemPrompt(),
                    userMessage = userMessage,
                )
            } catch (e: Exception) {
                Log.e(tag, "LLM call failed: ${e.javaClass.simpleName}: ${e.message}", e)
                emit(AgentEvent.Error(message = "LLM error: ${e.javaClass.simpleName}: ${e.message}"))
                AgentLogStore.log("[$iterationCount] LLM error: ${e.javaClass.simpleName}: ${e.message}")
                // Retry after delay.
                delay(3000)
                continue
            }

            Log.d(tag, "Decision: action=${decision.action}, reasoning=${decision.reasoning.take(100)}")
            emit(AgentEvent.DecisionMade(
                action = describeAction(decision.action),
                reasoning = decision.reasoning,
            ))

            // Step 3: Validate against safety policy.
            val policyResult = safetyPolicy.validate(decision.action, iterationCount)
            when (policyResult) {
                is PolicyResult.Allowed -> { /* proceed */ }
                is PolicyResult.NeedsApproval -> {
                    emit(AgentEvent.ApprovalNeeded(reason = policyResult.reason))
                    AgentLogStore.log("[$iterationCount] NEEDS APPROVAL: ${policyResult.reason}")
                    // For now, pause and request human review.
                    return AgentResult.NeedsHumanReview(
                        reason = policyResult.reason,
                        iterationsCompleted = iterationCount,
                    )
                }
                is PolicyResult.Blocked -> {
                    emit(AgentEvent.ActionBlocked(reason = policyResult.reason))
                    AgentLogStore.log("[$iterationCount] BLOCKED: ${policyResult.reason}")
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

            // Step 4: Execute the action.
            val actionDescription = describeAction(decision.action)
            AgentLogStore.log("[$iterationCount] $actionDescription")

            val actionResult = executeAction(decision.action)

            when (actionResult) {
                is ActionResult.Success -> {
                    previousActions.add(actionDescription)
                    emit(AgentEvent.ActionExecuted(description = actionDescription, success = true))
                }
                is ActionResult.Failed -> {
                    previousActions.add("FAILED: $actionDescription (${actionResult.reason})")
                    emit(AgentEvent.ActionExecuted(description = actionDescription, success = false))
                    AgentLogStore.log("[$iterationCount] Action failed: ${actionResult.reason}")
                }
                is ActionResult.Resolved -> {
                    emit(AgentEvent.Resolved(summary = actionResult.summary))
                    AgentLogStore.log("Case resolved: ${actionResult.summary}")
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
                    AgentLogStore.log("[$iterationCount] Human review requested: ${actionResult.reason}")
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
            appendLine("12. You can scroll_down or scroll_up to see more content.")
            appendLine("13. Use press_back to dismiss dialogs or navigate back if needed.")
            appendLine()
            appendLine("## Important")
            appendLine("You are looking at a REAL Android screen. The elements listed are actual UI components.")
            appendLine("Choose exactly ONE action per turn. Analyze the screen carefully before acting.")
        }
    }

    private fun buildUserMessage(formattedScreen: String): String {
        return buildString {
            append(formattedScreen)
            appendLine()
            if (previousActions.isNotEmpty()) {
                appendLine("## Your Previous Actions (last ${previousActions.size.coerceAtMost(10)})")
                for (action in previousActions.takeLast(10)) {
                    appendLine("- $action")
                }
                appendLine()
            }
            appendLine("Iteration: $iterationCount / ${SafetyPolicy.MAX_ITERATIONS}")
            appendLine()
            appendLine("What is your next action? Choose exactly one tool to use.")
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

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
