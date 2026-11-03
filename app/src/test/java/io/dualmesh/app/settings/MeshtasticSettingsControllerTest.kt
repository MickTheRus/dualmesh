package io.dualmesh.app.settings

import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import org.meshtastic.proto.*

class MeshtasticSettingsControllerTest {
    @Test fun `reopening reuses cached settings while explicit refresh and reconnect read again`() {
        val radio = Radio()
        radio.controller.ensureSettingsLoaded().getOrThrow()
        val pending = radio.outgoing.size
        radio.controller.ensureSettingsLoaded().getOrThrow()
        assertEquals(pending, radio.outgoing.size)
        radio.drain()
        val cached = radio.controller.radioSettings.value
        radio.controller.ensureSettingsLoaded().getOrThrow()
        assertTrue(radio.outgoing.isEmpty())
        assertEquals(cached, radio.controller.radioSettings.value)
        radio.controller.refreshSettings().getOrThrow()
        assertTrue(radio.outgoing.isNotEmpty())
        radio.drain()
        radio.controller.reset()
        assertFalse(radio.controller.radioSettings.value.loadRequested)
        radio.controller.ensureSettingsLoaded().getOrThrow()
        assertTrue(radio.outgoing.isNotEmpty())
    }

    @Test fun `reopening after a failed initial read retains the error without retrying everything`() {
        val radio = Radio()
        radio.controller.ensureSettingsLoaded().getOrThrow()
        radio.outgoing.clear()
        radio.timer.timeout()
        val cached = radio.controller.radioSettings.value
        assertNotNull(cached.error)
        radio.controller.ensureSettingsLoaded().getOrThrow()
        assertTrue(radio.outgoing.isEmpty())
        assertEquals(cached, radio.controller.radioSettings.value)
    }
    private class Radio {
        val timer = ManualSettingsScheduler()
        val outgoing = ArrayDeque<MeshPacket>()
        var mqtt = ModuleConfig.MQTTConfig(enabled = true, password = "keep-secret", root = "msh/test")
        var owner = User(long_name = "Test", short_name = "TEST")
        var rejectWrite = false
        var lieOnReadback = false
        val key = byteArrayOf(1, 2, 3, 4).toByteString()
        val writes = mutableListOf<AdminMessage>()
        val controller: MeshtasticSettingsController
        init {
            lateinit var current: MeshtasticSettingsController
            current = MeshtasticSettingsController(
                send = { bytes -> outgoing.addLast(ToRadio.ADAPTER.decode(bytes).packet!!); true },
                readyNode = { 1 }, scheduler = timer, onReadback = { current.observe(it) },
            )
            controller = current
            controller.observe(FromRadio(moduleConfig = ModuleConfig(mqtt = mqtt)))
        }
        fun response(request: MeshPacket, admin: AdminMessage, from: Int = 1, requestId: Int = request.id): MeshPacket = MeshPacket(
            from = from, to = 1, decoded = Data(portnum = PortNum.ADMIN_APP, payload = admin.encode().toByteString(), request_id = requestId),
        )
        fun answer(): MeshPacket {
            val packet = outgoing.removeFirst()
            val admin = AdminMessage.ADAPTER.decode(packet.decoded!!.payload)
            assertEquals(1, packet.to)
            assertEquals(0, packet.from)
            assertEquals(0, packet.hop_limit)
            val reply = when {
                admin.get_owner_request == true -> AdminMessage(get_owner_response = owner, session_passkey = key)
                admin.get_module_config_request != null -> AdminMessage(get_module_config_response = ModuleConfig(mqtt = mqtt), session_passkey = key)
                else -> {
                    assertEquals(key, admin.session_passkey)
                    writes.add(admin)
                    if (!rejectWrite && !lieOnReadback) {
                        admin.set_module_config?.mqtt?.let { mqtt = it }
                        admin.set_owner?.let { owner = it }
                    }
                    val reason = if (rejectWrite) Routing.Error.NOT_AUTHORIZED else Routing.Error.NONE
                    controller.receive(MeshPacket(from = 1, decoded = Data(
                        portnum = PortNum.ROUTING_APP, request_id = packet.id,
                        payload = Routing(error_reason = reason).encode().toByteString(),
                    )))
                    return packet
                }
            }
            controller.receive(response(packet, reply))
            return packet
        }
        fun drain() { var count = 0; while (outgoing.isNotEmpty()) { check(count++ < 30); answer() } }
        fun refresh() { controller.refreshSettings().getOrThrow(); drain() }
    }

    @Test fun `MQTT save obtains session key preserves credentials and confirms readback`() {
        val radio = Radio()
        radio.refresh()
        val revision = radio.controller.radioSettings.value.revision
        radio.controller.saveSettings("module:mqtt", mapOf("proxy_to_client_enabled" to "true"), revision).getOrThrow()
        radio.drain()
        assertTrue(radio.mqtt.proxy_to_client_enabled)
        assertEquals("keep-secret", radio.mqtt.password)
        assertEquals("msh/test", radio.mqtt.root)
        assertEquals(1, radio.writes.size)
        assertEquals("Saved and verified on the radio.", radio.controller.radioSettings.value.status)
        assertNull(radio.controller.radioSettings.value.error)
    }

    @Test fun `spoofed or uncorrelated admin responses cannot seed a key or complete a request`() {
        val radio = Radio()
        radio.controller.refreshSettings().getOrThrow()
        val request = radio.outgoing.first()
        val bogus = AdminMessage(get_owner_response = radio.owner, session_passkey = byteArrayOf(9).toByteString())
        assertFalse(radio.controller.receive(radio.response(request, bogus, from = 2)))
        assertFalse(radio.controller.receive(radio.response(request, bogus, requestId = request.id + 1)))
        assertTrue(radio.controller.radioSettings.value.busy)
        radio.drain()
        radio.controller.saveSettings("owner", mapOf("short_name" to "NEW"), radio.controller.radioSettings.value.revision).getOrThrow()
        radio.drain()
        assertEquals(radio.key, radio.writes.single().session_passkey)
    }

    @Test fun `rejections readback mismatches and timeouts never claim a successful save`() {
        for (mode in listOf("reject", "mismatch", "timeout")) {
            val radio = Radio()
            radio.refresh()
            radio.rejectWrite = mode == "reject"
            radio.lieOnReadback = mode == "mismatch"
            radio.controller.saveSettings("module:mqtt", mapOf("proxy_to_client_enabled" to "true"), radio.controller.radioSettings.value.revision).getOrThrow()
            if (mode == "timeout") {
                radio.answer(); radio.answer() // Fresh owner and module reads, then write is pending.
                radio.timer.timeout()
            } else radio.drain()
            assertNotNull(mode, radio.controller.radioSettings.value.error)
            assertFalse(radio.controller.radioSettings.value.status.startsWith("Saved and verified"))
            assertFalse(radio.controller.radioSettings.value.busy)
        }
    }

    @Test fun `stale drafts and invalid edits cannot transmit settings writes`() {
        val radio = Radio()
        radio.refresh()
        val state = radio.controller.radioSettings.value
        assertTrue(radio.controller.saveSettings("owner", mapOf("short_name" to "TOOLONG"), state.revision).isFailure)
        assertTrue(radio.controller.saveSettings("owner", mapOf("short_name" to "NEW"), state.revision - 1).isFailure)
        assertTrue(radio.outgoing.isEmpty())
        val revision = radio.controller.radioSettings.value.revision
        radio.controller.observe(FromRadio(packet = MeshPacket(from = 2)))
        assertEquals(revision, radio.controller.radioSettings.value.revision)
    }
}
