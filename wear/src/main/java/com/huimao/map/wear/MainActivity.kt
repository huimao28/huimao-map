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
import androidx.wear.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme as WearMaterialTheme
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.TimeText
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
    WearMaterialTheme {
        AppScaffold {
            ScreenScaffold(
                scrollState = androidx.compose.foundation.ScrollState(0),
                timeText = { TimeText() },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp)
            ) { contentPadding ->
                Column(
                    modifier = Modifier.fillMaxSize().padding(contentPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(if (state.navigating) "灰猫地图" else "等待手机导航", fontSize = 12.sp, color = WearMaterialTheme.colorScheme.onSurfaceVariant)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier.size(76.dp).background(WearMaterialTheme.colorScheme.primary, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "左转", tint = WearMaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(48.dp))
                        }
                        Text(if (state.navigating) state.instruction else "请在手机上开始导航", fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                        Text(if (state.navigating) "${state.distanceToTurnMeters} 米后" else "等待手机连接", fontSize = 18.sp, color = WearMaterialTheme.colorScheme.primary)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("当前道路", fontSize = 11.sp, color = WearMaterialTheme.colorScheme.onSurfaceVariant)
                            Text(state.roadName.ifBlank { "等待手机" }, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("剩余", fontSize = 11.sp, color = WearMaterialTheme.colorScheme.onSurfaceVariant)
                            Text("2.5 公里", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}
