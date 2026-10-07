package org.satelliteeavesdropper.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.satelliteeavesdropper.app.data.SatelliteRecord

/** Text and color distinguish decoder configuration without implying successful reception. */
@Composable
internal fun DecoderSupportBadge(record: SatelliteRecord, modifier: Modifier = Modifier) {
    val support = remember(record) { decoderSupport(record) }
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.testTag("decoder-support-${record.noradId}"),
        shape = RoundedCornerShape(12.dp),
        color = if (support.canDecode) colors.primaryContainer else colors.surfaceVariant,
        contentColor = if (support.canDecode) colors.onPrimaryContainer else colors.onSurfaceVariant,
        border = BorderStroke(1.dp, if (support.canDecode) colors.primary else colors.outlineVariant),
    ) {
        Text(support.summaryLabel, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}
