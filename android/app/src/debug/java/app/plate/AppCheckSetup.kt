package com.zandaulion.bitey

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug builds cannot pass Play Integrity -- they are sideloaded and signed
 * with the debug key -- so they use App Check's debug provider. The first time
 * a token is requested it logs a debug secret (tag DebugAppCheckProvider),
 * which must be added to App Check -> Apps -> Bitey -> Manage debug tokens in
 * the Firebase console. Treat that secret like a password: whoever holds it
 * can pass App Check.
 *
 * Release builds have their own copy of this object under src/release.
 */
internal object AppCheckSetup {
    private const val TAG = "BiteyAppCheck"

    fun install(context: Context) {
        if (FirebaseApp.getApps(context).isEmpty()) {
            Log.w(TAG, "Firebase is not configured in this build: no App Check token will be sent")
            return
        }
        val appCheck = FirebaseAppCheck.getInstance()
        appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
        // Asked for once at start-up, so a debug build shows straight away
        // whether it can obtain a token -- and so the debug secret is logged
        // before anyone needs it, rather than on the first paid reading. The
        // token itself is never logged.
        appCheck.getAppCheckToken(false)
            .addOnSuccessListener { Log.i(TAG, "App Check token obtained") }
            .addOnFailureListener { Log.w(TAG, "App Check token refused: ${it.message}") }
    }
}
