package com.zandaulion.bitey

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Bitey AI's free tier: a few readings a day without a subscription.
 *
 * The server needs to know who is asking without the app having accounts,
 * so each install signs in to Firebase anonymously -- invisibly, with no
 * name, email or diary attached -- and sends the resulting ID token. The
 * server counts free readings against that identity (functions/quota.js).
 *
 * Anonymous accounts unused for 30 days are deleted by Firebase (auto
 * clean-up). An install whose account went that way simply signs in again
 * and carries on; the free allowance is per day, so nothing is lost.
 *
 * It also remembers the allowance the server last reported, so the app can
 * send someone who has used today's readings to the plans instead of letting
 * them take a photo the server will refuse. The server stays the authority:
 * this is only a guess at what it will say.
 */
class FreeTier(private val context: Context) {
    companion object {
        private const val TAG = "BiteyFreeTier"
        private const val PREFS = "bitey_free_tier"
        private const val KEY_ALLOWANCE = "allowance"
        private const val WAIT_SECONDS = 10L
    }

    /** On in builds that carry it (free_tier_enabled) and have Firebase. */
    val enabled: Boolean =
        context.resources.getBoolean(R.bool.free_tier_enabled) &&
            FirebaseApp.getApps(context).isNotEmpty()

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The install's Firebase ID token, signing in first if needed, or null.
     * Blocking: call it off the main thread.
     */
    fun idToken(): String? {
        if (!enabled) return null
        val auth = FirebaseAuth.getInstance()
        return runCatching {
            val user = auth.currentUser ?: signIn(auth)
            try {
                Tasks.await(user.getIdToken(false), WAIT_SECONDS, TimeUnit.SECONDS).token
            } catch (err: ExecutionException) {
                // The account was cleaned up, or disabled: start a new one.
                if (err.cause !is FirebaseAuthInvalidUserException) throw err
                Log.i(TAG, "anonymous account gone; signing in again")
                auth.signOut()
                Tasks.await(signIn(auth).getIdToken(false), WAIT_SECONDS, TimeUnit.SECONDS).token
            }
        }.onFailure { Log.w(TAG, "no ID token: ${it.javaClass.simpleName}") }
            .getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun signIn(auth: FirebaseAuth) =
        requireNotNull(Tasks.await(auth.signInAnonymously(), WAIT_SECONDS, TimeUnit.SECONDS).user)

    /** Keeps what the server said is left, from any answer that says. */
    fun record(answer: JSONObject) {
        val tier = answer.optString("tier")
        if (tier != "free" && tier != "paid") return
        prefs.edit().putString(KEY_ALLOWANCE, JSONObject()
            .put("tier", tier)
            .put("remaining", answer.optInt("remaining", -1))
            .put("correctionsRemaining", answer.optInt("correctionsRemaining", -1))
            .put("limit", answer.optInt("limit", -1))
            .put("resetsAt", answer.optString("resetsAt"))
            .toString()).apply()
    }

    /**
     * The last free allowance, or null once it has reset or if there is none
     * -- in which case today is assumed to be untouched.
     */
    fun allowance(): JSONObject? {
        val stored = prefs.getString(KEY_ALLOWANCE, null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        if (stored.optString("tier") != "free") return null
        val resets = runCatching { Instant.parse(stored.optString("resetsAt")) }.getOrNull() ?: return null
        return if (Instant.now().isBefore(resets)) stored else null
    }

    /** Whether a free reading of this kind is likely to be accepted. */
    fun likelyAllowed(action: String): Boolean {
        val today = allowance() ?: return true
        val left = if (action == "correction") today.optInt("correctionsRemaining", 1) else today.optInt("remaining", 1)
        return left != 0
    }
}
