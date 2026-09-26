package org.satelliteeavesdropper.app.receiver

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Test

class ReceiverSessionCoordinatorTest {
    @Test fun newestSessionWaitsForOldUsbCleanupEvenIfIntermediateStartIsCanceled() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sessions = ReceiverSessionCoordinator(scope)
        val firstStarted = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val allowCleanup = CompletableDeferred<Unit>()
        val intermediateStarted = CompletableDeferred<Unit>()
        val newestStarted = CompletableDeferred<Unit>()
        try {
            sessions.replace {
                firstStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanupStarted.complete(Unit)
                        allowCleanup.await()
                    }
                }
            }
            withTimeout(2_000) { firstStarted.await() }
            sessions.replace { intermediateStarted.complete(Unit) }
            withTimeout(2_000) { cleanupStarted.await() }
            val newest = sessions.replace { newestStarted.complete(Unit) }
            assertFalse(intermediateStarted.isCompleted)
            assertFalse(newestStarted.isCompleted)
            allowCleanup.complete(Unit)
            withTimeout(2_000) { newestStarted.await(); newest.join() }
            assertFalse(intermediateStarted.isCompleted)
        } finally {
            allowCleanup.complete(Unit)
            sessions.cancel()
            scope.cancel()
        }
    }

    @Test fun newServiceInstanceWaitsForOldUsbCleanup() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val oldService = ReceiverSessionCoordinator(scope)
        val newService = ReceiverSessionCoordinator(scope)
        val firstStarted = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val allowCleanup = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        try {
            oldService.replace {
                firstStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanupStarted.complete(Unit)
                        allowCleanup.await()
                    }
                }
            }
            withTimeout(2_000) { firstStarted.await() }
            oldService.cancel()
            withTimeout(2_000) { cleanupStarted.await() }
            val second = newService.replace { secondStarted.complete(Unit) }
            assertFalse(secondStarted.isCompleted)
            allowCleanup.complete(Unit)
            withTimeout(2_000) { secondStarted.await(); second.join() }
        } finally {
            allowCleanup.complete(Unit)
            oldService.cancel()
            newService.cancel()
            scope.cancel()
        }
    }
}
