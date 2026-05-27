package org.moontechlab.selenetv.screen.detail

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
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
private val Accent = Color(0xFF4CAF50)
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
    continueEpisode: Int? = null,
    continueProgress: Float = 0f,
    // 当前源无播放记录、但其他源有可迁移记录时显示「迁移记录」。点击打开记录选择弹窗。
    showMigrate: Boolean = false,
    onMigrate: () -> Unit = {},
    // 详情 hero 已出但播放源仍在解析：无任何动作按钮可承接焦点。此时在主按钮槽位放一个等高的
    // 加载占位胶囊（承接 primaryFocus），既给焦点落点/明确反馈，又预留行高，源就绪后原位替换、零跳变。
    sourcesLoading: Boolean = false,
) {
    val hasAnyAction = showContinue || showPlay || showFavorite
    Row(
        // 向上焦点：有简介块时上键聚焦到它，否则取消搜索，避免焦点逃逸到覆盖层下的首页（#4）
        modifier = modifier
            .fillMaxWidth()
            // 恒定预留行高：占位胶囊→真实按钮的切换之间可能闪过一帧「两者都不渲染」（如 sourcesComplete
            // 先于派生 sources 落定的瞬态），若不锁高，行会塌成 0、把上方内容拽下又弹回，造成跳变。
            .heightIn(min = ButtonHeight)
            .focusProperties { up = upTarget ?: FocusRequester.Cancel },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!hasAnyAction && sourcesLoading) {
            LoadingActionPill(modifier = Modifier.focusRequester(primaryFocusRequester))
        }
        if (showContinue) {
            ContinueActionButton(
                episode = continueEpisode,
                progress = continueProgress,
                onClick = onContinue,
                modifier = Modifier.focusRequester(primaryFocusRequester),
            )
        }
        if (showPlay) {
            // 有续看记录时，普通播放按钮语义变为「从头播放」以区分「继续」
            PillActionButton(
                text = if (showContinue) "从头播放" else "播放",
                onClick = onPlay,
                modifier = if (showContinue) Modifier else Modifier.focusRequester(primaryFocusRequester),
            )
        }
        if (showMigrate) {
            // 无记录时占位于「播放」之后；播放按钮仍承接 primaryFocus，本按钮为次级动作
            PillActionButton(
                text = "迁移记录",
                icon = Icons.Default.SwapHoriz,
                onClick = onMigrate,
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

/**
 * 加载占位胶囊：与真实动作按钮同属一族——等高/同形/同底色(UnfocusedBg)，仅聚焦态不做黑白反色而是
 * 轻微提亮，暗示「加载中、暂不可操作」。前导用一个极简的旋转细弧(而非全屏国风月牙)，与简洁胶囊一致。
 * 点击无操作；源解析完成后由上层换成真实按钮，原位无跳变。
 */
@Composable
private fun LoadingActionPill(modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.05f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "loading_pill_scale",
    )
    val textColor = Color.White.copy(alpha = if (focused) 0.95f else 0.6f)
    Surface(
        onClick = {},
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(ButtonHeight),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(shape = ButtonShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = UnfocusedBg,
            // 聚焦不做黑白反色（区别于可操作按钮），只提亮一档，表明仍在加载
            focusedContainerColor = Color.White.copy(alpha = 0.3f),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxHeight().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MoonCrescentSpinner(modifier = Modifier.size(18.dp))
            Text(text = "正在加载播放源", color = textColor, fontSize = 15.sp, fontWeight = FontWeight.W600)
        }
    }
}

/**
 * 月神国风加载：仅中间月牙匀速旋转 + 一层轻柔光晕，去掉 [MoonSpinner] 的外环星点/巡天亮星，
 * 适配按钮内联小尺寸。月牙路径与 MoonSpinner 同源（外圆 45% + 内椭圆 32%×45% 挖出缺口）。
 */
@Composable
internal fun MoonCrescentSpinner(modifier: Modifier = Modifier, color: Color = Color(0xFFFAFAFA)) {
    val infinite = rememberInfiniteTransition(label = "moon_pill")
    val rotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "moon_pill_rot",
    )
    Canvas(modifier = modifier.graphicsLayer { rotationZ = rotation }) {
        val w = size.width
        val h = size.height
        val outerR = w * 0.45f
        val innerRx = w * 0.32f
        val innerRy = h * 0.45f
        val cx = w / 2f
        val cy = h / 2f
        val path = Path().apply {
            arcTo(
                rect = Rect(Offset(cx - outerR, cy - outerR), Size(outerR * 2, outerR * 2)),
                startAngleDegrees = -90f, sweepAngleDegrees = 180f, forceMoveTo = true,
            )
            arcTo(
                rect = Rect(Offset(cx - innerRx, cy - innerRy), Size(innerRx * 2, innerRy * 2)),
                startAngleDegrees = 90f, sweepAngleDegrees = -180f, forceMoveTo = false,
            )
            close()
        }
        drawIntoCanvas { canvas ->
            val paint = Paint().apply {
                this.color = color
                asFrameworkPaint().apply {
                    isAntiAlias = true
                    setShadowLayer(3f, 0f, 0f, color.copy(alpha = 0.47f).toArgb())
                }
            }
            canvas.drawPath(path, paint)
        }
    }
}

@Composable
private fun PillActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.PlayArrow,
) {
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
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(20.dp),
            )
            Text(text = text, color = fg, fontSize = 15.sp, fontWeight = FontWeight.W600)
        }
    }
}

/**
 * 「继续播放」按钮：在标准胶囊按钮基础上，集数并入文案（继续 · 第N集，总集>1 才有），
 * 底边嵌一条绿色进度细线（由 Surface 裁到胶囊形状，两端自然圆角）。
 * 无记录时上层直接渲染普通「播放」按钮，本按钮不出现——动作行高度恒定，切换源不跳变。
 */
@Composable
private fun ContinueActionButton(
    episode: Int?,
    progress: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.05f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "continue_scale",
    )
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 220f),
        label = "continue_progress",
    )
    val fg = if (focused) FocusedFg else UnfocusedFg
    val label = if (episode != null) "继续 · 第 $episode 集" else "继续播放"
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
        Box(modifier = Modifier.fillMaxHeight()) {
            // Row 决定按钮宽度（贴合文案）
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
                Text(text = label, color = fg, fontSize = 15.sp, fontWeight = FontWeight.W600)
            }
            // 进度细线：用 matchParentSize 贴合按钮实际宽度（而非整行约束），
            // 否则 fillMaxWidth(fraction) 会按整行宽度撑大按钮。两端由 Surface 裁到胶囊形。
            if (animatedProgress > 0f) {
                Box(modifier = Modifier.matchParentSize(), contentAlignment = Alignment.BottomStart) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(animatedProgress)
                            .height(3.dp)
                            .background(Accent),
                    )
                }
            }
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
