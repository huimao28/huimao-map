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
import com.baidu.mapapi.search.geocode.GeoCoder
import com.baidu.mapapi.search.geocode.OnGetGeoCoderResultListener
import com.baidu.mapapi.search.geocode.ReverseGeoCodeOption
import com.baidu.mapapi.search.geocode.ReverseGeoCodeResult
import com.baidu.mapapi.search.poi.PoiCitySearchOption
import com.baidu.mapapi.search.poi.PoiDetailResult
import com.baidu.mapapi.search.poi.PoiDetailSearchOption
import com.baidu.mapapi.search.poi.PoiNearbySearchOption
import com.baidu.mapapi.search.poi.PoiResult
import com.baidu.mapapi.search.poi.PoiSearch
import com.baidu.mapapi.search.poi.PoiSortType
import com.baidu.mapapi.search.route.BikingRoutePlanOption
import com.baidu.mapapi.search.route.BikingRouteResult
import com.baidu.mapapi.search.route.DrivingRoutePlanOption
import com.baidu.mapapi.search.route.DrivingRouteResult
import com.baidu.mapapi.search.route.IndoorRouteResult
import com.baidu.mapapi.search.route.IntegralRouteResult
import com.baidu.mapapi.search.route.MassTransitRouteResult
import com.baidu.mapapi.search.route.OnGetRoutePlanResultListener
import com.baidu.mapapi.search.route.PlanNode
import com.baidu.mapapi.search.route.RoutePlanSearch
import com.baidu.mapapi.search.route.TransitRoutePlanOption
import com.baidu.mapapi.search.route.TransitRouteResult
import com.baidu.mapapi.search.route.WalkingRoutePlanOption
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

/** 路线规划出行方式（与白马地图的步行/骑行/驾车/公交四档对齐，百度单引擎实现）。 */
enum class RouteMode(val label: String) {
    DRIVING("驾车"),
    WALKING("步行"),
    BIKING("骑行"),
    TRANSIT("公交"),
}

