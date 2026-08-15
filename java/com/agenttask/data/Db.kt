package com.agenttask.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val providerId: String? = null,
    val model: String? = null
)

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = ChatEntity::class, parentColumns = ["id"],
        childColumns = ["chatId"], onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("chatId")]
)
data class MsgEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: String,
    val role: String,                       // system | user | assistant | tool
    val content: String,
    val imagesJson: String = "[]",
    val toolCallsJson: String = "[]",
    val toolCallId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY updatedAt DESC")
    fun chats(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun chat(id: String): ChatEntity?

    @Upsert suspend fun upsert(chat: ChatEntity)

    @Query("DELETE FROM chats WHERE id = :id") suspend fun delete(id: String)

    @Query("UPDATE chats SET title = :title, updatedAt = :ts WHERE id = :id")
    suspend fun rename(id: String, title: String, ts: Long = System.currentTimeMillis())

    @Query("UPDATE chats SET updatedAt = :ts WHERE id = :id")
    suspend fun touch(id: String, ts: Long = System.currentTimeMillis())

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY id ASC")
    fun messages(chatId: String): Flow<List<MsgEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY id ASC")
    suspend fun messagesOnce(chatId: String): List<MsgEntity>

    @Insert suspend fun insert(msg: MsgEntity): Long

    @Query("UPDATE messages SET content = :content, toolCallsJson = :tools WHERE id = :id")
    suspend fun update(id: Long, content: String, tools: String)

    @Query("DELETE FROM messages WHERE id = :id") suspend fun deleteMsg(id: Long)
}

@Database(entities = [ChatEntity::class, MsgEntity::class], version = 1, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun dao(): ChatDao

    companion object {
        fun open(ctx: Context): Db =
            Room.databaseBuilder(ctx, Db::class.java, "agenttask.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
