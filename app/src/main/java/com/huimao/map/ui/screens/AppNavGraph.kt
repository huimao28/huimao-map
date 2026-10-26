package com.huimao.map.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.android.billingclient.api.ProductDetails
import com.huimao.map.billing.SponsorBillingManager
import com.huimao.map.billing.SponsorTier
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.platform.LocalConfiguration
import com.huimao.map.map.BaiduMapManager
import com.huimao.map.map.DrivingPolicyOption
import com.huimao.map.map.RouteMode
import com.huimao.map.map.BaiduMapView
import com.huimao.map.map.MapLayerType
import com.huimao.map.model.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import com.huimao.map.data.AppSettingsKeys
import com.huimao.map.data.dataStore
import kotlinx.coroutines.launch
import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.util.Calendar
import java.util.Locale

// ─────────────────────────────────────────────
// ViewModel（简化版，无 Hilt）
// ─────────────────────────────────────────────
class NavViewModel(app: Application) : AndroidViewModel(app) {
    private val _navState = MutableStateFlow(NavigationState())
    val navState: StateFlow<NavigationState> = _navState.asStateFlow()

    // ── TTS 语音播报 ──
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastSpokenInstruction = ""

    fun initTts(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ttsReady = (status == TextToSpeech.SUCCESS)
            if (ttsReady) {
                tts?.language = Locale.CHINESE
                tts?.setSpeechRate(1.0f)
                android.util.Log.i("NavViewModel", "TTS initialized")
            } else {
                android.util.Log.w("NavViewModel", "TTS init failed: $status")
            }
        }
    }

    fun speakInstruction(text: String) {
        if (!ttsReady || text.isEmpty() || text == lastSpokenInstruction) return
        lastSpokenInstruction = text
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav_instruction")
    }

    override fun onCleared() {
        super.onCleared()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    // ── 收藏夹数据（持久化到 DataStore，此处用 StateFlow 管理）──
    private val _favorites = MutableStateFlow(
        listOf(
            Place(uid = "home", name = "家", address = "", latLng = LatLng(0.0, 0.0)),
            Place(uid = "work", name = "公司", address = "", latLng = LatLng(0.0, 0.0))
        )
    )
    val favorites: StateFlow<List<Place>> = _favorites.asStateFlow()

    fun updateFavorite(uid: String, name: String, address: String) {
        val cleanAddress = address.trim()
        _favorites.value = _favorites.value.map { f ->
            if (f.uid == uid) f.copy(name = name, address = cleanAddress) else f
        }
        if (cleanAddress.isBlank()) return
        // 地址文字必须解析成真实坐标；旧实现只保存文字，导航时坐标永远是 0,0。
        viewModelScope.launch {
            runCatching {
                val loc = _currentLocation.value
                val results = if (loc != null) {
                    BaiduMapManager.searchPoi(cleanAddress, location = loc).ifEmpty {
                        BaiduMapManager.searchPoi(cleanAddress, city = "全国")
                    }
                } else BaiduMapManager.searchPoi(cleanAddress, city = "全国")
                results.firstOrNull()
            }.getOrNull()?.let { resolved ->
                _favorites.value = _favorites.value.map { f ->
                    if (f.uid == uid) f.copy(
                        name = name,
                        address = resolved.address.ifBlank { cleanAddress },
                        latLng = resolved.latLng
                    ) else f
                }
            }
        }
    }

    fun findNearestAndSelect(keyword: String) {
        val loc = _currentLocation.value ?: return
        if (loc.latitude == 0.0 || loc.longitude == 0.0 || !BaiduMapManager.isReady()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _isSearching.value = true
            try {
                val nearest = BaiduMapManager.searchPoi(keyword, location = loc).firstOrNull()
                if (nearest != null) selectDestination(nearest)
            } catch (e: Throwable) {
                android.util.Log.e("NavViewModel", "Nearby search failed: $keyword", e)
            } finally { _isSearching.value = false }
        }
    }

    fun getFavorite(uid: String): Place? = _favorites.value.firstOrNull { it.uid == uid }

    fun addFavorite(name: String, address: String) {
        val newList = _favorites.value.toMutableList()
        newList.add(Place(uid = "fav_${System.currentTimeMillis()}", name = name, address = address, latLng = LatLng(0.0, 0.0)))
        _favorites.value = newList
    }

    fun removeFavorite(uid: String) {
        _favorites.value = _favorites.value.filter { it.uid != uid }
    }

    private val _selectedDest = MutableStateFlow<Place?>(null)
    val selectedDest: StateFlow<Place?> = _selectedDest.asStateFlow()

    private val _routes = MutableStateFlow<List<RouteResult>>(emptyList())
    val routes: StateFlow<List<RouteResult>> = _routes.asStateFlow()

    // 当前定位（真实 GPS 更新后会覆盖）
    private val _currentLocation = MutableStateFlow<LatLng?>(null)
    val currentLocation: StateFlow<LatLng?> = _currentLocation.asStateFlow()

    // 搜索结果
    private val _searchResults = MutableStateFlow<List<Place>>(emptyList())
    val searchResults: StateFlow<List<Place>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    fun updateLocation(latLng: LatLng) {
        _currentLocation.value = latLng
    }

    // SDK 是否就绪状态（用于 UI 提示）
    private val _sdkReady = MutableStateFlow(false)
    val sdkReady: StateFlow<Boolean> = _sdkReady.asStateFlow()

    fun notifySdkReady() {
        _sdkReady.value = BaiduMapManager.isReady()
    }

    // 搜索任务：用于取消上一次搜索，防止并发崩溃
    private var searchJob: kotlinx.coroutines.Job? = null

    // 路线规划状态：出行方式 / 驾车策略 / 是否正在规划
    private val _routeMode = MutableStateFlow(RouteMode.DRIVING)
    val routeMode: StateFlow<RouteMode> = _routeMode.asStateFlow()
    private val _drivingPolicy = MutableStateFlow(DrivingPolicyOption.TIME_FIRST)
    val drivingPolicy: StateFlow<DrivingPolicyOption> = _drivingPolicy.asStateFlow()
    private val _isPlanning = MutableStateFlow(false)
    val isPlanning: StateFlow<Boolean> = _isPlanning.asStateFlow()

    // 搜索分页状态
    private val _hasMoreSearch = MutableStateFlow(false)
    val hasMoreSearch: StateFlow<Boolean> = _hasMoreSearch.asStateFlow()
    private var searchKeyword = ""
    private var searchCityHint = ""
    private var searchCityUsed = ""
    private var searchPage = 0
    private val searchPageSize = 10

    private val knownCities = listOf("北京", "上海", "天津", "重庆", "广州", "深圳", "长沙", "常德",
        "武汉", "成都", "杭州", "南京", "西安", "郑州", "济南", "青岛", "厦门", "福州",
        "昆明", "贵阳", "南宁", "海口", "三亚", "拉萨", "乌鲁木齐", "哈尔滨", "长春", "沈阳")

    /** 执行一次搜索（第 pageNum 页），返回结果与识别到的城市。 */
    private suspend fun runSearch(keyword: String, cityHint: String, pageNum: Int): Pair<List<Place>, String> {
        val loc = _currentLocation.value
        val text = keyword.trim()
        // 识别“北京故宫、常德市火车站”等跨城市查询。
        val detectedCity = cityHint.ifBlank { knownCities.firstOrNull { text.startsWith(it) } ?: "" }
        val cleanKeyword = if (detectedCity.isNotBlank()) {
            text.removePrefix(detectedCity).removePrefix("市").trim().ifBlank { text }
        } else text
        val results = if (detectedCity.isNotBlank()) {
            BaiduMapManager.searchPoi(cleanKeyword, city = detectedCity, pageNum = pageNum, pageCapacity = searchPageSize)
        } else if (loc != null) {
            val nearby = BaiduMapManager.searchPoi(cleanKeyword, location = loc, pageNum = pageNum, pageCapacity = searchPageSize)
            if (pageNum > 0 || nearby.size >= 5) nearby else {
                (nearby + BaiduMapManager.searchPoi(cleanKeyword, city = "全国", pageNum = 0, pageCapacity = searchPageSize))
                    .distinctBy { it.uid.ifBlank { "${it.name}-${it.latLng}" } }
            }
        } else {
            BaiduMapManager.searchPoi(cleanKeyword, city = "全国", pageNum = pageNum, pageCapacity = searchPageSize)
        }
        return results to detectedCity
    }

    /**
     * 调用百度地图 SDK 进行真实 POI 搜索
     * 每次调用前取消上一次搜索 Job，防止并发崩溃
     */
    fun searchPlaces(keyword: String, city: String = "") {
        if (keyword.length < 2) {
            _searchResults.value = emptyList()
            _hasMoreSearch.value = false
            return
        }
        // 刷新 SDK 状态
        _sdkReady.value = BaiduMapManager.isReady()
        // SDK 未就绪时不进行搜索，避免崩溃
        if (!_sdkReady.value) {
            _searchResults.value = emptyList()
            _isSearching.value = false
            _hasMoreSearch.value = false
            return
        }
        // 取消上一次搜索
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _isSearching.value = true
            try {
                val (results, usedCity) = runSearch(keyword, city, 0)
                searchKeyword = keyword
                searchCityHint = city
                searchCityUsed = usedCity
                searchPage = 0
                _searchResults.value = results
                _hasMoreSearch.value = results.size >= searchPageSize
            } catch (e: Exception) {
                android.util.Log.w("NavViewModel", "searchPlaces error: ${e.message}")
                _searchResults.value = emptyList()
                _hasMoreSearch.value = false
            } finally {
                _isSearching.value = false
            }
        }
    }

    /** 加载搜索结果下一页并追加。 */
    fun loadMoreSearch() {
        if (!_hasMoreSearch.value || _isSearching.value || searchKeyword.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _isSearching.value = true
            try {
                val nextPage = searchPage + 1
                val (results, _) = runSearch(searchKeyword, searchCityHint, nextPage)
                if (results.isNotEmpty()) {
                    searchPage = nextPage
                    _searchResults.value = (_searchResults.value + results)
                        .distinctBy { it.uid.ifBlank { "${it.name}-${it.latLng}" } }
                }
                _hasMoreSearch.value = results.size >= searchPageSize
            } catch (e: Exception) {
                android.util.Log.w("NavViewModel", "loadMoreSearch error: ${e.message}")
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun clearSearch() {
        _searchResults.value = emptyList()
    }

    fun selectDestination(place: Place) {
        // 先按 UID 拉一次详情，补全地址；失败则直接用搜索结果。
        viewModelScope.launch {
            val dest = if (place.uid.isNotBlank()) {
                try { BaiduMapManager.getPoiDetail(place.uid) ?: place } catch (_: Exception) { place }
            } else place
            _selectedDest.value = dest
            _routes.value = emptyList()
            // 驾车模式不走 RoutePlanSearch：预览页用导航 SDK 算路（白马地图式），
            // RoutePreviewScreen 的 LaunchedEffect 监听目的地/方式/策略触发 SDK 规划。
            if (_routeMode.value != RouteMode.DRIVING) planForDestination(dest)
        }
    }

    /** 预览页 SDK 算路结果写回（与 RoutePlanSearch 结果共用同一条 routes 流）。 */
    fun setRoutesFromSdk(routes: List<RouteResult>) {
        _routes.value = routes
    }

    /** 预览页 SDK 规划进行中状态。 */
    fun setPlanningState(planning: Boolean) {
        _isPlanning.value = planning
    }

    /** 公交算路用的城市：优先逆地理编码，其次搜索时识别的城市，最后从目的地地址前缀识别。 */
    private suspend fun transitCityFor(place: Place): String {
        val geoCity = try { BaiduMapManager.reverseGeoCity(place.latLng) } catch (_: Exception) { "" }
        if (geoCity.isNotBlank()) return geoCity.removeSuffix("市")
        if (searchCityUsed.isNotBlank()) return searchCityUsed
        val addr = place.address.trim()
        return knownCities.firstOrNull { addr.startsWith(it) } ?: ""
    }

    /** 按当前出行方式与驾车策略为目的地算路。 */
    fun planForDestination(place: Place) {
        viewModelScope.launch {
            val origin = _currentLocation.value
            if (origin == null || origin.latitude == 0.0 || origin.longitude == 0.0) {
                android.util.Log.w("NavViewModel", "Cannot plan route before location is ready")
                return@launch
            }
            _isPlanning.value = true
            try {
                // 使用百度地图 SDK 返回真实道路路线；不再生成直线距离估算路线。
                _routes.value = BaiduMapManager.planRoutes(
                    origin, place, _routeMode.value, _drivingPolicy.value, transitCityFor(place)
                )
            } catch (e: Throwable) {
                android.util.Log.e("NavViewModel", "Baidu route planning failed", e)
                _routes.value = emptyList()
            } finally {
                _isPlanning.value = false
            }
        }
    }

    /** 切换出行方式并重新算路。驾车切回时清空旧结果，等预览页 SDK 规划接手。 */
    fun setRouteMode(mode: RouteMode) {
        if (_routeMode.value == mode) return
        _routeMode.value = mode
        if (mode == RouteMode.DRIVING) {
            _routes.value = emptyList()
        } else {
            _selectedDest.value?.let { planForDestination(it) }
        }
    }

    /** 切换驾车策略并重新算路（仅驾车模式有效，SDK 规划由预览页 effect 触发）。 */
    fun setDrivingPolicy(policy: DrivingPolicyOption) {
        if (_drivingPolicy.value == policy) return
        _drivingPolicy.value = policy
        if (_routeMode.value == RouteMode.DRIVING) {
            _routes.value = emptyList()
        }
    }

    fun startNavigation(route: RouteResult) {
        _navState.value = NavigationState(
            isNavigating = true,
            destination = route.destination,
            currentStep = route.steps.firstOrNull(),
            nextStep = route.steps.getOrNull(1),
            distanceToNextTurn = route.steps.firstOrNull()?.distance ?: 0.0,
            remainingDistance = route.totalDistance,
            remainingDuration = route.totalDuration,
            eta = System.currentTimeMillis() + route.totalDuration * 1000,
            currentSpeed = 0f,
            speedLimit = 80f
        )
    }

    fun stopNavigation() {
        _navState.value = NavigationState()
        _selectedDest.value = null
        _routes.value = emptyList()
    }

    /**
     * 基于坐标计算估算路线（直线距离 * 系数）
     * 真实导航 SDK 集成后可替换为百度导航路线规划接口
     */
    private fun buildEstimatedRoutes(dest: Place): List<RouteResult> {
        val origin = _currentLocation.value
            ?.let { Place(name = "当前位置", latLng = it) }
            ?: Place(name = "当前位置", latLng = LatLng(0.0, 0.0))

        // 计算直线距离（米）
        val dLat = dest.latLng.latitude - origin.latLng.latitude
        val dLon = dest.latLng.longitude - origin.latLng.longitude
        val straightDist = Math.sqrt(dLat * dLat + dLon * dLon) * 111000.0
        val roadDist1 = straightDist * 1.35  // 推荐路线系数
        val roadDist2 = straightDist * 1.15  // 最短路线系数
        val dur1 = (roadDist1 / 40.0 * 3.6).toLong()   // 假设平均 40km/h
        val dur2 = (roadDist2 / 50.0 * 3.6).toLong()

        return listOf(
            RouteResult(
                routeId = "r1", origin = origin, destination = dest,
                steps = listOf(
                    RouteStep("前往 ${dest.name}", roadDist1, dur1, ManeuverType.STRAIGHT, ""),
                    RouteStep("到达目的地", 0.0, 0, ManeuverType.ARRIVE, "")
                ),
                totalDistance = roadDist1, totalDuration = dur1,
                trafficCondition = TrafficCondition.SMOOTH, routeType = RouteType.RECOMMENDED
            ),
            RouteResult(
                routeId = "r2", origin = origin, destination = dest,
                steps = listOf(
                    RouteStep("最短路径前往 ${dest.name}", roadDist2, dur2, ManeuverType.STRAIGHT, ""),
                    RouteStep("到达目的地", 0.0, 0, ManeuverType.ARRIVE, "")
                ),
                totalDistance = roadDist2, totalDuration = dur2,
                trafficCondition = TrafficCondition.UNKNOWN, routeType = RouteType.SHORTEST
            )
        )
    }
}

// ─────────────────────────────────────────────
// 路由
// ─────────────────────────────────────────────
object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val ROUTE_PREVIEW = "route_preview"
    const val NAVIGATION = "navigation"
    const val SETTINGS = "settings"
    const val FAVORITES = "favorites"
    const val ABOUT = "about"
    const val SEARCH_QUERY = "search/{query}"
    fun search(query: String) = "search/${android.net.Uri.encode(query)}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavGraph(
    navController: NavHostController = rememberNavController(),
    vm: NavViewModel = viewModel(factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(
        androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application
    ))
) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        enterTransition = { slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(280)) + fadeIn(tween(280)) },
        exitTransition = { slideOutHorizontally(targetOffsetX = { -it / 3 }, animationSpec = tween(280)) + fadeOut(tween(200)) },
        popEnterTransition = { slideInHorizontally(initialOffsetX = { -it / 3 }, animationSpec = tween(280)) + fadeIn(tween(280)) },
        popExitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(280)) + fadeOut(tween(200)) }
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                vm = vm,
                onSearchClick = { navController.navigate(Routes.SEARCH) },
                onCategorySearch = { keyword -> navController.navigate(Routes.search(keyword)) },
                onRoutePreview = { navController.navigate(Routes.ROUTE_PREVIEW) },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                onFavoritesClick = { navController.navigate(Routes.FAVORITES) }
            )
        }
        composable(Routes.SEARCH) {
            SearchScreen(
                vm = vm,
                initialQuery = "",
                onBack = { navController.popBackStack() },
                onPlaceSelected = { navController.navigate(Routes.ROUTE_PREVIEW) }
            )
        }
        composable(Routes.SEARCH_QUERY) { entry ->
            SearchScreen(
                vm = vm,
                initialQuery = android.net.Uri.decode(entry.arguments?.getString("query").orEmpty()),
                onBack = { navController.popBackStack() },
                onPlaceSelected = { navController.navigate(Routes.ROUTE_PREVIEW) }
            )
        }
        composable(Routes.ROUTE_PREVIEW) {
            RoutePreviewScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onStartNavigation = {
                    navController.navigate(Routes.NAVIGATION) {
                        popUpTo(Routes.HOME)
                    }
                }
            )
        }
        composable(Routes.NAVIGATION) {
            NavigationScreen(
                vm = vm,
                onStopNavigation = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onAboutClick = { navController.navigate(Routes.ABOUT) }
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.FAVORITES) {
            FavoritesScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onPlaceSelected = { navController.navigate(Routes.ROUTE_PREVIEW) }
            )
        }
    }
}

