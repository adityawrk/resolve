package com.cssupport.companion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class MediaUpload(
  val name: String,
  val type: String,
  val data: String,
)

data class MobileIssueSubmission(
  val apiKey: String,
  val appName: String,
  val issue: String,
  val description: String?,
  val orderId: String?,
  val media: List<MediaUpload>,
  val deviceName: String,
)

data class MobileIssueSession(
  val sessionId: String,
  val deviceId: String,
  val deviceToken: String,
  val deviceName: String,
  val platform: String,
)

data class MobileIssueSubmissionResult(
  val caseId: String,
  val targetPlatform: String,
  val session: MobileIssueSession,
)

data class CompanionCommandPayload(
  val caseId: String,
  val customerName: String,
  val issue: String,
  val orderId: String?,
  val attachmentPaths: List<String>,
  val targetPlatform: String,
  val desiredOutcome: String,
)

data class CompanionCommand(
  val id: String,
  val deviceId: String,
  val payload: CompanionCommandPayload,
)

class BackendClient {
  suspend fun submitMobileIssue(
    baseUrl: String,
    request: MobileIssueSubmission,
  ): MobileIssueSubmissionResult = withContext(Dispatchers.IO) {
    val media = JSONArray().apply {
      request.media.forEach { file ->
        put(
          JSONObject()
            .put("name", file.name)
            .put("type", file.type)
            .put("data", file.data),
        )
      }
    }

    val body = JSONObject()
      .put("apiKey", request.apiKey)
      .put("appName", request.appName)
      .put("issue", request.issue)
      .put("description", request.description)
      .put("orderId", request.orderId)
      .put("media", media)
      .put(
        "device",
        JSONObject()
          .put("name", request.deviceName)
          .put("platform", "android"),
      )

    val response = requestJson(
      method = "POST",
      url = normalizedBaseUrl(baseUrl) + "/api/client/mobile/submit",
      body = body.toString(),
      headers = mapOf("content-type" to "application/json"),
    )

    val sessionJson = response.getJSONObject("session")
    val session = MobileIssueSession(
      sessionId = sessionJson.getString("id"),
      deviceId = sessionJson.getString("deviceId"),
      deviceToken = sessionJson.getString("deviceToken"),
      deviceName = sessionJson.getString("deviceName"),
      platform = sessionJson.getString("platform"),
    )

    MobileIssueSubmissionResult(
      caseId = response.getString("caseId"),
      targetPlatform = response.optString("targetPlatform"),
      session = session,
    )
  }

  suspend fun pollCommand(
    baseUrl: String,
    credentials: DeviceCredentials,
  ): CompanionCommand? = withContext(Dispatchers.IO) {
    val body = JSONObject().put("deviceId", credentials.deviceId)

    val response = requestJson(
      method = "POST",
      url = normalizedBaseUrl(baseUrl) + "/api/device-agent/poll",
      body = body.toString(),
      headers = mapOf(
        "content-type" to "application/json",
        "x-device-token" to credentials.deviceToken,
      ),
    )

    if (response.isNull("command")) {
      return@withContext null
    }

    parseCommand(response.getJSONObject("command"))
  }

  suspend fun postCommandEvent(
    baseUrl: String,
    credentials: DeviceCredentials,
    commandId: String,
    message: String,
    stage: String = "step",
  ) = withContext(Dispatchers.IO) {
    val body = JSONObject()
      .put("deviceId", credentials.deviceId)
      .put("message", message)
      .put("stage", stage)

    requestJson(
      method = "POST",
      url = normalizedBaseUrl(baseUrl) + "/api/device-agent/commands/$commandId/events",
      body = body.toString(),
      headers = mapOf(
        "content-type" to "application/json",
        "x-device-token" to credentials.deviceToken,
      ),
    )
  }

  suspend fun completeCommand(
    baseUrl: String,
    credentials: DeviceCredentials,
    commandId: String,
    resultSummary: String,
  ) = withContext(Dispatchers.IO) {
    val body = JSONObject()
      .put("deviceId", credentials.deviceId)
      .put("resultSummary", resultSummary)

    requestJson(
      method = "POST",
      url = normalizedBaseUrl(baseUrl) + "/api/device-agent/commands/$commandId/complete",
      body = body.toString(),
      headers = mapOf(
        "content-type" to "application/json",
        "x-device-token" to credentials.deviceToken,
      ),
    )
  }

  suspend fun failCommand(
    baseUrl: String,
    credentials: DeviceCredentials,
    commandId: String,
    errorMessage: String,
  ) = withContext(Dispatchers.IO) {
    val body = JSONObject()
      .put("deviceId", credentials.deviceId)
      .put("errorMessage", errorMessage)

    requestJson(
      method = "POST",
      url = normalizedBaseUrl(baseUrl) + "/api/device-agent/commands/$commandId/fail",
      body = body.toString(),
      headers = mapOf(
        "content-type" to "application/json",
        "x-device-token" to credentials.deviceToken,
      ),
    )
  }

  private fun parseCommand(commandJson: JSONObject): CompanionCommand {
    val payloadJson = commandJson.getJSONObject("payload")
    val attachmentPathsJson = payloadJson.optJSONArray("attachmentPaths") ?: JSONArray()

    val attachmentPaths = buildList {
      for (index in 0 until attachmentPathsJson.length()) {
        add(attachmentPathsJson.getString(index))
      }
    }

    val orderIdRaw = payloadJson.optString("orderId", "")

    val payload = CompanionCommandPayload(
      caseId = payloadJson.getString("caseId"),
      customerName = payloadJson.optString("customerName"),
      issue = payloadJson.getString("issue"),
      orderId = orderIdRaw.takeIf { it.isNotBlank() },
      attachmentPaths = attachmentPaths,
      targetPlatform = payloadJson.getString("targetPlatform"),
      desiredOutcome = payloadJson.getString("desiredOutcome"),
    )

    return CompanionCommand(
      id = commandJson.getString("id"),
      deviceId = commandJson.getString("deviceId"),
      payload = payload,
    )
  }

  private fun normalizedBaseUrl(baseUrl: String): String {
    return baseUrl.trim().removeSuffix("/")
  }

  private fun requestJson(
    method: String,
    url: String,
    body: String,
    headers: Map<String, String>,
  ): JSONObject {
    val connection = (URL(url).openConnection() as HttpURLConnection)

    return try {
      connection.requestMethod = method
      connection.connectTimeout = 20_000
      connection.readTimeout = 20_000
      connection.doInput = true
      connection.doOutput = method != "GET"

      headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }

      if (method != "GET") {
        connection.outputStream.use { output ->
          output.write(body.toByteArray(Charsets.UTF_8))
        }
      }

      val code = connection.responseCode
      val stream = if (code in 200..299) connection.inputStream else connection.errorStream
      val text = stream.readTextSafely()

      if (code !in 200..299) {
        throw IllegalStateException("HTTP $code from $url: $text")
      }

      JSONObject(text)
    } finally {
      connection.disconnect()
    }
  }

  private fun InputStream?.readTextSafely(): String {
    if (this == null) {
      return ""
    }

    return BufferedReader(InputStreamReader(this)).use { reader ->
      buildString {
        while (true) {
          val line = reader.readLine() ?: break
          append(line)
        }
      }
    }
  }
}
