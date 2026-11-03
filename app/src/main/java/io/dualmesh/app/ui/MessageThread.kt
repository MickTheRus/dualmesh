package io.dualmesh.app.ui

import io.dualmesh.app.model.DeliveryState
import io.dualmesh.app.model.UnifiedMessage

internal data class MessageThreadItem(
    val message: UnifiedMessage,
    val reactions: List<UnifiedMessage> = emptyList(),
)

internal data class ReactionChip(
    val emoji: String,
    val count: Int,
)

/**
 * Link each reaction packet to its referenced message.
 * Keep the reaction visible if the referenced message is not available.
 * This can occur if the radio queue or local history was truncated.
 */
internal fun collapseMessageReactions(messages: List<UnifiedMessage>): List<MessageThreadItem> {
    fun key(message: UnifiedMessage, id: String = message.id) = Triple(message.network, message.deviceId, id)
    val parents = messages.filterNot(UnifiedMessage::isReaction).associateBy { key(it) }
    val linked = messages.filter { it.isReaction && key(it, it.reactionToMessageId!!) in parents }
        .groupBy { key(it, it.reactionToMessageId!!) }
    return messages.filterNot { it.isReaction && key(it, it.reactionToMessageId!!) in parents }.map { message ->
        MessageThreadItem(message, linked[key(message)].orEmpty()
            .sortedWith(compareByDescending<UnifiedMessage> {
                when (it.deliveryState) {
                    DeliveryState.DELIVERED -> 5
                    DeliveryState.DELIVERED_TO_MESH -> 4
                    DeliveryState.RECEIVED -> 3
                    DeliveryState.SENT, DeliveryState.SENT_TO_RADIO, DeliveryState.SENDING -> 2
                    DeliveryState.ERROR, DeliveryState.UNCONFIRMED -> 1
                }
            }.thenByDescending { it.timestampMillis })
            .distinctBy { it.sender to it.reactionEmoji })
    }
}

internal fun summarizeReactions(reactions: List<UnifiedMessage>): List<ReactionChip> =
    reactions
        .mapNotNull(UnifiedMessage::reactionEmoji)
        .groupingBy { it }
        .eachCount()
        .map { (emoji, count) -> ReactionChip(emoji, count) }
