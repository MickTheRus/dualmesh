package io.dualmesh.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.dualmesh.app.model.DeliveryState
import io.dualmesh.app.model.UnifiedMessage
import java.text.DateFormat
import java.util.Date

internal fun messageDeliveryLabel(message: UnifiedMessage): String = when (message.deliveryState) {
    DeliveryState.RECEIVED -> if (message.outgoing) "Sent" else "Received"
    DeliveryState.SENDING -> "Queued"
    DeliveryState.SENT_TO_RADIO -> "Sent to radio"
    DeliveryState.SENT -> "Sent"
    DeliveryState.DELIVERED_TO_MESH -> if (message.conversationId.startsWith("contact:")) "Relayed" else "Delivered to mesh"
    DeliveryState.DELIVERED -> "Delivered"
    DeliveryState.UNCONFIRMED -> "Unconfirmed"
    DeliveryState.ERROR -> "Failed"
}

internal fun messageReceptionLabel(message: UnifiedMessage): String = listOf(
    when (message.viaMqtt) { true -> "MQTT"; false -> "RF"; null -> "Source unknown" },
    when (message.hopsAway) { 0 -> "Direct · 0 hops"; 1 -> "1 hop"; null -> "Hops unknown"; else -> "${message.hopsAway} hops" },
).joinToString(" · ")

@Composable
internal fun MessageMetadata(message: UnifiedMessage) {
    var showDetails by rememberSaveable(message.id) { mutableStateOf(false) }
    val symbol = when (message.deliveryState) {
        DeliveryState.DELIVERED -> "✓✓"
        DeliveryState.DELIVERED_TO_MESH, DeliveryState.SENT -> "✓"
        DeliveryState.ERROR -> "!"
        DeliveryState.UNCONFIRMED -> "?"
        else -> "↑"
    }
    val color = when {
        !message.outgoing -> MaterialTheme.colorScheme.onSurfaceVariant
        message.deliveryState == DeliveryState.DELIVERED -> SignalGreen
        message.deliveryState == DeliveryState.ERROR -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = if (message.outgoing) "$symbol ${messageDeliveryLabel(message)}" else messageReceptionLabel(message),
        modifier = Modifier.clickable { showDetails = true }.padding(horizontal = 8.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
    if (showDetails) AlertDialog(
        onDismissRequest = { showDetails = false },
        title = { Text("Message details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${message.sender} · ${DateFormat.getDateTimeInstance().format(Date(message.timestampMillis))}")
                Text("Radio: ${message.deviceName ?: message.network.label}")
                if (message.outgoing) {
                    Text("Delivery: ${messageDeliveryLabel(message)}")
                    Text(message.deliveryDetail ?: when (message.deliveryState) {
                        DeliveryState.DELIVERED -> "Acknowledged by the recipient's radio. This is not a read receipt."
                        DeliveryState.SENT_TO_RADIO, DeliveryState.SENDING -> "Handed to the phone-to-radio transport; delivery has not been confirmed."
                        else -> "No additional delivery information was reported."
                    })
                } else {
                    Text(messageReceptionLabel(message))
                    message.receptionPath?.let { Text("Route: $it") }
                    if (message.receptionPath == "Direct route" && message.hopsAway == null) Text("MeshCore does not report the receive hop count for a direct route.")
                    message.snr?.let { Text("SNR: $it dB") }
                    message.rssi?.let { Text("RSSI: $it dBm") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showDetails = false }) { Text("Done") } },
    )
}
