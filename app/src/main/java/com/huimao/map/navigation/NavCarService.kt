package com.huimao.map.navigation

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.View
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.baidu.navisdk.adapter.BaiduNaviManagerFactory
import com.baidu.navisdk.adapter.IBNMiniMapViewManager
import com.huimao.map.ui.LocationRedirectActivity
import java.util.Locale
import java.util.TimeZone

class NavCarService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    override fun onCreateSession(): Session = NavCarSession()
}

class NavCarSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        handleNavigationIntent(intent)
        return CarMainScreen(carContext)
    }

    /** 会话已存在时，车机主机会通过 onNewIntent 下发新的导航请求。 */
    override fun onNewIntent(intent: Intent) {
        handleNavigationIntent(intent)
    }

    /**
     * 处理 Android Auto / 语音助手下发的 [CarContext.ACTION_NAVIGATE]（geo: / google.navigation:）。
     *
     * 百度导航引擎只能在手机端 NaviActivity 中初始化，因此这里把目的地转交给
     * LocationRedirectActivity，由它完成坐标转换并启动导航；导航一旦开始，
     * CarNavigationBridge 会驱动车机端模板与地图画面。
     */
    private fun handleNavigationIntent(intent: Intent) {
        if (intent.action != CarContext.ACTION_NAVIGATE) return
        val data = intent.data ?: return
        val handoff = Intent(Intent.ACTION_VIEW, data)
            .setClass(carContext, LocationRedirectActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val started = runCatching { carContext.startActivity(handoff) }
            .onFailure { android.util.Log.e("NavCarBaidu", "navigate intent handoff failed", it) }
            .isSuccess
        runCatching {
            CarToast.makeText(
                carContext,
                if (started) "正在手机端规划路线…" else "请解锁手机后重试",
                CarToast.LENGTH_LONG
            ).show()
        }
    }
}

class CarMainScreen(carContext: CarContext) : Screen(carContext) {
    private val navigationManager = carContext.getCarService(NavigationManager::class.java)
    private val appManager = carContext.getCarService(AppManager::class.java)
    private val miniMap: IBNMiniMapViewManager by lazy { BaiduNaviManagerFactory.getMiniMap() }
    private val frameHandler = Handler(Looper.getMainLooper())
    @Volatile private var carSurface: Surface? = null
    @Volatile private var surfaceWidth = 0
    @Volatile private var surfaceHeight = 0
    private var navigationAnnounced = false
    private var miniMapCreated = false
    private var miniMapView: View? = null
    private var lastBaiduBitmapAt = 0L
    private var tickerScheduled = false
    private var pendingInvalidate = false
    private var lastInvalidateAt = 0L

    private val frameTicker = object : Runnable {
        override fun run() {
            if (carSurface?.isValid == true && CarNavigationBridge.state.navigating) {
                renderMap()
                frameHandler.postDelayed(this, FRAME_INTERVAL_MS)
            } else {
                // 导航还没开始或 Surface 已失效：结束本轮循环，并允许后续状态变化时重启。
                tickerScheduled = false
            }
        }
    }

    /**
     * 车机 Surface 通常在导航开始之前就已就绪，此时 frameTicker 第一次执行就会退出。
     * 之前的实现只在 onSurfaceAvailable 中 post ticker，导致刷新循环永久停摆，
     * 车机端表现为黑屏或画面冻结。这里在每次状态变化时确保循环处于运行状态。
     */
    private fun ensureFrameTicker() {
        if (tickerScheduled) return
        if (carSurface?.isValid != true || !CarNavigationBridge.state.navigating) return
        tickerScheduled = true
        frameHandler.removeCallbacks(frameTicker)
        frameHandler.post(frameTicker)
    }

    private val invalidateRunnable = Runnable {
        pendingInvalidate = false
        lastInvalidateAt = SystemClock.uptimeMillis()
        invalidate()
    }

    /**
     * 车机主机对模板刷新有速率限制，超限会被主机判定为异常应用并断开连接。
     * 手机端每 300ms 推送一帧画面、每秒回调定位，都会触发 bridgeListener，
     * 因此把模板刷新限流到 [MIN_INVALIDATE_INTERVAL_MS]；
     * Surface 上的地图画面仍由 frameTicker 按 [FRAME_INTERVAL_MS] 独立重绘。
     */
    private fun scheduleInvalidate() {
        if (pendingInvalidate) return
        pendingInvalidate = true
        val delay = (lastInvalidateAt + MIN_INVALIDATE_INTERVAL_MS - SystemClock.uptimeMillis())
            .coerceIn(0L, MIN_INVALIDATE_INTERVAL_MS)
        frameHandler.postDelayed(invalidateRunnable, delay)
    }

