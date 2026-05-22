package org.moontechlab.selenetv.screen.detail

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import org.moontechlab.selenetv.model.DoubanMovieDetails
import org.moontechlab.selenetv.screen.DetailRoute
import org.moontechlab.selenetv.util.getImageUrlSync

@Composable
fun DetailHero(
    route: DetailRoute,
    details: DoubanMovieDetails?,
    actionsRow: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 详情可能因网络/反爬返回空字段对象（非 null），空标题需回退到 route 数据
    val title = details?.title?.takeIf { it.isNotBlank() } ?: route.title
    val poster = details?.poster?.takeIf { it.isNotEmpty() } ?: route.poster

    Row(
        modifier = modifier
            .fillMaxWidth()
            // 取 hero 内最高子项（海报 300dp）作为行高，使左列可 fillMaxHeight 把按钮推到底部
            .height(IntrinsicSize.Min)
            .padding(start = 58.dp, top = 60.dp, end = 48.dp, bottom = 40.dp),
    ) {
        // 左列：元信息在上、动作按钮在底部
        Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(end = 36.dp)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.W700,
            )
            Spacer(modifier = Modifier.height(8.dp))

            // chips + rating
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                details?.genres?.forEach { genre ->
                    Text(
                        text = genre,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 14.sp,
                    )
                }
                details?.rate?.let { rate ->
                    if (rate.isNotEmpty() && rate != "0.0") {
                        Text(
                            text = "★ $rate",
                            color = Color(0xFFE91E63),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.W700,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // sub 行
            val subParts = buildList {
                details?.releaseDate?.takeIf { it.isNotEmpty() }?.let { rd ->
                    val country = details.countries.firstOrNull()?.takeIf { it.isNotEmpty() }
                    if (country != null) add("$rd($country)") else add(rd)
                }
                details?.countries?.takeIf { it.isNotEmpty() }?.let { add(it.joinToString("/")) }
                details?.duration?.takeIf { it.isNotEmpty() }?.let { add(it) }
            }
            if (subParts.isNotEmpty()) {
                Text(
                    text = subParts.joinToString(" · "),
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 13.sp,
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            details?.summary?.takeIf { it.isNotEmpty() }?.let { summary ->
                Text(
                    text = summary,
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    maxLines = 4,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }

            // 弹性空白把动作按钮推到 hero 区域最底部
            Spacer(modifier = Modifier.weight(1f).height(24.dp))

            actionsRow()
        }

        // 右列：海报
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
