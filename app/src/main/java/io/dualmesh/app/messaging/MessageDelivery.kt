package io.dualmesh.app.messaging

import io.dualmesh.app.model.DeliveryState
import io.dualmesh.app.model.UnifiedMessage
import io.dualmesh.app.settings.SettingsScheduler
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Routing

/** One instance per radio. Keep timed-out sends so a late acknowledgement can still settle them. */
internal class MessageDeliveryTracker(
    private val scheduler: SettingsScheduler,
    private val emit: (UnifiedMessage) -> Unit,
) {
    private val messages = linkedMapOf<String, UnifiedMessage>()
    private val timeouts = mutableMapOf<String, () -> Unit>()
    private val timeoutTokens = mutableMapOf<String, Any>()
    private fun cancelTimeout(id: String) {
        timeoutTokens.remove(id)
        timeouts.remove(id)?.invoke()
    }

    fun get(id: String): UnifiedMessage? = messages[id]

    fun track(message: UnifiedMessage, timeoutMillis: Long = 300_000) {
        messages[message.id] = message
        emit(message)
        awaitAck(message.id, timeoutMillis)
        while (messages.size > 256) {
            val oldest = messages.keys.first()
            expire(oldest, "Delivery tracking capacity exceeded; confirmation unavailable.")
            messages.remove(oldest)
        }
    }

    fun awaitAck(id: String, timeoutMillis: Long) {
        cancelTimeout(id)
        val token = Any()
        timeoutTokens[id] = token
        timeouts[id] = scheduler.after(timeoutMillis) {
            if (timeoutTokens[id] === token) expire(id, "No acknowledgement received before the delivery timeout.")
        }
    }

    fun update(id: String, state: DeliveryState, detail: String, final: Boolean = true) {
        val old = messages[id] ?: return
        // A queue notification, duplicate echo or late NAK cannot undo a positive recipient confirmation.
        if (old.deliveryState == DeliveryState.DELIVERED || (old.deliveryState == DeliveryState.DELIVERED_TO_MESH && old.conversationId.startsWith("channel:"))) return
        if (old.deliveryState == DeliveryState.DELIVERED_TO_MESH && state !in setOf(DeliveryState.DELIVERED, DeliveryState.ERROR, DeliveryState.UNCONFIRMED)) return
        if (final) cancelTimeout(id)
        val updated = old.copy(deliveryState = state, deliveryDetail = detail)
        messages[id] = updated
        emit(updated)
    }

    private fun expire(id: String, reason: String) {
        val message = messages[id] ?: return
        if (id !in timeoutTokens) return
        if (message.deliveryState in setOf(DeliveryState.SENDING, DeliveryState.SENT_TO_RADIO, DeliveryState.SENT)) {
            update(id, DeliveryState.UNCONFIRMED, reason)
        } else cancelTimeout(id)
    }

    fun disconnect(reason: String) {
        messages.keys.toList().forEach { expire(it, reason) }
        timeouts.values.toList().forEach { it() }
        timeouts.clear()
        timeoutTokens.clear()
        messages.clear()
    }
}

internal fun MeshPacket.messageHops(): Int? =
    if (hop_start > 0 && hop_limit in 0..hop_start) hop_start - hop_limit else null

