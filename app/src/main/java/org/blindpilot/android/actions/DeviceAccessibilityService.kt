package org.blindpilot.android.actions

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.blindpilot.android.core.Cancellation

@Suppress("DEPRECATION")
class DeviceAccessibilityService : AccessibilityService(), DeviceExecutor {
    private data class Target(val path: List<Int>, val node: AccessibilityNodeInfo, val signature: String)
    private var generation = 0L
    private var windowId = -1
    private var appPackage = ""
    private val targets = mutableMapOf<String, Target>()

    override fun onServiceConnected() { current = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { TaskService.cancelActive() }
    override fun onUnbind(intent: Intent?): Boolean {
        TaskService.cancelActive()
        if (current === this) current = null
        clearTargets()
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        TaskService.cancelActive()
        if (current === this) current = null
        clearTargets()
        super.onDestroy()
    }

    @Synchronized override fun snapshot(): DeviceSnapshot {
        clearTargets()
        val root = rootInActiveWindow ?: error("No accessible window is available. Unlock the device and open an app.")
        try {
            generation++
            windowId = root.windowId
            appPackage = root.packageName?.toString().orEmpty()
            val nodes = mutableListOf<DeviceNode>()
            var visited = 0
            fun visit(node: AccessibilityNodeInfo, path: List<Int>, depth: Int) {
                if (visited++ >= 512 || depth > 30 || nodes.size >= 256) return
                if (node.isVisibleToUser) {
                    val id = "$generation:${nodes.size}"
                    nodes.add(DeviceNode(id, label(node), actions(node)))
                    targets[id] = Target(path, AccessibilityNodeInfo.obtain(node), signature(node))
                }
                for (index in 0 until node.childCount.coerceAtMost(256)) {
                    if (visited >= 512 || nodes.size >= 256) break
                    node.getChild(index)?.let { child ->
                        try { visit(child, path + index, depth + 1) } finally { child.recycle() }
                    }
                }
            }
            visit(root, emptyList(), 0)
            return DeviceSnapshot(generation, appPackage, nodes)
        } finally { root.recycle() }
    }

    @Synchronized override fun execute(action: DeviceAction, cancellation: Cancellation): String {
        cancellation.check()
        require(action.generation == generation) { "Stale screen. Observe and approve again." }
        when (action.type) {
            "open_app" -> {
                val intent = packageManager.getLaunchIntentForPackage(action.packageName)
                    ?: error("App cannot be opened: ${action.packageName}")
                cancellation.check()
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            "back", "home", "recents" -> {
                val code = when (action.type) {
                    "back" -> GLOBAL_ACTION_BACK
                    "home" -> GLOBAL_ACTION_HOME
                    else -> GLOBAL_ACTION_RECENTS
                }
                cancellation.check()
                check(performGlobalAction(code)) { "Android did not accept ${action.type}. Observe the screen before continuing." }
            }
            else -> executeNode(action, cancellation)
        }
        // An accepted action is not proof of success, and cannot be replayed with the same snapshot.
        generation++
        clearTargets()
        return "Android accepted ${action.type}; the next observation must verify its result."
    }

    private fun executeNode(action: DeviceAction, cancellation: Cancellation) {
        val target = targets[action.target] ?: error("Stale target. Observe and approve again.")
        var node = rootInActiveWindow ?: error("The target window closed")
        try {
            require(node.windowId == windowId && node.packageName?.toString() == appPackage) { "The active app changed. Observe and approve again." }
            for (index in target.path) {
                val child = node.getChild(index) ?: error("The target moved. Observe and approve again.")
                node.recycle()
                node = child
            }
            require(node == target.node && node.refresh() && signature(node) == target.signature && node.isVisibleToUser && node.isEnabled) {
                "The target changed. Observe and approve again."
            }
            require(action.type in actions(node)) { "The requested action is unavailable on this target" }
            val code = when (action.type) {
                "click" -> AccessibilityNodeInfo.ACTION_CLICK
                "long_click" -> AccessibilityNodeInfo.ACTION_LONG_CLICK
                "scroll_forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                "scroll_backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                "set_text" -> AccessibilityNodeInfo.ACTION_SET_TEXT
                else -> error("Unsupported device action")
            }
            val args = if (action.type == "set_text") Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text) } else null
            cancellation.check()
            check(node.performAction(code, args)) { "Android did not accept the action. Its result is uncertain; do not retry automatically." }
        } finally { node.recycle() }
    }

