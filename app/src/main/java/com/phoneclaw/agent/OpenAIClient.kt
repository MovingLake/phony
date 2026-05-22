package com.phoneclaw.agent

import android.graphics.Bitmap
import com.phoneclaw.data.OpenAIModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "OpenAIClient"
private const val BASE_URL = "https://api.openai.com/v1/chat/completions"
private const val MAX_SESSION_TURNS = 8

class OpenAIClient(
    private val apiKey: String,
    private val model: OpenAIModel = OpenAIModel.GPT_4O,
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
                DebugLog.w(TAG, "Session history ends with user — inserting assistant bridge")
                messages.put(JSONObject()
                    .put("role", "assistant")
                    .put("content", "…"))
            }
        }

        val isFollowUp = sessionHistory != null && sessionHistory.length() > 0
        messages.put(userMessageWithImage(
            bitmap = preparedInitial,
            text = if (isFollowUp)
                "Follow-up: \"$userCommand\"\nCoordinate system: screenshot is ${imgW}x${imgH}px."
            else
                "User said: \"$userCommand\"\n\n" +
                "If this is a general question — answer in plain text, no tools.\n" +
                "If this requires acting on the phone — use tools. " +
                "Coordinate system: screenshot is ${imgW}x${imgH}px."
        ))

        var iterations = 0
        val maxIterations = 20
        var nudgeCount = 0
        val maxNudges = 4

        while (iterations < maxIterations) {
            iterations++
            DebugLog.d(TAG, "[$iterations] Calling OpenAI (messages=${messages.length()})…")

            val responseJson = callApi(messages)
            val choice = responseJson.getJSONArray("choices").getJSONObject(0)
            val message = choice.getJSONObject("message")
            val finishReason = choice.optString("finish_reason")

            // Store assistant message verbatim (preserves tool_calls with IDs)
            messages.put(message)

            val toolCalls = message.optJSONArray("tool_calls")
            val contentText = message.optString("content", "").trim()

            if (contentText.isNotBlank()) {
                DebugLog.d(TAG, "[$iterations] Model text: ${contentText.take(120)}")
            }
            DebugLog.d(TAG, "[$iterations] finish_reason=$finishReason, tool_calls=${toolCalls?.length() ?: 0}")

            if (toolCalls == null || toolCalls.length() == 0) {
                if (contentText.isNotBlank() && AgentPrompts.looksLikeReasoning(contentText) && nudgeCount < maxNudges) {
                    nudgeCount++
                    DebugLog.d(TAG, "[$iterations] Reasoning text — nudging ($nudgeCount/$maxNudges)")
                    messages.put(JSONObject().put("role", "user").put("content", "Continue."))
                    continue
                }
                DebugLog.d(TAG, "[$iterations] No tool calls — done")
                val safeText = if (AgentPrompts.isSystemEcho(contentText)) {
                    DebugLog.w(TAG, "[$iterations] System-echo suppressed")
                    ""
                } else contentText
                return Pair(safeText.ifBlank { "Task completed." }, cleanHistory(messages))
            }

            nudgeCount = 0

            // Execute all tool calls; tool results go as separate role=tool messages
            var taskComplete = false
            var completionSummary = ""

            for (i in 0 until toolCalls.length()) {
                val call = toolCalls.getJSONObject(i)
                val callId = call.optString("id")
                val fn = call.getJSONObject("function")
                val name = fn.optString("name")

                val toolName = ToolName.entries.firstOrNull { it.id == name }
                if (toolName == null) {
                    DebugLog.w(TAG, "Unknown tool: $name")
                    messages.put(JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", callId)
                        .put("content", "Unknown tool: $name"))
                    continue
                }

                // OpenAI arguments field is a JSON string
                val argsStr = fn.optString("arguments", "{}")
                val argsJson = try { JSONObject(argsStr) } catch (_: Exception) { JSONObject() }
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

                messages.put(JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", callId)
                    .put("content", toolResult.result))

                if (toolName == ToolName.TASK_COMPLETE) {
                    taskComplete = true
                    completionSummary = toolResult.result
                }
            }

            // Attach a fresh screenshot as a new user message so the model can see what changed
            val freshBitmap = screenshotManager?.takeScreenshot()
            if (freshBitmap != null) {
                val (prepared, _) = screenshotManager.prepareForApi(freshBitmap)
                messages.put(userMessageWithImage(
                    bitmap = prepared,
                    text = "Updated screen (${prepared.width}x${prepared.height}px).",
                    quality = "feedback"
                ))
                DebugLog.d(TAG, "[$iterations] Attached fresh screenshot (${prepared.width}x${prepared.height})")
            }

            if (taskComplete) {
                DebugLog.d(TAG, "[$iterations] task_complete: $completionSummary")
                // End with an assistant text turn so history is valid for resumption
                messages.put(JSONObject().put("role", "assistant").put("content", completionSummary))
                return Pair(completionSummary, cleanHistory(messages))
            }
        }

        DebugLog.w(TAG, "Hit max iterations ($maxIterations)")
        val stopMsg = "Task stopped after $maxIterations steps."
        messages.put(JSONObject().put("role", "assistant").put("content", stopMsg))
        return Pair(stopMsg, cleanHistory(messages))
    }

    // ── History management ────────────────────────────────────────────────

    private fun cleanHistory(messages: JSONArray): JSONArray = trimHistory(stripImages(messages))

    /** Remove image_url content blocks from user messages before storing. */
    private fun stripImages(messages: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until messages.length()) {
            val msg = messages.getJSONObject(i)
            val contentArr = msg.optJSONArray("content")
            if (contentArr != null) {
                val newContent = JSONArray()
                for (j in 0 until contentArr.length()) {
                    val item = contentArr.getJSONObject(j)
                    if (item.optString("type") != "image_url") newContent.put(item)
                }
                if (newContent.length() > 0) {
                    val newMsg = JSONObject().put("role", msg.optString("role")).put("content", newContent)
                    if (msg.has("tool_calls")) newMsg.put("tool_calls", msg.optJSONArray("tool_calls"))
                    out.put(newMsg)
                }
            } else {
                // String content or tool messages — keep as-is
                out.put(msg)
            }
        }
        return out
    }

    /**
     * Keep last MAX_SESSION_TURNS messages, anchored at a clean user message.
     * For OpenAI, a valid anchor is a user message that is NOT preceded by a
     * "tool" role message — i.e. initial command messages, not screenshot updates.
     * Must end with assistant turn.
     */
    private fun trimHistory(messages: JSONArray): JSONArray {
        val start = if (messages.length() > MAX_SESSION_TURNS)
            messages.length() - MAX_SESSION_TURNS else 0

        for (i in start until messages.length()) {
            val msg = messages.getJSONObject(i)
            if (msg.optString("role") != "user") continue

            // Check predecessor — skip if it's a tool message (mid-exchange screenshot update)
            if (i > 0) {
                val prev = messages.getJSONObject(i - 1)
                if (prev.optString("role") == "tool") continue
            }

            // Must have text content (not just an image)
            val contentArr = msg.optJSONArray("content")
            val hasText = contentArr != null && (0 until contentArr.length()).any { j ->
                contentArr.getJSONObject(j).optString("type") == "text"
            }
            if (!hasText) continue

            val trimmed = JSONArray()
            for (k in i until messages.length()) trimmed.put(messages.get(k))
            return trimmed
        }
        return JSONArray()
    }

    // ── API call ──────────────────────────────────────────────────────────

    private suspend fun callApi(messages: JSONArray): JSONObject = withContext(Dispatchers.IO) {
        // Prepend system message — not in history, always first
        val allMessages = JSONArray()
        allMessages.put(JSONObject().put("role", "system").put("content", AgentPrompts.SYSTEM_PROMPT))
        for (i in 0 until messages.length()) allMessages.put(messages.get(i))

        val body = JSONObject()
            .put("model", model.modelId)
            .put("max_tokens", 4096)
            .put("messages", allMessages)
            .put("tools", AgentPrompts.buildOpenAIToolsJson())

        val request = Request.Builder()
            .url(BASE_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val resp = http.newCall(request).execute()
        val bodyStr = resp.body?.string() ?: throw Exception("Empty API response")

        if (!resp.isSuccessful) {
            DebugLog.e(TAG, "API ${resp.code}: ${bodyStr.take(300)}")
            throw Exception("OpenAI API ${resp.code}: ${bodyStr.take(200)}")
        }

        JSONObject(bodyStr)
    }

    // ── JSON helpers ──────────────────────────────────────────────────────

    private fun userMessageWithImage(bitmap: Bitmap, text: String, quality: String = "full"): JSONObject {
        val base64 = if (quality == "feedback")
            ScreenshotManager.bitmapToBase64Feedback(bitmap)
        else
            ScreenshotManager.bitmapToBase64(bitmap)

        return JSONObject().put("role", "user").put("content", JSONArray()
            .put(JSONObject()
                .put("type", "image_url")
                .put("image_url", JSONObject()
                    .put("url", "data:image/jpeg;base64,$base64")
                    .put("detail", "high")))
            .put(JSONObject().put("type", "text").put("text", text)))
    }
}
