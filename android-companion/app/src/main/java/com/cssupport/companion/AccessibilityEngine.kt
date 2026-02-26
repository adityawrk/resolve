package com.cssupport.companion

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Production accessibility automation engine.
 *
 * Parses the accessibility node tree from any foreground app into a structured
 * [ScreenState], and executes UI automation actions (click, type, scroll, etc.)
 * via the platform AccessibilityService APIs.
 *
 * All [AccessibilityNodeInfo] references obtained internally are recycled after use
 * to prevent memory leaks. Callers must NOT hold references to nodes returned by
 * finder methods beyond a single action -- capture what you need and let go.
 */
class AccessibilityEngine(private val service: AccessibilityService) {

    private val tag = "A11yEngine"

    // Signaled by the accessibility service when content changes.
    private val contentChanged = AtomicBoolean(false)

    // Last known foreground package, updated from accessibility events.
    private val currentPackageRef = AtomicReference<String?>(null)

    // Last known activity class, updated from TYPE_WINDOW_STATE_CHANGED.
    private val currentActivityRef = AtomicReference<String?>(null)

    // ── Event hooks (called from SupportAccessibilityService) ───────────────

    /** Notify that a content change event was received. */
    fun notifyContentChanged() {
        contentChanged.set(true)
    }

    /** Update the tracked foreground package name. */
    fun setCurrentPackage(packageName: String?) {
        currentPackageRef.set(packageName)
    }

    /** Update the tracked foreground activity class name. */
    fun setCurrentActivity(activityName: String?) {
        currentActivityRef.set(activityName)
    }

    // ── Screen capture ──────────────────────────────────────────────────────

