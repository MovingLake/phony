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
                var apiKey by remember { mutableStateOf("") }
                var hasOverlayPerm by remember { mutableStateOf(false) }
                var hasMicPerm by remember { mutableStateOf(false) }
                var isAccessibilityOn by remember { mutableStateOf(false) }

                // Load saved API key
                LaunchedEffect(Unit) {
                    apiKey = prefsManager.geminiApiKey.first()
                }

                // Re-check permissions every time the activity resumes
                // (user may have granted permissions in Settings and returned)
                val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
                LaunchedEffect(lifecycle) {
                    lifecycle.lifecycle.addObserver(
                        object : androidx.lifecycle.DefaultLifecycleObserver {
                            override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                                hasOverlayPerm = android.provider.Settings.canDrawOverlays(this@MainActivity)
                                hasMicPerm = ContextCompat.checkSelfPermission(
                                    this@MainActivity, Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                isAccessibilityOn = isAccessibilityServiceEnabled()
                            }
                        }
                    )
                }

                OnboardingScreen(
                    apiKey = apiKey,
                    onApiKeyChange = { apiKey = it },
                    onSaveApiKey = {
                        lifecycleScope.launch {
                            prefsManager.setGeminiApiKey(apiKey)
                        }
                    },
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
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return TextUtils.SimpleStringSplitter(':').apply {
            setString(enabledServices)
        }.any { it.equals(expectedComponentName, ignoreCase = true) }
    }
}
