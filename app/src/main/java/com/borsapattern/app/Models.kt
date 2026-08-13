package com.borsapattern.app

import androidx.room.*

@Entity(tableName="symbols")
data class SymbolEntity(
    @PrimaryKey val insCode:String,
    val symbol:String?,
    val name:String?,
    val flow:Int?=null,
    val segment:String="OTHER",
    val boardTitle:String?=null,
    val instrumentType:String="TYPE_STOCK"
)

@Entity(tableName="daily",primaryKeys=["insCode","date"])
data class DailyEntity(
    val insCode:String,val date:Int,val high:Double?,val last:Double?,
    val yesterday:Double?,val volume:Double?,val value:Double?
)

@Entity(tableName="queue_events",primaryKeys=["insCode","date"])
data class QueueEventEntity(
    val insCode:String,val date:Int,val eventTime:Int?,val queueValue:Double?,
    val score:Double,val status:String
)

@Entity(tableName="live_scores")
data class LiveScoreEntity(
    @PrimaryKey val insCode:String,
    val symbol:String?,
    val score:Double,
    val reason:String,
    val updatedAt:Long,
    val patternScore:Double=0.0,
    val technicalScore:Double=0.0,
    val volumeScore:Double=0.0,
    val rsi:Double?=null,
    val macd:Double?=null,
    val actorScore:Double=0.0,
    val lastPrice:Double=0.0
)

@Entity(tableName="paper_trades")
data class PaperTradeEntity(
    @PrimaryKey(autoGenerate=true) val id:Long=0,
    val insCode:String,
    val symbol:String?,
    val entryPrice:Double,
    val currentPrice:Double,
    val entryTime:Long,
    val exitTime:Long?,
    val exitPrice:Double?,
    val status:String,
    val entryScore:Double,
    val pnlPct:Double
)

data class QueueHistoryRow(
    val insCode:String,val symbol:String?,val date:Int,val eventTime:Int?,
    val queueValue:Double?,val score:Double,val status:String
)

@Dao
interface BorsaDao {
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsertSymbols(items:List<SymbolEntity>)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsertDaily(items:List<DailyEntity>)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsertEvents(items:List<QueueEventEntity>)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsertScores(items:List<LiveScoreEntity>)
    @Insert suspend fun insertPaperTrade(item:PaperTradeEntity):Long

    @Query("SELECT COUNT(*) FROM symbols") suspend fun symbolCount():Int
    @Query("SELECT COUNT(*) FROM daily") suspend fun dailyCount():Int
    @Query("SELECT COUNT(*) FROM queue_events WHERE status='CANDIDATE'") suspend fun candidateCount():Int
    @Query("SELECT COUNT(*) FROM queue_events WHERE status='QUEUE_CONFIRMED'") suspend fun confirmedCount():Int
    @Query("SELECT COUNT(*) FROM queue_events WHERE status='NOT_QUEUE'") suspend fun rejectedCount():Int
    @Query("SELECT COUNT(*) FROM queue_events WHERE status='ERROR'") suspend fun errorCount():Int
    @Query("SELECT MAX(date) FROM daily") suspend fun latestMarketDate():Int?
    @Query("SELECT MAX(date) FROM daily WHERE insCode=:insCode") suspend fun latestDateFor(insCode:String):Int?

    @Query("SELECT * FROM symbols ORDER BY COALESCE(symbol,name,insCode)")
    suspend fun allSymbols():List<SymbolEntity>

    @Query("SELECT * FROM symbols WHERE insCode=:insCode LIMIT 1")
    suspend fun symbolByCode(insCode:String):SymbolEntity?

    @Query("UPDATE symbols SET instrumentType=:type WHERE insCode=:insCode")
    suspend fun updateInstrumentType(insCode:String,type:String)

    @Query("""
      SELECT * FROM symbols
      WHERE symbol IS NULL OR TRIM(symbol)='' OR symbol=insCode OR symbol GLOB '[0-9]*'
      ORDER BY insCode LIMIT :limit
    """)
    suspend fun unknownSymbols(limit:Int):List<SymbolEntity>

    @Query("""
      SELECT * FROM live_scores
      WHERE insCode NOT IN (SELECT insCode FROM symbols)
         OR symbol IS NULL OR TRIM(symbol)=''
      ORDER BY updatedAt DESC LIMIT :limit
    """)
    suspend fun liveScoresNeedingName(limit:Int):List<LiveScoreEntity>

