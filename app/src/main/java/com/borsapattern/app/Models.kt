package com.borsapattern.app

import androidx.room.*

@Entity(tableName = "symbols")
data class SymbolEntity(
    @PrimaryKey val insCode: String,
    val symbol: String?,
    val name: String?
)

@Entity(tableName = "daily", primaryKeys = ["insCode","date"])
data class DailyEntity(
    val insCode: String,
    val date: Int,
    val high: Double?,
    val last: Double?,
    val yesterday: Double?,
    val volume: Double?,
    val value: Double?
)

@Entity(tableName = "queue_events", primaryKeys = ["insCode","date"])
data class QueueEventEntity(
    val insCode: String,
    val date: Int,
    val eventTime: Int?,
    val queueValue: Double?,
    val score: Double,
    val status: String
)

@Entity(tableName = "live_scores")
data class LiveScoreEntity(
    @PrimaryKey val insCode: String,
    val symbol: String?,
    val score: Double,
    val reason: String,
    val updatedAt: Long
)

@Dao
interface BorsaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSymbols(items: List<SymbolEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDaily(items: List<DailyEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEvents(items: List<QueueEventEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertScores(items: List<LiveScoreEntity>)

    @Query("SELECT COUNT(*) FROM symbols")
    suspend fun symbolCount(): Int

    @Query("SELECT COUNT(*) FROM daily")
    suspend fun dailyCount(): Int

    @Query("SELECT COUNT(*) FROM queue_events")
    suspend fun eventCount(): Int

    @Query("SELECT * FROM live_scores ORDER BY score DESC LIMIT 30")
    suspend fun topScores(): List<LiveScoreEntity>
}

@Database(
    entities = [SymbolEntity::class, DailyEntity::class, QueueEventEntity::class, LiveScoreEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): BorsaDao
}
