package com.graycat.map.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.baidu.location.BDAbstractLocationListener
import com.baidu.location.BDLocation
import com.baidu.location.LocationClient
import com.baidu.location.LocationClientOption
import com.graycat.map.data.AppSettingsKeys
import com.graycat.map.data.dataStore
import com.graycat.map.map.BaiduMapManager
import com.graycat.map.model.LatLng
import com.graycat.map.ui.screens.AppNavGraph
import com.graycat.map.ui.screens.NavViewModel
import com.graycat.map.ui.theme.BaiduNaviTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {

    private var locationClient: LocationClient? = null
    private var navViewModel: NavViewModel? = null

    // 权限申请回调
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        Log.i(TAG, "Permission result: granted=$granted")
        if (granted) {
            startLocationClient()
        } else {
            Log.w(TAG, "Location permission denied by user")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 隐私合规（冗余调用，Application 中已调用，此处保险起见再调一次）
        LocationClient.setAgreePrivacy(true)

        setContent {
            BaiduNaviTheme(
                darkTheme = isSystemInDarkTheme(),
                dynamicColor = true
            ) {
                val vm: NavViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                    factory = androidx.lifecycle.ViewModelProvider
                        .AndroidViewModelFactory.getInstance(application)
                )
                navViewModel = vm
                AppNavGraph(vm = vm)
            }
        }

        // 监听 AK 变化：用户在设置页保存 AK 后，重新初始化 SDK 并通知 ViewModel
        lifecycleScope.launch {
            dataStore.data
                .map { prefs -> prefs[AppSettingsKeys.BAIDU_API_KEY] ?: "" }
                .distinctUntilChanged()
                .collect { ak ->
                    if (ak.isNotBlank()) {
                        Log.i(TAG, "AK updated, reinitializing SDK")
                        BaiduMapManager.initialize(applicationContext, ak)
                        // 通知 ViewModel SDK 已就绪
                        navViewModel?.notifySdkReady()
                    }
                }
        }

        // 检查/申请定位权限
        checkAndRequestLocationPermission()
    }

    override fun onResume() {
        super.onResume()
        // 回到前台时，如果 LocationClient 已创建但未运行，重启它
        val client = locationClient ?: return
        try {
            client.start()
            Log.i(TAG, "LocationClient restarted on resume")
        } catch (e: Exception) {
            Log.w(TAG, "LocationClient restart failed: ${e.message}")
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            locationClient?.stop()
            Log.i(TAG, "LocationClient paused")
        } catch (e: Exception) {
            Log.w(TAG, "LocationClient pause failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            locationClient?.stop()
            locationClient = null
            Log.i(TAG, "LocationClient destroyed")
        } catch (e: Exception) {
            Log.w(TAG, "LocationClient destroy failed: ${e.message}")
        }
    }

    // ── 权限检查 ──────────────────────────────────

    private fun checkAndRequestLocationPermission() {
        val fineOk = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseOk = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineOk || coarseOk) {
            Log.i(TAG, "Location permission already granted, starting location")
            startLocationClient()
        } else {
            Log.i(TAG, "Requesting location permissions")
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    // ── 定位启动 ──────────────────────────────────

    /**
     * 直接在 MainActivity 中创建并启动 LocationClient。
     *
     * 关键点：
     * - setAgreePrivacy 已在 Application.onCreate 最前面调用
     * - LocationClient 必须在主线程创建
     * - 不依赖 BaiduMapManager.isReady()，定位和地图 SDK 相互独立
     */
    private fun startLocationClient() {
        if (locationClient != null) {
            Log.d(TAG, "LocationClient already exists, skip")
            return
        }

        try {
            Log.i(TAG, "Creating LocationClient...")
            val client = LocationClient(applicationContext)

            val option = LocationClientOption().apply {
                // 高精度：GPS + 网络 + 传感器融合
                locationMode = LocationClientOption.LocationMode.Hight_Accuracy
                // 百度坐标系
                setCoorType("bd09ll")
                // 每 2 秒定位一次
                setScanSpan(2000)
                // 需要地址信息
                setIsNeedAddress(true)
                // 需要语义化描述
                setIsNeedLocationDescribe(true)
                // 开启 GNSS
                setOpenGnss(true)
                // stop 时不杀死定位进程（保证 onPause 后还能快速恢复）
                setIgnoreKillProcess(true)
                // 位置变化时自动回调
                setOpenAutoNotifyMode()
            }
            client.locOption = option

            client.registerLocationListener(object : BDAbstractLocationListener() {
                override fun onReceiveLocation(location: BDLocation?) {
                    if (location == null) return
                    val lat = location.latitude
                    val lng = location.longitude
                    val locType = location.locType
                    Log.d(TAG, "onReceiveLocation: lat=$lat, lng=$lng, locType=$locType")

                    when (locType) {
                        BDLocation.TypeGpsLocation,       // 61 = GPS
                        BDLocation.TypeNetWorkLocation,   // 161 = 网络
                        BDLocation.TypeOffLineLocation -> { // 66 = 离线
                            if (lat != 0.0 && lng != 0.0) {
                                navViewModel?.updateLocation(LatLng(lat, lng))
                                Log.i(TAG, "定位成功 locType=$locType: ($lat, $lng)")
                            }
                        }
                        68 -> Log.e(TAG, "定位失败：AK 鉴权失败 (68)，请检查 AK 和 SHA1")
                        62 -> Log.w(TAG, "定位失败：无有效定位，请检查权限 (62)")
                        63 -> Log.w(TAG, "定位失败：网络异常 (63)")
                        65 -> Log.w(TAG, "定位失败：定位缓存超时 (65)")
                        67 -> Log.w(TAG, "定位失败：离线定位失败 (67)")
                        else -> {
                            // locType=161 有时也是网络定位成功
                            if (lat != 0.0 && lng != 0.0) {
                                navViewModel?.updateLocation(LatLng(lat, lng))
                                Log.i(TAG, "定位成功 locType=$locType: ($lat, $lng)")
                            } else {
                                Log.w(TAG, "定位失败 locType=$locType")
                            }
                        }
                    }
                }
            })

            client.start()
            locationClient = client
            Log.i(TAG, "LocationClient started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "LocationClient failed: ${e.message}", e)
            locationClient = null
        }
    }
}
