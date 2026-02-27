package com.cssupport.companion

import android.util.Log

/**
 * Action-level safety policy for the agent loop.
 *
 * Validates every action the LLM proposes before execution. Blocks or flags
 * actions that contain sensitive data, require financial approval, or exceed
 * operational limits.
 */
class SafetyPolicy(
    private val maxIterations: Int = MAX_ITERATIONS,
    private val minActionDelayMs: Long = MIN_ACTION_DELAY_MS,
    private val autoApproveSafeActions: Boolean = false,
) {

    private val tag = "SafetyPolicy"

    /**
     * Validate a proposed action. Returns a [PolicyResult] indicating whether
     * the action is allowed, needs approval, or is blocked.
     */
    fun validate(action: AgentAction, iterationCount: Int): PolicyResult {
        // Check iteration limit.
        if (iterationCount >= maxIterations) {
            return PolicyResult.Blocked(
                reason = "Maximum iteration limit ($maxIterations) reached. " +
                    "The agent has been running too long without resolving the issue.",
            )
        }

        return when (action) {
            is AgentAction.TypeMessage -> validateMessage(action.text)
            is AgentAction.ClickElement -> {
                // Validate the label if present (for financial/destructive keyword checks).
                val label = action.label ?: action.expectedOutcome
                val result = if (label.isNotBlank()) validateClick(label) else PolicyResult.Allowed
                // Auto-approve non-financial click actions when the user has opted in.
                // Financial actions always require human approval (intentional friction).
                if (result is PolicyResult.NeedsApproval && autoApproveSafeActions) {
                    val lower = (label ?: "").lowercase()
                    val isFinancial = FINANCIAL_KEYWORDS.any { lower.contains(it) }
                    if (isFinancial) result else PolicyResult.Allowed
                } else {
                    result
                }
            }
            is AgentAction.MarkResolved -> PolicyResult.Allowed
            is AgentAction.RequestHumanReview -> PolicyResult.Allowed
            is AgentAction.Wait -> PolicyResult.Allowed
            is AgentAction.ScrollDown -> PolicyResult.Allowed
            is AgentAction.ScrollUp -> PolicyResult.Allowed
            is AgentAction.PressBack -> PolicyResult.Allowed
            is AgentAction.UploadFile -> PolicyResult.Allowed
            is AgentAction.UpdatePlan -> PolicyResult.Allowed // No-op, always safe.
        }
    }

    /**
     * Validate message text for sensitive data leakage.
     */
    private fun validateMessage(text: String): PolicyResult {
        // Check for SSN patterns (xxx-xx-xxxx or xxxxxxxxx).
        if (SSN_PATTERN.containsMatchIn(text)) {
            return PolicyResult.Blocked(
                reason = "Message appears to contain a Social Security Number. " +
                    "Sensitive personal data must never be sent in chat.",
            )
        }

        // Check for credit card numbers (13-19 digit sequences, with optional spaces/dashes).
        if (CREDIT_CARD_PATTERN.containsMatchIn(text)) {
            val cleaned = text.replace(Regex("[\\s-]"), "")
            if (looksLikeCreditCard(cleaned)) {
                return PolicyResult.Blocked(
                    reason = "Message appears to contain a credit card number. " +
                        "Sensitive financial data must never be sent in chat.",
                )
            }
        }

        // Check for passwords or secrets.
        if (PASSWORD_PATTERN.containsMatchIn(text)) {
            return PolicyResult.NeedsApproval(
                reason = "Message may contain a password or secret. Please confirm before sending.",
            )
        }

        // Check for excessively long messages (potential prompt injection).
        if (text.length > MAX_MESSAGE_LENGTH) {
            return PolicyResult.Blocked(
                reason = "Message exceeds maximum length ($MAX_MESSAGE_LENGTH chars). " +
                    "This may indicate a prompt injection attempt.",
            )
        }

        return PolicyResult.Allowed
    }

    /**
     * Validate button clicks for financial/destructive actions.
     */
    private fun validateClick(buttonLabel: String): PolicyResult {
        val lower = buttonLabel.lowercase()

        // Financial actions that need human approval.
        for (keyword in FINANCIAL_KEYWORDS) {
            if (lower.contains(keyword)) {
                return PolicyResult.NeedsApproval(
                    reason = "Clicking '$buttonLabel' may involve a financial transaction. " +
                        "Human approval is required.",
                )
            }
        }

        // Destructive actions that need approval.
        for (keyword in DESTRUCTIVE_KEYWORDS) {
            if (lower.contains(keyword)) {
                return PolicyResult.NeedsApproval(
                    reason = "Clicking '$buttonLabel' may be a destructive/irreversible action. " +
                        "Human approval is required.",
                )
            }
        }

        return PolicyResult.Allowed
    }

    /**
     * Basic Luhn check to distinguish credit card numbers from other long digit sequences.
     */
    private fun looksLikeCreditCard(cleaned: String): Boolean {
        val digits = cleaned.filter { it.isDigit() }
        if (digits.length < 13 || digits.length > 19) return false

        // Luhn algorithm.
        var sum = 0
        var alternate = false
        for (i in digits.length - 1 downTo 0) {
            var n = digits[i].digitToInt()
            if (alternate) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
            alternate = !alternate
        }
        return sum % 10 == 0
    }

    companion object {
        const val MAX_ITERATIONS = 30
        const val MIN_ACTION_DELAY_MS = 800L
        const val MAX_MESSAGE_LENGTH = 2000

        // SSN: xxx-xx-xxxx or 9 consecutive digits.
        private val SSN_PATTERN = Regex("""\b\d{3}-\d{2}-\d{4}\b|\b\d{9}\b""")

        // Credit card: 13-19 digits with optional spaces/dashes.
        private val CREDIT_CARD_PATTERN = Regex("""\b[\d][\d\s-]{11,22}[\d]\b""")

        // Password-like strings.
        private val PASSWORD_PATTERN = Regex(
            """(?i)(password|passwd|pwd)\s*[:=]\s*\S+""",
        )

        private val FINANCIAL_KEYWORDS = listOf(
            "pay", "purchase", "subscribe", "buy", "checkout",
            "place order", "confirm payment", "add to cart",
            "complete purchase", "authorize",
        )

        private val DESTRUCTIVE_KEYWORDS = listOf(
            "delete account", "close account", "cancel subscription",
            "terminate", "deactivate",
        )
    }
}

sealed class PolicyResult {
    /** Action is safe to execute. */
    data object Allowed : PolicyResult()

    /** Action needs human approval before execution. */
    data class NeedsApproval(val reason: String) : PolicyResult()

    /** Action is blocked and must not be executed. */
    data class Blocked(val reason: String) : PolicyResult()
}
