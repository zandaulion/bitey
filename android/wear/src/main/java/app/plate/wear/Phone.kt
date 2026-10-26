package com.zandaulion.bitey.wear

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID

/** What came back from asking the phone to log or take back an entry. */
sealed interface PhoneReply {
    data class Logged(val id: String, val label: String, val kcal: Int) : PhoneReply
    data object Done : PhoneReply
    /** No phone with Bitey in reach: nothing was sent. */
    data object Unreachable : PhoneReply
    /** Sent, but no answer came back, so it may or may not have been logged. */
    data object Unconfirmed : PhoneReply
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
    private const val FIND_TIMEOUT_MS = 5_000L
    private const val ANSWER_TIMEOUT_MS = 8_000L

    /** How a request went: answered, never sent, or sent with no answer. */
    private sealed interface Outcome {
        data class Answered(val body: JSONObject) : Outcome
        data object NoPhone : Outcome
        data object NoAnswer : Outcome
    }

    /**
     * Logs a choice. A tap has one id; if the answer is lost, the request is
     * sent once more with the same id, and the phone answers it from memory
     * rather than logging the choice a second time. Testing on emulators,
     * a dropped link delivered the request and lost the reply, which, shown
     * as "can't reach your phone", invites a second tap and a double entry.
     */
    suspend fun log(context: Context, key: String): PhoneReply {
        val body = JSONObject().put("key", key).put("request", UUID.randomUUID().toString())
        var outcome = request(context, "/bitey/log", body)
        if (outcome == Outcome.NoAnswer) {
            delay(1_000)
            outcome = request(context, "/bitey/log", body)
        }
        return when (outcome) {
            is Outcome.Answered ->
                if (!outcome.body.optBoolean("ok")) PhoneReply.Failed
                else PhoneReply.Logged(outcome.body.optString("id"), outcome.body.optString("label"), outcome.body.optInt("kcal"))
            Outcome.NoPhone -> PhoneReply.Unreachable
            Outcome.NoAnswer -> PhoneReply.Unconfirmed
        }
    }

    suspend fun undo(context: Context, id: String): PhoneReply =
        when (val outcome = request(context, "/bitey/undo", JSONObject().put("id", id))) {
            is Outcome.Answered -> if (outcome.body.optBoolean("ok")) PhoneReply.Done else PhoneReply.Failed
            Outcome.NoPhone -> PhoneReply.Unreachable
            Outcome.NoAnswer -> PhoneReply.Unconfirmed
        }

    /** Asks the phone to send today again; it arrives through TodayListenerService. */
    suspend fun refresh(context: Context): Boolean =
        (request(context, "/bitey/refresh", JSONObject()) as? Outcome.Answered)?.body?.optBoolean("ok") == true

    private suspend fun request(context: Context, path: String, body: JSONObject): Outcome {
        val node = withTimeoutOrNull(FIND_TIMEOUT_MS) {
            runCatching {
                val nodes = Wearable.getCapabilityClient(context)
                    .getCapability(CAPABILITY, CapabilityClient.FILTER_REACHABLE).await().nodes
                nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
            }.getOrNull()
        } ?: return Outcome.NoPhone
        val answer = withTimeoutOrNull(ANSWER_TIMEOUT_MS) {
            runCatching {
                val bytes = Wearable.getMessageClient(context)
                    .sendRequest(node.id, path, body.toString().toByteArray(Charsets.UTF_8)).await()
                JSONObject(String(bytes, Charsets.UTF_8))
            }.getOrNull()
        }
        return answer?.let { Outcome.Answered(it) } ?: Outcome.NoAnswer
    }
}
