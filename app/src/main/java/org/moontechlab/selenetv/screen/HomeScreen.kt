package org.moontechlab.selenetv.screen

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import org.moontechlab.selenetv.screen.tab.*
import org.moontechlab.selenetv.ui.AnimatedTabContent
import org.moontechlab.selenetv.ui.TabSwitcher

@Composable
fun HomeScreen() {
    // 当前选中的标签页
    var selectedTabId by remember { mutableStateOf("home") }
    
    // 为每个 Tab 创建 FocusRequester
    val homeTabFocusRequester = remember { FocusRequester() }
    
    // 共享的滚动状态，用于让 TabSwitcher 跟随滚动
    val scrollState = rememberScrollState()
    
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
        // 根据 selectedTabId 显示对应的内容页面（带动画）
        // TabSwitcher 作为 header 不参与动画，但会跟随滚动
        AnimatedTabContent(
            selectedTabId = selectedTabId,
            scrollState = scrollState,
            modifier = Modifier.fillMaxSize(),
            header = {
                TabSwitcher(
                    selectedTabId = selectedTabId,
                    onTabSelected = { tabId -> selectedTabId = tabId },
                    onNavigateDown = { currentTab ->
                        when (currentTab) {
                            "home" -> homeTabFocusRequester.requestFocus()
                        }
                    },
                    onBackPressed = {
                        // TODO: 处理返回键逻辑
                    }
                )
            }
        ) { currentTabId ->
            when (currentTabId) {
                "search" -> SearchTab(scrollState = scrollState)
                "home" -> HomeTab(
                    focusRequester = homeTabFocusRequester,
                    scrollState = scrollState
                )
                "movies" -> MovieTab(scrollState = scrollState)
                "series" -> TvTab(scrollState = scrollState)
                "anime" -> AnimeTab(scrollState = scrollState)
                "variety" -> ShowTab(scrollState = scrollState)
                "live" -> LiveTab(scrollState = scrollState)
                "settings" -> SettingsTab(scrollState = scrollState)
            }
        }
    }
}

@Preview(device = Devices.TV_1080p)
@Composable
fun HomeScreenPreview() {
    HomeScreen()
}
