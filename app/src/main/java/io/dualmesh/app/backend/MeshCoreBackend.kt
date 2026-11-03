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
import io.dualmesh.app.model.TraceRouteHop
import io.dualmesh.app.model.TraceRouteResult
import io.dualmesh.app.model.TraceRouteState
import io.dualmesh.app.model.UnifiedMessage
import io.dualmesh.app.protocol.MeshCoreProtocol
import io.dualmesh.app.settings.MeshCoreSettingsController
import io.dualmesh.app.settings.SettingsScheduler
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import kotlin.random.Random

class MeshCoreBackend(
    context: Context,
    private val packetLogger: PacketLogger,
    private val captureSource: String = "",
) : MessagingBackend, GattSession.Listener {
    override val network = MeshNetwork.MESHCORE
    override val maxMessageBytes = 127

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<UnifiedMessage>(extraBufferCapacity = 64)
    override val incomingMessages: SharedFlow<UnifiedMessage> = _incomingMessages.asSharedFlow()

    private val _traceRouteResults =
        MutableSharedFlow<TraceRouteResult>(extraBufferCapacity = 16)
    override val traceRouteResults: SharedFlow<TraceRouteResult> =
        _traceRouteResults.asSharedFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    override val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val _channels = MutableStateFlow(listOf(RadioChannel(0, "Public")))
    override val channels: StateFlow<List<RadioChannel>> = _channels.asStateFlow()

    private val _contacts = MutableStateFlow<List<RadioContact>>(emptyList())
    override val contacts: StateFlow<List<RadioContact>> = _contacts.asStateFlow()

    private val _nodes = MutableStateFlow<List<MeshNode>>(emptyList())
    override val nodes: StateFlow<List<MeshNode>> = _nodes.asStateFlow()

    private val _channelCapacity = MutableStateFlow(MeshCoreProtocol.MAX_CHANNELS)
    override val channelCapacity: StateFlow<Int> = _channelCapacity.asStateFlow()

    private val _selectedChannel = MutableStateFlow(0)
    override val selectedChannel: StateFlow<Int> = _selectedChannel.asStateFlow()

    private val session = GattSession(context, BleRadioScanner.MESHCORE_SERVICE, this)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val settingsController: MeshCoreSettingsController = MeshCoreSettingsController(
        send = { frame ->
            if (_connectionState.value != ConnectionState.CONNECTED) false else {
                writeFrame(frame, "Radio settings command ${frame[0].toInt() and 255}")
                true
            }
        },
        ready = {
            _connectionState.value == ConnectionState.CONNECTED && !waitingForDeviceInfo &&
                channelScanIndex == null && !waitingForContacts && !waitingForSetTime && pendingTraces.isEmpty() && !nodeActionsController.busy
        },
        channelCapacity = { _channelCapacity.value },
        scheduler = SettingsScheduler { delay, action ->
            val callback = Runnable(action)
            mainHandler.postDelayed(callback, delay)
            ({ mainHandler.removeCallbacks(callback) })
        },
        onReadback = { frame ->
            if (frame[0].toInt() == RESPONSE_SELF_INFO) selfName = parseSelfName(frame)
            MeshCoreProtocol.parseChannelInfo(frame)?.let { info ->
                _channels.value = (_channels.value.filterNot { it.index == info.index } +
                    if (info.configured) listOf(RadioChannel(info.index, info.name)) else emptyList())
                    .sortedBy(RadioChannel::index)
            }
        },
    )
    private val nodeActionsController: io.dualmesh.app.nodes.MeshCoreNodeActions = io.dualmesh.app.nodes.MeshCoreNodeActions(
        send = { frame ->
            if (_connectionState.value != ConnectionState.CONNECTED) false else {
                writeFrame(frame, "Node action command ${frame[0].toInt() and 255}")
                true
            }
        },
        ready = {
            _connectionState.value == ConnectionState.CONNECTED && !waitingForDeviceInfo &&
                channelScanIndex == null && !waitingForContacts && !waitingForSetTime &&
                pendingTraces.isEmpty() && !settingsController.busy
        },
        scheduler = SettingsScheduler { delay, action ->
            val callback = Runnable(action)
            mainHandler.postDelayed(callback, delay)
            ({ mainHandler.removeCallbacks(callback) })
        },
    )
    override val nodeActions: io.dualmesh.app.nodes.NodeActions get() = nodeActionsController
    override val settingsAccess: io.dualmesh.app.settings.RadioSettingsAccess get() = settingsController
    private val handshakeTimeout = Runnable {
        if (_connectionState.value == ConnectionState.CONNECTING) {
            session.disconnect()
            _connectionState.value = ConnectionState.ERROR
        }
    }
    private val deviceInfoTimeout = Runnable {
        if (waitingForDeviceInfo) {
            waitingForDeviceInfo = false
            beginChannelScan(MeshCoreProtocol.MAX_CHANNELS)
        }
    }
    private val channelQueryTimeout = Runnable {
        channelScanIndex?.let { advanceChannelScan(it + 1) }
    }
    private val contactQueryTimeout = Runnable {
        if (waitingForContacts) finishStartup()
    }
    private var selfName: String? = null
    private var waitingForDeviceInfo = false
    private var channelScanIndex: Int? = null
    private var channelSlotCount = MeshCoreProtocol.MAX_CHANNELS
    private var waitingForSetTime = false
    private var waitingForContacts = false
    private val contactDetails = mutableMapOf<String, MeshCoreProtocol.ContactInfo>()
    private val pendingTraces = mutableMapOf<UInt, PendingTrace>()

    private data class PendingTrace(
        val result: TraceRouteResult,
        val forwardCount: Int,
    )

    override fun connect(address: String, name: String) {
        nodeActionsController.reset()
        settingsController.reset()
        clearStartupCallbacks()
        failPendingTraces("A new MeshCore connection started.")
        waitingForDeviceInfo = false
        channelScanIndex = null
        channelSlotCount = MeshCoreProtocol.MAX_CHANNELS
        _channelCapacity.value = MeshCoreProtocol.MAX_CHANNELS
        waitingForSetTime = false
        waitingForContacts = false
        _channels.value = listOf(RadioChannel(0, "Public"))
        _contacts.value = emptyList()
        _nodes.value = emptyList()
        contactDetails.clear()
        _selectedChannel.value = 0
        _connectedDeviceName.value = name
        session.connect(address)
    }

    override fun disconnect() {
        nodeActionsController.reset()
        settingsController.reset()
        clearHandshakeTimeout()
        clearStartupCallbacks()
        failPendingTraces("The MeshCore radio disconnected.")
        session.disconnect()
        _connectedDeviceName.value = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override fun selectChannel(index: Int) {
        if (_channels.value.any { it.index == index }) {
            _selectedChannel.value = index
        }
    }

    override fun sendChannelMessage(text: String, channel: Int): Result<Unit> {
        if (settingsController.busy || nodeActionsController.busy) return Result.failure(IllegalStateException("Wait for the current radio operation to finish"))
        if (_connectionState.value != ConnectionState.CONNECTED) {
            return Result.failure(IllegalStateException("MeshCore radio is not ready"))
        }
        if (text.encodeToByteArray().size > maxMessageBytes) {
            return Result.failure(
                IllegalArgumentException("MeshCore messages are limited to $maxMessageBytes UTF-8 bytes"),
            )
        }
        val nowSeconds = (System.currentTimeMillis() / 1000).toInt()
        writeFrame(
            MeshCoreProtocol.channelMessage(channel, text, nowSeconds),
            "CMD_SEND_CHANNEL_TXT_MSG channel=$channel",
        )
        val sender = selfName ?: "You"
        _incomingMessages.tryEmit(
            UnifiedMessage(
                id = MeshCoreProtocol.messageId(
                    conversationId = "channel:$channel",
                    sender = sender,
                    text = text,
                    timestampMillis = nowSeconds * 1000L,
                ),
                network = network,
                conversationId = "channel:$channel",
                sender = sender,
                text = text,
                timestampMillis = nowSeconds * 1000L,
                outgoing = true,
                deliveryState = DeliveryState.SENT_TO_RADIO,
            ),
        )
        return Result.success(Unit)
    }

    override fun sendDirectMessage(text: String, contactId: String): Result<Unit> {
        if (settingsController.busy || nodeActionsController.busy) return Result.failure(IllegalStateException("Wait for the current radio operation to finish"))
        if (_connectionState.value != ConnectionState.CONNECTED) {
            return Result.failure(IllegalStateException("MeshCore radio is not ready"))
        }
        if (text.encodeToByteArray().size > maxMessageBytes) {
            return Result.failure(
                IllegalArgumentException("MeshCore messages are limited to $maxMessageBytes UTF-8 bytes"),
            )
        }
        val normalizedId = contactId.lowercase()
        val nowSeconds = (System.currentTimeMillis() / 1000).toInt()
        val frame = runCatching {
            MeshCoreProtocol.directMessage(normalizedId, text, nowSeconds)
        }.getOrElse { return Result.failure(it) }
        writeFrame(frame, "CMD_SEND_TXT_MSG contact=${normalizedId.take(12)}")
        val conversationId = "contact:$normalizedId"
        _incomingMessages.tryEmit(
            UnifiedMessage(
                id = MeshCoreProtocol.messageId(
                    conversationId = conversationId,
                    sender = selfName ?: "You",
                    text = text,
                    timestampMillis = nowSeconds * 1000L,
                ),
                network = network,
                conversationId = conversationId,
                sender = selfName ?: "You",
                text = text,
                timestampMillis = nowSeconds * 1000L,
                outgoing = true,
                deliveryState = DeliveryState.SENT_TO_RADIO,
            ),
        )
        return Result.success(Unit)
    }

    override fun traceRoute(contactId: String): Result<Unit> {
        if (settingsController.busy || nodeActionsController.busy) return Result.failure(IllegalStateException("Wait for the current radio operation to finish"))
        if (_connectionState.value != ConnectionState.CONNECTED) {
            return Result.failure(IllegalStateException("MeshCore radio is not ready"))
        }
        val normalizedId = contactId.lowercase()
        val contact = contactDetails[normalizedId]
            ?: contactDetails.values.singleOrNull { it.publicKey.startsWith(normalizedId) }
            ?: return Result.failure(
                IllegalArgumentException("MeshCore contact path is not available"),
            )
        val tracePath = runCatching { MeshCoreProtocol.tracePath(contact) }
            .getOrElse { return Result.failure(it) }
        val tag = generateSequence { Random.nextInt(1, Int.MAX_VALUE).toUInt() }
            .first { it !in pendingTraces }
        val frame = runCatching {
            MeshCoreProtocol.traceRequest(tag, tracePath.hashes, tracePath.hashSize)
        }.getOrElse { return Result.failure(it) }
        val result = TraceRouteResult(
            id = tag.toString(),
            network = network,
            targetId = contact.publicKey,
            targetName = contact.name,
            state = TraceRouteState.PENDING,
            startedAtMillis = System.currentTimeMillis(),
            detail = if (contact.outPath.isEmpty()) {
                "Tracing this MeshCore contact directly and back."
            } else {
                "Tracing the stored path to this MeshCore contact and back."
            },
        )
        pendingTraces[tag] = PendingTrace(
            result = result,
            forwardCount = tracePath.forwardCount,
        )
        _traceRouteResults.tryEmit(result)
        writeFrame(frame, "CMD_SEND_TRACE_PATH target=${normalizedId.take(12)} tag=$tag")
        mainHandler.postDelayed(
            {
                pendingTraces.remove(tag)?.let { pending ->
                    _traceRouteResults.tryEmit(
                        pending.result.copy(
                            state = TraceRouteState.TIMED_OUT,
                            completedAtMillis = System.currentTimeMillis(),
                            detail = "No trace response arrived before the timeout.",
                        ),
                    )
                }
            },
            TRACE_TIMEOUT_MS,
        )
        return Result.success(Unit)
    }

    override fun onConnecting() {
        _connectionState.value = ConnectionState.CONNECTING
    }

    override fun onReady() {
        mainHandler.postDelayed(handshakeTimeout, HANDSHAKE_TIMEOUT_MS)
        session.enableNotifications(TX_CHARACTERISTIC)
        writeFrame(MeshCoreProtocol.appStart(), "CMD_APP_START")
    }

    override fun onDisconnected() {
        if (Looper.myLooper() != Looper.getMainLooper()) { mainHandler.post { onDisconnected() }; return }
        nodeActionsController.reset()
        settingsController.reset()
        clearHandshakeTimeout()
        clearStartupCallbacks()
        failPendingTraces("The MeshCore radio disconnected.")
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override fun onError(message: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) { mainHandler.post { onError(message) }; return }
        nodeActionsController.reset()
        settingsController.reset()
        clearHandshakeTimeout()
        clearStartupCallbacks()
        failPendingTraces(message)
        _connectionState.value = ConnectionState.ERROR
    }

    override fun onNotification(characteristic: UUID, value: ByteArray) {
        if (characteristic != TX_CHARACTERISTIC || value.isEmpty()) return
        if (Looper.myLooper() != Looper.getMainLooper()) { mainHandler.post { onNotification(characteristic, value) }; return }
        packetLogger.log(
            network = network,
            direction = PacketDirection.RX,
            transport = captureTransport("BLE"),
            payload = value,
            summary = describeIncomingFrame(value),
        )
        if (nodeActionsController.receive(value)) return
        if (settingsController.receive(value)) return
        settingsController.observe(value)
        if ((value[0].toInt() and 0xff) == RESPONSE_SELF_INFO) {
            selfName = parseSelfName(value)
            clearHandshakeTimeout()
            _connectionState.value = ConnectionState.CONNECTED
            waitingForDeviceInfo = true
            mainHandler.removeCallbacks(deviceInfoTimeout)
            mainHandler.postDelayed(deviceInfoTimeout, COMMAND_TIMEOUT_MS)
            writeFrame(MeshCoreProtocol.deviceQuery(), "CMD_DEVICE_QUERY")
        }
        if ((value[0].toInt() and 0xff) == MeshCoreProtocol.RESPONSE_DEVICE_INFO) {
            mainHandler.removeCallbacks(deviceInfoTimeout)
            waitingForDeviceInfo = false
            val maxChannels =
                MeshCoreProtocol.parseMaxChannels(value) ?: MeshCoreProtocol.MAX_CHANNELS
            _channelCapacity.value = maxChannels
            beginChannelScan(maxChannels)
        }
        MeshCoreProtocol.parseChannelInfo(value)?.let { info ->
            mainHandler.removeCallbacks(channelQueryTimeout)
            if (info.configured) {
                val updated = _channels.value.filterNot { it.index == info.index } +
                    RadioChannel(info.index, info.name)
                _channels.value = updated.sortedBy(RadioChannel::index)
            }
            advanceChannelScan(info.index + 1)
        }
        MeshCoreProtocol.parseContact(value)?.let { contact ->
            contactDetails[contact.publicKey] = contact
            mainHandler.removeCallbacks(contactQueryTimeout)
            val updated = _contacts.value.filterNot { it.id == contact.publicKey } +
                RadioContact(contact.publicKey, contact.name)
            _contacts.value = updated.sortedBy { it.name.lowercase() }
            val node = MeshNode(
                network = network,
                id = contact.publicKey,
                name = contact.name,
                publicKey = contact.publicKey,
                role = meshCoreAdvertType(contact.type),
                messageable = true,
                flags = contact.flags,
                lastHeardMillis = contact.lastAdvertMillis,
                latitude = contact.latitude,
                longitude = contact.longitude,
                positionTimeMillis = contact.lastAdvertMillis.takeIf { it > 0 },
                source = "MeshCore advert",
            )
            _nodes.value = (_nodes.value.filterNot { it.id == node.id } + node)
                .sortedBy { it.name.lowercase() }
            mainHandler.postDelayed(contactQueryTimeout, CONTACT_SYNC_TIMEOUT_MS)
        }
        when (value[0].toInt() and 0xff) {
            MeshCoreProtocol.RESPONSE_CONTACTS_START -> {
                waitingForContacts = true
                _contacts.value = emptyList()
                contactDetails.clear()
                mainHandler.removeCallbacks(contactQueryTimeout)
                mainHandler.postDelayed(contactQueryTimeout, CONTACT_SYNC_TIMEOUT_MS)
            }
            MeshCoreProtocol.RESPONSE_END_OF_CONTACTS -> {
                if (waitingForContacts) finishStartup()
            }
        }
        if ((value[0].toInt() and 0xff) == MeshCoreProtocol.RESPONSE_ERROR) {
            when {
                waitingForDeviceInfo -> {
                    mainHandler.removeCallbacks(deviceInfoTimeout)
                    waitingForDeviceInfo = false
                    beginChannelScan(MeshCoreProtocol.MAX_CHANNELS)
                }
                channelScanIndex != null -> {
                    mainHandler.removeCallbacks(channelQueryTimeout)
                    advanceChannelScan(channelScanIndex!! + 1)
                }
                waitingForContacts -> finishStartup()
            }
        }
        MeshCoreProtocol.parseTraceData(value)?.let(::handleTraceData)
        if ((value[0].toInt() and 0xff) == RESPONSE_OK && waitingForSetTime) {
            waitingForSetTime = false
            writeFrame(MeshCoreProtocol.syncNextMessage(), "CMD_SYNC_NEXT_MESSAGE")
        }
        MeshCoreProtocol.parseMessage(value)
            ?.let(::resolveContact)
            ?.let { message ->
                if (message.sender == selfName) {
                    message.copy(
                        outgoing = true,
                        deliveryState = DeliveryState.SENT_TO_RADIO,
                    )
                } else {
                    message
                }
            }
            ?.let(_incomingMessages::tryEmit)
        when (value[0].toInt() and 0xff) {
            PUSH_CONTACT_UPDATED,
            PUSH_PATH_UPDATED,
            PUSH_NEW_ADVERT,
            -> beginContactSync()
            MeshCoreProtocol.PUSH_MESSAGE_WAITING,
            MeshCoreProtocol.RESPONSE_CONTACT_MESSAGE,
            MeshCoreProtocol.RESPONSE_CHANNEL_MESSAGE,
            MeshCoreProtocol.RESPONSE_CONTACT_MESSAGE_V3,
            MeshCoreProtocol.RESPONSE_CHANNEL_MESSAGE_V3,
            -> writeFrame(MeshCoreProtocol.syncNextMessage(), "CMD_SYNC_NEXT_MESSAGE")
        }
    }

    override fun onRead(characteristic: UUID, value: ByteArray) = Unit

    private fun parseSelfName(frame: ByteArray): String? {
        if (frame.size <= SELF_NAME_OFFSET) return null
        val end = (SELF_NAME_OFFSET until frame.size)
            .firstOrNull { frame[it].toInt() == 0 }
            ?: frame.size
        return frame.copyOfRange(SELF_NAME_OFFSET, end)
            .decodeToString()
            .trim()
            .takeIf(String::isNotEmpty)
    }

    private fun clearHandshakeTimeout() {
        mainHandler.removeCallbacks(handshakeTimeout)
    }

    private fun beginChannelScan(maxChannels: Int) {
        channelSlotCount = maxChannels.coerceIn(1, MeshCoreProtocol.MAX_CHANNELS)
        advanceChannelScan(0)
    }

    private fun advanceChannelScan(index: Int) {
        mainHandler.removeCallbacks(channelQueryTimeout)
        if (index >= channelSlotCount) {
            channelScanIndex = null
            beginContactSync()
            return
        }
        channelScanIndex = index
        mainHandler.postDelayed(channelQueryTimeout, COMMAND_TIMEOUT_MS)
        writeFrame(MeshCoreProtocol.getChannel(index), "CMD_GET_CHANNEL index=$index")
    }

    private fun clearStartupCallbacks() {
        mainHandler.removeCallbacks(deviceInfoTimeout)
        mainHandler.removeCallbacks(channelQueryTimeout)
        mainHandler.removeCallbacks(contactQueryTimeout)
    }

    private fun beginContactSync() {
        waitingForContacts = true
        mainHandler.removeCallbacks(contactQueryTimeout)
        mainHandler.postDelayed(contactQueryTimeout, CONTACT_SYNC_TIMEOUT_MS)
        writeFrame(MeshCoreProtocol.getContacts(), "CMD_GET_CONTACTS")
    }

    private fun finishStartup() {
        waitingForContacts = false
        mainHandler.removeCallbacks(contactQueryTimeout)
        waitingForSetTime = true
        writeFrame(
            MeshCoreProtocol.setTime((System.currentTimeMillis() / 1000).toInt()),
            "CMD_SET_DEVICE_TIME",
        )
    }

    private fun writeFrame(frame: ByteArray, summary: String) {
        packetLogger.log(
            network = network,
            direction = PacketDirection.TX,
            transport = captureTransport("BLE"),
            payload = frame,
            summary = summary,
        )
        session.write(RX_CHARACTERISTIC, frame)
    }

    private fun describeIncomingFrame(frame: ByteArray): String {
        val code = frame.firstOrNull()?.toInt()?.and(0xff) ?: return "Empty frame"
        val name = when (code) {
            RESPONSE_OK -> "RESP_OK"
            MeshCoreProtocol.RESPONSE_ERROR -> "RESP_ERROR"
            MeshCoreProtocol.RESPONSE_CONTACTS_START -> "RESP_CONTACTS_START"
            MeshCoreProtocol.RESPONSE_CONTACT -> "RESP_CONTACT"
            MeshCoreProtocol.RESPONSE_END_OF_CONTACTS -> "RESP_END_OF_CONTACTS"
            RESPONSE_SELF_INFO -> "RESP_SELF_INFO"
            MeshCoreProtocol.RESPONSE_CONTACT_MESSAGE -> "RESP_CONTACT_MESSAGE"
            MeshCoreProtocol.RESPONSE_CHANNEL_MESSAGE -> "RESP_CHANNEL_MESSAGE"
            MeshCoreProtocol.RESPONSE_NO_MORE_MESSAGES -> "RESP_NO_MORE_MESSAGES"
            MeshCoreProtocol.RESPONSE_DEVICE_INFO -> "RESP_DEVICE_INFO"
            MeshCoreProtocol.RESPONSE_CONTACT_MESSAGE_V3 -> "RESP_CONTACT_MESSAGE_V3"
            MeshCoreProtocol.RESPONSE_CHANNEL_MESSAGE_V3 -> "RESP_CHANNEL_MESSAGE_V3"
            MeshCoreProtocol.RESPONSE_CHANNEL_INFO -> "RESP_CHANNEL_INFO"
            MeshCoreProtocol.PUSH_TRACE_DATA -> "PUSH_TRACE_DATA"
            PUSH_CONTACT_UPDATED -> "PUSH_CONTACT_UPDATED"
            PUSH_PATH_UPDATED -> "PUSH_PATH_UPDATED"
            MeshCoreProtocol.PUSH_MESSAGE_WAITING -> "PUSH_MESSAGE_WAITING"
            PUSH_NEW_ADVERT -> "PUSH_NEW_ADVERT"
            else -> "frame"
        }
        return "$name code=0x${code.toString(16).padStart(2, '0')}"
    }

    private fun captureTransport(transport: String): String =
        if (captureSource.isBlank()) transport else "$transport ${captureSource.take(18)}"

    private fun meshCoreAdvertType(type: Int): String =
        when (type) {
            0 -> "None"
            1 -> "Chat"
            2 -> "Repeater"
            3 -> "Room"
            4 -> "Sensor"
            else -> "Type $type"
        }

    private fun resolveContact(message: UnifiedMessage): UnifiedMessage {
        if (!message.conversationId.startsWith("contact:")) return message
        val prefix = message.conversationId.substringAfter("contact:")
        val contact = _contacts.value.firstOrNull { it.id.startsWith(prefix) }
        if (contact != null) {
            return message.copy(
                conversationId = "contact:${contact.id}",
                sender = contact.name,
            )
        }
        if (_contacts.value.none { it.id == prefix }) {
            _contacts.value = (_contacts.value + RadioContact(prefix, prefix))
                .sortedBy { it.name.lowercase() }
        }
        return message
    }

    private fun handleTraceData(trace: MeshCoreProtocol.TraceData) {
        val pending = pendingTraces.remove(trace.tag) ?: return
        val hops = trace.hops.map { hop ->
            TraceRouteHop(
                id = hop.hash,
                name = resolveTraceHash(hop.hash),
                snr = hop.snr,
            )
        }
        val forwardCount = pending.forwardCount.coerceAtMost(hops.size)
        _traceRouteResults.tryEmit(
            pending.result.copy(
                state = TraceRouteState.COMPLETE,
                forward = hops.take(forwardCount),
                returned = hops.drop(forwardCount) + TraceRouteHop(
                    id = "local",
                    name = selfName ?: "This radio",
                    snr = trace.finalSnr,
                ),
                completedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    private fun resolveTraceHash(hash: String): String {
        val matches = contactDetails.values.filter { it.publicKey.startsWith(hash) }
        return when (matches.size) {
            0 -> hash
            1 -> matches.first().name
            else -> "$hash (${matches.size} matches)"
        }
    }

    private fun failPendingTraces(detail: String) {
        if (pendingTraces.isEmpty()) return
        val completedAt = System.currentTimeMillis()
        pendingTraces.values.forEach { pending ->
            _traceRouteResults.tryEmit(
                pending.result.copy(
                    state = TraceRouteState.ERROR,
                    completedAtMillis = completedAt,
                    detail = detail,
                ),
            )
        }
        pendingTraces.clear()
    }

    companion object {
        private const val HANDSHAKE_TIMEOUT_MS = 15_000L
        private const val COMMAND_TIMEOUT_MS = 2_000L
        private const val CONTACT_SYNC_TIMEOUT_MS = 4_000L
        private const val TRACE_TIMEOUT_MS = 60_000L
        private const val RESPONSE_SELF_INFO = 5
        private const val RESPONSE_OK = 0
        private const val SELF_NAME_OFFSET = 58
        private const val PUSH_CONTACT_UPDATED = 0x80
        private const val PUSH_PATH_UPDATED = 0x81
        private const val PUSH_NEW_ADVERT = 0x8a
        private val RX_CHARACTERISTIC: UUID =
            UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private val TX_CHARACTERISTIC: UUID =
            UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    }
}
