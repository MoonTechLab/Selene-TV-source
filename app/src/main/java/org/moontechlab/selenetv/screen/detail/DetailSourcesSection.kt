package org.moontechlab.selenetv.screen.detail

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import org.moontechlab.selenetv.model.PlayRecord
import org.moontechlab.selenetv.model.SearchResult
import org.moontechlab.selenetv.model.VideoInfo
import org.moontechlab.selenetv.service.SearchProgress
import org.moontechlab.selenetv.ui.VideoCard
import org.moontechlab.selenetv.ui.VideoCardSource

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailSourcesSection(
    sources: List<SearchResult>,
    selectedSourceKey: String?,
    playRecords: Map<String, PlayRecord>,
    progress: SearchProgress?,
    complete: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val firstItemFocus = remember { FocusRequester() }

    // 详情内容禁用了垂直 bringIntoView（会向下级联），这里给横向 LazyRow 重新提供横向滚动 spec，
    // 否则左右选源时不会把卡片滚入视野。
    val horizontalSpec = remember {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val targetOffset = containerSize * 0.35f
                return offset - targetOffset + (size / 2f)
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth().padding(top = 16.dp, bottom = 40.dp)) {
        // 标题行
        Row(
            modifier = Modifier.padding(start = 58.dp, end = 58.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "播放源",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.W700,
            )
            if (!complete && progress != null && progress.totalSources > 0) {
                Text(
                    text = "${progress.completedSources}/${progress.totalSources}",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }

        when {
            sources.isEmpty() && complete -> {
                Text(
                    text = "没有可用的播放源",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 58.dp, top = 16.dp),
                )
            }
            sources.isEmpty() -> {
                LazyRow(
                    state = listState,
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    contentPadding = PaddingValues(horizontal = 58.dp),
                ) {
                    itemsIndexed(List(6) { it }) { _, _ -> SourceCardSkeleton() }
                }
            }
            else -> {
                CompositionLocalProvider(LocalBringIntoViewSpec provides horizontalSpec) {
                LazyRow(
                    state = listState,
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    contentPadding = PaddingValues(horizontal = 58.dp),
                    modifier = Modifier
                        .focusGroup()
                        .focusRestorer(firstItemFocus),
                ) {
                    itemsIndexed(sources, key = { _, s -> "${s.source}+${s.id}" }) { index, result ->
                        val key = "${result.source}+${result.id}"
                        val record = playRecords[key]
                        val isSelected = selectedSourceKey == key
                        VideoCard(
                            videoInfo = VideoInfo(
                                id = result.id,
                                source = result.source,
                                title = result.sourceName,
                                sourceName = result.sourceName,
                                year = result.year,
                                cover = result.poster,
                                index = record?.index ?: 0,
                                totalEpisodes = result.episodes.size.coerceAtLeast(1),
                                playTime = 0L,
                                totalTime = 0L,
                                saveTime = 0L,
                                searchTitle = result.title,
                                doubanId = result.doubanId?.toString(),
                            ),
                            from = VideoCardSource.SOURCE,
                            onClick = { onSelect(key) },
                            isSelected = isSelected,
                            modifier = if (index == 0) Modifier.focusRequester(firstItemFocus) else Modifier,
                        )
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun SourceCardSkeleton() {
    val infinite = rememberInfiniteTransition(label = "src_skel")
    val alpha by infinite.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.42f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "src_skel_alpha",
    )
    Column(modifier = Modifier.width(120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .width(120.dp)
                .height(180.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = alpha)),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .width(60.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = alpha * 0.7f)),
        )
    }
}
