package io.dualmesh.app.ui

import android.content.Context
import androidx.core.content.edit

internal class NodeMapPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("node_map_preferences", Context.MODE_PRIVATE)

    fun load(): NodeMapFilters = NodeMapFilters(
        meshCore = preferences.getBoolean("mesh_core", true),
        meshtastic = preferences.getBoolean("meshtastic", true),
        includeUnknown = preferences.getBoolean("include_unknown", true),
        excludeMqtt = preferences.getBoolean("exclude_mqtt", false),
        onlyDirect = preferences.getBoolean("only_direct", false),
        onlyOnline = preferences.getBoolean("only_online", false),
        hideUnheard = preferences.getBoolean("hide_unheard", false),
        excludeInfrastructure = preferences.getBoolean("exclude_infrastructure", false),
        lastHeardHours = preferences.getInt("last_heard_hours", 0).coerceAtLeast(0),
        excludedRoles = preferences.getStringSet("excluded_roles", emptySet()).orEmpty().toSet(),
        radioId = preferences.getString("radio_id", null),
        showLabels = preferences.getBoolean("show_labels", true),
        darkTiles = preferences.getBoolean("dark_tiles", false),
    )

    fun save(filters: NodeMapFilters) = preferences.edit {
        putBoolean("mesh_core", filters.meshCore)
        putBoolean("meshtastic", filters.meshtastic)
        putBoolean("include_unknown", filters.includeUnknown)
        putBoolean("exclude_mqtt", filters.excludeMqtt)
        putBoolean("only_direct", filters.onlyDirect)
        putBoolean("only_online", filters.onlyOnline)
        putBoolean("hide_unheard", filters.hideUnheard)
        putBoolean("exclude_infrastructure", filters.excludeInfrastructure)
        putInt("last_heard_hours", filters.lastHeardHours)
        putStringSet("excluded_roles", filters.excludedRoles)
        putString("radio_id", filters.radioId)
        putBoolean("show_labels", filters.showLabels)
        putBoolean("dark_tiles", filters.darkTiles)
    }
}
