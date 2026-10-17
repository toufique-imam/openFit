package com.openfit.ui.today

import com.openfit.test.InMemoryPreferences
import com.openfit.ui.RootTab
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json

class TodayLayoutStoreTest {
    private val prefs = InMemoryPreferences()
    private val store = TodayLayoutStore(prefs)

    @Test fun `custom focus layout survives recreation`() {
        val layout = TodayLayout(
            focus = FocusPreset.Custom,
            custom = listOf(TodayTile.Steps, null, TodayTile.SleepDuration, TodayTile.Distance),
            order = TodaySection.entries.reversed(),
            hidden = setOf(TodayTile.Steps),
            tabs = RootTab.entries.filter { it.optional }.take(3).toSet(),
        )
        store.save(layout)
        assertEquals(layout, TodayLayoutStore(prefs).load())
    }

    @Test fun `unknown and repeated names are ignored and sections added once`() {
        prefs.edit().putString("focus", "FuturePreset").putString("custom", "Steps,FutureTile,,Distance,Heart")
            .putString("order", "Health,Health,FutureSection").putString("hidden", "Steps,FutureTile").apply()
        val layout = store.load()
        assertEquals(TodayLayout().focus, layout.focus)
        assertEquals(listOf(TodayTile.Steps, null, null, TodayTile.Distance), layout.custom)
        assertEquals(0, layout.order.indexOf(TodaySection.Health))
        assertEquals(TodaySection.entries.size, layout.order.size)
        assertEquals(setOf(TodayTile.Steps), layout.hidden)
    }

    @Test fun `duplicate tabs do not consume limit and explicit empty stays empty`() {
        val tabs = RootTab.entries.filter { it.optional }
        prefs.edit().putString("tabs", (listOf("Today", "Unknown", tabs.first().name) + tabs.map { it.name }).joinToString(",")).apply()
        assertEquals(tabs.take(3).toSet(), store.load().tabs)
        prefs.edit().putString("tabs", "").apply()
        assertTrue(store.load().tabs.isEmpty())
    }

    @Test fun `focus movement changes lead metric and preserves trailing off slots`() {
        val slots = listOf(TodayTile.Steps, TodayTile.SleepDuration, TodayTile.Distance, null)
        assertEquals(listOf(TodayTile.SleepDuration, TodayTile.Distance, TodayTile.Steps, null), movedFocusSlots(slots, 0, 2))
        assertEquals(slots, movedFocusSlots(slots, 2, 3))
        assertEquals(slots, movedFocusSlots(slots, -1, 0))
    }

    @Test fun `draft serialization preserves unsaved layout including empty slots`() {
        val draft = TodayLayout(focus = FocusPreset.Custom, custom = listOf(TodayTile.Distance, null, null, null), hidden = setOf(TodayTile.Heart))
        assertEquals(draft, Json.decodeFromString(TodayLayout.serializer(), Json.encodeToString(TodayLayout.serializer(), draft)))
    }
}
