package com.graycat.map.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

private const val TAG = "NaviLauncher"

/**
 * 导航启动器：按优先级调起外部地图 App 进行导航。
 *
 * 优先级：百度地图 → 高德地图 → Google Maps → 系统浏览器（百度地图Web）
 *
 * 不依赖 carnavi SDK，普通地图 AK 即可使用。
 */
object NaviLauncher {

    /**
     * @param destLat  终点纬度（BD09LL）
     * @param destLng  终点经度（BD09LL）
     * @param destName 终点名称
     * @param startLat 起点纬度（BD09LL），0.0 表示用当前位置
     * @param startLng 起点经度（BD09LL），0.0 表示用当前位置
     * @return 是否成功启动
     */
    fun launch(
        context: Context,
        destLat: Double,
        destLng: Double,
        destName: String,
        startLat: Double = 0.0,
        startLng: Double = 0.0
    ): Boolean {
        // 1. 百度地图 App
        if (tryBaiduMaps(context, destLat, destLng, destName, startLat, startLng)) return true
        // 2. 高德地图 App（坐标需从 BD09LL 转 GCJ-02，此处用近似转换）
        if (tryGaodeMaps(context, destLat, destLng, destName, startLat, startLng)) return true
        // 3. Google Maps
        if (tryGoogleMaps(context, destLat, destLng, destName)) return true
        // 4. 兜底：浏览器打开百度地图 Web
        return tryWebBaiduMaps(context, destLat, destLng, destName)
    }

    // ── 百度地图 ────────────────────────────────────────

    private fun tryBaiduMaps(
        context: Context,
        destLat: Double, destLng: Double, destName: String,
        startLat: Double, startLng: Double
    ): Boolean {
        // 使用 baidumap://map/navi 直接进入导航模式（非路线规划页）
        // 文档：https://lbs.baidu.com/docs/webapi?title=mapadjustment/uri/andriod
        val uriStr = buildString {
            append("baidumap://map/navi")
            append("?query=${Uri.encode(destName)}")
            append("&location=${destLat},${destLng}")
            append("&coord_type=bd09ll")
            append("&type=TIME")  // 最短时间
            append("&src=andr.baidunavi.auto")
        }
        return tryStartActivity(context, Intent(Intent.ACTION_VIEW, Uri.parse(uriStr)), "百度地图导航")
    }

    // ── 高德地图 ────────────────────────────────────────

    private fun tryGaodeMaps(
        context: Context,
        destLat: Double, destLng: Double, destName: String,
        startLat: Double, startLng: Double
    ): Boolean {
        // BD09LL → GCJ-02 近似转换
        val (gcjDestLat, gcjDestLng) = bd09ToGcj02(destLat, destLng)

        val uriStr = buildString {
            append("androidamap://navi?sourceApplication=baidunavi")
            append("&poiname=${Uri.encode(destName)}")
            append("&lat=${gcjDestLat}&lon=${gcjDestLng}&dev=0")
            if (startLat != 0.0 && startLng != 0.0) {
                val (gcjSLat, gcjSLng) = bd09ToGcj02(startLat, startLng)
                append("&slat=${gcjSLat}&slon=${gcjSLng}&sname=${Uri.encode("当前位置")}")
            }
        }
        return tryStartActivity(context, Intent(Intent.ACTION_VIEW, Uri.parse(uriStr)), "高德地图")
    }

    // ── Google Maps ─────────────────────────────────────

