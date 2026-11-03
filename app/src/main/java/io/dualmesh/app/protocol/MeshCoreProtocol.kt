package io.dualmesh.app.protocol

import io.dualmesh.app.model.DeliveryState
import io.dualmesh.app.model.MeshNetwork
import io.dualmesh.app.model.UnifiedMessage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

object MeshCoreProtocol {
    const val RESPONSE_CONTACT_MESSAGE = 7
    const val RESPONSE_CHANNEL_MESSAGE = 8
    const val RESPONSE_NO_MORE_MESSAGES = 10
    const val RESPONSE_CONTACT_MESSAGE_V3 = 16
    const val RESPONSE_CHANNEL_MESSAGE_V3 = 17
    const val RESPONSE_ERROR = 0x01
    const val RESPONSE_DEVICE_INFO = 0x0d
    const val PUSH_MESSAGE_WAITING = 0x83
    const val RESPONSE_CHANNEL_INFO = 0x12
    const val RESPONSE_CONTACTS_START = 0x02
    const val RESPONSE_CONTACT = 0x03
    const val RESPONSE_END_OF_CONTACTS = 0x04
    const val PUSH_TRACE_DATA = 0x89
    const val MAX_CHANNELS = 8

    data class ChannelInfo(
        val index: Int,
        val name: String,
        val configured: Boolean,
    )

    data class ContactInfo(
        val publicKey: String,
        val name: String,
        val type: Int,
        val flags: Int,
        val outPathHashSize: Int,
        val outPath: List<String>,
        val lastAdvertMillis: Long,
        val latitude: Double?,
        val longitude: Double?,
    )

    data class TraceData(
        val tag: UInt,
        val authCode: UInt,
        val flags: Int,
        val hops: List<TraceHop>,
        val finalSnr: Float,
    )

    data class TraceHop(
        val hash: String,
        val snr: Float,
    )

    data class TracePath(
        val hashSize: Int,
        val hashes: List<String>,
        val forwardCount: Int,
    )

    fun deviceQuery(): ByteArray = byteArrayOf(0x16, 0x03)

    fun appStart(appName: String = "DualMesh"): ByteArray =
        ByteArrayOutputStream().apply {
            write(1)
            write(1)
            write(ByteArray(6))
            write(appName.encodeToByteArray())
            write(0)
        }.toByteArray()

    fun setTime(epochSeconds: Int): ByteArray =
        ByteBuffer.allocate(5)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(6)
            .putInt(epochSeconds)
            .array()

    fun syncNextMessage(): ByteArray = byteArrayOf(10)

    fun getContacts(): ByteArray = byteArrayOf(4)

    fun getChannel(index: Int): ByteArray {
        require(index in 0 until MAX_CHANNELS)
        return byteArrayOf(0x1f, index.toByte())
    }

    fun channelMessage(channel: Int, text: String, epochSeconds: Int): ByteArray =
        ByteArrayOutputStream().apply {
            write(3)
            write(0)
            write(channel and 0xff)
            write(littleEndianInt(epochSeconds))
            write(text.encodeToByteArray())
            write(0)
        }.toByteArray()

    fun directMessage(
        contactPublicKey: String,
        text: String,
        epochSeconds: Int,
        attempt: Int = 0,
    ): ByteArray {
        require(attempt in 0..3)
        val publicKey = contactPublicKey.hexToByteArray()
        require(publicKey.size >= 6) { "MeshCore contact key must include its 6-byte prefix" }
        return ByteArrayOutputStream().apply {
            write(2)
            write(0)
            write(attempt)
            write(littleEndianInt(epochSeconds))
            write(publicKey, 0, 6)
            write(text.encodeToByteArray())
            write(0)
        }.toByteArray()
    }

    fun traceRequest(
        tag: UInt,
        pathHashes: List<String>,
        hashSize: Int,
        authCode: UInt = 0u,
    ): ByteArray {
        val flags = when (hashSize) {
            1 -> 0
            2 -> 1
            4 -> 2
            8 -> 3
            else -> throw IllegalArgumentException("MeshCore trace hash size must be 1, 2, 4, or 8")
        }
        require(pathHashes.isNotEmpty()) { "MeshCore trace needs at least one path hop" }
        require(pathHashes.size <= 64 && pathHashes.size * hashSize < 179) { "The round-trip route is too long for a MeshCore trace packet" }
        val path = pathHashes.map { hash ->
            val bytes = hash.hexToByteArray()
            require(bytes.size == hashSize) {
                "MeshCore trace path hashes must use $hashSize bytes"
            }
            bytes
        }
        return ByteArrayOutputStream().apply {
            write(36)
            write(littleEndianInt(tag.toInt()))
            write(littleEndianInt(authCode.toInt()))
            write(flags)
            path.forEach(::write)
        }.toByteArray()
    }

