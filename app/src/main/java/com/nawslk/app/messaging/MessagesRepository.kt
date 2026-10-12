package com.nawslk.app.messaging

import androidx.room.withTransaction
import com.nawslk.app.calls.SignalingClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

/** Offline-first drafts/history; real SMS is guarded independently from demo synchronization. */
class MessagesRepository(private val db: MessagesDatabase,val accountKey: String,private val api: SignalingClient?) {
    val conversations=db.dao().conversations(accountKey)
    private val syncLock=Mutex()
    private val draftLock=Mutex()
    fun messages(conversationId: String,limit: Int=100)=db.dao().messages(conversationId,accountKey,limit.coerceIn(1,1000))
    suspend fun conversation(id: String)=db.dao().conversation(id,accountKey)
    suspend fun create(peer: String,label: String,consent: Boolean): ConversationEntity {
        require(MessageRules.validDestination(peer) && label.length<=80 && consent)
        val now=System.currentTimeMillis()
        val entity=ConversationEntity(UUID.randomUUID().toString(),accountKey,peer,label.trim().ifEmpty {peer},consent=consent,createdAtMs=now,updatedAtMs=now)
        db.dao().insertConversation(entity);return entity
    }
    suspend fun draft(id: String,body: String) = draftLock.withLock {
        require(body.codePointCount(0,body.length)<=1600)
        db.dao().draft(id,accountKey,body,System.currentTimeMillis())
    }
    suspend fun queue(id: String,body: String,key: String=UUID.randomUUID().toString()): MessageEntity = draftLock.withLock { db.withTransaction {
        require(MessageRules.validBody(body));UUID.fromString(key)
        val conversation=checkNotNull(db.dao().conversation(id,accountKey))
        check(conversation.consent && conversation.mode=="DEMO")
        db.dao().byKey(accountKey,key)?.let { require(it.body==body && it.conversationId==id);return@withTransaction it }
        val message=MessageEntity(UUID.randomUUID().toString(),id,accountKey,key,body,MessageRules.transition(MessageStatus.DRAFT,MessageStatus.QUEUED).name,createdAtMs=System.currentTimeMillis())
        db.dao().insertMessage(message)
        db.dao().draft(id,accountKey,"",System.currentTimeMillis())
        message
    } }
    suspend fun retry(id: String) {
        val message=checkNotNull(db.dao().message(id,accountKey))
        check(message.status==MessageStatus.FAILED.name)
        db.dao().update(message.copy(status=MessageStatus.QUEUED.name,failureCode=null))
    }
    suspend fun sync(): Int = syncLock.withLock {
        val client=api ?: return@withLock 0
        // A local namespace is never silently submitted under a newly linked account.
        val profile=client.request("GET","/v1/me")
        check(profile.getString("ownerId")==accountKey) { "Account scope differs" }
        var completed=0
        for(conversation in db.dao().allConversations(accountKey)) {
            client.request("POST","/v1/conversations",JSONObject().put("id",conversation.id).put("peerE164",conversation.peerE164).put("label",conversation.label).put("mode",conversation.mode).put("consent",conversation.consent))
        }
        for(message in db.dao().pending(accountKey)) {
            // Failed sends require explicit retry; process-death SENDING reuses the same idempotency key.
            if(message.status==MessageStatus.FAILED.name) continue
            val sending=message.copy(status=MessageStatus.SENDING.name,failureCode=null)
            db.dao().update(sending)
            try {
                val receipt=client.request("POST","/v1/conversations/${message.conversationId}/messages",JSONObject().put("id",message.id).put("idempotencyKey",message.idempotencyKey).put("body",message.body))
                check(receipt.getString("mode")=="DEMO" && receipt.getBoolean("simulated"))
                val status=MessageStatus.valueOf(receipt.getString("status"))
                MessageRules.transition(MessageStatus.SENDING,status)
                db.dao().update(sending.copy(status=status.name,providerReference=receipt.optString("provider_reference"),synced=true))
                completed++
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch(_:Exception) { db.dao().update(sending.copy(status=MessageStatus.FAILED.name,failureCode="SYNC_UNAVAILABLE")) }
        }
        pull(client)
        completed
    }
    private suspend fun pull(client: SignalingClient) {
        var before="0"
        var pages=0
        do {
            val response=client.request("GET","/v1/conversations?before=$before")
            val list=response.getJSONArray("conversations")
            for(i in 0 until list.length()) {
                val o=list.getJSONObject(i)
                val conversation=ConversationEntity(o.getString("id"),accountKey,o.getString("peer_e164"),o.getString("display_label"),o.getString("mode"),o.getBoolean("consent"),java.time.Instant.parse(o.getString("created_at")).toEpochMilli(),java.time.Instant.parse(o.getString("updated_at")).toEpochMilli())
                db.dao().insertConversation(conversation)
                var after="0"
                var messagePages=0
                do {
                    val messages=client.request("GET","/v1/conversations/${conversation.id}/messages?after=$after")
                    val values=messages.getJSONArray("messages")
                    for(j in 0 until values.length()) {
                        val value=values.getJSONObject(j)
                        val remote=MessageEntity(value.getString("id"),conversation.id,accountKey,value.getString("idempotency_key"),value.getString("body"),value.getString("status"),value.getString("mode"),value.getString("direction"),java.time.Instant.parse(value.getString("created_at")).toEpochMilli(),if(value.isNull("provider_reference")) null else value.getString("provider_reference"),if(value.isNull("failure_code")) null else value.getString("failure_code"),true)
                        val local=db.dao().message(remote.id,accountKey)
                        if(local==null) db.dao().insertMessage(remote)
                        else if(local.synced || local.idempotencyKey==remote.idempotencyKey) db.dao().update(remote)
                    }
                    after=messages.optString("nextCursor","null")
                } while(after!="null" && ++messagePages<100)
            }
            before=response.optString("nextCursor","null")
        } while(before!="null" && ++pages<20)
    }
    suspend fun delete(id: String) {
        // Local offline history can be removed without claiming remote erasure.
        db.dao().deleteConversation(id,accountKey)
    }
}
