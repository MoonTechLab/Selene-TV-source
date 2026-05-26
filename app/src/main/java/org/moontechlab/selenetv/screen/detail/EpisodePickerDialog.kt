package org.moontechlab.selenetv.screen.detail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/**
 * 选集弹窗。用 Dialog 建立独立焦点域（与 SummaryDialog 同理）：详情页内容 Column 在弹窗打开时放行 exit，
 * 但焦点无法跨焦点组进入详情页内的 sibling focusGroup（实测 requestFocus 夺焦失败、焦点滞留背后页面）。
 * Dialog 是独立窗口，焦点进入后被窗口天然隔离，requestFocus 到首集 chip 即生效，亦不会漏到背后页面。
 * 返回键经 Dialog 的 onDismissRequest 关闭。
 */
@Composable
fun EpisodePickerDialog(
    visible: Boolean,
    sourceName: String,
    episodes: List<String>,
    episodesTitles: List<String>?,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val firstFocus = remember { FocusRequester() }
        // 弹窗窗口挂载后把焦点落到首集；网格首项的 requester 可能晚一帧挂上，重试数帧确保夺焦成功。
        LaunchedEffect(Unit) {
            repeat(12) {
                kotlinx.coroutines.delay(30)
                try { firstFocus.requestFocus(); return@LaunchedEffect } catch (_: Exception) {}
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 900.dp)
                    .background(Color(0xFF1A1A1A).copy(alpha = 0.95f), RoundedCornerShape(16.dp))
                    .padding(32.dp),
            ) {
                Text(
                    text = "选择剧集 · $sourceName",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.W700,
                )
                Spacer(modifier = Modifier.height(16.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(8),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                ) {
                    itemsIndexed(episodes) { index, _ ->
                        val label = episodesTitles?.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: "第 ${index + 1} 集"
                        EpisodeChip(
                            label = label,
                            onClick = { onSelect(index) },
                            modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.05f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "ep_scale",
    )
    Surface(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { scaleX = scale; scaleY = scale },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f),
            focusedContainerColor = Color.White,
        ),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = if (focused) Color(0xFF0A0A0A) else Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.W500,
            )
        }
    }
}
