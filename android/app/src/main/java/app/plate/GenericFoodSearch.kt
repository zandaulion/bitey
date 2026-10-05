package com.zandaulion.bitey

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileOutputStream
import java.text.Normalizer
import java.util.Locale

/**
 * Reads the generic-food table packaged with the APK. The table never leaves
 * the device and is copied once into app-private database storage so Android's
 * SQLite engine can query it efficiently.
 *
 * The table (scripts/build-food-table.mjs) holds USDA's foods and ANSES's
 * CIQUAL, with each food's names in a separate table, one per language. Search
 * matches any name; the result is named in the reader's language when the
 * table has it, else in English. Ordering is left to the page, which ranks
 * with the same rules as the PWA (core/foods.js rankResults): this returns
 * the most promising candidates, not a final list.
 */
class GenericFoodSearch(private val context: Context) : AutoCloseable {
    private val database = lazy { openPackagedTable() }

    companion object {
        /** The filename doubles as a content version: a new table ships as a
         * new file, and the old copy is deleted. */
        private const val TABLE_FILE = "plate-generic-foods-v2.sqlite"
        private val OLD_TABLE_FILES = listOf("plate-generic-foods-v1.sqlite")

        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
        private val MARKS = Regex("\\p{M}+")
        private val LANG = Regex("[a-z]{2}")

        /** core/foods.js foldText, exactly: the table's search column was
         * built with it, so a query folded any other way would miss. */
        fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(MARKS, "")
            .lowercase(Locale.ROOT)
            .replace("œ", "oe").replace("æ", "ae").replace("ß", "ss")
    }

    fun search(rawQuery: String, rawLang: String): String {
        val query = rawQuery.trim().take(80)
        if (query.length < 2) return error("short_query", "Type at least two characters.")
        val tokens = fold(query).split(NON_WORD).filter { it.isNotBlank() }.take(6)
        if (tokens.isEmpty()) return error("short_query", "Type at least two characters.")
        val lang = rawLang.lowercase(Locale.ROOT).take(2).takeIf { LANG.matches(it) } ?: "en"

        val where = tokens.joinToString(" AND ") { "(n.search LIKE ? OR n.search LIKE ?)" }
        // Names that start with the whole query, then shorter names, come
        // first, so the cap keeps "Rice, white, cooked" rather than the 300th
        // dish that mentions rice.
        val args = buildList {
            add(lang)
            tokens.forEach { add("$it%"); add("% $it%") }
            add(tokens.joinToString(" ") + "%")
        }.toTypedArray()
        val rows = JSONArray()
        database.value.rawQuery(
            """
            SELECT f.id, f.kcal, f.protein, f.fat, f.carbs, f.fiber, f.serving_g, f.source,
                   (SELECT name FROM names WHERE food_id = f.id AND lang = ?) AS local,
                   (SELECT name FROM names WHERE food_id = f.id AND lang = 'en') AS english,
                   n.name AS matched
            FROM names n JOIN foods f ON f.id = n.food_id
            WHERE $where
            GROUP BY f.id
            ORDER BY MAX(n.search LIKE ?) DESC, MIN(length(n.name))
            LIMIT 300
            """.trimIndent(),
            args,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val source = cursor.getString(7)
                val name = cursor.getString(8) ?: cursor.getString(9) ?: cursor.getString(10)
                val per100 = JSONObject()
                    .put("calories", cursor.getDouble(1))
                    .put("protein", cursor.getDouble(2))
                    .put("fat", cursor.getDouble(3))
                    .put("carbs", cursor.getDouble(4))
                if (!cursor.isNull(5)) per100.put("fiber", cursor.getDouble(5))
                rows.put(JSONObject()
                    .put("id", "$source:${cursor.getLong(0)}")
                    .put("source", source)
                    .put("barcode", JSONObject.NULL)
                    .put("name", name)
                    .put("per100", per100)
                    .put("servingG", if (cursor.isNull(6)) JSONObject.NULL else cursor.getDouble(6)))
            }
        }
        return JSONObject().put("ok", true).put("results", rows).toString()
    }

    override fun close() {
        if (database.isInitialized()) database.value.close()
    }

    private fun openPackagedTable(): SQLiteDatabase {
        OLD_TABLE_FILES.forEach { context.deleteDatabase(it) }
        val target = context.getDatabasePath(TABLE_FILE)
        if (!target.exists()) {
            target.parentFile?.mkdirs()
            context.assets.open("database/foods.sqlite").use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
        }
        return SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY)
    }

    private fun error(code: String, message: String): String = JSONObject()
        .put("ok", false)
        .put("code", code)
        .put("message", message)
        .toString()
}
