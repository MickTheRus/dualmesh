package io.dualmesh.app.transport

import io.dualmesh.app.protocol.MeshtasticMqttSettings
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.MqttClientProxyMessage
import org.meshtastic.proto.ServiceEnvelope
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Exercise the real MQTT client against a loopback broker, without public traffic or RF. */
class MeshtasticMqttProxyTest {
    @Test fun `initial broker unavailability retries but rejected credentials stop retrying`() {
        Broker(connectCodes = listOf(3)).use { broker ->
            val proxy = MeshtasticMqttProxy {}
            try {
                proxy.configure(broker.settings(), 1)
                broker.peer() // First CONNACK rejects the connection as temporarily unavailable.
                broker.peer().subscriptions()
                await { proxy.status.value == "MQTT proxy connected" }
            } finally {
                proxy.stop()
            }
        }
        Broker(connectCodes = listOf(5)).use { broker ->
            val proxy = MeshtasticMqttProxy {}
            try {
                proxy.configure(broker.settings(), 1)
                broker.peer()
                await { proxy.status.value == "MQTT proxy: check broker credentials" }
                assertTrue(broker.noNewPeer())
            } finally {
                proxy.stop()
            }
        }
    }

    @Test fun `queued broker downlinks cannot reach a stopped or replacement radio session`() {
        Broker().use { broker ->
            val dispatched = LinkedBlockingQueue<() -> Unit>()
            val received = LinkedBlockingQueue<MqttClientProxyMessage>()
            val proxy = MeshtasticMqttProxy(dispatchToRadio = { dispatched.add(it) }) { received.add(it) }
            try {
                proxy.configure(broker.settings(), 1)
                val peer = broker.peer()
                peer.subscriptions()
                peer.publish("test/2/e/Test/!00000002", envelope())
                val queued = requireNotNull(dispatched.poll(5, TimeUnit.SECONDS))
                proxy.stop()
                queued()
                assertTrue(received.isEmpty())
                proxy.configure(broker.settings(), 3)
                val replacement = broker.peer()
                replacement.subscriptions()
                replacement.publish("test/2/e/Test/!00000002", envelope())
                requireNotNull(dispatched.poll(5, TimeUnit.SECONDS)).invoke()
                assertNotNull(received.poll(5, TimeUnit.SECONDS))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test fun `proxy publishes and receives binary and JSON with retention`() {
        Broker().use { broker ->
            val received = LinkedBlockingQueue<MqttClientProxyMessage>()
            val proxy = MeshtasticMqttProxy { received.add(it) }
            try {
                proxy.configure(broker.settings(), 1)
                val peer = broker.peer()
                assertEquals(listOf("test/2/e/Test/+", "test/2/json/Test/+", "test/2/e/PKI/+"), peer.subscriptions())
                await { proxy.status.value == "MQTT proxy connected" }
                assertEquals("user", peer.username)
                assertEquals("secret", peer.password)
                val bytes = envelope()
                proxy.publish(MqttClientProxyMessage(topic = "test/2/e/Test/!00000001", data_ = bytes.toByteString(), retained = true))
                val published = peer.published.poll(5, TimeUnit.SECONDS)!!
                assertArrayEquals(bytes, published.payload)
                assertTrue(published.retained)
                proxy.publish(MqttClientProxyMessage(topic = "test/2/json/Test/!00000001", text = "{\"value\":1}"))
                assertEquals("{\"value\":1}", peer.published.poll(5, TimeUnit.SECONDS)!!.payload.decodeToString())

                peer.publish("test/2/e/Test/!00000002", bytes, retained = true)
                val downlink = received.poll(5, TimeUnit.SECONDS)!!
                assertArrayEquals(bytes, downlink.data_!!.toByteArray())
                assertTrue(downlink.retained)
                val json = """{"from":2,"sender":"!00000002"}"""
                peer.publish("test/2/json/Test/!00000002", json.encodeToByteArray())
                assertEquals(json, received.poll(5, TimeUnit.SECONDS)!!.text)
            } finally {
                proxy.stop()
            }
            assertEquals("MQTT proxy off", proxy.status.value)
        }
    }

    @Test fun `broker disconnect reconnects and restores subscriptions`() {
        Broker().use { broker ->
            val received = LinkedBlockingQueue<MqttClientProxyMessage>()
            val proxy = MeshtasticMqttProxy { received.add(it) }
            try {
                proxy.configure(broker.settings(), 1)
                val first = broker.peer()
                val topics = first.subscriptions()
                first.close()
                val second = broker.peer()
                assertEquals(topics, second.subscriptions())
                assertEquals(first.clientId, second.clientId)
                second.publish("test/2/e/Test/!00000002", envelope())
                assertNotNull(received.poll(5, TimeUnit.SECONDS))
            } finally {
                proxy.stop()
            }
        }
    }

    @Test fun `radio sessions stay isolated and stop closes their broker connection`() {
        Broker().use { broker ->
            val firstMessages = LinkedBlockingQueue<MqttClientProxyMessage>()
            val secondMessages = LinkedBlockingQueue<MqttClientProxyMessage>()
            val first = MeshtasticMqttProxy { firstMessages.add(it) }
            val second = MeshtasticMqttProxy { secondMessages.add(it) }
            try {
                first.configure(broker.settings(), 1)
                val firstPeer = broker.peer()
                firstPeer.subscriptions()
                second.configure(broker.settings(), 3)
                val secondPeer = broker.peer()
                secondPeer.subscriptions()
                assertNotEquals(firstPeer.clientId, secondPeer.clientId)
                firstPeer.publish("test/2/e/Test/!00000002", envelope())
                assertNotNull(firstMessages.poll(5, TimeUnit.SECONDS))
                assertNull(secondMessages.poll(150, TimeUnit.MILLISECONDS))
                first.stop()
                assertTrue(firstPeer.closed.poll(5, TimeUnit.SECONDS) == true)
                secondPeer.publish("test/2/e/Test/!00000002", envelope())
                assertNotNull(secondMessages.poll(5, TimeUnit.SECONDS))
            } finally {
                first.stop()
                second.stop()
            }
        }
    }

    @Test fun `configuration replacement closes old broker session and uses new topics`() {
        Broker().use { broker ->
            val proxy = MeshtasticMqttProxy {}
            try {
                proxy.configure(broker.settings(), 1)
                val old = broker.peer()
                old.subscriptions()
                proxy.configure(broker.settings().copy(subscriptions = listOf("other/2/e/PKI/+")), 1)
                val replacement = broker.peer()
                assertEquals(listOf("other/2/e/PKI/+"), replacement.subscriptions())
                assertTrue(old.closed.poll(5, TimeUnit.SECONDS) == true)
                assertNotEquals(old.clientId, replacement.clientId)
            } finally {
                proxy.stop()
            }
        }
    }

    private fun envelope(): ByteArray = ServiceEnvelope(
        packet = MeshPacket(from = 2, encrypted = byteArrayOf(1, -1, 0).toByteString()),
        channel_id = "Test", gateway_id = "!00000002",
    ).encode()

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition())
    }

    private class Broker(connectCodes: List<Int> = emptyList()) : AutoCloseable {
        private val server = ServerSocket(0, 10, java.net.InetAddress.getLoopbackAddress())
        private val peers = LinkedBlockingQueue<Peer>()
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val connackCodes = LinkedBlockingQueue<Int>().apply { addAll(connectCodes) }
        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    sockets.add(socket)
                    val peer = Peer(socket, connackCodes.poll() ?: 0)
                    peers.add(peer)
                    thread(isDaemon = true) { peer.serve() }
                }
            }
        }
        fun settings() = MeshtasticMqttSettings(
            "tcp://localhost:${server.localPort}", "user", "secret",
            listOf("test/2/e/Test/+", "test/2/json/Test/+", "test/2/e/PKI/+"),
        )
        fun peer(): Peer = requireNotNull(peers.poll(8, TimeUnit.SECONDS)) { "Proxy did not connect" }
        fun noNewPeer(): Boolean = peers.poll(1500, TimeUnit.MILLISECONDS) == null
        override fun close() {
            server.close()
            sockets.forEach { runCatching { it.close() } }
        }
    }

    private data class Published(val topic: String, val payload: ByteArray, val retained: Boolean)

    private class Peer(private val socket: Socket, private val connackCode: Int) : AutoCloseable {
        private val input = DataInputStream(socket.getInputStream())
        private val output = socket.getOutputStream()
        private val subscribed = LinkedBlockingQueue<List<String>>()
        val published = LinkedBlockingQueue<Published>()
        val closed = LinkedBlockingQueue<Boolean>()
        var clientId = ""
        var username = ""
        var password = ""
        fun subscriptions(): List<String> = requireNotNull(subscribed.poll(5, TimeUnit.SECONDS))

        fun serve() {
            try {
                while (!socket.isClosed) {
                    val header = input.readUnsignedByte()
                    var length = 0
                    var factor = 1
                    do {
                        val digit = input.readUnsignedByte()
                        length += (digit and 127) * factor
                        factor *= 128
                    } while (digit and 128 != 0)
                    val body = ByteArray(length).also(input::readFully)
                    val data = DataInputStream(ByteArrayInputStream(body))
                    when (header ushr 4) {
                        1 -> {
                            data.readUTF() // protocol name
                            data.readUnsignedByte() // protocol version
                            val flags = data.readUnsignedByte()
                            data.readUnsignedShort() // keepalive
                            clientId = data.readUTF()
                            if (flags and 128 != 0) username = data.readUTF()
                            if (flags and 64 != 0) password = data.readUTF()
                            write(0x20, byteArrayOf(0, connackCode.toByte()))
                            if (connackCode != 0) return
                        }
                        8 -> {
                            val id = data.readUnsignedShort()
                            val topics = mutableListOf<String>()
                            while (data.available() > 0) {
                                topics.add(data.readUTF())
                                data.readUnsignedByte()
                            }
                            write(0x90, byteArrayOf((id shr 8).toByte(), id.toByte()) + ByteArray(topics.size) { 1 })
                            subscribed.add(topics)
                        }
                        3 -> {
                            val topic = data.readUTF()
                            val qos = (header shr 1) and 3
                            val id = if (qos > 0) data.readUnsignedShort() else 0
                            published.add(Published(topic, data.readBytes(), header and 1 != 0))
                            if (qos == 1) write(0x40, byteArrayOf((id shr 8).toByte(), id.toByte()))
                        }
                        12 -> write(0xd0, byteArrayOf())
                        14 -> return
                    }
                }
            } catch (_: java.io.IOException) {
                // Closing a broker connection is part of the reconnect/teardown tests.
            } finally {
                closed.add(true)
                close()
            }
        }

        fun publish(topic: String, bytes: ByteArray, retained: Boolean = false) {
            val body = ByteArrayOutputStream()
            DataOutputStream(body).apply { writeUTF(topic); write(bytes) }
            write(if (retained) 0x31 else 0x30, body.toByteArray())
        }

        @Synchronized private fun write(header: Int, body: ByteArray) {
            output.write(header)
            var length = body.size
            do {
                val digit = length % 128
                length /= 128
                output.write(if (length > 0) digit or 128 else digit)
            } while (length > 0)
            output.write(body)
            output.flush()
        }
        override fun close() = socket.close()
    }
}
