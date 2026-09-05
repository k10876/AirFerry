package com.airferry.sender.encode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreparationTaskTest {
    @Test fun preparationSurvivesReviewPaneRemoval() = runTest {
        val activityScope = CoroutineScope(coroutineContext + Job())
        val paneScope = CoroutineScope(coroutineContext + Job())
        val task = PreparationTask(activityScope)
        var result = ""
        var encoding = true
        task.start(
            prepare = { delay(100); "prepared" },
            onReady = { result = it },
            onError = { throw AssertionError(it) },
            onFinished = { encoding = false }
        )
        runCurrent()
        paneScope.cancel() // Review -> Encoding removes only the pane's scope.
        advanceUntilIdle()
        assertEquals("prepared", result)
        assertFalse(encoding)
        activityScope.cancel()
    }

    @Test fun replacementDiscardsUninterruptibleOldWorkAndItsFinally() = runTest {
        val task = PreparationTask(this)
        val events = mutableListOf<String>()
        task.start(
            prepare = { withContext(NonCancellable) { delay(100); "old" } },
            onReady = { events += it },
            onError = { events += "old error" },
            onFinished = { events += "old finished" }
        )
        runCurrent()
        task.start(
            prepare = { delay(200); "new" },
            onReady = { events += it },
            onError = { events += "new error" },
            onFinished = { events += "new finished" }
        )
        advanceUntilIdle()
        assertEquals(listOf("new", "new finished"), events)
    }

    @Test fun destructionCannotStartPlaybackOrReportCancellationAsError() = runTest {
        val activityScope = CoroutineScope(coroutineContext + Job())
        val task = PreparationTask(activityScope)
        var published = false
        var error = false
        task.start(
            prepare = { withContext(NonCancellable) { delay(100) } },
            onReady = { published = true },
            onError = { error = true },
            onFinished = {}
        )
        runCurrent()
        activityScope.cancel()
        advanceUntilIdle()
        assertFalse(published)
        assertFalse(error)
    }

    @Test fun explicitCancelSuppressesAllStaleCallbacks() = runTest {
        val task = PreparationTask(this)
        val events = mutableListOf<String>()
        task.start(
            prepare = { withContext(NonCancellable) { delay(100); error("late failure") } },
            onReady = { events += "ready" },
            onError = { events += "error" },
            onFinished = { events += "finished" }
        )
        runCurrent()
        task.cancel()
        advanceUntilIdle()
        assertTrue(events.isEmpty())
    }

    @Test fun cancellationIsNotAUserError() = runTest {
        val task = PreparationTask(this)
        var finished = false
        task.start(
            prepare = { throw CancellationException("The coroutine scope left the composition") },
            onReady = { throw AssertionError("must not publish") },
            onError = { throw AssertionError("must not display cancellation", it) },
            onFinished = { finished = true }
        )
        advanceUntilIdle()
        assertTrue(finished)
    }

    @Test fun realPreparationFailureIsReportedAndFinishes() = runTest {
        val task = PreparationTask(this)
        val events = mutableListOf<String>()
        task.start(
            prepare = { throw IllegalArgumentException("missing staged file") },
            onReady = { throw AssertionError("must not publish") },
            onError = { events += it.message!! },
            onFinished = { events += "finished" }
        )
        advanceUntilIdle()
        assertEquals(listOf("missing staged file", "finished"), events)
    }
}
