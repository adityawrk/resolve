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

        val message = choices.getJSONObject(0).getJSONObject("message")
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

        val messages = JSONArray().apply {
            if (conversationMessages != null && conversationMessages.isNotEmpty()) {
                for (msg in conversationMessages) {
                    when (msg) {
                        is ConversationMessage.UserObservation -> {
                            put(JSONObject().put("role", "user").put("content", msg.content))
                        }
                        is ConversationMessage.AssistantToolCall -> {
                            put(JSONObject().apply {
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
                                        put("input", JSONObject(msg.toolArguments))
                                    })
                                })
                            })
                        }
                        is ConversationMessage.ToolResult -> {
                            put(JSONObject().apply {
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

            // Always append the current observation as the latest user message.
            put(JSONObject().put("role", "user").put("content", userMessage))
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
        )

        val responseJson = httpPost(url, body.toString(), headers)
        return parseAnthropicResponse(responseJson)
    }

    private fun parseAnthropicResponse(json: JSONObject): AgentDecision {
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
            when (block.getString("type")) {
                "text" -> reasoning = block.getString("text")
                "tool_use" -> {
                    toolCallId = block.optString("id", "call_${System.currentTimeMillis()}")
                    toolName = block.getString("name")
                    val input = block.getJSONObject("input")
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
                description = "Type a message into the active chat/message input field and send it. " +
                    "ONLY use this when you are in a chat/support interface with a visible input field. " +
                    "Do NOT use this for search bars or non-chat inputs. " +
                    "After sending, you should use wait_for_response to wait for a reply.",
                properties = JSONObject().put("text", JSONObject()
                    .put("type", "string")
                    .put("description", "The message to type and send. Speak as the customer in first person.")),
                required = listOf("text"),
            ))

            put(toolDef(
                name = "click_button",
                description = "Click a button, link, tab, or any interactive element visible on screen. " +
                    "The label must match the text or content description shown in the screen state. " +
                    "Use the EXACT text as shown — partial matches work but exact is preferred. " +
                    "For icon buttons without text, use the content description (e.g., \"Navigate up\", \"More options\").",
                properties = JSONObject().put("buttonLabel", JSONObject()
                    .put("type", "string")
                    .put("description", "The label text or content description of the element to click, as shown on screen")),
                required = listOf("buttonLabel"),
            ))

            put(toolDef(
                name = "scroll_down",
                description = "Scroll down to reveal more content below the current viewport. " +
                    "Use when you need to find a specific item (e.g., an order, a Help button) that may be below. " +
                    "Do NOT scroll to browse — only scroll when you have a specific target in mind.",
                properties = JSONObject().put("reason", JSONObject()
                    .put("type", "string")
                    .put("description", "What you are looking for by scrolling")),
                required = listOf("reason"),
            ))

            put(toolDef(
                name = "scroll_up",
                description = "Scroll up to see earlier content above the current viewport.",
                properties = JSONObject().put("reason", JSONObject()
                    .put("type", "string")
                    .put("description", "What you are looking for by scrolling up")),
                required = listOf("reason"),
            ))

            put(toolDef(
                name = "wait_for_response",
                description = "Wait 5 seconds for the screen to update. " +
                    "Use after sending a chat message to wait for the support agent/bot to reply. " +
                    "Also use after clicking something that triggers a page load.",
                properties = JSONObject().put("reason", JSONObject()
                    .put("type", "string")
                    .put("description", "What you are waiting for")),
                required = listOf("reason"),
            ))

            put(toolDef(
                name = "upload_file",
                description = "Find and click an attachment/upload button to upload evidence. " +
                    "Use when the support agent asks for proof or when evidence would strengthen the case.",
                properties = JSONObject().put("fileDescription", JSONObject()
                    .put("type", "string")
                    .put("description", "Description of the file to upload")),
                required = listOf("fileDescription"),
            ))

            put(toolDef(
                name = "press_back",
                description = "Press the Android back button. Use to: " +
                    "(1) dismiss a popup, dialog, or overlay, " +
                    "(2) go back to the previous screen if you navigated to the wrong place, " +
                    "(3) close a keyboard or bottom sheet.",
                properties = JSONObject().put("reason", JSONObject()
                    .put("type", "string")
                    .put("description", "Why pressing back and where you expect to go")),
                required = listOf("reason"),
            ))

            put(toolDef(
                name = "request_human_review",
                description = "Pause and ask the customer for input. Use when: " +
                    "(1) support asks for info you don't have (OTP, last 4 digits of card, etc.), " +
                    "(2) support offers multiple resolution options and you need customer to choose, " +
                    "(3) a CAPTCHA or verification step blocks progress.",
                properties = JSONObject()
                    .put("reason", JSONObject()
                        .put("type", "string")
                        .put("description", "Why the customer needs to intervene"))
                    .put("needsInput", JSONObject()
                        .put("type", "boolean")
                        .put("description", "Whether the customer needs to type a response"))
                    .put("inputPrompt", JSONObject()
                        .put("type", "string")
                        .put("description", "Specific question to ask the customer")),
                required = listOf("reason"),
            ))

            put(toolDef(
                name = "mark_resolved",
                description = "Mark the case as RESOLVED. Use ONLY when: " +
                    "(1) the support agent has explicitly confirmed the refund/resolution, " +
                    "(2) a ticket/reference number has been provided, or " +
                    "(3) the desired outcome has been clearly achieved. " +
                    "Do NOT use this prematurely — wait for actual confirmation.",
                properties = JSONObject().put("summary", JSONObject()
                    .put("type", "string")
                    .put("description", "Summary of the resolution including any reference numbers or timelines given")),
                required = listOf("summary"),
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
                text = input.getString("text"),
            )
            "click_button" -> AgentAction.ClickButton(
                buttonLabel = input.getString("buttonLabel"),
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
                reason = input.getString("reason"),
                needsInput = input.optBoolean("needsInput", false),
                inputPrompt = if (input.has("inputPrompt")) input.optString("inputPrompt", "") else null,
            )
            "mark_resolved" -> AgentAction.MarkResolved(
                summary = input.getString("summary"),
            )
            else -> {
                Log.w(tag, "Unknown tool: $name")
                AgentAction.Wait(reason = "Unknown tool: $name")
            }
        }
    }

    // ── HTTP ────────────────────────────────────────────────────────────────

    private fun httpPost(url: String, body: String, headers: Map<String, String>): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000  // LLM calls can be slow.
            connection.doInput = true
            connection.doOutput = true

            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }

            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            if (stream == null) {
                return JSONObject()
            }
            val text = BufferedReader(InputStreamReader(stream)).use { reader ->
                reader.readText()
            }

            if (code !in 200..299) {
                Log.e(tag, "LLM HTTP $code (${config.provider}/${config.model}): ${text.take(800)}")
                // Include more of the response body so the actual error is visible in logs.
                val errorDetail = try {
                    val errJson = JSONObject(text)
                    errJson.optJSONObject("error")?.optString("message")
                        ?: errJson.optString("message")
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
    data class TypeMessage(val text: String) : AgentAction()
    data class ClickButton(val buttonLabel: String) : AgentAction()
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
}

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
