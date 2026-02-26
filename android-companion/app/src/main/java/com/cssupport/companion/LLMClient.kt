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
 * Supported providers:
 * - Azure OpenAI (GPT-5 Nano default)
 * - OpenAI direct
 * - Anthropic Claude (via messages API, mapped to tool-use format)
 * - Any OpenAI-compatible endpoint (Together, Groq, local, etc.)
 */
class LLMClient(private val config: LLMConfig) {

    private val tag = "LLMClient"

    /**
     * Send a chat completion request with tool definitions and return the parsed decision.
     */
    suspend fun chatCompletion(
        systemPrompt: String,
        userMessage: String,
    ): AgentDecision = withContext(Dispatchers.IO) {
        when (config.provider) {
            LLMProvider.ANTHROPIC -> callAnthropic(systemPrompt, userMessage)
            else -> callOpenAICompatible(systemPrompt, userMessage)
        }
    }

    // ── OpenAI-compatible (Azure, OpenAI, custom) ───────────────────────────

    private fun callOpenAICompatible(systemPrompt: String, userMessage: String): AgentDecision {
        val url = buildOpenAIUrl()
        Log.d(tag, "Calling: $url (model=${config.model}, provider=${config.provider})")
        val body = JSONObject().apply {
            put("model", config.model)
            put("max_completion_tokens", 1024)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", userMessage))
            })
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
                val action = parseToolCall(fn.getString("name"), fn.getString("arguments"))
                return AgentDecision(action = action, reasoning = reasoning)
            }
        }

        return AgentDecision.wait(reasoning.ifBlank { "LLM returned no tool call" })
    }

    // ── Anthropic Messages API ──────────────────────────────────────────────

    private fun callAnthropic(systemPrompt: String, userMessage: String): AgentDecision {
        val url = "https://api.anthropic.com/v1/messages"

        val body = JSONObject().apply {
            put("model", config.model)
            put("max_tokens", 1024)
            put("system", systemPrompt)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "user").put("content", userMessage))
            })
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

        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            when (block.getString("type")) {
                "text" -> reasoning = block.getString("text")
                "tool_use" -> {
                    val name = block.getString("name")
                    val input = block.getJSONObject("input")
                    action = parseToolCallFromJson(name, input)
                }
            }
        }

        return if (action != null) {
            AgentDecision(action = action, reasoning = reasoning)
        } else {
            AgentDecision.wait(reasoning.ifBlank { "LLM returned no tool use" })
        }
    }

    // ── Tool definitions (OpenAI format) ────────────────────────────────────

    private fun buildToolDefinitions(): JSONArray {
        return JSONArray().apply {
            put(toolDef(
                name = "type_message",
                description = "Type a message in the chat input field and send it to the support agent. " +
                    "Use this to describe the issue, answer questions, provide order details, or negotiate resolution.",
                properties = JSONObject().put("text", JSONObject()
                    .put("type", "string")
                    .put("description", "The message to type and send")),
                required = listOf("text"),
            ))

            put(toolDef(
                name = "click_button",
                description = "Click a button or interactive element visible on screen. " +
                    "Use this to select menu options, confirm choices, or navigate the support flow.",
                properties = JSONObject().put("buttonLabel", JSONObject()
                    .put("type", "string")
                    .put("description", "The exact label text of the button to click")),
                required = listOf("buttonLabel"),
            ))

            put(toolDef(
                name = "scroll_down",
                description = "Scroll down in the current view to see more content. " +
                    "Use this when you need to see more messages or options below.",
                properties = JSONObject().put("reason", JSONObject()
                    .put("type", "string")
                    .put("description", "Why scrolling is needed")),
                required = listOf("reason"),
            ))

            put(toolDef(
                name = "scroll_up",
                description = "Scroll up in the current view to see earlier content.",
                properties = JSONObject().put("reason", JSONObject()
                    .put("type", "string")
                    .put("description", "Why scrolling is needed")),
                required = listOf("reason"),
            ))

            put(toolDef(
                name = "wait_for_response",
                description = "Wait for the support agent or bot to respond before taking the next action. " +
                    "Use this after sending a message when you expect a reply.",
                properties = JSONObject().put("reason", JSONObject()
                    .put("type", "string")
                    .put("description", "Why we are waiting")),
                required = listOf("reason"),
            ))

            put(toolDef(
                name = "upload_file",
                description = "Upload an evidence file to the support chat. " +
                    "Use when the support agent asks for proof or when evidence strengthens the case.",
                properties = JSONObject().put("fileDescription", JSONObject()
                    .put("type", "string")
                    .put("description", "Description of which attached file to upload")),
                required = listOf("fileDescription"),
            ))

            put(toolDef(
                name = "press_back",
                description = "Press the device back button. Use to navigate back or dismiss a dialog.",
                properties = JSONObject().put("reason", JSONObject()
                    .put("type", "string")
                    .put("description", "Why pressing back")),
                required = listOf("reason"),
            ))

            put(toolDef(
                name = "request_human_review",
                description = "Pause automation and ask the customer for input. " +
                    "Use when: (1) the support agent asks for information you don't have, " +
                    "(2) a sensitive decision needs human approval, (3) you are unsure how to proceed.",
                properties = JSONObject()
                    .put("reason", JSONObject()
                        .put("type", "string")
                        .put("description", "Why the customer needs to review"))
                    .put("needsInput", JSONObject()
                        .put("type", "boolean")
                        .put("description", "Whether the customer needs to type a response"))
                    .put("inputPrompt", JSONObject()
                        .put("type", "string")
                        .put("description", "What to ask the customer for")),
                required = listOf("reason"),
            ))

            put(toolDef(
                name = "mark_resolved",
                description = "Mark the support case as resolved. Use when: (1) the desired outcome has been achieved, " +
                    "(2) the support agent has confirmed the resolution, or (3) the ticket has been closed.",
                properties = JSONObject().put("summary", JSONObject()
                    .put("type", "string")
                    .put("description", "Summary of what was resolved and the outcome")),
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
                inputPrompt = input.optString("inputPrompt", null),
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
) {
    companion object {
        fun wait(reason: String) = AgentDecision(
            action = AgentAction.Wait(reason = reason),
            reasoning = reason,
        )
    }
}

class LLMException(message: String, cause: Throwable? = null) : Exception(message, cause)
