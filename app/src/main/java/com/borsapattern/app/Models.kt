package com.borsapattern.app

import androidx.room.*

@Entity(tableName = "symbols")
data class SymbolEntity(@PrimaryKey val insCode:String, val symbol:String?, val name:String?)

@Entity(tableName = "daily", primaryKeys=["insCode","date"])
data class DailyEntity(
    val insCode:String,
    val date:Int,
    val high:Double?,
    val last:Double?,
    val yesterday:Double?,
    val volume:Double?,
    val value:Double?
)

@Entity(tableName = "queue_events", primaryKeys=["insCode","date"])
data class QueueEventEntity(
    val insCode:String,
    val date:Int,
    val eventTime:Int?,
    val queueValue:Double?,
    val score:Double,
    val status:String
)

@Entity(tableName = "live_scores")
data class LiveScoreEntity(
    @PrimaryKey val insCode:String,
    val symbol:String?,
    val score:Double,
    val reason:String,
    val updatedAt:Long
)

data class QueueHistoryRow(
    val insCode:String,
    val symbol:String?,
    val date:Int,
    val eventTime:Int?,
    val queueValue:Double?,
    val score:Double,
    val status:String
)

@Dao
interface BorsaDao {
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsertSymbols(items:List<SymbolEntity>)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsertDaily(items:List<DailyEntity>)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsertEvents(items:List<QueueEventEntity>)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsertScores(items:List<LiveScoreEntity>)

    @Query("SELECT COUNT(*) FROM symbols") suspend fun symbolCount():Int
    @Query("SELECT COUNT(*) FROM daily") suspend fun dailyCount():Int
    @Query("SELECT COUNT(*) FROM queue_events WHERE status='CANDIDATE'") suspend fun candidateCount():Int
    @Query("SELECT COUNT(*) FROM queue_events WHERE status='QUEUE_CONFIRMED'") suspend fun confirmedCount():Int
    @Query("SELECT COUNT(*) FROM queue_events WHERE status='NOT_QUEUE'") suspend fun rejectedCount():Int
    @Query("SELECT COUNT(*) FROM queue_events WHERE status='ERROR'") suspend fun errorCount():Int

    @Query("SELECT MAX(date) FROM daily") suspend fun latestMarketDate():Int?
    @Query("SELECT MAX(date) FROM daily WHERE insCode=:insCode") suspend fun latestDateFor(insCode:String):Int?
    @Query("SELECT * FROM symbols ORDER BY COALESCE(symbol, name, insCode)") suspend fun allSymbols():List<SymbolEntity>

    @Query("""
      SELECT l.insCode AS insCode,
             CASE
               WHEN s.symbol IS NOT NULL AND s.symbol != '' AND s.symbol NOT GLOB '[0-9]*' THEN s.symbol
               WHEN l.symbol IS NOT NULL AND l.symbol != '' AND l.symbol NOT GLOB '[0-9]*' THEN l.symbol
               ELSE NULL
             END AS symbol,
             l.score AS score,
             l.reason AS reason,
             l.updatedAt AS updatedAt
      FROM live_scores l
      LEFT JOIN symbols s ON s.insCode=l.insCode
      ORDER BY l.score DESC
      LIMIT 30
    """)
    suspend fun topScores():List<LiveScoreEntity>

    @Query("SELECT * FROM queue_events WHERE status='CANDIDATE' ORDER BY date DESC LIMIT :limit")
    suspend fun candidateEvents(limit:Int):List<QueueEventEntity>

    @Query("SELECT * FROM daily WHERE insCode=:insCode AND date=:date LIMIT 1")
    suspend fun dailyFor(insCode:String,date:Int):DailyEntity?

    @Query("UPDATE queue_events SET status='CANDIDATE' WHERE status='ERROR'")
    suspend fun retryErrors()

    @Query("""
      SELECT e.insCode AS insCode,
             CASE
               WHEN s.symbol IS NOT NULL AND s.symbol != '' AND s.symbol NOT GLOB '[0-9]*' THEN s.symbol
               ELSE NULL
             END AS symbol,
             e.date AS date,
             e.eventTime AS eventTime,
             e.queueValue AS queueValue,
             e.score AS score,
             e.status AS status
      FROM queue_events e
      LEFT JOIN symbols s ON s.insCode=e.insCode
      WHERE e.status='QUEUE_CONFIRMED'
      ORDER BY e.date DESC, e.score DESC
      LIMIT :limit
    """)
    suspend fun confirmedHistory(limit:Int=1000):List<QueueHistoryRow>
}

@Database(
    entities=[SymbolEntity::class,DailyEntity::class,QueueEventEntity::class,LiveScoreEntity::class],
    version=1,
    exportSchema=false
)
abstract class AppDatabase:RoomDatabase(){ abstract fun dao():BorsaDao }
