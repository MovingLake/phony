package com.phoneclaw.agent

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.util.Base64
import android.util.Log
import android.view.Display
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

private const val TAG = "ScreenshotManager"
private const val SCREENSHOT_TIMEOUT_MS = 3000L
private const val JPEG_QUALITY = 80

/**
 * Captures the current screen via AccessibilityService.takeScreenshot().
 * Requires API 30 and canTakeScreenshot="true" in the accessibility config.
 *
 * Screenshots are compressed as JPEG before being sent to Gemini to reduce
 * token cost and latency.
 */
class ScreenshotManager(private val service: AccessibilityService) {

    /**
     * Take a screenshot and return as a Bitmap.
     * Returns null if the screenshot could not be taken within the timeout.
     */
    suspend fun takeScreenshot(): Bitmap? {
        return withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) {
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
     * Gemini has an image size limit; we cap at 1080p for clarity vs cost balance.
     */
    fun prepareForApi(bitmap: Bitmap): Bitmap {
        val maxDim = 1080
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxDim && height <= maxDim) return bitmap

        val scale = maxDim.toFloat() / maxOf(width, height)
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    companion object {
        fun bitmapToBase64(bitmap: Bitmap): String {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        }
    }
}
