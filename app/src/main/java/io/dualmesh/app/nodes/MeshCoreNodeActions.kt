package io.dualmesh.app.nodes

import io.dualmesh.app.settings.RadioCommandQueue
import io.dualmesh.app.settings.SettingsReply
import io.dualmesh.app.settings.SettingsScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class MeshCoreNodeActions(
    private val send: (ByteArray) -> Boolean,
    private val ready: () -> Boolean,
    scheduler: SettingsScheduler,
) : NodeActions {
    private val queue = RadioCommandQueue<ByteArray>(scheduler, timeoutMessage = "The radio did not answer the node action. Firmware may not support it.")
    private val mutableState = MutableStateFlow(NodeActionState())
    override val state = mutableState.asStateFlow()
    val busy get() = queue.busy

    fun reset() {
        queue.cancel("Radio disconnected")
        mutableState.value = NodeActionState()
    }

    fun receive(frame: ByteArray) = queue.receive(frame)

    override fun requestStats(nodeId: String?): Result<Unit> = runCatching {
        require(nodeId == null) { "MeshCore stats are available for the connected companion radio" }
        requireReady()
        mutableState.value = NodeActionState(busy = true, status = "Reading radio statistics…", statsTarget = "Connected MeshCore radio")
        queue.start((0..2).map { type ->
            RadioCommandQueue.Step(
                send = { send(byteArrayOf(56, type.toByte())) },
                accept = { frame -> when {
                    frame.firstOrNull() == 1.toByte() -> SettingsReply.Error("Radio rejected stats (code ${frame.getOrNull(1)?.toInt()?.and(255)}). Stats require MeshCore protocol 8 or newer.")
                    frame.size >= 2 && frame[0] == 24.toByte() && frame[1].toInt() == type -> SettingsReply.Success
                    else -> SettingsReply.Ignore
                } },
                received = { frame ->
                    val group = parseStats(frame)
                    mutableState.value = mutableState.value.copy(stats = mutableState.value.stats + group, updatedAtMillis = System.currentTimeMillis())
                },
            )
        }) { error -> finish(error, "Radio statistics updated") }
    }

    override fun advertise(flood: Boolean): Result<Unit> = runCatching {
        requireReady()
        mutableState.value = mutableState.value.copy(busy = true, status = "Sending ${if (flood) "flood" else "zero-hop"} advert…", error = null)
        queue.start(listOf(RadioCommandQueue.Step(
            send = { send(byteArrayOf(7, if (flood) 1 else 0)) },
            accept = { frame -> when (frame.firstOrNull()?.toInt()) {
                0 -> SettingsReply.Success
                1 -> SettingsReply.Error("Radio rejected the advert (code ${frame.getOrNull(1)?.toInt()?.and(255)})")
                else -> SettingsReply.Ignore
            } },
        ))) { error -> finish(error, "${if (flood) "Flood" else "Zero-hop"} advert accepted by the radio") }
    }

    private fun requireReady() {
        check(ready()) { "Wait for this radio to finish connecting and its current operation" }
        check(!busy) { "Wait for the current node action" }
    }

    private fun finish(error: String?, status: String) {
        mutableState.value = mutableState.value.copy(busy = false, status = if (error == null) status else "Node action incomplete", error = error)
    }

    companion object {
        fun parseStats(frame: ByteArray): NodeStatsGroup {
            require(frame.size >= 2 && frame[0] == 24.toByte()) { "Invalid statistics frame" }
            val data = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
            fun u16(offset: Int) = data.getShort(offset).toInt() and 65535
            fun u32(offset: Int) = data.getInt(offset).toLong() and 0xffffffffL
            return when (frame[1].toInt()) {
                0 -> {
                    require(frame.size >= 11) { "Incomplete core statistics" }
                    NodeStatsGroup("Device", listOf("Battery" to "${u16(2)} mV", "Uptime" to "${u32(4)} s", "Error flags" to "0x${u16(8).toString(16)}", "Queued packets" to "${frame[10].toInt() and 255}"))
                }
                1 -> {
                    require(frame.size >= 14) { "Incomplete radio statistics" }
                    NodeStatsGroup("Radio", listOf("Noise floor" to "${data.getShort(2)} dBm", "Last RSSI" to "${frame[4]} dBm", "Last SNR" to "${frame[5] / 4f} dB", "TX airtime" to "${u32(6)} s", "RX airtime" to "${u32(10)} s"))
                }
                2 -> {
                    require(frame.size >= 26) { "Incomplete packet statistics" }
                    NodeStatsGroup("Packets", listOf("Received", "Sent", "Flood TX", "Direct TX", "Flood RX", "Direct RX").mapIndexed { index, name -> name to "${u32(2 + index * 4)}" } + if (frame.size >= 30) listOf("Receive errors" to "${u32(26)}") else emptyList())
                }
                else -> error("Unsupported statistics type")
            }
        }
    }
}