    /**
     * Capture the full screen state from the current accessibility tree.
     * Returns a structured [ScreenState] with all visible UI elements.
     */
    fun captureScreenState(): ScreenState {
        val rootNode = service.rootInActiveWindow
        if (rootNode == null) {
            Log.w(tag, "captureScreenState: rootInActiveWindow is null")
            return ScreenState(
                packageName = currentPackageRef.get().orEmpty(),
                activityName = currentActivityRef.get(),
                elements = emptyList(),
                focusedElement = null,
                timestamp = System.currentTimeMillis(),
            )
        }

        return try {
            val elements = mutableListOf<UIElement>()
            var focusedElement: UIElement? = null

            parseNodeTree(rootNode, elements)

            // Find the focused/input-focused element.
            val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusedNode != null) {
                focusedElement = nodeToUIElement(focusedNode)
                safeRecycle(focusedNode)
            }

            ScreenState(
                packageName = rootNode.packageName?.toString().orEmpty(),
                activityName = currentActivityRef.get(),
                elements = elements,
                focusedElement = focusedElement,
                timestamp = System.currentTimeMillis(),
            )
        } finally {
            safeRecycle(rootNode)
        }
    }

    /**
     * Recursively parse the node tree into a flat list of [UIElement].
     * Only includes nodes that have meaningful content (text, content description,
     * or are interactive). This avoids flooding the LLM with layout containers.
     */
    private fun parseNodeTree(
        node: AccessibilityNodeInfo,
        output: MutableList<UIElement>,
        depth: Int = 0,
    ) {
        // Safety: cap recursion at 30 levels to avoid pathological trees.
        if (depth > 30) return

        val element = nodeToUIElement(node)

        // Include this node if it carries information the agent can use.
        val isInteresting = element.text?.isNotBlank() == true
            || element.contentDescription?.isNotBlank() == true
            || element.isClickable
            || element.isEditable
            || element.isScrollable
            || element.isCheckable

        if (isInteresting) {
            output.add(element)
        }

        // Recurse into children.
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            try {
                parseNodeTree(child, output, depth + 1)
            } finally {
                safeRecycle(child)
            }
        }
    }

    private fun nodeToUIElement(node: AccessibilityNodeInfo): UIElement {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        return UIElement(
            id = node.viewIdResourceName,
            className = node.className?.toString().orEmpty(),
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            isClickable = node.isClickable,
            isEditable = node.isEditable,
            isScrollable = node.isScrollable,
            isCheckable = node.isCheckable,
            isChecked = if (node.isCheckable) node.isChecked else null,
            isFocused = node.isFocused,
            isEnabled = node.isEnabled,
            bounds = bounds,
            childCount = node.childCount,
        )
    }

    // ── Finders ─────────────────────────────────────────────────────────────

    /**
     * Find all nodes whose text or content description contains [text] (case-insensitive).
     * Caller MUST recycle returned nodes when done.
     */
    fun findNodesByText(text: String): List<AccessibilityNodeInfo> {
        val root = service.rootInActiveWindow ?: return emptyList()
        return try {
            val found = root.findAccessibilityNodeInfosByText(text)
            found?.toList() ?: emptyList()
        } finally {
            safeRecycle(root)
        }
    }

    /**
     * Find a single node by text. Returns the first clickable match, or the first match.
     * Caller MUST recycle returned node.
     */
    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val nodes = findNodesByText(text)
        if (nodes.isEmpty()) return null

        // Prefer clickable nodes (buttons) over static text.
        val clickable = nodes.firstOrNull { it.isClickable }
        if (clickable != null) {
            // Recycle the others.
            nodes.filter { it !== clickable }.forEach { safeRecycle(it) }
            return clickable
        }

        // Return first, recycle rest.
        nodes.drop(1).forEach { safeRecycle(it) }
        return nodes.first()
    }

    /**
     * Find a node by its view ID resource name (e.g., "com.app:id/send_button").
     * Caller MUST recycle returned node.
     */
    fun findNodeById(viewId: String): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        return try {
            val found = root.findAccessibilityNodeInfosByViewId(viewId)
            found?.firstOrNull()
        } finally {
            safeRecycle(root)
        }
    }

    /**
     * Find all editable input fields currently on screen.
     * Caller MUST recycle returned nodes.
     */
    fun findInputFields(): List<AccessibilityNodeInfo> {
        val root = service.rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        try {
            collectNodes(root, result, predicate = { it.isEditable })
        } finally {
            safeRecycle(root)
        }
        return result
    }

    /**
     * Find all clickable elements on screen.
     */
    fun findClickableElements(): List<ClickableElement> {
        val root = service.rootInActiveWindow ?: return emptyList()
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        try {
            collectNodes(root, nodes, predicate = { it.isClickable && it.isEnabled })
            return nodes.map { node ->
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                ClickableElement(
                    text = node.text?.toString(),
                    contentDescription = node.contentDescription?.toString(),
                    className = node.className?.toString().orEmpty(),
                    viewId = node.viewIdResourceName,
                    bounds = bounds,
                )
            }
        } finally {
            nodes.forEach { safeRecycle(it) }
            safeRecycle(root)
        }
    }

    /**
     * Find all scrollable containers on screen.
     * Caller MUST recycle returned nodes.
     */
    fun findScrollableNodes(): List<AccessibilityNodeInfo> {
        val root = service.rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        try {
            collectNodes(root, result, predicate = { it.isScrollable })
        } finally {
            safeRecycle(root)
        }
        return result
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        output: MutableList<AccessibilityNodeInfo>,
        predicate: (AccessibilityNodeInfo) -> Boolean,
        depth: Int = 0,
    ) {
        if (depth > 30) return

        if (predicate(node)) {
            // Do NOT recycle these -- caller owns them.
            output.add(AccessibilityNodeInfo.obtain(node))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectNodes(child, output, predicate, depth + 1)
            } finally {
                safeRecycle(child)
            }
        }
    }

    // ── Actions ─────────────────────────────────────────────────────────────

    /**
     * Click a node. Tries performAction(ACTION_CLICK) first, then walks up to
     * find a clickable ancestor (common pattern in complex UIs). Falls back to
     * tap gesture at the node's center coordinates.
     */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        // Direct click.
        if (node.isClickable) {
            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (result) {
                Log.d(tag, "clickNode: direct ACTION_CLICK succeeded")
                return true
            }
        }

        // Walk up to clickable parent.
        var current = node.parent
        var depth = 0
        while (current != null && depth < 8) {
            if (current.isClickable) {
                val result = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                safeRecycle(current)
                if (result) {
                    Log.d(tag, "clickNode: parent click succeeded at depth=$depth")
                    return true
                }
                return false
            }
            val next = current.parent
            safeRecycle(current)
            current = next
            depth++
        }
        current?.let { safeRecycle(it) }

        // Gesture fallback: tap at center of bounds.
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return tapAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    /**
     * Click a node matched by text label. Handles finding and recycling internally.
     */
    fun clickByText(text: String): Boolean {
        val node = findNodeByText(text) ?: run {
            Log.w(tag, "clickByText: no node found for '$text'")
            return false
        }
        return try {
            clickNode(node)
        } finally {
            safeRecycle(node)
        }
    }

    /**
     * Set text on an editable node. Clears existing text first, then inserts.
     */
    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!node.isEditable) {
            Log.w(tag, "setText: node is not editable")
            return false
        }

        // Focus the node first.
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        // Clear existing text.
        val selectAll = Bundle().apply {
            putInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                0,
            )
            putInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                node.text?.length ?: 0,
            )
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectAll)

        // Set new text.
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.d(tag, "setText: result=$result for '${text.take(40)}...'")
        return result
    }

    /**
     * Find the first input field and type text into it.
     */
    fun typeIntoFirstField(text: String): Boolean {
        val fields = findInputFields()
        if (fields.isEmpty()) {
            Log.w(tag, "typeIntoFirstField: no input fields found")
            return false
        }
        return try {
            setText(fields.first(), text)
        } finally {
            fields.forEach { safeRecycle(it) }
        }
    }

    /**
     * Scroll forward in a scrollable container.
     */
    fun scrollForward(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    /**
     * Scroll backward in a scrollable container.
     */
    fun scrollBackward(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    /**
     * Scroll forward on the first scrollable container found on screen.
     */
    fun scrollScreenForward(): Boolean {
        val scrollables = findScrollableNodes()
        if (scrollables.isEmpty()) {
            Log.w(tag, "scrollScreenForward: no scrollable containers")
            return false
        }
        return try {
            scrollForward(scrollables.first())
        } finally {
            scrollables.forEach { safeRecycle(it) }
        }
    }

    /**
     * Scroll backward on the first scrollable container found on screen.
     */
    fun scrollScreenBackward(): Boolean {
        val scrollables = findScrollableNodes()
        if (scrollables.isEmpty()) return false
        return try {
            scrollBackward(scrollables.first())
        } finally {
            scrollables.forEach { safeRecycle(it) }
        }
    }

    /** Press the global Back button. */
    fun pressBack(): Boolean {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    /** Press the global Home button. */
    fun pressHome(): Boolean {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    /** Open the Recents / app switcher. */
    fun pressRecents(): Boolean {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
    }

    /** Open the notification shade. */
    fun openNotifications(): Boolean {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
    }

    // ── Gesture-based actions ───────────────────────────────────────────────

    /**
     * Perform a tap gesture at the given screen coordinates.
     * Uses GestureDescription API (requires API 24+, canPerformGestures=true).
     */
    fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        var result = false
        val latch = java.util.concurrent.CountDownLatch(1)

        service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    result = true
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    result = false
                    latch.countDown()
                }
            },
            null,
        )

        latch.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
        Log.d(tag, "tapAt($x, $y): result=$result")
        return result
    }

    /**
     * Perform a swipe gesture from (startX, startY) to (endX, endY).
     */
    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        var result = false
        val latch = java.util.concurrent.CountDownLatch(1)

        service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    result = true
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    result = false
                    latch.countDown()
                }
            },
            null,
        )

        latch.await(durationMs + 2000, java.util.concurrent.TimeUnit.MILLISECONDS)
        return result
    }

    // ── Waiting / observation ───────────────────────────────────────────────

    /**
     * Suspend until a content change is observed or timeout expires.
     * Returns true if content changed, false on timeout.
     */
    suspend fun waitForContentChange(timeoutMs: Long = 5000): Boolean {
        contentChanged.set(false)
        return withTimeoutOrNull(timeoutMs) {
            while (!contentChanged.get()) {
                kotlinx.coroutines.delay(100)
            }
            true
        } ?: false
    }

    /**
     * Wait for a specific text to appear on screen, polling periodically.
     */
    suspend fun waitForText(text: String, timeoutMs: Long = 10000, pollIntervalMs: Long = 500): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            while (true) {
                val nodes = findNodesByText(text)
                if (nodes.isNotEmpty()) {
                    nodes.forEach { safeRecycle(it) }
                    return@withTimeoutOrNull true
                }
                kotlinx.coroutines.delay(pollIntervalMs)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } ?: false
    }

    // ── Info ────────────────────────────────────────────────────────────────

    /** Get the current foreground package name. */
    fun getCurrentPackage(): String? = currentPackageRef.get()

    /** Get the current foreground activity class name. */
    fun getCurrentActivity(): String? = currentActivityRef.get()

    // ── Helpers ─────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun safeRecycle(node: AccessibilityNodeInfo?) {
        try {
            node?.recycle()
        } catch (_: Exception) {
            // Already recycled -- ignore.
        }
    }
}

