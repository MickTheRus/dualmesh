package io.dualmesh.app.protocol

import io.dualmesh.app.model.MeshNetwork
import io.dualmesh.app.model.UnifiedMessage
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Constants
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum

/** Native Meshtastic reaction: UTF-8 payload, original packet ID, and emoji indicator 1. */
internal fun meshtasticReactionPacket(
    parent: UnifiedMessage,
    emoji: String,
    localNode: Int,
    packetId: Int,
    hopLimit: Int,
): MeshPacket {
    require(parent.network == MeshNetwork.MESHTASTIC && !parent.isReaction) { "Only Meshtastic text messages support reactions" }
    val replyId = parent.id.toUIntOrNull()?.takeIf { it != 0u }
        ?: throw IllegalArgumentException("This message has no valid packet ID")
    val text = emoji.trim()
    require(text.isNotEmpty() && text.encodeToByteArray().size <= 64 && text.none { it.isISOControl() }) { "Choose an emoji (maximum 64 UTF-8 bytes)" }
    val channel: Int
    val destination: Int
    when {
        parent.conversationId.startsWith("channel:") -> {
            channel = parent.conversationId.removePrefix("channel:").toIntOrNull()
                ?.takeIf { it in 0..7 } ?: throw IllegalArgumentException("Invalid reaction channel")
            destination = -1
        }
        parent.conversationId.startsWith("contact:") -> {
            channel = parent.channelIndex ?: 0
            require(channel in 0..7) { "Invalid reaction channel" }
            destination = parent.conversationId.removePrefix("contact:").toUIntOrNull()
                ?.takeIf { it != 0u && it != UInt.MAX_VALUE }?.toInt()
                ?: throw IllegalArgumentException("Invalid reaction recipient")
            require(destination != localNode) { "Cannot send a direct reaction to this node" }
        }
        else -> throw IllegalArgumentException("Invalid reaction conversation")
    }
    val data = Data(portnum = PortNum.TEXT_MESSAGE_APP, payload = text.encodeToByteArray().toByteString(), reply_id = replyId.toInt(), emoji = 1)
    require(Data.ADAPTER.encodedSize(data) <= Constants.DATA_PAYLOAD_LEN.value) { "Reaction exceeds the radio payload limit" }
    require(packetId != 0 && hopLimit in 0..7)
    return MeshPacket(from = localNode, to = destination, channel = channel, decoded = data, id = packetId,
        want_ack = true, hop_limit = hopLimit, hop_start = hopLimit)
}
