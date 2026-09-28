package org.satelliteeavesdropper.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Browser sessions remain in the user's browser; OrbitScope never reads login credentials. */
internal object SpaceTrackLinks {
    const val SIGN_UP = "https://www.space-track.org/auth/createAccount"
    const val LOGIN = "https://www.space-track.org/auth/login"
    const val DOCUMENTATION = "https://www.space-track.org/documentation#api-use-guidelines"
    // Space-Track recommends this filter for recent elements of objects without a decay date.
    // CSV avoids the known size problem of the unfiltered GP JSON export.
    const val CSV_EXPORT = "https://www.space-track.org/basicspacedata/query/class/gp/" +
        "DECAY_DATE/null-val/EPOCH/%3Enow-10/orderby/NORAD_CAT_ID/format/csv"
}

@Composable
internal fun SpaceTrackGuideDialog(
    onDismiss: () -> Unit,
    onImportFile: () -> Unit,
    importRunning: Boolean,
) {
    val context = LocalContext.current
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var showTroubleshooting by rememberSaveable { mutableStateOf(false) }
    fun openBrowser(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE))
            message = null
        } catch (_: ActivityNotFoundException) {
            message = "No browser is available. Copy the download link and open it on another device, then transfer the CSV file to this phone."
        }
    }
    OrbitAdaptiveDialog(
        onDismissRequest = onDismiss,
        title = { Text("Get Space-Track orbit data") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 450.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Use your own Space-Track account in your browser. OrbitScope does not store your password or connect to your browser session.")
                Text("1. Create an account", fontWeight = FontWeight.Bold)
                Text("Complete Space-Track registration and any required account approval. If you already have an account, continue to step 2.")
                OutlinedButton(onClick = { openBrowser(SpaceTrackLinks.SIGN_UP) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Sign up on Space-Track")
                }
                Text("2. Sign in", fontWeight = FontWeight.Bold)
                Text("Sign in on the Space-Track website using this phone's browser. Use the same browser for the export.")
                OutlinedButton(onClick = { openBrowser(SpaceTrackLinks.LOGIN) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Log in on Space-Track")
                }
                Text("3. Download orbital data", fontWeight = FontWeight.Bold)
                Text("Return here and open the CSV export. It requests the latest elements from the last 10 days for objects with no reported decay date; older and historical records are excluded.")
                OutlinedButton(onClick = { openBrowser(SpaceTrackLinks.CSV_EXPORT) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Open CSV export")
                }
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                        ClipData.newPlainText("Space-Track CSV export", SpaceTrackLinks.CSV_EXPORT),
                    )
                    message = "Download link copied."
                }) { Text("Copy download link") }
                message?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
                Text("Save the raw data as space-track-gp.csv in Downloads. If the browser displays text, use its download or save-file option. Do not save the login page, an HTML webpage, or a PDF.")
                Text("4. Import the downloaded file", fontWeight = FontWeight.Bold)
                Text("Tap Import downloaded file below and choose the CSV in Downloads. In Sky, choose All to browse imported records. For pass predictions, set your location and select All orbits; Radio profiles hides records without a receiver profile.")
                Text("Imported orbits are saved on this phone for tracking only. Repeat the download and import when you need newer elements; this is a manual update.")
                Text("Satellite reception still requires a current signed radio catalog. A Space-Track file supplies orbital elements, not transmitter frequencies or supported decoder profiles.",
                    color = MaterialTheme.colorScheme.secondary)
                Text("Space-Track permits GP retrieval at most once per hour. Avoid repeated downloads; importing a saved file makes no request to Space-Track.",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { showTroubleshooting = !showTroubleshooting }) {
                    Text(if (showTroubleshooting) "Hide download help" else "Download help")
                }
                if (showTroubleshooting) {
                    Text("If you see a login page, sign in and open the export again in that browser. If the export fails, check your account approval and Space-Track's API query builder: class GP, DECAY_DATE null-val, EPOCH >now-10, format CSV.")
                    Text("OrbitScope accepts GP CSV, OMM JSON and TLE text up to 64 MiB per file. The unfiltered GP JSON export may exceed this limit; use the recent CSV export above. A failed import keeps earlier imports.")
                    SelectionContainer { Text(SpaceTrackLinks.CSV_EXPORT, style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = { openBrowser(SpaceTrackLinks.DOCUMENTATION) }) { Text("Space-Track documentation") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onImportFile, enabled = !importRunning) {
                Text(if (importRunning) "Importing…" else "Import downloaded file")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
