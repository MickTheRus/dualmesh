package io.dualmesh.app.ui

import io.dualmesh.app.model.MeshNetwork
import io.dualmesh.app.model.MeshNode
import org.junit.Assert.assertEquals
import org.junit.Test

class NodeSearchTest {
    private val nodes = listOf(
        MeshNode(
            network = MeshNetwork.MESHTASTIC,
            id = "305419896",
            name = "Meshtastic Test Node",
            shortName = "TEST",
            hardware = "T_ECHO",
            role = "CLIENT_MUTE",
            channel = 2,
            hopsAway = 0,
            source = "Meshtastic NODEINFO_APP",
        ),
        MeshNode(
            network = MeshNetwork.MESHCORE,
            id = "0011223344",
            name = "MeshCore Test Node",
            role = "CHAT",
            source = "MeshCore contact",
        ),
    )

    @Test
    fun searchesMeshtasticNameAndDisplayedHexId() {
        assertEquals(listOf(nodes[0]), filterNodes(nodes, "MESHTASTIC"))
        assertEquals(listOf(nodes[0]), filterNodes(nodes, "!12345678"))
    }

    @Test
    fun matchesAllSearchTermsAcrossNodeFields() {
        assertEquals(listOf(nodes[0]), filterNodes(nodes, "echo channel 2"))
        assertEquals(emptyList<MeshNode>(), filterNodes(nodes, "echo channel 1"))
        assertEquals(listOf(nodes[0]), filterNodes(nodes, "ch 2"))
    }

    @Test
    fun searchesMeshCoreKeyAndKeepsOriginalOrderForBlankQuery() {
        assertEquals(listOf(nodes[1]), filterNodes(nodes, "001122"))
        assertEquals(nodes, filterNodes(nodes, "  "))
    }
}
