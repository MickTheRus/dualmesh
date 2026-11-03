package io.dualmesh.app.transport

import io.dualmesh.app.protocol.MeshtasticMqttSettings
import io.dualmesh.app.protocol.mqttDownlink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.meshtastic.proto.MqttClientProxyMessage
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One broker session per radio. MQTT failures do not disconnect the radio. */
class MeshtasticMqttProxy(
    private val dispatchToRadio: (() -> Unit) -> Unit = { it() },
    private val sendToRadio: (MqttClientProxyMessage) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var active: Session? = null
    private val pendingDownlinks = AtomicInteger()
    private val _status = MutableStateFlow("MQTT proxy off")
    val status: StateFlow<String> = _status.asStateFlow()

    private class Session(val settings: MeshtasticMqttSettings, val nodeNum: Int) {
        val clientId = "DualMesh-${UUID.randomUUID()}"
        var job: Job? = null
        @Volatile var connection: Connection? = null
    }

    private class Connection(val client: MqttAsyncClient) {
        val closed = AtomicBoolean(false)
        val disconnected = CompletableDeferred<Unit>()
    }

    fun configure(settings: MeshtasticMqttSettings, nodeNum: Int) = synchronized(lock) {
        val previous = active
        if (previous?.settings == settings && previous.nodeNum == nodeNum) return@synchronized
        stop()
        val session = Session(settings, nodeNum)
        active = session
        _status.value = "MQTT proxy connecting"
        session.job = scope.launch { runSession(session) }
    }

    fun stop(status: String = "MQTT proxy off") = synchronized(lock) {
        val previous = active
        active = null
        _status.value = status
        previous?.job?.cancel()
        previous?.connection?.let { connection -> scope.launch { dispose(connection) } }
    }

    fun publish(message: MqttClientProxyMessage) = synchronized(lock) {
        val session = active ?: return@synchronized
        val connection = session.connection ?: return@synchronized
        if (!connection.client.isConnected) return@synchronized
        val payload = message.data_?.toByteArray() ?: message.text?.encodeToByteArray() ?: return@synchronized
        // A firmware publish request already reflects the radio's uplink settings.
        runCatching {
            connection.client.publish(message.topic, payload, 1, message.retained)
        }.onFailure {
            updateStatus(session, "MQTT proxy publish failed")
        }
    }

    private suspend fun runSession(session: Session) {
        var retryDelay = 1_000L
        while (current(session)) {
            var connection: Connection? = null
            try {
                updateStatus(session, "MQTT proxy connecting")
                val client = MqttAsyncClient(session.settings.serverUri, session.clientId, MemoryPersistence())
                connection = Connection(client)
                val attempt = connection
                synchronized(lock) {
                    if (active === session) session.connection = attempt
                }
                client.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        attempt.disconnected.complete(Unit)
                    }

                    override fun messageArrived(topic: String, message: MqttMessage) {
                        val downlink = mqttDownlink(topic, message.payload, message.isRetained, session.nodeNum)
                            ?: return
                        if (pendingDownlinks.incrementAndGet() > 32) {
                            pendingDownlinks.decrementAndGet()
                            return
                        }
                        dispatchToRadio {
                            try {
                                synchronized(lock) {
                                    if (active === session && session.connection === attempt && !attempt.closed.get()) {
                                        sendToRadio(downlink)
                                    }
                                }
                            } finally {
                                pendingDownlinks.decrementAndGet()
                            }
                        }
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
                })
                if (!current(session)) return
                val options = MqttConnectOptions().apply {
                    mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
                    isCleanSession = true
                    isAutomaticReconnect = false
                    connectionTimeout = 10
                    keepAliveInterval = 30
                    maxInflight = 20
                    isHttpsHostnameVerificationEnabled = true
                    if (session.settings.username.isNotEmpty()) userName = session.settings.username
                    if (session.settings.password.isNotEmpty()) password = session.settings.password.toCharArray()
                }
                client.connect(options).waitForCompletion(15_000)
                if (!current(session)) return
                val topics = session.settings.subscriptions.toTypedArray()
                val subscribed = client.subscribe(topics, IntArray(topics.size) { 1 })
                try {
                    subscribed.waitForCompletion(15_000)
                } catch (error: MqttException) {
                    // SUBACK refusal is a broker policy decision; reconnecting cannot fix it.
                    if (error.reasonCode != 128) throw error
                }
                if (subscribed.grantedQos.any { it == 128 }) {
                    updateStatus(session, "MQTT proxy: broker refused a subscription")
                } else {
                    updateStatus(session, "MQTT proxy connected")
                }
                retryDelay = 1_000L
                attempt.disconnected.await()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (error is MqttException && error.reasonCode in setOf(4, 5)) {
                    updateStatus(session, "MQTT proxy: check broker credentials")
                    return
                }
            } finally {
                connection?.let { dispose(it) }
                synchronized(lock) {
                    if (session.connection === connection) session.connection = null
                }
            }
            if (!current(session)) return
            updateStatus(session, "MQTT proxy reconnecting")
            delay(retryDelay)
            retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
        }
    }

    private fun current(session: Session): Boolean = synchronized(lock) {
        active === session && session.job?.isActive != false
    }

    private fun updateStatus(session: Session, value: String) = synchronized(lock) {
        if (active === session) _status.value = value
    }

    private fun dispose(connection: Connection) {
        if (!connection.closed.compareAndSet(false, true)) return
        connection.disconnected.complete(Unit)
        runCatching { connection.client.disconnectForcibly(0, 0, false) }
        runCatching { connection.client.close(true) }
    }
}
