package org.moontechlab.selenetv.screen.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import org.moontechlab.selenetv.service.danmaku.DanmakuRenderSettings
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import org.moontechlab.selenetv.service.DanmakuService.SourceMatch

/** 弹幕显示区域选项。key 与 AppDataService 持久化值一致（off/third/half/full）。 */
data class DanmakuAreaOption(val key: String, val label: String)

val DanmakuAreaOptions: List<DanmakuAreaOption> = listOf(
    DanmakuAreaOption("off", "关闭"),
    DanmakuAreaOption("third", "1/3 屏"),
    DanmakuAreaOption("half", "半屏"),
    DanmakuAreaOption("full", "全屏"),
)

fun danmakuAreaLabel(key: String): String = DanmakuAreaOptions.firstOrNull { it.key == key }?.label ?: "关闭"

/** 区域 → 弹幕层占屏高度比例（off=0 不显示）。 */
fun danmakuAreaFraction(key: String): Float = when (key) {
    "third" -> 0.34f
    "half" -> 0.5f
    "full" -> 1f
    else -> 0f
}

/**
 * 控制条上的「弹幕」双段胶囊：一颗药丸内分两个可聚焦段——
 *  - **开关段**（主，[toggleFocus]）：指示点 + 文案，OK 即时开/关弹幕（不暂停、不弹窗）。
 *  - **齿轮段**（[gearFocus]）：⚙ 图标，OK 打开设置弹窗（暂停做预览）。
 *
 * 文案：开「弹幕 · {源}」/「弹幕」，关「弹幕 · 关」。指示点 ●绿（开）/ ○灰（关）。
 * 焦点：两段用 LEFT/RIGHT 互切；开关段左 [leftTarget]、齿轮段右 [rightTarget]、两段下 scrubber、上 [upTarget]。
 * 整颗药丸聚焦时统一放大（不分段缩放，避免相邻段重叠/抖动）。
 */
@Composable
fun DanmakuPillDuo(
    label: String,
    enabled: Boolean,
    onToggle: () -> Unit,
    onOpenSettings: () -> Unit,
    toggleFocus: FocusRequester,
    gearFocus: FocusRequester,
    scrubberFocus: FocusRequester,
    rightTarget: FocusRequester,
    leftTarget: FocusRequester? = null,
    upTarget: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    var toggleFocused by remember { mutableStateOf(false) }
    var gearFocused by remember { mutableStateOf(false) }
    val anyFocused = toggleFocused || gearFocused
    val scale by animateFloatAsState(
        targetValue = if (anyFocused) 1.04f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "danmaku_duo_scale",
    )
    val pillShape = RoundedCornerShape(percent = 50)
    val segShape = RoundedCornerShape(0.dp)   // 段内方角，靠外层 clip 成药丸两端圆角
    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                transformOrigin = TransformOrigin(0.5f, 0.5f)
            }
            .clip(pillShape)
            .border(1.dp, Color.White.copy(alpha = 0.3f), pillShape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 开关段（主）
        Surface(
            onClick = onToggle,
            modifier = Modifier
                .focusRequester(toggleFocus)
                .onFocusChanged { toggleFocused = it.isFocused }
                .focusProperties {
                    down = scrubberFocus
                    up = upTarget ?: FocusRequester.Cancel
                    left = leftTarget ?: FocusRequester.Cancel
                    right = gearFocus
                },
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            shape = ClickableSurfaceDefaults.shape(shape = segShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.White,
            ),
            border = ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(0.dp, Color.Transparent), shape = segShape),
                focusedBorder = Border(BorderStroke(0.dp, Color.Transparent), shape = segShape),
            ),
        ) {
            Row(
                modifier = Modifier.padding(start = 11.dp, end = 11.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DanmakuToggleDot(on = enabled, onLightSurface = toggleFocused)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = label,
                    color = when {
                        toggleFocused -> Color(0xFF0A0A0A)
                        enabled -> Color.White.copy(alpha = 0.85f)
                        else -> Color.White.copy(alpha = 0.5f)
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.W600,
                )
            }
        }
        // 分隔 hairline
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(15.dp)
                .background(Color.White.copy(alpha = 0.18f)),
        )
        // 齿轮段
        Surface(
            onClick = onOpenSettings,
            modifier = Modifier
                .focusRequester(gearFocus)
                .onFocusChanged { gearFocused = it.isFocused }
                .focusProperties {
                    down = scrubberFocus
                    up = upTarget ?: FocusRequester.Cancel
                    left = toggleFocus
                    right = rightTarget
                },
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            shape = ClickableSurfaceDefaults.shape(shape = segShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.White,
            ),
            border = ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(0.dp, Color.Transparent), shape = segShape),
                focusedBorder = Border(BorderStroke(0.dp, Color.Transparent), shape = segShape),
            ),
        ) {
            Box(modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)) {
                Text(
                    text = "⚙",
                    color = if (gearFocused) Color(0xFF0A0A0A) else Color.White.copy(alpha = 0.85f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.W600,
                )
            }
        }
    }
}

