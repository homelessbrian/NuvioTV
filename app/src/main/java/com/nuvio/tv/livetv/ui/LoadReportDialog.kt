package com.nuvio.tv.livetv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.model.LiveTvLoadReport
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Developer tool: what Live TV did while loading this time (saved copies used or not and why,
 * with timings), plus a QR code so a user can send it from their phone.
 */
@Composable
fun LoadReportDialog(onDismiss: () -> Unit) {
    val lines by LiveTvLoadReport.lines.collectAsStateWithLifecycle()
    val text = lines.joinToString("\n")
    val qr = remember(text) {
        runCatching { com.nuvio.tv.core.qr.QrCodeGenerator.generate(text.takeLast(1_700).ifBlank { "(empty)" }, 360) }.getOrNull()
    }
    LiveDialog(onDismiss = onDismiss, width = 860.dp) {
        LiveText("Live TV start-up report", size = 20.sp, weight = FontWeight.Bold)
        LiveText(
            "Since the app started. Scan the code to send it from your phone.",
            color = NuvioTheme.colors.TextSecondary, size = 13.sp
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LazyColumn(modifier = Modifier.weight(1f).heightIn(max = 380.dp)) {
                if (lines.isEmpty()) item { LiveText("Nothing yet: open Live TV first.", size = 13.sp) }
                items(lines) { line ->
                    androidx.tv.material3.Text(
                        text = line, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        color = NuvioTheme.colors.TextPrimary
                    )
                }
            }
            qr?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(180.dp).background(Color.White).padding(6.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        MenuItem("Close", onClick = onDismiss)
    }
}
