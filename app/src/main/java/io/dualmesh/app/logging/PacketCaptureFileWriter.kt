package io.dualmesh.app.logging

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Write newline-delimited JSON while packet logging is enabled.
 */
internal class PacketCaptureFileWriter(private val context: Context) {
    private var writer: BufferedWriter? = null

    var activePath: String? = null
        private set

    @SuppressLint("Recycle") // Keep the stream open. The stop() function closes it.
    fun start(): String {
        activePath?.let { return it }
        val fileName = "DualMesh-packets-${FILE_TIME_FORMAT.format(Instant.now())}.jsonl"
        val (output, path) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/DualMesh"
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/x-ndjson")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                },
            ) ?: error("Unable to create packet capture in Downloads")
            val stream = context.contentResolver.openOutputStream(uri, "wa")
                ?: run {
                    context.contentResolver.delete(uri, null, null)
                    error("Unable to open packet capture in Downloads")
                }
            stream to "/storage/emulated/0/$relativePath/$fileName"
        } else {
            val directory = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
                "DualMesh",
            )
            check(directory.exists() || directory.mkdirs()) {
                "Unable to create packet capture directory"
            }
            val file = File(directory, fileName)
            file.outputStream() to file.absolutePath
        }
        activePath = path
        writer = BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8))
        writer?.apply {
            append(
                """{"format":"dualmesh-packet-log","version":1,"created_utc":""" +
                    """"${ENTRY_TIME_FORMAT.format(Instant.now())}"}""",
            )
            newLine()
            flush()
        }
        return checkNotNull(activePath)
    }

    fun append(entry: PacketLogEntry) {
        if (writer == null) start()
        writer?.apply {
            append(
                buildString {
                    append("""{"timestamp_ms":${entry.timestampMillis}""")
                    append(""","timestamp_utc":"""")
                    append(ENTRY_TIME_FORMAT.format(Instant.ofEpochMilli(entry.timestampMillis)))
                    append('"')
                    append(""","network":"${entry.network.name}"""")
                    append(""","direction":"${entry.direction.name}"""")
                    append(""","transport":"${entry.transport.jsonEscape()}"""")
                    append(""","summary":"${entry.summary.jsonEscape()}"""")
                    append(""","length":${entry.payload.size}""")
                    append(""","payload_hex":"""")
                    entry.payload.forEach { byte ->
                        append(HEX_DIGITS[(byte.toInt() ushr 4) and 0x0f])
                        append(HEX_DIGITS[byte.toInt() and 0x0f])
                    }
                    append("\"}")
                },
            )
            newLine()
            // Flush this frame. This prevents data loss if the process stops.
            flush()
        }
    }

    fun stop() {
        writer?.runCatching {
            flush()
            close()
        }
        writer = null
        activePath = null
    }

    private fun String.jsonEscape(): String = buildString(length) {
        this@jsonEscape.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) {
                    append("\\u${char.code.toString(16).padStart(4, '0')}")
                } else {
                    append(char)
                }
            }
        }
    }

    companion object {
        private const val HEX_DIGITS = "0123456789abcdef"
        private val FILE_TIME_FORMAT = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC)
        private val ENTRY_TIME_FORMAT = DateTimeFormatter.ISO_INSTANT
    }
}
