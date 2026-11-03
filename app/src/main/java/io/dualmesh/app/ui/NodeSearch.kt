package io.dualmesh.app.ui

import io.dualmesh.app.model.MeshNetwork
import io.dualmesh.app.model.MeshNode
import java.util.Locale

internal fun filterNodes(nodes: List<MeshNode>, query: String): List<MeshNode> {
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    val requestedChannel = CHANNEL_QUERY.find(normalizedQuery)
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()
    val terms = normalizedQuery
        .replace(CHANNEL_QUERY, " ")
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)
    if (terms.isEmpty() && requestedChannel == null) return nodes

    return nodes.filter { node ->
        if (requestedChannel != null && node.channel != requestedChannel) return@filter false
        val displayId = if (node.network == MeshNetwork.MESHTASTIC) {
            runCatching {
                "!${node.id.toUInt().toString(16).padStart(8, '0')}"
            }.getOrDefault(node.id)
        } else {
            node.id
        }
        val searchable = listOfNotNull(
            node.name,
            node.shortName,
            node.id,
            displayId,
            node.publicKey,
            node.role,
            node.hardware,
            node.channel?.let { "channel $it ch $it" },
            node.hopsAway?.let { "$it hops" },
            node.source,
            node.network.label,
            node.deviceName,
            "mqtt".takeIf { node.viaMqtt == true },
        ).joinToString(" ").lowercase(Locale.ROOT)

        terms.all(searchable::contains)
    }
}

private val CHANNEL_QUERY = Regex("\\b(?:channel|ch)\\s*#?(\\d+)\\b")
