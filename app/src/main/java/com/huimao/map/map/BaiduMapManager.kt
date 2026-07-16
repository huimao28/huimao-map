package com.huimao.map.map

import android.content.Context
import android.util.Log
import com.baidu.location.BDAbstractLocationListener
import com.baidu.location.BDLocation
import com.baidu.location.LocationClient
import com.baidu.location.LocationClientOption
import com.baidu.mapapi.CoordType
import com.baidu.mapapi.SDKInitializer
import com.baidu.mapapi.search.core.SearchResult
import com.baidu.mapapi.search.poi.OnGetPoiSearchResultListener
import com.baidu.mapapi.search.poi.PoiCitySearchOption
import com.baidu.mapapi.search.poi.PoiNearbySearchOption
import com.baidu.mapapi.search.poi.PoiResult
import com.baidu.mapapi.search.poi.PoiSearch
import com.baidu.mapapi.search.route.BikingRouteResult
import com.baidu.mapapi.search.route.DrivingRoutePlanOption
import com.baidu.mapapi.search.route.DrivingRouteResult
import com.baidu.mapapi.search.route.IndoorRouteResult
import com.baidu.mapapi.search.route.IntegralRouteResult
import com.baidu.mapapi.search.route.MassTransitRouteResult
import com.baidu.mapapi.search.route.OnGetRoutePlanResultListener
import com.baidu.mapapi.search.route.PlanNode
import com.baidu.mapapi.search.route.RoutePlanSearch
import com.baidu.mapapi.search.route.TransitRouteResult
import com.baidu.mapapi.search.route.WalkingRouteResult
import com.huimao.map.model.LatLng
import com.huimao.map.model.ManeuverType
import com.huimao.map.model.Place
import com.huimao.map.model.RouteResult
import com.huimao.map.model.RouteStep
import com.huimao.map.model.RouteType
import com.huimao.map.model.TrafficCondition
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "BaiduMapManager"

object BaiduMapManager {

    /**
     * 百度地图 SDK 只能 initialize 一次（多次调用会崩溃）。
     * 但 setApiKey 可以在 initialize 之前随时调用。
     *
     * 正确顺序：
     *   1. setApiKey(ak)          ← 必须在 initialize 之前
     *   2. initialize(context)    ← 只能调一次
     *   3. setCoordType(BD09LL)   ← initialize 之后
     *
     * 如果用户后来更新了 AK，只需再次调用 setApiKey，
     * 无需重新 initialize（SDK 会自动重新鉴权）。
     */
    private var sdkInitialized = false  // initialize() 是否已调用
    private var currentAk = ""          // 当前 AK

    // 外部注册的定位回调（由 MainActivity 注册）
    private var locationCallback: ((LatLng) -> Unit)? = null
    private var locationClient: LocationClient? = null

