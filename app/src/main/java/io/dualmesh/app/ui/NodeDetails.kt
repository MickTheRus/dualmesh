package io.dualmesh.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.dualmesh.app.model.*
import java.text.DateFormat
import java.util.Date

@Composable
internal fun NodeDetailContent(
    node: MeshNode, favorite: Boolean, onFavorite: () -> Unit,
    traces: List<TraceRouteResult>, traceEnabled: Boolean, onTrace: () -> Unit,
    onMap: () -> Unit, onStats: (() -> Unit)?, onRoute: (() -> Unit)?,
) {
    val accent = if (node.network == MeshNetwork.MESHCORE) NeonViolet else NeonBlue
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(node.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(node.shortName ?: node.network.label, style = MaterialTheme.typography.titleSmall, color = accent)
            }
            FavoriteNodeButton(favorite, onFavorite)
        }
        NodeDetailSection("Last heard") {
            DetailValue("Seen", nodeLastSeenLabel(node).removePrefix("Last heard "))
            DetailValue("Time", node.lastHeardMillis.takeIf { it > 0 }?.let(::nodeTimestamp) ?: "Unknown")
            node.hopsAway?.let { DetailValue("Distance", if (it == 0) "Direct" else "$it hops") }
            node.snr?.let { DetailValue("SNR", "%.1f dB".format(it)) }
            node.rssi?.let { DetailValue("RSSI", "$it dBm") }
        }
        NodeDetailSection("Location") {
            if (node.hasValidMapPosition()) {
                DetailValue("Coordinates", "%.6f, %.6f".format(node.latitude, node.longitude))
                node.altitudeMeters?.let { DetailValue("Altitude", "$it m") }
                node.positionTimeMillis?.let { DetailValue("Updated", nodeTimestamp(it)) }
                node.precisionBits?.let { DetailValue("Precision", "$it bits") }
                TextButton(onClick = onMap) { Text("Show on map") }
            } else Text("No location reported", style = MaterialTheme.typography.bodyMedium)
        }
        NodeDetailSection("Source") {
            DetailValue("Network", node.network.label)
            DetailValue("Radio", node.deviceName.ifBlank { "Saved radio" })
            DetailValue("Reported by", node.source)
            DetailValue("Transport", when (node.viaMqtt) { true -> "MQTT"; false -> "Radio"; null -> "Not reported" })
            node.channel?.let { DetailValue("Channel", "$it") }
        }
        NodeDetailSection("Node info") {
            DetailValue("Node ID", nodeDisplayId(node))
            node.publicKey?.takeUnless { it.equals(node.id, ignoreCase = true) }?.let { DetailValue("Public key", it) }
            node.hardware?.let { DetailValue("Hardware", it) }
            node.role?.let { DetailValue("Role", it) }
            node.messageable?.let { DetailValue("Messaging", if (it) "Available" else "Unavailable / local node") }
            node.flags?.let { DetailValue("Flags", "0x${it.toString(16)}") }
            onStats?.let { TextButton(onClick = it) { Text("Request node stats") } }
        }
        if (node.network == MeshNetwork.MESHCORE) {
            NodeDetailSection("Route") {
                Text("Choose a direct path, flood, or an ordered repeater route on the source radio.", style = MaterialTheme.typography.bodyMedium)
                if (onRoute != null) TextButton(onClick = onRoute) { Text("Set route") }
                else Text("Connect the source radio to manage this route.", style = MaterialTheme.typography.bodySmall)
            }
        }
        NodeDetailSection("Traceroute") {
            val latest = traces.lastOrNull()
            OutlinedButton(onClick = onTrace, enabled = traceEnabled && latest?.state != TraceRouteState.PENDING) { Text(if (latest?.state == TraceRouteState.PENDING) "Tracing…" else "Trace route") }
            if (latest == null) Text("No traceroutes yet", style = MaterialTheme.typography.bodySmall)
            else TraceRouteStatus(latest)
            var expanded by remember(node.deviceId, node.id) { mutableStateOf(false) }
            if (traces.size > 1) {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide history" else "History (${traces.size - 1})") }
                if (expanded) traces.dropLast(1).asReversed().take(10).forEach { TraceRouteStatus(it) }
            }
        }
    }
}

@Composable
internal fun NodeDetailSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun DetailValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(0.4f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer(Modifier.weight(0.6f)) { Text(value, style = MaterialTheme.typography.bodyMedium) }
    }
}

internal fun nodeDisplayId(node: MeshNode): String = if (node.network == MeshNetwork.MESHTASTIC)
    runCatching { "!${node.id.toUInt().toString(16).padStart(8, '0')}" }.getOrDefault(node.id) else node.id
private fun nodeTimestamp(time: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))
