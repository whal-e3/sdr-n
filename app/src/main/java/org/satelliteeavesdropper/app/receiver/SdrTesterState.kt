package org.satelliteeavesdropper.app.receiver

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Retain tester evidence during activity recreation; hardware jobs stay activity-owned. */
internal object SdrTesterState {
    var snapshot by mutableStateOf<ReceptionSnapshot?>(null)
    var message by mutableStateOf<String?>(null)
    var running by mutableStateOf(false)
}
