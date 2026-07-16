package com.huimao.map.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/** 接收微信地图代理转发的位置，坐标约定为百度 BD09LL。 */
class LocationRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) handle(intent)
    }

    private fun handle(intent: Intent) {
        val uri = intent.data
        val lat = uri?.getQueryParameter("lat")?.toDoubleOrNull() ?: 0.0
        val lng = uri?.getQueryParameter("lng")?.toDoubleOrNull() ?: 0.0
        val name = uri?.getQueryParameter("name")?.takeIf { it.isNotBlank() } ?: "微信位置"
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0 || lat == 0.0 || lng == 0.0) {
            Toast.makeText(this, "微信位置坐标无效", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        NaviActivity.start(applicationContext, lat, lng, name)
        finish()
    }
}
