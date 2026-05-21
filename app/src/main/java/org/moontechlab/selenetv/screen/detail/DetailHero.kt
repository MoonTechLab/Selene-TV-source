package org.moontechlab.selenetv.screen.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
    val title = details?.title ?: route.title
    val poster = details?.poster?.takeIf { it.isNotEmpty() } ?: route.poster

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 58.dp, top = 60.dp, end = 48.dp, bottom = 40.dp),
    ) {
        // 左列
        Column(modifier = Modifier.weight(1f).padding(end = 36.dp)) {
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
                        fontSize = 11.sp,
                    )
                }
                details?.rate?.let { rate ->
                    if (rate.isNotEmpty() && rate != "0.0") {
                        Text(
                            text = "★ $rate",
                            color = Color(0xFFE91E63),
                            fontSize = 14.sp,
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
                    fontSize = 11.sp,
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            details?.summary?.takeIf { it.isNotEmpty() }?.let { summary ->
                Text(
                    text = summary,
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

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
