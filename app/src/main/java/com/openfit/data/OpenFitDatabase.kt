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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** A screen's computed state for a date or period, as JSON, so it shows at once next time while a fresh load runs. */
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

/** One day of one record type as read from Health Connect (records filed by the local day they start on). */
@Entity(tableName = "record_days", primaryKeys = ["type", "day"])
data class RecordDayEntity(
    val type: String,
    val day: String,
    /** When Health Connect was read for this day; a day read well after it ended is final. */
    val fetchedAt: Long,
    /** The day's [com.openfit.health.SavedRecord]s as JSON. */
    val json: String,
)

/** An aggregate request's answer from Health Connect, by the request it answers. */
@Entity(tableName = "aggregates")
data class AggregateEntity(
    @PrimaryKey val key: String,
    val fetchedAt: Long,
    /** The end of the range asked about, epoch milliseconds: answered well after it, the answer is final. */
    val rangeEnd: Long,
    val firstDay: String,
    val lastDay: String,
    /** ",steps,distance," — the record types it adds up, for invalidating when one of them changes. */
    val types: String,
    val json: String,
)

@Dao
interface HealthCacheDao {
    @Query("SELECT * FROM record_days WHERE type = :type AND day BETWEEN :from AND :until")
    suspend fun days(type: String, from: String, until: String): List<RecordDayEntity>

    /** Which of [from]..[until] are saved for [type], without loading their records. */
    @Query("SELECT day FROM record_days WHERE type = :type AND day BETWEEN :from AND :until")
    suspend fun savedDays(type: String, from: String, until: String): List<String>

    @Upsert
    suspend fun putDays(days: List<RecordDayEntity>)

    @Query("DELETE FROM record_days WHERE type = :type AND day = :day")
    suspend fun forgetDay(type: String, day: String)

    @Query("DELETE FROM record_days WHERE day >= :from")
    suspend fun forgetDaysFrom(from: String)

    /** A metric's saved daily totals ("day|steps|2026-10-16"), by day. */
    @Query("SELECT * FROM aggregates WHERE `key` LIKE :prefix || '%' AND firstDay BETWEEN :from AND :until")
    suspend fun dailyTotals(prefix: String, from: String, until: String): List<AggregateEntity>

    @Upsert
    suspend fun putAggregates(aggregates: List<AggregateEntity>)

    @Query("SELECT * FROM aggregates WHERE `key` = :key")
    suspend fun aggregate(key: String): AggregateEntity?

    @Upsert
    suspend fun putAggregate(aggregate: AggregateEntity)

    @Query("DELETE FROM aggregates WHERE types LIKE '%,' || :type || ',%' AND firstDay <= :day AND lastDay >= :day")
    suspend fun forgetAggregates(type: String, day: String)

    @Query("DELETE FROM aggregates WHERE lastDay >= :from")
    suspend fun forgetAggregatesFrom(from: String)

    @Query("DELETE FROM record_days")
    suspend fun clearDays()

    /** Whether any saved day of [type] holds a record (an empty day is saved as "[]"). */
    @Query("SELECT EXISTS(SELECT 1 FROM record_days WHERE type = :type AND json != '[]')")
    suspend fun hasRecords(type: String): Boolean

    /** Rows whose type starts with [prefix] ("cloud:"). */
    @Query("DELETE FROM record_days WHERE type LIKE :prefix || '%'")
    suspend fun clearDaysOf(prefix: String)

    @Query("DELETE FROM aggregates")
    suspend fun clearAggregates()
}

/** Version 2 adds the saved Health Connect data and keeps the saved screens. */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `record_days` (`type` TEXT NOT NULL, `day` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, " +
                "`json` TEXT NOT NULL, PRIMARY KEY(`type`, `day`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `aggregates` (`key` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, `rangeEnd` INTEGER NOT NULL, " +
                "`firstDay` TEXT NOT NULL, `lastDay` TEXT NOT NULL, `types` TEXT NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`key`))",
        )
    }
}

@Database(entities = [SnapshotEntity::class, RecordDayEntity::class, AggregateEntity::class], version = 2, exportSchema = false)
abstract class OpenFitDatabase : RoomDatabase() {
    abstract fun snapshots(): SnapshotDao
    abstract fun healthCache(): HealthCacheDao

    companion object {
        @Volatile private var instance: OpenFitDatabase? = null

        fun get(context: Context): OpenFitDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, OpenFitDatabase::class.java, "openfit.db")
                .addMigrations(MIGRATION_1_2)
                // Everything here is a cache of Health Connect, so a schema change without a migration can start over.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .also { instance = it }
        }
    }
}
