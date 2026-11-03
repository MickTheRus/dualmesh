package io.dualmesh.app.protocol

import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.meshtastic.proto.Channel
import org.meshtastic.proto.Config
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.MqttClientProxyMessage
import org.meshtastic.proto.ServiceEnvelope
import org.meshtastic.proto.ToRadio
import java.net.URI

/** Broker configuration belongs to one radio and is kept only for that connection. */
data class MeshtasticMqttSettings(
    val serverUri: String,
    val username: String,
    val password: String,
    val subscriptions: List<String>,
) {
    override fun toString(): String = "MeshtasticMqttSettings(<redacted>)"

    companion object {
        fun from(
            config: ModuleConfig.MQTTConfig,
            channels: Collection<Channel>,
            lora: Config.LoRaConfig?,
        ): MeshtasticMqttSettings {
            val defaultBroker = config.address.isBlank()
            val root = config.root.ifEmpty { "msh" }.trimEnd('/')
            require(root.isNotEmpty() && root.none { it == '+' || it == '#' || it == '\u0000' }) {
                "Invalid MQTT root topic"
            }
            val names = channels.filter { it.role != Channel.Role.DISABLED }
                .mapNotNull { it.settings }
                .filter { it.downlink_enabled }
                .map { it.name.ifEmpty { modemName(lora) } }
            require(names.none { name -> name.any { it == '/' || it == '+' || it == '#' || it == '\u0000' } }) {
                "Invalid MQTT channel name"
            }
            val topics = buildList {
                names.forEach { name ->
                    add("$root/2/e/$name/+")
                    @Suppress("DEPRECATION")
                    if (config.json_enabled) add("$root/2/json/$name/+")
                }
                // Meshtastic also receives encrypted direct messages on the PKI channel.
                add("$root/2/e/PKI/+")
            }.distinct()
            return MeshtasticMqttSettings(
                serverUri = brokerUri(config.address, config.tls_enabled),
                username = if (defaultBroker) "meshdev" else config.username,
                password = if (defaultBroker) "large4cats" else config.password,
                subscriptions = topics,
            )
        }

        internal fun brokerUri(address: String, tls: Boolean): String {
            val value = address.trim().ifEmpty { "mqtt.meshtastic.org" }
            val explicitScheme = value.contains("://")
            val parsed = URI(if (explicitScheme) value else "tcp://$value")
            val host = requireNotNull(parsed.host) { "Invalid MQTT broker address" }
            require(parsed.rawUserInfo == null && parsed.rawQuery == null && parsed.rawFragment == null) {
                "Enter MQTT credentials separately from the broker address"
            }
            val secure = if (explicitScheme) parsed.scheme in setOf("ssl", "mqtts", "wss")
                else tls || host.equals("mqtt.meshtastic.org", ignoreCase = true)
            val scheme = when (parsed.scheme) {
                "tcp", "mqtt", "ssl", "mqtts" -> if (secure) "ssl" else "tcp"
                "ws", "wss" -> parsed.scheme
                else -> throw IllegalArgumentException("Unsupported MQTT broker scheme")
            }
            val websocket = scheme == "ws" || scheme == "wss"
            require(websocket || parsed.path.isNullOrEmpty() || parsed.path == "/") {
                "MQTT TCP addresses cannot include a path"
            }
            val port = if (parsed.port == -1) {
                if (websocket) { if (secure) 443 else 80 } else { if (secure) 8883 else 1883 }
            } else parsed.port
            require(port in 1..65535) { "Invalid MQTT broker port" }
            return URI(scheme, null, host, port, if (websocket) parsed.path else null, null, null).toString()
        }

        // These names must match firmware MQTT topics, including the abbreviated presets.
        private fun modemName(lora: Config.LoRaConfig?): String {
            if (lora?.use_preset == false) return "Custom"
            return when (lora?.modem_preset ?: Config.LoRaConfig.ModemPreset.LONG_FAST) {
                Config.LoRaConfig.ModemPreset.LONG_FAST -> "LongFast"
                Config.LoRaConfig.ModemPreset.LONG_SLOW -> "LongSlow"
                Config.LoRaConfig.ModemPreset.VERY_LONG_SLOW -> "VLongSlow"
                Config.LoRaConfig.ModemPreset.MEDIUM_SLOW -> "MediumSlow"
                Config.LoRaConfig.ModemPreset.MEDIUM_FAST -> "MediumFast"
                Config.LoRaConfig.ModemPreset.SHORT_SLOW -> "ShortSlow"
                Config.LoRaConfig.ModemPreset.SHORT_FAST -> "ShortFast"
                Config.LoRaConfig.ModemPreset.LONG_MODERATE -> "LongMod"
                Config.LoRaConfig.ModemPreset.SHORT_TURBO -> "ShortTurbo"
                Config.LoRaConfig.ModemPreset.LONG_TURBO -> "LongTurbo"
                Config.LoRaConfig.ModemPreset.LITE_FAST -> "LiteFast"
                Config.LoRaConfig.ModemPreset.LITE_SLOW -> "LiteSlow"
                Config.LoRaConfig.ModemPreset.NARROW_FAST -> "NarrowFast"
                Config.LoRaConfig.ModemPreset.NARROW_SLOW -> "NarrowSlow"
                Config.LoRaConfig.ModemPreset.TINY_FAST -> "TinyFast"
                Config.LoRaConfig.ModemPreset.TINY_SLOW -> "TinySlow"
                Config.LoRaConfig.ModemPreset.MEDIUM_TURBO -> "MediumTurbo"
            }
        }
    }
}

/** Preserve firmware envelopes; never turn MQTT packets into locally synthesized mesh messages. */
internal fun mqttDownlink(
    topic: String,
    payload: ByteArray,
    retained: Boolean,
    nodeNum: Int,
): MqttClientProxyMessage? {
    if (payload.size > 512) return null
    val myId = "!${nodeNum.toUInt().toString(16).padStart(8, '0')}"
    val message = if (topic.contains("/json/")) {
        val text = runCatching { payload.decodeToString(throwOnInvalidSequence = true) }.getOrNull() ?: return null
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (json.has("from") && json.has("sender")) {
            val fromUs = json.optLong("from").toInt() == nodeNum
            val viaUs = json.optString("sender") == myId
            if (fromUs != viaUs) return null
        }
        MqttClientProxyMessage(topic = topic, text = text, retained = retained)
    } else {
        val envelope = runCatching { ServiceEnvelope.ADAPTER.decode(payload) }.getOrNull()
        envelope?.packet?.let { packet ->
            val fromUs = packet.from == nodeNum
            val viaUs = envelope.gateway_id == myId
            // Our own origin returning through our gateway is an implicit ACK; keep it.
            if (fromUs != viaUs) return null
            if (packet.decoded == null && packet.encrypted == null &&
                envelope.channel_id != "PKI" && !viaUs
            ) return null
        }
        MqttClientProxyMessage(topic = topic, data_ = payload.toByteString(), retained = retained)
    }
    // Include the topic and protobuf framing in the radio API's 512-byte limit.
    return message.takeIf { ToRadio(mqttClientProxyMessage = it).encode().size <= 512 }
}
