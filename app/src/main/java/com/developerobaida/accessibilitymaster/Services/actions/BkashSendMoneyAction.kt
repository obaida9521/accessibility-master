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
import kotlin.text.RegexOption
import java.util.concurrent.TimeUnit

object BkashSendMoneyAction : ServiceAction {

    private const val TAG = "BKASH_SEND"

    private var lastPinHandledAt: Long = 0L
    private var lastRefTypedAt: Long = 0L
    private const val PIN_HANDLED_COOLDOWN_MS = 1200L
    private const val REF_TYPE_COOLDOWN_MS = 1200L

    override fun onEvent(root: AccessibilityNodeInfo, service: ScanUIService): Boolean {
        try {
            val rootNode = service.rootInActiveWindow ?: root
            val page = service.detectPage(rootNode)
            Log.d(TAG, "BkashSendMoneyAction: detected page = $page")

            if (System.currentTimeMillis() - lastPinHandledAt < PIN_HANDLED_COOLDOWN_MS) {
                Log.d(TAG, "Short-circuit: recently handled PIN, skipping")
                return true
            }

            val amountHeuristic = service.findByDesc(rootNode, Regex("পরিমান|পরিমাণ|৳|Amount", RegexOption.IGNORE_CASE))
            val proceedHeuristic = service.findByDesc(rootNode, Regex("এগিয়ে\\s*যান|Proceed|Continue|পরবর্তী|Next", RegexOption.IGNORE_CASE))
            if (amountHeuristic != null || proceedHeuristic != null) {
                Log.d(TAG, "Heuristic: amount/proceed UI detected — attempting enterAmountAndProceed")
                val did = enterAmountAndProceed(rootNode, service)
                if (did) return true
            }

            if (page == ScanUIService.Page.LOGIN_PIN) {
                service.tryLogin()
                return true
            }
            if (page == ScanUIService.Page.OTP) return false
            if (page == ScanUIService.Page.HOME) return handleHome(rootNode, service)
            if (page == ScanUIService.Page.INBOX) return false

            if (page == ScanUIService.Page.UNKNOWN) {
                logNodeTree(rootNode, 0)
                logCandidateTilesForDebug(rootNode, service)
                dumpClickableNodesBounds(service)
                logHeuristicFindings(rootNode, service)
                return true
            }

            return false
        } catch (t: Throwable) {
            Log.w(TAG, "onEvent failed: ${t.message}", t)
            return false
        }
    }

    // ---------- HOME handling ----------
    private fun handleHome(rootNode: AccessibilityNodeInfo, service: ScanUIService): Boolean {
        try {
            Log.d(TAG, "HOME -> trying to open Send Money")
            var target = service.findByDesc(rootNode, Regex("^সেন্ড\\s*মানি\$", RegexOption.IGNORE_CASE))
            if (target == null) target = service.findByDesc(rootNode, Regex("সেন্ড|মানি|Send\\s*Money", RegexOption.IGNORE_CASE))
            if (target == null) {
                target = service.findFirst(rootNode) { n ->
                    val cls = n.className?.toString() ?: ""
                    val desc = (n.contentDescription ?: "").toString()
                    cls.contains("ImageView", true) && desc.contains("সেন্ড", true) && desc.contains("মানি", true)
                }
            }
            if (target == null) {
                Log.d(TAG, "Send Money tile not found on HOME")
                logCandidateTilesForDebug(rootNode, service)
                return false
            }

            val clicked = service.clickSelfOrAncestor(target, 4)
            if (!clicked) {
                var p = target.parent
                var manualClicked = false
                var hops = 0
                while (p != null && hops < 6) {
                    val clickRes = try { p.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Throwable) { false }
                    if (clickRes) { manualClicked = true; break }
                    p = p.parent; hops++
                }
                if (!manualClicked) {
                    Log.w(TAG, "Failed to click Send Money tile")
                    return false
                }
            }
            Log.d(TAG, "Send Money opened")
            service.waitMs(450)

            val newRoot = service.rootInActiveWindow ?: run {
                Log.d(TAG, "rootInActiveWindow null after opening Send Money")
                return true
            }
            logCandidateTilesForDebug(newRoot, service)

            val searchField = findSearchField(newRoot, service)
            if (searchField == null) {
                Log.d(TAG, "Search field not found on Send Money page -- attempting long-press on PINK BOTTOM BANNER")

                // ---- 1. Find the pink banner node -------------------------------------------------
                val pinkBanner = service.findFirst(newRoot) { n ->
                    try {
                        val desc = (n.contentDescription ?: "").toString()
                        val txt  = (n.text ?: "").toString()
                        val cls  = n.className?.toString() ?: ""
                        // ONLY trigger long-press when exact/helpful descriptive text appears
                        val shouldMatch = desc.contains("সেন্ড মানি করতে", true) || txt.contains("সেন্ড মানি করতে", true)
                        (shouldMatch || desc.contains("ট্যাপ করে ধরে রাখুন", true) || txt.contains("ট্যাপ করে ধরে রাখুন", true)) &&
                                (n.isClickable || n.isLongClickable || cls.contains("View", true) || cls.contains("Frame", true))
                    } catch (_: Throwable) { false }
                }

                // ---- 2. Try node-based long-press (only if description matches target text exactly) ----
                var gestureOk = false
                if (pinkBanner != null) {
                    val desc = (pinkBanner.contentDescription ?: "").toString()
                    val txt  = (pinkBanner.text ?: "").toString()
                    val matchesExact = desc.contains("সেন্ড মানি করতে ট্যাপ করে ধরে রাখুন", true) ||
                            txt.contains("সেন্ড মানি করতে ট্যাপ করে ধরে রাখুন", true)

                    if (matchesExact) {
                        val r = Rect()
                        try { pinkBanner.getBoundsInScreen(r) } catch (_: Throwable) { /* ignore */ }
                        if (!r.isEmpty) {
                            val cx = (r.left + r.right) / 2f
                            val cy = (r.top + r.bottom) / 2f

                            Log.d(TAG, "Trying node-centered long-press at center ($cx,$cy) for exact match")

                            try {
                                // Focus to improve recognition
                                pinkBanner.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                                service.waitMs(100)

                                gestureOk = performExhaustiveLongPress(service, pinkBanner)
                                Log.d(TAG, "Node-based long-press result -> $gestureOk")
                            } catch (_: Throwable) {}
                        } else {
                            Log.d(TAG, "Pink banner bounds empty — skipping node-centered long-press")
                        }
                    } else {
                        Log.d(TAG, "Pink banner found but text doesn't match exact trigger; skipping node long-press. desc='$desc' txt='$txt'")
                    }
                } else {
                    Log.d(TAG, "Pink banner node not found")
                }

                // 3. If node-based approach failed, try screen-based approach only if pink-banner text matched exact string earlier
                if (!gestureOk) {
                    // attempt to find any node that contains exact text to allow screen-based approach
                    val anyMatchNode = service.findFirst(newRoot) { n ->
                        try {
                            val d = (n.contentDescription ?: "").toString()
                            val t = (n.text ?: "").toString()
                            d.contains("সেন্ড মানি করতে ট্যাপ করে ধরে রাখুন", true) || t.contains("সেন্ড মানি করতে ট্যাপ করে ধরে রাখুন", true)
                        } catch (_: Throwable) { false }
                    }

                    if (anyMatchNode != null) {
                        Log.d(TAG, "Exact text found elsewhere — attempting screen-based long-press")
                        val acc = try { service as AccessibilityService } catch (_: Throwable) { null }
                        if (acc != null) {
                            val dm = acc.resources.displayMetrics
                            val w = dm.widthPixels.toFloat()
                            val h = dm.heightPixels.toFloat()
                            val targetX = w / 2f
                            val targetY = h * 0.88f

                            try {
                                gestureOk = performExhaustiveLongPress(service, null, targetX, targetY)
                                Log.d(TAG, "Screen-based long-press result -> $gestureOk")
                            } catch (_: Throwable) {}
                        } else {
                            Log.w(TAG, "Cannot cast service to AccessibilityService for screen-based gesture")
                        }
                    } else {
                        Log.d(TAG, "Did not find exact text anywhere — skipping screen-based long-press")
                    }
                }

                if (gestureOk) {
                    Log.d(TAG, "Long-press SUCCESS")
                    service.waitMs(1400)
                    val afterRoot = service.rootInActiveWindow ?: newRoot
                    val sf = findSearchField(afterRoot, service)
                    if (sf != null) {
                        Log.d(TAG, "Search field appeared after long-press (success path)")
                        focusAndTypeIntoNode(sf, ScannerHelper.recipientNumber, service)
                        return true
                    } else {
                        Log.d(TAG, "Search field NOT found after long-press")
                    }
                } else {
                    Log.w(TAG, "All long-press attempts FAILED or were skipped")
                }

                // ---- 4. Debug dump -------------------------------------------------
                logNodeTree(newRoot, 0)
                dumpClickableNodesBounds(service)
                //logNodesAtBottom(service)
                return true
            }

            val recipient = ScannerHelper.recipientNumber
            if (recipient.isEmpty()) {
                Log.d(TAG, "No recipient provided in ScannerHelper.recipientNumber")
                return true
            }

            val existingText = (searchField.text ?: "").toString()
            // If search field doesn't already contain recipient, type it
            if (!existingText.contains(recipient)) {
                focusAndTypeIntoNode(searchField, recipient, service)
                // DO NOT return immediately — let UI update and then try explicit Next click
                service.waitMs(300)
                // After typing, try clicking Next if present
                val afterTypingRoot = service.rootInActiveWindow ?: rootNode
                val clickedNextAfterTyping = clickNextButtonIfPresent(afterTypingRoot, service)
                if (clickedNextAfterTyping) {
                    Log.d(TAG, "Clicked Next after typing recipient")
                    service.waitMs(400)
                    return true
                }
                // else continue to suggestion detection below
            }

            // If search field already contains recipient or we typed and Next not found, try suggestions
            val clickedSuggestion = clickBestContactSuggestion(newRoot, recipient, service)
            if (clickedSuggestion) {
                service.waitMs(400)
                return true
            } else {
                // final attempt: click Next button explicitly (maybe visible now)
                val nextClicked = clickNextButtonIfPresent(newRoot, service)
                if (nextClicked) {
                    service.waitMs(400)
                    return true
                }
            }

            return true
        } catch (t: Throwable) {
            Log.w(TAG, "handleHome failed: ${t.message}", t)
            return false
        }
    }

