package io.dualmesh.app.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dualmesh.app.MainViewModel
import io.dualmesh.app.backend.MultiDeviceBackend
import io.dualmesh.app.connection.ConnectionProfileStore
import io.dualmesh.app.logging.PacketDirection
import io.dualmesh.app.logging.PacketLogEntry
import io.dualmesh.app.logging.PacketLogger
import io.dualmesh.app.model.ConnectionState
import io.dualmesh.app.model.MeshNetwork
import io.dualmesh.app.model.MeshNode
import io.dualmesh.app.model.RadioChannel
import io.dualmesh.app.model.RadioDevice
import io.dualmesh.app.model.RadioContact
import io.dualmesh.app.model.UnifiedMessage
import java.text.DateFormat
import java.util.Date
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

private enum class PhoneLayoutMode(val label: String) {
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
    val devices by manager.scanner.devices.collectAsStateWithLifecycle()
    val scanning by manager.scanner.scanning.collectAsStateWithLifecycle()
    val selectedNetwork by manager.selectedNetwork.collectAsStateWithLifecycle()
    val messages by manager.messages.collectAsStateWithLifecycle()
    val nodes by manager.nodes.collectAsStateWithLifecycle()
    val meshCoreState by manager.meshCore.connectionState.collectAsStateWithLifecycle()
    val meshtasticState by manager.meshtastic.connectionState.collectAsStateWithLifecycle()
    val meshCoreName by manager.meshCore.connectedDeviceName.collectAsStateWithLifecycle()
    val meshtasticName by manager.meshtastic.connectedDeviceName.collectAsStateWithLifecycle()
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
    val connectionHistory by manager.connectionHistory.collectAsStateWithLifecycle()
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
    var showViewMenu by remember { mutableStateOf(false) }
    var showPacketLog by rememberSaveable { mutableStateOf(false) }

