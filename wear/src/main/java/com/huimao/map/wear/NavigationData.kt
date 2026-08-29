package com.huimao.map.wear

import com.google.android.gms.wearable.DataMap

internal data class WearNavigationState(
    val navigating: Boolean = false,
    val instruction: String = "等待手机开始导航",
    val distanceToTurnMeters: Int = 0,
    val roadName: String = "",
    val remainingDistanceMeters: Int = 0
) {
    companion object {
        fun from(map: DataMap) = WearNavigationState(
            navigating = map.getBoolean("navigating", false),
            instruction = map.getString("instruction").orEmpty().ifBlank { "等待手机开始导航" },
            distanceToTurnMeters = map.getInt("distanceToTurnMeters", 0),
            roadName = map.getString("roadName").orEmpty(),
            remainingDistanceMeters = map.getInt("remainingDistanceMeters", 0)
        )
    }
}
