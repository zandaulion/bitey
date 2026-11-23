package com.zandaulion.bitey

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Bitey AI readings that carry on without the app.
 *
 * The page hands a photo (or a typed meal) over here and closes its sheet.
 * The reading is written down first -- the photo in app-private storage, a
 * row in Room -- and then sent by WorkManager, which waits for a network,
 * retries what can be retried, and survives the app being closed, killed or
 * the phone restarting. When the answer comes, the meal is saved to the
 * diary straight away, marked as not checked, and a notification says so.
 *
 * What leaves the phone is exactly what left it before: the same request,
 * just sent later. Nothing here is diary data until the entry is saved.
 */
object PendingReadings {
    private const val TAG = "BiteyReadings"
    const val CHANNEL = "meal_readings"
    private const val NOTIFICATION_LOGGED = 4101
    private const val NOTIFICATION_NEEDS_YOU = 4102
    private const val PREFS = "bitey_readings"
    private const val KEY_UNSEEN = "unseen_logged"
    const val EXTRA_OPEN_ENTRY = "com.zandaulion.bitey.OPEN_ENTRY"
    const val EXTRA_OPEN_DAY = "com.zandaulion.bitey.OPEN_DAY"

    /** A reading unanswered this long stops retrying and asks the person. */
    private const val GIVE_UP_MS = 24 * 60 * 60 * 1000L

    /** One that has been "sending" this long was interrupted. */
    private const val STALE_SENDING_MS = 2 * 60 * 1000L

    /** After this long, a reading nobody acted on is removed with its photo. */
    private const val FORGET_MS = 7 * 24 * 60 * 60 * 1000L

    private val ID = Regex("[A-Za-z0-9_-]{8,80}")
    private val lock = Any()

    /** Set by the activity while it is alive: something changed, redraw. */
    @Volatile
    var onChanged: (() -> Unit)? = null

    /** Set by the activity: a reading was just handed over (to ask, once,
     * whether notifications may say when it is done). */
    @Volatile
    var onQueued: (() -> Unit)? = null

    private fun dao(context: Context) = PlateDatabase.get(context).pendingReadings()
    private fun directory(context: Context) = File(context.filesDir, "pending").apply { mkdirs() }

    /**
     * Writes the reading down and schedules it. Returns its id, or null if it
     * could not be stored (then the page falls back to reading it while open).
     *
     * [request] is the page's request; its image, if any, is taken out and
     * kept as a file. [template] is the entry the page prepared, without its
     * foods: day, meal, photo type.
     */
    fun enqueue(context: Context, request: String, template: String): String? = runCatching {
        val body = JSONObject(request)
        val entry = JSONObject(template)
        val id = UUID.randomUUID().toString()
        val image = body.optString("image")
        var photoFile: String? = null
        if (image.length >= 100) {
            val bytes = Base64.decode(image, Base64.DEFAULT)
            require(bytes.isNotEmpty() && bytes.size <= MAX_PHOTO_BYTES)
            val name = "$id.${if (body.optString("mimeType") == "image/png") "png" else "jpg"}"
            File(directory(context), name).writeBytes(bytes)
            photoFile = name
        }
        body.remove("image")
        val now = System.currentTimeMillis()
        dao(context).save(PendingReadingEntity(
            id = id, createdAt = now,
            kind = if (body.optString("mode") == "text") "text" else "photo",
            requestJson = body.toString(), templateJson = entry.toString(), photoFile = photoFile,
            state = "queued", attempts = 0, lastError = null, lastMessage = null, updatedAt = now,
        ))
        schedule(context, id)
        onQueued?.invoke()
        onChanged?.invoke()
        id
    }.onFailure { Log.w(TAG, "could not queue a reading: ${it.javaClass.simpleName}") }.getOrNull()

