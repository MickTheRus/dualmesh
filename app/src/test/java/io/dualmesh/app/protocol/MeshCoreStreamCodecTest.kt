package io.dualmesh.app.protocol

import org.junit.Assert.*
import org.junit.Test

class MeshCoreStreamCodecTest {
    @Test fun readsFragmentedAndCoalescedFramesIncludingMarkerBytesInPayload() {
        val result = mutableListOf<ByteArray>()
        val codec = MeshCoreStreamCodec { result += it }
        val frame = byteArrayOf(0x3e, 3, 0, 5, 0x3e, 0x3c)
        frame.forEach { codec.accept(byteArrayOf(it)) }
        codec.accept(frame + frame)
        assertEquals(3, result.size)
        result.forEach { assertArrayEquals(byteArrayOf(5, 0x3e, 0x3c), it) }
    }
    @Test fun boundsLengthsRecoversFromNoiseAndFramesCommandsLittleEndian() {
        val result = mutableListOf<ByteArray>()
        val codec = MeshCoreStreamCodec { result += it }
        codec.accept(byteArrayOf(1, 2, 0x3e, -1, -1, 0x3e, 0, 0, 0x3e, 1, 0, 5))
        assertArrayEquals(byteArrayOf(5), result.single())
        val payload = ByteArray(300) { it.toByte() }
        val framed = MeshCoreStreamCodec.frame(payload)
        assertArrayEquals(byteArrayOf(0x3c, 44, 1), framed.copyOf(3))
        assertArrayEquals(payload, framed.copyOfRange(3, framed.size))
        assertThrows(IllegalArgumentException::class.java) { MeshCoreStreamCodec.frame(ByteArray(513)) }
        assertThrows(IllegalArgumentException::class.java) { MeshCoreStreamCodec.frame(byteArrayOf()) }
    }
}
