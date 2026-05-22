package com.phoneclaw.agent

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.util.Base64
import android.util.Log
import android.view.Display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

private const val TAG = "ScreenshotManager"
private const val SCREENSHOT_TIMEOUT_MS = 3000L
private const val JPEG_QUALITY = 80           // initial planning screenshot — full quality
private const val JPEG_QUALITY_FEEDBACK = 65  // after-action screenshots — 25% smaller, still readable

/**
 * Captures the current screen via AccessibilityService.takeScreenshot().
 * Requires API 30 and canTakeScreenshot="true" in the accessibility config.
 *
 * Screenshots are compressed as JPEG before being sent to Gemini to reduce
 * token cost and latency.
 */
class ScreenshotManager(private val service: AccessibilityService) {

    /** Called on the main thread immediately before capturing — use to hide overlays. */
    var onBeforeScreenshot: (() -> Unit)? = null
    /** Called on the main thread immediately after capturing — use to restore overlays. */
    var onAfterScreenshot: (() -> Unit)? = null

    /**
     * Take a screenshot and return as a Bitmap.
     * Returns null if the screenshot could not be taken within the timeout.
     */
    suspend fun takeScreenshot(): Bitmap? {
        // Hide overlays so the FAB/debug panel don't appear in the image
        withContext(Dispatchers.Main) { onBeforeScreenshot?.invoke() }
        // Give the window compositor one frame to actually hide the views
        kotlinx.coroutines.delay(80)

        val bitmap = withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    service.mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: AccessibilityService.ScreenshotResult) {
                            try {
                                val bitmap = Bitmap.wrapHardwareBuffer(
                                    screenshotResult.hardwareBuffer,
                                    screenshotResult.colorSpace
                                )?.copy(Bitmap.Config.ARGB_8888, false)
                                screenshotResult.hardwareBuffer.close()
                                Log.d(TAG, "Screenshot captured: ${bitmap?.width}x${bitmap?.height}")
                                continuation.resume(bitmap)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to convert screenshot bitmap", e)
                                continuation.resume(null)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w(TAG, "Screenshot failed with error code: $errorCode")
                            continuation.resume(null)
                        }
                    }
                )
            }
        }

        // Restore overlays regardless of success/failure
        withContext(Dispatchers.Main) { onAfterScreenshot?.invoke() }
        return bitmap
    }

    /**
     * Take a screenshot and encode it as a Base64 JPEG string.
     * Useful for embedding in JSON tool results.
     */
    suspend fun takeScreenshotAsBase64(): String? {
        val bitmap = takeScreenshot() ?: return null
        return bitmapToBase64(bitmap)
    }

    /**
     * Resize and compress a bitmap for sending to the Gemini API.
     * Returns the scaled bitmap AND the scale factor used.
     * The caller must use scaleFactor to convert Gemini's image-space coordinates
     * back to real screen coordinates before dispatching gestures.
     */
    fun prepareForApi(bitmap: Bitmap): Pair<Bitmap, Float> {
        val maxDim = 1080
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxDim && height <= maxDim) return Pair(bitmap, 1.0f)

        val scale = maxDim.toFloat() / maxOf(width, height)
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        return Pair(Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true), scale)
    }

    companion object {
        /** Full-quality encode — use for the initial planning screenshot. */
        fun bitmapToBase64(bitmap: Bitmap): String =
            encode(bitmap, JPEG_QUALITY)

        /** Reduced-quality encode — use for after-action feedback screenshots. */
        fun bitmapToBase64Feedback(bitmap: Bitmap): String =
            encode(bitmap, JPEG_QUALITY_FEEDBACK)

        private fun encode(bitmap: Bitmap, quality: Int): String {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        }
    }
}
