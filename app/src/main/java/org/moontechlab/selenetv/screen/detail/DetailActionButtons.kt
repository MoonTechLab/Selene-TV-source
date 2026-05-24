package org.moontechlab.selenetv.screen.detail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

// 焦点态 = 黑白反色：聚焦时白底黑字，非聚焦时半透明白底白字
private val FocusedBg = Color.White
private val FocusedFg = Color(0xFF0A0A0A)
private val UnfocusedBg = Color.White.copy(alpha = 0.15f)
private val UnfocusedFg = Color.White
private val ButtonHeight = 44.dp
private val ButtonShape = RoundedCornerShape(22.dp)

@Composable
fun DetailActionButtons(
    showContinue: Boolean,
    showPlay: Boolean,
    showDelete: Boolean,
    showFavorite: Boolean,
    isFavorite: Boolean,
    sourceName: String?,
    onContinue: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavorite: () -> Unit,
    primaryFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    upTarget: FocusRequester? = null,
) {
    Row(
        // 向上焦点：有简介块时上键聚焦到它，否则取消搜索，避免焦点逃逸到覆盖层下的首页（#4）
        modifier = modifier
            .fillMaxWidth()
            .focusProperties { up = upTarget ?: FocusRequester.Cancel },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showContinue) {
            PillActionButton(
                text = "继续播放",
                onClick = onContinue,
                modifier = Modifier.focusRequester(primaryFocusRequester),
            )
        }
        if (showPlay) {
            PillActionButton(
                text = "播放",
                onClick = onPlay,
                modifier = if (showContinue) Modifier else Modifier.focusRequester(primaryFocusRequester),
            )
        }
        if (showDelete) {
            IconActionButton(
                icon = Icons.Default.Delete,
                contentDescription = "删除播放记录",
                onClick = onDelete,
            )
        }
        if (showFavorite) {
            IconActionButton(
                icon = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (isFavorite) "取消收藏" else "收藏",
                onClick = onToggleFavorite,
                modifier = if (!showContinue && !showPlay) Modifier.focusRequester(primaryFocusRequester) else Modifier,
            )
        }

        if (sourceName != null) {
            Spacer(modifier = Modifier.weight(1f))
            SourceNameLabel(name = sourceName)
        }
    }
}

@Composable
private fun PillActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.05f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "pill_scale",
    )
    val fg = if (focused) FocusedFg else UnfocusedFg
    Surface(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(ButtonHeight),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(shape = ButtonShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = UnfocusedBg,
            focusedContainerColor = FocusedBg,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxHeight().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(20.dp),
            )
            Text(text = text, color = fg, fontSize = 15.sp, fontWeight = FontWeight.W600)
        }
    }
}

@Composable
private fun IconActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.05f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "icon_scale",
    )
    Surface(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(ButtonHeight),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(shape = ButtonShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = UnfocusedBg,
            focusedContainerColor = FocusedBg,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (focused) FocusedFg else UnfocusedFg,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SourceNameLabel(name: String) {
    Box(
        modifier = Modifier
            .border(0.5.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = name,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
            fontWeight = FontWeight.W500,
        )
    }
}