// ─────────────────────────────────────────────
// 百度地图视图（真实 SDK）
// ─────────────────────────────────────────────
@Composable
fun MapPlaceholder(
    modifier: Modifier = Modifier,
    currentLocation: LatLng? = null,
    destination: Place? = null,
    route: RouteResult? = null,
    mapLayerType: MapLayerType = MapLayerType.NORMAL
) {
    BaiduMapView(
        modifier = modifier,
        currentLocation = currentLocation,
        destination = destination,
        route = route,
        layerType = mapLayerType
    )
}

// ─────────────────────────────────────────────
// HomeScreen
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: NavViewModel,
    onSearchClick: () -> Unit,
    onCategorySearch: (String) -> Unit,
    onRoutePreview: () -> Unit,
    onSettingsClick: () -> Unit,
    onFavoritesClick: () -> Unit
) {
    val currentLocation by vm.currentLocation.collectAsState()
    val selectedDest by vm.selectedDest.collectAsState()

    // 图层类型状态：默认普通图，可手动切换
    val configuration = LocalConfiguration.current
    val isDarkMode = configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    // 深色模式下默认展示卫星图，浅色模式下默认普通图
    var mapLayerType by remember(isDarkMode) {
        mutableStateOf(if (isDarkMode) MapLayerType.SATELLITE else MapLayerType.NORMAL)
    }

    // BottomSheet 状态：skipHiddenState=true 防止完全隐藏后无法拉出
    val sheetState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded,
            skipHiddenState = true
        )
    )

    BottomSheetScaffold(
        scaffoldState = sheetState,
        sheetPeekHeight = 200.dp,
        sheetDragHandle = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .width(32.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(0.4f))
                )
            }
        },
        sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        sheetTonalElevation = 4.dp,
        sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        sheetContent = {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 16.dp)) {
                Text("快速前往", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        QuickCard(Icons.Default.Home, "家", "收藏地址",
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.onPrimaryContainer) {
                            val home = vm.getFavorite("home")
                            if (home != null && home.latLng.latitude != 0.0 && home.latLng.longitude != 0.0) {
                                vm.selectDestination(home); onRoutePreview()
                            } else onFavoritesClick()
                        }
                    }
                    item {
                        QuickCard(Icons.Default.Work, "公司", "收藏地址",
                            MaterialTheme.colorScheme.secondaryContainer,
                            MaterialTheme.colorScheme.onSecondaryContainer) {
                            val work = vm.getFavorite("work")
                            if (work != null && work.latLng.latitude != 0.0 && work.latLng.longitude != 0.0) {
                                vm.selectDestination(work); onRoutePreview()
                            } else onFavoritesClick()
                        }
                    }
                    item {
                        QuickCard(Icons.Default.LocalGasStation, "加油站", "周边最近",
                            MaterialTheme.colorScheme.tertiaryContainer,
                            MaterialTheme.colorScheme.onTertiaryContainer) { onCategorySearch("加油站") }
                    }
                    item {
                        QuickCard(Icons.Default.LocalParking, "停车场", "周边最近",
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            MaterialTheme.colorScheme.onSurface) { onCategorySearch("停车场") }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("周边发现", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val cats = listOf(
                        "餐厅" to Icons.Default.Restaurant,
                        "咖啡" to Icons.Default.LocalCafe,
                        "超市" to Icons.Default.ShoppingCart,
                        "医院" to Icons.Default.LocalHospital,
                        "酒店" to Icons.Default.Hotel,
                        "银行" to Icons.Default.AccountBalance
                    )
                    items(cats) { (label, icon) ->
                        SuggestionChip(
                            onClick = { onCategorySearch(label) },
                            label = { Text(label) },
                            icon = { Icon(icon, null, Modifier.size(18.dp)) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(bottom = innerPadding.calculateBottomPadding())
        ) {
            MapPlaceholder(
                modifier = Modifier.fillMaxSize(),
                currentLocation = currentLocation,
                destination = selectedDest,
                mapLayerType = mapLayerType
            )

            // 顶部搜索栏
            Column(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SearchBar(
                    query = "",
                    onQueryChange = {},
                    onSearch = { onSearchClick() },
                    active = false,
                    onActiveChange = { if (it) onSearchClick() },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索目的地、地址或地点") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        Row {
                            IconButton(onClick = onFavoritesClick) { Icon(Icons.Outlined.FavoriteBorder, null) }
                            IconButton(onClick = onSettingsClick) { Icon(Icons.Default.Settings, null) }
                        }
                    },
                    colors = SearchBarDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    tonalElevation = 6.dp
                ) {}
            }

            // 右侧按钮组：定位 + 图层切换
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = (200 + 16).dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 图层切换按钮
                SmallFloatingActionButton(
                    onClick = {
                        mapLayerType = if (mapLayerType == MapLayerType.NORMAL)
                            MapLayerType.SATELLITE else MapLayerType.NORMAL
                    },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.secondary
                ) {
                    Icon(
                        if (mapLayerType == MapLayerType.SATELLITE)
                            Icons.Default.Map else Icons.Default.Satellite,
                        contentDescription = "切换图层"
                    )
                }
                // 定位按钮
                SmallFloatingActionButton(
                    onClick = {},
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.primary
                ) { Icon(Icons.Default.MyLocation, null) }
            }
        }
    }
}

