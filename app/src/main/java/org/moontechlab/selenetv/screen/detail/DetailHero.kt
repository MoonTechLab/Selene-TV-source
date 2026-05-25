package org.moontechlab.selenetv.screen.detail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import org.moontechlab.selenetv.screen.DetailEntry
import org.moontechlab.selenetv.util.getImageUrlSync

@Composable
fun DetailHero(
    entry: DetailEntry,
    info: HeroInfo?,
    actionsRow: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    summaryFocusRequester: FocusRequester? = null,
    onSummaryClick: () -> Unit = {},
) {
    // info 可能字段为空，标题/海报回退到 entry
    val title = info?.title?.takeIf { it.isNotBlank() } ?: entry.title
    val poster = info?.poster?.takeIf { it.isNotEmpty() } ?: entry.poster

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(start = 58.dp, top = 60.dp, end = 48.dp, bottom = 40.dp),
    ) {
        Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(end = 36.dp)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.W700,
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                info?.genres?.forEach { genre ->
                    Text(
                        text = genre,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 14.sp,
                    )
                }
                info?.rate?.let { rate ->
                    Text(
                        text = "★ $rate",
                        color = Color(0xFFE91E63),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.W700,
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            info?.subLine?.takeIf { it.isNotEmpty() }?.let { sub ->
                Text(
                    text = sub,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 13.sp,
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            info?.summary?.takeIf { it.isNotEmpty() }?.let { summary ->
                if (summaryFocusRequester != null) {
                    SummaryBlock(
                        text = summary,
                        onClick = onSummaryClick,
                        focusRequester = summaryFocusRequester,
                        modifier = Modifier.offset(x = (-12).dp),
                    )
                } else {
                    Text(
                        text = summary,
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        maxLines = 4,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f).height(24.dp))

            actionsRow()
        }

        Box(
            modifier = Modifier
                .size(width = 200.dp, height = 300.dp)
                .shadow(
                    elevation = 16.dp,
                    shape = RoundedCornerShape(8.dp),
                    ambientColor = Color.Black.copy(alpha = 0.5f),
                    spotColor = Color.Black.copy(alpha = 0.5f),
                )
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF2A2A2A)),
        ) {
            AsyncImage(
                model = getImageUrlSync(poster),
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/**
 * 可聚焦的简介块：静态时是一段截断正文，聚焦后以弹簧动画浮成玻璃面（背景/描边渐显、正文提亮、
 * 轻微放大）。焦点进入/移出走同一条 progress 弹簧曲线，故移出时反向收回。
 * 作为左列最上方的可聚焦元素，向上取消焦点搜索，避免逃逸到覆盖层下的首页。
 */
@Composable
internal fun SummaryBlock(
    text: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 320f),
        label = "summary_focus",
    )
    val shape = RoundedCornerShape(10.dp)
    val containerColor = Color.White.copy(alpha = 0.08f * progress)
    val borderColor = Color.White.copy(alpha = 0.25f * progress)
    Surface(
        onClick = onClick,
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .focusProperties { up = FocusRequester.Cancel }
            .graphicsLayer {
                val s = 1f + 0.015f * progress
                scaleX = s
                scaleY = s
            },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = containerColor,
            focusedContainerColor = containerColor,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, borderColor), shape = shape),
            focusedBorder = Border(BorderStroke(1.dp, borderColor), shape = shape),
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = text,
                color = Color.White.copy(alpha = 0.75f + 0.20f * progress),
                fontSize = 14.sp,
                lineHeight = 22.sp,
                maxLines = 4,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}
