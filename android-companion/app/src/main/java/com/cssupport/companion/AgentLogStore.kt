package com.cssupport.companion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class LogCategory {
    AGENT_ACTION,
    AGENT_MESSAGE,
    STATUS_UPDATE,
    ERROR,
    DEBUG,
    TERMINAL_RESOLVED,
    TERMINAL_FAILED,
    APPROVAL_NEEDED,
}

data class LogEntry(
    val id: Long,
    val category: LogCategory,
    val displayMessage: String,
    val timestamp: Long = System.currentTimeMillis(),
    val formattedTime: String = timestampFormat.format(Date(timestamp)),
) {
    companion object {
        private val timestampFormat = SimpleDateFormat("HH:mm", Locale.US)
    }
}

object AgentLogStore {
    private val idCounter = AtomicLong(0)
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries = _entries.asStateFlow()

    @Synchronized
    fun log(message: String, category: LogCategory = LogCategory.DEBUG, display: String? = null) {
        val entry = LogEntry(
            id = idCounter.incrementAndGet(),
            category = category,
            displayMessage = display ?: message,
        )
        _entries.value = (_entries.value + entry).takeLast(100)
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
        idCounter.set(0)
    }
}