/** 驾车路线策略（映射百度 DrivingPolicy，与白马地图的策略档位一致）。 */
enum class DrivingPolicyOption(val label: String) {
    TIME_FIRST("时间优先"),
    AVOID_JAM("避堵"),
    DIS_FIRST("距离优先"),
    FEE_FIRST("少收费"),
}

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

    /**
     * 多方式路线规划（驾车/步行/骑行/公交）。
     * 驾车默认带路况策略（ROUTE_PATH_AND_TRAFFIC）并按所选策略算路；
     * 公交需传入城市名（无城市名时仍尝试算路，SDK 可能返回空）。
     */
    suspend fun planRoutes(
        origin: LatLng,
        destination: Place,
        mode: RouteMode,
        drivingPolicy: DrivingPolicyOption = DrivingPolicyOption.TIME_FIRST,
        city: String = ""
    ): List<RouteResult> = suspendCancellableCoroutine { cont ->
        val search = RoutePlanSearch.newInstance()
        var completed = false
        fun finish(value: List<RouteResult>) {
            if (completed) return
            completed = true
            try { search.destroy() } catch (_: Throwable) {}
            if (cont.isActive) cont.resume(value)
        }
        fun <T : com.baidu.mapapi.search.core.RouteStep> mapLines(
            lines: List<Triple<List<T>, Int, Int>>,
            prefix: String,
            instructionOf: (T) -> String?
        ): List<RouteResult> {
            val originPlace = Place(name = "当前位置", latLng = origin)
            return lines.mapIndexed { index, (steps, distance, duration) ->
                val points = steps.flatMap { step ->
                    step.wayPoints.orEmpty().map { LatLng(it.latitude, it.longitude) }
                }.distinct()
                val mappedSteps = steps.map { step ->
                    RouteStep(
                        instruction = instructionOf(step) ?: "继续行驶",
                        distance = step.distance.toDouble(),
                        duration = step.duration.toLong(),
                        maneuver = ManeuverType.STRAIGHT,
                        roadName = ""
                    )
                }
                RouteResult(
                    routeId = "$prefix-$index",
                    origin = originPlace,
                    destination = destination,
                    steps = mappedSteps,
                    pathPoints = points,
                    totalDistance = distance.toDouble(),
                    totalDuration = duration.toLong(),
                    trafficCondition = TrafficCondition.UNKNOWN,
                    routeType = if (index == 0) RouteType.RECOMMENDED else RouteType.FASTEST
                )
            }
        }
        search.setOnGetRoutePlanResultListener(object : OnGetRoutePlanResultListener {
            override fun onGetDrivingRouteResult(result: DrivingRouteResult?) {
                if (result == null || result.error != SearchResult.ERRORNO.NO_ERROR) {
                    Log.w(TAG, "Driving route failed: ${result?.error}")
                    finish(emptyList()); return
                }
                val rawLines = result.routeLines.orEmpty()
                val lines = rawLines.map { line ->
                    Triple(line.allStep.orEmpty(), line.distance, line.duration)
                }
                val mapped = mapLines(lines, "baidu-drive") { it.instructions }
                finish(mapped.mapIndexed { i, r ->
                    val cong = rawLines.getOrNull(i)?.congestionDistance ?: 0
                    r.copy(trafficCondition = if (cong > 0) TrafficCondition.SLOW else TrafficCondition.SMOOTH)
                })
            }
            override fun onGetWalkingRouteResult(result: WalkingRouteResult?) {
                if (result == null || result.error != SearchResult.ERRORNO.NO_ERROR) {
                    Log.w(TAG, "Walking route failed: ${result?.error}")
                    finish(emptyList()); return
                }
                val lines = result.routeLines.orEmpty().map { line ->
                    Triple(line.allStep.orEmpty(), line.distance, line.duration)
                }
                finish(mapLines(lines, "baidu-walk") { it.instructions })
            }
            override fun onGetBikingRouteResult(result: BikingRouteResult?) {
                if (result == null || result.error != SearchResult.ERRORNO.NO_ERROR) {
                    Log.w(TAG, "Biking route failed: ${result?.error}")
                    finish(emptyList()); return
                }
                val lines = result.routeLines.orEmpty().map { line ->
                    Triple(line.allStep.orEmpty(), line.distance, line.duration)
                }
                finish(mapLines(lines, "baidu-bike") { it.instructions })
            }
            override fun onGetTransitRouteResult(result: TransitRouteResult?) {
                if (result == null || result.error != SearchResult.ERRORNO.NO_ERROR) {
                    Log.w(TAG, "Transit route failed: ${result?.error}")
                    finish(emptyList()); return
                }
                val lines = result.routeLines.orEmpty().map { line ->
                    Triple(line.allStep.orEmpty(), line.distance, line.duration)
                }
                finish(mapLines(lines, "baidu-transit") { it.instructions })
            }
            override fun onGetMassTransitRouteResult(r: MassTransitRouteResult?) = Unit
            override fun onGetIndoorRouteResult(r: IndoorRouteResult?) = Unit
            override fun onGetIntegralRouteResult(r: IntegralRouteResult?) = Unit
        })
        cont.invokeOnCancellation { try { search.destroy() } catch (_: Throwable) {} }
        try {
            val from = PlanNode.withLocation(
                com.baidu.mapapi.model.LatLng(origin.latitude, origin.longitude)
            )
            val to = PlanNode.withLocation(
                com.baidu.mapapi.model.LatLng(
                    destination.latLng.latitude, destination.latLng.longitude
                )
            )
            val ok = when (mode) {
                RouteMode.DRIVING -> {
                    val policy = when (drivingPolicy) {
                        DrivingPolicyOption.TIME_FIRST -> DrivingRoutePlanOption.DrivingPolicy.ECAR_TIME_FIRST
                        DrivingPolicyOption.AVOID_JAM -> DrivingRoutePlanOption.DrivingPolicy.ECAR_AVOID_JAM
                        DrivingPolicyOption.DIS_FIRST -> DrivingRoutePlanOption.DrivingPolicy.ECAR_DIS_FIRST
                        DrivingPolicyOption.FEE_FIRST -> DrivingRoutePlanOption.DrivingPolicy.ECAR_FEE_FIRST
                    }
                    search.drivingSearch(
                        DrivingRoutePlanOption().from(from).to(to)
                            .trafficPolicy(DrivingRoutePlanOption.DrivingTrafficPolicy.ROUTE_PATH_AND_TRAFFIC)
                            .policy(policy)
                    )
                }
                RouteMode.WALKING -> search.walkingSearch(
                    WalkingRoutePlanOption().from(from).to(to)
                )
                RouteMode.BIKING -> search.bikingSearch(
                    BikingRoutePlanOption().from(from).to(to)
                )
                RouteMode.TRANSIT -> search.transitSearch(
                    TransitRoutePlanOption().from(from).to(to)
                        .city(city)
                        .policy(TransitRoutePlanOption.TransitPolicy.EBUS_TIME_FIRST)
                )
            }
            if (!ok) finish(emptyList())
        } catch (e: Throwable) {
            Log.e(TAG, "Route plan exception", e)
            finish(emptyList())
        }
    }

    /**
     * POI 详情（按 UID）：补全名称、地址与坐标。
     */
    suspend fun getPoiDetail(uid: String): Place? = suspendCancellableCoroutine { cont ->
        try {
            val search = PoiSearch.newInstance()
            val listener = object : OnGetPoiSearchResultListener {
                override fun onGetPoiResult(result: PoiResult?) {}
                override fun onGetPoiDetailResult(result: PoiDetailResult?) {
                    try { search.destroy() } catch (_: Throwable) {}
                    if (result == null || result.error != SearchResult.ERRORNO.NO_ERROR) {
                        Log.w(TAG, "POI detail failed: ${result?.error}")
                        if (cont.isActive) cont.resume(null)
                        return
                    }
                    val place = Place(
                        uid = result.uid ?: uid,
                        name = result.name ?: "",
                        address = result.address ?: "",
                        latLng = LatLng(
                            result.location?.latitude ?: 0.0,
                            result.location?.longitude ?: 0.0
                        )
                    )
                    if (cont.isActive) cont.resume(place)
                }
                override fun onGetPoiDetailResult(result: com.baidu.mapapi.search.poi.PoiDetailSearchResult?) {
                    try { search.destroy() } catch (_: Throwable) {}
                    if (cont.isActive) cont.resume(null)
                }
                override fun onGetPoiIndoorResult(result: com.baidu.mapapi.search.poi.PoiIndoorResult?) {}
            }
            search.setOnGetPoiSearchResultListener(listener)
            search.searchPoiDetail(PoiDetailSearchOption().poiUid(uid))
        } catch (e: Exception) {
            Log.e(TAG, "POI detail exception: ${e.message}", e)
            if (cont.isActive) cont.resume(null)
        }
    }

    /**
     * 逆地理编码：取坐标所在城市（用于公交算路的城市参数）。
     * 失败返回空字符串，调用方再用搜索时识别的城市或地址前缀兜底。
     */
    suspend fun reverseGeoCity(location: LatLng): String = suspendCancellableCoroutine { cont ->
        try {
            val geoCoder = GeoCoder.newInstance()
            val listener = object : OnGetGeoCoderResultListener {
                override fun onGetGeoCodeResult(result: com.baidu.mapapi.search.geocode.GeoCodeResult?) {}
                override fun onGetReverseGeoCodeResult(result: ReverseGeoCodeResult?) {
                    try { geoCoder.destroy() } catch (_: Throwable) {}
                    val city = result?.addressDetail?.city?.trim().orEmpty()
                    if (cont.isActive) cont.resume(city)
                }
            }
            geoCoder.setOnGetGeoCodeResultListener(listener)
            val ok = geoCoder.reverseGeoCode(
                ReverseGeoCodeOption().location(
                    com.baidu.mapapi.model.LatLng(location.latitude, location.longitude)
                )
            )
            if (!ok) {
                try { geoCoder.destroy() } catch (_: Throwable) {}
                if (cont.isActive) cont.resume("")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Reverse geocode exception: ${e.message}", e)
            if (cont.isActive) cont.resume("")
        }
    }

    /**
     * POI 搜索（周边或城市内），支持分页。
     * 参数与白马地图的百度侧保持一致：城内 scope=2（详情）、不限本城、
     * 返回地址；周边 scope=2、按距离排序、半径不硬限。
     */
    suspend fun searchPoi(
        keyword: String,
        city: String = "北京",
        location: LatLng? = null,
        pageNum: Int = 0,
        pageCapacity: Int = 10
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
                        // 白马地图的周边默认半径就是 50000 米，之前抄参数时漏了数值只抄了字段。
                        .radius(50000)
                        .pageNum(pageNum)
                        .pageCapacity(pageCapacity)
                        .scope(2)
                        .sortType(PoiSortType.distance_from_near_to_far)
                        .radiusLimit(false)
                )
            } else {
                val cityOption = PoiCitySearchOption()
                    .city(city)
                    .keyword(keyword)
                    .pageNum(pageNum)
                    .pageCapacity(pageCapacity)
                    .scope(2)
                    .cityLimit(false)
                cityOption.mIsReturnAddr = true
                search.searchInCity(cityOption)
            }
        } catch (e: Exception) {
            Log.e(TAG, "POI search exception: ${e.message}", e)
            if (cont.isActive) cont.resume(emptyList())
        }
    }
}
