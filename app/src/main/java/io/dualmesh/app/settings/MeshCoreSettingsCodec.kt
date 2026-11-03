package io.dualmesh.app.settings

import io.dualmesh.app.protocol.MeshCoreProtocol
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Layouts checked against meshcore-open and companion_radio/MyMesh.cpp. */
internal class MeshCoreSettingsCodec {
    private var self: ByteArray? = null
    private var device: ByteArray? = null
    private var tuning: ByteArray? = null
    private var autoadd: ByteArray? = null
    private var custom: ByteArray? = null
    private val channels = sortedMapOf<Int, ByteArray>()
    fun clear() { self = null; device = null; tuning = null; autoadd = null; custom = null; channels.clear() }
    val firmware: Int get() = device?.getOrNull(1)?.toInt()?.and(255) ?: 0

    fun observe(frame: ByteArray): Boolean {
        when (frame.firstOrNull()?.toInt()?.and(255)) {
            5 -> { if (frame.size < 58) return false; self = frame.copyOf() }
            13 -> { if (frame.size < 8) return false; device = frame.copyOf() }
            18 -> { if (frame.size < 50) return false; channels[frame[1].toInt() and 255] = frame.copyOf() }
            23 -> { if (frame.size < 9) return false; tuning = frame.copyOf() }
            25 -> { if (frame.size < 2) return false; autoadd = frame.copyOf() }
            21 -> custom = frame.copyOf()
            else -> return false
        }
        return true
    }

    fun sections(): List<RadioSettingsSection> = buildList {
        self?.let { s ->
            add(section("identity", "Identity", listOf(field("name", string(s, 58), help = "1–31 UTF-8 bytes"))))
            add(section("radio", "Radio", buildList {
                add(field("frequency_mhz", (uint(s, 48) / 1000.0).toString(), SettingKind.NUMBER))
                add(field("bandwidth_khz", (uint(s, 52) / 1000.0).toString(), SettingKind.CHOICE,
                    listOf("7.8", "10.4", "15.6", "20.8", "31.25", "41.7", "62.5", "125.0", "250.0", "500.0")))
                add(field("spreading_factor", ubyte(s, 56).toString(), SettingKind.CHOICE, (5..12).map(Int::toString)))
                add(field("coding_rate", ubyte(s, 57).toString(), SettingKind.CHOICE, (5..8).map(Int::toString), "5 = 4/5, 6 = 4/6, 7 = 4/7, 8 = 4/8"))
                add(field("tx_power_dbm", s[2].toInt().toString(), SettingKind.NUMBER, help = "Maximum ${s[3].toInt()} dBm on this hardware"))
                device?.getOrNull(80)?.let { add(field("client_repeat", (it.toInt() != 0).toString(), SettingKind.BOOLEAN, help = "Requires a frequency supported for client repeat by the firmware.")) }
            }))
            add(section("location", "Location", listOf(
                field("latitude", (int(s, 36) / 1_000_000.0).toString(), SettingKind.NUMBER),
                field("longitude", (int(s, 40) / 1_000_000.0).toString(), SettingKind.NUMBER),
            )))
            add(section("privacy", "Privacy and telemetry", listOf(
                field("manual_add_contacts", (ubyte(s, 47) != 0).toString(), SettingKind.BOOLEAN),
                field("advert_location", ubyte(s, 45).toString(), SettingKind.CHOICE, listOf("0", "1", "2"), "0 hides location; 1 includes location in adverts."),
                field("multi_acks", (ubyte(s, 44) != 0).toString(), SettingKind.BOOLEAN),
                field("telemetry_base", (ubyte(s, 46) and 3).toString(), SettingKind.CHOICE, listOf("0", "1", "2"), "0 denies; 1 allows contacts; 2 allows all."),
                field("telemetry_location", (ubyte(s, 46) shr 2 and 3).toString(), SettingKind.CHOICE, listOf("0", "1", "2")),
                field("telemetry_environment", (ubyte(s, 46) shr 4 and 3).toString(), SettingKind.CHOICE, listOf("0", "1", "2")),
            )))
        }
        device?.let { d ->
            add(section("bluetooth", "Bluetooth PIN", listOf(field("pin", uint(d, 4).toString(), SettingKind.SECRET, help = "Six digits (100000–999999), or 0 to disable. Re-pairing may be needed."))))
            d.getOrNull(81)?.let { add(section("path", "Path hashes", listOf(field("hash_mode", (it.toInt() and 255).toString(), SettingKind.CHOICE, listOf("0", "1", "2"), "0 = 1 byte; 1 = 2 bytes; 2 = 3 bytes.")))) }
        }
        tuning?.let { t -> add(section("tuning", "Radio tuning", listOf(
            field("rx_delay", (uint(t, 1) / 1000.0).toString(), SettingKind.NUMBER),
            field("airtime_factor", (uint(t, 5) / 1000.0).toString(), SettingKind.NUMBER),
        ))) }
        autoadd?.let { a -> add(section("contacts", "Automatic contacts", buildList {
            AUTOADD.forEach { (name, bit) -> add(field(name, (ubyte(a, 1) and (1 shl bit) != 0).toString(), SettingKind.BOOLEAN)) }
            a.getOrNull(2)?.let { add(field("max_hops", (it.toInt() and 255).toString(), SettingKind.NUMBER, help = "0–64")) }
        })) }
        custom?.let { c ->
            val fields = string(c, 1).split(',').mapNotNull { entry ->
                val parts = entry.split(':', limit = 2)
                if (parts.size != 2 || parts[0].isBlank()) null else field(parts[0], parts[1],
                    if (parts[0] == "gps") SettingKind.CHOICE else SettingKind.TEXT,
                    if (parts[0] == "gps") listOf("0", "1") else emptyList(),
                    if (parts[0] == "gps_interval") "GPS interval in seconds (0–86400)" else "Firmware sensor setting",
                )
            }
            if (fields.isNotEmpty()) add(section("sensors", "GPS and sensors", fields))
        }
        channels.forEach { (index, c) -> add(section("channel:$index", "Channel $index", listOf(
            field("name", string(c, 2, 32), help = "At most 31 UTF-8 bytes; blank clears the channel name."),
            field("psk_hex", c.copyOfRange(34, 50).toByteString().hex(), SettingKind.SECRET, help = "Exactly 32 hex characters (16 bytes)"),
        ))) }
    }

