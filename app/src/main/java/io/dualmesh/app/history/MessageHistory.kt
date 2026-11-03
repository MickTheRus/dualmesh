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

/** Merge queue/ACK updates and radio echoes without erasing confirmed delivery or receive metadata. */
internal fun mergeMessageObservation(previous: UnifiedMessage?, incoming: UnifiedMessage): UnifiedMessage {
    if (previous == null) return incoming
    val preserveDelivery = previous.outgoing && (
        incoming.deliveryState == io.dualmesh.app.model.DeliveryState.RECEIVED ||
            previous.deliveryState == io.dualmesh.app.model.DeliveryState.DELIVERED ||
            (previous.deliveryState == io.dualmesh.app.model.DeliveryState.DELIVERED_TO_MESH &&
                incoming.deliveryState in setOf(io.dualmesh.app.model.DeliveryState.SENDING, io.dualmesh.app.model.DeliveryState.SENT_TO_RADIO, io.dualmesh.app.model.DeliveryState.SENT))
        )
    return incoming.copy(
        outgoing = previous.outgoing || incoming.outgoing,
        timestampMillis = if (previous.outgoing) previous.timestampMillis else incoming.timestampMillis,
        deliveryState = if (preserveDelivery) previous.deliveryState else incoming.deliveryState,
        deliveryDetail = if (preserveDelivery) previous.deliveryDetail else incoming.deliveryDetail ?: previous.deliveryDetail,
        hopsAway = incoming.hopsAway ?: previous.hopsAway,
        viaMqtt = incoming.viaMqtt ?: previous.viaMqtt,
        snr = incoming.snr ?: previous.snr,
        rssi = incoming.rssi ?: previous.rssi,
        receptionPath = incoming.receptionPath ?: previous.receptionPath,
        channelIndex = incoming.channelIndex ?: previous.channelIndex,
    )
}

internal fun hasOwnReaction(history: List<UnifiedMessage>, parent: UnifiedMessage, emoji: String): Boolean =
    history.any {
        it.network == parent.network && it.deviceId == parent.deviceId && it.outgoing &&
            it.reactionToMessageId == parent.id && it.reactionEmoji == emoji &&
            it.deliveryState !in setOf(io.dualmesh.app.model.DeliveryState.ERROR, io.dualmesh.app.model.DeliveryState.UNCONFIRMED)
    }
