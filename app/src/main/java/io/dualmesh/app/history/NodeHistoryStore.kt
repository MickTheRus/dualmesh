package io.dualmesh.app.history

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.dualmesh.app.model.MeshNetwork
import io.dualmesh.app.model.MeshNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Store node data for each radio session.
 *
 * Store position observations separately from the node data.
 * A trail view can use this history without packet capture files.
 */
class NodeHistoryStore(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE nodes (
                network TEXT NOT NULL,
                device_id TEXT NOT NULL,
                device_name TEXT NOT NULL,
                node_id TEXT NOT NULL,
                name TEXT NOT NULL,
                short_name TEXT,
                public_key TEXT,
                hardware TEXT,
                role TEXT,
                channel INTEGER,
                via_mqtt INTEGER,
                messageable INTEGER,
                flags INTEGER,
                last_heard_millis INTEGER NOT NULL,
                snr REAL,
                rssi INTEGER,
                hops_away INTEGER,
                latitude REAL,
                longitude REAL,
                altitude_meters INTEGER,
                position_time_millis INTEGER,
                precision_bits INTEGER,
                source TEXT NOT NULL,
                PRIMARY KEY (network, device_id, node_id)
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE INDEX index_nodes_radio_name
            ON nodes(network, device_id, name)
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE node_positions (
                network TEXT NOT NULL,
                device_id TEXT NOT NULL,
                node_id TEXT NOT NULL,
                timestamp_millis INTEGER NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                altitude_meters INTEGER,
                precision_bits INTEGER,
                PRIMARY KEY (network, device_id, node_id, timestamp_millis, latitude, longitude)
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE INDEX index_node_positions_time
            ON node_positions(timestamp_millis DESC)
            """.trimIndent(),
        )
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1 && newVersion >= 2) {
            database.execSQL("ALTER TABLE nodes ADD COLUMN channel INTEGER")
            database.execSQL("ALTER TABLE nodes ADD COLUMN via_mqtt INTEGER")
            database.execSQL("ALTER TABLE nodes ADD COLUMN messageable INTEGER")
            database.execSQL("ALTER TABLE nodes ADD COLUMN flags INTEGER")
            return
        }
        error("No node database migration from $oldVersion to $newVersion")
    }

    suspend fun loadNodes(): List<MeshNode> = withContext(Dispatchers.IO) {
        val nodes = mutableListOf<MeshNode>()
        readableDatabase.query(
            "nodes",
            NODE_COLUMNS,
            null,
            null,
            null,
            null,
            "name COLLATE NOCASE ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val network = MeshNetwork.entries.firstOrNull {
                    it.name == cursor.getString(0)
                } ?: continue
                nodes += MeshNode(
                    network = network,
                    deviceId = cursor.getString(1),
                    deviceName = cursor.getString(2),
                    id = cursor.getString(3),
                    name = cursor.getString(4),
                    shortName = cursor.stringOrNull(5),
                    publicKey = cursor.stringOrNull(6),
                    hardware = cursor.stringOrNull(7),
                    role = cursor.stringOrNull(8),
                    channel = cursor.intOrNull(9),
                    viaMqtt = cursor.booleanOrNull(10),
                    messageable = cursor.booleanOrNull(11),
                    flags = cursor.intOrNull(12),
                    lastHeardMillis = cursor.getLong(13),
                    snr = cursor.floatOrNull(14),
                    rssi = cursor.intOrNull(15),
                    hopsAway = cursor.intOrNull(16),
                    latitude = cursor.doubleOrNull(17),
                    longitude = cursor.doubleOrNull(18),
                    altitudeMeters = cursor.intOrNull(19),
                    positionTimeMillis = cursor.longOrNull(20),
                    precisionBits = cursor.intOrNull(21),
                    source = cursor.getString(22),
                )
            }
        }
        nodes
    }

    suspend fun save(node: MeshNode) = saveNodes(listOf(node))

    suspend fun saveNodes(nodes: List<MeshNode>) = withContext(Dispatchers.IO) {
        if (nodes.isEmpty()) return@withContext
        val database = writableDatabase
        database.beginTransaction()
        try {
            nodes.forEach { node ->
                database.insertWithOnConflict(
                    "nodes",
                    null,
                    node.toValues(),
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
                if (node.hasPosition) {
                    database.insertWithOnConflict(
                        "node_positions",
                        null,
                        ContentValues().apply {
                            put("network", node.network.name)
                            put("device_id", node.deviceId)
                            put("node_id", node.id)
                            put(
                                "timestamp_millis",
                                node.positionTimeMillis ?: node.lastHeardMillis
                                    .takeIf { it > 0 } ?: System.currentTimeMillis(),
                            )
                            put("latitude", node.latitude)
                            put("longitude", node.longitude)
                            put("altitude_meters", node.altitudeMeters)
                            put("precision_bits", node.precisionBits)
                        },
                        SQLiteDatabase.CONFLICT_IGNORE,
                    )
                }
            }
            if (nodes.any(MeshNode::hasPosition)) {
                database.execSQL(
                    """
                    DELETE FROM node_positions
                    WHERE rowid NOT IN (
                        SELECT rowid FROM node_positions
                        ORDER BY timestamp_millis DESC, rowid DESC
                        LIMIT $MAX_POSITION_OBSERVATIONS
                    )
                    """.trimIndent(),
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private fun MeshNode.toValues() = ContentValues().apply {
        put("network", network.name)
        put("device_id", deviceId)
        put("device_name", deviceName)
        put("node_id", id)
        put("name", name)
        put("short_name", shortName)
        put("public_key", publicKey)
        put("hardware", hardware)
        put("role", role)
        put("channel", channel)
        put("via_mqtt", viaMqtt?.let { if (it) 1 else 0 })
        put("messageable", messageable?.let { if (it) 1 else 0 })
        put("flags", flags)
        put("last_heard_millis", lastHeardMillis)
        put("snr", snr)
        put("rssi", rssi)
        put("hops_away", hopsAway)
        put("latitude", latitude)
        put("longitude", longitude)
        put("altitude_meters", altitudeMeters)
        put("position_time_millis", positionTimeMillis)
        put("precision_bits", precisionBits)
        put("source", source)
    }

    private fun android.database.Cursor.stringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun android.database.Cursor.intOrNull(index: Int): Int? =
        if (isNull(index)) null else getInt(index)

    private fun android.database.Cursor.booleanOrNull(index: Int): Boolean? =
        if (isNull(index)) null else getInt(index) != 0

    private fun android.database.Cursor.longOrNull(index: Int): Long? =
        if (isNull(index)) null else getLong(index)

    private fun android.database.Cursor.floatOrNull(index: Int): Float? =
        if (isNull(index)) null else getFloat(index)

    private fun android.database.Cursor.doubleOrNull(index: Int): Double? =
        if (isNull(index)) null else getDouble(index)

    companion object {
        private const val DATABASE_NAME = "dualmesh_nodes.db"
        private const val DATABASE_VERSION = 2
        private const val MAX_POSITION_OBSERVATIONS = 20_000
        private val NODE_COLUMNS = arrayOf(
            "network",
            "device_id",
            "device_name",
            "node_id",
            "name",
            "short_name",
            "public_key",
            "hardware",
            "role",
            "channel",
            "via_mqtt",
            "messageable",
            "flags",
            "last_heard_millis",
            "snr",
            "rssi",
            "hops_away",
            "latitude",
            "longitude",
            "altitude_meters",
            "position_time_millis",
            "precision_bits",
            "source",
        )
    }
}
