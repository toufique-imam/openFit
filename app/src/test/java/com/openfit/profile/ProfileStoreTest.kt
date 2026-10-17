package com.openfit.profile

import com.openfit.test.InMemoryPreferences
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ProfileStoreTest {
    private val prefs = InMemoryPreferences()
    private var today = LocalDate.of(2026, 10, 17)
    private val store = ProfileStore(prefs) { today }

    @Test fun `pre-1970 birth date survives save and age changes on birthday`() {
        val birth = LocalDate.of(1960, 10, 18)
        store.save(Profile(age = 65, birthDate = birth))
        assertEquals(birth, store.load().birthDate)
        assertEquals(65, store.load().age)
        today = today.plusDays(1)
        assertEquals(66, store.load().age)
    }

    @Test fun `typing an age over a birth date clears that date`() {
        store.save(Profile(age = 26, birthDate = LocalDate.of(2000, 1, 1)))
        store.save(store.load().copy(age = 40))
        assertNull(store.load().birthDate)
        assertEquals(40, store.load().age)
    }

    @Test fun `legacy epoch day migrates on save and typed age stays fixed`() {
        val birth = LocalDate.of(1950, 1, 1)
        prefs.edit().putLong("birth_date_epoch_day", birth.toEpochDay() + 1).apply()
        store.save(store.load())
        assertEquals(birth.toString(), prefs.getString("birth_date", null))
        assertFalse(prefs.contains("birth_date_epoch_day"))
        store.save(Profile(age = 35))
        today = today.plusYears(1)
        assertEquals(35, store.load().age)
    }
}
