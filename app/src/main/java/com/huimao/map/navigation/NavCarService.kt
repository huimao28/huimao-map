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
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

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
    private val tileExecutor = Executors.newFixedThreadPool(3)
    @Volatile private var tileRenderPending = false
    private val baiduTileUdt = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
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
        val zoom = 20
        val now = System.currentTimeMillis()
        val centerBd = navigationCenter(state, now) ?: state.routePoints.firstOrNull() ?: return
        val centerPx = baiduWorldPixel(centerBd.first, centerBd.second, zoom)
        val routeShift = routeAlignmentShift(state.routePoints, state, zoom)
        val vehicleScreenX = surfaceWidth * 0.50f
        val vehicleScreenY = surfaceHeight * 0.68f
        val originX = centerPx.first - vehicleScreenX
        val originY = centerPx.second - vehicleScreenY
        // 北向上模式不需要旋转缓冲区，只预取屏幕四周一圈瓦片供车辆移动。
        val tileMargin = 256
        requestVisibleTiles(
            originX - tileMargin,
            originY - tileMargin,
            zoom,
            surfaceWidth + tileMargin * 2,
            surfaceHeight + tileMargin * 2
        )

        var canvas: Canvas? = null
        try {
            canvas = surface.lockCanvas(null)
            canvas.drawColor(Color.rgb(28, 35, 42))
            // 采用北向上地图，避免旋转矩形四角超出瓦片绘制区域而出现黑块。
            drawTiles(canvas, originX, originY, zoom)

            val path = Path()
            var hasPath = false
            state.routePoints.forEach { bd ->
                val px = baiduWorldPixel(bd.first, bd.second, zoom)
                val sx = (px.first - originX + routeShift.first).toFloat()
                val sy = (px.second - originY + routeShift.second).toFloat()
                if (!hasPath) { path.moveTo(sx, sy); hasPath = true } else path.lineTo(sx, sy)
            }
            if (hasPath) {
                canvas.drawPath(path, haloPaint)
                canvas.drawPath(path, routePaint)
            }
            // 北向上地图中，车辆箭头按实际航向旋转。
            canvas.save()
            canvas.rotate(effectiveBearing(state), vehicleScreenX, vehicleScreenY)
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
            canvas.restore()
            drawGuidanceCard(canvas, state)
            drawLocationStatus(canvas, state, now)
            drawScaleBar(canvas, centerBd.first, zoom)
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = 20f; alpha = 190
                canvas.drawText("© Baidu Maps", 18f, surfaceHeight - 18f, this)
            }
        } catch (_: Throwable) {
        } finally {
            if (canvas != null) runCatching { surface.unlockCanvasAndPost(canvas) }
        }
    }

    private fun navigationCenter(state: CarNavigationState, now: Long): Pair<Double, Double>? {
        val lat = state.latitude
        val lng = state.longitude
        if (lat == 0.0 || lng == 0.0) return null
        if (state.locationReliable && !state.inertialNavigation) return lat to lng
        val ageSeconds = ((now - state.lastLocationTimeMs).coerceAtLeast(0L) / 1000.0)
            .coerceAtMost(12.0)
        val speedMps = (state.speedKmh.coerceAtLeast(0) / 3.6).coerceAtMost(33.0)
        if (ageSeconds <= 0.5 || speedMps <= 0.2) return lat to lng
        val bearingRad = Math.toRadians(effectiveBearing(state).toDouble())
        val distance = speedMps * ageSeconds
        val earth = 6378137.0
        val dLat = distance * cos(bearingRad) / earth
        val dLng = distance * sin(bearingRad) / (earth * cos(Math.toRadians(lat)).coerceAtLeast(0.2))
        return (lat + Math.toDegrees(dLat)) to (lng + Math.toDegrees(dLng))
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

    private fun routeAlignmentShift(
        route: List<Pair<Double, Double>>,
        state: CarNavigationState,
        zoom: Int
    ): Pair<Double, Double> {
        if (route.isEmpty() || state.latitude == 0.0 || state.longitude == 0.0) return 0.0 to 0.0
        val locationPx = baiduWorldPixel(state.latitude, state.longitude, zoom)
        val nearest = route.minByOrNull { point ->
            val px = baiduWorldPixel(point.first, point.second, zoom)
            val dx = px.first - locationPx.first
            val dy = px.second - locationPx.second
            dx * dx + dy * dy
        } ?: return 0.0 to 0.0
        val nearestPx = baiduWorldPixel(nearest.first, nearest.second, zoom)
        val dx = locationPx.first - nearestPx.first
        val dy = locationPx.second - nearestPx.second
        // 仅修正显示层整体平移，不修改原始路线；超过 300px 说明路线/定位
        // 可能不是同一条路线，保留原始显示避免跳到错误道路。
        return if (kotlin.math.hypot(dx, dy) <= 300.0) dx to dy else 0.0 to 0.0
    }

    private fun drawScaleBar(canvas: Canvas, latitude: Double, zoom: Int) {
        // 百度瓦片不是标准 WebMercator 缩放定义。直接在百度墨卡托中取当前位置和
        // 向东 50 米的点，转换成同级世界像素，得到真实的 50 米屏幕长度。
        val earth = 6378137.0
        val deltaLng = Math.toDegrees(50.0 / (earth * cos(Math.toRadians(latitude)).coerceAtLeast(0.2)))
        val centerLng = CarNavigationBridge.state.longitude.takeIf { it != 0.0 } ?: return
        val start = baiduWorldPixel(latitude, centerLng, zoom)
        val end = baiduWorldPixel(latitude, centerLng + deltaLng, zoom)
        val width = kotlin.math.abs(end.first - start.first).toFloat()
            .coerceIn(120f, surfaceWidth * 0.55f)
        val left = 28f
        val bottom = surfaceHeight - 34f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 4f
            style = Paint.Style.STROKE
            setShadowLayer(3f, 0f, 1f, Color.BLACK)
        }
        canvas.drawLine(left, bottom, left + width, bottom, paint)
        canvas.drawLine(left, bottom - 8f, left, bottom + 8f, paint)
        canvas.drawLine(left + width, bottom - 8f, left + width, bottom + 8f, paint)
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 22f
            style = Paint.Style.FILL
            setShadowLayer(3f, 0f, 1f, Color.BLACK)
            canvas.drawText("50 m", left, bottom - 13f, this)
        }
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

    private fun drawLocationStatus(canvas: Canvas, state: CarNavigationState, now: Long) {
        val ageMs = now - state.lastLocationTimeMs
        val show = state.inertialNavigation || !state.locationReliable ||
            (state.lastLocationTimeMs > 0L && ageMs > 3000L) || state.accuracyMeters >= 80f
        if (!show) return
        val text = when {
            state.inertialNavigation || ageMs > 5000L -> "定位弱，车机惯性导航中"
            state.accuracyMeters >= 80f -> "定位精度约 ${state.accuracyMeters.toInt()} 米"
            else -> "定位信号弱"
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(218, 45, 52, 61); textSize = 25f
        }
        val paddingX = 22f
        val textWidth = paint.measureText(text)
        val right = surfaceWidth - 24f
        val top = 26f
        val left = right - textWidth - paddingX * 2
        canvas.drawRoundRect(left, top, right, top + 56f, 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 18, 28, 40)
        })
        paint.color = Color.WHITE
        canvas.drawText(text, left + paddingX, top + 37f, paint)
    }

    // Android Auto 使用百度 Web 瓦片坐标，不再使用标准 WebMercator。

    private fun baiduWorldPixel(lat: Double, lng: Double, zoom: Int): Pair<Double, Double> {
        val mercator = bd09ToBaiduMercator(lat, lng)
        val scale = 2.0.pow(18 - zoom)
        // 百度瓦片 x/y 以墨卡托原点为 (0,0)，Y 轴向北为正；
        // Canvas 世界像素则以左上为原点、Y 轴向南为正。
        return (mercator.first / scale) to (-mercator.second / scale)
    }

    private fun bd09ToBaiduMercator(lat: Double, lng: Double): Pair<Double, Double> {
        val x = lng.coerceIn(-180.0, 180.0)
        val y = lat.coerceIn(-74.0, 74.0)
        val bands = doubleArrayOf(75.0, 60.0, 45.0, 30.0, 15.0, 0.0)
        val coef = arrayOf(
            doubleArrayOf(-0.0015702102444, 111320.7020616939, 1704480524535203.0, -10338987376042340.0, 26112667856603880.0, -35149669176653700.0, 26595700718403920.0, -10725012454188240.0, 1800819912950474.0, 82.5),
            doubleArrayOf(0.0008277824516172526, 111320.7020463578, 647795574.6671607, -4082003173.641316, 10774905663.51142, -15171875531.51559, 12053065338.62167, -5124939663.577472, 913311935.9512032, 67.5),
            doubleArrayOf(0.00337398766765, 111320.7020202162, 4481351.045890365, -23393751.19931662, 79682215.47186455, -115964993.2797253, 97236711.15602145, -43661946.33752821, 8477230.501135234, 52.5),
            doubleArrayOf(0.00220636496208, 111320.7020209128, 51751.86112841131, 3796837.749470245, 992013.7397791013, -1221952.21711287, 1340652.697009075, -620943.6990984312, 144416.9293806241, 37.5),
            doubleArrayOf(-0.0003441963504368392, 111320.7020576856, 278.2353980772752, 2485758.690035394, 6070.750963243378, 54821.18345352118, 9540.606633304236, -2710.55326746645, 1405.483844121726, 22.5),
            doubleArrayOf(-0.0003218135878613132, 111320.7020701615, 0.00369383431289, 823725.6402795718, 0.46104986909093, 2351.343141331292, 1.58060784298199, 8.77738589078284, 0.37238884252424, 7.45)
        )
        var c = coef.last()
        for (i in bands.indices) if (kotlin.math.abs(y) >= bands[i]) { c = coef[i]; break }
        val yy = kotlin.math.abs(y) / c[9]
        val mx = c[0] + c[1] * kotlin.math.abs(x)
        var my = c[2]
        var factor = yy
        for (i in 3..8) { my += c[i] * factor; factor *= yy }
        return (if (x < 0) -mx else mx) to (if (y < 0) -my else my)
    }

    private fun drawTiles(canvas: Canvas, originX: Double, originY: Double, zoom: Int) {
        val minX = kotlin.math.floor(originX / 256.0).toInt()
        val maxX = kotlin.math.floor((originX + surfaceWidth) / 256.0).toInt()
        val minY = kotlin.math.floor(originY / 256.0).toInt()
        val maxY = kotlin.math.floor((originY + surfaceHeight) / 256.0).toInt()
        for (tx in minX..maxX) for (ty in minY..maxY) {
            val bitmap = tileCache["$zoom/$tx/$ty"] ?: continue
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
        val missing = ArrayList<Triple<Int, Int, String>>()
        for (tx in minX..maxX) for (ty in minY..maxY) {
            val key = "$zoom/$tx/$ty"
            if (!tileCache.containsKey(key) && tilesInFlight.add(key)) {
                missing.add(Triple(tx, ty, key))
            }
        }
        val centerTileX = kotlin.math.floor((originX + viewportWidth / 2.0) / 256.0).toInt()
        val centerTileY = kotlin.math.floor((originY + viewportHeight / 2.0) / 256.0).toInt()
        // 中心优先：当前可见区域先完成，外围预取随后，避免缓冲瓦片抢占下载队列。
        missing.sortBy { (x, y, _) ->
            val dx = x - centerTileX
            dx * dx + (y - centerTileY) * (y - centerTileY)
        }
        // 提交整个预取区域；tilesInFlight 会去重，四线程按中心距离依次加载。
        missing.forEach { (x, y, key) ->
            runCatching {
                tileExecutor.execute {
                    try {
                        // Canvas 的 ty 向南递增；百度接口 y 向北递增。
                        val tileY = -y
                        val shard = ((x + y) % 4 + 4) % 4
                        val connection = URL("https://maponline$shard.bdimg.com/tile/?qt=vtile&x=$x&y=$tileY&z=$zoom&styles=pl&scaler=1&udt=$baiduTileUdt")
                            .openConnection() as HttpURLConnection
                        connection.connectTimeout = 4000
                        connection.readTimeout = 5000
                        connection.setRequestProperty("User-Agent", "BaiduNaviAuto/8.7 AndroidAuto")
                        connection.inputStream.use { input ->
                            BitmapFactory.decodeStream(input)?.let { bitmap ->
                                tileCache[key] = bitmap
                                tileOrder.add(key)
                                while (tileCache.size > 160) {
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

    // 路线、位置和底图都保持百度 BD09LL / 百度墨卡托，避免跨坐标系图层不全和路线偏移。
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
