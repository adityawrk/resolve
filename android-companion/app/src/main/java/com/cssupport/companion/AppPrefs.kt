package com.cssupport.companion

import android.content.Context

private const val PREFS_NAME = "cs_companion_prefs"
private const val KEY_API_KEY = "api_key"
private const val KEY_APP_NAME = "app_name"
private const val KEY_DEVICE_ID = "device_id"
private const val KEY_DEVICE_TOKEN = "device_token"

data class DeviceCredentials(
  val deviceId: String,
  val deviceToken: String,
)

data class CompanionConfig(
  val apiKey: String,
  val appName: String,
  val credentials: DeviceCredentials?,
)

class AppPrefs(context: Context) {
  private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  fun loadConfig(): CompanionConfig {
    val apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
    val appName = prefs.getString(KEY_APP_NAME, "Amazon") ?: "Amazon"

    val deviceId = prefs.getString(KEY_DEVICE_ID, null)
    val deviceToken = prefs.getString(KEY_DEVICE_TOKEN, null)
    val credentials = if (!deviceId.isNullOrBlank() && !deviceToken.isNullOrBlank()) {
      DeviceCredentials(deviceId = deviceId, deviceToken = deviceToken)
    } else {
      null
    }

    return CompanionConfig(
      apiKey = apiKey,
      appName = appName,
      credentials = credentials,
    )
  }

  fun saveDraft(apiKey: String, appName: String) {
    prefs.edit()
      .putString(KEY_API_KEY, apiKey.trim())
      .putString(KEY_APP_NAME, appName.trim())
      .apply()
  }

  fun saveCredentials(credentials: DeviceCredentials) {
    prefs.edit()
      .putString(KEY_DEVICE_ID, credentials.deviceId)
      .putString(KEY_DEVICE_TOKEN, credentials.deviceToken)
      .apply()
  }

  fun clearCredentials() {
    prefs.edit()
      .remove(KEY_DEVICE_ID)
      .remove(KEY_DEVICE_TOKEN)
      .apply()
  }
}
