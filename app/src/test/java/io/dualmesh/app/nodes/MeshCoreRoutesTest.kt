package io.dualmesh.app.nodes

import io.dualmesh.app.protocol.MeshCoreProtocol
import io.dualmesh.app.settings.SettingsScheduler
import org.junit.Assert.*
import org.junit.Test

class MeshCoreRoutesTest {
    private val id = (0..31).joinToString("") { "%02x".format(it) }
    private val key get() = MeshCoreRouteCodec.key(id)
    private fun contact(route: MeshCoreRoute = MeshCoreRoute(1, listOf("aa", "bb"))): ByteArray {
        val base = ByteArray(156) { (it * 7).toByte() }
        base[0] = 3
        key.copyInto(base, 1)
        base[35] = 0
        return if (route.flood) base.apply { this[35] = -1 } else MeshCoreRouteCodec.updateContact(base, route).apply { this[0] = 3 }
    }
    private class Fixture {
        var timeout: (() -> Unit)? = null
        var delay = 0L
        var ready = true
        var available = true
        val sent = mutableListOf<ByteArray>()
        val observed = mutableListOf<ByteArray>()
        val actions = MeshCoreNodeActions({ sent += it; available }, { ready }, SettingsScheduler { millis, callback ->
            delay = millis
            timeout = callback
            ({ if (timeout === callback) timeout = null })
        }, onContact = { observed += it })
        fun wideHashes() = actions.observeDeviceInfo(ByteArray(82).also { it[0] = 13 })
    }

    @Test fun patchesOnlyPathBytesAndPreservesContactAndFutureFields() {
        val original = contact()
        val wanted = MeshCoreRoute(2, listOf("AABB", "ccdd"))
        val command = MeshCoreRouteCodec.updateContact(original, wanted)
        assertEquals(9, command[0].toInt())
        assertEquals(0x42, command[35].toInt() and 255)
        assertArrayEquals(byteArrayOf(0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte(), 0xdd.toByte()), command.copyOfRange(36, 40))
        assertTrue(command.copyOfRange(40, 100).all { it == 0.toByte() })
        assertArrayEquals(original.copyOfRange(1, 35), command.copyOfRange(1, 35))
        assertArrayEquals(original.copyOfRange(100, original.size), command.copyOfRange(100, command.size))
        command[0] = 3
        assertEquals(MeshCoreRoute(2, listOf("aabb", "ccdd")), MeshCoreRouteCodec.parseContact(command).second)
    }

    @Test fun validatesFullKeysHashWidthsLengthsAndCapacityBeforeTransmit() {
        assertThrows(IllegalArgumentException::class.java) { MeshCoreRouteCodec.key("aa") }
        listOf(MeshCoreRoute(0), MeshCoreRoute(4), MeshCoreRoute(2, listOf("aa")), MeshCoreRoute(1, listOf("zz")), MeshCoreRoute(1, List(64) { "aa" }), MeshCoreRoute(3, List(22) { "aabbcc" }), MeshCoreRoute(hops = listOf("aa"), flood = true)).forEach { route ->
            assertThrows(IllegalArgumentException::class.java) { MeshCoreRouteCodec.normalize(route) }
        }
        assertEquals(63, MeshCoreRouteCodec.normalize(MeshCoreRoute(1, List(63) { "aa" })).hops.size)
        assertEquals(32, MeshCoreRouteCodec.normalize(MeshCoreRoute(2, List(32) { "aabb" })).hops.size)
        val f = Fixture()
        assertTrue(f.actions.setRoute(id, MeshCoreRoute(2, listOf("aabb"))).isFailure)
        assertTrue(f.sent.isEmpty())
        f.wideHashes()
        assertTrue(f.actions.setRoute(id, MeshCoreRoute(2, listOf("aabb"))).isSuccess)
    }

    @Test fun readsFreshRecordWaitsForAckAndVerifiesReadbackOnCorrectContact() {
        val f = Fixture(); f.wideHashes()
        f.actions.observeContact(contact())
        val target = MeshCoreRoute(2, listOf("1020", "3040"))
        assertTrue(f.actions.setRoute(id, target).isSuccess)
        assertArrayEquals(byteArrayOf(30) + key, f.sent.single())
        val other = contact().also { it[1] = 99 }
        assertFalse(f.actions.receive(other))
        assertEquals(1, f.sent.size)
        val fresh = contact().apply { this[34] = 9; this[132] = 123 }
        f.actions.receive(fresh)
        assertEquals(2, f.sent.size)
        assertEquals(9, f.sent.last()[34].toInt())
        assertEquals(123, f.sent.last()[132].toInt())
        assertFalse(f.actions.receive(byteArrayOf(6)))
        assertEquals(2, f.sent.size)
        f.actions.receive(byteArrayOf(0))
        assertArrayEquals(byteArrayOf(30) + key, f.sent.last())
        f.actions.receive(contact(target))
        assertFalse(f.actions.busy)
        assertNull(f.actions.state.value.error)
        assertEquals(target, f.actions.routeState.value[id]!!.current)
        assertEquals(2, f.observed.size)
        assertEquals(listOf(MeshCoreRoute(1, listOf("aa", "bb"))), f.actions.routeState.value[id]!!.history)
    }