// ── Data classes ─────────────────────────────────────────────────────────────

data class ScreenState(
    val packageName: String,
    val activityName: String?,
    val elements: List<UIElement>,
    val focusedElement: UIElement?,
    val timestamp: Long,
) {
    /** Format the screen state as a human-readable description for the LLM. */
    fun formatForLLM(): String {
        val sb = StringBuilder()
        sb.appendLine("## Current Screen")
        sb.appendLine("Package: $packageName")
        if (activityName != null) {
            sb.appendLine("Activity: $activityName")
        }
        sb.appendLine()

        // Collect chat-like messages (TextViews in scrollable containers).
        val textElements = elements.filter { !it.text.isNullOrBlank() && !it.isClickable && !it.isEditable }
        val buttons = elements.filter { it.isClickable && it.isEnabled && (it.text?.isNotBlank() == true || it.contentDescription?.isNotBlank() == true) }
        val inputFields = elements.filter { it.isEditable }
        val scrollables = elements.filter { it.isScrollable }

        if (textElements.isNotEmpty()) {
            sb.appendLine("## Visible Text")
            for (el in textElements.take(50)) {
                val label = el.text ?: el.contentDescription ?: continue
                sb.appendLine("- $label")
            }
            sb.appendLine()
        }

        if (buttons.isNotEmpty()) {
            sb.appendLine("## Available Buttons / Clickable Elements")
            for (btn in buttons.take(30)) {
                val label = btn.text ?: btn.contentDescription ?: "unlabeled"
                val idHint = if (btn.id != null) " [${btn.id}]" else ""
                sb.appendLine("- \"$label\"$idHint")
            }
            sb.appendLine()
        }

        if (inputFields.isNotEmpty()) {
            sb.appendLine("## Input Fields")
            for (field in inputFields) {
                val hint = field.contentDescription ?: field.text ?: "empty"
                val idHint = if (field.id != null) " [${field.id}]" else ""
                sb.appendLine("- Input field: \"$hint\"$idHint")
            }
            sb.appendLine()
        }

        if (scrollables.isNotEmpty()) {
            sb.appendLine("## Scrollable Containers: ${scrollables.size}")
            sb.appendLine()
        }

        if (focusedElement != null) {
            sb.appendLine("## Focused Element")
            val label = focusedElement.text ?: focusedElement.contentDescription ?: focusedElement.className
            sb.appendLine("- $label")
            sb.appendLine()
        }

        return sb.toString()
    }
}

data class UIElement(
    val id: String?,
    val className: String,
    val text: String?,
    val contentDescription: String?,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isCheckable: Boolean,
    val isChecked: Boolean?,
    val isFocused: Boolean,
    val isEnabled: Boolean,
    val bounds: Rect,
    val childCount: Int,
)

data class ClickableElement(
    val text: String?,
    val contentDescription: String?,
    val className: String,
    val viewId: String?,
    val bounds: Rect,
)
