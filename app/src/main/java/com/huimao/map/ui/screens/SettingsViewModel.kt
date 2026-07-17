package com.huimao.map.ui.screens

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.huimao.map.data.AppSettingsKeys
import com.huimao.map.data.dataStore
import com.huimao.map.map.BaiduMapManager
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

    val ttsProvider = ds.data
        .map { it[AppSettingsKeys.TTS_PROVIDER] ?: "system" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "system")

    val baiduTtsAppId = ds.data
        .map { it[AppSettingsKeys.BAIDU_TTS_APP_ID] ?: "" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val baiduTtsApiKey = ds.data
        .map { it[AppSettingsKeys.BAIDU_TTS_API_KEY] ?: "" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val baiduTtsSecretKey = ds.data
        .map { it[AppSettingsKeys.BAIDU_TTS_SECRET_KEY] ?: "" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

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

    fun saveTtsProvider(provider: String) {
        viewModelScope.launch { ds.edit { it[AppSettingsKeys.TTS_PROVIDER] = provider } }
    }

    fun saveBaiduTtsCredentials(appId: String, apiKey: String, secretKey: String) {
        viewModelScope.launch {
            ds.edit {
                it[AppSettingsKeys.BAIDU_TTS_APP_ID] = appId.trim()
                it[AppSettingsKeys.BAIDU_TTS_API_KEY] = apiKey.trim()
                it[AppSettingsKeys.BAIDU_TTS_SECRET_KEY] = secretKey.trim()
            }
        }
    }

    fun setTrafficEnabled(enabled: Boolean) {
        viewModelScope.launch { ds.edit { it[AppSettingsKeys.TRAFFIC_ENABLED] = enabled } }
    }
    fun saveTrafficEnabled(enabled: Boolean) = setTrafficEnabled(enabled)

    fun setRouteType(type: Int) {
        viewModelScope.launch { ds.edit { it[AppSettingsKeys.ROUTE_TYPE] = type } }
    }
    fun saveRouteType(type: Int) = setRouteType(type)
}