    @Test fun directAndFloodAreDistinctAndVerified() {
        for (route in listOf(MeshCoreRoute(), MeshCoreRoute(flood = true))) {
            val f = Fixture()
            f.actions.setRoute(id, route)
            f.actions.receive(contact())
            if (route.flood) assertArrayEquals(byteArrayOf(13) + key, f.sent.last())
            else assertEquals(0, f.sent.last()[35].toInt())
            f.actions.receive(byteArrayOf(0))
            f.actions.receive(contact(route))
            assertNull(f.actions.state.value.error)
            assertEquals(route, f.actions.routeState.value[id]!!.current)
        }
    }

    @Test fun reportsRejectionMismatchTimeoutAndResetWithoutOptimisticRouteUpdates() {
        val f = Fixture()
        f.actions.setRoute(id, MeshCoreRoute())
        f.actions.receive(contact())
        f.actions.receive(byteArrayOf(1, 2))
        assertEquals(2, f.sent.size)
        assertNotNull(f.actions.state.value.error)
        assertEquals(MeshCoreRoute(1, listOf("aa", "bb")), f.actions.routeState.value[id]!!.current)
        f.actions.setRoute(id, MeshCoreRoute())
        f.actions.receive(contact()); f.actions.receive(byteArrayOf(0)); f.actions.receive(contact())
        assertTrue(f.actions.state.value.error!!.contains("different route"))
        f.actions.refreshRoute(id); f.timeout!!.invoke()
        assertFalse(f.actions.busy)
        assertNotNull(f.actions.state.value.error)
        f.actions.refreshRoute(id); f.actions.reset()
        assertTrue(f.actions.routeState.value.isEmpty())
        assertFalse(f.actions.busy)
        assertNull(f.timeout)
    }

    @Test fun discoversOutboundPathWithMatchingPrefixAndRequiresExplicitSave() {
        val f = Fixture()
        f.actions.discoverRoute(id)
        f.actions.receive(contact())
        assertArrayEquals(byteArrayOf(52, 0) + key, f.sent.last())
        assertFalse(f.actions.receive(byteArrayOf(0)))
        f.actions.receive(byteArrayOf(6, 1, 1, 2, 3, 4, 0, 0, 0, 0))
        assertEquals(60_000L, f.delay)
        val response = byteArrayOf(0x8d.toByte(), 0) + key.copyOf(6) + byteArrayOf(0x42, 0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte(), 0xdd.toByte(), 1, 0xee.toByte())
        assertFalse(f.actions.receive(response.copyOf().apply { this[2] = 99 }))
        assertTrue(f.actions.busy)
        f.actions.receive(response)
        assertFalse(f.actions.busy)
        assertNull(f.actions.state.value.error)
        assertEquals(MeshCoreRoute(2, listOf("aabb", "ccdd")), f.actions.routeState.value[id]!!.discovered)
        assertEquals(MeshCoreRoute(1, listOf("aa", "bb")), f.actions.routeState.value[id]!!.current)
        assertEquals(2, f.sent.size) // no write was performed
    }

    @Test fun rejectsTruncatedDiscoveryAndContactFramesAndGuardsBusyTransport() {
        val f = Fixture()
        f.actions.refreshRoute(id)
        assertTrue(f.actions.advertise(true).isFailure)
        assertTrue(f.actions.setRoute(id, MeshCoreRoute()).isFailure)
        f.actions.receive(contact().copyOf(40))
        assertNotNull(f.actions.state.value.error)
        val response = byteArrayOf(0x8d.toByte(), 0) + key.copyOf(6) + byteArrayOf(0x42, 1)
        assertThrows(IllegalArgumentException::class.java) { MeshCoreRouteCodec.discovery(response) }
        f.ready = false
        assertTrue(f.actions.discoverRoute(id).isFailure)
        f.ready = true; f.available = false
        f.actions.refreshRoute(id)
        assertFalse(f.actions.busy)
        assertNotNull(f.actions.state.value.error)
    }

    @Test fun tracerouteHandlesDirectWideHashPathsAndRejectsOversizedRoundTrips() {
        val direct = MeshCoreProtocol.parseContact(contact(MeshCoreRoute(3)))!!
        assertEquals(1, MeshCoreProtocol.tracePath(direct).hashSize)
        assertThrows(IllegalArgumentException::class.java) { MeshCoreProtocol.traceRequest(1u, List(65) { "aa" }, 1) }
        assertThrows(IllegalArgumentException::class.java) { MeshCoreProtocol.traceRequest(1u, List(45) { "aabbccdd" }, 4) }
    }

    @Test fun tracerouteRejectsThreeByteHashesInsteadOfSilentlyTruncatingThem() {
        val parsed = MeshCoreProtocol.parseContact(contact(MeshCoreRoute(3, listOf("aabbcc"))))!!
        assertThrows(IllegalArgumentException::class.java) { MeshCoreProtocol.tracePath(parsed) }
    }
}
