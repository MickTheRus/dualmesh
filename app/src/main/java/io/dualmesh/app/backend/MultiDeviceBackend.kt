package io.dualmesh.app.backend

import android.content.Context
import io.dualmesh.app.connection.ConnectionProfileStore
import io.dualmesh.app.logging.PacketLogger
import io.dualmesh.app.model.ConnectionState
import io.dualmesh.app.model.MeshNetwork
import io.dualmesh.app.model.MeshNode
import io.dualmesh.app.model.RadioChannel
import io.dualmesh.app.model.RadioContact
import io.dualmesh.app.model.TraceRouteResult
import io.dualmesh.app.model.UnifiedMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Control multiple radio sessions that use the same protocol.
 * All sessions receive messages.
 * One selected session transmits messages and controls channels.
 */
class MultiDeviceBackend(
    private val context: Context,
    override val network: MeshNetwork,
    private val packetLogger: PacketLogger,
    private val scope: CoroutineScope,
) : MessagingBackend {
    data class DeviceConnection(
        val profile: ConnectionProfileStore.Profile,
        val state: ConnectionState,
        val isOutbound: Boolean,
        val channels: List<RadioChannel>,
        val contacts: List<RadioContact>,
        val nodes: List<MeshNode>,
        val mqttProxyStatus: String? = null,
    )

    private data class Slot(
        var profile: ConnectionProfileStore.Profile,
        val backend: MessagingBackend,
        val observers: MutableList<Job> = mutableListOf(),
        var reconnectJob: Job? = null,
        var reconnectAttempt: Int = 0,
    )

    override val maxMessageBytes: Int =
        if (network == MeshNetwork.MESHCORE) 127 else 220

    private val slots = linkedMapOf<String, Slot>()
    private var outboundId: String? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<UnifiedMessage>(extraBufferCapacity = 128)
    override val incomingMessages: SharedFlow<UnifiedMessage> = _incomingMessages.asSharedFlow()

    private val _traceRouteResults =
        MutableSharedFlow<TraceRouteResult>(extraBufferCapacity = 32)
    override val traceRouteResults: SharedFlow<TraceRouteResult> =
        _traceRouteResults.asSharedFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    override val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val _channels = MutableStateFlow(defaultChannels())
    override val channels: StateFlow<List<RadioChannel>> = _channels.asStateFlow()

    private val _contacts = MutableStateFlow<List<RadioContact>>(emptyList())
    override val contacts: StateFlow<List<RadioContact>> = _contacts.asStateFlow()

    private val _nodes = MutableStateFlow<List<MeshNode>>(emptyList())
    override val nodes: StateFlow<List<MeshNode>> = _nodes.asStateFlow()

    private val _channelCapacity = MutableStateFlow(defaultChannelCapacity())
    override val channelCapacity: StateFlow<Int> = _channelCapacity.asStateFlow()

    private val _selectedChannel = MutableStateFlow(0)
    override val selectedChannel: StateFlow<Int> = _selectedChannel.asStateFlow()

    private val _deviceConnections = MutableStateFlow<List<DeviceConnection>>(emptyList())
    val deviceConnections: StateFlow<List<DeviceConnection>> = _deviceConnections.asStateFlow()

    fun add(
        profile: ConnectionProfileStore.Profile,
        makeOutbound: Boolean,
    ): Result<Unit> {
        require(profile.network == network) { "Profile belongs to ${profile.network.label}" }
        val id = targetId(profile)
        val existing = slots[id]
        if (existing != null) {
            existing.profile = profile.copy(enabled = true)
            if (makeOutbound || outboundId == null) outboundId = id
            connectSlot(id, existing)
            refreshAggregate()
            return Result.success(Unit)
        }
        if (slots.size >= MAX_ACTIVE_DEVICES) {
            return Result.failure(
                IllegalStateException(
                    "${network.label} supports up to $MAX_ACTIVE_DEVICES active devices",
                ),
            )
        }

        val backend = createBackend(profile)
        val slot = Slot(profile.copy(enabled = true), backend)
        slots[id] = slot
        if (makeOutbound || outboundId == null) outboundId = id
        observe(id, slot)
        val result = connectSlot(id, slot)
        if (result.isFailure) {
            remove(profile)
            return result
        }
        refreshAggregate()
        return Result.success(Unit)
    }

    fun remove(profile: ConnectionProfileStore.Profile) {
        val id = targetId(profile)
        val slot = slots.remove(id) ?: return
        slot.reconnectJob?.cancel()
        slot.observers.forEach(Job::cancel)
        slot.backend.disconnect()
        if (outboundId == id) outboundId = slots.keys.firstOrNull()
        refreshAggregate()
    }

    fun setOutbound(profile: ConnectionProfileStore.Profile): Result<Unit> {
        val id = targetId(profile)
        if (id !in slots) {
            return Result.failure(IllegalStateException("Connect this device before selecting it"))
        }
        outboundId = id
        refreshAggregate()
        return Result.success(Unit)
    }

    fun isActive(profile: ConnectionProfileStore.Profile): Boolean =
        targetId(profile) in slots

    fun settingsFor(deviceId: String): io.dualmesh.app.settings.RadioSettingsAccess? =
        slots[deviceId]?.backend?.settingsAccess

    override fun connect(address: String, name: String) {
        add(
            ConnectionProfileStore.Profile(
                network = network,
                transport = ConnectionProfileStore.Transport.BLE,
                address = address,
                name = name,
                enabled = true,
            ),
            makeOutbound = true,
        )
    }

    override fun disconnect() {
        slots.values.forEach { slot ->
            slot.reconnectJob?.cancel()
            slot.observers.forEach(Job::cancel)
            slot.backend.disconnect()
        }
        slots.clear()
        outboundId = null
        refreshAggregate()
    }

    override fun selectChannel(index: Int) {
        outboundBackend()?.selectChannel(index)
        refreshOutboundData()
    }

    override fun sendChannelMessage(text: String, channel: Int): Result<Unit> =
        outboundBackend()?.sendChannelMessage(text, channel)
            ?: Result.failure(IllegalStateException("No outbound ${network.label} device"))

    override fun sendDirectMessage(text: String, contactId: String): Result<Unit> =
        outboundBackend()?.sendDirectMessage(text, contactId)
            ?: Result.failure(IllegalStateException("No outbound ${network.label} device"))

    override fun traceRoute(contactId: String): Result<Unit> =
        outboundBackend()?.traceRoute(contactId)
            ?: Result.failure(IllegalStateException("No outbound ${network.label} device"))

    fun sendChannelMessage(deviceId: String, text: String, channel: Int): Result<Unit> =
        slots[deviceId]?.backend?.sendChannelMessage(text, channel)
            ?: Result.failure(IllegalStateException("${network.label} device is not connected"))

    fun sendDirectMessage(deviceId: String, text: String, contactId: String): Result<Unit> =
        slots[deviceId]?.backend?.sendDirectMessage(text, contactId)
            ?: Result.failure(IllegalStateException("${network.label} device is not connected"))

    fun traceRoute(deviceId: String, contactId: String): Result<Unit> =
        slots[deviceId]?.backend?.traceRoute(contactId)
            ?: Result.failure(IllegalStateException("${network.label} device is not connected"))

    private fun createBackend(profile: ConnectionProfileStore.Profile): MessagingBackend {
        val captureSource = profile.name
            .removePrefix("${profile.transport.name} ")
            .ifBlank { profile.address }
        return when (network) {
            MeshNetwork.MESHCORE -> MeshCoreBackend(context, packetLogger, captureSource)
            MeshNetwork.MESHTASTIC -> MeshtasticBackend(context, packetLogger, captureSource)
        }
    }

    private fun observe(id: String, slot: Slot) {
        (slot.backend as? MeshtasticBackend)?.let { backend ->
            slot.observers += backend.mqttProxyStatus
                .onEach { refreshAggregate() }
                .launchIn(scope)
        }
        slot.observers += slot.backend.incomingMessages
            .onEach { message ->
                _incomingMessages.emit(
                    message.copy(
                        deviceId = id,
                        deviceName = slot.profile.name,
                    ),
                )
            }
            .launchIn(scope)
        slot.observers += slot.backend.traceRouteResults
            .onEach { result ->
                _traceRouteResults.emit(
                    result.copy(
                        deviceId = id,
                        deviceName = slot.profile.name,
                    ),
                )
            }
            .launchIn(scope)
        slot.observers += slot.backend.connectionState
            .onEach { state ->
                if (slots[id] !== slot) return@onEach
                when (state) {
                    ConnectionState.CONNECTED -> {
                        slot.reconnectAttempt = 0
                        slot.reconnectJob?.cancel()
                        slot.reconnectJob = null
                    }
                    ConnectionState.DISCONNECTED,
                    ConnectionState.ERROR,
                    -> scheduleReconnect(id, slot)
                    ConnectionState.CONNECTING -> Unit
                }
                refreshAggregate()
            }
            .launchIn(scope)
        listOf(
            slot.backend.connectedDeviceName,
            slot.backend.channels,
            slot.backend.contacts,
            slot.backend.nodes,
            slot.backend.channelCapacity,
            slot.backend.selectedChannel,
        ).forEach { flow ->
            slot.observers += flow
                .onEach { refreshAggregate() }
                .launchIn(scope)
        }
    }

    private fun connectSlot(id: String, slot: Slot): Result<Unit> {
        slot.reconnectJob?.cancel()
        slot.reconnectJob = null
        return when (slot.profile.transport) {
            ConnectionProfileStore.Transport.BLE -> {
                slot.backend.connect(slot.profile.address, slot.profile.name)
                Result.success(Unit)
            }
            ConnectionProfileStore.Transport.TCP -> {
                val backend = slot.backend as? MeshtasticBackend
                    ?: return Result.failure(
                        IllegalArgumentException("TCP is only supported by Meshtastic"),
                    )
                backend.connectTcp(slot.profile.address)
            }
        }.also {
            if (it.isFailure && slots[id] === slot) refreshAggregate()
        }
    }

    private fun scheduleReconnect(id: String, slot: Slot) {
        if (slot.reconnectJob?.isActive == true || slots[id] !== slot) return
        val delayMillis = RETRY_DELAYS_MS[
            slot.reconnectAttempt.coerceAtMost(RETRY_DELAYS_MS.lastIndex)
        ]
        slot.reconnectJob = scope.launch {
            delay(delayMillis)
            if (slots[id] !== slot) return@launch
            slot.reconnectJob = null
            if (slot.backend.connectionState.value == ConnectionState.DISCONNECTED ||
                slot.backend.connectionState.value == ConnectionState.ERROR
            ) {
                slot.reconnectAttempt++
                connectSlot(id, slot)
            }
        }
    }

    private fun refreshAggregate() {
        val states = slots.values.map { it.backend.connectionState.value }
        _connectionState.value = when {
            ConnectionState.CONNECTED in states -> ConnectionState.CONNECTED
            ConnectionState.CONNECTING in states -> ConnectionState.CONNECTING
            ConnectionState.ERROR in states -> ConnectionState.ERROR
            else -> ConnectionState.DISCONNECTED
        }
        val connected = slots.values.filter {
            it.backend.connectionState.value == ConnectionState.CONNECTED
        }
        _connectedDeviceName.value = when (connected.size) {
            0 -> outboundSlot()?.backend?.connectedDeviceName?.value
            1 -> connected.first().backend.connectedDeviceName.value
            else -> "${connected.size} devices"
        }
        _deviceConnections.value = slots.map { (id, slot) ->
            DeviceConnection(
                profile = slot.profile,
                state = slot.backend.connectionState.value,
                mqttProxyStatus = (slot.backend as? MeshtasticBackend)?.mqttProxyStatus?.value,
                isOutbound = id == outboundId,
                channels = slot.backend.channels.value,
                contacts = slot.backend.contacts.value,
                nodes = slot.backend.nodes.value.map {
                    it.copy(deviceId = id, deviceName = slot.profile.name)
                },
            )
        }
        refreshOutboundData()
    }

    private fun refreshOutboundData() {
        val backend = outboundBackend()
        _channels.value = backend?.channels?.value ?: defaultChannels()
        _contacts.value = backend?.contacts?.value.orEmpty()
        _nodes.value = slots.flatMap { (id, slot) ->
            slot.backend.nodes.value.map { node ->
                node.copy(deviceId = id, deviceName = slot.profile.name)
            }
        }
        _channelCapacity.value = backend?.channelCapacity?.value ?: defaultChannelCapacity()
        _selectedChannel.value = backend?.selectedChannel?.value ?: 0
    }

    private fun outboundSlot(): Slot? = outboundId?.let(slots::get)

    private fun outboundBackend(): MessagingBackend? = outboundSlot()?.backend

    private fun defaultChannels(): List<RadioChannel> =
        if (network == MeshNetwork.MESHCORE) {
            listOf(RadioChannel(0, "Public"))
        } else {
            listOf(RadioChannel(0, "Primary", "PRIMARY"))
        }

    private fun defaultChannelCapacity(): Int =
        if (network == MeshNetwork.MESHCORE) 8 else 8

    companion object {
        const val MAX_ACTIVE_DEVICES = 4
        private val RETRY_DELAYS_MS = longArrayOf(2_000, 5_000, 10_000, 30_000, 60_000)

        fun targetId(profile: ConnectionProfileStore.Profile): String =
            "${profile.network.name}:${profile.transport.name}:${profile.address}"
    }
}
