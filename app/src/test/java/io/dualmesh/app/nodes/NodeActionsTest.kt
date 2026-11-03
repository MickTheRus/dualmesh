package io.dualmesh.app.nodes

import io.dualmesh.app.settings.SettingsScheduler
import org.junit.Assert.*
import org.junit.Test
import org.meshtastic.proto.*
import okio.ByteString.Companion.toByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NodeActionsTest {
    private class Clock : SettingsScheduler {
        var timeout: (() -> Unit)? = null
        override fun after(milliseconds: Long, action: () -> Unit): () -> Unit {
            timeout = action
            return { if (timeout === action) timeout = null }
        }
        fun expire() { timeout?.invoke() }
    }

    @Test fun meshCoreReadsSeriallyAndKeepsUnsignedCountersAndSignedSignal() {
        val sent = mutableListOf<ByteArray>()
        val action = MeshCoreNodeActions({ sent += it; true }, { true }, Clock())
        assertTrue(action.requestStats().isSuccess)
        assertArrayEquals(byteArrayOf(56, 0), sent.single())
        assertFalse(action.receive(byteArrayOf(24, 1)))
        val core = ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN).put(24).put(0).putShort(4200).putInt(-1).putShort(2).put(3).array()
        assertTrue(action.receive(core))
        assertEquals(2, sent.size)
        assertArrayEquals(byteArrayOf(56, 1), sent.last())
        val radio = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN).put(24).put(1).putShort(-112).put(-89).put(-10).putInt(12).putInt(30).array()
        action.receive(radio)
        assertArrayEquals(byteArrayOf(56, 2), sent.last())
        val packets = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN).put(24).put(2)
        repeat(7) { packets.putInt(if (it == 0) -1 else it) }
        action.receive(packets.array())
        assertFalse(action.state.value.busy)
        assertNull(action.state.value.error)
        assertEquals(3, action.state.value.stats.size)
        assertTrue(action.state.value.stats[0].values.contains("Uptime" to "4294967295 s"))
        assertTrue(action.state.value.stats[1].values.contains("Last SNR" to "-2.5 dB"))
        assertTrue(action.state.value.stats[2].values.contains("Received" to "4294967295"))
        assertTrue(action.state.value.stats[2].values.contains("Receive errors" to "6"))
    }

    @Test fun meshCoreSupportsLegacyCountersAndRejectsTruncation() {
        val frame = ByteArray(26).also { it[0] = 24; it[1] = 2 }
        assertEquals(6, MeshCoreNodeActions.parseStats(frame).values.size)
        assertThrows(IllegalArgumentException::class.java) { MeshCoreNodeActions.parseStats(frame.copyOf(25)) }
    }

    @Test fun meshCoreAdvertsWaitForAckAndReportRejectionTimeoutAndDisconnect() {
        val clock = Clock()
        val sent = mutableListOf<ByteArray>()
        val action = MeshCoreNodeActions({ sent += it; true }, { true }, clock)
        action.advertise(false)
        assertArrayEquals(byteArrayOf(7, 0), sent.single())
        assertTrue(action.busy)
        assertTrue(action.requestStats().isFailure)
        assertFalse(action.receive(byteArrayOf(6)))
        action.receive(byteArrayOf(0))
        assertFalse(action.busy)
        assertTrue(action.state.value.status!!.contains("accepted"))
        action.advertise(true)
        assertArrayEquals(byteArrayOf(7, 1), sent.last())
        action.receive(byteArrayOf(1, 2))
        assertNotNull(action.state.value.error)
        action.requestStats()
        clock.expire()
        assertFalse(action.busy)
        assertNotNull(action.state.value.error)
        action.requestStats()
        action.reset()
        assertEquals(NodeActionState(), action.state.value)
        assertFalse(action.busy)
    }

    @Test fun meshCoreGuardsRemoteUnavailableAndMalformedResponses() {
        val blocked = MeshCoreNodeActions({ fail("Should not transmit"); false }, { false }, Clock())
        assertTrue(blocked.advertise(true).isFailure)
        assertTrue(blocked.requestStats().isFailure)
        val action = MeshCoreNodeActions({ true }, { true }, Clock())
        assertTrue(action.requestStats("remote").isFailure)
        action.requestStats()
        action.receive(byteArrayOf(24, 0))
        assertNotNull(action.state.value.error)
        assertFalse(action.busy)
    }

    private class Mt {
        val sent = mutableListOf<ToRadio>()
        val clock = Clock()
        var ready: Int? = 42
        var transport = true
        var user: User? = User(id = "!0000002a", long_name = "My radio", short_name = "ME")
        val action = MeshtasticNodeActions(
            { sent += ToRadio.ADAPTER.decode(it); transport }, { ready }, { user }, { if (it == 42) 0 else 2 }, { 5 }, clock,
        )
        fun reply(telemetry: Telemetry, source: Int = sent.last().packet!!.to, id: Int = sent.last().packet!!.id) = MeshPacket(
            from = source, to = 42, decoded = Data(portnum = PortNum.TELEMETRY_APP, request_id = id, payload = telemetry.encode().toByteString()),
        )
    }

    @Test fun meshtasticLocalStatsReadDeviceAndLocalSequentiallyWithStrictCorrelation() {
        val mt = Mt()
        assertTrue(mt.action.requestStats().isSuccess)
        val packet = mt.sent.single().packet!!
        assertEquals(42, packet.to)
        assertEquals(0, packet.hop_limit)
        assertTrue(packet.decoded!!.want_response)
        assertNotNull(Telemetry.ADAPTER.decode(packet.decoded!!.payload).device_metrics)
        assertFalse(mt.action.receive(mt.reply(Telemetry(device_metrics = DeviceMetrics()), source = 99)))
        assertFalse(mt.action.receive(mt.reply(Telemetry(device_metrics = DeviceMetrics()), id = packet.id + 1)))
        assertFalse(mt.action.receive(mt.reply(Telemetry(local_stats = LocalStats()))))
        assertEquals(1, mt.sent.size)
        mt.action.receive(mt.reply(Telemetry(device_metrics = DeviceMetrics(battery_level = 101, voltage = 4.2f))))
        assertEquals(2, mt.sent.size)
        assertNotNull(Telemetry.ADAPTER.decode(mt.sent.last().packet!!.decoded!!.payload).local_stats)
        mt.action.receive(mt.reply(Telemetry(local_stats = LocalStats())))
        assertFalse(mt.action.busy)
        assertNull(mt.action.state.value.error)
        assertEquals(2, mt.action.state.value.stats.size)
        assertTrue(mt.action.state.value.stats.first().values.any { it.second == "External power" })
    }

    @Test fun meshtasticRemoteStatsUseNodeChannelAndConfiguredHops() {
        val mt = Mt()
        assertTrue(mt.action.requestStats("!00000063").isSuccess)
        val packet = mt.sent.single().packet!!
        assertEquals(99, packet.to)
        assertEquals(2, packet.channel)
        assertEquals(5, packet.hop_limit)
        mt.action.receive(mt.reply(Telemetry(device_metrics = DeviceMetrics(battery_level = 60))))
        assertEquals(1, mt.sent.size)
        assertEquals(1, mt.action.state.value.stats.size)
        assertFalse(mt.action.busy)
    }

    @Test fun meshtasticRejectsRoutingErrorsTimeoutsAndTransportFailures() {
        val mt = Mt()
        mt.action.requestStats("99")
        val packet = mt.sent.single().packet!!
        val error = MeshPacket(from = 42, decoded = Data(portnum = PortNum.ROUTING_APP, request_id = packet.id,
            payload = Routing(error_reason = Routing.Error.NO_ROUTE).encode().toByteString()))
        mt.action.receive(error)
        assertNotNull(mt.action.state.value.error)
        assertFalse(mt.action.busy)
        mt.action.requestStats()
        assertTrue(mt.action.advertise(true).isFailure)
        mt.clock.expire()
        assertNotNull(mt.action.state.value.error)
        mt.transport = false
        mt.action.requestStats()
        assertFalse(mt.action.busy)
        assertNotNull(mt.action.state.value.error)
        mt.action.reset()
        assertEquals(NodeActionState(), mt.action.state.value)
    }

    @Test fun meshtasticRetainsDeviceTelemetryWhenFirmwareDoesNotAnswerLocalStats() {
        val mt = Mt()
        mt.action.requestStats()
        mt.action.receive(mt.reply(Telemetry(device_metrics = DeviceMetrics(voltage = 4f))))
        mt.clock.expire()
        assertFalse(mt.action.busy)
        assertEquals("Device telemetry updated", mt.action.state.value.status)
        assertTrue(mt.action.state.value.error!!.contains("Local statistics"))
        assertEquals(1, mt.action.state.value.stats.size)
    }

    @Test fun meshtasticOmitsMissingMetricsAndHonorsRemoteRoutingFailures() {
        val mt = Mt()
        mt.action.requestStats("99")
        val id = mt.sent.last().packet!!.id
        mt.action.receive(MeshPacket(from = 99, decoded = Data(portnum = PortNum.ROUTING_APP, request_id = id,
            payload = Routing(error_reason = Routing.Error.NOT_AUTHORIZED).encode().toByteString())))
        assertFalse(mt.action.busy)
        assertNotNull(mt.action.state.value.error)
        mt.action.requestStats("99")
        mt.action.receive(mt.reply(Telemetry(device_metrics = DeviceMetrics(voltage = 3.8f))))
        val values = mt.action.state.value.stats.single().values
        assertEquals(listOf("3.8 V"), values.map { it.second })
        assertFalse(mt.action.busy)
    }

    @Test fun meshtasticAdvertsBroadcastOwnUserWithExplicitHopLimitsAndNoAckOrReply() {
        val mt = Mt()
        for (flood in listOf(false, true)) {
            assertTrue(mt.action.advertise(flood).isSuccess)
            val packet = mt.sent.last().packet!!
            assertEquals(-1, packet.to)
            assertEquals(if (flood) 5 else 0, packet.hop_limit)
            assertFalse(packet.want_ack)
            assertEquals(PortNum.NODEINFO_APP, packet.decoded!!.portnum)
            assertFalse(packet.decoded!!.want_response)
            assertEquals(mt.user, User.ADAPTER.decode(packet.decoded!!.payload))
        }
        mt.user = null
        assertTrue(mt.action.advertise(false).isFailure)
        mt.ready = null
        assertTrue(mt.action.requestStats().isFailure)
        assertTrue(mt.action.requestStats("-1").isFailure)
    }
}
