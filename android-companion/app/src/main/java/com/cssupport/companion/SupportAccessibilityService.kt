package com.cssupport.companion

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

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
                    localEngine.setCurrentPackage(packageName)
                    localEngine.setCurrentActivity(className)

                    // Do not log our own package to avoid noise.
                    if (packageName != "com.cssupport.companion") {
                        Log.d(tag, "Window state changed: $packageName / $className")
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
