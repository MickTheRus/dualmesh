package io.dualmesh.app.protocol

class MeshtasticStreamCodec(
    private val onPacket: (ByteArray) -> Unit,
) {
    private var state = 0
    private var lengthMsb = 0
    private var packetLength = 0
    private var packetOffset = 0
    private val packet = ByteArray(MAX_PACKET_SIZE)

    fun accept(bytes: ByteArray, count: Int = bytes.size) {
        for (index in 0 until count.coerceAtMost(bytes.size)) {
            accept(bytes[index])
        }
    }

    fun accept(byte: Byte) {
        when (state) {
            0 -> if (byte == START_1) state = 1
            1 -> when (byte) {
                START_2 -> state = 2
                START_1 -> Unit
                else -> state = 0
            }
            2 -> {
                lengthMsb = byte.toInt() and 0xff
                state = 3
            }
            3 -> {
                packetLength = (lengthMsb shl 8) or (byte.toInt() and 0xff)
                packetOffset = 0
                when {
                    packetLength > MAX_PACKET_SIZE -> reset(byte)
                    packetLength == 0 -> {
                        onPacket(byteArrayOf())
                        state = 0
                    }
                    else -> state = 4
                }
            }
            else -> {
                packet[packetOffset++] = byte
                if (packetOffset == packetLength) {
                    onPacket(packet.copyOf(packetLength))
                    state = 0
                }
            }
        }
    }

    fun reset() {
        state = 0
        lengthMsb = 0
        packetLength = 0
        packetOffset = 0
    }

    private fun reset(lastByte: Byte) {
        reset()
        if (lastByte == START_1) state = 1
    }

    companion object {
        const val DEFAULT_PORT = 4403
        const val MAX_PACKET_SIZE = 512
        val START_1: Byte = 0x94.toByte()
        val START_2: Byte = 0xc3.toByte()
        val WAKE_BYTES = byteArrayOf(START_1, START_1, START_1, START_1)

        fun frame(payload: ByteArray): ByteArray {
            require(payload.size <= MAX_PACKET_SIZE) {
                "Meshtastic stream packets are limited to $MAX_PACKET_SIZE bytes"
            }
            return byteArrayOf(
                START_1,
                START_2,
                (payload.size shr 8).toByte(),
                (payload.size and 0xff).toByte(),
            ) + payload
        }
    }
}
