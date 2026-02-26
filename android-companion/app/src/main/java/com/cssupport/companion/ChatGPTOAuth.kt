package com.cssupport.companion

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Handles the ChatGPT OAuth flow using OpenAI's auth server.
 * Uses Authorization Code flow with PKCE (public client).
 *
 * The redirect URI points to a localhost callback server that this class
 * spins up on port 1455. The browser redirects to http://localhost:1455/auth/callback
 * after the user authenticates, and we parse the code + state from the query string.
 */
object ChatGPTOAuth {

    private const val TAG = "ChatGPTOAuth"

    // OpenAI OAuth endpoints
    private const val AUTH_URL = "https://auth.openai.com/oauth/authorize"
    private const val TOKEN_URL = "https://auth.openai.com/oauth/token"

    // Client configuration - this is a public client (PKCE, no secret)
    // App developers can override by setting CHATGPT_OAUTH_CLIENT_ID in gradle.properties
    val CLIENT_ID: String = BuildConfig.CHATGPT_OAUTH_CLIENT_ID

    // Redirect URI -- localhost callback server on port 1455 (matches Codex public client ID)
    const val REDIRECT_URI = "http://localhost:1455/auth/callback"
    private const val CALLBACK_PORT = 1455

    // Scopes -- offline_access is required to receive a refresh token
    private const val SCOPE = "openid profile email offline_access"

    // Default model for ChatGPT OAuth users
    const val DEFAULT_MODEL = "gpt-5-mini"

    /** Timeout for the localhost callback server (60 seconds). */
    private const val CALLBACK_TIMEOUT_MS = 60_000L

