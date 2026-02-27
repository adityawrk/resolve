package com.cssupport.companion

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug logger that writes full LLM conversations to a file on device.
 *
 * Each agent run creates a new log file with the full system prompt, user messages,
 * LLM responses, parsed actions, and verification results. This gives complete
 * visibility into what the agent sees and how the LLM responds.
 *
 * Log files are stored in the app's external files directory under `debug_logs/`.
 * The user can share these files to help diagnose issues.
 */
object DebugLogger {

    private const val TAG = "DebugLogger"
    private const val MAX_LOG_FILES = 5

    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * Start a new debug log for an agent run.
     * Call this at the beginning of each agent loop.
     */
    fun startNewRun(context: Context, targetApp: String) {
        try {
            val dir = File(context.getExternalFilesDir(null), "debug_logs")
            if (!dir.exists()) dir.mkdirs()

            // Clean up old logs (keep only the most recent N).
            val existing = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
            existing.drop(MAX_LOG_FILES - 1).forEach { it.delete() }

            val timestamp = dateFormat.format(Date())
            logFile = File(dir, "agent_${timestamp}.log")
            write("=== Agent Debug Log ===")
            write("Started: ${Date()}")
            write("Target: $targetApp")
            write("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            write("Android: ${android.os.Build.VERSION.SDK_INT}")
            write("")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start debug log: ${e.message}")
        }
    }

    /** Log the system prompt sent to the LLM. */
    fun logSystemPrompt(prompt: String) {
        write("──── SYSTEM PROMPT ────")
        write(prompt)
        write("───────────────────────")
        write("")
    }

    /** Log the user message (screen state + directives) sent to the LLM. */
    fun logUserMessage(iteration: Int, phase: String, message: String) {
        write("──── TURN $iteration | Phase: $phase ────")
        write("USER MESSAGE:")
        write(message)
        write("")
    }

    /** Log the raw LLM response (tool call + reasoning). */
    fun logLLMResponse(toolName: String, toolArgs: String, reasoning: String) {
        write("LLM RESPONSE:")
        write("  Tool: $toolName")
        write("  Args: $toolArgs")
        if (reasoning.isNotBlank()) {
            write("  Reasoning: ${reasoning.take(500)}")
        }
        write("")
    }

    /** Log action execution result. */
    fun logActionResult(description: String, result: String) {
        write("ACTION: $description")
        write("RESULT: $result")
        write("")
    }

    /** Log phase transition. */
    fun logPhaseChange(from: String, to: String) {
        write("PHASE CHANGE: $from → $to")
        write("")
    }

    /** Log any notable event. */
    fun logEvent(event: String) {
        write("EVENT: $event")
    }

    /** Get the current log file path, or null if not logging. */
    fun getLogFilePath(): String? = logFile?.absolutePath

    /** Get the current log file, or null. */
    fun getLogFile(): File? = logFile

    private fun write(text: String) {
        val file = logFile ?: return
        try {
            val timestamp = timeFormat.format(Date())
            file.appendText("[$timestamp] $text\n")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write debug log: ${e.message}")
        }
    }
}
