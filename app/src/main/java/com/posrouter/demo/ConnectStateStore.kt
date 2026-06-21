package com.posrouter.demo

import android.content.Context

object ConnectStateStore {
    private const val PREFS = "posrouter_demo_prefs"
    private const val KEY_EZYPOS_CONNECTED = "ezypos_connect_confirmed"

    fun isEzyposConnected(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_EZYPOS_CONNECTED, false)

    fun setEzyposConnected(context: Context, connected: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_EZYPOS_CONNECTED, connected)
            .apply()
    }
}
