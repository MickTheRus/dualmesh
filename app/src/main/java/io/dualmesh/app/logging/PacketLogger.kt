package io.dualmesh.app.logging

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.content.edit
import io.dualmesh.app.model.MeshNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class PacketDirection {
    RX,
    TX,
}

data class PacketLogEntry(
    val id: Long,
    val network: MeshNetwork,
    val direction: PacketDirection,
    val transport: String,
    val timestampMillis: Long,
    val summary: String,
    val payload: ByteArray,
) {
    fun hex(maxBytes: Int = payload.size): String =
        payload
            .take(maxBytes)
            .joinToString(" ") { byte -> "%02x".format(byte.toInt() and 0xff) }
            .let { rendered ->
                if (payload.size > maxBytes) "$rendered … (+${payload.size - maxBytes} bytes)"
                else rendered
            }

    fun ascii(maxBytes: Int = payload.size): String =
        payload
            .take(maxBytes)
            .joinToString("") { byte ->
                val value = byte.toInt() and 0xff
                if (value in 32..126) value.toChar().toString() else "."
            }
}

/**
 * Capture protocol frames only when the user enables packet logging.
 * Store the frames in private app data.
 *
 * Capture MeshCore companion frames at the radio API boundary.
 * Capture Meshtastic ToRadio and FromRadio protobuf payloads at the same boundary.
 * Do not capture raw LoRa RF data.
 */
