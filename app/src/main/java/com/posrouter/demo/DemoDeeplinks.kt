package com.posrouter.demo

import android.net.Uri
import com.posrouter.POSRouter
import com.posrouter.PaymentRequest
import com.posrouter.PaymentResult

/**
 * Demo POS deeplinks — same partner role as GoMenu, with demo's own scheme.
 *
 * - Register: [buildKioskConnectUri] → stores demo scheme in kiosk connect bucket
 * - Launch kiosk: [buildKioskChargeUri] → optional `callback_url` or `partner_scheme`; else kiosk default from connect
 * - Receive kiosk relay + Ezypos callbacks: [PAY_RESULT_URI]
 *
 * GoMenu integration is analogous using `gomenu://pay_result` on connect.
 */
object DemoDeeplinks {
    const val KIOSK_SCHEME = "posrouter-kiosk"
    const val KIOSK_HOST_CONNECT = "connect"
    const val KIOSK_HOST_CHARGE = "charge"
    const val PAY_RESULT_HOST = "pay_result"
    const val PAY_RESULT_URI = "${DemoConfig.DEEPLINK_SCHEME}://$PAY_RESULT_HOST"

    fun buildKioskConnectUri(callbackUrl: String = DemoConfig.CALLBACK_URL): Uri =
        Uri.parse("$KIOSK_SCHEME://$KIOSK_HOST_CONNECT").buildUpon()
            .appendQueryParameter("callback_url", callbackUrl)
            .build()

    fun buildKioskChargeUri(
        orderId: String,
        amountCents: Long,
        currency: String,
        remark: String?,
        method: String = PaymentRequest.METHOD_SELECTION,
        callbackUrl: String? = null,
        partnerScheme: String = DemoConfig.DEEPLINK_SCHEME
    ): Uri = Uri.parse("$KIOSK_SCHEME://$KIOSK_HOST_CHARGE").buildUpon()
        .appendQueryParameter("amount", amountCents.toString())
        .appendQueryParameter("currency", currency)
        .appendQueryParameter("orderid", orderId)
        .appendQueryParameter("method", method)
        .appendQueryParameter("partner_scheme", partnerScheme)
        .apply {
            remark?.takeIf { it.isNotBlank() }?.let { appendQueryParameter("remark", it) }
            callbackUrl?.takeIf { it.isNotBlank() }?.let { appendQueryParameter("callback_url", it) }
        }
        .build()

    fun isPayResultCallback(uri: Uri): Boolean =
        uri.scheme.equals(DemoConfig.DEEPLINK_SCHEME, ignoreCase = true) &&
            uri.host.equals(PAY_RESULT_HOST, ignoreCase = true)

    /** Parses kiosk → demo relay without publishing to Lensing. */
    fun parsePartnerRelayResult(uri: Uri): PaymentResult? {
        val parsed = POSRouter.parseAcquirerCallback(uri) ?: return null
        val amount = uri.getQueryParameter("amount")?.toLongOrNull() ?: parsed.amount
        val currency = uri.getQueryParameter("currency")?.trim()?.takeIf { it.isNotEmpty() }
            ?: parsed.currency
        return parsed.copy(amount = amount, currency = currency)
    }
}
