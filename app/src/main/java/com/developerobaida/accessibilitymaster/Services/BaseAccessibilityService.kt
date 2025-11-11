package com.developerobaida.accessibilitymaster.Services

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.content.ClipboardManager
import android.content.ClipData
import android.os.Build
import android.util.Log
import com.developerobaida.accessibilitymaster.Services.actions.ServiceAction

abstract class BaseAccessibilityService : AccessibilityService() {
    // keep throttles and helpers here
    fun waitMs(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    // Reuse your existing canAct mechanism (copied from ScanUIService)
    private val lastActionAt = mutableMapOf<String, Long>()
    fun canAct(key: String, cooldownMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val ok = now - (lastActionAt[key] ?: 0L) > cooldownMs
        if (ok) lastActionAt[key] = now
        return ok
    }

    // Promote the helper functions you had to protected so actions can use them:
    fun findByDesc(node: AccessibilityNodeInfo?, pattern: Regex): AccessibilityNodeInfo? { /* copy your impl */
        if (node == null) return null
        val desc = (node.contentDescription ?: "").toString().replace("\n", " ").trim()
        if (desc.isNotEmpty() && pattern.containsMatchIn(desc)) return node
        for (i in 0 until node.childCount) {
            val res = findByDesc(node.getChild(i), pattern)
            if (res != null) return res
        }
        return null
    }

    fun findFirst(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val res = findFirst(node.getChild(i), predicate)
            if (res != null) return res
        }
        return null
    }

    protected fun AccessibilityNodeInfo.performSafeAction(action: Int): Boolean =
        try { performAction(action) } catch (_: Throwable) { false }

    fun trySetText(node: AccessibilityNodeInfo, value: String): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
            ) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } else false
        } catch (_: Throwable) { false }

    fun setTextSmart(target: AccessibilityNodeInfo, value: String): Boolean {
        if (trySetText(target, value)) return true
        return try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("q", value))
            target.performSafeAction(AccessibilityNodeInfo.ACTION_FOCUS)
            target.performSafeAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (_: Throwable) { false }
    }

    fun clickSelfOrAncestor(node: AccessibilityNodeInfo, maxHops: Int): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var hops = 0
        while (cur != null && hops <= maxHops) {
            if (cur.isEnabled && (cur.isClickable || cur.isFocusable)) {
                if (cur.isFocusable) cur.performSafeAction(AccessibilityNodeInfo.ACTION_FOCUS)
                if (cur.performSafeAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
            cur = cur.parent
            hops++
        }
        return false
    }

    // You can add waitForAndTypeInEditable, enterPinByKeypad, clickSearchButtonRobust etc. here as protected methods.
    protected fun waitForAndTypeInEditable(text: String, timeoutMs: Long, pollMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val root = rootInActiveWindow ?: return false
            val edit = findFirst(root) { it.className?.contains("EditText", true) == true }
                ?: findFirst(root) { it.actionList.any { a -> a.id == AccessibilityNodeInfo.ACTION_SET_TEXT } }
            if (edit != null) {
                edit.performSafeAction(AccessibilityNodeInfo.ACTION_FOCUS)
                try { Thread.sleep(80) } catch (_: InterruptedException) {}
                val ok = setTextSmart(edit, text)
                if (ok) {
                    waitMs(300)
                    return true
                } else return false
            }
            try { Thread.sleep(pollMs) } catch (_: InterruptedException) {}
        }
        return false
    }

    fun enterPinByKeypad(pin: String): Boolean {
        for (ch in pin) {
            val r = rootInActiveWindow ?: return false
            val btn = findByDesc(r, Regex("^$ch$")) ?: return false
            if (!clickSelfOrAncestor(btn, 2)) return false
            try { Thread.sleep(80) } catch (_: InterruptedException) {}
        }
        return true
    }
}
