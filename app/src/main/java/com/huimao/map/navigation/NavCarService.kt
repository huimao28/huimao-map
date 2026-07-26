package com.huimao.map.navigation

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
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
import java.util.TimeZone

class NavCarService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    override fun onCreateSession(): Session = NavCarSession()
}

class NavCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = CarMainScreen(carContext)
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

    private val frameTicker = object : Runnable {
        override fun run() {
            if (carSurface?.isValid == true && CarNavigationBridge.state.navigating) {
                renderMap()
                frameHandler.postDelayed(this, 250L)
            }
        }
    }

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(container: SurfaceContainer) {
            carSurface = container.surface
            surfaceWidth = container.width
            surfaceHeight = container.height
            ensureMiniMap()
            renderMap()
            frameHandler.removeCallbacks(frameTicker)
            frameHandler.post(frameTicker)
        }

        override fun onSurfaceDestroyed(container: SurfaceContainer) {
            if (carSurface === container.surface) carSurface = null
            frameHandler.removeCallbacks(frameTicker)
        }
    }

    private val bridgeListener: () -> Unit = {
        carContext.mainExecutor.execute {
            publishTrip()
            ensureMiniMap()
            renderMap()
            invalidate()
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
                appManager.setSurfaceCallback(null)
                carSurface = null
                if (miniMapCreated) runCatching {
                    miniMap.onPause()
                    miniMap.openBackgroundDrawNavi(false)
                    miniMap.onDestroy()
                }
                miniMapCreated = false
                CarNavigationBridge.removeListener(bridgeListener)
                navigationManager.clearNavigationManagerCallback()
            }
        })
    }

    private fun ensureMiniMap() {
        if (miniMapCreated || !CarNavigationBridge.state.navigating) return
        runCatching {
            miniMap.onCreate(carContext)
            miniMap.touchAble(false)
            miniMap.setNaviMode(IBNMiniMapViewManager.NaviMode.NORMAL)
            miniMap.setMapElementShow(true)
            miniMap.setIconElementShow(true)
            miniMap.setTrafficEnabled(true)
            miniMap.openBackgroundDrawNavi(true)
            miniMap.onResume()
            miniMapCreated = true
        }.onFailure {
            android.util.Log.e("NavCarBaidu", "Baidu background map init failed", it)
        }
    }

    override fun onGetTemplate(): Template {
        val state = CarNavigationBridge.state
        if (!state.navigating) {
            return MessageTemplate.Builder("请在手机端选择目的地并开始导航")
                .setTitle("灰猫地图")
                .setHeaderAction(Action.APP_ICON)
                .build()
        }
        val step = Step.Builder(state.instruction)
            .setRoad(state.roadName.ifBlank { state.instruction })
            .setManeuver(Maneuver.Builder(
                state.maneuverType.takeIf { it != 0 } ?: Maneuver.TYPE_STRAIGHT
            ).build()).build()
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
            invalidate()
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
            if (bitmap != null && !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
                val src = centerCrop(bitmap.width, bitmap.height, surfaceWidth, surfaceHeight)
                canvas.drawBitmap(bitmap, src, Rect(0, 0, surfaceWidth, surfaceHeight),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            } else {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 30f
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText("正在加载百度导航画面…", surfaceWidth / 2f, surfaceHeight / 2f, paint)
            }
        } catch (e: Throwable) {
            android.util.Log.w("NavCarBaidu", "Baidu frame render failed", e)
        } finally {
            if (canvas != null) runCatching { surface.unlockCanvasAndPost(canvas) }
        }
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
}
