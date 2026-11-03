package io.dualmesh.app.history

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.dualmesh.app.model.DeliveryState
import io.dualmesh.app.model.MeshNetwork
import io.dualmesh.app.model.UnifiedMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Store message history in app-owned storage.
 *
 * The app reads radio queues after a reconnect to fill gaps.
 * The composite primary key prevents duplicate messages.
 * The app updates a message that is already in the database.
 */
class MessageHistoryStore(
    context: Context,
    databaseName: String = DATABASE_NAME,
) : SQLiteOpenHelper(context, databaseName, null, DATABASE_VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(database: SQLiteDatabase) {
        createTables(database)
    }

    private fun createTables(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE $TABLE_MESSAGES (
                network TEXT NOT NULL,
                device_id TEXT NOT NULL,
                device_name TEXT,
                message_id TEXT NOT NULL,
                conversation_id TEXT NOT NULL,
                sender TEXT NOT NULL,
                body TEXT NOT NULL,
                timestamp_millis INTEGER NOT NULL,
                outgoing INTEGER NOT NULL,
                delivery_state TEXT NOT NULL,
                reaction_to_message_id TEXT,
                reaction_emoji TEXT,
                hops_away INTEGER,
                via_mqtt INTEGER,
                snr REAL,
                rssi INTEGER,
                reception_path TEXT,
                delivery_detail TEXT,
                PRIMARY KEY (network, device_id, message_id)
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE INDEX index_messages_time
            ON $TABLE_MESSAGES(timestamp_millis)
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE INDEX index_messages_conversation
            ON $TABLE_MESSAGES(network, device_id, conversation_id, timestamp_millis)
            """.trimIndent(),
        )
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1 && newVersion >= 2) {
            database.execSQL("ALTER TABLE $TABLE_MESSAGES RENAME TO messages_v1")
            database.execSQL("DROP INDEX IF EXISTS index_messages_time")
            database.execSQL("DROP INDEX IF EXISTS index_messages_conversation")
            createTables(database)
            database.execSQL(
                """
                INSERT INTO $TABLE_MESSAGES (
                    network, device_id, device_name, message_id, conversation_id, sender, body,
                    timestamp_millis, outgoing, delivery_state
                )
                SELECT
                    network, '', NULL, message_id, conversation_id, sender, body,
                    timestamp_millis, outgoing, delivery_state
                FROM messages_v1
                """.trimIndent(),
            )
            database.execSQL("DROP TABLE messages_v1")
            return
        }
        if (oldVersion == 2 && newVersion >= 3) {
            database.execSQL(
                "ALTER TABLE $TABLE_MESSAGES ADD COLUMN reaction_to_message_id TEXT",
            )
            database.execSQL(
                "ALTER TABLE $TABLE_MESSAGES ADD COLUMN reaction_emoji TEXT",
            )
        }
        if (oldVersion < 4 && newVersion >= 4) {
            listOf("hops_away INTEGER", "via_mqtt INTEGER", "snr REAL", "rssi INTEGER", "reception_path TEXT", "delivery_detail TEXT").forEach {
                database.execSQL("ALTER TABLE $TABLE_MESSAGES ADD COLUMN $it")
            }
        }
        if (oldVersion in 2..3 && newVersion <= DATABASE_VERSION) return
        error("No message database migration from $oldVersion to $newVersion")
    }

    suspend fun loadMessages(limit: Int = MAX_STORED_MESSAGES): List<UnifiedMessage> =
        withContext(Dispatchers.IO) {
            // Pending ACK timers cannot survive process death. Preserve history without claiming delivery.
            writableDatabase.execSQL("UPDATE $TABLE_MESSAGES SET delivery_state = 'UNCONFIRMED', delivery_detail = 'Delivery confirmation was interrupted by an app restart.' WHERE outgoing = 1 AND (delivery_state IN ('SENDING', 'SENT_TO_RADIO') OR (delivery_state = 'SENT' AND (network = 'MESHTASTIC' OR conversation_id LIKE 'contact:%')))")
            val messages = mutableListOf<UnifiedMessage>()
            readableDatabase.query(
                TABLE_MESSAGES,
                COLUMNS,
                null,
                null,
                null,
                null,
                "timestamp_millis ASC, rowid ASC",
                limit.toString(),
            ).use { cursor ->
                val networkColumn = cursor.getColumnIndexOrThrow("network")
                val deviceIdColumn = cursor.getColumnIndexOrThrow("device_id")
                val deviceNameColumn = cursor.getColumnIndexOrThrow("device_name")
                val idColumn = cursor.getColumnIndexOrThrow("message_id")
                val conversationColumn = cursor.getColumnIndexOrThrow("conversation_id")
                val senderColumn = cursor.getColumnIndexOrThrow("sender")
                val bodyColumn = cursor.getColumnIndexOrThrow("body")
                val timestampColumn = cursor.getColumnIndexOrThrow("timestamp_millis")
                val outgoingColumn = cursor.getColumnIndexOrThrow("outgoing")
                val deliveryColumn = cursor.getColumnIndexOrThrow("delivery_state")
                val reactionTargetColumn =
                    cursor.getColumnIndexOrThrow("reaction_to_message_id")
                val reactionEmojiColumn = cursor.getColumnIndexOrThrow("reaction_emoji")

                while (cursor.moveToNext()) {
                    val network = MeshNetwork.entries.firstOrNull {
                        it.name == cursor.getString(networkColumn)
                    } ?: continue
                    val deliveryState = DeliveryState.entries.firstOrNull {
                        it.name == cursor.getString(deliveryColumn)
                    } ?: DeliveryState.ERROR
                    messages += UnifiedMessage(
                        hopsAway = cursor.getColumnIndexOrThrow("hops_away").let { if (cursor.isNull(it)) null else cursor.getInt(it) },
                        viaMqtt = cursor.getColumnIndexOrThrow("via_mqtt").let { if (cursor.isNull(it)) null else cursor.getInt(it) != 0 },
                        snr = cursor.getColumnIndexOrThrow("snr").let { if (cursor.isNull(it)) null else cursor.getFloat(it) },
                        rssi = cursor.getColumnIndexOrThrow("rssi").let { if (cursor.isNull(it)) null else cursor.getInt(it) },
                        receptionPath = cursor.getString(cursor.getColumnIndexOrThrow("reception_path")),
                        deliveryDetail = cursor.getString(cursor.getColumnIndexOrThrow("delivery_detail")),
                        id = cursor.getString(idColumn),
                        network = network,
                        conversationId = cursor.getString(conversationColumn),
                        sender = cursor.getString(senderColumn),
                        text = cursor.getString(bodyColumn),
                        timestampMillis = cursor.getLong(timestampColumn),
                        outgoing = cursor.getInt(outgoingColumn) != 0,
                        deliveryState = deliveryState,
                        deviceId = cursor.getString(deviceIdColumn),
                        deviceName = if (cursor.isNull(deviceNameColumn)) {
                            null
                        } else {
                            cursor.getString(deviceNameColumn)
                        },
                        reactionToMessageId = if (cursor.isNull(reactionTargetColumn)) {
                            null
                        } else {
                            cursor.getString(reactionTargetColumn)
                        },
                        reactionEmoji = if (cursor.isNull(reactionEmojiColumn)) {
                            null
                        } else {
                            cursor.getString(reactionEmojiColumn)
                        },
                    )
                }
            }
            messages
        }

    /**
     * Return true if the message was not in the database.
     */
    suspend fun save(message: UnifiedMessage): Boolean = withContext(Dispatchers.IO) {
        val database = writableDatabase
        val values = message.toContentValues()
        database.beginTransaction()
        try {
            val rowId = database.insertWithOnConflict(
                TABLE_MESSAGES,
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            val inserted = rowId != -1L
            if (!inserted) {
                database.update(
                    TABLE_MESSAGES,
                    values,
                    "network = ? AND device_id = ? AND message_id = ?",
                    arrayOf(message.network.name, message.deviceId, message.id),
                )
            }
            database.execSQL(
                """
                DELETE FROM $TABLE_MESSAGES
                WHERE rowid NOT IN (
                    SELECT rowid FROM $TABLE_MESSAGES
                    ORDER BY timestamp_millis DESC, rowid DESC
                    LIMIT $MAX_STORED_MESSAGES
                )
                """.trimIndent(),
            )
            database.setTransactionSuccessful()
            inserted
        } finally {
            database.endTransaction()
        }
    }

    private fun UnifiedMessage.toContentValues() = ContentValues().apply {
        put("network", network.name)
        put("device_id", deviceId)
        put("device_name", deviceName)
        put("message_id", id)
        put("conversation_id", conversationId)
        put("sender", sender)
        put("body", text)
        put("timestamp_millis", timestampMillis)
        put("outgoing", if (outgoing) 1 else 0)
        put("delivery_state", deliveryState.name)
        put("reaction_to_message_id", reactionToMessageId)
        put("reaction_emoji", reactionEmoji)
        put("hops_away", hopsAway)
        put("via_mqtt", viaMqtt?.let { if (it) 1 else 0 })
        put("snr", snr)
        put("rssi", rssi)
        put("reception_path", receptionPath)
        put("delivery_detail", deliveryDetail)
    }

    companion object {
        const val MAX_STORED_MESSAGES = 5_000
        private const val DATABASE_NAME = "dualmesh_messages.db"
        private const val DATABASE_VERSION = 4
        private const val TABLE_MESSAGES = "messages"
        private val COLUMNS = arrayOf(
            "network",
            "device_id",
            "device_name",
            "message_id",
            "conversation_id",
            "sender",
            "body",
            "timestamp_millis",
            "outgoing",
            "delivery_state",
            "reaction_to_message_id",
            "reaction_emoji",
            "hops_away", "via_mqtt", "snr", "rssi", "reception_path", "delivery_detail",
        )
    }
}
