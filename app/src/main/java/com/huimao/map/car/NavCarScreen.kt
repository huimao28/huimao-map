package com.huimao.map.car

import android.graphics.Rect
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import com.huimao.map.navigation.CarNavigationBridge

/** Android Auto screen. Baidu on the phone owns navigation; OSM is rendered here. */
class NavCarScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {
    private var renderer: OsmCarRenderer? = null
    private var listener: (() -> Unit)? = null

    init {
        listener = { invalidate() }
        CarNavigationBridge.addListener(listener!!)
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
    }

    override fun onGetTemplate(): Template {
        val s = CarNavigationBridge.state
        val end = Action.Builder()
            .setTitle("结束")
            .setOnClickListener { CarNavigationBridge.stop(); invalidate() }
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder().addAction(end).build())
            .build()
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface ?: return
        renderer?.stop()
        renderer = OsmCarRenderer(surface)
        renderer?.render()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        renderer?.stop()
        renderer = null
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) = Unit
    override fun onStableAreaChanged(stableArea: Rect) = Unit

    fun release() {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
        listener?.let { CarNavigationBridge.removeListener(it) }
        listener = null
        renderer?.stop()
        renderer = null
    }
}