/** 弹幕开关指示点：●绿（开）/ ○灰空心（关）。切换时做一次轻量 scale 弹动。 */
@Composable
private fun DanmakuToggleDot(on: Boolean, onLightSurface: Boolean) {
    val dotScale by animateFloatAsState(
        targetValue = if (on) 1f else 0.7f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 500f),
        label = "danmaku_dot_scale",
    )
    val offColor = if (onLightSurface) Color(0xFF0A0A0A).copy(alpha = 0.45f) else Color.White.copy(alpha = 0.45f)
    Box(
        modifier = Modifier
            .size(8.dp)
            .graphicsLayer { scaleX = dotScale; scaleY = dotScale }
            .clip(CircleShape)
            .then(
                if (on) Modifier.background(Color(0xFF4CAF50))
                else Modifier.border(1.5.dp, offColor, CircleShape)
            ),
    )
}

/**
 * 弹幕设置弹窗（居中模态，背景压暗）。打开时播放页暂停、视频定格做实时预览底；关闭时按需恢复播放。
 * 左栏「弹幕源」：命中源 + 条数 + 其匹配剧集（✓=生效，OK 选用该源+该剧集）；
 * 右栏「显示与外观」：显示区域 + 不透明度/滚动速度/字号/显示密度/防重叠（分段选择，OK 即生效持久化）。
 * 独立 Popup window（focusable + BACK 关闭）。D-pad：左右跨栏、上下选项、左右切分段值。
 *
 * @param counts key → 已拉取条数；null = 尚未拉取。
 */
