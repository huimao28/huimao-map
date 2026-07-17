package com.huimao.map.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

object AppSettingsKeys {
    val BAIDU_API_KEY = stringPreferencesKey("baidu_api_key")
    val VOICE_ENABLED = booleanPreferencesKey("voice_enabled")
    val TTS_PROVIDER = stringPreferencesKey("tts_provider")
    val BAIDU_TTS_APP_ID = stringPreferencesKey("baidu_tts_app_id")
    val BAIDU_TTS_API_KEY = stringPreferencesKey("baidu_tts_api_key")
    val BAIDU_TTS_SECRET_KEY = stringPreferencesKey("baidu_tts_secret_key")
    val TRAFFIC_ENABLED = booleanPreferencesKey("traffic_enabled")
    val ROUTE_TYPE = intPreferencesKey("route_type")
    val DARK_MAP = booleanPreferencesKey("dark_map")
}
