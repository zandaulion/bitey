package com.zandaulion.bitey

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Offers Bitey's watch app to someone whose watch does not have it yet.
 *
 * A paired watch that does not declare the watch app's capability
 * (bitey_watch, in wear/res/values/wear.xml) has no Bitey. For those only,
 * Settings and the welcome show "Install on your watch", which opens Bitey's
 * Play Store page on the watch itself; the person confirms there. Nobody
 * without a Wear OS watch sees any of it.
 *
 * Both calls block on Play services, so they run off the main thread.
 */
object WatchInstall {
    private const val CAPABILITY = "bitey_watch"
    private const val TIMEOUT_S = 10L

    /** The helper's own thread: install() blocks on its result, so they must not share one. */
    private val helperExecutor = Executors.newSingleThreadExecutor()

    /** "missing" (a watch without Bitey), "installed", or "none" (no watch, or no way to tell). */
    fun state(context: Context): String = runCatching {
        val watches = connected(context)
        when {
            watches.isEmpty() -> "none"
            withoutBitey(context, watches).isNotEmpty() -> "missing"
            else -> "installed"
        }
    }.getOrDefault("none")

    /** Opens Bitey's Play page on each watch without it: "opened" if any did, else "failed". */
    fun install(context: Context): String = runCatching {
        val targets = withoutBitey(context, connected(context))
        if (targets.isEmpty()) return@runCatching "failed"
        val helper = RemoteActivityHelper(context, helperExecutor)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${context.packageName}"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val opened = targets.count { node ->
            runCatching { helper.startRemoteActivity(intent, node.id).get(TIMEOUT_S, TimeUnit.SECONDS) }.isSuccess
        }
        if (opened > 0) "opened" else "failed"
    }.getOrDefault("failed")

    private fun connected(context: Context): List<Node> =
        Tasks.await(Wearable.getNodeClient(context).connectedNodes, TIMEOUT_S, TimeUnit.SECONDS)

    private fun withoutBitey(context: Context, watches: List<Node>): List<Node> {
        val withBitey = Tasks.await(
            Wearable.getCapabilityClient(context).getCapability(CAPABILITY, CapabilityClient.FILTER_ALL),
            TIMEOUT_S, TimeUnit.SECONDS,
        ).nodes.map { it.id }.toSet()
        return watches.filter { it.id !in withBitey }
    }
}
