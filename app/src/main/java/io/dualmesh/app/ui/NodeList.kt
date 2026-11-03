package io.dualmesh.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.dualmesh.app.model.MeshNetwork
import io.dualmesh.app.model.MeshNode
import io.dualmesh.app.nodes.NodeActions
import java.text.DateFormat
import java.util.Date

internal enum class NodeSort(val label: String) {
    LAST_HEARD("Last heard"), NAME("Name"), HOPS("Hops"), SIGNAL("Signal")
}

internal fun sortNodeList(nodes: List<MeshNode>, sort: NodeSort): List<MeshNode> {
    val comparator = when (sort) {
        NodeSort.LAST_HEARD -> compareByDescending<MeshNode> { it.lastHeardMillis }
        NodeSort.NAME -> compareBy { it.name.lowercase(java.util.Locale.ROOT) }
        NodeSort.HOPS -> compareBy { it.hopsAway ?: Int.MAX_VALUE }
        NodeSort.SIGNAL -> compareByDescending { it.snr ?: Float.NEGATIVE_INFINITY }
    }
    return nodes.sortedWith(comparator.thenBy { it.name.lowercase(java.util.Locale.ROOT) }.thenBy(::mapNodeKey))
}

@Composable
internal fun CompactNodeRow(node: MeshNode, onClick: () -> Unit) {
    val accent = if (node.network == MeshNetwork.MESHCORE) NeonViolet else NeonBlue
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(44.dp), shape = CircleShape, color = accent.copy(alpha = 0.15f), contentColor = accent) {
            Box(contentAlignment = Alignment.Center) {
                Text(node.shortName?.takeIf { it.isNotBlank() }?.take(4) ?: node.name.take(3), style = MaterialTheme.typography.labelLarge, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            Text(node.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${if (node.network == MeshNetwork.MESHCORE) "MC" else "MT"} · ${node.deviceName.ifBlank { "Saved radio" }}${node.role?.let { " · $it" }.orEmpty()}", style = MaterialTheme.typography.bodySmall, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(nodeLastSeenLabel(node), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(if (node.viaMqtt == true) "MQTT" else node.hopsAway?.let { if (it == 0) "Direct" else "$it hops" } ?: "—", style = MaterialTheme.typography.labelMedium)
            node.snr?.let { Text("%.1f dB".format(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
internal fun NodeActionsDialog(access: NodeActions, network: MeshNetwork, radioName: String, nodeId: String? = null, onDismiss: () -> Unit) {
    val state by access.state.collectAsState()
    var actionError by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (nodeId == null) "Radio actions" else "Node statistics") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(radioName, style = MaterialTheme.typography.titleSmall)
                    if (nodeId != null) Text(nodeId, style = MaterialTheme.typography.bodySmall)
                    FilledTonalButton(onClick = { actionError = access.requestStats(nodeId).exceptionOrNull()?.message }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.busy) "Working…" else "Request stats")
                    }
                    if (nodeId == null) {
                        Text(if (network == MeshNetwork.MESHCORE) "Advertise this radio" else "Broadcast this radio’s user info", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                        Text(if (network == MeshNetwork.MESHCORE) "Uses the radio’s configured advert location policy and flood scope." else "Uses the selected channel. Flood follows the radio’s configured hop limit.", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { actionError = access.advertise(false).exceptionOrNull()?.message }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Zero-hop") }
                            OutlinedButton(onClick = { actionError = access.advertise(true).exceptionOrNull()?.message }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Flood") }
                        }
                    }
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    (actionError ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                if (state.stats.isNotEmpty()) {
                    item {
                        HorizontalDivider()
                        Text("Stats: ${state.statsTarget.orEmpty()}", style = MaterialTheme.typography.titleSmall)
                        state.updatedAtMillis?.let { Text("Read ${DateFormat.getTimeInstance().format(Date(it))}", style = MaterialTheme.typography.bodySmall) }
                    }
                    state.stats.forEach { group ->
                        item { Text(group.title, style = MaterialTheme.typography.titleSmall) }
                        items(group.values.size) { index ->
                            val (label, value) = group.values[index]
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                Text(value, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
