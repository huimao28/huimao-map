package com.huimao.map.navigation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import java.util.concurrent.CopyOnWriteArraySet

data class CarNavigationState(
    val navigating: Boolean = false,
    val roadName: String = "",
    val instruction: String = "正在获取导航指引",
    val maneuverType: Int = 0,
    val distanceToTurnMeters: Int = 0,
    val remainingDistanceMeters: Int = 0,
    val remainingTimeSeconds: Int = 0,
    val speedKmh: Int = 0,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val bearing: Float = 0f,
    val accuracyMeters: Float = 0f,
    val lastLocationTimeMs: Long = 0L,
    val locationReliable: Boolean = true,
    val inertialNavigation: Boolean = false,
    val routePoints: List<Pair<Double, Double>> = emptyList(),
    val destinationName: String = "目的地",
    /** 当前路口的车道建议（百度 onLaneInfoUpdate 解码结果），无车道信息时为空。 */
    val lanes: List<CarLane> = emptyList(),
    /** 路口放大图是否正在展示，以及距该路口的剩余距离（米）。 */
    val junctionActive: Boolean = false,
    val junctionRemainMeters: Int = 0,
    /** 高速信息一行文案（出口/收费站/服务区），非高速场景为空。 */
    val highwayInfoText: String = ""
)

/** 一条车道的呈现模型：direction 为百度车道图标名解码出的方向键，recommended 为是否推荐通行。 */
data class CarLane(
    val direction: String,
    val recommended: Boolean
)

/** 手机端百度导航与 Android Auto CarAppService 之间的进程内状态桥。 */
object CarNavigationBridge {
    @Volatile var state: CarNavigationState = CarNavigationState()
        private set

    @Volatile private var appContext: android.content.Context? = null
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun initialize(context: android.content.Context) {
        appContext = context.applicationContext
        WearNavigationSync.initialize(context)
        publish()
    }

    private fun publish() {
        val context = appContext ?: return
        WearNavigationSync.publish(context, state)
            .addOnFailureListener { /* 手表未连接时静默处理，连接后下一次状态更新会重试 */ }
    }
    @Volatile private var phoneFrame: Bitmap? = null
    @Volatile var phoneFrameTimeMs: Long = 0L
        private set

