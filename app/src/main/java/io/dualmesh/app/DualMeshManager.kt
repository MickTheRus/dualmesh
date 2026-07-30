package io.dualmesh.app

import android.content.Context
import android.util.Log
import io.dualmesh.app.backend.MessagingBackend
import io.dualmesh.app.backend.MultiDeviceBackend
import io.dualmesh.app.ble.BleRadioScanner
import io.dualmesh.app.connection.ConnectionProfileStore
import io.dualmesh.app.history.MessageHistoryStore
import io.dualmesh.app.history.NodeHistoryStore
import io.dualmesh.app.logging.PacketLogger
import io.dualmesh.app.model.MeshNetwork
import io.dualmesh.app.model.MeshNode
import io.dualmesh.app.model.RadioDevice
import io.dualmesh.app.model.TraceRouteResult
import io.dualmesh.app.model.UnifiedMessage
import io.dualmesh.app.service.MeshConnectionService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DualMeshManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val packetLogger = PacketLogger(context)
    val scanner = BleRadioScanner(context)
    val meshCore = MultiDeviceBackend(
        context,
        MeshNetwork.MESHCORE,
        packetLogger,
        scope,
    )
    val meshtastic = MultiDeviceBackend(
        context,
        MeshNetwork.MESHTASTIC,
        packetLogger,
        scope,
    )

    private val backends: Map<MeshNetwork, MultiDeviceBackend> = mapOf(
        MeshNetwork.MESHCORE to meshCore,
        MeshNetwork.MESHTASTIC to meshtastic,
    )
    private val profileStore = ConnectionProfileStore(context)
    private val messageStore = MessageHistoryStore(context)
    private val nodeStore = NodeHistoryStore(context)
    private val historyReady = CompletableDeferred<Unit>()
    private val nodesReady = CompletableDeferred<Unit>()
    private val historyMutex = Mutex()
    private val nodeMutex = Mutex()
    private var autoReconnectStarted = false

    private val _selectedNetwork = MutableStateFlow(profileStore.loadSelectedNetwork())
    val selectedNetwork: StateFlow<MeshNetwork> = _selectedNetwork.asStateFlow()

    private val _messages = MutableStateFlow<List<UnifiedMessage>>(emptyList())
    val messages: StateFlow<List<UnifiedMessage>> = _messages.asStateFlow()

    private val _nodes = MutableStateFlow<List<MeshNode>>(emptyList())
    val nodes: StateFlow<List<MeshNode>> = _nodes.asStateFlow()

    private val _traceRouteResults = MutableStateFlow<List<TraceRouteResult>>(emptyList())
    val traceRouteResults: StateFlow<List<TraceRouteResult>> =
        _traceRouteResults.asStateFlow()

    private val _messageEvents = MutableSharedFlow<UnifiedMessage>(extraBufferCapacity = 64)
    val messageEvents: SharedFlow<UnifiedMessage> = _messageEvents.asSharedFlow()

    private val _selectedDeviceIds = MutableStateFlow(
        MeshNetwork.entries.associateWith { network ->
            profileStore.loadOutbound(network)?.let(MultiDeviceBackend::targetId)
        },
    )
    val selectedDeviceIds: StateFlow<Map<MeshNetwork, String?>> =
        _selectedDeviceIds.asStateFlow()

    private val _selectedConversationIds = MutableStateFlow(
        MeshNetwork.entries.associateWith { network ->
            profileStore.loadConversation(network, _selectedDeviceIds.value[network])
        },
    )
    val selectedConversationIds: StateFlow<Map<MeshNetwork, String>> =
        _selectedConversationIds.asStateFlow()

    private val _reconnectTargets = MutableStateFlow<Set<MeshNetwork>>(emptySet())
    val reconnectTargets: StateFlow<Set<MeshNetwork>> = _reconnectTargets.asStateFlow()

    private val _connectionHistory = MutableStateFlow(
        MeshNetwork.entries.associateWith(profileStore::loadAll),
    )
    val connectionHistory: StateFlow<Map<MeshNetwork, List<ConnectionProfileStore.Profile>>> =
        _connectionHistory.asStateFlow()

    init {
        scope.launch {
            val storedMessages = runCatching { messageStore.loadMessages() }
                .onFailure { Log.e(TAG, "Unable to load message history", it) }
                .getOrDefault(emptyList())
            _messages.value = mergeMessages(storedMessages, _messages.value)
            historyReady.complete(Unit)
        }
        scope.launch {
            _nodes.value = runCatching { nodeStore.loadNodes() }
                .onFailure { Log.e(TAG, "Unable to load node history", it) }
                .getOrDefault(emptyList())
            nodesReady.complete(Unit)
        }
        backends.values.forEach { backend ->
            backend.incomingMessages
                .onEach(::appendMessage)
                .launchIn(scope)
            backend.nodes
                .onEach(::persistNodes)
                .launchIn(scope)
            backend.traceRouteResults
                .onEach(::updateTraceRoute)
                .launchIn(scope)
        }
    }

    fun selectNetwork(network: MeshNetwork) {
        _selectedNetwork.value = network
        profileStore.saveSelectedNetwork(network)
    }

    fun connect(device: RadioDevice): Result<Unit> {
        scanner.stop()
        return connect(
            ConnectionProfileStore.Profile(
                network = device.network,
                transport = ConnectionProfileStore.Transport.BLE,
                address = device.address,
                name = device.name,
                enabled = true,
            ),
        )
    }

    fun connectMeshtasticTcp(endpoint: String): Result<Unit> {
        scanner.stop()
        val normalized = endpoint.trim()
        return connect(
            ConnectionProfileStore.Profile(
                network = MeshNetwork.MESHTASTIC,
                transport = ConnectionProfileStore.Transport.TCP,
                address = normalized,
                name = "TCP $normalized",
                enabled = true,
            ),
        )
    }

    fun connect(profile: ConnectionProfileStore.Profile): Result<Unit> {
        val backend = backends.getValue(profile.network)
        val result = backend.add(profile, makeOutbound = true)
        if (result.isSuccess) {
            val saved = profileStore.activate(profile, makeOutbound = true)
            backend.setOutbound(saved)
            selectDevice(saved)
            selectNetwork(profile.network)
            refreshConnectionHistory()
            refreshReconnectTargets()
            MeshConnectionService.start(context)
        }
        return result
    }

    fun disconnect(network: MeshNetwork) {
        profileStore.disable(network)
        backends.getValue(network).disconnect()
        _selectedDeviceIds.value = _selectedDeviceIds.value + (network to null)
        refreshConnectionHistory()
        refreshReconnectTargets()
    }

    fun disconnect(profile: ConnectionProfileStore.Profile) {
        profileStore.disable(profile)
        backends.getValue(profile.network).remove(profile)
        val replacement = profileStore.loadOutbound(profile.network)
        if (replacement == null) {
            _selectedDeviceIds.value = _selectedDeviceIds.value + (profile.network to null)
        } else {
            selectDevice(replacement)
        }
        refreshConnectionHistory()
        refreshReconnectTargets()
    }

    /**
     * Select one radio for the message view and for message transmission.
     * All other connected radios continue to receive messages.
     */
    fun selectDevice(profile: ConnectionProfileStore.Profile): Result<Unit> {
        val result = backends.getValue(profile.network).setOutbound(profile)
        if (result.isSuccess) {
            profileStore.setOutbound(profile)
            val id = MultiDeviceBackend.targetId(profile)
            _selectedDeviceIds.value = _selectedDeviceIds.value + (profile.network to id)
            _selectedConversationIds.value = _selectedConversationIds.value +
                (profile.network to profileStore.loadConversation(profile.network, id))
            refreshConnectionHistory()
        }
        return result
    }

    fun selectChannel(network: MeshNetwork, index: Int) {
        backends.getValue(network).selectChannel(index)
        selectConversation(network, "channel:$index")
    }

    fun selectContact(network: MeshNetwork, contactId: String) {
        val normalized = normalizeContactId(network, contactId) ?: return
        selectConversation(network, "contact:$normalized")
    }

    fun send(text: String): Result<Unit> =
        if (text.isBlank()) {
            Result.failure(IllegalArgumentException("Message cannot be empty"))
        } else {
            send(_selectedNetwork.value, selectedConversation(_selectedNetwork.value), text.trim())
        }

    fun send(network: MeshNetwork, channel: Int, text: String): Result<Unit> =
        send(network, "channel:$channel", text)

    fun send(network: MeshNetwork, conversationId: String, text: String): Result<Unit> {
        return if (text.isBlank()) {
            Result.failure(IllegalArgumentException("Message cannot be empty"))
        } else {
            val backend = backends.getValue(network)
            when {
                conversationId.startsWith("channel:") -> {
                    val channel = conversationId.substringAfter("channel:").toIntOrNull()
                        ?: return Result.failure(IllegalArgumentException("Invalid channel"))
                    backend.sendChannelMessage(text.trim(), channel)
                }
                conversationId.startsWith("contact:") -> {
                    val contactId = conversationId.substringAfter("contact:")
                    backend.sendDirectMessage(text.trim(), contactId)
                }
                else -> Result.failure(IllegalArgumentException("Invalid conversation"))
            }
        }
    }

    fun send(
        network: MeshNetwork,
        deviceId: String,
        conversationId: String,
        text: String,
    ): Result<Unit> {
        if (text.isBlank()) {
            return Result.failure(IllegalArgumentException("Message cannot be empty"))
        }
        val backend = backends.getValue(network)
        return when {
            conversationId.startsWith("channel:") -> {
                val channel = conversationId.substringAfter("channel:").toIntOrNull()
                    ?: return Result.failure(IllegalArgumentException("Invalid channel"))
                backend.sendChannelMessage(deviceId, text.trim(), channel)
            }
            conversationId.startsWith("contact:") -> backend.sendDirectMessage(
                deviceId,
                text.trim(),
                conversationId.substringAfter("contact:"),
            )
            else -> Result.failure(IllegalArgumentException("Invalid conversation"))
        }
    }

    fun channelName(network: MeshNetwork, channel: Int): String =
        backends.getValue(network).channels.value
            .firstOrNull { it.index == channel }
            ?.name
            ?: "Channel $channel"

    fun maxMessageBytes(network: MeshNetwork): Int = backends.getValue(network).maxMessageBytes

    fun traceRoute(network: MeshNetwork, deviceId: String, contactId: String): Result<Unit> =
        backends.getValue(network).traceRoute(deviceId, contactId)

    fun conversationName(network: MeshNetwork, conversationId: String): String =
        when {
            conversationId.startsWith("channel:") -> {
                val channel = conversationId.substringAfter("channel:").toIntOrNull() ?: 0
                "#$channel ${channelName(network, channel)}"
            }
            conversationId.startsWith("contact:") -> {
                val id = conversationId.substringAfter("contact:")
                backends.getValue(network).contacts.value.firstOrNull { it.id == id }?.name
                    ?: _nodes.value.firstOrNull {
                        it.network == network &&
                            it.deviceId == _selectedDeviceIds.value[network] &&
                            it.id == id
                    }?.name
                    ?: if (network == MeshNetwork.MESHTASTIC) {
                        runCatching {
                            "!${id.toUInt().toString(16).padStart(8, '0')}"
                        }.getOrDefault(id)
                    } else {
                        id.take(12)
                    }
            }
            else -> conversationId
        }

    fun startAutoReconnect() {
        if (autoReconnectStarted) return
        autoReconnectStarted = true
        refreshReconnectTargets()
        MeshNetwork.entries.forEach { network ->
            val outbound = profileStore.loadOutbound(network)
            profileStore.loadEnabled(network).forEach { profile ->
                backends.getValue(network).add(
                    profile,
                    makeOutbound = outbound?.let {
                        it.transport == profile.transport && it.address == profile.address
                    } == true,
                )
            }
            outbound?.let(::selectDevice)
        }
        if (_reconnectTargets.value.isNotEmpty()) MeshConnectionService.start(context)
    }

    fun close() {
        scanner.stop()
        backends.values.forEach(MessagingBackend::disconnect)
        scope.cancel()
        messageStore.close()
        nodeStore.close()
        packetLogger.close()
    }

    private fun appendMessage(message: UnifiedMessage) {
        scope.launch {
            historyReady.await()
            historyMutex.withLock {
                val newInMemory = _messages.value.none {
                    it.network == message.network &&
                        it.deviceId == message.deviceId &&
                        it.id == message.id
                }
                val newInDatabase = runCatching { messageStore.save(message) }
                    .onFailure { Log.e(TAG, "Unable to save message ${message.id}", it) }
                    .getOrNull()
                _messages.value = mergeMessages(_messages.value, listOf(message))
                if (newInDatabase ?: newInMemory) _messageEvents.emit(message)
            }
        }
    }

    private fun mergeMessages(
        first: List<UnifiedMessage>,
        second: List<UnifiedMessage>,
    ): List<UnifiedMessage> =
        (first + second)
            .associateBy { message -> Triple(message.network, message.deviceId, message.id) }
            .values
            .sortedBy(UnifiedMessage::timestampMillis)
            .takeLast(MessageHistoryStore.MAX_STORED_MESSAGES)

    private fun updateTraceRoute(result: TraceRouteResult) {
        _traceRouteResults.value = (
            _traceRouteResults.value.filterNot {
                it.network == result.network &&
                    it.deviceId == result.deviceId &&
                    it.id == result.id
            } + result
            )
            .sortedBy(TraceRouteResult::startedAtMillis)
            .takeLast(MAX_TRACE_RESULTS)
    }

    private suspend fun persistNodes(incoming: List<MeshNode>) {
        if (incoming.isEmpty()) return
        nodesReady.await()
        nodeMutex.withLock {
            val current = _nodes.value.associateBy(::nodeKey).toMutableMap()
            val changed = incoming.mapNotNull { node ->
                val key = nodeKey(node)
                val existing = current[key]
                val merged = mergeNode(existing, node)
                current[key] = merged
                merged.takeIf { it != existing }
            }
            if (changed.isNotEmpty()) {
                _nodes.value = current.values.sortedWith(
                    compareBy<MeshNode>({ it.network.ordinal }, { it.deviceName }, { it.name }),
                )
                runCatching { nodeStore.saveNodes(changed) }
                    .onFailure { Log.e(TAG, "Unable to persist node directory", it) }
            }
        }
    }

    private fun mergeNode(existing: MeshNode?, incoming: MeshNode): MeshNode {
        if (existing == null) return incoming
        val incomingPositionIsNewer = incoming.hasPosition &&
            (existing.positionTimeMillis == null ||
                (incoming.positionTimeMillis ?: incoming.lastHeardMillis) >=
                existing.positionTimeMillis)
        return existing.copy(
            deviceName = incoming.deviceName.ifBlank { existing.deviceName },
            name = incoming.name.takeUnless { it.startsWith("!") } ?: existing.name,
            shortName = incoming.shortName ?: existing.shortName,
            publicKey = incoming.publicKey ?: existing.publicKey,
            hardware = incoming.hardware
                ?.takeUnless { it == "UNSET" }
                ?: existing.hardware,
            role = incoming.role ?: existing.role,
            channel = incoming.channel ?: existing.channel,
            viaMqtt = incoming.viaMqtt ?: existing.viaMqtt,
            messageable = incoming.messageable ?: existing.messageable,
            flags = incoming.flags ?: existing.flags,
            lastHeardMillis = maxOf(existing.lastHeardMillis, incoming.lastHeardMillis),
            snr = incoming.snr ?: existing.snr,
            rssi = incoming.rssi ?: existing.rssi,
            hopsAway = incoming.hopsAway ?: existing.hopsAway,
            latitude = if (incomingPositionIsNewer) incoming.latitude else existing.latitude,
            longitude = if (incomingPositionIsNewer) incoming.longitude else existing.longitude,
            altitudeMeters = if (incomingPositionIsNewer) {
                incoming.altitudeMeters
            } else {
                existing.altitudeMeters
            },
            positionTimeMillis = if (incomingPositionIsNewer) {
                incoming.positionTimeMillis ?: incoming.lastHeardMillis
            } else {
                existing.positionTimeMillis
            },
            precisionBits = if (incomingPositionIsNewer) {
                incoming.precisionBits
            } else {
                existing.precisionBits
            },
            source = incoming.source,
        )
    }

    private fun nodeKey(node: MeshNode): Triple<MeshNetwork, String, String> =
        Triple(node.network, node.deviceId, node.id)

    private fun selectConversation(network: MeshNetwork, conversationId: String) {
        _selectedConversationIds.value = _selectedConversationIds.value +
            (network to conversationId)
        profileStore.saveConversation(
            network,
            conversationId,
            _selectedDeviceIds.value[network],
        )
    }

    private fun selectedConversation(network: MeshNetwork): String =
        _selectedConversationIds.value[network] ?: "channel:0"

    private fun normalizeContactId(network: MeshNetwork, value: String): String? =
        when (network) {
            MeshNetwork.MESHCORE -> value.trim()
                .removePrefix("!")
                .lowercase()
                .takeIf {
                    it.length >= 12 &&
                        it.length % 2 == 0 &&
                        it.all { char -> char in '0'..'9' || char in 'a'..'f' }
                }
            MeshNetwork.MESHTASTIC -> {
                val raw = value.trim()
                val input = raw.removePrefix("!")
                runCatching {
                    if ((raw.startsWith("!") || input.any {
                            it.lowercaseChar() in 'a'..'f'
                        }) &&
                        input.length == 8 &&
                        input.all {
                            it.isDigit() || it.lowercaseChar() in 'a'..'f'
                        }
                    ) {
                        input.toUInt(16).toString()
                    } else {
                        input.toUInt().toString()
                    }
                }.getOrNull()
            }
        }

    private fun refreshReconnectTargets() {
        _reconnectTargets.value = MeshNetwork.entries
            .filterTo(mutableSetOf()) { profileStore.loadEnabled(it).isNotEmpty() }
    }

    private fun refreshConnectionHistory() {
        _connectionHistory.value = MeshNetwork.entries.associateWith(profileStore::loadAll)
    }

    companion object {
        private const val TAG = "DualMeshManager"
        private const val MAX_TRACE_RESULTS = 100
    }
}
