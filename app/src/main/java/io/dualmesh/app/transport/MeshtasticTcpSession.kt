package io.dualmesh.app.transport

import io.dualmesh.app.protocol.MeshtasticStreamCodec
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

class MeshtasticTcpSession(
    private val listener: Listener,
) {
    interface Listener {
        fun onTcpConnected()
        fun onTcpPacket(value: ByteArray)
        fun onTcpDisconnected()
        fun onTcpError(message: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Any()
    private var connectionJob: Job? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var generation = 0L

    fun connect(endpoint: Endpoint) {
        disconnect()
        val connectionGeneration = ++generation
        connectionJob = scope.launch {
            val activeSocket = Socket()
            socket = activeSocket
            val codec = MeshtasticStreamCodec(listener::onTcpPacket)
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
                    activeSocket.getOutputStream().apply {
                        write(MeshtasticStreamCodec.WAKE_BYTES)
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
                    codec.accept(buffer, count)
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

    fun send(payload: ByteArray): Boolean {
        val activeSocket = socket?.takeIf { it.isConnected && !it.isClosed } ?: return false
        val sendGeneration = generation
        val frame = runCatching { MeshtasticStreamCodec.frame(payload) }.getOrNull() ?: return false
        scope.launch {
            runCatching {
                synchronized(writeLock) {
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
        }
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
            if (port == MeshtasticStreamCodec.DEFAULT_PORT) host else "$host:$port"

        companion object {
            fun parse(value: String): Result<Endpoint> = runCatching {
                val input = value.trim()
                require(input.isNotEmpty()) { "Enter a Meshtastic hostname or IP address" }
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
                        MeshtasticStreamCodec.DEFAULT_PORT
                    }
                } else if (input.count { it == ':' } == 1) {
                    host = input.substringBefore(':')
                    port = input.substringAfter(':').toInt()
                } else {
                    host = input
                    port = MeshtasticStreamCodec.DEFAULT_PORT
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