    /**
     * Generate PKCE code verifier (cryptographically random 43-128 char string).
     */
    fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /**
     * Generate PKCE code challenge from verifier using S256 method.
     */
    fun generateCodeChallenge(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /**
     * Generate a random state parameter for CSRF protection.
     */
    fun generateState(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /**
     * Build the full authorization URL to open in a browser.
     */
    fun buildAuthorizationUrl(codeChallenge: String, state: String): String {
        return buildString {
            append(AUTH_URL)
            append("?response_type=code")
            append("&client_id=").append(URLEncoder.encode(CLIENT_ID, "UTF-8"))
            append("&redirect_uri=").append(URLEncoder.encode(REDIRECT_URI, "UTF-8"))
            append("&code_challenge=").append(URLEncoder.encode(codeChallenge, "UTF-8"))
            append("&code_challenge_method=S256")
            append("&state=").append(URLEncoder.encode(state, "UTF-8"))
            append("&scope=").append(URLEncoder.encode(SCOPE, "UTF-8"))
        }
    }

    /**
     * Start a one-shot HTTP server on [CALLBACK_PORT] that waits for the OAuth redirect.
     *
     * The browser will redirect to `http://localhost:1455/auth/callback?code=...&state=...`
     * after the user authenticates. This function:
     * 1. Accepts a single TCP connection
     * 2. Reads the HTTP GET request line
     * 3. Extracts `code` and `state` from the query string
     * 4. Responds with an HTML page telling the user to return to the app
     * 5. Closes the server socket and returns the result
     *
     * Returns null if the server times out or encounters an error.
     */
    suspend fun startCallbackServer(): OAuthCallbackResult? = withContext(Dispatchers.IO) {
        var serverSocket: ServerSocket? = null
        try {
            serverSocket = ServerSocket(CALLBACK_PORT)
            serverSocket.soTimeout = CALLBACK_TIMEOUT_MS.toInt()

            val result = withTimeoutOrNull(CALLBACK_TIMEOUT_MS) {
                val socket = serverSocket.accept()
                socket.use { client ->
                    val reader = BufferedReader(InputStreamReader(client.getInputStream()))

                    // Read the HTTP request line: "GET /auth/callback?code=...&state=... HTTP/1.1"
                    val requestLine = reader.readLine() ?: return@use null

                    Log.d(TAG, "Callback request: $requestLine")

                    // Parse query params from the request path
                    val pathAndQuery = requestLine.split(" ").getOrNull(1) ?: return@use null
                    val queryString = pathAndQuery.substringAfter("?", "")
                    val params = parseQueryString(queryString)

                    val code = params["code"]
                    val state = params["state"]
                    val error = params["error"]

                    // Build the HTML response
                    val htmlBody = if (code != null) {
                        "<html><body style=\"font-family:sans-serif;text-align:center;padding:40px;\">" +
                            "<h2>Authentication complete!</h2>" +
                            "<p>You can return to Resolve.</p>" +
                            "</body></html>"
                    } else {
                        val errorDesc = params["error_description"] ?: error ?: "Unknown error"
                        "<html><body style=\"font-family:sans-serif;text-align:center;padding:40px;\">" +
                            "<h2>Authentication failed</h2>" +
                            "<p>$errorDesc</p>" +
                            "<p>Please return to Resolve and try again.</p>" +
                            "</body></html>"
                    }

                    val httpResponse = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: text/html; charset=utf-8\r\n")
                        append("Content-Length: ${htmlBody.toByteArray(Charsets.UTF_8).size}\r\n")
                        append("Connection: close\r\n")
                        append("\r\n")
                        append(htmlBody)
                    }

                    client.getOutputStream().use { output ->
                        output.write(httpResponse.toByteArray(Charsets.UTF_8))
                        output.flush()
                    }

                    if (code != null && state != null) {
                        OAuthCallbackResult(code = code, state = state)
                    } else {
                        Log.w(TAG, "Callback missing code or state. error=$error")
                        null
                    }
                }
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "Callback server error", e)
            null
        } finally {
            try {
                serverSocket?.close()
            } catch (_: Exception) {
                // Ignore close errors
            }
        }
    }

    /**
     * Parse a URL query string into a map of key-value pairs.
     */
    private fun parseQueryString(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) {
                java.net.URLDecoder.decode(parts[0], "UTF-8") to
                    java.net.URLDecoder.decode(parts[1], "UTF-8")
            } else null
        }.toMap()
    }

    /**
     * Exchange an authorization code for access + refresh tokens.
     * Must be called on a background thread.
     */
    suspend fun exchangeCodeForTokens(
        code: String,
        codeVerifier: String,
    ): OAuthTokenResponse = withContext(Dispatchers.IO) {
        val body = buildString {
            append("grant_type=authorization_code")
            append("&code=").append(URLEncoder.encode(code, "UTF-8"))
            append("&redirect_uri=").append(URLEncoder.encode(REDIRECT_URI, "UTF-8"))
            append("&client_id=").append(URLEncoder.encode(CLIENT_ID, "UTF-8"))
            append("&code_verifier=").append(URLEncoder.encode(codeVerifier, "UTF-8"))
        }

        val response = httpPost(TOKEN_URL, body, mapOf(
            "Content-Type" to "application/x-www-form-urlencoded",
        ))

        parseTokenResponse(response)
    }

    /**
     * Refresh an expired access token using a refresh token.
     * Must be called on a background thread.
     */
    suspend fun refreshAccessToken(refreshToken: String): OAuthTokenResponse = withContext(Dispatchers.IO) {
        val body = buildString {
            append("grant_type=refresh_token")
            append("&refresh_token=").append(URLEncoder.encode(refreshToken, "UTF-8"))
            append("&client_id=").append(URLEncoder.encode(CLIENT_ID, "UTF-8"))
        }

        val response = httpPost(TOKEN_URL, body, mapOf(
            "Content-Type" to "application/x-www-form-urlencoded",
        ))

        parseTokenResponse(response)
    }

    private fun parseTokenResponse(json: JSONObject): OAuthTokenResponse {
        if (json.has("error")) {
            val error = json.getString("error")
            val desc = json.optString("error_description", "")
            throw OAuthException("$error: $desc")
        }

        return OAuthTokenResponse(
            accessToken = json.getString("access_token"),
            refreshToken = if (json.has("refresh_token")) json.getString("refresh_token") else null,
            expiresIn = json.optLong("expires_in", 3600),
            tokenType = json.optString("token_type", "Bearer"),
        )
    }

    private fun httpPost(url: String, body: String, headers: Map<String, String>): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.doInput = true
            connection.doOutput = true

            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }

            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it)).use { reader -> reader.readText() }
            } ?: ""

            if (code !in 200..299) {
                Log.e(TAG, "OAuth HTTP $code: ${text.take(500)}")
                // Try to parse as JSON error
                try {
                    return JSONObject(text)
                } catch (_: Exception) {
                    throw OAuthException("HTTP $code: ${text.take(200)}")
                }
            }

            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }
}

/** Result from the localhost OAuth callback server. */
data class OAuthCallbackResult(
    val code: String,
    val state: String,
)

data class OAuthTokenResponse(
    val accessToken: String,
    val refreshToken: String?,
    val expiresIn: Long,
    val tokenType: String,
)

class OAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)
