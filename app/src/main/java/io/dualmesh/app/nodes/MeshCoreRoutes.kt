package io.dualmesh.app.nodes

import io.dualmesh.app.protocol.MeshCoreProtocol
import kotlinx.coroutines.flow.StateFlow

/** Hop hashes are ordered from this companion toward the destination, excluding the destination. */
data class MeshCoreRoute(val hashBytes: Int = 1, val hops: List<String> = emptyList(), val flood: Boolean = false)
data class ContactRoute(val current: MeshCoreRoute, val history: List<MeshCoreRoute> = emptyList(), val discovered: MeshCoreRoute? = null)

interface ContactRoutes {
    val supportedHashBytes: List<Int>
    val routeState: StateFlow<Map<String, ContactRoute>>
    val operationState: StateFlow<NodeActionState>
    fun refreshRoute(contactId: String): Result<Unit>
    fun setRoute(contactId: String, route: MeshCoreRoute): Result<Unit>
    fun discoverRoute(contactId: String): Result<Unit>
}

internal object MeshCoreRouteCodec {
    fun key(contactId: String): ByteArray {
        require(contactId.matches(Regex("[0-9a-fA-F]{64}"))) { "A full MeshCore contact key is required" }
        return contactId.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    fun normalize(route: MeshCoreRoute): MeshCoreRoute {
        require(route.hashBytes in 1..3) { "Use 1, 2, or 3 bytes per hop hash" }
        require(!route.flood || route.hops.isEmpty()) { "A flood route cannot contain manual hops" }
        require(route.hops.size <= 63 && route.hops.size * route.hashBytes <= 64) { "The route exceeds the radio’s 64-byte path capacity" }
        val pattern = Regex("[0-9a-fA-F]{${route.hashBytes * 2}}")
        require(route.hops.all { it.matches(pattern) }) { "Each hop must contain ${route.hashBytes * 2} hex characters" }
        return if (route.flood) MeshCoreRoute(flood = true) else route.copy(hops = route.hops.map { it.lowercase() })
    }

    fun parseContact(frame: ByteArray): Pair<String, MeshCoreRoute> {
        val contact = MeshCoreProtocol.parseContact(frame) ?: error("Invalid contact response")
        return contact.publicKey to if (frame[35] == (-1).toByte()) MeshCoreRoute(flood = true)
            else normalize(MeshCoreRoute(contact.outPathHashSize, contact.outPath))
    }

    /** Patch the freshly read contact, preserving identity, flags, timestamps, location and future fields. */
    fun updateContact(frame: ByteArray, route: MeshCoreRoute): ByteArray {
        parseContact(frame)
        val normalized = normalize(route)
        require(!normalized.flood) { "Use reset-path for flood routing" }
        return frame.copyOf().apply {
            this[0] = 9
            this[35] = (((normalized.hashBytes - 1) shl 6) or normalized.hops.size).toByte()
            fill(0, 36, 100)
            normalized.hops.joinToString("").chunked(2).map { it.toInt(16).toByte() }.toByteArray().copyInto(this, 36)
        }
    }

    /** Firmware's first path is the outbound route; validate both paths before presenting a suggestion. */
    fun discovery(frame: ByteArray): MeshCoreRoute {
        require(frame.size >= 10 && frame[0] == 0x8d.toByte()) { "Invalid discovery response" }
        fun read(offset: Int): Pair<MeshCoreRoute, Int> {
            require(offset < frame.size) { "Truncated discovered route" }
            val encoded = frame[offset].toInt() and 255
            require(encoded != 255) { "Discovery returned an unknown route" }
            val width = (encoded ushr 6) + 1
            val count = encoded and 63
            val end = offset + 1 + width * count
            require(width in 1..3 && width * count <= 64 && end <= frame.size) { "Invalid discovered path length" }
            val hops = frame.copyOfRange(offset + 1, end).toList().chunked(width).map { bytes -> bytes.joinToString("") { "%02x".format(it.toInt() and 255) } }
            return normalize(MeshCoreRoute(width, hops)) to end
        }
        val (outbound, next) = read(8)
        val (_, end) = read(next)
        require(end == frame.size) { "Invalid discovery response length" }
        return outbound
    }
}
