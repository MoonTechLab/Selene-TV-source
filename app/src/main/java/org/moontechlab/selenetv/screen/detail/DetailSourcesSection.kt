package org.moontechlab.selenetv.screen.detail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
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
import org.moontechlab.selenetv.model.PlayRecord
import org.moontechlab.selenetv.model.SearchResult
import org.moontechlab.selenetv.model.SourceSpeed
import org.moontechlab.selenetv.model.SpeedTestState
import org.moontechlab.selenetv.model.VideoInfo
import org.moontechlab.selenetv.service.SearchProgress
import org.moontechlab.selenetv.ui.SpeedTestPill
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
    // 在已选中的源卡片上再次按 OK：等同于点按主操作按钮（继续观看 / 播放 / 多集选集）。
    onPlaySelected: () -> Unit = {},
    onSearch: () -> Unit = {},
    searchFocusRequester: FocusRequester? = null,
    // 测速优选
    speedResults: Map<String, SourceSpeed> = emptyMap(),
    speedTesting: Boolean = false,
    onSpeedTest: () -> Unit = {},
    onStopSpeedTest: () -> Unit = {},
    speedTestFocusRequester: FocusRequester? = null,
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
                Box(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Text(
                            text = "没有找到可用的播放源",
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 14.sp,
                        )
                        SearchCtaButton(
                            onClick = onSearch,
                            modifier = searchFocusRequester
                                ?.let { Modifier.focusRequester(it) } ?: Modifier,
                        )
                    }
                }
            }
            sources.isEmpty() -> {
                LazyRow(
                    state = listState,
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    contentPadding = PaddingValues(horizontal = 58.dp),
                ) {
                    itemsIndexed(List(8) { it }) { _, _ -> SourceCardSkeleton() }
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
                    // 前导测速胶囊：与源卡片同一导航平面，左右键即可够到；测速中变为「停止」入口。
                    item(key = "__speedtest__", contentType = "speedtest") {
                        SpeedTestPill(
                            testing = speedTesting,
                            testedCount = speedResults.count { it.value.state != SpeedTestState.TESTING },
                            totalCount = speedResults.size,
                            onClick = { if (speedTesting) onStopSpeedTest() else onSpeedTest() },
                            modifier = speedTestFocusRequester
                                ?.let { Modifier.focusRequester(it) } ?: Modifier,
                        )
                    }
                    itemsIndexed(sources, key = { _, s -> "${s.source}+${s.id}" }) { index, result ->
                        val key = "${result.source}+${result.id}"
                        val record = playRecords[key]
                        val isSelected = selectedSourceKey == key
                        val speed = speedResults[key]
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
                                playTime = record?.playTime ?: 0L,
                                totalTime = record?.totalTime ?: 0L,
                                saveTime = 0L,
                                searchTitle = result.title,
                                doubanId = result.doubanId?.toString(),
                            ),
                            from = VideoCardSource.SOURCE,
                            // 测速进行中禁止操作；否则：未选中 → 切换为当前源；已是当前源 → 等同主播放按钮
                            onClick = {
                                if (!speedTesting) {
                                    if (isSelected) onPlaySelected() else onSelect(key)
                                }
                            },
                            isSelected = isSelected,
                            speed = speed,
                            dimmed = speedTesting && (speed == null || speed.state == SpeedTestState.TESTING),
                            // 测速完成后按分重排时，卡片平滑位移（签名交互）
                            modifier = Modifier
                                .animateItem()
                                .then(if (index == 0) Modifier.focusRequester(firstItemFocus) else Modifier),
                        )
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun SearchCtaButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.05f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "search_cta_scale",
    )
    Surface(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { scaleX = scale; scaleY = scale },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(22.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f),
            focusedContainerColor = Color(0xFF4CAF50),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "去搜索其他来源",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.W600,
            )
        }
    }
}


@Composable
private fun SourceCardSkeleton() {
    // 静态占位（取原呼吸动画 0.15~0.42 的中点）：去掉每项各自的无限动画 + 每帧重组，详见 MediaCardSkeleton
    val alpha = 0.28f
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
