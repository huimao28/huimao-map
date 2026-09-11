package com.huimao.map.car

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.Surface
import com.huimao.map.navigation.CarNavigationBridge
import com.huimao.map.navigation.CarRouteGeometry
import com.huimao.map.navigation.GeoPoint
import kotlin.math.cos
import kotlin.math.sin

/** Lightweight first-pass OSM-style renderer. Replace the background with offline OSM tiles/vector data. */
class OsmCarRenderer(private val surface: Surface) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private var active = true

    fun render() {
        if (!active) return
        val canvas = try { surface.lockCanvas(null) } catch (_: Throwable) { null } ?: return
        try {
            canvas.drawColor(Color.rgb(242, 240, 234))
            drawRoadGrid(canvas)
            val state = CarNavigationBridge.state
            val route = state.routePoints.map { GeoPoint(it.first, it.second) }
            val car = GeoPoint(state.latitude, state.longitude)
            if (route.size > 1 && state.latitude != 0.0) {
                val visible = CarRouteGeometry.visibleRoute(route, car)
                drawRoute(canvas, visible, Color.rgb(30, 90, 170), 13f)
                drawRoute(canvas, visible, Color.rgb(55, 145, 242), 8f)
            }
            if (state.latitude != 0.0) drawCar(canvas, state.bearing)
            paint.color = Color.DKGRAY; paint.textSize = 18f; paint.style = Paint.Style.FILL
            canvas.drawText("© OpenStreetMap contributors", 14f, canvas.height - 16f, paint)
        } finally { surface.unlockCanvasAndPost(canvas) }
    }

    fun stop() { active = false }

    private fun drawRoadGrid(c: Canvas) {
        paint.color = Color.rgb(218, 216, 210); paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
        var x = 0f; while (x < c.width) { c.drawLine(x, 0f, x + c.width, c.height.toFloat(), paint); x += 110f }
        var y = 0f; while (y < c.height) { c.drawLine(0f, y, c.width.toFloat(), y, paint); y += 100f }
    }

    private fun drawRoute(c: Canvas, points: List<GeoPoint>, color: Int, width: Float) {
        if (points.size < 2) return
        val center = points[0]; val path = Path()
        points.forEachIndexed { i, p ->
            val x = c.width / 2f + ((p.lon - center.lon) * 700000.0 * cos(Math.toRadians(center.lat))).toFloat()
            val y = c.height / 2f - ((p.lat - center.lat) * 700000.0).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        paint.color = color; paint.strokeWidth = width; paint.style = Paint.Style.STROKE; c.drawPath(path, paint)
    }

    private fun drawCar(c: Canvas, bearing: Float) {
        val x = c.width / 2f; val y = c.height / 2f
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE; c.drawCircle(x, y, 20f, paint)
        paint.color = Color.rgb(25, 105, 220); c.drawCircle(x, y, 13f, paint)
        paint.color = Color.WHITE; paint.strokeWidth = 5f; paint.style = Paint.Style.STROKE
        val r = Math.toRadians(bearing.toDouble())
        c.drawLine(x, y, x + (27 * sin(r)).toFloat(), y - (27 * cos(r)).toFloat(), paint)
    }
}
