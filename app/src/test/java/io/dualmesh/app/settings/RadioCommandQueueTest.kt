package io.dualmesh.app.settings

import org.junit.Assert.*
import org.junit.Test

internal class ManualSettingsScheduler : SettingsScheduler {
    private data class Task(val action: () -> Unit, var cancelled: Boolean = false)
    private val tasks = mutableListOf<Task>()
    override fun after(milliseconds: Long, action: () -> Unit): () -> Unit {
        val task = Task(action)
        tasks.add(task)
        return { task.cancelled = true }
    }
    fun timeout() {
        val task = tasks.first { !it.cancelled }
        task.cancelled = true
        task.action()
    }
    fun staleCallbacks() { tasks.toList().forEach { it.action() } }
}

class RadioCommandQueueTest {
    @Test fun `commands serialize and unrelated replies cannot advance writes`() {
        val timer = ManualSettingsScheduler()
        val queue = RadioCommandQueue<Int>(timer)
        val sent = mutableListOf<Int>()
        var result: String? = "pending"
        queue.start((1..2).map { expected -> RadioCommandQueue.Step(
            send = { sent.add(expected); true },
            accept = { if (it == expected) SettingsReply.Success else SettingsReply.Ignore },
        ) }) { result = it }
        assertEquals(listOf(1), sent)
        assertFalse(queue.receive(9))
        assertEquals(listOf(1), sent)
        assertTrue(queue.receive(1))
        assertEquals(listOf(1, 2), sent)
        assertTrue(queue.receive(2))
        assertNull(result)
        timer.staleCallbacks()
        assertNull(result)
        assertFalse(queue.busy)
    }

    @Test fun `timeout rejection or send failure stops remaining writes`() {
        for (mode in listOf("timeout", "reject", "throw")) {
            val timer = ManualSettingsScheduler()
            val queue = RadioCommandQueue<Int>(timer)
            var secondSent = false
            var failure: String? = null
            queue.start(listOf(
                RadioCommandQueue.Step(send = { if (mode == "throw") error("transport error") else true }, accept = { SettingsReply.Error("rejected") }),
                RadioCommandQueue.Step(send = { secondSent = true; true }, accept = { SettingsReply.Success }),
            )) { failure = it }
            if (mode == "timeout") timer.timeout() else if (mode == "reject") queue.receive(1)
            assertFalse(secondSent)
            assertNotNull(failure)
            assertFalse(queue.busy)
        }
    }
}
