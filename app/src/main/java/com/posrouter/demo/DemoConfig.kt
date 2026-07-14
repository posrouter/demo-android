package com.posrouter.demo

import android.content.Context
import com.posrouter.LocalParamSeparator
import com.posrouter.POSRouterConfig
import com.posrouter.PaymentRequest

object DemoConfig {
    /** A-side ordering identity for Gateway `/init` (NATS auth). Loaded from `local.properties`. */
    fun participantCode(): String = BuildConfig.PARTICIPANT_CODE.trim().ifBlank { "GPOS" }

    fun participantKey(): String = BuildConfig.PARTICIPANT_KEY
    const val ACQUIRER_CODE = "SUPY"
    const val ACQUIRER_PACKAGE = "ezypay.com.globe.cardpos"
    const val DEEPLINK_SCHEME = "posrouter-pos-demo-android"
    const val CALLBACK_URL = "$DEEPLINK_SCHEME://pay_result"
    const val CURRENCY = "NZD"
    const val PAY_METHOD_CARD = "emv_card"
    const val PAY_METHOD_QR = "show_qr_code"
    const val PAY_METHOD_SKYZER = PaymentRequest.METHOD_SKYZER
    const val PAY_METHOD_SELECTION = PaymentRequest.METHOD_SELECTION

    /** Same-device POSRouter Kiosk (B-side). Used by Local Kiosk (Intent). */
    const val KIOSK_PACKAGE = "com.posrouter.kiosk"
    const val KIOSK_ACTIVITY = "com.posrouter.kiosk.KioskActivity"

    fun routerConfig(context: Context): POSRouterConfig =
        routerConfig(
            terminalId = ConnectStateStore.getTerminalId(context),
            merchantId = ConnectStateStore.getMerchantId(context)
        )

    fun routerConfig(terminalId: String, merchantId: String) = POSRouterConfig(
        participantCode = participantCode(),
        participantKey = participantKey(),
        terminalId = terminalId,
        acquirerCode = ACQUIRER_CODE,
        merchantId = merchantId,
        callbackUrl = CALLBACK_URL,
        currency = CURRENCY,
        acquirerPackageOverride = ACQUIRER_PACKAGE,
        localParamSeparator = LocalParamSeparator.AMPERSAND,
        gatewayBaseUrl = BuildConfig.GATEWAY_BASE_URL.takeIf { it.isNotBlank() }
    )
}
