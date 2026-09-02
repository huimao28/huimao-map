package com.huimao.map.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

/** 在后台接收手机导航状态，更新手表实时导航通知并在每次手机播报时振动。 */
class NavigationListenerService : WearableListenerService() {
    private val notificationId = 117
    private var lastAnnouncementId = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onDataChanged(buffer: DataEventBuffer) {
        buffer.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED || event.dataItem.uri.path != "/huimao/navigation") return@forEach
            val map = DataMapItem.fromDataItem(event.dataItem).dataMap
            updateNotification(map)
            val id = map.getLong("announcementId", 0L)
            if (id > 0L && id != lastAnnouncementId) {
                lastAnnouncementId = id
                postAnnouncementAlert(map.getString("announcement").orEmpty())
                vibrateForAnnouncement()
            }
        }
    }

    private fun updateNotification(map: com.google.android.gms.wearable.DataMap) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val navigating = map.getBoolean("navigating", false)
        if (!navigating) {
            manager.cancel(notificationId)
            return
        }
        val instruction = map.getString("instruction").orEmpty().ifBlank { "正在导航" }
        val distance = map.getInt("distanceToTurnMeters", 0)
        val road = map.getString("roadName").orEmpty()
        val detail = buildString {
            if (distance > 0) append("${distance} 米后")
            if (road.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(road)
            }
            if (isEmpty()) append("灰猫地图导航中")
        }
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_navigation_ongoing)
            .setContentTitle(instruction)
            .setContentText(detail)
            .setSubText("灰猫地图 · 导航中")
            .setContentIntent(intent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // 同时让系统通知通道负责振动；部分 Wear OS 设备会限制后台直接调用 Vibrator。
            .setVibrate(VIBRATION_PATTERN)

        // 将导航通知注册为 Wear OS 系统级 Ongoing Activity，使表盘底部出现可点击活动指示器。
        OngoingActivity.Builder(applicationContext, notificationId, builder)
            .setAnimatedIcon(R.drawable.ic_navigation_ongoing)
            .setStaticIcon(R.drawable.ic_navigation_ongoing)
            .setTouchIntent(intent)
            .build()
            .apply(applicationContext)
        manager.notify(notificationId, builder.build())
    }

    private fun postAnnouncementAlert(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val alert = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_navigation_ongoing)
            .setContentTitle("导航播报")
            .setContentText(text.ifBlank { "请注意前方路线" })
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setTimeoutAfter(3500L)
            .setVibrate(VIBRATION_PATTERN)
            .build()
        manager.notify(ANNOUNCEMENT_NOTIFICATION_ID, alert)
    }

    private fun vibrateForAnnouncement() {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else getSystemService(Vibrator::class.java)
        if (vibrator?.hasVibrator() != true) return
        val effect = if (Build.VERSION.SDK_INT >= 26) {
            VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 120), -1)
        } else null
        if (effect != null) vibrator.vibrate(effect) else @Suppress("DEPRECATION") vibrator.vibrate(240)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "导航通知", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "手机端灰猫地图导航实时指引" })
        manager.createNotificationChannel(NotificationChannel(
            ALERT_CHANNEL_ID, "导航播报", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "导航语音播报时的振动提醒"
            enableVibration(true)
            vibrationPattern = VIBRATION_PATTERN
        })
    }

    companion object {
        private const val CHANNEL_ID = "huimao_navigation"
        private const val ANNOUNCEMENT_NOTIFICATION_ID = 118
        private const val ALERT_CHANNEL_ID = "huimao_navigation_alert"
        private val VIBRATION_PATTERN = longArrayOf(0L, 120L, 80L, 120L)
    }
}
