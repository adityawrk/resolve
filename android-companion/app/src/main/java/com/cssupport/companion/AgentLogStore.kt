package com.cssupport.companion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AgentLogStore {
  private val timestampFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
  private val _entries = MutableStateFlow(listOf("Agent idle"))
  val entries = _entries.asStateFlow()

  @Synchronized
  fun log(message: String) {
    val stamped = "[${timestampFormat.format(Date())}] $message"
    _entries.value = (_entries.value + stamped).takeLast(80)
  }
}
