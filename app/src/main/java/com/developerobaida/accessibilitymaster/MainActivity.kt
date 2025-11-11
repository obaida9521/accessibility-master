package com.developerobaida.accessibilitymaster

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.developerobaida.accessibilitymaster.Services.Flow
import com.developerobaida.accessibilitymaster.Services.ForegroundServiceStarter
import com.developerobaida.accessibilitymaster.Services.ScannerHelper
import com.developerobaida.accessibilitymaster.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Action string the service uses to notify us
    private val ACTION_SEARCH_MATCH = "com.developerobaida.accessibilitymaster.ACTION_SEARCH_MATCH"

    private val matchReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_SEARCH_MATCH) {
                val trxId = intent.getStringExtra("trxId") ?: return
                onBkashMatch(trxId)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        enableEdgeToEdge()
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.openBkash.setOnClickListener {
            val pin = binding.targetPin.text.toString() ?: ""
            val query = binding.searchQuery.text.toString() ?: ""

            ScannerHelper.flowForService = Flow.TRX_CHECK

            ScannerHelper.targetPin = pin
            ScannerHelper.searchQuery = query
            openBkash()
        }

        binding.openBkashSendMoney.setOnClickListener {
            val pin = binding.targetPin.text.toString() ?: ""
            val query = binding.searchQuery.text.toString() ?: ""

            ScannerHelper.targetPin = pin
            ScannerHelper.searchQuery = query

            ScannerHelper.flowForService = Flow.SEND_MONEY

            ScannerHelper.recipientNumber = "01920648162"
            ScannerHelper.refs = "greiugh"

            ScannerHelper.amount = "1"

            openBkash()
        }

        // If the activity was started/reshown by the service with extras:
        handleIntentIfFromService(intent)

        if (!Commons.isAccessibilityServiceEnabled(this)) {
            Commons.promptEnableAccessibility(context = this)
        }
    }

    // Correct override signature: non-null Intent
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Optional: so getIntent() returns the latest one next time
        setIntent(intent)
        handleIntentIfFromService(intent)
    }

    private fun handleIntentIfFromService(incoming: Intent?) {
        if (incoming == null) return
        if (incoming.getBooleanExtra("fromService", false)) {
            incoming.getStringExtra("trxId")?.let { trxId ->
                onBkashMatch(trxId)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            ForegroundServiceStarter.start(this)
        }
    }

    private fun openBkash() {
        val pkg = "com.bKash.customerapp" // case-sensitive

        // 1) Try normal launcher intent
        packageManager.getLaunchIntentForPackage(pkg)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
            return
        }

        // 2) Resolve MAIN/LAUNCHER by package if needed
        val main = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(pkg)
        }
        val candidates = packageManager.queryIntentActivities(main, 0)
        if (candidates.isNotEmpty()) {
            val resolve = candidates.first().activityInfo
            val explicit = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setClassName(resolve.packageName, resolve.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(explicit)
            return
        }

        // 3) Not installed → Play Store
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
        } catch (_: Throwable) {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$pkg")
                )
            )
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ACTION_SEARCH_MATCH)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(matchReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(matchReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(matchReceiver)
        } catch (_: Throwable) {}
    }

    /** Your callback method — do whatever you need here. */
    private fun onBkashMatch(trxId: String) {
        Log.d("BKASH_APP", "Got matched TrxID: $trxId")
        Toast.makeText(this,"Got matched TrxID: $trxId", Toast.LENGTH_LONG).show()
        // TODO: update UI / call your backend / etc.
    }
}
