package com.huimao.map.car

import android.graphics.Rect
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Maneuver
import androidx.car.app.model.NavigationInfo
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationTemplate
import com.huimao.map.navigation.CarNavigationBridge

/** Android Auto screen. The phone/Baidu SDK owns navigation; this screen owns rendering only. */
class NavCarScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {
    private var renderer: OsmCarRenderer? = null
    private var listener: (() -> Unit)? = null

    init {
        listener = { invalidate() }
        CarNavigationBridge.addListener(listener!!)
    }

    override fun onGetTemplate(): Template {
        val s = CarNavigationBridge.state
        val maneuver = Maneuver.Builder(Maneuver.TYPE_DEPART).build()
        val navigationInfo = NavigationInfo.Builder().addManeuver(maneuver).build()
        val end = Action.Builder()
            .setTitle("结束")
            .setOnClickListener { CarNavigationBridge.stop(); invalidate() }
            .build()
        return NavigationTemplate.Builder()
            .setNavigationInfo(navigationInfo)
            .setActionStrip(ActionStrip.Builder().addAction(end).build())
            .setSurfaceCallback(this)
            .build()
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        renderer?.stop()
        renderer = OsmCarRenderer(surfaceContainer.surface)
        renderer?.render()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        renderer?.stop()
        renderer = null
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) = Unit
    override fun onStableAreaChanged(stableArea: Rect) = Unit

    override fun onDestroy() {
        listener?.let { CarNavigationBridge.removeListener(it) }
        listener = null
        renderer?.stop()
        renderer = null
        super.onDestroy()
    }
}
