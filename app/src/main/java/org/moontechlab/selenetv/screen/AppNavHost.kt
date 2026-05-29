package org.moontechlab.selenetv.screen

import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.moontechlab.selenetv.service.AppDataService
import org.moontechlab.selenetv.ui.MoonToastHost

@Composable
fun AppNavHost() {
    val navController = rememberNavController()

    // 未登录时直接以登录页作为启动首屏（强制登录），已登录则进首页
    val startDestination = remember {
        if (AppDataService.isLoggedInSync() || AppDataService.isLocalModeSync()) HomeRoute else LoginRoute
    }

    Box(modifier = Modifier.fillMaxSize()) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable<HomeRoute>(
            enterTransition = {
                fadeIn(animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f)) +
                        scaleIn(initialScale = 0.95f, animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f))
            },
            exitTransition = {
                fadeOut(animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f)) +
                        scaleOut(targetScale = 0.95f, animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f))
            },
        ) {
            HomeScreen(navController = navController)
        }
        composable<LoginRoute>(
            enterTransition = {
                fadeIn(animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f)) +
                        scaleIn(initialScale = 0.8f, animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f))
            },
            exitTransition = {
                fadeOut(animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f)) +
                        scaleOut(targetScale = 0.8f, animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f))
            },
        ) {
            LoginScreen(navController = navController)
        }
        // 详情页不再是独立的导航目的地，而是作为覆盖层渲染在 HomeScreen 之上
        // （见 HomeScreen 的 detailRoute overlay），以保持首页组合存活、数据与焦点不丢失。
    }

        // 启动时检查应用更新（命中且通过判定链才弹窗；失败静默）
        UpdateGate()

        // 全局自绘 Toast：渲染在所有页面/覆盖层之上，底部居中
        MoonToastHost(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 64.dp),
        )
    }
}
