package com.developerobaida.accessibilitymaster.Services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.developerobaida.accessibilitymaster.Services.ScannerHelper.searchQuery
import com.developerobaida.accessibilitymaster.Services.ScannerHelper.targetPin
import com.developerobaida.accessibilitymaster.Services.actions.BkashSendMoneyAction

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.DisplayMetrics

class ScanUIService : AccessibilityService() {

    private val TAG = "BKASH_SCAN"

    /* ======= Tunables ======= */
    private var firstActionDuration: Long = 1000L
    private var duration: Long = 1000L
    private val targetPkgs = setOf("com.bKash.customerapp")
    private val acceptPhoneNumberMatch = false
    /* ======================== */

    fun waitMs(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    // simple per-action cooldowns so we can try on every event without spamming clicks
    private val lastActionAt = mutableMapOf<String, Long>()
    fun canAct(key: String, cooldownMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val ok = now - (lastActionAt[key] ?: 0L) > cooldownMs
        if (ok) lastActionAt[key] = now
        return ok
    }

    enum class Page { LOGIN_PIN, OTP, HOME, INBOX, UNKNOWN }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.apply {
            packageNames = targetPkgs.toTypedArray()
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 50

            // inside ScanUIService or some init block
            ServiceRegistry.register("com.bKash.customerapp", Flow.SEND_MONEY, BkashSendMoneyAction)

        }
        serviceInfo = info
        Log.d(TAG, "Service connected; watching $targetPkgs")
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg !in targetPkgs) return

        val root = rootInActiveWindow ?: return
        val page = detectPage(root)
        Log.d(TAG, "Detected page: $page")




