package com.huimao.map.ui

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.TextView
import com.baidu.navisdk.adapter.BaiduNaviManagerFactory
import com.baidu.navisdk.adapter.struct.BNGuideConfig

private const val TAG = "NaviGuideActivity"

/**
 * 承载百度原生导航诱导 View 的 Activity。
 *
 * 必须继承普通 Activity，不能用 AppCompatActivity。
 * AppCompatActivity 重写了 getLayoutInflater() 返回 AppCompatLayoutInflater，
 * 百度 SDK 内部 inflate nsdk_layout_rg_mapmode_main_new_v2
 * （根元素为 RGRootViewFrameLayout）时，AppCompatLayoutInflater 会返回 null，
 * 导致 RGDefaultBaseUiFrame.a() 抛出：
 *   null cannot be cast to non-null type RGRootViewFrameLayout
 */
class NaviGuideActivity : Activity() {

    private var guideCreated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val config = BNGuideConfig.Builder().build()
            // 不要在此之前调用 setContentView
            // SDK 会自己操作 Window，然后返回根 View
            val guideView = BaiduNaviManagerFactory.getRouteGuideManager()
                .onCreate(this, config)

            if (guideView != null) {
                guideCreated = true
                setContentView(guideView)
                Log.i(TAG, "Navi guide view attached")
            } else {
                Log.e(TAG, "guideView is null")
                setContentView(TextView(this).apply {
                    setTextColor(0xFFFFFFFF.toInt())
                    setBackgroundColor(0xFF101010.toInt())
                    setPadding(32, 32, 32, 32)
                    text = "导航诱导界面创建失败（view=null）\n可能原因：AK 鉴权失败或导航服务未授权"
                })
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Guide onCreate failed: ${e.javaClass.name}: ${e.message}", e)
            setContentView(TextView(this).apply {
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFF101010.toInt())
                setPadding(32, 32, 32, 32)
                text = "导航界面异常\n${e.javaClass.name}\n${e.message}"
            })
        }
    }

    override fun onStart() {
        super.onStart()
        if (guideCreated) BaiduNaviManagerFactory.getRouteGuideManager().onStart()
    }

    override fun onResume() {
        super.onResume()
        if (guideCreated) BaiduNaviManagerFactory.getRouteGuideManager().onResume()
    }

    override fun onPause() {
        super.onPause()
        if (guideCreated) BaiduNaviManagerFactory.getRouteGuideManager().onPause()
    }

    override fun onStop() {
        super.onStop()
        if (guideCreated) BaiduNaviManagerFactory.getRouteGuideManager().onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (guideCreated) BaiduNaviManagerFactory.getRouteGuideManager().onDestroy(false)
        Log.i(TAG, "NaviGuideActivity destroyed")
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (guideCreated && keyCode == KeyEvent.KEYCODE_BACK) {
            val handled = BaiduNaviManagerFactory.getRouteGuideManager().onKeyDown(keyCode, event)
            if (handled) return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
