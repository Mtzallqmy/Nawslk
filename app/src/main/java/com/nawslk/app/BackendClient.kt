package com.nawslk.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal Railway API client. Network operations run on Dispatchers.IO.
 * Owner bootstrap requires a separate out-of-band secret and is intentionally not automatic.
 */
class BackendClient(private val baseUrl: String = "https://nawslk-api-production.up.railway.app") {
    init { require(baseUrl.startsWith("https://")) }
    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        val connection = URL("$baseUrl/health").openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 7000
        connection.readTimeout = 7000
        connection.requestMethod = "GET"
        try {
            connection.responseCode == 200 &&
                JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optString("status") == "ok"
        } finally {
            connection.disconnect()
        }
    }

    suspend fun ownerProfile(token: String): String = withContext(Dispatchers.IO) {
        require(token.length >= 32)
        val connection = URL("$baseUrl/v1/me").openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 7000
        connection.readTimeout = 7000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Authorization", "Bearer $token")
        try {
            if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getString("email")
        } finally {
            connection.disconnect()
        }
    }

    suspend fun bootstrap(email: String, secret: String): String = withContext(Dispatchers.IO) {
        require(secret.length >= 32)
        val connection = URL("$baseUrl/v1/bootstrap").openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 7000
        connection.readTimeout = 7000
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("X-Bootstrap-Secret", secret)
        try {
            connection.outputStream.use {
                it.write(JSONObject().put("email", email).toString().toByteArray(Charsets.UTF_8))
            }
            if (connection.responseCode != 201) error("Bootstrap failed (HTTP ${connection.responseCode})")
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getString("token")
        } finally {
            connection.disconnect()
        }
    }
}