    /**
     * Build a round-trip trace through the contact's stored outbound path.
     * The contact hash is always the turn-around hop, including for a direct contact.
     */
    fun tracePath(contact: ContactInfo): TracePath {
        val hashSize = if (contact.outPath.isEmpty()) 1 else when (contact.outPathHashSize) {
            0 -> 1
            3 -> throw IllegalArgumentException("Traceroute cannot encode 3-byte path hashes; choose a 1- or 2-byte route")
            1, 2, 4, 8 -> contact.outPathHashSize
            else -> throw IllegalArgumentException(
                "MeshCore contact uses an unsupported path hash size",
            )
        }
        val storedPath = contact.outPath
            .map { it.take(hashSize * 2) }

        var hashes = listOf(contact.publicKey.take(hashSize * 2))
        storedPath.asReversed().forEach { hash ->
            hashes = listOf(hash) + hashes + hash
        }
        return TracePath(
            hashSize = hashSize,
            hashes = hashes,
            forwardCount = storedPath.size + 1,
        )
    }

    fun parseMessage(frame: ByteArray, nowMillis: Long = System.currentTimeMillis()): UnifiedMessage? {
        if (frame.isEmpty()) return null
        return when (frame[0].toInt() and 0xff) {
            RESPONSE_CHANNEL_MESSAGE, RESPONSE_CHANNEL_MESSAGE_V3 -> parseChannelMessage(frame, nowMillis)
            RESPONSE_CONTACT_MESSAGE, RESPONSE_CONTACT_MESSAGE_V3 -> parseContactMessage(frame, nowMillis)
            else -> null
        }
    }

    fun parseChannelInfo(frame: ByteArray): ChannelInfo? {
        if (frame.size < 50 || (frame[0].toInt() and 0xff) != RESPONSE_CHANNEL_INFO) return null
        val index = frame[1].toInt() and 0xff
        if (index !in 0 until MAX_CHANNELS) return null
        val nameEnd = (2 until 34).firstOrNull { frame[it].toInt() == 0 } ?: 34
        val name = frame.copyOfRange(2, nameEnd).decodeToString().trim()
        val hasSecret = frame.copyOfRange(34, 50).any { it.toInt() != 0 }
        return ChannelInfo(
            index = index,
            name = name.ifBlank { if (index == 0) "Public" else "Channel $index" },
            configured = index == 0 || name.isNotBlank() || hasSecret,
        )
    }

    fun parseContact(frame: ByteArray): ContactInfo? {
        if (frame.size < 148 || (frame[0].toInt() and 0xff) != RESPONSE_CONTACT) return null
        val publicKey = frame.copyOfRange(1, 33).toHex()
        val nameEnd = (100 until 132).firstOrNull { frame[it].toInt() == 0 } ?: 132
        val name = frame.copyOfRange(100, nameEnd)
            .decodeToString()
            .trim()
            .ifBlank { publicKey.take(12) }
        val type = frame[33].toInt() and 0xff
        val flags = frame[34].toInt() and 0xff
        val encodedPathLength = frame[35].toInt() and 0xff
        val isFloodPath = encodedPathLength == 0xff
        val outPathHashSize = if (isFloodPath) 0 else (encodedPathLength ushr 6) + 1
        val outPathLength = if (isFloodPath) 0 else encodedPathLength and 0x3f
        if (outPathLength * outPathHashSize > 64) return null
        val outPath = if (outPathLength == 0) {
            emptyList()
        } else {
            frame.copyOfRange(36, 36 + outPathLength * outPathHashSize)
                .asIterable()
                .chunked(outPathHashSize)
                .map { chunk -> chunk.toByteArray().toHex() }
        }
        val lastAdvert = readLittleEndianInt(frame, 132).toLong() and 0xffffffffL
        val rawLatitude = readLittleEndianInt(frame, 136)
        val rawLongitude = readLittleEndianInt(frame, 140)
        val hasPosition = rawLatitude != 0 || rawLongitude != 0
        return ContactInfo(
            publicKey = publicKey,
            name = name,
            type = type,
            flags = flags,
            outPathHashSize = outPathHashSize,
            outPath = outPath,
            lastAdvertMillis = lastAdvert * 1000L,
            latitude = rawLatitude.takeIf { hasPosition }?.div(1_000_000.0),
            longitude = rawLongitude.takeIf { hasPosition }?.div(1_000_000.0),
        )
    }

    fun parseTraceData(frame: ByteArray): TraceData? {
        if (frame.size < 13 || (frame[0].toInt() and 0xff) != PUSH_TRACE_DATA) return null
        val pathBytes = frame[2].toInt() and 0xff
        val flags = frame[3].toInt() and 0xff
        val hashSize = 1 shl (flags and 0x03)
        if (pathBytes == 0 || pathBytes % hashSize != 0) return null
        val hopCount = pathBytes / hashSize
        val requiredSize = 12 + pathBytes + hopCount + 1
        if (frame.size < requiredSize) return null
        val tag = readLittleEndianInt(frame, 4).toUInt()
        val authCode = readLittleEndianInt(frame, 8).toUInt()
        var cursor = 12
        val hashes = List(hopCount) {
            frame.copyOfRange(cursor, cursor + hashSize).toHex().also {
                cursor += hashSize
            }
        }
        val hops = hashes.map { hash ->
            TraceHop(hash, frame[cursor++].toFloat() / 4f)
        }
        val finalSnr = frame[cursor].toFloat() / 4f
        return TraceData(tag, authCode, flags, hops, finalSnr)
    }