    private fun actions(node: AccessibilityNodeInfo): List<String> {
        if (!node.isEnabled || !node.isVisibleToUser) return emptyList()
        val available = node.actionList.map { it.id }.toSet()
        return listOf("click" to AccessibilityNodeInfo.ACTION_CLICK, "long_click" to AccessibilityNodeInfo.ACTION_LONG_CLICK,
            "scroll_forward" to AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, "scroll_backward" to AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
            "set_text" to AccessibilityNodeInfo.ACTION_SET_TEXT).filter { it.second in available }.map { it.first }
    }

    private fun sensitive(node: AccessibilityNodeInfo): Boolean = node.isPassword || (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive)
    private fun label(node: AccessibilityNodeInfo): String = if (sensitive(node)) "Sensitive field; content hidden" else
        listOfNotNull(node.contentDescription?.toString(), node.text?.toString(), node.hintText?.toString(), node.className?.toString())
            .filter { it.isNotBlank() }.distinct().joinToString("; ").take(512)
    private fun signature(node: AccessibilityNodeInfo): String {
        val bounds = Rect().also(node::getBoundsInScreen)
        return listOf(node.viewIdResourceName, node.className, node.text, node.contentDescription, node.isPassword,
            node.isChecked, node.isSelected, node.isEnabled, node.isVisibleToUser, bounds, actions(node)).joinToString("\u0000")
    }
    @Synchronized private fun clearTargets() { targets.values.forEach { it.node.recycle() }; targets.clear() }

    /** Window-only capture avoids other apps and refuses sensitive or incompletely inspected trees. */
    fun captureScreenshot(cancellation: Cancellation, result: (Bitmap?, String?) -> Unit) {
        cancellation.check()
        if (Build.VERSION.SDK_INT < 34) { result(null, "Safe window screenshot requires Android 14 or later"); return }
        val root = rootInActiveWindow ?: run { result(null, "No accessible window"); return }
        val capturedWindow = root.windowId
        var visited = 0
        fun unsafe(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (++visited > 512 || depth > 30 || sensitive(node) || node.childCount > 256) return true
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: return true
                try { if (unsafe(child, depth + 1)) return true } finally { child.recycle() }
            }
            return false
        }
        val blocked = try { unsafe(root, 0) } finally { root.recycle() }
        if (blocked) { result(null, "Screenshot blocked: sensitive or uninspectable window content"); return }
        cancellation.check()
        takeScreenshotOfWindow(capturedWindow, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val buffer = screenshot.hardwareBuffer
                try {
                    cancellation.check()
                    val currentRoot = rootInActiveWindow
                    val unchanged = try { currentRoot != null && currentRoot.windowId == capturedWindow && !unsafe(currentRoot, 0) } finally { currentRoot?.recycle() }
                    if (!unchanged) { result(null, "Window changed during screenshot"); return }
                    val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                    val bitmap = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                    hardware?.recycle()
                    if (bitmap == null) result(null, "Screenshot could not be read") else result(bitmap, null)
                } catch (e: Exception) { result(null, e.message ?: "Screenshot stopped") } finally { buffer.close() }
            }
            override fun onFailure(errorCode: Int) { result(null, "Android refused screenshot ($errorCode); protected content is never bypassed") }
        })
    }

    companion object { @Volatile var current: DeviceAccessibilityService? = null; private set }
}
