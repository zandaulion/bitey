package com.zandaulion.bitey

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button

/**
 * One of the action bar's buttons, able to carry a small counter in its top
 * corner: how many free Bitey AI readings are left today. It draws the badge
 * itself, so the bar's layout is the same with or without one.
 *
 * White with a green ring and green digits, so it reads on both the green
 * Photo button and the white ones.
 */
class BadgeButton(context: Context) : Button(context) {
    private var badge: String? = null
    private var badgeDescription: String? = null
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(40, 122, 77)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(32, 98, 62)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = 11f * density
    }

    /** [text] null clears the badge. [description] is what a screen reader adds. */
    fun setBadge(text: String?, description: String?) {
        if (badge == text && badgeDescription == description) return
        badge = text
        badgeDescription = description
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val text = badge ?: return
        val radius = 9f * density
        val cx = width - 7f * density - radius
        val cy = 7f * density + radius
        canvas.drawCircle(cx, cy, radius, fill)
        canvas.drawCircle(cx, cy, radius - ring.strokeWidth / 2, ring)
        canvas.drawText(text, cx, cy - (digits.ascent() + digits.descent()) / 2, digits)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val extra = badgeDescription ?: return
        info.text = "${text}, $extra"
    }
}
