package io.dualmesh.app.connection

import android.content.Context
import androidx.core.content.edit
import io.dualmesh.app.model.MeshNetwork
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

class ConnectionProfileStore(context: Context) {
    enum class Transport {
        BLE,
        TCP,
    }

    data class Profile(
        val network: MeshNetwork,
        val transport: Transport,
        val address: String,
        val name: String,
        val lastUsedMillis: Long = 0,
        val enabled: Boolean = false,
    )

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(network: MeshNetwork): Profile? = loadOutbound(network)

    fun loadOutbound(network: MeshNetwork): Profile? =
        loadLegacy(network, requireEnabled = true)

    fun loadAll(network: MeshNetwork): List<Profile> {
        val active = loadLegacy(network, requireEnabled = true)
        val legacy = loadLegacy(network, requireEnabled = false)
        val profiles = preferences.getStringSet(historyKey(network), emptySet())
            .orEmpty()
            .mapNotNull { decodeHistoryEntry(network, it) }
            .toMutableList()
        if (legacy != null) {
            val matching = profiles.indexOfFirst { it.sameTarget(legacy) }
            if (matching < 0) {
                profiles += legacy
            } else if (active != null) {
                profiles[matching] = profiles[matching].copy(enabled = true)
            }
        }
        val sorted = profiles
            .distinctBy { it.transport to it.address }
            .sortedByDescending(Profile::lastUsedMillis)
        return if (active == null) {
            sorted
        } else {
            sorted.sortedByDescending { it.sameTarget(active) }
        }
    }

    private fun loadLegacy(network: MeshNetwork, requireEnabled: Boolean): Profile? {
        if (requireEnabled && !preferences.getBoolean(key(network, "enabled"), false)) return null
        val transport = preferences.getString(key(network, "transport"), null)
            ?.let { runCatching { Transport.valueOf(it) }.getOrNull() }
            ?: return null
        val address = preferences.getString(key(network, "address"), null)
            ?.takeIf(String::isNotBlank)
            ?: return null
        val name = preferences.getString(key(network, "name"), null)
            ?.takeIf(String::isNotBlank)
            ?: address
        return Profile(
            network = network,
            transport = transport,
            address = address,
            name = name,
            enabled = preferences.getBoolean(key(network, "enabled"), false),
        )
    }

    fun saveBle(network: MeshNetwork, address: String, name: String) {
        activate(Profile(network, Transport.BLE, address, name), makeOutbound = true)
    }

    fun saveTcp(endpoint: String) {
        activate(
            Profile(
                MeshNetwork.MESHTASTIC,
                Transport.TCP,
                endpoint,
                "TCP $endpoint",
            ),
            makeOutbound = true,
        )
    }

    fun loadEnabled(network: MeshNetwork): List<Profile> =
        loadAll(network).filter(Profile::enabled)

    fun activate(profile: Profile, makeOutbound: Boolean = true): Profile {
        val recentProfile = profile.copy(
            lastUsedMillis = System.currentTimeMillis(),
            enabled = true,
        )
        updateHistory(recentProfile)
        if (makeOutbound || loadOutbound(profile.network) == null) {
            saveOutbound(recentProfile)
        }
        return recentProfile
    }

    fun setOutbound(profile: Profile) {
        val enabledProfile = profile.copy(enabled = true)
        updateHistory(enabledProfile)
        saveOutbound(enabledProfile)
    }

    fun disable(profile: Profile) {
        updateHistory(profile.copy(enabled = false))
        val outbound = loadOutbound(profile.network)
        if (outbound?.sameTarget(profile) == true) {
            val replacement = loadEnabled(profile.network)
                .firstOrNull { !it.sameTarget(profile) }
            if (replacement == null) {
                preferences.edit { putBoolean(key(profile.network, "enabled"), false) }
            } else {
                saveOutbound(replacement)
            }
        }
    }

    fun disable(network: MeshNetwork) {
        val disabled = loadAll(network)
            .map { it.copy(enabled = false) }
            .mapTo(mutableSetOf(), ::encodeHistoryEntry)
        preferences.edit {
            putBoolean(key(network, "enabled"), false)
            putStringSet(historyKey(network), disabled)
        }
    }

    fun loadConversation(network: MeshNetwork, deviceId: String? = null): String =
        preferences.getString(conversationKey(network, deviceId), null)
            ?.takeIf {
                it.startsWith("channel:") || it.startsWith("contact:")
            }
            ?: "channel:0"

    fun saveConversation(
        network: MeshNetwork,
        conversationId: String,
        deviceId: String? = null,
    ) {
        preferences.edit {
            putString(conversationKey(network, deviceId), conversationId)
        }
    }

    fun loadSelectedNetwork(): MeshNetwork =
        preferences.getString(KEY_SELECTED_NETWORK, null)
            ?.let { stored -> MeshNetwork.entries.firstOrNull { it.name == stored } }
            ?: MeshNetwork.MESHCORE

    fun saveSelectedNetwork(network: MeshNetwork) {
        preferences.edit { putString(KEY_SELECTED_NETWORK, network.name) }
    }

    fun hasEnabledProfiles(): Boolean = MeshNetwork.entries.any { load(it) != null }

    private fun updateHistory(profile: Profile) {
        val history = loadAll(profile.network)
            .filterNot { it.sameTarget(profile) }
            .plus(profile)
            .sortedByDescending(Profile::lastUsedMillis)
            .take(HISTORY_LIMIT)
            .mapTo(mutableSetOf(), ::encodeHistoryEntry)
        preferences.edit {
            putStringSet(historyKey(profile.network), history)
        }
    }

    private fun saveOutbound(profile: Profile) {
        preferences.edit {
            putBoolean(key(profile.network, "enabled"), true)
            putString(key(profile.network, "transport"), profile.transport.name)
            putString(key(profile.network, "address"), profile.address)
            putString(key(profile.network, "name"), profile.name)
        }
    }

    fun Profile.sameTarget(other: Profile): Boolean =
        transport == other.transport && address == other.address

    private fun key(network: MeshNetwork, suffix: String): String =
        "${network.name.lowercase()}_$suffix"

    private fun historyKey(network: MeshNetwork): String =
        "${network.name.lowercase()}_connection_history"

    private fun conversationKey(network: MeshNetwork, deviceId: String?): String =
        if (deviceId.isNullOrBlank()) {
            key(network, "last_conversation")
        } else {
            key(
                network,
                "last_conversation_${encoder.encodeToString(deviceId.toByteArray(UTF_8))}",
            )
        }

    companion object {
        private const val PREFERENCES_NAME = "connection_profiles"
        private const val KEY_SELECTED_NETWORK = "selected_network"
        private const val HISTORY_LIMIT = 12
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()

        internal fun encodeHistoryEntry(profile: Profile): String = listOf(
            profile.transport.name,
            encoder.encodeToString(profile.address.toByteArray(UTF_8)),
            encoder.encodeToString(profile.name.toByteArray(UTF_8)),
            profile.lastUsedMillis.toString(),
            profile.enabled.toString(),
        ).joinToString("|")

        internal fun decodeHistoryEntry(network: MeshNetwork, value: String): Profile? =
            runCatching {
                val fields = value.split('|')
                require(fields.size == 4 || fields.size == 5)
                Profile(
                    network = network,
                    transport = Transport.valueOf(fields[0]),
                    address = String(decoder.decode(fields[1]), UTF_8),
                    name = String(decoder.decode(fields[2]), UTF_8),
                    lastUsedMillis = fields[3].toLong(),
                    enabled = fields.getOrNull(4)?.toBooleanStrict() ?: false,
                )
            }.getOrNull()
    }
}
