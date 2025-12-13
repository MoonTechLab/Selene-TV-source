package org.moontechlab.selenetv.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/**
 * 标签页数据类
 */
data class TabItem(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val isIconOnly: Boolean = false
)

/**
 * TV 应用顶部标签页切换器
 * 
 * @param selectedTabId 当前选中的标签页 ID
 * @param onTabSelected 标签页选中回调
 * @param onNavigateDown 向下导航回调（根据当前标签页）
 * @param onBackPressed 返回键回调
 * @param modifier 修饰符
 */
@Composable
fun TabSwitcher(
    selectedTabId: String,
    onTabSelected: (String) -> Unit,
    onNavigateDown: (String) -> Unit,
    onBackPressed: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
    onFocusChanged: (Boolean) -> Unit = {}
) {
    // 内置标签页列表
    val tabs = remember {
        listOf(
            TabItem("search", "搜索", Icons.Default.Search, isIconOnly = true),
            TabItem("home", "首页", Icons.Default.Home),
            TabItem("movies", "电影", Icons.Default.Movie),
            TabItem("series", "剧集", Icons.Default.Tv),
            TabItem("anime", "动漫", Icons.Default.Pets),
            TabItem("show", "综艺", Icons.Default.Stars),
            TabItem("live", "直播", Icons.Default.RadioButtonChecked),
            TabItem("settings", "设置", Icons.Default.Settings)
        )
    }
    
    // 焦点请求器，用于控制初始焦点
    val focusRequesters = remember { tabs.map { FocusRequester() } }
    
    // 获取当前选中 tab 的 FocusRequester，用于 focusRestorer
    val selectedTabIndex = remember(selectedTabId) { tabs.indexOfFirst { it.id == selectedTabId } }
    val selectedTabFocusRequester = if (selectedTabIndex >= 0) focusRequesters[selectedTabIndex] else focusRequesters[0]
    
    // 当前聚焦的标签 ID（用于自动切换逻辑）
    var focusedTabId by remember { mutableStateOf<String?>(null) }
    
    // TabSwitcher 是否有任何焦点（任何 tab 被聚焦）
    val hasAnyFocus = focusedTabId != null
    
    // 是否正在切换标签页（500ms 延迟期间）
    var isSwitching by remember { mutableStateOf(false) }
    
    // 记录是否曾经因按下键而失焦
    var hadLostFocusByDown by remember { mutableStateOf(false) }
    
    // 自动切换逻辑：等待动画完成后再计时 500ms
    LaunchedEffect(focusedTabId) {
        if (focusedTabId != null && focusedTabId != selectedTabId) {
            isSwitching = true
            delay(600)
            if (focusedTabId != null && focusedTabId != selectedTabId) {
                onTabSelected(focusedTabId!!)
            }
            isSwitching = false
        } else {
            isSwitching = false
        }
    }
    
    // 应用启动时，焦点默认在当前激活的标签上
    LaunchedEffect(selectedTabId) {
        val selectedIndex = tabs.indexOfFirst { it.id == selectedTabId }
        if (selectedIndex >= 0) {
            focusRequesters[selectedIndex].requestFocus()
        }
    }
    
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp, start = 24.dp, end = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .shadow(
                    elevation = 14.dp,
                    shape = RoundedCornerShape(22.dp),
                    spotColor = Color.Black.copy(alpha = 0.3f)
                )
                .background(
                    color = Color.White.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(22.dp)
                )
                .padding(2.4.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { focusState ->
                    // 通知父组件焦点状态变化
                    onFocusChanged(focusState.hasFocus)
                    
                    // 当 focusGroup 获得焦点（hasFocus 但非 isFocused）且之前因按下键失焦过
                    if (focusState.hasFocus && !focusState.isFocused && hadLostFocusByDown) {
                        hadLostFocusByDown = false
                        val idx = tabs.indexOfFirst { it.id == selectedTabId }
                        if (idx >= 0) {
                            focusRequesters[idx].requestFocus()
                        }
                    }
                }
                .focusGroup()
                .focusRestorer(selectedTabFocusRequester),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, tab ->
                TabButton(
                    tab = tab,
                    isSelected = tab.id == selectedTabId,
                    hasAnyFocus = hasAnyFocus,
                    focusRequester = focusRequesters[index],
                    isFirstTab = index == 0,
                    isLastTab = index == tabs.lastIndex,
                    isSwitching = isSwitching,
                    onFocusChanged = { isFocused ->
                        focusedTabId = if (isFocused) tab.id else null
                    },
                    onTabClick = { onTabSelected(tab.id) },
                    onNavigateDown = {
                        hadLostFocusByDown = true
                        onNavigateDown(selectedTabId)
                    },
                    onBackPressed = {
                        // 在标签栏按返回键时，如果当前不是首页，则自动切换到首页标签
                        if (selectedTabId != "home") {
                            onTabSelected("home")
                        } else {
                            onBackPressed()
                        }
                    }
                )
            }
        }
    }
}

