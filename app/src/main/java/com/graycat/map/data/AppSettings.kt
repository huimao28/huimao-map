package com.graycat.map.data

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
    val TRAFFIC_ENABLED = booleanPreferencesKey("traffic_enabled")
    val ROUTE_TYPE = intPreferencesKey("route_type")
    val DARK_MAP = booleanPreferencesKey("dark_map")
}
