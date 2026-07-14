package com.posrouter.demo

import android.net.Uri
import com.posrouter.POSRouter
import com.posrouter.PaymentRequest
import com.posrouter.PaymentResult

/**
 * Demo POS deeplinks — same partner role as GoMenu, with demo's own scheme.
 *
 * - [buildKioskChargeUri] — per payment; include `callback_url` (no prior connect required)
 * - [buildKioskConnectUri] — optional: register partner bucket / `kiosk_lock=0`
 * - Receive kiosk relay + Ezypos callbacks: [PAY_RESULT_URI]
 *
 * Prefer [RoutePreference.LOCAL_POSROUTER_KIOSK] via `POSRouter.pay` for partner apps;
 * deeplink / explicit Intent are also demonstrated in [MainActivity].
 */
object DemoDeeplinks {
    const val KIOSK_SCHEME = "posrouter-kiosk"
    const val KIOSK_HOST_CONNECT = "connect"
    const val KIOSK_HOST_CHARGE = "charge"
    const val PAY_RESULT_HOST = "pay_result"
    const val PAY_RESULT_URI = "${DemoConfig.DEEPLINK_SCHEME}://$PAY_RESULT_HOST"

    fun buildKioskConnectUri(
        callbackUrl: String = DemoConfig.CALLBACK_URL,
        notifyConnect: Boolean = false,
        /** `false` for same-device companion POS (GoMenu, demo) — kiosk stays unpinned. */
        kioskLock: Boolean = false
    ): Uri = Uri.parse("$KIOSK_SCHEME://$KIOSK_HOST_CONNECT").buildUpon()
        .appendQueryParameter("callback_url", callbackUrl)
        .appendQueryParameter("kiosk_lock", if (kioskLock) "1" else "0")
        .apply {
            if (!notifyConnect) appendQueryParameter("notify", "0")
        }
        .build()

    fun buildKioskChargeUri(
        orderId: String,
        amountCents: Long,
        currency: String,
        remark: String?,
        method: String = PaymentRequest.METHOD_SELECTION,
        callbackUrl: String = DemoConfig.CALLBACK_URL,
        partnerScheme: String = DemoConfig.DEEPLINK_SCHEME
    ): Uri = Uri.parse("$KIOSK_SCHEME://$KIOSK_HOST_CHARGE").buildUpon()
        .appendQueryParameter("amount", amountCents.toString())
        .appendQueryParameter("currency", currency)
        .appendQueryParameter("orderid", orderId)
        .appendQueryParameter("method", method)
        .appendQueryParameter("partner_scheme", partnerScheme)
        .appendQueryParameter("callback_url", callbackUrl)
        .apply {
            remark?.takeIf { it.isNotBlank() }?.let { appendQueryParameter("remark", it) }
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
