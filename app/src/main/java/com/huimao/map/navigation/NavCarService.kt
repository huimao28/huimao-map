package com.huimao.map.navigation

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
import java.util.TimeZone
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

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
    @Volatile private var carSurface: Surface? = null
    @Volatile private var surfaceWidth = 0
    @Volatile private var surfaceHeight = 0
    private var navigationAnnounced = false
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(70, 150, 255); strokeWidth = 14f
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(18, 52, 92); strokeWidth = 26f
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val vehiclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val tileCache = ConcurrentHashMap<String, android.graphics.Bitmap>()
    private val tileOrder = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val tilesInFlight = ConcurrentHashMap.newKeySet<String>()
    private val tileExecutor = Executors.newFixedThreadPool(4)
    @Volatile private var tileRenderPending = false
    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(container: SurfaceContainer) {
            carSurface = container.surface
            surfaceWidth = container.width
            surfaceHeight = container.height
            renderMap()
        }
        override fun onSurfaceDestroyed(container: SurfaceContainer) {
            if (carSurface === container.surface) carSurface = null
        }
    }
    private val bridgeListener: () -> Unit = {
        carContext.mainExecutor.execute {
            publishTrip()
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
                appManager.setSurfaceCallback(null)
                carSurface = null
                tileExecutor.shutdownNow()
                tilesInFlight.clear()
                // 不主动 recycle：绘制线程可能仍持有 Bitmap，交给 GC 安全回收。
                tileCache.clear()
                tileOrder.clear()
                CarNavigationBridge.removeListener(bridgeListener)
                navigationManager.clearNavigationManagerCallback()
            }
        })
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
            ).build())
            .build()
        val routingDistance = state.distanceToTurnMeters.takeIf { it > 0 }
            ?: state.remainingDistanceMeters.coerceAtLeast(1)
        val routing = RoutingInfo.Builder()
            .setCurrentStep(step, displayDistance(routingDistance))
            .build()
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
        val state = CarNavigationBridge.state
        val zoom = 15
        val centerBd = if (state.latitude != 0.0 && state.longitude != 0.0) {
            state.latitude to state.longitude
        } else state.routePoints.firstOrNull() ?: return
        val center = bd09ToWgs84(centerBd.first, centerBd.second)
        val centerPx = worldPixel(center.first, center.second, zoom)
        val vehicleScreenX = surfaceWidth * 0.50f
        val vehicleScreenY = surfaceHeight * 0.68f
        val originX = centerPx.first - vehicleScreenX
        val originY = centerPx.second - vehicleScreenY
        // 地图会围绕车辆旋转，普通屏幕矩形不足以覆盖旋转后的四角。
        // 按屏幕对角线取正方形，再额外预取 512px 缓冲圈，车辆移动时不露黑边。
        val prefetchRadius = kotlin.math.ceil(
            kotlin.math.hypot(surfaceWidth.toDouble(), surfaceHeight.toDouble()) / 2.0
        ).toInt() + 768
        requestVisibleTiles(
            centerPx.first - prefetchRadius,
            centerPx.second - prefetchRadius,
            zoom,
            prefetchRadius * 2,
            prefetchRadius * 2
        )

        var canvas: Canvas? = null
        try {
            canvas = surface.lockCanvas(null)
            canvas.drawColor(Color.rgb(28, 35, 42))
            val mapBearing = effectiveBearing(state)
            canvas.save()
            // 将地图与路线反向旋转，车辆航向始终朝屏幕上方。
            canvas.rotate(-mapBearing, vehicleScreenX, vehicleScreenY)
            drawTiles(canvas, originX, originY, zoom)

            val path = Path()
            var hasPath = false
            state.routePoints.forEach { bd ->
                val wgs = bd09ToWgs84(bd.first, bd.second)
                val px = worldPixel(wgs.first, wgs.second, zoom)
                val sx = (px.first - originX).toFloat()
                val sy = (px.second - originY).toFloat()
                if (!hasPath) { path.moveTo(sx, sy); hasPath = true } else path.lineTo(sx, sy)
            }
            if (hasPath) {
                canvas.drawPath(path, haloPaint)
                canvas.drawPath(path, routePaint)
            }
            canvas.restore()
            // 固定朝上的车辆箭头，不随地图旋转。
            val arrow = Path().apply {
                moveTo(vehicleScreenX, vehicleScreenY - 34f)
                lineTo(vehicleScreenX - 24f, vehicleScreenY + 25f)
                lineTo(vehicleScreenX, vehicleScreenY + 14f)
                lineTo(vehicleScreenX + 24f, vehicleScreenY + 25f)
                close()
            }
            canvas.drawPath(arrow, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; style = Paint.Style.FILL
                setShadowLayer(8f, 0f, 3f, Color.BLACK)
            })
            canvas.drawCircle(vehicleScreenX, vehicleScreenY, 7f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 110, 245) })
            drawGuidanceCard(canvas, state)
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = 20f; alpha = 190
                canvas.drawText("© OpenStreetMap contributors", 18f, surfaceHeight - 18f, this)
            }
        } catch (_: Throwable) {
        } finally {
            if (canvas != null) runCatching { surface.unlockCanvasAndPost(canvas) }
        }
    }

    private fun effectiveBearing(state: CarNavigationState): Float {
        if (state.bearing.isFinite() && state.bearing >= 1f) return state.bearing % 360f
        if (state.routePoints.size < 2) return 0f
        val nearest = state.routePoints.indices.minByOrNull { i ->
            val p = state.routePoints[i]
            val dx = p.second - state.longitude
            val dy = p.first - state.latitude
            dx * dx + dy * dy
        } ?: 0
        val next = (nearest + 3).coerceAtMost(state.routePoints.lastIndex)
        if (next == nearest) return 0f
        val a = state.routePoints[nearest]
        val b = state.routePoints[next]
        val y = kotlin.math.sin(Math.toRadians(b.second - a.second)) * kotlin.math.cos(Math.toRadians(b.first))
        val x = kotlin.math.cos(Math.toRadians(a.first)) * kotlin.math.sin(Math.toRadians(b.first)) -
            kotlin.math.sin(Math.toRadians(a.first)) * kotlin.math.cos(Math.toRadians(b.first)) *
            kotlin.math.cos(Math.toRadians(b.second - a.second))
        return ((Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0).toFloat()
    }

    private fun drawGuidanceCard(canvas: Canvas, state: CarNavigationState) {
        val left = 24f
        val top = 24f
        val width = (surfaceWidth * 0.56f).coerceAtMost(680f)
        val height = 184f
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 20, 34, 51) }
        canvas.drawRoundRect(left, top, left + width, top + height, 24f, 24f, background)

        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 13f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val icon = Path()
        when (state.maneuverType) {
            Maneuver.TYPE_TURN_NORMAL_LEFT, Maneuver.TYPE_TURN_SHARP_LEFT,
            Maneuver.TYPE_TURN_SLIGHT_LEFT, Maneuver.TYPE_KEEP_LEFT,
            Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT -> {
                icon.moveTo(left + 105, top + 112); icon.lineTo(left + 105, top + 66)
                icon.quadTo(left + 105, top + 47, left + 82, top + 47)
                icon.lineTo(left + 52, top + 47); icon.moveTo(left + 52, top + 47)
                icon.lineTo(left + 73, top + 28); icon.moveTo(left + 52, top + 47); icon.lineTo(left + 73, top + 67)
            }
            Maneuver.TYPE_TURN_NORMAL_RIGHT, Maneuver.TYPE_TURN_SHARP_RIGHT,
            Maneuver.TYPE_TURN_SLIGHT_RIGHT, Maneuver.TYPE_KEEP_RIGHT,
            Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT -> {
                icon.moveTo(left + 58, top + 112); icon.lineTo(left + 58, top + 66)
                icon.quadTo(left + 58, top + 47, left + 82, top + 47)
                icon.lineTo(left + 112, top + 47); icon.moveTo(left + 112, top + 47)
                icon.lineTo(left + 91, top + 28); icon.moveTo(left + 112, top + 47); icon.lineTo(left + 91, top + 67)
            }
            Maneuver.TYPE_U_TURN_LEFT, Maneuver.TYPE_U_TURN_RIGHT -> {
                icon.moveTo(left + 98, top + 112); icon.lineTo(left + 98, top + 60)
                icon.cubicTo(left + 98, top + 24, left + 48, top + 24, left + 48, top + 60)
                icon.lineTo(left + 48, top + 78); icon.moveTo(left + 48, top + 78)
                icon.lineTo(left + 30, top + 60); icon.moveTo(left + 48, top + 78); icon.lineTo(left + 66, top + 60)
            }
            else -> {
                icon.moveTo(left + 80, top + 115); icon.lineTo(left + 80, top + 34)
                icon.moveTo(left + 80, top + 34); icon.lineTo(left + 58, top + 58)
                icon.moveTo(left + 80, top + 34); icon.lineTo(left + 102, top + 58)
            }
        }
        canvas.drawPath(icon, iconPaint)
        val distance = when {
            state.distanceToTurnMeters >= 1000 -> String.format("%.1f 公里", state.distanceToTurnMeters / 1000.0)
            state.distanceToTurnMeters > 0 -> "${state.distanceToTurnMeters} 米"
            else -> "前方"
        }
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 40f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            canvas.drawText(distance, left + 145f, top + 58f, this)
            textSize = 27f; typeface = android.graphics.Typeface.DEFAULT
            // 指引文字完全来自百度 GuidePanelMessage；仅做两行排版，不改写内容。
            val cue = state.instruction.ifBlank { state.roadName.ifBlank { "继续行驶" } }
            val lineLength = 19
            val firstLine = cue.take(lineLength)
            val secondLine = cue.drop(lineLength).take(lineLength)
            canvas.drawText(firstLine, left + 145f, top + 112f, this)
            if (secondLine.isNotBlank()) {
                textSize = 24f
                canvas.drawText(secondLine, left + 145f, top + 151f, this)
            }
        }
    }

    private fun worldPixel(lat: Double, lng: Double, zoom: Int): Pair<Double, Double> {
        val n = 256.0 * 2.0.pow(zoom)
        val clippedLat = lat.coerceIn(-85.05112878, 85.05112878)
        val x = (lng + 180.0) / 360.0 * n
        val rad = Math.toRadians(clippedLat)
        val y = (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * n
        return x to y
    }

    private fun drawTiles(canvas: Canvas, originX: Double, originY: Double, zoom: Int) {
        val minX = kotlin.math.floor(originX / 256.0).toInt()
        val maxX = kotlin.math.floor((originX + surfaceWidth) / 256.0).toInt()
        val minY = kotlin.math.floor(originY / 256.0).toInt()
        val maxY = kotlin.math.floor((originY + surfaceHeight) / 256.0).toInt()
        val count = 1 shl zoom
        for (tx in minX..maxX) for (ty in minY..maxY) {
            if (ty !in 0 until count) continue
            val wrappedX = ((tx % count) + count) % count
            val bitmap = tileCache["$zoom/$wrappedX/$ty"] ?: continue
            val left = (tx * 256.0 - originX).toFloat()
            val top = (ty * 256.0 - originY).toFloat()
            canvas.drawBitmap(bitmap, left, top, null)
        }
    }

    private fun requestVisibleTiles(
        originX: Double,
        originY: Double,
        zoom: Int,
        viewportWidth: Int = surfaceWidth,
        viewportHeight: Int = surfaceHeight
    ) {
        val minX = kotlin.math.floor(originX / 256.0).toInt()
        val maxX = kotlin.math.floor((originX + viewportWidth) / 256.0).toInt()
        val minY = kotlin.math.floor(originY / 256.0).toInt()
        val maxY = kotlin.math.floor((originY + viewportHeight) / 256.0).toInt()
        val count = 1 shl zoom
        val missing = ArrayList<Triple<Int, Int, String>>()
        for (tx in minX..maxX) for (ty in minY..maxY) {
            if (ty !in 0 until count) continue
            val x = ((tx % count) + count) % count
            val key = "$zoom/$x/$ty"
            if (!tileCache.containsKey(key) && tilesInFlight.add(key)) {
                missing.add(Triple(x, ty, key))
            }
        }
        val centerTileX = kotlin.math.floor((originX + viewportWidth / 2.0) / 256.0).toInt()
        val centerTileY = kotlin.math.floor((originY + viewportHeight / 2.0) / 256.0).toInt()
        // 中心优先：当前可见区域先完成，外围预取随后，避免缓冲瓦片抢占下载队列。
        missing.sortBy { (x, y, _) ->
            val dx = kotlin.math.abs(x - ((centerTileX % count + count) % count))
            val wrappedDx = minOf(dx, count - dx)
            wrappedDx * wrappedDx + (y - centerTileY) * (y - centerTileY)
        }
        // 提交整个预取区域；tilesInFlight 会去重，四线程按中心距离依次加载。
        missing.forEach { (x, y, key) ->
            runCatching {
                tileExecutor.execute {
                    try {
                        val connection = URL("https://tile.openstreetmap.org/$zoom/$x/$y.png")
                            .openConnection() as HttpURLConnection
                        connection.connectTimeout = 4000
                        connection.readTimeout = 5000
                        connection.setRequestProperty("User-Agent", "BaiduNaviAuto/8.7 AndroidAuto")
                        connection.inputStream.use { input ->
                            BitmapFactory.decodeStream(input)?.let { bitmap ->
                                tileCache[key] = bitmap
                                tileOrder.add(key)
                                while (tileCache.size > 320) {
                                    val oldKey = tileOrder.poll() ?: break
                                    // 只移出缓存，不主动 recycle，避免与 Surface 绘制竞态。
                                    tileCache.remove(oldKey)
                                }
                            }
                        }
                        connection.disconnect()
                    } finally {
                        tilesInFlight.remove(key)
                        scheduleTileRender()
                    }
                }
            }.onFailure { tilesInFlight.remove(key) }
        }
    }

    private fun scheduleTileRender() {
        if (tileRenderPending) return
        tileRenderPending = true
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            tileRenderPending = false
            if (carSurface?.isValid == true) renderMap()
        }, 180L)
    }

    /** 百度 BD09LL 转 WGS-84，供标准道路瓦片和路线对齐。 */
    private fun bd09ToWgs84(bdLat: Double, bdLng: Double): Pair<Double, Double> {
        val x = bdLng - 0.0065
        val y = bdLat - 0.006
        val z = kotlin.math.sqrt(x * x + y * y) - 0.00002 * kotlin.math.sin(y * PI * 3000.0 / 180.0)
        val theta = kotlin.math.atan2(y, x) - 0.000003 * kotlin.math.cos(x * PI * 3000.0 / 180.0)
        val gcjLat = z * kotlin.math.sin(theta)
        val gcjLng = z * kotlin.math.cos(theta)
        val a = 6378245.0
        val ee = 0.00669342162296594323
        fun transformLat(dx: Double, dy: Double): Double {
            var r = -100.0 + 2.0 * dx + 3.0 * dy + 0.2 * dy * dy + 0.1 * dx * dy + 0.2 * kotlin.math.sqrt(kotlin.math.abs(dx))
            r += (20.0 * kotlin.math.sin(6.0 * dx * PI) + 20.0 * kotlin.math.sin(2.0 * dx * PI)) * 2.0 / 3.0
            r += (20.0 * kotlin.math.sin(dy * PI) + 40.0 * kotlin.math.sin(dy / 3.0 * PI)) * 2.0 / 3.0
            r += (160.0 * kotlin.math.sin(dy / 12.0 * PI) + 320.0 * kotlin.math.sin(dy / 30.0 * PI)) * 2.0 / 3.0
            return r
        }
        fun transformLng(dx: Double, dy: Double): Double {
            var r = 300.0 + dx + 2.0 * dy + 0.1 * dx * dx + 0.1 * dx * dy + 0.1 * kotlin.math.sqrt(kotlin.math.abs(dx))
            r += (20.0 * kotlin.math.sin(6.0 * dx * PI) + 20.0 * kotlin.math.sin(2.0 * dx * PI)) * 2.0 / 3.0
            r += (20.0 * kotlin.math.sin(dx * PI) + 40.0 * kotlin.math.sin(dx / 3.0 * PI)) * 2.0 / 3.0
            r += (150.0 * kotlin.math.sin(dx / 12.0 * PI) + 300.0 * kotlin.math.sin(dx / 30.0 * PI)) * 2.0 / 3.0
            return r
        }
        val radLat = gcjLat / 180.0 * PI
        var magic = kotlin.math.sin(radLat)
        magic = 1 - ee * magic * magic
        val sqrtMagic = kotlin.math.sqrt(magic)
        val dLat = transformLat(gcjLng - 105.0, gcjLat - 35.0) * 180.0 / ((a * (1 - ee)) / (magic * sqrtMagic) * PI)
        val dLng = transformLng(gcjLng - 105.0, gcjLat - 35.0) * 180.0 / (a / sqrtMagic * kotlin.math.cos(radLat) * PI)
        return (gcjLat - dLat) to (gcjLng - dLng)
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
