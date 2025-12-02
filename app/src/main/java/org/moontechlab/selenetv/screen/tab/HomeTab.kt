package org.moontechlab.selenetv.screen.tab

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.moontechlab.selenetv.model.VideoInfo
import org.moontechlab.selenetv.model.toVideoInfo
import org.moontechlab.selenetv.service.ApiResponse
import org.moontechlab.selenetv.service.DoubanService
import org.moontechlab.selenetv.ui.RecommendSection
import org.moontechlab.selenetv.ui.VideoCardSource

@Composable
fun HomeTab(
    focusRequester: FocusRequester = remember { FocusRequester() }
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // 热门电影数据状态
    var hotMovies by remember { mutableStateOf<List<VideoInfo>>(emptyList()) }
    var isLoadingMovies by remember { mutableStateOf(true) }
    
    // 加载热门电影数据
    LaunchedEffect(Unit) {
        coroutineScope.launch {
            isLoadingMovies = true
            val doubanService = DoubanService.getInstance(context)
            when (val response = doubanService.getHotMovies(pageLimit = 20, page = 0)) {
                is ApiResponse.Success -> {
                    hotMovies = response.data.map { it.toVideoInfo() }
                }
                is ApiResponse.Error -> {
                    android.util.Log.e("HomeTab", "加载热门电影失败: ${response.message}")
                }
            }
            isLoadingMovies = false
        }
    }
    
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 24.dp)
            .focusRequester(focusRequester)
    ) {
        item {
            RecommendSection(
                title = "热门电影",
                videos = hotMovies,
                from = VideoCardSource.DOUBAN,
                isLoading = isLoadingMovies,
                onVideoClick = { video ->
                    // TODO: 处理视频点击事件
                    android.util.Log.d("HomeTab", "点击视频: ${video.title}")
                }
            )
        }
    }
}

@Preview(device = Devices.TV_1080p)
@Composable
fun HomeTabPreview() {
    HomeTab()
}
