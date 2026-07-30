package io.dualmesh.app.ui

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
    val parentIds = messages.asSequence()
        .filterNot(UnifiedMessage::isReaction)
        .mapTo(mutableSetOf(), UnifiedMessage::id)
    val linkedReactions = messages.asSequence()
        .filter { it.isReaction && it.reactionToMessageId in parentIds }
        .groupBy(UnifiedMessage::reactionToMessageId)

    return messages
        .filterNot { it.isReaction && it.reactionToMessageId in parentIds }
        .map { message ->
            MessageThreadItem(
                message = message,
                reactions = linkedReactions[message.id]
                    .orEmpty()
                    .distinctBy { it.sender to it.reactionEmoji },
            )
        }
}

internal fun summarizeReactions(reactions: List<UnifiedMessage>): List<ReactionChip> =
    reactions
        .mapNotNull(UnifiedMessage::reactionEmoji)
        .groupingBy { it }
        .eachCount()
        .map { (emoji, count) -> ReactionChip(emoji, count) }
