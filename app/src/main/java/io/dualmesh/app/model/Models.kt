package io.dualmesh.app.model

enum class MeshNetwork(val label: String) {
    MESHCORE("MeshCore"),
    MESHTASTIC("Meshtastic"),
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

enum class DeliveryState {
    RECEIVED,
    SENDING,
    SENT_TO_RADIO,
    ERROR,
}

data class RadioDevice(
    val address: String,
    val name: String,
    val network: MeshNetwork,
    val rssi: Int,
)

data class RadioChannel(
    val index: Int,
    val name: String,
    val role: String? = null,
)

data class RadioContact(
    val id: String,
    val name: String,
)

data class MeshNode(
    val network: MeshNetwork,
    val deviceId: String = "",
    val deviceName: String = "",
    val id: String,
    val name: String,
    val shortName: String? = null,
    val publicKey: String? = null,
    val hardware: String? = null,
    val role: String? = null,
    val channel: Int? = null,
    val viaMqtt: Boolean? = null,
    val messageable: Boolean? = null,
    val flags: Int? = null,
    val lastHeardMillis: Long = 0,
    val snr: Float? = null,
    val rssi: Int? = null,
    val hopsAway: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeMeters: Int? = null,
    val positionTimeMillis: Long? = null,
    val precisionBits: Int? = null,
    val source: String,
) {
    val hasPosition: Boolean
        get() = latitude != null && longitude != null
}

enum class TraceRouteState {
    PENDING,
    COMPLETE,
    TIMED_OUT,
    ERROR,
}

data class TraceRouteHop(
    val id: String,
    val name: String,
    val snr: Float? = null,
)

data class TraceRouteResult(
    val id: String,
    val network: MeshNetwork,
    val deviceId: String = "",
    val deviceName: String = "",
    val targetId: String,
    val targetName: String,
    val state: TraceRouteState,
    val forward: List<TraceRouteHop> = emptyList(),
    val returned: List<TraceRouteHop> = emptyList(),
    val startedAtMillis: Long,
    val completedAtMillis: Long? = null,
    val detail: String? = null,
)

data class UnifiedMessage(
    val id: String,
    val network: MeshNetwork,
    val conversationId: String,
    val sender: String,
    val text: String,
    val timestampMillis: Long,
    val outgoing: Boolean,
    val deliveryState: DeliveryState,
    val deviceId: String = "",
    val deviceName: String? = null,
    val reactionToMessageId: String? = null,
    val reactionEmoji: String? = null,
) {
    val isReaction: Boolean
        get() = reactionToMessageId != null && reactionEmoji != null
}
