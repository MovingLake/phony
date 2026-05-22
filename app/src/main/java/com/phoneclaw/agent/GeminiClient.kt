package com.phoneclaw.agent

import android.graphics.Bitmap
import com.phoneclaw.data.GeminiModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "GeminiClient"
private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
private const val MAX_SESSION_TURNS = 8

/**
 * Calls the Gemini REST API directly with OkHttp.
 *
 * Why not the SDK?
 *   gemini-3.5-flash embeds a `thought_signature` inside every functionCall part.
 *   When echoing history for the next turn the API requires that signature to be
 *   present. SDK 0.9.0 silently drops it → 400 on every follow-up.
 *   By calling REST directly we set thinkingBudget=0 (no thought signatures).
 */
class GeminiClient(
    private val apiKey: String,
    private val model: GeminiModel = GeminiModel.FLASH_3_5,
    private val screenshotManager: ScreenshotManager? = null,
) : AIClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val endpoint get() = "$BASE_URL/${model.modelId}:generateContent?key=$apiKey"

    override suspend fun runTask(
        userCommand: String,
        initialScreenshot: Bitmap,
        sessionHistory: JSONArray?,
        onToolCall: suspend (ToolCall) -> ToolResult,
        onStatusUpdate: (String) -> Unit,
    ): Pair<String, JSONArray> {
        DebugLog.d(TAG, "── Task start ──────────────────")
        DebugLog.d(TAG, "Model: ${model.modelId} | Command: $userCommand")

        val screenW = initialScreenshot.width
        val screenH = initialScreenshot.height
        val (preparedInitial, imgScale) = screenshotManager?.prepareForApi(initialScreenshot)
            ?: Pair(initialScreenshot, 1.0f)
        val imgW = preparedInitial.width
        val imgH = preparedInitial.height
        DebugLog.d(TAG, "Screen: ${screenW}x${screenH} → image: ${imgW}x${imgH} (scale=$imgScale)")

        val history = if (sessionHistory != null && sessionHistory.length() > 0) {
            DebugLog.d(TAG, "Resuming session (${sessionHistory.length()} prior turns)")
            JSONArray(sessionHistory.toString())
        } else {
            JSONArray()
        }

        if (history.length() > 0) {
            val last = history.getJSONObject(history.length() - 1)
            if (last.optString("role") == "user") {
                DebugLog.w(TAG, "Session history ends with user turn — inserting model bridge")
                history.put(JSONObject()
                    .put("role", "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", "…"))))
            }
        }

        val isFollowUp = sessionHistory != null && sessionHistory.length() > 0
        history.put(userTurn {
            addScreenshot(preparedInitial)
            addText(
                if (isFollowUp) {
                    "Follow-up: \"$userCommand\"\n" +
                    "Coordinate system: screenshot is ${imgW}x${imgH}px; use those coordinates for all gestures."
                } else {
                    "User said: \"$userCommand\"\n\n" +
                    "If this is a general question (joke, fact, advice, etc.) — just answer it in plain text, no tools.\n" +
                    "If this requires acting on the phone — use the tools. " +
                    "Coordinate system: screenshot is ${imgW}x${imgH}px; use those coordinates for all gestures."
                }
            )
        })

        var iterations = 0
        val maxIterations = 20
        var nudgeCount = 0
        val maxNudges = 4

        while (iterations < maxIterations) {
            iterations++
            DebugLog.d(TAG, "[$iterations] Calling Gemini (history=${history.length()} turns)…")

            val responseJson = callApi(buildRequest(history))

            val candidate = responseJson.getJSONArray("candidates").getJSONObject(0)
            val modelContent = candidate.getJSONObject("content")
            history.put(modelContent)

            val parts = modelContent.getJSONArray("parts")

            val functionCalls = mutableListOf<JSONObject>()
            val textParts = mutableListOf<String>()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                when {
                    p.has("functionCall") -> functionCalls.add(p.getJSONObject("functionCall"))
                    p.has("text") -> {
                        val t = p.getString("text")
                        if (t.isNotBlank()) textParts.add(t)
                    }
                }
            }

            val responseText = textParts.joinToString(" ").trim()
            if (responseText.isNotBlank()) {
                DebugLog.d(TAG, "[$iterations] Model text: ${responseText.take(120)}")
            }
            DebugLog.d(TAG, "[$iterations] Tool calls: ${functionCalls.size} — ${functionCalls.map { it.getString("name") }}")

            if (functionCalls.isEmpty()) {
                if (responseText.isNotBlank() && AgentPrompts.looksLikeReasoning(responseText) && nudgeCount < maxNudges) {
                    nudgeCount++
                    DebugLog.d(TAG, "[$iterations] Reasoning text — nudging ($nudgeCount/$maxNudges): ${responseText.take(80)}")
                    history.put(userTurn { addText("Continue.") })
                    continue
                }
                DebugLog.d(TAG, "[$iterations] No tool calls — done")
                val safeText = if (AgentPrompts.isSystemEcho(responseText)) {
                    DebugLog.w(TAG, "[$iterations] System-echo detected — suppressing: ${responseText.take(80)}")
                    ""
                } else responseText
                return Pair(safeText.ifBlank { "Task completed." }, cleanHistory(history))
            }

            val resultParts = JSONArray()
            var taskComplete = false
            var completionSummary = ""

            for (call in functionCalls) {
                val name = call.getString("name")
                val args: Map<String, String?> = if (call.has("args")) {
                    val argsObj = call.getJSONObject("args")
                    argsObj.keys().asSequence().associateWith { argsObj.optString(it) }
                } else emptyMap()

                val toolName = ToolName.entries.firstOrNull { it.id == name }
                if (toolName == null) {
                    DebugLog.w(TAG, "Unknown tool: $name")
                    resultParts.put(functionResponse(name, JSONObject().put("error", "Unknown tool")))
                    continue
                }

                DebugLog.d(TAG, "[$iterations] → $name($args)")
                onStatusUpdate(AgentPrompts.statusForTool(toolName))

                val scaledArgs = if (imgScale != 1.0f) AgentPrompts.scaleCoords(args, imgScale) else args

                val toolResult = try {
                    onToolCall(ToolCall(name = toolName, args = scaledArgs))
                } catch (e: Exception) {
                    DebugLog.e(TAG, "Tool $name threw", e)
                    ToolResult(toolName, false, "Exception: ${e.message}")
                }

                DebugLog.d(TAG, "[$iterations] ← $name: ok=${toolResult.success} — ${toolResult.result.take(80)}")

                resultParts.put(
                    functionResponse(name, JSONObject()
                        .put("success", toolResult.success)
                        .put("result", toolResult.result))
                )

                if (toolName == ToolName.TASK_COMPLETE) {
                    taskComplete = true
                    completionSummary = toolResult.result
                }
            }

            nudgeCount = 0

            if (taskComplete) {
                DebugLog.d(TAG, "[$iterations] task_complete: $completionSummary")
                history.put(JSONObject().put("role", "user").put("parts", resultParts))
                history.put(JSONObject()
                    .put("role", "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", completionSummary))))
                return Pair(completionSummary, cleanHistory(history))
            }

            val nextUserParts = mutableListOf<JSONObject>()
            for (i in 0 until resultParts.length()) nextUserParts.add(resultParts.getJSONObject(i))

            val freshBitmap = screenshotManager?.takeScreenshot()
            if (freshBitmap != null) {
                val (preparedFresh, _) = screenshotManager.prepareForApi(freshBitmap)
                nextUserParts.add(inlineFeedbackImage(preparedFresh))
                nextUserParts.add(textPart("Updated screen (${preparedFresh.width}x${preparedFresh.height}px)."))
                DebugLog.d(TAG, "[$iterations] Attached fresh screenshot (${preparedFresh.width}x${preparedFresh.height})")
            }

            history.put(JSONObject()
                .put("role", "user")
                .put("parts", JSONArray(nextUserParts)))

            DebugLog.d(TAG, "[$iterations] Looping with updated history…")
        }

        DebugLog.w(TAG, "Hit max iterations ($maxIterations)")
        val stopMsg = "Task stopped after $maxIterations steps."
        history.put(JSONObject()
            .put("role", "model")
            .put("parts", JSONArray().put(JSONObject().put("text", stopMsg))))
        return Pair(stopMsg, cleanHistory(history))
    }

    // ── History management ────────────────────────────────────────────────

    private fun cleanHistory(history: JSONArray): JSONArray = trimHistory(stripImages(history))

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

    private fun trimHistory(history: JSONArray): JSONArray {
        val start = if (history.length() > MAX_SESSION_TURNS)
            history.length() - MAX_SESSION_TURNS else 0

        for (i in start until history.length()) {
            val turn = history.getJSONObject(i)
            if (turn.optString("role") != "user") continue
            val parts = turn.optJSONArray("parts") ?: continue

            val hasText = (0 until parts.length()).any { j -> parts.getJSONObject(j).has("text") }
            val hasFunctionResponse = (0 until parts.length()).any { j ->
                parts.getJSONObject(j).has("functionResponse")
            }
            if (!hasText || hasFunctionResponse) continue

            val trimmed = JSONArray()
            for (k in i until history.length()) trimmed.put(history.get(k))
            return trimmed
        }
        return JSONArray()
    }

    // ── Request building ──────────────────────────────────────────────────

    private fun buildRequest(history: JSONArray): JSONObject = JSONObject()
        .put("system_instruction", JSONObject()
            .put("parts", JSONArray().put(JSONObject().put("text", AgentPrompts.SYSTEM_PROMPT))))
        .put("contents", history)
        .put("tools", JSONArray().put(JSONObject().put("function_declarations", buildToolsJson())))
        .put("generationConfig", JSONObject()
            .put("temperature", 0.2)
            .put("maxOutputTokens", 4096)
            .put("thinkingConfig", JSONObject().put("thinkingBudget", 0)))

    private suspend fun callApi(body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val resp = http.newCall(request).execute()
        val bodyStr = resp.body?.string() ?: throw Exception("Empty API response")

        if (!resp.isSuccessful) {
            DebugLog.e(TAG, "API ${resp.code}: ${bodyStr.take(300)}")
            throw Exception("Gemini API ${resp.code}: ${bodyStr.take(200)}")
        }

        JSONObject(bodyStr)
    }

    // ── JSON helpers ──────────────────────────────────────────────────────

    private fun userTurn(block: JSONArray.() -> Unit): JSONObject {
        val parts = JSONArray().apply(block)
        return JSONObject().put("role", "user").put("parts", parts)
    }

    private fun JSONArray.addScreenshot(bitmap: Bitmap) = put(inlineImage(bitmap))
    private fun JSONArray.addText(text: String) = put(textPart(text))

    private fun inlineImage(bitmap: Bitmap): JSONObject = JSONObject()
        .put("inlineData", JSONObject()
            .put("mimeType", "image/jpeg")
            .put("data", ScreenshotManager.bitmapToBase64(bitmap)))

    private fun inlineFeedbackImage(bitmap: Bitmap): JSONObject = JSONObject()
        .put("inlineData", JSONObject()
            .put("mimeType", "image/jpeg")
            .put("data", ScreenshotManager.bitmapToBase64Feedback(bitmap)))

    private fun textPart(text: String): JSONObject = JSONObject().put("text", text)

    private fun functionResponse(name: String, response: JSONObject): JSONObject = JSONObject()
        .put("functionResponse", JSONObject()
            .put("name", name)
            .put("response", response))

    // ── Tool declarations (Gemini format — uppercase types) ───────────────

    private fun buildToolsJson(): JSONArray = JSONArray().apply {
        put(toolDef(ToolName.TAKE_SCREENSHOT, "Capture the current screen. Call this when you need to see the current state."))
        put(toolDef(ToolName.TAP, "Tap at pixel coordinates on screen.",
            intParam("x", "X coordinate"), intParam("y", "Y coordinate")))
        put(toolDef(ToolName.LONG_PRESS, "Long press at pixel coordinates.",
            intParam("x", "X coordinate"), intParam("y", "Y coordinate")))
        put(toolDef(ToolName.SWIPE, "Swipe between two points.",
            intParam("startX"), intParam("startY"), intParam("endX"), intParam("endY"),
            intParam("durationMs", "Duration ms, default 300")))
        put(toolDef(ToolName.TYPE_TEXT, "Type text into the focused field.",
            strParam("text", "Text to type")))
        put(toolDef(ToolName.OPEN_APP, "Open an app by natural name (e.g. 'Spotify', 'Maps', 'Calendar').",
            strParam("app_name", "Natural app name")))
        put(toolDef(ToolName.PRESS_BACK, "Press Android back button."))
        put(toolDef(ToolName.PRESS_HOME, "Go to home screen."))
        put(toolDef(ToolName.PRESS_RECENT_APPS, "Open recent apps view."))
        put(toolDef(ToolName.SCROLL, "Scroll in a direction.",
            strParam("direction", "UP, DOWN, LEFT, or RIGHT"),
            intParam("x", "Center X of scroll area"),
            intParam("y", "Center Y of scroll area")))
        put(toolDef(ToolName.FIND_AND_CLICK_TEXT,
            "Find a UI element by text and click it — more reliable than tap().",
            strParam("text", "Text of the element to click")))
        put(toolDef(ToolName.GET_SCREEN_TEXT, "Extract all visible text from the accessibility tree."))
        put(toolDef(ToolName.SPEAK_TO_USER, "Speak a message to the user via TTS.",
            strParam("message", "What to say")))
        put(toolDef(ToolName.TASK_COMPLETE, "Signal task is done.",
            strParam("summary", "Spoken summary to read back to user")))
        put(toolDef(ToolName.WAIT, "Pause for N seconds — use after authentication prompts or to let animations finish.",
            intParam("seconds", "Seconds to wait (1–10)")))
    }

    private fun toolDef(tool: ToolName, description: String, vararg params: Pair<String, JSONObject>): JSONObject {
        val obj = JSONObject().put("name", tool.id).put("description", description)
        if (params.isNotEmpty()) {
            val props = JSONObject()
            val required = JSONArray()
            for ((name, schema) in params) {
                props.put(name, schema)
                if (schema.optBoolean("_required", true)) required.put(name)
            }
            obj.put("parameters", JSONObject()
                .put("type", "OBJECT")
                .put("properties", props)
                .put("required", required))
        }
        return obj
    }

    private fun intParam(name: String, desc: String = name): Pair<String, JSONObject> =
        name to JSONObject().put("type", "INTEGER").put("description", desc)

    private fun strParam(name: String, desc: String = name): Pair<String, JSONObject> =
        name to JSONObject().put("type", "STRING").put("description", desc)
}
