package io.dualmesh.app.transport

import io.dualmesh.app.model.MeshNetwork
import io.dualmesh.app.protocol.MeshCoreStreamCodec
import io.dualmesh.app.protocol.MeshtasticStreamCodec

internal class RadioStreamFraming(private val network: MeshNetwork, onPacket: (ByteArray) -> Unit) {
    private val mc = MeshCoreStreamCodec(onPacket)
    private val mt = MeshtasticStreamCodec(onPacket)
    val wakeBytes get() = if (network == MeshNetwork.MESHTASTIC) MeshtasticStreamCodec.WAKE_BYTES else byteArrayOf()
    fun accept(bytes: ByteArray, count: Int) { if (network == MeshNetwork.MESHCORE) mc.accept(bytes, count) else mt.accept(bytes, count) }
    fun frame(payload: ByteArray): ByteArray = if (network == MeshNetwork.MESHCORE) MeshCoreStreamCodec.frame(payload) else MeshtasticStreamCodec.frame(payload)
}
