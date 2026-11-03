package io.dualmesh.app.screenshots

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import io.dualmesh.app.MainViewModel
import io.dualmesh.app.DualMeshManager
import io.dualmesh.app.backend.MultiDeviceBackend
import io.dualmesh.app.connection.ConnectionProfileStore.Profile
import io.dualmesh.app.connection.ConnectionProfileStore.Transport
import io.dualmesh.app.model.*
import io.dualmesh.app.transport.UsbRadioTarget
import io.dualmesh.app.ui.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Only compiled into io.dualmesh.app.screenshots; all identities, traffic and positions are fictional. */
class ScreenshotActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleScope.launch {
            val manager = viewModel.manager
            listOf("historyReady", "nodesReady", "tracesReady").forEach {
                (read(manager, it) as CompletableDeferred<*>).await()
            }
            fixtures(manager)
            setContent { DualMeshApp(viewModel, requestPermissions = {}) }
        }
    }

    private fun fixtures(manager: DualMeshManager) {
        val now = System.currentTimeMillis()
        val mt = Profile(MeshNetwork.MESHTASTIC, Transport.BLE, "02:00:00:00:00:01", "Trail Base", enabled = true)
        val mc = Profile(MeshNetwork.MESHCORE, Transport.TCP, "192.0.2.10:5000", "Camp Companion", enabled = true)
        val usb = Profile(MeshNetwork.MESHTASTIC, Transport.USB, UsbRadioTarget(0x10c4, 0xea60, 0, "DEMO-USB", "").encode(), "Spare USB radio")
        val mtId = MultiDeviceBackend.targetId(mt)
        val mcId = MultiDeviceBackend.targetId(mc)
        val mtNodes = listOf(
            MeshNode(MeshNetwork.MESHTASTIC, mtId, mt.name, "1001", "Trail Base", "BASE", hardware = "HELTEC_V3", role = "CLIENT", channel = 1, viaMqtt = false, messageable = false,
                lastHeardMillis = now - 20_000, snr = 8.5f, rssi = -82, hopsAway = 0, latitude = 37.7694, longitude = -122.4845, source = "Meshtastic NODEINFO_APP"),
            MeshNode(MeshNetwork.MESHTASTIC, mtId, mt.name, "1003", "Ridge Scout", "RDGE", hardware = "RAK4631", role = "CLIENT", channel = 1, viaMqtt = false, messageable = true,
                lastHeardMillis = now - 65_000, snr = 6.25f, rssi = -96, hopsAway = 2, latitude = 37.7741, longitude = -122.4692, altitudeMeters = 52, positionTimeMillis = now - 65_000, source = "Meshtastic NODEINFO_APP"),
            MeshNode(MeshNetwork.MESHTASTIC, mtId, mt.name, "1004", "Park Gateway", "GATE", hardware = "STATION_G2", role = "ROUTER", channel = 1, viaMqtt = true, messageable = true,
                lastHeardMillis = now - 180_000, hopsAway = 1, latitude = 37.7680, longitude = -122.4580, source = "Meshtastic NODEINFO_APP"),
            MeshNode(MeshNetwork.MESHTASTIC, mtId, mt.name, "1005", "Lake Walker", "LAKE", hardware = "T_ECHO", role = "CLIENT", channel = 1, viaMqtt = false, messageable = true,
                lastHeardMillis = now - 380_000, snr = 4.5f, rssi = -104, hopsAway = 1, latitude = 37.7685, longitude = -122.4763, source = "Meshtastic NODEINFO_APP"),
        )
        val mcNodes = listOf(
            MeshNode(MeshNetwork.MESHCORE, mcId, mc.name, "11".repeat(32), "Camp Relay", "CAMP", hardware = "Heltec V3", role = "Repeater", viaMqtt = false, messageable = true,
                lastHeardMillis = now - 95_000, snr = 9f, rssi = -88, latitude = 37.7662, longitude = -122.4778, source = "MeshCore contact advert"),
            MeshNode(MeshNetwork.MESHCORE, mcId, mc.name, "22".repeat(32), "Meadow Hiker", "MDOW", hardware = "RAK4631", role = "Companion", viaMqtt = false, messageable = true,
                lastHeardMillis = now - 240_000, snr = 5.5f, latitude = 37.7710, longitude = -122.4645, source = "MeshCore contact advert"),
        )
        val mtChannels = listOf(RadioChannel(0, "LongFast"), RadioChannel(1, "Trail crew"))
        val mcChannels = listOf(RadioChannel(0, "Public"), RadioChannel(1, "Camp"))
        listOf(Triple(manager.meshtastic, mt, mtNodes), Triple(manager.meshCore, mc, mcNodes)).forEach { (backend, profile, nodes) ->
            val channels = if (profile == mt) mtChannels else mcChannels
            val contacts = nodes.filter { it.messageable == true }.map { RadioContact(it.id, it.name) }
            set(backend, "_connectionState", ConnectionState.CONNECTED)
            set(backend, "_connectedDeviceName", profile.name)
            set(backend, "_channels", channels)
            set(backend, "_contacts", contacts)
            set(backend, "_nodes", nodes)
            set(backend, "_selectedChannel", if (profile == mt) 1 else 0)
            set(backend, "_deviceConnections", listOf(MultiDeviceBackend.DeviceConnection(profile, ConnectionState.CONNECTED, true, channels, contacts, nodes)))
        }
        set(manager, "_selectedNetwork", MeshNetwork.MESHTASTIC)
        set(manager, "_selectedDeviceIds", mapOf(MeshNetwork.MESHTASTIC to mtId, MeshNetwork.MESHCORE to mcId))
        set(manager, "_selectedConversationIds", mapOf(MeshNetwork.MESHTASTIC to "channel:1", MeshNetwork.MESHCORE to "channel:0"))
        set(manager, "_connectionHistory", mapOf(MeshNetwork.MESHTASTIC to listOf(mt, usb), MeshNetwork.MESHCORE to listOf(mc)))
        val nodes = mtNodes + mcNodes
        set(manager, "_nodes", nodes)
        NodeMapPreferences(this).save(NodeMapFilters(darkTiles = true))
        NodeFavorites(this).save(setOf(mapNodeKey(mtNodes[1]), mapNodeKey(mcNodes[0])))
        fun message(id: String, network: MeshNetwork, sender: String, text: String, minutes: Int, outgoing: Boolean = false, state: DeliveryState = DeliveryState.RECEIVED, mqtt: Boolean? = false, hops: Int? = 0): UnifiedMessage {
            val profile = if (network == MeshNetwork.MESHTASTIC) mt else mc
            return UnifiedMessage(id, network, if (network == MeshNetwork.MESHTASTIC) "channel:1" else "channel:0", sender, text, now - minutes * 60_000, outgoing, state,
                MultiDeviceBackend.targetId(profile), profile.name, hopsAway = if (outgoing) null else hops, viaMqtt = if (outgoing) null else mqtt,
                channelIndex = if (network == MeshNetwork.MESHTASTIC) 1 else 0,
                deliveryDetail = if (outgoing) "Acknowledged by the mesh. Channel broadcasts do not confirm delivery to every recipient." else null)
        }
        val mt1 = message("101", MeshNetwork.MESHTASTIC, "Ridge Scout", "Base camp is ready. Anyone heading to the ridge?", 12, hops = 2)
        val mt2 = message("102", MeshNetwork.MESHTASTIC, "Park Gateway", "At the trailhead. Heading up in ten minutes.", 7, mqtt = true, hops = 1)
        val mt3 = message("103", MeshNetwork.MESHTASTIC, "You", "On my way. See you at the trailhead!", 3, true, DeliveryState.DELIVERED_TO_MESH, mqtt = null)
        val mc1 = message("mc-101", MeshNetwork.MESHCORE, "Meadow Hiker", "Signal is clear on the west trail.", 9, hops = 2)
        val mc2 = message("mc-102", MeshNetwork.MESHCORE, "You", "Thanks! We'll keep this channel open.", 4, true, DeliveryState.SENT, mqtt = null).copy(deliveryDetail = "Accepted by the radio. MeshCore channel broadcasts do not provide delivery acknowledgements.")
        val messages = listOf(mt1, mt2, mt3, mc1, mc2,
            message("201", MeshNetwork.MESHTASTIC, "Ridge Scout", "👍", 2).copy(reactionToMessageId = mt3.id, reactionEmoji = "👍"),
            message("202", MeshNetwork.MESHTASTIC, "Lake Walker", "👍", 2).copy(reactionToMessageId = mt3.id, reactionEmoji = "👍"),
            message("203", MeshNetwork.MESHTASTIC, "You", "❤️", 1, true, DeliveryState.DELIVERED_TO_MESH).copy(reactionToMessageId = mt2.id, reactionEmoji = "❤️"))
        set(manager, "_messages", messages.sortedBy { it.timestampMillis })
        set(manager, "_traceRouteResults", listOf(TraceRouteResult("demo-trace", MeshNetwork.MESHTASTIC, mtId, mt.name, "1003", "Ridge Scout", TraceRouteState.COMPLETE,
            forward = listOf(TraceRouteHop("1001", "Trail Base"), TraceRouteHop("1004", "Park Gateway", 8.5f), TraceRouteHop("1003", "Ridge Scout", 6.25f)),
            returned = listOf(TraceRouteHop("1003", "Ridge Scout"), TraceRouteHop("1004", "Park Gateway", 7f), TraceRouteHop("1001", "Trail Base", 9.25f)),
            startedAtMillis = now - 120_000, completedAtMillis = now - 118_000, detail = "Routes returned in both directions.")))
    }

    // Keep fixture injection out of the production API and all ordinary build variants.
    private fun read(target: Any, name: String): Any = target.javaClass.getDeclaredField(name).run { isAccessible = true; get(target)!! }
    @Suppress("UNCHECKED_CAST")
    private fun <T> set(target: Any, name: String, value: T) { (read(target, name) as MutableStateFlow<T>).value = value }
}
