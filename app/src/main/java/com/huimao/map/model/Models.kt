package com.huimao.map.model

data class LatLng(val latitude: Double, val longitude: Double)

data class Place(
    val uid: String = "",
    val name: String = "",
    val address: String = "",
    val latLng: LatLng = LatLng(0.0, 0.0)
)

/**
 * 路线步骤（导航指令）
 * instruction: 语音播报文本
 * distance: 本段距离（米）
 * duration: 本段时长（秒）
 * maneuver: 转向类型
 * roadName: 道路名称
 */
data class RouteStep(
    val instruction: String = "",
    val distance: Double = 0.0,
    val duration: Long = 0L,
    val maneuver: ManeuverType = ManeuverType.STRAIGHT,
    val roadName: String = ""
)

/**
 * 路线规划结果
 */
data class RouteResult(
    val routeId: String = "",
    val origin: Place = Place(),
    val destination: Place = Place(),
    val steps: List<RouteStep> = emptyList(),
    val pathPoints: List<LatLng> = emptyList(),
    val totalDistance: Double = 0.0,
    val totalDuration: Long = 0L,
    val trafficCondition: TrafficCondition = TrafficCondition.UNKNOWN,
    val routeType: RouteType = RouteType.RECOMMENDED,
    // SDK 路线 tab 自带的数据（白马地图路线页显示的收费与红绿灯就来自这里）。
    val toll: Int = 0,
    val lights: Int = 0,
    val label: String = ""
)

/**
 * 导航状态
 */
data class NavigationState(
    val isNavigating: Boolean = false,
    val destination: Place? = null,
    val currentStep: RouteStep? = null,
    val nextStep: RouteStep? = null,
    val distanceToNextTurn: Double = 0.0,
    val remainingDistance: Double = 0.0,
    val remainingDuration: Long = 0L,
    val eta: Long = 0L,
    val currentSpeed: Float = 0f,
    val speedLimit: Float = 80f
)

enum class ManeuverType {
    STRAIGHT, TURN_LEFT, TURN_RIGHT, TURN_SLIGHT_LEFT, TURN_SLIGHT_RIGHT,
    TURN_SHARP_LEFT, TURN_SHARP_RIGHT, U_TURN, ROUNDABOUT, ARRIVE, DEPART
}

enum class RouteType {
    RECOMMENDED, FASTEST, SHORTEST, AVOID_TOLL, AVOID_HIGHWAY
}

enum class TrafficCondition {
    SMOOTH, SLOW, CONGESTED, UNKNOWN
}
