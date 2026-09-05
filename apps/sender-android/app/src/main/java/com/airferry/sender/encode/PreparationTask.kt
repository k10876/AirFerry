package com.airferry.sender.encode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Main-thread owner, independent of the currently composed pane. */
class PreparationTask(private val scope: CoroutineScope) {
    private var job: Job? = null
    private var generation = 0L

    fun cancel() {
        // Invalidate callbacks before cancellation can run an old finally block.
        generation++
        job?.cancel()
        job = null
    }

    fun <T> start(
        prepare: suspend () -> T,
        onReady: (T) -> Unit,
        onError: (Exception) -> Unit,
        onFinished: () -> Unit
    ) {
        cancel()
        val token = generation
        job = scope.launch {
            try {
                val result = prepare()
                // JNI compression cannot be interrupted; never publish its stale result.
                currentCoroutineContext().ensureActive()
                if (token == generation) onReady(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (token == generation) onError(e)
            } finally {
                if (token == generation) onFinished()
            }
        }
    }
}
