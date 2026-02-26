package com.cssupport.companion

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Real accessibility service that powers the Resolve automation engine.
 *
 * Maintains a singleton [AccessibilityEngine] instance accessible to the agent loop.
 * Forwards accessibility events to the engine for content change detection and
 * foreground app tracking.
 *
 * Lifecycle:
 * - [onServiceConnected]: Creates the engine, configures event types, sets singleton.
 * - [onAccessibilityEvent]: Routes events to the engine.
 * - [onDestroy]: Clears the singleton reference.
 */
class SupportAccessibilityService : AccessibilityService() {

    private val tag = "SupportA11yService"
    private var engine: AccessibilityEngine? = null

    /** Floating stop button overlay, shown when the agent is running. */
    private var floatingOverlay: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()

        engine = AccessibilityEngine(this)
        instance = this

        // Configure the service for comprehensive event monitoring.
        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED

            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC

            // Monitor all packages (not restricted to specific apps).
            packageNames = null

            // Enable tree querying.
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS

            notificationTimeout = 100
        }

        Log.i(tag, "Accessibility service connected; engine created")
        AgentLogStore.log("Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val localEngine = engine ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val packageName = event.packageName?.toString()
                val className = event.className?.toString()

                if (!packageName.isNullOrBlank()) {
                    // Only update the tracked foreground package for real app windows,
                    // not transient system overlays (keyboard, notification shade, etc.).
                    val isSystemOverlay = packageName == "com.android.systemui"
                        || packageName == "com.google.android.inputmethod.latin"
                        || packageName == "com.android.inputmethod.latin"
                        || className?.contains("PopupWindow") == true
                        || className?.contains("Toast") == true
                    if (!isSystemOverlay) {
                        localEngine.setCurrentPackage(packageName)
                        localEngine.setCurrentActivity(className)
                    }

                    // Do not log our own package to avoid noise.
                    if (packageName != "com.cssupport.companion") {
                        Log.d(tag, "Window state changed: $packageName / $className" +
                            if (isSystemOverlay) " (overlay, ignored)" else "")
                    }
                }

                localEngine.notifyContentChanged()
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                localEngine.notifyContentChanged()
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                localEngine.notifyContentChanged()
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                localEngine.notifyContentChanged()
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                // These are informational -- the engine can use content-changed
                // signal for observation purposes.
            }
        }
    }

    override fun onInterrupt() {
        Log.w(tag, "Accessibility service interrupted")
        AgentLogStore.log("Accessibility service interrupted")
    }

    override fun onDestroy() {
        Log.i(tag, "Accessibility service destroyed")
        AgentLogStore.log("Accessibility service disconnected")
        engine = null
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(tag, "Accessibility service unbound")
        engine = null
        if (instance === this) {
            instance = null
        }
        return super.onUnbind(intent)
    }

    // ── Floating stop button overlay ─────────────────────────────────────────

    /**
     * Show a draggable floating "Stop" pill over all apps.
     * Uses TYPE_ACCESSIBILITY_OVERLAY — no extra permission required.
     */
    fun showFloatingStopButton() {
        mainHandler.post {
            if (floatingOverlay != null) return@post // Already shown.
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val dp = resources.displayMetrics.density

                // Build the pill: [X] Stop Resolve
                val pill = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((12 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())

                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#E0CC3333")) // Semi-transparent red
                        cornerRadius = 28 * dp
                    }

                    // Close icon
                    val icon = ImageView(this@SupportAccessibilityService).apply {
                        setImageResource(R.drawable.ic_close)
                        setColorFilter(Color.WHITE)
                        val size = (20 * dp).toInt()
                        layoutParams = LinearLayout.LayoutParams(size, size).apply {
                            marginEnd = (6 * dp).toInt()
                        }
                    }
                    addView(icon)

                    // "Stop" label
                    val label = TextView(this@SupportAccessibilityService).apply {
                        text = "Stop Resolve"
                        setTextColor(Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                    addView(label)

                    // Elevation/shadow
                    elevation = 8 * dp
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    x = (16 * dp).toInt()
                    y = (100 * dp).toInt()
                }

                // Make it draggable + clickable.
                var initialX = 0
                var initialY = 0
                var initialTouchX = 0f
                var initialTouchY = 0f
                var isDragging = false

                pill.setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            isDragging = false
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (initialTouchX - event.rawX).toInt()
                            val dy = (event.rawY - initialTouchY).toInt()
                            if (!isDragging && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) {
                                isDragging = true
                            }
                            if (isDragging) {
                                params.x = initialX + dx // x is inverted for END gravity
                                params.y = initialY + dy
                                wm.updateViewLayout(pill, params)
                            }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!isDragging) {
                                // Tap — stop the agent.
                                CompanionAgentService.stop(this@SupportAccessibilityService)
                                hideFloatingStopButton()
                            }
                            true
                        }
                        else -> false
                    }
                }

                wm.addView(pill, params)
                floatingOverlay = pill
                Log.d(tag, "Floating stop button shown")
            } catch (e: Exception) {
                Log.e(tag, "Failed to show floating stop button", e)
            }
        }
    }

    /**
     * Remove the floating stop button overlay.
     */
    fun hideFloatingStopButton() {
        mainHandler.post {
            val overlay = floatingOverlay ?: return@post
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                wm.removeView(overlay)
                Log.d(tag, "Floating stop button hidden")
            } catch (e: Exception) {
                Log.w(tag, "Failed to hide floating stop button", e)
            }
            floatingOverlay = null
        }
    }

    companion object {
        /**
         * Singleton reference to the running accessibility service.
         * Null when the service is not connected.
         *
         * The agent loop accesses the [AccessibilityEngine] through this reference.
         */
        @Volatile
        var instance: SupportAccessibilityService? = null
            private set

        /**
         * Get the [AccessibilityEngine] if the service is connected, or null.
         */
        fun getEngine(): AccessibilityEngine? {
            return instance?.engine
        }

        /**
         * Check whether the accessibility service is currently running.
         */
        fun isRunning(): Boolean {
            return instance != null
        }
    }
}