    fun parseMaxChannels(frame: ByteArray): Int? {
        if (frame.size < 4 || (frame[0].toInt() and 0xff) != RESPONSE_DEVICE_INFO) return null
        val protocolVersion = frame[1].toInt() and 0xff
        if (protocolVersion < 3) return null
        return (frame[3].toInt() and 0xff).takeIf { it > 0 }?.coerceAtMost(MAX_CHANNELS)
    }

    private fun parseChannelMessage(frame: ByteArray, nowMillis: Long): UnifiedMessage? {
        val code = frame[0].toInt() and 0xff
        var cursor = 1
        val channel: Int
        val encodedPathLength: Int
        if (code == RESPONSE_CHANNEL_MESSAGE_V3) {
            if (frame.size < 11) return null
            cursor++ // SNR
            cursor++ // reserved
            cursor++ // reserved
            channel = frame[cursor++].toInt() and 0xff
            encodedPathLength = frame[cursor++].toInt() and 255
        } else {
            if (frame.size < 8) return null
            channel = frame[cursor++].toInt() and 0xff
            encodedPathLength = frame[cursor++].toInt() and 255
        }
        if (cursor + 5 > frame.size) return null
        val textType = frame[cursor++].toInt() and 0xff
        if (textType != 0) return null
        val timestamp = readLittleEndianInt(frame, cursor).toLong() and 0xffffffffL
        cursor += 4
        val rawText = readCString(frame, cursor)
        if (rawText.isBlank()) return null
        val splitAt = rawText.indexOf(':')
        val sender = if (splitAt in 1..49) rawText.substring(0, splitAt) else "Unknown"
        val text = if (splitAt in 1..49) rawText.substring(splitAt + 1).trimStart() else rawText
        return incomingMessage(
            conversationId = "channel:$channel",
            sender = sender,
            text = text,
            timestampMillis = timestamp.takeIf { it > 0 }?.times(1000) ?: nowMillis,
        ).copy(
            hopsAway = encodedPathLength.takeUnless { it == 255 }?.and(63),
            viaMqtt = false,
            snr = if (code == RESPONSE_CHANNEL_MESSAGE_V3 || code == RESPONSE_CONTACT_MESSAGE_V3) frame[1].toInt() / 4f else null,
            receptionPath = if (encodedPathLength == 255) "Direct route" else "Flood",
        )
    }

    private fun parseContactMessage(frame: ByteArray, nowMillis: Long): UnifiedMessage? {
        val code = frame[0].toInt() and 0xff
        var cursor = if (code == RESPONSE_CONTACT_MESSAGE_V3) 4 else 1
        if (cursor + 12 > frame.size) return null
        val senderPrefix = frame.copyOfRange(cursor, cursor + 6).toHex()
        cursor += 6
        val encodedPathLength = frame[cursor++].toInt() and 255
        val textType = frame[cursor++].toInt() and 0xff
        val timestamp = readLittleEndianInt(frame, cursor).toLong() and 0xffffffffL
        cursor += 4
        val normalizedType = textType shr 2
        if (normalizedType == 2 || textType == 2) cursor += 4
        if (cursor >= frame.size) return null
        val text = readCString(frame, cursor)
        if (text.isBlank()) return null
        return incomingMessage(
            conversationId = "contact:$senderPrefix",
            sender = senderPrefix,
            text = text,
            timestampMillis = timestamp.takeIf { it > 0 }?.times(1000) ?: nowMillis,
        ).copy(
            hopsAway = encodedPathLength.takeUnless { it == 255 }?.and(63),
            viaMqtt = false,
            snr = if (code == RESPONSE_CHANNEL_MESSAGE_V3 || code == RESPONSE_CONTACT_MESSAGE_V3) frame[1].toInt() / 4f else null,
            receptionPath = if (encodedPathLength == 255) "Direct route" else "Flood",
        )
    }

    private fun incomingMessage(
        conversationId: String,
        sender: String,
        text: String,
        timestampMillis: Long,
    ) = UnifiedMessage(
        id = messageId(conversationId, sender, text, timestampMillis),
        network = MeshNetwork.MESHCORE,
        conversationId = conversationId,
        sender = sender,
        text = text,
        timestampMillis = timestampMillis,
        outgoing = false,
        deliveryState = DeliveryState.RECEIVED,
    )

    fun messageId(
        conversationId: String,
        sender: String,
        text: String,
        timestampMillis: Long,
    ): String {
        val canonical = "$conversationId\u0000$sender\u0000$text\u0000$timestampMillis"
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.encodeToByteArray())
            .take(16)
            .joinToString("") { "%02x".format(it) }
    }

    private fun littleEndianInt(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun readLittleEndianInt(data: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun readCString(data: ByteArray, offset: Int): String {
        val end = (offset until data.size).firstOrNull { data[it].toInt() == 0 } ?: data.size
        return data.copyOfRange(offset, end).decodeToString()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToByteArray(): ByteArray {
        require(length % 2 == 0 && all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "Invalid hexadecimal public key"
        }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
