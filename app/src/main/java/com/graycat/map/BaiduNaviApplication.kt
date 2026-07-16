package com.graycat.map

import android.app.Application
import android.util.Log
import com.baidu.location.LocationClient
import com.baidu.mapapi.SDKInitializer
import com.graycat.map.data.AppSettingsKeys
import com.graycat.map.data.dataStore
import com.graycat.map.map.BaiduMapManager
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private const val TAG = "BaiduNaviApp"

class BaiduNaviApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // ① 隐私合规：两个 SDK 各有独立的隐私同意接口，必须都调用
        //    a. 定位 SDK：LocationClient.setAgreePrivacy（静态方法，无需 context）
        //    b. 地图 SDK：SDKInitializer.setAgreePrivacy（需要 context）
        //    两者都必须在各自 SDK 初始化之前调用
        LocationClient.setAgreePrivacy(true)
        SDKInitializer.setAgreePrivacy(applicationContext, true)
        Log.i(TAG, "setAgreePrivacy(true) called for both Location SDK and Map SDK")

        // ② 同步读取 AK（runBlocking 确保 AK 在 SDK 初始化前已就绪）
        //    DataStore 首次读取通常 < 5ms，不影响启动速度
        val savedAk = try {
            runBlocking {
                dataStore.data
                    .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
                    .first()[AppSettingsKeys.BAIDU_API_KEY] ?: ""
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read AK from DataStore: ${e.message}")
            ""
        }

        Log.i(TAG, "Saved AK: ${if (savedAk.isBlank()) "(empty)" else "${savedAk.take(8)}..."}")

        // ③ 初始化百度地图 SDK（setApiKey 必须在 initialize 之前）
        //    如果 AK 为空，SDK 仍会初始化但鉴权会失败（地图可显示，搜索/导航需要 AK）
        BaiduMapManager.initialize(this, savedAk)
    }
}
