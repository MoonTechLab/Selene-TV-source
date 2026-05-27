package org.moontechlab.selenetv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import kotlinx.coroutines.delay
import org.moontechlab.selenetv.util.getImageUrlSync

/**
 * 封面缺失 / 加载失败时的兜底占位:自适应填满父级,衬在卡片现有深色背景上居中显示一个手绘
 * 「图片」图标(圆角框 + 太阳 + 山)+ 「暂无封面」文字。单色克制、手绘不引依赖,适配任意卡片尺寸/比例。
 * 自身不画背景,沿用调用方卡片底色。
 */
@Composable
fun NoCoverPlaceholder(modifier: Modifier = Modifier) {
    val tint = Color.White.copy(alpha = 0.32f)
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Selene 月牙(与品牌 MoonCrescentSpinner 同形:外半圆被内椭圆挖出),静态、不旋转。
        Canvas(modifier = Modifier.size(34.dp)) {
            val w = size.width
            val h = size.height
            val outerR = w * 0.45f
            val innerRx = w * 0.32f
            val innerRy = h * 0.45f
            val cx = w / 2f
            val cy = h / 2f
            val crescent = Path().apply {
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
            drawPath(crescent, color = tint)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "暂无封面", color = tint, fontSize = 12.sp, fontWeight = FontWeight.W500)
    }
}

/**
 * 卡片封面图:统一处理「无封面 / 加载失败 / 长时间加载不出来」的兜底。
 * - 非 http(s) URL(空、"null"、相对路径等)→ 直接占位。
 * - 加载失败(Error)→ 占位。
 * - [timeoutMs] 内仍未成功(含某些源连接挂死、无任何终止事件的情况)→ 占位。
 *
 * 不依赖 Coil/OkHttp 的超时是否生效:用 composable 级计时兜底,确保任何加载不出的情况都落到占位。
 * 已传入 [getImageUrlSync] 之前的原始 URL 即可,内部会做豆瓣 CDN 替换。
 */
@Composable
fun CoverImage(
    rawUrl: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    timeoutMs: Long = 5000,
) {
    val url = getImageUrlSync(rawUrl)
    val looksLikeUrl = url.startsWith("http://") || url.startsWith("https://")
    if (!looksLikeUrl) {
        NoCoverPlaceholder(modifier)
        return
    }
    var state by remember(url) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    var timedOut by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        delay(timeoutMs)
        timedOut = true
    }
    val isSuccess = state is AsyncImagePainter.State.Success
    val failed = state is AsyncImagePainter.State.Error || (timedOut && !isSuccess)
    Box(modifier) {
        AsyncImage(
            model = url,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            onState = { state = it },
        )
        if (failed) NoCoverPlaceholder(Modifier.fillMaxSize())
    }
}
