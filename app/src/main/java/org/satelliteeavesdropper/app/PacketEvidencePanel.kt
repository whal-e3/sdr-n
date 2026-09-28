package org.satelliteeavesdropper.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.satelliteeavesdropper.app.receiver.DecodedPacket
import org.satelliteeavesdropper.app.receiver.MAX_RETAINED_DECODED_PACKETS
import org.satelliteeavesdropper.app.receiver.PacketExport
import org.satelliteeavesdropper.app.receiver.ReceptionSnapshot
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal class PacketExportActions(
    val copy: (List<DecodedPacket>) -> Unit,
    val save: (List<DecodedPacket>) -> Unit,
    val message: String?,
    val busy: Boolean,
)

/** Registered at screen level so an open document picker survives rotation and tab changes. */
@Composable
internal fun rememberPacketExportActions(): PacketExportActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Capture the exact selection at the button press. Newly arriving packets cannot change it.
    var pendingJson by rememberSaveable { mutableStateOf<String?>(null) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var writing by androidx.compose.runtime.remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PacketExport.MIME_TYPE)) { uri ->
        val document = pendingJson
        pendingJson = null
        if (uri == null) {
            message = "Save canceled. No packet file was created."
        } else if (document == null) {
            message = "Packet selection was lost. Choose the packets and save again."
        } else {
            writing = true
            scope.launch(block = {
                try {
                    withContext(Dispatchers.IO) {
                        val stream = context.contentResolver.openOutputStream(uri, "wt")
                            ?: error("The selected location could not be opened.")
                        stream.use { it.write(document.toByteArray(Charsets.UTF_8)); it.flush() }
                    }
                    message = "Packet JSON saved."
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    message = "Could not save packet JSON. Choose another location and try again."
                    // Only the new CreateDocument result is eligible for cleanup after a failed write.
                    withContext(Dispatchers.IO) {
                        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                    }
                } finally {
                    writing = false
                }
            })
        }
    }
    return PacketExportActions(
        copy = { packets ->
            if (packets.isNotEmpty()) {
                try {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("OrbitScope decoded packets", PacketExport.toJson(packets)))
                    message = "Packet JSON copied."
                } catch (error: Exception) {
                    message = "Could not copy packet JSON. Try saving it to a file."
                }
            }
        },
        save = { packets ->
            if (packets.isNotEmpty() && pendingJson == null && !writing) {
                try {
                    val now = Instant.now()
                    pendingJson = PacketExport.toJson(packets, now)
                    val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC).format(now)
                    launcher.launch("orbitscope-packets-$stamp.json")
                } catch (error: Exception) {
                    pendingJson = null
                    message = "Could not open the file picker. Try copying the packet JSON."
                }
            }
        },
        message = message,
        busy = writing || pendingJson != null,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PacketEvidencePanel(snapshot: ReceptionSnapshot, actions: PacketExportActions) {
    var showPackets by rememberSaveable { mutableStateOf(false) }
    val packets = snapshot.decodedPackets
    OrbitPanel {
        Text("CRC-CHECKED AX.25 FRAMES", color = OrbitColors.cyan,
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Text("${packets.size} packets available", style = MaterialTheme.typography.titleMedium)
        Text("The latest $MAX_RETAINED_DECODED_PACKETS packets are kept in memory. Save or copy them before closing the app or starting another session.",
            color = OrbitColors.muted)
        if (snapshot.omittedPacketCount > 0) {
            Text("${snapshot.omittedPacketCount} older packets are no longer available.", color = OrbitColors.amber)
        }
        Text("JSON includes the full decoded frame bytes and reception metadata, including observer coordinates. It is not a raw IQ recording.",
            color = OrbitColors.muted)
        packets.lastOrNull()?.let { packet ->
            Text("Latest · ${packet.dequeuedAtUtc}", style = MaterialTheme.typography.labelSmall)
            SelectionContainer { Text(packet.displayText) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { actions.copy(packets) }, enabled = packets.isNotEmpty()) { Text("Copy packets") }
            OutlinedButton(onClick = { actions.save(packets) }, enabled = packets.isNotEmpty() && !actions.busy) { Text("Save packets") }
            TextButton(onClick = { showPackets = true }, enabled = packets.isNotEmpty()) { Text("View packets") }
        }
        actions.message?.let { Text(it, color = OrbitColors.cyan) }
    }
    if (showPackets) OrbitAdaptiveDialog(
        onDismissRequest = { showPackets = false },
        title = { Text("Decoded packets") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(packets.asReversed(), key = { it.packetId }) { packet ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Packet ${packet.sequence} · ${packet.byteLength} bytes", fontWeight = FontWeight.Bold)
                        Text(packet.dequeuedAtUtc.toString(), style = MaterialTheme.typography.labelSmall)
                        SelectionContainer { Text(packet.displayText) }
                        Text("${packet.metadata.device} · ${packet.metadata.rfCenterHz} Hz")
                        Text("Tracking ${packet.metadata.targetName} (NORAD ${packet.metadata.targetNoradId}). Transmitter identity is not verified.", color = OrbitColors.muted)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { actions.copy(listOf(packet)) }) { Text("Copy packet ${packet.sequence}") }
                            OutlinedButton(onClick = { actions.save(listOf(packet)) }, enabled = !actions.busy) { Text("Save packet ${packet.sequence}") }
                        }
                    }
                }
                actions.message?.let { status -> item { Text(status, color = OrbitColors.cyan) } }
            }
        },
        confirmButton = { TextButton(onClick = { showPackets = false }) { Text("Close") } },
    )
}