/**
 * 单个标签按钮
 */
@Composable
private fun TabButton(
    tab: TabItem,
    isSelected: Boolean,
    hasAnyFocus: Boolean,
    focusRequester: FocusRequester,
    isFirstTab: Boolean,
    isLastTab: Boolean,
    isSwitching: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    onTabClick: () -> Unit,
    onNavigateDown: () -> Unit,
    onBackPressed: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    
    // 聚焦时的放大动画，使用弹簧效果 (friction: 8, tension: 100)
    // dampingRatio ≈ friction/2√(mass*stiffness), stiffness ≈ tension
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,  // 0.5f，更接近 0.4
            stiffness = Spring.StiffnessLow                   // 200f，或者用 100f
        ),
        label = "tab_scale"
    )
    
    // 背景颜色和内容颜色
    val backgroundColor = when {
        isFocused -> Color.White // 聚焦：纯白色
        isSelected && !hasAnyFocus -> Color.White.copy(alpha = 0.2f) // 选中且整个 TabSwitcher 无焦点：半透明白色
        else -> Color.Transparent // 默认：透明
    }
    
    val contentColor = if (isFocused) Color(0xFF0a0a0a) else Color.White
    
    Surface(
        onClick = onTabClick,
        modifier = Modifier
            .height(31.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { focusState ->
                isFocused = focusState.isFocused
                onFocusChanged(focusState.isFocused)
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.key) {
                        // 下方向键：移动到内容区域（切换期间锁定）
                        Key.DirectionDown, Key.NavigateNext -> {
                            if (!isSwitching) {
                                onNavigateDown()
                            }
                            true
                        }
                        // 上方向键：边界陷阱，不允许向上移出（切换期间也锁定）
                        Key.DirectionUp, Key.NavigatePrevious -> {
                            true // 消费事件，阻止向上移动
                        }
                        // 左右方向键：边界陷阱（只在边界时消费事件）
                        Key.DirectionLeft -> {
                            isFirstTab // 如果是第一个标签，消费事件阻止移出；否则允许正常导航
                        }
                        Key.DirectionRight -> {
                            isLastTab // 如果是最后一个标签，消费事件阻止移出；否则允许正常导航
                        }
                        // 返回键由 BackHandler 处理，这里不消费
                        Key.Back, Key.Escape -> false
                        else -> false
                    }
                } else {
                    false
                }
            },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(16.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = backgroundColor,
            focusedContainerColor = backgroundColor,
            pressedContainerColor = backgroundColor
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f) // 禁用默认缩放，使用自定义动画
    ) {
        Row(
            modifier = Modifier
                .padding(
                    horizontal = if (tab.isIconOnly) 10.dp else 13.dp,
                    vertical = 0.dp
                )
                .fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标
            Icon(
                imageVector = tab.icon,
                contentDescription = tab.label,
                modifier = Modifier.size(14.dp),
                tint = contentColor
            )
            
            // 文字（搜索标签仅显示图标）
            if (!tab.isIconOnly) {
                Text(
                    text = tab.label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, // 600
                    color = contentColor
                )
            }
        }
    }
}

@Preview(device = Devices.TV_1080p)
@Composable
fun TabSwitcherPreview() {
    var selectedTab by remember { mutableStateOf("home") }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1a1a1a))
    ) {
        TabSwitcher(
            selectedTabId = selectedTab,
            onTabSelected = { tabId -> selectedTab = tabId },
            onNavigateDown = { /* 根据当前标签导航到对应内容区域 */ },
            onBackPressed = { /* 处理返回 */ }
        )
    }
}