    fun reads(id: String): List<Pair<ByteArray, Int>> = when {
        id.startsWith("channel:") -> listOf(MeshCoreProtocol.getChannel(id.substringAfter(':').toInt()) to 18)
        id == "tuning" -> listOf(byteArrayOf(43) to 23)
        id == "contacts" -> listOf(byteArrayOf(59) to 25)
        id == "sensors" -> listOf(byteArrayOf(40) to 21)
        id in setOf("bluetooth", "path") -> listOf(MeshCoreProtocol.deviceQuery() to 13)
        id == "radio" -> listOf(MeshCoreProtocol.deviceQuery() to 13, MeshCoreProtocol.appStart() to 5)
        else -> listOf(MeshCoreProtocol.appStart() to 5)
    }

    fun encode(id: String, changes: Map<String, String>): List<ByteArray> {
        val original = requireNotNull(sections().find { it.id == id }) { "Read this settings section first" }
        val values = original.fields.associate { it.id to it.value } + changes
        require(changes.keys.all { key -> original.fields.any { it.id == key } }) { "Unsupported setting" }
        fun value(key: String) = requireNotNull(values[key])
        fun number(key: String): Double = value(key).toDouble().also { require(it.isFinite()) { "Enter a finite number for $key" } }
        fun bool(key: String) = requireNotNull(value(key).toBooleanStrictOrNull()) { "Choose true or false for $key" }
        fun integer(key: String, range: IntRange) = value(key).toInt().also { require(it in range) { "$key must be ${range.first}–${range.last}" } }
        fun name(key: String, allowEmpty: Boolean = false): ByteArray = value(key).encodeToByteArray().also {
            require((allowEmpty || it.isNotEmpty()) && it.size <= 31 && 0.toByte() !in it) { "Name must be ${if (allowEmpty) "0" else "1"}–31 UTF-8 bytes" }
        }
        return when {
            id == "identity" -> listOf(byteArrayOf(8) + name("name"))
            id == "location" -> {
                val lat = number("latitude"); val lon = number("longitude")
                require(lat in -90.0..90.0 && lon in -180.0..180.0) { "Latitude must be ±90 and longitude ±180" }
                listOf(frame(14, (lat * 1_000_000).roundToInt(), (lon * 1_000_000).roundToInt()))
            }
            id == "radio" -> buildList {
                if (changes.keys.any { it != "tx_power_dbm" }) {
                    val freq = number("frequency_mhz")
                    val bw = number("bandwidth_khz")
                    require(freq in 150.0..2500.0) { "Frequency must be 150–2500 MHz" }
                    require(bw in listOf(7.8, 10.4, 15.6, 20.8, 31.25, 41.7, 62.5, 125.0, 250.0, 500.0)) { "Choose a supported bandwidth" }
                    val sf = integer("spreading_factor", 5..12); val cr = integer("coding_rate", 5..8)
                    var command = frame(11, (freq * 1000).roundToInt(), (bw * 1000).roundToInt()) + byteArrayOf(sf.toByte(), cr.toByte())
                    require(firmware < 9 || "client_repeat" in values) { "Refresh the complete device information before changing radio parameters" }
                    if ("client_repeat" in values) command += byteArrayOf(if (bool("client_repeat")) 1 else 0)
                    add(command)
                }
                if ("tx_power_dbm" in changes) add(byteArrayOf(12, integer("tx_power_dbm", -9..requireNotNull(self)[3].toInt()).toByte()))
            }
            id == "privacy" -> {
                val s = requireNotNull(self)
                var telemetry = ubyte(s, 46)
                listOf("telemetry_base", "telemetry_location", "telemetry_environment").forEachIndexed { index, key ->
                    if (key in changes) telemetry = (telemetry and (3 shl (index * 2)).inv()) or (integer(key, 0..2) shl (index * 2))
                }
                listOf(byteArrayOf(38,
                    if ("manual_add_contacts" in changes) { if (bool("manual_add_contacts")) 1 else 0 } else s[47],
                    telemetry.toByte(),
                    if ("advert_location" in changes) integer("advert_location", 0..2).toByte() else s[45],
                    if ("multi_acks" in changes) { if (bool("multi_acks")) 1 else 0 } else s[44],
                ))
            }
            id == "tuning" -> {
                val rx = number("rx_delay"); val airtime = number("airtime_factor")
                require(rx in 0.0..4_294_967.0 && airtime in 0.0..4_294_967.0) { "Tuning values are outside the supported range" }
                listOf(frame(21, (rx * 1000).roundToLong().toInt(), (airtime * 1000).roundToLong().toInt()))
            }
            id == "contacts" -> {
                var flags = ubyte(requireNotNull(autoadd), 1)
                AUTOADD.forEach { (key, bit) -> flags = if (bool(key)) flags or (1 shl bit) else flags and (1 shl bit).inv() }
                val command = byteArrayOf(58, flags.toByte())
                listOf(if ("max_hops" in values) command + integer("max_hops", 0..64).toByte() else command)
            }
            id == "bluetooth" -> {
                val pin = value("pin").toInt()
                require(pin == 0 || pin in 100000..999999) { "PIN must be 0 or six digits (100000–999999)" }
                listOf(frame(37, pin))
            }
            id == "path" -> listOf(byteArrayOf(61, 0, integer("hash_mode", 0..2).toByte()))
            id == "sensors" -> changes.map { (key, text) ->
                require(key.none { it in ":,\u0000" } && text.none { it in ":,\u0000" }) { "Sensor values cannot contain separators or NUL" }
                if (key == "gps_interval") require(text.toInt() in 0..86400) { "GPS interval must be 0–86400 seconds" }
                if (key == "gps") require(text in listOf("0", "1")) { "GPS must be 0 or 1" }
                val bytes = "$key:$text".encodeToByteArray()
                require(bytes.size <= 140) { "Sensor setting is too long" }
                byteArrayOf(41) + bytes + byteArrayOf(0)
            }
            id.startsWith("channel:") -> {
                val index = id.substringAfter(':').toInt()
                val bytes = name("name", allowEmpty = true).copyOf(32)
                val psk = value("psk_hex").trim().decodeHex().toByteArray()
                require(psk.size == 16) { "PSK must contain exactly 32 hex characters" }
                listOf(byteArrayOf(32, index.toByte()) + bytes + psk)
            }
            else -> error("Unsupported settings section")
        }
    }

    private fun field(id: String, value: String, kind: SettingKind = SettingKind.TEXT, choices: List<String> = emptyList(), help: String = "") =
        RadioSetting(id, ProtoSettings.label(id), value, kind, choices, help)
    private fun section(id: String, title: String, fields: List<RadioSetting>) = RadioSettingsSection(id, title, fields)
    private fun int(bytes: ByteArray, offset: Int) = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
    private fun uint(bytes: ByteArray, offset: Int) = int(bytes, offset).toUInt().toLong()
    private fun ubyte(bytes: ByteArray, offset: Int) = bytes[offset].toInt() and 255
    private fun string(bytes: ByteArray, offset: Int, length: Int = bytes.size - offset) =
        bytes.copyOfRange(offset, (offset + length).coerceAtMost(bytes.size)).takeWhile { it != 0.toByte() }.toByteArray().decodeToString()
    private fun frame(code: Int, vararg values: Int) = ByteBuffer.allocate(1 + values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        .apply { put(code.toByte()); values.forEach(::putInt) }.array()
    companion object {
        private val AUTOADD = listOf("overwrite_oldest" to 0, "auto_add_chat" to 1, "auto_add_repeaters" to 2, "auto_add_rooms" to 3, "auto_add_sensors" to 4)
    }
}
