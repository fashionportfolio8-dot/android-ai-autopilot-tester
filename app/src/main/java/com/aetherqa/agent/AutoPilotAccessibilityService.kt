package com.aetherqa.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AutoPilotAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AutoPilotAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    fun collectUiSummary(maxNodes: Int = 40): String {
        val root = rootInActiveWindow ?: return "No active window nodes."
        val sb = StringBuilder()
        var count = 0

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || count >= maxNodes) return
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()
            if (!text.isNullOrEmpty() || !desc.isNullOrEmpty() || node.isClickable) {
                sb.append(
                    "node[text='${text ?: desc ?: ""}', clickable=${node.isClickable}, " +
                        "bounds=(${rect.left},${rect.top},${rect.right},${rect.bottom}), " +
                        "center=(${rect.centerX()},${rect.centerY()})]\n"
                )
                count++
            }
            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        traverse(root)
        return sb.toString().ifEmpty { "UI tree empty (likely canvas/game surface)." }
    }

    fun tapAt(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 80L))
            .build()
        dispatchGesture(gesture, null, null)
    }

    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 350L) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        dispatchGesture(gesture, null, null)
    }

    fun pressBackAction() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }
}
