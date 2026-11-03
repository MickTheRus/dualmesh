package io.dualmesh.app.settings

import com.squareup.wire.Message
import com.squareup.wire.ProtoAdapter
import com.squareup.wire.ProtoWriter
import com.squareup.wire.WireField
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

/** Edit only named protobuf fields, retaining unknown fields and all unedited values. */
internal object ProtoSettings {
    private data class Binding(val field: Field, val wire: WireField, val adapter: ProtoAdapter<Any>)
    private val bindings = ConcurrentHashMap<Class<*>, List<Binding>>()
    @Suppress("UNCHECKED_CAST")
    private fun bindings(type: Class<*>): List<Binding> = bindings.getOrPut(type) {
        type.declaredFields.mapNotNull { field ->
            val annotation = field.getAnnotation(WireField::class.java) ?: return@mapNotNull null
            field.isAccessible = true
            Binding(field, annotation, ProtoAdapter.get(annotation.adapter) as ProtoAdapter<Any>)
        }.sortedBy { it.wire.schemaIndex }
    }

    fun records(message: Message<*, *>): List<Pair<String, Message<*, *>>> = bindings(message.javaClass)
        .mapNotNull { binding -> (binding.field.get(message) as? Message<*, *>)?.let { binding.field.name to it } }

    fun fields(message: Message<*, *>, prefix: String = ""): List<RadioSetting> = bindings(message.javaClass).flatMap { b ->
        val name = b.field.name
        val path = if (prefix.isEmpty()) name else "$prefix.$name"
        val value = b.field.get(message)
        val repeated = b.wire.label in setOf(WireField.Label.REPEATED, WireField.Label.PACKED)
        val messageType = b.adapter.type?.java?.let { Message::class.java.isAssignableFrom(it) } == true
        when {
            b.wire.keyAdapter.isNotEmpty() || (messageType && repeated) -> listOf(RadioSetting(
                path, label(path), "${(value as? Collection<*>)?.size ?: 0} entries", SettingKind.READ_ONLY,
                help = "Structured entries are preserved when other fields are saved.",
            ))
            messageType -> {
                val nested = value as? Message<*, *> ?: b.adapter.decode(byteArrayOf()) as Message<*, *>
                fields(nested, path)
            }
            else -> {
                val options = b.adapter.type?.java?.enumConstants?.map { (it as Enum<*>).name }.orEmpty()
                val secret = b.wire.redacted || name.contains("password", true) || name.contains("psk", true) ||
                    name.contains("private_key", true) || name.contains("admin_key", true) || value is ByteString
                val kind = when {
                    secret -> SettingKind.SECRET
                    repeated -> SettingKind.TEXT
                    options.isNotEmpty() -> SettingKind.CHOICE
                    b.adapter == ProtoAdapter.BOOL -> SettingKind.BOOLEAN
                    b.adapter == ProtoAdapter.STRING -> SettingKind.TEXT
                    else -> SettingKind.NUMBER
                }
                listOf(RadioSetting(path, label(path), if (repeated) {
                    (value as? List<*>)?.joinToString("\n") { format(it, b) }.orEmpty()
                } else format(value, b), kind, options, when {
                    repeated -> "One value per line.${if (secret) " Keys use Base64." else ""}"
                    b.adapter == ProtoAdapter.BYTES -> "Base64-encoded bytes; blank clears the field."
                    else -> ""
                }))
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun patch(message: Message<*, *>, changes: Map<String, String>): Message<*, *> {
        val known = fields(message).associateBy { it.id }
        changes.forEach { (id, _) ->
            require(known[id] != null && known[id]?.kind != SettingKind.READ_ONLY) { "Unsupported setting: $id" }
        }
        var bytes = message.encode()
        for ((path, value) in changes) bytes = patchPath(message.javaClass, bytes, path.split('.'), value)
        return (ProtoAdapter.get(message.javaClass) as ProtoAdapter<Any>).decode(bytes) as Message<*, *>
    }

    @Suppress("UNCHECKED_CAST")
    private fun patchPath(type: Class<*>, bytes: ByteArray, path: List<String>, input: String): ByteArray {
        val b = bindings(type).first { it.field.name == path.first() }
        val replacement = Buffer()
        val writer = ProtoWriter(replacement)
        if (path.size > 1) {
            val current = (ProtoAdapter.get(type) as ProtoAdapter<Any>).decode(bytes)
            val child = b.field.get(current)
            val childBytes = if (child == null) byteArrayOf() else b.adapter.encode(child)
            val updated = patchPath(requireNotNull(b.adapter.type).java, childBytes, path.drop(1), input)
            b.adapter.encodeWithTag(writer, b.wire.tag, b.adapter.decode(updated))
        } else {
            val repeated = b.wire.label in setOf(WireField.Label.REPEATED, WireField.Label.PACKED)
            if (repeated) {
                val values = input.lines().filter { it.isNotBlank() }.map { parse(it.trim(), b) }
                val adapter = if (b.wire.label == WireField.Label.PACKED) b.adapter.asPacked() else b.adapter.asRepeated()
                adapter.encodeWithTag(writer, b.wire.tag, values)
            } else if (input.isNotEmpty() || b.adapter == ProtoAdapter.STRING || b.adapter == ProtoAdapter.BYTES) {
                b.adapter.encodeWithTag(writer, b.wire.tag, parse(input, b))
            }
        }
        return replaceTag(bytes, b.wire.tag, replacement.readByteArray())
    }

    private fun format(value: Any?, b: Binding): String = when {
        value == null -> ""
        value is ByteString -> value.base64()
        value is Enum<*> -> value.name
        b.wire.adapter.endsWith("#UINT32") || b.wire.adapter.endsWith("#FIXED32") -> (value as Int).toUInt().toString()
        b.wire.adapter.endsWith("#UINT64") || b.wire.adapter.endsWith("#FIXED64") -> (value as Long).toULong().toString()
        else -> value.toString()
    }

    private fun parse(input: String, b: Binding): Any {
        val value = input.trim()
        return when (b.adapter) {
            ProtoAdapter.STRING -> input
            ProtoAdapter.BYTES -> requireNotNull(input.decodeBase64()) { "${label(b.field.name)} must be valid Base64" }
            ProtoAdapter.BOOL -> requireNotNull(value.toBooleanStrictOrNull()) { "Choose true or false" }
            ProtoAdapter.UINT32, ProtoAdapter.FIXED32 -> value.toUInt().toInt()
            ProtoAdapter.UINT64, ProtoAdapter.FIXED64 -> value.toULong().toLong()
            ProtoAdapter.INT32, ProtoAdapter.SINT32, ProtoAdapter.SFIXED32 -> value.toInt()
            ProtoAdapter.INT64, ProtoAdapter.SINT64, ProtoAdapter.SFIXED64 -> value.toLong()
            ProtoAdapter.FLOAT -> value.toFloat().also { require(it.isFinite()) { "Enter a finite number" } }
            ProtoAdapter.DOUBLE -> value.toDouble().also { require(it.isFinite()) { "Enter a finite number" } }
            else -> requireNotNull(b.adapter.type?.java?.enumConstants?.firstOrNull { (it as Enum<*>).name == value }) { "Choose a supported value" }
        }
    }

    fun label(value: String): String = value.split('.').joinToString(" / ") { group ->
        group.split('_').joinToString(" ") { word ->
            when (word.lowercase()) {
                "mqtt", "gps", "gpio", "usb", "tcp", "ble", "psk", "tls", "ssid", "ntp", "lora", "pwm" -> word.uppercase()
                else -> word.replaceFirstChar(Char::uppercase)
            }
        }
    }

    /** Retain complete wire chunks, including fields this version of the app does not know. */
    internal fun replaceTag(bytes: ByteArray, tag: Int, replacement: ByteArray): ByteArray {
        val out = Buffer()
        var offset = 0
        fun varint(): Long {
            var result = 0L
            for (shift in 0..63 step 7) {
                require(offset < bytes.size) { "Truncated protobuf" }
                val byte = bytes[offset++].toInt() and 255
                result = result or ((byte and 127).toLong() shl shift)
                if (byte and 128 == 0) return result
            }
            throw IllegalArgumentException("Invalid protobuf varint")
        }
        while (offset < bytes.size) {
            val start = offset
            val key = varint()
            require(key ushr 3 > 0) { "Invalid protobuf field tag" }
            when (key.toInt() and 7) {
                0 -> varint()
                1 -> offset += 8
                2 -> {
                    val length = varint()
                    require(length in 0..(bytes.size - offset).toLong()) { "Invalid protobuf length" }
                    offset += length.toInt()
                }
                5 -> offset += 4
                else -> throw IllegalArgumentException("Unsupported protobuf wire type")
            }
            require(offset <= bytes.size) { "Truncated protobuf" }
            if (key ushr 3 != tag.toLong()) out.write(bytes, start, offset - start)
        }
        out.write(replacement)
        return out.readByteArray()
    }
}
