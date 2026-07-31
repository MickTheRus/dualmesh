package io.dualmesh.app.history

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.dualmesh.app.model.MeshNetwork
import io.dualmesh.app.model.TraceRouteHop
import io.dualmesh.app.model.TraceRouteResult
import io.dualmesh.app.model.TraceRouteState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Store traceroute results for each radio.
 *
 * A run and its hops use the network, radio, and request ID as one key.
 * Pending runs become failed runs after an app restart because the old
 * transport session cannot deliver their responses to the new process.
 */
class TraceRouteHistoryStore(
    context: Context,
    databaseName: String = DATABASE_NAME,
) : SQLiteOpenHelper(context, databaseName, null, DATABASE_VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE $TABLE_RUNS (
                network TEXT NOT NULL,
                device_id TEXT NOT NULL,
                trace_id TEXT NOT NULL,
                device_name TEXT NOT NULL,
                target_id TEXT NOT NULL,
                target_name TEXT NOT NULL,
                state TEXT NOT NULL,
                started_at_millis INTEGER NOT NULL,
                completed_at_millis INTEGER,
                detail TEXT,
                PRIMARY KEY (network, device_id, trace_id)
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE INDEX index_trace_runs_target
            ON $TABLE_RUNS(network, device_id, target_id, started_at_millis DESC)
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE $TABLE_HOPS (
                network TEXT NOT NULL,
                device_id TEXT NOT NULL,
                trace_id TEXT NOT NULL,
                direction TEXT NOT NULL,
                hop_index INTEGER NOT NULL,
                node_id TEXT NOT NULL,
                node_name TEXT NOT NULL,
                snr REAL,
                PRIMARY KEY (network, device_id, trace_id, direction, hop_index)
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("No traceroute database migration from $oldVersion to $newVersion")
    }

    suspend fun markInterruptedRuns() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        writableDatabase.update(
            TABLE_RUNS,
            ContentValues().apply {
                put("state", TraceRouteState.ERROR.name)
                put("completed_at_millis", now)
                put("detail", "The app stopped before the trace response arrived.")
            },
            "state = ?",
            arrayOf(TraceRouteState.PENDING.name),
        )
    }

    suspend fun loadResults(limit: Int = MAX_STORED_TRACES): List<TraceRouteResult> =
        withContext(Dispatchers.IO) {
            val runs = mutableListOf<TraceRouteResult>()
            readableDatabase.query(
                TABLE_RUNS,
                RUN_COLUMNS,
                null,
                null,
                null,
                null,
                "started_at_millis DESC, rowid DESC",
                limit.toString(),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val network = MeshNetwork.entries.firstOrNull {
                        it.name == cursor.getString(0)
                    } ?: continue
                    val state = TraceRouteState.entries.firstOrNull {
                        it.name == cursor.getString(6)
                    } ?: TraceRouteState.ERROR
                    runs += TraceRouteResult(
                        id = cursor.getString(2),
                        network = network,
                        deviceId = cursor.getString(1),
                        deviceName = cursor.getString(3),
                        targetId = cursor.getString(4),
                        targetName = cursor.getString(5),
                        state = state,
                        startedAtMillis = cursor.getLong(7),
                        completedAtMillis = cursor.longOrNull(8),
                        detail = cursor.stringOrNull(9),
                    )
                }
            }
            val runKeys = runs.mapTo(mutableSetOf()) { it.key() }
            val hopsByRun = mutableMapOf<TraceKey, MutableMap<String, MutableList<IndexedHop>>>()
            readableDatabase.query(
                TABLE_HOPS,
                HOP_COLUMNS,
                null,
                null,
                null,
                null,
                "hop_index ASC",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val network = MeshNetwork.entries.firstOrNull {
                        it.name == cursor.getString(0)
                    } ?: continue
                    val key = TraceKey(network, cursor.getString(1), cursor.getString(2))
                    if (key !in runKeys) continue
                    val direction = cursor.getString(3)
                    val hop = IndexedHop(
                        index = cursor.getInt(4),
                        hop = TraceRouteHop(
                            id = cursor.getString(5),
                            name = cursor.getString(6),
                            snr = cursor.floatOrNull(7),
                        ),
                    )
                    hopsByRun.getOrPut(key, ::mutableMapOf)
                        .getOrPut(direction, ::mutableListOf)
                        .add(hop)
                }
            }
            runs.asReversed().map { run ->
                val hops = hopsByRun[run.key()].orEmpty()
                run.copy(
                    forward = hops[DIRECTION_FORWARD].orEmpty()
                        .sortedBy(IndexedHop::index)
                        .map(IndexedHop::hop),
                    returned = hops[DIRECTION_RETURN].orEmpty()
                        .sortedBy(IndexedHop::index)
                        .map(IndexedHop::hop),
                )
            }
        }

    suspend fun save(result: TraceRouteResult) = withContext(Dispatchers.IO) {
        val database = writableDatabase
        database.beginTransaction()
        try {
            database.insertWithOnConflict(
                TABLE_RUNS,
                null,
                result.toRunValues(),
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            val keyArguments = arrayOf(result.network.name, result.deviceId, result.id)
            database.delete(
                TABLE_HOPS,
                "network = ? AND device_id = ? AND trace_id = ?",
                keyArguments,
            )
            result.forward.forEachIndexed { index, hop ->
                database.insertOrThrow(
                    TABLE_HOPS,
                    null,
                    hop.toValues(result, DIRECTION_FORWARD, index),
                )
            }
            result.returned.forEachIndexed { index, hop ->
                database.insertOrThrow(
                    TABLE_HOPS,
                    null,
                    hop.toValues(result, DIRECTION_RETURN, index),
                )
            }
            database.execSQL(
                """
                DELETE FROM $TABLE_RUNS
                WHERE rowid NOT IN (
                    SELECT rowid FROM $TABLE_RUNS
                    ORDER BY started_at_millis DESC, rowid DESC
                    LIMIT $MAX_STORED_TRACES
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                DELETE FROM $TABLE_HOPS
                WHERE NOT EXISTS (
                    SELECT 1 FROM $TABLE_RUNS
                    WHERE $TABLE_RUNS.network = $TABLE_HOPS.network
                      AND $TABLE_RUNS.device_id = $TABLE_HOPS.device_id
                      AND $TABLE_RUNS.trace_id = $TABLE_HOPS.trace_id
                )
                """.trimIndent(),
            )
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private fun TraceRouteResult.toRunValues() = ContentValues().apply {
        put("network", network.name)
        put("device_id", deviceId)
        put("trace_id", id)
        put("device_name", deviceName)
        put("target_id", targetId)
        put("target_name", targetName)
        put("state", state.name)
        put("started_at_millis", startedAtMillis)
        put("completed_at_millis", completedAtMillis)
        put("detail", detail)
    }

    private fun TraceRouteHop.toValues(
        result: TraceRouteResult,
        direction: String,
        index: Int,
    ) = ContentValues().apply {
        put("network", result.network.name)
        put("device_id", result.deviceId)
        put("trace_id", result.id)
        put("direction", direction)
        put("hop_index", index)
        put("node_id", id)
        put("node_name", name)
        put("snr", snr)
    }

    private fun TraceRouteResult.key() = TraceKey(network, deviceId, id)

    private fun Cursor.stringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun Cursor.longOrNull(index: Int): Long? =
        if (isNull(index)) null else getLong(index)

    private fun Cursor.floatOrNull(index: Int): Float? =
        if (isNull(index)) null else getFloat(index)

    private data class TraceKey(
        val network: MeshNetwork,
        val deviceId: String,
        val traceId: String,
    )

    private data class IndexedHop(
        val index: Int,
        val hop: TraceRouteHop,
    )

    companion object {
        const val MAX_STORED_TRACES = 1_000
        private const val DATABASE_NAME = "dualmesh_traceroutes.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_RUNS = "trace_runs"
        private const val TABLE_HOPS = "trace_hops"
        private const val DIRECTION_FORWARD = "forward"
        private const val DIRECTION_RETURN = "return"
        private val RUN_COLUMNS = arrayOf(
            "network",
            "device_id",
            "trace_id",
            "device_name",
            "target_id",
            "target_name",
            "state",
            "started_at_millis",
            "completed_at_millis",
            "detail",
        )
        private val HOP_COLUMNS = arrayOf(
            "network",
            "device_id",
            "trace_id",
            "direction",
            "hop_index",
            "node_id",
            "node_name",
            "snr",
        )
    }
}
