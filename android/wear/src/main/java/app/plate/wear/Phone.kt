package com.zandaulion.bitey.wear

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** What came back from asking the phone to log or take back an entry. */
sealed interface PhoneReply {
    data class Logged(val id: String, val label: String, val kcal: Int) : PhoneReply
    data object Done : PhoneReply
    /** No phone with Bitey in reach, or it did not answer in time. */
    data object Unreachable : PhoneReply
    /** The phone answered, and could not do it. */
    data object Failed : PhoneReply
}

/**
 * Requests to Bitey on the phone (WatchRequestService there). The phone is
 * found by the capability its app declares, so a second paired phone without
 * Bitey is never asked.
 */
object Phone {
    private const val CAPABILITY = "bitey_phone"
    private const val TIMEOUT_MS = 10_000L

    suspend fun log(context: Context, key: String): PhoneReply {
        val reply = request(context, "/bitey/log", JSONObject().put("key", key)) ?: return PhoneReply.Unreachable
        if (!reply.optBoolean("ok")) return PhoneReply.Failed
        return PhoneReply.Logged(reply.optString("id"), reply.optString("label"), reply.optInt("kcal"))
    }

    suspend fun undo(context: Context, id: String): PhoneReply {
        val reply = request(context, "/bitey/undo", JSONObject().put("id", id)) ?: return PhoneReply.Unreachable
        return if (reply.optBoolean("ok")) PhoneReply.Done else PhoneReply.Failed
    }

    /** Asks the phone to send today again; it arrives through TodayListenerService. */
    suspend fun refresh(context: Context): Boolean =
        request(context, "/bitey/refresh", JSONObject())?.optBoolean("ok") == true

    private suspend fun request(context: Context, path: String, body: JSONObject): JSONObject? =
        withTimeoutOrNull(TIMEOUT_MS) {
            runCatching {
                val nodes = Wearable.getCapabilityClient(context)
                    .getCapability(CAPABILITY, CapabilityClient.FILTER_REACHABLE).await().nodes
                val node = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull() ?: return@runCatching null
                val bytes = Wearable.getMessageClient(context)
                    .sendRequest(node.id, path, body.toString().toByteArray(Charsets.UTF_8)).await()
                JSONObject(String(bytes, Charsets.UTF_8))
            }.getOrNull()
        }
}
