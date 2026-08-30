package com.huimao.map.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme as WearMaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viewModel = WearNavigationViewModel(applicationContext)
        setContent { WearNavigationScreen(viewModel) }
    }
}

@Composable
private fun WearNavigationScreen(viewModel: WearNavigationViewModel) {
    val state by viewModel.state.collectAsState()
    val phoneConnected by viewModel.phoneConnected.collectAsState()
    WearMaterialTheme {
        AppScaffold {
            if (state.navigating) {
                NavigationPage(state)
            } else {
                WaitingPage(phoneConnected, viewModel::refreshConnection)
            }
        }
    }
}

@Composable
private fun WaitingPage(connected: Boolean, onRetry: () -> Unit) {
    ScreenScaffold(timeText = { TimeText() }, contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("●", fontSize = 30.sp, color = WearMaterialTheme.colorScheme.primary)
            Text("请在手机端开始导航", fontSize = 21.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
            Text(if (connected) "等待导航信息同步" else "请先连接手机", fontSize = 14.sp, textAlign = TextAlign.Center, color = WearMaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            if (!connected) {
                Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                    Text("手机", fontSize = 12.sp, color = WearMaterialTheme.colorScheme.primary)
                    Text("检查连接")
                }
            }
        }
    }
}

@Composable
private fun NavigationPage(state: WearNavigationState) {
    ScreenScaffold(timeText = { TimeText() }, contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp)) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text("灰猫地图", fontSize = 12.sp, color = WearMaterialTheme.colorScheme.onSurfaceVariant)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(modifier = Modifier.size(76.dp).background(WearMaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.ArrowBack, contentDescription = state.instruction, tint = WearMaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(48.dp))
                }
                Text(state.instruction, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                Text("${state.distanceToTurnMeters} 米后", fontSize = 18.sp, color = WearMaterialTheme.colorScheme.primary)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("当前道路", fontSize = 11.sp, color = WearMaterialTheme.colorScheme.onSurfaceVariant)
                    Text(state.roadName.ifBlank { "—" }, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("剩余", fontSize = 11.sp, color = WearMaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatDistance(state.remainingDistanceMeters), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

private fun formatDistance(meters: Int): String = when {
    meters <= 0 -> "—"
    meters >= 1000 -> "%.1f 公里".format(meters / 1000f)
    else -> "$meters 米"
}
