package com.huimao.map.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.datastore.preferences.core.emptyPreferences
import com.baidu.location.BDAbstractLocationListener
import com.baidu.location.BDLocation
import com.baidu.location.LocationClient
import com.baidu.location.LocationClientOption
import com.baidu.mapapi.SDKInitializer
import com.baidu.mapapi.map.MapStatus
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.MapView
import com.baidu.mapapi.map.MyLocationConfiguration
import com.baidu.mapapi.map.MyLocationData
import com.baidu.mapapi.map.PolylineOptions
import com.baidu.mapapi.model.LatLng as BaiduLatLng
import com.baidu.navisdk.adapter.BNRoutePlanNode
import com.baidu.navisdk.adapter.BaiduNaviManagerFactory
import com.baidu.navisdk.adapter.IBNTTSManager
import com.baidu.navisdk.adapter.IBNRoutePlanManager
import com.baidu.navisdk.adapter.IBNaviListener
import com.baidu.navisdk.adapter.IBNaviViewListener
import com.baidu.navisdk.adapter.IBaiduNaviManager
import com.baidu.navisdk.adapter.struct.BNGuideConfig
import com.baidu.navisdk.adapter.struct.BNLocationData
import com.baidu.navisdk.adapter.struct.BNTTsInitConfig
import com.baidu.navisdk.adapter.struct.BNaviInitConfig
import com.baidu.navisdk.tts.ITTSInitListener
import com.huimao.map.data.AppSettingsKeys
import com.huimao.map.data.dataStore
import com.huimao.map.navigation.CarLane
import com.huimao.map.navigation.CarNavigationBridge
import com.huimao.map.navigation.NaviEngineManager
import com.huimao.map.navigation.SdkRoutePlanner
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Locale

private const val TAG = "NaviActivity"

class NaviActivity : Activity() {
    private var guideCreated = false
    private var guideCreateAttempted = false
    private var closingNavi = false
    private var routePlanAttempt = 0
    private var naviEngineReady = false
    private var routePlanStarted = false
    // 预览页选定的方案（SDK 选路用）：导航不再另起一套默认选路，预览选第几条就导航第几条。
    private var previewRouteIndex = 0
    private var previewRoutePref = IBNRoutePlanManager.RoutePlanPreference.ROUTE_PLAN_PREFERENCE_DEFAULT
    private var selectedRouteIndex: Int? = null
    // 预览页已用 SDK 算好路线：NaviActivity 复用，不再自算路。
    private var alreadyPlanned = false
    private var locationWaitStartedAt = 0L
    private var locationClient: LocationClient? = null
    private var locationListener: BDAbstractLocationListener? = null
    private var systemTts: TextToSpeech? = null
    private var guideRootView: View? = null
    private var voiceEnabled = true
    private var ttsProvider = "system"
    private var baiduTtsAppId = ""
    private var baiduTtsApiKey = ""
    private var baiduTtsSecretKey = ""
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private val phoneFrameHandler = Handler(Looper.getMainLooper())
    @Volatile private var phoneFrameCapturing = false
    @Volatile private var phoneFrameInFlight = false
    @Volatile private var lastPhoneFrameAt = 0L
    @Volatile private var ttsReady = false

    private var startLat = 0.0
    private var startLng = 0.0
    private var destLat = 0.0
    private var destLng = 0.0
    private var destName = ""
    private var lastReliableLocationAt = 0L
    private var lastPushedLocation: BDLocation? = null
    private var routeLocationWaitStartedAt = 0L
    private var lastLaneKey = ""
    private var lastTurnIconKey = ""
    private var lastEnlargeCaptureAt = 0L

    // 车机地图小样：百度原生 MapView（藏在诱导 View 底下，横向大尺寸渲染后截帧喂车机）。
    private var carMapView: MapView? = null
    private val carMapSnapshotHandler = Handler(Looper.getMainLooper())
    private var carMapSnapshotting = false
    private var firstCarMapFrameLogged = false

    // AA 连接时的手机端驾车提示页（上架合规）：覆盖诱导页，点「我未在驾车」恢复。
    private var drivingPromptView: android.view.View? = null
    private var drivingPromptDismissed = false
    private val carConnectionListener: () -> Unit = {
        runOnUiThread { refreshDrivingPrompt() }
    }

