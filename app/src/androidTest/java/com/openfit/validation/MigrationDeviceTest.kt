package com.openfit.validation

import android.content.Context
import androidx.room.Room
import com.openfit.data.OpenFitDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MigrationDeviceTest {
    @Test fun versionOneSnapshotSurvivesAllMigrations() = runBlocking {
        val app = ValidationContext.app
        val name = "migration-fixture.db"
        app.deleteDatabase(name)
        app.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { sqlite ->
            sqlite.execSQL("CREATE TABLE snapshots (`key` TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, date TEXT NOT NULL, version INTEGER NOT NULL, profileKey TEXT NOT NULL, savedAt INTEGER NOT NULL, json TEXT NOT NULL)")
            sqlite.execSQL("INSERT INTO snapshots VALUES ('today:2026-10-16', 'today', '2026-10-16', 1, 'profile', 123, '{\"retained\":true}')")
            sqlite.version = 1
        }
        val db = Room.databaseBuilder(app, OpenFitDatabase::class.java, name).addMigrations(*OpenFitDatabase.MIGRATIONS).build()
        try {
            val snapshot = requireNotNull(db.snapshots().get("today:2026-10-16"))
            assertEquals("{\"retained\":true}", snapshot.json)
            assertEquals(123L, snapshot.savedAt)
            assertTrue(db.healthCache().copyCoverage().isEmpty())
            assertNull(db.calculations().load("missing"))
        } finally { db.close(); app.deleteDatabase(name) }
    }
}
