package io.dualmesh.app.transport

import io.dualmesh.app.protocol.MeshtasticStreamCodec
import io.dualmesh.app.model.MeshNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class MeshtasticTcpSession(
    private val listener: Listener,
    private val network: MeshNetwork = MeshNetwork.MESHTASTIC,
) {
    interface Listener {
        fun onTcpConnected()
        fun onTcpPacket(value: ByteArray)
        fun onTcpDisconnected()
        fun onTcpError(message: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Any()
    private val pendingWrites = AtomicInteger()
    private val framing = RadioStreamFraming(network) {}
    private var connectionJob: Job? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var generation = 0L

    fun connect(endpoint: Endpoint) {
        disconnect()
        val connectionGeneration = ++generation
        connectionJob = scope.launch {
            val activeSocket = Socket()
            socket = activeSocket
            val codec = RadioStreamFraming(network, listener::onTcpPacket)
            var connected = false
            var failed = false
            try {
                activeSocket.tcpNoDelay = true
                activeSocket.keepAlive = true
                activeSocket.connect(
                    InetSocketAddress(endpoint.host, endpoint.port),
                    CONNECT_TIMEOUT_MS,
                )
                synchronized(writeLock) {
                    if (connectionGeneration != generation || activeSocket !== socket) return@launch
                    activeSocket.getOutputStream().apply {
                        write(codec.wakeBytes)
                        flush()
                    }
                }
                connected = true
                listener.onTcpConnected()

                val buffer = ByteArray(READ_BUFFER_SIZE)
                val input = activeSocket.getInputStream()
                while (isActive && connectionGeneration == generation) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (connectionGeneration == generation && socket === activeSocket) codec.accept(buffer, count)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (connectionGeneration == generation) {
                    failed = true
                    listener.onTcpError(
                        error.message?.takeIf(String::isNotBlank)
                            ?: "Meshtastic TCP connection failed",
                    )
                }
            } finally {
                runCatching { activeSocket.close() }
                .getOrNull()
                .also {
                    if (socket === activeSocket) socket = null
                }
                if (connected && !failed && connectionGeneration == generation) {
                    listener.onTcpDisconnected()
                }
            }
        }
    }

    fun send(payload: ByteArray, bounded: Boolean = false): Boolean {
        val activeSocket = socket?.takeIf { it.isConnected && !it.isClosed } ?: return false
        val sendGeneration = generation
        val frame = runCatching { framing.frame(payload) }.getOrNull() ?: return false
        if (pendingWrites.incrementAndGet() > 32 && bounded) {
            pendingWrites.decrementAndGet()
            return false
        }
        scope.launch {
            runCatching {
                synchronized(writeLock) {
                    if (sendGeneration != generation || activeSocket !== socket) return@synchronized
                    activeSocket.getOutputStream().apply {
                        write(frame)
                        flush()
                    }
                }
            }.onFailure {
                if (sendGeneration == generation && !activeSocket.isClosed) {
                    listener.onTcpError("Meshtastic TCP write failed")
                    disconnect()
                }
            }
        }.invokeOnCompletion { pendingWrites.decrementAndGet() }
        return true
    }

    fun disconnect() {
        generation++
        connectionJob?.cancel()
        connectionJob = null
        runCatching { socket?.close() }
        socket = null
    }

    fun close() {
        disconnect()
        scope.cancel()
    }

    data class Endpoint(val host: String, val port: Int) {
        override fun toString(): String =
            if (host.contains(':')) "[$host]:$port" else if (port == MeshtasticStreamCodec.DEFAULT_PORT) host else "$host:$port"

        companion object {
            fun parse(value: String, defaultPort: Int = MeshtasticStreamCodec.DEFAULT_PORT): Result<Endpoint> = runCatching {
                val input = value.trim()
                require(input.isNotEmpty()) { "Enter a hostname or IP address" }
                require(!input.contains("://") && input.none(Char::isWhitespace)) { "Use a hostname or IP address, without a URL scheme or spaces" }
                val host: String
                val port: Int
                if (input.startsWith("[")) {
                    val close = input.indexOf(']')
                    require(close > 1) { "Invalid IPv6 address" }
                    host = input.substring(1, close)
                    port = if (close + 1 < input.length) {
                        require(input[close + 1] == ':') { "Invalid TCP endpoint" }
                        input.substring(close + 2).toInt()
                    } else {
                        defaultPort
                    }
                } else if (input.count { it == ':' } == 1) {
                    host = input.substringBefore(':')
                    port = input.substringAfter(':').toInt()
                } else {
                    host = input
                    port = defaultPort
                }
                require(host.isNotBlank()) { "Hostname cannot be empty" }
                require(port in 1..65535) { "TCP port must be between 1 and 65535" }
                Endpoint(host, port)
            }
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_BUFFER_SIZE = 1024
    }
}
