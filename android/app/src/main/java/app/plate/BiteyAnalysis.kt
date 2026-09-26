package com.zandaulion.bitey

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * The client half of Bitey AI's photo reading.
 *
 * It exists in native code, not in the page, for one reason: the request must
 * carry the Play purchase token, and that token is a bearer credential for a
 * paid feature. The page supplies the photograph; this adds the token and
 * sends both to the analysis function, which verifies the purchase with Google
 * Play before the photograph reaches the model.
 *
 * Nothing else is sent. The body is rebuilt here from an allow-list rather
 * than forwarded, so a field added to the page's request -- by a bug or by
 * anything else -- cannot quietly start leaving the device.
 */
class BiteyAnalysis(
    private val appContext: Context,
    private val endpoint: String = ANALYSE_URL,
) {
    companion object {
        const val ANALYSE_URL = "https://europe-west1-plate-cc703.cloudfunctions.net/analyse"

        /** Just above the server's own ceiling, so an oversized photograph is
         * refused here without being uploaded to be refused there. */
        const val MAX_IMAGE_CHARS = 7 * 1024 * 1024

        private val LOCALE = Regex("[a-z]{2}(-[A-Za-z]{2})?")
    }

    /**
     * Sends one photograph and returns `{"httpStatus": n, "body": {...}}`.
     *
     * Always answers, including when the network is down: the page is waiting
     * on this and an exception would leave its promise pending forever, which
     * is the same silent failure the photo button suffered from before.
     */
    fun analyse(purchaseToken: String?, pageRequest: String): String {
        if (purchaseToken.isNullOrBlank()) {
            return answer(403, "not_entitled", "Bitey AI is not active on this Google Play account.")
        }
        val request = runCatching { JSONObject(pageRequest) }.getOrNull()
            ?: return answer(400, "no_image", "No photo was received.")

        val image = request.optString("image")
        if (image.length < 100) return answer(400, "no_image", "No photo was received.")
        if (image.length > MAX_IMAGE_CHARS) {
            return answer(400, "image_too_large", "That photo is too large to read.")
        }

        val body = JSONObject()
            .put("purchaseToken", purchaseToken)
            .put("image", image)
            .put("mimeType", if (request.optString("mimeType") == "image/png") "image/png" else "image/jpeg")
            .apply {
                request.optString("correction").trim().takeIf { it.isNotEmpty() }
                    ?.let { put("correction", it.take(200)) }
                request.optString("locale").takeIf { LOCALE.matches(it) }
                    ?.let { put("locale", it) }
            }
            .toString()

        val connection = try {
            (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                // The model can take most of a minute on a busy plate. The
                // server allows ninety seconds; this waits a little longer so
                // the server's own timeout, with its refund, is what fires.
                readTimeout = 95_000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                appCheckToken()?.let { setRequestProperty("X-Firebase-AppCheck", it) }
                // Identifies the software, never the person or the device.
                setRequestProperty("User-Agent", "Bitey-Android (photo analysis)")
            }
        } catch (_: IOException) {
            return unreachable()
        }

        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val parsed = runCatching { JSONObject(text) }.getOrNull()
                // A proxy or Google's front end can answer with HTML. The page
                // needs a shape it understands, not the page source.
                ?: JSONObject()
                    .put("error", if (status in 200..299) "unreadable" else "upstream_error")
                    .put("message", "The analysis could not be read. Try again.")
            JSONObject().put("httpStatus", status).put("body", parsed).toString()
        } catch (_: IOException) {
            unreachable()
        } finally {
            connection.disconnect()
        }
    }

    /**
     * An App Check token for the request, or null.
     *
     * Null is an answer, not an error: a build without google-services.json,
     * a device Play Integrity cannot attest, or a slow attestation all send
     * the request without one. While the server does not enforce App Check
     * that still works; once it does, the server refuses it, and says so.
     * Blocking is fine here -- this always runs on the analysis thread.
     */
    private fun appCheckToken(): String? = runCatching {
        if (FirebaseApp.getApps(appContext).isEmpty()) return null
        Tasks.await(FirebaseAppCheck.getInstance().getAppCheckToken(false), 10, TimeUnit.SECONDS).token
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun unreachable() = answer(
        0, "network", "Could not reach Bitey AI. Check the connection and try again.",
    )

    private fun answer(status: Int, code: String, message: String): String = JSONObject()
        .put("httpStatus", status)
        .put("body", JSONObject().put("error", code).put("message", message))
        .toString()
}
