package com.tabula.v3

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MediaLoadTestHookTest {
    private lateinit var executor: ExecutorService
    private lateinit var dispatcher: ExecutorCoroutineDispatcher
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "media-load-hook-test").apply { isDaemon = true }
        }
        dispatcher = executor.asCoroutineDispatcher()
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        assertTrue("These tests require the debug variant", BuildConfig.DEBUG)
        MediaLoadTestHook.reset()
    }

    @After
    fun tearDown() {
        try {
            MediaLoadTestHook.reset()
        } finally {
            scope.cancel()
            executor.shutdownNow()
            val terminated = executor.awaitTermination(5, TimeUnit.SECONDS)
            dispatcher.close()
            assertTrue("Hook worker must terminate after interruption", terminated)
        }
    }

    private suspend fun awaitParked(rows: Int) = withTimeout(5_000) {
        while (MediaLoadTestHook.parkedRows() != rows) delay(10)
    }

    @Test
    fun resetClearsAnUnconsumedArm() = runBlocking {
        try {
            MediaLoadTestHook.armOnce()
            MediaLoadTestHook.reset()
            val next = scope.async { MediaLoadTestHook.parkBeforeCommit(1) }
            withTimeout(5_000) { next.await() }
            assertEquals(-1, MediaLoadTestHook.parkedRows())
        } finally {
            MediaLoadTestHook.reset()
        }
    }

    @Test
    fun resetInFinallyReleasesCancelledParkedScan() = runBlocking {
        MediaLoadTestHook.armOnce()
        val scan = scope.async { MediaLoadTestHook.parkBeforeCommit(2) }
        try {
            awaitParked(2)
            scan.cancel()
            assertEquals(null, withTimeoutOrNull(200) { scan.join(); true })
        } finally {
            MediaLoadTestHook.reset()
        }
        withTimeout(5_000) { scan.join() }
        assertTrue(scan.isCancelled)
        assertEquals(-1, MediaLoadTestHook.parkedRows())
    }

    @Test
    fun oldResetDoesNotReleaseNewlyArmedGate() = runBlocking {
        MediaLoadTestHook.armOnce()
        val old = scope.async { MediaLoadTestHook.parkBeforeCommit(3) }
        try {
            awaitParked(3)
            MediaLoadTestHook.reset()
            MediaLoadTestHook.armOnce()
            val next = scope.async { MediaLoadTestHook.parkBeforeCommit(4) }
            awaitParked(4)
            withTimeout(5_000) { old.await() }
            assertFalse("Old gate release must not release the new gate", next.isCompleted)
            assertEquals(null, withTimeoutOrNull(200) { next.join(); true })
            MediaLoadTestHook.release()
            withTimeout(5_000) { next.await() }
        } finally {
            MediaLoadTestHook.reset()
        }
    }
}
