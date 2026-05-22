package com.phoneclaw.service

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Service
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.phoneclaw.BuildConfig
import com.phoneclaw.R
import com.phoneclaw.agent.AgentState
import com.phoneclaw.agent.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val TAG = "FloatingButtonManager"

/**
 * Manages the floating action button that hovers over all apps.
 *
 * The FAB has 5 visual states that mirror the agent lifecycle:
 *   Idle       → purple,  mic icon
 *   Listening  → red,     pulsing mic icon
 *   Thinking   → blue,    spinning claw icon
 *   Acting     → green,   gear icon
 *   Error      → dark red, X icon
 *
 * The button is draggable — users can reposition it anywhere on screen.
 */
class FloatingButtonManager(
    private val context: Context,
    private val onButtonClick: () -> Unit,
) {
    private val windowManager = context.getSystemService(Service.WINDOW_SERVICE) as WindowManager
    private var floatingView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var pulseAnimator: ValueAnimator? = null
    private var debugView: View? = null
    private var debugJob: Job? = null

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isBeingDragged = false

    fun show() {
        if (floatingView != null) return

        val layoutFlag = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = context.resources.displayMetrics.widthPixels - 180
            y = context.resources.displayMetrics.heightPixels / 2
        }

        val view = createView()
        floatingView = view

        try {
            windowManager.addView(view, params)
            Log.d(TAG, "Floating button shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating view", e)
        }

        // Debug panel disabled — interferes with fingerprint/sign-in overlays
        // if (BuildConfig.DEBUG) showDebugPanel()
    }

    /**
     * Temporarily hide/show all overlays. Called by ScreenshotManager so the
     * FAB and debug panel don't appear in screenshots sent to Gemini.
     */
    fun setOverlaysVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.INVISIBLE
        floatingView?.visibility = v
        debugView?.visibility = v
    }

    fun hide() {
        floatingView?.let { view ->
            try { windowManager.removeView(view) } catch (e: Exception) { }
            floatingView = null
        }
        debugView?.let { view ->
            try { windowManager.removeView(view) } catch (e: Exception) { }
            debugView = null
        }
        debugJob?.cancel()
        stopPulse()
    }

    fun updateState(state: AgentState) {
        val view = floatingView ?: return
        view.post {
            val container = view.findViewById<FrameLayout>(R.id.fab_container)
            val icon = view.findViewById<ImageView>(R.id.fab_icon)
            val statusText = view.findViewById<TextView>(R.id.fab_status)

            when (state) {
                is AgentState.Idle -> {
                    stopPulse()
                    container.setBackgroundResource(R.drawable.fab_bg_idle)
                    icon.setImageResource(R.drawable.ic_mic)
                    statusText.visibility = View.GONE
                }
                is AgentState.Running -> {
                    val statusMsg = state.statusMessage.lowercase()
                    when {
                        statusMsg.contains("listen") || statusMsg.contains("captur") -> {
                            startPulse(view)
                            container.setBackgroundResource(R.drawable.fab_bg_listening)
                            icon.setImageResource(R.drawable.ic_mic_active)
                        }
                        statusMsg.contains("think") || statusMsg.contains("screen") -> {
                            stopPulse()
                            startRotation(icon)
                            container.setBackgroundResource(R.drawable.fab_bg_thinking)
                            icon.setImageResource(R.drawable.ic_thinking)
                        }
                        else -> {
                            stopPulse()
                            container.setBackgroundResource(R.drawable.fab_bg_acting)
                            icon.setImageResource(R.drawable.ic_acting)
                        }
                    }
                    statusText.text = state.statusMessage
                    statusText.visibility = View.VISIBLE
                }
                is AgentState.Error -> {
                    stopPulse()
                    container.setBackgroundResource(R.drawable.fab_bg_error)
                    icon.setImageResource(R.drawable.ic_error)
                    // Show error message on FAB + Toast for debug visibility
                    statusText.text = state.message
                    statusText.visibility = View.VISIBLE
                    android.widget.Toast.makeText(context, "PhoneClaw: ${state.message}", android.widget.Toast.LENGTH_LONG).show()
                    // Auto-clear error after 4s so FAB returns to idle
                    view.postDelayed({
                        statusText.visibility = View.GONE
                        container.setBackgroundResource(R.drawable.fab_bg_idle)
                        icon.setImageResource(R.drawable.ic_mic)
                    }, 4000)
                }
            }
        }
    }

    private fun createView(): View {
        val view = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val container = FrameLayout(context).apply {
            id = R.id.fab_container
            val size = context.resources.getDimensionPixelSize(R.dimen.fab_size)
            layoutParams = FrameLayout.LayoutParams(size, size)
            setBackgroundResource(R.drawable.fab_bg_idle)
            elevation = 8f
        }

        val icon = ImageView(context).apply {
            id = R.id.fab_icon
            val iconSize = context.resources.getDimensionPixelSize(R.dimen.fab_icon_size)
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
            setImageResource(R.drawable.ic_mic)
            setColorFilter(ContextCompat.getColor(context, android.R.color.white))
        }

        val statusText = TextView(context).apply {
            id = R.id.fab_status
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = -40
            }
            setTextColor(ContextCompat.getColor(context, android.R.color.white))
            textSize = 10f
            visibility = View.GONE
            setShadowLayer(2f, 1f, 1f, 0xFF000000.toInt())
        }

        container.addView(icon)
        view.addView(container)
        view.addView(statusText)

        setupTouchListener(view, container)

        return view
    }

    private fun setupTouchListener(rootView: View, clickTarget: View) {
        rootView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isBeingDragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (!isBeingDragged && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) {
                        isBeingDragged = true
                    }
                    if (isBeingDragged) {
                        params?.x = initialX + dx
                        params?.y = initialY + dy
                        try {
                            windowManager.updateViewLayout(rootView, params)
                        } catch (e: Exception) { /* view may have been removed */ }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isBeingDragged) {
                        onButtonClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun startPulse(view: View) {
        stopPulse()
        val container = view.findViewById<View>(R.id.fab_container) ?: return
        pulseAnimator = ValueAnimator.ofFloat(1.0f, 1.15f, 1.0f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val scale = anim.animatedValue as Float
                container.scaleX = scale
                container.scaleY = scale
            }
            start()
        }
    }

    private fun startRotation(view: View) {
        ObjectAnimator.ofFloat(view, "rotation", 0f, 360f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        floatingView?.let { view ->
            val container = view.findViewById<View>(R.id.fab_container)
            container?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(150)?.start()
        }
    }

    // ─── Debug panel (DEBUG builds only) ───────────────────────────────────

    private fun showDebugPanel() {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels

        val debugParams = WindowManager.LayoutParams(
            (screenWidth * 0.92f).toInt(),
            (screenHeight * 0.28f).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 80
        }

        val scrollView = ScrollView(context)
        val textView = TextView(context).apply {
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            setTextColor(Color.argb(255, 180, 255, 180))
            typeface = Typeface.MONOSPACE
            textSize = 9.5f
            setPadding(12, 8, 12, 8)
        }
        scrollView.addView(textView)

        try {
            windowManager.addView(scrollView, debugParams)
            debugView = scrollView
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add debug view", e)
            return
        }

        // Collect DebugLog lines and update the text view live
        debugJob = CoroutineScope(Dispatchers.Main).launch {
            DebugLog.flow.collect { lines ->
                val text = lines.takeLast(20).joinToString("\n")
                textView.text = text
                scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }
}
