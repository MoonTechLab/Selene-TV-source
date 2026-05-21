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
    }
}
