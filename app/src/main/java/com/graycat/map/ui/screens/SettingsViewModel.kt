package com.graycat.map.ui.screens

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.graycat.map.data.AppSettingsKeys
import com.graycat.map.data.dataStore
import com.graycat.map.map.BaiduMapManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val ds = app.dataStore

    val baiduApiKey = ds.data
        .map { it[AppSettingsKeys.BAIDU_API_KEY] ?: "" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val voiceEnabled = ds.data
        .map { it[AppSettingsKeys.VOICE_ENABLED] ?: true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val trafficEnabled = ds.data
        .map { it[AppSettingsKeys.TRAFFIC_ENABLED] ?: true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val routeType = ds.data
        .map { it[AppSettingsKeys.ROUTE_TYPE] ?: 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun saveBaiduApiKey(key: String) {
        viewModelScope.launch {
            ds.edit { it[AppSettingsKeys.BAIDU_API_KEY] = key }
            // 重新初始化 SDK
            BaiduMapManager.initialize(getApplication(), key)
        }
    }

    fun setVoiceEnabled(enabled: Boolean) {
        viewModelScope.launch { ds.edit { it[AppSettingsKeys.VOICE_ENABLED] = enabled } }
    }
    fun saveVoiceEnabled(enabled: Boolean) = setVoiceEnabled(enabled)

    fun setTrafficEnabled(enabled: Boolean) {
        viewModelScope.launch { ds.edit { it[AppSettingsKeys.TRAFFIC_ENABLED] = enabled } }
    }
    fun saveTrafficEnabled(enabled: Boolean) = setTrafficEnabled(enabled)

    fun setRouteType(type: Int) {
        viewModelScope.launch { ds.edit { it[AppSettingsKeys.ROUTE_TYPE] = type } }
    }
    fun saveRouteType(type: Int) = setRouteType(type)
}
