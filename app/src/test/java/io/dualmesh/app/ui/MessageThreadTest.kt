package io.dualmesh.app.ui

import io.dualmesh.app.model.DeliveryState
import io.dualmesh.app.model.MeshNetwork
import io.dualmesh.app.model.UnifiedMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageThreadTest {
    @Test
    fun collapsesLinkedReactionsUnderTheirParent() {
        val parent = message(id = "42", text = "Test")
        val reaction = reaction(id = "100", parentId = parent.id, sender = "Alice", emoji = "👍")

        val items = collapseMessageReactions(listOf(parent, reaction))

        assertEquals(1, items.size)
        assertEquals(parent, items.single().message)
        assertEquals(listOf(reaction), items.single().reactions)
    }

    @Test
    fun preservesReactionWhenReferencedMessageIsUnavailable() {
        val orphan = reaction(id = "100", parentId = "missing", sender = "Alice", emoji = "👍")

        val items = collapseMessageReactions(listOf(orphan))

        assertEquals(1, items.size)
        assertEquals(orphan, items.single().message)
        assertTrue(items.single().reactions.isEmpty())
    }

    @Test
    fun deduplicatesReplayFromSameSenderAndCountsDifferentSenders() {
        val parent = message(id = "42", text = "Test")
        val first = reaction(id = "100", parentId = parent.id, sender = "Alice", emoji = "👍")
        val replay = reaction(id = "101", parentId = parent.id, sender = "Alice", emoji = "👍")
        val secondSender =
            reaction(id = "102", parentId = parent.id, sender = "Bob", emoji = "👍")

        val item = collapseMessageReactions(listOf(parent, first, replay, secondSender)).single()
        val chips = summarizeReactions(item.reactions)

        assertEquals(2, item.reactions.size)
        assertEquals(listOf(ReactionChip("👍", 2)), chips)
    }

    private fun message(id: String, text: String) =
        UnifiedMessage(
            id = id,
            network = MeshNetwork.MESHTASTIC,
            conversationId = "channel:1",
            sender = "Alice",
            text = text,
            timestampMillis = id.toLongOrNull() ?: 0,
            outgoing = false,
            deliveryState = DeliveryState.RECEIVED,
        )

    private fun reaction(
        id: String,
        parentId: String,
        sender: String,
        emoji: String,
    ) =
        message(id, emoji).copy(
            sender = sender,
            reactionToMessageId = parentId,
            reactionEmoji = emoji,
        )
}
