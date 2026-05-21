package org.moontechlab.selenetv.screen

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
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavDestination.Companion.hasRoute
import kotlinx.coroutines.launch
import org.moontechlab.selenetv.screen.tab.*
import org.moontechlab.selenetv.ui.AnimatedTabContent
import org.moontechlab.selenetv.ui.TabSwitcher
import org.moontechlab.selenetv.ui.TextMeasureProvider

@Composable
fun HomeScreen(navController: androidx.navigation.NavController) {
    val context = LocalContext.current
    
    // 当前选中的标签页
    var selectedTabId by remember { mutableStateOf("home") }

    var homeRefreshKey by remember { mutableIntStateOf(0) }

    // 每次返回 HomeRoute 时自增，让 Tab 重拉数据
    LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.collect { entry ->
            if (entry.destination.hasRoute<HomeRoute>()) {
                homeRefreshKey++
            }
        }
    }

    // 为每个 Tab 创建 FocusRequester (指向各 Tab 的分类筛选器)
    val searchFocusRequester = remember { FocusRequester() }
    val homeTabFocusRequester = remember { FocusRequester() }
    val movieCategoryFocusRequester = remember { FocusRequester() }
    val tvCategoryFocusRequester = remember { FocusRequester() }
    val animeCategoryFocusRequester = remember { FocusRequester() }
    val showCategoryFocusRequester = remember { FocusRequester() }
    val liveFocusRequester = remember { FocusRequester() }
    val settingsFocusRequester = remember { FocusRequester() }
    
    // TabSwitcher 的 FocusRequester
    val tabSwitcherFocusRequester = remember { FocusRequester() }
    
    // 协程作用域
    val coroutineScope = rememberCoroutineScope()
    
    // 共享的滚动状态，用于让 TabSwitcher 跟随滚动
    val scrollState = rememberScrollState()
    
    // 追踪焦点是否在 TabSwitcher 上
    var isTabSwitcherFocused by remember { mutableStateOf(true) }
    
    // 当焦点不在 TabSwitcher 时，拦截返回键，滚动到顶部并聚焦 TabSwitcher
    BackHandler(enabled = !isTabSwitcherFocused) {
        coroutineScope.launch {
            scrollState.animateScrollTo(0)
        }
        tabSwitcherFocusRequester.requestFocus()
    }
    
    // 当焦点在 TabSwitcher 且不在首页时，拦截返回键，切换到首页
    BackHandler(enabled = isTabSwitcherFocused && selectedTabId != "home") {
        selectedTabId = "home"
    }
    
    // 创建渐变背景
    val gradientBrush = Brush.verticalGradient(
        0.0f to Color(0xFF0a0a0a),
        0.4f to Color(0xFF1a1a1a),
        0.7f to Color(0xFF0f0f0f),
        1.0f to Color(0xFF050505)
    )
    
    TextMeasureProvider {
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
                            "search" -> searchFocusRequester.requestFocus()
                            "home" -> homeTabFocusRequester.requestFocus()
                            "movies" -> movieCategoryFocusRequester.requestFocus()
                            "series" -> tvCategoryFocusRequester.requestFocus()
                            "anime" -> animeCategoryFocusRequester.requestFocus()
                            "show" -> showCategoryFocusRequester.requestFocus()
                            "live" -> liveFocusRequester.requestFocus()
                            "settings" -> settingsFocusRequester.requestFocus()
                        }
                    },
                    onBackPressed = {
                        // 在首页 Tab 按返回键退出 app
                        (context as? Activity)?.finish()
                    },
                    focusRequester = tabSwitcherFocusRequester,
                    onFocusChanged = { hasFocus -> isTabSwitcherFocused = hasFocus }
                )
            }
        ) { currentTabId ->
            when (currentTabId) {
                "search" -> SearchTab(
                    focusRequester = searchFocusRequester,
                    scrollState = scrollState
                )
                "home" -> HomeTab(
                    focusRequester = homeTabFocusRequester,
                    scrollState = scrollState
                )
                "movies" -> MovieTab(
                    categoryFocusRequester = movieCategoryFocusRequester,
                    scrollState = scrollState
                )
                "series" -> TvTab(
                    categoryFocusRequester = tvCategoryFocusRequester,
                    scrollState = scrollState
                )
                "anime" -> AnimeTab(
                    categoryFocusRequester = animeCategoryFocusRequester,
                    scrollState = scrollState
                )
                "show" -> ShowTab(
                    categoryFocusRequester = showCategoryFocusRequester,
                    scrollState = scrollState
                )
                "live" -> LiveTab(
                    focusRequester = liveFocusRequester,
                    scrollState = scrollState
                )
                "settings" -> key(homeRefreshKey) {
                    SettingsTab(
                        focusRequester = settingsFocusRequester,
                        scrollState = scrollState,
                        onShowLoginScreen = {
                            navController.navigate(LoginRoute)
                        }
                    )
                }
            }
        }
        }
    }
    
}

@Preview(device = Devices.TV_1080p)
@Composable
fun HomeScreenPreview() {
    HomeScreen(navController = androidx.navigation.compose.rememberNavController())
}
