package org.moontechlab.selenetv.screen.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import org.moontechlab.selenetv.screen.DetailEntry

/**
 * Apple TV 式详情 hero：全幅剧照(backdrop) + 底部/左侧渐变压暗 + 左下信息栈。
 * 仅在拿到 TMDB 剧照时启用（[HeroInfo.backdrop] 非空）；否则用 [DetailHero] 旧布局。
 *
 * 标题用 TMDB logo 艺术字（[HeroInfo.logo]），缺失时回退文字标题。
 * 简介/评分/类型等均来自豆瓣，与旧布局一致；简介块复用 [SummaryBlock]，焦点行为不变。
 */
@Composable
fun DetailHeroV2(
    entry: DetailEntry,
    info: HeroInfo?,
    actionsRow: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    sourceName: String? = null,
    summaryFocusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    onSummaryClick: () -> Unit = {},
) {
    val title = info?.title?.takeIf { it.isNotBlank() } ?: entry.title
    val backdrop = info?.backdrop
    val logo = info?.logo

    // hero 取屏高的 ~96%，底部仅露出「播放源」极窄一条，提示可下滑。
    // 注意：MainActivity.adjustDensity() 把全局 density 重写为「画布固定 1080dp 宽」，但
    // config.screenHeightDp 仍以系统原始 density 计量，与本应用其余 dp 不是同一把尺子——
    // 直接乘会在真机(尤其 4K)上把 hero 撑到远超可视高度，底部动作按钮被顶出屏外。
    // 宽高比与 density 无关，故用「1080dp 画布宽 × (高/宽比)」换算回本应用 dp 才正确。
    // 再以 heightIn(min) 承载：内容更高时自适应增高、随滚动可达，绝不裁切按钮。
    val config = LocalConfiguration.current
    val canvasHeightDp = 1080f * (config.screenHeightDp.toFloat() / config.screenWidthDp.toFloat())
    val heroMinHeight = (canvasHeightDp * 0.96f).dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = heroMinHeight),
    ) {
        // 全幅剧照：底部用 DstIn 蒙版把图本身渐隐为透明，露出页面背景渐变，
        // 与下方「播放源」同底无缝衔接（不压成纯黑，避免与源列表背景出现段差）。
        if (backdrop != null) {
            AsyncImage(
                model = backdrop,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.verticalGradient(
                                0.0f to Color.Black,
                                0.5f to Color.Black,
                                1.0f to Color.Transparent,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            )
        }
        // 左侧渐变：保证左下文字/ logo 可读
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.horizontalGradient(
                        0.0f to Color(0xCC0A0A0A),
                        0.5f to Color.Transparent,
                    ),
                ),
        )

        // 左下信息栈
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.62f)
                .padding(start = 58.dp, end = 36.dp, bottom = 40.dp),
        ) {
            if (logo != null) {
                AsyncImage(
                    model = logo,
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomStart,
                    modifier = Modifier
                        .heightIn(max = 132.dp)
                        .widthIn(max = 440.dp),
                )
            } else {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.W700,
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                info?.rate?.let { rate ->
                    Text(
                        text = "★ $rate",
                        color = Color(0xFFE91E63),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.W700,
                    )
                }
                info?.genres?.take(3)?.forEach { genre ->
                    Text(
                        text = genre,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 14.sp,
                    )
                }
            }

            // 副信息行：年份/产地/时长 + 播放源（源名并入此处，避免在按钮区单独成块而突兀）
            val metaLine = listOfNotNull(
                info?.subLine?.takeIf { it.isNotEmpty() },
                sourceName?.takeIf { it.isNotBlank() },
            ).joinToString(" · ").takeIf { it.isNotEmpty() }
            metaLine?.let { line ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = line,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 13.sp,
                )
            }

            info?.summary?.takeIf { it.isNotEmpty() }?.let { summary ->
                Spacer(modifier = Modifier.height(12.dp))
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
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            actionsRow()
        }
    }
}
