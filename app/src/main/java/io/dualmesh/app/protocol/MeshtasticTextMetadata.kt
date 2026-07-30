package io.dualmesh.app.protocol

import org.meshtastic.proto.Data

/**
 * A Meshtastic text packet is a reaction when reply_id and emoji are not zero.
 * The UTF-8 payload contains the emoji that clients show.
 */
internal data class MeshtasticTextMetadata(
    val text: String,
    val reactionToMessageId: String? = null,
    val reactionEmoji: String? = null,
)

internal fun Data.textMetadata(): MeshtasticTextMetadata {
    val text = payload.utf8()
    return if (reply_id != 0 && emoji != 0) {
        MeshtasticTextMetadata(
            text = text,
            reactionToMessageId = reply_id.toUInt().toString(),
            reactionEmoji = text,
        )
    } else {
        MeshtasticTextMetadata(text = text)
    }
}
