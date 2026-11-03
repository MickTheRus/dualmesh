package io.dualmesh.app.ui

import io.dualmesh.app.model.MeshNetwork
import io.dualmesh.app.model.MeshNode
import org.junit.Assert.assertEquals
import org.junit.Test

class NodeListSortTest {
    @Test fun sortsKnownHopsAndSignalAheadOfUnknownAndBreaksTiesDeterministically() {
        val nodes = listOf(
            MeshNode(MeshNetwork.MESHCORE, id = "a", name = "Zulu", source = "test"),
            MeshNode(MeshNetwork.MESHTASTIC, id = "b", name = "bravo", source = "test", hopsAway = 0, snr = -5f, lastHeardMillis = 40),
            MeshNode(MeshNetwork.MESHCORE, id = "c", name = "Alpha", source = "test", hopsAway = 2, snr = 3f, lastHeardMillis = 50),
        )
        assertEquals(listOf("c", "b", "a"), sortNodeList(nodes, NodeSort.NAME).map { it.id })
        assertEquals(listOf("b", "c", "a"), sortNodeList(nodes, NodeSort.HOPS).map { it.id })
        assertEquals(listOf("c", "b", "a"), sortNodeList(nodes, NodeSort.SIGNAL).map { it.id })
        assertEquals(listOf("c", "b", "a"), sortNodeList(nodes, NodeSort.LAST_HEARD).map { it.id })
    }
}
