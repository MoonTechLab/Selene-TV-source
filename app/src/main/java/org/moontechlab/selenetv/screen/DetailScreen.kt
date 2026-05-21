package org.moontechlab.selenetv.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.tv.material3.Text

@Composable
fun DetailScreen(navController: NavController, route: DetailRoute) {
    BackHandler { navController.popBackStack() }

    val gradientBrush = Brush.verticalGradient(
        0.0f to Color(0xFF0a0a0a),
        0.4f to Color(0xFF1a1a1a),
        0.7f to Color(0xFF0f0f0f),
        1.0f to Color(0xFF050505),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(brush = gradientBrush),
        contentAlignment = Alignment.Center,
    ) {
        Column {
            Text("DetailScreen scaffold", color = Color.White, fontSize = 24.sp)
            Text("doubanId = ${route.doubanId}", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            Text("title = ${route.title}", color = Color.White, fontSize = 14.sp)
            Text("year = ${route.year}", color = Color.White, fontSize = 14.sp)
        }
    }
}
