package com.huimao.map.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
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
import com.baidu.navisdk.adapter.struct.BNTTsInitConfig
import com.baidu.navisdk.adapter.struct.BNaviInitConfig
import com.baidu.navisdk.tts.ITTSInitListener
import com.huimao.map.data.AppSettingsKeys
import com.huimao.map.data.dataStore
import com.huimao.map.navigation.CarNavigationBridge
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
    private var naviEngineReady = false
    private var routePlanStarted = false
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
    @Volatile private var ttsReady = false

    private var startLat = 0.0
    private var startLng = 0.0
    private var destLat = 0.0
    private var destLng = 0.0
    private var destName = ""

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
        return radius <= 120f
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
                    initSelectedTts()
                    // 先把 MainActivity 传来的当前位置送入外部定位通道，再发起算路。
                    // 避免新 LocationClient 首次回调尚未到达时使用 SDK 缓存位置。
                    if (startLat != 0.0 && startLng != 0.0) {
                        pushLocationToNaviEngine(BDLocation().apply {
                            latitude = startLat; longitude = startLng
                            radius = 10f; locType = BDLocation.TypeNetWorkLocation
                        })
                    }
                    naviEngineReady = true
                    startRoutePlanWhenLocationReady()
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

    private fun startRoutePlanWhenLocationReady() {
        if (routePlanStarted || closingNavi) return
        val loc = if (startLat != 0.0 && startLng != 0.0) {
            BDLocation().apply { latitude = startLat; longitude = startLng; radius = 10f }
        } else locationClient?.lastKnownLocation?.takeIf {
            it.latitude != 0.0 && it.longitude != 0.0
        }
        if (loc != null) {
            startLat = loc.latitude
            startLng = loc.longitude
            pushLocationToNaviEngine(loc)
            routePlanStarted = true
            startRoutePlan()
            return
        }
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
                text = "正在获取当前位置…\n\n微信位置已接收，定位完成后将自动规划路线"
            })
        }
    }

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
        // 路线规划成功后始终保存百度路线。Android Auto 可能在手机导航开始后才连接，
        // 若按监听器存在与否跳过同步，车机将永远拿不到路线轨迹。
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
