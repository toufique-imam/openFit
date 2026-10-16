package com.openfit.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert

/** A screen's computed state for one date, as JSON, so it shows at once next time while a fresh load runs. */
@Entity(tableName = "snapshots")
data class SnapshotEntity(
    /** "[kind]:[date]", e.g. "today:2026-10-16". */
    @PrimaryKey val key: String,
    val kind: String,
    val date: String,
    /** The snapshot format; rows of another version are ignored. */
    val version: Int,
    /** What the values were computed with (age, sex, goals); a different profile makes the row stale. */
    val profileKey: String,
    val savedAt: Long,
    val json: String,
)

@Dao
interface SnapshotDao {
    @Query("SELECT * FROM snapshots WHERE `key` = :key")
    suspend fun get(key: String): SnapshotEntity?

    @Upsert
    suspend fun put(snapshot: SnapshotEntity)

    @Query("DELETE FROM snapshots WHERE date < :before")
    suspend fun deleteBefore(before: String)
}

@Database(entities = [SnapshotEntity::class], version = 1, exportSchema = false)
abstract class OpenFitDatabase : RoomDatabase() {
    abstract fun snapshots(): SnapshotDao

    companion object {
        @Volatile private var instance: OpenFitDatabase? = null

        fun get(context: Context): OpenFitDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, OpenFitDatabase::class.java, "openfit.db")
                // Everything here is a cache of Health Connect, so a schema change can simply start over.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .also { instance = it }
        }
    }
}
