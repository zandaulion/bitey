package com.zandaulion.bitey.wear

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Button
import androidx.wear.protolayout.material.ButtonDefaults
import androidx.wear.protolayout.material.Colors
import androidx.wear.protolayout.material.CompactChip
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.EdgeContentLayout
import androidx.wear.protolayout.material.layouts.MultiButtonLayout
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import java.time.Duration

private const val RESOURCES_VERSION = "1"
private const val RING_DP = 6f
private const val TRACK = 0xFF2B2B2E.toInt()

/** Tiles draw from TodayStore and are asked to again when the phone sends today. */
abstract class BiteyTile : TileService() {
    abstract fun layout(device: DeviceParameters, today: Today?): LayoutElementBuilders.LayoutElement

    override fun onTileRequest(request: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            // Half-hourly as well, so an earlier day's figures go back to 0 after midnight.
            .setFreshnessIntervalMillis(Duration.ofMinutes(30).toMillis())
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(
                layout(request.deviceConfiguration, TodayStore.current(applicationContext)),
            ))
            .build()
        return CallbackToFutureAdapter.getFuture { it.set(tile); "tile" }
    }

    override fun onTileResourcesRequest(
        request: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> {
        val resources = ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build()
        return CallbackToFutureAdapter.getFuture { it.set(resources); "resources" }
    }

    /** Opens the watch app; with [choice], it logs that and offers Undo. */
    protected fun open(id: String, choice: String? = null): ModifiersBuilders.Clickable {
        val activity = ActionBuilders.AndroidActivity.Builder()
            .setPackageName(packageName)
            .setClassName(MainActivity::class.java.name)
        if (choice != null) {
            activity.addKeyToExtraMapping(
                MainActivity.EXTRA_CHOICE,
                ActionBuilders.AndroidStringExtra.Builder().setValue(choice).build(),
            )
        }
        return ModifiersBuilders.Clickable.Builder()
            .setId(id)
            .setOnClick(ActionBuilders.LaunchAction.Builder().setAndroidActivity(activity.build()).build())
            .build()
    }

    // Material Text defaults to the colour for text on a primary button, which
    // is near-black: on a tile's black background it all but disappears.
    protected fun text(context: Context, value: String, typography: Int, lines: Int = 1) =
        Text.Builder(context, value).setTypography(typography).setMaxLines(lines)
            .setColor(ColorBuilders.argb(Colors.DEFAULT.onSurface)).build()

    /** The app's ring: a track, and protein, carbs and fat laid along it by energy. */
    protected fun ring(today: Today): LayoutElementBuilders.LayoutElement {
        fun line(degrees: Float, colour: Int) = LayoutElementBuilders.ArcLine.Builder()
            .setLength(DimensionBuilders.degrees(degrees))
            .setThickness(DimensionBuilders.dp(RING_DP))
            .setColor(ColorBuilders.argb(colour))
            .setStrokeCap(LayoutElementBuilders.STROKE_CAP_BUTT)
            .build()
        fun arc() = LayoutElementBuilders.Arc.Builder()
            .setAnchorAngle(DimensionBuilders.degrees(0f))
            .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
        val segments = arc()
        today.ring.zip(MacroColours.ALL).forEach { (share, colour) ->
            if (share > 0f) segments.addContent(line(360f * share, colour))
        }
        return LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .addContent(arc().addContent(line(360f, TRACK)).build())
            .addContent(segments.build())
            .build()
    }

    /** A quick bite as a round button showing its icon; a tap opens the app to log it. */
    protected fun biteButton(today: Today, i: Int, bite: Choice, size: Float? = null) =
        Button.Builder(this, open("bite-$i", bite.key))
            .apply { if (size != null) setSize(size) }
            .setTextContent(bite.icon.ifBlank { "+" })
            .setContentDescription("${bite.label}, +${bite.kcal} ${today.word("kcal")}")
            .setButtonColors(ButtonDefaults.SECONDARY_COLORS)
            .build()

    protected fun waiting(device: DeviceParameters) = PrimaryLayout.Builder(device)
        .setContent(text(this, getString(R.string.waiting), Typography.TYPOGRAPHY_CAPTION1, 3))
        .build()
}

