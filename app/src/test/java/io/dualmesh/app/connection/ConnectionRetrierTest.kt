package io.dualmesh.app.connection

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionRetrierTest {
    @Test fun keepsRetryingWhenFailuresNeverEmitANewStateAndCapsBackoff() = runTest {
        var attempts = 0
        var retry = true
        val scheduled = mutableListOf<Long>()
        val controller = ConnectionRetrier(backgroundScope, { retry }, { attempts++ }, { _, next -> next?.let { scheduled += it - testScheduler.currentTime } }, { testScheduler.currentTime })
        controller.schedule(); controller.schedule()
        runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertEquals(1, attempts)
        advanceTimeBy(2_000); runCurrent(); assertEquals(2, attempts)
        advanceTimeBy(5_000); runCurrent(); assertEquals(3, attempts)
        advanceTimeBy(10_000); runCurrent(); assertEquals(4, attempts)
        repeat(3) { advanceTimeBy(30_000); runCurrent() }
        assertEquals(7, attempts)
        assertEquals(listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 30_000L, 30_000L, 30_000L), scheduled)
        retry = false; advanceTimeBy(60_000); runCurrent(); assertEquals(7, attempts)
    }
    @Test fun disableCancelsPendingAttemptAndSuccessfulReconnectResetsBackoff() = runTest {
        var attempts = 0
        var next: Long? = null
        val controller = ConnectionRetrier(backgroundScope, { true }, { attempts++ }, { _, at -> next = at }, { testScheduler.currentTime })
        controller.schedule(); runCurrent(); controller.cancel()
        advanceTimeBy(60_000); runCurrent(); assertEquals(0, attempts); assertNull(next)
        controller.schedule(); runCurrent(); advanceTimeBy(1_000); runCurrent(); assertEquals(1, attempts)
        controller.cancel(reset = true)
        controller.schedule(); assertEquals(testScheduler.currentTime + 1_000, next)
        controller.cancel()
    }
    @Test fun successfulStateDuringAttemptDoesNotScheduleAnotherConnection() = runTest {
        var needed = true
        var attempts = 0
        val controller = ConnectionRetrier(backgroundScope, { needed }, { attempts++; needed = false }, { _, _ -> })
        controller.schedule(); runCurrent(); advanceTimeBy(1_000); runCurrent()
        advanceTimeBy(100_000); runCurrent(); assertEquals(1, attempts)
    }
}
