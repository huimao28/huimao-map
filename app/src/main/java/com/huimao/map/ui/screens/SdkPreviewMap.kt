package com.huimao.map.ui.screens

import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.navisdk.adapter.BaiduNaviManagerFactory
import com.huimao.map.model.LatLng

/**
 * 路线预览页的地图：白马地图同款画法。
 *
 * 不再用自绘折线的 MapPlaceholder，而是把导航 SDK 自带的地图 attach 进页面容器，
 * 算路成功后 SDK 自己把路线画在这张图上（带实时路况）。底部卡片仍由我们自己画，
 * 只绑 getRoutePlanInfo() 的数据。
 *
 * 注意：
 * - SDK 的 MapView 是进程内单例对象，attach/detach 只是换父容器，画好的路线层
 *   不会因为换容器而丢失；所以算路与 attach 不用严格排队，update 里补 attach 即可。
 * - 点地图上的路线切方案：setRouteClickedListener 每次 update 都重注册一次，
 *   避免长生命周期的监听器抓着已 dispose 的组合状态。
 * - 算路完成前先把地图中心移到当前位置，避免停在 SDK 默认北京。
 */
@Composable
fun SdkPreviewMap(
    modifier: Modifier = Modifier,
    currentLocation: LatLng?,
    routesEmpty: Boolean,
    onRouteClicked: (Int) -> Unit
) {
    val context = LocalContext.current
    var centeredOnce by remember { mutableStateOf(false) }
    var wasEmpty by remember { mutableStateOf(true) }

    AndroidView(
        modifier = modifier,
        factory = { ctx -> FrameLayout(ctx) },
        update = { container ->
            try {
                val mgr = BaiduNaviManagerFactory.getMapManager()
                val mapView = mgr?.mapView
                // AndroidView 的 update 参数不支持 return@update 这种标签返回
                //（CI 报错 Unresolved label），改用 if 包住整段逻辑。
                if (mgr != null && mapView != null) {
                if (mapView.parent !== container) {
                    mgr.attach(container)
                    ensureRouteResultCreated(context)
                    try {
                        BaiduNaviManagerFactory.getRouteResultManager()?.onResume()
                    } catch (_: Throwable) {
                    }
                }
                // 地图点选路线 → 切方案（白马同款）。
                try {
                    BaiduNaviManagerFactory.getRouteResultManager()?.setRouteClickedListener { index ->
                        onRouteClicked(index)
                    }
                } catch (_: Throwable) {
                }
                // 新算出的路线第一次出现时全览一次；之后用户自己缩放平移不再打扰。
                if (wasEmpty && !routesEmpty) {
                    try {
                        BaiduNaviManagerFactory.getRouteResultManager()?.fullView()
                    } catch (_: Throwable) {
                    }
                }
                wasEmpty = routesEmpty
                // 算路完成前先把地图中心移到当前位置，避免停在 SDK 默认北京。
                if (!centeredOnce && routesEmpty) {
                    val loc = currentLocation?.takeIf { it.latitude != 0.0 && it.longitude != 0.0 }
                    if (loc != null) {
                        try {
                            mapView.map?.animateMapStatus(
                                MapStatusUpdateFactory.newLatLngZoom(
                                    com.baidu.mapapi.model.LatLng(loc.latitude, loc.longitude), 16f
                                )
                            )
                            centeredOnce = true
                        } catch (_: Throwable) {
                        }
                    }
                }
                }
            } catch (_: Throwable) {
            }
        },
        onRelease = { container ->
            try {
                BaiduNaviManagerFactory.getMapManager()?.detach(container)
            } catch (_: Throwable) {
            }
        }
    )
}

/** 路线结果管理器 onCreate 只需调一次（白马在路线页初始化时调），失败则忽略。 */
private var routeResultCreated = false

private fun ensureRouteResultCreated(context: android.content.Context) {
    if (routeResultCreated) return
    try {
        BaiduNaviManagerFactory.getRouteResultManager()?.onCreate(context)
        routeResultCreated = true
    } catch (_: Throwable) {
    }
}
