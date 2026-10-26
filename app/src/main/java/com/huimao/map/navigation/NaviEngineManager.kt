package com.huimao.map.navigation

import android.content.Context
import android.util.Log
import com.baidu.mapapi.SDKInitializer
import com.baidu.navisdk.adapter.BaiduNaviManagerFactory
import com.baidu.navisdk.adapter.IBaiduNaviManager
import com.baidu.navisdk.adapter.struct.BNaviInitConfig

/**
 * 导航引擎初始化的唯一入口（白马地图式流程的前提）。
 *
 * 以前引擎只在 NaviActivity 里初始化，预览页只能用 RoutePlanSearch 另算一套；
 * 现在预览页（MainActivity 内）就能先把引擎初始化好并用 SDK 算路，
 * NaviActivity 打开时直接复用已算好的路线，不再重复算路。
 *
 * 幂等：引擎已初始化时立即回调 onReady；初始化进行中时回调排队，
 * 成功后按顺序执行。TTS 初始化不归这里管，仍由 NaviActivity 自己做。
 */
object NaviEngineManager {
    private const val TAG = "NaviEngineManager"

    private var initStarted = false
    private val pendingReady = ArrayList<() -> Unit>()
    private val pendingFailed = ArrayList<(Int) -> Unit>()

    fun isInited(): Boolean = try {
        BaiduNaviManagerFactory.getBaiduNaviManager().isInited()
    } catch (_: Throwable) {
        false
    }

    fun ensureInitialized(
        context: Context,
        ak: String,
        onReady: () -> Unit,
        onFailed: (Int) -> Unit
    ) {
        if (isInited()) {
            onReady()
            return
        }
        if (initStarted) {
            pendingReady.add(onReady)
            pendingFailed.add(onFailed)
            return
        }
        initStarted = true
        pendingReady.add(onReady)
        pendingFailed.add(onFailed)
        try { SDKInitializer.setAgreePrivacy(context.applicationContext, true) } catch (_: Throwable) {}
        try { SDKInitializer.setApiKey(ak) } catch (_: Throwable) {}
        val config = BNaviInitConfig.Builder()
            .sdcardRootPath(
                context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath
            )
            .naviInitListener(object : IBaiduNaviManager.INaviInitListener {
                override fun onAuthResult(status: Int, msg: String?) {
                    Log.i(TAG, "auth=$status $msg")
                }

                override fun initStart() {
                    Log.i(TAG, "navi init start")
                }

                override fun initSuccess() {
                    try {
                        BaiduNaviManagerFactory.getBaiduNaviManager().apply {
                            externalLocation(true)
                            setGpsNeverClose(true)
                            initSensor()
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "Navigation GPS start failed", e)
                    }
                    val callbacks = pendingReady.toList()
                    pendingReady.clear()
                    pendingFailed.clear()
                    callbacks.forEach { it() }
                }

                override fun initFailed(errCode: Int) {
                    Log.e(TAG, "navi init failed errCode=$errCode")
                    val callbacks = pendingFailed.toList()
                    pendingReady.clear()
                    pendingFailed.clear()
                    callbacks.forEach { it(errCode) }
                }
            }).build()
        try {
            BaiduNaviManagerFactory.getBaiduNaviManager().enableOutLog(false)
            BaiduNaviManagerFactory.getBaiduNaviManager().init(context, config)
        } catch (e: Throwable) {
            Log.e(TAG, "navi init exception", e)
            pendingReady.clear()
            val callbacks = pendingFailed.toList()
            pendingFailed.clear()
            callbacks.forEach { it(-1) }
        }
    }
}
