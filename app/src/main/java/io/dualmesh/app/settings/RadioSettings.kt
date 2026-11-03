package io.dualmesh.app.settings

import kotlinx.coroutines.flow.StateFlow

enum class SettingKind { TEXT, NUMBER, BOOLEAN, CHOICE, SECRET, READ_ONLY }

data class RadioSetting(
    val id: String,
    val label: String,
    val value: String,
    val kind: SettingKind = SettingKind.TEXT,
    val choices: List<String> = emptyList(),
    val help: String = "",
) {
    override fun toString(): String = "RadioSetting($id, <redacted>)"
}

data class RadioSettingsSection(val id: String, val title: String, val fields: List<RadioSetting>)

data class RadioSettingsState(
    val sections: List<RadioSettingsSection> = emptyList(),
    val busy: Boolean = false,
    val status: String = "Read settings from the radio to begin.",
    val error: String? = null,
    val revision: Long = 0,
    val loadRequested: Boolean = false,
)

interface RadioSettingsAccess {
    val radioSettings: StateFlow<RadioSettingsState>
    /** Reopening the editor reuses this connection's cache, including an in-progress read. */
    fun ensureSettingsLoaded(): Result<Unit> =
        if (radioSettings.value.loadRequested || radioSettings.value.busy) Result.success(Unit)
        else refreshSettings()
    fun refreshSettings(): Result<Unit>
    fun saveSettings(sectionId: String, changes: Map<String, String>, revision: Long): Result<Unit>
}

fun interface SettingsScheduler {
    /** Return a function that cancels the scheduled callback. */
    fun after(milliseconds: Long, action: () -> Unit): () -> Unit
}

internal sealed interface SettingsReply {
    data object Ignore : SettingsReply
    data object Success : SettingsReply
    data class Error(val message: String) : SettingsReply
}

/** Serial requests with one timeout and one response matcher per command. */
internal class RadioCommandQueue<T>(private val scheduler: SettingsScheduler) {
    class Step<T>(
        val send: () -> Boolean,
        val accept: (T) -> SettingsReply,
        val received: (T) -> Unit = {},
        val optional: Boolean = false,
    )
    private val steps = ArrayDeque<Step<T>>()
    private var cancelTimeout: (() -> Unit)? = null
    private var generation = 0L
    private var finished: ((String?) -> Unit)? = null
    val busy: Boolean get() = finished != null

    fun start(commands: List<Step<T>>, onFinished: (String?) -> Unit) {
        check(!busy) { "Another radio settings operation is in progress" }
        steps.addAll(commands)
        finished = onFinished
        advance()
    }

    fun receive(value: T): Boolean {
        val step = steps.firstOrNull() ?: return false
        val reply = step.accept(value)
        if (reply == SettingsReply.Ignore) return false
        cancelTimeout?.invoke()
        generation++
        when (reply) {
            is SettingsReply.Error -> if (!step.optional) finish(reply.message) else { steps.removeFirst(); advance() }
            SettingsReply.Success -> {
                runCatching { step.received(value) }.onFailure {
                    finish(it.message ?: "Invalid settings response")
                    return true
                }
                steps.removeFirst()
                advance()
            }
            SettingsReply.Ignore -> Unit
        }
        return true
    }

    fun cancel(message: String? = null) = finish(message)

    private fun advance() {
        val step = steps.firstOrNull() ?: return finish(null)
        val version = ++generation
        cancelTimeout = scheduler.after(8_000) {
            if (version == generation) {
                if (step.optional) { steps.removeFirst(); advance() }
                else finish("Radio did not answer. Refresh settings before retrying; a write may have applied.")
            }
        }
        runCatching { step.send() }
            .onSuccess { if (!it) finish("The radio transport is unavailable") }
            .onFailure { finish(it.message ?: "Could not send radio settings") }
    }

    private fun finish(error: String?) {
        cancelTimeout?.invoke()
        cancelTimeout = null
        generation++
        steps.clear()
        val callback = finished
        finished = null
        callback?.invoke(error)
    }
}
