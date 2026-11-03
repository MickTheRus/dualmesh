package io.dualmesh.app.backend

import io.dualmesh.app.model.ConnectionState
import io.dualmesh.app.model.MeshNetwork
import io.dualmesh.app.model.MeshNode
import io.dualmesh.app.model.RadioChannel
import io.dualmesh.app.model.RadioContact
import io.dualmesh.app.model.TraceRouteResult
import io.dualmesh.app.model.UnifiedMessage
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface MessagingBackend {
    val settingsAccess: io.dualmesh.app.settings.RadioSettingsAccess? get() = null
    val network: MeshNetwork
    val maxMessageBytes: Int
    val connectionState: StateFlow<ConnectionState>
    val incomingMessages: SharedFlow<UnifiedMessage>
    val traceRouteResults: SharedFlow<TraceRouteResult>
    val connectedDeviceName: StateFlow<String?>
    val channels: StateFlow<List<RadioChannel>>
    val contacts: StateFlow<List<RadioContact>>
    val nodes: StateFlow<List<MeshNode>>
    val channelCapacity: StateFlow<Int>
    val selectedChannel: StateFlow<Int>

    fun connect(address: String, name: String)
    fun disconnect()
    fun selectChannel(index: Int)
    fun sendChannelMessage(text: String, channel: Int = 0): Result<Unit>
    fun sendDirectMessage(text: String, contactId: String): Result<Unit>
    fun traceRoute(contactId: String): Result<Unit>
}
