package com.nawslk.app.messaging

import android.app.Application
import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26,35],application=Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MessagesRoomTest {
    private val context: Context=RuntimeEnvironment.getApplication()
    private val name="test-messages.db"
    private var database: MessagesDatabase?=null
    private fun open(): MessagesDatabase = Room.databaseBuilder(context,MessagesDatabase::class.java,name)
        .addMigrations(MessagesDatabase.MIGRATION_1_2).allowMainThreadQueries().build().also{database=it}
    @After fun cleanup(){database?.close();context.deleteDatabase(name)}
    @Test(timeout=30000) fun offlineDraftAndQueuedHistorySurviveReopenAndDoNotClaimSent()=runBlocking {
        var db=open();var repository=MessagesRepository(db,"local:owner",null)
        val conversation=repository.create("+967700000001","جهة اتصال",true)
        repository.draft(conversation.id,"مسودة دون اتصال")
        db.close();db=open();repository=MessagesRepository(db,"local:owner",null)
        assertEquals("مسودة دون اتصال",repository.conversation(conversation.id)?.draftText)
        val queued=repository.queue(conversation.id,"رسالة في الانتظار")
        assertEquals(MessageStatus.QUEUED.name,queued.status);assertEquals(0,repository.sync())
        assertEquals(MessageStatus.QUEUED.name,repository.messages(conversation.id).first().single().status)
        assertEquals("",repository.conversation(conversation.id)?.draftText)
    }
    @Test(timeout=30000) fun concurrentQueueUsesOneKeyAndRejectsChangedContent()=runBlocking {
        val db=open();val repository=MessagesRepository(db,"account-a",null)
        val conversation=repository.create("+967700000001","test",true);val key=UUID.randomUUID().toString()
        val results=listOf(async(Dispatchers.IO){repository.queue(conversation.id,"content",key)},async(Dispatchers.IO){repository.queue(conversation.id,"content",key)}).awaitAll()
        val first=results[0];val second=results[1]
        assertEquals(first.id,second.id);assertEquals(1,repository.messages(conversation.id).first().size)
        assertThrows(IllegalArgumentException::class.java){runBlocking{repository.queue(conversation.id,"changed",key)}}
        val other=MessagesRepository(db,"account-b",null)
        assertTrue(other.conversations.first().isEmpty());assertTrue(other.messages(conversation.id).first().isEmpty())
        assertThrows(IllegalStateException::class.java){runBlocking{other.queue(conversation.id,"denied")}}
        repository.delete(conversation.id);assertNull(db.dao().message(first.id,"account-a"))
    }
    @Test(timeout=30000) fun migrationFromOnePreservesConversationMessageAndAddsSyncColumns()=runBlocking {
        val file=context.getDatabasePath(name);file.parentFile?.mkdirs()
        val old=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file,null)
        old.execSQL("CREATE TABLE conversations(id TEXT NOT NULL PRIMARY KEY, accountKey TEXT NOT NULL,peerE164 TEXT NOT NULL,label TEXT NOT NULL,mode TEXT NOT NULL,consent INTEGER NOT NULL,createdAtMs INTEGER NOT NULL,updatedAtMs INTEGER NOT NULL,draftText TEXT NOT NULL)")
        old.execSQL("CREATE INDEX index_conversations_accountKey_createdAtMs ON conversations(accountKey,createdAtMs)")
        old.execSQL("CREATE TABLE messages(id TEXT NOT NULL PRIMARY KEY,conversationId TEXT NOT NULL,accountKey TEXT NOT NULL,idempotencyKey TEXT NOT NULL,body TEXT NOT NULL,status TEXT NOT NULL,mode TEXT NOT NULL,direction TEXT NOT NULL,createdAtMs INTEGER NOT NULL,providerReference TEXT,FOREIGN KEY(conversationId) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        old.execSQL("CREATE INDEX index_messages_conversationId ON messages(conversationId)")
        old.execSQL("CREATE UNIQUE INDEX index_messages_accountKey_idempotencyKey ON messages(accountKey,idempotencyKey)")
        old.execSQL("INSERT INTO conversations VALUES('conversation','account','+967700000001','saved','DEMO',1,1,1,'draft')")
        old.execSQL("INSERT INTO messages VALUES('message','conversation','account','key','queued text','QUEUED','DEMO','OUTGOING',1,NULL)")
        old.version=1;old.close()
        val db=open();val saved=db.dao().message("message","account")
        assertEquals("queued text",saved?.body);assertFalse(saved!!.synced);assertNull(saved.failureCode)
        assertEquals("draft",db.dao().conversation("conversation","account")?.draftText)
        db.dao().deleteConversation("conversation","account");assertNull(db.dao().message("message","account"))
    }
}
