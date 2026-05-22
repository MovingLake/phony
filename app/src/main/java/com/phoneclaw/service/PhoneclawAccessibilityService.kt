package com.phoneclaw.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import com.phoneclaw.MainActivity
import com.phoneclaw.PhoneclawApp
import com.phoneclaw.R
import com.phoneclaw.agent.ActionExecutor
import com.phoneclaw.agent.AgentLoop
import com.phoneclaw.agent.AgentState
import com.phoneclaw.agent.AppResolver
import com.phoneclaw.agent.GeminiClient
import com.phoneclaw.agent.ScreenshotManager
import com.phoneclaw.data.GeminiModel
import com.phoneclaw.data.PreferencesManager
import com.phoneclaw.speech.SpeechManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "PhoneclawService"
private const val NOTIFICATION_ID = 1001

/**
 * The heart of PhoneClaw.
 *
 * This AccessibilityService:
 *   1. Shows a foreground notification to stay alive
 *   2. Manages the floating FAB overlay
 *   3. Listens for FAB clicks → triggers STT → runs the agent loop
 *   4. Routes agent state changes back to the FAB's visual state
 *
 * Lifecycle:
 *   onServiceConnected → show FAB, start foreground notification
 *   FAB click → listen() → runTask() → speak result
 *   onInterrupt / onDestroy → tear down FAB, cancel coroutines
 */
class PhoneclawAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var prefsManager: PreferencesManager
    private lateinit var screenshotManager: ScreenshotManager
    private lateinit var speechManager: SpeechManager
    private lateinit var appResolver: AppResolver
    private lateinit var actionExecutor: ActionExecutor
    private lateinit var floatingButtonManager: FloatingButtonManager

    private var agentLoop: AgentLoop? = null
    private var currentTaskJob: Job? = null
    private var isListening = false

    companion object {
        /** Whether the service is currently connected. Used by the UI to show status. */
        var isRunning = false
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "PhoneClaw accessibility service connected")
        isRunning = true

        prefsManager = PreferencesManager(this)
        screenshotManager = ScreenshotManager(this)
        speechManager = SpeechManager(this)
        appResolver = AppResolver(this)
        actionExecutor = ActionExecutor(this, appResolver, screenshotManager)

        floatingButtonManager = FloatingButtonManager(this) {
            onFabClicked()
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        floatingButtonManager.show()

        // Hide overlays before every screenshot so they don't pollute the image
        screenshotManager.onBeforeScreenshot = { floatingButtonManager.setOverlaysVisible(false) }
        screenshotManager.onAfterScreenshot  = { floatingButtonManager.setOverlaysVisible(true) }

        // Initialize the agent loop with the current API key
        serviceScope.launch {
            val apiKey = prefsManager.geminiApiKey.first()
            val modelId = prefsManager.selectedModel.first()
            if (apiKey.isNotBlank()) {
                initAgentLoop(apiKey, modelId)
            }

            // React to API key changes (user updates key in settings while service is running)
            prefsManager.geminiApiKey.collect { key ->
                val model = prefsManager.selectedModel.first()
                if (key.isNotBlank()) {
                    initAgentLoop(key, model)
                }
            }
        }
    }

    private fun initAgentLoop(apiKey: String, modelId: String) {
        Log.d(TAG, "Initializing agent loop with model: $modelId")
        val geminiClient = GeminiClient(
            apiKey = apiKey,
            model = GeminiModel.fromId(modelId),
            screenshotManager = screenshotManager
        )
        agentLoop = AgentLoop(
            geminiClient = geminiClient,
            actionExecutor = actionExecutor,
            screenshotManager = screenshotManager,
            onSpeak = { message ->
                withContext(Dispatchers.Main) {
                    floatingButtonManager.updateState(AgentState.Running("Speaking…"))
                    speechManager.speak(message)
                }
            }
        )

        // Bridge agent state → FAB visual state
        serviceScope.launch {
            agentLoop?.state?.collect { state ->
                floatingButtonManager.updateState(state)
            }
        }
    }

    private fun onFabClicked() {
        // If agent is already running, cancel it
        if (currentTaskJob?.isActive == true) {
            currentTaskJob?.cancel()
            agentLoop?.cancel()
            speechManager.stopSpeaking()
            floatingButtonManager.updateState(AgentState.Idle)
            return
        }

        val loop = agentLoop
        if (loop == null) {
            // No API key set yet
            serviceScope.launch {
                speechManager.speak("Please open PhoneClaw and enter your Gemini API key first.")
            }
            return
        }

        currentTaskJob = serviceScope.launch {
            // Update FAB to listening state
            floatingButtonManager.updateState(AgentState.Running("Listening…"))

            // Listen for voice command (STT runs on main thread)
            isListening = true
            val command = withContext(Dispatchers.Main) {
                speechManager.listen()
            }
            isListening = false

            if (command.isNullOrBlank()) {
                floatingButtonManager.updateState(AgentState.Idle)
                return@launch
            }

            Log.d(TAG, "User command: $command")
            floatingButtonManager.updateState(AgentState.Running("Thinking…"))

            // Run the agent loop
            loop.runTask(command)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to react to specific events — the agent reads the
        // screen on demand via takeScreenshot() and rootInActiveWindow.
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        isRunning = false
        floatingButtonManager.hide()
        speechManager.destroy()
        serviceScope.cancel()
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, PhoneclawApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