    private val naviListener = object : IBNaviListener() {
        override fun onNaviGuideEnd() {
            // SDK 在重算路线、切换诱导页面时也可能短暂回调 guideEnd，不能据此直接退出。
            // 只有明确的 onArriveDestination 才结束导航。
            Log.w(TAG, "onNaviGuideEnd received; keep activity until destination or user exit")
        }
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
        override fun onLaneInfoUpdate(
            action: IBNaviListener.Action?,
            lanes: MutableList<com.baidu.navisdk.adapter.struct.BNavLineItem>?
        ) {
            // 车道回调同值会高频重复，只在内容变化时更新桥状态与图标。
            if (action?.toString() == "HIDE" || lanes.isNullOrEmpty()) {
                if (CarNavigationBridge.state.lanes.isNotEmpty()) {
                    lastLaneKey = ""
                    CarNavigationBridge.updateLaneIcons(emptyList())
                    CarNavigationBridge.update { it.copy(lanes = emptyList()) }
                }
                return
            }
            val key = lanes.joinToString(",") { it.resName.orEmpty() }
            if (key == lastLaneKey) return
            lastLaneKey = key
            // resName 形如 nsdk_ic_lane_turn_left_90 / nsdk_ic_lane_straight_gray：
            // 去前缀得方向键，_gray 后缀表示不推荐通行（灰色），无后缀为推荐（高亮）。
            val parsed = lanes.map { item ->
                val raw = item.resName.orEmpty()
                CarLane(
                    direction = raw.removePrefix("nsdk_ic_lane_").removeSuffix("_gray"),
                    recommended = !raw.endsWith("_gray")
                )
            }
            CarNavigationBridge.updateLaneIcons(lanes.map { drawableToBitmap(it.drawable) })
            CarNavigationBridge.update { it.copy(lanes = parsed) }
        }
        override fun onEnlargeMapUpdate(info: com.baidu.navisdk.adapter.struct.BNaviEnlargeMapInfo?) {
            if (info == null) return
            if (info.action?.toString() == "HIDE") {
                if (CarNavigationBridge.state.junctionActive) {
                    CarNavigationBridge.clearJunctionFrame()
                    CarNavigationBridge.update { it.copy(junctionActive = false, junctionRemainMeters = 0) }
                }
                return
            }
            val remain = info.remainDistance?.toIntOrNull() ?: 0
            CarNavigationBridge.update { previous ->
                previous.copy(
                    junctionActive = true,
                    junctionRemainMeters = remain,
                    // 放大图的剩余距离是当前最可靠的距路口距离，优先于面板解析值。
                    distanceToTurnMeters = if (remain > 0) remain else previous.distanceToTurnMeters
                )
            }
            // dynamicBitmap 恒为 null（2026-10-23 探针实测），只能截取百度给的 View。
            val now = SystemClock.elapsedRealtime()
            if (now - lastEnlargeCaptureAt >= 500L) {
                lastEnlargeCaptureAt = now
                val enlargeView = info.enlargeMap
                if (enlargeView != null) runOnUiThread { captureEnlargeView(enlargeView) }
            }
        }
        override fun onHighWayInfoUpdate(
            action: IBNaviListener.Action?,
            info: com.baidu.navisdk.adapter.struct.BNHighwayInfo?
        ) {
            if (info == null || action?.toString() == "HIDE") {
                if (CarNavigationBridge.state.highwayInfoText.isNotBlank()) {
                    CarNavigationBridge.update { it.copy(highwayInfoText = "") }
                }
                return
            }
            val parts = ArrayList<String>()
            info.exitName?.takeIf { it.isNotBlank() }?.let { name ->
                val code = info.exitCode?.takeIf { it.isNotBlank() }?.let { "($it)" } ?: ""
                val dist = info.exitRemainDist.takeIf { it >= 0 }?.let { formatDistanceForCar(it) } ?: ""
                parts += "出口 $name$code $dist".trim()
            }
            info.gateName?.takeIf { it.isNotBlank() }?.let { name ->
                val dist = info.gateRemainDist.takeIf { it >= 0 }?.let { formatDistanceForCar(it) } ?: ""
                parts += "收费站 $name $dist".trim()
            }
            val service = listOfNotNull(
                info.serviceName?.takeIf { it.isNotBlank() }?.let { it to info.serviceRemainDist },
                info.service2Name?.takeIf { it.isNotBlank() }?.let { it to info.service2RemainDist }
            ).minByOrNull { (_, d) -> if (d >= 0) d else Int.MAX_VALUE }
            service?.let { (name, d) ->
                parts += "服务区 $name ${if (d >= 0) formatDistanceForCar(d) else ""}".trim()
            }
            val text = parts.joinToString(" · ")
            if (text != CarNavigationBridge.state.highwayInfoText) {
                CarNavigationBridge.update { it.copy(highwayInfoText = text) }
            }
        }
        override fun onGuideInfoUpdate(
            info: com.baidu.navisdk.adapter.struct.BNaviInfo?,
            panel: com.baidu.navisdk.adapter.struct.GuidePanelMessage?
        ) {
            if (info == null) return
            val callbackCue = panel?.stringBuilder?.toString()?.trim()?.takeIf { it.isNotBlank() }
                ?: info.roadName?.takeIf { it.isNotBlank() } ?: "继续行驶"
            val callbackDistance = info.distance.takeIf { it > 0 }
            val iconName = info.turnIconName.orEmpty()
            val roadName = info.roadName.orEmpty()
            // 百度直接给的转向图标位图同步给车机端自绘卡片使用（按图标名去重）。
            info.turnIcon?.let { icon ->
                if (iconName != lastTurnIconKey) {
                    lastTurnIconKey = iconName
                    CarNavigationBridge.updateTurnIcon(icon)
                }
            }

            // 回调可能不在主线程，且触发时百度原生 View 可能还没完成这一帧文本更新。
            // 先用回调立即更新兜底，再在主线程当前帧和下一帧读取手机端最终渲染结果覆盖。
            CarNavigationBridge.update { previous ->
                previous.copy(
                    instruction = callbackCue,
                    maneuverType = inferCarManeuver(iconName, callbackCue),
                    roadName = roadName,
                    distanceToTurnMeters = callbackDistance ?: previous.distanceToTurnMeters
                )
            }
            // 百度回调在长路段上有时给出道路级信息，而手机面板显示当前细分动作。
            // 只在主线程延迟读取当前真正可见的文字控件；不修改导航 View 或 SDK 状态。
            runOnUiThread {
                guideRootView?.postDelayed({
                    runCatching { syncVisibleGuidePanelToCar(iconName, roadName) }
                        .onFailure { Log.w(TAG, "Visible guide panel sync skipped", it) }
                }, 100L)
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
        private const val EXTRA_ROUTE_INDEX = "route_index"
        private const val EXTRA_ROUTE_PREF = "route_pref"
        private const val EXTRA_ALREADY_PLANNED = "already_planned"

        fun start(context: Context, destLat: Double, destLng: Double, destName: String,
                  startLat: Double = 0.0, startLng: Double = 0.0,
                  routeIndex: Int = 0,
                  routePref: Int = IBNRoutePlanManager.RoutePlanPreference.ROUTE_PLAN_PREFERENCE_DEFAULT,
                  alreadyPlanned: Boolean = false) {
            context.startActivity(Intent(context, NaviActivity::class.java).apply {
                putExtra(EXTRA_START_LAT, startLat)
                putExtra(EXTRA_START_LNG, startLng)
                putExtra(EXTRA_DEST_LAT, destLat)
                putExtra(EXTRA_DEST_LNG, destLng)
                putExtra(EXTRA_DEST_NAME, destName)
                putExtra(EXTRA_ROUTE_INDEX, routeIndex)
                putExtra(EXTRA_ROUTE_PREF, routePref)
                putExtra(EXTRA_ALREADY_PLANNED, alreadyPlanned)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }

        /** 预览页驾车策略 → 导航 SDK 算路偏好（与白马地图的偏好档位对齐）。 */
        fun preferenceFor(policy: com.huimao.map.map.DrivingPolicyOption): Int = when (policy) {
            com.huimao.map.map.DrivingPolicyOption.TIME_FIRST ->
                IBNRoutePlanManager.RoutePlanPreference.ROUTE_PLAN_PREFERENCE_TIME_FIRST
            com.huimao.map.map.DrivingPolicyOption.AVOID_JAM ->
                IBNRoutePlanManager.RoutePlanPreference.ROUTE_PLAN_PREFERENCE_AVOID_TRAFFIC_JAM
            com.huimao.map.map.DrivingPolicyOption.DIS_FIRST ->
                IBNRoutePlanManager.RoutePlanPreference.ROUTE_PLAN_PREFERENCE_DISTANCE_FIRST
            com.huimao.map.map.DrivingPolicyOption.FEE_FIRST ->
                IBNRoutePlanManager.RoutePlanPreference.ROUTE_PLAN_PREFERENCE_NOTOLL
        }
    }

    private val routePlanHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                IBNRoutePlanManager.MSG_NAVI_ROUTE_PLAN_START -> Log.i(TAG, "Route plan started")
                IBNRoutePlanManager.MSG_NAVI_ROUTE_PLAN_SUCCESS -> {
                    Log.i(TAG, "Route plan success; waiting for SDK TO_NAVI")
                    applyPreviewRouteSelection()
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
        CarNavigationBridge.addListener(carConnectionListener)
        startLat = intent.getDoubleExtra(EXTRA_START_LAT, 0.0)
        startLng = intent.getDoubleExtra(EXTRA_START_LNG, 0.0)
        destLat = intent.getDoubleExtra(EXTRA_DEST_LAT, 0.0)
        destLng = intent.getDoubleExtra(EXTRA_DEST_LNG, 0.0)
        destName = intent.getStringExtra(EXTRA_DEST_NAME) ?: ""
        previewRouteIndex = intent.getIntExtra(EXTRA_ROUTE_INDEX, 0)
        previewRoutePref = intent.getIntExtra(
            EXTRA_ROUTE_PREF,
            IBNRoutePlanManager.RoutePlanPreference.ROUTE_PLAN_PREFERENCE_DEFAULT
        )
        alreadyPlanned = intent.getBooleanExtra(EXTRA_ALREADY_PLANNED, false)
        loadVoiceSettings()
        audioManager = getSystemService(AudioManager::class.java)
        if (voiceEnabled && ttsProvider == "system") initSystemTts()
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

    private fun loadVoiceSettings() {
        try {
            val prefs = runBlocking { applicationContext.dataStore.data.first() }
            voiceEnabled = prefs[AppSettingsKeys.VOICE_ENABLED] ?: true
            ttsProvider = prefs[AppSettingsKeys.TTS_PROVIDER] ?: "system"
            baiduTtsAppId = prefs[AppSettingsKeys.BAIDU_TTS_APP_ID].orEmpty().trim()
            baiduTtsApiKey = prefs[AppSettingsKeys.BAIDU_TTS_API_KEY].orEmpty().trim()
            baiduTtsSecretKey = prefs[AppSettingsKeys.BAIDU_TTS_SECRET_KEY].orEmpty().trim()
        } catch (e: Throwable) { Log.w(TAG, "Load voice settings failed", e) }
    }

    private val ttsStateListener = object : IBNTTSManager.IOnTTSPlayStateChangedListener {
        override fun onPlayStart() { requestSpeechAudioFocus() }
        override fun onPlayEnd(speechId: String?) { abandonSpeechAudioFocus() }
        override fun onPlayError(code: Int, message: String?) {
            Log.w(TAG, "TTS play error $code: $message")
            abandonSpeechAudioFocus()
        }
    }

    private fun requestSpeechAudioFocus() {
        val manager = audioManager ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { }
            .setWillPauseWhenDucked(false)
            .build()
        audioFocusRequest?.let { manager.abandonAudioFocusRequest(it) }
        audioFocusRequest = request
        manager.requestAudioFocus(request)
    }

    private fun abandonSpeechAudioFocus() {
        val request = audioFocusRequest ?: return
        audioManager?.abandonAudioFocusRequest(request)
        audioFocusRequest = null
    }

    private fun initSelectedTts() {
        if (!voiceEnabled) return
        BaiduNaviManagerFactory.getTTSManager().setOnTTSStateChangedListener(ttsStateListener)
        if (ttsProvider == "baidu") initBaiduTts() else if (ttsReady) registerOuterTts()
    }

    private fun initBaiduTts() {
        if (baiduTtsAppId.isBlank() || baiduTtsApiKey.isBlank() || baiduTtsSecretKey.isBlank()) {
            Log.w(TAG, "Baidu TTS credentials missing; falling back to system TTS")
            ttsProvider = "system"
            initSystemTts()
            return
        }
        try {
            val config = BNTTsInitConfig.Builder()
                .context(applicationContext)
                .appId(baiduTtsAppId)
                .appKey(baiduTtsApiKey)
                .secretKey(baiduTtsSecretKey)
                .listener(object : ITTSInitListener {
                    override fun onSuccess() { Log.i(TAG, "Baidu SDK TTS initialized") }
                    override fun onFail(code: Int) {
                        Log.e(TAG, "Baidu SDK TTS init failed: $code; falling back to system TTS")
                        runOnUiThread { ttsProvider = "system"; initSystemTts() }
                    }
                }).build()
            BaiduNaviManagerFactory.getTTSManager().initTTS(config)
        } catch (e: Throwable) {
            Log.e(TAG, "Baidu SDK TTS init exception; falling back to system TTS", e)
            ttsProvider = "system"
            initSystemTts()
        }
    }

    private fun initSystemTts() {
        if (systemTts != null) return
        systemTts = TextToSpeech(applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                systemTts?.language = Locale.SIMPLIFIED_CHINESE
                systemTts?.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                systemTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { requestSpeechAudioFocus() }
                    override fun onDone(utteranceId: String?) { abandonSpeechAudioFocus() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { abandonSpeechAudioFocus() }
                    override fun onError(utteranceId: String?, errorCode: Int) { abandonSpeechAudioFocus() }
                })
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
                    override fun stopTTS() { systemTts?.stop(); abandonSpeechAudioFocus() }
                    override fun pauseTTS() { systemTts?.stop(); abandonSpeechAudioFocus() }
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
                    if (loc == null || loc.latitude == 0.0 || loc.longitude == 0.0) {
                        markCarLocationLost()
                        return
                    }
                    val valid = isUsableNavigationLocation(loc)
                    if (valid) {
                        pushLocationToNaviEngine(loc)
                    } else {
                        markCarLocationLost(loc)
                    }
                    // 微信位置只提供终点；首帧定位到达且导航引擎已就绪后再开始算路。
                    if (valid && naviEngineReady && !routePlanStarted && startLat == 0.0 && startLng == 0.0) {
                        startLat = loc.latitude
                        startLng = loc.longitude
                        runOnUiThread { startRoutePlanWhenLocationReady() }
                    }
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

    private fun isUsableNavigationLocation(loc: BDLocation): Boolean {
        val typeOk = when (loc.locType) {
            BDLocation.TypeGpsLocation,
            BDLocation.TypeNetWorkLocation,
            BDLocation.TypeOffLineLocation -> true
            else -> false
        }
        if (!typeOk || loc.latitude == 0.0 || loc.longitude == 0.0) return false
        val radius = loc.radius.takeIf { it.isFinite() && it > 0f } ?: 999f
        if (radius > 120f) return false
        val previous = lastPushedLocation
        if (previous != null && previous.latitude != 0.0 && previous.longitude != 0.0) {
            val distance = FloatArray(1)
            android.location.Location.distanceBetween(
                previous.latitude, previous.longitude,
                loc.latitude, loc.longitude,
                distance
            )
            val elapsedSeconds = ((SystemClock.elapsedRealtime() - lastReliableLocationAt).coerceAtLeast(1L)) / 1000.0
            val speedMps = (loc.speed.takeIf { it.isFinite() && it >= 0f } ?: 0f) / 3.6f
            val allowedJump = 80.0 + radius + (speedMps.coerceAtLeast(8f) * elapsedSeconds * 2.5)
            if (lastReliableLocationAt > 0L && distance[0] > allowedJump) {
                Log.w(TAG, "Drop jumpy location distance=${distance[0]} allowed=$allowedJump radius=$radius")
                return false
            }
        }
        return true
    }

    private fun markCarLocationLost(loc: BDLocation? = null) {
        val radius = loc?.radius?.takeIf { it.isFinite() && it > 0f } ?: CarNavigationBridge.state.accuracyMeters
        CarNavigationBridge.update { previous ->
            previous.copy(
                accuracyMeters = radius,
                locationReliable = false,
                inertialNavigation = previous.latitude != 0.0 && previous.longitude != 0.0
            )
        }
    }

    private fun pushLocationToNaviEngine(bdLoc: BDLocation) {
        lastReliableLocationAt = SystemClock.elapsedRealtime()
        lastPushedLocation = BDLocation(bdLoc)
        // Android Auto 自绘路线来自百度地图规划页（BD09LL），车辆位置也必须保持 BD09LL。
        CarNavigationBridge.update {
            it.copy(
                latitude = bdLoc.latitude,
                longitude = bdLoc.longitude,
                bearing = bdLoc.direction,
                speedKmh = bdLoc.speed.toInt().coerceAtLeast(0),
                accuracyMeters = bdLoc.radius.takeIf { radius -> radius.isFinite() && radius > 0f } ?: 0f,
                lastLocationTimeMs = System.currentTimeMillis(),
                locationReliable = true,
                inertialNavigation = false
            )
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
            // 车机地图小样：同一份 BD09 定位喂给 MapView 定位层（主线程）。
            runOnUiThread {
                CarNavigationBridge.carMapDiagLocFeeds++
                carMapView?.map?.let { map ->
                    runCatching {
                        map.setMyLocationData(
                            MyLocationData.Builder()
                                .latitude(bdLoc.latitude)
                                .longitude(bdLoc.longitude)
                                .accuracy(bdLoc.radius.takeIf { it.isFinite() && it > 0f } ?: 12f)
                                .direction(bdLoc.direction)
                                .build()
                        )
                    }
                }
            }
        } catch (e: Throwable) { Log.w(TAG, "Push external navigation location failed", e) }
    }

    private fun initNaviEngine() {
        val ak = savedAk()
        if (ak.isBlank() || ak == "PLACEHOLDER_AK") { showError("❌ 未配置百度地图 AK"); return }
        // 引擎初始化已抽到 NaviEngineManager：预览页可能已经初始化过，
        // 这里复用即可。TTS 仍归 NaviActivity（引擎早已就绪时 initSuccess
        // 不会再触发，TTS 必须在这里独立初始化）。
        NaviEngineManager.ensureInitialized(
            this, ak,
            onReady = {
                runOnUiThread {
                    initSelectedTts()
                // 启动参数中的起点只作为兜底，不写入导航引擎定位通道。
                // 部分手机/车机切换时 MainActivity 会给出跨国缓存点；若把它设为可靠基准，
                // 后续真实定位会被防跳点逻辑误杀，最终导致路线规划失败。
                naviEngineReady = true
                // 已规划模式也走同一套等定位逻辑：不等定位直接建诱导，
                // 诱导界面拿不到定位 fix，就会停在全览 + “定位信号中”。
                startRoutePlanWhenLocationReady()
                }
            },
            onFailed = { errCode ->
                runOnUiThread { showError("❌ 导航引擎初始化失败 errCode=$errCode") }
            }
        )
    }

    /**
     * 预览页已算好路线时的进入路径：不再自算路，直接按预览选定的方案进诱导。
     * 调用前已由等定位逻辑确认 SDK 里有现成路线，无路线时走老流程自算路。
     */
    private fun enterPlannedGuide() {
        applyPreviewRouteSelection()
        syncRouteGeometryToCar()
        startGuideOnce()
    }

    // RouteResultManager 不在这里提前初始化；routePlan 适配层会在算路完成后
    // 自行切换到 TO_NAVI，提前 startNavi 会复用上一条诱导路线。

    private fun startRoutePlanWhenLocationReady() {
        if (routePlanStarted || closingNavi) return
        val freshLoc = lastPushedLocation?.takeIf {
            it.latitude != 0.0 && it.longitude != 0.0 &&
                SystemClock.elapsedRealtime() - lastReliableLocationAt <= 10_000L
        }
        val fallbackLoc = locationClient?.lastKnownLocation?.takeIf {
            it.latitude != 0.0 && it.longitude != 0.0
        } ?: if (startLat != 0.0 && startLng != 0.0) {
            BDLocation().apply {
                latitude = startLat
                longitude = startLng
                radius = 80f
                locType = BDLocation.TypeNetWorkLocation
            }
        } else null
        val loc = freshLoc ?: if (routeLocationWaitStartedAt > 0L &&
            SystemClock.elapsedRealtime() - routeLocationWaitStartedAt >= 5_000L) fallbackLoc else null
        if (loc != null) {
            startLat = loc.latitude
            startLng = loc.longitude
            if (freshLoc != null) pushLocationToNaviEngine(loc)
            routePlanStarted = true
            // 预览页已算好路线时，定位就绪后直接进诱导，不再自算路；
            // SDK 里没有现成路线时（如进程被回收）回落老流程自算路。
            if (alreadyPlanned && SdkRoutePlanner.hasPlannedRoute()) {
                enterPlannedGuide()
            } else {
                startRoutePlan()
            }
            return
        }
        if (routeLocationWaitStartedAt == 0L) routeLocationWaitStartedAt = SystemClock.elapsedRealtime()
        if (locationWaitStartedAt == 0L) {
            locationWaitStartedAt = System.currentTimeMillis()
            showWaitingForLocation()
        }
        if (System.currentTimeMillis() - locationWaitStartedAt >= 15_000L) {
            showError("❌ 15 秒内未获取到当前位置\n请确认已开启定位和精确位置权限后重试")
            return
        }
        Handler(Looper.getMainLooper()).postDelayed({ startRoutePlanWhenLocationReady() }, 500L)
    }

    private fun showWaitingForLocation() {
        runOnUiThread {
            setContentView(TextView(this).apply {
                setTextColor(0xFF222222.toInt())
                setBackgroundColor(0xFFFFFFFF.toInt())
                textSize = 18f
                gravity = android.view.Gravity.CENTER
                setPadding(48, 48, 48, 48)
                text = "正在获取当前位置…\n\n定位完成后将自动进入导航"
            })
        }
    }

    private fun startRoutePlan() {
        if (destLat == 0.0 || destLng == 0.0) { showError("❌ 终点坐标无效"); return }
        try {
            // 导航适配层会依据 SDKInitializer.getCoordType() 自动转换节点。
            // 全局 CoordType 是 BD09LL，因此这里必须直接传 BD09LL，不能预转 GCJ-02。
            val startBd = lastPushedLocation?.takeIf {
                it.latitude != 0.0 && it.longitude != 0.0 &&
                    SystemClock.elapsedRealtime() - lastReliableLocationAt <= 10_000L
            } ?: if (startLat != 0.0 && startLng != 0.0) BDLocation().apply {
                latitude = startLat; longitude = startLng; radius = 80f; locType = BDLocation.TypeNetWorkLocation
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
                previewRoutePref,
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

    private data class NativeGuidePanel(val distanceMeters: Int, val instruction: String)

    private fun syncVisibleGuidePanelToCar(iconName: String, roadName: String) {
        val native = readVisibleGuidePanel() ?: return
        CarNavigationBridge.update { previous ->
            val cue = native.instruction.ifBlank { previous.instruction }
            previous.copy(
                instruction = cue,
                maneuverType = inferCarManeuver(iconName, cue),
                roadName = roadName.ifBlank { previous.roadName },
                distanceToTurnMeters = native.distanceMeters.takeIf { it > 0 }
                    ?: previous.distanceToTurnMeters
            )
        }
    }

    private fun readVisibleGuidePanel(): NativeGuidePanel? {
        val root = guideRootView ?: return null
        val visibleTexts = HashMap<String, MutableList<Pair<Int, String>>>()
        fun walk(view: View) {
            if (view.visibility != View.VISIBLE || !view.isShown || view.alpha <= 0.05f ||
                view.width <= 0 || view.height <= 0) return
            if (view is TextView && view.id != View.NO_ID) {
                val name = runCatching { resources.getResourceEntryName(view.id) }.getOrNull()
                val value = view.text?.toString()?.trim().orEmpty()
                if (name != null && value.isNotBlank()) {
                    val location = IntArray(2)
                    view.getLocationOnScreen(location)
                    // 同名控件可能同时存在于隐藏模式中；取屏幕上方当前显示的那组。
                    visibleTexts.getOrPut(name) { ArrayList() }.add(location[1] to value)
                }
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(root)
        fun text(name: String): String = visibleTexts[name]?.minByOrNull { it.first }?.second.orEmpty()
        val distanceText = (text("bnav_rg_sg_after_meters_info") +
            text("bnav_rg_sg_after_label_info")).trim()
        val instruction = listOf(
            text("bnav_rg_sg_link_info") + text("bnav_rg_sg_go_where_info"),
            text("bnav_rg_sg_go_label_tv") + text("bnav_rg_sg_go_where_info"),
            text("bnav_rg_enter_next_road") + text("bnav_rg_next_road"),
            text("bnav_rg_highway_enter_next_road") + text("bnav_rg_highway_next_road"),
            text("bnav_rg_hw_go_to_word") + text("bnav_rg_hw_go_where_multi_tv"),
            text("bnav_rg_enlarge_next_road")
        ).map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
        val distance = parseGuideDistanceMeters(distanceText)
        return if (distance > 0 || instruction.isNotBlank()) NativeGuidePanel(distance, instruction) else null
    }

    private fun syncNativeGuidePanelToCar(iconName: String, roadName: String) {
        val native = readNativeGuidePanel() ?: return
        CarNavigationBridge.update { previous ->
            val cue = native.instruction.takeIf { it.isNotBlank() } ?: previous.instruction
            previous.copy(
                instruction = cue,
                maneuverType = inferCarManeuver(iconName, cue),
                roadName = roadName.ifBlank { previous.roadName },
                distanceToTurnMeters = native.distanceMeters.takeIf { it > 0 }
                    ?: previous.distanceToTurnMeters
            )
        }
    }

    private fun readNativeGuidePanel(): NativeGuidePanel? {
        val root = guideRootView ?: return null
        fun id(name: String): Int? = resources.getIdentifier(name, "id", packageName).takeIf { it != 0 }
            ?: resources.getIdentifier(name, "id", "com.baidu.navisdk.embed").takeIf { it != 0 }
        fun text(name: String): String {
            val view = id(name)?.let { root.findViewById<View>(it) } ?: return ""
            fun collect(v: View): String = when (v) {
                is TextView -> v.text?.toString()?.trim().orEmpty()
                is ViewGroup -> (0 until v.childCount).joinToString("") { collect(v.getChildAt(it)) }
                else -> ""
            }
            return collect(view).trim()
        }
        val distanceText = listOf(
            text("bnav_rg_sg_after_meters_info") + text("bnav_rg_sg_after_label_info"),
            text("bnav_rg_distance_num_text") + text("bnav_rg_after_label_info"),
            text("nav_guide_info_distance")
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        val instruction = listOf(
            text("bnav_rg_sg_link_info") + text("bnav_rg_sg_go_where_info"),
            text("bnav_rg_sg_go_label_tv") + text("bnav_rg_sg_go_where_info"),
            text("bnav_rg_enter_next_road") + text("bnav_rg_next_road"),
            text("bnav_rg_highway_enter_next_road") + text("bnav_rg_highway_next_road"),
            text("bnav_rg_hw_go_to_word") + text("bnav_rg_hw_go_where_multi_tv"),
            text("bnav_rg_enlarge_next_road")
        ).map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
        val distance = parseGuideDistanceMeters(distanceText)
        return if (distance > 0 || instruction.isNotBlank()) NativeGuidePanel(distance, instruction) else null
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

    private fun drawableToBitmap(drawable: android.graphics.drawable.Drawable?): Bitmap? {
        if (drawable == null) return null
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 72
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 72
        return runCatching {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            bmp
        }.getOrNull()
    }

    /** 把百度路口放大图 View 截成位图存入桥，供车机端 Surface 绘制。必须在主线程调用。 */
    private fun captureEnlargeView(view: View) {
        runCatching {
            if (view.width <= 0 || view.height <= 0) {
                val ws = View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
                val hs = View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY)
                view.measure(ws, hs)
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            }
            if (view.width <= 0 || view.height <= 0) return
            val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bmp))
            CarNavigationBridge.updateJunctionFrame(bmp)
        }.onFailure { Log.w(TAG, "Enlarge view capture failed", it) }
    }

    /**
     * 车机地图小样：配置垫在诱导 View 底下的百度原生 MapView。
     * FOLLOWING 定位模式：地图自动跟随定位并按方向旋转（车头向上）；开路况图层。
     */
    private fun configureCarMap(mv: MapView) {
        runCatching {
            mv.showZoomControls(false)
            mv.showScaleControl(false)
            val map = mv.map ?: return
            map.isMyLocationEnabled = true
            map.setTrafficEnabled(true)
            map.setOnMapLoadedCallback {
                CarNavigationBridge.carMapDiagLoaded = true
                Log.i(TAG, "CarMap spike: map loaded")
            }
            map.setMyLocationConfiguration(
                MyLocationConfiguration(
                    // 罗盘模式：地图随定位方向旋转（车头向上）；FOLLOWING 只跟随不旋转。
                    MyLocationConfiguration.LocationMode.COMPASS,
                    true,
                    null
                )
            )
            map.animateMapStatus(
                MapStatusUpdateFactory.newMapStatus(
                    MapStatus.Builder().zoom(17.5f).overlook(-30f).build()
                )
            )
        }.onFailure { Log.w(TAG, "Car MapView configure failed", it) }
    }

    private val carMapSnapshotTicker = object : Runnable {
        override fun run() {
            if (!guideCreated || closingNavi) { carMapSnapshotting = false; return }
            val map = carMapView?.map
            if (map != null) {
                CarNavigationBridge.carMapDiagSnapshotCalls++
                runCatching {
                    map.snapshot { bmp ->
                        if (bmp != null) {
                            CarNavigationBridge.carMapDiagLastSize = "${bmp.width}x${bmp.height}"
                            if (!firstCarMapFrameLogged) {
                                firstCarMapFrameLogged = true
                                Log.i(TAG, "CarMap spike: first snapshot ${bmp.width}x${bmp.height}")
                            }
                            CarNavigationBridge.updateCarMapFrame(bmp)
                        } else {
                            CarNavigationBridge.carMapDiagSnapshotNulls++
                        }
                    }
                }.onFailure { CarNavigationBridge.carMapDiagSnapshotNulls++ }
            }
            carMapSnapshotHandler.postDelayed(this, 300L)
        }
    }

    private fun startCarMapSnapshots() {
        if (carMapSnapshotting) return
        carMapSnapshotting = true
        carMapSnapshotHandler.post(carMapSnapshotTicker)
    }

    private fun stopCarMapSnapshots() {
        carMapSnapshotting = false
        carMapSnapshotHandler.removeCallbacks(carMapSnapshotTicker)
    }

    private fun formatDistanceForCar(meters: Int): String = if (meters >= 1000) {
        String.format(Locale.US, "%.1f公里", meters / 1000.0)
    } else "${meters.coerceAtLeast(0)}米"

    // 内部转向分类：编码与 NavCarService 中的 Maneuver 类型映射一一对应。
    private fun inferCarManeuver(iconName: String, cue: String): Int {
        val text = "$iconName $cue".lowercase()
        return when {
            "掉头" in text || "u_turn" in text || "uturn" in text -> 7
            "左转" in text || "turn_left" in text -> 2
            "右转" in text || "turn_right" in text -> 3
            "靠左" in text || "keep_left" in text -> 4
            "靠右" in text || "keep_right" in text -> 5
            "到达" in text ->  destinationManeuverCode
            else -> 1
        }
    }

    private val destinationManeuverCode: Int get() = 6

    /**
     * 预览页选定的方案 → SDK 选路（白马地图同款 selectRoute）。
     * 在算路成功后、诱导开始前执行，导航与车机轨迹都跟随同一条；
     * 失败或越界时保持 SDK 默认选路，不阻塞导航。
     */
    private fun applyPreviewRouteSelection() {
        try {
            val count = BaiduNaviManagerFactory.getRoutePlanManager().getRoutePlanInfo()
                ?.routeInfoLatLngLists?.size ?: 0
            if (count <= 0) return
            val index = previewRouteIndex.coerceIn(0, count - 1)
            BaiduNaviManagerFactory.getRouteGuideManager().selectRoute(index)
            selectedRouteIndex = index
            Log.i(TAG, "Applied preview route selection: $index/$count")
        } catch (e: Throwable) {
            Log.w(TAG, "applyPreviewRouteSelection failed", e)
        }
    }

    private fun syncRouteGeometryToCar() {
        // 路线规划成功后始终保存百度路线。Android Auto 可能在手机导航开始后才连接，
        // 若按监听器存在与否跳过同步，车机将永远拿不到路线轨迹。
        try {
            val infos = BaiduNaviManagerFactory.getRoutePlanManager().getRoutePlanInfo()
            val selected = selectedRouteIndex
                ?: BaiduNaviManagerFactory.getRoutePlanManager().selectRouteId
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
            // 车机地图小样：同一条路线画进 MapView（BD09 原生坐标，零转换）。
            runOnUiThread {
                carMapView?.map?.let { map ->
                    runCatching {
                        map.clear()
                        val pts = sampled.map { BaiduLatLng(it.first, it.second) }
                        if (pts.size >= 2) {
                            map.addOverlay(
                                PolylineOptions().points(pts).width(14f)
                                    .color(0xFF1689FF.toInt()).zIndex(10)
                            )
                        }
                    }.onFailure { Log.w(TAG, "Car map polyline failed", it) }
                }
            }
            Log.i(TAG, "Synced ${sampled.size}/$total sampled route points to Android Auto")
        } catch (e: Throwable) {
            Log.w(TAG, "Route geometry sync failed", e)
        }
    }

    // 百度导航路线点按 SDK 返回的原始坐标同步；不再根据单次定位自动转换坐标系。

    private fun startGuideOnce() {
        if (guideCreated || guideCreateAttempted || closingNavi) return
        guideCreateAttempted = true
        try {
            try { BaiduNaviManagerFactory.getMapManager().getMapView() } catch (_: Throwable) {}
            val manager = BaiduNaviManagerFactory.getRouteGuideManager()
            val view = manager.onCreate(this, BNGuideConfig.Builder().build())
            if (view == null) { showError("❌ 百度导航界面初始化失败：onCreate 返回 null"); return }
            guideCreated = true
            guideRootView = view
            // 百度导航根 View 在刘海/安全区可能露出自身默认白底。仅替换根背景，
            // 不改变 View 尺寸、Insets、系统栏或任何子控件布局。
            view.setBackgroundColor(0xFF1F2937.toInt())
            CarNavigationBridge.start(destName)
            // 部分 SDK 版本在 MSG_NAVI_ROUTE_PLAN_SUCCESS 时路线几何尚未填充完整，
            // 导航 View 创建后再同步一次，避免车机永远停在“正在加载路线地图”。
            syncRouteGeometryToCar()
            // 部分长路线的折线列表会在导航 View 创建后异步补齐，延迟再同步两次。
            Handler(Looper.getMainLooper()).postDelayed({
                if (!closingNavi) syncRouteGeometryToCar()
            }, 1000)
            Handler(Looper.getMainLooper()).postDelayed({
                if (!closingNavi) syncRouteGeometryToCar()
            }, 3000)
            // 车机地图 v3：MapView 垫在诱导 View 底层按 1600x900 全尺寸渲染（手机端不可见），
            // 截帧给车机当高清底图；导航期间屏幕常亮、MapView 不随 Activity 暂停，
            // 保证它持续活跃渲染。诊断计数保留，车机诊断行可核对截帧成色。
            val container = android.widget.FrameLayout(this)
            CarNavigationBridge.resetCarMapDiag()
            val mvResult = runCatching { MapView(this) }
            val mv = mvResult.getOrNull()
            CarNavigationBridge.carMapDiagCreated = mv != null
            CarNavigationBridge.carMapDiagCreateError =
                mvResult.exceptionOrNull()
                    ?.let { "${it.javaClass.simpleName}: ${it.message}".take(60) } ?: ""
            if (mv != null) {
                carMapView = mv
                container.addView(
                    mv,
                    android.widget.FrameLayout.LayoutParams(1600, 900).apply {
                        gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                    }
                )
                configureCarMap(mv)
                runCatching { mv.onResume() }
            }
            container.addView(view, android.widget.FrameLayout.LayoutParams(-1, -1))
            setContentView(container)
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            startCarMapSnapshots()
            buildDrivingPrompt()
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
            startPhoneFrameCapture()
            locationClient?.lastKnownLocation?.let { pushLocationToNaviEngine(it) }
            Log.i(TAG, "Baidu native guide attached")
        } catch (e: Throwable) {
            Log.e(TAG, "Native guide create failed", e)
            diagnoseGuideLayout(e)
        }
    }

    // ===== AA 驾车提示页：车机连接时覆盖手机端诱导页，点「我未在驾车」恢复 =====
    private fun buildDrivingPrompt() {
        if (drivingPromptView != null) return
        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(0xFF0B1520.toInt())
            visibility = android.view.View.GONE
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val title = TextView(this).apply {
            text = "正在驾车，请使用车机端"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 26f
            gravity = Gravity.CENTER
        }
        val sub = TextView(this).apply {
            text = "为保证行车安全，请通过车机屏幕查看导航信息"
            setTextColor(0xFFB9C6D2.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
        }
        val button = Button(this).apply {
            text = "我未在驾车"
            setOnClickListener {
                drivingPromptDismissed = true
                refreshDrivingPrompt()
            }
        }
        column.addView(title)
        column.addView(sub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 36 })
        column.addView(button, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 72 })
        root.addView(
            column,
            android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
                setMargins(72, 0, 72, 0)
            }
        )
        addContentView(root, android.widget.FrameLayout.LayoutParams(-1, -1))
        drivingPromptView = root
        refreshDrivingPrompt()
    }

    private fun refreshDrivingPrompt() {
        if (!CarNavigationBridge.carConnected) drivingPromptDismissed = false
        val show = CarNavigationBridge.carConnected && !drivingPromptDismissed
        drivingPromptView?.visibility =
            if (show) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun startPhoneFrameCapture() {
        if (phoneFrameCapturing) return
        phoneFrameCapturing = true
        phoneFrameHandler.post(phoneFrameTicker)
    }

    private fun stopPhoneFrameCapture() {
        phoneFrameCapturing = false
        phoneFrameInFlight = false
        phoneFrameHandler.removeCallbacks(phoneFrameTicker)
    }

    private val phoneFrameTicker = object : Runnable {
        override fun run() {
            if (!phoneFrameCapturing || closingNavi || !guideCreated) return
            capturePhoneNavigationFrame()
            phoneFrameHandler.postDelayed(this, 300L)
        }
    }

    private fun capturePhoneNavigationFrame() {
        val view = guideRootView ?: return
        if (phoneFrameInFlight || view.width <= 0 || view.height <= 0 || !view.isShown) return
        if (SystemClock.elapsedRealtime() - lastPhoneFrameAt < 250L) return
        phoneFrameInFlight = true
        val maxW = 1280
        val scale = (maxW.toFloat() / view.width.toFloat()).coerceAtMost(1f)
        val outW = (view.width * scale).toInt().coerceAtLeast(1)
        val outH = (view.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val finish: (Boolean) -> Unit = { ok ->
            phoneFrameInFlight = false
            if (ok) {
                lastPhoneFrameAt = SystemClock.elapsedRealtime()
                CarNavigationBridge.updatePhoneFrame(bitmap)
            }
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val loc = IntArray(2)
                view.getLocationInWindow(loc)
                val src = android.graphics.Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
                android.view.PixelCopy.request(window, src, bitmap, { result ->
                    if (result == android.view.PixelCopy.SUCCESS && scale < 1f) {
                        val scaled = Bitmap.createScaledBitmap(bitmap, outW, outH, true)
                        bitmap.recycle()
                        CarNavigationBridge.updatePhoneFrame(scaled)
                        scaled.recycle()
                        phoneFrameInFlight = false
                        lastPhoneFrameAt = SystemClock.elapsedRealtime()
                    } else finish(result == android.view.PixelCopy.SUCCESS)
                }, phoneFrameHandler)
                return
            } catch (e: Throwable) {
                Log.w(TAG, "PixelCopy navigation frame failed; using software draw", e)
            }
        }
        try {
            val canvas = android.graphics.Canvas(bitmap)
            canvas.scale(scale, scale)
            view.draw(canvas)
            finish(true)
        } catch (e: Throwable) {
            Log.w(TAG, "Software navigation frame capture failed", e)
            finish(false)
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
        runCatching { carMapView?.onResume() }
        if (guideCreated) {
            BaiduNaviManagerFactory.getRouteGuideManager().onResume()
            startPhoneFrameCapture()
        }
    }
    override fun onPause() {
        stopPhoneFrameCapture()
        // 车机地图的 MapView 不随 Activity 暂停：车机在后台/切走时仍需它继续渲染截帧。
        if (guideCreated) BaiduNaviManagerFactory.getRouteGuideManager().onPause()
        super.onPause()
    }
    override fun onStop() { if (guideCreated) BaiduNaviManagerFactory.getRouteGuideManager().onStop(); super.onStop() }
    override fun onDestroy() {
        CarNavigationBridge.removeListener(carConnectionListener)
        stopPhoneFrameCapture()
        stopCarMapSnapshots()
        runCatching { carMapView?.onDestroy() }
        carMapView = null
        runCatching { window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
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
        abandonSpeechAudioFocus()
        audioManager = null
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (guideCreated && keyCode == KeyEvent.KEYCODE_BACK &&
            BaiduNaviManagerFactory.getRouteGuideManager().onKeyDown(keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }
}