    /**
     * 初始化或更新 AK。
     * - 首次调用：setApiKey → initialize → setCoordType
     * - 后续调用（AK 更新）：只调 setApiKey，SDK 自动重新鉴权
     *
     * 必须在主线程调用。
     */
    fun initialize(context: Context, apiKey: String) {
        val ak = apiKey.trim()
        try {
            if (ak.isNotBlank() && ak != "PLACEHOLDER_AK") {
                // 无论是否已初始化，都先更新 AK
                SDKInitializer.setApiKey(ak)
                currentAk = ak
                Log.i(TAG, "AK set: ${ak.take(8)}...")
            }

            if (!sdkInitialized) {
                // 首次初始化：必须在 setApiKey 之后调用
                SDKInitializer.initialize(context.applicationContext)
                SDKInitializer.setCoordType(CoordType.BD09LL)
                sdkInitialized = true
                Log.i(TAG, "Map SDK initialized (first time)")
            } else if (ak.isNotBlank() && ak != currentAk) {
                // AK 更新：重新设置（SDK 会自动重新鉴权，无需 reinitialize）
                Log.i(TAG, "AK updated, SDK will re-auth automatically")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Map SDK init failed: ${e.message}", e)
        }
    }

    /**
     * SDK 是否已初始化（不代表鉴权成功，鉴权结果在 SDK 内部处理）
     */
    fun isReady(): Boolean = sdkInitialized && currentAk.isNotBlank()

    /**
     * 启动百度定位 SDK。
     * 按文档要求：setAgreePrivacy 已在 Application.onCreate 最前面调用。
     */
    fun startLocation(context: Context, onLocation: (LatLng) -> Unit) {
        locationCallback = onLocation
        if (locationClient != null) {
            Log.d(TAG, "LocationClient already running")
            return
        }
        try {
            val client = LocationClient(context.applicationContext)
            locationClient = client

            val option = LocationClientOption().apply {
                locationMode = LocationClientOption.LocationMode.Hight_Accuracy
                setCoorType("bd09ll")
                setScanSpan(2000)
                setIsNeedAddress(true)
                setIsNeedLocationDescribe(true)
                setOpenGnss(true)
                setIgnoreKillProcess(true)
                setOpenAutoNotifyMode()
            }
            client.locOption = option

            client.registerLocationListener(object : BDAbstractLocationListener() {
                override fun onReceiveLocation(location: BDLocation?) {
                    if (location == null) return
                    val lat = location.latitude
                    val lng = location.longitude
                    val locType = location.locType
                    Log.d(TAG, "Location: lat=$lat, lng=$lng, type=$locType")
                    if (lat != 0.0 && lng != 0.0 &&
                        locType != BDLocation.TypeServerError &&
                        locType != BDLocation.TypeNetWorkException &&
                        locType != BDLocation.TypeCriteriaException
                    ) {
                        locationCallback?.invoke(LatLng(lat, lng))
                    } else {
                        Log.w(TAG, "Location failed, locType=$locType")
                    }
                }
            })

            client.start()
            Log.i(TAG, "LocationClient started")
        } catch (e: Exception) {
            Log.e(TAG, "LocationClient start failed: ${e.message}", e)
            locationClient = null
        }
    }

    fun stopLocation() {
        try {
            locationClient?.stop()
            locationClient = null
            locationCallback = null
            Log.i(TAG, "LocationClient stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Stop location failed: ${e.message}", e)
        }
    }

    /** 使用百度地图 SDK 规划真实驾车路线，返回道路几何、距离、时间和步骤。 */
    suspend fun planDrivingRoutes(origin: LatLng, destination: Place): List<RouteResult> =
        suspendCancellableCoroutine { cont ->
            val search = RoutePlanSearch.newInstance()
            var completed = false
            fun finish(value: List<RouteResult>) {
                if (completed) return
                completed = true
                try { search.destroy() } catch (_: Throwable) {}
                if (cont.isActive) cont.resume(value)
            }
            search.setOnGetRoutePlanResultListener(object : OnGetRoutePlanResultListener {
                override fun onGetDrivingRouteResult(result: DrivingRouteResult?) {
                    if (result == null || result.error != SearchResult.ERRORNO.NO_ERROR) {
                        Log.w(TAG, "Driving route failed: ${result?.error}")
                        finish(emptyList()); return
                    }
                    val originPlace = Place(name = "当前位置", latLng = origin)
                    val routes = result.routeLines.orEmpty().mapIndexed { index, line ->
                        val points = line.allStep.orEmpty().flatMap { step ->
                            step.wayPoints.orEmpty().map { LatLng(it.latitude, it.longitude) }
                        }.distinct()
                        val steps = line.allStep.orEmpty().map { step ->
                            RouteStep(
                                instruction = step.instructions ?: "继续行驶",
                                distance = step.distance.toDouble(),
                                duration = step.duration.toLong(),
                                maneuver = ManeuverType.STRAIGHT,
                                roadName = step.roadName ?: ""
                            )
                        }
                        RouteResult(
                            routeId = "baidu-$index",
                            origin = originPlace,
                            destination = destination,
                            steps = steps,
                            pathPoints = points,
                            totalDistance = line.distance.toDouble(),
                            totalDuration = line.duration.toLong(),
                            trafficCondition = if (line.congestionDistance > 0) TrafficCondition.SLOW else TrafficCondition.SMOOTH,
                            routeType = if (index == 0) RouteType.RECOMMENDED else RouteType.FASTEST
                        )
                    }
                    finish(routes)
                }
                override fun onGetWalkingRouteResult(r: WalkingRouteResult?) = Unit
                override fun onGetTransitRouteResult(r: TransitRouteResult?) = Unit
                override fun onGetMassTransitRouteResult(r: MassTransitRouteResult?) = Unit
                override fun onGetIndoorRouteResult(r: IndoorRouteResult?) = Unit
                override fun onGetBikingRouteResult(r: BikingRouteResult?) = Unit
                override fun onGetIntegralRouteResult(r: IntegralRouteResult?) = Unit
            })
            cont.invokeOnCancellation { try { search.destroy() } catch (_: Throwable) {} }
            try {
                val ok = search.drivingSearch(
                    DrivingRoutePlanOption()
                        .from(PlanNode.withLocation(com.baidu.mapapi.model.LatLng(origin.latitude, origin.longitude)))
                        .to(PlanNode.withLocation(com.baidu.mapapi.model.LatLng(
                            destination.latLng.latitude, destination.latLng.longitude)))
                        .policy(DrivingRoutePlanOption.DrivingPolicy.ECAR_TIME_FIRST)
                )
                if (!ok) finish(emptyList())
            } catch (e: Throwable) {
                Log.e(TAG, "Driving route exception", e)
                finish(emptyList())
            }
        }

    /**
     * POI 搜索（周边或城市内）
     */
    suspend fun searchPoi(
        keyword: String,
        city: String = "北京",
        location: LatLng? = null
    ): List<Place> = suspendCancellableCoroutine { cont ->
        try {
            val search = PoiSearch.newInstance()
            val listener = object : OnGetPoiSearchResultListener {
                override fun onGetPoiResult(result: PoiResult?) {
                    search.destroy()
                    if (result == null || result.error != SearchResult.ERRORNO.NO_ERROR) {
                        Log.w(TAG, "POI search failed: ${result?.error}")
                        if (cont.isActive) cont.resume(emptyList())
                        return
                    }
                    val places = result.allPoi?.mapNotNull { poi ->
                        try {
                            Place(
                                uid = poi.uid ?: System.currentTimeMillis().toString(),
                                name = poi.name ?: "",
                                address = poi.address ?: "",
                                latLng = LatLng(
                                    poi.location?.latitude ?: 0.0,
                                    poi.location?.longitude ?: 0.0
                                )
                            )
                        } catch (e: Exception) { null }
                    } ?: emptyList()
                    Log.i(TAG, "POI search returned ${places.size} results")
                    if (cont.isActive) cont.resume(places)
                }

                override fun onGetPoiDetailResult(result: com.baidu.mapapi.search.poi.PoiDetailResult?) {}
                override fun onGetPoiDetailResult(result: com.baidu.mapapi.search.poi.PoiDetailSearchResult?) {}
                override fun onGetPoiIndoorResult(result: com.baidu.mapapi.search.poi.PoiIndoorResult?) {}
            }
            search.setOnGetPoiSearchResultListener(listener)

            if (location != null && location.latitude != 0.0) {
                val bdLoc = com.baidu.mapapi.model.LatLng(location.latitude, location.longitude)
                search.searchNearby(
                    PoiNearbySearchOption()
                        .keyword(keyword)
                        .location(bdLoc)
                        .radius(5000)
                        .pageNum(0)
                )
            } else {
                search.searchInCity(
                    PoiCitySearchOption()
                        .city(city)
                        .keyword(keyword)
                        .pageNum(0)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "POI search exception: ${e.message}", e)
            if (cont.isActive) cont.resume(emptyList())
        }
    }
}
