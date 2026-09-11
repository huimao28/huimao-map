package com.huimao.map.navigation

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
    val destinationName: String = "目的地"
)

/** 手机端百度导航与 Android Auto CarAppService 之间的进程内状态桥。 */
object CarNavigationBridge {
    @Volatile var state: CarNavigationState = CarNavigationState()
        private set

    @Volatile private var announcementSequence: Long = 0L
    @Volatile private var announcementText: String = ""
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
    fun announcement(): Pair<Long, String> = announcementSequence to announcementText

    fun announce(text: String) {
        val value = text.trim()
        if (value.isBlank()) return
        announcementText = value
        announcementSequence += 1L
        publish()
        listeners.forEach { runCatching { it() } }
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

    fun stop() = update { CarNavigationState() }

    fun setRoutePoints(points: List<Pair<Double, Double>>) = update { it.copy(routePoints = points) }

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }
    fun hasListeners(): Boolean = listeners.isNotEmpty()
}