    private fun invalidateNow() {
        frameHandler.removeCallbacks(invalidateRunnable)
        pendingInvalidate = false
        lastInvalidateAt = SystemClock.uptimeMillis()
        invalidate()
    }

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(container: SurfaceContainer) {
            carSurface = container.surface
            surfaceWidth = container.width
            surfaceHeight = container.height
            // Android Auto 主机通常是横屏 Surface；重新按车机尺寸布局百度 MiniMap，
            // 避免沿用手机竖屏导航截图导致画面被裁剪成大色块。
            miniMapView?.let { layoutMiniMapView(it) }
            ensureMiniMap()
            miniMapView?.let { layoutMiniMapView(it) }
            renderMap()
            frameHandler.removeCallbacks(frameTicker)
            tickerScheduled = false
            ensureFrameTicker()
        }

        override fun onSurfaceDestroyed(container: SurfaceContainer) {
            if (carSurface === container.surface) carSurface = null
            frameHandler.removeCallbacks(frameTicker)
            tickerScheduled = false
        }
    }

    private val bridgeListener: () -> Unit = {
        carContext.mainExecutor.execute {
            publishTrip()
            ensureMiniMap()
            renderMap()
            ensureFrameTicker()
            scheduleInvalidate()
        }
    }

    init {
        appManager.setSurfaceCallback(surfaceCallback)
        CarNavigationBridge.addListener(bridgeListener)
        navigationManager.setNavigationManagerCallback(carContext.mainExecutor,
            object : NavigationManagerCallback {
                override fun onStopNavigation() { CarNavigationBridge.stop() }
            })
        if (CarNavigationBridge.state.navigating) {
            runCatching { navigationManager.navigationStarted() }
            navigationAnnounced = true
        }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                frameHandler.removeCallbacks(frameTicker)
                frameHandler.removeCallbacks(invalidateRunnable)
                tickerScheduled = false
                pendingInvalidate = false
                appManager.setSurfaceCallback(null)
                carSurface = null
                if (miniMapCreated) runCatching {
                    miniMap.onPause()
                    miniMap.openBackgroundDrawNavi(false)
                    miniMap.onDestroy()
                }
                miniMapView = null
                miniMapCreated = false
                CarNavigationBridge.removeListener(bridgeListener)
                navigationManager.clearNavigationManagerCallback()
            }
        })
    }

    private fun ensureMiniMap() {
        if (miniMapCreated || !CarNavigationBridge.state.navigating) return
        runCatching {
            miniMapView = miniMap.onCreate(carContext)
            miniMapView?.let { layoutMiniMapView(it) }
            miniMap.touchAble(false)
            miniMap.setNaviMode(IBNMiniMapViewManager.NaviMode.NORMAL)
            miniMap.setMapElementShow(true)
            miniMap.setIconElementShow(true)
            miniMap.setTrafficEnabled(true)
            miniMap.openBackgroundDrawNavi(true)
            miniMap.onResume()
            miniMapCreated = true
            android.util.Log.i("NavCarBaidu", "Baidu background map initialized with offscreen view ${surfaceWidth}x${surfaceHeight}")
        }.onFailure {
            android.util.Log.e("NavCarBaidu", "Baidu background map init failed", it)
        }
    }

    private fun layoutMiniMapView(view: View) {
        val width = surfaceWidth.takeIf { it > 0 } ?: 1280
        val height = surfaceHeight.takeIf { it > 0 } ?: 720
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        view.measure(widthSpec, heightSpec)
        view.layout(0, 0, width, height)
        runCatching { miniMap.setFullViewMarginSize(0, 0, 0, 0) }
        runCatching { miniMap.fullView(true) }
    }

    override fun onGetTemplate(): Template {
        val state = CarNavigationBridge.state
        if (!state.navigating) {
            return MessageTemplate.Builder("请在手机端选择目的地并开始导航")
                .setTitle("灰猫地图")
                .setHeaderAction(Action.APP_ICON)
                .build()
        }
        // 非法/不完整的转向类型（例如环岛缺少出口编号）会让 Maneuver.Builder 抛异常，
        // 而 onGetTemplate 抛异常会直接导致车机端应用崩溃，这里做兜底。
        val maneuver = runCatching {
            Maneuver.Builder(
                state.maneuverType.takeIf { it != 0 } ?: Maneuver.TYPE_STRAIGHT
            ).build()
        }.getOrElse {
            android.util.Log.w("NavCarBaidu", "invalid maneuver type ${state.maneuverType}", it)
            Maneuver.Builder(Maneuver.TYPE_STRAIGHT).build()
        }
        val step = Step.Builder(state.instruction.ifBlank { "继续行驶" })
            .setRoad(state.roadName.ifBlank { state.instruction.ifBlank { "继续行驶" } })
            .setManeuver(maneuver).build()
        val routingDistance = state.distanceToTurnMeters.takeIf { it > 0 }
            ?: state.remainingDistanceMeters.coerceAtLeast(1)
        val routing = RoutingInfo.Builder()
            .setCurrentStep(step, displayDistance(routingDistance)).build()
        val estimate = TravelEstimate.Builder(
            displayDistance(state.remainingDistanceMeters),
            DateTimeWithZone.create(
                System.currentTimeMillis() + state.remainingTimeSeconds * 1000L,
                TimeZone.getDefault()
            )
        ).setRemainingTimeSeconds(state.remainingTimeSeconds.toLong()).build()
        val stop = Action.Builder().setTitle("结束导航").setOnClickListener {
            CarNavigationBridge.stop()
            runCatching { navigationManager.navigationEnded() }
            navigationAnnounced = false
            invalidateNow()
        }.build()
        return NavigationTemplate.Builder()
            .setNavigationInfo(routing)
            .setDestinationTravelEstimate(estimate)
            .setBackgroundColor(CarColor.SECONDARY)
            .setActionStrip(ActionStrip.Builder().addAction(stop).build())
            .build()
    }

    private fun renderMap() {
        val surface = carSurface ?: return
        if (!surface.isValid || surfaceWidth <= 0 || surfaceHeight <= 0) return
        val bitmap = if (miniMapCreated) runCatching { miniMap.getMapViewBitmap() }.getOrNull() else null
        var canvas: Canvas? = null
        try {
            canvas = surface.lockCanvas(null)
            canvas.drawColor(Color.rgb(28, 35, 42))
            val mapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            // 车机端优先绘制百度 MiniMap 生成的横向地图图层。
            // 手机端导航截图只作为兜底，避免竖屏截图在横屏车机上被 center-crop 后只剩大色块。
            val drewBaiduMap = if (bitmap != null && !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
                lastBaiduBitmapAt = System.currentTimeMillis()
                val src = centerCrop(bitmap.width, bitmap.height, surfaceWidth, surfaceHeight)
                canvas.drawBitmap(bitmap, src, Rect(0, 0, surfaceWidth, surfaceHeight), mapPaint)
                true
            } else false

            if (!drewBaiduMap) {
                val phoneFrameFresh = System.currentTimeMillis() - CarNavigationBridge.phoneFrameTimeMs <= 2_000L
                val drewPhoneFrame = if (phoneFrameFresh) {
                    CarNavigationBridge.drawPhoneFrame(canvas, Rect(0, 0, surfaceWidth, surfaceHeight), mapPaint)
                } else false
                if (!drewPhoneFrame) {
                    miniMapView?.let { layoutMiniMapView(it) }
                    drawFallbackNavigation(canvas)
                }
            }
            drawInstructionCard(canvas)
        } catch (e: Throwable) {
            android.util.Log.w("NavCarBaidu", "Baidu frame render failed", e)
        } finally {
            if (canvas != null) runCatching { surface.unlockCanvasAndPost(canvas) }
        }
    }

    private fun drawInstructionCard(canvas: Canvas) {
        val state = CarNavigationBridge.state
        val left = 28f
        val top = 24f
        val width = (surfaceWidth * 0.42f).coerceIn(360f, 620f)
        val height = 118f
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 8, 18, 28) }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 30f
            textAlign = Paint.Align.LEFT
            isFakeBoldText = true
        }
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(205, 220, 230)
            textSize = 23f
            textAlign = Paint.Align.LEFT
        }
        val r = android.graphics.RectF(left, top, left + width, top + height)
        canvas.drawRoundRect(r, 18f, 18f, bg)
        canvas.drawRoundRect(r, 18f, 18f, border)
        val distance = state.distanceToTurnMeters.takeIf { it > 0 }?.let { formatMeters(it) }
            ?: formatMeters(state.remainingDistanceMeters.coerceAtLeast(0))
        val instruction = state.instruction.ifBlank { "继续行驶" }
        canvas.drawText(distance, left + 24f, top + 42f, title)
        canvas.drawText(instruction.take(18), left + 24f, top + 82f, sub)
    }

    private fun formatMeters(meters: Int): String = if (meters >= 1000) {
        String.format(Locale.US, "%.1f 公里", meters / 1000.0)
    } else "${meters.coerceAtLeast(0)} 米"

    private fun drawFallbackNavigation(canvas: Canvas) {
        val state = CarNavigationBridge.state
        val w = surfaceWidth.toFloat()
        val h = surfaceHeight.toFloat()
        val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(46, 135, 255)
            strokeWidth = 18f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val shadowPaint = Paint(routePaint).apply {
            color = Color.argb(130, 0, 0, 0)
            strokeWidth = 28f
        }
        val carPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
            textAlign = Paint.Align.CENTER
        }
        val smallPaint = Paint(textPaint).apply { textSize = 22f; color = Color.rgb(190, 205, 215) }

        val path = android.graphics.Path().apply {
            moveTo(w * 0.50f, h * 0.78f)
            cubicTo(w * 0.50f, h * 0.62f, w * 0.56f, h * 0.54f, w * 0.60f, h * 0.42f)
            cubicTo(w * 0.66f, h * 0.27f, w * 0.56f, h * 0.17f, w * 0.50f, h * 0.10f)
        }
        canvas.drawPath(path, shadowPaint)
        canvas.drawPath(path, routePaint)

        canvas.save()
        canvas.translate(w * 0.50f, h * 0.78f)
        canvas.rotate(state.bearing.takeIf { it.isFinite() } ?: 0f)
        val car = android.graphics.Path().apply {
            moveTo(0f, -32f)
            lineTo(20f, 26f)
            lineTo(0f, 14f)
            lineTo(-20f, 26f)
            close()
        }
        canvas.drawPath(car, carPaint)
        canvas.restore()

        val status = when {
            state.inertialNavigation || !state.locationReliable -> "定位弱，车机惯性导航中"
            lastBaiduBitmapAt == 0L -> "百度导航画面同步中"
            else -> "百度导航画面恢复中"
        }
        canvas.drawText(status, w / 2f, h * 0.47f, textPaint)
        canvas.drawText(state.instruction.ifBlank { "继续行驶" }, w / 2f, h * 0.54f, smallPaint)
    }

    private fun centerCrop(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Rect {
        val srcRatio = srcW.toDouble() / srcH
        val dstRatio = dstW.toDouble() / dstH
        return if (srcRatio > dstRatio) {
            val width = (srcH * dstRatio).toInt().coerceAtLeast(1)
            val left = ((srcW - width) / 2).coerceAtLeast(0)
            Rect(left, 0, (left + width).coerceAtMost(srcW), srcH)
        } else {
            val height = (srcW / dstRatio).toInt().coerceAtLeast(1)
            val top = ((srcH - height) / 2).coerceAtLeast(0)
            Rect(0, top, srcW, (top + height).coerceAtMost(srcH))
        }
    }

    private fun publishTrip() {
        val navigating = CarNavigationBridge.state.navigating
        if (navigating == navigationAnnounced) return
        runCatching {
            if (navigating) navigationManager.navigationStarted()
            else navigationManager.navigationEnded()
        }
        navigationAnnounced = navigating
    }

    private fun displayDistance(meters: Int): Distance = if (meters >= 1000) {
        Distance.create(meters / 1000.0, Distance.UNIT_KILOMETERS_P1)
    } else Distance.create(meters.toDouble(), Distance.UNIT_METERS)

    private companion object {
        /** 车机地图画面重绘间隔。 */
        const val FRAME_INTERVAL_MS = 250L

        /** 模板刷新最小间隔，避免触发车机主机的模板速率限制。 */
        const val MIN_INVALIDATE_INTERVAL_MS = 1_000L
    }
}