class PacketLogger(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val store = PacketLogStore(appContext)
    private val captureFileWriter = PacketCaptureFileWriter(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val storeMutex = Mutex()

    private val _enabledNetworks = MutableStateFlow<Set<MeshNetwork>>(
        MeshNetwork.entries.filterTo(mutableSetOf()) {
            preferences.getBoolean(enabledKey(it), false)
        },
    )
    val enabledNetworks: StateFlow<Set<MeshNetwork>> = _enabledNetworks.asStateFlow()

    private val _entries = MutableStateFlow<List<PacketLogEntry>>(emptyList())
    val entries: StateFlow<List<PacketLogEntry>> = _entries.asStateFlow()

    private val _activeCapturePath = MutableStateFlow<String?>(null)
    val activeCapturePath: StateFlow<String?> = _activeCapturePath.asStateFlow()

    private val _captureFileError = MutableStateFlow<String?>(null)
    val captureFileError: StateFlow<String?> = _captureFileError.asStateFlow()

    init {
        scope.launch {
            storeMutex.withLock {
                _entries.value = store.loadLatest(VISIBLE_ENTRY_LIMIT)
                if (_enabledNetworks.value.isNotEmpty()) ensureCaptureFile()
            }
        }
    }

    fun isEnabled(network: MeshNetwork): Boolean = network in _enabledNetworks.value

    fun setEnabled(network: MeshNetwork, enabled: Boolean) {
        val wasEmpty = _enabledNetworks.value.isEmpty()
        _enabledNetworks.value = if (enabled) {
            _enabledNetworks.value + network
        } else {
            _enabledNetworks.value - network
        }
        preferences.edit { putBoolean(enabledKey(network), enabled) }
        val isEmpty = _enabledNetworks.value.isEmpty()
        if (wasEmpty != isEmpty) {
            scope.launch {
                storeMutex.withLock {
                    if (isEmpty) {
                        captureFileWriter.stop()
                        _activeCapturePath.value = null
                        _captureFileError.value = null
                    } else {
                        ensureCaptureFile()
                    }
                }
            }
        }
    }

    fun log(
        network: MeshNetwork,
        direction: PacketDirection,
        transport: String,
        payload: ByteArray,
        summary: String,
    ) {
        if (!isEnabled(network)) return
        val capturedPayload = payload.copyOf()
        val capturedAt = System.currentTimeMillis()
        scope.launch {
            storeMutex.withLock {
                val entry = PacketLogEntry(
                    id = store.save(
                        network = network,
                        direction = direction,
                        transport = transport,
                        timestampMillis = capturedAt,
                        summary = summary,
                        payload = capturedPayload,
                    ),
                    network = network,
                    direction = direction,
                    transport = transport,
                    timestampMillis = capturedAt,
                    summary = summary,
                    payload = capturedPayload,
                )
                _entries.value = (listOf(entry) + _entries.value)
                    .take(VISIBLE_ENTRY_LIMIT)
                if (ensureCaptureFile()) {
                    runCatching { captureFileWriter.append(entry) }
                        .onFailure {
                            _captureFileError.value =
                                it.message ?: "Unable to append packet capture file"
                        }
                }
            }
        }
    }

    fun clear(network: MeshNetwork? = null) {
        scope.launch {
            storeMutex.withLock {
                store.clear(network)
                _entries.value = if (network == null) {
                    emptyList()
                } else {
                    _entries.value.filterNot { it.network == network }
                }
            }
        }
    }

    fun close() {
        scope.cancel()
        captureFileWriter.stop()
        store.close()
    }

    private fun ensureCaptureFile(): Boolean {
        if (_activeCapturePath.value != null) return true
        return runCatching { captureFileWriter.start() }
            .onSuccess {
                _activeCapturePath.value = it
                _captureFileError.value = null
            }
            .onFailure {
                _captureFileError.value = it.message ?: "Unable to create packet capture file"
            }
            .isSuccess
    }

    private fun enabledKey(network: MeshNetwork): String =
        "enabled_${network.name.lowercase()}"

    companion object {
        private const val PREFERENCES_NAME = "packet_logger"
        private const val VISIBLE_ENTRY_LIMIT = 1_000
    }
}

private class PacketLogStore(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE packet_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                network TEXT NOT NULL,
                direction TEXT NOT NULL,
                transport TEXT NOT NULL,
                timestamp_millis INTEGER NOT NULL,
                summary TEXT NOT NULL,
                payload BLOB NOT NULL
            )
            """.trimIndent(),
        )
        database.execSQL(
            "CREATE INDEX index_packet_log_time ON packet_log(timestamp_millis DESC)",
        )
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("No packet-log database migration from $oldVersion to $newVersion")
    }

    fun save(
        network: MeshNetwork,
        direction: PacketDirection,
        transport: String,
        timestampMillis: Long,
        summary: String,
        payload: ByteArray,
    ): Long {
        val database = writableDatabase
        database.beginTransaction()
        return try {
            val id = database.insertOrThrow(
                "packet_log",
                null,
                ContentValues().apply {
                    put("network", network.name)
                    put("direction", direction.name)
                    put("transport", transport.take(24))
                    put("timestamp_millis", timestampMillis)
                    put("summary", summary.take(240))
                    put("payload", payload)
                },
            )
            database.execSQL(
                """
                DELETE FROM packet_log
                WHERE id NOT IN (
                    SELECT id FROM packet_log
                    ORDER BY timestamp_millis DESC, id DESC
                    LIMIT $MAX_STORED_ENTRIES
                )
                """.trimIndent(),
            )
            database.setTransactionSuccessful()
            id
        } finally {
            database.endTransaction()
        }
    }

    fun loadLatest(limit: Int): List<PacketLogEntry> {
        val entries = mutableListOf<PacketLogEntry>()
        readableDatabase.query(
            "packet_log",
            arrayOf(
                "id",
                "network",
                "direction",
                "transport",
                "timestamp_millis",
                "summary",
                "payload",
            ),
            null,
            null,
            null,
            null,
            "timestamp_millis DESC, id DESC",
            limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val network = MeshNetwork.entries.firstOrNull {
                    it.name == cursor.getString(1)
                } ?: continue
                val direction = PacketDirection.entries.firstOrNull {
                    it.name == cursor.getString(2)
                } ?: continue
                entries += PacketLogEntry(
                    id = cursor.getLong(0),
                    network = network,
                    direction = direction,
                    transport = cursor.getString(3),
                    timestampMillis = cursor.getLong(4),
                    summary = cursor.getString(5),
                    payload = cursor.getBlob(6),
                )
            }
        }
        return entries
    }

    fun clear(network: MeshNetwork?) {
        if (network == null) {
            writableDatabase.delete("packet_log", null, null)
        } else {
            writableDatabase.delete(
                "packet_log",
                "network = ?",
                arrayOf(network.name),
            )
        }
    }

    companion object {
        private const val DATABASE_NAME = "dualmesh_packets.db"
        private const val DATABASE_VERSION = 1
        private const val MAX_STORED_ENTRIES = 20_000
    }
}
