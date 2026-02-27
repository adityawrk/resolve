package com.cssupport.companion

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Multi-provider LLM client using the OpenAI-compatible chat completions API
 * with function/tool calling.
 *
 * Supports multi-turn conversation history: the agent loop builds up a list of
 * messages (system, user observations, assistant tool calls, tool results) and
 * sends the full conversation to the LLM each turn. This gives the model memory
 * across turns, dramatically improving navigation coherence.
 *
 * Supported providers:
 * - Azure OpenAI (GPT-5 Nano default)
 * - OpenAI direct
 * - Anthropic Claude (via messages API, mapped to tool-use format)
 * - Any OpenAI-compatible endpoint (Together, Groq, local, etc.)
 */
class LLMClient(private val config: LLMConfig) {

    private val tag = "LLMClient"

    /**
     * Send a chat completion request with full multi-turn message history.
     * [conversationMessages] is the ordered list of messages built by the AgentLoop.
     * The system prompt is always the first message.
     */
    suspend fun chatCompletion(
        systemPrompt: String,
        userMessage: String,
        conversationMessages: List<ConversationMessage>? = null,
    ): AgentDecision = withContext(Dispatchers.IO) {
        when (config.provider) {
            LLMProvider.ANTHROPIC -> callAnthropic(systemPrompt, userMessage, conversationMessages)
            else -> callOpenAICompatible(systemPrompt, userMessage, conversationMessages)
        }
    }

    // ── OpenAI-compatible (Azure, OpenAI, custom) ───────────────────────────

