package io.dualmesh.app.messaging

import io.dualmesh.app.model.*
import io.dualmesh.app.settings.SettingsScheduler
import io.dualmesh.app.history.mergeMessageObservation
import io.dualmesh.app.protocol.MeshCoreProtocol
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import org.meshtastic.proto.*

class MessageDeliveryTest {
    private class Timer : SettingsScheduler {
        data class Task(val delay: Long, val action: () -> Unit, var cancelled: Boolean = false)
        val tasks = mutableListOf<Task>()
        override fun after(milliseconds: Long, action: () -> Unit): () -> Unit {
            val task = Task(milliseconds, action); tasks += task
            return { task.cancelled = true }
        }
        fun advance() { tasks.first { !it.cancelled }.also { it.cancelled = true }.action() }
        fun stale() = tasks.toList().forEach { it.action() }
    }
    private fun message(id: String = "42", conversation: String = "contact:2", network: MeshNetwork = MeshNetwork.MESHTASTIC) = UnifiedMessage(
        id, network, conversation, "You", "hello", 1000, true, DeliveryState.SENT_TO_RADIO,
    )
    private fun routing(from: Int, error: Routing.Error = Routing.Error.NONE, request: Int = 42, to: Int = 1) = FromRadio(packet = MeshPacket(
        from = from, to = to, decoded = Data(portnum = PortNum.ROUTING_APP, request_id = request,
            payload = Routing(error_reason = error).encode().toByteString()),
    ))
    private fun bytes(value: Int) = ByteArray(4) { (value ushr (it * 8)).toByte() }
    private fun sent(ack: Int = 42, timeout: Int = 2000) = byteArrayOf(6, 1) + bytes(ack) + bytes(timeout)
    private fun ack(code: Int = 42) = byteArrayOf(0x82.toByte()) + bytes(code) + bytes(750)

    @Test fun `MT distinguishes radio queue mesh relay and recipient acknowledgement`() {
        val states = mutableListOf<UnifiedMessage>(); val timer = Timer()
        val delivery = MeshtasticMessageDelivery(timer, { states += it }, { 1 })
        delivery.track(message())
        delivery.receive(FromRadio(queueStatus = QueueStatus(mesh_packet_id = 42, res = 0)))
        assertEquals(DeliveryState.SENT, states.last().deliveryState)
        delivery.receive(routing(1))
        assertEquals(DeliveryState.DELIVERED_TO_MESH, states.last().deliveryState)
        delivery.receive(routing(2))
        assertEquals(DeliveryState.DELIVERED, states.last().deliveryState)
        timer.stale()
        delivery.receive(routing(1, Routing.Error.NO_ROUTE))
        delivery.receive(FromRadio(queueStatus = QueueStatus(mesh_packet_id = 42, res = 1)))
        assertEquals(DeliveryState.DELIVERED, states.last().deliveryState)
    }

    @Test fun `MT broadcast acknowledgement confirms mesh only and unrelated request or destination is ignored`() {
        val states = mutableListOf<UnifiedMessage>(); val delivery = MeshtasticMessageDelivery(Timer(), { states += it }, { 1 })
        delivery.track(message(conversation = "channel:0"))
        assertFalse(delivery.receive(routing(2, request = 99)))
        assertFalse(delivery.receive(routing(2, to = 99)))
        assertEquals(DeliveryState.SENT_TO_RADIO, states.last().deliveryState)
        delivery.receive(routing(1))
        assertEquals(DeliveryState.DELIVERED_TO_MESH, states.last().deliveryState)
        delivery.receive(routing(1, Routing.Error.NO_ROUTE))
        assertEquals(DeliveryState.DELIVERED_TO_MESH, states.last().deliveryState)
    }

    @Test fun `MT queue failure timeout and late recipient ACK settle the same message`() {
        val states = mutableListOf<UnifiedMessage>(); val timer = Timer()
        val delivery = MeshtasticMessageDelivery(timer, { states += it }, { 1 })
        delivery.track(message())
        timer.advance()
        assertEquals(DeliveryState.UNCONFIRMED, states.last().deliveryState)
        delivery.receive(routing(2))
        assertEquals(DeliveryState.DELIVERED, states.last().deliveryState)
        delivery.track(message("43"))
        delivery.receive(FromRadio(queueStatus = QueueStatus(mesh_packet_id = 43, res = 1)))
        assertEquals(DeliveryState.ERROR, states.last().deliveryState)
        assertTrue(states.last().deliveryDetail!!.contains("queue"))
    }

    @Test fun `MC uses expected ACK timeout and accepts late confirmations without duplicating messages`() {
        val states = mutableListOf<UnifiedMessage>(); val timer = Timer()
        val delivery = MeshCoreMessageDelivery(timer, { states += it })
        delivery.track(message(network = MeshNetwork.MESHCORE))
        assertTrue(delivery.busy)
        assertFalse(delivery.receive(ack(91)))
        assertFalse(delivery.receive(byteArrayOf(6)))
        assertTrue(delivery.receive(sent()))
        assertFalse(delivery.busy)
        assertEquals(2000L, timer.tasks.last().delay)
        timer.advance()
        assertEquals(DeliveryState.UNCONFIRMED, states.last().deliveryState)
        assertTrue(delivery.receive(ack()))
        assertEquals(DeliveryState.DELIVERED, states.last().deliveryState)
        assertTrue(states.last().deliveryDetail!!.contains("750 ms"))
        timer.stale()
        assertEquals(DeliveryState.DELIVERED, states.last().deliveryState)
        assertEquals(setOf("42"), states.map { it.id }.toSet())
    }

