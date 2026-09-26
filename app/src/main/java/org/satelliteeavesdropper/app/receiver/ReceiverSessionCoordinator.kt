package org.satelliteeavesdropper.app.receiver

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes USB ownership across rapid target changes and receiver restarts. */
internal class ReceiverSessionCoordinator(private val scope: CoroutineScope) {
    private var current: Job? = null

    fun replace(session: suspend CoroutineScope.() -> Unit): Job {
        current?.cancel()
        return scope.launch {
            // A canceled waiter leaves the queue, but a running session keeps
            // the lock through its nativeStopRx/nativeDestroy cleanup.
            usbOwner.withLock { session(this) }
        }.also { current = it }
    }

    fun cancel() {
        current?.cancel()
    }

    private companion object {
        // Service destruction can race with a new Service instance while the
        // old canceled worker is still closing its native stream.
        val usbOwner = Mutex()
    }
}
