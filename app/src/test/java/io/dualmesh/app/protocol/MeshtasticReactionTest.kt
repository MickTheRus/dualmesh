package io.dualmesh.app.protocol

import io.dualmesh.app.history.hasOwnReaction
import io.dualmesh.app.model.*
import io.dualmesh.app.ui.collapseMessageReactions
import org.junit.Assert.*
import org.junit.Test
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum

class MeshtasticReactionTest {
    private fun parent(conversation: String = "channel:3") = UnifiedMessage(
        "4294967294", MeshNetwork.MESHTASTIC, conversation, "Alice", "Hello", 1000, false, DeliveryState.RECEIVED, deviceId = "radio-a",
    )
    private fun reaction(parent: UnifiedMessage, id: String = "50", state: DeliveryState = DeliveryState.DELIVERED) =
        parent.copy(id = id, outgoing = true, sender = "You", text = "👍", reactionToMessageId = parent.id, reactionEmoji = "👍", deliveryState = state)
    private fun packet(parent: UnifiedMessage = parent(), emoji: String = "👍") = meshtasticReactionPacket(parent, emoji, 1, 100, 5)

    @Test fun `native channel reaction preserves channel unsigned reply ID emoji bytes and acknowledgement`() {
        val encoded = packet(emoji = "👨‍👩‍👧‍👦").encode()
        val packet = MeshPacket.ADAPTER.decode(encoded)
        assertEquals(-1, packet.to)
        assertEquals(3, packet.channel)
        assertEquals(1, packet.from)
        assertEquals(100, packet.id)
        assertTrue(packet.want_ack)
        assertEquals(5, packet.hop_limit)
        assertEquals(5, packet.hop_start)
        assertEquals(PortNum.TEXT_MESSAGE_APP, packet.decoded!!.portnum)
        assertEquals(-2, packet.decoded!!.reply_id)
        assertEquals(1, packet.decoded!!.emoji)
        assertEquals("4294967294", packet.decoded!!.textMetadata().reactionToMessageId)
        assertEquals("👨‍👩‍👧‍👦", packet.decoded!!.textMetadata().reactionEmoji)
    }

    @Test fun `DM reactions target original conversation partner for received and outgoing parent`() {
        val parent = parent("contact:4294967293").copy(channelIndex = 2)
        for (message in listOf(parent, parent.copy(outgoing = true, sender = "You"))) {
            val packet = packet(message, "❤️")
            assertEquals(-3, packet.to)
            assertEquals(2, packet.channel)
            assertEquals("❤️", packet.decoded!!.payload.utf8())
        }
    }

    @Test fun `unsupported messages malformed IDs and oversized payloads cannot become reaction packets`() {
        val invalid = listOf(parent().copy(network = MeshNetwork.MESHCORE), parent().copy(id = "0"),
            parent().copy(id = "not-an-id"), parent("channel:8"), parent("contact:1"), parent("contact:0"), parent("contact:4294967295"),
            parent().copy(reactionToMessageId = "10", reactionEmoji = "👍"))
        invalid.forEach { assertTrue(runCatching { packet(it) }.isFailure) }
        listOf("", "  ", "👍".repeat(17), "👍\n").forEach { emoji ->
            // A trailing newline is trimmed before encoding; embedded controls are rejected.
            if (emoji == "👍\n") assertEquals("👍", packet(emoji = emoji).decoded!!.payload.utf8())
            else assertTrue(runCatching { packet(emoji = emoji) }.isFailure)
        }
        assertTrue(runCatching { packet(emoji = "👍\n👍") }.isFailure)
    }

    @Test fun `own pending and delivered reactions prevent duplicates but failed attempts allow retry`() {
        val parent = parent()
        for (state in listOf(DeliveryState.SENT_TO_RADIO, DeliveryState.SENT, DeliveryState.DELIVERED_TO_MESH, DeliveryState.DELIVERED)) {
            assertTrue(hasOwnReaction(listOf(reaction(parent, state = state)), parent, "👍"))
        }
        for (state in listOf(DeliveryState.ERROR, DeliveryState.UNCONFIRMED)) {
            assertFalse(hasOwnReaction(listOf(reaction(parent, state = state)), parent, "👍"))
        }
        assertFalse(hasOwnReaction(listOf(reaction(parent).copy(outgoing = false)), parent, "👍"))
        assertFalse(hasOwnReaction(listOf(reaction(parent).copy(deviceId = "radio-b")), parent, "👍"))
        assertFalse(hasOwnReaction(listOf(reaction(parent)), parent, "❤️"))
    }

    @Test fun `reaction attachment and counts are isolated by network and source radio`() {
        val first = parent()
        val otherRadio = first.copy(deviceId = "radio-b")
        val mc = first.copy(network = MeshNetwork.MESHCORE)
        val result = collapseMessageReactions(listOf(first, otherRadio, mc, reaction(first)))
        assertEquals(1, result.single { it.message.deviceId == "radio-a" && it.message.network == MeshNetwork.MESHTASTIC }.reactions.size)
        assertTrue(result.single { it.message.deviceId == "radio-b" }.reactions.isEmpty())
        assertTrue(result.single { it.message.network == MeshNetwork.MESHCORE }.reactions.isEmpty())
    }

    @Test fun `retry replaces failed chip state without increasing its count`() {
        val parent = parent()
        val failed = reaction(parent, state = DeliveryState.ERROR).copy(timestampMillis = 1000)
        val retry = reaction(parent, "51", DeliveryState.SENT).copy(timestampMillis = 2000)
        val reactions = collapseMessageReactions(listOf(parent, failed, retry)).single().reactions
        assertEquals(listOf(retry), reactions)
        val lateAck = failed.copy(deliveryState = DeliveryState.DELIVERED)
        val failedRetry = retry.copy(deliveryState = DeliveryState.ERROR)
        assertEquals(listOf(lateAck), collapseMessageReactions(listOf(parent, lateAck, failedRetry)).single().reactions)
    }
}
