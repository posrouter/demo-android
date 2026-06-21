package com.posrouter.demo

import com.posrouter.LocalParamSeparator
import com.posrouter.POSRouterConfig

object DemoConfig {
    const val PARTICIPANT_CODE = "GPOS"
    const val ACQUIRER_CODE = "SUPY"
    const val ACQUIRER_PACKAGE = "ezypay.com.globe.cardpos"
    const val CALLBACK_URL = "gomenu://pay_result"
    const val CURRENCY = "NZD"
    const val PAY_METHOD_CARD = "emv_card"
    const val PAY_METHOD_QR = "show_qr_code"

    fun routerConfig() = POSRouterConfig(
        participantCode = PARTICIPANT_CODE,
        participantKey = BuildConfig.PARTICIPANT_KEY,
        terminalId = BuildConfig.TERMINAL_ID,
        acquirerCode = ACQUIRER_CODE,
        merchantId = BuildConfig.EZYPOS_MID,
        callbackUrl = CALLBACK_URL,
        currency = CURRENCY,
        acquirerPackageOverride = ACQUIRER_PACKAGE,
        localParamSeparator = LocalParamSeparator.AMPERSAND
    )
}
