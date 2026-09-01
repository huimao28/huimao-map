package com.huimao.map.navigation

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.atomic.AtomicLong

/** 将手机导航状态发布到已连接的 Wear OS 设备。 */
object WearNavigationSync {
    private const val PATH = "/huimao/navigation"
    private const val KEY_NAVIGATING = "navigating"
    private const val KEY_INSTRUCTION = "instruction"
    private const val KEY_MANEUVER = "maneuverType"
    private const val KEY_TURN_DISTANCE = "distanceToTurnMeters"
    private const val KEY_REMAINING_DISTANCE = "remainingDistanceMeters"
    private const val KEY_REMAINING_TIME = "remainingTimeSeconds"
    private const val KEY_SPEED = "speedKmh"
    private const val KEY_ROAD = "roadName"
    private const val KEY_DESTINATION = "destinationName"
    private const val KEY_UPDATED_AT = "updatedAt"
    private const val KEY_ANNOUNCEMENT = "announcement"
    private const val KEY_ANNOUNCEMENT_ID = "announcementId"

    private var dataClient: DataClient? = null
    private val sequence = AtomicLong(0)

    @Synchronized
    fun initialize(context: Context) {
        if (dataClient == null) dataClient = Wearable.getDataClient(context.applicationContext)
    }

    fun publish(context: Context, state: CarNavigationState): Task<*> {
        initialize(context)
        val request = PutDataMapRequest.create(PATH).apply {
            dataMap.putBoolean(KEY_NAVIGATING, state.navigating)
            dataMap.putString(KEY_INSTRUCTION, state.instruction)
            dataMap.putInt(KEY_MANEUVER, state.maneuverType)
            dataMap.putInt(KEY_TURN_DISTANCE, state.distanceToTurnMeters.coerceAtLeast(0))
            dataMap.putInt(KEY_REMAINING_DISTANCE, state.remainingDistanceMeters.coerceAtLeast(0))
            dataMap.putInt(KEY_REMAINING_TIME, state.remainingTimeSeconds.coerceAtLeast(0))
            dataMap.putInt(KEY_SPEED, state.speedKmh.coerceAtLeast(0))
            dataMap.putString(KEY_ROAD, state.roadName)
            dataMap.putString(KEY_DESTINATION, state.destinationName)
            dataMap.putLong(KEY_UPDATED_AT, sequence.incrementAndGet())
            val announcement = com.huimao.map.navigation.CarNavigationBridge.announcement()
            dataMap.putLong(KEY_ANNOUNCEMENT_ID, announcement.first)
            dataMap.putString(KEY_ANNOUNCEMENT, announcement.second)
        }.asPutDataRequest().setUrgent()
        return dataClient!!.putDataItem(request)
    }
}