        val appPkg = event.packageName?.toString() ?: return
        val flow = ScannerHelper.flowForService
        val action = ServiceRegistry.get(appPkg, flow)
        if (action != null) {
            action.onEvent(root, this)
        } else {
            // ROUTE EVERY TIME (actions are self-throttled)
            when (page) {
                Page.LOGIN_PIN -> tryLogin()
                Page.OTP       -> { /* optional */ }
                Page.INBOX     -> trySearchOnInbox()
                Page.HOME      -> ensureInboxAndSearch()
                Page.UNKNOWN   -> { /* noop */ }
            }
        }

    }

    /* ================= Page Detection ================= */

    fun detectPage(root: AccessibilityNodeInfo): Page {
        // LOGIN
        val isLogin =
            findByDesc(root, Regex("একাউন্ট\\s*নাম্বার")) != null &&
                    (findByDesc(root, Regex("বিকাশ\\s*পিন")) != null ||
                            findByDesc(root, Regex("pin", RegexOption.IGNORE_CASE)) != null)
        if (isLogin) return Page.LOGIN_PIN

        // OTP
        val isOtp =
            findByDesc(root, Regex("OTP|ওটিপি", RegexOption.IGNORE_CASE)) != null ||
                    findByDesc(root, Regex("ভেরিফাই|Verify", RegexOption.IGNORE_CASE)) != null
        if (isOtp) return Page.OTP

        // INBOX (win over HOME)
        if (isInboxUi(root)) return Page.INBOX

        // HOME (fallback)
        if (isHomeUi(root)) return Page.HOME

        return Page.UNKNOWN
    }

    fun isInboxUi(root: AccessibilityNodeInfo): Boolean {
        val hasSearchStrip =
            findByDesc(root, Regex("TrxID\\s*বা\\s*নাম্বার\\s*দিয়ে\\s*খুঁজুন")) != null ||
                    findByDesc(root, Regex("Search\\s*by\\s*(TrxID|number)", RegexOption.IGNORE_CASE)) != null

        val hasTabs =
            findByDesc(root, Regex("লেনদেনসমূহ\\s*Tab\\s*\\d+\\s*of\\s*\\d+")) != null ||
                    findByDesc(root, Regex("নোটিফিকেশন\\s*Tab\\s*\\d+\\s*of\\s*\\d+")) != null

        val has90Days =
            findByDesc(root, Regex("গত\\s*৯০\\s*দিনের")) != null ||
                    findByDesc(root, Regex("90\\s*days", RegexOption.IGNORE_CASE)) != null

        val titlePlusEdit =
            findByDesc(root, Regex("^ইনবক্স$")) != null &&
                    findFirst(root) { it.className?.contains("EditText", true) == true } != null

        return hasSearchStrip || hasTabs || has90Days || titlePlusEdit
    }

    fun isHomeUi(root: AccessibilityNodeInfo): Boolean {
        val pats = listOf(
            Regex("\\bHome\\b", RegexOption.IGNORE_CASE), Regex("হোম"),
            Regex("Send\\s*Money", RegexOption.IGNORE_CASE),
            Regex("Cash\\s*Out", RegexOption.IGNORE_CASE),
            Regex("Add\\s*Money", RegexOption.IGNORE_CASE),
            Regex("Statement", RegexOption.IGNORE_CASE),
            Regex("সেন্ড\\s*মানি"), Regex("ক্যাশ\\s*আউট"),
            Regex("স্টেটমেন্ট"), Regex("এড\\s*মানি|অ্যাড\\s*মানি"),
            Regex("\\bbKash\\b", RegexOption.IGNORE_CASE), Regex("বিকাশ")
        )
        return findAnyDesc(root, pats) != null
    }

    /* ================= Actions ================= */

    fun tryLogin() {
        // act at most once per 1.2s
        if (!canAct("login", 1200)) return
        val pin = targetPin ?: return
        if (pin.length !in 4..5 || !pin.all { it.isDigit() }) return

        val r = rootInActiveWindow ?: return
        val pinField = findByDesc(r, Regex("বিকাশ\\s*পিন", RegexOption.IGNORE_CASE))
            ?: findByDesc(r, Regex("pin", RegexOption.IGNORE_CASE))
            ?: return

        pinField.performSafeAction(AccessibilityNodeInfo.ACTION_FOCUS)
        pinField.performSafeAction(AccessibilityNodeInfo.ACTION_CLICK)
        waitMs(firstActionDuration)

        val typed = if (trySetText(pinField, pin)) true else enterPinByKeypad(pin)
        if (!typed) return

        waitMs(duration)

        // click পরবর্তী
        waitForEnabledAndClickRefreshingRoot(
            prefer = {
                findByDesc(it, Regex("পরবর্তী"))
                    ?: findFirst(it) { n ->
                        (n.className?.contains("ImageView") == true ||
                                n.className?.contains("Button")   == true ||
                                n.className?.contains("View")     == true) &&
                                ((n.contentDescription ?: "").toString().contains("পরবর্তী"))
                    }
            },
            fallback = {
                findFirst(it) { n ->
                    (n.className?.contains("Button") == true ||
                            n.className?.contains("ImageView") == true ||
                            n.className?.contains("View") == true) &&
                            n.isEnabled && (n.isClickable || n.isFocusable)
                }
            },
            timeoutMs = 4000, pollMs = 120
        )
    }

    /** When on HOME, keep trying to open Inbox and then perform the search. */
    fun ensureInboxAndSearch() {
        if (!canAct("home.ensureInbox", 700)) return

        val root = rootInActiveWindow ?: return
        if (!isInboxUi(root)) {
            // Try to click the bottom "ইনবক্স" tab robustly
            clickInboxBottomTabRobust()
            // Give it a moment to become INBOX
            waitMs(250)
        }

        // If it already is INBOX, perform the search
        val r2 = rootInActiveWindow ?: return
        if (isInboxUi(r2)) {
            trySearchOnInbox()
        }
    }

    /** When already on INBOX, type & submit the query. */
    fun trySearchOnInbox() {
        if (!canAct("inbox.search", 900)) return

        val r1 = rootInActiveWindow ?: return

        // open the search row if needed
        val strip = findByDesc(r1, Regex("^TrxID\\s*বা\\s*নাম্বার\\s*দিয়ে\\s*খুঁজুন$"))
            ?: findByDesc(r1, Regex("Search\\b.*(TrxID|number)", RegexOption.IGNORE_CASE))
            ?: findFirst(r1) { it.className?.contains("EditText", true) == true } // if already open
        if (strip != null) clickSelfOrAncestor(strip, 3)

        waitMs(firstActionDuration)

        // type
        val typed = waitForAndTypeInEditable(searchQuery, timeoutMs = 6000, pollMs = 150)
        if (!typed) return

        waitMs(duration)

        // submit (খুঁজুন)
        val submitted = clickSearchButtonRobust(timeoutMs = 5000, pollMs = 160)
        if (!submitted) return

        waitMs(duration)

        // evaluate results
        evaluateSearchResult(searchQuery)
    }

    /* ============== Inbox helpers ============== */

    /** Clicks the bottom “ইনবক্স” tab using several strategies. */
    fun clickInboxBottomTabRobust(): Boolean {
        // A) Direct label
        if (waitForEnabledAndClickRefreshingRoot(
                prefer = { findByDesc(it, Regex("^ইনবক্স$")) ?: findByDesc(it, Regex("^Inbox$", RegexOption.IGNORE_CASE)) },
                fallback = {
                    findFirst(it) { n ->
                        (n.className?.contains("ImageView", true) == true ||
                                n.className?.contains("View", true)      == true) &&
                                (n.isClickable || n.isFocusable) &&
                                ((n.contentDescription ?: "").toString().contains("ইনবক্স") ||
                                        (n.contentDescription ?: "").toString().contains("Inbox", true))
                    }
                },
                timeoutMs = 1000, pollMs = 80
            )) return true

        // B) Find any one bottom-tab label and then click its siblings’ "ইনবক্স"
        val r = rootInActiveWindow ?: return false
        val anyTab = listOf("হোম", "QR স্ক্যান", "সার্চ", "ইনবক্স").firstNotNullOfOrNull { lab ->
            findByDesc(r, Regex("^$lab$"))
        }
        if (anyTab != null) {
            var p = anyTab.parent
            repeat(4) {
                if (p == null) return@repeat
                // scan siblings for inbox
                for (i in 0 until p!!.childCount) {
                    val s = p!!.getChild(i) ?: continue
                    val d = (s.contentDescription ?: "").toString()
                    if (d.equals("ইনবক্স", true) || d.equals("Inbox", true)) {
                        if (clickSelfOrAncestor(s, 2)) return true
                    }
                }
                p = p!!.parent
            }
        }
        return false
    }

    /* ============== Evaluation and return-to-app ============== */

    fun evaluateSearchResult(query: String): Boolean {
        val r = rootInActiveWindow ?: return false

        // No result banner?
        val noResult = findFirst(r) { n ->
            val d = (n.contentDescription ?: "").toString()
            d.contains("ফলাফল পাওয়া যায়নি") || d.contains("No results", true)
        }
        if (noResult != null) {
            Log.d(TAG, "NO RESULT for $query")
            return false
        }

        // Ignore echo inside EditText
        findFirst(r) { n ->
            n.className?.contains("EditText", true) == true &&
                    ((n.text ?: "").toString().contains(query, true))
        }?.let { Log.d(TAG, "Ignoring EditText echo of $query") }

        // Real result tiles have focusable card/image with TrxID line
        val trxRegex = Regex("""\bTrxID\s*:\s*$query\b""", RegexOption.IGNORE_CASE)
        val tile = findFirst(r) { n ->
            val klass = n.className?.toString() ?: ""
            if (!(klass.contains("ImageView", true) || klass.contains("View", true))) return@findFirst false
            if (!n.isEnabled || !n.isFocusable) return@findFirst false
            val desc = (n.contentDescription ?: "").toString().replace("\n", " ").trim()
            trxRegex.containsMatchIn(desc)
        }

        val phoneTile = if (acceptPhoneNumberMatch) {
            findFirst(r) { n ->
                val klass = n.className?.toString() ?: ""
                if (!(klass.contains("ImageView", true) || klass.contains("View", true))) return@findFirst false
                if (!n.isEnabled || !n.isFocusable) return@findFirst false
                val desc = (n.contentDescription ?: "").toString().replace("\n", " ").trim()
                desc.contains(query, true)
            }
        } else null

        val match = tile ?: phoneTile
        val matched = match != null
        if (matched) {
            Log.d(TAG, "MATCHED tile for $query")
            clickSelfOrAncestor(match!!, 3)
            bringAppToFrontWithMatch(query)
        } else {
            Log.d(TAG, "No real result tile for $query")
        }
        return matched
    }

    fun bringAppToFrontWithMatch(trxId: String) {
        val pkg = applicationContext.packageName
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) {
            launch.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            launch.putExtra("fromService", true)
            launch.putExtra("trxId", trxId)
            startActivity(launch)
        }
        sendBroadcast(Intent("com.developerobaida.accessibilitymaster.ACTION_SEARCH_MATCH").apply {
            putExtra("trxId", trxId)
        })
    }

    /* ============== Low-level utils ============== */

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

    fun enterPinByKeypad(pin: String): Boolean {
        for (ch in pin) {
            val r = rootInActiveWindow ?: return false
            val btn = findByDesc(r, Regex("^$ch$")) ?: return false
            if (!clickSelfOrAncestor(btn, 2)) return false
            try { Thread.sleep(80) } catch (_: InterruptedException) {}
        }
        return true
    }

    fun clickSearchButtonRobust(timeoutMs: Long, pollMs: Long): Boolean {
        val labels = listOf("খুঁজুন", "Search")
        val start = System.currentTimeMillis()
        fun matches(n: AccessibilityNodeInfo): Boolean {
            val d = (n.contentDescription ?: "").toString().replace("\n", " ").trim()
            val t = (n.text ?: "").toString().replace("\n", " ").trim()
            return labels.any { lab -> d.equals(lab, true) || t.equals(lab, true) || d.contains(lab, true) || t.contains(lab, true) }
        }
        while (System.currentTimeMillis() - start < timeoutMs) {
            val root = rootInActiveWindow ?: return false
            val btn = findFirst(root) { n ->
                (n.className?.contains("Button", true) == true ||
                        n.className?.contains("ImageView", true) == true ||
                        n.className?.contains("View", true) == true) &&
                        (n.isClickable || n.isFocusable) && n.isEnabled && matches(n)
            }
            if (btn != null && clickSelfOrAncestor(btn, 3)) return true

            // Sibling of EditText fallback
            val edit = findFirst(root) { it.className?.contains("EditText", true) == true }
            if (edit != null) {
                val p = edit.parent
                if (p != null) {
                    for (i in 0 until p.childCount) {
                        val s = p.getChild(i) ?: continue
                        if (s == edit) continue
                        val ok = (s.isEnabled && (s.isClickable || s.isFocusable)) &&
                                (s.className?.contains("Button", true) == true ||
                                        s.className?.contains("ImageView", true) == true ||
                                        s.className?.contains("View", true) == true)
                        if (ok && clickSelfOrAncestor(s, 2)) return true
                    }
                }
            }
            try { Thread.sleep(pollMs) } catch (_: InterruptedException) {}
        }
        return false
    }

    fun waitForEnabledAndClickRefreshingRoot(
        prefer: (AccessibilityNodeInfo) -> AccessibilityNodeInfo?,
        fallback: (AccessibilityNodeInfo) -> AccessibilityNodeInfo?,
        timeoutMs: Long,
        pollMs: Long
    ): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val fresh = rootInActiveWindow ?: return false
            val node = prefer(fresh) ?: fallback(fresh)
            if (node != null && clickSelfOrAncestor(node, 3)) return true
            try { Thread.sleep(pollMs) } catch (_: InterruptedException) {}
        }
        return false
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

    fun waitForAndTypeInEditable(text: String, timeoutMs: Long, pollMs: Long): Boolean {
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
                    waitMs(duration)
                    return true
                } else return false
            }
            try { Thread.sleep(pollMs) } catch (_: InterruptedException) {}
        }
        return false
    }

    fun setTextSmart(target: AccessibilityNodeInfo, value: String): Boolean {
        if (trySetText(target, value)) return true
        return try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("q", value))
            target.performSafeAction(AccessibilityNodeInfo.ACTION_FOCUS)
            target.performSafeAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (_: Throwable) { false }
    }

    fun findByDesc(node: AccessibilityNodeInfo?, pattern: Regex): AccessibilityNodeInfo? {
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

    fun AccessibilityNodeInfo.performSafeAction(action: Int): Boolean =
        try { performAction(action) } catch (_: Throwable) { false }

    fun findAnyDesc(root: AccessibilityNodeInfo, patterns: List<Regex>): AccessibilityNodeInfo? {
        for (p in patterns) findByDesc(root, p)?.let { return it }
        return null
    }


}