    DualMeshTheme {
        SpaceBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = GlassSurfaceStrong,
                    ),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OrbitalMark()
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    "DualMesh",
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Text(
                                    "ONE INBOX  ·  TWO MESHES",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    letterSpacing = 0.9.sp,
                                )
                            }
                        }
                    },
                    actions = {
                        Box {
                            HeaderAction("View") { showViewMenu = true }
                            DropdownMenu(
                                expanded = showViewMenu,
                                onDismissRequest = { showViewMenu = false },
                            ) {
                                Text(
                                    "Phone layout",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                PhoneLayoutMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(
                                                    mode.label,
                                                    fontWeight = if (phoneLayoutMode == mode) {
                                                        FontWeight.Bold
                                                    } else {
                                                        FontWeight.Normal
                                                    },
                                                )
                                                Text(
                                                    when (mode) {
                                                        PhoneLayoutMode.FOCUSED ->
                                                            "One network at a time"
                                                        PhoneLayoutMode.STACKED ->
                                                            "Meshtastic above MeshCore"
                                                    },
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        },
                                        onClick = {
                                            phoneLayoutMode = mode
                                            layoutPreferences.edit {
                                                putString(KEY_PHONE_LAYOUT_MODE, mode.name)
                                            }
                                            showViewMenu = false
                                        },
                                    )
                                }
                                HorizontalDivider()
                                Text(
                                    "Tablets always use side by side.",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        HeaderAction("Packets") { showPacketLog = true }
                        Spacer(Modifier.width(8.dp))
                    },
                )
            },
        ) { scaffoldPadding ->
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(scaffoldPadding)
                    .padding(horizontal = 10.dp),
            ) {
                val sideBySide = maxWidth >= TABLET_TWO_PANE_WIDTH
                if (sideBySide) {
                    Row(Modifier.fillMaxSize()) {
                        NetworkPane(
                            network = MeshNetwork.MESHTASTIC,
                            state = meshtasticState,
                            connectedDeviceName = meshtasticName,
                            devices = devices.filter { it.network == MeshNetwork.MESHTASTIC },
                            scanning = scanning,
                            channels = meshtasticChannels,
                            selectedChannel = meshtasticSelected,
                            capacity = meshtasticCapacity,
                            contacts = meshtasticContacts,
                            messages = messages,
                            nodes = nodes,
                            selectedConversationIds = selectedConversationIds,
                            packetLogging = MeshNetwork.MESHTASTIC in packetLoggingNetworks,
                            connectionProfiles =
                                connectionHistory[MeshNetwork.MESHTASTIC].orEmpty(),
                            deviceConnections = meshtasticConnections,
                            selectedDeviceId = selectedDeviceIds[MeshNetwork.MESHTASTIC],
                            viewModel = viewModel,
                            requestPermissions = requestPermissions,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        NetworkPane(
                            network = MeshNetwork.MESHCORE,
                            state = meshCoreState,
                            connectedDeviceName = meshCoreName,
                            devices = devices.filter { it.network == MeshNetwork.MESHCORE },
                            scanning = scanning,
                            channels = meshCoreChannels,
                            selectedChannel = meshCoreSelected,
                            capacity = meshCoreCapacity,
                            contacts = meshCoreContacts,
                            messages = messages,
                            nodes = nodes,
                            selectedConversationIds = selectedConversationIds,
                            packetLogging = MeshNetwork.MESHCORE in packetLoggingNetworks,
                            connectionProfiles =
                                connectionHistory[MeshNetwork.MESHCORE].orEmpty(),
                            deviceConnections = meshCoreConnections,
                            selectedDeviceId = selectedDeviceIds[MeshNetwork.MESHCORE],
                            viewModel = viewModel,
                            requestPermissions = requestPermissions,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else if (phoneLayoutMode == PhoneLayoutMode.STACKED) {
                    Column(Modifier.fillMaxSize()) {
                        NetworkPane(
                            network = MeshNetwork.MESHTASTIC,
                            state = meshtasticState,
                            connectedDeviceName = meshtasticName,
                            devices = devices.filter { it.network == MeshNetwork.MESHTASTIC },
                            scanning = scanning,
                            channels = meshtasticChannels,
                            selectedChannel = meshtasticSelected,
                            capacity = meshtasticCapacity,
                            contacts = meshtasticContacts,
                            messages = messages,
                            nodes = nodes,
                            selectedConversationIds = selectedConversationIds,
                            packetLogging = MeshNetwork.MESHTASTIC in packetLoggingNetworks,
                            connectionProfiles =
                                connectionHistory[MeshNetwork.MESHTASTIC].orEmpty(),
                            deviceConnections = meshtasticConnections,
                            selectedDeviceId = selectedDeviceIds[MeshNetwork.MESHTASTIC],
                            viewModel = viewModel,
                            requestPermissions = requestPermissions,
                            modifier = Modifier.weight(1f),
                        )
                        HorizontalDivider()
                        NetworkPane(
                            network = MeshNetwork.MESHCORE,
                            state = meshCoreState,
                            connectedDeviceName = meshCoreName,
                            devices = devices.filter { it.network == MeshNetwork.MESHCORE },
                            scanning = scanning,
                            channels = meshCoreChannels,
                            selectedChannel = meshCoreSelected,
                            capacity = meshCoreCapacity,
                            contacts = meshCoreContacts,
                            messages = messages,
                            nodes = nodes,
                            selectedConversationIds = selectedConversationIds,
                            packetLogging = MeshNetwork.MESHCORE in packetLoggingNetworks,
                            connectionProfiles =
                                connectionHistory[MeshNetwork.MESHCORE].orEmpty(),
                            deviceConnections = meshCoreConnections,
                            selectedDeviceId = selectedDeviceIds[MeshNetwork.MESHCORE],
                            viewModel = viewModel,
                            requestPermissions = requestPermissions,
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
                                state = meshtasticState,
                                connectedDeviceName = meshtasticName,
                                devices = devices.filter {
                                    it.network == MeshNetwork.MESHTASTIC
                                },
                                scanning = scanning,
                                channels = meshtasticChannels,
                                selectedChannel = meshtasticSelected,
                                capacity = meshtasticCapacity,
                                contacts = meshtasticContacts,
                                messages = messages,
                                nodes = nodes,
                                selectedConversationIds = selectedConversationIds,
                                packetLogging =
                                    MeshNetwork.MESHTASTIC in packetLoggingNetworks,
                                connectionProfiles =
                                    connectionHistory[MeshNetwork.MESHTASTIC].orEmpty(),
                                deviceConnections = meshtasticConnections,
                                selectedDeviceId = selectedDeviceIds[MeshNetwork.MESHTASTIC],
                                viewModel = viewModel,
                                requestPermissions = requestPermissions,
                                showNetworkHeader = false,
                                modifier = Modifier.weight(1f),
                            )
                            MeshNetwork.MESHCORE -> NetworkPane(
                                network = MeshNetwork.MESHCORE,
                                state = meshCoreState,
                                connectedDeviceName = meshCoreName,
                                devices = devices.filter { it.network == MeshNetwork.MESHCORE },
                                scanning = scanning,
                                channels = meshCoreChannels,
                                selectedChannel = meshCoreSelected,
                                capacity = meshCoreCapacity,
                                contacts = meshCoreContacts,
                                messages = messages,
                                nodes = nodes,
                                selectedConversationIds = selectedConversationIds,
                                packetLogging = MeshNetwork.MESHCORE in packetLoggingNetworks,
                                connectionProfiles =
                                    connectionHistory[MeshNetwork.MESHCORE].orEmpty(),
                                deviceConnections = meshCoreConnections,
                                selectedDeviceId = selectedDeviceIds[MeshNetwork.MESHCORE],
                                viewModel = viewModel,
                                requestPermissions = requestPermissions,
                                showNetworkHeader = false,
                                modifier = Modifier.weight(1f),
                            )
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
private fun OrbitalMark() {
    Box(
        modifier = Modifier
            .size(34.dp)
            .background(
                brush = Brush.radialGradient(
                    listOf(
                        Color.White.copy(alpha = 0.95f),
                        NeonBlue.copy(alpha = 0.75f),
                        NeonBlue.copy(alpha = 0.08f),
                    ),
                ),
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(Color(0xFF071326), CircleShape),
        )
    }
}

@Composable
private fun HeaderAction(
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = GlassSurface,
        shape = CircleShape,
        border = BorderStroke(1.dp, GlassOutline),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = NeonBlue,
        )
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
    state: ConnectionState,
    connectedDeviceName: String?,
    devices: List<RadioDevice>,
    scanning: Boolean,
    channels: List<RadioChannel>,
    selectedChannel: Int,
    capacity: Int,
    contacts: List<RadioContact>,
    messages: List<UnifiedMessage>,
    nodes: List<MeshNode>,
    selectedConversationIds: Map<MeshNetwork, String>,
    packetLogging: Boolean,
    connectionProfiles: List<ConnectionProfileStore.Profile>,
    deviceConnections: List<MultiDeviceBackend.DeviceConnection>,
    selectedDeviceId: String?,
    viewModel: MainViewModel,
    requestPermissions: () -> Unit,
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
    var showNodes by rememberSaveable(network, selectedDeviceId) { mutableStateOf(false) }
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
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(state)
                Text(
                    network.label,
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = networkAccent(network),
                )
                Spacer(Modifier.weight(1f))
                if (packetLogging) {
                    Text(
                        "REC",
                        style = MaterialTheme.typography.labelSmall,
                        color = SignalRed,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        ConnectionPanel(
            network = network,
            state = state,
            connectedDeviceName = connectedDeviceName,
            devices = devices,
            scanning = scanning,
            connectionProfiles = connectionProfiles,
            deviceConnections = deviceConnections,
            selectedDeviceId = selectedDeviceId,
            onScan = requestPermissions,
            onConnect = viewModel::connect,
            onConnectProfile = viewModel::connect,
            onConnectTcp = viewModel::connectMeshtasticTcp,
            onDisconnect = { viewModel.disconnect(network) },
            onDisconnectProfile = viewModel::disconnect,
            onSelectDevice = viewModel::selectDevice,
        )
        if (deviceConnections.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DeviceSelector(
                    connections = deviceConnections,
                    selectedDeviceId = selectedDeviceId,
                    onSelect = viewModel::selectDevice,
                    modifier = Modifier.weight(1f),
                )
                NodeMapButton(
                    nodeCount = radioNodes.size,
                    positionedCount = radioNodes.count(MeshNode::hasPosition),
                    onClick = { showNodes = true },
                )
            }
        }
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
            modifier = Modifier.weight(1f),
        )
        MessageComposer(
            enabled = selectedDeviceState == ConnectionState.CONNECTED,
            maxBytes = manager.maxMessageBytes(network),
            conversationLabel = conversationName,
            onSend = { text -> manager.send(network, conversationId, text) },
        )
    }
    if (showNodes) {
        NodeMapDialog(
            network = network,
            radioName = selectedDeviceConnection?.profile?.name ?: network.label,
            nodes = radioNodes,
            onDismiss = { showNodes = false },
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
private fun ConnectionPanel(
    network: MeshNetwork,
    state: ConnectionState,
    connectedDeviceName: String?,
    devices: List<RadioDevice>,
    scanning: Boolean,
    connectionProfiles: List<ConnectionProfileStore.Profile>,
    deviceConnections: List<MultiDeviceBackend.DeviceConnection>,
    selectedDeviceId: String?,
    onScan: () -> Unit,
    onConnect: (RadioDevice) -> Unit,
    onConnectProfile: (ConnectionProfileStore.Profile) -> Unit,
    onConnectTcp: (String) -> Result<Unit>,
    onDisconnect: () -> Unit,
    onDisconnectProfile: (ConnectionProfileStore.Profile) -> Unit,
    onSelectDevice: (ConnectionProfileStore.Profile) -> Unit,
) {
    var expanded by rememberSaveable(network) { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state == ConnectionState.CONNECTED) expanded = false
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = GlassSurface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, GlassOutline),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(state)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp),
                ) {
                    Text(
                        connectedDeviceName ?: "${network.label} connection",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        state.label(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide" else "Manage")
                }
                if (state == ConnectionState.CONNECTED) {
                    TextButton(onClick = onDisconnect) {
                        Text("Disconnect")
                    }
                }
            }
            AnimatedVisibility(expanded) {
                Column {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    if (connectionProfiles.isNotEmpty()) {
                        RecentConnections(
                            profiles = connectionProfiles,
                            deviceConnections = deviceConnections,
                            selectedDeviceId = selectedDeviceId,
                            onConnect = onConnectProfile,
                            onDisconnect = onDisconnectProfile,
                            onSelect = onSelectDevice,
                        )
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    }
                    RadioDiscovery(
                        devices = devices,
                        scanning = scanning,
                        onScan = onScan,
                        onConnect = onConnect,
                    )
                    if (network == MeshNetwork.MESHTASTIC) {
                        MeshtasticTcpConnect(
                            connecting = state == ConnectionState.CONNECTING,
                            onConnect = onConnectTcp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentConnections(
    profiles: List<ConnectionProfileStore.Profile>,
    deviceConnections: List<MultiDeviceBackend.DeviceConnection>,
    selectedDeviceId: String?,
    onConnect: (ConnectionProfileStore.Profile) -> Unit,
    onDisconnect: (ConnectionProfileStore.Profile) -> Unit,
    onSelect: (ConnectionProfileStore.Profile) -> Unit,
) {
    Text(
        "Recent connections",
        style = MaterialTheme.typography.labelLarge,
    )
    Text(
        "Enabled radios receive independently. Select one to view and send on its preset.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    profiles.take(MAX_VISIBLE_CONNECTION_PROFILES).forEach { profile ->
        val id = MultiDeviceBackend.targetId(profile)
        val connection = deviceConnections.firstOrNull {
            MultiDeviceBackend.targetId(it.profile) == id
        }
        val isSelected = id == selectedDeviceId
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        profile.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (isSelected) {
                        Surface(
                            color = NeonBlue.copy(alpha = 0.14f),
                            shape = CircleShape,
                            border = BorderStroke(1.dp, NeonBlue.copy(alpha = 0.35f)),
                        ) {
                            Text(
                                "SELECTED",
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = NeonBlue,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                Text(
                    "${profile.transport.name} · ${profile.address}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (connection == null) {
                TextButton(onClick = { onConnect(profile) }) {
                    Text("Connect")
                }
            } else {
                TextButton(
                    enabled = !isSelected,
                    onClick = { onSelect(profile) },
                ) {
                    Text(if (isSelected) connection.state.label() else "Use")
                }
                TextButton(onClick = { onDisconnect(profile) }) {
                    Text("Stop")
                }
            }
        }
    }
}

@Composable
private fun DeviceSelector(
    connections: List<MultiDeviceBackend.DeviceConnection>,
    selectedDeviceId: String?,
    onSelect: (ConnectionProfileStore.Profile) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = connections.firstOrNull {
        MultiDeviceBackend.targetId(it.profile) == selectedDeviceId
    } ?: connections.first()
    Box(modifier) {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = GlassSurface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, GlassOutline),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(selected.state)
                Spacer(Modifier.width(7.dp))
                Text(
                    "Radio · ${selected.profile.name}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = NeonBlue,
                )
                Text(
                    "▾",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            connections.forEach { connection ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(connection.profile.name)
                            Text(
                                "${connection.profile.transport.name} · " +
                                    connection.state.label(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    leadingIcon = { StatusDot(connection.state) },
                    onClick = {
                        onSelect(connection.profile)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun NodeMapButton(
    nodeCount: Int,
    positionedCount: Int,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = GlassSurface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, NeonBlue.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Nodes / Map",
                style = MaterialTheme.typography.labelLarge,
                color = NeonBlue,
            )
            Text(
                "$nodeCount saved · $positionedCount mapped",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NodeMapDialog(
    network: MeshNetwork,
    radioName: String,
    nodes: List<MeshNode>,
    onDismiss: () -> Unit,
) {
    val positioned = nodes.filter { node ->
        node.latitude?.let { it in -90.0..90.0 } == true &&
            node.longitude?.let { it in -180.0..180.0 } == true
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            colors = CardDefaults.cardColors(containerColor = GlassSurfaceStrong),
            shape = RoundedCornerShape(26.dp),
            border = BorderStroke(1.dp, GlassOutline),
        ) {
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "$radioName node map",
                            style = MaterialTheme.typography.titleLarge,
                            color = networkAccent(network),
                        )
                        Text(
                            "${nodes.size} stored nodes · ${positioned.size} with positions",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Spacer(Modifier.height(8.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.56f),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF020714)),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, GlassOutline),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        if (positioned.isNotEmpty()) {
                            NodeMap(positioned)
                        } else {
                            Text(
                                "No advertised positions received through this radio yet.",
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(24.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    "Stored directory",
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.44f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(nodes, key = { "${it.deviceId}:${it.id}" }) { node ->
                        NodeDirectoryRow(node)
                    }
                    if (nodes.isEmpty()) {
                        item {
                            Text(
                                "Node information will be retained after this radio supplies its " +
                                    "contact/node database or live adverts.",
                                modifier = Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NodeMap(nodes: List<MeshNode>) {
    val context = LocalContext.current
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    val markerIcon = remember(context) {
        val size = (18 * context.resources.displayMetrics.density).toInt().coerceAtLeast(24)
        val bitmap = android.graphics.Bitmap.createBitmap(
            size,
            size,
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val center = size / 2f
        paint.color = android.graphics.Color.argb(45, 40, 210, 255)
        canvas.drawCircle(center, center, size * 0.39f, paint)
        paint.color = android.graphics.Color.rgb(40, 210, 255)
        canvas.drawCircle(center, center, size * 0.34f, paint)
        paint.color = android.graphics.Color.rgb(2, 7, 20)
        canvas.drawCircle(center, center, size * 0.13f, paint)
        IconFactory.getInstance(context).fromBitmap(bitmap)
    }
    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            onStart()
            onResume()
            getMapAsync { readyMap ->
                readyMap.setStyle(Style.Builder().fromJson(NODE_MAP_STYLE_JSON)) {
                    map = readyMap
                }
            }
        }
    }
    DisposableEffect(mapView) {
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }
    LaunchedEffect(map, nodes) {
        val readyMap = map ?: return@LaunchedEffect
        readyMap.clear()
        val coordinates = nodes.mapNotNull { node ->
            val latitude = node.latitude ?: return@mapNotNull null
            val longitude = node.longitude ?: return@mapNotNull null
            val coordinate = LatLng(latitude, longitude)
            readyMap.addMarker(
                MarkerOptions()
                    .position(coordinate)
                    .title(node.name)
                    .snippet(nodeMapSnippet(node))
                    .icon(markerIcon),
            )
            coordinate
        }
        when (coordinates.size) {
            0 -> Unit
            1 -> readyMap.moveCamera(
                CameraUpdateFactory.newLatLngZoom(coordinates.first(), 11.0),
            )
            else -> readyMap.moveCamera(
                CameraUpdateFactory.newLatLngBounds(
                    LatLngBounds.Builder().includes(coordinates).build(),
                    72,
                ),
            )
        }
    }
    AndroidView(
        factory = { mapView },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun NodeDirectoryRow(node: MeshNode) {
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
        }
    }
}

private fun nodeMapSnippet(node: MeshNode): String =
    buildList {
        node.role?.let(::add)
        node.hardware?.takeUnless { it == "UNSET" }?.let(::add)
        node.altitudeMeters?.let { add("${it}m") }
        node.hopsAway?.let { add("$it hops") }
        add(nodeLastSeenLabel(node))
    }.joinToString(" · ")

private fun nodeLastSeenLabel(node: MeshNode): String {
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
private fun RadioDiscovery(
    devices: List<RadioDevice>,
    scanning: Boolean,
    onScan: () -> Unit,
    onConnect: (RadioDevice) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Bluetooth radios", style = MaterialTheme.typography.labelLarge)
            Text(
                if (devices.isEmpty()) {
                    "Scan for a nearby companion"
                } else {
                    "${devices.size} nearby"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onScan, contentPadding = PaddingValues(horizontal = 14.dp)) {
            Text(if (scanning) "Scanning…" else "Scan")
        }
    }
    devices.take(4).forEach { device ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${device.rssi} dBm",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { onConnect(device) }) {
                Text("Connect")
            }
        }
    }
}

@Composable
private fun MeshtasticTcpConnect(
    connecting: Boolean,
    onConnect: (String) -> Result<Unit>,
) {
    var endpoint by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text("Wi-Fi / Ethernet", style = MaterialTheme.typography.labelLarge)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = endpoint,
            onValueChange = {
                endpoint = it
                error = null
            },
            modifier = Modifier.weight(1f),
            singleLine = true,
            label = { Text("Host or IP") },
            placeholder = { Text("mesh-radio.local") },
            isError = error != null,
        )
        Button(
            enabled = endpoint.isNotBlank() && !connecting,
            contentPadding = PaddingValues(horizontal = 14.dp),
            onClick = {
                val result = onConnect(endpoint)
                error = result.exceptionOrNull()?.message
            },
        ) {
            Text(if (connecting) "Wait…" else "TCP")
        }
    }
    error?.let {
        Text(
            it,
            modifier = Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
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
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(Modifier.weight(1f)) {
            Text(
                selectedChannel?.let { "#${it.index} ${it.name}" }
                    ?: selectedContact?.name
                    ?: selectedConversationId.substringAfter(':').take(16),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${network.label} · ${channels.size}/$capacity channels · ${contacts.size} contacts",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                contentPadding = PaddingValues(horizontal = 14.dp),
            ) {
                Text("Conversations")
            }
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
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val threadItems = remember(messages) { collapseMessageReactions(messages) }
    LaunchedEffect(threadItems.lastOrNull()?.message?.id) {
        if (threadItems.isNotEmpty()) listState.scrollToItem(threadItems.lastIndex)
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
            MessageBubble(item.message, item.reactions)
        }
    }
}

@Composable
private fun MessageBubble(
    message: UnifiedMessage,
    reactions: List<UnifiedMessage> = emptyList(),
) {
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
            ReactionStrip(reactions)
        }
    }
}

@Composable
private fun ReactionStrip(reactions: List<UnifiedMessage>) {
    val chips = remember(reactions) { summarizeReactions(reactions).take(MAX_VISIBLE_REACTIONS) }
    if (chips.isEmpty()) return
    Row(
        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        chips.forEach { chip ->
            Surface(
                shape = CircleShape,
                color = GlassSurfaceStrong,
                border = BorderStroke(1.dp, NeonBlue.copy(alpha = 0.42f)),
            ) {
                Text(
                    text = if (chip.count == 1) chip.emoji else "${chip.emoji} ${chip.count}",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
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
private const val MAX_VISIBLE_CONNECTION_PROFILES = 6
private const val MAX_VISIBLE_REACTIONS = 5
private val TABLET_TWO_PANE_WIDTH = 720.dp
private const val LAYOUT_PREFERENCES_NAME = "layout_preferences"
private const val KEY_PHONE_LAYOUT_MODE = "phone_layout_mode"
private val NODE_MAP_STYLE_JSON = """
    {
      "version": 8,
      "name": "DualMesh node map",
      "sources": {
        "osm": {
          "type": "raster",
          "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
          "tileSize": 256,
          "maxzoom": 19,
          "attribution": "© OpenStreetMap contributors"
        }
      },
      "layers": [
        {
          "id": "space",
          "type": "background",
          "paint": {"background-color": "#020714"}
        },
        {
          "id": "osm",
          "type": "raster",
          "source": "osm",
          "paint": {
            "raster-saturation": -0.35,
            "raster-contrast": 0.20,
            "raster-brightness-max": 0.78
          }
        }
      ]
    }
""".trimIndent()
