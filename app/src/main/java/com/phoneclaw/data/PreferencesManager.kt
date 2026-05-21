package com.phoneclaw.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "phoneclaw_prefs")

class PreferencesManager(private val context: Context) {

    companion object {
        private val KEY_GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        // Future: model selection, local model path, etc.
        private val KEY_SELECTED_MODEL = stringPreferencesKey("selected_model")
    }

    val geminiApiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_GEMINI_API_KEY] ?: ""
    }

    val selectedModel: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SELECTED_MODEL] ?: GeminiModel.FLASH_2_0.modelId
    }

    suspend fun setGeminiApiKey(key: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_GEMINI_API_KEY] = key.trim()
        }
    }

    suspend fun setSelectedModel(modelId: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SELECTED_MODEL] = modelId
        }
    }
}

/** Available Gemini models. Local (Gemma) support is a future milestone. */
enum class GeminiModel(val modelId: String, val displayName: String, val isLocal: Boolean = false) {
    FLASH_2_0("gemini-2.0-flash", "Gemini 2.0 Flash (Recommended)"),
    FLASH_2_0_THINKING("gemini-2.0-flash-thinking-exp", "Gemini 2.0 Flash Thinking"),
    PRO_2_0("gemini-2.0-pro-exp", "Gemini 2.0 Pro"),
    // Future: local Gemma models
    // GEMMA_2B_LOCAL("gemma-2b-it", "Gemma 2B (Local)", isLocal = true),
    ;

    companion object {
        fun fromId(id: String): GeminiModel =
            entries.firstOrNull { it.modelId == id } ?: FLASH_2_0
    }
}