    private fun tryGoogleMaps(
        context: Context,
        destLat: Double, destLng: Double, destName: String
    ): Boolean {
        // BD09LL → WGS-84 近似转换（Google Maps 用 WGS-84）
        val (wgsLat, wgsLng) = bd09ToWgs84(destLat, destLng)
        val uri = Uri.parse("google.navigation:q=${wgsLat},${wgsLng}&mode=d")
        return tryStartActivity(context, Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
        }, "Google Maps")
    }

    // ── 百度地图 Web ─────────────────────────────────────

    private fun tryWebBaiduMaps(
        context: Context,
        destLat: Double, destLng: Double, destName: String
    ): Boolean {
        val url = "https://api.map.baidu.com/direction?destination=${destLat},${destLng}" +
                "&destination_name=${Uri.encode(destName)}&coord_type=bd09ll&mode=driving&output=html"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        return tryStartActivity(context, intent, "浏览器")
    }

    // ── 工具 ─────────────────────────────────────────────

    private fun tryStartActivity(context: Context, intent: Intent, appName: String): Boolean {
        return try {
            val resolved = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            if (resolved.isNotEmpty()) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.i(TAG, "Launched navigation via $appName")
                true
            } else {
                Log.i(TAG, "$appName not available")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "$appName launch failed: ${e.message}")
            false
        }
    }

    // ── 坐标转换 ─────────────────────────────────────────

    /** BD09LL → GCJ-02 */
    private fun bd09ToGcj02(bdLat: Double, bdLng: Double): Pair<Double, Double> {
        val PI = Math.PI
        val x = bdLng - 0.0065
        val y = bdLat - 0.006
        val z = Math.sqrt(x * x + y * y) - 0.00002 * Math.sin(y * PI * 3000.0 / 180.0)
        val theta = Math.atan2(y, x) - 0.000003 * Math.cos(x * PI * 3000.0 / 180.0)
        return Pair(z * Math.sin(theta), z * Math.cos(theta))
    }

    /** BD09LL → WGS-84（先转 GCJ-02 再转 WGS-84） */
    private fun bd09ToWgs84(bdLat: Double, bdLng: Double): Pair<Double, Double> {
        val (gcjLat, gcjLng) = bd09ToGcj02(bdLat, bdLng)
        return gcj02ToWgs84(gcjLat, gcjLng)
    }

    /** GCJ-02 → WGS-84 近似 */
    private fun gcj02ToWgs84(lat: Double, lng: Double): Pair<Double, Double> {
        val PI = Math.PI
        val a = 6378245.0
        val ee = 0.00669342162296594323
        fun transformLat(x: Double, y: Double): Double {
            var ret = -100.0 + 2.0*x + 3.0*y + 0.2*y*y + 0.1*x*y + 0.2*Math.sqrt(Math.abs(x))
            ret += (20.0*Math.sin(6.0*x*PI) + 20.0*Math.sin(2.0*x*PI)) * 2.0/3.0
            ret += (20.0*Math.sin(y*PI) + 40.0*Math.sin(y/3.0*PI)) * 2.0/3.0
            ret += (160.0*Math.sin(y/12.0*PI) + 320.0*Math.sin(y/30.0*PI)) * 2.0/3.0
            return ret
        }
        fun transformLng(x: Double, y: Double): Double {
            var ret = 300.0 + x + 2.0*y + 0.1*x*x + 0.1*x*y + 0.1*Math.sqrt(Math.abs(x))
            ret += (20.0*Math.sin(6.0*x*PI) + 20.0*Math.sin(2.0*x*PI)) * 2.0/3.0
            ret += (20.0*Math.sin(x*PI) + 40.0*Math.sin(x/3.0*PI)) * 2.0/3.0
            ret += (150.0*Math.sin(x/12.0*PI) + 300.0*Math.sin(x/30.0*PI)) * 2.0/3.0
            return ret
        }
        val dLat = transformLat(lng-105.0, lat-35.0)
        val dLng = transformLng(lng-105.0, lat-35.0)
        val radLat = lat / 180.0 * PI
        var magic = Math.sin(radLat); magic = 1.0 - ee*magic*magic
        val sqrtMagic = Math.sqrt(magic)
        val dLatFin = (dLat * 180.0) / ((a*(1-ee))/(magic*sqrtMagic)*PI)
        val dLngFin = (dLng * 180.0) / (a/sqrtMagic*Math.cos(radLat)*PI)
        return Pair(lat - dLatFin, lng - dLngFin)
    }
}
