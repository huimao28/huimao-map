package com.huimao.map.navigation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import com.baidu.navisdk.adapter.BNRoutePlanNode
import com.baidu.navisdk.adapter.BaiduNaviManagerFactory
import com.baidu.navisdk.adapter.IBNRoutePlanManager
import com.huimao.map.model.LatLng
import com.huimao.map.model.Place
import com.huimao.map.model.RouteResult
import com.huimao.map.model.RouteStep
import com.huimao.map.model.RouteType
import com.huimao.map.model.TrafficCondition

/**
 * 预览页驾车算路：直接用导航 SDK 的 RoutePlanManager（白马地图同款流程）。
 *
 * 算路一次，预览页读 getRoutePlanInfo() 的 tab/详情/几何画界面，
 * 点选调 selectRoute，开始导航时 NaviActivity 复用这条已算好的路线。
 * 步行/骑行/公交仍走 RoutePlanSearch（导航 SDK 只做驾车）。
 */
object SdkRoutePlanner {
    private const val TAG = "SdkRoutePlanner"

    /**
     * 驾车 SDK 算路。返回本次算路的 Handler，调用方在放弃时用 [cancel] 取消。
     * onResult 拿到的是按 SDK tab 顺序映射的 RouteResult，routeId 为 "sdk-i"。
     */
    fun plan(
        context: Context,
        ak: String,
        origin: LatLng,
        dest: Place,
        preference: Int,
        onResult: (List<RouteResult>) -> Unit,
        onError: (String) -> Unit
    ): Handler {
        val handler = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                when (msg.what) {
                    IBNRoutePlanManager.MSG_NAVI_ROUTE_PLAN_SUCCESS -> {
                        try {
                            onResult(mapRoutePlanInfo(dest))
                        } catch (e: Throwable) {
                            Log.e(TAG, "mapRoutePlanInfo failed", e)
                            onError("解析路线结果失败")
                        }
                    }
                    IBNRoutePlanManager.MSG_NAVI_ROUTE_PLAN_FAILED -> onError("路线规划失败")
                    IBNRoutePlanManager.MSG_NAVI_ROUTE_PLAN_CANCELED -> Unit
                    else -> super.handleMessage(msg)
                }
            }
        }
        NaviEngineManager.ensureInitialized(
            context, ak,
            onReady = {
                // 引擎初始化回调可能不在主线程，算路调用统一贴回主线程。
                Handler(Looper.getMainLooper()).post {
                    try {
                        BaiduNaviManagerFactory.getRoutePlanManager()
                            .removeRequestByHandler(handler)
                        val start = BNRoutePlanNode.Builder()
                            .latitude(origin.latitude).longitude(origin.longitude)
                            .name("当前位置").build()
                        val end = BNRoutePlanNode.Builder()
                            .latitude(dest.latLng.latitude).longitude(dest.latLng.longitude)
                            .name(dest.name).build()
                        val ok = BaiduNaviManagerFactory.getRoutePlanManager()
                            .routePlan(listOf(start, end), preference, null, handler)
                        if (!ok) onError("路线规划启动失败")
                    } catch (e: Throwable) {
                        Log.e(TAG, "routePlan failed", e)
                        onError("路线规划异常: ${e.message}")
                    }
                }
            },
            onFailed = { errCode -> onError("导航引擎初始化失败 errCode=$errCode") }
        )
        return handler
    }

    /** 取消一次未完成的算路（预览页切目的地/策略/退出时调用）。 */
    fun cancel(handler: Handler) {
        try {
            BaiduNaviManagerFactory.getRoutePlanManager().removeRequestByHandler(handler)
        } catch (_: Throwable) {
        }
    }

    /** 预览页点选方案 → SDK 选路（白马地图同款 selectRoute）。 */
    fun selectRoute(index: Int) {
        try {
            BaiduNaviManagerFactory.getRouteGuideManager().selectRoute(index)
        } catch (e: Throwable) {
            Log.w(TAG, "selectRoute failed", e)
        }
    }

    /** SDK 已有算路结果时返回 true（NaviActivity 复用判定用）。 */
    fun hasPlannedRoute(): Boolean = try {
        BaiduNaviManagerFactory.getRoutePlanManager().getRoutePlanInfo()
            ?.routeInfoLatLngLists?.isNotEmpty() == true
    } catch (_: Throwable) {
        false
    }

    private fun mapRoutePlanInfo(dest: Place): List<RouteResult> {
        val infos = BaiduNaviManagerFactory.getRoutePlanManager().getRoutePlanInfo()
            ?: return emptyList()
        val tabs = infos.routeTabInfos.orEmpty()
        val details = infos.routeDetailLists.orEmpty()
        val geometries = infos.routeInfoLatLngLists.orEmpty()
        return tabs.mapIndexed { i, tab ->
            val steps = details.getOrNull(i).orEmpty().map { d ->
                RouteStep(instruction = d.description ?: "")
            }
            val points = geometries.getOrNull(i).orEmpty().flatten().map {
                LatLng(it.latitude, it.longitude)
            }
            RouteResult(
                routeId = "sdk-$i",
                destination = dest,
                steps = steps,
                pathPoints = points,
                totalDistance = tab.length,
                totalDuration = tab.passTime.toLong(),
                trafficCondition = TrafficCondition.UNKNOWN,
                routeType = if (i == 0) RouteType.RECOMMENDED else RouteType.FASTEST,
                toll = tab.toll,
                lights = tab.lights,
                label = tab.pusLabelName ?: ""
            )
        }
    }
}
