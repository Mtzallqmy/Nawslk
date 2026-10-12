package com.nawslk.app.calls

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Authenticated HTTP signaling transport. Media transport is a separate WebRTC component.
 * Server currently supports only registered participants.
 */
class SignalingClient(
    private val tokenProvider: () -> String?,
    private val baseUrl: String = "https://nawslk-api-production.up.railway.app"
) {
    init { require(baseUrl.startsWith("https://")) }
    suspend fun request(method: String, path: String, body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val token = tokenProvider()
            if (path != "/v1/devices/redeem") requireNotNull(token) { "Device not connected to server" }
            val connection = URL("$baseUrl$path").openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.requestMethod = method
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            try {
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                if (connection.responseCode !in 200..299) {
                    error("Signaling request failed (HTTP ${connection.responseCode})")
                }
                JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            } finally {
                connection.disconnect()
            }
        }

    suspend fun createSession(peerId: String): JSONObject =
        request("POST", "/v1/calls/sessions", JSONObject().put("peerId", peerId))

    suspend fun session(id: String): JSONObject = request("GET", "/v1/calls/sessions/$id")

    suspend fun sendSignal(id: String, kind: String, payload: JSONObject): JSONObject =
        request("POST", "/v1/calls/sessions/$id/signals",
            JSONObject().put("kind", kind).put("payload", payload).put("messageId", java.util.UUID.randomUUID().toString()))

    suspend fun signals(id: String, after: Long = 0): JSONObject =
        request("GET", "/v1/calls/sessions/$id/signals?after=$after")

    suspend fun end(id: String): JSONObject = request("DELETE", "/v1/calls/sessions/$id")
}
