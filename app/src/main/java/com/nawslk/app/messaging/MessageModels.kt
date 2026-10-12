package com.nawslk.app.messaging

enum class MessageStatus { DRAFT, QUEUED, SENDING, SENT, DELIVERED, FAILED, RECEIVED }
enum class NumberStatus { AVAILABLE, RESERVED, ACTIVE, SUSPENDED, EXPIRED, RELEASED }
enum class NumberCapability { SMS, VOICE }
data class VirtualNumber(val id: String,val e164: String,val status: NumberStatus,val capabilities: Set<NumberCapability>,val providerId: String,val expiresAtMs: Long?,val sourceUrl: String)
data class NumberAssignment(val id: String,val numberId: String,val ownerId: String,val expiresAtMs: Long?)
data class ProviderAccount(val id: String,val providerId: String,val enabled: Boolean,val licensed: Boolean)
data class NumberEvent(val id: String,val numberId: String,val from: NumberStatus,val to: NumberStatus,val atMs: Long)
data class MessageAttachmentMetadata(val id: String,val mimeType: String,val sizeBytes: Long)
data class DeliveryEvent(val id: String,val messageId: String,val status: MessageStatus,val atMs: Long)
data class ProviderMessageReference(val providerId: String,val externalId: String,val simulated: Boolean)

object MessageRules {
    fun validDestination(value: String) = Regex("^\\+[1-9][0-9]{7,14}$").matches(value)
    fun validBody(value: String) = value.isNotBlank() && value.codePointCount(0,value.length)<=1600 && value.none { it.code in 0..8 || it.code in 11..12 || it.code in 14..31 }
    fun transition(from: MessageStatus,to: MessageStatus): MessageStatus {
        val allowed=when(from) {
            MessageStatus.DRAFT -> setOf(MessageStatus.QUEUED)
            MessageStatus.QUEUED -> setOf(MessageStatus.SENDING,MessageStatus.FAILED)
            MessageStatus.SENDING -> setOf(MessageStatus.SENT,MessageStatus.FAILED)
            MessageStatus.SENT -> setOf(MessageStatus.DELIVERED,MessageStatus.FAILED)
            MessageStatus.FAILED -> setOf(MessageStatus.QUEUED)
            else -> emptySet()
        }
        require(from==to || to in allowed)
        return to
    }
    fun transition(from: NumberStatus,to: NumberStatus): NumberStatus {
        val allowed=when(from) {
            NumberStatus.AVAILABLE -> setOf(NumberStatus.RESERVED,NumberStatus.RELEASED)
            NumberStatus.RESERVED -> setOf(NumberStatus.ACTIVE,NumberStatus.AVAILABLE,NumberStatus.EXPIRED,NumberStatus.RELEASED)
            NumberStatus.ACTIVE -> setOf(NumberStatus.SUSPENDED,NumberStatus.EXPIRED,NumberStatus.RELEASED)
            NumberStatus.SUSPENDED -> setOf(NumberStatus.ACTIVE,NumberStatus.EXPIRED,NumberStatus.RELEASED)
            NumberStatus.EXPIRED -> setOf(NumberStatus.RELEASED)
            NumberStatus.RELEASED -> emptySet()
        }
        require(from==to || to in allowed);return to
    }
}
interface MessageProvider { suspend fun send(message: MessageEntity): ProviderMessageReference }
class LocalMockMessageProvider : MessageProvider {
    override suspend fun send(message: MessageEntity) = ProviderMessageReference("local-demo","local-demo:${message.id}",true)
}
class LicensedMessageAdapter : MessageProvider {
    override suspend fun send(message: MessageEntity): ProviderMessageReference = error("Licensed SMS provider is not configured")
}
interface NumberProvider { suspend fun numbers(): List<VirtualNumber> }
class LocalMockNumberProvider : NumberProvider { override suspend fun numbers() = emptyList<VirtualNumber>() }
