package io.dualmesh.app.settings

import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import org.meshtastic.proto.*

class ProtoSettingsTest {
    @Test fun `MQTT patch retains credentials nested settings and unknown firmware fields`() {
        val unknown = "a00607".decodeHex() // future field 100 = 7
        val mqtt = ModuleConfig.MQTTConfig(
            enabled = true, address = "broker.example", username = "test", password = "secret",
            root = "msh/test", map_report_settings = ModuleConfig.MapReportSettings(publish_interval_secs = 600),
            unknownFields = unknown,
        )
        val updated = ProtoSettings.patch(mqtt, mapOf("proxy_to_client_enabled" to "true")) as ModuleConfig.MQTTConfig
        assertTrue(updated.proxy_to_client_enabled)
        assertEquals("secret", updated.password)
        assertEquals(mqtt.map_report_settings, updated.map_report_settings)
        assertEquals(unknown, updated.unknownFields)
        assertEquals(mqtt.copy(proxy_to_client_enabled = true), updated)
    }

    @Test fun `nested optional fields can be created while siblings remain unchanged`() {
        val config = ModuleConfig(mqtt = ModuleConfig.MQTTConfig(password = "keep"))
        val updated = ProtoSettings.patch(config, mapOf("mqtt.map_report_settings.publish_interval_secs" to "900")) as ModuleConfig
        assertEquals(900, updated.mqtt!!.map_report_settings!!.publish_interval_secs)
        assertEquals("keep", updated.mqtt!!.password)
    }

    @Test fun `unsigned enum bool bytes and repeated fields round trip through schema editor`() {
        val lora = Config.LoRaConfig(region = Config.LoRaConfig.RegionCode.US, hop_limit = 3)
        val fields = ProtoSettings.fields(lora).associateBy { it.id }
        assertEquals(SettingKind.CHOICE, fields.getValue("region").kind)
        assertTrue("US" in fields.getValue("region").choices)
        val updated = ProtoSettings.patch(lora, mapOf("hop_limit" to "7", "use_preset" to "true", "region" to "EU_868")) as Config.LoRaConfig
        assertEquals(7, updated.hop_limit)
        assertEquals(Config.LoRaConfig.RegionCode.EU_868, updated.region)
        assertTrue(updated.use_preset)
        val channel = ChannelSettings(name = "Test", psk = byteArrayOf(1).toByteString())
        val key = ByteArray(16) { it.toByte() }.toByteString()
        assertEquals(key, (ProtoSettings.patch(channel, mapOf("psk" to key.base64())) as ChannelSettings).psk)
        val security = Config.SecurityConfig()
        val keys = listOf(ByteArray(32) { 1 }.toByteString(), ByteArray(32) { 2 }.toByteString())
        val secured = ProtoSettings.patch(security, mapOf("admin_key" to keys.joinToString("\n") { it.base64() })) as Config.SecurityConfig
        assertEquals(keys, secured.admin_key)
        assertTrue(ProtoSettings.fields(secured).first { it.id == "admin_key" }.help.startsWith("One value per line"))
    }

    @Test fun `invalid numbers enums keys fields and nonfinite values are rejected`() {
        val lora = Config.LoRaConfig()
        listOf(mapOf("hop_limit" to "-1"), mapOf("region" to "BOGUS"), mapOf("override_frequency" to "NaN"), mapOf("no_such_field" to "1"))
            .forEach { assertTrue(runCatching { ProtoSettings.patch(lora, it) }.isFailure) }
        assertTrue(runCatching { ProtoSettings.patch(ChannelSettings(), mapOf("psk" to "%%%")) }.isFailure)
    }

    @Test fun `changing one field preserves unknown wire encodings and duplicate unknown chunks`() {
        val unknown = "a00607a9060001020304050607b20603616263bd0601020304a00608".decodeHex()
        val message = ModuleConfig.MQTTConfig(enabled = true, unknownFields = unknown)
        val updated = ProtoSettings.patch(message, mapOf("enabled" to "false")) as ModuleConfig.MQTTConfig
        assertFalse(updated.enabled)
        assertEquals(unknown, updated.unknownFields)
    }
}
