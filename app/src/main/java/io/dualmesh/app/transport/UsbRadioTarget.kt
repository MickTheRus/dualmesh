package io.dualmesh.app.transport

import java.util.Base64

/** Stable serial identity when available; otherwise require the same attached USB device path. */
data class UsbRadioTarget(val vendor: Int, val product: Int, val port: Int, val serial: String?, val path: String) {
    fun encode(): String = listOf(vendor, product, port, encodeText(serial.orEmpty()), encodeText(if (serial == null) path else "")).joinToString(":")
    companion object {
        private fun encodeText(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
        fun parse(value: String): Result<UsbRadioTarget> = runCatching {
            val parts = value.split(':'); require(parts.size == 5) { "Invalid USB radio address" }
            fun text(index: Int) = String(Base64.getUrlDecoder().decode(parts[index]), Charsets.UTF_8)
            val target = UsbRadioTarget(parts[0].toInt(), parts[1].toInt(), parts[2].toInt(), text(3).ifBlank { null }, text(4))
            require(target.vendor in 0..65535 && target.product in 0..65535 && target.port in 0..31 && (target.serial != null || target.path.isNotBlank())) { "Invalid USB radio address" }
            target
        }
    }
}
