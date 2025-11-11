// Services/ScannerHelper.kt
package com.developerobaida.accessibilitymaster.Services

object ScannerHelper {
    var targetPin: String = ""
    var refs: String = ""
    var searchQuery: String = ""
    var acceptPhoneNumberMatch: Boolean = false

    var flowForService: Flow = Flow.TRX_CHECK
    var flowAuthorPackageName: String = ""

    // parameters for flows
    var recipientNumber: String = ""
    var amount: String = ""
    var currency: String = "BDT"
}