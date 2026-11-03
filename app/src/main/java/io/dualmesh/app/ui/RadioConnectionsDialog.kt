package io.dualmesh.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dualmesh.app.DualMeshManager
import io.dualmesh.app.backend.MultiDeviceBackend
import io.dualmesh.app.connection.ConnectionProfileStore
import io.dualmesh.app.model.*
import io.dualmesh.app.transport.UsbRadioCatalog
import kotlinx.coroutines.delay

internal fun ConnectionProfileStore.Transport.displayName(): String = when (this) {
    ConnectionProfileStore.Transport.BLE -> "Bluetooth"
    ConnectionProfileStore.Transport.TCP -> "Wi-Fi / TCP"
    ConnectionProfileStore.Transport.USB -> "USB / serial"
}

@Composable
internal fun RadioConnectionsDialog(manager: DualMeshManager, initialNetwork: MeshNetwork? = null, requestScan: () -> Unit, embedded: Boolean = false, onDismiss: () -> Unit) {
    val history by manager.connectionHistory.collectAsStateWithLifecycle()
    val mc by manager.meshCore.deviceConnections.collectAsStateWithLifecycle()
    val mt by manager.meshtastic.deviceConnections.collectAsStateWithLifecycle()
    val nearby by manager.scanner.devices.collectAsStateWithLifecycle()
    val scanning by manager.scanner.scanning.collectAsStateWithLifecycle()
    val usb by manager.usbRadios.devices.collectAsStateWithLifecycle()
    val connectionNotice by manager.connectionNotice.collectAsStateWithLifecycle()
    val usbMessage by manager.usbRadios.message.collectAsStateWithLifecycle()
    var transport by rememberSaveable { mutableStateOf<ConnectionProfileStore.Transport?>(null) }
    var network by rememberSaveable { mutableStateOf(initialNetwork) }
    var query by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var addTcp by rememberSaveable { mutableStateOf(false) }
    var usbChoice by remember { mutableStateOf<UsbRadioCatalog.Device?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { manager.usbRadios.refresh(); now = System.currentTimeMillis(); delay(1_000) } }
    val connections = mc + mt
    val saved = history.values.flatten().filter {
        (network == null || it.network == network) && (transport == null || it.transport == transport) &&
            (it.name.contains(query, true) || it.address.contains(query, true) || it.network.label.contains(query, true) ||
                (it.transport == ConnectionProfileStore.Transport.USB && io.dualmesh.app.transport.UsbRadioTarget.parse(it.address).getOrNull()?.serial?.contains(query, true) == true))
    }.sortedWith(compareByDescending<ConnectionProfileStore.Profile> { it.enabled }.thenBy { it.name.lowercase() })
    val knownIds = history.values.flatten().map { MultiDeviceBackend.targetId(it) }.toSet()
    val bluetooth = nearby.filter { (network == null || it.network == network) && (it.name.contains(query, true) || it.address.contains(query, true)) }
        .map { ConnectionProfileStore.Profile(it.network, ConnectionProfileStore.Transport.BLE, it.address, it.name) }
        .filter { MultiDeviceBackend.targetId(it) !in knownIds }
    val serial = usb.filter { device ->
        (device.name.contains(query, true) || device.target.serial?.contains(query, true) == true) &&
            history.values.flatten().none { it.transport == ConnectionProfileStore.Transport.USB && it.address == device.target.encode() }
    }
    fun enable(profile: ConnectionProfileStore.Profile) { error = manager.retryConnection(profile).exceptionOrNull()?.message }
    @Composable fun RadioRow(profile: ConnectionProfileStore.Profile, savedProfile: Boolean) {
        val connection = connections.firstOrNull { MultiDeviceBackend.targetId(it.profile) == MultiDeviceBackend.targetId(profile) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = profile.enabled, onCheckedChange = { value -> if (value) enable(profile) else manager.disconnect(profile) },
                    modifier = Modifier.semantics { contentDescription = "Enable ${profile.name} · ${profile.network.label} · ${profile.transport.displayName()}" })
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(profile.name, style = MaterialTheme.typography.bodyLarge)
                    Text("${profile.network.label} · ${profile.transport.displayName()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    if (profile.transport != ConnectionProfileStore.Transport.USB) Text(profile.address, style = MaterialTheme.typography.bodySmall)
                    else io.dualmesh.app.transport.UsbRadioTarget.parse(profile.address).getOrNull()?.let { target ->
                        Text("${target.serial ?: "%04x:%04x".format(target.vendor, target.product)} · port ${target.port + 1}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(when {
                        !profile.enabled -> if (savedProfile) "Automatic reconnect off" else "Available"
                        connection?.state == ConnectionState.CONNECTED -> "Connected · reconnect enabled"
                        connection?.state == ConnectionState.CONNECTING -> "Connecting…"
                        connection?.retryAtMillis != null -> "Reconnect in ${((connection.retryAtMillis - now) / 1000).coerceAtLeast(0)}s"
                        else -> "Waiting to reconnect"
                    }, style = MaterialTheme.typography.bodySmall)
                }
            }
            connection?.mqttProxyStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            connection?.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (profile.enabled) {
                    if (connection?.state != ConnectionState.CONNECTED) TextButton(onClick = { enable(profile) }) { Text("Retry") }
                    TextButton(onClick = { error = manager.selectDevice(profile).exceptionOrNull()?.message }, enabled = connection != null && !connection.isOutbound) {
                        Text(if (connection?.isOutbound == true) "Used for messages" else "Use for messages")
                    }
                } else if (savedProfile) TextButton(onClick = { manager.forgetConnection(profile) }) { Text("Forget") }
            }
        }
        HorizontalDivider(thickness = 0.5.dp)
    }
    ScreenPresentation(embedded, onDismiss) {
        MaterialTheme(typography = Typography()) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().then(if (embedded) Modifier else Modifier.safeDrawingPadding())) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!embedded) TextButton(onClick = onDismiss) { Text("‹ Back") }
                        Text("Radio connections", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(transport == null, { transport = null }, label = { Text("All") })
                        ConnectionProfileStore.Transport.entries.forEach { value -> FilterChip(transport == value, { transport = value }, label = { Text(value.displayName()) }) }
                    }
                    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(network == null, { network = null }, label = { Text("Both meshes") })
                        MeshNetwork.entries.forEach { value -> FilterChip(network == value, { network = value }, label = { Text(if (value == MeshNetwork.MESHCORE) "MC" else "MT") }) }
                    }
                    OutlinedTextField(query, { query = it }, label = { Text("Find radio") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (transport == null || transport == ConnectionProfileStore.Transport.BLE) TextButton(onClick = requestScan, enabled = !scanning) { Text(if (scanning) "Scanning…" else "Scan Bluetooth") }
                        if (transport == null || transport == ConnectionProfileStore.Transport.TCP) TextButton(onClick = { addTcp = true }) { Text("Add Wi-Fi radio") }
                        if (transport == ConnectionProfileStore.Transport.USB) TextButton(onClick = { manager.usbRadios.refresh() }) { Text("Refresh USB") }
                    }
                    Text("Enable up to four radios per mesh. Each receives independently; choose one per mesh for messages.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                    (error ?: connectionNotice ?: usbMessage)?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    LazyColumn(Modifier.weight(1f)) {
                        if (saved.isNotEmpty()) item { Text("Saved radios", Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall) }
                        items(saved, key = { "saved:${MultiDeviceBackend.targetId(it)}" }) { RadioRow(it, true) }
                        if (transport == null || transport == ConnectionProfileStore.Transport.BLE) {
                            if (bluetooth.isNotEmpty()) item { Text("Nearby Bluetooth", Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall) }
                            items(bluetooth, key = { "ble:${it.address}" }) { RadioRow(it, false) }
                            if (transport == ConnectionProfileStore.Transport.BLE && bluetooth.isEmpty() && saved.isEmpty()) item { Text("No radios found. Turn on Bluetooth and scan, or check saved radio filters.", Modifier.padding(16.dp)) }
                        }
                        if (transport == null || transport == ConnectionProfileStore.Transport.USB) {
                            if (serial.isNotEmpty()) item { Text("Attached USB radios", Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall) }
                            items(serial, key = { "usb:${it.target.encode()}" }) { device ->
                                ListItem(headlineContent = { Text(device.name) }, supportingContent = { Text(if (device.authorized) "Choose the firmware protocol" else "USB access permission required") }, trailingContent = { TextButton(onClick = { usbChoice = device }) { Text("Connect") } })
                            }
                            if (transport == ConnectionProfileStore.Transport.USB && serial.isEmpty() && saved.isEmpty()) item { Text("Attach a USB serial radio to this phone using USB host/OTG. Supported serial adapters appear here.", Modifier.padding(16.dp)) }
                        }
                        if (transport == ConnectionProfileStore.Transport.TCP && saved.isEmpty()) item { Text("Add a radio by hostname or IP. MeshCore companion TCP uses port 5000; Meshtastic uses 4403 by default.", Modifier.padding(16.dp)) }
                    }
                }
            }
            if (addTcp) AddWifiRadioDialog(network ?: MeshNetwork.MESHCORE, onDismiss = { addTcp = false }, onAdd = { mesh, address, name ->
                val result = manager.connectTcp(mesh, address, name)
                if (result.isSuccess) addTcp = false
                result
            })
            usbChoice?.let { device -> AlertDialog(onDismissRequest = { usbChoice = null }, title = { Text("USB radio protocol") }, text = { Text("Choose the firmware running on ${device.name}.") },
                confirmButton = { TextButton(onClick = { manager.connectUsb(MeshNetwork.MESHCORE, device.target); usbChoice = null }) { Text("MeshCore") } },
                dismissButton = { TextButton(onClick = { manager.connectUsb(MeshNetwork.MESHTASTIC, device.target); usbChoice = null }) { Text("Meshtastic") } }) }
        }
    }
}

@Composable
private fun AddWifiRadioDialog(initialNetwork: MeshNetwork, onDismiss: () -> Unit, onAdd: (MeshNetwork, String, String) -> Result<Unit>) {
    var network by rememberSaveable { mutableStateOf(initialNetwork) }
    var endpoint by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add Wi-Fi / TCP radio") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { MeshNetwork.entries.forEach { value -> FilterChip(network == value, { network = value }, label = { Text(value.label) }) } }
            OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Hostname or IP[:port]") }, supportingText = { Text("Default port: ${if (network == MeshNetwork.MESHCORE) 5000 else 4403}") }, singleLine = true)
            OutlinedTextField(name, { name = it }, label = { Text("Radio name (optional)") }, singleLine = true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { error = onAdd(network, endpoint, name).exceptionOrNull()?.message }) { Text("Connect") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
