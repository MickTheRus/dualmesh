package io.dualmesh.app.history

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.dualmesh.app.model.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageHistoryStoreInstrumentedTest {
    private val databaseName = "test_message_delivery.db"
    private lateinit var context: Context
    @Before fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)
    }
    @After fun cleanup() { context.deleteDatabase(databaseName) }
    private fun sample() = UnifiedMessage("42", MeshNetwork.MESHTASTIC, "channel:0", "Alice", "hello", 1000, false, DeliveryState.RECEIVED,
        deviceId = "radio-a", hopsAway = 2, viaMqtt = true, snr = -5.25f, rssi = -105, receptionPath = "Flood", channelIndex = 3)

    @Test fun receiveMetadataAndDeliveryUpdatesSurviveReopenWithoutExtraRows() = runBlocking {
        val message = sample()
        MessageHistoryStore(context, databaseName).use { store ->
            assertTrue(store.save(message))
            assertTrue(store.save(message.copy(deviceId = "radio-b", viaMqtt = false)))
            val sent = message.copy(id = "99", outgoing = true, deliveryState = DeliveryState.SENT_TO_RADIO)
            store.save(sent)
            assertFalse(store.save(sent.copy(deliveryState = DeliveryState.DELIVERED, deliveryDetail = "Recipient ACK")))
        }
        MessageHistoryStore(context, databaseName).use { store ->
            val rows = store.loadMessages()
            assertEquals(3, rows.size)
            assertEquals(message, rows.single { it.deviceId == "radio-a" && !it.outgoing })
            assertEquals(false, rows.single { it.deviceId == "radio-b" }.viaMqtt)
            assertEquals(DeliveryState.DELIVERED, rows.single { it.outgoing }.deliveryState)
            assertEquals("Recipient ACK", rows.single { it.outgoing }.deliveryDetail)
        }
    }

    @Test fun migrationsFromEveryPreviousVersionPreserveMessagesAndUnknownMetadata() = runBlocking {
        for (version in 1..4) {
            context.deleteDatabase(databaseName)
            context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { db ->
                val deviceColumns = if (version >= 2) "device_id TEXT NOT NULL, device_name TEXT," else ""
                val keyColumns = if (version >= 2) "network, device_id, message_id" else "network, message_id"
                val reactionColumns = if (version >= 3) ", reaction_to_message_id TEXT, reaction_emoji TEXT" else ""
                val metadataColumns = if (version >= 4) ", hops_away INTEGER, via_mqtt INTEGER, snr REAL, rssi INTEGER, reception_path TEXT, delivery_detail TEXT" else ""
                db.execSQL("CREATE TABLE messages (network TEXT NOT NULL, $deviceColumns message_id TEXT NOT NULL, conversation_id TEXT NOT NULL, sender TEXT NOT NULL, body TEXT NOT NULL, timestamp_millis INTEGER NOT NULL, outgoing INTEGER NOT NULL, delivery_state TEXT NOT NULL $reactionColumns $metadataColumns, PRIMARY KEY ($keyColumns))")
                val deviceInsert = if (version >= 2) ", device_id" else ""
                val deviceValue = if (version >= 2) ", 'radio-a'" else ""
                db.execSQL("INSERT INTO messages (network, message_id, conversation_id, sender, body, timestamp_millis, outgoing, delivery_state $deviceInsert) VALUES ('MESHTASTIC', '42', 'channel:0', 'Alice', 'preserve me', 1000, 0, 'RECEIVED' $deviceValue)")
                db.version = version
            }
            MessageHistoryStore(context, databaseName).use { store ->
                val row = store.loadMessages().single()
                assertEquals("preserve me", row.text)
                assertNull(row.hopsAway)
                assertNull(row.viaMqtt)
                assertNull(row.channelIndex)
                assertEquals(DeliveryState.RECEIVED, row.deliveryState)
                store.save(row.copy(hopsAway = 0, viaMqtt = false))
                assertEquals(0, store.loadMessages().single().hopsAway)
            }
        }
    }

    @Test fun restartMarksInterruptedConfirmationsUnconfirmedButKeepsUnacknowledgedChannelSend() = runBlocking {
        MessageHistoryStore(context, databaseName).use { store ->
            store.save(sample().copy(outgoing = true, deliveryState = DeliveryState.SENT_TO_RADIO))
            store.save(sample().copy(id = "43", outgoing = true, deliveryState = DeliveryState.SENT))
            store.save(sample().copy(id = "44", network = MeshNetwork.MESHCORE, outgoing = true, deliveryState = DeliveryState.SENT))
        }
        MessageHistoryStore(context, databaseName).use { store ->
            val rows = store.loadMessages()
            assertEquals(DeliveryState.UNCONFIRMED, rows.single { it.id == "42" }.deliveryState)
            assertEquals(DeliveryState.UNCONFIRMED, rows.single { it.id == "43" }.deliveryState)
            assertEquals(DeliveryState.SENT, rows.single { it.id == "44" }.deliveryState)
        }
    }
}
