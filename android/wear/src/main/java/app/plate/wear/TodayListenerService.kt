package com.zandaulion.bitey.wear

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

/** Today, as sent by the phone whenever Bitey redraws it or something is logged. */
class TodayListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED || event.dataItem.uri.path != PATH) return@forEach
            val json = DataMapItem.fromDataItem(event.dataItem).dataMap.getString("json") ?: return@forEach
            TodayStore.save(applicationContext, json)
            redraw(applicationContext)
        }
    }

    companion object {
        const val PATH = "/bitey/today"

        /** Tiles and complications draw from TodayStore, so each is asked to again. */
        fun redraw(context: Context) {
            val tiles = TileService.getUpdater(context)
            tiles.requestUpdate(TodayTile::class.java)
            tiles.requestUpdate(BitesTile::class.java)
            for (source in listOf(CaloriesComplication::class.java, MacrosComplication::class.java)) {
                ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, source)).requestUpdateAll()
            }
        }
    }
}
