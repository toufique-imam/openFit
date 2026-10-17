package com.openfit.data

import android.content.Context
import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
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
    @Query("DELETE FROM snapshots") suspend fun clear()
}

/** Derived values only; raw copied records remain in record_days. */
@Entity(tableName = "day_loads", indices = [Index("day")])
data class DayLoadEntity(
    @PrimaryKey val key: String,
    val day: String,
    val zoneMinutes: Int,
    val cardioLoad: Double,
    val moderate: Int,
    val vigorous: Int,
    val peak: Int,
)

@Entity(tableName = "day_slots", indices = [Index("day")])
data class DaySlotsEntity(@PrimaryKey val key: String, val day: String, val slots: String)

@Dao
interface CalculationDao {
    @Query("SELECT * FROM day_loads WHERE `key` = :key")
    suspend fun load(key: String): DayLoadEntity?
    @Upsert suspend fun putLoads(rows: List<DayLoadEntity>)
    @Query("DELETE FROM day_loads WHERE day IN (:days)")
    suspend fun forgetLoads(days: List<String>)
    @Query("DELETE FROM day_loads WHERE day >= :day")
    suspend fun forgetLoadsFrom(day: String)
    @Query("DELETE FROM day_loads") suspend fun clearLoads()

    @Query("SELECT * FROM day_slots WHERE `key` = :key")
    suspend fun slots(key: String): DaySlotsEntity?
    @Upsert suspend fun putSlots(rows: List<DaySlotsEntity>)
    @Query("DELETE FROM day_slots WHERE day IN (:days)")
    suspend fun forgetSlots(days: List<String>)
    @Query("DELETE FROM day_slots") suspend fun clearSlots()
}

/** One day of one record type as read from Health Connect (records filed by the local day they start on). */
@Entity(tableName = "record_days", primaryKeys = ["type", "day"])
data class RecordDayEntity(
    val type: String,
    val day: String,
    /** When Health Connect was read for this day; a day read well after it ended is final. */
    val fetchedAt: Long,
    /** Legacy/small rows use JSON. Large rows use the compressed payload column. */
    val json: String,
    @ColumnInfo(defaultValue = "NULL") val payload: ByteArray? = null,
)

/** Locations survive until a successful reread so moved/deleted records invalidate their previous dates. */
@Entity(tableName = "record_locations", primaryKeys = ["type", "id"], indices = [Index("id"), Index(value = ["type", "day", "lastDay"])])
data class RecordLocationEntity(val type: String, val id: String, val day: String, val lastDay: String)

data class CopyCoverage(val type: String, val firstDay: String, val lastDay: String, val days: Int)

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
    @Query("DELETE FROM record_days WHERE type = :type") suspend fun clearType(type: String)
    @Query("UPDATE record_days SET type = :destination WHERE type = :source")
    suspend fun renameType(source: String, destination: String)
    /** Includes copied empty days, so the range describes import coverage rather than wearable use. */
    @Query("SELECT type, MIN(day) AS firstDay, MAX(day) AS lastDay, COUNT(*) AS days FROM record_days WHERE type NOT LIKE 'cloud:%' GROUP BY type ORDER BY type")
    suspend fun copyCoverage(): List<CopyCoverage>
    @Query("SELECT * FROM record_days AS d WHERE d.type = :type AND d.day <= :until AND (d.day >= :from OR EXISTS (SELECT 1 FROM record_locations AS r WHERE r.type = d.type AND r.day = d.day AND r.lastDay >= :from))")
    suspend fun overlappingDays(type: String, from: String, until: String): List<RecordDayEntity>
    @Query("SELECT * FROM record_locations WHERE id IN (:ids)")
    suspend fun locations(ids: List<String>): List<RecordLocationEntity>

    @Upsert
    suspend fun putLocations(records: List<RecordLocationEntity>)

    @Query("DELETE FROM record_locations WHERE type = :type AND day IN (:days)")
    suspend fun clearLocations(type: String, days: List<String>)

    @Query("DELETE FROM record_locations")
    suspend fun clearLocations()

    @Query("DELETE FROM record_locations WHERE type LIKE :prefix || '%'")
    suspend fun clearLocationsOf(prefix: String)
    @Query("SELECT * FROM record_days WHERE type = :type AND day BETWEEN :from AND :until")
    suspend fun days(type: String, from: String, until: String): List<RecordDayEntity>

    /** Which of [from]..[until] are saved for [type], without loading their records. */
    @Query("SELECT day FROM record_days WHERE type = :type AND day BETWEEN :from AND :until")
    suspend fun savedDays(type: String, from: String, until: String): List<String>

    @Query("SELECT day FROM record_days WHERE type = :type AND day BETWEEN :from AND :until AND fetchedAt >= :since")
    suspend fun daysRefreshedSince(type: String, from: String, until: String, since: Long): List<String>

    @Upsert
    suspend fun putDays(days: List<RecordDayEntity>)

    @Query("DELETE FROM record_days WHERE type = :type AND day = :day")
    suspend fun forgetDay(type: String, day: String)

    @Query("DELETE FROM record_days WHERE day >= :from")
    suspend fun forgetDaysFrom(from: String)

    /** A metric's saved daily totals ("day|steps|2026-10-16"), by day. */
    @Query("SELECT * FROM aggregates WHERE `key` BETWEEN :firstKey AND :lastKey")
    suspend fun dailyTotals(firstKey: String, lastKey: String): List<AggregateEntity>

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

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `record_locations` (`type` TEXT NOT NULL, `id` TEXT NOT NULL, `day` TEXT NOT NULL, `lastDay` TEXT NOT NULL, PRIMARY KEY(`type`, `id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_record_locations_id` ON `record_locations` (`id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_record_locations_type_day_lastDay` ON `record_locations` (`type`, `day`, `lastDay`)")
    }
}

private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `day_loads` (`key` TEXT NOT NULL, `day` TEXT NOT NULL, `zoneMinutes` INTEGER NOT NULL, `cardioLoad` REAL NOT NULL, `moderate` INTEGER NOT NULL, `vigorous` INTEGER NOT NULL, `peak` INTEGER NOT NULL, PRIMARY KEY(`key`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_day_loads_day` ON `day_loads` (`day`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `day_slots` (`key` TEXT NOT NULL, `day` TEXT NOT NULL, `slots` TEXT NOT NULL, PRIMARY KEY(`key`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_day_slots_day` ON `day_slots` (`day`)")
    }
}

private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `record_days` ADD COLUMN `payload` BLOB DEFAULT NULL")
    }
}

@Database(entities = [SnapshotEntity::class, RecordDayEntity::class, AggregateEntity::class, RecordLocationEntity::class, DayLoadEntity::class, DaySlotsEntity::class], version = 5, exportSchema = false)
abstract class OpenFitDatabase : RoomDatabase() {
    abstract fun snapshots(): SnapshotDao
    abstract fun healthCache(): HealthCacheDao
    abstract fun calculations(): CalculationDao

    companion object {
        internal val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
        @Volatile private var instance: OpenFitDatabase? = null

        fun get(context: Context): OpenFitDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, OpenFitDatabase::class.java, "openfit.db")
                .addMigrations(*MIGRATIONS)
                .build()
                .also { instance = it }
        }
    }
}
