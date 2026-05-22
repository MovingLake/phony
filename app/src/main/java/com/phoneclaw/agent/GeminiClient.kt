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

/**
 * Calls the Gemini REST API directly with OkHttp.
 *
 * Why not the SDK?
 *   gemini-3.5-flash (and other thinking models) embed a `thought_signature`
 *   inside every functionCall part. When echoing history for the next turn,
 *   the API requires that signature to be present. SDK 0.9.0 doesn't have a
 *   thoughtSignature field in its FunctionCallPart data class, so the value is
 *   silently dropped on serialisation and the API returns 400 every time.
 *
 *   By calling the REST API ourselves we set thinkingConfig.thinkingBudget = 0,
 *   which disables thinking-chain generation while keeping all other model
 *   capabilities. No thought_signatures → function calling works perfectly.
 *
 * Bonus: after every round of tool calls we automatically attach a fresh
 * screenshot to the next user message, giving Gemini visual feedback of what
 * each action produced without the model needing to explicitly call
 * take_screenshot every time.
 */
class GeminiClient(
    private val apiKey: String,
    private val model: GeminiModel = GeminiModel.FLASH_3_5,
    private val screenshotManager: ScreenshotManager? = null,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val endpoint get() =
        "$BASE_URL/${model.modelId}:generateContent?key=$apiKey"

    /**
     * Run a full agentic task.
     * Returns (finalSpokenSummary, updatedHistory).
     * Pass the returned history back as [sessionHistory] on the next call to carry
     * conversational context across FAB taps within the same session.
     */
    suspend fun runTask(
        userCommand: String,
        initialScreenshot: Bitmap,
        sessionHistory: JSONArray? = null,
        onToolCall: suspend (ToolCall) -> ToolResult,
        onStatusUpdate: (String) -> Unit,
    ): Pair<String, JSONArray> {
        DebugLog.d(TAG, "── Task start ──────────────────")
        DebugLog.d(TAG, "Model: ${model.modelId} | Command: $userCommand")

        // Scale the screenshot for the API and track the factor so we can
        // reverse-translate Gemini's image-space coordinates to real screen coords.
        val screenW = initialScreenshot.width
        val screenH = initialScreenshot.height
        val (preparedInitial, imgScale) = screenshotManager?.prepareForApi(initialScreenshot)
            ?: Pair(initialScreenshot, 1.0f)
        val imgW = preparedInitial.width
        val imgH = preparedInitial.height
        DebugLog.d(TAG, "Screen: ${screenW}x${screenH} → image: ${imgW}x${imgH} (scale=$imgScale)")

        // Clone existing session history (deep copy via JSON round-trip) or start fresh.
        val history = if (sessionHistory != null && sessionHistory.length() > 0) {
            DebugLog.d(TAG, "Resuming session (${sessionHistory.length()} prior turns)")
            JSONArray(sessionHistory.toString())
        } else {
            JSONArray()
        }

        // Safety net: Gemini requires strict user/model alternation.
        // If session history ends with a user turn (shouldn't happen after our task_complete
        // fix, but defensive), insert a brief model bridge turn before the new user message.
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
        var nudgeCount = 0    // consecutive reasoning-only turns; reset after each tool-call batch
        val maxNudges = 4     // per-step budget; generous so the model can recover at any point

        while (iterations < maxIterations) {
            iterations++
            DebugLog.d(TAG, "[$iterations] Calling Gemini (history=${history.length()} turns)…")

            val responseJson = callApi(buildRequest(history))

            // Store the raw model content verbatim — if thinking were ever re-enabled
            // the thought_signature inside the parts would be preserved here.
            val candidate = responseJson.getJSONArray("candidates").getJSONObject(0)
            val modelContent = candidate.getJSONObject("content")
            history.put(modelContent)

            val parts = modelContent.getJSONArray("parts")

            // Separate text vs function calls
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
                // Nudge the model whenever it emits reasoning/meta text instead of tool calls.
                // No hasExecutedToolCalls guard — the model can stall even on the very first turn
                // (e.g. emitting "(Note: coordinates are scaled…) Let's think…" before any action).
                if (responseText.isNotBlank() && looksLikeReasoning(responseText) && nudgeCount < maxNudges) {
                    nudgeCount++
                    DebugLog.d(TAG, "[$iterations] Reasoning text — nudging ($nudgeCount/$maxNudges): ${responseText.take(80)}")
                    history.put(userTurn {
                        addText("Continue.")
                    })
                    continue
                }
                DebugLog.d(TAG, "[$iterations] No tool calls — done")
                // Guard against the model echoing system-prompt instructions back to the user.
                // This happens when the model emits a constraint ("Do not narrate", "Only call
                // a tool") instead of acting — the text gets spoken aloud, which is confusing.
                val safeText = if (isSystemEcho(responseText)) {
                    DebugLog.w(TAG, "[$iterations] System-echo detected — suppressing: ${responseText.take(80)}")
                    ""
                } else responseText
                return Pair(safeText.ifBlank { "Task completed." }, history)
            }

            // Execute every tool call
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
                onStatusUpdate(statusForTool(toolName))

                // Translate image-space coordinates → real screen coordinates.
                // Gemini works in the scaled image space; gestures need screen pixels.
                val scaledArgs = if (imgScale != 1.0f) scaleCoords(args, imgScale) else args

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

            nudgeCount = 0  // reset after a successful action batch — fresh budget for the next step

            if (taskComplete) {
                DebugLog.d(TAG, "[$iterations] task_complete: $completionSummary")
                // Close the functionCall/functionResponse exchange
                history.put(JSONObject()
                    .put("role", "user")
                    .put("parts", resultParts))
                // End with a model text turn so the next task's user message is valid.
                // Without this, two consecutive user turns would cause a 400 error.
                history.put(JSONObject()
                    .put("role", "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", completionSummary))))
                return Pair(completionSummary, history)
            }

            // Build the next user turn: tool results + fresh screenshot
            val nextUserParts = mutableListOf<JSONObject>()
            for (i in 0 until resultParts.length()) nextUserParts.add(resultParts.getJSONObject(i))

            // Attach a fresh screenshot so Gemini can see what the actions produced.
            // Use feedback quality (65) — lower than the initial planning screenshot (80)
            // but still clear enough to read UI text; saves ~25% on input tokens.
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
        return Pair(stopMsg, history)
    }

    // ─── Request building ───────────────────────────────────────────────────

    private fun buildRequest(history: JSONArray): JSONObject = JSONObject()
        .put("system_instruction", JSONObject()
            .put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
        .put("contents", history)
        .put("tools", JSONArray().put(JSONObject().put("function_declarations", buildToolsJson())))
        .put("generationConfig", JSONObject()
            .put("temperature", 0.2)
            .put("maxOutputTokens", 4096)
            // thinkingBudget 0 = disable thinking chains on gemini-3.5-flash.
            // This eliminates thought_signature fields in function call parts,
            // which SDK 0.9.0 cannot handle. The model remains fully capable.
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

    // ─── Reasoning detection ────────────────────────────────────────────────

    /**
     * Returns true when the model's text looks like internal reasoning / narration
     * rather than a final spoken answer.  Used to detect premature loop exit.
     *
     * Pattern: forward-looking phrases that indicate the model is *about* to act
     * rather than reporting what it *has* done.
     */
    private fun looksLikeReasoning(text: String): Boolean {
        if (text.length < 20) return false
        val t = text.trim().lowercase()
        val forwardPhrases = listOf(
            // Intent narration
            "let me", "let's", "i'll ", "i will ", "i need to", "i'm going to",
            "now i", "first,", "first i", "to do this", "step-by-step",
            "i should", "now let", "next,", "next i",
            // Screen narration (the other big source of wasted round trips)
            "i can see", "looking at the", "the screen shows", "on screen:",
            "now on screen", "i notice", "it appears", "it seems", "i see that",
            "screen is", "screen now", "currently showing", "currently playing",
            // Meta commentary
            "coordinates are", "note that", "note:", "(note:", "okay, ", "alright,",
            "do not output", "it looks like"
        )
        return forwardPhrases.any { t.contains(it) }
    }

    /**
     * Returns true when the model's text is a verbatim echo of our system-prompt
     * constraints rather than a useful spoken answer.  These strings should never
     * be played back to the user via TTS.
     */
    private fun isSystemEcho(text: String): Boolean {
        if (text.isBlank()) return false
        val t = text.trim().lowercase()
        return t.contains("do not narrate") ||
               t.contains("don't narrate") ||
               t.contains("only call a tool") ||
               t.contains("use the available tools") ||
               t.contains("think silently") ||
               t.contains("act immediately") ||
               t.contains("no reasoning text") ||
               t.contains("no screen narration") ||
               t.contains("never describe what you see") ||
               t.contains("never narrate intent")
    }

    // ─── Coordinate scaling ─────────────────────────────────────────────────

    private val COORD_KEYS = setOf("x", "y", "startX", "startY", "endX", "endY")

    /** Scale image-space coords back to real screen coords (divide by imgScale). */
    private fun scaleCoords(args: Map<String, String?>, imgScale: Float): Map<String, String?> {
        val invScale = 1.0f / imgScale
        return args.mapValues { (key, value) ->
            if (key in COORD_KEYS) {
                val v = value?.toFloatOrNull()
                if (v != null) (v * invScale).toInt().toString() else value
            } else value
        }
    }

    // ─── JSON helpers ───────────────────────────────────────────────────────

    private fun userTurn(block: JSONArray.() -> Unit): JSONObject {
        val parts = JSONArray().apply(block)
        return JSONObject().put("role", "user").put("parts", parts)
    }

    private fun JSONArray.addScreenshot(bitmap: Bitmap) = put(inlineImage(bitmap))
    private fun JSONArray.addText(text: String) = put(textPart(text))

    /** Full quality — for the initial planning screenshot only. */
    private fun inlineImage(bitmap: Bitmap): JSONObject = JSONObject()
        .put("inlineData", JSONObject()
            .put("mimeType", "image/jpeg")
            .put("data", ScreenshotManager.bitmapToBase64(bitmap)))

    /** Reduced quality — for after-action feedback screenshots (~25% fewer tokens). */
    private fun inlineFeedbackImage(bitmap: Bitmap): JSONObject = JSONObject()
        .put("inlineData", JSONObject()
            .put("mimeType", "image/jpeg")
            .put("data", ScreenshotManager.bitmapToBase64Feedback(bitmap)))

    private fun textPart(text: String): JSONObject = JSONObject().put("text", text)

    private fun functionResponse(name: String, response: JSONObject): JSONObject = JSONObject()
        .put("functionResponse", JSONObject()
            .put("name", name)
            .put("response", response))

    private fun statusForTool(tool: ToolName): String = when (tool) {
        ToolName.TAKE_SCREENSHOT -> "Checking screen…"
        ToolName.TAP, ToolName.LONG_PRESS -> "Tapping…"
        ToolName.SWIPE -> "Swiping…"
        ToolName.TYPE_TEXT -> "Typing…"
        ToolName.OPEN_APP -> "Opening app…"
        ToolName.PRESS_BACK, ToolName.PRESS_HOME, ToolName.PRESS_RECENT_APPS -> "Navigating…"
        ToolName.SCROLL -> "Scrolling…"
        ToolName.FIND_AND_CLICK_TEXT -> "Finding element…"
        ToolName.GET_SCREEN_TEXT -> "Reading screen…"
        ToolName.SPEAK_TO_USER, ToolName.TASK_COMPLETE -> "Done"
        ToolName.WAIT -> "Waiting…"
    }

    // ─── Tool declarations (JSON schema) ───────────────────────────────────

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
        put(toolDef(ToolName.FIND_AND_CLICK_TEXT, "Find a UI element by text and click it — more reliable than tap().",
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
        val obj = JSONObject()
            .put("name", tool.id)
            .put("description", description)
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

    companion object {
        private val SYSTEM_PROMPT = """
            You are Phony, a friendly AI assistant who lives on the user's Android phone.
            You have two modes — use the right one based on what the user asked:

            ## Mode 1 — General Assistant (no tools needed)
            For ANY question that doesn't require touching the phone screen, just answer directly:
            - Jokes, trivia, facts, advice, math, recipes, definitions, creative writing, general chat
            - Just reply conversationally — your response will be read aloud, so keep it natural and concise
            - DO NOT call any tools for general questions. Just return your answer as plain text.
            - Examples: "tell me a joke", "what's the capital of France", "how do I cook pasta"

            ## Mode 2 — Phone Agent (use tools)
            When the user wants you to do something ON the phone, use the available tools.

            CRITICAL — NO REASONING TEXT, NO SCREEN NARRATION:
            - After every tool result a fresh screenshot is provided. Your ONLY valid response is the next tool call.
            - NEVER describe what you see: no "Now on screen:", "I can see...", "The screen shows...", "It appears...", "It seems..."
            - NEVER narrate intent: no "Let me...", "I'll...", "Now I'll...", "Let's...", "First...", "Next I..."
            - Describing before acting costs an extra API round-trip and slows the user down.
            - Only output plain text (no tools) when giving the FINAL spoken result to the user.
            - Think silently. Act immediately.

            COMPLETENESS — NEVER STOP EARLY:
            - Before reporting any list (search results, stocks, calendar events, contacts…), ALWAYS scroll DOWN and call get_screen_text at least once to check for content below the fold.
            - If the screen looks truncated or says "X of Y", keep scrolling until you've seen everything.
            - Use find_and_click_text for buttons/labels (more reliable than tap coordinates).
            - Use get_screen_text to read lists, results, calendar events, settings values.
            - To type: tap the field first, then type_text().
            - When done, call task_complete with a spoken summary of what you accomplished.

            ENDING A TASK — task_complete IS MANDATORY:
            - Every phone task MUST end with task_complete(summary="…"). No exceptions.
            - Even if you finish in one step — call task_complete immediately after.
            - Do NOT emit plain text as your final turn for phone tasks. The text won't be spoken unless task_complete is called.
            - If the command was garbled, unclear, or you couldn't hear it properly:
              1. Call speak_to_user("Sorry, I didn't catch that. Could you say it again?")
              2. Then call task_complete(summary="") — empty summary so nothing extra is spoken

            LOGIN / AUTHENTICATION:
            - If you see a login screen, PIN pad, password field, or fingerprint/biometric prompt:
              1. Call speak_to_user("Please authenticate to continue")
              2. Call wait(seconds=3) — a fresh screenshot is automatically attached after EVERY tool call
              3. Look at that fresh screenshot: if still on auth screen, call wait(seconds=3) again immediately
              4. Repeat wait() up to 8 times — do NOT call take_screenshot manually, it is provided for you
              5. Once auth screen is gone, continue the original task with no further explanation

            TOOL ERROR RECOVERY:
            - If find_and_click_text("Search, Tab 2 of 4") fails, retry with find_and_click_text("Search")
            - Never tap the exact same (x,y) more than twice — if nothing changed, coordinates are wrong
            - After 3 failed attempts on one step, call task_complete and explain honestly

            ## Android UI Patterns

            GENERAL — always prefer find_and_click_text over tap() when an element has a label:
            - Call get_screen_text first to discover what elements are on screen, then act on them by name
            - Tap by coordinate only as a last resort when no text/content-description label exists

            CAMERA & PHOTO APPS (Google Camera, Samsung Camera, Snapchat, etc.):
            - Open camera: open_app("Camera"), then wait(seconds=2) for it to fully initialize
            - Before tapping, always call get_screen_text — the shutter button label varies by app:
              Google Camera: "Shutter" | Samsung: "Take picture" | Snapchat: "Capture"
            - Try find_and_click_text in order: "Shutter" → "Take picture" → "Capture photo" → "Take photo"
            - If ALL find_and_click_text attempts fail, the shutter has no accessibility label.
              Fall back to tap by coordinate: the shutter is the large circle at the very bottom-center.
              Screenshots are scaled to 1080px tall — the shutter is always near y=950, x=center of image.
            - After tapping shutter, wait(seconds=1) then get_screen_text to verify capture happened
            - Switch mode: find_and_click_text("Photo"), find_and_click_text("Video"), find_and_click_text("Portrait")

            MUSIC & MEDIA (Spotify, YouTube Music, Apple Music):
            - Spotify skip forward (next song): find_and_click_text the current song title to open full player → find_and_click_text("Next")
            - Spotify skip back (previous): same → find_and_click_text("Previous")
            - Spotify play/pause: find_and_click_text("Play") or find_and_click_text("Pause")
            - Spotify search: find_and_click_text("Search") bottom tab → tap search field → type_text()
            - NEVER tap Spotify by coordinate — song list rows are densely packed and taps land on the wrong item
            - If a song is playing, the mini-player at the bottom shows the title — tap it by find_and_click_text(song title) to open full player

            VIDEO (YouTube, Netflix, Disney+, Max):
            - Search: find_and_click_text("Search") icon/tab, then type
            - Play a video: find_and_click_text with the video title after searching

            SOCIAL (Instagram, TikTok, Twitter/X, WhatsApp, iMessage):
            - New message / compose: find_and_click_text("New message"), "Compose", "Write", or the pencil icon label
            - For WhatsApp: find_and_click_text with the contact name, then type_text into the message field
            - Scroll feeds: scroll(DOWN) repeatedly; use get_screen_text to read post captions

            MAPS & NAVIGATION (Google Maps, Waze):
            - Search: find_and_click_text("Search here") or tap the search bar at the top, then type_text()
            - Get directions: after finding a place, find_and_click_text("Directions")

            FINANCE (Robinhood, Fidelity, Coinbase):
            - Portfolio / home: usually the first tab; use find_and_click_text for tab names
            - Stock details: tap the stock name or ticker by find_and_click_text
            - Always scroll down to see full price history, news, and positions

            SHOPPING (Amazon, eBay, DoorDash):
            - Search: find_and_click_text("Search") then type_text()
            - Add to cart: find_and_click_text("Add to Cart") or "Buy Now"

            SETTINGS:
            - Navigate hierarchically — find_and_click_text the section name, then the specific setting
            - Use get_screen_text to read current toggle states before changing them

            BOTTOM NAVIGATION BARS (most apps):
            - Tab labels vary: read them with get_screen_text, then find_and_click_text the right one

            ## Tone
            - Warm, concise, conversational — responses are spoken aloud so avoid bullet points and markdown
            - Address the user directly, not formally
        """.trimIndent()
    }
}
