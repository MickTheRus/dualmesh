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

    @Test fun meshCoreUsesDirectionalLittleEndianFramesWithoutMeshtasticWakeBytes() {
        val server = ServerSocket(0)
        val received = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val request = io.dualmesh.app.protocol.MeshCoreProtocol.appStart()
        val response = byteArrayOf(5, 1, 2, 3)
        val worker = thread {
            runCatching {
                server.accept().use { socket ->
                    assertArrayEquals(io.dualmesh.app.protocol.MeshCoreStreamCodec.frame(request), socket.getInputStream().readExactly(request.size + 3))
                    val frame = byteArrayOf(0x3e, response.size.toByte(), 0) + response
                    frame.forEach { socket.getOutputStream().write(byteArrayOf(it)); socket.getOutputStream().flush() }
                    assertTrue(received.await(5, TimeUnit.SECONDS))
                }
            }.onFailure { failure.set(it); received.countDown() }
        }
        lateinit var session: MeshtasticTcpSession
        session = MeshtasticTcpSession(object : MeshtasticTcpSession.Listener {
            override fun onTcpConnected() { session.send(request) }
            override fun onTcpPacket(value: ByteArray) { assertArrayEquals(response, value); received.countDown() }
            override fun onTcpDisconnected() = Unit
            override fun onTcpError(message: String) { failure.set(AssertionError(message)); received.countDown() }
        }, io.dualmesh.app.model.MeshNetwork.MESHCORE)
        try {
            session.connect(MeshtasticTcpSession.Endpoint("127.0.0.1", server.localPort))
            assertTrue(received.await(5, TimeUnit.SECONDS))
            worker.join(2_000)
            failure.get()?.let { throw AssertionError("MeshCore TCP failed", it) }
        } finally { session.close(); server.close(); worker.join(2_000) }
    }

    @Test fun parsesMeshCoreDefaultPortAndRoundTripsIpv6Endpoints() {
        val endpoint = MeshtasticTcpSession.Endpoint.parse("[fe80::1234]:5001", 5000).getOrThrow()
        org.junit.Assert.assertEquals(endpoint, MeshtasticTcpSession.Endpoint.parse(endpoint.toString(), 5000).getOrThrow())
        org.junit.Assert.assertEquals(5000, MeshtasticTcpSession.Endpoint.parse("radio.local", 5000).getOrThrow().port)
        org.junit.Assert.assertEquals(4403, MeshtasticTcpSession.Endpoint.parse("radio.local").getOrThrow().port)
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