@Composable
fun QuickCard(icon: ImageVector, label: String, sub: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = bg, modifier = Modifier.width(120.dp)) {
        Column(Modifier.padding(16.dp)) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(12.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = fg)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = fg.copy(0.7f))
        }
    }
}

// ─────────────────────────────────────────────
// SearchScreen
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(vm: NavViewModel, initialQuery: String = "", onBack: () -> Unit, onPlaceSelected: () -> Unit) {
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    val searchResults by vm.searchResults.collectAsState()
    val isSearching by vm.isSearching.collectAsState()
    val hasMoreSearch by vm.hasMoreSearch.collectAsState()

    // 输入变化时触发真实搜索（300ms debounce 防止快速输入并发崩溃）
    LaunchedEffect(query) {
        if (query.length >= 2) {
            delay(300L)  // debounce
            vm.searchPlaces(query)
        } else {
            vm.clearSearch()
        }
    }

    SearchBar(
        query = query, onQueryChange = { query = it },
        onSearch = {},
        active = true, onActiveChange = { if (!it) onBack() },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("搜索目的地、地址或地点") },
        leadingIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, null) }
        }
    ) {
        LazyColumn(Modifier.fillMaxSize()) {
            if (isSearching && searchResults.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (searchResults.isEmpty() && query.isEmpty()) {
                item {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Search, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.4f))
                        Spacer(Modifier.height(8.dp))
                        Text("输入地点名称或地址进行搜索", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (searchResults.isEmpty() && query.length >= 2 && !isSearching) {
                item {
                    val sdkReady by vm.sdkReady.collectAsState()
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.SearchOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.4f))
                        Spacer(Modifier.height(8.dp))
                        if (!sdkReady) {
                            Text("百度地图 SDK 未初始化", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(4.dp))
                            Text("请前往「设置 → 百度地图 API Key」配置 AK", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            Spacer(Modifier.height(4.dp))
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.errorContainer
                            ) {
                                Text(
                                    "注意：在百度平台注册 AK 时，必须使用 Release SHA1\nDE:FF:00:C2:E1:8A:20:62:9C:4A:17:67:B7:27:A5:08:CC:1B:F0:E4",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(8.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
                        } else {
                            Text("未找到相关地点", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("请尝试其他关键词", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.6f))
                        }
                    }
                }
            }
            items(searchResults) { place ->
                ListItem(
                    headlineContent = { Text(place.name, fontWeight = FontWeight.Medium) },
                    supportingContent = {
                        Text(place.address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    leadingContent = {
                        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(48.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Place, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                        }
                    },
                    trailingContent = { Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    modifier = Modifier.clickable {
                        vm.selectDestination(place)
                        onPlaceSelected()
                    }
                )
            }
            if (searchResults.isNotEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        if (isSearching) {
                            CircularProgressIndicator()
                        } else if (hasMoreSearch) {
                            OutlinedButton(onClick = { vm.loadMoreSearch() }) {
                                Text("加载更多")
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// 出行方式选择（分段按钮；ButtonGroup 在 material3 1.3.2 中不存在，见 CI 全量日志）
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteModeButtonGroup(selected: RouteMode, onSelect: (RouteMode) -> Unit, modifier: Modifier = Modifier) {
    val modes = RouteMode.entries
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                icon = {
                    SegmentedButtonDefaults.Icon(active = mode == selected) {
                        Icon(
                            when (mode) {
                                RouteMode.DRIVING -> Icons.Default.DirectionsCar
                                RouteMode.WALKING -> Icons.Default.DirectionsWalk
                                RouteMode.BIKING -> Icons.Default.DirectionsBike
                                RouteMode.TRANSIT -> Icons.Default.DirectionsBus
                            },
                            contentDescription = null
                        )
                    }
                },
                label = { Text(mode.label) }
            )
        }
    }
}

// ─────────────────────────────────────────────
// RoutePreviewScreen
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutePreviewScreen(vm: NavViewModel, onBack: () -> Unit, onStartNavigation: () -> Unit) {
    val dest by vm.selectedDest.collectAsState()
    val routes by vm.routes.collectAsState()
    var selIdx by remember { mutableIntStateOf(0) }
    var showPlanningMap by remember { mutableStateOf(true) }
    var pendingNaviLaunch by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentLocation by vm.currentLocation.collectAsState()
    val routeMode by vm.routeMode.collectAsState()
    val drivingPolicy by vm.drivingPolicy.collectAsState()
    val isPlanning by vm.isPlanning.collectAsState()

    // NaviActivity 返回时重新创建普通百度规划 MapView。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !pendingNaviLaunch) showPlanningMap = true
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 驾车模式：白马地图式 SDK 算路。目的地/出行方式/驾车策略任一变化都重新算路；
    // 算路结果直接写回 vm.routes，预览页读 SDK tab（不再用 RoutePlanSearch 另算一套）。
    // effect 取消或重启时取消上一轮未完成的算路，避免旧结果覆盖新目的地。
    LaunchedEffect(dest, routeMode, drivingPolicy) {
        if (routeMode != RouteMode.DRIVING) return@LaunchedEffect
        val d = dest ?: return@LaunchedEffect
        val origin = currentLocation ?: return@LaunchedEffect
        if (origin.latitude == 0.0 || origin.longitude == 0.0) return@LaunchedEffect
        vm.setPlanningState(true)
        vm.setRoutesFromSdk(emptyList())
        val ak = try {
            context.applicationContext.dataStore.data
                .map { it[AppSettingsKeys.BAIDU_API_KEY] ?: "" }
                .first()
        } catch (_: Exception) { "" }
        if (ak.isBlank()) {
            vm.setPlanningState(false)
            return@LaunchedEffect
        }
        val activity = context as? android.app.Activity ?: run {
            vm.setPlanningState(false)
            return@LaunchedEffect
        }
        val handler = com.huimao.map.navigation.SdkRoutePlanner.plan(
            activity, ak, origin, d,
            com.huimao.map.ui.NaviActivity.preferenceFor(drivingPolicy),
            onResult = { results ->
                // 只有当前目的地仍是本轮算路的目的地时才写回，防止旧结果覆盖。
                if (vm.selectedDest.value?.latLng == d.latLng) {
                    vm.setRoutesFromSdk(results)
                    if (results.isNotEmpty()) selIdx = selIdx.coerceIn(0, results.size - 1)
                }
                vm.setPlanningState(false)
            },
            onError = {
                if (vm.selectedDest.value?.latLng == d.latLng) vm.setRoutesFromSdk(emptyList())
                vm.setPlanningState(false)
            }
        )
        try {
            awaitCancellation()
        } finally {
            com.huimao.map.navigation.SdkRoutePlanner.cancel(handler)
        }
    }

    // 先让 Compose 真正 dispose MapView（onPause + onDestroy），下一帧再启动导航。
    LaunchedEffect(pendingNaviLaunch) {
        if (!pendingNaviLaunch) return@LaunchedEffect
        delay(250)
        val d = dest
        if (d != null && d.latLng.latitude != 0.0 && d.latLng.longitude != 0.0) {
            com.huimao.map.ui.NaviActivity.start(
                context, d.latLng.latitude, d.latLng.longitude, d.name,
                currentLocation?.latitude ?: 0.0, currentLocation?.longitude ?: 0.0,
                routeIndex = selIdx,
                routePref = com.huimao.map.ui.NaviActivity.preferenceFor(drivingPolicy),
                alreadyPlanned = true
            )
        }
        pendingNaviLaunch = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(dest?.name ?: "路线预览", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }
            )
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).background(MaterialTheme.colorScheme.background)) {
            if (showPlanningMap) {
                MapPlaceholder(
                    modifier = Modifier.fillMaxSize(),
                    currentLocation = currentLocation,
                    destination = dest,
                    route = routes.getOrNull(selIdx)
                )
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 4.dp
                ) {
                    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp).navigationBarsPadding().padding(bottom = 20.dp)) {
                        if (routes.isEmpty()) {
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                if (isPlanning) {
                                    CircularProgressIndicator()
                                } else {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("暂无方案", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (routeMode == RouteMode.TRANSIT) {
                                            Spacer(Modifier.height(4.dp))
                                            Text("公交算路需要城市信息，搜索时带上城市名试试", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.7f))
                                        }
                                    }
                                }
                            }
                        } else {
                            val route = routes.getOrNull(selIdx) ?: routes[0]
                            // 路线摘要
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Row(verticalAlignment = Alignment.Bottom) {
                                        Text(fmtDur(route.totalDuration), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.width(8.dp))
                                        Text(fmtDist(route.totalDistance), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                                    }
                                    // SDK 方案不带路况角标（tab 只有时间/距离/收费），只给旧 RoutePlanSearch 驾车方案显示。
                                    if (routeMode == RouteMode.DRIVING && route.trafficCondition != TrafficCondition.UNKNOWN) {
                                        val tc = route.trafficCondition
                                        val tcColor = when(tc) { TrafficCondition.SMOOTH -> Color(0xFF4CAF50); TrafficCondition.SLOW -> Color(0xFFFF9800); else -> Color(0xFFF44336) }
                                        val tcText = when(tc) { TrafficCondition.SMOOTH -> "畅通"; TrafficCondition.SLOW -> "缓慢"; else -> "拥堵" }
                                        Surface(shape = MaterialTheme.shapes.extraSmall, color = tcColor.copy(0.15f)) {
                                            Text(tcText, style = MaterialTheme.typography.labelMedium, color = tcColor, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                                        }
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("预计到达", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    val cal = Calendar.getInstance().apply { timeInMillis = System.currentTimeMillis() + route.totalDuration * 1000 }
                                    Text(String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            // 出行方式（分段按钮）
                            RouteModeButtonGroup(
                                selected = routeMode,
                                onSelect = { vm.setRouteMode(it) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (routeMode == RouteMode.DRIVING) {
                                Spacer(Modifier.height(8.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    DrivingPolicyOption.entries.forEach { policy ->
                                        FilterChip(
                                            selected = policy == drivingPolicy,
                                            onClick = { vm.setDrivingPolicy(policy) },
                                            label = { Text(policy.label) }
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            // 步骤展开
                            var showAllSteps by remember(route) { mutableStateOf(false) }
                            val steps = route.steps
                            val visibleSteps = if (showAllSteps) steps else steps.take(3)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                visibleSteps.forEach { step ->
                                    Row(Modifier.fillMaxWidth()) {
                                        Text(step.instruction, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                        if (step.distance > 0) {
                                            Spacer(Modifier.width(8.dp))
                                            Text(fmtDist(step.distance), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                                if (steps.size > 3) {
                                    TextButton(onClick = { showAllSteps = !showAllSteps }) {
                                        Text(if (showAllSteps) "收起步骤" else "展开全部 ${steps.size} 步")
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            // 路线选择
                            if (routes.size > 1) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    routes.forEachIndexed { i, r ->
                                        FilterChip(
                                            selected = i == selIdx,
                                            onClick = {
                                                selIdx = i
                                                // SDK 算路的方案：点选即调 SDK 选路（白马地图同款）。
                                                if (r.routeId.startsWith("sdk-")) {
                                                    com.huimao.map.navigation.SdkRoutePlanner.selectRoute(i)
                                                }
                                            },
                                            label = { Text("${fmtDur(r.totalDuration)} · ${fmtDist(r.totalDistance)}", style = MaterialTheme.typography.labelSmall) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                            }
                            if (routeMode == RouteMode.DRIVING) {
                                ExtendedFloatingActionButton(
                                    onClick = {
                                        val d = dest
                                        if (d != null && d.latLng.latitude != 0.0 && d.latLng.longitude != 0.0) {
                                            // 规划页已有百度地图 SDK 返回的真实道路几何，直接预载给 Android Auto。
                                            // 不依赖导航 SDK 的 routeInfoLatLngLists（该版本可能始终返回空）。
                                            val path = route.pathPoints
                                            val maxPoints = 1200
                                            val stride = (path.size / maxPoints).coerceAtLeast(1)
                                            val sampled = path.filterIndexed { index, _ -> index % stride == 0 }
                                                .take(maxPoints).map { it.latitude to it.longitude }.toMutableList()
                                            path.lastOrNull()?.let { end ->
                                                val pair = end.latitude to end.longitude
                                                if (sampled.lastOrNull() != pair) sampled.add(pair)
                                            }
                                            com.huimao.map.navigation.CarNavigationBridge.setRoutePoints(sampled)
                                            showPlanningMap = false
                                            pendingNaviLaunch = true
                                        } else {
                                            vm.startNavigation(route)
                                            onStartNavigation()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                    icon = { Icon(Icons.Default.Navigation, null) },
                                    text = { Text("开始导航", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                                )
                            } else {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "已展示${routeMode.label}路线方案，逐步导航目前仅支持驾车",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// NavigationScreen
// ─────────────────────────────────────────────
@Composable
fun NavigationScreen(vm: NavViewModel, onStopNavigation: () -> Unit) {
    val state by vm.navState.collectAsState()
    var showStop by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // 初始化 TTS
    LaunchedEffect(Unit) {
        vm.initTts(context)
    }

    // 监听导航步骤变化，自动播报语音
    LaunchedEffect(state.currentStep) {
        val step = state.currentStep ?: return@LaunchedEffect
        if (state.isNavigating) {
            vm.speakInstruction(step.instruction)
        }
    }

    if (showStop) {
        AlertDialog(
            onDismissRequest = { showStop = false },
            title = { Text("停止导航") },
            text = { Text("确定要停止当前导航吗？") },
            confirmButton = { TextButton(onClick = { showStop = false; vm.stopNavigation(); onStopNavigation() }) { Text("停止", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showStop = false }) { Text("继续") } }
        )
    }

    val currentLocation by vm.currentLocation.collectAsState()

    Box(Modifier.fillMaxSize()) {
        MapPlaceholder(
            modifier = Modifier.fillMaxSize(),
            currentLocation = currentLocation,
            destination = state.destination
        )

        // 转向指令卡片
        if (state.isNavigating) {
            state.currentStep?.let { step ->
                Surface(
                    Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(16.dp).fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    tonalElevation = 8.dp
                ) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(maneuverIcon(step.maneuver), null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(32.dp))
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(fmtDist(state.distanceToNextTurn), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 32.sp)
                            Text(step.instruction, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(0.8f))
                            if (step.roadName.isNotEmpty()) Text(step.roadName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(0.6f))
                        }
                    }
                }
            }
        }

        // 速度显示
        if (state.currentSpeed > 0) {
            val isOver = state.speedLimit > 0 && state.currentSpeed > state.speedLimit * 1.1f
            Surface(
                Modifier.align(Alignment.CenterStart).padding(start = 16.dp),
                shape = MaterialTheme.shapes.medium,
                color = if (isOver) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 4.dp
            ) {
                Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${state.currentSpeed.toInt()}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = if (isOver) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface)
                    Text("km/h", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.speedLimit > 0) {
                        Spacer(Modifier.height(4.dp))
                        Surface(shape = MaterialTheme.shapes.extraSmall, color = if (isOver) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline.copy(0.3f)) {
                            Text("${state.speedLimit.toInt()}", style = MaterialTheme.typography.labelSmall, color = if (isOver) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
            }
        }

        // 底部信息面板
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()) {
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 4.dp
            ) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 20.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TripItem("剩余时间", fmtDur(state.remainingDuration))
                        VerticalDivider(Modifier.height(40.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        TripItem("剩余距离", fmtDist(state.remainingDistance))
                        VerticalDivider(Modifier.height(40.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        TripItem("预计到达", fmtEta(state.eta))
                    }
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { showStop = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Icon(Icons.Default.Close, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("停止导航", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        // 到达提示
        AnimatedVisibility(visible = state.isNavigating && state.currentStep?.maneuver == ManeuverType.ARRIVE, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut(), modifier = Modifier.align(Alignment.Center)) {
            Surface(Modifier.fillMaxWidth().padding(32.dp), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer, tonalElevation = 12.dp) {
                Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CheckCircle, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text("已到达目的地", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    state.destination?.name?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(0.7f)) }
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { vm.stopNavigation(); onStopNavigation() }, Modifier.fillMaxWidth()) { Text("结束导航") }
                }
            }
        }
    }
}

@Composable
fun TripItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 8.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ─────────────────────────────────────────────
// SettingsScreen
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onAboutClick: () -> Unit) {
    val settingsVm: SettingsViewModel = viewModel(
        factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(
            androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application
        )
    )

    // 从 DataStore 持久化读取状态
    val savedApiKey by settingsVm.baiduApiKey.collectAsState()
    val voice by settingsVm.voiceEnabled.collectAsState()
    val ttsProvider by settingsVm.ttsProvider.collectAsState()
    val baiduTtsAppId by settingsVm.baiduTtsAppId.collectAsState()
    val baiduTtsApiKey by settingsVm.baiduTtsApiKey.collectAsState()
    val baiduTtsSecretKey by settingsVm.baiduTtsSecretKey.collectAsState()
    val traffic by settingsVm.trafficEnabled.collectAsState()
    val carDiag by settingsVm.carDiagEnabled.collectAsState()
    val routeType by settingsVm.routeType.collectAsState()

    var showApiKeyDialog by remember { mutableStateOf(false) }
    var showBaiduTtsDialog by remember { mutableStateOf(false) }
    var apiKeyInput by remember(savedApiKey) { mutableStateOf(savedApiKey) }
    var ttsAppIdInput by remember(baiduTtsAppId) { mutableStateOf(baiduTtsAppId) }
    var ttsApiKeyInput by remember(baiduTtsApiKey) { mutableStateOf(baiduTtsApiKey) }
    var ttsSecretInput by remember(baiduTtsSecretKey) { mutableStateOf(baiduTtsSecretKey) }

    // API Key 输入对话框
    if (showApiKeyDialog) {
        AlertDialog(
            onDismissRequest = { showApiKeyDialog = false },
            icon = { Icon(Icons.Default.Key, null) },
            title = { Text("配置百度地图 API Key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "请在百度地图开放平台申请 Android SDK 的 AK，并填入下方。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        label = { Text("API Key (AK)") },
                        placeholder = { Text("粘贴您的百度地图 AK") },
                        leadingIcon = { Icon(Icons.Default.VpnKey, null) },
                        trailingIcon = {
                            if (apiKeyInput.isNotEmpty()) {
                                IconButton(onClick = { apiKeyInput = "" }) {
                                    Icon(Icons.Default.Clear, null)
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium
                    )
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Info, null,
                                    Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    "在百度平台注册 AK 时请填写以下信息：",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                            Text(
                                "包名：com.huimao.map",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                            Text(
                                "GitHub 版 SHA1：DE:FF:00:C2:E1:8A:20:62:9C:4A:17:67:B7:27:A5:08:CC:1B:F0:E4",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                            Text(
                                "Google Play 版 SHA1：F6:4B:0C:D1:6D:AF:A3:51:09:BF:7F:9D:21:F3:81:E2:48:FC:DA:5B",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                "注意：Google Play 版必须使用 App signing certificate，不是 Upload key certificate。两种 SHA1 都加入同一个百度 AK 的安全码。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            },
            confirmButton = {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                Button(
                    onClick = {
                        if (apiKeyInput.isNotBlank()) {
                            settingsVm.saveBaiduApiKey(apiKeyInput)
                            // 保存后立即触发 SDK 初始化，无需等待协程 collect
                            com.huimao.map.map.BaiduMapManager.initialize(
                                ctx.applicationContext, apiKeyInput.trim()
                            )
                        }
                        showApiKeyDialog = false
                    }
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showApiKeyDialog = false }) { Text("取消") }
            }
        )
    }

    if (showBaiduTtsDialog) {
        AlertDialog(
            onDismissRequest = { showBaiduTtsDialog = false },
            title = { Text("百度语音配置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("请填写百度智能云语音合成应用凭据。凭据仅保存在本机。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(ttsAppIdInput, { ttsAppIdInput = it }, label = { Text("App ID") }, singleLine = true)
                    OutlinedTextField(ttsApiKeyInput, { ttsApiKeyInput = it }, label = { Text("API Key") }, singleLine = true)
                    OutlinedTextField(
                        ttsSecretInput, { ttsSecretInput = it }, label = { Text("Secret Key") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                    )
                    Text("未配置或百度语音初始化失败时，将自动回退系统 TTS。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                Button(onClick = {
                    settingsVm.saveBaiduTtsCredentials(ttsAppIdInput, ttsApiKeyInput, ttsSecretInput)
                    settingsVm.saveTtsProvider("baidu")
                    showBaiduTtsDialog = false
                }) { Text("保存并使用百度语音") }
            },
            dismissButton = { TextButton(onClick = { showBaiduTtsDialog = false }) { Text("取消") } }
        )
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("设置", fontWeight = FontWeight.SemiBold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }
        )
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("导航设置", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) }
            item {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column {
                        ListItem(headlineContent = { Text("语音播报") }, supportingContent = { Text("开启导航语音提示；播报时自动压低音乐") }, leadingContent = { Icon(Icons.Default.VolumeUp, null) }, trailingContent = { Switch(voice, { settingsVm.saveVoiceEnabled(it) }) })
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        ListItem(
                            headlineContent = { Text("语音引擎") },
                            supportingContent = { Text(if (ttsProvider == "baidu") "百度 SDK TTS" else "Android 系统 TTS") },
                            leadingContent = { Icon(Icons.Default.RecordVoiceOver, null) },
                            trailingContent = {
                                Row {
                                    FilterChip(
                                        selected = ttsProvider == "system",
                                        onClick = { settingsVm.saveTtsProvider("system") },
                                        label = { Text("系统") }
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    FilterChip(
                                        selected = ttsProvider == "baidu",
                                        onClick = {
                                            if (baiduTtsAppId.isBlank() || baiduTtsApiKey.isBlank() || baiduTtsSecretKey.isBlank()) showBaiduTtsDialog = true
                                            else settingsVm.saveTtsProvider("baidu")
                                        },
                                        label = { Text("百度") }
                                    )
                                }
                            }
                        )
                        if (ttsProvider == "baidu") {
                            ListItem(
                                headlineContent = { Text("百度语音凭据") },
                                supportingContent = { Text(if (baiduTtsAppId.isBlank()) "尚未配置" else "App ID：$baiduTtsAppId") },
                                leadingContent = { Icon(Icons.Default.Key, null) },
                                trailingContent = { TextButton(onClick = { showBaiduTtsDialog = true }) { Text("配置") } }
                            )
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        ListItem(headlineContent = { Text("实时路况") }, supportingContent = { Text("在地图上显示实时交通信息") }, leadingContent = { Icon(Icons.Default.Traffic, null) }, trailingContent = { Switch(traffic, { settingsVm.saveTrafficEnabled(it) }) })
                    }
                }
            }
            item { Text("路线偏好", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) }
            item {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(16.dp)) {
                        Text("默认路线类型", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(12.dp))
                        val types = listOf("推荐", "最快", "避高速", "避收费")
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            types.forEachIndexed { i, t ->
                                SegmentedButton(selected = routeType == i, onClick = { settingsVm.saveRouteType(i) }, shape = SegmentedButtonDefaults.itemShape(i, types.size)) {
                                    Text(t, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
            item { Text("Android Auto", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) }
            item {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column {
                        ListItem(
                            headlineContent = { Text("Android Auto 状态") },
                            supportingContent = { Text("已启用 · 连接车载系统后自动激活") },
                            leadingContent = { Icon(Icons.Default.DirectionsCar, null) },
                            trailingContent = {
                                Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.primaryContainer) {
                                    Text("已就绪", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                }
                            }
                        )
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        ListItem(
                            headlineContent = { Text("百度地图 API Key") },
                            supportingContent = {
                                Text(
                                    if (savedApiKey.isNotEmpty())
                                        "已配置：${savedApiKey.take(8)}…"
                                    else
                                        "点击配置您的百度地图 API Key",
                                    color = if (savedApiKey.isNotEmpty())
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            leadingContent = { Icon(Icons.Default.Key, null) },
                            trailingContent = {
                                Icon(
                                    if (savedApiKey.isNotEmpty()) Icons.Default.CheckCircle else Icons.Default.ChevronRight,
                                    null,
                                    tint = if (savedApiKey.isNotEmpty())
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            modifier = Modifier.clickable { showApiKeyDialog = true }
                        )
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        ListItem(headlineContent = { Text("车机诊断信息") }, supportingContent = { Text("在车机导航画面顶部显示截帧状态（调试用）") }, leadingContent = { Icon(Icons.Default.BugReport, null) }, trailingContent = { Switch(carDiag, { settingsVm.setCarDiagEnabled(it) }) })
                    }
                }
            }
            item { Text("关于", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) }
            item {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column {
                        ListItem(
                            headlineContent = { Text("关于灰猫地图") },
                            supportingContent = { Text("作者信息 · 开源库鸣谢 · 相关链接") },
                            leadingContent = { Icon(Icons.Default.Person, null) },
                            trailingContent = { Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                            modifier = Modifier.clickable { onAboutClick() }
                        )
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        ListItem(headlineContent = { Text("版本") }, supportingContent = { Text("${com.huimao.map.BuildConfig.VERSION_NAME} (${com.huimao.map.BuildConfig.VERSION_CODE})") }, leadingContent = { Icon(Icons.Default.Info, null) })
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        ListItem(headlineContent = { Text("百度地图 SDK") }, supportingContent = { Text("地图 v7.6.4.1 · 定位 v9.6.4") }, leadingContent = { Icon(Icons.Default.Map, null) })
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        ListItem(
                            headlineContent = { Text("包名") },
                            supportingContent = { Text(com.huimao.map.BuildConfig.APPLICATION_ID, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) },
                            leadingContent = { Icon(Icons.Default.Android, null) }
                        )
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        ListItem(
                            headlineContent = { Text("Release SHA1") },
                            supportingContent = { Text("DE:FF:00:C2:E1:8A:20:62:9C:4A:17:67:B7:27:A5:08:CC:1B:F0:E4", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, style = MaterialTheme.typography.labelSmall) },
                            leadingContent = { Icon(Icons.Default.Fingerprint, null) }
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// FavoritesScreen
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(vm: NavViewModel, onBack: () -> Unit, onPlaceSelected: () -> Unit) {
    val favorites by vm.favorites.collectAsState()

    // 编辑对话框状态
    var editingPlace by remember { mutableStateOf<Place?>(null) }
    var editName by remember { mutableStateOf("") }
    var editAddress by remember { mutableStateOf("") }

    // 添加地点对话框状态
    var showAddDialog by remember { mutableStateOf(false) }
    var addName by remember { mutableStateOf("") }
    var addAddress by remember { mutableStateOf("") }

    // 编辑对话框
    if (editingPlace != null) {
        AlertDialog(
            onDismissRequest = { editingPlace = null },
            title = { Text("编辑地点") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editAddress,
                        onValueChange = { editAddress = it },
                        label = { Text("地址") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editingPlace?.let { vm.updateFavorite(it.uid, editName, editAddress) }
                    editingPlace = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingPlace = null }) { Text("取消") }
            }
        )
    }

    // 添加地点对话框
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("添加收藏地点") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = addName,
                        onValueChange = { addName = it },
                        label = { Text("名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = addAddress,
                        onValueChange = { addAddress = it },
                        label = { Text("地址") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (addName.isNotBlank()) {
                        vm.addFavorite(addName, addAddress)
                    }
                    addName = ""; addAddress = ""
                    showAddDialog = false
                }) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("收藏地点", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { addName = ""; addAddress = ""; showAddDialog = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("添加地点") }
            )
        }
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(favorites, key = { it.uid }) { place ->
                val isSpecial = place.uid == "home" || place.uid == "work"
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    ListItem(
                        headlineContent = { Text(place.name, fontWeight = FontWeight.Medium) },
                        supportingContent = {
                            Text(
                                if (place.address.isBlank()) "点击编辑按钮设置地址" else place.address,
                                color = if (place.address.isBlank()) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        leadingContent = {
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = if (isSpecial) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(44.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = when (place.uid) {
                                            "home" -> Icons.Default.Home
                                            "work" -> Icons.Default.Work
                                            else -> Icons.Default.Favorite
                                        },
                                        contentDescription = null,
                                        tint = if (isSpecial) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            Row {
                                // 编辑按钮（家/公司显示编辑，其他显示删除）
                                if (isSpecial) {
                                    IconButton(onClick = {
                                        editName = place.name
                                        editAddress = place.address
                                        editingPlace = place
                                    }) {
                                        Icon(Icons.Default.Edit, contentDescription = "编辑", tint = MaterialTheme.colorScheme.secondary)
                                    }
                                } else {
                                    IconButton(onClick = { vm.removeFavorite(place.uid) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                                // 导航按钮；未解析出坐标时打开编辑框，不把 0,0 当目的地。
                                IconButton(onClick = {
                                    if (place.latLng.latitude != 0.0 && place.latLng.longitude != 0.0) {
                                        vm.selectDestination(place); onPlaceSelected()
                                    } else if (isSpecial) {
                                        editName = place.name
                                        editAddress = place.address
                                        editingPlace = place
                                    }
                                }) {
                                    Icon(Icons.Default.Navigation, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// 工具函数
// ─────────────────────────────────────────────
fun fmtDist(m: Double): String = when {
    m <= 0 -> "0米"
    m < 1000 -> "${m.toInt()}米"
    else -> String.format("%.1f公里", m / 1000)
}

fun fmtDur(s: Long): String = when {
    s <= 0 -> "0分钟"
    s < 60 -> "${s}秒"
    s < 3600 -> "${s / 60}分钟"
    else -> "${s / 3600}小时${(s % 3600) / 60}分钟"
}

fun fmtEta(ts: Long): String {
    if (ts == 0L) return "--:--"
    val cal = Calendar.getInstance().apply { timeInMillis = ts }
    return String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
}

fun maneuverIcon(m: ManeuverType): ImageVector = when (m) {
    ManeuverType.TURN_LEFT, ManeuverType.TURN_SHARP_LEFT, ManeuverType.TURN_SLIGHT_LEFT -> Icons.Default.TurnLeft
    ManeuverType.TURN_RIGHT, ManeuverType.TURN_SHARP_RIGHT, ManeuverType.TURN_SLIGHT_RIGHT -> Icons.Default.TurnRight
    ManeuverType.U_TURN -> Icons.Default.UTurnLeft
    ManeuverType.ROUNDABOUT -> Icons.Default.RotateRight
    ManeuverType.ARRIVE -> Icons.Default.Flag
    ManeuverType.DEPART -> Icons.Default.NearMe
    else -> Icons.Default.ArrowUpward
}

// ─────────────────────────────────────────────
// SponsorSection（关于页「赞助」区：三个档位的一次性内购，可重复购买）
// ─────────────────────────────────────────────
@Composable
fun SponsorSection() {
    val context = LocalContext.current
    var products by remember { mutableStateOf<List<ProductDetails>?>(null) }
    var toastText by remember { mutableStateOf<String?>(null) }

    val manager = remember {
        SponsorBillingManager(
            context,
            onPurchaseSuccess = { toastText = "感谢你的赞助！" },
            onError = { toastText = "购买未完成，请稍后重试" },
        )
    }

    LaunchedEffect(manager) {
        manager.connect { products = it }
    }
    DisposableEffect(manager) {
        onDispose { manager.destroy() }
    }
    toastText?.let { text ->
        LaunchedEffect(text) {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            toastText = null
        }
    }

    Text(
        "赞助",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
    )
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "如果灰猫地图对你有帮助，欢迎赞助支持开发 ☕ 赞助为一次性内购，可重复购买。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            when (val list = products) {
                null -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(vertical = 8.dp)
                            .size(28.dp),
                    )
                }
                else -> {
                    val items = list.mapNotNull { details ->
                        SponsorTier.fromProductId(details.productId)?.let { tier -> tier to details }
                    }
                    if (items.isEmpty()) {
                        Text(
                            "赞助功能暂未开放",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        items.forEach { (tier, details) ->
                            val price = details.oneTimePurchaseOfferDetails?.formattedPrice
                            val label = when (tier) {
                                SponsorTier.COFFEE -> "请喝咖啡"
                                SponsorTier.LUNCH -> "请吃午餐"
                                SponsorTier.FEAST -> "请吃大餐"
                            }
                            OutlinedButton(
                                onClick = {
                                    (context as? Activity)?.let { activity ->
                                        manager.launchPurchase(activity, details)
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                            ) {
                                Text(if (price != null) "$label · $price" else label)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// AboutScreen（关于灰猫地图：作者信息 · 开源库鸣谢 · 相关链接）
// ─────────────────────────────────────────────
private const val ABOUT_URL_RELEASES = "https://github.com/huimao28/huimao-map/releases"
private const val ABOUT_URL_REPO = "https://github.com/huimao28/huimao-map"
private const val ABOUT_URL_HOMEPAGE = "https://github.com/huimao28"

private val ABOUT_CREDITS = listOf(
    "百度地图 SDK（地图 / 定位 / 搜索 / 导航）" to "百度地图开放平台",
    "Jetpack Compose · Material 3" to "Apache License 2.0",
    "AndroidX（Core / Lifecycle / Activity / Navigation / DataStore）" to "Apache License 2.0",
    "OkHttp3" to "Apache License 2.0",
    "Lottie" to "Apache License 2.0",
    "Glide" to "BSD / MIT / Apache License 2.0",
    "Google Play services Wearable" to "Google Play 服务条款",
    "Google Play Billing Library" to "Google Play 服务条款",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    fun openUrl(url: String) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Exception) { }
    }
    fun sectionTitle(text: String): @Composable () -> Unit = {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
        )
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("关于灰猫地图", fontWeight = FontWeight.SemiBold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }
        )
    }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Column(Modifier.padding(vertical = 8.dp, horizontal = 4.dp)) {
                    Text(
                        "灰猫地图",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "版本 ${com.huimao.map.BuildConfig.VERSION_NAME} (${com.huimao.map.BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "基于百度地图与百度导航 SDK 的 Android 地图 / 导航应用，支持微信位置转发与 Wear OS 适配。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item {
                SponsorSection()
            }
            item { sectionTitle("作者信息")() }
            item {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column {
                        ListItem(
                            headlineContent = { Text("开发者") },
                            supportingContent = { Text("huimao28") },
                            leadingContent = { Icon(Icons.Default.Person, null) }
                        )
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        ListItem(
                            headlineContent = { Text("GitHub 主页") },
                            supportingContent = { Text("github.com/huimao28") },
                            leadingContent = { Icon(Icons.Default.Public, null) },
                            trailingContent = { Icon(Icons.Default.OpenInNew, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                            modifier = Modifier.clickable { openUrl(ABOUT_URL_HOMEPAGE) }
                        )
                    }
                }
            }
            item { sectionTitle("相关链接")() }
            item {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { openUrl(ABOUT_URL_RELEASES) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("检查更新", modifier = Modifier.weight(1f))
                            Icon(Icons.Default.OpenInNew, null)
                        }
                        OutlinedButton(
                            onClick = { openUrl(ABOUT_URL_REPO) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("开源仓库", modifier = Modifier.weight(1f))
                            Icon(Icons.Default.OpenInNew, null)
                        }
                    }
                }
            }
            item { sectionTitle("开源许可")() }
            item {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Text(
                        "本仓库暂未声明开源协议，默认保留所有权利。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            item { sectionTitle("开源库鸣谢")() }
            item {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column {
                        ABOUT_CREDITS.forEachIndexed { i, (name, license) ->
                            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            ListItem(
                                headlineContent = { Text(name, style = MaterialTheme.typography.bodyMedium) },
                                supportingContent = { Text(license, style = MaterialTheme.typography.labelSmall) },
                                leadingContent = { Icon(Icons.Default.Favorite, null, tint = MaterialTheme.colorScheme.primary) }
                            )
                        }
                    }
                }
            }
            item { sectionTitle("数据来源")() }
            item {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Text(
                        "地图、定位、POI 搜索、路线规划与导航数据均由百度地图提供。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}
