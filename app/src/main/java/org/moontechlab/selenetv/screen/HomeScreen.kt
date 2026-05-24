package org.moontechlab.selenetv.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavDestination.Companion.hasRoute
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.moontechlab.selenetv.model.AggregatedSearchResult
import org.moontechlab.selenetv.model.VideoInfo
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

    // 详情页作为覆盖层渲染（而非导航目的地），首页组合保持存活 → 数据与焦点不丢失。
    var detailEntry by remember { mutableStateOf<DetailEntry?>(null) }

    // 详情关闭后把焦点确定性还原到来源卡片
    val detailReturn = remember { DetailReturnFocusState() }

    // 每次返回 HomeRoute 时自增，让 Tab 重拉数据（例如从 Login 返回）
    // drop(1) 跳过初始发射，避免启动时 homeRefreshKey 被 0→1 触发 HomeTab 二次加载
    LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow
            .drop(1)
            .collect { entry ->
                if (entry.destination.hasRoute<HomeRoute>()) {
                    homeRefreshKey++
                }
            }
    }

    val openDetail: (VideoInfo) -> Unit = remember {
        { video ->
            detailReturn.setTarget("${video.source}+${video.id}")
            detailEntry = detailEntryFor(video)
        }
    }

    val openDetailFromSearch: (AggregatedSearchResult) -> Unit = remember {
        { agg ->
            val firstSource = agg.originalResults.firstOrNull()?.source ?: ""
            detailReturn.setTarget("$firstSource+${agg.key}")
            detailEntry = DetailEntry.SearchAggregate(agg)
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

    // 关闭详情覆盖层后用于把焦点还原到来源卡片：包裹全部 Tab 内容，
    // 其 focusRestorer 会恢复上次聚焦的后代（即用户点进详情前的那张卡片）。
    val contentRestoreFocusRequester = remember { FocusRequester() }
    
    // 协程作用域
    val coroutineScope = rememberCoroutineScope()
    
    // 共享的滚动状态，用于让 TabSwitcher 跟随滚动
    val scrollState = rememberScrollState()
    
    // 追踪焦点是否在 TabSwitcher 上
    var isTabSwitcherFocused by remember { mutableStateOf(true) }
    
    // 当焦点不在 TabSwitcher 时，拦截返回键，滚动到顶部并聚焦 TabSwitcher
    // （详情覆盖层打开时由 DetailScreen 自己的 BackHandler 处理，这里禁用）
    BackHandler(enabled = detailEntry == null && !isTabSwitcherFocused) {
        coroutineScope.launch {
            scrollState.animateScrollTo(0)
        }
        tabSwitcherFocusRequester.requestFocus()
    }

    // 当焦点在 TabSwitcher 且不在首页时，拦截返回键，切换到首页
    BackHandler(enabled = detailEntry == null && isTabSwitcherFocused && selectedTabId != "home") {
        selectedTabId = "home"
    }
    
    // 创建渐变背景
    val gradientBrush = Brush.verticalGradient(
        0.0f to Color(0xFF0a0a0a),
        0.4f to Color(0xFF1a1a1a),
        0.7f to Color(0xFF0f0f0f),
        1.0f to Color(0xFF050505)
    )

    // 关闭详情覆盖层后，把焦点确定性还原到来源卡片（来源卡片挂了 detailReturn.requester）；
    // 若来源卡片已不在组合中（例如所在行被回收），回退到内容包裹层的 focusRestorer。
    val restoreActiveTabFocus: () -> Unit = {
        try {
            detailReturn.requester.requestFocus()
        } catch (_: Exception) {
            try { contentRestoreFocusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    val closeDetail: () -> Unit = {
        detailEntry = null
        homeRefreshKey++
    }

    // 覆盖层关闭后（仅在曾打开过详情时），待滑出动画推进再恢复首页焦点。
    // 避免在 App 启动时（detailRoute 初始即为 null）抢走 TabSwitcher 的初始焦点。
    var hadDetailOpen by remember { mutableStateOf(false) }
    LaunchedEffect(detailEntry) {
        if (detailEntry != null) {
            hadDetailOpen = true
        } else if (hadDetailOpen) {
            hadDetailOpen = false
            delay(300) // 等覆盖层滑出动画(280ms)结束、DetailScreen 释放焦点后再恢复
            restoreActiveTabFocus()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    // 仅在首页内容内提供还原状态（不含详情覆盖层，避免播放源卡片误匹配）
    CompositionLocalProvider(LocalDetailReturnFocus provides detailReturn) {
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(contentRestoreFocusRequester)
                    .focusGroup()
                    .focusRestorer(),
            ) {
            when (currentTabId) {
                "search" -> SearchTab(
                    focusRequester = searchFocusRequester,
                    scrollState = scrollState,
                    onOpenDetail = openDetailFromSearch,
                )
                "home" -> HomeTab(
                    focusRequester = homeTabFocusRequester,
                    scrollState = scrollState,
                    onVideoSelected = openDetail,
                    homeRefreshKey = homeRefreshKey,
                )
                "movies" -> MovieTab(
                    categoryFocusRequester = movieCategoryFocusRequester,
                    scrollState = scrollState,
                    onVideoSelected = openDetail,
                )
                "series" -> TvTab(
                    categoryFocusRequester = tvCategoryFocusRequester,
                    scrollState = scrollState,
                    onVideoSelected = openDetail,
                )
                "anime" -> AnimeTab(
                    categoryFocusRequester = animeCategoryFocusRequester,
                    scrollState = scrollState,
                    onVideoSelected = openDetail,
                )
                "show" -> ShowTab(
                    categoryFocusRequester = showCategoryFocusRequester,
                    scrollState = scrollState,
                    onVideoSelected = openDetail,
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
    }

        // 详情覆盖层：从右侧滑入、滑出（#2）。首页内容保持在底层不被销毁（#3）。
        // 在动画退出期间保留最后一次的 route，避免内容提前消失。
        val retainedDetailEntry = remember { mutableStateOf<DetailEntry?>(null) }
        detailEntry?.let { retainedDetailEntry.value = it }
        AnimatedVisibility(
            visible = detailEntry != null,
            enter = slideInHorizontally(
                animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                initialOffsetX = { fullWidth -> fullWidth },
            ),
            exit = slideOutHorizontally(
                animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                targetOffsetX = { fullWidth -> fullWidth },
            ),
        ) {
            retainedDetailEntry.value?.let { entry ->
                DetailScreen(entry = entry, onBack = closeDetail)
            }
        }
    }
}

@Preview(device = Devices.TV_1080p)
@Composable
fun HomeScreenPreview() {
    HomeScreen(navController = androidx.navigation.compose.rememberNavController())
}
