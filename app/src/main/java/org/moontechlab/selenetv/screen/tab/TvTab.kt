package org.moontechlab.selenetv.screen.tab

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

@Composable
fun TvTab() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "剧集",
            fontSize = 32.sp,
            color = Color.White
        )
    }
}

@Preview(device = Devices.TV_1080p)
@Composable
fun TvTabPreview() {
    TvTab()
}
