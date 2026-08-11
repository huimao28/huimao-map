package com.huimao.map.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 接收外部导航请求并转交给 [NaviActivity]。
 *
 * 支持三类来源：
 * 1. 微信地图代理转发：`baidunaviauto://navigate?lat=&lng=&name=`，坐标约定为百度 BD09LL；
 * 2. Android Auto / 语音助手下发的导航意图：`androidx.car.app.action.NAVIGATE` + `geo:`；
 * 3. 通用地图意图：`geo:` 与 `google.navigation:`。
 *
 * 注意：只有内部的 `baidunaviauto://` 协议使用 BD09LL；按 Android 平台约定，
 * `geo:` / `google.navigation:` 携带的是 WGS-84 坐标，必须先转换成 BD09LL
 * 再交给百度导航，否则目的地会出现数百米的偏移。
 */
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
        if (uri == null) {
            Log.w(TAG, "missing data uri, action=${intent.action}")
            toastAndFinish("未收到导航目的地")
            return
        }
        val target = parseTarget(uri)
        if (target == null) {
            Log.w(TAG, "unsupported navigation uri: $uri")
            toastAndFinish("该导航请求缺少有效坐标，请在应用内搜索目的地")
            return
        }
        Log.i(TAG, "navigate to ${target.name} (${target.lat}, ${target.lng}) from scheme=${uri.scheme}")
        NaviActivity.start(applicationContext, target.lat, target.lng, target.name)
        finish()
    }

    private fun toastAndFinish(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun parseTarget(uri: Uri): Target? = when (uri.scheme?.lowercase()) {
        "baidunaviauto" -> parseInternal(uri)
        "geo" -> parseGeo(uri)
        "google.navigation" -> parseGoogleNavigation(uri)
        else -> null
    }

    /** 内部协议，坐标已是 BD09LL，无需转换。 */
    private fun parseInternal(uri: Uri): Target? {
        val lat = uri.getQueryParameter("lat")?.toDoubleOrNull() ?: return null
        val lng = uri.getQueryParameter("lng")?.toDoubleOrNull() ?: return null
        if (!isValid(lat, lng)) return null
        val name = uri.getQueryParameter("name")?.takeIf { it.isNotBlank() } ?: "微信位置"
        return Target(lat, lng, name)
    }

    /**
     * `geo:39.9,116.4`、`geo:39.9,116.4?z=16`、`geo:0,0?q=39.9,116.4(公司)`。
     *
     * `geo:` 属于 opaque URI，[Uri.getQueryParameter] 会抛 UnsupportedOperationException，
     * 因此这里手动解析 schemeSpecificPart。
     */
    private fun parseGeo(uri: Uri): Target? {
        val ssp = uri.schemeSpecificPart ?: return null
        val path = ssp.substringBefore('?')
        val query = ssp.substringAfter('?', "")
        val point = queryValue(query, "q")?.let { parsePoint(it) }?.takeIf { isValid(it.lat, it.lng) }
            ?: parsePoint(path)?.takeIf { isValid(it.lat, it.lng) }
            ?: return null
        return toBaiduTarget(point)
    }

    /** `google.navigation:q=39.9,116.4&mode=d`、`google.navigation:ll=39.9,116.4`。 */
    private fun parseGoogleNavigation(uri: Uri): Target? {
        val query = uri.schemeSpecificPart ?: return null
        val point = queryValue(query, "ll")?.let { parsePoint(it) }?.takeIf { isValid(it.lat, it.lng) }
            ?: queryValue(query, "q")?.let { parsePoint(it) }?.takeIf { isValid(it.lat, it.lng) }
            ?: return null
        return toBaiduTarget(point)
    }

    /** 解析 `lat,lng`、`lat,lng(名称)`、`loc:lat,lng` 形式的文本。 */
    private fun parsePoint(text: String): Point? {
        val decoded = Uri.decode(text.trim()).trim()
        if (decoded.isEmpty()) return null
        val label = decoded.substringAfter('(', "").substringBeforeLast(')').takeIf { it.isNotBlank() }
        val coords = decoded.substringBefore('(').trim().removePrefix("loc:").trim()
        val parts = coords.split(',')
        if (parts.size < 2) return null
        val lat = parts[0].trim().toDoubleOrNull() ?: return null
        val lng = parts[1].trim().substringBefore('&').trim().toDoubleOrNull() ?: return null
        return Point(lat, lng, label)
    }

    private fun queryValue(query: String, key: String): String? {
        if (query.isEmpty()) return null
        return query.split('&')
            .asSequence()
            .mapNotNull { pair ->
                val index = pair.indexOf('=')
                if (index <= 0) null else pair.substring(0, index) to pair.substring(index + 1)
            }
            .firstOrNull { it.first.equals(key, ignoreCase = true) }
            ?.second
            ?.takeIf { it.isNotBlank() }
    }

    private fun isValid(lat: Double, lng: Double): Boolean =
        lat in -90.0..90.0 && lng in -180.0..180.0 && (abs(lat) > 1e-6 || abs(lng) > 1e-6)

    /** 外部意图坐标为 WGS-84，转换为百度 BD09LL。 */
    private fun toBaiduTarget(point: Point): Target {
        val gcj = wgs84ToGcj02(point.lat, point.lng)
        val bd09 = gcj02ToBd09(gcj.first, gcj.second)
        return Target(bd09.first, bd09.second, point.label ?: "导航目的地")
    }

    private fun wgs84ToGcj02(lat: Double, lng: Double): Pair<Double, Double> {
        if (outOfChina(lat, lng)) return lat to lng
        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * Math.PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = dLat * 180.0 / (SEMI_MAJOR_AXIS * (1 - EE) / (magic * sqrtMagic) * Math.PI)
        dLng = dLng * 180.0 / (SEMI_MAJOR_AXIS / sqrtMagic * cos(radLat) * Math.PI)
        return (lat + dLat) to (lng + dLng)
    }

    private fun gcj02ToBd09(lat: Double, lng: Double): Pair<Double, Double> {
        val z = sqrt(lng * lng + lat * lat) + 0.00002 * sin(lat * Math.PI * 3000.0 / 180.0)
        val theta = atan2(lat, lng) + 0.000003 * cos(lng * Math.PI * 3000.0 / 180.0)
        return (z * sin(theta) + 0.006) to (z * cos(theta) + 0.0065)
    }

    private fun outOfChina(lat: Double, lng: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * Math.PI) + 320.0 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }

    private data class Point(val lat: Double, val lng: Double, val label: String?)

    private data class Target(val lat: Double, val lng: Double, val name: String)

    private companion object {
        const val TAG = "LocationRedirect"
        const val SEMI_MAJOR_AXIS = 6378245.0
        const val EE = 0.00669342162296594323
    }
}
