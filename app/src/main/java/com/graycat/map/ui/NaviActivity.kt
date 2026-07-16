package com.graycat.map.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.KeyEvent
import android.widget.TextView
import androidx.datastore.preferences.core.emptyPreferences
import com.baidu.location.BDAbstractLocationListener
import com.baidu.location.BDLocation
import com.baidu.location.LocationClient
import com.baidu.location.LocationClientOption
import com.baidu.mapapi.SDKInitializer
import com.baidu.navisdk.adapter.BNRoutePlanNode
import com.baidu.navisdk.adapter.BaiduNaviManagerFactory
import com.baidu.navisdk.adapter.IBNTTSManager
import com.baidu.navisdk.adapter.IBNRoutePlanManager
import com.baidu.navisdk.adapter.IBNaviListener
import com.baidu.navisdk.adapter.IBNaviViewListener
import com.baidu.navisdk.adapter.IBaiduNaviManager
import com.baidu.navisdk.adapter.struct.BNGuideConfig
import com.baidu.navisdk.adapter.struct.BNLocationData
import com.baidu.navisdk.adapter.struct.BNaviInitConfig
import com.graycat.map.data.AppSettingsKeys
import com.graycat.map.data.dataStore
import com.graycat.map.navigation.CarNavigationBridge
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Locale

private const val TAG = "NaviActivity"

class NaviActivity : Activity() {
    private var guideCreated = false
    private var guideCreateAttempted = false
    private var engineInitStarted = false
    private var closingNavi = false
    private var routePlanAttempt = 0
    private var locationClient: LocationClient? = null
    private var locationListener: BDAbstractLocationListener? = null
    private var systemTts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    private var startLat = 0.0
    private var startLng = 0.0
    private var destLat = 0.0
    private var destLng = 0.0
    private var destName = ""

    private val naviListener = object : IBNaviListener() {
        override fun onNaviGuideEnd() { CarNavigationBridge.stop(); closeNaviActivity() }
        override fun onArriveDestination() { CarNavigationBridge.stop(); closeNaviActivity() }
        override fun onRoadNameUpdate(roadName: String?) {
            CarNavigationBridge.update { it.copy(roadName = roadName.orEmpty()) }
        }
        override fun onRemainInfoUpdate(remainingDistance: Int, remainingTime: Int) {
            CarNavigationBridge.update {
                it.copy(remainingDistanceMeters = remainingDistance.coerceAtLeast(0),
                    remainingTimeSeconds = remainingTime.coerceAtLeast(0))
            }
        }
        override fun onGuideInfoUpdate(
            info: com.baidu.navisdk.adapter.struct.BNaviInfo?,
            panel: com.baidu.navisdk.adapter.struct.GuidePanelMessage?
        ) {
            if (info == null) return
            // 与手机百度导航顶部面板使用完全相同的数据源：
            // 距离取 BNaviInfo.distance，文字取 GuidePanelMessage.stringBuilder，
            // 转向类型取 BNaviInfo.turnIconName；不再用路线几何或文字自行估算距离。
            val cue = panel?.stringBuilder?.toString()?.trim()?.takeIf { it.isNotBlank() }
                ?: info.roadName?.takeIf { it.isNotBlank() } ?: "继续行驶"
            val maneuver = inferCarManeuver(info.turnIconName.orEmpty(), cue)
            CarNavigationBridge.update { previous ->
                previous.copy(
                    instruction = cue,
                    maneuverType = maneuver,
                    roadName = info.roadName.orEmpty(),
                    // 百度偶尔会短暂回传 0；保留上一帧有效值，避免左上角闪成 0 米。
                    distanceToTurnMeters = if (info.distance > 0) info.distance
                        else previous.distanceToTurnMeters
                )
            }
        }
        override fun onSpeedUpdate(speed: Int, limit: Int) {
            CarNavigationBridge.update { it.copy(speedKmh = speed.coerceAtLeast(0)) }
        }
        override fun onLocationChange(location: com.baidu.navisdk.adapter.struct.BNaviLocation?) {
            // 导航 SDK 回调坐标系不稳定，车机位置统一由 BD09LL LocationClient 更新。
            if (location == null) return
            CarNavigationBridge.update {
                it.copy(bearing = location.direction, speedKmh = location.speed.toInt().coerceAtLeast(0))
            }
        }
    }

