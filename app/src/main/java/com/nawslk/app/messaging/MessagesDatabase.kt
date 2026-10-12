package com.nawslk.app.messaging

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName="conversations",indices=[Index(value=["accountKey","createdAtMs"])])
data class ConversationEntity(
    @PrimaryKey val id: String,
    val accountKey: String,
    val peerE164: String,
    val label: String,
    val mode: String = "DEMO",
    val consent: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val draftText: String = ""
)
@Entity(tableName="messages",foreignKeys=[ForeignKey(entity=ConversationEntity::class,parentColumns=["id"],childColumns=["conversationId"],onDelete=ForeignKey.CASCADE)],indices=[Index("conversationId"),Index(value=["accountKey","idempotencyKey"],unique=true)])
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val accountKey: String,
    val idempotencyKey: String,
    val body: String,
    val status: String,
    val mode: String = "DEMO",
    val direction: String = "OUTGOING",
    val createdAtMs: Long,
    val providerReference: String? = null,
    @ColumnInfo(defaultValue="NULL") val failureCode: String? = null,
    @ColumnInfo(defaultValue="0") val synced: Boolean = false
)
@Dao
interface MessagesDao {
    @Query("SELECT * FROM conversations WHERE accountKey=:account ORDER BY updatedAtMs DESC,id LIMIT 100") fun conversations(account: String): Flow<List<ConversationEntity>>
    @Query("SELECT * FROM conversations WHERE accountKey=:account ORDER BY createdAtMs,id") suspend fun allConversations(account: String): List<ConversationEntity>
    @Query("SELECT * FROM conversations WHERE id=:id AND accountKey=:account") suspend fun conversation(id: String,account: String): ConversationEntity?
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun insertConversation(value: ConversationEntity): Long
    @Query("UPDATE conversations SET draftText=:body,updatedAtMs=:now WHERE id=:id AND accountKey=:account") suspend fun draft(id: String,account: String,body: String,now: Long)
    @Query("SELECT * FROM messages WHERE conversationId=:id AND accountKey=:account ORDER BY createdAtMs DESC,id DESC LIMIT :limit") fun messages(id: String,account: String,limit: Int): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE accountKey=:account AND synced=0 AND status IN ('QUEUED','SENDING','FAILED') ORDER BY createdAtMs,id LIMIT 50") suspend fun pending(account: String): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE id=:id AND accountKey=:account") suspend fun message(id: String,account: String): MessageEntity?
    @Query("SELECT * FROM messages WHERE accountKey=:account AND idempotencyKey=:key") suspend fun byKey(account: String,key: String): MessageEntity?
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun insertMessage(value: MessageEntity): Long
    @Update suspend fun update(value: MessageEntity)
    @Query("DELETE FROM conversations WHERE id=:id AND accountKey=:account") suspend fun deleteConversation(id: String,account: String)
}
@Database(entities=[ConversationEntity::class,MessageEntity::class],version=2,exportSchema=true)
abstract class MessagesDatabase : RoomDatabase() {
    abstract fun dao(): MessagesDao
    companion object {
        val MIGRATION_1_2=object : Migration(1,2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN failureCode TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN synced INTEGER NOT NULL DEFAULT 0")
            }
        }
        @Volatile private var instance: MessagesDatabase? = null
        fun get(context: Context): MessagesDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext,MessagesDatabase::class.java,"nawslk_messages.db").addMigrations(MIGRATION_1_2).build().also { instance=it }
        }
    }
}
