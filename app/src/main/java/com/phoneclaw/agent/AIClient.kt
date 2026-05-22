package com.phoneclaw.agent

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject

/**
 * Common interface for all AI providers (Gemini, Claude, OpenAI).
 * Each implementation handles its own wire format, tool declarations,
 * and session history internally — AgentLoop stores history opaquely.
 */
interface AIClient {
    /**
     * Run a full agentic task. Returns (spokenSummary, cleanedHistory).
     * The history format is provider-specific; pass it back unchanged on the
     * next call to carry context across FAB taps within the same session.
     * Each client strips images and trims before returning, so AgentLoop
     * can store it directly without further processing.
     */
    suspend fun runTask(
        userCommand: String,
        initialScreenshot: Bitmap,
        sessionHistory: JSONArray?,
        onToolCall: suspend (ToolCall) -> ToolResult,
        onStatusUpdate: (String) -> Unit,
    ): Pair<String, JSONArray>
}

// ─── Shared utilities ──────────────────────────────────────────────────────

/**
 * Provider-agnostic utilities shared by all AI clients.
 * System prompt, tool definitions (OpenAPI schema), reasoning detection, etc.
 */
object AgentPrompts {

    // ── Reasoning / echo detection ────────────────────────────────────────

    fun looksLikeReasoning(text: String): Boolean {
        if (text.length < 20) return false
        val t = text.trim().lowercase()
        val phrases = listOf(
            "let me", "let's", "i'll ", "i will ", "i need to", "i'm going to",
            "now i", "first,", "first i", "to do this", "step-by-step",
            "i should", "now let", "next,", "next i",
            "i can see", "looking at the", "the screen shows", "on screen:",
            "now on screen", "i notice", "it appears", "it seems", "i see that",
            "screen is", "screen now", "currently showing", "currently playing",
            "coordinates are", "note that", "note:", "(note:", "okay, ", "alright,",
            "do not output", "it looks like"
        )
        return phrases.any { t.contains(it) }
    }

    fun isSystemEcho(text: String): Boolean {
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

    // ── Coordinate scaling ────────────────────────────────────────────────

    val COORD_KEYS = setOf("x", "y", "startX", "startY", "endX", "endY")

    fun scaleCoords(args: Map<String, String?>, imgScale: Float): Map<String, String?> {
        val invScale = 1.0f / imgScale
        return args.mapValues { (key, value) ->
            if (key in COORD_KEYS) {
                val v = value?.toFloatOrNull()
                if (v != null) (v * invScale).toInt().toString() else value
            } else value
        }
    }

    // ── Status messages ───────────────────────────────────────────────────

    fun statusForTool(tool: ToolName): String = when (tool) {
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

    // ── Tool definitions (OpenAPI / JSON Schema) ──────────────────────────
    // Shared between Claude and OpenAI; Gemini uses its own uppercase format.

    private data class ToolDef(
        val name: ToolName,
        val description: String,
        val params: List<ParamDef> = emptyList(),
    )

    private data class ParamDef(
        val name: String,
        val type: String,          // "integer" or "string"
        val description: String,
        val optional: Boolean = false,
    )

    private val TOOLS = listOf(
        ToolDef(ToolName.TAKE_SCREENSHOT, "Capture the current screen. Call this when you need to see the current state."),
        ToolDef(ToolName.TAP, "Tap at pixel coordinates on screen.",
            listOf(ParamDef("x", "integer", "X coordinate"), ParamDef("y", "integer", "Y coordinate"))),
        ToolDef(ToolName.LONG_PRESS, "Long press at pixel coordinates.",
            listOf(ParamDef("x", "integer", "X coordinate"), ParamDef("y", "integer", "Y coordinate"))),
        ToolDef(ToolName.SWIPE, "Swipe between two points.",
            listOf(
                ParamDef("startX", "integer", "startX"), ParamDef("startY", "integer", "startY"),
                ParamDef("endX", "integer", "endX"),     ParamDef("endY", "integer", "endY"),
                ParamDef("durationMs", "integer", "Duration ms, default 300", optional = true),
            )),
        ToolDef(ToolName.TYPE_TEXT, "Type text into the focused field.",
            listOf(ParamDef("text", "string", "Text to type"))),
        ToolDef(ToolName.OPEN_APP, "Open an app by natural name (e.g. 'Spotify', 'Maps', 'Calendar').",
            listOf(ParamDef("app_name", "string", "Natural app name"))),
        ToolDef(ToolName.PRESS_BACK, "Press Android back button."),
        ToolDef(ToolName.PRESS_HOME, "Go to home screen."),
        ToolDef(ToolName.PRESS_RECENT_APPS, "Open recent apps view."),
        ToolDef(ToolName.SCROLL, "Scroll in a direction.",
            listOf(
                ParamDef("direction", "string", "UP, DOWN, LEFT, or RIGHT"),
                ParamDef("x", "integer", "Center X of scroll area"),
                ParamDef("y", "integer", "Center Y of scroll area"),
            )),
        ToolDef(ToolName.FIND_AND_CLICK_TEXT,
            "Find a UI element by text and click it — more reliable than tap().",
            listOf(ParamDef("text", "string", "Text of the element to click"))),
        ToolDef(ToolName.GET_SCREEN_TEXT, "Extract all visible text from the accessibility tree."),
        ToolDef(ToolName.SPEAK_TO_USER, "Speak a message to the user via TTS.",
            listOf(ParamDef("message", "string", "What to say"))),
        ToolDef(ToolName.TASK_COMPLETE, "Signal task is done.",
            listOf(ParamDef("summary", "string", "Spoken summary to read back to user"))),
        ToolDef(ToolName.WAIT,
            "Pause for N seconds — use after authentication prompts or to let animations finish.",
            listOf(ParamDef("seconds", "integer", "Seconds to wait (1–10)"))),
    )

    /** Claude tool declarations format. */
    fun buildClaudeToolsJson(): JSONArray = JSONArray().apply {
        TOOLS.forEach { tool ->
            val schema = JSONObject().put("type", "object")
            val props = JSONObject()
            val required = JSONArray()
            tool.params.forEach { p ->
                props.put(p.name, JSONObject().put("type", p.type).put("description", p.description))
                if (!p.optional) required.put(p.name)
            }
            schema.put("properties", props)
            if (required.length() > 0) schema.put("required", required)

            put(JSONObject()
                .put("name", tool.name.id)
                .put("description", tool.description)
                .put("input_schema", schema))
        }
    }

    /** OpenAI tool declarations format. */
    fun buildOpenAIToolsJson(): JSONArray = JSONArray().apply {
        TOOLS.forEach { tool ->
            val params = JSONObject().put("type", "object")
            val props = JSONObject()
            val required = JSONArray()
            tool.params.forEach { p ->
                props.put(p.name, JSONObject().put("type", p.type).put("description", p.description))
                if (!p.optional) required.put(p.name)
            }
            params.put("properties", props)
            if (required.length() > 0) params.put("required", required)

            put(JSONObject()
                .put("type", "function")
                .put("function", JSONObject()
                    .put("name", tool.name.id)
                    .put("description", tool.description)
                    .put("parameters", params)))
        }
    }

    // ── System prompt ─────────────────────────────────────────────────────

    val SYSTEM_PROMPT = """
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