    private fun callOpenAICompatible(
        systemPrompt: String,
        userMessage: String,
        conversationMessages: List<ConversationMessage>?,
    ): AgentDecision {
        val url = buildOpenAIUrl()
        Log.d(tag, "Calling: $url (model=${config.model}, provider=${config.provider})")

        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))

            if (conversationMessages != null && conversationMessages.isNotEmpty()) {
                // Build multi-turn conversation from history.
                for (msg in conversationMessages) {
                    when (msg) {
                        is ConversationMessage.UserObservation -> {
                            put(JSONObject().put("role", "user").put("content", msg.content))
                        }
                        is ConversationMessage.AssistantToolCall -> {
                            put(JSONObject().apply {
                                put("role", "assistant")
                                if (msg.reasoning.isNotBlank()) {
                                    put("content", msg.reasoning)
                                }
                                put("tool_calls", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("id", msg.toolCallId)
                                        put("type", "function")
                                        put("function", JSONObject().apply {
                                            put("name", msg.toolName)
                                            put("arguments", msg.toolArguments)
                                        })
                                    })
                                })
                            })
                        }
                        is ConversationMessage.ToolResult -> {
                            put(JSONObject().apply {
                                put("role", "tool")
                                put("tool_call_id", msg.toolCallId)
                                put("content", msg.result)
                            })
                        }
                    }
                }
            }

            // Always append the current observation as the latest user message.
            put(JSONObject().put("role", "user").put("content", userMessage))
        }

        val body = JSONObject().apply {
            put("model", config.model)
            put("max_completion_tokens", 1024)
            put("messages", messages)
            put("tools", buildToolDefinitions())
            put("tool_choice", "required")
        }

        val headers = mutableMapOf(
            "Content-Type" to "application/json",
        )

        when (config.provider) {
            LLMProvider.AZURE_OPENAI -> {
                headers["api-key"] = config.apiKey
            }
            else -> {
                headers["Authorization"] = "Bearer ${config.apiKey}"
            }
        }

        val responseJson = httpPost(url, body.toString(), headers)
        return parseOpenAIResponse(responseJson)
    }

    private fun buildOpenAIUrl(): String {
        return when (config.provider) {
            LLMProvider.AZURE_OPENAI -> {
                var endpoint = config.endpoint?.removeSuffix("/")
                    ?: throw IllegalStateException("Azure endpoint is required")
                // Azure AI Foundry endpoints include /api/projects/<name> which must be
                // stripped — the OpenAI deployment path is appended to the base resource URL.
                val projectIdx = endpoint.indexOf("/api/projects/")
                if (projectIdx > 0) {
                    endpoint = endpoint.substring(0, projectIdx)
                }
                val apiVersion = config.apiVersion ?: "2024-10-21"
                "$endpoint/openai/deployments/${config.model}/chat/completions?api-version=$apiVersion"
            }
            LLMProvider.OPENAI -> {
                "https://api.openai.com/v1/chat/completions"
            }
            LLMProvider.CUSTOM -> {
                val endpoint = config.endpoint?.removeSuffix("/")
                    ?: throw IllegalStateException("Custom endpoint is required")
                "$endpoint/v1/chat/completions"
            }
            LLMProvider.ANTHROPIC -> {
                // Should not reach here -- handled separately.
                throw IllegalStateException("Use callAnthropic for Anthropic provider")
            }
        }
    }

    private fun parseOpenAIResponse(json: JSONObject): AgentDecision {
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            Log.w(tag, "No choices in response")
            return AgentDecision.wait("No response from LLM")
        }

        val message = choices.optJSONObject(0)?.optJSONObject("message")
            ?: return AgentDecision.wait("Malformed LLM response (no message)")
        val reasoning = message.optString("content", "")

        val toolCalls = message.optJSONArray("tool_calls")
        if (toolCalls != null && toolCalls.length() > 0) {
            val tc = toolCalls.getJSONObject(0)
            if (tc.getString("type") == "function") {
                val fn = tc.getJSONObject("function")
                val toolCallId = tc.optString("id", "call_${System.currentTimeMillis()}")
                val toolName = fn.getString("name")
                val toolArgs = fn.getString("arguments")
                val action = parseToolCall(toolName, toolArgs)
                return AgentDecision(
                    action = action,
                    reasoning = reasoning,
                    toolCallId = toolCallId,
                    toolName = toolName,
                    toolArguments = toolArgs,
                )
            }
        }

        return AgentDecision.wait(reasoning.ifBlank { "LLM returned no tool call" })
    }

    // ── Anthropic Messages API ──────────────────────────────────────────────

    private fun callAnthropic(
        systemPrompt: String,
        userMessage: String,
        conversationMessages: List<ConversationMessage>?,
    ): AgentDecision {
        val url = "https://api.anthropic.com/v1/messages"

        // Anthropic requires strictly alternating user/assistant roles.
        // We must merge consecutive same-role messages into one message with a content array.
        val rawMessages = mutableListOf<JSONObject>()
        if (conversationMessages != null && conversationMessages.isNotEmpty()) {
            for (msg in conversationMessages) {
                when (msg) {
                    is ConversationMessage.UserObservation -> {
                        rawMessages.add(JSONObject().put("role", "user").put("content", msg.content))
                    }
                    is ConversationMessage.AssistantToolCall -> {
                        rawMessages.add(JSONObject().apply {
                            put("role", "assistant")
                            put("content", JSONArray().apply {
                                if (msg.reasoning.isNotBlank()) {
                                    put(JSONObject().apply {
                                        put("type", "text")
                                        put("text", msg.reasoning)
                                    })
                                }
                                put(JSONObject().apply {
                                    put("type", "tool_use")
                                    put("id", msg.toolCallId)
                                    put("name", msg.toolName)
                                    put("input", try { JSONObject(msg.toolArguments) } catch (_: Exception) { JSONObject() })
                                })
                            })
                        })
                    }
                    is ConversationMessage.ToolResult -> {
                        rawMessages.add(JSONObject().apply {
                            put("role", "user")
                            put("content", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("type", "tool_result")
                                    put("tool_use_id", msg.toolCallId)
                                    put("content", msg.result)
                                })
                            })
                        })
                    }
                }
            }
        }
        // Append the current observation as the latest user message.
        rawMessages.add(JSONObject().put("role", "user").put("content", userMessage))

        // Merge consecutive same-role messages to satisfy Anthropic's alternation requirement.
        val messages = JSONArray()
        for (msg in rawMessages) {
            val role = msg.getString("role")
            if (messages.length() > 0 && messages.getJSONObject(messages.length() - 1).getString("role") == role) {
                // Merge into the previous message by combining content.
                val prev = messages.getJSONObject(messages.length() - 1)
                val prevContent = prev.opt("content")
                val curContent = msg.opt("content")
                val merged = JSONArray()
                // Convert previous content to array form if it's a string.
                if (prevContent is JSONArray) {
                    for (i in 0 until prevContent.length()) merged.put(prevContent.get(i))
                } else if (prevContent is String) {
                    merged.put(JSONObject().put("type", "text").put("text", prevContent))
                }
                // Append current content.
                if (curContent is JSONArray) {
                    for (i in 0 until curContent.length()) merged.put(curContent.get(i))
                } else if (curContent is String) {
                    merged.put(JSONObject().put("type", "text").put("text", curContent))
                }
                prev.put("content", merged)
            } else {
                messages.put(msg)
            }
        }

        val body = JSONObject().apply {
            put("model", config.model)
            put("max_tokens", 1024)
            put("system", systemPrompt)
            put("messages", messages)
            put("tools", buildAnthropicToolDefinitions())
            put("tool_choice", JSONObject().put("type", "any"))
        }

        val headers = mapOf(
            "Content-Type" to "application/json",
            "x-api-key" to config.apiKey,
            "anthropic-version" to "2023-06-01",
            "anthropic-beta" to "token-efficient-tool-use-2025-04-14",
        )

        val responseJson = httpPost(url, body.toString(), headers)
        return parseAnthropicResponse(responseJson)
    }

    private fun parseAnthropicResponse(json: JSONObject): AgentDecision {
        // Check for Anthropic error responses (e.g., overloaded, rate-limited).
        if (json.optString("type") == "error") {
            val errorMsg = json.optJSONObject("error")?.optString("message", null)
                ?: "Anthropic API error"
            throw LLMException("Anthropic error: $errorMsg")
        }

        val content = json.optJSONArray("content")
        if (content == null || content.length() == 0) {
            return AgentDecision.wait("No content in Anthropic response")
        }

        var reasoning = ""
        var action: AgentAction? = null
        var toolCallId = ""
        var toolName = ""
        var toolArguments = "{}"

        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            when (block.optString("type", "")) {
                "text" -> reasoning = block.optString("text", "")
                "tool_use" -> {
                    toolCallId = block.optString("id", "call_${System.currentTimeMillis()}")
                    toolName = block.optString("name", "")
                    val input = block.optJSONObject("input") ?: JSONObject()
                    toolArguments = input.toString()
                    action = parseToolCallFromJson(toolName, input)
                }
            }
        }

        return if (action != null) {
            AgentDecision(
                action = action,
                reasoning = reasoning,
                toolCallId = toolCallId,
                toolName = toolName,
                toolArguments = toolArguments,
            )
        } else {
            AgentDecision.wait(reasoning.ifBlank { "LLM returned no tool use" })
        }
    }

    // ── Tool definitions (OpenAI format) ────────────────────────────────────

    private fun buildToolDefinitions(): JSONArray {
        return JSONArray().apply {
            put(toolDef(
                name = "type_message",
                description = "Type and send a message in a chat input field. ONLY use when no clickable option buttons match your need. Most chatbots use button menus — click those instead. Not for search bars. Use wait_for_response after.",
                properties = JSONObject()
                    .put("text", JSONObject()
                        .put("type", "string")
                        .put("description", "Message to type and send, in first person as the customer"))
                    .put("elementId", JSONObject()
                        .put("type", "integer")
                        .put("description", "Optional [N] of the input field to target")),
                required = listOf("text"),
            ))

            put(toolDef(
                name = "click_element",
                description = "Click element by its [N] ID from screen state. ALWAYS use elementId.",
                properties = JSONObject()
                    .put("elementId", JSONObject()
                        .put("type", "integer")
                        .put("description", "REQUIRED. The [N] number from screen state."))
                    .put("expectedOutcome", JSONObject()
                        .put("type", "string")
                        .put("description", "Expected result")),
                required = listOf("elementId", "expectedOutcome"),
            ))

            put(toolDef(
                name = "scroll_down",
                description = "Scroll down to find a specific element below. Do not scroll to browse.",
                properties = JSONObject().put("reason", JSONObject()
                    .put("type", "string")
                    .put("description", "What you are looking for")),
                required = listOf("reason"),
            ))

            put(toolDef(
                name = "scroll_up",
                description = "Scroll up to see content above.",
                properties = JSONObject().put("reason", JSONObject()
                    .put("type", "string")
                    .put("description", "What you are looking for")),
                required = listOf("reason"),
            ))

            put(toolDef(
                name = "wait_for_response",
                description = "Wait 5s for screen to update. Use after sending a message, clicking a chatbot option, or triggering a page load.",
                properties = JSONObject().put("reason", JSONObject()
                    .put("type", "string")
                    .put("description", "What you are waiting for")),
                required = listOf("reason"),
            ))

            put(toolDef(
                name = "upload_file",
                description = "Click an attachment/upload button to upload evidence.",
                properties = JSONObject().put("fileDescription", JSONObject()
                    .put("type", "string")
                    .put("description", "Description of file to upload")),
                required = listOf("fileDescription"),
            ))

            put(toolDef(
                name = "press_back",
                description = "Press Android back button. Use to dismiss popups, go back, or close keyboard.",
                properties = JSONObject().put("reason", JSONObject()
                    .put("type", "string")
                    .put("description", "Why pressing back")),
                required = listOf("reason"),
            ))

            put(toolDef(
                name = "request_human_review",
                description = "Pause for customer input. Use when support asks for info you don't have (OTP, card digits), offers choices, or CAPTCHA blocks.",
                properties = JSONObject()
                    .put("reason", JSONObject()
                        .put("type", "string")
                        .put("description", "Why customer needs to intervene"))
                    .put("needsInput", JSONObject()
                        .put("type", "boolean")
                        .put("description", "Whether customer needs to type"))
                    .put("inputPrompt", JSONObject()
                        .put("type", "string")
                        .put("description", "Question to ask customer")),
                required = listOf("reason"),
            ))

            put(toolDef(
                name = "mark_resolved",
                description = "Mark case RESOLVED. Only when support explicitly confirmed resolution/refund, or ticket number provided. Never use prematurely.",
                properties = JSONObject().put("summary", JSONObject()
                    .put("type", "string")
                    .put("description", "Resolution summary with reference numbers/timelines")),
                required = listOf("summary"),
            ))

            put(toolDef(
                name = "update_plan",
                description = "Record your plan. Use at the start or when your approach changes. Does NOT perform an action — call another tool after.",
                properties = JSONObject()
                    .put("explanation", JSONObject()
                        .put("type", "string")
                        .put("description", "Brief explanation of your current strategy"))
                    .put("steps", JSONObject()
                        .put("type", "array")
                        .put("description", "Ordered list of planned steps")
                        .put("items", JSONObject()
                            .put("type", "object")
                            .put("properties", JSONObject()
                                .put("step", JSONObject().put("type", "string"))
                                .put("status", JSONObject()
                                    .put("type", "string")
                                    .put("enum", JSONArray().put("pending").put("in_progress").put("completed"))))
                            .put("required", JSONArray().put("step")))),
                required = listOf("steps"),
            ))
        }
    }

    private fun toolDef(
        name: String,
        description: String,
        properties: JSONObject,
        required: List<String>,
    ): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", properties)
                    put("required", JSONArray(required))
                })
            })
        }
    }

    // ── Tool definitions (Anthropic format) ─────────────────────────────────

    private fun buildAnthropicToolDefinitions(): JSONArray {
        // Anthropic uses a slightly different format: top-level name/description/input_schema.
        val openAITools = buildToolDefinitions()
        val anthropicTools = JSONArray()

        for (i in 0 until openAITools.length()) {
            val fn = openAITools.getJSONObject(i).getJSONObject("function")
            anthropicTools.put(JSONObject().apply {
                put("name", fn.getString("name"))
                put("description", fn.getString("description"))
                put("input_schema", fn.getJSONObject("parameters"))
            })
        }

        return anthropicTools
    }

    // ── Tool call parsing ───────────────────────────────────────────────────

    private fun parseToolCall(name: String, argsJson: String): AgentAction {
        return try {
            val input = JSONObject(argsJson)
            parseToolCallFromJson(name, input)
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse tool call args: $argsJson", e)
            AgentAction.Wait(reason = "Failed to parse tool args: ${e.message}")
        }
    }

    private fun parseToolCallFromJson(name: String, input: JSONObject): AgentAction {
        return when (name) {
            "type_message" -> AgentAction.TypeMessage(
                text = input.optString("text", ""),
                elementId = parseElementId(input, "elementId"),
            )
            "click_element" -> AgentAction.ClickElement(
                elementId = parseElementId(input, "elementId"),
                label = input.optString("label", "").ifBlank { null },
                expectedOutcome = input.optString("expectedOutcome", ""),
            )
            // Backward compatibility: old tool name still accepted.
            "click_button" -> AgentAction.ClickElement(
                elementId = null,
                label = input.optString("buttonLabel", input.optString("label", "")),
                expectedOutcome = "",
            )
            "scroll_down" -> AgentAction.ScrollDown(
                reason = input.optString("reason", ""),
            )
            "scroll_up" -> AgentAction.ScrollUp(
                reason = input.optString("reason", ""),
            )
            "wait_for_response" -> AgentAction.Wait(
                reason = input.optString("reason", ""),
            )
            "upload_file" -> AgentAction.UploadFile(
                fileDescription = input.optString("fileDescription", ""),
            )
            "press_back" -> AgentAction.PressBack(
                reason = input.optString("reason", ""),
            )
            "request_human_review" -> AgentAction.RequestHumanReview(
                reason = input.optString("reason", "Human review requested"),
                needsInput = input.optBoolean("needsInput", false),
                inputPrompt = if (input.has("inputPrompt")) input.optString("inputPrompt", "") else null,
            )
            "mark_resolved" -> AgentAction.MarkResolved(
                summary = input.optString("summary", "Issue resolved"),
            )
            "update_plan" -> {
                val explanation = input.optString("explanation", "")
                val stepsArray = input.optJSONArray("steps")
                val steps = mutableListOf<PlanStep>()
                if (stepsArray != null) {
                    for (j in 0 until stepsArray.length()) {
                        val stepObj = stepsArray.optJSONObject(j)
                        if (stepObj != null) {
                            steps.add(PlanStep(
                                step = stepObj.optString("step", ""),
                                status = stepObj.optString("status", "pending"),
                            ))
                        }
                    }
                }
                AgentAction.UpdatePlan(explanation = explanation, steps = steps)
            }
            else -> {
                Log.w(tag, "Unknown tool: $name")
                AgentAction.Wait(reason = "Unknown tool: $name")
            }
        }
    }

    /**
     * Parse an element ID from a JSON field, handling both int and string representations.
     * LLMs may return `"elementId": 42` or `"elementId": "42"`.
     */
    private fun parseElementId(input: JSONObject, key: String): Int? {
        if (!input.has(key)) return null
        return try {
            val raw = input.get(key)
            when (raw) {
                is Int -> raw.takeIf { it > 0 }
                is Long -> raw.toInt().takeIf { it > 0 }
                is String -> raw.trim().toIntOrNull()?.takeIf { it > 0 }
                else -> input.optInt(key, -1).takeIf { it > 0 }
            }
        } catch (_: Exception) {
            null
        }
    }

    // ── HTTP ────────────────────────────────────────────────────────────────

    private fun httpPost(url: String, body: String, headers: Map<String, String>): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 45_000  // LLM calls can be slow but 45s is plenty.
            connection.doInput = true
            connection.doOutput = true

            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }

            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            if (stream == null) {
                throw LLMException("HTTP $code: empty response (no body)")
            }
            val text = BufferedReader(InputStreamReader(stream)).use { reader ->
                reader.readText()
            }

            if (code !in 200..299) {
                Log.e(tag, "LLM HTTP $code (${config.provider}/${config.model}): ${text.take(800)}")
                // Include more of the response body so the actual error is visible in logs.
                val errorDetail = try {
                    val errJson = JSONObject(text)
                    errJson.optJSONObject("error")?.optString("message", null)
                        ?: errJson.optString("message", null)
                        ?: text.take(300)
                } catch (_: Exception) {
                    text.take(300)
                }
                throw LLMException("HTTP $code: $errorDetail")
            }

            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }
}

