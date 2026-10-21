package com.hookbox.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "hookbox_settings")

/** 预置的中转地址，用户不用手填 */
const val DEFAULT_BASE_URL = "https://your-domain.com/hookbox"

/**
 * 预置的 RELAY_TOKEN，由构建时注入：
 *   gradle assembleDebug -PrelayToken=xxx
 * 本地不带该参数构建时为空，App 里需要用户自己填一次。
 * 不写死在源码里是因为这个仓库是公开的。
 */
val DEFAULT_TOKEN: String = com.hookbox.app.BuildConfig.RELAY_TOKEN

data class RelayConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val token: String = DEFAULT_TOKEN,
    val enabled: Boolean = false,
) {
    val configured: Boolean get() = baseUrl.isNotBlank() && token.isNotBlank()
}

class SettingsStore(private val context: Context) {

    val config: Flow<RelayConfig> = context.dataStore.data.map { prefs ->
        RelayConfig(
            baseUrl = prefs[KEY_URL] ?: DEFAULT_BASE_URL,
            token = prefs[KEY_TOKEN] ?: DEFAULT_TOKEN,
            enabled = prefs[KEY_ENABLED] ?: false,
        )
    }

    suspend fun save(baseUrl: String, token: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_URL] = baseUrl.trim().trimEnd('/')
            prefs[KEY_TOKEN] = token.trim()
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_ENABLED] = enabled }
    }

    private companion object {
        val KEY_URL = stringPreferencesKey("base_url")
        val KEY_TOKEN = stringPreferencesKey("token")
        val KEY_ENABLED = booleanPreferencesKey("enabled")
    }
}