    // ---------- helpers (focus/type etc) ----------
    private fun focusAndTypeIntoNode(node: AccessibilityNodeInfo, text: String, service: ScanUIService) {
        try {
            try { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) } catch (_: Throwable) {}
            val clicked = try { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Throwable) { false }
            if (!clicked) service.clickSelfOrAncestor(node, 3)
            service.waitMs(120)
            val typedDirect = try { service.trySetText(node, text) } catch (_: Throwable) { false }
            if (!typedDirect) service.setTextSmart(node, text)
            Log.d(TAG, "focusAndTypeIntoNode: typed '$text' -> direct=$typedDirect")
        } catch (_: Throwable) { }
    }

    private fun clickBestContactSuggestion(root: AccessibilityNodeInfo, recipient: String, service: ScanUIService): Boolean {
        try {
            val checkPart = if (recipient.length >= 5) recipient.takeLast(5) else recipient
            val suggestion = service.findFirst(root) { n ->
                try {
                    val cls = n.className?.toString() ?: ""
                    val desc = (n.contentDescription ?: "").toString()
                    val txt = (n.text ?: "").toString()
                    // Never treat EditText as a suggestion
                    val isEdit = cls.contains("EditText", true) || cls.contains("android.widget.EditText", true)
                    !isEdit && n.isClickable && (desc.contains(checkPart, true) || txt.contains(checkPart, true)) &&
                            (cls.contains("View", true) || cls.contains("ImageView", true) || cls.contains("LinearLayout", true) || cls.contains("RelativeLayout", true))
                } catch (_: Throwable) { false }
            }

            if (suggestion != null) {
                val clicked = service.clickSelfOrAncestor(suggestion, 4)
                Log.d(TAG, "Clicked contact suggestion -> clicked=$clicked text='${suggestion.text}' desc='${suggestion.contentDescription}'")
                return clicked
            }

            // Fallback: find any clickable/focusable result that contains last 4 digits but exclude EditText nodes
            val firstResult = service.findFirst(root) { n ->
                try {
                    val cls = n.className?.toString() ?: ""
                    val descR = (n.contentDescription ?: "").toString()
                    val txtR = (n.text ?: "").toString()
                    val isEdit = cls.contains("EditText", true) || cls.contains("android.widget.EditText", true)
                    ( (descR.contains(recipient.takeLast(4), true) || txtR.contains(recipient.takeLast(4), true)) && (n.isClickable || n.isFocusable) && !isEdit )
                } catch (_: Throwable) { false }
            }
            if (firstResult != null) {
                val clickedFirst = service.clickSelfOrAncestor(firstResult, 4)
                Log.d(TAG, "Clicked first result fallback -> clicked=$clickedFirst text='${firstResult.text}' desc='${firstResult.contentDescription}'")
                return clickedFirst
            }
            Log.d(TAG, "No contact suggestion or first result matched (non-Edit fallback)")
            return false
        } catch (t: Throwable) {
            Log.w(TAG, "clickBestContactSuggestion failed: ${t.message}", t)
            return false
        }
    }

    // ---------- amount / reference / PIN flow (unchanged) ----------
    private fun enterAmountAndProceed(root: AccessibilityNodeInfo, service: ScanUIService): Boolean {
        try {
            if (System.currentTimeMillis() - lastPinHandledAt < PIN_HANDLED_COOLDOWN_MS) {
                Log.d(TAG, "enterAmountAndProceed: skipping because PIN recently handled")
                return true
            }

            val amountToType = ScannerHelper.amount
            if (amountToType.isEmpty()) {
                Log.d(TAG, "No amount provided; skipping amount entry")
                return false
            }

            var amountField: AccessibilityNodeInfo? = service.findFirst(root) { n ->
                try {
                    val cls = n.className?.toString() ?: ""
                    if (!cls.contains("EditText", true)) return@findFirst false
                    val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) (n.hintText ?: "")?.toString() ?: "" else ""
                    val txt = (n.text ?: "").toString()
                    val desc = (n.contentDescription ?: "").toString()
                    val looksLikePhone = txt.matches(Regex("^\\d{7,}\$"))
                    (hint.contains("পরিমাণ", true) || txt.contains("৳") || desc.contains("৳") || hint.contains("Amount", true) || desc.contains("Amount", true)) && !looksLikePhone
                } catch (_: Throwable) { false }
            }

            if (amountField == null) {
                val labelNode = service.findByDesc(root, Regex("পরিমাণ|Amount|৳", RegexOption.IGNORE_CASE))
                if (labelNode != null) {
                    var parent = labelNode.parent
                    var found: AccessibilityNodeInfo? = null
                    var levels = 0
                    while (parent != null && levels < 4 && found == null) {
                        found = service.findFirst(parent) { it.className?.toString()?.contains("EditText", true) == true }
                        parent = parent.parent
                        levels++
                    }
                    if (found != null) {
                        val ftxt = (found.text ?: "").toString()
                        if (!ftxt.matches(Regex("^\\d{7,}\$"))) amountField = found
                        else Log.d(TAG, "label-based found edit appears to be phone/search -> ignoring as amount")
                    }
                }
            }

            if (amountField == null) {
                val recipient = ScannerHelper.recipientNumber
                amountField = service.findFirst(root) { n ->
                    try {
                        val cls = n.className?.toString() ?: ""
                        if (!cls.contains("EditText", true)) return@findFirst false
                        val txt = (n.text ?: "").toString()
                        if (recipient.isNotEmpty() && txt.contains(recipient.takeLast(4))) return@findFirst false
                        if (txt.matches(Regex("^\\d{7,}\$"))) return@findFirst false
                        true
                    } catch (_: Throwable) { false }
                }
            }

            var refEdit: AccessibilityNodeInfo? = null
            val refLabel = service.findByDesc(root, Regex("রেফারেন্স|Reference", RegexOption.IGNORE_CASE))
            if (refLabel != null) {
                var p = refLabel.parent
                var tries = 0
                search@ while (p != null && tries < 6) {
                    for (i in 0 until p.childCount) {
                        val c = try { p.getChild(i) } catch (_: Throwable) { null }
                        if (c != null) {
                            if (c.className?.toString()?.contains("EditText", true) == true) {
                                refEdit = c
                                break@search
                            }
                            val deep = service.findFirst(c) { it.className?.toString()?.contains("EditText", true) == true }
                            if (deep != null) { refEdit = deep; break@search }
                        }
                    }
                    p = p.parent
                    tries++
                }
            }

            if (amountField != null) {
                val before = (amountField.text ?: "").toString()
                Log.d(TAG, "enterAmountAndProceed: found amountField class=${amountField.className} beforeText='$before'")
                if (!before.matches(Regex("^\\d{7,}\$")) && before != amountToType) {
                    focusAndTypeIntoNode(amountField, amountToType, service)
                    service.waitMs(220)
                } else {
                    Log.d(TAG, "Amount field looks like phone or already contains target -> skipping typing")
                }
            } else {
                val labelNode = service.findByDesc(root, Regex("পরিমাণ|৳|Amount", RegexOption.IGNORE_CASE))
                if (labelNode != null) {
                    var p = labelNode.parent
                    var tries = 0
                    var nonEditTarget: AccessibilityNodeInfo? = null
                    while (p != null && tries < 6 && nonEditTarget == null) {
                        for (i in 0 until p.childCount) {
                            val child = try { p.getChild(i) } catch (_: Throwable) { null }
                            if (child != null) {
                                val cls = child.className?.toString() ?: ""
                                val txt = (child.text ?: "").toString()
                                if (child.isClickable || child.isFocusable || cls.contains("EditText", true) || txt.matches(Regex(".*\\d+.*"))) {
                                    nonEditTarget = child
                                    break
                                }
                            }
                        }
                        p = p.parent
                        tries++
                    }
                    if (nonEditTarget != null) {
                        Log.d(TAG, "enterAmountAndProceed: clicking non-Edit target to open amount input")
                        val clicked = service.clickSelfOrAncestor(nonEditTarget, 4)
                        if (!clicked) try { nonEditTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Throwable) {}
                        service.waitMs(300)
                        val afterRoot = service.rootInActiveWindow ?: root
                        val reFound = service.findFirst(afterRoot) { it.className?.toString()?.contains("EditText", true) == true && !(it.text ?: "").toString().matches(Regex("^\\d{7,}\$")) }
                        if (reFound != null) {
                            focusAndTypeIntoNode(reFound, amountToType, service)
                            service.waitMs(220)
                        } else {
                            Log.d(TAG, "enterAmountAndProceed: no EditText appeared after non-edit click")
                        }
                    } else {
                        Log.d(TAG, "enterAmountAndProceed: could not find any amount input field")
                    }
                }
            }

            val now = System.currentTimeMillis()
            if (!ScannerHelper.refs.isNullOrEmpty() && refEdit != null && (now - lastRefTypedAt > REF_TYPE_COOLDOWN_MS)) {
                val beforeRef = (refEdit.text ?: "").toString()
                val looksLikePhone = beforeRef.matches(Regex("^\\d{7,}\$"))
                if (looksLikePhone) {
                    Log.d(TAG, "refEdit looks like phone/search (text='$beforeRef') -> skipping ref typing and attempting PIN")
                } else if (beforeRef != ScannerHelper.refs) {
                    Log.d(TAG, "enterAmountAndProceed: typing reference into refEdit; beforeRef='$beforeRef'")
                    focusAndTypeIntoNode(refEdit, ScannerHelper.refs, service)
                    lastRefTypedAt = now
                    service.waitMs(180)
                } else {
                    Log.d(TAG, "Reference edit already contains value; skipping ref typing")
                    lastRefTypedAt = now
                }
                val afterRoot = service.rootInActiveWindow ?: root
                val pinHandled = handlePinIfPresent(afterRoot, service)
                if (pinHandled) {
                    lastPinHandledAt = System.currentTimeMillis()
                    Log.d(TAG, "PIN handled immediately after ref step")
                    return true
                } else {
                    Log.d(TAG, "PIN not handled immediately after ref step")
                }
            } else if (!ScannerHelper.refs.isNullOrEmpty() && refEdit == null) {
                Log.d(TAG, "ScannerHelper.refs provided but no ref edit found")
            }

            val proceedLabels = listOf("এগিয়ে যান", "এগিয়ে যান", "এগিয়ে", "Proceed", "Continue", "পরবর্তী", "Next")
            val didClickProceed = findAndClickByLabels(service.rootInActiveWindow ?: root, proceedLabels, 5, service)
            if (didClickProceed) {
                Log.d(TAG, "Clicked proceed node (one of $proceedLabels)")
                service.waitMs(600)
                val after = service.rootInActiveWindow
                if (after != null) {
                    logNodeTree(after, 0)
                    logCandidateTilesForDebug(after, service)
                    val pinHandled = handlePinIfPresent(after, service)
                    if (pinHandled) {
                        lastPinHandledAt = System.currentTimeMillis()
                        Log.d(TAG, "PIN handled after proceed — recorded timestamp")
                        return true
                    } else {
                        Log.d(TAG, "PIN not handled after proceed")
                        return false
                    }
                }
                return true
            } else {
                Log.d(TAG, "Proceed node not found among $proceedLabels")
            }

            return false
        } catch (t: Throwable) {
            Log.w(TAG, "enterAmountAndProceed failed: ${t.message}", t)
            return false
        }
    }

    // ---------- PIN handling (unchanged) ----------
    private fun handlePinIfPresent(root: AccessibilityNodeInfo, service: ScanUIService): Boolean {
        try {
            val pin = ScannerHelper.targetPin
            Log.d(TAG, "handlePinIfPresent: looking for PIN trigger; targetPin present=${pin.isNotEmpty()}")

            var pinTrigger: AccessibilityNodeInfo? = service.findByDesc(root, Regex("পিন\\s*নাম্বার|পিন\\s*নাম|PIN|পিন নাম্বার দিন", RegexOption.IGNORE_CASE))
            if (pinTrigger == null) {
                pinTrigger = service.findFirst(root) { n ->
                    try {
                        val d = (n.contentDescription ?: "").toString()
                        val t = (n.text ?: "").toString()
                        n.isClickable && (d.contains("পিন", true) || t.contains("পিন", true) || d.contains("pin", true) || t.contains("pin", true))
                    } catch (_: Throwable) { false }
                }
            }

            if (pinTrigger == null) {
                Log.d(TAG, "handlePinIfPresent: pin trigger not found on root")
                return false
            }

            Log.d(TAG, "handlePinIfPresent: found pinTrigger desc='${pinTrigger.contentDescription}' text='${pinTrigger.text}' class=${pinTrigger.className}")
            val clicked = service.clickSelfOrAncestor(pinTrigger, 6)
            if (!clicked) {
                val direct = try { pinTrigger.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Throwable) { false }
                Log.d(TAG, "handlePinIfPresent: direct click on trigger -> $direct")
            }
            service.waitMs(350)

            logPinArea(service.rootInActiveWindow ?: pinTrigger, service, maxAncestor = 6, maxDepth = 6)

            var enteredKeypad = false
            try {
                if (!pin.isNullOrEmpty()) {
                    enteredKeypad = try { service.enterPinByKeypad(pin) } catch (_: Throwable) { false }
                    Log.d(TAG, "handlePinIfPresent: service.enterPinByKeypad -> $enteredKeypad")
                }
            } catch (_: Throwable) { enteredKeypad = false }

            if (!enteredKeypad && !pin.isNullOrEmpty()) {
                val afterRoot = service.rootInActiveWindow ?: root
                enteredKeypad = enterPinViaVisibleKeypad(afterRoot, service, pin)
                Log.d(TAG, "handlePinIfPresent: enterPinViaVisibleKeypad -> $enteredKeypad")
            }

            var typedIntoNumericEdit = false
            if (!enteredKeypad && !pin.isNullOrEmpty()) {
                val numericEdit = service.findFirst(service.rootInActiveWindow ?: root) { n ->
                    try {
                        val cls = n.className?.toString() ?: ""
                        if (!cls.contains("EditText", true)) return@findFirst false
                        val txt = (n.text ?: "").toString()
                        txt.matches(Regex(".*\\d.*")) || txt.isEmpty()
                    } catch (_: Throwable) { false }
                }
                if (numericEdit != null) {
                    Log.d(TAG, "handlePinIfPresent: numeric EditText fallback -> class=${numericEdit.className}")
                    try { numericEdit.performAction(AccessibilityNodeInfo.ACTION_FOCUS) } catch (_: Throwable) {}
                    val clicked2 = try { numericEdit.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Throwable) { false }
                    if (!clicked2) service.clickSelfOrAncestor(numericEdit, 3)
                    service.waitMs(120)
                    val typedDirect = try { service.trySetText(numericEdit, pin) } catch (_: Throwable) { false }
                    typedIntoNumericEdit = if (typedDirect) true else try { service.setTextSmart(numericEdit, pin) } catch (_: Throwable) { false }
                    Log.d(TAG, "handlePinIfPresent: typed into numeric EditText -> $typedIntoNumericEdit")
                    service.waitMs(200)
                } else {
                    Log.d(TAG, "handlePinIfPresent: numeric Edit fallback not present")
                }
            }

            if (enteredKeypad || typedIntoNumericEdit) {
                val confirmLabels = listOf("পিন কনফার্ম করুন", "পিন কনফার্ম", "কনফার্ম করুন", "Confirm PIN", "Confirm", "ঠিক আছে", "OK")
                val clickedConfirm = findAndClickByLabels(service.rootInActiveWindow ?: root, confirmLabels, 8, service)
                if (clickedConfirm) {
                    Log.d(TAG, "handlePinIfPresent: clicked PIN confirm via label list")
                    lastPinHandledAt = System.currentTimeMillis()
                    service.waitMs(450)
                    return true
                }

                val anyConfirm = service.findFirst(service.rootInActiveWindow ?: root) { n ->
                    try {
                        val d = (n.contentDescription ?: "").toString()
                        val t = (n.text ?: "").toString()
                        (d.contains("কনফার্ম", true) || t.contains("কনফার্ম", true) || d.contains("Confirm", true) || t.contains("Confirm", true))
                    } catch (_: Throwable) { false }
                }
                if (anyConfirm != null) {
                    var cur: AccessibilityNodeInfo? = anyConfirm
                    var hops = 0
                    while (cur != null && hops < 10) {
                        if (cur.isClickable && cur.isEnabled) {
                            val c = try { cur.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Throwable) { false }
                            Log.d(TAG, "handlePinIfPresent: tried clicking confirm ancestor hop=$hops result=$c")
                            if (c) {
                                lastPinHandledAt = System.currentTimeMillis()
                                service.waitMs(450)
                                return true
                            }
                        }
                        cur = cur.parent
                        hops++
                    }
                }

                Log.w(TAG, "handlePinIfPresent: PIN entered but confirm not found/clicked. Marking PIN handled to avoid re-loop.")
                lastPinHandledAt = System.currentTimeMillis()
                return true
            } else {
                Log.d(TAG, "handlePinIfPresent: keypad/edit did not enter PIN")
                return false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "handlePinIfPresent failed: ${t.message}", t)
            return false
        }
    }

    private fun enterPinViaVisibleKeypad(root: AccessibilityNodeInfo?, service: ScanUIService, pin: String): Boolean {
        if (root == null) return false
        try {
            fun clickDigit(digit: Char): Boolean {
                val dStr = digit.toString()
                val node = service.findFirst(root) { n ->
                    try {
                        val t = (n.text ?: "").toString().trim()
                        val d = (n.contentDescription ?: "").toString().trim()
                        (t == dStr || d == dStr) && (n.isClickable || n.isEnabled)
                    } catch (_: Throwable) { false }
                }
                if (node != null) {
                    var cur: AccessibilityNodeInfo? = node
                    var hops = 0
                    while (cur != null && hops < 8) {
                        if (cur.isClickable && cur.isEnabled) {
                            val clicked = try { cur.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Throwable) { false }
                            if (clicked) return true
                        }
                        cur = cur.parent
                        hops++
                    }
                }

                val nodeContains = service.findFirst(root) { n ->
                    try {
                        val t = (n.text ?: "").toString().trim()
                        val d = (n.contentDescription ?: "").toString().trim()
                        (t.contains(dStr) || d.contains(dStr)) && (n.isClickable || n.isEnabled)
                    } catch (_: Throwable) { false }
                }
                if (nodeContains != null) {
                    var cur2: AccessibilityNodeInfo? = nodeContains
                    var hops2 = 0
                    while (cur2 != null && hops2 < 8) {
                        if (cur2.isClickable && cur2.isEnabled) {
                            val clicked = try { cur2.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Throwable) { false }
                            if (clicked) return true
                        }
                        cur2 = cur2.parent
                        hops2++
                    }
                }

                return false
            }

            fun clickDigitById(digit: Char): Boolean {
                val dStr = digit.toString()
                val node = service.findFirst(root) { n ->
                    try {
                        val id = try { n.viewIdResourceName ?: "" } catch (_: Throwable) { "" }
                        id.contains(dStr)
                    } catch (_: Throwable) { false }
                }
                if (node != null) {
                    var cur: AccessibilityNodeInfo? = node
                    var hops = 0
                    while (cur != null && hops < 8) {
                        if (cur.isClickable && cur.isEnabled) {
                            val clicked = try { cur.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Throwable) { false }
                            if (clicked) return true
                        }
                        cur = cur.parent
                        hops++
                    }
                }
                return false
            }

            for (ch in pin) {
                val ok = clickDigit(ch) || clickDigitById(ch)
                if (!ok) {
                    Log.d(TAG, "enterPinViaVisibleKeypad: could not find clickable node for digit='$ch'")
                    try { Thread.sleep(90) } catch (_: InterruptedException) {}
                    val retry = clickDigit(ch) || clickDigitById(ch)
                    if (!retry) {
                        Log.d(TAG, "enterPinViaVisibleKeypad: retry also failed for digit='$ch'")
                        return false
                    }
                }
                try { Thread.sleep(120) } catch (_: InterruptedException) {}
            }

            Log.d(TAG, "enterPinViaVisibleKeypad: successfully clicked all digits for pin")
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "enterPinViaVisibleKeypad failed: ${t.message}", t)
            return false
        }
    }

    // ---------- click confirm number ----------
    private fun clickConfirmNumberIfPresent(root: AccessibilityNodeInfo?, service: ScanUIService): Boolean {
        if (root == null) return false
        try {
            val exactRegex = Regex("হ্যাঁ\\s*,?\\s*নাম্বারটি\\s*সঠিক", RegexOption.IGNORE_CASE)
            var node: AccessibilityNodeInfo? = service.findByDesc(root, exactRegex)

            if (node == null) {
                node = service.findFirst(root) { n ->
                    try {
                        val d = (n.contentDescription ?: "").toString()
                        val t = (n.text ?: "").toString()
                        val combined = (d + " " + t)
                        combined.contains("হ্যাঁ", true) && combined.contains("নাম্বার", true) && combined.contains("সঠিক", true)
                    } catch (_: Throwable) { false }
                }
            }

            if (node == null) {
                Log.d(TAG, "Confirmation node ('হ্যাঁ, নাম্বারটি সঠিক') not found on this root")
                return false
            }

            Log.d(TAG, "clickConfirmNumberIfPresent: found candidate class=${node.className} text='${node.text}' desc='${node.contentDescription}'")

            // Try clickSelfOrAncestor
            val clicked = service.clickSelfOrAncestor(node, 6)
            if (clicked) {
                Log.d(TAG, "Clicked confirm-number via clickSelfOrAncestor")
                service.waitMs(450)
                val after = service.rootInActiveWindow
                if (after != null) { logNodeTree(after, 0); logCandidateTilesForDebug(after, service) }
                return true
            }

            // Direct performAction
            val direct = try { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Throwable) { false }
            if (direct) {
                Log.d(TAG, "Clicked confirm-number via node.performAction")
                service.waitMs(450)
                val after = service.rootInActiveWindow
                if (after != null) { logNodeTree(after, 0); logCandidateTilesForDebug(after, service) }
                return true
            }

            // Click clickable ancestor
            var cur: AccessibilityNodeInfo? = node
            var hops = 0
            while (cur != null && hops < 8) {
                if (cur.isClickable && cur.isEnabled) {
                    val pClick = try { cur.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Throwable) { false }
                    Log.d(TAG, "Attempted click on ancestor hop=$hops -> result=$pClick")
                    if (pClick) {
                        service.waitMs(450)
                        val after = service.rootInActiveWindow
                        if (after != null) { logNodeTree(after, 0); logCandidateTilesForDebug(after, service) }
                        return true
                    }
                }
                cur = cur.parent
                hops++
            }

            // Fallback to exact desc match search+click
            val alt = service.findFirst(root) { n ->
                try {
                    val d = (n.contentDescription ?: "").toString()
                    d.equals("হ্যাঁ, নাম্বারটি সঠিক", true) || d.equals("হ্যাঁ নাম্বারটি সঠিক", true)
                } catch (_: Throwable) { false }
            }
            if (alt != null) {
                val altClicked = service.clickSelfOrAncestor(alt, 6)
                if (altClicked) {
                    Log.d(TAG, "Clicked confirm-number via alt exact match")
                    service.waitMs(450)
                    return true
                }
            }

            Log.w(TAG, "Failed to click confirmation node even though it was found")
            return false
        } catch (t: Throwable) {
            Log.w(TAG, "clickConfirmNumberIfPresent failed: ${t.message}", t)
            return false
        }
    }

    private fun findSearchField(newRoot: AccessibilityNodeInfo, service: ScanUIService): AccessibilityNodeInfo? {
        var searchField: AccessibilityNodeInfo? = service.findFirst(newRoot) { n ->
            try {
                val cls = n.className?.toString() ?: ""
                if (!cls.contains("EditText", true)) return@findFirst false
                val hint = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) (n.hintText ?: "") else "")?.toString() ?: ""
                val desc = (n.contentDescription ?: "").toString()
                val text = (n.text ?: "").toString()
                (hint.contains("নাম", true) || hint.contains("নাম্বার", true)
                        || desc.contains("নাম", true) || desc.contains("নাম্বার", true)
                        || text.contains("নাম", true) || text.contains("নাম্বার", true))
            } catch (_: Throwable) { false }
        }
        if (searchField == null) {
            searchField = service.findFirst(newRoot) { n ->
                try {
                    val id = try { n.viewIdResourceName ?: "" } catch (_: Throwable) { "" }
                    val cls = n.className?.toString() ?: ""
                    (cls.contains("EditText", true) && (id.contains("search", true) || id.contains("name", true) || id.contains("number", true)))
                } catch (_: Throwable) { false }
            }
        }
        if (searchField == null) {
            searchField = service.findFirst(newRoot) { it.className?.contains("EditText", true) == true }
        }
        return searchField
    }

    // ---------- long-press helpers (new) ----------
    /**
     * Attempt a long-press either on a node (if provided) or at screen coordinates (if x/y provided).
     * Returns true if dispatchGesture reported completion within timeout.
     */
    private fun performExhaustiveLongPress(service: ScanUIService, node: AccessibilityNodeInfo?, x: Float? = null, y: Float? = null): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "performExhaustiveLongPress: API < N, cannot dispatchGesture")
            return false
        }

        val accService = try { service as AccessibilityService } catch (_: Throwable) { null }
        if (accService == null) {
            Log.w(TAG, "performExhaustiveLongPress: service is not AccessibilityService")
            return false
        }

        val (cx, cy) = if (node != null) {
            val r = Rect()
            try { node.getBoundsInScreen(r) } catch (_: Throwable) { }
            if (r.isEmpty) {
                if (x != null && y != null) Pair(x, y) else return false
            } else {
                Pair((r.left + r.right) / 2f, (r.top + r.bottom) / 2f)
            }
        } else {
            if (x != null && y != null) Pair(x, y) else return false
        }

        // build a path that presses at (cx,cy) and holds for duration
        try {
            val path = Path().apply { moveTo(cx, cy) }
            val duration = 650L
            val stroke = GestureDescription.StrokeDescription(path, 0, duration)
            val gd = GestureDescription.Builder().addStroke(stroke).build()

            val latch = CountDownLatch(1)
            var success = false

            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    success = true
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    success = false
                    latch.countDown()
                }
            }

            val posted = accService.dispatchGesture(gd, callback, null)
            if (!posted) {
                Log.w(TAG, "performExhaustiveLongPress: dispatchGesture returned false immediately")
                return false
            }

            // wait up to 1200ms for completion
            try {
                val awaited = latch.await(1200L, TimeUnit.MILLISECONDS)
                if (!awaited) {
                    Log.w(TAG, "performExhaustiveLongPress: timeout waiting for gesture completion")
                }
            } catch (ie: InterruptedException) {
                Log.w(TAG, "performExhaustiveLongPress: interrupted while waiting", ie)
            }

            return success
        } catch (t: Throwable) {
            Log.w(TAG, "performExhaustiveLongPress failed: ${t.message}", t)
            return false
        }
    }

    // ---------- tap-at-bounds fallback ----------
    private fun performTapAtBounds(service: ScanUIService, node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            // can't dispatch gesture; try performAction on node or ancestor
            val direct = try { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Throwable) { false }
            if (direct) return true
            var p = node.parent
            var hops = 0
            while (p != null && hops < 8) {
                val pc = try { p.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Throwable) { false }
                if (pc) return true
                p = p.parent; hops++
            }
            return false
        }

        val accService = try { service as AccessibilityService } catch (_: Throwable) { null } ?: return false
        val r = Rect()
        try { node.getBoundsInScreen(r) } catch (_: Throwable) { /* ignore */ }
        if (r.isEmpty) return false
        val cx = (r.left + r.right) / 2f
        val cy = (r.top + r.bottom) / 2f

        try {
            val path = Path().apply { moveTo(cx, cy) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 50)
            val gd = GestureDescription.Builder().addStroke(stroke).build()

            val latch = CountDownLatch(1)
            var success = false
            val cb = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    success = true
                    latch.countDown()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    success = false
                    latch.countDown()
                }
            }
            val posted = accService.dispatchGesture(gd, cb, null)
            if (!posted) return false
            latch.await(600, TimeUnit.MILLISECONDS)
            return success
        } catch (t: Throwable) {
            Log.w(TAG, "performTapAtBounds failed: ${t.message}", t)
            return false
        }
    }

    // ---------- click Next button (improved) ----------
    private fun clickNextButtonIfPresent(root: AccessibilityNodeInfo, service: ScanUIService): Boolean {
        try {
            // 1) Prefer exact description match (full phrase)
            var nextBtn: AccessibilityNodeInfo? = service.findByDesc(root, Regex("^পরের\\s*ধাপে\\s*যেতে\\s*ট্যাপ\\s*করুন\$", RegexOption.IGNORE_CASE))

            // 2) If not found, try contains patterns
            if (nextBtn == null) nextBtn = service.findByDesc(root, Regex("পরের\\s*ধাপে|পরের\\s*ধাপে\\s*যেতে|Next|Continue|পরবর্তী", RegexOption.IGNORE_CASE))

            // 3) Fallback: look for clickable Button/ImageView/View with desc/text containing 'পরের' AND 'ধাপে'
            if (nextBtn == null) {
                nextBtn = service.findFirst(root) { n ->
                    try {
                        val cls = n.className?.toString() ?: ""
                        val d = (n.contentDescription ?: "").toString()
                        val t = (n.text ?: "").toString()
                        // Accept Buttons and clickable Views
                        val okClass = cls.contains("Button", true) || cls.contains("ImageView", true) || cls.contains("View", true)
                        okClass && ( (d.contains("পরের", true) && d.contains("ধাপে", true)) || (t.contains("পরের", true) && t.contains("ধাপে", true)) )
                    } catch (_: Throwable) { false }
                }
            }

            if (nextBtn == null) {
                Log.d(TAG, "clickNextButtonIfPresent: Next button not found")
                return false
            }

            Log.d(TAG, "clickNextButtonIfPresent: nextBtn found class=${nextBtn.className} text='${nextBtn.text}' desc='${nextBtn.contentDescription}'")

            // Try helper click first (preferred)
            try {
                val clickedNext = service.clickSelfOrAncestor(nextBtn, 4)
                if (clickedNext) {
                    Log.d(TAG, "Clicked Next button via clickSelfOrAncestor")
                    service.waitMs(600)
                    val after = service.rootInActiveWindow
                    if (after != null) { logNodeTree(after, 0); logCandidateTilesForDebug(after, service) }
                    // try confirm overlay
                    clickConfirmNumberIfPresent(after, service)
                    return true
                }
            } catch (_: Throwable) { /* ignore helper errors */ }

            // Direct performAction
            val direct = try { nextBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Throwable) { false }
            if (direct) {
                Log.d(TAG, "Clicked Next button via performAction")
                service.waitMs(600)
                val after2 = service.rootInActiveWindow
                if (after2 != null) { logNodeTree(after2, 0); logCandidateTilesForDebug(after2, service) }
                clickConfirmNumberIfPresent(after2, service)
                return true
            }

            // Click clickable ancestor
            var cur: AccessibilityNodeInfo? = nextBtn
            var hops = 0
            while (cur != null && hops < 8) {
                if (cur.isClickable && cur.isEnabled) {
                    val pClick = try { cur.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Throwable) { false }
                    Log.d(TAG, "Attempted click on Next ancestor hop=$hops -> result=$pClick")
                    if (pClick) {
                        service.waitMs(600)
                        val after3 = service.rootInActiveWindow
                        if (after3 != null) { logNodeTree(after3, 0); logCandidateTilesForDebug(after3, service) }
                        clickConfirmNumberIfPresent(after3, service)
                        return true
                    }
                }
                cur = cur.parent
                hops++
            }

            // Final fallback: tap at bounds using gesture (API >= 24)
            val tapped = performTapAtBounds(service, nextBtn)
            if (tapped) {
                Log.d(TAG, "Tapped Next button by dispatchGesture fallback")
                service.waitMs(600)
                val after4 = service.rootInActiveWindow
                if (after4 != null) { logNodeTree(after4, 0); logCandidateTilesForDebug(after4, service) }
                clickConfirmNumberIfPresent(after4, service)
                return true
            }

            Log.w(TAG, "clickNextButtonIfPresent: failed to click Next button even though found")
            return false
        } catch (t: Throwable) {
            Log.w(TAG, "clickNextButtonIfPresent failed: ${t.message}", t)
            return false
        }
    }

    // ---------- logging helpers ----------
    private fun logPinArea(pinRoot: AccessibilityNodeInfo?, service: ScanUIService, maxAncestor: Int = 6, maxDepth: Int = 6) {
        if (pinRoot == null) {
            Log.d(TAG, "logPinArea: pinRoot == null")
            return
        }

        fun nodeSummary(n: AccessibilityNodeInfo?): String {
            if (n == null) return "<null>"
            val cls = n.className ?: "null"
            val text = (n.text ?: "").toString().replace("\n", " ").trim()
            val desc = (n.contentDescription ?: "").toString().replace("\n", " ").trim()
            val id = try { n.viewIdResourceName ?: "" } catch (_: Throwable) { "" }
            val flags = buildString {
                if (n.isClickable) append("clickable ")
                if (n.isFocusable) append("focusable ")
                if (n.isEnabled) append("enabled ")
                if (n.isAccessibilityFocused) append("a11yFocused ")
            }.trim()
            return "cls=${cls} text='${text}' desc='${desc}' id='${id}' flags='${flags}'"
        }

        Log.d(TAG, "=== PIN AREA DUMP START ===")
        Log.d(TAG, "pinRoot summary: ${nodeSummary(pinRoot)}")

        var anc: AccessibilityNodeInfo? = pinRoot.parent
        var hop = 0
        while (anc != null && hop < maxAncestor) {
            Log.d(TAG, "ancestor[$hop]: ${nodeSummary(anc)}")
            anc = anc.parent
            hop++
        }

        val found = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()
        val q = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        q.add(pinRoot to 0)
        while (q.isNotEmpty()) {
            val (cur, depth) = q.removeFirst()
            if (depth > maxDepth) continue
            try {
                val text = (cur.text ?: "").toString()
                val desc = (cur.contentDescription ?: "").toString()
                if ((cur.isClickable || cur.isFocusable || cur.isEnabled) && (text.isNotBlank() || desc.isNotBlank())) {
                    found.add(depth to cur)
                }
                for (i in 0 until cur.childCount) {
                    val c = try { cur.getChild(i) } catch (_: Throwable) { null }
                    if (c != null) q.add(c to (depth + 1))
                }
            } catch (_: Throwable) {}
        }

        Log.d(TAG, "pin-area clickable/focusable candidate count=${found.size}")
        found.take(200).forEachIndexed { idx, (d, n) ->
            val cls = n.className ?: "null"
            val text = (n.text ?: "").toString().replace("\n"," ").trim()
            val desc = (n.contentDescription ?: "").toString().replace("\n"," ").trim()
            val id = try { n.viewIdResourceName ?: "" } catch (_: Throwable) { "" }
            val flags = buildString {
                if (n.isClickable) append("clickable ")
                if (n.isFocusable) append("focusable ")
                if (n.isEnabled) append("enabled ")
            }.trim()
            Log.d(TAG, "pin-cand[$idx] depth=$d [cls=$cls] text='$text' desc='$desc' id='$id' flags='$flags'")
        }
        Log.d(TAG, "=== PIN AREA DUMP END ===")
    }

    private fun logHeuristicFindings(root: AccessibilityNodeInfo, service: ScanUIService) {
        try {
            val edits = mutableListOf<String>()
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.add(root)
            while (stack.isNotEmpty()) {
                val cur = stack.removeLast()
                try {
                    val cls = cur.className?.toString() ?: ""
                    if (cls.contains("EditText", true) || cls.contains("TextView", true) || cls.contains("Edit", true)) {
                        val hint = try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) (cur.hintText ?: "").toString() else "" } catch (_: Throwable) { "" }
                        val txt = (cur.text ?: "").toString()
                        val desc = (cur.contentDescription ?: "").toString()
                        val id = try { cur.viewIdResourceName ?: "" } catch (_: Throwable) { "" }
                        edits.add("[cls=$cls] hint='$hint' text='$txt' desc='$desc' id='$id' clickable=${cur.isClickable} focusable=${cur.isFocusable}")
                    }
                } catch (_: Throwable) {}
                for (i in 0 until cur.childCount) {
                    val child = try { cur.getChild(i) } catch (_: Throwable) { null }
                    if (child != null) stack.add(child)
                }
            }
            Log.d(TAG, "Heuristic: Found ${edits.size} EditText/Text-like nodes. Listing top 50:")
            edits.take(50).forEachIndexed { i, s -> Log.d(TAG, "Edit[$i]: $s") }

            val amountCandidates = mutableListOf<String>()
            val stack2 = ArrayDeque<AccessibilityNodeInfo>()
            stack2.add(root)
            while (stack2.isNotEmpty()) {
                val cur = stack2.removeLast()
                try {
                    val txt = (cur.text ?: "").toString()
                    val desc = (cur.contentDescription ?: "").toString()
                    if (txt.contains("৳") || txt.contains("টাকা") || txt.contains("BDT") || txt.matches(Regex(".*\\d{2,}.*"))) {
                        val cls = cur.className?.toString() ?: ""
                        val id = try { cur.viewIdResourceName ?: "" } catch (_: Throwable) { "" }
                        amountCandidates.add("[cls=$cls] text='$txt' desc='$desc' id='$id' clickable=${cur.isClickable}")
                    }
                } catch (_: Throwable) {}
                for (i in 0 until cur.childCount) {
                    val child = try { cur.getChild(i) } catch (_: Throwable) { null }
                    if (child != null) stack2.add(child)
                }
            }
            Log.d(TAG, "Heuristic: Found ${amountCandidates.size} amount-like nodes. Listing top 50:")
            amountCandidates.take(50).forEachIndexed { i, s -> Log.d(TAG, "Amount[$i]: $s") }

            val actionBtns = mutableListOf<String>()
            val stack3 = ArrayDeque<AccessibilityNodeInfo>()
            stack3.add(root)
            while (stack3.isNotEmpty()) {
                val cur = stack3.removeLast()
                try {
                    val cls = cur.className?.toString() ?: ""
                    val t = (cur.text ?: "").toString()
                    val d = (cur.contentDescription ?: "").toString()
                    if ((t.contains("Confirm", true) || t.contains("Send", true) || t.contains("পাঠান", true) || d.contains("Confirm", true) || d.contains("Send", true) || d.contains("পাঠান", true))
                        && (cls.contains("Button", true) || cls.contains("ImageView", true) || cur.isClickable)
                    ) {
                        val id = try { cur.viewIdResourceName ?: "" } catch (_: Throwable) { "" }
                        actionBtns.add("[cls=$cls] text='$t' desc='$d' id='$id' clickable=${cur.isClickable}")
                    }
                } catch (_: Throwable) {}
                for (i in 0 until cur.childCount) {
                    val child = try { cur.getChild(i) } catch (_: Throwable) { null }
                    if (child != null) stack3.add(child)
                }
            }
            Log.d(TAG, "Heuristic: Found ${actionBtns.size} action-like nodes. Listing top 50:")
            actionBtns.take(50).forEachIndexed { i, s -> Log.d(TAG, "ActionBtn[$i]: $s") }

        } catch (t: Throwable) {
            Log.w(TAG, "logHeuristicFindings failed: ${t.message}", t)
        }
    }

    private fun logNodeTree(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null) return
        val indent = "  ".repeat(depth)
        val cls = node.className ?: "null"
        val text = (node.text ?: "").toString().replace("\n", " ").trim()
        val desc = (node.contentDescription ?: "").toString().replace("\n", " ").trim()
        val id = try { node.viewIdResourceName ?: "" } catch (_: Throwable) { "" }
        val flags = buildString {
            if (node.isClickable) append("clickable ")
            if (node.isFocusable) append("focusable ")
            if (node.isEnabled) append("enabled ")
        }.trim()
        val extras = mutableListOf<String>()
        if (text.isNotEmpty()) extras.add("text='$text'")
        if (desc.isNotEmpty()) extras.add("desc='$desc'")
        if (id.isNotEmpty()) extras.add("id='$id'")
        if (flags.isNotEmpty()) extras.add(flags)
        Log.d(TAG, "$indent- ${cls} ${if (extras.isNotEmpty()) extras.joinToString(" | ") else ""}")
        if (depth >= 8) return
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Throwable) { null }
            if (child != null) logNodeTree(child, depth + 1)
        }
    }

    private fun logCandidateTilesForDebug(root: AccessibilityNodeInfo, service: ScanUIService) {
        val found = mutableListOf<String>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            try {
                val cls = cur.className?.toString() ?: ""
                val text = (cur.text ?: "").toString().replace("\n", " ").trim()
                val desc = (cur.contentDescription ?: "").toString().replace("\n", " ").trim()
                val id = try { cur.viewIdResourceName ?: "" } catch (_: Throwable) { "" }
                if ((cur.isClickable || cur.isFocusable) && (text.isNotEmpty() || desc.isNotEmpty() || id.isNotEmpty())) {
                    found.add("[cls=$cls] text='$text' desc='$desc' id='$id' clickable=${cur.isClickable}")
                }
                for (i in 0 until cur.childCount) {
                    val child = try { cur.getChild(i) } catch (_: Throwable) { null }
                    if (child != null) stack.add(child)
                }
            } catch (_: Throwable) {}
        }
        Log.d(TAG, "Candidate tiles count=${found.size}")
        found.take(200).forEachIndexed { idx, s -> Log.d(TAG, "Candidate[$idx]: $s") }
    }

    private fun findAndClickByLabels(
        root: AccessibilityNodeInfo?,
        labels: List<String>,
        maxAncestorHops: Int,
        service: ScanUIService
    ): Boolean {
        if (root == null) return false
        fun String.norm() = this.trim().lowercase()
        val exacts = labels.map { it.norm() }
        val contains = labels.map { it.norm() }

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            try {
                val text = (cur.text ?: "").toString().trim()
                val desc = (cur.contentDescription ?: "").toString().trim()
                val candidate = listOf(text, desc).map { it.lowercase() }

                val isMatch = candidate.any { c ->
                    exacts.any { it == c } || contains.any { c.contains(it) }
                }

                if (isMatch) {
                    var nodeToClick: AccessibilityNodeInfo? = cur
                    var hops = 0
                    while (nodeToClick != null && hops <= maxAncestorHops) {
                        if (nodeToClick.isClickable && nodeToClick.isEnabled) {
                            val clicked = try { nodeToClick.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Throwable) { false }
                            if (clicked) return true
                        }
                        nodeToClick = nodeToClick.parent
                        hops++
                    }
                }

                for (i in 0 until cur.childCount) {
                    val c = try { cur.getChild(i) } catch (_: Throwable) { null }
                    if (c != null) stack.add(c)
                }
            } catch (_: Throwable) { /* ignore inaccessible nodes */ }
        }
        return false
    }

    // ---------- dumpClickableNodesBounds ----------
    private fun dumpClickableNodesBounds(service: ScanUIService) {
        try {
            val root = service.rootInActiveWindow ?: return
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.add(root)
            val out = mutableListOf<String>()
            while (stack.isNotEmpty()) {
                val n = stack.removeFirst()
                try {
                    val cls = n.className?.toString() ?: "null"
                    val desc = (n.contentDescription ?: "").toString().replace("\n", " ").trim()
                    val txt = (n.text ?: "").toString().replace("\n", " ").trim()
                    val id = try { n.viewIdResourceName ?: "" } catch (_: Throwable) { "" }
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    val bounds = if (r.isEmpty) "<empty>" else "(${r.left},${r.top})-(${r.right},${r.bottom})"
                    val flags = buildString {
                        if (n.isClickable) append("clickable ")
                        if (n.isFocusable) append("focusable ")
                        if (n.isEnabled) append("enabled ")
                    }.trim()
                    if (flags.isNotEmpty()) {
                        out.add("[${cls}] bounds=$bounds flags='$flags' id='$id' desc='$desc' text='$txt'")
                    }
                } catch (_: Throwable) {}
                for (i in 0 until n.childCount) {
                    val c = try { n.getChild(i) } catch (_: Throwable) { null }
                    if (c != null) stack.add(c)
                }
            }
            Log.d(TAG, "=== CLICKABLE NODES DUMP START ===")
            out.sortedBy { it }.forEachIndexed { i, s -> Log.d(TAG, "Clickable[$i]: $s") }
            Log.d(TAG, "=== CLICKABLE NODES DUMP END ===")
        } catch (t: Throwable) {
            Log.w(TAG, "dumpClickableNodesBounds failed: ${t.message}", t)
        }
    }
}
