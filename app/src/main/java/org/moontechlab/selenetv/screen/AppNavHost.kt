package org.moontechlab.selenetv.screen

import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

@Composable
fun AppNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = HomeRoute,
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
}
