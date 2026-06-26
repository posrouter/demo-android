package com.posrouter.demo

import android.content.Context
import com.posrouter.LocalParamSeparator
import com.posrouter.POSRouterConfig
import com.posrouter.PaymentRequest

object DemoConfig {
    /** A-side ordering identity for Gateway `/init` (NATS auth). Not the same as [ACQUIRER_CODE]. */
    const val PARTICIPANT_CODE = "GPOS"
    const val ACQUIRER_CODE = "SUPY"
    const val ACQUIRER_PACKAGE = "ezypay.com.globe.cardpos"
    const val CALLBACK_URL = "gomenu://pay_result"
    const val CURRENCY = "NZD"
    const val PAY_METHOD_CARD = "emv_card"
    const val PAY_METHOD_QR = "show_qr_code"
    const val PAY_METHOD_SKYZER = PaymentRequest.METHOD_SKYZER

    fun routerConfig(context: Context): POSRouterConfig =
        routerConfig(
            terminalId = ConnectStateStore.getTerminalId(context),
            merchantId = ConnectStateStore.getMerchantId(context)
        )

    fun routerConfig(terminalId: String, merchantId: String) = POSRouterConfig(
        participantCode = PARTICIPANT_CODE,
        participantKey = BuildConfig.PARTICIPANT_KEY,
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
