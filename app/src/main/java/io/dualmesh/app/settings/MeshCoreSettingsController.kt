package io.dualmesh.app.settings

import io.dualmesh.app.protocol.MeshCoreProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.roundToLong

internal class MeshCoreSettingsController(
    private val send: (ByteArray) -> Boolean,
    private val ready: () -> Boolean,
    private val channelCapacity: () -> Int,
    scheduler: SettingsScheduler,
    private val onReadback: (ByteArray) -> Unit,
) : RadioSettingsAccess {
    private val codec = MeshCoreSettingsCodec()
    private val queue = RadioCommandQueue<ByteArray>(scheduler)
    private val state = MutableStateFlow(RadioSettingsState())
    override val radioSettings = state.asStateFlow()
    val busy: Boolean get() = queue.busy
    private var needsRefresh = true

    fun reset() {
        queue.cancel("The radio disconnected before settings could be verified.")
        codec.clear()
        needsRefresh = true
        state.value = RadioSettingsState(revision = state.value.revision + 1)
    }

    fun observe(frame: ByteArray) {
        if (codec.observe(frame)) publish()
    }

    fun receive(frame: ByteArray): Boolean = queue.receive(frame)

    override fun refreshSettings(): Result<Unit> = runCatching {
        check(ready()) { "Wait for this radio to finish connecting" }
        check(!queue.busy) { "Wait for the current settings operation" }
        val steps = mutableListOf(readStep(MeshCoreProtocol.deviceQuery(), 13), readStep(MeshCoreProtocol.appStart(), 5))
        (0 until channelCapacity()).forEach { steps += readStep(MeshCoreProtocol.getChannel(it), 18, optional = true) }
        steps += readStep(byteArrayOf(43), 23, optional = true)
        steps += readStep(byteArrayOf(40), 21, optional = true)
        if (codec.firmware >= 8) steps += readStep(byteArrayOf(59), 25, optional = true)
        state.value = state.value.copy(busy = true, loadRequested = true, status = "Reading settings from the radio…", error = null)
        queue.start(steps) { error ->
            needsRefresh = error != null
            state.value = state.value.copy(busy = false, status = if (error == null) "Settings read from the radio." else "Read incomplete.", error = error)
        }
    }

    override fun saveSettings(sectionId: String, changes: Map<String, String>, revision: Long): Result<Unit> = runCatching {
        check(ready()) { "Connect this radio before changing settings" }
        check(!queue.busy) { "Wait for the current settings operation" }
        check(!needsRefresh) { "Refresh settings before saving" }
        require(revision == state.value.revision) { "Radio settings changed. Reopen the section before saving." }
        require(changes.isNotEmpty()) { "No settings were changed" }
        codec.encode(sectionId, changes) // Validate the complete edit before transmitting anything.
        val reads = codec.reads(sectionId)
        var commands: List<ByteArray> = emptyList()
        val steps = reads.mapIndexed { index, (command, code) -> readStep(command, code) {
            if (index == reads.lastIndex) commands = codec.encode(sectionId, changes)
        } }.toMutableList()
        // The count of writes depends on the edited fields, not on the readback values.
        val count = codec.encode(sectionId, changes).size
        repeat(count) { index -> steps += RadioCommandQueue.Step(
            send = { send(commands[index]) },
            accept = { frame -> when (frame.firstOrNull()?.toInt()?.and(255)) {
                0 -> SettingsReply.Success
                1 -> SettingsReply.Error("Radio rejected the change (code ${frame.getOrNull(1)?.toInt()?.and(255) ?: -1}). Refresh to check any earlier writes.")
                else -> SettingsReply.Ignore
            } },
        ) }
        steps += reads.mapIndexed { index, (command, code) -> readStep(command, code) {
            if (index == reads.lastIndex) {
                val actual = codec.sections().first { it.id == sectionId }.fields.associateBy { it.id }
                check(changes.all { (key, value) -> equivalent(actual[key]?.value, value, key) }) {
                    "The radio returned different values. Review the refreshed settings before retrying."
                }
            }
        } }
        state.value = state.value.copy(busy = true, status = "Saving and verifying settings…", error = null)
        queue.start(steps) { error ->
            needsRefresh = error != null
            state.value = state.value.copy(busy = false, status = if (error == null) "Saved and verified on the radio." else "Save could not be verified.", error = error)
        }
    }

    private fun readStep(command: ByteArray, code: Int, optional: Boolean = false, received: () -> Unit = {}): RadioCommandQueue.Step<ByteArray> =
        RadioCommandQueue.Step(
            send = { send(command) },
            accept = { frame ->
                val type = frame.firstOrNull()?.toInt()?.and(255)
                when {
                    type == 1 -> SettingsReply.Error("Radio does not support or could not read this setting (code ${frame.getOrNull(1)?.toInt()?.and(255) ?: -1})")
                    type != code -> SettingsReply.Ignore
                    code == 18 && frame.getOrNull(1) != command.getOrNull(1) -> SettingsReply.Ignore
                    else -> SettingsReply.Success
                }
            },
            received = { frame ->
                check(codec.observe(frame)) { "The radio returned an incomplete settings record" }
                publish()
                onReadback(frame)
                received()
            }, optional = optional,
        )

    private fun publish() {
        state.value = state.value.copy(sections = codec.sections(), revision = state.value.revision + 1)
    }

    private fun equivalent(actual: String?, expected: String, key: String): Boolean {
        if (actual == null) return false
        if (key == "psk_hex") return actual.equals(expected.trim(), ignoreCase = true)
        val a = actual.toDoubleOrNull(); val b = expected.toDoubleOrNull()
        val scale = when (key) {
            "frequency_mhz", "bandwidth_khz", "rx_delay", "airtime_factor" -> 1000.0
            "latitude", "longitude" -> 1_000_000.0
            else -> null
        }
        val normalized = if (b != null && scale != null) (b * scale).roundToLong() / scale else b
        return if (a != null && normalized != null) abs(a - normalized) <= 0.000001 else actual == expected
    }
}
