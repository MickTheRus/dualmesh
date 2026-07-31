package io.dualmesh.app.history

import io.dualmesh.app.model.UnifiedMessage

internal fun resolveReactionConversation(
    message: UnifiedMessage,
    history: List<UnifiedMessage>,
): UnifiedMessage {
    val parentId = message.reactionToMessageId ?: return message
    val parent = history.firstOrNull {
        it.network == message.network &&
            it.deviceId == message.deviceId &&
            it.id == parentId &&
            !it.isReaction
    } ?: return message
    return message.copy(conversationId = parent.conversationId)
}
