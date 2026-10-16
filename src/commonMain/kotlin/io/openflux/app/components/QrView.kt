package io.openflux.app.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.openflux.desktop.service.PlatformServices

/** A QR code of [text], black on white whatever the theme (scanners want it so), at most 320 dp wide. */
@Composable
fun QrView(text: String, platform: PlatformServices, modifier: Modifier = Modifier) {
    val matrix = remember(text) { platform.qrMatrix(text) }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(16.dp)
                .semantics { contentDescription = "QR-код ссылки" },
        ) {
            val n = matrix.size
            if (n == 0) return@Canvas
            val cell = size.width / n
            for (y in 0 until n) for (x in 0 until matrix[y].size) {
                if (matrix[y][x]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
