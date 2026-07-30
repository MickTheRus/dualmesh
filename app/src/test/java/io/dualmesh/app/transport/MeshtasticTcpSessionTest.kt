package io.dualmesh.app.transport

import io.dualmesh.app.protocol.MeshtasticStreamCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class MeshtasticTcpSessionTest {
    @Test
    fun exchangesFramedPacketsOverRealSocket() {
        val server = ServerSocket(0)
        val serverReceived = CountDownLatch(1)
        val serverFailure = AtomicReference<Throwable?>()
        val outgoingPayload = byteArrayOf(4, 5, 6)
        val incomingPayload = byteArrayOf(1, 2, 3)

        val serverThread = thread(name = "meshtastic-test-server") {
            runCatching {
                server.accept().use { connection ->
                    assertArrayEquals(
                        MeshtasticStreamCodec.WAKE_BYTES,
                        connection.getInputStream().readExactly(4),
                    )
                    connection.getOutputStream().apply {
                        write(MeshtasticStreamCodec.frame(incomingPayload))
                        flush()
                    }
                    assertArrayEquals(
                        MeshtasticStreamCodec.frame(outgoingPayload),
                        connection.getInputStream().readExactly(4 + outgoingPayload.size),
                    )
                    serverReceived.countDown()
                }
            }.onFailure(serverFailure::set)
        }

        val connected = CountDownLatch(1)
        val packetReceived = CountDownLatch(1)
        val received = AtomicReference<ByteArray?>()
        val session = MeshtasticTcpSession(
            object : MeshtasticTcpSession.Listener {
                override fun onTcpConnected() {
                    connected.countDown()
                }

                override fun onTcpPacket(value: ByteArray) {
                    received.set(value)
                    packetReceived.countDown()
                }

                override fun onTcpDisconnected() = Unit

                override fun onTcpError(message: String) = Unit
            },
        )

        try {
            session.connect(MeshtasticTcpSession.Endpoint("127.0.0.1", server.localPort))
            assertTrue(connected.await(5, TimeUnit.SECONDS))
            assertTrue(packetReceived.await(5, TimeUnit.SECONDS))
            assertArrayEquals(incomingPayload, received.get())
            assertTrue(session.send(outgoingPayload))
            assertTrue(serverReceived.await(5, TimeUnit.SECONDS))
            serverFailure.get()?.let { throw AssertionError("TCP server failed", it) }
        } finally {
            session.close()
            server.close()
            serverThread.join(2_000)
        }
    }

    private fun InputStream.readExactly(count: Int): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = read(result, offset, count - offset)
            check(read >= 0) { "Unexpected end of stream" }
            offset += read
        }
        return result
    }
}
