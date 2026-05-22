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
import com.phoneclaw.agent.ClaudeClient
import com.phoneclaw.agent.GeminiClient
import com.phoneclaw.agent.OpenAIClient
import com.phoneclaw.agent.ScreenshotManager
import com.phoneclaw.data.AIProvider
import com.phoneclaw.data.ClaudeModel
import com.phoneclaw.data.GeminiModel
import com.phoneclaw.data.OpenAIModel
import com.phoneclaw.data.PreferencesManager
import com.phoneclaw.speech.SpeechManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "PhoneclawService"
private const val NOTIFICATION_ID = 1001

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

        floatingButtonManager = FloatingButtonManager(this) { onFabClicked() }

        startForeground(NOTIFICATION_ID, buildNotification())
        floatingButtonManager.show()

        screenshotManager.onBeforeScreenshot = { floatingButtonManager.setOverlaysVisible(false) }
        screenshotManager.onAfterScreenshot  = { floatingButtonManager.setOverlaysVisible(true) }

        // Re-initialize the agent loop whenever provider or any API key changes
        serviceScope.launch {
            combine(
                prefsManager.selectedProvider,
                prefsManager.geminiApiKey,
                prefsManager.anthropicApiKey,
                prefsManager.openaiApiKey,
            ) { provider, geminiKey, claudeKey, openaiKey ->
                Triple(provider, geminiKey, Pair(claudeKey, openaiKey))
            }.collect { (provider, geminiKey, otherKeys) ->
                val (claudeKey, openaiKey) = otherKeys
                initAgentLoop(provider, geminiKey, claudeKey, openaiKey)
            }
        }
    }

    private fun initAgentLoop(
        provider: AIProvider,
        geminiKey: String,
        claudeKey: String,
        openaiKey: String,
    ) {
        serviceScope.launch {
            val geminiModel = prefsManager.selectedModel.first()
            val claudeModel = prefsManager.selectedClaudeModel.first()
            val openaiModel = prefsManager.selectedOpenAIModel.first()

            Log.d(TAG, "Initializing agent loop — provider=$provider")

            val aiClient = when (provider) {
                AIProvider.GEMINI -> if (geminiKey.isNotBlank())
                    GeminiClient(geminiKey, GeminiModel.fromId(geminiModel), screenshotManager)
                else null
                AIProvider.CLAUDE -> if (claudeKey.isNotBlank())
                    ClaudeClient(claudeKey, ClaudeModel.fromId(claudeModel), screenshotManager)
                else null
                AIProvider.OPENAI -> if (openaiKey.isNotBlank())
                    OpenAIClient(openaiKey, OpenAIModel.fromId(openaiModel), screenshotManager)
                else null
            }

            if (aiClient == null) {
                Log.d(TAG, "No API key set for $provider — agent loop not initialized")
                agentLoop = null
                return@launch
            }

            agentLoop = AgentLoop(
                aiClient = aiClient,
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
    }

    private fun onFabClicked() {
        if (currentTaskJob?.isActive == true) {
            currentTaskJob?.cancel()
            agentLoop?.cancel()
            speechManager.stopSpeaking()
            floatingButtonManager.updateState(AgentState.Idle)
            return
        }

        val loop = agentLoop
        if (loop == null) {
            serviceScope.launch {
                speechManager.speak("Please open Phony and enter an API key first.")
            }
            return
        }

        currentTaskJob = serviceScope.launch {
            floatingButtonManager.updateState(AgentState.Running("Listening…"))

            isListening = true
            val command = withContext(Dispatchers.Main) { speechManager.listen() }
            isListening = false

            if (command.isNullOrBlank()) {
                floatingButtonManager.updateState(AgentState.Idle)
                return@launch
            }

            Log.d(TAG, "User command: $command")
            floatingButtonManager.updateState(AgentState.Running("Thinking…"))
            loop.runTask(command)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

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