@Composable
fun DanmakuSettingsDialog(
    open: Boolean,
    areaKey: String,
    matches: List<SourceMatch>,
    selectedSourceKey: String?,
    counts: Map<String, Int?>,
    activeMediaByKey: Map<String, String>,
    candidateCounts: Map<String, Int>,   // "源key|mediaId" → 该段抓到的条数（一键统计填充）
    settings: DanmakuRenderSettings,
    onFetchAllCounts: () -> Unit,         // 一键抓取全部源各条目弹幕数
    onSelectArea: (String) -> Unit,
    onSelectCandidate: (String, String) -> Unit,
    onChangeSettings: (DanmakuRenderSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val transitionState = remember { MutableTransitionState(false) }
    transitionState.targetState = open
    if (!transitionState.currentState && !transitionState.targetState) return

    val centerProvider = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize,
            ): IntOffset = IntOffset(
                ((windowSize.width - popupContentSize.width) / 2).coerceAtLeast(0),
                ((windowSize.height - popupContentSize.height) / 2).coerceAtLeast(0),
            )
        }
    }
    // 初始焦点：选中源的生效剧集；无选中则首个候选；无源则落到右栏「显示区域」。
    val initialFocus = remember { FocusRequester() }
    val initialKey = selectedSourceKey ?: matches.firstOrNull()?.key
    val initialMedia = matches.firstOrNull { it.key == initialKey }
        ?.let { activeMediaByKey[it.key] ?: it.activeMediaId }

    Popup(
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true),
        popupPositionProvider = centerProvider,
    ) {
        LaunchedEffect(Unit) {
            repeat(10) {
                try { initialFocus.requestFocus(); return@LaunchedEffect }
                catch (_: Exception) { kotlinx.coroutines.delay(40) }
            }
        }
        AnimatedVisibility(
            visibleState = transitionState,
            enter = fadeIn(animationSpec = tween(180)) + scaleIn(initialScale = 0.94f, animationSpec = tween(220)),
            exit = fadeOut(animationSpec = tween(140)) + scaleOut(targetScale = 0.96f, animationSpec = tween(160)),
        ) {
            Column(
                modifier = Modifier
                    .width(740.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xF20A0A0A))
                    .padding(22.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "弹幕设置",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.W700,
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    // 一键抓取全部源各条目的弹幕数（紧跟标题；不切换当前渲染源，只统计）。
                    DanmakuChip(label = "统计各源弹幕", selected = false, onClick = onFetchAllCounts)
                }
                Row(modifier = Modifier.height(348.dp)) {
                    // 左栏：各源名称 + 其匹配剧集（不再加「弹幕源」分组标题）
                    Column(modifier = Modifier.width(288.dp).verticalScroll(rememberScrollState())) {
                        if (matches.isEmpty()) {
                            Text(
                                text = "无可用源",
                                color = Color.White.copy(alpha = 0.4f),
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        } else {
                            matches.forEach { m ->
                                val active = activeMediaByKey[m.key] ?: m.activeMediaId
                                val isSel = m.key == selectedSourceKey
                                DanmakuSourceHeader(display = m.display, count = counts[m.key], current = isSel)
                                m.candidates.forEach { cand ->
                                    val isInitial = m.key == initialKey && cand.mediaId == initialMedia
                                    DanmakuMenuItem(
                                        label = cand.title,
                                        // 统计后显示该段条数 + 加号（分段源，仅当前段）；选中态由绿点+加粗体现，不打勾。
                                        trailing = candidateCounts["${m.key}|${cand.mediaId}"]?.let { if (it > 0) "$it+" else "无" },
                                        selected = isSel && cand.mediaId == active,
                                        onClick = { onSelectCandidate(m.key, cand.mediaId) },
                                        indent = 16.dp,
                                        focusRequester = if (isInitial) initialFocus else null,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(18.dp))
                    Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.08f)))
                    Spacer(modifier = Modifier.width(18.dp))
                    // 右栏：显示区域 + 外观/行为分段设置
                    Column(
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        SettingRow("显示区域", DanmakuAreaOptions.map { it.key }, areaKey, { danmakuAreaLabel(it) },
                            firstFocus = if (matches.isEmpty()) initialFocus else null) { onSelectArea(it) }
                        SettingRow("不透明度", DanmakuRenderSettings.OPACITY_STEPS, settings.opacity, { "${(it * 100).toInt()}%" }) {
                            onChangeSettings(settings.copy(opacity = it))
                        }
                        SettingRow("滚动速度", DanmakuRenderSettings.SPEED_STEPS, settings.speed, ::danmakuSpeedLabel) {
                            onChangeSettings(settings.copy(speed = it))
                        }
                        SettingRow("字号", DanmakuRenderSettings.FONT_STEPS, settings.fontScale, ::danmakuFontLabel) {
                            onChangeSettings(settings.copy(fontScale = it))
                        }
                        SettingRow("显示密度", DanmakuRenderSettings.DENSITY_STEPS, settings.densityPct, { if (it >= 100) "全部" else "$it%" }) {
                            onChangeSettings(settings.copy(densityPct = it))
                        }
                        SettingRow("防重叠", listOf(false, true), settings.antiOverlap, { if (it) "开" else "关" }) {
                            onChangeSettings(settings.copy(antiOverlap = it))
                        }
                    }
                }
            }
        }
    }
}

