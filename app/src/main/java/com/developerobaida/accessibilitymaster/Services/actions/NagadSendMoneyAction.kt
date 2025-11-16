package com.developerobaida.accessibilitymaster.Services.actions

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.developerobaida.accessibilitymaster.Services.ScannerHelper
import com.developerobaida.accessibilitymaster.Services.ScanUIService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object NagadSendMoneyAction : ServiceAction {
    private const val TAG = "NAGAD_SEND"

    private var lastPinHandledAt: Long = 0L
    private const val PIN_COOLDOWN_MS = 1200L

    // Page detection states for Nagad
    enum class NagadPage {
        LOGIN_PIN,
        HOME,
        SEND_MONEY,
        AMOUNT_ENTRY,
        CONFIRMATION,
        PIN_ENTRY,
        OTP,
        UNKNOWN
    }

    override fun onEvent(root: AccessibilityNodeInfo, service: ScanUIService): Boolean {
        try {
            val r = service.rootInActiveWindow ?: root
            val nagadPage = detectNagadPage(r, service)
            Log.d(TAG, "NagadSendMoneyAction: detected Nagad page = $nagadPage")

            // Log full diagnostics for every page detection
            logFullDiagnostics(r, service, nagadPage)

            if (System.currentTimeMillis() - lastPinHandledAt < PIN_COOLDOWN_MS) {
                Log.d(TAG, "short-circuit: recently handled PIN")
                return true
            }

            // Route based on detected page
            when (nagadPage) {
                NagadPage.LOGIN_PIN -> {
                    Log.d(TAG, "==> On LOGIN_PIN page")
                    return handleLoginPin(r, service)
                }
                NagadPage.HOME -> {
                    Log.d(TAG, "==> On HOME page")
                    return handleHome(r, service)
                }
                NagadPage.SEND_MONEY -> {
                    Log.d(TAG, "==> On SEND_MONEY page")
                    return handleSendMoney(r, service)
                }
                NagadPage.AMOUNT_ENTRY -> {
                    Log.d(TAG, "==> On AMOUNT_ENTRY page")
                    return handleAmountEntry(r, service)
                }
                NagadPage.CONFIRMATION -> {
                    Log.d(TAG, "==> On CONFIRMATION page")
                    return handleConfirmation(r, service)
                }
                NagadPage.PIN_ENTRY -> {
                    Log.d(TAG, "==> On PIN_ENTRY page")
                    return handlePinEntry(r, service)
                }
                NagadPage.OTP -> {
                    Log.d(TAG, "==> On OTP page (not handling)")
                    return false
                }
                NagadPage.UNKNOWN -> {
                    Log.d(TAG, "==> UNKNOWN page - logging diagnostics")
                    return false
                }
            }

            return false
        } catch (t: Throwable) {
            Log.w(TAG, "onEvent failed: ${t.message}", t)
            return false
        }
    }

    // ========== PAGE DETECTION ==========
    private fun detectNagadPage(root: AccessibilityNodeInfo, service: ScanUIService): NagadPage {
        try {
            // Collect all text/descriptions for analysis
            val allTexts = mutableListOf<String>()
            val allDescs = mutableListOf<String>()
            collectAllTextAndDesc(root, allTexts, allDescs)

            val combinedText = (allTexts + allDescs).joinToString(" ").lowercase()
            Log.d(TAG, "detectNagadPage: combinedText sample = ${combinedText.take(500)}")

            // LOGIN_PIN detection - check for PIN input field and login button
            if (combinedText.contains("পিন") &&
                (combinedText.contains("লগ ইন") || combinedText.contains("লগইন") || combinedText.contains("login"))) {
                return NagadPage.LOGIN_PIN
            }

            // HOME detection - look for common Nagad home keywords
            val homeKeywords = listOf(
                "সেন্ড মানি", "send money",
                "ক্যাশ আউট", "cash out",
                "মোবাইল রিচার্জ", "mobile recharge",
                "পেমেন্ট", "payment",
                "নগদ" // Nagad brand name
            )
            val homeMatches = homeKeywords.count { combinedText.contains(it) }
            if (homeMatches >= 2) {
                return NagadPage.HOME
            }

            // SEND_MONEY page - recipient input
            if ((combinedText.contains("প্রাপক") || combinedText.contains("recipient") ||
                        combinedText.contains("মোবাইল নাম্বার") || combinedText.contains("mobile number")) &&
                (combinedText.contains("সেন্ড") || combinedText.contains("send"))) {
                return NagadPage.SEND_MONEY
            }

            // AMOUNT_ENTRY page
            if (combinedText.contains("টাকা") || combinedText.contains("amount") ||
                combinedText.contains("৳") || combinedText.contains("taka")) {
                // Check if there's an amount input field
                val hasAmountField = service.findFirst(root) { n ->
                    val cls = n.className?.toString() ?: ""
                    val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        (n.hintText ?: "").toString() else ""
                    cls.contains("EditText", true) &&
                            (hint.contains("টাকা", true) || hint.contains("amount", true))
                } != null
                if (hasAmountField) {
                    return NagadPage.AMOUNT_ENTRY
                }
            }

            // CONFIRMATION page
            if (combinedText.contains("কনফার্ম") || combinedText.contains("confirm") ||
                combinedText.contains("নিশ্চিত") || combinedText.contains("verify")) {
                return NagadPage.CONFIRMATION
            }

            // PIN_ENTRY page (during transaction)
            if (combinedText.contains("পিন") &&
                (combinedText.contains("প্রবেশ") || combinedText.contains("enter") ||
                        combinedText.contains("দিন"))) {
                return NagadPage.PIN_ENTRY
            }

            // OTP page
            if (combinedText.contains("ওটিপি") || combinedText.contains("otp")) {
                return NagadPage.OTP
            }

            return NagadPage.UNKNOWN
        } catch (t: Throwable) {
            Log.w(TAG, "detectNagadPage failed: ${t.message}", t)
            return NagadPage.UNKNOWN
        }
    }

    private fun collectAllTextAndDesc(
        node: AccessibilityNodeInfo?,
        texts: MutableList<String>,
        descs: MutableList<String>
    ) {
        if (node == null) return
        try {
            val text = (node.text ?: "").toString().trim()
            val desc = (node.contentDescription ?: "").toString().trim()
            if (text.isNotEmpty()) texts.add(text)
            if (desc.isNotEmpty()) descs.add(desc)

            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Throwable) { null }
                collectAllTextAndDesc(child, texts, descs)
            }
        } catch (_: Throwable) { }
    }

    // ========== PAGE HANDLERS (STUBS FOR NOW) ==========

    private fun handleLoginPin(root: AccessibilityNodeInfo, service: ScanUIService): Boolean {
        Log.d(TAG, "handleLoginPin: attempting to enter PIN")

        val pin = ScannerHelper.targetPin
        if (pin.isNullOrEmpty()) {
            Log.d(TAG, "handleLoginPin: no PIN provided in ScannerHelper.targetPin")
            return false
        }

        Log.d(TAG, "handleLoginPin: PIN = $pin")

        try {
            // Find PIN input field by ID
            val pinInput = service.findFirst(root) { n ->
                try {
                    val id = n.viewIdResourceName ?: ""
                    id.contains("pin_input_view")
                } catch (_: Throwable) { false }
            }

            if (pinInput == null) {
                Log.d(TAG, "handleLoginPin: pin_input_view not found")
                return false
            }

            Log.d(TAG, "handleLoginPin: found pinInput, attempting to focus to show keyboard")

            // Focus on the field to show the keyboard
            try { pinInput.performAction(AccessibilityNodeInfo.ACTION_FOCUS) } catch (_: Throwable) { }
            service.waitMs(100)

            // Click the field to ensure keyboard appears
            val clicked = try {
                pinInput.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } catch (_: Throwable) { false }

            if (!clicked) {
                service.clickSelfOrAncestor(pinInput, 3)
            }

            // Wait for keyboard to appear
            service.waitMs(600)

            Log.d(TAG, "handleLoginPin: keyboard should be visible, getting fresh root")

            // Get fresh root to see the keyboard
            val freshRoot = service.rootInActiveWindow ?: root

            // Log what we see after keyboard appears
            Log.d(TAG, "=== AFTER KEYBOARD APPEARS ===")
            logAllButtons(freshRoot, service)
            dumpClickableNodesBounds(freshRoot, service)

            // Try to enter PIN via keyboard
            Log.d(TAG, "handleLoginPin: attempting to enter PIN via keyboard: $pin")
            val enteredViaKeyboard = enterPinViaKeyboard(freshRoot, service, pin)

            if (!enteredViaKeyboard) {
                Log.w(TAG, "handleLoginPin: failed to enter PIN via keyboard")
                return false
            }

            Log.d(TAG, "handleLoginPin: PIN entered successfully, waiting before clicking login")
            service.waitMs(400)

            // Get fresh root again for login button
            val loginRoot = service.rootInActiveWindow ?: freshRoot

            // Find and click login button by ID
            val loginBtn = service.findFirst(loginRoot) { n ->
                try {
                    val id = n.viewIdResourceName ?: ""
                    id.contains("progress_btn")
                } catch (_: Throwable) { false }
            }

            if (loginBtn == null) {
                Log.d(TAG, "handleLoginPin: progress_btn not found")
                return false
            }

            Log.d(TAG, "handleLoginPin: found login button, attempting to click")

            // Try multiple click strategies
            val btnClicked = service.clickSelfOrAncestor(loginBtn, 3)
            if (!btnClicked) {
                val directClick = try {
                    loginBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } catch (_: Throwable) { false }

                if (directClick) {
                    Log.d(TAG, "handleLoginPin: login button clicked via performAction")
                } else {
                    Log.d(TAG, "handleLoginPin: failed to click login button")
                    return false
                }
            } else {
                Log.d(TAG, "handleLoginPin: login button clicked via clickSelfOrAncestor")
            }

            service.waitMs(800)
            Log.d(TAG, "handleLoginPin: login sequence complete, waiting for next page")

            lastPinHandledAt = System.currentTimeMillis()
            return true

        } catch (t: Throwable) {
            Log.w(TAG, "handleLoginPin failed: ${t.message}", t)
            return false
        }
    }

    /**
     * Enter PIN by clicking keyboard buttons one by one
     */
    private fun enterPinViaKeyboard(root: AccessibilityNodeInfo, service: ScanUIService, pin: String): Boolean {
        try {
            // First, try to find keyboard buttons in accessibility tree
            val digitFound = service.findFirst(root) { n ->
                try {
                    val text = (n.text ?: "").toString().trim()
                    text in listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9")
                } catch (_: Throwable) { false }
            }

            if (digitFound != null) {
                Log.d(TAG, "enterPinViaKeyboard: keyboard buttons found in tree, using node clicking")
                return enterPinViaNodes(root, service, pin)
            } else {
                Log.d(TAG, "enterPinViaKeyboard: keyboard NOT in accessibility tree, using gesture-based tapping")
                return enterPinViaGestures(service, pin)
            }

        } catch (t: Throwable) {
            Log.w(TAG, "enterPinViaKeyboard failed: ${t.message}", t)
            return false
        }
    }

    /**
     * Enter PIN by clicking nodes (if keyboard is in accessibility tree)
     */
    private fun enterPinViaNodes(root: AccessibilityNodeInfo, service: ScanUIService, pin: String): Boolean {
        try {
            for ((index, digit) in pin.withIndex()) {
                Log.d(TAG, "enterPinViaNodes: entering digit ${index + 1}/${pin.length}: $digit")

                val freshRoot = service.rootInActiveWindow ?: root

                var digitNode: AccessibilityNodeInfo? = service.findFirst(freshRoot) { n ->
                    try {
                        val text = (n.text ?: "").toString().trim()
                        text == digit.toString() && (n.isClickable || n.isFocusable)
                    } catch (_: Throwable) { false }
                }

                if (digitNode == null) {
                    digitNode = service.findFirst(freshRoot) { n ->
                        try {
                            val desc = (n.contentDescription ?: "").toString().trim()
                            desc == digit.toString() && (n.isClickable || n.isFocusable)
                        } catch (_: Throwable) { false }
                    }
                }

                if (digitNode == null) {
                    Log.w(TAG, "enterPinViaNodes: could not find button for digit '$digit'")
                    return false
                }

                val clicked = service.clickSelfOrAncestor(digitNode, 4)
                if (!clicked) {
                    try { digitNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Throwable) { }
                }

                service.waitMs(150)
            }
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "enterPinViaNodes failed: ${t.message}", t)
            return false
        }
    }

    /**
     * Enter PIN using screen gestures (for keyboards not in accessibility tree)
     * Nagad keyboard layout from screenshot:
     * Row 1: 1  2  3
     * Row 2: 4  5  6
     * Row 3: 7  8  9
     * Row 4: DEL 0 DONE
     */
    private fun enterPinViaGestures(service: ScanUIService, pin: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "enterPinViaGestures: API < 24, cannot use dispatchGesture")
            return false
        }

        val accService = try { service as AccessibilityService } catch (_: Throwable) { null }
        if (accService == null) {
            Log.w(TAG, "enterPinViaGestures: service is not AccessibilityService")
            return false
        }

        try {
            // Get screen dimensions
            val dm = accService.resources.displayMetrics
            val screenWidth = dm.widthPixels.toFloat()
            val screenHeight = dm.heightPixels.toFloat()

            Log.d(TAG, "enterPinViaGestures: screen dimensions = ${screenWidth}x${screenHeight}")

            // From your screenshot, the keyboard starts around y=865 (bottom ~38% of 2273px screen)
            // Keyboard occupies roughly from 38% to 95% of screen height
            val keyboardTop = screenHeight * 0.38f  // Start of keyboard (around y=865 for 2273px)
            val keyboardBottom = screenHeight * 0.95f  // Bottom navigation bar starts here
            val keyboardHeight = keyboardBottom - keyboardTop

            Log.d(TAG, "enterPinViaGestures: keyboard area = $keyboardTop to $keyboardBottom (height=$keyboardHeight)")

            // Calculate button positions (3 columns, 4 rows)
            val colWidth = screenWidth / 3f
            val rowHeight = keyboardHeight / 4f

            Log.d(TAG, "enterPinViaGestures: colWidth=$colWidth, rowHeight=$rowHeight")

            // Define button positions for each digit
            val digitPositions = mapOf(
                '1' to Pair(0, 0), '2' to Pair(1, 0), '3' to Pair(2, 0),
                '4' to Pair(0, 1), '5' to Pair(1, 1), '6' to Pair(2, 1),
                '7' to Pair(0, 2), '8' to Pair(1, 2), '9' to Pair(2, 2),
                '0' to Pair(1, 3)  // 0 is in middle of bottom row
            )

            for ((index, digit) in pin.withIndex()) {
                Log.d(TAG, "enterPinViaGestures: tapping digit ${index + 1}/${pin.length}: $digit")

                val position = digitPositions[digit]
                if (position == null) {
                    Log.w(TAG, "enterPinViaGestures: no position defined for digit '$digit'")
                    return false
                }

                val (col, row) = position

                // Calculate tap position (center of button)
                val tapX = (col * colWidth) + (colWidth / 2f)
                val tapY = keyboardTop + (row * rowHeight) + (rowHeight / 2f)

                Log.d(TAG, "enterPinViaGestures: tapping at ($tapX, $tapY) for digit '$digit'")

                // Perform tap gesture
                val tapped = performTapGesture(accService, tapX, tapY)
                if (!tapped) {
                    Log.w(TAG, "enterPinViaGestures: failed to tap digit '$digit'")
                    return false
                }

                Log.d(TAG, "enterPinViaGestures: successfully tapped digit '$digit'")
                service.waitMs(200)
            }

            Log.d(TAG, "enterPinViaGestures: successfully entered all ${pin.length} digits")
            return true

        } catch (t: Throwable) {
            Log.w(TAG, "enterPinViaGestures failed: ${t.message}", t)
            return false
        }
    }

    /**
     * Perform a tap gesture at specific coordinates
     */
    private fun performTapGesture(service: AccessibilityService, x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        try {
            val path = Path().apply { moveTo(x, y) }
            // Increased duration from 100ms to 150ms for better tap recognition
            val stroke = GestureDescription.StrokeDescription(path, 0, 150)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            val latch = CountDownLatch(1)
            var success = false

            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    success = true
                    latch.countDown()
                    Log.d(TAG, "performTapGesture: tap completed at ($x, $y)")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    success = false
                    latch.countDown()
                    Log.w(TAG, "performTapGesture: tap CANCELLED at ($x, $y)")
                }
            }

            val dispatched = service.dispatchGesture(gesture, callback, null)
            if (!dispatched) {
                Log.w(TAG, "performTapGesture: dispatchGesture returned false immediately")
                return false
            }

            // Wait up to 800ms for completion (increased from 500ms)
            val completed = latch.await(800, TimeUnit.MILLISECONDS)
            if (!completed) {
                Log.w(TAG, "performTapGesture: timeout waiting for tap completion")
            }
            return success

        } catch (t: Throwable) {
            Log.w(TAG, "performTapGesture failed: ${t.message}", t)
            return false
        }
    }

    private fun handleHome(root: AccessibilityNodeInfo, service: ScanUIService): Boolean {
        Log.d(TAG, "handleHome: attempting to open Send Money")
        // TODO: Implement after seeing logs
        return false
    }

    private fun handleSendMoney(root: AccessibilityNodeInfo, service: ScanUIService): Boolean {
        Log.d(TAG, "handleSendMoney: attempting to enter recipient number")
        val recipient = ScannerHelper.recipientNumber
        Log.d(TAG, "handleSendMoney: recipient = $recipient")
        // TODO: Implement after seeing logs
        return false
    }

    private fun handleAmountEntry(root: AccessibilityNodeInfo, service: ScanUIService): Boolean {
        Log.d(TAG, "handleAmountEntry: attempting to enter amount")
        val amount = ScannerHelper.amount
        Log.d(TAG, "handleAmountEntry: amount = $amount")
        // TODO: Implement after seeing logs
        return false
    }

    private fun handleConfirmation(root: AccessibilityNodeInfo, service: ScanUIService): Boolean {
        Log.d(TAG, "handleConfirmation: attempting to confirm transaction")
        // TODO: Implement after seeing logs
        return false
    }

    private fun handlePinEntry(root: AccessibilityNodeInfo, service: ScanUIService): Boolean {
        Log.d(TAG, "handlePinEntry: attempting to enter PIN")
        val pin = ScannerHelper.targetPin
        Log.d(TAG, "handlePinEntry: pin present = ${pin.isNotEmpty()}")
        // TODO: Implement after seeing logs
        return false
    }

    // ========== DIAGNOSTIC LOGGING ==========

    private fun logFullDiagnostics(
        root: AccessibilityNodeInfo,
        service: ScanUIService,
        page: NagadPage
    ) {
        Log.d(TAG, "")
        Log.d(TAG, "╔════════════════════════════════════════════════════════════")
        Log.d(TAG, "║ NAGAD DIAGNOSTICS FOR PAGE: $page")
        Log.d(TAG, "╠════════════════════════════════════════════════════════════")

        // 1. Log node tree structure
        logNodeTree(root, 0, maxDepth = 8)

        Log.d(TAG, "╠════════════════════════════════════════════════════════════")

        // 2. Log all clickable/focusable nodes with bounds
        dumpClickableNodesBounds(root, service)

        Log.d(TAG, "╠════════════════════════════════════════════════════════════")

        // 3. Log all EditText fields
        logAllEditTextFields(root, service)

        Log.d(TAG, "╠════════════════════════════════════════════════════════════")

        // 4. Log all Button-like nodes
        logAllButtons(root, service)

        Log.d(TAG, "╠════════════════════════════════════════════════════════════")

        // 5. Log nodes containing keywords
        logKeywordMatches(root, service)

        Log.d(TAG, "╚════════════════════════════════════════════════════════════")
        Log.d(TAG, "")
    }

    private fun logNodeTree(node: AccessibilityNodeInfo?, depth: Int, maxDepth: Int = 8) {
        if (node == null || depth > maxDepth) return

        try {
            val indent = "  ".repeat(depth)
            val cls = node.className?.toString() ?: "null"
            val text = (node.text ?: "").toString().replace("\n", " ").trim()
            val desc = (node.contentDescription ?: "").toString().replace("\n", " ").trim()
            val id = try { node.viewIdResourceName ?: "" } catch (_: Throwable) { "" }

            val flags = buildString {
                if (node.isClickable) append("C")
                if (node.isFocusable) append("F")
                if (node.isEnabled) append("E")
                if (node.isCheckable) append("K")
            }

            val info = buildString {
                if (text.isNotEmpty()) append(" text='${text.take(50)}'")
                if (desc.isNotEmpty()) append(" desc='${desc.take(50)}'")
                if (id.isNotEmpty()) append(" id='${id.substringAfterLast("/")}'")
                if (flags.isNotEmpty()) append(" [$flags]")
            }

            Log.d(TAG, "$indent├─ ${cls.substringAfterLast(".")}$info")

            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Throwable) { null }
                logNodeTree(child, depth + 1, maxDepth)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "logNodeTree failed at depth $depth: ${t.message}")
        }
    }

    private fun dumpClickableNodesBounds(root: AccessibilityNodeInfo, service: ScanUIService) {
        Log.d(TAG, "║ CLICKABLE/FOCUSABLE NODES:")

        val nodes = mutableListOf<String>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)

        while (stack.isNotEmpty()) {
            val n = stack.removeFirst()
            try {
                if (n.isClickable || n.isFocusable) {
                    val cls = n.className?.toString()?.substringAfterLast(".") ?: "null"
                    val text = (n.text ?: "").toString().replace("\n", " ").trim().take(40)
                    val desc = (n.contentDescription ?: "").toString().replace("\n", " ").trim().take(40)
                    val id = try { n.viewIdResourceName?.substringAfterLast("/") ?: "" } catch (_: Throwable) { "" }

                    val r = Rect()
                    try { n.getBoundsInScreen(r) } catch (_: Throwable) { }
                    val bounds = if (r.isEmpty) "no-bounds" else "(${r.left},${r.top},${r.width()},${r.height()})"

                    val flags = buildString {
                        if (n.isClickable) append("C")
                        if (n.isFocusable) append("F")
                        if (n.isEnabled) append("E")
                    }

                    nodes.add("  [$cls] $bounds [$flags] ${if(text.isNotEmpty()) "txt='$text'" else ""} ${if(desc.isNotEmpty()) "desc='$desc'" else ""} ${if(id.isNotEmpty()) "id=$id" else ""}")
                }

                for (i in 0 until n.childCount) {
                    val c = try { n.getChild(i) } catch (_: Throwable) { null }
                    if (c != null) stack.add(c)
                }
            } catch (_: Throwable) { }
        }

        nodes.take(100).forEachIndexed { i, s ->
            Log.d(TAG, "║ [$i] $s")
        }
        if (nodes.size > 100) {
            Log.d(TAG, "║ ... and ${nodes.size - 100} more nodes")
        }
    }

    private fun logAllEditTextFields(root: AccessibilityNodeInfo, service: ScanUIService) {
        Log.d(TAG, "║ ALL EDITTEXT FIELDS:")

        val edits = mutableListOf<String>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)

        while (stack.isNotEmpty()) {
            val n = stack.removeFirst()
            try {
                val cls = n.className?.toString() ?: ""
                if (cls.contains("EditText", true)) {
                    val text = (n.text ?: "").toString().trim()
                    val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        (n.hintText ?: "").toString() else ""
                    val desc = (n.contentDescription ?: "").toString().trim()
                    val id = try { n.viewIdResourceName?.substringAfterLast("/") ?: "" } catch (_: Throwable) { "" }

                    val info = buildString {
                        if (text.isNotEmpty()) append(" text='$text'")
                        if (hint.isNotEmpty()) append(" hint='$hint'")
                        if (desc.isNotEmpty()) append(" desc='$desc'")
                        if (id.isNotEmpty()) append(" id=$id")
                    }

                    edits.add("  EditText$info")
                }

                for (i in 0 until n.childCount) {
                    val c = try { n.getChild(i) } catch (_: Throwable) { null }
                    if (c != null) stack.add(c)
                }
            } catch (_: Throwable) { }
        }

        if (edits.isEmpty()) {
            Log.d(TAG, "║   (no EditText fields found)")
        } else {
            edits.forEachIndexed { i, s -> Log.d(TAG, "║ [$i] $s") }
        }
    }

    private fun logAllButtons(root: AccessibilityNodeInfo, service: ScanUIService) {
        Log.d(TAG, "║ ALL BUTTON-LIKE NODES:")

        val buttons = mutableListOf<String>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)

        while (stack.isNotEmpty()) {
            val n = stack.removeFirst()
            try {
                val cls = n.className?.toString() ?: ""
                if (cls.contains("Button", true) || cls.contains("ImageView", true) ||
                    (cls.contains("View", true) && n.isClickable)) {
                    val text = (n.text ?: "").toString().trim().take(50)
                    val desc = (n.contentDescription ?: "").toString().trim().take(50)
                    val id = try { n.viewIdResourceName?.substringAfterLast("/") ?: "" } catch (_: Throwable) { "" }

                    if (text.isNotEmpty() || desc.isNotEmpty() || id.isNotEmpty()) {
                        val info = buildString {
                            append(cls.substringAfterLast("."))
                            if (text.isNotEmpty()) append(" text='$text'")
                            if (desc.isNotEmpty()) append(" desc='$desc'")
                            if (id.isNotEmpty()) append(" id=$id")
                        }
                        buttons.add("  $info")
                    }
                }

                for (i in 0 until n.childCount) {
                    val c = try { n.getChild(i) } catch (_: Throwable) { null }
                    if (c != null) stack.add(c)
                }
            } catch (_: Throwable) { }
        }

        if (buttons.isEmpty()) {
            Log.d(TAG, "║   (no button-like nodes found)")
        } else {
            buttons.take(50).forEachIndexed { i, s -> Log.d(TAG, "║ [$i] $s") }
            if (buttons.size > 50) {
                Log.d(TAG, "║ ... and ${buttons.size - 50} more buttons")
            }
        }
    }

    private fun logKeywordMatches(root: AccessibilityNodeInfo, service: ScanUIService) {
        Log.d(TAG, "║ KEYWORD MATCHES:")

        val keywords = listOf(
            "সেন্ড মানি", "send money",
            "পিন", "pin",
            "টাকা", "amount", "৳",
            "প্রাপক", "recipient",
            "নাম্বার", "number",
            "কনফার্ম", "confirm",
            "পরবর্তী", "next",
            "রেফারেন্স", "reference"
        )

        val matches = mutableMapOf<String, MutableList<String>>()
        keywords.forEach { matches[it] = mutableListOf() }

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)

        while (stack.isNotEmpty()) {
            val n = stack.removeFirst()
            try {
                val text = (n.text ?: "").toString()
                val desc = (n.contentDescription ?: "").toString()
                val combined = "$text $desc".lowercase()

                keywords.forEach { kw ->
                    if (combined.contains(kw.lowercase())) {
                        val cls = n.className?.toString()?.substringAfterLast(".") ?: "?"
                        val snippet = combined.replace("\n", " ").trim().take(60)
                        matches[kw]?.add("    [$cls] '$snippet'")
                    }
                }

                for (i in 0 until n.childCount) {
                    val c = try { n.getChild(i) } catch (_: Throwable) { null }
                    if (c != null) stack.add(c)
                }
            } catch (_: Throwable) { }
        }

        matches.forEach { (kw, list) ->
            if (list.isNotEmpty()) {
                Log.d(TAG, "║   '$kw' found in ${list.size} nodes:")
                list.take(5).forEach { Log.d(TAG, "║ $it") }
                if (list.size > 5) Log.d(TAG, "║     ... and ${list.size - 5} more")
            }
        }
    }
}