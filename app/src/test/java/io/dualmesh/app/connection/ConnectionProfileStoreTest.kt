package io.dualmesh.app.connection

import io.dualmesh.app.model.MeshNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionProfileStoreTest {
    @Test
    fun `history entry round trips BLE profile with unicode name`() {
        val profile = ConnectionProfileStore.Profile(
            network = MeshNetwork.MESHTASTIC,
            transport = ConnectionProfileStore.Transport.BLE,
            address = "02:00:00:00:00:01",
            name = "Example BLE radio",
            lastUsedMillis = 123_456L,
            enabled = true,
        )

        val decoded = ConnectionProfileStore.decodeHistoryEntry(
            MeshNetwork.MESHTASTIC,
            ConnectionProfileStore.encodeHistoryEntry(profile),
        )

        assertEquals(profile, decoded)
    }

    @Test
    fun `history entry round trips TCP profile`() {
        val profile = ConnectionProfileStore.Profile(
            network = MeshNetwork.MESHTASTIC,
            transport = ConnectionProfileStore.Transport.TCP,
            address = "192.0.2.10",
            name = "Example TCP radio",
            lastUsedMillis = 987_654L,
        )

        val decoded = ConnectionProfileStore.decodeHistoryEntry(
            MeshNetwork.MESHTASTIC,
            ConnectionProfileStore.encodeHistoryEntry(profile),
        )

        assertEquals(profile, decoded)
    }

    @Test
    fun `malformed history entry is ignored`() {
        assertNull(
            ConnectionProfileStore.decodeHistoryEntry(
                MeshNetwork.MESHCORE,
                "not-a-profile",
            ),
        )
    }

    @Test
    fun `legacy four field history entry migrates as disabled`() {
        val encoded = ConnectionProfileStore.encodeHistoryEntry(
            ConnectionProfileStore.Profile(
                network = MeshNetwork.MESHCORE,
                transport = ConnectionProfileStore.Transport.BLE,
                address = "02:00:00:00:00:02",
                name = "bench",
                lastUsedMillis = 42,
            ),
        ).substringBeforeLast('|')

        val decoded = ConnectionProfileStore.decodeHistoryEntry(MeshNetwork.MESHCORE, encoded)

        assertEquals(false, decoded?.enabled)
        assertEquals("02:00:00:00:00:02", decoded?.address)
    }
}