/** Today: a ring against the burn estimate, what has been eaten, and the macros. */
class TodayTile : BiteyTile() {
    override fun layout(device: DeviceParameters, today: Today?): LayoutElementBuilders.LayoutElement {
        today ?: return waiting(device)
        val content = LayoutElementBuilders.Column.Builder()
            .addContent(text(this, today.kcal.toString(), Typography.TYPOGRAPHY_DISPLAY2))
            .addContent(text(this, today.eaten, Typography.TYPOGRAPHY_CAPTION2, 2))
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(open("today")).build())
            .build()
        return EdgeContentLayout.Builder(device)
            .setEdgeContent(ring(today))
            .setEdgeContentThickness(RING_DP)
            .setPrimaryLabelTextContent(text(this, today.word("today"), Typography.TYPOGRAPHY_CAPTION1))
            .setContent(content)
            .setSecondaryLabelTextContent(text(this, today.macros(short = true), Typography.TYPOGRAPHY_CAPTION2))
            .build()
    }
}

/** Quick bites, one tap each, and the meals to repeat in the app. */
class BitesTile : BiteyTile() {
    override fun layout(device: DeviceParameters, today: Today?): LayoutElementBuilders.LayoutElement {
        today ?: return waiting(device)
        val buttons = MultiButtonLayout.Builder()
        today.bites.take(MAX_BUTTONS).forEachIndexed { i, bite ->
            buttons.addButtonContent(biteButton(today, i, bite))
        }
        return PrimaryLayout.Builder(device)
            .setPrimaryLabelTextContent(text(this, today.word("bites"), Typography.TYPOGRAPHY_CAPTION1))
            .setContent(buttons.build())
            .setPrimaryChipContent(
                CompactChip.Builder(this, today.word("meals"), open("meals"), device).build(),
            )
            .build()
    }

    private companion object {
        /** MultiButtonLayout's most; more and the buttons get too small to hit. */
        const val MAX_BUTTONS = 6
    }
}

/**
 * Today and the quick bites in one slot: the ring and the day's total, and
 * the first three bites beneath it. For a watch whose tiles are few or kept
 * short. Third-party tiles cannot join One UI's stacked "multi-info" tiles,
 * so this is the nearest thing to stacking them.
 */
class TodayBitesTile : BiteyTile() {
    override fun layout(device: DeviceParameters, today: Today?): LayoutElementBuilders.LayoutElement {
        today ?: return waiting(device)
        val row = LayoutElementBuilders.Row.Builder()
        today.bites.take(BUTTONS).forEachIndexed { i, bite ->
            if (i > 0) row.addContent(LayoutElementBuilders.Spacer.Builder().setWidth(DimensionBuilders.dp(4f)).build())
            row.addContent(biteButton(today, i, bite, size = BUTTON_DP))
        }
        val content = LayoutElementBuilders.Column.Builder()
            .addContent(
                LayoutElementBuilders.Box.Builder()
                    .addContent(text(this, today.kcal.toString(), Typography.TYPOGRAPHY_TITLE1))
                    .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(open("today")).build())
                    .build(),
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(6f)).build())
            .addContent(row.build())
            .build()
        return EdgeContentLayout.Builder(device)
            .setEdgeContent(ring(today))
            .setEdgeContentThickness(RING_DP)
            .setPrimaryLabelTextContent(text(this, today.word("today"), Typography.TYPOGRAPHY_CAPTION1))
            .setContent(content)
            .setSecondaryLabelTextContent(text(this, today.macros(short = true), Typography.TYPOGRAPHY_CAPTION2))
            .build()
    }

    private companion object {
        /** Three across inside the ring: at the default 52 dp they would not
         * fit a small watch, and 44 dp is still above the 40 dp touch minimum. */
        const val BUTTONS = 3
        const val BUTTON_DP = 44f
    }
}
