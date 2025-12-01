package org.moontechlab.selenetv.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import org.moontechlab.selenetv.screen.tab.*
import org.moontechlab.selenetv.ui.TabSwitcher

@Composable
fun HomeScreen() {
    // 当前选中的标签页
    var selectedTabId by remember { mutableStateOf("home") }
    
    // 创建渐变背景
    val gradientBrush = Brush.verticalGradient(
        0.0f to Color(0xFF0a0a0a),
        0.4f to Color(0xFF1a1a1a),
        0.7f to Color(0xFF0f0f0f),
        1.0f to Color(0xFF050505)
    )
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(brush = gradientBrush)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.Start
        ) {
            // 顶部标签页切换器
            TabSwitcher(
                selectedTabId = selectedTabId,
                onTabSelected = { tabId ->
                    selectedTabId = tabId
                },
                onNavigateDown = { _ ->
                    // TODO: 根据当前标签焦点移动到对应内容区域
                    // home -> 首页第一个区块标题
                    // movies/series/anime/variety -> 对应页面第一个选择器
                    // search/live/settings -> 无特定下一个焦点目标
                },
                onBackPressed = {
                    // TODO: 处理返回键逻辑
                }
            )
            
            // 根据 selectedTabId 显示对应的内容页面
            Box(modifier = Modifier.fillMaxSize()) {
                when (selectedTabId) {
                    "search" -> SearchTab()
                    "home" -> HomeTab()
                    "movies" -> MovieTab()
                    "series" -> TvTab()
                    "anime" -> AnimeTab()
                    "variety" -> ShowTab()
                    "live" -> LiveTab()
                    "settings" -> SettingsTab()
                }
            }
        }
    }
}

@Preview(device = Devices.TV_1080p)
@Composable
fun HomeScreenPreview() {
    HomeScreen()
}
