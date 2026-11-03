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
    private val onContact: (ByteArray) -> Unit = {},
    private val onIdle: () -> Unit = {},
) : NodeActions, ContactRoutes {
    private val queue = RadioCommandQueue<ByteArray>(scheduler, timeoutMessage = "The radio did not answer the node action. Firmware may not support it.")
    private val mutableState = MutableStateFlow(NodeActionState())
    override val state = mutableState.asStateFlow()
    val busy get() = queue.busy
    private var multiHashSupported = false
    override val supportedHashBytes get() = if (multiHashSupported) listOf(1, 2, 3) else listOf(1)
    fun observeDeviceInfo(frame: ByteArray) {
        if (frame.firstOrNull() == 13.toByte()) multiHashSupported = frame.size > 81
    }
    private val mutableRoutes = MutableStateFlow<Map<String, ContactRoute>>(emptyMap())
    override val routeState = mutableRoutes.asStateFlow()
    override val operationState get() = state

    fun observeContact(frame: ByteArray) {
        val (id, route) = runCatching { MeshCoreRouteCodec.parseContact(frame) }.getOrNull() ?: return
        val old = mutableRoutes.value[id]
        val history = if (old != null && old.current != route) (listOf(old.current) + old.history).distinct().take(10) else old?.history.orEmpty()
        mutableRoutes.value = mutableRoutes.value + (id to ContactRoute(route, history, old?.discovered))
    }

    override fun refreshRoute(contactId: String): Result<Unit> = runCatching {
        val key = MeshCoreRouteCodec.key(contactId)
        requireReady()
        begin("Reading contact route…")
        queue.start(listOf(readContact(key))) { error -> finish(error, "Route read from radio") }
    }

    override fun setRoute(contactId: String, route: MeshCoreRoute): Result<Unit> = runCatching {
        val key = MeshCoreRouteCodec.key(contactId)
        val normalized = MeshCoreRouteCodec.normalize(route)
        require(normalized.hashBytes in supportedHashBytes) { "This radio has not reported support for ${normalized.hashBytes}-byte path hashes" }
        requireReady()
        var fresh = byteArrayOf()
        begin("Saving and verifying contact route…")
        queue.start(listOf(
            readContact(key) { fresh = it },
            RadioCommandQueue.Step(
                send = { send(if (normalized.flood) byteArrayOf(13) + key else MeshCoreRouteCodec.updateContact(fresh, normalized)) },
                accept = ::ack,
            ),
            readContact(key) { frame ->
                check(MeshCoreRouteCodec.parseContact(frame).second == normalized) { "Radio returned a different route. Refresh before retrying; the route may have changed." }
            },
        )) { error -> finish(error, "Route saved and verified on radio") }
    }

    override fun discoverRoute(contactId: String): Result<Unit> = runCatching {
        val key = MeshCoreRouteCodec.key(contactId)
        requireReady()
        begin("Discovering route over the mesh…")
        queue.start(listOf(
            readContact(key),
            RadioCommandQueue.Step(
                send = { send(byteArrayOf(52, 0) + key) },
                accept = { frame -> if (frame.size >= 10 && frame[0] == 6.toByte()) SettingsReply.Success else errorReply(frame) },
            ),
            RadioCommandQueue.Step(
                send = { true },
                accept = { frame ->
                    if (frame.size >= 8 && frame[0] == 0x8d.toByte() && frame.copyOfRange(2, 8).contentEquals(key.copyOf(6))) SettingsReply.Success else errorReply(frame)
                },
                received = { frame ->
                    val id = contactId.lowercase()
                    val route = MeshCoreRouteCodec.discovery(frame)
                    val old = mutableRoutes.value[id] ?: error("Read the contact route before discovering")
                    mutableRoutes.value = mutableRoutes.value + (id to old.copy(discovered = route))
                },
                timeoutMillis = 60_000,
            ),
        )) { error -> finish(error, "Route discovered. Review it before saving to the radio.") }
    }

    private fun readContact(key: ByteArray, received: (ByteArray) -> Unit = {}): RadioCommandQueue.Step<ByteArray> = RadioCommandQueue.Step(
        send = { send(byteArrayOf(30) + key) },
        accept = { frame ->
            if (frame.size >= 33 && frame[0] == 3.toByte() && frame.copyOfRange(1, 33).contentEquals(key)) SettingsReply.Success else errorReply(frame)
        },
        received = { frame ->
            MeshCoreRouteCodec.parseContact(frame)
            observeContact(frame)
            onContact(frame)
            received(frame)
        },
    )

    private fun errorReply(frame: ByteArray): SettingsReply = if (frame.firstOrNull() == 1.toByte())
        SettingsReply.Error("Radio rejected the route operation (code ${frame.getOrNull(1)?.toInt()?.and(255)}). Refresh to check the current route.") else SettingsReply.Ignore
    private fun ack(frame: ByteArray): SettingsReply = if (frame.firstOrNull() == 0.toByte()) SettingsReply.Success else errorReply(frame)
    private fun begin(status: String) { mutableState.value = mutableState.value.copy(busy = true, status = status, error = null) }


    fun reset() {
        queue.cancel("Radio disconnected")
        mutableState.value = NodeActionState()
        mutableRoutes.value = emptyMap()
        multiHashSupported = false
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
        onIdle()
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
