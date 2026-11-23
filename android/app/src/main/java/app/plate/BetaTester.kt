package com.zandaulion.bitey

import android.content.Context

/**
 * Bitey Beta's tester mode: test as a subscriber (the default) or as a free
 * user. Only the beta build carries a tester token; in every other build the
 * token is empty and this is always off.
 *
 * Shared by the activity and the background reading worker, so both present
 * the same credentials.
 */
object BetaTester {
    private const val PREFS = "bitey_beta"
    private const val KEY_TESTER_MODE = "tester_mode"

    fun token(context: Context): String = context.getString(R.string.beta_tester_token)

    fun available(context: Context): Boolean = token(context).isNotBlank()

    fun on(context: Context): Boolean =
        available(context) && prefs(context).getBoolean(KEY_TESTER_MODE, true)

    /** The tester token while tester mode is on, else null. */
    fun activeToken(context: Context): String? = token(context).takeIf { on(context) }

    fun toggle(context: Context) {
        prefs(context).edit().putBoolean(KEY_TESTER_MODE, !on(context)).commit()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
