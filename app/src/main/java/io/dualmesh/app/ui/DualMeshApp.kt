package io.dualmesh.app.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dualmesh.app.MainViewModel
import io.dualmesh.app.backend.MultiDeviceBackend
import io.dualmesh.app.logging.PacketDirection
import io.dualmesh.app.logging.PacketLogEntry
import io.dualmesh.app.logging.PacketLogger
import io.dualmesh.app.model.ConnectionState
import io.dualmesh.app.model.MeshNetwork
import io.dualmesh.app.model.MeshNode
import io.dualmesh.app.model.RadioChannel
import io.dualmesh.app.model.RadioContact
import io.dualmesh.app.model.TraceRouteHop
import io.dualmesh.app.model.TraceRouteResult
import io.dualmesh.app.model.TraceRouteState
import io.dualmesh.app.model.UnifiedMessage
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal enum class PhoneLayoutMode(val label: String) {
    FOCUSED("Focused"),
    STACKED("Stacked"),
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun DualMeshApp(
    viewModel: MainViewModel,
    requestPermissions: () -> Unit,
) {
    val manager = viewModel.manager
    val selectedNetwork by manager.selectedNetwork.collectAsStateWithLifecycle()
    val messages by manager.messages.collectAsStateWithLifecycle()
    val nodes by manager.nodes.collectAsStateWithLifecycle()
    val meshCoreState by manager.meshCore.connectionState.collectAsStateWithLifecycle()
    val meshtasticState by manager.meshtastic.connectionState.collectAsStateWithLifecycle()
    val meshCoreChannels by manager.meshCore.channels.collectAsStateWithLifecycle()
    val meshtasticChannels by manager.meshtastic.channels.collectAsStateWithLifecycle()
    val meshCoreCapacity by manager.meshCore.channelCapacity.collectAsStateWithLifecycle()
    val meshtasticCapacity by manager.meshtastic.channelCapacity.collectAsStateWithLifecycle()
    val meshCoreSelected by manager.meshCore.selectedChannel.collectAsStateWithLifecycle()
    val meshtasticSelected by manager.meshtastic.selectedChannel.collectAsStateWithLifecycle()
    val meshCoreContacts by manager.meshCore.contacts.collectAsStateWithLifecycle()
    val meshtasticContacts by manager.meshtastic.contacts.collectAsStateWithLifecycle()
    val selectedConversationIds by manager.selectedConversationIds.collectAsStateWithLifecycle()
    val packetLoggingNetworks by manager.packetLogger.enabledNetworks.collectAsStateWithLifecycle()
    val meshCoreConnections by manager.meshCore.deviceConnections.collectAsStateWithLifecycle()
    val meshtasticConnections by manager.meshtastic.deviceConnections.collectAsStateWithLifecycle()
    val selectedDeviceIds by manager.selectedDeviceIds.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val layoutPreferences = remember(context) {
        context.getSharedPreferences(LAYOUT_PREFERENCES_NAME, Context.MODE_PRIVATE)
    }
    var phoneLayoutMode by rememberSaveable {
        mutableStateOf(
            layoutPreferences.getString(KEY_PHONE_LAYOUT_MODE, null)
                ?.let { stored ->
                    PhoneLayoutMode.entries.firstOrNull { it.name == stored }
                }
                ?: PhoneLayoutMode.STACKED,
        )
    }
    var showPacketLog by rememberSaveable { mutableStateOf(false) }
    var destination by rememberSaveable { mutableStateOf(AppDestination.CHANNELS) }
    var mapFocus by rememberSaveable { mutableStateOf<String?>(null) }
    val tabState = rememberSaveableStateHolder()
    var tabHistory by rememberSaveable { mutableStateOf(arrayListOf(AppDestination.CHANNELS.name)) }
    fun navigate(tab: AppDestination) {
        if (tab == destination) return
        tabHistory = ArrayList(tabHistory + tab.name)
        destination = tab
    }
    BackHandler(tabHistory.size > 1) {
        tabHistory = ArrayList(tabHistory.dropLast(1))
        destination = AppDestination.valueOf(tabHistory.last())
    }

    DualMeshTheme {
        SpaceBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            bottomBar = {
                NavigationBar {
                    AppDestination.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = destination == tab,
                            onClick = { navigate(tab); if (tab == AppDestination.MAP) mapFocus = null },
                            icon = { DestinationIcon(tab) },
                            label = { Text(tab.label, maxLines = 1) },
                        )
                    }
                }
            },
        ) { scaffoldPadding ->
            Box(Modifier.fillMaxSize().padding(scaffoldPadding)) {
                tabState.SaveableStateProvider(destination) {
                    when (destination) {
                        AppDestination.NODES -> MeshMapDialog(manager, embedded = true,
                            onNavigateMap = { mapFocus = mapNodeKey(it); navigate(AppDestination.MAP) }, onDismiss = {})
                        AppDestination.MAP -> MeshMapDialog(manager, initialDirectory = false, embedded = true,
                            focusNodeKey = mapFocus, onNavigateNodes = { navigate(AppDestination.NODES) }, onDismiss = {})
                        AppDestination.SETTINGS -> RadioSettingsScreen(manager, phoneLayoutMode,
                            onLayoutChange = { mode ->
                                phoneLayoutMode = mode
                                layoutPreferences.edit { putString(KEY_PHONE_LAYOUT_MODE, mode.name) }
                            }, onPacketLogs = { showPacketLog = true })
                        AppDestination.CONNECTIONS -> RadioConnectionsDialog(manager, requestScan = requestPermissions, embedded = true, onDismiss = {})
                        AppDestination.CHANNELS -> BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 10.dp),
                        ) {
                            val sideBySide = maxWidth >= TABLET_TWO_PANE_WIDTH
                            if (sideBySide) {
                                Row(Modifier.fillMaxSize()) {
                                    NetworkPane(
                                        network = MeshNetwork.MESHTASTIC,
                                        channels = meshtasticChannels,
                                        selectedChannel = meshtasticSelected,
                                        capacity = meshtasticCapacity,
                                        contacts = meshtasticContacts,
                                        messages = messages,
                                        nodes = nodes,
                                        selectedConversationIds = selectedConversationIds,
                                        packetLogging = MeshNetwork.MESHTASTIC in packetLoggingNetworks,
                                        deviceConnections = meshtasticConnections,
                                        selectedDeviceId = selectedDeviceIds[MeshNetwork.MESHTASTIC],
                                        viewModel = viewModel,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    NetworkPane(
                                        network = MeshNetwork.MESHCORE,
                                        channels = meshCoreChannels,
                                        selectedChannel = meshCoreSelected,
                                        capacity = meshCoreCapacity,
                                        contacts = meshCoreContacts,
                                        messages = messages,
                                        nodes = nodes,
                                        selectedConversationIds = selectedConversationIds,
                                        packetLogging = MeshNetwork.MESHCORE in packetLoggingNetworks,
                                        deviceConnections = meshCoreConnections,
                                        selectedDeviceId = selectedDeviceIds[MeshNetwork.MESHCORE],
                                        viewModel = viewModel,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            } else if (phoneLayoutMode == PhoneLayoutMode.STACKED) {
                                Column(Modifier.fillMaxSize()) {
                                    NetworkPane(
                                        network = MeshNetwork.MESHTASTIC,
                                        channels = meshtasticChannels,
                                        selectedChannel = meshtasticSelected,
                                        capacity = meshtasticCapacity,
                                        contacts = meshtasticContacts,
                                        messages = messages,
                                        nodes = nodes,
                                        selectedConversationIds = selectedConversationIds,
                                        packetLogging = MeshNetwork.MESHTASTIC in packetLoggingNetworks,
                                        deviceConnections = meshtasticConnections,
                                        selectedDeviceId = selectedDeviceIds[MeshNetwork.MESHTASTIC],
                                        viewModel = viewModel,
                                        modifier = Modifier.weight(1f),
                                    )
                                    HorizontalDivider()
                                    NetworkPane(
                                        network = MeshNetwork.MESHCORE,
                                        channels = meshCoreChannels,
                                        selectedChannel = meshCoreSelected,
                                        capacity = meshCoreCapacity,
                                        contacts = meshCoreContacts,
                                        messages = messages,
                                        nodes = nodes,
                                        selectedConversationIds = selectedConversationIds,
                                        packetLogging = MeshNetwork.MESHCORE in packetLoggingNetworks,
                                        deviceConnections = meshCoreConnections,
                                        selectedDeviceId = selectedDeviceIds[MeshNetwork.MESHCORE],
                                        viewModel = viewModel,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            } else {
                                Column(Modifier.fillMaxSize()) {
                                    FocusedNetworkSelector(
                                        selected = selectedNetwork,
                                        meshCoreState = meshCoreState,
                                        meshtasticState = meshtasticState,
                                        packetLoggingNetworks = packetLoggingNetworks,
                                        onSelect = viewModel::selectNetwork,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    when (selectedNetwork) {
                                        MeshNetwork.MESHTASTIC -> NetworkPane(
                                            network = MeshNetwork.MESHTASTIC,
                                            channels = meshtasticChannels,
                                            selectedChannel = meshtasticSelected,
                                            capacity = meshtasticCapacity,
                                            contacts = meshtasticContacts,
                                            messages = messages,
                                            nodes = nodes,
                                            selectedConversationIds = selectedConversationIds,
                                            packetLogging =
                                                MeshNetwork.MESHTASTIC in packetLoggingNetworks,
                                            deviceConnections = meshtasticConnections,
                                            selectedDeviceId = selectedDeviceIds[MeshNetwork.MESHTASTIC],
                                            viewModel = viewModel,
                                            showNetworkHeader = false,
                                            modifier = Modifier.weight(1f),
                                        )
                                        MeshNetwork.MESHCORE -> NetworkPane(
                                            network = MeshNetwork.MESHCORE,
                                            channels = meshCoreChannels,
                                            selectedChannel = meshCoreSelected,
                                            capacity = meshCoreCapacity,
                                            contacts = meshCoreContacts,
                                            messages = messages,
                                            nodes = nodes,
                                            selectedConversationIds = selectedConversationIds,
                                            packetLogging = MeshNetwork.MESHCORE in packetLoggingNetworks,
                                            deviceConnections = meshCoreConnections,
                                            selectedDeviceId = selectedDeviceIds[MeshNetwork.MESHCORE],
                                            viewModel = viewModel,
                                            showNetworkHeader = false,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (showPacketLog) {
            PacketLogDialog(
                logger = manager.packetLogger,
                onDismiss = { showPacketLog = false },
            )
        }
        }
    }
}


@Composable
private fun FocusedNetworkSelector(
    selected: MeshNetwork,
    meshCoreState: ConnectionState,
    meshtasticState: ConnectionState,
    packetLoggingNetworks: Set<MeshNetwork>,
    onSelect: (MeshNetwork) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MeshNetwork.entries.forEach { network ->
            val state = when (network) {
                MeshNetwork.MESHCORE -> meshCoreState
                MeshNetwork.MESHTASTIC -> meshtasticState
            }
            val isSelected = selected == network
            val accent = networkAccent(network)
            Surface(
                onClick = { onSelect(network) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(22.dp),
                color = if (isSelected) {
                    accent.copy(alpha = 0.20f)
                } else {
                    GlassSurface
                },
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(
                    1.dp,
                    if (isSelected) accent.copy(alpha = 0.70f) else GlassOutline,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusDot(state)
                    Column {
                        Text(
                            network.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isSelected) accent else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            state.label(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (network in packetLoggingNetworks) {
                        Text(
                            "REC",
                            style = MaterialTheme.typography.labelSmall,
                            color = SignalRed,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PacketLogDialog(
    logger: PacketLogger,
    onDismiss: () -> Unit,
) {
    val enabledNetworks by logger.enabledNetworks.collectAsStateWithLifecycle()
    val entries by logger.entries.collectAsStateWithLifecycle()
    val activeCapturePath by logger.activeCapturePath.collectAsStateWithLifecycle()
    val captureFileError by logger.captureFileError.collectAsStateWithLifecycle()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            shape = RoundedCornerShape(30.dp),
            color = ModalSurface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, GlassOutline),
        ) {
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Packet logger",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Protocol frames retained in private app storage",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Text(
                    "Captures may contain message text, contacts, channel configuration, and " +
                        "encryption keys. Logging is off by default. This records radio API " +
                        "frames, not raw over-the-air LoRa.",
                    modifier = Modifier.padding(vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = WarningAmber,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MeshNetwork.entries.reversed().forEach { network ->
                        PacketLoggerToggle(
                            network = network,
                            enabled = network in enabledNetworks,
                            onEnabledChange = { logger.setEnabled(network, it) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Text(
                    activeCapturePath?.let { "Writing file: $it" }
                        ?: "Enable either network to start a new capture file.",
                    modifier = Modifier.padding(top = 7.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Clear all removes viewer rows only; JSONL capture files remain in Downloads.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                captureFileError?.let {
                    Text(
                        "Capture file error: $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${entries.size} newest captured frames",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        enabled = entries.isNotEmpty(),
                        onClick = { logger.clear() },
                    ) {
                        Text("Clear all")
                    }
                }
                HorizontalDivider()
                if (entries.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Enable a network, then reconnect or wait for traffic.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(entries, key = PacketLogEntry::id) { entry ->
                            PacketLogRow(entry)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PacketLoggerToggle(
    network: MeshNetwork,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = GlassSurface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, GlassOutline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                network.label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
            )
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
    }
}

@Composable
private fun PacketLogRow(entry: PacketLogEntry) {
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    val directionColor = when (entry.direction) {
        PacketDirection.RX -> NeonBlue
        PacketDirection.TX -> NeonViolet
    }
    Surface(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
        color = GlassSurface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, GlassOutline),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${entry.network.label} ${entry.direction.name}",
                    style = MaterialTheme.typography.labelLarge,
                    color = directionColor,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    " · ${entry.transport} · ${entry.payload.size} B",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                        .format(Date(entry.timestampMillis)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                entry.summary,
                modifier = Modifier.padding(top = 3.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                entry.hex(if (expanded) entry.payload.size else 64),
                modifier = Modifier.padding(top = 5.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                Text(
                    entry.ascii(),
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NetworkPane(
    network: MeshNetwork,
    channels: List<RadioChannel>,
    selectedChannel: Int,
    capacity: Int,
    contacts: List<RadioContact>,
    messages: List<UnifiedMessage>,
    nodes: List<MeshNode>,
    selectedConversationIds: Map<MeshNetwork, String>,
    packetLogging: Boolean,
    deviceConnections: List<MultiDeviceBackend.DeviceConnection>,
    selectedDeviceId: String?,
    viewModel: MainViewModel,
    showNetworkHeader: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val manager = viewModel.manager
    val selectedDeviceConnection = deviceConnections.firstOrNull {
        MultiDeviceBackend.targetId(it.profile) == selectedDeviceId
    }
    val selectedDeviceState = selectedDeviceConnection?.state ?: ConnectionState.DISCONNECTED
    val radioNodes = nodes.filter {
        it.network == network && it.deviceId == selectedDeviceId
    }
    val availableContacts = (
        contacts + radioNodes
            .filter { it.messageable != false }
            .map { RadioContact(it.id, it.name) }
        )
        .associateBy(RadioContact::id)
        .values
        .sortedBy { it.name.lowercase() }
    val conversationId = selectedConversationIds[network] ?: "channel:$selectedChannel"
    val conversationName = manager.conversationName(network, conversationId)
    val conversationMessages = messages.filter {
        it.network == network &&
            (it.deviceId == selectedDeviceId || it.deviceId.isBlank()) &&
            it.conversationId == conversationId
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        if (showNetworkHeader) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(selectedDeviceState)
                Text(network.label, Modifier.padding(start = 8.dp).weight(1f), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, color = networkAccent(network))
                if (packetLogging) Text("REC", style = MaterialTheme.typography.labelSmall, color = SignalRed, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            selectedDeviceConnection?.let { "${it.profile.name} · ${selectedDeviceState.label()}" }
                ?: "Choose a ${network.label} radio in Connections",
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        ConversationSelector(
            network = network,
            channels = channels,
            contacts = availableContacts,
            capacity = capacity,
            selectedConversationId = conversationId,
            onSelect = { viewModel.selectChannel(network, it) },
            onSelectContact = { viewModel.selectContact(network, it) },
        )
        MessageList(
            messages = conversationMessages,
            conversationName = conversationName,
            canReact = { message ->
                message.network == MeshNetwork.MESHTASTIC && !message.isReaction && message.id.toUIntOrNull()?.let { it != 0u } == true &&
                    message.deviceId.isNotBlank() && deviceConnections.any {
                        MultiDeviceBackend.targetId(it.profile) == message.deviceId && it.state == ConnectionState.CONNECTED
                    }
            },
            onReact = manager::sendReaction,
            modifier = Modifier.weight(1f),
        )
        MessageComposer(
            enabled = selectedDeviceState == ConnectionState.CONNECTED,
            maxBytes = manager.maxMessageBytes(network),
            conversationLabel = conversationName,
            onSend = { text -> manager.send(network, conversationId, text) },
        )
    }
}

@Composable
private fun StatusDot(state: ConnectionState) {
    val color = when (state) {
        ConnectionState.CONNECTED -> SignalGreen
        ConnectionState.CONNECTING -> WarningAmber
        ConnectionState.ERROR -> SignalRed
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        Modifier
            .size(14.dp)
            .background(color.copy(alpha = 0.16f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .background(color, CircleShape),
        )
    }
}

@Composable
internal fun NodeDirectoryRow(
    node: MeshNode,
    traces: List<TraceRouteResult>,
    traceEnabled: Boolean,
    onTrace: () -> Unit,
) {
    val latestTrace = traces.lastOrNull()
    var showTraceHistory by rememberSaveable(node.deviceId, node.id) {
        mutableStateOf(false)
    }
    Surface(
        color = GlassSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, GlassOutline.copy(alpha = 0.7f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    node.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (node.hasPosition) {
                    Text(
                        "LOC",
                        color = SignalGreen,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onTrace,
                    enabled = traceEnabled && latestTrace?.state != TraceRouteState.PENDING,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                ) {
                    Text(if (latestTrace?.state == TraceRouteState.PENDING) "Tracing" else "Trace")
                }
            }
            Text(
                buildList {
                    add(
                        if (node.network == MeshNetwork.MESHTASTIC) {
                            runCatching {
                                "!${node.id.toUInt().toString(16).padStart(8, '0')}"
                            }.getOrDefault(node.id)
                        } else {
                            node.id.take(12)
                        },
                    )
                    node.role?.let(::add)
                    node.hardware?.takeUnless { it == "UNSET" }?.let(::add)
                    node.channel?.let { add("ch $it") }
                    if (node.viaMqtt == true) add("MQTT")
                    node.hopsAway?.let { add("$it hops") }
                    node.snr?.let { add("%.1f dB".format(it)) }
                }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (node.hasPosition) {
                Text(
                    "%.6f, %.6f%s".format(
                        node.latitude,
                        node.longitude,
                        node.altitudeMeters?.let { " · ${it}m" }.orEmpty(),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = NeonBlue,
                )
            }
            Text(
                nodeLastSeenLabel(node),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (latestTrace != null) {
                TraceRouteStatus(latestTrace)
            }
            if (traces.size > 1) {
                TextButton(onClick = { showTraceHistory = !showTraceHistory }) {
                    Text(
                        if (showTraceHistory) {
                            "Hide history"
                        } else {
                            "History (${traces.size})"
                        },
                    )
                }
                if (showTraceHistory) {
                    traces.asReversed()
                        .drop(1)
                        .take(MAX_VISIBLE_TRACE_HISTORY)
                        .forEach { oldTrace ->
                            Surface(
                                modifier = Modifier.padding(top = 4.dp),
                                color = Color(0x66101B31),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, GlassOutline.copy(alpha = 0.5f)),
                            ) {
                                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                                    TraceRouteStatus(oldTrace)
                                }
                            }
                        }
                    if (traces.size - 1 > MAX_VISIBLE_TRACE_HISTORY) {
                        Text(
                            "Showing the newest $MAX_VISIBLE_TRACE_HISTORY older traces.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun TraceRouteStatus(trace: TraceRouteResult) {
    val color = when (trace.state) {
        TraceRouteState.PENDING -> WarningAmber
        TraceRouteState.COMPLETE -> SignalGreen
        TraceRouteState.TIMED_OUT,
        TraceRouteState.ERROR,
        -> MaterialTheme.colorScheme.error
    }
    HorizontalDivider(Modifier.padding(vertical = 6.dp))
    Text(
        when (trace.state) {
            TraceRouteState.PENDING -> "Trace in progress"
            TraceRouteState.COMPLETE -> "Trace complete"
            TraceRouteState.TIMED_OUT -> "Trace timed out"
            TraceRouteState.ERROR -> "Trace failed"
        },
        style = MaterialTheme.typography.labelMedium,
        color = color,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(trace.startedAtMillis)),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (trace.state == TraceRouteState.COMPLETE) {
        if (trace.forward.isNotEmpty()) {
            Text(
                "Toward: ${formatTracePath(trace.forward)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (trace.returned.isNotEmpty()) {
            Text(
                "Return: ${formatTracePath(trace.returned)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    trace.detail?.let { detail ->
        Text(
            detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatTracePath(hops: List<TraceRouteHop>): String =
    hops.joinToString(" -> ") { hop ->
        hop.snr?.let {
            "${hop.name} (${String.format(Locale.US, "%.1f", it)} dB)"
        } ?: hop.name
    }

internal fun nodeLastSeenLabel(node: MeshNode): String {
    if (node.lastHeardMillis <= 0) return "Last heard unknown"
    val ageMillis = (System.currentTimeMillis() - node.lastHeardMillis).coerceAtLeast(0)
    val age = when {
        ageMillis < 60_000 -> "just now"
        ageMillis < 3_600_000 -> "${ageMillis / 60_000}m ago"
        ageMillis < 86_400_000 -> "${ageMillis / 3_600_000}h ago"
        else -> "${ageMillis / 86_400_000}d ago"
    }
    return "Last heard $age"
}

@Composable
private fun ConversationSelector(
    network: MeshNetwork,
    channels: List<RadioChannel>,
    contacts: List<RadioContact>,
    capacity: Int,
    selectedConversationId: String,
    onSelect: (Int) -> Unit,
    onSelectContact: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var contactFilter by rememberSaveable(network) { mutableStateOf("") }
    var showDirectEntry by rememberSaveable(network) { mutableStateOf(false) }
    var directTarget by rememberSaveable(network) { mutableStateOf("") }
    val normalizedDirectTarget = directTarget.trim().removePrefix("!")
    val directTargetValid = when (network) {
        MeshNetwork.MESHCORE ->
            normalizedDirectTarget.length >= 12 &&
                normalizedDirectTarget.length % 2 == 0 &&
                normalizedDirectTarget.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        MeshNetwork.MESHTASTIC -> runCatching {
            if (normalizedDirectTarget.length == 8 &&
                normalizedDirectTarget.all {
                    it.isDigit() || it.lowercaseChar() in 'a'..'f'
                }
            ) {
                normalizedDirectTarget.toUInt(16)
            } else {
                normalizedDirectTarget.toUInt()
            }
        }.isSuccess
    }
    val selectedChannel = selectedConversationId
        .takeIf { it.startsWith("channel:") }
        ?.substringAfter("channel:")
        ?.toIntOrNull()
        ?.let { selected -> channels.firstOrNull { it.index == selected } }
    val selectedContact = selectedConversationId
        .takeIf { it.startsWith("contact:") }
        ?.substringAfter("contact:")
        ?.let { selected -> contacts.firstOrNull { it.id == selected } }
    val visibleContacts = contacts
        .asSequence()
        .filter {
            contactFilter.isBlank() ||
                it.name.contains(contactFilter, ignoreCase = true) ||
                it.id.contains(contactFilter, ignoreCase = true) ||
                (network == MeshNetwork.MESHTASTIC &&
                    runCatching {
                        it.id.toUInt().toString(16).contains(
                            contactFilter.removePrefix("!"),
                            ignoreCase = true,
                        )
                    }.getOrDefault(false))
        }
        .take(MAX_VISIBLE_CONTACT_RESULTS)
        .toList()

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Choose conversation") { expanded = true }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(Modifier.weight(1f)) {
            Text(
                selectedChannel?.let { "#${it.index} ${it.name}" }
                    ?: selectedContact?.name
                    ?: selectedConversationId.substringAfter(':').take(16),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                "${channels.size}/$capacity channels · ${contacts.size} contacts",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            Text("▾", Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium, color = NeonBlue)
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.widthIn(min = 300.dp),
            ) {
                DropdownMenuItem(
                    text = { Text("New direct message…") },
                    onClick = {
                        expanded = false
                        showDirectEntry = true
                    },
                )
                HorizontalDivider()
                Text(
                    "Channels",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                channels.forEach { channel ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    "#${channel.index} ${channel.name}",
                                    fontWeight = if (
                                        selectedConversationId == "channel:${channel.index}"
                                    ) {
                                        FontWeight.Bold
                                    } else {
                                        FontWeight.Normal
                                    },
                                )
                                channel.role?.let {
                                    Text(
                                        it.lowercase().replaceFirstChar(Char::uppercase),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        onClick = {
                            onSelect(channel.index)
                            expanded = false
                        },
                    )
                }
                if (contacts.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        "Direct messages",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (contacts.size > MAX_VISIBLE_CONTACT_RESULTS || contactFilter.isNotBlank()) {
                        OutlinedTextField(
                            value = contactFilter,
                            onValueChange = { contactFilter = it },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            singleLine = true,
                            label = { Text("Search ${contacts.size} contacts") },
                        )
                    }
                    visibleContacts.forEach { contact ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        contact.name,
                                        fontWeight = if (
                                            selectedConversationId == "contact:${contact.id}"
                                        ) {
                                            FontWeight.Bold
                                        } else {
                                            FontWeight.Normal
                                        },
                                    )
                                    Text(
                                        if (network == MeshNetwork.MESHTASTIC) {
                                            runCatching {
                                                "!${contact.id.toUInt().toString(16).padStart(8, '0')}"
                                            }.getOrDefault(contact.id)
                                        } else {
                                            contact.id.take(12)
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                onSelectContact(contact.id)
                                contactFilter = ""
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
      }
      AnimatedVisibility(showDirectEntry) {
          Row(
              modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
              OutlinedTextField(
                  value = directTarget,
                  onValueChange = { directTarget = it },
                  modifier = Modifier.weight(1f),
                  singleLine = true,
                  label = {
                      Text(
                          if (network == MeshNetwork.MESHTASTIC) {
                              "Node ID (for example !1234abcd)"
                          } else {
                              "Contact public-key prefix"
                          },
                      )
                  },
              )
              Button(
                  enabled = directTargetValid,
                  onClick = {
                      onSelectContact(directTarget)
                      directTarget = ""
                      showDirectEntry = false
                  },
                  contentPadding = PaddingValues(horizontal = 14.dp),
              ) {
                  Text("Open")
              }
          }
      }
    }
}

@Composable
private fun MessageList(
    messages: List<UnifiedMessage>,
    conversationName: String,
    canReact: (UnifiedMessage) -> Boolean,
    onReact: (UnifiedMessage, String) -> Result<Unit>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val threadItems = remember(messages) { collapseMessageReactions(messages) }
    LaunchedEffect(threadItems.lastOrNull()?.message?.id, threadItems.lastOrNull()?.reactions?.size) {
        if (threadItems.isNotEmpty()) {
            listState.scrollToItem(threadItems.lastIndex)
            listState.scrollBy(listState.layoutInfo.viewportSize.height.toFloat())
        }
    }

    if (threadItems.isEmpty()) {
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "No messages yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Messages in $conversationName will appear here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            threadItems,
            key = { "${it.message.network}:${it.message.id}" },
        ) { item ->
            MessageBubble(item.message, item.reactions, canReact(item.message)) { emoji -> onReact(item.message, emoji) }
        }
    }
}

@Composable
private fun MessageBubble(
    message: UnifiedMessage,
    reactions: List<UnifiedMessage> = emptyList(),
    canReact: Boolean = false,
    onReact: (String) -> Result<Unit> = { Result.failure(IllegalStateException("Reactions unavailable")) },
) {
    var showReactions by rememberSaveable(message.id) { mutableStateOf(false) }
    var reactionError by rememberSaveable(message.id) { mutableStateOf<String?>(null) }
    fun react(emoji: String): Boolean {
        val result = onReact(emoji)
        reactionError = result.exceptionOrNull()?.message
        if (result.isSuccess) showReactions = false
        return result.isSuccess
    }
    LaunchedEffect(canReact) { if (!canReact) showReactions = false }
    if (showReactions && canReact) ReactionPicker(message, reactions, onDismiss = { showReactions = false }, onReact = ::react, error = reactionError)
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (message.outgoing) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 340.dp),
            horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start,
        ) {
            Column(
                modifier = Modifier
                    .background(
                        brush = if (message.outgoing) {
                            Brush.linearGradient(
                                listOf(Color(0xFF075A91), Color(0xFF43358C)),
                            )
                        } else {
                            Brush.linearGradient(
                                listOf(GlassSurfaceStrong, Color(0xEF1B2946)),
                            )
                        },
                        shape = RoundedCornerShape(
                            topStart = 22.dp,
                            topEnd = 22.dp,
                            bottomStart = if (message.outgoing) 22.dp else 7.dp,
                            bottomEnd = if (message.outgoing) 7.dp else 22.dp,
                        ),
                    )
                    .combinedClickable(enabled = canReact, onClick = {}, onLongClickLabel = "Add reaction", onLongClick = { reactionError = null; showReactions = true })
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        message.sender,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        DateFormat.getTimeInstance(DateFormat.SHORT)
                            .format(Date(message.timestampMillis)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    message.text,
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                MessageMetadata(message)
                if (canReact) Text("React", Modifier.clickable { reactionError = null; showReactions = true }.padding(8.dp),
                    style = MaterialTheme.typography.labelSmall, color = NeonBlue)
            }
            MessageReactionStrip(reactions, canReact, onReact = { react(it) }, onAdd = { reactionError = null; showReactions = true })
            if (reactionError != null && !showReactions) Text(reactionError!!, style = MaterialTheme.typography.labelSmall, color = SignalRed)
        }
    }
}

@Composable
private fun MessageComposer(
    enabled: Boolean,
    maxBytes: Int,
    conversationLabel: String,
    onSend: (String) -> Result<Unit>,
) {
    var text by rememberSaveable(conversationLabel) { mutableStateOf("") }
    var sendError by remember { mutableStateOf<String?>(null) }
    val byteCount = text.encodeToByteArray().size
    val withinLimit = byteCount <= maxBytes
    val supportingText: (@Composable () -> Unit)? = when {
        sendError != null -> {
            { Text(sendError.orEmpty()) }
        }
        text.isNotEmpty() -> {
            { Text("$byteCount / $maxBytes bytes") }
        }
        else -> null
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    sendError = null
                },
                modifier = Modifier.weight(1f),
                enabled = enabled,
                placeholder = {
                    Text(if (enabled) "Message $conversationLabel" else "Connect to send")
                },
                maxLines = 3,
                isError = !withinLimit || sendError != null,
                supportingText = supportingText,
            )
            Button(
                enabled = enabled && text.isNotBlank() && withinLimit,
                contentPadding = PaddingValues(horizontal = 18.dp),
                onClick = {
                    val result = onSend(text)
                    if (result.isSuccess) {
                        text = ""
                        sendError = null
                    } else {
                        sendError = result.exceptionOrNull()?.message
                            ?: "Message could not be sent"
                    }
                },
            ) {
                Text("Send")
            }
        }
    }
}

private fun ConnectionState.label(): String =
    name.lowercase().replaceFirstChar(Char::uppercase)

private fun networkAccent(network: MeshNetwork): Color = when (network) {
    MeshNetwork.MESHTASTIC -> NeonBlue
    MeshNetwork.MESHCORE -> NeonViolet
}

private const val MAX_VISIBLE_CONTACT_RESULTS = 30
private const val MAX_VISIBLE_TRACE_HISTORY = 25
private val TABLET_TWO_PANE_WIDTH = 720.dp
private const val LAYOUT_PREFERENCES_NAME = "layout_preferences"
private const val KEY_PHONE_LAYOUT_MODE = "phone_layout_mode"
