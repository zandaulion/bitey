package com.zandaulion.bitey

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.SizeF
import android.view.View
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.widget.RemoteViews
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * The home-screen widget: today's calories against what the person burns, the
 * macro split of what was eaten, and Photo, Barcode and Type buttons.
 *
 * The burn estimate lives in the page, so the page sends the finished figures
 * and the words to show (in the language chosen in Bitey) whenever it draws
 * today; this only stores and draws them. Meals can only be logged inside
 * Bitey, so a snapshot from an earlier day means nothing has been eaten yet
 * today, and the widget shows 0 against the same burn until Bitey is opened.
 *
 * The buttons are the launcher shortcuts' actions, so Photo still passes the
 * Bitey AI entitlement gate in the page.
 */
class TodayWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { draw(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager, id: Int, options: Bundle,
    ) = draw(context, manager, id)

    companion object {
        private const val PREFS = "today_widget"
        private const val KEY = "snapshot"

        /** Called from the page through the bridge, off the UI thread. */
        fun save(context: Context, json: String) {
            // Parsed once here so a malformed message never reaches a draw.
            if (runCatching { JSONObject(json) }.isFailure) return
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, json).apply()
            refresh(context)
        }

        /** Bitey's face as the page drew it, a base64 PNG. Kept as a file:
         * it is the one thing too large for a preference. */
        fun saveBitey(context: Context, base64: String) {
            val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull() ?: return
            // Decoded first, so only a real image ever replaces the last one.
            if (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) == null) return
            biteyFile(context).writeBytes(bytes)
            refresh(context)
        }

        private fun biteyFile(context: Context) = File(context.filesDir, "widget_bitey.png")

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TodayWidget::class.java))
            ids.forEach { draw(context, manager, it) }
        }

        private enum class Size { SMALL, MEDIUM, FULL, LARGE }

        /** The smallest width at which button labels fit beside their icons.
         * Narrower (three columns), a cut label ("P…") says less than none, so
         * the icons stay and the words move to what a screen reader announces. */
        private const val LABELS_FROM_DP = 300f

        /** The least height each layout needs, wide and narrow: narrow, the
         * legend and Bitey's advice wrap onto more lines, so the same layout
         * needs more height. Rows are not a fixed height (One UI: 100dp for
         * one, 223dp for two on a Fold4 cover screen; HyperOS: about 180dp for
         * two on a 4x6 grid; Moto: about 280dp for two). */
        private fun minHeightDp(size: Size, narrow: Boolean): Float = when (size) {
            Size.SMALL -> 40f
            // Measured on a Moto launcher: about 171dp narrow, where the legend
            // takes two lines, so it fits HyperOS's two rows of about 180dp.
            Size.MEDIUM -> if (narrow) 170f else 155f
            Size.FULL -> if (narrow) 220f else 200f
            Size.LARGE -> if (narrow) 320f else 250f
        }

        private fun draw(context: Context, manager: AppWidgetManager, id: Int) {
            val snapshot = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            // Drawn once and shared by every layout: a widget update has to
            // stay under Binder's 1MB, and identical bitmaps are sent once.
            val today = snapshot?.optString("day") == LocalDate.now().toString()
            val bar = snapshot?.let { bar(context, it, today) }
            val face = biteyFile(context).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }

            val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12 and later: every layout, each with the least size it
                // needs, and the launcher shows the largest that fits. Launchers
                // report sizes differently or not at all (the Moto launcher sent
                // none), so the widget does not try to work out its own.
                RemoteViews(
                    Size.values().flatMap { size ->
                        listOf(180f, LABELS_FROM_DP).map { width ->
                            val narrow = width < LABELS_FROM_DP
                            SizeF(width, minHeightDp(size, narrow)) to
                                build(context, size, narrow, snapshot, today, bar, face)
                        }
                    }.toMap(),
                )
            } else {
                // Before 12, one layout from the size the launcher reports: the
                // smaller of each pair (portrait and landscape), and the default
                // 4x2 when it reports nothing.
                val options = manager.getAppWidgetOptions(id)
                fun smaller(a: String, b: String): Int? = listOf(options.getInt(a, 0), options.getInt(b, 0))
                    .filter { it > 0 }.minOrNull()
                val heightDp = smaller(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 223
                val widthDp = smaller(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH) ?: 359
                val narrow = widthDp < LABELS_FROM_DP
                val size = Size.values().last { heightDp >= minHeightDp(it, narrow) }
                build(context, size, narrow, snapshot, today, bar, face)
            }
            manager.updateAppWidget(id, views)
        }

        private fun build(
            context: Context, size: Size, narrow: Boolean, snapshot: JSONObject?,
            today: Boolean, bar: Bitmap?, face: Bitmap?,
        ): RemoteViews {
            val compact = size == Size.SMALL
            val views = RemoteViews(
                context.packageName,
                when (size) {
                    Size.SMALL -> R.layout.widget_today_small
                    Size.MEDIUM -> R.layout.widget_today_medium
                    else -> R.layout.widget_today
                },
            )
            for (label in intArrayOf(R.id.widget_photo_label, R.id.widget_barcode_label, R.id.widget_manual_label)) {
                views.setViewVisibility(label, if (narrow) View.GONE else View.VISIBLE)
            }

            val text = snapshot?.optJSONObject("text")
            // Until Bitey has sent anything, the layout's own words stand: the
            // widget's name, "Open Bitey to see today", and the button labels.
            if (snapshot != null && text != null) {
                views.setTextViewText(R.id.widget_photo_label, text.optString("photo"))
                views.setTextViewText(R.id.widget_barcode_label, text.optString("barcode"))
                views.setTextViewText(R.id.widget_manual_label, text.optString("manual"))
                views.setContentDescription(R.id.widget_photo, text.optString("photo"))
                views.setContentDescription(R.id.widget_barcode, text.optString("barcode"))
                views.setContentDescription(R.id.widget_manual, text.optString("manual"))
                if (!compact) {
                    views.setTextViewText(R.id.widget_title, text.optString("today"))
                    views.setTextViewText(
                        R.id.widget_kcal,
                        headline(
                            context, text.optString(if (today) "eaten" else "fresh"),
                            if (today) snapshot.optInt("kcal") else 0,
                            if (size == Size.MEDIUM) 1.7f else 2f,
                        ),
                    )
                    if (bar != null) views.setImageViewBitmap(R.id.widget_bar, bar)
                    views.setTextViewText(R.id.widget_macros, macros(context, snapshot, text, today))
                    // Only the full layout has the advice block; touching a
                    // view a layout lacks fails the whole widget. Advice from an
                    // earlier day is about meals that are not today's, so a new
                    // day gets the greeting until Bitey is opened.
                    if (size == Size.LARGE) {
                        views.setViewVisibility(R.id.widget_advice, View.VISIBLE)
                        views.setTextViewText(
                            R.id.widget_advice_text,
                            text.optString(if (today) "advice" else "greeting"),
                        )
                        if (face != null) views.setImageViewBitmap(R.id.widget_bitey, face)
                        else views.setImageViewResource(R.id.widget_bitey, R.mipmap.ic_launcher_round)
                    } else if (size == Size.FULL) {
                        views.setViewVisibility(R.id.widget_advice, View.GONE)
                    }
                }
            }

            if (!compact) views.setOnClickPendingIntent(R.id.widget_summary, open(context, null))
            views.setOnClickPendingIntent(R.id.widget_photo, open(context, "photo"))
            views.setOnClickPendingIntent(R.id.widget_barcode, open(context, "barcode"))
            views.setOnClickPendingIntent(R.id.widget_manual, open(context, "manual"))
            return views
        }

        /** The day's line with its number drawn large and dark: "601" is what
         * the widget is for; "of ~1881 kcal" frames it. The words come from the
         * page in its language, so the number is found rather than assumed to
         * come first. */
        private fun headline(context: Context, line: String, kcal: Int, scale: Float): CharSequence {
            val styled = SpannableStringBuilder(line)
            val number = kcal.toString()
            val at = Regex("(?<![0-9])${number}(?![0-9])").find(line)?.range ?: return styled
            val end = at.last + 1
            styled.setSpan(RelativeSizeSpan(scale), at.first, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            styled.setSpan(StyleSpan(Typeface.BOLD), at.first, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            styled.setSpan(
                ForegroundColorSpan(context.getColor(R.color.widget_ink)),
                at.first, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            return styled
        }

        /** "● Protein 39 g   ● Carbs 60 g   ● Fat 23 g   ● Fiber 5 g", each dot
         * in its colour, so the line doubles as the bar's legend. Fibre has no
         * segment in the bar -- it carries little energy -- but keeps the
         * app's colour for it. */
        private fun macros(context: Context, snapshot: JSONObject, text: JSONObject, today: Boolean): CharSequence {
            val colours = macroColours(context)
            val line = SpannableStringBuilder()
            listOf("protein", "carbs", "fat", "fiber").forEachIndexed { i, key ->
                if (i > 0) line.append("   ")
                val grams = if (today) snapshot.optDouble(key, 0.0).roundToInt() else 0
                val start = line.length
                line.append("\u25CF")
                line.setSpan(ForegroundColorSpan(colours[i]), start, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                // Non-breaking within an item, so a narrow widget wraps between
                // items and never strands a dot from its name.
                line.append("\u00A0${text.optString(key).replace(' ', '\u00A0')}\u00A0$grams\u00A0g")
            }
            return line
        }

        private fun night(context: Context) = context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

        /** The app's --protein, --carbs, --fat and --fiber, light or dark. */
        private fun macroColours(context: Context) = if (night(context)) {
            intArrayOf(
                Color.rgb(0x34, 0xD3, 0x99), Color.rgb(0xFB, 0xBF, 0x24),
                Color.rgb(0xA7, 0x8B, 0xFA), Color.rgb(0x22, 0xD3, 0xEE),
            )
        } else {
            intArrayOf(
                Color.rgb(0x10, 0xB9, 0x81), Color.rgb(0xF5, 0x9E, 0x0B),
                Color.rgb(0x8B, 0x5C, 0xF6), Color.rgb(0x06, 0xB6, 0xD4),
            )
        }

        /** Opens Bitey, reusing a running one, with an action or without. */
        private fun open(context: Context, action: String?): PendingIntent {
            val intent = (if (action == null) Intent(context, MainActivity::class.java)
                else LaunchActions.intent(context, action))
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            // A request code per action, or the four would share one intent.
            val code = when (action) { "photo" -> 1; "barcode" -> 2; "manual" -> 3; else -> 0 }
            return PendingIntent.getActivity(
                context, code, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        /**
         * The day's energy as one bar, the same reading as the totals card in
         * the app: protein, carbs and fat by the energy they carry, on a track
         * that stands for what the person burns. Past the burn the track is
         * full and a notch marks where the burn fell.
         */
        private fun bar(context: Context, snapshot: JSONObject, today: Boolean): Bitmap {
            // One size for every layout, stretched to fit: each layout's own
            // width would mean a bitmap apiece, and a bar needs no more detail.
            val width = 600
            val height = 18
            val night = night(context)
            val track = if (night) Color.rgb(0x24, 0x35, 0x29) else Color.rgb(0xEF, 0xE9, 0xDF)
            val colours = macroColours(context)

            val energy = if (today) doubleArrayOf(
                snapshot.optDouble("protein", 0.0) * 4,
                snapshot.optDouble("carbs", 0.0) * 4,
                snapshot.optDouble("fat", 0.0) * 9,
            ) else doubleArrayOf(0.0, 0.0, 0.0)
            val eaten = energy.sum()
            val burn = snapshot.optDouble("burn", 0.0).takeIf { it > 0 } ?: 0.0
            // The scale is the burn, or the day itself once it is past it.
            val scale = maxOf(burn, eaten, 1.0)

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val radius = height / 2f
            val rounded = Path().apply {
                addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, Path.Direction.CW)
            }
            canvas.clipPath(rounded)
            paint.color = track
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

            var x = 0f
            energy.forEachIndexed { i, kcal ->
                val w = (kcal / scale * width).toFloat()
                paint.color = colours[i]
                canvas.drawRect(x, 0f, x + w, height.toFloat(), paint)
                x += w
            }
            if (burn > 0 && eaten > burn) {
                val at = (burn / scale * width).toFloat()
                paint.color = if (night) Color.WHITE else Color.rgb(0x1C, 0x24, 0x1D)
                canvas.drawRect(at - 1.5f, 0f, at + 1.5f, height.toFloat(), paint)
            }
            return bitmap
        }
    }
}