    private fun schedule(context: Context, id: String, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
        val work = OneTimeWorkRequestBuilder<ReadingWorker>()
            .setInputData(workDataOf("id" to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            // Linear, not exponential: the usual failure is a connection that
            // has only just come back, and doubling turned three early
            // misses into a four-minute wait. 20 s, 40 s, 60 s... instead.
            .setBackoffCriteria(BackoffPolicy.LINEAR, 20, TimeUnit.SECONDS)
            .apply {
                // Expedited: starts within seconds even after the app is left.
                // Before Android 12 an expedited job runs as a foreground
                // service, which needs a notification of its own; ordinary
                // work starts soon enough there.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
            }
            .addTag("reading")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("reading-$id", policy, work)
    }

    /**
     * Run when Bitey opens or comes back: anything still waiting is scheduled
     * again (unique work, so nothing doubles), an interrupted send is retried,
     * and readings left for a week are forgotten with their photos.
     */
    fun sweep(context: Context) {
        Thread {
            runCatching {
                val now = System.currentTimeMillis()
                for (reading in dao(context).all()) {
                    when {
                        reading.state in setOf("needs_you", "failed") && now - reading.updatedAt > FORGET_MS ->
                            forget(context, reading)
                        reading.state == "sending" && now - reading.updatedAt > STALE_SENDING_MS -> {
                            dao(context).save(reading.copy(state = "queued", updatedAt = now))
                            schedule(context, reading.id, ExistingWorkPolicy.REPLACE)
                        }
                        reading.state in setOf("queued", "waiting_network", "retrying") ->
                            schedule(context, reading.id)
                    }
                }
            }.onFailure { Log.w(TAG, "sweep failed: ${it.javaClass.simpleName}") }
        }.start()
    }

    /** The readings in progress or needing the person, for the page. */
    fun listJson(context: Context): String = JSONArray().apply {
        for (reading in dao(context).all()) put(JSONObject()
            .put("id", reading.id)
            .put("kind", reading.kind)
            .put("state", reading.state)
            .put("createdAt", Instant.ofEpochMilli(reading.createdAt).toString())
            .put("day", runCatching { JSONObject(reading.templateJson).optString("day") }.getOrDefault(""))
            .put("description", runCatching { JSONObject(reading.requestJson).optString("description") }.getOrDefault(""))
            .put("photo", reading.photoFile != null)
            .put("error", reading.lastError ?: JSONObject.NULL)
            .put("message", reading.lastMessage ?: JSONObject.NULL))
    }.toString()

    /** The pending photo's bytes, for the page's thumbnail. */
    fun photoFile(context: Context, id: String): File? {
        if (!ID.matches(id)) return null
        val name = dao(context).get(id)?.photoFile ?: return null
        return File(directory(context), name).takeIf { it.isFile }
    }

    /** "Try again" on a reading that gave up or needed the person. */
    fun retry(context: Context, id: String) {
        if (!ID.matches(id)) return
        val reading = dao(context).get(id) ?: return
        dao(context).save(reading.copy(
            state = "queued", attempts = 0, lastError = null, lastMessage = null,
            createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
        ))
        schedule(context, id, ExistingWorkPolicy.REPLACE)
        onChanged?.invoke()
    }

    fun discard(context: Context, id: String) {
        if (!ID.matches(id)) return
        WorkManager.getInstance(context).cancelUniqueWork("reading-$id")
        dao(context).get(id)?.let { forget(context, it) }
        onChanged?.invoke()
    }

    private fun forget(context: Context, reading: PendingReadingEntity) {
        reading.photoFile?.let { File(directory(context), it).delete() }
        dao(context).delete(reading.id)
    }

    /**
     * Sends one reading and settles it. Called by [ReadingWorker] on its own
     * thread; blocking throughout.
     */
    internal fun run(context: Context, id: String): Outcome {
        val reading = synchronized(lock) {
            val found = dao(context).get(id) ?: return Outcome.DONE
            if (found.state in setOf("needs_you", "failed")) return Outcome.DONE
            found.copy(state = "sending", attempts = found.attempts + 1, updatedAt = System.currentTimeMillis())
                .also { dao(context).save(it) }
        }
        onChanged?.invoke()

        val request = JSONObject(reading.requestJson).put("requestId", reading.id)
        reading.photoFile?.let { name ->
            val file = File(directory(context), name)
            if (!file.isFile) return settle(context, reading, "failed", "photo_gone", null)
            request.put("image", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
        }

        // Android may have started this process for the worker alone, with
        // no activity -- and the activity is what installs App Check.
        ensureAppCheck(context)
        val purchaseToken = purchaseToken(context)
        val idToken = FreeTier(context).idToken()
        val result = BiteyAnalysis(context, context.getString(R.string.analyse_url))
            .analyse(purchaseToken, idToken, request.toString())
        val parsed = runCatching { JSONObject(result) }.getOrNull() ?: JSONObject()
        val status = parsed.optInt("httpStatus")
        val answer = parsed.optJSONObject("body") ?: JSONObject()
        FreeTier(context).record(answer)
        val code = answer.optString("error").takeIf { it.isNotEmpty() }
        val message = answer.optString("message").takeIf { it.isNotEmpty() }
        Log.i(TAG, "reading answered $status${code?.let { " ($it)" } ?: ""} on attempt ${reading.attempts}")

        val estimate = answer.optJSONObject("estimate")
        if (status in 200..299 && estimate != null) {
            return if (saveEntry(context, reading, estimate, answer.optJSONObject("totals"))) Outcome.DONE
            else settle(context, reading, "failed", "save_failed", null)
        }

        // Worth another try: no network, the server or Google busy, the app
        // not verified this once. Everything else is an answer to give.
        val transient = status == 0 || status >= 500 ||
            code in setOf("rate_limited", "upstream_unreachable", "app_check_unavailable", "no_app_check", "bad_id_token")
        if (transient) {
            if (System.currentTimeMillis() - reading.createdAt > GIVE_UP_MS) {
                return settle(context, reading, "failed", code ?: "network", message)
            }
            synchronized(lock) {
                dao(context).save(reading.copy(
                    // No token for App Check is, in practice, a connection that
                    // is not usable yet -- flight mode just switched off --
                    // rather than a busy server, and is told as such.
                    state = if (status == 0 || code == "app_check_unavailable") "waiting_network" else "retrying",
                    lastError = code, lastMessage = message, updatedAt = System.currentTimeMillis(),
                ))
            }
            onChanged?.invoke()
            return Outcome.RETRY
        }
        return settle(context, reading, "needs_you", code ?: "analysis_failed", message)
    }

    enum class Outcome { DONE, RETRY }

    @Volatile
    private var appCheckInstalled = false

    private fun ensureAppCheck(context: Context) {
        if (appCheckInstalled) return
        synchronized(lock) {
            if (!appCheckInstalled) {
                AppCheckSetup.install(context)
                appCheckInstalled = true
            }
        }
    }

    private fun settle(
        context: Context, reading: PendingReadingEntity, state: String, code: String, message: String?,
    ): Outcome {
        synchronized(lock) {
            dao(context).save(reading.copy(
                state = state, lastError = code, lastMessage = message, updatedAt = System.currentTimeMillis(),
            ))
        }
        notifyNeedsYou(context, state)
        onChanged?.invoke()
        return Outcome.DONE
    }

    /**
     * The purchase token, as the activity would send it: the beta's tester
     * token when that is on, else Play's, asked for briefly. Null without a
     * subscription, which is what the free tier expects.
     */
    private fun purchaseToken(context: Context): String? {
        BetaTester.activeToken(context)?.let { return it }
        val billing = PlayBilling(context)
        return try {
            billing.refreshIfEngaged()
            billing.awaitPurchaseToken(5_000)
        } finally {
            billing.close()
        }
    }

    /**
     * The reading becomes a diary entry: the page's template, the server's
     * foods and totals, and the photo moved into the diary's own store. It is
     * marked as not checked until the person confirms or edits it.
     */
    private fun saveEntry(context: Context, reading: PendingReadingEntity, estimate: JSONObject, totals: JSONObject?): Boolean {
        val items = estimate.optJSONArray("items") ?: return false
        if (items.length() == 0 || totals == null) return false
        val template = JSONObject(reading.templateJson)
        val day = template.optString("day").takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) } ?: return false
        val now = System.currentTimeMillis()
        val entryId = UUID.randomUUID().toString()
        val photoFile = reading.photoFile?.let { File(directory(context), it) }?.takeIf { it.isFile }
        val mimeType = template.optString("photoMimeType").takeIf { it == "image/png" } ?: "image/jpeg"

        val db = PlateDatabase.get(context)
        if (photoFile != null) {
            runCatching { LocalPhotoStore(context, db.photos()).writeFromFile(entryId, photoFile, mimeType) }
                .onFailure { return false }
        }
        val entry = JSONObject()
            .put("id", entryId)
            .put("day", day)
            .put("meal", template.opt("meal") ?: JSONObject.NULL)
            .put("createdAt", Instant.ofEpochMilli(reading.createdAt).toString())
            .put("sortKey", reading.createdAt * 1000 + Random.nextInt(1000))
            .put("photoId", if (photoFile != null) entryId else JSONObject.NULL)
            .put("photoMimeType", if (photoFile != null) mimeType else JSONObject.NULL)
            .put("note", estimate.optString("note").takeIf { it.isNotBlank() }?.take(500) ?: JSONObject.NULL)
            .put("portionSource", "model")
            .put("items", items)
            .put("totals", totals)
            // Saved without the person looking at it. Cleared when they
            // confirm or edit (updateEntry in web/local-api.js).
            .put("checked", false)
        synchronized(lock) {
            db.entries().save(DiaryEntryEntity(entryId, day, entry.getLong("sortKey"), entry.toString(), now))
            forget(context, reading)
        }
        // The widget and the watch show today; a meal filed to another day
        // leaves them alone, and the page redraws them when it next opens.
        if (day == java.time.LocalDate.now().toString()) {
            TodayWidget.moveBy(context, totals, +1)
            WatchSync.entryAdded(context, totals, day)
        }
        notifyLogged(context, entryId, day, items, totals)
        onChanged?.invoke()
        return true
    }

    // ------------------------------------------------------------ notifications

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.readings_channel), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.readings_channel_description) },
        )
    }

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** One notification for every meal logged since Bitey was last opened. */
    private fun notifyLogged(context: Context, entryId: String, day: String, items: JSONArray, totals: JSONObject) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val unseen = prefs.getInt(KEY_UNSEEN, 0) + 1
        prefs.edit().putInt(KEY_UNSEEN, unseen).apply()
        if (!canNotify(context)) return
        createChannel(context)
        val kcal = totals.optDouble("calories", 0.0).let { Math.round(it).toInt() }
        val name = (0 until items.length()).mapNotNull { items.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
            .joinToString(", ").take(80)
        val title: String
        val text: String
        if (unseen == 1) {
            title = context.getString(R.string.reading_logged_title, name)
            text = context.getString(R.string.reading_logged_text, kcal)
        } else {
            title = context.resources.getQuantityString(R.plurals.readings_logged_title, unseen, unseen)
            text = context.getString(R.string.readings_logged_text)
        }
        post(context, NOTIFICATION_LOGGED, title, text, if (unseen == 1) entryId else null, day)
    }

    private fun notifyNeedsYou(context: Context, state: String) {
        if (!canNotify(context)) return
        createChannel(context)
        post(
            context, NOTIFICATION_NEEDS_YOU,
            context.getString(if (state == "failed") R.string.reading_failed_title else R.string.reading_needs_you_title),
            context.getString(R.string.reading_needs_you_text),
            null, null,
        )
    }

    private fun post(context: Context, id: Int, title: String, text: String, entryId: String?, day: String?) {
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply {
                entryId?.let { putExtra(EXTRA_OPEN_ENTRY, it) }
                day?.let { putExtra(EXTRA_OPEN_DAY, it) }
            }
        val pending = PendingIntent.getActivity(
            context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // What was eaten stays off the lock screen.
        val public = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile_bitey)
            .setContentTitle(context.getString(R.string.reading_public))
            .build()
        val notification = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile_bitey)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setVisibility(android.app.Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id, notification) }
    }

    /** Bitey is open: the logged meals have been seen. */
    fun clearNotifications(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_UNSEEN, 0).apply()
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_LOGGED) }
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_NEEDS_YOU) }
    }
}

/** Sends one queued reading. WorkManager runs it off the main thread. */
class ReadingWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.success()
        return when (PendingReadings.run(applicationContext, id)) {
            PendingReadings.Outcome.DONE -> Result.success()
            PendingReadings.Outcome.RETRY -> Result.retry()
        }
    }
}
