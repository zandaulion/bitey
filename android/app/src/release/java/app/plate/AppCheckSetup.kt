package com.zandaulion.bitey

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/**
 * Release builds attest with Play Integrity: Google vouches that the request
 * comes from this app, as installed from Play, on a genuine device.
 *
 * The debug build has its own copy of this object under src/debug, using the
 * debug provider; keeping the two in separate source sets is what guarantees
 * a release can never fall back to accepting debug tokens.
 */
internal object AppCheckSetup {
    private const val TAG = "BiteyAppCheck"

    fun install(context: Context) {
        // FirebaseApp exists only when google-services.json was present at
        // build time. Without it the app simply sends no App Check token.
        if (FirebaseApp.getApps(context).isEmpty()) return
        val appCheck = FirebaseAppCheck.getInstance()
        appCheck.installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
        // A Play Integrity attestation takes seconds. Done when the photo is
        // sent, it sat in front of the reading -- up to the 10 s the request
        // waits for it, and a request that gave up went out without a token
        // and was refused. So the token is fetched now, at start-up, and kept
        // fresh from then on; the reading finds it already in hand. The token
        // itself is never logged.
        appCheck.setTokenAutoRefreshEnabled(true)
        appCheck.getAppCheckToken(false)
            .addOnSuccessListener { Log.i(TAG, "App Check token ready") }
            .addOnFailureListener { Log.w(TAG, "App Check token refused: ${it.message}") }
    }
}
