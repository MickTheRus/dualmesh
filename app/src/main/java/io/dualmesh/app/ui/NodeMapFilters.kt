package io.dualmesh.app.ui

import io.dualmesh.app.model.MeshNetwork
import io.dualmesh.app.model.MeshNode

internal data class NodeMapFilters(
    val onlyFavorites: Boolean = false,
    val meshCore: Boolean = true,
    val meshtastic: Boolean = true,
    val includeUnknown: Boolean = true,
    val excludeMqtt: Boolean = false,
    val onlyDirect: Boolean = false,
    val onlyOnline: Boolean = false,
    val hideUnheard: Boolean = false,
    val excludeInfrastructure: Boolean = false,
    val lastHeardHours: Int = 0,
    val excludedRoles: Set<String> = emptySet(),
    val radioId: String? = null,
    val showLabels: Boolean = true,
    val darkTiles: Boolean = false,
)

internal fun filterMapNodes(
    nodes: List<MeshNode>,
    filters: NodeMapFilters,
    query: String,
    nowMillis: Long,
    favorites: Set<String> = emptySet(),
): List<MeshNode> = filterNodes(nodes, query).filter { node ->
    val heard = node.lastHeardMillis > 0
    val age = (nowMillis - node.lastHeardMillis).coerceAtLeast(0)
    (!filters.onlyFavorites || mapNodeKey(node) in favorites) &&
    (if (node.network == MeshNetwork.MESHCORE) filters.meshCore else filters.meshtastic) &&
        (filters.radioId == null || node.deviceId == filters.radioId) &&
        (filters.includeUnknown || node.hasKnownMapIdentity()) &&
        (!filters.excludeMqtt || node.viaMqtt != true) &&
        (!filters.onlyDirect || (node.hopsAway == 0 && node.viaMqtt != true)) &&
        (!filters.onlyOnline || (heard && age <= 2 * 3_600_000L)) &&
        (!filters.hideUnheard || heard) &&
        (!filters.excludeInfrastructure || node.role?.uppercase() !in INFRASTRUCTURE_ROLES) &&
        (filters.lastHeardHours <= 0 || (heard && age <= filters.lastHeardHours * 3_600_000L)) &&
        (node.role == null || mapRoleKey(node) !in filters.excludedRoles)
}

internal fun MeshNode.hasKnownMapIdentity(): Boolean = when (network) {
    MeshNetwork.MESHTASTIC -> !shortName.isNullOrBlank()
    MeshNetwork.MESHCORE -> name.isNotBlank() && !name.equals(id, ignoreCase = true) &&
        !name.equals(id.take(12), ignoreCase = true) && !name.equals("Unknown", ignoreCase = true)
}

internal fun MeshNode.hasValidMapPosition(): Boolean =
    latitude?.let { it.isFinite() && it in -90.0..90.0 } == true &&
        longitude?.let { it.isFinite() && it in -180.0..180.0 } == true &&
        !(latitude == 0.0 && longitude == 0.0)

internal fun mapNodeKey(node: MeshNode): String = "${node.network.name}:${node.deviceId}:${node.id}"

internal fun mapRoleKey(node: MeshNode): String = "${node.network.name}:${node.role}"

private val INFRASTRUCTURE_ROLES = setOf(
    "ROUTER", "ROUTER_CLIENT", "ROUTER_LATE", "REPEATER", "ROOM", "ROOM_SERVER",
)
