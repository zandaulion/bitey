package com.zandaulion.bitey

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Bitey Beta's App Check, the same as the debug build's: the beta is
 * sideloaded and signed with the sideload key, so it cannot pass Play
 * Integrity and uses App Check's debug provider. The first time a token is
 * requested it logs a debug secret (tag DebugAppCheckProvider), which must be
 * added to App Check -> Apps -> Bitey -> Manage debug tokens in the Firebase
 * console. Treat that secret like a password: whoever holds it can pass App
 * Check.
 *
 * When the build carries a secret (beta_app_check_debug_secret, from
 * android/beta.properties), it is written where the debug provider keeps its
 * own before the provider starts, so the token is the one already registered
 * and nothing needs reading from logcat. The library looks for it in
 * "com.google.firebase.appcheck.debug.store.<persistence key>" under
 * "com.google.firebase.appcheck.debug.DEBUG_SECRET" (its StorageHelper,
 * firebase-appcheck-debug 19.4.1); if a later version moves it, the provider
 * simply makes up a new secret and logs it as before.
 *
 * The beta's own function (functions/beta.js) is the only server it talks to.
 */
internal object AppCheckSetup {
    private const val TAG = "BiteyAppCheck"

    fun install(context: Context) {
        if (FirebaseApp.getApps(context).isEmpty()) {
            Log.w(TAG, "Firebase is not configured in this build: no App Check token will be sent")
            return
        }
        val secret = context.getString(R.string.beta_app_check_debug_secret)
        if (secret.isNotBlank()) {
            val store = "com.google.firebase.appcheck.debug.store.${FirebaseApp.getInstance().persistenceKey}"
            context.getSharedPreferences(store, Context.MODE_PRIVATE).edit()
                .putString("com.google.firebase.appcheck.debug.DEBUG_SECRET", secret)
                .commit()
        }
        val appCheck = FirebaseAppCheck.getInstance()
        appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
        // As in release: kept fresh, so a reading never waits for a token.
        appCheck.setTokenAutoRefreshEnabled(true)
        // Asked for once at start-up, so a debug build shows straight away
        // whether it can obtain a token -- and so the debug secret is logged
        // before anyone needs it, rather than on the first paid reading. The
        // token itself is never logged.
        appCheck.getAppCheckToken(false)
            .addOnSuccessListener { Log.i(TAG, "App Check token obtained") }
            .addOnFailureListener { Log.w(TAG, "App Check token refused: ${it.message}") }
    }
}
