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
        private val KEY_SELECTED_PROVIDER  = stringPreferencesKey("selected_provider")
        private val KEY_GEMINI_API_KEY     = stringPreferencesKey("gemini_api_key")
        private val KEY_GEMINI_MODEL       = stringPreferencesKey("selected_model")
        private val KEY_ANTHROPIC_API_KEY  = stringPreferencesKey("anthropic_api_key")
        private val KEY_CLAUDE_MODEL       = stringPreferencesKey("claude_model")
        private val KEY_OPENAI_API_KEY     = stringPreferencesKey("openai_api_key")
        private val KEY_OPENAI_MODEL       = stringPreferencesKey("openai_model")
    }

    // ── Provider ────────────────────────────────────────────────────────

    val selectedProvider: Flow<AIProvider> = context.dataStore.data.map { prefs ->
        AIProvider.entries.firstOrNull { it.name == prefs[KEY_SELECTED_PROVIDER] } ?: AIProvider.GEMINI
    }

    suspend fun setSelectedProvider(provider: AIProvider) =
        context.dataStore.edit { it[KEY_SELECTED_PROVIDER] = provider.name }

    // ── Gemini ──────────────────────────────────────────────────────────

    val geminiApiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_GEMINI_API_KEY] ?: ""
    }

    val selectedModel: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_GEMINI_MODEL] ?: GeminiModel.FLASH_3_5.modelId
    }

    suspend fun setGeminiApiKey(key: String) =
        context.dataStore.edit { it[KEY_GEMINI_API_KEY] = key.trim() }

    suspend fun setSelectedModel(modelId: String) =
        context.dataStore.edit { it[KEY_GEMINI_MODEL] = modelId }

    // ── Claude ──────────────────────────────────────────────────────────

    val anthropicApiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_ANTHROPIC_API_KEY] ?: ""
    }

    val selectedClaudeModel: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_CLAUDE_MODEL] ?: ClaudeModel.SONNET_4_6.modelId
    }

    suspend fun setAnthropicApiKey(key: String) =
        context.dataStore.edit { it[KEY_ANTHROPIC_API_KEY] = key.trim() }

    suspend fun setClaudeModel(modelId: String) =
        context.dataStore.edit { it[KEY_CLAUDE_MODEL] = modelId }

    // ── OpenAI ──────────────────────────────────────────────────────────

    val openaiApiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_OPENAI_API_KEY] ?: ""
    }

    val selectedOpenAIModel: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_OPENAI_MODEL] ?: OpenAIModel.GPT_5_5.modelId
    }

    suspend fun setOpenAIApiKey(key: String) =
        context.dataStore.edit { it[KEY_OPENAI_API_KEY] = key.trim() }

    suspend fun setOpenAIModel(modelId: String) =
        context.dataStore.edit { it[KEY_OPENAI_MODEL] = modelId }
}

// ─── Provider enum ──────────────────────────────────────────────────────────

enum class AIProvider(val displayName: String) {
    GEMINI("Gemini"),
    CLAUDE("Claude"),
    OPENAI("GPT"),
}

// ─── Model enums ────────────────────────────────────────────────────────────

enum class GeminiModel(val modelId: String, val displayName: String) {
    PRO_3_5("gemini-3.5-pro",          "Gemini 3.5 Pro (Best)"),
    FLASH_3_5("gemini-3.5-flash",      "Gemini 3.5 Flash (Recommended)"),
    PRO_2_5("gemini-2.5-pro",          "Gemini 2.5 Pro"),
    FLASH_2_5("gemini-2.5-flash",      "Gemini 2.5 Flash"),
    FLASH_2_5_LITE("gemini-2.5-flash-lite", "Gemini 2.5 Flash Lite (Fastest)"),
    ;
    companion object {
        fun fromId(id: String): GeminiModel =
            entries.firstOrNull { it.modelId == id } ?: FLASH_3_5
    }
}

enum class ClaudeModel(val modelId: String, val displayName: String) {
    OPUS_4_7("claude-opus-4-7",             "Claude Opus 4.7 (Best)"),
    SONNET_4_6("claude-sonnet-4-6",         "Claude Sonnet 4.6"),
    HAIKU_4_5("claude-haiku-4-5-20251001",  "Claude Haiku 4.5 (Fastest)"),
    ;
    companion object {
        fun fromId(id: String): ClaudeModel =
            entries.firstOrNull { it.modelId == id } ?: SONNET_4_6
    }
}

enum class OpenAIModel(val modelId: String, val displayName: String) {
    GPT_5_5("gpt-5.5",             "GPT-5.5 (Best)"),
    GPT_5_4("gpt-5.4",             "GPT-5.4"),
    GPT_5_4_MINI("gpt-5.4-mini",   "GPT-5.4 Mini (Fastest)"),
    ;
    companion object {
        fun fromId(id: String): OpenAIModel =
            entries.firstOrNull { it.modelId == id } ?: GPT_5_5
    }
}
