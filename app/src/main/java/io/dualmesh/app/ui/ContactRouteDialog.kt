package io.dualmesh.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.dualmesh.app.model.MeshNode
import io.dualmesh.app.nodes.*

@Composable
internal fun ContactRouteDialog(node: MeshNode, nodes: List<MeshNode>, access: ContactRoutes, onDismiss: () -> Unit) {
    val routes by access.routeState.collectAsState()
    val operation by access.operationState.collectAsState()
    val snapshot = routes[node.id.lowercase()]
    var initialized by remember(node.id) { mutableStateOf(false) }
    var mode by remember { mutableStateOf("Direct") }
    var width by remember { mutableIntStateOf(1) }
    var path by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var started by remember { mutableStateOf(false) }
    fun use(route: MeshCoreRoute) {
        mode = when { route.flood -> "Flood"; route.hops.isEmpty() -> "Direct"; else -> "Manual" }
        width = route.hashBytes
        path = route.hops.joinToString(", ")
        error = null
    }
    LaunchedEffect(node.id) {
        if (snapshot == null) { started = true; error = access.refreshRoute(node.id).exceptionOrNull()?.message }
    }
    LaunchedEffect(snapshot?.current) {
        if (!initialized && snapshot != null) { use(snapshot.current); initialized = true }
    }
    val tokens = path.trim().split(Regex("[,\\s]+")).filter { it.isNotEmpty() }
    val draft = runCatching { MeshCoreRouteCodec.normalize(when (mode) {
        "Flood" -> MeshCoreRoute(flood = true)
        "Direct" -> MeshCoreRoute(hashBytes = width)
        else -> MeshCoreRoute(width, tokens)
    }) }
    val contacts = nodes.filter { it.network == node.network && it.deviceId == node.deviceId && it.id != node.id }
    val repeaters = contacts.filter { it.role in setOf("Repeater", "Room", "REPEATER", "ROOM", "Room server") }
        .filter { it.name.contains(search, ignoreCase = true) || it.id.contains(search, ignoreCase = true) }.sortedBy { it.name.lowercase() }
    fun label(route: MeshCoreRoute): String = when {
        route.flood -> "Flood / no stored path"
        route.hops.isEmpty() -> "Direct / zero intermediate hops"
        else -> route.hops.joinToString(" → ") { hash ->
            val matches = contacts.filter { it.id.startsWith(hash, ignoreCase = true) }
            when (matches.size) { 1 -> "${matches.single().name} ($hash)"; 0 -> hash; else -> "$hash (${matches.size} matching nodes)" }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("‹ Back") }
                    Text("Set route", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { started = true; error = access.refreshRoute(node.id).exceptionOrNull()?.message }, enabled = !operation.busy) { Text("Refresh") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(node.name, style = MaterialTheme.typography.titleMedium)
                    Text("Source radio: ${node.deviceName}", style = MaterialTheme.typography.bodySmall)
                    NodeDetailSection("Current radio route") {
                        Text(snapshot?.current?.let(::label) ?: "Route not read yet")
                        Text("Saves update this contact on the source radio. The firmware may learn a new path later.", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Direct", "Flood", "Manual").forEach { value ->
                            FilterChip(selected = mode == value, onClick = { mode = value; error = null }, enabled = !operation.busy, label = { Text(value) })
                        }
                    }
                    when (mode) {
                        "Direct" -> Text("Send directly to the destination without intermediate repeaters.")
                        "Flood" -> Text("Forget the stored route and let the radio use flood routing. A subsequent exchange may learn a new path.")
                        else -> {
                            Text("Hops run from your radio toward the destination. Do not append the destination itself.", style = MaterialTheme.typography.bodySmall)
                            Text("Bytes per hop hash", style = MaterialTheme.typography.titleSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                access.supportedHashBytes.forEach { bytes -> FilterChip(selected = width == bytes, onClick = { width = bytes; path = ""; error = null }, enabled = !operation.busy, label = { Text("$bytes") }) }
                            }
                            Text("Changing hash width clears draft hops. Capacity: ${minOf(63, 64 / width)} hops.", style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(value = path, onValueChange = { path = it }, enabled = !operation.busy, modifier = Modifier.fillMaxWidth(), label = { Text("Ordered hop hashes") }, supportingText = { Text("${width * 2} hex characters per hop, separated by commas or spaces") }, isError = draft.isFailure)
                            if (draft.isFailure) Text(draft.exceptionOrNull()?.message.orEmpty(), color = MaterialTheme.colorScheme.error)
                            if (draft.isSuccess) tokens.forEachIndexed { index, hash ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${index + 1}. ${label(MeshCoreRoute(width, listOf(hash.lowercase())))}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    fun change(new: List<String>) { path = new.joinToString(", ") }
                                    TextButton(onClick = { change(tokens.toMutableList().apply { add(index - 1, removeAt(index)) }) }, enabled = !operation.busy && index > 0, modifier = Modifier.semantics { contentDescription = "Move hop ${index + 1} up" }) { Text("↑") }
                                    TextButton(onClick = { change(tokens.toMutableList().apply { add(index + 1, removeAt(index)) }) }, enabled = !operation.busy && index < tokens.lastIndex, modifier = Modifier.semantics { contentDescription = "Move hop ${index + 1} down" }) { Text("↓") }
                                    TextButton(onClick = { change(tokens.filterIndexed { i, _ -> i != index }) }, enabled = !operation.busy, modifier = Modifier.semantics { contentDescription = "Remove hop ${index + 1}" }) { Text("×") }
                                }
                            }
                            OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text("Find repeater or room") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            repeaters.take(10).forEach { repeater ->
                                TextButton(onClick = { path = (tokens + repeater.id.take(width * 2)).joinToString(", ") }, enabled = !operation.busy && draft.isSuccess && tokens.size < minOf(63, 64 / width)) { Text("+ ${repeater.name} · ${repeater.id.take(width * 2)}") }
                            }
                        }
                    }
                    OutlinedButton(onClick = { started = true; error = access.discoverRoute(node.id).exceptionOrNull()?.message }, enabled = !operation.busy && snapshot != null) { Text("Discover route") }
                    Text("Discovery sends an RF request. Review the returned path and tap Save route to apply it.", style = MaterialTheme.typography.bodySmall)
                    snapshot?.discovered?.let { route -> NodeDetailSection("Discovered route") { Text(label(route)); TextButton(onClick = { use(route) }, enabled = !operation.busy) { Text("Use discovered route") } } }
                    if (snapshot?.history?.isNotEmpty() == true) NodeDetailSection("Recent routes") {
                        snapshot.history.forEach { route -> TextButton(onClick = { use(route) }, enabled = !operation.busy) { Text(label(route)) } }
                    }
                    if (operation.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (started) operation.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    (error ?: operation.error.takeIf { started })?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { snapshot?.current?.let(::use) }, enabled = !operation.busy && snapshot != null, modifier = Modifier.weight(1f)) { Text("Reset edits") }
                    Button(onClick = { started = true; error = access.setRoute(node.id, draft.getOrThrow()).exceptionOrNull()?.message }, enabled = !operation.busy && initialized && draft.isSuccess && draft.getOrNull() != snapshot?.current, modifier = Modifier.weight(1f)) { Text("Save route") }
                }
            }
        }
    }
}
