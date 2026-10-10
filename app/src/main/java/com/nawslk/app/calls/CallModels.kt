package com.nawslk.app.calls

enum class CallDirection { OUTGOING, INCOMING }
enum class CallStatus { IDLE, RINGING, CONNECTING, ACTIVE, ENDED, FAILED, REJECTED }
enum class CallTransport { DEMO, WEBRTC, PSTN_PROVIDER }

data class CallSession(
    val id: String,
    val peer: String,
    val direction: CallDirection,
    val transport: CallTransport,
    val status: CallStatus,
    val startedAtMs: Long,
    val endedAtMs: Long? = null
)