    @Test fun `MC channel OK means sent not delivered and errors fail a pending send`() {
        val states = mutableListOf<UnifiedMessage>(); val timer = Timer()
        val delivery = MeshCoreMessageDelivery(timer, { states += it })
        delivery.track(message(conversation = "channel:0", network = MeshNetwork.MESHCORE))
        delivery.receive(byteArrayOf(0))
        assertEquals(DeliveryState.SENT, states.last().deliveryState)
        timer.stale()
        assertEquals(DeliveryState.SENT, states.last().deliveryState)
        delivery.disconnect("Disconnected")
        assertEquals(DeliveryState.SENT, states.last().deliveryState)
        delivery.track(message("43", network = MeshNetwork.MESHCORE))
        delivery.receive(byteArrayOf(1, 2))
        assertEquals(DeliveryState.ERROR, states.last().deliveryState)
        assertFalse(delivery.busy)
    }

    @Test fun `MC untagged command timeout retains ownership and disconnect releases it`() {
        val states = mutableListOf<UnifiedMessage>(); val timer = Timer()
        val delivery = MeshCoreMessageDelivery(timer, { states += it })
        delivery.track(message(network = MeshNetwork.MESHCORE))
        timer.tasks.first { it.delay == 10_000L }.action()
        assertTrue(delivery.busy)
        assertEquals(DeliveryState.UNCONFIRMED, states.last().deliveryState)
        delivery.disconnect("Disconnected")
        assertFalse(delivery.busy)
        assertFalse(delivery.receive(sent()))
        assertFalse(delivery.receive(ack()))
    }

    @Test fun `delivery tracking is isolated per radio`() {
        val first = mutableListOf<UnifiedMessage>(); val second = mutableListOf<UnifiedMessage>()
        val a = MeshtasticMessageDelivery(Timer(), { first += it }, { 1 })
        val b = MeshtasticMessageDelivery(Timer(), { second += it }, { 1 })
        a.track(message()); b.track(message())
        a.receive(routing(2))
        assertEquals(DeliveryState.DELIVERED, first.last().deliveryState)
        assertEquals(DeliveryState.SENT_TO_RADIO, second.last().deliveryState)
        b.disconnect("Disconnected")
        assertEquals(DeliveryState.UNCONFIRMED, second.last().deliveryState)
    }

    @Test fun `MT hop count requires valid original hop limit and preserves zero hops`() {
        assertEquals(0, MeshPacket(hop_start = 3, hop_limit = 3).messageHops())
        assertEquals(2, MeshPacket(hop_start = 3, hop_limit = 1).messageHops())
        assertNull(MeshPacket(hop_start = 0, hop_limit = 0).messageHops())
        assertNull(MeshPacket(hop_start = 2, hop_limit = 3).messageHops())
    }

    @Test fun `MC decodes flood hops and signed SNR but direct route hops remain unknown`() {
        val flood = byteArrayOf(17, -20, 0, 0, 0, 0x82.toByte(), 0) + bytes(1234) + "Alice: hello".encodeToByteArray()
        val message = MeshCoreProtocol.parseMessage(flood)!!
        assertEquals(2, message.hopsAway)
        assertEquals(-5f, message.snr)
        assertEquals(false, message.viaMqtt)
        assertEquals("Flood", message.receptionPath)
        val direct = byteArrayOf(16, 8, 0, 0) + ByteArray(6) + byteArrayOf(-1, 0) + bytes(1234) + "hello".encodeToByteArray()
        assertNull(MeshCoreProtocol.parseMessage(direct)!!.hopsAway)
        assertEquals("Direct route", MeshCoreProtocol.parseMessage(direct)!!.receptionPath)
        val zeroHop = flood.copyOf().also { it[5] = 0 }
        assertEquals(0, MeshCoreProtocol.parseMessage(zeroHop)!!.hopsAway)
    }

    @Test fun `echo cannot erase delivery confirmation timestamp or known metadata`() {
        val confirmed = message().copy(deliveryState = DeliveryState.DELIVERED, deliveryDetail = "Recipient ACK", hopsAway = 2, viaMqtt = true)
        val echo = message().copy(deliveryState = DeliveryState.RECEIVED, timestampMillis = 9999)
        val merged = mergeMessageObservation(confirmed, echo)
        assertEquals(DeliveryState.DELIVERED, merged.deliveryState)
        assertEquals("Recipient ACK", merged.deliveryDetail)
        assertEquals(1000L, merged.timestampMillis)
        assertEquals(2, merged.hopsAway)
        assertEquals(true, merged.viaMqtt)
    }
}
