package com.phoneclaw.agent

import android.graphics.Bitmap
import com.phoneclaw.data.ClaudeModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "ClaudeClient"
private const val BASE_URL = "https://api.anthropic.com/v1/messages"
private const val ANTHROPIC_VERSION = "2023-06-01"
private const val MAX_SESSION_TURNS = 8

class ClaudeClient(
    private val apiKey: String,
    private val model: ClaudeModel = ClaudeModel.SONNET_4_6,
    private val screenshotManager: ScreenshotManager? = null,
) : AIClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun runTask(
        userCommand: String,
        initialScreenshot: Bitmap,
        sessionHistory: JSONArray?,
        onToolCall: suspend (ToolCall) -> ToolResult,
        onStatusUpdate: (String) -> Unit,
    ): Pair<String, JSONArray> {
        DebugLog.d(TAG, "── Task start ──────────────────")
        DebugLog.d(TAG, "Model: ${model.modelId} | Command: $userCommand")

        val (preparedInitial, imgScale) = screenshotManager?.prepareForApi(initialScreenshot)
            ?: Pair(initialScreenshot, 1.0f)
        val imgW = preparedInitial.width
        val imgH = preparedInitial.height
        DebugLog.d(TAG, "Image: ${imgW}x${imgH} (scale=$imgScale)")

        // Clone session history or start fresh
        val messages = if (sessionHistory != null && sessionHistory.length() > 0) {
            DebugLog.d(TAG, "Resuming session (${sessionHistory.length()} prior messages)")
            JSONArray(sessionHistory.toString())
        } else {
            JSONArray()
        }

        // Safety net: must end with assistant turn for valid continuation
        if (messages.length() > 0) {
            val last = messages.getJSONObject(messages.length() - 1)
            if (last.optString("role") == "user") {
                DebugLog.w(TAG, "Session history ends with user — inserting model bridge")
                messages.put(JSONObject()
                    .put("role", "assistant")
                    .put("content", JSONArray().put(JSONObject()
                        .put("type", "text").put("text", "…"))))
            }
        }

        val isFollowUp = sessionHistory != null && sessionHistory.length() > 0
        messages.put(userMessage {
            addImage(preparedInitial)
            addText(
                if (isFollowUp)
                    "Follow-up: \"$userCommand\"\nCoordinate system: screenshot is ${imgW}x${imgH}px."
                else
                    "User said: \"$userCommand\"\n\n" +
                    "If this is a general question — answer in plain text, no tools.\n" +
                    "If this requires acting on the phone — use tools. " +
                    "Coordinate system: screenshot is ${imgW}x${imgH}px."
            )
        })

        var iterations = 0
        val maxIterations = 20
        var nudgeCount = 0
        val maxNudges = 4

        while (iterations < maxIterations) {
            iterations++
            DebugLog.d(TAG, "[$iterations] Calling Claude (messages=${messages.length()})…")

            val responseJson = callApi(messages)
            val content = responseJson.getJSONArray("content")
            val stopReason = responseJson.optString("stop_reason")

            // Store assistant turn verbatim
            messages.put(JSONObject().put("role", "assistant").put("content", content))

            val toolUseBlocks = mutableListOf<JSONObject>()
            val textParts = mutableListOf<String>()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                when (block.optString("type")) {
                    "tool_use" -> toolUseBlocks.add(block)
                    "text" -> {
                        val t = block.optString("text")
                        if (t.isNotBlank()) textParts.add(t)
                    }
                }
            }

            val responseText = textParts.joinToString(" ").trim()
            if (responseText.isNotBlank()) {
                DebugLog.d(TAG, "[$iterations] Model text: ${responseText.take(120)}")
            }
            DebugLog.d(TAG, "[$iterations] Tool calls: ${toolUseBlocks.size} — ${toolUseBlocks.map { it.optString("name") }}")

            if (toolUseBlocks.isEmpty()) {
                if (responseText.isNotBlank() && AgentPrompts.looksLikeReasoning(responseText) && nudgeCount < maxNudges) {
                    nudgeCount++
                    DebugLog.d(TAG, "[$iterations] Reasoning text — nudging ($nudgeCount/$maxNudges)")
                    messages.put(userMessage { addText("Continue.") })
                    continue
                }
                DebugLog.d(TAG, "[$iterations] No tool calls — done")
                val safeText = if (AgentPrompts.isSystemEcho(responseText)) {
                    DebugLog.w(TAG, "[$iterations] System-echo suppressed")
                    ""
                } else responseText
                return Pair(safeText.ifBlank { "Task completed." }, cleanHistory(messages))
            }

            nudgeCount = 0

            // Execute all tool calls and collect results
            val toolResultContent = JSONArray()
            var taskComplete = false
            var completionSummary = ""

            for (block in toolUseBlocks) {
                val name = block.optString("name")
                val toolName = ToolName.entries.firstOrNull { it.id == name }
                if (toolName == null) {
                    DebugLog.w(TAG, "Unknown tool: $name")
                    toolResultContent.put(JSONObject()
                        .put("type", "tool_result")
                        .put("tool_use_id", block.optString("id"))
                        .put("content", "Unknown tool: $name"))
                    continue
                }

                val argsJson = block.optJSONObject("input") ?: JSONObject()
                val args = argsJson.keys().asSequence().associateWith { argsJson.optString(it) }
                val scaledArgs = if (imgScale != 1.0f) AgentPrompts.scaleCoords(args, imgScale) else args

                DebugLog.d(TAG, "[$iterations] → $name($scaledArgs)")
                onStatusUpdate(AgentPrompts.statusForTool(toolName))

                val toolResult = try {
                    onToolCall(ToolCall(name = toolName, args = scaledArgs))
                } catch (e: Exception) {
                    DebugLog.e(TAG, "Tool $name threw", e)
                    ToolResult(toolName, false, "Exception: ${e.message}")
                }

                DebugLog.d(TAG, "[$iterations] ← $name: ok=${toolResult.success} — ${toolResult.result.take(80)}")

                toolResultContent.put(JSONObject()
                    .put("type", "tool_result")
                    .put("tool_use_id", block.optString("id"))
                    .put("content", toolResult.result))

                if (toolName == ToolName.TASK_COMPLETE) {
                    taskComplete = true
                    completionSummary = toolResult.result
                }
            }

            // Build next user message: tool results + fresh screenshot
            val nextContent = JSONArray()
            for (i in 0 until toolResultContent.length()) nextContent.put(toolResultContent.getJSONObject(i))

            val freshBitmap = screenshotManager?.takeScreenshot()
            if (freshBitmap != null) {
                val (prepared, _) = screenshotManager.prepareForApi(freshBitmap)
                nextContent.put(JSONObject()
                    .put("type", "image")
                    .put("source", JSONObject()
                        .put("type", "base64")
                        .put("media_type", "image/jpeg")
                        .put("data", ScreenshotManager.bitmapToBase64Feedback(prepared))))
                nextContent.put(JSONObject()
                    .put("type", "text")
                    .put("text", "Updated screen (${prepared.width}x${prepared.height}px)."))
            }

            messages.put(JSONObject().put("role", "user").put("content", nextContent))

            if (taskComplete) {
                DebugLog.d(TAG, "[$iterations] task_complete: $completionSummary")
                // Close with a terminal assistant text turn so history ends on assistant
                messages.put(JSONObject()
                    .put("role", "assistant")
                    .put("content", JSONArray().put(JSONObject()
                        .put("type", "text").put("text", completionSummary))))
                return Pair(completionSummary, cleanHistory(messages))
            }
        }

        DebugLog.w(TAG, "Hit max iterations ($maxIterations)")
        val stopMsg = "Task stopped after $maxIterations steps."
        messages.put(JSONObject()
            .put("role", "assistant")
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", stopMsg))))
        return Pair(stopMsg, cleanHistory(messages))
    }

    // ── History management ────────────────────────────────────────────────

    private fun cleanHistory(messages: JSONArray): JSONArray = trimHistory(stripImages(messages))

    /** Remove image blocks from all messages before storing in session. */
    private fun stripImages(messages: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until messages.length()) {
            val msg = messages.getJSONObject(i)
            val content = msg.optJSONArray("content") ?: continue
            val newContent = JSONArray()
            for (j in 0 until content.length()) {
                val block = content.getJSONObject(j)
                if (block.optString("type") != "image") newContent.put(block)
            }
            if (newContent.length() > 0) {
                out.put(JSONObject().put("role", msg.getString("role")).put("content", newContent))
            }
        }
        return out
    }

    /**
     * Keep last MAX_SESSION_TURNS messages, anchored at a clean user message
     * (text only, no tool_result blocks — i.e. an initial command turn, not
     * a mid-exchange "Updated screen" turn).
     * History must end with an assistant turn.
     */
    private fun trimHistory(messages: JSONArray): JSONArray {
        val start = if (messages.length() > MAX_SESSION_TURNS)
            messages.length() - MAX_SESSION_TURNS else 0

        for (i in start until messages.length()) {
            val msg = messages.getJSONObject(i)
            if (msg.optString("role") != "user") continue
            val content = msg.optJSONArray("content") ?: continue

            val hasText = (0 until content.length()).any { j ->
                content.getJSONObject(j).optString("type") == "text"
            }
            val hasToolResult = (0 until content.length()).any { j ->
                content.getJSONObject(j).optString("type") == "tool_result"
            }
            if (!hasText || hasToolResult) continue

            val trimmed = JSONArray()
            for (k in i until messages.length()) trimmed.put(messages.get(k))
            return trimmed
        }
        return JSONArray()
    }

    // ── API call ──────────────────────────────────────────────────────────

    private suspend fun callApi(messages: JSONArray): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", model.modelId)
            .put("max_tokens", 4096)
            .put("system", AgentPrompts.SYSTEM_PROMPT)
            .put("messages", messages)
            .put("tools", AgentPrompts.buildClaudeToolsJson())

        val request = Request.Builder()
            .url(BASE_URL)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val resp = http.newCall(request).execute()
        val bodyStr = resp.body?.string() ?: throw Exception("Empty API response")

        if (!resp.isSuccessful) {
            DebugLog.e(TAG, "API ${resp.code}: ${bodyStr.take(300)}")
            throw Exception("Claude API ${resp.code}: ${bodyStr.take(200)}")
        }

        JSONObject(bodyStr)
    }

    // ── JSON helpers ──────────────────────────────────────────────────────

    private fun userMessage(block: JSONArray.() -> Unit): JSONObject =
        JSONObject().put("role", "user").put("content", JSONArray().apply(block))

    private fun JSONArray.addImage(bitmap: Bitmap) = put(JSONObject()
        .put("type", "image")
        .put("source", JSONObject()
            .put("type", "base64")
            .put("media_type", "image/jpeg")
            .put("data", ScreenshotManager.bitmapToBase64(bitmap))))

    private fun JSONArray.addText(text: String) = put(JSONObject()
        .put("type", "text").put("text", text))
}
