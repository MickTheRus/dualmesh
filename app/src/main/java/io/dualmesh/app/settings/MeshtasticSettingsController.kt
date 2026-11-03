package io.dualmesh.app.settings

import com.squareup.wire.Message
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.*
import kotlin.random.Random

internal class MeshtasticSettingsController(
    private val send: (ByteArray) -> Boolean,
    private val readyNode: () -> Int?,
    scheduler: SettingsScheduler,
    private val onReadback: (FromRadio) -> Unit,
) : RadioSettingsAccess {
    private val state = MutableStateFlow(RadioSettingsState())
    override val radioSettings = state.asStateFlow()
    private val queue = RadioCommandQueue<MeshPacket>(scheduler)
    private val records = linkedMapOf<String, Message<*, *>>()
    private var passkey = ByteString.EMPTY
    private var needsRefresh = true
    private var packetId = 0

    fun ownerUser(): User? = records["owner"] as? User

    fun reset() {
        queue.cancel("The radio disconnected before the settings operation was verified.")
        passkey = ByteString.EMPTY
        records.clear()
        needsRefresh = true
        state.value = RadioSettingsState(revision = state.value.revision + 1)
    }

    fun observe(from: FromRadio) {
        var changed = false
        fun store(id: String, message: Message<*, *>) {
            if (records[id] != message) { records[id] = message; changed = true }
        }
        from.config?.let { config -> ProtoSettings.records(config).forEach { (name, _) -> store("config:$name", config) } }
        from.moduleConfig?.let { module -> ProtoSettings.records(module).forEach { (name, _) -> store("module:$name", module) } }
        from.channel?.let { store("channel:${it.index}", it) }
        if (changed) publish()
    }

    fun receive(packet: MeshPacket): Boolean = queue.receive(packet)

    override fun refreshSettings(): Result<Unit> = runCatching {
        requireReady()
        check(!queue.busy) { "Wait for the current settings operation" }
        val ids = records.keys.sortedBy(::sectionOrder)
        state.value = state.value.copy(busy = true, loadRequested = true, status = "Reading settings from the radio…", error = null)
        val reads = listOf(readStep("owner")) + ids.mapNotNull { id ->
            if (canRead(id)) readStep(id, optional = true) else null
        }
        queue.start(reads) { error ->
            needsRefresh = error != null
            state.value = state.value.copy(busy = false, status = if (error == null) "Settings read from the radio." else "Read incomplete.", error = error)
        }
    }

    override fun saveSettings(sectionId: String, changes: Map<String, String>, revision: Long): Result<Unit> = runCatching {
        requireReady()
        check(!queue.busy) { "Wait for the current settings operation" }
        check(!needsRefresh) { "Refresh settings before saving" }
        require(revision == state.value.revision) { "Radio settings changed. Reopen this section before saving." }
        require(changes.isNotEmpty()) { "No settings were changed" }
        val original = requireNotNull(records[sectionId]) { "Read this section from the radio first" }
        val allowed = section(sectionId, original).fields.associateBy { it.id }
        require(changes.keys.all { allowed[it]?.kind != null && allowed[it]?.kind != SettingKind.READ_ONLY }) { "Unsupported setting" }
        validate(sectionId, original, changes)
        var expected: Map<String, String> = emptyMap()
        val commands = mutableListOf(readStep("owner")) // Fresh session passkey for every save.
        if (sectionId != "owner") commands += readStep(sectionId)
        commands += writeStep {
            val current = requireNotNull(records[sectionId])
            val updated = validate(sectionId, current, changes)
            expected = section(sectionId, updated).fields.filter { it.id in changes }.associate { it.id to it.value }
            when (updated) {
                is Config -> AdminMessage(set_config = updated)
                is ModuleConfig -> AdminMessage(set_module_config = updated)
                is User -> AdminMessage(set_owner = updated)
                is Channel -> AdminMessage(set_channel = updated)
                else -> error("Unsupported settings section")
            }
        }
        commands += readStep(sectionId) {
            val actual = section(sectionId, requireNotNull(records[sectionId])).fields.associate { it.id to it.value }
            check(expected.all { (key, value) -> actual[key] == value }) {
                "The radio returned different values. Review the refreshed settings before retrying."
            }
        }
        state.value = state.value.copy(busy = true, status = "Saving and verifying settings…", error = null)
        queue.start(commands) { error ->
            needsRefresh = error != null
            state.value = state.value.copy(busy = false, status = if (error == null) "Saved and verified on the radio." else "Save could not be verified.", error = error)
        }
    }

    private fun requireReady(): Int = requireNotNull(readyNode()) { "Connect this radio before changing settings" }

    private fun readStep(id: String, optional: Boolean = false, verified: () -> Unit = {}): RadioCommandQueue.Step<MeshPacket> {
        var requestId = 0
        return RadioCommandQueue.Step(
            send = {
                requestId = nextId()
                packetId = requestId
                val title = when {
                    id == "owner" -> "identity"
                    id.startsWith("channel:") -> "channel ${id.substringAfter(':')}"
                    else -> ProtoSettings.label(id.substringAfter(':'))
                }
                state.value = state.value.copy(status = "Reading $title…")
                sendAdmin(readCommand(id), requestId, response = true)
            },
            accept = { packet ->
                val result = response(packet, requestId, write = false)
                if (result == SettingsReply.Success) {
                    val admin = AdminMessage.ADAPTER.decode(packet.decoded!!.payload)
                    if (matches(id, admin)) SettingsReply.Success else SettingsReply.Ignore
                } else result
            },
            received = { packet ->
                val admin = AdminMessage.ADAPTER.decode(packet.decoded!!.payload)
                if (admin.session_passkey.size > 0) passkey = admin.session_passkey
                admin.get_owner_response?.let { records["owner"] = it; publish() }
                admin.get_config_response?.let { onReadback(FromRadio(config = it)) }
                admin.get_module_config_response?.let { onReadback(FromRadio(moduleConfig = it)) }
                admin.get_channel_response?.let { onReadback(FromRadio(channel = it)) }
                verified()
            }, optional = optional,
        )
    }

    private fun writeStep(command: () -> AdminMessage): RadioCommandQueue.Step<MeshPacket> {
        var requestId = 0
        return RadioCommandQueue.Step(
            send = {
                requestId = nextId()
                packetId = requestId
                sendAdmin(command().copy(session_passkey = passkey), requestId, response = false)
            },
            accept = { response(it, requestId, write = true) },
        )
    }

    private fun sendAdmin(admin: AdminMessage, id: Int, response: Boolean): Boolean {
        val node = readyNode() ?: return false
        val data = Data(portnum = PortNum.ADMIN_APP, payload = admin.encode().toByteString(), want_response = response)
        require(data.encode().size <= Constants.DATA_PAYLOAD_LEN.value) { "Settings exceed the radio payload limit" }
        return send(ToRadio(packet = MeshPacket(
            from = 0, to = node, id = id, decoded = data, channel = 0,
            hop_limit = 0, want_ack = true, priority = MeshPacket.Priority.RELIABLE,
        )).encode())
    }

    private fun response(packet: MeshPacket, id: Int, write: Boolean): SettingsReply {
        if (packet.from != readyNode()) return SettingsReply.Ignore
        val data = packet.decoded ?: return SettingsReply.Ignore
        if (data.request_id != id) return SettingsReply.Ignore
        if (data.portnum == PortNum.ROUTING_APP) {
            val routing = runCatching { Routing.ADAPTER.decode(data.payload) }.getOrNull() ?: return SettingsReply.Ignore
            val reason = routing.error_reason ?: return SettingsReply.Ignore
            if (reason != Routing.Error.NONE) return SettingsReply.Error("Radio rejected the operation: ${reason.name}")
            return if (write) SettingsReply.Success else SettingsReply.Ignore
        }
        if (!write && data.portnum == PortNum.ADMIN_APP && runCatching { AdminMessage.ADAPTER.decode(data.payload) }.isSuccess) {
            return SettingsReply.Success
        }
        return SettingsReply.Ignore
    }

    private fun matches(id: String, admin: AdminMessage): Boolean = when {
        id == "owner" -> admin.get_owner_response != null
        id.startsWith("channel:") -> admin.get_channel_response?.index == id.substringAfter(':').toInt()
        id.startsWith("config:") -> admin.get_config_response?.let { ProtoSettings.records(it).any { pair -> pair.first == id.substringAfter(':') } } == true
        else -> admin.get_module_config_response?.let { ProtoSettings.records(it).any { pair -> pair.first == id.substringAfter(':') } } == true
    }

    private fun canRead(id: String): Boolean = runCatching { readCommand(id) }.isSuccess
    private fun readCommand(id: String): AdminMessage = when {
        id == "owner" -> AdminMessage(get_owner_request = true)
        id.startsWith("channel:") -> AdminMessage(get_channel_request = id.substringAfter(':').toInt() + 1)
        id.startsWith("config:") -> AdminMessage(get_config_request = AdminMessage.ConfigType.entries.first { enumName(it.name) == id.substringAfter(':').replace("_", "") })
        else -> AdminMessage(get_module_config_request = AdminMessage.ModuleConfigType.entries.first { enumName(it.name) == moduleName(id.substringAfter(':')) })
    }
    private fun enumName(value: String) = value.removeSuffix("_CONFIG").lowercase().replace("_", "")
    private fun moduleName(value: String): String = when (value) {
        "external_notification" -> "extnotif"
        "canned_message" -> "cannedmsg"
        else -> value.replace("_", "")
    }

    private fun validate(id: String, message: Message<*, *>, changes: Map<String, String>): Message<*, *> {
        if (id == "owner") {
            changes["long_name"]?.let { require(it.isNotBlank() && it.encodeToByteArray().size <= 39) { "Long name must be 1–39 UTF-8 bytes" } }
            changes["short_name"]?.let { require(it.isNotBlank() && it.encodeToByteArray().size <= 4) { "Short name must be 1–4 UTF-8 bytes" } }
        }
        val prefix = if (id.startsWith("config:") || id.startsWith("module:")) "${id.substringAfter(':')}." else ""
        val result = ProtoSettings.patch(message, changes.mapKeys { prefix + it.key })
        if (result is Config) {
            result.lora?.let { require(it.hop_limit in 0..7) { "Hop limit must be between 0 and 7" } }
            result.bluetooth?.let { require(it.fixed_pin in 0..999999) { "Bluetooth PIN must have at most six digits" } }
        }
        if (result is Channel) {
            require(result.index == (message as Channel).index) { "Channel index cannot change" }
            result.settings?.let {
                require(it.name.encodeToByteArray().size <= 11) { "Channel name must be at most 11 UTF-8 bytes" }
                require(it.psk.size in listOf(0, 1, 16, 32)) { "Channel PSK must decode to 0, 1, 16, or 32 bytes" }
            }
        }
        return result
    }

    private fun section(id: String, value: Message<*, *>): RadioSettingsSection {
        val leaf = if (value is Config || value is ModuleConfig) ProtoSettings.records(value).first().second else value
        val fields = ProtoSettings.fields(leaf).filter {
            when {
                id == "owner" -> it.id in setOf("long_name", "short_name")
                id.startsWith("channel:") -> it.id != "index"
                else -> true
            }
        }
        val title = when {
            id == "owner" -> "Identity"
            id.startsWith("channel:") -> {
                val channel = value as Channel
                "Channel ${channel.index}" + (channel.settings?.name?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            }
            id.startsWith("module:") -> "Module · ${ProtoSettings.label(id.substringAfter(':'))}"
            else -> ProtoSettings.label(id.substringAfter(':'))
        }
        return RadioSettingsSection(id, title, fields)
    }

    private fun publish() {
        state.value = state.value.copy(sections = records.entries.sortedBy { sectionOrder(it.key) }.map { (id, value) -> section(id, value) }, revision = state.value.revision + 1)
    }
    private fun sectionOrder(id: String): String = when {
        id == "owner" -> "00"
        id == "config:lora" -> "01"
        id.startsWith("config:") -> "02:$id"
        id.startsWith("module:") -> "04:$id"
        else -> "03:$id"
    }
    private fun nextId(): Int = generateSequence { Random.nextInt(1, Int.MAX_VALUE) }.first { it != packetId }
}
