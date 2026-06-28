package com.posrouter.demo

import android.content.Context
import com.posrouter.RoutePreference

object ConnectStateStore {
    private const val PREFS = "posrouter_demo_prefs"
    private const val KEY_EZYPOS_CONNECTED = "ezypos_connect_confirmed"
    private const val KEY_TERMINAL_ID = "terminal_id"
    private const val KEY_MERCHANT_ID = "merchant_id"
    private const val KEY_ROUTE_PREFERENCE = "route_preference"

    fun isEzyposConnected(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EZYPOS_CONNECTED, false)

    fun setEzyposConnected(context: Context, connected: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_EZYPOS_CONNECTED, connected)
            .apply()
    }

    fun getTerminalId(context: Context): String =
        prefs(context).getString(KEY_TERMINAL_ID, BuildConfig.TERMINAL_ID)
            ?.takeIf { it.isNotBlank() }
            ?: BuildConfig.TERMINAL_ID

    fun getMerchantId(context: Context): String =
        prefs(context).getString(KEY_MERCHANT_ID, BuildConfig.EZYPOS_MID).orEmpty()

    fun getRoutePreference(context: Context): String =
        prefs(context).getString(KEY_ROUTE_PREFERENCE, RoutePreference.AUTO)
            ?: RoutePreference.AUTO

    fun saveConnectSettings(
        context: Context,
        terminalId: String,
        merchantId: String,
        routePreference: String
    ): Boolean =
        prefs(context).edit()
            .putString(KEY_TERMINAL_ID, terminalId)
            .putString(KEY_MERCHANT_ID, merchantId)
            .putString(KEY_ROUTE_PREFERENCE, routePreference)
            .commit()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
