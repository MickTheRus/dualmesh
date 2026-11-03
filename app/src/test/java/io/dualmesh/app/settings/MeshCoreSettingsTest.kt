package io.dualmesh.app.settings

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun mcSelf(name: String = "Test radio"): ByteArray = ByteArray(58 + name.encodeToByteArray().size).apply {
    this[0] = 5; this[1] = 1; this[2] = 20; this[3] = 22
    ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(36, 33_123456); putInt(40, -112_123456)
        putInt(48, 915_075); putInt(52, 62_500)
    }
    this[44] = 1; this[45] = 1; this[46] = 0x15; this[47] = 1; this[56] = 7; this[57] = 5
    name.encodeToByteArray().copyInto(this, 58)
}
internal fun mcDevice(): ByteArray = ByteArray(82).apply {
    this[0] = 13; this[1] = 14; this[3] = 1
    ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 123456)
    this[80] = 1; this[81] = 2
}
internal fun mcChannel(): ByteArray = ByteArray(50).apply {
    this[0] = 18; this[1] = 0
    "Test".encodeToByteArray().copyInto(this, 2)
    (0 until 16).forEach { this[34 + it] = it.toByte() }
}

class MeshCoreSettingsTest {
    private fun codec() = MeshCoreSettingsCodec().apply { observe(mcSelf()); observe(mcDevice()); observe(mcChannel()) }

    @Test fun `self and device records produce correct identity RF location and secret fields`() {
        val sections = codec().sections().associateBy { it.id }
        fun value(section: String, id: String) = sections.getValue(section).fields.first { it.id == id }.value
        assertEquals("Test radio", value("identity", "name"))
        assertEquals("915.075", value("radio", "frequency_mhz"))
        assertEquals("62.5", value("radio", "bandwidth_khz"))
        assertEquals("true", value("radio", "client_repeat"))
        assertEquals("33.123456", value("location", "latitude"))
        assertEquals("123456", value("bluetooth", "pin"))
        assertEquals("000102030405060708090a0b0c0d0e0f", value("channel:0", "psk_hex"))
        assertEquals(SettingKind.SECRET, sections.getValue("channel:0").fields.last().kind)
    }

    @Test fun `radio edits preserve current repeat flag and unchanged RF values`() {
        val commands = codec().encode("radio", mapOf("spreading_factor" to "8"))
        assertEquals(1, commands.size)
        val frame = commands.single()
        assertEquals(11, frame[0].toInt())
        assertEquals(915075, ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).getInt(1))
        assertEquals(62500, ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).getInt(5))
        assertEquals(8, frame[9].toInt())
        assertEquals(1, frame[11].toInt())
        assertArrayEquals(byteArrayOf(12, 18), codec().encode("radio", mapOf("tx_power_dbm" to "18")).single())
    }

    @Test fun `privacy edits preserve unedited raw flags including future bits`() {
        val s = mcSelf().apply { this[46] = 0xdf.toByte(); this[44] = 2; this[47] = 3 }
        val codec = codec().apply { observe(s) }
        val frame = codec.encode("privacy", mapOf("advert_location" to "0")).single()
        assertArrayEquals(byteArrayOf(38, 3, 0xdf.toByte(), 0, 2), frame)
    }

    @Test fun `channel edits preserve key and encode fixed size fields without truncating names`() {
        val frame = codec().encode("channel:0", mapOf("name" to "Changed")).single()
        assertEquals(50, frame.size)
        assertArrayEquals(mcChannel().copyOfRange(34, 50), frame.copyOfRange(34, 50))
        assertTrue(runCatching { codec().encode("identity", mapOf("name" to "é".repeat(16))) }.isFailure)
        assertTrue(runCatching { codec().encode("channel:0", mapOf("psk_hex" to "abc")) }.isFailure)
    }

    @Test fun `invalid RF position PIN and unsigned settings fail before send`() {
        val codec = codec()
        listOf("frequency_mhz" to "NaN", "spreading_factor" to "13", "tx_power_dbm" to "23", "bandwidth_khz" to "99")
            .forEach { (key, value) -> assertTrue(runCatching { codec.encode("radio", mapOf(key to value)) }.isFailure) }
        assertTrue(runCatching { codec.encode("location", mapOf("latitude" to "91")) }.isFailure)
        assertTrue(runCatching { codec.encode("bluetooth", mapOf("pin" to "12345")) }.isFailure)
        assertTrue(runCatching { codec.encode("identity", mapOf("unknown" to "value")) }.isFailure)
    }

    @Test fun `controller serializes saves requires ack and verifies actual readback`() {
        val timer = ManualSettingsScheduler()
        val sent = ArrayDeque<ByteArray>()
        val controller = MeshCoreSettingsController({ sent.addLast(it); true }, { true }, { 1 }, timer, {})
        controller.observe(mcDevice()); controller.observe(mcSelf()); controller.observe(mcChannel())
        controller.refreshSettings().getOrThrow()
        fun answerReads(name: String) {
            var count = 0
            while (sent.isNotEmpty() && count++ < 20) {
                val command = sent.removeFirst()
                val reply = when (command[0].toInt()) {
                    22 -> mcDevice()
                    1 -> mcSelf(name)
                    31 -> mcChannel()
                    else -> byteArrayOf(1, 2)
                }
                assertTrue(controller.receive(reply))
            }
        }
        answerReads("Before")
        assertFalse(controller.radioSettings.value.busy)
        val cached = controller.radioSettings.value
        controller.ensureSettingsLoaded().getOrThrow()
        assertTrue(sent.isEmpty())
        assertEquals(cached, controller.radioSettings.value)
        controller.refreshSettings().getOrThrow()
        val pending = sent.size
        controller.ensureSettingsLoaded().getOrThrow()
        assertEquals(pending, sent.size)
        answerReads("Before")
        controller.saveSettings("identity", mapOf("name" to "After"), controller.radioSettings.value.revision).getOrThrow()
        assertEquals(1, sent.removeFirst()[0].toInt())
        controller.receive(mcSelf("Before"))
        assertEquals(8, sent.removeFirst()[0].toInt())
        assertFalse(controller.receive(byteArrayOf(9)))
        assertTrue(controller.radioSettings.value.busy)
        controller.receive(byteArrayOf(0))
        assertEquals(1, sent.removeFirst()[0].toInt())
        controller.receive(mcSelf("After"))
        assertEquals("Saved and verified on the radio.", controller.radioSettings.value.status)
        assertNull(controller.radioSettings.value.error)
        controller.saveSettings("identity", mapOf("name" to "Other"), controller.radioSettings.value.revision).getOrThrow()
        sent.removeFirst(); controller.receive(mcSelf("After")); sent.removeFirst()
        controller.receive(byteArrayOf(1, 6))
        assertNotNull(controller.radioSettings.value.error)
        assertTrue(sent.isEmpty())
        assertTrue(controller.saveSettings("identity", mapOf("name" to "Retry"), controller.radioSettings.value.revision).isFailure)
    }
}
