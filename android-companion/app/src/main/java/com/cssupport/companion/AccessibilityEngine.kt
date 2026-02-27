package com.cssupport.companion

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
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
     *
     * Uses the event-tracked [currentPackageRef] as the authoritative source for the
     * foreground package. When [rootInActiveWindow] returns a stale window (package
     * doesn't match the event-tracked one), searches [service.windows] for the correct
     * app window to get an accurate node tree.
     */
    fun captureScreenState(): ScreenState {
        // Event-tracked package is the authoritative source for which app is in foreground.
        val eventPackage = currentPackageRef.get()

        var rootNode = service.rootInActiveWindow

        // If rootInActiveWindow has a stale package, try to find the correct window.
        if (rootNode != null && eventPackage != null
            && rootNode.packageName?.toString() != eventPackage
        ) {
            Log.d(tag, "rootInActiveWindow is stale (has ${rootNode.packageName}, " +
                "expected $eventPackage) — searching windows")
            safeRecycle(rootNode)
            rootNode = null
            try {
                for (window in service.windows) {
                    val windowRoot = window.root ?: continue
                    if (windowRoot.packageName?.toString() == eventPackage) {
                        rootNode = windowRoot
                        break
                    } else {
                        safeRecycle(windowRoot)
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Failed to enumerate windows: ${e.message}")
            }
        }

        // Fallback: if rootInActiveWindow is null, search all windows for any root.
        // Prefer windows matching the target app package (popups, drawers, dialogs).
        // Avoid systemui unless it's the only option.
        if (rootNode == null) {
            try {
                var bestRoot: AccessibilityNodeInfo? = null
                var bestPkg: String? = null
                for (window in service.windows) {
                    val windowRoot = window.root ?: continue
                    val pkg = windowRoot.packageName?.toString() ?: ""
                    // Prefer target app package.
                    if (eventPackage != null && pkg == eventPackage) {
                        if (bestRoot != null) safeRecycle(bestRoot)
                        bestRoot = windowRoot
                        bestPkg = pkg
                        break // Exact match, stop searching.
                    }
                    // Skip our own overlay and systemui/launcher.
                    if (pkg == "com.cssupport.companion") {
                        safeRecycle(windowRoot)
                        continue
                    }
                    val isSystemWindow = pkg.contains("systemui") || pkg.contains("launcher")
                    if (bestRoot == null || (!isSystemWindow && (bestPkg?.contains("systemui") == true))) {
                        if (bestRoot != null) safeRecycle(bestRoot)
                        bestRoot = windowRoot
                        bestPkg = pkg
                    } else {
                        safeRecycle(windowRoot)
                    }
                }
                if (bestRoot != null) {
                    rootNode = bestRoot
                    Log.d(tag, "Found root via service.windows: $bestPkg")
                }
            } catch (e: Exception) {
                Log.w(tag, "Failed to enumerate windows for fallback: ${e.message}")
            }
        }

        if (rootNode == null) {
            Log.w(tag, "captureScreenState: no valid root node found")
            return ScreenState(
                packageName = eventPackage.orEmpty(),
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

            // Prefer event-tracked package, fall back to root node's package.
            val packageName = eventPackage
                ?: rootNode.packageName?.toString().orEmpty()

            ScreenState(
                packageName = packageName,
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
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
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
            val result = found?.firstOrNull()
            // Recycle any extra results to prevent binder leaks.
            found?.drop(1)?.forEach { safeRecycle(it) }
            result
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

    // ── Index-based actions (for numbered element references) ─────────────

    /**
     * Click an element by its numeric index from the current [ScreenState.elementIndex].
     *
     * This is the preferred action method -- the LLM references elements by [N] number
     * from the screen representation, and this resolves to the exact node on screen.
     *
     * @param index 1-based element index from ScreenState.elementIndex
     * @param screenState the ScreenState whose elementIndex was shown to the LLM
     * @return true if click succeeded
     */
    fun clickByIndex(index: Int, screenState: ScreenState): Boolean {
        val targetElement = screenState.getElementById(index)
        if (targetElement == null) {
            Log.w(tag, "clickByIndex: no element at index $index")
            return false
        }

        // Find the live node closest to the expected bounds.
        // Multiple elements can share the same label ("Order #28", "Help", etc.),
        // so we disambiguate by picking the node whose bounds center is nearest
        // to the target element's stored bounds.
        val label = targetElement.text ?: targetElement.contentDescription
        if (label != null) {
            val nodes = findNodesByText(label)
            if (nodes.isNotEmpty()) {
                val targetCx = targetElement.bounds.centerX()
                val targetCy = targetElement.bounds.centerY()
                val best = nodes.minByOrNull { node ->
                    val b = Rect()
                    node.getBoundsInScreen(b)
                    val dx = b.centerX() - targetCx
                    val dy = b.centerY() - targetCy
                    dx * dx + dy * dy
                }
                if (best != null) {
                    val result = clickNode(best)
                    nodes.forEach { safeRecycle(it) }
                    return result
                }
                nodes.forEach { safeRecycle(it) }
            }
        }

        // Fallback: tap at the center of the stored bounds.
        return tapAt(
            targetElement.bounds.centerX().toFloat(),
            targetElement.bounds.centerY().toFloat(),
        )
    }

    /**
     * Set text on an element referenced by its numeric index.
     *
     * @param index 1-based element index from ScreenState.elementIndex
     * @param text the text to type
     * @param screenState the ScreenState whose elementIndex was shown to the LLM
     * @return true if text was set successfully
     */
    fun setTextByIndex(index: Int, text: String, screenState: ScreenState): Boolean {
        val targetElement = screenState.getElementById(index)
        if (targetElement == null) {
            Log.w(tag, "setTextByIndex: no element at index $index")
            return false
        }
        if (!targetElement.isEditable) {
            Log.w(tag, "setTextByIndex: element $index is not editable")
            return false
        }

        // Find a live editable node near the target bounds.
        val fields = findInputFields()
        if (fields.isEmpty()) {
            Log.w(tag, "setTextByIndex: no input fields on screen")
            return false
        }

        // Find the input field closest to the indexed element's center.
        val targetCx = targetElement.bounds.centerX()
        val targetCy = targetElement.bounds.centerY()
        val closest = fields.minByOrNull { field ->
            val bounds = Rect()
            field.getBoundsInScreen(bounds)
            val dx = bounds.centerX() - targetCx
            val dy = bounds.centerY() - targetCy
            dx * dx + dy * dy
        }

        return try {
            if (closest != null) setText(closest, text) else false
        } finally {
            fields.forEach { safeRecycle(it) }
        }
    }

    /**
     * Read back the current text content of an input field by index.
     * Used for post-action verification of text input.
     */
    fun readFieldTextByIndex(index: Int, screenState: ScreenState): String? {
        val targetElement = screenState.getElementById(index)
            ?: return null
        if (!targetElement.isEditable) return null

        val fields = findInputFields()
        if (fields.isEmpty()) return null

        val targetCx = targetElement.bounds.centerX()
        val targetCy = targetElement.bounds.centerY()
        val closest = fields.minByOrNull { field ->
            val bounds = Rect()
            field.getBoundsInScreen(bounds)
            val dx = bounds.centerX() - targetCx
            val dy = bounds.centerY() - targetCy
            dx * dx + dy * dy
        }

        val result = closest?.text?.toString()
        fields.forEach { safeRecycle(it) }
        return result
    }

    /**
     * Read back the text of the first input field on screen.
     * Used for post-action verification when no specific index is available.
     */
    fun readFirstFieldText(): String? {
        val fields = findInputFields()
        if (fields.isEmpty()) return null
        val text = fields.first().text?.toString()
        fields.forEach { safeRecycle(it) }
        return text
    }

    // ── Screen stabilization ────────────────────────────────────────────────

    /**
     * Wait until the screen content stabilizes (stops changing).
     *
     * Technique from DroidRun: poll-compare-repeat until two consecutive captures
     * produce the same fingerprint, meaning the screen has finished loading/animating.
     *
     * @param maxWaitMs maximum time to wait for stabilization
     * @param pollIntervalMs time between captures
     * @return the stable [ScreenState], or the last captured state on timeout
     */
    suspend fun waitForStableScreen(
        maxWaitMs: Long = 3000,
        pollIntervalMs: Long = 300,
    ): ScreenState {
        // Initial wait to let animations/transitions begin (250ms is ~15 frames at 60fps).
        delay(250)

        var previousFingerprint = ""
        var lastState = captureScreenState()
        val deadline = System.currentTimeMillis() + maxWaitMs

        while (System.currentTimeMillis() < deadline) {
            val currentFingerprint = lastState.fingerprint()
            if (currentFingerprint == previousFingerprint && previousFingerprint.isNotEmpty()) {
                // Two consecutive captures are identical -- screen is stable.
                Log.d(tag, "Screen stabilized after ${maxWaitMs - (deadline - System.currentTimeMillis())}ms")
                return lastState
            }
            previousFingerprint = currentFingerprint
            delay(pollIntervalMs)
            lastState = captureScreenState()
        }

        Log.d(tag, "Screen stabilization timed out after ${maxWaitMs}ms")
        return lastState
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
    /**
     * Indexed element map: maps numeric IDs (1-based) to UIElements.
     * Built lazily on first access by [buildElementIndex]. Every interactive or
     * informational element gets a stable numeric ID for the current screen capture,
     * allowing the LLM to reference elements by number instead of by text label.
     *
     * IDs reset with each new screen capture -- they are per-turn, not persistent.
     */
    val elementIndex: Map<Int, UIElement> by lazy { buildElementIndex() }

    /**
     * Build the element index, assigning 1-based IDs to all meaningful elements.
     * Elements are ordered: top-bar left-to-right, then content top-to-bottom,
     * then bottom-bar left-to-right.
     */
    private fun buildElementIndex(): Map<Int, UIElement> {
        val screenWidth = elements.maxOfOrNull { it.bounds.right } ?: 1080
        val screenHeight = elements.maxOfOrNull { it.bounds.bottom } ?: 2400

        val meaningful = filterMeaningful(screenWidth, screenHeight)
        val deduped = deduplicateElements(meaningful)

        val topBarThreshold = screenHeight / 8
        val bottomBarThreshold = screenHeight * 7 / 8

        val topBar = deduped.filter { it.bounds.centerY() < topBarThreshold }
            .sortedBy { it.bounds.centerX() }
        val content = deduped.filter {
            it.bounds.centerY() in topBarThreshold..bottomBarThreshold
        }.sortedBy { it.bounds.centerY() }
        val bottomBar = deduped.filter { it.bounds.centerY() > bottomBarThreshold }
            .sortedBy { it.bounds.centerX() }

        val ordered = topBar + content + bottomBar
        val map = mutableMapOf<Int, UIElement>()
        ordered.forEachIndexed { idx, el ->
            map[idx + 1] = el
        }
        return map
    }

    /**
     * Look up an element by its numeric index.
     * Returns null if the index is out of range.
     */
    fun getElementById(index: Int): UIElement? = elementIndex[index]

    /**
     * Get a concise summary of this screen state for observation masking.
     * Used to replace verbose screen dumps in older conversation turns.
     */
    fun toMaskedSummary(phase: String): String {
        val simpleName = activityName?.substringAfterLast(".") ?: "unknown"
        val interactiveCount = elements.count { it.isClickable || it.isEditable }
        return "[Screen: $packageName/$simpleName, ${elements.size} elements ($interactiveCount interactive), phase: $phase]"
    }

    /**
     * Get the labels of all elements that are present in this screen but not in [other].
     * Used for differential state tracking.
     */
    fun newElementLabels(other: ScreenState?): List<String> {
        if (other == null) return emptyList()
        val otherLabels = other.elements.mapNotNull { el ->
            (el.text ?: el.contentDescription)?.take(40)
        }.toSet()
        return elements.mapNotNull { el ->
            val label = (el.text ?: el.contentDescription)?.take(40) ?: return@mapNotNull null
            if (label !in otherLabels) label else null
        }.take(10)
    }

    /**
     * Get the labels of elements present in [other] but missing from this screen.
     */
    fun removedElementLabels(other: ScreenState?): List<String> {
        if (other == null) return emptyList()
        val currentLabels = elements.mapNotNull { el ->
            (el.text ?: el.contentDescription)?.take(40)
        }.toSet()
        return other.elements.mapNotNull { el ->
            val label = (el.text ?: el.contentDescription)?.take(40) ?: return@mapNotNull null
            if (label !in currentLabels) label else null
        }.take(10)
    }

    /**
     * Format the screen state for the LLM with navigation-first presentation.
     *
     * ## Key design principles (research-backed):
     * 1. **Navigation elements shown FIRST** with `>>>` markers (primacy effect)
     * 2. **Product/content elements lose [N] indices** when navigating — can't click what has no ID
     * 3. **`isNavigationElement()`** classifier separates nav from noise
     * 4. **Phase-aware**: During NAVIGATING_TO_SUPPORT, products are collapsed.
     *    During IN_CHAT, all elements are shown.
     *
     * The system prompt tells the LLM: "ONLY click elements marked >>>".
     * This mechanical rule is far more reliable than "don't click products".
     *
     * @param previousScreen optional previous screen for marking new elements
     * @param collapseContent true to hide product/content [N] indices (navigation phases)
     */
    fun formatForLLM(previousScreen: ScreenState? = null, collapseContent: Boolean = false): String {
        val sb = StringBuilder()
        sb.appendLine("Package: $packageName")
        if (activityName != null) {
            val simpleName = activityName.substringAfterLast(".")
            if (simpleName.isNotBlank()) {
                sb.appendLine("Screen: $simpleName")
            }
        }

        val screenWidth = elements.maxOfOrNull { it.bounds.right } ?: 1080
        val screenHeight = elements.maxOfOrNull { it.bounds.bottom } ?: 2400

        val indexedElements = elementIndex
        if (indexedElements.isEmpty()) {
            sb.appendLine("(empty screen)")
            return sb.toString()
        }

        val prevLabels = previousScreen?.elements?.mapNotNull { el ->
            (el.text ?: el.contentDescription)?.take(40)
        }?.toSet()

        // Partition into zones.
        val topBarThreshold = screenHeight / 8
        val bottomBarThreshold = screenHeight * 7 / 8

        val topBar = indexedElements.filter { (_, el) ->
            el.bounds.centerY() < topBarThreshold
        }.toSortedMap()
        val content = indexedElements.filter { (_, el) ->
            el.bounds.centerY() in topBarThreshold..bottomBarThreshold
        }.toSortedMap()
        val bottomBar = indexedElements.filter { (_, el) ->
            el.bounds.centerY() > bottomBarThreshold
        }.toSortedMap()

        val screenPattern = detectScreenPattern(indexedElements.values.toList())
        if (screenPattern.isNotBlank()) {
            sb.appendLine("Layout: $screenPattern")
        }
        sb.appendLine()

        // === NAVIGATION FIRST (bottom bar tabs) ===
        // Bottom bar is shown first because it almost always contains the path to support.
        // Primacy effect: LLM pays most attention to first elements.
        if (bottomBar.isNotEmpty()) {
            sb.appendLine("[NAVIGATION -- click these to find support]")
            for ((id, el) in bottomBar) {
                val label = el.text ?: el.contentDescription
                // Show unlabeled clickable icons — they may be navigation.
                if (label.isNullOrBlank() && !el.isClickable) continue
                val line = formatSingleElement(id, el, screenWidth, prevLabels)
                sb.appendLine("  >>> $line")
            }
            sb.appendLine()
        }

        // === TOP BAR ===
        if (topBar.isNotEmpty()) {
            sb.appendLine("[TOP BAR]")
            for ((id, el) in topBar) {
                val label = el.text ?: el.contentDescription
                // Show unlabeled clickable icons — they're often critical nav (sidebar, profile, menu).
                if (label.isNullOrBlank() && !el.isClickable) continue
                val isNav = isNavigationElement(el)
                val prefix = if (isNav) ">>>" else "   "
                val line = formatSingleElement(id, el, screenWidth, prevLabels)
                sb.appendLine("  $prefix $line")
            }
            sb.appendLine()
        }

        // === CONTENT ===
        if (content.isNotEmpty()) {
            // Separate navigation-relevant, input fields, and noise.
            val navContent = content.filter { (_, el) -> isNavigationElement(el) }
            val inputContent = content.filter { (_, el) -> el.isEditable }
            val otherContent = content.filter { (_, el) ->
                !isNavigationElement(el) && !el.isEditable
            }

            // Always show navigation-relevant content and input fields with >>> and [N].
            if (navContent.isNotEmpty() || inputContent.isNotEmpty()) {
                sb.appendLine("[CONTENT]")
                for ((id, el) in navContent) {
                    val line = formatSingleElement(id, el, screenWidth, prevLabels)
                    sb.appendLine("  >>> $line")
                }
                for ((id, el) in inputContent) {
                    val line = formatSingleElement(id, el, screenWidth, prevLabels)
                    sb.appendLine("  >>> $line")
                }
                sb.appendLine()
            }

            // Other content: collapse when navigating, show when on support/chat pages.
            if (otherContent.isNotEmpty()) {
                if (collapseContent) {
                    // Navigating phase: collapse products to summary, NO [N] IDs.
                    val labels = otherContent.values
                        .mapNotNull { it.text ?: it.contentDescription }
                        .filter { it.length in 2..60 }
                        .take(8)
                    sb.appendLine("[${otherContent.size} other items -- NOT paths to support]")
                    if (labels.isNotEmpty()) {
                        sb.appendLine("  ~ ${labels.joinToString(", ") { "\"$it\"" }}")
                    }
                } else {
                    // Support/chat/order phase: show all with [N] IDs.
                    if (navContent.isEmpty() && inputContent.isEmpty()) {
                        sb.appendLine("[CONTENT]")
                    }
                    var itemCount = 0
                    for ((id, el) in otherContent) {
                        itemCount++
                        if (itemCount > 30) {
                            sb.appendLine("  (${otherContent.size - 30} more items...)")
                            break
                        }
                        val line = formatSingleElement(id, el, screenWidth, prevLabels)
                        sb.appendLine("  $line")
                    }
                }
                sb.appendLine()
            }

            // Scroll hint.
            val scrollables = content.values.filter { it.isScrollable }
            if (scrollables.isNotEmpty()) {
                sb.appendLine("  (scrollable)")
            }
        }

        if (focusedElement != null) {
            val label = focusedElement.text ?: focusedElement.contentDescription ?: focusedElement.className
            sb.appendLine("Focused: $label")
        }

        return sb.toString()
    }

    /**
     * Classify whether an element is a navigation element (leads to account/orders/help)
     * vs. a content/product element (food items, deals, listings).
     *
     * This is the key classifier that determines which elements get `>>>` markers
     * and which get collapsed. Navigation elements are always clickable by the LLM.
     */
    private fun isNavigationElement(el: UIElement): Boolean {
        val label = (el.text ?: el.contentDescription ?: "").lowercase()
        val className = el.className.lowercase()

        // Tabs are almost always navigation.
        if (className.contains("tab")) return true

        // Navigation keywords.
        // Exact-match keywords (short words that would cause false positives with contains).
        val exactNavKeywords = setOf("me", "more", "home")
        if (exactNavKeywords.contains(label.trim())) return true

        // Substring-match keywords (safe to match anywhere in the label).
        val navKeywords = listOf(
            "account", "profile", "settings", "my account",
            "orders", "order history", "my orders", "your orders", "past orders",
            "order details", "track order",
            "help", "support", "contact", "get help", "report", "issue",
            "contact us", "contact store", "queries",
            "chat", "live chat", "talk to us", "message us",
            "back", "close", "cancel", "navigate up", "skip",
            "sign in", "log in", "sign out", "log out", "login",
            "refund", "return", "complaint",
            "main menu", "sidebar", "hamburger",
        )
        if (navKeywords.any { label.contains(it) }) return true

        // Icon buttons (unlabeled image buttons) in top/bottom bars are often navigation.
        if ((label == "unlabeled" || label.isBlank())
            && (className.contains("imagebutton") || className.contains("imageview"))
            && el.isClickable
        ) return true

        return false
    }

    /**
     * Format a single element with its numeric ID, type hint, label, position, and state.
     */
    private fun formatSingleElement(
        id: Int,
        el: UIElement,
        screenWidth: Int,
        prevLabels: Set<String>?,
    ): String {
        val sb = StringBuilder()
        sb.append("[$id] ")

        // Type hint.
        val typeHint = when {
            el.isEditable -> "INPUT"
            el.isScrollable -> "scrollable"
            el.isClickable -> buttonTypeHint(el)
            else -> "text"
        }
        sb.append("$typeHint: ")

        // Label.
        val label = el.text ?: el.contentDescription ?: "unlabeled"
        val isUnlabeled = label == "unlabeled"
        val truncated = if (label.length > 120) label.take(117) + "..." else label
        sb.append("\"$truncated\"")

        // Position hint (fine-grained for unlabeled elements).
        sb.append(positionHint(el.bounds, screenWidth, isUnlabeled))

        // State annotations.
        if (el.isCheckable && el.isChecked == true) sb.append(" [CHECKED]")
        if (el.isCheckable && el.isChecked == false) sb.append(" [UNCHECKED]")
        if (el.isClickable && !el.isEnabled) sb.append(" [DISABLED]")

        // Selected state for tabs.
        val className = el.className.lowercase()
        if (className.contains("tab") && el.isChecked == true) {
            // Already handled by CHECKED above, but remap label.
            sb.append(" [SELECTED]")
        }

        // NEW marker for elements not on previous screen.
        if (prevLabels != null) {
            val elLabel = (el.text ?: el.contentDescription)?.take(40)
            if (elLabel != null && elLabel !in prevLabels) {
                sb.append(" <-- NEW")
            }
        }

        return sb.toString()
    }

    /**
     * Format a zone of indexed elements.
     */
    private fun formatIndexedElements(
        entries: Map<Int, UIElement>,
        sb: StringBuilder,
        screenWidth: Int,
        prevLabels: Set<String>?,
    ) {
        for ((id, el) in entries) {
            val label = el.text ?: el.contentDescription
            if (label.isNullOrBlank()) continue
            val line = formatSingleElement(id, el, screenWidth, prevLabels)
            sb.appendLine("  $line")
        }
    }

    // ── Filtering helpers ───────────────────────────────────────────────────

    private fun filterMeaningful(screenWidth: Int, screenHeight: Int): List<UIElement> {
        return elements.filter { el ->
            val hasContent = !el.text.isNullOrBlank()
                || !el.contentDescription.isNullOrBlank()
                || el.isClickable || el.isEditable || el.isScrollable || el.isCheckable
            val hasSize = el.bounds.width() > 2 && el.bounds.height() > 2
            val isOnScreen = el.bounds.right > 0 && el.bounds.bottom > 0
                && el.bounds.left < screenWidth && el.bounds.top < screenHeight
            hasContent && hasSize && isOnScreen
        }
    }

    private fun deduplicateElements(elements: List<UIElement>): List<UIElement> {
        return elements.distinctBy { el ->
            val label = el.text ?: el.contentDescription ?: ""
            val posKey = "${el.bounds.centerX() / 20},${el.bounds.centerY() / 20}"
            "$label|$posKey|${el.isClickable}|${el.isEditable}"
        }
    }

    /**
     * Detect common screen patterns from the element composition.
     * Gives the LLM a fast high-level understanding of what kind of screen it is looking at.
     */
    private fun detectScreenPattern(elements: List<UIElement>): String {
        val allText = elements.mapNotNull { it.text?.lowercase() ?: it.contentDescription?.lowercase() }
        val hasInputField = elements.any { it.isEditable }
        val clickables = elements.filter { it.isClickable }

        // Chat/messaging screen: has input field + send button + multiple text items.
        if (hasInputField && allText.any { it.contains("send") || it.contains("type") || it.contains("message") }) {
            return "Chat/messaging interface"
        }

        // Order list: contains order-related keywords.
        val orderKeywords = listOf("order", "orders", "#", "delivered", "cancelled", "in progress", "track")
        if (orderKeywords.count { kw -> allText.any { it.contains(kw) } } >= 2) {
            return "Order list/history"
        }

        // Support/help page: contains help-related keywords.
        val helpKeywords = listOf("help", "support", "contact", "faq", "chat with us", "get help", "report")
        if (helpKeywords.count { kw -> allText.any { it.contains(kw) } } >= 2) {
            return "Help/support page"
        }

        // Profile/account page.
        val profileKeywords = listOf("profile", "account", "settings", "sign out", "log out", "edit profile", "my account")
        if (profileKeywords.count { kw -> allText.any { it.contains(kw) } } >= 2) {
            return "Profile/account page"
        }

        // Home/feed: lots of clickable items, images, promotional content.
        if (clickables.size > 15 && !hasInputField) {
            return "Home/feed (many items)"
        }

        // Bottom navigation present.
        val screenHeight = elements.maxOfOrNull { it.bounds.bottom } ?: 2400
        val bottomNav = elements.filter {
            it.isClickable && it.bounds.centerY() > screenHeight * 7 / 8
        }
        if (bottomNav.size >= 3) {
            return "Screen with bottom navigation (${bottomNav.size} tabs)"
        }

        return ""
    }

    /**
     * Generate a concise position hint (e.g., "(left)", "(right)", "(center)").
     * Helps the LLM distinguish between elements with similar labels.
     */
    private fun positionHint(bounds: Rect, screenWidth: Int, isUnlabeled: Boolean = false): String {
        val centerX = bounds.centerX()
        // For unlabeled elements, use fine-grained 5-zone hints so the LLM can
        // distinguish between multiple unlabeled icons in the same bar.
        return if (isUnlabeled) {
            when {
                centerX > screenWidth * 85 / 100 -> " (far-right)"
                centerX > screenWidth * 2 / 3 -> " (right)"
                centerX < screenWidth * 15 / 100 -> " (far-left)"
                centerX < screenWidth / 3 -> " (left)"
                else -> " (center)"
            }
        } else {
            when {
                centerX < screenWidth / 3 -> " (left)"
                centerX > screenWidth * 2 / 3 -> " (right)"
                else -> ""
            }
        }
    }

    /**
     * Classify a clickable element type for the LLM.
     */
    private fun buttonTypeHint(el: UIElement): String {
        val className = el.className.lowercase()
        val label = (el.text ?: el.contentDescription ?: "").lowercase()

        return when {
            className.contains("imagebutton") || className.contains("imageview") -> "icon-btn"
            className.contains("checkbox") || el.isCheckable -> "checkbox"
            className.contains("switch") -> "switch"
            className.contains("tab") -> "tab"
            className.contains("chip") -> "chip"
            className.contains("radiobutton") -> "radio"
            // Navigation-like labels.
            label in listOf("back", "close", "cancel", "navigate up", "menu") -> "nav-btn"
            else -> "btn"
        }
    }

    /**
     * Generate a compact fingerprint of the screen state for change detection.
     * Two screen states with the same fingerprint are considered identical.
     */
    fun fingerprint(): String {
        val sig = buildString {
            append(packageName)
            append("|")
            append(activityName ?: "")
            append("|")
            // Use a hash of element labels + positions for fast comparison.
            val elemSig = elements
                .filter { it.text?.isNotBlank() == true || it.contentDescription?.isNotBlank() == true }
                .take(20)
                .joinToString(",") { el ->
                    val label = (el.text ?: el.contentDescription ?: "").take(20)
                    "$label@${el.bounds.centerX() / 50}"
                }
            append(elemSig)
        }
        return sig.hashCode().toString(16)
    }

    /**
     * Get a list of all element labels on this screen, for comparison purposes.
     */
    fun elementLabels(): Set<String> {
        return elements.mapNotNull { el ->
            (el.text ?: el.contentDescription)?.take(40)
        }.toSet()
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
