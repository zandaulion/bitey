package com.zandaulion.bitey.wear

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.roundToInt

/** A quick bite or repeat meal the phone can log. [key] is what is asked for. */
data class Choice(val key: String, val label: String, val icon: String, val kcal: Int)

/**
 * Today as the phone last sent it (WatchSync on the phone). The figures are
 * the page's own; the watch only draws them. Words come in the language chosen
 * in Bitey, with English if the phone has not sent a word yet.
 */
data class Today(
    val day: String,
    val kcal: Int,
    val burn: Int?,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val fiber: Double,
    val bites: List<Choice>,
    val meals: List<Choice>,
    private val text: Map<String, String>,
) {
    /**
     * As the widget reads it: a snapshot from an earlier day means nothing has
     * been eaten today yet, so it shows 0 against the same burn until Bitey is
     * opened or something is logged here. The choices stay, being still good.
     */
    fun asOf(date: LocalDate): Today =
        if (day == date.toString()) this
        else copy(day = date.toString(), kcal = 0, protein = 0.0, carbs = 0.0, fat = 0.0, fiber = 0.0)

    /**
     * The ring, as the phone widget's bar draws it: protein, carbs and fat by
     * energy, each a fraction of the whole ring. The scale is the burn
     * estimate, or the day itself once it is past it or when there is none,
     * so with no profile the ring is full and shows only the split. Bitey sets
     * no target of its own to fill towards.
     */
    val ring: List<Float> get() {
        val energy = listOf(protein * 4, carbs * 4, fat * 9)
        val scale = maxOf(burn?.toDouble() ?: 0.0, energy.sum(), 1.0)
        return energy.map { (it / scale).toFloat() }
    }

    /** How far through the burn estimate today is, or null without one. */
    val progress: Float? get() = burn?.takeIf { it > 0 }?.let { (kcal.toFloat() / it).coerceIn(0f, 1f) }

    /** "1240 of ~2300 kcal", in the phone's words. */
    val eaten: String get() = word("eaten", "{0} kcal").replace("{0}", kcal.toString())

    /** "Protein 82 g · Carbs 160 g · Fat 50 g", or with [short], "P 82 · C 160 · F 50". */
    fun macros(short: Boolean = false): String = macroParts(short).joinToString(" · ")

    /** The three parts of [macros], protein first, for drawing each in its colour. */
    fun macroParts(short: Boolean = true): List<String> = listOf(
        "protein" to protein, "carbs" to carbs, "fat" to fat,
    ).map { (key, grams) ->
        val name = word(key, ENGLISH.getValue(key))
        if (short) "${name.take(1)} ${grams.roundToInt()}" else "$name ${grams.roundToInt()} g"
    }

    fun word(key: String, english: String = ENGLISH[key] ?: key): String =
        text[key]?.takeIf { it.isNotBlank() } ?: english

    fun logged(label: String): String = word("logged", "Logged {0}").replace("{0}", label)

    companion object {
        private val ENGLISH = mapOf(
            "today" to "Today", "kcal" to "kcal", "protein" to "Protein", "carbs" to "Carbs",
            "fat" to "Fat", "fiber" to "Fiber", "bites" to "Quick bites", "meals" to "Repeat",
            "undo" to "Undo", "undone" to "Undone", "back" to "Back",
        )

        fun parse(json: String): Today? = runCatching {
            val o = JSONObject(json)
            val text = o.optJSONObject("text")
            Today(
                day = o.getString("day"),
                kcal = o.optInt("kcal"),
                burn = o.optInt("burn").takeIf { it > 0 && !o.isNull("burn") },
                protein = o.optDouble("protein", 0.0),
                carbs = o.optDouble("carbs", 0.0),
                fat = o.optDouble("fat", 0.0),
                fiber = o.optDouble("fiber", 0.0),
                bites = choices(o, "bites"),
                meals = choices(o, "meals"),
                text = text?.keys()?.asSequence()?.associateWith { text.optString(it) }.orEmpty(),
            )
        }.getOrNull()

        private fun choices(o: JSONObject, name: String): List<Choice> {
            val array = o.optJSONArray(name) ?: return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val c = array.optJSONObject(i) ?: return@mapNotNull null
                val key = c.optString("key").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Choice(key, c.optString("label"), c.optString("icon"), c.optInt("kcal"))
            }
        }
    }
}

/** The last snapshot, kept so tiles and complications can draw without the phone. */
object TodayStore {
    private const val PREFS = "today"
    private const val KEY = "snapshot"

    private val state = MutableStateFlow<Today?>(null)
    @Volatile private var loaded = false

    fun flow(context: Context): StateFlow<Today?> {
        load(context)
        return state
    }

    /** Today, with an earlier day's figures read as nothing eaten yet. */
    fun current(context: Context): Today? = load(context)?.asOf(LocalDate.now())

    fun save(context: Context, json: String) {
        val today = Today.parse(json) ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json).apply()
        state.value = today
        loaded = true
    }

    private fun load(context: Context): Today? {
        if (!loaded) {
            state.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null)?.let(Today::parse)
            loaded = true
        }
        return state.value
    }
}

/** The app's --protein, --carbs and --fat in its dark theme, as on the widget at night. */
object MacroColours {
    const val PROTEIN = 0xFF34D399.toInt()
    const val CARBS = 0xFFFBBF24.toInt()
    const val FAT = 0xFFA78BFA.toInt()
    val ALL = listOf(PROTEIN, CARBS, FAT)
}
