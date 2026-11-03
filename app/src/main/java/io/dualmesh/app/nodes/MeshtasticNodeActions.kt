package io.dualmesh.app.nodes

import io.dualmesh.app.settings.ProtoSettings
import io.dualmesh.app.settings.RadioCommandQueue
import io.dualmesh.app.settings.SettingsReply
import io.dualmesh.app.settings.SettingsScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.*
import kotlin.random.Random

internal class MeshtasticNodeActions(
    private val send: (ByteArray) -> Boolean,
    private val readyNode: () -> Int?,
    private val localUser: () -> User?,
    private val channel: (Int) -> Int,
    private val hopLimit: () -> Int,
    scheduler: SettingsScheduler,
) : NodeActions {
    private val queue = RadioCommandQueue<MeshPacket>(scheduler, timeoutMillis = 30_000,
        timeoutMessage = "No telemetry response arrived. The node may be unreachable or telemetry may be disabled.")
    private val mutableState = MutableStateFlow(NodeActionState())
    override val state = mutableState.asStateFlow()
    val busy get() = queue.busy

    fun reset() {
        queue.cancel("Radio disconnected")
        mutableState.value = NodeActionState()
    }

    fun receive(packet: MeshPacket) = queue.receive(packet)

    override fun requestStats(nodeId: String?): Result<Unit> = runCatching {
        val local = requireReady()
        val destination = nodeId?.let { parseNodeId(it) } ?: local
        require(destination != 0 && destination != -1) { "Select one Meshtastic node" }
        mutableState.value = NodeActionState(busy = true, status = "Requesting telemetry…", statsTarget = "!${destination.toUInt().toString(16).padStart(8, '0')}")
        val types = if (destination == local) listOf(false, true) else listOf(false)
        queue.start(types.map { localStats ->
            val id = Random.nextInt(1, Int.MAX_VALUE)
            val telemetry = if (localStats) Telemetry(local_stats = LocalStats()) else Telemetry(device_metrics = DeviceMetrics())
            val packet = MeshPacket(from = local, to = destination, id = id, channel = channel(destination),
                hop_limit = if (destination == local) 0 else hopLimit().coerceIn(0, 7),
                decoded = Data(portnum = PortNum.TELEMETRY_APP, payload = telemetry.encode().toByteString(), want_response = true, dest = destination))
            RadioCommandQueue.Step(
                send = { send(ToRadio(packet = packet).encode()) },
                accept = { response ->
                    val data = response.decoded
                    when {
                        data == null || data.request_id != id -> SettingsReply.Ignore
                        data.portnum == PortNum.ROUTING_APP && (response.from == local || response.from == destination) -> {
                            val routing = runCatching { Routing.ADAPTER.decode(data.payload) }.getOrNull()
                            if (routing?.error_reason != null && routing.error_reason != Routing.Error.NONE) SettingsReply.Error("Telemetry request rejected: ${routing.error_reason}") else SettingsReply.Ignore
                        }
                        response.from == destination && data.portnum == PortNum.TELEMETRY_APP -> {
                            val reply = runCatching { Telemetry.ADAPTER.decode(data.payload) }.getOrNull()
                            if (reply == null) SettingsReply.Error("Invalid telemetry response")
                            else if ((localStats && reply.local_stats != null) || (!localStats && reply.device_metrics != null)) SettingsReply.Success
                            else SettingsReply.Ignore
                        }
                        else -> SettingsReply.Ignore
                    }
                },
                received = { response ->
                    val reply = Telemetry.ADAPTER.decode(response.decoded!!.payload)
                    val record = if (localStats) reply.local_stats!! else reply.device_metrics!!
                    val values = ProtoSettings.fields(record).filter { it.value.isNotEmpty() }.map { field ->
                        field.label to when (field.id) {
                            "battery_level" -> if (field.value == "101") "External power" else "${field.value}%"
                            "voltage" -> "${field.value} V"
                            "channel_utilization", "air_util_tx" -> "${field.value}%"
                            "uptime_seconds" -> "${field.value} s"
                            "noise_floor" -> "${field.value} dBm"
                            else -> field.value
                        }
                    }
                    mutableState.value = mutableState.value.copy(stats = mutableState.value.stats + NodeStatsGroup(if (localStats) "Local statistics" else "Device telemetry", values), updatedAtMillis = System.currentTimeMillis())
                },
            )
        }) { error ->
            val partialLocal = destination == local && error != null && mutableState.value.stats.size == 1
            mutableState.value = mutableState.value.copy(
                busy = false,
                status = when { error == null -> "Telemetry updated"; partialLocal -> "Device telemetry updated"; else -> "Telemetry request incomplete" },
                error = if (partialLocal) "Local statistics were not returned by this firmware. Device telemetry is available below. $error" else error,
            )
        }
    }

    override fun advertise(flood: Boolean): Result<Unit> = runCatching {
        val local = requireReady()
        val user = localUser() ?: error("The radio has not supplied its own user info yet")
        val hops = if (flood) hopLimit().coerceIn(0, 7) else 0
        val packet = MeshPacket(from = local, to = -1, id = Random.nextInt(1, Int.MAX_VALUE),
            channel = channel(-1), hop_limit = hops,
            decoded = Data(portnum = PortNum.NODEINFO_APP, payload = user.encode().toByteString(), want_response = false))
        check(send(ToRadio(packet = packet).encode())) { "Radio transport is unavailable" }
        mutableState.value = mutableState.value.copy(status = "Node-info broadcast queued for radio ($hops hops)", error = null)
    }

    private fun requireReady(): Int {
        check(!busy) { "Wait for the current node action" }
        return readyNode() ?: error("Wait for this radio to finish connecting and its current operation")
    }

    companion object {
        fun parseNodeId(value: String): Int = if (value.startsWith("!")) value.drop(1).toUInt(16).toInt() else value.toUInt().toInt()
    }
}
