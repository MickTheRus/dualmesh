package io.dualmesh.app.nodes

import kotlinx.coroutines.flow.StateFlow

data class NodeStatsGroup(val title: String, val values: List<Pair<String, String>>)

data class NodeActionState(
    val busy: Boolean = false,
    val status: String? = null,
    val error: String? = null,
    val stats: List<NodeStatsGroup> = emptyList(),
    val statsTarget: String? = null,
    val updatedAtMillis: Long? = null,
)

interface NodeActions {
    val state: StateFlow<NodeActionState>
    /** Null targets the locally connected radio. */
    fun requestStats(nodeId: String? = null): Result<Unit>
    fun advertise(flood: Boolean): Result<Unit>
}