// ── Configuration ───────────────────────────────────────────────────────────

enum class LLMProvider {
    AZURE_OPENAI,
    OPENAI,
    ANTHROPIC,
    CUSTOM,
}

data class LLMConfig(
    val provider: LLMProvider,
    val apiKey: String,
    val model: String,
    val endpoint: String? = null,
    val apiVersion: String? = null,
) {
    companion object {
        /** Default config for Azure OpenAI GPT-5 Nano. */
        fun azureDefault(apiKey: String, endpoint: String): LLMConfig {
            return LLMConfig(
                provider = LLMProvider.AZURE_OPENAI,
                apiKey = apiKey,
                model = "gpt-5-nano",
                endpoint = endpoint,
                apiVersion = "2024-10-21",
            )
        }

        fun openAI(apiKey: String, model: String = "gpt-5-mini"): LLMConfig {
            return LLMConfig(
                provider = LLMProvider.OPENAI,
                apiKey = apiKey,
                model = model,
            )
        }

        fun anthropic(apiKey: String, model: String = "claude-sonnet-4-20250514"): LLMConfig {
            return LLMConfig(
                provider = LLMProvider.ANTHROPIC,
                apiKey = apiKey,
                model = model,
            )
        }

        fun custom(apiKey: String, endpoint: String, model: String): LLMConfig {
            return LLMConfig(
                provider = LLMProvider.CUSTOM,
                apiKey = apiKey,
                model = model,
                endpoint = endpoint,
            )
        }
    }
}