    @Synchronized
    fun updatePhoneFrame(source: Bitmap) {
        val copy = runCatching { source.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return
        val old = phoneFrame
        phoneFrame = copy
        phoneFrameTimeMs = System.currentTimeMillis()
        if (old != null && !old.isRecycled) runCatching { old.recycle() }
        listeners.forEach { runCatching { it() } }
    }

    @Synchronized
    fun drawPhoneFrame(canvas: Canvas, dest: Rect, paint: Paint): Boolean {
        val frame = phoneFrame ?: return false
        if (frame.isRecycled || frame.width <= 0 || frame.height <= 0) return false
        return runCatching {
            canvas.drawBitmap(frame, null, dest, paint)
            true
        }.getOrDefault(false)
    }

    @Synchronized
    fun clearPhoneFrame() {
        val old = phoneFrame
        phoneFrame = null
        phoneFrameTimeMs = 0L
        if (old != null && !old.isRecycled) runCatching { old.recycle() }
    }

    fun update(block: (CarNavigationState) -> CarNavigationState) {
        state = block(state)
        publish()
        listeners.forEach { runCatching { it() } }
    }

    fun start(destinationName: String) = update { previous ->
        previous.copy(
            navigating = true,
            destinationName = destinationName.ifBlank { "目的地" },
            instruction = "正在获取导航指引",
            maneuverType = 0,
            distanceToTurnMeters = 0
        )
    }

    fun stop() = update {
        clearPhoneFrame()
        clearCarMapFrame()
        clearJunctionFrame()
        updateLaneIcons(emptyList())
        CarNavigationState()
    }

    fun setRoutePoints(points: List<Pair<Double, Double>>) = update { it.copy(routePoints = points) }

    // ===== 车机地图截帧（百度原生 MapView 小样验证用，与 phoneFrame 同模式） =====
    @Volatile private var carMapFrame: Bitmap? = null
    @Volatile var carMapFrameTimeMs: Long = 0L
        private set

    @Synchronized
    fun updateCarMapFrame(source: Bitmap) {
        val copy = runCatching { source.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return
        val old = carMapFrame
        carMapFrame = copy
        carMapFrameTimeMs = System.currentTimeMillis()
        if (old != null && !old.isRecycled) runCatching { old.recycle() }
        listeners.forEach { runCatching { it() } }
    }

    fun carMapFrameSnapshot(): Bitmap? = carMapFrame?.takeUnless { it.isRecycled }

    @Synchronized
    fun clearCarMapFrame() {
        val old = carMapFrame
        carMapFrame = null
        carMapFrameTimeMs = 0L
        if (old != null && !old.isRecycled) runCatching { old.recycle() }
    }

    // ===== 车机地图小样诊断（临时：车机画面回报各环节状态，定位截帧断点） =====
    @Volatile var carMapDiagCreated: Boolean = false
    @Volatile var carMapDiagCreateError: String = ""
    @Volatile var carMapDiagLoaded: Boolean = false
    @Volatile var carMapDiagSnapshotCalls: Int = 0
    @Volatile var carMapDiagSnapshotNulls: Int = 0
    @Volatile var carMapDiagLastSize: String = "-"
    @Volatile var carMapDiagLocFeeds: Int = 0

    fun resetCarMapDiag() {
        carMapDiagCreated = false
        carMapDiagCreateError = ""
        carMapDiagLoaded = false
        carMapDiagSnapshotCalls = 0
        carMapDiagSnapshotNulls = 0
        carMapDiagLastSize = "-"
        carMapDiagLocFeeds = 0
    }

    // 车机连接状态（NavCarService 的车机界面创建/销毁时维护），
    // 手机端据此在导航时显示「正在驾车」提示页（上架合规）。
    @Volatile var carConnected: Boolean = false
        private set

    fun setCarConnected(connected: Boolean) {
        if (carConnected == connected) return
        carConnected = connected
        listeners.forEach { runCatching { it() } }
    }

    // 车机诊断行开关（由设置页 DataStore 同步，NavCarService 订阅维护）。
    @Volatile var carDiagEnabled: Boolean = true

    // ===== 车道图标 / 路口放大图 / 转向图标（位图不进 State，单独保存，与 phoneFrame 同模式） =====
    @Volatile private var laneIcons: List<Bitmap> = emptyList()

    @Synchronized
    fun updateLaneIcons(icons: List<Bitmap?>) {
        val old = laneIcons
        laneIcons = icons.mapNotNull { bmp ->
            bmp?.takeUnless { it.isRecycled }?.let {
                runCatching { it.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
            }
        }
        old.forEach { if (!it.isRecycled) runCatching { it.recycle() } }
    }

    fun laneIconsSnapshot(): List<Bitmap> = laneIcons

    @Volatile private var junctionFrame: Bitmap? = null
    @Volatile var junctionFrameTimeMs: Long = 0L
        private set

    @Synchronized
    fun updateJunctionFrame(source: Bitmap) {
        val copy = runCatching { source.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return
        val old = junctionFrame
        junctionFrame = copy
        junctionFrameTimeMs = System.currentTimeMillis()
        if (old != null && !old.isRecycled) runCatching { old.recycle() }
        listeners.forEach { runCatching { it() } }
    }

    @Synchronized
    fun drawJunctionFrame(canvas: Canvas, dest: Rect, paint: Paint): Boolean {
        val frame = junctionFrame ?: return false
        if (frame.isRecycled || frame.width <= 0 || frame.height <= 0) return false
        return runCatching {
            canvas.drawBitmap(frame, null, dest, paint)
            true
        }.getOrDefault(false)
    }

    /** 路口放大图的宽高比，未知时按 4:3 兜底。 */
    fun junctionFrameAspect(): Float {
        val frame = junctionFrame ?: return 4f / 3f
        if (frame.height <= 0) return 4f / 3f
        return frame.width.toFloat() / frame.height.toFloat()
    }

    @Synchronized
    fun clearJunctionFrame() {
        val old = junctionFrame
        junctionFrame = null
        junctionFrameTimeMs = 0L
        if (old != null && !old.isRecycled) runCatching { old.recycle() }
    }

    @Volatile private var turnIcon: Bitmap? = null

    @Synchronized
    fun updateTurnIcon(source: Bitmap) {
        val copy = runCatching { source.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return
        val old = turnIcon
        turnIcon = copy
        if (old != null && !old.isRecycled) runCatching { old.recycle() }
    }

    fun turnIconSnapshot(): Bitmap? = turnIcon?.takeUnless { it.isRecycled }

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }
    fun hasListeners(): Boolean = listeners.isNotEmpty()
}
