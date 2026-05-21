package com.phoneclaw.agent

/**
 * All tools available to the Gemini agent.
 *
 * Design principle (borrowed from nanoclaw): the agent loop is the only thing
 * that decides what happens. Tools are the sole IO surface — no sideband
 * communication. Each tool call is written, executed, and its result fed back
 * before the next iteration, identical to how nanoclaw's inbound/outbound
 * databases work as a single-writer contract.
 */
enum class ToolName(val id: String) {
    TAKE_SCREENSHOT("take_screenshot"),
    TAP("tap"),
    LONG_PRESS("long_press"),
    SWIPE("swipe"),
    TYPE_TEXT("type_text"),
    OPEN_APP("open_app"),
    PRESS_BACK("press_back"),
    PRESS_HOME("press_home"),
    PRESS_RECENT_APPS("press_recent_apps"),
    SCROLL("scroll"),
    FIND_AND_CLICK_TEXT("find_and_click_text"),
    GET_SCREEN_TEXT("get_screen_text"),
    SPEAK_TO_USER("speak_to_user"),
    TASK_COMPLETE("task_complete"),
}

/** Parsed tool call from the Gemini response. */
data class ToolCall(
    val name: ToolName,
    val args: Map<String, Any>
)

/** Result of executing a tool call. */
data class ToolResult(
    val name: ToolName,
    val success: Boolean,
    val result: String,
    /** Non-null when a screenshot was taken as part of this result. */
    val screenshotBase64: String? = null
)

/** Scroll direction for the scroll tool. */
enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }
