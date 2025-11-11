package com.developerobaida.accessibilitymaster.Services.actions

import android.view.accessibility.AccessibilityNodeInfo
import com.developerobaida.accessibilitymaster.Services.ScanUIService

interface ServiceAction {
    // Called on every accessibility event with the current root
    // Return true if action handled the event (and no further processing needed)
    fun onEvent(root: AccessibilityNodeInfo, service: ScanUIService): Boolean

    // Optional: called when action should reset state (e.g. onPause)
    fun reset() {}
}
