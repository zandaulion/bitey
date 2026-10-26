package com.zandaulion.bitey.wear

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import kotlin.math.roundToInt

private fun plain(text: String): ComplicationText = PlainComplicationText.Builder(text).build()

/**
 * Complications draw from TodayStore and are pushed a redraw when the phone
 * sends today; the manifest also has them refreshed half-hourly, so an
 * earlier day's figures go back to 0 after midnight.
 */
abstract class BiteyComplication : SuspendingComplicationDataSourceService() {
    abstract fun data(type: ComplicationType, today: Today, tap: PendingIntent?): ComplicationData?

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        data(request.complicationType, TodayStore.current(applicationContext) ?: return null, openApp())

    override fun getPreviewData(type: ComplicationType): ComplicationData? = data(type, PREVIEW, null)

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        val PREVIEW = Today(
            day = "", kcal = 1240, burn = 2300, protein = 82.0, carbs = 160.0, fat = 50.0, fiber = 18.0,
            bites = emptyList(), meals = emptyList(),
            text = mapOf("eaten" to "{0} of ~2300 kcal"),
        )
    }
}

/** Calories today: the figure, a ring against the burn estimate, or the line. */
class CaloriesComplication : BiteyComplication() {
    override fun data(type: ComplicationType, today: Today, tap: PendingIntent?): ComplicationData? {
        val kcal = plain(today.kcal.toString())
        val unit = plain(today.word("kcal"))
        val described = plain(today.eaten)
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(kcal, described)
                .setTitle(unit).setTapAction(tap).build()
            ComplicationType.RANGED_VALUE -> {
                // Without a burn estimate there is nothing to fill towards;
                // the ring then reads full at what has been eaten.
                val max = (today.burn ?: today.kcal).coerceAtLeast(1).toFloat()
                RangedValueComplicationData.Builder(today.kcal.toFloat().coerceAtMost(max), 0f, max, described)
                    .setText(kcal).setTitle(unit).setTapAction(tap).build()
            }
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(described, described)
                .setTitle(plain(today.word("today"))).setTapAction(tap).build()
            else -> null
        }
    }
}

/** Macros today: the three on a long line, or protein alone where only a short one fits. */
class MacrosComplication : BiteyComplication() {
    override fun data(type: ComplicationType, today: Today, tap: PendingIntent?): ComplicationData? {
        val line = plain(today.macros())
        return when (type) {
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(plain(today.macros(short = true)), line)
                .setTitle(plain(today.word("today"))).setTapAction(tap).build()
            // A short slot cuts any title past three or four letters ("PR…"),
            // so protein goes as the tile writes it: "P 89".
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                plain("${today.word("protein").take(1)} ${today.protein.roundToInt()}"), line,
            ).setTapAction(tap).build()
            else -> null
        }
    }
}
