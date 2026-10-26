package com.zandaulion.bitey.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class TodayTest {
    private val json = """
        {"day":"2026-10-26","kcal":1240,"burn":2300,"protein":82.4,"carbs":160,"fat":50,"fiber":18,
         "bites":[{"key":"preset:bite-50","label":"Bissen","icon":"🍏","kcal":50},{"label":"no key"}],
         "meals":[{"key":"meal:abc","label":"Porridge, coffee","icon":"🥣","kcal":420}],
         "text":{"eaten":"{0} von ~2300 kcal","protein":"Eiweiß","carbs":"Kohlenhydrate","fat":"Fett"}}
    """.trimIndent()

    @Test fun `parses what the phone sends`() {
        val today = Today.parse(json)!!
        assertEquals(1240, today.kcal)
        assertEquals(2300, today.burn)
        assertEquals(listOf("preset:bite-50"), today.bites.map { it.key })
        assertEquals("meal:abc", today.meals.single().key)
    }

    @Test fun `words come from the phone, in its language`() {
        val today = Today.parse(json)!!
        assertEquals("1240 von ~2300 kcal", today.eaten)
        assertEquals("Eiweiß 82 g · Kohlenhydrate 160 g · Fett 50 g", today.macros())
        assertEquals("E 82 · K 160 · F 50", today.macros(short = true))
        assertEquals("Undo", today.word("undo"))
    }

    @Test fun `an earlier day reads as nothing eaten yet`() {
        val today = Today.parse(json)!!.asOf(LocalDate.parse("2026-10-27"))
        assertEquals(0, today.kcal)
        assertEquals(2300, today.burn)
        assertEquals(1, today.bites.size)
        assertEquals("0 von ~2300 kcal", today.eaten)
    }

    @Test fun `progress fills towards the burn and stops there`() {
        assertEquals(1240f / 2300f, Today.parse(json)!!.progress!!, 1e-6f)
        assertEquals(1f, Today.parse(json.replace("\"kcal\":1240", "\"kcal\":3000"))!!.progress!!, 1e-6f)
        assertNull(Today.parse(json.replace("\"burn\":2300", "\"burn\":null"))!!.progress)
    }

    @Test fun `rejects what is not a snapshot`() {
        assertNull(Today.parse("not json"))
        assertNull(Today.parse("{}"))
    }

    @Test fun `with a burn estimate the ring fills towards it, split by macro energy`() {
        val ring = Today.parse(json)!!.ring
        // 82.4 g protein, 160 g carbs, 50 g fat: 329.6 + 640 + 450 kcal of 2300.
        assertEquals(329.6f / 2300f, ring[0], 1e-5f)
        assertEquals(640f / 2300f, ring[1], 1e-5f)
        assertEquals(450f / 2300f, ring[2], 1e-5f)
    }

    @Test fun `without a profile the ring is full and shows only the split`() {
        val ring = Today.parse(json.replace("\"burn\":2300", "\"burn\":null"))!!.ring
        assertEquals(1f, ring.sum(), 1e-5f)
        assertEquals(640f / 1419.6f, ring[1], 1e-5f)
    }

    @Test fun `past the burn the ring is full`() {
        val ring = Today.parse(json.replace("\"burn\":2300", "\"burn\":1000"))!!.ring
        assertEquals(1f, ring.sum(), 1e-5f)
    }

    @Test fun `nothing eaten leaves the ring empty`() {
        val empty = Today.parse(json)!!.asOf(LocalDate.parse("2026-10-27")).ring
        assertEquals(0f, empty.sum(), 0f)
    }
}
