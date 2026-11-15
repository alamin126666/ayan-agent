package com.ayan.agent.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ayan_agent")

object PrefKeys {
    val SETUP_DONE = booleanPreferencesKey("setup_done")
    val PROVIDER_ID = stringPreferencesKey("provider_id")
    val API_KEYS_JSON = stringPreferencesKey("api_keys_json")
    val MODEL_ID = stringPreferencesKey("model_id")
    val WORK_FOLDER_URI = stringPreferencesKey("work_folder_uri")
    val MULTI_KEY_FALLBACK = booleanPreferencesKey("multi_key_fallback")
}

/** Simple DataStore wrapper: setup flag, provider, API keys, model, work folder. */
class SettingsStore(private val context: Context) {

    val setupDone: Flow<Boolean> =
        context.dataStore.data.map { it[PrefKeys.SETUP_DONE] ?: false }

    val providerId: Flow<String> =
        context.dataStore.data.map { it[PrefKeys.PROVIDER_ID] ?: "openrouter" }

    val apiKeysJson: Flow<String> =
        context.dataStore.data.map { it[PrefKeys.API_KEYS_JSON] ?: "{}" }

    val modelId: Flow<String> =
        context.dataStore.data.map { it[PrefKeys.MODEL_ID] ?: "" }

    val workFolderUri: Flow<String?> =
        context.dataStore.data.map { it[PrefKeys.WORK_FOLDER_URI] }

    val multiKeyFallback: Flow<Boolean> =
        context.dataStore.data.map { it[PrefKeys.MULTI_KEY_FALLBACK] ?: true }

    suspend fun setSetupDone(done: Boolean) {
        context.dataStore.edit { it[PrefKeys.SETUP_DONE] = done }
    }

    suspend fun setProviderId(id: String) {
        context.dataStore.edit { it[PrefKeys.PROVIDER_ID] = id }
    }

    suspend fun setApiKeysJson(json: String) {
        context.dataStore.edit { it[PrefKeys.API_KEYS_JSON] = json }
    }

    suspend fun setModelId(model: String) {
        context.dataStore.edit { it[PrefKeys.MODEL_ID] = model }
    }

    suspend fun setWorkFolderUri(uri: String) {
        context.dataStore.edit { it[PrefKeys.WORK_FOLDER_URI] = uri }
    }

    suspend fun setMultiKeyFallback(enabled: Boolean) {
        context.dataStore.edit { it[PrefKeys.MULTI_KEY_FALLBACK] = enabled }
    }

    suspend fun getString(key: Preferences.Key<String>, default: String = ""): String =
        context.dataStore.data.map { it[key] ?: default }.first()
}
