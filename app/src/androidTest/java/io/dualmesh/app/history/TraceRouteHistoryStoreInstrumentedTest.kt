package io.dualmesh.app.history

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.dualmesh.app.model.MeshNetwork
import io.dualmesh.app.model.TraceRouteHop
import io.dualmesh.app.model.TraceRouteResult
import io.dualmesh.app.model.TraceRouteState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TraceRouteHistoryStoreInstrumentedTest {
    private val databaseName = "test_traceroutes.db"
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun testTraceResultRoundTripAndUpdate() = runBlocking {
        val store = TraceRouteHistoryStore(context, databaseName)
        try {
            val pending = sampleResult(TraceRouteState.PENDING)
            store.save(pending)
            store.save(
                pending.copy(
                    state = TraceRouteState.COMPLETE,
                    completedAtMillis = 2_000,
                    forward = listOf(
                        TraceRouteHop("1", "Local"),
                        TraceRouteHop("2", "Relay", 7.25f),
                    ),
                    returned = listOf(
                        TraceRouteHop("2", "Relay"),
                        TraceRouteHop("1", "Local", 5.5f),
                    ),
                    detail = "Complete",
                ),
            )

            val loaded = store.loadResults()

            assertEquals(1, loaded.size)
            assertEquals(TraceRouteState.COMPLETE, loaded.single().state)
            assertEquals("Relay", loaded.single().forward[1].name)
            assertEquals(7.25f, loaded.single().forward[1].snr)
            assertEquals(5.5f, loaded.single().returned[1].snr)
        } finally {
            store.close()
        }
    }

    @Test
    fun testPendingTraceBecomesFailedAfterRestart() = runBlocking {
        val store = TraceRouteHistoryStore(context, databaseName)
        try {
            store.save(sampleResult(TraceRouteState.PENDING))

            store.markInterruptedRuns()
            val loaded = store.loadResults()

            assertEquals(TraceRouteState.ERROR, loaded.single().state)
            assertNotNull(loaded.single().completedAtMillis)
            assertTrue(loaded.single().detail.orEmpty().contains("stopped"))
        } finally {
            store.close()
        }
    }

    private fun sampleResult(state: TraceRouteState) = TraceRouteResult(
        id = "42",
        network = MeshNetwork.MESHTASTIC,
        deviceId = "MESHTASTIC:TCP:192.0.2.10",
        deviceName = "Test radio",
        targetId = "1234",
        targetName = "Test node",
        state = state,
        startedAtMillis = 1_000,
    )
}
