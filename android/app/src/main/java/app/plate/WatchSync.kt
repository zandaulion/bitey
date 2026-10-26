package com.zandaulion.bitey

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The phone's half of the watch companion.
 *
 * The page sends today, as it drew it, together with every quick bite and
 * repeat meal the watch offers, each already built as the entry it saves
 * (entryTemplate in web/local-api.js). This keeps that snapshot and gives the
 * watch a copy without the entries: names, icons and calories are all it
 * draws, and food items have no reason to leave the phone.
 *
 * When something is tapped on the watch, [log] stamps that entry with what
 * only the moment of saving gives it -- id, day, time, sort key -- and writes
 * it to Room exactly as the page's entries.write does. Nothing is worked out
 * here: the totals are the ones the page computed. The figures shown on the
 * watch and the widget are moved by the entry's totals until Bitey is next
 * opened, when the page redraws them properly.
 */
object WatchSync {
    const val PATH_TODAY = "/bitey/today"
    const val PATH_LOG = "/bitey/log"
    const val PATH_UNDO = "/bitey/undo"
    const val PATH_REFRESH = "/bitey/refresh"

    private const val PREFS = "watch_sync"
    private const val KEY_SNAPSHOT = "snapshot"
    private const val KEY_PUBLISHED = "published"
    private const val KEY_LOGGED = "logged"

    /** How many of the watch's own entries it can still take back. */
    private const val UNDO_DEPTH = 10

    /** The Data Layer refuses an item over 100 KB. */
    private const val MAX_SNAPSHOT = 90_000

    private val ID = Regex("[A-Za-z0-9_-]{1,80}")
    private val lock = Any()

    /** Set by MainActivity while Bitey is open, so the page redraws a change. */
    @Volatile var onDiaryChanged: (() -> Unit)? = null

    /** Called from the page through the bridge, off the UI thread. */
    fun save(context: Context, json: String) {
        if (json.length > MAX_SNAPSHOT) return
        val snapshot = runCatching { JSONObject(json) }.getOrNull() ?: return
        synchronized(lock) { store(context, snapshot) }
        publish(context)
    }

    /** The watch asked for today again: first launch, or after a reinstall. */
    fun refresh(context: Context): JSONObject {
        publish(context, force = true)
        return JSONObject().put("ok", true)
    }

    /** Logs the choice the watch tapped. Runs off the main thread. */
    fun log(context: Context, key: String): JSONObject {
        val reply = synchronized(lock) {
            val snapshot = stored(context) ?: return error("no_snapshot")
            val choice = choices(snapshot).firstOrNull { it.optString("key") == key }
                ?: return error("not_found")
            val template = choice.optJSONObject("entry") ?: return error("not_found")
            if ((template.optJSONArray("items")?.length() ?: 0) == 0) return error("not_found")

            val now = System.currentTimeMillis()
            val id = UUID.randomUUID().toString()
            val day = LocalDate.now().toString()
            val sortKey = now * 1000 + Random.nextInt(1000)
            val entry = JSONObject(template.toString())
                .put("id", id)
                .put("day", day)
                .put("createdAt", Instant.ofEpochMilli(now).toString())
                .put("sortKey", sortKey)
                .put("photoId", JSONObject.NULL)
                .put("photoData", JSONObject.NULL)
            if (entry.toString().length > 64_000) return error("too_large")
            // entries.write in PlateWebView never stores photoData; nor does this.
            entry.remove("photoData")
            PlateDatabase.get(context).entries().save(
                DiaryEntryEntity(id = id, day = day, sortKey = sortKey, entryJson = entry.toString(), updatedAt = now),
            )
            remember(context, id)

            val totals = entry.optJSONObject("totals") ?: JSONObject()
            store(context, moved(snapshot, totals, +1, day))
            TodayWidget.moveBy(context, totals, +1)
            JSONObject()
                .put("ok", true)
                .put("id", id)
                .put("label", choice.optString("label"))
                .put("kcal", choice.optInt("kcal"))
        }
        publish(context)
        onDiaryChanged?.invoke()
        return reply
    }