    private val naviViewListener = object : IBNaviViewListener() {
        override fun onNaviBackClick() = closeNaviActivity()
        override fun onBottomBarClick(action: Action?) {
            // ContinueNavi / OpenSetting 都是导航 UI 内部动作，不代表退出。
            // 6.8 把 OpenSetting 错当成退出，导致上划底栏或打开设置时 Activity 被 finish。
            Log.i(TAG, "Navigation bottom action: $action")
        }
    }

    companion object {
        private const val EXTRA_START_LAT = "start_lat"
        private const val EXTRA_START_LNG = "start_lng"
        private const val EXTRA_DEST_LAT = "dest_lat"
        private const val EXTRA_DEST_LNG = "dest_lng"
        private const val EXTRA_DEST_NAME = "dest_name"

        fun start(context: Context, destLat: Double, destLng: Double, destName: String,
                  startLat: Double = 0.0, startLng: Double = 0.0) {
            context.startActivity(Intent(context, NaviActivity::class.java).apply {
                putExtra(EXTRA_START_LAT, startLat)
                putExtra(EXTRA_START_LNG, startLng)
                putExtra(EXTRA_DEST_LAT, destLat)
                putExtra(EXTRA_DEST_LNG, destLng)
                putExtra(EXTRA_DEST_NAME, destName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    private val routePlanHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                IBNRoutePlanManager.MSG_NAVI_ROUTE_PLAN_START -> Log.i(TAG, "Route plan started")
                IBNRoutePlanManager.MSG_NAVI_ROUTE_PLAN_SUCCESS -> {
                    Log.i(TAG, "Route plan success; waiting for SDK TO_NAVI")
                    syncRouteGeometryToCar()
                }
                IBNRoutePlanManager.MSG_NAVI_ROUTE_PLAN_TO_NAVI -> startGuideOnce()
                IBNRoutePlanManager.MSG_NAVI_ROUTE_PLAN_FAILED -> {
                    if (routePlanAttempt < 2) {
                        Log.w(TAG, "Route plan failed on attempt $routePlanAttempt, retrying with alternate start node")
                        Handler(Looper.getMainLooper()).postDelayed({ startRoutePlan() }, 700)
                    } else {
                        showError("路线规划失败\n起点=($startLat,$startLng)\n终点=($destLat,$destLng)")
                    }
                }
                IBNRoutePlanManager.MSG_NAVI_ROUTE_PLAN_CANCELED -> closeNaviActivity()
                else -> super.handleMessage(msg)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startLat = intent.getDoubleExtra(EXTRA_START_LAT, 0.0)
        startLng = intent.getDoubleExtra(EXTRA_START_LNG, 0.0)
        destLat = intent.getDoubleExtra(EXTRA_DEST_LAT, 0.0)
        destLng = intent.getDoubleExtra(EXTRA_DEST_LNG, 0.0)
        destName = intent.getStringExtra(EXTRA_DEST_NAME) ?: ""
        initSystemTts()
        checkPermissionAndInit()
    }

    private fun checkPermissionAndInit() {
        val ok = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (ok) {
            startIndependentLocation()
            initNaviEngine()
        } else requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION), 1001)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                startIndependentLocation()
                initNaviEngine()
            } else finish()
        }
    }

    private fun savedAk(): String = try {
        runBlocking { applicationContext.dataStore.data.catch { emit(emptyPreferences()) }
            .first()[AppSettingsKeys.BAIDU_API_KEY] ?: "" }.trim()
    } catch (_: Exception) { "" }