private fun danmakuSpeedLabel(s: Float): String = when {
    s <= 0.5f -> "很慢"; s <= 0.75f -> "慢"; s <= 1.0f -> "标准"; s <= 1.5f -> "快"; else -> "很快"
}
private fun danmakuFontLabel(s: Float): String = when {
    s <= 0.7f -> "小"; s <= 0.85f -> "较小"; s <= 1.0f -> "标准"; s <= 1.2f -> "较大"; else -> "大"
}

/** 一行设置：左固定标签 + 右一组分段选项（chip）。选中项落初始焦点（firstFocus）。 */
@Composable
private fun <T> SettingRow(
    label: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    firstFocus: FocusRequester? = null,
    onSelect: (T) -> Unit,
) {
    Row(modifier = Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 12.sp,
            fontWeight = FontWeight.W600,
            modifier = Modifier.width(60.dp),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { opt ->
                DanmakuChip(
                    label = labelOf(opt),
                    selected = opt == selected,
                    onClick = { onSelect(opt) },
                    focusRequester = if (opt == selected) firstFocus else null,
                )
            }
        }
    }
}

/** 分段选项 chip：聚焦=白底黑字，选中=绿字绿框，常态=白 0.6 + 细框。 */
@Composable
private fun DanmakuChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Surface(
        onClick = onClick,
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.White),
        border = ClickableSurfaceDefaults.border(
            border = Border(border = BorderStroke(1.dp, if (selected) Color(0xFF4CAF50) else Color.White.copy(alpha = 0.15f)), shape = shape),
            focusedBorder = Border(border = BorderStroke(0.dp, Color.Transparent), shape = shape),
        ),
    ) {
        Box(modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp)) {
            Text(
                text = label,
                color = when { focused -> Color(0xFF0A0A0A); selected -> Color(0xFF4CAF50); else -> Color.White.copy(alpha = 0.6f) },
                fontSize = 12.sp,
                fontWeight = if (focused || selected) FontWeight.W700 else FontWeight.W500,
            )
        }
    }
}

@Composable
private fun DanmakuSectionLabel(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.4f),
        fontSize = 11.sp,
        fontWeight = FontWeight.W600,
        modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
    )
}

/** 弹幕源分组标题（不可聚焦）：源名 + 条数 + ● 当前渲染源。其下缩进列出该源匹配剧集。 */
@Composable
private fun DanmakuSourceHeader(display: String, count: Int?, current: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(6.dp).clip(CircleShape)
                .background(if (current) Color(0xFF4CAF50) else Color.Transparent),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = display,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 13.sp,
            fontWeight = FontWeight.W700,
            modifier = Modifier.weight(1f),
        )
        val countText = when { count == null -> null; count <= 0 -> "无"; else -> count.toString() }
        if (countText != null) {
            Text(
                text = countText,
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 12.sp,
                fontWeight = FontWeight.W600,
            )
        }
    }
}

@Composable
private fun DanmakuMenuItem(
    label: String,
    trailing: String?,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    indent: Dp = 0.dp,                                // 候选剧集行缩进，体现「源 ▸ 剧集」层级
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .fillMaxWidth(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.White),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp + indent, end = 12.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(6.dp).clip(CircleShape).background(
                    if (selected) { if (focused) Color(0xFF0A0A0A) else Color(0xFF4CAF50) } else Color.Transparent
                ),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = label,
                color = when { focused -> Color(0xFF0A0A0A); selected -> Color.White; else -> Color.White.copy(alpha = 0.65f) },
                fontSize = 15.sp,
                fontWeight = if (focused || selected) FontWeight.W700 else FontWeight.W500,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = trailing,
                    color = if (focused) Color(0xFF0A0A0A).copy(alpha = 0.6f) else Color.White.copy(alpha = 0.4f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.W600,
                )
            }
        }
    }
}
