package io.dualmesh.app.protocol

import io.dualmesh.app.transport.MeshtasticTcpSession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshtasticStreamCodecTest {
    @Test
    fun framesPayloadWithOfficialStreamHeader() {
        val frame = MeshtasticStreamCodec.frame(byteArrayOf(0x10, 0x20, 0x30))

        assertArrayEquals(
            byteArrayOf(0x94.toByte(), 0xc3.toByte(), 0, 3, 0x10, 0x20, 0x30),
            frame,
        )
    }

    @Test
    fun decodesFragmentedFramesAndWakePadding() {
        val packets = mutableListOf<ByteArray>()
        val codec = MeshtasticStreamCodec(packets::add)
        val frame = MeshtasticStreamCodec.WAKE_BYTES +
            MeshtasticStreamCodec.frame(byteArrayOf(1, 2, 3))

        frame.forEach { codec.accept(it) }

        assertEquals(1, packets.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), packets.single())
    }

    @Test
    fun oversizedFrameResynchronizesOnNextHeader() {
        val packets = mutableListOf<ByteArray>()
        val codec = MeshtasticStreamCodec(packets::add)
        val corrupt = byteArrayOf(
            0x94.toByte(),
            0xc3.toByte(),
            0x02,
            0x01,
        )

        codec.accept(corrupt + MeshtasticStreamCodec.frame(byteArrayOf(42)))

        assertEquals(1, packets.size)
        assertArrayEquals(byteArrayOf(42), packets.single())
    }

    @Test
    fun parsesDefaultAndCustomTcpEndpoints() {
        val default = MeshtasticTcpSession.Endpoint.parse("mesh-radio.local").getOrThrow()
        val custom = MeshtasticTcpSession.Endpoint.parse("192.0.2.10:5500").getOrThrow()
        val ipv6 = MeshtasticTcpSession.Endpoint.parse("[fe80::1]:4403").getOrThrow()

        assertEquals(4403, default.port)
        assertEquals("192.0.2.10", custom.host)
        assertEquals(5500, custom.port)
        assertEquals("fe80::1", ipv6.host)
        assertTrue(MeshtasticTcpSession.Endpoint.parse("radio:70000").isFailure)
    }
}
