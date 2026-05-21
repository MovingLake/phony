package com.phoneclaw.agent

import android.graphics.Bitmap
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.FunctionDeclaration
import com.google.ai.client.generativeai.type.FunctionResponsePart
import com.google.ai.client.generativeai.type.Schema
import com.google.ai.client.generativeai.type.Tool
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import com.phoneclaw.data.GeminiModel
import org.json.JSONObject

private const val TAG = "GeminiClient"

/**
 * Wraps the Google Generative AI SDK to run the PhoneClaw agent loop.
 *
 * Each task starts a fresh chat session. The conversation history accumulates
 * within a single task (screenshots, tool calls, results) and is discarded
 * when the task completes — keeping memory bounded and responses fast.
 */
class GeminiClient(
    private val apiKey: String,
    private val model: GeminiModel = GeminiModel.FLASH_2_0,
) {

    private val generativeModel: GenerativeModel by lazy {
        GenerativeModel(
            modelName = model.modelId,
            apiKey = apiKey,
            tools = listOf(Tool(functionDeclarations = buildFunctionDeclarations())),
            generationConfig = generationConfig {
                temperature = 0.2f  // Low temperature for reliable action sequences
                maxOutputTokens = 2048
            },
            systemInstruction = content { text(SYSTEM_PROMPT) }
        )
    }

    /** Run a full agentic task. Returns the final spoken summary. */
    suspend fun runTask(
        userCommand: String,
        initialScreenshot: Bitmap,
        onToolCall: suspend (ToolCall) -> ToolResult,
        onStatusUpdate: (String) -> Unit,
    ): String {
        val chat = generativeModel.startChat()

        // Initial message: screenshot + user command
        val initialContent = content(role = "user") {
            image(initialScreenshot)
            text(
                """
                User voice command: "$userCommand"

                The screenshot above shows the current state of the screen.
                Complete the user's request step by step using the available tools.
                """.trimIndent()
            )
        }

        var response = chat.sendMessage(initialContent)
        var iterations = 0
        val maxIterations = 20  // Safety limit

        while (iterations < maxIterations) {
            iterations++
            Log.d(TAG, "Agent iteration $iterations")

            // Collect all function calls in this response
            val functionCalls = response.candidates
                .firstOrNull()?.content?.parts
                ?.filterIsInstance<com.google.ai.client.generativeai.type.FunctionCallPart>()
                ?: emptyList()

            if (functionCalls.isEmpty()) {
                // Pure text response — agent is done
                val finalText = response.text ?: "Task completed."
                Log.d(TAG, "Agent finished with text: $finalText")
                return finalText
            }

            // Execute each tool call and collect results
            val resultParts = mutableListOf<com.google.ai.client.generativeai.type.Part>()
            var taskComplete = false
            var completionSummary = ""

            for (call in functionCalls) {
                val toolName = ToolName.entries.firstOrNull { it.id == call.name }
                    ?: run {
                        Log.w(TAG, "Unknown tool: ${call.name}")
                        resultParts.add(
                            FunctionResponsePart(
                                call.name,
                                JSONObject(mapOf("error" to "Unknown tool: ${call.name}"))
                            )
                        )
                        continue
                    }

                onStatusUpdate(statusForTool(toolName))

                val toolCall = ToolCall(
                    name = toolName,
                    args = call.args ?: emptyMap()
                )

                val toolResult = onToolCall(toolCall)

                // Build the function response
                val resultJson = buildResultJson(toolResult)
                resultParts.add(FunctionResponsePart(call.name, resultJson))

                when (toolName) {
                    ToolName.TASK_COMPLETE -> {
                        taskComplete = true
                        completionSummary = toolResult.result
                    }
                    ToolName.SPEAK_TO_USER -> {
                        // speak_to_user is handled by the caller via onToolCall
                        // but if it's the last action, we're done
                    }
                    else -> {}
                }
            }

            if (taskComplete) {
                return completionSummary
            }

            // Send all tool results back in one message
            response = chat.sendMessage(
                content(role = "function") {
                    resultParts.forEach { part(it) }
                }
            )
        }

        return "Task stopped: too many steps. Last state may be incomplete."
    }

    private fun buildResultJson(result: ToolResult): JSONObject {
        val json = JSONObject()
        json.put("success", result.success)
        json.put("result", result.result)
        result.screenshotBase64?.let { json.put("screenshot_base64", it) }
        return json
    }

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
    }

    // ─── Function Declarations ──────────────────────────────────────────────

    private fun buildFunctionDeclarations(): List<FunctionDeclaration> = listOf(

        FunctionDeclaration(
            name = ToolName.TAKE_SCREENSHOT.id,
            description = "Capture the current screen state. Call this to refresh your view of the screen after performing actions.",
            parameters = emptyList(),
            requiredParameters = emptyList()
        ),

        FunctionDeclaration(
            name = ToolName.TAP.id,
            description = "Tap at specific pixel coordinates on the screen. Use the screenshot to determine the correct coordinates.",
            parameters = listOf(
                Schema.int("x", "X coordinate in pixels from the left edge"),
                Schema.int("y", "Y coordinate in pixels from the top edge")
            ),
            requiredParameters = listOf("x", "y")
        ),

        FunctionDeclaration(
            name = ToolName.LONG_PRESS.id,
            description = "Long press at specific pixel coordinates. Useful for context menus or drag initiation.",
            parameters = listOf(
                Schema.int("x", "X coordinate in pixels"),
                Schema.int("y", "Y coordinate in pixels")
            ),
            requiredParameters = listOf("x", "y")
        ),

        FunctionDeclaration(
            name = ToolName.SWIPE.id,
            description = "Swipe from one point to another. Useful for scrolling, dismissing notifications, or navigating between pages.",
            parameters = listOf(
                Schema.int("startX", "Starting X coordinate"),
                Schema.int("startY", "Starting Y coordinate"),
                Schema.int("endX", "Ending X coordinate"),
                Schema.int("endY", "Ending Y coordinate"),
                Schema.int("durationMs", "Duration of the swipe in milliseconds (100-1000, default 300)")
            ),
            requiredParameters = listOf("startX", "startY", "endX", "endY")
        ),

        FunctionDeclaration(
            name = ToolName.TYPE_TEXT.id,
            description = "Type text into the currently focused text field. Tap the field first to focus it, then call this.",
            parameters = listOf(
                Schema.str("text", "The text to type")
            ),
            requiredParameters = listOf("text")
        ),

        FunctionDeclaration(
            name = ToolName.OPEN_APP.id,
            description = "Open an installed app by name. Use natural names like 'Google Maps', 'Notes', 'Calendar', 'WhatsApp', 'Settings'.",
            parameters = listOf(
                Schema.str("app_name", "The app name as a user would say it, e.g. 'Google Maps', 'Spotify', 'Camera'")
            ),
            requiredParameters = listOf("app_name")
        ),

        FunctionDeclaration(
            name = ToolName.PRESS_BACK.id,
            description = "Press the Android back button.",
            parameters = emptyList(),
            requiredParameters = emptyList()
        ),

        FunctionDeclaration(
            name = ToolName.PRESS_HOME.id,
            description = "Press the Android home button to go to the home screen.",
            parameters = emptyList(),
            requiredParameters = emptyList()
        ),

        FunctionDeclaration(
            name = ToolName.PRESS_RECENT_APPS.id,
            description = "Open the recent apps/multitasking view.",
            parameters = emptyList(),
            requiredParameters = emptyList()
        ),

        FunctionDeclaration(
            name = ToolName.SCROLL.id,
            description = "Scroll in a direction within the current view.",
            parameters = listOf(
                Schema.str("direction", "One of: UP, DOWN, LEFT, RIGHT"),
                Schema.int("x", "X coordinate of the scroll target area (use center of scrollable area)"),
                Schema.int("y", "Y coordinate of the scroll target area (use center of scrollable area)")
            ),
            requiredParameters = listOf("direction", "x", "y")
        ),

        FunctionDeclaration(
            name = ToolName.FIND_AND_CLICK_TEXT.id,
            description = "Find a UI element containing the given text and click it. More reliable than coordinates for buttons and labels.",
            parameters = listOf(
                Schema.str("text", "The exact or partial text of the UI element to click")
            ),
            requiredParameters = listOf("text")
        ),

        FunctionDeclaration(
            name = ToolName.GET_SCREEN_TEXT.id,
            description = "Extract all visible text from the current screen using the accessibility tree. Use this to read content that may not be clear in the screenshot.",
            parameters = emptyList(),
            requiredParameters = emptyList()
        ),

        FunctionDeclaration(
            name = ToolName.SPEAK_TO_USER.id,
            description = "Speak a message to the user using text-to-speech. Use this to report results, ask clarifying questions, or give status updates during long tasks.",
            parameters = listOf(
                Schema.str("message", "What to say to the user. Be concise and conversational.")
            ),
            requiredParameters = listOf("message")
        ),

        FunctionDeclaration(
            name = ToolName.TASK_COMPLETE.id,
            description = "Signal that the task is fully complete. Always call this at the end.",
            parameters = listOf(
                Schema.str("summary", "A brief spoken summary of what was accomplished, to read back to the user.")
            ),
            requiredParameters = listOf("summary")
        ),
    )

    companion object {
        private val SYSTEM_PROMPT = """
            You are PhoneClaw, an AI assistant that runs on Android and helps users complete tasks on their phone using voice commands.

            ## Core Behavior
            - You see the user's screen via screenshots and can interact with it through tool calls
            - Break every task into small, concrete steps and execute them one at a time
            - After each action, take a screenshot to verify it worked before proceeding
            - Always call task_complete when you're done — never leave the user hanging
            - Be efficient: don't take unnecessary steps

            ## Tool Usage Guidelines
            - Prefer find_and_click_text over tap() when targeting labeled buttons or text — it's more reliable across screen sizes
            - Use get_screen_text to read content (search results, lists, settings) that may be hard to see in the screenshot
            - When opening apps, always use open_app() first rather than tapping home screen icons
            - For typing: first tap the input field, wait, then use type_text()
            - For search fields: tap the field, type_text, then tap the search/submit button or press Enter by typing "\n"

            ## Communication
            - Use speak_to_user() mid-task for long operations ("Opening Maps now…", "Found 3 tacos spots, listing them…")
            - In task_complete summary: be conversational, natural, helpful — like a real assistant
            - If a task is ambiguous, ask via speak_to_user before guessing

            ## Error Handling
            - If an action fails, try an alternative approach (e.g. try find_and_click_text if tap failed)
            - If completely stuck after 2-3 attempts, call task_complete with an honest explanation
            - Never loop indefinitely on the same failing action

            ## Platform Awareness
            - This is Android — apps have package names but users use natural names
            - Navigation: Back button goes back, Home button goes to launcher, swipe up from bottom = home on gesture nav
            - Common app names: "Maps" = Google Maps, "Messages" = Google Messages / SMS, "Phone" = Phone dialer
            - Calendar events, contacts, and settings are readable via get_screen_text after opening the relevant app
        """.trimIndent()
    }
}
