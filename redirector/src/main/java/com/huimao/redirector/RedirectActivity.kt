package com.huimao.redirector

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import java.net.URLDecoder
import kotlin.concurrent.thread
import kotlin.math.*

class RedirectActivity : Activity() {
    override fun onCreate(state: Bundle?) { super.onCreate(state); process(intent) }
    override fun onNewIntent(i: Intent?) { super.onNewIntent(i); if (i != null) process(i) }

    private fun process(i: Intent) {
        val raw = when (i.action) {
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
            else -> i.dataString
        }.orEmpty()
        if (raw.isBlank()) return fail("没有收到位置信息")
        thread {
            val text = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
            val p = parse(text)
            runOnUiThread { if (p == null) fail("无法识别该地图位置") else forward(p) }
        }
    }

    private fun forward(p: Place) {
        val point = when (p.coord.lowercase()) {
            "bd09", "bd09ll" -> p.lat to p.lng
            "wgs84", "wgs" -> {
                val gcj = wgsToGcj(p.lat, p.lng)
                gcjToBd(gcj.first, gcj.second)
            }
            else -> gcjToBd(p.lat, p.lng)
        }
        val uri = Uri.Builder().scheme("baidunaviauto").authority("navigate")
            .appendQueryParameter("lat", point.first.toString())
            .appendQueryParameter("lng", point.second.toString())
            .appendQueryParameter("name", p.name.ifBlank { "微信位置" }).build()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.huimao.map"))
            finish()
        } catch (_: Throwable) { fail("请先安装灰猫地图") }
    }

    private fun parse(raw: String): Place? {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        val q = HashMap<String, String>()
        uri.queryParameterNames.forEach { key -> uri.getQueryParameter(key)?.let { q[key.lowercase()] = it } }
        val coord = q["coord_type"] ?: q["coordtype"] ?: when {
            raw.startsWith("baidumap") || raw.startsWith("bdapp") -> "bd09"
            else -> "gcj02"
        }
        val name = q["to"] ?: q["dname"] ?: q["name"] ?: q["title"] ?: q["address"] ?: "微信位置"
        fun num(s: String?) = Regex("[-+]?[0-9]*\\.?[0-9]+").find(s.orEmpty())?.value?.toDoubleOrNull()
        val lat = num(q["lat"] ?: q["dlat"])
        val lng = num(q["lng"] ?: q["lon"] ?: q["dlon"])
        if (lat != null && lng != null) return Place(lat, lng, name, coord)
        val pair = q["location"] ?: q["center"] ?: q["position"] ?: q["tocoord"]
        val values = pair?.split(',', '|')?.mapNotNull(::num)
        if (values != null && values.size >= 2) return Place(values[0], values[1], name, coord)
        Regex("(?:latlng|location|coord|tocoord)[=:]([0-9.+-]+)[,%2C ]+([0-9.+-]+)", RegexOption.IGNORE_CASE)
            .find(raw)?.let { return Place(num(it.groupValues[1]) ?: return null, num(it.groupValues[2]) ?: return null, name, coord) }
        return null
    }

    private fun fail(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); finish() }
    private data class Place(val lat: Double, val lng: Double, val name: String, val coord: String)

    private fun gcjToBd(lat: Double, lng: Double): Pair<Double, Double> {
        val x = PI * 3000.0 / 180.0
        val z = sqrt(lng * lng + lat * lat) + 0.00002 * sin(lat * x)
        val t = atan2(lat, lng) + 0.000003 * cos(lng * x)
        return (z * sin(t) + 0.006) to (z * cos(t) + 0.0065)
    }
    private fun wgsToGcj(lat: Double, lng: Double): Pair<Double, Double> {
        if (lng !in 72.004..137.8347 || lat !in 0.8293..55.8271) return lat to lng
        val a = 6378245.0; val ee = 0.006693421622965943; val x = lng - 105.0; val y = lat - 35.0
        var dLat = -100 + 2*x + 3*y + .2*y*y + .1*x*y + .2*sqrt(abs(x))
        dLat += (20*sin(6*x*PI)+20*sin(2*x*PI))*2/3 + (20*sin(y*PI)+40*sin(y/3*PI))*2/3 + (160*sin(y/12*PI)+320*sin(y*PI/30))*2/3
        var dLng = 300 + x + 2*y + .1*x*x + .1*x*y + .1*sqrt(abs(x))
        dLng += (20*sin(6*x*PI)+20*sin(2*x*PI))*2/3 + (20*sin(x*PI)+40*sin(x/3*PI))*2/3 + (150*sin(x/12*PI)+300*sin(x/30*PI))*2/3
        val r = lat/180*PI; val m = sin(r); val mm = 1-ee*m*m; val sm = sqrt(mm)
        return (lat+dLat*180/((a*(1-ee))/(mm*sm)*PI)) to (lng+dLng*180/(a/sm*cos(r)*PI))
    }
}