// ── Agent action types ──────────────────────────────────────────────────────

sealed class AgentAction {
    data class TypeMessage(
        val text: String,
        /** Optional element index of the input field to type into. */
        val elementId: Int? = null,
    ) : AgentAction()

    /**
     * Click an element on screen, identified by numeric index (preferred) or label (fallback).
     * Replaces the old ClickButton action with unambiguous element referencing.
     */
    data class ClickElement(
        /** The [N] number from the screen state. Preferred -- exact match. */
        val elementId: Int? = null,
        /** Fallback text label when elementId is not available. */
        val label: String? = null,
        /** What the LLM expects to happen after clicking. Used for post-action verification. */
        val expectedOutcome: String = "",
    ) : AgentAction()

    data class ScrollDown(val reason: String) : AgentAction()
    data class ScrollUp(val reason: String) : AgentAction()
    data class Wait(val reason: String) : AgentAction()
    data class UploadFile(val fileDescription: String) : AgentAction()
    data class PressBack(val reason: String) : AgentAction()
    data class RequestHumanReview(
        val reason: String,
        val needsInput: Boolean = false,
        val inputPrompt: String? = null,
    ) : AgentAction()
    data class MarkResolved(val summary: String) : AgentAction()

    /**
     * Plan tool (Codex pattern): no-op that forces structured LLM reasoning.
     * The plan is recorded in conversation history and shown to the user,
     * but has no mechanical effect on the agent.
     */
    data class UpdatePlan(
        val explanation: String,
        val steps: List<PlanStep>,
    ) : AgentAction()
}

