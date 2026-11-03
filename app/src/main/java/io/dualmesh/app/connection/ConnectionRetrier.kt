package io.dualmesh.app.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Re-arms even when a failed attempt leaves StateFlow at the same error value. */
internal class ConnectionRetrier(
    private val scope: CoroutineScope,
    private val shouldRetry: () -> Boolean,
    private val connect: () -> Unit,
    private val changed: (Int, Long?) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var job: Job? = null
    private var attempt = 0
    private var generation = 0
    fun schedule() {
        if (job?.isActive == true || !shouldRetry()) return
        val version = ++generation
        val pause = DELAYS[attempt.coerceAtMost(DELAYS.lastIndex)]
        changed(attempt, now() + pause)
        job = scope.launch {
            delay(pause)
            if (version != generation || !shouldRetry()) return@launch
            job = null
            attempt++
            changed(attempt, null)
            connect()
            if (version == generation && shouldRetry()) schedule()
        }
    }
    fun cancel(reset: Boolean = false) {
        generation++
        job?.cancel()
        job = null
        if (reset) attempt = 0
        changed(attempt, null)
    }
    companion object { private val DELAYS = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000) }
}
