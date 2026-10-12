package com.nawslk.app.calls

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Device-local demo and WebRTC call metadata. No audio or credentials are stored. */
class CallHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("demo_call_history", Context.MODE_PRIVATE)

    fun read(): List<CallSession> = try {
        val items = JSONArray(prefs.getString("sessions", "[]"))
        (0 until items.length()).map { index ->
            val o = items.getJSONObject(index)
            CallSession(
                id = o.getString("id"),
                peer = o.getString("peer"),
                direction = CallDirection.valueOf(o.getString("direction")),
                transport = CallTransport.valueOf(o.getString("transport")),
                status = CallStatus.valueOf(o.getString("status")),
                startedAtMs = o.getLong("startedAtMs"),
                endedAtMs = if (o.has("endedAtMs")) o.getLong("endedAtMs") else null,
                durationMs = o.optLong("durationMs"),
                failureReason = if(o.has("failureReason")) o.getString("failureReason") else null
            )
        }
    } catch (_: Exception) { emptyList() }

    fun save(items: List<CallSession>) {
        val arr = JSONArray()
        items.take(100).forEach { session ->
            val o = JSONObject()
                .put("id", session.id)
                .put("peer", session.peer)
                .put("direction", session.direction.name)
                .put("transport", session.transport.name)
                .put("status", session.status.name)
                .put("startedAtMs", session.startedAtMs)
                .put("durationMs",session.durationMs)
            session.failureReason?.let { o.put("failureReason",it) }
            session.endedAtMs?.let { o.put("endedAtMs", it) }
            arr.put(o)
        }
        prefs.edit().putString("sessions", arr.toString()).apply()
    }
}