    private fun initSystemTts() {
        systemTts = TextToSpeech(applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                systemTts?.language = Locale.SIMPLIFIED_CHINESE
                registerOuterTts()
            } else Log.e(TAG, "Android TTS init failed: $status")
        }
    }

    private fun registerOuterTts() {
        try {
            BaiduNaviManagerFactory.getTTSManager().initTTS(
                object : IBNTTSManager.IBNOuterTTSPlayerCallback() {
                    override fun playTTSText(text: String?, speechId: String?, type: Int, raw: String?): Int {
                        if (!ttsReady || text.isNullOrBlank()) return 4
                        val result = systemTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null,
                            speechId ?: "baidu_navi_${System.currentTimeMillis()}")
                        return if (result == TextToSpeech.SUCCESS) 2 else 4
                    }
                    override fun getTTSState(): Int = if (systemTts?.isSpeaking == true) 2 else 1
                    override fun playXDTTSText(
                        wakeUp: Boolean,
                        data: com.baidu.navisdk.comapi.tts.c
                    ): Int = 4
                    override fun stopTTS() { systemTts?.stop() }
                    override fun pauseTTS() { systemTts?.stop() }
                    override fun resumeTTS() = Unit
                    override fun initTTSPlayer() = Unit
                    override fun releaseTTSPlayer() = Unit
                    override fun hasInitialized(): Boolean = ttsReady
                    override fun getCurTTSSpeech(): String = ""
                    override fun getStopTTSSpeechId(): String = ""
                    override fun getCurrentVolume(): Int = 100
                    override fun setTTSVolume(volume: Int) = Unit
                })
            Log.i(TAG, "Android outer TTS registered")
        } catch (e: Throwable) { Log.e(TAG, "Outer TTS registration failed", e) }
    }

    private fun startIndependentLocation() {
        if (locationClient != null) return
        try {
            LocationClient.setAgreePrivacy(true)
            val listener = object : BDAbstractLocationListener() {
                override fun onReceiveLocation(loc: BDLocation?) {
                    if (loc == null || loc.latitude == 0.0 || loc.longitude == 0.0) return
                    pushLocationToNaviEngine(loc)
                }
            }
            locationListener = listener
            locationClient = LocationClient(applicationContext).apply {
                locOption = LocationClientOption().apply {
                    locationMode = LocationClientOption.LocationMode.Hight_Accuracy
                    coorType = "bd09ll"
                    scanSpan = 1000
                    isOpenGnss = true
                    setNeedDeviceDirect(true)
                }
                registerLocationListener(listener)
                start()
            }
        } catch (e: Throwable) { Log.e(TAG, "Independent location start failed", e) }
    }

    private fun pushLocationToNaviEngine(bdLoc: BDLocation) {
        // Android Auto 自绘路线来自百度地图规划页（BD09LL），车辆位置也必须保持 BD09LL。
        CarNavigationBridge.update {
            it.copy(latitude = bdLoc.latitude, longitude = bdLoc.longitude,
                bearing = bdLoc.direction, speedKmh = bdLoc.speed.toInt().coerceAtLeast(0))
        }
        try {
            // 百度定位客户端输出 BD09LL；导航引擎外部定位接口要求 GCJ-02。
            val gcj = LocationClient.getBDLocationInCoorType(
                BDLocation(bdLoc), BDLocation.BDLOCATION_BD09LL_TO_GCJ02
            ) ?: return
            val data = BNLocationData.Builder().latitude(gcj.latitude).longitude(gcj.longitude)
                .accuracy(gcj.radius).direction(gcj.direction).speed(gcj.speed)
                .time(System.currentTimeMillis()).locType(gcj.locType).build()
            val navi = BaiduNaviManagerFactory.getBaiduNaviManager()
            navi.setGpsLoseLocationData(data)
            BaiduNaviManagerFactory.getMapManager().setMyLocationDataGjc02(data)
            // 不调用 setMapStatus：诱导页会根据导航引擎匹配位置自动跟车。
            // 每秒强制地图中心会覆盖 SDK 的路线视野并把页面锁在缓存位置。
        } catch (e: Throwable) { Log.w(TAG, "Push external navigation location failed", e) }
    }

    private fun initNaviEngine() {
        if (engineInitStarted) return
        engineInitStarted = true
        val ak = savedAk()
        if (ak.isBlank() || ak == "PLACEHOLDER_AK") { showError("❌ 未配置百度地图 AK"); return }
        try { SDKInitializer.setAgreePrivacy(applicationContext, true) } catch (_: Throwable) {}
        try { SDKInitializer.setApiKey(ak) } catch (_: Throwable) {}
        val config = BNaviInitConfig.Builder()
            .sdcardRootPath(getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath)
            .naviInitListener(object : IBaiduNaviManager.INaviInitListener {
                override fun onAuthResult(status: Int, msg: String?) { Log.i(TAG, "auth=$status $msg") }
                override fun initStart() { Log.i(TAG, "navi init start") }
                override fun initSuccess() = runOnUiThread {
                    try {
                        BaiduNaviManagerFactory.getBaiduNaviManager().apply {
                            // 使用 NaviActivity 的百度 LocationClient 作为导航引擎位置源。
                            // 这样 MainActivity 暂停后，导航不会退回 SDK 默认北京位置。
                            externalLocation(true)
                            setGpsNeverClose(true)
                            initSensor()
                        }
                    } catch (e: Throwable) { Log.e(TAG, "Navigation GPS start failed", e) }
                    if (ttsReady) registerOuterTts()
                    // 先把 MainActivity 传来的当前位置送入外部定位通道，再发起算路。
                    // 避免新 LocationClient 首次回调尚未到达时使用 SDK 缓存位置。
                    if (startLat != 0.0 && startLng != 0.0) {
                        pushLocationToNaviEngine(BDLocation().apply {
                            latitude = startLat; longitude = startLng
                            radius = 10f; locType = BDLocation.TypeNetWorkLocation
                        })
                    }
                    startRoutePlan()
                }
                override fun initFailed(errCode: Int) = runOnUiThread {
                    showError("❌ 导航引擎初始化失败 errCode=$errCode")
                }
            }).build()
        try {
            BaiduNaviManagerFactory.getBaiduNaviManager().enableOutLog(false)
            BaiduNaviManagerFactory.getBaiduNaviManager().init(this, config)
        } catch (e: Throwable) { showError("❌ init() 异常: ${e.message}") }
    }

    // RouteResultManager 不在这里提前初始化；routePlan 适配层会在算路完成后
    // 自行切换到 TO_NAVI，提前 startNavi 会复用上一条诱导路线。

    private fun startRoutePlan() {
        if (destLat == 0.0 || destLng == 0.0) { showError("❌ 终点坐标无效"); return }
        try {
            // 导航适配层会依据 SDKInitializer.getCoordType() 自动转换节点。
            // 全局 CoordType 是 BD09LL，因此这里必须直接传 BD09LL，不能预转 GCJ-02。
            val startBd = if (startLat != 0.0 && startLng != 0.0) BDLocation().apply {
                latitude = startLat; longitude = startLng
            } else locationClient?.lastKnownLocation
            if (startBd == null) {
                showError("❌ 当前定位尚未就绪，无法规划导航路线")
                return
            }
            startLat = startBd.latitude
            startLng = startBd.longitude
            routePlanAttempt++
            // 第一次优先让导航 SDK 使用它刚收到的外部“我的位置”，避免手工节点的
            // GPS 精度/时间戳在跨城长路线中被判定为无效；失败后再回退到明确坐标节点。
            val start = if (routePlanAttempt == 1) {
                BNRoutePlanNode.Builder().isMyLocation(true).name("当前位置").build()
            } else {
                BNRoutePlanNode.Builder().latitude(startBd.latitude).longitude(startBd.longitude)
                    .name("当前位置")
                    .gpsAccuracy(startBd.radius.coerceAtLeast(10f))
                    .gpsAngle(startBd.direction)
                    .gpsSpeed(startBd.speed)
                    .build()
            }
            val dest = BNRoutePlanNode.Builder().latitude(destLat).longitude(destLng)
                .name(destName).build()

            // 仅取消同一 Handler 尚未完成的请求，不在每次算路前强退整个导航引擎。
            // forceQuitNaviWithoutDialog/clearRouteLayer 都是异步清理，紧接着长距离算路会产生竞态。
            try { BaiduNaviManagerFactory.getRoutePlanManager().removeRequestByHandler(routePlanHandler) } catch (_: Throwable) {}

            val ok = BaiduNaviManagerFactory.getRoutePlanManager().routePlan(
                listOf(start, dest),
                IBNRoutePlanManager.RoutePlanPreference.ROUTE_PLAN_PREFERENCE_DEFAULT,
                null,
                routePlanHandler
            )
            if (!ok) {
                if (routePlanAttempt < 2) {
                    Handler(Looper.getMainLooper()).postDelayed({ startRoutePlan() }, 700)
                } else {
                    showError("❌ routePlan() 返回 false")
                }
            }
        } catch (e: Throwable) { showError("❌ routePlan 异常: ${e.message}") }
    }

    private fun parseGuideDistanceMeters(text: String): Int {
        val normalized = text.replace(",", "").replace("，", "")
        Regex("([0-9]+(?:\\.[0-9]+)?)\\s*(公里|千米|km)", RegexOption.IGNORE_CASE)
            .find(normalized)?.let { match ->
                return (match.groupValues[1].toDoubleOrNull()?.times(1000.0) ?: 0.0).toInt()
            }
        Regex("([0-9]+)\\s*(米|m)", RegexOption.IGNORE_CASE)
            .find(normalized)?.let { match ->
                return match.groupValues[1].toIntOrNull() ?: 0
            }
        return 0
    }

    private fun inferCarManeuver(iconName: String, cue: String): Int {
        val text = "$iconName $cue".lowercase()
        return when {
            "掉头" in text || "u_turn" in text || "uturn" in text -> androidx.car.app.navigation.model.Maneuver.TYPE_U_TURN_LEFT
            "向左急转" in text || "sharp_left" in text -> androidx.car.app.navigation.model.Maneuver.TYPE_TURN_SHARP_LEFT
            "向右急转" in text || "sharp_right" in text -> androidx.car.app.navigation.model.Maneuver.TYPE_TURN_SHARP_RIGHT
            "向左前方" in text || "slight_left" in text -> androidx.car.app.navigation.model.Maneuver.TYPE_TURN_SLIGHT_LEFT
            "向右前方" in text || "slight_right" in text -> androidx.car.app.navigation.model.Maneuver.TYPE_TURN_SLIGHT_RIGHT
            "左转" in text || "turn_left" in text -> androidx.car.app.navigation.model.Maneuver.TYPE_TURN_NORMAL_LEFT
            "右转" in text || "turn_right" in text -> androidx.car.app.navigation.model.Maneuver.TYPE_TURN_NORMAL_RIGHT
            "靠左" in text || "keep_left" in text -> androidx.car.app.navigation.model.Maneuver.TYPE_KEEP_LEFT
            "靠右" in text || "keep_right" in text -> androidx.car.app.navigation.model.Maneuver.TYPE_KEEP_RIGHT
            "出口" in text && "左" in text -> androidx.car.app.navigation.model.Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT
            "出口" in text || "匝道" in text -> androidx.car.app.navigation.model.Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
            "到达" in text -> androidx.car.app.navigation.model.Maneuver.TYPE_DESTINATION
            else -> androidx.car.app.navigation.model.Maneuver.TYPE_STRAIGHT
        }
    }

    private fun syncRouteGeometryToCar() {
        // 没有 Android Auto Host 时不复制长路线，保证手机导航路径零额外开销。
        if (!CarNavigationBridge.hasListeners()) return
        try {
            val infos = BaiduNaviManagerFactory.getRoutePlanManager().getRoutePlanInfo()
            val selected = BaiduNaviManagerFactory.getRoutePlanManager().selectRouteId
            val routes = infos?.routeInfoLatLngLists.orEmpty()
            val segments = routes.getOrNull(selected) ?: routes.firstOrNull().orEmpty()
            val total = segments.sumOf { it.size }
            val maxPoints = 1200
            val stride = (total / maxPoints).coerceAtLeast(1)
            val sampled = ArrayList<Pair<Double, Double>>(minOf(total, maxPoints + 1))
            var index = 0
            segments.forEach { segment ->
                segment.forEach { point ->
                    if (index % stride == 0 && sampled.size < maxPoints) {
                        sampled.add(point.latitude to point.longitude)
                    }
                    index++
                }
            }
            segments.lastOrNull()?.lastOrNull()?.let { end ->
                val pair = end.latitude to end.longitude
                if (sampled.lastOrNull() != pair) sampled.add(pair)
            }
            CarNavigationBridge.update { it.copy(routePoints = sampled) }
            Log.i(TAG, "Synced ${sampled.size}/$total sampled route points to Android Auto")
        } catch (e: Throwable) {
            Log.w(TAG, "Route geometry sync failed", e)
        }
    }

    private fun startGuideOnce() {
        if (guideCreated || guideCreateAttempted || closingNavi) return
        guideCreateAttempted = true
        try {
            try { BaiduNaviManagerFactory.getMapManager().getMapView() } catch (_: Throwable) {}
            val manager = BaiduNaviManagerFactory.getRouteGuideManager()
            val view = manager.onCreate(this, BNGuideConfig.Builder().build())
            if (view == null) { showError("❌ 百度导航界面初始化失败：onCreate 返回 null"); return }
            guideCreated = true
            CarNavigationBridge.start(destName)
            // 部分 SDK 版本在 MSG_NAVI_ROUTE_PLAN_SUCCESS 时路线几何尚未填充完整，
            // 导航 View 创建后再同步一次，避免车机永远停在“正在加载路线地图”。
            syncRouteGeometryToCar()
            setContentView(view)
            // SDK 偶尔会从持久化调试设置恢复 common_debug_layout，正式版强制隐藏。
            val debugLayoutId = resources.getIdentifier("common_debug_layout", "id", packageName)
                .takeIf { it != 0 } ?: resources.getIdentifier(
                    "common_debug_layout", "id", "com.baidu.navisdk.embed"
                )
            if (debugLayoutId != 0) view.findViewById<android.view.View>(debugLayoutId)?.visibility = android.view.View.GONE
            val debugTextId = resources.getIdentifier("common_debug_text", "id", packageName)
                .takeIf { it != 0 } ?: resources.getIdentifier(
                    "common_debug_text", "id", "com.baidu.navisdk.embed"
                )
            if (debugTextId != 0) view.findViewById<android.view.View>(debugTextId)?.visibility = android.view.View.GONE
            manager.setNaviListener(naviListener)
            manager.setNaviViewListener(naviViewListener)
            manager.onStart()
            manager.onResume()
            try { BaiduNaviManagerFactory.getMapManager().onResume() } catch (_: Throwable) {}
            locationClient?.lastKnownLocation?.let { pushLocationToNaviEngine(it) }
            Log.i(TAG, "Baidu native guide attached")
        } catch (e: Throwable) {
            Log.e(TAG, "Native guide create failed", e)
            diagnoseGuideLayout(e)
        }
    }

    private fun diagnoseGuideLayout(original: Throwable) {
        try {
            val id = resources.getIdentifier("nsdk_layout_rg_mapmode_main_new_v2", "layout", packageName)
                .takeIf { it != 0 } ?: resources.getIdentifier("nsdk_layout_rg_mapmode_main_new_v2",
                    "layout", "com.baidu.navisdk.embed")
            if (id != 0) layoutInflater.inflate(id, null, false)
            showError("❌ 百度导航内部状态初始化异常\n${original.javaClass.name}\n${original.message}\n\n${original.stackTraceToString().take(1400)}")
        } catch (layoutError: Throwable) {
            showError("❌ 百度导航 UI 依赖/布局加载异常\n${layoutError.javaClass.name}\n${layoutError.message}\n\n${layoutError.stackTraceToString().take(1400)}")
        }
    }

    private fun showError(msg: String) {
        Log.e(TAG, msg)
        runOnUiThread { setContentView(TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xFF101010.toInt())
            textSize = 16f; setPadding(48, 48, 48, 48); text = msg
        }) }
    }

    private fun closeNaviActivity() {
        if (closingNavi) return
        closingNavi = true
        CarNavigationBridge.stop()
        runOnUiThread {
            try { BaiduNaviManagerFactory.getRouteGuideManager().stopNavi() } catch (_: Throwable) {}
            finish()
        }
    }

    override fun onStart() { super.onStart(); if (guideCreated) BaiduNaviManagerFactory.getRouteGuideManager().onStart() }
    override fun onResume() {
        super.onResume()
        if (guideCreated) BaiduNaviManagerFactory.getRouteGuideManager().onResume()
    }
    override fun onPause() {
        if (guideCreated) BaiduNaviManagerFactory.getRouteGuideManager().onPause()
        super.onPause()
    }
    override fun onStop() { if (guideCreated) BaiduNaviManagerFactory.getRouteGuideManager().onStop(); super.onStop() }
    override fun onDestroy() {
        try { BaiduNaviManagerFactory.getRoutePlanManager().removeRequestByHandler(routePlanHandler) } catch (_: Throwable) {}
        if (guideCreated) try {
            BaiduNaviManagerFactory.getRouteGuideManager().apply {
                removeNaviListener(naviListener); setNaviViewListener(null)
                forceQuitNaviWithoutDialog(); onDestroy(false)
            }
        } catch (_: Throwable) {}
        try { BaiduNaviManagerFactory.getMapManager().clearRouteLayer() } catch (_: Throwable) {}
        try { locationListener?.let { locationClient?.unRegisterLocationListener(it) }; locationClient?.stop() } catch (_: Throwable) {}
        try { BaiduNaviManagerFactory.getBaiduNaviManager().stopLocationMonitor(); BaiduNaviManagerFactory.getBaiduNaviManager().unInitSensor() } catch (_: Throwable) {}
        try { BaiduNaviManagerFactory.getMapManager().onPause() } catch (_: Throwable) {}
        try { systemTts?.stop(); systemTts?.shutdown() } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (guideCreated && keyCode == KeyEvent.KEYCODE_BACK &&
            BaiduNaviManagerFactory.getRouteGuideManager().onKeyDown(keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }
}
