package com.phoneclaw.agent

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

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

    // ─── Session history (short-term memory) ───────────────────────────────
    // Preserved across FAB taps for SESSION_TTL_MS, then reset to a clean slate.
    // Trimmed to MAX_SESSION_TURNS to avoid excessive token cost.
    private var sessionHistory: JSONArray? = null
    private var lastTaskTime: Long = 0L
    private val SESSION_TTL_MS = 60_000L    // 1 minute
    private val MAX_SESSION_TURNS = 8       // keep last 8 turns ≈ 4 exchanges

    /** Run a full task from a voice command. Suspends until the task completes. */
    suspend fun runTask(userCommand: String) {
        if (_state.value is AgentState.Running) {
            DebugLog.w(TAG, "Agent already running, ignoring new task")
            return
        }

        // Session TTL check — reset history if user hasn't spoken in a while
        val now = System.currentTimeMillis()
        if (sessionHistory != null && now - lastTaskTime > SESSION_TTL_MS) {
            DebugLog.d(TAG, "Session expired (${(now - lastTaskTime) / 1000}s idle) — starting fresh")
            sessionHistory = null
        }

        _state.value = AgentState.Running("Capturing screen…")

        try {
            val screenshot = screenshotManager.takeScreenshot()
            if (screenshot == null) {
                val msg = "Could not capture screen — is the accessibility service active?"
                DebugLog.e(TAG, msg)
                _state.value = AgentState.Error(msg)
                return
            }

            // Pass the raw screenshot — GeminiClient handles scaling + coordinate translation
            val (summary, updatedHistory) = geminiClient.runTask(
                userCommand = userCommand,
                initialScreenshot = screenshot,
                sessionHistory = sessionHistory,
                onToolCall = { toolCall -> executeToolCall(toolCall) },
                onStatusUpdate = { status -> _state.value = AgentState.Running(status) }
            )

            // Strip screenshots before storing — they are ~50k tokens each and the
            // model doesn't need to re-see old screens when resuming the session.
            sessionHistory = trimHistory(stripImages(updatedHistory))
            lastTaskTime = System.currentTimeMillis()

            // Speak the final summary if it wasn't already spoken via speak_to_user
            if (summary.isNotBlank()) {
                onSpeak(summary)
            }

            _state.value = AgentState.Idle

        } catch (e: Exception) {
            // Errors are debug-only — log + show on FAB, never speak
            DebugLog.e(TAG, "Agent task failed: ${e.message}", e)
            val errorMsg = when {
                e.message?.contains("API_KEY") == true ->
                    "Bad API key"
                e.message?.contains("network", ignoreCase = true) == true ||
                e.message?.contains("connect", ignoreCase = true) == true ->
                    "Network error"
                else ->
                    e.message?.take(80) ?: "Unknown error"
            }
            _state.value = AgentState.Error(errorMsg)
        }
    }

    fun cancel() {
        _state.value = AgentState.Idle
        // Keep session history on cancel so the user can rephrase immediately;
        // the TTL will clear it naturally after 1 minute of inactivity.
    }

    /**
     * Strip all inlineData (screenshot bytes) from every turn in [history].
     * Screenshots are huge (~50k tokens each as base64) but the model doesn't
     * need to re-see old screens when resuming a session — text and tool
     * call/response pairs carry enough context.
     */
    private fun stripImages(history: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until history.length()) {
            val turn = history.getJSONObject(i)
            val parts = turn.optJSONArray("parts") ?: continue
            val newParts = JSONArray()
            for (j in 0 until parts.length()) {
                val part = parts.getJSONObject(j)
                if (!part.has("inlineData")) newParts.put(part)
            }
            if (newParts.length() > 0) {
                out.put(JSONObject()
                    .put("role", turn.getString("role"))
                    .put("parts", newParts))
            }
        }
        return out
    }

    /**
     * Trim history to [MAX_SESSION_TURNS] and align the slice to a valid
     * conversation boundary.
     *
     * Gemini API hard rules:
     *   1. History must start with a user turn.
     *   2. functionCall model turn → must be immediately followed by a user
     *      functionResponse turn (nothing else in between).
     *   3. History must end with a model turn so the next user message is valid.
     *
     * The only safe starting point is a user turn that contains ONLY text/image
     * content — no functionResponse parts. Our "Updated screen" user turns contain
     * both functionResponse and text (after stripping images), so they look like
     * they have text but are mid-exchange and would leave the preceding functionCall
     * dangling. Only the initial "User said:..." turn and injected nudge turns are
     * pure text and valid anchors.
     */
    private fun trimHistory(history: JSONArray): JSONArray {
        val start = if (history.length() > MAX_SESSION_TURNS)
            history.length() - MAX_SESSION_TURNS else 0

        for (i in start until history.length()) {
            val turn = history.getJSONObject(i)
            if (turn.optString("role") != "user") continue
            val parts = turn.optJSONArray("parts") ?: continue

            // Must have at least one text part (inlineData stripped already)
            val hasText = (0 until parts.length()).any { j -> parts.getJSONObject(j).has("text") }
            // Must NOT have any functionResponse parts — those turns are mid-exchange
            val hasFunctionResponse = (0 until parts.length()).any { j ->
                parts.getJSONObject(j).has("functionResponse")
            }

            if (!hasText || hasFunctionResponse) continue

            // Found a clean pure-text user turn — valid anchor for a new session
            val trimmed = JSONArray()
            for (k in i until history.length()) trimmed.put(history.get(k))
            return trimmed
        }
        // No valid anchor found — discard and start fresh
        return JSONArray()
    }

    private suspend fun executeToolCall(toolCall: ToolCall): ToolResult {
        DebugLog.d(TAG, "Executing tool: ${toolCall.name.id} args=${toolCall.args}")

        return when (toolCall.name) {
            ToolName.TAKE_SCREENSHOT -> {
                val bitmap = screenshotManager.takeScreenshot()
                if (bitmap != null) {
                    // Feedback quality — model uses this to verify actions, not for initial planning
                    val base64 = ScreenshotManager.bitmapToBase64Feedback(screenshotManager.prepareForApi(bitmap).first)
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

            ToolName.WAIT -> {
                val seconds = toolCall.intArg("seconds") ?: 2
                actionExecutor.wait(seconds)
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

private fun ToolCall.intArg(key: String): Int? = args[key]?.toIntOrNull()

private fun ToolCall.stringArg(key: String): String? = args[key]
