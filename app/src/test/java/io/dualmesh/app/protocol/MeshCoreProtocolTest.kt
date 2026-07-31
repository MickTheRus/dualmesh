package io.dualmesh.app.protocol

import io.dualmesh.app.model.MeshNetwork
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

class MeshCoreProtocolTest {
    @Test
    fun deviceQueryNegotiatesOfficialProtocolVersion() {
        assertArrayEquals(byteArrayOf(0x16, 0x03), MeshCoreProtocol.deviceQuery())
    }

    @Test
    fun channelMessageFrameMatchesCompanionProtocol() {
        val frame = MeshCoreProtocol.channelMessage(channel = 2, text = "hello", epochSeconds = 1234)

        assertEquals(3, frame[0].toInt())
        assertEquals(0, frame[1].toInt())
        assertEquals(2, frame[2].toInt())
        assertArrayEquals(
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1234).array(),
            frame.copyOfRange(3, 7),
        )
        assertEquals("hello", frame.copyOfRange(7, frame.lastIndex).decodeToString())
        assertEquals(0, frame.last().toInt())
    }

    @Test
    fun directMessageTargetsContactPrefixAndRequestsAck() {
        val publicKey = (0 until 32).joinToString("") { "%02x".format(it) }

        val frame = MeshCoreProtocol.directMessage(
            contactPublicKey = publicKey,
            text = "private hello",
            epochSeconds = 1234,
        )

        assertEquals(2, frame[0].toInt())
        assertEquals(0, frame[1].toInt())
        assertEquals(0, frame[2].toInt())
        assertArrayEquals(
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1234).array(),
            frame.copyOfRange(3, 7),
        )
        assertArrayEquals((0 until 6).map(Int::toByte).toByteArray(), frame.copyOfRange(7, 13))
        assertEquals("private hello", frame.copyOfRange(13, frame.lastIndex).decodeToString())
        assertEquals(0, frame.last().toInt())
    }

    @Test
    fun parsesContactListRecord() {
        val frame = ByteArray(148)
        frame[0] = MeshCoreProtocol.RESPONSE_CONTACT.toByte()
        (0 until 32).forEach { frame[1 + it] = it.toByte() }
        "Alice Radio".encodeToByteArray().copyInto(frame, destinationOffset = 100)
        frame[33] = 2
        frame[34] = 5
        frame[35] = 0x82.toByte()
        byteArrayOf(0x12, 0x34, 0x56, 0x78).copyInto(frame, destinationOffset = 36)
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1_700_000_000)
            .array().copyInto(frame, destinationOffset = 132)
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(123_456)
            .array().copyInto(frame, destinationOffset = 136)
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(-654_321)
            .array().copyInto(frame, destinationOffset = 140)

        val contact = MeshCoreProtocol.parseContact(frame)

        assertNotNull(contact)
        assertEquals("Alice Radio", contact?.name)
        assertEquals(64, contact?.publicKey?.length)
        assert(contact?.publicKey?.startsWith("000102030405") == true)
        assertEquals(2, contact?.type)
        assertEquals(5, contact?.flags)
        assertEquals(3, contact?.outPathHashSize)
        assertEquals(listOf("123456", "780000"), contact?.outPath)
        assertEquals(1_700_000_000_000L, contact?.lastAdvertMillis)
        assertEquals(0.123456, contact?.latitude ?: 0.0, 0.000001)
        assertEquals(-0.654321, contact?.longitude ?: 0.0, 0.000001)
    }

    @Test
    fun traceRequestUsesTagFlagsAndPath() {
        val frame = MeshCoreProtocol.traceRequest(
            tag = 0x12345678u,
            pathHashes = listOf("aabb", "ccdd"),
            hashSize = 2,
        )

        assertEquals(36, frame[0].toInt())
        assertArrayEquals(
            byteArrayOf(0x78, 0x56, 0x34, 0x12),
            frame.copyOfRange(1, 5),
        )
        assertEquals(1, frame[9].toInt())
        assertArrayEquals(
            byteArrayOf(0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte(), 0xdd.toByte()),
            frame.copyOfRange(10, 14),
        )
    }

    @Test
    fun directContactBuildsAZeroHopRoundTripTrace() {
        val contact = MeshCoreProtocol.ContactInfo(
            publicKey = "aabbccdd".padEnd(64, '0'),
            name = "Direct contact",
            type = 1,
            flags = 0,
            outPathHashSize = 0,
            outPath = emptyList(),
            lastAdvertMillis = 0,
            latitude = null,
            longitude = null,
        )

        val path = MeshCoreProtocol.tracePath(contact)

        assertEquals(1, path.hashSize)
        assertEquals(listOf("aa"), path.hashes)
        assertEquals(1, path.forwardCount)
    }

    @Test
    fun routedContactBuildsAPathToTheContactAndBack() {
        val contact = MeshCoreProtocol.ContactInfo(
            publicKey = "ccdd".padEnd(64, '0'),
            name = "Routed contact",
            type = 1,
            flags = 0,
            outPathHashSize = 1,
            outPath = listOf("aa", "bb"),
            lastAdvertMillis = 0,
            latitude = null,
            longitude = null,
        )

        val path = MeshCoreProtocol.tracePath(contact)

        assertEquals(1, path.hashSize)
        assertEquals(listOf("aa", "bb", "cc", "bb", "aa"), path.hashes)
        assertEquals(3, path.forwardCount)
    }

    @Test
    fun parsesMultiByteTraceResponse() {
        val frame = byteArrayOf(
            MeshCoreProtocol.PUSH_TRACE_DATA.toByte(),
            0,
            4,
            1,
            0x78,
            0x56,
            0x34,
            0x12,
            0,
            0,
            0,
            0,
            0xaa.toByte(),
            0xbb.toByte(),
            0xcc.toByte(),
            0xdd.toByte(),
            40,
            -8,
            20,
        )

        val trace = MeshCoreProtocol.parseTraceData(frame)

        assertNotNull(trace)
        assertEquals(0x12345678u, trace?.tag)
        assertEquals("aabb", trace?.hops?.get(0)?.hash)
        assertEquals(10f, trace?.hops?.get(0)?.snr)
        assertEquals("ccdd", trace?.hops?.get(1)?.hash)
        assertEquals(-2f, trace?.hops?.get(1)?.snr)
        assertEquals(5f, trace?.finalSnr)
    }

    @Test
    fun contactSyncCommandMatchesCompanionProtocol() {
        assertArrayEquals(byteArrayOf(4), MeshCoreProtocol.getContacts())
    }

    @Test
    fun parsesLegacyChannelMessage() {
        val timestamp = ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(1_700_000_000)
            .array()
        val frame = byteArrayOf(8, 0, 0, 0) +
            timestamp +
            "Alice: radio check\u0000".encodeToByteArray()

        val message = MeshCoreProtocol.parseMessage(frame)

        assertNotNull(message)
        assertEquals(MeshNetwork.MESHCORE, message?.network)
        assertEquals("Alice", message?.sender)
        assertEquals("radio check", message?.text)
        assertEquals("channel:0", message?.conversationId)
    }

    @Test
    fun parsesV3ChannelMessageWithoutTreatingPathMetadataAsPayload() {
        val timestamp = ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(1_700_000_001)
            .array()
        val frame = byteArrayOf(
            MeshCoreProtocol.RESPONSE_CHANNEL_MESSAGE_V3.toByte(),
            12, // SNR
            0, // reserved
            0, // reserved
            3, // channel
            0x82.toByte(), // path metadata only; no path bytes follow
            0, // text message
        ) + timestamp + "Bob: v3 works\u0000".encodeToByteArray()

        val message = MeshCoreProtocol.parseMessage(frame)

        assertNotNull(message)
        assertEquals("channel:3", message?.conversationId)
        assertEquals("Bob", message?.sender)
        assertEquals("v3 works", message?.text)
    }

    @Test
    fun parsesConfiguredChannelInfo() {
        val frame = ByteArray(50)
        frame[0] = MeshCoreProtocol.RESPONSE_CHANNEL_INFO.toByte()
        frame[1] = 4
        "Ops".encodeToByteArray().copyInto(frame, destinationOffset = 2)
        frame[34] = 1

        val channel = MeshCoreProtocol.parseChannelInfo(frame)

        assertNotNull(channel)
        assertEquals(4, channel?.index)
        assertEquals("Ops", channel?.name)
        assertEquals(true, channel?.configured)
    }

    @Test
    fun parsesAndCapsDeviceChannelCapacity() {
        val frame = byteArrayOf(
            MeshCoreProtocol.RESPONSE_DEVICE_INFO.toByte(),
            3,
            32,
            12,
        )

        assertEquals(MeshCoreProtocol.MAX_CHANNELS, MeshCoreProtocol.parseMaxChannels(frame))
    }

    @Test
    fun identicalMessagesHaveStableIds() {
        val first = MeshCoreProtocol.messageId("channel:0", "Alice", "hello", 1_700_000_000_000)
        val second = MeshCoreProtocol.messageId("channel:0", "Alice", "hello", 1_700_000_000_000)
        val different = MeshCoreProtocol.messageId("channel:0", "Alice", "hello!", 1_700_000_000_000)

        assertEquals(first, second)
        assertEquals(32, first.length)
        assert(first != different)
    }

    @Test
    fun malformedFramesNeverCrashTheParser() {
        val random = Random(42)

        repeat(10_000) {
            val frame = random.nextBytes(random.nextInt(0, 256))
            MeshCoreProtocol.parseMessage(frame)
            MeshCoreProtocol.parseContact(frame)
            MeshCoreProtocol.parseTraceData(frame)
        }

        val oversizedV3Path = ByteArray(11).also {
            it[0] = MeshCoreProtocol.RESPONSE_CHANNEL_MESSAGE_V3.toByte()
            it[2] = 1 // path flag
            it[5] = 0x80.toByte()
        }
        assertEquals(null, MeshCoreProtocol.parseMessage(oversizedV3Path))
    }
}
