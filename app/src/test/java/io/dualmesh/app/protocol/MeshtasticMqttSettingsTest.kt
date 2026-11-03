package io.dualmesh.app.protocol

import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.ServiceEnvelope
import org.meshtastic.proto.ToRadio

class MeshtasticMqttSettingsTest {
    @Test fun `default broker uses firmware credentials and TLS`() {
        val settings = MeshtasticMqttSettings.from(ModuleConfig.MQTTConfig(), emptyList(), null)
        assertEquals("ssl://mqtt.meshtastic.org:8883", settings.serverUri)
        assertEquals("meshdev", settings.username)
        assertEquals("large4cats", settings.password)
        assertEquals(listOf("msh/2/e/PKI/+"), settings.subscriptions)
        assertFalse(settings.toString().contains("large4cats"))
    }

    @Test fun `custom broker supports TLS ports IPv6 and websocket paths`() {
        assertEquals("tcp://broker.example:1883", MeshtasticMqttSettings.brokerUri("broker.example", false))
        assertEquals("ssl://broker.example:8884", MeshtasticMqttSettings.brokerUri("broker.example:8884", true))
        assertEquals("tcp://[::1]:1884", MeshtasticMqttSettings.brokerUri("[::1]:1884", false))
        assertEquals("wss://broker.example:443/mqtt", MeshtasticMqttSettings.brokerUri("wss://broker.example/mqtt", false))
        assertEquals("ssl://broker.example:8883", MeshtasticMqttSettings.brokerUri("mqtts://broker.example", false))
        assertEquals("tcp://broker.example:1883", MeshtasticMqttSettings.brokerUri("tcp://broker.example", true))
    }

    @Test fun `invalid broker settings are rejected`() {
        listOf("https://broker.example", "broker.example:0", "broker.example:65536", "tcp://user:secret@host", "host/path")
            .forEach { address ->
                assertTrue(address, runCatching { MeshtasticMqttSettings.brokerUri(address, false) }.isFailure)
            }
        assertTrue(runCatching {
            MeshtasticMqttSettings.from(ModuleConfig.MQTTConfig(root = "msh/#"), emptyList(), null)
        }.isFailure)
    }

    @Test fun `subscribe only downlink channels plus PKI with exact firmware names`() {
        val channels = listOf(
            Channel(index = 0, role = Channel.Role.PRIMARY, settings = ChannelSettings(downlink_enabled = true)),
            Channel(index = 1, role = Channel.Role.SECONDARY, settings = ChannelSettings(name = "Private", downlink_enabled = true)),
            Channel(index = 2, role = Channel.Role.SECONDARY, settings = ChannelSettings(name = "UplinkOnly", uplink_enabled = true)),
            Channel(index = 3, role = Channel.Role.DISABLED, settings = ChannelSettings(name = "Disabled", downlink_enabled = true)),
        )
        @Suppress("DEPRECATION")
        val settings = MeshtasticMqttSettings.from(
            ModuleConfig.MQTTConfig(address = "broker.example", username = "user", password = "secret", root = "msh/US/", json_enabled = true),
            channels,
            Config.LoRaConfig(use_preset = true, modem_preset = Config.LoRaConfig.ModemPreset.LONG_MODERATE),
        )
        assertEquals("user", settings.username)
        assertEquals("secret", settings.password)
        assertEquals(listOf(
            "msh/US/2/e/LongMod/+", "msh/US/2/json/LongMod/+",
            "msh/US/2/e/Private/+", "msh/US/2/json/Private/+", "msh/US/2/e/PKI/+",
        ), settings.subscriptions)
    }

    @Test fun `custom modem and very long slow use firmware topic names`() {
        val channels = listOf(Channel(role = Channel.Role.PRIMARY, settings = ChannelSettings(downlink_enabled = true)))
        val config = ModuleConfig.MQTTConfig()
        assertTrue(MeshtasticMqttSettings.from(config, channels, Config.LoRaConfig(use_preset = false))
            .subscriptions.contains("msh/2/e/Custom/+"))
        assertTrue(MeshtasticMqttSettings.from(config, channels, Config.LoRaConfig(
            use_preset = true, modem_preset = Config.LoRaConfig.ModemPreset.VERY_LONG_SLOW,
        )).subscriptions.contains("msh/2/e/VLongSlow/+"))
    }

    private fun envelope(from: Int, gateway: String, payload: Boolean = true, channel: String = "LongFast"): ByteArray =
        ServiceEnvelope(
            packet = MeshPacket(from = from, encrypted = if (payload) byteArrayOf(0, -1, 12).toByteString() else null),
            gateway_id = gateway,
            channel_id = channel,
        ).encode()

    @Test fun `binary downlink preserves envelope retain and implicit acknowledgements`() {
        val bytes = envelope(2, "!00000002")
        val message = requireNotNull(mqttDownlink("msh/2/e/LongFast/!00000002", bytes, true, 1))
        val decoded = ToRadio.ADAPTER.decode(ToRadio(mqttClientProxyMessage = message).encode()).mqttClientProxyMessage!!
        assertArrayEquals(bytes, decoded.data_!!.toByteArray())
        assertTrue(decoded.retained)
        assertNull(decoded.text)
        assertNotNull(mqttDownlink("topic", envelope(1, "!00000001"), false, 1))
        assertNull(mqttDownlink("topic", envelope(1, "!00000002"), false, 1))
        assertNull(mqttDownlink("topic", envelope(2, "!00000001"), false, 1))
        assertNull(mqttDownlink("topic", envelope(2, "!00000002", payload = false), false, 1))
        assertNotNull(mqttDownlink("topic", envelope(2, "!00000002", payload = false, channel = "PKI"), false, 1))
    }

    @Test fun `JSON downlink uses text and filters self traffic`() {
        val json = """{"from":2,"sender":"!00000002","payload":{"text":"hello"}}"""
        val message = requireNotNull(mqttDownlink("msh/2/json/LongFast/!00000002", json.encodeToByteArray(), true, 1))
        assertEquals(json, message.text)
        assertNull(message.data_)
        assertTrue(message.retained)
        assertNull(mqttDownlink("msh/2/json/x/y", "not JSON".encodeToByteArray(), false, 1))
        assertNull(mqttDownlink("msh/2/json/x/y", """{"from":1,"sender":"!00000002"}""".encodeToByteArray(), false, 1))
    }

    @Test fun `radio limit includes topic and protobuf overhead`() {
        assertNull(mqttDownlink("topic", ByteArray(513), false, 1))
        assertNull(mqttDownlink("msh/2/e/LongFast/!00000002", ByteArray(500), false, 1))
    }
}
