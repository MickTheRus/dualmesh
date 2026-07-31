package io.dualmesh.app.history

import io.dualmesh.app.model.DeliveryState
import io.dualmesh.app.model.MeshNetwork
import io.dualmesh.app.model.UnifiedMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageHistoryTest {
    @Test
    fun movesReactionToReferencedMessagesConversation() {
        val parent = message(id = "42", conversationId = "channel:1")
        val reaction = message(
            id = "100",
            conversationId = "channel:0",
            reactionToMessageId = parent.id,
        )

        val resolved = resolveReactionConversation(reaction, listOf(parent))

        assertEquals("channel:1", resolved.conversationId)
    }

    @Test
    fun doesNotMoveReactionAcrossRadios() {
        val parent = message(id = "42", conversationId = "channel:1", deviceId = "radio-a")
        val reaction = message(
            id = "100",
            conversationId = "channel:0",
            deviceId = "radio-b",
            reactionToMessageId = parent.id,
        )

        val resolved = resolveReactionConversation(reaction, listOf(parent))

        assertEquals("channel:0", resolved.conversationId)
    }

    private fun message(
        id: String,
        conversationId: String,
        deviceId: String = "radio-a",
        reactionToMessageId: String? = null,
    ) = UnifiedMessage(
        id = id,
        network = MeshNetwork.MESHTASTIC,
        conversationId = conversationId,
        sender = "Test node",
        text = if (reactionToMessageId == null) "Test" else "*️⃣",
        timestampMillis = id.toLong(),
        outgoing = false,
        deliveryState = DeliveryState.RECEIVED,
        deviceId = deviceId,
        reactionToMessageId = reactionToMessageId,
        reactionEmoji = if (reactionToMessageId == null) null else "*️⃣",
    )
}
