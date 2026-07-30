package io.dualmesh.app.backend

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.dualmesh.app.ble.BleRadioScanner
import io.dualmesh.app.ble.GattSession
import io.dualmesh.app.logging.PacketDirection
import io.dualmesh.app.logging.PacketLogger
import io.dualmesh.app.model.ConnectionState
import io.dualmesh.app.model.DeliveryState
import io.dualmesh.app.model.MeshNetwork
import io.dualmesh.app.model.MeshNode
import io.dualmesh.app.model.RadioChannel
import io.dualmesh.app.model.RadioContact
import io.dualmesh.app.model.UnifiedMessage
import io.dualmesh.app.protocol.textMetadata
import io.dualmesh.app.transport.MeshtasticTcpSession
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Data
import org.meshtastic.proto.Constants
import org.meshtastic.proto.Channel
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.ToRadio
import org.meshtastic.proto.User
import java.util.UUID
import kotlin.random.Random

class MeshtasticBackend(
    context: Context,
    private val packetLogger: PacketLogger,
    private val captureSource: String = "",
) :
    MessagingBackend,
    GattSession.Listener,
    MeshtasticTcpSession.Listener {
    override val network = MeshNetwork.MESHTASTIC
    override val maxMessageBytes = 220

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<UnifiedMessage>(extraBufferCapacity = 64)
    override val incomingMessages: SharedFlow<UnifiedMessage> = _incomingMessages.asSharedFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    override val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val _channels = MutableStateFlow(listOf(RadioChannel(0, "Primary", "PRIMARY")))
    override val channels: StateFlow<List<RadioChannel>> = _channels.asStateFlow()

    private val _contacts = MutableStateFlow<List<RadioContact>>(emptyList())
    override val contacts: StateFlow<List<RadioContact>> = _contacts.asStateFlow()

    private val _nodes = MutableStateFlow<List<MeshNode>>(emptyList())
    override val nodes: StateFlow<List<MeshNode>> = _nodes.asStateFlow()

    private val _channelCapacity = MutableStateFlow(MAX_CHANNELS)
    override val channelCapacity: StateFlow<Int> = _channelCapacity.asStateFlow()

    private val _selectedChannel = MutableStateFlow(0)
    override val selectedChannel: StateFlow<Int> = _selectedChannel.asStateFlow()

    private val session = GattSession(context, BleRadioScanner.MESHTASTIC_SERVICE, this)
    private val tcpSession = MeshtasticTcpSession(this)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val handshakeTimeout = Runnable {
        if (_connectionState.value == ConnectionState.CONNECTING) {
            disconnectActiveTransport()
            _connectionState.value = ConnectionState.ERROR
        }
    }
    private var activeTransport = Transport.NONE
    private var myNodeNum = 0
    private var reading = false
    private var configReady = false
    private var configNonce = 1

    override fun connect(address: String, name: String) {
        prepareConnection(Transport.BLE, name)
        tcpSession.disconnect()
        session.connect(address, requireBond = true)
    }

    fun connectTcp(value: String): Result<Unit> =
        MeshtasticTcpSession.Endpoint.parse(value).map { endpoint ->
            prepareConnection(Transport.TCP, "TCP $endpoint")
            session.disconnect()
            _connectionState.value = ConnectionState.CONNECTING
            tcpSession.connect(endpoint)
        }

    private fun prepareConnection(transport: Transport, name: String) {
        clearHandshakeTimeout()
        myNodeNum = 0
        reading = false
        configReady = false
        configNonce = generateSequence { Random.nextInt(1, Int.MAX_VALUE) }
            .first { it != NODELESS_CONFIG_ID }
        _channels.value = listOf(RadioChannel(0, "Primary", "PRIMARY"))
        _contacts.value = emptyList()
        _nodes.value = emptyList()
        _selectedChannel.value = 0
        activeTransport = transport
        _connectedDeviceName.value = name
    }

    override fun disconnect() {
        clearHandshakeTimeout()
        activeTransport = Transport.NONE
        session.disconnect()
        tcpSession.disconnect()
        _connectedDeviceName.value = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override fun selectChannel(index: Int) {
        if (_channels.value.any { it.index == index }) {
            _selectedChannel.value = index
        }
    }

    override fun sendChannelMessage(text: String, channel: Int): Result<Unit> {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            return Result.failure(IllegalStateException("Meshtastic radio is not ready"))
        }
        val data = Data(
            portnum = PortNum.TEXT_MESSAGE_APP,
            payload = text.encodeToByteArray().toByteString(),
        )
        if (Data.ADAPTER.encodedSize(data) > Constants.DATA_PAYLOAD_LEN.value) {
            return Result.failure(
                IllegalArgumentException("Meshtastic message exceeds the radio payload limit"),
            )
        }
        val packetId = Random.nextInt(1, Int.MAX_VALUE)
        val packet = MeshPacket(
            from = myNodeNum,
            to = -1,
            channel = channel,
            decoded = data,
            id = packetId,
            hop_limit = 3,
            hop_start = 3,
            want_ack = false,
        )
        if (!sendTransport(ToRadio(packet = packet).encode())) {
            return Result.failure(IllegalStateException("Meshtastic transport is unavailable"))
        }
        _incomingMessages.tryEmit(
            UnifiedMessage(
                id = packetId.toString(),
                network = network,
                conversationId = "channel:$channel",
                sender = "You",
                text = text,
                timestampMillis = System.currentTimeMillis(),
                outgoing = true,
                deliveryState = DeliveryState.SENT_TO_RADIO,
            ),
        )
        return Result.success(Unit)
    }

    override fun sendDirectMessage(text: String, contactId: String): Result<Unit> {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            return Result.failure(IllegalStateException("Meshtastic radio is not ready"))
        }
        val destination = parseNodeId(contactId)
            ?: return Result.failure(IllegalArgumentException("Invalid Meshtastic node ID"))
        if (destination == myNodeNum) {
            return Result.failure(IllegalArgumentException("Cannot send a direct message to this node"))
        }
        val data = Data(
            portnum = PortNum.TEXT_MESSAGE_APP,
            payload = text.encodeToByteArray().toByteString(),
        )
        if (Data.ADAPTER.encodedSize(data) > Constants.DATA_PAYLOAD_LEN.value) {
            return Result.failure(
                IllegalArgumentException("Meshtastic message exceeds the radio payload limit"),
            )
        }
        val packetId = Random.nextInt(1, Int.MAX_VALUE)
        val packet = MeshPacket(
            from = myNodeNum,
            to = destination,
            channel = 0,
            decoded = data,
            id = packetId,
            hop_limit = 3,
            hop_start = 3,
            want_ack = true,
        )
        if (!sendTransport(ToRadio(packet = packet).encode())) {
            return Result.failure(IllegalStateException("Meshtastic transport is unavailable"))
        }
        val normalizedId = destination.toUInt().toString()
        _incomingMessages.tryEmit(
            UnifiedMessage(
                id = packetId.toUInt().toString(),
                network = network,
                conversationId = "contact:$normalizedId",
                sender = "You",
                text = text,
                timestampMillis = System.currentTimeMillis(),
                outgoing = true,
                deliveryState = DeliveryState.SENT_TO_RADIO,
            ),
        )
        return Result.success(Unit)
    }

    override fun onConnecting() {
        if (activeTransport == Transport.BLE) {
            _connectionState.value = ConnectionState.CONNECTING
        }
    }

    override fun onReady() {
        if (activeTransport != Transport.BLE) return
        mainHandler.postDelayed(handshakeTimeout, HANDSHAKE_TIMEOUT_MS)
        session.enableNotifications(FROM_NUM_CHARACTERISTIC)
        sendTransport(ToRadio(want_config_id = configNonce).encode())
        drain()
    }

    override fun onDisconnected() {
        if (activeTransport != Transport.BLE) return
        clearHandshakeTimeout()
        reading = false
        configReady = false
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override fun onError(message: String) {
        if (activeTransport != Transport.BLE) return
        clearHandshakeTimeout()
        reading = false
        configReady = false
        _connectionState.value = ConnectionState.ERROR
    }

    override fun onNotification(characteristic: UUID, value: ByteArray) {
        if (activeTransport == Transport.BLE && characteristic == FROM_NUM_CHARACTERISTIC) drain()
    }

    override fun onRead(characteristic: UUID, value: ByteArray) {
        if (activeTransport != Transport.BLE || characteristic != FROM_RADIO_CHARACTERISTIC) return
        reading = false
        if (value.isEmpty()) return
        val decoded = runCatching { FromRadio.ADAPTER.decode(value) }
        packetLogger.log(
            network = network,
            direction = PacketDirection.RX,
            transport = captureTransport("BLE"),
            payload = value,
            summary = decoded.getOrNull()?.let(::describeFromRadio) ?: "Invalid FromRadio",
        )
        decoded.onSuccess(::handleFromRadio)
        drain()
    }

    private fun drain() {
        if (activeTransport != Transport.BLE || reading) return
        reading = true
        session.read(FROM_RADIO_CHARACTERISTIC)
    }

    private fun handleFromRadio(fromRadio: FromRadio) {
        fromRadio.channel?.let(::updateChannel)
        fromRadio.node_info?.let { nodeInfo ->
            val nodeNum = nodeInfo.num
            val user = nodeInfo.user
            updateNode(
                MeshNode(
                    network = network,
                    id = nodeNum.toUInt().toString(),
                    name = user?.long_name?.takeIf(String::isNotBlank)
                        ?: user?.short_name?.takeIf(String::isNotBlank)
                        ?: nodeLabel(nodeNum),
                    shortName = user?.short_name?.takeIf(String::isNotBlank),
                    publicKey = user?.public_key?.takeIf { it.size > 0 }?.hex(),
                    hardware = user?.hw_model?.name,
                    role = user?.role?.name,
                    channel = nodeInfo.channel,
                    viaMqtt = nodeInfo.via_mqtt,
                    messageable = user?.is_unmessagable != true,
                    lastHeardMillis = nodeInfo.last_heard.toLong()
                        .and(0xffffffffL)
                        .times(1000),
                    snr = nodeInfo.snr,
                    hopsAway = nodeInfo.hops_away,
                    latitude = nodeInfo.position?.latitude_i?.div(10_000_000.0),
                    longitude = nodeInfo.position?.longitude_i?.div(10_000_000.0),
                    altitudeMeters = nodeInfo.position?.altitude,
                    positionTimeMillis = nodeInfo.position?.positionTimeMillis(),
                    precisionBits = nodeInfo.position?.precision_bits,
                    source = "Meshtastic NodeInfo",
                ),
            )
            if (nodeNum != 0 && nodeNum != myNodeNum && user?.is_unmessagable != true) {
                val id = nodeNum.toUInt().toString()
                val fallback = nodeLabel(nodeNum)
                val name = user?.long_name?.takeIf(String::isNotBlank)
                    ?: user?.short_name?.takeIf(String::isNotBlank)
                    ?: fallback
                val updated = _contacts.value.filterNot { it.id == id } + RadioContact(id, name)
                _contacts.value = updated.sortedBy { it.name.lowercase() }
            }
        }
        fromRadio.my_info?.let {
            myNodeNum = it.my_node_num
            _nodes.value = _nodes.value.map { node ->
                if (parseNodeId(node.id) == myNodeNum) {
                    node.copy(messageable = false)
                } else {
                    node
                }
            }
            _contacts.value = _contacts.value.filterNot { contact ->
                parseNodeId(contact.id) == myNodeNum
            }
            if (configReady) markConnected()
        }
        if (fromRadio.config_complete_id == configNonce) {
            configReady = true
            val preferred = _channels.value.firstOrNull { it.role == Channel.Role.PRIMARY.name }
                ?: _channels.value.firstOrNull()
            preferred?.let { _selectedChannel.value = it.index }
            if (myNodeNum != 0) markConnected()
        }
        val packet = fromRadio.packet ?: return
        val decoded = packet.decoded ?: return
        when (decoded.portnum) {
            PortNum.POSITION_APP -> {
                runCatching { Position.ADAPTER.decode(decoded.payload) }
                    .onSuccess { position -> updatePosition(packet, position) }
                return
            }
            PortNum.NODEINFO_APP -> {
                runCatching { User.ADAPTER.decode(decoded.payload) }
                    .onSuccess { user -> updateUser(packet, user) }
                return
            }
            PortNum.TEXT_MESSAGE_APP -> Unit
            else -> return
        }
        val metadata = decoded.textMetadata()
        val text = metadata.text
        if (text.isBlank()) return
        val timestamp = packet.rx_time.toLong().and(0xffffffffL)
            .takeIf { it > 0 }
            ?.times(1000)
            ?: System.currentTimeMillis()
        val outgoing = packet.from == myNodeNum
        val isDirect = packet.to != BROADCAST_NODE
        val counterpart = if (outgoing) packet.to else packet.from
        val conversationId = if (isDirect) {
            val contactId = counterpart.toUInt().toString()
            if (_contacts.value.none { it.id == contactId }) {
                _contacts.value = (
                    _contacts.value + RadioContact(contactId, nodeLabel(counterpart))
                ).sortedBy { it.name.lowercase() }
            }
            "contact:$contactId"
        } else {
            "channel:${packet.channel}"
        }
        val sender = if (outgoing) {
            "You"
        } else {
            _contacts.value.firstOrNull { it.id == packet.from.toUInt().toString() }?.name
                ?: nodeLabel(packet.from)
        }
        _incomingMessages.tryEmit(
            UnifiedMessage(
                id = packet.id.toUInt().toString(),
                network = network,
                conversationId = conversationId,
                sender = sender,
                text = text,
                timestampMillis = timestamp,
                outgoing = outgoing,
                deliveryState = DeliveryState.RECEIVED,
                reactionToMessageId = metadata.reactionToMessageId,
                reactionEmoji = metadata.reactionEmoji,
            ),
        )
    }

    private fun markConnected() {
        clearHandshakeTimeout()
        _connectionState.value = ConnectionState.CONNECTED
    }

    private fun updateUser(packet: MeshPacket, user: User) {
        val nodeNum = packet.from
        updateNode(
            MeshNode(
                network = network,
                id = nodeNum.toUInt().toString(),
                name = user.long_name.takeIf(String::isNotBlank)
                    ?: user.short_name.takeIf(String::isNotBlank)
                    ?: nodeLabel(nodeNum),
                shortName = user.short_name.takeIf(String::isNotBlank),
                publicKey = user.public_key.takeIf { it.size > 0 }?.hex(),
                hardware = user.hw_model.name,
                role = user.role.name,
                channel = packet.channel,
                viaMqtt = packet.via_mqtt,
                messageable = user.is_unmessagable != true,
                lastHeardMillis = packetTimeMillis(packet),
                snr = packet.rx_snr,
                rssi = packet.rx_rssi.takeIf { it != 0 },
                hopsAway = packetHops(packet),
                source = "Meshtastic NODEINFO_APP",
            ),
        )
    }

    private fun updatePosition(packet: MeshPacket, position: Position) {
        val latitude = position.latitude_i?.div(10_000_000.0)
        val longitude = position.longitude_i?.div(10_000_000.0)
        if (latitude == null || longitude == null) return
        val nodeNum = packet.from
        updateNode(
            MeshNode(
                network = network,
                id = nodeNum.toUInt().toString(),
                name = _nodes.value.firstOrNull {
                    it.id == nodeNum.toUInt().toString()
                }?.name ?: nodeLabel(nodeNum),
                lastHeardMillis = packetTimeMillis(packet),
                snr = packet.rx_snr,
                rssi = packet.rx_rssi.takeIf { it != 0 },
                hopsAway = packetHops(packet),
                latitude = latitude,
                longitude = longitude,
                altitudeMeters = position.altitude,
                positionTimeMillis = position.positionTimeMillis()
                    ?: packetTimeMillis(packet),
                precisionBits = position.precision_bits,
                source = "Meshtastic POSITION_APP",
            ),
        )
    }

    private fun updateNode(incoming: MeshNode) {
        if (incoming.id == "0") return
        val node = if (myNodeNum != 0 && parseNodeId(incoming.id) == myNodeNum) {
            incoming.copy(messageable = false)
        } else {
            incoming
        }
        val existing = _nodes.value.firstOrNull { it.id == node.id }
        val merged = if (existing == null) {
            node
        } else {
            existing.copy(
                name = node.name.takeUnless { it.startsWith("!") } ?: existing.name,
                shortName = node.shortName ?: existing.shortName,
                publicKey = node.publicKey ?: existing.publicKey,
                hardware = node.hardware ?: existing.hardware,
                role = node.role ?: existing.role,
                channel = node.channel ?: existing.channel,
                viaMqtt = node.viaMqtt ?: existing.viaMqtt,
                messageable = node.messageable ?: existing.messageable,
                flags = node.flags ?: existing.flags,
                lastHeardMillis = maxOf(existing.lastHeardMillis, node.lastHeardMillis),
                snr = node.snr ?: existing.snr,
                rssi = node.rssi ?: existing.rssi,
                hopsAway = node.hopsAway ?: existing.hopsAway,
                latitude = node.latitude ?: existing.latitude,
                longitude = node.longitude ?: existing.longitude,
                altitudeMeters = node.altitudeMeters ?: existing.altitudeMeters,
                positionTimeMillis = node.positionTimeMillis ?: existing.positionTimeMillis,
                precisionBits = node.precisionBits ?: existing.precisionBits,
                source = node.source,
            )
        }
        _nodes.value = (_nodes.value.filterNot { it.id == node.id } + merged)
            .sortedBy { it.name.lowercase() }
    }

    private fun packetTimeMillis(packet: MeshPacket): Long =
        packet.rx_time.toLong().and(0xffffffffL)
            .takeIf { it > 0 }
            ?.times(1000)
            ?: System.currentTimeMillis()

    private fun packetHops(packet: MeshPacket): Int? =
        packet.hop_start.takeIf { it > 0 }
            ?.let { (it - packet.hop_limit).coerceAtLeast(0) }

    private fun Position.positionTimeMillis(): Long? =
        timestamp.toLong().and(0xffffffffL)
            .takeIf { it > 0 }
            ?.times(1000)
            ?: time.toLong().and(0xffffffffL)
                .takeIf { it > 0 }
                ?.times(1000)

    private fun updateChannel(channel: Channel) {
        val withoutChannel = _channels.value.filterNot { it.index == channel.index }
        if (channel.role == Channel.Role.DISABLED) {
            _channels.value = withoutChannel
            return
        }
        val role = channel.role.name
        val fallbackName = when (channel.role) {
            Channel.Role.PRIMARY -> "Primary"
            Channel.Role.SECONDARY -> "Secondary ${channel.index}"
            Channel.Role.DISABLED -> "Channel ${channel.index}"
        }
        val name = channel.settings?.name?.takeIf(String::isNotBlank) ?: fallbackName
        _channels.value = (withoutChannel + RadioChannel(channel.index, name, role))
            .sortedBy(RadioChannel::index)
    }

    private fun clearHandshakeTimeout() {
        mainHandler.removeCallbacks(handshakeTimeout)
    }

    override fun onTcpConnected() {
        if (activeTransport != Transport.TCP) return
        mainHandler.postDelayed(handshakeTimeout, HANDSHAKE_TIMEOUT_MS)
        sendTransport(ToRadio(want_config_id = configNonce).encode())
    }

    override fun onTcpPacket(value: ByteArray) {
        if (activeTransport != Transport.TCP) return
        val decoded = runCatching { FromRadio.ADAPTER.decode(value) }
        packetLogger.log(
            network = network,
            direction = PacketDirection.RX,
            transport = captureTransport("TCP"),
            payload = value,
            summary = decoded.getOrNull()?.let(::describeFromRadio) ?: "Invalid FromRadio",
        )
        decoded.onSuccess(::handleFromRadio)
    }

    override fun onTcpDisconnected() {
        if (activeTransport != Transport.TCP) return
        clearHandshakeTimeout()
        configReady = false
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override fun onTcpError(message: String) {
        if (activeTransport != Transport.TCP) return
        clearHandshakeTimeout()
        configReady = false
        _connectionState.value = ConnectionState.ERROR
    }

    private fun sendTransport(payload: ByteArray): Boolean {
        val transport = activeTransport
        val sent = when (transport) {
            Transport.BLE -> {
                session.write(TO_RADIO_CHARACTERISTIC, payload)
                true
            }
            Transport.TCP -> tcpSession.send(payload)
            Transport.NONE -> false
        }
        if (sent) {
            packetLogger.log(
                network = network,
                direction = PacketDirection.TX,
                transport = captureTransport(transport.name),
                payload = payload,
                summary = describeToRadio(payload),
            )
        }
        return sent
    }

    private fun describeToRadio(payload: ByteArray): String =
        runCatching { ToRadio.ADAPTER.decode(payload) }
            .getOrNull()
            ?.let { toRadio ->
                val packet = toRadio.packet
                when {
                    packet != null -> {
                        "MeshPacket id=${packet.id.toUInt()} to=${nodeLabel(packet.to)} " +
                            "ch=${packet.channel} port=${packet.decoded?.portnum?.name ?: "encrypted"}"
                    }
                    toRadio.want_config_id != null ->
                        "want_config id=${toRadio.want_config_id}"
                    else -> "ToRadio"
                }
            }
            ?: "Invalid ToRadio"

    private fun describeFromRadio(fromRadio: FromRadio): String {
        fromRadio.packet?.let { packet ->
            packet.decoded?.let { data ->
                if (data.portnum == PortNum.POSITION_APP) {
                    runCatching { Position.ADAPTER.decode(data.payload) }
                        .getOrNull()
                        ?.let { position ->
                            return "position from=${nodeLabel(packet.from)} " +
                                "lat=${position.latitude_i?.div(10_000_000.0)} " +
                                "lon=${position.longitude_i?.div(10_000_000.0)}"
                        }
                }
                if (data.portnum == PortNum.NODEINFO_APP) {
                    runCatching { User.ADAPTER.decode(data.payload) }
                        .getOrNull()
                        ?.let { user ->
                            return "node_info ${nodeLabel(packet.from)} " +
                                (user.long_name.takeIf(String::isNotBlank) ?: user.short_name)
                        }
                }
            }
            return "MeshPacket id=${packet.id.toUInt()} from=${nodeLabel(packet.from)} " +
                "to=${nodeLabel(packet.to)} ch=${packet.channel} " +
                "port=${packet.decoded?.portnum?.name ?: "encrypted"}"
        }
        fromRadio.node_info?.let { return "node_info ${nodeLabel(it.num)}" }
        fromRadio.channel?.let {
            return "channel index=${it.index} role=${it.role.name}"
        }
        fromRadio.my_info?.let { return "my_info ${nodeLabel(it.my_node_num)}" }
        fromRadio.config_complete_id?.let { return "config_complete id=$it" }
        return "FromRadio"
    }

    private fun disconnectActiveTransport() {
        when (activeTransport) {
            Transport.BLE -> session.disconnect()
            Transport.TCP -> tcpSession.disconnect()
            Transport.NONE -> Unit
        }
    }

    companion object {
        private const val HANDSHAKE_TIMEOUT_MS = 20_000L
        private const val NODELESS_CONFIG_ID = 69420
        private const val MAX_CHANNELS = 8
        private const val BROADCAST_NODE = -1
        private val TO_RADIO_CHARACTERISTIC: UUID =
            UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
        private val FROM_NUM_CHARACTERISTIC: UUID =
            UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")
        private val FROM_RADIO_CHARACTERISTIC: UUID =
            UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
    }

    private fun parseNodeId(value: String): Int? {
        val normalized = value.trim().removePrefix("!")
        return runCatching {
            if (normalized.length == 8 && normalized.any { it.lowercaseChar() in 'a'..'f' }) {
                normalized.toUInt(16).toInt()
            } else {
                normalized.toUInt().toInt()
            }
        }.getOrNull()
    }

    private fun nodeLabel(nodeNum: Int): String =
        "!${nodeNum.toUInt().toString(16).padStart(8, '0')}"

    private fun captureTransport(transport: String): String =
        if (captureSource.isBlank()) transport else "$transport ${captureSource.take(18)}"

    private enum class Transport {
        NONE,
        BLE,
        TCP,
    }
}