/**
 * A single step in an LLM-generated plan (Codex update_plan pattern).
 */
data class PlanStep(
    val step: String,
    val status: String = "pending", // pending | in_progress | completed
)

data class AgentDecision(
    val action: AgentAction,
    val reasoning: String,
    /** Tool call ID from the LLM response, used for multi-turn conversation tracking. */
    val toolCallId: String = "call_${System.currentTimeMillis()}",
    /** Tool function name as returned by the LLM. */
    val toolName: String = "",
    /** Raw JSON arguments string from the tool call. */
    val toolArguments: String = "{}",
) {
    companion object {
        fun wait(reason: String) = AgentDecision(
            action = AgentAction.Wait(reason = reason),
            reasoning = reason,
        )
    }
}

/**
 * Represents a single message in the multi-turn conversation history sent to the LLM.
 *
 * The conversation is structured as:
 * 1. [UserObservation] — the screen state + context for the current turn
 * 2. [AssistantToolCall] — the LLM's chosen action (tool call)
 * 3. [ToolResult] — the result of executing that action
 *
 * This gives the LLM full memory of prior screens, its reasoning, and action outcomes.
 */
sealed class ConversationMessage {
    /** User message containing screen observation and context. */
    data class UserObservation(val content: String) : ConversationMessage()

    /** Assistant message with a tool call (the LLM's chosen action). */
    data class AssistantToolCall(
        val toolCallId: String,
        val toolName: String,
        val toolArguments: String,
        val reasoning: String,
    ) : ConversationMessage()

    /** Tool result message (outcome of executing the action). */
    data class ToolResult(
        val toolCallId: String,
        val result: String,
    ) : ConversationMessage()
}

class LLMException(message: String, cause: Throwable? = null) : Exception(message, cause)
