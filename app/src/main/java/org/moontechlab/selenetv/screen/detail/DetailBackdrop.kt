package org.moontechlab.selenetv.screen.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * 详情页固定剧照层：全屏、不随内容滚动（信息栈/播放源浮在其上滚动）。从下到上四层——
 *  1) 清晰剧照；
 *  2) 低分辨率上采样得到的「软模糊」副本，由 [blur] 控制淡入；
 *  3) 压暗 scrim（随 [blur] 淡入，提升其上播放源卡片的对比度）；
 *  4) 视口锚定的可读性渐变（左暗 + 底暗），衬住浮在其上、落在首屏底部的左下信息栈。
 *
 * 不使用 Modifier.blur / RenderEffect：剧照是静态图，把同一张图解到极低分辨率再按全屏 Crop
 * 上采样，双线性插值即得柔和模糊。好处是 minSdk 23 起所有 Android 版本表现完全一致、无原生依赖、
 * 近乎零成本（只多解一张 ~64px 的小图），无需为旧设备做 no-op 降级。
 *
 * @param blur 0=清晰（焦点在动作按钮）… 1=完全模糊+压暗（焦点进入播放源）。由调用方按焦点动画驱动。
 */
@Composable
fun PinnedBackdrop(
    backdrop: String,
    blur: Float,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Box(modifier = modifier.fillMaxSize()) {
        // 1) 清晰剧照
        AsyncImage(
            model = backdrop,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // 2) 低分辨率模糊副本 + 3) 压暗 scrim：仅在需要时（blur>0）才解图/绘制
        if (blur > 0.01f) {
            AsyncImage(
                model = remember(backdrop) {
                    ImageRequest.Builder(context)
                        .data(backdrop)
                        .size(64) // 解到 ~64px，全屏 Crop 上采样 → 柔和模糊；调小更糊、调大更清
                        .build()
                },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High, // 双线性平滑上采样，避免马赛克块感
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = blur },
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = blur }
                    .background(Color.Black.copy(alpha = 0.45f)),
            )
        }

        // 4) 可读性渐变（视口锚定）：左暗衬 logo/文字、底暗衬动作按钮。blur=0 时是唯一的压暗来源。
        // 过度绘制优化：两条渐变只在各自实际压暗的区域绘制（左 55% 宽 / 底 45% 高），
        // 不再对整屏各做一次全屏混合（右上大片本就透明，无需逐像素 blend）。视觉与原先完全一致。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    // 左暗：仅覆盖左 55% 宽。渐变用画布坐标锚定（drawRect 的 shader 锚在画布原点，
                    // 不随 topLeft 偏移；这里 topLeft.x=0 本就对齐，仍显式写明以求一致）。
                    drawRect(
                        brush = Brush.horizontalGradient(
                            0.0f to Color(0xCC0A0A0A),
                            1.0f to Color.Transparent,
                            startX = 0f,
                            endX = size.width * 0.55f,
                        ),
                        topLeft = Offset.Zero,
                        size = Size(size.width * 0.55f, size.height),
                    )
                    // 底暗：仅覆盖底 45% 高。关键：startY/endY 必须用画布坐标（0.55h→h），否则 shader 从
                    // y=0 起算、跨度只到 0.45h，整条 rect 落在末端被 Clamp 成纯色暗带（之前那条难看的硬边）。
                    drawRect(
                        brush = Brush.verticalGradient(
                            0.0f to Color.Transparent,
                            1.0f to Color(0xAA000000),
                            startY = size.height * 0.55f,
                            endY = size.height,
                        ),
                        topLeft = Offset(0f, size.height * 0.55f),
                        size = Size(size.width, size.height * 0.45f),
                    )
                },
        )
    }
}