internal class MeshtasticMessageDelivery(
    scheduler: SettingsScheduler,
    emit: (UnifiedMessage) -> Unit,
    private val localNode: () -> Int,
) {
    private val tracker = MessageDeliveryTracker(scheduler, emit)
    fun track(message: UnifiedMessage) = tracker.track(message)
    fun disconnect(reason: String) = tracker.disconnect(reason)

    fun receive(fromRadio: FromRadio): Boolean {
        fromRadio.queueStatus?.let { queue ->
            val id = queue.mesh_packet_id.toUInt().toString()
            val message = tracker.get(id) ?: return@let
            if (message.deliveryState !in setOf(DeliveryState.SENT_TO_RADIO, DeliveryState.SENDING, DeliveryState.SENT)) return@let
            if (queue.res == 0) tracker.update(id, DeliveryState.SENT, "Accepted by the radio's transmit queue; awaiting acknowledgement.", final = false)
            else tracker.update(id, DeliveryState.ERROR, "Radio rejected the message (queue error ${queue.res}).")
        }
        val packet = fromRadio.packet ?: return false
        val data = packet.decoded ?: return false
        if (data.portnum != PortNum.ROUTING_APP || packet.to != localNode()) return false
        val id = data.request_id.toUInt().toString()
        val message = tracker.get(id) ?: return false
        val routing = runCatching { Routing.ADAPTER.decode(data.payload) }.getOrNull() ?: return false
        val reason = routing.error_reason ?: return false
        val direct = message.conversationId.startsWith("contact:")
        val recipient = message.conversationId.removePrefix("contact:").toUIntOrNull()?.toInt()
        if (reason != Routing.Error.NONE) {
            tracker.update(id, DeliveryState.ERROR, "Delivery failed: ${reason.name.lowercase().replace('_', ' ')}.")
        } else if (direct && packet.from == recipient) {
            tracker.update(id, DeliveryState.DELIVERED, "Acknowledged by the recipient's radio. This is not a read receipt.")
        } else {
            tracker.update(id, DeliveryState.DELIVERED_TO_MESH,
                if (direct) "Relayed into the mesh; the recipient has not confirmed delivery."
                else "Acknowledged by the mesh. Channel broadcasts do not confirm delivery to every recipient.", final = !direct)
        }
        return true
    }
}

/** The MeshCore companion has no command IDs: allow only one outstanding send-command reply. */
internal class MeshCoreMessageDelivery(
    private val scheduler: SettingsScheduler,
    emit: (UnifiedMessage) -> Unit,
) {
    private val tracker = MessageDeliveryTracker(scheduler, emit)
    private var command: UnifiedMessage? = null
    private var cancelCommandTimeout: (() -> Unit)? = null
    private val acknowledgements = linkedMapOf<UInt, String>()
    val busy: Boolean get() = command != null

    fun track(message: UnifiedMessage) {
        check(!busy)
        command = message
        tracker.track(message)
        cancelCommandTimeout = scheduler.after(10_000) {
            if (command === message) tracker.update(message.id, DeliveryState.UNCONFIRMED, "The radio did not confirm the send command. Reconnect if it remains unresponsive.")
            // Retain command ownership: a delayed untagged reply must never settle a later send.
        }
    }

    fun receive(frame: ByteArray): Boolean {
        if (frame.isEmpty()) return false
        val code = frame[0].toInt() and 255
        if (code == 0x82) {
            if (frame.size < 9) return false
            val ack = uint32(frame, 1)
            val id = acknowledgements[ack] ?: return false
            tracker.update(id, DeliveryState.DELIVERED, "Acknowledged by the recipient's radio in ${uint32(frame, 5)} ms. This is not a read receipt.")
            return true
        }
        val pending = command ?: return false
        val direct = pending.conversationId.startsWith("contact:")
        if (code == 1) {
            tracker.update(pending.id, DeliveryState.ERROR, "Radio rejected the message (error ${frame.getOrNull(1)?.toInt()?.and(255) ?: "unknown"}).")
        } else if (code == 0 && !direct) {
            tracker.update(pending.id, DeliveryState.SENT, "Accepted by the radio. MeshCore channel broadcasts do not provide delivery acknowledgements.")
        } else if (code == 6 && direct && frame.size >= 10) {
            val ack = uint32(frame, 2)
            acknowledgements[ack] = pending.id
            while (acknowledgements.size > 256) acknowledgements.remove(acknowledgements.keys.first())
            tracker.update(pending.id, DeliveryState.SENT, "Sent ${if (frame[1].toInt() == 1) "by flood" else "by direct route"}; awaiting recipient acknowledgement.", final = false)
            tracker.awaitAck(pending.id, uint32(frame, 6).toLong().coerceIn(1_000, 600_000))
        } else return false
        cancelCommandTimeout?.invoke()
        cancelCommandTimeout = null
        command = null
        return true
    }

    fun disconnect(reason: String) {
        cancelCommandTimeout?.invoke()
        cancelCommandTimeout = null
        command = null
        acknowledgements.clear()
        tracker.disconnect(reason)
    }

    private fun uint32(frame: ByteArray, offset: Int): UInt =
        (0..3).fold(0u) { result, index -> result or ((frame[offset + index].toUInt() and 255u) shl (8 * index)) }
}
