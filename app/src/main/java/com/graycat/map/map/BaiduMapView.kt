package com.graycat.map.map

import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.BitmapDescriptorFactory
import com.baidu.mapapi.map.MapStatus
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.MapView
import com.baidu.mapapi.map.MyLocationConfiguration
import com.baidu.mapapi.map.MyLocationData
import com.baidu.mapapi.map.PolylineOptions
import com.baidu.mapapi.model.LatLngBounds
import com.baidu.mapapi.model.LatLng as BaiduLatLng
import com.graycat.map.model.LatLng
import com.graycat.map.model.Place
import com.graycat.map.model.RouteResult

private const val TAG = "BaiduMapView"

@Composable
fun BaiduMapView(
    modifier: Modifier = Modifier,
    currentLocation: LatLng? = null,
    destination: Place? = null,
    route: RouteResult? = null,
    layerType: MapLayerType = MapLayerType.NORMAL
) {
    var mapView by remember { mutableStateOf<MapView?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            try {
                when (event) {
                    Lifecycle.Event.ON_RESUME -> mapView?.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView?.onPause()
                    else -> Unit
                }
            } catch (e: Throwable) {
                Log.w(TAG, "MapView lifecycle $event failed: ${e.message}")
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(
        factory = { ctx ->
            try {
                MapView(ctx).also { mv ->
                    mapView = mv
                    try { mv.onResume() } catch (_: Throwable) {}
                    mv.map?.apply {
                        isMyLocationEnabled = true
                        setMyLocationConfiguration(
                            MyLocationConfiguration(
                                MyLocationConfiguration.LocationMode.NORMAL,
                                true,
                                null
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "MapView creation failed: ${e.message}", e)
                MapView(ctx)
            }
        },
        modifier = modifier,
        update = { mv ->
            try {
                val baiduMap = mv.map ?: return@AndroidView

                // 设置地图类型
                baiduMap.mapType = when (layerType) {
                    MapLayerType.SATELLITE -> BaiduMap.MAP_TYPE_SATELLITE
                    else -> BaiduMap.MAP_TYPE_NORMAL
                }

                // 绘制百度路线规划返回的真实道路几何，并让视野包含整条路线。
                route?.pathPoints?.takeIf { it.size >= 2 }?.let { path ->
                    baiduMap.clear()
                    val points = path.map { BaiduLatLng(it.latitude, it.longitude) }
                    baiduMap.addOverlay(
                        PolylineOptions().points(points).width(14f)
                            .color(0xFF1689FF.toInt()).zIndex(10)
                    )
                    val builder = LatLngBounds.Builder()
                    points.forEach { builder.include(it) }
                    baiduMap.animateMapStatus(
                        MapStatusUpdateFactory.newLatLngBounds(builder.build())
                    )
                }

                // 更新定位蓝点
                currentLocation?.let { loc ->
                    if (loc.latitude != 0.0 && loc.longitude != 0.0) {
                        val locData = MyLocationData.Builder()
                            .latitude(loc.latitude)
                            .longitude(loc.longitude)
                            .accuracy(10f)
                            .build()
                        baiduMap.setMyLocationData(locData)

                        // 路线预览保持整条路线视野；没有路线时才跟随当前位置。
                        if (route?.pathPoints.isNullOrEmpty()) {
                            val bdLoc = BaiduLatLng(loc.latitude, loc.longitude)
                            val status = MapStatus.Builder()
                                .target(bdLoc)
                                .zoom(15f)
                                .build()
                            baiduMap.animateMapStatus(MapStatusUpdateFactory.newMapStatus(status))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "MapView update failed: ${e.message}", e)
            }
        }
    )

    DisposableEffect(Unit) {
        onDispose {
            try {
                mapView?.onDestroy()
            } catch (e: Exception) {
                Log.w(TAG, "MapView destroy failed: ${e.message}")
            }
        }
    }
}
