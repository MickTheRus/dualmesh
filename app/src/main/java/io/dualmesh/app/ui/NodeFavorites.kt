package io.dualmesh.app.ui

import android.content.Context
import androidx.core.content.edit

/** Local favorites are scoped to network + source radio + node, independently of firmware flags. */
internal class NodeFavorites(context: Context) {
    private val preferences = context.getSharedPreferences("node_favorites", Context.MODE_PRIVATE)
    fun load(): Set<String> = preferences.getStringSet("nodes", emptySet()).orEmpty().toSet()
    fun save(keys: Set<String>) = preferences.edit { putStringSet("nodes", keys.toSet()) }
}
