package com.phoneclaw.agent

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "AgentLoop"

/**
 * The main agent loop — nanoclaw's routing pipeline, but for phone actions.
 *
 * Flow per task:
 *   user speaks → screenshot → Gemini (tools) → execute actions → screenshot
 *   → Gemini → execute … → task_complete → speak result back
 *
 * All state is local to one task invocation; there's no shared mutable state
 * between tasks. This mirrors nanoclaw's per-session database isolation.
 */
class AgentLoop(
    private val geminiClient: GeminiClient,
    private val actionExecutor: ActionExecutor,
    private val screenshotManager: ScreenshotManager,
    private val onSpeak: suspend (String) -> Unit,
) {

    private val _state = MutableStateFlow<AgentState>(AgentState.Idle)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    /** Run a full task from a voice command. Suspends until the task completes. */
    suspend fun runTask(userCommand: String) {
        if (_state.value is AgentState.Running) {
            Log.w(TAG, "Agent already running, ignoring new task")
            return
        }

        _state.value = AgentState.Running("Capturing screen…")

        try {
            val screenshot = screenshotManager.takeScreenshot()
            if (screenshot == null) {
                _state.value = AgentState.Error("Could not capture screen. Is the accessibility service active?")
                onSpeak("Sorry, I couldn't capture your screen. Please check that PhoneClaw's accessibility service is enabled.")
                return
            }

            val apiScreenshot = screenshotManager.prepareForApi(screenshot)

            val summary = geminiClient.runTask(
                userCommand = userCommand,
                initialScreenshot = apiScreenshot,
                onToolCall = { toolCall -> executeToolCall(toolCall) },
                onStatusUpdate = { status -> _state.value = AgentState.Running(status) }
            )

            // Speak the final summary if it wasn't already spoken via speak_to_user
            if (summary.isNotBlank()) {
                onSpeak(summary)
            }

            _state.value = AgentState.Idle

        } catch (e: Exception) {
            Log.e(TAG, "Agent task failed", e)
            val errorMsg = when {
                e.message?.contains("API_KEY") == true -> "Invalid Gemini API key. Please check your key in settings."
                e.message?.contains("network") == true || e.message?.contains("connect") == true ->
                    "Network error. Please check your connection."
                else -> "Something went wrong: ${e.message}"
            }
            _state.value = AgentState.Error(errorMsg)
            onSpeak(errorMsg)
        }
    }

    fun cancel() {
        _state.value = AgentState.Idle
    }

    private suspend fun executeToolCall(toolCall: ToolCall): ToolResult {
        Log.d(TAG, "Executing tool: ${toolCall.name.id} args=${toolCall.args}")

        return when (toolCall.name) {
            ToolName.TAKE_SCREENSHOT -> {
                val bitmap = screenshotManager.takeScreenshot()
                if (bitmap != null) {
                    val base64 = ScreenshotManager.bitmapToBase64(screenshotManager.prepareForApi(bitmap))
                    ToolResult(ToolName.TAKE_SCREENSHOT, true, "Screenshot captured", screenshotBase64 = base64)
                } else {
                    ToolResult(ToolName.TAKE_SCREENSHOT, false, "Failed to capture screenshot")
                }
            }

            ToolName.TAP -> {
                val x = toolCall.intArg("x") ?: return missingArg(ToolName.TAP, "x")
                val y = toolCall.intArg("y") ?: return missingArg(ToolName.TAP, "y")
                actionExecutor.tap(x, y)
            }

            ToolName.LONG_PRESS -> {
                val x = toolCall.intArg("x") ?: return missingArg(ToolName.LONG_PRESS, "x")
                val y = toolCall.intArg("y") ?: return missingArg(ToolName.LONG_PRESS, "y")
                actionExecutor.longPress(x, y)
            }

            ToolName.SWIPE -> {
                val startX = toolCall.intArg("startX") ?: return missingArg(ToolName.SWIPE, "startX")
                val startY = toolCall.intArg("startY") ?: return missingArg(ToolName.SWIPE, "startY")
                val endX = toolCall.intArg("endX") ?: return missingArg(ToolName.SWIPE, "endX")
                val endY = toolCall.intArg("endY") ?: return missingArg(ToolName.SWIPE, "endY")
                val duration = toolCall.intArg("durationMs") ?: 300
                actionExecutor.swipe(startX, startY, endX, endY, duration)
            }

            ToolName.TYPE_TEXT -> {
                val text = toolCall.stringArg("text") ?: return missingArg(ToolName.TYPE_TEXT, "text")
                actionExecutor.typeText(text)
            }

            ToolName.OPEN_APP -> {
                val appName = toolCall.stringArg("app_name") ?: return missingArg(ToolName.OPEN_APP, "app_name")
                actionExecutor.openApp(appName)
            }

            ToolName.PRESS_BACK -> actionExecutor.pressBack()
            ToolName.PRESS_HOME -> actionExecutor.pressHome()
            ToolName.PRESS_RECENT_APPS -> actionExecutor.pressRecentApps()

            ToolName.SCROLL -> {
                val dirStr = toolCall.stringArg("direction") ?: return missingArg(ToolName.SCROLL, "direction")
                val direction = ScrollDirection.entries.firstOrNull { it.name == dirStr.uppercase() }
                    ?: return ToolResult(ToolName.SCROLL, false, "Invalid direction: $dirStr. Use UP, DOWN, LEFT, or RIGHT")
                val x = toolCall.intArg("x") ?: 540
                val y = toolCall.intArg("y") ?: 960
                actionExecutor.scroll(direction, x, y)
            }

            ToolName.FIND_AND_CLICK_TEXT -> {
                val text = toolCall.stringArg("text") ?: return missingArg(ToolName.FIND_AND_CLICK_TEXT, "text")
                actionExecutor.findAndClickText(text)
            }

            ToolName.GET_SCREEN_TEXT -> {
                actionExecutor.getScreenText()
            }

            ToolName.SPEAK_TO_USER -> {
                val message = toolCall.stringArg("message") ?: return missingArg(ToolName.SPEAK_TO_USER, "message")
                onSpeak(message)
                ToolResult(ToolName.SPEAK_TO_USER, true, "Spoke to user: $message")
            }

            ToolName.TASK_COMPLETE -> {
                val summary = toolCall.stringArg("summary") ?: "Done."
                ToolResult(ToolName.TASK_COMPLETE, true, summary)
            }
        }
    }

    private fun missingArg(tool: ToolName, arg: String) =
        ToolResult(tool, false, "Missing required argument: $arg")
}

// ─── State ─────────────────────────────────────────────────────────────────

sealed class AgentState {
    object Idle : AgentState()
    data class Running(val statusMessage: String) : AgentState()
    data class Error(val message: String) : AgentState()
}

// ─── Arg extraction helpers ────────────────────────────────────────────────

private fun ToolCall.intArg(key: String): Int? =
    when (val v = args[key]) {
        is Int -> v
        is Long -> v.toInt()
        is Double -> v.toInt()
        is Float -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }

private fun ToolCall.stringArg(key: String): String? =
    args[key]?.toString()
