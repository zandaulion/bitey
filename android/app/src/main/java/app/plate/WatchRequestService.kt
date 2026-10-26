package com.zandaulion.bitey

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Answers the watch: log a quick bite or repeat meal, take one back, or send
 * today again. Play services starts this when a request arrives, so it works
 * whether or not Bitey is open; the work is in [WatchSync].
 *
 * Requests are only accepted from Bitey's own watch app: the Data Layer
 * delivers between apps with the same package name and signing key, and the
 * manifest filter narrows it to /bitey/ paths.
 */
class WatchRequestService : WearableListenerService() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onRequest(nodeId: String, path: String, request: ByteArray): Task<ByteArray>? {
        if (!path.startsWith("/bitey/")) return null
        val result = TaskCompletionSource<ByteArray>()
        executor.execute {
            val body = runCatching { JSONObject(String(request, Charsets.UTF_8)) }.getOrElse { JSONObject() }
            val reply = runCatching {
                when (path) {
                    WatchSync.PATH_LOG -> WatchSync.log(applicationContext, body.optString("key"), body.optString("request"))
                    WatchSync.PATH_UNDO -> WatchSync.undo(applicationContext, body.optString("id"))
                    WatchSync.PATH_REFRESH -> WatchSync.refresh(applicationContext)
                    else -> JSONObject().put("ok", false).put("code", "unknown")
                }
            }.getOrElse { JSONObject().put("ok", false).put("code", "storage_error") }
            result.setResult(reply.toString().toByteArray(Charsets.UTF_8))
        }
        return result.task
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