    /** Takes back something the watch logged, and only that. */
    fun undo(context: Context, id: String): JSONObject {
        synchronized(lock) {
            if (!id.matches(ID) || id !in logged(context)) return error("not_found")
            val entries = PlateDatabase.get(context).entries()
            val entry = entries.entryJson(id)?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: return error("not_found")
            if (entries.delete(id) == 0) return error("not_found")
            forget(context, id)
            val totals = entry.optJSONObject("totals") ?: JSONObject()
            val day = entry.optString("day")
            stored(context)?.let { store(context, moved(it, totals, -1, day)) }
            if (day == LocalDate.now().toString()) TodayWidget.moveBy(context, totals, -1)
        }
        publish(context)
        onDiaryChanged?.invoke()
        return JSONObject().put("ok", true)
    }

    /**
     * Today's figures moved by one entry. A snapshot from an earlier day means
     * nothing has been eaten today yet, as the widget reads it, so a meal
     * logged today starts the day from zero. Taking back an entry from a day
     * the snapshot is not about leaves it alone.
     */
    internal fun moved(snapshot: JSONObject, totals: JSONObject, sign: Int, day: String): JSONObject {
        val next = JSONObject(snapshot.toString())
        if (next.optString("day") != day) {
            if (sign < 0) return next
            next.put("day", day)
            for (key in FIGURES) next.put(key, 0)
        }
        next.put("kcal", (next.optDouble("kcal", 0.0) + sign * totals.optDouble("calories", 0.0)).roundToInt().coerceAtLeast(0))
        for (key in MACROS) {
            val value = next.optDouble(key, 0.0) + sign * totals.optDouble(key, 0.0)
            next.put(key, (value.coerceAtLeast(0.0) * 10).roundToInt() / 10.0)
        }
        return next
    }

    /** What the watch is sent: everything but the entries behind the choices. */
    internal fun forWatch(snapshot: JSONObject): JSONObject {
        val copy = JSONObject(snapshot.toString())
        for (list in LISTS) {
            val choices = copy.optJSONArray(list) ?: continue
            for (i in 0 until choices.length()) choices.optJSONObject(i)?.remove("entry")
        }
        return copy
    }

    private fun publish(context: Context, force: Boolean = false) {
        val json = synchronized(lock) {
            val snapshot = stored(context) ?: return
            val json = forWatch(snapshot).toString()
            // The page sends today on every redraw; most are the same.
            if (!force && json == prefs(context).getString(KEY_PUBLISHED, null)) return
            prefs(context).edit().putString(KEY_PUBLISHED, json).apply()
            json
        }
        val request = PutDataMapRequest.create(PATH_TODAY).apply {
            dataMap.putString("json", json)
            // Without a change the Data Layer delivers nothing; a refresh is
            // asked for when the watch has lost what it had.
            if (force) dataMap.putLong("at", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        // No Play services, or no watch paired: nothing to do, and no error to show.
        runCatching { Wearable.getDataClient(context).putDataItem(request) }
    }

    private fun choices(snapshot: JSONObject): Sequence<JSONObject> = LISTS.asSequence().flatMap { list ->
        val array = snapshot.optJSONArray(list) ?: JSONArray()
        (0 until array.length()).asSequence().mapNotNull { array.optJSONObject(it) }
    }

    private fun stored(context: Context): JSONObject? =
        prefs(context).getString(KEY_SNAPSHOT, null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun store(context: Context, snapshot: JSONObject) {
        prefs(context).edit().putString(KEY_SNAPSHOT, snapshot.toString()).apply()
    }

    private fun logged(context: Context): List<String> =
        prefs(context).getString(KEY_LOGGED, null)
            ?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?.let { array -> (0 until array.length()).map { array.optString(it) } }
            ?: emptyList()

    private fun remember(context: Context, id: String) {
        val ids = (logged(context) + id).takeLast(UNDO_DEPTH)
        prefs(context).edit().putString(KEY_LOGGED, JSONArray(ids).toString()).apply()
    }

    private fun forget(context: Context, id: String) {
        val ids = logged(context) - id
        prefs(context).edit().putString(KEY_LOGGED, JSONArray(ids).toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun error(code: String) = JSONObject().put("ok", false).put("code", code)

    private val LISTS = listOf("bites", "meals")
    private val MACROS = listOf("protein", "carbs", "fat", "fiber")
    private val FIGURES = listOf("kcal") + MACROS
}
