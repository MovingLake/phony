package com.phoneclaw.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val TAG = "ActionExecutor"

/**
 * Translates agent tool calls into real Android interactions.
 *
 * All gesture-based actions use AccessibilityService.dispatchGesture(),
 * which works regardless of which app is in the foreground.
 */
class ActionExecutor(
    private val service: AccessibilityService,
    private val appResolver: AppResolver,
    private val screenshotManager: ScreenshotManager,
) {

    private val clipboardManager: ClipboardManager =
        service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /** Dispatch a tap gesture at (x, y). */
    suspend fun tap(x: Int, y: Int): ToolResult {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val success = dispatchGesture(gesture)
        delay(300) // Let the UI settle
        return ToolResult(ToolName.TAP, success, if (success) "Tapped at ($x, $y)" else "Tap failed at ($x, $y)")
    }

    /** Dispatch a long press at (x, y). */
    suspend fun longPress(x: Int, y: Int): ToolResult {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 800L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val success = dispatchGesture(gesture)
        delay(500)
        return ToolResult(ToolName.LONG_PRESS, success, if (success) "Long pressed at ($x, $y)" else "Long press failed")
    }

    /** Dispatch a swipe from (startX, startY) to (endX, endY). */
    suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int = 300): ToolResult {
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.toLong().coerceIn(100, 2000))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val success = dispatchGesture(gesture)
        delay(400)
        return ToolResult(ToolName.SWIPE, success, if (success) "Swiped ($startX,$startY) → ($endX,$endY)" else "Swipe failed")
    }

    /** Type text into the focused field via clipboard injection. */
    suspend fun typeText(text: String): ToolResult {
        // Set clipboard content
        val clip = ClipData.newPlainText("phoneclaw_input", text)
        clipboardManager.setPrimaryClip(clip)

        // Find focused field and paste
        val focusedNode = service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return if (focusedNode != null) {
            val bundle = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
            focusedNode.recycle()
            delay(200)
            ToolResult(ToolName.TYPE_TEXT, success, if (success) "Typed: $text" else "Could not type into focused field — is a text field focused?")
        } else {
            // Fallback: try paste via clipboard into whatever is focused
            val root = service.rootInActiveWindow
            val pastedViaRoot = root?.performAction(AccessibilityNodeInfo.ACTION_PASTE) ?: false
            root?.recycle()
            ToolResult(ToolName.TYPE_TEXT, pastedViaRoot, if (pastedViaRoot) "Pasted: $text" else "No focused text field found")
        }
    }

    /** Open an app by natural name. */
    suspend fun openApp(appName: String): ToolResult {
        val resolved = appResolver.resolve(appName)
        return if (resolved != null) {
            val (pkg, intent) = resolved
            intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            try {
                service.startActivity(intent)
                delay(1500) // Give the app time to launch
                ToolResult(ToolName.OPEN_APP, true, "Opened $appName (package: $pkg)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open app: $appName", e)
                ToolResult(ToolName.OPEN_APP, false, "Failed to open $appName: ${e.message}")
            }
        } else {
            ToolResult(ToolName.OPEN_APP, false, "App '$appName' not found. Is it installed?")
        }
    }

    /** Press the Android back button. */
    suspend fun pressBack(): ToolResult {
        val success = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        delay(300)
        return ToolResult(ToolName.PRESS_BACK, success, if (success) "Pressed back" else "Back action failed")
    }

    /** Press the Android home button. */
    suspend fun pressHome(): ToolResult {
        val success = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        delay(500)
        return ToolResult(ToolName.PRESS_HOME, success, if (success) "Pressed home" else "Home action failed")
    }

    /** Open recent apps. */
    suspend fun pressRecentApps(): ToolResult {
        val success = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        delay(500)
        return ToolResult(ToolName.PRESS_RECENT_APPS, success, if (success) "Opened recent apps" else "Failed to open recent apps")
    }

    /** Scroll in a direction at the given coordinates. */
    suspend fun scroll(direction: ScrollDirection, x: Int, y: Int): ToolResult {
        val screenHeight = service.resources.displayMetrics.heightPixels
        val screenWidth = service.resources.displayMetrics.widthPixels

        val (startX, startY, endX, endY) = when (direction) {
            ScrollDirection.DOWN -> listOf(x, y + 200, x, y - 200)
            ScrollDirection.UP -> listOf(x, y - 200, x, y + 200)
            ScrollDirection.RIGHT -> listOf(x + 200, y, x - 200, y)
            ScrollDirection.LEFT -> listOf(x - 200, y, x + 200, y)
        }

        return swipe(
            startX.coerceIn(0, screenWidth),
            startY.coerceIn(0, screenHeight),
            endX.coerceIn(0, screenWidth),
            endY.coerceIn(0, screenHeight),
            durationMs = 400
        ).copy(name = ToolName.SCROLL)
    }

    /** Find a UI element with matching text and click it. */
    suspend fun findAndClickText(text: String): ToolResult {
        val root = service.rootInActiveWindow ?: return ToolResult(
            ToolName.FIND_AND_CLICK_TEXT, false, "Could not get window root"
        )

        try {
            // Try progressively shorter versions of the text.
            // Gemini often reads the full accessibility description e.g.
            // "Search, Tab 2 of 4" but the node's .text is just "Search".
            val candidates = buildList {
                add(text)
                // First segment before comma: "Search, Tab 2 of 4" → "Search"
                if (text.contains(',')) add(text.substringBefore(',').trim())
                // First word
                val firstWord = text.split(' ', ',').firstOrNull { it.isNotBlank() }
                if (firstWord != null && firstWord != text) add(firstWord)
            }.distinct()

            var nodes: List<AccessibilityNodeInfo>? = null
            var matchedWith = text
            for (candidate in candidates) {
                val found = root.findAccessibilityNodeInfosByText(candidate)
                if (!found.isNullOrEmpty()) {
                    nodes = found
                    matchedWith = candidate
                    if (candidate != text) DebugLog.d(TAG, "find_and_click_text: matched '$candidate' (from '$text')")
                    break
                }
            }

            if (nodes.isNullOrEmpty()) {
                return ToolResult(ToolName.FIND_AND_CLICK_TEXT, false, "No element found with text: '$text'")
            }

            val target = nodes.firstOrNull { it.isClickable }
                ?: nodes.firstOrNull()?.let { node ->
                    // Walk up the tree to find a clickable parent
                    var parent: AccessibilityNodeInfo? = node.parent
                    var found: AccessibilityNodeInfo? = null
                    while (parent != null) {
                        if (parent.isClickable) {
                            found = parent
                            break
                        }
                        parent = parent.parent
                    }
                    found
                }

            return if (target != null) {
                val success = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                nodes.forEach { it.recycle() }
                target.recycle()
                delay(400)
                ToolResult(
                    ToolName.FIND_AND_CLICK_TEXT,
                    success,
                    if (success) "Clicked element '$matchedWith'" else "Found element but click failed"
                )
            } else {
                nodes.forEach { it.recycle() }
                ToolResult(ToolName.FIND_AND_CLICK_TEXT, false, "Found text '$text' but no clickable element")
            }
        } finally {
            root.recycle()
        }
    }

    /**
     * Extract all visible text from the accessibility node tree.
     * This is much faster than asking Gemini to OCR the screenshot.
     */
    /**
     * Extract all visible text from the accessibility node tree.
     *
     * Searches ALL accessible windows, not just rootInActiveWindow.
     * This is essential for apps like Google Camera where the shutter button
     * lives in a separate overlay window from the main viewfinder surface.
     * Falls back to rootInActiveWindow if getWindows() returns nothing.
     */
    fun getScreenText(): ToolResult {
        val text = StringBuilder()
        var found = false

        try {
            val windows = service.windows
            if (!windows.isNullOrEmpty()) {
                for (window in windows) {
                    val root = window.root ?: continue
                    try {
                        // Skip PhoneClaw's own overlay — the FAB and debug panel text
                        // ("Capturing screen… PhoneClaw AI Assistant") pollutes results.
                        // AccessibilityWindowInfo has no packageName; check the root node instead.
                        if (root.packageName?.toString() == service.packageName) continue
                        extractText(root, text, depth = 0)
                        found = true
                    } finally {
                        root.recycle()
                    }
                }
            }
        } catch (_: Exception) { /* getWindows() not available — fall through */ }

        // Fallback to active window if getWindows() yielded nothing
        if (!found) {
            val root = service.rootInActiveWindow ?: return ToolResult(
                ToolName.GET_SCREEN_TEXT, false, "Could not get window content"
            )
            try {
                extractText(root, text, depth = 0)
            } finally {
                root.recycle()
            }
        }

        val result = text.toString().trim()
        return ToolResult(
            ToolName.GET_SCREEN_TEXT,
            true,
            if (result.isNotEmpty()) result else "(No text visible on screen)"
        )
    }

    private fun extractText(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 50) return // Increased from 30 — camera/media apps have deep view trees

        val text = node.text?.toString()
        val contentDesc = node.contentDescription?.toString()
        val hintText = node.hintText?.toString()

        when {
            !text.isNullOrBlank() -> sb.appendLine(text)
            !contentDesc.isNullOrBlank() -> sb.appendLine(contentDesc)
            !hintText.isNullOrBlank() -> sb.appendLine("[hint: $hintText]")
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                extractText(child, sb, depth + 1)
                child.recycle()
            }
        }
    }

    /** Pause execution for a given number of seconds (clamped 1–10). */
    suspend fun wait(seconds: Int): ToolResult {
        val clamped = seconds.coerceIn(1, 10)
        delay(clamped * 1000L)
        return ToolResult(ToolName.WAIT, true, "Waited $clamped seconds")
    }

    // ─── Gesture helper ────────────────────────────────────────────────────

    private suspend fun dispatchGesture(gesture: GestureDescription): Boolean {
        return withTimeoutOrNull(2000L) {
            suspendCancellableCoroutine { continuation ->
                service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        continuation.resume(true)
                    }
                    override fun onCancelled(gestureDescription: GestureDescription) {
                        continuation.resume(false)
                    }
                }, null)
            }
        } ?: false
    }
}

// Helper to destructure 4-element lists
private operator fun <T> List<T>.component4(): T = this[3]
