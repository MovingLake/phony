package com.phoneclaw

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.phoneclaw.data.AIProvider
import com.phoneclaw.data.ClaudeModel
import com.phoneclaw.data.GeminiModel
import com.phoneclaw.data.OpenAIModel
import com.phoneclaw.data.PreferencesManager
import com.phoneclaw.service.PhoneclawAccessibilityService
import com.phoneclaw.ui.screens.OnboardingScreen
import com.phoneclaw.ui.theme.PhoneclawTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var prefsManager: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        prefsManager = PreferencesManager(this)

        setContent {
            PhoneclawTheme {
                // Provider
                var selectedProvider by remember { mutableStateOf(AIProvider.GEMINI) }
                // Gemini
                var geminiApiKey by remember { mutableStateOf("") }
                var selectedGeminiModel by remember { mutableStateOf(GeminiModel.FLASH_3_5.modelId) }
                // Claude
                var claudeApiKey by remember { mutableStateOf("") }
                var selectedClaudeModel by remember { mutableStateOf(ClaudeModel.SONNET_4_6.modelId) }
                // OpenAI
                var openaiApiKey by remember { mutableStateOf("") }
                var selectedOpenaiModel by remember { mutableStateOf(OpenAIModel.GPT_4O.modelId) }
                // Permissions
                var hasOverlayPerm by remember { mutableStateOf(false) }
                var hasMicPerm by remember { mutableStateOf(false) }
                var isAccessibilityOn by remember { mutableStateOf(false) }

                // Load saved preferences
                LaunchedEffect(Unit) {
                    selectedProvider    = prefsManager.selectedProvider.first()
                    geminiApiKey        = prefsManager.geminiApiKey.first()
                    selectedGeminiModel = prefsManager.selectedModel.first()
                    claudeApiKey        = prefsManager.anthropicApiKey.first()
                    selectedClaudeModel = prefsManager.selectedClaudeModel.first()
                    openaiApiKey        = prefsManager.openaiApiKey.first()
                    selectedOpenaiModel = prefsManager.selectedOpenAIModel.first()
                }

                // Re-check permissions on resume
                val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
                LaunchedEffect(lifecycle) {
                    lifecycle.lifecycle.addObserver(
                        object : androidx.lifecycle.DefaultLifecycleObserver {
                            override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                                hasOverlayPerm = Settings.canDrawOverlays(this@MainActivity)
                                hasMicPerm = ContextCompat.checkSelfPermission(
                                    this@MainActivity, Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                isAccessibilityOn = isAccessibilityServiceEnabled()
                            }
                        }
                    )
                }

                OnboardingScreen(
                    // Provider
                    selectedProvider = selectedProvider,
                    onProviderChange = {
                        selectedProvider = it
                        lifecycleScope.launch { prefsManager.setSelectedProvider(it) }
                    },
                    // Gemini
                    geminiApiKey = geminiApiKey,
                    onGeminiApiKeyChange = { geminiApiKey = it },
                    onSaveGeminiApiKey = {
                        lifecycleScope.launch { prefsManager.setGeminiApiKey(geminiApiKey) }
                    },
                    selectedGeminiModel = selectedGeminiModel,
                    onGeminiModelChange = {
                        selectedGeminiModel = it
                        lifecycleScope.launch { prefsManager.setSelectedModel(it) }
                    },
                    // Claude
                    claudeApiKey = claudeApiKey,
                    onClaudeApiKeyChange = { claudeApiKey = it },
                    onSaveClaudeApiKey = {
                        lifecycleScope.launch { prefsManager.setAnthropicApiKey(claudeApiKey) }
                    },
                    selectedClaudeModel = selectedClaudeModel,
                    onClaudeModelChange = {
                        selectedClaudeModel = it
                        lifecycleScope.launch { prefsManager.setClaudeModel(it) }
                    },
                    // OpenAI
                    openaiApiKey = openaiApiKey,
                    onOpenaiApiKeyChange = { openaiApiKey = it },
                    onSaveOpenaiApiKey = {
                        lifecycleScope.launch { prefsManager.setOpenAIApiKey(openaiApiKey) }
                    },
                    selectedOpenaiModel = selectedOpenaiModel,
                    onOpenaiModelChange = {
                        selectedOpenaiModel = it
                        lifecycleScope.launch { prefsManager.setOpenAIModel(it) }
                    },
                    // Permissions
                    hasOverlayPermission = hasOverlayPerm,
                    hasMicPermission = hasMicPerm,
                    isAccessibilityEnabled = isAccessibilityOn,
                )
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName =
            "${packageName}/${PhoneclawAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return TextUtils.SimpleStringSplitter(':').apply {
            setString(enabledServices)
        }.any { it.equals(expectedComponentName, ignoreCase = true) }
    }
}
