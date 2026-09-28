package org.satelliteeavesdropper.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Available offline, without granting location or USB access. */
@Composable
internal fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var document by remember { mutableStateOf<String?>(null) }
    val content = remember(document) {
        document?.let { path -> context.assets.open("legal/$path").bufferedReader().use { it.readText() } }
    }
    OrbitAdaptiveDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (document == null) "About OrbitScope" else document!!.removeSuffix(".txt")) },
        text = {
            if (content == null) Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState())) {
                Text("OrbitScope ${BuildConfig.VERSION_NAME}\nExperimental satellite tracking and receive-only SDR tools.")
                TextButton(onClick = { document = "Privacy.txt" }) { Text("Privacy policy") }
                TextButton(onClick = { document = "NOTICE.txt" }) { Text("Licenses and data attribution") }
                TextButton(onClick = { document = "THIRD_PARTY_NOTICES.txt" }) { Text("Dependency notices") }
                listOf("GPL-3.0.txt", "GPL-2.0.txt", "LGPL-2.1.txt", "Apache-2.0.txt", "HackRF-BSD.txt", "AndroidX-graphics-path.txt").forEach { name ->
                    TextButton(onClick = { document = name }) { Text(name.removeSuffix(".txt")) }
                }
            } else SelectionContainer {
                Text(content, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 450.dp).verticalScroll(rememberScrollState()))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = if (document != null) ({ TextButton(onClick = { document = null }) { Text("Back") } }) else null,
    )
}