    @Query("""
      UPDATE live_scores
      SET symbol=(SELECT COALESCE(NULLIF(symbol,''),NULLIF(name,'')) FROM symbols s WHERE s.insCode=live_scores.insCode)
      WHERE EXISTS(
        SELECT 1 FROM symbols s
        WHERE s.insCode=live_scores.insCode
          AND COALESCE(NULLIF(s.symbol,''),NULLIF(s.name,'')) IS NOT NULL
      )
    """)
    suspend fun repairLiveScoreNames()

    @Query("SELECT * FROM daily WHERE insCode=:insCode ORDER BY date DESC LIMIT :limit")
    suspend fun recentDaily(insCode:String,limit:Int=220):List<DailyEntity>

    @Query("""
      SELECT l.insCode AS insCode,
             COALESCE(NULLIF(s.symbol,''),NULLIF(s.name,''),NULLIF(l.symbol,''),'در حال تکمیل نام') AS symbol,
             l.score AS score,l.reason AS reason,l.updatedAt AS updatedAt,
             l.patternScore AS patternScore,l.technicalScore AS technicalScore,
             l.volumeScore AS volumeScore,l.rsi AS rsi,l.macd AS macd,
             l.actorScore AS actorScore,l.lastPrice AS lastPrice
      FROM live_scores l
      LEFT JOIN symbols s ON s.insCode=l.insCode
      WHERE COALESCE(s.segment,'OTHER') IN (:segments)
        AND COALESCE(s.instrumentType,'TYPE_STOCK') IN (:types)
      ORDER BY l.score DESC LIMIT 50
    """)
    suspend fun topScoresFor(segments:List<String>,types:List<String>):List<LiveScoreEntity>

    @Query("""
      SELECT e.* FROM queue_events e
      INNER JOIN symbols s ON s.insCode=e.insCode
      WHERE e.status='CANDIDATE'
        AND s.segment IN (:segments)
        AND s.instrumentType IN (:types)
      ORDER BY e.date DESC LIMIT :limit
    """)
    suspend fun candidateEventsFor(segments:List<String>,types:List<String>,limit:Int):List<QueueEventEntity>

    @Query("""
      SELECT COUNT(*) FROM queue_events e
      INNER JOIN symbols s ON s.insCode=e.insCode
      WHERE e.status='CANDIDATE'
        AND s.segment IN (:segments)
        AND s.instrumentType IN (:types)
    """)
    suspend fun candidateCountFor(segments:List<String>,types:List<String>):Int

    @Query("SELECT * FROM daily WHERE insCode=:insCode AND date=:date LIMIT 1")
    suspend fun dailyFor(insCode:String,date:Int):DailyEntity?

    @Query("UPDATE queue_events SET status='CANDIDATE' WHERE status='ERROR'")
    suspend fun retryErrors()

    @Query("""
      SELECT e.insCode AS insCode,
             COALESCE(NULLIF(s.symbol,''),NULLIF(s.name,''),'در حال تکمیل نام') AS symbol,
             e.date AS date,e.eventTime AS eventTime,e.queueValue AS queueValue,
             e.score AS score,e.status AS status
      FROM queue_events e
      INNER JOIN symbols s ON s.insCode=e.insCode
      WHERE e.status='QUEUE_CONFIRMED'
        AND s.segment IN (:segments)
        AND s.instrumentType IN (:types)
      ORDER BY e.date DESC,e.score DESC LIMIT :limit
    """)
    suspend fun confirmedHistoryFor(
        segments:List<String>,
        types:List<String>,
        limit:Int=1000
    ):List<QueueHistoryRow>

    @Query("SELECT * FROM paper_trades WHERE status='OPEN' AND insCode=:insCode LIMIT 1")
    suspend fun openPaperTrade(insCode:String):PaperTradeEntity?

    @Query("SELECT * FROM paper_trades ORDER BY entryTime DESC LIMIT :limit")
    suspend fun recentPaperTrades(limit:Int=100):List<PaperTradeEntity>

    @Query("""
      UPDATE paper_trades
      SET currentPrice=:price,pnlPct=:pnl
      WHERE id=:id
    """)
    suspend fun updatePaperTrade(id:Long,price:Double,pnl:Double)

    @Query("""
      UPDATE paper_trades
      SET currentPrice=:price,exitPrice=:price,exitTime=:exitTime,status='CLOSED',pnlPct=:pnl
      WHERE id=:id
    """)
    suspend fun closePaperTrade(id:Long,price:Double,exitTime:Long,pnl:Double)
}

@Database(
    entities=[
        SymbolEntity::class,DailyEntity::class,QueueEventEntity::class,
        LiveScoreEntity::class,PaperTradeEntity::class
    ],
    version=5,exportSchema=false
)
abstract class AppDatabase:RoomDatabase(){ abstract fun dao():BorsaDao }
