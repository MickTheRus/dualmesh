package io.dualmesh.app.ui

import io.dualmesh.app.model.MeshNetwork
import io.dualmesh.app.model.MeshNode
import org.junit.Assert.*
import org.junit.Test

class NodeMapFiltersTest {
    private val now = 1_000_000_000L
    private val mt = MeshNode(
        network = MeshNetwork.MESHTASTIC, deviceId = "mt-radio", deviceName = "Test MT radio",
        id = "1", name = "Test MT", shortName = "MT", hopsAway = 0, viaMqtt = false,
        lastHeardMillis = now - 60_000, latitude = 33.4, longitude = -112.0,
        role = "CLIENT", source = "test",
    )
    private val mc = mt.copy(
        network = MeshNetwork.MESHCORE, deviceId = "mc-radio", id = "abcdef0123456789",
        name = "Test MC", shortName = null, hopsAway = null, viaMqtt = null, role = "Chat",
    )
    private fun visible(nodes: List<MeshNode>, filters: NodeMapFilters = NodeMapFilters(), query: String = "") =
        filterMapNodes(nodes, filters, query, now)

    @Test fun `both networks are visible by default and can be selected independently`() {
        val nodes = listOf(mt, mc)
        assertEquals(nodes, visible(nodes))
        assertEquals(listOf(mc), visible(nodes, NodeMapFilters(meshtastic = false)))
        assertEquals(listOf(mt), visible(nodes, NodeMapFilters(meshCore = false)))
        assertTrue(visible(nodes, NodeMapFilters(meshCore = false, meshtastic = false)).isEmpty())
    }

    @Test fun `unknown MT means missing short name and unknown MC means missing advertised name`() {
        val unknownMT = mt.copy(shortName = null)
        val unknownMC = mc.copy(name = mc.id.take(12))
        val nodes = listOf(mt, mc, unknownMT, unknownMC)
        assertEquals(nodes, visible(nodes))
        assertEquals(listOf(mt, mc), visible(nodes, NodeMapFilters(includeUnknown = false)))
    }

    @Test fun `MQTT exclusion keeps RF and unavailable bearer data`() {
        val nodes = listOf(mt, mc, mt.copy(id = "2", viaMqtt = true))
        assertEquals(listOf(mt, mc), visible(nodes, NodeMapFilters(excludeMqtt = true)))
    }

    @Test fun `direct requires known zero hop RF and never assumes unknown hops are direct`() {
        val nodes = listOf(mt, mc, mt.copy(hopsAway = -1), mt.copy(hopsAway = 1), mt.copy(viaMqtt = true))
        assertEquals(listOf(mt), visible(nodes, NodeMapFilters(onlyDirect = true)))
    }

    @Test fun `online uses two hour window and excludes missing last heard`() {
        val boundary = mt.copy(lastHeardMillis = now - 2 * 3_600_000)
        val stale = mt.copy(lastHeardMillis = boundary.lastHeardMillis - 1)
        val unheard = mt.copy(lastHeardMillis = 0)
        val future = mt.copy(lastHeardMillis = now + 1_000)
        assertEquals(listOf(mt, boundary, future), visible(listOf(mt, boundary, stale, unheard, future), NodeMapFilters(onlyOnline = true)))
        assertEquals(listOf(stale), visible(listOf(stale, unheard), NodeMapFilters(hideUnheard = true)))
    }

    @Test fun `last heard time filter composes with query protocol and radio selection`() {
        val old = mt.copy(lastHeardMillis = now - 2 * 3_600_000)
        val otherRadio = mt.copy(deviceId = "other-radio")
        val filters = NodeMapFilters(meshCore = false, lastHeardHours = 1, radioId = "mt-radio")
        assertEquals(listOf(mt), visible(listOf(mt, old, mc, otherRadio), filters, "test"))
        assertTrue(visible(listOf(mt), filters, "missing").isEmpty())
    }

    @Test fun `infrastructure and role filters support both protocols`() {
        val router = mt.copy(role = "ROUTER")
        val repeater = mc.copy(role = "Repeater")
        val room = mc.copy(role = "Room")
        assertEquals(listOf(mt, mc), visible(listOf(mt, mc, router, repeater, room), NodeMapFilters(excludeInfrastructure = true)))
        assertEquals(listOf(mc), visible(listOf(mt, mc), NodeMapFilters(excludedRoles = setOf(mapRoleKey(mt)))))
    }

    @Test fun `positions reject missing nonfinite out of range and zero placeholder coordinates`() {
        assertTrue(mt.hasValidMapPosition())
        assertTrue(mt.copy(latitude = 0.0).hasValidMapPosition())
        assertTrue(mt.copy(longitude = 0.0).hasValidMapPosition())
        listOf(
            mt.copy(latitude = null), mt.copy(longitude = null), mt.copy(latitude = Double.NaN),
            mt.copy(longitude = Double.POSITIVE_INFINITY), mt.copy(latitude = 91.0),
            mt.copy(longitude = -181.0), mt.copy(latitude = 0.0, longitude = 0.0),
        ).forEach { assertFalse(it.hasValidMapPosition()) }
    }

    @Test fun `directory keeps unpositioned nodes and keys distinguish protocol and radio`() {
        val noPosition = mt.copy(latitude = null, longitude = null)
        assertEquals(listOf(noPosition), visible(listOf(noPosition)))
        assertNotEquals(mapNodeKey(mt), mapNodeKey(mt.copy(deviceId = "other")))
        assertNotEquals(mapNodeKey(mt), mapNodeKey(mt.copy(network = MeshNetwork.MESHCORE)))
        assertEquals(listOf(mt), visible(listOf(mt, mc), query = "Meshtastic"))
    }
}
