package io.dualmesh.app.protocol

import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.meshtastic.proto.Data
import org.meshtastic.proto.PortNum

class MeshtasticTextMetadataTest {
    @Test
    fun detectsReactionOnlyWhenReplyAndEmojiFlagsAreSet() {
        val metadata = data("👍", replyId = 42, emoji = 1).textMetadata()

        assertEquals("👍", metadata.text)
        assertEquals("42", metadata.reactionToMessageId)
        assertEquals("👍", metadata.reactionEmoji)
    }

    @Test
    fun keepsStandaloneEmojiAsARegularMessage() {
        val metadata = data("👍", replyId = 0, emoji = 0).textMetadata()

        assertNull(metadata.reactionToMessageId)
        assertNull(metadata.reactionEmoji)
    }

    @Test
    fun keepsTextReplyAsARegularMessage() {
        val metadata = data("On my way", replyId = 42, emoji = 0).textMetadata()

        assertEquals("On my way", metadata.text)
        assertNull(metadata.reactionEmoji)
    }

    @Test
    fun normalizesUnsignedReplyPacketId() {
        val metadata = data("❤️", replyId = -1, emoji = 1).textMetadata()

        assertEquals("4294967295", metadata.reactionToMessageId)
    }

    private fun data(text: String, replyId: Int, emoji: Int) =
        Data(
            portnum = PortNum.TEXT_MESSAGE_APP,
            payload = text.encodeToByteArray().toByteString(),
            reply_id = replyId,
            emoji = emoji,
        )
}
