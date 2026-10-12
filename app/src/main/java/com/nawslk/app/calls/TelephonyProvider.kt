package com.nawslk.app.calls

/** A licensed PSTN integration is separate from internal WebRTC device identities. */
interface TelephonyProvider {
    val configured: Boolean
    suspend fun dialE164(number: String): String
    suspend fun end(providerCallId: String)
}
class UnconfiguredTelephonyProvider : TelephonyProvider {
    override val configured = false
    override suspend fun dialE164(number: String): String = error("No licensed telephony provider configured")
    override suspend fun end(providerCallId: String) = error("No licensed telephony provider configured")
}
