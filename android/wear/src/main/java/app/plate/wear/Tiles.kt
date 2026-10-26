package com.zandaulion.bitey.wear

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Button
import androidx.wear.protolayout.material.ButtonDefaults
import androidx.wear.protolayout.material.CircularProgressIndicator
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

    protected fun waiting(device: DeviceParameters) = PrimaryLayout.Builder(device)
        .setContent(text(this, getString(R.string.waiting), Typography.TYPOGRAPHY_CAPTION1, 3))
        .build()
}

/** Today: a ring against the burn estimate, what has been eaten, and the macros. */
class TodayTile : BiteyTile() {
    override fun layout(device: DeviceParameters, today: Today?): LayoutElementBuilders.LayoutElement {
        today ?: return waiting(device)
        val ring = CircularProgressIndicator.Builder()
            .setProgress(today.progress ?: 0f)
            .setContentDescription(today.eaten)
            .build()
        val content = LayoutElementBuilders.Column.Builder()
            .addContent(text(this, today.kcal.toString(), Typography.TYPOGRAPHY_DISPLAY2))
            .addContent(text(this, today.eaten, Typography.TYPOGRAPHY_CAPTION2, 2))
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(open("today")).build())
            .build()
        return EdgeContentLayout.Builder(device)
            .setEdgeContent(ring)
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
            buttons.addButtonContent(
                Button.Builder(this, open("bite-$i", bite.key))
                    .setTextContent(bite.icon.ifBlank { "+" })
                    .setContentDescription("${bite.label}, +${bite.kcal} ${today.word("kcal")}")
                    .setButtonColors(ButtonDefaults.SECONDARY_COLORS)
                    .build(),
            )
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
