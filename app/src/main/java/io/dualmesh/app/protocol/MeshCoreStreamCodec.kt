package io.dualmesh.app.protocol

/** USB/TCP companion framing: '<' + little-endian length outbound, '>' inbound. */
class MeshCoreStreamCodec(private val onPacket: (ByteArray) -> Unit) {
    private var state = 0
    private var length = 0
    private var offset = 0
    private val buffer = ByteArray(MAX_PACKET_SIZE)
    fun accept(bytes: ByteArray, count: Int = bytes.size) {
        for (i in 0 until count.coerceIn(0, bytes.size)) {
            val byte = bytes[i]
            when (state) {
                0 -> if (byte == 0x3e.toByte()) state = 1
                1 -> { length = byte.toInt() and 255; state = 2 }
                2 -> {
                    length = length or ((byte.toInt() and 255) shl 8)
                    offset = 0
                    state = if (length in 1..MAX_PACKET_SIZE) 3 else if (byte == 0x3e.toByte()) 1 else 0
                }
                else -> {
                    buffer[offset++] = byte
                    if (offset == length) { state = 0; onPacket(buffer.copyOf(length)) }
                }
            }
        }
    }
    companion object {
        const val MAX_PACKET_SIZE = 512
        fun frame(payload: ByteArray): ByteArray {
            require(payload.size in 1..MAX_PACKET_SIZE) { "Invalid MeshCore companion frame length" }
            return byteArrayOf(0x3c, payload.size.toByte(), (payload.size ushr 8).toByte()) + payload
        }
    }
}
