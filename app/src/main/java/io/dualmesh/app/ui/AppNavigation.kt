package io.dualmesh.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dualmesh.app.DualMeshManager
import io.dualmesh.app.backend.MultiDeviceBackend
import io.dualmesh.app.model.ConnectionState
import io.dualmesh.app.model.MeshNetwork

internal enum class AppDestination(val label: String) {
    CHANNELS("Channels"), NODES("Nodes"), MAP("Map"), SETTINGS("Settings"), CONNECTIONS("Connections"),
}

@Composable
internal fun DestinationIcon(destination: AppDestination) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val scale = size.width / 24f
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(color, Offset(x * scale, y * scale), Offset(x2 * scale, y2 * scale), 1.8f * scale)
        fun circle(x: Float, y: Float, radius: Float) = drawCircle(color, radius * scale, Offset(x * scale, y * scale), style = Stroke(1.8f * scale))
        when (destination) {
            AppDestination.CHANNELS -> {
                val path = Path().apply { moveTo(3*scale,3*scale); lineTo(21*scale,3*scale); lineTo(21*scale,17*scale); lineTo(9*scale,17*scale); lineTo(3*scale,22*scale); close() }
                drawPath(path, color, style = Stroke(1.8f*scale)); line(7f,8f,17f,8f); line(7f,12f,14f,12f)
            }
            AppDestination.NODES -> {
                circle(12f,5f,2f); circle(5f,18f,2f); circle(19f,18f,2f)
                line(11f,7f,6f,16f); line(13f,7f,18f,16f); line(7f,18f,17f,18f)
            }
            AppDestination.MAP -> {
                val path = Path().apply { moveTo(2*scale,5*scale); lineTo(8*scale,2*scale); lineTo(16*scale,5*scale); lineTo(22*scale,2*scale); lineTo(22*scale,19*scale); lineTo(16*scale,22*scale); lineTo(8*scale,19*scale); lineTo(2*scale,22*scale); close() }
                drawPath(path,color,style=Stroke(1.8f*scale)); line(8f,2f,8f,19f); line(16f,5f,16f,22f)
            }
            AppDestination.SETTINGS -> {
                line(3f,6f,21f,6f); line(3f,12f,21f,12f); line(3f,18f,21f,18f)
                circle(8f,6f,2f); circle(16f,12f,2f); circle(10f,18f,2f)
            }
            AppDestination.CONNECTIONS -> {
                circle(12f,12f,2f)
                drawArc(color, -55f, 110f, false, Offset(5*scale,5*scale), androidx.compose.ui.geometry.Size(14*scale,14*scale), style=Stroke(1.8f*scale))
                drawArc(color, 125f, 110f, false, Offset(5*scale,5*scale), androidx.compose.ui.geometry.Size(14*scale,14*scale), style=Stroke(1.8f*scale))
                drawArc(color, -55f, 110f, false, Offset(scale,scale), androidx.compose.ui.geometry.Size(22*scale,22*scale), style=Stroke(1.8f*scale))
                drawArc(color, 125f, 110f, false, Offset(scale,scale), androidx.compose.ui.geometry.Size(22*scale,22*scale), style=Stroke(1.8f*scale))
            }
        }
    }
}

@Composable
internal fun RadioSettingsScreen(manager: DualMeshManager, layout: PhoneLayoutMode,
    onLayoutChange: (PhoneLayoutMode) -> Unit, onPacketLogs: () -> Unit) {
    val mc by manager.meshCore.deviceConnections.collectAsStateWithLifecycle()
    val mt by manager.meshtastic.deviceConnections.collectAsStateWithLifecycle()
    val connections = mt + mc
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text("Settings", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
            Text("Radios", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
        }
        items(connections, key = { MultiDeviceBackend.targetId(it.profile) }) { connection ->
            val id = MultiDeviceBackend.targetId(connection.profile)
            ListItem(
                headlineContent = { Text(connection.profile.name) },
                supportingContent = { Text("${connection.profile.network.label} · ${connection.profile.transport.displayName()} · ${connection.state.name.lowercase().replaceFirstChar { it.uppercase() }}") },
                trailingContent = { Text("›") },
                modifier = Modifier.clickable(enabled = connection.state == ConnectionState.CONNECTED) { editingId = id },
            )
            HorizontalDivider(thickness = 0.5.dp)
        }
        item {
            if (connections.isEmpty()) Text("Choose a radio in Connections to view its settings.", Modifier.padding(16.dp))
            Spacer(Modifier.height(16.dp))
            Text("App", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
            Text("Phone layout", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyLarge)
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PhoneLayoutMode.entries.forEach { mode ->
                    FilterChip(selected = layout == mode, onClick = { onLayoutChange(mode) }, label = { Text(mode.label) })
                }
            }
            Text("Focused shows one mesh; Stacked shows both. Tablets use side by side.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            ListItem(headlineContent = { Text("Packet logs") }, supportingContent = { Text("Inspect or record protocol traffic") },
                trailingContent = { Text("›") }, modifier = Modifier.clickable(onClick = onPacketLogs))
        }
    }
    val editing = connections.firstOrNull { MultiDeviceBackend.targetId(it.profile) == editingId }
    if (editing != null) {
        val backend = if (editing.profile.network == MeshNetwork.MESHCORE) manager.meshCore else manager.meshtastic
        val access = backend.settingsFor(editingId!!)
        if (access != null) RadioSettingsDialog(access, editing.profile.name, editing.state == ConnectionState.CONNECTED, onDismiss = { editingId = null })
    }
}
