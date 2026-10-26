package com.zandaulion.bitey

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchSyncTest {
    private val snapshot = JSONObject()
        .put("day", "2026-10-26")
        .put("kcal", 1240).put("burn", 2300)
        .put("protein", 82.0).put("carbs", 160.0).put("fat", 50.0).put("fiber", 18.0)
        .put("bites", JSONArray().put(JSONObject()
            .put("key", "preset:bite-100").put("label", "Handful").put("kcal", 100)
            .put("entry", JSONObject().put("items", JSONArray().put(JSONObject().put("name", "Handful (~100 kcal)"))))))
        .put("meals", JSONArray())

    private val handful = JSONObject().put("calories", 100.4).put("protein", 3.8).put("carbs", 11.3).put("fat", 4.4).put("fiber", 1.0)

    @Test fun `logging today adds the entry's totals`() {
        val next = WatchSync.moved(snapshot, handful, +1, "2026-10-26")
        assertEquals(1340, next.getInt("kcal"))
        assertEquals(85.8, next.getDouble("protein"), 1e-9)
        assertEquals(2300, next.getInt("burn"))
    }

    @Test fun `undo takes the same totals back`() {
        val back = WatchSync.moved(WatchSync.moved(snapshot, handful, +1, "2026-10-26"), handful, -1, "2026-10-26")
        assertEquals(1240, back.getInt("kcal"))
        assertEquals(82.0, back.getDouble("protein"), 1e-9)
    }

    @Test fun `the first log of a new day starts it from zero`() {
        val next = WatchSync.moved(snapshot, handful, +1, "2026-10-27")
        assertEquals("2026-10-27", next.getString("day"))
        assertEquals(100, next.getInt("kcal"))
        assertEquals(3.8, next.getDouble("protein"), 1e-9)
        assertEquals(2300, next.getInt("burn"))
    }

    @Test fun `undoing an entry from another day leaves today alone`() {
        val next = WatchSync.moved(snapshot, handful, -1, "2026-10-25")
        assertEquals(1240, next.getInt("kcal"))
        assertEquals("2026-10-26", next.getString("day"))
    }

    @Test fun `figures never go below zero`() {
        val small = JSONObject(snapshot.toString()).put("kcal", 50).put("protein", 1.0)
        val next = WatchSync.moved(small, handful, -1, "2026-10-26")
        assertEquals(0, next.getInt("kcal"))
        assertEquals(0.0, next.getDouble("protein"), 1e-9)
    }

    @Test fun `the watch gets names and calories, never the entries`() {
        val sent = WatchSync.forWatch(snapshot)
        val bite = sent.getJSONArray("bites").getJSONObject(0)
        assertFalse(bite.has("entry"))
        assertEquals("preset:bite-100", bite.getString("key"))
        assertTrue(snapshot.getJSONArray("bites").getJSONObject(0).has("entry"))
    }
}
