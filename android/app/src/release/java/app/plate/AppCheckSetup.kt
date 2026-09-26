package com.zandaulion.bitey

import android.content.Context
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
    fun install(context: Context) {
        // FirebaseApp exists only when google-services.json was present at
        // build time. Without it the app simply sends no App Check token.
        if (FirebaseApp.getApps(context).isEmpty()) return
        FirebaseAppCheck.getInstance()
            .installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
    }
}
